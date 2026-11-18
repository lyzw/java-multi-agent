/**
 * 对话审计配置 API 封装（平台级 · PLATFORM_ADMIN）。
 *
 * 端点：/api/platform/v1/chat-audit-config
 * 前端「系统配置 → 对话审计配置」页面读写；写入即生效（后端无缓存）。
 *
 * ⚠️ 响应/请求体只含开关 / 路径 / 阈值 / 脱敏规则，不含任何密钥。
 */
import { request } from '@/api/client'

export interface AuditScope {
  endpoints: string[]
  includePreconnectionFailures: boolean
  includeRoutingModelCalls: boolean
}

export interface AuditSinks {
  database: { enabled: boolean }
  file: { enabled: boolean; directory: string; partition: 'day' | 'hour'; fsync: boolean }
}

export interface AuditCapture {
  rawRequestBody: boolean
  rawAnswer: boolean
  thinking: boolean
  deltaChunks: boolean
  modelRequestMessages: boolean
  ragHits: boolean
  toolArgsAndResults: boolean
  headers: string[]
}

export interface AuditRedaction {
  mask: string
  sensitiveFields: string[]
}

export interface AuditSampling { rate: number }
export interface AuditWriter {
  async: boolean
  queueCapacity: number
  onSinkError: 'IGNORE' | 'LOG_ONLY'
  maxChunksPerRequest: number
}
export interface AuditRetention { days: number }
export interface AuditLimits { maxBodyBytes: number; maxAnswerChars: number }

export interface ChatAuditConfig {
  enabled: boolean
  scope: AuditScope
  sinks: AuditSinks
  capture: AuditCapture
  redaction: AuditRedaction
  sampling: AuditSampling
  writer: AuditWriter
  retention: AuditRetention
  limits: AuditLimits
}

export interface ChatAuditConfigResponse {
  config: ChatAuditConfig
  version: number
  updatedBy: string | null
  updatedAt: string | null
}

/** 允许选择的端点（与后端 ChatAuditConfig.ALLOWED_ENDPOINTS 一致）。 */
export const AUDIT_ENDPOINTS = [
  { value: '/api/agent/chat/stream', label: '平台链 · Agent 编排对话' },
  { value: '/api/knowledge/agent/chat/stream', label: '租户链 · Agent 编排对话' },
  { value: '/api/chat/stream', label: '平台链 · 直接对话' },
  { value: '/api/knowledge/chat/stream', label: '租户链 · 直接对话' },
  { value: '/api/runs', label: 'Run 执行端点' },
  { value: '/api/v1/retrieval', label: 'RAG 检索端点' },
]

const BASE = '/platform/v1/chat-audit-config'

/** GET 当前配置（回显）。 */
export function getChatAuditConfig(): Promise<ChatAuditConfigResponse> {
  return request<ChatAuditConfigResponse>(BASE, { method: 'GET' })
}

/** PUT 整体覆盖写（保存后下一个请求即生效）。 */
export function saveChatAuditConfig(config: ChatAuditConfig): Promise<ChatAuditConfigResponse> {
  return request<ChatAuditConfigResponse>(BASE, {
    method: 'PUT',
    body: JSON.stringify(config),
  })
}
