import type {
  TranscriptMessage,
  TranscriptResponse,
  TranscriptTokenSummary,
} from '@/api/traceReviewApi'

/**
 * 对话回放的呈现口径（纯函数，无 React / 无网络）。
 *
 * ⚠️ **本文件存在的理由**：后端刻意用 `null` 表示「未采集」，
 *    前端一旦把 `null` 写成 `?? 0`，就会把「没埋点的旧 Run / 用量事实缺失」
 *    显示成「0 token、0 元、0 毫秒」，并把「没有对话消息」显示成
 *    「这次辅导确实没说过话」——两者在界面上完全无法区分。
 *    这是本项目反复出现的失真形态：不报错，只是数字悄悄错。
 *
 * ⚠️ **不冒充内容判分**：这里只描述「回放事实是否齐全」，
 *    绝不输出「归因正确 / 回答正确」之类结论。答案正确性依赖题库标准解析，
 *    当前 L1 规则只判链路完整性（见 TraceEvaluationService）。
 */

export const NOT_COLLECTED = '未采集'

/** null / undefined 一律显示「未采集」，0 是真实值，必须保留。 */
export function renderOptionalNumber(v: number | null | undefined): string {
  return v === null || v === undefined ? NOT_COLLECTED : String(v)
}

export function renderDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return NOT_COLLECTED
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

export function renderStamp(v: string | null | undefined): string {
  return v === null || v === undefined || v === '' ? NOT_COLLECTED : v
}

/**
 * token 与成本汇总。
 *
 * ⚠️ `tokens === null` 表示整个用量事实不存在，不是「三项都是 0」。
 *    后端 `TranscriptResponse.tokens` 只在 `TraceReplayService` 拿到汇总时才有值。
 */
export function renderTokens(tokens: TranscriptTokenSummary | null | undefined): string {
  if (tokens === null || tokens === undefined) {
    return `${NOT_COLLECTED}（该 Run 没有用量事实，不代表没消耗 token）`
  }
  const cost = tokens.cost === null || tokens.cost === undefined
    ? NOT_COLLECTED
    : `¥${String(tokens.cost)}`
  return `输入 ${renderOptionalNumber(tokens.input)} · 输出 ${renderOptionalNumber(tokens.output)} · 成本 ${cost}`
}

function str(v: unknown): string {
  return typeof v === 'string' ? v : v === null || v === undefined ? '' : String(v)
}

/** 工具调用意图摘要（M3 只落库 name / argKeys / argCount，不含参数值）。 */
export function toolCallsLabel(content: Record<string, unknown>): string {
  const calls = content['toolCalls']
  if (!Array.isArray(calls) || calls.length === 0) return ''
  const parts = calls.map((c) => {
    if (typeof c !== 'object' || c === null) return '未知调用'
    const rec = c as Record<string, unknown>
    return `${str(rec['name']) || '未命名工具'}(${renderOptionalNumber(
      typeof rec['argCount'] === 'number' ? rec['argCount'] : null,
    )} 个参数)`
  })
  return `拟调用工具：${parts.join('、')}`
}

/** 模型输出是否被 token 上限腰斩（真实 MiniMax 实跑观测到 finishReason=LENGTH）。 */
export function isTruncatedOutput(content: Record<string, unknown>): boolean {
  const reason = str(content['finishReason']).toUpperCase()
  return reason === 'LENGTH' || reason === 'MAX_TOKENS' || reason === 'LENGTH_LIMIT'
}

/** 工具结果被后端封顶（summarizeToolOutput 会追加 …[truncated]）。 */
export function isTruncatedToolOutput(content: Record<string, unknown>): boolean {
  return str(content['output']).includes('[truncated]')
}

export interface MessageBody {
  /** 主展示文本。 */
  text: string
  /** 次要说明（角色语义、工具状态、合规边界等）。 */
  notes: string[]
}

/**
 * 按角色把 jsonb content 还原成人能读的东西。
 *
 * 键名来自 RunExecutionLoop 的 M1–M4 埋点，不是猜的：
 *  · USER      { text }
 *  · SYSTEM    { kind:'MEMORY_RECALL', items:[{knowledgePoint,errorType}], tokens }
 *  · ASSISTANT { text, toolCalls:[{id,name,argKeys,argCount}], finishReason }
 *  · TOOL      { toolName, state, output, reason }
 */
