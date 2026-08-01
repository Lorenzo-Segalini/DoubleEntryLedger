import { describe, expect, it } from 'vitest'
import {
  CurrencyMismatchError,
  MoneyParseError,
  add,
  compare,
  currency,
  format,
  minorUnitExponent,
  money,
  parse,
  subtract,
  sum,
} from '@/lib/money'

const EUR = currency('EUR')
const JPY = currency('JPY')
const TND = currency('TND')

describe('minorUnitExponent', () => {
  it('knows the three exponent classes', () => {
    expect(minorUnitExponent(EUR)).toBe(2)
    expect(minorUnitExponent(JPY)).toBe(0)
    expect(minorUnitExponent(TND)).toBe(3)
  })
})

describe('arithmetic', () => {
  it('is exact where floats are not', () => {
    // 0.1 + 0.2 !== 0.3 in float. In minor units it is just 10 + 20.
    expect(add(money(10, 'EUR'), money(20, 'EUR')).amountMinor).toBe(30)
  })

  it('sums a list without accumulating error', () => {
    const cents = Array.from({ length: 1000 }, () => money(1, 'EUR'))
    expect(sum(cents, EUR).amountMinor).toBe(1000)
  })

  it('refuses to combine currencies', () => {
    expect(() => add(money(100, 'EUR'), money(100, 'JPY'))).toThrow(CurrencyMismatchError)
    expect(() => subtract(money(100, 'EUR'), money(100, 'JPY'))).toThrow(CurrencyMismatchError)
    expect(() => compare(money(100, 'EUR'), money(100, 'JPY'))).toThrow(CurrencyMismatchError)
  })

  it('rejects a fractional minor unit rather than rounding it', () => {
    expect(() => money(12.5, 'EUR')).toThrow(MoneyParseError)
  })

  it('rejects amounts beyond safe integer range', () => {
    expect(() => money(Number.MAX_SAFE_INTEGER + 1, 'EUR')).toThrow(MoneyParseError)
  })
})

describe('parse', () => {
  it('reads decimals into minor units', () => {
    expect(parse('125.00', EUR).amountMinor).toBe(12_500)
    expect(parse('0.01', EUR).amountMinor).toBe(1)
    expect(parse('-42.5', EUR).amountMinor).toBe(-4_250)
  })

  it('accepts a comma as decimal separator', () => {
    expect(parse('125,00', EUR).amountMinor).toBe(12_500)
  })

  it('honours the currency exponent', () => {
    expect(parse('1000', JPY).amountMinor).toBe(1_000)
    expect(parse('1.234', TND).amountMinor).toBe(1_234)
  })

  it('rejects more decimals than the currency allows', () => {
    expect(() => parse('1.005', EUR)).toThrow(MoneyParseError)
    expect(() => parse('10.5', JPY)).toThrow(MoneyParseError)
  })

  it('rejects nonsense', () => {
    expect(() => parse('', EUR)).toThrow(MoneyParseError)
    expect(() => parse('abc', EUR)).toThrow(MoneyParseError)
    expect(() => parse('1.2.3', EUR)).toThrow(MoneyParseError)
  })
})

describe('format', () => {
  it('is the only place an amount becomes fractional', () => {
    expect(format(money(12_500, 'EUR'), 'en-IE')).toBe('€125.00')
    expect(format(money(1_000, 'JPY'), 'en-US')).toBe('¥1,000')
  })

  it('round-trips through parse without losing a cent', () => {
    for (const input of ['0.00', '0.01', '1.00', '999999.99']) {
      const parsed = parse(input, EUR)
      const reparsed = parse(format(parsed, 'en-IE').replace(/[^\d.-]/g, ''), EUR)
      expect(reparsed.amountMinor).toBe(parsed.amountMinor)
    }
  })
})

describe('currency', () => {
  it('rejects anything that is not an ISO-4217 code', () => {
    expect(() => currency('eur')).toThrow(MoneyParseError)
    expect(() => currency('EURO')).toThrow(MoneyParseError)
  })
})
