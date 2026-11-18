/**
 * AI 实验室 · 通用 Agent 编排对话（SSE）。
 *
 * <p><b>这一页展示什么</b>：一次请求内可观测的「路由 → RAG 检索/注入 → Skill 加载执行 →
 * Tool 调用/结果回传 → 思考草稿 → 流式回答 → 用量/终帧」完整链路。每一条时间线条目
 * 都来自一帧<b>真实到达</b>的 SSE 事件；没有定时器合成的进度、没有假打字机、没有百分比。
 *
 * <p><b>⚠️ 链路边界（必须如实呈现）</b>：平台有纯 LLM 的 {@code /api/chat/stream} 与 Run 事件轮询，
 * 本页走的是<b>一次请求内完成编排</b>的 {@code POST /api/agent/chat/stream}（已接入真实实现）。因此：
 * <ol>
 *   <li>「真实端点」模式请求上述编排端点，服务端完成路由 → RAG → Skill/Tool → 流式回答并落 Run 审计。
 *       端点未部署时收到 404/501 会明确显示「能力未接入」，<b>绝不</b>退化成假装成功的假流；</li>
 *   <li>「样例回放」模式由脚本构造协议帧，先序列化成 SSE 文本，再走与真实模式
 *       <b>完全相同</b>的解析器 + reducer + UI。页面顶部与每条样例回答都醒目标注
 *       <b>非真实模型输出</b>，不把演示轨迹伪装成生产链路。</li>
 * </ol>
 *
 * <p><b>身份域</b>：平台控制台（aud=admin-console-api）只走平台链 /api/agent/**，
 * 不复用租户链 /api/knowledge/**；租户前台另有独立实现。
 * <p><b>会话</b>只存内存，不写 localStorage / sessionStorage。
 */

