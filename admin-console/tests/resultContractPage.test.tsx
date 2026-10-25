// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  submitPublishRequest: vi.fn(),
}))
import { submitPublishRequest } from '@/api/adminApi'

vi.mock('@/auth/session', () => ({
  useSession: () => ({ roles: [] as string[] }),
  SessionProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

const navigateMock = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

import { ResultContractPage } from '@/pages/catalog/ResultContractPage'

// antd v5 渲染期读 matchMedia；jsdom 默认没有，补一个最小桩
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

function renderPage() {
  return render(
    <MemoryRouter>
      <AntApp>
        <ResultContractPage />
      </AntApp>
    </MemoryRouter>,
  )
}

describe('输出契约登记页', () => {
  it('✅ 渲染契约标识 / 契约定义 / 变更理由三个字段', () => {
    renderPage()
    expect(screen.getByLabelText(/契约标识/)).toBeTruthy()
    expect(screen.getByLabelText(/契约定义/)).toBeTruthy()
    expect(screen.getByLabelText(/变更理由/)).toBeTruthy()
  })

  it('✅ 提交时把契约放进 resultContracts（而不是 agents）', async () => {
    const mocked = vi.mocked(submitPublishRequest)
    mocked.mockResolvedValue({
      requestId: 'req-1234567890',
      operation: 'PUBLISH',
      state: 'PENDING',
      requesterId: 'u-1',
      approverId: null,
      decisionNote: null,
      requestedAt: '2026-10-25T00:00:00Z',
      decidedAt: null,
      appliedAt: null,
    } as never)

    renderPage()
    fireEvent.change(screen.getByLabelText(/契约标识/), { target: { value: 'answer-contract' } })
    // 契约定义用初始值（已是合法 JSON），只改理由
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '登记输出契约' } })
    fireEvent.click(screen.getByRole('button', { name: /提交登记申请/ }))

    await waitFor(() => expect(mocked).toHaveBeenCalledTimes(1))
    // ⚠️ 非空断言：上面已断言「被调用过一次」，这里取那一次的入参。
    const payload = mocked.mock.calls[0]![0]
    expect(payload.operation).toBe('PUBLISH')
    expect(payload.scope).toBe('TENANT')
    expect(payload.reason).toBe('登记输出契约')
    expect(payload.agents).toBeUndefined()
    const contracts = payload.resultContracts
    expect(contracts).toHaveLength(1)
    expect(contracts?.[0]?.contractId).toBe('answer-contract')
    // ⚠️ 契约 id 是**可读字符串**，不是 UUID
    expect(contracts?.[0]?.contractId).not.toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}/i,
    )
  })

  it('⛔ 契约定义不是合法 JSON → 不得提交（当场拒，不等服务端）', async () => {
    const mocked = vi.mocked(submitPublishRequest)
    mocked.mockClear()

    renderPage()
    fireEvent.change(screen.getByLabelText(/契约标识/), { target: { value: 'answer-contract' } })
    fireEvent.change(screen.getByLabelText(/契约定义/), {
      target: { value: '{ 这不是 JSON' },
    })
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '理由' } })
    fireEvent.click(screen.getByRole('button', { name: /提交登记申请/ }))

    await waitFor(() => {
      expect(screen.getByText(/不是合法 JSON/)).toBeTruthy()
    })
    expect(mocked).not.toHaveBeenCalled()
  })

  it('⛔ 契约标识为空 → 不得提交（必填校验）', async () => {
    const mocked = vi.mocked(submitPublishRequest)
    mocked.mockClear()

    renderPage()
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '理由' } })
    fireEvent.click(screen.getByRole('button', { name: /提交登记申请/ }))

    await waitFor(() => {
      expect(screen.getByText(/请输入契约标识/)).toBeTruthy()
    })
    expect(mocked).not.toHaveBeenCalled()
  })

  it('⛔ 变更理由为空 → 不得提交（理由会留痕）', async () => {
    const mocked = vi.mocked(submitPublishRequest)
    mocked.mockClear()

    renderPage()
    fireEvent.change(screen.getByLabelText(/契约标识/), { target: { value: 'answer-contract' } })
    fireEvent.click(screen.getByRole('button', { name: /提交登记申请/ }))

    await waitFor(() => {
      expect(screen.getByText(/变更理由不能为空/)).toBeTruthy()
    })
    expect(mocked).not.toHaveBeenCalled()
  })
})
