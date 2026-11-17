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
import { ApiError } from '@/api/client'
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

/**
 * F7（E2E 2026-11-14 浏览器级复测）：⛔ 404 必须是**显式终态**。
 *
 * ⚠️ **缺陷背景**：Run 不存在或不属于当前主体时，后端统一返回 404
 *    （反探测纪律：404 不区分「不存在」与「无权限」）。
 *    修复前前端把它当成「临时读失败」——
 *      · 页面继续按 10 秒一拍轮询（白烧 EVENT_QUERY 限流配额）；
 *      · 状态标签回落成「正在处理」→ 用户对着一个**永远不会出现**的结果死等。
 *
 * ⛔ **反证要求**：把 404 换成 200 + 其它状态时，断言必须转红
 *    （否则测试只是在测 mock 本身）。
 */
describe('F7：⛔ 404（不存在 / 无权查看）必须显式终态，且停止轮询', () => {
  function notFoundError(): ApiError {
    return new ApiError({
      status: 404,
      code: 'NOT_FOUND',
      message: '资源不存在',
      fieldErrors: {},
    })
  }

  it('404 → 显示「资源不存在」，不再显示「正在处理」', async () => {
    getRunStatusMock.mockRejectedValue(notFoundError())
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(screen.getByText('资源不存在')).toBeInTheDocument()
    const body = document.body.textContent ?? ''
    expect(body).not.toContain('正在处理')
    // ⚠️ 「仍在同步 / 暂时读不到」是**临时故障**的话术；404 不是临时故障，
    //    说「正在同步」等于让用户对着永远不会出现的结果死等。
    expect(body).not.toContain('正在同步最新状态')
    expect(body).not.toContain('暂时读不到最新状态')
  })

  it('404 → 停止轮询（推进 60 秒也不应再发状态请求）', async () => {
    getRunStatusMock.mockRejectedValue(notFoundError())
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    const callsAfterFirst = getRunStatusMock.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    expect(getRunStatusMock.mock.calls.length).toBe(callsAfterFirst)
  })

  it('⚠️ 反证：200 时不得显示「资源不存在」（否则上两条只是在测 mock）', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'RUNNING' }))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(screen.queryByText('资源不存在')).toBeNull()
    const body = document.body.textContent ?? ''
    expect(body).toContain('正在处理')
  })
})

/**
 * N6（E2E 二轮优化，2026-11-14）：状态读取的 **403 / 401 同样是终态**。
 *
 * ⚠️ 修复前的真实缺陷（本轮新发现，F8 只修了 404）：
 *   · `useRunStatus` 只对 404 停轮询 → 403/401 仍每 10 秒打后端烧配额；
 *   · `shouldShowSyncing` 只排除 404 → 页面一边显示「正在处理 /
 *     正在同步最新状态…」，一边又显示「重试不会改变结果」，自相矛盾。
 *   · 状态标签回落成「正在处理」→ 让用户对着不会改变的结果死等。
 *
 * ⛔ 反证要求：5xx 必须仍按临时故障降级（否则就是把所有错误都写成终态）。
 */