export function messageBody(message: TranscriptMessage): MessageBody {
  const c = message.content ?? {}
  const notes: string[] = []

  switch (message.role) {
    case 'USER':
      return { text: str(c['text']), notes: ['模型实际看到的渲染后输入（非原始载荷）'] }

    case 'ASSISTANT': {
      const calls = toolCallsLabel(c)
      if (calls !== '') notes.push(calls)
      const reason = str(c['finishReason'])
      if (reason !== '') notes.push(`终止原因：${reason}`)
      if (isTruncatedOutput(c)) {
        notes.push('⚠️ 输出被 token 上限截断，回答可能不完整 —— 不能据此判定辅导已讲完')
      }
      return { text: str(c['text']), notes }
    }

    case 'SYSTEM': {
      const items = c['items']
      const labels: string[] = []
      if (Array.isArray(items)) {
        for (const it of items) {
          if (typeof it === 'object' && it !== null) {
            const rec = it as Record<string, unknown>
            const kp = str(rec['knowledgePoint'])
            const et = str(rec['errorType'])
            labels.push([kp, et].filter((x) => x !== '').join(' / ') || '未标注')
          }
        }
      }
      const tokens = c['tokens']
      notes.push('仅记忆标签，按合规约定不存知识库原文')
      if (typeof tokens === 'number') notes.push(`召回 token≈${tokens}`)
      const kind = str(c['kind']) || '系统消息'
      return {
        text: labels.length > 0 ? `${kind}：${labels.join('；')}` : `${kind}（无标签明细）`,
        notes,
      }
    }

    case 'TOOL': {
      const name = str(c['toolName']) || '未知工具'
      const state = str(c['state']) || NOT_COLLECTED
      const reason = str(c['reason'])
      notes.push(`状态：${state}`)
      if (reason !== '') notes.push(`说明：${reason}`)
      if (isTruncatedToolOutput(c)) notes.push('⚠️ 工具输出已封顶截断，不是工具只返回了这么多')
      notes.push('平台审查视图可见；学生端回放隐藏 TOOL 消息')
      return { text: `${name} → ${str(c['output'])}`, notes }
    }

    default: {
      // 未知结构如实呈现，绝不静默显示成空消息（会把「解析不了」伪装成「没内容」）。
      let raw = ''
      try {
        raw = JSON.stringify(c)
      } catch {
        raw = '[无法序列化的 content]'
      }
      return { text: `未识别的角色 ${message.role}：${raw}`, notes: ['请核对后端回放埋点版本'] }
    }
  }
}

/** Run 级元信息的诚实提示（不判分，只说事实是否齐全）。 */
export function transcriptCaveats(t: TranscriptResponse): string[] {
  const out: string[] = []
  if (!Array.isArray(t.messages) || t.messages.length === 0) {
    out.push('未采集到对话消息：可能是 P1 之前的旧 Run 或埋点写入失败，不代表这次辅导没有对话。')
  } else {
    const hasUser = t.messages.some((m) => m.role === 'USER')
    const hasAssistant = t.messages.some((m) => m.role === 'ASSISTANT')
    if (!hasUser) out.push('回放缺少 USER 消息，无法核对模型实际看到的输入。')
    if (!hasAssistant) out.push('回放缺少 ASSISTANT 消息，无法核对模型输出。')
    if (t.messages.some((m) => m.role === 'ASSISTANT' && isTruncatedOutput(m.content))) {
      out.push('存在被 token 上限截断的模型输出；L1 规则会把它判为链路不完整（PARTIAL），而非回答正确。')
    }
  }
  if (t.tokens === null || t.tokens === undefined) {
    out.push('用量汇总为空（未采集），不是「0 token / 0 元」。')
  }
  if (t.durationMs === null || t.durationMs === undefined) {
    out.push('耗时未采集。')
  }
  return out
}

export const ROLE_LABELS: Record<string, string> = {
  USER: '学生输入',
  SYSTEM: '系统 / 记忆',
  ASSISTANT: '模型输出',
  TOOL: '工具结果',
}

export const ROLE_COLORS: Record<string, string> = {
  USER: 'blue',
  SYSTEM: 'default',
  ASSISTANT: 'green',
  TOOL: 'orange',
}
