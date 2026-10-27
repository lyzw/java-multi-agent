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
    window.location.href = `${APP_BASE_PATH}/login`
  }
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
    clearSession()
    redirectLogin()
    throw new ApiError(401, '登录已失效，请重新登录')
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
