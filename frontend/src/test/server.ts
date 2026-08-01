import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'

/**
 * MSW intercepts at the network layer, not by replacing modules.
 *
 * That distinction is what makes these tests worth having: the real fetch call,
 * the real headers, the real 401-then-refresh path all execute. Mocking the
 * client module would only prove the components call a function.
 */
export const server = setupServer()

export { http, HttpResponse }

export function problem(status: number, body: Record<string, unknown>) {
  return HttpResponse.json({ status, ...body }, { status, headers: { 'Content-Type': 'application/problem+json' } })
}

export const ACCOUNTS = [
  { id: 'a-1000', code: '1000', name: 'Cash at Bank', type: 'ASSET', currency: 'EUR', status: 'ACTIVE' },
  { id: 'a-4000', code: '4000', name: 'Revenue', type: 'REVENUE', currency: 'EUR', status: 'ACTIVE' },
]

export function tokenResponse(role: 'OPERATOR' | 'AUDITOR' | 'ADMIN' = 'OPERATOR', accessToken = 'token-1') {
  return {
    accessToken,
    tokenType: 'Bearer',
    expiresIn: 900,
    expiresAt: new Date(Date.now() + 900_000).toISOString(),
    email: `${role.toLowerCase()}@demo.local`,
    displayName: `Demo ${role}`,
    role,
  }
}
