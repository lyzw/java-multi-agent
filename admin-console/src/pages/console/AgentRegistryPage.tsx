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
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import type { TableColumnsType } from 'antd'
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  RobotOutlined,
  SearchOutlined,
  WarningOutlined,
  ApartmentOutlined,
  TeamOutlined,
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
import { describeSubmitResult } from '@/pages/publish/publishBits'
import { emptyTextWhileLoading } from '@/shared/loadingState'
import { scrollXOf } from '@/shared/tableLayout'

/** Agent 去重聚合后的全局画像 —— 每行 = 一个 Agent 的完整身份，
 *  boundTenants 是该 Agent 被 ACTIVE 绑定的所有租户清单（跨 tenant_agent_binding 聚合）。 */
/** 申请队列里的「全局申请」哨兵：映射到后端 tenantId = null（PLATFORM 作用域） */
const GLOBAL_QUEUE_TENANT = '__GLOBAL__'

type AgentUniqRow = PlatformAgentRegistryItem & { boundTenants: string[] }


/**
 * 跨租户 Agent 注册表（平台链 /api/platform/v1/capabilities · 运营代配置） —— Agent 视角。
 *
 * <p>⚠️ **产品口径**：所有 Agent 由运营统一配置，租户不再自行维护 Agent / Skill / Tool。
 * 本页从「只读盘点」升级为**可新增 / 修改 / 停用 / 分配租户**。
 *
 * <p>⚠️ **Agent 视角改造（2026-11-19）**：
 * 原实现以「tenant_agent_binding 绑定关系行」为主表，每行是一条租户-Agent 绑定，
 * 导致同一个 Agent 出现在 N 行里（多对多），运营看不清楚 Agent 的**全局画像**：
 * 绑定多少租户、用哪个模型、编排模式是什么、是不是独占 Agent、有没有绑模型（fail-closed 风险）。
 *
 * <p><b>改造要点</b>：
 * <ul>
 *   <li>后端 SQL 新增聚合：按 agent_id 聚合 {@code tenant_count}（该 Agent 被多少租户 ACTIVE 绑定），
 *       回显 {@code model_ref / skill_bundles / has_instructions / orchestration.mode}；</li>
 *   <li>前端以「Agent 全局身份」为第一视角：表格每行是一个 Agent 的**身份画像**
 *       （租户绑定清单展开显示，或点击进入详情抽屉查看完整绑定列表）；</li>
 *   <li>统计面板升级为**运营决策指标**：未绑模型数（fail-closed 隐患）、编排型 Agent 数、
 *       独占 Agent 数 vs 复用 Agent 数；</li>
 *   <li>RLS 没有被绕过：写端点由运营显式传入 tenantId（可留空 = 全局 Agent），
 *       有租户时仓储层仍按 tenantId 绑定租户上下文 + 应用角色连接；留空则走
 *       PLATFORM 发布头 + 运维通道，授权沿用平台域 RESOURCE_ADMIN；</li>
 *   <li>发布流程按服务端策略分流（DIRECT 直发 / MANUAL 手动执行 / APPROVAL_* 双人复核），在页内申请队列闭环；</li>
 *   <li>新增 / 修改继续走 {@link AgentDraftPanel}（同租户侧一张草稿表单），
 *       注入平台链提交通道。</li>
 * </ul>
 */
