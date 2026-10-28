// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

/**
 * 路由策略页（A7-1 / A7-1b）的行为锁定测试。
 *
 * <p><b>只 mock 传输层，不 mock `routingPolicyApi`</b>：让码值映射、
 * 401/403 与 429 分类这些真实代码也进入断言范围 —— 它们正是
 * 「界面把没跑成显示成没问题」会出错的地方。
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, request: vi.fn() }
})
vi.mock('@/api/adminApi', () => ({
  submitPublishRequest: vi.fn(),
}))

import { request, ApiError } from '@/api/client'
import { submitPublishRequest } from '@/api/adminApi'
import { RoutingPolicyPage } from '@/pages/catalog/RoutingPolicyPage'
import type { RoutingDryRunResult } from '@/api/routingPolicyApi'

const mockedRequest = vi.mocked(request)

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

beforeEach(() => {
  mockedRequest.mockReset()
  vi.mocked(submitPublishRequest).mockReset()
})

function renderPage() {
  return render(
    <MemoryRouter>
      <AntApp>
        <RoutingPolicyPage />
      </AntApp>
    </MemoryRouter>,
  )
}

/** 打开表单里第 index 个 antd Select 并选中指定 label 的选项（portal 渲染）。 */
async function pickOption(index: number, optionLabel: string): Promise<void> {
  const combobox = screen.getAllByRole('combobox')[index]!
  fireEvent.mouseDown(combobox, { button: 0 })
  const option = await screen.findByText(optionLabel, {
    selector: '.ant-select-item-option-content',
  })
  fireEvent.click(option)
}

/** 策略标识是行级必填；试跑不要求「变更理由」（理由属于申请单）。 */
function fillPolicyId(value = 'order-routing'): void {
  fireEvent.change(screen.getByLabelText(/策略标识/), { target: { value } })
}

const DRY_RUN_RESULT: RoutingDryRunResult = {
  parsed: true,
  errorWhere: null,
  errorMessage: null,
  publishable: false,
  violations: [
    { code: 'V9', message: '目标 refund-agent 未被任何样本命中', warning: false },
    { code: 'V3', message: '规则 r-2 被 r-1 完全遮蔽', warning: false },
  ],
  warnings: [{ code: 'V7', message: 'DNF 展开接近上限', warning: true }],
  probes: [
    {
      label: '退货探针',
      projectedInput: { text: '怎么退货' },
      decided: true,
      mode: 'RULES_ONLY',
      resolution: 'TARGET_NOT_ALLOWED',
      finalTarget: null,
      matchedRuleId: 'r-1',
      candidateFromModel: null,
      conflict: false,
      error: null,
      trace: [
        { ordinal: 0, ruleId: 'r-1', outcome: 'MATCH', error: null },
        { ordinal: 1, ruleId: 'r-2', outcome: 'NO_MATCH', error: null },
      ],
      issueWhere: null,
      issueMessage: null,
    },
  ],
  replayDate: '2026-10-28',
}

