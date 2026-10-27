// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'
import { AgentMetricsPage } from '@/pages/metrics/AgentMetricsPage'
import type { AgentMetricOverview } from '@/api/metricsApi'

/**
 * Agent 效果指标看板的治理纪律测试（P7）。
 *
 * ⚠️ 这些断言守的是**口径纪律**而不是渲染细节：
 *    · 未采集必须显示「—（未采集）」，绝不显示 0 —— 否则「没测过」会被读成「没幻觉」；
 *    · 样本量 < 30 的抽样指标必须显示「样本不足」，不能给出百分比；
 *    · 「链路成功 ≠ 任务成功」的口径警告必须在页面上可见；
 *    · 用户反馈必须标注「不判因果」，避免差评率被直接读成 Agent 劣化。
 */

vi.mock('@/api/metricsApi', async () => {
  const actual = await vi.importActual<typeof import('@/api/metricsApi')>('@/api/metricsApi')
  return {
    ...actual,
    fetchAgentMetricOverview: vi.fn(),
    fetchTerminationDistribution: vi.fn(),
    fetchLatency: vi.fn(),
    fetchToolBreakdown: vi.fn(),
    fetchAnomalies: vi.fn(),
  }
})

import {
  fetchAgentMetricOverview,
  fetchTerminationDistribution,
  fetchLatency,
  fetchToolBreakdown,
  fetchAnomalies,
} from '@/api/metricsApi'

/**
 * 基线总览：刻意让效果 / 反馈 / 风险三类全为 null ——
 * 这正是 P7-1 的真实状态（尚无采集来源），测试必须反映真实而不是理想。
 */
function overview(patch: Partial<AgentMetricOverview> = {}): AgentMetricOverview {
  return {
    traceCount: 120,
    succeededCount: 108,
    linkSuccessRate: 0.9,
    avgDurationMs: 4200,
    inputTokens: 1_200_000,
    outputTokens: 300_000,
    costAmount: 42.5,
    avgCostPerTrace: 0.354,
    oversizedTokenRate: 0.008,
    avgToolRounds: 3.2,
    maxRoundsReachedRate: 0.04,
    invalidCallRate: 0.06,
    loopSuspectedTraces: 2,
    autoCheckedCount: null,
    autoVerifiedCount: null,
    autoVerifiedRate: null,
    autoCheckCoverage: null,
    sampledCount: null,
    sampledSuccessRate: null,
    falseSuccessRate: null,
    hallucinationRate: null,
    instructionFollowRate: null,
    feedbackUpCount: null,
    feedbackDownCount: null,
    thumbUpRate: null,
    abandonRate: null,
    safetyBlockedTraces: 0,
    unauthorizedTraces: 0,
    sensitiveOutputTraces: 0,
    refreshedAt: '2026-10-27T01:00:00Z',
    ...patch,
  }
}

function stubDefaults(): void {
  vi.mocked(fetchTerminationDistribution).mockResolvedValue([
    { reason: 'COMPLETED', count: 100 },
    { reason: 'MODEL_ERROR', count: 12 },
    { reason: 'OTHER', count: 8 },
  ])
  vi.mocked(fetchLatency).mockResolvedValue({ p50: 3000, p95: 9000, p99: 15000 })
  vi.mocked(fetchToolBreakdown).mockResolvedValue([
    { toolName: 'search', callCount: 300, failedCount: 12, failRate: 0.04, avgDurationMs: 220, maxDurationMs: 1800 },
  ])
  vi.mocked(fetchAnomalies).mockResolvedValue([])
}

function renderPage(): void {
  render(
    <MemoryRouter>
      <AntApp>
        <AgentMetricsPage />
      </AntApp>
    </MemoryRouter>,
  )
}

/** 去空白：antd 会在两个 CJK 字符间插空格，直接比对文本会失败。 */
function norm(text: string | null | undefined): string {
  return (text ?? '').replace(/\s+/g, '')
}

/**
 * 按标题取某个 Statistic 的显示值。
 *
 * ⚠️ 必须按标题定位而不是全文 `not.toContain('0.0%')` ——
 *    页面里「12（10.0%）」这类正常数值同样含有 `0.0%` 子串，
 *    宽泛断言会误伤，也会在真出问题时给出误导性的失败位置。
 */
