import { useEffect, useMemo, useState } from 'react'
import { Layout, Menu, Avatar, Dropdown, Typography, Grid, Alert, Button, Space } from 'antd'
import type { MenuProps } from 'antd'
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { NAV_GROUPS, NAV_NODES } from '@/layout/navConfig'
import { Breadcrumb } from '@/layout/Breadcrumb'
import { useSession } from '@/auth/session'
import { clearSession } from '@/auth/tokenStorage'
import { APP_BASE_PATH, type Role } from '@/config/constants'
import { usePageTitle } from '@/shared/usePageTitle'

const { Header, Sider, Content } = Layout
const { useBreakpoint } = Grid

export function AdminLayout(): JSX.Element {
  const [collapsed, setCollapsed] = useState(false)
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

  const menuItems: MenuProps['items'] = visibleGroups.map((g) => ({
    key: `group:${g.key}`,
    icon: g.icon,
    label: g.label,
    children: g.children.map((n) => ({
      key: n.path,
      icon: n.icon,
      label: n.label,
    })),
  }))

  const visiblePaths = useMemo(
    () => new Set(visibleGroups.flatMap((g) => g.children.map((n) => n.path))),
    [visibleGroups],
  )

  const matched = NAV_NODES.find(
    (n) => visiblePaths.has(n.path) && location.pathname.startsWith(n.path),
  )
  const selectedKey = matched?.path ?? '/tenants'
  const pageTitle = matched?.label ?? '平台管理控制台'
  usePageTitle(pageTitle)

  /** 展开态：选中项所在分组必须展开，其余保留用户手动折叠的选择。 */
  const [openKeys, setOpenKeys] = useState<string[]>([])
  const activeGroupKeys = visibleGroups
    .filter((g) => g.children.some((n) => n.path === selectedKey))
    .map((g) => `group:${g.key}`)

  useEffect(() => {
    if (activeGroupKeys.length === 0) return
    setOpenKeys((prev) => {
      const missing = activeGroupKeys.filter((k) => !prev.includes(k))
      return missing.length === 0 ? prev : [...prev, ...missing]
    })
  }, [activeGroupKeys.join('|')])

  const userMenu = {
    items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }],
    onClick: ({ key }: { key: string }) => {
      if (key === 'logout') {
        signOut()
        navigate(`${APP_BASE_PATH}/login`)
      }
    },
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        collapsible
        collapsed={collapsed}
        trigger={null}
        breakpoint="lg"
        collapsedWidth={screens.xs ? 0 : 80}
      >
        <div
          style={{
            height: 48,
            margin: 16,
            color: '#fff',
            fontWeight: 600,
            fontSize: 16,
            display: 'flex',
            alignItems: 'center',
            justifyContent: collapsed ? 'center' : 'flex-start',
            overflow: 'hidden',
            whiteSpace: 'nowrap',
          }}
        >
          {collapsed ? 'PC' : '平台管理控制台'}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          openKeys={collapsed ? [] : openKeys}
          onOpenChange={(keys) => setOpenKeys(keys as string[])}
          items={menuItems}
          onClick={({ key }) => {
            // 分组标题不承载路由：只有叶子节点（路径）才导航
            if (key.startsWith('group:')) return
            navigate(key)
          }}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 16px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <span onClick={() => setCollapsed((c) => !c)} style={{ cursor: 'pointer', fontSize: 18 }}>
            {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          </span>
          <Dropdown menu={userMenu} placement="bottomRight">
            <span style={{ cursor: 'pointer' }}>
              <Avatar icon={<UserOutlined />} />
              <Typography.Text style={{ marginLeft: 8 }}>管理员</Typography.Text>
            </span>
          </Dropdown>
        </Header>
        <Content style={{ margin: 16 }}>
          {/* ⚠️ **即将过期提示必须渲染出来**（评测 P1-5）：`expiringSoon` 此前
              每秒算一次却没有任何组件消费 —— 平台令牌 15 分钟硬过期，
              用户毫无预兆地被踢回登录页且现场全丢。这里把「还剩多少秒」摆出来，
              并给一个**保住现场**的续期入口（重新登录后回到当前页）。 */}
          {expiringSoon && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={`平台会话即将过期（剩余约 ${expiresInSeconds} 秒）`}
              description="平台令牌有效期 15 分钟且不可续期。为避免操作中断，请尽快完成当前操作并重新登录——重新登录后会回到当前页面。"
              action={
                <Space>
                  <Button
                    size="small"
                    onClick={() => {
                      // 带上原因与回跳目标：登录页据此提示「为什么被踢出」并回跳本页
                      const rel = location.pathname.startsWith(APP_BASE_PATH)
                        ? location.pathname.slice(APP_BASE_PATH.length)
                        : location.pathname
                      // ⚠️ **这里不能用 signOut()**：它会翻转认证态，而此时还在受保护路由上，
                      //    RequireAuth 随即渲染 `<Navigate to="/login" replace>` 兜底 ——
                      //    那条兜底**不带 reason/next**，会把「为什么被踢出」和「回哪去」
                      //    一起抹掉（实测 URL 变成裸 /admin/login）。
                      //    根因是优先级：React Router 的导航是 transition（低优先级），
                      //    而认证态更新是同步的，先落地的永远是后者。
                      //    故这里只清存储、不动 React 态：登录成功后 signIn 会整体替换。
                      clearSession()
                      navigate(
                        `${APP_BASE_PATH}/login?reason=expired&next=${encodeURIComponent(rel)}`,
                      )
                    }}
                  >
                    重新登录
                  </Button>
                </Space>
              }
            />
          )}
          <Breadcrumb />
          <div
            style={{
              padding: 24,
              background: '#fff',
              borderRadius: 8,
              minHeight: 'calc(100vh - 176px)',
            }}
          >
            <Outlet />
          </div>
          <div
            style={{
              textAlign: 'center',
              padding: '16px 0',
              color: '#888',
              fontSize: 12,
            }}
          >
            © 2026 Agent Platform · 平台管理控制台
          </div>
        </Content>
      </Layout>
    </Layout>
  )
}
