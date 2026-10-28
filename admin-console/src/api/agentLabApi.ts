/**
 * AI 实验室 · Agent 执行效果测试 API。
 *
 * ⚠️ **通道现实（必须如实告知）**：`POST /api/runs` 与 `GET /api/runs/{id}` 是
 *    **租户域**端点，租户上下文取自已验签令牌，请求体里**没有也不该有** tenantId。
 *    admin-console 持有的是**平台令牌**（aud=admin-console-api），直接打过去
 *    必然 401 —— 那是受众隔离按设计生效，不是缺陷。
 *    现已接通合法通道：平台侧用
 *    `POST /api/platform/v1/tenants/{id}/execution-ticket` 换一张 **60 秒租户票证**
 *    （见 `executionTicketApi.ts`），本模块的每个租户域调用**强制**要求传入票证，
 *    类型层面就写不出「拿平台令牌打 /runs」这种代码。
 *    票证不可用时（未选身份/取票被拒）由页面明确提示，
 *    而不是伪装成「执行成功但没有结果」。
 *
 * ⚠️ **为什么仍用轮询而不是 SSE**：`GET /api/runs/{id}/events` 是 SSE，
 *    但浏览器 `EventSource` **无法携带 Authorization 头**（只能走 query 参数，
 *    而令牌入 query 会落进访问日志与 Referer）。票证解决的是「能不能拿到租户身份」，
 *    **不解决**「凭据不落 URL」——所以这里依旧轮询，不要把 SSE 说成已支持。
 */

import { request, ApiError } from '@/api/client'
import { ticketAuthHeader, type ExecutionTicket } from '@/api/executionTicketApi'

/**
 * 创建 Run 请求体（调用方视角：一段自由文本）。
 *
 * ⚠️ **`input` 在服务端是 `Map<String,Object>`，不是字符串**
 * （`platform/agent-api/.../run/CreateRunRequest.java` 的组件声明，实测 2026-10-28）：
 * 直接把 `"input":"一段文本"` 发出去得到的是 **400 INVALID_REQUEST**
 * （Jackson 绑定失败 → `ApiExceptionHandler` 归一），
 * 换成 `"input":{"userMessage":"一段文本"}` 得到 **201 QUEUED**。
 * 故本类型刻意**不**把字段叫 `input`：让调用点写不出「input 是字符串」这种误导性代码，
 * 封装线格式是 `createRun` 的责任。
 *
 * ⚠️ 不含 tenantId：租户来自验签令牌，请求体里出现 tenantId 会被
 *    服务端 KNOWN_FIELDS 白名单拒收（越权尝试要留痕，不是静默丢弃）。
 */
export interface CreateRunRequest {
  agentId: string
  /** 用户输入（会被 Prompt 防护做长度/深度/元素数校验） */
  userMessage: string
  timeoutMs?: number | null
  budgetAmount?: number | null
  /** 幂等键：同一键重复提交返回 200 重放，而非新建 */
  idempotencyKey?: string | null
}

export interface CreateRunResponse {
  runId: string
  replayed: boolean
  state: string
  correlationId: string
}

export interface RunStatus {
  runId: string
  state: RunState
  createdAt: string
  completedAt: string | null
  attempt: number
  /** 失败原因码（**不含**错误原文，避免把内部细节带出边界） */
  errorCode: string | null
}

/** `public.run_state` 枚举取值（绝不含 UNKNOWN）。 */
export type RunState =
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCELLED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMED_OUT'

/** 终态：轮询到此即停止。 */
const TERMINAL_STATES: readonly RunState[] = [
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT',
]

export function isTerminal(state: RunState): boolean {
  return TERMINAL_STATES.includes(state)
}

export const RUN_STATE_LABELS: Record<RunState, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  CANCELLED: '已取消',
  SUCCEEDED: '成功',
  FAILED: '失败',
  TIMED_OUT: '超时',
}

export const RUN_STATE_COLORS: Record<RunState, string> = {
  QUEUED: 'default',
  RUNNING: 'processing',
  CANCELLED: 'default',
  SUCCEEDED: 'success',
  FAILED: 'error',
  TIMED_OUT: 'warning',
}

/** POST /api/runs —— 发起一次执行（必须携带租户票证）。 */
export function createRun(
  payload: CreateRunRequest,
  ticket: ExecutionTicket,
): Promise<CreateRunResponse> {
  // ⚠️ 路径不含 `/api`：`request()` 已统一拼 API_BASE，自带前缀会拼成 `/api/api/runs`。
  // ⚠️ `auth=false`：`client.ts` 在合并完 extraHeaders 之后会用平台会话令牌
  //    覆盖 Authorization，用 `auth=true` 等于把票证扔掉 —— 那正是原始 401 的成因。
  const { userMessage, ...rest } = payload
  return request<CreateRunResponse>(
    '/runs',
    {
      method: 'POST',
      body: JSON.stringify({ ...rest, input: { userMessage } }),
    },
    false,
    ticketAuthHeader(ticket),
  )
}

/** GET /api/runs/{runId} —— 查询状态（不含执行输出正文；同样必须带票证）。 */
export function getRunStatus(
  runId: string,
  ticket: ExecutionTicket,
): Promise<RunStatus> {
  return request<RunStatus>(
    `/runs/${encodeURIComponent(runId)}`,
    { method: 'GET' },
    false,
    ticketAuthHeader(ticket),
  )
}

/**
 * 是否为「票证不被租户域接受」的通道问题。
 *
 * ⚠️ 单独建模：401/403 意味着**这张票不行**（过期、受众不符、角色被闸门拒），
 *    重试一万次也不会变对；把它和 5xx 混为一谈会诱导用户反复点「重试」。
 *    401 的一个合法子类是**票证自然过期**（60 秒），调用方应先续票再判失败 ——
 *    见 `ensureExecutionTicket` 的提前续取余量。
 */
export function isTenantChannelBlocked(cause: unknown): boolean {
  return cause instanceof ApiError && (cause.status === 401 || cause.status === 403)
}

/** 429 限流（后端带 Retry-After: 60）。 */
export function isRateLimited(cause: unknown): boolean {
  return cause instanceof ApiError && cause.status === 429
}

/** 400：客户端输入问题（含被 Prompt 防护拒绝）。 */
export function isRejectedInput(cause: unknown): boolean {
  return cause instanceof ApiError && cause.status === 400
}
