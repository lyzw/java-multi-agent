/**
 * fetch 封装 —— 令牌注入 + 错误归一化 + 401 兜底（复用现有前端契约）
 */

import { loadSession, clearSession } from '@/auth/tokenStorage'
import { API_BASE, APP_BASE_PATH } from '@/config/constants'

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly data?: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function redirectLogin(): void {
  if (typeof window !== 'undefined') {
    // ⚠️ **必须带上「为什么」和「回哪去」**（评测 P1-5）：
    //    裸跳登录页会让用户以为是自己点的，等他重新登录完又被送回默认页
    //    （/tenants），刚才的操作现场全丢。平台会话只有 15 分钟且不可续期，
    //    这种「被踢出且不解释」是控制台里最容易让人失去信任的一处。
    const full = `${window.location.pathname}${window.location.search}`
    // 回跳目标必须是 **basename 相对路径**（navigate() 会再拼一次 basename）。
    const rel = full.startsWith(APP_BASE_PATH) ? full.slice(APP_BASE_PATH.length) : ''
    const params = new URLSearchParams({ reason: 'expired' })
    if (rel && rel !== '/' && !rel.startsWith('//')) params.set('next', rel)
    window.location.href = `${APP_BASE_PATH}/login?${params.toString()}`
  }
}

/**
 * 本控制台**自身会话**的端点前缀。
 *
 * ⚠️ 只有这些路径返回 401 才说明「平台令牌失效」，值得清会话并跳登录。
 *    其余端点（`/runs`、`/cap/**`、`/admin/**`、`/config/**`、`/agents`）属
 *    **租户身份域**：平台令牌（`aud=admin-console-api`）打过去必然 401 ——
 *    那是身份域隔离按设计生效，与会话有效性无关。
 *
 * 此前一律按「登录失效」处理，实测后果是路由策略页与 AI 实验室页刚写好的
 * 「通道不可达」提示**永远走不到**：用户点「试跑」或「发起执行」被直接踢回
 * 登录页，草稿与会话一起丢失（2026-10-28 前端端到端测试）。
 *
 * 本控制台的会话域只有 `/platform/v1/**`（登录为 `/platform/v1/auth/login`）；
 * `/auth/token`、`/auth/roles` 属租户身份域，不在其中。
 */
const PLATFORM_SESSION_PREFIX = '/platform/'

function isPlatformSessionPath(path: string): boolean {
  return path.startsWith(PLATFORM_SESSION_PREFIX)
}

async function _request<T>(
  path: string,
  init: RequestInit = {},
  auth = true,
  extraHeaders?: Record<string, string>,
): Promise<T> {
  const headers = new Headers(init.headers)
  if (auth) {
    const s = loadSession()
    if (s) headers.set('Authorization', `Bearer ${s.accessToken}`)
  }
  if (init.body !== undefined) headers.set('Content-Type', 'application/json')
  headers.set('Accept', 'application/json')
  // ⚠️ 额外头（如 RAG 管理端点强制要求的 X-Tenant-Id）。
  //    必须显式携带：跨进程 HTTP 调用时主平台事务上下文不会随之而来，
  //    后端须按此头显式重建租户上下文，缺失即 400（而非静默空列表）。
  if (extraHeaders) {
    for (const [key, value] of Object.entries(extraHeaders)) {
      headers.set(key, value)
    }
  }

  let res: Response
  try {
    // ⚠️ 透传 init.signal：调用方可借 AbortController 实现超时（RAG 管理端点）。
    res = await fetch(`${API_BASE}${path}`, { ...init, headers })
  } catch (e) {
    throw new ApiError(0, `网络错误：${(e as Error).message}`)
  }

  if (res.status === 401) {
    if (isPlatformSessionPath(path)) {
      clearSession()
      redirectLogin()
      throw new ApiError(401, '登录已失效，请重新登录')
    }
    // ⚠️ 跨身份域端点的 401 是**预期的通道边界**，不是会话失效：
    //    不清会话、不跳登录，原样抛出交给调用方
    //    （`isTenantChannelBlocked`）呈现「需租户级令牌通道」。
    throw new ApiError(401, '平台令牌对该端点不可达（身份域隔离）')
  }
  if (res.status === 204) return undefined as T

  const text = await res.text()
  const data: unknown = text.length > 0 ? JSON.parse(text) : null
  if (!res.ok) {
    const msg =
      data && typeof data === 'object' && 'message' in data
        ? String((data as Record<string, unknown>)['message'])
        : `请求失败（${res.status}）`
    throw new ApiError(res.status, msg, data)
  }
  return data as T
}

/**
 * 通用请求入口（auth 默认开启）。
 *
 * @param extraHeaders 额外请求头（如 RAG 管理端点的 `X-Tenant-Id`）。
 *   透传至底层 fetch；调用方亦可在 `init.signal` 传 AbortSignal 实现超时。
 */
export function request<T>(
  path: string,
  init?: RequestInit,
  auth = true,
  extraHeaders?: Record<string, string>,
): Promise<T> {
  return _request<T>(path, init, auth, extraHeaders)
}
