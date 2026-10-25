import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  App as AntApp,
  Alert,
  Button,
  Card,
  Form,
  Input,
  Space,
  Typography,
} from 'antd'
import { SendOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { submitPublishRequest } from '@/api/adminApi'
import { isValidJson } from '../agentDefinition'
import type { PublishResultContractSpec } from '@/api/types'

/**
 * 输出契约登记（多级 Agent v1.1 · 第二十九批 · C29-1 · A6）。
 *
 * <p><b>⚠️ 契约是独立对象</b>：按 {@code (tenant_id, contract_id, version)} 寻址，
 * <b>不属于任何 Agent</b>。所以它有独立的登记页，而不是挂在某个 Agent 的表单里 ——
 * 一个契约可能被多个 Agent 引用，让作者「顺手在某个 Agent 里登记」会诱发重复登记。
 *
 * <p><b>⚠️ 走同一条三步工作流</b>（申请 → 双人复核 → 执行）：
 * 契约的登记与 Agent 的发布是<b>同一类变更</b>（都是写配置表），
 * 故复用 {@code /api/cap/config/publishes}，而不是另开一条旁路 ——
 * 旁路会绕过双人复核，而「谁能写配置」正是双人复核要管的事。
 *
 * <p><b>⚠️ 版本号与内容指纹由服务端决定</b>：客户端若能指定它们，
 * 就等于能指定「内容没变」这个证明。故本表单只收「标识 + 契约定义」。
 *
 * <p><b>⚠️ 契约 id 是可读字符串，不是 UUID</b>：对外契约
 * （{@code common.schema.json#/$defs/id}）把 id 定义为可读字符串，
 * 曾声明成 UUID 导致非 UUID 形式的契约在发布链路整笔回滚（V23 已修）。
 */
export function ResultContractPage(): JSX.Element {
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<{
    contractId: string
    schemaJson: string
    reason: string
  }>()

  const submit = async (): Promise<void> => {
    let values: { contractId: string; schemaJson: string; reason: string }
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    const schemaJson = values.schemaJson.trim()
    // ⚠️ 契约定义必须是合法 JSON —— 非法 JSON 会在服务端发布期被拒，
    //    但那时作者已经填完理由、走到审批了，反馈太晚。
    if (!isValidJson(schemaJson)) {
      message.error('契约定义不是合法 JSON')
      return
    }
    const spec: PublishResultContractSpec = {
      contractId: values.contractId.trim(),
      schemaJson,
    }
    setSubmitting(true)
    try {
      const req = await submitPublishRequest({
        operation: 'PUBLISH',
        scope: 'TENANT',
        resultContracts: [spec],
        reason: values.reason.trim(),
      })
      message.success(`契约登记申请已提交（${req.requestId.slice(0, 8)}…），待双人复核后执行`)
      form.resetFields()
      navigate('/publish')
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        message.error('申请被门禁预检拒绝（400）：请检查契约标识与定义')
      } else if (e instanceof ApiError && e.status === 403) {
        message.error('作用域不被允许（403）')
      } else {
        message.error(e instanceof Error ? e.message : '提交申请失败')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Typography.Title level={4}>输出契约登记</Typography.Title>
      <Alert
        type="info"
        showIcon
        message="申请 → 双人复核 → 执行"
        description={
          '契约是独立对象（不属于任何 Agent），按「契约标识 + 固定版本」寻址。' +
          '登记后，Agent 的编排声明才能引用它（resultContractRef = 标识@版本）。' +
          '提交只生成申请单，不写配置表。'
        }
      />
      <Card title="登记输出契约">
        <Form
          form={form}
          layout="vertical"
          initialValues={{ contractId: '', schemaJson: '{\n  "$schema": "https://json-schema.org/draft/2020-12/schema"\n}' }}
        >
          <Form.Item
            name="contractId"
            label="契约标识（contractId · 可读字符串）"
            rules={[{ required: true, message: '请输入契约标识' }]}
            extra="不是 UUID；如 answer-contract。版本号由服务端决定"
          >
            <Input placeholder="如 answer-contract" />
          </Form.Item>
          <Form.Item
            name="schemaJson"
            label="契约定义（schemaJson · JSON Schema）"
            rules={[{ required: true, message: '契约定义不能为空' }]}
            extra="这是契约定义本身（JSON Schema），不是契约实例"
          >
            <Input.TextArea rows={12} style={{ fontFamily: 'monospace', fontSize: 12 }} />
          </Form.Item>
          <Form.Item
            name="reason"
            label="变更理由"
            rules={[{ required: true, message: '变更理由不能为空（将留痕）' }]}
          >
            <Input.TextArea rows={2} placeholder="为何要登记这份契约" />
          </Form.Item>
          <Button
            type="primary"
            icon={<SendOutlined />}
            loading={submitting}
            onClick={() => void submit()}
          >
            提交登记申请
          </Button>
        </Form>
      </Card>
    </Space>
  )
}

export default ResultContractPage