describe('N6：⛔ 状态读取 403 / 401 必须显式终态、停轮询、不给重试', () => {
  function apiError(status: number, code: string, message: string): ApiError {
    return new ApiError({ status, code, message, fieldErrors: {} })
  }

  it('403 → 「无法查看状态」+ 终态说明，不得出现任何「正在处理」话术', async () => {
    getRunStatusMock.mockRejectedValue(apiError(403, 'ACCESS_DENIED', '无权查看该运行'))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(screen.getByText('无法查看状态')).toBeInTheDocument()
    const body = document.body.textContent ?? ''
    expect(body).toContain('不会改变结果')
    expect(body).not.toContain('正在处理')
    expect(body).not.toContain('正在同步最新状态')
    expect(body).not.toContain('暂时读不到最新状态')
    expect(screen.getByRole('button', { name: /刷新状态/ })).toBeDisabled()
  })

  it('403 → 状态查询与 SSE 两条轮询链都停', async () => {
    getRunStatusMock.mockRejectedValue(apiError(403, 'ACCESS_DENIED', '无权查看该运行'))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    const statusCalls = getRunStatusMock.mock.calls.length
    const streamCalls = openEventStreamMock.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    expect(getRunStatusMock.mock.calls.length).toBe(statusCalls)
    expect(openEventStreamMock.mock.calls.length).toBe(streamCalls)
  })

  it('401 → 唯一动作是重新登录，不给重试暗示', async () => {
    getRunStatusMock.mockRejectedValue(apiError(401, 'UNAUTHENTICATED', '登录已过期'))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(screen.getByText('无法查看状态')).toBeInTheDocument()
    const body = document.body.textContent ?? ''
    expect(body).toContain('重新登录')
    expect(body).not.toContain('正在同步最新状态')
  })

  it('⛔ 反证：5xx 是临时故障 → 仍显示「暂时读不到」、刷新可用、继续轮询', async () => {
    getRunStatusMock.mockRejectedValue(apiError(503, 'HTTP_503', '服务暂时不可用'))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(screen.getByText(/暂时读不到最新状态/)).toBeInTheDocument()
    expect(screen.queryByText('无法查看状态')).toBeNull()
    expect(screen.getByRole('button', { name: /刷新状态/ })).not.toBeDisabled()
    const before = getRunStatusMock.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(getRunStatusMock.mock.calls.length).toBeGreaterThan(before)
  })

  it('⛔ 混合路径：先 5xx 后 403，最终收敛为终态且停轮询', async () => {
    getRunStatusMock
      .mockRejectedValueOnce(apiError(503, 'HTTP_503', '服务暂时不可用'))
      .mockRejectedValue(apiError(403, 'ACCESS_DENIED', '无权查看该运行'))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(screen.getByText(/暂时读不到最新状态/)).toBeInTheDocument()
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(screen.getByText('无法查看状态')).toBeInTheDocument()
    expect(screen.queryByText(/暂时读不到最新状态/)).toBeNull()
    const calls = getRunStatusMock.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    expect(getRunStatusMock.mock.calls.length).toBe(calls)
  })

  it('⛔ 反证：200 RUNNING 不得显示「无法查看状态」', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'RUNNING' }))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    expect(screen.queryByText('无法查看状态')).toBeNull()
  })
})

/**
 * 真实失败路径门禁（规模化发布前必须覆盖的错误码 / 状态组合）。
 * 此前页面级只锁了 FAILED+MODEL_TIMEOUT 与 FAILED+BUDGET_EXHAUSTED 两组。
 */
describe('N3：失败 Run 终态的错误码分类（页面级硬门禁）', () => {
  const SERVICE_CONFIG_CODES = [
    'CONFIG_UNAVAILABLE',
    'BUDGET_EXHAUSTED',
    'AUTHZ_UNAVAILABLE',
    'NO_ACCOUNT',
    'CONFIG_MISSING',
    'AGENT_CONFIG_UNAVAILABLE',
  ]

  for (const code of SERVICE_CONFIG_CODES) {
    it(`⛔ FAILED + ${code}：不给重新发起、给回工作台、不透原码、停轮询`, async () => {
      getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'FAILED', errorCode: code }))
      renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

      await act(async () => { await vi.advanceTimersByTimeAsync(20) })
      const body = document.body.textContent ?? ''
      expect(body).toContain('服务配置尚未就绪')
      expect(body).toContain('联系管理员')
      expect(body).toContain('r-1')
      expect(body).not.toContain(code)
      expect(body).not.toContain('重新发起这道题')
      expect(body).not.toContain('再辅导一道')
      expect(screen.getByRole('link', { name: /回工作台/ })).toBeInTheDocument()
      expect(screen.queryByText('查看归因结果')).toBeNull()
      const calls = getRunStatusMock.mock.calls.length
      await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
      expect(getRunStatusMock.mock.calls.length).toBe(calls)
    })
  }

  it('⛔ FAILED + 后端真实未知码 MAX_LLM_CALLS：unknown 兜底、附运行标识、不给重试', async () => {
    getRunStatusMock.mockResolvedValue(
      sampleStatus({ state: 'FAILED', errorCode: 'MAX_LLM_CALLS' }),
    )
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    const body = document.body.textContent ?? ''
    expect(body).toContain('本次执行未完成')
    expect(body).toContain('r-1')
    expect(body).not.toContain('MAX_LLM_CALLS')
    expect(body).not.toContain('重新发起这道题')
    expect(body).not.toContain('再辅导一道')
  })

  it('FAILED + errorCode=null：兜底说明与 unknown advice 一致', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'FAILED', errorCode: null }))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    const body = document.body.textContent ?? ''
    expect(body).toContain('本次执行未完成')
    expect(body).not.toContain('重新发起这道题')
  })

  for (const code of ['MODEL_ERROR', 'PROVIDER_ERROR', 'EMPTY_ASSISTANT_OUTPUT']) {
    it(`⛔ FAILED + 临时故障码 ${code}：给重试入口且不透原码`, async () => {
      getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'FAILED', errorCode: code }))
      renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

      await act(async () => { await vi.advanceTimersByTimeAsync(20) })
      const body = document.body.textContent ?? ''
      expect(body).toContain('这次分析临时未能完成')
      expect(body).not.toContain(code)
      // 刷新后 location.state 丢失 ⇒ 文案是「再辅导一道」，不是名实不符的「这道题」
      expect(screen.getByRole('link', { name: /再辅导一道/ })).toBeInTheDocument()
    })
  }

  for (const code of ['MODEL_RATE_LIMITED', 'QUOTA_EXHAUSTED', 'MODEL_RETRY_EXHAUSTED']) {
    it(`⛔ FAILED + ${code}：显示真实终态原因、不给重试、停下轮询、绝不显示「执行中」`, async () => {
      getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'FAILED', errorCode: code }))
      renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

      await act(async () => { await vi.advanceTimersByTimeAsync(20) })
      const body = document.body.textContent ?? ''
      expect(body).toContain('本次分析未成功')
      expect(body).not.toContain(code)
      expect(body).not.toContain('执行中')
      expect(body).not.toContain('正在同步最新状态')
      expect(body).not.toContain('再辅导一道')
      expect(body).not.toContain('重新发起这道题')
      expect(screen.getByRole('link', { name: /回工作台/ })).toBeInTheDocument()
      const calls = getRunStatusMock.mock.calls.length
      await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
      expect(getRunStatusMock.mock.calls.length).toBe(calls)
    })
  }
})

