// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { App as AntApp } from 'antd'
import { DetailModeProvider } from '@/contexts/detailMode'

const getAgentVersions = vi.fn()
const cleanupAgentVersions = vi.fn()

vi.mock('@/api/adminApi', () => ({
  getAgentVersions: (...args: unknown[]) => getAgentVersions(...args),
  cleanupAgentVersions: (...args: unknown[]) => cleanupAgentVersions(...args),
}))

import { AgentVersionHistory } from '@/pages/agents/AgentVersionHistory'

const AGENT = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

function version(versionNumber: number, published: boolean, definition = '{}') {
  return {
    versionId: `v-${versionNumber}`,
    versionNumber,
    definition,
    publishedAt: published ? '2026-11-20T10:00:00Z' : null,
    createdAt: `2026-11-1${versionNumber}T10:00:00Z`,
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

describe('Agent 版本历史：展示 / 清理 / 双击 Diff', () => {
  beforeEach(() => {
    getAgentVersions.mockReset()
    cleanupAgentVersions.mockReset()
    window.localStorage.clear()
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('展示版本列表：区分已发布与草稿，并统计未发布草稿数', async () => {
    getAgentVersions.mockResolvedValue([
      version(3, false),
      version(2, true),
      version(1, true),
    ])
    renderHistory()

    await waitFor(() => expect(screen.getByText(/变更历史（3 条）/)).toBeTruthy())
    expect(screen.getByText('1 条未发布草稿')).toBeTruthy()
    expect(screen.getByText('草稿（未发布）')).toBeTruthy()
    expect(screen.getAllByText('已发布').length).toBe(2)
  })

  it('清理草稿：调用 DELETE 端点并刷新列表（列表状态随之更新）', async () => {
    getAgentVersions.mockResolvedValueOnce([
      version(3, false),
      version(2, true),
    ])
    cleanupAgentVersions.mockResolvedValue({ agentId: AGENT, removed: 1 })
    // 清理后重取 → 只剩已发布版本
    getAgentVersions.mockResolvedValueOnce([version(2, true)])

    renderHistory()
    await waitFor(() => expect(screen.getByText('1 条未发布草稿')).toBeTruthy())

    // 点击「清理草稿」→ 确认框 → 确认
    screen.getByRole('button', { name: /清理草稿/ }).click()
    await waitFor(() => expect(screen.getByText('确认清理')).toBeTruthy())
    screen.getByText('确认清理').click()

    await waitFor(() => expect(cleanupAgentVersions).toHaveBeenCalledWith(AGENT))
    // 刷新后未发布草稿归零，列表变回 1 条
    await waitFor(() => expect(screen.getByText(/变更历史（1 条）/)).toBeTruthy())
    expect(screen.queryByText('1 条未发布草稿')).toBeNull()
  })

  it('双击某条记录打开变更对比视图，并展示前后差异', async () => {
    getAgentVersions.mockResolvedValue([
      version(2, false, JSON.stringify({ name: 'B', model: 'gpt-4o', roles: ['x'] })),
      version(1, true, JSON.stringify({ name: 'A', model: 'gpt-4o' })),
    ])
    renderHistory()
    await waitFor(() => expect(screen.getByText(/变更历史（2 条）/)).toBeTruthy())

    // 双击第一行（v2）
    const rows = document.querySelectorAll('tbody tr')
    expect(rows.length).toBeGreaterThanOrEqual(2)
    rows[0]?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))

    // Diff 弹窗打开，标题显示 v1 → v2
    await waitFor(() => expect(screen.getByText(/v1 → v2/)).toBeTruthy())
    // 有变更的 key 被标出：name 被改（值 A → B）、roles 为新增
    const dialog = document.querySelector('.ant-modal-content') as HTMLElement
    expect(dialog).toBeTruthy()
    const text = dialog.textContent ?? ''
    expect(text).toContain('name')
    expect(text).toContain('roles')
    // 前后值都出现在对比视图里
    expect(text).toContain('A')
    expect(text).toContain('B')
  })

  it('只有一个版本时双击提示无法对比', async () => {
    getAgentVersions.mockResolvedValue([version(1, true)])
    renderHistory()
    await waitFor(() => expect(screen.getByText(/变更历史（1 条）/)).toBeTruthy())
    const rows = document.querySelectorAll('tbody tr')
    rows[0]?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))
    await waitFor(() => expect(screen.getByText(/只有一个版本/)).toBeTruthy())
  })
})
