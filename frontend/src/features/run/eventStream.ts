/**
 * SSE 轮询客户端（FE-D1 §7）
 *
 * ⚠️ 后端是**短连接 + 轮询续传**，不是长连接推送（P2-D05 §3.3）：
 *    每次响应都会结束，客户端须携带 Last-Event-ID 重新请求。
 *    延迟 = 轮询间隔，非实时。
 *
 * ⚠️ 另一实测事实（FE-D1 §7.5）：
 *    run_event 表**只有 RUN_CREATED 由生产代码写入**，Worker 不写该表。
 *    故进度页长期拿不到执行中事件 —— 必须能优雅降级。
 *    ⛔ 绝不编造进度百分比。
 */

import { openEventStream } from '@/api/client'
import { isTerminalState } from './runState'
import { IncrementalSseParser, type ParsedFrame } from './sse'
import type { RunEventPayload } from '@/api/types'

/** 轮询间隔（毫秒） */
export const POLL_INTERVAL_MS = 3000
/** 出错后退避间隔，避免打爆后端 */
export const ERROR_BACKOFF_MS = 10_000

export type RunEvent = {
  sequenceNo: number
  eventType: string
  at: string
  payload: RunEventPayload
}

export interface StreamSnapshot {
  events: RunEvent[]
  lastEventId: number | null
  /** 是否已进入终态（收到 run-terminal 或读到终态 state）*/
  done: boolean
  /** 是否出错（可继续重试）*/
  error: string | null
  /** 是否正在请求中 */
  loading: boolean
  /** ⚠️ 是否为「后端未提供事件」的降级态 —— 供 UI 如实告知用户 */
  degraded: boolean
}

export interface PollerCallbacks {
  onSnapshot: (snapshot: StreamSnapshot) => void
  onUnauthorized: () => void
}

function emptySnapshot(): StreamSnapshot {
  return {
    events: [],
    lastEventId: null,
    done: false,
    error: null,
    loading: false,
    degraded: false,
  }
}

/**
 * data 载荷解析。
 *
 * ⚠️ 后端为**白名单透传**：未列入白名单的字段不会出现在响应中。
 *    此处只消费白名单字段——即便将来后端误加了敏感字段，
 *    前端也不会渲染它（纵深防御）。
 */
function toRunEvent(frame: ParsedFrame): RunEvent | null {
  if (frame.data === null || frame.id === null) return null
  let parsed: unknown
  try {
    parsed = JSON.parse(frame.data) as unknown
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null

  const record = parsed as Record<string, unknown>
  const sequenceNo =
    typeof record['sequenceNo'] === 'number'
      ? record['sequenceNo']
      : frame.id
  const at = typeof record['at'] === 'string' ? record['at'] : ''

  // 只取白名单字段
  const payload: RunEventPayload = { sequenceNo, at }
  const optionalKeys = [
    'state',
    'stepType',
    'toolName',
    'operationState',
    'errorCode',
    'phase',
  ] as const
  for (const key of optionalKeys) {
    const value = record[key]
    if (typeof value === 'string') payload[key] = value
  }
  if (typeof record['attempt'] === 'number') {
    payload.attempt = record['attempt']
  }

  return {
    sequenceNo,
    eventType: frame.event ?? 'UNKNOWN',
    at,
    payload,
  }
}

/**
 * 创建 Run 事件轮询器。
 *
 * @returns 停止函数（组件卸载时必须调用，否则定时器泄漏）
 */
export function createRunEventPoller(
  runId: string,
  callbacks: PollerCallbacks,
): { stop: () => void } {
  let snapshot = emptySnapshot()
  let timer: ReturnType<typeof setTimeout> | null = null
  let stopped = false

  const emit = (patch: Partial<StreamSnapshot>): void => {
    snapshot = { ...snapshot, ...patch }
    callbacks.onSnapshot(snapshot)
  }

  const schedule = (delay: number): void => {
    if (stopped) return
    timer = setTimeout(() => {
      void poll()
    }, delay)
  }

  const poll = async (): Promise<void> => {
    if (stopped) return
    emit({ loading: true, error: null })

    try {
      const response = await openEventStream(runId, snapshot.lastEventId)
      const text = await response.text()

      const parser = new IncrementalSseParser()
      const frames = [...parser.push(text), ...parser.flush()]

      const newEvents: RunEvent[] = []
      let maxSeq = snapshot.lastEventId
      let sawTerminal = false

      for (const frame of frames) {
        if (frame.terminal) {
          sawTerminal = true
          continue
        }
        const event = toRunEvent(frame)
        if (event !== null) {
          newEvents.push(event)
          if (maxSeq === null || event.sequenceNo > maxSeq) {
            maxSeq = event.sequenceNo
          }
          if (event.payload.state !== undefined) {
            if (isTerminalState(event.payload.state)) {
              sawTerminal = true
            }
          }
        }
      }

      const merged = [...snapshot.events, ...newEvents]

      emit({
        loading: false,
        events: merged,
        lastEventId: maxSeq,
        done: sawTerminal,
        /*
         * ⚠️ 降级判定（F-19 修复）：
         *    旧写法是 `newEvents.length === 0 && !sawTerminal`，
         *    语义被写成「**本轮**无新事件」。实际想表达的是
         *    「**整个流从未产生过任何事件**」。
         *
         *    混淆后果：第 1 轮收到 RUN_CREATED、第 2 轮无新事件时
         *    degraded 翻回 true，UI 说「详细执行步骤将在功能完善后展示」，
         *    而上方的执行事件时间线**已经列出了** RUN_CREATED
         *    → 文案说「没步骤」、界面显示「有步骤」，自相矛盾。
         *
         * ✅ 改为只看**累计**：一旦收到过事件，永久不再降级提示。
         *    这是单向阀 —— 不会因为某一轮空翻就撤回已告知用户的事实。
         */
        degraded: merged.length === 0 && !sawTerminal,
      })

      if (sawTerminal) {
        stopped = true
        return
      }
      schedule(POLL_INTERVAL_MS)
    } catch (cause) {
      const status =
        typeof cause === 'object' && cause !== null && 'normalized' in cause
          ? (cause as { normalized: { status: number; message: string } })
              .normalized
          : null

      if (status !== null && status.status === 401) {
        stopped = true
        callbacks.onUnauthorized()
        return
      }

      emit({
        loading: false,
        error: status !== null ? status.message : '事件读取失败，请稍后重试',
      })
      schedule(ERROR_BACKOFF_MS)
    }
  }

  void poll()

  return {
    stop: () => {
      stopped = true
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
    },
  }
}
