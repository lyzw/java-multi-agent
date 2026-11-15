/**
 * useGovernancePage 分页状态机测试（E2E 四轮优化，D2）。
 *
 * ⚠️ 守则是「撤掉修复点会红」：若 loadMore 失败时仍 `setError(message)`
 * （修复前的行为），首屏 `error` 会被污染 → 治理页把整页列表换成 ErrorState、
 * 已看到的条目消失。本文件直接锁住 hook 行为，独立于任何页面渲染。
 */
import { describe, it, expect, vi } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useGovernancePage } from '@/features/governance/useGovernancePage'
import type { PageResponse } from '@/api/types'

const page = (items: string[], nextCursor: string | null): PageResponse<string> => ({
  items,
  nextCursor,
})

describe('useGovernancePage', () => {
  it('首屏拉取失败：写 error 并清空列表', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('boom'))
    const { result } = renderHook(() => useGovernancePage({ fetcher, deps: [] }))
    await waitFor(() => expect(result.current.error).not.toBeNull())
    expect(result.current.items).toEqual([])
    expect(result.current.loadMoreError).toBeNull()
  })

  it('D2：翻页失败只记 loadMoreError，保留列表、不污染首屏 error', async () => {
    const fetcher = vi.fn()
    fetcher.mockResolvedValueOnce(page(['a', 'b'], 'cur2'))
    fetcher.mockRejectedValueOnce(new Error('限流'))

    const { result } = renderHook(() => useGovernancePage({ fetcher, deps: [] }))
    await waitFor(() => expect(result.current.items).toEqual(['a', 'b']))
    expect(result.current.error).toBeNull()
    expect(result.current.loadMoreError).toBeNull()

    act(() => {
      result.current.loadMore()
    })
    await waitFor(() => expect(result.current.loadMoreError).not.toBeNull())

    // ⚠️ 已加载的列表必须保留（治理页不应整页替换为错误态）
    expect(result.current.items).toEqual(['a', 'b'])
    // ⚠️ 首屏 error 不被污染（否则页面会丢失已有数据）
    expect(result.current.error).toBeNull()
  })

  it('翻页成功：追加到列表并更新 hasMore', async () => {
    const fetcher = vi.fn()
    fetcher.mockResolvedValueOnce(page(['a'], 'cur2'))
    fetcher.mockResolvedValueOnce(page(['b'], null))

    const { result } = renderHook(() => useGovernancePage({ fetcher, deps: [] }))
    await waitFor(() => expect(result.current.items).toEqual(['a']))
    act(() => {
      result.current.loadMore()
    })
    await waitFor(() => expect(result.current.items).toEqual(['a', 'b']))
    expect(result.current.hasMore).toBe(false)
  })

  it('D2 反证：把 loadMore 失败改回 setError 时，首屏 error 会被污染（用例会红）', async () => {
    // ⚠️ 本用例只验证「两种错误是独立字段」这一不变量——
    //    若实现把两者合并（如都用 error），下面的断言会失败，提醒回归。
    const fetcher = vi.fn()
    fetcher.mockResolvedValueOnce(page(['a'], 'cur2'))
    fetcher.mockRejectedValueOnce(new Error('限流'))
    const { result } = renderHook(() => useGovernancePage({ fetcher, deps: [] }))
    await waitFor(() => expect(result.current.items).toEqual(['a']))
    act(() => {
      result.current.loadMore()
    })
    await waitFor(() => expect(result.current.loadMoreError).not.toBeNull())
    expect(result.current.error).toBeNull()
  })
})