import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import type { TextAreaRef } from 'antd/es/input/TextArea'
import {
  Alert,
  Button,
  Card,
  Collapse,
  Empty,
  Input,
  InputNumber,
  Segmented,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd'
import {
  ApiOutlined,
  ArrowDownOutlined,
  BookOutlined,
  BulbOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExperimentOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  MergeCellsOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined,
  ToolOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { getConsoleAgentRegistry, getPlatformTenants } from '@/api/adminApi'
import type { PlatformAgentRegistryItem, PlatformTenant } from '@/api/types'
import {
  AGENT_LIVE_STREAM_PATH,
  AGENT_SAMPLE_TRANSPORT_ID,
  resolveAgentEndpoint,
  type AgentStepStatus,
} from '@/api/agentChatApi'
import type { AgentChatParams } from '@/features/aiChat/useAgentChat'
import { useAgentChat } from '@/features/aiChat/useAgentChat'
import type { AgentChatMessage, AgentStepKind, AgentTraceStep } from '@/features/aiChat/agentChatState'
import type { SampleScenario } from '@/api/agentChatApi'
import '@/styles/aiChat.css'
import '@/styles/agentChat.css'

/** 排版引擎按需加载；加载期间按原文显示，不丢任何已到达 token。 */
const MarkdownAnswer = lazy(async () => {
  const module = await import('@/features/aiChat/MarkdownAnswer')
  return { default: module.MarkdownAnswer }
})

const { Text, Paragraph, Title } = Typography

const SOURCE_OPTIONS: { value: 'live' | 'sample'; label: string }[] = [
  { value: 'live', label: '真实端点' },
  { value: 'sample', label: '样例回放' },
]

const SCENARIO_OPTIONS: { value: SampleScenario; label: string }[] = [
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

const STEP_ICONS: Record<AgentStepKind, ReactNode> = {
  route: <ApiOutlined />,
  rag: <BookOutlined />,
  skill: <MergeCellsOutlined />,
  tool: <ToolOutlined />,
  handoff: <ReloadOutlined />,
  usage: <InfoCircleOutlined />,
  done: <CheckCircleOutlined />,
  error: <CloseCircleOutlined />,
  stop: <StopOutlined />,
}

const STATUS_COLORS: Record<AgentStepStatus, string> = {
  pending: 'default',
  running: 'processing',
  success: 'success',
  error: 'error',
  info: 'default',
}

const STATUS_LABELS: Record<AgentStepStatus, string> = {
  pending: '待执行',
  running: '执行中',
  success: '成功',
  error: '失败',
  info: '提示',
}

function timeLabel(at: number): string {
  return new Date(at).toLocaleTimeString('zh-CN', { hour12: false })
}

/** 单条组件步骤：状态色 + 图标 + 标题 + 可展开细节（工具入参/结果、Skill 异常原文）。 */
function TraceStepRow({ step }: { step: AgentTraceStep }): JSX.Element {
  return (
    <div className="agent-chat__trace-row" data-testid="agent-chat-trace-step" data-status={step.status}>
      <span className={`agent-chat__trace-icon agent-chat__trace-icon--${step.status}`}>
        {step.status === 'running' ? <LoadingOutlined /> : STEP_ICONS[step.kind]}
      </span>
      <span className="agent-chat__trace-main">
        <span className="agent-chat__trace-title">{step.title}</span>
        <Tag color={STATUS_COLORS[step.status]} bordered={false} className="agent-chat__trace-tag">
          {STATUS_LABELS[step.status]}
        </Tag>
        <Text type="secondary" className="agent-chat__trace-time">
          {timeLabel(step.at)}
        </Text>
        {step.detail !== undefined && step.detail !== '' && (
          <pre className={`agent-chat__trace-detail${step.status === 'error' ? ' agent-chat__trace-detail--error' : ''}`}>
            {step.detail}
          </pre>
        )}
      </span>
    </div>
  )
}

/** 推理草稿：只在本页（平台通道）开启 thinking 模式时出现；与正文独立累积。 */
function ThinkingBlock({ message }: { message: AgentChatMessage }): JSX.Element | null {
  if (!message.thinkingMode || message.thinking === '') return null
  const streaming = message.status === 'streaming'
  return (
    <Collapse
      ghost
      size="small"
      className="agent-chat__thinking"
      defaultActiveKey={streaming ? ['thinking'] : []}
      items={[
        {
          key: 'thinking',
          label: (
            <Space size={4} wrap>
              <BulbOutlined />
              {streaming ? '模型思考中…' : '模型思考过程'}
              <Text type="secondary" style={{ fontSize: 11.5 }}>
                （{message.thinking.length} 字 · 推理草稿，仅供参考）
              </Text>
            </Space>
          ),
          children: (
            <div className="agent-chat__thinking-body">
              {message.thinking}
              {streaming && <LoadingOutlined style={{ marginLeft: 6, color: '#8a93a3' }} />}
            </div>
          ),
        },
      ]}
    />
  )
}

/** 路由结果：选中 Agent + 理由 + 候选分数；全部来自 route 帧，前端不自行推理。 */
function RouteView({ message }: { message: AgentChatMessage }): JSX.Element | null {
  const route = message.route
  if (route === undefined) return null
  return (
    <div className="agent-chat__route" data-testid="agent-chat-route">
      <Space size={6} wrap>
        <RobotOutlined />
        <Text strong>{route.agentName || route.agentId || '未指定 Agent'}</Text>
        <Tag color="blue" bordered={false}>路由命中</Tag>
      </Space>
      {route.reason !== '' && (
        <div className="agent-chat__route-reason">路由理由：{route.reason}</div>
      )}
      {route.candidates.length > 0 && (
        <div className="agent-chat__route-candidates">
          候选：
          {route.candidates.map((c) => (
            <Tag key={c.agentId} bordered={false}>
              {c.name}
              {c.score !== undefined ? ` ${(c.score * 100).toFixed(0)}%` : ''}
            </Tag>
          ))}
        </div>
      )}
    </div>
  )
}

/** RAG 依据：命中片段可展开核对；零命中/未注入是合法 info，不伪装成失败或成功。 */
function RagView({ message }: { message: AgentChatMessage }): JSX.Element | null {
  const rag = message.rag
  if (rag === undefined) return null
  const empty = rag.hits.length === 0 || !rag.injected
  return (
    <div className="agent-chat__rag" data-testid="agent-chat-rag">
      <Space size={6} wrap>
        <BookOutlined />
        <Text strong>
          RAG 召回 {rag.hits.length} 个片段
          {rag.topK !== undefined ? `（topK=${rag.topK}）` : ''}
        </Text>
        <Tag color={rag.injected ? 'success' : 'default'} bordered={false}>
          {rag.injected ? '已注入上下文' : '未注入上下文'}
        </Tag>
      </Space>
      {rag.query !== undefined && rag.query !== '' && (
        <div className="agent-chat__rag-query">检索式：{rag.query}</div>
      )}
      {empty ? (
        <Alert
          className="agent-chat__rag-empty"
          type="info"
          showIcon
          message="本次没有把检索片段注入 Agent"
          description="可能是零命中，也可能是命中片段被内容闸门拦截。这是合法业务结果，不是系统故障；Agent 仍可能基于其它上下文作答。"
        />
      ) : (
        <Collapse
          className="agent-chat__rag-hits"
          ghost
          size="small"
          items={[
            {
              key: 'hits',
              label: `展开核对 ${rag.hits.length} 个片段原文`,
              children: (
                <Space direction="vertical" size="small" style={{ width: '100%' }}>
                  {rag.hits.map((hit, index) => (
                    <div key={`${hit.chunkId}-${index}`} className="agent-chat__hit">
                      <Text type="secondary" className="agent-chat__hit-meta">
                        [{index + 1}] {hit.locator || hit.chunkId} · 相关度 {hit.score.toFixed(2)}
                        {hit.edition !== '' ? ` · ${hit.edition}` : ''}
                        {hit.role !== '' ? ` · ${hit.role}` : ''}
                        {hit.reason !== '' ? ` · ${hit.reason}` : ''}
                      </Text>
                      <div className="agent-chat__hit-text">{hit.text}</div>
                    </div>
                  ))}
                </Space>
              ),
            },
          ]}
        />
      )}
    </div>
  )
}

interface AssistantBodyProps {
  message: AgentChatMessage
  question: string
  onRetry: () => void
  onRephrase: (question: string) => void
}

/** 单条 assistant 消息：来源标识 → 时间线 → 路由 → RAG → 思考 → 正文 → 警告/错误 → 用量。 */
function AssistantBody({ message, question, onRetry, onRephrase }: AssistantBodyProps): JSX.Element {
  const steps = message.steps
  const hasAnswer = message.content !== ''

  return (
    <div>
      <div className="agent-chat__assistant-head">
        <Tag color={message.source === 'sample' ? 'warning' : 'blue'} bordered={false}>
          {message.source === 'sample' ? `样例回放 · ${AGENT_SAMPLE_TRANSPORT_ID}` : '真实端点'}
        </Tag>
        {message.runId !== undefined && message.runId !== '' && (
          <Text type="secondary" className="agent-chat__run-id">
            run: {message.runId}
          </Text>
        )}
      </div>

      {message.status === 'streaming' && steps.length === 0 && hasAnswer === false && (
        <div className="agent-chat__waiting" role="status">
          <LoadingOutlined />
          <span>正在连接 Agent 编排端点…</span>
        </div>
      )}

      <div className="agent-chat__trace" data-testid="agent-chat-timeline">
        {steps.map((step) => (
          <TraceStepRow key={step.key} step={step} />
        ))}
      </div>

      <RouteView message={message} />
      <RagView message={message} />
      <ThinkingBlock message={message} />

      {hasAnswer && (
        <div className="agent-chat__answer" data-testid="agent-chat-answer">
          <Suspense fallback={<pre className="agent-chat__answer-raw">{message.content}</pre>}>
            <MarkdownAnswer content={message.content} streaming={message.status === 'streaming'} />
          </Suspense>
        </div>
      )}

      {message.status === 'streaming' && steps.length > 0 && !hasAnswer && (
        <div className="agent-chat__waiting" role="status">
          <LoadingOutlined />
          <span>组件已响应，正在生成最终回答…</span>
        </div>
      )}

      {message.truncated === true && (
        <Alert
          className="agent-chat__truncated"
          data-testid="agent-chat-truncated"
          type="warning"
          showIcon
          message="流在服务端终帧之前结束"
          description="上面已到达的内容被保留，但它可能不完整；这不是系统故障，也不是一次完整回答。请重试或缩小问题范围。"
          action={
            <Button size="small" onClick={onRetry}>
              重试
            </Button>
          }
        />
      )}

      {message.status === 'stopped' && (
        <Alert
          className="agent-chat__stopped"
          data-testid="agent-chat-stopped"
          type="info"
          showIcon
          message="已停止"
          description="你主动中止了本次回答，不是系统故障。上方保留的组件步骤与正文都是真实到达过的内容。"
        />
      )}

      {message.error !== undefined && (
        <Alert
          className="agent-chat__error"
          data-testid="agent-chat-error"
          type="error"
          showIcon
          message={
            <Space size={6} wrap>
              <Text strong type="danger">
                {message.error.message}
              </Text>
              <Tag color="red" bordered={false}>
                {message.error.code}
              </Tag>
            </Space>
          }
          description={message.error.hint}
          action={
            <Space size="small">
              {message.error.retryable && (
                <Button size="small" type="primary" icon={<ReloadOutlined />} onClick={onRetry}>
                  重试
                </Button>
              )}
              <Button size="small" onClick={() => onRephrase(question)}>
                换个问法
              </Button>
            </Space>
          }
        />
      )}

      {message.usage !== undefined && (
        <div className="agent-chat__footnote">
          <InfoCircleOutlined /> 用量：输入 {message.usage.inputTokens} · 输出{' '}
          {message.usage.outputTokens} tokens
        </div>
      )}
    </div>
  )
}

export function AgentChatPage(): JSX.Element {
  const [source, setSource] = useState<'live' | 'sample'>('sample')
  const [thinkingMode, setThinkingMode] = useState(true)
  const [scenario, setScenario] = useState<SampleScenario>('full')
  const [agentId, setAgentId] = useState('')
  const [tenantId, setTenantId] = useState('')
  const [topK, setTopK] = useState(5)
  const [draft, setDraft] = useState('')
  const [unread, setUnread] = useState(0)
  const [agents, setAgents] = useState<PlatformAgentRegistryItem[]>([])
  const [tenants, setTenants] = useState<PlatformTenant[]>([])
  const [catalogError, setCatalogError] = useState<string | null>(null)

  const listRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<TextAreaRef | null>(null)
  const forceBottomRef = useRef(false)
  const { state, send, stop, retry, newSession } = useAgentChat()

  // 目录只用于「显式指定 Agent / 租户」；加载失败不阻塞对话（自动路由仍可用）。
  useEffect(() => {
    let cancelled = false
    const run = async (): Promise<void> => {
      try {
        const [agentList, tenantList] = await Promise.all([
          getConsoleAgentRegistry(),
          getPlatformTenants({ limit: 200 }),
        ])
        if (!cancelled) {
          setAgents(agentList)
          setTenants(tenantList)
        }
      } catch {
        if (!cancelled) setCatalogError('Agent / 租户目录读取失败；可先清空选择走自动路由。')
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [])

  const params = useMemo<AgentChatParams>(
    () => ({
      source,
      thinkingMode,
      topK,
      agentId: agentId === '' ? undefined : agentId,
      tenantId: tenantId === '' ? undefined : tenantId,
      sampleScenario: source === 'sample' ? scenario : undefined,
    }),
    [source, thinkingMode, topK, agentId, tenantId, scenario],
  )

  const last = state.messages[state.messages.length - 1]
  const scrollSignature = [
    state.messages.length,
    last?.status ?? '',
    last?.content.length ?? 0,
    last?.thinking.length ?? 0,
    last?.steps.length ?? 0,
    last?.steps.map((s) => s.status).join(',') ?? '',
    last?.error?.code ?? '',
    last?.truncated === true ? 'truncated' : '',
  ].join(':')
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
    if (el !== null && el.scrollHeight - el.scrollTop - el.clientHeight < 80) setUnread(0)
  }, [])

  const scrollToBottom = useCallback((): void => {
    const el = listRef.current
    if (el !== null) el.scrollTop = el.scrollHeight
    setUnread(0)
  }, [])

  const canSend = draft.trim() !== '' && !state.sending
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

  const handleRetry = useCallback((): void => retry(params), [params, retry])

  const handleNewSession = useCallback((): void => {
    newSession()
    setDraft('')
    setUnread(0)
  }, [newSession])

  const questionOf = useCallback(
    (index: number): string => {
      for (let i = index - 1; i >= 0; i -= 1) {
        const m = state.messages[i]
        if (m !== undefined && m.role === 'user') return m.content
      }
      return ''
    },
    [state.messages],
  )

  const agentOptions = useMemo(
    () =>
      agents.map((a) => ({
        value: a.agentId,
        label: `${a.name ?? a.agentId}${a.tenantId ? ` · ${a.tenantId}` : ''}`,
      })),
    [agents],
  )
  const tenantOptions = useMemo(
    () => tenants.map((t) => ({ value: t.tenantId, label: `${t.name}（${t.slug}）` })),
    [tenants],
  )

  const statusOf = (m: AgentChatMessage): string => {
    if (m.status === 'streaming') return '接收中'
    if (m.status === 'stopped') return '已停止'
    if (m.status === 'failed') return '失败'
    return m.truncated === true ? '已截断' : '完成'
  }
  const statusColorOf = (m: AgentChatMessage): string => {
    if (m.status === 'streaming') return 'processing'
    if (m.status === 'stopped') return 'default'
    if (m.status === 'failed') return 'error'
    return m.truncated === true ? 'warning' : 'success'
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <div>
        <Title level={4} style={{ marginBottom: 4 }}>
          通用 Agent 对话
          <Tag color="blue" style={{ marginLeft: 10, verticalAlign: 'middle' }} bordered={false}>
            AI 实验室
          </Tag>
        </Title>
        <Paragraph type="secondary" style={{ marginBottom: 0 }}>
          一次请求内观察 Agent 路由 → RAG 检索/注入 → Skill → Tool → 结果回传 → 流式回答的完整编排过程。
          每个组件状态都来自真实到达的 SSE 帧；停止、失败、截断分别标记，不互相冒充。
        </Paragraph>
      </div>

      <Alert
        type="info"
        showIcon
        message="这条链路的诚实边界"
        description={
          <>
            1）<b>真实端点已接入编排式 SSE</b>。真实模式请求{' '}
            <code>POST {resolveAgentEndpoint()}</code>
            （路径常量 <code>{AGENT_LIVE_STREAM_PATH}</code>），服务端在单次请求内完成路由 → RAG → Skill/Tool → 流式回答并落 Run 审计；
            端点未部署时收到 404/501 会明确显示「能力未接入」，<b>绝不</b>退化成假的流式回答。
            <br />
            2）<b>样例回放不是真实模型输出</b>。脚本先构造协议帧，再序列化为 SSE 文本，经由
            <b>同一套解析器 + 状态机 + 界面</b>回放；它验证的是前端链路，不证明模型或后端已接入。
            <br />
            3）<b>thinking 模式</b>下会额外接收并折叠展示模型推理草稿（仅平台通道）；组件执行时间线始终可见，
            且不含预估进度或百分比。租户前台不下发原始推理草稿。
            <br />
            4）本页只走平台身份域 <code>/api/agent/**</code>，不读取租户 Run 输出，不复用租户链{' '}
            <code>/api/knowledge/**</code>。会话仅存内存。
            <br />
            5）<b>租户留空时走服务端配置的默认租户</b>（AI 实验室通用对话无需绑定具体租户）；若后端未配置该默认租户，
            会明确返回「平台链必须显式指定目标租户」，此时请在上方选择一个租户。
          </>
        }
      />

      {source === 'sample' && (
        <Alert
          data-testid="agent-chat-sample-banner"
          type="warning"
          showIcon
          message="样例回放 · 非真实模型输出"
          description={
            <>
              当前轨迹由本地脚本产生（场景：{SCENARIO_OPTIONS.find((o) => o.value === scenario)?.label}）。
              页面中的路由、RAG、Skill、Tool、回传与正文都经过真实 SSE 解析路径，但<b>不是</b>真实 Agent 执行结果。
              要看真实链路，请切换到「真实端点」（当前环境需已部署平台链编排端点）。
            </>
          }
        />
      )}

      {catalogError !== null && <Alert type="warning" showIcon message={catalogError} />}

      <Card title="编排参数" size="small">
        <div className="agent-chat__params">
          <div className="agent-chat__param">
            <span className="agent-chat__param-label">数据来源</span>
            <Segmented
              data-testid="agent-chat-source"
              value={source}
              onChange={(v) => setSource(v as 'live' | 'sample')}
              options={SOURCE_OPTIONS}
            />
          </div>
          {source === 'sample' && (
            <div className="agent-chat__param">
              <span className="agent-chat__param-label">回放场景</span>
              <Select
                data-testid="agent-chat-scenario"
                style={{ width: 230 }}
                value={scenario}
                onChange={setScenario}
                options={SCENARIO_OPTIONS}
              />
            </div>
          )}
          <div className="agent-chat__param">
            <span className="agent-chat__param-label">指定 Agent（留空＝自动路由）</span>
            <Select
              allowClear
              showSearch
              style={{ width: 250 }}
              placeholder="自动路由"
              value={agentId || undefined}
              onChange={(v) => setAgentId(v ?? '')}
              options={agentOptions}
              filterOption={(input, opt) =>
                String(opt?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
            />
          </div>
          <div className="agent-chat__param">
            <span className="agent-chat__param-label">租户（可选，留空走服务端默认租户）</span>
            <Select
              allowClear
              showSearch
              style={{ width: 230 }}
              placeholder="不指定"
              value={tenantId || undefined}
              onChange={(v) => setTenantId(v ?? '')}
              options={tenantOptions}
              filterOption={(input, opt) =>
                String(opt?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
            />
          </div>
          <div className="agent-chat__param">
            <span className="agent-chat__param-label">RAG topK</span>
            <InputNumber min={0} max={20} value={topK} onChange={(v) => setTopK(v ?? 5)} />
          </div>
          <div className="agent-chat__param agent-chat__param--switch">
            <Switch
              checked={thinkingMode}
              onChange={setThinkingMode}
              checkedChildren="thinking 开"
              unCheckedChildren="thinking 关"
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              开启后接收推理草稿；组件时间线始终可见
            </Text>
          </div>
        </div>
      </Card>

      <Card size="small" className="agent-chat">
        <div className="agent-chat__shell">
          <div
            className="agent-chat__messages"
            ref={listRef}
            onScroll={handleScroll}
            role="log"
            aria-live="polite"
            aria-label="Agent 编排对话消息列表"
          >
            {state.messages.length === 0 ? (
              <div className="agent-chat__empty">
                <Empty description="还没有提问。选好数据来源后，从下面的示例问题开始。" />
                <div className="agent-chat__samples">
                  {SAMPLE_QUESTIONS.map((q) => (
                    <Button key={q} size="small" onClick={() => handleRephrase(q)}>
                      {q}
                    </Button>
                  ))}
                </div>
              </div>
            ) : (
              state.messages.map((m, index) => (
                <div
                  key={m.id}
                  className={`agent-chat__row agent-chat__row--${m.role}`}
                  data-testid="agent-chat-message"
                >
                  <div className="agent-chat__avatar">
                    {m.role === 'user' ? <UserOutlined /> : <RobotOutlined />}
                  </div>
                  <div className="agent-chat__bubble">
                    <div className="agent-chat__meta">
                      <span>{m.role === 'user' ? '你' : 'Agent 编排'}</span>
                      <span>{timeLabel(m.createdAt)}</span>
                      {m.role === 'assistant' && m.elapsedMs !== undefined && (
                        <span>{(m.elapsedMs / 1000).toFixed(1)}s</span>
                      )}
                      {m.role === 'assistant' && (
                        <Tag color={statusColorOf(m)} bordered={false}>
                          {statusOf(m)}
                        </Tag>
                      )}
                    </div>
                    {m.role === 'user' ? (
                      <div className="agent-chat__text">{m.content}</div>
                    ) : (
                      <AssistantBody
                        message={m}
                        question={questionOf(index)}
                        onRetry={handleRetry}
                        onRephrase={handleRephrase}
                      />
                    )}
                  </div>
                </div>
              ))
            )}
          </div>

          {unread > 0 && (
            <Button
              className="agent-chat__scroll-pill"
              size="small"
              shape="round"
              icon={<ArrowDownOutlined />}
              onClick={scrollToBottom}
            >
              回到底部{unread > 1 ? `（${unread} 条新内容）` : ''}
            </Button>
          )}

          <div className="agent-chat__composer">
            <Input.TextArea
              ref={inputRef}
              rows={2}
              value={draft}
              maxLength={1000}
              showCount
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="输入问题…（Enter 发送，Shift+Enter 换行；等待期间仍可继续输入）"
            />
            <div className="agent-chat__actions">
              <Space wrap size="small">
                <Button
                  type="primary"
                  icon={<SendOutlined />}
                  loading={state.sending}
                  disabled={!canSend}
                  onClick={handleSend}
                >
                  发送
                </Button>
                <Button danger icon={<StopOutlined />} disabled={!state.sending} onClick={stop}>
                  停止
                </Button>
                <Button
                  icon={<PlusOutlined />}
                  disabled={state.messages.length === 0}
                  onClick={handleNewSession}
                >
                  新会话
                </Button>
              </Space>
              <Text type="secondary" style={{ fontSize: 12 }}>
                共 {state.messages.length} 条 · 会话仅存内存 ·{' '}
                {source === 'sample' ? '样例回放（非真实输出）' : '真实端点'}
              </Text>
            </div>
          </div>
        </div>
      </Card>

      <div className="agent-chat__legend">
        <ExperimentOutlined /> 组件状态图例：
        <Tag color="processing" bordered={false}>执行中</Tag>
        <Tag color="success" bordered={false}>成功</Tag>
        <Tag color="error" bordered={false}>组件失败（Agent 可降级继续）</Tag>
        <Tag bordered={false}>提示（如 RAG 零命中）</Tag>
        <Text type="secondary" style={{ fontSize: 12 }}>
          单个 Tool/Skill 失败不等于整轮失败；只有 <code>error</code> 帧或传输失败才是整轮 failed。
        </Text>
      </div>
    </Space>
  )
}
