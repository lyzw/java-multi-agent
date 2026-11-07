/**
 * AI 实验室 · AI 对话（语料问答）。
 *
 * <p><b>这一页是什么</b>：完整的聊天界面与交互闭环 —— 输入 → 发送 → 阶段增量接收
 * → 回答落地，以及加载/等待、停止、重试、失败、依据不足、限流、超时、网络异常
 * 等全部分支。接入的是项目<b>现有</b> RAG 检索/生成端点，零后端改动。
 *
 * <p><b>⚠️ 为什么不是「大模型聊天」（必须如实写在界面上，不藏在文档里）</b>：
 * <ol>
 *   <li>后端<b>没有</b>对话端点、没有 token 流：<code>ModelGateway.complete()</code>
 *       是 Worker 内部的一次性调用（MiniMax 适配器 <code>stream=false</code>），
 *       唯一 SSE <code>/api/runs/{id}/events</code> 被 fail-closed 白名单限成状态字段。
 *       所以本页的「流」是<b>两次真实服务端往返产生的阶段增量</b>
 *       （检索命中 → 依据就绪 → 回答落地），<b>不</b>伪造逐字打字机。</li>
 *   <li><code>/api/v1/retrieval/generate</code> 当前恒为 <code>EXCERPT_ASSEMBLY</code>
 *       （逐字摘录装配，<code>GroundedGenerationService</code> 刻意不接 LLM）。
 *       <code>generator</code> / <code>disclaimer</code> / 引用必须随答案展示，
 *       只贴 answer 等于把摘录伪装成 AI 讲解。</li>
 *   <li>P6 非目标与改造清单 R6 是硬红线：<b>平台控制台不看会话输出</b>。
 *       故本页刻意<b>不</b>接 <code>GET /api/runs/{id}/result</code>（唯一能拿模型终答的端点）。
 *       要真实模型多轮对话，需后端按 <code>docs/前端问答功能规划.html</code> §6 路线 B
 *       新增问答会话端点，再替换 {@link ragCorpusTransport}；前端不偷拿运行输出绕红线。</li>
 * </ol>
 *
 * <p><b>技术栈</b>：React 18 + TypeScript strict + antd 5 + Vite 6，
 * 数据走 <code>@/api/client#request</code>（<code>API_BASE='/api'</code>），
 * 状态用 <code>useReducer</code>（不引入状态库），会话只留内存。
 */

