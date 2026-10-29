// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'

import {
  clearRagGovernanceTicket,
  ensureRagGovernanceTicket,
  peekRagGovernanceTicket,
  requestRagGovernanceTicket,
  ticketAuthHeader,
  ragGovernanceTicketRemainingSeconds,
} from '@/api/ragTicket'
import type { RagGovernanceTicket } from '@/api/ragTicket'
import { useRagGovernanceTicket } from '@/features/rag/useRagGovernanceTicket'

/**
 * RAG 治理票据的前端纪律（RAG-D11 第四刀·第二刀 §2.3 / §2.7）。
 *
 * ⚠️ 守的三件事，每件都对应一个真实的泄露面：
 *    · **只存内存**：任何一次取票都不许碰 sessionStorage / localStorage——
 *      这张票能写「影响所有租户检索结果」的那批语料，落盘等于把授权留在磁盘上；
 *    · **不重复签发**：`ensure` 在未临近过期时复用，取票口有权限闸门与限流，
 *      每次点击都重新签发会稳定撞出 429，而 429 的文案对管理员毫无意义；
 *    · **过期即当作没有**：本地留 15 秒余量主动丢弃，界面不给一个会 403 的按钮。
 */

function ticketString(secondsLeft: number): string {
  return new Date(Date.now() + secondsLeft * 1000).toISOString()
}

function makeTicket(secondsLeft = 300): RagGovernanceTicket {
  return {
    accessToken: `tk-${secondsLeft}`,
    tokenType: 'Bearer',
    scope: 'PLATFORM',
    platformUserId: '55555555-5555-5555-5555-555555555555',
    expiresAt: ticketString(secondsLeft),
    jti: 'abcdef00-1111-2222-3333-444444444444',
  }
}

/** fetch 的实现：返回值决定这次「签」不算签（失败不计数，见下面 mockTicket）。 */
function okJson(body: unknown): Response {
  return {
    status: 200,
    ok: true,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

/**
 * 安装「每走到网络就计一次签发」的实现：首张票剩余 `firstRemainingSeconds` 秒，其后 300 秒。
 *
 * ⚠️ 计数写在实现内部：断言的是「真的又签发了一次」，不是桩的写法。
 *    默认桩不计数，所以要用计数的地方必须显式换桩。
 */
function countIssuances(firstRemainingSeconds: number): void {
  let used = false
  vi.mocked(fetch).mockImplementation(async () => {
    issued += 1
    const ticket = makeTicket(used ? 300 : firstRemainingSeconds)
    used = true
    return okJson(ticket)
  })
}

let issued: number
let setItemSpy: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  issued = 0
  window.sessionStorage.clear()
  window.localStorage.clear()
  // ⚠️ spy 挂在原型上：两处存储（session / local）的任何写入都会命中。
  setItemSpy = vi.spyOn(Storage.prototype, 'setItem')
  vi.stubGlobal('fetch', vi.fn(async () => okJson(makeTicket(300))))
  clearRagGovernanceTicket()
})

afterEach(() => {
  vi.unstubAllGlobals()
  setItemSpy.mockRestore()
})

describe('治理票据的取票契约', () => {
  it('✅ 走平台命名空间的一次 POST，body 指名档位', async () => {
    const t = await requestRagGovernanceTicket('PLATFORM')
    expect(t.scope).toBe('PLATFORM')
    const call = vi.mocked(fetch).mock.calls[0]
    expect(String(call?.[0])).toBe('/api/platform/v1/rag/governance-ticket')
    expect((call?.[1] as RequestInit).method).toBe('POST')
    expect(JSON.parse(String((call?.[1] as RequestInit).body))).toEqual({ scope: 'PLATFORM' })
  })

  it('✅ 任何一次取票都不写 sessionStorage / localStorage（凭据只准在内存里）', async () => {
    await ensureRagGovernanceTicket('PLATFORM')
    expect(setItemSpy).not.toHaveBeenCalled()
    expect(window.sessionStorage.length).toBe(0)
    expect(window.localStorage.length).toBe(0)
  })

  it('✅ ensure 在未临近过期处复用：连点两次只签一张票', async () => {
    countIssuances(300)
    await ensureRagGovernanceTicket('PLATFORM')
    await ensureRagGovernanceTicket('PLATFORM')
    expect(issued).toBe(1)
  })

  it('✅ 剩余时间进到 15 秒余量内视为「没有票」：宁可多签一次，不拿过期票打写端点', async () => {
    countIssuances(5)
    await ensureRagGovernanceTicket('PLATFORM')
    expect(issued).toBe(1)
    await ensureRagGovernanceTicket('PLATFORM')
    expect(issued).toBe(2)
  })

  it('✅ 过期票据的剩余秒数按 0 计（界面不能显示「还剩 -12 秒」）', () => {
    expect(ragGovernanceTicketRemainingSeconds(makeTicket(-30))).toBe(0)
    expect(ragGovernanceTicketRemainingSeconds(makeTicket(120), Date.now())).toBe(120)
  })

  it('✅ 请求头形态是「Bearer + 票据」，不是会话令牌', () => {
    expect(ticketAuthHeader(makeTicket())).toEqual({ Authorization: 'Bearer tk-300' })
  })

  it('✅ clear 之后 peek 为空：换档即作废有唯一入口', async () => {
    await ensureRagGovernanceTicket('PLATFORM')
    expect(peekRagGovernanceTicket()).not.toBeNull()
    clearRagGovernanceTicket()
    expect(peekRagGovernanceTicket()).toBeNull()
  })
})

describe('票据的界面态 hook', () => {
  it('✅ 领取成功后状态 ready 且持有票据；作废后回到 idle 且内存清空', async () => {
    const { result } = renderHook(() => useRagGovernanceTicket())
    expect(result.current.ticket).toBeNull()
    await act(async () => {
      result.current.obtain()
      await Promise.resolve()
    })
    expect(result.current.status).toBe('ready')
    expect(result.current.ticket?.accessToken).toBe('tk-300')
    act(() => {
      result.current.revoke()
    })
    expect(result.current.ticket).toBeNull()
    expect(result.current.status).toBe('idle')
    expect(peekRagGovernanceTicket()).toBeNull()
  })

  it('✅ 领取失败（403 无权限 / 503 通道关闭 / 429 限流）单独成态并转达原因', async () => {
    vi.mocked(fetch).mockImplementationOnce(async () => ({
      status: 403,
      ok: false,
      text: async () => JSON.stringify({ message: '没有 platform.resource.manage 权限' }),
    }) as unknown as Response)
    const { result } = renderHook(() => useRagGovernanceTicket())
    await act(async () => {
      result.current.obtain()
      await Promise.resolve()
    })
    expect(result.current.status).toBe('error')
    expect(result.current.message).toBe('没有 platform.resource.manage 权限')
    expect(result.current.ticket).toBeNull()
  })

  it('✅ 界面只显示 jti 与剩余秒数所需的数据，票据正文不外泄给存储', async () => {
    const { result } = renderHook(() => useRagGovernanceTicket())
    await act(async () => {
      result.current.obtain()
      await Promise.resolve()
    })
    expect(result.current.remainingSeconds).toBeGreaterThan(0)
    expect(setItemSpy).not.toHaveBeenCalled()
  })
})
