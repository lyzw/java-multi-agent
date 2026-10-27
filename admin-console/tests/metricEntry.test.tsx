// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, fireEvent, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MetricEntryPage } from '@/pages/metrics/MetricEntryPage'
import {
  recordEvalSample,
  recordTaskOutcome,
  recordRiskEvent,
  recordFeedback,
} from '@/api/metricsApi'

vi.mock('@/api/metricsApi', async () => {
  const actual = await vi.importActual<typeof import('@/api/metricsApi')>('@/api/metricsApi')
  return {
    ...actual,
    recordEvalSample: vi.fn(),
    recordTaskOutcome: vi.fn(),
    recordRiskEvent: vi.fn(),
    recordFeedback: vi.fn(),
  }
})

import { ApiError } from '@/api/client'

function renderPage(): void {
  render(
    <MemoryRouter>
      <MetricEntryPage />
    </MemoryRouter>,
  )
}

/** 打开卡片内某个 antd Select 并选中指定 label 的选项（portal 渲染）。 */
async function pickOption(card: HTMLElement, optionLabel: string): Promise<void> {
  const combobox = within(card).getByRole('combobox')
  fireEvent.mouseDown(combobox, { button: 0 })
  const option = await screen.findByText(optionLabel)
  fireEvent.click(option)
}

function typeInto(card: HTMLElement, placeholder: string, value: string): void {
  fireEvent.change(within(card).getByPlaceholderText(placeholder), { target: { value } })
}

beforeEach(() => {
  vi.mocked(recordEvalSample).mockResolvedValue(undefined)
  vi.mocked(recordTaskOutcome).mockResolvedValue(undefined)
  vi.mocked(recordRiskEvent).mockResolvedValue(undefined)
  vi.mocked(recordFeedback).mockResolvedValue(undefined)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('效果数据录入页', () => {
  it('渲染四个录入区块与页面标题', () => {
    renderPage()
    expect(screen.getByText('效果数据录入')).toBeInTheDocument()
    expect(screen.getByText('一、抽样标注录入（L2 黄金基线）')).toBeInTheDocument()
    expect(screen.getByText('二、终态校验回执录入（L1 自动校验）')).toBeInTheDocument()
    expect(screen.getByText('三、风险事件录入')).toBeInTheDocument()
    expect(screen.getByText('四、用户反馈录入（L3 有噪信号）')).toBeInTheDocument()
  })

  it('用户反馈：填写后提交调用 recordFeedback 并提示成功', async () => {
    renderPage()
    const card = screen.getByText('四、用户反馈录入（L3 有噪信号）').closest('.ant-card') as HTMLElement
    typeInto(card, '要与回执/标注关联的 Run Trace', 'trace-fb-1')
    typeInto(card, '回填数据归属的租户', 'tenant-fb-1')
    await pickOption(card, '👍 点赞')
    fireEvent.click(within(card).getByRole('button', { name: '提交录入' }))

    await waitFor(() => expect(recordFeedback).toHaveBeenCalledTimes(1))
    expect(recordFeedback).toHaveBeenCalledWith({
      traceId: 'trace-fb-1',
      tenantId: 'tenant-fb-1',
      kind: 'THUMB_UP',
    })
    expect(screen.getByText('用户反馈已录入（L3 有噪信号）')).toBeInTheDocument()
  })

  it('风险事件：提交调用 recordRiskEvent，只传摘要不传原文', async () => {
    renderPage()
    const card = screen.getByText('三、风险事件录入').closest('.ant-card') as HTMLElement
    typeInto(card, '要与回执/标注关联的 Run Trace', 'trace-risk-1')
    typeInto(card, '回填数据归属的租户', 'tenant-risk-1')
    await pickOption(card, '安全拦截（编造出处等）')
    typeInto(card, '仅摘要 / 规则名 / 命中模式代号，绝不粘贴对话或工具原文', '规则=虚构引用')
    fireEvent.click(within(card).getByRole('button', { name: '提交录入' }))

    await waitFor(() => expect(recordRiskEvent).toHaveBeenCalledTimes(1))
    expect(recordRiskEvent).toHaveBeenCalledWith({
      traceId: 'trace-risk-1',
      tenantId: 'tenant-risk-1',
      eventType: 'SAFETY_BLOCK',
      detailDigest: '规则=虚构引用',
    })
  })

  it('抽样标注：提交调用 recordEvalSample（可选字段缺省不参与映射）', async () => {
    renderPage()
    const card = screen.getByText('一、抽样标注录入（L2 黄金基线）').closest('.ant-card') as HTMLElement
    typeInto(card, '要与回执/标注关联的 Run Trace', 'trace-eval-1')
    typeInto(card, '回填数据归属的租户', 'tenant-eval-1')
    // 三个 Select 中第 0 个是 verdict
    const selects = within(card).getAllByRole('combobox')
    fireEvent.mouseDown(selects[0]!, { button: 0 })
    const option = await screen.findByText('成功')
    fireEvent.click(option)
    fireEvent.click(within(card).getByRole('button', { name: '提交录入' }))

    await waitFor(() => expect(recordEvalSample).toHaveBeenCalledTimes(1))
    expect(recordEvalSample).toHaveBeenCalledWith(
      expect.objectContaining({
        traceId: 'trace-eval-1',
        tenantId: 'tenant-eval-1',
        verdict: 'SUCCESS',
      }),
    )
  })

  it('必填项缺失时拦截提交（不调用 API）', async () => {
    renderPage()
    const card = screen.getByText('四、用户反馈录入（L3 有噪信号）').closest('.ant-card') as HTMLElement
    fireEvent.click(within(card).getByRole('button', { name: '提交录入' }))
    await waitFor(() =>
      expect(screen.getByText('请填写 Trace ID')).toBeInTheDocument(),
    )
    expect(recordFeedback).not.toHaveBeenCalled()
  })

  it('后端报错时显示错误提示而非成功', async () => {
    vi.mocked(recordFeedback).mockRejectedValue(new ApiError(400, 'traceId 非法'))
    renderPage()
    const card = screen.getByText('四、用户反馈录入（L3 有噪信号）').closest('.ant-card') as HTMLElement
    typeInto(card, '要与回执/标注关联的 Run Trace', 'trace-err-1')
    typeInto(card, '回填数据归属的租户', 'tenant-err-1')
    await pickOption(card, '👎 差评')
    fireEvent.click(within(card).getByRole('button', { name: '提交录入' }))

    await waitFor(() => expect(recordFeedback).toHaveBeenCalledTimes(1))
    expect(screen.getByText('traceId 非法')).toBeInTheDocument()
  })
})
