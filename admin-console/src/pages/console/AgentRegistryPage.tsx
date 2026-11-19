import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Col,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import type { TableColumnsType, TabsProps } from 'antd'
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  RobotOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import {
  applyPlatformAgentPublishRequest,
  bindPlatformAgent,
  createPlatformAgent,
  decidePlatformAgentPublishRequest,
  getConsoleAgentRegistry,
  getPlatformAgentDefinition,
  listPlatformAgentRequests,
  listPlatformAgentTenants,
  submitPlatformAgentLifecycleRequest,
  submitPlatformAgentPublishRequest,
  unbindPlatformAgent,
} from '@/api/adminApi'
import type {
  AgentDefinition,
  PlatformAgentBinding,
  PlatformAgentRegistryItem,
  PlatformRequestItem,
  SubmitPublishRequest,
} from '@/api/types'
import { lifecycleTag, tagList, timeCell } from '@/pages/catalog/displayBits'
import { AgentDraftPanel } from '@/pages/agents/AgentDraftPanel'
import { CatalogDetailDrawer, AgentRegistryDetail } from './platformDetailDrawer'
import { describeCatalogLoadError } from './platformRequestBits'
import { emptyTextWhileLoading } from '@/shared/loadingState'
import { scrollXOf } from '@/shared/tableLayout'

/**
 * 跨租户 Agent 注册表（平台链 /api/platform/v1/capabilities · 运营代配置）。
 *
 * <p>⚠️ **产品口径已变**：所有 Agent 由运营统一配置，租户不再自行维护
 * Agent / Skill / Tool。本页从「只读盘点」升级为**可新增 / 修改 / 停用**：
 * <ul>
 *   <li>新增 / 修改走 {@code AgentDraftPanel}（与租户侧同一张草稿表单），
 *       但注入平台链提交通道（带 tenantId）；</li>
 *   <li>「删除」= 停用（DEPRECATED 单向终态，已发布行不可变，不做物理删除）；</li>
 *   <li>三步工作流（申请 → 双人复核 → 执行）在页内申请队列闭环。</li>
 * </ul>
 *
 * <p>⚠️ **RLS 没有被绕过**：写端点复用租户链发布工作流，tenantId 由运营显式传入，
 * 仓储层仍按 tenantId 绑定租户上下文 + 应用角色连接。
 *
 * <p>⚠️ **页内按租户分组**：第一个 Tab「全部租户」，其后每个出现的 tenantId 一个 Tab；
 * 每个 tab 只按租户过滤行，不重复请求。
 */
