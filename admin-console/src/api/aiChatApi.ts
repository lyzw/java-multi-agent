/**
 * AI 实验室 · AI 对话的端点封装（两条通道：路线 A 语料问答 / 路线 B 直接对话）。
 *
 * <p><b>两条通道</b>：
 * <ol>
 *   <li><b>语料问答（corpus）</b>：RAG 服务（独立进程，默认 8081）的
 *       <code>POST /api/v1/retrieval</code> + <code>POST /api/v1/retrieval/generate</code>。
 *       两次调用都带 <code>X-Tenant-Id</code>（后端 <code>requireTenant</code> 缺头即 400，
 *       不是静默空结果），Bearer 令牌用平台会话（<code>aud=admin-console-api</code>）——
 *       RAG 过滤器对平台域令牌不做租户绑定，跨租户语料盘点属设计内行为。</li>
 *   <li><b>直接对话（direct）</b>：平台自身 <code>POST /api/chat/stream</code>
 *       （真·LLM 多轮流式 SSE，走平台会话令牌，租户取自令牌上下文）。
 *       这是<b>管理员自己</b>与模型的实时对话，<b>不</b>是回放租户 Agent Run 输出，
 *       故不触碰红线 R6。前端用 <code>delta</code> 事件逐 token 渲染，终帧带完整正文与用量。</li>
 * </ol>
 *
 * <p><b>⚠️ 诚实边界（不要删）</b>：
 * <ol>
 *   <li><b>语料问答不是大模型对话</b>：<code>/generate</code> 当前恒返回
 *       <code>generator=EXCERPT_ASSEMBLY</code>（逐字摘录装配），
 *       <code>GroundedGenerationService</code> 明确不接 LLM。
 *       页面必须原样展示 <code>generator</code> 与 <code>disclaimer</code>，
 *       只展示 answer 等于把摘录伪装成「AI 讲解」。</li>
 *   <li><b>直接对话仍是「管理员本人在聊」</b>：输入仅来自本次键入内容与系统提示词，
 *       后端不读取任何租户 Run 数据，与红线 R6「平台控制台不展示运行输出」不冲突。</li>
 *   <li><b>不伪造进度</b>：语料问答按<b>真实阶段增量</b>渲染（检索 → 依据 → 回答），
 *       不编造打字机；直接对话的 <code>delta</code> 来自真实 token 流，同样不造假。</li>
 * </ol>
 */

import { redirectLogin, request, ApiError } from '@/api/client'
import { clearSession, loadSession } from '@/auth/tokenStorage'
import { API_BASE } from '@/config/constants'

/** 检索模式（对齐 rag-core `RetrievalRequest.RetrievalMode`，防泄题第一道闸门）。 */
export type ChatMode = 'EXPLAIN' | 'SOLVE' | 'VERIFY' | 'OVERVIEW'

/** 版本范围模式（对齐 rag-core `EditionScope.Mode`）。 */
export type ChatScopeMode = 'STRICT' | 'PREFERRED_WITH_FALLBACK' | 'CROSS_EDITION'

/** 教材版本五轴，值必须用后端枚举的展示名（精确匹配，自由输入会 400）。 */
export interface ChatEdition {
  subject: string
  grade: string
  term: string
  publisher: string
  revisionCode: string
}

/** 对话通道：语料问答（RAG 摘录）或 直接对话（真·LLM 流式）。 */
export type ChatChannel = 'corpus' | 'direct'

/** 直接对话用的单轮历史（多轮上下文由前端维护，整体回传后端）。 */
export interface ChatDirectMessage {
  role: 'user' | 'assistant'
  content: string
}

/** 单个召回片段（对齐 `RetrievalResultDto.HitDto`）。 */
export interface ChatHit {
  chunkId: string
  text: string
  role: string
  score: number
  edition: string
  locator: string
  channel: string
  reason: string
}

interface ChatRetrievalResponse {
  hits: ChatHit[]
  versionMismatchDetected: boolean
  answerContentSuppressed: boolean
  primaryEdition: string
}

interface ChatGenerateResponse {
  answer: string
  citations: ChatHit[]
  generator: string
  disclaimer: string
  versionMismatchDetected: boolean
  answerContentSuppressed: boolean
}

