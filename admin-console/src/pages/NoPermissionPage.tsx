import { Result, Button } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import { APP_BASE_PATH } from '@/config/constants'

/**
 * 业务无权限整页（区分于「未登录」跳登录）。
 *
 * ⚠️ **与登录跳转严格区分**：本页只在后端返回 `ACCESS_DENIED`
 * （已登录、但当前账号缺少执行该操作所需的权限）时渲染；
 * 「未登录 / 令牌失效」走 `/login`。把二者混为一谈的后果是：
 * 用户做一笔正常业务操作被拒 → 被当成「没登录」直接踢回登录页、丢掉操作现场。
 *
 * 触发方式见 {@link notifyAccessDenied}：调用方在捕获到
 * `isAccessDenied(err)` 时 `navigate('/no-permission?reason=...')`。
 * （角色级无权限已由 {@link RequireRole} 的 403 Result 处理，本页面向「运行时 API 返回 ACCESS_DENIED」。）
 */
export function NoPermissionPage(): JSX.Element {
  const navigate = useNavigate()
  const location = useLocation()
  const reason =
    new URLSearchParams(location.search).get('reason') ??
    '当前账号无权执行该操作。这不是登录失效，重新登录不会解决。'

  return (
    <Result
      status="403"
      title="403 · 无权限"
      subTitle={reason}
      extra={
        <Button type="primary" onClick={() => navigate(`${APP_BASE_PATH}/tenants`)}>
          返回租户管理
        </Button>
      }
    />
  )
}
