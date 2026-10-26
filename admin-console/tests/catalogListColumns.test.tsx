// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  getToolCatalog: vi.fn(),
  getSkillCatalog: vi.fn(),
  getMcpCatalog: vi.fn(),
}))
import { getMcpCatalog, getSkillCatalog, getToolCatalog } from '@/api/adminApi'

import { ToolCatalogPage } from '@/pages/catalog/ToolCatalogPage'
import { SkillCatalogPage } from '@/pages/catalog/SkillCatalogPage'
import { McpCatalogPage } from '@/pages/catalog/McpCatalogPage'

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

/**
 * ⚠️ **夹具必须同时含「已发布」与「已停用」两种对象**。
 *
 * <p>本组用例要守的不变量是：**目录里的「状态」列不得把已停用的对象
 * 显示成「已发布」** —— 停用是**单向终态**，已发布过的对象停用后
 * `published` 仍为 true。若夹具里只有一个对象，这条用例就会因为
 * 「那个对象本来就没停用过」而假装通过。
 */
const TOOLS = [
  {
    toolId: 'tool-live',
    name: '订单查询',
    kind: 'MCP',
    riskLevel: 'READ',
    mcpServerId: 'srv-1',
    mcpToolName: 'query',
    inputSchema: '{}',
    paramPolicies: '{}',
    version: 3,
    schemaVersion: 1,
    published: true,
    publishedAt: '2026-10-20T00:00:00Z',
    lifecycleState: 'PUBLISHED',
    configFingerprint: 'fp-tool-live',
    createdAt: '2026-10-01T00:00:00Z',
  },
  {
    toolId: 'tool-dead',
    name: '旧的对账工具',
    kind: 'NATIVE',
    riskLevel: 'WRITE_EXTERNAL',
    mcpServerId: null,
    mcpToolName: null,
    inputSchema: '{}',
    paramPolicies: '{}',
    version: 1,
    schemaVersion: 1,
    // ⚠️ 已发布过 → published 仍为 true；停用是**单向终态**
    published: true,
    publishedAt: '2026-09-01T00:00:00Z',
    lifecycleState: 'DEPRECATED',
    configFingerprint: 'fp-tool-dead',
    createdAt: '2026-08-01T00:00:00Z',
  },
]

const SKILLS = [
  {
    skillId: 'sk-live',
    name: '订单分析',
    description: { purpose: '分析订单并给出结论', usage: [] },
    declaredTools: ['tool-live'],
    declaredMcp: [{ serverId: 'srv-1', toolAllowlist: null }],
    riskCeiling: 'READ',
    paramPolicies: '{}',
    requiredCaps: ['order.read'],
    version: 2,
    schemaVersion: 1,
    published: true,
    publishedAt: '2026-10-20T00:00:00Z',
    lifecycleState: 'PUBLISHED',
  },
  {
    skillId: 'sk-dead',
    name: '旧的对账技能',
    description: { purpose: '历史遗留', usage: [] },
    declaredTools: [],
    declaredMcp: [],
    riskCeiling: 'WRITE_EXTERNAL',
    paramPolicies: '{}',
    requiredCaps: [],
    version: 1,
    schemaVersion: 1,
    published: true,
    publishedAt: '2026-09-01T00:00:00Z',
    lifecycleState: 'DEPRECATED',
  },
]

const MCPS = [
  {
    serverId: 'srv-with-cred',
    version: 2,
    transport: 'HTTP',
    endpoint: 'https://example.internal/mcp',
    authKind: 'TOKEN',
    trustTier: 'INTERNAL',
    exposedToolAllowlist: null,
    connectionRisk: 'READ',
    dynamicDiscovery: false,
    configFingerprint: 'fp-srv-1',
    schemaVersion: 1,
    createdAt: '2026-10-01T00:00:00Z',
    publishedAt: '2026-10-20T00:00:00Z',
    published: true,
    hasCredentialRef: true,
    lifecycleState: 'PUBLISHED',
  },
  {
    serverId: 'srv-no-cred',
    version: 1,
    transport: 'SSE',
    endpoint: 'https://example.internal/sse',
    authKind: 'NONE',
    trustTier: 'PARTNER',
    exposedToolAllowlist: [],
    connectionRisk: 'WRITE_PROFILE',
    dynamicDiscovery: true,
    configFingerprint: 'fp-srv-2',
    schemaVersion: 1,
    createdAt: null,
    publishedAt: null,
    published: false,
    hasCredentialRef: false,
    lifecycleState: 'DRAFT',
  },
]

