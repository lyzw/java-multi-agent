/**
 * 摄取任务轮询 Hook（V1.0.2 §5 / 交互文档 §4.5「状态反馈」）。
 *
 * <p><b>为什么需要它</b>：上传接口早就会返回 `jobId`（`createIngestJobQuietly`），
 * 但 admin-console 从来没消费过——用户上传一本 148 页教材后要等约 450 秒，
 * 期间界面**没有任何进度**，无法判断「在跑」还是「挂了」。
 * 本 Hook 把 `/ingest-jobs` 的轮询补上，是「让进度可见」这一期的核心。
 *
 * <p><b>⚠️ 四条轮询纪律</b>（本 Hook 的契约，改动前请先读）：
 * <ol>
 *   <li><b>只在该轮询时轮询</b>：拿到数据后**没有**进行中任务 → 不再排下一次。
 *       管理面与检索共用 `/api/v1` 通道，空闲轮询是纯浪费且会污染审计。</li>
 *   <li><b>只在页面可见时轮询</b>：`visibilityState === 'hidden'` 时跳过本次并推迟；
 *       切回前台立即补拉一次——用户切走再回来看到的必须是**最新**状态，
 *       而不是停在「解析中」的僵尸卡片。</li>
 *   <li><b>终态即停</b>：全部任务进入 SUCCEEDED / FAILED / REJECTED 后停止，
 *       不保留空闲定时器。</li>
 *   <li><b>出错不清空</b>：轮询失败保留已有数据，错误单独成态。
 *       一次网络抖动不该让管理员正在看的列表变空。</li>
 * </ol>
 *
 * <p><b>⚠️ 轮询上限</b>：`INGEST_MAX_POLLS`（150 次 × 2s = 5 分钟）。
 * 超限即停并置 `exhausted`，由界面提示「可手动刷新」，而不是无限打后端。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { listRagIngestJobs } from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import { ApiError } from '@/api/ragClient'
import { isRagTargetReady } from '@/api/ragClient'
import { isRagIngestJobInFlight } from '@/api/ragTypes'
import type { RagIngestJob } from '@/api/ragTypes'

/** 轮询周期（毫秒）。与后端 worker 的消费轮询同量级，够快也不打爆管理面。 */
export const INGEST_POLL_INTERVAL_MS = 2000

/** 轮询次数上限（防止用户离开页面后无限轮询）。 */
export const INGEST_MAX_POLLS = 150

/** 列表状态（沿用管理面五态：默认/加载/空/错误/超时）。 */
export type RagIngestListStatus = 'idle' | 'loading' | 'success' | 'empty' | 'error' | 'timeout'

export interface RagIngestJobsState {
  status: RagIngestListStatus
  jobs: RagIngestJob[]
  error: string | null
  /** 是否仍在轮询 */
  polling: boolean
  /** 是否因触顶而停止轮询（界面据此给「手动刷新」提示） */
  exhausted: boolean
}

export interface UseRagIngestJobsOptions {
  /** 指定来源（省略 = 本租户最近的任务） */
  sourceId?: string
  /** 条数上限 */
  limit?: number
  /** 是否启用（默认 true；只读展示场景可关掉） */
  enabled?: boolean
}

export interface UseRagIngestJobsResult {
  state: RagIngestJobsState
  /** 手动重载（重置轮询计数并立即拉一次） */
  reload: () => void
  /** 是否还有进行中的任务 */
  hasInFlight: boolean
}

const IDLE: RagIngestJobsState = {
  status: 'idle',
  jobs: [],
  error: null,
  polling: false,
  exhausted: false,
}

/**
 * 轮询某个作用域 / 来源下的摄取任务。
 *
 * @param target 作用域目标（租户档未选租户时不发请求）
 * @param opts   过滤与开关
 */
