/**
 * 路由守卫登录回跳保留 query 测试（E2E 五轮优化，D7）。
 *
 * ⚠️ 守则是「撤掉修复点会红」：若守卫把 `state.from` 写成
 * `location.pathname`（丢了 `location.search`），用户从带查询参数的
 * 页面（如 `/config?tab=agents`）被踢到登录、再回来时会**丢失 query**，
 * 看到的是默认页而非原来那页。本文件锁住「from 必须含完整 search」。
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { RequireAuth } from '@/auth/RequireAuth'
import { RequireRole } from '@/auth/RequireRole'
import { RequireTask } from '@/auth/RequireTask'

const useSessionMock = vi.hoisted(() => vi.fn())

vi.mock('@/auth/session', () => ({
  SessionProvider: ({ children }: { children: ReactElement }) => <>{children}</>,
  useSession: () => useSessionMock(),
}))

function Capture(): JSX.Element {
  const loc = useLocation()
  const from = (loc.state as { from?: string } | null)?.from ?? ''
  return <div data-testid="captured">{from}</div>
}

function renderGuard(element: ReactElement, initialPath: string): void {
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/config" element={element} />
        <Route path="/profile" element={element} />
        <Route path="/login" element={<Capture />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('守卫登录回跳保留 query（D7）', () => {
  it('RequireAuth：未登录跳 /login 且 state.from 含完整 query', () => {
    useSessionMock.mockReturnValue({ authenticated: false, roles: [] })
    renderGuard(
      <RequireAuth>
        <div>secret</div>
      </RequireAuth>,
      '/config?tab=agents',
    )
    expect(screen.getByTestId('captured').textContent).toBe('/config?tab=agents')
  })

  it('RequireRole：未登录跳 /login 且 state.from 含完整 query', () => {
    useSessionMock.mockReturnValue({ authenticated: false, roles: [] })
    renderGuard(
      <RequireRole area="config">
        <div>secret</div>
      </RequireRole>,
      '/config?tab=runtime',
    )
    expect(screen.getByTestId('captured').textContent).toBe('/config?tab=runtime')
  })

  it('RequireTask：未登录跳 /login 且 state.from 含完整 query', () => {
    useSessionMock.mockReturnValue({ authenticated: false, roles: ['STUDENT'] })
    renderGuard(
      <RequireTask task="profile:read">
        <div>secret</div>
      </RequireTask>,
      '/profile?studentId=S-1',
    )
    expect(screen.getByTestId('captured').textContent).toBe('/profile?studentId=S-1')
  })
})
