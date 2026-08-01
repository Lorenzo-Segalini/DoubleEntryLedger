# ADR-0005 — Currency is modelled from day one; a transaction may not mix currencies

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

The demo runs in EUR. Two questions follow: does currency belong in the schema
now, and if so, may a single entry contain lines in more than one currency?

On the first: retrofitting currency into a ledger is one of the more painful
migrations available. Every amount column, every index, every balance query and
every report gains a dimension, and the migration has to invent a currency for
historical rows.

On the second, the trap is subtler. If lines in different currencies may share
an entry, then `SUM(signed_amount_minor) = 0` — the definition of double entry —
compares numbers that are not commensurable. An entry with `+100 EUR` and
`−100 JPY` would satisfy the constraint and mean nothing. The system's central
invariant would silently degrade into arithmetic on unrelated units.

## Decision

**Currency is present everywhere from the first migration**: on `account`, on
`journal_entry`, on `journal_line`.

**All lines of an entry must share one currency**, enforced by:

- The deferred constraint trigger, which rejects `count(DISTINCT currency) > 1`
  ([§2.5](../02-data-model.md#25-enforcing-every-entry-balances-i1-i2-i4)).
- The composite foreign key `journal_line (account_id, currency) → account (id, currency)`,
  which makes it impossible for a line to reference an account at a currency
  that account does not hold.

FX rates, revaluation and cross-currency transfers are **not** in v1.

## Consequences

**Good**

- No schema migration when multi-currency arrives — only new tables (`fx_rate`)
  and new logic.
- The balance invariant stays meaningful: every entry sums to zero in one unit.
- The constraint forces the correct model for cross-currency movements when they
  arrive. A currency exchange is not one entry with mixed lines; it is two
  balanced entries — one per currency — linked by a `transfer_group_id` and
  meeting at an FX clearing account. Each remains internally verifiable, and the
  rate applied is explicit rather than implied by an entry that happens to
  "balance". This is standard practice in production ledgers, and it is what the
  constraint makes unavoidable rather than merely recommended.
- Per-currency trial balances work in v1, since every entry belongs to exactly
  one currency.

**Costs**

- Columns that carry no variation in the demo, and slightly wider indexes.
- A cross-currency operation requires two API calls in v1 (there is no
  cross-currency endpoint yet), or waits for phase 3.
- The composite foreign key requires a redundant-looking `UNIQUE (id, currency)`
  on `account`. Cheap, and it buys declarative enforcement of invariant I5.

## Alternatives considered

**Defer currency entirely; add it later.** Rejected: the migration cost is
disproportionate to the cost of carrying the columns now, and the entry-level
constraint — the part with real design value — could not be demonstrated at all.

**Allow mixed-currency entries with a per-currency balance check.** The trigger
would verify that each currency sums to zero independently. Technically
workable, but it makes "an entry" no longer a single financial fact, complicates
every report, and hides the FX rate inside an entry rather than stating it. The
two-linked-entries model is clearer and is what the accounting literature
describes.

**Full multi-currency in v1.** Rejected on scope. The accounting judgement —
which rate, on which date, into which account — deserves more care than a demo
timeline allows, and a visibly half-correct FX implementation would undermine
the parts of the project that are correct.

## References

- [Domain Model §1.2](../01-domain-model.md#12-account-types-and-normal-balance)
- [Data Model §2.4](../02-data-model.md#24-journal_line)
- [Roadmap §9.3](../09-roadmap.md#93-phase-3--full-multi-currency)
