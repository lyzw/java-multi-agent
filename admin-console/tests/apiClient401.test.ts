import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { request, ApiError, isAccessDenied } from '@/api/client'
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
  // ⚠️ 桩必须带 pathname/search：跳登录要靠它们算出「回哪去」（评测 P1-5）。
  //    只给 href 的旧桩会让 pathname 变 undefined，回跳目标算不出来。
  Object.defineProperty(window, 'location', {
    configurable: true,
    writable: true,
    value: {
      href: 'http://localhost:5199/admin/tools',
      pathname: '/admin/tools',
      search: '',
    },
  })
})

afterEach(() => {
  // ⚠️ 必须清掉本文件存的会话：所有测试文件共享同一个 fork（singleFork），
  //    sessionStorage 会残留给后续文件，害得隔壁对「请求不带 Authorization」
  //    的断言随执行顺序时好时坏。本文件只负责造会话，不该把状态留给别人。
  window.sessionStorage.clear()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('会话域 401 · 令牌确实失效', () => {
  it('✅ /platform/** 返 401 → 清会话并跳登录页（带 basename + 过期原因 + 回跳目标）', async () => {
    reply(401)
    const cleared = vi.spyOn(tokenStorage, 'clearSession')

    await expect(request('/platform/v1/users')).rejects.toThrow(/登录已失效/)

    expect(cleared).toHaveBeenCalled()
    // ⚠️ 评测 P1-5：裸跳 '/admin/login' 会让用户以为是自己点的，
    //    重新登录完又被送回默认页（/tenants），刚才的操作现场全丢。
    //    跳登录必须带**为什么**（reason=expired）与**回哪去**（next=当前路径）。
    expect(window.location.href).toBe('/admin/login?reason=expired&next=%2Ftools')
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

describe('ACCESS_DENIED · 业务无权限绝不跳登录', () => {
  it('✅ /platform/** 返 403 + code=ACCESS_DENIED → 会话保留、不跳登录（渲染无权限页）', async () => {
    reply(403, { code: 'ACCESS_DENIED', message: '无权执行该操作' })
    const cleared = vi.spyOn(tokenStorage, 'clearSession')

    const err = await request('/platform/v1/users').catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(403)
    expect((err as ApiError).code).toBe('ACCESS_DENIED')
    // ⚠️ 关键断言：已登录但无权限，绝不能清会话、绝不能跳登录
    expect(cleared).not.toHaveBeenCalled()
    expect(window.location.href).not.toBe('/admin/login')
    expect(tokenStorage.loadSession()?.accessToken).toBe('platform-token')
  })

  it('✅ 租户域 403 + code=ACCESS_DENIED → 识别为无权限、会话保留', async () => {
    reply(403, { code: 'ACCESS_DENIED', message: '无权执行该操作' })

    const err = await request('/runs').catch((e: unknown) => e)

    expect(isAccessDenied(err)).toBe(true)
    expect(tokenStorage.loadSession()?.accessToken).toBe('platform-token')
  })

  it('✅ isAccessDenied 对 UNAUTHENTICATED 必须返回 false（二者不可混为一谈）', async () => {
    reply(401, { code: 'UNAUTHENTICATED', message: '未认证' })

    const err = await request('/runs').catch((e: unknown) => e)

    expect(isAccessDenied(err)).toBe(false)
    expect((err as ApiError).code).toBe('UNAUTHENTICATED')
  })
})

describe('登录端点 401 · 凭据错误绝不是会话过期', () => {
  it('✅ /platform/v1/auth/login 返 401 → 会话保留、不跳登录，抛出「用户名或密码错误」', async () => {
    reply(401, { error: 'invalid_credentials' })
    const cleared = vi.spyOn(tokenStorage, 'clearSession')

    const err = await request('/platform/v1/auth/login').catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(401)
    expect((err as ApiError).message).toBe('用户名或密码错误')
    // ⚠️ 关键：登录失败绝不能清会话 / 跳登录（否则像「登录卡死 / 超时」）
    expect(cleared).not.toHaveBeenCalled()
    expect(window.location.href).not.toBe('/admin/login')
    expect(tokenStorage.loadSession()?.accessToken).toBe('platform-token')
  })
})
