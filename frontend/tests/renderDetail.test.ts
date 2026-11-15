/**
 * 审计明细渲染守卫测试（E2E 五轮优化，D6）。
 *
 * ⚠️ 守则是「撤掉修复点会红」：若 `renderDetail` 不做
 * `detail === null || typeof detail !== 'object' || Array.isArray(detail)`
 * 守卫，后端偶发回带 null / 字符串 / 数组时，`Object.entries(null)`
 * 会抛错，把整页审计列表崩成错误态。本文件锁住「非 Record 一律降级为 '—'」。
 */
import { describe, it, expect } from 'vitest'
import { renderDetail } from '@/pages/governance/GovernancePages'

describe('renderDetail 类型守卫（D6）', () => {
  it('null → 占位符', () => {
    expect(renderDetail(null)).toBe('—')
  })
  it('字符串 → 占位符（非对象不得铺开）', () => {
    expect(renderDetail('some-string')).toBe('—')
  })
  it('数组 → 占位符', () => {
    expect(renderDetail([1, 2, 3])).toBe('—')
  })
  it('数字 → 占位符', () => {
    expect(renderDetail(42)).toBe('—')
  })
  it('空对象 → 占位符（无字段可展示）', () => {
    expect(renderDetail({})).toBe('—')
  })
  it('标量键值 → 拼接为可读串', () => {
    expect(renderDetail({ actor: 'alice', action: 'publish' })).toBe('actor: alice  action: publish')
  })
  it('嵌套对象值 → 摘要而非铺开', () => {
    expect(renderDetail({ actor: 'alice', meta: { x: 1 } })).toBe('actor: alice  meta: …')
  })
  it('null 值字段被跳过', () => {
    expect(renderDetail({ actor: 'alice', reason: null })).toBe('actor: alice')
  })
})
