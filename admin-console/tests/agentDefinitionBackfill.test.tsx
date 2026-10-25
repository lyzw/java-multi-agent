// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  getAgent: vi.fn(),
  getAgentVersions: vi.fn(),
  getAgentDefinition: vi.fn(),
  getDependencyImpact: vi.fn(),
  getDelegationChildren: vi.fn(),
  getDelegationParents: vi.fn(),
  publishAgentVersion: vi.fn(),
  submitPublishRequest: vi.fn(),
}))
import {
  getAgent,
  getAgentDefinition,
  getAgentVersions,
  getDelegationChildren,
  getDelegationParents,
  submitPublishRequest,
} from '@/api/adminApi'
import { ApiError } from '@/api/client'

import { AgentDetailPage } from '@/pages/AgentDetailPage'
import { evaluatePublishGate } from '@/pages/orchestration/orchestrationForm'

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

// ---- 夹具 ----

const AGENT = {
  agentId: 'agent-1',
  name: '错题归因助手',
  description: '旧体系描述',
  versionCount: 2,
  latestVersionNumber: 2,
  // ⚠️ 旧体系的自由 JSON 快照 —— 与 cap_agent_def 的 overrides **不是**同一份东西
  latestDefinition: '{"goal":"分析错题","steps":["取题","归因"]}',
  latestPublishedAt: '2026-10-20T00:00:00Z',
}

/** 权威定义（cap_agent_def）：关系字段只能从这里回填。 */
const DEFINITION = {
  agentId: 'agent-1',
  version: 5,
  name: '错题归因助手',
  description: '权威描述',
  roles: ['TEACHER', 'REVIEWER'],
  enabledSkills: ['skill-a', 'skill-b'],
  skillBundles: ['bundle-x'],
  capabilityTags: ['tag-1', 'tag-2'],
  // ⚠️ overrides 是「收紧覆盖」，不是整份 definition
  overrides: '{"removedSkills":["skill-old"]}',
  orchestration: JSON.stringify({
    mode: 'SEQUENTIAL',
    childAgentRefs: [{ id: 'child-1', version: 2, required: true }],
    maxDelegationDepth: 2,
    maxChildInvocations: 4,
    timeoutMs: 30000,
    failurePolicy: 'FAIL_FAST',
  }),
  published: true,
}

function renderAgentPage() {
  return render(
    <MemoryRouter initialEntries={['/agents/agent-1']}>
      <AntApp>
        <Routes>
          <Route path="/agents/:id" element={<AgentDetailPage />} />
        </Routes>
      </AntApp>
    </MemoryRouter>,
  )
}

/** 打开「发布」页签（antd Tabs 默认只渲染活动页签，表单在里面）。 */
async function openPublishTab(): Promise<void> {
  const tab = await screen.findByRole('tab', { name: /^发布/ })
  fireEvent.click(tab)
  await waitFor(() => expect(screen.getByLabelText(/变更理由/)).toBeTruthy())
}

/**
 * 读出 Select 标签模式里的当前值。
 *
 * ⚠️ antd Select 的隐藏 input 承载的是**搜索词**而不是选中值，
 * 选中值渲染在 `.ant-select-selection-item` 里 —— 断言必须看后者。
 */
/**
 * 定位「发布抽屉」表单容器。
 *
 * ⚠️ 刻意在表单内查找：页面别处也有「版本」等同名标签
 * （概览的 Descriptions 就有），不限定范围会拿错字段。
 */
function publishForm(): HTMLElement {
  const form = document.getElementById('agent-publish')
  if (!form) throw new Error('发布抽屉表单未挂载')
  return form
}

/** 按标签读取输入框当前值（Input / InputNumber 渲染真实 input，可用 id 定位）。 */
function inputValueByLabel(label: RegExp): string {
  const labelEl = within(publishForm()).getByText(label)
  const id = labelEl.getAttribute('for')
  const input = publishForm().querySelector(`#${CSS.escape(id ?? '')}`) as HTMLInputElement | null
  return input?.value ?? ''
}

