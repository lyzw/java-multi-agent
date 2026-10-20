/**
 * 路由/链接完整性不变式（FE-D6 F-25）
 *
 * ⚠️ 思路复制自 FE-D5 的 `css.integrity.test.ts`（CSS 类 ⊆ 已定义）：
 *   把「组件用的 CSS 类 ⊆ 主样式层已定义」换成「链接目标 ⊆ 已声明路由」。
 *
 * 目标：任何 `Link` / `NavLink` 的 `to`，必须能匹配到一个 `<Route path>`。
 *   参数路由（如 `/profile/:studentId`）放行任何同前缀的字面量（如 `/profile/current`）。
 *
 * ⛔ 注意：本不变式只防「结构性坏链」（to 指向不存在的路由）。
 *   `/profile/current` 这类「结构能匹配、但语义是死链（无当前用户概念）」的问题
 *   结构性不坏，单列已知限制（见 FE-D6 §1.3），不在此拦截。
 */

import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const srcDir = join(here, '..', 'src')
const read = (p: string): string => readFileSync(join(srcDir, p), 'utf8')

/** 收集某文件内所有 <Route path="..."> 与 <Navigate to="..."> 的 path */
function collectRoutePaths(): string[] {
  const app = read('App.tsx')
  const paths = [...app.matchAll(/path="([^"]*)"/g)].map((m) => m[1] ?? '')
  return paths
}

/** 收集所有 Link/NavLink 的 to 目标（字面量与模板字面量静态前缀） */
function collectLinkTargets(): string[] {
  const targets: string[] = []
  const walk = (dir: string): void => {
    const entries = readdirSync(dir, { withFileTypes: true })
    for (const e of entries) {
      const full = join(dir, e.name)
      if (e.isDirectory()) {
        walk(full)
        continue
      }
      if (!/\.tsx$/.test(e.name)) continue
      const src = read(full.replace(srcDir + '/', ''))

      // 1) 字面量 to="..."  或  to={`/static/${...}`} 的静态前缀
      for (const m of src.matchAll(/to=\{\s*`([^`]*)`\s*\}/g)) {
        const prefix = (m[1] ?? '').split('${')[0] ?? '' // 取到第一个插值前的静态部分
        targets.push(prefix)
      }
      // 2) 纯字面量 to="..."
      for (const m of src.matchAll(/to="([^"]*)"/g)) {
        targets.push(m[1] ?? '')
      }
    }
  }
  walk(srcDir)
  return targets
}

/** 收集 AppShell 内 NAV 数组的 to: '...' 字面量 */
function collectNavTargets(): string[] {
  const shell = read('layouts/AppShell.tsx')
  return [...shell.matchAll(/to:\s*'([^']*)'/g)].map((m) => m[1] ?? '')
}

/** 判断 link 是否被某 route 覆盖（精确匹配，或参数路由的同前缀） */
function isCovered(link: string, routes: string[]): boolean {
  for (const route of routes) {
    if (route === '*') continue // 兜底是重定向，不算「真实可达路由」
    if (route === link) return true
    // 参数路由：把 :param 段替换为通配后再比前缀
    if (route.includes(':')) {
      const staticPrefix = route.split(':')[0] ?? '' // 例如 /profile/:studentId → /profile/
      if (staticPrefix.endsWith('/') && link.startsWith(staticPrefix)) return true
    }
  }
  return false
}

describe('F-25：链接目标 ⊆ 已声明路由（CI 不变式）', () => {
  const routes = collectRoutePaths()
  const linkTargets = collectLinkTargets()
  const navTargets = collectNavTargets()

  it('App.tsx 应有可解析的路由表', () => {
    expect(routes.length).toBeGreaterThanOrEqual(15)
  })

  it.each(linkTargets.filter((t) => t.startsWith('/')))(
    '链接目标 %s 应匹配到一个已声明路由',
    (link) => {
      expect(isCovered(link, routes)).toBe(true)
    },
  )

  it.each(navTargets)(
    '侧栏导航项 %s 应匹配到一个已声明路由',
    (link) => {
      expect(isCovered(link, routes)).toBe(true)
    },
  )

  it('⛔ 反证：删掉 /config/audit 这条 Route 后，对应导航项应失配转红', () => {
    const without = routes.filter((r) => r !== '/config/audit')
    // /config/audit 是 AppShell 导航项之一；去掉后应失配
    const navStillCovered = navTargets.every((t) => isCovered(t, without))
    expect(navStillCovered).toBe(false)
  })
})
