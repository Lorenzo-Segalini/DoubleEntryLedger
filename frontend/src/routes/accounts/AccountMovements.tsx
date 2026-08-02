import { useState } from 'react'
import { useParams } from 'react-router'
import { useAccount, useBalance } from '@/features/ledger/queries'
import { Money } from '@/components/Money'
import { Card, Field, Spinner, inputClass } from '@/components/Ui'
import { InfoTip } from '@/components/Tooltip'
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
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-lg font-semibold text-fg">
            {account.data ? `${account.data.code} · ${account.data.name}` : 'Account'}
          </h1>
          <p className="text-sm text-muted">
            {account.data?.type} · {account.data?.currency}
          </p>
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

      <ErrorNotice error={account.error ?? balance.error} />

      {balance.isPending ? (
        <Spinner />
      ) : (
        balance.data && (
          <Card title={`Balance as of ${asOf}`}>
            {/*
              The term goes straight in the <dt>, with the tooltip as its
              sibling — not wrapped together in a span. A description list's
              term names nothing else on the page, so it can hold a control
              without renaming anything, and keeping the text a direct child
              means "the element containing this label" is still the <dt> whose
              <dd> carries the figure.
            */}
            <dl className="grid gap-2 text-sm sm:grid-cols-4">
              <div>
                <dt className="flex items-center gap-0.5 text-xs text-muted">
                  Total debits
                  <InfoTip term="total debits">
                    Everything posted to the left side of this account up to this date. For an asset account that is
                    money in; for an income account it is money out.
                  </InfoTip>
                </dt>
                <dd className="text-lg">
                  <Money value={balance.data.totalDebit} />
                </dd>
              </div>
              <div>
                <dt className="flex items-center gap-0.5 text-xs text-muted">
                  Total credits
                  <InfoTip term="total credits">
                    Everything posted to the right side of this account up to this date. Which direction represents
                    money arriving depends on the account&rsquo;s type.
                  </InfoTip>
                </dt>
                <dd className="text-lg">
                  <Money value={balance.data.totalCredit} />
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted">Balance</dt>
                <dd className="text-lg font-semibold">
                  <Money value={balance.data.balance} />
                </dd>
              </div>
              <div>
                <dt className="flex items-center gap-0.5 text-xs text-muted">
                  Lines
                  <InfoTip term="the line count" align="end">
                    How many journal lines the balance above was summed from. It is the size of the evidence, not a
                    balance of its own.
                  </InfoTip>
                </dt>
                <dd className="text-lg tabular-nums">{balance.data.lineCount}</dd>
              </div>
            </dl>

            <p className="mt-4 flex flex-wrap items-center gap-1 border-t border-line pt-3 text-xs text-muted">
              <span>Derived at {balance.data.derivedAt ? formatInstant(balance.data.derivedAt) : '—'}.</span>
              <InfoTip term="derived at">
                The moment this page did the sum. No balance is stored anywhere, so this timestamp is when the number
                came into existence — asking again re-derives it from the same lines.
              </InfoTip>
              <span>
                No balance is stored; this is a sum over the account&rsquo;s own lines, so the same date returns the same
                number in a year&rsquo;s time.
              </span>
            </p>
          </Card>
        )
      )}
    </>
  )
}
