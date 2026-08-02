import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useAuth } from '@/features/auth/useAuth'
import { ErrorNotice } from '@/components/ErrorNotice'
import { Button, Field, inputClass } from '@/components/Ui'
import { InfoTip } from '@/components/Tooltip'

/**
 * The demo credentials are published in the README, so the buttons that prefill
 * them are documentation rather than a shortcut. Signing in as the auditor is
 * the fastest way to see the point of the role: every write control disappears.
 */
const DEMO_LOGINS = [
  {
    role: 'Operator',
    email: 'operator@demo.local',
    password: 'demo-operator',
    can: 'post and reconcile',
    tip: 'Posts journal entries and reconciles statements. Cannot create accounts or change anyone else’s access.',
  },
  {
    role: 'Auditor',
    email: 'auditor@demo.local',
    password: 'demo-auditor',
    can: 'read everything, change nothing',
    tip: 'Reads every screen. Controls that write are absent from the page rather than greyed out — the clearest way to see what the role means.',
  },
  {
    role: 'Admin',
    email: 'admin@demo.local',
    password: 'demo-admin',
    can: 'everything',
    tip: 'Everything an operator can do, plus managing the chart of accounts.',
  },
]

export function Login() {
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await signIn(email, password)
      navigate('/', { replace: true })
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    // `bg-canvas` matches every other screen. This page used to inherit the
    // browser's white, which under a dark colour scheme is not a design choice
    // but a flash of the wrong page.
    <div className="min-h-dvh bg-canvas">
      <main className="mx-auto flex min-h-dvh max-w-md flex-col justify-center gap-6 p-6">
        <div>
          <h1 className="text-xl font-semibold text-fg">DoubleEntryLedger</h1>
          <p className="mt-1 text-sm text-muted">Back office</p>
        </div>

        <form onSubmit={submit} className="space-y-3 rounded-lg border border-line bg-surface p-4 shadow-sm">
          <Field label="Email">
            <input className={inputClass} type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </Field>
          <Field label="Password">
            <input
              className={inputClass}
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </Field>

          <ErrorNotice error={error} />

          <Button type="submit" disabled={busy} className="w-full">
            {busy ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>

        <div className="rounded-lg border border-line bg-surface p-3">
          <p className="mb-2 text-xs font-medium text-fg">Demo accounts</p>
          <ul className="space-y-1">
            {DEMO_LOGINS.map((demo) => (
              <li key={demo.email} className="flex items-center gap-1">
                <button
                  type="button"
                  onClick={() => {
                    setEmail(demo.email)
                    setPassword(demo.password)
                  }}
                  className="flex-1 rounded px-2 py-1 text-left text-xs hover:bg-surface-2"
                >
                  <span className="font-medium text-fg">{demo.role}</span>
                  <span className="text-muted"> — {demo.can}</span>
                </button>
                <InfoTip term={`the ${demo.role} role`} align="end">
                  {demo.tip}
                </InfoTip>
              </li>
            ))}
          </ul>
        </div>
      </main>
    </div>
  )
}