/** 一轮对话的完整入参（query 由输入框给出，其余由页面参数区给出）。 */
export interface ChatRequest {
  tenantId: string
  query: string
  mode: ChatMode
  edition: ChatEdition
  scopeMode: ChatScopeMode
  /** 1~50；同时是生成端点 hitIds 的上界 */
  topK: number
  /** 通道：corpus=语料问答，direct=真·LLM 多轮流式（默认 corpus）。 */
  channel?: ChatChannel
  /** 直接对话的多轮历史（不含本次 query，transport 会拼上）。 */
  history?: ChatDirectMessage[]
  /** 直接对话可调参数（透传后端）。 */
  modelId?: string
  systemPrompt?: string
  temperature?: number
  maxTokens?: number
}

/** 真实到达的阶段：只对应一次已发起/已完成的服务端调用。 */
export type ChatStage = 'retrieving' | 'generating'

export const CHAT_STAGE_LABELS: Record<ChatStage, string> = {
  retrieving: '检索语料',
  generating: '装配依据',
}

/** 直接对话通道对外的生成方式标识（随答案展示，与语料问答的 generator 同源字段）。 */
export const DIRECT_GENERATOR_LABEL = 'LLM·MiniMax-M2.5'

/**
 * 流式事件。<b>每个事件都由一次真实服务端交互产生</b>，没有定时器合成的事件。
 *
 * `stage` 在请求发出时推送；`evidence` / `answer` 在响应落地后推送；
 * `delta` 来自真·LLM 直接对话的逐 token 流。
 * `no-basis` 是合法业务结果（检索零命中，或依据被闸门滤光），**不是失败**。
 */
export type ChatStreamEvent =
  | { type: 'stage'; stage: ChatStage; at: number }
  | { type: 'evidence'; hits: ChatHit[]; primaryEdition: string; at: number }
  | { type: 'delta'; text: string; at: number }
  | { type: 'thinking'; text: string; at: number }
  | {
      type: 'answer'
      answer: string
      citations: ChatHit[]
      generator: string
      disclaimer: string
      versionMismatchDetected: boolean
      answerContentSuppressed: boolean
      at: number
    }
  | { type: 'no-basis'; reason: 'NO_HITS' | 'GATE_SUPPRESSED'; at: number }
  | { type: 'done'; at: number }

/** 停止/取消：与「失败」严格区分（用户主动 ≠ 系统坏了）。 */
export class ChatCancelledError extends Error {
  constructor() {
    super('本次回答已由用户停止')
    this.name = 'ChatCancelledError'
  }
}

export function isChatCancelled(cause: unknown): boolean {
  return cause instanceof ChatCancelledError
}

/** 超时用独立状态码，不与「网络层失败」(0) / HTTP 408 混淆。 */
export const CHAT_TIMEOUT_STATUS = -1

const RETRIEVAL_TIMEOUT_MS = 20_000
const GENERATE_TIMEOUT_MS = 20_000

/** 归一后的错误：code 显式带出，便于拿去查审计/日志。 */
export interface ChatErrorInfo {
  code: string
  message: string
  hint: string
  /** 是否值得显示「重试」：401/403/400 这类重试一万次也不会变对 */
  retryable: boolean
}

/**
 * 单次 POST 的公共封装：超时 + 外部 AbortSignal + X-Tenant-Id。
 *
 * ⚠️ 超时用独立 AbortController，再桥接外部 signal：这样「用户点停止」与
 *    「20 秒没回」都走同一条 abort 通路，但错误码不同（CHAT_TIMEOUT / 取消）。
 * ⚠️ `client.ts` 会把 fetch 异常统一包成 `ApiError(0, NETWORK_ERROR)`，
 *    所以 abort 后必须先看外部 signal 与 timedOut 旗标，不能只靠错误对象判断。
 */
async function chatPost<T>(
  path: string,
  body: unknown,
  tenantId: string,
  outerSignal: AbortSignal,
  timeoutMs: number,
): Promise<T> {
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  const relayAbort = (): void => controller.abort()
  outerSignal.addEventListener('abort', relayAbort, { once: true })
  if (outerSignal.aborted) controller.abort()

  try {
    return await request<T>(
      path,
      { method: 'POST', body: JSON.stringify(body), signal: controller.signal },
      true,
      { 'X-Tenant-Id': tenantId },
    )
  } catch (cause) {
    if (outerSignal.aborted) throw new ChatCancelledError()
    if (timedOut) {
      throw new ApiError(
        CHAT_TIMEOUT_STATUS,
        '等待回答超时，后端没有在规定时间内返回。',
        undefined,
        'CHAT_TIMEOUT',
      )
    }
    // ⚠️ RAG 服务与平台会话是**同一身份域**（验的就是平台签发的会话令牌）。
    //    共享层只把 `/platform/**` 的 401 当会话失效，`/v1/**` 会被误判成
    //    「跨身份域边界」；ragClient.ts 2026-11-03 已修过一次，本模块不能重犯。
    //    另外要求本地确有会话：没有会话的 401 是配置/验签问题（两端 JWT 密钥不同源），
    //    原样抛出才能走到 401 分支里那条可执行的排查指引。
    if (cause instanceof ApiError && cause.status === 401 && loadSession() !== null) {
      clearSession()
      redirectLogin()
      throw new ApiError(401, '登录已过期，请重新登录', cause.data, cause.code)
    }
    throw cause
  } finally {
    clearTimeout(timer)
    outerSignal.removeEventListener('abort', relayAbort)
  }
}

