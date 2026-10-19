/**
 * 统一「数据未就绪」占位（FE-D2 §2.1）
 *
 * ⚠️ 为什么必须统一：
 *   散落的占位文案会导致「有的页说未开放、有的页空白、有的页有假数据」，
 *   用户无法判断哪些能用。**一致性本身就是信息。**
 *
 * ⛔ 本组件**不是**「暂无数据」：
 *   「暂无数据」= 查了，确实没有；本组件 = 根本没接口可查。
 *   两者混淆会让用户以为系统坏了（FE-01 §4.3 S4）。
 */

export interface DataUnavailableProps {
  /** 后端缺口编号，如 G5 / C1 —— 可追溯到 FE-01/FE-02 */
  blockedBy: string
  /** 功能名，如「归因结果」 */
  feature: string
  /** 人话原因，如「后端尚未提供 result:read 端点」 */
  reason: string
  /** 预计承接阶段，如 P3 */
  eta?: string
  /** 可选：仍展示页面结构，让用户知道未来会长什么样 */
  children?: React.ReactNode
}

export function DataUnavailable({
  blockedBy,
  feature,
  reason,
  eta,
  children,
}: DataUnavailableProps): JSX.Element {
  return (
    <section className="unavailable" aria-live="polite">
      {children}

      <div className="unavailable__notice">
        <p className="unavailable__title">
          {feature}功能尚未开放
        </p>
        <p className="unavailable__reason">{reason}</p>
        <p className="unavailable__meta">
          阻塞编号 <code>{blockedBy}</code>
          {eta !== undefined ? <> · 预计由 {eta} 阶段承接</> : null}
        </p>
      </div>
    </section>
  )
}

/**
 * 区块骨架 —— 用于「结构已知、数据未就绪」。
 *
 * ⛔ 刻意**不填充假数据**：
 *    灰色占位块明确表达「这里将来有内容，现在没有」，
 *    而假数据会让人误以为系统能用。
 */
export function BlockSkeleton({
  title,
  rows = 3,
  note,
}: {
  title: string
  rows?: number
  note?: string
}): JSX.Element {
  return (
    <section className="block-skeleton">
      <h2 className="block-skeleton__title">{title}</h2>
      {note !== undefined ? (
        <p className="block-skeleton__note">{note}</p>
      ) : null}
      <ul className="block-skeleton__rows" aria-hidden="true">
        {Array.from({ length: rows }, (_, i) => (
          <li key={i} className="block-skeleton__bar" />
        ))}
      </ul>
    </section>
  )
}

/** 表格骨架 —— 列表类页面用 */
export function TableSkeleton({
  columns,
  rows = 5,
}: {
  columns: string[]
  rows?: number
}): JSX.Element {
  return (
    <table className="table-skeleton">
      <thead>
        <tr>
          {columns.map((col) => (
            <th key={col} scope="col">
              {col}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {Array.from({ length: rows }, (_, i) => (
          <tr key={i} aria-hidden="true">
            {columns.map((col) => (
              <td key={col}>
                <span className="table-skeleton__bar" />
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}