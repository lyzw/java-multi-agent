// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import type {
  McpCatalogItem,
  PlatformAuditEvent,
  PlatformUserSummary,
  PlatformRoleSummary,
} from '@/api/types'

// ---- mock API 层 ----
vi.mock('@/api/adminApi', () => ({
  getAuditLogs: vi.fn(),
  getMyPrincipals: vi.fn(),
  getMcpCatalog: vi.fn(),
  getPlatformAuditEvents: vi.fn(),
  getPlatformUsers: vi.fn(),
  getPlatformRoles: vi.fn(),
}))
import {
  getAuditLogs,
  getMyPrincipals,
  getMcpCatalog,
  getPlatformAuditEvents,
  getPlatformUsers,
  getPlatformRoles,
} from '@/api/adminApi'
import { AuditLogsPage } from '@/pages/audit/AuditLogsPage'
import { PrincipalMatrixPage } from '@/pages/PrincipalMatrixPage'
import { SettingsPage } from '@/pages/settings/SettingsPage'

// antd v5 渲染期读 matchMedia；jsdom 默认没有，补一个最小桩
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

beforeEach(() => {
  vi.mocked(getAuditLogs).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(getMyPrincipals).mockResolvedValue([])
  vi.mocked(getMcpCatalog).mockResolvedValue([])
  vi.mocked(getPlatformAuditEvents).mockResolvedValue([])
  vi.mocked(getPlatformUsers).mockResolvedValue([])
  vi.mocked(getPlatformRoles).mockResolvedValue([])
})

// ---- 夹具（平台身份域） ----
const auditFixture: PlatformAuditEvent[] = [
  {
    id: 'a-1',
    actorId: '11111111-1111-1111-1111-111111111111',
    actorType: 'PLATFORM_USER',
    action: 'TENANT_SUSPEND',
    targetType: 'tenant',
    targetId: 't-9',
    decision: 'ALLOW',
    reasonCode: null,
    correlationId: 'corr-1',
    ip: '10.0.0.1',
    occurredAt: '2026-10-24T10:00:00Z',
  },
  {
    id: 'a-2',
    actorId: '22222222-2222-2222-2222-222222222222',
    actorType: 'PLATFORM_USER',
    action: 'PLATFORM_LOGIN',
    targetType: 'platform_session',
    targetId: null,
    decision: 'ALLOW',
    reasonCode: null,
    correlationId: null,
    ip: '10.0.0.2',
    occurredAt: '2026-10-24T11:00:00Z',
  },
]

const usersFixture: PlatformUserSummary[] = [
  {
    id: 'u-1',
    username: 'admin',
    displayName: '平台超级管理员',
    status: 'ACTIVE',
    authSource: 'LOCAL',
    mfaRequired: true,
    authzVersion: 2,
    lastLoginAt: '2026-10-24T09:00:00Z',
  },
  {
    id: 'u-2',
    username: 'auditor',
    displayName: null,
    status: 'ACTIVE',
    authSource: 'LOCAL',
    mfaRequired: false,
    authzVersion: 1,
    lastLoginAt: null,
  },
]

const rolesFixture: PlatformRoleSummary[] = [
  {
    name: 'PLATFORM_SUPER_ADMIN',
    description: '平台超级管理员（最高权限，敏感操作强 MFA/重认证）',
    permissions: ['platform.tenant.write', 'platform.user.read', 'platform.audit.read'],
  },
  {
    name: 'PLATFORM_AUDITOR',
    description: '平台审计员（只读平台审计与脱敏租户生命周期）',
    permissions: ['platform.audit.read'],
  },
]

