/**
 * RAG 租户标识的本地记忆（**唯一读写方**）。
 *
 * ⚠️ 修复背景（E2E 二轮优化，2026-11-14）：
 *    `RagSearchPage` 与 `RagManagementPage` 曾共用同一个 localStorage key
 *    `rag.tenantId`，却各写各的格式：检索页写 `{value,timestamp}` JSON（7 天 TTL），
 *    管理页写裸 UUID 字符串且裸读。后果是跨页串写——
 *      · 管理页写完后，检索页 `JSON.parse` 抛错被 catch 吞掉 → 租户标识变空；
 *      · 检索页写完后，管理页把 `{"value":...}` 当成 tenantId 发给后端。
 *    两处「各自看着都对」的实现，合起来就是一个跨页缺陷。
 *
 * ⛔ 纪律：两页必须都用本模块；不得再直接 `localStorage.getItem(KEY)`。
 *    TTL 只管「本地记忆」，登录会话回带的 `session.tenantId` 仍是权威值，
 *    由调用方优先取用（本模块只负责「上次手填值」的读写入口）。
 */

export const RAG_TENANT_STORAGE_KEY = 'rag.tenantId'
export const RAG_TENANT_STORAGE_TTL_MS = 7 * 24 * 60 * 60 * 1000 // 7 天

interface StoredTenant {
  value: string
  timestamp: number
}

function isStoredTenant(value: unknown): value is StoredTenant {
  if (typeof value !== 'object' || value === null) return false
  const record = value as Record<string, unknown>
  return typeof record['value'] === 'string' && typeof record['timestamp'] === 'number'
}

/**
 * 读取上次手填的租户标识。
 *
 * ⚠️ 兼容历史裸字符串值（管理页旧实现留下的）：直接当作合法值返回，
 *    下次写入时会被规范化成 `{value,timestamp}`，不需要数据迁移。
 */
export function readStoredTenantId(): string {
  try {
    const stored = window.localStorage.getItem(RAG_TENANT_STORAGE_KEY)
    if (stored === null) return ''
    const trimmed = stored.trim()
    if (trimmed === '') return ''
    // 历史裸 UUID：不是 JSON，按租户标识直接返回。
    if (!trimmed.startsWith('{')) return trimmed
    const parsed: unknown = JSON.parse(trimmed)
    if (!isStoredTenant(parsed)) return ''
    if (Date.now() - parsed.timestamp > RAG_TENANT_STORAGE_TTL_MS) {
      window.localStorage.removeItem(RAG_TENANT_STORAGE_KEY)
      return ''
    }
    return parsed.value
  } catch {
    // ⚠️ 隐私模式 / 禁用存储 / 脏数据时退回空串，让用户手填。
    return ''
  }
}

/** 记住本次使用的租户标识；空串表示清除记忆。 */
export function rememberTenantId(tenantId: string): void {
  try {
    if (tenantId === '') {
      window.localStorage.removeItem(RAG_TENANT_STORAGE_KEY)
      return
    }
    const payload: StoredTenant = { value: tenantId, timestamp: Date.now() }
    window.localStorage.setItem(RAG_TENANT_STORAGE_KEY, JSON.stringify(payload))
  } catch {
    // 记忆失败不影响检索 / 管理操作本身，静默忽略。
  }
}
