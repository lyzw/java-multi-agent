// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import {
  EDGE_KIND_LABELS,
  NODE_COLORS,
  NODE_TYPE_LABELS,
  edgePath,
  filterNodes,
  impactTypeOf,
  isNegativeEdge,
  layoutGraph,
  truncate,
  type LayoutNode,
} from '@/pages/dependencies/graphLayout'
import type {
  DependencyGraphResponse,
  GraphEdge,
  GraphEdgeKind,
  GraphNode,
  GraphNodeType,
  ImpactReport,
} from '@/api/types'
import { DependencyGraphPage } from '@/pages/dependencies/DependencyGraphPage'
import { ImpactAnalysisPage } from '@/pages/dependencies/ImpactAnalysisPage'

// ---- 页面测试：mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  getDependencyGraph: vi.fn(),
  getDependencyImpact: vi.fn(),
}))
import { getDependencyGraph, getDependencyImpact } from '@/api/adminApi'

// antd v5 部分组件渲染期读 matchMedia；jsdom 默认没有，补一个最小桩
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

function node(
  id: string,
  type: GraphNodeType,
  refId: string,
  label: string,
  opts: { exists?: boolean; published?: boolean } = {},
): GraphNode {
  return {
    id,
    type,
    refId,
    label,
    detail: null,
    exists: opts.exists ?? true,
    published: opts.published ?? true,
    version: 1,
  }
}

/** 五层小图：tenant → agent → skill → tool → mcp_server（每条引用方向向下）。 */
function smallGraphNodes(): GraphNode[] {
  return [
    node('tenant:t-1', 'TENANT', 't-1', '租户 t-1'),
    node('agent:a-1', 'AGENT', 'a-1', '客服 Agent'),
    node('skill:sk-1', 'SKILL', 'sk-1', '知识检索'),
    node('tool:t-native', 'TOOL', 't-native', '原生检索'),
    node('mcp_server:srv-1', 'MCP_SERVER', 'srv-1', '搜索服务'),
  ]
}

function smallGraphEdges(): GraphEdge[] {
  return [
    { from: 'agent:a-1', to: 'tenant:t-1', kind: 'TENANT_OF' },
    { from: 'agent:a-1', to: 'skill:sk-1', kind: 'ENABLED_SKILL' },
    { from: 'skill:sk-1', to: 'tool:t-native', kind: 'DECLARED_TOOL' },
    { from: 'tool:t-native', to: 'mcp_server:srv-1', kind: 'MCP_BINDING' },
  ]
}

// ---- graphLayout 纯函数 ----

