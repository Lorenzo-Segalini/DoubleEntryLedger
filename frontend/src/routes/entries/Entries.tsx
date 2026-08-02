import { useState } from 'react'
import { Link } from 'react-router'
import { useAccounts, useJournal, type JournalFilters } from '@/features/ledger/queries'
import { Money } from '@/components/Money'
import {
  Badge,
  Button,
  Card,
  Empty,
  Field,
  Spinner,
  inputClass,
  tbodyRowClass,
  theadRowClass,
  thClass,
} from '@/components/Ui'
import { InfoTip, TipTerm } from '@/components/Tooltip'
import { ErrorNotice } from '@/components/ErrorNotice'
import { formatBusinessDate } from '@/lib/dates'

/**
 * Browsing the journal.
 *
 * Paged by cursor, not by page number. Entries arrive while a user reads; under
 * offset pagination every row shifts down and page two repeats rows from page
 * one. So there is no "page 3" to link to here — only "more", which is an honest
 * reflection of what an append-only log can promise.
 */
export function Entries() {
  const accounts = useAccounts()
  const [filters, setFilters] = useState<JournalFilters>({})
  const journal = useJournal(filters)

  const entries = (journal.data?.pages ?? []).flatMap((page) => page.items ?? [])

  return (
    <>
      <div>
        <div className="flex items-center gap-0.5">
          <h1 className="text-lg font-semibold text-fg">Journal</h1>
          <InfoTip term="the journal">
            The append-only record of every entry ever posted, newest first. Rows are never edited or removed; a mistake
            is corrected by posting its mirror, and both stay visible.
          </InfoTip>
        </div>
        <p className="text-sm text-muted">Most recent first. Nothing here can be edited.</p>
      </div>

      <Card title="Filters">
        <div className="grid gap-3 sm:grid-cols-4">
          <Field label="From" hint="Effective date, inclusive">
            <input
              type="date"
              className={inputClass}
              value={filters.from ?? ''}
              onChange={(e) => setFilters((f) => ({ ...f, from: e.target.value || undefined }))}
            />
          </Field>
          <Field label="To" hint="Effective date, inclusive">
            <input
              type="date"
              className={inputClass}
              value={filters.to ?? ''}
              onChange={(e) => setFilters((f) => ({ ...f, to: e.target.value || undefined }))}
            />
          </Field>
          <Field label="Account">
            <select
              className={inputClass}
              value={filters.accountId ?? ''}
              onChange={(e) => setFilters((f) => ({ ...f, accountId: e.target.value || undefined }))}
            >
              <option value="">Any</option>
              {(accounts.data ?? []).map((account) => (
                <option key={account.id} value={account.id}>
                  {account.code} · {account.name}
                </option>
              ))}
            </select>
          </Field>
          <Field
            label="Source"
            tip={
              <InfoTip term="where an entry came from" align="end">
                What put the entry in the journal: the API, a transfer, a reversal, an adjustment raised by
                reconciliation, a statement import, or the demo seed.
              </InfoTip>
            }
          >
            <select
              className={inputClass}
              value={filters.source ?? ''}
              onChange={(e) => setFilters((f) => ({ ...f, source: e.target.value || undefined }))}
            >
              <option value="">Any</option>
              {['API', 'TRANSFER', 'REVERSAL', 'ADJUSTMENT', 'IMPORT', 'SEED'].map((source) => (
                <option key={source} value={source}>
                  {source}
                </option>
              ))}
            </select>
          </Field>
        </div>
      </Card>

      <ErrorNotice error={journal.error} />

      <Card title="Entries">
        {journal.isPending ? (
          <Spinner />
        ) : entries.length === 0 ? (
          <Empty>No entries match these filters.</Empty>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <caption className="sr-only">Journal entries matching the current filters, most recent first.</caption>
                <thead>
                  <tr className={theadRowClass}>
                    <th scope="col" aria-label="Sequence number" className={thClass}>
                      <TipTerm
                        term="the sequence number"
                        tip="The position this entry holds in the journal. Numbers are handed out in order and never reused, so a gap would itself be evidence."
                      >
                        #
                      </TipTerm>
                    </th>
                    <th scope="col" aria-label="Effective" className={thClass}>
                      <TipTerm
                        term="the effective date"
                        tip="When the transaction happened in the world, which is not when it was written down. Reconciliation classifies the gap between the two as a timing difference rather than an error."
                      >
                        Effective
                      </TipTerm>
                    </th>
                    <th scope="col" className={thClass}>
                      Description
                    </th>
                    <th scope="col" className={thClass}>
                      Source
                    </th>
                    <th scope="col" className={`${thClass} text-right`}>
                      Amount
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {entries.map((entry) => (
                    <tr key={entry.id} className={tbodyRowClass}>
                      <th scope="row" className="py-1.5 text-left font-mono text-xs font-normal">
                        <Link to={`/entries/${entry.id}`} className="text-accent underline">
                          {entry.sequenceNo}
                        </Link>
                      </th>
                      <td className="py-1.5 text-xs">{formatBusinessDate(entry.effectiveDate ?? '')}</td>
                      <td className="py-1.5">
                        {entry.description}
                        {entry.reversalOfEntryId && (
                          <span className="ml-2">
                            <Badge tone="warn">Reversal</Badge>
                          </span>
                        )}
                      </td>
                      <td className="py-1.5 text-xs text-muted">{entry.source}</td>
                      <td className="py-1.5 text-right">
                        <Money value={entry.totalDebit} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="mt-4 flex flex-wrap items-center justify-between gap-2 border-t border-line pt-3">
              <span className="flex items-center gap-0.5 text-xs text-muted">
                {entries.length} shown
                <InfoTip term="how this list pages">
                  The server issues a cursor pointing at the last row sent, and the button asks for what follows it.
                  There is no page 3 to link to: entries arrive while you read, and under numbered pages every row would
                  shift down and page 2 would repeat rows from page 1.
                </InfoTip>
              </span>
              {journal.hasNextPage ? (
                <Button variant="ghost" onClick={() => journal.fetchNextPage()} disabled={journal.isFetchingNextPage}>
                  {journal.isFetchingNextPage ? 'Loading…' : 'Load more'}
                </Button>
              ) : (
                <span className="text-xs text-muted">End of the journal.</span>
              )}
            </div>
          </>
        )}
      </Card>
    </>
  )
}
