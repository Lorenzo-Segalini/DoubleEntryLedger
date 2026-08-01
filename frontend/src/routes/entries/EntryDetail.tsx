import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useEntry, useReverseEntry } from '@/features/ledger/queries'
import { useAuth } from '@/features/auth/useAuth'
import { Money } from '@/components/Money'
import { Badge, Button, Card, Field, Spinner, inputClass } from '@/components/Ui'
import { ErrorNotice } from '@/components/ErrorNotice'
import { formatBusinessDate, formatInstant, today } from '@/lib/dates'

/** Full provenance for one entry: the "why does this exist" panel. */
export function EntryDetail() {
  const { id = '' } = useParams()
  const entry = useEntry(id)
  const { can } = useAuth()
  const reverse = useReverseEntry()

  const [reason, setReason] = useState('')
  const [reversalDate, setReversalDate] = useState(today())
  // Generated once per open, not per click: double-clicking Reverse sends the
  // same key twice and the second response is a replay, not a second reversal.
  const [idempotencyKey] = useState(() => crypto.randomUUID())

  if (entry.isPending) return <Spinner />
  if (entry.error) return <ErrorNotice error={entry.error} />
  if (!entry.data) return null

  const it = entry.data
  const isReversal = Boolean(it.reversalOfEntryId)

  return (
    <>
      <div>
        <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
          Entry #{it.sequenceNo}
          {isReversal && (
            <span className="ml-2">
              <Badge tone="warn">Reversal</Badge>
            </span>
          )}
        </h1>
        <p className="text-sm text-slate-600 dark:text-slate-400">{it.description}</p>
      </div>

      <Card title="Lines">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-left text-xs uppercase text-slate-500 dark:border-slate-700">
              <th className="pb-2 font-medium">#</th>
              <th className="pb-2 font-medium">Account</th>
              <th className="pb-2 font-medium">Memo</th>
              <th className="pb-2 text-right font-medium">Debit</th>
              <th className="pb-2 text-right font-medium">Credit</th>
            </tr>
          </thead>
          <tbody>
            {(it.lines ?? []).map((line) => (
              <tr key={line.id} className="border-b border-slate-100 dark:border-slate-800">
                <td className="py-1.5 text-xs text-slate-500">{line.lineNo}</td>
                <td className="py-1.5">
                  <span className="font-mono text-xs">{line.accountCode}</span> {line.accountName}
                </td>
                <td className="py-1.5 text-xs text-slate-500">{line.memo}</td>
                <td className="py-1.5 text-right">{line.direction === 'DEBIT' && <Money value={line.amount} />}</td>
                <td className="py-1.5 text-right">{line.direction === 'CREDIT' && <Money value={line.amount} />}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="font-medium">
              <td className="pt-2" colSpan={3}>
                Total
              </td>
              <td className="pt-2 text-right">
                <Money value={it.totalDebit} />
              </td>
              <td className="pt-2 text-right">
                <Money value={it.totalCredit} />
              </td>
            </tr>
          </tfoot>
        </table>
      </Card>

      <Card title="Provenance">
        <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          <Row label="Effective date">{formatBusinessDate(it.effectiveDate ?? '')}</Row>
          <Row label="Posted at">{it.postedAt ? formatInstant(it.postedAt) : '—'}</Row>
          <Row label="Source">{it.source}</Row>
          <Row label="External reference">{it.externalRef ?? '—'}</Row>
          <Row label="Created by">
            <span className="font-mono text-xs">{it.createdBy}</span>
          </Row>
          <Row label="Request">
            <span className="font-mono text-xs">{it.requestId}</span>
          </Row>
          {it.reversalOfEntryId && (
            <Row label="Reverses">
              <Link to={`/entries/${it.reversalOfEntryId}`} className="font-mono text-xs underline">
                {it.reversalOfEntryId}
              </Link>
            </Row>
          )}
          {it.reversalReason && <Row label="Reason">{it.reversalReason}</Row>}
        </dl>
        <p className="mt-4 border-t border-slate-100 pt-3 text-xs text-slate-500 dark:border-slate-800">
          Effective date is when it happened; posted at is when we learned of it. The gap between them is what
          reconciliation classifies as a timing difference rather than an error.
        </p>
      </Card>

      {/*
        No edit control, here or anywhere. A posted entry is corrected by
        posting its mirror — the original stays visible, and so does the
        correction.
      */}
      {can('post') && !isReversal && (
        <Card title="Correct this entry">
          <p className="mb-3 text-sm text-slate-600 dark:text-slate-400">
            Entries are never edited or deleted. A correction posts a reversal that mirrors this entry, leaving both in
            the journal.
          </p>
          <div className="grid gap-3 sm:grid-cols-[1fr_auto_auto] sm:items-end">
            <Field label="Reason" hint="What an auditor reads six months from now.">
              <input className={inputClass} value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
            <Field label="Effective date">
              <input
                type="date"
                className={inputClass}
                value={reversalDate}
                onChange={(e) => setReversalDate(e.target.value)}
              />
            </Field>
            <Button
              variant="danger"
              disabled={!reason.trim() || reverse.isPending}
              onClick={() =>
                reverse.mutate({ entryId: it.id ?? '', reason, effectiveDate: reversalDate, idempotencyKey })
              }
            >
              {reverse.isPending ? 'Reversing…' : 'Post reversal'}
            </Button>
          </div>
          <div className="mt-3">
            <ErrorNotice error={reverse.error} />
          </div>
          {reverse.data && (
            <p className="mt-3 text-sm text-emerald-700 dark:text-emerald-400">
              Reversed by{' '}
              <Link to={`/entries/${reverse.data.id}`} className="underline">
                entry #{reverse.data.sequenceNo}
              </Link>
              .
            </p>
          )}
        </Card>
      )}
    </>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}
