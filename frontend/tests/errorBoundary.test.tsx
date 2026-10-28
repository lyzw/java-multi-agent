/**
 * 内容区错误边界（2026-10-28 端到端实测后补）。
 *
 * 盯住一件事：**一个字段的渲染失败不该让整站消失**。
 * 实测当时的形态是 Skill 目录抛
 * 「Objects are not valid as a React child」→ 整棵树卸载 → 全白屏，
 * 连导航和「退出」都点不到，用户只能手动刷新。
 */

import { describe, it, expect, vi, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import { ContentErrorBoundary } from '@/shared/ErrorBoundary'

function Explodes(): JSX.Element {
  // React 18 对「对象当 children」的处理在 jsdom 下就是抛错，
  // 这里直接抛，模拟渲染期异常。
  throw new Error('Objects are not valid as a React child')
}

function BoomShell({ resetKey }: { resetKey: string }): JSX.Element {
  return (
    <ContentErrorBoundary resetKey={resetKey}>
      <div>{resetKey === '/ok' ? <p>正常内容</p> : <Explodes />}</div>
    </ContentErrorBoundary>
  )
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('内容区错误边界', () => {
  it('子树抛错时给出可读的错误态与重新渲染入口，而不是空白', () => {
    // React 会把渲染期错误打到 console.error，测试里静音以保持输出干净
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    render(<BoomShell resetKey="/bad" />)

    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(/这个页面在渲染时出错了/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新渲染本页' })).toBeInTheDocument()
    expect(spy).toHaveBeenCalled()
  })

  it('路由变化（resetKey 变）后新页面正常渲染，不被扣在错误态里', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const { rerender } = render(<BoomShell resetKey="/bad" />)
    expect(screen.getByText(/渲染时出错/)).toBeInTheDocument()

    rerender(<BoomShell resetKey="/ok" />)

    expect(screen.getByText('正常内容')).toBeInTheDocument()
    expect(spy).toHaveBeenCalled()
  })
})

/** 受控复位：点「重新渲染本页」应清掉错误态。 */
function Resettable(): JSX.Element {
  const [key, setKey] = useState('/bad')
  return (
    <>
      <button type="button" onClick={() => setKey('/ok')}>
        去正常页
      </button>
      <ContentErrorBoundary resetKey={key}>
        {key === '/ok' ? <p>正常内容</p> : <Explodes />}
      </ContentErrorBoundary>
    </>
  )
}

describe('错误边界与导航共存', () => {
  it('用户点导航离开再回来，不要求先按「重新渲染」', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    render(<Resettable />)

    expect(screen.getByText(/渲染时出错/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '去正常页' }))

    expect(screen.getByText('正常内容')).toBeInTheDocument()
    expect(spy).toHaveBeenCalled()
  })
})
