import { useCallback, useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Radio,
  Space,
  Spin,
  Typography,
  App as AntApp,
} from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  applyPublishRequest,
  decidePublishRequest,
  getPublishRequest,
} from '@/api/adminApi'
import { useSession } from '@/auth/session'
import type { PublishRequestItem } from '@/api/types'
import { DualReviewNotice, isSelfApproval, operationTag, requestStateTag } from './publishBits'

/**
 * 申请详情 / 审批页（P2 · M6）。
 *
 * <p>⚠️ <b>同人审批前端预检</b>：会话里的 principalId 与申请人相同时，
 * 直接禁用审批按钮 —— 但这是<b>提示</b>不是边界，真正的拦截在后端
 * （应用层比对 + DB CHECK 双保险，返回 409）。
 */
export function PublishRequestDetailPage(): JSX.Element {
  const { rid = '' } = useParams<{ rid: string }>()
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const { session } = useSession()
  const myPrincipalId = session?.principalId ?? null

  const [item, setItem] = useState<PublishRequestItem | null>(null)
  const [loading, setLoading] = useState(true)
  const [missing, setMissing] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<{ decision: 'APPROVE' | 'REJECT'; note?: string }>()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await getPublishRequest(rid)
      setItem(r)
      setMissing(false)
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setMissing(true)
      } else {
        message.error(e instanceof Error ? e.message : '加载申请失败')
      }
    } finally {
      setLoading(false)
    }
  }, [rid, message])

  useEffect(() => {
    void load()
  }, [load])

  const decide = async (): Promise<void> => {
    let values: { decision: 'APPROVE' | 'REJECT'; note?: string }
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setSubmitting(true)
    try {
      await decidePublishRequest(rid, {
        decision: values.decision,
        note: values.note?.trim() ? values.note.trim() : undefined,
      })
      message.success(values.decision === 'APPROVE' ? '已批准' : '已驳回')
      form.resetFields()
      await load()
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        message.error('不得审批自己提交的变更申请（409）')
      } else {
        message.error(e instanceof Error ? e.message : '审批失败')
      }
    } finally {
      setSubmitting(false)
    }
  }

  const apply = async (): Promise<void> => {
    setSubmitting(true)
    try {
      await applyPublishRequest(rid)
      message.success('已执行生效')
      await load()
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        message.error('状态冲突或基线过期（409），请刷新后重试')
      } else if (e instanceof ApiError && e.status === 403) {
        message.error('作用域不被允许（403）：平台级发布走运维通道')
      } else {
        message.error(e instanceof Error ? e.message : '执行失败')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) return <Spin spinning style={{ width: '100%', marginTop: 80 }} />

  if (missing || !item) {
    return (
      <div>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/publish')}>
          返回发布中心
        </Button>
        <Alert
          style={{ marginTop: 16 }}
          type="warning"
          showIcon
          message="申请不存在或无权限"
          description="404 不区分「不存在」与「不属于本租户」，避免存在性探测。"
        />
      </div>
    )
  }

  const isSelf = isSelfApproval(item.requesterId, myPrincipalId)

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/publish')}>
          返回发布中心
        </Button>
      </Space>
      <Typography.Title level={3}>
        <Space>
          变更申请 <Typography.Text type="secondary" code>{item.requestId}</Typography.Text>
        </Space>
      </Typography.Title>

      <Card size="small" style={{ marginBottom: 16 }}>
        <Descriptions bordered column={2} size="small" items={[
          { key: 'op', label: '变更类型', children: operationTag(item.operation) },
          { key: 'state', label: '流程状态', children: requestStateTag(item.state) },
          { key: 'requester', label: '申请人', children: <Typography.Text code>{item.requesterId}</Typography.Text> },
          {
            key: 'approver',
            label: '审批人',
            children: item.approverId
              ? <Typography.Text code>{item.approverId}</Typography.Text>
              : '-',
          },
          { key: 'requestedAt', label: '申请时间', children: item.requestedAt },
          { key: 'decidedAt', label: '审批时间', children: item.decidedAt ?? '-' },
          { key: 'appliedAt', label: '生效时间', children: item.appliedAt ?? '-' },
          { key: 'note', label: '审批意见', children: item.decisionNote ?? '-' },
        ]} />
        {item.failureReason && (
          <Alert
            style={{ marginTop: 12 }}
            type="error"
            showIcon
            message="执行失败原因（FAILED 为终态，不自动重试）"
            description={item.failureReason}
          />
        )}
      </Card>

      {item.state === 'PENDING' && (
        <Card size="small" title="审批" style={{ marginBottom: 16 }}>
          <Alert
            type={isSelf ? 'error' : 'warning'}
            showIcon
            style={{ marginBottom: 16 }}
            message={isSelf ? '你提交的申请不能由本人审批' : '双人复核'}
            description={
              isSelf
                ? '当前会话主体与申请人相同（后端同样会以 409 拒绝）。请换一位持有 config:publish 权限的主体登录审批。'
                : '审批人不需持有额外权限——与申请人持有同一个动作（config:publish），「不得自批」由身份比对强制。'
            }
          />
          <Form form={form} layout="vertical" initialValues={{ decision: 'APPROVE' }}>
            <Form.Item name="decision" label="决定" rules={[{ required: true }]}>
              <Radio.Group disabled={isSelf}>
                <Radio value="APPROVE">批准</Radio>
                <Radio value="REJECT">驳回</Radio>
              </Radio.Group>
            </Form.Item>
            <Form.Item name="note" label="审批意见（可选）">
              <Input.TextArea rows={3} placeholder="批准 / 驳回的理由（将留痕）" disabled={isSelf} />
            </Form.Item>
            <Button
              type="primary"
              loading={submitting}
              disabled={isSelf}
              onClick={() => void decide()}
            >
              提交审批决定
            </Button>
          </Form>
          <div style={{ marginTop: 12 }}>
            <DualReviewNotice />
          </div>
        </Card>
      )}

      {item.state === 'APPROVED' && (
        <Card size="small" title="执行" style={{ marginBottom: 16 }}>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="审批已通过，但配置尚未改变"
            description="第三步「执行」才真正写入配置表。执行时服务端会再次强校验 CAS 基线，过期即 409。"
          />
          <Button type="primary" loading={submitting} onClick={() => void apply()}>
            执行变更
          </Button>
        </Card>
      )}
    </div>
  )
}

export default PublishRequestDetailPage
