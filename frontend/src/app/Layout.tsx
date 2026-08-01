import { NavLink, Outlet, useNavigate } from 'react-router'
import { useAuth } from '@/features/auth/useAuth'
import { Badge, Button } from '@/components/Ui'

const NAV: Array<{ to: string; label: string; end?: boolean; requires?: 'post' }> = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/accounts', label: 'Accounts' },
  { to: '/entries', label: 'Journal' },
  { to: '/entries/new', label: 'Post entry', requires: 'post' },
  { to: '/reconciliation', label: 'Reconciliation' },
]

export function Layout() {
  const { session, signOut, can } = useAuth()
  const navigate = useNavigate()

  return (
    <div className="min-h-dvh bg-slate-50 dark:bg-slate-950">
      <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <div className="mx-auto flex max-w-7xl items-center gap-6 px-4 py-3">
          <span className="text-sm font-semibold text-slate-900 dark:text-slate-100">DoubleEntryLedger</span>

          <nav className="flex gap-1">
            {NAV.filter((item) => !item.requires || can(item.requires)).map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end ?? false}
                className={({ isActive }) =>
                  `rounded px-2.5 py-1.5 text-sm ${
                    isActive
                      ? 'bg-slate-100 font-medium text-slate-900 dark:bg-slate-800 dark:text-slate-100'
                      : 'text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100'
                  }`
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-3">
            <span className="text-xs text-slate-500">{session?.email}</span>
            <Badge tone={session?.role === 'AUDITOR' ? 'warn' : 'neutral'}>{session?.role}</Badge>
            <Button
              variant="ghost"
              onClick={async () => {
                await signOut()
                navigate('/login', { replace: true })
              }}
            >
              Sign out
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-7xl space-y-4 p-4">
        <Outlet />
      </main>
    </div>
  )
}
