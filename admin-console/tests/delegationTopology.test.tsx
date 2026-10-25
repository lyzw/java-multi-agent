// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  getDelegations: vi.fn(),
  getDelegationChildren: vi.fn(),
  getDelegationParents: vi.fn(),
}))
import { getDelegationChildren, getDelegationParents, getDelegations } from '@/api/adminApi'

import { DelegationTopologyPage } from '@/pages/dependencies/DelegationTopologyPage'
import { DelegationTab } from '@/pages/dependencies/DelegationTab'
import { layoutDelegations } from '@/pages/dependencies/delegationLayout'
import type { DelegationEdge } from '@/api/types'

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

function edge(
  parent: string,
  child: string,
  opts: { ordinal?: number; required?: boolean } = {},
): DelegationEdge {
  return {
    parentAgentId: parent,
    parentReleaseVersion: 3,
    childAgentId: child,
    childReleaseVersion: 2,
    ordinal: opts.ordinal ?? 0,
    required: opts.required ?? true,
    createdAt: '2026-10-25T00:00:00Z',
  }
}

describe('委派拓扑 · 分层布局（纯函数）', () => {
  it('父永远画在子上面（否则「谁把工作交给谁」读反了）', () => {
    const layout = layoutDelegations([edge('parent-1', 'child-1'), edge('child-1', 'leaf-1')])
    const yOf = (id: string): number => layout.nodes.find((n) => n.agentId === id)!.y
    expect(yOf('parent-1')).toBeLessThan(yOf('child-1'))
    expect(yOf('child-1')).toBeLessThan(yOf('leaf-1'))
  })

  it('确定性：输入顺序打乱，布局逐字节一致', () => {
    const edges = [edge('p1', 'c1'), edge('p1', 'c2', { ordinal: 1 }), edge('c1', 'g1')]
    const a = layoutDelegations(edges)
    const b = layoutDelegations([...edges].reverse())
    expect(JSON.stringify(a)).toBe(JSON.stringify(b))
  })

  it('⛔ 自环不得让层号无限增长（否则画布被撑成一条长带）', () => {
    const layout = layoutDelegations([edge('a', 'b'), edge('b', 'a')])
    const ys = layout.nodes.map((n) => n.y)
    expect(Math.max(...ys) - Math.min(...ys)).toBeLessThan(200)
  })

  it('空边集不炸', () => {
    const layout = layoutDelegations([])
    expect(layout.nodes).toHaveLength(0)
    expect(layout.edges).toHaveLength(0)
  })
})

describe('委派拓扑全图页', () => {
  beforeAll(() => {
    vi.mocked(getDelegations).mockResolvedValue([
      edge('parent-1', 'child-1'),
      edge('parent-1', 'child-2', { ordinal: 1, required: false }),
    ] as never)
  })

  it('✅ 渲染全部边（父 / 子 / 版本 / 必需与否）', async () => {
    render(
      <MemoryRouter>
        <AntApp>
          <DelegationTopologyPage />
        </AntApp>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText(/拓扑图/)).toBeTruthy())
    expect(screen.getAllByText('parent-1').length).toBeGreaterThan(0)
    expect(screen.getAllByText('child-1').length).toBeGreaterThan(0)
    expect(screen.getAllByText('必需').length).toBeGreaterThan(0)
    expect(screen.getAllByText('可选').length).toBeGreaterThan(0)
  })
})

describe('Agent 详情页的委派拓扑页签 · 出向与入向是两个问题', () => {
  beforeAll(() => {
    vi.mocked(getDelegationChildren).mockResolvedValue([edge('agent-1', 'child-1')] as never)
    vi.mocked(getDelegationParents).mockResolvedValue([edge('parent-1', 'agent-1')] as never)
  })

  it('✅ 出向与入向分别呈现（只画出向答不了「能不能停用」）', async () => {
    render(
      <MemoryRouter>
        <AntApp>
          <DelegationTab agentId="agent-1" />
        </AntApp>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText(/出向：声明的子 Agent/)).toBeTruthy())
    expect(screen.getByText(/入向：依赖本 Agent 的父/)).toBeTruthy()
    expect(screen.getByText('child-1')).toBeTruthy()
    expect(screen.getByText('parent-1')).toBeTruthy()
  })

  it('✅ 入向非空 → 明确提示停用风险，且**如实说明无自动拦截**', async () => {
    render(
      <MemoryRouter>
        <AntApp>
          <DelegationTab agentId="agent-1" />
        </AntApp>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText(/个父 Agent 依赖本 Agent/)).toBeTruthy())
    expect(screen.getByText(/停用本 Agent 会让它们悬空/)).toBeTruthy()
    // ⚠️ **反证**：不得把「提示」说成「守卫」。
    //    当前**没有**任何自动拦截会因此阻止停用（后端 CAS 只管对象自身状态列，
    //    没有引用守卫）—— 若页面宣称「守卫不通过」，运维就会以为系统会拦住，
    //    实际却照放行 ⇒ **虚假安全感比没有提示更危险**。
    expect(screen.getByText(/无自动拦截|没有.*自动守卫/)).toBeTruthy()
  })
})
