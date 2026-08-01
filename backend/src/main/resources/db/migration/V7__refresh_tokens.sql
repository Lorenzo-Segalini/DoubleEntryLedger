-- V7 — Refresh tokens, with rotation and reuse detection.
--
-- Access tokens are stateless JWTs and cannot be revoked before they expire;
-- that is bounded to 15 minutes. Refresh tokens are the opposite: opaque, stored,
-- long-lived, and revocable — which is only useful if the storage can tell a
-- legitimate rotation from a stolen token being replayed.
--
-- Hence `family_id`. Each login starts a family; each refresh rotates within it.
-- Presenting a token that has already been rotated means two parties hold the
-- same credential, and the only safe reading is that one of them stole it, so the
-- entire family is revoked and both are forced to log in again.
--
-- See ADR-0007 and docs/03-api.md §3.2.

CREATE TABLE IF NOT EXISTS refresh_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id   UUID        NOT NULL,
    user_id     UUID        NOT NULL REFERENCES app_user (id),

    -- The token itself is never stored. A database dump is not a set of live
    -- credentials, and the sweeper cannot leak one it never had.
    token_hash  BYTEA       NOT NULL,

    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID        REFERENCES refresh_token (id),
    user_agent  TEXT,
    ip_address  INET,

    CONSTRAINT refresh_hash_is_sha256   CHECK (octet_length(token_hash) = 32),
    CONSTRAINT refresh_expires_after_issue CHECK (expires_at > issued_at),
    CONSTRAINT refresh_hash_unique      UNIQUE (token_hash)
);

CREATE INDEX IF NOT EXISTS refresh_family_idx  ON refresh_token (family_id);
CREATE INDEX IF NOT EXISTS refresh_user_idx    ON refresh_token (user_id, issued_at DESC);
CREATE INDEX IF NOT EXISTS refresh_expires_idx ON refresh_token (expires_at);

-- Live tokens: not used, not revoked, not expired.
CREATE INDEX IF NOT EXISTS refresh_live_idx
    ON refresh_token (token_hash)
    WHERE used_at IS NULL AND revoked_at IS NULL;

COMMENT ON COLUMN refresh_token.family_id IS
    'All tokens descended from one login. Reuse of a rotated token revokes the family.';

-- Grants for the application role, matching V6's matrix. DELETE is granted so the
-- sweeper can remove expired rows; this table holds credentials, not history.
DO $$ BEGIN
    EXECUTE 'REVOKE ALL ON refresh_token FROM ledger_app';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_token TO ledger_app';
EXCEPTION WHEN undefined_object THEN
    RAISE NOTICE 'role ledger_app is absent; skipping grants';
END $$;
