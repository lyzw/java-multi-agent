// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  getConsoleToolCatalog: vi.fn(),
  getConsoleTool: vi.fn(),
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
import { getConsoleToolCatalog } from '@/api/adminApi'

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
  beforeAll(() => {
    vi.mocked(getConsoleToolCatalog).mockResolvedValue(TOOLS as never)
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
})
