import { useState } from 'react'
import { Link } from 'react-router'
import { useTrialBalance } from '@/features/ledger/queries'
import { Money } from '@/components/Money'
import { Badge, Card, Empty, Field, Spinner, inputClass, tbodyRowClass, theadRowClass, thClass } from '@/components/Ui'
import { InfoTip, TipTerm } from '@/components/Tooltip'
import { ErrorNotice } from '@/components/ErrorNotice'
import { today } from '@/lib/dates'

export function Dashboard() {
  const [asOf, setAsOf] = useState(today())
  const trialBalance = useTrialBalance(asOf)

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="flex items-center gap-0.5">
            <h1 className="text-lg font-semibold text-fg">Trial balance</h1>
            <InfoTip term="a trial balance">
              Every account listed with its total debits and credits on one page. If the two totals differ, something in
              the journal is wrong — which is the only reason to run one.
            </InfoTip>
          </div>
          <p className="text-sm text-muted">Derived from the journal at read time. Nothing here is cached.</p>
        </div>

        <Field
          label="As of"
          tip={
            <InfoTip term="the as-of date" align="end">
              Sums every posting up to and including this date. Because no balance is stored, the same date asked again
              in a year returns the same figure.
            </InfoTip>
          }
        >
          <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className={inputClass} />
        </Field>
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
              tip={
                <InfoTip term="out of balance">
                  Total debits minus total credits across every account. Double-entry bookkeeping only holds while this
                  is zero; any other number means an entry got in that should not have.
                </InfoTip>
              }
              actions={
                <Badge tone={trialBalance.data.balanced ? 'good' : 'bad'}>
                  {trialBalance.data.balanced ? 'Balanced' : 'OUT OF BALANCE'}
                </Badge>
              }
            >
              <p className={`text-3xl font-semibold tabular-nums ${trialBalance.data.balanced ? 'text-good' : 'text-bad'}`}>
                {trialBalance.data.outOfBalanceMinor}
              </p>
              <p className="mt-1 text-xs text-muted">
                Minor units. Every entry balances by construction, and a deferred database trigger refuses to commit one
                that does not — so this is a statement being checked, not assumed.
              </p>
            </Card>

            <Card title={`Accounts as of ${asOf}`}>
              {(trialBalance.data.rows ?? []).length === 0 ? (
                <Empty>No postings on or before this date.</Empty>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <caption className="sr-only">
                      Trial balance as of {asOf}: every account with its total debits, total credits and derived balance.
                    </caption>
                    <thead>
                      <tr className={theadRowClass}>
                        <th scope="col" className={thClass}>
                          Code
                        </th>
                        <th scope="col" className={thClass}>
                          Account
                        </th>
                        <th scope="col" aria-label="Type" className={thClass}>
                          <TipTerm
                            term="the account type"
                            tip="Asset, liability, equity, income or expense. The type decides which side increases the account, which is why a balance below can be negative without anything being wrong."
                          >
                            Type
                          </TipTerm>
                        </th>
                        <th scope="col" aria-label="Debit" className={`${thClass} text-right`}>
                          <TipTerm
                            term="the debit column"
                            align="end"
                            tip="The left side of an entry. It increases assets and expenses and decreases everything else. It does not mean money left the account."
                          >
                            Debit
                          </TipTerm>
                        </th>
                        <th scope="col" aria-label="Credit" className={`${thClass} text-right`}>
                          <TipTerm
                            term="the credit column"
                            align="end"
                            tip="The right side of an entry. It increases liabilities, equity and income. It does not mean money arrived in the account."
                          >
                            Credit
                          </TipTerm>
                        </th>
                        <th scope="col" className={`${thClass} text-right`}>
                          Balance
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {(trialBalance.data.rows ?? []).map((row) => (
                        <tr key={row.accountCode} className={tbodyRowClass}>
                          <th scope="row" className="py-1.5 text-left font-mono text-xs font-normal">
                            {row.accountCode}
                          </th>
                          <td className="py-1.5">{row.accountName}</td>
                          <td className="py-1.5 text-xs text-muted">{row.type}</td>
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
                      <tr className="font-semibold">
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
                </div>
              )}
            </Card>

            <p className="text-xs text-muted">
              <Link to="/accounts" className="text-accent underline">
                Browse the chart of accounts
              </Link>
            </p>
          </>
        )
      )}
    </>
  )
}
