/**
 * 路由守卫（FE-D1 §4.3）
 *
 * ⛔ 重要：路由守卫**仅是体验优化**，不是安全边界。
 *    真正的授权在后端（P0-D03 红线 9：执行时重新授权）。
 *    前端隐藏入口 ≠ 用户无权访问 —— 后端仍会拒绝。
 */

import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useSession } from './session'

export function RequireAuth({ children }: { children: ReactNode }): JSX.Element {
  const { authenticated } = useSession()
  const location = useLocation()

  if (!authenticated) {
    // 记住原目标，登录后可回跳
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <>{children}</>
}
