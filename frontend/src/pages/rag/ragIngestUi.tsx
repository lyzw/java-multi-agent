/**
 * 摄取状态展示（frontend 主应用版）。
 *
 * <p>与 admin-console 的 `IngestJobTag` / `IngestProgress` 语义一致，
 * 但使用本应用的 `.chip` 体系与显式类名（**不做 `tone-${x}` 模板拼接**，
 * 见 `RagIngestJobList` 头注：CSS 完整性守卫会静态扫描类名）。
 *
 * <p><b>⚠️ 三条渲染纪律</b>：
 * <ol>
 *   <li><b>撤回取消不标红</b>：`sourceRevokedCancel` → 中性「已取消」；</li>
 *   <li><b>失败带原因</b>：`FAILED` 时原因不可丢；</li>
 *   <li><b>进度不造假</b>：解析不出百分比就不显示数字。</li>
 * </ol>
 */

import type { RagIngestJobStatus } from '@/api/types'
import { isIngestJobInFlight } from '@/features/rag/useRagIngestJobs'

/**
 * 状态 → 用户语言。
 *
 * ⚠️ 不暴露 `EMBEDDING` 这类技术词：用户只需要知道"还在跑"。
 */
const LABELS: Record<RagIngestJobStatus, string> = {
  PENDING: '排队中',
  PARSING: '解析中',
  CHUNKING: '切分中',
  EMBEDDING: '向量化中',
  INDEXING: '入库中',
  SUCCEEDED: '处理完成',
  FAILED: '处理失败',
  REJECTED: '已取消',
}

/**
 * 状态 → 类名。
 *
 * ⚠️ 写成完整字面量而非模板拼接：`css.integrity.test.ts` 会静态扫描源码，
 * `chip--${tone}` 会被解析成字面量 `chip--` 从而误报"类名未定义"。
 */
const CLASSES: Record<RagIngestJobStatus, string> = {
  PENDING: 'chip',
  PARSING: 'chip chip--info',
  CHUNKING: 'chip chip--info',
  EMBEDDING: 'chip chip--info',
  INDEXING: 'chip chip--info',
  SUCCEEDED: 'chip chip--settled',
  FAILED: 'chip chip--mark',
  REJECTED: 'chip',
}

/**
 * 从 `stage`（形如 `"PARSING 60"`）解析阶段内百分比。
 *
 * @returns 0~100 的整数；解析不出或越界返回 null（**绝不猜数字**）
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
 * 摄取状态 chip（来源行 / 载体行 / 任务列表共用）。
 */
export function IngestStatusChip({
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
  // 撤回取消优先于失败语义：中性色
  if (sourceRevokedCancel || status === 'REJECTED') {
    return (
      <span className="chip" title={errorDetail ?? '来源已撤回，任务随之下线'}>
        已取消
      </span>
    )
  }
  const percent = parseStagePercent(stage)
  const label = LABELS[status] ?? status
  const text = percent !== null && isIngestJobInFlight(status) ? `${label} ${percent}%` : label
  return (
    <span className={CLASSES[status]} title={status === 'FAILED' ? errorDetail ?? undefined : undefined}>
      {text}
    </span>
  )
}

/**
 * 细进度条（仅进行中渲染）。
 *
 * <p>⚠️ 终态返回 null：给已完成任务画满进度条是纯噪音。
 */
export function IngestProgressBar({
  status,
  stage,
}: {
  status: RagIngestJobStatus
  stage?: string | null
}): JSX.Element | null {
  if (!isIngestJobInFlight(status)) return null
  const percent = parseStagePercent(stage)
  return (
    <span
      className="rag-ingest-bar"
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent ?? undefined}
      aria-label="处理进度"
    >
      <span
        className="rag-ingest-bar__fill"
        // ⚠️ 无百分比时给一个短条而非 0%：0% 会被读成"没动静"
        style={{ width: percent === null ? '20%' : `${percent}%` }}
      />
    </span>
  )
}
