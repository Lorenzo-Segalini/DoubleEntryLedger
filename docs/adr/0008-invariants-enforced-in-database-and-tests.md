# ADR-0008 — Invariants are enforced in the database and proven by tests

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

"Every entry balances" can live in several places:

1. A comment, or a code-review habit.
2. A check in the service layer.
3. A database constraint.
4. A test.

Option 1 is not enforcement. Option 2 holds only for writes that go through that
service — not migrations, not a support script, not a second service added in a
hurry, not a `psql` session. Option 3 holds unconditionally but produces poor
error messages and cannot express everything. Option 4 proves the others work
but enforces nothing at runtime.

The failure mode being designed against is specific: an unbalanced entry that
reaches durable storage. Once it is committed, every subsequent report is wrong,
the append-only rule means it cannot be deleted, and the correction is itself an
awkward accounting exercise. Prevention is worth redundancy.

## Decision

Each invariant is enforced at **every layer where it can be**, and proven by a
test named after it.

| Layer | Role | Example |
|---|---|---|
| **Type system** | Make illegal states unrepresentable | `Money(long, Currency)`; no float reaches the domain |
| **Domain model** | Reject early, with a good message | `JournalEntry` validates balance in its constructor |
| **Service** | Cross-aggregate rules | Account exists, is active, currency matches |
| **Database** | Last line of defence, unconditional | Deferred constraint trigger; `CHECK`; composite FK; partial unique index |
| **Privileges** | Structural impossibility | `REVOKE UPDATE, DELETE` from the app role |
| **Tests** | Prove all of the above still hold | One test class per invariant |
| **ArchUnit** | Prevent decay as code is added | New `@PostMapping` without an idempotency key fails the build |

The full mapping is in
[Domain Model §1.3](../01-domain-model.md#13-the-invariants) and
[Testing §7.2](../07-testing.md#72-invariants-as-tests).

Three specifics that carry most of the weight:

**Deferred constraint triggers.** Balance is a property of a set of rows that
does not exist until the insert finishes. `DEFERRABLE INITIALLY DEFERRED` runs
the check at `COMMIT`, so an entry is transiently unbalanced during insertion and
an unbalanced entry can never become durable — by any route, including a manual
`psql` session.

**Generated columns.** `signed_amount_minor` and `balance_sign` are computed by
Postgres from `direction`/`amount_minor` and `type`. They cannot drift, because
no writer supplies them.

**Grants as a tested artefact.** An integration test asserts that `ledger_app`
holds only `SELECT` and `INSERT` on the journal tables. A future migration that
widens permissions fails CI even if no code uses the new privilege — the danger
of a broad grant is the code that does not exist yet.

## Consequences

**Good**

- An invariant violation is impossible, not merely unlikely. There is no
  write path — application, migration, script or console — that can commit an
  unbalanced entry.
- Errors surface where they are most useful: the domain model gives the caller a
  field-level `422`; the database backstops it.
- Tests are named after accounting rules, so a failure report says
  `EntryBalancesInvariantTest` rather than a method name.
- The rules are discoverable. A new contributor reading the schema learns the
  domain from it.

**Costs**

- The same rule is expressed in two or three languages (Java, SQL, PL/pgSQL) and
  can drift between them. Mitigated by testing the database's behaviour directly
  rather than the application's opinion of it — the tests in
  [§7.2](../07-testing.md#72-invariants-as-tests) issue raw SQL.
- Deferred triggers report failures at commit, which is less precise than a
  per-statement error. Handled by validating in the domain first, so the trigger
  is the backstop and not the user experience.
- PL/pgSQL is a language the team must maintain. Kept to three small functions.
- Tests must run against real PostgreSQL. H2 is unusable here, since half these
  mechanisms do not exist in it — this rules out the fast in-memory test suite
  some would expect, and that trade is accepted deliberately
  ([§7.1](../07-testing.md#71-layers)).

## Alternatives considered

**Application-layer only.** Faster to write and better error messages, but it
holds only for traffic through that layer. Ledgers outlive the applications that
write to them.

**Database-only.** Unconditional, but a `500` from a trigger is a poor API
response, and cross-aggregate rules do not fit in constraints.

**Rely on tests.** Tests prove the enforcement works; they enforce nothing at
runtime. They are a layer here, not the strategy.

## References

- [Domain Model §1.3](../01-domain-model.md#13-the-invariants)
- [Data Model §2.5–2.7](../02-data-model.md#25-enforcing-every-entry-balances-i1-i2-i4)
- [Testing](../07-testing.md)
