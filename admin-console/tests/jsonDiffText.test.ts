import { describe, it, expect } from 'vitest'
import { toJsonDiffLines } from '@/shared/jsonDiff'

const join = (lines: { text: string }[]) => lines.map((l) => l.text).join('\n')

describe('JSON 形态对比：toJsonDiffLines', () => {
  it('输出仍是合法 JSON（缩进/引号/逗号完整）', () => {
    const before = JSON.stringify({ a: 1, o: { k: [1, 2] } })
    const after = JSON.stringify({ a: 2, o: { k: [1, 2, 3] } })
    expect(JSON.parse(join(toJsonDiffLines(before, after, 'before'))))
      .toEqual({ a: 1, o: { k: [1, 2] } })
    expect(JSON.parse(join(toJsonDiffLines(before, after, 'after'))))
      .toEqual({ a: 2, o: { k: [1, 2, 3] } })
  })

  it('嵌套路径正确（对象内数组内对象）', () => {
    const before = JSON.stringify({ orchestration: { children: [{ agentId: 'a' }] } })
    const after = JSON.stringify({ orchestration: { children: [{ agentId: 'b' }] } })
    const lines = toJsonDiffLines(before, after, 'after')
    const hit = lines.find((l) => l.path === 'orchestration.children[0].agentId')
    expect(hit?.kind).toBe('changed')
  })

  it('新增字段只在「变更后」标注，变更前不含该行', () => {
    const before = JSON.stringify({ a: 1 })
    const after = JSON.stringify({ a: 1, b: 2 })
    const b = toJsonDiffLines(before, after, 'before')
    const a = toJsonDiffLines(before, after, 'after')
    expect(b.find((l) => l.path === 'b')).toBeUndefined()
    expect(a.find((l) => l.path === 'b')?.kind).toBe('changed')
  })

  it('删除字段只在「变更前」标注', () => {
    const before = JSON.stringify({ a: 1, b: 2 })
    const after = JSON.stringify({ a: 1 })
    expect(toJsonDiffLines(before, after, 'before').find((l) => l.path === 'b')?.kind).toBe('changed')
    expect(toJsonDiffLines(before, after, 'after').find((l) => l.path === 'b')).toBeUndefined()
  })

  it('数组元素按索引标注', () => {
    const before = JSON.stringify({ s: ['a'] })
    const after = JSON.stringify({ s: ['a', 'b'] })
    const lines = toJsonDiffLines(before, after, 'after')
    expect(lines.find((l) => l.path === 's[0]')?.kind).toBeNull()
    expect(lines.find((l) => l.path === 's[1]')?.kind).toBe('changed')
  })

  it('空对象变非空：两侧都标 changed，而不是一删一增', () => {
    const before = JSON.stringify({ o: {} })
    const after = JSON.stringify({ o: { k: 1 } })
    expect(toJsonDiffLines(before, after, 'before').find((l) => l.path === 'o')?.kind).toBe('changed')
    expect(toJsonDiffLines(before, after, 'after').find((l) => l.path === 'o')?.kind).toBe('changed')
  })

  it('一侧为空（首个版本）时，另一侧完整输出', () => {
    const after = JSON.stringify({ a: 1 })
    expect(toJsonDiffLines(null, after, 'before')).toEqual([])
    const lines = toJsonDiffLines(null, after, 'after')
    expect(JSON.parse(join(lines))).toEqual({ a: 1 })
  })

  it('非法 JSON 退化为原文逐行，不抛错', () => {
    const lines = toJsonDiffLines('{bad', '{"a":1}', 'before')
    expect(lines.length).toBeGreaterThan(0)
    expect(lines[0]?.path).toBe('(非 JSON 原文)')
  })
})
