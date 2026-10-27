import { request } from '@/api/client'

/**
 * Agent 效果指标 API（P7）。
 *
 * ⚠️ **头号纪律：`null` = 未采集，绝不等于 0。**
 *    后端对尚无采集来源的指标一律返回 `null`；前端若把 `null` 渲染成 `0`，
 *    「没测过幻觉」就会显示成「幻觉率 0%」——这是本项目反复出现的失真来源。
 *    所有呈现必须走 `renderMetric()`（见 AgentMetricsPage）。
 *
 * ⚠️ **本组端点只做观测**：不返回任何 Trace 的对话内容或工具原文，
 *    后端也刻意不提供这类字段。
 */

/** 归一化终止原因（9 值，后端枚举名）。 */
export type TerminationReason =
  | 'COMPLETED'
  | 'USER_CANCELLED'
  | 'MODEL_ERROR'
  | 'TOOL_TIMEOUT'
  | 'TOOL_5XX'
  | 'MAX_ROUNDS'
  | 'SAFETY_BLOCK'
  | 'TOKEN_BUDGET'
  | 'OTHER'

/** 六类总览。所有可空字段为 `null` 时表示「未采集」。 */
export interface AgentMetricOverview {
  traceCount: number
  succeededCount: number
  /** ⚠️ 链路成功率（框架没崩）**≠ 任务成功率** */
  linkSuccessRate: number | null
  avgDurationMs: number | null
  inputTokens: number | null
  outputTokens: number | null
  costAmount: number | null
  avgCostPerTrace: number | null
  oversizedTokenRate: number | null
  /** P7-2 真实口径：来自 run_step 工具步骤计数；未埋点的旧 Run 为 null（未采集） */
  avgToolRounds: number | null
  maxRoundsReachedRate: number | null
  invalidCallRate: number | null
  loopSuspectedTraces: number
  // ── L1 自动校验 ──
  autoCheckedCount: number | null
  autoVerifiedCount: number | null
  autoVerifiedRate: number | null
  /** ⚠️ 覆盖率是指标的组成部分，不是补充信息 */
  autoCheckCoverage: number | null
  // ── L2 抽样标注 ──
  sampledCount: number | null
  sampledSuccessRate: number | null
  falseSuccessRate: number | null
  hallucinationRate: number | null
  instructionFollowRate: number | null
  // ── L3 用户反馈（有噪）──
  feedbackUpCount: number | null
  feedbackDownCount: number | null
  thumbUpRate: number | null
  abandonRate: number | null
  // ── 风险安全 ──
  safetyBlockedTraces: number
  unauthorizedTraces: number
  sensitiveOutputTraces: number
  /** 数据时效：物化视图刷新时间，必须展示（刷新期间读到的是上一版） */
  refreshedAt: string
}

export interface ReasonCount {
  reason: TerminationReason
  count: number
}

export interface LatencyPercentiles {
  p50: number | null
  p95: number | null
  p99: number | null
}

/** ⚠️ 工具维度不给 P95 —— 分位数无法从计数与求和还原。 */
export interface ToolMetric {
  toolName: string
  callCount: number
  failedCount: number
  failRate: number | null
  avgDurationMs: number | null
  maxDurationMs: number | null
}

export type AnomalyKind = 'OVERSIZED_TOKEN' | 'LOOP_SUSPECTED' | 'MAX_ROUNDS'

export interface AnomalyTrace {
  traceId: string
  kind: AnomalyKind
  totalTokens: number | null
  toolRounds: number | null
  loopCount: number | null
  startedAt: string
}

function query(params: Record<string, string | undefined>): string {
  const usp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') {
      usp.set(k, v)
    }
  }
  const s = usp.toString()
  return s === '' ? '' : `?${s}`
}

export function fetchAgentMetricOverview(hours: number, tenantId?: string): Promise<AgentMetricOverview> {
  return request<AgentMetricOverview>(
    `/api/platform/v1/metrics/agent/overview${query({ hours: String(hours), tenantId })}`,
  )
}

export function fetchTerminationDistribution(hours: number, tenantId?: string): Promise<ReasonCount[]> {
  return request<ReasonCount[]>(
    `/api/platform/v1/metrics/agent/terminations${query({ hours: String(hours), tenantId })}`,
  )
}

export function fetchLatency(hours: number, tenantId?: string): Promise<LatencyPercentiles> {
  return request<LatencyPercentiles>(
    `/api/platform/v1/metrics/agent/latency${query({ hours: String(hours), tenantId })}`,
  )
}

export function fetchToolBreakdown(hours: number, tenantId?: string): Promise<ToolMetric[]> {
  return request<ToolMetric[]>(
    `/api/platform/v1/metrics/agent/tools${query({ hours: String(hours), tenantId })}`,
  )
}

export function fetchAnomalies(hours: number, limit = 20): Promise<AnomalyTrace[]> {
  return request<AnomalyTrace[]>(
    `/api/platform/v1/metrics/agent/anomalies${query({ hours: String(hours), limit: String(limit) })}`,
  )
}

