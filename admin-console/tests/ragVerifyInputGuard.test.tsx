// @vitest-environment jsdom
/**
 * 校验决定页「来源 ID / 复核人」输入守卫。
 *
 * ⚠️ 为什么要真挂载页面测：
 *   2026-11-10 页面端导入时发现，粘贴 36 位来源 UUID 会被 `useEffect([sourceId])`
 *   拆成 30+ 次 partial UUID 请求；后端 `?::uuid` 转换失败 → 连续 HTTP 500 刷屏，
 *   页面在最后一次成功前就被错误态淹没。这类缺陷类型检查查不出，
 *   纯函数单测也覆盖不到，必须从页面输入口进。
 *
 * 同时守住双人复核的降级路径：名录为空时必须出现手输 UUID 输入框，
 * 不能是一个空下拉把整条治理链卡死。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router-dom'

import type { RagSource } from '@/api/ragTypes'

const getRagSourceMock = vi.hoisted(() => vi.fn())
const listCoSignersMock = vi.hoisted(() => vi.fn())
const verifyMock = vi.hoisted(() => vi.fn())
const resetActionMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/ragClient', async (importActual) => {
  const actual = await importActual<Record<string, unknown>>()
  return {
    ...actual,
    isRagTargetReady: () => true,
    tenantTarget: (tenantId: string) => ({ scope: 'TENANT' as const, tenantId }),
    platformTarget: () => ({ scope: 'PLATFORM' as const }),
    getRagSource: getRagSourceMock,
    listRagCoSigners: listCoSignersMock,
  }
})

vi.mock('@/api/adminApi', async (importActual) => {
  const actual = await importActual<Record<string, unknown>>()
  return { ...actual, getPlatformTenants: vi.fn(async () => []) }
})

vi.mock('@/features/rag/useRagGovernanceTicket', () => ({
  useRagGovernanceTicket: () => ({
    ticket: null,
    status: 'idle' as const,
    message: null,
    remainingSeconds: 0,
    obtain: vi.fn(),
    revoke: vi.fn(),
  }),
}))

vi.mock('@/features/rag/useRagManagement', async (importActual) => {
  const actual = await importActual<Record<string, unknown>>()
  return {
    ...actual,
    useRagManagement: () => ({
      action: { status: 'idle', message: null, updated: null },
      verify: verifyMock,
      resetAction: resetActionMock,
    }),
  }
})

import { RagVerifyPage } from '@/pages/rag/RagVerifyPage'

const SOURCE_ID = '76d15916-7d33-4474-a1e1-f94c77c06001'

const SOURCE: RagSource = {
  sourceId: SOURCE_ID,
  sourceType: 'TEXTBOOK',
  title: '义务教育教科书（五·四学制） 体育与健康 六年级 全一册',
  publisher: '沪教版',
  isbn: '9787572030031',
  doi: null,
  trustTier: 'UNVERIFIED',
  verificationStatus: 'PENDING',
  verificationMethod: 'NONE',
  verificationEvidence: null,
  verifiedAt: null,
  verifiedBy: null,
  validDays: 365,
  licenseStatus: 'PENDING',
  stale: true,
  admissible: false,
  objectRef: null,
  contentHash: null,
  createdAt: '2026-11-10T11:51:24Z',
  updatedAt: '2026-11-10T11:51:24Z',
  inspectionStatus: 'NOT_REQUIRED',
  documentCount: 2,
}

async function renderPage(): Promise<void> {
  await act(async () => {
    render(
      <App>
        <MemoryRouter>
          <RagVerifyPage />
        </MemoryRouter>
      </App>,
    )
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  getRagSourceMock.mockResolvedValue(SOURCE)
  listCoSignersMock.mockResolvedValue([])
})

afterEach(() => {
  vi.useRealTimers()
})

describe('校验决定页输入守卫', () => {
  it('partial UUID 不发后端请求；完整 UUID 防抖后只读取一次', async () => {
    vi.useFakeTimers()
    await renderPage()

    const input = screen.getByPlaceholderText(/粘贴来源 UUID/)
    // antd 会在两个汉字间插入空格：可访问名是「读 取」
    const readButton = screen.getByRole('button', { name: /读\s*取/ })

    // 模拟逐字符输入（旧实现在这里产生 35 次 500）
    const partials = [
      '7',
      '76d15916',
      '76d15916-7d33',
      '76d15916-7d33-4474-a1e1-f94c77c0600', // 少一位：真实事故形态
    ]
    for (const value of partials) {
      fireEvent.change(input, { target: { value } })
      await act(async () => {
        vi.advanceTimersByTime(500)
      })
      expect(getRagSourceMock, `partial「${value}」不应触发读取`).not.toHaveBeenCalled()
      expect(listCoSignersMock, `partial「${value}」不应触发复核人名录`).not.toHaveBeenCalled()
      expect(readButton).toBeDisabled()
    }

    fireEvent.change(input, { target: { value: SOURCE_ID } })
    expect(readButton).not.toBeDisabled()
    await act(async () => {
      vi.advanceTimersByTime(300)
      await Promise.resolve()
    })

    expect(getRagSourceMock).toHaveBeenCalledTimes(1)
    expect(getRagSourceMock).toHaveBeenCalledWith({ scope: 'PLATFORM' }, SOURCE_ID)
    expect(listCoSignersMock).toHaveBeenCalledTimes(1)
  })

  it('复核人名录为空时降级为手输 UUID，而不是空下拉卡死签发', async () => {
    await renderPage()
    fireEvent.change(screen.getByPlaceholderText(/粘贴来源 UUID/), {
      target: { value: SOURCE_ID },
    })

    await waitFor(() => {
      expect(
        screen.getByPlaceholderText(/第二位复核人的主体 UUID/),
      ).toBeInTheDocument()
    })
    expect(screen.queryByPlaceholderText(/选择第二位签署人/)).toBeNull()
  })

  it('VERIFIED 只暴露可放行的版权状态，并预填已确认的合理引用', async () => {
    await renderPage()
    fireEvent.change(screen.getByPlaceholderText(/粘贴来源 UUID/), {
      target: { value: SOURCE_ID },
    })

    await waitFor(() => {
      expect(screen.getByText(/版权声明（必填）/)).toBeInTheDocument()
    })
    // ⚠️ 登记态 PENDING / 禁止态 RESTRICTED 绝不能出现在 VERIFIED 的选项里，
    //    否则「选了再被 400 拒」会让人误以为平台自相矛盾。
    expect(screen.getByText('FAIR_USE_QUOTATION · 合理引用')).toBeInTheDocument()
    expect(screen.queryByText('PENDING · 未取得授权')).toBeNull()
    expect(screen.queryByText('RESTRICTED · 禁止使用')).toBeNull()
  })

  it('VERIFIED 提交必须携带显式版权声明，否则按钮不可用', async () => {
    await renderPage()
    fireEvent.change(screen.getByPlaceholderText(/粘贴来源 UUID/), {
      target: { value: SOURCE_ID },
    })
    await waitFor(() => {
      expect(screen.getByText(/版权声明（必填）/)).toBeInTheDocument()
    })

    const evidence = screen.getByPlaceholderText(/已对照出版社授权电子版/)
    const coSigner = screen.getByPlaceholderText(/第二位复核人的主体 UUID/)
    fireEvent.change(evidence, {
      target: { value: '合理引用：已按版权页与教学引用范围核对' },
    })
    fireEvent.change(coSigner, {
      target: { value: 'fa41bfeb-1111-4111-8111-111111111111' },
    })

    // 选权威等级（antd Select：点击外层 selector 后选可见 option）
    const tierLabel = screen.getByText(/校验后权威等级/)
    const tierSelector = tierLabel.closest('div')?.querySelector('.ant-select-selector')
    expect(tierSelector).not.toBeNull()
    tierSelector!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await waitFor(() => {
      expect(screen.getByText('OFFICIAL_PUBLISHER · 官方出版社')).toBeInTheDocument()
    })
    fireEvent.click(screen.getByText('OFFICIAL_PUBLISHER · 官方出版社'))

    const submit = screen.getByRole('button', { name: /判定 VERIFIED/ })
    expect(submit).not.toBeDisabled()
    await act(async () => {
      fireEvent.click(submit)
    })

    await waitFor(() => expect(verifyMock).toHaveBeenCalledTimes(1))
    const payload = verifyMock.mock.calls[0]?.[2]
    expect(payload).toMatchObject({
      targetStatus: 'VERIFIED',
      licenseStatus: 'FAIR_USE_QUOTATION',
      verifiedBy: 'fa41bfeb-1111-4111-8111-111111111111',
    })
  })
})