function assertNotCancelled(signal: AbortSignal): void {
  if (signal.aborted) throw new ChatCancelledError()
}

export interface ChatSendContext {
  signal: AbortSignal
  onEvent: (event: ChatStreamEvent) => void
}

/**
 * 问答传输层接口。
 *
 * ⚠️ 规划 §6 路线 B 的 SSE 端点上线后，只需再写一个实现替换
 *    {@link ragCorpusTransport}，页面与状态机一行都不用改。
 *    接口刻意只承诺「按到达顺序推事件」，不承诺「逐 token」——
 *    同步端点同样能满足这个契约，不需要伪装。
 */
export interface ChatTransport {
  readonly id: string
  readonly streaming: boolean
  send(params: ChatRequest, ctx: ChatSendContext): Promise<void>
}

/**
 * 当前唯一实现：RAG 语料问答（同步两步 → 阶段增量事件）。
 *
 * `streaming=false` 是如实标注「无逐 token 流」，页面把它显示出来，
 * 而不是在 UI 上偷偷画一个打字机。
 */
export const ragCorpusTransport: ChatTransport = {
  id: 'RAG_CORPUS_QA',
  streaming: false,
  async send(params: ChatRequest, ctx: ChatSendContext): Promise<void> {
    const { signal, onEvent } = ctx
    const { query, mode, edition, scopeMode, tenantId, topK } = params

    onEvent({ type: 'stage', stage: 'retrieving', at: Date.now() })
    const retrieved = await chatPost<ChatRetrievalResponse>(
      '/v1/retrieval',
      { query, mode, edition, scopeMode, topK },
      tenantId,
      signal,
      RETRIEVAL_TIMEOUT_MS,
    )
    assertNotCancelled(signal)
    onEvent({
      type: 'evidence',
      hits: retrieved.hits,
      primaryEdition: retrieved.primaryEdition,
      at: Date.now(),
    })

    // 零命中：后端 generate 的 hitIds 是 @NotEmpty，硬发过去只会吃一个 400。
    // 「语料里没有依据」是合法业务结果，如实收口为 no-basis，不伪装成系统故障。
    if (retrieved.hits.length === 0) {
      onEvent({ type: 'no-basis', reason: 'NO_HITS', at: Date.now() })
      onEvent({ type: 'done', at: Date.now() })
      return
    }

    onEvent({ type: 'stage', stage: 'generating', at: Date.now() })
    const generated = await chatPost<ChatGenerateResponse>(
      '/v1/retrieval/generate',
      {
        query,
        mode,
        edition,
        scopeMode,
        // ⚠️ 依据必须来自上一轮检索：后端不自行检索，否则引用不可复现。
        hitIds: retrieved.hits.map((h) => h.chunkId).slice(0, 50),
      },
      tenantId,
      signal,
      GENERATE_TIMEOUT_MS,
    )
    assertNotCancelled(signal)

    // 依据被闸门滤光 → 200 + 空 answer，同样是合法业务结果（后端契约明确）。
    if (generated.answer.trim() === '') {
      onEvent({ type: 'no-basis', reason: 'GATE_SUPPRESSED', at: Date.now() })
      onEvent({ type: 'done', at: Date.now() })
      return
    }

    onEvent({
      type: 'answer',
      answer: generated.answer,
      citations: generated.citations,
      generator: generated.generator,
      disclaimer: generated.disclaimer,
      versionMismatchDetected: generated.versionMismatchDetected,
      answerContentSuppressed: generated.answerContentSuppressed,
      at: Date.now(),
    })
    onEvent({ type: 'done', at: Date.now() })
  },
}

/**
 * 直接对话通道：真·LLM 多轮流式 SSE（平台端点 `/api/chat/stream`）。
 *
 * `streaming=true` 如实标注——这是真·逐 token 流，页面用 `delta` 事件实时渲染成品、
 * 用 `thinking` 事件实时渲染模型的思考过程（推理草稿，折叠展示）。
 * 多轮上下文由页面维护并整体回传（{@link ChatRequest.history}），后端无状态。
 */
