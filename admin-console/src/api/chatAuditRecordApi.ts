/**
 * 对话审计记录查询 API（平台审计 / 效果评估角色，跨租户只读）。
 *
 * 端点：
 *   GET /api/platform/v1/chat-audit-records          列表（游标分页）
 *   GET /api/platform/v1/chat-audit-records/{source}/{auditId}  详情（完整信封）
 *
 * ⚠️ 与 chatAuditConfigApi 分开：配置页写开关需 PLATFORM_ADMIN；本页读租户原文，
 *    需 platform.audit.read（SUPER_ADMIN / SECURITY_ADMIN / AUDITOR）。
 * ⚠️ 列表只给摘要（用户问题截断预览），完整问题/回答/thinking/逐分片只在详情按 auditId 取。
 * ⚠️ 后端跨租户读走 BYPASSRLS 运维通道；未接入时返回 503（不会伪装成空列表）。
 */
import { request } from '@/api/client'
import type { CursorPage } from '@/api/types'

const BASE = '/platform/v1/chat-audit-records'

export type AuditRecordSource = 'RECORD' | 'UNATTRIBUTED'

export interface ChatAuditRecordSummary {
  auditId: string
  traceId: string
  source: AuditRecordSource
  tenantId: string | null
  /** RECORD 域为 PLATFORM/TENANT；建连前失败（UNATTRIBUTED）为 null。 */
  domain: string | null
  principalId: string | null
  endpoint: string
  startedAt: string
  completedAt: string | null
  durationMs: number
  terminalState: string
  httpMethod: string
  httpStatus: number
  /** 用户问题预览（截断，仅摘要；UNATTRIBUTED 为 null）。 */
  messagePreview: string | null
  /** 拒绝原因（仅 UNATTRIBUTED）。 */
  rejectionReason: string | null
}

export interface ChatAuditRecordListParams {
  /** ALL / RECORD / UNATTRIBUTED；缺省 ALL（两域合并）。 */
  source?: AuditRecordSource | 'ALL'
  tenantId?: string
  endpoint?: string
  terminalState?: string
  from?: string
  to?: string
  cursor?: string
  limit?: number
}

/** 详情信封结构（与后端 envelope 同构，宽松索引以便前端渲染） */
export interface ChatAuditEnvelopePayload {
  auditId?: string
  traceId?: string
  endpoint?: string
  domain?: string
  tenantId?: string | null
  principalId?: string | null
  startedAt?: string
  completedAt?: string | null
  durationMs?: number
  terminalState?: string
  configVersion?: number
  http?: {
    method?: string
    uri?: string
    headers?: Record<string, string>
    status?: number
    startedAt?: string
  }
  request?: {
    rawBody?: string | null
    message?: string | null
    history?: Array<{ role: string; content: string }>
    agentId?: string | null
    topK?: number | null
    thinkingMode?: boolean
    scenario?: string | null
    contextState?: string | null
    systemPrompt?: string | null
    modelMessages?: Array<{ role: string; content: string }>
  } | null
  events?: Array<{ seq: number; at: string; name: string; data: string | null }>
  chunks?: Array<{ seq: number; at: string; kind: string; text: string }>
  result?: {
    finalAnswer?: string | null
    finishReason?: string | null
    sseFrameCount?: number
    firstTokenMs?: number
    totalTokens?: number
    clientDisconnected?: boolean
  } | null
  error?: { phase?: string | null; code: string; message: string } | null
  bodyTruncated?: boolean
  answerTruncated?: boolean
  /** UNATTRIBUTED 的 metadata（仅结构性元数据，无原文）。 */
  [key: string]: unknown
}

export interface ChatAuditRecordDetail {
  source: AuditRecordSource
  auditId: string
  traceId: string
  tenantId: string | null
  domain: string | null
  principalId: string | null
  endpoint: string
  startedAt: string
  completedAt: string | null
  durationMs: number
  terminalState: string
  httpMethod: string
  httpStatus: number
  configVersion: number
  rejectionReason: string | null
  /** 完整信封 JSON（RECORD）或元数据 JSON（UNATTRIBUTED）。 */
  payload: ChatAuditEnvelopePayload
}

export const AUDIT_SOURCE_OPTIONS: Array<{ value: AuditRecordSource | 'ALL'; label: string }> = [
  { value: 'ALL', label: '全部来源' },
  { value: 'RECORD', label: '已归属请求（含原文）' },
  { value: 'UNATTRIBUTED', label: '建连前失败（仅元数据）' },
]

/** 终态选项（与 DDL 白名单一致）。 */
export const AUDIT_TERMINAL_OPTIONS = [
  'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'CLIENT_DISCONNECTED', 'REJECTED',
]

function buildQuery(params: ChatAuditRecordListParams): string {
  const qs = new URLSearchParams()
  const set = (key: string, value: string | number | undefined) => {
    if (value !== undefined && value !== '') qs.set(key, String(value))
  }
  set('source', params.source)
  set('tenantId', params.tenantId)
  set('endpoint', params.endpoint)
  set('terminalState', params.terminalState)
  set('from', params.from)
  set('to', params.to)
  set('cursor', params.cursor)
  set('limit', params.limit)
  const q = qs.toString()
  return q ? `?${q}` : ''
}

/** 列表（游标分页）。 */
export function listChatAuditRecords(
  params: ChatAuditRecordListParams = {},
): Promise<CursorPage<ChatAuditRecordSummary>> {
  return request<CursorPage<ChatAuditRecordSummary>>(`${BASE}${buildQuery(params)}`, {
    method: 'GET',
  })
}

/** 详情（完整信封原文）。 */
export function getChatAuditRecordDetail(
  source: AuditRecordSource,
  auditId: string,
): Promise<ChatAuditRecordDetail> {
  return request<ChatAuditRecordDetail>(`${BASE}/${source}/${encodeURIComponent(auditId)}`, {
    method: 'GET',
  })
}
