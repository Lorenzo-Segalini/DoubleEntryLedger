import { useState } from 'react'
import { Link, useParams } from 'react-router'
import {
  useExplainBreak,
  useReconciliationReport,
  useResolveBreak,
} from '@/features/reconciliation/queries'
import { useAccounts } from '@/features/ledger/queries'
import { useAuth } from '@/features/auth/useAuth'
import { Badge, Button, Card, Empty, Field, Spinner, inputClass } from '@/components/Ui'
import { ErrorNotice } from '@/components/ErrorNotice'
import { currency, format, minor } from '@/lib/money'
import { formatBusinessDate, formatInstant } from '@/lib/dates'
import type { BreakStatus, BreakType, BridgeRow } from '@/api/types'

const BREAK_TONE: Record<BreakType, 'neutral' | 'good' | 'warn' | 'bad'> = {
  MISSING_IN_LEDGER: 'bad',
  MISSING_IN_STATEMENT: 'bad',
  AMOUNT_MISMATCH: 'bad',
  DUPLICATE_IN_LEDGER: 'bad',
  DUPLICATE_IN_STATEMENT: 'bad',
  // Not an error: it resolves itself next period.
  TIMING_DIFFERENCE: 'warn',
  CURRENCY_MISMATCH: 'warn',
  OPENING_BALANCE_MISMATCH: 'warn',
}

const STATUS_TONE: Record<BreakStatus, 'neutral' | 'good' | 'warn' | 'bad'> = {
  OPEN: 'bad',
  EXPLAINED: 'warn',
  RESOLVED: 'good',
  WRITTEN_OFF: 'neutral',
}

export function ReconciliationReportView() {
  const { id = '' } = useParams()
  const report = useReconciliationReport(id)

  if (report.isPending) return <Spinner />
  if (report.error) return <ErrorNotice error={report.error} />
  if (!report.data) return null

  const it = report.data
  const unit = currency(it.currency ?? 'EUR')
  const money = (amountMinor: number) => format({ amountMinor: minor(amountMinor), currency: unit }, 'en-IE')

  return (
    <>
      <div>
        <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
          {it.accountCode} · {it.accountName}
        </h1>
        <p className="text-sm text-slate-600 dark:text-slate-400">
          {formatBusinessDate(it.periodStart ?? '')} – {formatBusinessDate(it.periodEnd ?? '')} · matched{' '}
          {it.matchedCount} of {(it.matchedCount ?? 0) + (it.unmatchedStatementLines ?? 0)} statement lines
        </p>
      </div>

      {/*
        The bridge, as a waterfall. Ledger closing on the left, one bar per
        break, statement closing on the right — a visual proof that the
        explanations add up to the difference rather than a list beside a total.
      */}
      <Card
        title="Bridge"
        actions={
          <Badge tone={it.bridgeBalanced ? 'good' : 'bad'}>
            {it.bridgeBalanced ? 'Explanations account for the difference' : 'BRIDGE DOES NOT CLOSE'}
          </Badge>
        }
      >
        <div className="grid gap-2 text-sm sm:grid-cols-3">
          <Figure label="Ledger closing" value={money(it.ledgerClosingMinor ?? 0)} />
          <Figure
            label="Difference"
            value={money(it.differenceMinor ?? 0)}
            tone={it.differenceMinor === 0 ? 'good' : 'bad'}
          />
          <Figure label="Statement closing" value={money(it.statementClosingMinor ?? 0)} />
        </div>

        <Waterfall
          rows={it.bridge ?? []}
          start={it.ledgerClosingMinor ?? 0}
          money={money}
        />

        <p className="mt-4 border-t border-slate-100 pt-3 text-sm dark:border-slate-800">
          <span className="text-slate-500">Ledger closing</span> {money(it.ledgerClosingMinor ?? 0)}{' '}
          <span className="text-slate-500">+ explanations</span> {money(it.bridgeTotalMinor ?? 0)}{' '}
          <span className="text-slate-500">=</span>{' '}
          <strong>{money((it.ledgerClosingMinor ?? 0) + (it.bridgeTotalMinor ?? 0))}</strong>{' '}
          <span className="text-slate-500">vs statement</span> {money(it.statementClosingMinor ?? 0)}
        </p>

        {!it.bridgeBalanced && (
          <p className="mt-2 text-sm text-rose-700 dark:text-rose-400">
            The explanations do not account for the difference. That is a defect in the matching engine — a
            double-consumed line, a sign error, a missed classification — not a judgement call for an operator.
          </p>
        )}
      </Card>

      <Card title="Breaks">
        {(it.bridge ?? []).length === 0 ? (
          <Empty>Nothing to explain — the statement agrees with the journal.</Empty>
        ) : (
          <ul className="divide-y divide-slate-100 dark:divide-slate-800">
            {(it.bridge ?? []).map((row) => (
              <BreakRow key={row.breakId} importId={id} row={row} money={money} />
            ))}
          </ul>
        )}
      </Card>

      <p className="text-xs text-slate-500">Generated {it.generatedAt ? formatInstant(it.generatedAt) : '—'}.</p>
    </>
  )
}

