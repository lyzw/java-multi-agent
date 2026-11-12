/**
 * AI 实验室 · 通用 Agent 编排对话的传输层（SSE）。
 *
 * <p><b>这是什么</b>：一条「通用 agent 聊天」事件协议 + 两种传输实现。协议把一次 agent 执行
 * 拆成可观测的阶段：路由 → RAG 检索/注入 → Skill 加载执行 → Tool 调用/结果回传 → 思考草稿 →
 * 流式回答 → 用量/终帧。每一帧都是<b>到达即渲染</b>的真实事件，前端不合成打字机、不编造进度。
 *
 * <p><b>⚠️ 后端现状（诚实边界，不要删）</b>：平台目前只有两种流——
 * <ol>
 *   <li>{@code /api/chat/stream}：纯 LLM 多轮流，只发 {@code delta}/{@code thinking}/{@code done}/{@code error}，
 *       <b>不做</b>路由/RAG/Tool/Skill 编排；</li>
 *   <li>{@code /api/runs/{id}/events}：Run 执行事件的短连接轮询，白名单状态字段。</li>
 * </ol>
 * 本文件定义的 {@code POST /api/agent/chat/stream} 已接入真实后端编排（路由 → RAG → Skill/Tool → 流式回答 + Run 审计）。
 * 端点未部署时 live 模式会如实收到 404/501 并显示「能力未接入」，<b>绝不</b>退化成假的流式回答。
 * 想无后端依赖地预览完整链路，可显式切到 {@link sampleAgentChatTransport}（样例回放，页面有醒目告警）。
 *
 * <p><b>线格式（与现有 ConsoleChatController 同源约定）</b>：
 * <ul>
 *   <li>{@code event: thinking} / {@code event: delta}：data 是<b>裸文本</b>（逐 token 增量），
 *       与 {@code /api/chat/stream} 完全一致；</li>
 *   <li>其余事件：data 是 JSON 对象；</li>
 *   <li>{@code event: done}：应用层终帧，收到即收口，<b>不等 TCP EOF</b>
 *       （SseEmitter 经反代后 chunked 流可能不干净结束）。</li>
 * </ul>
 *
 * <p><b>身份域</b>：本模块属平台控制台（{@code aud=admin-console-api}），端点为平台链
 * {@code /api/agent/chat/stream}；<b>绝不</b>复用租户前缀 {@code /api/knowledge/**}。
 *
 * <p><b>路径约定</b>：{@link AGENT_LIVE_STREAM_PATH} 是相对 {@code API_BASE}（= '/api'）
 * 的路径，与 {@code aiChatApi} 一致；实际请求 URL 由 {@link resolveAgentEndpoint} 拼出。
 * 这样开发代理与生产反向代理只需维护一处 /api 规则。
 */
import { redirectLogin, ApiError } from '@/api/client'
import { loadSession, clearSession, isExpired } from '@/auth/tokenStorage'
import { API_BASE } from '@/config/constants'

/** 平台链编排端点（相对 API_BASE；已接入真实后端编排；当前环境未部署时 live 模式如实报错）。 */
export const AGENT_LIVE_STREAM_PATH = '/agent/chat/stream'

/** 把相对路径解析为实际请求 URL（也允许测试注入完整 URL 做 mock）。 */
export function resolveAgentEndpoint(endpoint: string = AGENT_LIVE_STREAM_PATH): string {
  return endpoint.startsWith('http://') || endpoint.startsWith('https://') || endpoint.startsWith('/api/')
    ? endpoint
    : `${API_BASE}${endpoint}`
}
/** 整条 SSE 长连接的兜底超时（与后端 SseEmitter 超时解耦）。 */
export const AGENT_LIVE_TIMEOUT_MS = 120_000
/** 单条超时独立状态码，避免与网络失败(0)/HTTP 408 混淆。 */
export const AGENT_TIMEOUT_STATUS = -1

/** 样例回放标识（页面据此显示「非真实模型输出」告警）。 */
export const AGENT_SAMPLE_TRANSPORT_ID = 'AGENT_SAMPLE_REPLAY'
/** 样例回放默认帧间隔（毫秒）；既有流式体感，也让集成测试可在数秒内跑完。 */
export const SAMPLE_FRAME_DELAY_MS = 40

/* ── 通用协议类型 ─────────────────────────────────────────── */

/** 数据来源：live=真实端点；sample=本地样例回放（非真实输出）。 */
export type AgentChatSource = 'live' | 'sample'

/** 样例场景：覆盖主干与各类异常，确保错误处理有真实状态。 */
export type SampleScenario = 'full' | 'skill-error' | 'tool-error' | 'rag-empty'

/** 多轮上下文单条（后端无状态，历史由页面维护并整体回传）。 */
export interface AgentChatTurn {
  role: 'user' | 'assistant'
  content: string
}

/** 路由候选。 */
export interface AgentCandidate {
  agentId: string
  name: string
  score?: number
  reason?: string
}

/** RAG 命中片段（对齐 rag-core，仅展示白名单字段）。 */
export interface AgentHit {
  chunkId: string
  text: string
  score: number
  locator: string
  edition: string
  role: string
  reason: string
}

