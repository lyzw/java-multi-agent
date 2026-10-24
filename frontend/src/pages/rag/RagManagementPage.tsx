/**
 * RAG 语料数据管理页（RAG-D10 管理面）
 *
 * 交互结构：租户与筛选 → 来源列表 → 选中来源的详情/审核 → 索引统计。
 *
 * ⚠️ **本页面是「管理面」，不是「检索面」**（RAG-D10 §1.1）：
 *    检索面回答「能召回什么」，管理面回答「库里有什么、哪些还没审、能不能放行」。
 *
 * ⚠️ **只渲染合法的状态流转按钮**（RAG-D07 §2.4 T-1～T-12）：
 *    「撤回」是终态（T-10）→ 不提供任何后继按钮；
 *    「已拒绝」不能直接通过（T-12）→ 只提供「重新认领」。
 *
 * ⚠️ **不提供删除入口**（RAG-D07 §8 INV-4-a）：
 *    撤回的唯一手段是 REVOKED 状态；物理 DELETE 会级联抹除历史引用，破坏可溯源性。
 *
 * ⚠️ **诚实边界**：
 *    · 准入结论（admissible）由**服务端**计算——租户权威门槛前端不知道，
 *      前端不得自行推算。
 *    · G-1～G-4 尚未在检索路径强制（RAG-D01），故「已通过校验」≠
 *      「一定能被检索到」；本页面只呈现**状态事实**，不承诺检索结果。
 *
 * ⚠️ **数据录入 / 导入**：本页提供三类写入入口——
 *    · 录入新来源（单条登记，对齐 `POST /api/v1/management/sources`）；
 *    · 批量导入（CSV/JSON 一次性导入，对齐 `POST /api/v1/management/sources/batch`）；
 *    · 校验判定（审核流转，对齐 `.../verification`）。
 *    三者的治理语义完全一致：登记结果恒为 UNVERIFIED / PENDING，登记人都写入审计。
 */

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from 'react'
import { Sheet, Loading, LoadingRows, Empty, ErrorState } from '@/shared/states'
import { useRagManagement, allowedVerificationTargets } from '@/features/rag/useRagManagement'
import { useSession } from '@/auth/session'
import {
  listRagDocuments,
  listRagChunks,
  registerRagSource,
  getRagDictionaries,
  upsertRagDictionaryEntry,
  deleteRagDictionaryEntry,
  ApiError,
} from '@/api/client'
import { isTimeoutError } from '@/api/errors'
import { PageHead, Panel, StatusPill, type PillTone } from '@/components/ui'
import {
  ChunkRoleDistribution,
  DocumentEntry,
  MaintenanceIssueBoard,
  ParseStatusDistribution,
  RetrievalProbe,
  SourceStatusDistribution,
  VocabularyDrift,
} from '@/pages/rag/RagMaintenancePanels'
import type {
  RagSource,
  RagDocument,
  RagChunk,
  RagVerificationStatus,
  RagTrustTier,
  RagVerificationMethod,
  RagStatistics,
  RagBulkSourceItem,
  RagBulkImportItemResult,
  RagDictionaryType,
  RagDictionaryTypeView,
  RagDictionaryEntry,
  RagUpsertDictionaryEntryRequest,
} from '@/api/types'
import {
  RAG_VERIFICATION_LABELS,
  RAG_TRUST_TIER_LABELS,
  RAG_VERIFICATION_METHOD_LABELS,
  RAG_LICENSE_LABELS,
} from '@/api/types'

const TENANT_STORAGE_KEY = 'rag.tenantId'

/** 读取上次使用的租户标识（仅本地记忆，不涉及任何凭据）。 */
function readStoredTenantId(): string {
  try {
    return window.localStorage.getItem(TENANT_STORAGE_KEY) ?? ''
  } catch {
    return ''
  }
}

function rememberTenantId(tenantId: string): void {
  try {
    if (tenantId === '') window.localStorage.removeItem(TENANT_STORAGE_KEY)
    else window.localStorage.setItem(TENANT_STORAGE_KEY, tenantId)
  } catch {
    // 记忆失败不影响管理操作本身，静默忽略。
  }
}

const ALL_STATUSES: RagVerificationStatus[] = [
  'PENDING',
  'IN_REVIEW',
  'VERIFIED',
  'REJECTED',
  'REVOKED',
]

const TRUST_TIERS: RagTrustTier[] = [
  'OFFICIAL_PUBLISHER',
  'OFFICIAL_TUTORIAL',
  'PEER_REVIEWED_PAPER',
  'PREPRINT',
  'UNVERIFIED',
]

const VERIFICATION_METHODS: RagVerificationMethod[] = [
  'ISBN',
  'DOI',
  'MANUAL_REVIEW',
  'OFFICIAL_HASH',
]

function formatTime(iso: string | null): string {
  if (iso === null || iso === '') return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

/* ── 标签导航 ──────────────────────────────────────────── */

type TabId = 'list' | 'entry' | 'import' | 'stats' | 'ops' | 'dict'

const TABS: { id: TabId; label: string }[] = [
  { id: 'list', label: '来源列表与审核' },
  { id: 'entry', label: '录入新来源' },
  { id: 'import', label: '批量导入' },
  { id: 'stats', label: '索引与统计' },
  { id: 'ops', label: '运维工具' },
  { id: 'dict', label: '字典维护' },
]

/**
 * 标准 ARIA tabs 条（2026-10-24 UX 评审 P1 修复）
 *
 * ⚠️ **为什么自己实现**：原先只有 `role="tab"` + `aria-selected`，缺三样东西，
 *    键盘与读屏用户都吃亏：
 *    ① 内容区没有 `role="tabpanel"`，读屏不知道「这段内容属于哪个页签」；
 *    ② 每个页签都在 Tab 序列里——6 个页签要按 6 次 Tab 才能穿过这排页签；
 *    ③ 没有方向键切换，页签条退化成纯鼠标控件。
 *
 *    故按 WAI-ARIA tabs 模式补齐：`id` / `aria-controls` / roving tabindex
 *    （当前项 tabIndex=0，其余 -1）+ ArrowLeft/ArrowRight/Home/End 切换。
 *
 * ⚠️ **激活方式选「自动」**：按方向键即切换内容。本页各页签的内容都是
 *    「读列表 / 看统计」，没有必须留在原页签完成的输入，
 *    手动激活（方向键只移焦点）反而多按一次回车。
 */
function TabList({
  idPrefix,
  ariaLabel,
  tabs,
  active,
  onChange,
}: {
  idPrefix: string
  ariaLabel: string
  tabs: ReadonlyArray<{ id: string; label: string }>
  active: string
  onChange: (id: string) => void
}): JSX.Element {
  const buttonRefs = useRef<Record<string, HTMLButtonElement | null>>({})
  // ⚠️ 方向键切换后要把焦点交给新页签：状态更新是异步的，
  //    故先记下「待聚焦的页签」，等重渲染完成后再 .focus()。
  const pendingFocus = useRef<string | null>(null)

  useEffect(() => {
    const target = pendingFocus.current
    if (target === null) return
    pendingFocus.current = null
    buttonRefs.current[target]?.focus()
  }, [active])

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, currentId: string): void => {
    const ids = tabs.map((t) => t.id)
    const index = ids.indexOf(currentId)
    if (index < 0) return
    let next: number
    switch (e.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        next = (index + 1) % ids.length
        break
      case 'ArrowLeft':
      case 'ArrowUp':
        next = (index - 1 + ids.length) % ids.length
        break
      case 'Home':
        next = 0
        break
      case 'End':
        next = ids.length - 1
        break
      default:
        return
    }
    e.preventDefault()
    const nextId = ids[next]
    if (nextId === undefined) return
    pendingFocus.current = nextId
    onChange(nextId)
  }

  return (
    <div className="tabs" role="tablist" aria-label={ariaLabel}>
      {tabs.map((t) => (
        <button
          key={t.id}
          ref={(el) => {
            buttonRefs.current[t.id] = el
          }}
          type="button"
          role="tab"
          id={`${idPrefix}-tab-${t.id}`}
          aria-controls={`${idPrefix}-panel-${t.id}`}
          aria-selected={active === t.id}
          // roving tabindex：只有当前页签留在 Tab 序列里，
          // 其余靠方向键抵达（否则一排页签要按 N 次 Tab）。
          tabIndex={active === t.id ? 0 : -1}
          className={active === t.id ? 'tab tab--active' : 'tab'}
          onClick={() => onChange(t.id)}
          onKeyDown={(e) => onKeyDown(e, t.id)}
        >
          {t.label}
        </button>
      ))}
    </div>
  )
}

function TabNav({
  active,
  onChange,
}: {
  active: TabId
  onChange: (id: TabId) => void
}): JSX.Element {
  return (
    <TabList
      idPrefix="rag-mgmt"
      ariaLabel="语料管理功能"
      tabs={TABS}
      active={active}
      onChange={(id) => onChange(id as TabId)}
    />
  )
}

