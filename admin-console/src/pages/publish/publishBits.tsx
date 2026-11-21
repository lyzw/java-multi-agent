import type { ReactNode } from 'react'
import { Tag, Typography } from 'antd'
import type { PublishRelease, PublishRequestItem } from '@/api/types'

/** 申请状态 → 标签色（审批流程状态，≠ 配置对象生命周期） */
export function requestStateTag(state: PublishRequestItem['state']): ReactNode {
  switch (state) {
    case 'PENDING':
      return <Tag color="orange">待审批</Tag>
    case 'APPROVED':
      return <Tag color="blue">已批准 · 待执行</Tag>
    case 'REJECTED':
      return <Tag color="default">已驳回</Tag>
    case 'APPLIED':
      return <Tag color="green">已生效</Tag>
    case 'FAILED':
      return <Tag color="red">执行失败</Tag>
    default:
      return <Tag>{state}</Tag>
  }
}

/** 变更类型 → 标签 */
export function operationTag(operation: PublishRequestItem['operation']): ReactNode {
  switch (operation) {
    case 'PUBLISH':
      return <Tag color="blue">发布</Tag>
    case 'ROLLBACK':
      return <Tag color="volcano">回滚</Tag>
    case 'LIFECYCLE_TRANSITION':
      return <Tag color="purple">生命周期</Tag>
    default:
      return <Tag>{operation}</Tag>
  }
}

/** 发布状态 → 标签（PREPARING 运行期绝不可见） */
export function releaseStatusTag(status: PublishRelease['status']): ReactNode {
  switch (status) {
    case 'PUBLISHED':
      return <Tag color="green">已发布</Tag>
    case 'ROLLED_BACK':
      return <Tag color="default">已回滚</Tag>
    case 'PREPARING':
      return <Tag color="orange">准备中</Tag>
    default:
      return <Tag>{status}</Tag>
  }
}

/**
 * 提交申请后的提示文案 —— 一律以**服务端返回的流程状态**为准。
 *
 * <p>发布策略会在服务端自动分流：APPROVAL_* 停在 PENDING 等双人复核；
 * MANUAL 自动放行到 APPROVED 等人工执行；DIRECT 直接 APPLIED。
 * 前端写死「待双人复核」会在这三种情况下给出错误信息，故统一按状态描述。
 */
export function describeSubmitResult(
  state: PublishRequestItem['state'] | undefined,
  requestId?: string,
): string {
  const prefix = requestId ? `申请已提交（${requestId.slice(0, 8)}…）：` : '申请已提交：'
  switch (state) {
    case 'APPLIED':
      return `${prefix}已按发布策略直接发布生效。`
    case 'APPROVED':
      return `${prefix}已按发布策略免审批放行，待手动执行后才写入配置。`
    case 'PENDING':
      return `${prefix}待双人复核后执行。`
    case 'REJECTED':
      return `${prefix}已被驳回。`
    case 'FAILED':
      return `${prefix}执行失败，请查看失败详情。`
    default:
      return `${prefix}已提交，请到申请队列查看状态。`
  }
}

/**
 * 双人复核提示条（申请与审批两侧都挂）。
 *
 * ⚠️ 仅适用于策略判定为 APPROVAL_* 的申请；其余模式由服务端自动放行，
 * 不会出现待审批状态。同人审批由后端强校验（应用层 + DB CHECK 双保险）。
 */
export function DualReviewNotice({ extra }: { extra?: ReactNode }): JSX.Element {
  return (
    <Typography.Text type="secondary">
      双人复核：审批人不得等于申请人（后端 409 强校验）。{extra}
    </Typography.Text>
  )
}

/**
 * 同人审批前端预检。
 *
 * ⚠️ 会话无 principalId（旧会话 / 登录响应缺字段）时返回 false ——
 * 不猜「是不是同人」，把判定交给后端（409）。
 */
export function isSelfApproval(
  requesterId: string | null | undefined,
  myPrincipalId: string | null | undefined,
): boolean {
  if (requesterId == null || myPrincipalId == null) return false
  return requesterId === myPrincipalId
}
