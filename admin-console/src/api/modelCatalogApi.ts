/**
 * 模型配置（Model Catalog）管理 API 封装。
 *
 * <p>端点基址（与既有 admin 端点同口径，走平台会话令牌，由 client.ts 统一注入
 * `Authorization: Bearer <token>`）：
 * <ul>
 *   <li>Feature A · 模型定义 CRUD：<code>/api/admin/models</code></li>
 *   <li>Feature B · 供应商密钥（PLATFORM_ADMIN，仅回显脱敏后的 maskedKey）：
 *       <code>/api/admin/model-credentials</code></li>
 * </ul>
 *
 * ⚠️ 仅前端：后端端点由后端特性分支提供，本文件只按契约形状对接。
 */
import { request } from '@/api/client'

/** 模型定义（GET /api/admin/models · ModelConfigResponse） */
export interface ModelConfig {
  id: string
  provider: string
  scenario: string
  modelName: string
  displayName: string | null
  baseUrl: string | null
  isDefault: boolean
  supportsTools: boolean
  supportsStreaming: boolean
  supportsVision: boolean
  /** 向量维度（embedding 模型用）；非 embedding 模型为 null */
  dimension: number | null
  isEnabled: boolean
  sortOrder: number
  description: string | null
  createdAt: string
  updatedAt: string
}

/** 模型定义创建/更新请求体（POST/PUT /api/admin/models） */
export interface ModelConfigRequest {
  provider: string
  scenario: string
  modelName: string
  displayName: string
  baseUrl: string
  isDefault: boolean
  supportsTools: boolean
  supportsStreaming: boolean
  supportsVision: boolean
  dimension: number | null
  isEnabled: boolean
  sortOrder: number
  description: string
}

/** 供应商密钥（GET /api/admin/model-credentials · ProviderCredentialResponse） */
export interface ProviderCredential {
  provider: string
  /** 脱敏后的密钥，如 `sk-…aBc9`；未配置时为 null（绝不回显明文） */
  maskedKey: string | null
  updatedAt: string | null
}

/** PUT /api/admin/model-credentials 的响应（仅回显脱敏后片段） */
export interface ProviderCredentialSetResponse {
  provider: string
  maskedKey: string
}

const MODELS_BASE = '/admin/models'
const CREDENTIALS_BASE = '/admin/model-credentials'

/* ── Feature A：模型定义 CRUD ── */

/** GET /api/admin/models → ModelConfig[] */
export function listModels(): Promise<ModelConfig[]> {
  return request<ModelConfig[]>(MODELS_BASE, { method: 'GET' })
}

/** GET /api/admin/models/:id → ModelConfig */
export function getModel(id: string): Promise<ModelConfig> {
  return request<ModelConfig>(`${MODELS_BASE}/${encodeURIComponent(id)}`, { method: 'GET' })
}

/** POST /api/admin/models（201 → ModelConfig） */
export function createModel(payload: ModelConfigRequest): Promise<ModelConfig> {
  return request<ModelConfig>(MODELS_BASE, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** PUT /api/admin/models/:id（→ ModelConfig） */
export function updateModel(id: string, payload: ModelConfigRequest): Promise<ModelConfig> {
  return request<ModelConfig>(`${MODELS_BASE}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

/** DELETE /api/admin/models/:id → 204 */
export function deleteModel(id: string): Promise<void> {
  return request<void>(`${MODELS_BASE}/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

/* ── Feature B：供应商密钥（仅脱敏回显，绝无明文） ── */

/** GET /api/admin/model-credentials → ProviderCredential[] */
export function listProviderCredentials(): Promise<ProviderCredential[]> {
  return request<ProviderCredential[]>(CREDENTIALS_BASE, { method: 'GET' })
}

/** PUT /api/admin/model-credentials（body: {provider, apiKey} → 脱敏回显） */
export function setProviderCredential(payload: {
  provider: string
  apiKey: string
}): Promise<ProviderCredentialSetResponse> {
  return request<ProviderCredentialSetResponse>(CREDENTIALS_BASE, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

/** DELETE /api/admin/model-credentials/:provider → 204 */
export function deleteProviderCredential(provider: string): Promise<void> {
  return request<void>(
    `${CREDENTIALS_BASE}/${encodeURIComponent(provider)}`,
    { method: 'DELETE' },
  )
}
