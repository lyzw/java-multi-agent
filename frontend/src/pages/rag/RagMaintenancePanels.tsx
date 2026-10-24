/**
 * RAG 维护面板（语料管理页的运维侧能力）
 *
 * ⚠️ **为什么单独成文件**：语料管理页原本只回答「库里有什么、哪些还没审、能不能放行」
 *    （RAG-D10 §1.1 管理面定义），**没有回答「现在坏在哪儿、怎么修」**。
 *    运维视角缺这三块：
 *    · 分布下钻 —— 统计只有总数，看不出「问题集中在哪一类」；
 *    · 召回测试 —— 「已通过校验」≠「真能被召回」（RAG-D10 L-1：检索路径尚未强制消费状态）；
 *    · 词表口径 —— platform 与 RAG 各有一份学科/年级词表（评审报告 §2.4 点名的缺陷），
 *      两边不一致时**静默漏召回**，且没有任何地方能看见这个差异。
 *
 * ⚠️ **诚实边界**：
 *    · 召回测试调的是**真实检索端点**，因此它证明的是「链路通不通」，
 *      不是「语义质量好不好」——当前嵌入是 `LocalDeterministicEmbeddingPort`
 *      确定性占位（RAG-D02 §3.5），向量相似度**不可用于生产结论**。
 *    · 词表差异**只呈现不修改**：写入端点在 platform 侧尚未提供，
 *      前端自行编辑词表会造出第三份权威源。
 */

import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { Fragment } from 'react'
import { Loading, Empty, ErrorState } from '@/shared/states'
import {
  retrieveChunks,
  getDictionaries,
  applyRagDocumentVerification,
  getRagIssues,
  reindexRagCorpus,
  ApiError,
} from '@/api/client'
import { isTimeoutError } from '@/api/errors'
import { allowedVerificationTargets } from '@/features/rag/useRagManagement'
import {
  RAG_CHUNK_ROLE_LABELS,
  RAG_PARSE_STATUS_LABELS,
  RAG_VERIFICATION_LABELS,
  RAG_VERIFICATION_METHOD_LABELS,
  RAG_TRUST_TIER_LABELS,
  RAG_SUBJECTS,
  RAG_GRADES,
  RAG_TERMS,
  RAG_PUBLISHERS,
  RAG_MODE_LABELS,
  RAG_SCOPE_LABELS,
  type RagDocument,
  type RagEdition,
  type RagMaintenanceIssues,
  type RagReindexResult,
  type RagRetrievalMode,
  type RagRetrievalRequest,
  type RagRetrievalResponse,
  type RagScopeMode,
  type RagVerificationMethod,
  type RagVerificationStatus,
} from '@/api/types'

/* ── 分布下钻 ──────────────────────────────────────────── */

/**
 * 分布表：分类 → 计数（含占比）。
 *
 * ⚠️ `<dt>`/`<dd>` 必须是 `<dl class="kv">` 的**直接子元素**——
 *    `.kv` 是 `display:grid` 的两列网格，插入包裹 `<div>` 会打乱网格
 *    （每行变成一格）。故用 `<Fragment>` 而不是 `<div>` 包裹。
 *
 * ⚠️ 计数占比以**本表合计**为分母，不是全库总量——
 *    例如「来源状态分布」的占比只表示来源之间的构成，
 *    不表示占片段的比例。混用会得出错误结论。
 */
export function DistributionList({
  title,
  entries,
  labelOf,
  emptyHint,
}: {
  title: string
  entries: Record<string, number>
  /** 未知 key 的兜底：直接显示原值，不吞掉 */
  labelOf: (key: string) => string
  emptyHint: string
}): JSX.Element {
  const items = useMemo(
    () => Object.entries(entries).sort((a, b) => b[1] - a[1]),
    [entries],
  )
  const total = items.reduce((sum, [, n]) => sum + n, 0)

  return (
    <div className="rag-mgmt__block">
      <h3 className="rag-mgmt__block-title">{title}</h3>
      {items.length === 0 ? (
        <p className="muted" style={{ margin: 0 }}>
          {emptyHint}
        </p>
      ) : (
        <dl className="kv">
          {items.map(([key, count]) => (
            <Fragment key={key}>
              <dt>{labelOf(key)}</dt>
              <dd>
                {count}
                {total > 0 ? `（${Math.round((count / total) * 100)}%）` : ''}
              </dd>
            </Fragment>
          ))}
        </dl>
      )}
    </div>
  )
}

/** 片段角色分布（key = `rag_chunk.role` 枚举名）。 */
export function ChunkRoleDistribution({
  entries,
}: {
  entries: Record<string, number>
}): JSX.Element {
  return (
    <DistributionList
      title="片段角色分布"
      entries={entries}
      labelOf={(k) => RAG_CHUNK_ROLE_LABELS[k] ?? k}
      emptyHint="还没有入库片段。角色分布是判断「这批语料能不能支撑讲解」的依据。"
    />
  )
}