function statisticValue(title: string): string {
  const nodes = Array.from(document.querySelectorAll('.ant-statistic'))
  const hit = nodes.find(
    (n) => norm(n.querySelector('.ant-statistic-title')?.textContent) === norm(title),
  )
  return norm(hit?.querySelector('.ant-statistic-content')?.textContent)
}

describe('Agent 效果指标看板 · 口径纪律', () => {
  beforeEach(() => {
    stubDefaults()
    vi.mocked(fetchAgentMetricOverview).mockResolvedValue(overview())
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('未采集的指标显示「—」而不是 0', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('自动校验成功率').length).toBeGreaterThan(0)
    })
    // 「未采集」的语义：空值一律显示 —，绝不落成 0.0%
    expect(statisticValue('自动校验成功率')).toBe('—')
    expect(statisticValue('假成功占比')).toBe('—')
    expect(statisticValue('好评率')).toBe('—')
    expect(statisticValue('中途放弃率')).toBe('—')
    expect(screen.getAllByText('未接入外部校验器 —— 这里是「没测过」，不是 0%').length).toBeGreaterThan(0)
  })

  it('样本量不足 30 时显示「样本不足」，不给出百分比', async () => {
    vi.mocked(fetchAgentMetricOverview).mockResolvedValue(
      overview({ sampledCount: 12, sampledSuccessRate: 0.5, hallucinationRate: 0.25 }),
    )
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('样本不足').length).toBeGreaterThan(0)
    })
    // ⚠️ n=12 时的 50% 是噪声，页面不得把它渲染出来
    expect(statisticValue('抽样标注成功率（黄金基线）')).not.toContain('50.0%')
    expect(statisticValue('幻觉率')).toBe('样本不足')
  })

  it('样本量达标时正常显示百分比', async () => {
    vi.mocked(fetchAgentMetricOverview).mockResolvedValue(
      overview({ sampledCount: 40, sampledSuccessRate: 0.75, hallucinationRate: 0.1 }),
    )
    renderPage()
    await waitFor(() => {
      expect(statisticValue('抽样标注成功率（黄金基线）')).toContain('75.0%')
    })
    expect(statisticValue('幻觉率')).toContain('10.0%')
    expect(screen.getAllByText(/n=40/).length).toBeGreaterThan(0)
  })

  it('链路成功率必须带出「≠ 任务成功」的口径警告', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('链路成功率').length).toBeGreaterThan(0)
    })
    expect(screen.getAllByText(/框架未崩溃 ≠ 任务成功/).length).toBeGreaterThan(0)
  })

  it('用户反馈分区必须标注「不判因果」', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText(/只看趋势，不判因果/).length).toBeGreaterThan(0)
    })
    expect(screen.getAllByText(/触发抽样复核/).length).toBeGreaterThan(0)
  })

  it('终止原因分布合计与总数一致，且「其他」不消失', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('终止原因分布（根因分析核心）').length).toBeGreaterThan(0)
    })
    expect(norm(document.body.textContent)).toContain('合计120')
    expect(screen.getAllByText('其他（未细分）').length).toBeGreaterThan(0)
  })

  it('平均工具轮次不再标注退化近似（P7-2 已切换为 run_step 真实口径）', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('平均工具轮次').length).toBeGreaterThan(0)
    })
    // 退化近似标注已移除；真实口径下旧 Run 以「未采集」呈现
    // ⚠️ 负向断言必须用 queryAllByText：getAllByText 在零匹配时会抛错而非返回空数组
    expect(screen.queryAllByText(/退化近似/).length).toBe(0)
    expect(screen.getAllByText(/来自 run_step 真实埋点/).length).toBeGreaterThan(0)
  })

  it('指标不可用时不伪装成健康', async () => {
    vi.mocked(fetchAgentMetricOverview).mockRejectedValue(new Error('网络错误'))
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('指标不可用').length).toBeGreaterThan(0)
    })
    expect(screen.getAllByText(/不要因为看板空白就认为没问题/).length).toBeGreaterThan(0)
  })
})
