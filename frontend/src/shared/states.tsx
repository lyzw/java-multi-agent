/**
 * 数据区块的五态（FE-D3 §4）
 *
 * 每个区块必须实现：默认 / 加载 / 空 / 错误 / 未开放。
 *
 * ⛔ 五态**不可互相混淆**：
 *   空     = 查了，确实没有 → 邀请行动
 *   错误   = 查失败了       → 说明原因 + 下一步
 *   未开放 = 根本没接口可查 → 标注缺口
 *   加载   = 正在查         → 呼吸纸面
 *
 * 混为一谈的后果：用户以为系统坏了，或以为有数据却看不到。
 */

import { Link } from 'react-router-dom'

/* ── 面包屑：深层页位置指示（F3）─────────────────────── */

/**
 * ⚠️ 深层页（进度 / 结果 / 画像 / 详情）没有返回上级的路径指示，
 *     用户从结果页回不到列表。面包屑补这一环。
 * 仅用线与小字，不引入新视觉概念；最后一项为当前页（不渲染链接）。
 */
export interface Crumb {
  label: string
  /** 提供则渲染为链接；最后一项不传 to，表示当前页 */
  to?: string
}

export function Breadcrumbs({ items }: { items: Crumb[] }): JSX.Element {
  return (
    <nav className="breadcrumbs" aria-label="位置">
      <ol>
        {items.map((crumb, index) => {
          const isLast = index === items.length - 1
          return (
            <li key={`${crumb.label}-${index}`}>
              {crumb.to !== undefined && !isLast ? (
                <Link to={crumb.to}>{crumb.label}</Link>
              ) : (
                <span aria-current={isLast ? 'page' : undefined}>{crumb.label}</span>
              )}
              {isLast ? null : (
                <span className="breadcrumbs__sep" aria-hidden="true">
                  /
                </span>
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}

/* ── 列表底栏：加载更多 + 计数（F6 抽取，去重 PageFooter）── */

/**
 * ⚠️ 原 `PageFooter` 在 GovernancePages 与 ConfigPages 各抄了一份完全相同的实现。
 *     抽到此处作为单一真相源：只有后端给了 nextCursor 才显示「加载更多」，
 *     否则点它会发一个必然为空的请求，白白消耗治理桶配额。
 */
export function ListFooter({
  count,
  hasMore,
  loadingMore,
  onLoadMore,
}: {
  count: number
  hasMore: boolean
  loadingMore: boolean
  onLoadMore: () => void
}): JSX.Element {
  return (
    <div className="status-line" style={{ marginTop: 'var(--s-3)' }}>
      {hasMore ? (
        <button type="button" onClick={onLoadMore} disabled={loadingMore}>
          {loadingMore ? '正在加载…' : '加载更多'}
        </button>
      ) : null}
      <span className="muted">
        {hasMore ? `已显示 ${count} 条` : `已显示全部 ${count} 条`}
      </span>
    </div>
  )
}

/* ── 待后端能力提示（F7：统一「尚未接入后端」话术）────── */

/**
 * ⚠️ 全站多处「尚未接入 / 待后端能力」提示此前各写一套。
 *     统一为同一声音与阻断编号，便于用户追溯（也避免文案漂移）。
 */
export function CapabilityNote({
  feature,
  blockedBy,
  reason,
}: {
  /** 功能名，如「生成回答」 */
  feature: string
  /** 后端缺口编号，如 B5 —— 可追溯 */
  blockedBy?: string
  /** 人话原因 */
  reason: string
}): JSX.Element {
  return (
    <div className="notice" role="status">
      <p style={{ margin: 0 }}>
        {feature}能力尚未接入后端。{reason}
        {blockedBy !== undefined ? (
          <>
            （阻塞编号 <code>{blockedBy}</code>）
          </>
        ) : null}
      </p>
    </div>
  )
}

/* ── 加载：呼吸纸面 ──────────────────────────────────── */

/**
 * ⛔ 刻意不用灰色微光条（AI 生成默认）。
 * 改为整块纸面做极缓慢明度呼吸 —— 像纸在等墨迹干。
 */
export function Loading({ label = '正在读取' }: { label?: string }): JSX.Element {
  return (
    <div className="loading" role="status" aria-live="polite">
      <span className="loading__label">{label}</span>
    </div>
  )
}

/** 加载行：表格内的占位 */
export function LoadingRows({
  columns,
  rows = 5,
  label,
}: {
  columns: string[]
  rows?: number
  label?: string
}): JSX.Element {
  return (
    <div className="loading-rows" role="status" aria-live="polite">
      <span className="sr-only">{label ?? '正在读取'}</span>
      <table>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c} scope="col">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody aria-hidden="true">
          {Array.from({ length: rows }, (_, i) => (
            <tr key={i}>
              {columns.map((c) => (
                <td key={c}>{''}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/* ── 空：邀请行动 ────────────────────────────────────── */

export function Empty({
  title,
  hint,
  action,
}: {
  title: string
  /** 下一步做什么 —— ⛔ 不写「暂无数据」这种死话 */
  hint?: string
  action?: React.ReactNode
}): JSX.Element {
  return (
    <div className="empty">
      <p className="empty__title">{title}</p>
      {hint !== undefined ? <p className="muted">{hint}</p> : null}
      {action !== undefined ? <div className="empty__action">{action}</div> : null}
    </div>
  )
}

/* ── 错误：说明原因 + 可执行的下一步 ────────────────── */

export interface ErrorStateProps {
  /** 具体原因，不是「出错了」 */
  reason: string
  /** 用户下一步该做什么 */
  next?: string
  onRetry?: () => void
  retryLabel?: string
}

/**
 * ⛔ 不道歉、不写「请求失败」。
 * 错误页的价值在于**告诉用户怎么办**。
 */
export function ErrorState({
  reason,
  next,
  onRetry,
  retryLabel = '重试',
}: ErrorStateProps): JSX.Element {
  return (
    <div className="marked" role="alert">
      <p className="marked__label">没能完成</p>
      <p style={{ marginBottom: next !== undefined ? 4 : 0 }}>{reason}</p>
      {next !== undefined ? (
        <p className="muted" style={{ marginBottom: onRetry !== undefined ? 12 : 0 }}>
          {next}
        </p>
      ) : null}
      {onRetry !== undefined ? (
        <button type="button" className="btn btn--quiet" onClick={onRetry}>
          {retryLabel}
        </button>
      ) : null}
    </div>
  )
}

/* ── 未开放：与「空」区分 ────────────────────────────── */

export interface UnavailableProps {
  /** 功能名，如「归因结果」 */
  feature: string
  /** 后端缺口编号，如 G5 —— 可追溯 */
  blockedBy: string
  /** 人话原因 */
  reason: string
  /** 承接阶段 */
  eta?: string
  children?: React.ReactNode
}

/**
 * ⚠️ 与 Empty 的区别：
 *   Empty     = 接口正常，确实没数据 → 邀请行动
 *   Unavailable = 接口不存在         → 标注缺口，说明这是开发中的功能
 *
 * 混淆后果：用户会反复刷新等一个永远不会来的结果。
 */
export function Unavailable({
  feature,
  blockedBy,
  reason,
  eta,
  children,
}: UnavailableProps): JSX.Element {
  return (
    <div className="unavailable">
      {children}
      <div className="unavailable__box">
        <p className="unavailable__title">
          {feature}还没接上后端
        </p>
        <p className="muted">{reason}</p>
        <p className="unavailable__meta">
          阻塞编号 <code>{blockedBy}</code>
          {eta !== undefined ? <> · {eta} 阶段承接</> : null}
        </p>
      </div>
    </div>
  )
}

/** 区块容器：线与留白分隔，不用圆角卡片 */
export function Sheet({
  title,
  note,
  children,
}: {
  title: string
  note?: string
  children: React.ReactNode
}): JSX.Element {
  return (
    <section className="sheet">
      <h2 className="sheet__title">{title}</h2>
      {note !== undefined ? <p className="sheet__note">{note}</p> : null}
      {children}
    </section>
  )
}

/**
 * 批改纸区块 —— 产品签名
 *
 * ⛔ 左侧红边**仅在指出错误时**出现；无错时用 marked--calm 的中性线。
 */
export function Marked({
  label,
  tone = 'calm',
  children,
}: {
  label?: string
  tone?: 'calm' | 'error'
  children: React.ReactNode
}): JSX.Element {
  const cls = tone === 'error' ? 'marked' : 'marked marked--calm'
  return (
    <div className={cls}>
      {label !== undefined ? <p className="marked__label">{label}</p> : null}
      {children}
    </div>
  )
}