import { describe, it, expect } from 'vitest'
import { diffJson, diffSummary } from '@/shared/jsonDiff'

describe('配置 Diff：路径级差异', () => {
  it('嵌套对象按叶子路径定位差异', () => {
    const before = JSON.stringify({ orchestration: { mode: 'LEAF', children: [{ agentId: 'a' }] } })
    const after = JSON.stringify({ orchestration: { mode: 'ROUTER', children: [{ agentId: 'b' }] } })
    const lines = diffJson(before, after)
    const paths = lines.map((l) => l.path).sort()
    expect(paths).toEqual(['orchestration.children[0].agentId', 'orchestration.mode'])
    expect(lines.every((l) => l.kind === 'changed')).toBe(true)
    expect(diffSummary(lines)).toEqual({ added: 0, removed: 0, changed: 2 })
  })

  it('数组新增按索引标为 added', () => {
    const lines = diffJson(
      JSON.stringify({ enabledSkills: ['a'] }),
      JSON.stringify({ enabledSkills: ['a', 'b'] }),
    )
    expect(lines).toHaveLength(1)
    expect(lines[0]?.path).toBe('enabledSkills[1]')
    expect(lines[0]?.kind).toBe('added')
    expect(lines[0]?.after).toBe('b')
  })

  it('字段删除标为 removed，并保留原值', () => {
    const lines = diffJson(
      JSON.stringify({ modelRef: 'gpt-4o', name: 'x' }),
      JSON.stringify({ name: 'x' }),
    )
    expect(lines).toHaveLength(1)
    expect(lines[0]?.kind).toBe('removed')
    expect(lines[0]?.before).toBe('gpt-4o')
    expect(lines[0]?.after).toBeNull()
  })

  it('完全一致返回空数组', () => {
    const same = JSON.stringify({ a: 1, b: [1, 2] })
    expect(diffJson(same, same)).toEqual([])
  })

  it('非法 JSON 退化为整体 changed，而不是抛错', () => {
    const lines = diffJson('{bad', '{"a":1}')
    expect(lines).toHaveLength(1)
    expect(lines[0]?.path).toBe('(整体)')
    expect(lines[0]?.kind).toBe('changed')
  })

  it('超长文本折叠为摘要，避免撑爆面板', () => {
    const long = 'x'.repeat(500)
    const lines = diffJson(JSON.stringify({ p: '' }), JSON.stringify({ p: long }))
    expect(lines[0]?.after).toContain('共 500 字符')
  })
})
