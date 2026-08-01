import { beforeEach, describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Entries } from './Entries'
import { renderWithProviders } from '@/test/render'
import { ACCOUNTS, http, HttpResponse, server, tokenResponse } from '@/test/server'
import { tokenStore } from '@/api/tokenStore'

function entry(sequenceNo: number) {
  return {
    id: `e-${sequenceNo}`,
    sequenceNo,
    effectiveDate: '2026-06-15',
    description: `entry-${sequenceNo}`,
    currency: 'EUR',
    source: 'API',
    totalDebit: { amountMinor: 1000, currency: 'EUR', amount: '10.00' },
    totalCredit: { amountMinor: 1000, currency: 'EUR', amount: '10.00' },
    lines: [],
  }
}

describe('browsing the journal', () => {
  beforeEach(() => {
    tokenStore.set({
      accessToken: 'token',
      expiresAt: Date.now() + 600_000,
      email: 'operator@demo.local',
      displayName: 'Demo Operator',
      role: 'OPERATOR',
    })
    server.use(
      http.post('/api/v1/auth/refresh', () => HttpResponse.json(tokenResponse('OPERATOR', 'token'))),
      http.get('/api/v1/accounts', () => HttpResponse.json(ACCOUNTS)),
    )
  })

  it('follows the cursor the server issued rather than counting pages', async () => {
    const user = userEvent.setup()
    const cursorsSeen: (string | null)[] = []

    server.use(
      http.get('/api/v1/journal-entries', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        cursorsSeen.push(cursor)

        if (cursor === null) {
          return HttpResponse.json({ items: [entry(3), entry(2)], nextCursor: 'cursor-abc', hasMore: true })
        }
        return HttpResponse.json({ items: [entry(1)], nextCursor: null, hasMore: false })
      }),
    )

    renderWithProviders(<Entries />)
    await screen.findByText('entry-3')

    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await screen.findByText('entry-1')

    // The opaque cursor is passed straight back. There is no page arithmetic in
    // the client to get wrong, which is the point of the server issuing one.
    expect(cursorsSeen).toEqual([null, 'cursor-abc'])
    expect(screen.getByText('3 shown')).toBeInTheDocument()
  })

  it('says the journal has ended rather than offering a dead button', async () => {
    server.use(
      http.get('/api/v1/journal-entries', () =>
        HttpResponse.json({ items: [entry(1)], nextCursor: null, hasMore: false }),
      ),
    )

    renderWithProviders(<Entries />)
    await screen.findByText('entry-1')

    expect(screen.getByText('End of the journal.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('offers more even when the page came back exactly full', async () => {
    // A full page can also be the last one, so "more" must come from the
    // server's cursor and not from counting rows.
    server.use(
      http.get('/api/v1/journal-entries', () =>
        HttpResponse.json({ items: [entry(2), entry(1)], nextCursor: 'cursor-abc', hasMore: true }),
      ),
    )

    renderWithProviders(<Entries />)
    await screen.findByText('entry-2')

    expect(screen.getByRole('button', { name: 'Load more' })).toBeInTheDocument()
  })

  it('sends the filters the user chose', async () => {
    const user = userEvent.setup()
    const queries: string[] = []

    server.use(
      http.get('/api/v1/journal-entries', ({ request }) => {
        queries.push(new URL(request.url).search)
        return HttpResponse.json({ items: [], nextCursor: null, hasMore: false })
      }),
    )

    renderWithProviders(<Entries />)
    await screen.findByLabelText('Source')

    await user.selectOptions(screen.getByLabelText('Source'), 'REVERSAL')

    await screen.findByText('No entries match these filters.')
    expect(queries.at(-1)).toContain('source=REVERSAL')
  })

  it('marks a reversal so it is not mistaken for an ordinary entry', async () => {
    server.use(
      http.get('/api/v1/journal-entries', () =>
        HttpResponse.json({
          items: [{ ...entry(2), source: 'REVERSAL', reversalOfEntryId: 'e-1' }, entry(1)],
          nextCursor: null,
          hasMore: false,
        }),
      ),
    )

    renderWithProviders(<Entries />)

    const reversalRow = (await screen.findByText('entry-2')).closest('tr')
    expect(reversalRow).not.toBeNull()
    expect(within(reversalRow as HTMLElement).getByText('Reversal')).toBeInTheDocument()
  })
})
