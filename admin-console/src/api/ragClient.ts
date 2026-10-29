/**
 * RAG 语料数据管理 API 客户端（RAG-D10 管理面）。
 *
 * 单一数据源：`/api/v1/management/**`（rag-management 模块，独立服务，默认 8081）。
 *
 * ⚠️ **路径写法**：本模块的 path 一律写成 `/v1/...`（**不带** `/api`）——
 *    `request()` 会统一拼 API_BASE=`/api`，自带 `/api` 会拼成 `/api/api/v1/...`，
 *    被 vite 的 `/api` 规则代理到平台后端 8080 并返回 401 → 会话被清、跳登录页。
 *    带 `/api` 的完整路径只出现在下面的文档注释与后端契约里。
 *
 * ⚠️ **作用域由 `X-Rag-Scope` 显式声明**（RAG-D11 第四刀·第一刀）：
 *    `TENANT`（缺省，配 `X-Tenant-Id`）／`PLATFORM`（平台全局语料，**不带**租户头）／
 *    `ALL`（跨租户盘点，后端目前 501）。
 *    刻意**不**由「有没有传 `X-Tenant-Id`」隐式推断作用域——那样
 *    「忘记选租户」会静默变成一次跨租户或全局查询。
 *
 *    `TENANT` 档缺 `X-Tenant-Id` 时后端返回 **400**（而非静默空列表）：
 *    「没选租户」是本地配置问题，不该伪装成后端故障。
 *
 * ⚠️ **平台档写要治理票据**（第四刀·第二刀）：写请求（登记 / 批量登记 /
 *    来源与载体校验流转 / 重跑索引）在平台档把 `Authorization` **换成票据**，
 *    读请求不换。缺票据时后端是 **403**（登录令牌不够格），
 *    所以界面按 {@link isRagWriteTarget} 提前不给入口。
 *
 * ⚠️ **超时纪律**：管理面是人工操作，每次都是显式点击触发。普通管理端点给
 *    30s，重跑索引（批量）给 120s；超时归一为 `ApiError(RAG_TIMEOUT_STATUS)`，
 *    与「网络层失败」（status 0）区分——文案不同，混为一谈会误导管理员。
 */

import { request, ApiError } from '@/api/client'
import { ticketAuthHeader } from '@/api/ragTicket'
import type { RagGovernanceTicket } from '@/api/ragTicket'

/** ⚠️ 再导出：调用方要用 `instanceof ApiError` 区分「超时」与「网络层失败」，
 *    若各自去 `@/api/client` 导入固然可行，但会散成两个来源；统一从本模块取。 */
export { ApiError }
/** ⚠️ 再导出**类型**（不是函数）：`RagTarget.ticket` 就是这个形状，
 *    页面构造目标时必然需要它。票据的**取／清／看**只在 `@/api/ragTicket` 里，
 *    不在这里重复导出——凭据的持有处必须只有一个。 */
export type { RagGovernanceTicket }
import type {
  RagStatistics,
  RagSource,
  RagDocument,
  RagChunk,
  RagRegisterSourceRequest,
  RagBulkSourceItem,
  RagBulkImportResponse,
  RagVerificationRequest,
  RagDocumentVerificationRequest,
  RagMaintenanceIssues,
  RagReindexResult,
  RagSourceQuery,
  RagDailySearchStatistics,
  RagDailyImportStatistics,
  RagDictionaryType,
  RagDictionaryTypeView,
  RagDictionaryEntry,
  RagUpsertDictionaryEntryRequest,
  RagRetrievalRequest,
  RagRetrievalResponse,
} from '@/api/ragTypes'

/** 超时归一化的状态码哨兵（区别于网络层 status 0）。 */
export const RAG_TIMEOUT_STATUS = 408

/** 管理端点的默认超时（毫秒）。 */
const DEFAULT_RAG_MGMT_TIMEOUT_MS = 30000

/** 重跑索引的超时（批量操作，耗时与待索引片段数成正比）。 */
const DEFAULT_RAG_REINDEX_TIMEOUT_MS = 120000

/** 检索探针的默认超时。 */
const DEFAULT_RAG_RETRIEVAL_TIMEOUT_MS = 30000

/** 语料作用域档位（与后端 `RagCorpusScope` 同名同义）。 */
export type RagCorpusScope = 'TENANT' | 'PLATFORM' | 'ALL'

