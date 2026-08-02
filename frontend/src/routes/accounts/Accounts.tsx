import { Link } from 'react-router'
import { useAccounts } from '@/features/ledger/queries'
import { Badge, Card, Empty, Spinner, tbodyRowClass, theadRowClass, thClass } from '@/components/Ui'
import { InfoTip, TipTerm } from '@/components/Tooltip'
import { ErrorNotice } from '@/components/ErrorNotice'

export function Accounts() {
  const accounts = useAccounts()

  return (
    <>
      <div className="flex items-center gap-0.5">
        <h1 className="text-lg font-semibold text-fg">Chart of accounts</h1>
        <InfoTip term="a chart of accounts">
          The full list of accounts a journal entry may post to. Each has a code, a type and one currency, and none of
          them is ever deleted — an account that fell out of use is archived so its history stays readable.
        </InfoTip>
      </div>
      <ErrorNotice error={accounts.error} />

      <Card>
        {accounts.isPending ? (
          <Spinner />
        ) : (accounts.data ?? []).length === 0 ? (
          <Empty>No accounts yet.</Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <caption className="sr-only">
                Every account in the chart, with its type, currency and status. Select a code to open its movements.
              </caption>
              <thead>
                <tr className={theadRowClass}>
                  <th scope="col" className={thClass}>
                    Code
                  </th>
                  <th scope="col" className={thClass}>
                    Name
                  </th>
                  <th scope="col" aria-label="Type" className={thClass}>
                    <TipTerm
                      term="the account type"
                      tip="Asset, liability, equity, income or expense. The type decides which side of an entry increases the account."
                    >
                      Type
                    </TipTerm>
                  </th>
                  <th scope="col" className={thClass}>
                    Currency
                  </th>
                  <th scope="col" aria-label="Status" className={thClass}>
                    <TipTerm
                      term="the account status"
                      align="end"
                      tip="Archived accounts accept no new postings but keep every entry already made against them. Nothing in this ledger is deleted."
                    >
                      Status
                    </TipTerm>
                  </th>
                </tr>
              </thead>
              <tbody>
                {(accounts.data ?? []).map((account) => (
                  <tr key={account.id} className={tbodyRowClass}>
                    <th scope="row" className="py-1.5 text-left font-mono text-xs font-normal">
                      <Link to={`/accounts/${account.id}`} className="text-accent underline">
                        {account.code}
                      </Link>
                    </th>
                    <td className="py-1.5">{account.name}</td>
                    <td className="py-1.5 text-xs text-muted">{account.type}</td>
                    <td className="py-1.5 text-xs">{account.currency}</td>
                    <td className="py-1.5">
                      {account.status === 'ACTIVE' ? (
                        <Badge tone="good">Active</Badge>
                      ) : (
                        <Badge tone="warn">Archived</Badge>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  )
}