export const directChatTransport: ChatTransport = {
  id: 'DIRECT_LLM_SSE',
  streaming: true,
  async send(params: ChatRequest, ctx: ChatSendContext): Promise<void> {
    const { signal, onEvent } = ctx
    const { query, tenantId, history, modelId, systemPrompt, temperature, maxTokens } = params

    const messages: ChatDirectMessage[] = [
      ...(history ?? []),
      { role: 'user', content: query },
    ]

    onEvent({ type: 'stage', stage: 'generating', at: Date.now() })
    const res = await chatStreamPost(
      '/chat/stream',
      { messages, modelId, systemPrompt, temperature, maxTokens },
      tenantId,
      signal,
    )
    assertNotCancelled(signal)

    const reader = res.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let eventName = ''
    let dataLines: string[] = []

    const flush = (): void => {
      const data = dataLines.join('\n')
      if (eventName === 'delta') {
        onEvent({ type: 'delta', text: data, at: Date.now() })
      } else if (eventName === 'thinking') {
        onEvent({ type: 'thinking', text: data, at: Date.now() })
      } else if (eventName === 'done') {
        let content = ''
        try {
          const payload = JSON.parse(data) as {
            content?: string
            finishReason?: string
          }
          content = payload.content ?? ''
        } catch {
          // 终帧解析失败也不阻塞：增量已经逐帧到达
        }
        onEvent({
          type: 'answer',
          answer: content,
          citations: [],
          generator: DIRECT_GENERATOR_LABEL,
          disclaimer: '',
          versionMismatchDetected: false,
          answerContentSuppressed: false,
          at: Date.now(),
        })
      } else if (eventName === 'error') {
        let kind = 'PROVIDER_ERROR'
        let message = '模型调用失败'
        try {
          const payload = JSON.parse(data) as { kind?: string; message?: string }
          kind = payload.kind ?? kind
          message = payload.message ?? message
        } catch {
          // 保留默认值
        }
        throw new ApiError(mapKindToStatus(kind), message, undefined, kind)
      }
      eventName = ''
      dataLines = []
    }

    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        if (signal.aborted) throw new ChatCancelledError()
        buffer += decoder.decode(value, { stream: true })
        let idx: number
        while ((idx = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 1)
          if (line === '') {
            flush()
          } else if (line.startsWith('event:')) {
            eventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).replace(/^ /, ''))
          }
        }
      }
      if (eventName !== '' || dataLines.length > 0) flush()
      onEvent({ type: 'done', at: Date.now() })
    } finally {
      reader.cancel().catch(() => {})
    }
  },
}

/** 直接对话通道的流式 POST：超时/取消/401 与 `chatPost` 同源纪律，返回原始响应流。 */
async function chatStreamPost(
  path: string,
  body: unknown,
  tenantId: string,
  signal: AbortSignal,
): Promise<Response> {
  const headers = new Headers()
  const s = loadSession()
  if (s) headers.set('Authorization', `Bearer ${s.accessToken}`)
  headers.set('Content-Type', 'application/json')
  headers.set('Accept', 'text/event-stream')
  headers.set('X-Accel-Buffering', 'no')
  if (tenantId) headers.set('X-Tenant-Id', tenantId)

  let res: Response
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method: 'POST',
      body: JSON.stringify(body),
      headers,
      signal,
    })
  } catch (e) {
    if (signal.aborted) throw new ChatCancelledError()
    throw new ApiError(0, `网络错误：${(e as Error).message}`, undefined, 'NETWORK_ERROR')
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    if (signal.aborted) throw new ChatCancelledError()
    // 平台会话域 401 = 令牌失效（本端点走平台会话令牌）
    if (res.status === 401 && loadSession() !== null) {
      clearSession()
      redirectLogin()
      throw new ApiError(401, '登录已过期，请重新登录', undefined, 'UNAUTHENTICATED')
    }
    throw new ApiError(res.status, text || `请求失败（${res.status}）`, undefined, `HTTP_${res.status}`)
  }
  return res
}

/** 直接对话错误 kind → HTTP 状态码（供 {@link describeChatError} 按状态码/code 分支）。 */
function mapKindToStatus(kind: string): number {
  switch (kind) {
    case 'UNAUTHORIZED':
      return 401
    case 'RATE_LIMITED':
      return 429
    case 'INVALID_REQUEST':
      return 400
    case 'CONTENT_BLOCKED':
      return 422
    case 'TIMEOUT':
      return 504
    default:
      return 502
  }
}

