/**
 * 问题 10 行为级专项实测（旧报告 E2E_TEST_REPORT.md 遗留，2026-11-14 四轮优化）
 *
 * ⚠️ 上轮（§6.5.3）把「批量导入进度」判为「已修复」，依据是渲染快照里
 *    有 `<progress>`。本轮只读排查发现那是**运行时不成立**的：
 *      `useRagManagement.bulkRegister` 是 fire-and-forget（void run()），
 *    页面 `await` 到 undefined ⇒ ① 失败被静默当成功 ② 所有批次同一微任务链
 *    递归、进度被 React 批处理合并 ③ 每批覆写 bulk.response、结果表只剩最后一批
 *    ④ 连点会重复提交整批。
 *
 * ⛔ 本文件**真的驱动一次 25 行导入**（10/10/5 三批），用 deferred promise
 *    逐批控制，断言串行调用、进度中间态、失败停与续传、防重。
 *    撤掉 hook 的 async 返回值，这些用例必须成片转红。
 *
 * 诚实边界：mock 的是 HTTP 层，不证明后端真的逐批收到；证明的是
 *    「前端分批编排 / 进度 / 失败语义 / 结果累积」这条链。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import { RagManagementPage } from '@/pages/rag/RagManagementPage'
import type { RagBulkImportResponse, RagBulkSourceItem } from '@/api/types'

const listRagSourcesMock = vi.hoisted(() => vi.fn())
const getRagStatisticsMock = vi.hoisted(() => vi.fn())
const applyRagVerificationMock = vi.hoisted(() => vi.fn())
const registerRagSourceMock = vi.hoisted(() => vi.fn())
const listRagDocumentsMock = vi.hoisted(() => vi.fn())
const listRagChunksMock = vi.hoisted(() => vi.fn())
const getRagDictionariesMock = vi.hoisted(() => vi.fn())
const upsertRagDictionaryEntryMock = vi.hoisted(() => vi.fn())
const deleteRagDictionaryEntryMock = vi.hoisted(() => vi.fn())
const listRagIngestJobsMock = vi.hoisted(() => vi.fn())
const bulkRegisterRagSourcesMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    listRagSources: listRagSourcesMock,
    getRagStatistics: getRagStatisticsMock,
    applyRagVerification: applyRagVerificationMock,
    registerRagSource: registerRagSourceMock,
    listRagDocuments: listRagDocumentsMock,
    listRagChunks: listRagChunksMock,
    getRagDictionaries: getRagDictionariesMock,
    upsertRagDictionaryEntry: upsertRagDictionaryEntryMock,
    deleteRagDictionaryEntry: deleteRagDictionaryEntryMock,
    listRagIngestJobs: listRagIngestJobsMock,
    bulkRegisterRagSources: bulkRegisterRagSourcesMock,
  }
})

const TENANT = '11111111-1111-1111-1111-111111111111'
const REGISTERED_BY = '33333333-3333-3333-3333-333333333333'

function renderPage(): void {
  render(
    <MemoryRouter>
      <SessionProvider>
        <RagManagementPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** 25 行合法数据（validateRow 只要求类型/标题/出版社/ISBN 或 DOI 之一非空）。 */
const ROWS_25 = JSON.stringify(
  Array.from({ length: 25 }, (_, i) => ({
    sourceType: '教材',
    title: `测试教材 ${i + 1}`,
    publisher: '测试出版社',
    isbn: `9787${String(i).padStart(9, '0')}`,
    doi: null,
    objectRef: null,
    contentHash: null,
  })),
)

function okResponse(items: RagBulkSourceItem[]): RagBulkImportResponse {
  return {
    total: items.length,
    succeeded: items.length,
    failed: 0,
    items: items.map((_, i) => ({ index: i, ok: true, sourceId: `src-${i}`, error: null })),
  }
}

interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function openImportTab(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  renderPage()
  await user.click(screen.getByRole('tab', { name: '批量导入' }))
  await user.type(screen.getByLabelText('登记人（UUID，整批统一留痕）'), REGISTERED_BY)
  // ⚠️ JSON 里的 `{` 会被 userEvent 当成键盘描述符 → 用 change 直接设值。
  //    这里测的是「解析 → 分批提交」链路，不是逐字符输入行为。
  fireEvent.change(screen.getByLabelText('数据（CSV 或 JSON 数组）'), {
    target: { value: ROWS_25 },
  })
  await user.click(screen.getByRole('button', { name: '解析并校验' }))
  expect(await screen.findByRole('button', { name: '导入 25 条' })).toBeInTheDocument()
}

beforeEach(() => {
  window.sessionStorage.setItem(
    'agent.session',
    JSON.stringify({
      accessToken: 'tok-agent-admin',
      expiresAt: Date.now() + 900_000,
      tenantId: TENANT,
      principalId: REGISTERED_BY,
    }),
  )
  for (const m of [
    listRagSourcesMock, getRagStatisticsMock, applyRagVerificationMock, registerRagSourceMock,
    listRagDocumentsMock, listRagChunksMock, getRagDictionariesMock, upsertRagDictionaryEntryMock,
    deleteRagDictionaryEntryMock, listRagIngestJobsMock, bulkRegisterRagSourcesMock,
  ]) {
    m.mockReset()
  }
  listRagIngestJobsMock.mockResolvedValue([])
  listRagSourcesMock.mockResolvedValue([])
  getRagStatisticsMock.mockResolvedValue({
    totalSources: 0, verifiedSources: 0, totalDocuments: 0, totalChunks: 0,
    retrievableChunks: 0, chunksMissingEmbedding: 0, missingEmbeddingRatio: 0,
  })
  listRagDocumentsMock.mockResolvedValue([])
  listRagChunksMock.mockResolvedValue([])
  getRagDictionariesMock.mockResolvedValue([])
})

