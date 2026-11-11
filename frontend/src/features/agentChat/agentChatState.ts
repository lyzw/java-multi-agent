/**
 * 租户前台 · 通用 Agent 编排对话的纯状态机（无 IO、可直接单测）。
 *
 * <p><b>与 admin-console/src/features/aiChat/agentChatState.ts 逐字段同构</b>，
 * 改动须同步两工程（frontend/tests/agentChat.architecture.test.ts 会比对关键分支）。
 *
 * <p><b>四条不变量（与 features/chat/chatState 同源，且新增了组件级状态）</b>：
 * <ol>
 *   <li><b>用户气泡永不因失败被删除</b>：失败只落在 assistant 位，用户能看清自己问了什么并原样重试。</li>
 *   <li><b>STOP ≠ FAILED ≠ TRUNCATED</b>：主动停止是正常操作；断流（没收到服务端 done）保留已到达正文
 *       并标 truncated；只有服务端 error 帧 / 传输失败才是 failed。混同会让用户误判系统状态。</li>
 *   <li><b>陈旧事件按 assistantId 丢弃</b>：停止/新会话/重试后在途请求仍可能推来事件，
 *       reducer 只对「仍是 streaming 且 id 匹配」的那条生效。</li>
 *   <li><b>组件状态只来自真实到达的帧</b>：route/rag/skill/tool/handoff/usage 各自 upsert，
 *       没有定时器合成进度、没有百分比。RAG 零命中是合法 info，<b>不是</b> error；
 *       单个 skill/tool 的 status:error 可被 Agent 降级后继续生成，<b>不等于</b>整轮失败。</li>
 * </ol>
 *
 * <p>会话历史只在内存：不写 localStorage / sessionStorage。
 */

import type {
  AgentCandidate,
  AgentChatError,
  AgentChatSource,
  AgentContextCheckpoint,
  AgentHit,
  AgentStepStatus,
  AgentStreamEvent,
} from '@/api/agentChatApi'

export type AgentMessageStatus = 'streaming' | 'done' | 'stopped' | 'failed'

/** 时间线条目类型：只有这里列出的组件才会出现在「执行过程」里。 */
export type AgentStepKind =
  | 'route'
  | 'rag'
  | 'skill'
  | 'tool'
  | 'handoff'
  | 'usage'
  | 'done'
  | 'error'
  | 'stop'

export interface AgentTraceStep {
  /** upsert 身份：同一 toolCallId / skillId 的多帧收敛成一条，不刷屏。 */
  key: string
  kind: AgentStepKind
  title: string
  status: AgentStepStatus
  /** 到达时刻（取事件携带的时间，不取 reducer 执行时刻，避免重放时乱序）。 */
  at: number
  detail?: string
  /** 新一轮尝试（skill=load / tool=call）允许覆盖上一条 error；其余情况 error 粘滞。 */
  restart?: boolean
}

export interface AgentRouteView {
  agentId: string
  agentName: string
  reason: string
  candidates: AgentCandidate[]
}

export interface AgentRagView {
  query?: string
  topK?: number
  hits: AgentHit[]
  injected: boolean
}

export interface AgentUsageView {
  inputTokens: number
  outputTokens: number
}

export interface AgentChatMessage {
  id: string
  role: 'user' | 'assistant'
  status: AgentMessageStatus
  content: string
  createdAt: number
  /** 组件执行过程，按 SSE 帧到达顺序排列。 */
  steps: AgentTraceStep[]
  /** ⛔ 租户通道永不下发原始推理草稿；此字段恒为空，保留仅为与平台端协议同构。 */
  thinking: string
  runId?: string
  route?: AgentRouteView
  rag?: AgentRagView
  usage?: AgentUsageView
  /** 服务端终帧缺失：答案可能不完整。既不是失败，也不是完整成功。 */
  truncated?: boolean
  error?: AgentChatError
  elapsedMs?: number
  /** 本条回答来自真实端点还是样例回放（页面必须能逐条区分）。 */
  source?: AgentChatSource
  /** 本条是否开启了 thinking 模式（租户前台＝展示组件执行过程，不是推理草稿）。 */
  thinkingMode?: boolean
}

