/**
 * 模型正文渲染（ModelText）的契约。
 *
 * 盯三件事：① Markdown 标记不再以字面星号出现在学生眼前；
 * ② 换行要留住（模型用空行分段，糊成一整块就没法读）；
 * ③ **模型文本永远只是文本** —— 不能因为渲染而被当成 HTML 执行。
 */

import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { render, screen } from '@testing-library/react'
import { ModelText } from '@/shared/ModelText'

const srcDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'src')

describe('ModelText', () => {
  it('行内 **粗体** 与 `代码` 渲染成元素，标记字符不残留', () => {
    render(<ModelText text={'**正确步骤：** 先算 2 × (3x + x) = 32，即 `8x = 32`。'} />)

    expect(screen.getByText('正确步骤：').tagName).toBe('STRONG')
    expect(screen.getByText('8x = 32').tagName).toBe('CODE')
    // ⛔ 星号与反引号是 Markdown 语法，不该出现在界面上
    expect(document.body.textContent).not.toContain('**')
    expect(document.body.textContent).not.toContain('`')
  })

  it('保留换行（white-space: pre-line）', () => {
    render(<ModelText text={'第一段\n\n第二段'} />)

    const paragraph = screen.getByRole('paragraph')
    expect(paragraph.className).toBe('model-text')
    // ⚠️ 这里不能用 toHaveStyle：jsdom 不加载外部样式表，pre-line 写在
    //    tokens.css 里时它永远读不到。改为直接断言样式层的规则存在，
    //    「类名有定义」这一条另有 css.integrity 不变式兜底。
    const tokens = readFileSync(join(srcDir, 'styles/tokens.css'), 'utf8')
    expect(tokens).toMatch(/\.model-text\s*\{[^}]*white-space:\s*pre-line/)
    expect(document.body.textContent).toContain('第一段\n\n第二段')
  })

  it('⛔ 模型文本里的 HTML 只作为字面量存在，绝不产生元素', () => {
    const hostile = '<img src=x onerror=alert(1)> 以及 <script>alert(2)</script>'
    const { container } = render(<ModelText text={hostile} />)

    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    expect(container.textContent).toBe(hostile)
  })

  it('不成对的星号按普通文本处理（不吞字）', () => {
    render(<ModelText text='答案是 3 * 4 = 12，别写成了 **' />)

    expect(document.body.textContent).toBe('答案是 3 * 4 = 12，别写成了 **')
  })
})
