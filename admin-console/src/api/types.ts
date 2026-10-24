/** 后端契约类型（复用现有 agent-api 鉴权端点） */

/** POST /api/auth/token 请求体 */
export interface LoginRequest {
  tenantId: string
  loginName: string
  password: string
}

/** POST /api/auth/token 响应 */
export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  /** 有效期（秒），后端硬编码 900 */
  expiresIn: number
  tenantId?: string | null
  principalId?: string | null
  /** 可选：旧后端无此字段，缺失时前端用 GET /api/auth/roles 回补 */
  roles?: string[]
}

/** GET /api/auth/roles 响应（G1：登录返角色） */
export interface RolesResponse {
  roles: string[]
}

/* ── 管理后台聚合类型（逐步对接，P0 仅 dashboard 真实调用） ── */

export interface DashboardSummary {
  tenantCount: number
  agentCount: number
  toolCount: number
  skillCount: number
  mcpCount: number
  pendingApprovals: number
  activeAlerts: number
}

export interface Page<T> {
  content: T[]
  total: number
  page: number
  size: number
}

/** 游标分页（后端 GovernanceSupport.PageResponse） */
export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
}

export interface TenantItem {
  id: string
  name: string
  status: 'active' | 'suspended' | 'decommissioned'
  createdAt: string
}

/** 后端 TenantResponse（tenant 表全局视图） */
export interface TenantResponse {
  tenantId: string
  slug: string
  name: string
  status: 'ACTIVE' | 'SUSPENDED' | 'DECOMMISSIONED'
  createdAt: string | null
  updatedAt: string | null
}

/** 后端 PrincipalResponse（不含任何凭据） */
export interface PrincipalResponse {
  principalId: string
  loginName: string
  displayName: string | null
  status: string
  authzVersion: number
  roles: string[]
}

/** 后端 SelfSummary / TenantDetailResponse 中的计数部分 */
export interface TenantSummary {
  tenant: TenantResponse
  principalsCount: number
  agentsCount: number
  overridesCount: number
}

export interface TenantDetailResponse extends TenantSummary {
  principals: PrincipalResponse[]
}

export interface RlsVerifyResponse {
  contextBound: boolean
  isolated: boolean
  leakedRows: number
  boundTenantId: string | null
  ok: boolean
}

/** 配置覆盖项（复用 GET /api/config/overrides） */
export interface ConfigOverrideItem {
  key: string
  declaredValue: string | null
  effectiveValue: string | null
  source: string | null
  tightening: string
  reason: string | null
  changedBy: string | null
  updatedAt: string | null
}

/* ── P1 实体管理（复用既有 /api/agents 与 /api/cap/catalog） ── */

/** Agent 实体（GET /api/agents · AgentItemResponse，租户级） */
export interface AgentItem {
  agentId: string
  name: string
  description: string | null
  versionCount: number
  latestVersionNumber: number
  latestDefinition: string | null
  latestPublishedAt: string | null
}

/** Agent 版本快照（GET /api/agents/{id}/versions · AgentVersionItemResponse，不可变） */
export interface AgentVersion {
  versionId: string
  versionNumber: number
  definition: string
  publishedAt: string | null
  createdAt: string
}

export interface CreateAgentRequest {
  name: string
  description?: string | null
}

export interface PublishVersionRequest {
  definition: string
}

/** Tool 目录项（GET /api/cap/catalog/tools · ToolResponse，平台级只读） */
export interface ToolCatalogItem {
  toolId: string
  name: string | null
  kind: string
  riskLevel: string
  mcpServerId: string | null
  mcpToolName: string | null
  inputSchema: string
  paramPolicies: string
  version: number
  schemaVersion: number
  published: boolean
  publishedAt: string | null
}

/**
 * Skill 的 MCP 声明（§4.4）：
 * ⚠️ toolAllowlist 可空且空数组不等价于 null —— null=未配置（取 ServerAllowedOps 全集），[]=显式空。必须如实呈现。
 */
export interface McpDeclaration {
  serverId: string
  toolAllowlist: string[] | null
}

