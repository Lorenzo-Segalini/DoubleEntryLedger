import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { tokenStore, type Session } from '@/api/tokenStore'
import { can } from '@/api/types'
import { AuthContext, type AuthState } from './AuthContext'
import { login as doLogin, logout as doLogout, restoreSession } from './auth'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(tokenStore.get())
  const [loading, setLoading] = useState(true)

  useEffect(() => tokenStore.subscribe(setSession), [])

  useEffect(() => {
    // One attempt at a silent restore before rendering anything that depends on
    // being signed in, so a reload does not flash the login screen.
    restoreSession().finally(() => setLoading(false))
  }, [])

  const value = useMemo<AuthState>(
    () => ({
      session,
      loading,
      role: session?.role,
      signIn: async (email, password) => {
        await doLogin(email, password)
      },
      signOut: async () => {
        await doLogout()
      },
      can: (action) => can(session?.role, action),
    }),
    [session, loading],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}
