-- V9 — Allow a break that belongs to neither side.
--
-- Separate from V8 on purpose: PostgreSQL permits ALTER TYPE ... ADD VALUE inside
-- a transaction, but forbids *using* the new value in that same transaction. The
-- constraint below names it, so it has to wait for a transaction of its own —
-- which is exactly what a second Flyway migration is.
--
-- The change widens the rule rather than narrowing it: every break that satisfied
-- the old constraint still satisfies this one.

DO $$ BEGIN
    ALTER TABLE reconciliation_break DROP CONSTRAINT IF EXISTS break_has_a_side;

    ALTER TABLE reconciliation_break ADD CONSTRAINT break_has_a_side CHECK (
        -- An opening-balance disagreement is about the period, not about a row.
        type = 'OPENING_BALANCE_MISMATCH'
        OR statement_line_id IS NOT NULL
        OR journal_line_id IS NOT NULL
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
