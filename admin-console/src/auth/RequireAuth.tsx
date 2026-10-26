import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useSession } from '@/auth/session'

/**
 * 未登录 → 跳登录页（携带来源路径，登录后回跳）。
 *
 * <p>⚠️ **`to` 必须是 basename 相对路径**（`/login`，不是 `/admin/login`）：
 * Router 的 basename 已是 `/admin`，而 {@code navigate} 的目标是**相对 basename** 的 ——
 * 写成 `${APP_BASE_PATH}/login` 会被再拼一次前缀，变成 `/admin/admin/login` → 404。
 *
 * <p>⚠️ 同理，{@code state.from} 取自 {@code location.pathname} ——
 * 它**已被剥掉 basename**，故可直接回跳（相对路径）。
 */
export function RequireAuth({ children }: { children: ReactNode }): JSX.Element {
  const { authenticated } = useSession()
  const location = useLocation()
  if (!authenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <>{children}</>
}
