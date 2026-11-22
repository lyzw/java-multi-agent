/**
 * O7 反向验证：已实现的运维/发布页面必须同时接入路由与导航。
 * 撤掉任一接入（路由或导航），本测试必红。
 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { NAV_NODES } from '@/layout/navConfig'

const root = resolve(__dirname, '..')
const router = readFileSync(resolve(root, 'src/router.tsx'), 'utf8')

/** path → 页面组件名；路由里必须出现 <Component />。 */
const WIRING: Array<[string, string]> = [
  ['/dependencies/graph', 'DependencyGraphPage'],
  ['/dependencies/impact', 'ImpactAnalysisPage'],
  ['/dependencies/delegations', 'DelegationTopologyPage'],
  ['/publish', 'PublishCenterPage'],
  ['/publish/releases', 'PublishReleasesPage'],
  ['/publish/policies', 'PolicyConfigPage'],
  ['/publish/drafts', 'DraftListPage'],
  ['/publish/failures', 'PublishFailuresPage'],
  ['/publish/versions', 'VersionHistoryPage'],
  ['/audit/business', 'BusinessAuditLogPage'],
  ['/monitoring', 'Navigate'],
  ['/monitoring/health', 'MonitoringHealthPage'],
  ['/monitoring/denies', 'MonitoringDeniesPage'],
]

/** 取单条 route 的源码块（到下一条 `{ path:` 前），避免正则跨路由误命中。 */
function routeBlock(path: string): string {
  const rel = path.replace(/^\//, '')
  const start = router.indexOf(`path: '${rel}'`)
  if (start < 0) return ''
  const next = router.indexOf('\n        {', start + 1)
  return router.slice(start, next < 0 ? router.length : next)
}

describe('O7 孤儿页面接入', () => {
  it.each(WIRING)('%s 在导航中存在', (path) => {
    expect(NAV_NODES.some((n) => n.path === path)).toBe(true)
  })

  it.each(WIRING)('%s 在路由中挂载了 %s，且深链有角色门禁', (path, component) => {
    const block = routeBlock(path)
    // ⛔ 只挂载组件不算：菜单隐藏后深链仍可进 ⇒ 必须与 navConfig.requiredRoles 同源。
    expect(block).toContain(`<${component}`)
    expect(block).toContain('RequireRole')
  })
})
