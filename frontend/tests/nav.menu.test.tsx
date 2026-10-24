/**
 * 侧栏菜单完整性 / 角色回补测试（B1 补强 · 2026-10-23）
 *
 * ⚠️ 本文件的存在理由：**侧栏此前没有任何行为测试**。
 *    全部 349 项测试只校验了 `ROLE_PERMISSIONS` 这张**静态表**的内容，
 *    从未渲染过 AppShell——于是「管理员登录后只看到辅导台」这个缺陷
 *    可以在**所有测试全绿**的情况下长期存在。
 *
 * 🐛 本次修复的根因（实证）：
 *    ① 后端旧版本登录响应**不含 roles** → 前端 roles 恒空
 *    ② `hasRoleAccess([], area)` 恒 false → 三个管理分面被整组隐藏
 *    ③ 会话持久化在 sessionStorage → 登录早于 B1 的会话**永远**修不好
 *    ④ 顶栏回落文案写死「学生 / 家长 / 老师」→ **谎报身份**且无任何提示
 *
 * ✅ 本文件锁死三件事：
 *    · TENANT_ADMIN 必须看到**完整**菜单（4 分面 16 项）；
 *    · 角色缺失时必须**自愈**（回补 `/api/auth/roles`，不必重新登录）；
 *    · 角色确实拿不到时必须**如实说明**，不得谎称用户是学生。
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { AppShell } from '@/layouts/AppShell'
import { SessionProvider } from '@/auth/session'

/** 完整菜单：4 个分面 + 16 个入口（缺一即视为「菜单不完整」） */
const FULL_MENU_GROUPS = ['辅导台', '知识库治理', '运行治理', '平台配置与运维']
const FULL_MENU_ITEMS = [
  // 辅导台
  '工作台', '发起辅导', '检索记录', '知识检索', '学习画像', '报表中心',
  // 知识库治理
  '语料管理',
  // 运行治理
  '审批台', '未知操作处置', '审计查询',
  // 平台配置与运维
  '配置总览', '运行与容量', '工具与审批', '合规与零保留', 'Agent 配置', '系统健康',
]

/** 写入一个会话；`roles` 省略 = 旧会话（无角色字段） */
function seedSession(roles?: string[]): void {
  const record: Record<string, unknown> = {
    accessToken: 'tok-admin',
    expiresAt: Date.now() + 900_000,
    tenantId: 'f94ae0d9-289f-4c96-98ed-634f8f4fcf02',
  }
  if (roles !== undefined) record['roles'] = roles
  window.sessionStorage.setItem('agent.session', JSON.stringify(record))
}