export function useRagIngestJobs(
  target: RagTarget,
  opts: UseRagIngestJobsOptions = {},
): UseRagIngestJobsResult {
  const { sourceId, limit = 50, enabled = true } = opts
  const [state, setState] = useState<RagIngestJobsState>(IDLE)
  /** 手动重载计数：自增即重启轮询循环。 */
  const [reloadNonce, setReloadNonce] = useState(0)

  /**
   * 依赖用原语拼 key：target 是对象，每次渲染换引用会让 effect 反复重跑。
   *
   * ⚠️ key **不含票据**：票据是授权态不是查询条件，
   *    每领一次票就重拉列表会把管理员设好的筛选冲掉（与 `readKey` 同一纪律）。
   */
  const targetKey = `${target.scope}:${target.tenantId ?? ''}:${sourceId ?? ''}:${limit}:${enabled}`

  /** 发请求时用**当前** target（含票据等不可序列化字段）。 */
  const targetRef = useRef(target)
  targetRef.current = target

  /**
   * 一次拉取。
   *
   * @returns 本次拉到的任务里是否还有进行中的（由调用方决定是否续轮）
   */
  const load = useCallback(async (): Promise<boolean> => {
    const current = targetRef.current
    // ⚠️ 租户档没选租户不发请求：后端会 400，「没选租户」是本地配置问题，
    //    不该被伪装成后端故障（与 useRagManagement 同一纪律）。
    if (!isRagTargetReady(current)) {
      setState(IDLE)
      return false
    }
    try {
      const jobs = await listRagIngestJobs(current, { sourceId, limit })
      const inFlight = jobs.some((j) => isRagIngestJobInFlight(j.status))
      setState({
        status: jobs.length === 0 ? 'empty' : 'success',
        jobs,
        error: null,
        polling: inFlight,
        exhausted: false,
      })
      return inFlight
    } catch (cause) {
      // ⚠️ 出错**保留已有 jobs**：一次抖动不该让正在看的列表变空。
      setState((prev) => ({
        status: cause instanceof ApiError && cause.status === 408 ? 'timeout' : 'error',
        jobs: prev.jobs,
        error: cause instanceof ApiError ? cause.message : '读取摄取任务失败',
        polling: false,
        exhausted: false,
      }))
      return false
    }
  }, [sourceId, limit])

  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | null = null
    let polls = 0
    /** 上一次拉到的数据里是否还有进行中任务。 */
    let inFlight = true

    /** 清理定时器（幂等）。 */
    const clear = (): void => {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
    }

    /**
     * 安排下一次轮询。
     *
     * ⚠️ 只在「确有进行中任务」且「没触顶」时才排。
     *    用 setTimeout 自递归而非 setInterval：一次慢请求不会让多次 tick 排队叠加。
     */
    const schedule = (): void => {
      if (cancelled || !inFlight) return
      if (polls >= INGEST_MAX_POLLS) {
        setState((prev) => ({ ...prev, polling: false, exhausted: true }))
        return
      }
      clear()
      timer = setTimeout(() => {
        void tick()
      }, INGEST_POLL_INTERVAL_MS)
    }

    /** 一次轮询：拉数据 → 按结果决定是否续轮。 */
    const tick = async (): Promise<void> => {
      if (cancelled) return
      // ⚠️ 不可见时不发请求：切走的标签页不该继续占用后端。
      //    推迟到下一个周期；切回来由 visibilitychange 立即补拉。
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
        schedule()
        return
      }
      polls += 1
      inFlight = await load()
      if (cancelled) return
      setState((prev) => ({ ...prev, polling: inFlight }))
      schedule()
    }

    /**
     * 切回前台立即补拉一次（不消耗轮询预算之外的额外上限）。
     *
     * ⚠️ 这条是「僵尸卡片」的根治手段：用户离开 10 分钟回来，
     *    必须看到最新状态，而不是一次都没刷新过的旧数据。
     */
    const onVisible = (): void => {
      if (cancelled) return
      if (document.visibilityState !== 'visible') return
      clear()
      polls += 1
      void load().then((still) => {
        if (cancelled) return
        inFlight = still
        setState((prev) => ({ ...prev, polling: still }))
        schedule()
      })
    }

    if (!enabled) {
      setState(IDLE)
      return () => {
        cancelled = true
      }
    }

    // 首拉：先置 loading（已有数据时不回退成 loading，避免闪烁）
    setState((prev) => ({ ...prev, status: prev.jobs.length === 0 ? 'loading' : prev.status }))
    polls += 1
    void load().then((still) => {
      if (cancelled) return
      inFlight = still
      setState((prev) => ({ ...prev, polling: still }))
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
    // 依赖原语 key（不是 target 对象）+ reloadNonce（手动重启）
  }, [targetKey, enabled, load, reloadNonce])

  const hasInFlight = state.jobs.some((j) => isRagIngestJobInFlight(j.status))

  const reload = useCallback(() => {
    setReloadNonce((n) => n + 1)
  }, [])

  return { state, reload, hasInFlight }
}
