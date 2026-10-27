// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  getConsoleToolCatalog: vi.fn(),
  getConsoleTool: vi.fn(),
  getConsoleToolRelations: vi.fn(),
  getConsoleSkillCatalog: vi.fn(),
  getConsoleSkill: vi.fn(),
  getConsoleMcpCatalog: vi.fn(),
  getConsoleMcp: vi.fn(),
  getConsoleAgentRegistry: vi.fn(),
  listPlatformCapabilityRequests: vi.fn(),
  decidePlatformPublishRequest: vi.fn(),
  applyPlatformPublishRequest: vi.fn(),
  submitPlatformPublishRequest: vi.fn(),
  submitPlatformLifecycleRequest: vi.fn(),
}))
import { getConsoleToolCatalog, getConsoleTool, getConsoleToolRelations } from '@/api/adminApi'

import { ToolCatalogPage } from '@/pages/console/ToolCatalogPage'

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

// ---- 夹具：3 条不同 lifecycleState ----
const TOOLS = [
  {
    toolId: 'tool-published',
    name: '订单查询',
    kind: 'NATIVE',
    riskLevel: 'READ',
    mcpServerId: null,
    mcpToolName: null,
    inputSchema: '{}',
    paramPolicies: '{}',
    version: 3,
    schemaVersion: 1,
    published: true,
    publishedAt: '2026-10-20T00:00:00Z',
    lifecycleState: 'PUBLISHED',
  },
  {
    toolId: 'tool-draft',
    name: '草稿工具',
    kind: 'HTTP',
    riskLevel: 'WRITE_PROFILE',
    mcpServerId: null,
    mcpToolName: null,
    httpMethod: 'POST',
    httpUrl: 'https://example.internal/x',
    inputSchema: '{}',
    paramPolicies: '{}',
    version: 1,
    schemaVersion: 1,
    published: false,
    publishedAt: null,
    lifecycleState: 'DRAFT',
  },
  {
    toolId: 'tool-deprecated',
    name: '旧工具',
    kind: 'NATIVE',
    riskLevel: 'READ',
    mcpServerId: null,
    mcpToolName: null,
    inputSchema: '{}',
    paramPolicies: '{}',
    version: 2,
    schemaVersion: 1,
    published: true,
    publishedAt: '2026-09-01T00:00:00Z',
    lifecycleState: 'DEPRECATED',
  },
]

function renderCatalog() {
  return render(
    <MemoryRouter>
      <AntApp>
        <ToolCatalogPage />
      </AntApp>
    </MemoryRouter>,
  )
}

