import { useState } from 'react'
import { Layout, Menu, Avatar, Dropdown, Typography, Grid } from 'antd'
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { NAV_NODES } from '@/layout/navConfig'
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

  const visibleNodes = NAV_NODES.filter(
    (n) =>
      n.requiredRoles.length === 0 ||
      n.requiredRoles.some((r) => (roles as Role[]).includes(r)),
  )

  const menuItems = visibleNodes.map((n) => ({
    key: n.path,
    icon: n.icon,
    label: n.label,
  }))

  const matched = visibleNodes.find((n) => location.pathname.startsWith(n.path))
  const selectedKey = matched?.path ?? '/dashboard'

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
          {collapsed ? 'AC' : 'Agent 控制台'}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
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
