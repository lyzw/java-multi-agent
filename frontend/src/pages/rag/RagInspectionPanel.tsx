/**
 * 抽检台（V1.0.2 S3-4 / 设计 §8.4 `RagInspectionPanel` / 方案 §5 R2）。
 *
 * <p><b>职责</b>：按来源展示片段 → 判定人逐条标注（期望角色 / 实际角色 / 错误类型）
 * → 提交后由后端聚合出结论并回写 `inspection_status`。
 *
 * <p><b>⚠️ 三条交互纪律</b>：
 * <ol>
 *   <li><b>提交前必须至少标注一条</b>：空抽检提交等于治理空转，后端会 400，
 *       前端应提前拦住而不让用户撞墙；</li>
 *   <li><b>答案区漏判要显眼</b>：`ANSWER_LEAK` 是单点否决项，
 *       选中它时应给出明确提示「本册将判为不合格」，而不是与其它错误类型同等对待；</li>
 *   <li><b>FAIL 必选错误类型 / PASS 不得带类型</b>：这是后端领域对象的不变式，
 *       前端在提交时构造请求就必须遵守，否则必然 400。</li>
 * </ol>
 */

import { useCallback, useEffect, useState } from 'react'

import { listRagChunks, listRagInspections, submitRagInspection } from '@/api/client'
import type {
  RagChunk,
  RagInspectionErrorType,
  RagInspectionItem,
  RagInspectionRecord,
  RagInspectionVerdictResult,
} from '@/api/types'

/** 错误类型的中文说明（下拉与列表共用）。 */
const ERROR_TYPE_LABEL: Record<RagInspectionErrorType, string> = {
  ANSWER_LEAK: '答案区漏判（单点否决）',
  EXERCISE_NO_LOST: '题号丢失/错位',
  LOCATOR_WRONG: '页码或章节坐标错误',
  CHUNK_BOUNDARY: '切分边界错误',
  OTHER: '其他错误',
}

/**
 * 记录行的色阶类名。
 *
 * ⚠️ 写成完整字面量而非内联三元：前端 CSS 完整性守卫测试静态扫描源码，
 * 内联的 `x === 'FAIL' ? 'tone-error' : 'tone-ok'` 会把 'FAIL' 也当成类名扫描，
 * 从而误报「类名未定义」。
 */
function recordTone(verdict: 'PASS' | 'FAIL'): string {
  return verdict === 'FAIL' ? 'tone-error' : 'tone-ok'
}

/** 单条的本地判定草稿。 */
interface Draft {
  verdict: 'PASS' | 'FAIL'
  errorType: RagInspectionErrorType | ''
}

export interface RagInspectionPanelProps {
  tenantId: string
  sourceId: string
  documentId: string
}

