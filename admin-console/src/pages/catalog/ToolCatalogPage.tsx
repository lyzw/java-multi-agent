import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { Tag, Typography } from 'antd'
import { ToolOutlined } from '@ant-design/icons'
import { ReadOnlyCatalogPage } from './ReadOnlyCatalogPage'
import { riskTag } from './displayBits'
import { getToolCatalog } from '@/api/adminApi'
import type { ToolCatalogItem } from '@/api/types'

/** Tool 目录（P1 · 平台级只读，复用 /api/cap/catalog/tools） */
export function ToolCatalogPage(): JSX.Element {
  const fetcher = useCallback(() => getToolCatalog(), [])
  return (
    <ReadOnlyCatalogPage<ToolCatalogItem>
      title={<><ToolOutlined /> Tool 目录</>}
      notice="平台级 Tool 目录（只读）"
      noticeDesc="Tool 定义是平台级对象，全平台唯一。新增 / 改版 / 弃用须经带外运维通道，本控制台不提供写入口。点「影响」查看谁引用了该 Tool。"
      fetcher={fetcher}
      rowKey={(r) => r.toolId}
      actions={(r) => (
        <Link to={`/dependencies/impact?type=tool&id=${encodeURIComponent(r.toolId)}`}>影响</Link>
      )}
      columns={[
        { title: 'ID', dataIndex: 'toolId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
        { title: '名称', dataIndex: 'name', render: (v: string | null) => v ?? '-' },
        { title: '类型', dataIndex: 'kind', render: (v: string) => <Tag>{v}</Tag> },
        { title: '风险等级', dataIndex: 'riskLevel', render: (v: string) => riskTag(v) },
        {
          title: 'MCP 绑定',
          dataIndex: 'mcpServerId',
          render: (v: string | null, r: ToolCatalogItem) =>
            v ? (
              <Typography.Text code>{v}::{r.mcpToolName ?? '?'}</Typography.Text>
            ) : (
              <Typography.Text type="secondary">内置</Typography.Text>
            ),
        },
        { title: '版本', dataIndex: 'version', render: (v: number) => <Tag color="blue">v{v}</Tag> },
        {
          title: '状态',
          dataIndex: 'published',
          render: (p: boolean) => (p ? <Tag color="green">已发布</Tag> : <Tag>未发布</Tag>),
        },
      ]}
    />
  )
}

export default ToolCatalogPage
