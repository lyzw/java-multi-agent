import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button, Card, Descriptions, Modal, Space, Table, Tag, Typography, App as AntApp,
} from 'antd'
import { ReloadOutlined, RocketOutlined, WarningOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import {
  listDrafts, publishDraft, retryDraft, type ConfigDraftItem, type FailStage,
} from '@/api/publishWorkflow'

const FAIL_STAGE_TAGS: Record<FailStage, { color: string; label: string; hint: string }> = {
  PRE_CHECK:       { color: 'geekblue', label: '前端预检',     hint: '基础字段完整性 / JSON 结构校验' },
  PARAM_VALIDATE:  { color: 'blue',    label: '参数校验',      hint: 'Schema / 类型 / 范围 / 必填项' },
  DEP_CHECK:       { color: 'purple',  label: '依赖检查',      hint: '引用的 Skill/Tool/子 Agent/契约/路由是否存在且可用' },
  POINTER_CAS:     { color: 'orange',  label: '指针并发',      hint: '发布指针 CAS 失败，其他发布抢先了 — 重新尝试即可' },
  RUNTIME_SYNC:    { color: 'red',     label: '运行时同步',    hint: '版本行写入 / 发布头 / 指针原子切换后的运行时异常' },
}

/**
 * 发布失败列表（draft_state = PUBLISH_FAILED）。
 *
 * <p>⚠️ 失败 <b>不覆盖</b>线上生效版本 —— 发布指针保持不变。
 * 草稿的 failure_stage + failure_details 记录了完整失败现场，修复后可重新发布。
 */
export function PublishFailuresPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState<ConfigDraftItem[]>([])
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<ConfigDraftItem | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const page = await listDrafts({ state: 'PUBLISH_FAILED', limit: 100 })
      setRows(page.items)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const handleRetry = async (row: ConfigDraftItem) => {
    try {
      if (row.draftState === 'PUBLISH_FAILED') {
        await retryDraft(row.draftId)
      } else {
        await publishDraft(row.draftId)
      }
      message.success('已发起重发')
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns = useMemo(() => ([
    { title: '资源类型', dataIndex: 'resourceKind', width: 130 },
    { title: '资源 ID', dataIndex: 'resourceId', width: 200, ellipsis: true },
    {
      title: '失败阶段', dataIndex: 'failureStage', width: 130,
      render: (v?: FailStage) => {
        if (!v) return <Typography.Text type="secondary">—</Typography.Text>
        const m = FAIL_STAGE_TAGS[v]
        return <Space direction="vertical" size={0}>
          <Tag color={m.color}>{m.label}</Tag>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{m.hint}</Typography.Text>
        </Space>
      },
    },
    {
      title: '失败明细摘要', width: 240, ellipsis: true,
      render: (_: unknown, row: ConfigDraftItem) =>
        row.failureDetails
          ? <Typography.Text code ellipsis>{row.failureDetails}</Typography.Text>
          : <Typography.Text type="secondary">—</Typography.Text>,
    },
    { title: '线上基线版本', dataIndex: 'baseVersion', width: 110, render: (v: number) => `v${v}` },
    { title: '草稿版本', dataIndex: 'draftVersion', width: 100, render: (v: number) => `v${v}` },
    { title: '最后操作人', dataIndex: 'operatorId', width: 140 },
    { title: '失败时间', width: 180, render: (_: unknown, r: ConfigDraftItem) => dayjs(r.updatedAt).format('YYYY-MM-DD HH:mm:ss') },
    {
      title: '操作', width: 200, fixed: 'right' as const,
      render: (_: unknown, row: ConfigDraftItem) => (
        <Space size="small">
          <Button size="small" icon={<WarningOutlined />} onClick={() => setDetail(row)}>详情</Button>
          <Button size="small" type="primary" icon={<RocketOutlined />} onClick={() => handleRetry(row)}>重发</Button>
        </Space>
      ),
    },
  ]), [])

  return (
    <Card
      title={<Space>
        <Typography.Title level={4} style={{ margin: 0 }}>发布失败列表</Typography.Title>
        <Tag color="volcano">线上生效版本保持不变</Tag>
      </Space>}
      extra={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
    >
      <Table
        rowKey="draftId"
        columns={columns}
        dataSource={rows}
        loading={loading}
        pagination={{ pageSize: 20 }}
      />

      <Modal
        title="发布失败详情"
        open={!!detail}
        onCancel={() => setDetail(null)}
        footer={null}
        width={780}
      >
        {detail && (
          <>
            <Descriptions column={2} bordered size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="资源类型">{detail.resourceKind}</Descriptions.Item>
              <Descriptions.Item label="资源 ID">{detail.resourceId}</Descriptions.Item>
              <Descriptions.Item label="失败阶段">
                {detail.failureStage
                  ? <Tag color={FAIL_STAGE_TAGS[detail.failureStage].color}>{FAIL_STAGE_TAGS[detail.failureStage].label}</Tag>
                  : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="最后操作人">{detail.operatorId}</Descriptions.Item>
              <Descriptions.Item label="线上基线">v{detail.baseVersion}</Descriptions.Item>
              <Descriptions.Item label="草稿版本">v{detail.draftVersion}</Descriptions.Item>
            </Descriptions>

            <Typography.Text strong>结构化失败明细：</Typography.Text>
            <pre style={{
              background: '#fff1f0', border: '1px solid #ffa39e', borderRadius: 6,
              padding: 12, maxHeight: 380, overflow: 'auto', marginTop: 8, marginBottom: 0,
            }}>
              {detail.failureDetails ?? '无'}
            </pre>
          </>
        )}
      </Modal>
    </Card>
  )
}
