/**
 * 页面组件测试（FE-D1 §9 T1/T2/T10）
 *
 * 核心红线：
 *   T1 登录失败提示统一，不区分原因
 *   T2 表单**不存在**禁用字段输入框（姓名/学校/班级/身份证/手机号）
 *      ——「不存在」优于「校验后拒绝」
 */

import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { LoginPage } from '@/pages/LoginPage'
import { NewRunPage } from '@/pages/NewRunPage'
import { SessionProvider } from '@/auth/session'
import { ApiError } from '@/api/client'

function renderWithProviders(ui: React.ReactElement): {
  container: HTMLElement
} {
  return render(
    <MemoryRouter>
      <SessionProvider>{ui}</SessionProvider>
    </MemoryRouter>,
  )
}

describe('登录页', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    window.sessionStorage.clear()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('应渲染三个输入框', () => {
    renderWithProviders(<LoginPage />)
    expect(screen.getByLabelText(/租户标识/)).toBeInTheDocument()
    expect(screen.getByLabelText(/账号/)).toBeInTheDocument()
    expect(screen.getByLabelText(/密码/)).toBeInTheDocument()
  })

  describe('⛔ 刻意不提供的入口', () => {
    it('无「忘记密码」', () => {
      renderWithProviders(<LoginPage />)
      expect(screen.queryByText(/忘记密码/)).not.toBeInTheDocument()
    })
    it('无「注册」', () => {
      renderWithProviders(<LoginPage />)
      expect(screen.queryByText(/注册/)).not.toBeInTheDocument()
    })
    it('无「切换租户」', () => {
      renderWithProviders(<LoginPage />)
      expect(screen.queryByText(/切换租户/)).not.toBeInTheDocument()
    })
  })

  describe('T1：登录失败提示统一', () => {
    it('invalid_credentials → 统一文案', async () => {
      // ⚠️ 轻量替身：真实 Response 的 body 只能读一次，readBody 会消费它
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 401,
        text: async () => JSON.stringify({ error: 'invalid_credentials' }),
      } as unknown as Response)

      renderWithProviders(<LoginPage />)
      await userEvent.type(screen.getByLabelText(/租户标识/), 't-a')
      await userEvent.type(screen.getByLabelText(/账号/), 'alice')
      await userEvent.type(screen.getByLabelText(/密码/), 'wrongpass')
      await userEvent.click(screen.getByRole('button', { name: '登录' }))

      // ⛔ 先确认请求确实发出，再断言 UI —— 否则可能在请求前就通过
      await waitFor(() => {
        expect(fetchMock).toHaveBeenCalledTimes(1)
      })
      await waitFor(() => {
        expect(screen.getByText('租户、账号或密码不正确')).toBeInTheDocument()
      })
    })

    it('⛔ 提示不得区分「账号不存在」', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 401,
        text: async () => JSON.stringify({ error: 'invalid_credentials' }),
      } as unknown as Response)

      renderWithProviders(<LoginPage />)
      await userEvent.type(screen.getByLabelText(/租户标识/), 'nonexistent-tenant')
      await userEvent.type(screen.getByLabelText(/账号/), 'no-such-user')
      await userEvent.type(screen.getByLabelText(/密码/), 'any')
      await userEvent.click(screen.getByRole('button', { name: '登录' }))

      const message = await screen.findByText('租户、账号或密码不正确')
      // 重建账号枚举面是最严重的失误之一
      expect(message).not.toHaveTextContent('不存在')
      expect(message).not.toHaveTextContent('无此账号')
    })
  })

  describe('登录成功', () => {
    it('应写入 sessionStorage 而非 localStorage', async () => {
      // ⚠️ 不使用真实 Response：其 body 只能读取一次，
      //    而 client.ts 的 readBody 会消费它。用轻量替身更稳定。
      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify({
            accessToken: 'tok-123',
            tokenType: 'Bearer',
            expiresIn: 900,
          }),
      } as unknown as Response)

      renderWithProviders(<LoginPage />)
      await userEvent.type(screen.getByLabelText(/租户标识/), 't-a')
      await userEvent.type(screen.getByLabelText(/账号/), 'alice')
      await userEvent.type(screen.getByLabelText(/密码/), 'correct')
      await userEvent.click(screen.getByRole('button', { name: '登录' }))

      await waitFor(() => {
        expect(fetchMock).toHaveBeenCalledTimes(1)
      })
      await waitFor(() => {
        expect(window.sessionStorage.getItem('agent.session')).toContain('tok-123')
      })
      // ⛔ 不得写入 localStorage（降低 XSS 场景下令牌存活时间）
      expect(window.localStorage.getItem('agent.session')).toBeNull()
    })

    it('请求体应只含 tenantId/loginName/password', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify({ accessToken: 't', tokenType: 'Bearer', expiresIn: 900 }),
      } as unknown as Response)

      renderWithProviders(<LoginPage />)
      await userEvent.type(screen.getByLabelText(/租户标识/), 't-a')
      await userEvent.type(screen.getByLabelText(/账号/), 'alice')
      await userEvent.type(screen.getByLabelText(/密码/), 'secret')
      await userEvent.click(screen.getByRole('button', { name: '登录' }))

      await waitFor(() => {
        expect(fetchMock).toHaveBeenCalledTimes(1)
      })
      const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
      const body = JSON.parse(String(init.body)) as Record<string, unknown>
      expect(Object.keys(body).sort()).toEqual([
        'loginName',
        'password',
        'tenantId',
      ])
    })
  })
})

