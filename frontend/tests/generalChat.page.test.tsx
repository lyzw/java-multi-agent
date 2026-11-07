/**
 * 通用知识问答页面交互闭环测试。
 *
 * ⛔ 覆盖的是「用户真的能做完一件事」：发送 → 逐 token 落地 → 终帧收口、
 * 停止、失败重试、新建会话、换个问法、场景预设、敏感信息本地预检。
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { GeneralChatPage } from '@/pages/chat/GeneralChatPage'

function sseResponse(chunks: string[]): Response {
  let index = 0
  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: async (): Promise<{ done: boolean; value?: Uint8Array }> => {
          const chunk = chunks[index]
          index += 1
          if (chunk === undefined) return { done: true }
          return { done: false, value: new TextEncoder().encode(chunk) }
        },
        cancel: async (): Promise<void> => {},
      }),
    },
  } as unknown as Response
}

function successStream(): Response {
  return sseResponse([
    'event: delta\ndata: 条件概率是\n\n',
    'event: delta\ndata: 在已知条件下的概率。\n\n',
    'event: done\ndata: {"content":"条件概率是在已知条件下的概率。","finishReason":"STOP"}\n\n',
  ])
}

function seedSession(): void {
  window.sessionStorage.setItem(
    'agent.session',
    JSON.stringify({
      accessToken: 'tok-tenant',
      expiresAt: Date.now() + 900_000,
      tenantId: 'f94ae0d9-289f-4c96-98ed-634f8f4fcf02',
    }),
  )
}

function typeDraft(text: string): void {
  fireEvent.change(screen.getByRole('textbox', { name: '问题输入' }), {
    target: { value: text },
  })
}

function clickSend(): void {
  fireEvent.click(screen.getByRole('button', { name: '发送' }))
}

describe('通用知识问答页面', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    seedSession()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('首屏如实展示边界说明，空输入时发送不可用', () => {
    fetchMock.mockResolvedValue(successStream())
    render(<GeneralChatPage />)

    expect(screen.getByRole('heading', { name: '通用知识问答' })).toBeInTheDocument()
    expect(screen.getByText('这条链路的诚实边界')).toBeInTheDocument()
    expect(screen.getByText(/不检索 RAG、没有引用与出处/)).toBeInTheDocument()
    expect(screen.getByText(/会话只在内存/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    expect(screen.getByText(/还没有提问/)).toBeInTheDocument()
  })

  it('发送后逐 token 落地并收口；用户气泡始终保留', async () => {
    fetchMock.mockResolvedValue(successStream())
    render(<GeneralChatPage />)

    typeDraft('什么是条件概率')
    clickSend()

    expect(screen.getByText('什么是条件概率')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText('条件概率是在已知条件下的概率。')).toBeInTheDocument()
    })
    expect(screen.getByText(/通用知识问答 · 模型生成/)).toBeInTheDocument()
    // 发送后草稿清空，按钮因「空输入」禁用（这是本地校验，不是还在发送）；
    // 真正表示「已收口」的是停止按钮不可用 + 重新输入后可再发。
    expect(screen.getByRole('button', { name: '停止' })).toBeDisabled()
    typeDraft('下一问')
    expect(screen.getByRole('button', { name: '发送' })).toBeEnabled()
    expect(screen.getByText('什么是条件概率')).toBeInTheDocument()

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/knowledge/chat/stream')
    const payload = JSON.parse(String(init.body)) as {
      messages: { role: string; content: string }[]
    }
    expect(payload.messages).toEqual([{ role: 'user', content: '什么是条件概率' }])
  })

  it('⛔ 停止是正常操作，不是系统故障', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        body: {
          getReader: () => ({
            read: () =>
              new Promise((_resolve, reject) => {
                const init = fetchMock.mock.calls[0]?.[1] as RequestInit | undefined
                init?.signal?.addEventListener(
                  'abort',
                  () => reject(new Error('aborted')),
                  { once: true },
                )
              }),
            cancel: async (): Promise<void> => {},
          }),
        },
      } as unknown as Response),
    )
    render(<GeneralChatPage />)

    typeDraft('请讲讲牛顿第三定律')
    clickSend()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '停止' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '停止' }))

    await waitFor(() => {
      expect(screen.getByText(/已停止/)).toBeInTheDocument()
    })
    expect(screen.queryByText(/模型调用失败/)).not.toBeInTheDocument()
    expect(screen.getByText('请讲讲牛顿第三定律')).toBeInTheDocument()
  })

  it('可重试的失败给出「重试」，点击后原样重发', async () => {
    fetchMock
      .mockResolvedValueOnce(
        sseResponse([
          'event: error\ndata: {"kind":"RATE_LIMITED","message":"too fast"}\n\n',
        ]),
      )
      .mockResolvedValueOnce(successStream())
    render(<GeneralChatPage />)

    typeDraft('再问一次')
    clickSend()
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('模型调用过于频繁')
    })
    fireEvent.click(screen.getByRole('button', { name: '重试' }))

    await waitFor(() => {
      expect(screen.getByText('条件概率是在已知条件下的概率。')).toBeInTheDocument()
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const [, init] = fetchMock.mock.calls[1] as [string, RequestInit]
    const payload = JSON.parse(String(init.body)) as {
      messages: { role: string; content: string }[]
    }
    expect(payload.messages).toEqual([{ role: 'user', content: '再问一次' }])
  })

  it('404 是「能力未接入」，不给必然失败的重试按钮', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 404,
      text: async () => JSON.stringify({ error: 'not_found' }),
    } as unknown as Response)
    render(<GeneralChatPage />)

    typeDraft('问题')
    clickSend()
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('通用问答能力未接入')
    })
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument()
  })

  it('⛔ 敏感信息本地预检阻断发送，且不回显命中内容', async () => {
    fetchMock.mockResolvedValue(successStream())
    render(<GeneralChatPage />)

    typeDraft('我的电话 13812345678 请联系我')
    expect(screen.getByRole('alert')).toHaveTextContent('输入包含不可采集的信息')
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    clickSend()
    expect(fetchMock).not.toHaveBeenCalled()
    // ⚠️ 只断言「提示不回显命中内容」：输入框里当然还留着用户自己打的原文，
    //    用 queryByText 扫全文档会把 textarea 的 value 一并算进去（假阳性）。
    expect(screen.getByRole('alert')).not.toHaveTextContent('13812345678')
  })

  it('场景预设带上系统提示词，示例问题可回填输入框', async () => {
    fetchMock.mockResolvedValue(successStream())
    render(<GeneralChatPage />)

    fireEvent.change(screen.getByLabelText('回答场景'), {
      target: { value: 'concept' },
    })
    fireEvent.click(
      screen.getByRole('button', { name: '用一句话解释条件概率，并举一个生活里的例子' }),
    )
    expect(screen.getByRole('textbox', { name: '问题输入' })).toHaveValue(
      '用一句话解释条件概率，并举一个生活里的例子',
    )
    clickSend()

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const payload = JSON.parse(String(init.body)) as { systemPrompt?: string }
    expect(payload.systemPrompt).toContain('初中生能理解的语言')
  })

  it('新会话清空内存消息与草稿', async () => {
    fetchMock.mockResolvedValue(successStream())
    render(<GeneralChatPage />)

    typeDraft('第一个问题')
    clickSend()
    await waitFor(() => {
      expect(screen.getByText('条件概率是在已知条件下的概率。')).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: '新会话' }))

    expect(screen.getByText(/还没有提问/)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '问题输入' })).toHaveValue('')
  })
})
