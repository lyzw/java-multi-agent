/**
 * 反馈接口线路契约测试（P7-3 / P7-4）
 *
 * ⛔ 本类**不 mock** `@/api/client`——它测的是**真实**的请求拼装。
 *   组件层测试只能证明「调用了某个函数」，
 *   本类才能证明「发出去的 HTTP 请求形状是对的」。
 *
 * ⚠️ 核心断言（安全边界）：
 *   请求体**只有** runId 与 kind ——
 *   不得出现 tenantId（租户身份只能来自服务端验签的令牌），
 *   也不得出现 traceId（内部会话标识，浏览器无权断言）。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { recordRunFeedback } from '@/api/client'

interface Captured {
  url: string
  method: string
  body: unknown
  headers: Record<string, string>
}

let captured: Captured | null = null

beforeEach(() => {
  captured = null
  vi.stubGlobal('fetch', async (input: string, init?: RequestInit) => {
    captured = {
      url: String(input),
      method: init?.method ?? 'GET',
      body: init?.body === undefined ? undefined : JSON.parse(String(init.body)),
      headers: Object.fromEntries(
        new Headers(init?.headers ?? {}).entries() as unknown as [string, string][],
      ),
    }
    return {
      ok: true,
      status: 200,
      text: async () => '',
    } as unknown as Response
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('反馈接口线路契约', () => {
  it('应发到 /api/metrics/feedback（租户侧端点，不是平台侧）', async () => {
    await recordRunFeedback('r-1', 'THUMB_UP')
    expect(captured?.url).toBe('/api/metrics/feedback')
    expect(captured?.method).toBe('POST')
  })

  it('⛔ 请求体不得包含 tenantId / traceId（只能有 runId 与 kind）', async () => {
    await recordRunFeedback('r-1', 'THUMB_DOWN')
    expect(captured?.body).toEqual({ runId: 'r-1', kind: 'THUMB_DOWN' })
    const raw = JSON.stringify(captured?.body)
    expect(raw).not.toContain('tenant')
    expect(raw).not.toContain('trace')
  })

  it('应声明 JSON 内容类型', async () => {
    await recordRunFeedback('r-1', 'RETRY')
    expect(captured?.headers['content-type']).toBe('application/json')
  })

  it('四种取值应原样发出（大小写由后端统一处理）', async () => {
    for (const kind of ['THUMB_UP', 'THUMB_DOWN', 'RETRY', 'ABANDON'] as const) {
      await recordRunFeedback('r-1', kind)
      expect(captured?.body).toEqual({ runId: 'r-1', kind })
    }
  })
})