/** 页面调用入口（保留间接层，按通道选传输实现）。 */
export function sendChatMessage(params: ChatRequest, ctx: ChatSendContext): Promise<void> {
  const transport = params.channel === 'direct' ? directChatTransport : ragCorpusTransport
  return transport.send(params, ctx)
}

/**
 * 错误归一化 —— 组件层只读这里的产物，不直接判 HTTP 状态码。
 *
 * ⚠️ 文案要求「具体原因 + 可执行的下一步」，不写笼统的「请求失败」；
 *    429 明确不做自动重试（会继续烧配额），401 提示核对 RAG 与平台 JWT 密钥同源。
 */
export function describeChatError(cause: unknown): ChatErrorInfo {
  if (cause instanceof ApiError) {
    const code = cause.code
    // ⚠️ 直接对话通道的归一化 kind（event: error 帧携带）：优先按 code 分支，
    // 给出比泛化 HTTP 状态更贴合的文案（如「模型凭据未授权」vs 笼统 401）。
    switch (code) {
      case 'UNAUTHORIZED':
        return {
          code,
          message: '登录已过期，或模型凭据未授权',
          hint: '重新登录；若仍失败，核对平台 MiniMax 凭据是否已装配。',
          retryable: false,
        }
      case 'RATE_LIMITED':
        return {
          code,
          message: '模型调用过于频繁（429）',
          hint: '稍后重试；本页不做自动重试，避免继续消耗配额。',
          retryable: true,
        }
      case 'INVALID_REQUEST':
        return {
          code,
          message: '请求被拒绝（400）',
          hint: '检查直接对话参数（模型名 / 温度 / 最大 token）是否合法。',
          retryable: false,
        }
      case 'CONTENT_BLOCKED':
        return {
          code,
          message: '内容被安全策略拦截',
          hint: '换一种问法；该回答触发了输出护栏（fail-closed）。',
          retryable: false,
        }
      case 'PROVIDER_ERROR':
      case 'TIMEOUT':
        return {
          code,
          message: code === 'TIMEOUT' ? '模型响应超时' : '模型服务暂时不可用',
          hint: '稍后重试；持续失败请查看平台模型网关日志。',
          retryable: true,
        }
    }
    if (code === 'CHAT_TIMEOUT' || cause.status === CHAT_TIMEOUT_STATUS) {
      return {
        code,
        message: '等待回答超时',
        hint: '可以稍后重试，或缩小检索范围（降低召回条数 / 换更具体的问题）。',
        retryable: true,
      }
    }
    switch (cause.status) {
      case 400:
        return {
          code,
          message: '请求被拒绝（400）',
          hint: '检查租户是否已选、教材版本五轴是否为后端枚举展示名、检索模式与版本范围是否合法。',
          retryable: false,
        }
      case 401:
        return {
          code,
          message: '登录已过期，或平台令牌未被 RAG 服务接受',
          hint: '重新登录后再试；若仍失败，核对 PLATFORM_JWT_PRIVATE_KEY 与 RAG_PLATFORM_JWT_PUBLIC_KEY 是否同源。',
          retryable: false,
        }
      case 403:
        return {
          code,
          message: '无权在该租户的语料上检索（403）',
          hint: '确认当前平台账号具备语料读取权限，或换一个租户。',
          retryable: false,
        }
      case 404:
      case 501:
        return {
          code,
          message: '问答能力未接入',
          hint: 'RAG 服务可能未部署 /generate 端点，请联系后端确认，前端不提供必然失败的入口。',
          retryable: false,
        }
      case 429:
        return {
          code,
          message: '提问太频繁（429）',
          hint: '后端已限流，请稍等再试；本页不做自动重试，避免继续消耗配额。',
          retryable: true,
        }
      case 0:
        return {
          code,
          message: '网络不可达，或请求已被中止',
          hint: '确认 RAG 服务（默认 8081）可访问、开发代理 /api/v1 规则生效。',
          retryable: true,
        }
      default:
        if (cause.status >= 500) {
          return {
            code,
            message: `RAG 服务不可用（${cause.status}）`,
            hint: '稍后重试；持续失败请查看 RAG 服务健康探针 /actuator/health。',
            retryable: true,
          }
        }
    }
    return { code, message: cause.message, hint: '可重试一次；若仍失败请把错误码交给后端排查。', retryable: true }
  }
  return {
    code: 'UNKNOWN',
    message: cause instanceof Error ? cause.message : '未知错误',
    hint: '可重试一次；若仍失败请查看浏览器控制台与后端日志。',
    retryable: true,
  }
}
