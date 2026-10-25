import { useEffect, useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Card,
  Tabs,
  Descriptions,
  Table,
  Tag,
  Button,
  Space,
  Typography,
  Spin,
  Alert,
  Checkbox,
  Form,
  Input,
  InputNumber,
  Select,
  Collapse,
  App as AntApp,
} from 'antd'
import { RobotOutlined, SendOutlined, StopOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  getAgent,
  getAgentDefinition,
  getAgentVersions,
  publishAgentVersion,
  submitPublishRequest,
} from '@/api/adminApi'
import { isValidJson } from './agentDefinition'
import {
  buildOrchestrationJson,
  ORCHESTRATION_MODES,
  FAILURE_POLICIES,
  evaluatePublishGate,
  parseOrchestrationJsonStrict,
  validateOrchestration,
  type ChildRefFormValue,
  type FailurePolicy,
  type OrchestrationFormValues,
  type OrchestrationMode,
} from './orchestration/orchestrationForm'
import { AgentDependenciesTab } from './AgentDependenciesTab'
import { DelegationTab } from './dependencies/DelegationTab'
import type { AgentDefinition, AgentItem, AgentVersion, PublishAgentSpec } from '@/api/types'

function prettyJson(raw: string | null): string {
  if (!raw) return '（无）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/**
 * Agent 详情（P1 + P2 + P3 + C34-3）：
 * 概览 / 版本历史 / 依赖（P3）/ 委派拓扑（C34-3）/ 发布抽屉（三步工作流）/ 停用（C34-3）/ 直接发布（旧通道）。
 *
 * <p>⚠️ <b>definition 是不可变快照</b>（FR-002）：本页不提供「编辑已发布版本」，
 * 要改只能再发一个版本。
 *
 * <p>⚠️ <b>两条发布路径刻意并存</b>：
 * <ul>
 *   <li><b>发布抽屉</b>（P2）：走 {@code /api/cap/config/publishes} 三步工作流
 *       （申请→双人复核→执行），结构化 spec 进 {@code cap_agent_def}；</li>
 *   <li><b>直接发布</b>（P1 既有）：{@code POST /api/agents/{id}/versions} 立即落
 *       {@code agent_version} 快照，无审批 —— 供运维/自动化通道使用。</li>
 * </ul>
 *
 * <p>⚠️ <b>关系字段回填必须用权威定义</b>（C34-3 修复）：
 * {@code GET /api/cap/agents/{id}/definition}（{@code cap_agent_def}）才是多级 Agent
 * 的权威定义；{@code GET /api/agents/{id}} 的 {@code latestDefinition} 是<b>旧体系</b>
 * 的自由 JSON 快照，取不到关系字段。先前只回填 name/description，
 * 于是「改一下就把关系静默清空」成了常态。现在：
 * <ul>
 *   <li>取得到定义 → <b>完整回填</b>全部关系字段；</li>
 *   <li>取不到定义 → <b>显式告警 + 要求用户显式确认</b>，绝不静默清空；</li>
 *   <li>编排声明解析失败 → <b>显式报错并阻止提交</b>（静默当 LEAF = 取消全部委派）。</li>
 * </ul>
 */
export function AgentDetailPage(): JSX.Element {
  const { id = '' } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [agent, setAgent] = useState<AgentItem | null>(null)
  const [versions, setVersions] = useState<AgentVersion[]>([])
  const [loading, setLoading] = useState(true)
  const [missing, setMissing] = useState(false)
  /** 权威定义（cap_agent_def 的最新版本）—— 多级 Agent 的关系字段来源 */
  const [agentDefinition, setAgentDefinition] = useState<AgentDefinition | null>(null)
  /**
   * ⚠️ 取不到权威定义（cap_agent_def）时置 true。
   *
   * <p>这时**关系字段无从回填** —— 继续保存就会把它们**静默清空**
   * （「改一下就把关系清了」是本轮修复的核心问题）。
   * 故必须显式提示并要求用户确认，见 {@link acknowledgeClearRelations}。
   */
  const [definitionUnavailable, setDefinitionUnavailable] = useState(false)
  /** 用户已显式确认「本次保存会清空关系」 */
  const [acknowledgeClearRelations, setAcknowledgeClearRelations] = useState(false)
  /** 编排声明解析失败的原因（null = 正常） */
  const [orchestrationError, setOrchestrationError] = useState<string | null>(null)
  /**
   * ⚠️ 发布闸门（**唯一判据来源**）：提交按钮的 `disabled` 与提交函数内的
   *    提前 `return` 都读它。二者若各写一份判据，迟早漂移 ——
   *    漂移后要么「按钮可点了却被静默拦下」（用户只看到「点了没反应」），
   *    要么「按钮禁用了但程序化调用仍能提交空关系」。
   */
  const publishGate = evaluatePublishGate({
    orchestrationError,
    definitionUnavailable,
    acknowledgeClearRelations,
  })
  /** 「直接发布版本快照」用的 definition 原文（与上面的权威定义**不是**同一份东西） */
  const [definitionText, setDefinitionText] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [drawerSubmitting, setDrawerSubmitting] = useState(false)
  const [deprecateSubmitting, setDeprecateSubmitting] = useState(false)
  const [deprecateForm] = Form.useForm<{ reason: string }>()
  const [form] = Form.useForm<{ definition: string }>()
  const [drawerForm] = Form.useForm<{
    name: string
    description?: string
    roles?: string[]
    enabledSkills?: string[]
    skillBundles?: string[]
    capabilityTags?: string[]
    overrides?: string
    orchestrationMode: OrchestrationMode
    orchestrationChildren: ChildRefFormValue[]
    routingPolicyRef?: string
    resultContractRef?: string
    maxDelegationDepth?: number
    maxChildInvocations?: number
    timeoutMs?: number
    failurePolicy?: FailurePolicy
    reason: string
  }>()

  const load = useCallback(async () => {
    setLoading(true)
    let loadedAgent: AgentItem | null = null
    try {
      const [a, v] = await Promise.all([getAgent(id), getAgentVersions(id)])
      loadedAgent = a
      setAgent(a)
      setVersions(v)
      setMissing(false)
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setMissing(true)
      } else {
        message.error(e instanceof Error ? e.message : '加载 Agent 详情失败')
      }
      setLoading(false)
      return
    }

    // ⚠️ **名称 / 描述先按旧体系回填**：这两个字段不依赖多级定义，
    //    取不到权威定义时也拿得到 —— 让用户只补真正缺失的关系字段，
    //    而不是面对一个连名字都空着的表单。
    // ⚠️ overrides 先给默认的 '{}'（= 无收紧覆盖）：它与提交逻辑
    //    `values.overrides?.trim() || '{}'` 一致。取不到权威定义时
    //    「原有的收紧覆盖」无从得知，但这一点已由「保存会清空关系」的
    //    显式告警 + 显式确认覆盖，不算静默清空。
    drawerForm.setFieldsValue({
      name: loadedAgent?.name ?? '',
      description: loadedAgent?.description ?? '',
      overrides: '{}',
    })

    // ── 权威定义（cap_agent_def）──
    // ⚠️ **必须独立加载**：它是多级 Agent 的权威定义，与 /api/agents 的
    //    latestDefinition（旧体系的自由 JSON 快照）**不是同一份东西**。
    //    关系字段（启用哪些 Skill / 声明哪些子 Agent / 编排声明）只在这里。
    try {
      const d = await getAgentDefinition(id)
      setAgentDefinition(d)
      setDefinitionUnavailable(false)
      setAcknowledgeClearRelations(false)

      // ⚠️ 编排声明解析失败**必须显式报错**：静默当空 = 保存时把一份合法的
      //    编排声明覆盖成 LEAF（= 取消全部委派），且用户看不出来。
      const parsed = parseOrchestrationJsonStrict(d.orchestration)
      if (!parsed.ok) {
        setOrchestrationError(parsed.error)
      } else {
        setOrchestrationError(null)
        drawerForm.setFieldsValue({
          orchestrationMode: parsed.values.mode,
          orchestrationChildren: parsed.values.childAgentRefs,
          routingPolicyRef: parsed.values.routingPolicyRef ?? undefined,
          resultContractRef: parsed.values.resultContractRef ?? undefined,
          maxDelegationDepth: parsed.values.maxDelegationDepth ?? undefined,
          maxChildInvocations: parsed.values.maxChildInvocations ?? undefined,
          timeoutMs: parsed.values.timeoutMs ?? undefined,
          failurePolicy: parsed.values.failurePolicy ?? undefined,
        })
      }

      // ⚠️ **完整回填全部关系字段** —— 只回填 name/description 会让
      //    「改一下就静默清空关系」成为常态。
      drawerForm.setFieldsValue({
        name: d.name ?? loadedAgent?.name ?? '',
        description: d.description ?? loadedAgent?.description ?? '',
        roles: [...d.roles],
        enabledSkills: [...d.enabledSkills],
        skillBundles: [...d.skillBundles],
        capabilityTags: [...d.capabilityTags],
        // ⚠️ overrides 是「收紧覆盖」，**不是**整份 definition ——
        //    先前误填 latestDefinition 全文，语义完全错位。
        overrides: d.overrides,
      })
    } catch (e) {
      // ⚠️ 取不到权威定义 → 关系字段无从回填。此时**不得**让保存静默清空关系，
      //    必须显式提示并要求用户确认（见发布抽屉里的确认勾选框）。
      setAgentDefinition(null)
      setDefinitionUnavailable(true)
      setAcknowledgeClearRelations(false)
      if (!(e instanceof ApiError && e.status === 404)) {
        message.error(
          e instanceof Error
            ? `读取多级定义失败：${e.message}`
            : '读取多级定义失败（关系字段无法回填）',
        )
      }
    } finally {
      setLoading(false)
    }
  }, [id, message, drawerForm])

  useEffect(() => {
    void load()
  }, [load])

  const submitPublish = async (): Promise<void> => {
    let raw: string
    try {
      raw = await form.validateFields().then((v) => v.definition)
    } catch {
      return
    }
    // 客户端 JSON 预校验（后端 400 兜底）
    if (!isValidJson(raw)) {
      message.error('definition 不是合法 JSON')
      return
    }
    setSubmitting(true)
    try {
      const v = await publishAgentVersion(id, { definition: raw })
      message.success(`已发布 v${v.versionNumber}`)
      form.resetFields()
      setDefinitionText('')
      void load()
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        message.error('版本号并发冲突（409），请刷新后重试')
      } else if (e instanceof ApiError && e.status === 400) {
        message.error('definition 不是合法 JSON（400）')
      } else {
        message.error(e instanceof Error ? e.message : '发布失败')
      }
    } finally {
      setSubmitting(false)
    }
  }

  /**
   * 发布抽屉提交（P2）：构造结构化 spec → 提交 PUBLISH 申请。
   *
   * ⚠️ 版本号与内容指纹由服务端决定（客户端不采信）——
   * 「内容没变」这个证明不能由客户端自己说。
   */
  const submitDrawer = async (): Promise<void> => {
    let values: {
      name: string
      description?: string
      roles?: string[]
      enabledSkills?: string[]
      skillBundles?: string[]
      capabilityTags?: string[]
      overrides?: string
      orchestrationMode: OrchestrationMode
      orchestrationChildren: ChildRefFormValue[]
      routingPolicyRef?: string
      resultContractRef?: string
      maxDelegationDepth?: number
      maxChildInvocations?: number
      timeoutMs?: number
      failurePolicy?: FailurePolicy
      reason: string
    }
    try {
      values = await drawerForm.validateFields()
    } catch {
      return
    }
    // ⚠️ **安全闸门：编排声明解析失败 / 权威定义取不到 → 拒绝提交**。
    //    ① 回填失败时表单里的编排字段是「空」的，若照此提交就等于把一份
    //       合法的编排声明**覆盖成 LEAF**（= 取消全部委派）。必须显式报错。
    //    ② 取不到权威定义时关系字段无从回填，保存等于把关系**静默清空**。
    //    ⚠️ 判据来自**同一个纯函数** `evaluatePublishGate` —— 提交按钮的
    //       `disabled` 属性与这里的提前 return **共用一份判据**，
    //       否则两份判据迟早漂移（漂移后要么「点了没反应」，要么「拦不住」）。
    const gate = evaluatePublishGate({ orchestrationError, definitionUnavailable, acknowledgeClearRelations })
    if (gate.blocked) {
      message.error(gate.reason ?? '发布被闸门拦截')
      return
    }
    const spec: PublishAgentSpec = {
      agentId: id,
      name: values.name.trim(),
      description: values.description?.trim() || null,
      roles: values.roles ?? [],
      enabledSkills: values.enabledSkills ?? [],
      skillBundles: values.skillBundles ?? [],
      capabilityTags: values.capabilityTags ?? [],
      overrides: values.overrides?.trim() || '{}',
    }
    if (!isValidJson(spec.overrides ?? '{}')) {
      message.error('overrides 不是合法 JSON')
      return
    }
    // ⚠️ 编排声明：先本地校验再提交 —— 让作者**当场**看到错误，
    //    而不是提交后才被服务端的 fail-closed 门禁拒。
    //    ⚠️ 本地校验**不是防线**：服务端 `AgentOrchestrationParser` 才是权威
    //    （配置期 fail-closed，绝不「降级成 LEAF 照常发布」）。
    const orchestrationValues: OrchestrationFormValues = {
      mode: values.orchestrationMode ?? 'LEAF',
      childAgentRefs: values.orchestrationChildren ?? [],
      routingPolicyRef: values.routingPolicyRef ?? null,
      resultContractRef: values.resultContractRef ?? null,
      maxDelegationDepth: values.maxDelegationDepth ?? null,
      maxChildInvocations: values.maxChildInvocations ?? null,
      timeoutMs: values.timeoutMs ?? null,
      failurePolicy: values.failurePolicy ?? null,
    }
    const orchestrationCheck = validateOrchestration(orchestrationValues)
    if (!orchestrationCheck.ok) {
      message.error(`编排声明非法：${orchestrationCheck.error}`)
      return
    }
    spec.orchestration = buildOrchestrationJson(orchestrationValues)
    setDrawerSubmitting(true)
    try {
      const req = await submitPublishRequest({
        operation: 'PUBLISH',
        scope: 'TENANT',
        agents: [spec],
        reason: values.reason.trim(),
      })
      message.success(`申请已提交（${req.requestId.slice(0, 8)}…），待双人复核后执行`)
      drawerForm.resetFields()
      navigate('/publish')
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        message.error('申请被门禁预检拒绝（400）：请检查 spec 或理由')
      } else if (e instanceof ApiError && e.status === 403) {
        message.error('作用域不被允许（403）')
      } else {
        message.error(e instanceof Error ? e.message : '提交申请失败')
      }
    } finally {
      setDrawerSubmitting(false)
    }
  }

  /**
   * 停用提交：Agent 的生命周期迁移（LIFECYCLE_TRANSITION）。
   *
   * ⚠️ **走租户级三步工作流**（`/api/cap/config/publishes`）——
   *    Agent 是租户级对象，不是平台级对象，所以走的是租户通道
   *    （平台通道只管 tool / skill / mcp_server）。
   * ⚠️ version 必须是**当前**版本号 —— 它就是 CAS 前置条件，
   *    对不上时后端拒绝迁移（说明别人先动了）。
   */
  const submitDeprecate = async (): Promise<void> => {
    let values: { reason: string }
    try {
      values = await deprecateForm.validateFields()
    } catch {
      return
    }
    const version = agentDefinition?.version ?? agent?.latestVersionNumber ?? 0
    if (version <= 0) {
      message.error('无法确定当前版本号，不能提交停用申请')
      return
    }
    setDeprecateSubmitting(true)
    try {
      const req = await submitPublishRequest({
        operation: 'LIFECYCLE_TRANSITION',
        scope: 'TENANT',
        lifecycle: {
          type: 'agent',
          objectId: id,
          version,
          expectedFrom: 'PUBLISHED',
          to: 'DEPRECATED',
        },
        reason: values.reason.trim(),
      })
      message.success(`停用申请已提交（${req.requestId.slice(0, 8)}…），待双人复核后执行`)
      deprecateForm.resetFields()
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        message.error('申请被门禁预检拒绝（400）：请检查版本号与理由')
      } else if (e instanceof ApiError && e.status === 403) {
        message.error('作用域不被允许（403）')
      } else {
        message.error(e instanceof Error ? e.message : '提交停用申请失败')
      }
    } finally {
      setDeprecateSubmitting(false)
    }
  }

  if (loading) return <Spin spinning style={{ width: '100%', marginTop: 80 }} />

  if (missing) {
    return (
      <div>
        <Typography.Title level={3}>
          <Space><RobotOutlined /> Agent 详情</Space>
        </Typography.Title>
        <Alert
          type="warning"
          showIcon
          message="Agent 不存在或无权限"
          description="该 Agent 不存在，或不属于当前租户（404 不区分「不存在」与「无权限」，避免存在性探测）。"
          action={<Button onClick={() => navigate('/agents')}>返回列表</Button>}
        />
      </div>
    )
  }

  return (
    <div>
      <Typography.Title level={3}>
        <Space>
          <RobotOutlined /> {agent?.name ?? 'Agent'}
          <Typography.Text type="secondary" code style={{ fontSize: 14 }}>{id}</Typography.Text>
        </Space>
      </Typography.Title>

      <Tabs
        items={[
          {
            key: 'overview',
            label: '概览',
            children: (
              <Card>
                <Descriptions bordered column={2} size="small" items={[
                  { key: 'name', label: '名称', children: agent?.name ?? '-' },
                  { key: 'desc', label: '描述', children: agent?.description ?? '-' },
                  {
                    key: 'versions',
                    label: '版本',
                    children: agent && agent.versionCount > 0
                      ? <Tag color="blue">当前 v{agent.latestVersionNumber} · 共 {agent.versionCount}</Tag>
                      : <Tag color="orange">尚无版本</Tag>,
                  },
                  { key: 'published', label: '最近发布', children: agent?.latestPublishedAt ?? '-' },
                ]} />
                {/* ⚠️ 权威定义（cap_agent_def）与旧体系快照是**两份不同的东西**，
                    概览里分开呈现，避免运维把其中一份当成另一份。 */}
                <Alert
                  style={{ marginTop: 16 }}
                  type={agentDefinition ? 'info' : 'warning'}
                  showIcon
                  message={
                    agentDefinition
                      ? `多级定义（cap_agent_def）· v${agentDefinition.version}${agentDefinition.published ? ' · 已发布' : ' · 未发布'}`
                      : '该 Agent 尚无多级定义（cap_agent_def）'
                  }
                  description={
                    agentDefinition ? (
                      <Descriptions bordered column={2} size="small" items={[
                        { key: 'roles', label: '角色', children: agentDefinition.roles.join('、') || '-' },
                        { key: 'skills', label: '启用技能', children: agentDefinition.enabledSkills.join('、') || '-' },
                        { key: 'bundles', label: '技能包', children: agentDefinition.skillBundles.join('、') || '-' },
                        { key: 'tags', label: '能力标签', children: agentDefinition.capabilityTags.join('、') || '-' },
                        { key: 'overrides', label: '收紧覆盖', children: <pre style={{ margin: 0, fontSize: 11 }}>{agentDefinition.overrides}</pre>, span: 2 },
                        { key: 'orchestration', label: '编排声明', children: <pre style={{ margin: 0, fontSize: 11 }}>{agentDefinition.orchestration || '（无）'}</pre>, span: 2 },
                      ]} />
                    ) : (
                      '关系字段（角色 / 启用技能 / 技能包 / 能力标签 / 编排声明）取不到权威定义 —— ' +
                      '在发布抽屉中提交会**清空**这些关系，需显式确认。'
                    )
                  }
                />
                {agent?.latestDefinition && (
                  <Alert
                    style={{ marginTop: 16 }}
                    type="info"
                    showIcon
                    message="当前版本 definition（不可变快照）"
                    description={
                      <pre style={{ margin: 0, maxHeight: 360, overflow: 'auto', fontSize: 12 }}>
                        {prettyJson(agent.latestDefinition)}
                      </pre>
                    }
                  />
                )}
              </Card>
            ),
          },
          {
            key: 'versions',
            label: `版本历史（${versions.length}）`,
            children: (
              <Card>
                <Table<AgentVersion>
                  rowKey="versionId"
                  dataSource={versions}
                  pagination={false}
                  locale={{ emptyText: '尚无版本' }}
                  columns={[
                    {
                      title: '版本',
                      dataIndex: 'versionNumber',
                      render: (n: number) => <Tag color="blue">v{n}</Tag>,
                    },
                    {
                      title: '发布状态',
                      dataIndex: 'publishedAt',
                      render: (v: string | null) => v ? <Tag color="green">已发布</Tag> : <Tag>草稿</Tag>,
                    },
                    { title: '发布时间', dataIndex: 'publishedAt', render: (v: string | null) => v ?? '-' },
                    { title: '创建时间', dataIndex: 'createdAt' },
                    {
                      title: 'definition',
                      dataIndex: 'definition',
                      render: (d: string) => (
                        <Typography.Paragraph
                          code
                          ellipsis={{ rows: 2, expandable: true, symbol: '展开' }}
                          style={{ marginBottom: 0, maxWidth: 420 }}
                        >
                          {prettyJson(d)}
                        </Typography.Paragraph>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'dependencies',
            label: '依赖',
            children: <AgentDependenciesTab agentId={id} />,
          },
          {
            key: 'delegation',
            label: '委派拓扑',
            children: <DelegationTab agentId={id} />,
          },
          {
            key: 'publish',
            label: '发布',
            children: (
              <Space direction="vertical" size="large" style={{ width: '100%' }}>
                <Card
                  title="发布抽屉（三步工作流 · 双人复核）"
                  extra={<Tag color="blue">推荐</Tag>}
                >
                  <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 16 }}
                    message="申请 → 双人复核 → 执行"
                    description="提交只生成申请单（不写配置表）；审批人不得等于申请人；执行才真正生效。提交后可在发布中心跟踪。"
                  />
                  {/* ⚠️ 取不到权威定义时的显式告警 + 显式确认 —— 绝不静默清空关系 */}
                  {definitionUnavailable && (
                    <Alert
                      type="error"
                      showIcon
                      style={{ marginBottom: 16 }}
                      message="该 Agent 尚无多级定义（cap_agent_def），保存会清空关系"
                      description={
                        '关系字段（角色 / 启用技能 / 技能包 / 能力标签 / 编排声明 / 配置覆盖 overrides）取不到权威定义，' +
                        '本次提交会把它们**全部写成空**（overrides 会被重置为 {}，即「无收紧覆盖」）。' +
                        '请先确认这一点再提交。'
                      }
                    />
                  )}
                  {orchestrationError && (
                    <Alert
                      type="error"
                      showIcon
                      style={{ marginBottom: 16 }}
                      message="编排声明解析失败，已阻止提交"
                      description={
                        `${orchestrationError}。回填失败时表单里的编排字段是空的，` +
                        '若照此提交就等于把编排声明覆盖成 LEAF（取消全部委派）。请先修复配置。'
                      }
                    />
                  )}
                  {/* ⚠️ 刻意给 Form 命名：三个表单都含 reason 字段时，不加前缀会产出
                      重复的 DOM id，标签与输入框的关联会指错地方。 */}
                  <Form form={drawerForm} name="agent-publish" layout="vertical">
                    <Form.Item
                      name="name"
                      label="名称"
                      rules={[{ required: true, message: '请输入名称' }]}
                    >
                      <Input />
                    </Form.Item>
                    <Form.Item name="description" label="描述">
                      <Input.TextArea rows={2} />
                    </Form.Item>
                    <Form.Item name="roles" label="角色（roles）" extra="回车分隔多个值">
                      <Select mode="tags" placeholder="如 TEACHER" tokenSeparators={[',']} />
                    </Form.Item>
                    <Form.Item name="enabledSkills" label="启用技能（enabledSkills）">
                      <Select mode="tags" placeholder="技能 id" tokenSeparators={[',']} />
                    </Form.Item>
                    <Form.Item name="skillBundles" label="技能包（skillBundles）">
                      <Select mode="tags" placeholder="技能包 id" tokenSeparators={[',']} />
                    </Form.Item>
                    <Form.Item name="capabilityTags" label="能力标签（capabilityTags）">
                      <Select mode="tags" placeholder="标签" tokenSeparators={[',']} />
                    </Form.Item>
                    <Form.Item
                      name="overrides"
                      label="配置覆盖（overrides · JSON）"
                      rules={[{ required: true, message: 'overrides 不能为空' }]}
                      extra="⚠️ overrides 是「收紧覆盖」，**不是**整份 definition。回填自权威定义的 overrides 字段；空对象写 {}"
                    >
                      <Input.TextArea
                        rows={8}
                        style={{ fontFamily: 'monospace', fontSize: 12 }}
                      />
                    </Form.Item>
                    {/* ── 编排声明（多级 Agent v1.1 · 第二十九批 · C29-1 · A6）── */}
                    <Form.Item
                      name="orchestrationMode"
                      label="编排模式（orchestration.mode）"
                      initialValue="LEAF"
                      extra="LEAF = 不允许委派（缺省）；ROUTER = 按路由策略择一；SEQUENTIAL = 按声明顺序依次执行"
                    >
                      <Select
                        options={ORCHESTRATION_MODES.map((m) => ({
                          value: m,
                          label: m,
                        }))}
                      />
                    </Form.Item>
                    <Form.List name="orchestrationChildren">
                      {(fields, { add, remove }) => (
                        <>
                          {fields.map((field) => (
                            <Space key={field.key} align="baseline">
                              <Form.Item
                                {...field}
                                name={[field.name, 'id']}
                                label="子 Agent id"
                                rules={[{ required: true, message: '请输入子 Agent id' }]}
                              >
                                <Input placeholder="子 Agent 标识（UUID）" style={{ width: 260 }} />
                              </Form.Item>
                              <Form.Item
                                {...field}
                                name={[field.name, 'version']}
                                label="版本"
                                rules={[{ required: true, message: '必须固定版本' }]}
                              >
                                <InputNumber min={1} precision={0} placeholder="固定 release 版本" />
                              </Form.Item>
                              <Form.Item
                                {...field}
                                name={[field.name, 'required']}
                                label="必需"
                                initialValue
                              >
                                <Select
                                  style={{ width: 96 }}
                                  options={[
                                    { value: true, label: '是' },
                                    { value: false, label: '否' },
                                  ]}
                                />
                              </Form.Item>
                              <Button danger onClick={() => remove(field.name)}>
                                移除
                              </Button>
                            </Space>
                          ))}
                          <Button
                            onClick={() =>
                              add({ id: '', version: null, required: true })
                            }
                          >
                            添加子 Agent 引用
                          </Button>
                        </>
                      )}
                    </Form.List>
                    <Form.Item
                      name="routingPolicyRef"
                      label="路由策略引用（routingPolicyRef · id@version）"
                      extra="ROUTER 模式必填；版本必须固定，不得用 latest"
                    >
                      <Input placeholder="如 policy-a@1" />
                    </Form.Item>
                    <Form.Item
                      name="resultContractRef"
                      label="输出契约引用（resultContractRef · id@version）"
                      extra="版本必须固定，不得用 latest；契约需先在「输出契约」页登记"
                    >
                      <Input placeholder="如 answer-contract@1" />
                    </Form.Item>
                    <Space size="large" wrap>
                      <Form.Item
                        name="maxDelegationDepth"
                        label="最大委派深度"
                        extra="非 LEAF 必填；平台不提供默认值"
                      >
                        <InputNumber min={1} precision={0} style={{ width: 140 }} />
                      </Form.Item>
                      <Form.Item
                        name="maxChildInvocations"
                        label="最大子调用次数"
                        extra="非 LEAF 必填；0 = 不允许发起任何子调用"
                      >
                        <InputNumber min={0} precision={0} style={{ width: 140 }} />
                      </Form.Item>
                      <Form.Item
                        name="timeoutMs"
                        label="委派超时（毫秒）"
                        extra="非 LEAF 必填；平台不提供默认值"
                      >
                        <InputNumber min={1} precision={0} style={{ width: 160 }} />
                      </Form.Item>
                      <Form.Item name="failurePolicy" label="失败策略">
                        <Select
                          allowClear
                          placeholder="留空 = 服务端默认值"
                          options={FAILURE_POLICIES.map((p) => ({ value: p, label: p }))}
                        />
                      </Form.Item>
                    </Space>
                    <Form.Item
                      name="reason"
                      label="变更理由"
                      rules={[{ required: true, message: '变更理由不能为空（将留痕）' }]}
                    >
                      <Input.TextArea rows={2} placeholder="为何要做这次变更" />
                    </Form.Item>
                    {/* ⚠️ 关系会被清空时，必须由用户**显式确认**才能提交 ——
                        绝不静默清空。 */}
                    {definitionUnavailable && (
                      <Form.Item style={{ marginBottom: 12 }}>
                        <Checkbox
                          checked={acknowledgeClearRelations}
                          onChange={(e) => setAcknowledgeClearRelations(e.target.checked)}
                        >
                          我已知悉：该 Agent 尚无多级定义（cap_agent_def），本次提交会把角色 / 启用技能 /
                          技能包 / 能力标签 / 编排声明**全部写成空**，
                          且配置覆盖（overrides）会被重置为 {'{}'}（= 无收紧覆盖）
                        </Checkbox>
                      </Form.Item>
                    )}
                    <Button
                      type="primary"
                      icon={<SendOutlined />}
                      loading={drawerSubmitting}
                      // ⚠️ 与 `submitDrawer` 内的提前 return **共用同一判据**
                      //    （`evaluatePublishGate`）—— 两处各写一份必然漂移。
                      disabled={publishGate.blocked}
                      onClick={() => void submitDrawer()}
                    >
                      提交变更申请
                    </Button>
                  </Form>
                </Card>

                {/* ── 停用（生命周期迁移，不是物理删除） ── */}
                <Card title="停用（生命周期迁移）">
                  <Alert
                    type="warning"
                    showIcon
                    style={{ marginBottom: 16 }}
                    message="停用 = 迁移到 DEPRECATED，不是物理删除"
                    description="已发布行不可变（DB 触发器），物理删除会让引用它的对象变成悬空引用。停用走「申请 → 双人复核 → 执行」。"
                  />
                  {definitionUnavailable ? (
                    <Alert
                      type="info"
                      showIcon
                      message="该 Agent 尚无多级定义（cap_agent_def），没有可迁移的生命周期行"
                      description="先发布一版多级定义，才能对它做停用迁移。"
                    />
                  ) : (
                    <Form form={deprecateForm} name="agent-deprecate" layout="vertical">
                      <Form.Item
                        name="reason"
                        label="停用理由"
                        rules={[{ required: true, message: '停用理由不能为空（将留痕）' }]}
                      >
                        <Input.TextArea rows={2} placeholder="为何停用该 Agent（将留痕）" />
                      </Form.Item>
                      <Button
                        danger
                        icon={<StopOutlined />}
                        loading={deprecateSubmitting}
                        onClick={() => void submitDeprecate()}
                      >
                        提交停用申请
                      </Button>
                    </Form>
                  )}
                </Card>

                <Collapse
                  items={[{
                    key: 'direct',
                    label: '高级：直接发布版本快照（不经审批 · 运维/自动化通道）',
                    children: (
                      <div>
                        <Alert
                          type="warning"
                          showIcon
                          style={{ marginBottom: 16 }}
                          message="此路径立即生效，无双人复核"
                          description="POST /api/agents/{id}/versions 直接写入 agent_version 快照。日常变更请走上方发布抽屉。"
                        />
                        <Form form={form} name="agent-snapshot" layout="vertical">
                          <Form.Item
                            name="definition"
                            label="definition（JSON）"
                            rules={[{ required: true, message: '请输入 definition JSON' }]}
                            extra={<Typography.Text type="secondary">当前将发布 v{(agent?.latestVersionNumber ?? 0) + 1}</Typography.Text>}
                          >
                            <Input.TextArea
                              rows={14}
                              placeholder='{"goal":"…","steps":[…]}'
                              value={definitionText}
                              onChange={(e) => setDefinitionText(e.target.value)}
                              style={{ fontFamily: 'monospace', fontSize: 12 }}
                            />
                          </Form.Item>
                          <Button
                            icon={<SendOutlined />}
                            loading={submitting}
                            onClick={() => void submitPublish()}
                          >
                            直接发布
                          </Button>
                        </Form>
                      </div>
                    ),
                  }]}
                />
              </Space>
            ),
          },
        ]}
      />
    </div>
  )
}

export default AgentDetailPage