/**
 * 问题 3 行为级专项实测（2026-11-14 四轮优化）
 *
 * ⚠️ 此前只有 `eventStream.poller.test.ts` 的 poller 单元用例与源码复核，
 *    **组件卸载链路从未实测**。这里用 fake timers 真的卸载后推进 60 秒，
 *    断言两条轮询链都不再发请求、定时器全部清空。
 *
 * 诚实边界：jsdom 里没有真实 GC / 堆增长测量，这能证明的是
 *    「不再发起新请求 + 定时器归零」，不等于证明真实浏览器无内存泄漏。
 */
describe('T9：组件卸载后两条轮询链都必须停（问题 3 行为级）', () => {
  it('⛔ unmount 后推进 60 秒，状态查询与 SSE 都不再发起请求', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'RUNNING' }))
    const { unmount } = render(
      <MemoryRouter initialEntries={['/runs/r-1/progress']}>
        <SessionProvider>
          <Routes>
            <Route path="/runs/:runId/progress" element={<RunProgressPage />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    const statusCalls = getRunStatusMock.mock.calls.length
    const sseCalls = openEventStreamMock.mock.calls.length
    expect(statusCalls).toBeGreaterThan(0)

    unmount()

    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    expect(getRunStatusMock.mock.calls.length).toBe(statusCalls)
    expect(openEventStreamMock.mock.calls.length).toBe(sseCalls)
    // ⚠️ 定时器没清干净会让这个数 > 0；这是「卸载后仍在跑」的直接信号
    expect(vi.getTimerCount()).toBe(0)
  })

  it('⛔ 反证：不卸载时同样推进 60 秒，状态轮询确实会增加（守卫有牙齿）', async () => {
    getRunStatusMock.mockResolvedValue(sampleStatus({ state: 'RUNNING' }))
    renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

    await act(async () => { await vi.advanceTimersByTimeAsync(20) })
    const before = getRunStatusMock.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    expect(getRunStatusMock.mock.calls.length).toBeGreaterThan(before)
  })
})

describe('非 FAILED 终态：TIMED_OUT / CANCELLED 不得伪装成进行中', () => {
  const cases: Array<[string, string, string]> = [
    ['TIMED_OUT', '已超时', '执行超时未能完成'],
    ['CANCELLED', '已取消', '本次执行已取消'],
  ]

  for (const [state, label, hint] of cases) {
    it(`⛔ ${state}：独立标签与说明、不给必然失败的重试、停轮询`, async () => {
      getRunStatusMock.mockResolvedValue(sampleStatus({ state, errorCode: null }))
      renderAt('/runs/r-1/progress', <RunProgressPage />, '/runs/:runId/progress')

      await act(async () => { await vi.advanceTimersByTimeAsync(20) })
      expect(screen.getByText(label)).toBeInTheDocument()
      expect(screen.getByText(hint)).toBeInTheDocument()
      const body = document.body.textContent ?? ''
      expect(body).not.toContain('正在处理')
      expect(body).not.toContain('正在同步最新状态')
      expect(body).not.toContain('重新发起这道题')
      const calls = getRunStatusMock.mock.calls.length
      await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
      expect(getRunStatusMock.mock.calls.length).toBe(calls)
    })
  }
})
