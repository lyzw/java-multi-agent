/**
 * 租户前台 · 通用 Agent 编排对话的唯一状态入口。
 *
 * 与 admin-console/src/features/aiChat/useAgentChat.ts 同构：useReducer +
 * AbortController + mountedRef，不引入状态库。传输层只按到达顺序推事件，
 * hook 只负责投递给 reducer；换 live / sample 传输时本文件一行不用改。
 *
 * ⚠️ 卸载 / 停止 / 新会话都 abort 在途请求；stateRef 同步镜像让 retry 能读到最新消息；
 * controllerRef 身份比较保证旧请求的迟到事件与失败不写进新气泡。
 * ⛔ 会话历史只在内存，不写 sessionStorage / localStorage。
 */

import { useCallback, useEffect, useReducer, useRef } from 'react'
import {
  describeAgentChatError,
  isAgentChatCancelled,
  sendAgentChat,
  type AgentChatRequest,
} from '@/api/agentChatApi'
import {
  agentChatReducer,
  conversationTurns,
  currentContextCheckpoint,
  initialAgentChatState,
  lastFailedAgentQuestion,
  type AgentChatState,
} from '@/features/agentChat/agentChatState'

/**
 * 页面参数区给出的部分（message/history 由 hook 组装）。
 * ⚠️ contextState 刻意不开放给页面：只有 hook 能从 committed checkpoint 注入，
 * 否则页面把半截 / 伪造的 state 塞进请求会绕过状态机的提交闸门。
 */
export type AgentChatParams = Omit<AgentChatRequest, 'message' | 'history' | 'contextState'>

export interface UseAgentChat {
  state: AgentChatState
  send: (text: string, params: AgentChatParams) => void
  stop: () => void
  retry: (params: AgentChatParams) => void
  newSession: () => void
}

function makeId(prefix: string): string {
  const rand =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  return `${prefix}-${rand}`
}

export function useAgentChat(): UseAgentChat {
  const [state, dispatch] = useReducer(agentChatReducer, initialAgentChatState)
  const controllerRef = useRef<AbortController | null>(null)
  const mountedRef = useRef(true)
  const stateRef = useRef<AgentChatState>(state)
  stateRef.current = state

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      controllerRef.current?.abort()
      controllerRef.current = null
    }
  }, [])

  const run = useCallback(
    (text: string, params: AgentChatParams, assistantId: string, controller: AbortController): void => {
      void (async (): Promise<void> => {
        try {
          const committed = currentContextCheckpoint(stateRef.current)
          const request: AgentChatRequest = {
            ...params,
            message: text,
            // ⚠️ 只回传「已完成且未截断」的问答对；半截答案不能当下一轮上下文。
            history: conversationTurns(stateRef.current.messages),
            // ⚠️ S3-M13：只上送上一轮非截断 done 提交的 committed state；
            // pending / 截断 / 失败态绝不上送。首轮为 undefined，JSON 自动省略该键。
            contextState: committed?.state,
          }
          await sendAgentChat(request, {
            signal: controller.signal,
            onEvent: (event) => {
              // done 到达即释放「在途」锁：用户不必等微任务队列才能发下一句。
              if (event.type === 'done' && controllerRef.current === controller) {
                controllerRef.current = null
              }
              if (mountedRef.current) dispatch({ type: 'STREAM', id: assistantId, event })
            },
          })
        } catch (cause) {
          if (isAgentChatCancelled(cause)) return
          // 已被新请求接管：旧失败不许覆盖新气泡。
          if (controllerRef.current !== controller) return
          if (mountedRef.current) {
            dispatch({
              type: 'FAIL',
              id: assistantId,
              error: describeAgentChatError(cause),
              at: Date.now(),
            })
          }
        } finally {
          if (controllerRef.current === controller) controllerRef.current = null
        }
      })()
    },
    [],
  )

  const send = useCallback(
    (raw: string, params: AgentChatParams): void => {
      const text = raw.trim()
      if (text === '' || controllerRef.current !== null) return
      const controller = new AbortController()
      controllerRef.current = controller
      const at = Date.now()
      const userId = makeId('u')
      const assistantId = makeId('a')
      // ⚠️ id 在 dispatch 之前生成：reducer 异步生效，从 state 读回刚插入的气泡会晚一帧。
      dispatch({
        type: 'SEND',
        userId,
        assistantId,
        text,
        at,
        source: params.source,
        thinkingMode: params.thinkingMode,
      })
      run(text, params, assistantId, controller)
    },
    [run],
  )

  const stop = useCallback((): void => {
    const controller = controllerRef.current
    if (controller === null) return
    controllerRef.current = null
    controller.abort()
    // 不 dispatch FAIL：用户主动停止是正常操作。
    dispatch({ type: 'STOP', at: Date.now() })
  }, [])

  const retry = useCallback(
    (params: AgentChatParams): void => {
      const failed = lastFailedAgentQuestion(stateRef.current)
      if (failed === null || controllerRef.current !== null) return
      const controller = new AbortController()
      controllerRef.current = controller
      dispatch({ type: 'RETRY', id: failed.assistantId, at: Date.now() })
      run(failed.question, params, failed.assistantId, controller)
    },
    [run],
  )

  const newSession = useCallback((): void => {
    const controller = controllerRef.current
    controllerRef.current = null
    controller?.abort()
    dispatch({ type: 'NEW_SESSION' })
  }, [])

  return { state, send, stop, retry, newSession }
}
