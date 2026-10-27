// @vitest-environment jsdom
import { describe, it, expect, beforeAll } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

import { NotFoundPage } from '@/pages/NotFoundPage'

beforeAll(() => {
  if (!window.matchMedia) {
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })) as unknown as typeof window.matchMedia
  }
})

describe('NotFoundPage（评审 §1.2 P0 · 未知路径兜底）', () => {
  it('渲染 404 反馈与两个真实去向，不泄露导航清单', () => {
    render(
      <MemoryRouter>
        <NotFoundPage />
      </MemoryRouter>,
    )
    // 明确的「不存在」反馈，而非白屏或静默重定向
    expect(screen.getByText('404')).toBeInTheDocument()
    expect(screen.getByText(/这个地址没有对应的页面/)).toBeInTheDocument()
    // 只给两个真实去向；⛔ 不应出现任何角色相关的导航清单
    expect(screen.getByRole('link', { name: '回租户注册表' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: '回登录页' })).toHaveAttribute('href', '/login')
  })
})
