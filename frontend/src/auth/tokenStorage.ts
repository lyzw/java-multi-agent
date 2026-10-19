/**
 * 令牌存储与过期计算（FE-D1 §4）
 *
 * ⛔ 决策：存 sessionStorage，**不用 localStorage**
 *    理由：降低 XSS 场景下令牌存活时间（FE-D1 §4.1）。
 * ⛔ 无 refresh token（缺口 G13）：到期即硬跳登录，不做静默刷新。
 */

const STORAGE_KEY = 'agent.session'
/** 到期前多久开始提示（秒） */
export const EXPIRY_WARN_SECONDS = 60

export interface StoredSession {
  accessToken: string
  /** 绝对过期时刻（epoch ms） */
  expiresAt: number
}

function isBrowser(): boolean {
  return typeof window !== 'undefined' && typeof window.sessionStorage !== 'undefined'
}

export function loadSession(): StoredSession | null {
  if (!isBrowser()) return null
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY)
    if (raw === null) return null
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return null
    const record = parsed as Record<string, unknown>
    if (typeof record['accessToken'] !== 'string') return null
    if (typeof record['expiresAt'] !== 'number') return null
    return { accessToken: record['accessToken'], expiresAt: record['expiresAt'] }
  } catch {
    // JSON 损坏：视为无会话，不抛错打断应用
    return null
  }
}

export function saveSession(accessToken: string, expiresInSeconds: number): StoredSession {
  // ⚠️ 用后端返回的 expiresIn 计算，不读前端配置。
  //    后端该值硬编码 900（不读 auth.jwt.token-ttl-seconds）。
  const session: StoredSession = {
    accessToken,
    expiresAt: Date.now() + expiresInSeconds * 1000,
  }
  if (isBrowser()) {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
  }
  return session
}

export function clearSession(): void {
  if (!isBrowser()) return
  window.sessionStorage.removeItem(STORAGE_KEY)
}

/** 剩余有效秒数；<= 0 表示已过期 */
export function remainingSeconds(
  session: StoredSession | null,
  now: number = Date.now(),
): number {
  if (session === null) return 0
  return Math.floor((session.expiresAt - now) / 1000)
}

export function isExpired(
  session: StoredSession | null,
  now: number = Date.now(),
): boolean {
  return remainingSeconds(session, now) <= 0
}

/** 是否处于「即将过期」窗口 */
export function isExpiringSoon(
  session: StoredSession | null,
  now: number = Date.now(),
): boolean {
  const remaining = remainingSeconds(session, now)
  return remaining > 0 && remaining <= EXPIRY_WARN_SECONDS
}
