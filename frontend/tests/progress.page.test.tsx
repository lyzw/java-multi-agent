/**
 * 进度页接线测试（FE-D9 / G3）
 *
 * ⛔ 核心纪律（沿用项目 R-016 教训 + FE-D8 范式）：
 *   - 既要「行为测试」，也要「连通性测试」（确实调了状态接口）
 *   - 必须有「反证」：数据来自 mock，改 mock 值 UI 必随之变化
 *   - **诚实边界**：状态以 G3 为准；读不到状态是**降级**不是**故障**
 *
 * ⚠️ 本类的存在意义：接线前进度页的状态**只**来自 SSE 事件，
 *    而 `run_event` 只有 `RUN_CREATED` → 即便 Run 早已完成，
 *    页面也永远显示「正在处理」，**且持续打后端**（每 3 秒一次，直到用户离开）。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactElement } from 'react'
import { SessionProvider } from '@/auth/session'
import { RunProgressPage } from '@/pages/RunProgressPage'
import type { RunStatusResponse } from '@/api/types'

const getRunStatusMock = vi.hoisted(() => vi.fn())
const openEventStreamMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    getRunStatus: getRunStatusMock,
    openEventStream: openEventStreamMock,
  }
})

function renderAt(path: string, element: ReactElement, pattern: string): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path={pattern} element={element} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** 标准状态样本。 */
function sampleStatus(overrides?: Partial<RunStatusResponse>): RunStatusResponse {
  return {
    runId: 'r-1',
    state: 'SUCCEEDED',
    createdAt: '2026-10-20T08:00:00Z',
    completedAt: '2026-10-20T08:00:05Z',
    attempt: 1,
    errorCode: null,
    ...overrides,
  }
}

/** 空的 SSE 响应（无事件）——对应「run_event 只有 RUN_CREATED」之外的极简场景。 */
function emptyStream(): Response {
  return new Response('', { status: 200 })
}

beforeEach(() => {
  vi.useFakeTimers()
  getRunStatusMock.mockReset()
  openEventStreamMock.mockReset()
  openEventStreamMock.mockResolvedValue(emptyStream())
})

/**
 * ⚠️ 全程使用假定时器，并在每个用例后**清空所有待触发定时器**。
 *
 * 原因：本页有**两条轮询链**（SSE 3s + 状态 10s）。
 * 若某个用例用真定时器跑完，其待触发的定时器会**跨用例存活**，
 * 在后续用例推进时间时突然触发 → 表现为「别的用例的断言莫名多出一次调用」。
 *
 * ⚠️ 这是「单跑绿、联跑红」的经典形态——与 setup.ts 里
 * 「显式注册 cleanup」的注释属同一族。
 */
afterEach(() => {
  vi.clearAllTimers()
  vi.useRealTimers()
})

describe('T1：进度页接上 G3 状态查询', () => {
  it('应调用状态接口并显示真实状态', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'RUNNING' }))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    // ⚠️ 假定时器下 testing-library 的 waitFor/findBy* 不可用
    //    （其自动推进只认 jest 的假定时器），故改为「推进定时器 + 同步断言」。
    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(getRunStatusMock).toHaveBeenCalledWith('r-1')
    // 状态来自 G3（RUNNING）
    // ⚠️ RUNNING 的标签是「执行中」（runState.stateLabel）
    expect(screen.getByText('执行中')).toBeInTheDocument()
  })
})

describe('T2：⛔ Run 已完成时不得再显示「正在处理」', () => {
  it('状态为 SUCCEEDED 时应显示完成态', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'SUCCEEDED' }))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(getRunStatusMock).toHaveBeenCalled()
    const body = document.body.textContent ?? ''
    // ⚠️ 接线前这里必然命中「正在处理，请稍候」——
    //    Run 早已完成却让用户「请稍候」，是直接的误导。
    expect(body).not.toContain('正在处理')
    expect(body).not.toContain('正在处理，请稍候')
    expect(screen.getByText('已完成')).toBeInTheDocument()
  })
})

describe('T3：⛔ 到达终态即停一切轮询', () => {
  it('读到终态后 SSE 轮询应停止', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'SUCCEEDED' }))
    openEventStreamMock.mockResolvedValue(emptyStream())

    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')
    await act(async () => { await vi.advanceTimersByTimeAsync(50) })

    const callsAfterTerminal = openEventStreamMock.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })

    expect(openEventStreamMock.mock.calls.length).toBe(callsAfterTerminal)
  })

  it('状态轮询在终态后自停', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'SUCCEEDED' }))

    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')
    await act(async () => { await vi.advanceTimersByTimeAsync(50) })
    const callsAtTerminal = getRunStatusMock.mock.calls.length

    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    expect(getRunStatusMock.mock.calls.length).toBe(callsAtTerminal)
  })
})

describe('T4：状态接口失败 → 降级，不是故障', () => {
  it('应给出明确提示，且不显示成系统故障', async () => {
    getRunStatusMock.mockRejectedValue(new Error('boom'))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(screen.getByText(/暂时读不到最新状态/)).toBeInTheDocument()
    const body = document.body.textContent ?? ''
    expect(body).not.toContain('系统故障')
  })
})

describe('T5：⚠️ 限流配额纪律（与 SSE 共用 EVENT_QUERY 桶，30 次/60 秒）', () => {
  it('状态轮询在 60 秒内不得超过 7 次（间隔 10 秒）', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'RUNNING' }))

    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })

    // ⚠️ 若间隔被改成 3 秒，60 秒内会是 21 次 → 与 SSE 的 20 次/分合计 41 > 30 → 必然 429
    expect(getRunStatusMock.mock.calls.length).toBeLessThanOrEqual(7)
  })
})

describe('T6：⛔ 不得再显示过时的 G5 占位', () => {
  it('页面不应出现「后端尚未提供 result:read 端点」', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'SUCCEEDED' }))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(getRunStatusMock).toHaveBeenCalled()
    const body = document.body.textContent ?? ''
    // ⚠️ G5 已由 P4-D10 关闭，结果页已接真实数据（FE-D8）
    expect(body).not.toContain('result:read')
    expect(body).not.toContain('G5')
  })

  it('成功后应提供跳转到结果页的入口', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'SUCCEEDED' }))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(screen.getByText('查看归因结果')).toBeInTheDocument()
  })
})

describe('T7：⛔ 绝不编造进度百分比', () => {
  it('页面不含任何百分比进度', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'RUNNING' }))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(getRunStatusMock).toHaveBeenCalled()
    const body = document.body.textContent ?? ''
    expect(body).not.toMatch(/\d+\s*%/)
  })
})
