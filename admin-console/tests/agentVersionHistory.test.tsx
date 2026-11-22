// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { App as AntApp } from 'antd'
import { DetailModeProvider } from '@/contexts/detailMode'

const getPlatformAgentDefinitionVersions = vi.fn()
const cleanupAgentVersions = vi.fn()

vi.mock('@/api/adminApi', () => ({
  getPlatformAgentDefinitionVersions: (...args: unknown[]) => getPlatformAgentDefinitionVersions(...args),
  cleanupAgentVersions: (...args: unknown[]) => cleanupAgentVersions(...args),
}))

import { AgentVersionHistory } from '@/pages/agents/AgentVersionHistory'

const AGENT = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

function def(version: number, published: boolean, patch: Record<string, unknown> = {}) {
  return {
    agentId: AGENT,
    version,
    name: 'Agent',
    description: null,
    roles: [],
    enabledSkills: [],
    skillBundles: [],
    capabilityTags: [],
    overrides: '{}',
    orchestration: '{}',
    instructions: '{}',
    modelRef: 'gpt-4o',
    generationParams: '{}',
    published,
    createdAt: `2026-11-0${version}T10:00:00Z`,
    publishedAt: published ? `2026-11-0${version}T12:00:00Z` : null,
    ...patch,
  }
}

function renderHistory() {
  return render(
    <AntApp>
      <DetailModeProvider>
        <AgentVersionHistory agentId={AGENT} />
      </DetailModeProvider>
    </AntApp>,
  )
}

describe('Agent 变更历史：展示 / 清理 / 双击配置对比', () => {
  beforeEach(() => {
    getPlatformAgentDefinitionVersions.mockReset()
    cleanupAgentVersions.mockReset()
    window.localStorage.clear()
  })
  afterEach(() => vi.restoreAllMocks())

  it('展示版本列表：区分已发布与草稿，并统计未发布草稿数', async () => {
    getPlatformAgentDefinitionVersions.mockResolvedValue([
      def(3, false), def(2, true), def(1, true),
    ])
    renderHistory()
    await waitFor(() => expect(screen.getByText(/变更历史（3 条）/)).toBeTruthy())
    expect(screen.getByText('1 条未发布草稿')).toBeTruthy()
    expect(screen.getByText('草稿（未发布）')).toBeTruthy()
    expect(screen.getAllByText('已发布').length).toBe(2)
  })

  it('清理草稿：调用 DELETE 后重新拉取，列表状态随之更新', async () => {
    getPlatformAgentDefinitionVersions.mockResolvedValueOnce([def(3, false), def(2, true)])
    cleanupAgentVersions.mockResolvedValue({ agentId: AGENT, removed: 1 })
    getPlatformAgentDefinitionVersions.mockResolvedValueOnce([def(2, true)])

    renderHistory()
    await waitFor(() => expect(screen.getByText('1 条未发布草稿')).toBeTruthy())

    screen.getByRole('button', { name: /清理草稿/ }).click()
    await waitFor(() => expect(screen.getByText('确认清理')).toBeTruthy())
    screen.getByText('确认清理').click()

    await waitFor(() => expect(cleanupAgentVersions).toHaveBeenCalledWith(AGENT))
    await waitFor(() => expect(screen.getByText(/变更历史（1 条）/)).toBeTruthy())
    expect(screen.queryByText('1 条未发布草稿')).toBeNull()
  })

  it('双击某条记录 → 打开该版本与上一版本的配置对比（含变更路径与前后值）', async () => {
    getPlatformAgentDefinitionVersions.mockResolvedValue([
      def(2, false, { modelRef: 'gpt-4o-mini', enabledSkills: ['skill-a'] }),
      def(1, true, { modelRef: 'gpt-4o' }),
    ])
    renderHistory()
    await waitFor(() => expect(screen.getByText(/变更历史（2 条）/)).toBeTruthy())

    const rows = document.querySelectorAll('tbody tr.ant-table-row')
    expect(rows.length).toBe(2)
    rows[0]?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))

    // 标题：v1 → v2 配置对比
    await waitFor(() => expect(screen.getByText(/v1 → v2 配置对比/)).toBeTruthy())
    const modal = document.querySelector('.ant-modal-content') as HTMLElement
    expect(modal).toBeTruthy()
    const text = modal.textContent ?? ''
    // 以 JSON 呈现前后两份配置
    expect(text).toContain('modelRef')
    expect(text).toContain('gpt-4o-mini')
    expect(text).toContain('gpt-4o')
    // 变更后 JSON 含新增的 skill 元素
    expect(text).toContain('skill-a')
    // 统计标签
    expect(text).toContain('+1')
    expect(text).toContain('~1')
  })

  it('首个版本双击提示没有更早版本可对比', async () => {
    getPlatformAgentDefinitionVersions.mockResolvedValue([def(1, true)])
    renderHistory()
    await waitFor(() => expect(screen.getByText(/变更历史（1 条）/)).toBeTruthy())
    const rows = document.querySelectorAll('tbody tr.ant-table-row')
    rows[0]?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))
    await waitFor(() => expect(screen.getByText(/首个版本/)).toBeTruthy())
  })

  it('两版配置完全一致时提示「完全一致」而不是空面板', async () => {
    getPlatformAgentDefinitionVersions.mockResolvedValue([def(2, false), def(1, true)])
    renderHistory()
    await waitFor(() => expect(screen.getByText(/变更历史（2 条）/)).toBeTruthy())
    const rows = document.querySelectorAll('tbody tr.ant-table-row')
    rows[0]?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))
    await waitFor(() => expect(screen.getByText(/两版配置完全一致/)).toBeTruthy())
  })
})
