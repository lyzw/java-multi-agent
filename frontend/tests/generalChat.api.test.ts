/**
 * 通用知识问答 SSE 传输层契约测试。
 *
 * ⛔ 重点不是「能解析字符串」，而是三条容易悄悄回退的性质：
 *   ① done 是应用层终帧：收到即收口，不等 TCP EOF（否则气泡永远停在生成中）；
 *   ② 断流如实标 truncated，不伪装成成功或系统故障；
 *   ③ 推理草稿（thinking）即使被旧字节码推来，也绝不下发到页面。
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  GENERAL_CHAT_PATH,
  GeneralChatCancelledError,
  GeneralChatFailure,
  streamGeneralChat,
  type GeneralChatStreamEvent,
} from '@/api/generalChatApi'

/** 构造一个可控的 SSE 响应（不依赖 jsdom 是否提供 ReadableStream）。 */
function sseResponse(chunks: string[], onCancel?: () => void): Response {
  let index = 0
  return {
    ok: true,
    status: 200,
    text: async () => '',
    body: {
      getReader: () => ({
        read: async (): Promise<{ done: boolean; value?: Uint8Array }> => {
          const chunk = chunks[index]
          index += 1
          if (chunk === undefined) return { done: true }
          return { done: false, value: new TextEncoder().encode(chunk) }
        },
        cancel: async (): Promise<void> => {
          onCancel?.()
        },
      }),
    },
  } as unknown as Response
}

