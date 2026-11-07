/**
 * AI 对话的纯状态机（可单测、无 IO）。
 *
 * ⚠️ 三条纪律（对齐 `docs/前端问答功能规划.html` §4.3）：
 * <ol>
 *   <li><b>用户气泡永不因失败被删除</b>：失败只落在 assistant 位，
 *       用户还能看清自己问了什么、并原样重发。</li>
 *   <li><b>STOP ≠ FAILED</b>：用户主动停止是正常操作，不是系统故障；
 *       混同会让用户以为服务坏了。</li>
 *   <li><b>陈旧事件按 assistantId 丢弃</b>：停止/新建会话后，在途请求可能仍会
 *       推来一两个事件；reducer 只对「仍是 streaming 且 id 匹配」的那条生效，
 *       避免把上一轮回答贴到新一轮气泡上。</li>
 * </ol>
 *
 * 会话历史<b>只在内存</b>：平台控制台不落 sessionStorage / localStorage，
 * 避免把语料问答内容长期留在浏览器磁盘上。
 */

import type {
  ChatChannel,
  ChatErrorInfo,
  ChatHit,
  ChatStage,
  ChatStreamEvent,
} from '@/api/aiChatApi'

export type MessageStatus = 'streaming' | 'done' | 'stopped' | 'failed'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  status: MessageStatus
  content: string
  createdAt: number
  /** 已真实到达的阶段（不是预估进度） */
  stages?: ChatStage[]
  /** 模型思考过程（推理草稿，直接对话通道独有；折叠展示，仅管理员可见） */
  thinking?: string
  /**
   * 该气泡由哪条通道产生：只有 direct（真·LLM）才做 Markdown/公式渲染；
   * corpus 是 EXCERPT_ASSEMBLY 逐字摘录，任何重排都是对原文的篡改。
   */
  channel?: ChatChannel
  /** 检索阶段召回的片段，作为「依据已就绪」的证据 */
  evidence?: ChatHit[]
  primaryEdition?: string
  citations?: ChatHit[]
  /** 后端生成方式标识，必须随答案展示 */
  generator?: string
  disclaimer?: string
  flags?: { versionMismatchDetected: boolean; answerContentSuppressed: boolean }
  /** 依据不足：合法业务结果，不是故障 */
  noBasis?: 'NO_HITS' | 'GATE_SUPPRESSED'
  error?: ChatErrorInfo
  elapsedMs?: number
}

export interface ChatState {
  messages: ChatMessage[]
  sending: boolean
}

export const initialChatState: ChatState = { messages: [], sending: false }

export type ChatAction =
  | { type: 'SEND'; userId: string; assistantId: string; text: string; at: number; channel?: ChatChannel }
  | { type: 'STREAM'; id: string; event: ChatStreamEvent }
  | { type: 'FAIL'; id: string; error: ChatErrorInfo; at: number }
  | { type: 'STOP'; at: number }
  | { type: 'RETRY'; id: string; at: number }
  | { type: 'NEW_SESSION' }

/** 只更新指定 assistant 位；找不到 / 已不处于接收态就原样返回（防竞态）。 */
function patchStreaming(
  state: ChatState,
  id: string,
  patch: (m: ChatMessage) => ChatMessage,
): ChatState {
  const index = state.messages.findIndex((m) => m.id === id)
  if (index < 0) return state
  const target = state.messages[index]
  if (target === undefined || target.role !== 'assistant' || target.status !== 'streaming') {
    return state
  }
  const next = state.messages.slice()
  next[index] = patch(target)
  return { ...state, messages: next }
}