export interface AgentChatState {
  messages: AgentChatMessage[]
  sending: boolean
  /**
   * M13 mock：协议 v1.1 的**已提交**压缩态。只在内存，刷新即丢；
   * ⛔ 当前不进入请求体，携带 `contextState` 属 M8 + G1 签核后的能力。
   */
  contextCheckpoint?: AgentContextCheckpoint
  /** 已提交压缩态对应的版本（来自 `done.contextStateVersion`，缺省为 undefined）。 */
  contextStateVersion?: string
  /** 本轮已到达但**尚未**收到非截断 done 的压缩态；截断 / 失败 / 停止一律不提交。 */
  pendingContextCheckpoint?: AgentContextCheckpoint
  pendingContextStateVersion?: string
}

/** M13 mock：初始 / 刷新即无压缩态；⛔ 不写 localStorage / sessionStorage。 */
export const initialAgentChatState: AgentChatState = { messages: [], sending: false }

export type AgentChatAction =
  | {
      type: 'SEND'
      userId: string
      assistantId: string
      text: string
      at: number
      source: AgentChatSource
      thinkingMode: boolean
    }
  | { type: 'STREAM'; id: string; event: AgentStreamEvent }
  | { type: 'FAIL'; id: string; error: AgentChatError; at: number }
  | { type: 'STOP'; at: number }
  | { type: 'RETRY'; id: string; at: number }
  | { type: 'NEW_SESSION' }

