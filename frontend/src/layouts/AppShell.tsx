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
 *   · 恰好 18 个 link（原 17 + Agent 编排对话），可访问名 = 中文标签（图标 aria-hidden）；
 *   · 4 个分面标题；header-identity / roles-unknown-notice 两个 testid；
 *   · <main className="main">；
 *   · 到期预警 / 角色未回传提示 / 登出 全部保留。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { ContentErrorBoundary } from '@/shared/ErrorBoundary'
import { hasRoleAccess } from '@/auth/RequireRole'
import { canPerformTask, unsupportedRoleOf, type TaskId } from '@/auth/capabilities'
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
  /** 治理面区域（config / approvals / audit …）；与 task 二选一 */
  area?: string
  /**
   * 业务面任务（T1 硬门禁）。
   * ⛔ 每个可见入口都必须登记：没有登记就说明「这个入口没有对应的真实操作用例」，
   *    正是 F6 / N1 / N2 的共同根因。见 capabilities.ts。
   */
  task?: TaskId
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
    // ⚠️ E2E 二次评估 N1：产品裁决「家长 / 老师本版本不开放」，
    //    rolesHint 不再承诺三个受众；只写实际开放的学生。
    rolesHint: '学生（本版本）',
    items: [
      { to: '/', label: '工作台', icon: 'dashboard', task: 'dashboard' },
      { to: '/runs/new', label: '发起辅导', icon: 'plus', task: 'run:create' },
      { to: '/runs', label: '检索记录', icon: 'search', task: 'run:list' },
      { to: '/rag', label: '知识检索', icon: 'book', task: 'rag:search' },
      // 通用知识问答：不挂教材语料的 SSE 对话（与 /rag 的「可溯源依据」互补）。
      { to: '/chat', label: '通用问答', icon: 'cpu', task: 'chat:general' },
      // 通用 Agent 编排对话：同一租户链的 SSE 过程可视化，端点与 /chat 分开。
      { to: '/agent-chat', label: 'Agent 对话', icon: 'activity', task: 'chat:agent' },
      // ⚠️ N2：STUDENT 没有 PROFILE_READ，填完编号才撞 403 —— 不再渲染该入口。
      //    治理角色（TENANT_ADMIN / VIEWER）有 PROFILE_READ，仍可见。
      { to: '/profile', label: '学习画像', icon: 'profile', task: 'profile:read' },
      // ⚠️ N5：租户级聚合报表归位到治理面（受众不开放），
      //    避免学生看到「发起量 13」与本人口径「10 条」两个数字被误读为数据错误。
      //    聚合数据不含个体信息，后端复用 RUN_READ 是有意设计，此处只调整入口定位。
      { to: '/reports', label: '报表中心', icon: 'activity', task: 'reports:read' },
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

/**
 * 「本版本未开放角色」终态说明（N1）。
 *
 * ⚠️ 为什么是**整页**而不是一个 toast：这些账号此前能登录，
 *    却既发不出辅导、也看不到任何记录（导航承诺 8 个入口，每个都是 403 / 空态）。
 *    产品裁决后必须一次性说清楚，而不是让用户逐个入口试错。
 *
 * ⛔ 铁律 15：终态 / 权限错误不得伪装成临时故障。
 *    文案中不得出现「正在处理 / 网络问题 / 稍后再试 / 重新登录即可」，
 *    也不给「重试」按钮 —— 这里没有任何可重试的动作。
 */
function UnsupportedRoleNotice({
  role,
  onSignOut,
}: {
  role: string
  onSignOut: () => void
}): JSX.Element {
  return (
    <div className="marked" role="alert" data-testid="role-unavailable-notice">
      <p className="marked__label">该角色本版本尚未开放</p>
      <p style={{ margin: 0 }}>
        当前账号的角色是 <code>{role}</code>。本版本仅向学生账号开放错题归因辅导，
        家长与老师账号暂不能发起辅导或查看辅导记录。
      </p>
      <p className="muted" style={{ marginBottom: 12 }}>
        这是账号的角色范围，不是登录失效、网络故障或数据没加载。
        重新登录、刷新或重试都不会改变结果；如需使用，请联系管理员更换为学生账号。
      </p>
      <button type="button" className="btn btn--quiet" onClick={onSignOut}>
        退出登录
      </button>
    </div>
  )
}

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

  // ⛔ T1 硬门禁：业务面入口按「角色能否完成真实操作」裁剪，
  //    治理面仍按 area 判定。两者共用一个 visibleNav，不再出现
  //    「导航承诺、后端拒绝」的错配（F6 / N1 / N2）。
  const unsupportedRole = unsupportedRoleOf(roles)
  const visibleNav = unsupportedRole !== null
    ? []
    : NAV.map((group) => ({
        ...group,
        items: group.items.filter((item) => {
          const areaOk = item.area === undefined || hasRoleAccess(roles, item.area)
          const taskOk = item.task === undefined || canPerformTask(roles, item.task)
          return areaOk && taskOk
        }),
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
          <ContentErrorBoundary resetKey={location.pathname}>
            {unsupportedRole !== null ? (
              <UnsupportedRoleNotice role={unsupportedRole} onSignOut={handleSignOut} />
            ) : (
              <Outlet />
            )}
          </ContentErrorBoundary>
        </main>
      </div>
    </div>
  )
}
