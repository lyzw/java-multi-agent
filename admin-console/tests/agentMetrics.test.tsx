// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react'
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
    fetchMetricScenarios: vi.fn(),
  }
})

import {
  fetchAgentMetricOverview,
  fetchTerminationDistribution,
  fetchLatency,
  fetchToolBreakdown,
  fetchAnomalies,
  fetchMetricScenarios,
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
  vi.mocked(fetchMetricScenarios).mockResolvedValue([
    { value: '数学:一元二次方程', label: '数学:一元二次方程', traceCount: 2 },
    { value: '(unlabeled)', label: '未标注', traceCount: 1 },
  ])
}

/**
 * 打开页面顶部的场景 Select 并选中指定 label 的选项。
 * ⚠️ antd v5 必须先 mouseDown 才展开，选项渲染在 body 下的 portal 里。
 */
async function pickScenario(label: string): Promise<void> {
  const combobox = screen.getAllByRole('combobox')[0]!
  fireEvent.mouseDown(combobox, { button: 0 })
  const option = await screen.findByText(label, { selector: '.ant-select-item-option-content' })
  fireEvent.click(option)
}

/** 取某个 fetch 最近一次调用的参数（切换筛选后会重新拉一次）。 */
function lastArgs(mock: { calls: unknown[][] }): unknown[] {
  return mock.calls.at(-1) ?? []
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

/**
 * ⚠️ 钩子必须在**文件顶层**，不能放进某个 describe：本文件有两个并列 describe，
 *    放进去的话第二个 describe 不继承 beforeEach/afterEach —— mock 没初始化、
 *    DOM 与调用记录跨用例累积，症状是「指标不可用」和「调用次数 2/3」这种
 *    看起来像产品缺陷、实际是测试脚手架漏件的假红。
 */
beforeEach(() => {
  stubDefaults()
  vi.mocked(fetchAgentMetricOverview).mockResolvedValue(overview())
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('Agent 效果指标看板 · 口径纪律', () => {
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

describe('场景筛选 · 整页同口径与失真防护', () => {
  const FILTER = { scenario: '数学:一元二次方程' }

  it('选中场景后五个读端点都收到同一 filter，页面显式标注统计范围', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('自动校验成功率').length).toBeGreaterThan(0)
    })

    await pickScenario('数学:一元二次方程（2）')

    await waitFor(() => {
      expect(lastArgs(vi.mocked(fetchAgentMetricOverview).mock)[1]).toEqual(FILTER)
    })
    // ⚠️ 逐端点断言：漏带任意一个端点都会精确指出是哪一个
    expect(lastArgs(vi.mocked(fetchTerminationDistribution).mock)[1]).toEqual(FILTER)
    expect(lastArgs(vi.mocked(fetchLatency).mock)[1]).toEqual(FILTER)
    expect(lastArgs(vi.mocked(fetchToolBreakdown).mock)[1]).toEqual(FILTER)
    expect(lastArgs(vi.mocked(fetchAnomalies).mock)[2]).toEqual(FILTER)
    expect(screen.getAllByText(/当前统计范围：场景 = 数学:一元二次方程/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/终止原因分布、耗时分位、工具维度、异常明细/).length).toBeGreaterThan(0)
  })

  it('切换场景不重拉枚举（筛选器不自锁，选了 A 仍能切回 B）', async () => {
    renderPage()
    await waitFor(() => expect(vi.mocked(fetchMetricScenarios).mock.calls).toHaveLength(1))

    await pickScenario('未标注（1）')

    await waitFor(() => {
      expect(lastArgs(vi.mocked(fetchAgentMetricOverview).mock)[1])
        .toEqual({ scenario: '(unlabeled)' })
    })
    expect(vi.mocked(fetchMetricScenarios).mock.calls).toHaveLength(1)
    // 「未标注」显示后端给的可读 label，不把哨兵原文丢给审查者
    expect(screen.getAllByText(/当前统计范围：场景 = 未标注/).length).toBeGreaterThan(0)
  })

  it('切换时间窗会重新拉场景枚举，并把新 hours 传下去', async () => {
    renderPage()
    await waitFor(() => expect(vi.mocked(fetchMetricScenarios).mock.calls).toHaveLength(1))

    fireEvent.click(screen.getByText('近 7 天'))

    await waitFor(() => expect(vi.mocked(fetchMetricScenarios).mock.calls).toHaveLength(2))
    expect(lastArgs(vi.mocked(fetchMetricScenarios).mock)[0]).toBe(168)
    expect(lastArgs(vi.mocked(fetchAgentMetricOverview).mock)[0]).toBe(168)
  })

  it('场景枚举失败给出可操作错误，不伪装成「暂无场景」，指标仍可看', async () => {
    vi.mocked(fetchMetricScenarios).mockRejectedValue(new Error('网络错误'))
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('场景筛选器不可用').length).toBeGreaterThan(0)
    })
    expect(screen.getAllByText(/取不到场景列表/).length).toBeGreaterThan(0)
    // 筛选器挂了 ≠ 指标挂了：不能整页变成「指标不可用」
    expect(screen.getAllByText('自动校验成功率').length).toBeGreaterThan(0)
    expect(screen.queryAllByText('指标不可用')).toHaveLength(0)
  })
})
