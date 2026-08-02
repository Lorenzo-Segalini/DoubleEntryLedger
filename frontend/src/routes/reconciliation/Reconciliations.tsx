import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { useAccounts } from '@/features/ledger/queries'
import { useImportStatement, useReconciliations } from '@/features/reconciliation/queries'
import { useAuth } from '@/features/auth/useAuth'
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
import { currency, parse } from '@/lib/money'
import { formatBusinessDate } from '@/lib/dates'

export function Reconciliations() {
  const runs = useReconciliations()
  const { can } = useAuth()

  return (
    <>
      <div>
        <div className="flex items-center gap-0.5">
          <h1 className="text-lg font-semibold text-fg">Reconciliation</h1>
          <InfoTip term="reconciliation">
            Comparing a bank statement against the ledger line by line, then explaining every remaining difference. It
            finishes when the explanations add up to the gap exactly — not when the gap looks small.
          </InfoTip>
        </div>
        <p className="text-sm text-muted">
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
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <caption className="sr-only">
                Statements imported so far. Select an account code to open that run&rsquo;s report.
              </caption>
              <thead>
                <tr className={theadRowClass}>
                  <th scope="col" className={thClass}>
                    Account
                  </th>
                  <th scope="col" aria-label="Period" className={thClass}>
                    <TipTerm
                      term="the statement period"
                      tip="The span the statement covers. Only postings whose effective date falls inside it are compared, which is why an entry posted late still matches."
                    >
                      Period
                    </TipTerm>
                  </th>
                  <th scope="col" className={thClass}>
                    File
                  </th>
                  <th scope="col" className={thClass}>
                    Status
                  </th>
                </tr>
              </thead>
              <tbody>
                {(runs.data ?? []).map((run) => (
                  <tr key={run.id} className={tbodyRowClass}>
                    <th scope="row" className="py-1.5 text-left font-mono text-xs font-normal">
                      <Link to={`/reconciliation/${run.id}`} className="text-accent underline">
                        {run.accountCode}
                      </Link>
                    </th>
                    <td className="py-1.5 text-xs">
                      {formatBusinessDate(run.periodStart ?? '')} – {formatBusinessDate(run.periodEnd ?? '')}
                    </td>
                    <td className="py-1.5 text-xs text-muted">{run.sourceFilename}</td>
                    <td className="py-1.5">
                      <Badge tone={run.status === 'COMPLETED' ? 'good' : run.status === 'FAILED' ? 'bad' : 'warn'}>
                        {run.status}
                      </Badge>
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

  /*
    Every field here is explained by visible hint text rather than a tooltip.
    These are the inputs a first-time visitor has to get right before anything
    on the next screen means anything, and help that has to be hovered or
    focused to appear is help most people never find.
  */
  return (
    <Card title="Import a statement">
      <form onSubmit={submit} className="grid gap-3 sm:grid-cols-3">
        <Field label="CSV file" hint="Columns: value_date, amount, currency, description, external_id">
          <input
            type="file"
            accept=".csv,text/csv"
            className={inputClass}
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            required
          />
        </Field>
        <Field label="Account" hint="The ledger account this statement belongs to">
          <select className={inputClass} value={accountCode} onChange={(e) => setAccountCode(e.target.value)}>
            {(accounts.data ?? []).map((account) => (
              <option key={account.id} value={account.code}>
                {account.code} · {account.name}
              </option>
            ))}
          </select>
        </Field>
        <div />
        <Field label="Period start" hint="First day the statement covers">
          <input type="date" className={inputClass} value={periodStart} onChange={(e) => setPeriodStart(e.target.value)} />
        </Field>
        <Field label="Period end" hint="Last day the statement covers">
          <input type="date" className={inputClass} value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
        </Field>
        <div />
        <Field label="Opening balance" hint="As the statement declares it, not as the ledger has it">
          <input className={`${inputClass} text-right tabular-nums`} value={opening} onChange={(e) => setOpening(e.target.value)} />
        </Field>
        <Field label="Closing balance" hint="Opening plus every row on the statement must equal this">
          <input className={`${inputClass} text-right tabular-nums`} value={closing} onChange={(e) => setClosing(e.target.value)} />
        </Field>
        <div className="flex items-end">
          <Button type="submit" disabled={!file || importStatement.isPending}>
            {importStatement.isPending ? 'Reconciling…' : 'Import and reconcile'}
          </Button>
        </div>
      </form>

      <p className="mt-3 text-xs text-muted">
        The statement&rsquo;s own arithmetic is checked first: opening plus the rows must equal the declared closing.
        Reconciling against a file that does not add up produces confident nonsense.
      </p>

      <div className="mt-3">
        <ErrorNotice error={importStatement.error} />
      </div>
    </Card>
  )
}
