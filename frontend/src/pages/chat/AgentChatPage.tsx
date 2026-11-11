/**
 * 租户前台 · 通用 Agent 编排对话（SSE）。
 *
 * <p><b>这一页是什么</b>：在一次请求里可观测「路由 → RAG 检索/注入 → Skill → Tool →
 * 结果回传 → 流式回答 → 用量/终帧」的通用 Agent 聊天。每条时间线都来自一帧真实到达的
 * SSE 事件，不合成进度、不打字机、不百分比。
 *
 * <p><b>⚠️ 诚实边界（写在界面上，不藏在文档里）</b>：
 * <ol>
 *   <li><b>真实端点已接入编排式 SSE</b>。真实模式请求租户链
 *       {@code POST /api/knowledge/agent/chat/stream}，服务端完成路由 → RAG → Skill/Tool → 流式回答 + Run 审计；
 *       端点未部署时 404/501 明示「能力未接入」，<b>绝不</b>退化成假流。</li>
 *   <li><b>样例回放不是真实模型输出</b>。脚本构造协议帧 → 序列化为 SSE 文本 →
 *       走与真实模式完全相同的解析器 + reducer + UI；页面顶部与每条样例回答都醒目标注。</li>
 *   <li><b>推理草稿不下发</b>：租户通道不接收也不展示原始 thinking 文本。
 *       「thinking 模式」在这里的含义是<b>展开组件执行过程</b>，不是展示模型草稿。</li>
 *   <li><b>会话只在内存</b>：不写 sessionStorage / localStorage，刷新即清空。
 *       失败 / 停止 / 截断分别标记，不互相冒充。</li>
 * </ol>
 *
 * <p>技术栈沿用租户前台：零 UI 库，PageHead / Panel / StatusPill / Icon + 主样式层类。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { Link } from 'react-router-dom'
import { Icon, type IconName } from '@/components/Icon'
import { PageHead, Panel, StatusPill, type PillTone } from '@/components/ui'
import { scanText } from '@/features/compliance/scan'
import {
  AGENT_CHAT_PATH,
  AGENT_SAMPLE_TRANSPORT_ID,
  type AgentChatSource,
  type AgentStepStatus,
  type SampleScenario,
} from '@/api/agentChatApi'
import type {
  AgentChatMessage,
  AgentStepKind,
  AgentTraceStep,
} from '@/features/agentChat/agentChatState'
import { useAgentChat, type AgentChatParams } from '@/features/agentChat/useAgentChat'

const NEAR_BOTTOM_PX = 80
const MAX_INPUT_LENGTH = 1000

const SOURCE_LABEL: Record<AgentChatSource, string> = {
  live: '真实端点',
  sample: '样例回放',
}

const SCENARIOS: { value: SampleScenario; label: string }[] = [
  { value: 'full', label: '完整链路' },
  { value: 'skill-error', label: 'Skill 失败（Agent 降级）' },
  { value: 'tool-error', label: 'Tool 超时（结果回传降级）' },
  { value: 'rag-empty', label: 'RAG 零命中（合法结果）' },
]

const SAMPLE_QUESTIONS = [
  '一元二次方程判别式小于零是什么意思？',
  '二次函数的顶点式怎么推导？',
  '勾股定理适用于哪些三角形？',
]

/** ⛔ 不写 `gchat__row--${role}` 模板串：css.integrity 会析出悬空令牌。 */
const ROW_CLASS: Record<'user' | 'assistant', string> = {
  user: 'gchat__row gchat__row--user',
  assistant: 'gchat__row gchat__row--assistant',
}

const STEP_ICONS: Record<AgentStepKind, IconName> = {
  route: 'cpu',
  rag: 'book',
  skill: 'layers',
  tool: 'wrench',
  handoff: 'refresh',
  usage: 'info',
  done: 'check',
  error: 'alert',
  stop: 'close',
}

const STEP_TONES: Record<AgentStepStatus, PillTone> = {
  pending: 'neutral',
  running: 'info',
  success: 'success',
  error: 'danger',
  info: 'neutral',
}

const STEP_STATUS_LABELS: Record<AgentStepStatus, string> = {
  pending: '待执行',
  running: '执行中',
  success: '成功',
  error: '失败',
  info: '提示',
}

const MESSAGE_TONES: Record<'streaming' | 'done' | 'stopped' | 'failed', PillTone> = {
  streaming: 'info',
  done: 'success',
  stopped: 'neutral',
  failed: 'danger',
}

