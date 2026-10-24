import type { DenySeverity, MonitoringStatus } from '@/api/types'

/** 严重级别 → 中文标签（后端 DenySeverity 口径） */
export const SEVERITY_LABELS: Record<DenySeverity, string> = {
  CRITICAL: '严重',
  WARNING: '警告',
  INFO: '提示',
}

/** 严重级别 → antd Tag 颜色（与 P3 riskTag 配色风格一致） */
export const SEVERITY_COLORS: Record<DenySeverity, string> = {
  CRITICAL: 'red',
  WARNING: 'orange',
  INFO: 'default',
}

/** 健康状态 → 中文标签 */
export const STATUS_LABELS: Record<MonitoringStatus, string> = {
  HEALTHY: '正常',
  DEGRADED: '降级',
  UNAVAILABLE: '不可用',
}

/** 健康状态 → antd Tag 颜色 */
export const STATUS_COLORS: Record<MonitoringStatus, string> = {
  HEALTHY: 'green',
  DEGRADED: 'orange',
  UNAVAILABLE: 'red',
}

/** 监控窗口选项（小时） */
export const WINDOW_OPTIONS: Array<{ value: number; label: string }> = [
  { value: 1, label: '最近 1 小时' },
  { value: 24, label: '最近 24 小时' },
  { value: 72, label: '最近 3 天' },
  { value: 168, label: '最近 7 天' },
]

/**
 * 原因码 → 中文说明。
 *
 * ⚠️ 覆盖不到的码直接展示原始码文本：原因码是稳定契约（§5.7），
 * 新增码在文案补齐前仍应可读 —— 展示 "SOME_NEW_CODE" 比展示空白更有用。
 */
const REASON_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  AUTHZ_STALE: '授权版本陈旧',
  AUTHZ_ROLE_MISSING: '角色不足',
  AUTHZ_UNAVAILABLE: '版本服务不可用',
  SKILL_NOT_ENABLED: 'Skill 未启用',
  TOOL_NOT_DECLARED: 'Tool 未声明',
  RISK_EXCEEDS_CEILING: '风险超限',
  RISK_LEVEL_UNKNOWN: '风险等级未知',
  PARAM_POLICY_FAIL: '参数策略失败',
  CONFIG_FINGERPRINT_STALE: '闭包指纹失效',
  MCP_NOT_BOUND: 'MCP Server 未声明',
  MCP_OP_NOT_ALLOWED: 'MCP 操作越出允许集',
  MCP_TOOL_DRIFTED: 'MCP 清单漂移',
  RESOURCE_VERSION_MISMATCH: '资源版本不匹配',
  RESOURCE_UNKNOWN: '对象不存在',
  PARAM_SCHEMA_FAIL: '参数结构校验失败',
  APPROVAL_NOT_FOUND: '审批不存在',
  APPROVAL_EXPIRED: '审批已过期',
  APPROVAL_SCOPE_MISMATCH: '审批范围不匹配',
  APPROVAL_RESERVED_BY_OTHER: '审批已被他人预占',
  APPROVAL_REPLAY_REJECTED: '审批重放被拒',
  BUDGET_EXCEEDED: '预算超限',
  IDEMPOTENCY_CONFLICT: '幂等键冲突',
  EXEC_ADMISSION_STALE: '准入版本/指纹失效',
  EXEC_UNKNOWN: '副作用结果不确定（需人工对账）',
  IDEMPOTENCY_UNAVAILABLE: '幂等存储不可用',
  IDEMPOTENCY_IN_PROGRESS: '同一操作正在执行中',
  EXECUTION_PERSISTENCE_FAILED: '副作用前持久化失败',
  RUN_CANCELLED: 'Run 已取消',
  TOOL_EXECUTION_FAILED: '工具明确失败',
}

/** 原因码中文说明（未覆盖时回落到原始码） */
export function reasonLabel(code: string): string {
  return REASON_LABELS[code] ?? code
}

/** ISO 时刻 → 本地可读时间（空值回落到破折号） */
export function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString('zh-CN', { hour12: false })
}
