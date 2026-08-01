import { Navigate, useLocation } from 'react-router'
import type { ReactNode } from 'react'
import { useAuth } from '@/features/auth/useAuth'
import { Spinner } from '@/components/Ui'
import type { PERMISSIONS } from '@/api/types'

/**
 * Gates a route.
 *
 * Ergonomics, never security: the same rules are enforced independently at the
 * service layer, so a user who reaches a hidden route by typing the URL still
 * gets a 403 from the API. What this avoids is offering a control that will fail.
 */
export function RequireAuth({
  children,
  requires,
}: {
  children: ReactNode
  requires?: keyof typeof PERMISSIONS
}) {
  const { session, loading, can } = useAuth()
  const location = useLocation()

  if (loading) return <Spinner label="Restoring session" />
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (requires && !can(requires)) return <Navigate to="/" replace />

  return <>{children}</>
}
