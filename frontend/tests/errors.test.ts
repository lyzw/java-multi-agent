/**
 * 错误归一化测试（FE-D1 §9 T9）
 *
 * ⛔ 核心：后端存在**两套不兼容的错误体**，必须都能解析。
 *    若只认其中一套，另一套会解析出 undefined，错误提示退化为空白。
 */

import { describe, it, expect } from 'vitest'
import {
  normalizeHttpError,
  normalizeNetworkError,
  isAuthError,
  isIdempotencyConflict,
} from '@/api/errors'

describe('错误归一化', () => {
  describe('形状① {code, message} —— 业务错误路径', () => {
    it('应解析 code 与 message', () => {
      const result = normalizeHttpError(403, {
        code: 'ACCESS_DENIED',
        message: '无权执行该操作',
      })
      expect(result.code).toBe('ACCESS_DENIED')
      expect(result.message).toBe('无权执行该操作')
      expect(result.status).toBe(403)
    })

    it('message 缺失时用默认文案', () => {
      const result = normalizeHttpError(403, { code: 'ACCESS_DENIED' })
      expect(result.code).toBe('ACCESS_DENIED')
      expect(result.message).toBe('无权执行该操作')
    })

    it('应提取 correlationId（排障用）', () => {
      const result = normalizeHttpError(500, {
        code: 'INTERNAL_ERROR',
        message: '服务暂时不可用',
        correlationId: 'corr-123',
      })
      expect(result.correlationId).toBe('corr-123')
    })
  })

  describe('形状② {error} —— Security 过滤器 / AuthController 路径', () => {
    it('unauthorized → UNAUTHENTICATED', () => {
      const result = normalizeHttpError(401, { error: 'unauthorized' })
      expect(result.code).toBe('UNAUTHENTICATED')
      expect(result.message).toBe('登录已过期，请重新登录')
    })

    it('forbidden → ACCESS_DENIED', () => {
      const result = normalizeHttpError(403, { error: 'forbidden' })
      expect(result.code).toBe('ACCESS_DENIED')
      expect(result.message).toBe('无权执行该操作')
    })

    it('⛔ invalid_credentials → 统一文案，不暴露原因', () => {
      const result = normalizeHttpError(401, { error: 'invalid_credentials' })
      expect(result.code).toBe('INVALID_CREDENTIALS')
      // 三种失败路径（账号不存在 / 租户不存在 / 密码错误）必须是同一句文案。
      // ⚠️ 断言「不含租户」是错的——枚举三项本就包含"租户"。
      //    真正要防的是**区分**它们，故断言不含任何"具体是哪一项错了"的措辞。
      expect(result.message).toBe('租户、账号或密码不正确')
      expect(result.message).not.toContain('密码错误')
      expect(result.message).not.toContain('账号不存在')
      expect(result.message).not.toContain('不存在')
      expect(result.message).not.toContain('无效')
    })

    it('⛔ 三种失败路径产出完全相同的文案', () => {
      // 后端把所有失败路径压成同一个 error 值（耗时也一致），
      // 前端不得再做任何细分——那会重建后端刻意消除的枚举面。
      const a = normalizeHttpError(401, { error: 'invalid_credentials' })
      const b = normalizeHttpError(401, { code: 'INVALID_CREDENTIALS' })
      expect(a.message).toBe(b.message)
      expect(a.code).toBe(b.code)
    })
  })

  describe('两套形状必须都能解析（防止只实现一套）', () => {
    it('同一语义的不同形状应归一到同一 code', () => {
      const viaCode = normalizeHttpError(401, { code: 'UNAUTHENTICATED', message: '' })
      const viaError = normalizeHttpError(401, { error: 'unauthorized' })
      expect(viaCode.code).toBe(viaError.code)
    })

    it('401 与 403 不得混为一谈', () => {
      const unauthorized = normalizeHttpError(401, { error: 'unauthorized' })
      const forbidden = normalizeHttpError(403, { error: 'forbidden' })
      expect(unauthorized.code).not.toBe(forbidden.code)
      expect(isAuthError(unauthorized)).toBe(true)
      expect(isAuthError(forbidden)).toBe(false)
    })
  })

  describe('无法识别的响应', () => {
    it('null 体 → 仍返回可用结构', () => {
      const result = normalizeHttpError(500, null)
      expect(result.code).toBe('HTTP_500')
      expect(result.message).toBe('服务暂时不可用')
    })

    it('HTML 错误页（解析失败场景）→ 不崩溃', () => {
      const result = normalizeHttpError(500, '<html>Whitelabel Error</html>')
      expect(result.status).toBe(500)
      expect(result.message.length).toBeGreaterThan(0)
    })

    it('空对象 → 降级为 HTTP_<status>', () => {
      const result = normalizeHttpError(418, {})
      expect(result.code).toBe('HTTP_418')
    })
  })

  describe('已实现错误码映射', () => {
    const cases: { status: number; code: string; expectMessage: string }[] = [
      { status: 400, code: 'INVALID_REQUEST', expectMessage: '请求有误，请检查填写内容' },
      { status: 403, code: 'ACCESS_DENIED', expectMessage: '无权执行该操作' },
      { status: 404, code: 'NOT_FOUND', expectMessage: '资源不存在' },
      {
        status: 409,
        code: 'IDEMPOTENCY_CONFLICT',
        expectMessage: '该提交与之前的请求不一致，请刷新后重试',
      },
      { status: 422, code: 'COMPLIANCE_BLOCKED', expectMessage: '当前数据不符合合规要求' },
      { status: 415, code: 'UNSUPPORTED_MEDIA_TYPE', expectMessage: '请求格式不受支持' },
    ]

    for (const testCase of cases) {
      it(`${testCase.status} ${testCase.code}`, () => {
        const result = normalizeHttpError(testCase.status, { code: testCase.code })
        expect(result.code).toBe(testCase.code)
        expect(result.message).toBe(testCase.expectMessage)
      })
    }
  })

  describe('幂等冲突识别', () => {
    it('409 + IDEMPOTENCY_CONFLICT 应识别', () => {
      const result = normalizeHttpError(409, { code: 'IDEMPOTENCY_CONFLICT' })
      expect(isIdempotencyConflict(result)).toBe(true)
    })
    it('其他 409 不应识别为幂等冲突', () => {
      const result = normalizeHttpError(409, { code: 'RUN_STATE_CONFLICT' })
      expect(isIdempotencyConflict(result)).toBe(false)
    })
  })

  describe('网络失败', () => {
    it('应给出可操作提示', () => {
      const result = normalizeNetworkError(new TypeError('Failed to fetch'))
      expect(result.status).toBe(0)
      expect(result.message).toContain('网络')
    })
  })

  describe('⛔ 超长 message 兜底', () => {
    it('后端原样透出的超长文案应被截断', () => {
      const longMessage = '很长的中文'.repeat(200)
      const result = normalizeHttpError(400, {
        code: 'INVALID_REQUEST',
        message: longMessage,
      })
      expect(result.message.length).toBeLessThan(longMessage.length)
      expect(result.message.length).toBeLessThanOrEqual(201)
    })
  })
})
