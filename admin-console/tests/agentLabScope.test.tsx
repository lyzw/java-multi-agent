// @vitest-environment jsdom
/**
 * AI 实验室 · 执行租户松绑改造的范围纪律测试（批次 1 / T1-1 ~ T1-8）。
 *
 * ⚠️ 这些断言守的是**作用域语义**而不是渲染细节：
 *    · 执行租户是独立显式输入（票证租户），不再由被测 Agent 反推；
 *    · 「选 Agent」不是页面准入门槛，只是发起执行的参数之一；
 *    · 跨租户错配必须在**前端发起前**被拦截（T1-6 是本批次核心回归断言）；
 *    · 换执行租户即作废旧票（旧票属于上一个租户，复用即串租户）；
 *    · 票证只在内存，绝不落浏览器存储。
 *
 * 全部走真实 `@/api/client` + `@/api/executionTicketApi` + 假 fetch；
 * 只 mock 数据源 `@/api/adminApi`（租户清单 / Agent 注册表）。
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

const TENANT_A = '3fa85f64-5717-4562-b3fc-2c963f66afa6'
const TENANT_B = 'a1111111-1111-1111-1111-111111111111'
const TENANT_TOKEN = 'tenant-jwt-from-ticket'

interface RegistryRow {
  agentId: string
  name: string | null
  tenantId: string
  [k: string]: unknown
}

/** 供个别用例注入「过滤租户 ≠ 行租户」的脏数据（模拟后端漂移 / 过滤器被清空后的存量选择）。 */
const registryState = vi.hoisted(() => ({ rowsOverride: null as RegistryRow[] | null }))

vi.mock('@/api/adminApi', () => ({
  getPlatformTenants: vi.fn(async () => [
    { tenantId: TENANT_A, name: '租户A', slug: 'ta' },
    { tenantId: TENANT_B, name: '租户B', slug: 'tb' },
  ]),
  getConsoleAgentRegistry: vi.fn(async (params?: { tenantId?: string }) => {
    const rows: RegistryRow[] = registryState.rowsOverride ?? [
      {
        agentId: 'agent-1',
        name: '助手一',
        tenantId: TENANT_A,
        latestVersionNumber: 3,
        lifecycleState: 'PUBLISHED',
        defVersion: 3,
        enabledSkills: [],
        roles: [],
        capabilityTags: [],
        versionCount: 3,
        pendingState: null,
      },
      {
        agentId: 'agent-2',
        name: '助手二',
        tenantId: TENANT_B,
        latestVersionNumber: 1,
        lifecycleState: 'PUBLISHED',
        defVersion: 1,
        enabledSkills: [],
        roles: [],
        capabilityTags: [],
        versionCount: 1,
        pendingState: null,
      },
    ]
    // ⚠️ override 语义 = 后端漂移 / 存量选择：原样返回，不再按租户过滤
    if (registryState.rowsOverride !== null) return rows
    return params?.tenantId
      ? rows.filter((r) => r.tenantId === params.tenantId)
      : rows
  }),
}))

import { AgentLabPage } from '@/pages/agentlab/AgentLabPage'
import { getConsoleAgentRegistry } from '@/api/adminApi'
import { peekExecutionTicket, clearExecutionTicket } from '@/api/executionTicketApi'

/** 每次出网请求的记录（URL / 方法 / Authorization），按序累积。 */
let sent: { url: string; method: string; authorization: string | null }[]
let mintCount = 0

function ticketJson(tenantId: string, loginName: string) {
  return {
    accessToken: TENANT_TOKEN,
    tokenType: 'Bearer',
    tenantId,
    principalId: 'b2222222-2222-2222-2222-222222222222',
    loginName,
    roles: ['AGENT_AUTHOR'],
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
    jti: `jti-${mintCount}`,
  }
}

