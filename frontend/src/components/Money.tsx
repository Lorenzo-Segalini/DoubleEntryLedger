import type { MoneyResponse } from '@/api/types'

/**
 * Renders an amount.
 *
 * Uses the server's `amount` display string rather than dividing `amountMinor`
 * here. The server already formatted it with the currency's own exponent — JPY
 * has none, TND has three — and doing the division again in the browser is one
 * more place for a float to appear.
 */
export function Money({
  value,
  signed = false,
  className = '',
}: {
  value: MoneyResponse | undefined
  /** Show an explicit sign, for columns where direction matters. */
  signed?: boolean
  className?: string
}) {
  if (!value) return <span className={className}>—</span>

  const minor = value.amountMinor ?? 0
  const negative = minor < 0
  const tone = negative ? 'text-rose-700 dark:text-rose-400' : ''

  return (
    <span className={`tabular-nums ${tone} ${className}`}>
      {signed && minor > 0 ? '+' : ''}
      {value.amount} {value.currency}
    </span>
  )
}