export function RagInspectionPanel({ tenantId, sourceId, documentId }: RagInspectionPanelProps) {
  const [chunks, setChunks] = useState<RagChunk[]>([])
  const [drafts, setDrafts] = useState<Record<string, Draft>>({})
  const [records, setRecords] = useState<RagInspectionRecord[]>([])
  const [result, setResult] = useState<RagInspectionVerdictResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!tenantId || !sourceId) {
      return
    }
    try {
      const [cs, rs] = await Promise.all([
        listRagChunks(tenantId, documentId, 100),
        listRagInspections(tenantId, sourceId, 200),
      ])
      setChunks(cs)
      setRecords(rs)
      // ⚠️ 默认全部标 PASS 是**危险**的：判的人会顺手提交而没真看。
      //    故初始不预填，用户必须逐条点选；未标注的条目不计入提交。
      setDrafts({})
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载抽检数据失败')
    }
  }, [tenantId, sourceId, documentId])

  useEffect(() => {
    void load()
  }, [load])

  const setDraft = (chunkId: string, patch: Partial<Draft>) => {
    setDrafts((prev) => {
      const current: Draft = prev[chunkId] ?? { verdict: 'PASS', errorType: '' }
      return { ...prev, [chunkId]: { ...current, ...patch } }
    })
  }

  const marked = Object.entries(drafts)

  /** 存在 ANSWER_LEAK 判定时给出显式提醒。 */
  const hasAnswerLeak = marked.some(
    ([, d]) => d.verdict === 'FAIL' && d.errorType === 'ANSWER_LEAK',
  )

  /** 存在 FAIL 却未选错误类型 → 阻止提交（后端必然 400）。 */
  const hasIncompleteFail = marked.some(
    ([, d]) => d.verdict === 'FAIL' && !d.errorType,
  )

  const onSubmit = useCallback(async () => {
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
        return {
          chunkId,
          // ⚠️ 期望/实际角色取自片段现状：判定人只需回答「对不对、错在哪类」，
          //    不必手工誊写角色名——手抄正是最容易抄错的一步。
          expectedRole: d.verdict === 'PASS' ? (chunk?.role ?? '') : (chunk?.role ?? ''),
          actualRole: chunk?.role ?? '',
          errorType: d.verdict === 'FAIL' ? (d.errorType as RagInspectionErrorType) : null,
          verdict: d.verdict,
        }
      })
      const r = await submitRagInspection(tenantId, sourceId, items)
      setResult(r)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交抽检失败')
    } finally {
      setBusy(false)
    }
  }, [marked, hasIncompleteFail, chunks, tenantId, sourceId, load])

  if (!tenantId) {
    return <p className="rag-ingest-hint">请先选择租户。</p>
  }

  return (
    <div className="rag-inspection">
      <h3>人工抽检</h3>
      <p className="rag-ingest-hint">
        口径（D04 §6）：答案区识别 ≥95% 且<b>漏判=0 单点否决</b>；题号保留 100%。
        未通过抽检的来源<b>不得签发 VERIFIED</b>。
      </p>

      {hasAnswerLeak ? (
        <p className="rag-ingest-error">
          已标记录答案区漏判：本册将判为<b>不合格</b>（漏判即泄题，单点否决）。
        </p>
      ) : null}
      {error ? <p className="rag-ingest-error">{error}</p> : null}

      {result ? (
        <div className={result.passed ? 'rag-inspection-result-ok' : 'rag-ingest-error'}>
          <b>{result.passed ? '抽检通过' : '抽检不通过'}</b>
          {' '}— 共 {result.total} 条，错 {result.failed} 条，漏判 {result.answerLeaks} 条，
          准确率 {(result.accuracy * 100).toFixed(1)}%
          {result.reason ? `（${result.reason}）` : ''}
        </div>
      ) : null}

      <table className="rag-inspection-table">
        <thead>
          <tr>
            <th>片段</th>
            <th>当前角色</th>
            <th>判定</th>
            <th>错误类型</th>
          </tr>
        </thead>
        <tbody>
          {chunks.map((c) => {
            const d = drafts[c.chunkId]
            return (
              <tr key={c.chunkId}>
                {/* ⚠️ 片段摘要接口不返回正文（只给定位与规模），
                    故这里展示「页码 · 章节路径 · 题号」——抽检判定要看的是
                    「这段属于哪里、判得对不对」，而不是正文原文。 */}
                <td title={`${c.sectionPath} · p${c.pageNumber}`}>
                  p{c.pageNumber} · {c.sectionPath}
                  {c.exerciseNo ? ` · 题 ${c.exerciseNo}` : ''}
                  {c.hasEmbedding ? '' : ' · ⚠️缺向量'}
                </td>
                <td>{c.role}</td>
                <td>
                  <select
                    value={d?.verdict ?? ''}
                    onChange={(e) =>
                      setDraft(c.chunkId, {
                        verdict: (e.target.value || 'PASS') as 'PASS' | 'FAIL',
                        errorType: e.target.value === 'PASS' ? '' : (d?.errorType ?? ''),
                      })
                    }
                  >
                    <option value="">未判</option>
                    <option value="PASS">正确</option>
                    <option value="FAIL">错误</option>
                  </select>
                </td>
                <td>
                  <select
                    value={d?.errorType ?? ''}
                    disabled={!d || d.verdict !== 'FAIL'}
                    onChange={(e) =>
                      setDraft(c.chunkId, {
                        verdict: 'FAIL',
                        errorType: e.target.value as RagInspectionErrorType | '',
                      })
                    }
                  >
                    <option value="">—</option>
                    {(Object.keys(ERROR_TYPE_LABEL) as RagInspectionErrorType[]).map((k) => (
                      <option key={k} value={k}>
                        {ERROR_TYPE_LABEL[k]}
                      </option>
                    ))}
                  </select>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <div className="rag-inspection-actions">
        <button type="button" disabled={busy || marked.length === 0} onClick={() => void onSubmit()}>
          提交抽检（已标注 {marked.length} 条）
        </button>
      </div>

      <h4>历史记录</h4>
      {records.length === 0 ? (
        <p className="rag-ingest-hint">暂无抽检记录。</p>
      ) : (
        <ul className="rag-inspection-history">
          {records.map((r) => (
            <li key={r.recordId} className={recordTone(r.verdict)}>
              {r.verdict === 'FAIL' ? '错误' : '正确'}
              {r.errorType ? `（${ERROR_TYPE_LABEL[r.errorType] ?? r.errorType}）` : ''}
              {' '}· {r.actualRole} · {r.createdAt}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
