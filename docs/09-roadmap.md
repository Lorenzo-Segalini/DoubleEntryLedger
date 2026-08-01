# 9. Roadmap

What is in scope for v1, what is deliberately deferred, and what the deferred
items would cost. An honest boundary is more useful than a long feature list —
each deferred item below names the hook already present in the v1 design that
makes it additive rather than a rewrite.

## 9.1 In scope for v1

| Capability | Documented in |
|---|---|
| Append-only double-entry journal with derived balances | [§1](01-domain-model.md), [§2](02-data-model.md) |
| Corrections as reversals, never edits | [§1.4](01-domain-model.md#14-append-only-and-what-corrections-look-like) |
| Invariants enforced in domain, database and tests | [§2.5–2.7](02-data-model.md), [§7.2](07-testing.md#72-invariants-as-tests) |
| Idempotent write endpoints, safe under concurrent retry | [§4](04-idempotency.md) |
| Reconciliation with typed, classified, self-balancing breaks | [§5](05-reconciliation.md) |
| React + TypeScript back office with full audit trail | [§6](06-frontend.md) |
| JWT auth with `OPERATOR` / `AUDITOR` / `ADMIN` | [§3.2](03-api.md#32-authentication-and-roles) |
| Multi-currency **schema**, single currency per transaction | [ADR-0005](adr/0005-single-currency-per-transaction.md) |
| Optimistic locking on account metadata | [§2.2](02-data-model.md#22-account) |
| Versioned API with OpenAPI 3.1 and a CI breaking-change gate | [§3.8](03-api.md#38-versioning-and-compatibility) |
| Property-based testing of ledger invariants | [§7.3](07-testing.md#73-property-based-testing) |
| Observability: metrics, tracing, structured logs | [§8.9](08-deployment.md#89-observability) |
| Dockerised CI/CD to Fly.io + Neon + Vercel, preview envs | [§8](08-deployment.md) |

Several items the brief listed as nice-to-have are in v1 because deferring them
would have cost more than including them. Multi-currency columns are free now
and a painful migration later. Optimistic locking is one column. API versioning
is a path prefix on day one and a breaking change on day two.

## 9.2 Phase 2 — performance and reporting

### Balance snapshots
Periodic checkpoint per account per date; a balance becomes *nearest snapshot +
lines since*. Turns an `O(lines)` read into `O(lines since last snapshot)`.

The essential constraint: **snapshots are a cache, never a source of truth.** A
`rebuild-snapshots` command must be able to drop the table and reproduce it
exactly, and a scheduled job must verify snapshot against derived and alert on
any divergence. Building the derived version first is what makes this safe to
add — the correct answer stays available to check the fast one against.

*Hook already present:* every balance read goes through `BalanceQuery`, one
interface with one implementation.

### CSV and PDF export
Journal, account statements and reconciliation reports. CSV streams; PDF is
generated server-side so the layout is identical to what an auditor received
last quarter. Exports themselves are audited — who exported what, and when.

*Hook already present:* `audit_event` records non-financial actions.

### Period close
Freeze a date range: postings with an `effective_date` inside a closed period
are rejected unless made by an `ADMIN` with an explicit reopen. Requires a
`period` table and one predicate in the posting service.

## 9.3 Phase 3 — full multi-currency

v1 stores currency everywhere and forbids mixing it within an entry. The
remaining work is genuine accounting, not schema:

- An `fx_rate` table with rates valid over date ranges and a named source
- FX gain/loss accounts, and a revaluation run that posts the difference between
  historical and current rates as ordinary journal entries
- Cross-currency transfers modelled as **two balanced entries** linked by a
  `transfer_group_id` — one per currency, each internally balanced, hitting an
  FX clearing account. This is why [ADR-0005](adr/0005-single-currency-per-transaction.md)
  forbids mixing currencies inside one entry rather than merely discouraging it:
  the constraint forces the correct model instead of an entry that appears to
  balance only because two currencies were added together.
- Trial balance per currency, plus a reporting-currency consolidation

Deferred because the accounting judgement (which rate, on which date, into which
account) deserves more care than a demo timeline allows, and getting it visibly
half-right would be worse than not shipping it.

## 9.4 Phase 4 — integration

### Transactional outbox
An `outbox_event` table written in the same transaction as the journal entry,
with a relay publishing to a broker and marking rows sent. Guarantees at-least-once
delivery with no dual-write window, and consumers deduplicate on event id — the
same idempotency argument as [§4](04-idempotency.md), one layer out.

The `IN_PROGRESS` idempotency state in [§4.5](04-idempotency.md#45-the-in_progress-case)
exists partly for this: an asynchronous path needs it, and adding it later would
change the client contract.

### Webhooks
Signed (HMAC) delivery of `entry.posted`, `entry.reversed`,
`reconciliation.completed`, with exponential backoff and a replay endpoint.

### Bank format importers
MT940 and CAMT.053 alongside CSV. The reconciliation engine already consumes a
normalised internal shape, so this is parser work only — the matching pipeline
does not change.

## 9.5 Explicitly out of scope

Stated so the boundary is a decision rather than an omission:

- **A general accounting package.** No VAT, depreciation, payroll or statutory
  reporting. This is a ledger, not an ERP.
- **Multi-tenancy.** Adding a tenant discriminator to every table and every
  query is straightforward but invasive, and it would obscure the double-entry
  mechanics this project exists to demonstrate.
- **A public write API for untrusted clients.** Rate limiting, quotas and abuse
  handling are a different problem from ledger correctness.
- **Horizontal scaling of writes.** A single Postgres primary handles far more
  than this workload. Sharding a ledger is a genuinely hard problem and pretending
  to solve it at demo scale would be dishonest.

## 9.6 Known limitations of the live demo

- Fly's scale-to-zero means the first request after idle takes ~1–2 s
  ([§8.6](08-deployment.md#86-deploy-workflow)).
- Demo data resets nightly, so entries posted by visitors are transient
  ([§8.10](08-deployment.md#810-demo-data-lifecycle)).
- Neon's free tier is a single region (`eu-central-1`); latency from outside
  Europe reflects that, not the application.
- Demo credentials are public and every role is writable except `AUDITOR`. The
  demo is a demo.
