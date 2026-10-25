import { useState, type ReactNode } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Radio,
  Space,
  Tag,
  App as AntApp,
  type FormInstance,
} from 'antd'
import { SendOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  applyPlatformPublishRequest,
  decidePlatformPublishRequest,
  submitPlatformPublishRequest,
} from '@/api/adminApi'
import type { PlatformPublishRequest, PlatformRequestItem } from '@/api/types'

/**
 * 把平台级通道的错误翻译成**可区分**的提示。
 *
 * <p>⚠️ **四个状态码对应四件完全不同的事**，绝不能合并成一句「操作失败」：
 * <ul>
 *   <li><b>400</b> = 门禁预检拒绝（参数/形状非法）—— 作者能改；</li>
 *   <li><b>403</b> = 不是 PLATFORM_ADMIN —— 作者改不了，得换人；</li>
 *   <li><b>409</b> = 自审批 / 状态冲突 —— 流程问题，不是参数问题；</li>
 *   <li><b>503</b> = 平台运维角色未接入 —— <b>环境问题，不是用户的错</b>。</li>
 * </ul>
 */
export function describePlatformError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 400) return '申请被门禁预检拒绝（400）：请检查填写内容与引用完整性'
    if (e.status === 403) return '权限不足（403）：平台级配置仅限 PLATFORM_ADMIN'
    if (e.status === 409) return '状态冲突（409）：不得审批自己提交的申请，或申请已被处理'
    if (e.status === 503) return '平台运维通道未接入（503）：这是环境问题，请联系平台运维'
    if (e.status === 404) return '资源不存在（404）'
  }
  return e instanceof Error ? e.message : '操作失败'
}

export interface PlatformPublishPanelProps<TValues> {
  /** 表单实例（由调用方持有，以便在「登记」与「修改」之间切换初值） */
  form: FormInstance<TValues>
  /**
   * 表单取值 → 发布申请体。
   *
   * ⚠️ 返回 `{ error }` 表示**本地**校验未过（形状非法 / 闭包不完整）——
   * 本地先拒是为了让作者当场看到，而不是占住一个审批名额后才被服务端拒。
   * ⚠️ 允许返回 Promise：平台级发布集合必须构成**自洽闭包**，
   * 补齐闭包成员需要读取被引用对象的当前定义（一次网络往返）。
   */
  buildRequest: (
    values: TValues,
  ) => PlatformPublishRequest | { error: string } | Promise<PlatformPublishRequest | { error: string }>
  /** 表单字段（必须渲染在 `<Form>` 内部） */
  renderFields: () => ReactNode
  title: string
  submitText?: string
}

/**
 * 平台级发布三步工作流（申请 → 审批 → 执行）。
 *
 * <p><b>⚠️ 三步不可合并</b>：把申请人与审批人两个 id 放进一次请求由调用方填写，
 * 双人复核就退化成「调用方自己声明另一个人同意了」。平台级对象影响**全平台**，
 * 比租户级更需要复核。
 *
 * <p><b>⚠️ 审批与执行是两步，且不是同一个人</b>：本页把三步并列呈现，
 * 是为了让「我批了」与「配置真的变了」在界面上可区分 ——
 * 审批通过（APPROVED）时配置**尚未**改变。
 *
 * <p><b>⚠️ 平台级申请单不在租户发布队列里</b>（那是 {@code /api/cap/config/publishes}），
 * 故本页自带三步闭环，不跳转到发布中心。
 */
