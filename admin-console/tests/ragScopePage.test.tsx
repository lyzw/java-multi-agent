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
import { RagManagementPage } from '@/pages/rag/RagManagementPage'

/**
 * RAG 管理页的三档作用域切换（RAG-D11 第四刀·第一刀）。
 *
 * ⚠️ 守的是「选错档位会不会看错数据」：
 *    · 平台全局档必须**不带**租户去请求（带了后端 400）；
 *    · 平台全局档不得留写入口（后端会 409，但让人点到 409 是产品缺陷）；
 *    · 档位切换必须清空上一档的结果——把全局语料当成某租户的语料下治理结论，
 *      比空列表危险得多。
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
})

function pickScope(label: string): void {
  fireEvent.click(screen.getByText(label))
}

describe('RAG 管理页作用域分段控件', () => {
  it('✅ 未选租户时（缺省租户档）一条 RAG 请求都不发', async () => {
    render(<RagManagementPage />)
    await waitFor(() => expect(screen.getByText('RAG 语料数据管理')).toBeTruthy())
    expect(listRagSources).not.toHaveBeenCalled()
    expect(getRagStatistics).not.toHaveBeenCalled()
  })

  it('✅ 切到平台全局档：按平台档发请求，且不要求选租户', async () => {
    render(<RagManagementPage />)
    pickScope('平台全局语料')
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    expect(vi.mocked(listRagSources).mock.calls[0]?.[0]).toEqual(PLATFORM_TARGET)
    expect(vi.mocked(getRagStatistics).mock.calls[0]?.[0]).toEqual(PLATFORM_TARGET)
  })

  it('✅ 平台全局档不渲染写入口：登记按钮禁用并说明原因', async () => {
    render(<RagManagementPage />)
    pickScope('平台全局语料')
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    const register = screen.getByRole('button', { name: '登记来源' })
    expect((register as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText('平台全局语料：本档只读')).toBeTruthy()
  })

  it('✅ 平台全局档下租户选择器禁用（两种身份互斥，不给同时选的机会）', async () => {
    render(<RagManagementPage />)
    pickScope('平台全局语料')
    await waitFor(() => expect(listRagSources).toHaveBeenCalled())
    expect(screen.getByText('平台全局语料不属于任何租户')).toBeTruthy()
  })

  it('✅ 租户档选定租户后按租户目标发请求（与本刀之前一致）', async () => {
    render(<RagManagementPage />)
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
