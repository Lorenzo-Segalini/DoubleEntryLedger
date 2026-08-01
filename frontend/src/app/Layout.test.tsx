import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { Layout } from './Layout'
import { renderWithProviders } from '@/test/render'
import { http, HttpResponse, server, tokenResponse } from '@/test/server'
import { tokenStore } from '@/api/tokenStore'
import type { AppRole } from '@/api/types'

/**
 * Seeds the session *and* the refresh endpoint with the same role.
 *
 * AuthProvider attempts a silent restore on mount, and the server's answer wins
 * — correctly, since it is the authority on who you are. A handler that always
 * replied OPERATOR would therefore overwrite the role under test, and the
 * assertions below would be measuring the fixture rather than the component.
 */
function signedInAs(role: AppRole) {
  tokenStore.set({
    accessToken: 'token',
    expiresAt: Date.now() + 600_000,
    email: `${role.toLowerCase()}@demo.local`,
    displayName: `Demo ${role}`,
    role,
  })
  server.use(http.post('/api/v1/auth/refresh', () => HttpResponse.json(tokenResponse(role, 'token'))))
}

describe('the navigation', () => {
  it('offers posting to an operator', async () => {
    signedInAs('OPERATOR')
    renderWithProviders(<Layout />)

    expect(await screen.findByRole('link', { name: 'Post entry' })).toBeInTheDocument()
  })

  it('offers an auditor nothing to change', async () => {
    signedInAs('AUDITOR')
    renderWithProviders(<Layout />)

    await screen.findByRole('link', { name: 'Dashboard' })

    // Absent from the DOM, not merely disabled: a disabled control still tells a
    // user the operation exists for them, and it does not.
    expect(screen.queryByRole('link', { name: 'Post entry' })).not.toBeInTheDocument()

    // Everything readable is still offered.
    expect(screen.getByRole('link', { name: 'Accounts' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Journal' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Reconciliation' })).toBeInTheDocument()
  })

  it('shows which role is signed in', async () => {
    signedInAs('AUDITOR')
    renderWithProviders(<Layout />)

    expect(await screen.findByText('AUDITOR')).toBeInTheDocument()
    expect(screen.getByText('auditor@demo.local')).toBeInTheDocument()
  })

  it('gives an admin the operator controls too', async () => {
    signedInAs('ADMIN')
    renderWithProviders(<Layout />)

    expect(await screen.findByRole('link', { name: 'Post entry' })).toBeInTheDocument()
  })
})
