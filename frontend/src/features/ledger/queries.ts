import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/api/client'
import type {
  AccountResponse,
  BalanceResponse,
  EntryResponse,
  PostEntryRequest,
  TransferRequest,
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
