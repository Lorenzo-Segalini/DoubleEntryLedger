import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useAuth } from '@/features/auth/useAuth'
import { ErrorNotice } from '@/components/ErrorNotice'
import { Button, Field, inputClass } from '@/components/Ui'

/**
 * The demo credentials are published in the README, so the buttons that prefill
 * them are documentation rather than a shortcut. Signing in as the auditor is
 * the fastest way to see the point of the role: every write control disappears.
 */
const DEMO_LOGINS = [
  { role: 'Operator', email: 'operator@demo.local', password: 'demo-operator', can: 'post and reconcile' },
  { role: 'Auditor', email: 'auditor@demo.local', password: 'demo-auditor', can: 'read everything, change nothing' },
  { role: 'Admin', email: 'admin@demo.local', password: 'demo-admin', can: 'everything' },
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
    <main className="mx-auto flex min-h-dvh max-w-md flex-col justify-center gap-6 p-6">
      <div>
        <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">DoubleEntryLedger</h1>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">Back office</p>
      </div>

      <form onSubmit={submit} className="space-y-3">
        <Field label="Email">
          <input
            className={inputClass}
            type="email"
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
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

      <div className="rounded border border-slate-200 p-3 dark:border-slate-700">
        <p className="mb-2 text-xs font-medium text-slate-700 dark:text-slate-300">Demo accounts</p>
        <ul className="space-y-1">
          {DEMO_LOGINS.map((demo) => (
            <li key={demo.email}>
              <button
                type="button"
                onClick={() => {
                  setEmail(demo.email)
                  setPassword(demo.password)
                }}
                className="w-full rounded px-2 py-1 text-left text-xs hover:bg-slate-100 dark:hover:bg-slate-800"
              >
                <span className="font-medium text-slate-900 dark:text-slate-100">{demo.role}</span>
                <span className="text-slate-500"> — {demo.can}</span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </main>
  )
}
