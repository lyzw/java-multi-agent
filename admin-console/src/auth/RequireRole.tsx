import { Result, Button } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useSession } from '@/auth/session'
import { type Role } from '@/config/constants'

/**
 * 角色守卫：角色仅用于**导航显隐**，非安全边界——后端执行时仍重新授权（红线）。
 * - rolesStatus 未知（角色未回传/回补未完成）→ 显示加载中。
 * - 无交集 → 403（不重定向，便于超管看到明确无权限提示）。
 */
export function RequireRole({
  roles,
  children,
}: {
  roles: Role[]
  children: ReactNode
}): JSX.Element {
  const { roles: userRoles, rolesStatus } = useSession()
  const navigate = useNavigate()
  const location = useLocation()

  if (rolesStatus === 'unknown') {
    return <Result status="info" title="正在加载权限…" />
  }

  const allowed = roles.some((r) => userRoles.includes(r))
  if (!allowed) {
    return (
      <Result
        status="403"
        title="403"
        subTitle={`当前账号无权访问「${location.pathname}」（需后端重授权）。`}
        extra={
          <Button
            type="primary"
            onClick={() => {
              // ⚠️ navigate 目标相对 Router basename（/admin），不带前缀；
              //    带上 /admin 会拼成 /admin/admin/tenants → 404。
              navigate('/tenants')
            }}
          >
            返回租户管理
          </Button>
        }
      />
    )
  }

  return <>{children}</>
}
