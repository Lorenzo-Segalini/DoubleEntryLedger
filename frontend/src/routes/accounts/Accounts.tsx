import { Link } from 'react-router'
import { useAccounts } from '@/features/ledger/queries'
import { Badge, Card, Empty, Spinner } from '@/components/Ui'
import { ErrorNotice } from '@/components/ErrorNotice'

export function Accounts() {
  const accounts = useAccounts()

  return (
    <>
      <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Chart of accounts</h1>
      <ErrorNotice error={accounts.error} />

      <Card>
        {accounts.isPending ? (
          <Spinner />
        ) : (accounts.data ?? []).length === 0 ? (
          <Empty>No accounts yet.</Empty>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left text-xs uppercase text-slate-500 dark:border-slate-700">
                <th className="pb-2 font-medium">Code</th>
                <th className="pb-2 font-medium">Name</th>
                <th className="pb-2 font-medium">Type</th>
                <th className="pb-2 font-medium">Currency</th>
                <th className="pb-2 font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {(accounts.data ?? []).map((account) => (
                <tr key={account.id} className="border-b border-slate-100 dark:border-slate-800">
                  <td className="py-1.5 font-mono text-xs">
                    <Link to={`/accounts/${account.id}`} className="underline">
                      {account.code}
                    </Link>
                  </td>
                  <td className="py-1.5">{account.name}</td>
                  <td className="py-1.5 text-xs text-slate-500">{account.type}</td>
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
        )}
      </Card>
    </>
  )
}
