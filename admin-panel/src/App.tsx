import { Routes, Route, Navigate } from 'react-router-dom'
import { Toaster } from '@/components/ui/toaster'
import { Layout } from '@/components/Layout'
import { AuthGuard } from '@/components/AuthGuard'
import { LoginPage, RegisterPage } from '@/features/auth'
import { OnboardingPage } from '@/features/onboarding'
import { DashboardPage } from '@/features/dashboard'
import { BillingPage } from '@/features/billing'
import { ConnectPage } from '@/features/connect/ConnectPage'
import { SettingsPage } from '@/features/settings/SettingsPage'
import { LinksPage } from '@/features/links/LinksPage'
import { StatusPage } from '@/features/status/StatusPage'
import { ReportsPage } from '@/features/reports/ReportsPage'
import { useShopStore } from '@/lib/store'

function App() {
  const { shopId } = useShopStore()

  return (
    <>
      <Routes>
        {/* Public routes */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        
        {/* Onboarding (authenticated but no layout) */}
        <Route path="/onboarding" element={
          <AuthGuard>
            <OnboardingPage />
          </AuthGuard>
        } />
        
        {/* Protected routes with Layout */}
        <Route path="/" element={
          <AuthGuard>
            <Layout />
          </AuthGuard>
        }>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="dashboard" element={<DashboardPage />} />
          <Route path="billing" element={<BillingPage />} />
          <Route path="connect" element={<ConnectPage />} />
          <Route 
            path="settings" 
            element={shopId ? <SettingsPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="links" 
            element={shopId ? <LinksPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="status" 
            element={shopId ? <StatusPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="reports" 
            element={shopId ? <ReportsPage /> : <Navigate to="/connect" replace />} 
          />
        </Route>
        
        {/* Catch all - redirect to dashboard */}
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
      <Toaster />
    </>
  )
}

export default App