describe('管理台 · 路由策略登记与试跑（A7-1 / A7-1b）', () => {
  it('✅ 渲染策略标识、三段策略文本与探针字段', () => {
    renderPage()
    expect(screen.getByLabelText(/策略标识/)).toBeTruthy()
    expect(screen.getByLabelText(/输入词汇表/)).toBeTruthy()
    expect(screen.getByLabelText(/有序规则列表/)).toBeTruthy()
    expect(screen.getByLabelText(/回放样本/)).toBeTruthy()
    expect(screen.getByLabelText(/试跑探针/)).toBeTruthy()
  })

  it('✅ 试跑把三段文本按**原文逐字**送后端（不重新序列化）', async () => {
    mockedRequest.mockResolvedValue(DRY_RUN_RESULT)
    const raw = '{\n  "type":"object",  "properties": {"b":{"type":"string"},"a":{"type":"string"}}\n}'

    renderPage()
    fireEvent.change(screen.getByLabelText(/策略标识/), { target: { value: 'order-routing' } })
    fireEvent.change(screen.getByLabelText(/输入词汇表/), { target: { value: raw } })
    fireEvent.click(screen.getByRole('button', { name: /试跑/ }))

    await waitFor(() => expect(mockedRequest).toHaveBeenCalledTimes(1))
    expect(mockedRequest.mock.calls[0]![0]).toBe('/cap/config/routing-policies/dry-run')
    const body = JSON.parse(String(mockedRequest.mock.calls[0]![1]!.body)) as {
      policy: { policyId: string; inputSchema: string }
    }
    // ⚠️ 逐字相等：改写空白与键序等于每次发布都声称「我改过」（内容指纹按行算）
    expect(body.policy.inputSchema).toBe(raw)
    expect(body.policy.policyId).toBe('order-routing')
  })

  it('✅ 回显守卫结论、稳定码、投影后的输入与回放日', async () => {
    mockedRequest.mockResolvedValue(DRY_RUN_RESULT)
    renderPage()
    fillPolicyId()
    fireEvent.click(screen.getByRole('button', { name: /试跑/ }))

    await screen.findByText(/守卫拦下 2 条/)
    expect(screen.getByText(/DNF 展开接近上限/)).toBeTruthy()
    // 稳定码要翻成人话，但码值本身不能被替换掉
    expect(screen.getByText('拓扑过了、策略没收')).toBeTruthy()
    expect(screen.getByText('2026-10-28')).toBeTruthy()
    // 投影后的输入：词汇表外的字段（写错的字段名）在这里看不见，是唯一的可见证据
    expect(screen.getByText('{"text":"怎么退货"}')).toBeTruthy()
    expect(screen.queryByText(/topicc/)).toBeNull()
  })

  it('✅ 展开探针行 → 渲染求值轨迹（只含实际判过的规则）', async () => {
    mockedRequest.mockResolvedValue(DRY_RUN_RESULT)
    const { container } = renderPage()
    fillPolicyId()
    fireEvent.click(screen.getByRole('button', { name: /试跑/ }))
    await screen.findByText('退货探针')

    const expander = container.querySelector('.ant-table-row-expand-icon')
    expect(expander).not.toBeNull()
    fireEvent.click(expander!)

    await screen.findByText(/求值轨迹/)
    // 轨迹只含实际判过的规则：r-2 不在任何列里，只可能来自轨迹
    expect(container.textContent).toContain('r-2')
    expect(container.textContent).toContain('未命中')
    expect(container.textContent).toContain('命中')
  })

  it('⛔ 平台令牌打不通租户域（403）→ 明示「需租户级令牌通道」，不得显示成有结论', async () => {
    mockedRequest.mockRejectedValue(new ApiError(403, 'forbidden'))
    renderPage()
    fillPolicyId()
    fireEvent.click(screen.getByRole('button', { name: /试跑/ }))

    await screen.findByText(/租户级令牌通道/)
    expect(screen.queryByText(/现在提交可放行/)).toBeNull()
  })

  it('✅ 试跑不要求「变更理由」（理由属于申请单，dry-run 不产生申请单）', async () => {
    mockedRequest.mockResolvedValue(DRY_RUN_RESULT)
    renderPage()
    fillPolicyId()
    expect(screen.getByLabelText(/变更理由/)).toHaveProperty('value', '')
    fireEvent.click(screen.getByRole('button', { name: /试跑/ }))
    await waitFor(() => expect(mockedRequest).toHaveBeenCalledTimes(1))
  })

  it('⛔ 探针不是合法 JSON → 当场拒，不发请求（结构性预检，不碰文法）', async () => {
    renderPage()
    fillPolicyId()
    fireEvent.change(screen.getByLabelText(/试跑探针/), { target: { value: '[ 这不是 JSON' } })
    fireEvent.click(screen.getByRole('button', { name: /试跑/ }))

    await waitFor(() => expect(screen.getByText(/探针列表（probes） 不是合法 JSON/)).toBeTruthy())
    expect(mockedRequest).not.toHaveBeenCalled()
  })

  it('⛔ DEFAULT_TARGET 但兜底目标为空 → 当场拒（与后端行级校验同口径）', async () => {
    renderPage()
    fillPolicyId()
    await pickOption(1, 'DEFAULT_TARGET')
    fireEvent.click(screen.getByRole('button', { name: /试跑/ }))

    await waitFor(() => expect(screen.getByText(/必须填写兜底目标/)).toBeTruthy())
    expect(mockedRequest).not.toHaveBeenCalled()
  })

  it('✅ 提交走三步工作流的申请（策略放 routingPolicies，且不带版本号与指纹）', async () => {
    vi.mocked(submitPublishRequest).mockResolvedValue({
      requestId: 'req-1234567890',
      operation: 'PUBLISH',
      state: 'PENDING',
      requesterId: 'u-1',
      approverId: null,
      decisionNote: null,
      requestedAt: '2026-10-28T00:00:00Z',
      decidedAt: null,
      appliedAt: null,
    } as never)

    renderPage()
    fireEvent.change(screen.getByLabelText(/策略标识/), { target: { value: 'order-routing' } })
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '登记退货策略' } })
    fireEvent.click(screen.getByRole('button', { name: /提交登记申请/ }))

    await waitFor(() => expect(submitPublishRequest).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(submitPublishRequest).mock.calls[0]![0]
    expect(payload.operation).toBe('PUBLISH')
    expect(payload.scope).toBe('TENANT')
    expect(payload.agents).toBeUndefined()
    expect(payload.routingPolicies).toHaveLength(1)
    expect(payload.routingPolicies?.[0]?.policyId).toBe('order-routing')
    // ⚠️ 版本号与内容指纹由服务端决定：客户端若能指定它们，
    //    就等于能指定「内容没变」这个证明
    expect(JSON.stringify(payload)).not.toMatch(/"version"/)
    expect(JSON.stringify(payload)).not.toMatch(/fingerprint/i)
    // 申请单已生成 ≠ 配置已改变：界面必须给 requestId 并说明后两步
    await screen.findByText(/申请单已生成，配置尚未改变/)
    expect(within(screen.getByText(/申请单已生成，配置尚未改变/).closest('.ant-alert')!).
      getByText('req-1234567890')).toBeTruthy()
  })
})
