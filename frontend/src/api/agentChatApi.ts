/**
 * 租户前台「通用 Agent 编排对话」的 SSE 传输层。
 *
 * <p><b>协议与 admin-console/src/api/agentChatApi.ts 完全一致</b>：
 * run.start → route → rag.query/rag.hits → skill → tool → handoff → 思考/正文 → usage → done。
 * 该文件改动时须同步两工程（frontend/tests/agentChat.architecture.test.ts 会逐事件名比对）。
 *
 * <p><b>端点与身份域</b>：{@code POST /api/knowledge/agent/chat/stream} 留给<b>租户安全链</b>
 * （{@code aud=agent-platform}），与通用问答 {@code /api/knowledge/chat/stream} 同域不同路径。
 * ⛔ 绝不复用平台控制台的 {@code /api/chat/stream} 或平台编排端点 {@code /api/agent/chat/stream}；
 *    租户令牌打过去必然 401（2026-11-07 身份域事故同源）。
 *
 * <p><b>⚠️ 接入现状（诚实边界）</b>：后端已在租户链实现一次请求内完成
 * 路由 → RAG → Skill → Tool → 回传 → 流式回答 + Run 审计的编排 SSE 端点，并已真实联调。
 * 当前运行的服务若未部署该端点，live 模式收到 404/501 会明示「能力未接入」，
 * <b>绝不</b>退化成假流；想立即预览完整链路可显式切到样例回放
 * （页面醒目标注「非真实模型输出」）。
 *
 * <p><b>推理草稿不下发</b>：租户通道按设计不展示原始 thinking 文本。
 * 即使旧字节码误推 {@code event: thinking}，这里也显式忽略；
 * 「thinking 模式」在租户前台的含义是<b>展示组件执行过程</b>，不是展示模型推理草稿。
 *
 * <p><b>线格式与终帧</b>：thinking/delta 的 data 为裸文本，其余为 JSON。
 * done 是应用层终帧，收到即收口，不等 TCP EOF；未收到终帧而流结束则标 truncated，
 * 保留已到达正文，既不伪装完整也不伪装成系统故障。
 */
import { notifyUnauthorized } from '@/api/client'
import { normalizeHttpError, type NormalizedError } from '@/api/errors'
import { clearSession, loadSession } from '@/auth/tokenStorage'
import { IncrementalSseParser, type ParsedFrame } from '@/features/run/sse'

/** 租户专用编排端点（守卫测试锁死：必须在 /api/knowledge/** 下，且不得复用纯 LLM 路径）。 */
export const AGENT_CHAT_PATH = '/api/knowledge/agent/chat/stream'

/** 整条 SSE 长连接的兜底超时。 */
export const AGENT_CHAT_TIMEOUT_MS = 150_000
/** 单条超时独立状态码，避免与网络失败(0)/HTTP 408 混淆。 */
export const AGENT_TIMEOUT_STATUS = -1

/** 样例回放标识（页面据此显示「非真实模型输出」告警）。 */
export const AGENT_SAMPLE_TRANSPORT_ID = 'AGENT_SAMPLE_REPLAY'
export const SAMPLE_FRAME_DELAY_MS = 40

/* ── 通用协议类型（与 admin-console 同构） ─────────────────── */

export type AgentChatSource = 'live' | 'sample'
export type SampleScenario = 'full' | 'skill-error' | 'tool-error' | 'rag-empty'

export interface AgentChatTurn {
  role: 'user' | 'assistant'
  content: string
}

export interface AgentCandidate {
  agentId: string
  name: string
  score?: number
  reason?: string
}

export interface AgentHit {
  chunkId: string
  text: string
  score: number
  locator: string
  edition: string
  role: string
  reason: string
}

export type AgentStepStatus = 'pending' | 'running' | 'success' | 'error' | 'info'

/**
 * 协议 v1.1 第 14 事件的载荷（**只解析、只内存暂存**不透明 state）。
 * ⚠️ state 是不透明基串，前端只做形状校验，绝不解析/解密/渲染；请求侧 contextState 属 M8。
 */
