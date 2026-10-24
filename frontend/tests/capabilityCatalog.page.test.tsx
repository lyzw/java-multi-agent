/**
 * 能力目录页测试（Point 3 · Tool / Skill 配置页的读取面）
 *
 * ⛔ 本测试最核心的断言是「**这两个页只读**」：
 *    Tool / Skill 定义是 platform 级对象，其变更走带外运维通道
 *    （后端 CapConfigPublishController 对 PLATFORM 作用域显式 403）。
 *    若哪天有人「顺手」在页面上加了保存按钮，本测试必须转红——
 *    那不是功能完善，是把平台级写操作挂到了租户身份上（Sev-0 级越权）。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import {
  ToolCatalogPanel,
  SkillCatalogPanel,
} from '@/pages/config/CapabilityCatalogPanels'
import { ConfigToolsPage } from '@/pages/config/ConfigPages'
import { ApiError } from '@/api/client'
import { normalizeHttpError } from '@/api/errors'
import type { CapToolCatalogItem, CapSkillCatalogItem } from '@/api/types'

const listCapabilityToolsMock = vi.hoisted(() => vi.fn())
const listCapabilitySkillsMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    listCapabilityTools: listCapabilityToolsMock,
    listCapabilitySkills: listCapabilitySkillsMock,
  }
})

const TENANT = '11111111-1111-1111-1111-111111111111'

function renderPanel(ui: JSX.Element): void {
  render(
    <MemoryRouter>
      <SessionProvider>{ui}</SessionProvider>
    </MemoryRouter>,
  )
}

function sampleTool(overrides?: Partial<CapToolCatalogItem>): CapToolCatalogItem {
  return {
    toolId: 'rag.grounding',
    name: 'RAG 接地',
    kind: 'NATIVE',
    riskLevel: 'READ',
    mcpServerId: null,
    mcpToolName: null,
    inputSchema: '{"type":"object"}',
    paramPolicies: '{"query":{"max":200}}',
    version: 3,
    schemaVersion: 1,
    published: true,
    publishedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

function sampleSkill(overrides?: Partial<CapSkillCatalogItem>): CapSkillCatalogItem {
  return {
    skillId: 'error-attribution',
    name: '错题归因',
    description: '定位薄弱知识点',
    declaredTools: ['rag.grounding', 'profile.read'],
    declaredMcp: [
      { serverId: 'mcp.registry', toolAllowlist: ['lookup'] },
      { serverId: 'mcp.ops', toolAllowlist: null },
    ],
    riskCeiling: 'WRITE_PROFILE',
    paramPolicies: '{"topK":{"max":5}}',
    requiredCaps: ['CAP_PROFILE_READ'],
    version: 2,
    schemaVersion: 1,
    published: true,
    publishedAt: '2026-10-02T00:00:00Z',
    ...overrides,
  }
}

beforeEach(() => {
  window.sessionStorage.setItem(
    'agent.session',
    JSON.stringify({
      accessToken: 'tok-agent-admin',
      expiresAt: Date.now() + 900 * 1000,
      tenantId: TENANT,
      principalId: '33333333-3333-3333-3333-333333333333',
    }),
  )
  listCapabilityToolsMock.mockReset()
  listCapabilitySkillsMock.mockReset()
  listCapabilityToolsMock.mockResolvedValue([sampleTool()])
  listCapabilitySkillsMock.mockResolvedValue([sampleSkill()])
})

describe('Tool 目录（Point 3）', () => {
  it('列出工具并展示种类、风险等级与 MCP 绑定', async () => {
    renderPanel(<ToolCatalogPanel />)

    expect(await screen.findByText('rag.grounding')).toBeInTheDocument()
    expect(screen.getByText('READ · 只读')).toBeInTheDocument()
    expect(screen.getByText('平台原生')).toBeInTheDocument()
    expect(screen.getByText('v3')).toBeInTheDocument()
  })

  it('MCP 工具展示 server 与远端工具名', async () => {
    listCapabilityToolsMock.mockResolvedValue([
      sampleTool({
        toolId: 'mcp.registry.lookup',
        kind: 'MCP',
        riskLevel: 'WRITE_EXTERNAL',
        mcpServerId: 'mcp.registry',
        mcpToolName: 'lookup',
      }),
    ])
    renderPanel(<ToolCatalogPanel />)

    expect(await screen.findByText('mcp.registry.lookup')).toBeInTheDocument()
    expect(screen.getByText('WRITE_EXTERNAL · 写外部')).toBeInTheDocument()
    expect(screen.getByText('MCP 远端')).toBeInTheDocument()
    // ⚠️ 绑定关系渲染为**单个文本节点**（server / tool），
    //    便于无障碍名与断言；拆成多个元素会让文本被「打碎」。
    expect(screen.getByText('mcp.registry / lookup')).toBeInTheDocument()
  })

  it('未知风险等级呈现为「不可判定」，⛔ 不得静默当成最严或最松', async () => {
    listCapabilityToolsMock.mockResolvedValue([sampleTool({ riskLevel: 'WEIRD' })])
    renderPanel(<ToolCatalogPanel />)

    expect(await screen.findByText('WEIRD · 不可判定')).toBeInTheDocument()
  })

  it('⛔ 本页不提供任何写入入口（平台级对象走带外运维通道）', async () => {
    renderPanel(<ToolCatalogPanel />)
    await screen.findByText('rag.grounding')

    expect(screen.queryByRole('button', { name: '保存' })).toBeNull()
    expect(screen.queryByRole('button', { name: '新增' })).toBeNull()
    expect(screen.queryByRole('button', { name: '删除' })).toBeNull()
    expect(screen.queryByRole('button', { name: '发布' })).toBeNull()
    expect(screen.getByText(/为什么这里不能改/)).toBeInTheDocument()
  })

  it('读取失败给出错误态与重试', async () => {
    listCapabilityToolsMock.mockRejectedValue(
      new ApiError(normalizeHttpError(500, { message: 'boom' })),
    )
    renderPanel(<ToolCatalogPanel />)

    expect(await screen.findByText('没能完成')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新读取' })).toBeInTheDocument()
  })

  it('空目录是合法空态，不是故障', async () => {
    listCapabilityToolsMock.mockResolvedValue([])
    renderPanel(<ToolCatalogPanel />)

    expect(await screen.findByText('平台还没有登记工具定义')).toBeInTheDocument()
  })

  it('⛔ 目录表必须包在 .table-wrap 里（窄屏溢出应发生在表内，不是整页）', async () => {
    renderPanel(<ToolCatalogPanel />)
    await screen.findByText('rag.grounding')

    const tables = Array.from(document.querySelectorAll('table'))
    expect(tables.length).toBeGreaterThan(0)
    for (const t of tables) {
      expect(t.parentElement?.classList.contains('table-wrap')).toBe(true)
    }
  })
})

describe('Skill 目录（Point 3）', () => {
  it('列出技能并展示风险上限、声明工具与必需能力', async () => {
    renderPanel(<SkillCatalogPanel />)

    expect(await screen.findByText('error-attribution')).toBeInTheDocument()
    expect(screen.getByText('WRITE_PROFILE · 写画像')).toBeInTheDocument()
    expect(screen.getByText('rag.grounding')).toBeInTheDocument()
    expect(screen.getByText('CAP_PROFILE_READ')).toBeInTheDocument()
  })

  it('⛔ MCP 白名单的 null 与 [] 必须分开呈现（§4.4 语义不同）', async () => {
    renderPanel(<SkillCatalogPanel />)

    // null = 未配置 → 取 Server 全集
    expect(
      await screen.findByText('未配置（取 Server 全集）'),
    ).toBeInTheDocument()
    expect(screen.getByText('mcp.ops')).toBeInTheDocument()
    // 显式清单 → 逐条列出
    expect(screen.getByText('mcp.registry')).toBeInTheDocument()
    expect(screen.getByText('lookup')).toBeInTheDocument()
  })

  it('⛔ 显式空清单呈现为「不允许任何操作」，不得当成未配置', async () => {
    listCapabilitySkillsMock.mockResolvedValue([
      sampleSkill({
        declaredMcp: [{ serverId: 'mcp.ops', toolAllowlist: [] }],
      }),
    ])
    renderPanel(<SkillCatalogPanel />)

    expect(
      await screen.findByText('显式不允许任何操作'),
    ).toBeInTheDocument()
    // ⚠️ 反证：同一份夹具下**不得**出现「未配置」的表述。
    expect(screen.queryByText('未配置（取 Server 全集）')).toBeNull()
  })

  it('⛔ 本页不提供任何写入入口', async () => {
    renderPanel(<SkillCatalogPanel />)
    await screen.findByText('error-attribution')

    expect(screen.queryByRole('button', { name: '保存' })).toBeNull()
    expect(screen.queryByRole('button', { name: '新增' })).toBeNull()
    expect(screen.queryByRole('button', { name: '删除' })).toBeNull()
  })

  it('⛔ 技能表必须包在 .table-wrap 里（窄屏溢出应发生在表内，不是整页）', async () => {
    renderPanel(<SkillCatalogPanel />)
    await screen.findByText('error-attribution')

    const tables = Array.from(document.querySelectorAll('table'))
    expect(tables.length).toBeGreaterThan(0)
    for (const t of tables) {
      expect(t.parentElement?.classList.contains('table-wrap')).toBe(true)
    }
  })
})

describe('工具与审批页的子 tab（Point 3）', () => {
  it('默认落在「工具与审批口径」，可切到 Tool / Skill 目录', async () => {
    const user = userEvent.setup()
    renderPanel(<ConfigToolsPage />)

    const policies = await screen.findByRole('tab', { name: '工具与审批口径' })
    expect(policies).toHaveAttribute('aria-selected', 'true')

    await user.click(screen.getByRole('tab', { name: 'Tool 目录' }))
    expect(await screen.findByText('rag.grounding')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Skill 目录' }))
    expect(await screen.findByText('error-attribution')).toBeInTheDocument()
  })

  it('切到 Tool 目录时才拉取工具列表（不预加载）', async () => {
    const user = userEvent.setup()
    renderPanel(<ConfigToolsPage />)

    await screen.findByRole('tab', { name: '工具与审批口径' })
    expect(listCapabilityToolsMock).not.toHaveBeenCalled()

    await user.click(screen.getByRole('tab', { name: 'Tool 目录' }))
    await waitFor(() => expect(listCapabilityToolsMock).toHaveBeenCalledTimes(1))
  })
})
