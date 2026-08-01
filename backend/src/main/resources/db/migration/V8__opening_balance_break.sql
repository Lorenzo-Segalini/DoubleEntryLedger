-- V8 — A break type for a disagreement about where the period started.
--
-- The bridge invariant is
--
--     ledger_closing + Σ(delta) = statement_closing
--
-- and closing = opening + movements on both sides. So the difference decomposes
-- into an opening-balance difference plus a movements difference. Classifying
-- only the movements leaves the opening gap unexplained, and the bridge cannot
-- close however good the matching is.
--
-- An opening mismatch usually means the previous period was never reconciled, or
-- was reconciled and its breaks left open. It is a real finding for an operator,
-- so it is a break rather than a silent adjustment.

ALTER TYPE break_type ADD VALUE IF NOT EXISTS 'OPENING_BALANCE_MISMATCH';
