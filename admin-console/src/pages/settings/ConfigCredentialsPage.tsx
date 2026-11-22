import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Card,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import { getMcpCatalog } from '@/api/adminApi'
import type { McpCatalogItem } from '@/api/types'
import { formatTime } from '@/pages/monitoring/monitoringFormat'

const { Text } = Typography

/**
 * MCP 凭据引用登记视图（/config/credentials）。
 *
 * <p>⚠️ <b>本页只呈现「是否登记了凭据引用」这一布尔事实</b>：
 * credentialRef 是凭据库定位符，凭据材料与定位符<b>均不入响应</b>（§4.2 / §6.4）——
 * 后端目录端点刻意只回 {@code hasCredentialRef} 布尔。
 * 登记与轮换凭据走<b>带外运维通道</b>：任何能通过应用层读写凭据引用的入口
 * 都等于把「凭据管理权」暴露给租户身份，这是 P0.5 红线的延伸。
 */
function CredentialsPane(): JSX.Element {
  const [rows, setRows] = useState<McpCatalogItem[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    getMcpCatalog()
      .then(setRows)
      .catch((e: unknown) => {
        setRows([])
        setError(e instanceof Error ? e.message : 'MCP 目录不可用')
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const registered = rows.filter((r) => r.hasCredentialRef).length

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="凭据材料与定位符永不入响应"
        description={
          <Text>
            本页只显示各 MCP Server 是否已登记凭据引用（布尔标识）。
            登记与轮换经带外运维通道执行；应用层不提供任何凭据读写入口。
          </Text>
        }
      />
      {error && (
        <Alert
          type="error"
          showIcon
          message="MCP 目录不可用"
          description={<Text>{error}</Text>}
        />
      )}
      <Spin spinning={loading}>
        <Table<McpCatalogItem>
          rowKey="serverId"
          size="small"
          dataSource={rows}
          pagination={false}
          locale={{ emptyText: '平台上没有 MCP Server 定义' }}
          columns={[
            { title: 'Server', dataIndex: 'serverId', width: 220 },
            { title: '传输', dataIndex: 'transport', width: 110 },
            {
              title: '认证形态',
              dataIndex: 'authKind',
              width: 140,
              render: (v: string) => <Text code>{v}</Text>,
            },
            {
              title: '信任分级',
              dataIndex: 'trustTier',
              width: 110,
            },
            {
              title: '凭据引用',
              dataIndex: 'hasCredentialRef',
              width: 140,
              render: (v: boolean) =>
                v ? <Tag color="green">已登记</Tag> : <Tag>未登记</Tag>,
            },
            {
              title: '发布时刻',
              dataIndex: 'publishedAt',
              width: 180,
              render: (v: string | null) => formatTime(v),
            },
          ]}
        />
        <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
          共 {rows.length} 个 MCP Server，其中 {registered} 个已登记凭据引用。
        </Text>
      </Spin>
    </Space>
  )
}

/** 凭据引用登记（只读标识）：/config/credentials 的独立页面，不再承载旧「全局参数」占位页签。 */
export function ConfigCredentialsPage(): JSX.Element {
  return (
    <Card title="凭据引用登记">
      <CredentialsPane />
    </Card>
  )
}
