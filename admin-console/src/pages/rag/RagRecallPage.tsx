/**
 * RAG 语料管理 · 召回解释（质检）（RAG-D13 §3.3.3 / RAG-D14 §4.8）
 *
 * <p><b>这一页做什么</b>：模拟一次检索，验证"某条查询召回什么、为什么、是否触发门禁"。
 * 它是读面 `POST /api/v1/retrieval` 的**管理端调试视图**——改造前这个能力只存在于
 * `frontend/`（学生端），管理台无法自查"为什么学生搜不到"。
 *
 * <p><b>靶点 C 的承载（单一分数量纲）</b>：每条命中只展示**一个 `relevance`**，
 * 配 0.38 阈值刻度条。后端 `HitDto` 已经刻意不外泄 `fusionScore`（RRF 排序分），
 * 本页遵守同一口径——把两个量纲同时画出来会让用户以为"有两套分"。
 *
 * <p><b>靶点 D 的承载（过滤可诊断）</b>：视觉稿要求「过滤前 → 版本过滤 → 角色过滤 → 门禁」
 * 的漏斗计数。后端 `RetrievalResult.funnel` 已携带五段计数与主因归因（RAG-D17），
 * 本页直接呈现——**归因由后端算**（前端比较三个差值必然与后端漂移）。
 * 旧版后端不返回 funnel 时显示说明而非编造数字。
 *
 * <p><b>靶点 E 的承载（返回三态）</b>：把 `hits` 为空 + `answerContentSuppressed`
 * 两个信号收敛成 `GROUNDED / INSUFFICIENT / SUPPRESSED` 三态，
 * 取代"空数组 + 布尔"的模糊语义。
 */

import { useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'

import { ApiError, retrieveRagChunks } from '@/api/ragClient'
import type { RagRetrievalHit, RagRetrievalMode, RagRetrievalResponse, RagScopeMode } from '@/api/ragTypes'
import { RagNotReady, RagScopeShell, type RagScopePageProps } from './RagScopeShell'
import {
  ChunkRoleTag,
  RAG_SCORE_THRESHOLD,
  RETRIEVAL_MODE_OPTIONS,
  RecallFunnelSummary,
  RelevanceMeter,
  SCOPE_MODE_OPTIONS,
  recallChannelLabel,
  recallReasonLabel,
} from './ragVisuals'

const { Text, Paragraph } = Typography

/** 返回三态（靶点 E：取代空数组+布尔的模糊语义）。 */
type AnswerStatus = 'GROUNDED' | 'INSUFFICIENT' | 'SUPPRESSED'

const STATUS_META: Record<AnswerStatus, { label: string; color: string; desc: string }> = {
  GROUNDED: {
    label: 'GROUNDED · 有依据',
    color: 'green',
    desc: '命中达到分数门禁，可作为生成本文的依据。',
  },
  INSUFFICIENT: {
    label: 'INSUFFICIENT · 依据不足',
    color: 'orange',
    desc: '未在已授权语料中找到达到门禁的依据。这是**合法输出**，不是故障。',
  },
  SUPPRESSED: {
    label: 'SUPPRESSED · 已按策略排除',
    color: 'red',
    desc: '答案类内容已按当前讲解策略排除，未进入上下文（防泄题）。',
  },
}

interface FormValues {
  query: string
  mode: RagRetrievalMode
  subject: string
  grade: string
  term: string
  publisher: string
  revisionCode: string
  scopeMode: RagScopeMode
  topK: number
}

/** 版本五轴可选值（须与后端 `EditionCoordinate` 展示名严格一致，否则 400）。 */
const SUBJECT_OPTIONS = ['语文', '数学', '英语', '物理', '化学', '生物', '历史', '地理'].map(
  (v) => ({ value: v, label: v }),
)
const GRADE_OPTIONS = [
  '一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '七年级', '八年级', '九年级',
].map((v) => ({ value: v, label: v }))
const TERM_OPTIONS = ['上册', '下册', '全一册'].map((v) => ({ value: v, label: v }))
const PUBLISHER_OPTIONS = ['人教版', '沪教版', '北师大版', '苏教版', '外研版', '译林版'].map(
  (v) => ({ value: v, label: v }),
)

export function RagRecallPage(): JSX.Element {
  return (
    <RagScopeShell
      domain="审核"
      title="召回解释（质检）"
      description="模拟一次检索，验证「召回什么、为什么、是否触发门禁」。这是读面的管理端调试视图——学生搜不到时，先在这里自查。"
    >
      {(scope) => <RagRecallBody scope={scope} />}
    </RagScopeShell>
  )
}

/** 页面主体：处在 {@link RagScopeShell} 的 provider 之内，故可直接消费作用域。 */
function RagRecallBody({ scope }: RagScopePageProps): JSX.Element {
  const { target, ready } = scope
  const [form] = Form.useForm<FormValues>()

  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<RagRetrievalResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const onRun = async (v: FormValues): Promise<void> => {
    setLoading(true)
    setError(null)
    setResult(null)
    const started = performance.now()
    try {
      const res = await retrieveRagChunks(target, {
        query: v.query,
        mode: v.mode,
        edition: {
          subject: v.subject,
          grade: v.grade,
          term: v.term,
          publisher: v.publisher,
          revisionCode: v.revisionCode,
        },
        scopeMode: v.scopeMode,
        topK: v.topK,
      })
      setResult(res)
      setElapsedMs(Math.round(performance.now() - started))
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : '检索失败（RAG 检索端点需租户档 + 有效令牌）',
      )
    } finally {
      setLoading(false)
    }
  }

  /** 把后端信号收敛为三态。 */
  const status: AnswerStatus | null = useMemo(() => {
    if (!result) return null
    if (result.hits.length > 0) return 'GROUNDED'
    if (result.answerContentSuppressed) return 'SUPPRESSED'
    return 'INSUFFICIENT'
  }, [result])

  /** 命中按 relevance 降序（后端已排序，这里再保证一次口径）。 */
  const hits: RagRetrievalHit[] = useMemo(() => {
    if (!result) return []
    return [...result.hits].sort((a, b) => b.score - a.score)
  }, [result])

  return (
    <>
      {!ready ? (
        <RagNotReady />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Card size="small" title="① 查询条件（student_context + question）">
            <Form<FormValues>
              form={form}
              layout="vertical"
              onFinish={(v) => void onRun(v)}
              initialValues={{
                mode: 'EXPLAIN',
                subject: '数学',
                grade: '七年级',
                term: '上册',
                publisher: '人教版',
                revisionCode: '2024修订',
                scopeMode: 'STRICT',
                topK: 10,
              }}
            >
              <Row gutter={12}>
                <Col span={14}>
                  <Form.Item
                    name="query"
                    label="问题 question"
                    rules={[{ required: true, message: '请填写查询' }]}
                  >
                    <Input placeholder="例如：二次函数的顶点怎么求" />
                  </Form.Item>
                </Col>
                <Col span={5}>
                  <Form.Item
                    name="mode"
                    label="意图 intent"
                    rules={[{ required: true }]}
                  >
                    <Select options={RETRIEVAL_MODE_OPTIONS} />
                  </Form.Item>
                </Col>
                <Col span={5}>
                  <Form.Item name="topK" label="topK">
                    <InputNumber min={1} max={50} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
              </Row>

              <Row gutter={12}>
                <Col span={5}>
                  <Form.Item name="subject" label="学科" rules={[{ required: true }]}>
                    <Select options={SUBJECT_OPTIONS} />
                  </Form.Item>
                </Col>
                <Col span={5}>
                  <Form.Item name="grade" label="年级" rules={[{ required: true }]}>
                    <Select options={GRADE_OPTIONS} />
                  </Form.Item>
                </Col>
                <Col span={4}>
                  <Form.Item name="term" label="册次" rules={[{ required: true }]}>
                    <Select options={TERM_OPTIONS} />
                  </Form.Item>
                </Col>
                <Col span={5}>
                  <Form.Item name="publisher" label="出版社" rules={[{ required: true }]}>
                    <Select options={PUBLISHER_OPTIONS} />
                  </Form.Item>
                </Col>
                <Col span={5}>
                  <Form.Item
                    name="revisionCode"
                    label="版次"
                    rules={[{ required: true, message: '版次必填' }]}
                  >
                    <Input placeholder="2024修订" />
                  </Form.Item>
                </Col>
              </Row>

              <Row gutter={12} align="bottom">
                <Col span={8}>
                  <Form.Item name="scopeMode" label="版本范围 Mode">
                    <Select options={SCOPE_MODE_OPTIONS} />
                  </Form.Item>
                </Col>
                <Col span={16}>
                  <Form.Item>
                    <Button type="primary" htmlType="submit" loading={loading}>
                      执行检索
                    </Button>
                    {elapsedMs != null && (
                      <Text type="secondary" style={{ marginLeft: 12 }}>
                        耗时 {elapsedMs} ms
                      </Text>
                    )}
                  </Form.Item>
                </Col>
              </Row>
            </Form>
          </Card>

          {error && <Alert type="error" showIcon message="检索失败" description={error} />}

          {loading && (
            <Card size="small">
              <Spin />
            </Card>
          )}

          {result && status && (
            <>
              <Card size="small" title="② 检索摘要">
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Space wrap size={16}>
                    <Tag color={STATUS_META[status].color}>{STATUS_META[status].label}</Tag>
                    <Text type="secondary">命中 {hits.length} 条</Text>
                    {result.versionMismatchDetected && (
                      <Tag color="orange">版本不一致（含非首选版本依据）</Tag>
                    )}
                    {result.answerContentSuppressed && (
                      <Tag color="red">答案类内容已抑制</Tag>
                    )}
                    {result.primaryEdition && (
                      <Tag>{`首选版本 ${result.primaryEdition}`}</Tag>
                    )}
                  </Space>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {STATUS_META[status].desc}
                  </Text>
                  {result.funnel ? (
                    <div>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        过滤漏斗（每层剩余条数）与主因归因：
                      </Text>
                      <div style={{ marginTop: 6 }}>
                        <RecallFunnelSummary funnel={result.funnel} />
                      </div>
                    </div>
                  ) : (
                    <Alert
                      type="info"
                      showIcon
                      message="过滤漏斗计数未返回"
                      description="当前后端版本未随响应返回漏斗字段。升级后端后本区自动补齐——此处不编造数字。"
                    />
                  )}
                </Space>
              </Card>

              <Card size="small" title={`③ 命中片段（${hits.length}）`}>
                {hits.length === 0 ? (
                  <div style={{ padding: '24px 0', textAlign: 'center' }}>
                    <Empty
                      description={
                        status === 'SUPPRESSED'
                          ? '部分依据属于答案类内容，已按当前讲解策略排除（未进入上下文）'
                          : '未在已授权语料中找到达到门禁的依据'
                      }
                    />
                  </div>
                ) : (
                  <Space direction="vertical" size={10} style={{ width: '100%' }}>
                    {hits.map((h) => (
                      <Card key={h.chunkId} size="small" type="inner">
                        <Space direction="vertical" size={6} style={{ width: '100%' }}>
                          <Space wrap>
                            <ChunkRoleTag role={h.role} showRisk />
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {h.locator}
                            </Text>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              · {h.edition}
                            </Text>
                          </Space>
                          <Text style={{ fontSize: 13 }}>{h.text}</Text>
                          <Row gutter={12} align="middle">
                            <Col span={10}>
                              <RelevanceMeter
                                score={h.score}
                                // ⚠️ 优先用服务端下发的真实阈值：阈值可配置，
                                //    写死 0.38 会让刻度线与真实门禁不是同一个数。
                                threshold={result.minScore ?? RAG_SCORE_THRESHOLD}
                              />
                            </Col>
                            <Col span={14}>
                              <Space wrap size={6}>
                                <Tag>通道 {recallChannelLabel(h.channel)}</Tag>
                                <Tag>原因 {recallReasonLabel(h.reason)}</Tag>
                              </Space>
                            </Col>
                          </Row>
                        </Space>
                      </Card>
                    ))}
                  </Space>
                )}
              </Card>

              <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
                ⚠️ 本页只展示一个 <Text code>relevance</Text>（0–1），不展示 RRF 融合排序分——
                两者量纲不同，同时呈现会让人误以为"有两套分"（RAG-D12 靶点 C）。
              </Paragraph>
            </>
          )}
        </Space>
      )}
    </>
  )
}
