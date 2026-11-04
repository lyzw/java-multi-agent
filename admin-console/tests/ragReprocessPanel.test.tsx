// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { App } from 'antd'

import { RagReprocessPanel } from '@/pages/rag/RagReprocessPanel'
import { ApiError } from '@/api/ragClient'

/**
 * 重处理面板（交互文档 §4.3「追加重传」）。
 *
 * ⚠️ 守四件事：
 *    · 三种模式**显式列出**，各自标注代价（不做一个含糊的「重新处理」按钮）；
 *    · 不可逆模式要二次确认；
 *    · 501 呈现为「能力未上线」而不是「你操作失败」；
 *    · 不可写时不执行。
 */

const reprocessMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/ragClient', async (importActual) => {
  const actual = await importActual<typeof import('@/api/ragClient')>()
  return { ...actual, reprocessRagSource: reprocessMock }
})

const TARGET = { scope: 'TENANT' as const, tenantId: 't-1' }

function renderPanel(writable = true): void {
  render(
    <App>
      <RagReprocessPanel target={TARGET} sourceId="s-1" writable={writable} />
    </App>,
  )
}

beforeEach(() => {
  reprocessMock.mockReset()
  reprocessMock.mockResolvedValue({
    mode: 'REINDEX_MISSING',
    accepted: true,
    message: '已补全缺失向量：扫描 3 条、补齐 2 条。检索不受影响。',
    newJobId: null,
  })
})

describe('重处理面板', () => {
  it('三种模式各自列出，并标注代价（不是一个含糊的按钮）', async () => {
    renderPanel()

    expect(await screen.findByText('只补缺失的向量')).toBeInTheDocument()
    expect(screen.getByText('重新切分并重建向量')).toBeInTheDocument()
    expect(screen.getByText('换一个文件重新处理')).toBeInTheDocument()
    // 代价标注
    expect(screen.getByText('分钟级')).toBeInTheDocument()
    expect(screen.getByText('不可撤销')).toBeInTheDocument()
  })

  it('⚠️ 每种模式在动手前都写清「会发生什么」', async () => {
    renderPanel()

    expect(await screen.findByText(/检索不中断/)).toBeInTheDocument()
    expect(screen.getByText(/期间语料短暂不可检索/)).toBeInTheDocument()
    expect(screen.getByText(/原文件作废/)).toBeInTheDocument()
  })

  it('执行 REINDEX_MISSING 后展示后端返回的真实结果', async () => {
    renderPanel()

    // ⚠️ antd 给双字中文按钮插空格（渲染成「执 行」），
    //    用 name 精确匹配会找不到；改为按正则模糊匹配。
    const buttons = await screen.findAllByRole('button', { name: /执\s*行/ })
    // 第一个是 REINDEX_MISSING
    fireEvent.click(buttons[0]!)

    await waitFor(() => {
      expect(reprocessMock).toHaveBeenCalledWith(TARGET, 's-1', 'REINDEX_MISSING')
    })
    // ⚠️ 用后端 message（只有它知道实际做了什么）
    expect(await screen.findByText(/已补全缺失向量：扫描 3 条/)).toBeInTheDocument()
  })

  it('⚠️ 501 呈现为「能力暂未上线」，不是「操作失败」', async () => {
    reprocessMock.mockRejectedValue(
      new ApiError(501, '重新切分暂不可用：切分参数当前固化在处理流水线内'),
    )
    renderPanel()

    // ⚠️ antd 给双字中文按钮插空格（渲染成「执 行」），
    //    用 name 精确匹配会找不到；改为按正则模糊匹配。
    const buttons = await screen.findAllByRole('button', { name: /执\s*行/ })
    fireEvent.click(buttons[1]!)

    await waitFor(() => {
      expect(screen.getByText(/这项能力暂未上线/)).toBeInTheDocument()
    })
    // 不能让人以为是自己用错了
    expect(screen.queryByText('重处理失败，请稍后重试')).toBeNull()
  })

  it('不可写时按钮禁用', async () => {
    renderPanel(false)

    // ⚠️ antd 给双字中文按钮插空格（渲染成「执 行」），
    //    用 name 精确匹配会找不到；改为按正则模糊匹配。
    const buttons = await screen.findAllByRole('button', { name: /执\s*行/ })
    buttons.forEach((b) => expect(b).toBeDisabled())
    expect(screen.getByText(/当前作用域不可写/)).toBeInTheDocument()
  })
})
