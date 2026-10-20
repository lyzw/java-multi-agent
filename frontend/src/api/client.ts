/**
 * fetch 封装 —— 令牌注入 + 错误归一化 + 401 兜底（FE-D1 §3）
 */

import {
  normalizeHttpError,
  normalizeNetworkError,
  type NormalizedError,
} from './errors'
import { loadSession, clearSession } from '@/auth/tokenStorage'
import type {
  LoginRequest,
  LoginResponse,
  CreateRunRequest,
  CreateRunResponse,
  ConfigResponse,
  RunResultResponse,
  ProfileResponse,
  RunStatusResponse,
} from './types'

/** 401 时通知上层（SessionProvider）清理会话并跳登录 */
type UnauthorizedHandler = () => void

let onUnauthorized: UnauthorizedHandler = () => {}

export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  onUnauthorized = handler
}

const BASE = ''

function buildHeaders(hasBody: boolean): Headers {
  const headers = new Headers()
  if (hasBody) headers.set('Content-Type', 'application/json')
  const session = loadSession()
  if (session !== null) {
    headers.set('Authorization', `Bearer ${session.accessToken}`)
  }
  return headers
}

/** 解析响应体；失败时返回 null（不抛，交给归一化处理） */
async function readBody(response: Response): Promise<unknown> {
  try {
    const text = await response.text()
    if (text.length === 0) return null
    return JSON.parse(text) as unknown
  } catch {
    return null
  }
}

export class ApiError extends Error {
  readonly normalized: NormalizedError

  constructor(normalized: NormalizedError) {
    super(normalized.message)
    this.name = 'ApiError'
    this.normalized = normalized
  }
}

/**
 * 统一请求入口
 *
 * ⚠️ **命名约束**：本函数名与形参名必须与外层符号都不同。
 * 曾发生过真实缺陷：`login(request: LoginRequest)` 的形参 `request`
 * 遮蔽了同名模块级 `request` 函数 → 运行时 `request2 is not a function`
 * → 所有 HTTP 调用失效。
 * 故内部函数用 `_request`，对外形参用 `payload`，避免任何同名遮蔽。
 */
async function _request<T>(
  path: string,
  init: RequestInit,
  hasBody: boolean,
): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${BASE}${path}`, {
      ...init,
      headers: buildHeaders(hasBody),
    })
  } catch (cause) {
    throw new ApiError(normalizeNetworkError(cause))
  }

  const body = await readBody(response)

  if (!response.ok) {
    const normalized = normalizeHttpError(response.status, body)
    // ⚠️ 401 触发全局清理：避免半登录态（FE-D1 §4.3）
    if (response.status === 401) {
      clearSession()
      onUnauthorized()
    }
    throw new ApiError(normalized)
  }

  return body as T
}

/* ── 认证 ──────────────────────────────────────────────── */

/**
 * POST /api/auth/token
 *
 * ⚠️ tenantId 由客户端提供，但后端登录名全局唯一且失败路径耗时一致，
 *    故不产生可利用的枚举面（后端 D-001 §3.2）。
 */
export function login(payload: LoginRequest): Promise<LoginResponse> {
  return _request<LoginResponse>(
    '/api/auth/token',
    { method: 'POST', body: JSON.stringify(payload) },
    true,
  )
}

/* ── Run ───────────────────────────────────────────────── */

export function createRun(payload: CreateRunRequest): Promise<CreateRunResponse> {
  return _request<CreateRunResponse>(
    '/api/runs',
    { method: 'POST', body: JSON.stringify(payload) },
    true,
  )
}

/* ── 配置只读（P4-D07 / C1）─────────────────────────── */

/**
 * GET /api/config
 *
 * ⚠️ 只读、零副作用、不暴露密钥（P4-D07 B-2）。
 * 返回 yml 声明值 + 每条配置的 `effective`（是否真正接入运行时），
 * 供配置页诚实呈现「声明 vs 实际」。
 */
export function getConfig(): Promise<ConfigResponse> {
  return _request<ConfigResponse>('/api/config', { method: 'GET' }, false)
}

/**
 * GET /api/runs/{runId}/events —— 返回 SSE 文本流。
 *
 * ⚠️ 不用 EventSource（FE-D1 §3.4）：
 *    ① 后端是**短连接轮询**，不是长连接推送
 *    ② 需自定义 Last-Event-ID 头，EventSource 不支持
 * 故用 fetch + ReadableStream 自行解析。
 */
export async function openEventStream(
  runId: string,
  lastEventId: number | null,
): Promise<Response> {
  const headers = new Headers()
  const session = loadSession()
  if (session !== null) {
    headers.set('Authorization', `Bearer ${session.accessToken}`)
  }
  headers.set('Accept', 'text/event-stream')
  if (lastEventId !== null) {
    headers.set('Last-Event-ID', String(lastEventId))
  }

  let response: Response
  try {
    response = await fetch(`/api/runs/${encodeURIComponent(runId)}/events`, {
      method: 'GET',
      headers,
    })
  } catch (cause) {
    throw new ApiError(normalizeNetworkError(cause))
  }

  if (!response.ok) {
    const body = await readBody(response)
    const normalized = normalizeHttpError(response.status, body)
    if (response.status === 401) {
      clearSession()
      onUnauthorized()
    }
    throw new ApiError(normalized)
  }

  return response
}

/* ── 结果读取（P4-D10 / G5）────────────────────────────────── */

/**
 * GET /api/runs/{runId}/result
 *
 * ⚠️ 404 表示「不存在**或**不属于本租户」——后端**不区分**二者，
 *    故前端**不得**据此推断「该 Run 属于别人」。
 */
export function getRunResult(runId: string): Promise<RunResultResponse> {
  return _request<RunResultResponse>(
    `/api/runs/${encodeURIComponent(runId)}/result`,
    { method: 'GET' },
    false,
  )
}

/* ── 学习画像（P4-D09 / G6）─────────────────────────────────── */

/**
 * GET /api/profiles/{studentId}
 *
 * ⚠️ 不存在时返回 **200 + notFound:true**（不是 404）——
 *    返回 404 会构成 studentId 枚举通道。
 */
export function getProfile(studentId: string): Promise<ProfileResponse> {
  return _request<ProfileResponse>(
    `/api/profiles/${encodeURIComponent(studentId)}`,
    { method: 'GET' },
    false,
  )
}

/* ── Run 状态查询（P4-D09 / G3）──────────────────────────────── */

/**
 * GET /api/runs/{runId}
 *
 * ⚠️ 与 SSE 端点**共用同一个限流桶**（EVENT_QUERY，30 次/60 秒）——
 *    故状态轮询间隔必须大于 SSE 的 3 秒（见 FE-D9 §2.3 配额分配）。
 */
export function getRunStatus(runId: string): Promise<RunStatusResponse> {
  return _request<RunStatusResponse>(
    `/api/runs/${encodeURIComponent(runId)}`,
    { method: 'GET' },
    false,
  )
}
