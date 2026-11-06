// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'

// ---- mock ragClient：只桩掉会发请求的函数，档位判定用真实实现 ----
vi.mock('@/api/ragClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/ragClient')>()
  return {
    ...actual,
    listRagSources: vi.fn(async () => []),
    getRagStatistics: vi.fn(async () => null),
    getRagDailySearches: vi.fn(async () => ({ searchesByDay: [] })),
    getRagDailyImports: vi.fn(async () => ({ importsByDay: [] })),
    getRagIssues: vi.fn(async () => null),
  }
})

// ---- mock 票据通道：界面测试关心「有没有票→给不给写入口」，不关心真的签 ----
const TICKET = {
  accessToken: 'tk-1',
  tokenType: 'Bearer',
  scope: 'PLATFORM',
  platformUserId: '55555555-5555-5555-5555-555555555555',
  expiresAt: new Date(Date.now() + 300_000).toISOString(),
  jti: 'abcdef00-1111-2222-3333-444444444444',
}

vi.mock('@/api/ragTicket', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/ragTicket')>()
  let held: ReturnType<typeof actual.peekRagGovernanceTicket> = null
  return {
    ...actual,
    ensureRagGovernanceTicket: vi.fn(async () => {
      held = { ...TICKET, expiresAt: new Date(Date.now() + 300_000).toISOString() }
      return held
    }),
    clearRagGovernanceTicket: vi.fn(() => {
      held = null
    }),
    peekRagGovernanceTicket: vi.fn(() => held),
  }
})

vi.mock('@/api/adminApi', () => ({
  getPlatformTenants: vi.fn(async () => [
    { tenantId: '11111111-1111-1111-1111-111111111111', name: '示例租户', slug: 'demo' },
  ]),
}))

import {
  listRagSources,
  getRagStatistics,
  PLATFORM_TARGET,
  tenantTarget,
} from '@/api/ragClient'
import { ensureRagGovernanceTicket, clearRagGovernanceTicket } from '@/api/ragTicket'
import { RagManagementPage } from '@/pages/rag/RagManagementPage'

/**
 * RAG 管理页的三档作用域切换（RAG-D11 第四刀·第一、二刀）。
 *
 * ⚠️ 守的是「选错档位会不会看错数据 / 动错数据」：
 *    · 平台全局档必须**不带**租户去请求（带了后端 400）；
 *    · 平台全局档**没有治理票据时不得留写入口**（缺票后端 403，
 *      而让人点一次 403 是产品缺陷）；有票才放开登记／流转／重跑索引；
 *    · 换档必须**作废票据**并清空上一档的结果——把全局语料当成某租户的语料
 *      下治理结论，比空列表危险得多。
 */

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
  vi.mocked(listRagSources).mockClear()
  vi.mocked(getRagStatistics).mockClear()
  vi.mocked(ensureRagGovernanceTicket).mockClear()
  vi.mocked(clearRagGovernanceTicket).mockClear()
})

function pickScope(label: string): void {
  fireEvent.click(screen.getByText(label))
}

function registerButton(): HTMLButtonElement {
  return screen.getByRole('button', { name: '登记来源' }) as HTMLButtonElement
}