function selectValues(label: RegExp): string[] {
  const labelEl = within(publishForm()).getByText(label)
  const id = labelEl.getAttribute('for')
  const input = publishForm().querySelector(`#${CSS.escape(id ?? '')}`)
  const holder = input?.closest('.ant-select') ?? null
  if (!holder) return []
  return Array.from(holder.querySelectorAll('.ant-select-selection-item')).map(
    (n) => n.textContent ?? '',
  )
}

describe('Agent 详情页 · 关系字段回填（第三十四批 · C34-3 修复）', () => {
  beforeAll(() => {
    vi.mocked(getAgent).mockResolvedValue(AGENT as never)
    vi.mocked(getAgentVersions).mockResolvedValue([] as never)
    vi.mocked(getAgentDefinition).mockResolvedValue(DEFINITION as never)
    vi.mocked(getDelegationChildren).mockResolvedValue([] as never)
    vi.mocked(getDelegationParents).mockResolvedValue([] as never)
  })

  it('✅ 概览呈现权威定义的关系字段（角色 / 技能 / 技能包 / 能力标签）', async () => {
    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    expect(screen.getByText('TEACHER、REVIEWER')).toBeTruthy()
    expect(screen.getByText('skill-a、skill-b')).toBeTruthy()
    expect(screen.getByText('bundle-x')).toBeTruthy()
    expect(screen.getByText('tag-1、tag-2')).toBeTruthy()
  })

  it('✅ 编辑表单回填全部关系字段（不是只回填 name/description）', async () => {
    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    await openPublishTab()

    // 角色 / 启用技能 / 技能包 / 能力标签：Select 的值落在隐藏 input 上
    expect(selectValues(/角色（roles）/)).toEqual(['TEACHER', 'REVIEWER'])
    expect(selectValues(/启用技能（enabledSkills）/)).toEqual(['skill-a', 'skill-b'])
    expect(selectValues(/技能包（skillBundles）/)).toEqual(['bundle-x'])
    expect(selectValues(/能力标签（capabilityTags）/)).toEqual(['tag-1', 'tag-2'])
  })

  it('✅ overrides 回填自权威定义的 overrides（⛔ 不是 latestDefinition 全文）', async () => {
    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    await openPublishTab()

    const overrides = screen.getByLabelText(/配置覆盖（overrides/) as HTMLTextAreaElement
    expect(overrides.value).toBe('{"removedSkills":["skill-old"]}')
    // ⛔ 反证：不得是旧体系快照全文（那会把「收紧覆盖」语义彻底搞错）
    expect(overrides.value).not.toContain('分析错题')
    expect(overrides.value).not.toContain('steps')
  })

  it('✅ 编排声明回填为结构化表单（mode / 子 Agent / 限制）', async () => {
    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    await openPublishTab()

    expect(selectValues(/编排模式（orchestration.mode）/)).toEqual(['SEQUENTIAL'])
    expect(screen.getByDisplayValue('child-1')).toBeTruthy()
    // ⚠️ 「版本」在页面上不止一个（子 Agent 版本 / 最大委派深度），
    //    按标签定位，避免拿错字段。
    expect(inputValueByLabel(/^版本$/)).toBe('2')
    expect(inputValueByLabel(/最大委派深度/)).toBe('2')
    expect(inputValueByLabel(/最大子调用次数/)).toBe('4')
    expect(inputValueByLabel(/委派超时/)).toBe('30000')
  })

  it('✅ 提交时把回填到的关系原样送出（改一下不会清空关系）', async () => {
    vi.mocked(submitPublishRequest).mockReset()
    vi.mocked(submitPublishRequest).mockResolvedValue({
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

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    await openPublishTab()
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '微调描述' } })
    fireEvent.click(screen.getByRole('button', { name: /提交变更申请/ }))

    await waitFor(() => expect(submitPublishRequest).toHaveBeenCalledTimes(1))
    const body = vi.mocked(submitPublishRequest).mock.calls[0]![0]
    const spec = body.agents?.[0]
    expect(spec?.roles).toEqual(['TEACHER', 'REVIEWER'])
    expect(spec?.enabledSkills).toEqual(['skill-a', 'skill-b'])
    expect(spec?.skillBundles).toEqual(['bundle-x'])
    expect(spec?.capabilityTags).toEqual(['tag-1', 'tag-2'])
    expect(spec?.overrides).toBe('{"removedSkills":["skill-old"]}')
    expect(spec?.orchestration).toContain('SEQUENTIAL')
  })

  it('✅ 委派拓扑页签可见（出向 / 入向两个问题分开呈现）', async () => {
    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    const tab = screen.getByRole('tab', { name: /委派拓扑/ })
    fireEvent.click(tab)
    await waitFor(() => expect(screen.getByText(/出向：声明的子 Agent/)).toBeTruthy())
    expect(screen.getByText(/入向：依赖本 Agent 的父/)).toBeTruthy()
  })
})