const DETAIL_CLASS: Record<'normal' | 'error', string> = {
  normal: 'achat__step-detail',
  error: 'achat__step-detail achat__step-detail--error',
}

function timeLabel(at: number): string {
  return new Date(at).toLocaleTimeString('zh-CN', { hour12: false })
}

function questionBefore(messages: AgentChatMessage[], index: number): string {
  for (let i = index - 1; i >= 0; i -= 1) {
    const m = messages[i]
    if (m !== undefined && m.role === 'user') return m.content
  }
  return ''
}

/** 单条组件步骤：状态药丸 + 图标 + 细节（工具入参/结果、Skill 异常原文）。 */
function TraceStepRow({ step }: { step: AgentTraceStep }): JSX.Element {
  // ⚠️ 类名先在 JSX 外算好：className={DETAIL_CLASS[cond ? 'error' : 'normal']}
  //    会被 css.integrity 的引号扫描误抓成 .error / .normal 两个「未定义类」。
  const detailClass = step.status === 'error' ? DETAIL_CLASS.error : DETAIL_CLASS.normal
  return (
    <div className="achat__step" data-testid="agent-chat-trace-step" data-status={step.status}>
      <Icon name={STEP_ICONS[step.kind]} className="achat__icon" />
      <div className="achat__step-main">
        <span className="achat__step-title">{step.title}</span>{' '}
        <StatusPill tone={STEP_TONES[step.status]}>{STEP_STATUS_LABELS[step.status]}</StatusPill>
        <span className="achat__step-time">{timeLabel(step.at)}</span>
        {step.detail !== undefined && step.detail !== '' ? (
          <pre className={detailClass}>{step.detail}</pre>
        ) : null}
      </div>
    </div>
  )
}

/** 路由结果：选中 Agent + 理由 + 候选分数；全部来自 route 帧，前端不自行推理。 */
function RouteView({ message }: { message: AgentChatMessage }): JSX.Element | null {
  const route = message.route
  if (route === undefined) return null
  return (
    <div className="achat__route" data-testid="agent-chat-route">
      <p className="achat__route-title">
        <Icon name="cpu" className="achat__icon" />
        <strong>{route.agentName || route.agentId || '未指定 Agent'}</strong>
        <StatusPill tone="info">路由命中</StatusPill>
      </p>
      {route.reason !== '' ? <p className="achat__route-note">路由理由：{route.reason}</p> : null}
      {route.candidates.length > 0 ? (
        <p className="achat__route-note">
          候选：
          {route.candidates.map((c) => (
            <span key={c.agentId} className="achat__candidate">
              {c.name}
              {c.score !== undefined ? ` ${(c.score * 100).toFixed(0)}%` : ''}
            </span>
          ))}
        </p>
      ) : null}
    </div>
  )
}

/** RAG 依据：零命中/未注入是合法 info，不伪装成失败，也不假装已注入。 */
function RagView({ message }: { message: AgentChatMessage }): JSX.Element | null {
  const rag = message.rag
  if (rag === undefined) return null
  const injected = rag.injected
  return (
    <div className="achat__rag" data-testid="agent-chat-rag">
      <p className="achat__rag-title">
        <Icon name="book" className="achat__icon" />
        <strong>
          RAG 召回 {rag.hits.length} 个片段
          {rag.topK !== undefined ? `（topK=${rag.topK}）` : ''}
        </strong>
        <StatusPill tone={injected ? 'success' : 'warn'}>
          {injected ? '已注入上下文' : '未注入上下文'}
        </StatusPill>
      </p>
      {rag.query !== undefined && rag.query !== '' ? (
        <p className="achat__rag-note">检索式：{rag.query}</p>
      ) : null}
      {rag.hits.length === 0 || !injected ? (
        <p className="achat__rag-note">
          本次没有把检索片段注入 Agent。可能是零命中，也可能是命中片段被内容闸门拦截；
          这是合法业务结果，不是系统故障。
        </p>
      ) : (
        <details className="achat__hits">
          <summary>展开核对 {rag.hits.length} 个片段原文</summary>
          {rag.hits.map((hit, index) => (
            <div className="achat__hit" key={`${hit.chunkId}-${index}`}>
              <p className="achat__hit-meta">
                [{index + 1}] {hit.locator || hit.chunkId} · 相关度 {hit.score.toFixed(2)}
                {hit.edition !== '' ? ` · ${hit.edition}` : ''}
                {hit.role !== '' ? ` · ${hit.role}` : ''}
                {hit.reason !== '' ? ` · ${hit.reason}` : ''}
              </p>
              <p className="achat__hit-text">{hit.text}</p>
            </div>
          ))}
        </details>
      )}
    </div>
  )
}

