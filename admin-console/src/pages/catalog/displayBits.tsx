import type { ReactNode } from 'react'
import { Tag, Typography } from 'antd'

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
