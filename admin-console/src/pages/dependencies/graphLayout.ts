import type { GraphEdge, GraphNode, GraphNodeType, GraphEdgeKind } from '@/api/types'

/**
 * 依赖图谱分层布局（P3 · 纯函数，可单测）。
 *
 * <p>布局策略：<b>按引用方向自上而下分层</b>——
 * Tenant(0) → Agent(1) → Skill(2) → Tool(3) → MCP Server(4)。
 * 所有引用边（含 overrides 负向边）在该分层下均向下或同层流动，
 * 不会出现回环交叉。
 *
 * <p>⚠️ 确定性：层内按 {@code (label, refId)} 排序，同输入必得同输出 ——
 * 图谱是运维决策素材，布局跳动会让「同一份数据两张图」难以比对。
 */

export const NODE_W = 156
export const NODE_H = 46
const GAP_X = 28
const GAP_Y = 36
const LAYER_GAP = 92
const MAX_COLS = 6
const PAD = 36

/** 节点类型 → 层号（引用方向自上而下）。 */
const LAYERS: Record<GraphNodeType, number> = {
  TENANT: 0,
  AGENT: 1,
  SKILL: 2,
  TOOL: 3,
  MCP_SERVER: 4,
}

export interface LayoutNode {
  node: GraphNode
  /** 中心 x */
  x: number
  /** 中心 y */
  y: number
  layer: number
}

export interface GraphLayout {
  nodes: LayoutNode[]
  width: number
  height: number
}

/**
 * 计算分层布局。
 *
 * @param nodes 图谱节点（顺序不限，内部排序保证确定性）
 * @return 每个节点的中心坐标 + 画布尺寸
 */
export function layoutGraph(nodes: GraphNode[]): GraphLayout {
  const layers = new Map<number, GraphNode[]>()
  for (const n of nodes) {
    const layer = LAYERS[n.type] ?? 2
    const list = layers.get(layer)
    if (list) list.push(n)
    else layers.set(layer, [n])
  }
  for (const list of layers.values()) {
    list.sort((a, b) => a.label.localeCompare(b.label) || a.refId.localeCompare(b.refId))
  }
  const ordered = [...layers.entries()].sort((a, b) => a[0] - b[0])

  // 单遍建「行」：每行记录层号 / 节点切片 / 行宽 / 行顶 y（无索引访问，类型严格）
  interface Row {
    layer: number
    slice: GraphNode[]
    width: number
    top: number
  }
  const rows: Row[] = []
  let y = PAD
  for (const [layer, list] of ordered) {
    for (let i = 0; i < list.length; i += MAX_COLS) {
      const slice = list.slice(i, i + MAX_COLS)
      const width = slice.length * NODE_W + (slice.length - 1) * GAP_X
      rows.push({ layer, slice, width, top: y })
      y += NODE_H + GAP_Y
    }
    y += LAYER_GAP - GAP_Y
  }

  const contentWidth = rows.length > 0 ? Math.max(...rows.map((r) => r.width)) : 0
  const width = contentWidth + PAD * 2
  const height = Math.max(y - GAP_Y + PAD, NODE_H + PAD * 2)

  // 定位：行内居中于内容宽度
  const out: LayoutNode[] = []
  for (const row of rows) {
    const startX = PAD + (contentWidth - row.width) / 2
    row.slice.forEach((node, col) => {
      out.push({
        node,
        x: startX + col * (NODE_W + GAP_X) + NODE_W / 2,
        y: row.top + NODE_H / 2,
        layer: row.layer,
      })
    })
  }
  return { nodes: out, width, height }
}

/**
 * 边的 SVG 路径（分层布局下的 S 形曲线）。
 *
 * <p>向下边走 S 曲线（控制点垂直分离，避免穿过中间层节点文字）；
 * 非向下边（理论上不出现）退化为直线。
 */
export function edgePath(a: LayoutNode, b: LayoutNode): string {
  const dx = b.x - a.x
  const dy = b.y - a.y
  if (dy <= 0 || Math.abs(dx) < 1) {
    return `M ${a.x} ${a.y} L ${b.x} ${b.y}`
  }
  const mid = dy / 2
  return `M ${a.x} ${a.y} C ${a.x} ${a.y + mid}, ${b.x} ${b.y - mid}, ${b.x} ${b.y}`
}

/** 边类型 → 展示标签。 */
export const EDGE_KIND_LABELS: Record<GraphEdgeKind, string> = {
  TENANT_OF: '租户所有',
  ENABLED_SKILL: '启用 Skill',
  DECLARED_TOOL: '声明 Tool',
  DECLARED_MCP: '声明 MCP',
  MCP_BINDING: 'MCP 绑定',
  OVERRIDE_REMOVED_SKILL: '显式移除 Skill',
  OVERRIDE_REMOVED_TOOL: '显式移除 Tool',
}

/** 负向边（overrides 显式移除）—— 渲染为虚线以示「配置里写着但不生效」。 */
export function isNegativeEdge(kind: GraphEdgeKind): boolean {
  return kind === 'OVERRIDE_REMOVED_SKILL' || kind === 'OVERRIDE_REMOVED_TOOL'
}

/** 节点类型 → 主题色（浅色主题）。 */
export const NODE_COLORS: Record<GraphNodeType, string> = {
  TENANT: '#8c8c8c',
  AGENT: '#1677ff',
  SKILL: '#722ed1',
  TOOL: '#13c2c2',
  MCP_SERVER: '#fa8c16',
}

/** 节点类型 → 中文标签。 */
export const NODE_TYPE_LABELS: Record<GraphNodeType, string> = {
  TENANT: '租户',
  AGENT: 'Agent',
  SKILL: 'Skill',
  TOOL: 'Tool',
  MCP_SERVER: 'MCP',
}

/** 图谱节点类型 → 影响分析查询类型（URL 参数口径）。 */
export function impactTypeOf(type: GraphNodeType): string | null {
  switch (type) {
    case 'AGENT':
      return 'agent'
    case 'TOOL':
      return 'tool'
    case 'SKILL':
      return 'skill'
    case 'MCP_SERVER':
      return 'mcp_server'
    default:
      return null
  }
}

/** 截断展示文本（节点盒内单行）。 */
export function truncate(text: string, max = 11): string {
  return text.length > max ? `${text.slice(0, max)}…` : text
}

/** 过滤后的可见节点集合（类型过滤 + 搜索命中高亮由调用方处理）。 */
export function filterNodes(
  nodes: GraphNode[],
  activeTypes: Set<GraphNodeType>,
  edges: GraphEdge[],
): { nodes: GraphNode[]; edges: GraphEdge[] } {
  const visibleTypes = activeTypes.size === 0 ? null : activeTypes
  const visible = nodes.filter((n) => !visibleTypes || visibleTypes.has(n.type))
  const ids = new Set(visible.map((n) => n.id))
  return {
    nodes: visible,
    edges: edges.filter((e) => ids.has(e.from) && ids.has(e.to)),
  }
}
