/**
 * 回放呈现口径的行为守卫。
 *
 * ⚠️ 核心防线：`null = 未采集` 绝不能塌成 0；「没有消息」绝不能塌成「没对话」；
 *    「finishReason=LENGTH」绝不能显示成一次完整辅导。
 *    这些都不会抛异常，只会让审查者看到悄悄错了的结论。
 */
import { describe, expect, it } from 'vitest'
import {
  isTruncatedOutput,
  isTruncatedToolOutput,
  messageBody,
  renderDuration,
  renderOptionalNumber,
  renderTokens,
  toolCallsLabel,
  transcriptCaveats,
} from '@/features/quality/transcriptPresentation'
import type {
  TranscriptMessage,
  TranscriptResponse,
} from '@/api/traceReviewApi'

function msg(role: string, content: Record<string, unknown>, seq = 1): TranscriptMessage {
  return {
    seq,
    role,
    stepNumber: 0,
    content,
    toolOperationId: null,
    modelId: null,
    inputTokens: null,
    outputTokens: null,
    latencyMs: null,
    createdAt: '2026-11-08T12:00:00Z',
  }
}

function transcript(overrides: Partial<TranscriptResponse> = {}): TranscriptResponse {
  return {
    runId: 'run-1',
    traceId: 'trace-1',
    state: 'SUCCEEDED',
    createdAt: '2026-11-08T12:00:00Z',
    completedAt: '2026-11-08T12:00:03Z',
    durationMs: 3000,
    snapshot: {},
    messages: [
      msg('USER', { text: '这道题为什么错了？' }, 1),
      msg('ASSISTANT', { text: '先看来判别式', finishReason: 'STOP', toolCalls: [] }, 2),
    ],
    steps: [],
    tokens: { input: 120, output: 80, cost: '0.02' },
    ...overrides,
  }
}

describe('未采集 ≠ 0', () => {
  it('tokens 为 null 时明确说「未采集」，不出现 0 token / ¥0 的错觉', () => {
    const s = renderTokens(null)
    expect(s).toContain('未采集')
    expect(s).toContain('不代表没消耗 token')
    expect(s).not.toContain('输入 0')
    expect(s).not.toContain('¥0')
  })

  it('真实 0 必须保留为 0（成本 0.00 也不能被当成未采集）', () => {
    expect(renderTokens({ input: 0, output: 0, cost: '0.00' })).toBe('输入 0 · 输出 0 · 成本 ¥0.00')
    expect(renderTokens({ input: 12, output: 3, cost: null })).toContain('成本 未采集')
  })

  it('durationMs=0 是真实值 0 ms，null 才是未采集', () => {
    expect(renderDuration(0)).toBe('0 ms')
    expect(renderDuration(null)).toBe('未采集')
    expect(renderDuration(3000)).toBe('3.00 s')
    expect(renderOptionalNumber(0)).toBe('0')
    expect(renderOptionalNumber(null)).toBe('未采集')
  })
})

