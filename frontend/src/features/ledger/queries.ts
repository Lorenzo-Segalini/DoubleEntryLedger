import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/api/client'
import type {
  AccountResponse,
  BalanceResponse,
  EntryResponse,
  PostEntryRequest,
  TransferRequest,
  PageResponseEntry,
  TrialBalanceResponse,
} from '@/api/types'

export const keys = {
  accounts: ['accounts'] as const,
  account: (id: string) => ['accounts', id] as const,
  balance: (id: string, asOf: string) => ['accounts', id, 'balance', asOf] as const,
  entry: (id: string) => ['entries', id] as const,
  trialBalance: (asOf: string) => ['reports', 'trial-balance', asOf] as const,
}

export function useAccounts() {
  return useQuery({
    queryKey: keys.accounts,
    queryFn: ({ signal }) => api.get<AccountResponse[]>('/api/v1/accounts', signal),
  })
}

export function useAccount(id: string) {
  return useQuery({
    queryKey: keys.account(id),
    queryFn: ({ signal }) => api.get<AccountResponse>(`/api/v1/accounts/${id}`, signal),
  })
}

export function useBalance(id: string, asOf: string) {
  return useQuery({
    queryKey: keys.balance(id, asOf),
    queryFn: ({ signal }) => api.get<BalanceResponse>(`/api/v1/accounts/${id}/balance?asOf=${asOf}`, signal),
  })
}

export function useTrialBalance(asOf: string) {
  return useQuery({
    queryKey: keys.trialBalance(asOf),
    queryFn: ({ signal }) =>
      api.get<TrialBalanceResponse>(`/api/v1/reports/trial-balance?asOf=${asOf}`, signal),
  })
}

/**
 * `| undefined` throughout: clearing a filter sets it to undefined rather than
 * deleting the key, and exactOptionalPropertyTypes treats those as different.
 */
export interface JournalFilters {
  from?: string | undefined
  to?: string | undefined
  accountId?: string | undefined
  source?: string | undefined
  externalRef?: string | undefined
}

/**
 * Browses the journal, most recent first.
 *
 * `useInfiniteQuery` rather than a page number: the server issues an opaque
 * cursor because offset pagination over an append-only ledger repeats or skips
 * rows as entries arrive mid-read. Passing that cursor straight through is the
 * whole integration — there is no page arithmetic to get wrong here.
 */
export function useJournal(filters: JournalFilters, limit = 25) {
  return useInfiniteQuery({
    queryKey: ['entries', 'list', filters, limit] as const,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({ limit: String(limit) })
      for (const [key, value] of Object.entries(filters)) {
        if (value) params.set(key, value)
      }
      if (pageParam) params.set('cursor', pageParam)
      return api.get<PageResponseEntry>(`/api/v1/journal-entries?${params}`, signal)
    },
    // Undefined stops the fetching, so an absent cursor ends the list without
    // the UI having to infer it from a short page.
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
}

export function useEntry(id: string) {
  return useQuery({
    queryKey: keys.entry(id),
    queryFn: ({ signal }) => api.get<EntryResponse>(`/api/v1/journal-entries/${id}`, signal),
  })
}

/**
 * Posting invalidates everything derived.
 *
 * Balances are computed from the journal at read time, so a new entry changes
 * every balance and every report that touched its accounts. Rather than reason
 * about which, drop the lot — the queries are cheap and being subtly stale about
 * money is not.
 */
function invalidateDerived(client: ReturnType<typeof useQueryClient>) {
  client.invalidateQueries({ queryKey: ['accounts'] })
  client.invalidateQueries({ queryKey: ['reports'] })
  client.invalidateQueries({ queryKey: ['entries'] })
  client.invalidateQueries({ queryKey: ['reconciliations'] })
}

export function usePostEntry() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: { request: PostEntryRequest; idempotencyKey: string }) =>
      api.post<EntryResponse>('/api/v1/journal-entries', request, idempotencyKey),
    onSuccess: () => invalidateDerived(client),
  })
}

export function useTransfer() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: { request: TransferRequest; idempotencyKey: string }) =>
      api.post<EntryResponse>('/api/v1/transfers', request, idempotencyKey),
    onSuccess: () => invalidateDerived(client),
  })
}

export function useReverseEntry() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({
      entryId,
      reason,
      effectiveDate,
      idempotencyKey,
    }: {
      entryId: string
      reason: string
      effectiveDate: string
      idempotencyKey: string
    }) =>
      api.post<EntryResponse>(
        `/api/v1/journal-entries/${entryId}/reversal`,
        { reason, effectiveDate },
        idempotencyKey,
      ),
    onSuccess: () => invalidateDerived(client),
  })
}
