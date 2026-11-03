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

import { request, ApiError, redirectLogin } from '@/api/client'
import { clearSession } from '@/auth/tokenStorage'
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
  RagInspectionItem,
  RagInspectionRecord,
  RagInspectionVerdictResult,
  RagInspectionSubmitRequest,
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
 * <p>⚠️ <b>401 的归属（2026-11-03 修复）</b>：RAG 服务与平台会话是同一身份域
 *    （验的就是平台签发的会话令牌），所以 RAG 端点的 401 = 「登录已过期」，
 *    必须清会话跳登录——共享层把 `/v1/**` 的 401 归为「跨身份域边界」
 *    对 RAG 是误判，实测后果是会话过期后页面只报「读取失败」、
 *    管理员不知道要重新登录。唯一例外是平台档写请求：Authorization 被
 *    <b>治理票据</b>覆盖，401 = 票据失效而非会话失效，原样抛出交调用方。
 *
 * @param headers 本次请求的额外头（缺省 = 只带作用域头；写请求传 {@link writeHeaders}）
 * @param usingTicket 本请求的 Authorization 是否为治理票据（平台档写请求）
 */
async function ragRequest<T>(
  path: string,
  init: RequestInit,
  target: RagTarget,
  timeoutMs: number,
  headers: Record<string, string> = scopeHeaders(target),
  usingTicket = false,
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
        undefined,
        'RAG_TIMEOUT',
      )
    }
    const err = toRagApiError(cause)
    if (
      err instanceof ApiError &&
      err.status === 401 &&
      !usingTicket
    ) {
      // 会话令牌已失效：清会话 + 跳登录（带 reason=expired，登录后可回跳）。
      clearSession()
      redirectLogin()
      throw new ApiError(401, '登录已过期，请重新登录', err.data, err.code)
    }
    throw err
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
  // 平台档写的 Authorization 是治理票据：它的 401 = 票据失效，≠ 会话过期，不触发登出。
  const usingTicket = target.scope === 'PLATFORM' && target.ticket !== undefined
  return ragRequest<T>(path, init, target, timeoutMs, writeHeaders(target), usingTicket)
}

/** 是否为 RAG 请求超时（区别于网络层 status 0）。 */
export function isRagTimeout(cause: unknown): boolean {
  return cause instanceof ApiError && cause.status === RAG_TIMEOUT_STATUS
}

/**
 * RAG 通道错误的面向管理员文案（按 4xx / 5xx 分流）。
 *
 * <p>⚠️ **为什么不能直接透传通用层的 message**：通用 fetch 层对「非平台会话前缀」的 401
 * 一律给「平台令牌对该端点不可达（身份域隔离）」——那是给**租户身份域**端点
 * （`/runs`、`/admin/**` 等）准备的归因，搬到 RAG 管理面上是**错的**：
 * RAG 的 401 只有一个含义——**它没验过平台令牌的签名**。
 * 归因错了，运维就会往「权限/身份域」方向查，而真因往往是
 * 「平台与 RAG 用的不是同一把 JWT 密钥」（评测 P0-2 实测：两端公钥指纹不一致，
 * RAG 管理面 401，而控制台把它显示成身份域隔离）。
 *
 * <p>⚠️ **5xx 必须说清「这不是控制台能修的」**：RAG 侧 500 意味着服务端故障，
 * 反复点重试没有意义，提示应直接指向 RAG 服务日志。
 *
 * @param cause 捕获的错误
 * @returns 面向管理员的一句话说明
 */
export function ragErrorMessage(cause: unknown): string {
  if (!(cause instanceof ApiError)) return '操作失败，请稍后重试'
  const status = cause.status
  const detail = cause.message

  // ⚠️ status 0 = 网络层失败（连不上/被中断），不是 HTTP 错误码。
  if (status === 0) {
    return `连不上 RAG 服务：${detail}。请确认 RAG 服务已启动（默认端口 8081）。`
  }
  if (status === 400) {
    // 400 多半是「没选租户」「缺 X-Tenant-Id」这类本地配置问题，后端文案本身就具体，保留它。
    return `RAG 服务拒绝了这次请求：${detail}`
  }
  if (status === 401) {
    return 'RAG 服务拒绝了平台令牌（验签未通过：令牌无效、过期或受众不符）。' +
      '这通常意味着平台与 RAG 没有共用同一套 JWT 密钥——请核对 jwt.env 中 ' +
      'PLATFORM_JWT_PRIVATE_KEY 与 RAG_PLATFORM_JWT_PUBLIC_KEY 是否同源。'
  }
  if (status === 403) {
    return `RAG 服务拒绝执行：${detail}`
  }
  if (status === 404) {
    return `RAG 服务上找不到该端点（${detail}）。多半是 RAG 服务与控制台版本不匹配。`
  }
  if (status === 409) {
    return `RAG 服务拒绝这次状态流转：${detail}`
  }
  if (status >= 500) {
    // ⚠️ 此前这里断言「控制台侧无法修复」，实测把一次**治理代码的作用域缺陷**
    //    （平台档取抽检状态抛 ISE）包装成了「服务故障」，排查方向被直接带偏。
    //    现在保留「看服务日志」的唯一可执行动作，但不再替后端下结论。
    return `RAG 服务返回 HTTP ${status}。这是服务端错误，控制台侧无法修复：` +
      '请查看 RAG 服务日志定位（常见原因：治理守卫/作用域判定抛异常、运维通道未接入）。'
  }
  return `请求失败（${status}）：${detail}`
}

