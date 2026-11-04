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

  /**
   * ⚠️ 这条守的是一类**已经真实发生过两次**的事故：
   *   批量改造时 JSX 表达式丢了花括号，页面把代码当文字渲染出来了：
   *     - `{error && <Alert/>}` → 屏幕上出现「error &&」
   *     - `{!ready ? (<X/>) : (<Y/>)}` → 屏幕上出现「!ready ? (」和「) : (」
   *
   *   ⚠️ 为什么 tsc 查不出来：那些裸文本在 TS 里是合法的 JSX 文本节点。
   *      所以只能靠"渲染后的可见文本里不该出现裸代码"来守。
   */
  it('渲染文本里不出现裸的变量名或三元表达式（丢了花括号的 JSX 表达式）', () => {
    const forbidden = ['error &&', '!ready ? (', ') : (', '{error']
    for (const p of PAGES) {
      const { container, unmount } = render(<MemoryRouter>{p.el()}</MemoryRouter>)
      const text = container.textContent ?? ''
      for (const f of forbidden) {
        expect(text, `${p.name} 渲染出裸代码 "${f}"，疑似 JSX 花括号丢失`).not.toContain(f)
      }
      unmount()
    }
  })

  /**
   * ⚠️ **覆盖边界（如实登记）**：旧的 `/rag` 单页入口（{@link RagManagementPage}）
   *    不在上面的挂载清单里。
   *
   *    原因不是懒，是它**挂不住**：该页用 useRagIngestJobs，那个 hook 在挂载后
   *    会 setTimeout 自递归轮询（且不可见时也只是「推迟到下一个周期」再排一次），
   *    在 jsdom 里不会自然停下——挂载它就等于让测试进入无限轮询。
   *    其它 13 页没有这个行为，所以挂载断言对它们有效。
   *
   *    替代守法是静态体检：把那个文件当文本读，断言它满足那几条曾出事的结构约束。
   *    这比「假装它也挂载过」诚实——后者会让覆盖清单看起来更漂亮，却少了它。
   */
  it('旧 /rag 入口页：静态结构体检（它不参与挂载守卫）', async () => {
    const fs = await import('node:fs')
    const path = await import('node:path')
    // ⚠️ 不用 import.meta.url：jsdom 环境下它不是 file: 协议，readFileSync 会抛
    //    「The URL must be of scheme file」。用 cwd 拼相对路径，与 runner 等价且稳。
    const src = fs.readFileSync(
      path.resolve(process.cwd(), 'src/pages/rag/RagManagementPage.tsx'), 'utf8')

    // ① 不许把 useRagScope 提到顶层（该页不依赖 Shell，但若日后改成依赖，必须走 render prop）
    expect(src).not.toMatch(/=\s*useRagScope\(\)/)
    // ② 不许出现裸 JSX 表达式（花括号丢失）——与挂载守卫同一类事故
    expect(src).not.toMatch(/^\s*(error &&|!ready \? \()/m)
    // ③ 必须真的有内容（防止文件被误删成空壳）
    expect(src).toContain('RAG 语料数据管理')
  })
})
