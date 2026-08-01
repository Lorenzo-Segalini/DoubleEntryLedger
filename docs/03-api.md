# 3. API Design

REST over JSON, versioned in the path, described by an OpenAPI 3.1 document
generated from the code at build time and published at `/v3/api-docs` (Swagger
UI at `/swagger-ui.html`).

Base URL: `/api/v1`

## 3.1 Conventions

**Money on the wire.** Every monetary value is an object, never a bare number:

```json
{ "amountMinor": 12500, "currency": "EUR", "amount": "125.00" }
```

`amountMinor` is the integer count of minor units and is the **only** field the
server reads on input. `amount` is a formatted decimal *string* that responses
include for display convenience and requests ignore.

Sending a decimal where minor units are expected is a **`400`**, not a rounding
decision made on the caller's behalf. This needs saying because Jackson's
default is the opposite: `ACCEPT_FLOAT_AS_INT` is on, and `125.50` into a
`long` field truncates silently to `125` — money lost with no error anywhere.
The application disables it (`spring.jackson.deserialization.accept-float-as-int:
false`) and an API test asserts the rejection. See
[ADR-0002](adr/0002-integer-minor-units-for-money.md).

Null fields are omitted rather than serialised as `null`
(`default-property-inclusion: non_null`), so a non-reversal entry has no
`reversalOfEntryId` key at all.

**Errors** follow RFC 9457 `application/problem+json`:

```json
{
  "type":     "https://ledger.example.dev/problems/unbalanced-entry",
  "title":    "Journal entry does not balance",
  "status":   422,
  "detail":   "Debits total 10000, credits total 9000; difference 1000 EUR minor units.",
  "instance": "/api/v1/journal-entries",
  "requestId": "01JQ8Z5K3M9P2V7B",
  "errors": [
    { "pointer": "/lines/1/amountMinor", "code": "UNBALANCED", "message": "..." }
  ]
}
```

`requestId` is on every response (also as the `X-Request-Id` header) and is the
same value stored on `journal_entry.request_id`, so any row in the back office
can be traced back to the request and log line that created it.

**Status codes.** `201` for a posted entry, `200` for reads and idempotent
replays, `409` for a genuine conflict (idempotency key reuse, double reversal),
`422` for a request that is well-formed JSON but violates a domain rule, `400`
only for malformed syntax.

**Pagination** is cursor-based on `(effective_date, sequence_no)` — the same pair
the index is built on, so seeking is a range scan rather than a sort, and
`sequence_no` breaks the ties that arise because entries routinely share an
effective date.

Offset pagination over an append-only ledger is wrong in a way that looks fine in
testing: entries arrive while a user reads, every row shifts down, and page two
repeats rows from page one or skips them. `JournalPaginationIT` asserts exactly
that scenario — read a page, post five newer entries, read the rest — and
requires no row to appear twice or vanish.

```json
{ "items": [ ... ], "nextCursor": "eyJkIjoiMjAyNi0wNi0zMCIsInMiOjQwMTJ9", "hasMore": true }
```

**Time.** All timestamps are RFC 3339 UTC with `Z`. All business dates are
`YYYY-MM-DD`. The server has no notion of the client's timezone and does not
guess one.

## 3.2 Authentication and roles



`POST /api/v1/auth/login` exchanges credentials for a short-lived access JWT
(15 min, `Authorization: Bearer`) and a rotating refresh token delivered as an
`HttpOnly; Secure; SameSite=Strict` cookie. Access tokens are signed RS256; the
public key is served at `/.well-known/jwks.json`.

| Role | Read journal & balances | Post entries / reversals | Import & reconcile | Manage accounts & users |
|---|:--:|:--:|:--:|:--:|
| `AUDITOR` | ✅ | ❌ | read-only | ❌ |
| `OPERATOR` | ✅ | ✅ | ✅ | ❌ |
| `ADMIN` | ✅ | ✅ | ✅ | ✅ |

