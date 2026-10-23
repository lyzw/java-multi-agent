/**
 * 知识检索页（RAG 前端模块 · FE-RAG）
 *
 * 交互结构：搜索框 + 检索/版本控件 → 发起「检索 + 生成」请求 →
 *   ① 回答区：展示基于检索来源整理的教材原文（B5：逐字摘录，非大模型）；
 *   ② 来源区：展示可溯源的召回片段（册/章/页/题）。
 *
 * ⚠️ 诚实边界（沿用全站五态纪律）：
 *   · 生成方式是「逐字摘录装配」（`EXCERPT_ASSEMBLY`），**不是大模型**——
 *     页面必须展示后端回带的 `generator` 标识，不得写成「AI 讲解」。
 *   · 依据不足（后端 200 + 空 answer）是**合法结果**，按冷静提示呈现，
 *     不刷红、不伪装故障（RAG-D00 防幻觉纪律）。
 *   · 0 命中 = 合法空态（分数门禁过滤），不是故障。
 *   · 超时 / 网络失败 / 端点缺失 三者文案与下一步各不相同。
 *
 * ⚠️ 版本五轴全部必填且必须命中后端枚举展示名，
 *    否则后端 400（控制器用 displayName 精确匹配）。故学科/年级/册次/出版社
 *    用下拉枚举（从后端 EditionCoordinate 提取），版次用文本输入，默认 "2024"。
 */

import { useMemo, useState, type FormEvent } from 'react'
import { Sheet, Loading, Empty, ErrorState, CapabilityNote } from '@/shared/states'
import { useRagSearch } from '@/features/rag/useRagSearch'
import { useSession } from '@/auth/session'
import type {
  RagRetrievalMode,
  RagScopeMode,
  RagEdition,
  RagRetrievalRequest,
  RagRetrievalHit,
} from '@/api/types'
import {
  RAG_SUBJECTS,
  RAG_GRADES,
  RAG_TERMS,
  RAG_PUBLISHERS,
  RAG_MODE_LABELS,
  RAG_SCOPE_LABELS,
} from '@/api/types'

const MODES: RagRetrievalMode[] = ['EXPLAIN', 'SOLVE', 'VERIFY', 'OVERVIEW']

function formatScore(score: number): string {
  if (!Number.isFinite(score)) return '—'
  return `${Math.round(score * 100)}%`
}

/** 单个来源卡片（可溯源的召回片段）。 */
function SourceCard({
  hit,
  index,
}: {
  hit: RagRetrievalHit
  index: number
}): JSX.Element {
  return (
    <article className="source">
      <header className="source__head">
        <span className="source__no">来源 {index}</span>
        <span className="chip">{hit.edition}</span>
        <span className="chip">{hit.channel}</span>
        <span className="chip chip--settled">{hit.role}</span>
        <span className="source__score">相关度 {formatScore(hit.score)}</span>
      </header>
      <p className="source__text hand">{hit.text}</p>
      <dl className="source__meta">
        <div>
          <dt>引用</dt>
          <dd>{hit.locator}</dd>
        </div>
        <div>
          <dt>命中原因</dt>
          <dd>{hit.reason}</dd>
        </div>
      </dl>
    </article>
  )
}

const TENANT_STORAGE_KEY = 'rag.tenantId'

/** 读取上次使用的租户标识（仅本地记忆，不涉及任何凭据）。 */
function readStoredTenantId(): string {
  try {
    return window.localStorage.getItem(TENANT_STORAGE_KEY) ?? ''
  } catch {
    // ⚠️ 隐私模式/禁用存储时 localStorage 可能抛错——此时退回空串，让用户手填。
    return ''
  }
}

function rememberTenantId(tenantId: string): void {
  try {
    if (tenantId === '') window.localStorage.removeItem(TENANT_STORAGE_KEY)
    else window.localStorage.setItem(TENANT_STORAGE_KEY, tenantId)
  } catch {
    // 记忆失败不影响检索本身，静默忽略。
  }
}

