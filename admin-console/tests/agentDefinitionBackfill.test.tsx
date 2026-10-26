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
  createAgent: vi.fn(),
  getAgentList: vi.fn(),
}))
import {
  createAgent,
  getAgent,
  getAgentList,
  getAgentDefinition,
  getAgentVersions,
  getDelegationChildren,
  getDelegationParents,
  submitPublishRequest,
} from '@/api/adminApi'
import { ApiError } from '@/api/client'

import { AgentDetailPage } from '@/pages/AgentDetailPage'
import { AgentRegistryPage } from '@/pages/AgentRegistryPage'
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

/** 一份已通过复核的申请单（页面只关心 requestId）。 */
const REQUEST = {
  requestId: 'req-1234567890',
  operation: 'PUBLISH',
  state: 'PENDING',
  requesterId: 'u-1',
  approverId: null,
  decisionNote: null,
  requestedAt: '2026-10-25T00:00:00Z',
  decidedAt: null,
  appliedAt: null,
}

function renderAgentPage(path = '/agents/agent-1') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AntApp>
        <Routes>
          <Route path="/agents/:id" element={<AgentDetailPage />} />
        </Routes>
      </AntApp>
    </MemoryRouter>,
  )
}

/**
 * 渲染 Agent 管理页并**自动打开新建弹窗**（第三十七批 · C37-3）。
 *
 * ⚠️ 「新建」已改为弹窗：`/agents/new` 现在只是深链入口，
 *    真正的新建表单在弹窗里（与详情页的「修改」共用同一张 AgentDraftPanel）。
 */
function renderAgentRegistry(path = '/agents?register=1') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AntApp>
        <Routes>
          <Route path="/agents" element={<AgentRegistryPage />} />
        </Routes>
      </AntApp>
    </MemoryRouter>,
  )
}

/**
 * 打开「编辑」页签（草稿表单所在处）。
 *
 * ⚠️ antd Tabs 默认只渲染活动页签，表单在里面。
 */
async function openEditTab(): Promise<void> {
  const tab = await screen.findByRole('tab', { name: /^编辑/ })
  fireEvent.click(tab)
  await waitFor(() => expect(screen.getByLabelText(/变更理由/)).toBeTruthy())
}

/** 打开「发布与生命周期」页签（停用 / 直接发布快照所在处）。 */
async function openLifecycleTab(): Promise<void> {
  const tab = await screen.findByRole('tab', { name: /^发布与生命周期/ })
  fireEvent.click(tab)
  await waitFor(() => expect(screen.getByLabelText(/停用理由/)).toBeTruthy())
}

/**
 * 定位草稿表单容器。
 *
 * ⚠️ 刻意在表单内查找：页面别处也有「版本」等同名标签
 * （概览的 Descriptions 就有），不限定范围会拿错字段。
 */
function draftForm(): HTMLElement {
  const form = document.getElementById('agent-draft')
  if (!form) throw new Error('草稿表单未挂载')
  return form
}

/** 按标签读取输入框当前值（Input / InputNumber 渲染真实 input，可用 id 定位）。 */
function inputValueByLabel(label: RegExp): string {
  const labelEl = within(draftForm()).getByText(label)
  const id = labelEl.getAttribute('for')
  const input = draftForm().querySelector(`#${CSS.escape(id ?? '')}`) as HTMLInputElement | null
  return input?.value ?? ''
}