export interface AgentContextCheckpoint {
  state: string
  upToTurn?: number
  policyVersion: string
  tier: number
  fallback: boolean
  digest: string
}

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
  // ⚠️ P1-4：estimated 表示 token 数是**本地估算**（供应商流式不返回 usage）。
  | { type: 'usage'; inputTokens: number; outputTokens: number; estimated?: boolean; at: number }
  | { type: 'error'; code: string; message: string; at: number }
  /** 协议 v1.1 第 14 事件：只解析、只内存暂存不透明 state，S3-M13 起由 hook 逐字上送。 */
  | ({ type: 'context.checkpoint' } & AgentContextCheckpoint & { at: number })
  | { type: 'done'; truncated: boolean; contextStateVersion?: string; at: number }

export interface AgentChatRequest {
  message: string
  history: AgentChatTurn[]
  source: AgentChatSource
  /** 租户前台：只表示「展示组件执行过程」，不代表下发推理草稿。 */
  thinkingMode: boolean
  agentId?: string
  topK?: number
  /** 仅 sample 模式消费；live 忽略。 */
  sampleScenario?: SampleScenario
  /** live 端点覆盖（测试/私有部署用）；缺省为 AGENT_CHAT_PATH。 */
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

export interface AgentChatTransport {
  readonly id: string
  readonly label: string
  readonly simulated: boolean
  send(params: AgentChatRequest, ctx: AgentChatSendContext): Promise<void>
}

export interface AgentChatError {
  code: string
  message: string
  hint: string
  retryable: boolean
  status?: number
}

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

/* ── 错误归一化（复用租户端唯一实现） ───────────────────────── */

function errorFromHttp(normalized: NormalizedError, path: string): AgentChatError {
  switch (normalized.code) {
    case 'UNAUTHENTICATED':
      return {
        code: normalized.code,
        message: '登录已过期，请重新登录',
        hint: '重新登录后重试；若刚登录就出现，请核对编排端点是否仍留在租户链上。',
        retryable: false,
        status: normalized.status,
      }
    case 'ACCESS_DENIED':
      return {
        code: normalized.code,
        message: '无权使用 Agent 编排对话',
        hint: '当前租户账号没有该能力授权，请联系租户管理员；本页不会因此登出。',
        retryable: false,
        status: normalized.status,
      }
    case 'INVALID_REQUEST':
      return {
        code: normalized.code,
        message: '请求被拒绝（400）',
        hint: '检查 AgentId / topK / 历史消息是否合法，或换一种问法。',
        retryable: false,
        status: normalized.status,
      }
  }
  switch (normalized.status) {
    case 404:
    case 501:
      return {
        code: 'CAPABILITY_NOT_INSTALLED',
        message: `当前环境未部署编排对话端点（POST ${path}）`,
        hint: '确认后端版本已包含租户链编排 SSE 端点（run/route/rag/skill/tool/handoff/delta/done）；若为旧环境请升级后重试。也可切到「样例回放」预览完整链路（非真实输出）。',
        retryable: false,
        status: normalized.status,
      }
    case 429:
      return {
        code: normalized.code,
        message: '编排对话连接过于频繁（429）',
        hint: '稍后重试；本页不做自动重试，避免继续消耗配额。',
        retryable: true,
        status: normalized.status,
      }
    case 0:
      return {
        code: normalized.code,
        message: normalized.code === 'REQUEST_TIMEOUT' ? '等待编排回答超时' : '网络不可达',
        hint:
          normalized.code === 'REQUEST_TIMEOUT'
            ? '稍后重试，或缩小 topK / 简化问题。'
            : '确认平台服务可访问、开发代理 /api 规则生效。',
        retryable: true,
        status: normalized.status,
      }
    default:
      if (normalized.status >= 500) {
        return {
          code: normalized.code,
          message: '编排服务暂时不可用',
          hint: '稍后重试；持续失败请查看平台服务健康探针与模型网关日志。',
          retryable: true,
          status: normalized.status,
        }
      }
      return {
        code: normalized.code,
        message: normalized.message,
        hint: '可重试一次；若仍失败请把错误码交给后端排查。',
        retryable: true,
        status: normalized.status,
      }
  }
}

function errorFromFrame(kind: string, message: string): AgentChatError {
  switch (kind) {
    case 'UNAUTHORIZED':
      return {
        code: kind,
        message: '登录已过期，或模型凭据未授权',
        hint: '重新登录；若仍失败请核对模型凭据是否已装配。',
        retryable: false,
      }
    case 'CONFIG_UNAVAILABLE':
      return {
        code: kind,
        message: 'Agent 配置不可用',
        hint: '所选 Agent 可能没有已发布版本，或缺少 instructions/modelRef；请先在配置页发布新版本。',
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

export function describeAgentChatError(cause: unknown): AgentChatError {
  if (cause instanceof AgentChatFailure) return cause.info
  return {
    code: 'UNKNOWN',
    message: cause instanceof Error ? cause.message : '未知错误',
    hint: '可重试一次；若仍失败请查看浏览器控制台与后端日志。',
    retryable: true,
  }
}

/* ── 帧解码（与 admin-console 同一协议） ─────────────────────── */

/** 一帧 SSE（与增量解析器 ParsedFrame 的 event/data 同构）。 */
export interface AgentSseFrame {
  event: string | null
  data: string | null
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

/** 单帧 → 事件。未知事件返回 null（fail-closed），绝不据此伪造阶段。 */
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
        // ⚠️ 旧后端不传该字段 → 视为「非估算」（保持既有展示）。
        estimated: p?.['estimated'] === true,
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
 * 读取 SSE 响应流 → 增量解析 → 按到达顺序推事件。
 *
 * ⚠️ 复用现有 {@link IncrementalSseParser}（不另造第二份分帧器），但额外加一道防线：
 *  {@code flush()} 处理的是<b>可能不完整</b>的尾块，若帧只有 event 没有 data
 *  （例如字节恰好停在 "event: done\n"），绝不能把它当完整终帧，否则断流会被误判为成功。
 */
export async function consumeAgentStream(
  body: ReadableStream<Uint8Array>,
  ctx: AgentChatSendContext,
): Promise<void> {
  const { signal, onEvent } = ctx
  const reader = body.getReader()
  const parser = new IncrementalSseParser()
  const decoder = new TextDecoder()
  let sawDone = false
  let sawTerminal = false

  const handleFrame = (frame: ParsedFrame, tail = false): boolean => {
    if (frame.terminal) {
      // Run 事件的 `: run-terminal` 注释：本协议的终帧仍是 done；
      // 只收到终止注释而无 done 时，按截断收口。
      sawTerminal = true
      return true
    }
    // ⚠️ 半帧防线：tail 块缺 data 时不解码（见函数注释）。
    if (tail && frame.data === null) return false
    const event = decodeAgentFrame(frame)
    if (event === null) return false
    if (event.type === 'thinking') {
      // ⛔ 推理草稿仅平台管理员可见；租户通道一律丢弃（含旧字节码误推）。
      return false
    }
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
      const chunk = await reader.read()
      if (chunk.done) break
      if (signal.aborted) throw new AgentChatCancelledError()
      for (const frame of parser.push(decoder.decode(chunk.value, { stream: true }))) {
        if (handleFrame(frame)) break
      }
      if (sawDone || sawTerminal) break
    }
    if (!sawDone && !sawTerminal) {
      for (const frame of parser.flush()) {
        if (handleFrame(frame, true)) break
      }
    }
  } finally {
    // 收到终帧后主动断开，避免后端/反代继续挂着已完成的连接。
    reader.cancel().catch(() => {})
  }
  if (!sawDone) onEvent({ type: 'done', truncated: true, at: Date.now() })
}

/* ── 样例回放帧（与 admin-console 同协议，推理草稿恒定不下发） ── */

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
 * 构造样例事件序列。仍走「帧 → SSE 文本 → 同一解析器/状态机/UI」的真实代码路径。
 * ⚠️ includeReasoning 保留在签名里以与平台端协议同构，但租户传输<b>恒传 false</b>，
 *    页面也没有展示推理草稿的组件。
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
    frame('rag.query', { query: question, topK: 5 }),
    frame('rag.hits', {
      hits: scenario === 'rag-empty' ? [] : SAMPLE_HITS,
      injected: scenario !== 'rag-empty',
    }),
  ]

  if (scenario === 'skill-error') {
    frames.push(
      frame('skill', {
        skillId: 'sample-skill-algebra',
        skillName: '代数讲解 Skill',
        phase: 'load',
        status: 'running',
        message: '加载 Skill 定义与闭包依赖',
      }),
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
      frame('tool', {
        toolCallId: 'sample-tc-1',
        toolName: 'knowledge_lookup',
        phase: 'error',
        status: 'error',
        error: 'TOOL_TIMEOUT：知识检索工具在 5s 内未返回（已降级为无工具回答）',
      }),
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
      frame('tool', {
        toolCallId: 'sample-tc-1',
        toolName: 'knowledge_lookup',
        phase: 'result',
        status: 'success',
        result: JSON.stringify({ hits: 2, summary: '命中 2 个教材片段，已作为上下文注入' }),
      }),
      frame('handoff', {
        from: 'sample-tc-1',
        note: '工具结果已回传 Agent，继续生成最终回答',
      }),
    )
  }

  // ⛔ 推理草稿不得进入租户通道：参数保留是为了与平台端协议同构，
  //    但这里刻意不产出任何 thinking 帧（即使调用方误传 includeReasoning=true）。
  void thinkingMode
  void includeReasoning

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
          controller.enqueue(encoder.encode(`event: ${current.event}\ndata: ${current.data}\n\n`))
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

/** 真实端点（租户身份域）。端点未部署时如实报错，不退化成假流。 */
export const liveAgentChatTransport: AgentChatTransport = {
  id: 'AGENT_LIVE_SSE',
  label: '真实端点',
  simulated: false,
  async send(params, ctx): Promise<void> {
    const { signal, onEvent } = ctx
    const path = params.endpoint ?? AGENT_CHAT_PATH
    /**
     * 是否已有正文到达：决定「流中断」的呈现。
     * 有正文 → 保留并标 truncated；一字未到 → 如实失败，不让空白气泡假装成功。
     */
    let sawContent = false
    const relayEvent = (event: AgentStreamEvent): void => {
      if ((event.type === 'delta' || event.type === 'answer') && event.text !== '') {
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
    }, AGENT_CHAT_TIMEOUT_MS)
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

      let res: Response
      try {
        res = await fetch(path, {
          method: 'POST',
          headers,
          body: JSON.stringify({
            message: params.message,
            history: params.history,
            agentId: params.agentId,
            topK: params.topK,
            thinkingMode: params.thinkingMode,
            contextState: params.contextState,
          }),
          signal: controller.signal,
        })
      } catch (cause) {
        if (signal.aborted) throw new AgentChatCancelledError()
        if (timedOut) throw timeoutFailure()
        throw new AgentChatFailure(errorFromHttp(normalizeHttpError(0, cause), path))
      }

      if (!res.ok) {
        const text = await res.text().catch(() => '')
        if (signal.aborted) throw new AgentChatCancelledError()
        let body: unknown = null
        try {
          body = text.length > 0 ? (JSON.parse(text) as unknown) : null
        } catch {
          // HTML 错误页 / 空体：归一化层会给出兜底文案
        }
        const normalized = normalizeHttpError(res.status, body)
        // 与 client.ts 同源：只有 UNAUTHENTICATED 才清会话跳登录；
        // ACCESS_DENIED / 身份域错配不清会话、不丢掉内存里的对话草稿。
        if (normalized.code === 'UNAUTHENTICATED') {
          clearSession()
          notifyUnauthorized()
        }
        throw new AgentChatFailure(errorFromHttp(normalized, path))
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
 * 样例回放（<b>非真实模型输出</b>）。刻意走与 live 完全相同的 SSE 解析路径：
 * 帧先序列化为 SSE 文本，再经 consumeAgentStream → decodeAgentFrame → 状态机。
 * ⚠️ 租户前台<b>恒定</b> includeReasoning=false：既不产生 thinking 帧，也不展示推理草稿。
 */
export function createSampleAgentTransport(options: { frameDelayMs?: number } = {}): AgentChatTransport {
  const delay = options.frameDelayMs ?? SAMPLE_FRAME_DELAY_MS
  return {
    id: AGENT_SAMPLE_TRANSPORT_ID,
    label: '样例回放',
    simulated: true,
    async send(params, ctx): Promise<void> {
      const frames = buildSampleFrames(
        params.message,
        params.sampleScenario ?? 'full',
        params.thinkingMode,
        false, // ⛔ 租户通道永不下发原始推理草稿
      )
      await consumeAgentStream(framesToStream(frames, delay, ctx.signal), ctx)
    },
  }
}

export const sampleAgentChatTransport = createSampleAgentTransport()

export function selectAgentChatTransport(source: AgentChatSource): AgentChatTransport {
  return source === 'sample' ? sampleAgentChatTransport : liveAgentChatTransport
}

export function sendAgentChat(
  params: AgentChatRequest,
  ctx: AgentChatSendContext,
): Promise<void> {
  return selectAgentChatTransport(params.source).send(params, ctx)
}
