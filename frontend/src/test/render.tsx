import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render as rtlRender } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { ReactElement, ReactNode } from 'react'
import { AuthProvider } from '@/features/auth/AuthProvider'

/**
 * Renders a component inside the providers it actually runs under.
 *
 * Retries are off: a component test asserting on an error state should not wait
 * for two retries to elapse first, and the retry policy has its own tests.
 */
export function renderWithProviders(
  ui: ReactElement,
  { route = '/', path }: { route?: string; path?: string } = {},
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          <AuthProvider>
            {path ? <Routes><Route path={path} element={children} /></Routes> : children}
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>
    )
  }

  return { queryClient, ...rtlRender(ui, { wrapper: Wrapper }) }
}
