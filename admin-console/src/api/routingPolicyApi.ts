/**
 * 路由策略 dry-run API（10 号设计 §7 债 5 · 管理台 A7-1b）。
 *
 * ⚠️ **本模块刻意不含任何谓词文法、求值器或守卫实现**：
 *    判定只在后端 `agent-routing` 存在一份。前端若复刻一份，
 *    漂移的症状是「界面显示会命中 A，运行期实际去了 B」——
 *    而这份漂移只会以「路由经常走兜底」的形态出现在线上，不报错。
 *    本模块因此只做三件事：拼请求、原样透传 JSON 文本、把响应映射成表格数据。
 *
 * ⚠️ **通道现实（与 AI 实验室同口径，必须如实告知）**：
 *    `/api/cap/config/**` 是**租户域**端点，租户来自已验签令牌；
 *    admin-console 持**平台令牌**（`aud=admin-console-api`），对它可能 401/403。
 *    此时页面明示「需租户级令牌通道」，**不**把「没跑成」伪装成「跑通且没问题」。
 */

import { request, ApiError } from '@/api/client'
import type { PublishRoutingPolicySpec } from '@/api/types'

/** dry-run 请求体（DryRunRequest）。 */
export interface RoutingDryRunRequest {
  /** 策略草稿：与发布申请里 `routingPolicies[]` 的条目<b>同一个形状</b> */
  policy: PublishRoutingPolicySpec
  /**
   * 探针数组的 JSON <b>原文</b>（`[{label,input,modelCandidate,ctx}]`）。
   * 缺省 = 只要守卫报告。⚠️ 不在前端解析或重序列化：解析规则只有一份。
   */
  probes?: string | null
}

/** 一条守卫结论（FindingResponse）。 */
export interface DryRunFinding {
  /** 守卫编号（V3~V10） */
  code: string
  message: string
  /** true = 只提示不阻止发布（仅 V7）；其余一律阻止 */
  warning: boolean
}

/** 求值轨迹的一条（TraceResponse）。 */
export interface DryRunTrace {
  ordinal: number
  ruleId: string
  /** `MATCH` / `NO_MATCH` / `ERROR_SKIPPED` / `ERROR_REJECTED` */
  outcome: string
  error: string | null
}

/** 一条探针的试跑结论（ProbeResponse）。 */
export interface DryRunProbeResult {
  label: string
  /**
   * <b>真正进入求值视野</b>的字段。
   * 词汇表外的字段（含写错的字段名）在这里看不到 ——
   * 这是「字段名打错不报错」在界面上唯一的可见证据。
   */
  projectedInput: Record<string, unknown>
  decided: boolean
  mode: string | null
  /** 稳定码（§6.1 九个之一）；`decided=false` 时为 null */
  resolution: string | null
  finalTarget: string | null
  matchedRuleId: string | null
  candidateFromModel: string | null
  conflict: boolean
  error: string | null
  trace: DryRunTrace[]
  issueWhere: string | null
  issueMessage: string | null
}

/** dry-run 结论（DryRunResponse）。 */
export interface RoutingDryRunResult {
  /** 策略文本是否解析通过 */
  parsed: boolean
  errorWhere: string | null
  errorMessage: string | null
  /** 这份草稿<b>现在</b>提交会不会被发布期守卫放行 */
  publishable: boolean
  violations: DryRunFinding[]
  warnings: DryRunFinding[]
  probes: DryRunProbeResult[]
  /** 本次回放所用的 UTC 日：引用 `ctx.now.utcDate` 的策略结论取决于它 */
  replayDate: string
}

/** §6.1 稳定码 → 人话（只翻码值，不改判据）。 */
export const RESOLUTION_LABELS: Record<string, string> = {
  RULE_MATCH: '规则命中',
  SEMANTIC_MATCH: '语义命中',
  DEFAULT_TARGET: '走兜底目标',
  NO_MATCH_REJECTED: '无命中且被拒绝',
  EVAL_ERROR_SKIPPED: '规则求值异常已跳过',
  EVAL_ERROR_REJECTED: '规则求值异常中止路由',
  POLICY_UNAVAILABLE: '策略不可得（fail-closed）',
  TARGET_NOT_ALLOWED: '拓扑过了、策略没收',
  ROUTING_CONFLICT: '规则与候选冲突（已按 onConflict 裁决）',
}

/** 求值轨迹结论 → 人话。 */
export const TRACE_OUTCOME_LABELS: Record<string, string> = {
  MATCH: '命中',
  NO_MATCH: '未命中',
  ERROR_SKIPPED: '异常跳过',
  ERROR_REJECTED: '异常中止',
}

/** 失败码（委派没有产生可执行目标）：与后端 `Resolution.isFailure()` 同集合。 */
const FAILURE_RESOLUTIONS: readonly string[] = [
  'NO_MATCH_REJECTED',
  'EVAL_ERROR_REJECTED',
  'POLICY_UNAVAILABLE',
  'TARGET_NOT_ALLOWED',
]

export function isFailureResolution(resolution: string | null): boolean {
  return resolution !== null && FAILURE_RESOLUTIONS.includes(resolution)
}

/** POST /api/cap/config/routing-policies/dry-run —— 试跑一份草稿（不落库、不产生申请单）。 */
export function dryRunRoutingPolicy(
  payload: RoutingDryRunRequest,
): Promise<RoutingDryRunResult> {
  // ⚠️ 路径不含 `/api`：`request()` 已统一拼 API_BASE。
  return request<RoutingDryRunResult>('/cap/config/routing-policies/dry-run', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** 「平台令牌打不通租户域端点」：重试一万次也不会变对，故与 5xx 分开建模。 */
export function isTenantChannelBlocked(cause: unknown): boolean {
  return cause instanceof ApiError && (cause.status === 401 || cause.status === 403)
}

/** 429 限流（dry-run 与治理查询同桶）。 */
export function isRateLimited(cause: unknown): boolean {
  return cause instanceof ApiError && cause.status === 429
}

/** 400：请求本身不合法（行级校验没过），与「策略文本有错」是两件事。 */
export function isRejectedInput(cause: unknown): boolean {
  return cause instanceof ApiError && cause.status === 400
}
