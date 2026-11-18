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
  ['/monitoring/health', 'MonitoringHealthPage'],
  ['/monitoring/denies', 'MonitoringDeniesPage'],
]

describe('O7 孤儿页面接入', () => {
  it.each(WIRING)('%s 在导航中存在', (path) => {
    expect(NAV_NODES.some((n) => n.path === path)).toBe(true)
  })

  it.each(WIRING)('%s 在路由中挂载了 %s', (path, component) => {
    // 路由相对 basename（无前导 /），组件必须以 JSX 挂载而非仅 import。
    const rel = path.replace(/^\//, '')
    const pattern = new RegExp(`path:\\s*'${rel}'[^}]*element:\\s*<${component}\\s*/>`)
    expect(router).toMatch(pattern)
  })
})