function bodyFor(url: string, requestBody?: unknown): unknown {
  if (url.endsWith('/execution-ticket')) {
    mintCount += 1
    const tenantId = decodeURIComponent(url.split('/tenants/')[1]?.split('/')[0] ?? '')
    const loginName = String(JSON.parse(String(requestBody ?? '{}'))['loginName'] ?? 'unknown')
    return ticketJson(tenantId, loginName)
  }
  if (url.endsWith('/principals')) {
    const tenantId = decodeURIComponent(url.split('/tenants/')[1]?.split('/')[0] ?? '')
    return [
      {
        principalId: 'b2222222-2222-2222-2222-222222222222',
        loginName: 'stu-01',
        displayName: '学生一',
        status: 'ACTIVE',
        roles: ['AGENT_AUTHOR'],
        studentId: 'stu_001',
      },
      // ⚠️ 混一个非 ACTIVE 成员：页面必须过滤掉（后端只对 ACTIVE 签票）
      {
        principalId: 'c3333333-3333-3333-3333-333333333333',
        loginName: 'stu-suspended',
        displayName: null,
        status: 'SUSPENDED',
        roles: [],
      },
    ].filter(() => tenantId !== '')
  }
  if (url.includes('/runs/')) {
    return {
      runId: 'r-1',
      state: 'QUEUED',
      createdAt: new Date().toISOString(),
      completedAt: null,
      attempt: 0,
      errorCode: null,
    }
  }
  return { runId: 'r-1', replayed: false, state: 'QUEUED', correlationId: 'c-1' }
}

beforeEach(() => {
  mintCount = 0
  sent = []
  registryState.rowsOverride = null
  clearExecutionTicket()

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const headers = new Headers(init?.headers)
    sent.push({ url, method: init?.method ?? 'GET', authorization: headers.get('Authorization') })
    return {
      ok: true,
      status: url.endsWith('/api/runs') ? 201 : 200,
      async text() {
        return JSON.stringify(bodyFor(url, init?.body))
      },
    } as unknown as Response
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  cleanup()
  clearExecutionTicket()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

function renderPage(): void {
  render(
    <MemoryRouter>
      <AntApp>
        <AgentLabPage />
      </AntApp>
    </MemoryRouter>,
  )
}

/** antd v5 Select：按 combobox 序号展开（0=执行租户 1=Agent 2=租户身份），点选 portal 选项。 */
async function pick(comboboxIndex: number, label: string | RegExp): Promise<void> {
  const combobox = screen.getAllByRole('combobox')[comboboxIndex]!
  fireEvent.mouseDown(combobox, { button: 0 })
  const option = await screen.findByText(label, { selector: '.ant-select-item-option-content' })
  fireEvent.click(option)
}

function executeButton(): HTMLButtonElement {
  const button = screen.getByText('发起执行').closest('button')
  expect(button).not.toBeNull()
  return button as HTMLButtonElement
}

async function prepareExecutable(): Promise<void> {
  renderPage()
  // 等首次注册表（无过滤）加载完
  await waitFor(() => expect(vi.mocked(getConsoleAgentRegistry).mock.calls.length).toBeGreaterThan(0))
  await pick(0, '租户A（ta）')
  // 换执行租户后注册表按租户过滤，等过滤请求真的发出去
  await waitFor(() => {
    expect(
      vi.mocked(getConsoleAgentRegistry).mock.calls.some(
        (c) => (c[0] as { tenantId?: string } | undefined)?.tenantId === TENANT_A,
      ),
    ).toBe(true)
  })
  await pick(1, /助手一/)
  // 租户成员按执行租户加载（出网真实请求）
  await waitFor(() => {
    expect(sent.some((c) => c.url.endsWith(`/tenants/${TENANT_A}/principals`))).toBe(true)
  })
  await pick(2, /stu-01/)
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '一元二次方程怎么算' } })
  await waitFor(() => expect(executeButton().disabled).toBe(false))
}

