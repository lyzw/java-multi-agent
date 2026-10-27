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
 * ⚠️ **必须携带 `X-Tenant-Id`**：管理 SQL 一律以
 *    `tenant_id = rag_current_tenant()` 收口。跨进程 HTTP 调用时，主平台的事务上下文
 *    不会随之而来，后端须显式重建租户上下文；缺失该头后端返回 **400**
 *    （而非静默空列表）。故「没选租户」是本地配置问题，不伪装成后端故障。
 *
 * ⚠️ **超时纪律**：管理面是人工操作，每次都是显式点击触发。普通管理端点给
 *    30s，重跑索引（批量）给 120s；超时归一为 `ApiError(RAG_TIMEOUT_STATUS)`，
 *    与「网络层失败」（status 0）区分——文案不同，混为一谈会误导管理员。
 */

import { request, ApiError } from '@/api/client'

/** ⚠️ 再导出：调用方要用 `instanceof ApiError` 区分「超时」与「网络层失败」，
 *    若各自去 `@/api/client` 导入固然可行，但会散成两个来源；统一从本模块取。 */
export { ApiError }
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

/** 管理端点的租户头。 */
function tenantHeaders(tenantId: string): Record<string, string> {
  return { 'X-Tenant-Id': tenantId.trim() }
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
 */
async function ragRequest<T>(
  path: string,
  init: RequestInit,
  tenantId: string,
  timeoutMs: number,
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
      tenantHeaders(tenantId),
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

/** 是否为 RAG 请求超时（区别于网络层 status 0）。 */
export function isRagTimeout(cause: unknown): boolean {
  return cause instanceof ApiError && cause.status === RAG_TIMEOUT_STATUS
}

/* ── 统计与列表 ───────────────────────────────────────── */

/** GET /api/v1/management/statistics —— 索引与准入统计。 */
export function getRagStatistics(
  tenantId: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagStatistics> {
  return ragRequest<RagStatistics>('/v1/management/statistics', { method: 'GET' }, tenantId, timeoutMs)
}

/** GET /api/v1/management/sources —— 列出来源。 */
export function listRagSources(
  tenantId: string,
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
    tenantId,
    timeoutMs,
  )
}

/** GET /api/v1/management/sources/{sourceId} —— 来源详情（不存在 404）。 */
export function getRagSource(
  tenantId: string,
  sourceId: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource> {
  return ragRequest<RagSource>(
    `/v1/management/sources/${encodeURIComponent(sourceId)}`,
    { method: 'GET' },
    tenantId,
    timeoutMs,
  )
}

/** POST /api/v1/management/sources —— 登记语料来源（结果恒为 PENDING / UNVERIFIED）。 */
export function registerRagSource(
  tenantId: string,
  payload: RagRegisterSourceRequest,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource> {
  return ragRequest<RagSource>(
    '/v1/management/sources',
    { method: 'POST', body: JSON.stringify(payload) },
    tenantId,
    timeoutMs,
  )
}

/** POST /api/v1/management/sources/batch —— 批量登记（逐条成败、整体不回滚）。 */
export function bulkRegisterRagSources(
  tenantId: string,
  registeredBy: string,
  items: RagBulkSourceItem[],
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagBulkImportResponse> {
  return ragRequest<RagBulkImportResponse>(
    '/v1/management/sources/batch',
    { method: 'POST', body: JSON.stringify({ registeredBy, sources: items }) },
    tenantId,
    timeoutMs,
  )
}

/** POST /api/v1/management/sources/{sourceId}/verification —— 校验状态流转。 */
export function applyRagVerification(
  tenantId: string,
  sourceId: string,
  payload: RagVerificationRequest,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagSource> {
  return ragRequest<RagSource>(
    `/v1/management/sources/${encodeURIComponent(sourceId)}/verification`,
    { method: 'POST', body: JSON.stringify(payload) },
    tenantId,
    timeoutMs,
  )
}

/** GET /api/v1/management/sources/{sourceId}/documents —— 列出来源的载体。 */
export function listRagDocuments(
  tenantId: string,
  sourceId: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDocument[]> {
  return ragRequest<RagDocument[]>(
    `/v1/management/sources/${encodeURIComponent(sourceId)}/documents`,
    { method: 'GET' },
    tenantId,
    timeoutMs,
  )
}

/** GET /api/v1/management/documents/{documentId}/chunks —— 列出文档的片段摘要（limit ≤ 500）。 */
export function listRagChunks(
  tenantId: string,
  documentId: string,
  limit = 100,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagChunk[]> {
  return ragRequest<RagChunk[]>(
    `/v1/management/documents/${encodeURIComponent(documentId)}/chunks${toQuery({ limit })}`,
    { method: 'GET' },
    tenantId,
    timeoutMs,
  )
}

/** POST /api/v1/management/documents/{documentId}/verification —— 载体级校验流转。 */
export function applyRagDocumentVerification(
  tenantId: string,
  documentId: string,
  payload: RagDocumentVerificationRequest,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDocument> {
  return ragRequest<RagDocument>(
    `/v1/management/documents/${encodeURIComponent(documentId)}/verification`,
    { method: 'POST', body: JSON.stringify(payload) },
    tenantId,
    timeoutMs,
  )
}

/** GET /api/v1/management/issues —— 维护问题清单（「哪里坏了」的定位入口）。 */
export function getRagIssues(
  tenantId: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagMaintenanceIssues> {
  return ragRequest<RagMaintenanceIssues>(
    '/v1/management/issues',
    { method: 'GET' },
    tenantId,
    timeoutMs,
  )
}

/** POST /api/v1/management/reindex —— 重跑本租户索引（同步执行，limit ≤ 2000）。 */
export function reindexRagCorpus(
  tenantId: string,
  limit = 500,
  timeoutMs: number = DEFAULT_RAG_REINDEX_TIMEOUT_MS,
): Promise<RagReindexResult> {
  return ragRequest<RagReindexResult>(
    '/v1/management/reindex',
    { method: 'POST', body: JSON.stringify({ limit }) },
    tenantId,
    timeoutMs,
  )
}

/** GET /api/v1/management/statistics/daily-searches —— 每日检索次数。 */
export function getRagDailySearches(
  tenantId: string,
  days = 30,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDailySearchStatistics> {
  return ragRequest<RagDailySearchStatistics>(
    `/v1/management/statistics/daily-searches${toQuery({ days })}`,
    { method: 'GET' },
    tenantId,
    timeoutMs,
  )
}

/** GET /api/v1/management/statistics/daily-imports —— 每日导入语料量。 */
export function getRagDailyImports(
  tenantId: string,
  days = 30,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDailyImportStatistics> {
  return ragRequest<RagDailyImportStatistics>(
    `/v1/management/statistics/daily-imports${toQuery({ days })}`,
    { method: 'GET' },
    tenantId,
    timeoutMs,
  )
}

/* ── RAG 字典 ─────────────────────────────────────────── */

/** GET /api/v1/management/dictionaries —— 列出全部字典类型（含是否可维护、各类型条目）。 */
export function getRagDictionaries(
  tenantId: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDictionaryTypeView[]> {
  return ragRequest<RagDictionaryTypeView[]>(
    '/v1/management/dictionaries',
    { method: 'GET' },
    tenantId,
    timeoutMs,
  )
}

/** POST /api/v1/management/dictionaries/{type}/entries —— 写入 / 更新一项（仅可编辑类型）。 */
export function upsertRagDictionaryEntry(
  tenantId: string,
  type: RagDictionaryType,
  payload: RagUpsertDictionaryEntryRequest,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDictionaryEntry> {
  return ragRequest<RagDictionaryEntry>(
    `/v1/management/dictionaries/${encodeURIComponent(type)}/entries`,
    { method: 'POST', body: JSON.stringify(payload) },
    tenantId,
    timeoutMs,
  )
}

/** DELETE /api/v1/management/dictionaries/{type}/entries/{code} —— 删除一项（仅可编辑类型）。 */
export function deleteRagDictionaryEntry(
  tenantId: string,
  type: RagDictionaryType,
  code: string,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<void> {
  return ragRequest<void>(
    `/v1/management/dictionaries/${encodeURIComponent(type)}/entries/${encodeURIComponent(code)}`,
    { method: 'DELETE' },
    tenantId,
    timeoutMs,
  )
}

/* ── 检索探针（维护 · 诚实边界 RAG-R01）────────────────── */

/** POST /api/v1/retrieval —— 执行检索，返回可溯源召回片段（不写库）。 */
export function retrieveRagChunks(
  tenantId: string,
  payload: RagRetrievalRequest,
  timeoutMs: number = DEFAULT_RAG_RETRIEVAL_TIMEOUT_MS,
): Promise<RagRetrievalResponse> {
  return ragRequest<RagRetrievalResponse>(
    '/v1/retrieval',
    { method: 'POST', body: JSON.stringify(payload) },
    tenantId,
    timeoutMs,
  )
}
