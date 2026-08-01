-- V3 — The journal. This is the only source of financial truth in the system.
--
-- See docs/02-data-model.md §2.3 to §2.6, and ADR-0001.

CREATE TABLE IF NOT EXISTS journal_entry (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_no          BIGINT GENERATED ALWAYS AS IDENTITY,

    -- Two dates, answering different questions. effective_date is when it
    -- happened in business terms and drives balances and reporting; posted_at is
    -- when we learned about it and drives the audit trail. The gap between them
    -- is what reconciliation classifies as a timing difference rather than an
    -- error — see docs/01-domain-model.md §1.5.
    effective_date       DATE         NOT NULL,
    posted_at            TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),

    description          TEXT         NOT NULL,
    currency             CHAR(3)      NOT NULL,
    source               entry_source NOT NULL,
    external_ref         TEXT,
    idempotency_key      TEXT,
    reversal_of_entry_id UUID         REFERENCES journal_entry (id),
    reversal_reason      TEXT,
    created_by           UUID         NOT NULL REFERENCES app_user (id),

    -- Correlation id from the HTTP layer, also present in logs and traces. Given
    -- an entry in the back office you can find the exact request that produced it.
    request_id           TEXT         NOT NULL,

    CONSTRAINT entry_description_not_blank CHECK (length(btrim(description)) > 0),
    CONSTRAINT entry_currency_iso          CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT entry_request_id_not_blank  CHECK (length(btrim(request_id)) > 0),

    -- Backdating is allowed and audited. Postdating is not: an entry cannot be
    -- effective in the future.
    CONSTRAINT entry_not_postdated         CHECK (effective_date <= (posted_at AT TIME ZONE 'UTC')::date),

    CONSTRAINT entry_not_self_reversal     CHECK (id IS DISTINCT FROM reversal_of_entry_id),
    CONSTRAINT entry_reversal_has_reason   CHECK (
        (reversal_of_entry_id IS NULL) = (reversal_reason IS NULL)
    )
);

-- I8: an entry can be reversed at most once. A unique index rather than a
-- read-then-write check, so it holds under concurrency.
CREATE UNIQUE INDEX IF NOT EXISTS entry_single_reversal_idx
    ON journal_entry (reversal_of_entry_id)
    WHERE reversal_of_entry_id IS NOT NULL;

