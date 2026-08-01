import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { useAccounts, usePostEntry } from '@/features/ledger/queries'
import { Button, Card, Field, Spinner, inputClass } from '@/components/Ui'
import { ErrorNotice } from '@/components/ErrorNotice'
import { ApiError } from '@/lib/problem'
import { currency, format, minor, parse, type CurrencyCode } from '@/lib/money'
import { today } from '@/lib/dates'

interface LineDraft {
  accountCode: string
  direction: 'DEBIT' | 'CREDIT'
  /** Kept as the user typed it. Parsing to minor units happens once, on submit. */
  amount: string
  memo: string
}

const EMPTY_LINE: LineDraft = { accountCode: '', direction: 'DEBIT', amount: '', memo: '' }

export function PostEntry() {
  const accounts = useAccounts()
  const post = usePostEntry()

  const [effectiveDate, setEffectiveDate] = useState(today())
  const [description, setDescription] = useState('')
  const [currencyCode, setCurrencyCode] = useState('EUR')
  const [externalRef, setExternalRef] = useState('')
  const [lines, setLines] = useState<LineDraft[]>([
    { ...EMPTY_LINE },
    { ...EMPTY_LINE, direction: 'CREDIT' },
  ])

  /**
   * One key for the life of this form, not one per submit.
   *
   * Double-clicking the button therefore sends the same key twice, and the
   * second response is a replay of the first rather than a second posting. That
   * is the scenario idempotency exists for, and it is reachable here by
   * double-clicking.
   */
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID())

  const unit = safeCurrency(currencyCode)

  /**
   * Running totals in minor units.
   *
   * Amounts are parsed once here and never divided: no arithmetic in this
   * component touches a fractional number, so the difference shown is exact.
   */
  const totals = useMemo(() => {
    let debit = 0
    let credit = 0
    let malformed = false

    for (const line of lines) {
      if (!line.amount.trim()) continue
      try {
        const parsed = parse(line.amount, unit)
        if (line.direction === 'DEBIT') debit += parsed.amountMinor
        else credit += parsed.amountMinor
      } catch {
        malformed = true
      }
    }
    return { debit, credit, difference: debit - credit, malformed }
  }, [lines, unit])

  const filledLines = lines.filter((line) => line.accountCode && line.amount.trim())
  const balanced = totals.difference === 0 && filledLines.length >= 2 && !totals.malformed
  const canSubmit = balanced && description.trim().length > 0 && !post.isPending

  const fieldErrors = post.error instanceof ApiError ? post.error.fieldErrors() : {}

  function updateLine(index: number, patch: Partial<LineDraft>) {
    setLines((current) => current.map((line, i) => (i === index ? { ...line, ...patch } : line)))
  }

  function submit() {
    post.mutate(
      {
        idempotencyKey,
        request: {
          effectiveDate,
          description,
          currency: currencyCode,
          ...(externalRef.trim() ? { externalRef: externalRef.trim() } : {}),
          lines: filledLines.map((line) => ({
            accountCode: line.accountCode,
            direction: line.direction,
            amountMinor: parse(line.amount, unit).amountMinor,
            ...(line.memo.trim() ? { memo: line.memo.trim() } : {}),
          })),
        },
      },
      {
        onSuccess: () => {
          // A new key for the next entry: the previous one is now spent on a
          // posting that succeeded.
          setIdempotencyKey(crypto.randomUUID())
          setLines([{ ...EMPTY_LINE }, { ...EMPTY_LINE, direction: 'CREDIT' }])
          setDescription('')
          setExternalRef('')
        },
      },
    )
  }

  if (accounts.isPending) return <Spinner />

  return (
    <>
      <div>
        <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Post a journal entry</h1>
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Two or more lines, debits equal to credits. The server and a database trigger enforce the same rule
          independently.
        </p>
      </div>

      {post.data && (
        <div className="rounded border border-emerald-300 bg-emerald-50 p-3 text-sm dark:border-emerald-800 dark:bg-emerald-950/40">
          Posted as{' '}
          <Link to={`/entries/${post.data.id}`} className="font-medium underline">
            entry #{post.data.sequenceNo}
          </Link>
          .
        </div>
      )}

      <Card title="Entry">
        <div className="grid gap-3 sm:grid-cols-4">
          <Field label="Effective date" error={fieldErrors['/effectiveDate']}>
            <input
              type="date"
              className={inputClass}
              value={effectiveDate}
              max={today()}
              onChange={(e) => setEffectiveDate(e.target.value)}
            />
          </Field>
          <Field label="Currency">
            <input
              className={inputClass}
              value={currencyCode}
              maxLength={3}
              onChange={(e) => setCurrencyCode(e.target.value.toUpperCase())}
            />
          </Field>
          <Field label="Description" error={fieldErrors['/description']}>
            <input className={inputClass} value={description} onChange={(e) => setDescription(e.target.value)} />
          </Field>
          <Field label="External reference" hint="Optional">
            <input className={inputClass} value={externalRef} onChange={(e) => setExternalRef(e.target.value)} />
          </Field>
        </div>
      </Card>

      <Card
        title="Lines"
        actions={
          <Button variant="ghost" onClick={() => setLines((c) => [...c, { ...EMPTY_LINE }])}>
            Add line
          </Button>
        }
      >
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs uppercase text-slate-500">
              <th className="pb-2 font-medium">Account</th>
              <th className="pb-2 font-medium">Direction</th>
              <th className="pb-2 font-medium">Amount</th>
              <th className="pb-2 font-medium">Memo</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {lines.map((line, index) => (
              <tr key={index}>
                <td className="py-1 pr-2">
                  <select
                    className={inputClass}
                    value={line.accountCode}
                    onChange={(e) => updateLine(index, { accountCode: e.target.value })}
                    aria-label={`Account for line ${index + 1}`}
                  >
                    <option value="">Select…</option>
                    {(accounts.data ?? []).map((account) => (
                      <option key={account.id} value={account.code}>
                        {account.code} · {account.name}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="py-1 pr-2">
                  <select
                    className={inputClass}
                    value={line.direction}
                    onChange={(e) => updateLine(index, { direction: e.target.value as 'DEBIT' | 'CREDIT' })}
                    aria-label={`Direction for line ${index + 1}`}
                  >
                    <option value="DEBIT">Debit</option>
                    <option value="CREDIT">Credit</option>
                  </select>
                </td>
                <td className="py-1 pr-2">
                  <input
                    className={`${inputClass} text-right tabular-nums`}
                    inputMode="decimal"
                    placeholder="0.00"
                    value={line.amount}
                    onChange={(e) => updateLine(index, { amount: e.target.value })}
                    aria-label={`Amount for line ${index + 1}`}
                  />
                </td>
                <td className="py-1 pr-2">
                  <input
                    className={inputClass}
                    value={line.memo}
                    onChange={(e) => updateLine(index, { memo: e.target.value })}
                    aria-label={`Memo for line ${index + 1}`}
                  />
                </td>
                <td className="py-1">
                  {lines.length > 2 && (
                    <Button variant="ghost" onClick={() => setLines((c) => c.filter((_, i) => i !== index))}>
                      Remove
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        {/* Live totals, with the difference shown until it is zero. */}
        <div className="mt-4 flex flex-wrap items-center justify-end gap-6 border-t border-slate-100 pt-3 text-sm dark:border-slate-800">
          <span className="text-slate-500">
            Debits <strong className="tabular-nums text-slate-900 dark:text-slate-100">{format({ amountMinor: minor(totals.debit), currency: unit }, 'en-IE')}</strong>
          </span>
          <span className="text-slate-500">
            Credits <strong className="tabular-nums text-slate-900 dark:text-slate-100">{format({ amountMinor: minor(totals.credit), currency: unit }, 'en-IE')}</strong>
          </span>
          <span
            className={
              balanced
                ? 'font-medium text-emerald-700 dark:text-emerald-400'
                : 'font-medium text-rose-700 dark:text-rose-400'
            }
          >
            {totals.malformed
              ? 'An amount is not a valid number'
              : balanced
                ? 'Balanced'
                : `Out by ${format({ amountMinor: minor(Math.abs(totals.difference)), currency: unit }, 'en-IE')}`}
          </span>
        </div>
      </Card>

      <ErrorNotice error={post.error} />

      <div className="flex justify-end">
        <Button onClick={submit} disabled={!canSubmit}>
          {post.isPending ? 'Posting…' : 'Post entry'}
        </Button>
      </div>
    </>
  )
}

function safeCurrency(code: string): CurrencyCode {
  try {
    return currency(code)
  } catch {
    return currency('EUR')
  }
}
