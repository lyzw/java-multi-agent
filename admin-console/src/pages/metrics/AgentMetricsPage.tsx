import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Progress,
  Row,
  Segmented,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd'
import { ApiError } from '@/api/client'
import {
  fetchAgentMetricOverview,
  fetchAnomalies,
  fetchLatency,
  fetchTerminationDistribution,
  fetchToolBreakdown,
  fetchTraceMetric,
  collectTraceMetric,
  ANOMALY_KIND_LABELS,
  TERMINATION_REASON_COLORS,
  TERMINATION_REASON_LABELS,
  TRACE_ID_SOURCE_LABELS,
  type AgentMetricOverview,
  type AnomalyTrace,
  type LatencyPercentiles,
  type ReasonCount,
  type ToolMetric,
  type TraceMetric,
} from '@/api/metricsApi'

/**
 * Agent 效果指标看板（P7）。
 *
 * ⚠️ **三条不容妥协的呈现纪律**：
 *  1. **未采集 = `—（未采集）`，绝不显示 0**。把「没测过」渲染成 0，
 *     会让「幻觉率 0%」看起来像「没有幻觉」。
 *  2. **L1 指标强制并排显示覆盖率**、**L2 指标强制显示样本量 n**。
 *     只校验了 3% 的任务时，「成功率 99%」这句话本身就是误导。
 *  3. **链路成功 ≠ 任务成功**，两个分区物理隔离，绝不互换标签。
 *
 * ⚠️ **不引入图表库**（延续 P4/P5 零新依赖）：趋势与分布用条形占比呈现，
 *    Grafana 才是时序图的主场，这里只回答「现在是多少、该不该去看」。
 */

/** 可信度层级徽标。 */
type Tier = 'L0' | 'L1' | 'L2' | 'L3'

const TIER_HINTS: Record<Tier, { text: string; color: string; tip: string }> = {
  L0: { text: 'L0 系统事实', color: 'blue', tip: '执行链路直接落库，无解释空间 —— 高可信，优先看' },
  L1: { text: 'L1 自动校验', color: 'cyan', tip: '外部终态校验回执 —— 高可信，但必须同时看覆盖率' },
  L2: { text: 'L2 抽样标注', color: 'gold', tip: '黄金基线 —— 必须带样本量 n，n<30 不足以判断' },
  L3: { text: 'L3 有噪信号', color: 'orange', tip: '用户反馈 —— 只看趋势，不能单独当真值' },
}

function TierTag({ tier }: { tier: Tier }): JSX.Element {
  const h = TIER_HINTS[tier]
  return (
    <Tag color={h.color} title={h.tip} style={{ fontSize: 11, marginInlineEnd: 0 }}>
      {h.text}
    </Tag>
  )
}

/** 样本量下限：低于它一律显示「样本不足」，不显示百分比。 */
const MIN_SAMPLE_SIZE = 30

/** 未采集的统一呈现。 */
const NOT_COLLECTED = '—'

function pct(v: number | null): string {
  return v === null ? NOT_COLLECTED : `${(v * 100).toFixed(1)}%`
}

function ms(v: number | null): string {
  if (v === null) {
    return NOT_COLLECTED
  }
  return v >= 1000 ? `${(v / 1000).toFixed(2)} s` : `${Math.round(v)} ms`
}

function count(v: number | null): string {
  return v === null ? NOT_COLLECTED : v.toLocaleString('zh-CN')
}

/** 布尔类指标：`null` 是「未采集」，不能画成勾或叉。 */
function yn(v: boolean | null): string {
  if (v === null) {
    return NOT_COLLECTED
  }
  return v ? '是' : '否'
}

function text(v: string | null | undefined): string {
  return v === undefined || v === null || v === '' ? NOT_COLLECTED : v
}

function stamp(v: string | null): string {
  return v === null ? NOT_COLLECTED : new Date(v).toLocaleString('zh-CN')
}

