/**
 * 租户前台 · Agent 编排对话页的浏览器层闭环测试。
 *
 * 样例回放走的是同一套 SSE 解析器 + reducer + UI，因此这里验证的是真实前端路径；
 * 同时守卫三条租户红线：身份域端点、推理草稿不下发、样例回放醒目告警。
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AgentChatPage } from '@/pages/chat/AgentChatPage'

function renderPage(): void {
  render(
    <MemoryRouter>
      <AgentChatPage />
    </MemoryRouter>,
  )
}

function json(
  status: number,
  body: unknown,
): { status: number; ok: boolean; text: () => Promise<string> } {
  return {
    status,
    ok: status >= 200 && status < 300,
    text: () => Promise.resolve(JSON.stringify(body)),
  }
}

function seedSession(): void {
  window.sessionStorage.setItem(
    'agent.session',
    JSON.stringify({
      accessToken: 'tok-tenant',
      expiresAt: Date.now() + 900_000,
      tenantId: 'f94ae0d9-289f-4c96-98ed-634f8f4fcf02',
      roles: ['STUDENT'],
    }),
  )
}

function inputBox(): HTMLElement {
  return screen.getByRole('textbox', { name: '问题输入' })
}

async function ask(question: string): Promise<void> {
  fireEvent.change(inputBox(), { target: { value: question } })
  await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).not.toBeDisabled())
  fireEvent.click(screen.getByRole('button', { name: '发送' }))
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

let fetchImpl: (url: string, init: RequestInit) => Promise<unknown>
const calls: { url: string; init: RequestInit }[] = []

beforeEach(() => {
  seedSession()
  calls.length = 0
  fetchImpl = () => Promise.resolve(json(404, { code: 'NOT_FOUND' }))
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
  it('首屏如实标注样例回放非真实输出，空输入不能发送', () => {
    renderPage()

    expect(screen.getByRole('heading', { name: 'Agent 编排对话' })).toBeInTheDocument()
    expect(screen.getByText('这条链路的诚实边界')).toBeInTheDocument()
    expect(screen.getByTestId('agent-chat-sample-banner')).toBeInTheDocument()
    expect(document.body.textContent).toContain('非真实模型输出')
    expect(document.body.textContent).toContain('/api/knowledge/agent/chat/stream')
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
  })

  it('发送 → 组件时间线 → 回答落地；推理草稿不出现在租户页面', async () => {
    renderPage()
    await ask('判别式小于零是什么意思？')

    expect(screen.getByText('判别式小于零是什么意思？')).toBeInTheDocument()
    await waitForSampleDone()

    const steps = screen.getAllByTestId('agent-chat-trace-step').map((el) => el.textContent ?? '')
    expect(steps.some((t) => t.includes('路由 ·'))).toBe(true)
    expect(steps.some((t) => t.includes('RAG ·') && t.includes('已注入上下文'))).toBe(true)
    expect(steps.some((t) => t.includes('Skill ·') && t.includes('完成'))).toBe(true)
    expect(steps.some((t) => t.includes('Tool ·') && t.includes('返回'))).toBe(true)
    expect(steps.some((t) => t.includes('结果回传 Agent'))).toBe(true)
    expect(screen.getByTestId('agent-chat-route')).toBeInTheDocument()
    expect(screen.getByTestId('agent-chat-rag')).toBeInTheDocument()
    expect(screen.getByTestId('agent-chat-answer')).toBeInTheDocument()
    expect(screen.queryByTestId('agent-chat-error')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toContain('这段推理草稿')
    expect(calls.some((c) => c.url.includes('/api/agent/chat/stream'))).toBe(false)
  })

  it('Skill 失败是组件级错误：时间线标红但 Agent 仍降级给出回答', async () => {
    renderPage()
    const scenario = document.getElementById('achat-scenario')
    expect(scenario).not.toBeNull()
    fireEvent.change(scenario as HTMLSelectElement, { target: { value: 'skill-error' } })

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

  it('thinking 模式只控制组件过程可见性，关掉后不影响回答与状态机', async () => {
    renderPage()
    const toggle = screen.getByRole('checkbox')
    expect(toggle).toBeChecked()
    fireEvent.click(toggle) // 关闭过程模式
    expect(toggle).not.toBeChecked()

    await ask('判别式小于零是什么意思？')
    // 等整轮回放到 done 终帧再判断：thinking 关闭时时间线不渲染，
    // 改等页脚「已收口」标志（仅 done 且未截断出现），避免把回放中途误判成终点。
    await waitFor(() => expect(document.body.textContent).toContain('已收口'))
    expect(screen.getByTestId('agent-chat-answer')).toBeInTheDocument()
    expect(screen.queryByTestId('agent-chat-timeline')).not.toBeInTheDocument()

    fireEvent.click(toggle) // 重新打开：历史事件按到达顺序完整保留
    expect(screen.getByTestId('agent-chat-timeline')).toBeInTheDocument()
    expect(screen.getAllByTestId('agent-chat-trace-step').length).toBeGreaterThanOrEqual(7)
  })

  it('用户停止显示 stopped，不冒充失败或截断', async () => {
    renderPage()
    await ask('判别式小于零是什么意思？')
    fireEvent.click(screen.getByRole('button', { name: '停止' }))
    await waitFor(() => expect(screen.getByTestId('agent-chat-stopped')).toBeInTheDocument())
    expect(screen.queryByTestId('agent-chat-error')).not.toBeInTheDocument()
    expect(screen.queryByTestId('agent-chat-truncated')).not.toBeInTheDocument()
  })
})

describe('AgentChatPage · 真实端点未部署', () => {
  it('切真实端点后 404 明示能力未接入，保留用户气泡，不偷偷回放样例', async () => {
    renderPage()
    const source = document.getElementById('achat-source')
    expect(source).not.toBeNull()
    fireEvent.change(source as HTMLSelectElement, { target: { value: 'live' } })
    expect(screen.queryByTestId('agent-chat-sample-banner')).not.toBeInTheDocument()

    fetchImpl = (url) =>
      url === '/api/knowledge/agent/chat/stream'
        ? Promise.resolve(json(404, { code: 'NOT_FOUND' }))
        : Promise.resolve(json(200, []))

    await ask('判别式小于零是什么意思？')
    const error = await screen.findByTestId('agent-chat-error', {}, { timeout: 10_000 })
    expect(error.textContent).toContain('CAPABILITY_NOT_INSTALLED')
    expect(error.textContent).toContain('当前环境未部署编排对话端点')
    expect(screen.getByText('判别式小于零是什么意思？')).toBeInTheDocument()
    expect(screen.queryByTestId('agent-chat-answer')).not.toBeInTheDocument()

    const call = calls.find((c) => c.url === '/api/knowledge/agent/chat/stream')
    expect(call).toBeDefined()
    expect(new Headers(call?.init.headers).get('Accept')).toBe('text/event-stream')
  })
})
