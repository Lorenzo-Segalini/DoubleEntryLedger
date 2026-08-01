# Documentation

Design and specification for the double-entry ledger. Written before the code,
and kept as the reference the code is checked against.

## Reading order

| # | Document | What it answers |
|---|---|---|
| 1 | [Domain Model](01-domain-model.md) | What a ledger is here, and the nine invariants everything else protects |
| 2 | [Data Model](02-data-model.md) | The PostgreSQL schema, and how it enforces those invariants |
| 3 | [API Design](03-api.md) | Endpoints, money on the wire, errors, roles |
| 4 | [Idempotency](04-idempotency.md) | Why a retried transfer cannot post twice |
| 5 | [Reconciliation](05-reconciliation.md) | Matching against a bank statement and explaining every difference |
| 6 | [Back Office](06-frontend.md) | The React + TypeScript operator console |
| 7 | [Testing Strategy](07-testing.md) | How the invariants are proven rather than asserted |
| 8 | [Deployment & CI/CD](08-deployment.md) | Docker, GitHub Actions, Fly.io, Neon, Vercel |
| 9 | [Roadmap](09-roadmap.md) | What is in v1, what is deferred, and what is out of scope |

[Architecture Decision Records](adr/) — nine decisions with their context, costs
and rejected alternatives.

## Short version

If you read only two things, read
[§1.3 The invariants](01-domain-model.md#13-the-invariants) and
[ADR-0001](adr/0001-append-only-journal.md). Everything else follows from them:

- The journal is append-only. Corrections are reversals, not edits.
- Balances are derived by summing entries. There is no stored balance to drift.
- Money is an integer count of minor units, from the database to the browser.
- Every entry must balance, and the database refuses to commit one that does not.
- Every write endpoint requires an idempotency key, so a retry cannot post twice.
- Reconciliation explains a difference line by line, and the explanations must
  sum to the difference.
