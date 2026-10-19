/**
 * 应用布局：顶栏 + 侧边导航（FE-D2 §4）
 *
 * ⚠️ 身份显示：LoginResponse 不含 roles（缺口 G1），
 *    故顶栏显示「身份未由服务端提供」，**不得伪造角色**。
 *    JWT payload 里虽有 roles，但那是用户可解码的凭证信息，
 *    用它做「服务端授予的身份」展示是误导。
 */

import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useSession } from '@/auth/session'

interface NavItem {
  to: string
  label: string
  /** 允许访问的角色；undefined = 登录即可 */
  roles?: string[]
}

interface NavGroup {
  title: string
  /**
   * ⛔ 当前**未生效**：登录响应无 roles（缺口 G1），无法判定。
   *    保留字段是为 G1 补齐后直接启用，无需改结构。
   *    现在不用「假定有权限」去隐藏入口——那会让无权用户看不到功能，
   *    而后端拒绝才是权威结论。
   */
  roles?: string[]
  items: NavItem[]
}

/**
 * ⛔ 角色来自后端 ROLE_PERMISSIONS 实测（FE-D1 §6.2）。
 *    与后端保持一致，避免出现「前端能进、后端全拒」的错觉。
 */
const NAV: NavGroup[] = [
  {
    title: '辅导',
    items: [
      { to: '/', label: '工作台' },
      { to: '/runs/new', label: '发起辅导' },
      { to: '/runs', label: '检索台' },
    ],
  },
  {
    title: '配置',
    roles: ['TENANT_ADMIN', 'AGENT_AUTHOR'],
    items: [
      { to: '/config', label: '配置总览' },
      { to: '/config/runtime', label: '运行与容量' },
      { to: '/config/tools', label: '工具与审批' },
      { to: '/config/compliance', label: '合规与零保留' },
      { to: '/agents', label: 'Agent 配置与发布' },
    ],
  },
  {
    title: '治理',
    roles: ['TENANT_ADMIN', 'APPROVER'],
    items: [
      { to: '/approvals', label: '审批台' },
      { to: '/reconciliation', label: '未知操作处置' },
    ],
  },
  {
    title: '运维',
    roles: ['TENANT_ADMIN'],
    items: [
      { to: '/audit', label: '审计查询' },
      { to: '/config/audit', label: '配置变更审计' },
      { to: '/health', label: '系统健康' },
    ],
  },
]

export function AppLayout(): JSX.Element {
  const { signOut } = useSession()
  const navigate = useNavigate()
  const [error, setError] = useState(false)

  const handleSignOut = (): void => {
    try {
      signOut()
      navigate('/login', { replace: true })
    } catch {
      // ⚠️ 登出失败不应阻断跳转——否则用户被困在已失效的会话里
      setError(true)
      navigate('/login', { replace: true })
    }
  }

  return (
    <div className="app-layout">
      <header className="app-header">
        <div className="app-header__brand">错题归因辅导</div>
        <div className="app-header__right">
          {/*
            ⛔ 不得显示登录名/角色：
            LoginResponse 无这些字段（缺口 G1），
            硬显示就是伪造身份。
          */}
          <span className="app-header__identity" title="后端登录响应未包含身份信息（G1）">
            身份未由服务端提供
          </span>
          <button type="button" className="btn-ghost" onClick={handleSignOut}>
            退出登录
          </button>
        </div>
      </header>

      {error ? (
        <p role="alert" className="app-header__warn">
          退出登录时发生异常，已返回登录页
        </p>
      ) : null}

      <div className="app-body">
        <nav className="app-nav" aria-label="主导航">
          {NAV.map((group) => (
            <div key={group.title} className="app-nav__group">
              <p className="app-nav__group-title">{group.title}</p>
              <ul>
                {group.items.map((item) => (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      end={item.to === '/'}
                      className={({ isActive }) =>
                        isActive ? 'app-nav__link app-nav__link--active' : 'app-nav__link'
                      }
                    >
                      {item.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>

        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}