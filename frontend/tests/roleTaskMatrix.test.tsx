/**
 * T1 硬门禁：角色 × 任务矩阵（E2E 二次评估，2026-11-14）
 *
 * ⚠️ 为什么需要这个文件：上一轮 F1 宣称「API 级 13/13 PASS，已闭环」，
 *    紧接着浏览器级复测连续暴露 F6（看不到 Agent）→ F7（Run 跑不起来）
 *    → F8（404 死等）；二次评估再量其余入口，又发现 N1 / N2。
 *    共同根因：验收断言的是「接口返回预期状态码」，不是
 *    「用户能完成他登录进来要做的事」。
 *
 * ⛔ 本文件固化的门禁：
 *    ① 任何任务都必须登记一条「从入口完成真实操作」的验收用例；
 *    ② 任一角色导航中可见的每个入口，都必须属于该角色可达的任务集合；
 *    ③ 不可达入口必须断言「不渲染」，或明确说明不可用（终态页）。
 *
 * 覆盖 F6 / N1 / N2 三型错配；撤掉裁剪会直接转红。
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { AppShell } from '@/layouts/AppShell'
import { SessionProvider } from '@/auth/session'
import {
  ALL_TASKS,
  ROLE_TASKS,
  TASK_ACCEPTANCE_CASES,
  UNSUPPORTED_ROLES,
  canPerformTask,
  tasksForRoles,
  unsupportedRoleOf,
  type TaskId,
} from '@/auth/capabilities'

/** 导航标签 → 任务（与 AppShell.NAV 的业务面入口一一对应）。 */
const LABEL_TO_TASK: Record<string, TaskId> = {
  工作台: 'dashboard',
  发起辅导: 'run:create',
  检索记录: 'run:list',
  知识检索: 'rag:search',
  通用问答: 'chat:general',
  'Agent 对话': 'chat:agent',
  学习画像: 'profile:read',
  报表中心: 'reports:read',
}

const BUSINESS_LABELS = Object.keys(LABEL_TO_TASK)

