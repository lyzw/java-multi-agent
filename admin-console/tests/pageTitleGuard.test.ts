import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { extname, join, resolve } from 'node:path'

/**
 * 页标题单一来源守卫（2026-11-22）：
 * 标题后缀与面包屑/铭牌必须同口径；受保护页面不得再各自 usePageTitle，
 * 否则 React effect 执行顺序会让父布局覆盖子页面，形成第二份无效来源。
 */
const srcDir = resolve(process.cwd(), 'src')

function tsFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) return tsFiles(full)
    return ['.ts', '.tsx'].includes(extname(entry.name)) ? [full] : []
  })
}

describe('页标题单一来源', () => {
  it('标题后缀、布局兜底、面包屑根级统一为「平台控制台」', () => {
    expect(readFileSync(join(srcDir, 'shared/usePageTitle.ts'), 'utf8')).toContain("SUFFIX = '平台控制台'")
    expect(readFileSync(join(srcDir, 'layout/AdminLayout.tsx'), 'utf8')).toContain("matched?.label ?? '平台控制台'")
    expect(readFileSync(join(srcDir, 'layout/Breadcrumb.tsx'), 'utf8')).toContain("title: '平台控制台'")
  })

  it('只有登录页与布局调用 usePageTitle（业务页标题由导航 label 派生）', () => {
    const callers = tsFiles(srcDir).filter((file) => {
      const text = readFileSync(file, 'utf8')
      return /usePageTitle\s*\(/.test(text) && !file.endsWith('shared/usePageTitle.ts')
    })
    const rel = callers.map((f) => f.replace(`${srcDir}/`, ''))
    expect(rel.sort()).toEqual(['layout/AdminLayout.tsx', 'pages/LoginPage.tsx'])
  })
})
