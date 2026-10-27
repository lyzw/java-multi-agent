import { useState } from 'react'
import { Alert, Button, Card, Form, Input, Select, Space, Typography } from 'antd'
import {
  recordEvalSample,
  recordTaskOutcome,
  recordRiskEvent,
  recordFeedback,
  EVAL_VERDICT_LABELS,
  FEEDBACK_KIND_LABELS,
  RISK_EVENT_TYPE_LABELS,
  type EvalVerdict,
  type FeedbackKind,
  type RiskEventType,
  type EvalSampleRequest,
  type TaskOutcomeRequest,
  type RiskEventRequest,
  type FeedbackRequest,
} from '@/api/metricsApi'
import { ApiError } from '@/api/client'

/**
 * 效果数据录入页（P7-3）。
 *
 * ⚠️ **权限**：本页四个录入端点都要求 `platform.resource.manage`（平台权限），
 *    只应在 admin-console 调用。租户侧反馈请走 tenant 端 `/api/metrics/feedback`
 *    （tenantId 由租户令牌派生，不接受请求体里的 tenantId，防伪造）。
 *
 * ⚠️ **合规红线（贯穿四个表单）**：
 *    · 风险事件只接受「摘要」（detailDigest），**绝不接受原文** —— 一旦接受原文落库，
 *      就构成二次泄露：「拦截住了」会变成「存下来了」。
 *    · traceId / tenantId 必填且由录入员显式填写（平台侧可对任一租户的任意 trace 回填）。
 */

type Status = { kind: 'idle' | 'submitting' | 'success' | 'error'; msg?: string }

const YES_NO = [
  { label: '是', value: true },
  { label: '否', value: false },
]

/** 提交结果提示（不含按钮，避免依赖 antd App 上下文）。 */
function StatusAlert({ status }: { status: Status }): JSX.Element | null {
  if (status.kind === 'idle' || status.kind === 'submitting') return null
  if (status.kind === 'success') {
    return <Alert type="success" showIcon message={status.msg ?? '录入成功'} style={{ marginTop: 12 }} />
  }
  return <Alert type="error" showIcon message={status.msg ?? '录入失败'} style={{ marginTop: 12 }} />
}

interface EntryCardProps {
  title: string
  description: string
  status: Status
  onFinish: (values: Record<string, unknown>) => void
  children: React.ReactNode
}

/** 通用录入卡片：标题 + 说明 + 表单 + 提交/重置 + 状态提示。 */
function EntryCard({ title, description, status, onFinish, children }: EntryCardProps): JSX.Element {
  const [form] = Form.useForm()
  const submitting = status.kind === 'submitting'
  return (
    <Card title={title} size="small">
      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
        {description}
      </Typography.Paragraph>
      <Form
        form={form}
        layout="vertical"
        disabled={submitting}
        onFinish={(v) => onFinish(v as Record<string, unknown>)}
      >
        {children}
        <Space>
          <Button type="primary" htmlType="submit" loading={submitting}>
            提交录入
          </Button>
          <Button onClick={() => form.resetFields()} disabled={submitting}>
            重置
          </Button>
        </Space>
      </Form>
      <StatusAlert status={status} />
    </Card>
  )
}

const SHARED_FIELDS = (
  <>
    <Form.Item label="Trace ID" name="traceId" rules={[{ required: true, message: '请填写 Trace ID' }]}>
      <Input placeholder="要与回执/标注关联的 Run Trace" />
    </Form.Item>
    <Form.Item label="租户 ID" name="tenantId" rules={[{ required: true, message: '请填写租户 ID' }]}>
      <Input placeholder="回填数据归属的租户" />
    </Form.Item>
  </>
)

