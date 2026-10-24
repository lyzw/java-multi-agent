import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Form,
  Input,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import { getDependencyImpact } from '@/api/adminApi'
import type {
  ImpactChain,
  ImpactEntityType,
  ImpactRef,
  ImpactReport,
} from '@/api/types'
import {
  EDGE_KIND_LABELS,
  NODE_TYPE_LABELS,
} from './graphLayout'

const TYPE_OPTIONS: Array<{ value: ImpactEntityType; label: string }> = [
  { value: 'AGENT', label: 'Agent（租户级）' },
  { value: 'SKILL', label: 'Skill（平台级）' },
  { value: 'TOOL', label: 'Tool（平台级）' },
  { value: 'MCP_SERVER', label: 'MCP Server（平台级）' },
]

/** 影响分析里的对象类型 → 展示标签（图谱 NodeType 口径 + TENANT 兜底）。 */
function refTypeLabel(type: string): string {
  return NODE_TYPE_LABELS[type as keyof typeof NODE_TYPE_LABELS] ?? type
}

function refTag(type: string): JSX.Element {
  const color =
    type === 'AGENT'
      ? 'blue'
      : type === 'SKILL'
        ? 'purple'
        : type === 'TOOL'
          ? 'cyan'
          : type === 'MCP_SERVER'
            ? 'orange'
            : 'default'
  return <Tag color={color}>{refTypeLabel(type)}</Tag>
}

/**
 * 影响分析页（P3 · /dependencies/impact?type=&id=）。
 *
 * <p>回答两个问题：目标<b>引用了谁</b>（出向依赖，详情页「依赖」页签同源）、
 * <b>改/删它会命中谁</b>（入向引用 + 传递链 + 受影响 Agent）。
 *
 * <p>⚠️ <b>只读</b>：本页不提供删除 / 停用入口。Agent 的变更通道是「发布新版本」；
 * 平台级对象（Tool/Skill/MCP）的变更在带外运维通道 —— 应用层不挂写入口，
 * 与 P0.5 作用域红线、P1 目录只读哲学一脉相承。
 */
