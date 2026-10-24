import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
  App as AntApp,
} from 'antd'
import { ReloadOutlined, RollbackOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { getPublishPointer, getPublishReleases, submitPublishRequest } from '@/api/adminApi'
import type { PublishPointer, PublishRelease } from '@/api/types'
import { releaseStatusTag } from './publishBits'

/**
 * 发布时间线 + 回滚（P2 · M6）。
 *
 * <p>⚠️ <b>回滚不覆盖历史</b>：选择历史 release 只是把指针指向它
 * （服务端会打 ROLLED_BACK 审计旁路标记），历史内容一字不改。
 *
 * <p>⚠️ <b>回滚不恢复已撤销的授权</b>：它只改配置指针，不 resurrect 任何主体授权。
 *
 * <p>⚠️ 回滚同样走三步工作流：本页只<b>提交申请</b>，审批与执行在发布中心完成。
 */
export function PublishReleasesPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [items, setItems] = useState<PublishRelease[]>([])
  const [pointer, setPointer] = useState<PublishPointer | null>(null)
  const [loading, setLoading] = useState(true)
  const [target, setTarget] = useState<PublishRelease | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [reason, setReason] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [page, ptr] = await Promise.all([
        getPublishReleases({ limit: 50 }),
        getPublishPointer(),
      ])
      setItems(page.items)
      setPointer(ptr)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const submitRollback = async (): Promise<void> => {
    if (!target) return
    if (!reason.trim()) {
      message.error('变更理由不能为空')
      return
    }
    setSubmitting(true)
    try {
      await submitPublishRequest({
        operation: 'ROLLBACK',
        scope: 'TENANT',
        targetReleaseId: Number(target.releaseId),
        expectedPublishSeq: pointer?.currentPublishSeq ?? null,
        reason: reason.trim(),
      })
      message.success('回滚申请已提交，请在发布中心完成审批与执行')
      setTarget(null)
      setReason('')
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        message.error('冲突（409）：目标发布或基线已变化，请刷新后重试')
      } else {
        message.error(e instanceof Error ? e.message : '提交回滚申请失败')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div>
      <Typography.Title level={3}>
        <Space>
          <RollbackOutlined /> 发布时间线
        </Space>
      </Typography.Title>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="时间线含已回滚发布（ROLLED_BACK 仅作审计旁路，不参与当前版本解析）"
        description="回滚只是把指针指向历史 release，历史内容一字不改；也不恢复任何已撤销的主体授权。"
      />

      <Card
        size="small"
        style={{ marginBottom: 16 }}
        extra={
          <Button icon={<ReloadOutlined />} size="small" onClick={() => void load()}>
            刷新
          </Button>
        }
      >
        <Table<PublishRelease>
          rowKey="releaseId"
          loading={loading}
          dataSource={items}
          pagination={false}
          locale={{ emptyText: '尚无发布记录' }}
          columns={[
            {
              title: '发布序号',
              dataIndex: 'configPublishSeq',
              render: (seq: string, row) => (
                <Space>
                  <Typography.Text code>#{seq}</Typography.Text>
                  {pointer != null &&
                    pointer.currentPublishSeq != null &&
                    Number(seq) === pointer.currentPublishSeq && (
                      <Tag color="blue">当前</Tag>
                    )}
                  {row.status === 'ROLLED_BACK' && <Tag>已回滚</Tag>}
                </Space>
              ),
              width: 160,
            },
            {
              title: '状态',
              dataIndex: 'status',
              render: releaseStatusTag,
              width: 100,
            },
            {
              title: '申请人',
              dataIndex: 'requesterId',
              render: (v: string | null) => (v ? <Typography.Text code>{v}</Typography.Text> : '-'),
            },
            {
              title: '审批人',
              dataIndex: 'approverId',
              render: (v: string | null) => (v ? <Typography.Text code>{v}</Typography.Text> : '-'),
            },
            { title: '生效时间', dataIndex: 'effectiveAt', render: (v: string | null) => v ?? '-' },
            { title: '创建时间', dataIndex: 'createdAt' },
            {
              title: '聚合指纹',
              dataIndex: 'aggregateFingerprint',
              render: (v: string | null) =>
                v ? <Typography.Text code style={{ fontSize: 12 }}>{v.slice(0, 16)}…</Typography.Text> : '-',
            },
            {
              title: '操作',
              key: 'actions',
              width: 90,
              render: (_: unknown, row) =>
                row.status === 'PUBLISHED' ? (
                  <Button size="small" onClick={() => setTarget(row)}>
                    回滚到此
                  </Button>
                ) : (
                  '-'
                ),
            },
          ]}
        />
      </Card>

      <Modal
        title={`提交回滚申请 → 发布 #${target?.configPublishSeq ?? ''}`}
        open={target != null}
        onCancel={() => {
          setTarget(null)
          setReason('')
        }}
        onOk={() => void submitRollback()}
        confirmLoading={submitting}
        okText="提交申请"
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="回滚同样需要双人复核"
          description="本页只提交申请；审批与执行在发布中心完成。回滚仅改变配置指针，不恢复已撤销授权、不删除历史记录。"
        />
        <Form layout="vertical">
          <Form.Item label="变更理由" required>
            <Input.TextArea
              rows={3}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="为何回滚到该发布（将留痕）"
            />
          </Form.Item>
          <Form.Item label="CAS 基线（expectedPublishSeq）">
            <Typography.Text code>
              {pointer?.currentPublishSeq ?? '尚未发布'}
            </Typography.Text>
            <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
              以服务端执行时读到的当前值为准
            </Typography.Text>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default PublishReleasesPage
