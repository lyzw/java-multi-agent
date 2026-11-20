import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button, Card, DatePicker, Descriptions, Input, Modal, Select, Space,
  Table, Tag, Typography, App as AntApp,
} from 'antd'
import type { Dayjs } from 'dayjs'
import dayjs from 'dayjs'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import {
  queryAuditLogs, type AuditEventType, type AuditLogItem,
} from '@/api/publishWorkflow'

const EVENT_TAGS: Record<AuditEventType, { color: string; label: string }> = {
  CREATE:           { color: 'green',  label: '新建' },
  SAVE_DRAFT:       { color: 'blue',   label: '保存草稿' },
  SUBMIT_APPROVAL:  { color: 'cyan',   label: '提交审核' },
  APPROVE:          { color: 'geekblue', label: '审批通过' },
  REJECT:           { color: 'red',    label: '审批驳回' },
  PUBLISH_SUCCESS:  { color: 'green',  label: '发布成功' },
  PUBLISH_FAILED:   { color: 'volcano', label: '发布失败' },
  ROLLBACK:         { color: 'purple', label: '回滚' },
  DISCARD:          { color: 'default', label: '放弃' },
  LIFECYCLE:        { color: 'orange', label: '生命周期' },
}

/**
 * 业务审计日志（cap_audit_log）。
 *
 * <p>⚠️ 与平台系统审计（platform_audit_event）是两条独立日志：
 * 平台系统审计记录"谁登录了 / 租户启停 / 角色变更"；
 * 业务审计记录"Agent 的配置是怎么一步步变成现在这样"。
 */
