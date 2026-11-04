/**
 * 摄取任务轮询 Hook（frontend 主应用版）。
 *
 * <p>与 admin-console 的 `useRagIngestJobs` **语义一致、签名不同**：
 * 主应用的管理端点按 `tenantId` 字符串发请求（无平台档 / 治理票据概念），
 * 故这里直接收 `tenantId`，不引入 `RagTarget`。
 *
 * <p><b>⚠️ 为什么要有这个 Hook（而不是复用 `RagIngestJobList` 内部的轮询）</b>：
 * `RagIngestJobList` 的轮询依赖 `jobs` 数组本身（`useEffect(..., [hasInFlight, tenantId, jobs])`），
 * 每次 `setJobs` 都会重建 `setInterval`——定时器被反复清掉重建，实际周期漂移，
 * 且无法被「来源列表」「载体列表」复用（它们只需要一个 `Map<sourceId, job>`）。
 * 本 Hook 把轮询与渲染解耦，三条纪律集中在一处。
 *
 * <p><b>四条纪律</b>（与 admin-console 版逐条对齐，改动请同步两边）：
 * <ol>
 *   <li>没有进行中任务 → 不排下一次（空闲不轮询）；</li>
 *   <li>页面不可见 → 跳过本次，切回前台立即补拉；</li>
 *   <li>终态即停（SUCCEEDED / FAILED / REJECTED）；</li>
 *   <li>出错保留已有数据，只把错误单独成态。</li>
 * </ol>
 */

import { useCallback, useEffect, useRef, useState } from 'react'

import { listRagIngestJobs } from '@/api/client'
import type { RagIngestJob, RagIngestJobStatus } from '@/api/types'

/** 轮询周期（毫秒）。 */
export const FRONTEND_INGEST_POLL_INTERVAL_MS = 2000

/** 轮询次数上限（2s × 150 = 5 分钟）。 */
export const FRONTEND_INGEST_MAX_POLLS = 150

/** 进行中状态集合（与 `RagIngestJobList` 的 `IN_FLIGHT` 口径一致）。 */
const IN_FLIGHT: ReadonlySet<RagIngestJobStatus> = new Set([
  'PENDING',
  'PARSING',
  'CHUNKING',
  'EMBEDDING',
  'INDEXING',
])

/** 任务是否仍在进行中。 */
export function isIngestJobInFlight(status: RagIngestJobStatus): boolean {
  return IN_FLIGHT.has(status)
}

export type IngestJobsStatus = 'idle' | 'loading' | 'success' | 'empty' | 'error'

export interface IngestJobsState {
  status: IngestJobsStatus
  jobs: RagIngestJob[]
  error: string | null
  polling: boolean
}

export interface UseIngestJobsOptions {
  /** 只看某来源；不传 = 本租户最近的任务 */
  sourceId?: string
  limit?: number
  enabled?: boolean
}

export interface UseIngestJobsResult {
  state: IngestJobsState
  /** 手动重载（重置轮询计数并立即拉一次） */
  reload: () => void
  hasInFlight: boolean
}

const IDLE: IngestJobsState = { status: 'idle', jobs: [], error: null, polling: false }

/**
 * 轮询本租户（或某来源）的摄取任务。
 *
 * @param tenantId 租户标识（空串 = 不发请求）
 * @param opts     过滤与开关
 */
export function useRagIngestJobs(
  tenantId: string,
  opts: UseIngestJobsOptions = {},
): UseIngestJobsResult {
  const { sourceId, limit = 50, enabled = true } = opts
  const [state, setState] = useState<IngestJobsState>(IDLE)
  const [reloadNonce, setReloadNonce] = useState(0)

  const key = `${tenantId}:${sourceId ?? ''}:${limit}:${enabled}`

  /** 发请求用当前 tenantId（避免 effect 依赖对象/闭包旧值）。 */
  const tenantRef = useRef(tenantId)
  tenantRef.current = tenantId

  const load = useCallback(async (): Promise<boolean> => {
    const tid = tenantRef.current
    if (tid.trim() === '') {
      setState(IDLE)
      return false
    }
    try {
      const jobs = await listRagIngestJobs(tid.trim(), { sourceId, limit })
      const inFlight = jobs.some((j) => isIngestJobInFlight(j.status))
      setState({
        status: jobs.length === 0 ? 'empty' : 'success',
        jobs,
        error: null,
        polling: inFlight,
      })
      return inFlight
    } catch (e) {
      // ⚠️ 出错保留已有 jobs：一次抖动不该把正在看的列表抹空
      setState((prev) => ({
        status: 'error',
        jobs: prev.jobs,
        error: e instanceof Error ? e.message : '读取摄取任务失败',
        polling: false,
      }))
      return false
    }
  }, [sourceId, limit])

  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | null = null
    let polls = 0
    let inFlight = true

    const clear = (): void => {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
    }

    const schedule = (): void => {
      if (cancelled || !inFlight) return
      if (polls >= FRONTEND_INGEST_MAX_POLLS) return
      clear()
      timer = setTimeout(() => {
        void tick()
      }, FRONTEND_INGEST_POLL_INTERVAL_MS)
    }

    const tick = async (): Promise<void> => {
      if (cancelled) return
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
        schedule()
        return
      }
      polls += 1
      inFlight = await load()
      if (cancelled) return
      schedule()
    }

    /** 切回前台立即补拉。 */
    const onVisible = (): void => {
      if (cancelled) return
      if (typeof document === 'undefined' || document.visibilityState !== 'visible') return
      clear()
      polls += 1
      void load().then((still) => {
        if (cancelled) return
        inFlight = still
        schedule()
      })
    }

    if (!enabled || tenantId.trim() === '') {
      setState(IDLE)
      return () => {
        cancelled = true
      }
    }

    setState((prev) => ({ ...prev, status: prev.jobs.length === 0 ? 'loading' : prev.status }))
    polls += 1
    void load().then((still) => {
      if (cancelled) return
      inFlight = still
      schedule()
    })

    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', onVisible)
    }

    return () => {
      cancelled = true
      clear()
      if (typeof document !== 'undefined') {
        document.removeEventListener('visibilitychange', onVisible)
      }
    }
    // 依赖原语 key（不是 tenantId 对象）+ reloadNonce（手动重启）
  }, [key, enabled, load, reloadNonce, tenantId])

  const hasInFlight = state.jobs.some((j) => isIngestJobInFlight(j.status))

  const reload = useCallback(() => {
    setReloadNonce((n) => n + 1)
  }, [])

  return { state, reload, hasInFlight }
}
