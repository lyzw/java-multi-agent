import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Card, Empty, Space, Spin, Table, Tag, Typography } from 'antd'
import { ShareAltOutlined } from '@ant-design/icons'
import { getDelegations } from '@/api/adminApi'
import type { DelegationEdge } from '@/api/types'
import { asGraphLayoutNode, layoutDelegations } from './delegationLayout'
import { edgePath } from './graphLayout'

/**
 * 委派拓扑全图（第三十四批 · C34-3）。
 *
 * <p>⚠️ <b>读的是「发布关系投影」，不是权威定义</b>：
 * 权威定义是 Agent release 里的 {@code childAgentRefs}，
 * 本表只是把「边」投影出来便于查询与分析，<b>可重建</b>。
 * 所以本视图**只读** —— 手工写一条边会让「投影」与「release」两个真相分叉。
 *
 * <p>⚠️ <b>为什么需要它</b>：「谁依赖我」是停用风险的**硬需求**（⚠️ 当前只是提示，无自动拦截）——
 * 没有它，运维只能靠全表扫边来回答「这个子 Agent 能不能被停用」，
 * 而漏扫一次就等于放行了一次「被引用对象被抽走」的事故。
 */
export function DelegationTopologyPage(): JSX.Element {
  const [edges, setEdges] = useState<DelegationEdge[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setEdges(await getDelegations())
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载委派拓扑失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  if (loading) return <Spin spinning style={{ width: '100%', marginTop: 80 }} />

  if (error) {
    return (
      <Alert type="error" showIcon message="加载委派拓扑失败" description={error} />
    )
  }

  const layout = layoutDelegations(edges)

  return (
    <div>
      <Typography.Title level={3}>
        <Space><ShareAltOutlined /> 委派拓扑</Space>
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="谁把工作交给谁"
        description={
          '本页是「发布关系投影」的只读视图（边的唯一来源是发布期投影，不可手工写）。' +
          '出向边回答「我依赖谁」，入向边回答「谁依赖我」。' +
          '⚠️ 入向非空**只是提示**，不是拦截：当前**没有**任何自动守卫会因此' +
          '阻止停用 —— 停用仍照常放行。停用它会让那个父悬空，需要人工判断与处置。'
        }
      />
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Card size="small" title={`拓扑图（${layout.nodes.length} 个 Agent · ${edges.length} 条边）`}>
          {layout.nodes.length === 0 ? (
            <Empty description="本租户尚无委派关系" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            <div style={{ overflow: 'auto', maxHeight: 520 }}>
              <svg
                width={layout.width}
                height={layout.height}
                role="img"
                aria-label="委派拓扑图"
                style={{ display: 'block' }}
              >
                {layout.edges.map(({ edge, from, to }, index) => (
                  <path
                    key={`${edge.parentAgentId}->${edge.childAgentId}@${edge.ordinal}-${index}`}
                    d={edgePath(asGraphLayoutNode(from), asGraphLayoutNode(to))}
                    fill="none"
                    stroke={edge.required ? '#1677ff' : '#bfbfbf'}
                    strokeWidth={edge.required ? 2 : 1.5}
                    strokeDasharray={edge.required ? undefined : '4 3'}
                  />
                ))}
                {layout.nodes.map((node) => (
                  <g key={node.agentId}>
                    <rect
                      x={node.x - 78}
                      y={node.y - 23}
                      width={156}
                      height={46}
                      rx={6}
                      fill="#e6f4ff"
                      stroke="#1677ff"
                    />
                    <text
                      x={node.x}
                      y={node.y + 4}
                      textAnchor="middle"
                      fontSize={11}
                      fill="#0958d9"
                    >
                      {node.agentId.length > 18 ? `${node.agentId.slice(0, 18)}…` : node.agentId}
                    </text>
                  </g>
                ))}
              </svg>
            </div>
          )}
        </Card>

        <Card size="small" title={`边清单（${edges.length}）`}>
          <Table<DelegationEdge>
            rowKey={(r) => `${r.parentAgentId}->${r.childAgentId}@${r.parentReleaseVersion}:${r.childReleaseVersion}:${r.ordinal}`}
            size="small"
            pagination={false}
            locale={{ emptyText: '本租户尚无委派关系' }}
            dataSource={edges}
            columns={[
              {
                title: '父 Agent',
                dataIndex: 'parentAgentId',
                render: (v: string) => (
                  <Link to={`/agents/${encodeURIComponent(v)}`}>
                    <Typography.Text code>{v}</Typography.Text>
                  </Link>
                ),
              },
              { title: '父版本', dataIndex: 'parentReleaseVersion', render: (v: number) => <Tag color="blue">v{v}</Tag> },
              {
                title: '子 Agent',
                dataIndex: 'childAgentId',
                render: (v: string) => (
                  <Link to={`/agents/${encodeURIComponent(v)}`}>
                    <Typography.Text code>{v}</Typography.Text>
                  </Link>
                ),
              },
              { title: '子版本', dataIndex: 'childReleaseVersion', render: (v: number) => <Tag color="blue">v{v}</Tag> },
              { title: '次序', dataIndex: 'ordinal' },
              {
                title: '是否必需',
                dataIndex: 'required',
                render: (v: boolean) => (v ? <Tag color="red">必需</Tag> : <Tag>可选</Tag>),
              },
              { title: '投影时间', dataIndex: 'createdAt' },
            ]}
          />
        </Card>
      </Space>
    </div>
  )
}

export default DelegationTopologyPage