/** 组件步骤状态。 */
export type AgentStepStatus = 'pending' | 'running' | 'success' | 'error' | 'info'

/**
 * 协议 v1.1 第 14 事件的载荷（**只解析、只内存暂存**不透明 state）。
 *
 * <p>⚠️ {@code state} 是服务端签名的**不透明基串**，前端只做形状校验，
 * ⛔ 绝不解析/解密/渲染其正文，也⛔ 不写入任何 localStorage/sessionStorage；
 * 请求侧携带 {@code contextState} 属于 M8 + G1 签核后的能力，当前请求体逐位不变。
 */
export interface AgentContextCheckpoint {
  state: string
  upToTurn?: number
  policyVersion: string
  tier: number
  fallback: boolean
  digest: string
}

/**
 * 页面消费的流式事件：每一条都对应一帧真实到达（live 来自服务端，sample 来自脚本化的帧）。
 * thinking/delta 是增量；answer 是终帧正文兜底（绝不回撤已到达的增量）；done 是应用层收口。
 */
export type AgentStreamEvent =
  | { type: 'run.start'; runId: string; at: number }
  | {
      type: 'route'
      agentId: string
      agentName: string
      reason: string
      candidates: AgentCandidate[]
      at: number
    }
  | { type: 'rag.query'; query: string; topK: number; at: number }
  | { type: 'rag.hits'; hits: AgentHit[]; injected: boolean; at: number }
  | {
      type: 'skill'
      skillId: string
      skillName: string
      phase: 'load' | 'run' | 'result' | 'error'
      status: AgentStepStatus
      message?: string
      result?: string
      error?: string
      at: number
    }
  | {
      type: 'tool'
      toolCallId: string
      toolName: string
      phase: 'call' | 'run' | 'result' | 'error'
      status: AgentStepStatus
      args?: string
      result?: string
      error?: string
      at: number
    }
  | { type: 'handoff'; from: string; note: string; at: number }
  | { type: 'thinking'; text: string; at: number }
  | { type: 'delta'; text: string; at: number }
  | { type: 'answer'; text: string; at: number }
  | { type: 'usage'; inputTokens: number; outputTokens: number; at: number }
  | { type: 'error'; code: string; message: string; at: number }
  /** 协议 v1.1 第 14 事件：只解析、只内存暂存不透明 state，S3-M13 起由 hook 逐字上送。 */
  | ({ type: 'context.checkpoint' } & AgentContextCheckpoint & { at: number })
  /** truncated=true 表示流在未收到服务端终帧时结束（断流 ≠ 失败）。 */
  | { type: 'done'; truncated: boolean; contextStateVersion?: string; at: number }

/** 单次调用入参。 */
export interface AgentChatRequest {
  message: string
  history: AgentChatTurn[]
  source: AgentChatSource
  thinkingMode: boolean
  agentId?: string
  topK?: number
  tenantId?: string
  /** 仅 sample 模式消费；live 忽略。 */
  sampleScenario?: SampleScenario
  /** live 端点覆盖（测试/私有部署用）；缺省为 {@link AGENT_LIVE_STREAM_PATH}。 */
  endpoint?: string
  /**
   * S3-M13 正式收发：上一轮「非截断 done」提交的压缩态，不透明串逐字上送。
   * ⛔ 只能由 useAgentChat 从 currentContextCheckpoint 注入；页面不得传，响应字段
   * contextStateVersion 更不得反向进请求。首轮 / 刷新后缺省 undefined，JSON 序列化自动省略该键。
   */
  contextState?: string
}

export interface AgentChatSendContext {
  signal: AbortSignal
  onEvent: (event: AgentStreamEvent) => void
}

/**
 * 传输接口。⚠️ 与现有 aiChatApi.ChatTransport 同构：页面/状态机只认「按到达顺序推事件」，
 * 换传输实现时一行不用改。{@code simulated} 让 UI 能如实标注样例回放。
 */
export interface AgentChatTransport {
  readonly id: string
  readonly label: string
  readonly simulated: boolean
  send(params: AgentChatRequest, ctx: AgentChatSendContext): Promise<void>
}

/** 归一化错误：组件只读这里，不直接判 HTTP 状态码。 */
export interface AgentChatError {
  code: string
  message: string
  hint: string
  retryable: boolean
  status?: number
}

/** 传输层已归一化的失败。 */
export class AgentChatFailure extends Error {
  readonly info: AgentChatError

  constructor(info: AgentChatError) {
    super(info.message)
    this.name = 'AgentChatFailure'
    this.info = info
  }
}

/** 用户主动停止：与「失败」严格区分。 */
export class AgentChatCancelledError extends Error {
  constructor() {
    super('本次回答已由用户停止')
    this.name = 'AgentChatCancelledError'
  }
}

export function isAgentChatCancelled(cause: unknown): boolean {
  return cause instanceof AgentChatCancelledError
}

/* ── 错误归一化 ───────────────────────────────────────────── */

