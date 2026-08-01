import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PostEntry } from './PostEntry'
import { renderWithProviders } from '@/test/render'
import { ACCOUNTS, http, HttpResponse, problem, server } from '@/test/server'
import { tokenStore } from '@/api/tokenStore'

describe('posting an entry', () => {
  beforeEach(() => {
    tokenStore.set({
      accessToken: 'token',
      expiresAt: Date.now() + 600_000,
      email: 'operator@demo.local',
      displayName: 'Demo Operator',
      role: 'OPERATOR',
    })
    server.use(http.get('/api/v1/accounts', () => HttpResponse.json(ACCOUNTS)))
  })

  async function fillBalancedEntry(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByLabelText('Description'), 'Card settlement')
    await user.selectOptions(screen.getByLabelText('Account for line 1'), '1000')
    await user.type(screen.getByLabelText('Amount for line 1'), '125.00')
    await user.selectOptions(screen.getByLabelText('Account for line 2'), '4000')
    await user.selectOptions(screen.getByLabelText('Direction for line 2'), 'CREDIT')
    await user.type(screen.getByLabelText('Amount for line 2'), '125.00')
  }

  it('will not let an unbalanced entry be submitted, and says by how much', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PostEntry />)
    await screen.findByLabelText('Account for line 1')

    await user.type(screen.getByLabelText('Description'), 'Unbalanced')
    await user.selectOptions(screen.getByLabelText('Account for line 1'), '1000')
    await user.type(screen.getByLabelText('Amount for line 1'), '100.00')
    await user.selectOptions(screen.getByLabelText('Account for line 2'), '4000')
    await user.selectOptions(screen.getByLabelText('Direction for line 2'), 'CREDIT')
    await user.type(screen.getByLabelText('Amount for line 2'), '90.00')

    expect(await screen.findByText('Out by €10.00')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Post entry' })).toBeDisabled()
  })

  it('computes the difference exactly, with no floating point drift', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PostEntry />)
    await screen.findByLabelText('Account for line 1')

    // 0.1 + 0.2 in floating point is 0.30000000000000004. In minor units it is
    // 10 + 20, so this is exact and the entry balances.
    await user.type(screen.getByLabelText('Description'), 'Cents')
    await user.selectOptions(screen.getByLabelText('Account for line 1'), '1000')
    await user.type(screen.getByLabelText('Amount for line 1'), '0.10')
    await user.click(screen.getByRole('button', { name: 'Add line' }))
    await user.selectOptions(screen.getByLabelText('Account for line 3'), '1000')
    await user.type(screen.getByLabelText('Amount for line 3'), '0.20')
    await user.selectOptions(screen.getByLabelText('Account for line 2'), '4000')
    await user.selectOptions(screen.getByLabelText('Direction for line 2'), 'CREDIT')
    await user.type(screen.getByLabelText('Amount for line 2'), '0.30')

    expect(await screen.findByText('Balanced')).toBeInTheDocument()
  })

  it('flags an amount that is not a number rather than treating it as zero', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PostEntry />)
    await screen.findByLabelText('Account for line 1')

    await user.selectOptions(screen.getByLabelText('Account for line 1'), '1000')
    await user.type(screen.getByLabelText('Amount for line 1'), 'abc')

    expect(await screen.findByText('An amount is not a valid number')).toBeInTheDocument()
  })

  it('sends minor units, never a decimal', async () => {
    const user = userEvent.setup()
    let body: Record<string, unknown> | null = null

    server.use(
      http.post('/api/v1/journal-entries', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ id: 'e-1', sequenceNo: 7 }, { status: 201 })
      }),
    )

    renderWithProviders(<PostEntry />)
    await screen.findByLabelText('Account for line 1')
    await fillBalancedEntry(user)
    await user.click(screen.getByRole('button', { name: 'Post entry' }))

    await waitFor(() => expect(body).not.toBeNull())
    const lines = (body as unknown as { lines: Array<{ amountMinor: number }> }).lines
    expect(lines[0]?.amountMinor).toBe(12_500)
    expect(Number.isInteger(lines[0]?.amountMinor)).toBe(true)
  })

  /**
   * The scenario idempotency exists for, reachable by double-clicking.
   *
   * The key is generated when the form opens, not when submit is pressed, so a
   * second click sends the same key and the server replays the first outcome.
   */
  it('sends the same idempotency key when submit is clicked twice', async () => {
    const user = userEvent.setup()
    const keys: (string | null)[] = []

    server.use(
      http.post('/api/v1/journal-entries', async ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        await new Promise((resolve) => setTimeout(resolve, 30))
        return HttpResponse.json({ id: 'e-1', sequenceNo: 7 }, { status: 201 })
      }),
    )

    renderWithProviders(<PostEntry />)
    await screen.findByLabelText('Account for line 1')
    await fillBalancedEntry(user)

    const submit = screen.getByRole('button', { name: 'Post entry' })
    await user.click(submit)
    await user.click(submit)

    await waitFor(() => expect(keys.length).toBeGreaterThan(0))
    expect(new Set(keys).size).toBe(1)
  })

  it('takes a fresh key after a successful post', async () => {
    const user = userEvent.setup()
    const keys: (string | null)[] = []

    server.use(
      http.post('/api/v1/journal-entries', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return HttpResponse.json({ id: 'e-1', sequenceNo: 7 }, { status: 201 })
      }),
    )

    renderWithProviders(<PostEntry />)
    await screen.findByLabelText('Account for line 1')
    await fillBalancedEntry(user)
    await user.click(screen.getByRole('button', { name: 'Post entry' }))
    await screen.findByText(/Posted as/)

    // The previous key is spent on a posting that succeeded; reusing it would
    // replay that entry instead of writing a new one.
    await fillBalancedEntry(user)
    await user.click(screen.getByRole('button', { name: 'Post entry' }))

    await waitFor(() => expect(keys.length).toBe(2))
    expect(keys[0]).not.toBe(keys[1])
  })

  it("shows the server's rejection, including the amount it is out by", async () => {
    const user = userEvent.setup()

    // The client thought it balanced; the server disagrees. That should surface
    // as the server's answer, not be swallowed because the UI was confident.
    server.use(
      http.post('/api/v1/journal-entries', () =>
        problem(422, {
          type: 'https://ledger.lseg.dev/problems/unbalanced-entry',
          title: 'Unbalanced entry',
          detail: 'entry is unbalanced by 1000 minor units',
          code: 'UNBALANCED_ENTRY',
          requestId: 'req-99',
          details: { differenceMinor: 1000 },
        }),
      ),
    )

    renderWithProviders(<PostEntry />)
    await screen.findByLabelText('Account for line 1')
    await fillBalancedEntry(user)
    await user.click(screen.getByRole('button', { name: 'Post entry' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Unbalanced entry')
    expect(alert).toHaveTextContent('differenceMinor')
    // The token a user can quote that finds the call in the server logs.
    expect(alert).toHaveTextContent('req-99')
  })
})
