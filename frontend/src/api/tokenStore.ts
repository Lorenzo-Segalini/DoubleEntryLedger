/**
 * The access token lives here and nowhere else.
 *
 * Deliberately a module-scoped variable, not `localStorage` or `sessionStorage`:
 * anything an injected script can read, it will. The refresh token is an
 * `HttpOnly` cookie the browser sends to `/api/v1/auth` on its own, which is why
 * losing this on reload is survivable — the client asks for a new access token
 * and carries on.
 */

import type { AppRole } from './types'

export interface Session {
  accessToken: string
  expiresAt: number
  email: string
  displayName: string | null
  role: AppRole
}

let session: Session | null = null

type Listener = (session: Session | null) => void
const listeners = new Set<Listener>()

export const tokenStore = {
  get(): Session | null {
    return session
  },

  set(next: Session | null): void {
    session = next
    listeners.forEach((listener) => listener(next))
  },

  clear(): void {
    tokenStore.set(null)
  },

  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },

  /**
   * Treats a token as expired slightly early.
   *
   * A token that passes the check here and expires in flight produces a 401 the
   * user sees as a random failure. Thirty seconds of margin costs one extra
   * refresh and removes the whole class of race.
   */
  isExpired(skewSeconds = 30): boolean {
    if (!session) return true
    return Date.now() >= session.expiresAt - skewSeconds * 1000
  },
}
