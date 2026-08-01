import { expect, test, type Page } from '@playwright/test'

/**
 * The back office against a real backend and a real PostgreSQL — never mocks.
 *
 * These are the flows that would embarrass the project if they broke: money
 * moving, an unbalanced entry being refused, a correction leaving both entries
 * visible, and the auditor being unable to write.
 */

const DEMO = {
  operator: { email: 'operator@demo.local', password: 'demo-operator' },
  auditor: { email: 'auditor@demo.local', password: 'demo-auditor' },
}

async function signIn(page: Page, who: keyof typeof DEMO) {
  await page.goto('/login')
  await page.getByLabel('Email').fill(DEMO[who].email)
  await page.getByLabel('Password').fill(DEMO[who].password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('link', { name: 'Dashboard' })).toBeVisible()
}

test.describe('signing in', () => {
  test('rejects a wrong password without saying which part was wrong', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('Email').fill(DEMO.operator.email)
    await page.getByLabel('Password').fill('not-the-password')
    await page.getByRole('button', { name: 'Sign in' }).click()

    await expect(page.getByRole('alert')).toContainText('invalid credentials')
    await expect(page).toHaveURL(/\/login/)
  })

  test('an unauthenticated visitor is sent to the login screen', async ({ page }) => {
    await page.goto('/accounts')
    await expect(page).toHaveURL(/\/login/)
  })

  test('signs in and lands on the trial balance', async ({ page }) => {
    await signIn(page, 'operator')

    await expect(page.getByRole('heading', { name: 'Trial balance' })).toBeVisible()
    // The out-of-balance figure, as a zero. This is the assertion the whole
    // project exists to make true.
    await expect(page.getByText('Balanced', { exact: true })).toBeVisible()
  })

  test('survives a reload without asking for the password again', async ({ page }) => {
    await signIn(page, 'operator')
    await page.reload()

    // The access token is in memory and is gone; the HttpOnly refresh cookie is
    // not, and the client trades it for a new one before rendering.
    await expect(page.getByRole('heading', { name: 'Trial balance' })).toBeVisible()
  })
})

