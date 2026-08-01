# ADR-0004 — Idempotency keys are required on every write endpoint

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

A client that does not receive a response cannot know whether its request was
applied. Timeouts, dropped connections and instance replacement all produce this
state, and it is ordinary, not exceptional. Both retrying and not retrying can
be wrong; without a mechanism, the safe-looking choice posts the money twice.

Duplicate postings are among the worst defects a ledger can have: the totals
still balance, so every internal consistency check passes, and the error is
found by a customer or by reconciliation weeks later.

## Decision

Every state-changing endpoint requires an `Idempotency-Key` header. A request
carrying a key is applied **at most once**; a repeat with the same body returns
the original response — same status, same body, same entry id.

Mechanism:

- `idempotency_record` keyed by `(key, endpoint, principal_id)`.
- The key is claimed with `INSERT … ON CONFLICT DO NOTHING`. **The primary key
  is the concurrency control**, not a preceding `SELECT`. Check-then-insert
  fails under exactly the conditions that matter, since a client timeout
  commonly fires while the original request is still running.
- The claim, the ledger write and the stored response are **one transaction**.
  There is no window where the entry exists and the record does not.
- A fingerprint (`SHA-256` of the canonicalised body) distinguishes a genuine
  retry from a key reused with different content. Same key + different body is
  `409`, never a second posting.
- `journal_entry.idempotency_key` carries an independent partial unique index as
  a second line of defence, so a duplicate is impossible even if the record was
  swept by TTL.
- Records expire after 24 hours.

**Required, not optional.** An optional safety mechanism is one that is omitted
by the caller who most needed it. An ArchUnit rule fails the build if a new
`@PostMapping` lacks an idempotency key parameter, so the guarantee does not
decay as endpoints are added.

## Consequences

**Good**

- Retries are safe by default, which is the only way retries are ever safe.
- Correctness rests on a database uniqueness constraint rather than on
  application timing.
- The stored response makes a replay byte-identical rather than a
  re-derivation that could differ.
- The two independent mechanisms fail differently: the store gives a *good*
  answer, the unique index guarantees a *safe* one.

**Costs**

- One extra table, one extra write per posting, and a sweeper job.
- Clients must generate and reuse a key across retries — a real integration
  burden, though the same one Stripe and every payments API impose.
- Response bodies are stored as `JSONB` for 24 hours, which duplicates data
  already in the journal.
- A retry arriving after expiry gets a `409` rather than a clean replay. A worse
  error message; never a duplicate posting.

## Alternatives considered

**Client-supplied unique business reference.** Works when one exists, but not
every posting has a natural key, and it conflates "identifies this transaction"
with "deduplicates this request".

**Server-side deduplication on a content hash within a time window.** Rejects
legitimate identical transactions — two customers paying the same amount for the
same product in the same minute is normal.

**`PUT` with a client-generated id.** Genuinely idempotent and architecturally
clean, but it lets the client choose primary keys and makes "return the original
response" awkward when the second `PUT` differs from the first.

**Optional key with a "strict mode" flag.** Rejected: see above. The mechanism is
only worth having if it is unconditional.

## References

- [Idempotency](../04-idempotency.md)
- [Data Model §2.9](../02-data-model.md#29-idempotency-store)
- [Testing §7.4](../07-testing.md#74-idempotency-under-concurrency)
