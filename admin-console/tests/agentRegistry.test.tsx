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
  // 运营代租户配置 Agent（写通道）
  createPlatformAgent: vi.fn(),
  submitPlatformAgentPublishRequest: vi.fn(),
  submitPlatformAgentLifecycleRequest: vi.fn(),
  listPlatformAgentRequests: vi.fn(),
  decidePlatformAgentPublishRequest: vi.fn(),
  applyPlatformAgentPublishRequest: vi.fn(),
  getPlatformAgentDefinition: vi.fn(),
  // 全局 Agent 与租户的多对多绑定（直接配置 CRUD）
  listPlatformAgentBindings: vi.fn(),
  listPlatformAgentTenants: vi.fn(),
  bindPlatformAgent: vi.fn(),
  updatePlatformAgentBinding: vi.fn(),
  unbindPlatformAgent: vi.fn(),
}))
import {
  getConsoleAgentRegistry,
  listPlatformAgentTenants,
  submitPlatformAgentLifecycleRequest,
  getPlatformAgentDefinition,
  unbindPlatformAgent,
} from '@/api/adminApi'
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
  vi.mocked(submitPlatformAgentLifecycleRequest).mockReset()
  vi.mocked(getPlatformAgentDefinition).mockReset()
  vi.mocked(listPlatformAgentTenants).mockReset()
  vi.mocked(unbindPlatformAgent).mockReset()
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

