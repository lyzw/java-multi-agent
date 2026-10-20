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
  /** 是否已读到终态（供调用方停止其它轮询链） */
  done: boolean
  reload: () => void
}

export function useRunStatus(runId: string | undefined): UseRunStatusResult {
  const [status, setStatus] = useState<RunStatusResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)

  /** 终态后不再轮询——用 ref 而非 state，避免闭包读到旧值 */
  const doneRef = useRef(false)

  useEffect(() => {
    if (runId === undefined || runId === '') {
      setStatus(null)
      setLoading(false)
      setError('缺少运行标识')
      return
    }
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | null = null
    doneRef.current = false
    setLoading(true)
    setError(null)

    const poll = async (): Promise<void> => {
      if (cancelled || doneRef.current) return
      try {
        const data = await getRunStatus(runId)
        if (cancelled) return
        setStatus(data)
        setLoading(false)
        setError(null)
        if (isTerminalState(data.state)) {
          // ⚠️ 终态自停：不再发下一次请求
          doneRef.current = true
          return
        }
      } catch (cause: unknown) {
        if (cancelled) return
        const message =
          cause instanceof ApiError ? cause.normalized.message : FALLBACK_ERROR
        setError(message)
        setLoading(false)
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
    done: status !== null && isTerminalState(status.state),
    reload,
  }
}
