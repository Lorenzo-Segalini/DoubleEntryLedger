import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useExplainBreak, useReconciliationReport, useResolveBreak } from '@/features/reconciliation/queries'
import { useAccounts } from '@/features/ledger/queries'
import { useAuth } from '@/features/auth/useAuth'
import { Badge, Button, Card, Empty, Field, Spinner, inputClass, type Tone } from '@/components/Ui'
import { InfoTip } from '@/components/Tooltip'
import { ErrorNotice } from '@/components/ErrorNotice'
import { currency, format, minor } from '@/lib/money'
import { formatBusinessDate, formatInstant } from '@/lib/dates'
import type { BreakStatus, BreakType, BridgeRow } from '@/api/types'

const BREAK_TONE: Record<BreakType, Tone> = {
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

/*
  What each break type actually means.

  The codes are precise and unhelpful to anyone meeting them for the first
  time, and they are the entire vocabulary of this screen — so each one carries
  its definition rather than being softened into prose.
*/
const BREAK_HELP: Record<BreakType, string> = {
  MISSING_IN_LEDGER: 'The bank shows this movement and the journal has no entry for it. Usually a charge nobody booked.',
  MISSING_IN_STATEMENT: 'The journal has an entry the bank never shows. Either it has not cleared yet, or it should not have been posted.',
  AMOUNT_MISMATCH: 'The same transaction appears on both sides for different amounts — a fee taken off the top, or a keying error.',
  DUPLICATE_IN_LEDGER: 'One bank movement, two journal entries. One of them needs reversing.',
  DUPLICATE_IN_STATEMENT: 'The statement lists the same movement twice against a single journal entry.',
  TIMING_DIFFERENCE: 'Both sides agree on the transaction and disagree on the date. It corrects itself next period; nothing needs posting.',
  CURRENCY_MISMATCH: 'The statement line is in a different currency from the account. No amount is comparable until that is settled.',
  OPENING_BALANCE_MISMATCH: 'The statement declares an opening balance the ledger did not have on that date, so every figure after it is offset by the same gap.',
}

const STATUS_TONE: Record<BreakStatus, Tone> = {
  OPEN: 'bad',
  EXPLAINED: 'warn',
  RESOLVED: 'good',
  WRITTEN_OFF: 'neutral',
}

const STATUS_HELP: Record<BreakStatus, string> = {
  OPEN: 'Nobody has said why this difference exists yet.',
  EXPLAINED: 'A reason has been recorded and no money moved. Correct when the two sides will agree on their own.',
  RESOLVED: 'An adjusting entry was posted through the ordinary posting service, so it is balanced, attributed and reversible like any other.',
  WRITTEN_OFF: 'Accepted as a loss too small to chase, with the reason recorded beside it.',
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
        <h1 className="text-lg font-semibold text-fg">
          {it.accountCode} · {it.accountName}
        </h1>
        <p className="text-sm text-muted">
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
        tip={
          <InfoTip term="a bridge">
            The walk from what the ledger says to what the bank says, one explained difference at a time. It closes only
            if the explanations account for the gap exactly.
          </InfoTip>
        }
        actions={
          <Badge tone={it.bridgeBalanced ? 'good' : 'bad'}>
            {it.bridgeBalanced ? 'Explanations account for the difference' : 'BRIDGE DOES NOT CLOSE'}
          </Badge>
        }
      >
        <div className="grid gap-2 text-sm sm:grid-cols-3">
          <Figure
            label="Ledger closing"
            value={money(it.ledgerClosingMinor ?? 0)}
            tip="What this account comes to in the journal on the last day of the period."
          />
          <Figure
            label="Difference"
            value={money(it.differenceMinor ?? 0)}
            tone={it.differenceMinor === 0 ? 'good' : 'bad'}
            tip="Statement closing minus ledger closing. Every unit of it has to be accounted for by a break below; a difference of zero is the goal, but an explained difference is an acceptable outcome."
          />
          <Figure
            label="Statement closing"
            value={money(it.statementClosingMinor ?? 0)}
            tip="What the bank declares the account came to, taken from the imported file rather than computed."
          />
        </div>

        <Waterfall rows={it.bridge ?? []} start={it.ledgerClosingMinor ?? 0} money={money} />

        <p className="mt-4 border-t border-line pt-3 text-sm">
          <span className="text-muted">Ledger closing</span> {money(it.ledgerClosingMinor ?? 0)}{' '}
          <span className="text-muted">+ explanations</span> {money(it.bridgeTotalMinor ?? 0)}{' '}
          <span className="text-muted">=</span>{' '}
          <strong>{money((it.ledgerClosingMinor ?? 0) + (it.bridgeTotalMinor ?? 0))}</strong>{' '}
          <span className="text-muted">vs statement</span> {money(it.statementClosingMinor ?? 0)}
        </p>

        {!it.bridgeBalanced && (
          <p className="mt-2 text-sm font-medium text-bad">
            The explanations do not account for the difference. That is a defect in the matching engine — a
            double-consumed line, a sign error, a missed classification — not a judgement call for an operator.
          </p>
        )}
      </Card>

      <Card
        title="Breaks"
        tip={
          <InfoTip term="a break">
            One difference between the statement and the journal, classified by why it exists. A break is a question to
            answer, not necessarily a mistake — a timing difference is a break that will resolve itself.
          </InfoTip>
        }
      >
        {(it.bridge ?? []).length === 0 ? (
          <Empty>Nothing to explain — the statement agrees with the journal.</Empty>
        ) : (
          <ul className="divide-y divide-line">
            {(it.bridge ?? []).map((row) => (
              <BreakRow key={row.breakId} importId={id} row={row} money={money} />
            ))}
          </ul>
        )}
      </Card>

      <p className="text-xs text-muted">Generated {it.generatedAt ? formatInstant(it.generatedAt) : '—'}.</p>
    </>
  )
}

