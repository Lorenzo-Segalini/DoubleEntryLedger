/**
 * Money as an integer count of minor units, never a float.
 *
 * The whole point of this module is that division by the currency's minor-unit
 * exponent happens in exactly one place — `format` — and multiplication by it in
 * exactly one other — `parse`. Everywhere else, an amount is an integer and
 * JavaScript's float semantics never get an opportunity to apply.
 *
 * See docs/06-frontend.md §6.2 and docs/adr/0002-integer-minor-units-for-money.md
 */

/** ISO-4217 alphabetic code. */
export type CurrencyCode = string & { readonly __currency: unique symbol }

/**
 * An integer count of a currency's smallest unit.
 *
 * Branded so a raw `number` cannot be passed where an amount is expected: the
 * compiler rejects `{ amountMinor: 12.5 }` as surely as it rejects a string.
 */
export type Minor = number & { readonly __brand: unique symbol }

export interface Money {
  readonly amountMinor: Minor
  readonly currency: CurrencyCode
}

export class CurrencyMismatchError extends Error {
  constructor(a: CurrencyCode, b: CurrencyCode) {
    super(`cannot combine ${a} and ${b}`)
    this.name = 'CurrencyMismatchError'
  }
}

export class MoneyParseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'MoneyParseError'
  }
}

const CURRENCY_PATTERN = /^[A-Z]{3}$/

export function currency(code: string): CurrencyCode {
  if (!CURRENCY_PATTERN.test(code)) {
    throw new MoneyParseError(`not an ISO-4217 code: ${code}`)
  }
  return code as CurrencyCode
}

/**
 * Wraps an integer as minor units.
 *
 * Rejects non-integers rather than rounding: a fractional minor unit means the
 * caller has already done float arithmetic somewhere, and silently rounding it
 * away would hide the bug this type exists to prevent.
 */
export function minor(value: number): Minor {
  if (!Number.isSafeInteger(value)) {
    throw new MoneyParseError(`minor units must be a safe integer, got ${value}`)
  }
  return value as Minor
}

export function money(amountMinor: number, code: string): Money {
  return { amountMinor: minor(amountMinor), currency: currency(code) }
}

const exponentCache = new Map<string, number>()

/** Minor-unit exponent: 2 for EUR, 0 for JPY, 3 for TND. */
export function minorUnitExponent(code: CurrencyCode): number {
  const cached = exponentCache.get(code)
  if (cached !== undefined) return cached

  const digits =
    new Intl.NumberFormat('en', { style: 'currency', currency: code }).resolvedOptions()
      .maximumFractionDigits ?? 2
  exponentCache.set(code, digits)
  return digits
}

function requireSameCurrency(a: Money, b: Money): void {
  if (a.currency !== b.currency) throw new CurrencyMismatchError(a.currency, b.currency)
}

export function add(a: Money, b: Money): Money {
  requireSameCurrency(a, b)
  return { amountMinor: minor(a.amountMinor + b.amountMinor), currency: a.currency }
}

export function subtract(a: Money, b: Money): Money {
  requireSameCurrency(a, b)
  return { amountMinor: minor(a.amountMinor - b.amountMinor), currency: a.currency }
}

export function negate(a: Money): Money {
  return { amountMinor: minor(-a.amountMinor), currency: a.currency }
}

export function sum(amounts: readonly Money[], code: CurrencyCode): Money {
  return amounts.reduce<Money>((acc, m) => add(acc, m), { amountMinor: minor(0), currency: code })
}

export function isZero(a: Money): boolean {
  return a.amountMinor === 0
}

export function compare(a: Money, b: Money): number {
  requireSameCurrency(a, b)
  return Math.sign(a.amountMinor - b.amountMinor)
}

/** The only place an amount becomes a fractional number. */
export function format(m: Money, locale = 'it-IT'): string {
  return new Intl.NumberFormat(locale, { style: 'currency', currency: m.currency }).format(
    m.amountMinor / 10 ** minorUnitExponent(m.currency),
  )
}

/**
 * Parses user input into minor units.
 *
 * Rejects more decimal places than the currency allows instead of rounding: for
 * a data-entry tool, "1.005 EUR is not a valid amount" is a better answer than
 * quietly deciding whether that is 1.00 or 1.01.
 */
export function parse(input: string, code: CurrencyCode): Money {
  const trimmed = input.trim().replace(/\s/g, '').replace(',', '.')
  if (!/^-?\d+(\.\d+)?$/.test(trimmed)) {
    throw new MoneyParseError(`not a valid amount: ${input}`)
  }

  const exponent = minorUnitExponent(code)
  const [whole = '0', fraction = ''] = trimmed.split('.')

  if (fraction.length > exponent) {
    throw new MoneyParseError(
      `${code} allows ${exponent} decimal place(s), got ${fraction.length} in ${input}`,
    )
  }

  const negative = whole.startsWith('-')
  const digits = `${whole.replace('-', '')}${fraction.padEnd(exponent, '0')}`
  const value = Number(digits)

  if (!Number.isSafeInteger(value)) {
    throw new MoneyParseError(`amount out of safe integer range: ${input}`)
  }
  return { amountMinor: minor(negative ? -value : value), currency: code }
}
