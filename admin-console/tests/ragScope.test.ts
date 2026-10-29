import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  listRagSources,
  registerRagSource,
  getRagStatistics,
  reindexRagCorpus,
  applyRagVerification,
  tenantTarget,
  PLATFORM_TARGET,
  platformTarget,
  isRagTargetReady,
  isRagWriteTarget,
  isRagRetrievalTarget,
} from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import type { RagGovernanceTicket } from '@/api/ragTicket'

/**
 * RAG 作用域头与治理票据的契约测试（RAG-D11 第四刀·第一、二刀）。
 *
 * ⚠️ 守的是**契约形态**，不是渲染：
 *    · `X-Rag-Scope` 必须始终携带——后端刻意不由「有没有 X-Tenant-Id」推断作用域，
 *      缺这个头等于悄悄按租户档走；
 *    · 平台档**不得**带 `X-Tenant-Id`——同时给两个身份后端返回 400；
 *    · 平台档的**写**请求必须换成治理票据做 `Authorization`，**读**请求不许换
 *      （审计里「动过全局语料」与「看了一眼」必须是两种凭据）；
 *    · UI 层用 {@link isRagWriteTarget} 提前拦掉无票的写入口，两端都要成立。
 */

interface FetchCall {
  path: string
  headers: Record<string, string>
}

const calls: FetchCall[] = []

function stubFetch(body = '[]'): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: unknown, init?: RequestInit) => {
      const headers = new Headers(init?.headers)
      const record: Record<string, string> = {}
      headers.forEach((value, key) => {
        record[key] = value
      })
      calls.push({ path: String(input), headers: record })
      return {
        status: 200,
        ok: true,
        text: async () => body,
      } as unknown as Response
    }),
  )
}

const TENANT: RagTarget = tenantTarget('11111111-1111-1111-1111-111111111111')

/** 一张未过期的治理票据（只用于断言头的形态，不参与真实校验）。 */
const TICKET: RagGovernanceTicket = {
  accessToken: 'ticket-token',
  tokenType: 'Bearer',
  scope: 'PLATFORM',
  platformUserId: '55555555-5555-5555-5555-555555555555',
  expiresAt: new Date(Date.now() + 240_000).toISOString(),
  jti: 'abcdef00-1111-2222-3333-444444444444',
}

/** 一张已过期的票据：本地必须按「没有票据」处理，不能让界面给出一个会 401 的按钮。 */
const EXPIRED_TICKET: RagGovernanceTicket = {
  ...TICKET,
  expiresAt: new Date(Date.now() - 1_000).toISOString(),
}

/** 取唯一一次请求；没有请求就是「前端根本没发」，比断言头值更该先失败。 */
function onlyCall(): FetchCall {
  const call = calls[0]
  if (!call) throw new Error('没有发出任何 RAG 请求')
  return call
}