/** 来源校验状态分布（key = `ver_status` 枚举名）。 */
export function SourceStatusDistribution({
  entries,
}: {
  entries: Record<string, number>
}): JSX.Element {
  return (
    <DistributionList
      title="来源校验状态分布"
      entries={entries}
      labelOf={(k) => RAG_VERIFICATION_LABELS[k as keyof typeof RAG_VERIFICATION_LABELS] ?? k}
      emptyHint="还没有登记来源。"
    />
  )
}

/** 载体解析状态分布（key = `parse_status`）。 */
export function ParseStatusDistribution({
  entries,
}: {
  entries: Record<string, number>
}): JSX.Element {
  return (
    <DistributionList
      title="载体解析状态分布"
      entries={entries}
      labelOf={(k) => RAG_PARSE_STATUS_LABELS[k] ?? k}
      emptyHint="还没有载体。解析失败的载体不会产出片段，等于这份语料在检索侧不存在。"
    />
  )
}

/* ── 召回测试 ──────────────────────────────────────────── */

type ProbeStatus = 'idle' | 'loading' | 'success' | 'empty' | 'error' | 'timeout'

/**
 * 召回测试：用**真实检索端点**验证「已准入的语料能否被召回」。
 *
 * ⚠️ 为什么管理面需要它：RAG-D10 已知限制 L-1 指出——
 *    治理状态**可管理**，但检索路径**尚未强制消费**这些状态
 *    （过期/版权/等级三闸门缺失）。于是「来源已 VERIFIED」并不等于
 *    「它的片段会被召回」。只看管理面会得到虚假的安心感。
 *
 * ⛔ 边界：当前嵌入是确定性占位，故本面板证明的是**链路**，
 *    不是**语义质量**。文案必须如实标注，不得让运维误以为这是效果评测。
 */
