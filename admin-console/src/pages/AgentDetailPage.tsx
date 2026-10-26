import { useCallback, useEffect, useState } from 'react'
import { Navigate, useParams, useNavigate, useSearchParams } from 'react-router-dom'
import {
  Card,
  Tabs,
  Table,
  Tag,
  Button,
  Space,
  Typography,
  Spin,
  Alert,
  Form,
  Input,
  Collapse,
  App as AntApp,
} from 'antd'
import { RobotOutlined, SendOutlined, StopOutlined, ArrowLeftOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  getAgent,
  getAgentDefinition,
  getAgentVersions,
  publishAgentVersion,
  submitPublishRequest,
} from '@/api/adminApi'
import { isValidJson } from './agentDefinition'
import { AgentDependenciesTab } from './AgentDependenciesTab'
import { DelegationTab } from './dependencies/DelegationTab'
import { AgentDraftPanel } from './agents/AgentDraftPanel'
import { AgentOverview } from './agents/AgentOverview'
import { NEW_AGENT_SEGMENT } from './agents/agentDraftForm'
import type { AgentDefinition, AgentItem, AgentVersion } from '@/api/types'

/**
 * Agent 详情 / 新建（第三十五批 · 管理台 Agent 全字段增改查）。
 *
 * <p>概览 / 版本历史 / 依赖（P3）/ 委派拓扑（C34-3）/ 编辑（发新版本）/ 停用（C34-3）/ 直接发布（旧通道）。
 *
 * <p>⚠️ <b>「新建」与「修改」共用同一张草稿表单</b>（{@code /agents/new} 是保留路由段）——
 * 分两条路由就会有两份表单，迟早漂移。差别只在「对象是否已存在」。
 *
 * <p>⚠️ <b>definition 是不可变快照</b>（FR-002）：本页不提供「编辑已发布版本」，
 * 要改只能再发一个版本。
 *
 * <p>⚠️ <b>两条发布路径刻意并存</b>：
 * <ul>
 *   <li><b>编辑页签</b>：走 {@code /api/cap/config/publishes} 三步工作流
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
  const isRegistration = id === NEW_AGENT_SEGMENT
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { message } = AntApp.useApp()
  const [agent, setAgent] = useState<AgentItem | null>(null)
  const [versions, setVersions] = useState<AgentVersion[]>([])
  const [loading, setLoading] = useState(!isRegistration)
  const [missing, setMissing] = useState(false)
  /** 权威定义（cap_agent_def 的最新版本）—— 多级 Agent 的关系字段来源 */
  const [agentDefinition, setAgentDefinition] = useState<AgentDefinition | null>(null)
  /**
   * ⚠️ 取不到权威定义（cap_agent_def）时置 true。
   *
   * <p>这时**关系字段无从回填** —— 继续保存就会把它们**静默清空**
   * （「改一下就把关系清了」是本轮修复的核心问题）。
   * 故必须显式提示并要求用户确认（见草稿面板里的确认勾选框）。
   */
  const [definitionUnavailable, setDefinitionUnavailable] = useState(false)
  /** 「直接发布版本快照」用的 definition 原文（与上面的权威定义**不是**同一份东西） */
  const [definitionText, setDefinitionText] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [deprecateSubmitting, setDeprecateSubmitting] = useState(false)
  const [deprecateForm] = Form.useForm<{ reason: string }>()
  const [form] = Form.useForm<{ definition: string }>()

  const load = useCallback(async () => {
    if (isRegistration) return
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
      setLoading(false)
      return
    }

    // ── 权威定义（cap_agent_def）──
    // ⚠️ **必须独立加载**：它是多级 Agent 的权威定义，与 /api/agents 的
    //    latestDefinition（旧体系的自由 JSON 快照）**不是同一份东西**。
    //    关系字段（启用哪些 Skill / 声明哪些子 Agent / 编排声明）只在这里。
    try {
      const d = await getAgentDefinition(id)
      setAgentDefinition(d)
      setDefinitionUnavailable(false)
    } catch (e) {
      // ⚠️ 取不到权威定义 → 关系字段无从回填。此时**不得**让保存静默清空关系，
      //    必须显式提示并要求用户确认（见草稿面板里的确认勾选框）。
      setAgentDefinition(null)
      setDefinitionUnavailable(true)
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
  }, [id, isRegistration, message])

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

  // ── 新建模式：只作**深链入口** → 回列表页并打开新建弹窗 ──
  //    ⚠️ **必须放在全部 Hooks 之后**（提前 return 会让本组件在
  //    「/agents/new → /agents/xxx」的导航中出现 Hook 数量突变，React 直接报错）。
  //    ⚠️ 登记表单只有一份（AgentDraftPanel），弹窗与详情页共用它 ——
  //    同一套登记表单不该有两个宿主。
  if (isRegistration) {
    return <Navigate to="/agents?register=1" replace />
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

  // ⚠️ 默认页签：列表页的「编辑」按钮带 ?tab=edit 直达编辑页签。
  const requestedTab = searchParams.get('tab')
  const defaultTab = requestedTab === 'edit' ? 'edit' : 'overview'

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/agents')}>
          返回 Agent 管理
        </Button>
      </Space>
      <Typography.Title level={3}>
        <Space>
          <RobotOutlined /> {agent?.name ?? 'Agent'}
          <Typography.Text type="secondary" code style={{ fontSize: 14 }}>{id}</Typography.Text>
        </Space>
      </Typography.Title>

      <Tabs
        defaultActiveKey={defaultTab}
        items={[
          {
            key: 'overview',
            label: '概览',
            children: (
              <AgentOverview
                agent={agent}
                definition={agentDefinition}
                definitionUnavailable={definitionUnavailable}
              />
            ),
          },
          {
            key: 'edit',
            label: '编辑',
            children: (
              <AgentDraftPanel
                agentId={id}
                definition={agentDefinition}
                definitionUnavailable={definitionUnavailable}
                legacy={{ name: agent?.name, description: agent?.description }}
                onSubmitted={(r) => {
                  if (r.failed) {
                    void load()
                    return
                  }
                  navigate('/publish')
                }}
              />
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
            label: '发布与生命周期',
            children: (
              <Space direction="vertical" size="large" style={{ width: '100%' }}>
                <Alert
                  type="info"
                  showIcon
                  message="日常变更请走「编辑」页签（三步工作流 · 双人复核）"
                  description="本页是生命周期与运维通道：停用走「申请 → 双人复核 → 执行」；直接发布版本快照是旧通道，无审批。"
                />

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
                          description="POST /api/agents/{id}/versions 直接写入 agent_version 快照。日常变更请走「编辑」页签。"
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

/** 旧体系的自由 JSON 快照 —— 解析失败就原样呈现，不吞内容。 */
function prettyJson(raw: string | null): string {
  if (!raw) return '（无）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

export default AgentDetailPage
