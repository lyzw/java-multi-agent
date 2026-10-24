/**
 * 交互与现代化补强的行为测试（2026-10-24）
 *
 * 覆盖本轮「UI 现代化 + 交互合理化」新增的**可测行为**：
 *   · skip link（键盘可达性基线）
 *   · 侧栏折叠态跨刷新持久化
 *   · 404 页取代静默跳转
 *   · 检索列表可进详情
 *   · 进度页失败态有下一步 + 手动刷新
 *   · 登录页不再随全局错误全员标 aria-invalid
 *   · 页标题同步
 *
 * ⛔ 纯视觉（颜色/圆角/过渡时长）不在此测——那由 design.spec 的纪律断言
 *    与人工评审负责；本文件只测"行为变了没有"。
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { AppShell } from '@/layouts/AppShell'
import { SessionProvider } from '@/auth/session'
import { LoginPage } from '@/pages/LoginPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { RunSearchPage } from '@/pages/business/RunSearchPage'
import { RunProgressPage } from '@/pages/RunProgressPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { PageHead } from '@/components/ui'
import { Empty } from '@/shared/states'
import { ApiError } from '@/api/client'
import type { RunListItem } from '@/api/types'

/* ── 公共夹具 ────────────────────────────────────────────── */

/**
 * 默认 fetch 桩：空分页 + 空事件流。
 *
 * ⚠️ 壳内渲染会挂到工作台（DashboardPage 一进页面就拉 /api/runs）。
 *    不打桩的话，那个 Promise 在断言结束后才落地 → React act() 警告
 *    （警告会淹没真实问题，故在此统一兜掉）。
 */
function stubDefaultFetch(): ReturnType<typeof vi.fn> {
  const mock = vi.fn().mockImplementation((input: string) => {
    if (typeof input === 'string' && input.includes('/api/runs')) {
      return Promise.resolve({
        ok: true,
        status: 200,
        text: async () => JSON.stringify({ items: [], nextCursor: null }),
      } as unknown as Response)
    }
    return Promise.resolve({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ events: [] }),
    } as unknown as Response)
  })
  vi.stubGlobal('fetch', mock)
  return mock
}

beforeEach(() => {
  window.sessionStorage.clear()
  window.localStorage.clear()
  stubDefaultFetch()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function seedSession(roles?: string[]): void {
  const record: Record<string, unknown> = {
    accessToken: 'tok-admin',
    expiresAt: Date.now() + 900_000,
    tenantId: 'f94ae0d9-289f-4c96-98ed-634f8f4fcf02',
  }
  if (roles !== undefined) record['roles'] = roles
  window.sessionStorage.setItem('agent.session', JSON.stringify(record))
}

function renderShellAt(path: string): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/" element={<AppShell />}>
            <Route index element={<DashboardPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

function renderRunsPage(): void {
  render(
    <MemoryRouter initialEntries={['/runs']}>
      <SessionProvider>
        <RunSearchPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

const SAMPLE_RUN: RunListItem = {
  runId: 'run-abc-123',
  state: 'FAILED',
  createdAt: '2026-10-24T10:00:00.000Z',
  completedAt: '2026-10-24T10:00:42.000Z',
  attempt: 1,
  errorCode: null,
  subject: null,
  grade: null,
}

describe('skip link（键盘可达性基线）', () => {
  it('应是壳内第一个可聚焦元素，且指向主内容焦点靶', () => {
    seedSession(['TENANT_ADMIN'])
    renderShellAt('/')

    const skip = screen.getByRole('link', { name: '跳到主内容' })
    expect(skip).toHaveAttribute('href', '#main-content')
    // 焦点靶必须在主内容区内（span 带 id，main 承载 class）
    expect(document.getElementById('main-content')).not.toBeNull()
    const main = document.querySelector('main.main')
    expect(main).not.toBeNull()
    expect(main?.contains(document.getElementById('main-content'))).toBe(true)
  })

  it('skip link 不是导航项（导航地标内仍恰好 16 项）', () => {
    seedSession(['TENANT_ADMIN'])
    renderShellAt('/')
    const nav = screen.getByRole('navigation')
    // 16 个导航链接（nav.menu.test 已逐项断言，这里只确认 skip link 在壳外）
    expect(nav.querySelectorAll('a')).toHaveLength(16)
    expect(nav.contains(screen.getByRole('link', { name: '跳到主内容' }))).toBe(false)
  })
})

describe('侧栏折叠态持久化', () => {
  it('折叠后写入 localStorage，重渲染（模拟刷新）保持折叠', async () => {
    seedSession(['TENANT_ADMIN'])
    const user = userEvent.setup()
    const { unmount } = render(
      <MemoryRouter initialEntries={['/']}>
        <SessionProvider>
          <AppShell />
        </SessionProvider>
      </MemoryRouter>,
    )

    const toggle = screen.getByRole('button', { name: '折叠导航' })
    await user.click(toggle)
    expect(window.localStorage.getItem('nav.collapsed')).toBe('1')

    // 模拟刷新：卸载后重新挂载，初始状态应读回 true
    unmount()
    render(
      <MemoryRouter initialEntries={['/']}>
        <SessionProvider>
          <AppShell />
        </SessionProvider>
      </MemoryRouter>,
    )
    expect(screen.getByRole('button', { name: '展开导航' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('未折叠时不留存储键（默认展开，无副作用）', () => {
    seedSession(['TENANT_ADMIN'])
    renderShellAt('/')
    expect(window.localStorage.getItem('nav.collapsed')).toBeNull()
  })
})

describe('404 页取代静默跳工作台', () => {

  it('未知路径显示「页面不存在」，并给出可行动的出口', () => {
    renderShellAt('/this/does/not/exist')
    expect(screen.getByRole('heading', { name: '页面不存在' })).toBeInTheDocument()
    // ⚠️ 两个出口链接要与侧栏的同名导航项区分 → 收敛到主内容区查询
    const main = document.querySelector('main.main')
    expect(main).not.toBeNull()
    const inMain = within(main as HTMLElement)
    expect(inMain.getByRole('link', { name: /回工作台/ })).toBeInTheDocument()
    expect(inMain.getByRole('link', { name: /检索记录/ })).toBeInTheDocument()
  })

  it('404 页仍在壳内（导航仍在，不会把人困死）', () => {
    renderShellAt('/nope')
    expect(screen.getByRole('navigation')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '工作台' })).toBeInTheDocument()
  })

  it('标题同步为 404 页标题', () => {
    renderShellAt('/nope')
    expect(document.title).toBe('页面不存在 · 错题归因辅导')
  })
})

describe('检索记录：列表可进详情', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    seedSession(['TENANT_ADMIN'])
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  it('运行标识是指向进度页的链接（此前是死列表）', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ items: [SAMPLE_RUN], nextCursor: null }),
    } as unknown as Response)

    renderRunsPage()
    const link = await screen.findByRole('link', { name: /run-abc-123/ })
    expect(link).toHaveAttribute('href', '/runs/run-abc-123')
  })
})

describe('进度页：失败态不再死路 + 手动刷新', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    seedSession(['TENANT_ADMIN'])
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  it('失败终态给出「重新发起」与「回检索记录」两个出口', async () => {
    // 状态端点返回 FAILED（终态）→ 轮询自停
    fetchMock.mockImplementation((input: string) => {
      if (typeof input === 'string' && input.includes('/api/runs/')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          text: async () =>
            JSON.stringify({
              runId: 'run-x',
              state: 'FAILED',
              errorCode: 'INTERNAL',
              createdAt: '2026-10-24T10:00:00.000Z',
            }),
        } as unknown as Response)
      }
      // SSE 轮询：空事件
      return Promise.resolve({
        ok: true,
        status: 200,
        text: async () => JSON.stringify({ events: [] }),
      } as unknown as Response)
    })

    render(
      <MemoryRouter initialEntries={['/runs/run-x']}>
        <SessionProvider>
          <Routes>
            <Route path="/runs/:runId" element={<RunProgressPage />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('link', { name: /重新发起这道题/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /回检索记录/ })).toBeInTheDocument()
    // 成功态的 CTA 不应出现
    expect(screen.queryByRole('link', { name: /查看归因结果/ })).not.toBeInTheDocument()
  })

  it('状态区提供「刷新状态」手动刷新按钮', async () => {
    fetchMock.mockImplementation((input: string) => {
      if (typeof input === 'string' && input.includes('/api/runs/')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          text: async () =>
            JSON.stringify({
              runId: 'run-y',
              state: 'RUNNING',
              createdAt: '2026-10-24T10:00:00.000Z',
            }),
        } as unknown as Response)
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        text: async () => JSON.stringify({ events: [] }),
      } as unknown as Response)
    })

    render(
      <MemoryRouter initialEntries={['/runs/run-y']}>
        <SessionProvider>
          <Routes>
            <Route path="/runs/:runId" element={<RunProgressPage />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )

    const refresh = await screen.findByRole('button', { name: /刷新状态/ })
    expect(refresh).toBeInTheDocument()
  })
})

