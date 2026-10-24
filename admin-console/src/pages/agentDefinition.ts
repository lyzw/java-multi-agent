/**
 * Agent definition 预校验（P1）。
 *
 * <p>发布版本的 {@code definition} 必须是合法 JSON（后端 400 兜底）。
 * 前端先预校验，给出即时反馈，避免无效请求打到后端。
 */

/** definition 是否为「非空且合法」的 JSON。 */
export function isValidJson(raw: string): boolean {
  if (!raw || !raw.trim()) return false
  try {
    JSON.parse(raw)
    return true
  } catch {
    return false
  }
}
