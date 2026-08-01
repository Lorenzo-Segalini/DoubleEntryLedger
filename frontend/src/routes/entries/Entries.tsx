import { Link } from 'react-router'
import { Card } from '@/components/Ui'

/**
 * Listing the journal needs a cursor-paginated endpoint the API does not expose
 * yet — `GET /journal-entries` with filters is specified in docs/03-api.md §3.4
 * but not implemented. Rather than fake it with a client-side fetch of
 * everything, this says what is missing.
 */
export function Entries() {
  return (
    <>
      <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Journal</h1>
      <Card>
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Browsing the journal needs the cursor-paginated list endpoint, which is specified but not yet built. Individual
          entries are readable by id — the link on a posted entry, or on a reconciliation break&rsquo;s adjusting entry,
          goes straight to its audit trail.
        </p>
        <p className="mt-3 text-sm">
          <Link to="/entries/new" className="underline">
            Post an entry
          </Link>{' '}
          ·{' '}
          <Link to="/reconciliation" className="underline">
            Reconcile a statement
          </Link>
        </p>
      </Card>
    </>
  )
}