/**
 * 一次 RAG 管理调用的目标：作用域 + （仅租户档）租户 ID + （仅平台档写）治理票据。
 *
 * ⚠️ 平台档**刻意不携带租户 ID**：后端在收到 `X-Rag-Scope: PLATFORM` 且同时收到
 *    `X-Tenant-Id` 时返回 400——「以平台身份看全局」与「指定要看哪个租户」互斥，
 *    同时给出说明调用方自己不清楚要看什么。
 *
 * ⚠️ `ticket` 是**内存态凭据**（见 `@/api/ragTicket` 的头注）：只有写请求会把它
 *    放进 `Authorization`，读请求一律沿用会话令牌——后端读闸门只查「是不是平台域」，
 *    把票据挂在读请求上会让「哪些调用动过全局语料」失去区分度。
 */
export interface RagTarget {
  readonly scope: RagCorpusScope
  readonly tenantId?: string
  readonly ticket?: RagGovernanceTicket
}

/** 租户档目标（缺省档，行为与改造前一致）。 */
export function tenantTarget(tenantId: string): RagTarget {
  return { scope: 'TENANT', tenantId }
}

/** 平台全局档目标（**不带票据 = 只读视图**；写入口由 {@link isRagWriteTarget} 收口）。 */
export const PLATFORM_TARGET: RagTarget = { scope: 'PLATFORM' }

/** 平台全局档目标（带治理票据）：登记 / 校验流转 / 重跑索引据此放开。 */
export function platformTarget(ticket?: RagGovernanceTicket | null): RagTarget {
  return ticket ? { scope: 'PLATFORM', ticket } : PLATFORM_TARGET
}

/** 目标是否足以发起租户档请求（未选租户时前端不发请求，避免把配置问题伪装成 400）。 */
export function isRagTargetReady(target: RagTarget): boolean {
  return target.scope !== 'TENANT' || (target.tenantId ?? '').trim() !== ''
}

/**
 * 目标档是否可写。
 *
 * <p>⚠️ **平台档可写的前提是「手里有一张没过期且档位一致的治理票据」**：
 * 后端写闸门（`requireWriteContext`）除平台域身份外还校验票据的 `rag_scope` claim，
 * 缺票据是 **403**（不是 409）。前端据此不渲染写入口，
 * 而不是让管理员点一次吃一个「需要治理票据」。
 *
 * <p>⚠️ 过期票据按「没有票据」处理：后端按 `exp` 判定，这里用同一判据的本地近似，
 * 差别只会在临界秒出现，而那恰好是「操作员看着按钮能用、点了却 401」的区间。
 *
 * <p>`ALL`（跨租户盘点）永不可写：「同时往 N 个租户写」不是任何一档的定义。
 */
export function isRagWriteTarget(target: RagTarget): boolean {
  if (target.scope === 'TENANT') return isRagTargetReady(target)
  if (target.scope !== 'PLATFORM') return false
  const ticket = target.ticket
  return ticket !== undefined && Date.parse(ticket.expiresAt) > Date.now()
}

/**
 * 检索探针档是否可用。
 *
 * <p>⚠️ 刻意**不等于** {@link isRagWriteTarget}：探针按租户上下文召回（检索面 `rag-api`
 * 不认平台档，第四刀·第二刀不动它），平台全局档没有租户可比，因此全局语料能不能写，
 * 与「能不能在这里发一次检索」是两件事。合成一个旗标会让人以为写完就能立刻验召回。
 */
export function isRagRetrievalTarget(target: RagTarget): boolean {
  return target.scope === 'TENANT' && isRagTargetReady(target)
}

/** 管理端点的作用域头（+ 租户档的租户头）。 */
function scopeHeaders(target: RagTarget): Record<string, string> {
  const headers: Record<string, string> = { 'X-Rag-Scope': target.scope }
  if (target.scope === 'TENANT') headers['X-Tenant-Id'] = (target.tenantId ?? '').trim()
  return headers
}

/**
 * 写端点的头：作用域头 + （平台档）治理票据覆盖会话令牌。
 *
 * <p>⚠️ 只有平台档替换 `Authorization`：租户档的写与第一刀逐字节一致，
 * 仍走会话令牌 + `X-Tenant-Id`。
 */
function writeHeaders(target: RagTarget): Record<string, string> {
  const headers = scopeHeaders(target)
  if (target.scope === 'PLATFORM' && target.ticket) {
    Object.assign(headers, ticketAuthHeader(target.ticket))
  }
  return headers
}

/** 构造查询串：跳过 null / undefined / 空串（避免把「没传」伪装成「传了空」）。 */
function toQuery(params: Record<string, string | number | boolean | null | undefined>): string {
  const parts: string[] = []
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length === 0 ? '' : `?${parts.join('&')}`
}

