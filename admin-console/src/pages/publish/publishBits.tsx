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
 * 双人复核提示条（申请与审批两侧都挂）。
 *
 * ⚠️ 同人审批由后端强校验（approver_id <> requester_id，应用层 + DB CHECK 双保险），
 * 本提示只是把规则前置到眼里，不是安全边界。
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
