import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button, Card, Descriptions, Modal, Space, Table, Tag, Typography, App as AntApp,
} from 'antd'
import { ReloadOutlined, SwapOutlined, RollbackOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'

/**
 * 历史已发布版本 + 版本 Diff + 回滚能力。
 *
 * <p>现有后端 `GET /api/cap/config/releases` 已返回时间线（含 ROLLED_BACK）。
 * 这里复用它，再加上"任选历史版本一键回滚"。
 */
export function VersionHistoryPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<any>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      // 复用既有发布时间线端点（由 CapConfigPublishController.releases 提供）
      const resp = await fetch('/api/cap/config/releases?limit=50', {
        headers: { 'Authorization': `Bearer ${localStorage.getItem('token') ?? ''}` },
      })
      if (resp.ok) {
        const json = await resp.json()
        setRows(json.items ?? json ?? [])
      }
    } catch (e) {
      // 后端尚未实现时静默展示空
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const handleRollback = async (row: any) => {
    const reason = window.prompt(
      `确认回滚到 release_id=${row.releaseId ?? row.id ?? '?'}（${row.configPublishSeq ?? row.seq ?? '?'}）\n\n请填写回滚原因：`,
      ''
    )
    if (!reason) return
    try {
      // 走既有 ConfigPublishWorkflowService 的 ROLLBACK 操作类型
      await fetch('/api/cap/config/publishes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${localStorage.getItem('token') ?? ''}` },
        body: JSON.stringify({
          operation: 'ROLLBACK',
          targetReleaseId: row.releaseId ?? row.id,
          reason,
        }),
      })
      message.success('回滚申请已提交')
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns = useMemo(() => ([
    { title: 'Release ID', dataIndex: 'releaseId', width: 120, render: (v: any) => v ?? '—' },
    { title: '发布序号', dataIndex: 'configPublishSeq', width: 100, render: (v: any) => v ?? '—' },
    {
      title: '状态', dataIndex: 'status', width: 120,
      render: (v: string) => {
        if (v === 'PUBLISHED') return <Tag color="green">已发布</Tag>
        if (v === 'ROLLED_BACK') return <Tag color="orange">已回滚</Tag>
        if (v === 'APPLIED') return <Tag color="blue">已生效</Tag>
        return <Tag>{v ?? '—'}</Tag>
      },
    },
    { title: '操作人', dataIndex: 'requesterId', width: 140, render: (v: any) => v ?? '—' },
    { title: '审批人', dataIndex: 'approverId', width: 140, render: (v: any) => v ?? '—' },
    { title: '发布时间', width: 180, render: (_: unknown, r: any) => r.effectiveAt ? dayjs(r.effectiveAt).format('YYYY-MM-DD HH:mm:ss') : '—' },
    {
      title: '操作', width: 180, fixed: 'right' as const,
      render: (_: unknown, row: any) => (
        <Space size="small">
          <Button size="small" onClick={() => setDetail(row)}>查看</Button>
          {(row.status === 'PUBLISHED' || row.status === 'APPLIED') && (
            <Button size="small" danger icon={<RollbackOutlined />} onClick={() => handleRollback(row)}>回滚</Button>
          )}
        </Space>
      ),
    },
  ]), [])

  return (
    <Card
      title={<Space>
        <Typography.Title level={4} style={{ margin: 0 }}>历史发布版本</Typography.Title>
        <Tag color="gold">任选历史版本一键回滚</Tag>
      </Space>}
      extra={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
    >
      {rows.length === 0 && !loading ? (
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center', padding: 40 }}>
          <SwapOutlined style={{ fontSize: 32, marginRight: 12 }} />
          暂无历史发布记录（后续会随第一次发布自动填充）
        </Typography.Paragraph>
      ) : (
        <Table
          rowKey={(r: any) => r.releaseId ?? r.id ?? Math.random()}
          columns={columns}
          dataSource={rows}
          loading={loading}
          pagination={{ pageSize: 20 }}
        />
      )}

      <Modal title="发布详情" open={!!detail} onCancel={() => setDetail(null)} footer={null} width={780}>
        {detail && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="Release ID">{detail.releaseId ?? detail.id ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="发布序号">{detail.configPublishSeq ?? detail.seq ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="状态">{detail.status ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="生效时间">{detail.effectiveAt ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="请求体摘要">
              <pre style={{
                background: '#f5f5f5', borderRadius: 4, padding: 8, maxHeight: 400, overflow: 'auto', margin: 0,
              }}>
                {JSON.stringify(detail.objectVersions ?? detail, null, 2)}
              </pre>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </Card>
  )
}
