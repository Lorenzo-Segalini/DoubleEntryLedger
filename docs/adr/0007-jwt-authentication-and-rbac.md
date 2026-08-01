# ADR-0007 — Stateless JWT authentication with three roles

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

The demo is public and writable. Without authentication, any visitor could post
entries, and the curated reconciliation data that makes the back office worth
looking at would be destroyed within days.

More importantly, a ledger's access model is part of its design. The role that
demonstrates this best is the auditor: someone who must see everything and
change nothing. A system where "read-only" is a UI convention rather than an
enforced boundary has not really made the argument.

The frontend is on Vercel and the backend on Fly.io — different origins — so the
token transport has to work cross-origin.

## Decision

**Stateless JWT** access tokens plus rotating refresh tokens.

- Access token: RS256, 15-minute lifetime, sent as `Authorization: Bearer`.
  Public key published at `/.well-known/jwks.json`.
- Refresh token: opaque, 7 days, delivered as an `HttpOnly; Secure;
  SameSite=Strict` cookie, rotated on every use with the previous value
  invalidated. Reuse of a rotated token revokes the whole family — the standard
  detection for a stolen refresh token.
- Access tokens are held **in memory only** on the client. Never `localStorage`,
  which any injected script can read.

**Three roles**, as a flat enum rather than a permission matrix:

| Role | Read | Post entries | Reconcile | Administer |
|---|:--:|:--:|:--:|:--:|
| `AUDITOR` | ✅ | ❌ | read-only | ❌ |
| `OPERATOR` | ✅ | ✅ | ✅ | ❌ |
| `ADMIN` | ✅ | ✅ | ✅ | ✅ |

Enforcement is `@PreAuthorize` on the **service** layer, not only on
controllers. A new controller that forgets its annotation still hits a guarded
service. Every denial writes an `audit_event` with `outcome = 'DENIED'`, so
attempted privilege violations are part of the record rather than a log line
that rotates away.

Passwords are bcrypt at cost 12. Demo credentials are published in the README
and injected from environment variables at boot, never written into a migration.

## Consequences

**Good**

- No session store, no sticky sessions; horizontal scaling and scale-to-zero
  work without shared state.
- Short access-token lifetime bounds the damage from a leaked token to 15
  minutes; the refresh cookie is unreachable from JavaScript.
- `AUDITOR` makes the append-only argument concrete and clickable: log in as
  auditor and every write control is absent from the DOM, while the API refuses
  the request independently.
- Service-layer authorisation means the guarantee does not depend on remembering
  to annotate each new endpoint.

**Costs**

- Access tokens cannot be revoked before expiry. Accepted: 15 minutes, and the
  refresh family can be revoked immediately.
- The refresh-rotation flow is genuinely fiddly on the client — concurrent 401s
  must trigger one refresh, not one per request. Solved with a single-flight
  promise ([§6.5](../06-frontend.md#65-auth-handling)) and covered by a test.
- Cross-origin cookies require correct `SameSite` and CORS configuration, which
  is easy to get subtly wrong. It is also the realistic production shape, so the
  configuration is worth having exercised.
- Three roles will not fit a real organisation. Fine for this scope; a
  permission-based model is the natural evolution and the enum is the only thing
  that would change.

## Alternatives considered

**Server-side sessions with a cookie.** Simpler, revocable instantly, and a
perfectly good choice — but it needs a shared session store the moment there is
more than one instance, which works against scale-to-zero.

**A managed identity provider (Auth0, Clerk, Supabase Auth).** Less code and
better security defaults. Rejected because it moves the interesting part —
role-based authorisation over ledger operations — into a vendor console where it
cannot be read in the repository, and it adds a fourth provider with its own
free-tier expiry risk.

**API keys only.** Adequate for machine callers, but the back office needs user
identity for the audit trail: `journal_entry.created_by` has to name a person.

**No authentication, read-only demo.** Would have removed the ability to
demonstrate posting, idempotency-under-retry and reconciliation resolution —
which is most of what there is to show.

## References

- [API §3.2](../03-api.md#32-authentication-and-roles)
- [Data Model §2.11](../02-data-model.md#211-users-roles-audit)
- [Frontend §6.5](../06-frontend.md#65-auth-handling)