describe('按角色还原 content（键名来自 RunExecutionLoop 的 M1–M4 埋点）', () => {
  it('USER：渲染后的输入，不是原始载荷', () => {
    const b = messageBody(msg('USER', { text: '<student>题目</student>' }))
    expect(b.text).toContain('题目')
    expect(b.notes.join()).toContain('模型实际看到')
  })

  it('SYSTEM：只呈现记忆标签，明确说明不存知识库原文', () => {
    const b = messageBody(msg('SYSTEM', {
      kind: 'MEMORY_RECALL',
      items: [{ knowledgePoint: '一元二次方程', errorType: '判别式混淆' }],
      tokens: 42,
    }))
    expect(b.text).toContain('一元二次方程 / 判别式混淆')
    expect(b.notes.join()).toContain('不存知识库原文')
    expect(b.notes.join()).toContain('42')
  })

  it('ASSISTANT：工具调用只展示名称与参数个数，不含参数值', () => {
    const b = messageBody(msg('ASSISTANT', {
      text: '我来调用工具',
      toolCalls: [{ id: 'c1', name: 'wrongbook.lookup', argKeys: ['studentId'], argCount: 1 }],
      finishReason: 'TOOL_CALLS',
    }))
    expect(b.text).toBe('我来调用工具')
    expect(b.notes.join()).toContain('wrongbook.lookup(1 个参数)')
    expect(b.notes.join()).not.toContain('studentId')
    expect(b.notes.join()).toContain('TOOL_CALLS')
  })

  it('TOOL：状态 / 输出截断 / 学生端不可见都要如实呈现', () => {
    const b = messageBody(msg('TOOL', {
      toolName: 'wrongbook.lookup',
      state: 'FAILED',
      output: '前半段…[truncated]',
      reason: 'timeout',
    }))
    expect(b.text).toContain('wrongbook.lookup')
    expect(b.notes.join()).toContain('FAILED')
    expect(b.notes.join()).toContain('工具输出已封顶截断')
    expect(b.notes.join()).toContain('学生端回放隐藏 TOOL')
    expect(isTruncatedToolOutput({ output: '…[truncated]' })).toBe(true)
  })

  it('未知角色如实暴露，不静默显示成空消息', () => {
    const b = messageBody(msg('UNKNOWN_ROLE', { foo: 'bar' }))
    expect(b.text).toContain('未识别的角色')
    expect(b.text).toContain('foo')
  })
})

describe('截断输出（真实 MiniMax 实跑观测点）', () => {
  it('finishReason=LENGTH 判为截断并给出「不能判定已讲完」的警示', () => {
    const c = { text: '因为判别式', finishReason: 'LENGTH', toolCalls: [] }
    expect(isTruncatedOutput(c)).toBe(true)
    const b = messageBody(msg('ASSISTANT', c))
    expect(b.notes.join()).toContain('不能据此判定辅导已讲完')
  })

  it('MAX_TOKENS 同样算截断；STOP 不算', () => {
    expect(isTruncatedOutput({ finishReason: 'MAX_TOKENS' })).toBe(true)
    expect(isTruncatedOutput({ finishReason: 'STOP' })).toBe(false)
    expect(isTruncatedOutput({})).toBe(false)
  })

  it('工具调用摘要在无调用时为空串，不生成「拟调用工具：」的空标签', () => {
    expect(toolCallsLabel({ toolCalls: [] })).toBe('')
    expect(toolCallsLabel({})).toBe('')
  })
})

describe('Run 级完整性提示（只说事实，不冒充判分）', () => {
  it('空时间线必须说「未采集」，绝不说「没有对话」', () => {
    const caveats = transcriptCaveats(transcript({ messages: [] }))
    expect(caveats.join()).toContain('未采集到对话消息')
    expect(caveats.join()).toContain('不代表这次辅导没有对话')
    expect(caveats.join()).not.toContain('归因正确率')
  })

  it('缺 USER / 缺 ASSISTANT 分别指出，不能合并成一句「数据不全」', () => {
    const onlyUser = transcriptCaveats(transcript({ messages: [msg('USER', { text: 'x' })] }))
    expect(onlyUser.join()).toContain('缺少 ASSISTANT 消息')
    const onlyAssistant = transcriptCaveats(transcript({ messages: [msg('ASSISTANT', { text: 'y' })] }))
    expect(onlyAssistant.join()).toContain('缺少 USER 消息')
  })

  it('截断输出提示 L1 会判 PARTIAL，但绝不写成「回答错误」', () => {
    const caveats = transcriptCaveats(transcript({
      messages: [
        msg('USER', { text: 'x' }),
        msg('ASSISTANT', { text: 'y', finishReason: 'LENGTH', toolCalls: [] }),
      ],
    }))
    expect(caveats.join()).toContain('判为链路不完整（PARTIAL）')
    expect(caveats.join()).not.toContain('回答错误')
    expect(caveats.join()).not.toContain('归因错误')
  })

  it('tokens / 耗时为 null 都提示未采集', () => {
    const caveats = transcriptCaveats(transcript({ tokens: null, durationMs: null }))
    expect(caveats.join()).toContain('不是「0 token / 0 元」')
    expect(caveats.join()).toContain('耗时未采集')
  })

  it('完整回放不产生任何警告', () => {
    expect(transcriptCaveats(transcript())).toEqual([])
  })
})
