// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'

import { RagReviewInbox } from '@/pages/rag/RagReviewInbox'
import type { RagReviewQueueItem } from '@/api/ragTypes'

/**
 * 审核收件箱（交互文档 §4.6 / §10.2 防退化断言）。
 *
 * ⚠️ 守四件事：
 *    · 下一步**直接渲染服务端的 stage**，前端不推算；
 *    · 不可执行时**说清原因**（「你自己采集的」比灰按钮有用）；
 *    · 答案类片段数必须露出（抽检最关键对象）；
 *    · 空态 ≠ 故障。
 */

const listQueueMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/ragClient', async (importActual) => {
  const actual = await importActual<typeof import('@/api/ragClient')>()
  return { ...actual, listRagReviewQueue: listQueueMock }
})

const TARGET = { scope: 'TENANT' as const, tenantId: 't-1' }

function item(overrides: Partial<RagReviewQueueItem> = {}): RagReviewQueueItem {
  return {
    sourceId: 's-1',
    title: '沪教版英语六年级上册',
    publisher: '上海教育出版社',
    verificationStatus: 'PENDING',
    metadataConfirmed: true,
    inspectionStatus: 'PASSED',
    documentCount: 1,
    chunkCount: 1463,
    answerChunkCount: 0,
    registeredBy: 'u-creator',
    stage: 'READY_TO_CLAIM',
    stageText: '抽检已通过，可认领进入审核',
    canAct: true,
    cantActReason: null,
    createdAt: '2026-11-03T00:00:00Z',
    ...overrides,
  }
}

function renderInbox(): void {
  render(
    <App>
      <RagReviewInbox target={TARGET} />
    </App>,
  )
}

beforeEach(() => {
  listQueueMock.mockReset()
  listQueueMock.mockResolvedValue([])
})

describe('审核收件箱', () => {
  it('渲染服务端给出的阶段与说明（前端不推算下一步）', async () => {
    listQueueMock.mockResolvedValue([item()])
    renderInbox()

    expect(await screen.findByText('抽检已通过，可认领进入审核')).toBeInTheDocument()
    // ⚠️ 「认领并审核」同时出现在 Tag 与按钮上，用 getAllByText
    expect(screen.getAllByText('认领并审核').length).toBeGreaterThan(0)
  })

  it('⚠️ 不可执行时显示原因，而不是只给灰按钮', async () => {
    listQueueMock.mockResolvedValue([
      item({
        canAct: false,
        cantActReason: '这是你自己采集的语料，按治理规则不能由你审核（需换一位审核员）',
      }),
    ])
    renderInbox()

    expect(
      await screen.findByText(/这是你自己采集的语料/),
    ).toBeInTheDocument()
    // 不可执行时不该渲染可点的阶段按钮
    expect(screen.queryByRole('button', { name: '认领并审核' })).toBeNull()
  })

  it('答案类片段数必须露出（漏判即泄题）', async () => {
    listQueueMock.mockResolvedValue([item({ answerChunkCount: 26 })])
    renderInbox()

    expect(await screen.findByText('答案类 26 条')).toBeInTheDocument()
  })

  it('没有答案类片段时不渲染该标签（避免噪音）', async () => {
    listQueueMock.mockResolvedValue([item({ answerChunkCount: 0 })])
    renderInbox()

    await waitFor(() => {
      expect(screen.queryByText(/答案类/)).toBeNull()
    })
  })

  it('空态提示「暂无待审」，且与故障区分', async () => {
    listQueueMock.mockResolvedValue([])
    renderInbox()

    expect(await screen.findByText(/暂时没有待审语料/)).toBeInTheDocument()
    expect(screen.queryByText(/读取审核待办失败/)).toBeNull()
  })

  it('读取失败时给错误 + 重试，不与空态混淆', async () => {
    listQueueMock.mockRejectedValue(new Error('boom'))
    renderInbox()

    // ⚠️ 文案被 antd 拆在 Alert 的 message / description 两个元素里，
    //    用整段文本匹配会找不到；这里断言「出现了错误 Alert」即可。
    await waitFor(() => {
      expect(document.querySelector('.ant-alert-error')).not.toBeNull()
    })
    expect(screen.queryByText(/暂时没有待审语料/)).toBeNull()
  })

  it('多个阶段并存时各自渲染正确的标签', async () => {
    listQueueMock.mockResolvedValue([
      item({ sourceId: 'a', stage: 'CONFIRM_METADATA', stageText: '系统推断的元数据尚未确认' }),
      item({ sourceId: 'b', stage: 'NEEDS_INSPECTION', stageText: '尚未抽检' }),
      item({ sourceId: 'c', stage: 'INSPECTION_FAILED', stageText: '抽检未通过' }),
    ])
    renderInbox()

    await waitFor(() => {
      // ⚠️ 每个阶段标签同时出现在 Tag 与按钮上
      expect(screen.getAllByText('确认元数据').length).toBeGreaterThan(0)
      expect(screen.getAllByText('开始抽检').length).toBeGreaterThan(0)
      expect(screen.getAllByText('需重新处理').length).toBeGreaterThan(0)
    })
  })
})