/**
 * 把通用层的错误翻译成 RAG 专用文案。
 *
 * <p>⚠️ 保持 status 不变：调用方按 status 分流（超时 408 / 冲突 409 / 票据 403），
 * 只换文案，不换语义。
 */
function toRagApiError(cause: unknown): unknown {
  if (!(cause instanceof ApiError)) return cause
  // ⚠️ 保持 status 与 code 不变：调用方按 status 分流（超时 408 / 冲突 409 / 票据 403），
  // 只换文案，不换语义。
  return new ApiError(cause.status, ragErrorMessage(cause), cause.data, cause.code)
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

/**
 * POST /api/v1/management/sources/{sourceId}/documents —— 上传物理文档载体。
 *
 * <p>⚠️ 这是「真实文件录入」入口（区别于来源登记里手填的 `objectRef`）：
 *    把文件以 `multipart/form-data` 提交，后端亲自落桶并重算 SHA-256，
 *    前端不传、也不信任任何 objectRef / contentHash。
 *
 * <p>⚠️ `body` 是 `FormData`：`request()` 对 FormData 会保留浏览器自动生成的
 *    `multipart/form-data; boundary=...`（不会覆盖成 application/json）。
 *    写请求头（含平台档治理票据）由 {@link ragWriteRequest} 统一处理。
 *
 * @param file 选中的文件
 * @param opts.carrier 载体类型（可选；缺省由文件名后缀推断）
 * @param opts.registeredBy 操作人 UUID（可选；缺省回落到会话主体）
 */
export function uploadRagDocument(
  target: RagTarget,
  sourceId: string,
  file: File,
  opts: { carrier?: string; registeredBy?: string } = {},
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagDocument> {
  const form = new FormData()
  form.append('file', file)
  if (opts.carrier) form.append('carrier', opts.carrier)
  if (opts.registeredBy) form.append('registeredBy', opts.registeredBy)
  return ragWriteRequest<RagDocument>(
    `/v1/management/sources/${encodeURIComponent(sourceId)}/documents`,
    { method: 'POST', body: form },
    target,
    timeoutMs,
  )
}

/**
 * GET /api/v1/management/documents/{documentId}/chunks —— 列出文档的片段摘要（limit ≤ 500）。
 *
 * <p>⚠️ <b>roles 角色白名单（2026-11-03 修复）</b>：筛选在 SQL 端生效
 * （LIMIT 作用于筛选后的结果）。没有它，「前 N 条」会被正文淹没——
 * 端到端抽检实测：本册前 200 条恰好全为 EXPLANATION，
 * 26 条答案/解答片段（抽检最关键对象）翻遍默认列表也够不到。
 *
 * @param roles 角色白名单（如 `['EXAMPLE_SOLUTION','EXERCISE_ANSWER']`）；空 = 不过滤
 */
export function listRagChunks(
  target: RagTarget,
  documentId: string,
  limit = 100,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
  roles?: string[],
): Promise<RagChunk[]> {
  return ragRequest<RagChunk[]>(
    `/v1/management/documents/${encodeURIComponent(documentId)}/chunks${toQuery({
      limit,
      role: roles && roles.length > 0 ? roles.join(',') : null,
    })}`,
    { method: 'GET' },
    target,
    timeoutMs,
  )
}

/* ── 人工抽检（审核前置，V1.0.2 §5 R2）────────────────── */

/**
 * GET /api/v1/management/sources/{sourceId}/inspection —— 列出来源的抽检历史记录。
 *
 * ⚠️ 读请求走 `scopeHeaders`：租户档带 `X-Tenant-Id`，平台档不带票据
 *    （与列来源/载体的读路径一致，不污染平台审计）。平台全局语的抽检记录
 *    挂在「全局哨兵租户」行上，后端按作用域正确返回。
 */
export function listRagInspections(
  target: RagTarget,
  sourceId: string,
  limit = 200,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagInspectionRecord[]> {
  return ragRequest<RagInspectionRecord[]>(
    `/v1/management/sources/${encodeURIComponent(sourceId)}/inspection${toQuery({ limit })}`,
    { method: 'GET' },
    target,
    timeoutMs,
  )
}

/**
 * POST /api/v1/management/sources/{sourceId}/inspection —— 提交一次抽检。
 *
 * ⚠️ 写请求走 `writeHeaders`：平台档会带上治理票据（无票后端 403）。
 *    <b>空提交会被后端 400 拒绝</b>（未抽检任何片段不得提交结论），
 *    前端在调用前也必须拦住（见 `RagInspectionPanel`）。
 * ⚠️ 整组记录同事务写入并回写 `rag_source.inspection_status`，
 *    该状态是 VERIFIED 迁移的前置守卫——记录与状态必须同时可见。
 */
export function submitRagInspection(
  target: RagTarget,
  sourceId: string,
  items: RagInspectionItem[],
  inspectorId?: string | null,
  timeoutMs: number = DEFAULT_RAG_MGMT_TIMEOUT_MS,
): Promise<RagInspectionVerdictResult> {
  const body: RagInspectionSubmitRequest = {
    inspectorId: inspectorId ?? null,
    items,
  }
  return ragWriteRequest<RagInspectionVerdictResult>(
    `/v1/management/sources/${encodeURIComponent(sourceId)}/inspection`,
    { method: 'POST', body: JSON.stringify(body) },
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
