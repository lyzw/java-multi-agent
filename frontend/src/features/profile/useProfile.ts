/**
 * 学习画像读取 hook（FE-D8）
 *
 * 单一数据源：`GET /api/profiles/{studentId}`。
 *
 * ⚠️ 后端对「学生不存在」返回 **200 + notFound:true**（不是 404），
 *    故本 hook **不把 notFound 当作错误**——它是一个**合法状态**，
 *    由页面渲染成「找不到这个匿名编号」而非错误页。
 *
 * ⚠️ N2（E2E 二次评估）：除文案外额外保留 `errorInfo`（归一化错误），
 *    让页面能按 status 分流：403 = 终态权限错误，不给重试；
 *    5xx / 网络 = 临时故障，才给重试。此前只有一句字符串，
 *    页面只能一律写「可能是网络问题」——把权限拒绝伪装成临时故障。
 */

import { useCallback, useEffect, useState } from 'react'
import { getProfile, ApiError } from '@/api/client'
import { normalizeNetworkError, type NormalizedError } from '@/api/errors'
import type { ProfileResponse } from '@/api/types'

export interface UseProfileResult {
  profile: ProfileResponse | null
  loading: boolean
  /** 兼容旧调用方的纯文案（= errorInfo.message） */
  error: string | null
  /** 归一化错误：供 `ErrorStateFromError` 按 status 分流 */
  errorInfo: NormalizedError | null
  reload: () => void
}

/** 缺编号是本地校验失败，不是后端故障 */
function missingIdError(): NormalizedError {
  return {
    status: 400,
    code: 'INVALID_REQUEST',
    message: '缺少匿名编号',
    fieldErrors: {},
  }
}

export function useProfile(studentId: string | undefined): UseProfileResult {
  const [profile, setProfile] = useState<ProfileResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [errorInfo, setErrorInfo] = useState<NormalizedError | null>(null)
  const [nonce, setNonce] = useState(0)

  useEffect(() => {
    if (studentId === undefined || studentId === '') {
      setProfile(null)
      setLoading(false)
      setErrorInfo(missingIdError())
      return
    }
    let cancelled = false
    setLoading(true)
    setErrorInfo(null)

    getProfile(studentId)
      .then((data) => {
        if (cancelled) return
        setProfile(data)
        setLoading(false)
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        const normalized = cause instanceof ApiError
          ? cause.normalized
          : normalizeNetworkError(cause)
        setErrorInfo(normalized)
        setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [studentId, nonce])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  return {
    profile,
    loading,
    error: errorInfo?.message ?? null,
    errorInfo,
    reload,
  }
}
