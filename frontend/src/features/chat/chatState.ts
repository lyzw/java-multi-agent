/**
 * 通用知识问答的纯状态机（可单测、无 IO）。
 *
 * ⚠️ 三条纪律（与 admin-console AI 对话同源，别在这里开例外）：
 * <ol>
 *   <li><b>用户气泡永不因失败被删除</b>：失败只落在 assistant 位，
 *       用户还能看清自己问了什么、并原样重发。</li>
 *   <li><b>STOP ≠ FAILED</b>：用户主动停止是正常操作，不是系统故障。</li>
 *   <li><b>陈旧事件按 assistantId 丢弃</b>：停止/新建会话后，在途请求可能仍会
 *       推来一两个事件；reducer 只对「仍是 streaming 且 id 匹配」的那条生效。</li>
 * </ol>
 *
 * ⛔ 会话历史只在内存：不写 sessionStorage / localStorage。
 *    通用知识问答可能包含用户随手粘贴的内容，不长期留在浏览器磁盘上。
 */

import type {
  GeneralChatError,
  GeneralChatStreamEvent,
} from '@/api/generalChatApi'

export type ChatMessageStatus = 'streaming' | 'done' | 'stopped' | 'failed'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  status: ChatMessageStatus
  content: string
  createdAt: number
  /** 未收到应用层终帧：以上只是已到达的部分正文 */
  truncated?: boolean
  error?: GeneralChatError
  elapsedMs?: number
}

export interface ChatState {
  messages: ChatMessage[]
  sending: boolean
}

export const initialChatState: ChatState = { messages: [], sending: false }

export type ChatAction =
  | { type: 'SEND'; userId: string; assistantId: string; text: string; at: number }
  | { type: 'STREAM'; id: string; event: GeneralChatStreamEvent; at: number }
  | { type: 'FAIL'; id: string; error: GeneralChatError; at: number }
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

function applyStreamEvent(
  m: ChatMessage,
  event: GeneralChatStreamEvent,
  at: number,
): ChatMessage {
  switch (event.type) {
    case 'delta':
      // 逐 token 只追加，绝不回撤已收到的文本
      return { ...m, content: m.content + event.text }
    case 'answer':
      // 终帧是兜底而非覆盖：解析失败 / 空 content 时保留增量结果
      return event.text !== '' ? { ...m, content: event.text } : m
    case 'finish':
      return {
        ...m,
        status: 'done',
        truncated: event.truncated,
        elapsedMs: Math.max(at - m.createdAt, 0),
      }
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
      }
      return { messages: [...state.messages, user, assistant], sending: true }
    }

    case 'STREAM': {
      const next = patchStreaming(state, action.id, (m) =>
        applyStreamEvent(m, action.event, action.at),
      )
      if (next === state) return state
      return { ...next, sending: action.event.type === 'finish' ? false : next.sending }
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
      const next = state.messages.slice()
      next[index] = {
        id: target.id,
        role: 'assistant',
        status: 'streaming',
        content: '',
        createdAt: action.at,
      }
      return { messages: next, sending: true }
    }

    case 'NEW_SESSION':
      return initialChatState
  }
}

/** 取最后一条失败回答所属的用户问题（供「重试」原样重发）。 */
export function lastFailedQuestion(
  state: ChatState,
): { assistantId: string; question: string } | null {
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

/** 取某个 assistant 气泡之前最近的用户问题（供「换个问法」回填输入框）。 */
export function questionBefore(state: ChatState, index: number): string {
  for (let i = index - 1; i >= 0; i -= 1) {
    const m = state.messages[i]
    if (m !== undefined && m.role === 'user') return m.content
  }
  return ''
}

/**
 * 多轮上下文：只回传已收口的非空问答对（后端无状态，历史由本页维护）。
 *
 * @param omitTrailingUserQuestion 重试时传本次要重发的问题。
 *   ⚠️ 失败重试不会新增用户气泡，但历史里**已经有**那一条 user 消息；
 *      调用方随后还会把同一个问题拼到 messages 末尾。不去重就会把同一句
 *      用户问题连续发两遍，污染多轮上下文（问题记录 FE-29 同类缺陷）。
 *      这里只删「末尾恰好是同一句 user」这一种情形，不动更早的历史。
 */
export function conversationHistory(
  messages: ChatMessage[],
  omitTrailingUserQuestion?: string,
): {
  role: 'user' | 'assistant'
  content: string
}[] {
  const history = messages
    .filter(
      (m) =>
        m.content.trim() !== '' &&
        (m.status === 'done' || m.status === 'stopped'),
    )
    .map((m) => ({ role: m.role, content: m.content }))
  if (omitTrailingUserQuestion !== undefined) {
    const last = history[history.length - 1]
    if (
      last !== undefined &&
      last.role === 'user' &&
      last.content === omitTrailingUserQuestion
    ) {
      history.pop()
    }
  }
  return history
}
