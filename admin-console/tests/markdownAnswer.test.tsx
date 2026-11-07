// @vitest-environment jsdom
/**
 * 直接对话回答正文的 Markdown / LaTeX 渲染。
 *
 * 反证重点：截图里的 `\[ … \]` 必须真被 KaTeX 接管（不是"看起来像"），
 * 流式半截公式不能吞掉后面的正文，模型输出的 HTML/外链/图片不能进 DOM。
 */
import { describe, expect, it } from 'vitest'
import { render } from '@testing-library/react'
import { MarkdownAnswer } from '@/features/aiChat/MarkdownAnswer'
import {
  closeUnclosedFence,
  escapeUnclosedDollars,
  normalizeBareLatex,
  normalizeMathDelimiters,
  prepareAnswerText,
} from '@/features/aiChat/markdownText'

describe('markdownText · 纯文本预处理', () => {
  it('✅ 模型惯用的 `\\[ … \\]` 转成 remark-math 认识的 `$$ … $$`', () => {
    const source = '看这个：\n\\[\nax^2+bx+c=0 \\quad (a\\ne 0)\n\\]\n\n判别式是 \\(\\Delta=b^2-4ac\\)。'
    expect(normalizeMathDelimiters(source)).toBe(
      '看这个：\n$$\nax^2+bx+c=0 \\quad (a\\ne 0)\n$$\n\n判别式是 $\\Delta=b^2-4ac$。',
    )
  })

  it('✅ 未闭合的 `\\[` 保持原文（流式），闭合后整体成公式', () => {
    const half = '解：\n\\[\nax^2+bx+c=0'
    expect(normalizeMathDelimiters(half)).toBe(half)
    const full = `${half}\n\\]`
    expect(normalizeMathDelimiters(full)).toContain('$$\nax^2+bx+c=0\n$$')
  })

  it('✅ 代码块里的 LaTeX / 美元符号不被改写', () => {
    const source = '```latex\n\\[x\\]\n$$y$$\n```\n\n正文 \\(\\Delta\\)'
    expect(normalizeMathDelimiters(source)).toBe('```latex\n\\[x\\]\n$$y$$\n```\n\n正文 $\\Delta$')
    expect(normalizeBareLatex('```latex\n\\[x\\]\n$$y$$\n```\n\n正文')).toBe(
      '```latex\n\\[x\\]\n$$y$$\n```\n\n正文',
    )
  })

  it('✅ 未闭合代码围栏补齐；已闭合的不动', () => {
    expect(closeUnclosedFence('```js\nconst a = 1')).toBe('```js\nconst a = 1\n```\n')
    expect(closeUnclosedFence('```js\nconst a = 1\n```')).toBe('```js\nconst a = 1\n```')
  })

  it('✅ 未闭合的 `$$` / 行内 `$` 被转义，不会吞掉后续正文', () => {
    expect(escapeUnclosedDollars('$$\nx^2\n\n后面的正文')).toBe('\\$\\$\nx^2\n\n后面的正文')
    expect(escapeUnclosedDollars('设 $\\Delta<0 时')).toBe('设 \\$\\Delta<0 时')
    // 成对的必须原样保留
    expect(escapeUnclosedDollars('设 $\\Delta<0$ 时，且 $$x^2$$')).toBe(
      '设 $\\Delta<0$ 时，且 $$x^2$$',
    )
    // 代码块里的 $ 不参与配对
    expect(escapeUnclosedDollars('```\n$price\n```')).toBe('```\n$price\n```')
  })

  it('✅ 完成态把裸 LaTeX 命令补成行内数学，已包好的不重复包', () => {
    expect(normalizeBareLatex('当 **(\\Delta<0)** 时')).toBe('当 **($\\Delta$<0)** 时')
    expect(normalizeBareLatex('已有 $\\Delta$ 与 x^2')).toBe('已有 $\\Delta$ 与 x^2')
    // Markdown 转义符不能被误判成公式
    expect(normalizeBareLatex('\\* 不是公式')).toBe('\\* 不是公式')
  })

  it('✅ prepareAnswerText：流式不补裸命令、完成态补全，且都不改原始 content', () => {
    const source = '**(\\Delta<0)**\n\n\\[\nx^2\n\\]'
    const streaming = prepareAnswerText(source, true)
    const done = prepareAnswerText(source, false)
    expect(streaming).not.toContain('$\\Delta$')
    expect(done).toContain('$\\Delta$')
    expect(done).toContain('$$\nx^2\n$$')
    expect(source).toBe('**(\\Delta<0)**\n\n\\[\nx^2\n\\]')
  })
})