/** 终止原因中文标签 + 排序（与后端 9 值一一对应）。 */
export const TERMINATION_REASON_LABELS: Record<TerminationReason, string> = {
  COMPLETED: '正常完成',
  USER_CANCELLED: '用户取消',
  MODEL_ERROR: '模型报错',
  TOOL_TIMEOUT: '工具超时',
  TOOL_5XX: '工具返回 5xx',
  MAX_ROUNDS: '最大轮次终止',
  SAFETY_BLOCK: '安全拦截',
  TOKEN_BUDGET: 'token 上限熔断',
  OTHER: '其他（未细分）',
}

/** 终止原因配色：越靠右越需要人看。 */
export const TERMINATION_REASON_COLORS: Record<TerminationReason, string> = {
  COMPLETED: 'green',
  USER_CANCELLED: 'default',
  MODEL_ERROR: 'red',
  TOOL_TIMEOUT: 'orange',
  TOOL_5XX: 'volcano',
  MAX_ROUNDS: 'gold',
  SAFETY_BLOCK: 'purple',
  TOKEN_BUDGET: 'magenta',
  OTHER: 'default',
}

export const ANOMALY_KIND_LABELS: Record<AnomalyKind, string> = {
  OVERSIZED_TOKEN: '超大 token（疑似死循环）',
  LOOP_SUSPECTED: '循环调用',
  MAX_ROUNDS: '触达最大轮次',
}

// ════════════════════════════════════════════════════════════════════
// 录入（回填）—— 标注 / 校验回执 / 风险事件 / 用户反馈
//
// ⚠️ 这些端点要求 `platform.resource.manage`（平台权限），只能在
//    admin-console（平台管理员）侧调用；租户侧反馈请走 tenant 端 `/api/metrics/feedback`
//   端点（tenantId 由租户令牌派生，不接受请求体里的 tenantId，防伪造）。
// ════════════════════════════════════════════════════════════════════

/** L2 抽样标注结论（后端枚举名，大写）。 */
export type EvalVerdict = 'SUCCESS' | 'PARTIAL' | 'FAIL'

/** L3 用户反馈类型（后端枚举名，大写）。 */
export type FeedbackKind = 'THUMB_UP' | 'THUMB_DOWN' | 'RETRY' | 'ABANDON'

/** 风险事件类型（后端枚举名，大写）。 */
export type RiskEventType = 'SAFETY_BLOCK' | 'UNAUTHORIZED_CALL' | 'SENSITIVE_OUTPUT'

/** L2 抽样标注录入请求。 */
export interface EvalSampleRequest {
  traceId: string
  tenantId: string
  verdict: EvalVerdict
  /** 是否出现幻觉（L2 黄金基线核心判据）。 */
  hallucination?: boolean
  /** 是否遵从指令。 */
  instructionFollowed?: boolean
  note?: string
}

/** L1 自动终态校验回执录入请求。 */
export interface TaskOutcomeRequest {
  traceId: string
  tenantId: string
  /** 外部校验器代码（标识是哪一道校验）。 */
  checkerCode: string
  externalRef?: string
  verified: boolean
  evidenceDigest?: string
}

/** 风险事件录入请求。⚠️ 只有摘要，绝不带原文（合规红线）。 */
export interface RiskEventRequest {
  traceId: string
  tenantId: string
  eventType: RiskEventType
  detailDigest?: string
}

/** 用户反馈录入请求。 */
export interface FeedbackRequest {
  traceId: string
  tenantId: string
  kind: FeedbackKind
}

/** 录入响应：空 body（204/200），以 void 接收。 */
function postJson<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, { method: 'POST', body: JSON.stringify(body) })
}

export function recordEvalSample(payload: EvalSampleRequest): Promise<void> {
  return postJson<void>('/api/platform/v1/metrics/agent/eval-samples', payload)
}

export function recordTaskOutcome(payload: TaskOutcomeRequest): Promise<void> {
  return postJson<void>('/api/platform/v1/metrics/agent/task-outcomes', payload)
}

export function recordRiskEvent(payload: RiskEventRequest): Promise<void> {
  return postJson<void>('/api/platform/v1/metrics/agent/risk-events', payload)
}

export function recordFeedback(payload: FeedbackRequest): Promise<void> {
  return postJson<void>('/api/platform/v1/metrics/agent/feedback', payload)
}

/** L2 抽样标注结论中文标签。 */
export const EVAL_VERDICT_LABELS: Record<EvalVerdict, string> = {
  SUCCESS: '成功',
  PARTIAL: '部分成功',
  FAIL: '失败',
}

/** L3 用户反馈类型中文标签。 */
export const FEEDBACK_KIND_LABELS: Record<FeedbackKind, string> = {
  THUMB_UP: '👍 点赞',
  THUMB_DOWN: '👎 差评',
  RETRY: '重试',
  ABANDON: '放弃',
}

/** 风险事件类型中文标签。 */
export const RISK_EVENT_TYPE_LABELS: Record<RiskEventType, string> = {
  SAFETY_BLOCK: '安全拦截（编造出处等）',
  UNAUTHORIZED_CALL: '越权调用尝试',
  SENSITIVE_OUTPUT: '敏感信息输出命中',
}
