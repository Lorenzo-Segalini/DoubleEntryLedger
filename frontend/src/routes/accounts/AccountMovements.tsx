import { useState } from 'react'
import { useParams } from 'react-router'
import { useAccount, useBalance } from '@/features/ledger/queries'
import { Money } from '@/components/Money'
import { Card, Spinner, inputClass } from '@/components/Ui'
import { ErrorNotice } from '@/components/ErrorNotice'
import { formatInstant, today } from '@/lib/dates'

/**
 * The screen that carries the argument.
 *
 * The header shows opening, debits, credits and closing — and there is no edit
 * control anywhere on it. The empty space where one would be is the point: a
 * posted entry is corrected by a reversal, never by a change.
 */
export function AccountMovements() {
  const { id = '' } = useParams()
  const [asOf, setAsOf] = useState(today())

  const account = useAccount(id)
  const balance = useBalance(id, asOf)

  return (
    <>
      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
            {account.data ? `${account.data.code} · ${account.data.name}` : 'Account'}
          </h1>
          <p className="text-sm text-slate-600 dark:text-slate-400">
            {account.data?.type} · {account.data?.currency}
          </p>
        </div>
        <label className="text-xs text-slate-600 dark:text-slate-400">
          As of
          <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className={`${inputClass} mt-1`} />
        </label>
      </div>

      <ErrorNotice error={account.error ?? balance.error} />

      {balance.isPending ? (
        <Spinner />
      ) : (
        balance.data && (
          <Card title={`Balance as of ${asOf}`}>
            <dl className="grid gap-2 text-sm sm:grid-cols-4">
              <div>
                <dt className="text-xs text-slate-500">Total debits</dt>
                <dd className="text-lg">
                  <Money value={balance.data.totalDebit} />
                </dd>
              </div>
              <div>
                <dt className="text-xs text-slate-500">Total credits</dt>
                <dd className="text-lg">
                  <Money value={balance.data.totalCredit} />
                </dd>
              </div>
              <div>
                <dt className="text-xs text-slate-500">Balance</dt>
                <dd className="text-lg font-semibold">
                  <Money value={balance.data.balance} />
                </dd>
              </div>
              <div>
                <dt className="text-xs text-slate-500">Lines</dt>
                <dd className="text-lg tabular-nums">{balance.data.lineCount}</dd>
              </div>
            </dl>

            <p className="mt-4 border-t border-slate-100 pt-3 text-xs text-slate-500 dark:border-slate-800">
              Derived at {balance.data.derivedAt ? formatInstant(balance.data.derivedAt) : '—'}. No balance is stored;
              this is a sum over the account&rsquo;s own lines, so the same date returns the same number in a year&rsquo;s
              time.
            </p>
          </Card>
        )
      )}
    </>
  )
}
