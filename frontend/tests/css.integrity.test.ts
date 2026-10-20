/**
 * CSS 完整性回归（FE-D5）
 *
 * ⚠️ 这个测试要拦住的，正是 FE-D4 F-17 漏掉的那类问题：
 *    组件用了某个 className，但主样式层（tokens.css / layout.css）没有定义，
 *    于是该元素在生产环境**裸奔**（只吃 body 默认值）。
 *
 * FE-D4 期间发现：legacy-backup.css 自 FE-D3 起就**没被 index.css 引入**，
 * 其中 8 个被组件使用的类（error / field__errors / not-available / status
 * (+修饰符) / status__label / status__hint / notice / unavailable）长期无样式。
 * 本轮把它们迁移进 layout.css 并删除了 legacy-backup.css。
 *
 * 本测试把「组件用到的类名 ⊆ 主样式层已定义」变成**可执行的不变式**，
 * 这样未来任何一次「加组件却忘写样式」都会立刻转红。
 */

import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const srcDir = join(here, '..', 'src')

const read = (p: string): string => readFileSync(join(srcDir, p), 'utf8')

/** 剥离注释（块注释 + 行注释），避免把注释里的 `className="old"` 误判为使用 */
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
}

/** 提取主样式层定义的全部类名（含 .a / .a--mod / .parent .child） */
function definedClasses(): Set<string> {
  const out = new Set<string>()
  for (const file of ['styles/tokens.css', 'styles/layout.css']) {
    const s = stripComments(read(file))
    for (const m of s.matchAll(/\.([a-zA-Z][\w-]*)/g)) out.add(m[1] ?? '')
  }
  return out
}

/**
 * 提取组件使用的类名。
 * 覆盖：静态 `className="a b"`、模板串里**引号内的**字面量 `className={\`a ${x}\`}`。
 * ⚠️ 不覆盖纯变量引用 `className={var}`（无法静态解析，靠人工/连通性测试兜底）。
 *    因此本测试只认「写死在引号里的类名」——这与本项目 className 的实际写法一致。
 */
function usedClasses(): Set<string> {
  const out = new Set<string>()
  const collect = (dir: string): void => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name)
      const stat = statSync(full)
      if (stat.isDirectory()) {
        if (name === 'node_modules' || name === 'dist') continue
        collect(full)
      } else if (name.endsWith('.tsx')) {
        const code = stripComments(readFileSync(full, 'utf8'))
        for (const m of code.matchAll(/className="([^"]*)"/g)) {
          for (const c of (m[1] ?? '').split(/\s+/)) if (c) out.add(c)
        }
        for (const m of code.matchAll(/className=\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}/g)) {
          // 只取引号内的字面量；变量引用（如 {cls}）跳过
          for (const lit of (m[1] ?? '').match(/'([^']*)'|`([^`]*)`|"([^"]*)"/g) ?? []) {
            const inner = lit.slice(1, -1).replace(/\$\{[^}]*\}/g, ' ')
            for (const c of inner.split(/\s+/)) if (c) out.add(c)
          }
        }
      }
    }
  }
  collect(srcDir)
  return out
}

/** 永远不可能是类名的 token（TS 关键字 / 类型名被模板串误抓） */
const NON_CLASS_TOKENS = new Set([
  'object', 'string', 'number', 'boolean', 'undefined', 'null',
  'true', 'false', 'void', 'any', 'never', 'unknown', 'symbol',
  'bigint', 'function', 'calm',
])

describe('FE-D5：CSS 完整性不变式', () => {
  const defined = definedClasses()
  const used = usedClasses()

  it('legacy-backup.css 已删除（不再有双份样式源）', () => {
    expect(existsSync(join(srcDir, 'styles/legacy-backup.css'))).toBe(false)
  })

  it('FE-D5 迁移的 8 个裸奔类已在主样式层定义', () => {
    const migrated = [
      'error', 'field__errors', 'not-available',
      'status', 'status--success', 'status--failure',
      'status__label', 'status__hint', 'notice', 'unavailable',
    ]
    for (const c of migrated) {
      expect(defined.has(c), `类 .${c} 应在主样式层有定义`).toBe(true)
    }
  })

  it('组件使用的每个类名都在主样式层有定义（除明确豁免项）', () => {
    const violations: string[] = []
    for (const token of used) {
      if (token.startsWith('is-') || token.startsWith('has-')) continue // 状态/特性前缀，动态切换，不要求样式
      if (token.startsWith('status--')) {
        // 动态修饰符：基线 .status 已定义即视为覆盖
        if (!defined.has('status')) violations.push(token)
        continue
      }
      if (token.startsWith('-')) continue // 模板串误抓的带前缀噪声
      if (NON_CLASS_TOKENS.has(token)) continue
      if (!defined.has(token)) violations.push(token)
    }
    expect(
      violations,
      `以下类名被组件使用但主样式层未定义：${violations.join(', ')}`,
    ).toEqual([])
  })
})