describe('MarkdownAnswer · 渲染', () => {
  it('✅ `\\[ … \\]` 块公式经 KaTeX 渲染为 display math（截图场景）', () => {
    const { container } = render(
      <MarkdownAnswer content={'一元二次方程\n\n\\[\nax^2+bx+c=0 \\quad (a\\ne 0)\n\\]\n\n判别式为 \\(\\Delta=b^2-4ac\\)。'} />,
    )
    expect(container.querySelector('.katex-display')).not.toBeNull()
    expect(container.querySelectorAll('.katex').length).toBeGreaterThanOrEqual(2)
    // KaTeX 真的排了版（HTML 分支是排版后的字符，MathML 分支保留 LaTeX 注释供无障碍读取）
    expect(container.querySelector('.katex-html')?.textContent).not.toContain('\\quad')
    expect(container.querySelector('.katex-mathml')).not.toBeNull()
    expect(container.textContent).toContain('一元二次方程')
  })

  it('✅ `$$…$$` / `$…$` / GFM 表格 / 列表 / 代码块都正常呈现', () => {
    const content = [
      '## 判别式',
      '',
      '$$\\Delta=b^2-4ac$$',
      '',
      '| 情况 | 根 |',
      '| --- | --- |',
      '| $\\Delta<0$ | 无实根 |',
      '',
      '- 第一项',
      '- 第二项',
      '',
      '```python',
      'd = b*b - 4*a*c',
      '```',
    ].join('\n')
    const { container } = render(<MarkdownAnswer content={content} />)
    expect(container.querySelector('h2')?.textContent).toBe('判别式')
    expect(container.querySelector('table')).not.toBeNull()
    expect(container.querySelectorAll('li')).toHaveLength(2)
    expect(container.querySelector('pre code')?.textContent).toContain('d = b*b')
    expect(container.querySelectorAll('.katex').length).toBeGreaterThanOrEqual(2)
  })

  it('✅ fail-closed：raw HTML / 危险链接 / 外部图片都不进 DOM', () => {
    const content = [
      '<script>window.__pwned = true</script>',
      '<img src="https://example.com/pixel.png" onerror="window.__pwned = true">',
      '',
      '[点我](javascript:alert(1)) · [文档](https://example.com/docs)',
      '',
      '![追踪图](https://example.com/track.png)',
    ].join('\n')
    const { container } = render(<MarkdownAnswer content={content} />)
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect((window as unknown as { __pwned?: boolean }).__pwned).toBeUndefined()
    const links = Array.from(container.querySelectorAll('a'))
    expect(links).toHaveLength(2)
    expect(links[0]?.getAttribute('href')).toBeNull()
    expect(links[1]?.getAttribute('href')).toBe('https://example.com/docs')
    expect(links[1]?.getAttribute('rel')).toContain('noopener')
    expect(container.textContent).toContain('[图片：追踪图]')
  })

  it('✅ 流式半截公式不产生 KaTeX 错误节点，也不吞掉后文', () => {
    const { container } = render(
      <MarkdownAnswer streaming content={'解：\n\\[\nax^2+bx+c=0'} />,
    )
    expect(container.querySelector('.katex-error')).toBeNull()
    expect(container.textContent).toContain('解：')
    expect(container.textContent).toContain('ax^2+bx+c=0')
  })
})