interface AssistantBodyProps {
  message: AgentChatMessage
  question: string
  processVisible: boolean
  onRetry: () => void
  onRephrase: (question: string) => void
}

/** 单条 assistant：来源 → 过程 → 路由 → RAG → 正文 → 截断/停止/失败 → 用量。 */
function AssistantBody({
  message,
  question,
  processVisible,
  onRetry,
  onRephrase,
}: AssistantBodyProps): JSX.Element {
  const truncated = message.truncated === true
  const status: 'streaming' | 'done' | 'stopped' | 'failed' = truncated
    ? 'done'
    : message.status

  return (
    <div>
      <p className="achat__source">
        <StatusPill tone={message.source === 'sample' ? 'warn' : 'info'}>
          {message.source === 'sample' ? `样例回放 · ${AGENT_SAMPLE_TRANSPORT_ID}` : '真实端点'}
        </StatusPill>
        {message.runId !== undefined && message.runId !== '' ? (
          <span className="achat__run-id">run: {message.runId}</span>
        ) : null}
      </p>

      {message.status === 'streaming' && message.steps.length === 0 && message.content === '' ? (
        <p className="gchat__waiting" role="status">正在连接 Agent 编排端点…</p>
      ) : null}

      {processVisible && message.steps.length > 0 ? (
        <div className="achat__timeline" data-testid="agent-chat-timeline">
          {message.steps.map((step) => (
            <TraceStepRow key={step.key} step={step} />
          ))}
        </div>
      ) : null}

      {processVisible && message.steps.length === 0 && message.status === 'streaming' ? (
        <p className="achat__legend">过程模式已开启，等待第一个组件帧到达…</p>
      ) : null}

      <RouteView message={message} />
      <RagView message={message} />

      {message.content !== '' ? (
        <div className="achat__answer" data-testid="agent-chat-answer" aria-label="回答正文">
          {message.content}
        </div>
      ) : null}

      {message.status === 'streaming' && message.steps.length > 0 && message.content === '' ? (
        <p className="gchat__waiting" role="status">组件已响应，正在生成最终回答…</p>
      ) : null}

      {truncated ? (
        <div className="gchat__truncated" role="status" data-testid="agent-chat-truncated">
          流在服务端终帧之前结束，以上仅为已到达的内容，可能不完整；这不是系统故障，也不是一次完整回答。
          <button type="button" className="btn btn--quiet" onClick={onRetry}>
            重试
          </button>
        </div>
      ) : null}

      {message.status === 'stopped' ? (
        <p className="gchat__stopped" data-testid="agent-chat-stopped">
          已停止（你主动中止了本次回答，不是系统故障）。上方保留的组件步骤与正文都是真实到达过的内容。
        </p>
      ) : null}

      {message.error !== undefined ? (
        <div className="gchat__error" role="alert" data-testid="agent-chat-error">
          <strong>{message.error.message}</strong>
          <span className="gchat__code">{message.error.code}</span>
          <p>{message.error.hint}</p>
          <div className="gchat__error-actions">
            {message.error.retryable ? (
              <button type="button" className="btn" onClick={onRetry}>
                重试
              </button>
            ) : null}
            <button type="button" className="btn btn--quiet" onClick={() => onRephrase(question)}>
              换个问法
            </button>
          </div>
        </div>
      ) : null}

      {message.usage !== undefined ? (
        <p className="achat__usage">
          用量：输入 {message.usage.inputTokens} · 输出 {message.usage.outputTokens} tokens
        </p>
      ) : null}

      {message.status !== 'failed' ? (
        <p className="gchat__footnote">
          通用 Agent 编排 · {SOURCE_LABEL[message.source ?? 'live']} ·{' '}
          {status === 'done' && !truncated ? '已收口' : '尚未完整收口'} · 会话只保留在内存中
        </p>
      ) : null}
    </div>
  )
}