function applyStreamEvent(m: ChatMessage, event: ChatStreamEvent): ChatMessage {
  switch (event.type) {
    case 'stage':
      // 同值合并：重复到达不制造更多「阶段」，界面不出现假进度。
      if ((m.stages ?? []).includes(event.stage)) return m
      return { ...m, stages: [...(m.stages ?? []), event.stage] }
    case 'evidence':
      return { ...m, evidence: event.hits, primaryEdition: event.primaryEdition }
    case 'delta':
      // 真·LLM 逐 token 流：增量追加，且绝不回撤已收到的文本。
      return { ...m, content: m.content + event.text }
    case 'thinking':
      // 思考过程增量：与正文独立累积，互不干扰、互不回撤。
      return { ...m, thinking: (m.thinking ?? '') + event.text }
    case 'answer':
      return {
        ...m,
        // 直接对话的终帧是「兜底」而非覆盖：终帧 content 偶发为空/解析失败时，
        // 绝不回撤已经逐 token 收到的正文（delta 纪律：只追加，不回退）。
        content: event.answer !== '' ? event.answer : m.content,
        citations: event.citations,
        generator: event.generator,
        disclaimer: event.disclaimer,
        flags: {
          versionMismatchDetected: event.versionMismatchDetected,
          answerContentSuppressed: event.answerContentSuppressed,
        },
      }
    case 'no-basis':
      return { ...m, noBasis: event.reason }
    case 'done':
      return { ...m, status: 'done', elapsedMs: Math.max(event.at - m.createdAt, 0) }
  }
}

export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case 'SEND': {
      if (state.sending) return state
      const user: ChatMessage = {
        id: action.userId,
        role: 'user',
        status: 'done',
        content: action.text,
        createdAt: action.at,
      }
      const assistant: ChatMessage = {
        id: action.assistantId,
        role: 'assistant',
        status: 'streaming',
        content: '',
        createdAt: action.at,
        channel: action.channel,
        stages: [],
        thinking: '',
      }
      return { messages: [...state.messages, user, assistant], sending: true }
    }

    case 'STREAM': {
      const next = patchStreaming(state, action.id, (m) => applyStreamEvent(m, action.event))
      if (next === state) return state
      return { ...next, sending: action.event.type === 'done' ? false : next.sending }
    }

    case 'FAIL': {
      const next = patchStreaming(state, action.id, (m) => ({
        ...m,
        status: 'failed',
        error: action.error,
        elapsedMs: Math.max(action.at - m.createdAt, 0),
      }))
      if (next === state) return state
      return { ...next, sending: false }
    }

    case 'STOP': {
      let changed = false
      const messages = state.messages.map((m): ChatMessage => {
        if (m.role === 'assistant' && m.status === 'streaming') {
          changed = true
          return { ...m, status: 'stopped', elapsedMs: Math.max(action.at - m.createdAt, 0) }
        }
        return m
      })
      if (!changed) return state
      return { messages, sending: false }
    }

    case 'RETRY': {
      if (state.sending) return state
      const index = state.messages.findIndex((m) => m.id === action.id)
      const target = state.messages[index]
      if (target === undefined || target.role !== 'assistant' || target.status !== 'failed') {
        return state
      }
      const reset: ChatMessage = {
        id: target.id,
        role: 'assistant',
        status: 'streaming',
        content: '',
        createdAt: action.at,
        // 重试沿用原通道；否则切过通道后重试会把摘录当 LLM 输出重排。
        channel: target.channel,
        stages: [],
        thinking: '',
      }
      const next = state.messages.slice()
      next[index] = reset
      return { messages: next, sending: true }
    }

    case 'NEW_SESSION':
      return initialChatState
  }
}

/** 取最后一条失败回答所属的用户问题（供「重试」原样重发）。 */
export function lastFailedQuestion(state: ChatState): { assistantId: string; question: string } | null {
  for (let i = state.messages.length - 1; i >= 0; i -= 1) {
    const m = state.messages[i]
    if (m === undefined || m.role !== 'assistant' || m.status !== 'failed') continue
    for (let j = i - 1; j >= 0; j -= 1) {
      const prev = state.messages[j]
      if (prev !== undefined && prev.role === 'user') {
        return { assistantId: m.id, question: prev.content }
      }
    }
    return null
  }
  return null
}
