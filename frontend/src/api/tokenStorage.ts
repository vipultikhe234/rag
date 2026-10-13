import type { User } from './types'

// sessionStorage limits the token's lifetime to the browser tab; the backend also enforces expiry.
const TOKEN_KEY = 'product-rag.token'
const USER_KEY = 'product-rag.user'
const EXPIRES_KEY = 'product-rag.expiresAt'

export interface StoredSession {
  token: string
  user: User
  expiresAt: string
}

export function saveSession(session: StoredSession): void {
  sessionStorage.setItem(TOKEN_KEY, session.token)
  sessionStorage.setItem(USER_KEY, JSON.stringify(session.user))
  sessionStorage.setItem(EXPIRES_KEY, session.expiresAt)
}

export function loadSession(): StoredSession | null {
  const token = sessionStorage.getItem(TOKEN_KEY)
  const user = sessionStorage.getItem(USER_KEY)
  const expiresAt = sessionStorage.getItem(EXPIRES_KEY)
  if (!token || !user || !expiresAt) {
    return null
  }
  if (Date.parse(expiresAt) <= Date.now()) {
    clearSession()
    return null
  }
  try {
    return { token, user: JSON.parse(user) as User, expiresAt }
  } catch {
    clearSession()
    return null
  }
}

export function getToken(): string | null {
  return loadSession()?.token ?? null
}

export function clearSession(): void {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(USER_KEY)
  sessionStorage.removeItem(EXPIRES_KEY)
}