export function PlatformPublishPanel<TValues>({
  form,
  buildRequest,
  renderFields,
  title,
  submitText = '提交发布申请',
}: PlatformPublishPanelProps<TValues>): JSX.Element {
  const { message } = AntApp.useApp()
  const [request, setRequest] = useState<PlatformRequestItem | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [deciding, setDeciding] = useState(false)
  const [applying, setApplying] = useState(false)
  const [decision, setDecision] = useState<'APPROVE' | 'REJECT'>('APPROVE')
  const [note, setNote] = useState('')

  const submit = async (): Promise<void> => {
    let values: TValues
    try {
      values = await form.validateFields()
    } catch {
      return // 表单校验未过
    }
    // ⚠️ `buildRequest` 会去读闭包成员的当前定义（网络请求）——
    //    它**可能 reject**（网络抖动 / 解析异常）。此处必须 try/catch：
    //    否则 rejection 会变成 unhandled rejection，页面**零反馈**
    //    （用户只看到「点了没反应」），而异常只在控制台里。
    let body: PlatformPublishRequest | { error: string }
    try {
      body = await buildRequest(values)
    } catch (e) {
      message.error(
        e instanceof Error ? `构造发布请求失败：${e.message}` : '构造发布请求失败（未知原因）',
      )
      return
    }
    if ('error' in body) {
      message.error(body.error)
      return
    }
    setSubmitting(true)
    try {
      const created = await submitPlatformPublishRequest(body)
      setRequest(created)
      message.success(`申请已提交（${created.requestId.slice(0, 8)}…），待审批后执行`)
    } catch (e) {
      message.error(describePlatformError(e))
    } finally {
      setSubmitting(false)
    }
  }

  const decide = async (): Promise<void> => {
    if (!request) return
    setDeciding(true)
    try {
      const updated = await decidePlatformPublishRequest(request.requestId, {
        decision,
        note: note.trim() ? note.trim() : undefined,
      })
      setRequest(updated)
      message.success(decision === 'APPROVE' ? '已批准' : '已驳回')
    } catch (e) {
      message.error(describePlatformError(e))
    } finally {
      setDeciding(false)
    }
  }

  const apply = async (): Promise<void> => {
    if (!request) return
    setApplying(true)
    try {
      const updated = await applyPlatformPublishRequest(request.requestId)
      setRequest(updated)
      message.success('已执行生效')
    } catch (e) {
      message.error(describePlatformError(e))
    } finally {
      setApplying(false)
    }
  }

  return (
    <Card title={title}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="申请 → 审批 → 执行（三步不可合并）"
        description={
          '提交只生成申请单，不写配置表；审批人不得等于申请人；执行才真正写入平台配置。' +
          '平台级对象影响全平台，变更需 PLATFORM_ADMIN 身份。'
        }
      />
      {/* ⚠️ 刻意给 Form 命名：antd 用 `name` 给字段 id 加前缀。
          两个表单都含 `reason` 字段时，不加前缀会产出**重复的 DOM id**，
          标签与输入框的关联就指错了地方（点「停用理由」会聚焦到发布表单的理由框）。 */}
      <Form form={form} name="platform-publish" layout="vertical">
        {renderFields()}
        <Form.Item
          name="reason"
          label="变更理由"
          rules={[{ required: true, message: '变更理由不能为空（将留痕）' }]}
        >
          <Input.TextArea rows={2} placeholder="为何要做这次变更（将留痕）" />
        </Form.Item>
        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={submitting}
          onClick={() => void submit()}
        >
          {submitText}
        </Button>
      </Form>

      {request && (
        <div style={{ marginTop: 24 }}>
          <Alert
            type={request.state === 'APPLIED' ? 'success' : request.state === 'FAILED' ? 'error' : 'warning'}
            showIcon
            style={{ marginBottom: 16 }}
            message={`平台级申请 ${request.requestId}`}
            description={
              request.state === 'APPLIED'
                ? '执行完成，平台配置已改变。'
                : request.state === 'APPROVED'
                  ? '审批已通过，但配置尚未改变 —— 需第三步「执行」才写入。'
                  : request.state === 'REJECTED'
                    ? '申请已被驳回（终态）。'
                    : request.state === 'FAILED'
                      ? '执行失败（终态，不自动重试）。'
                      : '等待审批（申请人不得审批本人提交的申请）。'
            }
          />
          <Descriptions bordered column={2} size="small" items={[
            { key: 'state', label: '流程状态', children: <Tag color="blue">{request.state}</Tag> },
            { key: 'requester', label: '申请人', children: request.requesterId },
            { key: 'approver', label: '审批人', children: request.approverId ?? '-' },
            { key: 'requestedAt', label: '申请时间', children: request.requestedAt },
            { key: 'decidedAt', label: '审批时间', children: request.decidedAt ?? '-' },
            { key: 'appliedAt', label: '生效时间', children: request.appliedAt ?? '-' },
            { key: 'note', label: '审批意见', children: request.decisionNote ?? '-', span: 2 },
          ]} />
          {request.failureReason && (
            <Alert
              style={{ marginTop: 12 }}
              type="error"
              showIcon
              message="执行失败原因"
              description={request.failureReason}
            />
          )}

          {request.state === 'PENDING' && (
            <Card size="small" title="第二步：审批" style={{ marginTop: 16 }}>
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <Radio.Group
                  value={decision}
                  onChange={(e) => setDecision(e.target.value as 'APPROVE' | 'REJECT')}
                >
                  <Radio value="APPROVE">批准</Radio>
                  <Radio value="REJECT">驳回</Radio>
                </Radio.Group>
                <Input.TextArea
                  rows={2}
                  placeholder="审批意见（可选，将留痕）"
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                />
                <Button type="primary" loading={deciding} onClick={() => void decide()}>
                  提交审批决定
                </Button>
              </Space>
            </Card>
          )}

          {request.state === 'APPROVED' && (
            <Card size="small" title="第三步：执行" style={{ marginTop: 16 }}>
              <Button type="primary" loading={applying} onClick={() => void apply()}>
                执行变更
              </Button>
            </Card>
          )}
        </div>
      )}
    </Card>
  )
}

export default PlatformPublishPanel
