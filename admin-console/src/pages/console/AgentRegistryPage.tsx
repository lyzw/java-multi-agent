import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Card, Table, Tabs, Tag, Tooltip, Typography } from 'antd'
import type { TableColumnsType, TabsProps } from 'antd'
import { TeamOutlined } from '@ant-design/icons'
import { getConsoleAgentRegistry } from '@/api/adminApi'
import type { PlatformAgentRegistryItem } from '@/api/types'
import { lifecycleTag, tagList, timeCell } from '@/pages/catalog/displayBits'
import { describeCatalogLoadError } from './platformRequestBits'

/**
 * 跨租户 Agent 注册表（平台链 /api/platform/v1/capabilities/agents · 只读盘点）。
 *
 * <p>⚠️ **页内按租户分组**：为跨租户看全部 agent，第一个 Tab「全部租户」，
 * 其后每个出现的 tenantId 一个 Tab；每个 tab 只按租户过滤行，不重复请求。
 */
function registryColumns(): TableColumnsType<PlatformAgentRegistryItem> {
  return [
    { title: 'agentId', dataIndex: 'agentId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: '名称', dataIndex: 'name', render: (v: string | null) => v ?? '-' },
    // ⚠️ 状态读不到（null）时如实呈现「未提供」—— 不得回落成「已发布」。
    { title: '生命周期', dataIndex: 'lifecycleState', render: (v: string | null) => lifecycleTag(v) },
    {
      title: '定义版本',
      dataIndex: 'defVersion',
      render: (v: number | null) => (v === null ? <Typography.Text type="secondary">无定义</Typography.Text> : <Tag color="blue">v{v}</Tag>),
    },
    { title: '版本数', dataIndex: 'versionCount' },
    {
      title: '最新版本',
      dataIndex: 'latestVersionNumber',
      render: (v: number | null) => (v === null ? <Typography.Text type="secondary">-</Typography.Text> : `v${v}`),
    },
    { title: '最新发布时间', dataIndex: 'latestPublishedAt', render: (v: string | null) => timeCell(v) },
    { title: '角色', dataIndex: 'roles', render: (v: string[]) => tagList(v, '（无）', 'geekblue') },
    {
      title: '启用技能',
      dataIndex: 'enabledSkills',
      // ⚠️ 列里只给数量；清单悬浮查看 —— 列是拿来「扫」的，不是拿来读清单的。
      render: (v: string[]) =>
        v.length === 0 ? (
          <Typography.Text type="secondary">（无）</Typography.Text>
        ) : (
          <Tooltip title={v.join('、')}>
            <Tag color="purple">{v.length} 个</Tag>
          </Tooltip>
        ),
    },
    { title: '能力标签', dataIndex: 'capabilityTags', render: (v: string[]) => tagList(v, '（无）') },
    {
      title: '收紧覆盖',
      dataIndex: 'hasOverrides',
      render: (b: boolean) =>
        b ? <Tag color="gold">有覆盖</Tag> : <Typography.Text type="secondary">无</Typography.Text>,
    },
  ]
}

export function AgentRegistryPage(): JSX.Element {
  const [rows, setRows] = useState<PlatformAgentRegistryItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setRows(await getConsoleAgentRegistry())
    } catch (e) {
      setError(describeCatalogLoadError(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // ⚠️ 每个 tab 只做**前端过滤**，不重复请求（一份全量数据当所有 tab 的源）。
  const tenantIds = useMemo(() => [...new Set(rows.map((r) => r.tenantId))], [rows])
  const columns = useMemo(() => registryColumns(), [])
  const renderTable = (data: PlatformAgentRegistryItem[]): JSX.Element => (
    <Table<PlatformAgentRegistryItem>
      rowKey={(r) => `${r.tenantId}::${r.agentId}`}
      loading={loading}
      dataSource={data}
      columns={columns}
      pagination={false}
      size="small"
      scroll={{ x: 'max-content' }}
      locale={{ emptyText: '暂无数据' }}
    />
  )

  const items: TabsProps['items'] = [
    { key: 'ALL', label: <span>全部租户<span style={{ marginLeft: 4, opacity: 0.6 }}>({rows.length})</span></span>, children: renderTable(rows) },
    ...tenantIds.map((tenantId) => ({
      key: tenantId,
      label: <span>{tenantId}<span style={{ marginLeft: 4, opacity: 0.6 }}>({rows.filter((r) => r.tenantId === tenantId).length})</span></span>,
      children: renderTable(rows.filter((r) => r.tenantId === tenantId)),
    })),
  ]

  return (
    <div>
      <Typography.Title level={3}>
        <TeamOutlined /> Agent 注册表（跨租户）
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="只读盘点——仅含管理元数据，定义正文与运行输出不出平台边界；平台不提供 Agent 写端点（禁区）"
        description="本注册表只呈现租户 Agent 的管理元数据（版本数、生命周期、角色、启用技能、能力标签等）。Agent 的定义正文与运行输出不跨租户出平台边界；跨租户的 Agent 创建/改版通道不存在 —— 变更一律回各自租户实施。"
      />
      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Agent 注册表不可用"
          description={error}
        />
      )}
      <Card>
        <Tabs defaultActiveKey="ALL" items={items} />
      </Card>
    </div>
  )
}

export default AgentRegistryPage