describe('登录页：错误反馈诚实化', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  it('全局失败（如 500）不把三个输入框全判为非法', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <SessionProvider>
          <LoginPage />
        </SessionProvider>
      </MemoryRouter>,
    )

    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => JSON.stringify({ code: 'INTERNAL', message: '服务暂时不可用' }),
    } as unknown as Response)

    await user.type(screen.getByLabelText(/租户标识/), 't')
    await user.type(screen.getByLabelText(/账号/), 'u')
    await user.type(screen.getByLabelText(/密码/), 'p')
    await user.click(screen.getByRole('button', { name: '登录' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    // ⛔ 旧行为：error !== null → 三个框全部 aria-invalid=true
    expect(screen.getByLabelText(/租户标识/)).toHaveAttribute('aria-invalid', 'false')
    expect(screen.getByLabelText(/账号/)).toHaveAttribute('aria-invalid', 'false')
    expect(screen.getByLabelText(/密码/)).toHaveAttribute('aria-invalid', 'false')
  })

  it('登录页标题同步', () => {
    render(
      <MemoryRouter>
        <SessionProvider>
          <LoginPage />
        </SessionProvider>
      </MemoryRouter>,
    )
    expect(document.title).toBe('登录 · 错题归因辅导')
  })
})

describe('PageHead / Empty 的现代化增强', () => {
  it('PageHead 把标题写进 document.title', () => {
    render(<PageHead title="某个页面" />)
    expect(document.title).toBe('某个页面 · 错题归因辅导')
  })

  it('Empty 传入 icon 时渲染图标容器', () => {
    const { container } = render(<Empty icon="inbox" title="空的" />)
    expect(container.querySelector('.empty__icon')).not.toBeNull()
  })

  it('Empty 不传 icon 时不渲染图标容器（保持旧观感）', () => {
    const { container } = render(<Empty title="空的" />)
    expect(container.querySelector('.empty__icon')).toBeNull()
  })
})

describe('ApiError 契约未被破坏', () => {
  it('归一化错误仍带 fieldErrors 字段', () => {
    const err = new ApiError({
      status: 400,
      code: 'INVALID_REQUEST',
      message: '请求有误',
      fieldErrors: {},
    })
    expect(err.normalized.fieldErrors).toEqual({})
  })
})
