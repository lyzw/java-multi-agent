/**
 * 设计规范自检（FE-D3）
 *
 * ⛔ 这些断言不是测「样式好不好看」，而是测**设计纪律有没有被违反**。
 *   规范写在文档里会被后来者改掉；写成测试才会一直有效。
 */

import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const srcDir = join(here, '..', 'src')
const read = (p: string): string => readFileSync(join(srcDir, p), 'utf8')

const tokens = read('styles/tokens.css')
const layout = read('styles/layout.css')

describe('用色纪律（R1–R4）', () => {
  it('R1：批改红只允许出现在语义 token，不做主按钮', () => {
    // 主按钮必须是墨色
    expect(tokens).toMatch(/--ink:\s*#1a1d21/i)
    // 红色定义为 --mark（批改），而非常规的 --primary 品牌色
    expect(tokens).toMatch(/--mark:\s*#c8382d/i)
    // ⛔ 不应重新引入「品牌蓝/主色」这类会削弱红之语义的 token
    expect(tokens).not.toMatch(/--color-primary/i)
    expect(tokens).not.toMatch(/--primary:/i)
  })

  it('R3：状态色低饱和，禁止高饱和绿蓝', () => {
    // 已确认用低饱和青，而非荧光绿
    expect(tokens).toMatch(/--settled:\s*#1d6b5c/i)
    expect(tokens).not.toMatch(/#00ff00|#0f0|chartreuse|lime/i)
    expect(tokens).not.toMatch(/#00b0ff|#1e90ff|dodgerblue/i)
  })

  it('R2：主按钮用墨色', () => {
    // ⚠️ .btn 定义在 tokens.css（控件层），不在 layout.css
    expect(tokens).toMatch(/\.btn\s*\{[^}]*background:\s*var\(--ink\)/s)
  })
})

describe('版式纪律', () => {
  it('区块用线与留白分隔，不用卡片阴影', () => {
    // ⛔ 不允许 box-shadow 做卡片（AI 默认：同一套阴影）
    expect(tokens + layout).not.toMatch(/box-shadow:\s*(?!none)/i)
  })

  it('⛔ 不使用灰色微光条（AI 默认的骨架屏）', () => {
    // 加载态应是「呼吸纸面」，不是 shimmer 微光
    expect(layout).not.toMatch(/linear-gradient\(90deg, #eef1f4/i)
    expect(layout).not.toMatch(/animation:\s*shimmer/i)
    expect(layout).toMatch(/@keyframes breathe/)
  })

  it('⛔ 加载态尊重 prefers-reduced-motion', () => {
    expect(tokens).toMatch(/prefers-reduced-motion/)
  })

  it('题目区用宋体，与界面区在字体上分开', () => {
    expect(tokens).toMatch(/--font-hand:\s*'Songti SC'/)
    expect(layout + tokens).toMatch(/\.steps__body\s*\{[^}]*var\(--font-hand\)/s)
  })

  it('行宽有上限（< 80 字符）', () => {
    expect(tokens).toMatch(/max-width:\s*72ch/)
  })
})

describe('可达性基线（FE-D3 §5）', () => {
  it('焦点环全站可见', () => {
    expect(tokens).toMatch(/:focus-visible\s*\{[^}]*outline:\s*2px solid/s)
  })

  it('触摸目标 ≥ 40px', () => {
    expect(layout).toMatch(/min-height:\s*40px/)
  })

  it('错误提示用 role="alert"', () => {
    const pages = [
      read('pages/business/RunResultPage.tsx'),
      read('pages/governance/GovernancePages.tsx'),
    ].join('\n')
    expect(pages).toContain('role="alert"')
  })

  it('表格使用 scope="col"', () => {
    const config = read('pages/config/ConfigPages.tsx')
    expect(config).toContain('scope="col"')
  })
})

describe('响应式三断点', () => {
  it('应有 1023 / 767 / 479 三个断点', () => {
    expect(layout).toMatch(/@media \(max-width: 1023px\)/)
    expect(layout).toMatch(/@media \(max-width: 767px\)/)
    expect(layout).toMatch(/@media \(max-width: 479px\)/)
  })

  it('窄屏侧栏转为横向标签条', () => {
    expect(layout).toMatch(/@media \(max-width: 767px\)[\s\S]*overflow-x:\s*auto/)
  })
})

describe('⛔ 合规红线仍是结构级（非文案）', () => {
  it('P9 处置台文件内零 button 元素', () => {
    const p9 = read('pages/governance/GovernancePages.tsx')
    // 提取 P9 段落到 P10 段之间（该区间负责禁重试）
    const start = p9.indexOf('P9 未知操作处置')
    const end = p9.indexOf('P10 审计查询')
    const recon = p9.slice(start, end === -1 ? undefined : end)

    // ⛔ 注释里要解释「为什么不提供重试」——那是必要的设计说明，不能删。
    //    故只检查**实际渲染元素**，不检查文案。
    expect(recon).not.toMatch(/<button/)
    // 没有任何可点击的重试控件
    expect(recon).not.toMatch(/onClick/)
  })

  it('P5 结果页结构中无「来源/引用/出处」区块', () => {
    const p5 = read('pages/business/RunResultPage.tsx')
    // 注释里可以说明「刻意没有」，但不能有实际的 h2/h3
    const headings = p5.match(/<h[123][^>]*>([^<]*)</g) ?? []
    for (const h of headings) {
      expect(h).not.toMatch(/来源|引用|出处|页码|参考资料/)
    }
  })

  it('P17 合规页零编辑控件', () => {
    const config = read('pages/config/ConfigPages.tsx')
    expect(config).not.toMatch(/<input/)
    expect(config).not.toMatch(/<textarea/)
  })

  it('待核对分区仍然存在（P0-D00 §3.4 硬禁令）', () => {
    expect(read('pages/business/RunResultPage.tsx')).toContain('data-testid="reconcile-zone"')
  })
})