/**
 * 移动端导航抽屉的行为回归（2026-10-24 UX 评审 P1-F）
 *
 * ⚠️ 这个文件的存在理由：**旧形态在小屏上根本没法用**，而此前
 *    没有任何一条测试覆盖过「≤767px 时侧栏怎么办」——
 *    侧栏此前是一条横向滚动条：16 个入口总宽 1918px，
 *    390px 视口首屏只露出 3.5 项，第 4 项被裁在「知」字中间。
 *
 * 本文件锁死抽屉的四条行为约定：
 *   1. 菜单按钮在 DOM 里且带 aria-expanded / aria-controls；
 *   2. 打开时焦点进入抽屉（键盘用户不能"不知道自己在哪"）；
 *   3. Esc 能关闭，关闭后焦点回到菜单按钮；
 *   4. 路由变化自动关闭（否则遮罩会盖住刚跳过去的页面）。
 *
 * ⛔ 另有一条不可回退的契约：**抽屉化不得让 16 个导航项消失**
 *    （nav.menu.test.tsx 已按可访问名逐项断言，这里再断言一次
 *    「抽屉关闭状态下它们仍在导航地标内」）。
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { AppShell } from '@/layouts/AppShell'
import { SessionProvider } from '@/auth/session'

function seedSession(): void {
  window.sessionStorage.setItem(
    'agent.session',
    JSON.stringify({
      accessToken: 'tok-admin',
      expiresAt: Date.now() + 900_000,
      tenantId: 'f94ae0d9-289f-4c96-98ed-634f8f4fcf02',
      roles: ['TENANT_ADMIN'],
    }),
  )
}

function renderShell(): void {
  render(
    <MemoryRouter initialEntries={['/']}>
      <SessionProvider>
        <Routes>
          <Route path="/" element={<AppShell />}>
            <Route index element={<div>工作台内容</div>} />
            <Route path="runs" element={<div>检索记录内容</div>} />
          </Route>
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

/**
 * 抽屉开关按钮。
 *
 * ⚠️ 它的可访问名会随状态在「打开导航 / 关闭导航」之间变化，
 *    所以这里按 class 取元素，而不是按可访问名取——
 *    否则断言"打开后 aria-expanded=true"时，名字已经变了。
 */
function navToggle(): HTMLElement {
  const el = document.querySelector('.nav-toggle')
  if (el === null) throw new Error('未找到抽屉开关按钮')
  return el as HTMLElement
}

beforeEach(() => {
  window.sessionStorage.clear()
  window.localStorage.clear()
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ items: [], nextCursor: null }),
    } as unknown as Response),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('移动端导航抽屉', () => {
  it('菜单按钮存在且带展开状态与目标关联', () => {
    seedSession()
    renderShell()

    const toggle = screen.getByRole('button', { name: '打开导航' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveAttribute('aria-controls', 'main-nav')
    expect(document.getElementById('main-nav')).not.toBeNull()
  })

  it('打开时遮罩出现、焦点进入抽屉的第一个入口', async () => {
    seedSession()
    const user = userEvent.setup()
    renderShell()

    await user.click(screen.getByRole('button', { name: '打开导航' }))

    expect(navToggle()).toHaveAttribute('aria-expanded', 'true')
    // 遮罩存在
    expect(document.querySelector('.nav-scrim')).not.toBeNull()

    // ⛔ 焦点必须落在抽屉内，否则键盘用户不知道自己在哪
    await waitFor(() => {
      const focused = document.activeElement
      expect(focused).not.toBeNull()
      expect(focused?.closest('nav')).not.toBeNull()
    })
    expect((document.activeElement as HTMLElement).textContent).toContain('工作台')
  })

  it('Esc 关闭抽屉，且焦点回到菜单按钮', async () => {
    seedSession()
    const user = userEvent.setup()
    renderShell()

    const toggle = screen.getByRole('button', { name: '打开导航' })
    await user.click(toggle)
    await waitFor(() => {
      expect(document.activeElement?.closest('nav')).not.toBeNull()
    })

    await user.keyboard('{Escape}')

    await waitFor(() => {
      expect(navToggle()).toHaveAttribute('aria-expanded', 'false')
    })
    expect(document.activeElement).toBe(navToggle())
  })

  it('路由变化时自动收起（否则遮罩会盖住新页面）', async () => {
    seedSession()
    const user = userEvent.setup()
    renderShell()

    await user.click(screen.getByRole('button', { name: '打开导航' }))
    expect(navToggle()).toHaveAttribute('aria-expanded', 'true')

    // 点导航项 → 路由变化 → 抽屉应自动收起
    const nav = within(screen.getByRole('navigation'))
    await user.click(nav.getByRole('link', { name: '检索记录' }))

    await waitFor(() => {
      expect(navToggle()).toHaveAttribute('aria-expanded', 'false')
    })
  })

  it('⛔ 抽屉化不得让 16 个导航项消失', () => {
    seedSession()
    renderShell()

    const nav = within(screen.getByRole('navigation'))
    expect(nav.getAllByRole('link')).toHaveLength(16)
    for (const label of ['工作台', '发起辅导', '检索记录', '审批台', '系统健康']) {
      expect(nav.getByRole('link', { name: label })).toBeInTheDocument()
    }
  })

  it('折叠按钮仍保留（桌面用），且与菜单按钮是两个不同的控件', () => {
    seedSession()
    renderShell()

    expect(screen.getByRole('button', { name: '折叠导航' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '打开导航' })).toBeInTheDocument()
  })
})