describe('批量导入：25 行 → 串行 10/10/5，进度可观察，结果逐行累积', () => {
  it('⛔ 三批按真实结果串行推进，中间进度依次出现，结果表含全部 25 行', async () => {
    const d1 = deferred<RagBulkImportResponse>()
    const d2 = deferred<RagBulkImportResponse>()
    const d3 = deferred<RagBulkImportResponse>()
    bulkRegisterRagSourcesMock
      .mockImplementationOnce(() => d1.promise)
      .mockImplementationOnce(() => d2.promise)
      .mockImplementationOnce(() => d3.promise)

    const user = userEvent.setup()
    await openImportTab(user)
    await user.click(screen.getByRole('button', { name: '导入 25 条' }))

    // 第 1 批发出后，第 2 批**不得**提前发起（串行，不是并发）
    await waitFor(() => expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(1))
    expect(bulkRegisterRagSourcesMock.mock.calls[0]?.[2].length).toBe(10)
    expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(1)

    const batch1 = bulkRegisterRagSourcesMock.mock.calls[0]?.[2] as RagBulkSourceItem[]
    await act(async () => { d1.resolve(okResponse(batch1)) })
    expect(await screen.findByText('已处理 10 / 25 行')).toBeInTheDocument()

    await waitFor(() => expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(2))
    expect(bulkRegisterRagSourcesMock.mock.calls[1]?.[2].length).toBe(10)
    const batch2 = bulkRegisterRagSourcesMock.mock.calls[1]?.[2] as RagBulkSourceItem[]
    await act(async () => { d2.resolve(okResponse(batch2)) })
    expect(await screen.findByText('已处理 20 / 25 行')).toBeInTheDocument()

    await waitFor(() => expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(3))
    expect(bulkRegisterRagSourcesMock.mock.calls[2]?.[2].length).toBe(5)
    const batch3 = bulkRegisterRagSourcesMock.mock.calls[2]?.[2] as RagBulkSourceItem[]
    await act(async () => { d3.resolve(okResponse(batch3)) })

    // ⛔ 行号按全局下标累积，不是每批都从 1 开始
    expect(await screen.findByText(/已导入 25 \/ 25 行/)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText(/已处理 20 \/ 25 行/)).toBeNull())
    expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(3)
  })

  it('⛔ 第 2 批失败：显式「导入失败」+ 进度停在 10/25 + 不重发已成功行的续传', async () => {
    const d1 = deferred<RagBulkImportResponse>()
    const d2 = deferred<RagBulkImportResponse>()
    const d3 = deferred<RagBulkImportResponse>()
    const d4 = deferred<RagBulkImportResponse>()
    bulkRegisterRagSourcesMock
      .mockImplementationOnce(() => d1.promise)
      .mockImplementationOnce(() => d2.promise)
      .mockImplementationOnce(() => d3.promise)
      .mockImplementationOnce(() => d4.promise)

    const user = userEvent.setup()
    await openImportTab(user)
    await user.click(screen.getByRole('button', { name: '导入 25 条' }))

    await waitFor(() => expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(1))
    const batch1 = bulkRegisterRagSourcesMock.mock.calls[0]?.[2] as RagBulkSourceItem[]
    await act(async () => { d1.resolve(okResponse(batch1)) })

    await waitFor(() => expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(2))
    await act(async () => { d2.reject(new Error('第 2 批网络中断')) })

    // ⛔ 失败绝不能被伪装成成功
    expect(await screen.findByText('导入失败')).toBeInTheDocument()
    expect(screen.getByText(/已成功 10 \/ 25 行，第 2 批失败/)).toBeInTheDocument()
    expect(screen.getByText('已处理 10 / 25 行')).toBeInTheDocument()
    // 第 3 批不得发起
    expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(2)
    // 已成功的 10 行结果仍在，且不会整页报错
    expect(screen.getByText(/已导入 10 \/ 25 行/)).toBeInTheDocument()
    expect(screen.queryByText('没能完成')).toBeNull()

    // 续传：只发剩余 15 行，不重发第 1 批
    const resume = screen.getByRole('button', { name: '续传剩余 15 条' })
    await user.click(resume)
    await waitFor(() => expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(3))
    expect(bulkRegisterRagSourcesMock.mock.calls[2]?.[2].length).toBe(10)
    const batch3 = bulkRegisterRagSourcesMock.mock.calls[2]?.[2] as RagBulkSourceItem[]
    await act(async () => { d3.resolve(okResponse(batch3)) })
    await waitFor(() => expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(4))
    expect(bulkRegisterRagSourcesMock.mock.calls[3]?.[2].length).toBe(5)
    const batch4 = bulkRegisterRagSourcesMock.mock.calls[3]?.[2] as RagBulkSourceItem[]
    await act(async () => { d4.resolve(okResponse(batch4)) })
    expect(await screen.findByText(/已导入 25 \/ 25 行/)).toBeInTheDocument()
  })

  it('⛔ 连点两次只提交一套请求（同步防重，不依赖 fire-and-forget 状态）', async () => {
    const d1 = deferred<RagBulkImportResponse>()
    bulkRegisterRagSourcesMock.mockImplementation(() => d1.promise)

    const user = userEvent.setup()
    await openImportTab(user)
    const button = screen.getByRole('button', { name: '导入 25 条' })
    await user.click(button)
    await user.click(button)

    await waitFor(() => expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(1))
    expect(bulkRegisterRagSourcesMock).toHaveBeenCalledTimes(1)
  })
})
