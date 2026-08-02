import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { useAuth } from '@/features/auth/useAuth'
import { Badge, Button } from '@/components/Ui'
import { InfoTip } from '@/components/Tooltip'

/*
  `current` rather than NavLink's `end`, because the two are not the same
  question. "Post entry" lives under "/entries", so NavLink marks both of them
  active on /entries/new and the page ends up with two aria-current="page"
  links — which is a contradiction, not an emphasis. Stating the match per item
  keeps Journal lit on an entry's detail page while /entries/new belongs to
  Post entry alone.
*/
const NAV: Array<{ to: string; label: string; current: (path: string) => boolean; requires?: 'post' }> = [
  { to: '/', label: 'Dashboard', current: (p) => p === '/' },
  { to: '/accounts', label: 'Accounts', current: (p) => p === '/accounts' || p.startsWith('/accounts/') },
  { to: '/entries', label: 'Journal', current: (p) => p === '/entries' || (p.startsWith('/entries/') && p !== '/entries/new') },
  { to: '/entries/new', label: 'Post entry', requires: 'post', current: (p) => p === '/entries/new' },
  {
    to: '/reconciliation',
    label: 'Reconciliation',
    current: (p) => p === '/reconciliation' || p.startsWith('/reconciliation/'),
  },
]

export function Layout() {
  const { session, signOut, can } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()

  return (
    <div className="min-h-dvh bg-canvas">
      {/*
        The first stop for a keyboard user on every page. Five nav links repeat
        on every screen, and without this each one has to be tabbed past to
        reach a table.
      */}
      <a
        href="#main"
        className="sr-only rounded bg-solid px-3 py-2 text-sm font-medium text-solid-fg focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50"
      >
        Skip to main content
      </a>

      <header className="border-b border-line bg-surface">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
          <span className="text-sm font-semibold text-fg">DoubleEntryLedger</span>

          <nav aria-label="Sections" className="flex flex-wrap gap-1">
            {NAV.filter((item) => !item.requires || can(item.requires)).map((item) => {
              const isCurrent = item.current(pathname)
              return (
                <Link
                  key={item.to}
                  to={item.to}
                  {...(isCurrent ? { 'aria-current': 'page' as const } : {})}
                  // The current tab is marked by weight and a rule under it, not
                  // by a background tint alone — a tint that clears 3:1 against
                  // the header is still not a distinction everyone can see.
                  className={`rounded px-2.5 py-1.5 text-sm ${
                    isCurrent
                      ? 'bg-surface-2 font-semibold text-fg underline decoration-2 underline-offset-4'
                      : 'text-muted hover:bg-surface-2 hover:text-fg'
                  }`}
                >
                  {item.label}
                </Link>
              )
            })}
          </nav>

          <div className="ml-auto flex items-center gap-2">
            <span className="text-xs text-muted">{session?.email}</span>
            <Badge tone={session?.role === 'AUDITOR' ? 'warn' : 'neutral'}>{session?.role}</Badge>
            <InfoTip term="the signed-in role" align="end">
              {session?.role === 'AUDITOR'
                ? 'An auditor can read every screen and change nothing. Controls that write are absent from the page rather than disabled, so nothing here offers an action that would be refused.'
                : 'Determines which controls appear. The same rules are enforced again by the API, so hiding a control is a convenience and never the protection itself.'}
            </InfoTip>
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

      <main id="main" tabIndex={-1} className="mx-auto max-w-7xl space-y-4 p-4">
        <Outlet />
      </main>
    </div>
  )
}
