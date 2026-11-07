/**
 * 通用知识问答状态机契约测试（纯函数，无 IO）。
 *
 * ⛔ 对应三条纪律：用户气泡永不因失败被删、STOP ≠ FAILED、
 *    陈旧事件按 assistantId 丢弃。另有「终帧不得回撤增量」与
 *    「截断如实标记」两条流式特有性质。
 */

import { describe, it, expect } from 'vitest'
import {
  chatReducer,
  conversationHistory,
  initialChatState,
  lastFailedQuestion,
  questionBefore,
  type ChatState,
} from '@/features/chat/chatState'
import type { GeneralChatError } from '@/api/generalChatApi'

const ERROR: GeneralChatError = {
  code: 'RATE_LIMITED',
  message: '模型调用过于频繁',
  hint: '稍后重试',
  retryable: true,
}

function sent(): ChatState {
  return chatReducer(initialChatState, {
    type: 'SEND',
    userId: 'u1',
    assistantId: 'a1',
    text: '什么是条件概率',
    at: 1000,
  })
}

describe('通用问答状态机', () => {
  it('SEND 增加一问一答，并锁住在途', () => {
    const state = sent()
    expect(state.sending).toBe(true)
    expect(state.messages.map((m) => m.role)).toEqual(['user', 'assistant'])
    expect(state.messages[1]?.status).toBe('streaming')
    expect(questionBefore(state, 1)).toBe('什么是条件概率')
  })

  it('并发 SEND 被忽略（不产生第二条气泡）', () => {
    const state = chatReducer(sent(), {
      type: 'SEND',
      userId: 'u2',
      assistantId: 'a2',
      text: '另一句',
      at: 1100,
    })
    expect(state.messages).toHaveLength(2)
  })

  it('delta 只追加；终帧为空时不回撤已到达正文', () => {
    let state = sent()
    state = chatReducer(state, {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'delta', text: '你好' },
      at: 1100,
    })
    state = chatReducer(state, {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'delta', text: '世界' },
      at: 1200,
    })
    expect(state.messages[1]?.content).toBe('你好世界')

    state = chatReducer(state, {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'answer', text: '' },
      at: 1300,
    })
    expect(state.messages[1]?.content).toBe('你好世界')

    state = chatReducer(state, {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'answer', text: '终帧完整正文' },
      at: 1400,
    })
    expect(state.messages[1]?.content).toBe('终帧完整正文')
  })

  it('finish 释放发送锁，并如实携带 truncated', () => {
    const state = chatReducer(sent(), {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'finish', truncated: true },
      at: 2500,
    })
    expect(state.sending).toBe(false)
    expect(state.messages[1]).toMatchObject({
      status: 'done',
      truncated: true,
      elapsedMs: 1500,
    })
  })

  it('⛔ 陈旧事件（id 不匹配 / 已收口）一律丢弃', () => {
    let state = sent()
    state = chatReducer(state, {
      type: 'STREAM',
      id: 'old-id',
      event: { type: 'delta', text: '贴错气泡' },
      at: 1100,
    })
    expect(state.messages[1]?.content).toBe('')

    state = chatReducer(state, {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'finish', truncated: false },
      at: 1200,
    })
    state = chatReducer(state, {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'delta', text: '迟到的 token' },
      at: 1300,
    })
    expect(state.messages[1]?.content).toBe('')
    expect(state.sending).toBe(false)
  })

  it('⛔ FAIL 只落在 assistant 位，用户问题仍可原样重发', () => {
    const state = chatReducer(sent(), {
      type: 'FAIL',
      id: 'a1',
      error: ERROR,
      at: 2000,
    })
    expect(state.messages[0]?.content).toBe('什么是条件概率')
    expect(state.messages[1]).toMatchObject({ status: 'failed', error: ERROR })
    expect(state.sending).toBe(false)
    expect(lastFailedQuestion(state)).toEqual({
      assistantId: 'a1',
      question: '什么是条件概率',
    })
  })

  it('⛔ STOP 不是 FAILED，且停止后迟到事件不再生效', () => {
    let state = sent()
    state = chatReducer(state, {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'delta', text: '已收到' },
      at: 1100,
    })
    state = chatReducer(state, { type: 'STOP', at: 1500 })
    expect(state.messages[1]).toMatchObject({ status: 'stopped', elapsedMs: 500 })
    expect(state.messages[1]?.error).toBeUndefined()
    expect(state.sending).toBe(false)

    state = chatReducer(state, {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'delta', text: '迟到' },
      at: 1600,
    })
    expect(state.messages[1]?.content).toBe('已收到')
  })

  it('RETRY 复用原 assistant 位并清空半截正文', () => {
    let state = sent()
    state = chatReducer(state, {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'delta', text: '半截' },
      at: 1100,
    })
    state = chatReducer(state, { type: 'FAIL', id: 'a1', error: ERROR, at: 1200 })
    state = chatReducer(state, { type: 'RETRY', id: 'a1', at: 1300 })
    expect(state.messages).toHaveLength(2)
    expect(state.messages[1]).toMatchObject({
      id: 'a1',
      status: 'streaming',
      content: '',
    })
    expect(state.sending).toBe(true)
  })

  it('NEW_SESSION 清空内存会话', () => {
    expect(chatReducer(sent(), { type: 'NEW_SESSION' })).toEqual(initialChatState)
  })

  it('多轮历史只回传已收口且非空的问答对', () => {
    const state = chatReducer(sent(), {
      type: 'STREAM',
      id: 'a1',
      event: { type: 'finish', truncated: false },
      at: 1200,
    })
    const messages = [
      ...state.messages,
      {
        id: 'a2',
        role: 'assistant' as const,
        status: 'streaming' as const,
        content: '生成中的半截',
        createdAt: 1300,
      },
      {
        id: 'a3',
        role: 'assistant' as const,
        status: 'done' as const,
        content: '   ',
        createdAt: 1400,
      },
    ]
    expect(conversationHistory(messages)).toEqual([
      { role: 'user', content: '什么是条件概率' },
    ])
  })

  it('⛔ 重试时去掉末尾同一句 user，避免同一问题连续发两遍（FE-29）', () => {
    const state = chatReducer(sent(), {
      type: 'FAIL',
      id: 'a1',
      error: ERROR,
      at: 1200,
    })
    expect(conversationHistory(state.messages, '什么是条件概率')).toEqual([])
    // 更早的历史不受影响：只有「末尾恰好同句」才被去掉
    const withHistory = [
      ...state.messages,
      {
        id: 'u2',
        role: 'user' as const,
        status: 'done' as const,
        content: '另一句',
        createdAt: 1300,
      },
      {
        id: 'a2',
        role: 'assistant' as const,
        status: 'done' as const,
        content: '另一句的回答',
        createdAt: 1400,
      },
    ]
    // 末尾是 assistant 时一律不动，哪怕更早的历史里出现过同一句问题
    expect(conversationHistory(withHistory, '什么是条件概率')).toEqual([
      { role: 'user', content: '什么是条件概率' },
      { role: 'user', content: '另一句' },
      { role: 'assistant', content: '另一句的回答' },
    ])
  })
})
