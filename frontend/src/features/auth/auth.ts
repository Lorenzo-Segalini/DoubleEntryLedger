import { api, toSession } from '@/api/client'
import { tokenStore } from '@/api/tokenStore'
import type { MeResponse, TokenResponse } from '@/api/types'

export async function login(email: string, password: string) {
  const token = await api.anonymousPost<TokenResponse>('/api/v1/auth/login', { email, password })
  tokenStore.set(toSession(token))
  return tokenStore.get()
}

export async function logout() {
  try {
    await api.anonymousPost<void>('/api/v1/auth/logout', {})
  } finally {
    // Cleared whichever way the call went: staying "signed in" because the
    // server was unreachable is the wrong failure mode.
    tokenStore.clear()
  }
}

export function me() {
  return api.get<MeResponse>('/api/v1/auth/me')
}

/**
 * Restores a session on boot.
 *
 * The access token is in memory only, so a reload loses it — but the refresh
 * cookie survives, and the server will trade it for a new one. That is the whole
 * reason it is safe to keep the access token out of storage.
 */
export async function restoreSession() {
  try {
    const token = await api.anonymousPost<TokenResponse>('/api/v1/auth/refresh', {})
    tokenStore.set(toSession(token))
    return tokenStore.get()
  } catch {
    tokenStore.clear()
    return null
  }
}