function selectValues(label: RegExp): string[] {
  const labelEl = within(draftForm()).getByText(label)
  const id = labelEl.getAttribute('for')
  const input = draftForm().querySelector(`#${CSS.escape(id ?? '')}`)
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
    await openEditTab()

    // 角色 / 启用技能 / 技能包 / 能力标签：Select 的值落在隐藏 input 上
    expect(selectValues(/角色（roles）/)).toEqual(['TEACHER', 'REVIEWER'])
    expect(selectValues(/启用技能（enabledSkills）/)).toEqual(['skill-a', 'skill-b'])
    expect(selectValues(/技能包（skillBundles）/)).toEqual(['bundle-x'])
    expect(selectValues(/能力标签（capabilityTags）/)).toEqual(['tag-1', 'tag-2'])
  })

  it('✅ overrides 回填自权威定义的 overrides（⛔ 不是 latestDefinition 全文）', async () => {
    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    await openEditTab()

    const overrides = screen.getByLabelText(/配置覆盖（overrides/) as HTMLTextAreaElement
    expect(overrides.value).toBe('{"removedSkills":["skill-old"]}')
    // ⛔ 反证：不得是旧体系快照全文（那会把「收紧覆盖」语义彻底搞错）
    expect(overrides.value).not.toContain('分析错题')
    expect(overrides.value).not.toContain('steps')
  })

  it('✅ 编排声明回填为结构化表单（mode / 子 Agent / 限制）', async () => {
    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    await openEditTab()

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
    vi.mocked(submitPublishRequest).mockResolvedValue(REQUEST as never)

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    await openEditTab()
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

  it('✅ 权威定义取不到时，名称 / 描述仍从旧体系实体行回填（不得对着空表单）', async () => {
    vi.mocked(getAgentDefinition).mockReset()
    vi.mocked(getAgentDefinition).mockRejectedValue(new ApiError(404, 'not found'))

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/该 Agent 尚无多级定义/)).toBeTruthy())
    await openEditTab()

    // ⚠️ 名称 / 描述**不依赖**多级定义 —— 取不到定义时也拿得到。
    //    回填只跑一次（按「对象 + 定义版本 + 旧体系实体行」去重），
    //    但**必须在实体行到达后仍然生效**，否则用户面对的是连名字都空着的表单。
    expect((screen.getByLabelText(/^名称/) as HTMLInputElement).value).toBe('错题归因助手')
    expect((screen.getByLabelText(/^描述/) as HTMLTextAreaElement).value).toBe('旧体系描述')
  })

  it('⛔ 权威定义 404 → 明确提示「尚无多级定义，保存会清空关系」，并要求显式确认', async () => {    vi.mocked(getAgentDefinition).mockReset()
    vi.mocked(getAgentDefinition).mockRejectedValue(new ApiError(404, 'not found'))
    vi.mocked(submitPublishRequest).mockReset()

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/该 Agent 尚无多级定义/)).toBeTruthy())
    await openEditTab()

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
    await openEditTab()
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
    await openEditTab()
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
    vi.mocked(submitPublishRequest).mockResolvedValue(REQUEST as never)

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/该 Agent 尚无多级定义/)).toBeTruthy())
    await openEditTab()
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
    await openEditTab()

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
      ...REQUEST,
      operation: 'LIFECYCLE_TRANSITION',
    } as never)

    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())
    await openLifecycleTab()

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

/* ══════════════════════════════════════════════════════════════
   第三十五批：新增 / 修改 / 详情 呈现**全部** Agent 信息
   ══════════════════════════════════════════════════════════════ */

