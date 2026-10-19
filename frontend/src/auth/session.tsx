/**
 * 会话上下文（FE-D1 §4.2）
 *
 * 职责：持有登录态、到期前 60s 提示、到期强制清理。
 * ⛔ 无 refresh token（缺口 G13）：到期即硬跳登录，不做静默刷新。
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import {
  loadSession,
  saveSession,
  clearSession,
  isExpired,
  isExpiringSoon,
  remainingSeconds,
  type StoredSession,
} from './tokenStorage'

export interface SessionState {
  session: StoredSession | null
  authenticated: boolean
  /** 是否处于「即将过期」窗口 */
  expiringSoon: boolean
  /** 登录失效的原因（用于登录页展示提示） */
  expiredNotice: boolean
  signIn: (accessToken: string, expiresIn: number) => void
  signOut: () => void
  /** 由 API 层 401 触发 */
  onUnauthorized: () => void
}

const SessionContext = createContext<SessionState | null>(null)

export function SessionProvider({ children }: { children: ReactNode }): JSX.Element {
  const [session, setSession] = useState<StoredSession | null>(() => {
    const stored = loadSession()
    // 启动时若已过期，直接清掉，避免半登录态
    if (stored !== null && isExpired(stored)) {
      clearSession()
      return null
    }
    return stored
  })
  const [expiredNotice, setExpiredNotice] = useState(false)
  const [, setTick] = useState(0)

  // 每秒推进一次，用于「即将过期」窗口的判定
  useEffect(() => {
    const handle = setInterval(() => setTick((n) => n + 1), 1000)
    return () => clearInterval(handle)
  }, [])

  // 到期强制登出
  useEffect(() => {
    if (session === null) return
    const remaining = remainingSeconds(session)
    if (remaining <= 0) {
      clearSession()
      setSession(null)
      setExpiredNotice(true)
      return
    }
    const handle = setTimeout(() => {
      clearSession()
      setSession(null)
      setExpiredNotice(true)
    }, remaining * 1000)
    return () => clearTimeout(handle)
  }, [session])

  const signIn = useCallback((accessToken: string, expiresIn: number) => {
    const saved = saveSession(accessToken, expiresIn)
    setSession(saved)
    setExpiredNotice(false)
  }, [])

  const signOut = useCallback(() => {
    clearSession()
    setSession(null)
    setExpiredNotice(false)
  }, [])

  const onUnauthorized = useCallback(() => {
    clearSession()
    setSession(null)
    setExpiredNotice(true)
  }, [])

  const value = useMemo<SessionState>(
    () => ({
      session,
      authenticated: session !== null && !isExpired(session),
      expiringSoon: isExpiringSoon(session),
      expiredNotice,
      signIn,
      signOut,
      onUnauthorized,
    }),
    [session, expiredNotice, signIn, signOut, onUnauthorized],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionState {
  const context = useContext(SessionContext)
  if (context === null) {
    throw new Error('useSession 必须在 SessionProvider 内使用')
  }
  return context
}
