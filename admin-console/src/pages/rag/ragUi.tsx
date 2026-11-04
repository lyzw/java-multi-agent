/**
 * RAG 管理页面共享 UI 工具（颜色 / 标签 / 当前主体 / 校验表单）。
 *
 * ⚠️ 所有颜色与标签集中在此，避免散落各处导致治理语义漂移。
 */

import { Progress, Tag, Tooltip } from 'antd'
import { loadSession } from '@/auth/tokenStorage'
import type {
  RagIngestJobStatus,
  RagVerificationStatus,
  RagTrustTier,
  RagLicenseStatus,
  RagVerificationMethod,
} from '@/api/ragTypes'
import {
  RAG_VERIFICATION_LABELS,
  RAG_TRUST_TIER_LABELS,
  RAG_LICENSE_LABELS,
  RAG_INGEST_JOB_STATUS_LABELS,
  RAG_INGEST_JOB_STATUS_COLORS,
  isRagIngestJobInFlight,
} from '@/api/ragTypes'

/** 校验状态配色（与后端状态机语义一致）。 */
export const RAG_VERIFICATION_COLORS: Record<RagVerificationStatus, string> = {
  PENDING: 'default',
  IN_REVIEW: 'blue',
  VERIFIED: 'green',
  REJECTED: 'volcano',
  REVOKED: 'red',
}

/** 权威等级配色。 */
export const RAG_TRUST_TIER_COLORS: Record<RagTrustTier, string> = {
  OFFICIAL_PUBLISHER: 'green',
  OFFICIAL_TUTORIAL: 'cyan',
  PEER_REVIEWED_PAPER: 'blue',
  PREPRINT: 'gold',
  UNVERIFIED: 'default',
}

/** 版权状态配色。 */
export const RAG_LICENSE_COLORS: Record<RagLicenseStatus, string> = {
  LICENSED: 'green',
  FAIR_USE_QUOTATION: 'blue',
  PENDING: 'gold',
  RESTRICTED: 'red',
}

/** 校验手段配色（仅作轻区分）。 */
export const RAG_METHOD_COLORS: Record<RagVerificationMethod, string> = {
  NONE: 'default',
  ISBN: 'blue',
  DOI: 'blue',
  MANUAL_REVIEW: 'purple',
  OFFICIAL_HASH: 'geekblue',
}

/** 渲染校验状态标签。 */
export function VerificationTag({ status }: { status: RagVerificationStatus }): JSX.Element {
  return <Tag color={RAG_VERIFICATION_COLORS[status]}>{RAG_VERIFICATION_LABELS[status]}</Tag>
}

/** 渲染权威等级标签。 */
export function TrustTierTag({ tier }: { tier: RagTrustTier }): JSX.Element {
  return <Tag color={RAG_TRUST_TIER_COLORS[tier]}>{RAG_TRUST_TIER_LABELS[tier]}</Tag>
}

/** 渲染版权状态标签。 */
export function LicenseTag({ status }: { status: RagLicenseStatus }): JSX.Element {
  return <Tag color={RAG_LICENSE_COLORS[status]}>{RAG_LICENSE_LABELS[status]}</Tag>
}

/**
 * 合法流转目标的动词标签（用于按钮文案）。
 *
 * ⚠️ 仅覆盖 `allowedVerificationTargets` 可能返回的目标，
 *    与后端状态机白名单逐条对齐（RAG-D07 §2.4 T-1～T-12）。
 */
export const VERIFICATION_TARGET_VERB: Record<RagVerificationStatus, string> = {
  IN_REVIEW: '提交审核',
  VERIFIED: '通过校验',
  REJECTED: '拒绝',
  REVOKED: '撤回',
  PENDING: '退回待校验',
}

/** 当前登录平台主体的 UUID（校验留痕用）。缺失回落空串，由表单校验拦截。 */
export function currentPrincipalId(): string {
  return loadSession()?.principalId ?? ''
}

/* ── 摄取进度展示（交互文档 §4.2 / §4.5）────────────────── */

/**
 * 解析 `stage` 字段里的阶段内百分比。
 *
 * 后端格式形如 `"PARSING 60"` / `"EMBEDDING 60"`。
 *
 * ⚠️ 解析不出来就返回 `null`，**绝不猜一个数字**：
 *    进度条显示假百分比比不显示更糟——用户会据此判断"是不是卡住了"。
 */
export function parseStagePercent(stage: string | null | undefined): number | null {
  if (!stage) return null
  const match = /(\d{1,3})\s*$/.exec(stage.trim())
  if (!match) return null
  const value = Number(match[1])
  if (!Number.isFinite(value) || value < 0 || value > 100) return null
  return value
}

/**
 * 摄取任务状态标签。
 *
 * <p>⚠️ 三条渲染纪律：
 * <ol>
 *   <li><b>撤回取消不标红</b>：`sourceRevokedCancel` 为真时显示为中性色「已取消」——
 *       那是法务撤回的正常结果，不是文件出错，标红会误导采集员。</li>
 *   <li><b>失败必须带原因</b>：`FAILED` 时把 `errorDetail` 放进 tooltip，
 *       否则用户只知道"失败了"，不知道下一步该做什么。</li>
 *   <li><b>进度与状态同显</b>：有百分比时显示「解析中 60%」，而不是只给一个状态词。</li>
 * </ol>
 */
export function IngestJobTag({
  status,
  stage,
  errorDetail,
  sourceRevokedCancel,
}: {
  status: RagIngestJobStatus
  stage?: string | null
  errorDetail?: string | null
  sourceRevokedCancel?: boolean
}): JSX.Element {
  // 撤回取消优先于 FAILED 语义：显示中性「已取消」
  if (sourceRevokedCancel || status === 'REJECTED') {
    const cancelled = <Tag>已取消</Tag>
    return errorDetail ? <Tooltip title={errorDetail}>{cancelled}</Tooltip> : cancelled
  }
  const percent = parseStagePercent(stage)
  const label = RAG_INGEST_JOB_STATUS_LABELS[status] ?? status
  const text = percent !== null && isRagIngestJobInFlight(status) ? `${label} ${percent}%` : label
  const tag = <Tag color={RAG_INGEST_JOB_STATUS_COLORS[status]}>{text}</Tag>
  // ⚠️ 失败原因必须可达：只显示"处理失败"等于让用户自己猜下一步
  return status === 'FAILED' && errorDetail ? (
    <Tooltip title={errorDetail}>{tag}</Tooltip>
  ) : (
    tag
  )
}

/**
 * 单行进度条（任务卡 / 载体行使用）。
 *
 * <p>⚠️ 只在**进行中**时渲染进度条；终态由 {@link IngestJobTag} 表达，
 *    给已完成的任务画一条满进度条是噪音。
 */
export function IngestProgress({
  status,
  stage,
  width = 120,
}: {
  status: RagIngestJobStatus
  stage?: string | null
  width?: number
}): JSX.Element | null {
  if (!isRagIngestJobInFlight(status)) return null
  const percent = parseStagePercent(stage)
  return (
    <Progress
      percent={percent ?? 0}
      size="small"
      status="active"
      // ⚠️ 无百分比时显示「进行中」而非 0%：0% 会被读成"没动静"
      format={() => (percent === null ? '进行中' : `${percent}%`)}
      style={{ width, marginBottom: 0 }}
    />
  )
}
