import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { request, ApiError } from '@/api/client'
import * as tokenStorage from '@/auth/tokenStorage'

/**
 * 401 的分域处置（2026-10-28 前端端到端测试抓到的缺陷）。
 *
 * ⚠️ 判据只有一条：**401 是谁发的**。
 *    · `/platform/**` 是本控制台自己的会话域 → 401 说明令牌失效，清会话并跳登录；
 *    · 其余端点（`/runs`、`/cap/**`、`/admin/**`、`/config/**`）属**租户身份域**，
 *      平台令牌（aud=admin-console-api）打过去**必然** 401 —— 那是身份域隔离按设计生效。
 *
 * 旧实现把两者混为一谈：点「路由策略试跑」或「AI 实验室发起执行」会把人直接踢回登录页、
 * 草稿与会话一起丢失，而页面上为这两种情形写好的「通道不可达」提示**永远走不到**。
 */

function reply(status: number, body: unknown = null): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      status,
      ok: status >= 200 && status < 300,
      text: () => Promise.resolve(body === null ? '' : JSON.stringify(body)),
    }),
  )
}

beforeEach(() => {
  window.sessionStorage.clear()
  tokenStorage.storeSession({
    accessToken: 'platform-token',
    userId: 'u-1',
    roles: ['PLATFORM_SUPER_ADMIN'],
    expiresAt: Date.now() + 60_000,
  } as never)
  // jsdom 的 window.location 不可写：换成可断言的桩（跳登录靠写 href）
  Object.defineProperty(window, 'location', {
    configurable: true,
    writable: true,
    value: { href: 'http://localhost:5199/admin/tools' },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('会话域 401 · 令牌确实失效', () => {
  it('✅ /platform/** 返 401 → 清会话并跳登录页（带 basename，不是 /login）', async () => {
    reply(401)
    const cleared = vi.spyOn(tokenStorage, 'clearSession')

    await expect(request('/platform/v1/users')).rejects.toThrow(/登录已失效/)

    expect(cleared).toHaveBeenCalled()
    expect(window.location.href).toBe('/admin/login')
  })
})

describe('租户身份域 401/403 · 通道边界不是会话失效', () => {
  it.each(['/runs', '/cap/config/routing-policies/dry-run', '/admin/tenants/self', '/config/overrides'])(
    '✅ %s 返 401 → 会话保留、不跳登录，原样抛出 401 交给页面提示',
    async (path) => {
      reply(401)
      const cleared = vi.spyOn(tokenStorage, 'clearSession')

      const err = await request(path).catch((e: unknown) => e)

      expect(err).toBeInstanceOf(ApiError)
      expect((err as ApiError).status).toBe(401)
      expect(cleared).not.toHaveBeenCalled()
      expect(window.location.href).not.toBe('/admin/login')
      // 会话仍在：用户不至于因为一次跨域试探而被踢出控制台
      expect(tokenStorage.loadSession()?.accessToken).toBe('platform-token')
    },
  )

  it('✅ 租户域 403 同样保留会话（权限不足 ≠ 未登录）', async () => {
    reply(403, { message: '禁止访问' })
    const cleared = vi.spyOn(tokenStorage, 'clearSession')

    const err = await request('/runs').catch((e: unknown) => e)

    expect((err as ApiError).status).toBe(403)
    expect(cleared).not.toHaveBeenCalled()
  })
})
