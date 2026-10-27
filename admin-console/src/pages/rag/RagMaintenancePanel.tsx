/**
 * RAG 维护面板（问题清单 + 重跑索引 + 检索探针）。
 *
 * ⚠️ 治理与诚实纪律：
 *    · 「重跑索引」是**补写向量**的修复动作，不是「刷新页面」——
 *      它消耗真实算力，须显式点击并给出结果（扫描数 / 写入数 / 耗时）。
 *    · 检索探针是**验证治理结论是否落到检索侧**的手段（RAG-R01）。
 *      0 召回要如实呈现，并明确说明「治理可管理 ≠ 检索已强制」。
 *    · 问题清单给出「是哪几个载体坏了」，而不只是「坏了多少」——
 *      只给数字无法定位，等于把排查成本推给运维。
 */

import { useState } from 'react'
import {
  Alert,
  AutoComplete,
  Button,
  Card,
  Col,
  Empty,
  Input,
  InputNumber,
  List,
  Row,
  Select,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { getRagIssues, reindexRagCorpus, retrieveRagChunks, ApiError } from '@/api/ragClient'
import type {
  RagIssueDocument,
  RagIssueSource,
  RagMaintenanceIssues,
  RagReindexResult,
  RagRetrievalHit,
  RagRetrievalMode,
  RagScopeMode,
} from '@/api/ragTypes'
import { RAG_RETRIEVAL_MODE_LABELS, RAG_SCOPE_MODE_LABELS, RAG_PARSE_STATUS_LABELS } from '@/api/ragTypes'
import { useDictOptions } from '@/features/dictionaries/useDictionaries'
import { TrustTierTag, VerificationTag } from './ragUi'

const { Text, Paragraph } = Typography

/* ── 问题清单 ─────────────────────────────────────────── */

type IssuesState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ok'; data: RagMaintenanceIssues }
  | { kind: 'error'; message: string }

const docIssueColumns: ColumnsType<RagIssueDocument> = [
  { title: '标题', dataIndex: 'title', ellipsis: true },
  { title: '载体', dataIndex: 'carrier', width: 90 },
  {
    title: '解析状态',
    dataIndex: 'parseStatus',
    width: 110,
    render: (s: string) => (
      <Tag color={s === 'FAILED' ? 'red' : 'default'}>{RAG_PARSE_STATUS_LABELS[s] ?? s}</Tag>
    ),
  },
  { title: '片段数', dataIndex: 'chunkCount', width: 90 },
  {
    title: '缺向量',
    dataIndex: 'missingEmbeddingCount',
    width: 100,
    render: (c?: number) =>
      c === undefined ? '—' : <Tag color="red">{c}</Tag>,
  },
]

const srcIssueColumns: ColumnsType<RagIssueSource> = [
  { title: '标题', dataIndex: 'title', ellipsis: true },
  {
    title: '状态',
    dataIndex: 'verificationStatus',
    width: 110,
    render: (_: unknown, row) => <VerificationTag status={row.verificationStatus} />,
  },
  { title: '权威等级', dataIndex: 'trustTier', width: 170, render: (_: unknown, row) => <TrustTierTag tier={row.trustTier} /> },
  {
    title: '过期',
    dataIndex: 'stale',
    width: 90,
    render: (s: boolean) => (s ? <Tag color="orange">已过期</Tag> : <Tag>否</Tag>),
  },
]

/* ── 检索探针 ─────────────────────────────────────────── */

const hitColumns: ColumnsType<RagRetrievalHit> = [
  { title: '得分', dataIndex: 'score', width: 80, render: (s: number) => s.toFixed(3) },
  { title: '角色', dataIndex: 'role', width: 110 },
  { title: '版本', dataIndex: 'edition', width: 160, ellipsis: true },
  { title: '定位', dataIndex: 'locator', width: 150, ellipsis: true },
  { title: '内容', dataIndex: 'text', ellipsis: true },
]

export interface RagMaintenancePanelProps {
  tenantId: string
}

export function RagMaintenancePanel({ tenantId }: RagMaintenancePanelProps): JSX.Element {
  const [issues, setIssues] = useState<IssuesState>({ kind: 'idle' })
  const [reindex, setReindex] = useState<RagReindexResult | null>(null)
  const [reindexing, setReindexing] = useState(false)
  const [reindexError, setReindexError] = useState<string | null>(null)
  const [indexLimit, setIndexLimit] = useState(500)

  const [query, setQuery] = useState('')
  const [mode, setMode] = useState<RagRetrievalMode>('EXPLAIN')
  const [scopeMode, setScopeMode] = useState<RagScopeMode>('STRICT')
  // 版本坐标系四轴：建议值来自字典表，但保留自由输入
  // （检索侧对这些轴的取值没有枚举约束，硬改成下拉会把合法输入挡在门外）
  const { options: subjectOptions } = useDictOptions('rag.subject')
  const { options: gradeOptions } = useDictOptions('rag.grade')
  const { options: termOptions } = useDictOptions('rag.term')
  const { options: publisherOptions } = useDictOptions('rag.publisher')

  const [subject, setSubject] = useState('数学')
  const [grade, setGrade] = useState('七年级')
  const [term, setTerm] = useState('上册')
  const [publisher, setPublisher] = useState('人教版')
  const [revisionCode, setRevisionCode] = useState('2024')
  const [topK, setTopK] = useState(8)
  const [hits, setHits] = useState<RagRetrievalHit[] | null>(null)
  const [probing, setProbing] = useState(false)
  const [probeError, setProbeError] = useState<string | null>(null)
  const [probeMeta, setProbeMeta] = useState<{
    versionMismatchDetected: boolean
    answerContentSuppressed: boolean
    primaryEdition: string
  } | null>(null)

  const loadIssues = async (): Promise<void> => {
    setIssues({ kind: 'loading' })
    try {
      const data = await getRagIssues(tenantId)
      setIssues({ kind: 'ok', data })
    } catch (cause) {
      setIssues({ kind: 'error', message: cause instanceof ApiError ? cause.message : '读取失败' })
    }
  }

  const runReindex = async (): Promise<void> => {
    setReindexing(true)
    setReindexError(null)
    setReindex(null)
    try {
      const result = await reindexRagCorpus(tenantId, indexLimit)
      setReindex(result)
    } catch (cause) {
      setReindexError(cause instanceof ApiError ? cause.message : '重跑索引失败')
    } finally {
      setReindexing(false)
    }
  }

  const runProbe = async (): Promise<void> => {
    setProbing(true)
    setProbeError(null)
    setHits(null)
    setProbeMeta(null)
    try {
      const res = await retrieveRagChunks(tenantId, {
        query,
        mode,
        edition: { subject, grade, term, publisher, revisionCode },
        scopeMode,
        topK,
      })
      setHits(res.hits)
      setProbeMeta({
        versionMismatchDetected: res.versionMismatchDetected,
        answerContentSuppressed: res.answerContentSuppressed,
        primaryEdition: res.primaryEdition,
      })
    } catch (cause) {
      setProbeError(cause instanceof ApiError ? cause.message : '检索失败')
    } finally {
      setProbing(false)
    }
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        title="问题清单"
        extra={
          <Button onClick={() => void loadIssues()} loading={issues.kind === 'loading'}>
            加载问题清单
          </Button>
        }
      >
        {issues.kind === 'idle' && <Empty description="点击右上角加载，定位「哪里坏了」" />}
        {issues.kind === 'loading' && <Spin />}
        {issues.kind === 'error' && <Alert type="error" showIcon message={issues.message} />}
        {issues.kind === 'ok' && (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Row gutter={16}>
              <Col span={8}>
                <Statistic
                  title="缺向量的载体"
                  value={issues.data.totals.documentsMissingEmbedding}
                  valueStyle={{
                    color: issues.data.totals.documentsMissingEmbedding > 0 ? '#cf1322' : undefined,
                  }}
                />
              </Col>
              <Col span={8}>
                <Statistic
                  title="解析失败的载体"
                  value={issues.data.totals.documentsFailedParse}
                  valueStyle={{
                    color: issues.data.totals.documentsFailedParse > 0 ? '#cf1322' : undefined,
                  }}
                />
              </Col>
              <Col span={8}>
                <Statistic title="待审核来源" value={issues.data.totals.sourcesPendingReview} />
              </Col>
            </Row>

            <div>
              <Text strong>缺向量的载体</Text>
              <Table<RagIssueDocument>
                size="small"
                rowKey="documentId"
                style={{ marginTop: 8 }}
                pagination={false}
                locale={{ emptyText: '无' }}
                columns={docIssueColumns}
                dataSource={issues.data.documentsMissingEmbedding}
              />
            </div>
            <div>
              <Text strong>解析失败的载体</Text>
              <Table<RagIssueDocument>
                size="small"
                rowKey="documentId"
                style={{ marginTop: 8 }}
                pagination={false}
                locale={{ emptyText: '无' }}
                columns={docIssueColumns}
                dataSource={issues.data.documentsFailedParse}
              />
            </div>
            <div>
              <Text strong>待审核来源</Text>
              <Table<RagIssueSource>
                size="small"
                rowKey="sourceId"
                style={{ marginTop: 8 }}
                pagination={false}
                locale={{ emptyText: '无' }}
                columns={srcIssueColumns}
                dataSource={issues.data.sourcesPendingReview}
              />
            </div>
          </Space>
        )}
      </Card>

      <Card title="重跑索引">
        <Paragraph type="secondary" style={{ marginBottom: 12 }}>
          为「已切分但没有向量」的片段补写向量。这是<b>真实的算力消耗</b>，不是刷新页面；
          结果会给出扫描数、写入数与耗时。
        </Paragraph>
        <Space>
          <Text>单次上限</Text>
          <InputNumber min={1} max={2000} value={indexLimit} onChange={(v) => setIndexLimit(v ?? 500)} />
          <Button type="primary" loading={reindexing} onClick={() => void runReindex()}>
            重跑索引
          </Button>
        </Space>
        {reindexError && (
          <Alert style={{ marginTop: 12 }} type="error" showIcon message={reindexError} />
        )}
        {reindex && (
          <Row gutter={16} style={{ marginTop: 12 }}>
            <Col span={8}>
              <Statistic title="扫描片段" value={reindex.scannedChunks} />
            </Col>
            <Col span={8}>
              <Statistic title="写入向量" value={reindex.indexedChunks} valueStyle={{ color: '#3f8600' }} />
            </Col>
            <Col span={8}>
              <Statistic title="耗时（毫秒）" value={reindex.durationMs} />
            </Col>
          </Row>
        )}
      </Card>

      <Card title="检索探针">
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="用途：验证「治理结论」是否真的落到检索侧"
          description="已知限制 RAG-R01：检索路径目前尚未强制准入闸门。若这里 0 召回而治理侧显示可检索，属实——请以探针为准，不要以治理状态冒充检索行为。"
        />
        <Space wrap style={{ marginBottom: 12 }}>
          <Select
            style={{ width: 110 }}
            value={mode}
            onChange={setMode}
            options={Object.entries(RAG_RETRIEVAL_MODE_LABELS).map(([v, l]) => ({ value: v, label: l }))}
          />
          <Select
            style={{ width: 220 }}
            value={scopeMode}
            onChange={setScopeMode}
            options={Object.entries(RAG_SCOPE_MODE_LABELS).map(([v, l]) => ({ value: v, label: l }))}
          />
          <AutoComplete
            style={{ width: 110 }}
            value={subject}
            onChange={setSubject}
            options={subjectOptions}
            placeholder="学科"
          />
          <AutoComplete
            style={{ width: 110 }}
            value={grade}
            onChange={setGrade}
            options={gradeOptions}
            placeholder="年级"
          />
          <AutoComplete
            style={{ width: 100 }}
            value={term}
            onChange={setTerm}
            options={termOptions}
            placeholder="学期"
          />
          <AutoComplete
            style={{ width: 120 }}
            value={publisher}
            onChange={setPublisher}
            options={publisherOptions}
            placeholder="出版社"
          />
          <Input
            style={{ width: 110 }}
            value={revisionCode}
            onChange={(e) => setRevisionCode(e.target.value)}
            placeholder="版次"
          />
          <InputNumber min={1} max={50} value={topK} onChange={(v) => setTopK(v ?? 8)} />
        </Space>
        <Space style={{ display: 'flex', marginBottom: 12 }}>
          <Input.TextArea
            rows={2}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="检索问题，如：一元一次方程移项要注意什么"
          />
        </Space>
        <Button type="primary" loading={probing} onClick={() => void runProbe()}>
          执行检索
        </Button>

        {probeError && (
          <Alert style={{ marginTop: 12 }} type="error" showIcon message={probeError} />
        )}

        {hits !== null && (
          <div style={{ marginTop: 12 }}>
            {hits.length === 0 ? (
              <Alert
                type="warning"
                showIcon
                message="0 召回"
                description="可能是该版本下确实没有语料、语料尚未切分/补向量，或来源未通过校验。请回到「问题清单」与「统计概览」定位。"
              />
            ) : (
              <>
                {probeMeta?.versionMismatchDetected && (
                  <Alert
                    style={{ marginBottom: 8 }}
                    type="warning"
                    showIcon
                    message="检测到版本不一致"
                    description={`主版本：${probeMeta.primaryEdition}`}
                  />
                )}
                {probeMeta?.answerContentSuppressed && (
                  <Alert
                    style={{ marginBottom: 8 }}
                    type="warning"
                    showIcon
                    message="答案区内容已被抑制"
                    description="按防泄题策略，习题答案类片段不返回正文。"
                  />
                )}
                <Table<RagRetrievalHit>
                  size="small"
                  rowKey="chunkId"
                  columns={hitColumns}
                  dataSource={hits}
                  pagination={false}
                  expandable={{
                    expandedRowRender: (row) => (
                      <Paragraph style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{row.text}</Paragraph>
                    ),
                  }}
                />
              </>
            )}
          </div>
        )}

        {hits !== null && hits.length > 0 && (
          <List
            size="small"
            style={{ marginTop: 8 }}
            header="召回原因（可溯源）"
            dataSource={hits}
            renderItem={(h) => (
              <List.Item>
                <Text type="secondary">
                  {h.channel} · {h.reason}
                </Text>
              </List.Item>
            )}
          />
        )}
      </Card>
    </Space>
  )
}
