/**
 * 结果页 / 画像页接线测试（FE-D8）
 *
 * ⛔ 核心纪律（沿用项目 R-016 教训 + FE-D7 范式）：
 *   - 既要「行为测试」（被调用时渲染正确），也要「连通性测试」（确实调了接口）
 *   - 必须有「反证」：数据来自 mock，改 mock 值 UI 必随之变化，否则断言恒真
 *   - **诚实边界**：真实空态不得渲染成「系统故障」；更不得渲染**编造内容**
 *
 * ⚠️ 本测试类的存在意义：P5 此前渲染的是**编造的解题过程**（FE-D8 §1.1）。
 *    故「**不得出现编造内容**」是本类最核心的断言。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactElement } from 'react'
import { SessionProvider } from '@/auth/session'
import { RunResultPage } from '@/pages/business/RunResultPage'
import { ProfilePage } from '@/pages/business/ProfilePage'
import type { ProfileResponse, RunResultResponse } from '@/api/types'

const getRunResultMock = vi.hoisted(() => vi.fn())
const getProfileMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return { ...actual, getRunResult: getRunResultMock, getProfile: getProfileMock }
})

function renderAt(path: string, element: ReactElement, pattern: string): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path={pattern} element={element} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  getRunResultMock.mockReset()
  getProfileMock.mockReset()
})

/** 标准成功结果：有讲解、错因与步骤各一条、无待核对。 */
function sampleResult(overrides?: Partial<RunResultResponse>): RunResultResponse {
  return {
    runId: 'r-1',
    state: 'SUCCEEDED',
    completedAt: '2026-10-20T08:00:00Z',
    errorCode: null,
    explanation: '先配方化为顶点式，再读出顶点。',
    weaknesses: [
      { knowledgePoint: '根式分母有理化', errorType: '符号错误', confidence: 0.82 },
    ],
    steps: [
      { number: 1, state: 'SUCCEEDED', stepType: 'MODEL', output: '配方' },
    ],
    reconciliation: { pending: false, operations: [] },
    ...overrides,
  }
}

/** 标准画像：存在、无薄弱点。 */
function sampleProfile(overrides?: Partial<ProfileResponse>): ProfileResponse {
  return {
    studentId: 'stu_abc',
    grade: '初二',
    subject: '数学',
    weaknesses: [],
    notFound: false,
    ...overrides,
  }
}

describe('T1：P5 接上真实结果', () => {
  it('应调用结果接口并渲染讲解内容', async () => {
    getRunResultMock.mockResolvedValue(sampleResult())
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')

    await waitFor(() => expect(getRunResultMock).toHaveBeenCalledWith('r-1'))
    expect(await screen.findByText('先配方化为顶点式，再读出顶点。')).toBeInTheDocument()
  })

  it('⛔ 不得渲染任何编造的解题内容（FE-D8 修复前这里会命中）', async () => {
    getRunResultMock.mockResolvedValue(sampleResult())
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')

    await screen.findByText('先配方化为顶点式，再读出顶点。')
    const body = document.body.textContent ?? ''
    // ⚠️ 这 6 条是 FE-D8 §1.1 列出的**原页面编造内容**，逐条断言其不存在
    expect(body).not.toContain('√3')
    expect(body).not.toContain('分子分母同乘')
    expect(body).not.toContain('符号处理不当')
    expect(body).not.toContain('根式分母有理化时')
    expect(body).not.toContain('分母化为正')
    expect(body).not.toContain('得到最简形式')
  })

  it('应渲染真实状态与真实错因', async () => {
    getRunResultMock.mockResolvedValue(sampleResult())
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')

    // ⚠️ 「SUCCEEDED」出现两次（Run 状态 + 步骤状态）→ 用 getAllByText
    expect((await screen.findAllByText('SUCCEEDED')).length).toBeGreaterThan(0)
    expect(await screen.findByText('符号错误')).toBeInTheDocument()
    expect(await screen.findByText('根式分母有理化')).toBeInTheDocument()
  })
})

