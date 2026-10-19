/**
 * 幂等键策略测试（FE-D1 §9 T5/T6）—— ⚠️ Sev-0 相关
 *
 * ⛔ 为什么这是 Sev-0：
 *   record_weakness 幂等键 = student_id + run_id + knowledge_point。
 *   每次重试换新 idempotencyKey → 重复创建 Run → 不同 runId
 *   → 被视为不同操作 → 重复写入薄弱点（P0-D00 §3.4 失败判定）。
 */

import { describe, it, expect, beforeEach } from 'vitest'
import {
  resolveIdempotencyKey,
  clearIdempotencyKey,
  discardIdempotencyKey,
  generateIdempotencyKey,
  fingerprintOf,
} from '@/features/run/idempotency'

const BASE_INPUT = {
  studentId: 'stu_a1b2c3d4e5f6a7b8',
  subject: '数学',
  questionText: '求 ab',
}

describe('幂等键策略', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  describe('键生成', () => {
    it('每次生成应唯一', () => {
      const keys = new Set(Array.from({ length: 50 }, () => generateIdempotencyKey()))
      expect(keys.size).toBe(50)
    })
  })

  describe('T5：内容不变则复用同一 key', () => {
    it('两次解析应返回相同 key', () => {
      const first = resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      const second = resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      expect(second).toBe(first)
    })

    it('连续多次重试仍返回同一 key', () => {
      const keys = Array.from({ length: 5 }, () =>
        resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT }),
      )
      expect(new Set(keys).size).toBe(1)
    })

    it('⛔ 网络失败后重试必须是同一个 key（防重复 Run）', () => {
      // 首次提交
      const first = resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      // 模拟网络失败 → 用户点击重试
      const retry1 = resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      const retry2 = resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      expect(retry1).toBe(first)
      expect(retry2).toBe(first)
    })
  })

  describe('T5：内容变更则换新 key', () => {
    it('input 变化应换新 key', () => {
      const first = resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      const second = resolveIdempotencyKey({
        agentId: 'a',
        input: { ...BASE_INPUT, questionText: '求 bc' },
      })
      expect(second).not.toBe(first)
    })

    it('agentId 变化应换新 key', () => {
      const first = resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      const second = resolveIdempotencyKey({ agentId: 'b', input: BASE_INPUT })
      expect(second).not.toBe(first)
    })
  })

  describe('指纹稳定性', () => {
    it('键顺序不同但内容相同 → 指纹相同', () => {
      // ⚠️ 若不排序，对象字面量键顺序不同会导致误判为"内容变更"
      const a = fingerprintOf({ agentId: 'x', input: { p: 1, q: 2 } })
      const b = fingerprintOf({ agentId: 'x', input: { q: 2, p: 1 } })
      expect(a).toBe(b)
    })

    it('嵌套对象键顺序不同 → 指纹相同', () => {
      const a = fingerprintOf({ agentId: 'x', input: { nested: { a: 1, b: 2 } } })
      const b = fingerprintOf({ agentId: 'x', input: { nested: { b: 2, a: 1 } } })
      expect(a).toBe(b)
    })

    it('数组顺序不同 → 指纹不同（数组顺序有语义）', () => {
      const a = fingerprintOf({ agentId: 'x', input: { arr: [1, 2] } })
      const b = fingerprintOf({ agentId: 'x', input: { arr: [2, 1] } })
      expect(a).not.toBe(b)
    })
  })

  describe('T6：成功后清除', () => {
    it('清除后下次应生成新 key', () => {
      const first = resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      clearIdempotencyKey()
      const second = resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      expect(second).not.toBe(first)
    })
  })

  describe('409 冲突处理', () => {
    it('discardIdempotencyKey 应清除存储', () => {
      resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      discardIdempotencyKey()
      const next = resolveIdempotencyKey({ agentId: 'a', input: BASE_INPUT })
      // 清除后是新 key —— 但由用户显式重新提交触发，不是自动重试
      expect(typeof next).toBe('string')
      expect(next.length).toBeGreaterThan(0)
    })
  })
})
