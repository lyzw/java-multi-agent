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
  /**
   * 远程 HTTP 请求方法（仅 kind=HTTP 时非空）。
   *
   * ⚠️ 老版本后端**不回显**该字段，故声明为可选：
   * 缺失时按「未提供」呈现，**不得**由前端臆造。
   */
  httpMethod?: string | null
  /** 远程 HTTP 请求目标 URL（仅 kind=HTTP 时非空）；老版本后端不回显。 */
  httpUrl?: string | null
  /** 远程 HTTP 请求超时（毫秒；仅 kind=HTTP 时非空）；老版本后端不回显。 */
  httpTimeoutMs?: number | null
  inputSchema: string
  paramPolicies: string
  version: number
  schemaVersion: number
  published: boolean
  publishedAt: string | null
  /**
   * ⚠️ 生命周期状态（PUBLISHED / DEPRECATED / …；读失败时为 null）。
   *
   * <p>⚠️ **不能硬编码 `expectedFrom=PUBLISHED`**：对象可能**早已停用**，
   * 再提交一次迁移只会得到一个「原状态不符」的报错 —— 那读起来像
   * 「有人跟我抢」，而真实原因是「它早就停了」。故前端必须**如实读取**。
   */
  lifecycleState: string | null
  /**
   * 配置指纹（cap_tool_def.config_fingerprint）—— 该版本定义内容的指纹。
   *
   * <p>⚠️ 老版本后端**不回显**该字段，故声明为可选：
   * 缺失时按「未提供」呈现，**不得**由前端臆造。
   */
  configFingerprint?: string | null
  /** 版本行创建时间（cap_tool_def.created_at）；老版本后端不回显。 */
  createdAt?: string | null
}

/**
 * Skill 的 MCP 声明（§4.4）：
 * ⚠️ toolAllowlist 可空且空数组不等价于 null —— null=未配置（取 ServerAllowedOps 全集），[]=显式空。必须如实呈现。
 */
export interface McpDeclaration {
  serverId: string
  toolAllowlist: string[] | null
}

/**
 * Skill 两级描述的一条分场景操作指引（2级）。
 *
 * 1级 purpose 是「选型信号」（路由/挑工具首先看到），
 * 2级 usage 是「被命中后注入上下文的操作指引」（when = 什么情况，how = 用何种操作）。
 * ⚠️ 条目间应互斥或有明确优先级，禁止「其他情况都用这条」式兜底条目。
 */
export interface SkillUsageItem {
  /** 触发条件（≤200 字，可被模型从对话上下文直接判断）。 */
  when: string
  /** 操作步骤（≤1000 字，仅允许引导已声明能力；外部写入须写明确认与回报）。 */
  how: string
}

/**
 * Skill 两级描述（契约 $defs.skillDescription）。
 *
 * ⚠️ **描述不是授权**：两级内容只引导 `declaredTools` / `declaredMcp`
 * 已声明的能力，授权真相只有声明列表与 `riskCeiling`；
 * 描述与声明冲突时按声明执行（fail-closed）。
 */
export interface SkillDescription {
  /** 1级 · 用途简述（≤200 字，一句话说清做什么、适用什么请求）。 */
  purpose: string
  /** 2级 · 分场景操作指引（≤20 条，可空）。 */
  usage?: SkillUsageItem[]
}

/** Skill 目录项（GET /api/cap/catalog/skills · SkillResponse，平台级只读） */
export interface SkillCatalogItem {
  skillId: string
  name: string | null
  /** 两级描述（1级 purpose + 2级 usage）；存量行可能为 null。 */
  description: SkillDescription | null
  declaredTools: string[]
  declaredMcp: McpDeclaration[]
  riskCeiling: string
  paramPolicies: string
  requiredCaps: string[]
  version: number
  schemaVersion: number
  published: boolean
  publishedAt: string | null
  /** ⚠️ 生命周期状态（PUBLISHED / DEPRECATED / …；读失败时为 null）—— 不硬编码 CAS 原状态 */
  lifecycleState: string | null
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
  /** 发布状态（= publishedAt 非空）；⚠️ 与 lifecycleState 是**两件事**（停用是单向终态） */
  published: boolean
  /** ⚠️ 仅布尔标识「是否登记了凭据引用」；定位符与凭据材料永不入响应（P5-T3） */
  hasCredentialRef: boolean
  /** ⚠️ 生命周期状态（PUBLISHED / DEPRECATED / …；读失败时为 null）—— 不硬编码 CAS 原状态 */
  lifecycleState: string | null
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
  /**
   * 编排声明（多级 Agent v1.1 · 第二十九批 · C29-1 · A6）。
   *
   * ⚠️ 缺省 = `{}` = **LEAF**（MA-T01）：不是「不限制」，而是「不允许委派」。
   * ⚠️ 形态是 **JSON 文本**（与后端 `AgentRequest.orchestration` 一致），
   * 由管理台的编排声明表单生成。
   */
  orchestration?: string
}