export function RagSearchPage(): JSX.Element {
  const [query, setQuery] = useState('')
  const [mode, setMode] = useState<RagRetrievalMode>('EXPLAIN')
  const [subject, setSubject] = useState<string>(RAG_SUBJECTS[0])
  const [grade, setGrade] = useState<string>(RAG_GRADES[6]) // 七年级
  const [term, setTerm] = useState<string>(RAG_TERMS[0]) // 上册
  const [publisher, setPublisher] = useState<string>(RAG_PUBLISHERS[0]) // 人教版
  const [revisionCode, setRevisionCode] = useState('2024')
  const [topK, setTopK] = useState(8)
  // ⚠️ 空串 → 后端按 STRICT 处理（scopeMode 可空）。
  const [scopeMode, setScopeMode] = useState<string>('')
  // ⚠️ 租户标识：RAG 的检索 SQL 以 `tenant_id = rag_current_tenant()` 收口，
  //    该函数读会话变量 `app.tenant_id`（只在事务内有效）。跨进程调用时
  //    主平台的事务上下文不随请求而来，故必须由调用方显式携带（X-Tenant-Id）。
  //    缺失时后端返回 400，而不是静默返回空结果。
  // ⚠️ B2（2026-10-23）：优先用登录回带的权威 tenantId 预填，消除手填 UUID 的痛苦；
  //    若用户此前在本机手填过（localStorage 记忆），仍以手填值为准，并允许重新编辑。
  const { session } = useSession()
  const [tenantId, setTenantId] = useState<string>(
    () => session?.tenantId ?? readStoredTenantId(),
  )

  const { retrieval, generation, search, retry } = useRagSearch()

  const busy = retrieval.status === 'loading' || generation.status === 'loading'
  const tenantReady = tenantId.trim().length > 0
  const canSubmit = query.trim().length > 0 && tenantReady && !busy

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault()
    if (!canSubmit) return
    const edition: RagEdition = {
      subject,
      grade,
      term,
      publisher,
      revisionCode: revisionCode.trim() === '' ? '2024' : revisionCode.trim(),
    }
    const payload: RagRetrievalRequest = {
      query: query.trim(),
      mode,
      edition,
      // ⚠️ 空串 → null，让后端走默认 STRICT。
      scopeMode: scopeMode === '' ? null : (scopeMode as RagScopeMode),
      knowledgePointId: null,
      subject: null,
      grade: null,
      topK,
    }
    rememberTenantId(tenantId.trim())
    search(payload, tenantId.trim())
  }

  const versionMismatch = useMemo(
    () => retrieval.response?.versionMismatchDetected ?? false,
    [retrieval.response],
  )
  const answerSuppressed = useMemo(
    () => retrieval.response?.answerContentSuppressed ?? false,
    [retrieval.response],
  )

  return (
    <>
      <div className="main__head">
        <h1>知识检索</h1>
        <p className="main__lead">
          输入问题，从教材语料中检索可溯源的讲解与出处；生成回答会基于这些来源，避免凭空编造。
        </p>
      </div>

      {/* ── 检索设置 ── */}
      <Sheet title="检索设置" note="版本五轴决定引用能否被校验，请按学生正在用的教材填写。">
        <form onSubmit={handleSubmit} noValidate>
          <div className="field">
            <label htmlFor="rag-query">你的问题</label>
            <textarea
              id="rag-query"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="例如：二次函数的顶点怎么求？"
              rows={3}
              className="hand"
            />
          </div>

          <div className="field" style={{ maxWidth: 280 }}>
            <label htmlFor="rag-mode">检索模式</label>
            <select
              id="rag-mode"
              value={mode}
              onChange={(e) => setMode(e.target.value as RagRetrievalMode)}
            >
              {MODES.map((m) => (
                <option key={m} value={m}>
                  {RAG_MODE_LABELS[m]}
                </option>
              ))}
            </select>
          </div>

          <div className="field">
            <label htmlFor="rag-tenant">租户标识</label>
            <input
              id="rag-tenant"
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
              placeholder="UUID，例如 11111111-1111-1111-1111-111111111111"
              className="hand"
              autoComplete="off"
            />
            <p className="field__hint">
              ⚠️ 检索按租户隔离（RLS）：已自动填入登录租户，一般无需修改；
              若与你的教材租户不同，可手动更正。留空将无法检索。
            </p>
          </div>

          <fieldset className="rag-edition">
            <legend>教材版本</legend>
            <div className="rag-edition-grid">
              <div className="field" style={{ margin: 0 }}>
                <label htmlFor="rag-subject">学科</label>
                <select
                  id="rag-subject"
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
              <div className="field" style={{ margin: 0 }}>
                <label htmlFor="rag-grade">年级</label>
                <select
                  id="rag-grade"
                  value={grade}
                  onChange={(e) => setGrade(e.target.value)}
                >
                  {RAG_GRADES.map((g) => (
                    <option key={g} value={g}>
                      {g}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field" style={{ margin: 0 }}>
                <label htmlFor="rag-term">册次</label>
                <select
                  id="rag-term"
                  value={term}
                  onChange={(e) => setTerm(e.target.value)}
                >
                  {RAG_TERMS.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field" style={{ margin: 0 }}>
                <label htmlFor="rag-publisher">出版社</label>
                <select
                  id="rag-publisher"
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
              <div className="field" style={{ margin: 0 }}>
                <label htmlFor="rag-revision">版次</label>
                <input
                  id="rag-revision"
                  type="text"
                  value={revisionCode}
                  onChange={(e) => setRevisionCode(e.target.value)}
                  placeholder="如 2024"
                />
              </div>
            </div>
          </fieldset>

          <div className="rag-row">
            <div className="field" style={{ margin: 0, maxWidth: 200 }}>
              <label htmlFor="rag-topk">召回数量（1–50）</label>
              <input
                id="rag-topk"
                type="number"
                min={1}
                max={50}
                value={topK}
                onChange={(e) => {
                  const n = Number(e.target.value)
                  if (Number.isFinite(n)) setTopK(Math.min(50, Math.max(1, Math.trunc(n))))
                }}
              />
            </div>
            <div className="field" style={{ margin: 0, maxWidth: 320 }}>
              <label htmlFor="rag-scope">版本范围</label>
              <select
                id="rag-scope"
                value={scopeMode}
                onChange={(e) => setScopeMode(e.target.value)}
              >
                <option value="">默认（严格锁定当前版本）</option>
                {(Object.keys(RAG_SCOPE_LABELS) as RagScopeMode[]).map((s) => (
                  <option key={s} value={s}>
                    {RAG_SCOPE_LABELS[s]}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="btn-row" style={{ marginTop: 'var(--s-4)' }}>
            <button type="submit" className="btn" disabled={!canSubmit}>
              {busy ? '检索中…' : '检索并生成'}
            </button>
            <span className="muted">
              检索调用 <code>/api/v1/retrieval</code>；整理调用{' '}
              <code>/api/v1/retrieval/generate</code>，依据就是本次召回结果。
            </span>
          </div>
        </form>
      </Sheet>

      {/* ── 回答区 ── */}
      <Sheet
        title="回答"
        note="内容逐字摘自下方检索来源，未作改写；未接入大模型前不产生新的解释。"
      >
        {generation.status === 'idle' ? (
          <p className="muted" style={{ margin: 0 }}>
            提交问题后，这里会显示基于检索来源整理的教材原文。
          </p>
        ) : generation.status === 'loading' ? (
          <Loading label="正在整理教材原文" />
        ) : generation.status === 'success' ? (
          <>
            {/*
              ⚠️ B5：生成方式必须如实展示。后端当前是「逐字摘录装配」
                 （EXCERPT_ASSEMBLY），不是大模型——写成「AI 讲解」是欺骗。
            */}
            <p className="generator-note">
              整理方式：<code>{generation.generator ?? 'EXCERPT_ASSEMBLY'}</code>
              （逐字摘自教材原文，未作改写）
            </p>
            <div className="answer">{generation.answer}</div>
            {generation.citations.length > 0 ? (
              <p className="muted" style={{ marginTop: 'var(--s-3)' }}>
                本段引用了 {generation.citations.length} 处教材原文。
              </p>
            ) : null}
            {generation.disclaimer !== null ? (
              <div className="notice" role="status" style={{ marginTop: 'var(--s-3)' }}>
                <p style={{ margin: 0 }}>{generation.disclaimer}</p>
              </div>
            ) : null}
          </>
        ) : generation.status === 'insufficient' ? (
          // ⚠️ 依据不足 = 合法结果（后端 200 + 空 answer），不是故障。
          <div className="notice" role="status">
            <p style={{ margin: 0 }}>
              {generation.disclaimer ??
                '没有可引用的教材依据，因此不生成讲解——比给一段无出处的解释更诚实。'}
            </p>
            <p className="muted" style={{ margin: 'var(--s-2) 0 0' }}>
              可以换个说法，或把版本范围改成「优先本版，可回退同册」再试。
            </p>
          </div>
        ) : generation.status === 'notConnected' ? (
          <CapabilityNote
            feature="生成回答"
            blockedBy="B5"
            reason="rag 模块的生成端点未就绪。检索来源已经在下方列出，可据此查看讲解依据。"
          />
        ) : (
          <ErrorState
            reason={generation.error ?? '生成回答失败'}
            next="可以重试生成，或先查看下方检索来源。"
            onRetry={retry}
          />
        )}
      </Sheet>

      {/* ── 来源区 ── */}
      <Sheet
        title="检索来源"
        note="每个来源都带册/章/页/题引用，可回溯到具体教材位置。"
      >
        {retrieval.status === 'idle' ? (
          <p className="muted" style={{ margin: 0 }}>
            提交问题后，这里会列出检索到的可溯源语料。
          </p>
        ) : retrieval.status === 'loading' ? (
          <Loading label="正在检索教材语料" />
        ) : retrieval.status === 'empty' ? (
          <Empty
            title="没有找到相关语料"
            hint="换个说法，或把版本范围改成「优先本版，可回退同册」再试。"
          />
        ) : retrieval.status === 'error' || retrieval.status === 'timeout' ? (
          <ErrorState
            reason={retrieval.error ?? '检索失败'}
            next="可以重试，或稍后再试。"
            onRetry={retry}
          />
        ) : (
          <>
            {versionMismatch ? (
              <div className="notice" role="status" style={{ marginBottom: 'var(--s-4)' }}>
                <p style={{ margin: 0 }}>
                  检测到版本差异：以下部分内容可能来自你当前版本以外的教材，
                  引用时请留意与手上课本是否一致。
                </p>
              </div>
            ) : null}
            {answerSuppressed ? (
              <p className="muted" style={{ marginTop: 0, marginBottom: 'var(--s-3)' }}>
                答案类内容已被过滤，仅展示讲解依据（防泄题）。
              </p>
            ) : null}
            <div className="source-list">
              {retrieval.hits.map((hit, index) => (
                <SourceCard key={hit.chunkId} hit={hit} index={index + 1} />
              ))}
            </div>
          </>
        )}
      </Sheet>
    </>
  )
}