function Figure({ label, value, tone, tip }: { label: string; value: string; tone?: 'good' | 'bad'; tip: string }) {
  const colour = tone === 'good' ? 'text-good' : tone === 'bad' ? 'text-bad' : 'text-fg'
  return (
    <div>
      <p className="flex items-center gap-0.5 text-xs text-muted">
        {label}
        <InfoTip term={label.toLowerCase()}>{tip}</InfoTip>
      </p>
      <p className={`text-xl font-semibold tabular-nums ${colour}`}>{value}</p>
    </div>
  )
}

/**
 * Each bar is one break's contribution, drawn to scale against the largest.
 *
 * The bar is decoration: it is marked `aria-hidden`, and the figure it depicts
 * is in the cell beside it as text. Direction is carried by the sign in that
 * text, so the red/green of the bars adds emphasis and never information.
 */
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
            <span className="w-48 shrink-0 truncate text-muted">{row.type}</span>
            <span aria-hidden="true" className="flex-1">
              <span
                className={`block h-3 rounded ${delta < 0 ? 'bg-bad' : 'bg-good'}`}
                style={{ width: `${width}%` }}
              />
            </span>
            <span className="w-28 shrink-0 text-right tabular-nums">{money(delta)}</span>
            <span className="w-32 shrink-0 text-right tabular-nums text-muted">{money(running)}</span>
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

  const type = (row.type ?? 'MISSING_IN_LEDGER') as BreakType
  const status = (row.status ?? 'OPEN') as BreakStatus
  const closed = status === 'RESOLVED' || status === 'WRITTEN_OFF'

  return (
    <li className="py-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={BREAK_TONE[type]}>{row.type}</Badge>
        <InfoTip term={`the ${type} classification`}>{BREAK_HELP[type]}</InfoTip>

        <Badge tone={STATUS_TONE[status]}>{status}</Badge>
        <InfoTip term={`the ${status} state`}>{STATUS_HELP[status]}</InfoTip>

        <span className="text-sm text-fg">{row.detail}</span>
        <span className="ml-auto text-sm font-medium tabular-nums">{money(row.deltaMinor ?? 0)}</span>
        {can('reconcile') && !closed && (
          <Button variant="ghost" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
            {open ? 'Close' : 'Act'}
          </Button>
        )}
      </div>

      {open && !closed && (
        <div className="mt-3 rounded border border-line bg-surface-2 p-3">
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
              <Field
                label="Counter account"
                hint="Where the other side of the adjusting entry lands"
              >
                <select className={inputClass} value={counterAccount} onChange={(e) => setCounterAccount(e.target.value)}>
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

          <p className="mt-2 text-xs text-muted">
            Explaining moves no money — correct for a timing difference. Resolving posts an adjusting entry through the
            ordinary posting service, so it is an entry like any other: balanced, attributed and reversible.
          </p>

          <div className="mt-2">
            <ErrorNotice error={explain.error ?? resolve.error} />
          </div>

          {resolve.data && (
            <p className="mt-2 text-sm text-good">
              Posted as{' '}
              <Link to={`/entries/${resolve.data.adjustingEntryId}`} className="text-accent underline">
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
