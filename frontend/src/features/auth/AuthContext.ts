import { createContext } from 'react'
import type { Session } from '@/api/tokenStore'
import type { AppRole, PERMISSIONS } from '@/api/types'

export interface AuthState {
  session: Session | null
  loading: boolean
  role: AppRole | undefined
  signIn: (email: string, password: string) => Promise<void>
  signOut: () => Promise<void>
  can: (action: keyof typeof PERMISSIONS) => boolean
}

/**
 * In its own module so the provider file exports only a component — otherwise
 * Vite's fast refresh silently stops working for the whole subtree.
 */
export const AuthContext = createContext<AuthState | null>(null)
