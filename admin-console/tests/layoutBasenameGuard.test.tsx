// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { App as AntApp } from 'antd'
import type { ReactNode } from 'react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * ⚠️ **basename 双前缀守卫**（2026-11-10 页面端实测缺陷）。
 *
 * 真实 Router 用 `createBrowserRouter(..., { basename: '/admin' })`，
 * 故 `useNavigate()` / `<Navigate to>` 的目标是 **basename 相对**的：
 *   · 正确：`navigate('/login')`           → 浏览器 /admin/login           ✅
 *   · 错误：`navigate('/admin/login')`     → 浏览器 /admin/admin/login     ❌ 404
 *
 * 之前 `loginRedirect.test.tsx` 用**无 basename** 的 MemoryRouter + `toContain('/login')`
 * 弱断言：写错时得到 `/admin/login`，`toContain('/login')` 恰好成立 ⇒ **假绿**，
 * 永远抓不到「多拼一次 /admin」这个真实缺陷。
 *
 * 本文件刻意用 `MemoryRouter basename='/admin'`（唯一能复现双前缀的姿势）——
 * 声明式路由即可，不依赖数据路由，规避 jsdom 的 AbortSignal 跨 realm 问题。
 * 断言用 `location.pathname`（**已剥掉 basename**）：正确目标应为 `/login`，
 * 一旦有人写 `${APP_BASE_PATH}/login`，这里就会变成 `/admin/login`（= 浏览器双前缀）而变红。
 */

vi.mock('@/api/adminApi', () => ({
  platformLogin: vi.fn(),
  getPlatformMe: vi.fn(),
}))

import { AdminLayout } from '@/layout/AdminLayout'
import { RequireRole } from '@/auth/RequireRole'
import { NoPermissionPage } from '@/pages/NoPermissionPage'
import { SessionProvider } from '@/auth/session'
import { storeSession } from '@/auth/tokenStorage'

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

beforeEach(() => {
  window.sessionStorage.clear()
})

/** 读取已剥掉 basename 的 pathname + search，用于精确断言跳转目标。 */
function LocProbe(): JSX.Element {
  const loc = useLocation()
  return <span data-testid="loc">{`${loc.pathname}${loc.search}`}</span>
}

/** 带真实 basename 的路由外壳：任何未命中路由也落到探针，保证跳转后能读到 pathname。 */
function Shell({ children, initial = '/mcps' }: { children: ReactNode; initial?: string }): JSX.Element {
  return (
    <MemoryRouter basename="/admin" initialEntries={[`/admin${initial}`]}>
      <AntApp>
        <SessionProvider>
          <Routes>
            <Route path="/mcps" element={children} />
            <Route path="/login" element={<LocProbe />} />
            <Route path="/tenants" element={<LocProbe />} />
            <Route path="*" element={<LocProbe />} />
          </Routes>
        </SessionProvider>
      </AntApp>
    </MemoryRouter>
  )
}

/** 写入一个「即将过期」的平台会话，触发 AdminLayout 的告警条与「重新登录」按钮。 */
function storeExpiringSession(): void {
  storeSession({
    accessToken: 'tok',
    expiresAt: Date.now() + 30_000,
    tenantId: null,
    principalId: 'u-1',
    roles: ['PLATFORM_SUPER_ADMIN'],
  })
}

describe('basename 双前缀守卫 · navigate 目标必须相对 /admin', () => {
  it('⛔ 「重新登录」落到 /login（不是 /admin/login），且保留 reason/next', async () => {
    storeExpiringSession()
    render(
      <Shell>
        <AdminLayout />
      </Shell>,
    )
    const btn = await screen.findByRole('button', { name: '重新登录' })
    fireEvent.click(btn)

    await waitFor(() => {
      const full = screen.getByTestId('loc').textContent ?? ''
      // pathname 已剥 basename：正确 = /login?...；双前缀缺陷会变成 /admin/login?...
      expect(full.startsWith('/login?')).toBe(true)
      expect(full).toContain('reason=expired')
      expect(full).toContain('next=')
    })
    // 明确拒绝双前缀（浏览器真实表现 /admin/admin/login）
    const full = screen.getByTestId('loc').textContent ?? ''
    expect(full.startsWith('/admin/')).toBe(false)
  })

  it('⛔ 「退出登录」落到 /login（不是 /admin/login）', async () => {
    storeSession({
      accessToken: 'tok',
      expiresAt: Date.now() + 900_000,
      tenantId: null,
      principalId: 'u-1',
      roles: ['PLATFORM_SUPER_ADMIN'],
    })
    render(
      <Shell>
        <AdminLayout />
      </Shell>,
    )
    // 展开右上角头像下拉（antd Dropdown 默认 hover 触发）→ 退出登录
    const trigger = document.querySelector('.ant-dropdown-trigger') as HTMLElement
    expect(trigger).toBeTruthy()
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseMove(trigger)
    const logout = await screen.findByText('退出登录')
    fireEvent.click(logout)

    await waitFor(() => {
      const full = screen.getByTestId('loc').textContent ?? ''
      expect(full).toBe('/login')
    })
  })

  it('⛔ RequireRole 的「返回租户管理」落到 /tenants（不是 /admin/tenants）', async () => {
    // 用户无所需角色 → 渲染 403 Result 及其返回按钮
    storeSession({
      accessToken: 'tok',
      expiresAt: Date.now() + 900_000,
      tenantId: null,
      principalId: 'u-1',
      roles: ['PLATFORM_SUPPORT'],
    })
    render(
      <MemoryRouter basename="/admin" initialEntries={['/admin/mcps']}>
        <SessionProvider>
          <Routes>
            <Route
              path="/mcps"
              element={
                <RequireRole roles={['PLATFORM_SUPER_ADMIN']}>
                  <span>不该出现</span>
                </RequireRole>
              }
            />
            <Route path="/tenants" element={<LocProbe />} />
            <Route path="*" element={<LocProbe />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )
    // 角色回补可能异步覆盖 roles，等 403 按钮出现再点
    const back = await screen.findByRole('button', { name: '返回租户管理' })
    fireEvent.click(back)
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/tenants'))
    expect(screen.getByTestId('loc').textContent).not.toBe('/admin/tenants')
  })

  it('⛔ NoPermissionPage 的「返回租户管理」落到 /tenants（不是 /admin/tenants）', async () => {
    render(
      <MemoryRouter basename="/admin" initialEntries={['/admin/no-permission?reason=x']}>
        <Routes>
          <Route path="/no-permission" element={<NoPermissionPage />} />
          <Route path="/tenants" element={<LocProbe />} />
          <Route path="*" element={<LocProbe />} />
        </Routes>
      </MemoryRouter>,
    )
    const back = await screen.findByRole('button', { name: '返回租户管理' })
    fireEvent.click(back)
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/tenants'))
    expect(screen.getByTestId('loc').textContent).not.toBe('/admin/tenants')
  })

  it('✅ 原生跳转（client.ts redirectLogin）仍用完整 /admin 前缀，不受本规则影响', () => {
    // 该路径用 window.location.href（原生导航，不经过 Router basename），必须带 /admin。
    // 这里以源码断言锁定，防止有人「顺手」把 APP_BASE_PATH 从原生跳转里也删掉。
    const client = readFileSync(resolve(process.cwd(), 'src/api/client.ts'), 'utf-8')
    expect(client).toMatch(/window\.location\.href = `\$\{APP_BASE_PATH\}\/login/)
  })
})
