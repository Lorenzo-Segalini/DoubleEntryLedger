import { useState } from 'react'
import { Link } from 'react-router'
import { useTrialBalance } from '@/features/ledger/queries'
import { Money } from '@/components/Money'
import { Badge, Card, Empty, Spinner, inputClass } from '@/components/Ui'
import { ErrorNotice } from '@/components/ErrorNotice'
import { today } from '@/lib/dates'

export function Dashboard() {
  const [asOf, setAsOf] = useState(today())
  const trialBalance = useTrialBalance(asOf)

  return (
    <>
      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Trial balance</h1>
          <p className="text-sm text-slate-600 dark:text-slate-400">
            Derived from the journal at read time. Nothing here is cached.
          </p>
        </div>
        <label className="text-xs text-slate-600 dark:text-slate-400">
          As of
          <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className={`${inputClass} mt-1`} />
        </label>
      </div>

      <ErrorNotice error={trialBalance.error} />

      {trialBalance.isPending ? (
        <Spinner />
      ) : (
        trialBalance.data && (
          <>
            {/*
              The out-of-balance figure, displayed prominently as a green zero.
              A trial balance that only renders when it balances cannot tell you
              the one thing you would ever run it to find out.
            */}
            <Card
              title="Out of balance"
              actions={
                <Badge tone={trialBalance.data.balanced ? 'good' : 'bad'}>
                  {trialBalance.data.balanced ? 'Balanced' : 'OUT OF BALANCE'}
                </Badge>
              }
            >
              <p
                className={`text-3xl font-semibold tabular-nums ${
                  trialBalance.data.balanced
                    ? 'text-emerald-700 dark:text-emerald-400'
                    : 'text-rose-700 dark:text-rose-400'
                }`}
              >
                {trialBalance.data.outOfBalanceMinor}
              </p>
              <p className="mt-1 text-xs text-slate-500">
                Minor units. Every entry balances by construction, and a deferred database trigger refuses to commit one
                that does not — so this is a statement being checked, not assumed.
              </p>
            </Card>

            <Card title={`Accounts as of ${asOf}`}>
              {(trialBalance.data.rows ?? []).length === 0 ? (
                <Empty>No postings on or before this date.</Empty>
              ) : (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-slate-200 text-left text-xs uppercase text-slate-500 dark:border-slate-700">
                      <th className="pb-2 font-medium">Code</th>
                      <th className="pb-2 font-medium">Account</th>
                      <th className="pb-2 font-medium">Type</th>
                      <th className="pb-2 text-right font-medium">Debit</th>
                      <th className="pb-2 text-right font-medium">Credit</th>
                      <th className="pb-2 text-right font-medium">Balance</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(trialBalance.data.rows ?? []).map((row) => (
                      <tr key={row.accountCode} className="border-b border-slate-100 dark:border-slate-800">
                        <td className="py-1.5 font-mono text-xs">{row.accountCode}</td>
                        <td className="py-1.5">{row.accountName}</td>
                        <td className="py-1.5 text-xs text-slate-500">{row.type}</td>
                        <td className="py-1.5 text-right">
                          <Money value={row.debit} />
                        </td>
                        <td className="py-1.5 text-right">
                          <Money value={row.credit} />
                        </td>
                        <td className="py-1.5 text-right font-medium">
                          <Money value={row.balance} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr className="font-medium">
                      <td className="pt-2" colSpan={3}>
                        Total
                      </td>
                      <td className="pt-2 text-right">
                        <Money value={trialBalance.data.totalDebit} />
                      </td>
                      <td className="pt-2 text-right">
                        <Money value={trialBalance.data.totalCredit} />
                      </td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              )}
            </Card>

            <p className="text-xs text-slate-500">
              <Link to="/accounts" className="underline">
                Browse the chart of accounts
              </Link>
            </p>
          </>
        )
      )}
    </>
  )
}