function errorFromHttp(status: number, body: unknown, path: string): AgentChatError {
  let code = `HTTP_${status}`
  let serverMessage = ''
  if (body && typeof body === 'object') {
    const rec = body as Record<string, unknown>
    if (typeof rec['code'] === 'string') code = rec['code']
    if (typeof rec['message'] === 'string') serverMessage = rec['message']
  }
  if (status === 401) {
    // ⚠️ 只有「本地会话确实已过期」才由 live 传输转成 UNAUTHENTICATED 并跳登录；
    //    这里的 401 是会话仍有效却被端点拒绝 = 身份域 / 部署错配，
    //    一律不清会话、不登出，否则用户会丢掉整段内存里的对话草稿。
    return {
      code: 'CHANNEL_UNAUTHENTICATED',
      message: '平台令牌未通过编排端点的身份认证',
      hint: '会话仍有效，说明端点可能挂在错误的身份域上（应为平台链 /api/agent/**，不是租户链 /api/knowledge/**）；请后端核对安全链，本页不会因此登出。',
      retryable: false,
      status,
    }
  }
  if (status === 403) {
    return {
      code: 'ACCESS_DENIED',
      message: '无权使用 Agent 编排对话',
      hint: '确认当前平台账号具备相应权限；本页不会因此登出。',
      retryable: false,
      status,
    }
  }
  if (status === 404 || status === 501) {
    return {
      code: 'CAPABILITY_NOT_INSTALLED',
      message: `当前环境未部署编排对话端点（POST ${path}）`,
      hint: '确认后端版本已包含平台链编排 SSE 端点（run/route/rag/skill/tool/handoff/delta/done）；若为旧环境请升级后重试。也可切到「样例回放」预览完整链路（非真实输出）。',
      retryable: false,
      status,
    }
  }
  if (status === 429) {
    return {
      code: 'RATE_LIMITED',
      message: '编排对话连接过于频繁（429）',
      hint: '稍后重试；本页不做自动重试，避免继续消耗配额。',
      retryable: true,
      status,
    }
  }
  if (status === 400) {
    return {
      code: 'INVALID_REQUEST',
      message: '请求被拒绝（400）',
      hint: '检查 AgentId / topK / 历史消息是否合法，或换一种问法。',
      retryable: false,
      status,
    }
  }
  if (status >= 500) {
    return {
      code: 'SERVER_ERROR',
      message: serverMessage || `编排服务暂时不可用（${status}）`,
      hint: '稍后重试；持续失败请查看平台服务健康探针与模型网关日志。',
      retryable: true,
      status,
    }
  }
  return {
    code,
    message: serverMessage || `请求失败（${status}）`,
    hint: '可重试一次；若仍失败请把错误码交给后端排查。',
    retryable: true,
    status,
  }
}

/** SSE error 帧的 kind → 面向用户的文案（含可执行的下一步）。 */
function errorFromFrame(kind: string, message: string): AgentChatError {
  switch (kind) {
    case 'UNAUTHORIZED':
      return {
        code: kind,
        message: '登录已过期，或模型凭据未授权',
        hint: '重新登录；若仍失败请核对平台模型凭据是否已装配。',
        retryable: false,
      }
    case 'CONFIG_UNAVAILABLE':
      return {
        code: kind,
        message: 'Agent 配置不可用',
        hint: '所选 Agent 可能没有已发布版本，或缺少 instructions/modelRef；请先在治理页发布新版本。',
        retryable: false,
      }
    case 'ROUTE_FAILED':
      return {
        code: kind,
        message: 'Agent 路由失败（fail-closed）',
        hint: '没有匹配的已发布 Agent；请检查路由策略或显式指定 AgentId。',
        retryable: false,
      }
    case 'RAG_UNAVAILABLE':
      return {
        code: kind,
        message: '知识检索服务不可用',
        hint: '稍后重试；持续失败请检查 RAG 服务健康探针与租户语料授权。',
        retryable: true,
      }
    case 'TOOL_EXECUTION_FAILED':
      return {
        code: kind,
        message: '工具执行失败',
        hint: '本次回答可能已降级；稍后重试，或检查工具的审批/凭据配置。',
        retryable: true,
      }
    case 'SKILL_EXECUTION_FAILED':
      return {
        code: kind,
        message: 'Skill 执行失败',
        hint: '本次回答可能已降级；稍后重试，或检查 Skill 的闭包声明是否自洽。',
        retryable: true,
      }
    case 'CONTENT_BLOCKED':
      return {
        code: kind,
        message: '内容被安全策略拦截',
        hint: '换一种问法；该回答触发了输出护栏（fail-closed）。',
        retryable: false,
      }
    case 'TIMEOUT':
      return {
        code: kind,
        message: '模型响应超时',
        hint: '稍后重试；持续失败请查看模型网关日志。',
        retryable: true,
      }
    default:
      return {
        code: kind || 'PROVIDER_ERROR',
        message: message || '编排服务暂时不可用',
        hint: '稍后重试；持续失败请把错误码交给后端排查。',
        retryable: true,
      }
  }
}

/** 组件层唯一错误出口。 */
export function describeAgentChatError(cause: unknown): AgentChatError {
  if (cause instanceof AgentChatFailure) return cause.info
  if (cause instanceof ApiError) {
    return errorFromHttp(cause.status, cause.data, resolveAgentEndpoint())
  }
  return {
    code: 'UNKNOWN',
    message: cause instanceof Error ? cause.message : '未知错误',
    hint: '可重试一次；若仍失败请查看浏览器控制台与后端日志。',
    retryable: true,
  }
}