/** Skill 目录项（GET /api/cap/catalog/skills · SkillResponse，平台级只读） */
export interface SkillCatalogItem {
  skillId: string
  name: string | null
  description: string | null
  declaredTools: string[]
  declaredMcp: McpDeclaration[]
  riskCeiling: string
  paramPolicies: string
  requiredCaps: string[]
  version: number
  schemaVersion: number
  published: boolean
  publishedAt: string | null
}

/** MCP Server 目录项（GET /api/cap/catalog/mcps · McpResponse；⚠️ 不含 credentialRef，凭据永不入响应） */
export interface McpCatalogItem {
  serverId: string
  version: number
  transport: string
  endpoint: string | null
  authKind: string
  trustTier: string
  exposedToolAllowlist: string[] | null
  connectionRisk: string
  dynamicDiscovery: boolean
  configFingerprint: string
  schemaVersion: number
  createdAt: string | null
  publishedAt: string | null
}

/* ── P2 发布审批（复用既有 /api/cap/config/publishes 三步工作流） ──
   ⚠️ 三步不可合并：申请 → 审批 → 执行；「我批了」与「配置真的变了」必须可区分。
   ⚠️ 双人复核靠身份比对（approverId <> requesterId），不靠权限拆分。 */

/** 变更申请（RequestResponse） */
export interface PublishRequestItem {
  requestId: string
  operation: 'PUBLISH' | 'ROLLBACK' | 'LIFECYCLE_TRANSITION'
  state: 'PENDING' | 'APPROVED' | 'REJECTED' | 'APPLIED' | 'FAILED'
  requesterId: string
  approverId: string | null
  decisionNote: string | null
  requestedAt: string
  decidedAt: string | null
  appliedAt: string | null
  failureReason: string | null
}

/** 发布历史条目（ReleaseResponse；⚠️ releaseId/seq 为字符串，避免 JS number 精度落在审计字段上） */
export interface PublishRelease {
  releaseId: string
  configPublishSeq: string
  scope: 'TENANT' | 'PLATFORM'
  tenantId: string | null
  objectVersions: string
  aggregateFingerprint: string | null
  status: 'PREPARING' | 'PUBLISHED' | 'ROLLED_BACK'
  requesterId: string | null
  approverId: string | null
  effectiveAt: string | null
  createdAt: string
}

/** 当前发布指针（PointerResponse；currentPublishSeq 即申请时应携带的 expectedPublishSeq） */
export interface PublishPointer {
  currentReleaseId: number | null
  currentPublishSeq: number | null
  updatedAt: string | null
}

/** Agent 定义草稿（AgentRequest；版本号与内容指纹由服务端决定，客户端不采信） */
export interface PublishAgentSpec {
  agentId: string
  name: string
  description?: string | null
  roles?: string[]
  enabledSkills?: string[]
  skillBundles?: string[]
  capabilityTags?: string[]
  overrides?: string
}

/** 生命周期迁移参数（LifecycleRequest） */
export interface PublishLifecycleSpec {
  type: string
  objectId: string
  version: number
  expectedFrom: string
  to: string
}

/** 申请体（PublishRequest；scope 缺省 TENANT —— 本端点只服务租户级对象） */
export interface SubmitPublishRequest {
  operation: 'PUBLISH' | 'ROLLBACK' | 'LIFECYCLE_TRANSITION'
  scope?: 'TENANT' | 'PLATFORM'
  expectedPublishSeq?: number | null
  agents?: PublishAgentSpec[]
  targetReleaseId?: number | null
  lifecycle?: PublishLifecycleSpec
  reason: string
}

/** 审批决定（DecisionRequest） */
export interface PublishDecision {
  decision: 'APPROVE' | 'REJECT'
  note?: string
}

/* ── P3 关系视图（GET /api/admin/dependencies/*） ── */

/** 图谱节点类型（NodeType） */
export type GraphNodeType = 'TENANT' | 'AGENT' | 'TOOL' | 'SKILL' | 'MCP_SERVER'

/** 图谱边类型（EdgeKind）—— direction: from 引用 to */
export type GraphEdgeKind =
  | 'TENANT_OF'
  | 'ENABLED_SKILL'
  | 'DECLARED_TOOL'
  | 'DECLARED_MCP'
  | 'MCP_BINDING'
  | 'OVERRIDE_REMOVED_SKILL'
  | 'OVERRIDE_REMOVED_TOOL'

