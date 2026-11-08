// @vitest-environment jsdom
/**
 * 人工审查队列 / 对话回放的链路守卫。
 *
 * ⚠️ 保留真实 `TraceTranscriptDrawer`（只 mock 网络层），这样
 *    「点了查看回放但根本没发请求」「回放里把未采集显示成 0」
 *    这类只在整条链路上才出现的缺陷才会变红。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, cleanup } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ReviewQueuePage } from '@/pages/quality/ReviewQueuePage'
import { ApiError } from '@/api/client'
import {
  assignReviewTask,
  dismissReviewTask,
  fetchPlatformTranscript,
  fetchReviewTasks,
  resolveReviewTask,
  type ReviewTask,
  type TranscriptResponse,
} from '@/api/traceReviewApi'

vi.mock('@/api/traceReviewApi', async () => {
  const actual = await vi.importActual<typeof import('@/api/traceReviewApi')>('@/api/traceReviewApi')
  return {
    ...actual,
    fetchReviewTasks: vi.fn(),
    fetchPlatformTranscript: vi.fn(),
    assignReviewTask: vi.fn(),
    resolveReviewTask: vi.fn(),
    dismissReviewTask: vi.fn(),
  }
})

const TASK: ReviewTask = {
  taskId: 'task-1111-2222',
  traceId: 'trace-1',
  tenantId: '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2',
  runId: 'dc17ae19-288a-49f5-8ac2-3af1f4d3893f',
  source: 'L1_GAP',
  priority: 'HIGH',
  status: 'PENDING',
  assignedTo: null,
  rootCause: null,
  createdAt: '2026-11-08T12:00:00Z',
}

const TRANSCRIPT: TranscriptResponse = {
  runId: TASK.runId,
  traceId: 'trace-1',
  state: 'SUCCEEDED',
  createdAt: '2026-11-08T12:00:00Z',
  completedAt: '2026-11-08T12:00:03Z',
  durationMs: 3000,
  snapshot: {},
  messages: [
    {
      seq: 1,
      role: 'USER',
      stepNumber: 0,
      content: { text: '这道一元二次方程为什么错了？' },
      toolOperationId: null,
      modelId: null,
      inputTokens: null,
      outputTokens: null,
      latencyMs: null,
      createdAt: '2026-11-08T12:00:00Z',
    },
    {
      seq: 2,
      role: 'ASSISTANT',
      stepNumber: 1,
      content: { text: '先看判别式', finishReason: 'LENGTH', toolCalls: [] },
      toolOperationId: null,
      modelId: 'MiniMax-M2.5',
      inputTokens: 120,
      outputTokens: 80,
      latencyMs: 1500,
      createdAt: '2026-11-08T12:00:03Z',
    },
  ],
  steps: [{ stepNumber: 1, type: 'THINK', durationMs: 1500 }],
  // ⚠️ 刻意 null：验证「未采集」不被渲染成 0。
  tokens: null,
}

function renderPage(entry = '/review-queue'): void {
  render(
    <MemoryRouter initialEntries={[entry]}>
      <ReviewQueuePage />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.mocked(fetchReviewTasks).mockResolvedValue([TASK])
  vi.mocked(fetchPlatformTranscript).mockResolvedValue(TRANSCRIPT)
  vi.mocked(assignReviewTask).mockResolvedValue(undefined)
  vi.mocked(resolveReviewTask).mockResolvedValue(undefined)
  vi.mocked(dismissReviewTask).mockResolvedValue(undefined)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('审查队列 · 呈现纪律', () => {
  it('渲染标题、审查口径与队列行（来源显示中文，不是原始枚举）', async () => {
    renderPage()
    expect(screen.getByText('人工审查队列')).toBeInTheDocument()
    expect(screen.getByText(/L1 规则只判「链路完整性」/)).toBeInTheDocument()
    expect(await screen.findByText('L1 判分缺口')).toBeInTheDocument()
    expect(screen.getByText('待认领')).toBeInTheDocument()
    expect(fetchReviewTasks).toHaveBeenCalledWith({
      status: undefined,
      tenantId: undefined,
      limit: 50,
    })
  })

  it('⚠️ 加载失败不能显示成「暂无待审任务」', async () => {
    vi.mocked(fetchReviewTasks).mockRejectedValue(new ApiError(
      200,
      '审查队列响应缺少 tasks 数组，不能当作「暂无待审任务」',
      undefined,
      'MALFORMED_RESPONSE',
    ))
    renderPage()
    expect(await screen.findByText('审查队列加载失败')).toBeInTheDocument()
    expect(screen.getByText('加载失败，无法判断是否有待审任务（不能当作空队列）')).toBeInTheDocument()
    expect(screen.queryByText('当前筛选下暂无待审任务')).not.toBeInTheDocument()
  })

  it('无权限读取时明确说缺 platform.resource.read', async () => {
    vi.mocked(fetchReviewTasks).mockRejectedValue(new ApiError(403, '无权执行该操作'))
    renderPage()
    expect(await screen.findByText(/缺少 platform.resource.read/)).toBeInTheDocument()
  })
})

describe('查看对话回放', () => {
  it('点击「查看回放」真的按该行 runId 请求平台链，并渲染完整时间线', async () => {
    renderPage()
    await screen.findByText('L1 判分缺口')
    fireEvent.click(screen.getByRole('button', { name: '查看回放' }))
    await waitFor(() => {
      expect(fetchPlatformTranscript).toHaveBeenCalledWith(TASK.runId, TASK.tenantId)
    })
    expect(await screen.findByText('对话回放 · 内容级复盘')).toBeInTheDocument()
    expect(screen.getByText('这道一元二次方程为什么错了？')).toBeInTheDocument()
    expect(screen.getByText('先看判别式')).toBeInTheDocument()
    expect(screen.getByText(/输出被 token 上限截断/)).toBeInTheDocument()
  })

  it('⚠️ tokens 为 null 显示「未采集」，不出现 0 token / 0 元', async () => {
    renderPage()
    await screen.findByText('L1 判分缺口')
    fireEvent.click(screen.getByRole('button', { name: '查看回放' }))
    expect(await screen.findByText(/未采集（该 Run 没有用量事实/)).toBeInTheDocument()
    expect(screen.getByText(/不是「0 token \/ 0 元」/)).toBeInTheDocument()
  })

  it('空时间线明确提示「未采集」，不伪装成「没有对话」', async () => {
    vi.mocked(fetchPlatformTranscript).mockResolvedValue({ ...TRANSCRIPT, messages: [] })
    renderPage()
    await screen.findByText('L1 判分缺口')
    fireEvent.click(screen.getByRole('button', { name: '查看回放' }))
    expect(await screen.findByText('未采集到对话消息')).toBeInTheDocument()
    expect(screen.getByText(/不代表这次辅导没有对话/)).toBeInTheDocument()
  })

  it('⚠️ 404 必须给出可操作指引，不能只说「资源不存在」', async () => {
    vi.mocked(fetchPlatformTranscript).mockRejectedValue(new ApiError(404, '资源不存在'))
    renderPage()
    await screen.findByText('L1 判分缺口')
    fireEvent.click(screen.getByRole('button', { name: '查看回放' }))
    expect(await screen.findByText(/请从审查队列 \/ 指标明细进入回放/)).toBeInTheDocument()
    expect(screen.getByText(/该 Run 可能尚未采集指标/)).toBeInTheDocument()
  })

  it('深链 ?runId= 自动打开回放抽屉（无 tenantId，交由后端反查）', async () => {
    renderPage(`/review-queue?runId=${TASK.runId}`)
    await waitFor(() => {
      expect(fetchPlatformTranscript).toHaveBeenCalledWith(TASK.runId, undefined)
    })
    expect(await screen.findByText('对话回放 · 内容级复盘')).toBeInTheDocument()
  })
})

describe('队列流转', () => {
  it('认领调用 assign 并刷新队列', async () => {
    renderPage()
    await screen.findByText('L1 判分缺口')
    fireEvent.click(screen.getByRole('button', { name: '认领' }))
    await waitFor(() => expect(assignReviewTask).toHaveBeenCalledWith('task-1111-2222'))
    await waitFor(() => expect(screen.getByText(/已认领任务/)).toBeInTheDocument())
  })

  it('归因关闭：填备注后按白名单 rootCause 提交', async () => {
    renderPage()
    await screen.findByText('L1 判分缺口')
    fireEvent.click(screen.getByRole('button', { name: '归因关闭' }))
    expect(await screen.findByText('归因关闭审查任务')).toBeInTheDocument()
    fireEvent.change(
      screen.getByPlaceholderText('备注（可选；只写结论摘要，不要粘贴对话原文）'),
      { target: { value: '模型输出被截断，已调大预算' } },
    )
    fireEvent.click(screen.getByRole('button', { name: '确认关闭' }))
    await waitFor(() => {
      expect(resolveReviewTask).toHaveBeenCalledWith(
        'task-1111-2222',
        'UNKNOWN',
        '模型输出被截断，已调大预算',
      )
    })
  })

  it('驳回调用 dismiss，且页面不提供删除按钮', async () => {
    renderPage()
    await screen.findByText('L1 判分缺口')
    fireEvent.click(screen.getByRole('button', { name: '驳回' }))
    fireEvent.click(await screen.findByRole('button', { name: '确认驳回' }))
    await waitFor(() => expect(dismissReviewTask).toHaveBeenCalledWith('task-1111-2222', undefined))
    expect(screen.queryByRole('button', { name: '删除' })).not.toBeInTheDocument()
  })

  it('⚠️ 写操作 403 明确说缺 platform.resource.manage，不说「网络失败」', async () => {
    vi.mocked(assignReviewTask).mockRejectedValue(new ApiError(403, '无权执行该操作'))
    renderPage()
    await screen.findByText('L1 判分缺口')
    fireEvent.click(screen.getByRole('button', { name: '认领' }))
    expect(await screen.findByText(/缺少 platform.resource.manage/)).toBeInTheDocument()
    expect(screen.queryByText(/网络错误/)).not.toBeInTheDocument()
  })

  it('已终结任务不再提供认领 / 归因 / 驳回，只留查看回放', async () => {
    vi.mocked(fetchReviewTasks).mockResolvedValue([{ ...TASK, status: 'RESOLVED' }])
    renderPage()
    await screen.findByText('已归因关闭')
    expect(screen.queryByRole('button', { name: '认领' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '归因关闭' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查看回放' })).toBeInTheDocument()
  })
})
