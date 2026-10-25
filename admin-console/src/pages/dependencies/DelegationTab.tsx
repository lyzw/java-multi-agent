import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Card, Empty, Space, Spin, Table, Tag, Typography } from 'antd'
import { getDelegationChildren, getDelegationParents } from '@/api/adminApi'
import type { DelegationEdge } from '@/api/types'

/**
 * Agent 详情页的「委派拓扑」页签（第三十四批 · C34-3）。
 *
 * <p>⚠️ <b>出向与入向是两个问题</b>，不能合并：
 * <ul>
 *   <li><b>出向</b>（我声明了哪些子）= 「我的委派拓扑」；</li>
 *   <li><b>入向</b>（谁声明了我）= 停用风险的<b>提示</b>（⚠️ 无自动拦截）——
 *       入向非空意味着停用/删除本 Agent 会让那个父悬空。</li>
 * </ul>
 * 只画出向就答不了「能不能停用」，而那正是运维最常问的问题。
 *
 * <p>⚠️ <b>入向不限子版本</b>：被停用的是那个 <b>Agent</b>，不是某一版 ——
 * 任何父引用了它的任何一版，都构成「抽走它会让那个父悬空」的事实。
 */
export function DelegationTab({ agentId }: { agentId: string }): JSX.Element {
  const [outgoing, setOutgoing] = useState<DelegationEdge[]>([])
  const [incoming, setIncoming] = useState<DelegationEdge[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    setLoading(true)
    Promise.all([getDelegationChildren(agentId), getDelegationParents(agentId)])
      .then(([out, inc]) => {
        if (!active) return
        setOutgoing(out)
        setIncoming(inc)
        setError(null)
      })
      .catch((e: unknown) => {
        if (active) setError(e instanceof Error ? e.message : '加载委派拓扑失败')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [agentId])

  if (loading) return <Spin spinning style={{ width: '100%', marginTop: 48 }} />
  if (error) return <Empty description={error} />

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type={incoming.length > 0 ? 'warning' : 'info'}
        showIcon
        message={
          incoming.length > 0
            ? `有 ${incoming.length} 个父 Agent 依赖本 Agent —— 停用本 Agent 会让它们悬空`
            : '没有父 Agent 依赖本 Agent'
        }
        description={
          '委派边是「发布关系投影」（边的唯一来源是发布期投影，不可手工写）。' +
          '⚠️ 入向非空**只是提示**，不是拦截：当前**没有**任何自动守卫会因此阻止停用，' +
          '停用仍照常放行 —— 需要人工判断与处置。'
        }
      />

      <Card size="small" title={`出向：声明的子 Agent（${outgoing.length}）`}>
        {outgoing.length === 0 ? (
          <Empty description="本 Agent 未声明任何子 Agent（LEAF 或尚未声明）" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Table<DelegationEdge>
            rowKey={(r) => `${r.childAgentId}@${r.childReleaseVersion}:${r.ordinal}`}
            size="small"
            pagination={false}
            dataSource={outgoing}
            columns={[
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
              { title: '声明次序', dataIndex: 'ordinal' },
              {
                title: '是否必需',
                dataIndex: 'required',
                render: (v: boolean) => (v ? <Tag color="red">必需</Tag> : <Tag>可选</Tag>),
              },
            ]}
          />
        )}
      </Card>

      <Card size="small" title={`入向：依赖本 Agent 的父（${incoming.length}）`}>
        {incoming.length === 0 ? (
          <Empty description="没有父 Agent 声明本 Agent" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Table<DelegationEdge>
            rowKey={(r) => `${r.parentAgentId}@${r.parentReleaseVersion}:${r.ordinal}`}
            size="small"
            pagination={false}
            dataSource={incoming}
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
              { title: '声明次序', dataIndex: 'ordinal' },
              {
                title: '是否必需',
                dataIndex: 'required',
                render: (v: boolean) => (v ? <Tag color="red">必需</Tag> : <Tag>可选</Tag>),
              },
            ]}
          />
        )}
      </Card>
    </Space>
  )
}

export default DelegationTab
