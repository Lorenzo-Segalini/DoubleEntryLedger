-- V1 — Enum types, users, the audit log, and the append-only guard function.
--
-- See docs/02-data-model.md §2.1 and §2.11.
--
-- ===========================================================================
-- Re-runnability
--
-- Flyway already runs each versioned migration exactly once per schema, tracked
-- in flyway_schema_history. Every statement here is *also* written to be safe to
-- execute again by hand — in DBeaver, in psql, against a database that already
-- has these objects. Nothing in this file drops, truncates or deletes anything.
--
-- The idioms used throughout the migration set:
--   extensions        CREATE EXTENSION IF NOT EXISTS
--   enum types        DO block trapping duplicate_object
--   tables            CREATE TABLE IF NOT EXISTS
--   indexes           CREATE INDEX IF NOT EXISTS
--   functions         CREATE OR REPLACE FUNCTION
--   triggers          CREATE OR REPLACE TRIGGER
--   constraint trig.  DO block guarded on pg_trigger (OR REPLACE is unsupported)
--
-- PostgreSQL has transactional DDL and Flyway wraps each migration in one
-- transaction, so a failure rolls the whole file back. A half-applied migration
-- — the case where CREATE TABLE IF NOT EXISTS would silently skip a table that
-- exists but is structurally wrong — cannot arise.
-- ===========================================================================

CREATE EXTENSION IF NOT EXISTS citext;

-- ---------------------------------------------------------------------------
-- Enumerated types
--
-- Native enums rather than lookup tables: these sets change at the speed of the
-- domain (approximately never), and having PostgreSQL reject a typo is worth
-- more than the flexibility of a join.
-- ---------------------------------------------------------------------------

DO $$ BEGIN
    CREATE TYPE account_type AS ENUM ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE account_status AS ENUM ('ACTIVE', 'ARCHIVED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE direction AS ENUM ('DEBIT', 'CREDIT');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE entry_source AS ENUM ('API', 'TRANSFER', 'REVERSAL', 'IMPORT', 'ADJUSTMENT', 'SEED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE app_role AS ENUM ('OPERATOR', 'AUDITOR', 'ADMIN');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ---------------------------------------------------------------------------
-- The append-only guard (invariant I6)
--
-- Defined here because both journal_entry/journal_line (V3) and audit_event
-- (below) attach it. It is the readable half of the enforcement; the structural
-- half is the REVOKE in V6. Two mechanisms for one rule is deliberate: the grant
-- makes the statement impossible to issue, the trigger explains why and keeps
-- protecting the data if a future migration widens the grant by mistake.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% on % is forbidden: the journal is append-only. '
                    'Post a reversing entry instead.', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation',
              HINT = 'POST /api/v1/journal-entries/{id}/reversal';
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION reject_mutation() IS
    'Blocks UPDATE and DELETE on append-only tables. See ADR-0001.';

-- ---------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         CITEXT      NOT NULL UNIQUE,
    display_name  TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    role          app_role    NOT NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT app_user_email_not_blank        CHECK (length(btrim(email::text)) > 0),
    CONSTRAINT app_user_display_name_not_blank CHECK (length(btrim(display_name)) > 0)
);

COMMENT ON COLUMN app_user.password_hash IS
    'bcrypt, cost 12. The seeded system principal gets a non-bcrypt sentinel that can never match.';

-- ---------------------------------------------------------------------------
-- Audit log
--
-- Covers what the journal cannot: logins, denied authorisation attempts, account
-- creation, break explanations, statement imports. Financial history lives in the
-- journal; this records who did what to the system. Append-only for the same reason.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS audit_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    actor_id    UUID        REFERENCES app_user (id),
    actor_role  app_role,
    action      TEXT        NOT NULL,
    target_type TEXT        NOT NULL,
    target_id   TEXT,
    request_id  TEXT        NOT NULL,
    ip_address  INET,
    outcome     TEXT        NOT NULL,
    detail      JSONB       NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT audit_outcome_known    CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    CONSTRAINT audit_action_not_blank CHECK (length(btrim(action)) > 0)
);

CREATE INDEX IF NOT EXISTS audit_target_idx     ON audit_event (target_type, target_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS audit_actor_time_idx ON audit_event (actor_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS audit_request_idx    ON audit_event (request_id);

CREATE OR REPLACE TRIGGER audit_event_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