describe('AI 实验室 · 执行租户松绑（批次 1）', () => {
  it('T1-1 · 未选执行租户时「发起执行」禁用', async () => {
    renderPage()
    await waitFor(() => expect(vi.mocked(getConsoleAgentRegistry).mock.calls.length).toBeGreaterThan(0))
    expect(executeButton().disabled).toBe(true)
  })

  it('T1-2 · 已选执行租户、未选 Agent 时仍禁用（agentId 必填红线保留）', async () => {
    renderPage()
    await waitFor(() => expect(vi.mocked(getConsoleAgentRegistry).mock.calls.length).toBeGreaterThan(0))
    await pick(0, '租户A（ta）')
    await waitFor(() => expect(executeButton().disabled).toBe(true))
    // 未选 Agent、未选身份：禁用原因必须能从 Tooltip 读出来
    expect(screen.getByText('发起执行').closest('span')).not.toBeNull()
  })

  it('T1-3 · 未选执行租户时「租户身份（取票）」禁用，且文案指向执行租户', async () => {
    renderPage()
    await waitFor(() => expect(vi.mocked(getConsoleAgentRegistry).mock.calls.length).toBeGreaterThan(0))
    const identity = screen.getAllByRole('combobox')[2]!
    expect((identity as HTMLInputElement).disabled || identity.closest('.ant-select-disabled'))
      .not.toBeNull()
    expect(screen.getAllByText(/先选择执行租户/).length).toBeGreaterThan(0)
    // 旧文案「先选择 Agent：票证绑到该 Agent 所属租户」的语义已废，不得回归
    expect(screen.queryAllByText(/票证绑到该 Agent 所属租户/)).toHaveLength(0)
  })

  it('T1-4 · 换执行租户后旧票立即作废（peekExecutionTicket 为 null）', async () => {
    await prepareExecutable()
    fireEvent.click(executeButton())
    // 创建成功后票证在内存
    await waitFor(() => {
      expect(sent.some((c) => c.url.endsWith('/api/runs'))).toBe(true)
    })
    expect(peekExecutionTicket()).not.toBeNull()
    // 换租户 → 旧票必须立刻作废
    await pick(0, '租户B（tb）')
    expect(peekExecutionTicket()).toBeNull()
  })

  it('T1-5 · 换执行租户后重新加载该租户成员', async () => {
    await prepareExecutable()
    await pick(0, '租户B（tb）')
    await waitFor(() => {
      expect(sent.some((c) => c.url.endsWith(`/tenants/${TENANT_B}/principals`))).toBe(true)
    })
  })

  it('T1-6 · 跨租户错配被前端拦截：不发 /api/runs，错误文案含两个租户 id', async () => {
    // 脏数据：执行租户 = A，注册表却返回了 B 租户的 Agent（模拟过滤器清空后的存量选择 / 后端漂移）
    registryState.rowsOverride = [
      {
        agentId: 'agent-2',
        name: '助手二',
        tenantId: TENANT_B,
        latestVersionNumber: 1,
        lifecycleState: 'PUBLISHED',
        defVersion: 1,
        enabledSkills: [],
        roles: [],
        capabilityTags: [],
        versionCount: 1,
        pendingState: null,
      },
    ]
    renderPage()
    await waitFor(() => expect(vi.mocked(getConsoleAgentRegistry).mock.calls.length).toBeGreaterThan(0))
    await pick(0, '租户A（ta）')
    await pick(1, /助手二/)
    await waitFor(() => {
      expect(sent.some((c) => c.url.endsWith(`/tenants/${TENANT_A}/principals`))).toBe(true)
    })
    await pick(2, /stu-01/)
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'q' } })
    await waitFor(() => expect(executeButton().disabled).toBe(false))

    fireEvent.click(executeButton())

    // ⚠️ 核心回归断言：错配在前端被拦，/api/runs 一次都不发
    await waitFor(() => {
      expect(screen.getAllByText(/与执行租户 .* 不一致/).length).toBeGreaterThan(0)
    })
    expect(screen.getAllByText(new RegExp(TENANT_B)).length).toBeGreaterThan(0)
    expect(screen.getAllByText(new RegExp(TENANT_A)).length).toBeGreaterThan(0)
    expect(sent.some((c) => c.url.endsWith('/api/runs'))).toBe(false)
  })

  it('T1-7 · 正常路径发出 /api/runs 且带票证头（Bearer 租户令牌）', async () => {
    await prepareExecutable()
    fireEvent.click(executeButton())
    await waitFor(() => {
      expect(sent.some((c) => c.url.endsWith('/api/runs'))).toBe(true)
    })
    const runCall = sent.find((c) => c.url.endsWith('/api/runs') && c.method === 'POST')
    expect(runCall).toBeDefined()
    expect(runCall?.authorization).toBe(`Bearer ${TENANT_TOKEN}`)
  })

  it('T1-8 · 票证不落浏览器存储', async () => {
    await prepareExecutable()
    fireEvent.click(executeButton())
    await waitFor(() => {
      expect(peekExecutionTicket()).not.toBeNull()
    })
    for (const storage of [window.sessionStorage, window.localStorage]) {
      for (let i = 0; i < storage.length; i++) {
        const key = storage.key(i) ?? ''
        expect(key.toLowerCase()).not.toContain('ticket')
        expect(String(storage.getItem(key))).not.toContain(TENANT_TOKEN)
      }
    }
  })
})