/**
 * 带超时与租户头的统一请求入口。
 *
 * ⚠️ 超时判定用 `timedOut` 旗标而非只看异常类型：AbortController 中断后
 *    `request` 会抛网络层 `ApiError(0)`，须由本层覆盖成超时语义。
 *
 * @param headers 本次请求的额外头（缺省 = 只带作用域头；写请求传 {@link writeHeaders}）
 */
async function ragRequest<T>(
  path: string,
  init: RequestInit,
  target: RagTarget,
  timeoutMs: number,
  headers: Record<string, string> = scopeHeaders(target),
): Promise<T> {
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  try {
    return await request<T>(
      path,
      { ...init, signal: controller.signal },
      true,
      headers,
    )
  } catch (cause) {
    if (timedOut) {
      throw new ApiError(
        RAG_TIMEOUT_STATUS,
        '请求超时，后端没有在规定时间内返回。可以稍后重试，或缩小本次操作范围。',
      )
    }
    throw cause
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 写请求入口：与 {@link ragRequest} 同一套超时纪律，唯一区别是平台档会带上治理票据。
 *
 * ⚠️ 刻意**不**让读路径复用这个头集合：票据是「动过全局语料」的凭据，
 *    读操作拿它去发请求，等于让审计与日志里两类调用长得一样。
 */
function ragWriteRequest<T>(
  path: string,
  init: RequestInit,
  target: RagTarget,
  timeoutMs: number,
): Promise<T> {
  return ragRequest<T>(path, init, target, timeoutMs, writeHeaders(target))
}

/** 是否为 RAG 请求超时（区别于网络层 status 0）。 */
export function isRagTimeout(cause: unknown): boolean {
  return cause instanceof ApiError && cause.status === RAG_TIMEOUT_STATUS
}

/* ── 统计与列表 ───────────────────────────────────────── */

/** GET /api/v1/management/statistics —— 索引与准入统计。 */
export function getRagStatistics(
  target: RagTarget,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagStatistics> {
  return ragRequest<RagStatistics>('/v1/management/statistics', { method: 'GET' }, target, timeoutMs)
}

/** GET /api/v1/management/sources —— 列出来源。 */
export function listRagSources(
  target: RagTarget,
  query: RagSourceQuery = {},
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource[]> {
  return ragRequest<RagSource[]>(
    `/v1/management/sources${toQuery({
      status: query.status,
      trustTier: query.trustTier,
      keyword: query.keyword,
      includeStale: query.includeStale === true ? 'true' : null,
      limit: query.limit,
      offset: query.offset,
    })}`,
    { method: 'GET' },
    target,
    timeoutMs,
  )
}

/** GET /api/v1/management/sources/{sourceId} —— 来源详情（不存在 404）。 */
export function getRagSource(
  target: RagTarget,
  sourceId: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource> {
  return ragRequest<RagSource>(
    `/v1/management/sources/${encodeURIComponent(sourceId)}`,
    { method: 'GET' },
    target,
    timeoutMs,
  )
}

/** POST /api/v1/management/sources —— 登记语料来源（结果恒为 PENDING / UNVERIFIED）。 */
export function registerRagSource(
  target: RagTarget,
  payload: RagRegisterSourceRequest,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource> {
  return ragWriteRequest<RagSource>(
    '/v1/management/sources',
    { method: 'POST', body: JSON.stringify(payload) },
    target,
    timeoutMs,
  )
}

/** POST /api/v1/management/sources/batch —— 批量登记（逐条成败、整体不回滚）。 */
export function bulkRegisterRagSources(
  target: RagTarget,
  registeredBy: string,
  items: RagBulkSourceItem[],
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagBulkImportResponse> {
  return ragWriteRequest<RagBulkImportResponse>(
    '/v1/management/sources/batch',
    { method: 'POST', body: JSON.stringify({ registeredBy, sources: items }) },
    target,
    timeoutMs,
  )
}

/** POST /api/v1/management/sources/{sourceId}/verification —— 校验状态流转。 */
export function applyRagVerification(
  target: RagTarget,
  sourceId: string,
  payload: RagVerificationRequest,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource> {
  return ragWriteRequest<RagSource>(
    `/v1/management/sources/${encodeURIComponent(sourceId)}/verification`,
    { method: 'POST', body: JSON.stringify(payload) },
    target,
    timeoutMs,
  )
}

/** GET /api/v1/management/sources/{sourceId}/documents —— 列出来源的载体。 */
export function listRagDocuments(
  target: RagTarget,
  sourceId: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDocument[]> {
  return ragRequest<RagDocument[]>(
    `/v1/management/sources/${encodeURIComponent(sourceId)}/documents`,
    { method: 'GET' },
    target,
    timeoutMs,
  )
}

/** GET /api/v1/management/documents/{documentId}/chunks —— 列出文档的片段摘要（limit ≤ 500）。 */
export function listRagChunks(
  target: RagTarget,
  documentId: string,
  limit = 100,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagChunk[]> {
  return ragRequest<RagChunk[]>(
    `/v1/management/documents/${encodeURIComponent(documentId)}/chunks${toQuery({ limit })}`,
    { method: 'GET' },
    target,
    timeoutMs,
  )
}

/** POST /api/v1/management/documents/{documentId}/verification —— 载体级校验流转。 */
export function applyRagDocumentVerification(
  target: RagTarget,
  documentId: string,
  payload: RagDocumentVerificationRequest,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDocument> {
  return ragWriteRequest<RagDocument>(
    `/v1/management/documents/${encodeURIComponent(documentId)}/verification`,
    { method: 'POST', body: JSON.stringify(payload) },
    target,
    timeoutMs,
  )
}

/** GET /api/v1/management/issues —— 维护问题清单（「哪里坏了」的定位入口）。 */
export function getRagIssues(
  target: RagTarget,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagMaintenanceIssues> {
  return ragRequest<RagMaintenanceIssues>(
    '/v1/management/issues',
    { method: 'GET' },
    target,
    timeoutMs,
  )
}

/** POST /api/v1/management/reindex —— 重跑本租户索引（同步执行，limit ≤ 2000）。 */
export function reindexRagCorpus(
  target: RagTarget,
  limit = 500,
  timeoutMs: number = DEFAULT_RAG_REINDEX_TIMEOUT_MS,
): Promise<RagReindexResult> {
  return ragWriteRequest<RagReindexResult>(
    '/v1/management/reindex',
    { method: 'POST', body: JSON.stringify({ limit }) },
    target,
    timeoutMs,
  )
}

/** GET /api/v1/management/statistics/daily-searches —— 每日检索次数。 */
export function getRagDailySearches(
  target: RagTarget,
  days = 30,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDailySearchStatistics> {
  return ragRequest<RagDailySearchStatistics>(
    `/v1/management/statistics/daily-searches${toQuery({ days })}`,
    { method: 'GET' },
    target,
    timeoutMs,
  )
}

/** GET /api/v1/management/statistics/daily-imports —— 每日导入语料量。 */
export function getRagDailyImports(
  target: RagTarget,
  days = 30,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDailyImportStatistics> {
  return ragRequest<RagDailyImportStatistics>(
    `/v1/management/statistics/daily-imports${toQuery({ days })}`,
    { method: 'GET' },
    target,
    timeoutMs,
  )
}

/* ── RAG 字典 ─────────────────────────────────────────── */

/** GET /api/v1/management/dictionaries —— 列出全部字典类型（含是否可维护、各类型条目）。 */
export function getRagDictionaries(
  target: RagTarget,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDictionaryTypeView[]> {
  return ragRequest<RagDictionaryTypeView[]>(
    '/v1/management/dictionaries',
    { method: 'GET' },
    target,
    timeoutMs,
  )
}

/** POST /api/v1/management/dictionaries/{type}/entries —— 写入 / 更新一项（仅可编辑类型）。 */
export function upsertRagDictionaryEntry(
  target: RagTarget,
  type: RagDictionaryType,
  payload: RagUpsertDictionaryEntryRequest,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDictionaryEntry> {
  return ragRequest<RagDictionaryEntry>(
    `/v1/management/dictionaries/${encodeURIComponent(type)}/entries`,
    { method: 'POST', body: JSON.stringify(payload) },
    target,
    timeoutMs,
  )
}

/** DELETE /api/v1/management/dictionaries/{type}/entries/{code} —— 删除一项（仅可编辑类型）。 */
export function deleteRagDictionaryEntry(
  target: RagTarget,
  type: RagDictionaryType,
  code: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<void> {
  return ragRequest<void>(
    `/v1/management/dictionaries/${encodeURIComponent(type)}/entries/${encodeURIComponent(code)}`,
    { method: 'DELETE' },
    target,
    timeoutMs,
  )
}

/* ── 检索探针（维护 · 诚实边界 RAG-R01）────────────────── */

/** POST /api/v1/retrieval —— 执行检索，返回可溯源召回片段（不写库）。 */
export function retrieveRagChunks(
  target: RagTarget,
  payload: RagRetrievalRequest,
  timeoutMs: number = DEFAULT_RAG_RETRIEVAL_TIMEOUT_MS,
): Promise<RagRetrievalResponse> {
  return ragRequest<RagRetrievalResponse>(
    '/v1/retrieval',
    { method: 'POST', body: JSON.stringify(payload) },
    target,
    timeoutMs,
  )
}
