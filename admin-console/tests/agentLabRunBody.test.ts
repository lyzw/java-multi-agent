// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/client', () => ({
  request: vi.fn(),
  ApiError: class ApiError extends Error {},
}))

import { request } from '@/api/client'
import { createRun } from '@/api/agentLabApi'
import type { ExecutionTicket } from '@/api/executionTicketApi'

const sentBody = () => {
  const [, init] = vi.mocked(request).mock.calls[0] ?? []
  return JSON.parse(String(init?.body))
}

/** 一张形状正确的假票证：只关心它如何变成请求头（见 executionTicketChannel.test.ts）。 */
const ticket: ExecutionTicket = {
  accessToken: 'tenant-jwt',
  tokenType: 'Bearer',
  tenantId: '3fa85f64-5717-4562-b3fc-2c963f66afa6',
  principalId: '11111111-1111-1111-1111-111111111111',
  loginName: 'stu-01',
  roles: ['AGENT_AUTHOR'],
  expiresAt: new Date(Date.now() + 60_000).toISOString(),
  jti: 'jti-1',
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
    await createRun(
      {
        agentId: 'agent-1',
        userMessage: '一元二次方程怎么计算',
        idempotencyKey: 'lab-1',
      },
      ticket,
    )

    expect(sentBody().input).toEqual({ userMessage: '一元二次方程怎么计算' })
  })

  it('不透传 tenantId，也不把 userMessage 留在顶层', async () => {
    await createRun(
      {
        agentId: 'agent-1',
        userMessage: 'q',
        timeoutMs: 30000,
        idempotencyKey: 'lab-2',
      },
      ticket,
    )

    const sent = sentBody()
    expect(Object.keys(sent).sort()).toEqual(
      ['agentId', 'idempotencyKey', 'input', 'timeoutMs'].sort(),
    )
    expect(sent.userMessage).toBeUndefined()
  })

  it('缺省的可选项不进请求体（null 由服务端按“未声明”处理）', async () => {
    await createRun({ agentId: 'agent-1', userMessage: 'q', budgetAmount: null }, ticket)

    expect(sentBody().budgetAmount).toBeNull()
    expect('timeoutMs' in sentBody()).toBe(false)
  })
})
