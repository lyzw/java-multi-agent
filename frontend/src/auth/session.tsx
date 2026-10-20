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
  EXPIRY_WARN_SECONDS,
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
  const [expiringSoon, setExpiringSoon] = useState(false)

  /**
   * 会话生命周期：预警定时器 + 到期定时器，一一对应。
   *
   * ⚠️ 这里曾经是「每秒 setInterval 推 tick」+「到期 setTimeout」两套。
   *    tick 方案的真实形态是**失效**的：`value` 的 useMemo 依赖数组里没有 tick，
   *    每秒重渲染全部命中 memo 缓存 → `expiringSoon` 永远等于登录瞬间的值（false），
   *    且当时 `expiringSoon` 无任何 UI 消费 ——
   *    等于 FE-D1 §4.2 承诺的「到期前 60s 提示」从未真正存在，而所有测试全绿。
   *
   * ✅ 改为**定时器对齐到具体时刻**，共两个，不轮询：
   *    · 到 `expiresAt - 60s` → 置 expiringSoon = true（预警）
   *    · 到 `expiresAt`      → 清会话 + 置 expiredNotice（硬跳登录）
   *    两者都从同一个 session 派生， session 变更即整体重建，无残留。
   */
  useEffect(() => {
    if (session === null) {
      setExpiringSoon(false)
      return
    }

    const now = Date.now()
    const warnAt = session.expiresAt - EXPIRY_WARN_SECONDS * 1000
    const warnDelay = warnAt - now
    const expireDelay = session.expiresAt - now

    // 已进入预警窗口（含剩余不足 60s 的负值）→ 立即为 true，不排定时器
    const warnHandle =
      warnDelay <= 0
        ? (setExpiringSoon(true), null)
        : setTimeout(() => setExpiringSoon(true), warnDelay)

    // 已过期 → 立即清理（兜底，正常路径由 useState 初始化时处理）
    const expire = (): void => {
      clearSession()
      setSession(null)
      setExpiredNotice(true)
      setExpiringSoon(false)
    }
    const expireHandle =
      expireDelay <= 0 ? null : setTimeout(expire, expireDelay)
    if (expireDelay <= 0) expire()

    return () => {
      if (warnHandle !== null) clearTimeout(warnHandle)
      if (expireHandle !== null) clearTimeout(expireHandle)
    }
  }, [session])

  const signIn = useCallback((accessToken: string, expiresIn: number) => {
    const saved = saveSession(accessToken, expiresIn)
    setSession(saved)
    setExpiredNotice(false)
    setExpiringSoon(false)
  }, [])

  const signOut = useCallback(() => {
    clearSession()
    setSession(null)
    setExpiredNotice(false)
    setExpiringSoon(false)
  }, [])

  const onUnauthorized = useCallback(() => {
    clearSession()
    setSession(null)
    setExpiredNotice(true)
    setExpiringSoon(false)
  }, [])

  const value = useMemo<SessionState>(
    () => ({
      session,
      authenticated: session !== null && !isExpired(session),
      expiringSoon,
      expiredNotice,
      signIn,
      signOut,
      onUnauthorized,
    }),
    [session, expiringSoon, expiredNotice, signIn, signOut, onUnauthorized],
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
