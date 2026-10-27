/**
 * 用户反馈按钮测试（P7-3 / P7-4）
 *
 * ⛔ 核心纪律（沿用项目既有范式）：
 *   - 既测「行为」（点了会调接口、UI 有反馈），也测「连通性」（确实调了）
 *   - 必须有「反证」：mock 的返回值变化，UI 必随之变化
 *   - **不得**断言一个恒真的东西（比如只断言按钮存在）
 *
 * ⚠️ 本类的存在意义：租户前台的反馈按钮此前**根本不存在**——
 *   平台侧 `/metrics/agent/feedback` 要 `platform.resource.manage` 权限，
 *   租户令牌直调必 403，所以按钮要么不存在、要么点了必然失败。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { RunFeedback } from '@/features/run/RunFeedback'
import type { RunFeedbackKind } from '@/api/types'

const recordRunFeedbackMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return { ...actual, recordRunFeedback: recordRunFeedbackMock }
})

beforeEach(() => {
  recordRunFeedbackMock.mockReset()
  recordRunFeedbackMock.mockResolvedValue(undefined)
})

/** 四个动作 → 后端枚举（逐字一致）。 */
const CASES: { label: string; kind: RunFeedbackKind }[] = [
  { label: '有帮助', kind: 'THUMB_UP' },
  { label: '没帮助', kind: 'THUMB_DOWN' },
  { label: '再讲一次', kind: 'RETRY' },
  { label: '先不看了', kind: 'ABANDON' },
]

describe('T1：反馈按钮存在且可点', () => {
  it('应渲染四个反馈动作', () => {
    render(<RunFeedback runId="r-1" />)
    for (const c of CASES) {
      expect(screen.getByRole('button', { name: c.label })).toBeInTheDocument()
    }
  })

  it('⛔ 不得渲染自由文本输入框（反馈只有枚举，不采集用户原话）', () => {
    render(<RunFeedback runId="r-1" />)
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })
})

describe('T2：点击会真正上报（连通性 + 反证）', () => {
  for (const c of CASES) {
    it(`点击「${c.label}」应上报 ${c.kind}`, async () => {
      render(<RunFeedback runId="r-1" />)

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: c.label }))
      })

      await waitFor(() =>
        expect(recordRunFeedbackMock).toHaveBeenCalledWith('r-1', c.kind),
      )
      expect(await screen.findByText(`已记录：${c.label}`)).toBeInTheDocument()
    })
  }

  it('上报参数只有 runId 与 kind（前端不传租户、不传 traceId）', async () => {
    render(<RunFeedback runId="r-1" />)

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '有帮助' }))
    })

    await waitFor(() => expect(recordRunFeedbackMock).toHaveBeenCalledTimes(1))
    const args = recordRunFeedbackMock.mock.calls[0] as unknown[]
    expect(args).toHaveLength(2)
    expect(args[0]).toBe('r-1')
    expect(args[1]).toBe('THUMB_UP')
    // ⛔ 不得出现租户标识 / 会话标识
    expect(JSON.stringify(args)).not.toContain('tenant')
    expect(JSON.stringify(args)).not.toContain('trace')
  })

  it('未点击前不应出现「已记录」', () => {
    render(<RunFeedback runId="r-1" />)
    expect(screen.queryByText(/已记录/)).not.toBeInTheDocument()
  })
})

describe('T3：失败必须如实呈现（不得静默吞掉）', () => {
  it('上报失败时应显示错误，且不得显示「已记录」', async () => {
    recordRunFeedbackMock.mockRejectedValue(new Error('boom'))
    render(<RunFeedback runId="r-1" />)

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '没帮助' }))
    })

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText(/已记录/)).not.toBeInTheDocument()
  })

  it('失败后按钮应恢复可点（用户可以重试）', async () => {
    recordRunFeedbackMock.mockRejectedValueOnce(new Error('boom'))
    render(<RunFeedback runId="r-1" />)

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '没帮助' }))
    })
    await screen.findByRole('alert')

    expect(screen.getByRole('button', { name: '没帮助' })).not.toBeDisabled()
  })
})

describe('T4：提交中不接受第二次点击', () => {
  it('连点只应上报一次（反馈是事件流，连点会放大计数）', async () => {
    let resolve: (() => void) | undefined
    recordRunFeedbackMock.mockReturnValue(
      new Promise<void>((r) => {
        resolve = () => r()
      }),
    )
    render(<RunFeedback runId="r-1" />)

    const button = screen.getByRole('button', { name: '有帮助' })
    await act(async () => {
      fireEvent.click(button)
    })
    // 提交中：按钮应禁用
    expect(button).toBeDisabled()
    await act(async () => {
      fireEvent.click(button)
      fireEvent.click(button)
    })

    expect(recordRunFeedbackMock).toHaveBeenCalledTimes(1)

    await act(async () => {
      resolve?.()
    })
    expect(await screen.findByText('已记录：有帮助')).toBeInTheDocument()
  })
})