describe('Agent 新建（弹窗 · 第三十七批 · C37-3）· 全字段草稿', () => {
  beforeAll(() => {
    vi.mocked(createAgent).mockReset()
    vi.mocked(createAgent).mockResolvedValue({
      ...AGENT,
      agentId: 'agent-new',
      versionCount: 0,
      latestVersionNumber: 0,
    } as never)
    vi.mocked(submitPublishRequest).mockReset()
    vi.mocked(submitPublishRequest).mockResolvedValue(REQUEST as never)
    // ⚠️ 弹窗提交成功后会刷新列表 —— 列表接口必须给一个合法形状
    vi.mocked(getAgentList).mockResolvedValue({ items: [], nextCursor: null } as never)
  })

  it('✅ 新建页给出**全部** Agent 字段（不只有名称与描述）', async () => {
    renderAgentRegistry()
    await waitFor(() => expect(screen.getAllByText(/新建 Agent（全字段草稿/).length).toBeGreaterThan(0))

    // 基础信息
    expect(screen.getByLabelText(/^名称/)).toBeTruthy()
    expect(screen.getByLabelText(/^描述/)).toBeTruthy()
    // 关系字段
    expect(screen.getByLabelText(/角色（roles）/)).toBeTruthy()
    expect(screen.getByLabelText(/启用技能（enabledSkills）/)).toBeTruthy()
    expect(screen.getByLabelText(/技能包（skillBundles）/)).toBeTruthy()
    expect(screen.getByLabelText(/能力标签（capabilityTags）/)).toBeTruthy()
    // 收紧覆盖 + 编排声明
    expect(screen.getByLabelText(/配置覆盖（overrides/)).toBeTruthy()
    expect(screen.getByLabelText(/编排模式（orchestration.mode）/)).toBeTruthy()
    expect(screen.getByText(/添加子 Agent 引用/)).toBeTruthy()
    expect(screen.getByLabelText(/路由策略引用/)).toBeTruthy()
    expect(screen.getByLabelText(/输出契约引用/)).toBeTruthy()
    expect(screen.getByLabelText(/最大委派深度/)).toBeTruthy()
    expect(screen.getByLabelText(/最大子调用次数/)).toBeTruthy()
    expect(screen.getByLabelText(/委派超时/)).toBeTruthy()
    expect(screen.getByLabelText(/失败策略/)).toBeTruthy()
    // 变更理由
    expect(screen.getByLabelText(/变更理由/)).toBeTruthy()
  })

  it('✅ 新建提交：先建 Agent 行，再提交全字段发布申请', async () => {
    renderAgentRegistry()
    await waitFor(() => expect(screen.getAllByText(/新建 Agent（全字段草稿/).length).toBeGreaterThan(0))

    fireEvent.change(screen.getByLabelText(/^名称/), { target: { value: '新 Agent' } })
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '首次登记' } })
    fireEvent.click(screen.getByRole('button', { name: /创建 Agent 并提交发布申请/ }))

    await waitFor(() => expect(submitPublishRequest).toHaveBeenCalledTimes(1))
    // ⚠️ 顺序不可颠倒：cap_agent_def 对 agent 表有外键 ⇒ 必须先有 Agent 行
    expect(createAgent).toHaveBeenCalledTimes(1)
    expect(vi.mocked(createAgent).mock.calls[0]![0]).toMatchObject({ name: '新 Agent' })

    const body = vi.mocked(submitPublishRequest).mock.calls[0]![0]
    expect(body.operation).toBe('PUBLISH')
    expect(body.scope).toBe('TENANT')
    expect(body.reason).toBe('首次登记')
    const spec = body.agents?.[0]
    // ⚠️ agentId 必须是**刚创建**的那个
    expect(spec?.agentId).toBe('agent-new')
    // ⚠️ 关系字段即便没填也必须是**显式的空数组**（不是 undefined）
    expect(spec?.roles).toEqual([])
    expect(spec?.enabledSkills).toEqual([])
    expect(spec?.skillBundles).toEqual([])
    expect(spec?.capabilityTags).toEqual([])
    // ⚠️ 缺省编排声明 = LEAF（= 不允许委派），不是「不限制」
    expect(spec?.orchestration).toContain('LEAF')
    expect(spec?.overrides).toBe('{}')
  })

  it('⛔ 表单校验未过 → 不得建 Agent 行（不得留下孤儿行）', async () => {
    vi.mocked(createAgent).mockClear()
    vi.mocked(submitPublishRequest).mockClear()

    renderAgentRegistry()
    await waitFor(() => expect(screen.getAllByText(/新建 Agent（全字段草稿/).length).toBeGreaterThan(0))

    // 名称留空（必填未过）+ 理由填好 —— 只有「名称」这一项非法
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '试试' } })
    fireEvent.click(screen.getByRole('button', { name: /创建 Agent 并提交发布申请/ }))

    await waitFor(() => expect(screen.getByText(/请输入名称/)).toBeTruthy())
    // ⛔ 校验发生在**建行之前** ⇒ 不得留下一个没有多级定义的孤儿 Agent
    expect(createAgent).not.toHaveBeenCalled()
    expect(submitPublishRequest).not.toHaveBeenCalled()
  })
})

describe('Agent 详情 · 概览呈现全部信息', () => {
  beforeAll(() => {
    vi.mocked(getAgent).mockResolvedValue(AGENT as never)
    vi.mocked(getAgentVersions).mockResolvedValue([] as never)
    vi.mocked(getAgentDefinition).mockResolvedValue(DEFINITION as never)
    vi.mocked(getDelegationChildren).mockResolvedValue([] as never)
    vi.mocked(getDelegationParents).mockResolvedValue([] as never)
  })

  it('✅ 旧体系实体行与权威定义**并列**呈现（不是合并成一份）', async () => {
    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())

    // 旧体系实体行
    expect(screen.getByText(/租户实体行（agent 表 · 旧体系）/)).toBeTruthy()
    expect(screen.getAllByText('旧体系描述').length).toBeGreaterThan(0)
    // 权威定义
    expect(screen.getByText(/多级定义字段（cap_agent_def）/)).toBeTruthy()
    expect(screen.getByText('权威描述')).toBeTruthy()
  })

  it('✅ 编排声明按结构展开（模式 / 子 Agent / 限制 / 策略引用）', async () => {
    renderAgentPage()
    await waitFor(() => expect(screen.getByText(/多级定义（cap_agent_def）/)).toBeTruthy())

    expect(screen.getByText('SEQUENTIAL')).toBeTruthy()
    expect(screen.getByText('child-1')).toBeTruthy()
    expect(screen.getByText('FAIL_FAST')).toBeTruthy()
    expect(screen.getByText('30000')).toBeTruthy()
    // 收紧覆盖原文可见
    expect(screen.getAllByText(/removedSkills/).length).toBeGreaterThan(0)
  })
})