export function AgentRegistryPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState<PlatformAgentRegistryItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [detailItem, setDetailItem] = useState<PlatformAgentRegistryItem | null>(null)
  const [search, setSearch] = useState('')
  const [orchestrationFilter, setOrchestrationFilter] = useState<string>('ALL')
  const [tenantFilter, setTenantFilter] = useState<string>('ALL')

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

  // =================================================================
  // ① 去重：后端返回的是「tenantId::agentId 绑定行」，前端以 Agent 全局
  //   身份为主视角，所以先按 agentId 去重聚合，每行携带完整画像。
  // =================================================================
  const agentUniq = useMemo(() => {
    const map = new Map<string, PlatformAgentRegistryItem & { boundTenants: string[] }>()
    for (const r of rows) {
      const existing = map.get(r.agentId)
      if (existing) {
        // 聚合：合并 boundTenants，其他字段取最新一行的（相同 agentId 字段值一致，
        // 但 boundTenants 必须全部收齐）
        if (!existing.boundTenants.includes(r.tenantId)) existing.boundTenants.push(r.tenantId)
        if (r.modelRef && !existing.modelRef) existing.modelRef = r.modelRef
        if (r.skillBundles && (!existing.skillBundles || existing.skillBundles.length === 0)) existing.skillBundles = r.skillBundles
        if (typeof r.hasInstructions === 'boolean') existing.hasInstructions = r.hasInstructions
        if (typeof r.hasOrchestration === 'boolean') existing.hasOrchestration = r.hasOrchestration
        if (r.orchestrationMode && !existing.orchestrationMode) existing.orchestrationMode = r.orchestrationMode
        if (typeof r.tenantCount === 'number') existing.tenantCount = r.tenantCount
      } else {
        map.set(r.agentId, { ...r, boundTenants: [r.tenantId] })
      }
    }
    // 回填 tenantCount（如果后端没返回）—— 用 boundTenants.length 兜底
    for (const v of map.values()) {
      if (typeof v.tenantCount !== 'number') v.tenantCount = v.boundTenants.length
    }
    return [...map.values()]
  }, [rows])

  const tenantIds = useMemo(() => [...new Set(rows.map((r) => r.tenantId))], [rows])

  // =================================================================
  // ② 统计面板 —— 运营决策视角
  // =================================================================
  const stats = useMemo(() => {
    let total = 0, published = 0, deprecated = 0, noDef = 0
    let noModel = 0, leaf = 0, router = 0, sequential = 0, orchestrator = 0
    let exclusive = 0, shared = 0, reusable = 0

    for (const a of agentUniq) {
      total++
      if (a.lifecycleState === 'PUBLISHED') published++
      else if (a.lifecycleState === 'DEPRECATED') deprecated++
      if (a.defVersion === null) noDef++
      if (!a.modelRef) noModel++
      const mode = (a.orchestrationMode ?? '').toUpperCase()
      if (!mode || mode === 'LEAF') { leaf++ }
      else { orchestrator++ }
      if (mode === 'ROUTER') router++
      if (mode === 'SEQUENTIAL') sequential++
      const tc = a.tenantCount ?? 0
      if (tc === 0 || !a.tenantCount) exclusive++ // 没返回 tenantCount 时的兜底
      if (tc === 1) exclusive++
      else if (tc >= 2) { shared++; reusable++ }
    }
    // 修正 exclusive = 恰好 1 个租户
    exclusive = agentUniq.filter((a) => (a.tenantCount ?? 0) === 1).length
    return { total, published, deprecated, noDef, noModel, leaf, router, sequential, orchestrator, exclusive, shared }
  }, [agentUniq])

  // =================================================================
  // ③ 过滤：搜索 + 编排模式 + 租户
  // =================================================================
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return agentUniq.filter((a) => {
      if (orchestrationFilter !== 'ALL') {
        const mode = (a.orchestrationMode ?? '').toUpperCase() || 'LEAF'
        if (mode !== orchestrationFilter) return false
      }
      if (tenantFilter !== 'ALL' && !(a as AgentUniqRow).boundTenants.includes(tenantFilter)) return false
      if (q === '') return true
      // 搜索覆盖：agentId / 名称 / 描述 / 模型 / 编排模式 / 绑定租户 / 角色 / 技能 / 标签 / 技能包
      if (a.agentId.toLowerCase().includes(q)) return true
      if ((a.name ?? '').toLowerCase().includes(q)) return true
      if ((a.description ?? '').toLowerCase().includes(q)) return true
      if ((a.modelRef ?? '').toLowerCase().includes(q)) return true
      if ((a.orchestrationMode ?? '').toLowerCase().includes(q)) return true
      if ((a as AgentUniqRow).boundTenants.some((t: string) => t.toLowerCase().includes(q))) return true
      if (a.roles.some((x) => x.toLowerCase().includes(q))) return true
      if (a.enabledSkills.some((x) => x.toLowerCase().includes(q))) return true
      if (a.capabilityTags.some((x) => x.toLowerCase().includes(q))) return true
      if ((a.skillBundles ?? []).some((x) => x.toLowerCase().includes(q))) return true
      return false
    })
  }, [agentUniq, search, orchestrationFilter, tenantFilter])

  // =================================================================
  // ④ 加载
  // =================================================================
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

  // =================================================================
  // ⑤ 新增 / 修改 / 停用 / 审批 / 执行 —— 业务动作
  // =================================================================
  const openCreate = () => {
    setEditorMode('create')
    setEditorAgentId('')
    setEditorDefinition(null)
    setEditorDefUnavailable(false)
    setEditorLegacy(null)
    setEditorTenantId(tenantIds.length === 1 ? tenantIds[0] ?? '' : '')
    setEditorOpen(true)
  }

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
          // 留空 = 全局 Agent：后端把 null / 空白都当「不建绑定」（铁律 29：不把可空值塞进 List.of）
          tenantId: editorTenantId || null,
          name: input.name,
          description: input.description,
        })
        return { agentId: created.agentId }
      },
      submitPublishRequest: async (payload: SubmitPublishRequest) => {
        // 无租户 → Controller 按 PLATFORM 作用域走平台发布通道（沿用平台域 RESOURCE_ADMIN）
        const created = await submitPlatformAgentPublishRequest({
          tenantId: editorTenantId || null,
          reason: payload.reason,
          expectedPublishSeq: payload.expectedPublishSeq,
          agents: payload.agents ?? [],
        })
        // ⚠️ 把服务端流程状态交回表单：策略可能已自动放行，不能写死「待双人复核」。
        return { requestId: created.requestId, state: created.state }
      },
    }),
    [editorTenantId],
  )

  const submitDeprecate = async () => {
    if (!deprecateTarget) return
    const version = deprecateTarget.defVersion ?? deprecateTarget.latestVersionNumber
    if (!version) {
      message.error('无法确定当前版本号，不能提交停用申请')
      return
    }
    setDeprecating(true)
    try {
      const created = await submitPlatformAgentLifecycleRequest({
        tenantId: deprecateTarget.tenantId,
        objectId: deprecateTarget.agentId,
        version,
        expectedFrom: deprecateTarget.lifecycleState ?? 'PUBLISHED',
        reason: deprecateReason.trim(),
      })
      message.success(describeSubmitResult(created.state, created.requestId))
      setDeprecateTarget(null)
      setDeprecateReason('')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '提交停用申请失败')
    } finally {
      setDeprecating(false)
    }
  }

  const loadQueue = useCallback(async () => {
    if (!queueTenantId) return
    setQueueLoading(true)
    try {
      setQueueRows(await listPlatformAgentRequests({
        tenantId: queueTenantId === GLOBAL_QUEUE_TENANT ? null : queueTenantId,
        state: queueState,
      }))
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
        tenantId: queueTenantId === GLOBAL_QUEUE_TENANT ? null : queueTenantId,
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
      await applyPlatformAgentPublishRequest(requestId, {
        tenantId: queueTenantId === GLOBAL_QUEUE_TENANT ? null : queueTenantId,
      })
      message.success('已执行，配置已生效')
      void loadQueue()
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '执行失败')
    }
  }

  // =================================================================
  // ⑥ 编排模式 Badge / 图标 —— Agent 视角一眼看出编排属性
  // =================================================================
  const orchestrationBadge = (mode: string | null | undefined): JSX.Element => {
    const m = (mode ?? '').toUpperCase() || 'LEAF'
    if (m === 'LEAF') {
      return <Tag color="default">LEAF 叶子</Tag>
    }
    if (m === 'ROUTER') {
      return <Tag color="purple"><ApartmentOutlined /> ROUTER 路由</Tag>
    }
    if (m === 'SEQUENTIAL') {
      return <Tag color="orange"><ApartmentOutlined /> SEQUENTIAL 串行</Tag>
    }
    return <Tag color="blue">{m}</Tag>
  }

  const modelRefCell = (agent: PlatformAgentRegistryItem): JSX.Element => {
    if (!agent.modelRef) {
      return (
        <Tooltip title="未绑定模型 · 运行时会 fail-closed 拒绝执行 ⚠️">
          <Tag color="red"><WarningOutlined /> 未绑模型</Tag>
        </Tooltip>
      )
    }
    return <Typography.Text code style={{ fontSize: 12 }}>{agent.modelRef}</Typography.Text>
  }

  const tenantCountCell = (agent: AgentUniqRow): JSX.Element => {
    const tc = agent.tenantCount ?? (agent.boundTenants?.length ?? 0)
    if (tc === 0) {
      return <Tag color="default">未分配</Tag>
    }
    if (tc === 1) {
      return (
        <Tooltip title={(agent.boundTenants ?? []).join('、')}>
          <Tag color="blue"><TeamOutlined /> 独占 · 1</Tag>
        </Tooltip>
      )
    }
    return (
      <Tooltip title={`绑定租户：${(agent.boundTenants ?? []).join('、')}`}>
        <Tag color="green"><TeamOutlined /> 复用 · {tc}</Tag>
      </Tooltip>
    )
  }

  // =================================================================
  // ⑦ 表格列定义
  // =================================================================
  const registryColumns = useMemo<TableColumnsType<AgentUniqRow>>(
    () => [
      {
        title: 'Agent',
        dataIndex: 'agentId',
        width: 240,
        fixed: 'left',
        render: (v: string, r) => (
          <Space direction="vertical" size={0}>
            <Typography.Text code style={{ fontSize: 13 }}>{v}</Typography.Text>
            {(r as AgentUniqRow).name && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{(r as AgentUniqRow).name}</Typography.Text>
            )}
          </Space>
        ),
      },
      {
        title: '租户绑定',
        key: 'tenants',
        width: 200,
        render: (_, r) => tenantCountCell(r),
      },
      {
        title: '模型',
        key: 'modelRef',
        width: 180,
        render: (_, r) => modelRefCell(r),
      },
      {
        title: '编排',
        key: 'orchestration',
        width: 130,
        render: (_, r) => orchestrationBadge(r.orchestrationMode),
      },
      {
        title: '定义版本',
        dataIndex: 'defVersion',
        width: 110,
        render: (v: number | null) =>
          v === null ? (
            <Typography.Text type="secondary">无定义</Typography.Text>
          ) : (
            <Tag color="blue">v{v}</Tag>
          ),
      },
      {
        title: '生命周期',
        dataIndex: 'lifecycleState',
        width: 110,
        render: (v: string | null) => lifecycleTag(v),
      },
      {
        title: '发布版本',
        key: 'version',
        width: 120,
        render: (_, r) =>
          r.versionCount === 0 ? (
            <Typography.Text type="secondary">尚无版本</Typography.Text>
          ) : (
            <Tag color="cyan">v{r.latestVersionNumber} · 共 {r.versionCount}</Tag>
          ),
      },
      {
        title: '最近发布',
        dataIndex: 'latestPublishedAt',
        width: 160,
        render: (v: string | null) => timeCell(v),
      },
      {
        title: '角色',
        dataIndex: 'roles',
        width: 150,
        render: (v: string[]) => tagList(v, '（无）', 'geekblue'),
      },
      {
        title: '启用技能',
        dataIndex: 'enabledSkills',
        width: 120,
        render: (v: string[]) =>
          v.length === 0 ? (
            <Typography.Text type="secondary">（无）</Typography.Text>
          ) : (
            <Tooltip title={v.join('、')}>
              <Tag color="purple">{v.length} 个</Tag>
            </Tooltip>
          ),
      },
      {
        title: '技能包',
        dataIndex: 'skillBundles',
        width: 120,
        render: (v: string[] | undefined) => {
          const list = v ?? []
          return list.length === 0 ? (
            <Typography.Text type="secondary">（无）</Typography.Text>
          ) : (
            <Tooltip title={list.join('、')}>
              <Tag color="gold">{list.length} 个</Tag>
            </Tooltip>
          )
        },
      },
      {
        title: '提示词',
        key: 'instructions',
        width: 90,
        render: (_, r) =>
          r.hasInstructions ? (
            <Tag color="green">已声明</Tag>
          ) : (
            <Typography.Text type="secondary">未声明</Typography.Text>
          ),
      },
      {
        title: '能力标签',
        dataIndex: 'capabilityTags',
        width: 150,
        render: (v: string[]) => tagList(v, '（无）'),
      },
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
        width: 260,
        render: (_, r) => {
          // 以 Agent 为主视角时，「编辑 / 停用」需要指定租户（同一个 Agent 可能被多个租户绑定）。
          // 这里取第一个绑定租户作为默认，若只有一个就直接用。
          const uniq = agentUniq.find((a) => a.agentId === r.agentId)
          const bound = (uniq as AgentUniqRow)?.boundTenants ?? [r.tenantId]
          const primaryTenant = bound[0] ?? r.tenantId
          const editTarget = { ...r, tenantId: primaryTenant }
          return (
            <Space size="small">
              <Button size="small" icon={<EditOutlined />} onClick={() => void openEdit(editTarget)}>
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
                  setDeprecateTarget(editTarget)
                  setDeprecateReason('')
                }}
              >
                停用
              </Button>
              <Button size="small" icon={<EyeOutlined />} onClick={() => setDetailItem(r)}>
                详情
              </Button>
            </Space>
          )
        },
      },
    ],
    [agentUniq],
  )

  // =================================================================
  // ⑧ 申请队列列
  // =================================================================
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

  // =================================================================
  // ⑨ 渲染
  // =================================================================
  return (
    <div>
      <Typography.Title level={3}>
        <Space>
          <RobotOutlined /> Agent 注册表（跨租户 · 运营配置 · Agent 视角）
        </Space>
      </Typography.Title>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="Agent 是全局智能体实体；运营统一维护定义与租户绑定关系"
        description="本页以 Agent 身份为主视角聚合：每行 = 一个 Agent 的完整画像（绑定多少租户、用哪个模型、编排模式、能力清单）。新增 / 修改走同一张草稿表单（流程按发布策略分流）；「删除」= 停用（DEPRECATED 单向终态）。"
      />
      {error && (
        <Alert type="error" showIcon style={{ marginBottom: 16 }} message="Agent 注册表不可用" description={error} />
      )}

      {/* ── 统计面板：运营决策视角 ── */}
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Card size="small" variant="borderless" style={{ background: '#fafafa' }}>
            <Statistic loading={loading} title="Agent 总数" value={stats.total} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" variant="borderless" style={{ background: '#f6ffed' }}>
            <Statistic loading={loading} title="已发布" value={stats.published} valueStyle={{ color: '#389e0d' }} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" variant="borderless" style={{ background: '#fff1f0' }}>
            <Statistic loading={loading} title="已停用" value={stats.deprecated} valueStyle={{ color: '#cf1322' }} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" variant="borderless" style={{ background: '#fff7e6' }}>
            <Statistic loading={loading} title="⚠️ 未绑模型" value={stats.noModel} valueStyle={{ color: '#d46b08' }} suffix="fail-closed" />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" variant="borderless" style={{ background: '#f0f5ff' }}>
            <Statistic loading={loading} title="编排型 Agent" value={stats.orchestrator} valueStyle={{ color: '#2f54eb' }} prefix={<ApartmentOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" variant="borderless" style={{ background: '#f9f0ff' }}>
            <Statistic loading={loading} title="独占 Agent（仅 1 租户）" value={stats.exclusive} prefix={<TeamOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" variant="borderless" style={{ background: '#e6fffb' }}>
            <Statistic loading={loading} title="复用 Agent（≥ 2 租户）" value={stats.shared} valueStyle={{ color: '#08979c' }} prefix={<TeamOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" variant="borderless" style={{ background: '#f5f5f5' }}>
            <Statistic loading={loading} title="无定义" value={stats.noDef} />
          </Card>
        </Col>
      </Row>

      {/* ── 主卡：搜索 + 过滤 + 表格 ── */}
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
        <Space wrap style={{ marginBottom: 16 }}>
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="搜索 agentId / 名称 / 模型 / 编排模式 / 租户 / 角色 / 技能 / 标签 / 技能包"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            style={{ maxWidth: 420 }}
          />
          <Select
            value={orchestrationFilter}
            onChange={setOrchestrationFilter}
            style={{ width: 170 }}
            options={[
              { value: 'ALL', label: '全部编排模式' },
              { value: 'LEAF', label: 'LEAF 叶子' },
              { value: 'ROUTER', label: 'ROUTER 路由' },
              { value: 'SEQUENTIAL', label: 'SEQUENTIAL 串行' },
            ]}
          />
          <Select
            value={tenantFilter}
            onChange={setTenantFilter}
            style={{ width: 220 }}
            options={[
              { value: 'ALL', label: '全部租户' },
              ...tenantIds.map((t) => ({ value: t, label: t })),
            ]}
          />
          <Typography.Text type="secondary">共 {filtered.length} 个 Agent</Typography.Text>
        </Space>

        <Table<PlatformAgentRegistryItem & { boundTenants: string[] }>
          rowKey="agentId"
          loading={loading}
          dataSource={filtered}
          columns={registryColumns}
          pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (total) => `共 ${total} 个 Agent` }}
          size="small"
          scroll={{ x: scrollXOf(registryColumns) }}
          locale={{ emptyText: emptyTextWhileLoading(loading, '暂无 Agent') }}
          onRow={(r) => ({ onDoubleClick: () => setDetailItem(r) })}
        />
      </Card>

      {/* ── 详情抽屉（Agent 视角：完整画像 + 租户绑定清单）── */}
      <CatalogDetailDrawer
        title={
          <Space>
            <RobotOutlined />
            <Typography.Text code>{detailItem?.agentId ?? 'Agent 详情'}</Typography.Text>
            {detailItem?.name && <Typography.Text type="secondary">{detailItem.name}</Typography.Text>}
          </Space>
        }
        open={detailItem !== null}
        onClose={() => setDetailItem(null)}
      >
        {detailItem !== null ? <AgentRegistryDetail item={detailItem} agentUniq={agentUniq} /> : null}
      </CatalogDetailDrawer>

      {/* ── 新增 / 修改：与租户侧同一张 AgentDraftPanel ── */}
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
          <Form.Item
            label="初始绑定租户（可选）"
            extra="Agent 创建为全局实体：留空 = 只建全局定义、走 PLATFORM 全局发布，后续在行内「分配租户」增减绑定；选择租户 = 创建后立即绑定并走 TENANT 发布"
          >
            <Select
              value={editorTenantId || undefined}
              onChange={(v) => setEditorTenantId(v)}
              allowClear
              placeholder="留空 = 全局 Agent（不绑定租户）"
              options={[...new Set([...tenantIds, editorTenantId].filter(Boolean))].map((t) => ({ value: t, label: t }))}
              disabled={editorMode === 'edit' || loadingDefinition}
            />
          </Form.Item>
        </Form>
        {(editorMode === 'create') || (editorMode === 'edit' && !loadingDefinition) ? (
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
                  : describeSubmitResult(r.state, r.requestId ?? undefined),
              )
            }}
          />
        ) : null}
      </Modal>

      {/* ── 停用：理由弹窗 ── */}
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
          description="已发布版本行不可变，停用同样按发布策略分流（APPROVAL_* 才需双人复核），执行后该 Agent 的当前版本进入 DEPRECATED，无法复活。"
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

      {/* ── 申请队列：三步工作流闭环 ── */}
      <Drawer
        title="Agent 变更申请队列（按发布策略分流）"
        open={queueOpen}
        onClose={() => setQueueOpen(false)}
        width={1000}
      >
        <Space wrap style={{ marginBottom: 12 }}>
          <Select
            value={queueTenantId || undefined}
            onChange={(v) => setQueueTenantId(v)}
            placeholder="选择租户 / 全局申请"
            style={{ width: 260 }}
            options={[
              { value: GLOBAL_QUEUE_TENANT, label: '全局申请（无租户 · PLATFORM）' },
              ...[...new Set([...tenantIds, queueTenantId].filter(Boolean))].map((t) => ({ value: t, label: t })),
            ]}
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
          <Alert type="info" showIcon message="请先选择租户或「全局申请」以查看其变更申请" />
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

      {/* ── 分配租户：多对多绑定（直接 CRUD，不走发布工作流）── */}
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
