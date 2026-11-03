/**
 * fetch 封装 —— 令牌注入 + 错误归一化 + 401 兜底（复用现有前端契约）
 */

import { loadSession, clearSession } from '@/auth/tokenStorage'
import { API_BASE, APP_BASE_PATH } from '@/config/constants'

export class ApiError extends Error {
  /** HTTP 状态码；网络层失败为 0 */
  readonly status: number
  /**
   * 统一错误码（与租户端 `NormalizedError.code` 同口径）。
   *
   * ⚠️ 来自后端两类错误形状：{code,message}（ApiExceptionHandler / Security 过滤器）
   * 或 {error}（AuthController 登录失败）。构造时未显式给出 code，则按状态码推导：
   * 401→UNAUTHENTICATED，403→ACCESS_DENIED，其余→HTTP_<status>。
   * 旧的 `new ApiError(status, message, data?)` 调用因此仍可正常工作（code 自动推导）。
   */
  readonly code: string
  readonly data?: unknown

  constructor(status: number, message: string, data?: unknown, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.data = data
    this.code = code ?? deriveCodeFromStatus(status)
  }
}

/** 仅按状态码推导兜底 code（体缺失 / 测试手工构造错误时） */
function deriveCodeFromStatus(status: number): string {
  if (status === 401) return 'UNAUTHENTICATED'
  if (status === 403) return 'ACCESS_DENIED'
  return `HTTP_${status}`
}

/**
 * 从响应体提取统一错误码（与租户端 errors.ts 的 readCodeMessage / readErrorField 同口径）。
 *
 * ⚠️ 兼容后端两套错误形状：① {code,message} ② {error}。
 * 体缺失时按状态码推导，保证「无 body 的裸 401/403」也能拿到正确 code，
 * 从而让「按 code 分支」不依赖后端一定带了 body。
 */
function extractCode(status: number, body: unknown): string {
  if (body && typeof body === 'object') {
    const rec = body as Record<string, unknown>
    if (typeof rec['code'] === 'string') return rec['code']
    if (typeof rec['error'] === 'string') {
      const mapped: Record<string, string> = {
        unauthorized: 'UNAUTHENTICATED',
        forbidden: 'ACCESS_DENIED',
        invalid_credentials: 'INVALID_CREDENTIALS',
      }
      return mapped[rec['error']] ?? `HTTP_${status}`
    }
  }
  if (status === 401) return 'UNAUTHENTICATED'
  if (status === 403) return 'ACCESS_DENIED'
  return `HTTP_${status}`
}

/** 从响应体提取面向用户文案：优先后端 message，否则按 code 兜底 */
function extractMessage(status: number, code: string, body: unknown): string {
  if (
    body &&
    typeof body === 'object' &&
    'message' in body &&
    typeof (body as Record<string, unknown>)['message'] === 'string'
  ) {
    return String((body as Record<string, unknown>)['message'])
  }
  switch (code) {
    case 'UNAUTHENTICATED':
      return '登录已过期，请重新登录'
    case 'ACCESS_DENIED':
      return '无权执行该操作'
    default:
      return `请求失败（${status}）`
  }
}

/**
 * 是否为「业务无权限」（已登录但缺少所需权限）。
 *
 * ⚠️ 与身份认证失败（UNAUTHENTICATED）严格区分：无权限**不**触发登录跳转，
 * 只提示「无权执行该操作」或渲染无权限整页。把二者混为一谈会导致：
 * 用户做一笔正常业务操作被拒 → 被当成「未登录」直接踢回登录页、丢掉操作现场。
 */
export function isAccessDenied(cause: unknown): boolean {
  return cause instanceof ApiError && cause.code === 'ACCESS_DENIED'
}

/**
 * 已登录但缺权限（code=ACCESS_DENIED）的统一落点。
 *
 * @param err 捕获到的错误（通常是 `ApiError`）
 * @param navigate 传入则跳转到「无权限」整页（保留现场，可返回上一页）；
 *                 不传则返回 false，由调用方自行用既有的错误态呈现。
 * @returns 是否命中 ACCESS_DENIED（便于调用方 if 分支后做其它事）
 */
