import { useCallback, useEffect, useState } from 'react'
import { Alert, App as AntApp, Button, Card, Form, InputNumber, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { ApiOutlined, PlusOutlined } from '@ant-design/icons'
import { getConsoleMcpCatalog } from '@/api/adminApi'
import type { McpCatalogItem } from '@/api/types'
import {
  riskTag,
  allowlistCell,
  fingerprintCell,
  lifecycleStateCell,
  timeCell,
} from '@/pages/catalog/displayBits'
import { PlatformRegistrationModal } from '@/pages/catalog/PlatformRegistrationModal'
import { McpFormFields } from '@/pages/catalog/McpFormFields'
import { type McpServerFormValues } from '@/pages/catalog/platformObjectForm'
import { buildMcpPublishRequest } from '@/pages/catalog/platformPublishRequests'
import { useRegistrationModal } from '@/pages/catalog/useRegistrationModal'
import { PlatformDeprecateModal } from './PlatformDeprecateModal'
import {
  LifecycleTabs,
  PlatformRequestQueueButton,
  describeCatalogLoadError,
} from './platformRequestBits'

/** MCP Server 目录（平台链 /api/platform/v1/capabilities/mcps · 按生命周期分组；transport 用列呈现） */
export function McpCatalogPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState<McpCatalogItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const registration = useRegistrationModal()
  const [deprecateTarget, setDeprecateTarget] = useState<{
    serverId: string
    version: number
  } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setRows(await getConsoleMcpCatalog())
    } catch (e) {
      setError(describeCatalogLoadError(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const columns: TableColumnsType<McpCatalogItem> = [
    { title: 'ID', dataIndex: 'serverId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: '名称', key: 'name', dataIndex: 'serverId', render: () => <Typography.Text type="secondary">-</Typography.Text> },
    {
      title: '传输', // ← 二级维度：不单独开 Tab，用列呈现
      dataIndex: 'transport',
      render: (v: string) => <Tag color={v === 'HTTP' ? 'blue' : v === 'SSE' ? 'cyan' : 'default'}>{v}</Tag>,
    },
    {
      title: 'Endpoint / Base URL',
      dataIndex: 'endpoint',
      render: (v: string | null) =>
        v ? (
          <Typography.Text code ellipsis style={{ maxWidth: 260 }}>
            {v}
          </Typography.Text>
        ) : (
          <Typography.Text type="secondary">（本地命令）</Typography.Text>
        ),
    },
    {
      title: '暴露操作（null≠[]）',
      dataIndex: 'exposedToolAllowlist',
      render: (v: string[] | null) => allowlistCell(v),
    },
    {
      title: '凭据引用',
      dataIndex: 'hasCredentialRef',
      render: (b: boolean) =>
        b ? <Tag color="gold">已登记</Tag> : <Tag>无</Tag>,
    },
    { title: '连接风险', dataIndex: 'connectionRisk', render: (v: string) => riskTag(v) },
    {
      title: '状态',
      dataIndex: 'lifecycleState',
      render: (state: string | null, r: McpCatalogItem) => lifecycleStateCell(state, r.published),
    },
    { title: '版本', dataIndex: 'version', render: (v: number) => <Typography.Text>v{v}</Typography.Text> },
    { title: '发布时间', dataIndex: 'publishedAt', render: (v: string | null) => timeCell(v) },
    { title: '配置指纹', dataIndex: 'configFingerprint', render: (v: string | null | undefined) => fingerprintCell(v) },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 90,
      render: (_: unknown, r: McpCatalogItem) =>
        r.lifecycleState === 'PUBLISHED' ? (
          <Button size="small" onClick={() => setDeprecateTarget({ serverId: r.serverId, version: r.version })}>
            停用
          </Button>
        ) : null,
    },
  ]

  const renderTable = (data: McpCatalogItem[]): JSX.Element => (
    <Table<McpCatalogItem>
      rowKey="serverId"
      loading={loading}
      dataSource={data}
      columns={columns}
      pagination={false}
      size="small"
      scroll={{ x: 'max-content' }}
      locale={{ emptyText: '暂无数据' }}
    />
  )

  return (
    <div>
      <Typography.Title level={3}>
        <ApiOutlined /> MCP 服务目录（平台能力）
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="平台级 MCP Server 目录（/api/platform/v1/capabilities/*）"
        description="MCP Server 是平台级对象。登记 / 改版 / 停用走平台级三步工作流（申请 → 审批 → 执行，需 PLATFORM_ADMIN）。凭据仅存定位符，本目录不回显任何凭据定位符或凭据材料。"
      />
      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="MCP Server 目录不可用"
          description={error}
        />
      )}
      <Card
        extra={
          <Space>
            <PlatformRequestQueueButton />
            <Button type="primary" icon={<PlusOutlined />} onClick={registration.openModal}>
              登记 MCP Server
            </Button>
          </Space>
        }
      >
        <LifecycleTabs<McpCatalogItem>
          items={rows}
          renderPane={(data) => renderTable(data)}
        />
      </Card>

      <PlatformDeprecateModal
        objectType="mcp_server"
        objectId={deprecateTarget?.serverId ?? null}
        version={deprecateTarget?.version ?? null}
        open={deprecateTarget !== null}
        onClose={() => setDeprecateTarget(null)}
        onSubmitted={() => message.success('已在申请队列，可在页头「申请队列」查看/跟进')}
      />

      <PlatformRegistrationModal<McpServerFormValues & { reason: string; expectedPublishSeq?: number }>
        open={registration.open}
        title="登记 MCP Server（申请 → 审批 → 执行）"
        onClose={registration.closeModal}
        buildRequest={buildMcpPublishRequest}
        renderFields={() => (
          <>
            <McpFormFields isRegistration />
            <Form.Item
              name="expectedPublishSeq"
              label="发布基线（expectedPublishSeq · 可选）"
              extra="填入你读到的当前发布序号；不一致时后端 409（防止覆盖别人改过的配置）"
            >
              <InputNumber min={0} precision={0} style={{ width: 200 }} />
            </Form.Item>
          </>
        )}
      />
    </div>
  )
}

export default McpCatalogPage
