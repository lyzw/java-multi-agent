// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import {
  makeDefaultDraft,
  makePredicate,
  makeValue,
  pretty,
  serializeInputSchema,
  serializePredicate,
  serializeProbes,
  serializeRules,
  serializeSamples,
  validateDraft,
} from '../src/pages/catalog/routingBuilder/routingVisualModel'
import type { PredicateNode, VisualDraft } from '../src/pages/catalog/routingBuilder/routingVisualModel'

/**
 * 可视化编辑器 → 后端 `PolicyCodec` 文法的契约锁。
 *
 * <p>这里不实现求值器；只保证「界面点出来的条件」能变成后端认识的单键谓词对象，
 * 且不会出现 gt/ne 这类不存在的算子或前端自作主张的字段名。
 */

describe('路由策略可视化模型 · 序列化文法', () => {
  it('默认草稿是一份结构合法、可直接试跑的策略', () => {
    const draft = makeDefaultDraft()
    draft.policyId = 'order-routing' // 策略标识由使用者填写，其余部分应天然合法
    const report = validateDraft(draft)
    expect(report.errors).toEqual([])
    expect(report.warnings).toEqual([])

    expect(serializeInputSchema(draft)).toEqual({
      type: 'object',
      properties: {
        topic: { type: 'string', enum: ['order_status', 'refund', 'policy'] },
        orderId: { type: 'string' },
      },
      required: ['topic'],
    })

    const rules = serializeRules(draft) as Record<string, unknown>[]
    expect(rules).toHaveLength(2)
    expect(Object.keys(rules[0]!).sort()).toEqual(['note', 'onError', 'ruleId', 'target', 'when'])
    expect(rules[0]!.when).toEqual({
      and: [
        { eq: ['in.topic', 'order_status'] },
        { not: { null: ['in.orderId'] } },
      ],
    })
    // ordinal 由服务端按声明序回填，前端不得写入
    expect(pretty(rules)).not.toContain('ordinal')
  })

  it('样本与探针只输出后端认识的键；空 ctx 不出现', () => {
    const draft = makeDefaultDraft()
    const samples = serializeSamples(draft) as Record<string, unknown>[]
    expect(Object.keys(samples[0]!).sort()).toEqual(['expectTarget', 'input', 'label', 'via'])
    expect(samples[0]!.input).toEqual({ topic: 'order_status', orderId: 'SO-1' })

    const probes = serializeProbes(draft) as Record<string, unknown>[]
    expect(Object.keys(probes[0]!).sort()).toEqual(['input', 'label'])
    expect(JSON.stringify(probes)).not.toContain('expectTarget')
  })

  it('12 个算子都序列化成单键对象，且不出现不存在的算子', () => {
    const left = makeValue('input', 'text')
    const literal = { ...makeValue('literal'), literal: 'abc' }
    const nodes: PredicateNode[] = [
      { key: 'p', kind: 'const', value: true },
      { key: 'p', kind: 'and', children: [makePredicate('eq', 'text')] },
      { key: 'p', kind: 'or', children: [makePredicate('eq', 'text')] },
      { key: 'p', kind: 'not', child: makePredicate('null', 'text') },
      { key: 'p', kind: 'eq', left, right: literal },
      { key: 'p', kind: 'in', left, items: ['a', 'b'], itemType: 'string' },
      { key: 'p', kind: 'null', left },
      { key: 'p', kind: 'startsWith', left, right: literal },
      { key: 'p', kind: 'endsWith', left, right: literal },
      { key: 'p', kind: 'contains', left, right: literal },
      { key: 'p', kind: 'anyOf', left: makeValue('input', 'tags'), items: ['a'], itemType: 'string' },
      { key: 'p', kind: 're', left, right: { ...makeValue('literal'), literal: 'SO-\\d{6}' } },
    ]
    const serialized = nodes.map(serializePredicate)
    serialized.forEach((node) => expect(Object.keys(node)).toHaveLength(1))

    expect(serialized[4]).toEqual({ eq: ['in.text', 'abc'] })
    expect(serialized[5]).toEqual({ in: ['in.text', ['a', 'b']] })
    expect(serialized[6]).toEqual({ null: ['in.text'] })
    expect(serialized[10]).toEqual({ anyOf: ['in.tags', ['a']] })
    expect(serialized[11]).toEqual({ re: ['in.text', 'SO-\\d{6}'] })

    const all = pretty(serialized)
    for (const bad of ['"gt"', '"gte"', '"lt"', '"lte"', '"ne"']) {
      expect(all).not.toContain(bad)
    }
  })

  it('值位置保留标量类型：数字 7 与文本 "7" 不同', () => {
    const str = serializePredicate({
      key: 'p', kind: 'eq',
      left: makeValue('input', 'text'),
      right: { ...makeValue('literal'), literal: '7' },
    })
    const num = serializePredicate({
      key: 'p', kind: 'eq',
      left: makeValue('input', 'amount'),
      right: { ...makeValue('literal', 'number'), literalType: 'number', literal: '7' },
    })
    expect(str).toEqual({ eq: ['in.text', '7'] })
    expect(num).toEqual({ eq: ['in.amount', 7] })
  })

  it('结构校验拦住未声明字段、非法枚举与缺失兜底目标', () => {
    const draft: VisualDraft = makeDefaultDraft()
    draft.rules = [{
      key: 'r1', ruleId: 'r1', target: 'x', onError: 'SKIP', note: '',
      when: {
        key: 'p', kind: 'eq',
        left: makeValue('input', 'typoField'),
        right: { ...makeValue('literal'), literal: 'a' },
      },
    }]
    draft.policyId = 'x'
    expect(validateDraft(draft).errors.some((e) => e.includes('typoField'))).toBe(true)

    const fallback: VisualDraft = { ...makeDefaultDraft(), policyId: 'x', noMatchAction: 'DEFAULT_TARGET' }
    expect(validateDraft(fallback).errors.some((e) => e.includes('兜底目标'))).toBe(true)
  })
})
