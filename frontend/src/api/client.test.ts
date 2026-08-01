import { beforeEach, describe, expect, it } from 'vitest'
import { api, request } from './client'
import { tokenStore } from './tokenStore'
import { ApiError } from '@/lib/problem'
import { http, HttpResponse, problem, server, tokenResponse } from '@/test/server'

/**
 * The client's job is not "call fetch". It is to keep a stale token from turning
 * into a user-visible failure, and to do that without stampeding the refresh
 * endpoint — which on this backend would be read as a stolen credential.
 */
describe('the API client', () => {
  beforeEach(() => {
    tokenStore.set({
      accessToken: 'stale',
      expiresAt: Date.now() + 600_000,
      email: 'operator@demo.local',
      displayName: 'Demo',
      role: 'OPERATOR',
    })
  })

  it('sends the access token as a bearer credential', async () => {
    let seen: string | null = null
    server.use(
      http.get('/api/v1/accounts', ({ request: req }) => {
        seen = req.headers.get('Authorization')
        return HttpResponse.json([])
      }),
    )

    await api.get('/api/v1/accounts')

    expect(seen).toBe('Bearer stale')
  })

  it('passes the idempotency key through on writes', async () => {
    let seen: string | null = null
    server.use(
      http.post('/api/v1/transfers', ({ request: req }) => {
        seen = req.headers.get('Idempotency-Key')
        return HttpResponse.json({ id: 'e-1' }, { status: 201 })
      }),
    )

    await api.post('/api/v1/transfers', {}, 'key-abc')

    expect(seen).toBe('key-abc')
  })

  it('refreshes once and retries after a 401', async () => {
    let attempts = 0
    let refreshes = 0

    server.use(
      http.get('/api/v1/accounts', () => {
        attempts += 1
        return attempts === 1 ? new HttpResponse(null, { status: 401 }) : HttpResponse.json([{ id: 'a-1' }])
      }),
      http.post('/api/v1/auth/refresh', () => {
        refreshes += 1
        return HttpResponse.json(tokenResponse('OPERATOR', 'fresh'))
      }),
    )

    const result = await api.get<unknown[]>('/api/v1/accounts')

    expect(result).toHaveLength(1)
    expect(refreshes).toBe(1)
    expect(tokenStore.get()?.accessToken).toBe('fresh')
  })

  /**
   * The test this module exists for.
   *
   * Four parallel queries on a stale token must produce one refresh, not four.
   * The extras would present a token the first has already rotated, and the
   * server — correctly — reads that as a stolen credential and revokes the whole
   * family. The user is signed out by their own dashboard loading.
   */
  it('coalesces concurrent refreshes into one', async () => {
    let refreshes = 0
    const seenTokens: (string | null)[] = []

    server.use(
      http.get('/api/v1/accounts', ({ request: req }) => {
        const auth = req.headers.get('Authorization')
        if (auth === 'Bearer stale') return new HttpResponse(null, { status: 401 })
        seenTokens.push(auth)
        return HttpResponse.json([])
      }),
      http.post('/api/v1/auth/refresh', async () => {
        refreshes += 1
        // A real refresh takes time; without the delay every caller would have
        // finished before the next one started and the race would not occur.
        await new Promise((resolve) => setTimeout(resolve, 25))
        return HttpResponse.json(tokenResponse('OPERATOR', 'fresh'))
      }),
    )

    await Promise.all([
      api.get('/api/v1/accounts'),
      api.get('/api/v1/accounts'),
      api.get('/api/v1/accounts'),
      api.get('/api/v1/accounts'),
    ])

    expect(refreshes).toBe(1)
    expect(seenTokens).toEqual(['Bearer fresh', 'Bearer fresh', 'Bearer fresh', 'Bearer fresh'])
  })

  it('refreshes proactively when the token is about to expire', async () => {
    // Inside the 30-second skew: sending this would very likely come back 401.
    tokenStore.set({
      accessToken: 'about-to-expire',
      expiresAt: Date.now() + 5_000,
      email: 'operator@demo.local',
      displayName: 'Demo',
      role: 'OPERATOR',
    })

    let seen: string | null = null
    server.use(
      http.get('/api/v1/accounts', ({ request: req }) => {
        seen = req.headers.get('Authorization')
        return HttpResponse.json([])
      }),
      http.post('/api/v1/auth/refresh', () => HttpResponse.json(tokenResponse('OPERATOR', 'fresh'))),
    )

    await api.get('/api/v1/accounts')

    // One round trip instead of two, and no idempotency key burnt on a request
    // that was never going to be authorised.
    expect(seen).toBe('Bearer fresh')
  })

  it('clears the session when the refresh itself is refused', async () => {
    server.use(
      http.get('/api/v1/accounts', () => new HttpResponse(null, { status: 401 })),
      http.post('/api/v1/auth/refresh', () => new HttpResponse(null, { status: 401 })),
    )

    await expect(api.get('/api/v1/accounts')).rejects.toBeInstanceOf(ApiError)
    expect(tokenStore.get()).toBeNull()
  })

  it('does not poison later attempts after a failed refresh', async () => {
    let refreshOk = false
    server.use(
      http.get('/api/v1/accounts', ({ request: req }) =>
        req.headers.get('Authorization') === 'Bearer fresh'
          ? HttpResponse.json([])
          : new HttpResponse(null, { status: 401 }),
      ),
      http.post('/api/v1/auth/refresh', () =>
        refreshOk ? HttpResponse.json(tokenResponse('OPERATOR', 'fresh')) : new HttpResponse(null, { status: 401 }),
      ),
    )

    await expect(api.get('/api/v1/accounts')).rejects.toBeInstanceOf(ApiError)

    // The in-flight promise is cleared in `finally`, so a later attempt starts a
    // new refresh rather than awaiting a permanently rejected one.
    refreshOk = true
    tokenStore.set({
      accessToken: 'stale',
      expiresAt: Date.now() + 600_000,
      email: 'operator@demo.local',
      displayName: 'Demo',
      role: 'OPERATOR',
    })
    await expect(api.get('/api/v1/accounts')).resolves.toEqual([])
  })

  it('parses problem+json into an error carrying the code and the request id', async () => {
    server.use(
      http.post('/api/v1/journal-entries', () =>
        problem(422, {
          type: 'https://ledger.lseg.dev/problems/unbalanced-entry',
          title: 'Unbalanced entry',
          detail: 'entry is unbalanced by 1000 minor units',
          code: 'UNBALANCED_ENTRY',
          requestId: 'req-42',
          details: { differenceMinor: 1000 },
        }),
      ),
    )

    const error = await api.post('/api/v1/journal-entries', {}, 'k').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.status).toBe(422)
    expect(apiError.is('UNBALANCED_ENTRY')).toBe(true)
    expect(apiError.requestId).toBe('req-42')
    expect(apiError.problem.details).toEqual({ differenceMinor: 1000 })
  })

  it('still produces a usable error when the body is not problem+json', async () => {
    // A gateway timeout or a proxy error page. The UI needs something to show.
    server.use(http.get('/api/v1/accounts', () => new HttpResponse('<html>502</html>', { status: 502 })))

    const error = (await api.get('/api/v1/accounts').catch((e: unknown) => e)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(502)
    expect(error.message).toContain('502')
  })

  it('returns nothing for a 204 rather than failing to parse an empty body', async () => {
    server.use(http.post('/api/v1/reconciliations/x/breaks/y/explain', () => new HttpResponse(null, { status: 204 })))

    await expect(request('/api/v1/reconciliations/x/breaks/y/explain', { method: 'POST', body: {} })).resolves
      .toBeUndefined()
  })
})
