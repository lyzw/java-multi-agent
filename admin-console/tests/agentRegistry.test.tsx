// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

vi.mock('@/api/adminApi', () => ({
  getConsoleAgentRegistry: vi.fn(),
  getConsoleAgentChanges: vi.fn(),
  getConsoleToolCatalog: vi.fn(),
  getConsoleSkillCatalog: vi.fn(),
  getConsoleMcpCatalog: vi.fn(),
  getConsoleTool: vi.fn(),
  getConsoleToolRelations: vi.fn(),
  getConsoleSkill: vi.fn(),
  getConsoleSkillRelations: vi.fn(),
  getConsoleMcp: vi.fn(),
  getConsoleMcpRelations: vi.fn(),
  listPlatformCapabilityRequests: vi.fn(),
  submitPlatformPublishRequest: vi.fn(),
  decidePlatformPublishRequest: vi.fn(),
  applyPlatformPublishRequest: vi.fn(),
  submitPlatformLifecycleRequest: vi.fn(),
  createPlatformAgent: vi.fn(),
  submitPlatformAgentPublishRequest: vi.fn(),
  submitPlatformAgentLifecycleRequest: vi.fn(),
  listPlatformAgentRequests: vi.fn(),
  decidePlatformAgentPublishRequest: vi.fn(),
  applyPlatformAgentPublishRequest: vi.fn(),
  getPlatformAgentDefinition: vi.fn(),
  getPlatformTenants: vi.fn(),
  listPlatformAgentBindings: vi.fn(),
  listPlatformAgentTenants: vi.fn(),
  bindPlatformAgent: vi.fn(),
  updatePlatformAgentBinding: vi.fn(),
  unbindPlatformAgent: vi.fn(),
}))
import {
  getConsoleAgentRegistry,
  getConsoleAgentChanges,
  getPlatformTenants,
  listPlatformAgentTenants,
  submitPlatformAgentLifecycleRequest,
  getPlatformAgentDefinition,
  unbindPlatformAgent,
} from '@/api/adminApi'
import { ApiError } from '@/api/client'

import { AgentRegistryPage } from '@/pages/console/AgentRegistryPage'

const TENANT_A = '11111111-1111-1111-1111-111111111111'
const TENANT_B = '22222222-2222-2222-2222-222222222222'
const AGENT_A = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const AGENT_B = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
const AGENT_C = 'cccccccc-cccc-cccc-cccc-cccccccccccc'

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
  vi.mocked(getConsoleAgentChanges).mockReset()
  vi.mocked(getPlatformTenants).mockReset()
  vi.mocked(listPlatformAgentTenants).mockReset()
  vi.mocked(submitPlatformAgentLifecycleRequest).mockReset()
  vi.mocked(getPlatformAgentDefinition).mockReset()
  vi.mocked(unbindPlatformAgent).mockReset()
})

const ENTRIES = [
  {
    tenantId: TENANT_A,
    agentId: AGENT_A,
    name: '订单助手',
    description: '负责订单查询',
    versionCount: 3,
    latestVersionNumber: 3,
    latestPublishedAt: '2026-10-20T00:00:00Z',
    defVersion: 2,
    lifecycleState: 'PUBLISHED',
    roles: ['order-reader'],
    enabledSkills: ['sk-1'],
    capabilityTags: ['order'],
    hasOverrides: false,
    pendingState: 'PENDING',
    pendingAt: '2026-11-21T10:00:00Z',
  },
  {
    tenantId: TENANT_A,
    agentId: AGENT_B,
    name: null,
    description: null,
    versionCount: 0,
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
    tenantId: TENANT_B,
    agentId: AGENT_C,
    name: '客服助手',
    description: null,
    versionCount: 2,
    latestVersionNumber: 2,
    latestPublishedAt: '2026-10-21T00:00:00Z',
    defVersion: 1,
    lifecycleState: 'DEPRECATED',
    roles: ['support'],
    enabledSkills: [],
    capabilityTags: ['support'],
    hasOverrides: true,
  },
]

const TENANTS = [
  {
    tenantId: TENANT_A, slug: 'alpha', name: '订单租户', lifecycleState: 'ACTIVE',
    plan: null, effectivePolicyVersion: 1, tenantAuthzVersion: 1,
  },
  {
    tenantId: TENANT_B, slug: 'beta', name: '客服租户', lifecycleState: 'ACTIVE',
    plan: null, effectivePolicyVersion: 1, tenantAuthzVersion: 1,
  },
]

