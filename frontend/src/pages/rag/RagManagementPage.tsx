/**
 * RAG 语料数据管理页（RAG-D10 管理面）
 *
 * 交互结构：租户与筛选 → 来源列表 → 选中来源的详情/审核 → 索引统计。
 *
 * ⚠️ **本页面是「管理面」，不是「检索面」**（RAG-D10 §1.1）：
 *    检索面回答「能召回什么」，管理面回答「库里有什么、哪些还没审、能不能放行」。
 *    二者是两个方向的闸门，混在一起会让「自动闸门」被「人工闸门」的宽松语义污染。
 *
 * ⚠️ **只渲染合法的状态流转按钮**（RAG-D07 §2.4 T-1～T-12）：
 *    「撤回」是终态（T-10）→ 不提供任何后继按钮；
 *    「已拒绝」不能直接通过（T-12）→ 只提供「重新认领」；
 *    让审核员点一个注定被 409 的操作是产品缺陷
 *    （与 P4-D10「过期审批不提供批准按钮」同构纪律）。
 *
 * ⚠️ **不提供删除入口**（RAG-D07 §8 INV-4-a）：
 *    撤回的唯一手段是 REVOKED 状态；物理 DELETE 会级联抹除历史引用，
 *    破坏可溯源性。故本页面**没有任何删除按钮**。
 *
 * ⚠️ **诚实边界**：
 *    · 准入结论（admissible）由**服务端**计算——租户权威门槛前端不知道，
 *      前端不得自行推算（会与真实门槛静默漂移）。
 *    · G-1～G-4 尚未在检索路径强制（RAG-D01），故「已通过校验」≠
 *      「一定能被检索到」；本页面只呈现**状态事实**，不承诺检索结果。
 */

import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { Sheet, Loading, LoadingRows, Empty, ErrorState } from '@/shared/states'
import { useRagManagement, allowedVerificationTargets } from '@/features/rag/useRagManagement'
import { useSession } from '@/auth/session'
import { listRagDocuments, listRagChunks } from '@/api/client'
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

/**
 * 状态徽章。
 *
 * ⚠️ 用色纪律（FE-D3 R1/R3）：批改红只用于**错误/阻止性**状态。
 *    REVOKED（撤回）与 REJECTED（拒绝）是阻止性状态 → 用红；
 *    其余用中性/低饱和色。⛔ 不用颜色表达「好/坏」之外的语义。
 */
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

