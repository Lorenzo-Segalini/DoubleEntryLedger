import { ApiError, toApiError } from '@/lib/problem'
import { tokenStore, type Session } from './tokenStore'
import type { AppRole, TokenResponse } from './types'

/**
 * Empty in development so requests go through the Vite proxy and the browser
 * sees a same-origin call — which is how the refresh cookie behaves in
 * production, where the SPA and API share a domain via Vercel's rewrite.
 */
const BASE = import.meta.env.VITE_API_BASE_URL ?? ''

/**
 * `| undefined` on every optional field is not noise: `exactOptionalPropertyTypes`
 * distinguishes "absent" from "present and undefined", and callers here do pass
 * an explicit undefined when a value is not available.
 */
export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE' | undefined
  body?: unknown
  /** Required by the server on every write. Generated once per form, not per submit. */
  idempotencyKey?: string | undefined
  signal?: AbortSignal | undefined
  /** Skips the refresh-and-retry dance. Used by the auth calls themselves. */
  anonymous?: boolean | undefined
  formData?: FormData | undefined
}

/**
 * The in-flight refresh, shared by every caller that hits a 401.
 *
 * Without this, a screen issuing four parallel queries on a stale token fires
 * four refreshes. Three of them present a token the first has already rotated,
 * and the server — correctly — reads that as a stolen credential and revokes the
 * whole family. The user is logged out by their own dashboard loading.
 */
let refreshInFlight: Promise<Session | null> | null = null

async function refreshSession(): Promise<Session | null> {
  refreshInFlight ??= (async () => {
    try {
      const response = await fetch(`${BASE}/api/v1/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
      })
      if (!response.ok) {
        tokenStore.clear()
        return null
      }
      const token = (await response.json()) as TokenResponse
      const session = toSession(token)
      tokenStore.set(session)
      return session
    } catch {
      tokenStore.clear()
      return null
    } finally {
      // Cleared in `finally` so a failed refresh does not poison every
      // subsequent attempt with a permanently rejected promise.
      refreshInFlight = null
    }
  })()

  return refreshInFlight
}

export function toSession(token: TokenResponse): Session {
  return {
    accessToken: token.accessToken ?? '',
    expiresAt: Date.parse(token.expiresAt ?? '') || Date.now() + (token.expiresIn ?? 0) * 1000,
    email: token.email ?? '',
    displayName: token.displayName ?? null,
    role: (token.role ?? 'AUDITOR') as AppRole,
  }
}

async function send(path: string, options: RequestOptions, token: string | null): Promise<Response> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'

  const init: RequestInit = {
    method: options.method ?? 'GET',
    headers,
    // The refresh cookie is HttpOnly and SameSite=Strict; it rides along on its
    // own, but only if credentials are included.
    credentials: 'include',
  }

  const body = options.formData ?? (options.body === undefined ? undefined : JSON.stringify(options.body))
  if (body !== undefined) init.body = body
  if (options.signal) init.signal = options.signal

  return fetch(`${BASE}${path}`, init)
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let session = tokenStore.get()

  // Refresh before sending rather than after failing: it costs one round trip
  // instead of two, and avoids burning an idempotency key on a request that was
  // never going to be authorised.
  if (!options.anonymous && session && tokenStore.isExpired()) {
    session = await refreshSession()
  }

  let response = await send(path, options, options.anonymous ? null : (session?.accessToken ?? null))

  if (response.status === 401 && !options.anonymous) {
    const refreshed = await refreshSession()
    if (refreshed) {
      // Same idempotency key on the retry, deliberately: if the first attempt
      // somehow posted before the 401, this replays that outcome rather than
      // posting again.
      response = await send(path, options, refreshed.accessToken)
    }
  }

  if (!response.ok) {
    throw await toApiError(response)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),

  post: <T>(path: string, body: unknown, idempotencyKey?: string) =>
    request<T>(path, { method: 'POST', body, idempotencyKey }),

  upload: <T>(path: string, formData: FormData) => request<T>(path, { method: 'POST', formData }),

  anonymousPost: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'POST', body, anonymous: true }),
}

export { ApiError }
