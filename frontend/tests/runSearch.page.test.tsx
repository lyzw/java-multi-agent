/**
 * P7 检索台接线测试（FE-D10 / G7）
 *
 * ⛔ 核心纪律（沿用 FE-D8/FE-D9 范式）：
 *   - 行为测试 + 连通性测试（确实调了列表接口）
 *   - 反证：改 mock 数据 → UI 必须跟着变
 *   - **不得渲染假分页控件**：分页语义已冻结为游标分页（P4-D13），
 *     页码式控件会与后端语义冲突。
 *
 * ⚠️ 本类的存在意义：P7 此前是纯占位页（`Unavailable` + 假筛选器），
 *    后端 G7 无接口。后端就绪后若前端仍不接线，
 *    页面会**看起来正常但永远没有数据**——「假装有数据」的反面。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import { RunSearchPage } from '@/pages/business/RunSearchPage'
import type { RunListItem } from '@/api/types'

const listRunsMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return { ...actual, listRuns: listRunsMock }
})

function renderPage(): void {
  render(
    <MemoryRouter>
      <SessionProvider>
        <RunSearchPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

function sampleRun(overrides?: Partial<RunListItem>): RunListItem {
  return {
    runId: 'run-abc-123',
    state: 'SUCCEEDED',
    createdAt: '2026-10-21T01:00:00Z',
    completedAt: '2026-10-21T01:00:05Z',
    attempt: 1,
    errorCode: null,
    ...overrides,
  }
}

beforeEach(() => {
  listRunsMock.mockReset()
  listRunsMock.mockResolvedValue({ items: [sampleRun()], nextCursor: null })
})

describe('P7 Run 检索台（G7 接线）', () => {
  it('⛔ 连通性：确实调用列表接口，并渲染真实条目', async () => {
    renderPage()

    await waitFor(() => {
      expect(listRunsMock).toHaveBeenCalled()
    })
    expect(await screen.findByText('run-abc-123')).toBeTruthy()
    // ⛔ 不得再出现「接口未就绪」占位——后端已就绪
    expect(screen.queryByText(/还没有列表和分页接口/)).toBeNull()
  })

  it('⛔ 反证：改 mock 数据 → UI 必须跟着变', async () => {
    listRunsMock.mockResolvedValue({
      items: [sampleRun({ runId: 'run-changed-999', state: 'FAILED' })],
      nextCursor: null,
    })
    renderPage()

    expect(await screen.findByText('run-changed-999')).toBeTruthy()
    expect(screen.queryByText('run-abc-123')).toBeNull()
  })

  it('⛔ 诚实空态：接口返回空列表 ≠ 故障', async () => {
    listRunsMock.mockResolvedValue({ items: [], nextCursor: null })
    renderPage()

    expect(
      await screen.findByText(/没有符合条件的记录/),
    ).toBeTruthy()
    // ⛔ 空态不得说成「系统故障」
    expect(screen.queryByText(/读取失败/)).toBeNull()
  })

  it('⛔ 诚实错误态：接口失败要说「读取失败」而非空列表', async () => {
    listRunsMock.mockRejectedValue(new Error('boom'))
    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/读取失败/)).toBeTruthy()
    })
    expect(screen.queryByText(/没有符合条件的记录/)).toBeNull()
  })

  it('⛔ 分页：有 nextCursor 才显示「加载更多」', async () => {
    listRunsMock.mockResolvedValue({ items: [sampleRun()], nextCursor: 'cur-1' })
    renderPage()

    const more = await screen.findByRole('button', { name: /加载更多/ })
    expect(more).toBeTruthy()
  })

  it('⛔ 分页：无 nextCursor 时不显示「加载更多」（不空转烧配额）', async () => {
    listRunsMock.mockResolvedValue({ items: [sampleRun()], nextCursor: null })
    renderPage()

    await screen.findByText('run-abc-123')
    expect(screen.queryByRole('button', { name: /加载更多/ })).toBeNull()
  })

  it('⛔ 改筛选条件 → 回到第一页（丢弃旧游标）', async () => {
    const user = userEvent.setup()
    listRunsMock.mockResolvedValue({ items: [sampleRun()], nextCursor: 'cur-1' })
    renderPage()

    await screen.findByText('run-abc-123')
    const callsBefore = listRunsMock.mock.calls.length

    await user.selectOptions(screen.getByLabelText('状态'), 'FAILED')

    await waitFor(() => {
      expect(listRunsMock.mock.calls.length).toBeGreaterThan(callsBefore)
    })
    // ⛔ 改筛选后第一次请求必须**不带游标**——带旧游标会查到错页
    const lastCall = listRunsMock.mock.calls.at(-1)
    const params = lastCall?.[0] as { cursor?: string | null; state?: string }
    expect(params.cursor ?? null).toBeNull()
    expect(params.state).toBe('FAILED')
  })

  it('⛔ ⛔ 不得渲染「重试」类按钮——UNKNOWN 重放是 Sev-0', async () => {
    renderPage()
    await screen.findByText('run-abc-123')

    expect(screen.queryByRole('button', { name: /^重试$/ })).toBeNull()
    expect(screen.queryByText(/一键重试/)).toBeNull()
  })

  it('⛔ 不得编造后端没有的字段（耗时/学科）', async () => {
    renderPage()
    await screen.findByText('run-abc-123')

    // ⚠️ FE-01 §P7 原规划有「学科」「耗时」两列，
    //    但 run 表**没有** subject / duration 列（P4-D09 实测）。
    //    渲染它们只能靠前端编造 → 故断言这两列不出现。
    expect(screen.queryByText('学科')).toBeNull()
    expect(screen.queryByText('耗时')).toBeNull()
  })
})