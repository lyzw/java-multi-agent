import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useSession } from '@/auth/session'
import { APP_BASE_PATH } from '@/config/constants'

/** 未登录 → 跳登录页（携带来源路径，登录后回跳） */
export function RequireAuth({ children }: { children: ReactNode }): JSX.Element {
  const { authenticated } = useSession()
  const location = useLocation()
  if (!authenticated) {
    return (
      <Navigate to={`${APP_BASE_PATH}/login`} replace state={{ from: location.pathname }} />
    )
  }
  return <>{children}</>
}
