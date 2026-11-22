import { describe, it, expect } from 'vitest'
import { findNavNode, HIDDEN_NAV_NODES, NAV_GROUPS, NAV_NODES } from '@/layout/navConfig'

/**
 * 侧栏菜单 key 全局唯一性 + RAG 入口收口守卫。
 *
 * AdminLayout 用 `n.path` 作为 AntD Menu 的 key，两个入口指向同一路由会产生
 * "Duplicated key '/rag/dashboard' used in Menu" 警告，且 selectedKeys 无法区分
 * 该高亮哪一个分组。历史上有过两次同类回归：
 *   ① 「知识与数据 → RAG 语料管理」与「RAG → Dashboard 总览」同路由；
 *   ② RAG 拆成四个顶级分组后，旧 /rag 单页与配置中心又各存一份路径清单。
 * 现在 RAG 全部收口到 `rag-workbench` 一个分组，组内用 `section` 分四个治理域。
 *
 * ⚠️ 反向验证：把任意一条 path 复制成第二项，或新增第二个 RAG 分组，
 *    下面的唯一性 / 单分组断言立刻变红。
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

  it('/rag/dashboard 只有一个入口，且落在 RAG 工作台分组', () => {
    const owners = NAV_GROUPS.filter((g) => g.children.some((n) => n.path === '/rag/dashboard'))
    expect(owners).toHaveLength(1)
    expect(owners[0]?.key).toBe('rag-workbench')
  })

  it('RAG 只占一个顶级分组，组内 section 覆盖四个治理域且每个节点都带 section', () => {
    const owners = NAV_GROUPS.filter((g) =>
      g.children.some((n) => n.path === '/rag' || n.path.startsWith('/rag/')),
    )
    expect(owners.map((g) => g.key)).toEqual(['rag-workbench'])
    const nodes = owners[0]!.children
    expect(nodes.some((n) => n.path === '/rag')).toBe(true)
    expect(nodes.every((n) => !!n.section)).toBe(true)
    expect([...new Set(nodes.map((n) => n.section))].sort()).toEqual(
      ['规则配置', '审核', '总览', '录入与校对', '运维'].sort(),
    )
  })

  it('菜单节点总数与分组展开结果一致（无隐藏节点）', () => {
    expect(NAV_NODES.length).toBe(NAV_GROUPS.reduce((sum, g) => sum + g.children.length, 0))
  })

  it('兼容路由 /rag/legacy 有独立反查节点，不借前缀冒充工作台总览', () => {
    expect(NAV_NODES.some((n) => n.path === '/rag/legacy')).toBe(false)
    expect(HIDDEN_NAV_NODES.map((n) => n.path)).toEqual(['/rag/legacy'])
    expect(findNavNode('/rag/legacy')?.label).toBe('旧版语料管理')
  })

  it('发布策略与发布执行同组；审计三页同组且同角色口径', () => {
    const owners = (path: string) => NAV_GROUPS.filter((g) => g.children.some((n) => n.path === path))
    expect(owners('/publish/policies')[0]?.key).toBe(owners('/publish')[0]?.key)

    const auditOwner = owners('/audit')[0]
    expect(auditOwner?.key).toBe(owners('/chat-audit-records')[0]?.key)
    expect(auditOwner?.key).toBe(owners('/audit/business')[0]?.key)
    for (const path of ['/audit', '/chat-audit-records', '/audit/business']) {
      const node = NAV_NODES.find((n) => n.path === path)
      expect(node?.requiredRoles).toContain('PLATFORM_SECURITY_ADMIN')
      expect(node?.requiredRoles).toContain('PLATFORM_AUDITOR')
    }
  })

  it('运维组按 section 分组，含 /monitoring 索引', () => {
    const ops = NAV_GROUPS.find((g) => g.key === 'ops-observability')!
    expect(ops.children.every((n) => !!n.section)).toBe(true)
    expect([...new Set(ops.children.map((n) => n.section))].sort()).toEqual(
      ['依赖关系', '发布策略', '发布执行', '运行监控'].sort(),
    )
    expect(ops.children.some((n) => n.path === '/monitoring')).toBe(true)
  })
})
