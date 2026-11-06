/**
 * RAG 管理页面共享 UI 工具（颜色 / 标签 / 当前主体 / 校验表单）。
 *
 * ⚠️ 所有颜色与标签集中在此，避免散落各处导致治理语义漂移。
 */

import { Progress, Tag, Tooltip, Typography } from 'antd'
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

const { Text } = Typography

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

/* ── 时间单元格（录入 / 审核）────────────────────────────── */

/**
 * ISO 时刻 → 本地可读文本（`YYYY-MM-DD HH:mm:ss`），原始值进 tooltip。
 *
 * <p>⚠️ 三条纪律（与目录页 {@code timeCell} 同一口径，避免同控制台两种时间格式）：
 * <ol>
 *   <li><b>缺失如实呈现</b>：{@code null} = 「该事件尚未发生」，
 *       {@code undefined} = 「后端本次未返回」——都渲染成 `—`，绝不补默认值；</li>
 *   <li><b>本地可读时刻</b>：后端给的是带微秒的 UTC，直接铺在表格里读不出秒级顺序；</li>
 *   <li><b>原始 ISO 可复制</b>：排查跨时区问题时，人需要能拿到原值。</li>
 * </ol>
 */
export function timeText(value: string | null | undefined): string {
  if (value === null || value === undefined) return ''
  const raw = value.trim()
  if (raw === '') return ''
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return raw
  // ⚠️ 逐段补零而不是直接 toString：`toLocaleString('zh-CN')` 在个位数月/日时
  //    输出 `2026-11-1 10:00:00`（宽度不固定），一列时间就会参差不齐，
  //    扫一眼也分不清 11-1 与 11-10 哪个在前。补零后每行等宽、可直接按字面比较。
  //    ⚠️ 这里只改变**显示宽度**，不改时区口径：仍是浏览器本地时刻。
  const pad = (n: number): string => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

/**
 * 时间单元格：本地可读时刻 + 原始 ISO tooltip；无值时显示 `—`。
 *
 * @param value ISO 时刻（null/undefined 均可）
 */
export function TimeCell({ value }: { value: string | null | undefined }): JSX.Element {
  const text = timeText(value)
  if (text === '') {
    // ⚠️ 「没发生」与「没读到」在此统一显示 `—`，但调用方可用 title 说明前者；
    //    这里**不**写「未知」，因为列表里的大部分空白都是「尚未发生」。
    return <Text type="secondary">—</Text>
  }
  return (
    <Tooltip title={String(value)}>
      <Text style={{ fontSize: 12, whiteSpace: 'nowrap' }}>{text}</Text>
    </Tooltip>
  )
}
