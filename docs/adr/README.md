# Architecture Decision Records

Each record captures one decision, the context that forced it, and what it costs.
The cost sections are not filler — a decision without a stated downside has
usually not been made.

Format: [Michael Nygard's](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).
Records are immutable once accepted; a change means a new record that supersedes
an old one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-append-only-journal.md) | The journal is append-only; corrections are reversals | Accepted |
| [0002](0002-integer-minor-units-for-money.md) | Money is an integer count of minor units | Accepted |
| [0003](0003-balances-are-derived.md) | Balances are derived from entries, never stored | Accepted |
| [0004](0004-idempotency-keys-on-write-endpoints.md) | Idempotency keys are required on every write endpoint | Accepted |
| [0005](0005-single-currency-per-transaction.md) | Currency modelled from day one; no mixing within a transaction | Accepted |
| [0006](0006-hosting-topology.md) | Hosting: Vercel + Fly.io + Neon | Accepted |
| [0007](0007-jwt-authentication-and-rbac.md) | Stateless JWT authentication with three roles | Accepted |
| [0008](0008-invariants-enforced-in-database-and-tests.md) | Invariants enforced in the database and proven by tests | Accepted |
| [0009](0009-monorepo.md) | Frontend and backend live in one repository | Accepted |

## Adding a record

```
docs/adr/NNNN-short-kebab-title.md
```

Sections: Status, Date, Context, Decision, Consequences (good **and** costs),
Alternatives considered, References. If the "Alternatives considered" section is
empty, the decision was probably not a decision.
