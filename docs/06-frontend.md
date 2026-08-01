# 6. Back Office (Frontend)

A React + TypeScript operator console. Its job is to make the ledger's
properties visible: that balances come from entries, that nothing is edited, and
that every number can be traced to the fact that produced it.

## 6.1 Stack

| Concern | Choice | Why |
|---|---|---|
| Build | Vite | Fast, first-class TS, deploys to Vercel without configuration |
| Language | TypeScript, `strict: true` | Non-negotiable given the money types below |
| Routing | React Router | File-less, explicit routes; no framework lock-in for a SPA |
| Server state | TanStack Query | Caching, retries, and a clear boundary between server and client state |
| Forms | React Hook Form + Zod | One schema validates the form and narrows the type |
| API types | `openapi-typescript` generated from `/v3/api-docs` | Types come from the backend contract, not from hand-copied interfaces |
| Styling | Tailwind CSS | Fast to build a dense data UI; no component library to fight |
| Tables | TanStack Table | Headless; sorting and cursor pagination stay under our control |
| Lint | oxlint | Rust-based, ships with the Vite scaffold; fast enough to run on every save |
| Tests | Vitest + Testing Library + MSW | Component tests against mocked HTTP, not mocked modules |
| E2E | Playwright | Runs against a real backend in CI |

There is **no client-side state manager**. Nearly all state here is server
state; TanStack Query owns it. The rest (filters, selection) lives in the URL,
which also makes any back-office view shareable as a link.

## 6.2 Money in TypeScript

The frontend inherits the backend's rule and enforces it in the type system:

```ts
/** Integer minor units. Branded so a raw number cannot be passed by accident. */
export type Minor = number & { readonly __brand: 'Minor' };

export interface Money {
  readonly amountMinor: Minor;
  readonly currency: CurrencyCode;
}

export const add = (a: Money, b: Money): Money => {
  if (a.currency !== b.currency) throw new CurrencyMismatchError(a, b);
  return { amountMinor: (a.amountMinor + b.amountMinor) as Minor, currency: a.currency };
};

export const format = (m: Money, locale = 'it-IT'): string =>
  new Intl.NumberFormat(locale, { style: 'currency', currency: m.currency })
    .format(m.amountMinor / 10 ** minorUnitExponent(m.currency));
```

The division by the minor-unit exponent happens **only inside `format`**. No
arithmetic anywhere else in the app touches a fractional number, so JavaScript's
float semantics never get an opportunity to produce `0.1 + 0.2`. An oxlint rule
(`no-restricted-syntax`) forbids arithmetic operators on any expression typed
`Minor` outside `lib/money.ts`.

Input works the other way: the amount field is a text input parsed into minor
units by a single `parseMoney` function, which rejects more than the currency's
allowed decimal places rather than rounding silently.

## 6.3 Screens

### Dashboard
Trial balance summary with the `outOfBalance` figure displayed prominently — as
a green zero, which is the point. Total entries, recent postings, open
reconciliation breaks by type.

### Chart of Accounts
Tree by `parentId`, filterable by type and currency. Each row shows the derived
balance as of a date picker that defaults to today. Changing the date
re-derives; nothing is cached server-side, and the UI says so with a "derived
at" timestamp.

### Account Movements
The screen that carries the argument. A statement view with a running balance
column, and a header showing:

```
Opening balance (2026-06-01)          41,065.00
  + debits                           124,000.00
  − credits                           75,781.00
Closing balance (2026-06-30)          48,219.00   ← equals the sum of the rows below
```

Reversed entries render struck through with a link to their reversal; reversal
entries render with a link back and their reason inline. There is no edit
button anywhere in this view, and the empty space where one would be is
deliberate.

### Post Entry
An n-line form. Running debit and credit totals update as you type, with the
difference shown live and the submit button disabled until it is zero. The
client-side check mirrors the server's validation but is explicitly not trusted:
the same rule is enforced by the API and by a database trigger, and the UI shows
the server's `422` field pointers if they ever disagree.

An `Idempotency-Key` (a UUID v4) is generated once when the form opens, not per
submission. Double-clicking submit therefore sends the same key twice and the
second response is a replay — the exact scenario idempotency exists for,
reachable in the demo by double-clicking.

### Entry Detail / Audit Trail
Full provenance for one entry: lines, creator and role, `postedAt` vs
`effectiveDate`, source, request id, idempotency key, the reversal relationship
in both directions, and related audit events. This is the "why does this exist"
panel, backed by `GET /journal-entries/{id}/audit`.

### Reconciliation
- **Import** — CSV drop zone with a preview of the parsed rows and the
  statement's internal-consistency check shown *before* upload is confirmed.
- **Report** — the bridge from [§5.5](05-reconciliation.md#55-the-report) rendered
  as a waterfall: ledger closing on the left, one bar per break, statement
  closing on the right. A visual proof that the explanations add up to the
  difference.
- **Breaks** — grouped by type, each expandable to show the statement line and
  journal line side by side with the differing fields highlighted, plus the rule
  and confidence that produced (or failed to produce) the match. Actions:
  explain, resolve, write off.

### Login
Three demo role buttons that prefill credentials, so a visitor can see what
`AUDITOR` looks like — every write control absent, not merely disabled — in one
click.

## 6.4 Structure

```
frontend/src/
├── api/          generated types + typed fetch client + query hooks
├── lib/          money.ts, dates.ts, cursor.ts, problem.ts (RFC 9457 parsing)
├── components/   presentational, no data fetching
├── features/
│   ├── accounts/
│   ├── entries/
│   ├── reconciliation/
│   └── auth/
├── routes/       route components, composing features
└── test/         MSW handlers, render helpers
```

Data fetching lives in `features/*/api.ts` as TanStack Query hooks. Components
receive data as props and are trivially testable. `components/` never imports
from `features/`.

## 6.5 Auth handling

The access token is held **in memory only** — never `localStorage`, which is
readable by any injected script. The refresh token is an `HttpOnly` cookie the
JavaScript cannot see. On boot and on `401`, the client calls `/auth/refresh`
once; a single-flight promise ensures concurrent 401s trigger one refresh, not
one per request. Failure clears state and routes to login.

Role gating is a `<RequireRole>` wrapper plus a `useCan()` hook. The UI hides
what a role cannot do, and the server enforces it independently — the frontend's
gating is ergonomics, never security.

## 6.6 Testing

- **Unit** — `money.ts` gets exhaustive tests including JPY (0 decimals) and TND
  (3), parse rejection, and overflow at `Number.MAX_SAFE_INTEGER`.
- **Component** — Vitest + Testing Library against MSW handlers generated from
  the OpenAPI schema, so a backend contract change breaks the frontend tests in
  CI rather than in production.
- **E2E** — Playwright against a real backend and Postgres in Docker Compose:
  log in as operator, post a balanced entry, verify the balance moved by exactly
  that amount, attempt an unbalanced entry and assert the error, reverse an
  entry and verify the balance returns, log in as auditor and assert the post
  controls are absent from the DOM.
- **Accessibility** — `axe-core` in the Playwright run; keyboard-navigable
  tables and forms are a requirement, not a nice-to-have, for a data-entry tool.

---

Next: [Testing Strategy](07-testing.md).
