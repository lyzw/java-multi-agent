// @vitest-environment jsdom
/**
 * useAiChat 全流程：发送 → 阶段增量 → 回答落地；失败 → 重试；停止；新会话。
 * 传输层用受控桩，验证的是 hook 的状态编排与竞态守卫，不是 HTTP 线格式
 * （线格式由 aiChatApi.test.ts 用真实 client.ts 覆盖）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { ApiError } from '@/api/client'
import { useAiChat, type ChatParams } from '@/features/aiChat/useAiChat'

vi.mock('@/api/aiChatApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/aiChatApi')>()
  return { ...actual, sendChatMessage: vi.fn() }
})

import {
  ChatCancelledError,
  DIRECT_GENERATOR_LABEL,
  sendChatMessage,
  type ChatRequest,
  type ChatStreamEvent,
} from '@/api/aiChatApi'

const PARAMS: ChatParams = {
  tenantId: 't-1',
  mode: 'EXPLAIN',
  edition: { subject: '数学', grade: '八年级', term: '上册', publisher: '人教版', revisionCode: '2024' },
  scopeMode: 'STRICT',
  topK: 8,
}

function successScript(): ChatStreamEvent[] {
  return [
    { type: 'stage', stage: 'retrieving', at: 1 },
    { type: 'evidence', hits: [], primaryEdition: '人教版', at: 2 },
    { type: 'stage', stage: 'generating', at: 3 },
    {
      type: 'answer',
      answer: '判别式小于零时无实数根。',
      citations: [],
      generator: 'EXCERPT_ASSEMBLY',
      disclaimer: '逐字摘录',
      versionMismatchDetected: false,
      answerContentSuppressed: false,
      at: 4,
    },
    { type: 'done', at: 5 },
  ]
}

function scripted(events: ChatStreamEvent[]): void {
  vi.mocked(sendChatMessage).mockImplementation(async (_params, ctx) => {
    for (const event of events) ctx.onEvent(event)
  })
}

beforeEach(() => {
  vi.mocked(sendChatMessage).mockReset()
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('useAiChat · 正常闭环', () => {
  it('✅ 发送 → 阶段增量 → 回答落地，done 后释放发送锁', async () => {
    // 用受控 latch 卡住传输层，才能真正断言「接收中」这个中间态；
    // 立即 resolve 的 async 桩会被 act 的微任务批处理吞掉中间态。
    let finish: (() => void) | null = null
    vi.mocked(sendChatMessage).mockImplementation(
      (_params, ctx) =>
        new Promise<void>((resolve) => {
          ctx.onEvent({ type: 'stage', stage: 'retrieving', at: 1 })
          ctx.onEvent({ type: 'evidence', hits: [], primaryEdition: '人教版', at: 2 })
          finish = () => {
            for (const event of successScript().slice(2)) ctx.onEvent(event)
            resolve()
          }
        }),
    )
    const { result } = renderHook(() => useAiChat())

    act(() => result.current.send('判别式小于零是什么意思？', PARAMS))
    expect(result.current.state.messages).toHaveLength(2)
    expect(result.current.state.sending).toBe(true)
    expect(result.current.state.messages[1]?.stages).toEqual(['retrieving'])

    await act(async () => {
      finish?.()
    })
    expect(result.current.state.sending).toBe(false)
    const a = result.current.state.messages[1]
    expect(a?.content).toBe('判别式小于零时无实数根。')
    expect(a?.stages).toEqual(['retrieving', 'generating'])
    expect(a?.generator).toBe('EXCERPT_ASSEMBLY')
    expect(a?.status).toBe('done')
    expect(result.current.state.messages[0]?.content).toBe('判别式小于零是什么意思？')

    // 反证：done 之后必须能立刻再发一句（在途锁已被释放）
    act(() => result.current.send('再问一句', PARAMS))
    expect(result.current.state.messages).toHaveLength(4)
  })

  it('✅ 空输入 / 纯空白本地拦下，不发请求', () => {
    scripted(successScript())
    const { result } = renderHook(() => useAiChat())
    act(() => result.current.send('   ', PARAMS))
    expect(result.current.state.messages).toHaveLength(0)
    expect(sendChatMessage).not.toHaveBeenCalled()
  })

  it('✅ 等待期间第二次 send 被在途锁拦下，不产生重复请求', async () => {
    vi.mocked(sendChatMessage).mockImplementation(
      (_params, ctx) =>
        new Promise<void>((resolve) => {
          ctx.signal.addEventListener('abort', () => resolve(), { once: true })
        }),
    )
    const { result } = renderHook(() => useAiChat())
    act(() => result.current.send('第一句', PARAMS))
    act(() => result.current.send('第二句', PARAMS))
    expect(sendChatMessage).toHaveBeenCalledTimes(1)
    expect(result.current.state.messages).toHaveLength(2)
    act(() => result.current.stop())
  })
})

describe('useAiChat · 异常与主动操作', () => {
  it('✅ 失败保留用户气泡，重试原样重发同一个问题', async () => {
    vi.mocked(sendChatMessage).mockRejectedValueOnce(
      new ApiError(429, '请求失败（429）', undefined, 'RATE_LIMITED'),
    )
    const { result } = renderHook(() => useAiChat())
    act(() => result.current.send('原题', PARAMS))
    await waitFor(() => expect(result.current.state.messages[1]?.status).toBe('failed'))
    expect(result.current.state.messages[0]?.content).toBe('原题')
    expect(result.current.state.messages[1]?.error?.code).toBe('RATE_LIMITED')

    scripted(successScript())
    act(() => result.current.retry(PARAMS))
    await waitFor(() => expect(result.current.state.messages[1]?.status).toBe('done'))
    expect(result.current.state.messages).toHaveLength(2)
    expect(vi.mocked(sendChatMessage).mock.calls[1]?.[0].query).toBe('原题')
  })

  it('✅ 停止标 stopped，不标 failed；取消异常不覆盖状态', async () => {
    vi.mocked(sendChatMessage).mockImplementation(
      (_params, ctx) =>
        new Promise<void>((_resolve, reject) => {
          ctx.signal.addEventListener('abort', () => reject(new ChatCancelledError()), { once: true })
        }),
    )
    const { result } = renderHook(() => useAiChat())
    act(() => result.current.send('一个问题', PARAMS))
    act(() => result.current.stop())
    await waitFor(() => expect(result.current.state.sending).toBe(false))
    expect(result.current.state.messages[1]?.status).toBe('stopped')
    expect(result.current.state.messages[1]?.error).toBeUndefined()
  })

  it('✅ 新会话清空消息，旧请求的迟到事件不回写', async () => {
    let emitLate: ((e: ChatStreamEvent) => void) | null = null
    vi.mocked(sendChatMessage).mockImplementation(
      (_params, ctx) =>
        new Promise<void>(() => {
          emitLate = ctx.onEvent
        }),
    )
    const { result } = renderHook(() => useAiChat())
    act(() => result.current.send('旧问题', PARAMS))
    act(() => result.current.newSession())
    expect(result.current.state.messages).toHaveLength(0)

    await act(async () => {
      emitLate?.({
        type: 'answer',
        answer: '迟到的回答',
        citations: [],
        generator: 'X',
        disclaimer: '',
        versionMismatchDetected: false,
        answerContentSuppressed: false,
        at: 9,
      })
    })
    expect(result.current.state.messages).toHaveLength(0)
  })
})

describe('useAiChat · 直接对话通道 (history 注入)', () => {
  const DIRECT_PARAMS: ChatParams = { ...PARAMS, channel: 'direct' }
  const captured: ChatRequest[] = []

  beforeEach(() => {
    captured.length = 0
    vi.mocked(sendChatMessage).mockImplementation(async (params, ctx) => {
      captured.push(params)
      ctx.onEvent({ type: 'stage', stage: 'generating', at: 1 })
      ctx.onEvent({ type: 'delta', text: '答', at: 2 })
      ctx.onEvent({
        type: 'answer',
        answer: '答',
        citations: [],
        generator: DIRECT_GENERATOR_LABEL,
        disclaimer: '',
        versionMismatchDetected: false,
        answerContentSuppressed: false,
        at: 3,
      })
      ctx.onEvent({ type: 'done', at: 4 })
    })
  })

  it('✅ 多轮上下文由前端维护：第二句带上第一句的问答历史，且不含本句 query', async () => {
    const { result } = renderHook(() => useAiChat())

    act(() => result.current.send('第一问', DIRECT_PARAMS))
    await waitFor(() => expect(result.current.state.messages[1]?.status).toBe('done'))
    // 首轮无历史：request.history 是空数组（占位也行，后端按空处理）
    expect(captured[0]?.history).toHaveLength(0)

    act(() => result.current.send('第二问', DIRECT_PARAMS))
    await waitFor(() => expect(result.current.state.messages[3]?.status).toBe('done'))

    expect(captured[1]?.history).toEqual([
      { role: 'user', content: '第一问' },
      { role: 'assistant', content: '答' },
    ])
    // 本句问题自身由 transport 拼到 messages 末尾，不进 history
    expect(captured[1]?.query).toBe('第二问')
    const sentMessages = (captured[1]?.history ?? []).length
    expect(sentMessages).toBe(2)
  })
})