const mcpFixture: McpCatalogItem[] = [
  {
    serverId: 'srv-search',
    version: 1,
    transport: 'HTTP',
    endpoint: 'https://mcp.example.com',
    authKind: 'API_KEY',
    trustTier: 'TRUSTED',
    exposedToolAllowlist: null,
    connectionRisk: 'MEDIUM',
    dynamicDiscovery: false,
    configFingerprint: 'fp-1',
    schemaVersion: 1,
    createdAt: '2026-10-01T00:00:00Z',
    publishedAt: '2026-10-01T00:00:00Z',
    published: true,
    hasCredentialRef: true,
  lifecycleState: 'PUBLISHED',
  },
  {
    serverId: 'srv-open',
    version: 1,
    transport: 'STDIO',
    endpoint: null,
    authKind: 'NONE',
    trustTier: 'UNTRUSTED',
    exposedToolAllowlist: [],
    connectionRisk: 'HIGH',
    dynamicDiscovery: true,
    configFingerprint: 'fp-2',
    schemaVersion: 1,
    createdAt: null,
    publishedAt: null,
    published: false,
    hasCredentialRef: false,
  lifecycleState: 'PUBLISHED',
  },
]

describe('P5 平台审计页（/audit · 平台身份域）', () => {
  it('渲染平台审计条目与分域提示，且不含任何写入口', async () => {
    vi.mocked(getPlatformAuditEvents).mockResolvedValue(auditFixture)
    render(<AuditLogsPage />)

    // 平台/租户审计分域提示
    expect(screen.getByText('平台域审计（与租户业务审计分域）')).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getByText('TENANT_SUSPEND')).toBeInTheDocument()
    })
    expect(screen.getByText('PLATFORM_LOGIN')).toBeInTheDocument()
    expect(screen.getByText('tenant')).toBeInTheDocument()
    // 只读红线：页面上不存在任何删除/修改按钮
    expect(screen.queryByRole('button', { name: '删除' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '修改' })).not.toBeInTheDocument()
  })
})

describe('P5 平台用户与角色页（/roles · 平台身份域）', () => {
  it('渲染平台用户与冻结角色矩阵，被撤权用户给出水位告警', async () => {
    vi.mocked(getPlatformUsers).mockResolvedValue(usersFixture)
    vi.mocked(getPlatformRoles).mockResolvedValue(rolesFixture)
    render(<PrincipalMatrixPage />)

    await waitFor(() => {
      expect(screen.getByText('admin')).toBeInTheDocument()
    })
    expect(screen.getByText('auditor')).toBeInTheDocument()
    // 冻结角色矩阵呈现（角色 Tag 渲染中文名；权限清单呈现原始 permission 名，
    // platform.audit.read 同时出现在 SUPER_ADMIN 与 AUDITOR 的权限清单 → 用 *AllBy*）
    expect(screen.getByText('平台角色权限矩阵（冻结）')).toBeInTheDocument()
    expect(screen.getByText('平台审计员')).toBeInTheDocument()
    expect(screen.getAllByText('platform.audit.read').length).toBeGreaterThanOrEqual(1)
    // authzVersion=2 的用户触发撤权告警
    expect(screen.getByText('1 个平台用户被撤权过')).toBeInTheDocument()
  })
})

describe('P5 配置中心（/settings）— 安全红线', () => {
  it('凭据引用仅呈现布尔标识，任何凭据定位符/明文都不出现在 DOM 中', async () => {
    vi.mocked(getMcpCatalog).mockResolvedValue(mcpFixture)
    render(<SettingsPage />)

    await waitFor(() => {
      expect(screen.getByText('srv-search')).toBeInTheDocument()
    })
    // 布尔标识呈现
    expect(screen.getByText('已登记')).toBeInTheDocument()
    expect(screen.getByText('未登记')).toBeInTheDocument()
    // ⚠️ 安全断言（P5-T7）：凭据定位符与凭据材料绝不渲染
    const text = document.body.textContent ?? ''
    expect(text).not.toContain('vault://')
    expect(text).not.toContain('credentialRef:')
    expect(text).not.toContain('secret')
    expect(text).not.toContain('API_KEY_VALUE')
  })

  it('全局参数页签为占位态：不提供任何写入口', async () => {
    render(<SettingsPage />)
    // antd Tabs 默认懒渲染：先激活「全局参数」页签
    fireEvent.click(screen.getByRole('tab', { name: '全局参数' }))
    expect(await screen.findByText('全局参数暂未开放应用层管理')).toBeInTheDocument()
    // 占位态不给写按钮
    expect(screen.queryByRole('button', { name: '保存' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '新增' })).not.toBeInTheDocument()
  })
})
