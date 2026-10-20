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
  PageResponse,
  RunListItem,
  ApprovalItem,
  ApprovalDecisionResponse,
  OperationItem,
  Disposition,
  DispositionResponse,
  AuditEntry,
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

/* ── 治理只读 / 决策（P4-D13 / G7~G10）────────────────────── */

/**
 * 治理类端点的限流纪律（FE-D10）。
 *
 * ⚠️ 后端把治理请求放在**独立的限流桶** `GOVERNANCE_QUERY`（120 次/60 秒），
 *    与学生侧进度页的 `EVENT_QUERY`（30 次/60 秒）**互不相干**——
 *    这正是 P4-D13 把治理桶限额与全局限额分开的原因：
 *    运维翻列表不会把学生的进度页轮询打挂（FE-D9 §2.3 余量只有 4 次/分）。
 *
 * ⚠️ 但**治理页之间仍共享同一桶**（按租户限流，不按人）：
 *    故本页**不做自动轮询**，只在用户显式操作时请求。
 */

/** 列表类查询的通用参数（游标分页）。 */
export interface ListQuery {
  limit?: number
  cursor?: string | null
  state?: string | null
}

/** 审计查询参数：⚠️ 动作字段叫 `action` 而非 `state`（见 {@link queryAudit}）。 */
export interface AuditQuery extends ListQuery {
  action?: string | null
  resourceType?: string | null
  resourceId?: string | null
  from?: string | null
  to?: string | null
}

/**
 * 拼查询串。
 *
 * ⚠️ 空值一律**省略**而不是传空串：
 * 后端对 `state=""` 会走「不限」分支，但对 `limit=` 会因非数字而 400。
 * 前端多传一个空参数就换回一个 400，排查成本极高。
 */
function toQuery(params: Record<string, string | number | null | undefined>): string {
  const parts: string[] = []
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length === 0 ? '' : `?${parts.join('&')}`
}

/** GET /api/runs —— Run 列表（游标分页）。 */
export function listRuns(
  params: ListQuery & { createdFrom?: string | null; createdTo?: string | null } = {},
): Promise<PageResponse<RunListItem>> {
  return _request<PageResponse<RunListItem>>(
    `/api/runs${toQuery({ ...params })}`,
    { method: 'GET' },
    false,
  )
}

/** GET /api/approvals —— 审批列表（游标分页）。 */
export function listApprovals(
  params: ListQuery = {},
): Promise<PageResponse<ApprovalItem>> {
  return _request<PageResponse<ApprovalItem>>(
    `/api/approvals${toQuery({ ...params })}`,
    { method: 'GET' },
    false,
  )
}

/**
 * POST /api/approvals/{id}/decision —— 记录审批决定。
 *
 * ⚠️ **没有任何身份字段**（`approverId` 由后端从令牌取）：
 * 传了也会被后端忽略，且传错会让人误以为「可以把审批记到别人名下」。
 *
 * ⚠️ `reason` 在 DENY 时**必填**，缺失后端返回 400。
 */
export function decideApproval(
  approvalId: string,
  decision: 'APPROVE' | 'DENY',
  reason?: string | null,
): Promise<ApprovalDecisionResponse> {
  return _request<ApprovalDecisionResponse>(
    `/api/approvals/${encodeURIComponent(approvalId)}/decision`,
    {
      method: 'POST',
      body: JSON.stringify({ decision, reason: reason ?? null }),
    },
    true,
  )
}

/** GET /api/operations —— 工具操作列表（游标分页）。 */
export function listOperations(
  params: ListQuery = {},
): Promise<PageResponse<OperationItem>> {
  return _request<PageResponse<OperationItem>>(
    `/api/operations${toQuery({ ...params })}`,
    { method: 'GET' },
    false,
  )
}

/**
 * POST /api/operations/{id}/disposition —— 人工处置 UNKNOWN 操作。
 *
 * ⛔⛔ `disposition` 的类型**不含 `RETRY`**——不是遗漏，是 Sev-0 级产品边界。
 */
export function disposeOperation(
  operationId: string,
  disposition: Disposition,
  note?: string | null,
): Promise<DispositionResponse> {
  return _request<DispositionResponse>(
    `/api/operations/${encodeURIComponent(operationId)}/disposition`,
    {
      method: 'POST',
      body: JSON.stringify({ disposition, note: note ?? null }),
    },
    true,
  )
}

/**
 * GET /api/audit —— 审计查询。
 *
 * ⚠️ 审计是**只读**的：本文件刻意不提供任何删除/修改端点。
 */
export function queryAudit(
  params: AuditQuery = {},
): Promise<PageResponse<AuditEntry>> {
  return _request<PageResponse<AuditEntry>>(
    // ⚠️ `action` 必须**显式**映射：`ListQuery` 只有 `state`，
    //    若直接 `{ ...params }` 展开，`action` 仍会被 toQuery 带上——
    //    但一旦有人把 `action` 写成 `state`（或反过来），
    //    TS 在展开后**不报错**，筛选条件会**静默失效**，
    //    页面表现为「怎么筛都没数据」。故逐字段列举。
    `/api/audit${toQuery({
      limit: params.limit,
      cursor: params.cursor,
      action: params.action,
      resourceType: params.resourceType,
      resourceId: params.resourceId,
      from: params.from,
      to: params.to,
    })}`,
    { method: 'GET' },
    false,
  )
}
