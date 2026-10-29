import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  listRagSources,
  registerRagSource,
  getRagStatistics,
  tenantTarget,
  PLATFORM_TARGET,
  isRagTargetReady,
  isRagWriteTarget,
} from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'

/**
 * RAG 作用域头契约测试（RAG-D11 第四刀·第一刀）。
 *
 * ⚠️ 守的是**契约形态**，不是渲染：
 *    · `X-Rag-Scope` 必须始终携带——后端刻意不由「有没有 X-Tenant-Id」推断作用域，
 *      缺这个头等于悄悄按租户档走；
 *    · 平台档**不得**带 `X-Tenant-Id`——同时给两个身份后端返回 400；
 *    · 写请求在平台档仍会发出去（由后端 409 收口），但 UI 层用
 *      {@link isRagWriteTarget} 提前拦掉，两端都要成立。
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

  it('✅ 写端点在平台档照原样发出（由后端 409 收口，前端不伪装成成功）', async () => {
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

describe('档位可发 / 可写判定', () => {
  it('✅ 租户档没选租户时不可发（缺 X-Tenant-Id 只会换来一个 400）', () => {
    expect(isRagTargetReady(tenantTarget(''))).toBe(false)
    expect(isRagTargetReady(tenantTarget('   '))).toBe(false)
    expect(isRagTargetReady(PLATFORM_TARGET)).toBe(true)
  })

  it('✅ 只有租户档可写：平台全局语料在本刀只读', () => {
    expect(isRagWriteTarget(TENANT)).toBe(true)
    expect(isRagWriteTarget(PLATFORM_TARGET)).toBe(false)
    expect(isRagWriteTarget(tenantTarget(''))).toBe(false)
    expect(isRagWriteTarget({ scope: 'ALL' })).toBe(false)
  })
})
