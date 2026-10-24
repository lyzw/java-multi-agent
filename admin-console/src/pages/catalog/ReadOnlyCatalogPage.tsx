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
}

/**
 * 平台级只读目录通用页（P1 · Tool / Skill / MCP 共用）。
 *
 * <p>⚠️ 目录揭示「平台上有哪些能力、各自风险画像」，是攻击面枚举素材，
 * 故读取同样需鉴权；且<b>刻意只读</b>——平台级对象变更走带外运维通道
 * （与 P0.5 作用域红线一致），不把写入口挂到租户身份上。
 */
export function ReadOnlyCatalogPage<T>({
  title,
  notice,
  noticeDesc,
  fetcher,
  columns,
  rowKey,
  actions,
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
      <Card>
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
