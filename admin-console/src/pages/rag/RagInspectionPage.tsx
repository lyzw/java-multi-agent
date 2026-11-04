/**
 * RAG 语料管理 · 抽检队列（RAG-D13 §3.3.2 / RAG-D14 §4.7）
 *
 * <p><b>这一页做什么</b>：对已入库片段做人工抽检，判定系统给出的角色是否可信。
 *
 * <p><b>⚠️ 抽检是 VERIFIED 的前置守卫</b>（后端 `rag_source.inspection_status`）：
 * 抽检未通过 → 来源签不出 VERIFIED。所以本页不是"锦上添花的质检"，
 * 而是治理链路上的必经关卡。
 *
 * <p><b>视觉稿的两个核心区</b>：
 * <ol>
 *   <li><b>疑似清单</b>：后端 `SuspicionRules` 算出的疑点（HIGH = 可能泄题），
 *       按严重度排序——人只需要复核这些，不必逐条翻几千个片段；</li>
 *   <li><b>判定区</b>：对每条给出 PASS / FAIL，FAIL 必须填错误类型与期望角色
 *       （这正是"人工纠正"的知识沉淀成规则改进的依据）。</li>
 * </ol>
 *
 * <p><b>与"召回解释"的关系</b>：抽检看的是"这条判得对不对"，
 * 召回解释看的是"这条为什么被召回"——两者在视觉稿里是不同页面，
 * 因为动作不同（前者改判定，后者诊断检索）。本页只做前者。
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Empty,
  Input,
  List,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'

import {
  ApiError,
  listRagInspectionSuspects,
  listRagInspections,
  submitRagInspection,
} from '@/api/ragClient'
import type {
  RagInspectionErrorType,
  RagInspectionItem,
  RagInspectionRecord,
  RagInspectionSuspect,
} from '@/api/ragTypes'
import { RAG_INSPECTION_ERROR_TYPE_LABELS, RAG_SUSPICION_SEVERITY_ORDER } from '@/api/ragTypes'
import { RagNotReady, RagScopeShell, useRagScope } from './RagScopeShell'
import { ChunkRoleTag, RagEmpty, chunkRoleGroup, chunkRoleLabel } from './ragVisuals'

const { Text, Paragraph } = Typography

/** 错误类型选项（与后端 `RagInspectionErrorType` 一致）。 */
const ERROR_TYPE_OPTIONS = (
  Object.keys(RAG_INSPECTION_ERROR_TYPE_LABELS) as RagInspectionErrorType[]
).map((k) => ({ value: k, label: `${k} · ${RAG_INSPECTION_ERROR_TYPE_LABELS[k]}` }))

/** 人工可指定的正确角色。 */
const EXPECTED_ROLE_OPTIONS = [
  'EXERCISE_ANSWER',
  'EXAMPLE_SOLUTION',
  'EXERCISE_HINT',
  'EXERCISE',
  'EXAMPLE',
  'EXPLANATION',
  'APPENDIX',
  'BOILERPLATE',
].map((v) => ({ value: v, label: `${chunkRoleLabel(v)} (${v})` }))

/** 单条待判定项。 */
interface VerdictDraft {
  verdict: 'PASS' | 'FAIL'
  expectedRole: string
  errorType: RagInspectionErrorType | null
}