describe('RAG 管理页作用域分段控件', () => {
  it('✅ 平台数据优先：缺省档就是平台全局档，首屏按 PLATFORM_TARGET 发请求且不要求选租户', async () => {
    render(<RagManagementPage />)
    await waitFor(() => expect(screen.getByText('RAG 语料数据管理')).toBeTruthy())
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    expect(vi.mocked(listRagSources).mock.calls[0]?.[0]).toEqual(PLATFORM_TARGET)
    expect(vi.mocked(getRagStatistics).mock.calls[0]?.[0]).toEqual(PLATFORM_TARGET)
    // 平台档与租户身份互斥：租户选择器禁用，占位文案说明「不属于任何租户」
    expect(screen.getByText('平台全局语料不属于任何租户')).toBeTruthy()
  })

  it('✅ 切到本租户档且未选租户时不再发新 RAG 请求（配置问题不伪装成后端故障）', async () => {
    render(<RagManagementPage />)
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    vi.mocked(listRagSources).mockClear()
    vi.mocked(getRagStatistics).mockClear()

    pickScope('本租户语料')
    await waitFor(() =>
      expect(screen.getByText(/租户档未选择租户时不会发起任何 RAG 请求/)).toBeTruthy(),
    )
    expect(listRagSources).not.toHaveBeenCalled()
    expect(getRagStatistics).not.toHaveBeenCalled()
  })

  it('✅ 从租户档切回平台档：重新按平台档发请求', async () => {
    render(<RagManagementPage />)
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    pickScope('本租户语料')
    vi.mocked(listRagSources).mockClear()
    vi.mocked(getRagStatistics).mockClear()

    pickScope('平台全局语料')
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    expect(vi.mocked(listRagSources).mock.calls[0]?.[0]).toEqual(PLATFORM_TARGET)
    expect(vi.mocked(getRagStatistics).mock.calls[0]?.[0]).toEqual(PLATFORM_TARGET)
  })

  it('✅ 平台全局档**无票据**时不给写入口：登记按钮禁用并说明要领取票据', async () => {
    render(<RagManagementPage />)
    pickScope('平台全局语料')
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    expect(registerButton().disabled).toBe(true)
    expect(screen.getByText('当前无票据：本档只读')).toBeTruthy()
    // 自动取票**不发生**：签票要过权限闸门、受限流、且每次留审计，
    // 静默替管理员签一张等于把「显式的治理动作」变成后台噪音。
    expect(ensureRagGovernanceTicket).not.toHaveBeenCalled()
  })

  it('✅ 领取票据后写入口放开（登记按钮可点），且票据只存内存', async () => {
    render(<RagManagementPage />)
    pickScope('平台全局语料')
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: '领取治理票据' }))
    await waitFor(() => expect(registerButton().disabled).toBe(false))
    expect(ensureRagGovernanceTicket).toHaveBeenCalledTimes(1)
    // 票据只在内存：不出现在任何存储里
    expect(window.sessionStorage.length).toBe(0)
    expect(window.localStorage.length).toBe(0)
  })

  it('✅ 换档即作废票据（A7）：切回租户档再切回来，写入口重新变灰', async () => {
    render(<RagManagementPage />)
    pickScope('平台全局语料')
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: '领取治理票据' }))
    await waitFor(() => expect(registerButton().disabled).toBe(false))
    pickScope('本租户语料')
    await waitFor(() => expect(clearRagGovernanceTicket).toHaveBeenCalled())
    pickScope('平台全局语料')
    await waitFor(() => expect(registerButton().disabled).toBe(true))
  })

  it('✅ 平台全局档下租户选择器禁用（两种身份互斥，不给同时选的机会）', async () => {
    render(<RagManagementPage />)
    pickScope('平台全局语料')
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    expect(screen.getByText('平台全局语料不属于任何租户')).toBeTruthy()
  })

  it('✅ 领票据不重查列表：管理员设好的筛选条件不该被一次取票冲掉', async () => {
    render(<RagManagementPage />)
    pickScope('平台全局语料')
    await waitFor(() => expect(listRagSources).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: '领取治理票据' }))
    await waitFor(() => expect(registerButton().disabled).toBe(false))
    expect(listRagSources).toHaveBeenCalledTimes(1)
  })

  it('✅ 切到租户档并选定租户后按租户目标发请求（与本刀之前一致）', async () => {
    render(<RagManagementPage />)
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    pickScope('本租户语料')
    vi.mocked(listRagSources).mockClear()

    fireEvent.mouseDown(screen.getByText('选择租户（本档管理端点要求 X-Tenant-Id）'))
    const option = await screen.findByText('示例租户（demo）')
    fireEvent.click(option)
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    expect(vi.mocked(listRagSources).mock.calls[0]?.[0]).toEqual(
      tenantTarget('11111111-1111-1111-1111-111111111111'),
    )
  })

  it('✅ 跨租户盘点档位存在但禁用（第三刀，避免给了入口却只会 501）', () => {
    render(<RagManagementPage />)
    const item = screen.getByText('跨租户盘点').closest('.ant-segmented-item')
    expect(item?.className ?? '').toContain('ant-segmented-item-disabled')
  })
})
