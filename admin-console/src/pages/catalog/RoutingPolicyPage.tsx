import { useState } from 'react'
import {
  App as AntApp,
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Form,
  Input,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ExperimentOutlined, SendOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { submitPublishRequest } from '@/api/adminApi'
import {
  dryRunRoutingPolicy,
  isFailureResolution,
  isRateLimited,
  isRejectedInput,
  isTenantChannelBlocked,
  RESOLUTION_LABELS,
  TRACE_OUTCOME_LABELS,
  type DryRunProbeResult,
  type RoutingDryRunResult,
} from '@/api/routingPolicyApi'
import { isValidJson } from '../agentDefinition'
import type { PublishRoutingPolicySpec, PublishRequestItem } from '@/api/types'

const { Paragraph, Text } = Typography

/**
 * 路由策略登记 + 试跑（10 号设计 §7 债 5 · 管理台 A7-1b / A7-1）。
 *
 * <p><b>为什么「试跑」必须在这个页面上</b>：规则写错字段名<b>不会报错</b> ——
 * 词汇表外的字段在投影时被静默丢弃，线上只表现为「路由经常走兜底」。
 * 发布期守卫（V3~V10）拦得住，但只有<b>提交之后</b>才拦得住，
 * 而配置者需要的是「提交之前就知道」。
 *
 * <p><b>⚠️ 本页不含第二份求值器</b>：三段策略文本（{@code inputSchema} /
 * {@code evaluation} / {@code sampleSet}）与探针一律按<b>原文</b>送后端，
 * 前端只做<b>结构性</b>预检（是不是合法 JSON、必填格是否空着）。
 * 文法、上限、谓词求值、守卫判定的唯一真相在后端 {@code agent-routing}；
 * 这里若复刻一份，漂移的症状就是「界面显示命中 A、运行期去了 B」。
 *
 * <p><b>⚠️ 原文透传的另一半理由</b>：内容指纹按「这一行内容没变」计算，
 * 前端重新序列化会改写空白与键序，等于每次发布都声称「我改过」。
 *
 * <p><b>⚠️ 结论的边界要写在界面上</b>：dry-run 判的是<b>选择</b>（会走到哪个目标、为什么），
 * <b>准入</b>（目标 ∈ 父声明、固定版本、逐跳授权、深度/次数/超时/预算）在运行期判 ——
 * dry-run 没有父声明。故「试跑没问题」<b>不等于</b>「上线一定成功」。
 *
 * <p><b>⚠️ 通道现实</b>：{@code /api/cap/config/**} 是租户域端点，租户来自已验签令牌；
 * 平台控制台持平台令牌，可能 401/403。此时明确报「需租户级令牌通道」，
 * 绝不把「没跑成」显示成「跑通且没问题」（与 AI 实验室同口径）。
 */

/** 表单值：三段策略文本与探针都是<b>原文字符串</b>，不是对象。 */
interface DraftForm {
  policyId: string
  mode: PublishRoutingPolicySpec['mode']
  noMatchAction: PublishRoutingPolicySpec['noMatchAction']
  onConflict?: PublishRoutingPolicySpec['onConflict']
  defaultTarget?: string
  inputSchema: string
  evaluation: string
  sampleSet?: string
  probes: string
  reason: string
}

/** 结构性预检：只判「能不能是一段 JSON」，不判文法（文法归后端）。 */
function checkStructure(v: DraftForm): string | null {
  if (!v.policyId.trim()) return '策略标识不能为空'
  for (const [label, raw] of [
    ['输入词汇表（inputSchema）', v.inputSchema],
    ['规则列表（evaluation）', v.evaluation],
  ] as const) {
    if (!isValidJson(raw)) return `${label} 不是合法 JSON`
  }
  for (const [label, raw] of [
    ['回放样本（sampleSet）', v.sampleSet],
    ['探针列表（probes）', v.probes],
  ] as const) {
    if (raw && raw.trim() && !isValidJson(raw)) return `${label} 不是合法 JSON`
  }
  // 这一条是行级结构，不是语义：后端 DEFAULT_TARGET 缺兜底目标会直接 400，
  // 在这里先问一句，省得作者填完理由走到审批才发现。
  if (v.noMatchAction === 'DEFAULT_TARGET' && !(v.defaultTarget ?? '').trim()) {
    return 'noMatchAction=DEFAULT_TARGET 时必须填写兜底目标'
  }
  return null
}

/** 试跑只校验草稿字段：变更理由属于申请单， dry-run 不产生申请单，不该拦它。 */
const DRAFT_FIELDS: (keyof DraftForm)[] = [
  'policyId',
  'mode',
  'noMatchAction',
  'onConflict',
  'defaultTarget',
  'inputSchema',
  'evaluation',
  'sampleSet',
  'probes',
]

export function RoutingPolicyPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [running, setRunning] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<RoutingDryRunResult | null>(null)
  /** 已生成的申请单标识（本页不跳「发布中心」：那条路由在纯平台控制台里并不存在） */
  const [requestId, setRequestId] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ kind: 'error' | 'warning'; text: string } | null>(null)
  const [form] = Form.useForm<DraftForm>()

  const readDraft = async (names?: (keyof DraftForm)[]): Promise<DraftForm | null> => {
    let values: DraftForm
    try {
      values = await form.validateFields(names)
    } catch {
      return null
    }
    const structural = checkStructure(values)
    if (structural) {
      message.error(structural)
      return null
    }
    return values
  }

  const toSpec = (v: DraftForm): PublishRoutingPolicySpec => ({
    policyId: v.policyId.trim(),
    mode: v.mode,
    // ⚠️ 原文透传：不 JSON.stringify(JSON.parse(...))，那会改写空白与键序
    inputSchema: v.inputSchema,
    evaluation: v.evaluation,
    noMatchAction: v.noMatchAction,
    defaultTarget: (v.defaultTarget ?? '').trim() || undefined,
    onConflict: v.onConflict || undefined,
    sampleSet: v.sampleSet?.trim() ? v.sampleSet : undefined,
  })

  const runDry = async (): Promise<void> => {
    const v = await readDraft(DRAFT_FIELDS)
    if (!v) return
    setRunning(true)
    setNotice(null)
    try {
      setResult(await dryRunRoutingPolicy({ policy: toSpec(v), probes: v.probes.trim() || null }))
    } catch (e) {
      if (isTenantChannelBlocked(e)) {
        setNotice({
          kind: 'warning',
          text: '平台令牌对租户域端点 /api/cap/config/routing-policies/dry-run 不可达（401/403）：'
            + '本租户的租户级令牌通道尚未接入平台控制台。这不是策略有问题。',
        })
      } else if (isRateLimited(e)) {
        setNotice({ kind: 'warning', text: '试跑被限流（429）：请稍候 60 秒再试。' })
      } else if (isRejectedInput(e)) {
        setNotice({
          kind: 'error',
          text: `请求本身不合法（400）：${e instanceof ApiError ? e.message : '未知原因'}`,
        })
      } else {
        setNotice({ kind: 'error', text: e instanceof Error ? e.message : '试跑失败' })
      }
    } finally {
      setRunning(false)
    }
  }

  const submit = async (): Promise<void> => {
    const v = await readDraft()
    if (!v) return
    if (!v.reason.trim()) {
      message.error('变更理由不能为空（将留痕）')
      return
    }
    const spec: PublishRoutingPolicySpec = toSpec(v)
    setSubmitting(true)
    try {
      const req: PublishRequestItem = await submitPublishRequest({
        operation: 'PUBLISH',
        scope: 'TENANT',
        routingPolicies: [spec],
        reason: v.reason.trim(),
      })
      message.success('策略登记申请已提交，待双人复核后执行')
      setRequestId(req.requestId)
      form.resetFields()
      setResult(null)
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        message.error('申请被门禁预检拒绝（400）：请检查策略标识与三段文本')
      } else if (e instanceof ApiError && e.status === 403) {
        message.error('作用域不被允许（403）')
      } else {
        message.error(e instanceof Error ? e.message : '提交申请失败')
      }
    } finally {
      setSubmitting(false)
    }
  }

  const probeColumns: ColumnsType<DryRunProbeResult> = [
    { title: '探针', dataIndex: 'label', width: 160 },
    {
      title: '结论',
      width: 200,
      render: (_, p) =>
        p.decided ? (
          <Tag color={isFailureResolution(p.resolution) ? 'error' : 'success'}>
            {RESOLUTION_LABELS[p.resolution ?? ''] ?? p.resolution}
          </Tag>
        ) : (
          <Tag color="warning">探针本身不合法</Tag>
        ),
    },
    {
      title: '最终目标',
      dataIndex: 'finalTarget',
      width: 160,
      render: (t: string | null) => t ?? <Text type="secondary">—</Text>,
    },
    {
      title: '命中规则',
      dataIndex: 'matchedRuleId',
      width: 160,
      render: (r: string | null) => r ?? <Text type="secondary">—</Text>,
    },
    {
      title: '进入求值的字段',
      render: (_, p) => (
        <Text code style={{ fontSize: 12 }}>
          {JSON.stringify(p.projectedInput)}
        </Text>
      ),
    },
  ]

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Typography.Title level={4}>路由策略登记</Typography.Title>

      <Alert
        type="info"
        showIcon
        message="先试跑，再提交；提交走「申请 → 双人复核 → 执行」"
        description={
          '试跑（dry-run）把这份草稿原样交给运行期同一个引擎与守卫，不落库、不产生申请单。'
          + '它判的是「会走到哪个目标、为什么」，不是「这个目标能不能被委派」'
          + '（准入在运行期判，dry-run 没有父声明）—— 试跑没问题不等于上线一定成功。'
        }
      />

      <Card title="策略草稿">
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            mode: 'RULES_ONLY',
            noMatchAction: 'REJECT',
            onConflict: 'RULE_WINS',
            inputSchema:
              '{\n  "type": "object",\n  "properties": {\n    "text": { "type": "string" }\n  }\n}',
            evaluation: '[\n  {\n    "ruleId": "r-1",\n    "target": "faq-answer",\n    "when": {}\n  }\n]',
            probes: '[\n  {\n    "label": "示例探针",\n    "input": { "text": "怎么退货" }\n  }\n]',
          }}
        >
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="policyId"
                label="策略标识（policyId · 可读字符串）"
                rules={[{ required: true, message: '请输入策略标识' }]}
                extra="不是 UUID；版本号与内容指纹由服务端决定"
              >
                <Input placeholder="如 order-routing" />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="mode" label="目标选择方式（mode）">
                <Select
                  options={[
                    { value: 'RULES_ONLY', label: 'RULES_ONLY（推荐：模型零参与）' },
                    { value: 'RULE_FIRST', label: 'RULE_FIRST' },
                    { value: 'SEMANTIC', label: 'SEMANTIC' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={5}>
              <Form.Item name="noMatchAction" label="无命中处置（noMatchAction）">
                <Select
                  options={[
                    { value: 'REJECT', label: 'REJECT（拒绝委派）' },
                    { value: 'DEFAULT_TARGET', label: 'DEFAULT_TARGET' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={5}>
              <Form.Item name="onConflict" label="冲突裁决（onConflict）" extra="仅 RULE_FIRST / SEMANTIC 有意义">
                <Select
                  allowClear
                  options={[
                    { value: 'RULE_WINS', label: 'RULE_WINS' },
                    { value: 'CANDIDATE_WINS', label: 'CANDIDATE_WINS' },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item
            name="defaultTarget"
            label="兜底目标（defaultTarget）"
            extra="noMatchAction=DEFAULT_TARGET 时必须非空；它仍要经过运行期准入"
          >
            <Input placeholder="如 fallback-agent" style={{ width: 320 }} />
          </Form.Item>

          <Divider orientation="left" plain>
            三段策略文本（原文送后端，前端不重新序列化）
          </Divider>

          <Form.Item
            name="inputSchema"
            label="输入词汇表（inputSchema · 顶层 type=object）"
            rules={[{ required: true, message: '输入词汇表不能为空' }]}
            extra="规则里的 in.* 只能引用这里声明过的字段；这里没声明的字段会被静默丢弃"
          >
            <Input.TextArea rows={8} style={{ fontFamily: 'monospace', fontSize: 12 }} />
          </Form.Item>

          <Form.Item
            name="evaluation"
            label="有序规则列表（evaluation · Rule 数组）"
            rules={[{ required: true, message: '规则列表不能为空' }]}
            extra="数组次序即求值次序，首个命中即止；有界 DNF 上限与守卫由后端判定"
          >
            <Input.TextArea rows={12} style={{ fontFamily: 'monospace', fontSize: 12 }} />
          </Form.Item>

          <Form.Item
            name="sampleSet"
            label="发布期回放样本（sampleSet · 可空）"
            extra="守卫 V9 要求每条规则的目标至少被一个样本命中——缺它会被拦在审批之后"
          >
            <Input.TextArea rows={6} style={{ fontFamily: 'monospace', fontSize: 12 }} />
          </Form.Item>

          <Form.Item
            name="probes"
            label="试跑探针（probes · 只在本次 dry-run 存在，不进策略正文与指纹）"
            extra="每项 {label, input, modelCandidate?, ctx?}；ctx 可覆盖 channel / locale / run.attempt / run.depth，不得写 tenantId"
          >
            <Input.TextArea rows={8} style={{ fontFamily: 'monospace', fontSize: 12 }} />
          </Form.Item>

          <Space>
            <Button
              icon={<ExperimentOutlined />}
              loading={running}
              onClick={() => void runDry()}
            >
              试跑（不提交）
            </Button>
          </Space>

          <Divider orientation="left" plain>
            提交发布申请
          </Divider>

          <Paragraph type="secondary" style={{ marginBottom: 8 }}>
            试跑只是<b>看结论</b>；登记策略仍要走三步工作流，「我批了」与「配置真的变了」必须可区分。
          </Paragraph>
          <Form.Item
            name="reason"
            label="变更理由"
            rules={[{ required: true, message: '变更理由不能为空（将留痕）' }]}
          >
            <Input.TextArea rows={2} placeholder="为何要登记/修改这份策略" />
          </Form.Item>
          <Button
            type="primary"
            icon={<SendOutlined />}
            loading={submitting}
            onClick={() => void submit()}
          >
            提交登记申请
          </Button>
        </Form>
      </Card>

      {requestId && (
        <Alert
          type="success"
          showIcon
          message="申请单已生成，配置尚未改变"
          description={
            <Space direction="vertical">
              <Text code>{requestId}</Text>
              <Text type="secondary">
                {`后续两步：POST /api/cap/config/publishes/${requestId}/decision（另一人复核）、`
                  + '再 POST …/apply（此步才真正写入）。申请人不能自批。'}
              </Text>
            </Space>
          }
        />
      )}

      {notice && (
        <Alert type={notice.kind} showIcon message="试跑未取得结论" description={notice.text} />
      )}

      {result && (
        <Card title="试跑结论">
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions size="small" column={3} bordered>
              <Descriptions.Item label="策略文本可解析">
                <Tag color={result.parsed ? 'success' : 'error'}>
                  {result.parsed ? '是' : '否'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="现在提交可放行">
                <Tag color={result.publishable ? 'success' : 'error'}>
                  {result.publishable ? '是' : '否'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="回放所用 UTC 日">
                {result.replayDate}
              </Descriptions.Item>
            </Descriptions>

            {!result.parsed && (
              <Alert
                type="error"
                showIcon
                message={`策略文本解析失败：${result.errorWhere ?? '?'}`}
                description={result.errorMessage ?? '未给出原因'}
              />
            )}

            {result.violations.length > 0 && (
              <Alert
                type="error"
                showIcon
                message={`发布期守卫拦下 ${result.violations.length} 条`}
                description={
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {result.violations.map((f) => (
                      <li key={f.code}>
                        <Text strong>{f.code}</Text> {f.message}
                      </li>
                    ))}
                  </ul>
                }
              />
            )}

            {result.warnings.length > 0 && (
              <Alert
                type="warning"
                showIcon
                message={`守卫提示 ${result.warnings.length} 条（不阻止发布）`}
                description={
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {result.warnings.map((f) => (
                      <li key={f.code}>
                        <Text strong>{f.code}</Text> {f.message}
                      </li>
                    ))}
                  </ul>
                }
              />
            )}

            {result.probes.length === 0 ? (
              <Paragraph type="secondary">
                未提供探针：以上只是守卫报告。想知道「这条输入会走去哪」，填探针后再试跑。
              </Paragraph>
            ) : (
              <Table
                rowKey="label"
                size="small"
                columns={probeColumns}
                dataSource={result.probes}
                pagination={false}
                expandable={{
                  expandedRowRender: (p) =>
                    p.decided ? (
                      <Space direction="vertical" style={{ width: '100%' }}>
                        {p.error && <Text type="danger">{p.error}</Text>}
                        {p.candidateFromModel && (
                          <Text>模拟的模型候选：{p.candidateFromModel}</Text>
                        )}
                        {p.conflict && <Tag color="warning">规则与候选不同，已按 onConflict 裁决</Tag>}
                        <Text strong>求值轨迹（只含实际判过的规则）</Text>
                        <Timeline
                          items={p.trace.map((t) => ({
                            color: t.outcome === 'MATCH' ? 'green' : t.outcome.startsWith('ERROR') ? 'red' : 'gray',
                            children: (
                              <span>
                                #{t.ordinal} <Text code>{t.ruleId}</Text> —{' '}
                                {TRACE_OUTCOME_LABELS[t.outcome] ?? t.outcome}
                                {t.error ? `：${t.error}` : ''}
                              </span>
                            ),
                          }))}
                        />
                      </Space>
                    ) : (
                      <Text type="danger">
                        {p.issueWhere ?? '探针'}：{p.issueMessage ?? '探针不合法'}
                      </Text>
                    ),
                }}
              />
            )}

            <Paragraph type="secondary" style={{ marginBottom: 0 }}>
              以上结论<b>只覆盖选择</b>：目标是否 ∈ 父声明的子 Agent、固定版本、逐跳授权、
              深度/次数/超时/预算都在运行期准入判定，dry-run 没有父声明，碰不到它们。
            </Paragraph>
          </Space>
        </Card>
      )}
    </Space>
  )
}

export default RoutingPolicyPage
