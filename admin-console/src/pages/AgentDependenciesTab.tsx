import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Card, Empty, Space, Spin, Table, Tag, Typography } from 'antd'
import { getDependencyImpact } from '@/api/adminApi'
import type { ImpactRef, ImpactReport } from '@/api/types'
import { EDGE_KIND_LABELS } from './dependencies/graphLayout'

/**
 * Agent「依赖」页签（P3 · AgentDetailPage 第四个页签）。
 *
 * <p>数据源与影响分析页同源（{@code GET /api/admin/dependencies/impact?type=agent}）：
 * 只展示<b>出向依赖</b>（启用了哪些 Skill、显式移除了哪些对象）。
 *
 * <p>⚠️ <b>为什么不放「删除 Agent」</b>：V1 应用层不存在任何对象的物理删除端点
 * （平台级对象本就只读；Agent 的变更通道是「发布新版本」）。删除守卫的判据
 * （影响分析）在此呈现，但写动作不挂控制台 —— 与 P0.5 红线一致。
 */
export function AgentDependenciesTab({ agentId }: { agentId: string }): JSX.Element {
  const [report, setReport] = useState<ImpactReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    setLoading(true)
    getDependencyImpact('AGENT', agentId)
      .then((r) => {
        if (active) setReport(r)
      })
      .catch((e: unknown) => {
        if (active) setError(e instanceof Error ? e.message : '依赖加载失败')
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

  const deps = report?.dependencies ?? []
  const dangling = report?.danglingRefs ?? []

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="V1 没有任何对象引用 Agent"
        description="删除 / 停用本 Agent 不会命中其他对象（inherits 写入即拒，N1）。下方为该 Agent 引用（依赖）的对象。"
      />
      {dangling.length > 0 && (
        <Alert
          type="warning"
          showIcon
          message={`悬空引用 ${dangling.length} 处（配置缺陷）`}
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {dangling.map((d, i) => (
                <li key={i}>
                  {EDGE_KIND_LABELS[d.via]} → {d.type}{' '}
                  <Typography.Text code>{d.id}</Typography.Text>（无定义）
                </li>
              ))}
            </ul>
          }
        />
      )}
      <Card size="small" title={`依赖的对象（${deps.length}）`}>
        {deps.length === 0 ? (
          <Empty description="该 Agent 未引用任何 Skill / Tool" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Table<ImpactRef>
            rowKey={(r) => `${r.type}:${r.id}:${r.kind}`}
            dataSource={deps}
            pagination={false}
            size="small"
            columns={[
              {
                title: '类型',
                dataIndex: 'type',
                width: 110,
                render: (v: string) => (
                  <Tag color={v === 'SKILL' ? 'purple' : v === 'TOOL' ? 'cyan' : 'default'}>{v}</Tag>
                ),
              },
              { title: '名称', dataIndex: 'label' },
              {
                title: 'ID',
                dataIndex: 'id',
                render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
              },
              {
                title: '引用方式',
                dataIndex: 'kind',
                width: 180,
                render: (v: ImpactRef['kind']) => (
                  <Tag color={v.startsWith('OVERRIDE_REMOVED') ? 'red' : 'default'}>
                    {EDGE_KIND_LABELS[v]}
                  </Tag>
                ),
              },
              {
                title: '影响',
                key: 'impact',
                width: 90,
                render: (_: unknown, r: ImpactRef) => (
                  <Link
                    to={`/dependencies/impact?type=${r.type.toLowerCase()}&id=${encodeURIComponent(r.id)}`}
                  >
                    查看
                  </Link>
                ),
              },
            ]}
          />
        )}
      </Card>
      <Typography.Text type="secondary">
        需要看全租户依赖关系？前往 <Link to="/dependencies">能力闭包图谱</Link>。
      </Typography.Text>
    </Space>
  )
}

export default AgentDependenciesTab
