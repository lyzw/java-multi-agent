/**
 * 人工抽检台（V1.0.2 §5 R2 / 设计 §8.5 抽检面板 / 方案 §5）。
 *
 * <p><b>职责</b>：选定来源的某份载体 → 逐条片段标注（判定 / 错误类型 / 应判角色）
 * → 提交后由后端聚合结论并回写 `rag_source.inspection_status`，
 * 该状态是 VERIFIED 迁移的前置守卫（{@link InspectionGuard}）。
 *
 * <p><b>⚠️ 三条交互纪律</b>：
 * <ol>
 *   <li><b>提交前必须至少标注一条</b>：空抽检提交会被后端 400 拒绝，
 *       前端提前拦住，避免管理员撞墙；</li>
 *   <li><b>答案区漏判（ANSWER_LEAK）要显眼</b>：它是单点否决项，
 *       选中即提示「本册将判为不合格」；</li>
 *   <li><b>FAIL 必选错误类型 / PASS 不得带类型</b>：这是后端领域对象的不变式，
 *       构造请求时即遵守，否则必然 400。</li>
 * </ol>
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Empty,
  Segmented,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  listRagChunks,
  listRagDocuments,
  listRagInspections,
  listRagInspectionSuspects,
  submitRagInspection,
  ApiError,
  isRagTargetReady,
} from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import type {
  RagChunk,
  RagDocument,
  RagInspectionErrorType,
  RagInspectionItem,
  RagInspectionRecord,
  RagInspectionStatus,
  RagInspectionSuspect,
  RagInspectionVerdictResult,
  RagSource,
} from '@/api/ragTypes'
import {
  RAG_CHUNK_ROLE_LABELS,
  RAG_INSPECTION_ERROR_TYPE_LABELS,
  RAG_INSPECTION_STATUS_LABELS,
} from '@/api/ragTypes'
import { currentPrincipalId } from './ragUi'

const { Text, Paragraph } = Typography

/** 错误类型的中文说明（下拉与列表共用）。 */
const ERROR_TYPE_LABEL: Record<RagInspectionErrorType, string> = RAG_INSPECTION_ERROR_TYPE_LABELS

/** 全部角色选项（应判角色下拉用）。key 来自 DB 的 role::text，类型不可知，故用 Record。 */
const ROLE_OPTIONS = Object.entries(RAG_CHUNK_ROLE_LABELS).map(([value, label]) => ({
  value,
  label,
}))

interface Draft {
  verdict: 'PASS' | 'FAIL'
  /** FAIL 时的正确角色（应判角色）；PASS 时忽略 */
  expectedRole: string
  /** FAIL 时的错误类型 */
  errorType: RagInspectionErrorType | ''
}

export interface RagInspectionPanelProps {
  target: RagTarget
  source: RagSource
  /**
   * 已标注条数变化回调（供抽屉判断「关闭会不会丢草稿」）。
   * ⚠️ 关抽屉丢弃未提交标注没有任何恢复手段，抽屉必须据此弹确认（2026-11-03）。
   */
  onDraftsChange?: (markedCount: number) => void
}

/** 答案类角色：抽检口径里最关键、也最容易被正文淹没的片段（RAG-D04 §6）。 */
const ANSWER_ROLES = ['EXAMPLE_SOLUTION', 'EXERCISE_ANSWER']