describe('graphLayout · 分层布局（引用方向自上而下）', () => {
  it('五层类型各自落在正确层号', () => {
    const layout = layoutGraph(smallGraphNodes())
    const layerOf = (id: string): number =>
      layout.nodes.find((l) => l.node.id === id)!.layer
    expect(layerOf('tenant:t-1')).toBe(0)
    expect(layerOf('agent:a-1')).toBe(1)
    expect(layerOf('skill:sk-1')).toBe(2)
    expect(layerOf('tool:t-native')).toBe(3)
    expect(layerOf('mcp_server:srv-1')).toBe(4)
  })

  it('层号越大 y 越靠下（单调不回环）', () => {
    const layout = layoutGraph(smallGraphNodes())
    const yOf = (id: string): number => layout.nodes.find((l) => l.node.id === id)!.y
    expect(yOf('tenant:t-1')).toBeLessThan(yOf('agent:a-1'))
    expect(yOf('agent:a-1')).toBeLessThan(yOf('skill:sk-1'))
    expect(yOf('skill:sk-1')).toBeLessThan(yOf('tool:t-native'))
    expect(yOf('tool:t-native')).toBeLessThan(yOf('mcp_server:srv-1'))
  })

  it('确定性：输入顺序打乱，布局逐字节一致（运维比对同一份数据两张图）', () => {
    const nodes = smallGraphNodes()
    const a = layoutGraph(nodes)
    const b = layoutGraph([...nodes].reverse())
    expect(JSON.stringify(a)).toBe(JSON.stringify(b))
  })

  it('层内按 (label, refId) 排序（断言与 locale 无关：标签取拉丁字母）', () => {
    // ⚠️ 不用中文标签做排序断言：localeCompare 对 Han 字符的排序随 runtime locale
    // 变（本环境按码点：乙 U+4E59 < 甲 U+7532），拉丁标签在任何 locale 下同序。
    const nodes = [
      node('agent:b', 'AGENT', 'b', 'Agent B'),
      node('agent:a', 'AGENT', 'a', 'Agent A'),
      node('agent:a2', 'AGENT', 'a2', 'Agent A'),
    ]
    const layout = layoutGraph(nodes)
    // 'Agent A' 两条并列 → refId 升序；'Agent B' 居后
    expect(layout.nodes.map((l) => l.node.refId)).toEqual(['a', 'a2', 'b'])
  })

  it('超过 MAX_COLS(6) 换行：前 6 个同一行，第 7 个下一行', () => {
    const nodes: GraphNode[] = []
    for (let i = 0; i < 7; i++) {
      nodes.push(node(`agent:a-${i}`, 'AGENT', `a-${i}`, `Agent ${i}`))
    }
    const layout = layoutGraph(nodes)
    const ys = layout.nodes.map((l) => l.y)
    expect(new Set(ys.slice(0, 6)).size).toBe(1) // 行 0：6 节点同 y
    const firstRowY = ys[0]
    const secondRowY = ys[6]
    expect(firstRowY).toBeDefined()
    expect(secondRowY).toBeDefined()
    if (firstRowY === undefined || secondRowY === undefined) return
    expect(secondRowY).toBeGreaterThan(firstRowY) // 行 1
  })

  it('空输入不炸，画布退化为最小尺寸', () => {
    const layout = layoutGraph([])
    expect(layout.nodes).toEqual([])
    expect(layout.width).toBeGreaterThan(0)
    expect(layout.height).toBeGreaterThan(0)
  })

  it('画布宽度随最宽行增长（6 列场景）', () => {
    const nodes: GraphNode[] = []
    for (let i = 0; i < 6; i++) {
      nodes.push(node(`agent:a-${i}`, 'AGENT', `a-${i}`, `Agent ${i}`))
    }
    const layout = layoutGraph(nodes)
    // 6*156 + 5*28 + 2*36
    expect(layout.width).toBe(6 * 156 + 5 * 28 + 2 * 36)
  })

  it('夹具所有边均不违反分层：引用边向下/同层，仅 TENANT_OF 所有权边向上', () => {
    const layout = layoutGraph(smallGraphNodes())
    const layerOf = new Map(layout.nodes.map((l) => [l.node.id, l.layer]))
    for (const e of smallGraphEdges()) {
      const from = layerOf.get(e.from)
      const to = layerOf.get(e.to)
      expect(from, `边 ${e.from} 起点应有布局`).toBeDefined()
      expect(to, `边 ${e.to} 终点应有布局`).toBeDefined()
      if (from === undefined || to === undefined) continue
      if (e.kind === 'TENANT_OF') {
        expect(to).toBeLessThan(from) // Agent(1) → Tenant(0)
      } else {
        expect(to).toBeGreaterThanOrEqual(from)
      }
    }
  })
})

describe('graphLayout · edgePath（分层 S 曲线）', () => {
  const at = (x: number, y: number, layer: number): LayoutNode => ({
    node: node('n', 'AGENT', 'n', 'n'),
    x,
    y,
    layer,
  })

  it('向下边 → S 曲线（控制点垂直分离）', () => {
    const a = at(0, 0, 0)
    const b = at(50, 100, 1)
    expect(edgePath(a, b)).toBe('M 0 0 C 0 50, 50 50, 50 100')
  })

  it('同层 / 向上边 → 直线兜底', () => {
    expect(edgePath(at(0, 0, 1), at(50, 0, 1))).toBe('M 0 0 L 50 0')
    expect(edgePath(at(0, 100, 2), at(50, 0, 1))).toBe('M 0 100 L 50 0')
  })

  it('垂直向下（dx≈0）→ 直线，避免 S 退化为零宽', () => {
    expect(edgePath(at(10, 0, 0), at(10, 100, 1))).toBe('M 10 0 L 10 100')
  })
})

