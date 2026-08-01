/**
 * Hand-written aliases over the generated schema.
 *
 * `schema.d.ts` is regenerated from the backend's OpenAPI document and must not
 * be edited; these names give the rest of the app something stable and readable
 * to import, and localise the breakage when a response shape changes.
 */
import type { components } from './schema'

type Schemas = components['schemas']

export type MoneyResponse = Schemas['MoneyResponse']
export type AccountResponse = Schemas['AccountResponse']
export type BalanceResponse = Schemas['BalanceResponse']
export type EntryResponse = Schemas['EntryResponse']
export type EntryLineResponse = Schemas['EntryLineResponse']
export type TrialBalanceResponse = Schemas['TrialBalanceResponse']
export type TokenResponse = Schemas['TokenResponse']
export type MeResponse = Schemas['MeResponse']
export type ReconciliationReport = Schemas['ReconciliationReport']
export type BridgeRow = Schemas['BridgeRow']
export type ReconciliationBreak = Schemas['ReconciliationBreak']
export type StatementImport = Schemas['StatementImport']

export type PostEntryRequest = Schemas['PostEntryRequest']
export type TransferRequest = Schemas['TransferRequest']
export type ReversalRequest = Schemas['ReversalRequest']
export type LoginRequest = Schemas['LoginRequest']

export type AppRole = 'OPERATOR' | 'AUDITOR' | 'ADMIN'
export type Direction = 'DEBIT' | 'CREDIT'
export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE'

export type BreakType =
  | 'MISSING_IN_LEDGER'
  | 'MISSING_IN_STATEMENT'
  | 'AMOUNT_MISMATCH'
  | 'TIMING_DIFFERENCE'
  | 'DUPLICATE_IN_LEDGER'
  | 'DUPLICATE_IN_STATEMENT'
  | 'CURRENCY_MISMATCH'
  | 'OPENING_BALANCE_MISMATCH'

export type BreakStatus = 'OPEN' | 'EXPLAINED' | 'RESOLVED' | 'WRITTEN_OFF'

/** What the UI uses to hide controls. The server enforces the same rules independently. */
export const PERMISSIONS = {
  read: ['AUDITOR', 'OPERATOR', 'ADMIN'] as AppRole[],
  post: ['OPERATOR', 'ADMIN'] as AppRole[],
  reconcile: ['OPERATOR', 'ADMIN'] as AppRole[],
  administer: ['ADMIN'] as AppRole[],
}

export function can(role: AppRole | undefined, action: keyof typeof PERMISSIONS): boolean {
  return role !== undefined && PERMISSIONS[action].includes(role)
}
