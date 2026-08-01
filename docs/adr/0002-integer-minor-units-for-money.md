# ADR-0002 — Money is an integer count of minor units

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

Money needs a representation in Java, in PostgreSQL, in JSON and in TypeScript.
The four have to agree, and the weakest link sets the guarantee.

`double` is out immediately: `0.1 + 0.2 = 0.30000000000000004`, and a ledger
whose totals depend on accumulation order is not a ledger.

`BigDecimal` (Java) over `NUMERIC(19,4)` (Postgres) is the usual answer, and it
is not wrong. But it has three properties that matter here:

1. Scale is per-value, not per-type. `new BigDecimal("1.50")` and
   `new BigDecimal("1.5")` are not `equals()`, which is a bug factory in tests
   and in `Map` keys.
2. Division requires an explicit `RoundingMode` at every call site, and the one
   that omits it throws `ArithmeticException` in production rather than at
   compile time.
3. It survives the trip through JSON badly. JavaScript has no decimal type, so
   a `NUMERIC` serialised as a JSON number becomes a `double` in the browser —
   the exact problem `BigDecimal` was chosen to avoid, reintroduced at the API
   boundary where it is hardest to see.

The last point is decisive for a system with a TypeScript frontend.

## Decision

Money is `(long amountMinor, Currency currency)` — an integer count of the
currency's smallest unit, never separated from its currency.

- **Java:** a `Money` record. Arithmetic uses `Math.addExact` / `multiplyExact`,
  so overflow throws instead of wrapping into a negative balance. Mixing
  currencies throws.
- **PostgreSQL:** `BIGINT amount_minor` + `CHAR(3) currency`. `BIGINT` holds
  ±92 trillion euro at cent precision.
- **JSON:** `{ "amountMinor": 12500, "currency": "EUR", "amount": "125.00" }`.
  `amountMinor` is an integer well inside `Number.MAX_SAFE_INTEGER` and is the
  only field the server reads. `amount` is a formatted **string** for display,
  ignored on input. A JSON *number* in a money field is rejected with `422`.
- **TypeScript:** a branded `Minor = number & { __brand: 'Minor' }`, with
  arithmetic confined to `lib/money.ts` by an ESLint rule and division by the
  minor-unit exponent occurring only inside `format()`.

Currencies whose minor-unit exponent is not 2 (JPY 0, TND 3) are handled by
reading the exponent from `java.util.Currency` / `Intl.NumberFormat` at the
formatting boundary only. The stored integer is always the smallest unit for
that currency, so no code outside formatting needs to know the exponent.

## Consequences

**Good**

- Exact by construction. Addition and subtraction — which is all a ledger does —
  are integer operations with no rounding decisions at all.
- The invariant `SUM(signed_amount_minor) = 0` is exact integer arithmetic in
  Postgres, which is what lets it be a `CHECK`-grade constraint and a health
  check.
- Equality is value equality. No scale surprises in tests or maps.
- Survives JSON intact. The browser receives an integer and does integer maths.
- Overflow is loud (`ArithmeticException`) rather than silent.

**Costs**

- Every amount must be scaled at the edges. Mitigated by having exactly two
  places that scale: `format` and `parse`, both tested against all three
  exponent classes.
- Percentage operations (fees, VAT) need an explicit rounding policy. This is a
  cost of the domain, not of the representation — `BigDecimal` would demand the
  same decision, just less visibly.
- Amounts above ~92 trillion units overflow. Not a constraint this system will
  meet; the exception if it did is preferable to silent truncation.
- `amountMinor: 12500` is less readable than `125.00` when eyeballing a
  response. The redundant `amount` string in responses exists for exactly that.

## Alternatives considered

**`BigDecimal` + `NUMERIC(19,4)`.** Rejected for the three reasons above, chiefly
the JSON boundary. Reasonable in a Java-only system.

**Money as a decimal string everywhere (`"125.00"`).** Safe across the wire, but
every operation needs a parse, and it moves validation from the type system to
runtime.

**A money library (Joda-Money, JSR-354/Moneta).** Both are competent. Rejected
because the domain needs add, subtract, negate and compare — roughly forty lines
— and a dependency here would mostly be a mapping layer between the library's
types and the persistence and JSON representations that still had to be chosen.

## References

- [Domain Model §1.6](../01-domain-model.md#16-money)
- [API §3.1](../03-api.md#31-conventions)
- [Frontend §6.2](../06-frontend.md#62-money-in-typescript)
