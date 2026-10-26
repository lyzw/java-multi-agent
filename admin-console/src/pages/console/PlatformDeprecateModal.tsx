/**
 * 平台能力对象的「停用」弹窗（Tool / Skill / MCP 目录行 PUBLISHED 共用）。
 *
 * <p>语义与租户链 detail 页的 {@code PlatformDeprecateCard} 一致：
 * <b>停用不是物理删除</b> —— 已发布行不可变（DB 触发器），正确语义是
 * 迁移到 {@code DEPRECATED}（单向终态），且仍走
 * 「申请 → 审批 → 执行」三步工作流（PLATFORM_ADMIN 闸门）。
 */

import { useEffect, useState } from 'react'
import { App as AntApp, Form, Input, Modal } from 'antd'
import { submitPlatformLifecycleRequest } from '@/api/adminApi'
import type { PlatformRequestItem } from '@/api/types'
import { describePlatformError } from '@/pages/catalog/PlatformPublishPanel'

/** 本页聚焦的三类对象（平台通道只管这三类）。 */
export type PlatformObjectType = 'tool' | 'skill' | 'mcp_server'

export interface PlatformDeprecateModalProps {
  /** 'tool' | 'skill' | 'mcp_server'（lifecycle-requests 的 type） */
  objectType: PlatformObjectType
  /** 对象标识（toolId / skillId / serverId） */
  objectId: string | null
  /** ⚠️ 对象**当前版本号** —— CAS 前置条件，版本对不上后端会拒 */
  version: number | null
  /** 弹窗是否打开（通常 = objectId != null） */
  open: boolean
  onClose: () => void
  /** 提交成功后的回调（页面可刷新队列提示） */
  onSubmitted?: (req: PlatformRequestItem) => void
}

/**
 * 停用申请弹窗：填理由 → 按**当前版本号** + {@code expectedFrom: 'PUBLISHED'}
 * + {@code to: 'DEPRECATED'} 构造生命周期迁移申请提交。
 *
 * <p>⚠️ CAS 原状态固定 {@code PUBLISHED}：本弹窗只出现在 PUBLISHED 行的操作列，
 * 行数据的 {@code lifecycleState} 就是 PUBLISHED —— 语义等同而不需要再传。
 */
export function PlatformDeprecateModal({
  objectType,
  objectId,
  version,
  open,
  onClose,
  onSubmitted,
}: PlatformDeprecateModalProps): JSX.Element {
  const { message } = AntApp.useApp()
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<{ reason: string }>()

  // ⚠️ 换目标对象时必须清空上一次的理由：理由会留痕，串行张冠李戴比空着更危险。
  useEffect(() => {
    if (!open) return
    form.resetFields()
  }, [open, objectId, form])

  const submit = async (): Promise<void> => {
    if (objectId === null || version === null) return
    let values: { reason: string }
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setSubmitting(true)
    try {
      const req = await submitPlatformLifecycleRequest({
        type: objectType,
        objectId,
        version,
        expectedFrom: 'PUBLISHED',
        to: 'DEPRECATED',
        reason: values.reason.trim(),
      })
      message.success('停用申请已提交，等待审批与执行')
      form.resetFields()
      onSubmitted?.(req)
      onClose()
    } catch (e) {
      message.error(describePlatformError(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      open={open}
      title={`停用 ${objectType}（迁移到 DEPRECATED，单向终态）`}
      onCancel={onClose}
      confirmLoading={submitting}
      onOk={() => void submit()}
      okText="提交停用申请"
      destroyOnClose
    >
      {/* ⚠️ 刻意给 Form 命名：避免与其他弹窗表单的 reason 字段撞 DOM id。 */}
      <Form form={form} name={`platform-deprecate-${objectType}`} layout="vertical">
        <Form.Item label="对象">
          <Input value={objectId ?? ''} disabled />
        </Form.Item>
        <Form.Item label="CAS 版本">
          <Input value={version === null ? '' : `v${version}`} disabled />
        </Form.Item>
        <Form.Item
          name="reason"
          label="停用理由"
          rules={[{ required: true, message: '停用理由不能为空（将留痕）' }]}
        >
          <Input.TextArea rows={3} placeholder="为何停用该对象（将留痕在申请与审计中）" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

export default PlatformDeprecateModal
