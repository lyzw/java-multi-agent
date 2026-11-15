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
  // ⚠️ **按 pathname 匹配**：query 不是路径的一部分。
  //    `navigate('/no-permission?reason=…')`（F2 · E2E 2026-11-14）提取出的
  //    静态前缀含 `?reason=`，若拿它去和路由表 `/no-permission` 精确比对，
  //    永远失配 —— 那不是「链接指向不存在的路由」，而是**比对口径错了**。
  //    ⚠️ 这**不放宽**不变式：剥掉 query 后仍必须命中某个已声明路由，
  //    否则照样转红（见下方反证用例）。
  const pathname = link.split('?')[0] ?? link
  for (const route of routes) {
    if (route === '*') continue // 兜底是重定向，不算「真实可达路由」
    if (route === pathname) return true
    // 参数路由：把 :param 段替换为通配后再比前缀
    if (route.includes(':')) {
      const staticPrefix = route.split(':')[0] ?? '' // 例如 /profile/:studentId → /profile/
      if (staticPrefix.endsWith('/') && pathname.startsWith(staticPrefix)) return true
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
    },  )

  /*
   * ⚠️ 反证用例曾以 `/config/audit` 为样本（F4 前它是独立审计页）。
   *    F4（2026-10-23）已把配置查看留痕合并进 `/audit` 过滤视图，
   *    该路由与导航项随之删除。改用仍在用的治理入口 `/approvals`
   *    作样本，反证语义不变：删掉一条 Route，对应导航项必须失配转红。
   */
  it('⛔ 反证：删掉 /approvals 这条 Route 后，对应导航项应失配转红', () => {
    const without = routes.filter((r) => r !== '/approvals')
    // /approvals 是 AppShell 导航项之一；去掉后应失配
    const navStillCovered = navTargets.every((t) => isCovered(t, without))
    expect(navStillCovered).toBe(false)
  })

  /**
   * ⚠️ 反证：query 剥离**不得**让「无对应路由的链接」蒙混过关。
   *
   * ⚠️ 这条锁的是「按 pathname 匹配」这条新口径的**下界**：
   * 剥掉 query 只是修正比对口径，**不是**给任意链接开后门。
   */
  it('⛔ 反证：query 剥离后仍须命中已声明路由（无路由者照样失配）', () => {
    expect(isCovered('/no-permission?reason=x', routes)).toBe(true)
    expect(isCovered('/definitely-not-a-route?reason=x', routes)).toBe(false)
  })
})
