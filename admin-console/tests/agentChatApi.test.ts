// @vitest-environment jsdom
/**
 * 通用 Agent 编排 SSE 传输层测试。
 *
 * 覆盖：协议逐事件解码、样例回放与真实端点共用同一解析路径、error 帧归一化、
 * done 应用层终帧（不等 TCP EOF）、断流/truncated、半帧误判反证、404/401 诚实边界。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ReadableStream } from 'node:stream/web'
import { storeSession } from '@/auth/tokenStorage'
import { STORAGE_KEY } from '@/config/constants'
import {
  AGENT_LIVE_STREAM_PATH,
  AGENT_SAMPLE_TRANSPORT_ID,
  AgentChatCancelledError,
  AgentChatFailure,
  buildSampleFrames,
  createSampleAgentTransport,
  decodeAgentFrame,
  describeAgentChatError,
  isAgentChatCancelled,
  resolveAgentEndpoint,
  sampleAgentChatTransport,
  sendAgentChat,
  type AgentChatRequest,
  type AgentChatSendContext,
  type AgentStreamEvent,
  type SampleScenario,
} from '@/api/agentChatApi'

interface Call {
  url: string
  init: RequestInit
}

const calls: Call[] = []
let fetchImpl: (url: string, init: RequestInit) => Promise<unknown>

const REQUEST: AgentChatRequest = {
  message: '判别式小于零是什么意思？',
  history: [{ role: 'user', content: '上一问' }, { role: 'assistant', content: '上一答' }],
  source: 'live',
  thinkingMode: true,
  agentId: 'agent-1',
  topK: 5,
}

function json(status: number, body: unknown): { status: number; ok: boolean; text: () => Promise<string> } {
  return {
    status,
    ok: status >= 200 && status < 300,
    text: () => Promise.resolve(JSON.stringify(body)),
  }
}

/** 用原始 SSE 字节流模拟后端；close=false 模拟反代不干净结束的 chunked 流。 */
function sseResponse(
  chunks: string[],
  close = true,
): { ok: boolean; status: number; body: ReadableStream<Uint8Array> } {
  const enc = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(enc.encode(chunk))
      if (close) controller.close()
    },
  })
  return { ok: true, status: 200, body: stream }
}

function emptyBodyResponse(): { ok: boolean; status: number; body: null } {
  return { ok: true, status: 200, body: null }
}

function headerOf(call: Call | undefined, name: string): string | null {
  if (call === undefined) return null
  return new Headers(call.init.headers).get(name)
}

interface RunResult {
  events: AgentStreamEvent[]
  failure: unknown
}

async function run(
  request: AgentChatRequest,
  signal: AbortSignal = new AbortController().signal,
): Promise<RunResult> {
  const events: AgentStreamEvent[] = []
  const ctx: AgentChatSendContext = { signal, onEvent: (e) => events.push(e) }
  try {
    await sendAgentChat(request, ctx)
    return { events, failure: null }
  } catch (cause) {
    return { events, failure: cause }
  }
}

function asFailure(cause: unknown): AgentChatFailure {
  if (!(cause instanceof AgentChatFailure)) {
    throw new Error(`期望 AgentChatFailure，实际得到 ${String(cause)}`)
  }
  return cause
}

beforeEach(() => {
  calls.length = 0
  window.sessionStorage.clear()
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
  vi.useRealTimers()
})

