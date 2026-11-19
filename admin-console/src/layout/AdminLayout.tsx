import { useEffect, useMemo, useRef, useState } from 'react'
import { Layout, Menu, Dropdown, Grid, Alert, Button, Space } from 'antd'
import type { MenuProps } from 'antd'
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  LogoutOutlined,
  LeftOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { NAV_GROUPS, findNavNode } from '@/layout/navConfig'
import { Breadcrumb } from '@/layout/Breadcrumb'
import { useSession } from '@/auth/session'
import { clearSession } from '@/auth/tokenStorage'
import { APP_BASE_PATH, type Role } from '@/config/constants'
import { usePageTitle } from '@/shared/usePageTitle'

const { Header, Sider, Content } = Layout
const { useBreakpoint } = Grid

/** 侧栏视图：'index' = 全部分组索引；分组 key = 该组页面列表。 */
type DrillView = string | null

/** 侧栏顶部铭牌标记（黄铜同心准星）——控制面的品牌锚点。 */
function BrandMark(): JSX.Element {
  return (
    <svg className="dc-brandmark" viewBox="0 0 26 26" fill="none" aria-hidden="true">
      <rect x="1" y="1" width="24" height="24" rx="3" stroke="#A9761B" strokeWidth="1.4" />
      <path d="M6 13h4M16 13h4M13 6v4M13 16v4" stroke="#A9761B" strokeWidth="1.4" strokeLinecap="round" />
      <circle cx="13" cy="13" r="3.4" stroke="#A9761B" strokeWidth="1.4" />
      <circle cx="13" cy="13" r="1" fill="#A9761B" />
    </svg>
  )
}

