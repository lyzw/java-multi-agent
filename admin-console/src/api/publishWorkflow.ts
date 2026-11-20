/**
 * 发布流程管理 API：策略配置 / 草稿 / 业务审计。
 * 对应后端：
 *   /api/cap/publish-policies
 *   /api/cap/drafts
 *   /api/cap/audit-logs
 */
import { request } from './client'

// ── 发布策略 ─────────────────────────────────────────────────

export type PublishPolicyLevel = 'GLOBAL' | 'RESOURCE_TYPE' | 'INSTANCE'
export type PublishMode = 'DIRECT' | 'MANUAL' | 'APPROVAL_MANUAL' | 'APPROVAL_AUTO'

export interface PublishPolicyItem {
  policyId: string
  scope: string
  tenantId: string | null
  policyLevel: PublishPolicyLevel
  resourceKind?: string
  resourceId?: string
  publishMode: PublishMode
  enabled: boolean
  updatedBy: string
  createdAt: string
  updatedAt: string
}

export interface PublishPolicyCreate {
  policyLevel: PublishPolicyLevel
  resourceKind?: string
  resourceId?: string
  publishMode: PublishMode
  enabled?: boolean
}

/** POST /api/cap/publish-policies */
export function createPublishPolicy(body: PublishPolicyCreate) {
  return request<PublishPolicyItem>('/cap/publish-policies', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/** GET /api/cap/publish-policies */
export function listPublishPolicies(scope?: string) {
  const q = scope ? `scope=${scope}` : ''
  return request<PublishPolicyItem[]>(`/cap/publish-policies${q ? `?${q}` : ''}`)
}

/** GET /api/cap/publish-policies/{id} */
export function getPublishPolicy(id: string, scope?: string) {
  const q = scope ? `scope=${scope}` : ''
  return request<PublishPolicyItem>(`/cap/publish-policies/${id}${q ? `?${q}` : ''}`)
}

/** PUT /api/cap/publish-policies/{id} */
export function updatePublishPolicy(id: string, body: Partial<PublishPolicyCreate>) {
  return request<PublishPolicyItem>(`/cap/publish-policies/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

/** DELETE /api/cap/publish-policies/{id} */
export function deletePublishPolicy(id: string, scope?: string) {
  const q = scope ? `scope=${scope}` : ''
  return request<void>(`/cap/publish-policies/${id}${q ? `?${q}` : ''}`, { method: 'DELETE' })
}

/** GET /api/cap/publish-policies/resolve?resourceKind=&resourceId= */
export function resolvePublishMode(resourceKind: string, resourceId?: string) {
  const params = new URLSearchParams({ resourceKind })
  if (resourceId) params.set('resourceId', resourceId)
  return request<{ publishMode: PublishMode }>(
    `/cap/publish-policies/resolve?${params.toString()}`
  )
}

// ── 变更草稿 ──────────────────────────────────────────────────

export type DraftState =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'PENDING_PUBLISH'
  | 'APPROVAL_REJECTED'
  | 'PUBLISH_FAILED'
  | 'DISCARDED'

export type FailStage =
  | 'PRE_CHECK'
  | 'PARAM_VALIDATE'
  | 'DEP_CHECK'
  | 'POINTER_CAS'
  | 'RUNTIME_SYNC'

export interface ConfigDraftItem {
  draftId: string
  scope: string
  tenantId: string | null
  resourceKind: string
  resourceId: string
  resourceName?: string
  baseVersion: number
  draftVersion: number
  draftState: DraftState
  failureStage?: FailStage
  draftConfig?: string
  failureDetails?: string
  rejectionReason?: string
  operatorId: string
  createdAt: string
  updatedAt: string
}

export interface PageResponse<T> {
  items: T[]
  nextCursor?: string | null
}

export interface DraftCreate {
  resourceKind: string
  resourceId: string
  resourceName?: string
  baseVersion?: number
  draftConfig: string
}

/** POST /api/cap/drafts */
export function createDraft(body: DraftCreate) {
  return request<ConfigDraftItem>('/cap/drafts', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/** PUT /api/cap/drafts/{id} */
export function saveDraft(id: string, draftConfig: string) {
  return request<ConfigDraftItem>(`/cap/drafts/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ draftConfig }),
  })
}

