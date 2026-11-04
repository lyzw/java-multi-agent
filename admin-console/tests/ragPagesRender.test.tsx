// @vitest-environment jsdom
/**
 * RAG 13 页挂载守卫。
 *
 * <p><b>为什么要有这个测试</b>：改造期出现过一次「13 个页面全部白屏」的事故——
 * 页面在自己的 render 顶层调用 `useRagScope()`，而提供作用域的 `RagScopeShell`
 * 写在同一个组件的 return 里，消费者位于提供者之上，运行时必然抛
 * 「useRagScope 必须在 RagScopeShell 内使用」。
 *
 * <p>这个缺陷 <b>tsc 查不出</b>（类型完全合法）、只做纯函数单测也覆盖不到，
 * 只有真正把组件挂起来才会暴露。所以这里对每一页做一次真实挂载：
 * 只要不抛异常、且渲染出页面标题，就算通过。
 *
 * <p>⚠️ 断言的是「能挂载」，不是「数据正确」——数据正确性由各页自己的用例负责。
 * 这条守卫的职责只有一个：**结构错误不许再溜过去**。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { ReactElement } from 'react'

// ── 依赖替身：这些页面会发请求，本次只验证结构，不验证数据 ────────────────────
vi.mock('@/api/ragClient', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/ragClient')
  return {
    ...actual,
    isRagTargetReady: () => true,
    tenantTarget: (tenantId: string) => ({ scope: 'TENANT' as const, tenantId }),
    platformTarget: () => ({ scope: 'PLATFORM' as const }),
    listRagSources: vi.fn(async () => []),
    getRagStats: vi.fn(async () => ({
      totalSources: 0,
      totalChunks: 0,
      retrievableChunks: 0,
      chunksMissingEmbedding: 0,
    })),
    listRagIngestJobs: vi.fn(async () => []),
    listRagIndexGenerations: vi.fn(async () => []),
    listRagReviewers: vi.fn(async () => []),
    getRagDictionaries: vi.fn(async () => []),
    listRagKpAliases: vi.fn(async () => []),
    listRagChunks: vi.fn(async () => []),
    searchRagRecall: vi.fn(async () => ({
      hits: [],
      minScore: 0.38,
      funnel: { candidates: 0, passedGate: 0, returned: 0, filteredByReason: {} },
    })),
  }
})

vi.mock('@/api/adminApi', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/adminApi')
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

import { RagDashboardPage } from '@/pages/rag/RagDashboardPage'
import { RagSourceListPage } from '@/pages/rag/RagSourceListPage'
import { RagSourceRegisterPage } from '@/pages/rag/RagSourceRegisterPage'
import { RagDocUploadPage } from '@/pages/rag/RagDocUploadPage'
import { RagChunkFixPage } from '@/pages/rag/RagChunkFixPage'
import { RagVerifyPage } from '@/pages/rag/RagVerifyPage'
import { RagInspectionPage } from '@/pages/rag/RagInspectionPage'
import { RagRecallPage } from '@/pages/rag/RagRecallPage'
import { RagReviewerRosterPage } from '@/pages/rag/RagReviewerRosterPage'
import { RagKpAliasPage } from '@/pages/rag/RagKpAliasPage'
import { RagDictionaryPage } from '@/pages/rag/RagDictionaryPage'
import { RagIngestJobsPage } from '@/pages/rag/RagIngestJobsPage'
import { RagReindexPage } from '@/pages/rag/RagReindexPage'

const PAGES: ReadonlyArray<{ name: string; el: () => ReactElement; title: string }> = [
  { name: 'Dashboard', el: () => <RagDashboardPage />, title: 'Dashboard' },
  { name: '来源列表', el: () => <RagSourceListPage />, title: '来源列表' },
  { name: '来源登记', el: () => <RagSourceRegisterPage />, title: '来源登记' },
  { name: '载体上传', el: () => <RagDocUploadPage />, title: '载体上传与解析预览' },
  { name: '片段校对', el: () => <RagChunkFixPage />, title: '片段校对' },
  { name: '校验决定', el: () => <RagVerifyPage />, title: '校验决定' },
  { name: '抽检队列', el: () => <RagInspectionPage />, title: '抽检队列' },
  { name: '召回解释', el: () => <RagRecallPage />, title: '召回解释（质检）' },
  { name: '审核员名录', el: () => <RagReviewerRosterPage />, title: '审核员名录' },
  { name: '知识点别名', el: () => <RagKpAliasPage />, title: '知识点别名' },
  { name: '字典', el: () => <RagDictionaryPage />, title: '字典' },
  { name: '摄取任务', el: () => <RagIngestJobsPage />, title: '摄取任务' },
  { name: '重索引', el: () => <RagReindexPage />, title: '重索引' },
]

describe('RAG 13 页挂载守卫', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it.each(PAGES)('$name 能挂载且渲染出标题（不再白屏）', ({ el, title }) => {
    // ⚠️ 用 try/catch 包住：React 会把渲染期抛出的异常直接冒泡，
    //    这里如果不抓，vitest 报的是框架栈，看不出「是哪一页坏了」。
    let error: unknown = null
    try {
      render(<MemoryRouter>{el()}</MemoryRouter>)
    } catch (cause) {
      error = cause
    }
    expect(error, error instanceof Error ? error.message : undefined).toBeNull()
    expect(screen.getAllByText(title).length).toBeGreaterThan(0)
  })

  it('每一页都渲染了作用域外壳（标题上方有治理域面包屑）', () => {
    for (const p of PAGES) {
      const { container, unmount } = render(<MemoryRouter>{p.el()}</MemoryRouter>)
      const text = container.textContent ?? ''
      expect(text, `${p.name} 缺少外壳面包屑`).toContain('RAG 语料管理 ·')
      unmount()
    }
  })
})
