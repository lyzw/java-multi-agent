// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

import {
  useRagIngestJobs,
  FRONTEND_INGEST_POLL_INTERVAL_MS,
} from '@/features/rag/useRagIngestJobs'
import { parseStagePercent } from '@/pages/rag/ragIngestUi'
import type { RagIngestJob, RagIngestJobStatus } from '@/api/types'

/**
 * frontend 主应用的摄取轮询纪律（与 admin-console 版逐条对齐）。
 *
 * ⚠️ 守的四个契约：
 *    · 终态即停（不留空闲定时器）；
 *    · 空列表不轮询（管理面与检索共用 /api/v1）；
 *    · 不可见不请求；
 *    · 出错不清空。
 */

function job(status: RagIngestJobStatus, overrides: Partial<RagIngestJob> = {}): RagIngestJob {
  return {
    jobId: 'job-1',
    tenantId: 'tenant-1',
    sourceId: 'src-1',
    status,
    stage: null,
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

function setVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => state,
  })
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('useRagIngestJobs（frontend）', () => {
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

  it('进行中任务按周期轮询', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async () => okJson([job('PARSING')]))

    const { result } = renderHook(() => useRagIngestJobs('tenant-1'))
    await waitFor(() => expect(result.current.state.status).toBe('success'))
    expect(result.current.hasInFlight).toBe(true)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(FRONTEND_INGEST_POLL_INTERVAL_MS * 2)
    })
    expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(3)
  })

  it('终态后停止轮询', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockResolvedValueOnce(okJson([job('EMBEDDING')]))
    fetchMock.mockResolvedValue(okJson([job('SUCCEEDED')]))

    const { result } = renderHook(() => useRagIngestJobs('tenant-1'))
    await waitFor(() => expect(result.current.state.polling).toBe(true))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(FRONTEND_INGEST_POLL_INTERVAL_MS)
    })
    await waitFor(() => expect(result.current.state.polling).toBe(false))

    const calls = fetchMock.mock.calls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(FRONTEND_INGEST_POLL_INTERVAL_MS * 10)
    })
    expect(fetchMock.mock.calls.length).toBe(calls)
  })

  it('空列表不轮询', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async () => okJson([]))

    const { result } = renderHook(() => useRagIngestJobs('tenant-1'))
    await waitFor(() => expect(result.current.state.status).toBe('empty'))
    const calls = fetchMock.mock.calls.length

    await act(async () => {
      await vi.advanceTimersByTimeAsync(FRONTEND_INGEST_POLL_INTERVAL_MS * 5)
    })
    expect(fetchMock.mock.calls.length).toBe(calls)
  })

  it('页签隐藏时不发请求', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async () => okJson([job('PARSING')]))

    const { result } = renderHook(() => useRagIngestJobs('tenant-1'))
    await waitFor(() => expect(result.current.state.status).toBe('success'))
    const before = fetchMock.mock.calls.length

    setVisibility('hidden')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(FRONTEND_INGEST_POLL_INTERVAL_MS * 3)
    })
    expect(fetchMock.mock.calls.length).toBe(before)
  })

  it('出错保留已有任务', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockResolvedValueOnce(okJson([job('PARSING')]))
    fetchMock.mockRejectedValue(new Error('boom'))

    const { result } = renderHook(() => useRagIngestJobs('tenant-1'))
    await waitFor(() => expect(result.current.state.jobs).toHaveLength(1))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(FRONTEND_INGEST_POLL_INTERVAL_MS)
    })
    await waitFor(() => expect(result.current.state.status).toBe('error'))
    expect(result.current.state.jobs).toHaveLength(1)
  })

  it('空租户不发请求', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async () => okJson([]))

    renderHook(() => useRagIngestJobs('  '))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(FRONTEND_INGEST_POLL_INTERVAL_MS * 3)
    })
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('parseStagePercent（frontend）', () => {
  it('解析阶段百分比，越界或非法返回 null', () => {
    expect(parseStagePercent('PARSING 60')).toBe(60)
    expect(parseStagePercent('EMBEDDING 5')).toBe(5)
    expect(parseStagePercent('PARSING')).toBeNull()
    expect(parseStagePercent(null)).toBeNull()
    expect(parseStagePercent('PARSING 120')).toBeNull()
  })
})