export function AgentMetricsPage(): JSX.Element {
  const [hours, setHours] = useState<number>(24)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [overview, setOverview] = useState<AgentMetricOverview | null>(null)
  const [terminations, setTerminations] = useState<ReasonCount[]>([])
  const [latency, setLatency] = useState<LatencyPercentiles | null>(null)
  const [tools, setTools] = useState<ToolMetric[]>([])
  const [anomalies, setAnomalies] = useState<AnomalyTrace[]>([])

  /** 异常下钻：抽屉展示的 Trace ID（'' 表示关闭）。 */
  const [drillTraceId, setDrillTraceId] = useState<string>('')
  const [drill, setDrill] = useState<TraceMetric | null>(null)
  const [drillLoading, setDrillLoading] = useState(false)
  const [drillError, setDrillError] = useState<string | null>(null)

  /** 补算（POST /collect）的三态结果。 */
  const [collecting, setCollecting] = useState(false)
  const [collectResult, setCollectResult] = useState<{ kind: 'ok' | 'pending' | 'error'; message: string } | null>(null)

  useEffect(() => {
    if (drillTraceId === '') {
      setDrill(null)
      setDrillError(null)
      setCollectResult(null)
      return
    }
    let cancelled = false
    setDrillLoading(true)
    setDrillError(null)
    fetchTraceMetric(drillTraceId)
      .then((data) => {
        if (cancelled) return
        setDrill(data)
        setDrillLoading(false)
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        setDrill(null)
        setDrillError(cause instanceof ApiError ? cause.message : '读取 Trace 明细失败')
        setDrillLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [drillTraceId])

  const load = useCallback(async (): Promise<void> => {
    setLoading(true)
    setError(null)
    try {
      const [ov, tm, lt, tl, an] = await Promise.all([
        fetchAgentMetricOverview(hours),
        fetchTerminationDistribution(hours),
        fetchLatency(hours),
        fetchToolBreakdown(hours),
        fetchAnomalies(hours, 20),
      ])
      setOverview(ov)
      setTerminations(tm)
      setLatency(lt)
      setTools(tl)
      setAnomalies(an)
    } catch (e) {
      setError((e as Error).message || '指标加载失败')
    } finally {
      setLoading(false)
    }
  }, [hours])

  useEffect(() => {
    void load()
  }, [load])

  /**
   * 手动补算当前 Trace 所属 Run 的指标。
   *
   * ⚠️ 409（NOT_CONVERGED）与真正的失败分流呈现：前者是「此刻算不出」，
   *    提示等 Run 收敛后再补；写成「失败请重试」只会让人反复点。
   */
  const runCollect = useCallback(async (): Promise<void> => {
    if (drill === null) return
    setCollecting(true)
    setCollectResult(null)
    try {
      const res = await collectTraceMetric(drill.tenantId, drill.runId)
      setCollectResult({
        kind: res.status === 'COLLECTED' ? 'ok' : 'pending',
        message:
          res.status === 'COLLECTED'
            ? `已补算，traceId=${res.traceId ?? drill.traceId}`
            : '后端未返回可补算结果，请稍后再试',
      })
      if (res.status === 'COLLECTED') {
        setDrill(await fetchTraceMetric(drill.traceId))
      }
    } catch (cause) {
      setCollectResult({
        kind: cause instanceof ApiError && cause.status === 409 ? 'pending' : 'error',
        message:
          cause instanceof ApiError && cause.status === 409
            ? '该 Run 尚未收敛，此刻算不出指标——这不是服务故障，等它进入终态后再补算。'
            : cause instanceof ApiError
              ? cause.message
              : '补算请求未能发出',
      })
    } finally {
      setCollecting(false)
    }
  }, [drill])

  if (loading && overview === null) {
    return (
      <div style={{ padding: 48, textAlign: 'center' }}>
        <Spin size="large" tip="加载指标…">
          <div style={{ height: 120 }} />
        </Spin>
      </div>
    )
  }

  if (error !== null) {
    return (
      <Alert
        type="error"
        showIcon
        message="指标不可用"
        description={
          <span>
            {error}。⚠️ 这是「取不到数」，不是「Agent 健康」——不要因为看板空白就认为没问题。
          </span>
        }
        action={
          <Button size="small" onClick={() => void load()}>
            重试
          </Button>
        }
      />
    )
  }

  if (overview === null) {
    return <Empty description="暂无指标数据" />
  }

  const ov = overview
  const totalTerminations = terminations.reduce((s, r) => s + r.count, 0)
  const sampled = ov.sampledCount
  const sampleEnough = sampled !== null && sampled >= MIN_SAMPLE_SIZE

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title="Agent 效果指标"
        extra={
          <Space>
            <Segmented
              value={String(hours)}
              onChange={(v) => setHours(Number(v))}
              options={[
                { label: '近 24 小时', value: '24' },
                { label: '近 7 天', value: '168' },
                { label: '近 30 天', value: '720' },
              ]}
            />
            <Button size="small" onClick={() => void load()}>
              刷新
            </Button>
          </Space>
        }
      >
        <Alert
          type="info"
          showIcon
          message="读数前请先接受三条口径"
          description={
            <ul style={{ margin: 0, paddingInlineStart: 20 }}>
              <li>
                <b>链路成功率 ≠ 任务成功率</b>：前者只说明框架没崩、走完了终止逻辑，
                后者要由「自动校验 / 抽样标注」回答。
              </li>
              <li>
                <b>{NOT_COLLECTED}（未采集）不是 0</b>：显示「未采集」的指标表示当前没有采集来源，
                把它读成 0 会得到「幻觉率 0%」这种假象。
              </li>
              <li>
                <b>用户反馈是有噪信号</b>：差评率上涨的处置是<b>触发抽样复核</b>，
                不能直接判定 Agent 劣化——下游工具故障同样会导致差评。
              </li>
            </ul>
          }
          style={{ marginBottom: 8 }}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          数据时效 {new Date(ov.refreshedAt).toLocaleString('zh-CN')}
          （小时桶来自物化视图，刷新期间可能读到上一版）
        </Typography.Text>
      </Card>

      {/* ── 一、SLA & 可用性 ─────────────────────────────────── */}
      <Card
        size="small"
        title={
          <Space>
            一、SLA &amp; 可用性
            <TierTag tier="L0" />
          </Space>
        }
      >
        <Row gutter={[16, 16]}>
          <Col span={6}>
            <Statistic title="Trace 总数（已收敛）" value={ov.traceCount} />
          </Col>
          <Col span={6}>
            <Statistic
              title="链路成功率"
              value={pct(ov.linkSuccessRate)}
              // ⚠️ 与其它指标同规则：只有真的取到数且低于阈值才标红。
              //    原先无条件红，「—（未采集）」会被读成「成功率很难看」，
              //    与本文件第 1 条呈现纪律自相矛盾。
              valueStyle={{
                color:
                  ov.linkSuccessRate !== null && ov.linkSuccessRate < 0.95 ? '#cf1322' : undefined,
              }}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              ⚠️ 框架未崩溃 ≠ 任务成功
            </Typography.Text>
          </Col>
          <Col span={6}>
            <Statistic title="平均端到端耗时" value={ms(ov.avgDurationMs)} />
          </Col>
          <Col span={6}>
            <Space direction="vertical" size={0}>
              <Typography.Text type="secondary">端到端耗时分位</Typography.Text>
              <Typography.Text>
                P50 {ms(latency?.p50 ?? null)} · P95 {ms(latency?.p95 ?? null)} · P99{' '}
                {ms(latency?.p99 ?? null)}
              </Typography.Text>
            </Space>
          </Col>
        </Row>

        <Typography.Title level={5} style={{ marginTop: 16, fontSize: 13 }}>
          终止原因分布（根因分析核心）
        </Typography.Title>
        {terminations.length === 0 ? (
          <Empty description="区间内无终态 Trace" />
        ) : (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {terminations.map((r) => {
              const share = totalTerminations === 0 ? 0 : (r.count / totalTerminations) * 100
              return (
                <Row key={r.reason} align="middle" gutter={8}>
                  <Col span={6}>
                    <Tag color={TERMINATION_REASON_COLORS[r.reason]}>
                      {TERMINATION_REASON_LABELS[r.reason]}
                    </Tag>
                  </Col>
                  <Col span={14}>
                    <Progress
                      percent={Number(share.toFixed(1))}
                      size="small"
                      showInfo={false}
                      strokeColor={r.reason === 'COMPLETED' ? '#52c41a' : '#faad14'}
                    />
                  </Col>
                  <Col span={4}>
                    <Typography.Text>
                      {r.count}（{share.toFixed(1)}%）
                    </Typography.Text>
                  </Col>
                </Row>
              )
            })}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              合计 {totalTerminations} —— 与 Trace 总数一致；未识别原因一律归入「其他」，
              不会凭空消失。
            </Typography.Text>
          </Space>
        )}
      </Card>

      {/* ── 二、成本 ─────────────────────────────────────────── */}
      <Card
        size="small"
        title={
          <Space>
            二、成本指标
            <TierTag tier="L0" />
          </Space>
        }
      >
        <Row gutter={[16, 16]}>
          <Col span={6}>
            <Statistic title="总输入 token" value={count(ov.inputTokens)} />
          </Col>
          <Col span={6}>
            <Statistic title="总输出 token" value={count(ov.outputTokens)} />
          </Col>
          <Col span={6}>
            <Statistic
              title="总费用（已计量）"
              value={ov.costAmount === null ? NOT_COLLECTED : `¥${ov.costAmount.toFixed(2)}`}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              计量账 ≠ 结算账（预留/未对账不计入）
            </Typography.Text>
          </Col>
          <Col span={6}>
            <Statistic
              title="超大 token 占比"
              value={pct(ov.oversizedTokenRate)}
              valueStyle={{
                color: (ov.oversizedTokenRate ?? 0) > 0.01 ? '#cf1322' : undefined,
              }}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              &gt; 20k token；&gt; 1% 通常指向死循环
            </Typography.Text>
          </Col>
        </Row>
      </Card>

      {/* ── 三、Agent 过程行为 ────────────────────────────────── */}
      <Card
        size="small"
        title={
          <Space>
            三、Agent 过程行为
            <TierTag tier="L0" />
          </Space>
        }
      >
        <Row gutter={[16, 16]}>
          <Col span={6}>
            <Statistic title="平均工具轮次" value={ov.avgToolRounds === null ? NOT_COLLECTED : ov.avgToolRounds.toFixed(2)} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              来自 run_step 真实埋点；旧 Run 未采集显示「未采集」
            </Typography.Text>
          </Col>
          <Col span={6}>
            <Statistic
              title="最大轮次触达率"
              value={pct(ov.maxRoundsReachedRate)}
              valueStyle={{
                color: (ov.maxRoundsReachedRate ?? 0) > 0.1 ? '#cf1322' : undefined,
              }}
            />
          </Col>
          <Col span={6}>
            <Statistic title="无效调用率" value={pct(ov.invalidCallRate)} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              重复调用 + 幻觉工具 + 参数非法
            </Typography.Text>
          </Col>
          <Col span={6}>
            <Statistic
              title="疑似循环 Trace"
              value={ov.loopSuspectedTraces}
              valueStyle={{ color: ov.loopSuspectedTraces > 0 ? '#cf1322' : undefined }}
            />
          </Col>
        </Row>

        <Typography.Title level={5} style={{ marginTop: 16, fontSize: 13 }}>
          工具选择分布（⭕辅助信号：看是否过度依赖某个工具）
        </Typography.Title>
        <Table<ToolMetric>
          size="small"
          rowKey="toolName"
          pagination={false}
          dataSource={tools}
          locale={{ emptyText: '区间内无工具调用' }}
          columns={[
            { title: '工具', dataIndex: 'toolName' },
            { title: '调用次数', dataIndex: 'callCount', align: 'right' },
            { title: '失败次数', dataIndex: 'failedCount', align: 'right' },
            {
              title: '失败率',
              dataIndex: 'failRate',
              align: 'right',
              render: (v: number | null) => pct(v),
            },
            {
              title: '平均耗时',
              dataIndex: 'avgDurationMs',
              align: 'right',
              render: (v: number | null) => ms(v),
            },
            {
              title: '最大耗时',
              dataIndex: 'maxDurationMs',
              align: 'right',
              render: (v: number | null) => ms(v),
            },
          ]}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          ⚠️ 工具维度不提供 P95：分位数无法从计数与求和还原，用最大值冒充会把尾部延迟高估。
        </Typography.Text>
      </Card>

      {/* ── 四、业务效果 ─────────────────────────────────────── */}
      <Card
        size="small"
        title="四、业务效果指标（核心：到底能不能干活）"
        extra={
          <Space>
            <TierTag tier="L1" />
            <TierTag tier="L2" />
          </Space>
        }
      >
        <Row gutter={[16, 16]}>
          <Col span={8}>
            <Statistic
              title="自动校验成功率"
              value={pct(ov.autoVerifiedRate)}
              suffix={
                ov.autoCheckCoverage === null
                  ? undefined
                  : `（覆盖 ${(ov.autoCheckCoverage * 100).toFixed(1)}%）`
              }
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {ov.autoCheckedCount === null
                ? '未接入外部校验器 —— 这里是「没测过」，不是 0%'
                : `已校验 ${ov.autoCheckedCount} 条`}
            </Typography.Text>
          </Col>
          <Col span={8}>
            <Statistic
              title="抽样标注成功率（黄金基线）"
              value={sampleEnough ? pct(ov.sampledSuccessRate) : '样本不足'}
              suffix={sampled === null ? undefined : `（n=${sampled}）`}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {sampleEnough ? '' : `样本量需 ≥ ${MIN_SAMPLE_SIZE} 才足以判断`}
            </Typography.Text>
          </Col>
          <Col span={8}>
            <Statistic title="假成功占比" value={pct(ov.falseSuccessRate)} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              自动校验通过、人工复核判错 —— 需 L1 ∩ L2 同时有数
            </Typography.Text>
          </Col>
        </Row>
        <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
          <Col span={12}>
            <Statistic
              title="幻觉率"
              value={sampleEnough ? pct(ov.hallucinationRate) : '样本不足'}
              valueStyle={{
                color: sampleEnough && (ov.hallucinationRate ?? 0) > 0.05 ? '#cf1322' : undefined,
              }}
            />
          </Col>
          <Col span={12}>
            <Statistic
              title="指令遵从率（⭕辅助）"
              value={sampleEnough ? pct(ov.instructionFollowRate) : '样本不足'}
            />
          </Col>
        </Row>
      </Card>

      {/* ── 五、用户反馈 ─────────────────────────────────────── */}
      <Card
        size="small"
        title={
          <Space>
            五、用户反馈（有噪信号）
            <TierTag tier="L3" />
          </Space>
        }
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="只看趋势，不判因果"
          description="下游工具故障、网络抖动、UI 卡顿都会导致差评。差评率上涨 → 触发抽样复核，而不是直接判定 Agent 劣化。"
        />
        <Row gutter={[16, 16]}>
          <Col span={6}>
            <Statistic title="好评数" value={count(ov.feedbackUpCount)} />
          </Col>
          <Col span={6}>
            <Statistic title="差评数" value={count(ov.feedbackDownCount)} />
          </Col>
          <Col span={6}>
            <Statistic title="好评率" value={pct(ov.thumbUpRate)} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              分母为已表态会话，在途不计入
            </Typography.Text>
          </Col>
          <Col span={6}>
            <Statistic title="中途放弃率" value={pct(ov.abandonRate)} />
          </Col>
        </Row>
      </Card>

      {/* ── 六、安全 & 风险 ───────────────────────────────────── */}
      <Card
        size="small"
        title={
          <Space>
            六、安全 &amp; 风险
            <TierTag tier="L0" />
          </Space>
        }
      >
        <Row gutter={[16, 16]}>
          <Col span={8}>
            <Statistic title="安全拦截 Trace" value={ov.safetyBlockedTraces} />
          </Col>
          <Col span={8}>
            <Statistic
              title="越权调用尝试 Trace"
              value={ov.unauthorizedTraces}
              valueStyle={{ color: ov.unauthorizedTraces > 0 ? '#cf1322' : undefined }}
            />
          </Col>
          <Col span={8}>
            <Statistic
              title="敏感信息输出命中 Trace"
              value={ov.sensitiveOutputTraces}
              valueStyle={{ color: ov.sensitiveOutputTraces > 0 ? '#cf1322' : undefined }}
            />
          </Col>
        </Row>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          ⚠️ 合规红线：风险事件只落摘要与计数，绝不保存命中原文——存下来就是二次泄露。
        </Typography.Text>
      </Card>

      {/* ── 异常明细 ─────────────────────────────────────────── */}
      <Card size="small" title="异常 Trace Top-N（具象排查入口）">
        <Table<AnomalyTrace>
          size="small"
          rowKey="traceId"
          pagination={false}
          dataSource={anomalies}
          locale={{ emptyText: '区间内未发现异常 Trace' }}
          columns={[
            { title: 'Trace ID', dataIndex: 'traceId', ellipsis: true },
            {
              title: '异常类型',
              dataIndex: 'kind',
              render: (k: AnomalyTrace['kind']) => (
                <Tag color={k === 'OVERSIZED_TOKEN' ? 'red' : 'orange'}>
                  {ANOMALY_KIND_LABELS[k]}
                </Tag>
              ),
            },
            {
              title: '总 token',
              dataIndex: 'totalTokens',
              align: 'right',
              render: (v: number | null) => count(v),
            },
            {
              title: '工具轮次',
              dataIndex: 'toolRounds',
              align: 'right',
              render: (v: number | null) => count(v),
            },
            {
              title: '循环次数',
              dataIndex: 'loopCount',
              align: 'right',
              render: (v: number | null) => count(v),
            },
            {
              title: '开始时间',
              dataIndex: 'startedAt',
              render: (v: string) => new Date(v).toLocaleString('zh-CN'),
            },
            {
              title: '操作',
              key: 'actions',
              width: 72,
              render: (_: unknown, row) => (
                <Button size="small" onClick={() => setDrillTraceId(row.traceId)}>
                  明细
                </Button>
              ),
            },
          ]}
        />
      </Card>

      {/* ── 异常下钻：单条 Trace 明细（后端只有指标，没有对话正文）── */}
      <Drawer
        title={`Trace 明细 ${drillTraceId}`}
        width={720}
        open={drillTraceId !== ''}
        onClose={() => setDrillTraceId('')}
        destroyOnClose
      >
        {drillLoading && <Spin tip="加载明细…" />}
        {drillError !== null && (
          <Alert
            type="error"
            showIcon
            message="读取明细失败"
            description={`${drillError}。⚠️ 后端 404 表示这条 Trace 没有指标行——可以先补算。`}
            action={
              <Button size="small" onClick={() => setDrillTraceId('')}>
                关闭
              </Button>
            }
          />
        )}
        {drill !== null && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Alert
              type="info"
              showIcon
              message={`traceId 来源：${TRACE_ID_SOURCE_LABELS[drill.traceIdSource]}`}
              description={
                drill.traceIdSource === 'CORRELATION_FALLBACK'
                  ? '本行是按时间窗关联推断出来的，可能与真实 Run 不完全对应——据此定位根因前先核对 runId。'
                  : 'traceId 与 Run 直接对应，可放心作为排查入口。'
              }
            />
            <Descriptions
              size="small"
              column={2}
              bordered
              items={[
                { key: 't', label: 'Trace ID', children: drill.traceId, span: 2 },
                { key: 'r', label: 'Run ID', children: drill.runId, span: 2 },
                { key: 'tenant', label: '租户', children: drill.tenantId },
                { key: 'agent', label: 'Agent', children: `${text(drill.agentId)} v${drill.agentVersion ?? NOT_COLLECTED}` },
                { key: 'scenario', label: '场景', children: text(drill.scenario) },
                { key: 'model', label: '模型', children: text(drill.modelId) },
                { key: 'started', label: '开始', children: stamp(drill.startedAt) },
                { key: 'ended', label: '结束', children: stamp(drill.endedAt) },
                { key: 'dur', label: '耗时', children: ms(drill.durationMs) },
                {
                  key: 'term',
                  label: '终止原因',
                  children: (
                    <Tag color={TERMINATION_REASON_COLORS[drill.terminationReason]}>
                      {TERMINATION_REASON_LABELS[drill.terminationReason]}
                    </Tag>
                  ),
                },
                { key: 'state', label: '终态', children: drill.terminalState },
                { key: 'in', label: '输入 token', children: count(drill.inputTokens) },
                { key: 'out', label: '输出 token', children: count(drill.outputTokens) },
                {
                  key: 'cost',
                  label: '费用',
                  children:
                    drill.costAmount === null
                      ? NOT_COLLECTED
                      : `${drill.costAmount} ${text(drill.currency)}`,
                },
                { key: 'rounds', label: '工具轮次', children: count(drill.toolRounds) },
                { key: 'calls', label: '工具调用', children: count(drill.toolCallTotal) },
                { key: 'failed', label: '失败', children: count(drill.toolCallFailed) },
                { key: 'invalid', label: '参数非法', children: count(drill.toolCallInvalid) },
                { key: 'dup', label: '重复调用', children: count(drill.toolCallDuplicate) },
                { key: 'loop', label: '循环次数', children: count(drill.loopSuspectedCount) },
                { key: 'maxr', label: '触达轮次上限', children: yn(drill.maxRoundsReached) },
                { key: 'clar', label: '澄清次数', children: count(drill.clarificationCount) },
                { key: 'think', label: '思考耗时', children: ms(drill.thinkMsTotal) },
                { key: 'toolms', label: '工具耗时', children: ms(drill.toolMsTotal) },
                { key: 'ac', label: 'L1 已校验', children: yn(drill.autoChecked) },
                { key: 'av', label: 'L1 通过', children: yn(drill.autoVerified) },
                { key: 'sp', label: 'L2 已抽样', children: yn(drill.sampled) },
                { key: 'hv', label: '人工结论', children: text(drill.humanVerdict) },
                { key: 'hal', label: '幻觉', children: yn(drill.hallucination) },
                { key: 'if', label: '指令遵从', children: yn(drill.instructionFollowed) },
                { key: 'up', label: '好评', children: count(drill.feedbackUp) },
                { key: 'down', label: '差评', children: count(drill.feedbackDown) },
                { key: 'retry', label: '重试次数', children: count(drill.retryCount) },
                { key: 'aband', label: '中途放弃', children: yn(drill.abandoned) },
                { key: 'safe', label: '安全拦截', children: count(drill.safetyBlockedCount) },
                { key: 'unauth', label: '越权尝试', children: count(drill.unauthorizedAttemptCount) },
                { key: 'sens', label: '敏感命中', children: count(drill.sensitiveOutputHits) },
              ]}
            />
            <Space>
              <Button loading={collecting} onClick={() => void runCollect()}>
                补算指标
              </Button>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                运维入口：正常由 Run 收敛事件自动采集，这里只用于漏采回补。
              </Typography.Text>
            </Space>
            {collectResult !== null && (
              <Alert
                type={collectResult.kind === 'ok' ? 'success' : collectResult.kind === 'pending' ? 'warning' : 'error'}
                showIcon
                message={collectResult.message}
              />
            )}
          </Space>
        )}
      </Drawer>
    </Space>
  )
}