export function BusinessAuditLogPage(): JSX.Element {
  const {} = AntApp.useApp()
  const [rows, setRows] = useState<AuditLogItem[]>([])
  const [loading, setLoading] = useState(false)
  const [cursor, setCursor] = useState<string | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [detail, setDetail] = useState<AuditLogItem | null>(null)

  const [eventType, setEventType] = useState<AuditEventType | undefined>()
  const [operatorId, setOperatorId] = useState('')
  const [resourceKind, setResourceKind] = useState('')
  const [resourceId, setResourceId] = useState('')
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null)

  const search = useCallback(async (reset = true) => {
    setLoading(true)
    try {
      const page = await queryAuditLogs({
        eventType: eventType ?? undefined,
        operatorId: operatorId || undefined,
        resourceKind: resourceKind || undefined,
        resourceId: resourceId || undefined,
        fromTime: range?.[0]?.toISOString(),
        toTime: range?.[1]?.toISOString(),
        cursor: reset ? undefined : cursor ?? undefined,
        limit: 50,
      })
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
  }, [eventType, operatorId, resourceKind, resourceId, range, cursor])

  useEffect(() => { void search() /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [])

  const columns = useMemo(() => ([
    {
      title: '事件', dataIndex: 'eventType', width: 130,
      render: (v: AuditEventType) => {
        const m = EVENT_TAGS[v] ?? { color: 'default', label: v }
        return <Tag color={m.color}>{m.label}</Tag>
      },
    },
    { title: '资源类型', dataIndex: 'resourceKind', width: 130 },
    { title: '资源 ID', dataIndex: 'resourceId', width: 180, ellipsis: true },
    {
      title: '版本变化', width: 120,
      render: (_: unknown, r: AuditLogItem) => {
        if (r.fromVersion == null && r.toVersion == null) return <Typography.Text type="secondary">—</Typography.Text>
        return <Typography.Text code>
          {r.fromVersion ?? '—'} → {r.toVersion ?? '—'}
        </Typography.Text>
      },
    },
    {
      title: '状态变化', width: 180,
      render: (_: unknown, r: AuditLogItem) => {
        if (!r.fromState && !r.toState) return <Typography.Text type="secondary">—</Typography.Text>
        return <Space size={4}>
          {r.fromState && <Tag color="default">{r.fromState}</Tag>}
          {r.toState && <Tag color="blue">{r.toState}</Tag>}
        </Space>
      },
    },
    { title: '操作人', dataIndex: 'operatorId', width: 140 },
    { title: '时间', width: 180, render: (_: unknown, r: AuditLogItem) => dayjs(r.createdAt).format('YYYY-MM-DD HH:mm:ss') },
    {
      title: '操作', width: 100, fixed: 'right' as const,
      render: (_: unknown, row: AuditLogItem) => (
        <Button size="small" onClick={() => setDetail(row)}>详情</Button>
      ),
    },
  ]), [])

  return (
    <Card
      title={<Typography.Title level={4} style={{ margin: 0 }}>业务审计日志</Typography.Title>}
      extra={<Button icon={<ReloadOutlined />} onClick={() => search(true)}>刷新</Button>}
    >
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          allowClear placeholder="事件类型"
          style={{ width: 180 }}
          options={Object.entries(EVENT_TAGS).map(([k, v]) => ({ value: k, label: v.label }))}
          value={eventType}
          onChange={v => setEventType(v as AuditEventType | undefined)}
        />
        <Input placeholder="操作人 ID" style={{ width: 160 }} value={operatorId} onChange={e => setOperatorId(e.target.value)} />
        <Input placeholder="资源类型" style={{ width: 140 }} value={resourceKind} onChange={e => setResourceKind(e.target.value)} />
        <Input placeholder="资源 ID" style={{ width: 180 }} value={resourceId} onChange={e => setResourceId(e.target.value)} />
        <DatePicker.RangePicker
          showTime
          value={range}
          onChange={v => setRange(v as any)}
        />
        <Button type="primary" icon={<SearchOutlined />} onClick={() => search(true)}>查询</Button>
      </Space>

      <Table
        rowKey="auditId"
        columns={columns}
        dataSource={rows}
        loading={loading}
        pagination={false}
        scroll={{ x: 1300 }}
      />
      {hasMore && (
        <div style={{ textAlign: 'center', marginTop: 16 }}>
          <Button onClick={() => search(false)} loading={loading}>加载更多</Button>
        </div>
      )}

      <Modal
        title="审计详情"
        open={!!detail}
        onCancel={() => setDetail(null)}
        footer={null}
        width={900}
      >
        {detail && (
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="事件">
              {(() => { const m = EVENT_TAGS[detail.eventType] ?? { color: 'default', label: detail.eventType }; return <Tag color={m.color}>{m.label}</Tag> })()}
            </Descriptions.Item>
            <Descriptions.Item label="时间">{detail.createdAt}</Descriptions.Item>
            <Descriptions.Item label="资源类型">{detail.resourceKind}</Descriptions.Item>
            <Descriptions.Item label="资源 ID">{detail.resourceId}</Descriptions.Item>
            <Descriptions.Item label="操作人">{detail.operatorId}</Descriptions.Item>
            <Descriptions.Item label="版本">{detail.fromVersion ?? '—'} → {detail.toVersion ?? '—'}</Descriptions.Item>
            {detail.reason && <Descriptions.Item label="原因" span={2}>{detail.reason}</Descriptions.Item>}
            {detail.fromState && <Descriptions.Item label="变更前状态">{detail.fromState}</Descriptions.Item>}
            {detail.toState && <Descriptions.Item label="变更后状态">{detail.toState}</Descriptions.Item>}
            {detail.beforeConfig && (
              <Descriptions.Item label="变更前配置" span={2}>
                <CodeBlock text={detail.beforeConfig} />
              </Descriptions.Item>
            )}
            {detail.afterConfig && (
              <Descriptions.Item label="变更后配置" span={2}>
                <CodeBlock text={detail.afterConfig} />
              </Descriptions.Item>
            )}
            {detail.diffSummary && (
              <Descriptions.Item label="变更摘要" span={2}>
                <CodeBlock text={detail.diffSummary} />
              </Descriptions.Item>
            )}
          </Descriptions>
        )}
      </Modal>
    </Card>
  )
}

function CodeBlock({ text }: { text: string }) {
  return (
    <pre style={{
      background: '#f5f5f5', borderRadius: 4, padding: 8,
      maxHeight: 320, overflow: 'auto', margin: 0,
    }}>
      {text}
    </pre>
  )
}
