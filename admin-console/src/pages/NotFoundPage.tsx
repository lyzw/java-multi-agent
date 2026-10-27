/**
 * 404 页（评审 §1.2 P0 修复 · 2026-10-27）
 *
 * ⚠️ 此前 router 无 `path="*"` 兜底：输错 URL / 点过期链接 = **白屏**，
 *    无任何「页面不存在」的反馈。与 frontend 的 NotFoundPage 同型缺陷。
 *
 * ⛔ 不提供「站点地图」式的大列表：本控制台的导航由角色决定
 *    （PLATFORM_SCOPE_ROLES / LIFECYCLE_SCOPE / AUDIT_SCOPE），
 *    列一份包含无权入口的清单等于把权限模型泄露进 404 页。
 *    只给两个真实去向：回租户注册表（默认首页）/ 回登录页。
 */

import { Link } from 'react-router-dom'
import { Button, Result } from 'antd'

export function NotFoundPage(): JSX.Element {
  return (
    <Result
      status="404"
      title="404"
      subTitle="这个地址没有对应的页面。可能是链接已过期，或地址输入有误。"
      extra={
        <div style={{ display: 'flex', gap: 12, justifyContent: 'center' }}>
          <Link to="/">
            <Button type="primary">回租户注册表</Button>
          </Link>
          <Link to="/login">
            <Button>回登录页</Button>
          </Link>
        </div>
      }
    />
  )
}
