/**
 * 通用校验流转表单（来源级与载体级共用，二者 payload 结构同构）。
 *
 * ⚠️ 治理纪律（RAG-D07 §2.4 / RAG-D10）：
 *    · 只渲染 `allowedVerificationTargets` 返回的**合法目标**，从源头消除「注定 409」。
 *    · `evidenceRef` 必填（后端强制留痕）；`decidedBy` 取当前登录主体（审核留痕）。
 *    · 判定 VERIFIED 时 `trustTier` 必填（否则来源通过校验却仍被权威门槛拦下）。
 *    · 撤回（REVOKED）必带依据，DB CHECK 亦会拦。
 */

import { useEffect } from 'react'
import { Alert, Button, Form, Input, InputNumber, Select, Space, Typography } from 'antd'
import type { RagVerificationRequest, RagVerificationStatus } from '@/api/ragTypes'
import { allowedVerificationTargets } from '@/features/rag/useRagManagement'
import { useDictOptions } from '@/features/dictionaries/useDictionaries'
import { VERIFICATION_TARGET_VERB, currentPrincipalId } from './ragUi'

const { Text } = Typography

export interface RagVerificationFormProps {
  /** 当前状态（决定可渲染的合法目标） */
  currentStatus: RagVerificationStatus
  submitting: boolean
  onSubmitted: (payload: RagVerificationRequest) => void
  /** 撤销（关闭模态等） */
  onCancel?: () => void
}

interface FormShape {
  targetStatus: RagVerificationStatus
  evidenceRef: string
  verificationMethod: RagVerificationRequest['verificationMethod']
  trustTier: RagVerificationRequest['trustTier']
  validDays: number
}

export function RagVerificationForm({
  currentStatus,
  submitting,
  onSubmitted,
  onCancel,
}: RagVerificationFormProps): JSX.Element {
  const [form] = Form.useForm<FormShape>()
  const targets = allowedVerificationTargets(currentStatus)
  const decidedBy = currentPrincipalId()
  // 校验手段 / 权威等级取值来自字典表（不可达时回落静态兜底）
  const { options: methodOptions } = useDictOptions('rag.verification_method')
  const { options: tierOptions } = useDictOptions('rag.trust_tier')

  // 目标变更时：若不是 VERIFIED，清空 trustTier。
  const targetStatus = Form.useWatch('targetStatus', form)
  useEffect(() => {
    if (targetStatus !== 'VERIFIED') {
      form.setFieldValue('trustTier', null)
    }
  }, [targetStatus, form])

  // 无合法目标（REVOKED 终态）：提示并禁用提交。
  if (targets.length === 0) {
    return (
      <Alert
        type="warning"
        showIcon
        message="当前状态无合法流转"
        description="该来源已撤回（终态），不可复活。重新准入必须新建来源。"
      />
    )
  }

  const handleFinish = (values: FormShape): void => {
    const payload: RagVerificationRequest = {
      targetStatus: values.targetStatus,
      evidenceRef: values.evidenceRef.trim(),
      decidedBy,
      verificationMethod: values.verificationMethod,
      trustTier: values.targetStatus === 'VERIFIED' ? values.trustTier : null,
      validDays: values.validDays,
    }
    onSubmitted(payload)
  }

  const isVerifiedTarget = targetStatus === 'VERIFIED'

  return (
    <Form<FormShape>
      form={form}
      layout="vertical"
      initialValues={{
        targetStatus: targets[0],
        verificationMethod: 'MANUAL_REVIEW',
        validDays: 365,
        trustTier: null,
      }}
      onFinish={handleFinish}
    >
      <Form.Item
        label="下一步状态"
        name="targetStatus"
        rules={[{ required: true, message: '请选择下一步状态' }]}
      >
        <Select
          options={targets.map((t) => ({
            value: t,
            label: VERIFICATION_TARGET_VERB[t] ?? t,
          }))}
        />
      </Form.Item>

      <Form.Item
        label="校验依据（必填，强制留痕）"
        name="evidenceRef"
        rules={[
          { required: true, message: '必须填写校验依据' },
          { min: 4, message: '依据至少 4 个字，便于审计回溯' },
        ]}
      >
        <Input.TextArea rows={2} placeholder="如：ISBN 978-7-xxxx-xxxx-x 比对一致 / 出版社授权书编号" />
      </Form.Item>

      <Space size="large" style={{ display: 'flex' }} align="start">
        <Form.Item label="校验手段" name="verificationMethod" style={{ minWidth: 200 }}>
          <Select options={methodOptions} />
        </Form.Item>
        <Form.Item
          label="权威等级"
          name="trustTier"
          style={{ minWidth: 200 }}
          rules={isVerifiedTarget ? [{ required: true, message: '通过校验须指定权威等级' }] : []}
        >
          <Select
            allowClear
            disabled={!isVerifiedTarget}
            placeholder={isVerifiedTarget ? '请选择权威等级' : '仅「通过校验」需要'}
            options={tierOptions}
          />
        </Form.Item>
        <Form.Item label="有效期（天）" name="validDays" style={{ minWidth: 120 }}>
          <InputNumber min={1} max={3650} />
        </Form.Item>
      </Space>

      <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
        决定人（留痕）：<Text code>{decidedBy || '（当前会话无 principalId，无法留痕）'}</Text>
      </Text>

      <Space>
        <Button type="primary" htmlType="submit" loading={submitting}>
          提交校验决定
        </Button>
        {onCancel && <Button onClick={onCancel}>取消</Button>}
      </Space>
    </Form>
  )
}
