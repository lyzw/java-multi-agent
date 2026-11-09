import { ApiError, request } from '@/api/client'

/**
 * 对话回放 / 人工审查队列 API（P1 回放底座 + P2 审查闭环 + P3 评估双轨）。
 *
 * ⚠️ **身份域**：admin-console 持平台令牌（`aud=admin-console-api`），
 *    必须走 `/platform/v1/**` 平台链端点。租户侧 `/api/runs/{id}/transcript`
 *    对平台令牌不可达，且学生端会隐藏 TOOL 消息 —— 审查场景必须用平台链
 *    （后端先过 `platform.resource.read` 闸门，再反查 Run 所属租户）。
 *
 * ⚠️ **路径不含 `/api` 前缀**：`request()` 统一拼 `API_BASE`（见 client.ts），
 *    自带前缀会拼成 `/api/api/...` → 401 → 会话被清并跳登录。
 *
 * ⚠️ **「空」不等于「没有」**：队列响应缺少 `tasks` 数组时直接抛错，
 *    绝不静默兜底成 `[]`。否则「端点返回结构不对 / 被网关改写」
 *    会显示成「暂无待审任务」，审查者据此认定没问题时，异常 Run 根本没进队列 ——
 *    这是本项目反复出现的失真形态（不报错、只是数字悄悄错）。
 */

/** 一次 Run 的对话回放（与后端 TranscriptController.TranscriptResponse 逐字段对齐）。 */
export interface TranscriptResponse {
  runId: string
  traceId: string | null
  state: string
  createdAt: string | null
  completedAt: string | null
  /** null = 未采集（旧 Run 没有用量事实），不是「耗时 0」。 */
  durationMs: number | null
  /** 执行快照（agentId / modelId / 输入摘要等，结构由后端决定）。 */
  snapshot: Record<string, unknown> | null
  messages: TranscriptMessage[]
  steps: TranscriptStep[]
  /** ⚠️ null = 没有用量数据，绝不能显示成「0 token / ¥0」。 */
  tokens: TranscriptTokenSummary | null
}

/** 一条回放消息。`content` 结构按角色不同，见 transcriptPresentation.ts。 */
export interface TranscriptMessage {
  seq: number
  role: string
  stepNumber: number | null
  content: Record<string, unknown>
  toolOperationId: string | null
  modelId: string | null
  inputTokens: number | null
  outputTokens: number | null
  latencyMs: number | null
  createdAt: string
}

export interface TranscriptStep {
  stepNumber: number
  type: string
  durationMs: number | null
}

export interface TranscriptTokenSummary {
  input: number
  output: number
  /** 后端用 BigDecimal 序列化；不同 Jackson 配置下可能是数字或字符串。 */
  cost: number | string | null
}

/** 人工审查任务（与 ReviewTaskPort.ReviewTaskView 逐字段对齐）。 */
export interface ReviewTask {
  taskId: string
  traceId: string
  tenantId: string
  runId: string
  source: string
  priority: string
  status: string
  assignedTo: string | null
  rootCause: string | null
  createdAt: string
}

export type ReviewSource = 'ALERT' | 'ANOMALY' | 'SAMPLE_RANDOM' | 'L1_GAP' | 'MANUAL'
export type ReviewStatus = 'PENDING' | 'IN_REVIEW' | 'RESOLVED' | 'DISMISSED'
export type RootCause = 'MODEL' | 'PROMPT' | 'TOOL' | 'DATA' | 'CONFIG' | 'UNKNOWN'

export const REVIEW_SOURCE_LABELS: Record<string, string> = {
  ALERT: '告警联动',
  ANOMALY: '异常指标',
  SAMPLE_RANDOM: '随机抽样（黄金基线）',
  L1_GAP: 'L1 判分缺口',
  MANUAL: '人工创建',
}

export const REVIEW_PRIORITY_COLORS: Record<string, string> = {
  HIGH: 'red',
  NORMAL: 'blue',
  LOW: 'default',
}

export const REVIEW_STATUS_LABELS: Record<string, string> = {
  PENDING: '待认领',
  IN_REVIEW: '审查中',
  RESOLVED: '已归因关闭',
  DISMISSED: '已驳回',
}