/**
 * 输出契约草稿（ResultContractRequest · 多级 Agent v1.1 · 第二十九批 · C29-1 · A6）。
 *
 * ⚠️ **不含版本号与内容指纹** —— 二者由服务端决定。
 * 客户端若能指定版本号或内容指纹，就等于能指定「内容没变」这个证明。
 */
export interface PublishResultContractSpec {
  /** 契约标识（可读字符串，如 `answer-contract`，**不是 UUID**） */
  contractId: string
  /** 契约定义本身（JSON Schema 文本）—— 不是契约实例 */
  schemaJson: string
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
  /** 输出契约草稿（多级 Agent v1.1 · 第二十九批 · C29-1 · A6） */
  resultContracts?: PublishResultContractSpec[]
  /**
   * 路由策略草稿（第十四批）。
   *
   * ⚠️ 空列表 = 本次发布**不登记策略**（≠「策略随意」）——
   * 没有策略可登记时，ROUTER Agent 的 `routingPolicyRef` 只能指向**此前已发布**的策略。
   */
  routingPolicies?: PublishRoutingPolicySpec[]
  targetReleaseId?: number | null
  lifecycle?: PublishLifecycleSpec
  reason: string
}

/** 审批决定（DecisionRequest） */
export interface PublishDecision {
  decision: 'APPROVE' | 'REJECT'
  note?: string
}

/**
 * 路由策略草稿（RoutingPolicyRequest · 第十四批）。
 *
 * ⚠️ 与 Agent 草稿、输出契约草稿同口径：**不含版本号与内容指纹** —— 二者由服务端决定。
 */
export interface PublishRoutingPolicySpec {
  /** 策略标识（可读字符串） */
  policyId: string
  /** 选择策略 */
  strategy: 'EXPLICIT_TARGETS' | 'ORDERED_FALLBACK'
  /** 允许的目标 Agent 标识（有序） */
  targets?: string[]
}

/* ── C34-3：平台级能力对象写通道（第三十四批 · C34-2 后端契约） ── */

/**
 * 风险等级取值域（§5.6）。
 *
 * ⚠️ **没有「未知」这一档** —— 判不出风险就等于「无法判定」，
 * 不允许把它当成「无限制」（后端 `ConfigValidationGate` 会以 RISK_LEVEL_UNKNOWN 拒）。
 */
export type CapabilityRiskLevel = 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'

/**
 * Tool 类型：NATIVE = 内置（不绑定任何 MCP Server）；MCP = 绑定到某个 Server；
 * HTTP = 远程 HTTP 请求（直接打一个配置里的 URL）。
 *
 * ⚠️ **HTTP 与 MCP 的关键差别**（第三十七批）：
 * MCP 工具的目标由 MCP Server 的**固定清单**约束（远端操作名必须在清单内），
 * 而 HTTP 工具的目标**就是配置里的那个 URL** ——
 * 它绕开了固定清单，因此**自带 SSRF 防线是它的固有义务**（设计文档 §13.1）。
 */
export type PlatformToolKind = 'NATIVE' | 'MCP' | 'HTTP'

/** MCP 传输方式（V1 只支持 HTTP / SSE，后端 `isSupportedInV1` 之外一律拒）。 */
export type PlatformMcpTransport = 'HTTP' | 'SSE'

/** MCP 认证方式。 */
export type PlatformMcpAuthKind = 'TOKEN' | 'OAUTH' | 'NONE'

/** MCP 信任分级。 */
export type PlatformMcpTrustTier = 'INTERNAL' | 'PARTNER' | 'PUBLIC'