beforeEach(() => {
  calls.length = 0
  stubFetch()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('RAG 管理端点的作用域头', () => {
  it('✅ 租户档同时携带 X-Rag-Scope: TENANT 与 X-Tenant-Id', async () => {
    await listRagSources(TENANT)
    expect(calls).toHaveLength(1)
    expect(onlyCall().headers['x-rag-scope']).toBe('TENANT')
    expect(onlyCall().headers['x-tenant-id']).toBe('11111111-1111-1111-1111-111111111111')
  })

  it('✅ 平台全局档带作用域头但**不带**租户头', async () => {
    await listRagSources(PLATFORM_TARGET)
    expect(onlyCall().headers['x-rag-scope']).toBe('PLATFORM')
    expect(onlyCall().headers['x-tenant-id']).toBeUndefined()
  })

  it('✅ 统计端点同样按档位发头（读端点一个都不许漏）', async () => {
    await getRagStatistics(PLATFORM_TARGET)
    expect(onlyCall().headers['x-rag-scope']).toBe('PLATFORM')
    expect(onlyCall().headers['x-tenant-id']).toBeUndefined()
  })

  it('✅ 写端点在平台档照原样发出（缺票由后端 403 收口，前端不伪装成成功）', async () => {
    stubFetch('{}')
    await registerRagSource(PLATFORM_TARGET, {
      sourceType: '教材',
      title: '平台全局教材',
      publisher: '人民教育出版社',
      isbn: '9787100000000',
      doi: null,
      objectRef: null,
      contentHash: null,
      registeredBy: '33333333-3333-3333-3333-333333333333',
    })
    expect(onlyCall().headers['x-rag-scope']).toBe('PLATFORM')
  })

  it('✅ 租户 ID 会被 trim 后再进头（避免尾空格造成后端 UUID 解析失败）', async () => {
    await listRagSources(tenantTarget('  11111111-1111-1111-1111-111111111111  '))
    expect(onlyCall().headers['x-tenant-id']).toBe('11111111-1111-1111-1111-111111111111')
  })
})

describe('治理票据只随写请求出发', () => {
  it('✅ 平台档写请求把 Authorization 换成票据（不是会话令牌）', async () => {
    stubFetch('{}')
    await registerRagSource(platformTarget(TICKET), {
      sourceType: '教材',
      title: '平台全局教材',
      publisher: '人民教育出版社',
      isbn: null,
      doi: null,
      objectRef: null,
      contentHash: null,
      registeredBy: '33333333-3333-3333-3333-333333333333',
    })
    expect(onlyCall().headers['authorization']).toBe('Bearer ticket-token')
    expect(onlyCall().headers['x-rag-scope']).toBe('PLATFORM')
    expect(onlyCall().headers['x-tenant-id']).toBeUndefined()
  })

  it('✅ 校验流转与重跑索引同样带票（写端点一个都不许漏，漏了就只会 403）', async () => {
    stubFetch('{}')
    await applyRagVerification(platformTarget(TICKET), '99999999-9999-9999-9999-999999999999', {
      targetStatus: 'IN_REVIEW',
      evidenceRef: '治理会议 2026-10-29 纪要',
      decidedBy: '33333333-3333-3333-3333-333333333333',
      verificationMethod: 'MANUAL_REVIEW',
      trustTier: null,
      validDays: 365,
    })
    expect(calls[0]?.headers['authorization']).toBe('Bearer ticket-token')
    await reindexRagCorpus(platformTarget(TICKET), 64)
    expect(calls[1]?.headers['authorization']).toBe('Bearer ticket-token')
  })

  it('✅ 同一张票据下，读请求仍不带票据（审计要能区分「改过」与「看过」）', async () => {
    await listRagSources(platformTarget(TICKET))
    expect(onlyCall().headers['authorization']).toBeUndefined()
    expect(onlyCall().headers['x-rag-scope']).toBe('PLATFORM')
  })

  it('✅ 租户档的写不受票据影响（仍是会话令牌 + X-Tenant-Id，与第一刀逐字节一致）', async () => {
    stubFetch('{}')
    await registerRagSource(TENANT, {
      sourceType: '教材',
      title: '租户教材',
      publisher: '人民教育出版社',
      isbn: null,
      doi: null,
      objectRef: null,
      contentHash: null,
      registeredBy: '33333333-3333-3333-3333-333333333333',
    })
    expect(onlyCall().headers['authorization']).toBeUndefined()
    expect(onlyCall().headers['x-tenant-id']).toBe('11111111-1111-1111-1111-111111111111')
  })
})

describe('档位可发 / 可写判定', () => {
  it('✅ 租户档没选租户时不可发（缺 X-Tenant-Id 只会换来一个 400）', () => {
    expect(isRagTargetReady(tenantTarget(''))).toBe(false)
    expect(isRagTargetReady(tenantTarget('   '))).toBe(false)
    expect(isRagTargetReady(PLATFORM_TARGET)).toBe(true)
  })

  it('✅ 租户档恒可写；平台档**有票**才可写；盘点档永不可写', () => {
    expect(isRagWriteTarget(TENANT)).toBe(true)
    expect(isRagWriteTarget(PLATFORM_TARGET)).toBe(false)
    expect(isRagWriteTarget(platformTarget(TICKET))).toBe(true)
    expect(isRagWriteTarget(platformTarget(null))).toBe(false)
    expect(isRagWriteTarget(tenantTarget(''))).toBe(false)
    expect(isRagWriteTarget({ scope: 'ALL' })).toBe(false)
  })

  it('✅ 过期票据按「没有票据」处理（不给一个注定 403 的按钮）', () => {
    expect(isRagWriteTarget(platformTarget(EXPIRED_TICKET))).toBe(false)
  })

  it('✅ 检索探针只在租户档可用：平台档能写也不代表能在这里发检索', () => {
    expect(isRagRetrievalTarget(TENANT)).toBe(true)
    expect(isRagRetrievalTarget(tenantTarget(''))).toBe(false)
    expect(isRagRetrievalTarget(platformTarget(TICKET))).toBe(false)
    expect(isRagRetrievalTarget({ scope: 'ALL' })).toBe(false)
  })
})
