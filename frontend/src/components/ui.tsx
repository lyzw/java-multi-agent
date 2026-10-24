/**
 * 现代化 UI 基元（演进引入）
 *
 * 这些组件只在既有「纸感」纪律内补齐现代交互件：
 *   · PageHead   统一页头（eyebrow + 标题 + 导语 + 操作位）
 *   · Panel      1px 边 + 极浅纸面的分组面板（⛔ 无阴影）
 *   · StatusPill 带圆点的低饱和状态药丸
 *   · StatCard   统计卡（工作台 / 看板）
 *   · Toolbar    筛选控件 + 操作的底部对齐条
 *
 * ⚠️ 动态类名一律走查找表（PANEL / PILL）再以变量形式交给 className，
 *    避免 `pill pill--${tone}` 这类模板串在 css.integrity 检查里
 *    析出悬空的 `pill--` 令牌而误报「未定义」。
 */

import type { ReactNode } from 'react'
import { Icon, type IconName } from '@/components/Icon'
import { usePageTitle } from '@/shared/usePageTitle'

/* ── 页头 ─────────────────────────────────────────────────── */

export interface PageHeadProps {
  title: string
  lead?: ReactNode
  eyebrow?: string
  /** 右侧操作（按钮 / 链接组）*/
  actions?: ReactNode
}

export function PageHead({ title, lead, eyebrow, actions }: PageHeadProps): JSX.Element {
  // ⚠️ 标题同步放在这里而不是每个页面各写一遍：
  //    PageHead 是除登录页外每个页面的第一件事，"忘了设标题"
  //    在这个位置不可能发生。
  usePageTitle(title)
  const heading = (
    <>
      {eyebrow !== undefined ? <p className="page-head__eyebrow">{eyebrow}</p> : null}
      <h1 className="page-head__title">{title}</h1>
      {lead !== undefined ? <p className="page-head__lead">{lead}</p> : null}
    </>
  )
  return (
    <div className="page-head">
      {actions !== undefined ? (
        <div className="page-head__row">
          <div>{heading}</div>
          <div className="page-head__actions">{actions}</div>
        </div>
      ) : (
        heading
      )}
    </div>
  )
}

/* ── 面板 ─────────────────────────────────────────────────── */

const PANEL: Record<'default' | 'quiet' | 'danger', string> = {
  default: 'panel',
  quiet: 'panel panel--quiet',
  danger: 'panel panel--danger',
}

export interface PanelProps {
  title?: string
  note?: string
  actions?: ReactNode
  icon?: IconName
  variant?: 'default' | 'quiet' | 'danger'
  /** 去掉 body 内边距（表格 / 列表贴边）*/
  flush?: boolean
  children: ReactNode
}

export function Panel({
  title,
  note,
  actions,
  icon,
  variant = 'default',
  flush = false,
  children,
}: PanelProps): JSX.Element {
  const hasHead = title !== undefined || actions !== undefined
  return (
    <section className={PANEL[variant]}>
      {hasHead ? (
        <header className="panel__head">
          <div>
            {title !== undefined ? (
              <h2 className="panel__title">
                {icon !== undefined ? <Icon name={icon} /> : null}
                {title}
              </h2>
            ) : null}
            {note !== undefined ? <p className="panel__note">{note}</p> : null}
          </div>
          {actions !== undefined ? <div className="panel__actions">{actions}</div> : null}
        </header>
      ) : null}
      <div className={flush ? 'panel__body panel__body--flush' : 'panel__body'}>{children}</div>
    </section>
  )
}

/* ── 状态药丸 ─────────────────────────────────────────────── */

export type PillTone = 'neutral' | 'info' | 'success' | 'warn' | 'danger'

const PILL: Record<PillTone, string> = {
  neutral: 'pill pill--neutral',
  info: 'pill pill--info',
  success: 'pill pill--success',
  warn: 'pill pill--warn',
  danger: 'pill pill--danger',
}

export interface StatusPillProps {
  tone: PillTone
  /** 是否显示前置圆点（默认 true）*/
  dot?: boolean
  children: ReactNode
}

export function StatusPill({ tone, dot = true, children }: StatusPillProps): JSX.Element {
  return (
    <span className={PILL[tone]}>
      {dot ? <span className="pill__dot" aria-hidden="true" /> : null}
      {children}
    </span>
  )
}

/* ── 统计卡 ───────────────────────────────────────────────── */

export interface StatCardProps {
  label: string
  value: ReactNode
  hint?: string
  icon?: IconName
  tone?: PillTone
}

export function StatCard({ label, value, hint, icon, tone }: StatCardProps): JSX.Element {
  return (
    <div className="stat">
      <p className="stat__label">
        {icon !== undefined ? <Icon name={icon} className="icon--sm" /> : null}
        {label}
      </p>
      <p className="stat__value" style={tone !== undefined ? { color: `var(--pill-${tone}-fg)` } : undefined}>
        {value}
      </p>
      {hint !== undefined ? <p className="stat__hint">{hint}</p> : null}
    </div>
  )
}

/* ── 工具条 ───────────────────────────────────────────────── */

export function Toolbar({ children }: { children: ReactNode }): JSX.Element {
  return <div className="toolbar">{children}</div>
}
