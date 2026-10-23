/**
 * 应用外壳（FE-D3 §3.1）
 *
 * 顶栏 + 侧栏。侧栏用线与留白分组，不用圆角卡片。
 *
 * ⚠️ 2026-10-23（B1）：导航按登录回带的 `roles` 显隐——
 *    学生/家长/老师只看到「辅导」组，管理页（配置/治理/运维）按角色出现。
 *    这只是一层 UI 引导，**不是安全边界**：后端执行时仍按红线 9 重授权。
 */

import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { hasRoleAccess } from '@/auth/RequireRole'

interface NavItem {
  to: string
  label: string
  /** 需要的角色区域；缺省 = 任何已登录用户可见（学生侧页面） */
  area?: string
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
      { to: '/rag', label: '知识检索' },
    ],
  },
  {
    title: '配置',
    items: [
      { to: '/config', label: '总览', area: 'config' },
      { to: '/config/runtime', label: '运行与容量', area: 'config' },
      { to: '/config/tools', label: '工具与审批', area: 'config' },
      { to: '/config/compliance', label: '合规与零保留', area: 'config' },
      { to: '/agents', label: 'Agent 配置', area: 'agents' },
    ],
  },
  {
    title: '治理',
    items: [
      { to: '/approvals', label: '审批台', area: 'approvals' },
      { to: '/reconciliation', label: '未知操作处置', area: 'reconciliation' },
      // ⚠️ RAG 语料管理属**治理**组而非「辅导」组：
      //    它的动作是审核与下架（撤回），不是面向学生的检索。
      { to: '/rag/management', label: '语料管理', area: 'ragManagement' },
    ],
  },
  {
    title: '运维',
    items: [
      { to: '/audit', label: '审计查询', area: 'audit' },
      { to: '/config/audit', label: '配置变更审计', area: 'audit' },
      { to: '/health', label: '系统健康', area: 'health' },
    ],
  },
]

export function AppShell(): JSX.Element {
  const { signOut, expiringSoon, roles, session } = useSession()
  const navigate = useNavigate()
  const [warn, setWarn] = useState(false)

  // ⚠️ 按角色显隐导航项：没有 area 的项（学生侧页面）对所有人可见；
  //    带 area 的项仅当 hasRoleAccess 通过才显示。安全以后端为准。
  const visibleNav = NAV.map((group) => ({
    ...group,
    items: group.items.filter(
      (item) => item.area === undefined || hasRoleAccess(roles, item.area),
    ),
  })).filter((group) => group.items.length > 0)

  const roleLabel =
    roles.length > 0 ? roles.join(' · ') : '学生 / 家长 / 老师'
  const tenantShort =
    session?.tenantId != null && session.tenantId.length >= 8
      ? session.tenantId.slice(0, 8)
      : session?.tenantId ?? '—'

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
          <span
            className="header__identity"
            title={`角色来自登录响应；租户 ${session?.tenantId ?? '—'}（仅用于 RAG 等下游调用）`}
          >
            角色：{roleLabel} · 租户 {tenantShort}
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
          {visibleNav.map((group) => (
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