export function ImpactAnalysisPage(): JSX.Element {
  const [searchParams, setSearchParams] = useSearchParams()
  const typeParam = (searchParams.get('type') ?? 'TOOL') as ImpactEntityType
  const idParam = searchParams.get('id') ?? ''

  const [formType, setFormType] = useState<ImpactEntityType>(typeParam)
  const [formId, setFormId] = useState(idParam)
  const [report, setReport] = useState<ImpactReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const run = useCallback((type: ImpactEntityType, id: string) => {
    if (!id.trim()) return
    setLoading(true)
    setError(null)
    setNotFound(false)
    setReport(null)
    getDependencyImpact(type, id.trim())
      .then((r) => {
        if (!r.entityExists) {
          setNotFound(true)
          return
        }
        setReport(r)
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : '查询失败'))
      .finally(() => setLoading(false))
  }, [])

  // URL 参数驱动（图谱页点击节点 / 详情页入口均带参数）
  useEffect(() => {
    setFormType(typeParam)
    setFormId(idParam)
    if (idParam) run(typeParam, idParam)
    // run 由 useCallback 稳定；仅参数变化触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [typeParam, idParam])

  const submit = (): void => {
    setSearchParams({ type: formType, id: formId })
  }

  return (
    <div>
      <Typography.Title level={3}>影响分析</Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="影响分析 = 删除守卫的判据（只读）"
        description="改 / 删一个对象前先看这里：入向引用与传递链说明「会命中谁」，悬空引用说明「目标自身的配置缺陷」。本页不提供变更入口 —— Agent 变更请发布新版本，平台级对象变更走带外运维通道。"
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={submit}>
          <Form.Item label="类型">
            <Select
              value={formType}
              style={{ width: 180 }}
              options={TYPE_OPTIONS}
              onChange={(v) => setFormType(v)}
            />
          </Form.Item>
          <Form.Item label="ID">
            <Input
              style={{ width: 280 }}
              placeholder="对象业务 id（agent_id / tool id / skill id / server id）"
              value={formId}
              onChange={(e) => setFormId(e.target.value)}
            />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={loading}>
              分析
            </Button>
          </Form.Item>
        </Form>
      </Card>

      {loading && <Spin spinning style={{ width: '100%', marginTop: 48 }} />}
      {error && <Empty description={error} />}
      {notFound && (
        <Alert
          type="error"
          showIcon
          message="实体不存在"
          description="该 id 在当前租户 / 平台目录中找不到定义（与「无权限」不作区分）。"
        />
      )}

      {report && (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Card size="small">
            <Descriptions column={2} size="small">
              <Descriptions.Item label="实体">
                <Space>
                  {refTag(report.entityType)}
                  <Typography.Text strong>{report.entityLabel}</Typography.Text>
                  <Typography.Text code>{report.entityId}</Typography.Text>
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="受影响 Agent">
                {report.affectedAgentIds.length === 0 ? (
                  <Typography.Text type="secondary">无（本租户内无 Agent 命中）</Typography.Text>
                ) : (
                  <Space wrap>
                    {report.affectedAgentIds.map((a) => (
                      <Tag key={a} color="blue">
                        {a}
                      </Tag>
                    ))}
                  </Space>
                )}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          {report.danglingRefs.length > 0 && (
            <Alert
              type="warning"
              showIcon
              message={`悬空引用 ${report.danglingRefs.length} 处（配置缺陷）`}
              description={
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {report.danglingRefs.map((d, i) => (
                    <li key={i}>
                      {EDGE_KIND_LABELS[d.via]} → {refTypeLabel(d.type)}{' '}
                      <Typography.Text code>{d.id}</Typography.Text>（无定义；授权路径对它
                      fail-closed 拒绝）
                    </li>
                  ))}
                </ul>
              }
            />
          )}

          <Card size="small" title={`出向依赖（引用了 ${report.dependencies.length} 个对象）`}>
            {report.dependencies.length === 0 ? (
              <Empty description="该对象不引用任何对象" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <RefTable refs={report.dependencies} />
            )}
          </Card>

          <Card size="small" title={`入向引用（被 ${report.dependents.length} 个对象直接引用）`}>
            {report.dependents.length === 0 ? (
              <Empty
                description={
                  report.entityType === 'AGENT'
                    ? 'V1 没有任何对象引用 Agent（inherits 写入即拒）—— 删除 / 停用 Agent 不会命中其他对象'
                    : '暂无直接引用者'
                }
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            ) : (
              <RefTable refs={report.dependents} />
            )}
          </Card>

          <Card size="small" title={`传递影响链（${report.chains.length} 条）`}>
            {report.chains.length === 0 ? (
              <Empty description="无传递链路" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <ChainTable chains={report.chains} />
            )}
          </Card>
        </Space>
      )}
    </div>
  )
}

function RefTable({ refs }: { refs: ImpactRef[] }): JSX.Element {
  return (
    <Table<ImpactRef>
      rowKey={(r) => `${r.type}:${r.id}:${r.kind}`}
      dataSource={refs}
      pagination={false}
      size="small"
      columns={[
        {
          title: '类型',
          dataIndex: 'type',
          width: 110,
          render: (v: string) => refTag(v),
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
      ]}
    />
  )
}

function ChainTable({ chains }: { chains: ImpactChain[] }): JSX.Element {
  return (
    <Table<ImpactChain>
      rowKey={(c, i) => `${c.agentId}-${i}`}
      dataSource={chains}
      pagination={false}
      size="small"
      columns={[
        {
          title: '链路（目标 → … → 受影响 Agent）',
          key: 'path',
          render: (_: unknown, c: ImpactChain) => (
            <Space wrap size={4}>
              {c.path.map((s, i) => (
                <Space key={`${s.id}-${i}`} size={4}>
                  {i > 0 && <Typography.Text type="secondary">→</Typography.Text>}
                  {refTag(s.type)}
                  <Typography.Text>{s.label}</Typography.Text>
                </Space>
              ))}
            </Space>
          ),
        },
        {
          title: '末端 Agent',
          dataIndex: 'agentId',
          width: 220,
          render: (v: string) => (
            <Typography.Text code>
              {v.slice(0, 8)}…
            </Typography.Text>
          ),
        },
      ]}
    />
  )
}

export default ImpactAnalysisPage
