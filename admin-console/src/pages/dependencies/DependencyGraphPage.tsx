import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Empty,
  Input,
  Space,
  Spin,
  Typography,
} from 'antd'
import { ApartmentOutlined, ReloadOutlined } from '@ant-design/icons'
import { getDependencyGraph } from '@/api/adminApi'
import type {
  DependencyGraphResponse,
  GraphEdgeKind,
  GraphNode,
  GraphNodeType,
} from '@/api/types'
import {
  NODE_COLORS,
  NODE_H,
  NODE_TYPE_LABELS,
  NODE_W,
  edgePath,
  filterNodes,
  impactTypeOf,
  isNegativeEdge,
  layoutGraph,
  truncate,
} from './graphLayout'

const ALL_TYPES: GraphNodeType[] = ['TENANT', 'AGENT', 'SKILL', 'TOOL', 'MCP_SERVER']

/** 边 → 描边样式（负向边虚线示警）。 */
function edgeStyle(kind: GraphEdgeKind): { stroke: string; dash: string } {
  if (kind === 'TENANT_OF') return { stroke: '#bfbfbf', dash: '2 4' }
  if (isNegativeEdge(kind)) return { stroke: '#ff7875', dash: '6 4' }
  return { stroke: '#91caff', dash: '' }
}

/**
 * 能力闭包图谱页（P3 · /dependencies）。
 *
 * <p>⚠️ <b>只读</b>：本页不提供任何变更入口 —— 改 / 删对象走各自既有通道
 * （Agent 发新版本、平台对象走带外运维）。图谱是「看清依赖」的决策素材，
 * 点击节点进入影响分析页（同样是只读）。
 */