export function RetrievalProbe({ tenantId }: { tenantId: string }): JSX.Element {
  const [query, setQuery] = useState('')
  const [mode, setMode] = useState<RagRetrievalMode>('EXPLAIN')
  const [scopeMode, setScopeMode] = useState<RagScopeMode>('STRICT')
  const [subject, setSubject] = useState<string>(RAG_SUBJECTS[0])
  const [grade, setGrade] = useState<string>(RAG_GRADES[6])
  const [term, setTerm] = useState<string>(RAG_TERMS[0])
  const [publisher, setPublisher] = useState<string>(RAG_PUBLISHERS[0])
  const [revisionCode, setRevisionCode] = useState('2024')
  const [topK, setTopK] = useState(8)

  const [status, setStatus] = useState<ProbeStatus>('idle')
  const [response, setResponse] = useState<RagRetrievalResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  const tenantReady = tenantId.trim().length > 0

  const run = useCallback(
    async (payload: RagRetrievalRequest): Promise<void> => {
      setStatus('loading')
      setError(null)
      try {
        const data = await retrieveChunks(payload, undefined, tenantId.trim())
        setResponse(data)
        setStatus(data.hits.length === 0 ? 'empty' : 'success')
      } catch (cause) {
        setResponse(null)
        // ⚠️ 超时与其它错误分开成态（同 useRagManagement 的五态纪律）：
        //    「检索慢」和「检索请求非法」的下一步建议完全不同。
        if (cause instanceof ApiError && isTimeoutError(cause.normalized)) {
          setStatus('timeout')
          setError('检索超时。语料量大或嵌入服务慢时会这样，请稍后重试。')
          return
        }
        setStatus('error')
        setError(
          cause instanceof ApiError
            ? cause.normalized.message
            : '检索请求失败，请检查租户标识与版本坐标。',
        )
      }
    },
    [tenantId],
  )

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault()
    if (query.trim() === '' || !tenantReady) return
    const edition: RagEdition = {
      subject,
      grade,
      term,
      publisher,
      revisionCode: revisionCode.trim() === '' ? '2024' : revisionCode.trim(),
    }
    void run({
      query: query.trim(),
      mode,
      edition,
      scopeMode,
      knowledgePointId: null,
      subject: null,
      grade: null,
      topK,
    })
  }

  const busy = status === 'loading'

  return (
    <>
      <form onSubmit={handleSubmit} noValidate>
        <div className="field">
          <label htmlFor="rag-probe-query">测试问题</label>
          <input
            id="rag-probe-query"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="例如：二次函数的顶点怎么求？"
          />
        </div>

        <div className="rag-row">
          <div className="field" style={{ margin: 0, maxWidth: 180 }}>
            <label htmlFor="rag-probe-mode">模式</label>
            <select
              id="rag-probe-mode"
              value={mode}
              onChange={(e) => setMode(e.target.value as RagRetrievalMode)}
            >
              {(Object.keys(RAG_MODE_LABELS) as RagRetrievalMode[]).map((m) => (
                <option key={m} value={m}>
                  {RAG_MODE_LABELS[m]}
                </option>
              ))}
            </select>
          </div>

          <div className="field" style={{ margin: 0, maxWidth: 220 }}>
            <label htmlFor="rag-probe-scope">版本范围</label>
            <select
              id="rag-probe-scope"
              value={scopeMode}
              onChange={(e) => setScopeMode(e.target.value as RagScopeMode)}
            >
              {(Object.keys(RAG_SCOPE_LABELS) as RagScopeMode[]).map((s) => (
                <option key={s} value={s}>
                  {RAG_SCOPE_LABELS[s]}
                </option>
              ))}
            </select>
          </div>

          <div className="field" style={{ margin: 0, maxWidth: 150 }}>
            <label htmlFor="rag-probe-subject">学科</label>
            <select
              id="rag-probe-subject"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
            >
              {RAG_SUBJECTS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>

          <div className="field" style={{ margin: 0, maxWidth: 150 }}>
            <label htmlFor="rag-probe-grade">年级</label>
            <select id="rag-probe-grade" value={grade} onChange={(e) => setGrade(e.target.value)}>
              {RAG_GRADES.map((g) => (
                <option key={g} value={g}>
                  {g}
                </option>
              ))}
            </select>
          </div>

          <div className="field" style={{ margin: 0, maxWidth: 130 }}>
            <label htmlFor="rag-probe-term">册次</label>
            <select id="rag-probe-term" value={term} onChange={(e) => setTerm(e.target.value)}>
              {RAG_TERMS.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </div>

          <div className="field" style={{ margin: 0, maxWidth: 140 }}>
            <label htmlFor="rag-probe-publisher">出版社</label>
            <select
              id="rag-probe-publisher"
              value={publisher}
              onChange={(e) => setPublisher(e.target.value)}
            >
              {RAG_PUBLISHERS.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          </div>

          <div className="field" style={{ margin: 0, maxWidth: 110 }}>
            <label htmlFor="rag-probe-revision">版次</label>
            <input
              id="rag-probe-revision"
              value={revisionCode}
              onChange={(e) => setRevisionCode(e.target.value)}
              placeholder="2024"
            />
          </div>

          <div className="field" style={{ margin: 0, maxWidth: 90 }}>
            <label htmlFor="rag-probe-topk">召回数</label>
            <input
              id="rag-probe-topk"
              type="number"
              min={1}
              max={50}
              value={topK}
              onChange={(e) => setTopK(Number(e.target.value))}
            />
          </div>
        </div>

        <div className="btn-row" style={{ marginTop: 'var(--s-4)' }}>
          <button
            type="submit"
            className="btn"
            disabled={busy || query.trim() === '' || !tenantReady}
          >
            {busy ? '检索中…' : '开始召回测试'}
          </button>
          {!tenantReady ? (
            <span className="muted">召回测试需要租户标识，请先在上方「查询条件」里填写。</span>
          ) : null}
        </div>
      </form>

      {status === 'idle' ? (
        <p className="muted" style={{ marginTop: 'var(--s-4)' }}>
          填一个问题与版本坐标，直接打真实检索端点——验证「已准入的语料到底能不能被召回」。
        </p>
      ) : null}

      {status === 'loading' ? <Loading label="正在检索" /> : null}

      {status === 'error' || status === 'timeout' ? (
        <ErrorState
          reason={error ?? '检索失败'}
          next="检索失败通常意味着版本坐标与语料对不上，或租户标识有误。请先核对这两项。"
          onRetry={() => {
            if (query.trim() === '' || !tenantReady) return
            void run({
              query: query.trim(),
              mode,
              edition: { subject, grade, term, publisher, revisionCode: revisionCode.trim() || '2024' },
              scopeMode,
              knowledgePointId: null,
              subject: null,
              grade: null,
              topK,
            })
          }}
          retryLabel="重新检索"
        />
      ) : null}

      {status === 'empty' && response !== null ? (
        <Empty
          title="召回到 0 条"
          hint="链路是通的，但这条版本坐标下没有可召回内容。常见原因：语料未通过校验、版本坐标与入库坐标不一致、或该学科尚未入库。"
        />
      ) : null}

      {status === 'success' && response !== null ? (
        <div style={{ marginTop: 'var(--s-4)' }}>
          <p className="sheet__note">
            召回 {response.hits.length} 条 · 主版本 {response.primaryEdition}
            {' · '}
            分数为相关性分（稠密通道=余弦相似度，词法通道=ts_rank），
            非语义质量分——当前嵌入为确定性占位，它只反映「共享了多少字面」。
          </p>
          {response.versionMismatchDetected ? (
            <div className="marked marked--calm">
              <p className="marked__label">版本不匹配</p>
              <p style={{ margin: 0 }}>
                检出版本与请求坐标不一致，召回结果可能来自其它版本——引用前须核对。
              </p>
            </div>
          ) : null}
          <div className="entry-list" style={{ marginTop: 'var(--s-3)' }}>
            {response.hits.map((hit) => (
              <div key={hit.chunkId} className="entry">
                {/* ⚠️ 标题与描述包进 .entry__body：.entry 是 flex **行**布局，
                    直接子元素会并排——描述（这里是教材片段正文，最长的一类文本）
                    会被挤到标题右侧，并把容器撑破。 */}
                <div className="entry__body">
                  <span className="entry__title">
                    {RAG_CHUNK_ROLE_LABELS[hit.role] ?? hit.role}
                  </span>
                  <span className="entry__desc">{hit.text}</span>
                </div>
                <span className="chip">相关性分 {hit.score.toFixed(3)}</span>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </>
  )
}

/* ── B-1 / B-2：问题清单 + 重跑索引 ────────────────────── */

/**
 * 维护问题清单 + 重跑索引。
 *
 * ⚠️ **与统计的分工**：统计回答「有多少」，清单回答「**是哪些**」。
 *    只有数字时，运维无法行动——「缺 12 个向量」得知道是哪 12 个。
 *
 * ⚠️ **手动触发**（与词表比对一致）：清单是一次诊断动作，
 *    不是页面常驻状态。挂载即拉会把「维护前的快照」和「维护后的结果」
 *    混在一起，运维分不清自己看到的是哪一时刻的。
 */
export function MaintenanceIssueBoard({ tenantId }: { tenantId: string }): JSX.Element {
  const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')
  const [issues, setIssues] = useState<RagMaintenanceIssues | null>(null)
  const [error, setError] = useState<string | null>(null)

  const [reindexStatus, setReindexStatus] = useState<'idle' | 'running' | 'done' | 'error'>('idle')
  const [reindexResult, setReindexResult] = useState<RagReindexResult | null>(null)
  const [reindexError, setReindexError] = useState<string | null>(null)

  const tenantReady = tenantId.trim().length > 0

  const check = useCallback(async (): Promise<void> => {
    setStatus('loading')
    setError(null)
    try {
      const data = await getRagIssues(tenantId.trim())
      setIssues(data)
      setStatus('success')
    } catch (cause) {
      setStatus('error')
      setError(
        cause instanceof ApiError
          ? cause.normalized.message
          : '读取问题清单失败，请确认租户标识。',
      )
    }
  }, [tenantId])

  const runReindex = useCallback(async (): Promise<void> => {
    setReindexStatus('running')
    setReindexError(null)
    try {
      const result = await reindexRagCorpus(tenantId.trim())
      setReindexResult(result)
      setReindexStatus('done')
      // ⚠️ 重跑后重新拉清单：用户要立刻看到问题是否消除，
      //    而不是自己猜「跑了 12 个是不是就修好了」。
      void check()
    } catch (cause) {
      setReindexStatus('error')
      setReindexError(
        cause instanceof ApiError
          ? cause.normalized.message
          : '重跑索引失败。索引是幂等的（只补缺失向量），可重试。',
      )
    }
  }, [tenantId, check])

  if (status === 'idle') {
    return (
      <div className="btn-row">
        <button
          type="button"
          className="btn"
          disabled={!tenantReady}
          onClick={() => void check()}
        >
          检查问题清单
        </button>
        <span className="muted">
          定位「哪里坏了」：缺向量的载体、解析失败的载体、待审核的来源。
        </span>
      </div>
    )
  }

  if (status === 'loading') return <Loading label="正在读取问题清单" />

  if (status === 'error') {
    return (
      <ErrorState
        reason={error ?? '读取问题清单失败'}
        next="问题清单端点不可用时，请确认租户标识与后端服务状态。"
        onRetry={() => void check()}
        retryLabel="重新检查"
      />
    )
  }

  if (issues === null) {
    return (
      <p className="muted" style={{ margin: 0 }}>
        正在准备问题清单…
      </p>
    )
  }

  const allClear =
    issues.totals.documentsMissingEmbedding === 0 &&
    issues.totals.documentsFailedParse === 0 &&
    issues.totals.sourcesPendingReview === 0

  return (
    <>
      {allClear ? (
        <Empty
          title="没有发现待处理的问题"
          hint="缺向量的载体、解析失败的载体、待审核的来源都是空的。"
        />
      ) : null}

      {issues.documentsMissingEmbedding.length > 0 ? (
        <div className="rag-mgmt__block">
          <h3 className="rag-mgmt__block-title">
            缺向量的载体（{issues.totals.documentsMissingEmbedding}）
          </h3>
          <p className="sheet__note">
            这些载体的片段已入库但没有向量——<b>召不回</b>，是静默漏召回。
          </p>
          <div className="entry-list">
            {issues.documentsMissingEmbedding.map((doc) => (
              <div key={doc.documentId} className="entry">
                {/* ⚠️ .entry 是 flex 行布局：标题与描述必须包进 .entry__body 才不会并排。 */}
                <div className="entry__body">
                  <span className="entry__title">{doc.title}</span>
                  <span className="entry__desc">
                    {doc.carrier} · 解析 {RAG_PARSE_STATUS_LABELS[doc.parseStatus] ?? doc.parseStatus} ·
                    共 {doc.chunkCount} 片段，其中 {doc.missingEmbeddingCount} 个缺向量
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {issues.documentsFailedParse.length > 0 ? (
        <div className="rag-mgmt__block">
          <h3 className="rag-mgmt__block-title">
            解析失败的载体（{issues.totals.documentsFailedParse}）
          </h3>
          <p className="sheet__note">
            解析失败不产出片段——这份语料在检索侧等于不存在。
          </p>
          <div className="entry-list">
            {issues.documentsFailedParse.map((doc) => (
              <div key={doc.documentId} className="entry">
                {/* ⚠️ .entry 是 flex 行布局：标题与描述必须包进 .entry__body 才不会并排。 */}
                <div className="entry__body">
                  <span className="entry__title">{doc.title}</span>
                  <span className="entry__desc">
                    {doc.carrier} · 解析 {RAG_PARSE_STATUS_LABELS[doc.parseStatus] ?? doc.parseStatus} ·
                    共 {doc.chunkCount} 片段
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {issues.sourcesPendingReview.length > 0 ? (
        <div className="rag-mgmt__block">
          <h3 className="rag-mgmt__block-title">
            待审核的来源（{issues.totals.sourcesPendingReview}）
          </h3>
          <p className="sheet__note">来源未通过校验，其下的语料不会进入可检索索引。</p>
          <div className="entry-list">
            {issues.sourcesPendingReview.map((src) => (
              <div key={src.sourceId} className="entry">
                {/* ⚠️ .entry 是 flex 行布局：标题与描述必须包进 .entry__body 才不会并排。 */}
                <div className="entry__body">
                  <span className="entry__title">{src.title}</span>
                  <span className="entry__desc">
                    {RAG_VERIFICATION_LABELS[src.verificationStatus] ?? src.verificationStatus} ·{' '}
                    {RAG_TRUST_TIER_LABELS[src.trustTier] ?? src.trustTier}
                    {src.stale ? ' · 已过期' : ''}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {/*
        ⚠️ 重跑索引只补**缺失向量**，不解析、不切分、不审核。
          故按钮文案必须写清它**不**做什么——
          运维若以为「重跑索引」能修好解析失败的载体，会一直等一个不会发生的结果。
      */}
      <div className="rag-mgmt__block">
        <h3 className="rag-mgmt__block-title">重跑索引</h3>
        <p className="sheet__note">
          只为「来源已通过校验、但片段缺向量」的片段补建向量。
          <b>不</b>解析、不切分、不审核语料——解析失败与待审核的问题需另行处理。
        </p>
        <div className="btn-row">
          <button
            type="button"
            className="btn"
            disabled={!tenantReady || reindexStatus === 'running'}
            onClick={() => void runReindex()}
          >
            {reindexStatus === 'running' ? '重跑中…' : '重跑索引'}
          </button>
        </div>
        {reindexStatus === 'done' && reindexResult !== null ? (
          <div className="marked marked--calm" role="status" style={{ marginTop: 'var(--s-3)' }}>
            <p className="marked__label">已重跑</p>
            <p style={{ margin: 0 }}>
              扫描 {reindexResult.scannedChunks} 个待索引片段，处理 {reindexResult.indexedChunks} 个，
              耗时 {reindexResult.durationMs} ms。
            </p>
          </div>
        ) : null}
        {reindexStatus === 'error' ? (
          <div className="marked" role="alert" style={{ marginTop: 'var(--s-3)' }}>
            <p className="marked__label">没能完成</p>
            <p style={{ margin: 0 }}>
              {reindexError ?? '重跑索引失败。'}索引是幂等的（只补缺失向量），可以直接重试。
            </p>
          </div>
        ) : null}
      </div>
    </>
  )
}

/* ── C：载体级审核与下架 ───────────────────────────────── */

/**
 * 载体级冲突文案。
 *
 * ⚠️ **刻意不用归一化错误文案**：409 的默认映射是「请求冲突，请刷新后重试」，
 *    而刷新不会让非法转移变合法（同 useRagManagement 的 CONFLICT_MESSAGE 纪律）。
 *    这里把「为什么被拒」与「合法下一步」都写清楚。
 */
const DOCUMENT_CONFLICT_MESSAGE =
  '这一步不被允许：载体不得高于来源（来源未通过校验时，载体不能置为已通过），' +
  '且撤回是终态、已拒绝的载体须先重新认领。请按提示选择合法的下一步。'

/**
 * 载体级审核动作。
 *
 * ⚠️ **只渲染合法的目标状态**（与后端 `DocumentVerification` 对齐）：
 *    撤回是终态 → 无后继；已拒绝不得直通通过 → 只能重新认领。
 *    让审核员点一个注定 409 的操作是产品缺陷。
 *
 * ⚠️ **「载体不得高于来源」在此预筛**：来源未通过校验时，
 *    载体的「通过校验」选项**不出现**。这条不变量是公开的领域规则
 *    （不是服务端才知道的权威门槛），故前端可以据此裁剪；
 *    但**最终判定仍在服务端**（前端预筛只是不给出注定失败的操作）。
 */
export function DocumentVerificationControls({
  document,
  tenantId,
  sourceStatus,
  onChanged,
}: {
  document: RagDocument
  tenantId: string
  sourceStatus: RagVerificationStatus
  onChanged: (updated: RagDocument) => void
}): JSX.Element {
  const current = document.verificationStatus ?? 'PENDING'
  const targets = useMemo(
    () =>
      allowedVerificationTargets(current).filter(
        // 载体不得高于来源：来源没过校验，载体不能自称 VERIFIED
        (t) => t !== 'VERIFIED' || sourceStatus === 'VERIFIED',
      ),
    [current, sourceStatus],
  )

  const [open, setOpen] = useState(false)
  const [target, setTarget] = useState<RagVerificationStatus | null>(targets[0] ?? null)
  const [evidence, setEvidence] = useState('')
  const [decider, setDecider] = useState('')
  const [method, setMethod] = useState<RagVerificationMethod>('MANUAL_REVIEW')
  const [busy, setBusy] = useState(false)
  const [conflict, setConflict] = useState<string | null>(null)
  const [failed, setFailed] = useState<string | null>(null)

  if (targets.length === 0) {
    return (
      <div className="marked marked--calm">
        <p className="marked__label">已撤回</p>
        <p style={{ margin: 0 }}>
          撤回是终态，载体不能复活。若这份物理文件需要重新准入，
          请<b>重新登记</b>一份载体——保留原记录才能回答「这个载体为什么不见了」。
        </p>
      </div>
    )
  }

  const ready = target !== null && evidence.trim() !== '' && decider.trim() !== '' && !busy

  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault()
    if (!ready || target === null) return
    setBusy(true)
    setConflict(null)
    setFailed(null)
    void applyRagDocumentVerification(tenantId, document.documentId, {
      targetStatus: target,
      evidenceRef: evidence.trim(),
      decidedBy: decider.trim(),
      verificationMethod: method,
      trustTier: null,
      validDays: 365,
    })
      .then((updated) => {
        setBusy(false)
        setOpen(false)
        setEvidence('')
        onChanged(updated)
      })
      .catch((cause: unknown) => {
        setBusy(false)
        // ⚠️ 409 = 治理规则被违反（不是服务故障）。
        //    ⛔ **不得使用归一化后的错误文案**：它的 409 默认映射是
        //    「请求冲突，请刷新后重试」——而刷新**永远不会**让非法转移变合法，
        //    只会诱导审核员反复重试一个注定被拒的操作。
        //    故此处用本域自己的冲突文案（与 useRagManagement 的 CONFLICT_MESSAGE 同构）。
        if (cause instanceof ApiError && cause.normalized.status === 409) {
          setConflict(DOCUMENT_CONFLICT_MESSAGE)
          return
        }
        setFailed(cause instanceof ApiError ? cause.normalized.message : '提交失败，请稍后重试。')
      })
  }

  return (
    <>
      <div className="btn-row">
        <button
          type="button"
          className="btn btn--quiet"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
        >
          {open ? '收起载体审核' : '载体审核'}
        </button>
      </div>

      {open ? (
        <form onSubmit={submit} noValidate style={{ marginTop: 'var(--s-3)' }}>
          {conflict !== null ? (
            <div className="marked" role="alert">
              <p className="marked__label">这一步不被允许</p>
              <p style={{ margin: 0 }}>{conflict}</p>
            </div>
          ) : null}
          {failed !== null ? (
            <div className="marked" role="alert">
              <p className="marked__label">没能完成</p>
              <p style={{ margin: 0 }}>{failed}</p>
            </div>
          ) : null}

          <div className="rag-row">
            <div className="field" style={{ margin: 0, maxWidth: 200 }}>
              <label htmlFor={`doc-target-${document.documentId}`}>下一步</label>
              <select
                id={`doc-target-${document.documentId}`}
                value={target ?? ''}
                onChange={(e) => setTarget(e.target.value as RagVerificationStatus)}
              >
                {targets.map((t) => (
                  <option key={t} value={t}>
                    {RAG_VERIFICATION_LABELS[t]}
                  </option>
                ))}
              </select>
            </div>

            <div className="field" style={{ margin: 0, maxWidth: 200 }}>
              <label htmlFor={`doc-method-${document.documentId}`}>校验手段</label>
              <select
                id={`doc-method-${document.documentId}`}
                value={method}
                onChange={(e) => setMethod(e.target.value as RagVerificationMethod)}
              >
                {(Object.keys(RAG_VERIFICATION_METHOD_LABELS) as RagVerificationMethod[]).map((m) => (
                  <option key={m} value={m}>
                    {RAG_VERIFICATION_METHOD_LABELS[m]}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="field">
            <label htmlFor={`doc-evidence-${document.documentId}`}>校验依据（必填）</label>
            <input
              id={`doc-evidence-${document.documentId}`}
              value={evidence}
              onChange={(e) => setEvidence(e.target.value)}
              placeholder="例如：扫描件第 3 页缺页，2026-10-23 复核"
            />
          </div>

          <div className="field">
            <label htmlFor={`doc-decider-${document.documentId}`}>决定人（UUID，必填）</label>
            <input
              id={`doc-decider-${document.documentId}`}
              value={decider}
              onChange={(e) => setDecider(e.target.value)}
              placeholder="审核员的主体 UUID"
              className="hand"
              autoComplete="off"
            />
            <p className="field__hint">
              决定人与依据都会写入审计。撤回（已撤回状态）<b>必带依据</b>。
            </p>
          </div>

          <div className="btn-row">
            <button type="submit" className="btn" disabled={!ready}>
              {busy ? '提交中…' : '提交载体判定'}
            </button>
          </div>
        </form>
      ) : null}
    </>
  )
}

/** 载体行：状态徽章 + 载体级审核入口。 */
export function DocumentEntry({
  document,
  tenantId,
  sourceStatus,
  onChanged,
  onViewChunks,
}: {
  document: RagDocument
  tenantId: string
  sourceStatus: RagVerificationStatus
  onChanged: (updated: RagDocument) => void
  /** 「查看片段」动作（可选） */
  onViewChunks?: (documentId: string) => void
}): JSX.Element {
  const status = document.verificationStatus ?? 'PENDING'
  const blocking = status === 'REVOKED' || status === 'REJECTED'
  return (
    <div className="entry">
      {/* ⚠️ .entry 是 flex 行布局：标题与描述必须包进 .entry__body 才不会并排，
          否则描述（载体 + 解析状态 + 片段数）会挤到标题右侧。 */}
      <div className="entry__body">
        <span className="entry__title">{document.title}</span>
        <span className="entry__desc">
          {document.carrier} · 解析 {RAG_PARSE_STATUS_LABELS[document.parseStatus] ?? document.parseStatus} ·
          {document.chunkCount} 个片段
          {document.pageCount !== null ? ` · ${document.pageCount} 页` : ''}
        </span>
      </div>
      <div className="rag-mgmt__tags">
        <span className={blocking ? 'chip chip--mark' : 'chip'}>
          {RAG_VERIFICATION_LABELS[status]}
        </span>
        {/*
          ⚠️ 这里显示的是**标记**，不是保证。
             检索路径当前只过滤 `src.ver_status='VERIFIED'`（来源级），
             **尚未**过滤 `doc.retrievable`（RAG-D07 §8 的 RAG-R01 缺口）。
             故措辞用「标记：开/关」而非「可检索/不可检索」——
             后者会让人以为学生端一定搜不到，而实际上单独撤回的载体仍可能被召回。
        */}
        <span className="chip">可检索标记：{document.retrievable ? '开' : '关'}</span>
        {document.revokedReason !== undefined && document.revokedReason !== null ? (
          <span className="chip">撤回依据：{document.revokedReason}</span>
        ) : null}
        {onViewChunks !== undefined ? (
          <button
            type="button"
            className="btn btn--quiet"
            onClick={() => onViewChunks(document.documentId)}
          >
            查看片段
          </button>
        ) : null}
      </div>
      {status === 'REVOKED' ? (
        <div className="marked marked--calm">
          <p className="marked__label">载体级下架的诚实边界</p>
          <p style={{ margin: 0 }}>
            撤回只在<b>管理面</b>生效：检索路径尚未过滤载体级可检索标记，
            因此<b>单独撤回这个载体，学生端仍可能召回它</b>。
            来源级撤回会连带下架载体，那才是真正阻断召回的路径。
          </p>
        </div>
      ) : null}
      <DocumentVerificationControls
        document={document}
        tenantId={tenantId}
        sourceStatus={sourceStatus}
        onChanged={onChanged}
      />
    </div>
  )
}

/* ── D-2：词表口径差异 ─────────────────────────────────── */

interface DriftEntry {
  onlyInRag: string[]
  onlyInPlatform: string[]
}

/**
 * 计算两份词表的差集。
 *
 * ⚠️ 刻意**不**做「自动对齐」：两份词表语义本就不同
 *    （platform 是初中理科辅导口径，RAG 是教材版本坐标口径），
 *    自动合并会造出第三份口径。**呈现差异**才是运维该做的事。
 */
function diff(left: readonly string[], right: readonly string[]): DriftEntry {
  const rightSet = new Set(right)
  const leftSet = new Set(left)
  return {
    onlyInRag: left.filter((x) => !rightSet.has(x)),
    onlyInPlatform: right.filter((x) => !leftSet.has(x)),
  }
}

/**
 * 词表口径差异：platform 的 `/api/reference` vs RAG 侧的硬编码坐标。
 *
 * ⚠️ **只呈现不修改**（诚实边界）：写入端点在 platform 侧尚未提供，
 *    前端自行编辑会造出第三份权威源，且改动不落在任何一个后端里。
 *
 * ⚠️ **刻意不自动加载**（手动触发）：比对要打的是**另一个服务**（platform）的
 *    `/api/reference`。若在页面挂载时自动打，platform 一抖动就会在 RAG 管理页
 *    弹出一个与本租户语料无关的错误条——噪音，且会掩盖真正的语料问题。
 *    改为显式点击，既避免误报，也让「什么时候检查过」由人决定。
 */
export function VocabularyDrift(): JSX.Element {
  const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle')
  const [drift, setDrift] = useState<{ subjects: DriftEntry; grades: DriftEntry } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const check = useCallback(async (): Promise<void> => {
    setStatus('loading')
    setError(null)
    try {
      const dict = await getDictionaries()
      setDrift({
        subjects: diff(RAG_SUBJECTS, dict.subjects ?? []),
        grades: diff(RAG_GRADES, dict.grades ?? []),
      })
      setStatus('success')
    } catch (cause) {
      setStatus('error')
      setError(
        cause instanceof ApiError
          ? cause.normalized.message
          : '读取平台词表失败（/api/reference）。',
      )
    }
  }, [])

  if (status === 'idle') {
    return (
      <div className="btn-row">
        <button type="button" className="btn" onClick={() => void check()}>
          比对词表口径
        </button>
        <span className="muted">
          比对 platform 的业务词表与 RAG 的版本坐标词表——两边不一致会静默漏召回。
        </span>
      </div>
    )
  }

  if (status === 'loading') return <Loading label="正在比对词表" />

  if (status === 'error') {
    return (
      <ErrorState
        reason={error ?? '读取平台词表失败'}
        next="平台词表端点 /api/reference 不可用时无法比对差异。"
        onRetry={() => void check()}
        retryLabel="重新读取"
      />
    )
  }

  if (drift === null) {
    return (
      <p className="muted" style={{ margin: 0 }}>
        正在准备词表比对…
      </p>
    )
  }

  const sections = [
    { title: '学科', entry: drift.subjects },
    { title: '年级', entry: drift.grades },
  ]

  return (
    <>
      {sections.map((s) => (
        <div key={s.title} className="rag-mgmt__block">
          <h3 className="rag-mgmt__block-title">{s.title}口径差异</h3>
          <dl className="kv">
            <dt>仅 RAG 侧有</dt>
            <dd>{s.entry.onlyInRag.length === 0 ? '无' : s.entry.onlyInRag.join('、')}</dd>
            <dt>仅 platform 有</dt>
            <dd>
              {s.entry.onlyInPlatform.length === 0 ? '无' : s.entry.onlyInPlatform.join('、')}
            </dd>
          </dl>
        </div>
      ))}
      <div className="marked marked--calm" style={{ marginTop: 'var(--s-3)' }}>
        <p className="marked__label">为什么只呈现不修改</p>
        <p style={{ margin: 0 }}>
          两份词表语义本就不同（platform 是辅导业务口径，RAG 是教材版本坐标口径）。
          自动对齐会造出第三份权威源；改动必须落在 platform 侧，
          由后端下发（评审报告 B3：词表端点未落地前，前端硬编码是已知缺口）。
        </p>
      </div>
    </>
  )
}