function stubDefaults() {
  vi.mocked(getConsoleAgentRegistry).mockResolvedValue(ENTRIES as never)
  vi.mocked(getPlatformTenants).mockResolvedValue(TENANTS as never)
  vi.mocked(getConsoleAgentChanges).mockResolvedValue([
    {
      changeId: 'req-1', category: 'CONFIG', tenantId: TENANT_A,
      action: 'PUBLISH', state: 'PENDING', targetVersion: 3,
      reason: '补充订单字段', actorId: 'operator-1',
      requestedAt: '2026-11-21T10:00:00Z', appliedAt: null,
    },
    {
      changeId: 'req-2', category: 'AUDIT', tenantId: TENANT_A,
      action: 'agent_config.binding.bind', state: 'APPLIED', targetVersion: null,
      reason: `agent_config.binding.bind tenant=${TENANT_A}`, actorId: 'operator-2',
      requestedAt: '2026-11-20T10:00:00Z', appliedAt: '2026-11-20T10:00:00Z',
    },
  ] as never)
}

function renderRegistry() {
  return render(
    <MemoryRouter>
      <AntApp>
        <AgentRegistryPage />
      </AntApp>
    </MemoryRouter>,
  )
}

describe('平台控制台 · Agent 注册表（中文优先 · 跨租户 · 运营配置）', () => {
  it('✅ 列表用中文名称而非 UUID；租户筛选显示租户名', async () => {
    stubDefaults()
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getAllByText('订单助手').length).toBeGreaterThan(0))
    expect(screen.getAllByText('客服助手').length).toBeGreaterThan(0)
    expect(screen.getAllByText('未命名 Agent').length).toBeGreaterThan(0)
    expect(screen.queryByText(AGENT_A)).toBeNull()
    expect(screen.queryByText(AGENT_B)).toBeNull()

    // 租户下拉的选项以中文名呈现（Select 选项在 portal 中，需先展开）
    const selectors = container.querySelectorAll('.ant-select-selector')
    fireEvent.mouseDown(selectors[1]!)
    await waitFor(() => {
      const options = Array.from(document.querySelectorAll('.ant-select-item-option-content'))
        .map((el) => el.textContent ?? '')
      expect(options).toContain('订单租户')
      expect(options).not.toContain(TENANT_A)
    })
  })

  it('✅ 跨租户全量盘点口径显式标注：租户/Agent 计数 + 本地过滤不重新请求（改造点 2-1-1）', async () => {
    stubDefaults()
    renderRegistry()
    await waitFor(() => expect(screen.getAllByText('订单助手').length).toBeGreaterThan(0))
    // 顶部 Alert 必须写明「跨租户全量盘点」口径与覆盖数量，防止误读为本租户视图
    expect(screen.getAllByText(/跨租户全量盘点/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/个已绑定租户/).length).toBeGreaterThan(0)
    // 筛选语义：本地过滤同一份数据，不重新请求平台链
    expect(screen.getAllByText(/不重新请求平台链/).length).toBeGreaterThan(0)
  })

  it('✅ 已修改未发布：列表标红字标签，统计卡计数包含该 Agent', async () => {
    stubDefaults()
    renderRegistry()
    await waitFor(() => expect(screen.getAllByText('已修改未发布').length).toBeGreaterThan(0))
    // 统计卡「已修改未发布」的值为 1
    const title = screen.getAllByText('已修改未发布')
      .find((el) => el.classList.contains('ant-statistic-title'))!
    const card = title.closest('.ant-card')!
    expect(within(card as HTMLElement).getByText('1')).toBeTruthy()
  })

  it('✅ 行尾写入口齐全；已停用行的「停用」被禁用', async () => {
    stubDefaults()
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getAllByText('订单助手').length).toBeGreaterThan(0))
    const body = container.querySelector<HTMLElement>('.ant-table-tbody')!
    const rows = Array.from(body.querySelectorAll('tr')).filter((tr) => tr.textContent?.includes('客服助手'))
    expect(rows.length).toBeGreaterThan(0)
    const buttons = Array.from(rows[0]!.querySelectorAll('td button'))
      .map((b) => (b.textContent ?? '').replace(/\s+/g, ''))
    expect(buttons).toContain('编辑')
    expect(buttons).toContain('分配租户')
    expect(buttons).toContain('详情')
    const deprecate = Array.from(rows[0]!.querySelectorAll('td button'))
      .find((b) => (b.textContent ?? '').includes('停用'))!
    expect(deprecate.hasAttribute('disabled')).toBe(true)
  })

  it('✅ 分配租户：抽屉标题与表格用租户名，停用分配仍按 tenantId 调用', async () => {
    stubDefaults()
    vi.mocked(listPlatformAgentTenants).mockResolvedValue([
      {
        tenantId: TENANT_A, agentId: AGENT_A, status: 'ACTIVE', pinnedVersion: null,
        config: '{}', createdAt: '2026-10-20T00:00:00Z', updatedAt: '2026-10-20T00:00:00Z',
      },
    ] as never)
    vi.mocked(unbindPlatformAgent).mockResolvedValue(undefined as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getAllByText('订单助手').length).toBeGreaterThan(0))
    const firstRow = container.querySelector<HTMLElement>('.ant-table-tbody tr:not(.ant-table-measure-row)')!
    fireEvent.click(within(firstRow).getByRole('button', { name: /分配租户/ }))
    const title = await screen.findByText('分配租户（Agent 订单助手）')
    const drawer = title.closest('.ant-drawer') as HTMLElement
    await waitFor(() => expect(within(drawer).getAllByText('订单租户').length).toBeGreaterThan(0))
    fireEvent.click(within(drawer).getByRole('button', { name: /停用分配/ }))
    await waitFor(() => expect(unbindPlatformAgent).toHaveBeenCalledWith(TENANT_A, AGENT_A))
  })

  it('✅ 详情：显示「租户名 - UUID」、历史变更记录中文动作/理由，且只发一次历史请求', async () => {
    stubDefaults()
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getAllByText('订单助手').length).toBeGreaterThan(0))
    const row = Array.from(container.querySelectorAll('.ant-table-tbody tr'))
      .find((tr) => tr.textContent?.includes('订单助手'))!
    fireEvent.click(within(row as HTMLElement).getByRole('button', { name: /详情/ }))
    await waitFor(() => expect(screen.getByText('历史变更记录（最近 20 条）')).toBeTruthy())
    const drawer = screen.getByText('历史变更记录（最近 20 条）').closest('.ant-drawer') as HTMLElement
    expect(within(drawer).getByText(`订单租户 - ${TENANT_A}`)).toBeTruthy()
    expect(within(drawer).getByText('已修改未发布（待审批）')).toBeTruthy()
    expect(within(drawer).getByText('定义发布')).toBeTruthy()
    expect(within(drawer).getByText('分配租户')).toBeTruthy()
    expect(within(drawer).getByText('补充订单字段')).toBeTruthy()
    expect(getConsoleAgentChanges).toHaveBeenCalledWith(AGENT_A, 20)
    expect(getConsoleAgentChanges).toHaveBeenCalledTimes(1)
  })

  it('✅ 停用（删除语义）：填理由提交 → 走平台链生命周期申请', async () => {
    stubDefaults()
    vi.mocked(submitPlatformAgentLifecycleRequest).mockResolvedValue({ requestId: 'req-1' } as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getAllByText('订单助手').length).toBeGreaterThan(0))
    const row = container.querySelector<HTMLElement>('.ant-table-tbody tr:not(.ant-table-measure-row)')!
    fireEvent.click(within(row).getByRole('button', { name: /停用/ }))
    const textarea = await screen.findByPlaceholderText('说明停用原因（必填）')
    fireEvent.change(textarea, { target: { value: '不再使用' } })
    fireEvent.click(screen.getByRole('button', { name: /提交停用申请/ }))
    await waitFor(() =>
      expect(submitPlatformAgentLifecycleRequest).toHaveBeenCalledWith(
        expect.objectContaining({
          tenantId: TENANT_A,
          objectId: AGENT_A,
          version: 2,
          expectedFrom: 'PUBLISHED',
          reason: '不再使用',
        }),
      ),
    )
  })

  it('✅ 修改：先按 tenantId 取权威定义回填', async () => {
    stubDefaults()
    vi.mocked(getPlatformAgentDefinition).mockResolvedValue({
      agentId: AGENT_A, version: 2, name: '订单助手', roles: ['order-reader'],
      enabledSkills: [], skillBundles: [], capabilityTags: [], overrides: '{}',
      orchestration: '{}', instructions: '{}', generationParams: '{}', published: true,
    } as never)
    const { container } = renderRegistry()
    await waitFor(() => expect(screen.getAllByText('订单助手').length).toBeGreaterThan(0))
    const row = container.querySelector<HTMLElement>('.ant-table-tbody tr:not(.ant-table-measure-row)')!
    fireEvent.click(within(row).getByRole('button', { name: /编辑/ }))
    await waitFor(() => expect(getPlatformAgentDefinition).toHaveBeenCalledWith(TENANT_A, AGENT_A))
  })

  it('⛔ 503 → platform-ops 未接入文案；403 → 权限不足文案', async () => {
    vi.mocked(getPlatformTenants).mockResolvedValue([] as never)
    vi.mocked(getConsoleAgentRegistry).mockRejectedValue(
      new ApiError(503, 'platform-ops session not configured'),
    )
    renderRegistry()
    await waitFor(() =>
      expect(screen.getAllByText(/platform-ops 数据源未配置/).length).toBeGreaterThan(0),
    )
    expect(screen.getAllByText(/跨租户盘点不可用/).length).toBeGreaterThan(0)

    vi.mocked(getConsoleAgentRegistry).mockRejectedValue(new ApiError(403, 'forbidden'))
    renderRegistry()
    await waitFor(() => expect(screen.getAllByText(/权限不足（403）/).length).toBeGreaterThan(0))
  })
})