export function DependencyGraphPage(): JSX.Element {
  const navigate = useNavigate()
  const [graph, setGraph] = useState<DependencyGraphResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeTypes, setActiveTypes] = useState<Set<GraphNodeType>>(new Set())
  const [search, setSearch] = useState('')
  const [zoom, setZoom] = useState(1)

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    getDependencyGraph()
      .then((g) => setGraph(g))
      .catch((e: unknown) =>
        setError(e instanceof Error ? e.message : '图谱加载失败'),
      )
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const view = useMemo(() => {
    if (!graph) return null
    const filtered = filterNodes(graph.nodes, activeTypes, graph.edges)
    const layout = layoutGraph(filtered.nodes)
    const posById = new Map(layout.nodes.map((l) => [l.node.id, l]))
    return { filtered, layout, posById }
  }, [graph, activeTypes])

  const counts = useMemo(() => {
    if (!graph) return { nodes: 0, edges: 0, phantom: 0 }
    return {
      nodes: graph.nodes.length,
      edges: graph.edges.length,
      phantom: graph.nodes.filter((n) => !n.exists).length,
    }
  }, [graph])

  const searchHit = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return null
    return new Set(
      (graph?.nodes ?? [])
        .filter(
          (n) =>
            n.label.toLowerCase().includes(q) ||
            n.refId.toLowerCase().includes(q),
        )
        .map((n) => n.id),
    )
  }, [graph, search])

  const openImpact = (node: GraphNode): void => {
    const type = impactTypeOf(node.type)
    if (!type) return // TENANT 根节点无影响分析口径
    navigate(`/dependencies/impact?type=${type}&id=${encodeURIComponent(node.refId)}`)
  }

  return (
    <div>
      <Typography.Title level={3}>
        <ApartmentOutlined /> 能力闭包图谱
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="图谱口径：每个对象的最高版本行（含未发布草稿）"
        description={
          <>
            边方向 = 引用方向（Agent ─▶ Skill ─▶ Tool / MCP）。运行期生效闭包以发布指针为准
            （授权路径 fail-closed 解析）；本图谱回答「声明了什么、改它会命中谁」。
            悬空引用（{counts.phantom}）以虚线幽灵节点保留 —— 那是配置缺陷信号。
            点击任意节点查看影响分析（同样只读）。
          </>
        }
      />

      <Card
        size="small"
        style={{ marginBottom: 16 }}
        bodyStyle={{ padding: '12px 16px' }}
      >
        <Space wrap size="middle">
          <Checkbox.Group
            options={ALL_TYPES.map((t) => ({
              label: NODE_TYPE_LABELS[t],
              value: t,
            }))}
            value={[...activeTypes]}
            onChange={(values) => setActiveTypes(new Set(values as GraphNodeType[]))}
          />
          <Input.Search
            allowClear
            placeholder="搜索节点名称 / ID（高亮）"
            style={{ width: 260 }}
            onSearch={setSearch}
            onChange={(e) => setSearch(e.target.value)}
          />
          <Space>
            <Button size="small" onClick={() => setZoom((z) => Math.max(0.4, +(z - 0.1).toFixed(2)))}>
              −
            </Button>
            <Typography.Text>{Math.round(zoom * 100)}%</Typography.Text>
            <Button size="small" onClick={() => setZoom((z) => Math.min(1.6, +(z + 0.1).toFixed(2)))}>
              +
            </Button>
            <Button size="small" onClick={() => setZoom(1)}>
              重置
            </Button>
          </Space>
          <Button size="small" icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Typography.Text type="secondary">
            节点 {counts.nodes} · 边 {counts.edges}
          </Typography.Text>
        </Space>
      </Card>

      {graph?.truncated && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="图谱过大已截断"
          description="超出展示上限的部分未渲染。请改用影响分析页按实体查询（按需展开）。"
        />
      )}

      <Card>
        {loading ? (
          <Spin spinning style={{ width: '100%', marginTop: 48 }} />
        ) : error ? (
          <Empty description={error} />
        ) : !view || view.filtered.nodes.length === 0 ? (
          <Empty description="没有可渲染的节点" />
        ) : (
          <div style={{ overflow: 'auto', maxHeight: '72vh' }}>
            <svg
              width={view.layout.width * zoom}
              height={view.layout.height * zoom}
              viewBox={`0 0 ${view.layout.width} ${view.layout.height}`}
              style={{ display: 'block', margin: '0 auto' }}
            >
              {/* 边先画，节点后画（节点盖住边端） */}
              <g>
                {view.filtered.edges.map((e, i) => {
                  const a = view.posById.get(e.from)
                  const b = view.posById.get(e.to)
                  if (!a || !b) return null
                  const style = edgeStyle(e.kind)
                  return (
                    <path
                      key={`e-${i}`}
                      d={edgePath(a, b)}
                      fill="none"
                      stroke={style.stroke}
                      strokeWidth={1.5}
                      strokeDasharray={style.dash || undefined}
                    />
                  )
                })}
              </g>
              <g>
                {view.layout.nodes.map(({ node, x, y }) => {
                  const color = NODE_COLORS[node.type]
                  const clickable = impactTypeOf(node.type) !== null
                  const hit = searchHit?.has(node.id) ?? false
                  return (
                    <g
                      key={node.id}
                      transform={`translate(${x - NODE_W / 2}, ${y - NODE_H / 2})`}
                      style={{ cursor: clickable ? 'pointer' : 'default' }}
                      onClick={() => clickable && openImpact(node)}
                    >
                      <rect
                        width={NODE_W}
                        height={NODE_H}
                        rx={8}
                        fill="#ffffff"
                        stroke={hit ? '#faad14' : node.exists ? color : '#ff4d4f'}
                        strokeWidth={hit ? 3 : node.exists ? 1.5 : 1.5}
                        strokeDasharray={node.exists ? undefined : '5 3'}
                      />
                      <circle cx={12} cy={12} r={4} fill={color} />
                      <text x={22} y={16} fontSize={10} fill="#8c8c8c">
                        {NODE_TYPE_LABELS[node.type]}
                      </text>
                      <text x={8} y={33} fontSize={12} fill="#262626" fontWeight={500}>
                        {truncate(node.label)}
                      </text>
                      {!node.published && node.type !== 'TENANT' && (
                        <text x={NODE_W - 8} y={33} fontSize={10} fill="#faad14" textAnchor="end">
                          草稿
                        </text>
                      )}
                      {!node.exists && (
                        <text x={NODE_W - 8} y={16} fontSize={10} fill="#ff4d4f" textAnchor="end">
                          悬空
                        </text>
                      )}
                    </g>
                  )
                })}
              </g>
            </svg>
          </div>
        )}
      </Card>

      <Card size="small" style={{ marginTop: 16 }} title="图例">
        <Space wrap size="middle">
          {ALL_TYPES.map((t) => (
            <Space key={t} size={4}>
              <span
                style={{
                  display: 'inline-block',
                  width: 10,
                  height: 10,
                  borderRadius: 2,
                  background: NODE_COLORS[t],
                }}
              />
              <Typography.Text>{NODE_TYPE_LABELS[t]}</Typography.Text>
            </Space>
          ))}
          <Typography.Text type="secondary" style={{ marginLeft: 16 }}>
            实线 = 引用；红色虚线 = overrides 显式移除（配置里写着但不生效）；
            灰色点线 = 租户所有；红色虚框 = 悬空引用（无定义）。
          </Typography.Text>
        </Space>
      </Card>
    </div>
  )
}

export default DependencyGraphPage
