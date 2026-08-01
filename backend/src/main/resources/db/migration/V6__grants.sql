-- V6 — Privilege separation.
--
-- This is the structural half of invariant I6. The trigger in V3 produces a
-- readable error; this makes the statement impossible for the application to
-- issue at all. Two mechanisms, failing independently, for one rule.
--
-- `ledger_app` is a NOLOGIN group role, not a login user. The application's
-- actual database user — whatever the platform provisions, `ledger` locally and
-- a Neon-managed role in production — is granted membership. That keeps this
-- migration out of the business of managing passwords, which belong in
-- `fly secrets`, not in version control.
--
-- Migrations themselves run as a separate role with DDL rights. That separation
-- is what keeps "the app can deploy schema changes" from collapsing into "the app
-- can delete history".
--
-- See docs/02-data-model.md §2.6 and docs/08-deployment.md §8.5.

-- ---------------------------------------------------------------------------
-- Roles
-- ---------------------------------------------------------------------------

DO $$ BEGIN
    CREATE ROLE ledger_app NOLOGIN;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

COMMENT ON ROLE ledger_app IS
    'Application privilege set: SELECT+INSERT on the journal, full DML elsewhere. '
    'Grant this to the runtime login user. See ADR-0001.';

-- The current migration user keeps ownership. Granting the group to it means a
-- local single-user setup (compose, Testcontainers) exercises the same privilege
-- matrix as production rather than a permissive shortcut.
DO $$ BEGIN
    EXECUTE format('GRANT ledger_app TO %I', current_user);
EXCEPTION
    WHEN duplicate_object THEN NULL;
    WHEN insufficient_privilege THEN
        RAISE NOTICE 'could not grant ledger_app to %; grant it manually', current_user;
END $$;

-- ---------------------------------------------------------------------------
-- Schema access
-- ---------------------------------------------------------------------------

GRANT USAGE ON SCHEMA public TO ledger_app;

-- PUBLIC gets CREATE on `public` in older PostgreSQL versions. Revoking it means
-- an application role cannot introduce tables that sit outside migrations.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- The journal: append-only, structurally
--
-- No UPDATE. No DELETE. No TRUNCATE. Not "not used" — not granted.
-- ---------------------------------------------------------------------------

REVOKE ALL ON journal_entry, journal_line FROM ledger_app;
GRANT SELECT, INSERT ON journal_entry, journal_line TO ledger_app;

-- audit_event is append-only for the same reason: it records what the journal
-- cannot, and a record that can be edited is not a record.
REVOKE ALL ON audit_event FROM ledger_app;
GRANT SELECT, INSERT ON audit_event TO ledger_app;

-- ---------------------------------------------------------------------------
-- Everything else
--
-- These tables hold opinions and configuration, not financial facts, so they are
-- legitimately mutable. Accounts still cannot be deleted: an account referenced
-- by history must remain resolvable, and archiving is the supported path.
-- ---------------------------------------------------------------------------

REVOKE ALL ON account FROM ledger_app;
GRANT SELECT, INSERT, UPDATE ON account TO ledger_app;

REVOKE ALL ON app_user FROM ledger_app;
GRANT SELECT, INSERT, UPDATE ON app_user TO ledger_app;

REVOKE ALL ON idempotency_record FROM ledger_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_record TO ledger_app;
COMMENT ON TABLE idempotency_record IS
    'DELETE is granted here and nowhere else in the write path: the TTL sweeper '
    'removes expired records. Losing one degrades a late retry to a 409 from the '
    'journal''s unique index — never to a duplicate posting.';

REVOKE ALL ON statement_import, statement_line, reconciliation_match, reconciliation_break
    FROM ledger_app;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON statement_import, statement_line, reconciliation_match, reconciliation_break
    TO ledger_app;

-- Identity columns need their sequences.
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledger_app;

-- ---------------------------------------------------------------------------
-- Future objects
--
-- Without this, a table added by a later migration would be invisible to the
-- application until someone remembered to grant it — and the failure would show
-- up at runtime, in production, as a permission error.
-- ---------------------------------------------------------------------------

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ledger_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ledger_app;
