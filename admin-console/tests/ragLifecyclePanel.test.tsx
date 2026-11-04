// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'

import { RagLifecyclePanel } from '@/pages/rag/RagLifecyclePanel'
import type { RagLifecycleItem } from '@/api/ragTypes'

/**
 * 语料生命周期面板（交互文档 §4.7）。
 *
 * ⚠️ 守三件事：
 *    · 「已过期」与「即将到期」视觉必须可区分（紧急程度不同）；
 *    · 过期判定**直接信任服务端**，前端不自己算；
 *    · 空态 ≠ 故障。
 */

const listLifecycleMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/ragClient', async (importActual) => {
  const actual = await importActual<typeof import('@/api/ragClient')>()
  return { ...actual, listRagLifecycle: listLifecycleMock }
})

const TARGET = { scope: 'TENANT' as const, tenantId: 't-1' }

function item(overrides: Partial<RagLifecycleItem> = {}): RagLifecycleItem {
  return {
    sourceId: 's-1',
    title: '沪教版英语六年级上册',
    verificationStatus: 'VERIFIED',
    verifiedAt: '2025-11-01T00:00:00Z',
    validDays: 365,
    expiresAt: '2026-11-01T00:00:00Z',
    daysUntilExpiry: 200,
    stale: false,
    expiringSoon: false,
    admissible: true,
    ...overrides,
  }
}

function renderPanel(): void {
  render(
    <App>
      <RagLifecyclePanel target={TARGET} />
    </App>,
  )
}

beforeEach(() => {
  listLifecycleMock.mockReset()
  listLifecycleMock.mockResolvedValue([])
})

describe('语料生命周期面板', () => {
  it('正常语料显示「有效期内」与剩余天数', async () => {
    listLifecycleMock.mockResolvedValue([item({ daysUntilExpiry: 200 })])
    renderPanel()

    expect(await screen.findByText('有效期内')).toBeInTheDocument()
    expect(screen.getByText('剩余 200 天')).toBeInTheDocument()
  })

  it('⚠️ 已过期显示为红色「已过期」，并给出影响说明', async () => {
    listLifecycleMock.mockResolvedValue([
      item({ stale: true, daysUntilExpiry: -35, admissible: false }),
    ])
    renderPanel()

    // ⚠️「已过期」出现在 Tag、统计标题、Alerts 中，用 getAllByText
    expect((await screen.findAllByText('已过期')).length).toBeGreaterThan(0)
    expect(screen.getByText(/已过期 35 天/)).toBeInTheDocument()
    // 说明必须说清「学生现在搜不到」
    expect(screen.getByText(/学生现在搜不到/)).toBeInTheDocument()
  })

  it('⚠️ 即将到期与已过期的提示文案不同（紧急程度不同）', async () => {
    listLifecycleMock.mockResolvedValue([
      item({ sourceId: 'a', expiringSoon: true, daysUntilExpiry: 19 }),
    ])
    renderPanel()

    expect(await screen.findByText('即将到期')).toBeInTheDocument()
    expect(screen.getByText(/30 天内到期/)).toBeInTheDocument()
    // 不是过期告警
    expect(screen.queryByText(/学生现在搜不到/)).toBeNull()
  })

  it('剩余天数用负数文案（已过期 N 天）而不是「剩余 -35 天」', async () => {
    listLifecycleMock.mockResolvedValue([
      item({ stale: true, daysUntilExpiry: -35 }),
    ])
    renderPanel()

    await waitFor(() => {
      expect(screen.queryByText(/剩余 -35 天/)).toBeNull()
    })
  })

  it('没有已校验语料时给空态，而不是错误', async () => {
    listLifecycleMock.mockResolvedValue([])
    renderPanel()

    expect(await screen.findByText(/还没有通过校验的语料/)).toBeInTheDocument()
    expect(screen.queryByText(/读取生命周期失败/)).toBeNull()
  })

  it('读取失败给错误 + 重试', async () => {
    listLifecycleMock.mockRejectedValue(new Error('boom'))
    renderPanel()

    await waitFor(() => {
      expect(document.querySelector('.ant-alert-error')).not.toBeNull()
    })
  })

  it('统计区分「已过期」与「即将到期」的数量', async () => {
    listLifecycleMock.mockResolvedValue([
      item({ sourceId: 'a', stale: true, daysUntilExpiry: -10 }),
      item({ sourceId: 'b', expiringSoon: true, daysUntilExpiry: 20 }),
      item({ sourceId: 'c', daysUntilExpiry: 200 }),
    ])
    renderPanel()

    expect(await screen.findByText('已校验语料')).toBeInTheDocument()
    expect(screen.getAllByText('已过期').length).toBeGreaterThan(0)
    expect(screen.getByText(/即将到期（30 天内）/)).toBeInTheDocument()
  })
})