/**
 * 平台级 Tool 定义草稿（PlatformToolSpec）。
 *
 * ⚠️ **kind 与 MCP 绑定字段是互斥且成对的两组事实**：
 * `kind=MCP` 时 `mcpServerId` + `mcpToolName` **必须都给**，
 * `kind=NATIVE` 时**两者都不能给** —— 后端 400（DB 层也有 CHECK 兜底）。
 */
export interface PlatformToolSpec {
  toolId: string
  name?: string
  kind: PlatformToolKind
  riskLevel: CapabilityRiskLevel
  /** ⚠️ 仅 kind=MCP 时可给 */
  mcpServerId?: string
  /** ⚠️ 仅 kind=MCP 时可给 */
  mcpToolName?: string
  /** ⚠️ 仅 kind=HTTP 时可给：请求方法（GET/POST/PUT/PATCH/DELETE/HEAD） */
  httpMethod?: string
  /** ⚠️ 仅 kind=HTTP 时可给：目标 URL（绝对地址，http/https） */
  httpUrl?: string
  /** ⚠️ 仅 kind=HTTP 时可给：单次请求超时（毫秒，正整数，上限 300000） */
  httpTimeoutMs?: number
  inputSchema?: string
  paramPolicies?: string
}

/**
 * Skill 对 MCP Server 的声明草稿（PlatformMcpDeclarationSpec）。
 *
 * ⚠️ **`toolAllowlist` 的 `null` 与 `[]` 语义不同**：
 * `null` = 未配置（取该 Server 允许集**全集**），
 * `[]` = 显式**不允许任何操作**。把 `[]` 归一成 `null` 等于权限放宽。
 */
export interface PlatformMcpDeclarationSpec {
  serverId: string
  toolAllowlist?: string[] | null
}

/** 平台级 Skill 定义草稿（PlatformSkillSpec）。 */
export interface PlatformSkillSpec {
  skillId: string
  name?: string
  /** 两级描述（1级 purpose + 2级 usage）——改描述 = 内容变更（重算指纹）。 */
  description?: SkillDescription
  declaredTools?: string[]
  declaredMcp?: PlatformMcpDeclarationSpec[]
  riskCeiling: CapabilityRiskLevel
  paramPolicies?: string
  requiredCaps?: string[]
}

/**
 * 平台级 MCP Server 定义草稿（PlatformMcpServerSpec）。
 *
 * ⚠️ **`exposedToolAllowlist` 同样 `null` ≠ `[]`**（三态：未配置 / 显式空 / 指定清单）。
 * ⚠️ `credentialRef` 是**凭据库定位符**（不是凭据材料）；目录接口**从不回显**它，
 * 只给 `hasCredentialRef` 布尔 —— 所以「改一个别的字段」也可能把凭据引用丢掉。
 */
export interface PlatformMcpServerSpec {
  serverId: string
  transport: PlatformMcpTransport
  endpoint?: string
  command?: string
  authKind: PlatformMcpAuthKind
  trustTier: PlatformMcpTrustTier
  exposedToolAllowlist?: string[] | null
  connectionRisk: CapabilityRiskLevel
  credentialRef?: string
}

/** 平台级发布申请体（POST /api/cap/platform/publish-requests）。 */
export interface PlatformPublishRequest {
  tools?: PlatformToolSpec[]
  skills?: PlatformSkillSpec[]
  mcpServers?: PlatformMcpServerSpec[]
  /** CAS 基线（客户端读到的当前发布序号）；缺省 = 不做基线校验 */
  expectedPublishSeq?: number
  /** ⚠️ 必填，空串 → 后端 400 */
  reason: string
}

/** 平台级生命周期迁移申请体（POST /api/cap/platform/lifecycle-requests）。 */
export interface PlatformLifecycleRequest {
  /** ⚠️ 平台通道只管理平台级对象：tool / skill / mcp_server */
  type: 'tool' | 'skill' | 'mcp_server'
  objectId: string
  /** ⚠️ 必须是对象**当前版本号**（详情页里有） */
  version: number
  /** CAS 前置条件：停用通道固定 PUBLISHED */
  expectedFrom: string
  /** 目标状态（停用 = DEPRECATED，单向终态） */
  to: string
  reason: string
}

/** 平台级变更申请视图（RequestResponse）。 */
export interface PlatformRequestItem {
  requestId: string
  scope: string
  operation: string
  state: 'PENDING' | 'APPROVED' | 'REJECTED' | 'APPLIED' | 'FAILED'
  requesterId: string
  approverId?: string
  decisionNote?: string
  failureReason?: string
  requestedAt: string
  decidedAt?: string
  appliedAt?: string
}

