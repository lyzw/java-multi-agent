import type { DelegationEdge } from '@/api/types'
import { NODE_H, NODE_W, type LayoutNode } from './graphLayout'

/**
 * 委派拓扑的**分层布局**（第三十四批 · C34-3 · 纯函数，可单测）。
 *
 * <p>⚠️ <b>为什么不用 {@code layoutGraph}</b>：那张图的层是
 * Tenant → Agent → Skill → Tool → MCP 的**固定五层**，
 * 而委派拓扑是 **Agent → Agent**（深度由声明决定，没有固定层数）。
 * 套用固定分层会把所有 Agent 压在同一层，拓扑结构就看不见了。
 * 盒子尺寸与连线路径仍复用 {@code graphLayout} 的工具箱，保持视觉一致。
 *
 * <p>⚠️ <b>深度按「到最深后代的距离」计算</b>：父的深度恒大于子，
 * 于是父永远画在子上面。自环/环靠 {@code visiting} 集合断开 ——
 * 不断开的话深度会随迭代次数无限增长，把画布撑成一条长带。
 */
export interface DelegationNodeLayout {
  agentId: string
  /** 中心 x */
  x: number
  /** 中心 y */
  y: number
  /** 层号（0 = 最上层的根 Agent） */
  layer: number
}

export interface DelegationEdgeLayout {
  edge: DelegationEdge
  from: DelegationNodeLayout
  to: DelegationNodeLayout
}

export interface DelegationLayout {
  nodes: DelegationNodeLayout[]
  edges: DelegationEdgeLayout[]
  width: number
  height: number
}

const GAP_X = 28
const GAP_Y = 36
const LAYER_GAP = 92
const MAX_COLS = 6
const PAD = 36

/** 计算每个 Agent 到「最深后代」的距离（memo + 回边断开）。 */
export function computeDelegationDepths(edges: DelegationEdge[]): Map<string, number> {
  const childrenOf = new Map<string, string[]>()
  for (const edge of edges) {
    const list = childrenOf.get(edge.parentAgentId)
    if (list) list.push(edge.childAgentId)
    else childrenOf.set(edge.parentAgentId, [edge.childAgentId])
  }

  const memo = new Map<string, number>()
  const visiting = new Set<string>()

  const depthOf = (agentId: string): number => {
    const cached = memo.get(agentId)
    if (cached !== undefined) return cached
    // ⚠️ 命中 visiting = 回边（环）：此处不再推进深度，否则环会让深度无限增长。
    if (visiting.has(agentId)) return 0
    visiting.add(agentId)
    let depth = 0
    for (const child of childrenOf.get(agentId) ?? []) {
      depth = Math.max(depth, depthOf(child) + 1)
    }
    visiting.delete(agentId)
    memo.set(agentId, depth)
    return depth
  }

  const all = new Set<string>()
  for (const edge of edges) {
    all.add(edge.parentAgentId)
    all.add(edge.childAgentId)
  }
  for (const agentId of all) depthOf(agentId)
  return memo
}

/**
 * 计算委派拓扑布局。
 *
 * @param edges 委派边（顺序不限，内部排序保证确定性）
 */
export function layoutDelegations(edges: DelegationEdge[]): DelegationLayout {
  const ids = new Set<string>()
  for (const edge of edges) {
    ids.add(edge.parentAgentId)
    ids.add(edge.childAgentId)
  }
  if (ids.size === 0) {
    return { nodes: [], edges: [], width: PAD * 2, height: PAD * 2 }
  }

  const depths = computeDelegationDepths(edges)
  const maxDepth = Math.max(0, ...[...ids].map((id) => depths.get(id) ?? 0))
  // ⚠️ 深度取「到最深后代的距离」，故反转层号：根（深度最大）在最上层。
  const layerOf = (id: string): number => maxDepth - (depths.get(id) ?? 0)

  const layers = new Map<number, string[]>()
  for (const id of [...ids].sort()) {
    const layer = layerOf(id)
    const list = layers.get(layer)
    if (list) list.push(id)
    else layers.set(layer, [id])
  }
  const ordered = [...layers.entries()].sort((a, b) => a[0] - b[0])

  // 单遍建「行」：每行记录切片 / 行宽 / 行顶 y（无索引访问，类型严格）
  interface Row {
    slice: string[]
    width: number
    top: number
  }
  const rows: Row[] = []
  let y = PAD
  let contentWidth = 0
  for (const [, list] of ordered) {
    for (let i = 0; i < list.length; i += MAX_COLS) {
      const slice = list.slice(i, i + MAX_COLS)
      const width = slice.length * NODE_W + (slice.length - 1) * GAP_X
      contentWidth = Math.max(contentWidth, width)
      rows.push({ slice, width, top: y })
      y += NODE_H + GAP_Y
    }
    y += LAYER_GAP - GAP_Y
  }

  // 行内居中（与 layoutGraph 同口径）
  const positions = new Map<string, DelegationNodeLayout>()
  rows.forEach((row, rowIndex) => {
    const startX = PAD + (contentWidth - row.width) / 2
    row.slice.forEach((agentId, col) => {
      positions.set(agentId, {
        agentId,
        x: startX + col * (NODE_W + GAP_X) + NODE_W / 2,
        y: row.top + NODE_H / 2,
        layer: rowIndex,
      })
    })
  })

  const nodes = [...positions.values()].sort((a, b) => a.layer - b.layer || a.agentId.localeCompare(b.agentId))
  const laidOutEdges: DelegationEdgeLayout[] = []
  for (const edge of edges) {
    const from = positions.get(edge.parentAgentId)
    const to = positions.get(edge.childAgentId)
    if (from && to) laidOutEdges.push({ edge, from, to })
  }
  // ⚠️ **确定性**：边也排序 —— 图谱是运维决策素材，
  //    「同一份数据两张图」必须逐字节一致（与 layoutGraph 同口径）。
  laidOutEdges.sort((a, b) =>
    a.edge.parentAgentId.localeCompare(b.edge.parentAgentId) ||
    a.edge.childAgentId.localeCompare(b.edge.childAgentId) ||
    a.edge.ordinal - b.edge.ordinal,
  )

  const width = contentWidth + PAD * 2
  const height = Math.max(y - GAP_Y + PAD, NODE_H + PAD * 2)
  return { nodes, edges: laidOutEdges, width, height }
}

/**
 * 把委派节点包装成 {@code layoutGraph} 的 LayoutNode，以便复用连线路径。
 *
 * ⚠️ 这层包装只为复用 {@code edgePath} 的 S 曲线算法，不代表委派图
 * 与能力闭包图是同一张图（层含义完全不同）。
 */
export function asGraphLayoutNode(node: DelegationNodeLayout): LayoutNode {
  return {
    node: {
      id: `agent:${node.agentId}`,
      type: 'AGENT',
      refId: node.agentId,
      label: node.agentId,
      detail: null,
      exists: true,
      published: true,
      version: 0,
    },
    x: node.x,
    y: node.y,
    layer: node.layer,
  }
}
