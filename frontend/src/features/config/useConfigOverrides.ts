/**
 * 配置覆盖写入 hook（P4-D15 / FE-D11 · C3 + C5 + C7）。
 *
 * ⚠️ **为什么单列一个 hook 而不在页面里直接调 API**：
 * 写入路径的**并发与竞态**比只读路径复杂得多——
 * 「提交中」「成功后需重载」「失败后可重试」三者必须互斥，
 * 页面里内联会重复实现并逐渐漂移（P9 处置台的教训）。
 *
 * ⚠️ **乐观更新一律不做**：写配置是高危操作，
 * 界面先变而后端拒绝，会造成「看起来生效了其实没生效」——
 * 正是 P4-D15 D15-B3 描述的危险形态。
 * 故一律**先等服务端响应，成功后再重载**。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  listConfigOverrides,
  applyConfigOverride,
  clearConfigOverride,
  ApiError,
} from '@/api/client'
import type { ConfigOverrideItem } from '@/api/types'

/** 未知错误兜底文案（不给用户凭空猜测的具体原因）。 */
const FALLBACK_ERROR = '配置操作失败，请稍后重试'

export interface UseConfigOverridesResult {
  items: ConfigOverrideItem[] | null
  loading: boolean
  error: string | null
  /** 正在提交的键（同一时刻只允许一个，避免连点重复提交） */
  submittingKey: string | null
  /** 上一次操作的错误（与加载错误分开：加载失败和提交失败语义不同） */
  actionError: string | null
  reload: () => void
  apply: (key: string, value: string, reason: string) => Promise<boolean>
  clear: (key: string) => Promise<boolean>
  clearActionError: () => void
}

export function useConfigOverrides(): UseConfigOverridesResult {
  const [items, setItems] = useState<ConfigOverrideItem[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [submittingKey, setSubmittingKey] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)
  const alive = useRef(true)

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    listConfigOverrides()
      .then((data) => {
        if (cancelled) return
        setItems(data)
        setLoading(false)
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        setError(cause instanceof ApiError ? cause.normalized.message : FALLBACK_ERROR)
        setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [nonce])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  const runAction = useCallback(
    async (key: string, action: () => Promise<unknown>): Promise<boolean> => {
      // ⛔ 同一时刻只允许一个提交：并发提交会让「后到的响应」覆盖
      //    「先到的响应」，最终界面显示的值可能与后端不一致。
      setSubmittingKey(key)
      setActionError(null)
      try {
        await action()
        if (!alive.current) return false
        // ⚠️ 成功后**重载而非本地改**：以上一轮服务端返回为准，
        //    避免前端算出的 effectiveValue 与后端不一致。
        setNonce((n) => n + 1)
        return true
      } catch (cause: unknown) {
        if (!alive.current) return false
        setActionError(
          cause instanceof ApiError ? cause.normalized.message : FALLBACK_ERROR,
        )
        return false
      } finally {
        if (alive.current) setSubmittingKey(null)
      }
    },
    [],
  )

  const apply = useCallback(
    (key: string, value: string, reason: string) =>
      runAction(key, () => applyConfigOverride(key, { value, reason })),
    [runAction],
  )

  const clear = useCallback(
    (key: string) => runAction(key, () => clearConfigOverride(key)),
    [runAction],
  )

  const clearActionError = useCallback(() => setActionError(null), [])

  return {
    items,
    loading,
    error,
    submittingKey,
    actionError,
    reload,
    apply,
    clear,
    clearActionError,
  }
}