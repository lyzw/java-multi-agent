import { useEffect, useMemo, useState } from 'react'
import { Layout, Menu, Avatar, Dropdown, Typography, Grid } from 'antd'
import type { MenuProps } from 'antd'
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { NAV_GROUPS, NAV_NODES } from '@/layout/navConfig'
import { useSession } from '@/auth/session'
import { APP_BASE_PATH, type Role } from '@/config/constants'

const { Header, Sider, Content } = Layout
const { useBreakpoint } = Grid

export function AdminLayout(): JSX.Element {
  const [collapsed, setCollapsed] = useState(false)
  const { roles, signOut } = useSession()
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
          <div
            style={{
              padding: 24,
              background: '#fff',
              borderRadius: 8,
              minHeight: 'calc(100vh - 140px)',
            }}
          >
            <Outlet />
          </div>
        </Content>
      </Layout>
    </Layout>
  )
}
