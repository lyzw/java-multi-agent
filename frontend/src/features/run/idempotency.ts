/**
 * 幂等键策略（FE-D1 §5）—— ⚠️ Sev-0 相关
 *
 * ⛔ 为什么这是 Sev-0：
 * 后端 record_weakness 的幂等键是 student_id + run_id + knowledge_point。
 * 若前端每次重试都换新的 idempotencyKey → 重复创建 Run →
 * 不同 runId 被视为不同操作 → **重复写入薄弱点**（P0-D00 §3.4 列为失败判定）。
 *
 * 策略：
 *   · 首次提交          → 生成新 UUID，存 sessionStorage
 *   · 网络失败/5xx 重试 → **复用同一 key**
 *   · 表单内容变更      → **换新 key**（避免 409 冲突）
 *   · 创建成功          → 清除 key
 */

const STORAGE_KEY = 'run.idempotency'

/** 浏览器 crypto.randomUUID 在非安全上下文（http）下不可用，需兜底 */
export function generateIdempotencyKey(): string {
  const cryptoRef = globalThis.crypto
  if (cryptoRef !== undefined && typeof cryptoRef.randomUUID === 'function') {
    return cryptoRef.randomUUID()
  }
  // 兜底：模板字符串 + 随机段。注意这**不是**密码学用途，仅作幂等键
  const random = Math.random().toString(16).slice(2, 10)
  const stamp = Date.now().toString(16)
  return `idem-${stamp}-${random}-${Math.random().toString(16).slice(2, 10)}`
}

/** 请求指纹：内容不变则指纹不变 */
export function fingerprintOf(request: {
  agentId: string
  input: Record<string, unknown>
}): string {
  // ⚠️ 键排序：否则对象键顺序变化会导致误判为"内容变更"而换 key
  return JSON.stringify({
    agentId: request.agentId,
    input: sortKeys(request.input),
  })
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeys)
  if (typeof value === 'object' && value !== null) {
    const record = value as Record<string, unknown>
    const sorted: Record<string, unknown> = {}
    for (const key of Object.keys(record).sort()) {
      sorted[key] = sortKeys(record[key])
    }
    return sorted
  }
  return value
}

interface StoredKey {
  key: string
  fingerprint: string
}

function isBrowser(): boolean {
  return typeof window !== 'undefined' && typeof window.sessionStorage !== 'undefined'
}

function readStored(): StoredKey | null {
  if (!isBrowser()) return null
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY)
    if (raw === null) return null
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return null
    const record = parsed as Record<string, unknown>
    if (typeof record['key'] !== 'string') return null
    if (typeof record['fingerprint'] !== 'string') return null
    return { key: record['key'], fingerprint: record['fingerprint'] }
  } catch {
    return null
  }
}

/**
 * 取得本次提交应使用的幂等键。
 *
 * @param request 当前提交内容（用于计算指纹）
 * @returns 与内容绑定的幂等键；内容未变则复用旧 key
 */
export function resolveIdempotencyKey(request: {
  agentId: string
  input: Record<string, unknown>
}): string {
  const fingerprint = fingerprintOf(request)
  const stored = readStored()

  if (stored !== null && stored.fingerprint === fingerprint) {
    return stored.key
  }

  // 内容已变（或无记录）→ 换新 key
  const key = generateIdempotencyKey()
  if (isBrowser()) {
    const payload: StoredKey = { key, fingerprint }
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
  }
  return key
}

/** 创建成功后清除，避免下次误用 */
export function clearIdempotencyKey(): void {
  if (!isBrowser()) return
  window.sessionStorage.removeItem(STORAGE_KEY)
}

/**
 * ⛔ 收到 409 IDEMPOTENCY_CONFLICT 时清除 key。
 *
 * ⚠️ **不得自动换 key 重试**——那可能造成重复 Run 与重复薄弱点写入。
 *    只能提示用户刷新后重新提交。
 */
export function discardIdempotencyKey(): void {
  clearIdempotencyKey()
}
