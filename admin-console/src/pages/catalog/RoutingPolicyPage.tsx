import { useMemo, useState } from 'react'
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
import type { PublishRoutingPolicySpec, PublishRequestItem } from '@/api/types'
import {
  MODE_HINT,
  flattenFields,
  makeDefaultDraft,
  pretty,
  serializeInputSchema,
  serializeProbes,
  serializeRules,
  serializeSamples,
  validateDraft,
} from './routingBuilder/routingVisualModel'
import type { VisualDraft } from './routingBuilder/routingVisualModel'
import {
  PreviewPanel,
  ProbeEditor,
  RuleEditor,
  SampleEditor,
  SchemaEditor,
} from './routingBuilder/routingVisualEditors'

const { Paragraph, Text } = Typography

/**
 * 路由策略登记（可视化配置）+ 试跑（10 号设计 §7 债 5 · 管理台 A7-1b / A7-1）。
 *
 * <p><b>交互模型</b>：策略的四段文本（inputSchema / evaluation / sampleSet / probes）
 * 不再由人手输 JSON，而是由本页的表单控件维护一份结构化草稿，再按后端
 * `PolicyCodec` 的文法序列化成 JSON 文本。这样非技术用户也能用「选字段、选算子、
 * 填取值」的方式完成配置；右侧始终显示即将提交的 JSON 与结构校验提示。
 *
 * <p><b>为什么「试跑」必须在这个页面上</b>：规则写错字段名<b>不会报错</b> ——
 * 词汇表外的字段在投影时被静默丢弃，线上只表现为「路由经常走兜底」。
 * 发布期守卫（V3~V10）拦得住，但只有<b>提交之后</b>才拦得住，
 * 而配置者需要的是「提交之前就知道」。
 *
 * <p><b>⚠️ 本页不含第二份求值器</b>：结构化草稿只做<b>结构性</b>预检
 * （字段是否声明、算子参数是否完整、条数/深度上限、样本覆盖提示），
 * 文法、语义守卫与准入判定的唯一真相在后端 {@code agent-routing}。
 * 这里若复刻一份求值器，漂移的症状就是「界面显示命中 A、运行期去了 B」。
 *
 * <p><b>⚠️ 结论的边界要写在界面上</b>：dry-run 判的是<b>选择</b>（会走到哪个目标、为什么），
 * <b>准入</b>（目标 ∈ 父声明、固定版本、逐跳授权、深度/次数/超时/预算）在运行期判 ——
 * dry-run 没有父声明。故「试跑没问题」<b>不等于</b>「上线一定成功」。
 *
 * <p><b>⚠️ 通道现实</b>：{@code /api/cap/config/**} 是租户域端点，租户来自已验签令牌；
 * 平台控制台持平台令牌，可能 401/403。此时明确报「需租户级令牌通道」，
 * 绝不把「没跑成」显示成「跑通且没问题」（与 AI 实验室同口径）。
 */

interface BasicForm {
  policyId: string
  mode: VisualDraft['mode']
  noMatchAction: VisualDraft['noMatchAction']
  onConflict?: VisualDraft['onConflict']
  defaultTarget?: string
  reason: string
}

/** 基础字段由 Form 持有，四段结构化内容由可视化编辑器持有，合并后才是完整草稿。 */
function withBasic(draft: VisualDraft, basic: Partial<BasicForm>): VisualDraft {
  return {
    ...draft,
    policyId: basic.policyId ?? draft.policyId,
    mode: basic.mode ?? draft.mode,
    noMatchAction: basic.noMatchAction ?? draft.noMatchAction,
    onConflict: basic.onConflict,
    defaultTarget: basic.defaultTarget ?? draft.defaultTarget,
  }
}

