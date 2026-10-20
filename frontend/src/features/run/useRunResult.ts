/**
 * 结果读取 hook（FE-D8）
 *
 * 单一数据源：`GET /api/runs/{runId}/result`。
 * 返回 `{ result, loading, error, reload }`，供页面在
 * 「加载 / 错误(可重试) / 数据」三态间切换。
 *
 * ⚠️ 防竞态：effect 内 `cancelled` 守卫，避免组件卸载
 *    （含 StrictMode 双挂载）后 setState。
 * ⚠️ runId 为空时**不发请求**（避免对 `/api/runs//result` 产生无意义调用）。
 */

import { useCallback, useEffect, useState } from 'react'
import { getRunResult, ApiError } from '@/api/client'
import type { RunResultResponse } from '@/api/types'

export interface UseRunResultResult {
  result: RunResultResponse | null
  loading: boolean
  error: string | null
  reload: () => void
}

/** 未知错误兜底文案（不给用户凭空猜测的具体原因） */
const FALLBACK_ERROR = '结果读取失败，请稍后重试'

export function useRunResult(runId: string | undefined): UseRunResultResult {
  const [result, setResult] = useState<RunResultResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)

  useEffect(() => {
    if (runId === undefined || runId === '') {
      setResult(null)
      setLoading(false)
      setError('缺少运行标识')
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)

    getRunResult(runId)
      .then((data) => {
        if (cancelled) return
        setResult(data)
        setLoading(false)
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        const message =
          cause instanceof ApiError ? cause.normalized.message : FALLBACK_ERROR
        setError(message)
        setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [runId, nonce])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  return { result, loading, error, reload }
}
