// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/client', () => ({
  request: vi.fn(),
  ApiError: class ApiError extends Error {},
}))

import { request } from '@/api/client'
import { createRun } from '@/api/agentLabApi'

const sentBody = () => {
  const [, init] = vi.mocked(request).mock.calls[0] ?? []
  return JSON.parse(String(init?.body))
}

describe('createRun 的线格式', () => {
  beforeEach(() => {
    vi.mocked(request).mockReset()
    vi.mocked(request).mockResolvedValue({
      runId: 'r-1',
      replayed: false,
      state: 'QUEUED',
      correlationId: 'c-1',
    })
  })

  // 服务端 CreateRunRequest.input 是 Map<String,Object>；
  // 实测发字符串得 400 INVALID_REQUEST，发对象得 201。这条断言就是那次实测的固化。
  it('input 发成对象，而不是裸字符串', async () => {
    await createRun({
      agentId: 'agent-1',
      userMessage: '一元二次方程怎么计算',
      idempotencyKey: 'lab-1',
    })

    expect(sentBody().input).toEqual({ userMessage: '一元二次方程怎么计算' })
  })

  it('不透传 tenantId，也不把 userMessage 留在顶层', async () => {
    await createRun({
      agentId: 'agent-1',
      userMessage: 'q',
      timeoutMs: 30000,
      idempotencyKey: 'lab-2',
    })

    const sent = sentBody()
    expect(Object.keys(sent).sort()).toEqual(
      ['agentId', 'idempotencyKey', 'input', 'timeoutMs'].sort(),
    )
    expect(sent.userMessage).toBeUndefined()
  })

  it('缺省的可选项不进请求体（null 由服务端按“未声明”处理）', async () => {
    await createRun({ agentId: 'agent-1', userMessage: 'q', budgetAmount: null })

    expect(sentBody().budgetAmount).toBeNull()
    expect('timeoutMs' in sentBody()).toBe(false)
  })
})