describe('平台控制台 · Agent 注册表 /agents（运营代配置 · 按租户分组）', () => {
  it('✅ 按租户分 Tabs：全部绑定 + 每个 tenantId 各一个 Tab', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getByText('alpha-1')).toBeTruthy())
    const labels = Array.from(container.querySelectorAll('.ant-tabs-tab')).map((t) => t.textContent ?? '')
    expect(labels.some((l) => l.includes('全部绑定'))).toBe(true)
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

  it('✅ 全局实体声明：页头声明全局 Agent + 多对多绑定，保留申请队列与新建入口', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    renderRegistry()
    await waitFor(() =>
      expect(
        screen.getByText(/Agent 为全局实体；租户通过配置绑定使用/),
      ).toBeTruthy(),
    )
    expect(screen.getByText(/同一 Agent 可分配给多个租户/)).toBeTruthy()
    // ⚠️ 写入口：新建 Agent + 申请队列必须可见
    expect(screen.getByRole('button', { name: /新建 Agent/ })).toBeTruthy()
    expect(screen.getByRole('button', { name: /申请队列/ })).toBeTruthy()
  })

  it('✅ 行尾写入口：每行有 编辑 / 分配租户 / 停用 / 详情；已停用行的「停用」被禁用', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getByText('beta-1')).toBeTruthy())
    const body = container.querySelector<HTMLElement>('.ant-table-tbody')!
    // 每行四个动作按钮（编辑 / 分配租户 / 停用 / 详情）
    const rowButtons = Array.from(body.querySelectorAll('tr'))
      .map((tr) =>
        Array.from(tr.querySelectorAll('td button')).map((b) => (b.textContent ?? '').replace(/\s+/g, '')),
      )
      .filter((btns) => btns.length > 0)
    expect(rowButtons).toHaveLength(ENTRIES.length)
    expect(rowButtons.every((btns) =>
      ['编辑', '分配租户', '停用', '详情'].every((t) => btns.includes(t)),
    )).toBe(true)
    // beta-1 已 DEPRECATED → 停用按钮禁用
    const betaRow = Array.from(body.querySelectorAll('tr')).find((tr) => tr.textContent?.includes('beta-1'))!
    const deprecateBtn = Array.from(betaRow.querySelectorAll('td button')).find((b) => b.textContent?.includes('停用'))!
    expect(deprecateBtn.hasAttribute('disabled')).toBe(true)
  })

  it('✅ 分配租户：读取该全局 Agent 的绑定列表，停用分配走直接配置 CRUD', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    vi.mocked(listPlatformAgentTenants).mockResolvedValue([
      {
        tenantId: 'tenant-alpha',
        agentId: 'alpha-1',
        status: 'ACTIVE',
        pinnedVersion: null,
        config: '{}',
        createdAt: '2026-10-20T00:00:00Z',
        updatedAt: '2026-10-20T00:00:00Z',
      },
    ] as never)
    vi.mocked(unbindPlatformAgent).mockResolvedValue(undefined as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getByText('alpha-1')).toBeTruthy())

    const firstRow = container.querySelector<HTMLElement>(
      '.ant-table-tbody tr:not(.ant-table-measure-row)',
    )!
    fireEvent.click(within(firstRow).getByRole('button', { name: /分配租户/ }))

    await waitFor(() => expect(listPlatformAgentTenants).toHaveBeenCalledWith('alpha-1'))
    const title = await screen.findByText('分配租户（Agent alpha-1）')
    const drawer = title.closest('.ant-drawer') as HTMLElement
    expect(within(drawer).getByText('tenant-alpha')).toBeTruthy()
    expect(within(drawer).getByText('跟随发布头')).toBeTruthy()

    fireEvent.click(within(drawer).getByRole('button', { name: /停用分配/ }))
    await waitFor(() =>
      expect(unbindPlatformAgent).toHaveBeenCalledWith('tenant-alpha', 'alpha-1'),
    )
  })

  it('✅ 双击行打开详情抽屉：内容来自行内字段，注册表**零新请求**', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getByText('alpha-1')).toBeTruthy())
    expect(getConsoleAgentRegistry).toHaveBeenCalledTimes(1)

    fireEvent.dblClick(container.querySelector<HTMLElement>('.ant-table-tbody td')!)
    // 抽屉标题 = 租户 :: agentId；关键字段与红线声明都在
    await waitFor(() => expect(screen.getByText('tenant-alpha :: alpha-1')).toBeTruthy())
    const drawer = screen.getByText('tenant-alpha :: alpha-1').closest('.ant-drawer') as HTMLElement
    // ⚠️ 同名文本（order-reader）也在列表行里 —— 字段断言须限定在抽屉内。
    expect(within(drawer).getByText('order-reader')).toBeTruthy()
    expect(within(drawer).getByText('跨租户只读盘点')).toBeTruthy()
    // ⚠️ 详情不发任何请求（无单条端点，渲染行内数据），更不允许把注册表 list 再打一遍
    expect(getConsoleAgentRegistry).toHaveBeenCalledTimes(1)
  })

  it('✅ 行尾「详情」按钮与双击等价', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getByText('beta-1')).toBeTruthy())
    fireEvent.click(within(container.querySelector<HTMLElement>('.ant-table-tbody')!).getAllByRole('button', { name: /详\s*情/ })[2]!)
    await waitFor(() => expect(screen.getByText('tenant-beta :: beta-1')).toBeTruthy())
  })

  it('✅ 停用（删除语义）：填理由提交 → 走平台链生命周期申请（DEPRECATED 终态），携带 tenantId / 版本号', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    vi.mocked(submitPlatformAgentLifecycleRequest).mockResolvedValue({ requestId: 'req-1' } as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getByText('alpha-1')).toBeTruthy())

    // 在第一行的行尾点「停用」
    const firstRow = container.querySelector<HTMLElement>('.ant-table-tbody tr:not(.ant-table-measure-row)')!
    fireEvent.click(within(firstRow).getByRole('button', { name: /停用/ }))
    // 弹窗出现：填变更理由
    const textarea = await screen.findByPlaceholderText('说明停用原因（必填）')
    fireEvent.change(textarea, { target: { value: '不再使用' } })
    fireEvent.click(screen.getByRole('button', { name: /提交停用申请/ }))

    await waitFor(() =>
      expect(submitPlatformAgentLifecycleRequest).toHaveBeenCalledWith(
        expect.objectContaining({
          tenantId: 'tenant-alpha',
          objectId: 'alpha-1',
          // alpha-1 defVersion=2 → 停用针对权威定义的当前版本
          version: 2,
          expectedFrom: 'PUBLISHED',
          reason: '不再使用',
        }),
      ),
    )
  })

  it('✅ 修改：点「编辑」先按 tenantId 取权威定义回填（取不到如实标记写入口可用）', async () => {
    vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
    vi.mocked(getPlatformAgentDefinition).mockResolvedValue({
      agentId: 'alpha-1',
      version: 2,
      name: '订单助手',
      roles: ['order-reader'],
      enabledSkills: [],
      skillBundles: [],
      capabilityTags: [],
      overrides: '{}',
      orchestration: '{}',
      instructions: '{}',
      generationParams: '{}',
      published: true,
    } as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getByText('alpha-1')).toBeTruthy())

    const firstRow = container.querySelector<HTMLElement>('.ant-table-tbody tr:not(.ant-table-measure-row)')!
    fireEvent.click(within(firstRow).getByRole('button', { name: /编辑/ }))
    await waitFor(() =>
      expect(getPlatformAgentDefinition).toHaveBeenCalledWith('tenant-alpha', 'alpha-1'),
    )
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
