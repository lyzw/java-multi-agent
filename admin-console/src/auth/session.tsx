import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import {
  loadSession,
  storeSession,
  clearSession,
  isExpired,
  willExpireSoon,
  type StoredSession,
} from '@/auth/tokenStorage'
import { getPlatformMe } from '@/api/adminApi'

export type RolesStatus = 'unknown' | 'known'

export interface SessionState {
  session: StoredSession | null
  authenticated: boolean
  /** 角色集合（仅用于导航显隐，非安全边界） */
  roles: string[]
  rolesStatus: RolesStatus
  /** 是否处于「即将过期」窗口 */
  expiringSoon: boolean
  signIn: (session: StoredSession) => void
  signOut: () => void
  /** 由 API 层 401 触发 */
  onUnauthorized: () => void
  /** 主动回补角色（GET /api/auth/roles），幂等 */
  refreshRoles: (force?: boolean) => Promise<void>
}

const SessionContext = createContext<SessionState | null>(null)

export function SessionProvider({ children }: { children: ReactNode }): JSX.Element {
  const [session, setSession] = useState<StoredSession | null>(() => {
    const stored = loadSession()
    if (stored !== null && isExpired(stored)) {
      clearSession()
      return null
    }
    return stored
  })
  const [rolesStatus, setRolesStatus] = useState<RolesStatus>(() => {
    const stored = loadSession()
    return stored !== null && (stored.roles ?? []).length > 0 ? 'known' : 'unknown'
  })
  const [expiringSoon, setExpiringSoon] = useState(false)
  /** 已尝试回补角色的令牌；保证同一令牌只回补一次（防无限循环） */
  const backfillRef = useRef<string | null>(null)

  useEffect(() => {
    if (session === null) {
      setExpiringSoon(false)
      return
    }
    const tick = () => setExpiringSoon(willExpireSoon(session))
    tick()
    const id = window.setInterval(tick, 1000)
    return () => window.clearInterval(id)
  }, [session])

  const signIn = (s: StoredSession): void => {
    storeSession(s)
    setSession(s)
    setRolesStatus((s.roles ?? []).length > 0 ? 'known' : 'unknown')
    if ((s.roles ?? []).length === 0) void refreshRoles()
  }

  const signOut = (): void => {
    clearSession()
    setSession(null)
    setRolesStatus('unknown')
    setExpiringSoon(false)
  }

  const onUnauthorized = (): void => {
    signOut()
  }

  const refreshRoles = async (force = false): Promise<void> => {
    const cur = loadSession()
    if (cur === null) return
    if (!force && backfillRef.current === cur.accessToken) return
    backfillRef.current = cur.accessToken
    try {
      // 平台身份域：角色回补走 GET /api/platform/v1/auth/me（MeResponse.roles）
      const me = await getPlatformMe()
      const next: StoredSession = { ...cur, roles: me.roles }
      storeSession(next)
      setSession(next)
      setRolesStatus('known')
    } catch {
      /* 回补失败保持 unknown，不阻塞使用 */
    }
  }

  const roles = session?.roles ?? []
  const value: SessionState = {
    session,
    authenticated: session !== null,
    roles,
    rolesStatus,
    expiringSoon,
    signIn,
    signOut,
    onUnauthorized,
    refreshRoles,
  }
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionState {
  const ctx = useContext(SessionContext)
  if (ctx === null) throw new Error('useSession 必须在 SessionProvider 内使用')
  return ctx
}