describe('发起辅导页', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('⛔ T2：禁用字段根本不存在输入框', () => {
    /**
     * ⚠️ 断言对象是**输入控件**，不是整页 HTML。
     * 合规提示文案中会主动出现「不会收集姓名、学校、班级」等字样
     * （这是正确的用户告知），断言整页文本不含这些词会误报。
     */
    it('⛔ 不得存在采集禁用信息的输入控件', () => {
      const { container } = renderWithProviders(<NewRunPage />)
      const controls = Array.from(
        container.querySelectorAll('input, textarea, select'),
      )

      for (const el of controls) {
        const type = el.getAttribute('type')
        const name = (el.getAttribute('name') ?? '').toLowerCase()
        const id = (el.getAttribute('id') ?? '').toLowerCase()
        const placeholder = (el.getAttribute('placeholder') ?? '').toLowerCase()
        const haystack = `${name} ${id} ${placeholder}`

        // PIPL 硬红线：这些信息**结构上不允许采集**
        for (const forbidden of [
          'name',
          'realname',
          'school',
          'class',
          'idcard',
          'identity',
          'phone',
          'mobile',
          'tel',
          'address',
          'wechat',
          'qq',
          'avatar',
          'face',
        ]) {
          expect(haystack).not.toContain(forbidden)
        }
        // ⛔ 不得有其他敏感输入类型（file 可能上传含身份信息的图片）
        expect(type).not.toBe('file')
        expect(type).not.toBe('tel')
        expect(type).not.toBe('email')
      }
    })

    it('⛔ 字段集合应恰为白名单 6 项', () => {
      const { container } = renderWithProviders(<NewRunPage />)
      const names = Array.from(
        container.querySelectorAll('input, textarea, select'),
      )
        .map((el) => el.getAttribute('name') ?? '')
        .filter(Boolean)
        .sort()

      // DEC-6 采集白名单：随机 student_id / 年级 / 学科 + 题目与过程
      expect(names).toEqual([
        'correctAnswer',
        'grade',
        'questionText',
        'studentId',
        'studentSolution',
        'subject',
      ])
    })

    it('⛔ 应显式告知用户不采集身份信息', () => {
      renderWithProviders(<NewRunPage />)
      // 这条告知本身是合规要求（PIPL 单独同意的前提）
      expect(screen.getByText(/不会收集姓名/)).toBeInTheDocument()
    })
  })

  it('学生标识字段应提示不要填姓名', () => {
    renderWithProviders(<NewRunPage />)
    // ⛔ 该提示是合规引导，必须存在（label + hint 共两处匹配）
    const hints = screen.getAllByText(/匿名编号/)
    expect(hints.length).toBeGreaterThan(0)
    expect(screen.getByText(/不要填姓名/)).toBeInTheDocument()
  })

  describe('⛔ 敏感信息阻断', () => {
    it('输入手机号后应显示违规提示且不发请求', async () => {
      const fetchSpy = vi.fn()
      vi.stubGlobal('fetch', fetchSpy)
      renderWithProviders(<NewRunPage />)

      await userEvent.type(
        screen.getByLabelText(/学生匿名编号/),
        'stu_a1b2c3d4e5f6a7b8',
      )
      await userEvent.type(
        screen.getByLabelText(/题目内容/),
        '题目',
      )
      await userEvent.type(
        screen.getByLabelText(/我的解题过程/),
        '我的电话 13812345678',
      )
      await userEvent.type(screen.getByLabelText(/已知正确答案/), '15')

      await userEvent.click(screen.getByRole('button', { name: '开始分析' }))

      await waitFor(() => {
        expect(screen.getAllByRole('alert').length).toBeGreaterThan(0)
      })
      // ⛔ 预检命中时不得发出请求
      expect(fetchSpy).not.toHaveBeenCalled()
      expect(screen.getByText(/手机号/)).toBeInTheDocument()
    })
  })

  it('提交按钮在字段未填齐时应禁用', () => {
    renderWithProviders(<NewRunPage />)
    expect(screen.getByRole('button', { name: '开始分析' })).toBeDisabled()
  })
})

describe('ApiError 契约', () => {
  it('应携带归一化后的错误信息', () => {
    const error = new ApiError({
      status: 400,
      code: 'INVALID_REQUEST',
      message: '请求有误',
      fieldErrors: {},
    })
    expect(error.normalized.code).toBe('INVALID_REQUEST')
    expect(error.message).toBe('请求有误')
  })
})
