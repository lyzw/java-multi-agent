import { useState } from 'react'
import { Alert, Button, Card, Form, Input, Space, App as AntApp } from 'antd'
import { StopOutlined } from '@ant-design/icons'
import { submitPlatformLifecycleRequest } from '@/api/adminApi'
import { buildLifecycleRequest, type PlatformObjectType } from './platformObjectForm'
import { describePlatformError } from './PlatformPublishPanel'

export interface PlatformDeprecateCardProps {
  /** 对象类型（平台通道只管 tool / skill / mcp_server） */
  type: PlatformObjectType
  /** 对象标识 */
  objectId: string
  /** ⚠️ 必须是对象**当前版本号** —— 版本对不上，后端 CAS 前置条件不满足 */
  version: number
  /**
   * ⚠️ 对象的**真实生命周期状态**（目录接口提供；读失败时为 null）。
   *
   * <p>⚠️ 传进来是为了**如实呈现**与**正确携带 CAS 原状态**：
   * 对象若**早已停用**，页面应当直接说「它已经停用了」，
   * 而不是让操作者填完理由、走到审批才收到一个「原状态不符」——
   * 那读起来像「有人跟我抢」，而真实原因是「它早就停了」。
   */
  lifecycleState: string | null
}

/**
 * 「停用」入口（第三十四批 · C34-3）。
 *
 * <p><b>⚠️ 这里刻意没有「删除」按钮</b>：物理删除**不做** ——
 * 已发布行不可变（DB 触发器），把已发布的平台级对象物理删掉会让
 * 「引用了它的 Skill」变成悬空引用。正确语义是**停用**
 * （迁移到 {@code DEPRECATED}，单向终态），且同样走三步工作流。
 *
 * <p><b>⚠️ 停用也走「申请 → 审批 → 执行」</b>：停用会让引用它的 Skill 失效，
 * 影响面与发布同级 —— 直接改状态列等于绕过双人复核。
 *
 * <p><b>⚠️ CAS 原状态必须来自目录接口的 {@code lifecycleState}</b>，
 * 不得硬编码 {@code PUBLISHED}：对象可能早已停用（迁移是单向终态），
 * 硬编码只会得到一个语义错误的「原状态不符」报错。
 */
export function PlatformDeprecateCard({
  type,
  objectId,
  version,
  lifecycleState,
}: PlatformDeprecateCardProps): JSX.Element {
  const { message } = AntApp.useApp()
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<{ reason: string }>()

  // ⚠️ 已经停用 = 终态，不能再迁移一次（后端也会拒，但页面应当**如实说明**）
  const alreadyDeprecated = lifecycleState === 'DEPRECATED'

  const submit = async (): Promise<void> => {
    let values: { reason: string }
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    const built = buildLifecycleRequest(type, objectId, version, values.reason)
    if (!built.ok) {
      message.error(built.error)
      return
    }
    setSubmitting(true)
    try {
      const req = await submitPlatformLifecycleRequest(built.spec)
      message.success(`停用申请已提交（${req.requestId.slice(0, 8)}…），待审批后执行`)
      form.resetFields()
    } catch (e) {
      message.error(describePlatformError(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Card size="small" title="停用">
      <Alert
        type={alreadyDeprecated ? 'info' : 'warning'}
        showIcon
        style={{ marginBottom: 16 }}
        message={
          alreadyDeprecated
            ? '该对象已停用（DEPRECATED）—— 停用是单向终态，不能再次迁移'
            : '停用 = 迁移到 DEPRECATED，不是物理删除'
        }
        description={
          alreadyDeprecated
            ? '目录接口报告的当前生命周期状态是 DEPRECATED。再次提交停用申请只会得到' +
              '一个「原状态不符」的报错 —— 那读起来像「有人跟我抢」，而真实原因是「它早就停了」。' +
              '如需恢复，请另走发布流程发新版本。'
            : '已发布行不可变（DB 触发器），物理删除会让引用它的对象变成悬空引用。' +
              `停用按当前版本 v${version} 走 CAS 前置条件（期望原状态 ${lifecycleState ?? 'PUBLISHED'}），` +
              '并同样走「申请 → 审批 → 执行」。'
        }
      />
      {/* ⚠️ 刻意给 Form 命名（与发布表单区分）：否则两个表单的 `reason`
          字段会产出**重复的 DOM id**，标签指向错输入框。 */}
      <Form form={form} name="platform-deprecate" layout="vertical">
        <Form.Item
          name="reason"
          label="停用理由"
          rules={[{ required: true, message: '停用理由不能为空（将留痕）' }]}
        >
          <Input.TextArea rows={2} placeholder="为何停用该对象（将留痕）" />
        </Form.Item>
        <Space>
          <Button
            danger
            icon={<StopOutlined />}
            loading={submitting}
            disabled={alreadyDeprecated}
            onClick={() => void submit()}
          >
            提交停用申请
          </Button>
        </Space>
      </Form>
    </Card>
  )
}

export default PlatformDeprecateCard
