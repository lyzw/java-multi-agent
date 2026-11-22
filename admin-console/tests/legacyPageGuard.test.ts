import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * 旧版目录页治理守卫（2026-11-22）。
 *
 * 审计时误把「测试仍在引用」当成「彻底无引用」。这里锁定真实边界：
 * 运行期 router 不得重新挂这些旧页面；保留文件必须显式标注 @deprecated；
 * 两个确认零引用的文件必须删除，避免同名文件继续误导 IDE 跳转。
 */
const root = resolve(process.cwd())
const read = (p: string): string => readFileSync(resolve(root, p), 'utf8')

const DEPRECATED_PAGES = [
  'src/pages/AgentRegistryPage.tsx',
  'src/pages/AgentDetailPage.tsx',
  'src/pages/catalog/McpCatalogPage.tsx',
  'src/pages/catalog/McpDetailPage.tsx',
  'src/pages/catalog/SkillCatalogPage.tsx',
  'src/pages/catalog/SkillDetailPage.tsx',
  'src/pages/catalog/ToolCatalogPage.tsx',
  'src/pages/catalog/ToolDetailPage.tsx',
  'src/pages/catalog/ResultContractPage.tsx',
]

const DELETED_PAGES = [
  'src/pages/PlaceholderPage.tsx',
  'src/pages/publish/PublishRequestDetailPage.tsx',
]

describe('旧版页面不得重新进入运行期', () => {
  it('router 不 import 任何 @deprecated 旧页面', () => {
    const router = read('src/router.tsx')
    for (const file of DEPRECATED_PAGES) {
      const basename = file.split('/').pop()!.replace(/\.tsx$/, '')
      expect(router).not.toContain(`@/pages/${file.replace(/^src\/pages\//, '').replace(/\.tsx$/, '')}'`)
      expect(router).not.toMatch(new RegExp(`pages/(catalog/)?${basename}['"]`))
    }
  })

  it.each(DEPRECATED_PAGES)('%s 保留时必须显式标注 deprecated', (file) => {
    expect(read(file)).toContain('@deprecated 运行期不可达')
  })

  it.each(DELETED_PAGES)('%s 必须已删除（零引用文件不留尾）', (file) => {
    expect(existsSync(resolve(root, file))).toBe(false)
  })
})