function httpResponse(status: number, body: unknown): Response {
  return {
    ok: false,
    status,
    text: async () => JSON.stringify(body),
  } as unknown as Response
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

describe('通用问答 SSE 传输层', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    seedSession()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('逐 token 到达；done 帧立即收口，后续帧不再处理', async () => {
    let cancelled = false
    fetchMock.mockResolvedValue(
      sseResponse(
        [
          'event: delta\ndata: 你好\n\n',
          'event: delta\ndata: 世界\n\n',
          'event: done\ndata: {"content":"你好世界","finishReason":"STOP"}\n\n',
          'event: delta\ndata: 不该出现\n\n',
        ],
        () => {
          cancelled = true
        },
      ),
    )

    const events: GeneralChatStreamEvent[] = []
    await streamGeneralChat(
      { messages: [{ role: 'user', content: '讲讲条件概率' }] },
      { signal: new AbortController().signal, onEvent: (e) => events.push(e) },
    )

    expect(events).toEqual([
      { type: 'delta', text: '你好' },
      { type: 'delta', text: '世界' },
      { type: 'answer', text: '你好世界' },
      { type: 'finish', truncated: false },
    ])
    expect(cancelled).toBe(true)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(GENERAL_CHAT_PATH)
    expect(init.method).toBe('POST')
    const headers = init.headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer tok-tenant')
    expect(headers.get('Accept')).toBe('text/event-stream')
    const payload = JSON.parse(String(init.body)) as {
      messages: { role: string; content: string }[]
      systemPrompt?: string
    }
    expect(payload.messages).toEqual([{ role: 'user', content: '讲讲条件概率' }])
    expect(payload.systemPrompt).toBeUndefined()
  })

  it('场景预设会带上 systemPrompt，且多行 data 按规范拼接', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        'event: delta\ndata: 第一行\n',
        'data: 第二行\n\n',
        'event: done\ndata: {"content":"第一行\\n第二行"}\n\n',
      ]),
    )

    const events: GeneralChatStreamEvent[] = []
    await streamGeneralChat(
      {
        messages: [{ role: 'user', content: '梳理函数' }],
        systemPrompt: '分条梳理知识点',
      },
      { signal: new AbortController().signal, onEvent: (e) => events.push(e) },
    )

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const payload = JSON.parse(String(init.body)) as { systemPrompt?: string }
    expect(payload.systemPrompt).toBe('分条梳理知识点')
    expect(events).toContainEqual({ type: 'delta', text: '第一行\n第二行' })
  })

  it('⛔ thinking 帧一律忽略（推理草稿不回落到租户通道）', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        'event: thinking\ndata: 这段草稿不得下发\n\n',
        'event: delta\ndata: 正文\n\n',
        'event: done\ndata: {"content":"正文"}\n\n',
      ]),
    )

    const events: GeneralChatStreamEvent[] = []
    await streamGeneralChat(
      { messages: [{ role: 'user', content: 'hi' }] },
      { signal: new AbortController().signal, onEvent: (e) => events.push(e) },
    )

    expect(events.map((e) => e.type)).toEqual(['delta', 'answer', 'finish'])
    expect(JSON.stringify(events)).not.toContain('这段草稿不得下发')
  })

  it('error 帧归一化为可重试的失败，不产生 finish 事件', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        'event: error\ndata: {"kind":"RATE_LIMITED","message":"too fast"}\n\n',
      ]),
    )

    const events: GeneralChatStreamEvent[] = []
    let failure: GeneralChatFailure | null = null
    try {
      await streamGeneralChat(
        { messages: [{ role: 'user', content: 'hi' }] },
        { signal: new AbortController().signal, onEvent: (e) => events.push(e) },
      )
    } catch (cause) {
      failure = cause as GeneralChatFailure
    }

    expect(failure).toBeInstanceOf(GeneralChatFailure)
    expect(failure?.info.code).toBe('RATE_LIMITED')
    expect(failure?.info.retryable).toBe(true)
    expect(events).toEqual([])
  })

  it('流在未收到终帧时结束 → 如实标 truncated=true', async () => {
    fetchMock.mockResolvedValue(sseResponse(['event: delta\ndata: 半截\n\n']))

    const events: GeneralChatStreamEvent[] = []
    await streamGeneralChat(
      { messages: [{ role: 'user', content: 'hi' }] },
      { signal: new AbortController().signal, onEvent: (e) => events.push(e) },
    )

    expect(events).toEqual([
      { type: 'delta', text: '半截' },
      { type: 'finish', truncated: true },
    ])
  })

  it('read() 抛错（连接中断）也收口为截断，不伪装成系统故障', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: async () => {
            throw new Error('connection reset')
          },
          cancel: async () => {},
        }),
      },
    } as unknown as Response)

    const events: GeneralChatStreamEvent[] = []
    await streamGeneralChat(
      { messages: [{ role: 'user', content: 'hi' }] },
      { signal: new AbortController().signal, onEvent: (e) => events.push(e) },
    )

    expect(events).toEqual([{ type: 'finish', truncated: true }])
  })

  it('401 按 client.ts 纪律清会话，且不把业务错误误判为未登录', async () => {
    fetchMock.mockResolvedValue(
      httpResponse(401, { error: 'unauthorized' }),
    )
    await expect(
      streamGeneralChat(
        { messages: [{ role: 'user', content: 'hi' }] },
        { signal: new AbortController().signal, onEvent: () => {} },
      ),
    ).rejects.toMatchObject({
      info: { code: 'UNAUTHENTICATED', retryable: false },
    })
    expect(window.sessionStorage.getItem('agent.session')).toBeNull()

    fetchMock.mockResolvedValue(
      httpResponse(403, { error: 'forbidden' }),
    )
    await expect(
      streamGeneralChat(
        { messages: [{ role: 'user', content: 'hi' }] },
        { signal: new AbortController().signal, onEvent: () => {} },
      ),
    ).rejects.toMatchObject({
      info: { code: 'ACCESS_DENIED', retryable: false, message: '无权使用通用问答' },
    })
  })

  it('404 = 能力未接入（不是故障），给出可执行的下一步', async () => {
    fetchMock.mockResolvedValue(httpResponse(404, { error: 'not_found' }))
    let failure: GeneralChatFailure | null = null
    try {
      await streamGeneralChat(
        { messages: [{ role: 'user', content: 'hi' }] },
        { signal: new AbortController().signal, onEvent: () => {} },
      )
    } catch (cause) {
      failure = cause as GeneralChatFailure
    }
    expect(failure?.info.message).toBe('通用问答能力未接入')
    expect(failure?.info.hint).toContain('/api/knowledge/chat/stream')
    expect(failure?.info.retryable).toBe(false)
  })

  it('网络失败与用户取消严格区分', async () => {
    fetchMock.mockRejectedValue(new Error('Failed to fetch'))
    await expect(
      streamGeneralChat(
        { messages: [{ role: 'user', content: 'hi' }] },
        { signal: new AbortController().signal, onEvent: () => {} },
      ),
    ).rejects.toMatchObject({ info: { code: 'NETWORK_ERROR', retryable: true } })

    const controller = new AbortController()
    fetchMock.mockRejectedValue(new Error('aborted'))
    controller.abort()
    await expect(
      streamGeneralChat(
        { messages: [{ role: 'user', content: 'hi' }] },
        { signal: controller.signal, onEvent: () => {} },
      ),
    ).rejects.toBeInstanceOf(GeneralChatCancelledError)
  })

  it('响应体没有可读流时如实收口为截断', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 200 } as unknown as Response)
    const events: GeneralChatStreamEvent[] = []
    await streamGeneralChat(
      { messages: [{ role: 'user', content: 'hi' }] },
      { signal: new AbortController().signal, onEvent: (e) => events.push(e) },
    )
    expect(events).toEqual([{ type: 'finish', truncated: true }])
  })
})