function renderPage(element: JSX.Element) {
  return render(
    <MemoryRouter>
      <AntApp>{element}</AntApp>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.mocked(getToolCatalog).mockResolvedValue(TOOLS as never)
  vi.mocked(getSkillCatalog).mockResolvedValue(SKILLS as never)
  vi.mocked(getMcpCatalog).mockResolvedValue(MCPS as never)
})

describe('Tool 目录 · 列表列', () => {
  it('✅ 新增列就位：状态 / 发布时间 / 配置指纹', async () => {
    renderPage(<ToolCatalogPage />)
    await waitFor(() => expect(screen.getAllByText('tool-live').length).toBeGreaterThan(0))

    expect(screen.getAllByText('状态').length).toBeGreaterThan(0)
    expect(screen.getAllByText('发布时间').length).toBeGreaterThan(0)
    expect(screen.getAllByText('配置指纹').length).toBeGreaterThan(0)
    expect(screen.getAllByText('fp-tool-live').length).toBeGreaterThan(0)
    expect(screen.getAllByText('2026-10-20T00:00:00Z').length).toBeGreaterThan(0)
  })

  it('⛔ 已停用（DEPRECATED）的 Tool 不得显示成「已发布」', async () => {
    renderPage(<ToolCatalogPage />)
    await waitFor(() => expect(screen.getAllByText('tool-live').length).toBeGreaterThan(0))

    // ⚠️ 停用是**单向终态**：published 仍为 true，但**状态列必须说「已停用」**
    expect(screen.getAllByText('已停用').length).toBe(1)
    // 只有那个真正在役的对象才算「已发布」
    expect(screen.getAllByText('已发布').length).toBe(1)
  })
})

describe('Skill 目录 · 列表列', () => {
  it('✅ 新增列就位：描述 / 状态 / 发布时间', async () => {
    renderPage(<SkillCatalogPage />)
    await waitFor(() => expect(screen.getAllByText('sk-live').length).toBeGreaterThan(0))

    expect(screen.getAllByText('描述用途（1级）').length).toBeGreaterThan(0)
    expect(screen.getAllByText('状态').length).toBeGreaterThan(0)
    expect(screen.getAllByText('发布时间').length).toBeGreaterThan(0)
    // ⚠️ 描述是「这 Skill 干什么」的第一手信息，扫目录就该看到
    expect(screen.getAllByText('分析订单并给出结论').length).toBeGreaterThan(0)
  })

  it('⛔ 已停用的 Skill 不得显示成「已发布」', async () => {
    renderPage(<SkillCatalogPage />)
    await waitFor(() => expect(screen.getAllByText('sk-live').length).toBeGreaterThan(0))

    expect(screen.getAllByText('已停用').length).toBe(1)
    expect(screen.getAllByText('已发布').length).toBe(1)
  })
})

describe('MCP Server 目录 · 列表列', () => {
  it('✅ 新增列就位：凭据引用 / 状态 / 发布时间 / 配置指纹', async () => {
    renderPage(<McpCatalogPage />)
    await waitFor(() => expect(screen.getAllByText('srv-with-cred').length).toBeGreaterThan(0))

    expect(screen.getAllByText('凭据引用').length).toBeGreaterThan(0)
    expect(screen.getAllByText('状态').length).toBeGreaterThan(0)
    expect(screen.getAllByText('发布时间').length).toBeGreaterThan(0)
    expect(screen.getAllByText('配置指纹').length).toBeGreaterThan(0)
    expect(screen.getAllByText('fp-srv-1').length).toBeGreaterThan(0)
  })

  it('✅ 凭据只呈现「是否登记」（布尔），绝不回显定位符', async () => {
    renderPage(<McpCatalogPage />)
    await waitFor(() => expect(screen.getAllByText('srv-with-cred').length).toBeGreaterThan(0))

    expect(screen.getAllByText('已登记').length).toBe(1)
    expect(screen.getAllByText('无').length).toBeGreaterThan(0)
    // ⚠️ 定位符与凭据材料**永不入响应**（目录接口只给布尔）
    expect(screen.queryByText(/credentialRef/i)).toBeNull()
  })

  it('✅ 草稿态（DRAFT）如实显示原始状态，不谎称「已发布」', async () => {
    renderPage(<McpCatalogPage />)
    await waitFor(() => expect(screen.getAllByText('srv-no-cred').length).toBeGreaterThan(0))

    expect(screen.getAllByText('DRAFT').length).toBe(1)
    expect(screen.getAllByText('已发布').length).toBe(1)
  })
})
