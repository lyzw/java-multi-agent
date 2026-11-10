import { describe, it, expect } from 'vitest'
import { NAV_GROUPS, NAV_NODES } from '@/layout/navConfig'

/**
 * 2026-11-10 E2E 缺陷 D-05 守卫：侧栏菜单 key 必须全局唯一。
 *
 * AdminLayout 用 `n.path` 作为 AntD Menu 的 key，两个入口指向同一路由会产生
 * "Duplicated key '/rag/dashboard' used in Menu" 警告，且 selectedKeys 无法区分
 * 该高亮哪一个分组。原先「知识与数据 → RAG 语料管理」是改造前遗留的旧入口，
 * 与「RAG · 录入与校对 → Dashboard 总览」完全同路由，已删除。
 *
 * ⚠️ 反向验证：把任意一条 path 复制成第二项，下面的唯一性断言立刻变红。
 */
describe('侧栏菜单路径唯一性', () => {
  it('每条 path 在 NAV_NODES 中恰好出现一次', () => {
    const paths = NAV_NODES.map((n) => n.path)
    const duplicates = paths.filter((p, i) => paths.indexOf(p) !== i)
    expect(duplicates).toEqual([])
    expect(new Set(paths).size).toBe(paths.length)
  })

  it('分组内也不存在重复 path，且每个分组至少有一个可点入口', () => {
    for (const group of NAV_GROUPS) {
      expect(group.children.length).toBeGreaterThan(0)
      const paths = group.children.map((n) => n.path)
      expect(new Set(paths).size).toBe(paths.length)
    }
  })

  it('/rag/dashboard 只有一个入口，且落在 RAG 治理域分组', () => {
    const owners = NAV_GROUPS.filter((g) => g.children.some((n) => n.path === '/rag/dashboard'))
    expect(owners).toHaveLength(1)
    expect(owners[0]?.key).toBe('rag-ingest')
  })

  it('菜单节点总数与分组展开结果一致（无隐藏节点）', () => {
    expect(NAV_NODES.length).toBe(NAV_GROUPS.reduce((sum, g) => sum + g.children.length, 0))
  })
})
