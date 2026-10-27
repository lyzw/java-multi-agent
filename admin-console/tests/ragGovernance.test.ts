import { describe, it, expect } from 'vitest'
import { allowedVerificationTargets } from '@/features/rag/useRagManagement'
import { isRagTimeout, RAG_TIMEOUT_STATUS } from '@/api/ragClient'
import { ApiError } from '@/api/client'
import type { RagVerificationStatus } from '@/api/ragTypes'

/**
 * RAG 治理纪律测试（RAG-D07 §2.4 状态机 T-1～T-12）。
 *
 * ⚠️ 守的是**纪律**：
 *    · REVOKED 必须是终态（无合法后继）——撤回不可复活是产品承诺，不是渲染细节；
 *    · 前端只渲染合法目标，让用户点一个注定被 409 的操作是产品缺陷；
 *    · 超时（408 哨兵）必须与网络层失败（status 0）区分——文案不同。
 */

const ALL: RagVerificationStatus[] = [
  'PENDING',
  'IN_REVIEW',
  'VERIFIED',
  'REJECTED',
  'REVOKED',
]

describe('RAG 校验状态流转白名单', () => {
  it('✅ REVOKED 是终态：无任何合法后继（T-10）', () => {
    expect(allowedVerificationTargets('REVOKED')).toEqual([])
  })

  it('✅ PENDING 只能进入审核或撤回（T-1 / T-2）', () => {
    expect(allowedVerificationTargets('PENDING').sort()).toEqual(['IN_REVIEW', 'REVOKED'])
  })

  it('✅ IN_REVIEW 可以判定通过与拒绝，也可撤回（T-3～T-5）', () => {
    expect(allowedVerificationTargets('IN_REVIEW').sort()).toEqual([
      'REJECTED',
      'REVOKED',
      'VERIFIED',
    ])
  })

  it('✅ VERIFIED / REJECTED 只能回到审核或撤回（T-6～T-9）', () => {
    expect(allowedVerificationTargets('VERIFIED').sort()).toEqual(['IN_REVIEW', 'REVOKED'])
    expect(allowedVerificationTargets('REJECTED').sort()).toEqual(['IN_REVIEW', 'REVOKED'])
  })

  it('✅ 任何状态都不会把自身列为合法目标（自转无意义）', () => {
    for (const s of ALL) {
      expect(allowedVerificationTargets(s)).not.toContain(s)
    }
  })

  it('✅ 只有 REVOKED 是终态，其余状态至少有一个合法后继', () => {
    for (const s of ALL) {
      const targets = allowedVerificationTargets(s)
      if (s === 'REVOKED') {
        expect(targets.length).toBe(0)
      } else {
        expect(targets.length).toBeGreaterThan(0)
      }
    }
  })
})

describe('RAG 超时与网络层失败的区分', () => {
  it('✅ 408 哨兵被识别为超时', () => {
    expect(isRagTimeout(new ApiError(RAG_TIMEOUT_STATUS, '请求超时'))).toBe(true)
  })

  it('✅ 网络层失败（status 0）不是超时——文案不同，不能混', () => {
    expect(isRagTimeout(new ApiError(0, '网络错误'))).toBe(false)
  })

  it('✅ 409 不是超时（它是非法状态转移，不是服务故障）', () => {
    expect(isRagTimeout(new ApiError(409, '非法流转'))).toBe(false)
  })

  it('✅ 非 ApiError 不会被误判为超时', () => {
    expect(isRagTimeout(new Error('boom'))).toBe(false)
    expect(isRagTimeout(null)).toBe(false)
  })
})
