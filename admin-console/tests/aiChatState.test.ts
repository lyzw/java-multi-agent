/**
 * AI 对话状态机纯函数测试。
 *
 * 反证重点：用户气泡不因失败消失、STOP ≠ FAILED、陈旧事件不得污染新气泡。
 */
import { describe, expect, it } from 'vitest'
import type { ChatStreamEvent } from '@/api/aiChatApi'
import {
  chatReducer,
  initialChatState,
  lastFailedQuestion,
  type ChatState,
} from '@/features/aiChat/chatState'

const HIT = {
  chunkId: 'c-1',
  text: '判别式小于零时方程无实数根',
  role: 'EXPLAIN',
  score: 0.71,
  edition: '数学·八年级·上册·人教版',
  locator: 'P42',
  channel: 'DENSE',
  reason: 'SEMANTIC_MATCH',
}

function sent(): ChatState {
  return chatReducer(initialChatState, {
    type: 'SEND',
    userId: 'u-1',
    assistantId: 'a-1',
    text: '判别式小于零是什么意思？',
    at: 1_000,
  })
}

function stream(state: ChatState, event: ChatStreamEvent): ChatState {
  return chatReducer(state, { type: 'STREAM', id: 'a-1', event })
}

describe('chatReducer · 发送与阶段增量', () => {
  it('✅ SEND 乐观插入用户气泡 + 空的接收中回答，sending=true', () => {
    const s = sent()
    expect(s.sending).toBe(true)
    expect(s.messages).toHaveLength(2)
    expect(s.messages[0]).toMatchObject({ role: 'user', status: 'done', content: '判别式小于零是什么意思？' })
    expect(s.messages[1]).toMatchObject({ role: 'assistant', status: 'streaming', content: '' })
  })

  it('✅ 阶段事件按真实到达顺序累积，同值不重复（不制造假进度）', () => {
    let s = stream(sent(), { type: 'stage', stage: 'retrieving', at: 1_010 })
    s = stream(s, { type: 'stage', stage: 'retrieving', at: 1_020 })
    s = stream(s, { type: 'evidence', hits: [HIT], primaryEdition: '人教版', at: 1_100 })
    s = stream(s, { type: 'stage', stage: 'generating', at: 1_110 })
    s = stream(s, {
      type: 'answer',
      answer: '判别式小于零时无实数根。',
      citations: [HIT],
      generator: 'EXCERPT_ASSEMBLY',
      disclaimer: '逐字摘录',
      versionMismatchDetected: false,
      answerContentSuppressed: true,
      at: 1_200,
    })
    s = stream(s, { type: 'done', at: 1_210 })

    const a = s.messages[1]
    expect(a?.stages).toEqual(['retrieving', 'generating'])
    expect(a?.evidence).toHaveLength(1)
    expect(a?.content).toBe('判别式小于零时无实数根。')
    expect(a?.generator).toBe('EXCERPT_ASSEMBLY')
    expect(a?.flags?.answerContentSuppressed).toBe(true)
    expect(a?.status).toBe('done')
    expect(a?.elapsedMs).toBe(210)
    expect(s.sending).toBe(false)
  })

  it('✅ no-basis 是合法业务结果，不是 failed', () => {
    let s = stream(sent(), { type: 'no-basis', reason: 'NO_HITS', at: 1_050 })
    s = stream(s, { type: 'done', at: 1_060 })
    expect(s.messages[1]).toMatchObject({ status: 'done', noBasis: 'NO_HITS' })
    expect(s.messages[1]?.error).toBeUndefined()
  })
})