describe('协议解码与路径约定', () => {
  it('✅ 平台链路径经 API_BASE 拼接，不复用租户链 /api/chat/stream', () => {
    expect(AGENT_LIVE_STREAM_PATH).toBe('/agent/chat/stream')
    expect(resolveAgentEndpoint()).toBe('/api/agent/chat/stream')
    expect(resolveAgentEndpoint('/custom/stream')).toBe('/api/custom/stream')
    expect(resolveAgentEndpoint('https://example.test/stream')).toBe('https://example.test/stream')
  })

  it('✅ 样例帧全部可解码且阶段顺序固定；未知事件/缺 ID 的组件帧 fail-closed', () => {
    const frames = buildSampleFrames('q', 'full', true, true)
    const decoded = frames.map((f) => decodeAgentFrame(f))
    expect(decoded.every((e) => e !== null)).toBe(true)
    const types: string[] = decoded.map((e) => e?.type ?? '')

    // ⚠️ 只锁「阶段顺序」与「类型集合」，不锁 delta 帧数：
    //    正文切片长度是文案细节，写死会让改一句样例文案就假红。
    expect(types[0]).toBe('run.start')
    expect(types.at(-1)).toBe('done')
    const order = ['route', 'rag.query', 'rag.hits', 'skill', 'tool', 'handoff', 'delta', 'answer', 'usage', 'done']
    let cursor = -1
    for (const stage of order) {
      const at = types.indexOf(stage)
      expect(at, `${stage} 必须存在且按到达顺序出现`).toBeGreaterThan(cursor)
      cursor = at
    }
    expect(new Set(types)).toEqual(
      new Set([
        'run.start', 'route', 'rag.query', 'rag.hits', 'skill', 'tool',
        'handoff', 'thinking', 'delta', 'answer', 'usage', 'done',
      ]),
    )
    expect(types.filter((t) => t === 'delta').length).toBeGreaterThan(1)

    // error 帧属于「整轮失败」协议分支，正常样例不应产生，但必须能被解码。
    expect(
      decodeAgentFrame({
        event: 'error',
        data: '{"kind":"CONFIG_UNAVAILABLE","message":"无已发布版本"}',
      }),
    ).toMatchObject({ type: 'error', code: 'CONFIG_UNAVAILABLE' })

    expect(decodeAgentFrame({ event: 'unknown', data: '{}' })).toBeNull()
    expect(decodeAgentFrame({ event: 'skill', data: '{"phase":"run"}' })).toBeNull()
    expect(decodeAgentFrame({ event: 'tool', data: '{"phase":"call"}' })).toBeNull()
    expect(decodeAgentFrame({ event: 'thinking', data: '' })).toBeNull()
    expect(decodeAgentFrame({ event: 'delta', data: '' })).toBeNull()
  })

  it('✅ thinking/delta 的 data 是裸文本，其余事件的 data 是 JSON', () => {
    const frames = buildSampleFrames('q', 'full', true, true)
    const thinking = frames.find((f) => f.event === 'thinking')
    const delta = frames.find((f) => f.event === 'delta')
    const route = frames.find((f) => f.event === 'route')
    expect(thinking?.data.startsWith('{')).toBe(false)
    expect(delta?.data.startsWith('{')).toBe(false)
    expect(route?.data.startsWith('{')).toBe(true)
  })
})