/** 平台级审批决定体（PlatformDecisionRequest）。 */
export interface PlatformDecision {
  decision: 'APPROVE' | 'REJECT'
  note?: string
}

/* ── C34-3：能力目录关系视图 ── */

/** Tool 紧凑摘要（关系视图用）。 */
export interface ToolSummary {
  toolId: string
  name?: string
  kind: string
  riskLevel: string
  version: number
  published: boolean
}

/** MCP Server 紧凑摘要（关系视图用）。 */
export interface McpSummary {
  serverId: string
  transport: string
  trustTier: string
  connectionRisk: string
  version: number
  published: boolean
}

/** Tool 的关系视图（GET /api/cap/catalog/tools/{toolId}/relations）。 */
export interface ToolRelations {
  toolId: string
  /** ⚠️ null = 内置 Tool（合法状态，不是「查不到」） */
  mcpServer: McpSummary | null
}

/** Skill 的关系视图（GET /api/cap/catalog/skills/{skillId}/relations）。 */
export interface SkillRelations {
  skillId: string
  tools: ToolSummary[]
  /** ⚠️ 「声明了但对象不存在」的 Tool id —— 必须如实呈现 */
  missingTools: string[]
  mcpServers: McpSummary[]
  /** ⚠️ 「声明了但对象不存在」的 MCP Server id —— 必须如实呈现 */
  missingMcpServers: string[]
}

/** MCP Server 的关系视图（GET /api/cap/catalog/mcps/{serverId}/relations）。 */
export interface McpRelations {
  serverId: string
  /** ⚠️ 允许集（null≠[]），与 `tools`（实际登记）是两回事 */
  exposedToolAllowlist: string[] | null
  /** 实际登记在该 Server 上的 Tool 定义 */
  tools: ToolSummary[]
}

/* ── C34-3：多级 Agent 的权威定义（cap_agent_def） ── */

/**
 * Agent 的权威定义（GET /api/cap/agents/{agentId}/definition）。
 *
 * ⚠️ `overrides` 与 `orchestration` 都是 **JSON 原文字符串**（不是对象）——
 * 解析失败必须**显式报错**，不能静默当空。
 */
export interface AgentDefinition {
  agentId: string
  version: number
  name?: string
  description?: string
  roles: string[]
  enabledSkills: string[]
  skillBundles: string[]
  capabilityTags: string[]
  /** 收紧覆盖（JSON 原文）—— ⚠️ **不是**整份 definition */
  overrides: string
  /** 编排声明（JSON 原文） */
  orchestration: string
  published: boolean
}

/* ── C34-3：委派拓扑（cap_agent_child_ref 投影） ── */

/**
 * 一条委派边（DelegationEdgeResponse）。
 *
 * ⚠️ 这是**发布关系投影**，不是权威定义 —— 边的唯一来源是发布期投影。
 */