function renderShell(): void {
  render(
    <MemoryRouter initialEntries={['/']}>
      <SessionProvider>
        <Routes>
          <Route path="/" element={<AppShell />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** 构造一个成功的 /api/auth/roles 响应 */
function rolesResponse(roles: string[]): Response {
  return {
    ok: true,
    status: 200,
    text: async () => JSON.stringify({ roles }),
  } as unknown as Response
}

describe('侧栏菜单完整性', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    window.sessionStorage.clear()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('⛔ TENANT_ADMIN 必须看到完整菜单（4 分面 16 项）', () => {
    seedSession(['TENANT_ADMIN'])
    renderShell()

    for (const group of FULL_MENU_GROUPS) {
      expect(screen.getByText(group)).toBeInTheDocument()
    }
    for (const item of FULL_MENU_ITEMS) {
      expect(screen.getByRole('link', { name: item })).toBeInTheDocument()
    }
    // 恰好 16 项，不多不少（多出重复入口同样是「菜单不完整」）
    // ⚠️ 2026-10-24：作用域收敛到**导航地标**。壳内现有两类 link：
    //    ① 侧栏导航项（16 个，本断言的对象）；
    //    ② 「跳到主内容」skip link（键盘可达性基线，不是导航项）。
    //    旧写法用全局 getAllByRole('link') 会把 skip link 一并数进去，
    //    于是"加一个无障碍跳转链接"会被误判成"菜单多了入口"。
    //    按地标收窄后，两类关注点各归其位，断言力度不减。
    const nav = within(screen.getByRole('navigation'))
    expect(nav.getAllByRole('link')).toHaveLength(FULL_MENU_ITEMS.length)
  })

  it('TENANT_ADMIN 的顶栏应显示真实角色名', () => {
    seedSession(['TENANT_ADMIN'])
    renderShell()
    expect(screen.getByText(/TENANT_ADMIN/)).toBeInTheDocument()
  })

  it('STUDENT 只看到辅导台，且不得出现「角色未回传」提示', () => {
    seedSession(['STUDENT'])
    renderShell()

    expect(screen.getByText('辅导台')).toBeInTheDocument()
    expect(screen.queryByText('知识库治理')).not.toBeInTheDocument()
    expect(screen.queryByText('运行治理')).not.toBeInTheDocument()
    expect(screen.queryByText('平台配置与运维')).not.toBeInTheDocument()
    expect(screen.queryByTestId('roles-unknown-notice')).not.toBeInTheDocument()
  })
})

describe('角色回补（自愈，不必重新登录）', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    window.sessionStorage.clear()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('⛔ 旧会话（无角色）应回补角色并显示完整菜单', async () => {
    seedSession() // 无 roles 字段 = 登录早于 B1 的会话
    fetchMock.mockResolvedValue(rolesResponse(['TENANT_ADMIN']))
    renderShell()

    await waitFor(() => {
      expect(screen.getByRole('link', { name: '审批台' })).toBeInTheDocument()
    })
    for (const item of FULL_MENU_ITEMS) {
      expect(screen.getByRole('link', { name: item })).toBeInTheDocument()
    }
    // 回补成功后不应再显示「角色未回传」提示
    expect(screen.queryByTestId('roles-unknown-notice')).not.toBeInTheDocument()
  })

  it('回补结果应持久化，刷新后无需再次回补', async () => {
    seedSession()
    fetchMock.mockResolvedValue(rolesResponse(['TENANT_ADMIN']))
    renderShell()

    await waitFor(() => {
      expect(screen.getByRole('link', { name: '审批台' })).toBeInTheDocument()
    })
    const stored = JSON.parse(window.sessionStorage.getItem('agent.session') ?? '{}')
    expect(stored.roles).toEqual(['TENANT_ADMIN'])
  })

  it('同一令牌只回补一次（不得无限请求）', async () => {
    seedSession()
    fetchMock.mockResolvedValue(rolesResponse([])) // 空数组 → 仍会重渲染
    renderShell()

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled()
    })
    const callsAfterFirst = fetchMock.mock.calls.length
    // 再等几拍，确认没有形成循环
    await new Promise((resolve) => setTimeout(resolve, 60))
    expect(fetchMock.mock.calls.length).toBe(callsAfterFirst)
  })
})

describe('角色拿不到时必须如实说明（不得谎报身份）', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    window.sessionStorage.clear()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('⛔ 回补失败时不得把管理员说成「学生 / 家长 / 老师」', async () => {
    seedSession()
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => JSON.stringify({ error: 'internal' }),
    } as unknown as Response)
    renderShell()

    await waitFor(() => {
      expect(screen.getByTestId('roles-unknown-notice')).toBeInTheDocument()
    })
    // ⛔ 谎报身份是本缺陷最有害的一面：管理员会以为平台没有管理功能
    // ⚠️ 断言**只针对顶栏身份条**：「学生 / 家长 / 老师」作为辅导台分面的
    //    受众小字提示是合法的，不能一并禁掉。
    expect(screen.getByTestId('header-identity')).not.toHaveTextContent('学生 / 家长 / 老师')
    expect(screen.getByTestId('header-identity')).toHaveTextContent('角色未回传')
    expect(screen.getByTestId('roles-unknown-notice')).toHaveTextContent('角色未回传')
  })

  it('角色未知时管理菜单仍须隐藏（fail-closed）', async () => {
    seedSession()
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => JSON.stringify({ error: 'internal' }),
    } as unknown as Response)
    renderShell()

    await waitFor(() => {
      expect(screen.getByTestId('roles-unknown-notice')).toBeInTheDocument()
    })
    expect(screen.queryByRole('link', { name: '审批台' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '配置总览' })).not.toBeInTheDocument()
    // 学生侧入口仍在（辅导台不挂 area）
    expect(screen.getByRole('link', { name: '工作台' })).toBeInTheDocument()
  })

  it('「重试获取角色」按钮可再次触发回补', async () => {
    seedSession()
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => JSON.stringify({ error: 'internal' }),
    } as unknown as Response)
    renderShell()

    await waitFor(() => {
      expect(screen.getByTestId('roles-unknown-notice')).toBeInTheDocument()
    })
    const before = fetchMock.mock.calls.length
    screen.getByRole('button', { name: '重试获取角色' }).click()
    await waitFor(() => {
      expect(fetchMock.mock.calls.length).toBeGreaterThan(before)
    })
  })
})
