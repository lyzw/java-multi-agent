/**
 * 令牌存储与过期计算（复用现有前端契约）
 *
 * ⛔ 决策：存 sessionStorage，**不用 localStorage**——降低 XSS 场景下令牌存活时间。
 * ⛔ 无 refresh token：到期即硬跳登录，不做静默刷新。
 */

import { STORAGE_KEY } from '@/config/constants'

/** 到期前多久开始提示（秒） */
export const EXPIRY_WARN_SECONDS = 60

export interface StoredSession {
  accessToken: string
  /** 绝对过期时刻（epoch ms） */
  expiresAt: number
  /** 权威租户 ID */
  tenantId?: string | null
  /** 主体 ID（审核留痕） */
  principalId?: string | null
  /**
   * 角色集合：仅用于导航显隐，非安全边界。
   * 旧会话（升级前写入）无此字段时回落为空数组。
   */
  roles?: string[]
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
    const tenantId = typeof record['tenantId'] === 'string' ? record['tenantId'] : null
    const principalId = typeof record['principalId'] === 'string' ? record['principalId'] : null
    const roles = Array.isArray(record['roles'])
      ? (record['roles'] as unknown[]).filter((r): r is string => typeof r === 'string')
      : []
    return {
      accessToken: record['accessToken'],
      expiresAt: record['expiresAt'],
      tenantId,
      principalId,
      roles,
    }
  } catch {
    return null
  }
}

export function storeSession(session: StoredSession): void {
  if (!isBrowser()) return
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
  } catch {
    /* 忽略配额 / 隐私模式异常 */
  }
}

export function clearSession(): void {
  if (!isBrowser()) return
  try {
    window.sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    /* 忽略 */
  }
}

export function isExpired(session: StoredSession, now: number = Date.now()): boolean {
  return now >= session.expiresAt
}

export function willExpireSoon(session: StoredSession, now: number = Date.now()): boolean {
  return session.expiresAt - now <= EXPIRY_WARN_SECONDS * 1000
}
