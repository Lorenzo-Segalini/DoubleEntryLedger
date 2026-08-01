# ADR-0009 — Frontend and backend live in one repository

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

The system has two deployable units — a Spring Boot service and a React SPA —
that ship to different hosts. They could live in one repository or two.

They share exactly one thing, and it is the thing that breaks: the API contract.
Nearly every feature touches both sides. Adding a field to a journal entry means
a DTO change, an OpenAPI change, a regenerated TypeScript type, and a component
change.

Split across two repositories, that is two pull requests with a partial ordering
nobody enforces, two review cycles, and a window during which `main` on one side
does not work with `main` on the other. The usual response is to version the
contract as a package and coordinate releases, which is real work with real
value — at a team size and release cadence this project does not have.

## Decision

One repository:

```
backend/    Spring Boot (Maven)
frontend/   React + TypeScript (Vite)
docs/       documentation and ADRs, including openapi/v1.yaml
infra/      fly.toml, compose.yaml, seed data
.github/    workflows
```

The two are **built and deployed independently**, via path-filtered workflows:

```yaml
on:
  push:
    branches: [main]
    paths: ['backend/**', 'infra/fly.toml', '.github/workflows/deploy-backend.yml']
```

A frontend-only change does not rebuild or redeploy the backend, and vice versa.
The E2E workflow is the deliberate exception — it triggers on either side,
because catching drift between them is its entire purpose.

No monorepo tooling (Nx, Turborepo, Bazel). Two build systems, each doing its
own job, coordinated by path filters. At two projects, a meta-build tool costs
more than it returns.

The API contract is committed at `docs/openapi/v1.yaml`, regenerated from the
backend in CI and diffed with `oasdiff`. The frontend generates its types from
that file. A contract change is therefore visible in the same diff as the code
that caused it, and a breaking change fails the build in the PR that introduces
it — not in production a week later.

## Consequences

**Good**

- A full-stack change is one commit, one PR, one review, one revert.
- `main` is always internally consistent; there is no version skew between
  repositories.
- E2E tests run against matching versions by construction.
- One issue tracker, one CI dashboard, one place a reader looks for the project
  — which matters for a repository whose audience is people evaluating it.
- The OpenAPI diff sits next to the change that produced it.

**Costs**

- Every clone fetches both projects. Irrelevant at this size.
- Workflows need path filters or CI does double work on every push. This is the
  main tax, and it is about six lines of YAML per workflow.
- A shared `main` means an unrelated red build can block a merge. Mitigated by
  independent required checks per path.
- Deploy pipelines must not assume the repository root is the build context —
  Docker builds use `context: ./backend`.

## Alternatives considered

**Two repositories with a published contract package.** The right answer when
the sides have separate release cadences, separate teams, or external consumers.
None of those apply here.

**Monorepo with Nx or Turborepo.** Their value is caching and task orchestration
across many packages. With two projects in different languages, `mvn verify` and
`pnpm build` triggered by path filters achieve the same result with no extra
concept to learn.

**Backend serving the built frontend as static resources.** One deployable, no
CORS, no separate host. Rejected because it gives up Vercel's CDN and per-PR
previews, and couples a frontend change to a full backend redeploy — the
opposite of what the path-filtered pipelines are for.

## References

- [Deployment §8.2](../08-deployment.md#82-monorepo-layout)
- [API §3.8](../03-api.md#38-versioning-and-compatibility)
