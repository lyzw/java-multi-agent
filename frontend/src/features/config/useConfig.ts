/**
 * 配置只读取数 hook（FE-D7）
 *
 * 单一数据源：所有配置页共用 `GET /api/config`。
 * 返回 `{ config, loading, error, reload }`，供页面在
 * 「加载 / 错误(可重试) / 数据」三态间切换。
 *
 * ⚠️ 防竞态：effect 内 `cancelled` 守卫，避免在组件卸载
 *    （含 StrictMode 双挂载）后 setState。
 * ⚠️ 诚实文案：失败只说明「没读到」，不给具体猜测；
 *    区分 `ApiError`（已知归一化文案）与未知错误。
 */

import { useCallback, useEffect, useState } from 'react'
import { getConfig, ApiError } from '@/api/client'
import type { ConfigResponse } from '@/api/types'

export interface UseConfigResult {
  config: ConfigResponse | null
  loading: boolean
  error: string | null
  reload: () => void
}

/** 未知错误兜底文案（不给用户凭空猜测的具体原因） */
const FALLBACK_ERROR = '配置读取失败，请稍后重试'

export function useConfig(): UseConfigResult {
  const [config, setConfig] = useState<ConfigResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    getConfig()
      .then((data) => {
        if (cancelled) return
        setConfig(data)
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
  }, [nonce])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  return { config, loading, error, reload }
}
