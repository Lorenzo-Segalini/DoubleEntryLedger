-- V4 — Idempotency store.
--
-- The guarantee does not come from checking whether a key exists — that is a read
-- followed by a write, and two concurrent requests can both read "absent" before
-- either writes. It comes from the PRIMARY KEY below: claiming a key is an INSERT,
-- PostgreSQL serialises it, exactly one caller wins, and the loser learns it lost
-- from the database rather than from a race it might have observed wrongly.
--
-- See docs/04-idempotency.md and ADR-0004.

DO $$ BEGIN
    CREATE TYPE idempotency_status AS ENUM ('IN_PROGRESS', 'COMPLETED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS idempotency_record (
    key                 TEXT               NOT NULL,
    endpoint            TEXT               NOT NULL,
    principal_id        UUID               NOT NULL REFERENCES app_user (id),

    -- SHA-256 over the canonicalised request body. Answers one question: is this
    -- the same request, or a different request that happens to reuse the key?
    -- Same key + different fingerprint is rejected with 409 and posts nothing —
    -- silently applying the second request would leave the caller believing both
    -- succeeded when exactly one did.
    request_fingerprint BYTEA              NOT NULL,

    status              idempotency_status NOT NULL DEFAULT 'IN_PROGRESS',
    response_status     SMALLINT,
    response_body       JSONB,
    entry_id            UUID               REFERENCES journal_entry (id),
    created_at          TIMESTAMPTZ        NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ        NOT NULL,

    -- Scoped by endpoint and principal so two clients choosing the same UUID
    -- cannot collide, and a key used for a transfer cannot short-circuit a
    -- different operation.
    PRIMARY KEY (key, endpoint, principal_id),

    CONSTRAINT idem_key_not_blank CHECK (length(btrim(key)) > 0),
    CONSTRAINT idem_fingerprint_is_sha256 CHECK (octet_length(request_fingerprint) = 32),

    -- A replay must be able to return the original response byte for byte, so a
    -- COMPLETED record without one is not a valid state.
    CONSTRAINT idem_completed_has_response CHECK (
        status <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL)
    ),
    CONSTRAINT idem_completed_has_timestamp CHECK (
        (status = 'COMPLETED') = (completed_at IS NOT NULL)
    ),
    CONSTRAINT idem_expires_after_creation CHECK (expires_at > created_at)
);

-- Drives the sweeper. Records expire after 24 hours: comfortably beyond any
-- realistic client retry schedule, and short enough to keep the table small.
-- After expiry the semantics degrade gracefully rather than dangerously — a very
-- late retry is no longer recognised as a replay, but the unique index on
-- journal_entry.idempotency_key still refuses to post it twice.
CREATE INDEX IF NOT EXISTS idem_expires_idx ON idempotency_record (expires_at);

CREATE INDEX IF NOT EXISTS idem_entry_idx ON idempotency_record (entry_id) WHERE entry_id IS NOT NULL;
