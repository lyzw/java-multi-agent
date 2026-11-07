// @vitest-environment jsdom
/**
 * AI 对话端点封装测试：线格式、阶段事件、合法空结果、异常归一化、超时与取消。
 *
 * 用 stubGlobal('fetch') 走真实 `client.ts#request`，不 mock 掉被测模块的依赖，
 * 这样「路径拼错 / 少带 X-Tenant-Id / Content-Type 被覆盖」这类真实缺陷才会暴露。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { storeSession } from '@/auth/tokenStorage'
import { STORAGE_KEY } from '@/config/constants'
import {
  CHAT_TIMEOUT_STATUS,
  ChatCancelledError,
  describeChatError,
  DIRECT_GENERATOR_LABEL,
  sendChatMessage,
  type ChatDirectMessage,
  type ChatRequest,
  type ChatStreamEvent,
} from '@/api/aiChatApi'
import { ReadableStream } from 'node:stream/web'

const HIT = {
  chunkId: '11111111-2222-4333-8444-555555555555',
  text: '判别式小于零时，方程没有实数根。',
  role: 'EXPLANATION',
  score: 0.72,
  edition: '数学·八年级·上册·人教版',
  locator: 'P42 例 3',
  channel: 'DENSE',
  reason: 'SEMANTIC_MATCH',
}

const RETRIEVAL_OK = {
  hits: [HIT],
  versionMismatchDetected: false,
  answerContentSuppressed: false,
  primaryEdition: '数学·八年级·上册·人教版',
}

const GENERATE_OK = {
  answer: '判别式小于零时，方程没有实数根。',
  citations: [HIT],
  generator: 'EXCERPT_ASSEMBLY',
  disclaimer: '本回答为教材原文逐字摘录。',
  versionMismatchDetected: false,
  answerContentSuppressed: false,
}

const PARAMS: ChatRequest = {
  tenantId: '11111111-1111-4111-8111-111111111111',
  query: '判别式小于零是什么意思？',
  mode: 'EXPLAIN',
  edition: { subject: '数学', grade: '八年级', term: '上册', publisher: '人教版', revisionCode: '2024' },
  scopeMode: 'STRICT',
  topK: 8,
}

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
  if (url.endsWith('/api/v1/retrieval')) return Promise.resolve(json(200, RETRIEVAL_OK))
  if (url.endsWith('/api/v1/retrieval/generate')) return Promise.resolve(json(200, GENERATE_OK))
  return Promise.resolve(json(404, { code: 'NOT_FOUND', message: 'not found' }))
}

function collect(params: ChatRequest, signal?: AbortSignal): Promise<ChatStreamEvent[]> {
  const events: ChatStreamEvent[] = []
  const controller = new AbortController()
  return sendChatMessage(params, {
    signal: signal ?? controller.signal,
    onEvent: (e) => events.push(e),
  }).then(() => events)
}

function headerOf(call: Call | undefined, name: string): string | null {
  if (call === undefined) return null
  return new Headers(call.init.headers).get(name)
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
  vi.useRealTimers()
})

describe('sendChatMessage · 线格式', () => {
  it('✅ 两次真实调用都带 X-Tenant-Id，且 hitIds 来自上一轮检索', async () => {
    await collect(PARAMS)

    expect(calls).toHaveLength(2)
    expect(calls[0]?.url).toBe('/api/v1/retrieval')
    expect(calls[1]?.url).toBe('/api/v1/retrieval/generate')
    expect(headerOf(calls[0], 'X-Tenant-Id')).toBe(PARAMS.tenantId)
    expect(headerOf(calls[1], 'X-Tenant-Id')).toBe(PARAMS.tenantId)
    expect(headerOf(calls[0], 'Content-Type')).toBe('application/json')

    const retrievalBody = JSON.parse(String(calls[0]?.init.body)) as Record<string, unknown>
    expect(retrievalBody).toMatchObject({ query: PARAMS.query, mode: 'EXPLAIN', topK: 8 })
    expect(retrievalBody['edition']).toEqual(PARAMS.edition)

    const generateBody = JSON.parse(String(calls[1]?.init.body)) as Record<string, unknown>
    expect(generateBody['hitIds']).toEqual([HIT.chunkId])
    expect(generateBody['edition']).toEqual(retrievalBody['edition'])
  })

  it('✅ 事件顺序 = 真实到达顺序：stage → evidence → stage → answer → done', async () => {
    const events = await collect(PARAMS)
    expect(events.map((e) => e.type)).toEqual(['stage', 'evidence', 'stage', 'answer', 'done'])
    const answer = events.find((e) => e.type === 'answer')
    expect(answer).toMatchObject({ answer: GENERATE_OK.answer, generator: 'EXCERPT_ASSEMBLY' })
  })

  it('✅ 反证：零命中不发 generate 请求，收口为合法业务结果 no-basis', async () => {
    fetchImpl = (url) =>
      url.endsWith('/api/v1/retrieval')
        ? Promise.resolve(json(200, { ...RETRIEVAL_OK, hits: [] }))
        : Promise.resolve(json(200, GENERATE_OK))
    const events = await collect(PARAMS)
    expect(calls).toHaveLength(1)
    expect(events.map((e) => e.type)).toEqual(['stage', 'evidence', 'no-basis', 'done'])
    expect(events.some((e) => e.type === 'no-basis' && e.reason === 'NO_HITS')).toBe(true)
  })

  it('✅ 反证：200 + 空 answer 不是故障，标 GATE_SUPPRESSED', async () => {
    fetchImpl = (url) =>
      url.endsWith('/api/v1/retrieval')
        ? Promise.resolve(json(200, RETRIEVAL_OK))
        : Promise.resolve(json(200, { ...GENERATE_OK, answer: '' }))
    const events = await collect(PARAMS)
    expect(events.some((e) => e.type === 'no-basis' && e.reason === 'GATE_SUPPRESSED')).toBe(true)
    expect(events.some((e) => e.type === 'answer')).toBe(false)
  })
})

describe('sendChatMessage · 异常与取消', () => {
  it('✅ 429 原样抛出 ApiError，归一文案给出冷却提示且不做自动重试', async () => {
    fetchImpl = () => Promise.resolve(json(429, { code: 'RATE_LIMITED', message: 'too many requests' }))
    await expect(collect(PARAMS)).rejects.toBeInstanceOf(ApiError)
    const info = describeChatError(new ApiError(429, '请求失败（429）', undefined, 'RATE_LIMITED'))
    expect(info).toMatchObject({ code: 'RATE_LIMITED', retryable: true })
    expect(info.hint).toContain('不做自动重试')
  })

  it('✅ 超时归一为 CHAT_TIMEOUT，与网络层失败 (0) 不同码', async () => {
    vi.useFakeTimers()
    fetchImpl = (_url, init): Promise<unknown> =>
      new Promise((_resolve, reject) => {
        init.signal?.addEventListener(
          'abort',
          () => reject(new DOMException('aborted', 'AbortError')),
          { once: true },
        )
      })
    // ⚠️ 立刻挂上 rejection handler：定时器推进期间就抛出的异常，
    //    若等 advanceTimers 返回后再 expect().rejects，会被记为 unhandled rejection。
    const outcome = expect(collect(PARAMS)).rejects.toMatchObject({
      status: CHAT_TIMEOUT_STATUS,
      code: 'CHAT_TIMEOUT',
    })
    await vi.advanceTimersByTimeAsync(20_050)
    await outcome
    expect(describeChatError(new ApiError(CHAT_TIMEOUT_STATUS, 'x', undefined, 'CHAT_TIMEOUT')).message).toBe(
      '等待回答超时',
    )
  })

  it('✅ 用户停止抛 ChatCancelledError，不落到 describeChatError 的失败分支', async () => {
    const controller = new AbortController()
    fetchImpl = (_url, init): Promise<unknown> =>
      new Promise((_resolve, reject) => {
        init.signal?.addEventListener(
          'abort',
          () => reject(new DOMException('aborted', 'AbortError')),
          { once: true },
        )
      })
    const outcome = expect(collect(PARAMS, controller.signal)).rejects.toBeInstanceOf(
      ChatCancelledError,
    )
    controller.abort()
    await outcome
  })

  it('✅ 反证：/v1/** 的 401 是会话过期，清会话 + 跳登录，不是「跨身份域边界」', async () => {
    storeSession({ accessToken: 'platform-token', expiresAt: Date.now() + 60_000 })
    const original = window.location
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { href: 'http://localhost/admin/ai-chat', pathname: '/admin/ai-chat', search: '' },
    })
    try {
      fetchImpl = () => Promise.resolve(json(401, { code: 'UNAUTHENTICATED' }))
      await expect(collect(PARAMS)).rejects.toMatchObject({
        status: 401,
        message: '登录已过期，请重新登录',
      })
      expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
      expect(window.location.href).toContain('/admin/login?reason=expired')
    } finally {
      Object.defineProperty(window, 'location', {
        configurable: true,
        writable: true,
        value: original,
      })
    }
  })

  it.each([
    [400, '请求被拒绝', false],
    [401, '登录已过期', false],
    [403, '无权', false],
    [501, '未接入', false],
    [500, 'RAG 服务不可用', true],
    [0, '网络不可达', true],
  ] as const)('✅ describeChatError(%i) 给出具体原因与下一步', (status, keyword, retryable) => {
    const info = describeChatError(new ApiError(status, 'raw', undefined, `HTTP_${status}`))
    expect(info.message).toContain(keyword)
    expect(info.hint.length).toBeGreaterThan(8)
    expect(info.retryable).toBe(retryable)
  })
})

describe('sendChatMessage · 直接对话通道 (directChatTransport)', () => {
  /**
   * 用 ReadableStream 模拟后端 SSE 原始字节流，验证 directChatTransport 的帧解析：
   * event:/data: 行解析、delta 逐帧、done 终帧带完整正文、error 帧归一为对应 ApiError。
   * 不再走 fetch 桩，而是直接给 body 一个可读流，覆盖「流式字节 → 事件」这一真实路径。
   */
  function sseResponse(chunks: string[]): { ok: boolean; status: number; body: ReadableStream<Uint8Array> } {
    const enc = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const c of chunks) controller.enqueue(enc.encode(c))
        controller.close()
      },
    })
    return { ok: true, status: 200, body: stream }
  }

  /** 刻意不 close：模拟后端/反代未干净结束 chunked 流，reader 永远等不到 EOF。 */
  function hangingSseResponse(
    chunks: string[],
  ): { ok: boolean; status: number; body: ReadableStream<Uint8Array> } {
    const enc = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const c of chunks) controller.enqueue(enc.encode(c))
      },
    })
    return { ok: true, status: 200, body: stream }
  }

  const DIRECT_OK = [
    'event: delta\ndata: 你好\n\n',
    'event: delta\ndata: 世界\n\n',
    'event: done\ndata: {"content":"你好世界","finishReason":"STOP","inputTokens":5,"outputTokens":2}\n\n',
  ]

  /**
   * ⚠️ 2026-11-07 回归：后端改用 Spring 原生 SseEmitter.event() 后，真实线上字节是
   * `event:delta` / `data:你好`（冒号后<b>不带空格</b>）。此前后端误用
   * emitter.send(String)，整帧被包成一条 data 值（`data:event: delta`），
   * 事件名全丢，前端一帧都分派不到，对话框永远停在「正在生成回复」。
   * 本例用无空格的真实格式，锁死解析器对「有空格 / 无空格」两种写法都兼容。
   */
  it('✅ 兼容 Spring 原生帧（event:/data: 无空格）：thinking/delta/done 全部分派', async () => {
    fetchImpl = (url) =>
      url.endsWith('/api/chat/stream')
        ? Promise.resolve(
            sseResponse([
              'event:thinking\ndata:先算判别式\n\n',
              'event:delta\ndata:判别式小于零\n\n',
              'event:delta\ndata:时没有实数根\n\n',
              'event:done\ndata:{"content":"判别式小于零时没有实数根","finishReason":"STOP"}\n\n',
            ]),
          )
        : Promise.resolve(json(200, {}))
    const events = await collect({ ...PARAMS, channel: 'direct' })
    expect(events.map((e) => e.type)).toEqual([
      'stage',
      'thinking',
      'delta',
      'delta',
      'answer',
      'done',
    ])
    const deltas = events.filter(
      (e): e is Extract<ChatStreamEvent, { type: 'delta' }> => e.type === 'delta',
    )
    expect(deltas.map((d) => d.text)).toEqual(['判别式小于零', '时没有实数根'])
    expect(events.find((e) => e.type === 'thinking')).toMatchObject({ text: '先算判别式' })
    expect(events.find((e) => e.type === 'answer')).toMatchObject({
      answer: '判别式小于零时没有实数根',
    })
  })

  it('✅ 真·LLM 流：delta 逐帧 + 终帧 answer(generator=DIRECT_GENERATOR_LABEL) + done', async () => {
    fetchImpl = (url) =>
      url.endsWith('/api/chat/stream')
        ? Promise.resolve(sseResponse(DIRECT_OK))
        : Promise.resolve(json(200, {}))
    const events = await collect({ ...PARAMS, channel: 'direct' })
    expect(events.map((e) => e.type)).toEqual(['stage', 'delta', 'delta', 'answer', 'done'])
    const deltas = events.filter(
      (e): e is Extract<ChatStreamEvent, { type: 'delta' }> => e.type === 'delta',
    )
    expect(deltas.map((d) => d.text)).toEqual(['你好', '世界'])
    const answer = events.find((e) => e.type === 'answer')
    expect(answer).toMatchObject({ answer: '你好世界', generator: DIRECT_GENERATOR_LABEL })
  })

  it('✅ 直接对话请求走 /api/chat/stream，带 messages（含本次 query）与 SSE 头', async () => {
    fetchImpl = (url) =>
      url.endsWith('/api/chat/stream')
        ? Promise.resolve(sseResponse(DIRECT_OK))
        : Promise.resolve(json(200, {}))
    await collect({ ...PARAMS, channel: 'direct' })
    expect(calls[0]?.url).toBe('/api/chat/stream')
    const body = JSON.parse(String(calls[0]?.init.body)) as { messages: ChatDirectMessage[] }
    expect(Array.isArray(body.messages)).toBe(true)
    expect(body.messages[body.messages.length - 1]).toEqual({ role: 'user', content: PARAMS.query })
    expect(headerOf(calls[0], 'Accept')).toBe('text/event-stream')
    expect(headerOf(calls[0], 'X-Tenant-Id')).toBe(PARAMS.tenantId)
  })

  it('✅ 反证：流不结束也必须靠 done 帧收口，气泡不能永远停在「生成中」', async () => {
    fetchImpl = (url) =>
      url.endsWith('/api/chat/stream')
        ? Promise.resolve(
            hangingSseResponse([
              'event:delta\ndata:判别式小于零时无实数根\n\n',
              'event:done\ndata:{"content":"判别式小于零时无实数根","finishReason":"STOP"}\n\n',
            ]),
          )
        : Promise.resolve(json(200, {}))
    // ⚠️ 若把 done 事件留到 reader EOF 才推，这个用例会一直挂到 testTimeout。
    const events = await collect({ ...PARAMS, channel: 'direct' })
    expect(events.map((e) => e.type)).toEqual(['stage', 'delta', 'answer', 'done'])
  })

  it('✅ error 帧归一为对应 ApiError（RATE_LIMITED → 429），且流被关闭', async () => {
    fetchImpl = (url) =>
      url.endsWith('/api/chat/stream')
        ? Promise.resolve(
            sseResponse(['event: error\ndata: {"kind":"RATE_LIMITED","message":"too many requests"}\n\n']),
          )
        : Promise.resolve(json(200, {}))
    await expect(collect({ ...PARAMS, channel: 'direct' })).rejects.toMatchObject({
      status: 429,
      code: 'RATE_LIMITED',
    })
  })

  const DIRECT_WITH_THINKING = [
    'event: thinking\ndata: 让我先算判别式\n\n',
    'event: delta\ndata: 你好\n\n',
    'event: thinking\ndata: 再确认根的情况\n\n',
    'event: delta\ndata: 世界\n\n',
    'event: done\ndata: {"content":"你好世界","finishReason":"STOP","inputTokens":5,"outputTokens":2}\n\n',
  ]

  it('✅ 思考过程走 thinking 事件，与答案 delta 交错且互不覆盖', async () => {
    fetchImpl = (url) =>
      url.endsWith('/api/chat/stream')
        ? Promise.resolve(sseResponse(DIRECT_WITH_THINKING))
        : Promise.resolve(json(200, {}))
    const events = await collect({ ...PARAMS, channel: 'direct' })
    expect(events.map((e) => e.type)).toEqual([
      'stage',
      'thinking',
      'delta',
      'thinking',
      'delta',
      'answer',
      'done',
    ])
    const think = events.filter(
      (e): e is Extract<ChatStreamEvent, { type: 'thinking' }> => e.type === 'thinking',
    )
    expect(think.map((t) => t.text)).toEqual(['让我先算判别式', '再确认根的情况'])
    const deltas = events.filter(
      (e): e is Extract<ChatStreamEvent, { type: 'delta' }> => e.type === 'delta',
    )
    expect(deltas.map((d) => d.text)).toEqual(['你好', '世界'])
    const answer = events.find((e) => e.type === 'answer')
    expect(answer).toMatchObject({ answer: '你好世界' })
  })
})

