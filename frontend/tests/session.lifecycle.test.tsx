/**
 * 会话生命周期测试（FE-D4 §1.1 F-15）
 *
 * ⛔ 本文件的核心价值：证明 `expiringSoon` **真的会变**。
 *
 * 背景（F-15 的原始形态）：
 *   `session.tsx` 曾用「每秒 setInterval 推 tick」驱动到期提示，
 *   但 `value` 的 useMemo 依赖数组里**没有 tick** ——
 *   每秒重渲染全部命中 memo 缓存，`expiringSoon` 永远等于登录瞬间的值。
 *   登录时 remaining≈900s → `isExpiringSoon` 返回 false → **此后永远 false**。
 *   且当时无任何 UI 消费它，于是：
 *     · 声明了功能（SessionState.expiringSoon）
 *     · 有实现（isExpiringSoon + tick）
 *     · 有 150 项全绿的测试
 *     · 但功能在运行时**完全不存在**
 *
 * 所以这里的断言必须是「推进时钟后状态改变」，
 * 而不是「初始值为 false」—— 后者在缺陷形态下同样通过。
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider, useSession } from '@/auth/session'
import { EXPIRY_WARN_SECONDS } from '@/auth/tokenStorage'

/** 从 provider 内部读出当前值 —— 避免依赖任何 UI 文本 */
function SessionProbe(): React.ReactElement {
  const { expiringSoon, authenticated } = useSession()
  return (
    <div>
      <span data-testid="expiring">{String(expiringSoon)}</span>
      <span data-testid="authenticated">{String(authenticated)}</span>
    </div>
  )
}

function renderProbe(): void {
  render(
    <MemoryRouter>
      <SessionProvider>
        <SessionProbe />
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** 以 expiresIn 秒数构造一个会话并写入 sessionStorage */
function seedSession(expiresIn: number): void {
  const expiresAt = Date.now() + expiresIn * 1000
  window.sessionStorage.setItem(
    'agent.session',
    JSON.stringify({ accessToken: 'tok-probe', expiresAt }),
  )
}

describe('会话到期预警（F-15）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    vi.useFakeTimers()
    // 固定时钟起点，使所有计算可复现
    vi.setSystemTime(new Date('2026-10-20T02:00:00.000Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('刚登录时不应处于预警窗口', () => {
    seedSession(900)
    renderProbe()
    expect(screen.getByTestId('expiring')).toHaveTextContent('false')
  })

  /**
   * ⛔ 这是 F-15 的**主断言**：
   *    推进到「到期前 60s」之后，expiringSoon 必须变为 true。
   *    在缺陷形态下（tick 不在 useMemo 依赖里）这里永远是 false → 用例转红。
   */
  it('推进到到期前 60s 内应进入预警窗口', () => {
    seedSession(900)
    renderProbe()

    // 900s TTL，预警点在 840s 处。推进 845s → 剩余 55s，应已预警
    act(() => {
      vi.advanceTimersByTime(845_000)
    })

    expect(screen.getByTestId('expiring')).toHaveTextContent('true')
  })

  it('预警应恰好发生在到期前 60s（不是提前更多）', () => {
    seedSession(900)
    renderProbe()

    // 839s 处剩余 61s —— 恰好差 1s 未进窗口
    act(() => {
      vi.advanceTimersByTime(839_000)
    })
    expect(screen.getByTestId('expiring')).toHaveTextContent('false')

    // 再推进 2s → 剩余 59s，进入窗口
    act(() => {
      vi.advanceTimersByTime(2_000)
    })
    expect(screen.getByTestId('expiring')).toHaveTextContent('true')
  })

  it('剩余不足 60s 的会话应在挂载时立即预警（无等待）', () => {
    seedSession(30)
    act(() => { renderProbe() })
    // 不等任何定时器，初始即应为 true
    expect(screen.getByTestId('expiring')).toHaveTextContent('true')
  })

  it('无会话时不应预警', () => {
    renderProbe()
    expect(screen.getByTestId('expiring')).toHaveTextContent('false')
  })

  it('到期应清空会话并置失效标记', () => {
    seedSession(900)
    renderProbe()

    act(() => {
      vi.advanceTimersByTime(900_000)
    })

    expect(screen.getByTestId('authenticated')).toHaveTextContent('false')
    expect(window.sessionStorage.getItem('agent.session')).toBeNull()
  })

  it('预警窗口宽度应恰好等于 EXPIRY_WARN_SECONDS', () => {
    // 锁住契约：900 - 60 = 840，预警定时器必须设在 840s 处
    expect(EXPIRY_WARN_SECONDS).toBe(60)
    seedSession(900)
    renderProbe()

    act(() => {
      vi.advanceTimersByTime(840_000)
    })
    expect(screen.getByTestId('expiring')).toHaveTextContent('true')
  })
})
