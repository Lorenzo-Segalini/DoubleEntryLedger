# ADR-0006 — Hosting: Vercel + Fly.io + Neon

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

The project must be **permanently live** from a CV link. That imposes
constraints a throwaway deployment does not:

1. It must still work in twelve months without maintenance or a credit card
   expiring into a deletion.
2. Cost must be approximately zero, sustained.
3. It must deploy from `main` with no manual steps, and be reproducible from a
   clean checkout.
4. PostgreSQL must be a real managed Postgres — the schema depends on deferred
   constraint triggers, generated columns, partial indexes and `pg_trgm`.
5. Preview environments per pull request, since CI/CD is part of what the
   project demonstrates.

Requirement 1 eliminates more options than it appears to. Several popular free
tiers expire the database after 30–90 days, which turns a portfolio link into a
500 page at the worst possible moment.

## Decision

| Layer | Choice |
|---|---|
| Frontend | **Vercel** — static SPA, global CDN, automatic preview per PR |
| Backend | **Fly.io** — runs the Docker image, scale-to-zero, `fra` region |
| Database | **Neon** — serverless Postgres 17, non-expiring free tier, branching |
| Images | **GHCR** — free for public repos, same auth as the repository |

Region: both Fly and Neon in Frankfurt, so the app-to-database round trip stays
in single-digit milliseconds. A derived-balance design does more queries than a
cached one, and putting the two on different continents would make that a
visible problem.

## Rationale per component

**Neon** is the decisive choice. Its free tier does not expire, and its
**database branching** creates a copy-on-write branch in seconds. That is what
makes preview environments honest: each PR gets its own real database seeded
from the demo data, and a migration that breaks in preview breaks nothing else.
A preview sharing the production database is not a preview. Branching also makes
the nightly demo reset a `branches reset` rather than a destructive script
against live data — which matters, because the application deliberately has no
code path that can delete a journal entry
([ADR-0001](0001-append-only-journal.md)).

**Fly.io** runs the image we already build for CI and local Compose, so there is
no second packaging format. `auto_stop_machines = "suspend"` gives near-zero
cost at idle with a ~1–2 s resume rather than a cold JVM boot. Crucially, its
`release_command` runs Flyway on a one-off machine **before** new instances take
traffic — a failed migration aborts the deploy and the previous version keeps
serving. Running migrations from application startup instead means N instances
racing to migrate, with a failure leaving a half-migrated schema live.

**Vercel** for a static SPA needs no defence: push, preview URL, done. No serverless
functions are used, so there is no vendor coupling in the code — the frontend is
a `dist/` directory that any static host would serve.

## Consequences

**Good**

- €0/month at demo scale, with no expiry clock.
- One Docker image serves local Compose, CI E2E and production.
- Real preview environments, database included, torn down on PR close.
- Rollback is redeploying a previous SHA tag; no rebuild.
- Migrations gated before traffic, with two database roles preserved into
  production ([§8.5](../08-deployment.md#85-migrations-on-deploy)).

**Costs**

- Three providers, three dashboards, three secrets to rotate.
- Cold start after idle. Mitigated by a scheduled health ping during European
  daytime, and honestly stated in
  [Roadmap §9.6](../09-roadmap.md#96-known-limitations-of-the-live-demo).
- Free-tier limits (Neon compute hours, Fly bandwidth) would need attention
  under real traffic. Not a demo concern.
- Frontend and backend on different origins, so CORS must be configured
  explicitly. Arguably a benefit: it is the realistic deployment shape.

## Alternatives considered

**Render (app + database).** Simplest single-provider setup, and the free
Postgres instance is deleted after 30 days. Disqualified by requirement 1.

**Railway (app + database).** Excellent developer experience, no meaningful free
tier — usage-based from the start. Rejected on requirement 2, not on quality.

**Supabase for Postgres.** Comparable free tier, but pauses projects after a
week of inactivity, and its value is mostly in features (auth, realtime,
row-level security via PostgREST) this project deliberately implements itself.
Neon's branching is the more relevant capability here.

**Google Cloud Run + Cloud SQL.** Cloud Run is a good fit; Cloud SQL has no free
tier and the smallest instance runs ~€8/month. Rejected on cost. Cloud Run + Neon
was a close second to Fly and remains a straightforward swap, since the unit of
deployment is the same image.

**Single VPS with Docker Compose and Caddy.** Cheapest at scale and fully
portable, but it is a machine to patch and back up for years, and it demonstrates
nothing about CI/CD beyond `ssh` and `docker compose pull`.

## References

- [Deployment](../08-deployment.md)
