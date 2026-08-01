import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // E2E runs against the real backend and a real PostgreSQL — never mocks.
  // `pnpm up` brings the Compose stack online first.
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : { command: 'pnpm dev', url: 'http://localhost:5173', reuseExistingServer: !process.env.CI },
})