/* ── SSE 帧解析与事件解码 ─────────────────────────────────── */

/** 一帧 SSE（只保留 event/data；id/retry 本协议不用）。导出供协议守卫直接构造帧。 */
export interface AgentSseFrame {
  event: string | null
  data: string | null
}

function parseBlock(block: string): AgentSseFrame | null {
  let event: string | null = null
  const dataLines: string[] = []
  for (const raw of block.split(/\r?\n/)) {
    const line = raw.replace(/^\uFEFF/, '')
    if (line === '' || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    else if (field === 'data') dataLines.push(value)
    // id / retry / 未知字段：忽略（fail-closed，不据此推进状态）
  }
  if (event === null && dataLines.length === 0) return null
  return { event, data: dataLines.length > 0 ? dataLines.join('\n') : null }
}

function nextBlock(buffer: string): { block: string; rest: string } | null {
  const match = /\r?\n\r?\n/.exec(buffer)
  if (match === null) return null
  return {
    block: buffer.slice(0, match.index),
    rest: buffer.slice(match.index + match[0].length),
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null
    ? (value as Record<string, unknown>)
    : null
}

function parseJson(data: string): Record<string, unknown> | null {
  try {
    return asRecord(JSON.parse(data))
  } catch {
    return null
  }
}

function str(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined
}

function num(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function bool(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined
}

function candidates(value: unknown): AgentCandidate[] {
  if (!Array.isArray(value)) return []
  const out: AgentCandidate[] = []
  for (const item of value) {
    const rec = asRecord(item)
    if (rec === null) continue
    const agentId = str(rec['agentId'])
    const name = str(rec['name'])
    if (agentId === undefined || name === undefined) continue
    out.push({
      agentId,
      name,
      score: num(rec['score']),
      reason: str(rec['reason']),
    })
  }
  return out
}

function hits(value: unknown): AgentHit[] {
  if (!Array.isArray(value)) return []
  const out: AgentHit[] = []
  for (const item of value) {
    const rec = asRecord(item)
    if (rec === null) continue
    const chunkId = str(rec['chunkId'])
    const text = str(rec['text'])
    if (chunkId === undefined || text === undefined) continue
    out.push({
      chunkId,
      text,
      score: num(rec['score']) ?? 0,
      locator: str(rec['locator']) ?? '',
      edition: str(rec['edition']) ?? '',
      role: str(rec['role']) ?? '',
      reason: str(rec['reason']) ?? '',
    })
  }
  return out
}

function toStatus(value: unknown): AgentStepStatus {
  const raw = str(value)
  switch (raw) {
    case 'pending':
    case 'running':
    case 'success':
    case 'error':
    case 'info':
      return raw
    default:
      return 'running'
  }
}

/**
 * 单帧 → 事件。<b>未知事件返回 null（fail-closed）</b>，绝不据此伪造阶段。
 * thinking/delta 的 data 是裸文本；其余事件的 data 是 JSON。
 * 导出供守卫测试逐事件验证协议覆盖。
 */
export function decodeAgentFrame(frame: AgentSseFrame): AgentStreamEvent | null {
  const name = frame.event ?? ''
  const raw = frame.data ?? ''
  const at = Date.now()
  switch (name) {
    case 'run.start': {
      const p = parseJson(raw)
      return { type: 'run.start', runId: str(p?.['runId']) ?? '', at }
    }
    case 'route': {
      const p = parseJson(raw)
      return {
        type: 'route',
        agentId: str(p?.['agentId']) ?? '',
        agentName: str(p?.['agentName']) ?? str(p?.['name']) ?? '',
        reason: str(p?.['reason']) ?? '',
        candidates: candidates(p?.['candidates']),
        at,
      }
    }
    case 'rag.query': {
      const p = parseJson(raw)
      return {
        type: 'rag.query',
        query: str(p?.['query']) ?? '',
        topK: num(p?.['topK']) ?? 0,
        at,
      }
    }
    case 'rag.hits': {
      const p = parseJson(raw)
      return {
        type: 'rag.hits',
        hits: hits(p?.['hits']),
        injected: bool(p?.['injected']) ?? false,
        at,
      }
    }
    case 'skill': {
      const p = parseJson(raw)
      const skillId = str(p?.['skillId'])
      if (skillId === undefined) return null
      const phaseRaw = str(p?.['phase'])
      const phase =
        phaseRaw === 'load' || phaseRaw === 'run' || phaseRaw === 'result' || phaseRaw === 'error'
          ? phaseRaw
          : 'run'
      return {
        type: 'skill',
        skillId,
        skillName: str(p?.['skillName']) ?? str(p?.['name']) ?? skillId,
        phase,
        status: toStatus(p?.['status']),
        message: str(p?.['message']),
        result: str(p?.['result']),
        error: str(p?.['error']),
        at,
      }
    }
    case 'tool': {
      const p = parseJson(raw)
      const toolCallId = str(p?.['toolCallId'])
      if (toolCallId === undefined) return null
      const phaseRaw = str(p?.['phase'])
      const phase =
        phaseRaw === 'call' || phaseRaw === 'run' || phaseRaw === 'result' || phaseRaw === 'error'
          ? phaseRaw
          : 'run'
      return {
        type: 'tool',
        toolCallId,
        toolName: str(p?.['toolName']) ?? str(p?.['name']) ?? toolCallId,
        phase,
        status: toStatus(p?.['status']),
        args: str(p?.['args']),
        result: str(p?.['result']),
        error: str(p?.['error']),
        at,
      }
    }
    case 'handoff': {
      const p = parseJson(raw)
      return {
        type: 'handoff',
        from: str(p?.['from']) ?? 'tool',
        note: str(p?.['note']) ?? '结果已回传 Agent',
        at,
      }
    }
    case 'thinking':
      return raw === '' ? null : { type: 'thinking', text: raw, at }
    case 'delta':
      return raw === '' ? null : { type: 'delta', text: raw, at }
    case 'answer': {
      const p = parseJson(raw)
      return { type: 'answer', text: str(p?.['text']) ?? '', at }
    }
    case 'usage': {
      const p = parseJson(raw)
      return {
        type: 'usage',
        inputTokens: num(p?.['inputTokens']) ?? 0,
        outputTokens: num(p?.['outputTokens']) ?? 0,
        at,
      }
    }
    case 'error': {
      const p = parseJson(raw)
      const kind = str(p?.['kind']) ?? ''
      return {
        type: 'error',
        code: kind || 'PROVIDER_ERROR',
        message: str(p?.['message']) ?? '',
        at,
      }
    }
    case 'context.checkpoint': {
      // S3-M13：只做形状校验与不透明暂存。⛔ 不解析 state、不验 HMAC；由 hook 逐字上送。
      const p = parseJson(raw)
      const state = str(p?.['state'])
      const policyVersion = str(p?.['policyVersion'])
      const digest = str(p?.['digest'])
      const tier = num(p?.['tier'])
      const fallback = bool(p?.['fallback'])
      if (
        state === undefined || state === '' ||
        policyVersion === undefined || policyVersion === '' ||
        digest === undefined || digest === '' ||
        tier === undefined || !Number.isInteger(tier) || tier < 1 || tier > 3 ||
        fallback === undefined
      ) return null
      const rawUpToTurn = p?.['upToTurn']
      const upToTurn = rawUpToTurn === undefined || rawUpToTurn === null
        ? undefined
        : num(rawUpToTurn)
      if (upToTurn !== undefined && (!Number.isInteger(upToTurn) || upToTurn < 0)) return null
      return {
        type: 'context.checkpoint',
        state,
        ...(upToTurn === undefined ? {} : { upToTurn }),
        policyVersion,
        tier,
        fallback,
        digest,
        at,
      }
    }
    case 'done': {
      // done 允许空 data；truncated 由流结束时的收口逻辑补充（见 consumeAgentStream）。
      // v1.1 只追加可选字段；旧 `{}` 帧必须仍产出无该字段的事件。
      const p = raw === '' ? undefined : parseJson(raw)
      const version = p?.['contextStateVersion']
      return {
        type: 'done',
        truncated: false,
        ...(typeof version === 'string' && version !== '' ? { contextStateVersion: version } : {}),
        at,
      }
    }
    default:
      return null
  }
}

/**
 * 读取 SSE 响应流 → 解码 → 按到达顺序推事件。
 *
 * ⚠️ 两条纪律（与 generalChatApi/aiChatApi 同源）：
 * <ol>
 *   <li><b>done 是应用层终帧</b>：收到即收口并取消 reader，不等 TCP EOF；</li>
 *   <li><b>断流 ≠ 失败</b>：未收到服务端 done 而流结束时，补一条 {@code truncated:true} 的 done，
 *       保留已到达正文，既不伪装完整也不伪装成系统故障。</li>
 * </ol>
 * error 帧直接抛 {@link AgentChatFailure}（整轮失败，与单个 tool/skill 的 status:error 不同）。
 */
export async function consumeAgentStream(
  body: ReadableStream<Uint8Array>,
  ctx: AgentChatSendContext,
): Promise<void> {
  const { signal, onEvent } = ctx
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let sawDone = false

  const handleFrame = (frame: AgentSseFrame): boolean => {
    const event = decodeAgentFrame(frame)
    if (event === null) return false
    if (event.type === 'error') {
      throw new AgentChatFailure(errorFromFrame(event.code, event.message))
    }
    onEvent(event)
    if (event.type === 'done') {
      sawDone = true
      return true
    }
    return false
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (signal.aborted) throw new AgentChatCancelledError()
      buffer += decoder.decode(value, { stream: true })
      let matched = nextBlock(buffer)
      while (matched !== null) {
        buffer = matched.rest
        const frame = parseBlock(matched.block)
        if (frame !== null && handleFrame(frame)) break
        matched = sawDone ? null : nextBlock(buffer)
      }
      if (sawDone) break
    }
    if (!sawDone) {
      // 冲刷多字节字符的残余字节（TextDecoder 可能还在等后续 chunk）。
      buffer += decoder.decode()
      const tail = parseBlock(buffer)
      // ⚠️ 半帧绝不能当完整帧：bytes 可能恰好停在 "event: done\n" 而 data 还没到，
      //    若此时把 tail 交给 decodeAgentFrame，就会把断流误判成「服务端正常收口」。
      //    本协议所有帧都带 data（done 也发 `data: {}`），故缺 data 一律视为截断。
      if (tail !== null && tail.data !== null) handleFrame(tail)
    }
  } finally {
    // 收到终帧后主动断开，避免后端/反代继续挂着已完成的连接。
    reader.cancel().catch(() => {})
  }
  if (!sawDone) onEvent({ type: 'done', truncated: true, at: Date.now() })
}

/* ── 样例回放帧 ───────────────────────────────────────────── */

interface SampleFrame {
  event: string
  data: string
}

function frame(event: string, data: string | Record<string, unknown>): SampleFrame {
  return { event, data: typeof data === 'string' ? data : JSON.stringify(data) }
}

const SAMPLE_HITS: AgentHit[] = [
  {
    chunkId: 'sample-hit-1',
    text: '判别式 Δ = b² − 4ac。Δ < 0 时方程无实数根；Δ = 0 时有两个相等实根；Δ > 0 时有两个不等实根。',
    score: 0.91,
    locator: 'P42 例 3',
    edition: '数学·八年级·下册·人教版',
    role: 'EXPLANATION',
    reason: 'SEMANTIC_MATCH',
  },
  {
    chunkId: 'sample-hit-2',
    text: '配方法推导：ax² + bx + c = 0 两边除以 a 并配方，得到 (x + b/2a)² = (b² − 4ac)/4a²，右边为负即无实数根。',
    score: 0.83,
    locator: 'P44 推导',
    edition: '数学·八年级·下册·人教版',
    role: 'EXPLANATION',
    reason: 'DERIVATION_MATCH',
  },
]

const SAMPLE_ANSWER =
  '判别式 Δ = b² − 4ac 决定一元二次方程 ax² + bx + c = 0 的根的情况：\n\n' +
  '1. **Δ > 0**：两个不相等的实数根；\n' +
  '2. **Δ = 0**：两个相等的实数根（重根）；\n' +
  '3. **Δ < 0**：没有实数根，但有一对共轭复根。\n\n' +
  '当题目问「判别式小于零是什么意思」时，答案是：**这个方程在实数范围内无解**。\n\n' +
  '小提示：以上依据来自教材片段，回答由本地样例脚本给出，并非真实模型输出。'

/**
 * 构造样例事件序列。<b>这仍是「协议帧」</b>：样例回放会把它们序列化成 SSE 文本，
 * 再走与 live 完全相同的 {@link consumeAgentStream} + 状态机，因此解析器/状态机/UI 全被真实验证。
 *
 * <p>⚠️ 反证守卫：若删掉任一组件帧（route/rag/skill/tool/handoff/delta/answer/done），
 * 测试 `sample replay emits every component` 会立刻转红。
 */
export function buildSampleFrames(
  question: string,
  scenario: SampleScenario,
  thinkingMode: boolean,
  includeReasoning: boolean,
): SampleFrame[] {
  const frames: SampleFrame[] = [
    frame('run.start', { runId: 'sample-run-0001' }),
    frame('route', {
      agentId: 'sample-agent-math',
      agentName: '数学错题归因 Agent',
      reason: '识别为初中数学·代数概念题，匹配「错题归因」路由策略',
      candidates: [
        { agentId: 'sample-agent-math', name: '数学错题归因 Agent', score: 0.93 },
        { agentId: 'sample-agent-general', name: '通用问答 Agent', score: 0.41 },
      ],
    }),
  ]

  if (scenario === 'rag-empty') {
    frames.push(frame('rag.query', { query: question, topK: 5 }))
    frames.push(frame('rag.hits', { hits: [], injected: false }))
  } else {
    frames.push(frame('rag.query', { query: question, topK: 5 }))
    frames.push(frame('rag.hits', { hits: SAMPLE_HITS, injected: true }))
  }

  if (scenario === 'skill-error') {
    frames.push(
      frame('skill', {
        skillId: 'sample-skill-algebra',
        skillName: '代数讲解 Skill',
        phase: 'load',
        status: 'running',
        message: '加载 Skill 定义与闭包依赖',
      }),
    )
    frames.push(
      frame('skill', {
        skillId: 'sample-skill-algebra',
        skillName: '代数讲解 Skill',
        phase: 'error',
        status: 'error',
        error: 'SKILL_DEPENDENCY_MISSING：声明的 tool:explain_step 没有已发布版本',
      }),
    )
  } else {
    frames.push(
      frame('skill', {
        skillId: 'sample-skill-algebra',
        skillName: '代数讲解 Skill',
        phase: 'load',
        status: 'running',
        message: '加载 Skill 定义与闭包依赖',
      }),
    )
    frames.push(
      frame('skill', {
        skillId: 'sample-skill-algebra',
        skillName: '代数讲解 Skill',
        phase: 'result',
        status: 'success',
        result: '已装配 3 条讲解规则 + 1 个工具引用',
      }),
    )
  }

  if (scenario === 'tool-error') {
    frames.push(
      frame('tool', {
        toolCallId: 'sample-tc-1',
        toolName: 'knowledge_lookup',
        phase: 'call',
        status: 'running',
        args: JSON.stringify({ query: '判别式 小于零', topK: 3 }),
      }),
    )
    frames.push(
      frame('tool', {
        toolCallId: 'sample-tc-1',
        toolName: 'knowledge_lookup',
        phase: 'error',
        status: 'error',
        error: 'TOOL_TIMEOUT：知识检索工具在 5s 内未返回（已降级为无工具回答）',
      }),
    )
    frames.push(
      frame('handoff', {
        from: 'sample-tc-1',
        note: '工具失败结果已回传 Agent，改为仅依据已注入的 RAG 片段继续生成',
      }),
    )
  } else {
    frames.push(
      frame('tool', {
        toolCallId: 'sample-tc-1',
        toolName: 'knowledge_lookup',
        phase: 'call',
        status: 'running',
        args: JSON.stringify({ query: '判别式 小于零', topK: 3 }),
      }),
    )
    frames.push(
      frame('tool', {
        toolCallId: 'sample-tc-1',
        toolName: 'knowledge_lookup',
        phase: 'result',
        status: 'success',
        result: JSON.stringify({ hits: 2, summary: '命中 2 个教材片段，已作为上下文注入' }),
      }),
    )
    frames.push(
      frame('handoff', {
        from: 'sample-tc-1',
        note: '工具结果已回传 Agent，继续生成最终回答',
      }),
    )
  }

  // ⛔ 推理草稿只在平台通道下发：租户前台调用时 includeReasoning=false，脚本不产生 thinking 帧。
  if (thinkingMode && includeReasoning) {
    frames.push(frame('thinking', '用户问的是判别式小于零的含义，属于概念解释。'))
    frames.push(frame('thinking', '已有两条教材依据；工具也返回了命中，直接给结论 + 分情况讨论即可。'))
  }

  for (const chunk of SAMPLE_ANSWER.match(/[\s\S]{1,24}/g) ?? [SAMPLE_ANSWER]) {
    frames.push(frame('delta', chunk))
  }
  frames.push(frame('answer', { text: SAMPLE_ANSWER }))
  frames.push(frame('usage', { inputTokens: 186, outputTokens: 142 }))
  frames.push(frame('done', {}))
  return frames
}

/** 把样例帧序列化为 SSE 文本流，逐帧带间隔下推；abort 立即停止。 */
function framesToStream(
  frames: SampleFrame[],
  delayMs: number,
  signal: AbortSignal,
): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  let cancelled = false
  let index = 0
  return new ReadableStream<Uint8Array>({
    start(controller) {
      const pump = async (): Promise<void> => {
        while (index < frames.length) {
          if (cancelled || signal.aborted) {
            try {
              controller.close()
            } catch {
              /* 已关闭 */
            }
            return
          }
          if (delayMs > 0) {
            await new Promise<void>((resolve) => setTimeout(resolve, delayMs))
          }
          if (cancelled || signal.aborted) {
            try {
              controller.close()
            } catch {
              /* 已关闭 */
            }
            return
          }
          const current = frames[index]
          index += 1
          if (current === undefined) break
          const text = `event: ${current.event}\ndata: ${current.data}\n\n`
          controller.enqueue(encoder.encode(text))
        }
        try {
          controller.close()
        } catch {
          /* 已关闭 */
        }
      }
      void pump()
    },
    cancel() {
      cancelled = true
    },
  })
}

/* ── 传输实现 ─────────────────────────────────────────────── */

/** 真实端点传输（平台身份域）。端点未部署时如实报错，不退化成假流。 */
export const liveAgentChatTransport: AgentChatTransport = {
  id: 'AGENT_LIVE_SSE',
  label: '真实端点',
  simulated: false,
  async send(params: AgentChatRequest, ctx: AgentChatSendContext): Promise<void> {
    const { signal, onEvent } = ctx
    const endpoint = resolveAgentEndpoint(params.endpoint)
    /**
     * 是否已有正文/终答到达。决定「流中断」的呈现：
     * · 已到达 → 保留正文，只标 truncated（断流 ≠ 系统故障）；
     * · 未到达 → 如实抛失败，不让空白气泡假装成功。
     * 组件过程帧（route/rag/tool/skill/thinking）不算正文。
     */
    let sawContent = false
    const relayEvent = (event: AgentStreamEvent): void => {
      if (
        (event.type === 'delta' || event.type === 'answer') &&
        event.text !== ''
      ) {
        sawContent = true
      }
      onEvent(event)
    }
    const interrupt = (fallback: AgentChatError): void => {
      if (sawContent) {
        onEvent({ type: 'done', truncated: true, at: Date.now() })
        return
      }
      throw new AgentChatFailure(fallback)
    }
    const controller = new AbortController()
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, AGENT_LIVE_TIMEOUT_MS)
    const relayAbort = (): void => controller.abort()
    signal.addEventListener('abort', relayAbort, { once: true })
    if (signal.aborted) controller.abort()

    const timeoutFailure = (): AgentChatFailure =>
      new AgentChatFailure({
        code: 'REQUEST_TIMEOUT',
        message: '等待编排回答超时',
        hint: '稍后重试，或缩小 topK / 简化问题；持续超时请查看模型网关日志。',
        retryable: true,
        status: AGENT_TIMEOUT_STATUS,
      })

    try {
      const headers = new Headers()
      const session = loadSession()
      if (session !== null) headers.set('Authorization', `Bearer ${session.accessToken}`)
      headers.set('Content-Type', 'application/json')
      headers.set('Accept', 'text/event-stream')
      headers.set('X-Accel-Buffering', 'no')
      if (params.tenantId) headers.set('X-Tenant-Id', params.tenantId)

      let res: Response
      try {
        res = await fetch(endpoint, {
          method: 'POST',
          headers,
          body: JSON.stringify({
            message: params.message,
            history: params.history,
            agentId: params.agentId,
            topK: params.topK,
            thinkingMode: params.thinkingMode,
            tenantId: params.tenantId,
            contextState: params.contextState,
          }),
          signal: controller.signal,
        })
      } catch (cause) {
        if (signal.aborted) throw new AgentChatCancelledError()
        if (timedOut) throw timeoutFailure()
        throw new AgentChatFailure({
          code: 'NETWORK_ERROR',
          message: `网络错误：${cause instanceof Error ? cause.message : '请求未能发出'}`,
          hint: '确认平台服务可访问、开发代理 /api 规则生效。',
          retryable: true,
          status: 0,
        })
      }

      if (!res.ok) {
        const text = await res.text().catch(() => '')
        if (signal.aborted) throw new AgentChatCancelledError()
        if (res.status === 401) {
          const session2 = loadSession()
          if (session2 !== null && isExpired(session2)) {
            clearSession()
            redirectLogin()
            // 会话确实过期：与「身份域错配」区分开，别把可执行的排查指引写错。
            throw new AgentChatFailure({
              code: 'UNAUTHENTICATED',
              message: '登录已过期，请重新登录',
              hint: '重新登录后重试；本页会话只存 sessionStorage，其余对话草稿仍在内存中。',
              status: res.status,
              retryable: false,
            })
          }
        }
        throw new AgentChatFailure(errorFromHttp(res.status, parseJson(text), endpoint))
      }

      const body = res.body
      if (body === null) {
        interrupt({
          code: 'EMPTY_STREAM',
          message: '编排端点返回了空响应流',
          hint: '后端需以 text/event-stream 推送 run/step/done 帧；仅返回 200 空体不算成功。',
          retryable: true,
          status: res.status,
        })
        return
      }
      try {
        await consumeAgentStream(body, { signal, onEvent: relayEvent })
      } catch (cause) {
        if (cause instanceof AgentChatFailure) throw cause
        if (cause instanceof AgentChatCancelledError) throw cause
        if (signal.aborted) throw new AgentChatCancelledError()
        if (timedOut) {
          interrupt(timeoutFailure().info)
          return
        }
        // 传输层异常中断：有正文就保留并标截断；一字未到就报失败。
        interrupt({
          code: 'STREAM_INTERRUPTED',
          message: '编排流在返回正文前中断',
          hint: '稍后重试；持续失败请查看反向代理的 chunked 转发与后端 SseEmitter 超时配置。',
          retryable: true,
        })
      }
    } finally {
      clearTimeout(timer)
      signal.removeEventListener('abort', relayAbort)
    }
  },
}

