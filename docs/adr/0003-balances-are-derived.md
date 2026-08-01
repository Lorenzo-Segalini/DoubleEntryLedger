# ADR-0003 — Balances are derived from entries, never stored

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

Reading a balance is the most frequent operation in the system. Two options:

1. Keep a `balance` column on `account` and update it on every posting.
2. Derive it: `SUM(signed_amount_minor)` over the account's lines.

Option 1 is the fast one and the one most systems reach for. It also introduces
a second source of truth for the same fact. From that moment, the interesting
question is not "what is the balance" but "which of the two answers is right",
and there is no principled way to decide — the column and the journal are equally
authoritative by construction.

Stored balances drift. A failed transaction that updated the column but not the
lines, a bulk import that bypassed the service, a concurrent update lost to a
race, a bug in one of several posting paths. The drift is silent, it compounds,
and it is usually discovered by a customer.

The stored column also makes the write path contended: every posting to a hot
account updates the same row, serialising writes that append-only inserts would
have run concurrently.

## Decision

No balance is stored. Every balance is computed from `journal_line` at read
time.

The schema is shaped to make that cheap:

- `signed_amount_minor` is a **generated stored column**, so summation is a plain
  `SUM()` with no `CASE`.
- `effective_date` is denormalised onto `journal_line`, which is safe precisely
  because [ADR-0001](0001-append-only-journal.md) makes both rows immutable —
  a copied column cannot go stale if nothing can change. Balance-as-of queries
  therefore avoid joining the entry table.
- A covering index `(account_id, effective_date, id) INCLUDE (signed_amount_minor)`
  makes the common query an index-only scan.

Balance-as-of-date is a `WHERE effective_date <= :as_of` on the same query, so
historical reporting is the same code path as current balance — not a separate
implementation that can disagree with it.

## Consequences

**Good**

- One source of truth. The balance cannot disagree with the entries because it
  *is* the entries.
- Historical balances are free and exact.
- No write contention on account rows; postings to the same account run
  concurrently.
- Bugs in the posting path show up as a rejected entry (the deferred trigger),
  not as a silently wrong balance.

**Costs**

- Reads are `O(lines on the account)` rather than `O(1)`. At demo scale — tens
  of thousands of lines — the index-only scan is single-digit milliseconds. It
  does not scale indefinitely, and that is accepted for v1.
- A trial balance across all accounts scans the whole table. Acceptable for a
  report that runs on demand.

## When this stops being enough

Balance snapshots ([Roadmap §9.2](../09-roadmap.md#balance-snapshots)): a
periodic checkpoint per account, turning a read into *nearest snapshot + lines
since*.

The constraint that keeps this decision intact: **snapshots are a cache, never a
source of truth.** They must be droppable and rebuildable from the journal at
any time, and a scheduled job compares snapshot against derived and alerts on
divergence. Building the derived version first is what makes the optimisation
safe to add later — the correct answer remains available to check the fast one
against. Starting with the cache would have left nothing to check it with.

All balance reads already go through a single `BalanceQuery` interface, so the
change is one implementation, not a refactor.

## References

- [Data Model §2.8](../02-data-model.md#28-deriving-balances)
- [ADR-0001](0001-append-only-journal.md)