describe('sendChatMessage · 直接对话 401 不得误踢登录页', () => {
  it('✅ 会话有效却 401（身份域 / 旧版后端租户链）→ 保留会话并给出通道排查提示', async () => {
    storeSession({
      accessToken: 'valid-platform-token',
      expiresAt: Date.now() + 10 * 60_000,
      roles: ['PLATFORM_ADMIN'],
    })
    fetchImpl = () =>
      Promise.resolve(json(401, { code: 'UNAUTHENTICATED', message: '未认证' }))

    await expect(collect({ ...PARAMS, channel: 'direct' })).rejects.toMatchObject({
      status: 401,
      code: 'CHAT_CHANNEL_UNAUTHENTICATED',
    })
    // ⚠️ 会话仍在 = 没走 clearSession() + redirectLogin() 那条硬跳分支。
    //    本页对话只存内存，硬跳会把草稿一并丢掉，且重新登录后仍会 401。
    expect(window.sessionStorage.getItem(STORAGE_KEY)).not.toBeNull()
    expect(calls[0]?.url).toBe('/api/chat/stream')
    const info = describeChatError(
      new ApiError(401, 'raw', undefined, 'CHAT_CHANNEL_UNAUTHENTICATED'),
    )
    expect(info.hint).toContain('/api/chat')
    expect(info.retryable).toBe(false)
  })

  it('✅ 无本地会话的 401 也不清会话跳登录（配置问题原样上报）', async () => {
    fetchImpl = () => Promise.resolve(json(401, { code: 'UNAUTHENTICATED', message: '未认证' }))

    await expect(collect({ ...PARAMS, channel: 'direct' })).rejects.toMatchObject({
      status: 401,
      code: 'CHAT_CHANNEL_UNAUTHENTICATED',
    })
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
  })
})

describe('describeChatError · 直接对话通道归一化 kind', () => {
  it.each([
    ['UNAUTHORIZED', 401, '未授权', false],
    ['CHAT_CHANNEL_UNAUTHENTICATED', 401, '身份认证', false],
    ['RATE_LIMITED', 429, '频繁', true],
    ['INVALID_REQUEST', 400, '拒绝', false],
    ['CONTENT_BLOCKED', 422, '拦截', false],
    ['PROVIDER_ERROR', 502, '不可用', true],
    ['TIMEOUT', 504, '超时', true],
  ] as const)('✅ kind=%s 给出贴合文案与下一步', (kind, status, keyword, retryable) => {
    const info = describeChatError(new ApiError(status, 'raw', undefined, kind))
    expect(info.code).toBe(kind)
    expect(info.message).toContain(keyword)
    expect(info.hint.length).toBeGreaterThan(8)
    expect(info.retryable).toBe(retryable)
  })
})
