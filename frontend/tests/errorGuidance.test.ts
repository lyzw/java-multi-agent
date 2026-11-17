/**
 * 统一错误指引测试（E2E 二次评估 N2 / N3 / N6）
 *
 * ⚠️ 本文件锁死的是**铁律 15**：终态 / 权限错误不得伪装成临时故障。
 *    F8 只修了进度页的 404，画像页仍把 403 说成「可能是网络问题」并给重试；
 *    报表页 7 个区块各写一份 next 文案 —— 修一处漏一处。
 *    故纪律收敛到 `errorGuidance` / `runFailureGuidance` 两个函数，测试集中在此。
 */

import { describe, it, expect } from 'vitest'
import {
  errorGuidance,
  runFailureGuidance,
  readableFailureReason,
} from '@/api/errorGuidance'
import type { NormalizedError } from '@/api/errors'

function err(status: number, code: string, message = '出错了'): NormalizedError {
  return { status, code, message, fieldErrors: {} }
}

const TERMINAL_WORDS = ['网络问题', '稍后再试', '正在处理', '稍后重试', '重试即可']

describe('errorGuidance：终态错误不得伪装成临时故障', () => {
  it('⛔ 403（权限拒绝）→ 不给重试、不说网络问题、指向管理员', () => {
    const g = errorGuidance(err(403, 'ACCESS_DENIED', '无权执行该操作'))
    expect(g.retryable).toBe(false)
    expect(g.reason).toContain('无权执行该操作')
    expect(g.next).toContain('联系管理员')
    for (const word of TERMINAL_WORDS) expect(g.next).not.toContain(word)
  })

  it('⛔ 404（资源不存在 / 反探测）→ 不给重试、明确说重试无用', () => {
    const g = errorGuidance(err(404, 'NOT_FOUND', '资源不存在'))
    expect(g.retryable).toBe(false)
    expect(g.next).toContain('不会让它出现')
    for (const word of TERMINAL_WORDS) expect(g.next).not.toContain(word)
  })

  it('401（会话失效）→ 不给重试，唯一动作是重新登录', () => {
    const g = errorGuidance(err(401, 'UNAUTHENTICATED', '登录已过期，请重新登录'))
    expect(g.retryable).toBe(false)
    expect(g.next).toContain('重新登录')
  })

  it('临时故障（0 / 5xx / 超时）→ 可重试', () => {
    for (const e of [
      err(0, 'NETWORK_ERROR'),
      err(0, 'REQUEST_TIMEOUT'),
      err(500, 'INTERNAL_ERROR'),
      err(503, 'HTTP_503'),
    ]) {
      expect(errorGuidance(e).retryable).toBe(true)
    }
  })

  it('400 校验类 → 可修改后重试', () => {
    expect(errorGuidance(err(400, 'INVALID_REQUEST')).retryable).toBe(true)
  })

  it('code 与 status 冲突时以语义终态优先（403 标成 0 也不给重试）', () => {
    expect(errorGuidance(err(0, 'ACCESS_DENIED')).retryable).toBe(false)
  })

  it('⛔ 判定顺序不可调换：终态码即使 status 被改写成 5xx / 0 / 409 仍不可重试', () => {
    for (const e of [
      err(500, 'ACCESS_DENIED'),
      err(0, 'NOT_FOUND'),
      err(409, 'UNAUTHENTICATED'),
      err(503, 'ACCESS_DENIED'),
    ]) {
      expect(errorGuidance(e).retryable).toBe(false)
      expect(errorGuidance(e).next).not.toContain('稍后重试通常可以恢复')
    }
  })

  it('其余 4xx（409 / 422 / 429）可修改后重试，且不说成权限问题', () => {
    for (const e of [
      err(409, 'IDEMPOTENCY_CONFLICT'),
      err(422, 'VALIDATION_FAILED'),
      err(429, 'RATE_LIMITED'),
    ]) {
      const g = errorGuidance(e)
      expect(g.retryable).toBe(true)
      expect(g.next).not.toContain('权限')
    }
  })

  it('null 错误给通用兜底，不给重试', () => {
    expect(errorGuidance(null).retryable).toBe(false)
  })

  it('⛔ 反证：把 403 当可重试的错误实现会被上面的断言抓到', () => {
    const flawed = { ...errorGuidance(err(403, 'ACCESS_DENIED')), retryable: true }
    expect(flawed.retryable).toBe(true)
    expect(flawed.retryable).not.toBe(errorGuidance(err(403, 'ACCESS_DENIED')).retryable)
  })
})

