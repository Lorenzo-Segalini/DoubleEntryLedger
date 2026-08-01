import { useState } from 'react'
import { Link } from 'react-router'
import { useAccounts, useJournal, type JournalFilters } from '@/features/ledger/queries'
import { Money } from '@/components/Money'
import { Badge, Button, Card, Empty, Field, Spinner, inputClass } from '@/components/Ui'
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
        <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Journal</h1>
        <p className="text-sm text-slate-600 dark:text-slate-400">Most recent first. Nothing here can be edited.</p>
      </div>

      <Card title="Filters">
        <div className="grid gap-3 sm:grid-cols-4">
          <Field label="From">
            <input
              type="date"
              className={inputClass}
              value={filters.from ?? ''}
              onChange={(e) => setFilters((f) => ({ ...f, from: e.target.value || undefined }))}
            />
          </Field>
          <Field label="To">
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
          <Field label="Source">
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
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-200 text-left text-xs uppercase text-slate-500 dark:border-slate-700">
                  <th className="pb-2 font-medium">#</th>
                  <th className="pb-2 font-medium">Effective</th>
                  <th className="pb-2 font-medium">Description</th>
                  <th className="pb-2 font-medium">Source</th>
                  <th className="pb-2 text-right font-medium">Amount</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.id} className="border-b border-slate-100 dark:border-slate-800">
                    <td className="py-1.5 font-mono text-xs">
                      <Link to={`/entries/${entry.id}`} className="underline">
                        {entry.sequenceNo}
                      </Link>
                    </td>
                    <td className="py-1.5 text-xs">{formatBusinessDate(entry.effectiveDate ?? '')}</td>
                    <td className="py-1.5">
                      {entry.description}
                      {entry.reversalOfEntryId && (
                        <span className="ml-2">
                          <Badge tone="warn">Reversal</Badge>
                        </span>
                      )}
                    </td>
                    <td className="py-1.5 text-xs text-slate-500">{entry.source}</td>
                    <td className="py-1.5 text-right">
                      <Money value={entry.totalDebit} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div className="mt-4 flex items-center justify-between border-t border-slate-100 pt-3 dark:border-slate-800">
              <span className="text-xs text-slate-500">{entries.length} shown</span>
              {journal.hasNextPage ? (
                <Button variant="ghost" onClick={() => journal.fetchNextPage()} disabled={journal.isFetchingNextPage}>
                  {journal.isFetchingNextPage ? 'Loading…' : 'Load more'}
                </Button>
              ) : (
                <span className="text-xs text-slate-500">End of the journal.</span>
              )}
            </div>
          </>
        )}
      </Card>
    </>
  )
}
