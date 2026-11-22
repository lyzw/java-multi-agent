// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createRef } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { App as AntApp } from 'antd'
import { DetailModeProvider } from '@/contexts/detailMode'

const getPlatformAgentDefinitionVersions = vi.fn()
const cleanupAgentVersions = vi.fn()

vi.mock('@/api/adminApi', () => ({
  getPlatformAgentDefinitionVersions: (...a: unknown[]) => getPlatformAgentDefinitionVersions(...a),
  cleanupAgentVersions: (...a: unknown[]) => cleanupAgentVersions(...a),
}))

import { AgentVersionHistory, type AgentVersionHistoryHandle } from '@/pages/agents/AgentVersionHistory'

const AGENT = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

function def(version: number, published = true) {
  return {
    agentId: AGENT, version, name: 'Agent', description: null,
    roles: [], enabledSkills: [], skillBundles: [], capabilityTags: [],
    overrides: '{}', orchestration: '{}', instructions: '{}',
    modelRef: 'gpt-4o', generationParams: '{}', published,
    createdAt: `2026-11-0${version}T10:00:00Z`,
    publishedAt: published ? `2026-11-0${version}T12:00:00Z` : null,
  }
}

describe('变更记录自带 before/after → 配置对比', () => {
  beforeEach(() => {
    getPlatformAgentDefinitionVersions.mockReset()
    cleanupAgentVersions.mockReset()
    window.localStorage.clear()
  })

  it('diffConfig 直接用记录里的 before/after，不依赖版本反推', async () => {
    // 版本历史里只有 v1（与记录无关），证明对比不走版本反推
    getPlatformAgentDefinitionVersions.mockResolvedValue([def(1)])
    const ref = createRef<AgentVersionHistoryHandle>()
    render(
      <AntApp>
        <DetailModeProvider>
          <AgentVersionHistory ref={ref} agentId={AGENT} />
        </DetailModeProvider>
      </AntApp>,
    )
    await waitFor(() => expect(screen.getByText(/变更历史（1 条）/)).toBeTruthy())

    ref.current?.diffConfig({
      before: JSON.stringify({ modelRef: 'gpt-4o', enabledSkills: ['a'] }),
      after: JSON.stringify({ modelRef: 'gpt-4o-mini', enabledSkills: ['a', 'b'] }),
      label: '定义发布 · 配置对比',
    })

    await waitFor(() => expect(screen.getByText(/定义发布 · 配置对比/)).toBeTruthy())
    const modal = document.querySelector('.ant-modal-content') as HTMLElement
    const text = modal.textContent ?? ''
    // 前后值都来自记录本身
    expect(text).toContain('modelRef')
    expect(text).toContain('gpt-4o-mini')
    expect(text).toContain('enabledSkills[1]')
    // 记录来源的说明文案
    expect(text).toContain('变更记录中保存的变更前配置')
    // 统计
    expect(text).toContain('+1')
    expect(text).toContain('~1')
  })

  it('before 为 null（首个版本）也能对比，并提示变更前不存在配置', async () => {
    getPlatformAgentDefinitionVersions.mockResolvedValue([def(1)])
    const ref = createRef<AgentVersionHistoryHandle>()
    render(
      <AntApp>
        <DetailModeProvider>
          <AgentVersionHistory ref={ref} agentId={AGENT} />
        </DetailModeProvider>
      </AntApp>,
    )
    await waitFor(() => expect(screen.getByText(/变更历史（1 条）/)).toBeTruthy())

    ref.current?.diffConfig({
      before: null,
      after: JSON.stringify({ modelRef: 'gpt-4o' }),
      label: '创建 · 配置对比',
    })

    await waitFor(() => expect(screen.getByText(/创建 · 配置对比/)).toBeTruthy())
    const modal = document.querySelector('.ant-modal-content') as HTMLElement
    expect(modal.textContent ?? '').toContain('首个版本，变更前不存在配置')
  })

  it('after 为 null（管理类动作）不打开对比，给出提示', async () => {
    getPlatformAgentDefinitionVersions.mockResolvedValue([def(1)])
    const ref = createRef<AgentVersionHistoryHandle>()
    render(
      <AntApp>
        <DetailModeProvider>
          <AgentVersionHistory ref={ref} agentId={AGENT} />
        </DetailModeProvider>
      </AntApp>,
    )
    await waitFor(() => expect(screen.getByText(/变更历史（1 条）/)).toBeTruthy())

    ref.current?.diffConfig({ before: null, after: null, label: '分配租户' })
    await waitFor(() => expect(screen.getByText(/没有配置变更内容/)).toBeTruthy())
  })
})