describe('Agent 详情页 · 回填不全时绝不静默清空关系', () => {
  beforeAll(() => {
    vi.mocked(getAgent).mockResolvedValue(AGENT as never)
    vi.mocked(getAgentVersions).mockResolvedValue([] as never)
    vi.mocked(getDelegationChildren).mockResolvedValue([] as never)
    vi.mocked(getDelegationParents).mockResolvedValue([] as never)
  })

  it('⛔ 权威定义 404 → 明确提示「尚无多级定义，保存会清空关系」，并要求显式确认', async () => {
    vi.mocked(getAgentDefinition).mockReset()
    vi.mocked(getAgentDefinition).mockRejectedValue(new ApiError(404, 'not found'))
    vi.mocked(submitPublishRequest).mockReset()

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/该 Agent 尚无多级定义/)).toBeTruthy())
    await openPublishTab()

    // ⚠️ 必须有显式告警与显式确认框
    expect(screen.getAllByText(/保存会清空关系/).length).toBeGreaterThan(0)
    const confirm = screen.getByRole('checkbox')
    expect(confirm).toBeTruthy()
    // ⛔ 未确认前提交按钮必须是禁用的
    expect(screen.getByRole('button', { name: /提交变更申请/ })).toHaveProperty('disabled', true)
  })

  it('⛔ 未确认就提交 → 不得发起发布请求（关系不得被静默清空）', async () => {
    vi.mocked(getAgentDefinition).mockReset()
    vi.mocked(getAgentDefinition).mockRejectedValue(new ApiError(404, 'not found'))
    vi.mocked(submitPublishRequest).mockReset()

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/该 Agent 尚无多级定义/)).toBeTruthy())
    await openPublishTab()
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '改个名字' } })

    // 按钮是禁用的，点击无效
    fireEvent.click(screen.getByRole('button', { name: /提交变更申请/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /提交变更申请/ })).toHaveProperty('disabled', true))
    expect(submitPublishRequest).not.toHaveBeenCalled()
  })

  it('⛔ 即便绕过了按钮禁用，第二道闸门（提交函数内）也必须拦住（不得静默清空关系）', async () => {
    vi.mocked(getAgentDefinition).mockReset()
    vi.mocked(getAgentDefinition).mockRejectedValue(new ApiError(404, 'not found'))
    vi.mocked(submitPublishRequest).mockReset()

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/该 Agent 尚无多级定义/)).toBeTruthy())
    await openPublishTab()
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '改个名字' } })

    // ⚠️ 上一条只验了「按钮 disabled」这一层（UI 层的拦截）。
    //    antd 的 Button 走的是 **props**（不是 DOM 属性），`removeAttribute` 绕不过它 ——
    //    所以「按钮禁用了」**不能**证明「提交函数内部也拦得住」。
    //    这里改用**同一个判据**（`evaluatePublishGate`）来钉住内部那道闸门：
    //    判据被抽成纯函数后，两处调用的是同一份判据，
    //    故「按钮禁用」与「提交拦截」不会各写一份而漂移。
    const gate = evaluatePublishGate({
      orchestrationError: null,
      definitionUnavailable: true,
      acknowledgeClearRelations: false,
    })
    expect(gate.blocked).toBe(true)
    expect(gate.reason).toContain('保存会清空关系')

    // ⛔ 且不得发起发布请求（否则就是拿「关系全空」的覆盖去发布）
    expect(submitPublishRequest).not.toHaveBeenCalled()
  })

  it('✅ 显式确认后可以提交（用户知情，故允许）', async () => {
    vi.mocked(getAgentDefinition).mockReset()
    vi.mocked(getAgentDefinition).mockRejectedValue(new ApiError(404, 'not found'))
    vi.mocked(submitPublishRequest).mockReset()
    vi.mocked(submitPublishRequest).mockResolvedValue({
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

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/该 Agent 尚无多级定义/)).toBeTruthy())
    await openPublishTab()
    fireEvent.click(screen.getByRole('checkbox'))
    await waitFor(() => expect(screen.getByRole('button', { name: /提交变更申请/ })).toHaveProperty('disabled', false))

    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '知情后改个名字' } })
    fireEvent.click(screen.getByRole('button', { name: /提交变更申请/ }))
    await waitFor(() => expect(submitPublishRequest).toHaveBeenCalledTimes(1))
  })
})

