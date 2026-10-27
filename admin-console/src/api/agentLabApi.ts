/**
 * AI 实验室 · Agent 执行效果测试 API。
 *
 * ⚠️ **通道现实（必须如实告知）**：`POST /api/runs` 与 `GET /api/runs/{id}` 是
 *    **租户域**端点，租户上下文取自已验签令牌，请求体里**没有也不该有** tenantId。
 *    admin-console 持有的是**平台令牌**（aud=admin-console-api），
 *    对租户域端点可能不可达（401/403）。故本模块刻意**不吞掉**这类响应：
 *    由页面明确提示「需租户级令牌通道」，而不是伪装成「执行成功但没有结果」。
 *
 * ⚠️ **为什么用轮询而不是 SSE**：`GET /api/runs/{id}/events` 是 SSE，
 *    但浏览器 `EventSource` **无法携带 Authorization 头**（只能走 query 参数，
 *    而令牌入 query 会落进访问日志与 Referer）。在后端提供一次性票证之前，
 *    轮询是唯一不牺牲凭据安全性的做法——这个取舍写在代码里，不藏在文档里。
 */

import { request, ApiError } from '@/api/client'

/** 创建 Run 请求体（⚠️ 不含 tenantId：租户来自验签令牌）。 */
export interface CreateRunRequest {
  agentId: string
  /** 用户输入（会被 Prompt 防护做长度/深度/元素数校验） */
  input: string
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

/** POST /api/runs —— 发起一次执行。 */
export function createRun(payload: CreateRunRequest): Promise<CreateRunResponse> {
  return request<CreateRunResponse>('/api/runs', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** GET /api/runs/{runId} —— 查询状态（不含执行输出正文）。 */
export function getRunStatus(runId: string): Promise<RunStatus> {
  return request<RunStatus>(`/api/runs/${encodeURIComponent(runId)}`)
}

/**
 * 是否为「平台令牌打不通租户域端点」的通道问题。
 *
 * ⚠️ 单独建模：401/403 意味着**通道不对**，重试一万次也不会变对；
 *    把它和 5xx 混为一谈会诱导用户反复点「重试」。
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