/** 只更新指定 assistant 位；找不到 / 已不在接收态就原样返回（防竞态）。 */
function patchStreaming(
  state: AgentChatState,
  id: string,
  patch: (m: AgentChatMessage) => AgentChatMessage,
): AgentChatState {
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

/**
 * 同 key upsert。⚠️ error 粘滞：同一 skill/tool 的失败不能被后续成功帧悄悄抹平，
 * 除非服务端明确发了新一轮的 load/call（restart）。失真往往就是「失败被吞、界面全绿」。
 */
function upsertStep(steps: AgentTraceStep[], step: AgentTraceStep): AgentTraceStep[] {
  const index = steps.findIndex((s) => s.key === step.key)
  if (index < 0) return [...steps, step]
  const prev = steps[index]
  if (prev === undefined) return [...steps, step]
  const stickyError = prev.status === 'error' && step.status !== 'error' && step.restart !== true
  const merged: AgentTraceStep = {
    ...prev,
    ...step,
    status: stickyError ? 'error' : step.status,
    title: stickyError ? prev.title : step.title,
    detail: stickyError ? prev.detail : (step.detail ?? prev.detail),
  }
  const next = steps.slice()
  next[index] = merged
  return next
}

function ragDetail(hits: AgentHit[], injected: boolean): string {
  const suffix = injected
    ? '已注入 Agent 上下文'
    : hits.length === 0
      ? '未注入上下文（零命中是合法结果）'
      : '未注入上下文（可能被内容闸门拦截）'
  return `召回 ${hits.length} 个片段 · ${suffix}`
}

function applyStreamEvent(m: AgentChatMessage, event: AgentStreamEvent): AgentChatMessage {
  switch (event.type) {
    case 'run.start':
      return { ...m, runId: event.runId === '' ? m.runId : event.runId }

    case 'route':
      return {
        ...m,
        route: {
          agentId: event.agentId,
          agentName: event.agentName,
          reason: event.reason,
          candidates: event.candidates,
        },
        steps: upsertStep(m.steps, {
          key: 'route',
          kind: 'route',
          title: `路由 · ${event.agentName || event.agentId || '未指定 Agent'}`,
          status: 'success',
          at: event.at,
          detail: event.reason || undefined,
        }),
      }

    case 'rag.query': {
      const rag: AgentRagView = {
        ...(m.rag ?? { hits: [], injected: false }),
        query: event.query,
        topK: event.topK,
      }
      return {
        ...m,
        rag,
        steps: upsertStep(m.steps, {
          key: 'rag',
          kind: 'rag',
          title: 'RAG · 检索中',
          status: 'running',
          at: event.at,
          detail: `查询「${event.query}」· topK=${event.topK}`,
        }),
      }
    }

    case 'rag.hits': {
      const rag: AgentRagView = {
        ...(m.rag ?? {}),
        hits: event.hits,
        injected: event.injected,
      }
      return {
        ...m,
        rag,
        steps: upsertStep(m.steps, {
          key: 'rag',
          kind: 'rag',
          title: event.injected ? 'RAG · 已注入上下文' : 'RAG · 未注入上下文',
          status: event.injected ? 'success' : 'info',
          at: event.at,
          detail: ragDetail(event.hits, event.injected),
        }),
      }
    }

    case 'skill': {
      const base = { key: `skill:${event.skillId}`, kind: 'skill' as const }
      const detail =
        event.phase === 'error'
          ? event.error
          : event.phase === 'result'
            ? event.result
            : event.message
      const title = `Skill · ${event.skillName}${
        event.phase === 'load'
          ? '（加载）'
          : event.phase === 'run'
            ? '（执行）'
            : event.phase === 'result'
              ? '（完成）'
              : '（失败）'
      }`
      return {
        ...m,
        steps: upsertStep(m.steps, {
          ...base,
          title,
          status: event.status,
          at: event.at,
          detail: detail === '' ? undefined : detail,
          restart: event.phase === 'load',
        }),
      }
    }

    case 'tool': {
      const detail =
        event.phase === 'error'
          ? event.error
          : event.phase === 'result'
            ? event.result
            : event.args
      const title = `Tool · ${event.toolName}${
        event.phase === 'call'
          ? '（调用）'
          : event.phase === 'run'
            ? '（执行中）'
            : event.phase === 'result'
              ? '（返回）'
              : '（失败）'
      }`
      return {
        ...m,
        steps: upsertStep(m.steps, {
          key: `tool:${event.toolCallId}`,
          kind: 'tool',
          title,
          status: event.status,
          at: event.at,
          detail: detail === '' ? undefined : detail,
          restart: event.phase === 'call',
        }),
      }
    }

    case 'handoff':
      return {
        ...m,
        steps: upsertStep(m.steps, {
          key: `handoff:${event.from}:${event.at}`,
          kind: 'handoff',
          title: '工具/Skill 结果回传 Agent',
          status: 'info',
          at: event.at,
          detail: event.note === '' ? undefined : `${event.from} → ${event.note}`,
        }),
      }

    case 'thinking':
      // 推理草稿与正文独立累积，互不干扰、互不回撤。
      return { ...m, thinking: m.thinking + event.text }

    case 'delta':
      // 真·逐 token 流：只追加，绝不回撤已到达的文本。
      return { ...m, content: m.content + event.text }

    case 'answer': {
      // 终帧是「兜底/补齐」，不是覆盖：
      // · 正文还空 → 用终帧；
      // · 终帧以已有正文为前缀 → 说明增量只是提前到达的前缀，补齐为完整终答；
      // · 两者不一致 → 保留已到达增量，绝不悄悄回撤用户已经看到的内容。
      if (event.text === '') return m
      if (m.content === '' || event.text.startsWith(m.content)) {
        return { ...m, content: event.text }
      }
      return m
    }

    case 'usage': {
      const usage: AgentUsageView = {
        inputTokens: event.inputTokens,
        outputTokens: event.outputTokens,
      }
      return {
        ...m,
        usage,
        steps: upsertStep(m.steps, {
          key: 'usage',
          kind: 'usage',
          title: '用量统计',
          status: 'info',
          at: event.at,
          detail: `输入 ${event.inputTokens} · 输出 ${event.outputTokens} tokens`,
        }),
      }
    }

    case 'error':
      // 正常由 transport 抛 AgentChatFailure 走 FAIL；这里兜住直接消费原始帧的调用方，
      // 避免服务端已宣告失败却把气泡留在 streaming。
      return {
        ...m,
        status: 'failed',
        error: {
          code: event.code,
          message: event.message || '编排服务宣告本轮失败',
          hint: '查看执行过程中最后一个失败组件；单个 Tool/Skill 失败通常会被 Agent 降级，不必整轮重试。',
          retryable: true,
        },
        steps: upsertStep(m.steps, {
          key: 'error',
          kind: 'error',
          title: '编排失败',
          status: 'error',
          at: event.at,
          detail: `${event.code}${event.message ? ` · ${event.message}` : ''}`,
        }),
        elapsedMs: Math.max(event.at - m.createdAt, 0),
      }

    case 'context.checkpoint':
      // 压缩态属于会话级状态，不在单条消息上渲染；由 reducer 在 STREAM 分支暂存。
      return m

    case 'done':
      return {
        ...m,
        status: 'done',
        truncated: event.truncated,
        elapsedMs: Math.max(event.at - m.createdAt, 0),
        steps: upsertStep(m.steps, {
          key: 'done',
          kind: 'done',
          title: event.truncated ? '流已中断（终帧缺失）' : '编排完成',
          status: event.truncated ? 'info' : 'success',
          at: event.at,
          detail: event.truncated
            ? '未收到服务端 done 帧，已到达的正文保留，但答案可能不完整。'
            : undefined,
        }),
      }
  }
}

export function agentChatReducer(state: AgentChatState, action: AgentChatAction): AgentChatState {
  switch (action.type) {
    case 'SEND': {
      if (state.sending) return state
      const user: AgentChatMessage = {
        id: action.userId,
        role: 'user',
        status: 'done',
        content: action.text,
        createdAt: action.at,
        steps: [],
        thinking: '',
      }
      const assistant: AgentChatMessage = {
        id: action.assistantId,
        role: 'assistant',
        status: 'streaming',
        content: '',
        createdAt: action.at,
        steps: [],
        thinking: '',
        source: action.source,
        thinkingMode: action.thinkingMode,
      }
      // ⚠️ 必须展开旧 state：M13 新增的 contextCheckpoint / contextStateVersion
      //    若只重建 messages + sending 会被静默清空（铁律 #10 同类失真）。
      return {
        ...state,
        messages: [...state.messages, user, assistant],
        sending: true,
        pendingContextCheckpoint: undefined,
        pendingContextStateVersion: undefined,
      }
    }

    case 'STREAM': {
      // M13 mock：context.checkpoint 是会话级压缩态，不写入单条消息；
      // 只有「非截断 done」才把本轮 pending 提交为 committed（截断 / 失败 / 停止一律不提交）。
      if (action.event.type === 'context.checkpoint') {
        const next = patchStreaming(state, action.id, (m) => m)
        if (next === state) return state
        const cp: AgentContextCheckpoint = {
          state: action.event.state,
          ...(action.event.upToTurn === undefined ? {} : { upToTurn: action.event.upToTurn }),
          policyVersion: action.event.policyVersion,
          tier: action.event.tier,
          fallback: action.event.fallback,
          digest: action.event.digest,
        }
        return { ...next, pendingContextCheckpoint: cp }
      }

      const next = patchStreaming(state, action.id, (m) => applyStreamEvent(m, action.event))
      if (next === state) return state
      const clear = {
        pendingContextCheckpoint: undefined,
        pendingContextStateVersion: undefined,
      }
      if (action.event.type === 'error') {
        return { ...next, ...clear, sending: false }
      }
      if (action.event.type === 'done') {
        if (action.event.truncated) return { ...next, ...clear, sending: false }
        const cp = next.pendingContextCheckpoint
        if (cp === undefined) return { ...next, ...clear, sending: false }
        return {
          ...next,
          ...clear,
          sending: false,
          contextCheckpoint: cp,
          contextStateVersion:
            action.event.contextStateVersion ?? next.pendingContextStateVersion,
        }
      }
      return next
    }

    case 'FAIL': {
      const next = patchStreaming(state, action.id, (m) => ({
        ...m,
        status: 'failed',
        error: action.error,
        elapsedMs: Math.max(action.at - m.createdAt, 0),
        steps: upsertStep(m.steps, {
          key: 'error',
          kind: 'error',
          title: '编排失败',
          status: 'error',
          at: action.at,
          detail: `${action.error.code} · ${action.error.message}`,
        }),
      }))
      if (next === state) return state
      return {
        ...next,
        sending: false,
        // 本轮失败：绝不把已到达但未完成的压缩态当下一轮上下文。
        pendingContextCheckpoint: undefined,
        pendingContextStateVersion: undefined,
      }
    }

    case 'STOP': {
      let changed = false
      const messages = state.messages.map((m): AgentChatMessage => {
        if (m.role !== 'assistant' || m.status !== 'streaming') return m
        changed = true
        return {
          ...m,
          status: 'stopped',
          elapsedMs: Math.max(action.at - m.createdAt, 0),
          steps: upsertStep(m.steps, {
            key: 'stop',
            kind: 'stop',
            title: '用户主动停止',
            status: 'info',
            at: action.at,
            detail: '本次回答被中止，不是系统故障。',
          }),
        }
      })
      if (!changed) return state
      return {
        ...state,
        messages,
        sending: false,
        // 主动停止不是完成：本轮压缩态作废，上一轮已提交的压缩态保留。
        pendingContextCheckpoint: undefined,
        pendingContextStateVersion: undefined,
      }
    }

    case 'RETRY': {
      if (state.sending) return state
      const index = state.messages.findIndex((m) => m.id === action.id)
      const target = state.messages[index]
      if (target === undefined || target.role !== 'assistant' || target.status !== 'failed') {
        return state
      }
      // ⚠️ 全字段重置：漏掉任一新字段（steps/thinking/source/truncated…）都会让重试
      //    把上一轮的执行过程或截断标记带进新气泡 —— 「兼容构造器静默清空」同类失真。
      const reset: AgentChatMessage = {
        ...target,
        status: 'streaming',
        content: '',
        createdAt: action.at,
        steps: [],
        thinking: '',
        runId: undefined,
        route: undefined,
        rag: undefined,
        usage: undefined,
        truncated: undefined,
        error: undefined,
        elapsedMs: undefined,
      }
      const next = state.messages.slice()
      next[index] = reset
      return {
        ...state,
        messages: next,
        sending: true,
        // 重试失败轮次：已提交态（来自更早的成功轮）保留，本轮 pending 作废。
        pendingContextCheckpoint: undefined,
        pendingContextStateVersion: undefined,
      }
    }

    case 'NEW_SESSION':
      return initialAgentChatState
  }
}

/** 取最后一条失败回答所属的用户问题（供「重试」原样重发）。 */
export function lastFailedAgentQuestion(
  state: AgentChatState,
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

/**
 * 多轮上下文：后端无状态，完整历史由页面维护并整体回传。
 *
 * ⚠️ 只收「用户问 + 完整且未截断的 assistant 答」成对轮次。失败/停止/截断轮次
 *    连同它的用户问题一起排除 —— 把半截答案当历史喂给模型，会让下一轮沿用错误前提。
 */
export function conversationTurns(messages: AgentChatMessage[]): {
  role: 'user' | 'assistant'
  content: string
}[] {
  const turns: { role: 'user' | 'assistant'; content: string }[] = []
  for (let i = 0; i < messages.length; i += 1) {
    const user = messages[i]
    if (user === undefined || user.role !== 'user') continue
    const assistant = messages[i + 1]
    if (
      assistant?.role === 'assistant' &&
      assistant.status === 'done' &&
      assistant.truncated !== true &&
      assistant.content.trim() !== ''
    ) {
      turns.push({ role: 'user', content: user.content }, { role: 'assistant', content: assistant.content })
    }
  }
  return turns
}

/**
 * M13 mock 暴露的「当前已提交压缩态」只读句柄。
 * ⚠️ 仅内存：刷新页面后回到 {@link initialAgentChatState}，调用方必须按「无压缩态」降级。
 * ⛔ 当前不进入请求体；真正携带 `contextState` 须等 M8 服务端五项校验 + G1 TL/QA 签核。
 */
export function currentContextCheckpoint(
  state: AgentChatState,
): { state: string; version?: string } | null {
  if (state.contextCheckpoint === undefined) return null
  return state.contextStateVersion === undefined
    ? { state: state.contextCheckpoint.state }
    : { state: state.contextCheckpoint.state, version: state.contextStateVersion }
}