describe('样例回放 · 与真实端点共用解析路径', () => {
  it('✅ 帧先序列化成 SSE 文本再解析：每个组件阶段都真实到达，且 done 非截断', async () => {
    const transport = createSampleAgentTransport({ frameDelayMs: 0 })
    const events: AgentStreamEvent[] = []
    await transport.send(
      { ...REQUEST, source: 'sample', sampleScenario: 'full' },
      { signal: new AbortController().signal, onEvent: (e) => events.push(e) },
    )

    expect(transport.id).toBe(AGENT_SAMPLE_TRANSPORT_ID)
    expect(transport.simulated).toBe(true)
    expect(events.map((e) => e.type)).toEqual(
      buildSampleFrames('q', 'full', true, true).map((f) => decodeAgentFrame(f)?.type),
    )
    expect(events.map((e) => e.type)).toEqual(expect.arrayContaining([
      'run.start',
      'route',
      'rag.query',
      'rag.hits',
      'skill',
      'tool',
      'handoff',
      'thinking',
      'delta',
      'answer',
      'usage',
      'done',
    ]))
    expect(events.at(-1)).toMatchObject({ type: 'done', truncated: false })
    expect(calls).toHaveLength(0) // 样例回放不发网络请求
  })

  it('✅ thinkingMode=false 时不产生原始推理草稿帧（租户前台沿用同一开关）', async () => {
    const { events } = await run({ ...REQUEST, source: 'sample', thinkingMode: false })
    expect(events.some((e) => e.type === 'thinking')).toBe(false)
    expect(events.some((e) => e.type === 'delta')).toBe(true)
  })

  it('✅ 四种场景都覆盖：正常、Skill 失败、Tool 超时、RAG 零命中', async () => {
    const scenarios: SampleScenario[] = ['full', 'skill-error', 'tool-error', 'rag-empty']
    const transport = createSampleAgentTransport({ frameDelayMs: 0 })
    for (const scenario of scenarios) {
      const events: AgentStreamEvent[] = []
      await transport.send(
        { ...REQUEST, source: 'sample', sampleScenario: scenario },
        { signal: new AbortController().signal, onEvent: (e) => events.push(e) },
      )
      expect(events.at(-1)).toMatchObject({ type: 'done', truncated: false })
      expect(events.some((e) => e.type === 'error')).toBe(false)
    }

    const skillError: AgentStreamEvent[] = []
    await transport.send(
      { ...REQUEST, source: 'sample', sampleScenario: 'skill-error' },
      { signal: new AbortController().signal, onEvent: (e) => skillError.push(e) },
    )
    expect(skillError.some((e) => e.type === 'skill' && e.status === 'error')).toBe(true)
    expect(skillError.some((e) => e.type === 'delta')).toBe(true) // Agent 降级后仍给出回答

    const ragEmpty: AgentStreamEvent[] = []
    await transport.send(
      { ...REQUEST, source: 'sample', sampleScenario: 'rag-empty' },
      { signal: new AbortController().signal, onEvent: (e) => ragEmpty.push(e) },
    )
    expect(ragEmpty.some((e) => e.type === 'rag.hits' && e.hits.length === 0 && !e.injected)).toBe(true)
  })

  it('✅ 默认样例导出可被页面直接选用（非真实输出标识不丢）', () => {
    expect(sampleAgentChatTransport.simulated).toBe(true)
    expect(sampleAgentChatTransport.label).toBe('样例回放')
  })
})

describe('真实端点 · 请求与诚实失败', () => {
  it('✅ POST /api/agent/chat/stream，带令牌/历史/thinkingMode/topK，Accept 为 SSE', async () => {
    storeSession({ accessToken: 'platform-token', expiresAt: Date.now() + 60_000 })
    fetchImpl = () => Promise.resolve(sseResponse(['event: done\ndata: {}\n\n']))

    const { events, failure } = await run(REQUEST)
    expect(failure).toBeNull()
    expect(events.map((e) => e.type)).toEqual(['done'])

    const call = calls[0]
    expect(call?.url).toBe('/api/agent/chat/stream')
    expect(call?.init.method).toBe('POST')
    expect(headerOf(call, 'Accept')).toBe('text/event-stream')
    expect(headerOf(call, 'Authorization')).toBe('Bearer platform-token')
    expect(headerOf(call, 'Content-Type')).toBe('application/json')
    const body = JSON.parse(String(call?.init.body)) as Record<string, unknown>
    expect(body['message']).toBe(REQUEST.message)
    expect(body['history']).toEqual(REQUEST.history)
    expect(body['thinkingMode']).toBe(true)
    expect(body['agentId']).toBe('agent-1')
    expect(body['topK']).toBe(5)
    // M13 mock：两前端只解析 v1.1 帧，⛔ 请求体仍逐位不变（contextState 属 M8 + G1 签核）。
    expect(body).not.toHaveProperty('contextState')
  })

  it('✅ 404/501 明示「能力未接入」，并给出样例回放这条可执行出路', async () => {
    fetchImpl = () => Promise.resolve(json(404, { code: 'NOT_FOUND', message: 'no endpoint' }))
    const { failure } = await run(REQUEST)
    const info = asFailure(failure).info
    expect(info.code).toBe('CAPABILITY_NOT_INSTALLED')
    expect(info.message).toContain('/api/agent/chat/stream')
    expect(info.hint).toContain('样例回放')
    expect(info.retryable).toBe(false)
  })

  it('✅ 401 且会话仍有效 = 身份域/部署错配：不登出、不清会话', async () => {
    storeSession({ accessToken: 'valid-token', expiresAt: Date.now() + 60_000 })
    fetchImpl = () => Promise.resolve(json(401, { code: 'UNAUTHENTICATED' }))
    const { failure } = await run(REQUEST)
    const info = asFailure(failure).info
    expect(info.code).toBe('CHANNEL_UNAUTHENTICATED')
    expect(info.hint).toContain('不会因此登出')
    expect(window.sessionStorage.getItem(STORAGE_KEY)).not.toBeNull()
  })

  it('✅ 用户停止：取消错误与「失败」严格区分', () => {
    const cancelled = new AgentChatCancelledError()
    expect(isAgentChatCancelled(cancelled)).toBe(true)
    expect(isAgentChatCancelled(new AgentChatFailure({ code: 'X', message: 'x', hint: 'x', retryable: true }))).toBe(false)
  })
})

