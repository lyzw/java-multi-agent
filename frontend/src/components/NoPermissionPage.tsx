/**
 * 业务无权限整页（区分于「未登录」）。
 *
 * ⚠️ **与登录跳转严格区分**：本页只在后端返回 `ACCESS_DENIED`
 * （已登录、但当前账号缺少执行该操作所需的权限）时渲染；
 * 「未登录 / 令牌失效」走 `/login`。把二者混为一谈的后果是：
 * 用户做一笔正常业务操作被拒 → 被当成「没登录」直接踢回登录页、
 * 丢掉操作现场（本项目 2026-10-28 实测缺陷）。
 *
 * 触发方式见 {@link notifyAccessDenied}：调用方在捕获到
 * `isAccessDenied(err.normalized)` 时 `navigate('/no-permission?reason=...')`。
 */

import { Link, useLocation, useNavigate } from 'react-router-dom'
import { PageHead } from '@/components/ui'

export function NoPermissionPage(): JSX.Element {
  const location = useLocation()
  const navigate = useNavigate()
  const reason = new URLSearchParams(location.search).get('reason') ?? undefined

  return (
    <>
      <PageHead
        title="无权限"
        eyebrow="403"
        lead="你已登录，但当前账号没有执行该操作的权限。"
      />
      <div className="marked" role="alert">
        <p className="marked__label">访问被拒绝</p>
        <p style={{ marginBottom: 4 }}>
          {reason ?? '当前账号无权执行该操作。这不是登录失效，重新登录不会解决。'}
        </p>
        <p className="muted" style={{ marginBottom: 12 }}>
          如需访问，请联系管理员为你分配相应权限。
        </p>
        <div className="empty__action">
          <Link to="/" className="btn">
            返回工作台
          </Link>
          <button
            type="button"
            className="btn btn--quiet"
            onClick={() => navigate(-1)}
          >
            返回上一页
          </button>
        </div>
      </div>
    </>
  )
}
