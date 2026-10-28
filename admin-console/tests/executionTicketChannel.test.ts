// @vitest-environment jsdom
/**
 * 跨域执行票证通道 —— 三条不可回退的边界。
 *
 * 1) **票证头必须赢过平台会话头**：原始缺陷就是平台令牌覆盖了租户票证，
 *    于是 `/api/runs` 永远 401。这里在 fetch 边界断言，而不是断言 mock 的调用参数 ——
 *    只有真实 `client.ts` 的拼装顺序才能证明这件事。
 * 2) **票证绝不落浏览器存储**：它 60 秒内与租户口令等价可用。
 * 3) **临近过期才续取**：轮询 2 秒一次，若每 tick 都取票，一次观察会刷出上百条审计。
 *
 * 全部走真实 `@/api/client` + 假 fetch，`@/api/executionTicketApi` 不 mock。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { clearSession, storeSession } from '@/auth/tokenStorage'
import { createRun, getRunStatus, type CreateRunRequest } from '@/api/agentLabApi'
import {
  clearExecutionTicket,
  ensureExecutionTicket,
  getTenantPrincipals,
  peekExecutionTicket,
  requestExecutionTicket,
  type ExecutionTicket,
} from '@/api/executionTicketApi'

const PLATFORM_TOKEN = 'platform-jwt-DO-NOT-USE-ON-TENANT-ENDPOINTS'
const TENANT_TOKEN = 'tenant-jwt-from-ticket'
const TENANT_A = '3fa85f64-5717-4562-b3fc-2c963f66afa6'
const TENANT_B = 'a1111111-1111-1111-1111-111111111111'
const PRINCIPAL = 'b2222222-2222-2222-2222-222222222222'

/** 每次 fetch 的出网请求头（含 Authorization），按 URL 归类。 */
let sent: { url: string; method: string; authorization: string | null }[]
let mintCount = 0

function ticketJson(tenantId: string, loginName: string): ExecutionTicket {
  return {
    accessToken: TENANT_TOKEN,
    tokenType: 'Bearer',
    tenantId,
    principalId: PRINCIPAL,
    loginName,
    roles: ['AGENT_AUTHOR'],
    // ⚠️ 由「当前时间」推 60 秒：配合 fake timers，剩余时间才是可推进的真实量。
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
    jti: `jti-${mintCount}`,
  }
}

function bodyFor(url: string, requestBody?: unknown): unknown {
  if (url.endsWith('/execution-ticket')) {
    mintCount += 1
    // ⚠️ 租户/身份从**本次请求**里取（不是写死），否则「换身份必须重签」
    //    会被假网关返回同一张票掩盖掉 —— 实测第一版就这样漏判。
    const tenantId = decodeURIComponent(url.split('/tenants/')[1]?.split('/')[0] ?? '')
    const loginName = String(
      JSON.parse(String(requestBody ?? '{}'))['loginName'] ?? 'unknown',
    )
    return ticketJson(tenantId, loginName)
  }
  if (url.endsWith('/principals')) return []
  if (url.includes('/runs/')) {
    return {
      runId: 'r-1',
      state: 'QUEUED',
      createdAt: new Date().toISOString(),
      completedAt: null,
      attempt: 0,
      errorCode: null,
    }
  }
  return { runId: 'r-1', replayed: false, state: 'QUEUED', correlationId: 'c-1' }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-28T00:00:00Z'))
  mintCount = 0
  sent = []
  clearExecutionTicket()
  clearSession()

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const headers = new Headers(init?.headers)
    sent.push({ url, method: init?.method ?? 'GET', authorization: headers.get('Authorization') })
    return {
      ok: true,
      status: 200,
      async text() {
        return JSON.stringify(bodyFor(url, init?.body))
      },
    } as unknown as Response
  })
  vi.stubGlobal('fetch', fetchMock)
})

describe('① 票证头压过平台会话头', () => {
  it('createRun 发出的是票证令牌，不是平台令牌', async () => {
    // 平台会话真实存在（sessionStorage），这是覆盖行为的历史成因。
    storeSession({ accessToken: PLATFORM_TOKEN, expiresAt: Date.now() + 600_000 })

    const ticket = await ensureExecutionTicket(TENANT_A, 'stu-01')
    const payload: CreateRunRequest = { agentId: 'agent-1', userMessage: 'q' }
    await createRun(payload, ticket)

    const runCall = sent.find((c) => c.url.endsWith('/api/runs') && c.method === 'POST')
    expect(runCall).toBeDefined()
    expect(runCall?.authorization).toBe(`Bearer ${TENANT_TOKEN}`)
    expect(runCall?.authorization).not.toContain(PLATFORM_TOKEN)
  })

  it('getRunStatus 同样带票证（漏一处就会「发起成功、查不到状态」）', async () => {
    storeSession({ accessToken: PLATFORM_TOKEN, expiresAt: Date.now() + 600_000 })

    const ticket = await ensureExecutionTicket(TENANT_A, 'stu-01')
    await getRunStatus('r-1', ticket)

    const getStatus = sent.find((c) => c.url.includes('/api/runs/r-1'))
    expect(getStatus?.authorization).toBe(`Bearer ${TENANT_TOKEN}`)
  })

  it('取票本身用平台令牌（它是平台域端点）', async () => {
    storeSession({ accessToken: PLATFORM_TOKEN, expiresAt: Date.now() + 600_000 })

    await ensureExecutionTicket(TENANT_A, 'stu-01')

    const mint = sent.find((c) => c.url.endsWith('/execution-ticket'))
    expect(mint?.authorization).toBe(`Bearer ${PLATFORM_TOKEN}`)
  })

  it('租户成员列表也属平台域，用平台令牌', async () => {
    storeSession({ accessToken: PLATFORM_TOKEN, expiresAt: Date.now() + 600_000 })

    await getTenantPrincipals(TENANT_A)

    const list = sent.find((c) => c.url.endsWith('/principals'))
    expect(list?.authorization).toBe(`Bearer ${PLATFORM_TOKEN}`)
  })
})

