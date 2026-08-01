# lib

Framework-free helpers.

- `money.ts` — the branded `Minor` type and every arithmetic operation on it.
  Division by the currency's minor-unit exponent happens **only** inside
  `format()`, so no float ever touches an amount elsewhere. See
  [docs/06-frontend.md §6.2](../../../docs/06-frontend.md#62-money-in-typescript).
- `dates.ts` — business dates (`YYYY-MM-DD`) kept distinct from instants.
- `cursor.ts` — opaque pagination cursors.
- `problem.ts` — RFC 9457 `application/problem+json` parsing into typed errors.