describe('runFailureGuidance：内部错误码不得甩给受众', () => {
  it('⛔ 租户级配置 / 预算故障 → 不提供「重新发起」', () => {
    // ⚠️ 全量 6 码：少一个都可能在页面上把租户级故障误判成可重试。
    for (const code of [
      'CONFIG_UNAVAILABLE',
      'BUDGET_EXHAUSTED',
      'AUTHZ_UNAVAILABLE',
      'NO_ACCOUNT',
      'CONFIG_MISSING',
      'AGENT_CONFIG_UNAVAILABLE',
    ]) {
      const g = runFailureGuidance(code, 'run-1')
      expect(g.kind).toBe('service-config')
      expect(g.canRetry).toBe(false)
      expect(g.contactAdmin).toBe(true)
      expect(g.reason).not.toContain(code)
      expect(g.advice).not.toContain(code)
      expect(g.advice).toContain('run-1')
      expect(g.advice).toContain('联系管理员')
      for (const word of TERMINAL_WORDS) expect(g.advice).not.toContain(word)
    }
  })

  it('临时故障 → 可用原题重新发起一次', () => {
    // ⚠️ 全量 12 码。
    for (const code of [
      'MODEL_TIMEOUT',
      'MODEL_ERROR',
      'PROVIDER_ERROR',
      'TIMEOUT',
      'TIMED_OUT',
      'REQUEST_TIMEOUT',
      'NETWORK_ERROR',
      'RATE_LIMITED',
      'RAG_UNAVAILABLE',
      'INTERNAL_ERROR',
      'EMPTY_ASSISTANT_OUTPUT',
      'NO_ASSISTANT_OUTPUT',
    ]) {
      const g = runFailureGuidance(code)
      expect(g.kind).toBe('transient')
      expect(g.canRetry).toBe(true)
      expect(g.reason).not.toContain(code)
    }
  })

  it('未知 / 缺失错误码 → 不猜含义、不给必然失败的重试、附运行标识', () => {
    for (const code of [undefined, null, '', 'SOME_FUTURE_CODE']) {
      const g = runFailureGuidance(code, 'run-2')
      expect(g.kind).toBe('unknown')
      expect(g.canRetry).toBe(false)
      expect(g.contactAdmin).toBe(true)
      expect(g.advice).toContain('run-2')
    }
  })

  it('readableFailureReason 覆盖三类码与 trim，空白输入返回 null', () => {
    expect(readableFailureReason('BUDGET_EXHAUSTED')).toBe('失败原因：服务配置尚未就绪')
    expect(readableFailureReason('MODEL_ERROR')).toBe('失败原因：这次分析临时未能完成')
    expect(readableFailureReason('SOME_FUTURE_CODE')).toBe('失败原因：本次执行未完成')
    // ⚠️ 后端可能带空白；不 trim 会落进 unknown，变成「联系管理员」的假故障。
    expect(readableFailureReason('  MODEL_ERROR  ')).toBe('失败原因：这次分析临时未能完成')
    expect(readableFailureReason(null)).toBeNull()
    expect(readableFailureReason(undefined)).toBeNull()
    expect(readableFailureReason('')).toBeNull()
    expect(readableFailureReason('   ')).toBeNull()
  })

  it('⛔ 反证：把租户级故障标成可重试会被断言抓到', () => {
    const flawed = { ...runFailureGuidance('BUDGET_EXHAUSTED'), canRetry: true }
    expect(flawed.canRetry).toBe(true)
    expect(flawed.canRetry).not.toBe(runFailureGuidance('BUDGET_EXHAUSTED').canRetry)
  })

  it('⛔ 反证：从分类表删掉 CONFIG_MISSING，全量用例会转红', () => {
    const flawedTable = [
      'CONFIG_UNAVAILABLE', 'BUDGET_EXHAUSTED', 'AUTHZ_UNAVAILABLE',
      'NO_ACCOUNT', 'AGENT_CONFIG_UNAVAILABLE',
    ]
    const classify = (code: string): string =>
      flawedTable.includes(code) ? 'service-config' : 'unknown'
    expect(classify('CONFIG_MISSING')).toBe('unknown')
    expect(runFailureGuidance('CONFIG_MISSING').kind).toBe('service-config')
  })
})