describe('chatReducer · 异常与竞态', () => {
  const failed = (): ChatState =>
    chatReducer(sent(), {
      type: 'FAIL',
      id: 'a-1',
      error: { code: 'HTTP_429', message: '提问太频繁', hint: '稍后再试', retryable: true },
      at: 1_500,
    })

  it('✅ FAIL 保留用户气泡，错误只落在 assistant 位', () => {
    const s = failed()
    expect(s.messages[0]?.content).toBe('判别式小于零是什么意思？')
    expect(s.messages[1]).toMatchObject({ status: 'failed', error: { code: 'HTTP_429' } })
    expect(s.sending).toBe(false)
  })

  it('✅ STOP 标记 stopped 而非 failed，且不影响已完成的回答', () => {
    let s = stream(sent(), { type: 'stage', stage: 'retrieving', at: 1_010 })
    s = chatReducer(s, { type: 'STOP', at: 1_100 })
    expect(s.messages[1]?.status).toBe('stopped')
    expect(s.messages[1]?.error).toBeUndefined()
    expect(s.sending).toBe(false)
  })

  it('✅ 反证：STOP 之后迟到的事件不得写回已停止的气泡', () => {
    let s = chatReducer(sent(), { type: 'STOP', at: 1_100 })
    s = stream(s, { type: 'answer', answer: '迟到的回答', citations: [], generator: 'X', disclaimer: '', versionMismatchDetected: false, answerContentSuppressed: false, at: 1_200 })
    expect(s.messages[1]?.content).toBe('')
    expect(s.messages[1]?.status).toBe('stopped')
  })

  it('✅ 反证：NEW_SESSION 之后旧 assistantId 的事件无效', () => {
    let s = chatReducer(sent(), { type: 'NEW_SESSION' })
    s = stream(s, { type: 'stage', stage: 'retrieving', at: 1_010 })
    expect(s).toEqual(initialChatState)
  })

  it('✅ RETRY 原地重置失败气泡、保留用户问题，不新增重复用户气泡', () => {
    const s = chatReducer(failed(), { type: 'RETRY', id: 'a-1', at: 2_000 })
    expect(s.messages).toHaveLength(2)
    expect(s.messages[1]).toMatchObject({ status: 'streaming', content: '' })
    expect(s.messages[1]?.error).toBeUndefined()
    expect(s.sending).toBe(true)
  })

  it('✅ lastFailedQuestion 找到失败回答对应的原问题', () => {
    expect(lastFailedQuestion(failed())).toEqual({
      assistantId: 'a-1',
      question: '判别式小于零是什么意思？',
    })
    expect(lastFailedQuestion(sent())).toBeNull()
  })
})

describe('chatReducer · 真实 LLM 逐 token 流 (delta)', () => {
  it('✅ delta 逐 token 追加：你 → 好 → 世界，done 后 status=done、generator 随终帧带出', () => {
    let s = sent()
    s = stream(s, { type: 'stage', stage: 'generating', at: 1_010 })
    s = stream(s, { type: 'delta', text: '你', at: 1_020 })
    expect(s.messages[1]?.content).toBe('你')
    s = stream(s, { type: 'delta', text: '好', at: 1_030 })
    s = stream(s, { type: 'delta', text: '世界', at: 1_040 })
    expect(s.messages[1]?.content).toBe('你好世界')
    // 终帧：后端给出完整正文与生成方式标识（DIRECT_GENERATOR_LABEL 同源字段）。
    s = stream(s, {
      type: 'answer',
      answer: '你好世界',
      citations: [],
      generator: 'LLM·MiniMax-M2.5',
      disclaimer: '',
      versionMismatchDetected: false,
      answerContentSuppressed: false,
      at: 1_050,
    })
    expect(s.messages[1]?.content).toBe('你好世界')
    expect(s.messages[1]?.generator).toBe('LLM·MiniMax-M2.5')
    s = stream(s, { type: 'done', at: 1_060 })
    expect(s.messages[1]?.status).toBe('done')
    expect(s.sending).toBe(false)
  })

  it('✅ 反证：delta 是增量而非替换——已发文本不回撤', () => {
    let s = sent()
    s = stream(s, { type: 'delta', text: '已发前半', at: 1_010 })
    s = stream(s, { type: 'delta', text: '已发后半', at: 1_020 })
    expect(s.messages[1]?.content).toBe('已发前半已发后半')
  })

  it('✅ thinking 与正文独立累积，互不回撤、互不覆盖', () => {
    let s = sent()
    s = stream(s, { type: 'stage', stage: 'generating', at: 1_010 })
    s = stream(s, { type: 'thinking', text: '先算判别式', at: 1_015 })
    s = stream(s, { type: 'delta', text: '你', at: 1_020 })
    s = stream(s, { type: 'thinking', text: '再判根的情况', at: 1_030 })
    s = stream(s, { type: 'delta', text: '好世界', at: 1_040 })
    expect(s.messages[1]?.thinking).toBe('先算判别式再判根的情况')
    expect(s.messages[1]?.content).toBe('你好世界')
    // 反证：一次 thinking 事件只追加，不替换已累积的思考
    s = stream(s, { type: 'thinking', text: '末了核对一遍', at: 1_050 })
    expect(s.messages[1]?.thinking).toBe('先算判别式再判根的情况末了核对一遍')
  })
})