describe('T2：P5 真实空态（不得编造，也不得说成故障）', () => {
  it('weaknesses 与 steps 为空时应显示真实空态', async () => {
    getRunResultMock.mockResolvedValue(
      sampleResult({ weaknesses: [], steps: [], explanation: '只有一段讲解' }),
    )
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')

    expect(await screen.findByText('没有步骤记录')).toBeInTheDocument()
    expect(screen.getByText('这次分析没有记录到错因')).toBeInTheDocument()
    // ⛔ 「空」不得被表述成「故障」
    const body = document.body.textContent ?? ''
    expect(body).not.toContain('系统故障')
    expect(body).not.toContain('没能完成')
  })

  it('空态应说明原因（用户该知道为什么没有）', async () => {
    getRunResultMock.mockResolvedValue(sampleResult({ weaknesses: [], steps: [] }))
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')

    // ⚠️ 必须先等页面加载完成，否则读到的是「正在读取」态
    await screen.findByText('没有步骤记录')
    const body = document.body.textContent ?? ''
    expect(body).toContain('不会用示例内容填充')
    expect(body).toContain('不会替你编一条')
  })
})

describe('T3：P5 待核对分区', () => {
  it('pending=true 时应列出未决操作', async () => {
    getRunResultMock.mockResolvedValue(
      sampleResult({
        reconciliation: {
          pending: true,
          operations: [
            { operationId: 'op-1', toolName: 'record_weakness', state: 'UNKNOWN' },
          ],
        },
      }),
    )
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')

    expect(await screen.findByText('待核对')).toBeInTheDocument()
    expect(await screen.findByText('record_weakness')).toBeInTheDocument()
    // ⚠️ 「UNKNOWN」出现两次（分区说明 + 操作状态）→ 用 getAllByText
    expect((await screen.findAllByText(/UNKNOWN/)).length).toBeGreaterThan(0)
  })

  it('pending=false 时应明确说没有待核对项', async () => {
    getRunResultMock.mockResolvedValue(sampleResult())
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')

    expect(await screen.findByText('当前没有待核对项。')).toBeInTheDocument()
  })
})

describe('T4：P5 错误态', () => {
  it('请求失败时应显示可重试的错误，且不露出编造内容', async () => {
    getRunResultMock.mockRejectedValue(new Error('boom'))
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')

    expect(await screen.findByText('没能完成')).toBeInTheDocument()
    const body = document.body.textContent ?? ''
    expect(body).not.toContain('√3')
    expect(body).not.toContain('符号处理不当')
  })
})

describe('T5：P6 notFound', () => {
  it('notFound=true 时应显示「找不到」而不是错误页', async () => {
    getProfileMock.mockResolvedValue(sampleProfile({ notFound: true }))
    renderAt('/profile/stu_abc', <ProfilePage />, '/profile/:studentId')

    expect(await screen.findByText('找不到这个匿名编号')).toBeInTheDocument()
    // ⛔ 「没有这个学生」**不是**「系统故障」
    expect(screen.queryByText('没能完成')).not.toBeInTheDocument()
  })
})

describe('T6：P6 结构化薄弱点不被过滤掉', () => {
  it('应渲染结构化薄弱点条目', async () => {
    getProfileMock.mockResolvedValue(
      sampleProfile({
        weaknesses: [
          { knowledgePoint: '分数加法', errorType: '概念不清', confidence: 0.7, createdAt: null },
        ],
      }),
    )
    renderAt('/profile/stu_abc', <ProfilePage />, '/profile/:studentId')

    expect(await screen.findByText('分数加法')).toBeInTheDocument()
    expect(await screen.findByText(/概念不清/)).toBeInTheDocument()
  })

  it('应渲染年级与学科', async () => {
    getProfileMock.mockResolvedValue(sampleProfile())
    renderAt('/profile/stu_abc', <ProfilePage />, '/profile/:studentId')

    expect(await screen.findByText('初二')).toBeInTheDocument()
    expect(await screen.findByText('数学')).toBeInTheDocument()
  })
})

describe('T7：⛔ C6 敏感信息拦截', () => {
  it('讲解内容含身份证时应被拦截并提示，且不静默删除整页', async () => {
    getRunResultMock.mockResolvedValue(
      sampleResult({ explanation: '你的身份证是 110101199001011234' }),
    )
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')

    expect(await screen.findByText('已拦下不可采集的信息')).toBeInTheDocument()
    // ⛔ 不得静默把内容显示出来
    expect(document.body.textContent ?? '').not.toContain('110101199001011234')
    // ⛔ 也不得整页报错（那会让「偶然复述学号」变成整页不可用）
    expect(screen.queryByText('没能完成')).not.toBeInTheDocument()
  })
})