/* ── 子分区 tab（详情 / 运维区内部复用的通用 tab 条）────── */
function SubTabNav({
  idPrefix,
  ariaLabel,
  tabs,
  active,
  onChange,
}: {
  /** id 前缀：必须与内容区 tabpanel 的 id 前缀一致，否则 aria-controls 断链 */
  idPrefix: string
  ariaLabel: string
  tabs: ReadonlyArray<{ id: string; label: string }>
  active: string
  onChange: (id: string) => void
}): JSX.Element {
  return (
    <TabList
      idPrefix={idPrefix}
      ariaLabel={ariaLabel}
      tabs={tabs}
      active={active}
      onChange={onChange}
    />
  )
}

/* ── 字段控件（录入/导入共用）────────────────────────── */

/**
 * 文本字段。
 *
 * ⚠️ **错误与提示必须用 aria-describedby 挂到输入框上**（同 shared/Field.tsx）：
 *    只有 `role="alert"` 的话，错误只在**出现那一刻**播报一次；
 *    用户改完再聚焦输入框时，读屏不再复述「这里错在哪」。
 *    错误文案与提示各自带 id，再由 describedBy 拼接引用。
 */
function TextField({
  id,
  label,
  value,
  onChange,
  hint,
  error,
  placeholder,
  required,
  mono,
}: {
  id: string
  label: string
  value: string
  onChange: (v: string) => void
  hint?: string
  error?: string | null
  placeholder?: string
  required?: boolean
  mono?: boolean
}): JSX.Element {
  const errorId = `${id}-error`
  const hintId = `${id}-hint`
  const describedBy =
    [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ') || undefined

  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {required ? <span className="req"> *</span> : null}
      </label>
      <input
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={mono ? 'mono' : undefined}
      />
      {hint ? (
        <p id={hintId} className="field__hint">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="field__error" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  )
}

/** 下拉选择控件（枚举类录入项使用，选项来自后台字典）。 */
function SelectField({
  id,
  label,
  value,
  options,
  onChange,
  hint,
  error,
  required,
  placeholder,
}: {
  id: string
  label: string
  value: string
  options: ReadonlyArray<{ value: string; label: string }>
  onChange: (v: string) => void
  hint?: string
  error?: string | null
  required?: boolean
  placeholder?: string
}): JSX.Element {
  // ⚠️ 同 TextField：错误/提示靠 aria-describedby 与控件关联，
  //    否则读屏只在错误出现瞬间播报一次。
  const errorId = `${id}-error`
  const hintId = `${id}-hint`
  const describedBy =
    [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ') || undefined

  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {required ? <span className="req"> *</span> : null}
      </label>
      <select
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
      >
        {placeholder !== undefined ? <option value="">{placeholder}</option> : null}
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      {hint ? (
        <p id={hintId} className="field__hint">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="field__error" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  )
}

/* ── 状态徽章 ─────────────────────────────────────────── */

function StatusChip({ source }: { source: RagSource }): JSX.Element {
  const label = RAG_VERIFICATION_LABELS[source.verificationStatus]
  const status = source.verificationStatus
  const tone: PillTone =
    status === 'VERIFIED'
      ? 'success'
      : status === 'REVOKED' || status === 'REJECTED'
        ? 'danger'
        : status === 'IN_REVIEW'
          ? 'info'
          : 'warn'
  return (
    <>
      <StatusPill tone={tone}>{label}</StatusPill>
      {source.stale ? <span className="chip">已过期</span> : null}
      {source.admissible ? <span className="chip chip--settled">准入可检索</span> : null}
    </>
  )
}

/** 来源列表项。 */
function SourceEntry({
  source,
  selected,
  onSelect,
}: {
  source: RagSource
  selected: boolean
  onSelect: (id: string) => void
}): JSX.Element {
  return (
    <div className="entry">
      <button
        type="button"
        className="rag-mgmt__pick"
        onClick={() => onSelect(source.sourceId)}
        aria-pressed={selected}
      >
        <span className="entry__title">{source.title}</span>
        <span className="entry__desc">
          {source.publisher} · {source.sourceType}
          {source.isbn !== null ? ` · ISBN ${source.isbn}` : ''}
          {source.doi !== null ? ` · DOI ${source.doi}` : ''}
        </span>
      </button>
      <div className="rag-mgmt__tags">
        <StatusChip source={source} />
        <span className="chip">{RAG_TRUST_TIER_LABELS[source.trustTier]}</span>
        <span className="chip">{RAG_LICENSE_LABELS[source.licenseStatus]}</span>
      </div>
    </div>
  )
}

/** 详情抽屉：来源元数据 + 载体 + 片段（Point 1a：拆成三个 tab，避免长页平铺）。 */
function SourceDetail({
  source,
  tenantId,
}: {
  source: RagSource
  tenantId: string
}): JSX.Element {
  const [detailTab, setDetailTab] = useState<'meta' | 'docs' | 'chunks'>('meta')
  const [documents, setDocuments] = useState<RagDocument[] | null>(null)
  const [docError, setDocError] = useState<string | null>(null)
  const [chunks, setChunks] = useState<RagChunk[] | null>(null)
  const [chunkDocId, setChunkDocId] = useState<string | null>(null)

  const loadDocuments = useCallback(() => {
    setDocuments(null)
    setDocError(null)
    const run = async (): Promise<void> => {
      try {
        const list = await listRagDocuments(tenantId, source.sourceId)
        setDocuments(list)
      } catch {
        setDocError('读取载体列表失败')
      }
    }
    void run()
  }, [tenantId, source.sourceId])

  const loadChunks = useCallback(
    (documentId: string) => {
      setChunkDocId(documentId)
      setChunks(null)
      // 点「查看片段」即从载体跳到片段 tab
      setDetailTab('chunks')
      const run = async (): Promise<void> => {
        try {
          const list = await listRagChunks(tenantId, documentId, 100)
          setChunks(list)
        } catch {
          setChunks([])
        }
      }
      void run()
    },
    [tenantId],
  )

  return (
    <>
      <SubTabNav
        idPrefix="rag-mgmt-detail"
        ariaLabel="来源详情"
        active={detailTab}
        onChange={(id) => setDetailTab(id as 'meta' | 'docs' | 'chunks')}
        tabs={[
          { id: 'meta', label: '元数据' },
          { id: 'docs', label: '载体' },
          { id: 'chunks', label: '片段' },
        ]}
      />

      {/*
        ⚠️ 内容区必须是 tabpanel：读屏用户切换页签后要能知道
          「现在读的内容属于哪个页签」，否则只剩一堆无主的表格。
          tabIndex={0} 让键盘用户能直接聚焦面板（面板内没有可聚焦元素时）。
      */}
      <div
        role="tabpanel"
        id={`rag-mgmt-detail-panel-${detailTab}`}
        aria-labelledby={`rag-mgmt-detail-tab-${detailTab}`}
        tabIndex={0}
      >
        {detailTab === 'meta' ? (
          <dl className="kv">
            <dt>来源 ID</dt>
            <dd className="mono">{source.sourceId}</dd>
            <dt>校验状态</dt>
            <dd>{RAG_VERIFICATION_LABELS[source.verificationStatus]}</dd>
            <dt>权威等级</dt>
            <dd>{RAG_TRUST_TIER_LABELS[source.trustTier]}</dd>
            <dt>校验手段</dt>
            <dd>{RAG_VERIFICATION_METHOD_LABELS[source.verificationMethod]}</dd>
            <dt>版权状态</dt>
            <dd>{RAG_LICENSE_LABELS[source.licenseStatus]}</dd>
            <dt>校验依据</dt>
            <dd>{source.verificationEvidence ?? '—'}</dd>
            <dt>决定人</dt>
            <dd className="mono">{source.verifiedBy ?? '—'}</dd>
            <dt>决定时间</dt>
            <dd>{formatTime(source.verifiedAt)}</dd>
            <dt>有效期</dt>
            <dd>{source.validDays} 天</dd>
            <dt>内容校验和</dt>
            <dd className="mono">{source.contentHash ?? '—'}</dd>
          </dl>
        ) : null}

        {detailTab === 'docs' ? (
          <div>
            <div className="btn-row" style={{ marginTop: 'var(--s-3)' }}>
              <button type="button" className="btn btn--quiet" onClick={loadDocuments}>
                加载载体列表
              </button>
            </div>

            {docError !== null ? (
              <div className="marked" role="alert" style={{ marginTop: 'var(--s-3)' }}>
                <p className="marked__label">没能完成</p>
                <p>{docError}</p>
              </div>
            ) : null}

            {documents === null ? null : documents.length === 0 ? (
              <Empty
                title="这个来源还没有载体"
                hint="登记后需上传物理文件（PDF/扫描件）并入库切分。"
              />
            ) : (
              <div className="entry-list" style={{ marginTop: 'var(--s-3)' }}>
                {documents.map((doc) => (
                  <DocumentEntry
                    key={doc.documentId}
                    document={doc}
                    tenantId={tenantId}
                    sourceStatus={source.verificationStatus}
                    onChanged={(updated) => {
                      setDocuments((prev) =>
                        prev === null
                          ? prev
                          : prev.map((d) => (d.documentId === updated.documentId ? updated : d)),
                      )
                    }}
                    onViewChunks={loadChunks}
                  />
                ))}
              </div>
            )}
          </div>
        ) : null}

        {detailTab === 'chunks' ? (
          chunkDocId === null ? (
            <p className="muted" style={{ margin: 'var(--s-3) 0' }}>
              请先在「载体」中选择一条载体，再查看其片段。
            </p>
          ) : (
            <div style={{ marginTop: 'var(--s-3)' }}>
              <p className="sheet__note">
                片段摘要（最多 100 条）· 当前载体 <span className="mono">{chunkDocId}</span>
              </p>
              {chunks === null ? (
                <Loading label="正在读取片段" />
              ) : chunks.length === 0 ? (
                <Empty title="该载体还没有片段" hint="需要先经过解析与切分入库。" />
              ) : (
                <div className="entry-list">
                  {chunks.map((c) => (
                    <div key={c.chunkId} className="entry">
                      {/* ⚠️ 标题与描述要包进 .entry__body：.entry 是 flex **行**布局，
                          直接子元素会并排——描述会被挤到标题右侧。 */}
                      <div className="entry__body">
                        <span className="entry__title">
                          第 {c.pageNumber} 页 · {c.sectionPath}
                        </span>
                        <span className="entry__desc">
                          {c.role}
                          {c.exerciseNo !== null ? ` · 第 ${c.exerciseNo} 题` : ''} ·{' '}
                          {c.charLength} 字
                        </span>
                      </div>
                      <span className="chip">{c.hasEmbedding ? '已建向量' : '缺向量'}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )
        ) : null}
      </div>
    </>
  )
}

/** 审核动作表单：只列出合法的目标状态。 */
function VerificationForm({
  source,
  onSubmit,
  busy,
  canSubmit,
}: {
  source: RagSource
  onSubmit: (payload: {
    targetStatus: RagVerificationStatus
    evidenceRef: string
    trustTier: RagTrustTier | null
    verificationMethod: RagVerificationMethod
  }) => void
  busy: boolean
  canSubmit: boolean
}): JSX.Element {
  const targets = allowedVerificationTargets(source.verificationStatus)
  const [target, setTarget] = useState<RagVerificationStatus | null>(targets[0] ?? null)
  const [evidence, setEvidence] = useState('')
  const [tier, setTier] = useState<RagTrustTier>('OFFICIAL_TUTORIAL')
  const [method, setMethod] = useState<RagVerificationMethod>('ISBN')

  if (targets.length === 0) {
    return (
      <div className="marked marked--calm">
        <p className="marked__label">已撤回</p>
        <p style={{ margin: 0 }}>
          撤回是终态，不能再流转。若这份语料需要重新准入，请<b>重新登记一条来源</b>——
          保留原记录才能回答「这个来源为什么不见了」。
        </p>
      </div>
    )
  }

  const needTier = target === 'VERIFIED'
  const ready = target !== null && evidence.trim() !== '' && canSubmit
  const submitDisabled = !ready || busy

  return (
    <form
      onSubmit={(e: FormEvent<HTMLFormElement>) => {
        e.preventDefault()
        if (!ready || target === null) return
        onSubmit({
          targetStatus: target,
          evidenceRef: evidence.trim(),
          trustTier: needTier ? tier : null,
          verificationMethod: method,
        })
      }}
      noValidate
    >
      <div className="rag-row">
        <div className="field" style={{ margin: 0, maxWidth: 220 }}>
          <label htmlFor="rag-mgmt-target">下一步</label>
          <select
            id="rag-mgmt-target"
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

        {needTier ? (
          <div className="field" style={{ margin: 0, maxWidth: 240 }}>
            <label htmlFor="rag-mgmt-tier">判定权威等级</label>
            <select
              id="rag-mgmt-tier"
              value={tier}
              onChange={(e) => setTier(e.target.value as RagTrustTier)}
            >
              {TRUST_TIERS.map((t) => (
                <option key={t} value={t}>
                  {RAG_TRUST_TIER_LABELS[t]}
                </option>
              ))}
            </select>
          </div>
        ) : null}

        <div className="field" style={{ margin: 0, maxWidth: 220 }}>
          <label htmlFor="rag-mgmt-method">校验手段</label>
          <select
            id="rag-mgmt-method"
            value={method}
            onChange={(e) => setMethod(e.target.value as RagVerificationMethod)}
          >
            {VERIFICATION_METHODS.map((m) => (
              <option key={m} value={m}>
                {RAG_VERIFICATION_METHOD_LABELS[m]}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="field">
        <label htmlFor="rag-mgmt-evidence">校验依据（必填）</label>
        <input
          id="rag-mgmt-evidence"
          value={evidence}
          onChange={(e) => setEvidence(e.target.value)}
          placeholder="例如 ISBN:https://...@2026-10-22"
        />
        <p className="field__hint">
          每次流转都会留痕（谁、基于什么证据、何时）。没有依据的「已通过」只是一个无法复核的断言。
        </p>
      </div>

      <div className="btn-row">
        <button type="submit" className="btn" disabled={submitDisabled}>
          {busy ? '提交中…' : '提交判定'}
        </button>
        {!canSubmit ? <span className="muted">请先填写决定人与校验依据。</span> : null}
      </div>
    </form>
  )
}

/** 统计看板。 */
function StatisticsBoard({ stats }: { stats: RagStatistics }): JSX.Element {
  const storedButHidden = stats.totalChunks - stats.retrievableChunks
  return (
    <>
      <dl className="kv">
        <dt>来源总数</dt>
        <dd>{stats.totalSources}</dd>
        <dt>已通过校验</dt>
        <dd>{stats.verifiedSources}</dd>
        <dt>当前准入</dt>
        <dd>{stats.admissibleSources}</dd>
        <dt>文档载体</dt>
        <dd>{stats.totalDocuments}</dd>
        <dt>片段总数</dt>
        <dd>{stats.totalChunks}</dd>
        <dt>可检索片段</dt>
        <dd>{stats.retrievableChunks}</dd>
        <dt>已入库但不可检索</dt>
        <dd>{storedButHidden}</dd>
        <dt>缺向量片段</dt>
        <dd>
          {stats.chunksMissingEmbedding}（{Math.round(stats.missingEmbeddingRatio * 100)}%）
        </dd>
      </dl>

      {storedButHidden > 0 ? (
        <div className="marked marked--calm" style={{ marginTop: 'var(--s-3)' }}>
          <p className="marked__label">已入库 ≠ 可检索</p>
          <p style={{ margin: 0 }}>
            有 {storedButHidden} 个片段已入库但当前不可检索——来源未通过校验、校验过期、版权未授权或权威等级不达门槛都会造成。
          </p>
        </div>
      ) : null}

      {stats.chunksMissingEmbedding > 0 ? (
        <div className="marked marked--calm" style={{ marginTop: 'var(--s-3)' }}>
          <p className="marked__label">缺向量</p>
          <p style={{ margin: 0 }}>
            有 {stats.chunksMissingEmbedding} 个片段已入库但没有向量——这些片段<b>召不回</b>
            （静默漏召回），需要重跑索引。
          </p>
        </div>
      ) : null}

      <ChunkRoleDistribution entries={stats.chunksByRole ?? {}} />
      <SourceStatusDistribution entries={stats.sourcesByVerificationStatus ?? {}} />
      <ParseStatusDistribution entries={stats.documentsByParseStatus ?? {}} />
    </>
  )
}

/* ── 录入新来源（单条登记）──────────────────────────── */

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/
const HASH_RE = /^[0-9a-fA-F]{64}$/

/**
 * 批量导入的单次行数上限（与服务端 `BulkRegisterRequest.sources` 的 `@Size(max=100)` 一致）。
 *
 * ⚠️ **前后端必须同一个数**：服务端超限会让**整批** 400，
 * 与「逐条成败」的承诺冲突，且用户看不出是哪一行超了。
 * 故前端在提交前就拦，并给出可执行的提示（拆分后分批导入）。
 */
const MAX_BULK_ROWS = 100

function RegisterSourceForm({ tenantId }: { tenantId: string }): JSX.Element {
  const [sourceType, setSourceType] = useState('')
  const [title, setTitle] = useState('')
  const [publisher, setPublisher] = useState('')
  const [isbn, setIsbn] = useState('')
  const [doi, setDoi] = useState('')
  const [objectRef, setObjectRef] = useState('')
  const [contentHash, setContentHash] = useState('')
  const [registeredBy, setRegisteredBy] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [status, setStatus] = useState<'idle' | 'submitting' | 'success' | 'error' | 'timeout'>(
    'idle',
  )
  const [result, setResult] = useState<RagSource | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  // ⚠️ 来源类型来自后台字典（SOURCE_TYPE），允许在「字典维护」中维护。
  // 字典未加载或为空时回落到自由文本，保证录入不被阻断。
  const [sourceTypeOptions, setSourceTypeOptions] = useState<RagDictionaryEntry[]>([])
  const [sourceTypeDictReady, setSourceTypeDictReady] = useState(false)

  useEffect(() => {
    let cancelled = false
    const run = async (): Promise<void> => {
      try {
        const dicts = await getRagDictionaries(tenantId.trim())
        if (cancelled) return
        const sourceTypes =
          dicts.find((d) => d.type === 'SOURCE_TYPE')?.entries.filter((e) => e.enabled) ?? []
        setSourceTypeOptions(sourceTypes)
        setSourceTypeDictReady(true)
      } catch {
        // ⚠️ 字典读取失败时回落到自由文本，不阻断录入流程。
        if (!cancelled) setSourceTypeDictReady(false)
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [tenantId])

  const validate = useCallback((): Record<string, string> => {
    const e: Record<string, string> = {}
    if (sourceType.trim() === '') e.sourceType = '来源类型不能为空'
    if (title.trim() === '') e.title = '标题不能为空'
    if (publisher.trim() === '') e.publisher = '出版社不能为空'
    if (isbn.trim() === '' && doi.trim() === '') e.isbn = 'ISBN 与 DOI 至少提供一个'
    if (contentHash.trim() !== '' && !HASH_RE.test(contentHash.trim()))
      e.contentHash = '内容校验和必须是 64 位十六进制 SHA-256'
    if (registeredBy.trim() === '') e.registeredBy = '登记人不能为空（登记必须留痕）'
    else if (!UUID_RE.test(registeredBy.trim())) e.registeredBy = '登记人必须是合法 UUID'
    return e
  }, [sourceType, title, publisher, isbn, doi, contentHash, registeredBy])

  const onSubmit = useCallback(
    (e: FormEvent<HTMLFormElement>) => {
      e.preventDefault()
      const eMap = validate()
      setErrors(eMap)
      if (Object.keys(eMap).length > 0) return
      setStatus('submitting')
      setResult(null)
      setMessage(null)
      const payload = {
        sourceType: sourceType.trim(),
        title: title.trim(),
        publisher: publisher.trim(),
        isbn: isbn.trim() === '' ? null : isbn.trim(),
        doi: doi.trim() === '' ? null : doi.trim(),
        objectRef: objectRef.trim() === '' ? null : objectRef.trim(),
        contentHash: contentHash.trim() === '' ? null : contentHash.trim(),
        registeredBy: registeredBy.trim(),
      }
      const run = async (): Promise<void> => {
        try {
          const created = await registerRagSource(tenantId.trim(), payload)
          setResult(created)
          setStatus('success')
          // ⚠️ 清空表单，便于连续登记下一条
          setSourceType('')
          setTitle('')
          setPublisher('')
          setIsbn('')
          setDoi('')
          setObjectRef('')
          setContentHash('')
        } catch (cause) {
          if (cause instanceof ApiError && isTimeoutError(cause.normalized)) {
            setStatus('timeout')
            setMessage(cause.normalized.message)
          } else if (cause instanceof ApiError) {
            setStatus('error')
            setMessage(cause.normalized.message)
          } else {
            setStatus('error')
            setMessage('登记失败，请稍后重试')
          }
        }
      }
      void run()
    },
    [validate, tenantId, sourceType, title, publisher, isbn, doi, objectRef, contentHash, registeredBy],
  )

  return (
    <Sheet
      title="录入新来源"
      note="登记一条教材或教辅来源。登记结果恒为「待校验 / 未校验」，须经审核流转才能进入可检索索引。登记人必须填写以留痕。"
    >
      <form onSubmit={onSubmit} noValidate>
        <div className="rag-row">
          {sourceTypeDictReady && sourceTypeOptions.length > 0 ? (
            <SelectField
              id="reg-sourceType"
              label="来源类型"
              required
              value={sourceType}
              onChange={setSourceType}
              placeholder="请选择来源类型"
              options={sourceTypeOptions.map((o) => ({ value: o.label, label: o.label }))}
              hint="选项来自后台字典，可在「字典维护」中增删。"
              error={errors.sourceType}
            />
          ) : (
            <TextField
              id="reg-sourceType"
              label="来源类型"
              required
              value={sourceType}
              onChange={setSourceType}
              placeholder="教材 / 教辅 / 论文"
              error={errors.sourceType}
            />
          )}
          <TextField
            id="reg-title"
            label="标题"
            required
            value={title}
            onChange={setTitle}
            placeholder="例如 人教版八年级上册数学"
            error={errors.title}
          />
        </div>
        <TextField
          id="reg-publisher"
          label="出版社 / 出版方"
          required
          value={publisher}
          onChange={setPublisher}
          placeholder="人民教育出版社"
          error={errors.publisher}
        />
        <div className="rag-row">
          <TextField
            id="reg-isbn"
            label="ISBN"
            value={isbn}
            onChange={setIsbn}
            placeholder="9787xxxxxxxx"
            error={errors.isbn}
            mono
          />
          <TextField
            id="reg-doi"
            label="DOI"
            value={doi}
            onChange={setDoi}
            placeholder="10.xxxx/xxxx"
            mono
          />
        </div>
        <div className="rag-row">
          <TextField
            id="reg-objectRef"
            label="对象存储引用"
            value={objectRef}
            onChange={setObjectRef}
            placeholder="obj://bucket/path.pdf（可空）"
            mono
          />
          <TextField
            id="reg-contentHash"
            label="内容校验和"
            value={contentHash}
            onChange={setContentHash}
            placeholder="64 位十六进制 SHA-256（可空）"
            error={errors.contentHash}
            mono
          />
        </div>
        <TextField
          id="reg-registeredBy"
          label="登记人（UUID）"
          required
          value={registeredBy}
          onChange={setRegisteredBy}
          placeholder="审核员 / 采编的主体 UUID"
          error={errors.registeredBy}
          mono
        />

        <div className="btn-row" style={{ marginTop: 'var(--s-2)' }}>
          <button type="submit" className="btn" disabled={status === 'submitting'}>
            {status === 'submitting' ? '提交中…' : '登记来源'}
          </button>
        </div>

        {status === 'success' && result !== null ? (
          <div className="marked marked--calm" role="status" style={{ marginTop: 'var(--s-3)' }}>
            <p className="marked__label">已登记</p>
            <p style={{ margin: 0 }}>
              来源「{result.title}」已登记，状态：{RAG_VERIFICATION_LABELS[result.verificationStatus]}。
              来源 ID：<span className="mono">{result.sourceId}</span>
            </p>
          </div>
        ) : null}

        {status === 'error' || status === 'timeout' ? (
          <div className="marked" role="alert" style={{ marginTop: 'var(--s-3)' }}>
            <p className="marked__label">没能登记</p>
            <p style={{ margin: 0 }}>{message}</p>
          </div>
        ) : null}
      </form>
    </Sheet>
  )
}

/* ── 批量导入（CSV / JSON）──────────────────────────── */

interface ImportRow {
  sourceType: string
  title: string
  publisher: string
  isbn: string
  doi: string
  objectRef: string
  contentHash: string
}

/** 把任意对象归一成 ImportRow（缺字段按空串处理）。 */
function normalizeRaw(obj: Record<string, unknown>): ImportRow {
  const str = (v: unknown): string => (typeof v === 'string' ? v.trim() : v == null ? '' : String(v))
  return {
    sourceType: str(obj.sourceType),
    title: str(obj.title),
    publisher: str(obj.publisher),
    isbn: str(obj.isbn),
    doi: str(obj.doi),
    objectRef: str(obj.objectRef),
    contentHash: str(obj.contentHash),
  }
}

/** 基础 CSV 解析：支持引号包裹字段与字段内逗号。 */function parseCsvLine(line: string): string[] {
  const out: string[] = []
  let cur = ''
  let inQuotes = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (inQuotes) {
      if (c === '"') {
        if (line[i + 1] === '"') {
          cur += '"'
          i++
        } else {
          inQuotes = false
        }
      } else {
        cur += c
      }
    } else if (c === '"') {
      inQuotes = true
    } else if (c === ',') {
      out.push(cur)
      cur = ''
    } else {
      cur += c
    }
  }
  out.push(cur)
  return out
}

function parseImportText(text: string): { rows: ImportRow[]; error?: string } {
  const trimmed = text.trim()
  if (trimmed === '') return { rows: [], error: '请输入或上传 CSV / JSON 数据' }

  // 先尝试 JSON
  try {
    const parsed = JSON.parse(trimmed)
    const arr: unknown[] = Array.isArray(parsed) ? parsed : [parsed]
    if (arr.length === 0) return { rows: [], error: 'JSON 数组为空' }
    return {
      rows: arr.map((item) => {
        if (typeof item !== 'object' || item === null) {
          throw new Error('JSON 每行的元素必须是对象')
        }
        return normalizeRaw(item as Record<string, unknown>)
      }),
    }
  } catch (jsonErr) {
    // JSON 失败 → 退化为 CSV
    if (jsonErr instanceof SyntaxError && /JSON/.test(jsonErr.message)) {
      // 继续尝试 CSV
    } else if (jsonErr instanceof Error && jsonErr.message === 'JSON 每行的元素必须是对象') {
      return { rows: [], error: jsonErr.message }
    }
  }

  const lines = trimmed.split(/\r?\n/).filter((l) => l.trim() !== '')
  if (lines.length < 2) return { rows: [], error: 'CSV 需要表头行加至少一行数据' }
  const headers = parseCsvLine(lines[0] ?? '').map((h) => h.trim())
  const rows: ImportRow[] = []
  for (let i = 1; i < lines.length; i++) {
    const cells = parseCsvLine(lines[i] ?? '')
    const obj: Record<string, string> = {}
    headers.forEach((h, idx) => {
      obj[h] = (cells[idx] ?? '').trim()
    })
    rows.push(normalizeRaw(obj))
  }
  if (rows.length === 0) return { rows: [], error: 'CSV 没有数据行' }
  return { rows }
}

/** 单行校验：返回错误文案数组（空数组表示通过）。 */
function validateRow(row: ImportRow): string[] {
  const errs: string[] = []
  if (row.sourceType === '') errs.push('来源类型为空')
  if (row.title === '') errs.push('标题为空')
  if (row.publisher === '') errs.push('出版社为空')
  if (row.isbn === '' && row.doi === '') errs.push('ISBN 与 DOI 至少提供一个')
  if (row.contentHash !== '' && !HASH_RE.test(row.contentHash))
    errs.push('内容校验和需为 64 位十六进制')
  return errs
}

const SAMPLE_JSON = `[
  {
    "sourceType": "教材",
    "title": "人教版八年级上册数学",
    "publisher": "人民教育出版社",
    "isbn": "9787100000000",
    "doi": null,
    "objectRef": "obj://book-8a-math.pdf",
    "contentHash": "${'a'.repeat(64)}"
  },
  {
    "sourceType": "教辅",
    "title": "初中物理实验探究（华东师大版）",
    "publisher": "华东师范大学出版社",
    "isbn": null,
    "doi": "10.1234/phys-exp-2026",
    "objectRef": "obj://phys-exp.pdf",
    "contentHash": "${'b'.repeat(64)}"
  }
]`

function BulkImportPanel({ tenantId }: { tenantId: string }): JSX.Element {
  const { bulk, bulkRegister, resetBulk } = useRagManagement()
  const [registeredBy, setRegisteredBy] = useState('')
  const [byError, setByError] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [rows, setRows] = useState<ImportRow[] | null>(null)
  const [rowErrors, setRowErrors] = useState<(string[] | null)[]>([])
  const [parseError, setParseError] = useState<string | null>(null)
  // ⚠️ 文件 input 本身不可见（sr-only），键盘操作要靠外层 label 转发点击，故留引用。
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const tenantReady = tenantId.trim().length > 0

  const handleParse = useCallback(() => {
    const { rows: parsed, error } = parseImportText(text)
    if (error !== undefined) {
      setParseError(error)
      setRows(null)
      setRowErrors([])
      return
    }
    // ⚠️ 超限在**解析后立即**拦下并给出可执行提示：
    //    否则用户填完表点导入，只得到一条来自服务端的 400，
    //    既不知道超了多少，也不知道该拆成几批。
    if (parsed.length > MAX_BULK_ROWS) {
      setParseError(
        `共解析出 ${parsed.length} 行，超过单次导入上限 ${MAX_BULK_ROWS} 行。` +
          '请拆分后分批导入。',
      )
      setRows(null)
      setRowErrors([])
      return
    }
    setParseError(null)
    setRows(parsed)
    setRowErrors(parsed.map((r) => (validateRow(r).length > 0 ? validateRow(r) : null)))
  }, [text])

  const onFile = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file === undefined) return
    const reader = new FileReader()
    reader.onload = () => {
      setText(String(reader.result ?? ''))
      setRows(null)
      setRowErrors([])
      setParseError(null)
    }
    reader.readAsText(file)
    // ⚠️ 允许重复选择同一文件
    e.target.value = ''
  }, [])

  /**
   * 待提交的行。
   *
   * ⚠️⚠️ **提交全部行，不只提交客户端校验通过的行**（修复前的真实缺陷）：
   *   服务端响应里的 `index` 是**提交数组内**的下标。
   *   修复前前端先过滤掉不合法行再提交 → 提交数组的下标空间被压缩 →
   *   服务端返回的 `index` 与用户**原始行号错位**：
   *   用户原第 4 行失败，界面却显示「行号 3 失败」。
   *   而这个特性的全部价值就是「让用户逐行核对」——行号错位直接打掉它。
   *
   *   改为提交全部行后：服务端逐行独立判定成败，
   *   返回的 `index` 与用户原始行号**严格一致**。
   *   客户端校验退化为「标红提示」的体验辅助，不再决定提交内容。
   */
  const submittedItems: RagBulkSourceItem[] = useMemo(() => {
    if (rows === null) return []
    return rows.map((r) => ({
      sourceType: r.sourceType,
      title: r.title,
      publisher: r.publisher,
      isbn: r.isbn === '' ? null : r.isbn,
      doi: r.doi === '' ? null : r.doi,
      objectRef: r.objectRef === '' ? null : r.objectRef,
      contentHash: r.contentHash === '' ? null : r.contentHash,
    }))
  }, [rows])

  /**
   * 客户端校验通过的行数（**仅用于提示**）。
   *
   * ⚠️ 不参与提交内容的选择：提交的是**全部行**，
   *    否则服务端返回的 index 会与用户原始行号错位（见 {@link submittedItems}）。
   */
  const validCount = useMemo(
    () => (rows === null ? 0 : rowErrors.filter((e) => e === null).length),
    [rows, rowErrors],
  )

  const handleImport = useCallback(() => {
    if (!tenantReady) return
    if (registeredBy.trim() === '') {
      setByError('请填写登记人 UUID（整批统一留痕）')
      return
    }
    if (!UUID_RE.test(registeredBy.trim())) {
      setByError('登记人必须是合法 UUID')
      return
    }
    setByError(null)
    if (submittedItems.length === 0) {
      setParseError('没有可导入的行：请先「解析并校验」，并修正标红的行。')
      return
    }
    // ⚠️ 上限在**提交前**拦截：服务端 @Size(max=100) 会让整批返回 400，
    //    而批量端点的语义是「逐条成败」——整批 400 既说不清是哪一行超了，
    //    也让「失败行不影响其它行」的承诺落空。
    if (submittedItems.length > MAX_BULK_ROWS) {
      setParseError(
        `共解析出 ${submittedItems.length} 行，超过单次导入上限 ${MAX_BULK_ROWS} 行。` +
          '请拆分后分批导入。',
      )
      return
    }
    bulkRegister(tenantId.trim(), registeredBy.trim(), submittedItems)
  }, [tenantReady, registeredBy, submittedItems, bulkRegister, tenantId])

  const resultTable = (items: RagBulkImportItemResult[]): JSX.Element => (
    <div className="table-wrap" style={{ marginTop: 'var(--s-3)' }}>
      <table>
        <thead>
          <tr>
            <th scope="col">行号</th>
            <th scope="col">结果</th>
            <th scope="col">来源 ID</th>
            <th scope="col">说明</th>
          </tr>
        </thead>
        <tbody>
          {items.map((it) => (
            <tr key={it.index}>
              <td className="mono">{it.index + 1}</td>
              <td>
                {it.ok ? (
                  <span className="chip chip--settled">成功</span>
                ) : (
                  <span className="chip chip--mark">失败</span>
                )}
              </td>
              <td className="mono">{it.sourceId ?? '—'}</td>
              <td>{it.error ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )

  return (
    <Sheet
      title="批量导入语料来源"
      note="按 CSV 或 JSON 一次性导入多条来源（上限 100 条）。每行独立成败：失败行不影响其它行，结果逐条展示。登记人（UUID）对整批统一留痕。"
    >
      {!tenantReady ? (
        <div className="marked" role="alert">
          <p className="marked__label">请先填写租户</p>
          <p style={{ margin: 0 }}>批量导入按租户隔离，顶部未填写租户时无法导入。</p>
        </div>
      ) : null}

      <div className="field" style={{ maxWidth: 460 }}>
        <label htmlFor="bulk-registeredBy">登记人（UUID，整批统一留痕）</label>
        <input
          id="bulk-registeredBy"
          value={registeredBy}
          onChange={(e) => setRegisteredBy(e.target.value)}
          placeholder="采编 / 审核的主体 UUID"
          className="mono"
          aria-invalid={byError ? true : undefined}
          aria-describedby={byError ? 'bulk-registeredBy-error' : undefined}
        />
        {/* ⚠️ 错误文案要带 id 并被 aria-describedby 引用：
            否则读屏只在报错瞬间播报一次，改完再聚焦就不知道「为什么被拒」。 */}
        {byError ? (
          <p id="bulk-registeredBy-error" className="field__error" role="alert">
            {byError}
          </p>
        ) : null}
      </div>

      <div className="field">
        <label htmlFor="bulk-input">数据（CSV 或 JSON 数组）</label>
        <textarea
          id="bulk-input"
          className="mono"
          style={{ minHeight: 160 }}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={'每行一条；JSON 形如 [{ "sourceType": "教材", "title": "...", "publisher": "...", "isbn": "..." }]'}
        />
        <p className="field__hint">
          CSV 需表头行：<code>sourceType,title,publisher,isbn,doi,objectRef,contentHash</code>。
          ISBN / DOI 至少提供一个；contentHash 可空，若提供须为 64 位十六进制。
        </p>
      </div>

      <div className="btn-row">
        <button type="button" className="btn" onClick={handleParse} disabled={text.trim() === ''}>
          解析并校验
        </button>
        {/*
          ⚠️ **为什么 input 用 .sr-only 而不是 display:none**（2026-10-24 UX 评审 P0）：
            display:none 会让 input 同时退出**无障碍树**与**焦点序列**，
            外层 label 也不可聚焦——这个「看起来像按钮」的东西键盘永远 Tab 不到，
            读屏完全感知不到。
            .sr-only 是 clip 式视觉隐藏：看不见，但**留在无障碍树里**。

          故把 label 本身做成真正的可聚焦控件（tabIndex=0 + role=button），
          并用键盘事件把 Enter / Space 转发给 input.click()——
          label 本身不是按钮，原生不会响应这两个键。
          input 设 tabIndex={-1}：避免与 label 形成两个 Tab 停靠点，
          且 input 被聚焦时没有可见焦点环（它是 clip 隐藏的）。
        */}
        <label
          className="btn btn--quiet"
          htmlFor="rag-mgmt-bulk-file"
          tabIndex={0}
          role="button"
          style={{ cursor: 'pointer' }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              fileInputRef.current?.click()
            }
          }}
        >
          上传文件
          <input
            ref={fileInputRef}
            id="rag-mgmt-bulk-file"
            type="file"
            accept=".json,.csv,application/json,text/csv"
            onChange={onFile}
            className="sr-only"
            tabIndex={-1}
          />
        </label>
        <button type="button" className="btn btn--quiet" onClick={() => setText(SAMPLE_JSON)}>
          填充示例
        </button>
      </div>

      {parseError !== null ? (
        <div className="marked" role="alert" style={{ marginTop: 'var(--s-3)' }}>
          <p className="marked__label">没能解析</p>
          <p style={{ margin: 0 }}>{parseError}</p>
        </div>
      ) : null}

      {rows !== null ? (
        <div style={{ marginTop: 'var(--s-4)' }}>
          <div className="status-line">
            <span className="muted">
              共 {rows.length} 行，待修正 {rows.length - validCount} 行（提交时仍会带上全部 {rows.length} 行，由服务端逐行判定成败）
            </span>
          </div>
          <div className="table-wrap" style={{ marginTop: 'var(--s-2)' }}>
            <table>
              <thead>
                <tr>
                  <th scope="col">行号</th>
                  <th scope="col">来源类型</th>
                  <th scope="col">标题</th>
                  <th scope="col">出版社</th>
                  <th scope="col">ISBN / DOI</th>
                  <th scope="col">校验</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => {
                  const errs = rowErrors[i]
                  const ok = errs === null
                  return (
                    <tr key={i} className={ok ? '' : 'import-row--invalid'}>
                      <td className="mono">{i + 1}</td>
                      <td>{r.sourceType || '—'}</td>
                      <td>{r.title || '—'}</td>
                      <td>{r.publisher || '—'}</td>
                      <td className="mono">
                        {r.isbn || r.doi || '—'}
                      </td>
                      <td>
                        {ok ? (
                          <span className="chip chip--settled">通过</span>
                        ) : (
                          <span className="chip chip--mark" title={errs?.join('；')}>
                            错误 {errs?.length}
                          </span>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          {rows.length - validCount > 0 ? (
            <ul className="field__errors" style={{ marginTop: 'var(--s-2)' }}>
              {rowErrors.map((errs, i) =>
                errs === null ? null : (
                  <li key={i}>
                    第 {i + 1} 行：{errs.join('；')}
                  </li>
                ),
              )}
            </ul>
          ) : null}

          <div className="btn-row" style={{ marginTop: 'var(--s-3)' }}>
            <button
              type="button"
              className="btn"
              onClick={handleImport}
              disabled={!tenantReady || bulk.status === 'submitting' || submittedItems.length === 0}
            >
              {bulk.status === 'submitting' ? '导入中…' : `导入 ${submittedItems.length} 条`}
            </button>
          </div>
        </div>
      ) : null}

      {bulk.status === 'submitting' ? (
        <Loading label="正在批量导入" />
      ) : null}

      {bulk.status === 'error' || bulk.status === 'timeout' ? (
        <div className="marked" role="alert" style={{ marginTop: 'var(--s-3)' }}>
          <p className="marked__label">导入失败</p>
          <p style={{ margin: 0 }}>{bulk.error}</p>
        </div>
      ) : null}

      {bulk.status === 'success' && bulk.response !== null ? (
        <div style={{ marginTop: 'var(--s-4)' }}>
          <div className="status-line">
            <span className="muted">
              总数 {bulk.response.total} · 成功{' '}
              <b className="settled">{bulk.response.succeeded}</b> · 失败{' '}
              <b className="mark">{bulk.response.failed}</b>
            </span>
            <button type="button" className="btn btn--quiet" onClick={resetBulk}>
              清空结果
            </button>
          </div>
          {resultTable(bulk.response.items)}
        </div>
      ) : null}
    </Sheet>
  )
}

/* ── 字典维护（Point 2：枚举下拉的数据源，可由前端维护）──────── */

const DICT_TYPE_LABELS: Record<RagDictionaryType, string> = {
  SOURCE_TYPE: '来源类型',
  TRUST_TIER: '权威等级',
  VERIFICATION_METHOD: '校验手段',
  LICENSE_STATUS: '版权状态',
}

/**
 * 字典维护页：列出全部字典类型与条目。
 *
 * ⚠️ 两类字典区别对待：
 * - 动态字典（SOURCE_TYPE）可在此增删改；
 * - 派生字典（权威等级 / 校验手段 / 版权状态）由后端枚举派生、只读——
 *   它们参与准入判定，前端改了会漂移治理语义，故不提供编辑入口。
 */
function DictionaryMaintenance({ tenantId }: { tenantId: string }): JSX.Element {
  const [dicts, setDicts] = useState<RagDictionaryTypeView[] | null>(null)
  const [status, setStatus] = useState<'loading' | 'ok' | 'error' | 'timeout'>('loading')
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(() => {
    setStatus('loading')
    setError(null)
    getRagDictionaries(tenantId.trim())
      .then((d) => {
        setDicts(d)
        setStatus('ok')
      })
      .catch((cause: unknown) => {
        if (cause instanceof ApiError && isTimeoutError(cause.normalized)) {
          setStatus('timeout')
          setError(cause.normalized.message)
        } else if (cause instanceof ApiError) {
          setStatus('error')
          setError(cause.normalized.message)
        } else {
          setStatus('error')
          setError('读取字典失败，请稍后重试')
        }
      })
  }, [tenantId])

  useEffect(() => {
    reload()
  }, [reload])

  if (status === 'loading' && dicts === null) {
    return (
      <Sheet title="字典维护" note="枚举下拉项由后台字典提供。动态字典（来源类型）可在此增删改；派生字典（权威等级 / 校验手段 / 版权状态）只读。">
        <Loading label="正在读取字典" />
      </Sheet>
    )
  }

  if (status === 'error' || status === 'timeout') {
    return (
      <Sheet title="字典维护" note="枚举下拉项由后台字典提供。">
        <ErrorState
          reason={error ?? '读取字典失败'}
          next="可以重新读取；若持续失败，请确认租户标识是否正确、后端是否已启动。"
          onRetry={reload}
          retryLabel="重新读取"
        />
      </Sheet>
    )
  }

  const views = dicts ?? []
  return (
    <Sheet
      title="字典维护"
      note="枚举下拉项由后台字典提供。动态字典（来源类型）可在此增删改；派生字典（权威等级 / 校验手段 / 版权状态）由系统枚举派生，只读。"
    >
      {views.length === 0 ? (
        <Empty title="暂无可维护的字典" hint="后端未返回任何字典类型。" />
      ) : (
        <>
          {views.map((v) => (
            <DictionaryTypeSection key={v.type} view={v} tenantId={tenantId} onChanged={reload} />
          ))}
        </>
      )}
    </Sheet>
  )
}

/** 单个字典类型的编辑区（仅可编辑类型开放增删）。 */
function DictionaryTypeSection({
  view,
  tenantId,
  onChanged,
}: {
  view: RagDictionaryTypeView
  tenantId: string
  onChanged: () => void
}): JSX.Element {
  const [draft, setDraft] = useState<RagUpsertDictionaryEntryRequest>({
    code: '',
    label: '',
    sortOrder: 0,
    enabled: true,
  })
  const [editingCode, setEditingCode] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const startEdit = (entry: RagDictionaryEntry): void => {
    setEditingCode(entry.code)
    setDraft({ code: entry.code, label: entry.label, sortOrder: entry.sortOrder, enabled: entry.enabled })
    setErr(null)
  }

  const submit = (): void => {
    if (draft.code.trim() === '' || draft.label.trim() === '') {
      setErr('编码与名称均必填')
      return
    }
    setBusy(true)
    setErr(null)
    upsertRagDictionaryEntry(tenantId.trim(), view.type, {
      code: draft.code.trim(),
      label: draft.label.trim(),
      sortOrder: draft.sortOrder,
      enabled: draft.enabled,
    })
      .then(() => {
        setDraft({ code: '', label: '', sortOrder: 0, enabled: true })
        setEditingCode(null)
        onChanged()
      })
      .catch((cause: unknown) => {
        if (cause instanceof ApiError) setErr(cause.normalized.message)
        else setErr('保存失败，请稍后重试')
      })
      .finally(() => setBusy(false))
  }

  const remove = (code: string): void => {
    setBusy(true)
    setErr(null)
    deleteRagDictionaryEntry(tenantId.trim(), view.type, code)
      .then(() => onChanged())
      .catch((cause: unknown) => {
        if (cause instanceof ApiError) setErr(cause.normalized.message)
        else setErr('删除失败，请稍后重试')
      })
      .finally(() => setBusy(false))
  }

  return (
    <section className="dict-type">
      <header className="dict-type__head">
        <h3>{DICT_TYPE_LABELS[view.type] ?? view.type}</h3>
        {view.editable ? (
          <span className="chip chip--settled">可维护</span>
        ) : (
          <span className="chip">只读</span>
        )}
      </header>

      <ul className="dict-list">
        {view.entries.length === 0 ? (
          <li className="dict-empty">（暂无条目）</li>
        ) : (
          view.entries.map((e) => (
            <li key={e.code} className="dict-item">
              <span className="dict-item__code">{e.code}</span>
              <span className="dict-item__label">{e.label}</span>
              {!e.enabled ? <span className="chip">已停用</span> : null}
              {view.editable ? (
                <span className="dict-add__row">
                  <button type="button" className="link-btn" disabled={busy} onClick={() => startEdit(e)}>
                    编辑
                  </button>
                  <button type="button" className="link-btn link-btn--danger" disabled={busy} onClick={() => remove(e.code)}>
                    删除
                  </button>
                </span>
              ) : null}
            </li>
          ))
        )}
      </ul>

      {view.editable ? (
        <div className="dict-add">
          <TextField
            id={`dict-${view.type}-code`}
            label="编码"
            value={draft.code}
            onChange={(v) => setDraft((d) => ({ ...d, code: v }))}
            placeholder="如 TEXTBOOK"
          />
          <TextField
            id={`dict-${view.type}-label`}
            label="名称"
            value={draft.label}
            onChange={(v) => setDraft((d) => ({ ...d, label: v }))}
            placeholder="如 教材"
          />
          <TextField
            id={`dict-${view.type}-sort`}
            label="排序"
            value={String(draft.sortOrder)}
            onChange={(v) => setDraft((d) => ({ ...d, sortOrder: Number(v) || 0 }))}
            placeholder="0"
          />
          <div className="dict-add__row">
            <label className="inline-check">
              <input
                type="checkbox"
                checked={draft.enabled}
                onChange={(e) => setDraft((d) => ({ ...d, enabled: e.target.checked }))}
              />
              启用
            </label>
            <button type="button" className="btn" disabled={busy} onClick={submit}>
              {editingCode !== null ? '保存修改' : '新增'}
            </button>
            {editingCode !== null ? (
              <button
                type="button"
                className="link-btn"
                disabled={busy}
                onClick={() => {
                  setEditingCode(null)
                  setDraft({ code: '', label: '', sortOrder: 0, enabled: true })
                  setErr(null)
                }}
              >
                取消
              </button>
            ) : null}
          </div>
          {err ? (
            <p className="field__error" role="alert">
              {err}
            </p>
          ) : null}
        </div>
      ) : (
        <p className="field__hint">该字典由系统枚举派生，不可在此修改。</p>
      )}
    </section>
  )
}

/* ── 主页 ───────────────────────────────────────────── */

export function RagManagementPage(): JSX.Element {
  const { session } = useSession()
  const sessionTenant = session?.tenantId ?? null
  const [tenantId, setTenantId] = useState<string>(
    () => sessionTenant ?? readStoredTenantId(),
  )

  const [syncedTenant, setSyncedTenant] = useState<string | null>(sessionTenant)
  if (sessionTenant !== null && sessionTenant !== syncedTenant) {
    setSyncedTenant(sessionTenant)
    setTenantId(sessionTenant)
  }

  const [tab, setTab] = useState<TabId>('list')
  const [statusFilter, setStatusFilter] = useState('')
  const [keyword, setKeyword] = useState('')
  const [includeStale, setIncludeStale] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [deciderId, setDeciderId] = useState('')
  const [opsTab, setOpsTab] = useState<'probe' | 'issues' | 'vocab'>('probe')

  const {
    sources,
    stats,
    action,
    loadSources,
    loadStats,
    verify,
    retrySources,
    retryStats,
    resetAction,
  } = useRagManagement()

  const tenantReady = tenantId.trim().length > 0

  const handleLoad = useCallback(
    (e: FormEvent<HTMLFormElement>) => {
      e.preventDefault()
      if (!tenantReady) return
      rememberTenantId(tenantId.trim())
      loadSources(tenantId.trim(), {
        status: statusFilter === '' ? null : (statusFilter as RagVerificationStatus),
        keyword: keyword.trim() === '' ? null : keyword.trim(),
        includeStale,
        limit: 50,
        offset: 0,
      })
      loadStats(tenantId.trim())
    },
    [tenantReady, tenantId, statusFilter, keyword, includeStale, loadSources, loadStats],
  )

  const selected = useMemo(
    () => sources.sources.find((s) => s.sourceId === selectedId) ?? null,
    [sources.sources, selectedId],
  )

  const busy = action.status === 'submitting'

  return (
    <>
      <PageHead
        title="语料管理"
        lead="登记教材与教辅来源、走完校验流程、查看入库与索引状况。未通过校验的来源不会进入可检索索引。"
      />

      {/* ── 租户（所有操作的前提）── */}
      <Panel
        title="租户"
        icon="search"
        note="检索与管理均按租户隔离：租户由主平台在调用时注入，此处需手填一次。填错不会报错，只会查不到数据。"
      >
        <div className="field" style={{ margin: 0, maxWidth: 460 }}>
          <label htmlFor="rag-mgmt-tenant">租户标识</label>
          <input
            id="rag-mgmt-tenant"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            placeholder="UUID，例如 11111111-1111-1111-1111-111111111111"
            className="mono hand"
            autoComplete="off"
            aria-describedby="rag-mgmt-tenant-hint"
          />
          {/* ⚠️ 提示用 aria-describedby 挂到输入框：读屏聚焦时才读得到
              「填错不会报错」这条最容易被忽略的前提。 */}
          <p id="rag-mgmt-tenant-hint" className="field__hint">
            登录后已按会话中的租户自动填充。管理操作按租户隔离，填错不会报错，只会查不到数据——请勿随意修改。
          </p>
        </div>
      </Panel>

      <TabNav active={tab} onChange={setTab} />

      {/*
        ⚠️ 主内容区包成 tabpanel：读屏用户切页签后要能知道
          「现在读的这一屏属于哪个页签」——否则页签条只是装饰。
      */}
      <div
        role="tabpanel"
        id={`rag-mgmt-panel-${tab}`}
        aria-labelledby={`rag-mgmt-tab-${tab}`}
        tabIndex={0}
      >
        {/* ── 来源列表与审核 ── */}
        {tab === 'list' ? (
          <>
            <Sheet title="查询条件" note="按租户、校验状态与关键词筛选来源。">
              <form onSubmit={handleLoad} noValidate>
                <div className="rag-row">
                  <div className="field" style={{ margin: 0, maxWidth: 220 }}>
                    <label htmlFor="rag-mgmt-status">校验状态</label>
                    <select
                      id="rag-mgmt-status"
                      value={statusFilter}
                      onChange={(e) => setStatusFilter(e.target.value)}
                    >
                      <option value="">全部</option>
                      {ALL_STATUSES.map((s) => (
                        <option key={s} value={s}>
                          {RAG_VERIFICATION_LABELS[s]}
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="field" style={{ margin: 0, maxWidth: 280 }}>
                    <label htmlFor="rag-mgmt-keyword">标题 / ISBN / DOI</label>
                    <input
                      id="rag-mgmt-keyword"
                      value={keyword}
                      onChange={(e) => setKeyword(e.target.value)}
                      placeholder="模糊匹配"
                    />
                  </div>

                  <div className="field" style={{ margin: 0, maxWidth: 260 }}>
                    <label htmlFor="rag-mgmt-stale">过期来源</label>
                    <select
                      id="rag-mgmt-stale"
                      value={includeStale ? 'yes' : 'no'}
                      onChange={(e) => setIncludeStale(e.target.value === 'yes')}
                    >
                      <option value="no">不显示（默认）</option>
                      <option value="yes">一并显示</option>
                    </select>
                  </div>
                </div>

                <div className="btn-row" style={{ marginTop: 'var(--s-4)' }}>
                  <button type="submit" className="btn" disabled={!tenantReady}>
                    查询
                  </button>
                  {!tenantReady ? (
                    <span className="muted">请先填写租户标识——缺失时后端会返回 400。</span>
                  ) : null}
                </div>
              </form>
            </Sheet>

            <Sheet title="语料来源" note="点击一条来源查看详情与审核。撤回是终态，不能复活。">
              {sources.status === 'idle' ? (
                <p className="muted" style={{ margin: 0 }}>
                  填写租户标识后点「查询」，这里会列出本租户的语料来源。
                </p>
              ) : sources.status === 'loading' ? (
                <LoadingRows columns={['来源', '状态', '等级', '版权']} rows={4} />
              ) : sources.status === 'empty' ? (
                <Empty
                  title="这个租户还没有登记语料来源"
                  hint="登记教材或教辅后，先走校验流程，通过校验才能进入可检索索引。"
                />
              ) : sources.status === 'error' || sources.status === 'timeout' ? (
                <ErrorState
                  reason={sources.error ?? '读取来源列表失败'}
                  next="可以重新查询；若持续失败，请确认租户标识是否正确、后端是否已启动。"
                  onRetry={retrySources}
                  retryLabel="重新查询"
                />
              ) : (
                <div className="entry-list">
                  {sources.sources.map((s) => (
                    <SourceEntry
                      key={s.sourceId}
                      source={s}
                      selected={s.sourceId === selectedId}
                      onSelect={setSelectedId}
                    />
                  ))}
                </div>
              )}
            </Sheet>

            {selected !== null ? (
              <Sheet title={`审核：${selected.title}`} note="每次判定都会记录依据与决定人，供事后追溯。">
                <SourceDetail source={selected} tenantId={tenantId.trim()} />

                <div className="rag-mgmt__block">
                  <h3 className="rag-mgmt__block-title">校验判定</h3>
                  {action.status === 'conflict' ? (
                    <div className="marked" role="alert">
                      <p className="marked__label">这一步不被允许</p>
                      <p style={{ margin: 0 }}>{action.message}</p>
                    </div>
                  ) : action.status === 'error' || action.status === 'timeout' ? (
                    <ErrorState
                      reason={action.message ?? '提交失败'}
                      next="请检查依据与决定人是否填写完整后重试。"
                      onRetry={resetAction}
                      retryLabel="重新填写"
                    />
                  ) : action.status === 'success' ? (
                    <div className="marked marked--calm" role="status">
                      <p className="marked__label">已记录</p>
                      <p style={{ margin: 0 }}>
                        判定已生效，当前状态：
                        {RAG_VERIFICATION_LABELS[action.updated?.verificationStatus ?? 'PENDING']}。
                      </p>
                    </div>
                  ) : null}

                  <div className="field" style={{ marginTop: 'var(--s-3)', maxWidth: 420 }}>
                    <label htmlFor="rag-mgmt-decider">决定人（UUID，必填）</label>
                    <input
                      id="rag-mgmt-decider"
                      value={deciderId}
                      onChange={(e) => setDeciderId(e.target.value)}
                      placeholder="审核员 / 法务 的主体 UUID"
                      className="mono hand"
                      autoComplete="off"
                      aria-describedby="rag-mgmt-decider-hint"
                    />
                    {/* ⚠️ 提示也要用 aria-describedby 挂上：否则读屏聚焦输入框时，
                        读不到「留空无法提交」这条会挡住提交的前提。 */}
                    <p id="rag-mgmt-decider-hint" className="field__hint">
                      决定人会写入审计。留空无法提交——「谁作出的判定」必须可回溯。
                    </p>
                  </div>

                  <VerificationForm
                    key={selected.sourceId}
                    source={selected}
                    busy={busy}
                    canSubmit={deciderId.trim() !== ''}
                    onSubmit={(payload) => {
                      verify(tenantId.trim(), selected.sourceId, {
                        targetStatus: payload.targetStatus,
                        evidenceRef: payload.evidenceRef,
                        decidedBy: deciderId.trim(),
                        verificationMethod: payload.verificationMethod,
                        trustTier: payload.trustTier,
                        validDays: 365,
                      })
                    }}
                  />
                </div>
              </Sheet>
            ) : null}
          </>
        ) : null}

        {/* ── 录入新来源 ── */}
        {tab === 'entry' ? <RegisterSourceForm tenantId={tenantId.trim()} /> : null}

        {/* ── 批量导入 ── */}
        {tab === 'import' ? <BulkImportPanel tenantId={tenantId.trim()} /> : null}

        {/* ── 索引与统计 ── */}
        {tab === 'stats' ? (
          <Sheet title="索引与准入统计" note="区分「已入库」与「可检索」——片段数量健康不代表检索能得到内容。">
            {stats.status === 'idle' ? (
              <p className="muted" style={{ margin: 0 }}>
                查询后会显示本租户的语料与索引状况。
              </p>
            ) : stats.status === 'loading' ? (
              <Loading label="正在读取统计" />
            ) : stats.status === 'error' || stats.status === 'timeout' ? (
              <ErrorState
                reason={stats.error ?? '读取统计失败'}
                next="可以重新读取；若持续失败，请确认租户标识是否正确。"
                onRetry={retryStats}
                retryLabel="重新读取"
              />
            ) : stats.stats !== null ? (
              <StatisticsBoard stats={stats.stats} />
            ) : null}
          </Sheet>
        ) : null}

        {/* ── 运维工具（Point 1b：拆成三个子 tab）── */}
        {tab === 'ops' ? (
          <>
            <SubTabNav
              idPrefix="rag-mgmt-ops"
              ariaLabel="运维工具"
              active={opsTab}
              onChange={(id) => setOpsTab(id as 'probe' | 'issues' | 'vocab')}
              tabs={[
                { id: 'probe', label: '召回测试' },
                { id: 'issues', label: '维护清单' },
                { id: 'vocab', label: '词表差异' },
              ]}
            />

            {/* ⚠️ 子分区同样要有 tabpanel：否则读屏只听到三个页签，
                不知道下面这块内容（召回测试 / 维护清单 / 词表差异）归谁。 */}
            <div
              role="tabpanel"
              id={`rag-mgmt-ops-panel-${opsTab}`}
              aria-labelledby={`rag-mgmt-ops-tab-${opsTab}`}
              tabIndex={0}
            >
              {opsTab === 'probe' ? (
                <Sheet
                  title="召回测试"
                  note="用真实检索端点验证「已准入的语料到底能不能被召回」。当前嵌入为确定性占位，故本面板验证的是链路，不是语义质量。"
                >
                  <RetrievalProbe tenantId={tenantId.trim()} />
                </Sheet>
              ) : null}

              {opsTab === 'issues' ? (
                <Sheet
                  title="维护问题清单"
                  note="定位缺向量的载体、解析失败的载体与待审核的来源，并可重跑索引补建缺失向量。"
                >
                  <MaintenanceIssueBoard tenantId={tenantId.trim()} />
                </Sheet>
              ) : null}

              {opsTab === 'vocab' ? (
                <Sheet
                  title="词表口径差异"
                  note="platform 业务词表与 RAG 版本坐标词表的差集。差异会导致按学科/年级过滤时静默漏召回。"
                >
                  <VocabularyDrift />
                </Sheet>
              ) : null}
            </div>
          </>
        ) : null}

        {/* ── 字典维护（Point 2）── */}
        {tab === 'dict' ? <DictionaryMaintenance tenantId={tenantId.trim()} /> : null}
      </div>
    </>
  )
}