export function AgentChatPage(): JSX.Element {
  const [source, setSource] = useState<AgentChatSource>('sample')
  const [thinkingMode, setThinkingMode] = useState(true)
  const [scenario, setScenario] = useState<SampleScenario>('full')
  const [agentId, setAgentId] = useState('')
  const [topK, setTopK] = useState(5)
  const [draft, setDraft] = useState('')
  const [unread, setUnread] = useState(0)
  const listRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLTextAreaElement | null>(null)
  const forceBottomRef = useRef(false)
  const { state, send, stop, retry, newSession } = useAgentChat()

  const params = useMemo<AgentChatParams>(
    () => ({
      source,
      thinkingMode,
      topK,
      agentId: agentId.trim() === '' ? undefined : agentId.trim(),
      sampleScenario: source === 'sample' ? scenario : undefined,
    }),
    [source, thinkingMode, topK, agentId, scenario],
  )

  const violations = useMemo(() => scanText(draft), [draft])
  const hasSensitive = violations.length > 0
  const canSend = draft.trim() !== '' && !state.sending && !hasSensitive

  const last = state.messages[state.messages.length - 1]
  const scrollSignature = [
    state.messages.length,
    last?.status ?? '',
    last?.content.length ?? 0,
    last?.steps.length ?? 0,
    last?.steps.map((s) => s.status).join(',') ?? '',
    last?.error?.code ?? '',
    last?.truncated === true ? 'truncated' : '',
  ].join(':')
  useEffect(() => {
    const el = listRef.current
    if (el === null) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    if (forceBottomRef.current || distance < NEAR_BOTTOM_PX * 2) {
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
    if (el !== null && el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX) {
      setUnread(0)
    }
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
      // 中文输入法组合期间按 Enter 是「选字」，不能当发送。
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
        title="Agent 编排对话"
        eyebrow="AI 对话"
        lead="一次请求内观察 Agent 路由、RAG 注入、Skill、Tool、结果回传与流式回答；组件过程逐帧可见，停止 / 失败 / 截断分别标记。"
        actions={
          <Link to="/chat" className="btn btn--quiet">
            通用问答
          </Link>
        }
      />

      <div className="gchat__notice marked marked--calm">
        <span className="marked__label">这条链路的诚实边界</span>
        <ul className="gchat__notice-list">
          <li>
            <b>真实端点已接入编排式 SSE</b>：真实模式请求 <code>{AGENT_CHAT_PATH}</code>
            （租户链，服务端完成路由 → RAG → Skill/Tool → 流式回答 + Run 审计）；当前环境未部署时
            404/501 会明示「能力未接入」，绝不退化成假流。
          </li>
          <li>
            <b>样例回放不是真实模型输出</b>：脚本帧先序列化成 SSE 文本，再走同一套解析器与状态机；
            它验证前端链路，不证明模型或后端已接入。
          </li>
          <li>
            <b>thinking 模式 = 组件过程可见</b>：租户通道按设计不下发原始推理草稿；
            时间线只展示真实到达的路由 / RAG / Skill / Tool / 回传 / 用量帧。
          </li>
          <li>
            <b>不读写业务数据、会话只在内存</b>：输入只来自本次键入与完整多轮历史，
            不写浏览器存储，刷新即清空。
          </li>
        </ul>
      </div>

      {source === 'sample' ? (
        <div
          className="achat__banner marked marked--calm"
          role="alert"
          data-testid="agent-chat-sample-banner"
        >
          <span className="marked__label">样例回放 · 非真实模型输出</span>
          当前轨迹由本地脚本产生（场景：
          {SCENARIOS.find((s) => s.value === scenario)?.label ?? scenario}）。页面中的路由、RAG、
          Skill、Tool、回传与正文都经过真实 SSE 解析路径，但<b>不是</b>真实 Agent 执行结果。
          要看真实链路请切换到「真实端点」（当前环境需已部署租户链编排端点）。
        </div>
      ) : null}

      <Panel title="编排设置" note="真实端点已接入租户链编排 SSE；当前环境未部署时 404/501 会如实报错；样例回放可立即预览完整链路（非真实输出）。">
        <div className="gchat__toolbar">
          <label className="gchat__field" htmlFor="achat-source" data-testid="agent-chat-source">
            <span>数据来源</span>
            <select
              id="achat-source"
              className="gchat__select"
              value={source}
              onChange={(e) => setSource(e.target.value as AgentChatSource)}
            >
              <option value="sample">样例回放</option>
              <option value="live">真实端点</option>
            </select>
          </label>
          {source === 'sample' ? (
            <label className="gchat__field" htmlFor="achat-scenario" data-testid="agent-chat-scenario">
              <span>回放场景</span>
              <select
                id="achat-scenario"
                className="gchat__select"
                value={scenario}
                onChange={(e) => setScenario(e.target.value as SampleScenario)}
              >
                {SCENARIOS.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
          <label className="gchat__field" htmlFor="achat-agent">
            <span>指定 AgentId（留空＝自动路由）</span>
            <input
              id="achat-agent"
              className="gchat__select"
              value={agentId}
              maxLength={120}
              placeholder="留空自动路由"
              onChange={(e) => setAgentId(e.target.value)}
            />
          </label>
          <label className="gchat__field" htmlFor="achat-topk">
            <span>RAG topK</span>
            <input
              id="achat-topk"
              className="gchat__select"
              type="number"
              min={0}
              max={20}
              value={topK}
              onChange={(e) => setTopK(Number(e.target.value) || 0)}
            />
          </label>
          <label className="gchat__field">
            <input
              type="checkbox"
              checked={thinkingMode}
              onChange={(e) => setThinkingMode(e.target.checked)}
            />
            <span>thinking 模式：展开组件执行过程（不下发推理草稿）</span>
          </label>
        </div>
      </Panel>

      <div className="gchat">
        <div
          className="gchat__messages"
          ref={listRef}
          onScroll={handleScroll}
          role="log"
          aria-live="polite"
          aria-label="Agent 编排对话消息列表"
          data-testid="agent-chat-messages"
        >
          {state.messages.length === 0 ? (
            <div className="gchat__empty">
              <p className="gchat__empty-title">还没有提问。选一个示例开始，或直接输入问题。</p>
              <div className="gchat__samples">
                {SAMPLE_QUESTIONS.map((q) => (
                  <button
                    key={q}
                    type="button"
                    className="btn btn--quiet"
                    onClick={() => handleRephrase(q)}
                  >
                    {q}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            state.messages.map((m, index) => (
              <div key={m.id} className={ROW_CLASS[m.role]} data-testid="agent-chat-message">
                <div className="gchat__bubble">
                  <div className="gchat__meta">
                    <span>{m.role === 'user' ? '你' : 'Agent 编排'}</span>
                    <span>{timeLabel(m.createdAt)}</span>
                    {m.role === 'assistant' && m.elapsedMs !== undefined ? (
                      <span>{(m.elapsedMs / 1000).toFixed(1)}s</span>
                    ) : null}
                    {m.role === 'assistant' ? (
                      <StatusPill tone={MESSAGE_TONES[m.status]}>
                        {m.status === 'streaming'
                          ? '接收中'
                          : m.status === 'stopped'
                            ? '已停止'
                            : m.status === 'failed'
                              ? '失败'
                              : m.truncated === true
                                ? '已截断'
                                : '完成'}
                      </StatusPill>
                    ) : null}
                  </div>
                  {m.role === 'user' ? (
                    <div className="gchat__text">{m.content}</div>
                  ) : (
                    <AssistantBody
                      message={m}
                      question={questionBefore(state.messages, index)}
                      processVisible={thinkingMode}
                      onRetry={() => retry(params)}
                      onRephrase={handleRephrase}
                    />
                  )}
                </div>
              </div>
            ))
          )}
        </div>

        {unread > 0 ? (
          <button
            type="button"
            className="gchat__scroll-pill btn btn--quiet"
            onClick={scrollToBottom}
          >
            回到底部{unread > 1 ? `（${unread} 条新内容）` : ''}
          </button>
        ) : null}

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
          {hasSensitive ? (
            <p className="gchat__sensitive" role="alert">
              输入包含不可采集的信息，请修改后再发送（本地预检，不回显命中内容）。
            </p>
          ) : null}
          <div className="gchat__actions">
            <div className="gchat__actions-group">
              <button type="button" className="btn" disabled={!canSend} onClick={handleSend}>
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
              共 {state.messages.length} 条 · 会话仅存内存 ·{' '}
              {source === 'sample' ? '样例回放（非真实输出）' : '真实端点'}
            </span>
          </div>
        </div>
      </div>

      <p className="achat__legend">
        组件状态说明：单个 Tool / Skill 的「失败」可被 Agent 降级后继续生成，<b>不等于整轮失败</b>；
        RAG 零命中是合法提示；只有 <code>error</code> 帧或传输失败才是整轮 failed。
      </p>
    </>
  )
}