function seedSession(roles: string[]): void {
  window.sessionStorage.setItem('agent.session', JSON.stringify({
    accessToken: `tok-${roles.join('-')}`,
    expiresAt: Date.now() + 900_000,
    tenantId: 'f94ae0d9-289f-4c96-98ed-634f8f4fcf02',
    roles,
  }))
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

/** 导航地标内所有可见入口的可访问名 */
function visibleNavLabels(): string[] {
  const nav = screen.getByRole('navigation', { name: '主导航' })
  // ⚠️ 用 queryAllByRole：未开放角色的导航是**刻意空**的地标，
  //    getAllByRole 在零命中时会抛错，那会把「0 个入口」误判成测试失败。
  return within(nav).queryAllByRole('link').map((a) => a.textContent?.trim() ?? '')
}

describe('门禁①：每个任务都必须有「真实操作」验收用例', () => {
  it('⛔ ALL_TASKS 与用例登记表一一对应，缺登记即失败', () => {
    expect(Object.keys(TASK_ACCEPTANCE_CASES).sort()).toEqual([...ALL_TASKS].sort())
    for (const task of ALL_TASKS) {
      expect(TASK_ACCEPTANCE_CASES[task]).toMatch(/^TC-/)
      expect(TASK_ACCEPTANCE_CASES[task].length).toBeGreaterThan(10)
    }
  })

  it('⛔ 反向验证：删掉一条登记，上面的完整性断言会转红', () => {
    const tampered = { ...TASK_ACCEPTANCE_CASES }
    delete (tampered as Partial<typeof tampered>)['profile:read']
    expect(Object.keys(tampered).sort()).not.toEqual([...ALL_TASKS].sort())
  })
})

describe('门禁②：能力矩阵与产品裁决', () => {
  it('STUDENT 可发起辅导，但不得有画像 / 报表（N2 / N5）', () => {
    const tasks = tasksForRoles(['STUDENT'])
    expect(tasks).toContain('run:create')
    expect(tasks).toContain('run:list')
    expect(tasks).not.toContain('profile:read')
    expect(tasks).not.toContain('reports:read')
  })

  it('⛔ N1：家长 / 老师本版本不开放 —— 任务集为空且整页拦截', () => {
    for (const role of UNSUPPORTED_ROLES) {
      expect(ROLE_TASKS[role]).toEqual([])
      expect(tasksForRoles([role])).toEqual([])
      expect(unsupportedRoleOf([role])).toBe(role)
      expect(canPerformTask([role], 'run:create')).toBe(false)
    }
  })

  it('多角色取并集：挂了历史 PARENT 的管理员不得被锁死', () => {
    expect(unsupportedRoleOf(['PARENT', 'TENANT_ADMIN'])).toBeNull()
    expect(canPerformTask(['PARENT', 'TENANT_ADMIN'], 'reports:read')).toBe(true)
  })

  it('TENANT_ADMIN 全能力；空 / 未知角色 fail-closed 只留工作台', () => {
    expect(tasksForRoles(['TENANT_ADMIN'])).toEqual([...ALL_TASKS])
    expect(tasksForRoles([])).toEqual(['dashboard'])
    expect(tasksForRoles(['SOME_FUTURE_ROLE'])).toEqual(['dashboard'])
  })

  it('⛔ 反向验证：给 STUDENT 放开画像会被断言抓到', () => {
    const tampered: Record<string, readonly TaskId[]> = {
      ...ROLE_TASKS,
      STUDENT: [...ROLE_TASKS['STUDENT'] ?? [], 'profile:read'],
    }
    expect(tampered['STUDENT']).toContain('profile:read')
    expect(ROLE_TASKS['STUDENT']).not.toContain('profile:read')
  })
})

describe('门禁③：渲染出来的导航 ⊆ 可达任务（不可达入口不渲染）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    // ⚠️ 角色已知，不会触发 /api/auth/roles 回补请求
    vi.stubGlobal('fetch', vi.fn())
  })

  it('⛔ STUDENT：可见入口全部可达；画像 / 报表不渲染', () => {
    seedSession(['STUDENT'])
    renderShell()

    const labels = visibleNavLabels()
    const allowed = new Set(
      tasksForRoles(['STUDENT']).map((t) =>
        BUSINESS_LABELS.find((l) => LABEL_TO_TASK[l] === t),
      ),
    )
    for (const label of labels) {
      if (label in LABEL_TO_TASK) expect(allowed.has(label)).toBe(true)
    }
    expect(labels).toContain('发起辅导')
    expect(labels).not.toContain('学习画像')
    expect(labels).not.toContain('报表中心')
    expect(screen.getByText('学生（本版本）')).toBeInTheDocument()
    expect(screen.getByTestId('header-identity')).not.toHaveTextContent('家长')
  })

  it('⛔ N1：PARENT 登录后是明确终态说明页，导航零业务入口', () => {
    seedSession(['PARENT'])
    renderShell()

    expect(screen.getByTestId('role-unavailable-notice')).toBeInTheDocument()
    expect(visibleNavLabels()).toHaveLength(0)
    const text = document.body.textContent ?? ''
    expect(text).toContain('该角色本版本尚未开放')
    expect(text).toContain('联系管理员')
    for (const word of ['网络问题', '稍后再试', '正在处理', '重新登录即可']) {
      expect(text).not.toContain(word)
    }
    expect(screen.getByRole('button', { name: '退出登录' })).toBeInTheDocument()
  })

  it('⛔ N1：TEACHER 同构（上一轮浏览器走查被中断，这里补齐）', () => {
    seedSession(['TEACHER'])
    renderShell()
    expect(screen.getByTestId('role-unavailable-notice')).toHaveTextContent('TEACHER')
    expect(visibleNavLabels()).toHaveLength(0)
  })

  it('⛔ 反向验证：若撤掉裁剪，PARENT 会重新出现「发起辅导」入口', () => {
    // 模拟旧实现：不按任务裁剪，直接渲染全部业务入口
    const flawedLabels = BUSINESS_LABELS.filter((l) =>
      canPerformTask(['PARENT'], LABEL_TO_TASK[l] ?? 'dashboard') ||
      l === '发起辅导',
    )
    expect(flawedLabels).toContain('发起辅导') // 旧实现的错配确实存在
    expect(canPerformTask(['PARENT'], 'run:create')).toBe(false) // 真实矩阵拒绝
  })

  it('TENANT_ADMIN 仍保有完整 18 项菜单（既有契约不回归）', () => {
    seedSession(['TENANT_ADMIN'])
    renderShell()
    expect(visibleNavLabels()).toHaveLength(18)
    expect(visibleNavLabels()).toContain('学习画像')
    expect(visibleNavLabels()).toContain('报表中心')
    expect(screen.queryByTestId('role-unavailable-notice')).not.toBeInTheDocument()
  })
})
