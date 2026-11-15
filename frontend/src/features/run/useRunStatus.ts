/**
 * Run 状态查询 hook（FE-D9 / G3）
 *
 * 单一数据源：`GET /api/runs/{runId}`——`run.state` 的**权威快照**。
 *
 * ⚠️⚠️ **限流配额（FE-D9 §2.3）**：状态端点与 SSE 端点**共用**限流桶
 *    `EVENT_QUERY`（**30 次 / 60 秒**）。SSE 轮询占 20 次/分，
 *    故状态轮询间隔取 **10 秒**（6 次/分）→ 合计 26 次/分，**余量 4**。
 *    任何新增轮询都必须重算此表，不得「再加一条链试试」。
 *
 * ⚠️ **终态自停**：读到终态即停止轮询（不再发请求）。
 *    这条很重要——SSE 流在本项目里**永远收不到终态事件**
 *    （`run_event` 只有 `RUN_CREATED`），故若只靠 SSE，
 *    页面会**每 3 秒打一次后端直到用户离开**。
 *
 * ⚠️ 防竞态：effect 内 `cancelled` 守卫，避免组件卸载（含 StrictMode 双挂载）后 setState。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { getRunStatus, ApiError } from '@/api/client'
import { errorGuidance } from '@/api/errorGuidance'
import { normalizeNetworkError, type NormalizedError } from '@/api/errors'
import { isTerminalState } from './runState'
import type { RunStatusResponse } from '@/api/types'

/** 状态轮询间隔（毫秒）——受限流配额约束，见文件头说明 */
export const STATUS_POLL_INTERVAL_MS = 10_000

/** 未知错误兜底文案（不给用户凭空猜测的具体原因） */
const FALLBACK_ERROR = '状态读取失败，请稍后重试'

export interface UseRunStatusResult {
  status: RunStatusResponse | null
  loading: boolean
  error: string | null
  /** ⚠️ N6：归一化错误，供页面按 status 分流（403/404 是终态，不给重试） */
  errorInfo: NormalizedError | null
  /**
   * Run **不存在或当前主体无权查看**（404）。
   *
   * ⚠️ 为什么要单列一个状态（E2E 2026-11-14 · F7）：
   *    404 与「临时读失败」必须分开对待 ——
   *    · 临时失败（如 5xx / 网络抖动）→ 值得重试，页面继续轮询；
   *    · 404 → **重试无意义**（资源不存在或不属于本主体，后端刻意统一为 404），
   *      继续轮询既烧限流配额（EVENT_QUERY 30 次/60 秒），
   *      又会让页面永远显示「正在处理」——对用户是**误导**。
   */
  notFound: boolean
  /** 是否已读到终态（供调用方停止其它轮询链） */
  done: boolean
  reload: () => void
}

export function useRunStatus(runId: string | undefined): UseRunStatusResult {
  const [status, setStatus] = useState<RunStatusResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [errorInfo, setErrorInfo] = useState<NormalizedError | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [nonce, setNonce] = useState(0)

  /** 终态后不再轮询——用 ref 而非 state，避免闭包读到旧值 */
  const doneRef = useRef(false)

  useEffect(() => {
    if (runId === undefined || runId === '') {
      setStatus(null)
      setLoading(false)
      setError('缺少运行标识')
      setErrorInfo({
        status: 400,
        code: 'INVALID_REQUEST',
        message: '缺少运行标识',
        fieldErrors: {},
      })
      return
    }
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | null = null
    doneRef.current = false
    setLoading(true)
    setError(null)
    setErrorInfo(null)
    setNotFound(false)

    const poll = async (): Promise<void> => {
      if (cancelled || doneRef.current) return
      try {
        const data = await getRunStatus(runId)
        if (cancelled) return
        setStatus(data)
        setLoading(false)
        setError(null)
        setErrorInfo(null)
        if (isTerminalState(data.state)) {
          // ⚠️ 终态自停：不再发下一次请求
          doneRef.current = true
          return
        }
      } catch (cause: unknown) {
        if (cancelled) return
        const normalized = cause instanceof ApiError
          ? cause.normalized
          : normalizeNetworkError(cause)
        const message = cause instanceof ApiError ? normalized.message : FALLBACK_ERROR
        setError(message)
        setErrorInfo(normalized)
        setLoading(false)
        // ⚠️ F7（404）→ N6（403 / 401）：**所有语义终态错误一律停轮询**。
        //    单一真相源是 errorGuidance（retryable === false 即终态）。
        //    权限拒绝、会话失效、资源不存在重试都不会改变结果；继续轮询
        //    只会白烧 EVENT_QUERY 配额（30 次/60 秒），还会让用户对着
        //    「正在处理」死等一个永远不会出现的结果（铁律 15）。
        if (!errorGuidance(normalized).retryable) {
          // 404 仍单列：后端刻意统一为 404 做反探测，页面要显示「资源不存在」。
          if (cause instanceof ApiError && cause.normalized.status === 404) {
            setNotFound(true)
          }
          doneRef.current = true
          return
        }
      }
      if (cancelled || doneRef.current) return
      timer = setTimeout(() => {
        void poll()
      }, STATUS_POLL_INTERVAL_MS)
    }

    void poll()

    return () => {
      cancelled = true
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
    }
  }, [runId, nonce])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  return {
    status,
    loading,
    error,
    errorInfo,
    notFound,
    done: status !== null && isTerminalState(status.state),
    reload,
  }
}
