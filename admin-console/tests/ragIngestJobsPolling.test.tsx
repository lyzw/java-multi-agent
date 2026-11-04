// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

import { useRagIngestJobs, INGEST_POLL_INTERVAL_MS } from '@/features/rag/useRagIngestJobs'
import type { RagIngestJob } from '@/api/ragTypes'

/**
 * 摄取任务轮询的纪律守卫（交互文档 §4.5 / §10.2 防退化断言）。
 *
 * ⚠️ 守的是四条**可被下一个人轻易破坏**的契约：
 *    · **终态即停**——全部任务已终态后不得再有周期性请求；
 *    · **无任务不轮询**——空列表不起定时器（管理面与检索共用通道）；
 *    · **不可见不发请求**——切走的标签页不该继续占用后端；
 *    · **出错不清空**——一次抖动不得把正在看的列表抹成空。
 *
 * 桩打在最外层 `fetch`，断言的是「网络被调用了几次」，而不是 hook 内部状态，
 * 因为前者才是真正影响后端的量。
 */

function job(overrides: Partial<RagIngestJob> = {}): RagIngestJob {
  return {
    jobId: 'job-1',
    tenantId: 'tenant-1',
    sourceId: 'src-1',
    status: 'PARSING',
    stage: 'PARSING 40',
    attempt: 0,
    createdBy: null,
    parserVersion: null,
    embedModel: null,
    chunkCount: null,
    errorDetail: null,
    startedAt: null,
    finishedAt: null,
    createdAt: '2026-11-03T00:00:00Z',
    sourceRevokedCancel: false,
    ...overrides,
  }
}

function okJson(body: unknown): Response {
  return {
    status: 200,
    ok: true,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

const tenantTarget = { scope: 'TENANT' as const, tenantId: 'tenant-1' }

/** 把 document.visibilityState 伪装成指定值（jsdom 默认 visible）。 */
function setVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => state,
  })
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('useRagIngestJobs 轮询纪律', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.stubGlobal('fetch', vi.fn())
    setVisibility('visible')
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('拉到进行中的任务后，按周期持续轮询', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async () => okJson([job({ status: 'PARSING' })]))

    const { result } = renderHook(() => useRagIngestJobs(tenantTarget))

    await waitFor(() => {
      expect(result.current.state.status).toBe('success')
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(result.current.state.polling).toBe(true)
    expect(result.current.hasInFlight).toBe(true)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(INGEST_POLL_INTERVAL_MS * 2)
    })

    // 首拉 1 次 + 两个周期各 1 次
    expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(3)
  })

  it('全部任务终态后停止轮询（终态即停）', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockResolvedValueOnce(okJson([job({ status: 'PARSING' })]))
    fetchMock.mockResolvedValue(okJson([job({ status: 'SUCCEEDED' })]))

    const { result } = renderHook(() => useRagIngestJobs(tenantTarget))

    await waitFor(() => {
      expect(result.current.state.polling).toBe(true)
    })

    // 推进一个周期：此时拉到 SUCCEEDED，应停止
    await act(async () => {
      await vi.advanceTimersByTimeAsync(INGEST_POLL_INTERVAL_MS)
    })

    await waitFor(() => {
      expect(result.current.state.polling).toBe(false)
    })
    const callsAtStop = fetchMock.mock.calls.length

    // 再推进 10 个周期，不应再有请求
    await act(async () => {
      await vi.advanceTimersByTimeAsync(INGEST_POLL_INTERVAL_MS * 10)
    })

    expect(fetchMock.mock.calls.length).toBe(callsAtStop)
    expect(result.current.hasInFlight).toBe(false)
  })

  it('空任务列表不启动周期性轮询', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async () => okJson([]))

    const { result } = renderHook(() => useRagIngestJobs(tenantTarget))

    await waitFor(() => {
      expect(result.current.state.status).toBe('empty')
    })
    const callsAfterFirst = fetchMock.mock.calls.length

    await act(async () => {
      await vi.advanceTimersByTimeAsync(INGEST_POLL_INTERVAL_MS * 5)
    })

    expect(fetchMock.mock.calls.length).toBe(callsAfterFirst)
    expect(result.current.state.polling).toBe(false)
  })

  it('页面不可见时不发请求', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async () => okJson([job({ status: 'PARSING' })]))

    const { result } = renderHook(() => useRagIngestJobs(tenantTarget))
    await waitFor(() => {
      expect(result.current.state.status).toBe('success')
    })
    const before = fetchMock.mock.calls.length

    setVisibility('hidden')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(INGEST_POLL_INTERVAL_MS * 3)
    })

    // 隐藏期间 tick 被跳过（不增加请求）
    expect(fetchMock.mock.calls.length).toBe(before)
  })

  it('轮询出错时保留已有任务，不清空列表', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockResolvedValueOnce(okJson([job({ status: 'PARSING' })]))
    fetchMock.mockRejectedValue(new Error('network down'))

    const { result } = renderHook(() => useRagIngestJobs(tenantTarget))

    await waitFor(() => {
      expect(result.current.state.jobs).toHaveLength(1)
    })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(INGEST_POLL_INTERVAL_MS)
    })

    await waitFor(() => {
      expect(result.current.state.status).toBe('error')
    })
    // ⚠️ 关键：列表仍在，只是状态变 error
    expect(result.current.state.jobs).toHaveLength(1)
    expect(result.current.state.error).toBeTruthy()
  })

  it('租户档未选租户时不发任何请求', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async () => okJson([]))

    const { result } = renderHook(() =>
      useRagIngestJobs({ scope: 'TENANT', tenantId: '' }),
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(INGEST_POLL_INTERVAL_MS * 3)
    })

    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.current.state.status).toBe('idle')
  })

  it('enabled=false 完全不请求', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async () => okJson([]))

    renderHook(() => useRagIngestJobs(tenantTarget, { enabled: false }))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(INGEST_POLL_INTERVAL_MS * 3)
    })

    expect(fetchMock).not.toHaveBeenCalled()
  })
})
