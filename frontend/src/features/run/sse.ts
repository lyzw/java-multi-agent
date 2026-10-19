/**
 * SSE 帧解析（FE-D1 §7.3）
 *
 * 后端帧格式：
 *   id: 3
 *   event: RUN_STATE_CHANGED
 *   data: {"sequenceNo":3,...}
 *   <空行>
 *
 * ⚠️ 终态时后端追加注释行 `: run-terminal` 后关闭流，
 *    故解析器必须识别注释行——它是"可以停止轮询"的唯一信号。
 */

export interface ParsedFrame {
  /** 事件序号，即 Last-Event-ID 的值 */
  id: number | null
  event: string | null
  /** data 行的原始 JSON 字符串 */
  data: string | null
  /** 是否为终止信号 */
  terminal: boolean
}

/** 空行判定（BOM 与 CR 需容忍） */
function isBlank(line: string): boolean {
  return line.trim().length === 0
}

/** 解析单帧的文本块 */
export function parseFrame(block: string): ParsedFrame | null {
  const lines = block.split(/\r?\n/)
  let id: number | null = null
  let event: string | null = null
  let data: string | null = null
  let terminal = false

  for (const rawLine of lines) {
    const line = rawLine.replace(/^﻿/, '')
    if (isBlank(line)) continue

    // 注释行：以 ':' 开头
    if (line.startsWith(':')) {
      if (line.slice(1).trim() === 'run-terminal') {
        terminal = true
      }
      continue
    }

    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    // ⚠️ SSE 规范：冒号后有一个可选空格
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    switch (field) {
      case 'id': {
        const parsed = Number.parseInt(value, 10)
        if (!Number.isNaN(parsed)) id = parsed
        break
      }
      case 'event':
        event = value
        break
      case 'data':
        // 多个 data 行应以 \n 连接（本次后端不产生多行，保留规范行为）
        data = data === null ? value : `${data}\n${value}`
        break
      default:
        // 忽略未知字段（如 retry:）
        break
    }
  }

  if (terminal) {
    return { id, event, data, terminal: true }
  }
  if (id === null && event === null && data === null) {
    return null
  }
  return { id, event, data, terminal: false }
}

/**
 * 解析完整 SSE 文本流为帧数组。
 *
 * @param text 完整响应文本
 */
export function parseStream(text: string): ParsedFrame[] {
  const frames: ParsedFrame[] = []
  // 以空行切分；末尾空行会产生空块，须跳过
  const blocks = text.split(/\r?\n\r?\n/)
  for (const block of blocks) {
    if (isBlank(block)) continue
    const frame = parseFrame(block)
    if (frame !== null) frames.push(frame)
  }
  return frames
}

/**
 * 流式增量解析器 —— 支持边读边出，避免等待整段响应结束。
 *
 * ⚠️ 必要性：后端单次最多返回 200 条事件，整段等待会让用户看到长时间空白。
 */
export class IncrementalSseParser {
  private buffer = ''
  /** 已声明的终止信号 */
  private terminalSeen = false

  /** 喂入增量文本，产出完整帧 */
  push(chunk: string): ParsedFrame[] {
    this.buffer += chunk
    const frames: ParsedFrame[] = []

    // 反复提取以空行结尾的完整块
    let searchFrom = 0
    for (;;) {
      const match = /\r?\n\r?\n/.exec(this.buffer.slice(searchFrom))
      if (match === null) break
      const end = searchFrom + match.index + match[0].length
      const block = this.buffer.slice(searchFrom, end)
      searchFrom = end
      if (isBlank(block)) continue
      const frame = parseFrame(block)
      if (frame !== null) {
        frames.push(frame)
        if (frame.terminal) this.terminalSeen = true
      }
    }
    if (searchFrom > 0) {
      this.buffer = this.buffer.slice(searchFrom)
    }
    return frames
  }

  /** 流结束：处理残留的不完整块（后端可能在末尾无空行） */
  flush(): ParsedFrame[] {
    const rest = this.buffer
    this.buffer = ''
    if (isBlank(rest)) return []
    const frame = parseFrame(rest)
    if (frame === null) return []
    if (frame.terminal) this.terminalSeen = true
    return [frame]
  }

  /** 是否已收到终止信号 */
  hasTerminal(): boolean {
    return this.terminalSeen
  }
}
