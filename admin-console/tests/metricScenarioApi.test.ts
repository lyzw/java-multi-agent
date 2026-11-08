// @vitest-environment jsdom
/**
 * 场景筛选的线格式守卫（质量看板收尾）。
 *
 * ⚠️ 守的是**整页同口径**：五个读端点必须都带同一个 scenario。
 *    只给 /overview 加筛选，分布/耗时/工具/异常仍是全量 —— 合计对不上却不报任何错，
 *    正是本项目反复出现的失真形态（不报错、只是数字悄悄错）。
 *
 * ⚠️ 用 stubGlobal('fetch') 走真实 client.ts#request —— mock 掉 request
 *    就照不出「某个函数忘了拼查询参数」这类只在真实请求里才暴露的缺陷。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import {
  fetchAgentMetricOverview,
  fetchTerminationDistribution,
  fetchLatency,
  fetchToolBreakdown,
  fetchAnomalies,
  fetchMetricScenarios,
  type MetricFilter,
} from '@/api/metricsApi'

let calls: string[] = []

function reply(status: number, body: unknown = null): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((url: string) => {
      calls.push(url)
      return Promise.resolve({
        status,
        ok: status >= 200 && status < 300,
        text: () => Promise.resolve(body === null ? '' : JSON.stringify(body)),
      })
    }),
  )
}

async function allFive(filter: MetricFilter = {}): Promise<void> {
  await Promise.all([
    fetchAgentMetricOverview(24, filter),
    fetchTerminationDistribution(24, filter),
    fetchLatency(24, filter),
    fetchToolBreakdown(24, filter),
    fetchAnomalies(24, 20, filter),
  ])
}

function paths(): string[] {
  return calls.map((u) => u.split('?')[0] ?? '').sort()
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

const SCENARIO = '数学:一元二次方程'
const ENCODED = encodeURIComponent(SCENARIO)
const ENDPOINTS = [
  '/api/platform/v1/metrics/agent/anomalies',
  '/api/platform/v1/metrics/agent/latency',
  '/api/platform/v1/metrics/agent/overview',
  '/api/platform/v1/metrics/agent/terminations',
  '/api/platform/v1/metrics/agent/tools',
].sort()

describe('场景筛选 · 整页同口径', () => {
  it('scenario 必须出现在全部 5 个读端点的查询串里（一个都不能漏）', async () => {
    reply(200, [])
    await allFive({ scenario: SCENARIO })

    expect(paths()).toEqual(ENDPOINTS)
    // 逐条断言：漏掉任意一个端点都会在这里精确标出是哪一个
    for (const url of calls) {
      expect(url).toContain(`scenario=${ENCODED}`)
    }
  })

  it('缺省不带 scenario；tenantId 仍独立透传', async () => {
    reply(200, [])
    await allFive()
    expect(paths()).toEqual(ENDPOINTS)
    for (const url of calls) {
      expect(url).not.toContain('scenario=')
      expect(url).toContain('hours=24')
    }

    calls = []
    await allFive({ tenantId: '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2', scenario: SCENARIO })
    for (const url of calls) {
      expect(url).toContain('tenantId=2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2')
      expect(url).toContain(`scenario=${ENCODED}`)
    }
  })

  it('「未标注」哨兵 (unlabeled) 原样进查询串，由后端译成 IS NULL', async () => {
    reply(200, [])
    await allFive({ scenario: '(unlabeled)' })
    expect(paths()).toEqual(ENDPOINTS)
    for (const url of calls) {
      expect(url).toContain('scenario=%28unlabeled%29')
    }
  })

  it('anomalies 的 limit 不被 filter 覆盖', async () => {
    reply(200, [])
    await fetchAnomalies(24, 7, { scenario: SCENARIO })
    expect(calls[0]).toContain('limit=7')
    expect(calls[0]).toContain(`scenario=${ENCODED}`)
  })
})

describe('场景枚举端点（筛选器自己的数据源）', () => {
  it('URL 刻意不带 scenario —— 跟着当前筛选拉会自锁，选中 A 后切不回 B', async () => {
    reply(200, [])
    await fetchMetricScenarios(24, { tenantId: '2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2' })

    expect(calls).toHaveLength(1)
    expect(calls[0]?.split('?')[0]).toBe('/api/platform/v1/metrics/agent/scenarios')
    expect(calls[0]).toContain('hours=24')
    expect(calls[0]).toContain('tenantId=2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2')
    expect(calls[0]).not.toContain('scenario=')
  })

  it('响应不是数组时抛错，绝不退化成「暂无场景」的空下拉', async () => {
    reply(200, { tasks: [] })
    await expect(fetchMetricScenarios(24)).rejects.toBeInstanceOf(ApiError)
    await expect(fetchMetricScenarios(24)).rejects.toThrow('不是数组')
  })

  it('正常响应按 value/label/traceCount 返回', async () => {
    reply(200, [{ value: '(unlabeled)', label: '未标注', traceCount: 3 }])
    await expect(fetchMetricScenarios(24)).resolves.toEqual([
      { value: '(unlabeled)', label: '未标注', traceCount: 3 },
    ])
  })
})
