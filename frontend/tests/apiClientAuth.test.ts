/**
 * 权限拦截契约测试（权限重构 · 按 code 分支，而非裸 HTTP 状态）
 *
 * ⚠️ 核心纪律：拦截器**只**在归一化 code === 'UNAUTHENTICATED' 时清会话 + 跳登录；
 *    对于 ACCESS_DENIED（业务无权限，已登录但缺权限）或其它 code，一律**原样抛出**，
 *    绝不触碰会话、绝不跳登录——否则会把「正常业务操作被拒」误判为「未登录」，
 *    直接把用户踢回登录页、丢掉操作现场（2026-10-28 实测缺陷）。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as tokenStorage from '@/auth/tokenStorage'
import { ApiError, getConfig, setUnauthorizedHandler } from '@/api/client'
import { isAccessDenied } from '@/api/errors'

/** 桩 fetch：返回指定状态码 + 响应体 */
function reply(status: number, body: unknown = null): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      text: async () => (body === null ? '' : JSON.stringify(body)),
    }),
  )
}

let unauthorizedCalls = 0

beforeEach(() => {
  window.sessionStorage.clear()
  tokenStorage.saveSession('tenant-token', 900)
  unauthorizedCalls = 0
  setUnauthorizedHandler(() => {
    unauthorizedCalls += 1
  })
})

afterEach(() => {
  // ⚠️ 还原默认 no-op，避免串到其它文件的 handler
  setUnauthorizedHandler(() => {})
  window.sessionStorage.clear()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('401 UNAUTHENTICATED → 清会话 + 通知跳登录', () => {
  it('✅ code=UNAUTHENTICATED → 清会话并触发 onUnauthorized', async () => {
    reply(401, { code: 'UNAUTHENTICATED', message: '未认证' })
    const cleared = vi.spyOn(tokenStorage, 'clearSession')

    const err = await getConfig().catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).normalized.code).toBe('UNAUTHENTICATED')
    expect(cleared).toHaveBeenCalled()
    expect(unauthorizedCalls).toBe(1)
    // 会话确实被清空
    expect(tokenStorage.loadSession()).toBeNull()
  })

  it('✅ 旧形状 {error:"unauthorized"} 仍归一到 UNAUTHENTICATED → 清会话', async () => {
    reply(401, { error: 'unauthorized' })
    const cleared = vi.spyOn(tokenStorage, 'clearSession')

    const err = await getConfig().catch((e: unknown) => e)

    expect((err as ApiError).normalized.code).toBe('UNAUTHENTICATED')
    expect(cleared).toHaveBeenCalled()
  })
})

describe('403 ACCESS_DENIED → 保留会话，绝不跳登录', () => {
  it('✅ code=ACCESS_DENIED → 会话保留、不跳登录、原样抛出', async () => {
    reply(403, { code: 'ACCESS_DENIED', message: '无权执行该操作' })
    const cleared = vi.spyOn(tokenStorage, 'clearSession')

    const err = await getConfig().catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).normalized.code).toBe('ACCESS_DENIED')
    expect(cleared).not.toHaveBeenCalled()
    expect(unauthorizedCalls).toBe(0)
    // 会话仍在：用户不至于因为一次业务无权限而被踢出
    expect(tokenStorage.loadSession()?.accessToken).toBe('tenant-token')
  })

  it('✅ 旧形状 {error:"forbidden"} 仍归一到 ACCESS_DENIED → 不跳登录', async () => {
    reply(403, { error: 'forbidden' })
    const cleared = vi.spyOn(tokenStorage, 'clearSession')

    const err = await getConfig().catch((e: unknown) => e)

    expect((err as ApiError).normalized.code).toBe('ACCESS_DENIED')
    expect(cleared).not.toHaveBeenCalled()
  })

  it('✅ isAccessDenied 能识别 ACCESS_DENIED、且不误判 UNAUTHENTICATED', async () => {
    reply(403, { code: 'ACCESS_DENIED', message: '无权执行该操作' })
    const deniedErr = await getConfig().catch((e: unknown) => e)
    expect(isAccessDenied((deniedErr as ApiError).normalized)).toBe(true)

    reply(401, { code: 'UNAUTHENTICATED', message: '未认证' })
    const authErr = await getConfig().catch((e: unknown) => e)
    expect(isAccessDenied((authErr as ApiError).normalized)).toBe(false)
  })
})

describe('防御：即使 401 携带 ACCESS_DENIED code，也不清会话', () => {
  it('✅ code 决定行为，而非裸状态码', async () => {
    reply(401, { code: 'ACCESS_DENIED', message: '无权执行该操作' })
    const cleared = vi.spyOn(tokenStorage, 'clearSession')

    const err = await getConfig().catch((e: unknown) => e)

    expect((err as ApiError).normalized.code).toBe('ACCESS_DENIED')
    expect(cleared).not.toHaveBeenCalled()
    expect(unauthorizedCalls).toBe(0)
  })
})