describe('② 票证只在内存', () => {
  it('一次完整执行流程不写任何浏览器存储', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    storeSession({ accessToken: PLATFORM_TOKEN, expiresAt: Date.now() + 600_000 })
    setItem.mockClear() // 只统计取票之后的写入

    const ticket = await ensureExecutionTicket(TENANT_A, 'stu-01')
    await createRun({ agentId: 'agent-1', userMessage: 'q' }, ticket)
    await getRunStatus('r-1', ticket)

    // 关键断言不是「没写过 storage」，而是「写过的内容里没有票证」。
    for (const call of setItem.mock.calls) {
      const written = String(call[1])
      expect(written).not.toContain(TENANT_TOKEN)
      expect(written).not.toContain(ticket.jti)
    }
    expect(peekExecutionTicket()).not.toBeNull()
  })

  it('票证不出现在 URL 里（SSE 走 query 的诱惑要留痕）', async () => {
    const ticket = await ensureExecutionTicket(TENANT_A, 'stu-01')
    await createRun({ agentId: 'agent-1', userMessage: 'q' }, ticket)

    for (const call of sent) {
      expect(call.url).not.toContain(TENANT_TOKEN)
    }
  })
})

describe('③ 临近过期才续取', () => {
  it('46 秒内多次调用只签 1 次票（不是每个轮询 tick 一次）', async () => {
    await ensureExecutionTicket(TENANT_A, 'stu-01')
    for (let i = 0; i < 22; i++) {
      vi.advanceTimersByTime(2_000) // 与 POLL_INTERVAL_MS 同节奏
      await ensureExecutionTicket(TENANT_A, 'stu-01')
    }

    expect(mintCount).toBe(1)
  })

  it('跨过续取余量后才重签，且新票仍然可用', async () => {
    await ensureExecutionTicket(TENANT_A, 'stu-01')
    vi.advanceTimersByTime(46_000) // 剩余 14s < 15s 余量
    const renewed = await ensureExecutionTicket(TENANT_A, 'stu-01')

    expect(mintCount).toBe(2)
    expect(renewed.jti).toBe('jti-2')
  })

  it('换租户即丢弃旧票：绝不把 A 租户的票拿去打 B 租户', async () => {
    await ensureExecutionTicket(TENANT_A, 'stu-01')
    const second = await ensureExecutionTicket(TENANT_B, 'stu-01')

    expect(mintCount).toBe(2)
    expect(second.tenantId).toBe(TENANT_B)
  })

  it('换身份即丢弃旧票：扮演对象变了必须重新留痕', async () => {
    await ensureExecutionTicket(TENANT_A, 'stu-01')
    const second = await ensureExecutionTicket(TENANT_A, 'stu-02')

    expect(mintCount).toBe(2)
    expect(second.loginName).toBe('stu-02')
  })

  it('clearExecutionTicket 后强制重签（切上下文由调用方显式作废）', async () => {
    await ensureExecutionTicket(TENANT_A, 'stu-01')
    clearExecutionTicket()
    expect(peekExecutionTicket()).toBeNull()
    await ensureExecutionTicket(TENANT_A, 'stu-01')

    expect(mintCount).toBe(2)
  })
})

describe('④ 显式取票的路径与载荷', () => {
  it('POST 到平台域票证端点，body 只带 loginName', async () => {
    let capturedBody: unknown
    vi.mocked(fetch).mockImplementation(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        capturedBody = init?.body
        sent.push({
          url,
          method: init?.method ?? 'GET',
          authorization: new Headers(init?.headers).get('Authorization'),
        })
        return {
          ok: true,
          status: 200,
          async text() {
            return JSON.stringify({ ...ticketJson(TENANT_A, 'stu-01'), jti: 'jti-x' })
          },
        } as unknown as Response
      },
    )

    await requestExecutionTicket(TENANT_A, 'stu-01')

    const mint = sent.find((c) => c.url.endsWith('/execution-ticket'))
    expect(mint?.url).toBe(`/api/platform/v1/tenants/${TENANT_A}/execution-ticket`)
    expect(mint?.method).toBe('POST')
    // ⚠️ body 只允许 loginName：租户走 URL 路径，票证本身绝不出现在 query 或 body 里。
    expect(JSON.parse(String(capturedBody))).toEqual({ loginName: 'stu-01' })
  })
})
