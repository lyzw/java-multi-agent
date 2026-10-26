import { useCallback, useEffect, useState } from 'react'
import { Alert, App as AntApp, Button, Card, Form, InputNumber, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ToolOutlined } from '@ant-design/icons'
import { getConsoleToolCatalog } from '@/api/adminApi'
import type { ToolCatalogItem } from '@/api/types'
import {
  riskTag,
  fingerprintCell,
  lifecycleStateCell,
  timeCell,
} from '@/pages/catalog/displayBits'
import { PlatformRegistrationModal } from '@/pages/catalog/PlatformRegistrationModal'
import { ToolFormFields } from '@/pages/catalog/ToolFormFields'
import { type ToolFormValues } from '@/pages/catalog/platformObjectForm'
import { buildToolPublishRequest } from '@/pages/catalog/platformPublishRequests'
import { useRegistrationModal } from '@/pages/catalog/useRegistrationModal'
import { PlatformDeprecateModal } from './PlatformDeprecateModal'
import {
  LifecycleTabs,
  PlatformRequestQueueButton,
  describeCatalogLoadError,
} from './platformRequestBits'

/** Tool 目录（平台链 /api/platform/v1/capabilities/tools · 按生命周期分组） */
export function ToolCatalogPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState<ToolCatalogItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const registration = useRegistrationModal()
  const [deprecateTarget, setDeprecateTarget] = useState<{ toolId: string; version: number } | null>(
    null,
  )

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setRows(await getConsoleToolCatalog())
    } catch (e) {
      setError(describeCatalogLoadError(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const columns: TableColumnsType<ToolCatalogItem> = [
    { title: 'ID', dataIndex: 'toolId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: '名称', dataIndex: 'name', render: (v: string | null) => v ?? '-' },
    {
      title: '类型', // ← 二级维度：不单独开 Tab，用列呈现
      dataIndex: 'kind',
      render: (v: string) => (
        <Tag color={v === 'MCP' ? 'purple' : v === 'HTTP' ? 'blue' : 'default'}>{v}</Tag>
      ),
    },
    { title: '风险等级', dataIndex: 'riskLevel', render: (v: string) => riskTag(v) },
    { title: '版本', dataIndex: 'version', render: (v: number) => <Tag color="blue">v{v}</Tag> },
    {
      // ⚠️ 「状态」列 = 生命周期 + 发布状态合并 —— 只看 published 会把
      //    「已停用」（停用是单向终态，停用后 published 仍为 true）显示成「已发布」。
      title: '状态',
      dataIndex: 'lifecycleState',
      render: (state: string | null, r: ToolCatalogItem) => lifecycleStateCell(state, r.published),
    },
    { title: '发布时间', dataIndex: 'publishedAt', render: (v: string | null) => timeCell(v) },
    { title: '配置指纹', dataIndex: 'configFingerprint', render: (v: string | null | undefined) => fingerprintCell(v) },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 90,
      render: (_: unknown, r: ToolCatalogItem) =>
        r.lifecycleState === 'PUBLISHED' ? (
          <Button size="small" onClick={() => setDeprecateTarget({ toolId: r.toolId, version: r.version })}>
            停用
          </Button>
        ) : null,
    },
  ]

  const renderTable = (data: ToolCatalogItem[]): JSX.Element => (
    <Table<ToolCatalogItem>
      rowKey="toolId"
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
        <ToolOutlined /> Tool 目录（平台能力）
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="平台级 Tool 目录（/api/platform/v1/capabilities/*）"
        description="Tool 定义是平台级对象，全平台唯一。登记 / 改版 / 停用走平台级三步工作流（申请 → 审批 → 执行，需 PLATFORM_ADMIN）。"
      />
      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Tool 目录不可用"
          description={error}
        />
      )}
      <Card
        extra={
          <Space>
            <PlatformRequestQueueButton />
            <Button type="primary" icon={<PlusOutlined />} onClick={registration.openModal}>
              登记 Tool
            </Button>
          </Space>
        }
      >
        <LifecycleTabs<ToolCatalogItem>
          items={rows}
          renderPane={(data) => renderTable(data)}
        />
      </Card>

      <PlatformDeprecateModal
        objectType="tool"
        objectId={deprecateTarget?.toolId ?? null}
        version={deprecateTarget?.version ?? null}
        open={deprecateTarget !== null}
        onClose={() => setDeprecateTarget(null)}
        onSubmitted={() => message.success('已在申请队列，可在页头「申请队列」查看/跟进')}
      />

      <PlatformRegistrationModal<ToolFormValues & { reason: string; expectedPublishSeq?: number }>
        open={registration.open}
        title="登记 Tool（申请 → 审批 → 执行）"
        onClose={registration.closeModal}
        buildRequest={buildToolPublishRequest}
        renderFields={() => (
          <>
            <ToolFormFields isRegistration />
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

export default ToolCatalogPage
