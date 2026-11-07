/**
 * 通用知识问答的唯一状态入口：发送 / 停止 / 重试 / 新建会话。
 *
 * ⚠️ 不引入状态库：问答状态是页面局部状态，`useReducer` + 一个 hook 足够。
 * ⚠️ 卸载 / 停止 / 新建会话都会 abort 在途请求；`mountedRef` 与
 *    `controllerRef` 双守卫保证「旧请求的迟到事件」不会写进新气泡或卸载后的组件。
 *    catch 里先判取消：用户主动停止不落 failed 态。
 */

import { useCallback, useEffect, useReducer, useRef } from 'react'
import {
  describeGeneralChatError,
  isGeneralChatCancelled,
  streamGeneralChat,
  type GeneralChatRequest,
} from '@/api/generalChatApi'
import {
  chatReducer,
  conversationHistory,
  initialChatState,
  lastFailedQuestion,
  type ChatState,
} from '@/features/chat/chatState'

/** 页面参数区给出的部分（query 来自输入框）。 */
export interface GeneralChatParams {
  systemPrompt?: string
}

export interface UseGeneralChat {
  state: ChatState
  send: (text: string, params: GeneralChatParams) => void
  stop: () => void
  retry: (params: GeneralChatParams) => void
  newSession: () => void
}

function makeId(prefix: string): string {
  const rand =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  return `${prefix}-${rand}`
}

export function useGeneralChat(): UseGeneralChat {
  const [state, dispatch] = useReducer(chatReducer, initialChatState)
  const controllerRef = useRef<AbortController | null>(null)
  const mountedRef = useRef(true)
  /** 同步镜像：retry / 多轮历史要在事件回调里读到最新消息列表，不能等 useEffect。 */
  const stateRef = useRef<ChatState>(state)
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
    (
      text: string,
      params: GeneralChatParams,
      assistantId: string,
      controller: AbortController,
      omitTrailingUserQuestion?: string,
    ): void => {
      void (async (): Promise<void> => {
        try {
          // ⚠️ 多轮上下文整体回传（后端无状态）：只取已收口的问答对，
          //    避免把正在生成中的半截回答当作历史喂给模型。
          //    重试时额外去掉「已在上一条失败轮次里出现过的同一句 user」，
          //    否则本函数末尾又拼一次会重复（FE-29 上下文污染）。
          const messages = conversationHistory(
            stateRef.current.messages,
            omitTrailingUserQuestion,
          )
          const request: GeneralChatRequest = {
            messages: [...messages, { role: 'user', content: text }],
            ...(params.systemPrompt !== undefined
              ? { systemPrompt: params.systemPrompt }
              : {}),
          }
          await streamGeneralChat(request, {
            signal: controller.signal,
            onEvent: (event) => {
              // 终帧到达即释放「在途」锁：用户不必等微任务队列才能发下一句。
              if (event.type === 'finish' && controllerRef.current === controller) {
                controllerRef.current = null
              }
              if (mountedRef.current) {
                dispatch({ type: 'STREAM', id: assistantId, event, at: Date.now() })
              }
            },
          })
        } catch (cause) {
          if (isGeneralChatCancelled(cause)) return
          // 已被新请求接管：旧失败不许覆盖新气泡。
          if (controllerRef.current !== controller) return
          if (mountedRef.current) {
            dispatch({
              type: 'FAIL',
              id: assistantId,
              error: describeGeneralChatError(cause),
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
    (raw: string, params: GeneralChatParams): void => {
      const text = raw.trim()
      // 空输入本地拦下（按钮本就禁用，这里是并发兜底），不发请求。
      if (text === '' || controllerRef.current !== null) return
      const controller = new AbortController()
      controllerRef.current = controller
      const at = Date.now()
      const userId = makeId('u')
      const assistantId = makeId('a')
      dispatch({ type: 'SEND', userId, assistantId, text, at })
      // ⚠️ id 必须在 dispatch 之前生成：reducer 异步生效，
      //    从 state 里「读回刚插入的气泡」会晚一帧，流式事件就贴不上了。
      run(text, params, assistantId, controller)
    },
    [run],
  )

  const stop = useCallback((): void => {
    const controller = controllerRef.current
    if (controller === null) return
    controllerRef.current = null
    controller.abort()
    dispatch({ type: 'STOP', at: Date.now() })
  }, [])

  const retry = useCallback(
    (params: GeneralChatParams): void => {
      const failed = lastFailedQuestion(stateRef.current)
      if (failed === null || controllerRef.current !== null) return
      const controller = new AbortController()
      controllerRef.current = controller
      dispatch({ type: 'RETRY', id: failed.assistantId, at: Date.now() })
      run(failed.question, params, failed.assistantId, controller, failed.question)
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
