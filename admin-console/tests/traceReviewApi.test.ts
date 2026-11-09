// @vitest-environment jsdom
/**
 * 回放 / 审查队列 API 的线格式守卫。
 *
 * 用 stubGlobal('fetch') 走真实 client.ts#request —— 不 mock 掉依赖，
 * 这样「路径自带 /api 前缀拼成 /api/api/...」「查询参数漏带」这类
 * 只在真实请求里才暴露的缺陷才会变红。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import {
  assignReviewTask,
  dismissReviewTask,
  fetchPlatformTranscript,
  fetchReviewTasks,
  fetchToolOperation,
  resolveReviewTask,
} from '@/api/traceReviewApi'

interface FetchCall {
  url: string
  init: RequestInit
}

let calls: FetchCall[] = []

function reply(status: number, body: unknown = null): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((url: string, init: RequestInit) => {
      calls.push({ url, init })
      return Promise.resolve({
        status,
        ok: status >= 200 && status < 300,
        text: () => Promise.resolve(body === null ? '' : JSON.stringify(body)),
      })
    }),
  )
}

beforeEach(() => {
  calls = []
  window.sessionStorage.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  window.sessionStorage.clear()
})

describe('对话回放端点', () => {
  it('走平台链 /platform/v1/runs/{id}/transcript，且只拼一次 /api 前缀', async () => {
    reply(200, { runId: 'run-1', messages: [], steps: [], tokens: null })
    await fetchPlatformTranscript('run-1')
    expect(calls[0]?.url).toBe('/api/platform/v1/runs/run-1/transcript')
    expect(calls[0]?.url).not.toContain('/api/api/')
  })

  it('runId 做 URL 编码，不把原始串直接拼进路径', async () => {
    reply(200, {})
    await fetchPlatformTranscript('run/with space')
    expect(calls[0]?.url).toBe('/api/platform/v1/runs/run%2Fwith%20space/transcript')
  })

  it('显式 tenantId 必须进查询串：覆盖「指标尚未采集、后端反查不到」的 Run', async () => {
    reply(200, {})
    await fetchPlatformTranscript('run-1', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2')
    expect(calls[0]?.url).toBe(
      '/api/platform/v1/runs/run-1/transcript?tenantId=2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2',
    )
  })
})

describe('TOOL 消息下钻端点', () => {
  it('路径与后端一致，且 tenantId 必带（tool_operation 是 FORCE RLS 租户域表）', async () => {
    reply(200, { operationId: 'op-1', detail: {} })
    await fetchToolOperation('op-1', '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2')
    expect(calls[0]?.url).toBe(
      '/api/platform/v1/runs/tool-operations/op-1?tenantId=2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2',
    )
    expect(calls[0]?.url).not.toContain('/api/api/')
  })

  it('operationId 做 URL 编码，不把原始串直接拼进路径', async () => {
    reply(200, { operationId: 'x', detail: {} })
    await fetchToolOperation('op/with space', 't-1')
    expect(calls[0]?.url).toContain('/tool-operations/op%2Fwith%20space?')
  })

  it('tenantId 做 URL 编码', async () => {
    reply(200, { operationId: 'x', detail: {} })
    await fetchToolOperation('op-1', 'tenant/with space')
    expect(calls[0]?.url).toContain('tenantId=tenant%2Fwith%20space')
  })
})

describe('审查队列端点', () => {
  it('缺省视图不带 status/tenantId；指定时按精确值拼查询', async () => {
    reply(200, { tasks: [] })
    await fetchReviewTasks()
    expect(calls[0]?.url).toBe('/api/platform/v1/review-tasks')

    reply(200, { tasks: [] })
    await fetchReviewTasks({ status: 'PENDING', tenantId: 't-1', limit: 20 })
    expect(calls[1]?.url).toBe('/api/platform/v1/review-tasks?status=PENDING&tenantId=t-1&limit=20')
  })

  it('⚠️ 响应缺少 tasks 数组必须抛错，绝不能静默当成「暂无待审任务」', async () => {
    reply(200, {})
    await expect(fetchReviewTasks()).rejects.toMatchObject({
      code: 'MALFORMED_RESPONSE',
      message: expect.stringContaining('不能当作「暂无待审任务」'),
    })
    // 反向确认它是 ApiError 而不是普通 rejection（页面会按 code 分支）。
    reply(200, { tasks: 'not-an-array' })
    await expect(fetchReviewTasks()).rejects.toBeInstanceOf(ApiError)
  })

  it('认领 / 归因 / 驳回：路径与请求体对齐后端控制器契约', async () => {
    reply(204)
    await assignReviewTask('task-1')
    expect(calls[0]?.url).toBe('/api/platform/v1/review-tasks/task-1/assign')
    expect(calls[0]?.init.method).toBe('POST')
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ assignee: undefined })

    await resolveReviewTask('task-1', 'TOOL', '工具超时')
    expect(calls[1]?.url).toBe('/api/platform/v1/review-tasks/task-1/resolve')
    expect(JSON.parse(String(calls[1]?.init.body))).toEqual({ rootCause: 'TOOL', note: '工具超时' })

    await dismissReviewTask('task-1')
    expect(calls[2]?.url).toBe('/api/platform/v1/review-tasks/task-1/dismiss')
    expect(JSON.parse(String(calls[2]?.init.body))).toEqual({ note: undefined })
  })
})