export function RoutingPolicyPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [running, setRunning] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<RoutingDryRunResult | null>(null)
  /** 已生成的申请单标识（本页不跳「发布中心」：那条路由在纯平台控制台里并不存在） */
  const [requestId, setRequestId] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ kind: 'error' | 'warning'; text: string } | null>(null)
  const [form] = Form.useForm<BasicForm>()
  const [visual, setVisual] = useState<VisualDraft>(() => makeDefaultDraft())

  // 基础字段逐个订阅：右侧预览与校验随输入实时更新
  const wPolicyId = Form.useWatch('policyId', form)
  const wMode = Form.useWatch('mode', form)
  const wNoMatch = Form.useWatch('noMatchAction', form)
  const wConflict = Form.useWatch('onConflict', form)
  const wDefaultTarget = Form.useWatch('defaultTarget', form)
  const currentDraft = useMemo(
    () => withBasic(visual, {
      policyId: wPolicyId, mode: wMode, noMatchAction: wNoMatch,
      onConflict: wConflict, defaultTarget: wDefaultTarget,
    }),
    [visual, wPolicyId, wMode, wNoMatch, wConflict, wDefaultTarget],
  )

  const flatFields = useMemo(() => flattenFields(visual.fields), [visual.fields])
  const knownTargets = useMemo(() => {
    const values = [
      ...currentDraft.rules.map((r) => r.target.trim()),
      ...currentDraft.samples.map((s) => s.expectTarget.trim()),
      currentDraft.defaultTarget.trim(),
    ]
    return values.filter((v, i) => v && values.indexOf(v) === i)
  }, [currentDraft])

  /** 读取表单 + 结构自检；文法与守卫仍归后端。变更理由属于申请单，dry-run 不校验它。 */
  const readDraft = async (includeReason = false): Promise<{ draft: VisualDraft; reason: string } | null> => {
    const fields: (keyof BasicForm)[] = [
      'policyId', 'mode', 'noMatchAction', 'onConflict', 'defaultTarget',
      ...(includeReason ? (['reason'] as (keyof BasicForm)[]) : []),
    ]
    let basic: BasicForm
    try {
      basic = await form.validateFields(fields)
    } catch {
      return null
    }
    const draft = withBasic(visual, basic)
    const report = validateDraft(draft)
    if (report.errors.length) {
      message.error(report.errors[0])
      return null
    }
    return { draft, reason: (basic.reason ?? '').trim() }
  }

  const toSpec = (d: VisualDraft): PublishRoutingPolicySpec => ({
    policyId: d.policyId.trim(),
    mode: d.mode,
    // 四段文本由可视化编辑器按后端文法生成；空样本集显式写 "[]"（后端 DTO 拒空白）
    inputSchema: pretty(serializeInputSchema(d)),
    evaluation: pretty(serializeRules(d)),
    noMatchAction: d.noMatchAction,
    defaultTarget: d.defaultTarget.trim() || undefined,
    onConflict: d.onConflict || undefined,
    sampleSet: pretty(serializeSamples(d)),
  })

  const runDry = async (): Promise<void> => {
    const parsed = await readDraft()
    if (!parsed) return
    const d = parsed.draft
    setRunning(true)
    setNotice(null)
    try {
      const probes = serializeProbes(d)
      setResult(await dryRunRoutingPolicy({
        policy: toSpec(d),
        probes: probes.length ? pretty(probes) : null,
      }))
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
    const parsed = await readDraft(true)
    if (!parsed) return
    const d = parsed.draft
    const reason = parsed.reason
    if (!reason) {
      message.error('变更理由不能为空（将留痕）')
      return
    }
    const spec: PublishRoutingPolicySpec = toSpec(d)
    setSubmitting(true)
    try {
      const req: PublishRequestItem = await submitPublishRequest({
        operation: 'PUBLISH',
        scope: 'TENANT',
        routingPolicies: [spec],
        reason,
      })
      message.success('策略登记申请已提交，待双人复核后执行')
      setRequestId(req.requestId)
      form.resetFields()
      setVisual(makeDefaultDraft())
      setResult(null)
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        message.error('申请被门禁预检拒绝（400）：请检查策略标识与四段配置内容')
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
      <Typography.Title level={4} style={{ marginBottom: 0 }}>
        路由策略登记
      </Typography.Title>

      <Alert
        type="info"
        showIcon
        message="用下拉、开关和条件选择器完成配置；先试跑，再提交"
        description={
          '页面会把可视化配置转换成后端需要的策略文本，无需手写规则代码。'
          + '试跑（dry-run）把这份草稿原样交给运行期同一个引擎与守卫，不落库、不产生申请单。'
          + '它判的是「会走到哪个目标、为什么」，不是「这个目标能不能被委派」'
          + '（准入在运行期判，dry-run 没有父声明）—— 试跑没问题不等于上线一定成功。'
        }
      />

      <Form
        form={form}
        layout="vertical"
        initialValues={{
          mode: 'RULES_ONLY',
          noMatchAction: 'REJECT',
          onConflict: 'RULE_WINS',
        }}
      >
        <Card title="① 基本信息" size="small">
          <Row gutter={16}>
            <Col xs={24} md={8}>
              <Form.Item
                name="policyId"
                label="策略标识（policyId）"
                rules={[{ required: true, message: '请输入策略标识' }]}
                extra="可读字符串，不是 UUID；版本号与内容指纹由服务端决定"
              >
                <Input placeholder="如 order-routing" />
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item name="mode" label="目标选择方式" extra={MODE_HINT[wMode ?? 'RULES_ONLY']}>
                <Select
                  options={[
                    { value: 'RULES_ONLY', label: '仅规则（推荐，模型零参与）' },
                    { value: 'RULE_FIRST', label: '规则优先，模型兜底' },
                    { value: 'SEMANTIC', label: '语义（完全交给模型）' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={5}>
              <Form.Item name="noMatchAction" label="无命中处置">
                <Select
                  options={[
                    { value: 'REJECT', label: '拒绝委派' },
                    { value: 'DEFAULT_TARGET', label: '走兜底目标' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={5}>
              <Form.Item
                name="onConflict"
                label="冲突裁决"
                extra="仅「规则优先 / 语义」有意义"
              >
                <Select
                  allowClear
                  options={[
                    { value: 'RULE_WINS', label: '规则优先' },
                    { value: 'CANDIDATE_WINS', label: '模型候选优先' },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="defaultTarget"
            label="兜底目标"
            extra="「无命中处置 = 走兜底目标」时必填；它仍要经过运行期准入"
          >
            <Input placeholder="如 fallback-agent" style={{ maxWidth: 360 }} />
          </Form.Item>
        </Card>

        <Row gutter={16} style={{ marginTop: 16 }}>
          <Col xs={24} xl={16}>
            <Space direction="vertical" size={16} style={{ width: '100%' }}>
              <Card title="② 输入字段（词汇表）" size="small">
                <SchemaEditor
                  value={visual.fields}
                  onChange={(fields) => setVisual((prev) => ({ ...prev, fields }))}
                />
              </Card>

              <Card title="③ 路由规则" size="small">
                <RuleEditor
                  value={visual.rules}
                  onChange={(rules) => setVisual((prev) => ({ ...prev, rules }))}
                  fields={flatFields}
                  knownTargets={knownTargets}
                />
              </Card>

              <Card title="④ 发布期回放样本" size="small">
                <SampleEditor
                  value={visual.samples}
                  onChange={(samples) => setVisual((prev) => ({ ...prev, samples }))}
                  fields={flatFields}
                  knownTargets={knownTargets}
                />
              </Card>

              <Card title="⑤ 试跑探针" size="small">
                <ProbeEditor
                  value={visual.probes}
                  onChange={(probes) => setVisual((prev) => ({ ...prev, probes }))}
                  fields={flatFields}
                />
              </Card>

              <Card title="⑥ 校验并提交登记申请" size="small">
                <Paragraph type="secondary">
                  右侧会实时显示结构校验与最终 JSON。试跑只是<b>看结论</b>；
                  登记策略仍要走三步工作流，「我批了」与「配置真的变了」必须可区分。
                </Paragraph>
                <Form.Item
                  name="reason"
                  label="变更理由（将留痕）"
                  rules={[{ required: true, message: '变更理由不能为空（将留痕）' }]}
                >
                  <Input.TextArea rows={2} placeholder="为何要登记/修改这份策略" />
                </Form.Item>
                <Divider style={{ margin: '8px 0 16px' }} />
                <Space wrap>
                  <Button
                    icon={<ExperimentOutlined />}
                    loading={running}
                    onClick={() => void runDry()}
                  >
                    试跑（不提交）
                  </Button>
                  <Button
                    type="primary"
                    icon={<SendOutlined />}
                    loading={submitting}
                    onClick={() => void submit()}
                  >
                    提交登记申请
                  </Button>
                </Space>
              </Card>
            </Space>
          </Col>

          <Col xs={24} xl={8}>
            <PreviewPanel draft={currentDraft} />
          </Col>
        </Row>
      </Form>

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