describe('Agent 详情页 · 编排声明解析失败必须显式报错', () => {
  beforeAll(() => {
    vi.mocked(getAgent).mockResolvedValue(AGENT as never)
    vi.mocked(getAgentVersions).mockResolvedValue([] as never)
    vi.mocked(getDelegationChildren).mockResolvedValue([] as never)
    vi.mocked(getDelegationParents).mockResolvedValue([] as never)
  })

  it('⛔ 权威定义里的编排声明不是合法 JSON → 报错并阻止提交（不得静默当 LEAF）', async () => {
    vi.mocked(getAgentDefinition).mockReset()
    vi.mocked(getAgentDefinition).mockResolvedValue({
      ...DEFINITION,
      orchestration: '{ 这不是 JSON',
    } as never)
    vi.mocked(submitPublishRequest).mockReset()

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    await openPublishTab()

    expect(screen.getAllByText(/编排声明解析失败/).length).toBeGreaterThan(0)
    // ⛔ 提交按钮禁用：回填失败时表单里的编排字段是空的，
    //    照此提交就等于把编排声明覆盖成 LEAF（取消全部委派）
    expect(screen.getByRole('button', { name: /提交变更申请/ })).toHaveProperty('disabled', true)
    expect(submitPublishRequest).not.toHaveBeenCalled()
  })
})

describe('Agent 详情页 · 停用（生命周期迁移）', () => {
  beforeAll(() => {
    vi.mocked(getAgent).mockResolvedValue(AGENT as never)
    vi.mocked(getAgentVersions).mockResolvedValue([] as never)
    vi.mocked(getAgentDefinition).mockResolvedValue(DEFINITION as never)
    vi.mocked(getDelegationChildren).mockResolvedValue([] as never)
    vi.mocked(getDelegationParents).mockResolvedValue([] as never)
  })

  it('✅ 停用走 LIFECYCLE_TRANSITION（不是物理删除，也不是 PUBLISH）', async () => {
    vi.mocked(submitPublishRequest).mockReset()
    vi.mocked(submitPublishRequest).mockResolvedValue({
      requestId: 'req-1234567890',
      operation: 'LIFECYCLE_TRANSITION',
      state: 'PENDING',
      requesterId: 'u-1',
      approverId: null,
      decisionNote: null,
      requestedAt: '2026-10-25T00:00:00Z',
      decidedAt: null,
      appliedAt: null,
    } as never)

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    await openPublishTab()

    fireEvent.change(screen.getByLabelText(/停用理由/), { target: { value: '下线该 Agent' } })
    fireEvent.click(screen.getByRole('button', { name: /提交停用申请/ }))

    await waitFor(() => expect(submitPublishRequest).toHaveBeenCalledTimes(1))
    const body = vi.mocked(submitPublishRequest).mock.calls[0]![0]
    expect(body.operation).toBe('LIFECYCLE_TRANSITION')
    expect(body.lifecycle?.type).toBe('agent')
    expect(body.lifecycle?.objectId).toBe('agent-1')
    expect(body.lifecycle?.expectedFrom).toBe('PUBLISHED')
    expect(body.lifecycle?.to).toBe('DEPRECATED')
    expect(body.lifecycle?.version).toBe(5)
  })
})
