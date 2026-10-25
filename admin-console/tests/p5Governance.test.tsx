// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import type { AuditLogEntry, McpCatalogItem, PrincipalResponse } from '@/api/types'

// ---- mock API 层 ----
vi.mock('@/api/adminApi', () => ({
  getAuditLogs: vi.fn(),
  getMyPrincipals: vi.fn(),
  getMcpCatalog: vi.fn(),
}))
import { getAuditLogs, getMyPrincipals, getMcpCatalog } from '@/api/adminApi'
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
})

// ---- 夹具 ----
const auditFixture: AuditLogEntry[] = [
  {
    auditId: 'a-1',
    actorId: 'p-admin',
    action: 'CONFIG_APPROVE',
    resourceType: 'config_publish_request',
    resourceId: 'r-9',
    createdAt: '2026-10-24T10:00:00Z',
    correlationId: 'corr-1',
    detail: { decision: 'APPROVE', approverId: 'p-admin', requesterId: 'p-author' },
  },
  {
    auditId: 'a-2',
    actorId: 'p-admin',
    action: 'AUDIT_READ',
    resourceType: 'audit_record',
    resourceId: null,
    createdAt: '2026-10-24T11:00:00Z',
    correlationId: null,
    detail: { count: 7 },
  },
]

const principalsFixture: PrincipalResponse[] = [
  {
    principalId: 'p-1',
    loginName: 'admin',
    displayName: '租户管理员',
    status: 'ACTIVE',
    authzVersion: 2,
    roles: ['TENANT_ADMIN'],
  },
  {
    principalId: 'p-2',
    loginName: 'author',
    displayName: null,
    status: 'ACTIVE',
    authzVersion: 1,
    roles: ['AGENT_AUTHOR'],
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
    hasCredentialRef: false,
  lifecycleState: 'PUBLISHED',
  },
]

describe('P5 审计日志页（/audit）', () => {
  it('渲染审计条目与不可篡改提示，且不含任何写入口', async () => {
    vi.mocked(getAuditLogs).mockResolvedValue({ items: auditFixture, nextCursor: null })
    render(<AuditLogsPage />)

    // 不可篡改 + 查询留痕双提示
    expect(screen.getByText('审计记录不可篡改，查询行为本身亦留痕')).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getByText('CONFIG_APPROVE')).toBeInTheDocument()
    })
    expect(screen.getByText('config_publish_request')).toBeInTheDocument()
    expect(screen.getByText('AUDIT_READ')).toBeInTheDocument()
    // 游标分页提示：无跳页
    expect(screen.getByText('游标分页（追加只读，无跳页）')).toBeInTheDocument()
    // 页面上不存在任何删除/修改按钮
    expect(screen.queryByRole('button', { name: '删除' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '修改' })).not.toBeInTheDocument()
  })
})

describe('P5 角色矩阵页（/roles）', () => {
  it('渲染主体-角色矩阵，被撤权主体给出水位告警', async () => {
    vi.mocked(getMyPrincipals).mockResolvedValue(principalsFixture)
    render(<PrincipalMatrixPage />)

    await waitFor(() => {
      expect(screen.getByText('admin')).toBeInTheDocument()
    })
    // 角色分布
    expect(screen.getByText('租户管理员 · 1')).toBeInTheDocument()
    expect(screen.getByText('Agent 作者 · 1')).toBeInTheDocument()
    // authzVersion=2 的主体触发撤权告警
    expect(screen.getByText('租户内 1 个主体被撤权过')).toBeInTheDocument()
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
