import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { useAccounts } from '@/features/ledger/queries'
import { useImportStatement, useReconciliations } from '@/features/reconciliation/queries'
import { useAuth } from '@/features/auth/useAuth'
import { Badge, Button, Card, Empty, Field, Spinner, inputClass } from '@/components/Ui'
import { ErrorNotice } from '@/components/ErrorNotice'
import { currency, parse } from '@/lib/money'
import { formatBusinessDate } from '@/lib/dates'

export function Reconciliations() {
  const runs = useReconciliations()
  const { can } = useAuth()

  return (
    <>
      <div>
        <h1 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Reconciliation</h1>
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Compare a bank statement against the journal and explain every difference.
        </p>
      </div>

      {can('reconcile') && <ImportForm />}

      <Card title="Past runs">
        {runs.isPending ? (
          <Spinner />
        ) : (runs.data ?? []).length === 0 ? (
          <Empty>No statements imported yet.</Empty>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left text-xs uppercase text-slate-500 dark:border-slate-700">
                <th className="pb-2 font-medium">Account</th>
                <th className="pb-2 font-medium">Period</th>
                <th className="pb-2 font-medium">File</th>
                <th className="pb-2 font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {(runs.data ?? []).map((run) => (
                <tr key={run.id} className="border-b border-slate-100 dark:border-slate-800">
                  <td className="py-1.5 font-mono text-xs">
                    <Link to={`/reconciliation/${run.id}`} className="underline">
                      {run.accountCode}
                    </Link>
                  </td>
                  <td className="py-1.5 text-xs">
                    {formatBusinessDate(run.periodStart ?? '')} – {formatBusinessDate(run.periodEnd ?? '')}
                  </td>
                  <td className="py-1.5 text-xs text-slate-500">{run.sourceFilename}</td>
                  <td className="py-1.5">
                    <Badge tone={run.status === 'COMPLETED' ? 'good' : run.status === 'FAILED' ? 'bad' : 'warn'}>
                      {run.status}
                    </Badge>
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

function ImportForm() {
  const accounts = useAccounts()
  const importStatement = useImportStatement()
  const navigate = useNavigate()

  const [file, setFile] = useState<File | null>(null)
  const [accountCode, setAccountCode] = useState('1000')
  const [periodStart, setPeriodStart] = useState('2026-06-01')
  const [periodEnd, setPeriodEnd] = useState('2026-06-30')
  const [opening, setOpening] = useState('0.00')
  const [closing, setClosing] = useState('0.00')

  const unit = currency('EUR')

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!file) return

    importStatement.mutate(
      {
        file,
        accountCode,
        periodStart,
        periodEnd,
        openingBalanceMinor: parse(opening, unit).amountMinor,
        closingBalanceMinor: parse(closing, unit).amountMinor,
      },
      { onSuccess: (report) => navigate(`/reconciliation/${report.importId}`) },
    )
  }

  return (
    <Card title="Import a statement">
      <form onSubmit={submit} className="grid gap-3 sm:grid-cols-3">
        <Field label="CSV file" hint="value_date, amount, currency, description, external_id">
          <input
            type="file"
            accept=".csv,text/csv"
            className={inputClass}
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            required
          />
        </Field>
        <Field label="Account">
          <select className={inputClass} value={accountCode} onChange={(e) => setAccountCode(e.target.value)}>
            {(accounts.data ?? []).map((account) => (
              <option key={account.id} value={account.code}>
                {account.code} · {account.name}
              </option>
            ))}
          </select>
        </Field>
        <div />
        <Field label="Period start">
          <input type="date" className={inputClass} value={periodStart} onChange={(e) => setPeriodStart(e.target.value)} />
        </Field>
        <Field label="Period end">
          <input type="date" className={inputClass} value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
        </Field>
        <div />
        <Field label="Opening balance" hint="As the statement declares it">
          <input className={`${inputClass} text-right tabular-nums`} value={opening} onChange={(e) => setOpening(e.target.value)} />
        </Field>
        <Field label="Closing balance">
          <input className={`${inputClass} text-right tabular-nums`} value={closing} onChange={(e) => setClosing(e.target.value)} />
        </Field>
        <div className="flex items-end">
          <Button type="submit" disabled={!file || importStatement.isPending}>
            {importStatement.isPending ? 'Reconciling…' : 'Import and reconcile'}
          </Button>
        </div>
      </form>

      <p className="mt-3 text-xs text-slate-500">
        The statement&rsquo;s own arithmetic is checked first: opening plus the rows must equal the declared closing.
        Reconciling against a file that does not add up produces confident nonsense.
      </p>

      <div className="mt-3">
        <ErrorNotice error={importStatement.error} />
      </div>
    </Card>
  )
}
