// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { isSelfApproval, operationTag, releaseStatusTag, requestStateTag } from '@/pages/publish/publishBits'

describe('publishBits · 同人审批前端预检', () => {
  it('申请人 = 当前主体 → 同人（后端仍会 409，这里只是前置提示）', () => {
    expect(isSelfApproval('p-1', 'p-1')).toBe(true)
  })

  it('申请人 ≠ 当前主体 → 非同人', () => {
    expect(isSelfApproval('p-1', 'p-2')).toBe(false)
  })

  it('任一侧缺失 → 不猜同人，判定交后端', () => {
    expect(isSelfApproval(null, 'p-1')).toBe(false)
    expect(isSelfApproval('p-1', null)).toBe(false)
    expect(isSelfApproval(undefined, undefined)).toBe(false)
  })
})

describe('publishBits · 审批流程状态渲染（≠ 配置对象生命周期）', () => {
  it('五种申请状态各自可渲染', () => {
    for (const state of ['PENDING', 'APPROVED', 'REJECTED', 'APPLIED', 'FAILED'] as const) {
      const { unmount } = render(<>{requestStateTag(state)}</>)
      expect(screen.getByText(state === 'PENDING' ? '待审批' : state === 'APPROVED' ? '已批准 · 待执行' : state === 'REJECTED' ? '已驳回' : state === 'APPLIED' ? '已生效' : '执行失败')).toBeTruthy()
      unmount()
    }
  })

  it('三种发布状态各自可渲染', () => {
    for (const status of ['PREPARING', 'PUBLISHED', 'ROLLED_BACK'] as const) {
      const { unmount } = render(<>{releaseStatusTag(status)}</>)
      expect(screen.getByText(status === 'PUBLISHED' ? '已发布' : status === 'ROLLED_BACK' ? '已回滚' : '准备中')).toBeTruthy()
      unmount()
    }
  })

  it('三种变更类型各自可渲染', () => {
    for (const op of ['PUBLISH', 'ROLLBACK', 'LIFECYCLE_TRANSITION'] as const) {
      const { unmount } = render(<>{operationTag(op)}</>)
      expect(screen.getByText(op === 'PUBLISH' ? '发布' : op === 'ROLLBACK' ? '回滚' : '生命周期')).toBeTruthy()
      unmount()
    }
  })
})
