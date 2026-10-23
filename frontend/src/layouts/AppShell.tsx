/**
 * 应用外壳（演进重做 · 保留全部行为契约）
 *
 * 顶栏 + 侧栏。侧栏按角色分面分组（辅导台 / 知识库治理 / 运行治理 / 平台配置与运维）。
 *
 * ✅ 演进点：
 *   · 每个导航项配图标（线性描边），扫描更快；
 *   · 新增「折叠为图标栏」开关，替代旧版中屏「只显示首字」的信息丢失；
 *   · 顶栏加品牌标识块；折叠按钮为图标按钮。
 *
 * ⚠️ 保留的硬契约（nav.menu.test.tsx / design.spec.test.ts 依赖）：
 *   · 恰好 16 个 link，可访问名 = 中文标签（图标 aria-hidden）；
 *   · 4 个分面标题；header-identity / roles-unknown-notice 两个 testid；
 *   · <main className="main">；小屏（≤767px）侧栏横向滚动；
 *   · 到期预警 / 角色未回传提示 / 登出 全部保留。
 */

import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { hasRoleAccess } from '@/auth/RequireRole'
import { Icon, type IconName } from '@/components/Icon'

interface NavItem {
  to: string
  label: string
  icon: IconName
  /** 需要的角色区域；缺省 = 任何已登录用户可见（学生侧页面） */
  area?: string
}

interface NavGroup {
  title: string
  icon: IconName
  rolesHint?: string
  items: NavItem[]
}

const NAV: NavGroup[] = [
  {
    title: '辅导台',
    icon: 'dashboard',
    rolesHint: '学生 / 家长 / 老师',
    items: [
      { to: '/', label: '工作台', icon: 'dashboard' },
      { to: '/runs/new', label: '发起辅导', icon: 'plus' },
      { to: '/runs', label: '检索记录', icon: 'search' },
      { to: '/rag', label: '知识检索', icon: 'book' },
      { to: '/profile', label: '学习画像', icon: 'profile' },
      // ⚠️ 报表中心：租户级**聚合**视图（按天看辅导 / 错题 / 检索 / 语料导入）。
      //    与「检索记录」不同：后者是逐条明细，前者是按天汇总。
      //    不挂 area —— 聚合数据不含个体信息，与后端 StatisticsController
      //    复用 RUN_READ 动作的授权范围保持一致（任何已登录用户可见本租户汇总）。
      { to: '/reports', label: '报表中心', icon: 'activity' },
    ],
  },
  {
    title: '知识库治理',
    icon: 'layers',
    rolesHint: '需内容审核 / 租户管理员',
    items: [{ to: '/rag/management', label: '语料管理', icon: 'layers', area: 'ragManagement' }],
  },
  {
    title: '运行治理',
    icon: 'shield',
    rolesHint: '需审批人 / 租户管理员',
    items: [
      { to: '/approvals', label: '审批台', icon: 'shield', area: 'approvals' },
      { to: '/reconciliation', label: '未知操作处置', icon: 'alert', area: 'reconciliation' },
      { to: '/audit', label: '审计查询', icon: 'file', area: 'audit' },
    ],
  },
  {
    title: '平台配置与运维',
    icon: 'sliders',
    rolesHint: '需租户管理员 / 运维',
    items: [
      { to: '/config', label: '配置总览', icon: 'sliders', area: 'config' },
      { to: '/config/runtime', label: '运行与容量', icon: 'gauge', area: 'config' },
      { to: '/config/tools', label: '工具与审批', icon: 'wrench', area: 'config' },
      { to: '/config/compliance', label: '合规与零保留', icon: 'lock', area: 'config' },
      { to: '/agents', label: 'Agent 配置', icon: 'cpu', area: 'agents' },
      { to: '/health', label: '系统健康', icon: 'activity', area: 'health' },
    ],
  },
]

export function AppShell(): JSX.Element {
  const { signOut, expiringSoon, roles, rolesStatus, session, refreshRoles } = useSession()
  const navigate = useNavigate()
  const [warn, setWarn] = useState(false)
  const [collapsed, setCollapsed] = useState(false)

  const visibleNav = NAV.map((group) => ({
    ...group,
    items: group.items.filter(
      (item) => item.area === undefined || hasRoleAccess(roles, item.area),
    ),
  })).filter((group) => group.items.length > 0)

  const roleLabel =
    rolesStatus === 'unknown'
      ? '角色未回传'
      : roles.length > 0
        ? roles.join(' · ')
        : '无管理角色'

  const rolesUnknown = rolesStatus === 'unknown'
  const tenantShort =
    session?.tenantId != null && session.tenantId.length >= 8
      ? session.tenantId.slice(0, 8)
      : session?.tenantId ?? '—'

  const handleSignOut = (): void => {
    try {
      signOut()
      navigate('/login', { replace: true })
    } catch {
      setWarn(true)
      navigate('/login', { replace: true })
    }
  }

  return (
    <div className="shell" data-collapsed={collapsed}>
      <header className="header">
        <span className="brand">
          <span className="brand__mark">
            <Icon name="book" />
          </span>
          <span className="brand__text">错题归因辅导</span>
        </span>
        <div className="header__right">
          <span
            className="header__identity"
            data-testid="header-identity"
            title={`角色来自登录响应或 /api/auth/roles 回补；租户 ${session?.tenantId ?? '—'}（仅用于 RAG 等下游调用）`}
          >
            角色：{roleLabel} · 租户 {tenantShort}
          </span>
          <button
            type="button"
            className="btn btn--quiet btn--icon"
            aria-label={collapsed ? '展开导航' : '折叠导航'}
            aria-pressed={collapsed}
            title={collapsed ? '展开导航' : '折叠导航'}
            onClick={() => setCollapsed((v) => !v)}
          >
            <Icon name="panel" />
          </button>
          <button type="button" className="btn btn--quiet" onClick={handleSignOut}>
            <Icon name="logout" />
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

      {expiringSoon ? (
        <p role="status" className="marked marked--calm" style={{ margin: 0 }}>
          <span className="marked__label">会话即将到期</span>
          还有约 1 分钟。后端暂不支持自动续期，届时需要重新登录。
        </p>
      ) : null}

      {rolesUnknown ? (
        <div
          role="status"
          className="marked marked--calm"
          style={{ margin: 0 }}
          data-testid="roles-unknown-notice"
        >
          <span className="marked__label">角色未回传</span>
          后端未返回当前账号的角色，管理菜单暂不显示（安全策略：角色未知一律不放行）。
          <button
            type="button"
            className="btn btn--quiet"
            onClick={() => void refreshRoles(true)}
          >
            重试获取角色
          </button>
        </div>
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
                    <NavLink to={item.to} end={item.to === '/'} className="nav__link" title={item.label}>
                      <Icon name={item.icon} className="nav__icon" />
                      <span className="nav__label">{item.label}</span>
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
