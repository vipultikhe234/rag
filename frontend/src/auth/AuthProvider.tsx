import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { UNAUTHORIZED_EVENT } from '../api/client'
import { authApi } from '../api/endpoints'
import { clearSession, loadSession, saveSession } from '../api/tokenStorage'
import type { User } from '../api/types'
import { AuthContext, type AuthContextValue } from './authContext'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => loadSession()?.user ?? null)
  const [initializing, setInitializing] = useState<boolean>(() => loadSession() !== null)

  // Re-validate a stored session with the backend (the account may have been changed or the secret rotated).
  useEffect(() => {
    const session = loadSession()
    if (!session) {
      return
    }
    let cancelled = false
    authApi
      .me()
      .then((current) => {
        if (!cancelled) {
          saveSession({ ...session, user: current })
          setUser(current)
        }
      })
      .catch(() => {
        if (!cancelled) {
          clearSession()
          setUser(null)
        }
      })
      .finally(() => {
        if (!cancelled) setInitializing(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    const onUnauthorized = () => setUser(null)
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const response = await authApi.login(email, password)
    saveSession({ token: response.accessToken, user: response.user, expiresAt: response.expiresAt })
    setUser(response.user)
    return response.user
  }, [])

  const logout = useCallback(() => {
    clearSession()
    setUser(null)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ user, isAdmin: user?.role === 'ADMIN', initializing, login, logout }),
    [user, initializing, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