export function notifyAccessDenied(
  err: unknown,
  navigate?: (to: string) => void,
): boolean {
  if (!isAccessDenied(err)) return false
  if (navigate) {
    const message = err instanceof ApiError ? err.message : ''
    const qs = message ? `?reason=${encodeURIComponent(message)}` : ''
    navigate(`/no-permission${qs}`)
  }
  return true
}

/**
 * 清会话并跳登录页（带 reason=expired 与回跳地址）。
 *
 * ⚠️ 2026-11-03 导出：RAG 管理端点（/v1/**，独立 rag 服务）与平台会话是
 *    <b>同一身份域</b>——平台会话令牌过期时 RAG 返回 401，语义就是「登录已过期」，
 *    ragClient 据此调用本函数，而不是把 401 误判成「跨身份域边界」
 *    （后者只适用于平台令牌打租户端点的场景）。
 */
export function redirectLogin(): void {
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

/** 平台登录端点（公开认证入口）。它的 401 = 凭据错误，绝不是「会话过期」。 */
const PLATFORM_LOGIN_PATH = '/platform/v1/auth/login'

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
  // ⚠️ 仅当 body 不是 FormData 时才强制 JSON：multipart 上传（文件）必须保留浏览器
  //    自动生成的 `Content-Type: multipart/form-data; boundary=...`，手动覆盖成
  //    application/json 会让后端收不到文件 part（RAG 文档上传端点）。
  if (init.body !== undefined && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
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
    throw new ApiError(0, `网络错误：${(e as Error).message}`, undefined, 'NETWORK_ERROR')
  }

  if (res.status === 204) return undefined as T

  const text = await res.text()
  const data: unknown = text.length > 0 ? JSON.parse(text) : null

  if (!res.ok) {
    const code = extractCode(res.status, data)
    // ⚠️ **按「归一化 code + 路径」双轴判定是否清会话跳登录**（权限重构）：
    //   两条件**同时**满足才登出：
    //     · 轴一（路径）：仅本控制台自身会话域 `/platform/**` 的拒绝才可能意味令牌失效；
    //     · 轴二（code）：该拒绝必须是 UNAUTHENTICATED（未认证 / 令牌失效）。
    //   其余情形一律**不清会话、不跳登录**，原样抛出交给调用方分支：
    //     · 跨身份域 401（平台令牌打租户端点）→ 通道边界，交 `isTenantChannelBlocked` 提示；
    //     · 任意 ACCESS_DENIED（含 /platform/** 的 403，已登录但无权限）→ 渲染无权限页。
    //   以往对 401 一律清会话跳登录，会把「业务无权限 / 跨身份域 401」误判为未登录，
    //   直接把用户踢回登录页、丢掉操作现场（2026-10-28 实测缺陷）。
    if (res.status === 401) {
      // ⚠️ 登录端点（公开认证入口）的 401 = 凭据错误，绝非「会话过期」。
      //    必须原样抛出交 LoginPage 显示「用户名或密码错误」，严禁在此清会话 / 跳登录——
      //    否则用户刚输完密码就被弹回登录页、且看不到任何错误原因，表现上像「登录卡死 / 超时」。
      if (path === PLATFORM_LOGIN_PATH) {
        throw new ApiError(401, '用户名或密码错误', data, code)
      }
      // ⚠️ 本控制台自身会话域（非登录）的 401 = 平台令牌失效 → 清会话并跳登录
      if (isPlatformSessionPath(path)) {
        clearSession()
        redirectLogin()
        throw new ApiError(401, '登录已失效，请重新登录', data, code)
      }
      // ⚠️ 跨身份域端点的 401 是**预期的通道边界**，不是会话失效：
      //    不清会话、不跳登录，原样抛出交给调用方
      //    （`isTenantChannelBlocked`）呈现「需租户级令牌通道」。
      throw new ApiError(401, '平台令牌对该端点不可达（身份域隔离）', data, code)
    }
    const message = extractMessage(res.status, code, data)
    throw new ApiError(res.status, message, data, code)
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
