/**
 * 模型正文的 Markdown + LaTeX 渲染契约（2026-11-17 · P0-1/B1 验收项）。
 *
 * <p><b>要防的事</b>：教师评审实测「归因讲解里公式原样显示（0 个公式渲染节点）」——
 * 对初中生就是乱码。此前 {@code ModelText} 只认粗体与行内代码，其余全是字面量。
 *
 * <p><b>反向验证（方案 B1 明写的要求）</b>：
 * ① 撤掉 {@code MarkdownAnswer} 的 KaTeX 渲染（回到纯文本）→ 本文件必须红；
 * ② 撤掉 {@code normalizeMathDelimiters}（单独占行的 {@code \[} 归一化）→ 用例 B 必须红。
 *
 * <p><b>安全边界一并钉住</b>：模型输出是不可信内容，公式渲染绝不能变成 HTML 执行通道。
 */

import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { MarkdownAnswer } from '@/shared/MarkdownAnswer'

/**
 * KaTeX 会在 .katex-mathml 的 <annotation encoding="application/x-tex"> 里保留源码
 * （无障碍用途），因此不能直接拿 document.body.textContent 断言「源码不残留」。
 * 这里剥掉全部 .katex 子树，只看学生真正会读到的可见文本。
 */
function outsideMath(container: HTMLElement): string {
  const clone = container.cloneNode(true) as HTMLElement
  clone.querySelectorAll('.katex').forEach((node) => node.remove())
  return clone.textContent ?? ''
}

describe('MarkdownAnswer · 公式渲染契约', () => {
  it('A. 行内 $…$ 与块级 $$…$$ 渲染成 KaTeX 节点，源码不残留', () => {
    const { container } = render(
      <MarkdownAnswer content={'顶点坐标是 $x = \\frac{-b}{2a}$，整式如下：\n\n$$y = a(x-h)^2 + k$$'} />,
    )

    expect(container.querySelectorAll('.katex').length)
      .toBeGreaterThanOrEqual(2)
    // ⛔ 源码形态不该出现在学生眼前
    expect(outsideMath(container)).not.toContain('\\frac{-b}{2a}')
    expect(outsideMath(container)).not.toContain('$$')
  })

  it('B. 单独占行的 \\[ … \\] 归一化为块级公式（教师评审实测的原始形态）', () => {
    const { container } = render(
      <MarkdownAnswer
        content={'配方求顶点：\n\n\\[\ny = a(x-h)^2 + k\n\\]\n\n其中 h 是顶点横坐标。'}
      />,
    )

    expect(container.querySelectorAll('.katex').length)
      .toBeGreaterThanOrEqual(1)
    expect(outsideMath(container)).not.toContain('\\[')
    expect(outsideMath(container)).not.toContain('\\]')
  })

  it('C. 裸 LaTeX 命令（无定界符）也被识别为公式', () => {
    const { container } = render(
      <MarkdownAnswer content={'重点记住 \\frac{b}{2a} 这个式子。'} />,
    )

    expect(container.querySelectorAll('.katex').length)
      .toBeGreaterThanOrEqual(1)
    expect(outsideMath(container)).not.toContain('\\frac{b}{2a}')
  })

  it('D. ⛔ 公式渲染不得变成 HTML 执行通道', () => {
    const hostile = '$x <img src=x onerror=alert(1)> <script>alert(2)</script>$'
    const { container } = render(<MarkdownAnswer content={hostile} />)

    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
  })

  it('E. 非法公式不得让整页崩溃（KaTeX throwOnError=false）', () => {
    expect(() => render(<MarkdownAnswer content={'$\\frac{1}{$'} />)).not.toThrow()
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('F. ⛔ 行内代码里的 $ 不得破坏同行真实公式的渲染', () => {
    // 回归：`echo $HOME` 里的 $ 曾被当成公式定界符，导致同一行的 $x$ 错位、0 个公式节点。
    const { container } = render(
      <MarkdownAnswer content={'用 `echo $HOME` 命令；另外 $x$ 是公式。'} />,
    )

    expect(container.querySelectorAll('.katex').length,
      '行内代码之外的 $x$ 必须仍然渲染成公式')
      .toBeGreaterThanOrEqual(1)
    expect(outsideMath(container)).toContain('echo $HOME')
  })
})
