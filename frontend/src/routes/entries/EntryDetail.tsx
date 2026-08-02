import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { useEntry, useReverseEntry } from '@/features/ledger/queries'
import { useAuth } from '@/features/auth/useAuth'
import { Money } from '@/components/Money'
import { Badge, Button, Card, Field, Spinner, inputClass, tbodyRowClass, theadRowClass, thClass } from '@/components/Ui'
import { InfoTip, TipTerm } from '@/components/Tooltip'
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
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-lg font-semibold text-fg">Entry #{it.sequenceNo}</h1>
          {isReversal && (
            <>
              <Badge tone="warn">Reversal</Badge>
              <InfoTip term="a reversal entry">
                This entry exists to cancel an earlier one. It mirrors the original line for line, and both remain in the
                journal — nothing was edited away.
              </InfoTip>
            </>
          )}
        </div>
        <p className="text-sm text-muted">{it.description}</p>
      </div>

      <Card title="Lines">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">
              The lines of entry {it.sequenceNo}, with the debit and credit totals they sum to.
            </caption>
            <thead>
              <tr className={theadRowClass}>
                <th scope="col" aria-label="Line number" className={thClass}>
                  #
                </th>
                <th scope="col" className={thClass}>
                  Account
                </th>
                <th scope="col" className={thClass}>
                  Memo
                </th>
                <th scope="col" aria-label="Debit" className={`${thClass} text-right`}>
                  <TipTerm
                    term="the debit column"
                    align="end"
                    tip="The left side of the entry. It increases assets and expenses and decreases everything else — it does not mean money left the account."
                  >
                    Debit
                  </TipTerm>
                </th>
                <th scope="col" aria-label="Credit" className={`${thClass} text-right`}>
                  <TipTerm
                    term="the credit column"
                    align="end"
                    tip="The right side of the entry. It increases liabilities, equity and income — it does not mean money arrived in the account."
                  >
                    Credit
                  </TipTerm>
                </th>
              </tr>
            </thead>
            <tbody>
              {(it.lines ?? []).map((line) => (
                <tr key={line.id} className={tbodyRowClass}>
                  <th scope="row" className="py-1.5 text-left text-xs font-normal text-muted">
                    {line.lineNo}
                  </th>
                  <td className="py-1.5">
                    <span className="font-mono text-xs">{line.accountCode}</span> {line.accountName}
                  </td>
                  <td className="py-1.5 text-xs text-muted">{line.memo}</td>
                  <td className="py-1.5 text-right">{line.direction === 'DEBIT' && <Money value={line.amount} />}</td>
                  <td className="py-1.5 text-right">{line.direction === 'CREDIT' && <Money value={line.amount} />}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr className="font-semibold">
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
        </div>
      </Card>

      <Card
        title="Provenance"
        tip={
          <InfoTip term="provenance">
            Where this entry came from and who is answerable for it. Every field here was recorded when the entry was
            written and none of it can be changed afterwards.
          </InfoTip>
        }
      >
        <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          <Row
            label="Effective date"
            tip={
              <InfoTip term="the effective date">
                When the transaction happened in the world. It can be earlier than the moment it was posted, and that gap
                is normal rather than a mistake.
              </InfoTip>
            }
          >
            {formatBusinessDate(it.effectiveDate ?? '')}
          </Row>
          <Row
            label="Posted at"
            tip={
              <InfoTip term="posted at">
                When the ledger learned of the transaction and wrote it down. This is the value that can never move; the
                effective date is a claim about the world, this is a fact about the record.
              </InfoTip>
            }
          >
            {it.postedAt ? formatInstant(it.postedAt) : '—'}
          </Row>
          <Row label="Source">{it.source}</Row>
          <Row label="External reference">{it.externalRef ?? '—'}</Row>
          <Row label="Created by">
            <span className="font-mono text-xs">{it.createdBy}</span>
          </Row>
          <Row
            label="Request"
            tip={
              <InfoTip term="the request id" align="end">
                The identifier of the HTTP call that created this entry. Quoting it finds the exact call in the server
                logs, which is the one thing that turns &ldquo;it went wrong&rdquo; into a trail someone can follow.
              </InfoTip>
            }
          >
            <span className="font-mono text-xs">{it.requestId}</span>
          </Row>
          {it.reversalOfEntryId && (
            <Row label="Reverses">
              <Link to={`/entries/${it.reversalOfEntryId}`} className="font-mono text-xs text-accent underline">
                {it.reversalOfEntryId}
              </Link>
            </Row>
          )}
          {it.reversalReason && <Row label="Reason">{it.reversalReason}</Row>}
        </dl>
        <p className="mt-4 border-t border-line pt-3 text-xs text-muted">
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
        <Card
          title="Correct this entry"
          tip={
            <InfoTip term="correcting an entry">
              There is no edit and no delete. A correction posts a mirror of this entry, so the journal shows both what
              was recorded and what was done about it.
            </InfoTip>
          }
        >
          <p className="mb-3 text-sm text-muted">
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
            <p className="mt-3 text-sm text-good">
              Reversed by{' '}
              <Link to={`/entries/${reverse.data.id}`} className="text-accent underline">
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

function Row({ label, tip, children }: { label: string; tip?: ReactNode; children: ReactNode }) {
  return (
    <div>
      {/*
        The tooltip sits inside the <dt> deliberately: a term in a description
        list names nothing else on the page, so unlike a heading or a column
        header it can carry a control without renaming anything.
      */}
      <dt className="flex items-center gap-0.5 text-xs text-muted">
        {label}
        {tip}
      </dt>
      <dd>{children}</dd>
    </div>
  )
}
