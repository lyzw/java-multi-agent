/**
 * 通用知识问答（租户前台 · 真·LLM 多轮 SSE 对话）。
 *
 * <p><b>这一页是什么</b>：不挂教材语料、不读任何学生 / Run 数据的自由问答，
 * 用来处理通用概念、跨学科常识、知识梳理一类问题。回答逐 token 实时呈现，
 * 输入 → 发送 → 流式接收 → 终帧收口，以及停止 / 重试 / 新建会话 /
 * 失败 / 超时 / 限流 / 断流截断等分支全部有真实状态。
 *
 * <p><b>⚠️ 诚实边界（必须写在界面上，不藏在文档里）</b>：
 * <ol>
 *   <li><b>不是教材语料问答</b>：不检索 RAG、没有引用与出处，回答由模型生成、
 *       可能有误。需要可溯源依据请走「知识检索」页。</li>
 *   <li><b>不读写租户业务数据</b>：输入只来自本次键入与本页内存里的多轮历史，
 *       后端不读取任何 Run / 学习画像 / 学生信息。</li>
 *   <li><b>不伪造进度</b>：{@code delta} 来自真实 token 帧，没有打字机特效、
 *       没有百分比；等待期只显示「正在生成回答」。断流如实标「截断」，
 *       不把半截回答伪装成完整答案。</li>
 *   <li><b>会话只在内存</b>：不写 sessionStorage / localStorage，刷新即清空。</li>
 * </ol>
 *
 * <p><b>技术栈</b>：React 18 + TypeScript strict，零新增依赖；
 * 流式传输在 {@code api/generalChatApi.ts}，状态机在 {@code features/chat/chatState.ts}。
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { PageHead, Panel, StatusPill } from '@/components/ui'
import { scanText } from '@/features/compliance/scan'
import {
  questionBefore,
  type ChatMessage,
  type ChatState,
} from '@/features/chat/chatState'
import {
  useGeneralChat,
  type GeneralChatParams,
} from '@/features/chat/useGeneralChat'

/** 场景预设：系统提示词是前端常量，不接受用户自由输入（避免把提示词当输入框）。 */
const SCENES: { id: string; label: string; prompt: string; hint: string }[] = [
  {
    id: 'free',
    label: '自由问答',
    prompt: '',
    hint: '不加额外提示词，按原问题回答。',
  },
  {
    id: 'concept',
    label: '概念讲解',
    prompt: '用初中生能理解的语言解释概念，先给直觉再给定义，最后举一个生活里的例子。',
    hint: '适合「是什么 / 为什么」类问题。',
  },
  {
    id: 'outline',
    label: '知识梳理',
    prompt: '把问题涉及的知识点分条梳理，标出前置知识与常见混淆点，不要写成流水账。',
    hint: '适合把零散常识整理成结构。',
  },
  {
    id: 'misconception',
    label: '易错辨析',
    prompt: '聚焦该问题最常见的误解与易错点，逐条说明「错在哪、正确的理解是什么」。',
    hint: '适合区分相近概念。',
  },
]

const SAMPLE_QUESTIONS = [
  '用一句话解释条件概率，并举一个生活里的例子',
  '牛顿第三定律和二力平衡有什么区别？',
  '帮我梳理「函数」这个概念的知识脉络',
  '为什么天空是蓝色的？请用初中生能懂的话说明',
]

const NEAR_BOTTOM_PX = 80
const MAX_INPUT_LENGTH = 1000

/**
 * 行样式查表：⛔ 不写 `gchat__row--${role}` 模板串 ——
 * css.integrity 会从中析出悬空的 `gchat__row--` 令牌并误报「未定义」。
 */
const ROW_CLASS: Record<'user' | 'assistant', string> = {
  user: 'gchat__row gchat__row--user',
  assistant: 'gchat__row gchat__row--assistant',
}

function timeLabel(at: number): string {
  return new Date(at).toLocaleTimeString('zh-CN', { hour12: false })
}

interface AssistantBodyProps {
  message: ChatMessage
  question: string
  onRetry: () => void
  onRephrase: (question: string) => void
}

