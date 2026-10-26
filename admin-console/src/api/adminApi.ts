/**
 * 管理后台 API 封装
 *
 * P0 真实对接：login / getRoles（既有鉴权端点）。
 * P0.5 真实对接：租户管理端点（GET /api/admin/tenants/self*、平台级 /api/admin/tenants*）、
 *   配置覆盖（GET /api/config/overrides，复用既有端点）。
 * P1 真实对接：Agent CRUD（/api/agents）+ Tool/Skill/MCP 只读目录（/api/cap/catalog）。
 * P2 真实对接：发布审批三步工作流（/api/cap/config/publishes）。
 * P3 真实对接：依赖图谱与影响分析（/api/admin/dependencies/*，只读）。
 * C34-3 真实对接：平台级能力对象写通道（/api/cap/platform/*，PLATFORM_ADMIN 闸门）、
 *   目录关系视图（/api/cap/catalog 下的 relations 端点）、多级 Agent 权威定义
 *   （/api/cap/agents/{id}/definition）、委派拓扑（/api/cap/delegations）。
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
  ToolRelations,
  SkillRelations,
  McpRelations,
  AgentDefinition,
  DelegationEdge,
  PlatformPublishRequest,
  PlatformLifecycleRequest,
  PlatformRequestItem,
  PlatformDecision,
  PlatformUserDetail,
  CreatePlatformUserRequest,
  UpdatePlatformUserRequest,
  UserRolesResponse,
  PlatformAgentRegistryItem,
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
   ⚠️ 目录**读取**仍是对全体租户可见的（能力清单是攻击面枚举素材，故读取同样鉴权）；
   但**变更**从 C34-3 起不再走带外运维通道，而是接平台级三步工作流
   （PLATFORM_ADMIN 闸门 + 运维通道），见本文件末尾「平台级能力对象写通道」。 */

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

/* ── C34-3：能力目录关系视图（只读，第三十四批） ──
   ⚠️ 关系视图把「声明了但对象不存在」的 id **显式列为 missing**，
   而不是悄悄从清单里消失 —— 悄悄消失会让运维以为「没有这个依赖」。 */

/** GET /api/cap/catalog/tools/{id}/relations —— Tool 的关系（所属 MCP Server；null = 内置 Tool） */
export function getToolRelations(id: string): Promise<ToolRelations> {
  return request<ToolRelations>(`/cap/catalog/tools/${id}/relations`, { method: 'GET' })
}

/** GET /api/cap/catalog/skills/{id}/relations —— Skill 的关系（声明的 Tool / MCP + 缺失项） */
export function getSkillRelations(id: string): Promise<SkillRelations> {
  return request<SkillRelations>(`/cap/catalog/skills/${id}/relations`, { method: 'GET' })
}

/**
 * GET /api/cap/catalog/mcps/{id}/relations —— MCP Server 的关系。
 *
 * ⚠️ `exposedToolAllowlist`（允许集）与 `tools`（实际登记）是**两回事**，
 * 允许集可以更宽、登记可以更窄 —— 页面必须分开呈现。
 */
export function getMcpRelations(id: string): Promise<McpRelations> {
  return request<McpRelations>(`/cap/catalog/mcps/${id}/relations`, { method: 'GET' })
}

/* ── C34-3：多级 Agent 的权威定义（只读） ── */

/**
 * GET /api/cap/agents/{id}/definition —— Agent 的权威定义（cap_agent_def 最新版本）。
 *
 * ⚠️ 与 `GET /api/agents/{id}` 的 `latestDefinition` **不是同一份东西**：
 * 后者是旧体系（agent / agent_version）的自由 JSON 快照，取不到关系字段。
 */
export function getAgentDefinition(id: string): Promise<AgentDefinition> {
  return request<AgentDefinition>(`/cap/agents/${id}/definition`, { method: 'GET' })
}

/* ── C34-3：委派拓扑（只读，cap_agent_child_ref 投影） ── */

