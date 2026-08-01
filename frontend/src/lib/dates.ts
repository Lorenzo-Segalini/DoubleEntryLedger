/**
 * Business dates are calendar days, not instants.
 *
 * `effectiveDate` is a `YYYY-MM-DD` with no timezone, and parsing it with
 * `new Date()` would attach the browser's — turning 2026-06-30 into 29 June for
 * anyone west of UTC. These functions never construct a Date from one.
 */

export type BusinessDate = string

export function today(): BusinessDate {
  const now = new Date()
  return [
    now.getFullYear(),
    String(now.getMonth() + 1).padStart(2, '0'),
    String(now.getDate()).padStart(2, '0'),
  ].join('-')
}

export function formatBusinessDate(date: BusinessDate, locale = 'it-IT'): string {
  const [year, month, day] = date.split('-')
  if (!year || !month || !day) return date
  return new Intl.DateTimeFormat(locale, { day: '2-digit', month: 'short', year: 'numeric' }).format(
    new Date(Number(year), Number(month) - 1, Number(day)),
  )
}

/** An instant, on the other hand, genuinely is a point in time. */
export function formatInstant(instant: string, locale = 'it-IT'): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'medium' }).format(
    new Date(instant),
  )
}

export function isFuture(date: BusinessDate): boolean {
  return date > today()
}