/** 详情抽屉：来源元数据 + 载体 + 片段。 */
function SourceDetail({
  source,
  tenantId,
}: {
  source: RagSource
  tenantId: string
}): JSX.Element {
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

      <div className="btn-row" style={{ marginTop: 'var(--s-3)' }}>
        <button type="button" className="btn btn--quiet" onClick={loadDocuments}>
          查看载体与片段
        </button>
      </div>

      {documents === null && docError === null ? null : null}

      {docError !== null ? (
        <div className="marked" role="alert" style={{ marginTop: 'var(--s-3)' }}>
          <p className="marked__label">没能完成</p>
          <p>{docError}</p>
        </div>
      ) : null}

      {documents !== null ? (
        documents.length === 0 ? (
          <Empty title="这个来源还没有载体" hint="登记后需上传物理文件（PDF/扫描件）并入库切分。" />
        ) : (
          <div className="entry-list" style={{ marginTop: 'var(--s-3)' }}>
            {documents.map((doc) => (
              <DocumentEntry
                key={doc.documentId}
                document={doc}
                tenantId={tenantId}
                // ⚠️ 载体不得高于来源：来源没过校验时，载体的「通过校验」选项不出现。
                //    这条不变量是公开的领域规则（不是服务端才知道的权威门槛），
                //    故前端可据此裁剪；最终判定仍在服务端。
                sourceStatus={source.verificationStatus}
                onChanged={(updated) => {
                  // ⚛ 就地更新：载体判定后列表必须反映新状态，
                  //    否则运维会以为下架没生效（实际后端已改）。
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
        )
      ) : null}

      {chunkDocId !== null ? (
        <div style={{ marginTop: 'var(--s-3)' }}>
          <p className="sheet__note">片段摘要（最多 100 条）</p>
          {chunks === null ? (
            <Loading label="正在读取片段" />
          ) : chunks.length === 0 ? (
            <Empty title="该载体还没有片段" hint="需要先经过解析与切分入库。" />
          ) : (
            <div className="entry-list">
              {chunks.map((c) => (
                <div key={c.chunkId} className="entry">
                  <span className="entry__title">
                    第 {c.pageNumber} 页 · {c.sectionPath}
                  </span>
                  <span className="entry__desc">
                    {c.role}
                    {c.exerciseNo !== null ? ` · 第 ${c.exerciseNo} 题` : ''} · {c.charLength} 字
                  </span>
                  <span className="chip">
                    {c.hasEmbedding ? '已建向量' : '缺向量'}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      ) : null}
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
  /** 正在提交（按钮显示「提交中…」） */
  busy: boolean
  /**
   * 表单本身是否可提交（决定人/依据已填）。
   *
   * ⚠️ 与 `busy` **必须分开**：
   *    二者语义不同——`busy` 是「请求在飞」，
   *    `canSubmit` 是「条件已满足」。若把「未填完」也塞进 `busy`，
   *    按钮文案会变成「提交中…」，而实际**什么都没在提交**——
   *    用户会以为系统在忙，实际上是自己没填完。
   */
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
          撤回是终态，不能再流转。若这份语料需要重新准入，
          请<b>重新登记一条来源</b>——保留原记录才能回答「这个来源为什么不见了」。
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
          ⚠️ 每次流转都会留痕（谁、基于什么证据、何时）。没有依据的「已通过」
          只是一个无法复核的断言。
        </p>
      </div>

      <div className="btn-row">
        <button type="submit" className="btn" disabled={submitDisabled}>
          {busy ? '提交中…' : '提交判定'}
        </button>
        {!canSubmit ? (
          <span className="muted">请先填写决定人与校验依据。</span>
        ) : null}
      </div>
    </form>
  )
}

/** 统计看板。 */
function StatisticsBoard({ stats }: { stats: import('@/api/types').RagStatistics }): JSX.Element {
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
            有 {storedButHidden} 个片段已入库但当前不可检索——来源未通过校验、
            校验过期、版权未授权或权威等级不达门槛都会造成。
            片段数量健康并不代表检索能得到内容。
          </p>
        </div>
      ) : null}

      {stats.chunksMissingEmbedding > 0 ? (
        <div className="marked marked--calm" style={{ marginTop: 'var(--s-3)' }}>
          <p className="marked__label">缺向量</p>
          <p style={{ margin: 0 }}>
            有 {stats.chunksMissingEmbedding} 个片段已入库但没有向量——
            这些片段**召不回**（静默漏召回），需要重跑索引。
          </p>
        </div>
      ) : null}

      {/*
        ⚠️ 三张**分布表**此前被整块丢弃：后端 `StatisticsResponse` 一直返回
           `chunksByRole` / `sourcesByVerificationStatus` / `documentsByParseStatus`，
           前端类型也定义了，但看板只渲染标量总数。
           后果：运维只知道「缺 12 个向量」，**不知道缺在哪一类**——
           分布正是「问题集中在哪」的唯一答案（RAG-D10 Q-5 的下钻雏形）。
      */}
      <ChunkRoleDistribution entries={stats.chunksByRole ?? {}} />
      <SourceStatusDistribution entries={stats.sourcesByVerificationStatus ?? {}} />
      <ParseStatusDistribution entries={stats.documentsByParseStatus ?? {}} />
    </>
  )
}

export function RagManagementPage(): JSX.Element {
  const { session } = useSession()
  // ⚠️ 租户标识的**优先级**（2026-10-22）：
  //    会话中的权威租户（登录响应由后端回带） > 本地记忆 > 手填。
  //
  //    为什么以会话为准：RAG 库**不存租户副本**（RAG-D00 §4「租户与身份」边界），
  //    租户 ID 空间与 platform 一致；用户手填极易填错——
  //    填错不会报错，只会「查不到任何数据」，表现为「页面不显示」。
  //    本地记忆仅作会话缺失时的兜底。
  const sessionTenant = session?.tenantId ?? null
  const [tenantId, setTenantId] = useState<string>(
    () => sessionTenant ?? readStoredTenantId(),
  )

  // ⚠️ 会话建立后（首次拿到权威租户）自动同步一次：
  //    否则用户刷新页面时仍停留在「未填租户」的禁用态。
  const [syncedTenant, setSyncedTenant] = useState<string | null>(sessionTenant)
  if (sessionTenant !== null && sessionTenant !== syncedTenant) {
    setSyncedTenant(sessionTenant)
    setTenantId(sessionTenant)
  }
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [keyword, setKeyword] = useState('')
  const [includeStale, setIncludeStale] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [deciderId, setDeciderId] = useState('')

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

      {/* ── 查询条件 ── */}
      <Panel title="查询条件" icon="search" note="检索与管理均按租户隔离（RLS）：租户由主平台在调用时注入，此处需手填一次。">
        <form onSubmit={handleLoad} noValidate>
          <div className="field">
            <label htmlFor="rag-mgmt-tenant">租户标识</label>
            <input
              id="rag-mgmt-tenant"
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
              placeholder="UUID，例如 11111111-1111-1111-1111-111111111111"
              className="hand"
              autoComplete="off"
            />
            <p className="field__hint">
              ⚠️ 登录后已按会话中的租户自动填充。管理操作按租户隔离（RLS），
              填错不会报错，只会查不到数据——请勿随意修改。
            </p>
          </div>

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
      </Panel>

      {/* ── 来源列表 ── */}
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

      {/* ── 来源详情与审核 ── */}
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
                  判定已生效，当前状态：{RAG_VERIFICATION_LABELS[action.updated?.verificationStatus ?? 'PENDING']}。
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
                className="hand"
                autoComplete="off"
              />
              <p className="field__hint">
                ⚠️ 决定人会写入审计。留空无法提交——「谁作出的判定」必须可回溯。
              </p>
            </div>

            <VerificationForm
              // ⚠️ key 必须绑到来源 ID：VerificationForm 内部用 useState 保存
              //    「下一步」与「判定等级」，若不加 key，React 会复用组件实例——
              //    切换来源后表单仍显示**上一个来源**的选项与初始值，
              //    会出现「给 A 来源的判定按钮被用来提交 B 来源」的错配。
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

      {/* ── 统计 ── */}
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

      {/*
        ⚠️ 召回测试（D-1）：管理面与检索面之间**没有交叉验证**时，
           「来源已 VERIFIED」会给出虚假的安心感——
           RAG-D10 已知限制 L-1 明确：检索路径尚未强制消费
           过期/版权/等级三闸门，治理状态「可管理」不等于「被检索路径尊重」。
           故把真实检索端点摆到运维面前，直接看能不能召回。
      */}
      <Sheet
        title="召回测试"
        note="用真实检索端点验证「已准入的语料到底能不能被召回」。当前嵌入为确定性占位，故本面板验证的是链路，不是语义质量。"
      >
        <RetrievalProbe tenantId={tenantId.trim()} />
      </Sheet>

      {/*
        ⚠️ 维护问题清单（B-1）+ 重跑索引（B-2）：
           统计只回答「有多少」，运维需要知道「**是哪些**」才能动手。
      */}
      <Sheet
        title="维护问题清单"
        note="定位缺向量的载体、解析失败的载体与待审核的来源，并可重跑索引补建缺失向量。"
      >
        <MaintenanceIssueBoard tenantId={tenantId.trim()} />
      </Sheet>

      {/*
        ⚠️ 词表口径差异（D-2）：platform 的 /api/reference 与 RAG 侧硬编码坐标
           各有一份学科/年级词表（评审报告 §2.4 点名的缺陷）。
           两边不一致时**静默漏召回**，且原先没有任何地方能看见这个差异。
      */}
      <Sheet
        title="词表口径差异"
        note="platform 业务词表与 RAG 版本坐标词表的差集。差异会导致按学科/年级过滤时静默漏召回。"
      >
        <VocabularyDrift />
      </Sheet>
    </>
  )
}
