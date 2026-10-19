/**
 * Run 状态映射（FE-D1 §8）
 *
 * ⚠️ 取自后端 RunState 枚举的**实际取值**（不是文档描述）。
 * ⛔ TIMED_OUT 是真实终态 —— 后端 P2-D05 §4 记载：
 *    初稿漏判 TIMED_OUT，导致 SSE 连接永不关闭。
 *    本文件的终态判定与轮询终止条件**均不得遗漏**。
 */

/** 后端 RunState 的全部 6 个取值 */
export const RUN_STATES = [
  'QUEUED',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT',
] as const

export type RunState = (typeof RUN_STATES)[number]

/**
 * 终态集合。
 *
 * 依据：RunState.legalTargets() 为空集者（后端代码事实）。
 * ⚠️ 非「非 RUNNING 即终态」——QUEUED 也是非终态。
 */
const TERMINAL_STATES: ReadonlySet<string> = new Set<string>([
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT',
])

export function isTerminalState(state: string): boolean {
  return TERMINAL_STATES.has(state)
}

export function isKnownState(state: string): state is RunState {
  return (RUN_STATES as readonly string[]).includes(state)
}

/** 面向用户的中文标签 */
export function stateLabel(state: string): string {
  switch (state) {
    case 'QUEUED':
      return '排队中'
    case 'RUNNING':
      return '执行中'
    case 'SUCCEEDED':
      return '已完成'
    case 'FAILED':
      return '失败'
    case 'CANCELLED':
      return '已取消'
    case 'TIMED_OUT':
      return '已超时'
    default:
      // ⚠️ 未知状态不猜测含义。如实呈现，避免误导
      return '未知状态'
  }
}

/** 语义色调，供 UI 使用 */
export type StateTone = 'pending' | 'running' | 'success' | 'failure' | 'neutral'

export function stateTone(state: string): StateTone {
  switch (state) {
    case 'QUEUED':
      return 'pending'
    case 'RUNNING':
      return 'running'
    case 'SUCCEEDED':
      return 'success'
    case 'FAILED':
      return 'failure'
    case 'CANCELLED':
      return 'neutral'
    case 'TIMED_OUT':
      // ⛔ TIMED_OUT 须有独立呈现——它是失败的一种，
      //    但与业务失败（FAILED）成因不同，合并会掩盖超时问题
      return 'failure'
    default:
      return 'neutral'
  }
}

/** 失败类终态的补充说明 */
export function stateHint(state: string, errorCode?: string): string | null {
  if (state === 'FAILED') {
    return errorCode !== undefined && errorCode.length > 0
      ? `失败原因：${errorCode}`
      : '本次执行未完成'
  }
  if (state === 'TIMED_OUT') {
    return '执行超时未能完成'
  }
  if (state === 'CANCELLED') {
    return '本次执行已取消'
  }
  return null
}
