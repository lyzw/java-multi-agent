/**
 * 应用外壳（FE-D3 §3.1）
 *
 * 顶栏 + 侧栏。侧栏用线与留白分组，不用圆角卡片。
 *
 * ⚠️ 身份不由服务端提供时不伪造（G1）。
 */

import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useSession } from '@/auth/session'

interface NavItem {
  to: string
  label: string
}

interface NavGroup {
  title: string
  items: NavItem[]
}

const NAV: NavGroup[] = [
  {
    title: '辅导',
    items: [
      { to: '/', label: '工作台' },
      { to: '/runs/new', label: '发起辅导' },
      { to: '/runs', label: '检索记录' },
    ],
  },
  {
    title: '配置',
    items: [
      { to: '/config', label: '总览' },
      { to: '/config/runtime', label: '运行与容量' },
      { to: '/config/tools', label: '工具与审批' },
      { to: '/config/compliance', label: '合规与零保留' },
      { to: '/agents', label: 'Agent 配置' },
    ],
  },
  {
    title: '治理',
    items: [
      { to: '/approvals', label: '审批台' },
      { to: '/reconciliation', label: '未知操作处置' },
    ],
  },
  {
    title: '运维',
    items: [
      { to: '/audit', label: '审计查询' },
      { to: '/config/audit', label: '配置变更审计' },
      { to: '/health', label: '系统健康' },
    ],
  },
]

export function AppShell(): JSX.Element {
  const { signOut, expiringSoon } = useSession()
  const navigate = useNavigate()
  const [warn, setWarn] = useState(false)

  const handleSignOut = (): void => {
    try {
      signOut()
      navigate('/login', { replace: true })
    } catch {
      // ⛔ 登出异常不应阻断跳转，否则用户被困在失效会话里
      setWarn(true)
      navigate('/login', { replace: true })
    }
  }

  return (
    <div className="shell">
      <header className="header">
        <span className="header__brand">错题归因辅导</span>
        <div className="header__right">
          <span className="header__identity" title="后端登录响应未包含身份信息（G1）">
            身份未由服务端提供
          </span>
          <button type="button" className="btn btn--quiet" onClick={handleSignOut}>
            退出
          </button>
        </div>
      </header>

      {warn ? (
        <p role="alert" className="marked" style={{ margin: 0, borderLeftWidth: 2 }}>
          <span className="marked__label">退出时出错</span>
          已回到登录页
        </p>
      ) : null}

      {/*
        ⚠️ 到期前 60s 预警（FE-D1 §4.2）。

        ⛔ 不用 --mark 红：这是**提示**不是**错误**，红边只留给指出错误（FE-D3 R1）。
        用中性线 + settled 色文字，与「错误」在视觉上明确区分。
      */}
      {expiringSoon ? (
        <p role="status" className="marked marked--calm" style={{ margin: 0 }}>
          <span className="marked__label">会话即将到期</span>
          还有约 1 分钟。后端暂不支持自动续期，届时需要重新登录。
        </p>
      ) : null}

      <div className="shell__body">
        <nav className="nav" aria-label="主导航">
          {NAV.map((group) => (
            <div key={group.title} className="nav__group">
              <p className="nav__group-title">{group.title}</p>
              <ul>
                {group.items.map((item) => (
                  <li key={item.to}>
                    <NavLink to={item.to} end={item.to === '/'}>
                      {item.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>

        <main className="main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}