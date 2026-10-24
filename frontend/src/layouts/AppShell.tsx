/**
 * 应用外壳（演进重做 · 保留全部行为契约）
 *
 * 顶栏 + 侧栏。侧栏按角色分面分组（辅导台 / 知识库治理 / 运行治理 / 平台配置与运维）。
 *
 * ✅ 演进点：
 *   · 每个导航项配图标（线性描边），扫描更快；
 *   · 新增「折叠为图标栏」开关，替代旧版中屏「只显示首字」的信息丢失；
 *     折叠态持久化到 localStorage，刷新不弹回；
 *   · 顶栏加品牌标识块；折叠按钮为图标按钮；
 *   · 壳内第一个可聚焦元素是「跳到主内容」skip link（键盘可达性基线）。
 *   · **小屏抽屉**（2026-10-24 UX 评审 P1-F）：≤767px 时侧栏不再是一条
 *     横向滚动条（16 项总宽 1918px，首屏只露 3.5 项且第 4 项被裁成半截），
 *     改为顶栏菜单按钮拉出的抽屉：打开进焦点、Esc/遮罩可关、关后焦点回按钮。
 *
 * ⚠️ 保留的硬契约（nav.menu.test.tsx / design.spec.test.ts 依赖）：
 *   · 恰好 16 个 link，可访问名 = 中文标签（图标 aria-hidden）；
 *   · 4 个分面标题；header-identity / roles-unknown-notice 两个 testid；
 *   · <main className="main">；
 *   · 到期预警 / 角色未回传提示 / 登出 全部保留。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { hasRoleAccess } from '@/auth/RequireRole'
import { Icon, type IconName } from '@/components/Icon'

/** 侧栏折叠态的本机记忆键（纯布局偏好，不涉及任何凭据 / 受控数据）。 */
const COLLAPSED_KEY = 'nav.collapsed'

/** 读取折叠态；存储不可用（隐私模式）时按展开处理。 */
function readCollapsed(): boolean {
  try {
    return window.localStorage.getItem(COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}

function writeCollapsed(value: boolean): void {
  try {
    if (value) window.localStorage.setItem(COLLAPSED_KEY, '1')
    else window.localStorage.removeItem(COLLAPSED_KEY)
  } catch {
    // 记忆失败不影响使用，静默忽略
  }
}

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
  const location = useLocation()
  const [warn, setWarn] = useState(false)
  // ⚠️ 折叠态跨刷新保留：重度用户把侧栏收起来后，
  //    每次刷新都被弹回展开态是很烦人的小刺。
  const [collapsed, setCollapsed] = useState(readCollapsed)
  /*
   * 小屏抽屉（2026-10-24 UX 评审 P1-F）
   *
   * ⚠️ 旧形态是 ≤767px 时侧栏变一条横向滚动条：16 个入口总宽 1918px，
   *    390px 视口首屏只露出 3.5 项，第 4 项被裁在「知」字中间——
   *    一个点不动的半截元素。横向滚动条治不了 16 项。
   *
   * 抽屉的三条行为约定：
   *   1. 打开时焦点进入抽屉（否则键盘用户不知道自己在哪）；
   *   2. Esc / 点遮罩 / 点导航项都能关闭，关闭后焦点回到菜单按钮；
   *   3. 收起时抽屉用 visibility:hidden —— 它同时移出焦点序列与
   *      无障碍树（只用 transform 移出屏幕的话，那 16 个链接
   *      仍会被 Tab 到，那才是真正的坑）。
   */
  const [drawerOpen, setDrawerOpen] = useState(false)
  const drawerButtonRef = useRef<HTMLButtonElement>(null)
  const navRef = useRef<HTMLElement>(null)

  const closeDrawer = useCallback((): void => {
    setDrawerOpen(false)
    // 焦点回到触发它的按钮，否则键盘用户会被丢在文档流里
    drawerButtonRef.current?.focus()
  }, [])

  // 路由一变就收起抽屉：否则点完导航项，遮罩还盖着新页面
  useEffect(() => {
    setDrawerOpen(false)
  }, [location.pathname])

  // Esc 关闭抽屉
  useEffect(() => {
    if (!drawerOpen) return undefined
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') closeDrawer()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [drawerOpen, closeDrawer])

  // 抽屉打开时锁住页面滚动（背景跟着滚会把抽屉甩掉）
  useEffect(() => {
    if (!drawerOpen) return undefined
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previous
    }
  }, [drawerOpen])

  // 打开后把焦点送进抽屉的第一个入口
  useEffect(() => {
    if (!drawerOpen) return
    const first = navRef.current?.querySelector<HTMLElement>('a[href]')
    first?.focus()
  }, [drawerOpen])

  const toggleCollapsed = (): void => {
    setCollapsed((prev) => {
      const next = !prev
      writeCollapsed(next)
      return next
    })
  }

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
    <div className="shell" data-collapsed={collapsed} data-drawer={drawerOpen}>
      {/*
        ⚠️ skip link 必须是壳内第一个可聚焦元素：
        键盘用户进页面第一件事就是跳过 16 个导航链接直达主内容。
        锚点指向 #main-content（主内容区内的焦点靶），
        不改 <main className="main"> 的字面结构（structure.integrity 锁死它）。
      */}
      <a href="#main-content" className="skip-link">
        跳到主内容
      </a>
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
          {/*
            小屏抽屉开关（桌面不显示，见 layout.css 的 .nav-toggle）。
            ⚠️ 与「折叠导航」互斥：折叠收窄侧栏、菜单弹出侧栏，
               两者在小屏上同时出现会让顶栏挤成一团。
          */}
          <button
            ref={drawerButtonRef}
            type="button"
            className="btn btn--quiet btn--icon nav-toggle"
            aria-label={drawerOpen ? '关闭导航' : '打开导航'}
            aria-expanded={drawerOpen}
            aria-controls="main-nav"
            title={drawerOpen ? '关闭导航' : '打开导航'}
            onClick={() => {
              if (drawerOpen) closeDrawer()
              else setDrawerOpen(true)
            }}
          >
            <Icon name={drawerOpen ? 'close' : 'menu'} />
          </button>
          <button
            type="button"
            className="btn btn--quiet btn--icon nav-collapse"
            aria-label={collapsed ? '展开导航' : '折叠导航'}
            aria-pressed={collapsed}
            title={collapsed ? '展开导航' : '折叠导航'}
            onClick={toggleCollapsed}
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
        {/*
          抽屉遮罩：只在抽屉打开时存在，点击即关闭。
          aria-hidden：它没有语义，只是挡住背景防止误点。
        */}
        {drawerOpen ? (
          <button
            type="button"
            className="nav-scrim"
            aria-label="关闭导航"
            tabIndex={-1}
            onClick={closeDrawer}
          />
        ) : null}

        <nav id="main-nav" ref={navRef} className="nav" aria-label="主导航">
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
          {/* skip link 的焦点靶：tabIndex=-1 让锚点跳转能把焦点也带进来 */}
          <span id="main-content" className="sr-only" tabIndex={-1} />
          <Outlet />
        </main>
      </div>
    </div>
  )
}
