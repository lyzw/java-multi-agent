import { useCallback } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button, Space, Tag, Typography } from 'antd'
import { ApiOutlined, PlusOutlined } from '@ant-design/icons'
import { ReadOnlyCatalogPage } from './ReadOnlyCatalogPage'
import { riskTag, allowlistCell } from './displayBits'
import { getMcpCatalog } from '@/api/adminApi'
import type { McpCatalogItem } from '@/api/types'

/** MCP Server 目录（P1 · 平台级；C34-3 起可进入详情登记 / 改版 / 停用） */
export function McpCatalogPage(): JSX.Element {
  const navigate = useNavigate()
  const fetcher = useCallback(() => getMcpCatalog(), [])
  return (
    <ReadOnlyCatalogPage<McpCatalogItem>
      title={<><ApiOutlined /> MCP 服务目录</>}
      notice="平台级 MCP Server 目录"
      noticeDesc="MCP Server 是平台级对象。连接测试 / 清单采集端点留待后续阶段前置；登记 / 改版 / 停用走平台级三步工作流（需 PLATFORM_ADMIN）。凭据仅存定位符，本目录不暴露任何凭据材料。"
      cardExtra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/mcp/new')}>
          登记 MCP Server
        </Button>
      }
      fetcher={fetcher}
      rowKey={(r) => r.serverId}
      actions={(r) => (
        <Space size="small">
          <Link to={`/mcp/${encodeURIComponent(r.serverId)}`}>详情</Link>
          <Link to={`/dependencies/impact?type=mcp_server&id=${encodeURIComponent(r.serverId)}`}>影响</Link>
        </Space>
      )}
      columns={[
        { title: 'ID', dataIndex: 'serverId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
        {
          title: '传输',
          dataIndex: 'transport',
          render: (v: string) => <Tag color={v === 'HTTP' ? 'blue' : v === 'SSE' ? 'cyan' : 'default'}>{v}</Tag>,
        },
        {
          title: 'Endpoint',
          dataIndex: 'endpoint',
          render: (v: string | null) =>
            v ? (
              <Typography.Text code ellipsis style={{ maxWidth: 260 }}>{v}</Typography.Text>
            ) : (
              <Typography.Text type="secondary">（本地命令）</Typography.Text>
            ),
        },
        { title: '认证', dataIndex: 'authKind', render: (v: string) => <Tag>{v}</Tag> },
        {
          title: '信任分级',
          dataIndex: 'trustTier',
          render: (v: string) => <Tag color={v === 'INTERNAL' ? 'green' : 'orange'}>{v}</Tag>,
        },
        { title: '连接风险', dataIndex: 'connectionRisk', render: (v: string) => riskTag(v) },
        {
          title: '动态发现',
          dataIndex: 'dynamicDiscovery',
          render: (b: boolean) => (b ? <Tag color="red">开</Tag> : <Tag>关</Tag>),
        },
        { title: '暴露操作（null≠[]）', dataIndex: 'exposedToolAllowlist', render: (v: string[] | null) => allowlistCell(v) },
        { title: '版本', dataIndex: 'version', render: (v: number) => <Typography.Text>v{v}</Typography.Text> },
      ]}
    />
  )
}

export default McpCatalogPage