`AUDITOR` is the role that makes the point: it can see every entry, every
reversal and every audit event, and it is structurally incapable of changing any
of them. Authorisation is enforced with method-level `@PreAuthorize` on the
service layer, not only on controllers, so a new controller cannot accidentally
expose an unguarded path.

Every denied request writes an `audit_event` with `outcome = 'DENIED'`, and every
login failure records *why* — `bad-password` or `unknown-email` — even though the
caller is told neither. That asymmetry is the point: what a client may not learn,
an auditor may.

Two transaction details make this work, and both are the kind that fail silently
when they are wrong:

- The audit methods are `@Transactional(REQUIRES_NEW)` **on the public methods**,
  not on the shared private writer. Spring's proxy only intercepts calls arriving
  from outside the bean, so annotating a helper the class calls on `this` does
  nothing — and a denied login would roll back and take its own "denied" record
  with it.
- `refresh` is `@Transactional(noRollbackFor = AuthenticationFailedException)`.
  Detecting reuse revokes the family and then rejects the call; a plain
  `@Transactional` rolls the revocation back on the way out, leaving the
  attacker's session alive while the logs claim it was killed.

### Refresh token rotation

Every refresh mints a new token and marks the old one used, within one family per
login. Presenting an already-rotated token means two parties hold the same
credential and nothing can tell which is calling, so the whole family is revoked
and both are forced to log in again. The legitimate client is logged out too —
an inconvenience that beats an attacker with a live session.

Tokens are stored as SHA-256 hashes, never in the clear: a database dump should
not be a set of live credentials.

The public demo seeds three accounts with published credentials
(`auditor@demo.local`, `operator@demo.local`, `admin@demo.local`); passwords are
in the root README and injected from environment variables at boot.

## 3.3 Accounts

```
GET    /api/v1/accounts                     ?type=&currency=&status=&cursor=
POST   /api/v1/accounts                     ADMIN
GET    /api/v1/accounts/{id}
PATCH  /api/v1/accounts/{id}                ADMIN   (name, status, parent only)
GET    /api/v1/accounts/{id}/balance        ?asOf=YYYY-MM-DD
GET    /api/v1/accounts/{id}/movements      ?from=&to=&cursor=&limit=
```

`GET /accounts/{id}/balance`:

```json
{
  "accountId": "8f14...", "code": "1000", "name": "Cash at Bank",
  "type": "ASSET", "asOf": "2026-06-30",
  "balance":       { "amountMinor":  4821900, "currency": "EUR", "amount": "48219.00" },
  "signedBalance": { "amountMinor":  4821900, "currency": "EUR", "amount": "48219.00" },
  "totalDebit":    { "amountMinor": 12400000, "currency": "EUR", "amount": "124000.00" },
  "totalCredit":   { "amountMinor":  7578100, "currency": "EUR", "amount": "75781.00" },
  "lineCount": 1842,
  "derivedAt": "2026-08-01T10:22:03Z"
}
```