import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
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
  Tag,
  Typography,
} from 'antd'
import type { TextAreaRef } from 'antd/es/input/TextArea'
import {
  ArrowDownOutlined,
  BookOutlined,
  BulbOutlined,
  ExclamationCircleOutlined,
  LoadingOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { getPlatformTenants } from '@/api/adminApi'
import { ApiError } from '@/api/client'
import type { PlatformTenant } from '@/api/types'
import {
  CHAT_STAGE_LABELS,
  DIRECT_GENERATOR_LABEL,
  ragCorpusTransport,
  type ChatChannel,
  type ChatEdition,
  type ChatHit,
  type ChatMode,
  type ChatScopeMode,
} from '@/api/aiChatApi'
import type { ChatParams } from '@/features/aiChat/useAiChat'
import { useAiChat } from '@/features/aiChat/useAiChat'
import type { ChatMessage } from '@/features/aiChat/chatState'
import '@/styles/aiChat.css'

/**
 * 排版引擎（react-markdown + KaTeX + 字体）只在「直接对话」首次出答案时加载，
 * 不压到管理控制台其它页面的首屏。加载期间按原文显示 —— 那是真实状态，
 * 不是打字机特效，也不丢任何已到达的 token。
 */
const MarkdownAnswer = lazy(async () => {
  const module = await import('@/features/aiChat/MarkdownAnswer')
  return { default: module.MarkdownAnswer }
})

const { Text, Paragraph, Title } = Typography

/** 与 rag-core `EditionCoordinate` 枚举展示名逐项一致，不许自由输入。 */
const SUBJECTS = ['数学', '语文', '英语', '物理', '化学', '生物', '历史', '地理', '道法']
const GRADES = [
  '一年级', '二年级', '三年级', '四年级', '五年级', '六年级',
  '七年级', '八年级', '九年级', '高一', '高二', '高三',
]
const TERMS = ['上册', '下册', '全一册']
const PUBLISHERS = ['人教版', '苏教版', '北师大版', '华师大版', '沪教版', '鲁教版', '鄂教版', '其他']

const MODE_OPTIONS: { value: ChatMode; label: string }[] = [
  { value: 'EXPLAIN', label: '讲解（默认，屏蔽答案类片段）' },
  { value: 'SOLVE', label: '解题' },
  { value: 'VERIFY', label: '核对' },
  { value: 'OVERVIEW', label: '概览' },
]

const SCOPE_OPTIONS: { value: ChatScopeMode; label: string }[] = [
  { value: 'STRICT', label: '严格锁定当前版本' },
  { value: 'PREFERRED_WITH_FALLBACK', label: '优先本版，可回退同册' },
  { value: 'CROSS_EDITION', label: '跨版本对比' },
]

const SAMPLE_QUESTIONS = [
  '一元二次方程判别式小于零是什么意思？',
  '勾股定理适用于哪些三角形？',
  '二次函数的顶点式怎么推导？',
]

const DEFAULT_EDITION: ChatEdition = {
  subject: '数学',
  grade: '八年级',
  term: '上册',
  publisher: '人教版',
  revisionCode: '2024',
}

const NEAR_BOTTOM_PX = 80

function timeLabel(at: number): string {
  return new Date(at).toLocaleTimeString('zh-CN', { hour12: false })
}

function optionsOf(values: readonly string[]): { value: string; label: string }[] {
  return values.map((v) => ({ value: v, label: v }))
}

interface AssistantBodyProps {
  message: ChatMessage
  question: string
  onRetry: () => void
  onRephrase: (question: string) => void
}

/** 模型思考过程（推理草稿）：折叠展示，流式时默认展开、完成后可收起。 */
function ThinkingBlock({ message }: { message: ChatMessage }): JSX.Element | null {
  const thinking = message.thinking
  if (thinking === undefined || thinking === '') return null
  // 流式期间默认展开（边想边看）；完成后保留展开状态，用户可自行收起。
  const [open, setOpen] = useState(message.status === 'streaming')
  const streaming = message.status === 'streaming'
  return (
    <Collapse
      ghost
      size="small"
      className="ai-chat__thinking"
      activeKey={open ? ['thinking'] : []}
      onChange={(keys) => setOpen(keys.includes('thinking'))}
      items={[
        {
          key: 'thinking',
          label: (
            <Space size={4} wrap>
              <BulbOutlined />
              {streaming ? '模型思考中…' : '模型思考过程'}
              <Text type="secondary" style={{ fontSize: 11.5 }}>
                （{thinking.length} 字 · 推理草稿，仅供参考）
              </Text>
            </Space>
          ),
          children: (
            <div className="ai-chat__thinking-body">
              {thinking}
              {streaming && <LoadingOutlined style={{ marginLeft: 6, color: '#8a93a3' }} />}
            </div>
          ),
        },
      ]}
    />
  )
}

type AnswerView = 'rendered' | 'source'

/**
 * 直接对话（真·LLM）正文：Markdown + LaTeX 排版视图，外加「原文」核对视图。
 *
 * ⚠️ 原文视图展示的是<b>未经任何预处理</b>的模型输出：渲染层会归一化 `\[…\]`、
 * 补裸命令、转义未闭合定界符，这些改写只发生在呈现层，必须能被一键核对，
 * 否则「答案被重排过」这件事就藏起来了（与「逐字摘录不得伪装」同一条纪律）。
 */
function RichAnswer({ content, streaming }: { content: string; streaming: boolean }): JSX.Element {
  const [view, setView] = useState<AnswerView>('rendered')
  return (
    <div className="ai-chat__rich">
      <div className="ai-chat__rich-toolbar">
        <Segmented
          size="small"
          value={view}
          onChange={(v) => setView(v as AnswerView)}
          options={[
            { label: '排版', value: 'rendered' },
            { label: '原文', value: 'source' },
          ]}
        />
        <Text type="secondary" style={{ fontSize: 11.5 }}>
          {view === 'rendered' ? 'Markdown · LaTeX 公式' : '模型原始输出（未经任何重排）'}
        </Text>
      </div>
      {view === 'source' ? (
        <pre className="ai-chat__source">{content}</pre>
      ) : (
        <Suspense fallback={<div className="ai-chat__answer">{content}</div>}>
          <MarkdownAnswer content={content} streaming={streaming} />
        </Suspense>
      )}
    </div>
  )
}

/** 单条 assistant 消息的完整呈现：阶段 → 依据 → 思考 → 正文 → 脚注 → 引用 → 错误。 */
function AssistantBody({ message, question, onRetry, onRephrase }: AssistantBodyProps): JSX.Element {
  const stages = message.stages ?? []
  const evidence = message.evidence ?? []
  const citations = message.citations ?? []

  return (
    <div>
      {/* 阶段只显示「真实到达过」的：没有预估进度、没有百分比 */}
      {stages.length > 0 && (
        <div className="ai-chat__stages">
          {stages.map((s) => (
            <Tag key={s} color="processing" bordered={false}>
              {CHAT_STAGE_LABELS[s]}
            </Tag>
          ))}
          {message.status === 'streaming' && <LoadingOutlined style={{ color: '#2f6df6' }} />}
        </div>
      )}

      <ThinkingBlock message={message} />

      {message.status === 'streaming' && message.content === '' && (message.thinking ?? '') === '' && (
        <div className="ai-chat__waiting" role="status">
          <LoadingOutlined />
          <span>正在生成回答…</span>
        </div>
      )}

      {evidence.length > 0 && (
        <div className="ai-chat__evidence">
          <BookOutlined /> 检索命中 {evidence.length} 个片段
          {message.primaryEdition ? ` · 主版本 ${message.primaryEdition}` : ''}
          <Text type="secondary" style={{ fontSize: 11.5, marginLeft: 6 }}>
            （以下回答只以这些片段为依据）
          </Text>
        </div>
      )}

      {/*
        ⚠️ 只有「直接对话」的 LLM 输出才排版：语料问答是 EXCERPT_ASSEMBLY
        逐字摘录，把原文里的 * / $ / 反斜杠当 Markdown 解释就是篡改依据。
      */}
      {message.content !== '' &&
        (message.channel === 'direct' ? (
          <RichAnswer content={message.content} streaming={message.status === 'streaming'} />
        ) : (
          <div className="ai-chat__answer">{message.content}</div>
        ))}

      {message.noBasis !== undefined && (
        <div className="ai-chat__no-basis" role="status">
          <Text strong>本回答无语料依据。</Text>
          <br />
          <Text type="secondary" style={{ fontSize: 12 }}>
            {message.noBasis === 'NO_HITS'
              ? '本次检索没有命中可用片段。请换一种问法，或检查租户/教材版本是否选对。'
              : '命中片段被内容闸门过滤，依据不足，服务端返回空 answer（合法业务结果，不是故障）。'}
          </Text>
          <div style={{ marginTop: 8 }}>
            <Button size="small" onClick={() => onRephrase(question)}>
              换个问法
            </Button>
          </div>
        </div>
      )}

      {message.error !== undefined && (
        <div className="ai-chat__error" role="alert">
          <Text strong type="danger">
            <ExclamationCircleOutlined /> {message.error.message}
          </Text>
          <Tag color="red" style={{ marginLeft: 8 }}>
            {message.error.code}
          </Tag>
          <br />
          <Text type="secondary" style={{ fontSize: 12 }}>
            {message.error.hint}
          </Text>
          <div style={{ marginTop: 8 }}>
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
          </div>
        </div>
      )}

      {message.status === 'stopped' && (
        <Text type="secondary" style={{ fontSize: 12 }}>
          已停止（你主动中止了本次回答，不是系统故障）。
        </Text>
      )}

      {/* ⚠️ generator / disclaimer 不是装饰：后端给了就必须原样展示 */}
      {message.generator !== undefined && (
        <div className="ai-chat__footnote">
          <Space size={4} wrap>
            <Text type="secondary">生成方式</Text>
            <Tag color={message.generator === 'EXCERPT_ASSEMBLY' ? 'gold' : 'blue'} bordered={false}>
              {message.generator}
            </Tag>
            {message.generator === 'EXCERPT_ASSEMBLY' && (
              <Text type="secondary">逐字摘录装配，非大模型生成</Text>
            )}
            {message.generator !== undefined &&
              message.generator.startsWith('LLM') && (
                <Text type="secondary">真实大模型生成（多轮上下文由本页维护，整体回传后端）</Text>
              )}
          </Space>
          {message.disclaimer !== undefined && message.disclaimer !== '' && (
            <div style={{ marginTop: 4 }}>说明：{message.disclaimer}</div>
          )}
          {message.flags?.versionMismatchDetected && (
            <div style={{ marginTop: 4, color: '#d89614' }}>回答引用了非首选版本的内容。</div>
          )}
          {message.flags?.answerContentSuppressed && (
            <div style={{ marginTop: 4 }}>部分答案类内容已被模式闸门隐藏（防泄题）。</div>
          )}
        </div>
      )}

      {citations.length > 0 && (
        <Collapse
          className="ai-chat__citations"
          ghost
          size="small"
          items={[
            {
              key: 'citations',
              label: `引用依据（${citations.length} 处，可展开核对原文）`,
              children: (
                <Space direction="vertical" size="small" style={{ width: '100%' }}>
                  {citations.map((hit: ChatHit, index: number) => (
                    <div key={`${hit.chunkId}-${index}`}>
                      <Text type="secondary" style={{ fontSize: 11.5 }}>
                        [{index + 1}] {hit.locator || hit.edition || hit.chunkId}
                      </Text>
                      <div className="ai-chat__citation">{hit.text}</div>
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

export function AiChatPage(): JSX.Element {
  const [tenants, setTenants] = useState<PlatformTenant[]>([])
  const [tenantsError, setTenantsError] = useState<string | null>(null)
  const [tenantId, setTenantId] = useState('')
  const [channel, setChannel] = useState<ChatChannel>('corpus')
  const [mode, setMode] = useState<ChatMode>('EXPLAIN')
  const [scopeMode, setScopeMode] = useState<ChatScopeMode>('STRICT')
  const [edition, setEdition] = useState<ChatEdition>(DEFAULT_EDITION)
  const [topK, setTopK] = useState(8)
  const [draft, setDraft] = useState('')
  const [unread, setUnread] = useState(0)

  const listRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<TextAreaRef | null>(null)
  const forceBottomRef = useRef(false)
  const { state, send, stop, retry, newSession } = useAiChat()

  useEffect(() => {
    let cancelled = false
    const run = async (): Promise<void> => {
      try {
        const list = await getPlatformTenants({ limit: 200 })
        if (!cancelled) setTenants(list)
      } catch (cause) {
        if (!cancelled) {
          setTenantsError(cause instanceof ApiError ? cause.message : '读取租户列表失败')
        }
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [])

  const params = useMemo<ChatParams>(
    () => ({ tenantId, mode, edition, scopeMode, topK, channel }),
    [tenantId, mode, edition, scopeMode, topK, channel],
  )

  /** 自动跟随：仅当用户仍在底部附近；上翻阅读时改为「回到底部」胶囊。 */
  const last = state.messages[state.messages.length - 1]
  const scrollSignature = `${state.messages.length}:${last?.status ?? ''}:${last?.content.length ?? 0}:${
    last?.stages?.length ?? 0
  }:${last?.evidence?.length ?? 0}:${last?.thinking?.length ?? 0}:${last?.error?.code ?? ''}`
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

  const isDirect = channel === 'direct'
  const canSend = draft.trim() !== '' && !state.sending && (isDirect || tenantId !== '')

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

  const handleRetry = useCallback((): void => {
    // ⚠️ 只有语料问答强制 X-Tenant-Id；直接对话租户取自登录令牌，
    //    未选租户时也必须能重试，否则 direct 通道的错误气泡上的「重试」是死按钮。
    if (!isDirect && tenantId === '') return
    retry(params)
  }, [isDirect, params, retry, tenantId])

  const handleNewSession = useCallback((): void => {
    newSession()
    setDraft('')
    setUnread(0)
  }, [newSession])

  /** 每个 assistant 气泡对应的用户问题（供「换个问法」回填输入框）。 */
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

  const tenantOptions = useMemo(
    () => tenants.map((t) => ({ value: t.tenantId, label: `${t.name}（${t.slug}）` })),
    [tenants],
  )

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <div>
        <Title level={4} style={{ marginBottom: 4 }}>
          AI 对话
          <Tag color="blue" style={{ marginLeft: 10, verticalAlign: 'middle' }} bordered={false}>
            AI 实验室
          </Tag>
        </Title>
        <Paragraph type="secondary" style={{ marginBottom: 0 }}>
          两种通道：<b>语料问答</b>走现有 RAG 接口（检索 → 装配依据 → 展示回答）；<b>直接对话</b>是真·LLM
          多轮流式端点，逐 token 实时呈现。支持停止、重试、错误与依据不足分支。
        </Paragraph>
      </div>

      <Alert
        type="info"
        showIcon
        message="这条链路的诚实边界"
        description={
          <>
            1）<b>语料问答不是大模型对话</b>：回答由 <code>/api/v1/retrieval</code> +{' '}
            <code>/api/v1/retrieval/generate</code> 产生，当前生成方式恒为{' '}
            <b>EXCERPT_ASSEMBLY（逐字摘录装配）</b>，后端刻意未接 LLM；生成方式与引用会随每条回答展示。
            <br />
            2）<b>直接对话是真·LLM，但只是「管理员本人在聊」</b>：走平台会话令牌，租户取自令牌上下文，
            后端不读取任何租户 Run 数据，与红线 R6「平台控制台不展示运行输出」不冲突。多轮上下文由本页维护并回传。
            <br />
            3）<b>不伪造进度</b>：语料问答按真实阶段增量渲染（检索 → 依据 → 回答）；直接对话的{' '}
            <code>delta</code> 来自真实 token 流，二者都不编造打字机特效或进度百分比。传输标识{' '}
            <code>{ragCorpusTransport.id}</code> ·{' '}
            <Tag color="default" bordered={false}>
              SYNC_PIPELINE
            </Tag>{' '}
            / <code>{DIRECT_GENERATOR_LABEL}</code>。
            <br />
            4）<b>回答排版只作用于直接对话</b>：LLM 输出按 Markdown + LaTeX 渲染
            （<code>$…$</code> / <code>$$…$$</code> / <code>\[…\]</code> / <code>\(…\)</code>），
            可切「原文」核对未重排的模型字节；<b>语料问答的逐字摘录一律原样显示</b>，
            不把摘录里的符号解释成格式。
          </>
        }
      />

      {tenantsError !== null && (
        <Alert type="error" showIcon message="读取租户列表失败" description={tenantsError} />
      )}
      {!isDirect && tenantId === '' && tenantsError === null && (
        <Alert
          type="warning"
          showIcon
          message="请先选择租户"
          description="RAG 检索/生成端点强制要求 X-Tenant-Id 请求头，缺失后端返回 400（不是静默空结果）。未选租户时发送按钮不可用。"
        />
      )}

      <Card title="对话通道与参数" size="small">
        <div className="ai-chat__params">
          <div className="ai-chat__param">
            <span className="ai-chat__param-label">通道</span>
            <Segmented
              value={channel}
              onChange={(v) => setChannel(v as ChatChannel)}
              options={[
                { value: 'corpus', label: '语料问答' },
                { value: 'direct', label: '直接对话' },
              ]}
            />
          </div>
          {!isDirect && (
            <div className="ai-chat__param" data-testid="ai-chat-tenant">
              <span className="ai-chat__param-label">租户（必填，注入 X-Tenant-Id）</span>
              <Select
                showSearch
                style={{ width: 240 }}
                placeholder="选择租户"
                value={tenantId || undefined}
                onChange={(v) => setTenantId(v ?? '')}
                options={tenantOptions}
                filterOption={(inp, opt) =>
                  String(opt?.label ?? '').toLowerCase().includes(inp.toLowerCase())
                }
              />
            </div>
          )}
          {isDirect && (
            <Alert
              type="success"
              showIcon
              style={{ flex: '1 1 100%' }}
              message="直接对话：真·LLM 多轮流式"
              description="与模型实时对话（租户取自你的登录令牌，不读取任何租户 Run 输出）。多轮上下文由本页维护并回传。"
            />
          )}
          {!isDirect && (
            <>
            <div className="ai-chat__param">
            <span className="ai-chat__param-label">检索模式</span>
            <Select
              style={{ width: 240 }}
              value={mode}
              onChange={(v: ChatMode) => setMode(v)}
              options={MODE_OPTIONS}
            />
          </div>
          <div className="ai-chat__param">
            <span className="ai-chat__param-label">版本范围</span>
            <Select
              style={{ width: 220 }}
              value={scopeMode}
              onChange={(v: ChatScopeMode) => setScopeMode(v)}
              options={SCOPE_OPTIONS}
            />
          </div>
          <div className="ai-chat__param">
            <span className="ai-chat__param-label">学科</span>
            <Select
              style={{ width: 100 }}
              value={edition.subject}
              onChange={(v) => setEdition((e) => ({ ...e, subject: v }))}
              options={optionsOf(SUBJECTS)}
            />
          </div>
          <div className="ai-chat__param">
            <span className="ai-chat__param-label">年级</span>
            <Select
              style={{ width: 110 }}
              value={edition.grade}
              onChange={(v) => setEdition((e) => ({ ...e, grade: v }))}
              options={optionsOf(GRADES)}
            />
          </div>
          <div className="ai-chat__param">
            <span className="ai-chat__param-label">册次</span>
            <Select
              style={{ width: 100 }}
              value={edition.term}
              onChange={(v) => setEdition((e) => ({ ...e, term: v }))}
              options={optionsOf(TERMS)}
            />
          </div>
          <div className="ai-chat__param">
            <span className="ai-chat__param-label">出版社</span>
            <Select
              style={{ width: 130 }}
              value={edition.publisher}
              onChange={(v) => setEdition((e) => ({ ...e, publisher: v }))}
              options={optionsOf(PUBLISHERS)}
            />
          </div>
          <div className="ai-chat__param">
            <span className="ai-chat__param-label">版次</span>
            <Input
              style={{ width: 100 }}
              value={edition.revisionCode}
              onChange={(e) => setEdition((prev) => ({ ...prev, revisionCode: e.target.value }))}
            />
          </div>
          <div className="ai-chat__param">
            <span className="ai-chat__param-label">召回条数（1–50）</span>
            <InputNumber
              min={1}
              max={50}
              value={topK}
              onChange={(v) => setTopK(v ?? 8)}
            />
          </div>
          </>
          )}
        </div>
      </Card>

      <Card size="small" className="ai-chat">
        <div className="ai-chat__shell">
          <div
            className="ai-chat__messages"
            ref={listRef}
            onScroll={handleScroll}
            role="log"
            aria-live="polite"
            aria-label="对话消息列表"
          >
            {state.messages.length === 0 ? (
              <div className="ai-chat__empty">
                <Empty description="还没有提问。选好租户与教材版本后，从下面的示例问题开始。" />
                <div className="ai-chat__samples">
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
                  className={`ai-chat__row ai-chat__row--${m.role}${
                    m.role === 'assistant' && m.channel === 'direct' ? ' ai-chat__row--rich' : ''
                  }`}
                >
                  <div className="ai-chat__avatar">
                    {m.role === 'user' ? <UserOutlined /> : <RobotOutlined />}
                  </div>
                  <div className="ai-chat__bubble">
                    <div className="ai-chat__meta">
                      <span>{m.role === 'user' ? '你' : 'AI 对话'}</span>
                      <span>{timeLabel(m.createdAt)}</span>
                      {m.role === 'assistant' && m.elapsedMs !== undefined && (
                        <span>{(m.elapsedMs / 1000).toFixed(1)}s</span>
                      )}
                      {m.role === 'assistant' && m.status === 'streaming' && (
                        <Tag color="processing" bordered={false}>
                          接收中
                        </Tag>
                      )}
                    </div>
                    {m.role === 'user' ? (
                      <div className="ai-chat__text">{m.content}</div>
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
              className="ai-chat__scroll-pill"
              size="small"
              shape="round"
              icon={<ArrowDownOutlined />}
              onClick={scrollToBottom}
            >
              回到底部{unread > 1 ? `（${unread} 条新内容）` : ''}
            </Button>
          )}

          <div className="ai-chat__composer">
            <Input.TextArea
              ref={inputRef}
              rows={2}
              value={draft}
              maxLength={500}
              showCount
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="输入问题…（Enter 发送，Shift+Enter 换行；等待期间仍可继续输入）"
            />
            <div className="ai-chat__actions">
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
                <Button
                  danger
                  icon={<StopOutlined />}
                  disabled={!state.sending}
                  onClick={stop}
                >
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
                共 {state.messages.length} 条 · 会话仅存内存，不写入浏览器存储
              </Text>
            </div>
          </div>
        </div>
      </Card>
    </Space>
  )
}