describe('graphLayout · 标签 / 颜色 / 映射常量', () => {
  it('七种边类型全部有中文标签', () => {
    const kinds: GraphEdgeKind[] = [
      'TENANT_OF',
      'ENABLED_SKILL',
      'DECLARED_TOOL',
      'DECLARED_MCP',
      'MCP_BINDING',
      'OVERRIDE_REMOVED_SKILL',
      'OVERRIDE_REMOVED_TOOL',
    ]
    for (const k of kinds) {
      expect(EDGE_KIND_LABELS[k]).toBeTruthy()
    }
  })

  it('五类节点全部有颜色与标签', () => {
    const types: GraphNodeType[] = ['TENANT', 'AGENT', 'SKILL', 'TOOL', 'MCP_SERVER']
    for (const t of types) {
      expect(NODE_COLORS[t]).toMatch(/^#[0-9a-f]{6}$/i)
      expect(NODE_TYPE_LABELS[t]).toBeTruthy()
    }
  })

  it('负向边仅 overrides 显式移除两类', () => {
    expect(isNegativeEdge('OVERRIDE_REMOVED_SKILL')).toBe(true)
    expect(isNegativeEdge('OVERRIDE_REMOVED_TOOL')).toBe(true)
    expect(isNegativeEdge('ENABLED_SKILL')).toBe(false)
    expect(isNegativeEdge('TENANT_OF')).toBe(false)
  })

  it('impactTypeOf：TENANT 无影响口径，其余映射小写码', () => {
    expect(impactTypeOf('TENANT')).toBeNull()
    expect(impactTypeOf('AGENT')).toBe('agent')
    expect(impactTypeOf('SKILL')).toBe('skill')
    expect(impactTypeOf('TOOL')).toBe('tool')
    expect(impactTypeOf('MCP_SERVER')).toBe('mcp_server')
  })

  it('truncate：≤ 上限原样，超出截断加省略号', () => {
    expect(truncate('原生检索')).toBe('原生检索')
    expect(truncate('a'.repeat(11))).toBe('a'.repeat(11))
    expect(truncate('a'.repeat(12))).toBe(`${'a'.repeat(11)}…`)
  })
})

describe('graphLayout · filterNodes（类型过滤 + 边剪枝）', () => {
  const nodes = [
    node('agent:a-1', 'AGENT', 'a-1', 'A1'),
    node('agent:a-2', 'AGENT', 'a-2', 'A2'),
    node('skill:sk-1', 'SKILL', 'sk-1', 'S1'),
  ]
  const edges: GraphEdge[] = [
    { from: 'agent:a-1', to: 'skill:sk-1', kind: 'ENABLED_SKILL' },
    { from: 'agent:a-1', to: 'agent:a-2', kind: 'TENANT_OF' },
  ]

  it('空集合 = 全部可见（不猜过滤意图）', () => {
    const v = filterNodes(nodes, new Set(), edges)
    expect(v.nodes).toHaveLength(3)
    expect(v.edges).toHaveLength(2)
  })

  it('选中类型外的节点与相关边一并剔除', () => {
    const v = filterNodes(nodes, new Set<GraphNodeType>(['AGENT']), edges)
    expect(v.nodes.map((n) => n.id)).toEqual(['agent:a-1', 'agent:a-2'])
    expect(v.edges).toEqual([
      { from: 'agent:a-1', to: 'agent:a-2', kind: 'TENANT_OF' },
    ])
  })

  it('过滤后孤立边不残留（两端必须都可见）', () => {
    const v = filterNodes(nodes, new Set<GraphNodeType>(['SKILL']), edges)
    expect(v.nodes.map((n) => n.id)).toEqual(['skill:sk-1'])
    expect(v.edges).toEqual([])
  })
})

// ---- 页面 smoke（API 层 mock） ----

const graphFixture: DependencyGraphResponse = {
  tenantId: 't-1',
  nodes: [
    node('tenant:t-1', 'TENANT', 't-1', '租户 t-1'),
    node('agent:a-1', 'AGENT', 'a-1', '客服 Agent'),
    node('agent:a-2', 'AGENT', 'a-2', '答疑 Agent', { published: false }),
    node('skill:sk-1', 'SKILL', 'sk-1', '知识检索'),
    node('skill:sk-missing', 'SKILL', 'sk-missing', '幽灵技能', { exists: false }),
    node('tool:t-native', 'TOOL', 't-native', '原生检索'),
    node('mcp_server:srv-1', 'MCP_SERVER', 'srv-1', '搜索服务'),
  ],
  edges: [
    { from: 'agent:a-1', to: 'tenant:t-1', kind: 'TENANT_OF' },
    { from: 'agent:a-1', to: 'skill:sk-1', kind: 'ENABLED_SKILL' },
    { from: 'agent:a-1', to: 'skill:sk-missing', kind: 'ENABLED_SKILL' },
    { from: 'agent:a-2', to: 'skill:sk-1', kind: 'ENABLED_SKILL' },
    { from: 'agent:a-2', to: 'skill:sk-1', kind: 'OVERRIDE_REMOVED_SKILL' },
    { from: 'skill:sk-1', to: 'tool:t-native', kind: 'DECLARED_TOOL' },
    { from: 'tool:t-native', to: 'mcp_server:srv-1', kind: 'MCP_BINDING' },
  ],
  truncated: false,
}

describe('DependencyGraphPage · 图谱页 smoke', () => {
  it('加载后渲染标题、口径说明、节点与边', async () => {
    vi.mocked(getDependencyGraph).mockResolvedValue(graphFixture)
    const { container } = render(
      <MemoryRouter>
        <DependencyGraphPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText('能力闭包图谱')).toBeTruthy()
    // 口径说明（管理面最高版本口径，与运行期发布指针分开）
    expect(screen.getByText(/图谱口径/)).toBeTruthy()
    // 节点标签（SVG text）
    expect(screen.getByText('客服 Agent')).toBeTruthy()
    expect(screen.getByText('幽灵技能')).toBeTruthy()
    // 边渲染成 <path>（图形区 svg > g > path；antd 图标的 path 是 svg 直接子节点，不计入）
    expect(container.querySelectorAll('svg > g > path').length).toBe(
      graphFixture.edges.length,
    )
    // 计数条
    expect(screen.getByText(/节点 7 · 边 7/)).toBeTruthy()
  })

  it('截断时展示降级警告', async () => {
    vi.mocked(getDependencyGraph).mockResolvedValue({ ...graphFixture, truncated: true })
    render(
      <MemoryRouter>
        <DependencyGraphPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText('图谱过大已截断')).toBeTruthy()
  })

  it('加载失败 → 空态展示错误信息', async () => {
    vi.mocked(getDependencyGraph).mockRejectedValue(new Error('图谱加载失败：网络异常'))
    render(
      <MemoryRouter>
        <DependencyGraphPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText('图谱加载失败：网络异常')).toBeTruthy()
  })
})

const toolReport: ImpactReport = {
  entityType: 'TOOL',
  entityId: 't-native',
  entityLabel: '原生检索',
  entityExists: true,
  dependencies: [
    { type: 'MCP_SERVER', id: 'srv-1', label: '搜索服务', kind: 'MCP_BINDING' },
  ],
  dependents: [
    { type: 'SKILL', id: 'sk-1', label: '知识检索', kind: 'DECLARED_TOOL' },
  ],
  chains: [
    {
      path: [
        { type: 'TOOL', id: 't-native', label: '原生检索' },
        { type: 'SKILL', id: 'sk-1', label: '知识检索' },
        { type: 'AGENT', id: 'a-1', label: '客服 Agent' },
      ],
      agentId: 'a-1',
      agentLabel: '客服 Agent',
    },
  ],
  affectedAgentIds: ['a-1'],
  danglingRefs: [],
}

describe('ImpactAnalysisPage · 影响分析页 smoke', () => {
  it('URL 参数驱动：渲染出向依赖 / 入向引用 / 传递链 / 受影响 Agent', async () => {
    vi.mocked(getDependencyImpact).mockResolvedValue(toolReport)
    render(
      <MemoryRouter initialEntries={['/dependencies/impact?type=TOOL&id=t-native']}>
        <ImpactAnalysisPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText('影响分析')).toBeTruthy()
    expect(screen.getByText('出向依赖（引用了 1 个对象）')).toBeTruthy()
    expect(screen.getByText('入向引用（被 1 个对象直接引用）')).toBeTruthy()
    expect(screen.getByText('传递影响链（1 条）')).toBeTruthy()
    expect(screen.getByText('客服 Agent')).toBeTruthy()
    expect(vi.mocked(getDependencyImpact)).toHaveBeenCalledWith('TOOL', 't-native')
  })

  it('entityExists=false → 「实体不存在」告警（与无权限不作区分）', async () => {
    vi.mocked(getDependencyImpact).mockResolvedValue({
      ...toolReport,
      entityExists: false,
      dependencies: [],
      dependents: [],
      chains: [],
      affectedAgentIds: [],
    })
    render(
      <MemoryRouter initialEntries={['/dependencies/impact?type=TOOL&id=nope']}>
        <ImpactAnalysisPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText('实体不存在')).toBeTruthy()
    expect(screen.queryByText('出向依赖（引用了 0 个对象）')).toBeNull()
  })

  it('悬空引用渲染告警列表', async () => {
    vi.mocked(getDependencyImpact).mockResolvedValue({
      ...toolReport,
      danglingRefs: [{ type: 'TOOL', id: 't-missing', via: 'DECLARED_TOOL' }],
    })
    render(
      <MemoryRouter initialEntries={['/dependencies/impact?type=SKILL&id=sk-1']}>
        <ImpactAnalysisPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText(/悬空引用 1 处/)).toBeTruthy()
    expect(screen.getByText('t-missing')).toBeTruthy()
  })
})