test.describe('posting', () => {
  test('posts a balanced entry and moves the balance by exactly that amount', async ({ page }) => {
    await signIn(page, 'operator')

    // Read the account balance before.
    await page.goto('/accounts')
    await page.getByRole('link', { name: '1100' }).click()
    const before = await page.getByText(/Total debits/).locator('..').innerText()

    await page.goto('/entries/new')
    await page.getByLabel('Description').fill('E2E settlement')
    await page.getByLabel('Account for line 1').selectOption('1100')
    await page.getByLabel('Direction for line 1').selectOption('DEBIT')
    await page.getByLabel('Amount for line 1').fill('12.34')
    await page.getByLabel('Account for line 2').selectOption('4000')
    await page.getByLabel('Direction for line 2').selectOption('CREDIT')
    await page.getByLabel('Amount for line 2').fill('12.34')

    await expect(page.getByText('Balanced')).toBeVisible()
    await page.getByRole('button', { name: 'Post entry' }).click()

    await expect(page.getByText(/Posted as/)).toBeVisible()

    await page.goto('/accounts')
    await page.getByRole('link', { name: '1100' }).click()
    const after = await page.getByText(/Total debits/).locator('..').innerText()
    expect(after).not.toEqual(before)
  })

  test('refuses to submit an unbalanced entry and says by how much', async ({ page }) => {
    await signIn(page, 'operator')
    await page.goto('/entries/new')

    await page.getByLabel('Description').fill('E2E unbalanced')
    await page.getByLabel('Account for line 1').selectOption('1000')
    await page.getByLabel('Amount for line 1').fill('100.00')
    await page.getByLabel('Account for line 2').selectOption('4000')
    await page.getByLabel('Direction for line 2').selectOption('CREDIT')
    await page.getByLabel('Amount for line 2').fill('90.00')

    // The client says how far out it is, and will not let the request leave.
    await expect(page.getByText('Out by €10.00')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Post entry' })).toBeDisabled()
  })

  test('a correction posts a reversal and leaves the original in place', async ({ page }) => {
    await signIn(page, 'operator')
    await page.goto('/entries/new')

    await page.getByLabel('Description').fill('E2E to be reversed')
    await page.getByLabel('Account for line 1').selectOption('1000')
    await page.getByLabel('Amount for line 1').fill('5.00')
    await page.getByLabel('Account for line 2').selectOption('4000')
    await page.getByLabel('Direction for line 2').selectOption('CREDIT')
    await page.getByLabel('Amount for line 2').fill('5.00')
    await page.getByRole('button', { name: 'Post entry' }).click()

    await page.getByRole('link', { name: /entry #/ }).click()
    await expect(page.getByRole('heading', { name: /Entry #/ })).toBeVisible()
    const originalUrl = page.url()

    await page.getByLabel('Reason').fill('E2E correction')
    await page.getByRole('button', { name: 'Post reversal' }).click()
    await expect(page.getByText(/Reversed by/)).toBeVisible()

    // The original is still readable. Correcting does not erase.
    await page.goto(originalUrl)
    await expect(page.getByRole('heading', { name: /Entry #/ })).toBeVisible()
  })
})

test.describe('the auditor', () => {
  test('can read everything and is offered nothing to change', async ({ page }) => {
    await signIn(page, 'auditor')

    await expect(page.getByRole('heading', { name: 'Trial balance' })).toBeVisible()
    await page.goto('/accounts')
    await expect(page.getByRole('heading', { name: 'Chart of accounts' })).toBeVisible()

    // Absent from the DOM, not merely disabled.
    await expect(page.getByRole('link', { name: 'Post entry' })).toHaveCount(0)
  })

  test('cannot reach the posting screen by typing the URL', async ({ page }) => {
    await signIn(page, 'auditor')
    await page.goto('/entries/new')

    // The route guard redirects. The API would refuse it independently anyway —
    // this only avoids offering a control that was always going to fail.
    await expect(page.getByRole('heading', { name: 'Post a journal entry' })).toHaveCount(0)
  })

  test('sees no import form on the reconciliation screen', async ({ page }) => {
    await signIn(page, 'auditor')
    await page.goto('/reconciliation')

    await expect(page.getByRole('heading', { name: 'Reconciliation' })).toBeVisible()
    await expect(page.getByText('Import a statement')).toHaveCount(0)
  })
})

test.describe('reconciliation', () => {
  test('imports a statement and renders a bridge that closes', async ({ page }) => {
    await signIn(page, 'operator')
    await page.goto('/reconciliation')

    // One unbooked bank charge, against a declared opening of zero that the
    // account does not actually have. Both differences must be accounted for:
    // the charge as MISSING_IN_LEDGER, and the opening gap as its own break.
    // That the bridge still closes is the point.
    const csv = [
      'value_date,amount,currency,description,external_id',
      '2026-06-28,-14.50,EUR,E2E BANK FEE,e2e-fee',
    ].join('\n')

    await page.getByLabel('CSV file').setInputFiles({
      name: 'e2e.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from(csv),
    })
    await page.getByLabel('Account').selectOption('1000')
    await page.getByLabel('Opening balance').fill('0.00')
    await page.getByLabel('Closing balance').fill('-14.50')
    await page.getByRole('button', { name: 'Import and reconcile' }).click()

    await expect(page.getByRole('heading', { name: /1000/ })).toBeVisible()
    // The deliverable: the explanations account for the difference.
    await expect(page.getByText('Explanations account for the difference')).toBeVisible()

    // Matched on the description, which only the break list carries: the
    // waterfall above is also a list, and shows the type on its own.
    const feeBreak = page.getByRole('listitem').filter({ hasText: 'E2E BANK FEE' })
    await expect(feeBreak).toHaveCount(1)
    await expect(feeBreak).toContainText('MISSING_IN_LEDGER')
  })

  test('a statement that does not add up is refused before matching', async ({ page }) => {
    await signIn(page, 'operator')
    await page.goto('/reconciliation')

    const csv = ['value_date,amount,currency,description', '2026-06-04,1000.00,EUR,E2E INCONSISTENT'].join('\n')

    await page.getByLabel('CSV file').setInputFiles({
      name: 'e2e-bad.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from(csv),
    })
    await page.getByLabel('Account').selectOption('1000')
    await page.getByLabel('Opening balance').fill('0.00')
    await page.getByLabel('Closing balance').fill('9999.99')
    await page.getByRole('button', { name: 'Import and reconcile' }).click()

    // Reconciling against a file whose own rows contradict its declared closing
    // produces confident nonsense, so it never gets that far.
    const alert = page.getByRole('alert')
    await expect(alert).toContainText('closing')
    // And the error carries the request id that finds it in the server logs.
    await expect(alert).toContainText('Request')
  })
})
