/**
 * fetch 封装 —— 令牌注入 + 错误归一化 + 401 兜底（FE-D1 §3）
 */

import {
  normalizeHttpError,
  normalizeNetworkError,
  makeTimeoutError,
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
  AgentItem,
  AgentVersionItem,
  ConfigOverrideItem,
  ApplyOverrideRequest,
  CreateAgentRequest,
  PublishVersionRequest,
  RagRetrievalRequest,
  RagRetrievalResponse,
  RagGenerateRequest,
  RagGenerateResponse,
  RagStatistics,
  RagSource,
  RagDocument,
  RagChunk,
  RagRegisterSourceRequest,
  RagVerificationRequest,
  RagSourceQuery,
} from './types'

/** 401 时通知上层（SessionProvider）清理会话并跳登录 */
type UnauthorizedHandler = () => void

let onUnauthorized: UnauthorizedHandler = () => {}

export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  onUnauthorized = handler
}

const BASE = ''

function buildHeaders(hasBody: boolean, extra?: Record<string, string>): Headers {
  const headers = new Headers()
  if (hasBody) headers.set('Content-Type', 'application/json')
  const session = loadSession()
  if (session !== null) {
    headers.set('Authorization', `Bearer ${session.accessToken}`)
  }
  if (extra !== undefined) {
    for (const [key, value] of Object.entries(extra)) {
      headers.set(key, value)
    }
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
  signal?: AbortSignal,
  extraHeaders?: Record<string, string>,
): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${BASE}${path}`, {
      ...init,
      headers: buildHeaders(hasBody, extraHeaders),
      ...(signal !== undefined ? { signal } : {}),
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

/* ── Agent 配置与发布（P4-D14 / G2）──────────────────── */

/**
 * GET /api/agents —— Agent 列表（游标分页）。
 *
 * ⚠️ 与治理页同桶 `GOVERNANCE_QUERY`，故**不做自动轮询**。
 * 仅 `limit` / `cursor`，其余筛选暂不需要（后端也不支持）。
 */
export function listAgents(
  params: ListQuery = {},
): Promise<PageResponse<AgentItem>> {
  return _request<PageResponse<AgentItem>>(
    `/api/agents${toQuery({ limit: params.limit, cursor: params.cursor })}`,
    { method: 'GET' },
    false,
  )
}

/**
 * POST /api/agents —— 创建 Agent。
 *
 * ⚠️ 创建人由后端从令牌取，请求体**不含**创建人字段。
 * 租户内重名 → 409（后端唯一约束兜底），前端按冲突提示即可。
 */
export function createAgent(
  payload: CreateAgentRequest,
): Promise<AgentItem> {
  return _request<AgentItem>(
    '/api/agents',
    { method: 'POST', body: JSON.stringify(payload) },
    true,
  )
}

/**
 * POST /api/agents/{agentId}/versions —— 发布新版本。
 *
 * ⚠️ `definition` 是不可变快照（JSON 文本）；要改配置只能再发一个版本，
 *     端点**不提供**「编辑已发布版本」。
 * 不存在的 agent → 404；并发发布竞态 → 409（前端按冲突提示）。
 */
export function publishAgentVersion(
  agentId: string,
  payload: PublishVersionRequest,
): Promise<AgentVersionItem> {
  return _request<AgentVersionItem>(
    `/api/agents/${encodeURIComponent(agentId)}/versions`,
    { method: 'POST', body: JSON.stringify(payload) },
    true,
  )
}

/* ── 配置写入（P4-D15 / F6 · C3）────────────────────────────── */

/**
 * GET /api/config/overrides —— 列本租户的可写配置及其生效值。
 *
 * ⚠️ 与 `getConfig()`（只读目录）**刻意分开**：只读端点给出
 * 「声明值 + 是否接线」，本端点给出「**声明值 + 生效值 + 来源**」，
 * 写页面需要知道用户改的值**到底有没有真的生效**。
 */
export function listConfigOverrides(): Promise<ConfigOverrideItem[]> {
  return _request<ConfigOverrideItem[]>(
    '/api/config/overrides',
    { method: 'GET' },
    false,
  )
}

/**
 * PUT /api/config/overrides/{key} —— 写入一条配置覆盖。
 *
 * ⚠️ 收紧类配置**只能调小**；试图调大会得到 **409 +
 * `CONFIG_RELAXATION_REJECTED`**，调用方应据此提示「只能收紧」，
 * 而**不是**提示「刷新重试」——刷新永远不会让收紧限制变松。
 *
 * ⚠️ `reason` 必填且后端要求 ≥10 字（C7 变更模板）。
 */
export function applyConfigOverride(
  key: string,
  payload: ApplyOverrideRequest,
): Promise<ConfigOverrideItem> {
  return _request<ConfigOverrideItem>(
    `/api/config/overrides/${encodeURIComponent(key)}`,
    { method: 'PUT', body: JSON.stringify(payload) },
    true,
  )
}

/**
 * DELETE /api/config/overrides/{key} —— 清除覆盖，回落到 yml 默认值。
 *
 * ⚠️ **清除也是一次变更**（会收紧/放宽取决于方向），后端同样记审计。
 */
export function clearConfigOverride(key: string): Promise<void> {
  return _request<void>(
    `/api/config/overrides/${encodeURIComponent(key)}`,
    { method: 'DELETE' },
    false,
  )
}

/* ── RAG 检索增强（rag 模块 /api/v1）──────────────────── */

/**
 * RAG 请求的默认超时（毫秒）。
 *
 * ⚠️ 检索/生成是**重操作**（向量召回 + 模型推理），后端无 SLA 保证，
 *    前端必须自带超时而不是无限等待（FE-D3 五态纪律：加载态要有出口）。
 *    超时会被归一化为 REQUEST_TIMEOUT，页面给出「重试 / 换问题」的下一步。
 */
const DEFAULT_RAG_TIMEOUT_MS = 20000

/**
 * 带超时的请求封装。
 *
 * ⛔ 用 `timedOut` 标志判定超时，而非依赖 `fetch` 抛出的 AbortError：
 *    `_request` 内部已把 fetch 异常归一为 `ApiError(NETWORK_ERROR)`，
 *    AbortError 不会原样冒泡。用标志能稳定区分「主动超时」与「其他网络失败」。
 */
async function requestWithTimeout<T>(
  path: string,
  init: RequestInit,
  hasBody: boolean,
  timeoutMs: number,
  extraHeaders?: Record<string, string>,
): Promise<T> {
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  try {
    return await _request<T>(path, init, hasBody, controller.signal, extraHeaders)
  } catch (cause) {
    if (timedOut) throw new ApiError(makeTimeoutError())
    throw cause
  } finally {
    clearTimeout(timer)
  }
}

/**
 * POST /api/v1/retrieval —— 执行检索，返回可溯源的召回片段。
 *
 * ⚠️ 严格对齐后端 `RagRetrievalController`（RAG-D02 §6）。
 *    `edition` 五轴必须命中后端枚举展示名，否则后端 400。
 *
 * ⚠️ **必须携带 `X-Tenant-Id`**：检索 SQL 以 `tenant_id = rag_current_tenant()`
 *    收口，而该函数读的是**会话变量** `app.tenant_id`——只在事务内有效。
 *    跨进程 HTTP 调用时主平台的事务上下文不会随之而来，
 *    后端必须显式重建租户上下文；缺失该头后端返回 400（而非静默空结果）。
 */
export function retrieveChunks(
  payload: RagRetrievalRequest,
  timeoutMs: number = DEFAULT_RAG_TIMEOUT_MS,
  tenantId?: string,
): Promise<RagRetrievalResponse> {
  return requestWithTimeout<RagRetrievalResponse>(
    '/api/v1/retrieval',
    { method: 'POST', body: JSON.stringify(payload) },
    true,
    timeoutMs,
    tenantId === undefined || tenantId.trim() === ''
      ? undefined
      : { 'X-Tenant-Id': tenantId.trim() },
  )
}

/**
 * POST /api/v1/retrieval/generate —— 在检索召回基础上生成讲解。
 *
 * ⚠️ **后端尚未实现该端点**（rag 模块当前只暴露检索），故会返回 404/501，
 *    上层按「生成能力未接入」诚实呈现，**不要**当故障刷红。
 *
 * ⚠️ **必须携带 `X-Tenant-Id`**（B2 修复，2026-10-23）：生成端点与前端的
 *    检索端点共用同一套 RLS 租户收口，缺失该头后端返回 400。此前该调用
 *    **漏传**此头，是已确认的缺陷——生成端点一旦上线即 400。
 *    租户值取自会话（登录回带的权威 tenantId），与 `retrieveChunks` 同源。
 */
export function generateRagAnswer(
  payload: RagGenerateRequest,
  timeoutMs: number = DEFAULT_RAG_TIMEOUT_MS,
  tenantId?: string,
): Promise<RagGenerateResponse> {
  return requestWithTimeout<RagGenerateResponse>(
    '/api/v1/retrieval/generate',
    { method: 'POST', body: JSON.stringify(payload) },
    true,
    timeoutMs,
    tenantId === undefined || tenantId.trim() === ''
      ? undefined
      : { 'X-Tenant-Id': tenantId.trim() },
  )
}

/* ── RAG 语料数据管理（rag-management / RAG-D10）────────────── */

/**
 * RAG 管理端点的默认超时（毫秒）。
 *
 * ⚠️ 管理面是**人工操作**，每次都是显式点击触发，
 *    不像检索那样有自动重试。故超时给得比检索宽松，
 *    避免管理员在审核过程中被 20 秒超时打断。
 */
const DEFAULT_RAG_MGMT_TIMEOUT_MS = 30000

/**
 * 管理端点的租户头。
 *
 * ⚠️ **必须携带 `X-Tenant-Id`**：管理 SQL 一律以
 * `tenant_id = rag_current_tenant()` 收口，而该函数读的是**会话变量**
 * `app.tenant_id`——只在事务内有效。跨进程 HTTP 调用时主平台的事务上下文
 * 不会随之而来，后端必须显式重建租户上下文。
 * 缺失该头后端返回 **400**（而非静默返回空列表）。
 */
function tenantHeaders(tenantId: string): Record<string, string> {
  return { 'X-Tenant-Id': tenantId.trim() }
}

/**
 * GET /api/v1/management/statistics —— 索引与准入统计。
 *
 * ⚠️ 统计口径已区分「已入库」与「可检索」（RAG-D10 D-3）——
 *    管理方最常见的误判是「片段数 = 可用知识量」。
 */
export function getRagStatistics(
  tenantId: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagStatistics> {
  return requestWithTimeout<RagStatistics>(
    '/api/v1/management/statistics',
    { method: 'GET' },
    false,
    timeoutMs,
    tenantHeaders(tenantId),
  )
}

/**
 * GET /api/v1/management/sources —— 列出来源。
 *
 * ⚠️ 后端 `limit` 上限为 500，超出返回 400；
 *    `includeStale=false`（默认）时**过期来源不返回**——
 *    「过期」是计算属性（verified_at + valid_days < now），
 *    不是状态位，故由服务端在 SQL 中计算。
 */
export function listRagSources(
  tenantId: string,
  query: RagSourceQuery = {},
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource[]> {
  return requestWithTimeout<RagSource[]>(
    `/api/v1/management/sources${toQuery({
      status: query.status,
      trustTier: query.trustTier,
      keyword: query.keyword,
      includeStale: query.includeStale === true ? 'true' : null,
      limit: query.limit,
      offset: query.offset,
    })}`,
    { method: 'GET' },
    false,
    timeoutMs,
    tenantHeaders(tenantId),
  )
}

/**
 * GET /api/v1/management/sources/{sourceId} —— 来源详情。
 *
 * ⚠️ 不存在返回 **404**。跨租户访问同样表现为 404
 *    （RLS 过滤掉，前端**不得**据此推断「该来源属于别的租户」）。
 */
export function getRagSource(
  tenantId: string,
  sourceId: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource> {
  return requestWithTimeout<RagSource>(
    `/api/v1/management/sources/${encodeURIComponent(sourceId)}`,
    { method: 'GET' },
    false,
    timeoutMs,
    tenantHeaders(tenantId),
  )
}

/**
 * POST /api/v1/management/sources —— 登记语料来源。
 *
 * ⚠️ 登记结果恒为 PENDING / UNVERIFIED（采集员不得自行提升等级）。
 * ⚠️ `registeredBy` 必填——登记必须留痕（后端 principal_id NOT NULL）。
 */
export function registerRagSource(
  tenantId: string,
  payload: RagRegisterSourceRequest,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource> {
  return requestWithTimeout<RagSource>(
    '/api/v1/management/sources',
    { method: 'POST', body: JSON.stringify(payload) },
    true,
    timeoutMs,
    tenantHeaders(tenantId),
  )
}

/**
 * POST /api/v1/management/sources/{sourceId}/verification —— 校验状态流转。
 *
 * ⚠️ **409 = 非法状态转移**（RAG-D07 §2.4 T-1～T-12），不是服务故障：
 *    例如「已拒绝 → 直接通过」被禁（必须先回到「审核中」）、
 *    「已撤回 → 任意」被禁（撤回是终态）。
 *    前端须把 409 呈现为**流程走错了**并给出合法下一步，
 *    **绝不能**提示「刷新后重试」——刷新不会让非法转移变合法。
 */
export function applyRagVerification(
  tenantId: string,
  sourceId: string,
  payload: RagVerificationRequest,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource> {
  return requestWithTimeout<RagSource>(
    `/api/v1/management/sources/${encodeURIComponent(sourceId)}/verification`,
    { method: 'POST', body: JSON.stringify(payload) },
    true,
    timeoutMs,
    tenantHeaders(tenantId),
  )
}

/**
 * GET /api/v1/management/sources/{sourceId}/documents —— 列出来源的载体。
 */
export function listRagDocuments(
  tenantId: string,
  sourceId: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDocument[]> {
  return requestWithTimeout<RagDocument[]>(
    `/api/v1/management/sources/${encodeURIComponent(sourceId)}/documents`,
    { method: 'GET' },
    false,
    timeoutMs,
    tenantHeaders(tenantId),
  )
}

/**
 * GET /api/v1/management/documents/{documentId}/chunks —— 列出文档的片段摘要。
 *
 * ⚠️ 后端 limit 上限 500，超出返回 400。
 */
export function listRagChunks(
  tenantId: string,
  documentId: string,
  limit = 100,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagChunk[]> {
  return requestWithTimeout<RagChunk[]>(
    `/api/v1/management/documents/${encodeURIComponent(documentId)}/chunks${toQuery({
      limit,
    })}`,
    { method: 'GET' },
    false,
    timeoutMs,
    tenantHeaders(tenantId),
  )
}
