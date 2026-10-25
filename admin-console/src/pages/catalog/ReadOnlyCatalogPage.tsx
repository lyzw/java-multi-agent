import { useEffect, useState, type ReactNode } from 'react'
import { Card, Table, Alert, Typography, App as AntApp } from 'antd'
import type { TableColumnsType } from 'antd'

interface ReadOnlyCatalogPageProps<T> {
  title: ReactNode
  notice: string
  noticeDesc: string
  fetcher: () => Promise<T[]>
  columns: TableColumnsType<T>
  rowKey: (record: T) => string
  /**
   * 行尾操作（P3 起：目录行的「影响分析」入口）。
   *
   * <p>⚠️ 刻意只放<b>导航类</b>操作（跳影响分析页）：平台级对象没有应用层
   * 写入口，行尾不出现任何编辑 / 删除按钮本身就是一条安全声明。
   */
  actions?: (record: T) => ReactNode
  /**
   * 卡片右上角的操作区（C34-3：登记新对象的入口）。
   *
   * <p>⚠️ 刻意与「行尾操作」分开：登记是**对目录**的动作（产生新对象），
   * 行尾是对**某一行**的动作（导航到那个对象）—— 混在一起会让人
   * 分不清「点这一下会改哪个对象」。
   */
  cardExtra?: ReactNode
}

/**
 * 平台级能力目录通用页（P1 · Tool / Skill / MCP 共用）。
 *
 * <p>⚠️ 目录揭示「平台上有哪些能力、各自风险画像」，是攻击面枚举素材，
 * 故读取同样需鉴权。
 *
 * <p>⚠️ <b>C34-3 起本目录不再是终点</b>：平台级对象的变更已接上
 * 「申请 → 审批 → 执行」三步工作流（PLATFORM_ADMIN 闸门 + 运维通道），
 * 所以行尾多了「详情」入口，页头多了「登记」入口。
 * 但<b>行尾仍不出现任何编辑 / 删除按钮</b> —— 变更动作一律落到详情页，
 * 那里才有完整的上下文（关系、版本、停用语义）。
 */
export function ReadOnlyCatalogPage<T>({
  title,
  notice,
  noticeDesc,
  fetcher,
  columns,
  rowKey,
  actions,
  cardExtra,
}: ReadOnlyCatalogPageProps<T>): JSX.Element {
  const { message } = AntApp.useApp()
  const [data, setData] = useState<T[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    setLoading(true)
    fetcher()
      .then((rows) => {
        if (active) setData(rows)
      })
      .catch((e: unknown) => {
        if (active) message.error(e instanceof Error ? e.message : '加载目录失败')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
    // 目录页挂载即加载一次，fetcher 由调用方以 useCallback 稳定
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const allColumns: TableColumnsType<T> = actions
    ? [
        ...columns,
        {
          title: '影响',
          key: 'impact',
          width: 90,
          fixed: 'right' as const,
          render: (_: unknown, record: T) => actions(record),
        },
      ]
    : columns

  return (
    <div>
      <Typography.Title level={3}>{title}</Typography.Title>
      <Alert type="info" showIcon style={{ marginBottom: 16 }} message={notice} description={noticeDesc} />
      <Card extra={cardExtra}>
        <Table<T>
          rowKey={rowKey}
          loading={loading}
          dataSource={data}
          pagination={false}
          locale={{ emptyText: '暂无数据' }}
          columns={allColumns}
        />
      </Card>
    </div>
  )
}

export default ReadOnlyCatalogPage
