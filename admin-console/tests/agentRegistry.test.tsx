// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

vi.mock('@/api/adminApi', () => ({
  getConsoleAgentRegistry: vi.fn(),
  getConsoleToolCatalog: vi.fn(),
  getConsoleSkillCatalog: vi.fn(),
  getConsoleMcpCatalog: vi.fn(),
  listPlatformCapabilityRequests: vi.fn(),
  submitPlatformPublishRequest: vi.fn(),
  decidePlatformPublishRequest: vi.fn(),
  applyPlatformPublishRequest: vi.fn(),
  submitPlatformLifecycleRequest: vi.fn(),
}))
import { getConsoleAgentRegistry } from '@/api/adminApi'
import { ApiError } from '@/api/client'

import { AgentRegistryPage } from '@/pages/console/AgentRegistryPage'

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

afterEach(() => {
  vi.mocked(getConsoleAgentRegistry).mockReset()
})

// ---- 夹具：两个租户各若干 Agent ----
const ENTRIES = [
  {
    tenantId: 'tenant-alpha',
    agentId: 'alpha-1',
    name: '订单助手',
    description: null,
    versionCount: 3,
    latestVersionNumber: 3,
    latestPublishedAt: '2026-10-20T00:00:00Z',
    defVersion: 2,
    lifecycleState: 'PUBLISHED',
    roles: ['order-reader'],
    enabledSkills: ['sk-1', 'sk-2'],
    capabilityTags: ['order'],
    hasOverrides: false,
  },
  {
    tenantId: 'tenant-alpha',
    agentId: 'alpha-2',
    name: null,
    description: null,
    versionCount: 1,
    latestVersionNumber: null,
    latestPublishedAt: null,
    defVersion: null,
    lifecycleState: null,
    roles: [],
    enabledSkills: [],
    capabilityTags: [],
    hasOverrides: false,
  },
  {
    tenantId: 'tenant-beta',
    agentId: 'beta-1',
    name: '客服助手',
    description: null,
    versionCount: 2,
    latestVersionNumber: 2,
    latestPublishedAt: '2026-10-21T00:00:00Z',
    defVersion: 1,
    lifecycleState: 'DEPRECATED',
    roles: ['support'],
    enabledSkills: ['sk-9'],
    capabilityTags: ['support'],
    hasOverrides: true,
  },
]

function renderRegistry() {
  return render(
    <MemoryRouter>
      <AntApp>
        <AgentRegistryPage />
      </AntApp>
    </MemoryRouter>,
  )
}

describe('平台控制台 · Agent 注册表 /agents（只读盘点 · 按租户分组）', () => {
  it('✅ 按租户分 Tabs：全部租户 + 每个 tenantId 各一个 Tab', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getByText('alpha-1')).toBeTruthy())
    const labels = Array.from(container.querySelectorAll('.ant-tabs-tab')).map((t) => t.textContent ?? '')
    expect(labels.some((l) => l.includes('全部租户'))).toBe(true)
    expect(labels.some((l) => l.includes('tenant-alpha'))).toBe(true)
    expect(labels.some((l) => l.includes('tenant-beta'))).toBe(true)
  })

  it('✅ 「全部租户」3 行；切到 tenant-beta 只剩该租户的行', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getByText('beta-1')).toBeTruthy())

    const betaTab = Array.from(container.querySelectorAll('.ant-tabs-tab')).find((t) =>
      (t.textContent ?? '').includes('tenant-beta'),
    )!
    fireEvent.click(betaTab)
    await waitFor(() => {
      const active = container.querySelector<HTMLElement>('.ant-tabs-tabpane-active')!
      expect(within(active).getByText('beta-1')).toBeTruthy()
      expect(within(active).queryByText('alpha-1')).toBeNull()
    })
  })

  it('✅ 只读盘点：无「登记」按钮；页头声明禁区红线', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    renderRegistry()
    await waitFor(() =>
      expect(
        screen.getByText(/只读盘点——仅含管理元数据，定义正文与运行输出不出平台边界/),
      ).toBeTruthy(),
    )
    expect(screen.getByText(/平台不提供 Agent 写端点（禁区）/)).toBeTruthy()
    // ⚠️ 注册表页**不得**有任何登记入口（平台不提供 Agent 写端点）
    expect(screen.queryByRole('button', { name: /登记/ })).toBeNull()
    // 也没有申请队列（它属于三步工作流页头，本页是纯只读页）
    expect(screen.queryByRole('button', { name: /申请队列/ })).toBeNull()
  })

  it('✅ 只读性质：整页无任何写入口按钮（停用/删除/编辑都不该有）', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getByText('beta-1')).toBeTruthy())
    const buttonTexts = Array.from(container.querySelectorAll('td button, .ant-card-extra button')).map(
      (b) => (b.textContent ?? '').replace(/\s+/g, ''),
    )
    expect(buttonTexts.filter((t) => t.length > 0)).toHaveLength(0)
  })

  it('⛔ 503（platform-ops 数据源未配置）→ 展示「平台运营会话窗未接入」Alert，跨租户盘点如实声明不可用', async () => {
    vi.mocked(getConsoleAgentRegistry).mockRejectedValue(
      new ApiError(503, 'platform-ops session not configured'),
    )
    renderRegistry()
    await waitFor(() =>
      expect(
        screen.getAllByText(/platform-ops 数据源未配置/).length,
      ).toBeGreaterThan(0),
    )
    expect(screen.getAllByText(/跨租户盘点不可用/).length).toBeGreaterThan(0)
  })

  it('⛔ 403 → 提示「权限不足（403）」，与 503 文案不同', async () => {
    vi.mocked(getConsoleAgentRegistry).mockRejectedValue(new ApiError(403, 'forbidden'))
    renderRegistry()
    await waitFor(() => expect(screen.getAllByText(/权限不足（403）/).length).toBeGreaterThan(0))
    expect(screen.queryAllByText(/platform-ops/)).toHaveLength(0)
  })
})