-- Second line of defence behind the idempotency store (V4). If that store were
-- bypassed, misconfigured or swept by TTL, the journal itself still refuses the
-- duplicate. See docs/04-idempotency.md §4.6.
CREATE UNIQUE INDEX IF NOT EXISTS entry_idempotency_key_idx
    ON journal_entry (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS entry_effective_date_idx ON journal_entry (effective_date, sequence_no);
CREATE INDEX IF NOT EXISTS entry_posted_at_idx      ON journal_entry (posted_at DESC);
CREATE INDEX IF NOT EXISTS entry_external_ref_idx   ON journal_entry (external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX IF NOT EXISTS entry_source_idx         ON journal_entry (source, effective_date);

CREATE TABLE IF NOT EXISTS journal_line (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id     UUID      NOT NULL REFERENCES journal_entry (id),
    line_no      SMALLINT  NOT NULL,
    account_id   UUID      NOT NULL,
    direction    direction NOT NULL,
    amount_minor BIGINT    NOT NULL,
    currency     CHAR(3)   NOT NULL,
    memo         TEXT,

    -- Denormalised from journal_entry. Normally a copied column is a liability
    -- because it can go stale; here it cannot, since invariant I6 means neither
    -- row ever changes after insert. In exchange, every balance-as-of query drops
    -- a join against what will be the largest table in the database.
    -- The deferred trigger below verifies it matches the entry.
    effective_date DATE    NOT NULL,

    -- Generated, so it cannot drift from direction and amount_minor. Debit is
    -- positive, credit negative, which makes every balance query a plain SUM()
    -- with no CASE and lets the covering index answer it without the heap.
    signed_amount_minor BIGINT NOT NULL GENERATED ALWAYS AS (
        CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END
    ) STORED,

    -- I3: the sign lives in `direction`. A negative debit is not a thing an
    -- accountant would write, and allowing it would give every amount two
    -- representations — which means two code paths, and eventually a disagreement.
    CONSTRAINT line_amount_positive  CHECK (amount_minor > 0),
    CONSTRAINT line_no_positive      CHECK (line_no > 0),
    CONSTRAINT line_unique_per_entry UNIQUE (entry_id, line_no),

    -- I5, declaratively: a line can only reference an account at that account's
    -- own currency.
    CONSTRAINT line_account_currency_fk
        FOREIGN KEY (account_id, currency) REFERENCES account (id, currency)
);

CREATE INDEX IF NOT EXISTS line_entry_idx   ON journal_line (entry_id, line_no);

-- Covering index: makes balance-as-of an index-only scan.
CREATE INDEX IF NOT EXISTS line_account_idx ON journal_line (account_id, effective_date, id)
                              INCLUDE (signed_amount_minor);

-- ---------------------------------------------------------------------------
-- Invariants I1, I2, I4 — enforced at COMMIT
--
-- A CHECK constraint sees one row. Balancing spans the whole set of lines for an
-- entry, and those lines do not all exist until the insert is finished. A
-- DEFERRABLE INITIALLY DEFERRED constraint trigger fires once at COMMIT: the
-- entry is transiently unbalanced during insertion, and an unbalanced entry can
-- never reach durable storage by any route — not the API, not a migration, not
-- someone with a psql prompt.
--
-- The application also validates balance before opening a transaction, so callers
-- get a clean 422 with field pointers. This is the backstop, not the UX.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION assert_entry_is_balanced() RETURNS TRIGGER AS $$
DECLARE
    v_line_count     INT;
    v_signed_total   BIGINT;
    v_currencies     INT;
    v_entry_currency CHAR(3);
    v_entry_date     DATE;
    v_date_mismatch  INT;
BEGIN
    -- The entry may have been rolled back; nothing to check if it is gone.
    SELECT currency, effective_date INTO v_entry_currency, v_entry_date
      FROM journal_entry WHERE id = NEW.entry_id;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT count(*),
           COALESCE(sum(signed_amount_minor), 0),
           count(DISTINCT currency),
           count(*) FILTER (WHERE effective_date <> v_entry_date)
      INTO v_line_count, v_signed_total, v_currencies, v_date_mismatch
      FROM journal_line
     WHERE entry_id = NEW.entry_id;

    IF v_line_count < 2 THEN                                              -- I2
        RAISE EXCEPTION 'entry % has % line(s); at least 2 required',
            NEW.entry_id, v_line_count
            USING ERRCODE = 'check_violation';
    END IF;

    IF v_signed_total <> 0 THEN                                           -- I1
        RAISE EXCEPTION 'entry % is unbalanced by % minor units',
            NEW.entry_id, v_signed_total
            USING ERRCODE = 'check_violation',
                  HINT = 'Debits must equal credits.';
    END IF;

    IF v_currencies > 1 THEN                                              -- I4
        RAISE EXCEPTION 'entry % mixes % currencies', NEW.entry_id, v_currencies
            USING ERRCODE = 'check_violation',
                  HINT = 'Model a currency exchange as two linked balanced entries. See ADR-0005.';
    END IF;

    IF v_entry_currency <> (SELECT currency FROM journal_line WHERE entry_id = NEW.entry_id LIMIT 1) THEN
        RAISE EXCEPTION 'entry % declares currency % but its lines do not',
            NEW.entry_id, v_entry_currency
            USING ERRCODE = 'check_violation';
    END IF;

    IF v_date_mismatch > 0 THEN
        RAISE EXCEPTION 'entry % has % line(s) whose effective_date differs from the entry',
            NEW.entry_id, v_date_mismatch
            USING ERRCODE = 'check_violation',
                  HINT = 'journal_line.effective_date is a denormalised copy and must match.';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- PostgreSQL rejects CREATE OR REPLACE CONSTRAINT TRIGGER, so this is guarded on
-- the catalogue rather than dropped and recreated: re-running must never leave a
-- window, however brief, in which the balance check is not attached.
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'journal_line_balanced'
           AND tgrelid = 'journal_line'::regclass
           AND NOT tgisinternal
    ) THEN
        CREATE CONSTRAINT TRIGGER journal_line_balanced
            AFTER INSERT ON journal_line
            DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION assert_entry_is_balanced();
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- I6 — append-only
-- ---------------------------------------------------------------------------

CREATE OR REPLACE TRIGGER journal_entry_immutable
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE OR REPLACE TRIGGER journal_line_immutable
    BEFORE UPDATE OR DELETE ON journal_line
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
