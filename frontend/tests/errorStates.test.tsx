/**
 * 错误态接线 + 原题回填测试（E2E 二次评估 N2 / N4 / N6）
 *
 * ⚠️ 光测 `errorGuidance` 纯函数不够：F8 的教训是「抽象对了，
 *    但页面没接上」。故这里必须渲染真实页面，断言
 *    · 画像页 403 **没有**重试按钮、没有「网络问题」话术；
 *    · 5xx 仍有重试；
 *    · 「重新发起这道题」确实把原题带回表单。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import { ProfilePage } from '@/pages/business/ProfilePage'
import { NewRunPage } from '@/pages/NewRunPage'
import { ErrorStateFromError } from '@/shared/states'
import { ApiError } from '@/api/client'
import { makeRetryState, readRetryState } from '@/features/run/retryForm'
import type { NormalizedError } from '@/api/errors'

const getProfileMock = vi.hoisted(() => vi.fn())
const listAgentsMock = vi.hoisted(() => vi.fn())
const getDictionariesMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    getProfile: getProfileMock,
    listAgents: listAgentsMock,
    getDictionaries: getDictionariesMock,
  }
})

function apiError(status: number, code: string, message: string): ApiError {
  return new ApiError({ status, code, message, fieldErrors: {} })
}

const FORM = {
  studentId: 'S-2026-0001',
  subject: '数学',
  grade: '初一',
  questionText: '题目内容',
  studentSolution: '我的解题过程',
  correctAnswer: '已知正确答案',
}

beforeEach(() => {
  window.sessionStorage.clear()
  getProfileMock.mockReset()
  listAgentsMock.mockReset().mockResolvedValue({ items: [] })
  getDictionariesMock.mockReset().mockResolvedValue({ subjects: [], grades: [] })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('N2：学习画像 403 必须是终态，不得伪装成网络故障', () => {
  function renderProfile(): void {
    render(
      <MemoryRouter initialEntries={['/profile/S-2026-0001']}>
        <SessionProvider>
          <Routes>
            <Route path="/profile/:studentId" element={<ProfilePage />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )
  }

  it('⛔ 403 → 不给重试按钮、不说「可能是网络问题」', async () => {
    getProfileMock.mockRejectedValue(apiError(403, 'ACCESS_DENIED', '无权执行该操作'))
    renderProfile()

    await waitFor(() => {
      expect(screen.getByText(/无权执行该操作/)).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument()
    const text = document.body.textContent ?? ''
    expect(text).not.toContain('可能是网络问题')
    expect(text).not.toContain('还没被辅导过')
    expect(text).toContain('联系管理员')
    expect(text).toContain('不会改变结果')
  })

  it('⛔ 反证：5xx 临时故障仍给重试（否则上一条只是在测「永远不给按钮」）', async () => {
    getProfileMock.mockRejectedValue(apiError(500, 'HTTP_500', '服务暂时不可用'))
    renderProfile()

    await waitFor(() => {
      expect(screen.getByText(/服务暂时不可用/)).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
  })
})

describe('N6：ErrorStateFromError 是唯一分流点', () => {
  const forbidden: NormalizedError = {
    status: 403, code: 'ACCESS_DENIED', message: '无权执行该操作', fieldErrors: {},
  }
  const offline: NormalizedError = {
    status: 0, code: 'NETWORK_ERROR', message: '网络连接失败', fieldErrors: {},
  }

  it('终态错误即便传了 onRetry 也不渲染按钮', () => {
    render(<ErrorStateFromError error={forbidden} onRetry={vi.fn()} />)
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument()
  })

  it('临时故障渲染按钮，且可保留页面原有按钮文案', () => {
    render(<ErrorStateFromError error={offline} onRetry={vi.fn()} retryLabel="重新读取" />)
    expect(screen.getByRole('button', { name: '重新读取' })).toBeInTheDocument()
  })
})

describe('N4：重新发起这道题必须带回原题', () => {
  it('readRetryState 严格白名单：缺字段 / 非字符串一律整份丢弃', () => {
    expect(readRetryState(makeRetryState(FORM, 'agent-1')).retryForm).toEqual(FORM)
    expect(readRetryState({ retryForm: { studentId: 'x' } })).toEqual({})
    expect(readRetryState({ retryForm: { ...FORM, __proto__: 'bad' } }).retryForm).toEqual(FORM)
    expect(readRetryState(null)).toEqual({})
  })

  it('⛔ 带 location.state 时表单被回填，且明确提示需核对', () => {
    render(
      <MemoryRouter initialEntries={[{ pathname: '/runs/new', state: makeRetryState(FORM, 'agent-1') }]}>
        <SessionProvider>
          <Routes>
            <Route path="/runs/new" element={<NewRunPage />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )

    expect((document.querySelector('#questionText') as HTMLTextAreaElement).value).toBe(FORM.questionText)
    // ⚠️ 身份改造（2026-11-17）：学生标识**不再由用户填写** ——
    //    它由登录态派生，页面没有可编辑的 studentId 输入框了。
    expect(document.querySelector('#studentId')).toBeNull()
    expect(screen.getByText(/已带回上一道题的内容/)).toBeInTheDocument()
  })

  it('⛔ 反证：无 location.state 时表单为空（不残留上一题）', () => {
    render(
      <MemoryRouter initialEntries={['/runs/new']}>
        <SessionProvider>
          <Routes>
            <Route path="/runs/new" element={<NewRunPage />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )

    // ⚠️ 身份改造：无 location.state 时不得残留上一题内容；
    //    学生标识由登录态决定，页面没有 studentId 输入框。
    expect((document.querySelector('#questionText') as HTMLTextAreaElement).value).toBe('')
    expect(document.querySelector('#studentId')).toBeNull()
    expect(screen.queryByText(/已带回上一道题的内容/)).not.toBeInTheDocument()
  })
})
