import { useEffect, useState } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { Loader2 } from 'lucide-react'

import { useAuthStore } from '@/lib/auth-store'

interface AuthGuardProps {
  children: React.ReactNode
}

export function AuthGuard({ children }: AuthGuardProps) {
  const location = useLocation()
  const { isAuthenticated, isLoading, checkAuth } = useAuthStore()
  const [checking, setChecking] = useState(true)

  useEffect(() => {
    const verify = async () => {
      await checkAuth()
      setChecking(false)
    }
    verify()
  }, [checkAuth])

  // Show loading spinner while checking auth
  if (checking || isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-primary" />
      </div>
    )
  }

  // Redirect to login if not authenticated
  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />
  }

  return <>{children}</>
}