export function AgentRegistryPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState<PlatformAgentRegistryItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [detailItem, setDetailItem] = useState<PlatformAgentRegistryItem | null>(null)
  const [search, setSearch] = useState('')

  // ── 新增 / 修改：共用 AgentDraftPanel 弹窗 ──
  const [editorOpen, setEditorOpen] = useState(false)
  const [editorMode, setEditorMode] = useState<'create' | 'edit'>('create')
  const [editorTenantId, setEditorTenantId] = useState<string>('')
  const [editorAgentId, setEditorAgentId] = useState<string>('')
  const [editorDefinition, setEditorDefinition] = useState<AgentDefinition | null>(null)
  const [editorDefUnavailable, setEditorDefUnavailable] = useState(false)
  const [editorLegacy, setEditorLegacy] = useState<{
    name?: string | null
    description?: string | null
  } | null>(null)
  const [loadingDefinition, setLoadingDefinition] = useState(false)

  // ── 停用：理由弹窗 ──
  const [deprecateTarget, setDeprecateTarget] = useState<PlatformAgentRegistryItem | null>(null)
  const [deprecateReason, setDeprecateReason] = useState('')
  const [deprecating, setDeprecating] = useState(false)

  // ── 申请队列：三步工作流闭环 ──
  const [queueOpen, setQueueOpen] = useState(false)
  const [queueTenantId, setQueueTenantId] = useState<string>('')
  const [queueRows, setQueueRows] = useState<PlatformRequestItem[]>([])
  const [queueLoading, setQueueLoading] = useState(false)
  const [queueState, setQueueState] = useState<string | undefined>(undefined)

  // ── 分配租户：全局 Agent 与租户的多对多绑定 ──
  const [bindingOpen, setBindingOpen] = useState(false)
  const [bindingAgentId, setBindingAgentId] = useState<string>('')
  const [bindingRows, setBindingRows] = useState<PlatformAgentBinding[]>([])
  const [bindingLoading, setBindingLoading] = useState(false)
  const [bindingTenantId, setBindingTenantId] = useState<string>('')
  const [bindingPinnedVersion, setBindingPinnedVersion] = useState<number | null>(null)
  const [bindingConfig, setBindingConfig] = useState<string>('{}')

  const stats = useMemo(() => {
    return {
      total: rows.length,
      published: rows.filter((r) => r.lifecycleState === 'PUBLISHED').length,
      deprecated: rows.filter((r) => r.lifecycleState === 'DEPRECATED').length,
      noDef: rows.filter((r) => r.defVersion === null).length,
    }
  }, [rows])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (q === '') return rows
    return rows.filter((r) => {
      if (r.agentId.toLowerCase().includes(q)) return true
      if ((r.name ?? '').toLowerCase().includes(q)) return true
      if (r.tenantId.toLowerCase().includes(q)) return true
      if (r.roles.some((x) => x.toLowerCase().includes(q))) return true
      if (r.enabledSkills.some((x) => x.toLowerCase().includes(q))) return true
      if (r.capabilityTags.some((x) => x.toLowerCase().includes(q))) return true
      return false
    })
  }, [rows, search])

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

  const tenantIds = useMemo(() => [...new Set(filtered.map((r) => r.tenantId))], [filtered])

  // ── 新建：租户选择（多租户页必须显式指定归属租户）──
  const openCreate = () => {
    setEditorMode('create')
    setEditorAgentId('')
    setEditorDefinition(null)
    setEditorDefUnavailable(false)
    setEditorLegacy(null)
    setEditorTenantId(tenantIds.length === 1 ? tenantIds[0] ?? '' : '')
    setEditorOpen(true)
  }

  // ── 修改：先取权威定义回填（取不到就如实标记，不静默清空关系）──
  const openEdit = async (r: PlatformAgentRegistryItem) => {
    setEditorMode('edit')
    setEditorTenantId(r.tenantId)
    setEditorAgentId(r.agentId)
    setEditorLegacy({ name: r.name, description: r.description })
    setEditorDefinition(null)
    setEditorDefUnavailable(false)
    setEditorOpen(true)
    setLoadingDefinition(true)
    try {
      setEditorDefinition(await getPlatformAgentDefinition(r.tenantId, r.agentId))
    } catch {
      setEditorDefUnavailable(true)
    } finally {
      setLoadingDefinition(false)
    }
  }

  const submitApi = useMemo(
    () => ({
      createAgent: async (input: { name: string; description?: string | null }) => {
        const created = await createPlatformAgent({
          tenantId: editorTenantId,
          name: input.name,
          description: input.description,
        })
        return { agentId: created.agentId }
      },
      submitPublishRequest: async (payload: SubmitPublishRequest) => {
        const created = await submitPlatformAgentPublishRequest({
          tenantId: editorTenantId,
          reason: payload.reason,
          expectedPublishSeq: payload.expectedPublishSeq,
          agents: payload.agents ?? [],
        })
        return { requestId: created.requestId }
      },
    }),
    [editorTenantId],
  )

  // ── 停用申请 ──
  const submitDeprecate = async () => {
    if (!deprecateTarget) return
    const version = deprecateTarget.defVersion ?? deprecateTarget.latestVersionNumber
    if (!version) {
      message.error('无法确定当前版本号，不能提交停用申请')
      return
    }
    setDeprecating(true)
    try {
      await submitPlatformAgentLifecycleRequest({
        tenantId: deprecateTarget.tenantId,
        objectId: deprecateTarget.agentId,
        version,
        expectedFrom: deprecateTarget.lifecycleState ?? 'PUBLISHED',
        reason: deprecateReason.trim(),
      })
      message.success('停用申请已提交，待双人复核后执行')
      setDeprecateTarget(null)
      setDeprecateReason('')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '提交停用申请失败')
    } finally {
      setDeprecating(false)
    }
  }

  // ── 申请队列 ──
  const loadQueue = useCallback(async () => {
    if (!queueTenantId) return
    setQueueLoading(true)
    try {
      setQueueRows(await listPlatformAgentRequests({ tenantId: queueTenantId, state: queueState }))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载申请队列失败')
    } finally {
      setQueueLoading(false)
    }
  }, [queueTenantId, queueState, message])

  useEffect(() => {
    if (queueOpen) void loadQueue()
  }, [queueOpen, loadQueue])

  const loadBindings = useCallback(async () => {
    if (!bindingAgentId) return
    setBindingLoading(true)
    try {
      setBindingRows(await listPlatformAgentTenants(bindingAgentId))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载分配关系失败')
    } finally {
      setBindingLoading(false)
    }
  }, [bindingAgentId, message])

  useEffect(() => {
    if (bindingOpen) void loadBindings()
  }, [bindingOpen, loadBindings])

  const openBindings = (r: PlatformAgentRegistryItem) => {
    setBindingAgentId(r.agentId)
    setBindingTenantId('')
    setBindingPinnedVersion(null)
    setBindingConfig('{}')
    setBindingOpen(true)
  }

  const doBind = async () => {
    if (!bindingTenantId) {
      message.warning('请先选择租户')
      return
    }
    try {
      await bindPlatformAgent({
        tenantId: bindingTenantId,
        agentId: bindingAgentId,
        pinnedVersion: bindingPinnedVersion,
        config: bindingConfig,
      })
      message.success('分配已建立')
      void loadBindings()
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '分配失败')
    }
  }

  const doUnbind = async (tenantId: string) => {
    try {
      await unbindPlatformAgent(tenantId, bindingAgentId)
      message.success('分配已停用')
      void loadBindings()
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '停用分配失败')
    }
  }

  const doDecision = async (requestId: string, decision: 'APPROVE' | 'REJECT') => {
    try {
      await decidePlatformAgentPublishRequest(requestId, {
        tenantId: queueTenantId,
        decision,
      })
      message.success(decision === 'APPROVE' ? '已批准，待执行' : '已拒绝')
      void loadQueue()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '审批失败')
    }
  }

  const doApply = async (requestId: string) => {
    try {
      await applyPlatformAgentPublishRequest(requestId, { tenantId: queueTenantId })
      message.success('已执行，配置已生效')
      void loadQueue()
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '执行失败')
    }
  }

  const registryColumns = useMemo<TableColumnsType<PlatformAgentRegistryItem>>(
    () => [
      { title: 'agentId', dataIndex: 'agentId', width: 200, render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
      { title: '名称', dataIndex: 'name', width: 150, render: (v: string | null) => v ?? '-' },
      { title: '生命周期', dataIndex: 'lifecycleState', width: 110, render: (v: string | null) => lifecycleTag(v) },
      {
        title: '定义版本',
        dataIndex: 'defVersion',
        width: 100,
        render: (v: number | null) => (v === null ? <Typography.Text type="secondary">无定义</Typography.Text> : <Tag color="blue">v{v}</Tag>),
      },
      { title: '版本数', dataIndex: 'versionCount', width: 80 },
      {
        title: '最新版本',
        dataIndex: 'latestVersionNumber',
        width: 90,
        render: (v: number | null) => (v === null ? <Typography.Text type="secondary">-</Typography.Text> : `v${v}`),
      },
      { title: '最新发布时间', dataIndex: 'latestPublishedAt', width: 170, render: (v: string | null) => timeCell(v) },
      { title: '角色', dataIndex: 'roles', width: 150, render: (v: string[]) => tagList(v, '（无）', 'geekblue') },
      {
        title: '启用技能',
        dataIndex: 'enabledSkills',
        width: 100,
        render: (v: string[]) =>
          v.length === 0 ? (
            <Typography.Text type="secondary">（无）</Typography.Text>
          ) : (
            <Tooltip title={v.join('、')}>
              <Tag color="purple">{v.length} 个</Tag>
            </Tooltip>
          ),
      },
      { title: '能力标签', dataIndex: 'capabilityTags', width: 140, render: (v: string[]) => tagList(v, '（无）') },
      {
        title: '收紧覆盖',
        dataIndex: 'hasOverrides',
        width: 100,
        render: (b: boolean) =>
          b ? <Tag color="gold">有覆盖</Tag> : <Typography.Text type="secondary">无</Typography.Text>,
      },
      {
        title: '操作',
        key: 'actions',
        fixed: 'right',
        width: 330,
        render: (_: unknown, r: PlatformAgentRegistryItem) => (
          <Space size="small">
            <Button size="small" icon={<EditOutlined />} onClick={() => void openEdit(r)}>
              编辑
            </Button>
            <Button size="small" onClick={() => openBindings(r)}>
              分配租户
            </Button>
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              disabled={r.lifecycleState === 'DEPRECATED'}
              onClick={() => {
                setDeprecateTarget(r)
                setDeprecateReason('')
              }}
            >
              停用
            </Button>
            <Button size="small" icon={<EyeOutlined />} onClick={() => setDetailItem(r)}>
              详情
            </Button>
          </Space>
        ),
      },
    ],
    [],
  )

  const renderTable = (data: PlatformAgentRegistryItem[]): JSX.Element => (
    <Table<PlatformAgentRegistryItem>
      rowKey={(r) => `${r.tenantId}::${r.agentId}`}
      loading={loading}
      dataSource={data}
      columns={registryColumns}
      pagination={false}
      size="small"
      scroll={{ x: scrollXOf(registryColumns) }}
      locale={{ emptyText: emptyTextWhileLoading(loading, '暂无数据') }}
      onRow={(r) => ({ onDoubleClick: () => setDetailItem(r) })}
    />
  )

  const items: TabsProps['items'] = [
    { key: 'ALL', label: <span>全部绑定<span style={{ marginLeft: 4, opacity: 0.6 }}>({filtered.length})</span></span>, children: renderTable(filtered) },
    ...tenantIds.map((tenantId) => ({
      key: tenantId,
      label: <span>{tenantId}<span style={{ marginLeft: 4, opacity: 0.6 }}>({filtered.filter((r) => r.tenantId === tenantId).length})</span></span>,
      children: renderTable(filtered.filter((r) => r.tenantId === tenantId)),
    })),
  ]

  const queueColumns: TableColumnsType<PlatformRequestItem> = [
    { title: 'requestId', dataIndex: 'requestId', width: 130, render: (v: string) => <Typography.Text code>{v.slice(0, 12)}…</Typography.Text> },
    { title: '操作', dataIndex: 'operation', width: 150 },
    { title: '状态', dataIndex: 'state', width: 110, render: (v: string) => <Tag color={v === 'APPLIED' ? 'green' : v === 'PENDING' ? 'blue' : v === 'REJECTED' || v === 'FAILED' ? 'red' : 'gold'}>{v}</Tag> },
    { title: '申请人', dataIndex: 'requesterId', width: 150, ellipsis: true },
    { title: '申请时间', dataIndex: 'requestedAt', width: 170, render: (v: string) => timeCell(v) },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 190,
      render: (_: unknown, r: PlatformRequestItem) => (
        <Space size="small">
          <Button size="small" disabled={r.state !== 'PENDING'} onClick={() => void doDecision(r.requestId, 'APPROVE')}>批准</Button>
          <Button size="small" disabled={r.state !== 'PENDING'} onClick={() => void doDecision(r.requestId, 'REJECT')}>拒绝</Button>
          <Button size="small" type="primary" disabled={r.state !== 'APPROVED'} onClick={() => void doApply(r.requestId)}>执行</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Typography.Title level={3}>
        <Space>
          <RobotOutlined /> Agent 注册表（跨租户 · 运营配置）
        </Space>
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="Agent 为全局实体；租户通过配置绑定使用，运营统一维护定义与分配关系"
        description="新增 / 修改走同一张草稿表单（申请 → 双人复核 → 执行三步工作流）；「删除」= 停用（DEPRECATED 单向终态）。「分配租户」直接维护多对多绑定；同一 Agent 可分配给多个租户，同一租户可绑定多个 Agent。"
      />
      {error && (
        <Alert type="error" showIcon style={{ marginBottom: 16 }} message="Agent 注册表不可用" description={error} />
      )}

      <Row gutter={12} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small" variant="borderless" style={{ background: '#fafafa' }}>
            <Statistic loading={loading} title="Agent 总数" value={stats.total} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" variant="borderless" style={{ background: '#f6ffed' }}>
            <Statistic loading={loading} title="已发布" value={stats.published} valueStyle={{ color: '#389e0d' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" variant="borderless" style={{ background: '#fff1f0' }}>
            <Statistic loading={loading} title="已停用" value={stats.deprecated} valueStyle={{ color: '#cf1322' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" variant="borderless" style={{ background: '#f0f5ff' }}>
            <Statistic loading={loading} title="无定义" value={stats.noDef} valueStyle={{ color: '#2f54eb' }} />
          </Card>
        </Col>
      </Row>

      <Card
        extra={
          <Space>
            <Button
              onClick={() => {
                setQueueTenantId(tenantIds.length === 1 ? tenantIds[0] ?? '' : '')
                setQueueState(undefined)
                setQueueOpen(true)
              }}
            >
              申请队列
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建 Agent
            </Button>
          </Space>
        }
      >
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索 agentId / 名称 / 租户 / 角色 / 技能 / 标签"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ maxWidth: 360, marginBottom: 16 }}
        />
        <Tabs defaultActiveKey="ALL" items={items} />
      </Card>

      {/* 详情抽屉：内容全部来自行内字段（不发请求）。 */}
      <CatalogDetailDrawer
        title={<Typography.Text code>{detailItem ? `${detailItem.tenantId} :: ${detailItem.agentId}` : 'Agent 详情'}</Typography.Text>}
        open={detailItem !== null}
        onClose={() => setDetailItem(null)}
      >
        {detailItem !== null ? <AgentRegistryDetail item={detailItem} /> : null}
      </CatalogDetailDrawer>

      {/* 新增 / 修改：与租户侧同一张 AgentDraftPanel，注入平台链提交通道。 */}
      <Modal
        open={editorOpen}
        title={editorMode === 'create' ? '新建 Agent（运营代租户配置）' : `修改 Agent（${editorAgentId}）`}
        onCancel={() => setEditorOpen(false)}
        width={960}
        footer={null}
        styles={{ body: { maxHeight: '74vh', overflowY: 'auto' } }}
        destroyOnHidden
      >
        <Form layout="vertical">
          <Form.Item label="初始绑定租户" required extra="Agent 创建为全局实体，并立即绑定到所选租户；后续可在行内「分配租户」中增减绑定">
            <Select
              value={editorTenantId || undefined}
              onChange={(v) => setEditorTenantId(v)}
              placeholder="选择 tenantId"
              options={[...new Set([...tenantIds, editorTenantId].filter(Boolean))].map((t) => ({ value: t, label: t }))}
              disabled={editorMode === 'edit' || loadingDefinition}
            />
          </Form.Item>
        </Form>
        {editorMode === 'create' && !editorTenantId && (
          <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="请先选择初始绑定租户" />
        )}
        {(editorMode === 'create' && editorTenantId) || (editorMode === 'edit' && !loadingDefinition) ? (
          <AgentDraftPanel
            registration={editorMode === 'create'}
            agentId={editorMode === 'edit' ? editorAgentId : undefined}
            definition={editorDefinition}
            definitionUnavailable={editorDefUnavailable}
            legacy={editorLegacy}
            submitApi={submitApi}
            onSubmitted={(r) => {
              setEditorOpen(false)
              void load()
              message.success(
                r.failed
                  ? 'Agent 行已创建，但发布申请提交失败（请到详情页重试）'
                  : '申请已提交，待双人复核后执行',
              )
            }}
          />
        ) : null}
      </Modal>

      {/* 停用：理由弹窗（一律 DEPRECATED 终态）。 */}
      <Modal
        open={deprecateTarget !== null}
        title={`停用 Agent（${deprecateTarget?.agentId ?? ''}）`}
        okText="提交停用申请"
        okButtonProps={{ danger: true, loading: deprecating }}
        cancelText="取消"
        onOk={() => void submitDeprecate()}
        onCancel={() => setDeprecateTarget(null)}
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="停用是不可逆的终态（DEPRECATED）"
          description="已发布版本行不可变，停用走「申请 → 双人复核 → 执行」，执行后该 Agent 的当前版本进入 DEPRECATED，无法复活。"
        />
        <Form layout="vertical">
          <Form.Item label="变更理由" required>
            <Input.TextArea
              rows={3}
              value={deprecateReason}
              onChange={(e) => setDeprecateReason(e.target.value)}
              placeholder="说明停用原因（必填）"
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 申请队列：三步工作流闭环（申请 → 审批 → 执行）。 */}
      <Drawer
        title="Agent 变更申请队列（三步工作流）"
        open={queueOpen}
        onClose={() => setQueueOpen(false)}
        width={1000}
      >
        <Space wrap style={{ marginBottom: 12 }}>
          <Select
            value={queueTenantId || undefined}
            onChange={(v) => setQueueTenantId(v)}
            placeholder="选择租户"
            style={{ width: 220 }}
            options={[...new Set([...tenantIds, queueTenantId].filter(Boolean))].map((t) => ({ value: t, label: t }))}
          />
          <Select
            value={queueState ?? 'ALL'}
            onChange={(v) => setQueueState(v === 'ALL' ? undefined : v)}
            style={{ width: 160 }}
            options={[
              { value: 'ALL', label: '全部状态' },
              { value: 'PENDING', label: '待审批' },
              { value: 'APPROVED', label: '已批准' },
              { value: 'APPLIED', label: '已执行' },
              { value: 'REJECTED', label: '已拒绝' },
              { value: 'FAILED', label: '失败' },
            ]}
          />
          <Button onClick={() => void loadQueue()} loading={queueLoading}>刷新</Button>
        </Space>
        {!queueTenantId ? (
          <Alert type="info" showIcon message="请先选择租户以查看其变更申请" />
        ) : (
          <Table<PlatformRequestItem>
            rowKey="requestId"
            loading={queueLoading}
            dataSource={queueRows}
            columns={queueColumns}
            pagination={false}
            size="small"
            scroll={{ x: scrollXOf(queueColumns) }}
            locale={{ emptyText: '暂无变更申请' }}
          />
        )}
      </Drawer>

      {/* 分配租户：全局 Agent 与租户的多对多配置绑定（直接 CRUD，不走发布工作流）。 */}
      <Drawer
        title={`分配租户（Agent ${bindingAgentId || ''}）`}
        open={bindingOpen}
        onClose={() => setBindingOpen(false)}
        width={900}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="绑定决定租户能否使用该 Agent；停用绑定不影响 Agent 定义本身"
        />
        <Space wrap style={{ marginBottom: 16 }}>
          <Select
            value={bindingTenantId || undefined}
            onChange={(v) => setBindingTenantId(v)}
            placeholder="选择租户"
            style={{ width: 260 }}
            options={[...new Set([...tenantIds, bindingTenantId].filter(Boolean))].map((t) => ({ value: t, label: t }))}
          />
          <InputNumber
            min={1}
            placeholder="固定版本（可选）"
            value={bindingPinnedVersion ?? undefined}
            onChange={(v) => setBindingPinnedVersion(v)}
            style={{ width: 160 }}
          />
          <Input
            value={bindingConfig}
            onChange={(e) => setBindingConfig(e.target.value)}
            placeholder='绑定配置 JSON，默认 {}'
            style={{ width: 220 }}
          />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => void doBind()}>
            建立分配
          </Button>
          <Button onClick={() => void loadBindings()} loading={bindingLoading}>刷新</Button>
        </Space>
        <Table<PlatformAgentBinding>
          rowKey="tenantId"
          loading={bindingLoading}
          dataSource={bindingRows}
          pagination={false}
          size="small"
          columns={[
            { title: '租户', dataIndex: 'tenantId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
            { title: '状态', dataIndex: 'status', render: (v: string) => <Tag color={v === 'ACTIVE' ? 'green' : 'default'}>{v}</Tag> },
            { title: '固定版本', dataIndex: 'pinnedVersion', render: (v: number | null) => (v === null ? '跟随发布头' : `v${v}`) },
            { title: '配置', dataIndex: 'config', ellipsis: true },
            { title: '更新时间', dataIndex: 'updatedAt', render: (v: string) => timeCell(v) },
            {
              title: '操作',
              key: 'actions',
              width: 110,
              render: (_: unknown, r: PlatformAgentBinding) => (
                <Button
                  size="small"
                  danger
                  disabled={r.status !== 'ACTIVE'}
                  onClick={() => void doUnbind(r.tenantId)}
                >
                  停用分配
                </Button>
              ),
            },
          ]}
          locale={{ emptyText: '该 Agent 尚未分配给任何租户' }}
        />
      </Drawer>
    </div>
  )
}

export default AgentRegistryPage
