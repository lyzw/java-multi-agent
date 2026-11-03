import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { listRagSources, ragErrorMessage, ApiError } from '@/api/ragClient'
import { PLATFORM_TARGET } from '@/api/ragClient'

/**
 * RAG 通道错误的文案分流（评测 P0-2）。
 *
 * ⚠️ 守的是**归因**不是措辞：错误的归因会把排查方向带偏。
 *    · RAG 的 401 = 它没验过平台令牌的签名（真因通常是两端 JWT 密钥不同源）；
 *      通用层却会把它说成「身份域隔离」——照着那个方向查会一路查错。
 *    · RAG 的 5xx = 服务端故障，控制台侧修不了，提示必须指向 RAG 服务日志，
 *      而不是让管理员反复点重试。
 *    · 网络层失败（status 0）要说清「连不上」，并给出服务默认端口。
 */

function stubFetchStatus(status: number, body: unknown = null): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      status,
      ok: status >= 200 && status < 300,
      text: async () => (body === null ? '' : JSON.stringify(body)),
    })) as unknown as typeof fetch,
  )
}

beforeEach(() => {
  window.sessionStorage.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('RAG 通道错误的文案分流', () => {
  it('✅ 401 归因「令牌没验过」，并点明两端密钥必须同源（不得说成身份域隔离）', async () => {
    stubFetchStatus(401)
    const err = await listRagSources(PLATFORM_TARGET).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(401)
    const message = (err as ApiError).message
    expect(message).toContain('RAG 服务拒绝了平台令牌')
    expect(message).toContain('PLATFORM_JWT_PRIVATE_KEY')
    expect(message).toContain('RAG_PLATFORM_JWT_PUBLIC_KEY')
    // ⚠️ 旧的通用归因对 RAG 是错的：RAG 与平台之间**不存在**租户身份域边界
    expect(message).not.toContain('身份域隔离')
  })

  it('✅ 5xx 说清「服务端故障、控制台侧修不了」，并指向 RAG 服务日志', async () => {
    stubFetchStatus(500, { timestamp: '2026-10-30T09:28:00Z', status: 500, error: 'Internal Server Error' })
    const err = await listRagSources(PLATFORM_TARGET).catch((e: unknown) => e)

    expect((err as ApiError).status).toBe(500)
    const message = (err as ApiError).message
    // ⚠️ 文案曾断言「RAG 服务内部错误」，实测把一次治理代码的作用域缺陷
    //    （平台档取抽检状态抛 ISE）包装成「服务故障」，排查方向被带偏。
    //    现在只承诺「这是服务端错误 + 去看日志」，不再替后端下结论。
    expect(message).toContain('HTTP 500')
    expect(message).toContain('服务端错误')
    expect(message).toContain('控制台侧无法修复')
    expect(message).toContain('RAG 服务日志')
  })

  it('✅ 网络层失败说清「连不上」并给出默认端口（status 0 不是 HTTP 码）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new Error('Failed to fetch')
      }),
    )
    const err = await listRagSources(PLATFORM_TARGET).catch((e: unknown) => e)

    expect((err as ApiError).status).toBe(0)
    const message = (err as ApiError).message
    expect(message).toContain('连不上 RAG 服务')
    expect(message).toContain('8081')
  })

  it('✅ 400 保留后端原话（多半是「没选租户」，后端文案本就具体）', async () => {
    stubFetchStatus(400, { message: '未提供 X-Tenant-Id' })
    const err = await listRagSources(PLATFORM_TARGET).catch((e: unknown) => e)

    expect((err as ApiError).status).toBe(400)
    expect((err as ApiError).message).toContain('未提供 X-Tenant-Id')
  })

  it('✅ 非 ApiError 也给得出人话，而不是把异常对象直接抛给界面', () => {
    expect(ragErrorMessage(new Error('boom'))).toBe('操作失败，请稍后重试')
  })
})
