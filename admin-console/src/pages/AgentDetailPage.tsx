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
  Form,
  Input,
  InputNumber,
  Select,
  Collapse,
  App as AntApp,
} from 'antd'
import { RobotOutlined, SendOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { getAgent, getAgentVersions, publishAgentVersion, submitPublishRequest } from '@/api/adminApi'
import { isValidJson } from './agentDefinition'
import {
  buildOrchestrationJson,
  ORCHESTRATION_MODES,
  FAILURE_POLICIES,
  validateOrchestration,
  type ChildRefFormValue,
  type FailurePolicy,
  type OrchestrationFormValues,
  type OrchestrationMode,
} from './orchestration/orchestrationForm'
import { AgentDependenciesTab } from './AgentDependenciesTab'
import type { AgentItem, AgentVersion, PublishAgentSpec } from '@/api/types'

function prettyJson(raw: string | null): string {
  if (!raw) return '（无）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/**
 * Agent 详情（P1 + P2 + P3）：概览 / 版本历史 / 依赖（P3）/ 发布抽屉（三步工作流）/ 直接发布（旧通道）。
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
 */
export function AgentDetailPage(): JSX.Element {
  const { id = '' } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [agent, setAgent] = useState<AgentItem | null>(null)
  const [versions, setVersions] = useState<AgentVersion[]>([])
  const [loading, setLoading] = useState(true)
  const [missing, setMissing] = useState(false)
  const [definition, setDefinition] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [drawerSubmitting, setDrawerSubmitting] = useState(false)
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
    try {
      const [a, v] = await Promise.all([getAgent(id), getAgentVersions(id)])
      setAgent(a)
      setVersions(v)
      setMissing(false)
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setMissing(true)
      } else {
        message.error(e instanceof Error ? e.message : '加载 Agent 详情失败')
      }
    } finally {
      setLoading(false)
    }
  }, [id, message])

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
      setDefinition('')
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
                  <Form
                    form={drawerForm}
                    layout="vertical"
                    initialValues={{
                      name: agent?.name ?? '',
                      description: agent?.description ?? '',
                      overrides: agent?.latestDefinition ?? '{}',
                    }}
                  >
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
                      extra="默认取当前版本 definition；空对象写 {}"
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
                    <Button
                      type="primary"
                      icon={<SendOutlined />}
                      loading={drawerSubmitting}
                      onClick={() => void submitDrawer()}
                    >
                      提交变更申请
                    </Button>
                  </Form>
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
                        <Form form={form} layout="vertical">
                          <Form.Item
                            name="definition"
                            label="definition（JSON）"
                            rules={[{ required: true, message: '请输入 definition JSON' }]}
                            extra={<Typography.Text type="secondary">当前将发布 v{(agent?.latestVersionNumber ?? 0) + 1}</Typography.Text>}
                          >
                            <Input.TextArea
                              rows={14}
                              placeholder='{"goal":"…","steps":[…]}'
                              value={definition}
                              onChange={(e) => setDefinition(e.target.value)}
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
