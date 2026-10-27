// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
// ⚠️ 页面还间接依赖 publish 构造器（platformPublishRequests → getConsoleTool/Mcp），一并给桩。
vi.mock('@/api/adminApi', () => ({
  getConsoleToolCatalog: vi.fn(),
  getConsoleSkillCatalog: vi.fn(),
  getConsoleMcpCatalog: vi.fn(),
  getConsoleTool: vi.fn(),
  getConsoleSkill: vi.fn(),
  getConsoleMcp: vi.fn(),
  getConsoleToolRelations: vi.fn(),
  getConsoleSkillRelations: vi.fn(),
  getConsoleMcpRelations: vi.fn(),
  getConsoleAgentRegistry: vi.fn(),
  listPlatformCapabilityRequests: vi.fn(),
  decidePlatformPublishRequest: vi.fn(),
  applyPlatformPublishRequest: vi.fn(),
  submitPlatformPublishRequest: vi.fn(),
  submitPlatformLifecycleRequest: vi.fn(),
}))
import {
  getConsoleMcp,
  getConsoleMcpCatalog,
  getConsoleMcpRelations,
  getConsoleSkill,
  getConsoleSkillCatalog,
  getConsoleSkillRelations,
} from '@/api/adminApi'

import { McpCatalogPage } from '@/pages/console/McpCatalogPage'
import { SkillCatalogPage } from '@/pages/console/SkillCatalogPage'
import type { McpCatalogItem, McpRelations, SkillCatalogItem, SkillRelations } from '@/api/types'

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

// ---- 夹具：Skill / MCP 各一条 PUBLISHED ----
const SKILL: SkillCatalogItem = {
  skillId: 'skill-published',
  name: '查询技能',
  description: { purpose: '只读查询技能', usage: [] },
  riskCeiling: 'READ',
  paramPolicies: '{"args":{}}',
  requiredCaps: [],
  declaredTools: ['tool-1', 'tool-gone'],
  declaredMcp: [{ serverId: 'mcp-gone', toolAllowlist: null }],
  version: 2,
  schemaVersion: 1,
  published: true,
  publishedAt: '2026-10-20T00:00:00Z',
  lifecycleState: 'PUBLISHED',
}

const SKILL_RELATIONS: SkillRelations = {
  skillId: 'skill-published',
  tools: [{ toolId: 'tool-1', name: '工具一', kind: 'NATIVE', riskLevel: 'READ', version: 1, published: true }],
  // ⚠️ 声明了但目录里不存在 —— 详情必须**如实**呈现悬空引用
  missingTools: ['tool-gone'],
  mcpServers: [],
  missingMcpServers: ['mcp-gone'],
}

const MCP: McpCatalogItem = {
  serverId: 'mcp-published',
  version: 1,
  transport: 'HTTP',
  endpoint: 'http://127.0.0.1:19090/mcp',
  authKind: 'NONE',
  trustTier: 'TRUSTED',
  exposedToolAllowlist: ['tool-1'],
  connectionRisk: 'READ',
  dynamicDiscovery: false,
  configFingerprint: 'fp-mcp-1',
  schemaVersion: 1,
  createdAt: '2026-10-01T00:00:00Z',
  publishedAt: '2026-10-20T00:00:00Z',
  published: true,
  hasCredentialRef: false,
  lifecycleState: 'PUBLISHED',
}

const MCP_RELATIONS: McpRelations = {
  serverId: 'mcp-published',
  // ⚠️ 关系端点的允许集返回 null（= 未配置取全集；≠ []）—— 详情必须按此语义呈现
  exposedToolAllowlist: null,
  tools: [{ toolId: 'tool-1', name: '工具一', kind: 'NATIVE', riskLevel: 'READ', version: 1, published: true }],
}

function renderPage(page: JSX.Element): ReturnType<typeof render> {
  return render(
    <MemoryRouter>
      <AntApp>
        {page}
      </AntApp>
    </MemoryRouter>,
  )
}

describe('平台控制台 · Skill 目录页（/skills · 双击详情抽屉）', () => {
  beforeEach(() => {
    // 调用计数断言要求每个用例从零开始
    vi.mocked(getConsoleSkillCatalog).mockReset().mockResolvedValue([SKILL] as never)
    vi.mocked(getConsoleSkill).mockReset().mockResolvedValue(SKILL as never)
    vi.mocked(getConsoleSkillRelations).mockReset().mockResolvedValue(SKILL_RELATIONS as never)
  })

  it('✅ 双击行打开详情抽屉：单条 + 关系取数，目录 list **不再**被调用', async () => {
    const { container } = renderPage(<SkillCatalogPage />)
    await waitFor(() => expect(screen.getByText('skill-published')).toBeTruthy())
    expect(getConsoleSkillCatalog).toHaveBeenCalledTimes(1)

    fireEvent.dblClick(container.querySelector<HTMLElement>('td')!)
    await waitFor(() => expect(screen.getByText('Skill 详情 · skill-published')).toBeTruthy())
    // 关系如实呈现：已登记 + 悬空引用分得开
    await waitFor(() => expect(screen.getByText(/tool-gone（悬空引用/)).toBeTruthy())
    await waitFor(() => expect(screen.getByText(/mcp-gone（悬空引用/)).toBeTruthy())
    // 详情取的是单条 + 关系；目录 list 保持一次
    expect(getConsoleSkill).toHaveBeenCalledWith('skill-published')
    expect(getConsoleSkillRelations).toHaveBeenCalledWith('skill-published')
    expect(getConsoleSkillCatalog).toHaveBeenCalledTimes(1)
  })
})

describe('平台控制台 · MCP 目录页（/mcps · 双击详情抽屉）', () => {
  beforeEach(() => {
    vi.mocked(getConsoleMcpCatalog).mockReset().mockResolvedValue([MCP] as never)
    vi.mocked(getConsoleMcp).mockReset().mockResolvedValue(MCP as never)
    vi.mocked(getConsoleMcpRelations).mockReset().mockResolvedValue(MCP_RELATIONS as never)
  })

  it('✅ 双击行打开详情抽屉：允许集 null≠[] 按语义呈现，目录 list **不再**被调用', async () => {
    const { container } = renderPage(<McpCatalogPage />)
    await waitFor(() => expect(screen.getByText('mcp-published')).toBeTruthy())
    expect(getConsoleMcpCatalog).toHaveBeenCalledTimes(1)

    fireEvent.dblClick(container.querySelector<HTMLElement>('td')!)
    await waitFor(() => expect(screen.getByText('MCP Server 详情 · mcp-published')).toBeTruthy())
    // 待审关系区：允许集 null = 未配置（取全集）；已登记 Tool 给出清单
    await waitFor(() => expect(screen.getByText('未配置（取全集）')).toBeTruthy())
    await waitFor(() => expect(screen.getAllByText('tool-1').length).toBeGreaterThan(0))
    expect(getConsoleMcp).toHaveBeenCalledWith('mcp-published')
    expect(getConsoleMcpRelations).toHaveBeenCalledWith('mcp-published')
    expect(getConsoleMcpCatalog).toHaveBeenCalledTimes(1)
  })
})