export function RagInspectionPanel({ target, source, onDraftsChange }: RagInspectionPanelProps): JSX.Element {
  const sourceId = source.sourceId

  const [docs, setDocs] = useState<RagDocument[]>([])
  const [activeDocId, setActiveDocId] = useState<string | null>(null)
  const [chunks, setChunks] = useState<RagChunk[]>([])
  /**
   * 角色筛选（SQL 端生效，LIMIT 作用于筛选后）。
   * ⚠️ 没有它，答案片段会被正文淹没——本册 1,589 条里前 200 条全是
   * EXPLANATION，抽检员靠翻页永远够不到 26 条答案/解答片段（实测踩坑）。
   */
  const [roleFilter, setRoleFilter] = useState<'ALL' | 'ANSWER' | string>('ALL')
  /**
   * 视图模式（交互文档 §4.6「看红灯」）。
   *
   * ⚠️ 默认是 `SUSPECTS`（只看疑点）而不是 `ALL`：
   *    当前默认「全部」等于要求人翻 159 页裁决 1463 条，
   *    而抽检真正要找的是「哪几条可能错了」。默认值决定了绝大多数人的实际路径。
   *
   * ⚠️ 但 `ALL` 入口必须保留且显眼：预筛漏掉的片段仍需人工核对。
   *    把「只看疑点」做成唯一视图会造成「没被标出=没问题」的错觉。
   */
  const [viewMode, setViewMode] = useState<'SUSPECTS' | 'ALL'>('SUSPECTS')
  const [suspects, setSuspects] = useState<RagInspectionSuspect[]>([])
  const [loadingSuspects, setLoadingSuspects] = useState(false)
  const [drafts, setDrafts] = useState<Record<string, Draft>>({})
  const [records, setRecords] = useState<RagInspectionRecord[]>([])
  const [result, setResult] = useState<RagInspectionVerdictResult | null>(null)
  /** 本会话内提交产生的权威状态（后端已按同一规则回写 rag_source.inspection_status）。
   *  来源行 props 在抽检后不会自动刷新，用它覆盖「props 里已过期的权威值」。 */
  const [submittedStatus, setSubmittedStatus] = useState<RagInspectionStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loadingDocs, setLoadingDocs] = useState(false)
  const [loadingChunks, setLoadingChunks] = useState(false)

  /**
   * 加载疑点预筛结果。
   *
   * ⚠️ 失败**不阻断**抽检：预筛只是加速手段，退回「查看全部」仍能完成抽检。
   *    把它做成致命错误会让一次接口抖动挡死整条治理链路。
   */
  const loadSuspects = useCallback(async (): Promise<void> => {
    if (!isRagTargetReady(target)) return
    setLoadingSuspects(true)
    try {
      const list = await listRagInspectionSuspects(target, sourceId, 100)
      setSuspects(list)
    } catch {
      // 静默降级：界面提示「预筛不可用，请用『查看全部』」
      setSuspects([])
    } finally {
      setLoadingSuspects(false)
    }
  }, [target, sourceId])

  const loadRecords = useCallback(async (): Promise<void> => {
    if (!isRagTargetReady(target)) return
    try {
      const rs = await listRagInspections(target, sourceId, 200)
      setRecords(rs)
    } catch (cause) {
      // 历史读取失败不阻断主流程；首次抽检本就无记录。
      if (cause instanceof ApiError && cause.status !== 404) {
        setError(cause.message)
      }
    }
  }, [target, sourceId])

  const loadDocs = useCallback(async (): Promise<void> => {
    if (!isRagTargetReady(target)) return
    setLoadingDocs(true)
    try {
      const list = await listRagDocuments(target, sourceId)
      setDocs(list)
      const firstDoc = list[0]
      if (list.length > 0 && firstDoc && !activeDocId) {
        setActiveDocId(firstDoc.documentId)
      }
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '读取载体失败')
    } finally {
      setLoadingDocs(false)
    }
  }, [target, sourceId, activeDocId])

  const loadChunks = useCallback(
    async (docId: string, roles?: string[]): Promise<void> => {
      setLoadingChunks(true)
      setChunks([])
      try {
        const list = await listRagChunks(target, docId, 200, undefined, roles)
        setChunks(list)
      } catch (cause) {
        setError(cause instanceof ApiError ? cause.message : '读取片段失败')
      } finally {
        setLoadingChunks(false)
      }
    },
    [target],
  )

  useEffect(() => {
    void loadDocs()
    void loadRecords()
    void loadSuspects()
    // 来源切换时清空草稿、上次结论与会话内提交状态
    setDrafts({})
    setResult(null)
    setSubmittedStatus(null)
    setError(null)
    // 换来源时回到「看红灯」视图，并清掉上一册的疑点
    setViewMode('SUSPECTS')
    setSuspects([])
  }, [loadDocs, loadRecords, loadSuspects, sourceId])

  useEffect(() => {
    if (activeDocId) {
      // 角色筛选在 SQL 端生效：答案类片段不再被正文淹没（2026-11-03 修复）。
      void loadChunks(
        activeDocId,
        roleFilter === 'ALL'
          ? undefined
          : roleFilter === 'ANSWER'
            ? ANSWER_ROLES
            : [roleFilter],
      )
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeDocId, roleFilter])

  const setDraft = (chunkId: string, patch: Partial<Draft>): void => {
    setDrafts((prev) => {
      const current: Draft = prev[chunkId] ?? { verdict: 'PASS', expectedRole: '', errorType: '' }
      return { ...prev, [chunkId]: { ...current, ...patch } }
    })
  }

  const marked = Object.entries(drafts)

  // 向抽屉上报「未提交标注数」：抽屉据此在关闭时弹确认，防止 Esc/误点丢草稿。
  useEffect(() => {
    onDraftsChange?.(marked.length)
  }, [onDraftsChange, marked.length])

  /** 存在 ANSWER_LEAK 判定时给出显式提醒（单点否决）。 */
  const hasAnswerLeak = marked.some(
    ([, d]) => d.verdict === 'FAIL' && d.errorType === 'ANSWER_LEAK',
  )

  /** 存在 FAIL 却未选错误类型 → 阻止提交（后端必然 400）。 */
  const hasIncompleteFail = marked.some(
    ([, d]) => d.verdict === 'FAIL' && !d.errorType,
  )

  /** 当前抽检状态（优先级：本会话提交结果 > 来源行权威字段 > 历史记录推导）。
   *  ⚠️ source.inspectionStatus 为 null/缺省表示「本次响应未读取」，
   *  不是任何一种结论——此时才回退为按最近一次抽检记录近似。 */
  const derivedStatus: RagInspectionStatus = useMemo(() => {
    if (submittedStatus) return submittedStatus
    if (source.inspectionStatus) return source.inspectionStatus
    if (records.length === 0) return 'NOT_REQUIRED'
    const latest = records.reduce((a, b) => (b.createdAt > a.createdAt ? b : a))
    return latest.verdict === 'PASS' ? 'PASSED' : 'FAILED'
  }, [submittedStatus, source.inspectionStatus, records])

  /** 状态展示是否为「权威值」（决定说明文案，避免误导排查方向）。 */
  const statusIsAuthoritative = submittedStatus !== null || source.inspectionStatus != null

  const onSubmit = useCallback(async (): Promise<void> => {
    if (marked.length === 0) {
      setError('请至少标注一个片段后再提交（空抽检不得提交结论）')
      return
    }
    if (hasIncompleteFail) {
      setError('存在判定为错误的片段但未选择错误类型，请补齐后再提交')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const items: RagInspectionItem[] = marked.map(([chunkId, d]) => {
        const chunk = chunks.find((c) => c.chunkId === chunkId)
        const actualRole = chunk?.role ?? ''
        if (d.verdict === 'PASS') {
          return {
            chunkId,
            expectedRole: actualRole,
            actualRole,
            errorType: null,
            verdict: 'PASS',
          }
        }
        return {
          chunkId,
          expectedRole: d.expectedRole || actualRole,
          actualRole,
          errorType: d.errorType as RagInspectionErrorType,
          verdict: 'FAIL',
        }
      })
      const r = await submitRagInspection(target, sourceId, items, currentPrincipalId())
      setResult(r)
      // 后端已按同一聚合规则回写 rag_source.inspection_status：
      // 本会话内以提交结果为权威（来源行 props 在列表刷新前是旧的）。
      setSubmittedStatus(r.passed ? 'PASSED' : 'FAILED')
      await loadRecords()
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '提交抽检失败')
    } finally {
      setBusy(false)
    }
  }, [marked, hasIncompleteFail, chunks, target, sourceId, loadRecords])

  const chunkColumns: ColumnsType<RagChunk> = [
    {
      title: '当前角色',
      dataIndex: 'role',
      width: 140,
      render: (r: string) => <Tag>{RAG_CHUNK_ROLE_LABELS[r] ?? r}</Tag>,
    },
    {
      title: '定位（页码 · 章节 · 题号）',
      key: 'loc',
      ellipsis: true,
      render: (_: unknown, row) => (
        <span title={`${row.sectionPath} · p${row.pageNumber}`}>
          p{row.pageNumber} · {row.sectionPath}
          {row.exerciseNo ? ` · 题 ${row.exerciseNo}` : ''}
          {row.hasEmbedding ? '' : ' · ⚠️缺向量'}
        </span>
      ),
    },
    {
      title: '应判为',
      key: 'expected',
      width: 150,
      render: (_: unknown, row) => {
        const d = drafts[row.chunkId]
        if (!d || d.verdict !== 'FAIL') {
          return <Text type="secondary">—</Text>
        }
        return (
          <Select
            size="small"
            style={{ width: 130 }}
            placeholder="选择正确角色"
            value={d.expectedRole || undefined}
            onChange={(v) => setDraft(row.chunkId, { expectedRole: v })}
            options={ROLE_OPTIONS}
          />
        )
      },
    },
    {
      title: '判定',
      key: 'verdict',
      width: 110,
      render: (_: unknown, row) => {
        const d = drafts[row.chunkId]
        return (
          <Select
            size="small"
            style={{ width: 96 }}
            value={d?.verdict ?? ''}
            onChange={(v) =>
              setDraft(row.chunkId, {
                verdict: (v || 'PASS') as 'PASS' | 'FAIL',
                // 切回 PASS 时清空错误类型
                errorType: v === 'PASS' ? '' : (d?.errorType ?? ''),
              })
            }
            options={[
              { value: '', label: '未判' },
              { value: 'PASS', label: '正确' },
              { value: 'FAIL', label: '错误' },
            ]}
          />
        )
      },
    },
    {
      title: '错误类型',
      key: 'errorType',
      width: 170,
      render: (_: unknown, row) => {
        const d = drafts[row.chunkId]
        if (!d || d.verdict !== 'FAIL') {
          return <Text type="secondary">—</Text>
        }
        return (
          <Select
            size="small"
            style={{ width: 160 }}
            placeholder="必选"
            value={d.errorType || undefined}
            onChange={(v) =>
              setDraft(row.chunkId, { errorType: v as RagInspectionErrorType | '' })
            }
            options={(Object.keys(ERROR_TYPE_LABEL) as RagInspectionErrorType[]).map((k) => ({
              value: k,
              label: ERROR_TYPE_LABEL[k],
            }))}
          />
        )
      },
    },
  ]

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="人工抽检是签发 VERIFIED 的硬前置"
        description={
          <span>
            口径（D04 §6）：答案区识别准确率 ≥95% 且<b>漏判=0 单点否决</b>；题号保留 100%。
            未通过（{RAG_INSPECTION_STATUS_LABELS.FAILED}）或从未抽检（
            {RAG_INSPECTION_STATUS_LABELS.NOT_REQUIRED}）的来源<b>不得签发 VERIFIED</b>。
          </span>
        }
      />

      <Space wrap align="center">
        <Text>当前抽检状态：</Text>
        <Tag
          color={
            derivedStatus === 'PASSED' ? 'green' : derivedStatus === 'FAILED' ? 'red' : 'default'
          }
        >
          {RAG_INSPECTION_STATUS_LABELS[derivedStatus]}
        </Tag>
        <Text type="secondary">
          {statusIsAuthoritative
            ? '（来源行 inspection_status 权威值；闸门由后端在校验流转时执行）'
            : '（权威值未随来源返回，以最近一次抽检结论近似；闸门由后端执行）'}
        </Text>
      </Space>

      {hasAnswerLeak ? (
        <Alert
          type="error"
          showIcon
          message="已标注答案区漏判：本册将判为不合格"
          description="漏判即泄题，单点否决。请确认这些片段确为答案/解答内容。"
        />
      ) : null}
      {error ? <Alert type="error" showIcon message={error} /> : null}

      {result ? (
        <Alert
          type={result.passed ? 'success' : 'error'}
          showIcon
          message={`抽检${result.passed ? '通过' : '不通过'} — 共 ${result.total} 条，错 ${result.failed} 条，漏判 ${result.answerLeaks} 条，准确率 ${(result.accuracy * 100).toFixed(1)}%`}
          description={result.reason ? `原因：${result.reason}` : undefined}
        />
      ) : null}

      <Space wrap align="center">
        <Text>选择载体：</Text>
        {loadingDocs ? (
          <Spin size="small" />
        ) : (
          <Select
            style={{ width: 320 }}
            placeholder="选择要抽检的载体"
            value={activeDocId ?? undefined}
            onChange={(v) => setActiveDocId(v)}
            options={docs.map((d) => ({
              value: d.documentId,
              label: `${d.title}（${d.carrier} · ${d.chunkCount} 片段）`,
            }))}
          />
        )}
        <Text>角色：</Text>
        <Select
          style={{ width: 200 }}
          value={roleFilter}
          onChange={(v) => setRoleFilter(v)}
          options={[
            { value: 'ALL', label: '全部角色' },
            {
              value: 'ANSWER',
              label: '仅答案类（抽检重点）',
            },
            ...Object.entries(RAG_CHUNK_ROLE_LABELS).map(([value, label]) => ({
              value,
              label: `仅 ${label}`,
            })),
          ]}
        />
      </Space>

      {docs.length === 0 && !loadingDocs ? (
        <Empty description="该来源还没有文档载体，请先上传" />
      ) : null}

      {/*
        ── 看红灯（交互文档 §4.6）────────────────────────────────
        ⚠️ 默认视图：系统预筛出的疑点，按严重度排序（HIGH 在前 = 可能泄题）。
        ⚠️ 「查看全部」入口必须保留：预筛漏掉的片段仍需人工核对，
           不能造成「没被标出 = 没问题」的错觉。
      */}
      <Space wrap align="center">
        <Text>抽检视图：</Text>
        <Segmented
          value={viewMode}
          onChange={(v) => setViewMode(v as 'SUSPECTS' | 'ALL')}
          options={[
            {
              label: `只看疑点${suspects.length > 0 ? `（${suspects.length}）` : ''}`,
              value: 'SUSPECTS',
            },
            { label: '查看全部片段', value: 'ALL' },
          ]}
        />
        {viewMode === 'SUSPECTS' && loadingSuspects ? <Spin size="small" /> : null}
        {viewMode === 'SUSPECTS' && !loadingSuspects ? (
          <Button size="small" onClick={() => void loadSuspects()}>
            重新预筛
          </Button>
        ) : null}
      </Space>

      {viewMode === 'SUSPECTS' ? (
        <div>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="系统已按已知高风险模式挑出疑点，请重点确认这几条"
            description={
              <span>
                「疑似答案区被判成正文」是<b>单点否决</b>项——漏判即泄题。
                预筛只是建议，<b>仍有片段可能未被标出</b>，必要时请切到「查看全部片段」核对。
                共 {suspects.length} 条疑点。
              </span>
            }
          />
          {suspects.length === 0 ? (
            <Empty description="没有发现疑点。这通常是个好消息，但保险起见请点「查看全部片段」抽查几条。" />
          ) : (
            <Space direction="vertical" size="small" style={{ width: '100%' }}>
              {suspects.map((sp) => (
                <Card
                  key={`${sp.chunkId}-${sp.reason}`}
                  size="small"
                  title={
                    <Space wrap size={6}>
                      <Tag color={sp.severity === 'HIGH' ? 'red' : sp.severity === 'MEDIUM' ? 'orange' : 'default'}>
                        {sp.severity === 'HIGH' ? '高' : sp.severity === 'MEDIUM' ? '中' : '低'}
                      </Tag>
                      <Text strong>{sp.reasonText}</Text>
                      <Tag>{RAG_CHUNK_ROLE_LABELS[sp.role] ?? sp.role}</Tag>
                    </Space>
                  }
                  extra={
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      p{sp.pageNumber ?? '?'} · {sp.sectionPath ?? '—'}
                      {sp.exerciseNo ? ` · 题 ${sp.exerciseNo}` : ''}
                    </Text>
                  }
                >
                  {/*
                    ⚠️ 必须展示**正文**：判「是不是答案」必须看到字。
                        只给角色与页码的摘要行，人无法做判断（这是旧抽检台的核心缺陷）。
                  */}
                  <pre
                    style={{
                      margin: 0,
                      padding: 8,
                      background: 'rgba(0,0,0,0.03)',
                      borderRadius: 4,
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-all',
                      fontSize: 12,
                      maxHeight: 160,
                      overflow: 'auto',
                    }}
                  >
                    {sp.text || '（空片段）'}
                  </pre>
                </Card>
              ))}
            </Space>
          )}
        </div>
      ) : (
        <>
      {loadingChunks ? (
        <Spin />
      ) : chunks.length > 0 ? (
        <Table<RagChunk>
          rowKey="chunkId"
          size="small"
          title={() =>
            roleFilter === 'ANSWER'
              ? `片段抽检（${chunks.length} 条，标注 ${marked.length} 条）· 仅答案类`
              : `片段抽检（${chunks.length} 条，标注 ${marked.length} 条）`
          }
          columns={chunkColumns}
          dataSource={chunks}
          pagination={{ pageSize: 10 }}
        />
      ) : null}
        </>
      )}



      <Space>
        <Button
          type="primary"
          loading={busy}
          disabled={busy || marked.length === 0 || hasIncompleteFail}
          onClick={() => void onSubmit()}
        >
          提交抽检（已标注 {marked.length} 条）
        </Button>
        {hasIncompleteFail ? (
          <Text type="danger">存在「错误」未选错误类型，无法提交</Text>
        ) : null}
      </Space>

      <div>
        <Paragraph strong>历史抽检记录（{records.length} 条）</Paragraph>
        {records.length === 0 ? (
          <Text type="secondary">暂无抽检记录。</Text>
        ) : (
          <ul style={{ paddingLeft: 18, margin: 0 }}>
            {records.map((r) => (
              <li key={r.recordId}>
                <Tag color={r.verdict === 'PASS' ? 'green' : 'red'}>
                  {r.verdict === 'PASS' ? '正确' : '错误'}
                </Tag>
                {r.errorType ? `（${ERROR_TYPE_LABEL[r.errorType] ?? r.errorType}）` : ''}
                {' '}· 实际角色 {RAG_CHUNK_ROLE_LABELS[r.actualRole] ?? r.actualRole}
                {' '}· {r.createdAt}
              </li>
            ))}
          </ul>
        )}
      </div>
    </Space>
  )
}