`balance` is the natural balance (positive means "the account has that much of
what it is supposed to hold"); `signedBalance` is the debit-positive figure.
Both are returned because reports need the first and arithmetic needs the second.

`PATCH` deliberately accepts only `name`, `status` and `parentId`. Type and
currency are absent from the schema, not merely rejected — the API does not
offer a shape that the database would refuse (§2.7).

## 3.4 Posting entries

The general endpoint takes n lines:

```http
POST /api/v1/journal-entries
Authorization: Bearer <token>
Idempotency-Key: 7c9e6679-7425-40de-944b-e07fc1f90ae7
Content-Type: application/json

{
  "effectiveDate": "2026-06-30",
  "description": "Card payment #4471 settled",
  "currency": "EUR",
  "externalRef": "psp:pay_3Nk8Qz",
  "lines": [
    { "accountCode": "1100", "direction": "DEBIT",  "amountMinor":  9710, "memo": "net settlement" },
    { "accountCode": "5000", "direction": "DEBIT",  "amountMinor":   290, "memo": "processor fee" },
    { "accountCode": "4000", "direction": "CREDIT", "amountMinor": 10000 }
  ]
}
```

```http
HTTP/1.1 201 Created
Location: /api/v1/journal-entries/3f2a...
X-Request-Id: 01JQ8Z5K3M9P2V7B
```

```json
{
  "id": "3f2a...", "sequenceNo": 4012,
  "effectiveDate": "2026-06-30", "postedAt": "2026-08-01T10:22:03Z",
  "description": "Card payment #4471 settled",
  "currency": "EUR", "source": "API", "externalRef": "psp:pay_3Nk8Qz",
  "reversalOfEntryId": null, "reversedByEntryId": null,
  "totalDebit":  { "amountMinor": 10000, "currency": "EUR", "amount": "100.00" },
  "totalCredit": { "amountMinor": 10000, "currency": "EUR", "amount": "100.00" },
  "lines": [
    { "lineNo": 1, "accountId": "…", "accountCode": "1100", "accountName": "Payment Processor Clearing",
      "direction": "DEBIT", "amount": { "amountMinor": 9710, "currency": "EUR", "amount": "97.10" }, "memo": "net settlement" }
  ],
  "createdBy": { "id": "…", "displayName": "Demo Operator" },
  "requestId": "01JQ8Z5K3M9P2V7B"
}
```

Validation, in order, with the first failure returned:

| Check | Status | Problem type |
|---|---|---|
| Unknown account code | `422` | `unknown-account` |
| Account archived | `422` | `account-archived` |
| Line currency ≠ account currency | `422` | `currency-mismatch` |
| Entry mixes currencies | `422` | `mixed-currency-entry` |
| Fewer than two lines | `422` | `insufficient-lines` |
| Any `amountMinor` ≤ 0 | `422` | `non-positive-amount` |
| Debits ≠ credits | `422` | `unbalanced-entry` |
| `effectiveDate` in the future | `422` | `postdated-entry` |
| Missing `Idempotency-Key` | `400` | `idempotency-key-required` |
| Key reused with a different body | `409` | `idempotency-key-conflict` |

`Idempotency-Key` is **required**, not optional, on every write endpoint. An
optional safety mechanism is one that gets omitted by the caller who most needed
it. Full semantics: [Idempotency](04-idempotency.md).

### Transfers

```
POST /api/v1/transfers
```

```json
{
  "effectiveDate": "2026-06-30",
  "fromAccountCode": "1000",
  "toAccountCode": "1100",
  "amountMinor": 250000,
  "currency": "EUR",
  "description": "Top-up processor float"
}
```

Sugar over the two-line case: it expands to `CREDIT from` / `DEBIT to` and runs
through the identical posting service, validation and idempotency path. It
exists because the two-account transfer is 90% of real traffic and forcing every
caller to hand-write a balanced line array invites arithmetic mistakes at the
edge. It returns the same entry representation as `POST /journal-entries`.

### Reversals

```
POST /api/v1/journal-entries/{id}/reversal
```

```json
{ "effectiveDate": "2026-07-02", "reason": "Duplicate settlement from PSP webhook retry" }
```

The server builds the mirrored lines itself; the request body cannot supply
lines. That is what guarantees invariant I9 — a reversal is *derived* from the
original, so it cannot be a partial or subtly different cancellation.

- `409 already-reversed` if a reversal exists (enforced by a unique index, so it
  holds under concurrency, not just under a read-then-write check).
- `422 reversal-of-reversal` — reversing a reversal is refused; the intent is
  almost always a fresh correcting entry, and allowing chains makes the audit
  view ambiguous.
- `reason` is mandatory and free text; it is what an auditor reads six months
  later.

### Reading entries

```
GET /api/v1/journal-entries            ?from=&to=&accountId=&source=&externalRef=&limit=&cursor=
GET /api/v1/journal-entries/{id}
GET /api/v1/journal-entries/{id}/audit
```

The list is cursor-paginated, most recent first:

```json
{ "items": [ … ], "nextCursor": "MjAyNi0wNi0zMHw0MDEy", "hasMore": true }
```

`nextCursor` is absent on the last page, so a caller never infers the end from a
short page — a page can be short because the limit was odd, or full and final.
`limit` defaults to 50 and is **capped** at 200 rather than rejected: a caller
asking for 10,000 wants as many as they can have, and refusing outright only
makes them retry.

`accountId` matches entries with at least one line on that account, using
`EXISTS` rather than a join, so an entry touching the account twice still appears
once.

A malformed cursor is a `400`, not a silent restart from the beginning — which
would look to a user like the list resetting itself.

`/audit` returns the full provenance of one entry: creator, role, request id,
idempotency key, source, the reversal relationship in both directions, and the
related `audit_event` rows. This is the endpoint behind the back office's "why
does this entry exist" panel.

## 3.5 Reports

```
GET /api/v1/reports/trial-balance   ?asOf=YYYY-MM-DD&currency=EUR
GET /api/v1/reports/account-summary ?from=&to=&type=
```

```json
{
  "asOf": "2026-06-30", "currency": "EUR",
  "rows": [
    { "accountCode": "1000", "accountName": "Cash at Bank", "type": "ASSET",
      "debit":  { "amountMinor": 12400000, "currency": "EUR", "amount": "124000.00" },
      "credit": { "amountMinor":  7578100, "currency": "EUR", "amount": "75781.00" },
      "balance":{ "amountMinor":  4821900, "currency": "EUR", "amount": "48219.00" } }
  ],
  "totalDebit":  { "amountMinor": 41200000, "currency": "EUR", "amount": "412000.00" },
  "totalCredit": { "amountMinor": 41200000, "currency": "EUR", "amount": "412000.00" },
  "outOfBalanceMinor": 0,
  "balanced": true
}
```

`outOfBalanceMinor` is always present and always computed. A trial balance that
only renders when it balances cannot tell you the one thing you would ever run
it to find out.

## 3.6 Reconciliation

```
POST /api/v1/reconciliations                        multipart: file + metadata   OPERATOR
GET  /api/v1/reconciliations                        ?accountId=&status=
GET  /api/v1/reconciliations/{id}
GET  /api/v1/reconciliations/{id}/report
GET  /api/v1/reconciliations/{id}/breaks             ?type=&status=
POST /api/v1/reconciliations/{id}/breaks/{breakId}/explain    OPERATOR
POST /api/v1/reconciliations/{id}/breaks/{breakId}/resolve    OPERATOR
```

`resolve` posts an adjusting journal entry through the ordinary posting service
and links it to the break. It requires its own `Idempotency-Key`, because
resolving a break is a money-moving operation like any other. Semantics and the
report shape: [Reconciliation](05-reconciliation.md).

## 3.7 Operational endpoints

```
GET /actuator/health                 liveness + readiness, public
GET /actuator/health/ledgerBalance   asserts invariant I7 across the whole journal
GET /actuator/info                   build version, git sha
GET /actuator/prometheus             ADMIN or network-restricted
GET /v3/api-docs                     OpenAPI 3.1
```

`ledgerBalance` running `SELECT SUM(signed_amount_minor) FROM journal_line` as a
health check means the deployment reports itself unhealthy if the ledger is ever
out of balance. It should be structurally impossible; publishing it as a health
check is how you find out if that belief is wrong.

## 3.8 Versioning and compatibility

`/api/v1` is frozen once the demo is public. Additive changes (new optional
fields, new endpoints) ship in place. Anything breaking goes to `/api/v2` with
both served in parallel.

CI enforces this: the OpenAPI document is generated on every PR and diffed
against the committed `docs/openapi/v1.yaml` with `oasdiff`. A breaking change
fails the build unless the PR also bumps the version — the check is described in
[Deployment §8.3](08-deployment.md#83-continuous-integration).

---

Next: [Idempotency](04-idempotency.md).
