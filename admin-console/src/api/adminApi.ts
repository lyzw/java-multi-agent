/**
 * 管理后台 API 封装
 *
 * P0 真实对接：login / getRoles（既有鉴权端点）。
 * P0.5 真实对接：租户管理端点（GET /api/admin/tenants/self*、平台级 /api/admin/tenants*）、
 *   配置覆盖（GET /api/config/overrides，复用既有端点）。
 * P1 真实对接：Agent CRUD（/api/agents）+ Tool/Skill/MCP 只读目录（/api/cap/catalog）。
 * P2 真实对接：发布审批三步工作流（/api/cap/config/publishes）。
 * P3 真实对接：依赖图谱与影响分析（/api/admin/dependencies/*，只读）。
 */

import { request } from '@/api/client'
import type {
  LoginRequest,
  LoginResponse,
  RolesResponse,
  DashboardSummary,
  CursorPage,
  TenantResponse,
  PrincipalResponse,
  TenantSummary,
  TenantDetailResponse,
  RlsVerifyResponse,
  ConfigOverrideItem,
  AgentItem,
  AgentVersion,
  CreateAgentRequest,
  PublishVersionRequest,
  ToolCatalogItem,
  SkillCatalogItem,
  McpCatalogItem,
  PublishRequestItem,
  PublishRelease,
  PublishPointer,
  SubmitPublishRequest,
  PublishDecision,
  DependencyGraphResponse,
  ImpactReport,
  ImpactEntityType,
  MonitoringHealth,
  DenyAlert,
  AuditLogEntry,
} from '@/api/types'

/** POST /api/auth/token —— 无需鉴权 */
export function login(payload: LoginRequest): Promise<LoginResponse> {
  return request<LoginResponse>('/auth/token', {
    method: 'POST',
    body: JSON.stringify(payload),
  }, false)
}

/** GET /api/auth/roles —— 按令牌现查库中登记角色（G1） */
export function getRoles(): Promise<RolesResponse> {
  return request<RolesResponse>('/auth/roles', { method: 'GET' })
}

/** GET /api/admin/dashboard —— 聚合概览（后续阶段实现） */
export function getDashboard(): Promise<DashboardSummary> {
  return request<DashboardSummary>('/admin/dashboard', { method: 'GET' })
}

/* ── P0.5 租户管理 ── */

/** GET /api/admin/tenants/self —— 当前租户概要（租户内，已认证即可） */
export function getMyTenant(): Promise<TenantSummary> {
  return request<TenantSummary>('/admin/tenants/self', { method: 'GET' })
}

/** GET /api/admin/tenants/self/principals —— 当前租户主体（租户内） */
export function getMyPrincipals(): Promise<PrincipalResponse[]> {
  return request<PrincipalResponse[]>('/admin/tenants/self/principals', { method: 'GET' })
}

/** GET /api/admin/tenants —— 租户注册表（平台级，PLATFORM_ADMIN） */
export function getTenantList(params?: {
  status?: string
  limit?: number
  cursor?: string
}): Promise<CursorPage<TenantResponse>> {
  const qs = new URLSearchParams()
  if (params?.status) qs.set('status', params.status)
  if (params?.limit) qs.set('limit', String(params.limit))
  if (params?.cursor) qs.set('cursor', params.cursor)
  const q = qs.toString()
  return request<CursorPage<TenantResponse>>(`/admin/tenants${q ? `?${q}` : ''}`, { method: 'GET' })
}

/** GET /api/admin/tenants/{id} —— 租户详情（平台级） */
export function getTenantDetail(id: string): Promise<TenantDetailResponse> {
  return request<TenantDetailResponse>(`/admin/tenants/${id}`, { method: 'GET' })
}

/** GET /api/admin/tenants/{id}/principals —— 跨租户列出主体（平台级） */
export function getTenantPrincipals(id: string): Promise<PrincipalResponse[]> {
  return request<PrincipalResponse[]>(`/admin/tenants/${id}/principals`, { method: 'GET' })
}