/** rootCause 白名单与后端 CHECK 约束、控制器 valueOf 校验同一清单。 */
export const ROOT_CAUSE_OPTIONS: { value: RootCause; label: string }[] = [
  { value: 'MODEL', label: '模型能力 / 输出' },
  { value: 'PROMPT', label: '提示词 / 指令' },
  { value: 'TOOL', label: '工具调用' },
  { value: 'DATA', label: '知识库 / 数据' },
  { value: 'CONFIG', label: '配置 / 预算' },
  { value: 'UNKNOWN', label: '未定（需继续排查）' },
]

const TRANSCRIPT_BASE = '/platform/v1/runs'
const TASK_BASE = '/platform/v1/review-tasks'

function query(params: Record<string, string | number | undefined>): string {
  const usp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') usp.set(k, String(v))
  }
  const s = usp.toString()
  return s === '' ? '' : `?${s}`
}

/**
 * 平台侧跨租户回放。
 *
 * ⚠️ **显式传 tenantId 优先**：后端反查依赖「该 Run 已被采集过指标」，
 *    尚未采集的 Run 仅凭 runId 定位不到租户 → 404 文案会要求显式 tenantId。
 *    审查队列 / 指标明细都带 tenantId，调用方应一律带上，别把可用性押在采集上。
 */
export function fetchPlatformTranscript(runId: string, tenantId?: string): Promise<TranscriptResponse> {
  return request<TranscriptResponse>(
    `${TRANSCRIPT_BASE}/${encodeURIComponent(runId)}/transcript${query({ tenantId })}`,
  )
}

/**
 * 读取一次工具调用的原始详情（TOOL 消息下钻）。
 *
 * ⚠️ tenantId 必填：tool_operation 是 FORCE RLS 的租户域表，
 * 只有 (tenantId, operationId) 才能命中；跨租户查恒 404。
 */
export function fetchToolOperation(
  operationId: string,
  tenantId: string,
): Promise<{ operationId: string; detail: Record<string, unknown> }> {
  return request(
    `${TRANSCRIPT_BASE}/tool-operations/${encodeURIComponent(operationId)}?tenantId=${encodeURIComponent(tenantId)}`,
  )
}

export interface ReviewTaskQuery {
  /** 精确状态；不传 = 待处理视图（PENDING + IN_REVIEW）。 */
  status?: string
  tenantId?: string
  limit?: number
}

/** 拉取审查队列。响应结构异常时抛错，不退化成空列表。 */
export async function fetchReviewTasks(params: ReviewTaskQuery = {}): Promise<ReviewTask[]> {
  const body = await request<{ tasks?: unknown }>(
    `${TASK_BASE}${query({ status: params.status, tenantId: params.tenantId, limit: params.limit })}`,
  )
  if (!Array.isArray(body?.tasks)) {
    throw new ApiError(
      200,
      '审查队列响应缺少 tasks 数组，不能当作「暂无待审任务」',
      body,
      'MALFORMED_RESPONSE',
    )
  }
  return body.tasks as ReviewTask[]
}

function postVoid(path: string, body: unknown): Promise<void> {
  return request<void>(path, { method: 'POST', body: JSON.stringify(body) })
}

/** 认领：置 IN_REVIEW。要求 platform.resource.manage。 */
export function assignReviewTask(taskId: string, assignee?: string): Promise<void> {
  return postVoid(`${TASK_BASE}/${encodeURIComponent(taskId)}/assign`, { assignee })
}

/** 归因关闭：置 RESOLVED。rootCause 非法值后端 400（控制器先校验枚举）。 */
export function resolveReviewTask(
  taskId: string,
  rootCause: RootCause,
  note?: string,
): Promise<void> {
  return postVoid(`${TASK_BASE}/${encodeURIComponent(taskId)}/resolve`, { rootCause, note })
}

/** 驳回：置 DISMISSED（误报 / 无需跟进）。无物理删除端点。 */
export function dismissReviewTask(taskId: string, note?: string): Promise<void> {
  return postVoid(`${TASK_BASE}/${encodeURIComponent(taskId)}/dismiss`, { note })
}
