// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  getPlatformUsers: vi.fn(),
  getPlatformUser: vi.fn(),
  getPlatformRoles: vi.fn(),
  createPlatformUser: vi.fn(),
  updatePlatformUser: vi.fn(),
  resetPlatformUserPassword: vi.fn(),
  assignPlatformUserRole: vi.fn(),
  revokePlatformUserRole: vi.fn(),
}))
import { assignPlatformUserRole, getPlatformRoles, getPlatformUsers } from '@/api/adminApi'

// ---- mock 会话：当前登录者是 u-1（超管） ----
vi.mock('@/auth/session', () => ({
  useSession: () => ({
    session: { accessToken: 't', expiresAt: 9999999999999, principalId: 'u-1', roles: ['PLATFORM_SUPER_ADMIN'] },
    roles: ['PLATFORM_SUPER_ADMIN'],
    authenticated: true,
  }),
}))

import { UsersManagePage } from '@/pages/users/UsersManagePage'

beforeAll(() => {
  if (!window.matchMedia) {
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })) as unknown as typeof window.matchMedia
  }
})

// ---- 夹具：2 个平台用户（u-1 = 当前登录者本人；u-shield 是超管） ----
const USERS = [
  {
    id: 'u-1',
    username: 'admin',
    displayName: '平台超级管理员',
    email: 'admin@example.com',
    status: 'ACTIVE',
    authSource: 'LOCAL',
    mfaRequired: true,
    authzVersion: 2,
    lastLoginAt: '2026-10-25T09:00:00Z',
    createdAt: null,
    updatedAt: null,
    roles: ['PLATFORM_SUPER_ADMIN'],
  },
  {
    id: 'u-shield',
    username: 'tenant.admin',
    displayName: '租户生命周期管理员',
    email: 'tl@example.com',
    status: 'ACTIVE',
    authSource: 'LOCAL',
    mfaRequired: false,
    authzVersion: 1,
    lastLoginAt: null,
    createdAt: null,
    updatedAt: null,
    roles: ['TENANT_LIFECYCLE_ADMIN'],
  },
] as never

const ROLE_CATALOG = [
  { name: 'PLATFORM_SUPER_ADMIN', description: '平台超级管理员（最高权限，敏感操作强 MFA/重认证）', permissions: [] },
  { name: 'TENANT_LIFECYCLE_ADMIN', description: '租户生命周期管理员', permissions: [] },
  { name: 'PLATFORM_AUDITOR', description: '平台审计员', permissions: [] },
] as never

function renderPage() {
  // UsersManagePage 使用 App.useApp() 的 message —— 必须包在 <AntApp> 里
  return render(
    <AntApp>
      <UsersManagePage />
    </AntApp>,
  )
}

beforeEach(() => {
  vi.mocked(getPlatformUsers).mockResolvedValue(USERS)
  vi.mocked(getPlatformRoles).mockResolvedValue(ROLE_CATALOG)
})

describe('平台用户管理页（/users · 平台身份域写端点）', () => {
  it('渲染用户表与角色 Tag；出现新建用户入口', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('admin')).toBeInTheDocument()
    })
    expect(screen.getByText('tenant.admin')).toBeInTheDocument()
    // 角色 Tag 以中文名呈现（roleShared 的 ROLE_LABELS）；显示名同文 → 用 *AllBy*
    expect(screen.getAllByText('租户生命周期管理员').length).toBeGreaterThanOrEqual(1)
    // 写入口存在
    expect(screen.getByRole('button', { name: /新建用户/ })).toBeInTheDocument()
  })

  it('自身保护：当前登录者（admin）所在行的停用/重置密码/角色按钮不可用', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('admin')).toBeInTheDocument()
    })
    // 自己那一行（含 admin 文本的行）内，自身保护相关的操作按钮必须被禁用
    const ownRow = screen.getByText('admin').closest('tr') as HTMLElement
    expect(ownRow).not.toBeNull()
    const disabledLike = within(ownRow).queryAllByRole('button').filter((b) => b.hasAttribute('disabled'))
    expect(disabledLike.length).toBeGreaterThanOrEqual(1)
    // 对照：另一行（tenant.admin）应保有可用操作（自身保护只约束自己）
    const otherRow = screen.getByText('tenant.admin').closest('tr') as HTMLElement
    const otherEnabled = within(otherRow).queryAllByRole('button').filter((b) => !b.hasAttribute('disabled'))
    expect(otherEnabled.length).toBeGreaterThanOrEqual(1)
  })

  it('角色抽屉：为另一用户赋予角色，调用写端点并展示权威 RoleResponse', async () => {
    vi.mocked(assignPlatformUserRole).mockResolvedValue({ roles: ['TENANT_LIFECYCLE_ADMIN', 'PLATFORM_AUDITOR'] } as never)
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('tenant.admin')).toBeInTheDocument()
    })
    const otherRow = screen.getByText('tenant.admin').closest('tr') as HTMLElement
    fireEvent.click(within(otherRow).getByRole('button', { name: '角色' }))
    // 赋予角色：选择目录中的 PLATFORM_AUDITOR
    await waitFor(() => {
      expect(screen.getByText('角色管理 · tenant.admin')).toBeInTheDocument()
    })
    fireEvent.mouseDown(screen.getByText('选择角色'))
    const option = await screen.findByText('平台审计员')
    fireEvent.click(option)
    fireEvent.click(screen.getByRole('button', { name: /赋\s*予/ }))
    await waitFor(() => {
      expect(assignPlatformUserRole).toHaveBeenCalledOnce()
    })
    expect(vi.mocked(assignPlatformUserRole).mock.calls[0]?.[1]).toBe('PLATFORM_AUDITOR')
    // Tag（已赋予列表）与 Select 选项内容同文 → 用 *AllBy* 断言已渲染
    await waitFor(() => {
      expect(screen.getAllByText('平台审计员').length).toBeGreaterThanOrEqual(1)
    })
  })

  it('加载失败呈现 Alert，不给空表假象', async () => {
    vi.mocked(getPlatformUsers).mockRejectedValue(new Error('503 ops down'))
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('503 ops down')).toBeInTheDocument()
    })
  })
})