/** 单条 assistant 气泡：等待 → 正文 → 截断/失败/停止 → 脚注。 */
function AssistantBody({
  message,
  question,
  onRetry,
  onRephrase,
}: AssistantBodyProps): JSX.Element {
  const streaming = message.status === 'streaming'

  return (
    <div>
      {streaming && message.content === '' && (
        <div className="gchat__waiting" role="status">
          正在生成回答…
        </div>
      )}

      {message.content !== '' && (
        <div className="gchat__text" aria-label="回答正文">
          {message.content}
        </div>
      )}

      {message.truncated === true && (
        <div className="gchat__truncated" role="status">
          连接在未收到终帧时结束，以上仅为已到达的部分内容；可「换个问法」重新提问。
        </div>
      )}

      {message.error !== undefined && (
        <div className="gchat__error" role="alert">
          <strong>{message.error.message}</strong>
          <span className="gchat__code">{message.error.code}</span>
          <p>{message.error.hint}</p>
          <div className="gchat__error-actions">
            {message.error.retryable && (
              <button type="button" className="btn" onClick={onRetry}>
                重试
              </button>
            )}
            <button
              type="button"
              className="btn btn--quiet"
              onClick={() => onRephrase(question)}
            >
              换个问法
            </button>
          </div>
        </div>
      )}

      {message.status === 'stopped' && (
        <p className="gchat__stopped">
          已停止（你主动中止了本次回答，不是系统故障）。
        </p>
      )}

      {message.status !== 'failed' && (
        <p className="gchat__footnote">
          通用知识问答 · 模型生成，未经教材语料核验；会话只保留在内存中。
        </p>
      )}
    </div>
  )
}

interface MessageListProps {
  state: ChatState
  onRetry: () => void
  onRephrase: (question: string) => void
  onSample: (question: string) => void
}

function MessageList({
  state,
  onRetry,
  onRephrase,
  onSample,
}: MessageListProps): JSX.Element {
  if (state.messages.length === 0) {
    return (
      <div className="gchat__empty">
        <p className="gchat__empty-title">还没有提问。选一个示例开始，或直接输入问题。</p>
        <div className="gchat__samples">
          {SAMPLE_QUESTIONS.map((q) => (
            <button
              key={q}
              type="button"
              className="btn btn--quiet"
              onClick={() => onSample(q)}
            >
              {q}
            </button>
          ))}
        </div>
      </div>
    )
  }

  return (
    <>
      {state.messages.map((m, index) => (
        <div
          key={m.id}
          className={ROW_CLASS[m.role]}
          data-testid={`gchat-row-${m.role}`}
        >
          <div className="gchat__bubble">
            <div className="gchat__meta">
              <span>{m.role === 'user' ? '你' : 'AI 问答'}</span>
              <span>{timeLabel(m.createdAt)}</span>
              {m.role === 'assistant' && m.elapsedMs !== undefined && (
                <span>{(m.elapsedMs / 1000).toFixed(1)}s</span>
              )}
              {m.role === 'assistant' && m.status === 'streaming' && (
                <StatusPill tone="info">接收中</StatusPill>
              )}
              {m.role === 'assistant' && m.status === 'failed' && (
                <StatusPill tone="danger">失败</StatusPill>
              )}
            </div>
            {m.role === 'user' ? (
              <div className="gchat__text">{m.content}</div>
            ) : (
              <AssistantBody
                message={m}
                question={questionBefore(state, index)}
                onRetry={onRetry}
                onRephrase={onRephrase}
              />
            )}
          </div>
        </div>
      ))}
    </>
  )
}

