// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'

import { RagSourceTable } from '@/pages/rag/RagSourceTable'
import { timeText } from '@/pages/rag/ragUi'
import type { RagSource } from '@/api/ragTypes'
import type { RagSourceListState } from '@/features/rag/useRagManagement'

/**
 * 来源列表的**时间列**（2026-11-05）。
 *
 * ⚠️ 守的是「时间这类事实不许被凑出来」：
 *    · 录入 / 审核开始 / 审核决定 / 元数据确认是**四个不同事件**，
 *      任一缺失都表示该事件尚未发生，**不得**回落到相邻时刻；
 *    · 缺失渲染成 `—`，不得补当前时间或登记时间（那会让 SLA 看起来正常）；
 *    · 时间一律显示本地可读时刻，原始 ISO 进 tooltip（与目录页同一口径）。
 */

vi.mock('@/api/ragClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/ragClient')>()
  return { ...actual, isRagTargetReady: () => true }
})

vi.mock('@/features/dictionaries/useDictionaries', () => ({
  useDictOptions: () => ({ options: [], source: 'fallback' as const }),
}))

vi.mock('@/features/rag/useRagIngestJobs', () => ({
  useRagIngestJobs: () => ({
    state: { jobs: [], status: 'idle', error: null },
    reload: vi.fn(),
  }),
}))

const TARGET = { scope: 'TENANT' as const, tenantId: 't-1' }

function source(overrides: Partial<RagSource> = {}): RagSource {
  return {
    sourceId: 's-1',
    sourceType: 'TEXTBOOK',
    title: '沪教版英语六年级上册',
    publisher: '上海教育出版社',
    isbn: null,
    doi: null,
    trustTier: 'UNVERIFIED',
    verificationStatus: 'IN_REVIEW',
    verificationMethod: 'MANUAL_REVIEW',
    verificationEvidence: null,
    verifiedAt: null,
    verifiedBy: null,
    validDays: 365,
    licenseStatus: 'PENDING',
    stale: false,
    admissible: false,
    objectRef: null,
    contentHash: null,
    createdAt: '2026-11-01T02:00:00Z',
    updatedAt: '2026-11-04T06:00:00Z',
    reviewStartedAt: '2026-11-02T03:00:00Z',
    decidedAt: null,
    metadataConfirmedAt: '2026-11-03T04:00:00Z',
    ...overrides,
  }
}

function success(list: RagSource[]): RagSourceListState {
  return { status: 'success', sources: list, error: null }
}

function renderTable(state: RagSourceListState): void {
  render(
    <App>
      <RagSourceTable
        state={state}
        target={TARGET}
        onQuery={vi.fn()}
        onRefresh={vi.fn()}
        onSelect={vi.fn()}
        onRegister={vi.fn()}
      />
    </App>,
  )
}

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

describe('来源列表 · 时间列', () => {
  it('✅ 时间列就位：录入 / 审核时间 / 元数据确认 / 最近更新', async () => {
    renderTable(success([source()]))
    await waitFor(() => expect(screen.getByText('沪教版英语六年级上册')).toBeTruthy())

    for (const title of ['录入时间', '审核时间', '元数据确认', '最近更新']) {
      expect(screen.getAllByText(title).length).toBeGreaterThan(0)
    }
    // 审核列必须**同时**露出「开始」与「决定」两段，而不是只给一个合并时刻
    expect(screen.getByText('开始')).toBeTruthy()
    expect(screen.getByText('决定')).toBeTruthy()
  })

  it('✅ 时间按本地可读时刻渲染，原始 ISO 不出现在正文', async () => {
    renderTable(success([source()]))
    await waitFor(() => expect(screen.getByText('沪教版英语六年级上册')).toBeTruthy())

    // ⚠️ 断言**形状**而不是具体时刻：写死字符串等于把断言绑死在跑测试的时区上。
    const times = screen.getAllByText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
    expect(times.length).toBeGreaterThan(0)
    expect(screen.queryAllByText('2026-11-01T02:00:00Z')).toHaveLength(0)
  })

  it('⛔ 未判定不得回落到录入时间：决定一栏必须是「—」', async () => {
    // 行里 decidedAt=null 而 createdAt 有值——最常见的「假数据」就是把两者混用
    renderTable(success([source({ decidedAt: null, verifiedAt: null })]))
    await waitFor(() => expect(screen.getByText('沪教版英语六年级上册')).toBeTruthy())

    const created = timeText('2026-11-01T02:00:00Z')
    // 录入时间只出现一次（录入列），若审核列拿它冒充就会出现第二次
    expect(screen.getAllByText(created).length).toBe(1)
  })

  it('⛔ 未确认元数据要写「未确认」而不是「—」（两者语义不同）', async () => {
    renderTable(success([source({ metadataConfirmedAt: null })]))
    await waitFor(() => expect(screen.getByText('沪教版英语六年级上册')).toBeTruthy())
    expect(screen.getByText('未确认')).toBeTruthy()
  })

  it('⛔ 旧版后端未返回字段时显示「—」，不冒充「尚未发生」的结论', async () => {
    renderTable(
      success([
        source({
          reviewStartedAt: undefined,
          decidedAt: undefined,
          metadataConfirmedAt: undefined,
        }),
      ]),
    )
    await waitFor(() => expect(screen.getByText('沪教版英语六年级上册')).toBeTruthy())
    // undefined = 本次未返回：审核列整格退化为「—」，也不得写「未确认」这种结论
    expect(screen.queryByText('未确认')).toBeNull()
    expect(screen.queryByText('开始')).toBeNull()
  })
})
