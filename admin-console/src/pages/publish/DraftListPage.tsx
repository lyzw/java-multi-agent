import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button, Card, Descriptions, Modal, Space, Table, Tabs, Tag, Typography, App as AntApp,
} from 'antd'
import { ReloadOutlined, EditOutlined, CheckCircleOutlined, RocketOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import {
  approveDraft, discardDraft, listDrafts, publishDraft, rejectDraft,
  submitDraftApproval, type ConfigDraftItem, type DraftState, type FailStage,
} from '@/api/publishWorkflow'

/** 变更工作流状态标签（"双轨独立状态"的轨道一） */
const DRAFT_STATE_TAGS: Record<DraftState, { color: string; label: string }> = {
  DRAFT:              { color: 'default', label: '草稿' },
  PENDING_APPROVAL:   { color: 'blue', label: '待审核' },
  PENDING_PUBLISH:    { color: 'orange', label: '待发布' },
  APPROVAL_REJECTED:  { color: 'red', label: '已驳回' },
  PUBLISH_FAILED:     { color: 'volcano', label: '发布失败' },
  DISCARDED:          { color: 'default', label: '已放弃' },
}

/** 发布失败阶段标签 */
const FAIL_STAGE_TAGS: Record<FailStage, { color: string; label: string }> = {
  PRE_CHECK:       { color: 'geekblue', label: '前端预检' },
  PARAM_VALIDATE:  { color: 'blue',    label: '参数校验' },
  DEP_CHECK:       { color: 'purple',  label: '依赖检查' },
  POINTER_CAS:     { color: 'orange',  label: '指针并发' },
  RUNTIME_SYNC:    { color: 'red',     label: '运行时同步' },
}

const STATE_TABS: { key: string; label: string; state?: DraftState }[] = [
  { key: 'all',                 label: '全部草稿' },
  { key: 'active',              label: '活跃草稿' },
  { key: 'DRAFT',               label: '草稿编辑中',     state: 'DRAFT' },
  { key: 'PENDING_APPROVAL',    label: '待审核',          state: 'PENDING_APPROVAL' },
  { key: 'PENDING_PUBLISH',     label: '待发布',          state: 'PENDING_PUBLISH' },
  { key: 'APPROVAL_REJECTED',   label: '已驳回',          state: 'APPROVAL_REJECTED' },
  { key: 'PUBLISH_FAILED',      label: '发布失败',        state: 'PUBLISH_FAILED' },
]

type TabKey = (typeof STATE_TABS)[number]['key']

/**
 * 草稿列表（变更工作流轨道）。
 *
 * <p>⚠️ 与"线上运行状态"（由发布指针决定）是两条独立轨道 ——
 * 前端应同时展示"线上运行：已发布 v5"与"变更工作流：草稿 v6 编辑中"。
 */
export function DraftListPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [tab, setTab] = useState<TabKey>('active')
  const [rows, setRows] = useState<ConfigDraftItem[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [detailOpen, setDetailOpen] = useState(false)
  const [current, setCurrent] = useState<ConfigDraftItem | null>(null)

  const load = useCallback(async (reset = true) => {
    setLoading(true)
    try {
      const t = STATE_TABS.find(x => x.key === tab)!
      const state = t.state
      const page = await listDrafts({ state, cursor: reset ? undefined : cursor ?? undefined, limit: 50 })
      if (reset) {
        setRows(page.items)
      } else {
        setRows(prev => [...prev, ...page.items])
      }
      setCursor(page.nextCursor ?? null)
      setHasMore(!!page.nextCursor)
    } finally {
      setLoading(false)
    }
  }, [tab, cursor])

  useEffect(() => { void load() /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [tab])

  const afterAction = async (msg: string) => { message.success(msg); await load(true) }

  const columns = useMemo(() => ([
    { title: '资源类型', dataIndex: 'resourceKind', width: 140 },
    { title: '资源 ID',   dataIndex: 'resourceId', width: 180, ellipsis: true },
    {
      title: '资源名称', dataIndex: 'resourceName', width: 180, ellipsis: true,
      render: (v?: string) => v ?? <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: '变更工作流状态', dataIndex: 'draftState', width: 140,
      render: (v: DraftState) => {
        const m = DRAFT_STATE_TAGS[v]
        return <Tag color={m.color}>{m.label}</Tag>
      },
    },
    {
      title: '线上运行基线', width: 140,
      render: (_: unknown, row: ConfigDraftItem) => (
        <Typography.Text type="secondary">
          v{row.baseVersion || '—'}
          {row.baseVersion === 0 ? <Tag style={{ marginLeft: 4 }} color="cyan">新建</Tag> : null}
        </Typography.Text>
      ),
    },
    { title: '草稿版本', dataIndex: 'draftVersion', width: 100, render: (v: number) => `v${v}` },
    {
      title: '最近失败', width: 180,
      render: (_: unknown, row: ConfigDraftItem) => {
        if (!row.failureStage) return <Typography.Text type="secondary">—</Typography.Text>
        const m = FAIL_STAGE_TAGS[row.failureStage]
        return <Space size="small"><Tag color={m.color}>{m.label}</Tag></Space>
      },
    },
    {
      title: '最近驳回原因', width: 200, ellipsis: true,
      render: (_: unknown, row: ConfigDraftItem) =>
        row.rejectionReason
          ? <Typography.Text type="danger" ellipsis>{row.rejectionReason}</Typography.Text>
          : <Typography.Text type="secondary">—</Typography.Text>,
    },
    { title: '最后操作人', dataIndex: 'operatorId', width: 140 },
    { title: '最后更新', width: 170, render: (_: unknown, r: ConfigDraftItem) => dayjs(r.updatedAt).format('YYYY-MM-DD HH:mm:ss') },
    {
      title: '操作', width: 260, fixed: 'right' as const,
      render: (_: unknown, row: ConfigDraftItem) => (
        <Space size="small" wrap>
          <Button size="small" icon={<EditOutlined />} onClick={() => { setCurrent(row); setDetailOpen(true) }}>详情</Button>
          {row.draftState === 'DRAFT' && (
            <Button size="small" icon={<CheckCircleOutlined />} onClick={() => submitApprovalAction(row)}>提交审核</Button>
          )}
          {row.draftState === 'PENDING_APPROVAL' && (
            <Button size="small" type="primary" onClick={() => approveAction(row)}>审批通过</Button>
          )}
          {row.draftState === 'PENDING_APPROVAL' && (
            <Button size="small" danger onClick={() => rejectAction(row)}>驳回</Button>
          )}
          {(row.draftState === 'DRAFT' || row.draftState === 'PENDING_PUBLISH' || row.draftState === 'PUBLISH_FAILED') && (
            <Button size="small" type="primary" icon={<RocketOutlined />} onClick={() => publishAction(row)}>发布</Button>
          )}
          {(row.draftState === 'DRAFT' || row.draftState === 'APPROVAL_REJECTED' || row.draftState === 'PUBLISH_FAILED') && (
            <Button size="small" danger onClick={() => discardAction(row)}>放弃</Button>
          )}
        </Space>
      ),
    },
  ]), [])

  const submitApprovalAction = async (row: ConfigDraftItem) => {
    try { await submitDraftApproval(row.draftId); await afterAction('已提交审核') }
    catch (e) { message.error((e as Error).message) }
  }
  const approveAction = async (row: ConfigDraftItem) => {
    try { await approveDraft(row.draftId); await afterAction('已审批通过') }
    catch (e) { message.error((e as Error).message) }
  }
  const rejectAction = async (row: ConfigDraftItem) => {
    const reason = window.prompt('驳回原因：', '')
    if (reason == null) return
    try { await rejectDraft(row.draftId, reason); await afterAction('已驳回') }
    catch (e) { message.error((e as Error).message) }
  }
  const publishAction = async (row: ConfigDraftItem) => {
    try { await publishDraft(row.draftId); await afterAction('发布请求已发起') }
    catch (e) { message.error((e as Error).message) }
  }
  const discardAction = async (row: ConfigDraftItem) => {
    const reason = window.prompt('放弃原因（可选）：', '')
    if (reason == null) return
    try { await discardDraft(row.draftId, reason); await afterAction('已放弃') }
    catch (e) { message.error((e as Error).message) }
  }

  return (
    <Card
      title={<Space><Typography.Title level={4} style={{ margin: 0 }}>变更草稿列表</Typography.Title>
        <Tag color="gold">变更工作流轨道</Tag></Space>}
      extra={<Button icon={<ReloadOutlined />} onClick={() => load(true)}>刷新</Button>}
    >
      <Tabs
        activeKey={tab}
        onChange={k => setTab(k as TabKey)}
        items={STATE_TABS.map(t => ({ key: t.key, label: t.label }))}
      />
      <Table
        rowKey="draftId"
        columns={columns}
        dataSource={rows}
        loading={loading}
        pagination={false}
        scroll={{ x: 1500 }}
      />

      <DetailModal open={detailOpen} data={current} onClose={() => setDetailOpen(false)} />
    </Card>
  )
}

function DetailModal({ open, data, onClose }: { open: boolean; data: ConfigDraftItem | null; onClose: () => void }) {
  if (!data) return null
  return (
    <Modal title="草稿详情" open={open} onCancel={onClose} footer={null} width={860}>
      <Descriptions column={2} bordered>
        <Descriptions.Item label="资源类型">{data.resourceKind}</Descriptions.Item>
        <Descriptions.Item label="资源 ID">{data.resourceId}</Descriptions.Item>
        <Descriptions.Item label="工作流状态">
          <Tag color={DRAFT_STATE_TAGS[data.draftState].color}>{DRAFT_STATE_TAGS[data.draftState].label}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="线上基线版本">v{data.baseVersion}</Descriptions.Item>
        <Descriptions.Item label="草稿版本">v{data.draftVersion}</Descriptions.Item>
        <Descriptions.Item label="最后操作人">{data.operatorId}</Descriptions.Item>
        {data.failureStage && (
          <>
            <Descriptions.Item label="失败阶段" span={2}>
              <Tag color={FAIL_STAGE_TAGS[data.failureStage].color}>{FAIL_STAGE_TAGS[data.failureStage].label}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="失败明细" span={2}>
              <pre>
                {data.failureDetails ?? '无'}
              </pre>
            </Descriptions.Item>
          </>
        )}
        {data.rejectionReason && (
          <Descriptions.Item label="驳回原因" span={2}>
            <Typography.Text type="danger">{data.rejectionReason}</Typography.Text>
          </Descriptions.Item>
        )}
        <Descriptions.Item label="草稿内容" span={2}>
          <pre style={{
            maxHeight: 300, overflow: 'auto', background: '#f5f5f5', padding: 12, borderRadius: 4, margin: 0,
          }}>
            {data.draftConfig}
          </pre>
        </Descriptions.Item>
      </Descriptions>
    </Modal>
  )
}