/** GET /api/cap/drafts */
export function listDrafts(params?: { state?: DraftState; cursor?: string; limit?: number }) {
  const qs = new URLSearchParams()
  if (params?.state) qs.set('state', params.state)
  if (params?.cursor) qs.set('cursor', params.cursor)
  if (params?.limit) qs.set('limit', String(params.limit))
  const q = qs.toString()
  return request<PageResponse<ConfigDraftItem>>(`/cap/drafts${q ? `?${q}` : ''}`)
}

/** GET /api/cap/drafts/active?resourceKind=&resourceId= */
export function getActiveDraft(resourceKind: string, resourceId: string) {
  const q = new URLSearchParams({ resourceKind, resourceId }).toString()
  return request<ConfigDraftItem | null>(`/cap/drafts/active?${q}`)
}

/** POST /api/cap/drafts/{id}/submit-approval */
export function submitDraftApproval(id: string, reason?: string) {
  const q = reason ? `?reason=${encodeURIComponent(reason)}` : ''
  return request<ConfigDraftItem>(`/cap/drafts/${id}/submit-approval${q}`, { method: 'POST' })
}

/** POST /api/cap/drafts/{id}/approve */
export function approveDraft(id: string, reason?: string) {
  const q = reason ? `?reason=${encodeURIComponent(reason)}` : ''
  return request<ConfigDraftItem>(`/cap/drafts/${id}/approve${q}`, { method: 'POST' })
}

/** POST /api/cap/drafts/{id}/reject?reason= */
export function rejectDraft(id: string, reason: string) {
  return request<ConfigDraftItem>(`/cap/drafts/${id}/reject?reason=${encodeURIComponent(reason)}`, { method: 'POST' })
}

/** POST /api/cap/drafts/{id}/publish */
export function publishDraft(id: string) {
  return request<ConfigDraftItem>(`/cap/drafts/${id}/publish`, { method: 'POST' })
}

/** POST /api/cap/drafts/{id}/discard */
export function discardDraft(id: string, reason?: string) {
  const q = reason ? `?reason=${encodeURIComponent(reason)}` : ''
  return request<ConfigDraftItem>(`/cap/drafts/${id}/discard${q}`, { method: 'POST' })
}

/** POST /api/cap/drafts/{id}/retry */
export function retryDraft(id: string) {
  return request<ConfigDraftItem>(`/cap/drafts/${id}/retry`, { method: 'POST' })
}

// ── 业务审计日志 ───────────────────────────────────────────────

export type AuditEventType =
  | 'CREATE'
  | 'SAVE_DRAFT'
  | 'SUBMIT_APPROVAL'
  | 'APPROVE'
  | 'REJECT'
  | 'PUBLISH_SUCCESS'
  | 'PUBLISH_FAILED'
  | 'ROLLBACK'
  | 'DISCARD'
  | 'LIFECYCLE'

export interface AuditLogItem {
  auditId: string
  scope: string
  tenantId: string | null
  resourceKind: string
  resourceId: string
  resourceName?: string
  eventType: AuditEventType
  fromVersion?: number | null
  toVersion?: number | null
  fromState?: string
  toState?: string
  beforeConfig?: string
  afterConfig?: string
  diffSummary?: string
  operatorId: string
  reason?: string
  createdAt: string
}

export interface AuditLogQueryParams {
  resourceKind?: string
  resourceId?: string
  eventType?: AuditEventType
  operatorId?: string
  fromTime?: string
  toTime?: string
  cursor?: string
  limit?: number
}

/** GET /api/cap/audit-logs */
export function queryAuditLogs(params: AuditLogQueryParams = {}) {
  const qs = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v) qs.set(k, String(v))
  })
  const q = qs.toString()
  return request<PageResponse<AuditLogItem>>(`/cap/audit-logs${q ? `?${q}` : ''}`)
}
