/**
 * 应用外壳（FE-D3 §3.1）
 *
 * 顶栏 + 侧栏。侧栏用线与留白分组，不用圆角卡片。
 *
 * ⚠️ 2026-10-23（评审方案 F1 + B1）：导航按**角色分面**重排——
 *    辅导台 / 知识库治理 / 运行治理 / 平台配置与运维 四个分面，
 *    每个分面标题下标注「通常需要哪些角色」。
 *    可见性由登录回带的 `roles` 控制（学生/家长/老师只看到辅导台等学生侧页；
 *    语料管理、审批台等需角色的分面按角色出现）。
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
  /**
   * 该分面的受众说明（F1）。
   *
   * ⚠️ 只作**解释性小字**，不参与过滤判定——真正决定可见性的是 item.area
   *    与登录回带的 roles。写清楚受众能避免「为什么我看不到那一组」的困惑。
   */
  rolesHint?: string
  items: NavItem[]
}

/**
 * ⚠️ 四视图分面（评审方案 §3.2）：
 *   · 辅导台       —— 终端用户（学生/家长/老师），无 area，对所有人可见
 *   · 知识库治理   —— 内容审核 / 租户管理员（语料管理是审核与下架，非学生检索）
 *   · 运行治理     —— 审批人 / 租户管理员（审批台 / 处置台 / 审计）
 *   · 平台配置与运维 —— 租户管理员 / 运维（配置只读骨架 + 系统健康）
 *
 * ⚠️ 知识检索（/rag）只放在「辅导台」：它是学生侧的检索入口；
 *    「知识库治理」只放语料管理（审核面）。两者同属知识库主题，
 *    但受众与动作不同，按分面分开比在两组各放一份更清晰（避免重复入口）。
 */
const NAV: NavGroup[] = [
  {
    title: '辅导台',
    rolesHint: '学生 / 家长 / 老师',
    items: [
      { to: '/', label: '工作台' },
      { to: '/runs/new', label: '发起辅导' },
      { to: '/runs', label: '检索记录' },
      { to: '/rag', label: '知识检索' },
      { to: '/profile', label: '学习画像' },
    ],
  },
  {
    title: '知识库治理',
    rolesHint: '需内容审核 / 租户管理员',
    items: [
      // ⚠️ 语料管理属治理分面：动作是审核与下架（撤回），不是面向学生的检索。
      { to: '/rag/management', label: '语料管理', area: 'ragManagement' },
    ],
  },
  {
    title: '运行治理',
    rolesHint: '需审批人 / 租户管理员',
    items: [
      { to: '/approvals', label: '审批台', area: 'approvals' },
      { to: '/reconciliation', label: '未知操作处置', area: 'reconciliation' },
      { to: '/audit', label: '审计查询', area: 'audit' },
    ],
  },
  {
    title: '平台配置与运维',
    rolesHint: '需租户管理员 / 运维',
    items: [
      { to: '/config', label: '配置总览', area: 'config' },
      { to: '/config/runtime', label: '运行与容量', area: 'config' },
      { to: '/config/tools', label: '工具与审批', area: 'config' },
      { to: '/config/compliance', label: '合规与零保留', area: 'config' },
      { to: '/agents', label: 'Agent 配置', area: 'agents' },
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
              {group.rolesHint !== undefined ? (
                <p className="nav__group-hint">{group.rolesHint}</p>
              ) : null}
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