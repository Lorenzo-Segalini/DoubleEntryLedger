-- V2 — Chart of accounts.
--
-- See docs/02-data-model.md §2.2 and §2.7.

CREATE TABLE IF NOT EXISTS account (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         TEXT           NOT NULL,
    name         TEXT           NOT NULL,
    type         account_type   NOT NULL,
    currency     CHAR(3)        NOT NULL,
    status       account_status NOT NULL DEFAULT 'ACTIVE',
    parent_id    UUID           REFERENCES account (id),

    -- Generated, not supplied. The mapping from account type to normal balance is
    -- a property of accounting, so it is not something a caller gets to pass in
    -- and get wrong. natural_balance = signed_balance * balance_sign.
    balance_sign SMALLINT       NOT NULL GENERATED ALWAYS AS (
                     CASE WHEN type IN ('ASSET', 'EXPENSE') THEN 1 ELSE -1 END
                 ) STORED,

    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by   UUID           NOT NULL REFERENCES app_user (id),
    version      BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT account_code_unique       UNIQUE (code),
    CONSTRAINT account_code_not_blank    CHECK (length(btrim(code)) > 0),
    CONSTRAINT account_name_not_blank    CHECK (length(btrim(name)) > 0),
    CONSTRAINT account_currency_iso      CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT account_not_own_parent    CHECK (id IS DISTINCT FROM parent_id),

    -- Redundant next to the primary key, and deliberately so: it gives
    -- journal_line a composite foreign key target, which is what enforces
    -- invariant I5 declaratively — a line can only reference an account *at that
    -- account's own currency*.
    CONSTRAINT account_id_currency_unique UNIQUE (id, currency)
);

CREATE INDEX IF NOT EXISTS account_parent_idx   ON account (parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS account_type_idx     ON account (type, code);
CREATE INDEX IF NOT EXISTS account_currency_idx ON account (currency);

COMMENT ON COLUMN account.version IS
    'Optimistic locking for metadata edits. The journal needs none: append-only '
    'writes never contend on a row.';

-- ---------------------------------------------------------------------------
-- Type and currency freeze
--
-- Renaming an account is fine — the name is a label. Changing its type silently
-- rewrites the meaning of every historical line that touched it, so it is not.
--
-- The function references journal_line, created in V3. PL/pgSQL resolves table
-- names at execution time, so defining it here is safe and keeps the rule next
-- to the table it protects.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION reject_account_reinterpretation() RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.type <> OLD.type OR NEW.currency <> OLD.currency)
       AND EXISTS (SELECT 1 FROM journal_line WHERE account_id = OLD.id) THEN
        RAISE EXCEPTION 'account % has postings; type and currency are frozen', OLD.code
            USING ERRCODE = 'restrict_violation',
                  HINT = 'Archive this account and open a new one instead.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER account_type_currency_frozen
    BEFORE UPDATE ON account
    FOR EACH ROW EXECUTE FUNCTION reject_account_reinterpretation();
