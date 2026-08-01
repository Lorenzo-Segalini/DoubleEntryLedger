# Seed data

Curated demo fixtures, loaded by `R__demo_seed.sql`.

- `chart-of-accounts.csv` — the payments-business chart from
  [docs/01-domain-model.md §1.9](../../docs/01-domain-model.md#19-worked-example-the-demo-chart-of-accounts).
- `june-2026-bank.csv` — a bank statement containing four deliberate
  discrepancies (a duplicate, a timing difference, an amount mismatch and an
  unbooked bank charge) so the reconciliation report has something to explain.

The nightly reset job asserts this statement still produces exactly those four
breaks; if the seed drifts, the job fails rather than silently degrading the demo.
See [docs/08-deployment.md §8.10](../../docs/08-deployment.md#810-demo-data-lifecycle).
