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

/**
 * 2026-10-24 UX 评审新增的不变式。
 *
 * ⚠️ 这一组每一条都对应一个**实测出来**的缺陷，
 *    写成断言是为了让它们不再悄悄回来。
 */
describe('UX 评审回归防线（2026-10-24）', () => {
  /** 剥离注释，避免注释里的类名/数值误判为规则 */
  const strip = (s: string): string =>
    s.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')

  /** 取「选择器命中该名字」的所有规则块 */
  const blocks = (css: string, selector: string): string[] =>
    strip(css)
      .split('}')
      .filter((block) => block.includes(selector))

  it('R5：承载文字的元素不得使用三级墨 --ink-3（实算仅 2.77~3.22:1）', () => {
    const textBearing = [
      '.nav__group-title',
      '.nav__group-hint',
      '.header__identity',
      '.page-head__eyebrow',
      '.kv dt',
      '.stat__hint',
      '.chart__label',
      '.loading__label',
      '.unavailable__meta',
    ]
    for (const sel of textBearing) {
      const matched = blocks(layout, sel)
      expect(matched.length, `应能找到 ${sel} 的规则`).toBeGreaterThan(0)
      for (const block of matched) {
        expect(block, `${sel} 不得用 --ink-3 承载文字`).not.toMatch(/var\(--ink-3\)/)
      }
    }
  })

  it('⛔ 移动端主内容不得按内容宽度收缩（否则整页横向滚动）', () => {
    // .shell__body 在 ≤767px 变成纵向 flex，align-items 必须是 stretch：
    // 若是 flex-start，.main 会被收缩成「内容最大宽度」并把整页顶出视口
    // （实测 /config scrollWidth 935 vs 视口 390）。
    expect(layout).toMatch(/@media \(max-width: 767px\)[\s\S]{0,600}?\.shell__body\s*\{[^}]*align-items:\s*stretch/)
  })

  it('⛔ 侧栏标签只许视觉隐藏，不许 display:none（会移出无障碍树）', () => {
    for (const sel of ['.nav__label', '.nav__group-title', '.nav__group-hint']) {
      for (const block of blocks(layout, sel)) {
        expect(block, `${sel} 不得用 display:none 收起`).not.toMatch(/display:\s*none/)
      }
    }
    // 视觉隐藏用 clip 方案（保留在无障碍树与可访问名计算里）
    expect(layout).toMatch(/clip:\s*rect\(0,\s*0,\s*0,\s*0\)/)
  })

  it('⛔ 正文链接必须有颜色规则（否则落回浏览器默认蓝 rgb(0,0,238)）', () => {
    expect(layout).toMatch(/main a\s*\{[^}]*color:\s*var\(--ink-2\)/)
  })

  it('输入框边框对比度须 ≥ 3:1（用 --rule-input，不是 --rule-strong）', () => {
    expect(tokens).toMatch(/--rule-input:\s*#868c94/)
    expect(tokens).toMatch(/border:\s*1px solid var\(--rule-input\)/)
  })
})

describe('⛔ 合规红线仍是结构级（非文案）', () => {
  it('P9 处置台不得提供任何「重试/重放」入口（Sev-0）', () => {
    const p9 = read('pages/governance/GovernancePages.tsx')
    /*
     * ⚠️ 2026-10-24 修复：旧版这段断言**从未真正检查过任何东西**。
     *    标记写的是 'P9 未知操作处置'，而文件里实际的段落标记是
     *    'P9 UNKNOWN 处置台' —— indexOf 返回 -1，slice(-1) 只切到
     *    文件的最后一个字符，于是「零 button」这条断言一直空转。
     *    （2026-10-24 UX 评审实测发现。）
     *
     * 顺带修正断言口径：红线是「不得**重试**」，
     * 不是「一个按钮都不许有」——「记录核对结论」「取消」是
     * **核对结论**的入口，与重放无关（运行时那条更强的断言在
     * tests/governance.page.test.tsx：它按渲染后的按钮文案逐个查）。
     */
    const start = p9.indexOf('P9 UNKNOWN 处置台')
    expect(start, 'P9 段落标记必须存在——否则本断言形同虚设').toBeGreaterThan(-1)
    const end = p9.indexOf('P10 审计查询')
    const recon = p9.slice(start, end === -1 ? undefined : end)

    // 按钮的可访问名不得是「重试/重放」
    expect(recon).not.toMatch(/<button[^>]*>\s*(重试|重放|retry)/i)
    // 事件处理不得指向重试类动作
    expect(recon).not.toMatch(/onClick=\{[^}]*(retry|重试|重放)/i)
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
    // ⛔ 只检查 P17 合规页区间：零保留登记属法律承诺（GATE-2 合同承诺），
    //    P17 前端不得提供编辑控件。P18 起是 Agent 管理页（G2），允许创建/发布
    //    表单（input/textarea）——若整文件扫描会把 P18 的合法管理表单误判为违规。
    //    故仿 P9 区间切法，定位到 P17→P18 段。
    const start = config.indexOf('P17 合规与零保留')
    const end = config.indexOf('P18 Agent 配置与发布')
    const p17 = config.slice(start, end === -1 ? undefined : end)
    expect(p17).not.toMatch(/<input/)
    expect(p17).not.toMatch(/<textarea/)
  })

  it('待核对分区仍然存在（P0-D00 §3.4 硬禁令）', () => {
    expect(read('pages/business/RunResultPage.tsx')).toContain('data-testid="reconcile-zone"')
  })
})