// @vitest-environment jsdom
/**
 * Agent 编排对话页的浏览器层闭环测试。
 *
 * 覆盖：样例回放全链路可见、组件失败与整轮失败区分、真实端点未部署时如实报错、
 * 用户停止不伪装成故障。样例回放走的是同一套 SSE 解析器/reducer/UI，
 * 因此这些断言验证的是真实前端代码路径，而不是页面里写死的假状态。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntApp } from 'antd'
import { AgentChatPage } from '@/pages/agentlab/AgentChatPage'

interface Call {
  url: string
  init: RequestInit
}

const calls: Call[] = []
let fetchImpl: (url: string, init: RequestInit) => Promise<unknown>

function json(status: number, body: unknown): { status: number; ok: boolean; text: () => Promise<string> } {
  return {
    status,
    ok: status >= 200 && status < 300,
    text: () => Promise.resolve(JSON.stringify(body)),
  }
}

function defaultFetch(url: string): Promise<unknown> {
  if (url.includes('/platform/v1/capabilities/agents')) return Promise.resolve(json(200, []))
  if (url.includes('/platform/v1/tenants')) return Promise.resolve(json(200, []))
  return Promise.resolve(json(404, { code: 'NOT_FOUND', message: 'not found' }))
}

async function renderPage(): Promise<void> {
  // act async 包住首次渲染，让目录读取的 effect 在断言前落地。
  await act(async () => {
    render(
      <AntApp>
        <AgentChatPage />
      </AntApp>,
    )
  })
}

function inputBox(): HTMLElement {
  return screen.getByPlaceholderText(/输入问题/)
}

async function ask(question: string): Promise<void> {
  fireEvent.change(inputBox(), { target: { value: question } })
  await waitFor(() => expect(screen.getByRole('button', { name: /发送/ })).not.toBeDisabled())
  fireEvent.click(screen.getByRole('button', { name: /发送/ }))
}

/** 等样例回放到终帧：最后一条时间线必然是「编排完成」。 */
async function waitForSampleDone(): Promise<void> {
  await waitFor(
    () => {
      const steps = screen.getAllByTestId('agent-chat-trace-step')
      expect(steps.length).toBeGreaterThanOrEqual(7)
      expect(steps.at(-1)?.textContent).toContain('编排完成')
    },
    { timeout: 20_000 },
  )
}

beforeEach(() => {
  calls.length = 0
  window.sessionStorage.clear()
  fetchImpl = defaultFetch
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init: RequestInit) => {
      calls.push({ url, init })
      return fetchImpl(url, init)
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AgentChatPage · 样例回放闭环', () => {
  it('✅ 默认样例模式醒目告警，完整链路的组件阶段全部可见且非真实输出', async () => {
    await renderPage()

    expect(screen.getByTestId('agent-chat-sample-banner')).toBeInTheDocument()
    expect(document.body.textContent).toContain('非真实模型输出')
    expect(document.body.textContent).toContain('/api/agent/chat/stream')
    expect(screen.getByRole('button', { name: /发送/ })).toBeDisabled()

    await ask('判别式小于零是什么意思？')
    expect(screen.getByText('判别式小于零是什么意思？')).toBeInTheDocument()
    await waitForSampleDone()

    const steps = screen.getAllByTestId('agent-chat-trace-step').map((el) => el.textContent ?? '')
    expect(steps.some((t) => t.includes('路由 ·'))).toBe(true)
    expect(steps.some((t) => t.includes('RAG ·') && t.includes('已注入上下文'))).toBe(true)
    expect(steps.some((t) => t.includes('Skill ·') && t.includes('完成'))).toBe(true)
    expect(steps.some((t) => t.includes('Tool ·') && t.includes('返回'))).toBe(true)
    expect(steps.some((t) => t.includes('结果回传 Agent'))).toBe(true)
    expect(steps.some((t) => t.includes('用量统计'))).toBe(true)

    expect(screen.getByTestId('agent-chat-route')).toBeInTheDocument()
    expect(screen.getByTestId('agent-chat-rag')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('agent-chat-answer')).toBeInTheDocument())
    expect(screen.queryByTestId('agent-chat-error')).not.toBeInTheDocument()
    expect(calls.some((c) => c.url.includes('/api/agent/chat/stream'))).toBe(false)
  })

  it('✅ Skill 失败只是组件级错误：时间线标红但 Agent 仍降级给出回答', async () => {
    await renderPage()

    const scenario = screen.getByTestId('agent-chat-scenario')
    const selector = scenario.querySelector('.ant-select-selector')
    expect(selector).not.toBeNull()
    fireEvent.mouseDown(selector as Element)
    await waitFor(() =>
      expect(screen.getByText('Skill 失败（Agent 降级）')).toBeInTheDocument(),
    )
    fireEvent.click(screen.getByText('Skill 失败（Agent 降级）'))

    await ask('判别式小于零是什么意思？')
    await waitForSampleDone()

    const failed = screen
      .getAllByTestId('agent-chat-trace-step')
      .filter((el) => el.getAttribute('data-status') === 'error')
    expect(failed.length).toBeGreaterThan(0)
    expect(failed.some((el) => (el.textContent ?? '').includes('SKILL_DEPENDENCY_MISSING'))).toBe(true)
    expect(screen.getByTestId('agent-chat-answer')).toBeInTheDocument()
    expect(screen.queryByTestId('agent-chat-error')).not.toBeInTheDocument()
  })

  it('✅ 用户停止显示 stopped，不显示失败告警', async () => {
    await renderPage()
    await ask('判别式小于零是什么意思？')
    // 样例帧有间隔，停止时通常已收到 run/route 等组件帧；无论是否收到，语义都必须是主动停止。
    fireEvent.click(screen.getByRole('button', { name: /停止/ }))
    await waitFor(() => expect(screen.getByTestId('agent-chat-stopped')).toBeInTheDocument())
    expect(screen.queryByTestId('agent-chat-error')).not.toBeInTheDocument()
    expect(screen.queryByTestId('agent-chat-truncated')).not.toBeInTheDocument()
  })
})

describe('AgentChatPage · 真实端点未部署的诚实边界', () => {
  it('✅ 切到真实端点后 404 明示能力未接入，保留用户气泡，不偷偷回放样例', async () => {
    await renderPage()
    fireEvent.click(screen.getByText('真实端点'))
    expect(screen.queryByTestId('agent-chat-sample-banner')).not.toBeInTheDocument()

    fetchImpl = (url) =>
      url.endsWith('/api/agent/chat/stream')
        ? Promise.resolve(json(404, { code: 'NOT_FOUND', message: 'not found' }))
        : defaultFetch(url)

    await ask('判别式小于零是什么意思？')
    const error = await screen.findByTestId('agent-chat-error', {}, { timeout: 10_000 })
    expect(error.textContent).toContain('CAPABILITY_NOT_INSTALLED')
    expect(error.textContent).toContain('当前环境未部署编排对话端点')
    expect(screen.getByText('判别式小于零是什么意思？')).toBeInTheDocument()
    expect(screen.queryByTestId('agent-chat-answer')).not.toBeInTheDocument()

    const call = calls.find((c) => c.url === '/api/agent/chat/stream')
    expect(call).toBeDefined()
    expect(new Headers(call?.init.headers).get('Accept')).toBe('text/event-stream')
  })
})
