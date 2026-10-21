import { describe, it, expect } from 'vitest'
import { readdirSync, statSync, existsSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'

/**
 * 结构不变式：src/ 与 tests/ 之外不得出现 .ts / .tsx 源码。
 *
 * ⚠️ 为什么需要它（2026-10-22，D16-F1 教训）：
 *   `frontend/{api,features,pages}/` 曾存在 4 个游离 .ts/.tsx 文件——它们
 *   **不在 tsconfig.include 内**，既不被 `tsc --noEmit` 编译、也不进 vite 构建，
 *   却写着「F6/C3/C5/C7 已实现」的注释与自述文档。
 *   结果是「tsc 零错误 + 311 项全绿」为真，而**那些文件从未被验证过**：
 *   里面有语法错误、自我 import、以及指向不存在模块的引用。
 *
 *   这类缺陷逃过所有既有检查的根因不是「断言写错了」，而是
 *   **验证工具的边界小于代码的边界**——检查根本没发生在目标对象上。
 *   与其逐个修文件，不如把边界本身变成不变式。
 */
const ROOT = resolve(__dirname, '..')
const ALLOWED_ROOTS = ['src', 'tests']
const SCAN_EXT = /\.(ts|tsx)$/
/** 不扫描的目录（构建产物与依赖，不属于源码边界）。 */
const IGNORED_DIRS = new Set(['node_modules', 'dist', 'coverage', '.git', '.vite'])

function walk(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (IGNORED_DIRS.has(entry)) continue
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) walk(full, acc)
    else if (SCAN_EXT.test(entry)) acc.push(full)
  }
  return acc
}

describe('结构不变式：源码边界（FE-D16-F1）', () => {
  it('src/ 与 tests/ 之外不存在任何 .ts/.tsx 源码', () => {
    const offenders = walk(ROOT)
      .map((f) => relative(ROOT, f))
      .filter((rel) => !ALLOWED_ROOTS.some((allowed) => rel.startsWith(`${allowed}/`)))
      // vite.config.ts 位于根目录且在 tsconfig.include 内，属合法例外
      .filter((rel) => rel !== 'vite.config.ts')

    expect(
      offenders,
      `发现游离源码文件（tsc 与 vite 都不会编译它们，等于从未验证）：\n${offenders.join('\n')}`
    ).toEqual([])
  })

  it('tsconfig 的 include 必须覆盖 src 与 tests（边界与实际源码一致）', () => {
    const tsconfig = join(ROOT, 'tsconfig.json')
    expect(existsSync(tsconfig)).toBe(true)
    const raw = require('node:fs').readFileSync(tsconfig, 'utf-8')
    // 去注释后做粗粒度断言：include 必须同时含 src 与 tests
    const body = raw.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(body).toMatch(/"include"\s*:\s*\[[^\]]*"src"/)
    expect(body).toMatch(/"include"\s*:\s*\[[^\]]*"tests"/)
  })
})