/** GET /api/cap/delegations —— 本租户全部委派边（拓扑图用） */
export function getDelegations(): Promise<DelegationEdge[]> {
  return request<DelegationEdge[]>('/cap/delegations', { method: 'GET' })
}

/** GET /api/cap/delegations/parents/{id}/children —— 某父 Agent 的出向边（我依赖谁） */
export function getDelegationChildren(id: string): Promise<DelegationEdge[]> {
  return request<DelegationEdge[]>(`/cap/delegations/parents/${id}/children`, { method: 'GET' })
}

/**
 * GET /api/cap/delegations/children/{id}/parents —— 某子 Agent 的入向边（谁依赖我）。
 *
 * ⚠️ 不限子版本：停用的是那个 **Agent**，不是某一版 ——
 * 任何父引用了它的任何一版，都构成「抽走它会让那个父悬空」的事实。
 */
export function getDelegationParents(id: string): Promise<DelegationEdge[]> {
  return request<DelegationEdge[]>(`/cap/delegations/children/${id}/parents`, { method: 'GET' })
}

/* ── C34-3：平台级能力对象写通道（第三十四批 · C34-2 后端契约） ──
   ⚠️ 39 批起本组函数指向**平台链**端点（/api/platform/v1/capabilities/*）：
      admin-console 使用平台令牌（aud=admin-console-api），旧租户链
      /api/cap/platform/* 的端点对平台令牌不可达；后端已在平台链落同形状端点，
      申请→审批→执行三步与双人复核纪律不变。 */

