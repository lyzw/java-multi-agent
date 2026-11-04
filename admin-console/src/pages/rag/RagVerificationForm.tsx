/**
 * 通用校验流转表单（来源级与载体级共用，二者 payload 结构同构）。
 *
 * ⚠️ 治理纪律（RAG-D07 §2.4 / RAG-D10）：
 *    · 只渲染 `allowedVerificationTargets` 返回的**合法目标**，从源头消除「注定 409」。
 *    · `evidenceRef` 必填（后端强制留痕）；`decidedBy` 取当前登录主体（审核留痕）。
 *    · 判定 VERIFIED 时 `trustTier` 必填（否则来源通过校验却仍被权威门槛拦下）。
 *    · 撤回（REVOKED）必带依据，DB CHECK 亦会拦。
 */

import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Form, Input, InputNumber, Select, Space, Typography } from 'antd'
import type {
  RagReviewer,
  RagVerificationRequest,
  RagVerificationStatus,
} from '@/api/ragTypes'
import { listRagCoSigners, isRagTargetReady } from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
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
  /**
   * 当前来源与作用域（用于拉「可复核审核员」下拉）。
   *
   * ⚠️ 缺省时不渲染下拉，退回手输 UUID——
   * 这是刻意的降级：宁可让人手输，也不要让一个拉不到名录的表单挡住签发。
   */
  sourceId?: string
  target?: RagTarget
}

interface FormShape {
  targetStatus: RagVerificationStatus
  evidenceRef: string
  verificationMethod: RagVerificationRequest['verificationMethod']
  trustTier: RagVerificationRequest['trustTier']
  validDays: number
  /** 复核人（双人复核的第二个人）—— 仅 VERIFIED 必填 */
  verifiedBy: string
}

/** UUID 形状（后端 SoD 会把该值写进 uuid 列，畸形串只会得到 500/409）。 */
const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

