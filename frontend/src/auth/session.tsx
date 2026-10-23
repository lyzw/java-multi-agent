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
  useRef,
  useState,
  type ReactNode,
} from 'react'
import {
  loadSession,
  saveSession,
  clearSession,
  updateSessionRoles,
  isExpired,
  EXPIRY_WARN_SECONDS,
  type StoredSession,
} from './tokenStorage'
import { getRoles } from '@/api/client'

/**
 * 角色是否**已知**。
 *
 * ⚠️ 为什么要单独一个状态位，而不是只看 `roles.length`：
 *    `roles = []` 有两种含义完全不同的成因——
 *      ① 账号**确实**没有登记任何角色（学生/家长/老师）→ 无管理菜单是**正确**的；
 *      ② 后端**没回带**角色（旧版后端 / 回补失败）→ 无管理菜单是**缺陷**，
 *         且必须让用户知道，否则管理员会以为「平台没有这些功能」。
 *    两者在 `roles` 数组上**无法区分**，故单列状态位。
 */
export type RolesStatus = 'unknown' | 'known'

export interface SessionState {
  session: StoredSession | null
  authenticated: boolean
  /** 角色集合（B1）；仅用于导航显隐，非安全边界。 */
  roles: string[]
  /**
   * 角色是否已知（B1 补强）。
   *
   * - `unknown`：尚未拿到权威角色（登录响应缺 `roles` 且回补未完成/失败）。
   *   此时**不能**断言用户是学生，也**不能**断言其无管理权限——
   *   UI 应如实说明「角色未回传」。
   * - `known`：已从登录响应或 `/api/auth/roles` 得到权威角色集合
   *   （**包括**权威结果为空数组的情形）。
   */
  rolesStatus: RolesStatus
  /** 是否处于「即将过期」窗口 */
  expiringSoon: boolean
  /** 登录失效的原因（用于登录页展示提示） */
  expiredNotice: boolean
  /**
   * 建立会话。
   *
   * @param accessToken 访问令牌
   * @param expiresIn 有效期（秒）
   * @param tenantId 权威租户 ID（供 RAG 管理面构造 X-Tenant-Id 头）
   * @param principalId 主体 ID（供审核留痕）
   * @param roles 主体角色集合（B1）；仅用于导航显隐
   */
  signIn: (
    accessToken: string,
    expiresIn: number,
    tenantId?: string | null,
    principalId?: string | null,
    roles?: string[],
  ) => void
  signOut: () => void
  /** 由 API 层 401 触发 */
  onUnauthorized: () => void
  /**
   * 主动回补角色（`GET /api/auth/roles`）。
   *
   * ⚠️ 幂等：同一令牌只回补一次，重复调用直接返回。
   *    供「会话建立但角色未知」时自愈——不必让用户重新登录。
   *
   * @param force `true` 时跳过去重（用户点「重试获取角色」）
   */
  refreshRoles: (force?: boolean) => Promise<void>
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
   * 角色是否已知（见 {@link RolesStatus}）。
   *
   * ⚠️ 初始值取自**已存会话**：存了角色即为已知；
   *    存了会话但没有角色 ⇒ 未知（老会话 / 老后端），走回补。
   */
  const [rolesStatus, setRolesStatus] = useState<RolesStatus>(() => {
    const stored = loadSession()
    return stored !== null && (stored.roles ?? []).length > 0 ? 'known' : 'unknown'
  })
  /** 已尝试回补角色的令牌；保证同一令牌只回补一次（防无限循环）。 */
  const backfillTokenRef = useRef<string | null>(null)

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

  const signIn = useCallback((
        accessToken: string,
        expiresIn: number,
        tenantId?: string | null,
        principalId?: string | null,
        roles?: string[],
      ) => {
    const saved = saveSession(accessToken, expiresIn, tenantId, principalId, roles)
    setSession(saved)
    // ⚠️ `roles === undefined` ⇒ 后端**没有回带**该字段（老版本后端），
    //    与「回带了空数组」（确实无角色）**必须区分**：
    //    前者角色未知 → 走回补；后者角色已知且为。
    setRolesStatus(roles === undefined ? 'unknown' : 'known')
    setExpiredNotice(false)
    setExpiringSoon(false)
  }, [])

  /**
   * 主动回补角色（`GET /api/auth/roles`）。
   *
   * ⚠️ **同一令牌默认只回补一次**：`backfillTokenRef` 记录已尝试过的令牌。
   *    否则「回补结果为空数组」会让 effect 反复触发 → **无限请求循环**。
   *
   * ⚠️ `force = true` 时**跳过该去重**（用户点「重试获取角色」）：
   *    首次回补可能因后端临时故障失败，重试必须真的再发一次请求。
   *
   * ⚠️ 失败时**不抛**、不改角色：角色保持未知，UI 如实说明「角色未回传」，
   *    而不是谎称用户是学生（fail-closed 但**可见**）。
   */
  const refreshRoles = useCallback(async (force: boolean = false): Promise<void> => {
    const current = session
    if (current === null) return
    if (!force && backfillTokenRef.current === current.accessToken) return
    backfillTokenRef.current = current.accessToken

    try {
      const response = await getRoles()
      const roles = Array.isArray(response.roles) ? response.roles : []
      // ⚠️ 会话可能已在请求期间过期/登出 → 不得复活它
      setSession((prev) =>
        prev === null || prev.accessToken !== current.accessToken ? prev : { ...prev, roles },
      )
      // ⚠️ 持久化**带令牌校验**：期间若会话已换成别的令牌，则不写，
      //    避免把旧令牌的角色串写到新会话上。
      updateSessionRoles(roles, current.accessToken)
      setRolesStatus('known')
    } catch {
      // 失败：角色保持未知。由 UI 如实呈现，不伪造。
    }
  }, [session])

  /**
   * 角色回补（自动触发）。
   *
   * ⚠️ 触发条件：会话存在 **且** 角色未知。
   *    登录早于 B1 的会话其 roles 恒为空，管理员会**永久**看不到管理菜单，
   *    除非重新登录——本 effect 让它**自愈**，不必让用户重登。
   */
  useEffect(() => {
    if (session === null) return
    if (rolesStatus === 'known') return
    void refreshRoles()
  }, [session, rolesStatus, refreshRoles])

  const signOut = useCallback(() => {
    clearSession()
    setSession(null)
    setRolesStatus('unknown')
    setExpiredNotice(false)
    setExpiringSoon(false)
  }, [])

  const onUnauthorized = useCallback(() => {
    clearSession()
    setSession(null)
    setRolesStatus('unknown')
    setExpiredNotice(true)
    setExpiringSoon(false)
  }, [])

  const value = useMemo<SessionState>(
    () => ({
      session,
      authenticated: session !== null && !isExpired(session),
      roles: session?.roles ?? [],
      rolesStatus,
      expiringSoon,
      expiredNotice,
      signIn,
      signOut,
      onUnauthorized,
      refreshRoles,
    }),
    [session, rolesStatus, expiringSoon, expiredNotice, signIn, signOut, onUnauthorized, refreshRoles],
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