describe('真实端点 · 终帧、断流与错误帧', () => {
  it('✅ done 是应用层终帧：收到即收口，不等 TCP EOF', async () => {
    fetchImpl = () =>
      Promise.resolve(
        sseResponse(
          ['event: delta\ndata:你好\n\n', 'event: done\ndata: {}\n\n'],
          false, // 刻意不 close：模拟 reader 永远等不到 EOF
        ),
      )
    const { events, failure } = await run(REQUEST)
    expect(failure).toBeNull()
    expect(events.map((e) => e.type)).toEqual(['delta', 'done'])
    expect(events.at(-1)).toMatchObject({ type: 'done', truncated: false })
  })

  it('✅ 流自然结束但无终帧：保留正文，标 truncated，不伪装完整', async () => {
    fetchImpl = () => Promise.resolve(sseResponse(['event: delta\ndata:半截\n\n']))
    const { events, failure } = await run(REQUEST)
    expect(failure).toBeNull()
    expect(events.map((e) => e.type)).toEqual(['delta', 'done'])
    expect(events.at(-1)).toMatchObject({ type: 'done', truncated: true })
  })

  it('✅ 反证：半帧 "event: done\\n"（data 尚未到达）绝不能被当成正常终帧', async () => {
    fetchImpl = () =>
      Promise.resolve(sseResponse(['event: delta\ndata:半截\n\n', 'event: done\n']))
    const { events } = await run(REQUEST)
    expect(events.at(-1)).toMatchObject({ type: 'done', truncated: true })
  })

  it('✅ 200 空响应体不算成功：没有正文时如实报 EMPTY_STREAM', async () => {
    fetchImpl = () => Promise.resolve(emptyBodyResponse())
    const { events, failure } = await run(REQUEST)
    expect(events).toEqual([])
    expect(asFailure(failure).info.code).toBe('EMPTY_STREAM')
  })

  it('✅ SSE error 帧归一为 AgentChatFailure；组件级 status:error 不触发整轮失败', async () => {
    fetchImpl = () =>
      Promise.resolve(
        sseResponse([
          'event: delta\ndata:已到达的正文\n\n',
          'event: error\ndata: {"kind":"CONFIG_UNAVAILABLE","message":"Agent 没有已发布版本"}\n\n',
          'event: done\ndata: {}\n\n',
        ]),
      )
    const { events, failure } = await run(REQUEST)
    const info = asFailure(failure).info
    expect(info.code).toBe('CONFIG_UNAVAILABLE')
    expect(info.hint).toContain('已发布版本')
    // error 帧之后不再推事件：绝不给「失败后又成功收口」的假象。
    expect(events.map((e) => e.type)).toEqual(['delta'])

    fetchImpl = () =>
      Promise.resolve(
        sseResponse([
          'event: tool\ndata: {"toolCallId":"tc-1","toolName":"lookup","phase":"error","status":"error","error":"TOOL_TIMEOUT"}\n\n',
          'event: delta\ndata:降级回答\n\n',
          'event: done\ndata: {}\n\n',
        ]),
      )
    const degraded = await run(REQUEST)
    expect(degraded.failure).toBeNull()
    expect(degraded.events.some((e) => e.type === 'tool' && e.status === 'error')).toBe(true)
    expect(degraded.events.at(-1)).toMatchObject({ type: 'done', truncated: false })
  })

  it('✅ 未知错误归一化留可排查信息', () => {
    expect(describeAgentChatError(new Error('boom'))).toMatchObject({
      code: 'UNKNOWN',
      message: 'boom',
      retryable: true,
    })
  })
})