export function MetricEntryPage(): JSX.Element {
  const [evalStatus, setEvalStatus] = useState<Status>({ kind: 'idle' })
  const [outcomeStatus, setOutcomeStatus] = useState<Status>({ kind: 'idle' })
  const [riskStatus, setRiskStatus] = useState<Status>({ kind: 'idle' })
  const [feedbackStatus, setFeedbackStatus] = useState<Status>({ kind: 'idle' })

  const guard = (e: unknown): string => (e instanceof ApiError ? e.message : '网络错误，请稍后重试')

  const submitEval = async (v: Record<string, unknown>): Promise<void> => {
    setEvalStatus({ kind: 'submitting' })
    const payload: EvalSampleRequest = {
      traceId: String(v.traceId),
      tenantId: String(v.tenantId),
      verdict: v.verdict as EvalVerdict,
      hallucination: v.hallucination as boolean | undefined,
      instructionFollowed: v.instructionFollowed as boolean | undefined,
      note: v.note ? String(v.note) : undefined,
    }
    try {
      await recordEvalSample(payload)
      setEvalStatus({ kind: 'success', msg: '抽样标注已录入（L2 黄金基线）' })
    } catch (e) {
      setEvalStatus({ kind: 'error', msg: guard(e) })
    }
  }

  const submitOutcome = async (v: Record<string, unknown>): Promise<void> => {
    setOutcomeStatus({ kind: 'submitting' })
    const payload: TaskOutcomeRequest = {
      traceId: String(v.traceId),
      tenantId: String(v.tenantId),
      checkerCode: String(v.checkerCode),
      externalRef: v.externalRef ? String(v.externalRef) : undefined,
      verified: Boolean(v.verified),
      evidenceDigest: v.evidenceDigest ? String(v.evidenceDigest) : undefined,
    }
    try {
      await recordTaskOutcome(payload)
      setOutcomeStatus({ kind: 'success', msg: '终态校验回执已录入（L1）' })
    } catch (e) {
      setOutcomeStatus({ kind: 'error', msg: guard(e) })
    }
  }

  const submitRisk = async (v: Record<string, unknown>): Promise<void> => {
    setRiskStatus({ kind: 'submitting' })
    const payload: RiskEventRequest = {
      traceId: String(v.traceId),
      tenantId: String(v.tenantId),
      eventType: v.eventType as RiskEventType,
      detailDigest: v.detailDigest ? String(v.detailDigest) : undefined,
    }
    try {
      await recordRiskEvent(payload)
      setRiskStatus({ kind: 'success', msg: '风险事件已录入（仅摘要，无原文）' })
    } catch (e) {
      setRiskStatus({ kind: 'error', msg: guard(e) })
    }
  }

  const submitFeedback = async (v: Record<string, unknown>): Promise<void> => {
    setFeedbackStatus({ kind: 'submitting' })
    const payload: FeedbackRequest = {
      traceId: String(v.traceId),
      tenantId: String(v.tenantId),
      kind: v.kind as FeedbackKind,
    }
    try {
      await recordFeedback(payload)
      setFeedbackStatus({ kind: 'success', msg: '用户反馈已录入（L3 有噪信号）' })
    } catch (e) {
      setFeedbackStatus({ kind: 'error', msg: guard(e) })
    }
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Typography.Title level={4} style={{ margin: 0 }}>
        效果数据录入
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
        手动回填四类效果数据（L1 自动校验回执 / L2 抽样标注 / 风险事件 / L3 用户反馈）。
        这些端点要求平台资源写权限，只在平台控制台调用；租户侧反馈请走租户端反馈入口。
      </Typography.Paragraph>

      <EntryCard
        title="一、抽样标注录入（L2 黄金基线）"
        description="专家对抽样 Trace 标注成功与否，以及是否出现幻觉、是否遵从指令。样本量需 ≥ 30 才足以判断。"
        status={evalStatus}
        onFinish={submitEval}
      >
        {SHARED_FIELDS}
        <Form.Item
          label="标注结论"
          name="verdict"
          rules={[{ required: true, message: '请选择标注结论' }]}
        >
          <Select
            placeholder="SUCCESS / PARTIAL / FAIL"
            options={(Object.keys(EVAL_VERDICT_LABELS) as EvalVerdict[]).map((k) => ({
              label: EVAL_VERDICT_LABELS[k],
              value: k,
            }))}
          />
        </Form.Item>
        <Form.Item label="是否出现幻觉" name="hallucination">
          <Select placeholder="可选" allowClear options={YES_NO} />
        </Form.Item>
        <Form.Item label="是否遵从指令" name="instructionFollowed">
          <Select placeholder="可选" allowClear options={YES_NO} />
        </Form.Item>
        <Form.Item label="备注" name="note">
          <Input.TextArea rows={2} placeholder="可选" />
        </Form.Item>
      </EntryCard>

      <EntryCard
        title="二、终态校验回执录入（L1 自动校验）"
        description="外部校验器对 Run 终态的回执。verified 必填；evidenceDigest 是证据摘要，不得带原文。"
        status={outcomeStatus}
        onFinish={submitOutcome}
      >
        {SHARED_FIELDS}
        <Form.Item
          label="校验器代码"
          name="checkerCode"
          rules={[{ required: true, message: '请填写校验器代码' }]}
        >
          <Input placeholder="标识是哪一道外部校验（如 grammar-check）" />
        </Form.Item>
        <Form.Item label="外部引用" name="externalRef">
          <Input placeholder="可选，外部系统单据号等" />
        </Form.Item>
        <Form.Item
          label="校验通过"
          name="verified"
          rules={[{ required: true, message: '请选择校验结论' }]}
        >
          <Select options={YES_NO} />
        </Form.Item>
        <Form.Item label="证据摘要" name="evidenceDigest">
          <Input placeholder="可选，证据摘要（不得为原文）" />
        </Form.Item>
      </EntryCard>

      <EntryCard
        title="三、风险事件录入"
        description="⚠️ 合规红线：只有摘要（detailDigest），绝不接受原文 —— 一旦接受原文落库，就构成二次泄露。"
        status={riskStatus}
        onFinish={submitRisk}
      >
        {SHARED_FIELDS}
        <Form.Item
          label="事件类型"
          name="eventType"
          rules={[{ required: true, message: '请选择事件类型' }]}
        >
          <Select
            placeholder="SAFETY_BLOCK / UNAUTHORIZED_CALL / SENSITIVE_OUTPUT"
            options={(Object.keys(RISK_EVENT_TYPE_LABELS) as RiskEventType[]).map((k) => ({
              label: RISK_EVENT_TYPE_LABELS[k],
              value: k,
            }))}
          />
        </Form.Item>
        <Form.Item label="摘要（不得为原文）" name="detailDigest">
          <Input.TextArea rows={2} placeholder="仅摘要 / 规则名 / 命中模式代号，绝不粘贴对话或工具原文" />
        </Form.Item>
      </EntryCard>

      <EntryCard
        title="四、用户反馈录入（L3 有噪信号）"
        description="终端用户对某次会话的反馈。下游工具故障同样会导致差评，差评率上涨应触发抽样复核，而非直接判定 Agent 劣化。"
        status={feedbackStatus}
        onFinish={submitFeedback}
      >
        {SHARED_FIELDS}
        <Form.Item
          label="反馈类型"
          name="kind"
          rules={[{ required: true, message: '请选择反馈类型' }]}
        >
          <Select
            placeholder="THUMB_UP / THUMB_DOWN / RETRY / ABANDON"
            options={(Object.keys(FEEDBACK_KIND_LABELS) as FeedbackKind[]).map((k) => ({
              label: FEEDBACK_KIND_LABELS[k],
              value: k,
            }))}
          />
        </Form.Item>
      </EntryCard>
    </Space>
  )
}
