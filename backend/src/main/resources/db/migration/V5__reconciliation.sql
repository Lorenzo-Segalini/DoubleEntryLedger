-- V5 — Reconciliation: statement imports, matches, and typed breaks.
--
-- Unlike the journal, these tables ARE mutable. That is consistent, not a
-- contradiction: they hold opinions about the world, not financial facts.
-- Resolving a break never edits the journal — it posts a new entry through the
-- ordinary posting service and records its id here.
--
-- See docs/05-reconciliation.md.

-- Trigram similarity backs the lowest-confidence matching pass.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

DO $$ BEGIN
    CREATE TYPE import_status AS ENUM ('PENDING', 'MATCHING', 'COMPLETED', 'FAILED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE break_type AS ENUM (
    'MISSING_IN_LEDGER',
    'MISSING_IN_STATEMENT',
    'AMOUNT_MISMATCH',
    'TIMING_DIFFERENCE',
    'DUPLICATE_IN_LEDGER',
    'DUPLICATE_IN_STATEMENT',
    'CURRENCY_MISMATCH'
);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE break_status AS ENUM ('OPEN', 'EXPLAINED', 'RESOLVED', 'WRITTEN_OFF');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ---------------------------------------------------------------------------
-- Imports
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS statement_import (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id            UUID          NOT NULL REFERENCES account (id),
    currency              CHAR(3)       NOT NULL,
    period_start          DATE          NOT NULL,
    period_end            DATE          NOT NULL,
    opening_balance_minor BIGINT        NOT NULL,
    closing_balance_minor BIGINT        NOT NULL,
    source_filename       TEXT          NOT NULL,
    content_sha256        BYTEA         NOT NULL,
    status                import_status NOT NULL DEFAULT 'PENDING',
    imported_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    imported_by           UUID          NOT NULL REFERENCES app_user (id),
    failure_reason        TEXT,

    CONSTRAINT import_period_ordered  CHECK (period_start <= period_end),
    CONSTRAINT import_currency_iso    CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT import_sha_is_sha256   CHECK (octet_length(content_sha256) = 32),
    CONSTRAINT import_failed_has_reason CHECK (
        status <> 'FAILED' OR failure_reason IS NOT NULL
    ),

    -- Idempotency by natural key: re-uploading the same file returns the existing
    -- import rather than creating a second one. The file's content is a better
    -- key than anything a client would invent.
    CONSTRAINT import_not_duplicate UNIQUE (account_id, content_sha256)
);

CREATE INDEX IF NOT EXISTS import_account_period_idx ON statement_import (account_id, period_end DESC);
CREATE INDEX IF NOT EXISTS import_status_idx         ON statement_import (status);

CREATE TABLE IF NOT EXISTS statement_line (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_id        UUID    NOT NULL REFERENCES statement_import (id) ON DELETE CASCADE,
    row_no           INT     NOT NULL,
    value_date       DATE    NOT NULL,

    -- Signed, from the bank's perspective: money leaving the account is negative.
    -- The importer maps this onto the ledger's convention for the account type.
    -- Getting that mapping wrong inverts every match, so it lives in one small
    -- class with a table-driven test per account type.
    amount_minor     BIGINT  NOT NULL,

    currency         CHAR(3) NOT NULL,
    description      TEXT    NOT NULL,
    external_id      TEXT,
    counterparty_ref TEXT,

    CONSTRAINT stmt_line_nonzero      CHECK (amount_minor <> 0),
    CONSTRAINT stmt_line_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT stmt_line_row_positive CHECK (row_no > 0),
    CONSTRAINT stmt_line_unique       UNIQUE (import_id, row_no)
);

CREATE INDEX IF NOT EXISTS stmt_line_match_idx ON statement_line (import_id, amount_minor, value_date);
CREATE INDEX IF NOT EXISTS stmt_line_ext_idx   ON statement_line (external_id) WHERE external_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS stmt_line_desc_trgm ON statement_line USING gin (description gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- Matches
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS reconciliation_match (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_id         UUID         NOT NULL REFERENCES statement_import (id) ON DELETE CASCADE,
    statement_line_id UUID         NOT NULL REFERENCES statement_line (id) ON DELETE CASCADE,
    journal_line_id   UUID         NOT NULL REFERENCES journal_line (id),
    rule              TEXT         NOT NULL,
    confidence        NUMERIC(4,3) NOT NULL,
    matched_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT match_confidence_range CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT match_rule_known CHECK (rule IN (
        'EXACT_REFERENCE', 'EXACT_AMOUNT_DATE', 'AMOUNT_DATE_WINDOW', 'FUZZY_DESCRIPTION', 'MANUAL'
    )),

    -- A match is one-to-one. Without this a single ledger line could "explain"
    -- two statement lines, and the difference would silently stop adding up.
    CONSTRAINT match_statement_line_once UNIQUE (import_id, statement_line_id),
    CONSTRAINT match_journal_line_once   UNIQUE (import_id, journal_line_id)
);

CREATE INDEX IF NOT EXISTS match_import_idx ON reconciliation_match (import_id, rule);

-- ---------------------------------------------------------------------------
-- Breaks
--
-- delta_minor on every break is what makes the report a bridge rather than a
-- list: the deltas of all open and explained breaks must sum exactly to the
-- difference between the ledger balance and the statement balance.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS reconciliation_break (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_id          UUID         NOT NULL REFERENCES statement_import (id) ON DELETE CASCADE,
    type               break_type   NOT NULL,
    status             break_status NOT NULL DEFAULT 'OPEN',
    statement_line_id  UUID         REFERENCES statement_line (id) ON DELETE CASCADE,
    journal_line_id    UUID         REFERENCES journal_line (id),
    delta_minor        BIGINT       NOT NULL,
    currency           CHAR(3)      NOT NULL,
    detail             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    explanation        TEXT,
    resolving_entry_id UUID         REFERENCES journal_entry (id),
    resolved_by        UUID         REFERENCES app_user (id),
    resolved_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT break_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT break_has_a_side CHECK (
        statement_line_id IS NOT NULL OR journal_line_id IS NOT NULL
    ),

    -- A resolution is a posted entry, never an edit. Recording one without it
    -- would let a break be closed with nothing behind it.
    CONSTRAINT break_resolution_complete CHECK (
        status NOT IN ('RESOLVED', 'WRITTEN_OFF')
        OR (resolving_entry_id IS NOT NULL AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
    ),

    CONSTRAINT break_explained_has_text CHECK (
        status NOT IN ('EXPLAINED', 'WRITTEN_OFF') OR explanation IS NOT NULL
    )
);

CREATE INDEX IF NOT EXISTS break_import_status_idx ON reconciliation_break (import_id, status, type);
CREATE INDEX IF NOT EXISTS break_journal_line_idx  ON reconciliation_break (journal_line_id)
                                     WHERE journal_line_id IS NOT NULL;
