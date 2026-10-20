/**
 * 学习画像读取 hook（FE-D8）
 *
 * 单一数据源：`GET /api/profiles/{studentId}`。
 *
 * ⚠️ 后端对「学生不存在」返回 **200 + notFound:true**（不是 404），
 *    故本 hook **不把 notFound 当作错误**——它是一个**合法状态**，
 *    由页面渲染成「找不到这个匿名编号」而非错误页。
 */

import { useCallback, useEffect, useState } from 'react'
import { getProfile, ApiError } from '@/api/client'
import type { ProfileResponse } from '@/api/types'

export interface UseProfileResult {
  profile: ProfileResponse | null
  loading: boolean
  error: string | null
  reload: () => void
}

/** 未知错误兜底文案 */
const FALLBACK_ERROR = '画像读取失败，请稍后重试'

export function useProfile(studentId: string | undefined): UseProfileResult {
  const [profile, setProfile] = useState<ProfileResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)

  useEffect(() => {
    if (studentId === undefined || studentId === '') {
      setProfile(null)
      setLoading(false)
      setError('缺少匿名编号')
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)

    getProfile(studentId)
      .then((data) => {
        if (cancelled) return
        setProfile(data)
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
  }, [studentId, nonce])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  return { profile, loading, error, reload }
}