/** 图谱节点（Node） */
export interface GraphNode {
  /** 全局节点 id（agent:<id> / tool:<id> / skill:<id> / mcp_server:<id> / tenant:<id>） */
  id: string
  type: GraphNodeType
  /** 业务 id（agent_id / tool id / skill id / server id / tenant_id） */
  refId: string
  label: string
  detail: string | null
  /** false = 悬空引用（被引用但无定义） */
  exists: boolean
  published: boolean
  version: number
}

/** 图谱边（Edge）—— from 引用 to */
export interface GraphEdge {
  from: string
  to: string
  kind: GraphEdgeKind
}

/** 依赖图谱响应（GET /api/admin/dependencies/graph） */
export interface DependencyGraphResponse {
  tenantId: string
  nodes: GraphNode[]
  edges: GraphEdge[]
  /** true = 超限截断（展示降级，引导按实体走影响分析） */
  truncated: boolean
}

/** 影响分析目标类型（DependencyEntityType；查询兼容 "mcp" 别名） */
export type ImpactEntityType = 'AGENT' | 'TOOL' | 'SKILL' | 'MCP_SERVER'

/** 一条引用（方向由所在列表体现） */
export interface ImpactRef {
  type: string
  id: string
  label: string
  kind: GraphEdgeKind
}

/** 传递影响链的一步 */
export interface ImpactChainStep {
  type: string
  id: string
  label: string
}

/** 传递影响链：目标 → … → 末端 Agent */
export interface ImpactChain {
  path: ImpactChainStep[]
  agentId: string
  agentLabel: string
}

/** 悬空引用告警（目标引用了不存在的对象） */
export interface ImpactDanglingRef {
  type: string
  id: string
  via: GraphEdgeKind
}

/** 影响分析响应（GET /api/admin/dependencies/impact） */
export interface ImpactReport {
  entityType: ImpactEntityType
  entityId: string
  entityLabel: string
  entityExists: boolean
  /** 出向：目标引用了谁 */
  dependencies: ImpactRef[]
  /** 入向：谁直接引用了目标（TENANT_OF 所有权边不计） */
  dependents: ImpactRef[]
  /** 传递链：沿入向回溯到受影响 Agent */
  chains: ImpactChain[]
  /** 去重的受影响 Agent（仅本租户） */
  affectedAgentIds: string[]
  /** 目标自身的悬空引用 */
  danglingRefs: ImpactDanglingRef[]
}

/* ── P4 运行监控（只读） ── */

/** 拒绝事件严重级别（后端 DenySeverity 口径） */
export type DenySeverity = 'CRITICAL' | 'WARNING' | 'INFO'

/** 监控健康状态（后端 MonitoringHealth.Status 口径） */
export type MonitoringStatus = 'HEALTHY' | 'DEGRADED' | 'UNAVAILABLE'

/** 授权版本水位（后端 AuthzWatermark 口径） */
export interface AuthzWatermark {
  tenantId: string
  /** 持有授权版本记录的主体数 */
  subjectCount: number
  /** 被撤权过（authzVersion > 1）的主体数 */
  revokedSubjects: number
  /** 租户内最大授权版本号 */
  maxAuthzVersion: number
  /** 最近一次版本递增时刻；从未撤权时为 null */
  lastBumpAt: string | null
  /** 快照观测时刻 */
  observedAt: string
}

/** 一条近期拒绝告警（后端 DenyAlert 口径） */
export interface DenyAlert {
  severity: DenySeverity
  reasonCode: string
  principalId: string | null
  toolId: string | null
  runId: string | null
  invocationId: string
  occurredAt: string
}

/** 决策日志审计覆盖率（后端 DecisionAuditCoverage 口径） */
export interface DecisionAuditCoverage {
  total: number
  invocationCount: number
  denyCount: number
  nonSpecificDenyCount: number
  incompleteCount: number
}

/** 运行健康快照（GET /api/admin/monitoring/health） */
export interface MonitoringHealth {
  status: MonitoringStatus
  authzWatermark: AuthzWatermark
  recentDenies: DenyAlert[]
  criticalCount: number
  warningCount: number
  infoCount: number
  coverage: DecisionAuditCoverage
  observedAt: string
}
