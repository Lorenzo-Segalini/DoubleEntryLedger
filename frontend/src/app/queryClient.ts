import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/lib/problem'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (failureCount, error) => {
        // Retrying a 401, 403 or 422 cannot help: the token is wrong, the role
        // is wrong, or the request is. Only transient failures are worth a
        // second attempt.
        if (error instanceof ApiError && error.status < 500) return false
        return failureCount < 2
      },
    },
    mutations: {
      // Never automatically. A write that failed may or may not have been
      // applied, and deciding that is the idempotency layer's job, invoked
      // deliberately with the same key — not a blind retry here.
      retry: false,
    },
  },
})
