/**
 * 治理列表 hook（FE-D10 / G7~G10）
 *
 * 四个治理列表（Run / 审批 / 操作 / 审计）共享同一套分页状态机，
 * 故抽一个 hook 而非复制四份——**分页语义一旦分叉就会出错**，
 * 且「改一处忘三处」是这类代码最常见的腐化路径。
 *
 * ⚠️ **游标分页的硬规则**：游标绑定「排序键 + 筛选条件」。
 *   用户改了筛选条件后**必须丢弃游标**，否则会拿着「旧筛选的游标」
 *   去查「新筛选的下一页」——得到空页或错页，且看起来像「这个租户没数据了」。
 *   本 hook 用 `reset()` 强制这一点，页面把它做成「改筛选即回到第一页」。
 *
 * ⚠️ **不做自动轮询**：治理桶按**租户**限流、多个运维共享同一桶；
 *   自动轮询会把配额耗在「运维没在看」的页面上，且互相抢配额。
 *   只在用户显式操作（翻页 / 改筛选 / 刷新）时请求。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '@/api/client'
import type { PageResponse } from '@/api/types'

/** 未知错误兜底文案（不给用户凭空猜测的具体原因） */
const FALLBACK_ERROR = '读取失败，请稍后重试'

export interface UseGovernancePageOptions<T> {
  /** 实际发起请求的函数（调用方须用 useCallback 包裹） */
  fetcher: (params: {
    limit: number
    cursor: string | null
  }) => Promise<PageResponse<T>>
  /** 依赖变化（如筛选条件）时回到第一页并重新加载 */
  deps: readonly unknown[]
  pageSize?: number
}

export interface UseGovernancePageResult<T> {
  items: T[]
  loading: boolean
  loadingMore: boolean
  error: string | null
  /** 是否还有下一页（`nextCursor !== null`） */
  hasMore: boolean
  /** 回到第一页并重新加载（改筛选时必须调用） */
  reset: () => void
  /** 重新加载当前条件下的第一页 */
  reload: () => void
  /** 追加下一页。无下一页时**不发起请求**。 */
  loadMore: () => void
}

export function useGovernancePage<T>(
  options: UseGovernancePageOptions<T>,
): UseGovernancePageResult<T> {
  const { fetcher, deps, pageSize = 20 } = options
  const [items, setItems] = useState<T[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)

  /** ⛔ 并发守卫：StrictMode 双挂载 + 用户连点「加载更多」
   *  都会造成重复请求；治理桶配额有限，重复请求就是浪费别人的配额。 */
  const inFlight = useRef(false)

  useEffect(() => {
    let cancelled = false
    inFlight.current = true
    setLoading(true)
    setError(null)

    fetcher({ limit: pageSize, cursor: null })
      .then((page) => {
        if (cancelled) return
        setItems(page.items ?? [])
        setNextCursor(page.nextCursor ?? null)
        setLoading(false)
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        const message =
          cause instanceof ApiError ? cause.normalized.message : FALLBACK_ERROR
        setError(message)
        setItems([])
        setNextCursor(null)
        setLoading(false)
      })
      .finally(() => {
        inFlight.current = false
      })

    return () => {
      cancelled = true
    }
    // ⚠️ deps 由调用方给出；本 hook 不重建 fetcher，
    //    故调用方必须用 useCallback 包裹 fetcher（见各页实现）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pageSize, nonce, ...deps])

  const reset = useCallback(() => {
    setNextCursor(null)
    setNonce((n) => n + 1)
  }, [])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  const loadMore = useCallback(() => {
    if (nextCursor === null || loading || loadingMore || inFlight.current) {
      // ⛔ 无下一页时**不发起请求**：空转翻页会把限流配额耗光
      return
    }
    let cancelled = false
    inFlight.current = true
    setLoadingMore(true)
    fetcher({ limit: pageSize, cursor: nextCursor })
      .then((page) => {
        if (cancelled) return
        setItems((prev) => [...prev, ...(page.items ?? [])])
        setNextCursor(page.nextCursor ?? null)
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        const message =
          cause instanceof ApiError ? cause.normalized.message : FALLBACK_ERROR
        setError(message)
      })
      .finally(() => {
        inFlight.current = false
        setLoadingMore(false)
      })
    // ⚠️ 这里不写 return 清理函数：本 hook 的 effect 清理只针对首屏请求；
    //    「加载更多」是事件驱动的，用 cancelled 局部守卫已足够，
    //    组件卸载后 setState 在 React 18 中是 no-op，不会报警告。
    void cancelled
  }, [nextCursor, loading, loadingMore, fetcher, pageSize])

  return {
    items,
    loading,
    loadingMore,
    error,
    hasMore: nextCursor !== null,
    reset,
    reload,
    loadMore,
  }
}