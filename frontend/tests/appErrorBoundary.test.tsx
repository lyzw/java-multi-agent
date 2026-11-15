/**
 * 顶层错误边界测试（E2E 四轮优化，DEF-1）。
 *
 * ⚠️ 守则是「撤掉修复点会红」：若 `App.tsx` 没用 `AppErrorBoundary` 包住
 * `AppRoutes`，子组件抛错会冒泡到根、整棵 `<App/>` 被卸载（全白屏），
 * 此时 `getByText('页面出错了')` 必然失败。
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { AppErrorBoundary } from '@/shared/AppErrorBoundary'

const signOutMock = vi.fn()

vi.mock('@/auth/session', () => ({
  SessionProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
  useSession: () => ({ signOut: signOutMock }),
}))

function Boom(): JSX.Element {
  throw new Error('kaboom')
}

describe('AppErrorBoundary', () => {
  afterEach(() => {
    signOutMock.mockReset()
    // ⚠️ 还原可能被 replace 的 window.location
    delete (window as unknown as { __loc?: Location }).__loc
  })

  it('捕获子组件渲染异常并展示整页兜底，不白屏', () => {
    render(
      <MemoryRouter>
        <AppErrorBoundary resetKey="/x">
          <Boom />
        </AppErrorBoundary>
      </MemoryRouter>,
    )
    expect(screen.getByText('页面出错了')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回登录页' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试本页' })).toBeInTheDocument()
  })

  it('点击「返回登录页」清会话并整页跳转 /login', () => {
    const assignSpy = vi.fn()
    const realLocation = window.location
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...realLocation, assign: assignSpy },
    })
    try {
      render(
        <MemoryRouter>
          <AppErrorBoundary resetKey="/x">
            <Boom />
          </AppErrorBoundary>
        </MemoryRouter>,
      )
      fireEvent.click(screen.getByRole('button', { name: '返回登录页' }))
      expect(signOutMock).toHaveBeenCalled()
      expect(assignSpy).toHaveBeenCalledWith('/login')
    } finally {
      Object.defineProperty(window, 'location', { configurable: true, value: realLocation })
    }
  })

  it('⛔ 反证：子组件不抛错时不应出现兜底（对照「抛错才兜底」）', () => {
    render(
      <MemoryRouter>
        <AppErrorBoundary resetKey="/x">
          <div>正常内容</div>
        </AppErrorBoundary>
      </MemoryRouter>,
    )
    expect(screen.queryByText('页面出错了')).not.toBeInTheDocument()
    expect(screen.getByText('正常内容')).toBeInTheDocument()
  })
})