/**
 * 样例回放传输（<b>非真实模型输出</b>）。刻意走与 live 完全相同的 SSE 解析路径：
 * 帧先序列化为 SSE 文本，再经 consumeAgentStream → decodeAgentFrame → 状态机。
 * 因此 thinking 模式展示的每个组件步骤都是「真实解析出来的」，而不是 UI 里写死的假状态。
 */
export function createSampleAgentTransport(options: {
  frameDelayMs?: number
  includeReasoning?: boolean
} = {}): AgentChatTransport {
  const delay = options.frameDelayMs ?? SAMPLE_FRAME_DELAY_MS
  const includeReasoning = options.includeReasoning ?? true
  return {
    id: AGENT_SAMPLE_TRANSPORT_ID,
    label: '样例回放',
    simulated: true,
    async send(params: AgentChatRequest, ctx: AgentChatSendContext): Promise<void> {
      const frames = buildSampleFrames(
        params.message,
        params.sampleScenario ?? 'full',
        params.thinkingMode,
        includeReasoning,
      )
      await consumeAgentStream(framesToStream(frames, delay, ctx.signal), ctx)
    },
  }
}

/** 默认样例回放（平台通道允许展示推理草稿）。 */
export const sampleAgentChatTransport = createSampleAgentTransport()

/** 按来源选择传输实现（页面唯一入口）。 */
export function selectAgentChatTransport(source: AgentChatSource): AgentChatTransport {
  return source === 'sample' ? sampleAgentChatTransport : liveAgentChatTransport
}

/** 页面调用入口。 */
export function sendAgentChat(
  params: AgentChatRequest,
  ctx: AgentChatSendContext,
): Promise<void> {
  return selectAgentChatTransport(params.source).send(params, ctx)
}
