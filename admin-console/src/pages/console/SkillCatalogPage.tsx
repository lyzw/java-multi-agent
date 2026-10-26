import { useCallback, useEffect, useState } from 'react'
import { Alert, App as AntApp, Button, Card, Form, InputNumber, Space, Table, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { BulbOutlined, PlusOutlined } from '@ant-design/icons'
import { getConsoleSkillCatalog } from '@/api/adminApi'
import type { SkillCatalogItem } from '@/api/types'
import { riskTag, lifecycleStateCell, timeCell } from '@/pages/catalog/displayBits'
import { PlatformRegistrationModal } from '@/pages/catalog/PlatformRegistrationModal'
import { SkillFormFields } from '@/pages/catalog/SkillFormFields'
import { type SkillFormValues } from '@/pages/catalog/platformObjectForm'
import { buildSkillPublishRequest } from '@/pages/catalog/platformPublishRequests'
import { useRegistrationModal } from '@/pages/catalog/useRegistrationModal'
import { PlatformDeprecateModal } from './PlatformDeprecateModal'
import {
  LifecycleTabs,
  PlatformRequestQueueButton,
  describeCatalogLoadError,
} from './platformRequestBits'

/** Skill 目录（平台链 /api/platform/v1/capabilities/skills · 按生命周期分组） */
export function SkillCatalogPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState<SkillCatalogItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const registration = useRegistrationModal()
  const [deprecateTarget, setDeprecateTarget] = useState<{
    skillId: string
    version: number
  } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setRows(await getConsoleSkillCatalog())
    } catch (e) {
      setError(describeCatalogLoadError(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const columns: TableColumnsType<SkillCatalogItem> = [
    { title: 'ID', dataIndex: 'skillId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: '名称', dataIndex: 'name', render: (v: string | null) => v ?? '-' },
    { title: '风险上限', dataIndex: 'riskCeiling', render: (v: string) => riskTag(v) },
    {
      title: '状态',
      dataIndex: 'lifecycleState',
      render: (state: string | null, r: SkillCatalogItem) => lifecycleStateCell(state, r.published),
    },
    {
      title: '声明工具',
      dataIndex: 'declaredTools',
      render: (v: string[]) => <Typography.Text>{v.length} 个</Typography.Text>,
    },
    {
      title: '声明 MCP',
      dataIndex: 'declaredMcp',
      render: (v: SkillCatalogItem['declaredMcp']) => <Typography.Text>{v.length} 个</Typography.Text>,
    },
    { title: '版本', dataIndex: 'version', render: (v: number) => <Typography.Text>v{v}</Typography.Text> },
    { title: '发布时间', dataIndex: 'publishedAt', render: (v: string | null) => timeCell(v) },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 90,
      render: (_: unknown, r: SkillCatalogItem) =>
        r.lifecycleState === 'PUBLISHED' ? (
          <Button size="small" onClick={() => setDeprecateTarget({ skillId: r.skillId, version: r.version })}>
            停用
          </Button>
        ) : null,
    },
  ]

  const renderTable = (data: SkillCatalogItem[]): JSX.Element => (
    <Table<SkillCatalogItem>
      rowKey="skillId"
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
        <BulbOutlined /> Skill 目录（平台能力）
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="平台级 Skill 目录（/api/platform/v1/capabilities/*）"
        description="Skill 定义是平台级对象，其声明的工具 / MCP 关系与风险上限在下方呈现。登记 / 改版 / 停用走平台级三步工作流（申请 → 审批 → 执行，需 PLATFORM_ADMIN）。"
      />
      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Skill 目录不可用"
          description={error}
        />
      )}
      <Card
        extra={
          <Space>
            <PlatformRequestQueueButton />
            <Button type="primary" icon={<PlusOutlined />} onClick={registration.openModal}>
              登记 Skill
            </Button>
          </Space>
        }
      >
        <LifecycleTabs<SkillCatalogItem>
          items={rows}
          renderPane={(data) => renderTable(data)}
        />
      </Card>

      <PlatformDeprecateModal
        objectType="skill"
        objectId={deprecateTarget?.skillId ?? null}
        version={deprecateTarget?.version ?? null}
        open={deprecateTarget !== null}
        onClose={() => setDeprecateTarget(null)}
        onSubmitted={() => message.success('已在申请队列，可在页头「申请队列」查看/跟进')}
      />

      <PlatformRegistrationModal<SkillFormValues & { reason: string; expectedPublishSeq?: number }>
        open={registration.open}
        title="登记 Skill（申请 → 审批 → 执行）"
        onClose={registration.closeModal}
        buildRequest={buildSkillPublishRequest}
        renderFields={() => (
          <>
            <SkillFormFields isRegistration />
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

export default SkillCatalogPage
