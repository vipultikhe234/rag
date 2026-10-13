import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { FullPageSpinner } from '../components/ui/Spinner'
import { useAuth } from './authContext'

interface ProtectedRouteProps {
  children: ReactNode
  requireAdmin?: boolean
}

export function ProtectedRoute({ children, requireAdmin = false }: ProtectedRouteProps) {
  const { user, isAdmin, initializing } = useAuth()
  const location = useLocation()

  if (initializing) {
    return <FullPageSpinner label="Checking your session" />
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  if (requireAdmin && !isAdmin) {
    return <Navigate to="/products" replace state={{ forbidden: true }} />
  }
  return <>{children}</>
}
