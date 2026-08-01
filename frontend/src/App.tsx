import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { queryClient } from '@/app/queryClient'
import { Layout } from '@/app/Layout'
import { RequireAuth } from '@/app/RequireAuth'
import { AuthProvider } from '@/features/auth/AuthProvider'
import { Login } from '@/routes/Login'
import { Dashboard } from '@/routes/Dashboard'
import { Accounts } from '@/routes/accounts/Accounts'
import { AccountMovements } from '@/routes/accounts/AccountMovements'
import { Entries } from '@/routes/entries/Entries'
import { EntryDetail } from '@/routes/entries/EntryDetail'
import { PostEntry } from '@/routes/entries/PostEntry'
import { Reconciliations } from '@/routes/reconciliation/Reconciliations'
import { ReconciliationReportView } from '@/routes/reconciliation/ReconciliationReport'

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route
              element={
                <RequireAuth>
                  <Layout />
                </RequireAuth>
              }
            >
              <Route index element={<Dashboard />} />
              <Route path="accounts" element={<Accounts />} />
              <Route path="accounts/:id" element={<AccountMovements />} />
              <Route path="entries" element={<Entries />} />
              <Route
                path="entries/new"
                element={
                  <RequireAuth requires="post">
                    <PostEntry />
                  </RequireAuth>
                }
              />
              <Route path="entries/:id" element={<EntryDetail />} />
              <Route path="reconciliation" element={<Reconciliations />} />
              <Route path="reconciliation/:id" element={<ReconciliationReportView />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