export function GeneralChatPage(): JSX.Element {
  const [sceneId, setSceneId] = useState('free')
  const [draft, setDraft] = useState('')
  const [unread, setUnread] = useState(0)
  const listRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLTextAreaElement | null>(null)
  const forceBottomRef = useRef(false)
  const { state, send, stop, retry, newSession } = useGeneralChat()

  const scene = useMemo(
    () => SCENES.find((s) => s.id === sceneId) ?? SCENES[0],
    [sceneId],
  )
  const params = useMemo<GeneralChatParams>(
    () => (scene !== undefined && scene.prompt !== '' ? { systemPrompt: scene.prompt } : {}),
    [scene],
  )

  const violations = useMemo(() => scanText(draft), [draft])
  const hasSensitive = violations.length > 0
  const canSend = draft.trim() !== '' && !state.sending && !hasSensitive

  /** 自动跟随：仅当用户仍在底部附近；上翻阅读时改为「回到底部」胶囊。 */
  const last = state.messages[state.messages.length - 1]
  const scrollSignature = `${state.messages.length}:${last?.status ?? ''}:${
    last?.content.length ?? 0
  }:${last?.error?.code ?? ''}:${last?.truncated ?? false}`
  useEffect(() => {
    const el = listRef.current
    if (el === null) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    if (forceBottomRef.current || distance < 160) {
      el.scrollTop = el.scrollHeight
      forceBottomRef.current = false
      setUnread(0)
    } else {
      setUnread((n) => n + 1)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scrollSignature])

  const handleScroll = useCallback((): void => {
    const el = listRef.current
    if (el === null) return
    if (el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX) setUnread(0)
  }, [])

  const scrollToBottom = useCallback((): void => {
    const el = listRef.current
    if (el !== null) el.scrollTop = el.scrollHeight
    setUnread(0)
  }, [])

  const handleSend = useCallback((): void => {
    if (!canSend) return
    forceBottomRef.current = true
    send(draft, params)
    setDraft('')
  }, [canSend, draft, params, send])

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLTextAreaElement>): void => {
      // ⚠️ 中文输入法组合期间按 Enter 是「选字」，不能当发送。
      if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
        event.preventDefault()
        handleSend()
      }
    },
    [handleSend],
  )

  const handleRephrase = useCallback((question: string): void => {
    setDraft(question)
    forceBottomRef.current = true
    window.setTimeout(() => inputRef.current?.focus(), 0)
  }, [])

  const handleNewSession = useCallback((): void => {
    newSession()
    setDraft('')
    setUnread(0)
  }, [newSession])

  return (
    <>
      <PageHead
        title="通用知识问答"
        eyebrow="AI 对话"
        lead="不挂教材语料的自由问答：逐 token 流式呈现，多轮上下文由本页维护。需要可溯源依据请走「知识检索」。"
      />

      <div className="gchat__notice marked marked--calm">
        <span className="marked__label">这条链路的诚实边界</span>
        <ul className="gchat__notice-list">
          <li>
            <b>不是教材语料问答</b>：不检索 RAG、没有引用与出处，回答由模型生成、可能有误，请自行核对。
          </li>
          <li>
            <b>不读写业务数据</b>：输入只来自本次键入与本页内存里的多轮历史，后端不读取任何 Run / 学习画像 / 学生信息。
          </li>
          <li>
            <b>不伪造进度</b>：文字来自真实 token 帧，没有打字机特效；断流会如实标注「部分内容」，不伪装成完整答案。
          </li>
          <li>
            <b>会话只在内存</b>：不写入浏览器存储，刷新即清空；推理草稿按设计不下发到租户通道。
          </li>
        </ul>
      </div>

      <Panel title="对话设置" note="系统提示词为本页预设常量，不接受自由输入。">
        <div className="gchat__toolbar">
          <label className="gchat__field" htmlFor="gchat-scene">
            <span>回答场景</span>
            <select
              id="gchat-scene"
              className="gchat__select"
              value={sceneId}
              onChange={(e) => setSceneId(e.target.value)}
            >
              {SCENES.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <span className="gchat__hint">{scene?.hint ?? ''}</span>
          <span className="gchat__hint">通道：租户 SSE · 逐 token 流式</span>
        </div>
      </Panel>

      <div className="gchat">
        <div
          className="gchat__messages"
          ref={listRef}
          onScroll={handleScroll}
          role="log"
          aria-live="polite"
          aria-label="对话消息列表"
        >
          <MessageList
            state={state}
            onRetry={() => retry(params)}
            onRephrase={handleRephrase}
            onSample={handleRephrase}
          />
        </div>

        {unread > 0 && (
          <button
            type="button"
            className="gchat__scroll-pill btn btn--quiet"
            onClick={scrollToBottom}
          >
            回到底部{unread > 1 ? `（${unread} 条新内容）` : ''}
          </button>
        )}

        <div className="gchat__composer">
          <textarea
            ref={inputRef}
            className="gchat__input"
            rows={2}
            value={draft}
            maxLength={MAX_INPUT_LENGTH}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="输入问题…（Enter 发送，Shift+Enter 换行；等待期间仍可继续输入）"
            aria-label="问题输入"
          />
          {hasSensitive && (
            <p className="gchat__sensitive" role="alert">
              输入包含不可采集的信息，请修改后再发送（本地预检，不回显命中内容）。
            </p>
          )}
          <div className="gchat__actions">
            <div className="gchat__actions-group">
              <button
                type="button"
                className="btn"
                disabled={!canSend}
                aria-busy={state.sending}
                onClick={handleSend}
              >
                发送
              </button>
              <button
                type="button"
                className="btn btn--quiet"
                disabled={!state.sending}
                onClick={stop}
              >
                停止
              </button>
              <button
                type="button"
                className="btn btn--quiet"
                disabled={state.messages.length === 0}
                onClick={handleNewSession}
              >
                新会话
              </button>
            </div>
            <span className="gchat__count">
              共 {state.messages.length} 条 · 会话仅存内存 · 上限 {MAX_INPUT_LENGTH} 字
            </span>
          </div>
        </div>
      </div>
    </>
  )
}
