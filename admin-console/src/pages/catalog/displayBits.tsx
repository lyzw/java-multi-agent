import type { ReactNode } from 'react'
import { Tag, Tooltip, Typography } from 'antd'

/** 风险等级配色（READ 只读 / WRITE_PROFILE 内部写 / WRITE_EXTERNAL 外部写 / UNKNOWN 不可判定） */
export function riskTag(level: string): ReactNode {
  const color =
    level === 'READ'
      ? 'green'
      : level === 'WRITE_PROFILE'
        ? 'gold'
        : level === 'WRITE_EXTERNAL'
          ? 'red'
          : 'default'
  return <Tag color={color}>{level}</Tag>
}

/**
 * 生命周期状态配色（DRAFT / VALIDATED / PUBLISHED / DEPRECATED）。
 *
 * <p>⚠️ **读不到状态 ≠ 没有状态**：目录接口读失败时给 `null`，
 * 此时必须如实呈现「未提供」，不得回落成「已发布」——
 * 那会让一个**早已停用**的对象看起来还活着。
 */
export function lifecycleTag(state: string | null | undefined): ReactNode {
  if (!state) return <Typography.Text type="secondary">未提供</Typography.Text>
  const color =
    state === 'PUBLISHED'
      ? 'green'
      : state === 'DEPRECATED'
        ? 'red'
        : state === 'VALIDATED'
          ? 'blue'
          : 'orange'
  return <Tag color={color}>{state}</Tag>
}

/**
 * 目录列表里的「状态」列：**生命周期状态 + 发布状态合并**成一列。
 *
 * <p>⚠️ **为什么必须合并**：两者是**不同**的事实 ——
 * {@code published} 来自「有无 `published_at`」，{@code lifecycleState}
 * 来自生命周期迁移记录。一个对象完全可能
 * 「{@code published=true} 而 {@code lifecycleState=DEPRECATED}」
 * （停用是**单向终态**，已发布过的对象停用后仍然 published）。
 * ⚠️ **只显示 `published` 会把「已停用」的对象显示成「已发布」** ——
 * 运维扫一眼目录会以为它还能用，而它其实早已不能被引用。
 */
export function lifecycleStateCell(
  state: string | null | undefined,
  published: boolean,
): ReactNode {
  if (state === 'DEPRECATED') return <Tag color="red">已停用</Tag>
  // ⚠️ DRAFT / VALIDATED 都**不是**已发布 —— 如实显示原始状态
  if (state && state !== 'PUBLISHED') return <Tag color="orange">{state}</Tag>
  return published ? <Tag color="green">已发布</Tag> : <Tag color="orange">未发布</Tag>
}

/**
 * 配置指纹列（`config_fingerprint`）—— 该版本定义内容的指纹。
 *
 * <p>⚠️ 目录里**放不下**完整指纹，故省略显示 + tooltip 给全值：
 * 指纹是「这一行的内容到底变没变」的**唯一客观依据**
 * （比对 JSON 原文靠不住 —— 格式化一次就比不出了）。
 */
export function fingerprintCell(value: string | null | undefined): ReactNode {
  const v = (value ?? '').trim()
  if (v === '') return <Typography.Text type="secondary">未提供</Typography.Text>
  return (
    <Tooltip title={v}>
      <Typography.Text code copyable={{ text: v }} style={{ maxWidth: 160 }}>
        {v.length > 20 ? `${v.slice(0, 20)}…` : v}
      </Typography.Text>
    </Tooltip>
  )
}

/** 时间列（无值时如实呈现「-」，不得补默认值）。 */
export function timeCell(value: string | null | undefined): ReactNode {
  const v = (value ?? '').trim()
  return v === '' ? <Typography.Text type="secondary">-</Typography.Text> : v
}

/**
 * allowlist 的 null≠[] 语义呈现（§4.4）：
 * <ul>
 *   <li>{@code null} = 未配置（取 ServerAllowedOps 全集）；</li>
 *   <li>{@code []} = 显式空（不允许任何操作）；</li>
 *   <li>其余 = 列出显式项。</li>
 * </ul>
 * ⚠️ 二者语义不同，不可归一化——把「显式空」显示成「未配置」等于权限放宽。
 */
export function allowlistCell(list: string[] | null): ReactNode {
  if (list === null) {
    return <Typography.Text type="secondary">未配置（取全集）</Typography.Text>
  }
  if (list.length === 0) {
    return <Tag color="red">显式空（∅）</Tag>
  }
  return <Typography.Text>{list.join('、')}</Typography.Text>
}

export function tagList(list: string[], emptyText = '（无）', color?: string): ReactNode {
  if (list.length === 0) {
    return <Typography.Text type="secondary">{emptyText}</Typography.Text>
  }
  return (
    <>
      {list.map((v) => (
        <Tag key={v} color={color}>{v}</Tag>
      ))}
    </>
  )
}