function Figure({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'bad' }) {
  const colour =
    tone === 'good'
      ? 'text-emerald-700 dark:text-emerald-400'
      : tone === 'bad'
        ? 'text-rose-700 dark:text-rose-400'
        : 'text-slate-900 dark:text-slate-100'
  return (
    <div>
      <p className="text-xs text-slate-500">{label}</p>
      <p className={`text-xl font-semibold tabular-nums ${colour}`}>{value}</p>
    </div>
  )
}

/** Each bar is one break's contribution, drawn to scale against the largest. */
function Waterfall({
  rows,
  start,
  money,
}: {
  rows: BridgeRow[]
  start: number
  money: (minorUnits: number) => string
}) {
  const live = rows.filter((row) => (row.deltaMinor ?? 0) !== 0)
  if (live.length === 0) return null

  const largest = Math.max(...live.map((row) => Math.abs(row.deltaMinor ?? 0)), 1)
  let running = start

  return (
    <ol className="mt-4 space-y-1">
      {live.map((row) => {
        const delta = row.deltaMinor ?? 0
        running += delta
        const width = Math.max(2, (Math.abs(delta) / largest) * 100)
        return (
          <li key={row.breakId} className="flex items-center gap-2 text-xs">
            <span className="w-48 shrink-0 truncate text-slate-600 dark:text-slate-400">{row.type}</span>
            <span className="flex-1">
              <span
                className={`block h-3 rounded ${delta < 0 ? 'bg-rose-400' : 'bg-emerald-400'}`}
                style={{ width: `${width}%` }}
              />
            </span>
            <span className="w-28 shrink-0 text-right tabular-nums">{money(delta)}</span>
            <span className="w-32 shrink-0 text-right tabular-nums text-slate-500">{money(running)}</span>
          </li>
        )
      })}
    </ol>
  )
}

function BreakRow({
  importId,
  row,
  money,
}: {
  importId: string
  row: BridgeRow
  money: (minorUnits: number) => string
}) {
  const { can } = useAuth()
  const accounts = useAccounts()
  const explain = useExplainBreak(importId)
  const resolve = useResolveBreak(importId)

  const [open, setOpen] = useState(false)
  const [explanation, setExplanation] = useState('')
  const [counterAccount, setCounterAccount] = useState('5000')
  const [idempotencyKey] = useState(() => crypto.randomUUID())

  const status = (row.status ?? 'OPEN') as BreakStatus
  const closed = status === 'RESOLVED' || status === 'WRITTEN_OFF'

  return (
    <li className="py-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={BREAK_TONE[(row.type ?? 'MISSING_IN_LEDGER') as BreakType]}>{row.type}</Badge>
        <Badge tone={STATUS_TONE[status]}>{status}</Badge>
        <span className="text-sm text-slate-700 dark:text-slate-300">{row.detail}</span>
        <span className="ml-auto tabular-nums text-sm font-medium">{money(row.deltaMinor ?? 0)}</span>
        {can('reconcile') && !closed && (
          <Button variant="ghost" onClick={() => setOpen((o) => !o)}>
            {open ? 'Close' : 'Act'}
          </Button>
        )}
      </div>

      {open && !closed && (
        <div className="mt-3 rounded border border-slate-200 p-3 dark:border-slate-700">
          <Field label="Explanation" hint="What an auditor reads six months from now.">
            <input className={inputClass} value={explanation} onChange={(e) => setExplanation(e.target.value)} />
          </Field>

          <div className="mt-3 flex flex-wrap items-end gap-3">
            <Button
              variant="ghost"
              disabled={!explanation.trim() || explain.isPending}
              onClick={() => explain.mutate({ breakId: row.breakId ?? '', explanation })}
            >
              Explain only
            </Button>

            <div className="flex items-end gap-2">
              <Field label="Counter account">
                <select
                  className={inputClass}
                  value={counterAccount}
                  onChange={(e) => setCounterAccount(e.target.value)}
                >
                  {(accounts.data ?? []).map((account) => (
                    <option key={account.id} value={account.code}>
                      {account.code} · {account.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Button
                disabled={!explanation.trim() || resolve.isPending}
                onClick={() =>
                  resolve.mutate({
                    breakId: row.breakId ?? '',
                    counterAccountCode: counterAccount,
                    explanation,
                    idempotencyKey,
                  })
                }
              >
                {resolve.isPending ? 'Posting…' : 'Resolve by posting'}
              </Button>
            </div>
          </div>

          <p className="mt-2 text-xs text-slate-500">
            Explaining moves no money — correct for a timing difference. Resolving posts an adjusting entry through the
            ordinary posting service, so it is an entry like any other: balanced, attributed and reversible.
          </p>

          <div className="mt-2">
            <ErrorNotice error={explain.error ?? resolve.error} />
          </div>

          {resolve.data && (
            <p className="mt-2 text-sm text-emerald-700 dark:text-emerald-400">
              Posted as{' '}
              <Link to={`/entries/${resolve.data.adjustingEntryId}`} className="underline">
                an adjusting entry
              </Link>
              .
            </p>
          )}
        </div>
      )}
    </li>
  )
}