/** POST /api/admin/tenants —— 开通租户（平台级） */
export function provisionTenant(payload: { slug: string; name: string }): Promise<TenantResponse> {
  return request<TenantResponse>('/admin/tenants', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** PUT /api/admin/tenants/{id}/status —— 生命周期置状态（平台级） */
export function updateTenantStatus(
  id: string,
  status: 'ACTIVE' | 'SUSPENDED' | 'DECOMMISSIONED',
): Promise<TenantResponse> {
  return request<TenantResponse>(`/admin/tenants/${id}/status`, {
    method: 'PUT',
    body: JSON.stringify({ status }),
  })
}

/** POST /api/admin/tenants/{id}/rls-verify —— RLS 隔离校验（平台级） */
export function verifyTenantRls(id: string): Promise<RlsVerifyResponse> {
  return request<RlsVerifyResponse>(`/admin/tenants/${id}/rls-verify`, {
    method: 'POST',
  })
}

/** GET /api/config/overrides —— 当前租户配置覆盖（复用既有端点） */
export function getConfigOverrides(): Promise<ConfigOverrideItem[]> {
  return request<ConfigOverrideItem[]>('/config/overrides', { method: 'GET' })
}

/* ── P1 实体管理：Agent（租户级，复用既有 /api/agents） ── */

/** GET /api/agents —— 列出本租户 Agent（游标分页，含最新版本摘要） */
export function getAgentList(params?: {
  limit?: number
  cursor?: string
}): Promise<CursorPage<AgentItem>> {
  const qs = new URLSearchParams()
  if (params?.limit) qs.set('limit', String(params.limit))
  if (params?.cursor) qs.set('cursor', params.cursor)
  const q = qs.toString()
  return request<CursorPage<AgentItem>>(`/agents${q ? `?${q}` : ''}`, { method: 'GET' })
}

/** GET /api/agents/{id} —— Agent 详情（含最新版本摘要） */
export function getAgent(id: string): Promise<AgentItem> {
  return request<AgentItem>(`/agents/${id}`, { method: 'GET' })
}

/** GET /api/agents/{id}/versions —— 版本历史（不可变快照，降序） */
export function getAgentVersions(id: string): Promise<AgentVersion[]> {
  return request<AgentVersion[]>(`/agents/${id}/versions`, { method: 'GET' })
}

/** POST /api/agents —— 创建 Agent（创建人取自令牌，不取自请求体） */
export function createAgent(payload: CreateAgentRequest): Promise<AgentItem> {
  return request<AgentItem>('/agents', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** POST /api/agents/{id}/versions —— 发布新版本（definition 为不可变快照） */
export function publishAgentVersion(
  id: string,
  payload: PublishVersionRequest,
): Promise<AgentVersion> {
  return request<AgentVersion>(`/agents/${id}/versions`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/* ── P1 实体管理：Tool / Skill / MCP（平台级，复用 /api/cap/catalog 只读目录） ──
   ⚠️ 平台级对象变更走带外运维通道（与 P0.5 作用域红线一致），本控制台只读。 */

/** GET /api/cap/catalog/tools —— Tool 目录（只读） */
export function getToolCatalog(): Promise<ToolCatalogItem[]> {
  return request<ToolCatalogItem[]>('/cap/catalog/tools', { method: 'GET' })
}

/** GET /api/cap/catalog/tools/{id} —— 单个 Tool（只读） */
export function getTool(id: string): Promise<ToolCatalogItem> {
  return request<ToolCatalogItem>(`/cap/catalog/tools/${id}`, { method: 'GET' })
}

/** GET /api/cap/catalog/skills —— Skill 目录（只读） */
export function getSkillCatalog(): Promise<SkillCatalogItem[]> {
  return request<SkillCatalogItem[]>('/cap/catalog/skills', { method: 'GET' })
}

/** GET /api/cap/catalog/skills/{id} —— 单个 Skill（只读） */
export function getSkill(id: string): Promise<SkillCatalogItem> {
  return request<SkillCatalogItem>(`/cap/catalog/skills/${id}`, { method: 'GET' })
}

/** GET /api/cap/catalog/mcps —— MCP Server 目录（只读） */
export function getMcpCatalog(): Promise<McpCatalogItem[]> {
  return request<McpCatalogItem[]>('/cap/catalog/mcps', { method: 'GET' })
}

/** GET /api/cap/catalog/mcps/{id} —— 单个 MCP Server（只读） */
export function getMcp(id: string): Promise<McpCatalogItem> {
  return request<McpCatalogItem>(`/cap/catalog/mcps/${id}`, { method: 'GET' })
}

/* ── P2 发布审批（复用既有 /api/cap/config/publishes 三步工作流） ──
   ⚠️ 平台级 scope 由后端显式拒绝（403）：平台级对象走带外运维通道，
      控制台仅发起租户级申请（P0.5 红线延续）。 */

/** GET /api/cap/config/publishes —— 变更申请队列（游标分页，按状态过滤） */
export function getPublishRequests(params?: {
  state?: string
  limit?: number
  cursor?: string
}): Promise<CursorPage<PublishRequestItem>> {
  const qs = new URLSearchParams()
  if (params?.state) qs.set('state', params.state)
  if (params?.limit) qs.set('limit', String(params.limit))
  if (params?.cursor) qs.set('cursor', params.cursor)
  const q = qs.toString()
  return request<CursorPage<PublishRequestItem>>(
    `/cap/config/publishes${q ? `?${q}` : ''}`,
    { method: 'GET' },
  )
}

/** GET /api/cap/config/publishes/{requestId} —— 单条申请（详情深链） */
export function getPublishRequest(requestId: string): Promise<PublishRequestItem> {
  return request<PublishRequestItem>(`/cap/config/publishes/${requestId}`, { method: 'GET' })
}

/** POST /api/cap/config/publishes —— 提交变更申请（不写配置表） */
export function submitPublishRequest(
  payload: SubmitPublishRequest,
): Promise<PublishRequestItem> {
  return request<PublishRequestItem>('/cap/config/publishes', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** POST /api/cap/config/publishes/{requestId}/decision —— 审批（不写配置表） */
export function decidePublishRequest(
  requestId: string,
  payload: PublishDecision,
): Promise<PublishRequestItem> {
  return request<PublishRequestItem>(`/cap/config/publishes/${requestId}/decision`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** POST /api/cap/config/publishes/{requestId}/apply —— 执行（此步才真正写入） */
export function applyPublishRequest(requestId: string): Promise<PublishRequestItem> {
  return request<PublishRequestItem>(`/cap/config/publishes/${requestId}/apply`, {
    method: 'POST',
  })
}

/** GET /api/cap/config/pointer —— 当前发布指针（CAS 基线来源） */
export function getPublishPointer(): Promise<PublishPointer> {
  return request<PublishPointer>('/cap/config/pointer', { method: 'GET' })
}

/** GET /api/cap/config/releases —— 发布时间线（含 ROLLED_BACK，回滚目标来源） */
export function getPublishReleases(params?: {
  limit?: number
  cursor?: string
}): Promise<CursorPage<PublishRelease>> {
  const qs = new URLSearchParams()
  if (params?.limit) qs.set('limit', String(params.limit))
  if (params?.cursor) qs.set('cursor', params.cursor)
  const q = qs.toString()
  return request<CursorPage<PublishRelease>>(
    `/cap/config/releases${q ? `?${q}` : ''}`,
    { method: 'GET' },
  )
}

/* ── P3 依赖图谱与影响分析（只读） ── */

/** GET /api/admin/dependencies/graph —— 能力闭包图谱（节点+边，租户作用域） */
export function getDependencyGraph(): Promise<DependencyGraphResponse> {
  return request<DependencyGraphResponse>('/admin/dependencies/graph', { method: 'GET' })
}

/**
 * GET /api/admin/dependencies/impact?type=&id= —— 影响分析
 *
 * ⚠️ 只读：返回「引用了谁 + 改/删它会命中谁」，不提供任何变更能力。
 * type 合法值：agent | tool | skill | mcp_server（后端兼容 mcp 别名）。
 */
export function getDependencyImpact(
  type: ImpactEntityType,
  id: string,
): Promise<ImpactReport> {
  const qs = new URLSearchParams({ type, id })
  return request<ImpactReport>(
    `/admin/dependencies/impact?${qs.toString()}`,
    { method: 'GET' },
  )
}

/* ── P4 运行监控（只读） ── */
/**
 * GET /api/admin/monitoring/health —— 租户运行健康快照（聚合视图）
 *
 * ⚠️ 只读：健康是观测结论，处置动作在各自的既有通道（查审计 / 改配置 / 走发布）。
 * ⚠️ 数据源不可用时后端返回 503（不返空快照冒充正常），此处直接抛错由页面呈现。
 */
export function getMonitoringHealth(params?: {
  windowHours?: number
}): Promise<MonitoringHealth> {
  const qs = new URLSearchParams()
  if (params?.windowHours) qs.set('windowHours', String(params.windowHours))
  const q = qs.toString()
  return request<MonitoringHealth>(
    `/admin/monitoring/health${q ? `?${q}` : ''}`,
    { method: 'GET' },
  )
}

/**
 * GET /api/admin/monitoring/denies —— 近期拒绝告警列表（下钻视图）
 *
 * ⚠️ 只返回 DENY 记录：ALLOW 不构成告警素材（后端口径已收窄）。
 */
export function getMonitoringDenies(params?: {
  windowHours?: number
  limit?: number
}): Promise<DenyAlert[]> {
  const qs = new URLSearchParams()
  if (params?.windowHours) qs.set('windowHours', String(params.windowHours))
  if (params?.limit) qs.set('limit', String(params.limit))
  const q = qs.toString()
  return request<DenyAlert[]>(
    `/admin/monitoring/denies${q ? `?${q}` : ''}`,
    { method: 'GET' },
  )
}

/* ── P5 审计日志（只读，复用既有 /api/audit） ──
   ⚠️ audit_record 有 forbid_audit_mutation 触发器：无任何写/删路径。
   查询行为本身由后端 recordAccess 留痕（fail-open）。 */

/** GET /api/audit —— 审计流水（游标分页，ledger 维度筛选） */
export function getAuditLogs(params?: {
  action?: string
  resourceType?: string
  resourceId?: string
  from?: string
  to?: string
  limit?: number
  cursor?: string
}): Promise<CursorPage<AuditLogEntry>> {
  const qs = new URLSearchParams()
  if (params?.action) qs.set('action', params.action)
  if (params?.resourceType) qs.set('resourceType', params.resourceType)
  if (params?.resourceId) qs.set('resourceId', params.resourceId)
  if (params?.from) qs.set('from', params.from)
  if (params?.to) qs.set('to', params.to)
  if (params?.limit) qs.set('limit', String(params.limit))
  if (params?.cursor) qs.set('cursor', params.cursor)
  const q = qs.toString()
  return request<CursorPage<AuditLogEntry>>(`/audit${q ? `?${q}` : ''}`, { method: 'GET' })
}
