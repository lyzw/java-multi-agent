import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/** 文档同步守卫：静态预览与 README 不得继续呈现改造前的导航口径。 */
const read = (p: string): string => readFileSync(resolve(process.cwd(), p), 'utf8')

describe('页面治理文档口径', () => {
  it('README 分组表以 NAV_GROUPS 为唯一来源，含新分组', () => {
    const readme = read('README.md')
    expect(readme).toContain('唯一来源：`src/layout/navConfig.tsx`')
    for (const label of ['审计中心', '配置管理 · 基础', '配置管理 · 策略与审计', '运维观测与发布', 'RAG 工作台']) {
      expect(readme).toContain(label)
    }
    expect(readme).not.toContain('本轮 E2E 实测 30 页')
  })

  it('静态设计预览明确标注为历史视觉稿', () => {
    const html = read('design-preview.html')
    expect(html).toContain('历史视觉稿')
    expect(html).toContain('src/layout/navConfig.tsx')
  })
})