describe('协议 v1.1 · context.checkpoint 与 done 可选字段（M13 mock 只解析不上送）', () => {
  const VALID = {
    state: 'opaque-base64',
    upToTurn: 3,
    policyVersion: 'cp_v1',
    tier: 2,
    fallback: false,
    digest: 'sha256:abc',
  }

  it('✅ 合法 checkpoint 帧解码为第 14 事件，upToTurn 缺省时省略字段', () => {
    expect(decodeAgentFrame({ event: 'context.checkpoint', data: JSON.stringify(VALID) })).toMatchObject({
      type: 'context.checkpoint',
      state: 'opaque-base64',
      upToTurn: 3,
      policyVersion: 'cp_v1',
      tier: 2,
      fallback: false,
      digest: 'sha256:abc',
    })
    const { upToTurn: _ignored, ...withoutTurn } = VALID
    expect(decodeAgentFrame({ event: 'context.checkpoint', data: JSON.stringify(withoutTurn) })).not.toHaveProperty('upToTurn')
  })

  it('⛔ 形状不合法的 checkpoint 帧一律 fail-closed（不伪造压缩态）', () => {
    const invalid: Array<Record<string, unknown>> = [
      {},
      { ...VALID, state: '' },
      { ...VALID, policyVersion: '' },
      { ...VALID, digest: '' },
      { ...VALID, tier: 0 },
      { ...VALID, tier: 4 },
      { ...VALID, tier: 1.5 },
      { ...VALID, upToTurn: -1 },
      { ...VALID, upToTurn: 1.5 },
      { ...VALID, fallback: 'false' },
      { ...VALID, fallback: undefined },
      { ...VALID, state: 42 },
    ]
    for (const data of invalid) {
      expect(decodeAgentFrame({ event: 'context.checkpoint', data: JSON.stringify(data) })).toBeNull()
    }
    expect(decodeAgentFrame({ event: 'context.checkpoint', data: 'not-json' })).toBeNull()
  })

  it('✅ done 只追加可选字段；旧 `{}` 帧的解码结果零新增键', () => {
    const legacy = decodeAgentFrame({ event: 'done', data: '{}' })
    expect(Object.keys(legacy ?? {}).sort()).toEqual(['at', 'truncated', 'type'])
    expect(legacy).toMatchObject({ type: 'done', truncated: false })

    const v11 = decodeAgentFrame({
      event: 'done',
      data: '{"truncated":false,"contextStateVersion":"cp_v1"}',
    })
    expect(v11).toMatchObject({ type: 'done', truncated: false, contextStateVersion: 'cp_v1' })
    expect(decodeAgentFrame({ event: 'done', data: '{"contextStateVersion":""}' })).not.toHaveProperty('contextStateVersion')
    expect(decodeAgentFrame({ event: 'done', data: '{"contextStateVersion":42}' })).not.toHaveProperty('contextStateVersion')
  })

  it('✅ 线序 answer → context.checkpoint → done；done 仍是唯一应用层终帧，请求体无 contextState', async () => {
    fetchImpl = () =>
      Promise.resolve(
        sseResponse([
          'event: answer\ndata: {"text":"答案"}\n\n',
          `event: context.checkpoint\ndata: ${JSON.stringify(VALID)}\n\n`,
          'event: done\ndata: {"truncated":false,"contextStateVersion":"cp_v1"}\n\n',
        ]),
      )
    const { events, failure } = await run(REQUEST)
    expect(failure).toBeNull()
    expect(events.map((e) => e.type)).toEqual(['answer', 'context.checkpoint', 'done'])
    expect(events[1]).toMatchObject({ type: 'context.checkpoint', digest: 'sha256:abc' })
    expect(events[2]).toMatchObject({ type: 'done', contextStateVersion: 'cp_v1' })

    const body = JSON.parse(String(calls[0]?.init.body)) as Record<string, unknown>
    expect(body).not.toHaveProperty('contextState')
    expect(body).not.toHaveProperty('contextStateVersion')
  })
})