/**
 * ⚠️ 规模化发布门禁：后端真实写入 run.error_code、但前端分类表里
 *    没有的码（RunExecutionLoop / RequestAssemblyException）。
 *    它们必须 fail-closed 落到 unknown：不猜含义、不原样透出、「附运行标识」，
 *    且绝不给「重新这道题」（那会把未知故障变成必然失败的重试循环）。
 */
/**
 * 真实后端终态码（额度耗尽 / 重试上限 / 死信）：非临时，不给重新发起。
 *
 * ⚠️ 这些码过去不在分类表里 → 落入 unknown 兜底。虽然 unknown 也不可重试，
 *    但文案是「本次执行未完成」，无法告诉用户真实原因（额度用尽）——
 *    这正是缺陷里「进度页说不清为什么失败」的一部分。
 */
describe('runFailureGuidance：额度耗尽 / 重试上限是终态，不是临时故障', () => {
  const QUOTA_CODES = [
    'MODEL_RATE_LIMITED',
    'QUOTA_EXHAUSTED',
    'MODEL_PROVIDER_ERROR',
    'MODEL_RETRY_EXHAUSTED',
    'MAX_ATTEMPTS_EXCEEDED',
  ]

  for (const code of QUOTA_CODES) {
    it(`⛔ ${code} → quota-exhausted：可失败原因可读、不可重试、不原样透出码`, () => {
      const g = runFailureGuidance(code, 'run-3')
      expect(g.kind).toBe('quota-exhausted')
      expect(g.canRetry).toBe(false)
      expect(g.contactAdmin).toBe(true)
      expect(g.reason).not.toContain(code)
      expect(g.reason).not.toContain('稍等马上就好')
      expect(g.advice).not.toContain(code)
      expect(g.advice).toContain('run-3')
      for (const word of TERMINAL_WORDS) expect(g.advice).not.toContain(word)
    })
  }

  it('额度耗尽有专属终态文案（不把「稍等马上就好」当结论）', () => {
    for (const code of ['MODEL_RATE_LIMITED', 'QUOTA_EXHAUSTED']) {
      expect(runFailureGuidance(code).reason)
        .toBe('模型额度已用尽或调用过于频繁，本次分析未成功；请稍后再试或联系管理员')
    }
    expect(readableFailureReason('MODEL_RATE_LIMITED'))
      .toBe('失败原因：模型额度已用尽或调用过于频繁，本次分析未成功；请稍后再试或联系管理员')
  })

  it('⛔ 反证：把额度耗尽标成可重试（落回 TRANSIENT）会被上面抓到', () => {
    const flawed = { ...runFailureGuidance('MODEL_RATE_LIMITED'), canRetry: true }
    expect(flawed.canRetry).toBe(true)
    expect(flawed.canRetry).not.toBe(runFailureGuidance('MODEL_RATE_LIMITED').canRetry)
  })
})

describe('runFailureGuidance：后端真实 errorCode 的消费契约', () => {
  const BACKEND_REAL_CODES = [
    'MAX_LLM_CALLS',
    'MAX_COST',
    'TOOL_INTERNAL',
    'CONTEXT_POLICY_INVALID',
    'CONTEXT_ASSEMBLY_FAILED',
    'SAFETY_BLOCK',
    'MAX_ROUNDS',
  ]

  for (const code of BACKEND_REAL_CODES) {
    it(`⛔ ${code} → unknown：不给重试、不透原码、附运行标识`, () => {
      const g = runFailureGuidance(code, 'run-9')
      expect(g.kind).toBe('unknown')
      expect(g.canRetry).toBe(false)
      expect(g.contactAdmin).toBe(true)
      expect(g.advice).toContain('run-9')
      expect(g.reason).not.toContain(code)
      expect(g.advice).not.toContain(code)
      expect(g.advice).not.toContain('网络问题')
    })
  }

  it('⛔ 反证：把 unknown 兜底改成可重试会被上面的断言抓到', () => {
    const flawed = { ...runFailureGuidance('MAX_LLM_CALLS'), canRetry: true }
    expect(flawed.canRetry).toBe(true)
    expect(flawed.canRetry).not.toBe(runFailureGuidance('MAX_LLM_CALLS').canRetry)
  })
})
