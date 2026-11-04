// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'

import { RagVerificationForm } from '@/pages/rag/RagVerificationForm'
import type { RagReviewer } from '@/api/ragTypes'

/**
 * 签发表单的「复核人」字段（交互文档问题 P5 / D-001 RAG 侧授权投影）。
 *
 * ⚠️ 守三件事：
 *    · 有名录时是**下拉选人**，不是手输 UUID；
 *    · 名录拉取失败时**降级为手输**，不阻断签发；
 *    · 服务端已排除本人与采集人，下拉里不该出现「选了会被拒」的选项。
 */

const listCoSignersMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/ragClient', async (importActual) => {
  const actual = await importActual<typeof import('@/api/ragClient')>()
  return { ...actual, listRagCoSigners: listCoSignersMock }
})

const TARGET = { scope: 'TENANT' as const, tenantId: 't-1' }

function reviewer(overrides: Partial<RagReviewer> = {}): RagReviewer {
  return {
    principalId: 'u-reviewer',
    displayName: '教研组-李老师',
    canReview: true,
    canCoSign: true,
    enabled: true,
    ...overrides,
  }
}

function renderForm(opts: { status?: 'PENDING' | 'IN_REVIEW'; withSource?: boolean } = {}): void {
  render(
    <App>
      <RagVerificationForm
        currentStatus={opts.status ?? 'IN_REVIEW'}
        submitting={false}
        onSubmitted={vi.fn()}
        sourceId={opts.withSource === false ? undefined : 's-1'}
        target={TARGET}
      />
    </App>,
  )
}

beforeEach(() => {
  listCoSignersMock.mockReset()
  listCoSignersMock.mockResolvedValue([reviewer()])
})

describe('签发表单的复核人字段', () => {
  it('✅ 有审核员名录时渲染下拉，而不是 UUID 输入框', async () => {
    renderForm()

    // ⚠️ antd 的 Select 占位符不在纯文本节点里，用 DOM 结构判断：
    //    名录加载成功后，复核人位置会多出一个「没有输入框」的 Select。
    await waitFor(() => {
      expect(listCoSignersMock).toHaveBeenCalled()
    })
    await waitFor(() => {
      // 手输 UUID 的输入框应已消失（被下拉取代）
      expect(screen.queryByPlaceholderText(/第二位复核人的主体 UUID/)).toBeNull()
    })
    // 不再是「手输 UUID」的提示
    expect(screen.queryByPlaceholderText(/第二位复核人的主体 UUID/)).toBeNull()
  })

  it('✅ 下拉选项展示人能认出的显示名（不是裸 UUID）', async () => {
    renderForm()

    await waitFor(() => {
      expect(screen.queryByPlaceholderText(/第二位复核人的主体 UUID/)).toBeNull()
    })

    // ⚠️ antd 的 Select 结构：取最后一个（复核人）打开看选项
    const selectors = Array.from(document.querySelectorAll('.ant-select-selector'))
    const reviewerSelector = selectors[selectors.length - 1]
    expect(reviewerSelector).toBeDefined()
    reviewerSelector!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))

    await waitFor(() => {
      // 选项里必须是**人能认出的显示名**，不是裸 UUID
      expect(screen.getByText('教研组-李老师')).toBeInTheDocument()
    })
  })

  it('⚠️ 名录拉取失败时降级为手输 UUID，不阻断签发', async () => {
    listCoSignersMock.mockRejectedValue(new Error('boom'))
    renderForm()

    await waitFor(() => {
      expect(
        screen.getByPlaceholderText(/第二位复核人的主体 UUID/),
      ).toBeInTheDocument()
    })
    // 表单仍然可用（提交按钮存在）
    expect(screen.getByRole('button', { name: /提交校验决定/ })).toBeInTheDocument()
  })

  it('名录为空时同样降级为手输（不是空下拉让人无从选择）', async () => {
    listCoSignersMock.mockResolvedValue([])
    renderForm()

    await waitFor(() => {
      expect(
        screen.getByPlaceholderText(/第二位复核人的主体 UUID/),
      ).toBeInTheDocument()
    })
  })

  it('缺 sourceId 时不请求名录（避免无意义网络调用）', async () => {
    renderForm({ withSource: false })

    await waitFor(() => {
      expect(listCoSignersMock).not.toHaveBeenCalled()
    })
  })

  it('非 VERIFIED 目标时复核人字段禁用（只有通过校验才需双人复核）', async () => {
    renderForm({ status: 'PENDING' })
    // PENDING 的合法目标是 IN_REVIEW / REVOKED，都不需要复核人 →
    // 复核人字段（最后一个输入类控件）应处于禁用态
    await waitFor(() => {
      expect(listCoSignersMock).toHaveBeenCalled()
    })
    const inputs = Array.from(document.querySelectorAll('input'))
    const anyDisabled = inputs.some((i) => (i as HTMLInputElement).disabled)
    expect(anyDisabled).toBe(true)
  })
})
