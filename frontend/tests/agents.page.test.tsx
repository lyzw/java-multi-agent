/**
 * Agent 配置页测试（Point 3 · 结构化发布表单）
 *
 * ⛔ 本测试最核心的断言是「**发布的是结构化字段组装出的 JSON**」：
 *    早前版本是一个裸 JSON 文本框，用户必须手写
 *    `{"roles":[...],"enabledSkills":[...]}`——写错一个引号就 400，
 *    且无法从表单上看出「这个 Agent 到底启用了哪些技能」。
 *    结构化表单把字段显式化，但仍走同一个 `definition` 契约
 *    （不可变快照 + 发新版本），**不改变发布语义**。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import { AgentsPage } from '@/pages/config/ConfigPages'
import type { AgentItem } from '@/api/types'

const listAgentsMock = vi.hoisted(() => vi.fn())
const createAgentMock = vi.hoisted(() => vi.fn())
const publishAgentVersionMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    listAgents: listAgentsMock,
    createAgent: createAgentMock,
    publishAgentVersion: publishAgentVersionMock,
  }
})

const TENANT = '11111111-1111-1111-1111-111111111111'

function sampleAgent(overrides?: Partial<AgentItem>): AgentItem {
  return {
    agentId: 'agent-1',
    name: '数学归因助手',
    description: '定位薄弱知识点',
    versionCount: 1,
    latestVersionNumber: 1,
    latestDefinition: null,
    latestPublishedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

function renderPage(): void {
  render(
    <MemoryRouter>
      <SessionProvider>
        <AgentsPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  window.sessionStorage.setItem(
    'agent.session',
    JSON.stringify({
      accessToken: 'tok-agent-admin',
      expiresAt: Date.now() + 900 * 1000,
      tenantId: TENANT,
      principalId: '33333333-3333-3333-3333-333333333333',
    }),
  )
  listAgentsMock.mockReset()
  createAgentMock.mockReset()
  publishAgentVersionMock.mockReset()
  listAgentsMock.mockResolvedValue({ items: [sampleAgent()], nextCursor: null })
  createAgentMock.mockResolvedValue(sampleAgent())
  publishAgentVersionMock.mockResolvedValue({
    versionId: 'v-2',
    versionNumber: 2,
    definition: '{}',
    publishedAt: null,
    createdAt: '2026-10-24T00:00:00Z',
  })
})

describe('Agent 配置页：结构化发布表单（Point 3）', () => {
  const openPublish = async (
    user: ReturnType<typeof userEvent.setup>,
  ): Promise<void> => {
    await user.click(await screen.findByRole('button', { name: '发布新版本' }))
  }

  it('默认进入「结构化填写」模式，字段显式可见', async () => {
    const user = userEvent.setup()
    renderPage()
    await openPublish(user)

    expect(await screen.findByLabelText(/角色/)).toBeInTheDocument()
    expect(screen.getByLabelText(/启用的技能/)).toBeInTheDocument()
    expect(screen.getByLabelText(/技能包/)).toBeInTheDocument()
    expect(screen.getByLabelText(/能力标签/)).toBeInTheDocument()
    expect(screen.getByLabelText(/覆盖参数/)).toBeInTheDocument()
    // ⚠️ 结构化模式下**不应**出现裸 JSON 文本框（那是被取代的旧形态）。
    expect(screen.queryByLabelText(/definition（JSON）/)).toBeNull()
  })

  it('结构化字段被组装成 definition JSON 后发布', async () => {
    const user = userEvent.setup()
    renderPage()
    await openPublish(user)

    await user.type(screen.getByLabelText(/角色/), 'TEACHER,GUARDIAN')
    await user.type(screen.getByLabelText(/启用的技能/), 'error-attribution,rag-grounding')
    await user.type(screen.getByLabelText(/能力标签/), 'math-grade8')
    await user.click(screen.getByRole('button', { name: '发布' }))

    await waitFor(() => expect(publishAgentVersionMock).toHaveBeenCalledTimes(1))
    const [agentId, payload] = publishAgentVersionMock.mock.calls[0]!
    expect(agentId).toBe('agent-1')
    const definition = JSON.parse((payload as { definition: string }).definition)
    expect(definition.roles).toEqual(['TEACHER', 'GUARDIAN'])
    expect(definition.enabledSkills).toEqual(['error-attribution', 'rag-grounding'])
    expect(definition.skillBundles).toEqual([])
    expect(definition.capabilityTags).toEqual(['math-grade8'])
    expect(definition.overrides).toEqual({})
  })

  it('⛔ 空清单切成空数组，⛔ 不得切成 [""]', async () => {
    const user = userEvent.setup()
    renderPage()
    await openPublish(user)

    // 故意留空所有清单字段（用户没填）
    await user.click(screen.getByRole('button', { name: '发布' }))

    await waitFor(() => expect(publishAgentVersionMock).toHaveBeenCalledTimes(1))
    const definition = JSON.parse(
      (publishAgentVersionMock.mock.calls[0]![1] as { definition: string }).definition,
    )
    expect(definition.roles).toEqual([])
    expect(definition.enabledSkills).toEqual([])
    // ⚠️ 若是 [""]，等于声明了一个名为空字符串的角色——
    //    无意义且危险的配置，这里必须拦死。
    expect(definition.roles).not.toEqual([''])
  })

  it('中文逗号同样按分隔符处理（用户不会特意切英文逗号）', async () => {
    const user = userEvent.setup()
    renderPage()
    await openPublish(user)

    await user.type(screen.getByLabelText(/启用的技能/), '错题归因，知识检索')
    await user.click(screen.getByRole('button', { name: '发布' }))

    await waitFor(() => expect(publishAgentVersionMock).toHaveBeenCalledTimes(1))
    const definition = JSON.parse(
      (publishAgentVersionMock.mock.calls[0]![1] as { definition: string }).definition,
    )
    expect(definition.enabledSkills).toEqual(['错题归因', '知识检索'])
  })

  it('overrides 不是合法 JSON 时拒绝发布并提示', async () => {
    const user = userEvent.setup()
    renderPage()
    await openPublish(user)

    // ⚠️ 用 fireEvent.change 而非 user.type：后者把 `{`/`[` 当特殊键定界符，
    //    输入 JSON 片段会被解析成「重复修饰符」而抛错，测不到想测的分支。
    fireEvent.change(screen.getByLabelText(/覆盖参数/), { target: { value: 'not-json' } })
    await user.click(screen.getByRole('button', { name: '发布' }))

    expect(await screen.findByText(/overrides 不是合法 JSON/)).toBeInTheDocument()
    expect(publishAgentVersionMock).not.toHaveBeenCalled()
  })

  it('⛔ 反证：可切回「JSON 原文」模式（高级用户不被堵死）', async () => {
    const user = userEvent.setup()
    renderPage()
    await openPublish(user)

    await user.click(screen.getByRole('tab', { name: 'JSON 原文' }))
    const textarea = await screen.findByLabelText(/definition（JSON）/)
    expect(textarea.tagName).toBe('TEXTAREA')

    fireEvent.change(textarea, {
      target: { value: '{"roles":["TEACHER"]}' },
    })
    await user.click(screen.getByRole('button', { name: '发布' }))
    await waitFor(() => expect(publishAgentVersionMock).toHaveBeenCalledTimes(1))
    expect(
      (publishAgentVersionMock.mock.calls[0]![1] as { definition: string }).definition,
    ).toBe('{"roles":["TEACHER"]}')
  })

  it('已有 definition 时结构化表单被预填（不是每次从零开始）', async () => {
    listAgentsMock.mockResolvedValue({
      items: [
        sampleAgent({
          latestDefinition: JSON.stringify({
            roles: ['TEACHER'],
            enabledSkills: ['error-attribution'],
            skillBundles: [],
            capabilityTags: [],
            overrides: { topK: 5 },
          }),
        }),
      ],
      nextCursor: null,
    })
    const user = userEvent.setup()
    renderPage()
    await openPublish(user)

    expect((await screen.findByLabelText(/角色/) as HTMLInputElement).value ?? '').toBeDefined()
    await waitFor(() =>
      expect(
        (screen.getByLabelText(/启用的技能/) as HTMLInputElement).value,
      ).toBe('error-attribution'),
    )
    expect((screen.getByLabelText(/角色/) as HTMLInputElement).value).toBe('TEACHER')
    expect((screen.getByLabelText(/覆盖参数/) as HTMLTextAreaElement).value).toContain('topK')
  })

  it('⛔ 反证：definition 不是合法 JSON 时静默留空，不把发布面板变成错误态', async () => {
    listAgentsMock.mockResolvedValue({
      items: [sampleAgent({ latestDefinition: 'not-json-at-all' })],
      nextCursor: null,
    })
    const user = userEvent.setup()
    renderPage()
    await openPublish(user)

    // 面板仍可正常打开发布（用户可切到 JSON 原文模式继续手改）
    expect(await screen.findByRole('button', { name: '发布' })).toBeInTheDocument()
    expect((screen.getByLabelText(/角色/) as HTMLInputElement).value).toBe('')
  })
})