describe('平台控制台 · Tool 目录页（/tools · 生命周期分组）', () => {
  beforeEach(() => {
    // ⚠️ 每个用例从零开始：调用计数的断言（「目录 list 只打一次」）要求计数干净。
    vi.mocked(getConsoleToolCatalog).mockReset().mockResolvedValue(TOOLS as never)
    // 详情抽屉的取数（只读详情不触目录 list）
    vi.mocked(getConsoleTool).mockReset().mockImplementation(
      (id: string) => Promise.resolve(TOOLS.find((t) => t.toolId === id) as never),
    )
    vi.mocked(getConsoleToolRelations).mockReset().mockImplementation(
      (id: string) => Promise.resolve({ toolId: id, mcpServer: null } as never),
    )
  })

  it('✅ Tabs 按生命周期分组：全部 / 已发布 / 草稿 / 已停用都出现', async () => {
    const { container } = renderCatalog()
    await waitFor(() =>
      expect(screen.getByText(/平台级 Tool 目录/)).toBeTruthy(),
    )
    const labels = Array.from(container.querySelectorAll('.ant-tabs-tab')).map((t) => t.textContent ?? '')
    expect(labels.some((l) => l.includes('全部'))).toBe(true)
    expect(labels.some((l) => l.includes('已发布'))).toBe(true)
    expect(labels.some((l) => l.includes('已停用'))).toBe(true)
  })

  it('✅ 「全部」Tab 有 3 行；「已发布」Tab 只有 PUBLISHED 那行', async () => {
    const { container } = renderCatalog()
    await waitFor(() => expect(screen.getByText('tool-published')).toBeTruthy())
    const pane = container.querySelector<HTMLElement>('.ant-tabs-tabpane-active')!
    expect(within(pane).getByText('tool-draft')).toBeTruthy()
    expect(within(pane).getByText('tool-deprecated')).toBeTruthy()

    // 切到「已发布」：只剩 tool-published
    const publishedTab = Array.from(container.querySelectorAll('.ant-tabs-tab')).find((t) =>
      (t.textContent ?? '').includes('已发布'),
    )!
    fireEvent.click(publishedTab)
    await waitFor(() => {
      const active = container.querySelector<HTMLElement>('.ant-tabs-tabpane-active')!
      expect(within(active).getByText('tool-published')).toBeTruthy()
      expect(within(active).queryByText('tool-draft')).toBeNull()
      expect(within(active).queryByText('tool-deprecated')).toBeNull()
    })
  })

  it('✅ riskLevel 由 riskTag 渲染为 Tag', async () => {
    const { container } = renderCatalog()
    await waitFor(() => expect(screen.getAllByText('READ').length).toBeGreaterThan(0))
    expect(screen.getAllByText('WRITE_PROFILE').length).toBeGreaterThan(0)
    const tagTexts = Array.from(container.querySelectorAll('.ant-tag')).map((t) => t.textContent ?? '')
    expect(tagTexts).toContain('READ')
    expect(tagTexts).toContain('WRITE_PROFILE')
  })

  it('✅ kind 作为表格列呈现（不单独开 Tab）', async () => {
    renderCatalog()
    await waitFor(() => expect(screen.getAllByText('NATIVE').length).toBe(2))
    expect(screen.getAllByText('HTTP').length).toBeGreaterThan(0)
  })

  it('✅ 已发布行提供「停用」入口；非已发布行不出现', async () => {
    renderCatalog()
    await waitFor(() => expect(screen.getAllByText('tool-published').length).toBeGreaterThan(0))
    // ⚠️ antd 会给按钮汉字中间插空格（「停用」读成「停 用」）→ 用归一化正则匹配
    expect(screen.getAllByRole('button', { name: /停\s*用/ }).length).toBe(1)
  })

  it('✅ 页头有「登记 Tool」与「申请队列」按钮', async () => {
    renderCatalog()
    await waitFor(() => expect(screen.getByRole('button', { name: /登记 Tool/ })).toBeTruthy())
    expect(screen.getByRole('button', { name: /申请队列/ })).toBeTruthy()
  })

  it('✅ 双击行打开详情抽屉：只读详情取数，目录 list **不再**被调用', async () => {
    const { container } = renderCatalog()
    await waitFor(() => expect(screen.getByText('tool-published')).toBeTruthy())
    // 列表请求恰一次（渲染时一次；之后任何交互都不允许再打目录 list）
    expect(getConsoleToolCatalog).toHaveBeenCalledTimes(1)

    const cell = container.querySelector<HTMLElement>('td')! // 第一行的第一列（toolId）
    fireEvent.dblClick(cell)
    await waitFor(() => expect(screen.getByText('Tool 详情 · tool-published')).toBeTruthy())
    await waitFor(() => expect(screen.getByText('版本与生命周期')).toBeTruthy())
    // 关系区如实呈现「内置 Tool」三态
    await waitFor(() => expect(screen.getByText(/内置 Tool/)).toBeTruthy())
    // 详情取的是**单条 + 关系**，而不是把整个目录再拉一遍
    expect(getConsoleTool).toHaveBeenCalledWith('tool-published')
    expect(getConsoleToolRelations).toHaveBeenCalledWith('tool-published')
    expect(getConsoleToolCatalog).toHaveBeenCalledTimes(1)
  })

  it('✅ 行尾「详情」按钮 = 双击的等价入口（可发现性）', async () => {
    const { container } = renderCatalog()
    await waitFor(() => expect(screen.getByText('tool-draft')).toBeTruthy())
    expect(getConsoleToolCatalog).toHaveBeenCalledTimes(1)

    const rows = Array.from(container.querySelectorAll<HTMLElement>('.ant-table-row'))
    const draftRow = rows.find((r) => (r.textContent ?? '').includes('tool-draft'))!
    fireEvent.click(within(draftRow).getByRole('button', { name: /详\s*情/ }))
    await waitFor(() => expect(screen.getByText('Tool 详情 · tool-draft')).toBeTruthy())
    expect(getConsoleTool).toHaveBeenCalledWith('tool-draft')
    expect(getConsoleToolCatalog).toHaveBeenCalledTimes(1)
  })
})
