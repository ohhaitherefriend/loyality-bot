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
import { OrdersPage } from '@/features/orders/OrdersPage'
import { CatalogPage } from '@/features/catalog/CatalogPage'
import { MailboxesPage } from '@/features/mailboxes/MailboxesPage'
import { OperationsDashboardPage, ExceptionsQueuePage, BatchDetailPage } from '@/features/operations'
import { LandingPage } from '@/features/landing'
import { PricingPage } from '@/features/pricing/PricingPage'
import { OfferPage } from '@/features/legal/OfferPage'
import { PrivacyPage } from '@/features/legal/PrivacyPage'
import { DetailsPage } from '@/features/legal/DetailsPage'
import {
  StorefrontApp,
  StorefrontCatalogPage,
  StorefrontProductPage,
  StorefrontCartPage,
  StorefrontOrderSuccessPage,
} from '@/features/storefront'
import { useShopStore } from '@/lib/store'
import { useAuthStore } from '@/lib/auth-store'

function App() {
  const { shopId } = useShopStore()
  const { shops } = useAuthStore()
  const currentShopId = shopId || shops[0]?.shopId || null

  return (
    <>
      <Routes>
        {/* Landing — opens first */}
        <Route path="/" element={<LandingPage />} />
        <Route path="/landing" element={<LandingPage />} />

        {/* Public */}
        <Route path="/pricing" element={<PricingPage />} />
        <Route path="/offer" element={<OfferPage />} />
        <Route path="/privacy" element={<PrivacyPage />} />
        <Route path="/details" element={<DetailsPage />} />

        {/* Auth */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        
        {/* Onboarding (authenticated but no layout) */}
        <Route path="/onboarding" element={
          <AuthGuard>
            <OnboardingPage />
          </AuthGuard>
        } />
        
        {/* Protected routes with Layout */}
        <Route element={<AuthGuard><Layout /></AuthGuard>}>
          <Route path="dashboard" element={<DashboardPage />} />
          <Route path="billing" element={<BillingPage />} />
          <Route path="connect" element={<ConnectPage />} />
          <Route 
            path="settings" 
            element={currentShopId ? <SettingsPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="links" 
            element={currentShopId ? <LinksPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="status" 
            element={currentShopId ? <StatusPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="reports" 
            element={currentShopId ? <ReportsPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="orders" 
            element={currentShopId ? <OrdersPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="catalog" 
            element={currentShopId ? <CatalogPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="mailboxes" 
            element={currentShopId ? <MailboxesPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="operations" 
            element={currentShopId ? <OperationsDashboardPage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="operations/exceptions" 
            element={currentShopId ? <ExceptionsQueuePage /> : <Navigate to="/connect" replace />} 
          />
          <Route 
            path="operations/batches/:batchId" 
            element={currentShopId ? <BatchDetailPage /> : <Navigate to="/connect" replace />} 
          />
        </Route>
        
        {/* Telegram Mini App storefront */}
        <Route path="/store/:shopId" element={<StorefrontApp />}>
          <Route index element={<StorefrontCatalogPage />} />
          <Route path="products/:productId" element={<StorefrontProductPage />} />
          <Route path="cart" element={<StorefrontCartPage />} />
          <Route path="success/:orderId" element={<StorefrontOrderSuccessPage />} />
        </Route>

        {/* Catch all */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <Toaster />
    </>
  )
}

export default App

