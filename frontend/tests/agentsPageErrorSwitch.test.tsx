/**
 * AgentsPage 视图切换清残留错误测试（E2E 五轮优化，D3）。
 *
 * ⚠️ 守则是「撤掉修复点会红」：若「新建 / 发布」两个视图切换时不清
 * `actionError`，一次发布的失败提示会串味进新建表单（或反之），
 * 用户看到的是上一动作的错误，困惑且误导。本文件锁住
 * 「触发发布错误后，点『新建 Agent』切换视图 → 错误提示消失」。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
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

function sampleAgent(): AgentItem {
  return {
    agentId: 'agent-1',
    name: '数学归因助手',
    description: '定位薄弱知识点',
    versionCount: 1,
    latestVersionNumber: 1,
    latestDefinition: null,
    latestPublishedAt: '2026-10-01T00:00:00Z',
  }
}

const PUBLISH_ERROR = '发布版本冲突：definition 已存在'

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
  publishAgentVersionMock.mockRejectedValue(new Error(PUBLISH_ERROR))
})

function renderPage(): void {
  render(
    <MemoryRouter>
      <SessionProvider>
        <AgentsPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('AgentsPage 视图切换清残留错误（D3）', () => {
  it('发布失败后切到新建视图，错误提示消失', async () => {
    const user = userEvent.setup()
    renderPage()

    // 列表渲染后，点「发布新版本」进入发布视图
    const publishButtons = await screen.findAllByRole('button', { name: '发布新版本' })
    await user.click(publishButtons[0] as HTMLElement)

    // 点「发布」触发失败
    const publishSubmit = await screen.findByRole('button', { name: '发布' })
    await user.click(publishSubmit)

    // 发布错误出现（非新建视图时可见）
    await waitFor(() => expect(screen.getByText(PUBLISH_ERROR)).toBeInTheDocument())

    // 切回「新建 Agent」视图
    await user.click(screen.getByRole('button', { name: '新建 Agent' }))

    // ⛔ D3 撤点反证：若切换不清除 actionError，这条错误会串味残留
    await waitFor(() =>
      expect(screen.queryByText(PUBLISH_ERROR)).not.toBeInTheDocument(),
    )
  })
})
