// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  platformLogin: vi.fn(),
  getPlatformMe: vi.fn(),
}))
import { getPlatformMe, platformLogin } from '@/api/adminApi'

import { LoginPage } from '@/pages/LoginPage'
import { RequireAuth } from '@/auth/RequireAuth'
import { SessionProvider } from '@/auth/session'
import { STORAGE_KEY } from '@/config/constants'

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
  // 角色回补走 GET /api/platform/v1/auth/me（平台身份域）
  vi.mocked(getPlatformMe).mockResolvedValue({
    userId: 'u-1',
    roles: ['PLATFORM_SUPER_ADMIN'],
    authzVersion: 1,
    mfaVerified: false,
  } as never)
})

/** 平台登录响应（POST /api/platform/v1/auth/login · TokenResponse）。
 *  ⛔ 无 tenantId：平台主体不属于任何租户。 */
const LOGIN_OK = {
  accessToken: 'tok',
  tokenType: 'Bearer',
  expiresIn: 900,
  userId: 'u-1',
  roles: ['PLATFORM_SUPER_ADMIN'],
} as never

/** 读取当前位置的探针。 */
function LocationProbe(): JSX.Element {
  const location = useLocation()
  return <span data-testid="loc">{location.pathname}</span>
}

/** 读取「来源路径」探针（`state.from`）。 */
function FromProbe(): JSX.Element {
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? ''
  return <span data-testid="from">{from}</span>
}

/* ══════════════════════════════════════════════════════════════
   未登录跳转：RequireAuth → 登录页
   ══════════════════════════════════════════════════════════════ */

describe('未登录跳转 · basename 不得被再拼一次', () => {
  /**
   * ⚠️ 必须有 `/login` 路由：跳过去之后探针要**继续挂着**才能读出 pathname ——
   * 没有该路由时组件直接卸载，断言会一直等不到。
   */
  function mountProtected() {
    render(
      <MemoryRouter initialEntries={['/tenants']}>
        <SessionProvider>
          <Routes>
            <Route path="/login" element={<><LocationProbe /><FromProbe /></>} />
            <Route
              path="/tenants"
              element={
                <RequireAuth>
                  <LocationProbe />
                </RequireAuth>
              }
            />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )
  }

  it('⛔ RequireAuth 跳登录页必须是 basename **相对**路径（/login，不是 /admin/login）', async () => {
    mountProtected()
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/login'))
    // ⛔ 双前缀 ⇒ 实际落到 /admin/admin/login ⇒ 404，用户根本回不到登录页
    expect(screen.getByTestId('loc').textContent).not.toBe('/admin/login')
  })

  it('⛔ 来源路径必须剥掉 basename（否则登录后回跳到 /admin/admin/tenants）', async () => {
    mountProtected()
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/login'))
    // ⚠️ `state.from` 取自 `location.pathname` —— 必须是 basename **相对**路径，
    //    否则登录后 `navigate(from)` 会再拼一次 basename ⇒ /admin/admin/tenants ⇒ 404
    expect(screen.getByTestId('from').textContent).toBe('/tenants')
  })
})

/* ══════════════════════════════════════════════════════════════
   登录回跳：LoginPage → 租户管理 / 来源页
   ══════════════════════════════════════════════════════════════ */

describe('登录回跳 · basename 不得被再拼一次', () => {
  /**
   * ⚠️ 用经典 `MemoryRouter`（不用数据路由）：本仓库的 jsdom 环境与
   *    react-router v7 的**数据路由**不兼容（导航会被 AbortSignal 跨 realm 报错打断）。
   *
   * ⚠️ 但这**不影响本组断言的有效性**：判据是「传给 navigate 的目标里
   *    **不能含 `/admin` 前缀**」—— 写错时目标就是 `/admin/tenants`，
   *    在任何路由下都能被断言逮住。
   */
  function mountLogin(initialState?: { from?: string }) {
    render(
      <MemoryRouter
        initialEntries={[
          initialState ? { pathname: '/login', state: initialState } : '/login',
        ]}
      >
        <AntApp>
          <SessionProvider>
            <Routes>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/tenants" element={<><LocationProbe /><span>租户注册表</span></>} />
              <Route path="/agents" element={<><LocationProbe /><span>Agent 列表</span></>} />
            </Routes>
          </SessionProvider>
        </AntApp>
      </MemoryRouter>,
    )
  }

  async function submitLogin(): Promise<void> {
    // 平台登录：用户名 + 密码，⛔ 无「租户 ID」输入（身份域分离）
    fireEvent.change(screen.getByLabelText(/用户名/), { target: { value: 'u-1' } })
    fireEvent.change(screen.getByLabelText(/密码/), { target: { value: 'pw' } })
    // ⚠️ jsdom 不会因点击 submit 按钮而派发表单提交 ⇒ 直接派发 submit 事件
    const form = document.querySelector('form')
    if (!form) throw new Error('登录表单未挂载')
    fireEvent.submit(form)
  }

  it('✅ 平台登录成功 → 落到租户管理（⛔ 不是 /admin/tenants 双前缀）', async () => {
    vi.mocked(platformLogin).mockResolvedValue(LOGIN_OK)
    mountLogin()

    await screen.findByLabelText(/用户名/)
    await submitLogin()

    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/tenants'))
    // ⛔ 回跳目标再拼一次 basename ⇒ /admin/admin/tenants ⇒ 404
    expect(screen.getByTestId('loc').textContent).not.toBe('/admin/tenants')
    // 会话确实落库了（平台会话独立键）
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeTruthy()
    const stored = JSON.parse(window.sessionStorage.getItem(STORAGE_KEY) ?? '{}') as {
      tenantId?: string | null
      roles?: string[]
    }
    // ⛔ 平台主体不属于任何租户
    expect(stored.tenantId).toBeNull()
    expect(stored.roles).toEqual(['PLATFORM_SUPER_ADMIN'])
  })

  it('✅ 登录成功且带来源路径 → 回跳到来源页（⛔ 不是双前缀）', async () => {
    vi.mocked(platformLogin).mockResolvedValue(LOGIN_OK)
    // ⚠️ `state.from` 是 basename 相对路径（RequireAuth 取自 location.pathname）
    mountLogin({ from: '/agents' })

    await screen.findByLabelText(/用户名/)
    await submitLogin()

    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/agents'))
    expect(screen.getByTestId('loc').textContent).not.toBe('/admin/agents')
    expect(screen.getByText('Agent 列表')).toBeTruthy()
  })

  it('⛔ 登录页不提供「租户 ID」输入（平台身份与租户身份分离）', async () => {
    mountLogin()
    await screen.findByLabelText(/用户名/)
    expect(screen.queryByLabelText(/租户 ID/)).not.toBeInTheDocument()
  })
})