/** POST /api/platform/v1/capabilities/publish-requests —— 提交平台级发布申请（不写配置表） */
export function submitPlatformPublishRequest(
  payload: PlatformPublishRequest,
): Promise<PlatformRequestItem> {
  return request<PlatformRequestItem>('/platform/v1/capabilities/publish-requests', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** POST /api/platform/v1/capabilities/publish-requests/{requestId}/decision —— 审批（不写配置表） */
export function decidePlatformPublishRequest(
  requestId: string,
  payload: PlatformDecision,
): Promise<PlatformRequestItem> {
  return request<PlatformRequestItem>(
    `/platform/v1/capabilities/publish-requests/${requestId}/decision`,
    {
      method: 'POST',
      body: JSON.stringify(payload),
    },
  )
}

/** POST /api/platform/v1/capabilities/publish-requests/{requestId}/apply —— 执行（此步才真正写入） */
export function applyPlatformPublishRequest(requestId: string): Promise<PlatformRequestItem> {
  return request<PlatformRequestItem>(
    `/platform/v1/capabilities/publish-requests/${requestId}/apply`,
    { method: 'POST' },
  )
}

/** GET /api/platform/v1/capabilities/publish-requests —— 平台级变更申请队列 */
export function listPlatformCapabilityRequests(params?: {
  state?: string
  limit?: number
}): Promise<PlatformRequestItem[]> {
  const qs = new URLSearchParams()
  if (params?.state) qs.set('state', params.state)
  if (params?.limit) qs.set('limit', String(params.limit))
  const q = qs.toString()
  return request<PlatformRequestItem[]>(
    `/platform/v1/capabilities/publish-requests${q ? `?${q}` : ''}`,
    { method: 'GET' },
  )
}

/**
 * POST /api/platform/v1/capabilities/lifecycle-requests —— 提交平台级生命周期迁移申请（停用）。
 *
 * ⚠️ **这是「停用」通道，不是物理删除**：已发布行不可变（DB 触发器），
 * 正确语义是迁移到 DEPRECATED（单向终态）。
 */
export function submitPlatformLifecycleRequest(
  payload: PlatformLifecycleRequest,
): Promise<PlatformRequestItem> {
  return request<PlatformRequestItem>('/platform/v1/capabilities/lifecycle-requests', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/* ── 平台控制台 · 能力目录读（平台链 /api/platform/v1/capabilities/*） ── */

/** GET /api/platform/v1/capabilities/tools —— Tool 目录（平台令牌；全字段） */
export function getConsoleToolCatalog(): Promise<ToolCatalogItem[]> {
  return request<ToolCatalogItem[]>('/platform/v1/capabilities/tools', { method: 'GET' })
}

/** GET /api/platform/v1/capabilities/tools/{id} —— 单个 Tool（全字段） */
export function getConsoleTool(id: string): Promise<ToolCatalogItem> {
  return request<ToolCatalogItem>(`/platform/v1/capabilities/tools/${id}`, { method: 'GET' })
}

/** GET /api/platform/v1/capabilities/skills —— Skill 目录 */
export function getConsoleSkillCatalog(): Promise<SkillCatalogItem[]> {
  return request<SkillCatalogItem[]>('/platform/v1/capabilities/skills', { method: 'GET' })
}

/** GET /api/platform/v1/capabilities/skills/{id} —— 单个 Skill */
export function getConsoleSkill(id: string): Promise<SkillCatalogItem> {
  return request<SkillCatalogItem>(`/platform/v1/capabilities/skills/${id}`, { method: 'GET' })
}

/** GET /api/platform/v1/capabilities/mcps —— MCP Server 目录 */
export function getConsoleMcpCatalog(): Promise<McpCatalogItem[]> {
  return request<McpCatalogItem[]>('/platform/v1/capabilities/mcps', { method: 'GET' })
}

/** GET /api/platform/v1/capabilities/mcps/{id} —— 单个 MCP Server */
export function getConsoleMcp(id: string): Promise<McpCatalogItem> {
  return request<McpCatalogItem>(`/platform/v1/capabilities/mcps/${id}`, { method: 'GET' })
}

/** GET /api/platform/v1/capabilities/skills/{id}/relations —— Skill 的关系（含缺失项） */
export function getConsoleSkillRelations(id: string): Promise<SkillRelations> {
  return request<SkillRelations>(`/platform/v1/capabilities/skills/${id}/relations`, {
    method: 'GET',
  })
}

/** GET /api/platform/v1/capabilities/mcps/{id}/relations —— MCP Server 的关系 */
export function getConsoleMcpRelations(id: string): Promise<McpRelations> {
  return request<McpRelations>(`/platform/v1/capabilities/mcps/${id}/relations`, {
    method: 'GET',
  })
}

/** GET /api/platform/v1/capabilities/tools/{id}/relations —— Tool 的关系 */
export function getConsoleToolRelations(id: string): Promise<ToolRelations> {
  return request<ToolRelations>(`/platform/v1/capabilities/tools/${id}/relations`, {
    method: 'GET',
  })
}

/** GET /api/platform/v1/capabilities/agents —— 跨租户 Agent 注册表（只读盘点） */
export function getConsoleAgentRegistry(params?: {
  tenantId?: string
}): Promise<PlatformAgentRegistryItem[]> {
  const qs = new URLSearchParams()
  if (params?.tenantId) qs.set('tenantId', params.tenantId)
  const q = qs.toString()
  return request<PlatformAgentRegistryItem[]>(
    `/platform/v1/capabilities/agents${q ? `?${q}` : ''}`,
    { method: 'GET' },
  )
}

/** GET /api/platform/v1/capabilities/pointer —— 平台发布指针（CAS 基线来源） */
export function getConsoleCapabilityPointer(): Promise<{
  currentReleaseId: number | null
  currentPublishSeq: number | null
  updatedAt: string | null
}> {
  return request('/platform/v1/capabilities/pointer', { method: 'GET' })
}

/* ══ 平台身份域（重规划 §7 / §9 · /api/platform/v1/*）══
   ⚠️ 本段全部端点仅接受 aud=admin-console-api 的平台令牌；
      与上方租户链端点（aud=agent-platform）令牌层互拒。
      admin-console 已转型为纯平台控制台，登录走 platformLogin。 */

import type {
  PlatformLoginResponse,
  PlatformMe,
  PlatformRoleSummary,
  PlatformTenant,
  PlatformQuota,
  PlatformCapability,
  PlatformAuditEvent,
} from '@/api/types'

/** POST /api/platform/v1/auth/login —— 平台登录（公开端点；失败统一 401 防枚举） */
export function platformLogin(payload: {
  username: string
  password: string
}): Promise<PlatformLoginResponse> {
  return request<PlatformLoginResponse>('/platform/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify(payload),
  }, false)
}

/** GET /api/platform/v1/auth/me —— 当前平台主体（角色回补用） */
export function getPlatformMe(): Promise<PlatformMe> {
  return request<PlatformMe>('/platform/v1/auth/me', { method: 'GET' })
}

/** GET /api/platform/v1/users —— 平台用户列表（详情口径，含 roles；需 platform.user.read） */
export function getPlatformUsers(): Promise<PlatformUserDetail[]> {
  return request<PlatformUserDetail[]>('/platform/v1/users', { method: 'GET' })
}

/** GET /api/platform/v1/users/{id} —— 单个平台用户详情 */
export function getPlatformUser(id: string): Promise<PlatformUserDetail> {
  return request<PlatformUserDetail>(`/platform/v1/users/${encodeURIComponent(id)}`, {
    method: 'GET',
  })
}

/** POST /api/platform/v1/users —— 创建平台账号（LOCAL 认证源；新建即启用） */
export function createPlatformUser(payload: CreatePlatformUserRequest): Promise<PlatformUserDetail> {
  return request<PlatformUserDetail>('/platform/v1/users', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** PUT /api/platform/v1/users/{id} —— 更新资料/状态（profile 更新会调审核动作） */
export function updatePlatformUser(
  id: string,
  payload: UpdatePlatformUserRequest,
): Promise<PlatformUserDetail> {
  return request<PlatformUserDetail>(`/platform/v1/users/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

/** PUT /api/platform/v1/users/{id}/password —— 重置密码（≥10 位；自身保护在服务层） */
export function resetPlatformUserPassword(id: string, newPassword: string): Promise<void> {
  return request<void>(`/platform/v1/users/${encodeURIComponent(id)}/password`, {
    method: 'PUT',
    body: JSON.stringify({ newPassword }),
  })
}

/** POST /api/platform/v1/users/{id}/roles —— 赋予角色（SUPER_ADMIN 仅超管可授） */
export function assignPlatformUserRole(id: string, role: string): Promise<UserRolesResponse> {
  return request<UserRolesResponse>(`/platform/v1/users/${encodeURIComponent(id)}/roles`, {
    method: 'POST',
    body: JSON.stringify({ role }),
  })
}

/** DELETE /api/platform/v1/users/{id}/roles/{role} —— 撤销角色（防最后一个超管自撤） */
export function revokePlatformUserRole(id: string, role: string): Promise<UserRolesResponse> {
  return request<UserRolesResponse>(
    `/platform/v1/users/${encodeURIComponent(id)}/roles/${encodeURIComponent(role)}`,
    { method: 'DELETE' },
  )
}

/** GET /api/platform/v1/roles —— 平台角色目录（需 platform.role.read） */
export function getPlatformRoles(): Promise<PlatformRoleSummary[]> {
  return request<PlatformRoleSummary[]>('/platform/v1/roles', { method: 'GET' })
}

/** GET /api/platform/v1/tenants —— 租户列表（简单分页，非游标） */
export function getPlatformTenants(params?: {
  status?: string
  limit?: number
}): Promise<PlatformTenant[]> {
  const qs = new URLSearchParams()
  if (params?.status) qs.set('status', params.status)
  if (params?.limit) qs.set('limit', String(params.limit))
  const q = qs.toString()
  return request<PlatformTenant[]>(`/platform/v1/tenants${q ? `?${q}` : ''}`, { method: 'GET' })
}

/** POST /api/platform/v1/tenants —— 开通租户（进入 PENDING_SETUP，不自动建租户管理员） */
export function createPlatformTenant(payload: {
  slug: string
  name: string
  plan?: string
}): Promise<PlatformTenant> {
  return request<PlatformTenant>('/platform/v1/tenants', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** GET /api/platform/v1/tenants/{id} —— 租户详情 */
export function getPlatformTenant(id: string): Promise<PlatformTenant> {
  return request<PlatformTenant>(`/platform/v1/tenants/${id}`, { method: 'GET' })
}

/** POST /api/platform/v1/tenants/{id}/suspend —— 暂停（非法迁移 → 409） */
export function suspendPlatformTenant(id: string): Promise<PlatformTenant> {
  return request<PlatformTenant>(`/platform/v1/tenants/${id}/suspend`, { method: 'POST' })
}

/** POST /api/platform/v1/tenants/{id}/resume —— 恢复 */
export function resumePlatformTenant(id: string): Promise<PlatformTenant> {
  return request<PlatformTenant>(`/platform/v1/tenants/${id}/resume`, { method: 'POST' })
}

/** POST /api/platform/v1/tenants/{id}/decommission —— 退役（进入 DECOMMISSIONING） */
export function decommissionPlatformTenant(id: string): Promise<PlatformTenant> {
  return request<PlatformTenant>(`/platform/v1/tenants/${id}/decommission`, { method: 'POST' })
}

/** GET /api/platform/v1/tenants/{id}/quota —— 读取配额 */
export function getPlatformQuota(id: string): Promise<PlatformQuota> {
  return request<PlatformQuota>(`/platform/v1/tenants/${id}/quota`, { method: 'GET' })
}

/** PUT /api/platform/v1/tenants/{id}/quota —— 更新配额 */
export function updatePlatformQuota(
  id: string,
  payload: PlatformQuota,
): Promise<PlatformQuota> {
  return request<PlatformQuota>(`/platform/v1/tenants/${id}/quota`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

/** GET /api/platform/v1/tenants/{id}/capabilities —— 列出租户能力授权 */
export function getPlatformCapabilities(id: string): Promise<PlatformCapability[]> {
  return request<PlatformCapability[]>(`/platform/v1/tenants/${id}/capabilities`, {
    method: 'GET',
  })
}

/** POST /api/platform/v1/tenants/{id}/capabilities —— 授予能力 */
export function grantPlatformCapability(
  id: string,
  payload: { resourceCode: string; scope: string },
): Promise<PlatformCapability> {
  return request<PlatformCapability>(`/platform/v1/tenants/${id}/capabilities`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** DELETE /api/platform/v1/tenants/{id}/capabilities/{code} —— 撤销能力 */
export function revokePlatformCapability(id: string, code: string): Promise<void> {
  return request<void>(`/platform/v1/tenants/${id}/capabilities/${encodeURIComponent(code)}`, {
    method: 'DELETE',
  })
}

/** GET /api/platform/v1/audit-events —— 平台审计（需 platform.audit.read；与租户审计分域） */
export function getPlatformAuditEvents(params?: {
  actorId?: string
  action?: string
  targetType?: string
  targetId?: string
  from?: string
  to?: string
  limit?: number
}): Promise<PlatformAuditEvent[]> {
  const qs = new URLSearchParams()
  if (params?.actorId) qs.set('actorId', params.actorId)
  if (params?.action) qs.set('action', params.action)
  if (params?.targetType) qs.set('targetType', params.targetType)
  if (params?.targetId) qs.set('targetId', params.targetId)
  if (params?.from) qs.set('from', params.from)
  if (params?.to) qs.set('to', params.to)
  if (params?.limit) qs.set('limit', String(params.limit))
  const q = qs.toString()
  return request<PlatformAuditEvent[]>(`/platform/v1/audit-events${q ? `?${q}` : ''}`, {
    method: 'GET',
  })
}