export function RagVerificationForm({
  currentStatus,
  submitting,
  onSubmitted,
  onCancel,
  sourceId,
  target,
}: RagVerificationFormProps): JSX.Element {
  const [form] = Form.useForm<FormShape>()
  const targets = allowedVerificationTargets(currentStatus)

  /**
   * 可复核的审核员（下拉数据源）。
   *
   * ⚠️ 拉取失败**不阻断签发**：退回手输 UUID。
   *    让一个拉不到名录的表单挡住整条治理链路，比手输一次糟糕得多。
   */
  const [coSigners, setCoSigners] = useState<RagReviewer[]>([])
  const [loadingCoSigners, setLoadingCoSigners] = useState(false)
  /** 请求序号（见下方 effect 注释：用序号而非布尔，避免丢弃最新结果）。 */
  const requestSeq = useRef(0)

  /*
    ⚠️ 依赖用**原语**而不是 target 对象：target 每次渲染都是新引用，
       直接放进依赖会让 effect 反复重跑，名录永远加载不完
       （表现是下拉一直不出现，而手输框也不出现——两者皆空）。
  */
  const targetKey = target ? `${target.scope}:${target.tenantId ?? ''}` : ''

  useEffect(() => {
    if (!sourceId || !target || !isRagTargetReady(target)) return
    setLoadingCoSigners(true)
    void listRagCoSigners(target, sourceId)
      .then((list) => {
        // 只有「仍是最新一次请求」才落结果，避免旧请求覆盖新数据
        if (requestSeq.current === seq) setCoSigners(list)
      })
      .catch(() => {
        // 静默降级：表单退回手输 UUID
        if (requestSeq.current === seq) setCoSigners([])
      })
      .finally(() => {
        if (requestSeq.current === seq) setLoadingCoSigners(false)
      })
    /*
      ⚠️ 撤销标记用**计数器**而不是布尔：
          React 在依赖变化与卸载时都会跑 cleanup。若 cleanup 无条件把本次
          请求标为「作废」，其结果就被丢弃——而下一次 effect 又会重新发起，
          抽屉内表单频繁重渲染时名录会**永远停在空数组**
          （实测：接口 200 且返回 1 条，下拉始终为空）。
          做法：每次 effect 自增序号，回调里比对「我这次是否仍是最新一次」。
    */
    const seq = ++requestSeq.current
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceId, targetKey])
  const decidedBy = currentPrincipalId()
  // 校验手段 / 权威等级取值来自字典表（不可达时回落静态兜底）
  const { options: methodOptions } = useDictOptions('rag.verification_method')
  const { options: tierOptions } = useDictOptions('rag.trust_tier')

  // 目标变更时：若不是 VERIFIED，清空 trustTier（与复核人——两者都只对 VERIFIED 有意义）。
  const targetStatus = Form.useWatch('targetStatus', form)
  useEffect(() => {
    if (targetStatus !== 'VERIFIED') {
      form.setFieldValue('trustTier', null)
      form.setFieldValue('verifiedBy', '')
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
      // ⚠️ 只有 VERIFIED 带复核人；其余状态后端不要求，传 null 避免脏数据。
      verifiedBy:
        values.targetStatus === 'VERIFIED' ? (values.verifiedBy ?? '').trim() || null : null,
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

      {/*
        双人复核的第二个主体。
        ⚠️ 必须由人工填写：后端只能校验「这个 UUID ≠ 执行人」，
           它无法证明这个人是真的复核过。前端若自动补一个，等于替审核员伪造一次复核。
        ⚠️ 只对 VERIFIED 渲染/必填：其余状态的流转后端不要求双人（SoD 对非 VERIFIED 直接放行）。
      */}
      <Form.Item
        label="复核人（双人复核 · 必须与决定人不同）"
        name="verifiedBy"
        style={{ maxWidth: 420 }}
        tooltip={
          coSigners.length > 0
            ? '从可复核的审核员中选人。服务端已排除你本人与该来源的采集人。'
            : '第二位复核人的主体 UUID。后端会拒绝「与决定人相同」的复核人。'
        }
        rules={
          isVerifiedTarget
            ? [
                { required: true, message: '通过校验须选择第二位复核人' },
                // ⚠️ 只有退回手输时才校验 UUID 格式：下拉选出来的值本就是合法 UUID
                ...(coSigners.length > 0
                  ? []
                  : [{ pattern: UUID_PATTERN, message: '须为合法 UUID' }]),
                {
                  validator: (_, value: string) =>
                    !value || value.trim().toLowerCase() !== decidedBy.toLowerCase()
                      ? Promise.resolve()
                      : Promise.reject(new Error('复核人不能是本人（后端会以 409 拒绝）')),
                },
              ]
            : []
        }
      >
        {/*
          ⚠️ **下拉选人，而不是手输 UUID**（交互文档问题 P5）：
              让审核员去「找到一个 UUID 并抄进来」是把服务端已知的信息转嫁给用户。
              ⚠️ 服务端已排除本人与采集人（后端 SoD 会拒绝这两类），
              故下拉里不存在「选了会被拒」的选项。
        */}
        {coSigners.length > 0 ? (
          <Select
            disabled={!isVerifiedTarget}
            allowClear={isVerifiedTarget}
            loading={loadingCoSigners}
            placeholder={
              isVerifiedTarget ? '选择第二位复核人' : '仅「通过校验」需要双人复核'
            }
            options={coSigners.map((r) => ({
              value: r.principalId,
              label: r.displayName,
            }))}
          />
        ) : (
          <Input
            disabled={!isVerifiedTarget}
            allowClear={isVerifiedTarget}
            placeholder={
              isVerifiedTarget
                ? loadingCoSigners
                  ? '正在读取可复核审核员…'
                  : '第二位复核人的主体 UUID（未配置审核员名录时手输）'
                : '仅「通过校验」需要双人复核'
            }
          />
        )}
      </Form.Item>

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