export function RagInspectionPage(): JSX.Element {
  const { message } = App.useApp()
  const { target, ready, scopeKey } = useRagScope()

  const [sourceId, setSourceId] = useState('')
  const [suspects, setSuspects] = useState<RagInspectionSuspect[]>([])
  const [history, setHistory] = useState<RagInspectionRecord[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /** chunkId → 草稿判定。 */
  const [drafts, setDrafts] = useState<Record<string, VerdictDraft>>({})
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    setSourceId('')
    setSuspects([])
    setHistory([])
    setDrafts({})
    setError(null)
  }, [scopeKey])

  const load = useCallback(
    async (sid: string) => {
      if (!sid) return
      setLoading(true)
      setError(null)
      try {
        const [s, h] = await Promise.all([
          listRagInspectionSuspects(target, sid, 50),
          listRagInspections(target, sid, 50),
        ])
        setSuspects(s)
        setHistory(h)
        // 默认全部 PASS（抽检的默认假设是"系统判对了"，人只挑出错的）。
        const d: Record<string, VerdictDraft> = {}
        for (const item of s) {
          d[item.chunkId] = {
            verdict: 'PASS',
            expectedRole: item.role,
            errorType: null,
          }
        }
        setDrafts(d)
      } catch (cause) {
        setError(cause instanceof ApiError ? cause.message : '读取抽检数据失败')
        setSuspects([])
        setHistory([])
      } finally {
        setLoading(false)
      }
    },
    [target],
  )

  useEffect(() => {
    if (sourceId && ready) void load(sourceId)
  }, [sourceId, ready, load])

  /** 按严重度排序（HIGH 在前 = 可能泄题优先复核）。 */
  const sorted = useMemo(() => {
    return [...suspects].sort(
      (a, b) =>
        (RAG_SUSPICION_SEVERITY_ORDER[a.severity] ?? 9) -
        (RAG_SUSPICION_SEVERITY_ORDER[b.severity] ?? 9),
    )
  }, [suspects])

  const failCount = useMemo(
    () => Object.values(drafts).filter((d) => d.verdict === 'FAIL').length,
    [drafts],
  )

  const setDraft = (chunkId: string, patch: Partial<VerdictDraft>): void => {
    setDrafts((prev) => {
      // ⚠️ noUncheckedIndexedAccess 下 prev[chunkId] 可能为 undefined：
      //    缺省补一个 PASS 草稿，而不是把 undefined 展开进对象（会产生非法状态）。
      const base: VerdictDraft =
        prev[chunkId] ?? { verdict: 'PASS', expectedRole: '', errorType: null }
      return { ...prev, [chunkId]: { ...base, ...patch } }
    })
  }

  const onSubmit = async (): Promise<void> => {
    if (!sourceId) return
    const items: RagInspectionItem[] = sorted
      .map((s) => ({ suspect: s, draft: drafts[s.chunkId] }))
      .filter((x): x is { suspect: RagInspectionSuspect; draft: VerdictDraft } =>
        x.draft !== undefined)
      .map(({ suspect: s, draft: d }) => {
        return {
          chunkId: s.chunkId,
          actualRole: s.role,
          expectedRole: d.verdict === 'PASS' ? s.role : d.expectedRole,
          errorType: d.verdict === 'FAIL' ? d.errorType : null,
          verdict: d.verdict,
        }
      })

    if (items.length === 0) {
      message.warning('没有可提交的抽检项')
      return
    }
    // FAIL 必须带错误类型（后端会 400）
    const bad = items.find((i) => i.verdict === 'FAIL' && !i.errorType)
    if (bad) {
      message.error('判定为 FAIL 的项必须选择错误类型')
      return
    }

    setSubmitting(true)
    try {
      const result = await submitRagInspection(
        target,
        sourceId,
        items,
        null,
      )
      message.success(
        `抽检已提交：${result.total} 条，${result.passed ? '全部通过' : '存在不通过项'}`,
      )
      await load(sourceId)
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '提交抽检失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <RagScopeShell
      domain="审核"
      title="抽检队列"
      description="对已入库片段做人工抽检，判定系统给出的角色是否可信。抽检未通过则来源签不出 VERIFIED——这是治理链路的必经关卡。"
    >
      {!ready ? (
        <RagNotReady />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Card size="small">
            <Space wrap align="center">
              <Text>来源 ID</Text>
              <Input
                style={{ width: 380 }}
                placeholder="粘贴来源 UUID"
                value={sourceId}
                onChange={(e) => setSourceId(e.target.value.trim())}
              />
              <Button onClick={() => void load(sourceId)} disabled={!sourceId}>
                加载抽检清单
              </Button>
              {sorted.length > 0 && (
                <Space>
                  <Text type="secondary">共 {sorted.length} 条疑点</Text>
                  {failCount > 0 && <Tag color="red">判定 FAIL {failCount} 条</Tag>}
                  <Button
                    type="primary"
                    loading={submitting}
                    onClick={() => void onSubmit()}
                  >
                    提交抽检结论
                  </Button>
                </Space>
              )}
            </Space>
          </Card>

          {error && <Alert type="error" showIcon message={error} />}

          {loading ? (
            <Card size="small">
              <Spin />
            </Card>
          ) : !sourceId ? (
            <Card size="small">
              <RagEmpty text="请填写来源 ID 以加载疑似清单" />
            </Card>
          ) : sorted.length === 0 ? (
            <Card size="small">
              <Empty description="没有抽检疑点：该来源的片段角色判定未触发任何规则" />
            </Card>
          ) : (
            <Row gutter={16}>
              <Col span={16}>
                <Card size="small" title="疑似清单（按严重度排序）">
                  <List
                    size="small"
                    dataSource={sorted}
                    pagination={{ pageSize: 8, size: 'small' }}
                    renderItem={(s) => {
                      const d = drafts[s.chunkId]
                      const isHigh = s.severity === 'HIGH'
                      return (
                        <List.Item
                          style={{
                            borderLeft: isHigh ? '4px solid #cf1322' : undefined,
                            background: isHigh ? '#fff1f0' : undefined,
                            paddingLeft: 12,
                            alignItems: 'flex-start',
                          }}
                        >
                          <Space direction="vertical" size={6} style={{ width: '100%' }}>
                            <Space wrap>
                              <ChunkRoleTag role={s.role} showRisk />
                              <Tag color={isHigh ? 'red' : s.severity === 'MEDIUM' ? 'orange' : 'default'}>
                                {s.severity}
                              </Tag>
                              <Text type="secondary" style={{ fontSize: 12 }}>
                                第 {s.pageNumber ?? '—'} 页
                                {s.exerciseNo ? ` · ${s.exerciseNo}` : ''}
                              </Text>
                            </Space>
                            <Text style={{ fontSize: 13 }}>{s.text}</Text>
                            <Text type="warning" style={{ fontSize: 12 }}>
                              疑点：{s.reasonText}
                            </Text>

                            <Space wrap>
                              <Select
                                size="small"
                                value={d?.verdict ?? 'PASS'}
                                style={{ width: 90 }}
                                onChange={(v: 'PASS' | 'FAIL') =>
                                  setDraft(s.chunkId, {
                                    verdict: v,
                                    errorType: v === 'PASS' ? null : d?.errorType ?? null,
                                  })
                                }
                                options={[
                                  { value: 'PASS', label: '通过' },
                                  { value: 'FAIL', label: '不通过' },
                                ]}
                              />
                              {d?.verdict === 'FAIL' && (
                                <>
                                  <Select
                                    size="small"
                                    style={{ width: 220 }}
                                    placeholder="应为角色"
                                    value={d.expectedRole === s.role ? undefined : d.expectedRole}
                                    onChange={(v: string) =>
                                      setDraft(s.chunkId, { expectedRole: v })
                                    }
                                    options={EXPECTED_ROLE_OPTIONS}
                                  />
                                  <Select
                                    size="small"
                                    style={{ width: 220 }}
                                    placeholder="错误类型（必选）"
                                    value={d.errorType ?? undefined}
                                    onChange={(v: RagInspectionErrorType) =>
                                      setDraft(s.chunkId, { errorType: v })
                                    }
                                    options={ERROR_TYPE_OPTIONS}
                                  />
                                </>
                              )}
                            </Space>
                          </Space>
                        </List.Item>
                      )
                    }}
                  />
                </Card>
              </Col>

              <Col span={8}>
                <Card size="small" title="抽检历史">
                  {history.length === 0 ? (
                    <RagEmpty text="暂无抽检记录" />
                  ) : (
                    <Table<RagInspectionRecord>
                      size="small"
                      rowKey="recordId"
                      pagination={{ pageSize: 6, size: 'small' }}
                      dataSource={history}
                      columns={[
                        {
                          title: '判定',
                          dataIndex: 'verdict',
                          width: 80,
                          render: (v: string) => (
                            <Tag color={v === 'PASS' ? 'green' : 'red'}>{v}</Tag>
                          ),
                        },
                        {
                          title: '角色（实→期）',
                          key: 'roles',
                          render: (_, r) => (
                            <Text style={{ fontSize: 12 }}>
                              {chunkRoleLabel(r.actualRole)} → {chunkRoleLabel(r.expectedRole)}
                            </Text>
                          ),
                        },
                        {
                          title: '时间',
                          dataIndex: 'createdAt',
                          width: 90,
                          render: (v: string) => (
                            <Text type="secondary" style={{ fontSize: 11 }}>
                              {String(v).slice(5, 16)}
                            </Text>
                          ),
                        },
                      ]}
                    />
                  )}
                </Card>

                <Card size="small" title="判定口径" style={{ marginTop: 12 }}>
                  <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
                    · <b>通过</b>：系统角色与人工判断一致
                    <br />· <b>不通过</b>：须给出"应为角色"与错误类型
                    <br />· 错误类型会沉淀为切分规则改进的依据
                    <br />· 答案类（红）在讲解模式会被硬过滤；误判为"讲解"则直接泄题
                  </Paragraph>
                  {sorted.some((s) => chunkRoleGroup(s.role) === 'answer') && (
                    <Alert
                      type="warning"
                      showIcon
                      style={{ marginTop: 8 }}
                      message={`本次疑点含 ${
                        sorted.filter((s) => chunkRoleGroup(s.role) === 'answer').length
                      } 条答案类片段`}
                    />
                  )}
                </Card>
              </Col>
            </Row>
          )}
        </Space>
      )}
    </RagScopeShell>
  )
}
