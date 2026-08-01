import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './server'
import { tokenStore } from '@/api/tokenStore'

// `error` rather than `warn`: an unhandled request means a test is exercising a
// call nobody declared, and letting that pass silently is how a component test
// ends up asserting against a network failure it never noticed.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))

afterEach(() => {
  server.resetHandlers()
  // The token store is module state, so it survives between tests unless
  // cleared — exactly the shared-state trap the backend suite was bitten by.
  tokenStore.clear()
})

afterAll(() => server.close())