export function AdminLayout(): JSX.Element {
  const [collapsed, setCollapsed] = useState(false)
  /** 当前钻取到的分组；null = 显示全部分组索引。 */
  const [drill, setDrill] = useState<DrillView>(null)
  const { roles, signOut, expiringSoon, expiresInSeconds } = useSession()
  const location = useLocation()
  const navigate = useNavigate()
  const screens = useBreakpoint()

  /** 分组 → 过滤掉无权访问的节点；整组为空则整组不显示（不留一个空的分组壳）。 */
  const visibleGroups = useMemo(
    () =>
      NAV_GROUPS.filter(
        (g) =>
          g.requiredRoles.length === 0 ||
          g.requiredRoles.some((r) => (roles as Role[]).includes(r)),
      )
        .map((g) => ({
          ...g,
          children: g.children.filter(
            (n) =>
              n.requiredRoles.length === 0 ||
              n.requiredRoles.some((r) => (roles as Role[]).includes(r)),
          ),
        }))
        .filter((g) => g.children.length > 0),
    [roles],
  )

  /** 当前路由命中的节点/分组（精确优先、最长前缀兜底，深链 /tenants/:id 亦定位）。 */
  const matched = useMemo(
    () =>
      findNavNode(
        location.pathname,
        visibleGroups.flatMap((g) => g.children),
      ),
    [location.pathname, visibleGroups],
  )
  const currentGroup = useMemo(
    () => (matched ? visibleGroups.find((g) => g.children.some((n) => n.key === matched.key)) : undefined),
    [matched, visibleGroups],
  )
  const pageTitle = matched?.label ?? '平台管理控制台'
  usePageTitle(pageTitle)

  /**
   * 路由变化时自动把侧栏定位到所属分组——深链、刷新、浏览器前进后退都不丢层级。
   * 仅在 pathname **真正变化** 时执行：用户在索引态手动后退（不改路由）不会被强行拽回钻取态。
   */
  const lastPath = useRef<string | null>(null)
  useEffect(() => {
    if (lastPath.current === location.pathname) return
    lastPath.current = location.pathname
    setDrill(currentGroup?.key ?? null)
  }, [location.pathname, currentGroup])

  /**
   * 钻取视图的菜单项：
   *  - 索引态：一列分组，右侧带页面数——像目录，也像面包屑的「上一级」。
   *  - 钻取态：首项是「返回全部分组」，随后是该组页面列表。
   * 折叠态强制显示分组索引（80px 宽放页面名无意义），点分组即展开并钻入。
   */
  // ⚠️ 角色异步回补可能让 drill 指向的分组忽然无权访问；此时必须回退索引态，
  //    否则下方 activeGroup!.children 会在真实浏览器里抛 TypeError（jsdom 仅打 uncaught，测试仍绿）。
  const activeGroup = drill === null ? undefined : visibleGroups.find((g) => g.key === drill)
  const isIndex = collapsed || !activeGroup

  const menuItems: MenuProps['items'] = useMemo(() => {
    if (isIndex || !activeGroup) {
      return visibleGroups.map((g) => ({
        key: `group:${g.key}`,
        icon: g.icon,
        label: (
          <span className="dc-drill-index-label">
            <span className="dc-drill-index-name">{g.label}</span>
            <span className="dc-drill-index-count">{g.children.length}</span>
          </span>
        ),
      }))
    }
    const g = activeGroup!
    return [
      {
        key: '__back__',
        icon: <LeftOutlined />,
        label: <span className="dc-drill-back">全部分组</span>,
        className: 'dc-drill-back-item',
      },
      ...g.children.map((n) => ({
        key: n.path,
        icon: n.icon,
        label: n.label,
      })),
    ]
  }, [isIndex, activeGroup, visibleGroups])

  /** 选中态：索引态高亮当前路由所属分组；钻取态高亮当前页面。 */
  const selectedKey = isIndex
    ? currentGroup
      ? `group:${currentGroup.key}`
      : ''
    : matched && activeGroup?.children.some((n) => n.path === matched.path)
      ? matched.path
      : ''

  /**
   * 面包屑「控制台 / 分组」级点击 = 路径向上，退回分组索引（不跳路由、不改默认落地行为）。
   * 钻入某个分组是索引态下点分组项的动作，两者职责分离、语义单一。
   */
  const handleCrumbGroup = (): void => {
    setDrill(null)
  }

  /** 会话余量百分比：平台令牌固定 2h（7200s），按比例画刻度条；真实到期仍以 expiringSoon 为准。 */
  const sessionPercent = Math.max(0, Math.min(100, Math.round((expiresInSeconds / 7200) * 100)))
  const mm = String(Math.floor(expiresInSeconds / 60)).padStart(2, '0')
  const ss = String(expiresInSeconds % 60).padStart(2, '0')

  const userMenu = {
    items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }],
    onClick: ({ key }: { key: string }) => {
      if (key === 'logout') {
        signOut()
        // ⚠️ navigate 目标相对 Router basename（/admin），不带前缀（否则 /admin/admin/login）。
        navigate('/login')
      }
    },
  }

  return (
    <Layout style={{ minHeight: '100vh', background: 'var(--dc-paper)' }}>
      <Sider
        theme="dark"
        collapsible
        collapsed={collapsed}
        trigger={null}
        breakpoint="lg"
        collapsedWidth={screens.xs ? 0 : 80}
        width={248}
        style={{ display: 'flex', flexDirection: 'column' }}
      >
        <div className={`dc-brand${collapsed ? ' dc-brand--collapsed' : ''}`}>
          <BrandMark />
          {!collapsed && (
            <div>
              <div className="dc-brand__name">平台控制台</div>
              <div className="dc-brand__sub">运营 / 治理 / 观测</div>
            </div>
          )}
        </div>
        <div style={{ flex: 1, overflow: 'auto', minHeight: 0 }}>
          <Menu
            theme="dark"
            mode="inline"
            inlineCollapsed={collapsed}
            selectedKeys={[selectedKey]}
            items={menuItems}
            onClick={({ key }) => {
              if (key === '__back__') {
                setDrill(null)
                return
              }
              if (key.startsWith('group:')) {
                setDrill(key.slice('group:'.length))
                setCollapsed(false)
                return
              }
              navigate(key)
            }}
            style={{ borderInlineEnd: 0, background: 'transparent' }}
          />
        </div>
        {!collapsed && (
          <div className="dc-session">
            <div className="dc-session__row">
              <span>平台令牌剩余</span>
              <span className="dc-session__time">{mm}:{ss}</span>
            </div>
            <div className="dc-session__bar" aria-hidden="true">
              <i style={{ width: `${sessionPercent}%` }} />
            </div>
          </div>
        )}
      </Sider>
      <Layout style={{ background: 'var(--dc-paper)', minWidth: 0 }}>
        <Header
          style={{
            height: 64,
            padding: '0 28px',
            display: 'flex',
            alignItems: 'center',
            gap: 16,
            borderBottom: '1px solid var(--dc-line)',
            position: 'sticky',
            top: 0,
            zIndex: 20,
          }}
        >
          <Button
            type="text"
            aria-label={collapsed ? '展开导航' : '折叠导航'}
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed((c) => !c)}
            style={{ color: 'var(--dc-text-2)' }}
          />
          {/* 面包屑即唯一路径指示，收敛在顶栏：控制台 › 分组 › 页面，分组/首页可点回退侧栏。 */}
          <Breadcrumb groups={visibleGroups} onGroupClick={handleCrumbGroup} />
          <div style={{ flex: 1 }} />
          <div className="dc-envchip">
            <span className="pulse" />
            生产环境
          </div>
          <Dropdown menu={userMenu} placement="bottomRight" trigger={['click', 'hover']}>
            <span style={{ cursor: 'pointer', display: 'inline-flex', alignItems: 'center' }}>
              <span className="dc-avatar">管</span>
            </span>
          </Dropdown>
        </Header>
        <Content style={{ margin: 0, padding: '22px 28px 56px', minWidth: 0 }}>
          {/* ⚠️ 会话过期提示必须渲染：平台令牌 2h 硬过期，给保住现场的续期入口。
              navigate 目标相对 basename；不能先 signOut（否则 RequireAuth 兜底丢 reason/next）。 */}
          {expiringSoon && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={`平台会话即将过期（剩余约 ${expiresInSeconds} 秒）`}
              description="平台令牌有效期 2 小时且不可续期。请尽快完成当前操作并重新登录——重新登录后会回到当前页面。"
              action={
                <Space>
                  <Button
                    size="small"
                    onClick={() => {
                      const rel = location.pathname.startsWith(APP_BASE_PATH)
                        ? location.pathname.slice(APP_BASE_PATH.length)
                        : location.pathname
                      clearSession()
                      navigate(`/login?reason=expired&next=${encodeURIComponent(rel)}`)
                    }}
                  >
                    重新登录
                  </Button>
                </Space>
              }
            />
          )}
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
