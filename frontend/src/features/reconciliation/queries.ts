import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/api/client'
import type { ReconciliationReport, StatementImport } from '@/api/types'

export const reconciliationKeys = {
  all: ['reconciliations'] as const,
  report: (id: string) => ['reconciliations', id, 'report'] as const,
}

export function useReconciliations() {
  return useQuery({
    queryKey: reconciliationKeys.all,
    queryFn: ({ signal }) => api.get<StatementImport[]>('/api/v1/reconciliations', signal),
  })
}

export function useReconciliationReport(id: string) {
  return useQuery({
    queryKey: reconciliationKeys.report(id),
    queryFn: ({ signal }) => api.get<ReconciliationReport>(`/api/v1/reconciliations/${id}/report`, signal),
  })
}

export interface ImportInput {
  file: File
  accountCode: string
  periodStart: string
  periodEnd: string
  openingBalanceMinor: number
  closingBalanceMinor: number
}

export function useImportStatement() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: ImportInput) => {
      const form = new FormData()
      form.append('file', input.file)
      form.append('accountCode', input.accountCode)
      form.append('periodStart', input.periodStart)
      form.append('periodEnd', input.periodEnd)
      form.append('openingBalanceMinor', String(input.openingBalanceMinor))
      form.append('closingBalanceMinor', String(input.closingBalanceMinor))
      // No idempotency key: the file's SHA-256 is a better natural key than
      // anything the client could invent, so re-uploading returns the same run.
      return api.upload<ReconciliationReport>('/api/v1/reconciliations', form)
    },
    onSuccess: () => client.invalidateQueries({ queryKey: reconciliationKeys.all }),
  })
}

export function useExplainBreak(importId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ breakId, explanation }: { breakId: string; explanation: string }) =>
      api.post<void>(`/api/v1/reconciliations/${importId}/breaks/${breakId}/explain`, { explanation }),
    onSuccess: () => client.invalidateQueries({ queryKey: reconciliationKeys.report(importId) }),
  })
}

export function useResolveBreak(importId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({
      breakId,
      counterAccountCode,
      explanation,
      idempotencyKey,
    }: {
      breakId: string
      counterAccountCode: string
      explanation: string
      idempotencyKey: string
    }) =>
      api.post<{ breakId: string; adjustingEntryId: string }>(
        `/api/v1/reconciliations/${importId}/breaks/${breakId}/resolve`,
        { counterAccountCode, explanation },
        idempotencyKey,
      ),
    onSuccess: () => {
      // Resolving posts a real entry, so balances and reports move with it.
      client.invalidateQueries({ queryKey: reconciliationKeys.report(importId) })
      client.invalidateQueries({ queryKey: ['accounts'] })
      client.invalidateQueries({ queryKey: ['reports'] })
    },
  })
}