export interface DelegationEdge {
  parentAgentId: string
  parentReleaseVersion: number
  childAgentId: string
  childReleaseVersion: number
  /** 父的声明次序（⚠️ 框架**不**按此顺序代为执行） */
  ordinal: number
  required: boolean
  createdAt: string
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

/* ── P5 审计与配置 ── */

/**
 * 审计条目（GET /api/audit · AuditEntryResponse）。
 *
 * ⚠️ 后端不返回 tenantId（RLS 已按调用方租户过滤）；audit_record 有
 * forbid_audit_mutation 触发器，本类型仅供展示，无任何写回路径。
 */
export interface AuditLogEntry {
  auditId: string
  actorId: string
  action: string
  resourceType: string
  resourceId: string | null
  createdAt: string
  correlationId: string | null
  /** 事件细节（后端 Map<String,Object>，键集随 action 变化，原样呈现） */
  detail: Record<string, unknown>
}

/* ── 平台身份域（重规划 §7 / §9 · /api/platform/v1/*） ── */

/* ── 平台用户/角色/权限管理写端点（第三十九批 · /api/platform/v1/users/*） ── */

/** 平台用户（详情口径；含邮箱与审计元信息与角色集合；绝不返回 password_hash） */
export interface PlatformUserDetail {
  id: string
  username: string
  displayName: string | null
  email: string | null
  status: string
  authSource: string
  mfaRequired: boolean
  authzVersion: number
  lastLoginAt: string | null
  createdAt: string | null
  updatedAt: string | null
  roles: string[]
}

/** 创建平台账号请求体（LOCAL 认证源） */
export interface CreatePlatformUserRequest {
  username: string
  password: string
  displayName?: string
  email?: string
  mfaRequired?: boolean
  roles?: string[]
}

/** 更新平台用户请求体（null 语义 = 不变，由前端展开时省略） */
export interface UpdatePlatformUserRequest {
  displayName?: string
  email?: string
  status?: 'ACTIVE' | 'DISABLED'
  mfaRequired?: boolean
}

export interface UserRolesResponse {
  roles: string[]
}

/* ── 平台控制台 · 能力目录（平台链 /api/platform/v1/capabilities/*） ── */

/**
 * 跨租户 Agent 注册表条目（平台控制台只读盘点）。
 *
 * ⚠️ 只有管理元数据：definition 正文、运行输出、会话内容永不出现（禁区红线）。
 */
export interface PlatformAgentRegistryItem {
  tenantId: string
  agentId: string
  name: string | null
  description: string | null
  versionCount: number
  latestVersionNumber: number | null
  latestPublishedAt: string | null
  /** 权威定义（cap_agent_def）版本；无定义时为 null */
  defVersion: number | null
  /** 生命周期状态（PUBLISHED / DEPRECATED / …）；无定义时为 null */
  lifecycleState: string | null
  roles: string[]
  enabledSkills: string[]
  capabilityTags: string[]
  /** 收紧覆盖（overrides）是否非空 */
  hasOverrides: boolean
}

/** 平台链上的目录项形状与租户链 /api/cap/catalog/* 完全一致（复用旧契约类型）。 */
export type ConsoleToolItem = ToolCatalogItem
export type ConsoleSkillItem = SkillCatalogItem
export type ConsoleMcpItem = McpCatalogItem


/** 平台登录响应（POST /api/platform/v1/auth/login · TokenResponse）。
 *
 * ⛔ 无 tenantId：平台主体不属于任何租户；失败统一 401（防账号枚举）。
 */
export interface PlatformLoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  userId: string
  roles: string[]
}

/** 当前平台主体（GET /api/platform/v1/auth/me · MeResponse） */
export interface PlatformMe {
  userId: string
  roles: string[]
  authzVersion: number
  mfaVerified: boolean
}

/** 平台用户摘要（GET /api/platform/v1/users · UserSummary，绝不返回 password_hash） */
export interface PlatformUserSummary {
  id: string
  username: string
  displayName: string | null
  status: string
  authSource: string
  mfaRequired: boolean
  authzVersion: number
  lastLoginAt: string | null
}

/** 平台角色摘要（GET /api/platform/v1/roles · RoleSummary，来自冻结矩阵） */
export interface PlatformRoleSummary {
  name: string
  description: string
  permissions: string[]
}

/** 平台租户摘要（GET/POST /api/platform/v1/tenants · TenantSummary）。
 *
 * ⚠️ lifecycleState 为状态机值（PENDING_SETUP/ACTIVE/SUSPENDED/
 * DECOMMISSIONING/DECOMMISSIONED/CANCELLED），非旧 status 三值。
 */
export interface PlatformTenant {
  tenantId: string
  slug: string
  name: string
  lifecycleState: string
  plan: string | null
  effectivePolicyVersion: number
  tenantAuthzVersion: number
}

/** 平台租户配额（GET/PUT /api/platform/v1/tenants/{id}/quota） */
export interface PlatformQuota {
  agentLimit: number
  runConcurrencyLimit: number
  budgetLimit: string | null
}

/** 平台租户能力授权（GET/POST/DELETE /api/platform/v1/tenants/{id}/capabilities） */
export interface PlatformCapability {
  id: string
  tenantId: string
  resourceCode: string
  scope: string
  status: string
  version: number
}

/** 平台审计事件（GET /api/platform/v1/audit-events · AuditSummary，
 *  读 platform.platform_audit_event，与租户审计分域、无 tenant_id） */
export interface PlatformAuditEvent {
  id: string
  actorId: string
  actorType: string
  action: string
  targetType: string
  targetId: string | null
  decision: string
  reasonCode: string | null
  correlationId: string | null
  ip: string | null
  occurredAt: string
}
