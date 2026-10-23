/**
 * API 类型定义 —— 严格对齐后端实测契约（FE-D1 §3.1）
 *
 * ⚠️ 本文件**只覆盖实测存在的 4 个端点**。
 * 后端无 OpenAPI/Swagger（FE-01 §0.1），故无契约来源可依。
 * 不为「未来某接口」预留类型——凭空造类型会固化错误契约。
 */

/* ── 认证 ──────────────────────────────────────────────── */

/** POST /api/auth/token 请求体 */
export interface LoginRequest {
  tenantId: string
  loginName: string
  password: string
}

/**
 * POST /api/auth/token 成功响应
 *
 * ⚠️ B1（2026-10-23）：登录响应**现已包含 `roles`**，前端据此做导航显隐。
 *    但 `roles` 仍是**可选**字段（`roles?`）：
 *    ① 后端版本较旧时该字段不存在；
 *    ② 登录响应缺失角色时，前端会用 {@code GET /api/auth/roles} 回补
 *       （见 {@link RolesResponse} 与 `SessionProvider` 的回补逻辑）。
 *    `roles` 仅用于 UI 提示，**不是安全边界**——后端执行时仍重新授权。
 */
export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  /** ⚠️ 后端硬编码 900（不读配置 auth.jwt.token-ttl-seconds） */
  expiresIn: number
  /**
   * 权威租户 ID（2026-10-22 新增）。
   *
   * ⚠️ RAG 管理面/检索面据此构造 {@code X-Tenant-Id} 头重建 RLS 租户上下文
   *    （`tokenStorage` 文档已明确该值即 RAG 侧的租户标识）。
   */
  tenantId?: string | null
  /** 主体 ID（2026-10-22 新增）——审核留痕需要「谁操作的」 */
  principalId?: string | null
  /**
   * 主体在本租户内的角色集合（B1：2026-10-23 新增）。
   *
   * ⚠️ 仅用于前端导航显隐（如学生不显示审批台）。
   *    安全判定以执行时后端重授权为准，前端**不得**据此放行敏感操作。
   */
  roles?: string[]
}

/**
 * GET /api/auth/roles 成功响应（G1：登录返角色）
 *
 * ⚠️ **本类型不是冗余**——它是「登录响应缺 roles」时的**唯一**补救通道：
 *    `POST /api/auth/token` 的 `roles` 字段是后端 B1 增补的，**旧版后端没有**；
 *    而会话是持久化在 sessionStorage 里的，登录早于 B1 的会话其 `roles` 恒为空，
 *    管理员会**永久**看不到管理菜单，除非重新登录。
 *    `/api/auth/roles` 按令牌现查库中登记角色，故可在**不重新登录**的前提下回补。
 *
 * ⚠️ 角色仅用于 UI 提示，**不是安全边界**——后端执行时仍重新授权（红线 9）。
 */
export interface RolesResponse {
  roles: string[]
}

/* ── Run ───────────────────────────────────────────────── */

/**
 * POST /api/runs 请求体
 *
 * ⚠️ 幂等键是**请求体字段**，不是 Idempotency-Key 头。
 */
export interface CreateRunRequest {
  idempotencyKey: string
  agentId: string
  input: Record<string, unknown>
  requestedBy?: string
}

/** POST /api/runs 响应体 */
export interface CreateRunResponse {
  runId: string
  /** true = 幂等重放，返回的是原 runId */
  replayed: boolean
  /** ⚠️ 后端硬编码 "QUEUED"，非取自实际状态机 */
  state: string
  /** 排障用：500 时展示给用户 */
  correlationId: string
}

/* ── Run 事件（SSE）───────────────────────────────────── */

/**
 * SSE data 载荷的**白名单字段**。
 *
 * ⚠️ 后端为白名单透传（P2-D05 修正：黑名单方案被证伪，
 * studentName / idCard 都是字符串标量会原样输出）。
 * 固定字段 sequenceNo / at 始终存在，其余按事件类型可选。
 */
export interface RunEventPayload {
  sequenceNo: number
  at: string
  state?: string
  attempt?: number
  stepType?: string
  toolName?: string
  operationState?: string
  errorCode?: string
  phase?: string
}

/** ⚠️ 后端 run_event.event_type 是裸 TEXT，**无 CHECK 约束、无枚举类**。
 *  实际出现过的取值中，仅 RUN_CREATED 有生产写入方（FE-D1 §7.4）。
 *  故此处为 string 而非联合类型——枚举化会固化未知值。 */
export type RunEventType = string

/* ── 配置只读（P4-D07 / C1）───────────────────────────── */

/**
 * GET /api/config 响应：配置快照（声明值 vs 运行时实际生效值）。
 *
 * ⚠️ 严格对齐后端 {@code ConfigResponse} record。
 * 绝不暴露任何密钥 / 口令 / 私钥（后端 B-2 铁律）。
 */
export interface ConfigResponse {
  /** 按业务域分组的配置条目 */
  groups: ConfigGroup[]
  /** 零保留（DEC-10 GATE-2）运行态视图 */
  zeroRetention: ZeroRetentionView
  /** 扁平的 key → effective 投影，便于前端程序化判断 */
  effectiveFlags: Record<string, boolean>
}

/** 配置分组（按业务域聚合若干配置条目） */
export interface ConfigGroup {
  /** 分组名（如「运行与容量」「工具与审批」） */
  name: string
  /** 组内条目 */
  entries: ConfigEntry[]
}

/**
 * 单条配置的诚实视图。
 *
 * ⚠️ 为什么必须带 {@code effective}：FE-02 §0.3 要求配置页区分
 * 「yml 声明值」与「运行时实际生效值」。本项目有大量 yml 键是摆设
 * （声明了但生产无读取点），前端**绝不**把声明值渲染成已生效。
 */
export interface ConfigEntry {
  /** 配置键（与 application.yml 完全对应） */
  key: string
  /** yml 解析后的声明值；含环境变量覆盖。未声明时为 "(未声明)" */
  declaredValue: string
  /** 该键是否真正接入运行时（改变它会影响行为） */
  effective: boolean
  /** 声明来源（yml / yml(校验镜像) 等） */
  source: string
  /** 诚实说明：已接入 / 仅声明未接线 / 仅声明未确认 */
  note: string
}

/**
 * 零保留（DEC-10 GATE-2）运行态视图。
 *
 * ⚠️ 刻意区分「声明」与「运行时确认」：yml 只声明了供应商清单，
 * 不含证据出处 → 注册表这些供应商均为 PENDING（≠ CONFIRMED）
 * → 闸门仍 fail-closed 拒绝真实学生数据。
 * 前端据此显示「已声明 N 家，当前均无有效确认证据 → 仍 fail-closed 拒绝」，
 * 而非误导性的「已确认放行」。
 */
export interface ZeroRetentionView {
  /** yml 声明的供应商清单（声明 of truth） */
  declaredProviders: string[]
  /** 注册表实际状态：providerId → 有效状态（PENDING/CONFIRMED/REJECTED/EXPIRED） */
  registryStatus: Record<string, string>
  /** 是否至少有一家已真正确认（CONFIRMED）。本片恒为 false */
  anyConfirmed: boolean
  /** GATE-1 是否仍在生效（无任何供应商允许真实数据接入）。本片恒为 true */
  failClosed: boolean
}

/* ── 结果读取（P4-D10 / G5）────────────────────────────────── */

/**
 * Run 结果响应（后端契约见 P4-D10 §2.2，冻结条款 K-1～K-10）。
 *
 * ⚠️ `completedAt` 在非终态为 `null`——**不可**退化成 epoch 字符串。
 * ⚠️ `explanation` 仅在成功时有值；失败时后端**不返回**失败原因原文。
 * ⚠️ `weaknesses` / `steps` **恒为数组**（空即空数组）——前端不得区分 null。
 */
export interface RunResultResponse {
  runId: string
  state: string
  completedAt: string | null
  errorCode: string | null
  explanation: string | null
  weaknesses: ResultWeakness[]
  steps: ResultStep[]
  reconciliation: ResultReconciliation
}

/** 错因与知识点（结构化对象，非 string[]）。 */
export interface ResultWeakness {
  knowledgePoint: string
  errorType: string
  /** 参考值，不参与任何判定；可能为 null。 */
  confidence: number | null
}

/** 解题步骤。⚠️ `output` 是原始 JSON 文本，且当前恒无数据（无写入方）。 */
export interface ResultStep {
  number: number
  state: string
  stepType: string
  output: string | null
}

/** 待核对分区（P0-D00 §3.4 硬要求）：**恒存在**，不是「有才显示」。 */
export interface ResultReconciliation {
  pending: boolean
  operations: UnknownOperation[]
}

/** 未决工具操作。 */
export interface UnknownOperation {
  operationId: string
  toolName: string
  state: string
}

/* ── 学习画像（P4-D09 / G6）─────────────────────────────────── */

/**
 * 学习画像响应。
 *
 * ⚠️ `notFound` 为 true 时后端仍返回 200（不返回 404，避免枚举 studentId），
 * 前端**必须**据此区分「没有这个学生」与「有画像但没数据」。
 */
export interface ProfileResponse {
  studentId: string
  grade: string | null
  subject: string | null
  weaknesses: ProfileWeakness[]
  notFound: boolean
}

/** 单条薄弱点（结构化对象）。 */
export interface ProfileWeakness {
  knowledgePoint: string
  errorType: string
  confidence: number | null
  createdAt: string | null
}

/* ── 治理只读 / 决策（P4-D13 / G7~G10）──────────────────── */

/* ── Run 列表（G7 / P7 检索台）────────────────────────────────── */

/**
 * 游标分页响应。
 *
 * ⚠️ `nextCursor === null` 表示**没有更多**，与「本页为空」是两回事。
 */
export interface PageResponse<T> {
  items: T[]
  nextCursor: string | null
}

/** `run_state` 的合法取值（P4-D10 §1.4 实测，**不含 UNKNOWN**）。 */
export type RunState =
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCELLED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMED_OUT'

export interface RunListItem {
  runId: string
  state: string
  createdAt: string
  /** 非终态为 null */
  completedAt: string | null
  attempt: number
  /** 失败原因**码**，不是错误原文；当前生产未写入（P4-D12 L-1）→ 恒为 null */
  errorCode: string | null
}

/* ── 审批（G8 / P8 审批台）────────────────────────────────────── */

export type ApprovalState = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CONSUMED'

export interface ApprovalItem {
  approvalId: string
  operationId: string
  runId: string
  state: string
  requirement: string
  /** ⚠️ 是**参数摘要**，不是参数原文（FR-014） */
  argumentDigest: string
  targetResource: string
  approverScope: string
  expiresAt: string
  approverId: string | null
  decidedAt: string | null
  createdAt: string
  /** ⚠️ **服务端计算**的过期标志——前端不得自己比时间 */
  expired: boolean
}

export interface ApprovalDecisionResponse {
  approvalId: string
  state: string
}

/* ── UNKNOWN 处置（G9 / P9）───────────────────────────────────── */

export type OperationState =
  | 'PENDING'
  | 'IN_FLIGHT'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'UNKNOWN'
  | 'RECONCILED_SUCCEEDED'
  | 'RECONCILED_ABSENT'
  | 'MANUAL_DISPOSED'

export interface OperationItem {
  operationId: string
  runId: string
  toolName: string
  riskLevel: string
  state: string
  createdAt: string
  updatedAt: string
  /** P3-D03 自动对账来源；人工处置后为 `MANUAL` */
  reconciliationSource: string | null
  manuallyDisposed: boolean
}

/**
 * 处置取值。
 *
 * ⛔⛔ **刻意没有 `RETRY`**：UNKNOWN 的含义是「副作用是否已发生**不知道**」，
 * 此时重放 = 可能在学生数据上重复施加副作用（**Sev-0**）。
 * 三个取值全部是**终态归档**，没有任何一条会退回执行态。
 */
export type Disposition = 'RECONCILED_SUCCEEDED' | 'RECONCILED_ABSENT' | 'MANUAL_DISPOSED'

export interface DispositionResponse {
  operationId: string
  state: string
  disposedBy: string
  disposedAt: string
}

/* ── 审计（G10 / P10）──────────────────────────────────────────── */

export interface AuditEntry {
  auditId: string
  actorId: string
  action: string
  resourceType: string
  resourceId: string | null
  createdAt: string
  correlationId: string | null
  detail: Record<string, unknown>
}

/**
 * Run 状态快照（后端 `GET /api/runs/{runId}`）。
 *
 * ⚠️ `completedAt` 在非终态为 `null`——**不可**退化成 epoch 字符串。
 * ⚠️ `errorCode` 是**错误码**，不含内部错误原文。
 */
export interface RunStatusResponse {
  runId: string
  /** run_state 枚举字符串；⛔ 绝不为 UNKNOWN（那是 tool_operation 的状态） */
  state: string
  createdAt: string
  completedAt: string | null
  attempt: number
  errorCode: string | null
}

/* ── Agent 配置与发布（P4-D14 / G2）────────────────────── */

/**
 * Agent 列表项（后端 `GET /api/agents`，与 `AgentItemResponse` 同构）。
 *
 * ⚠️ `latestDefinition` / `latestPublishedAt` 在「尚无版本」时为 `null`——
 *    前端**不得**把 null 退化成占位串，直接显示「—」或「未发布」。
 * ⚠️ `description` 可空（创建时可不填）。
 */
export interface AgentItem {
  agentId: string
  name: string
  description: string | null
  versionCount: number
  latestVersionNumber: number
  latestDefinition: string | null
  latestPublishedAt: string | null
}

/** 已发布的 Agent 版本（后端 `POST /api/agents/{id}/versions` 响应）。 */
export interface AgentVersionItem {
  versionId: string
  versionNumber: number
  definition: string
  publishedAt: string | null
  createdAt: string
}

/** POST /api/agents 请求体。 */
export interface CreateAgentRequest {
  name: string
  description?: string | null
}

/** POST /api/agents/{id}/versions 请求体。 */
export interface PublishVersionRequest {
  definition: string
}

/* ── P4-D15 配置写入（F6 · C2/C3/C5/C7）───────────────────────── */

/** 配置收紧方向（与后端 `ConfigOverridePort.Tightening` 一致）。 */
export type Tightening = 'TIGHTEN' | 'TIGHTEN_RATCHET' | 'NEUTRAL'

/**
 * 单条配置覆盖（后端 `GET /api/config/overrides` 列表项）。
 *
 * ⚠️ `declaredValue` 与 `effectiveValue` **刻意并存**：
 *    前者是对照用的 yml 声明值，后者才是当前真正生效的值。
 *    未被覆盖时二者相同且 `source='yml'`；被覆盖时 `source='db_override'`。
 *    页面必须**如实展示这一区别**——否则管理员无法判断自己改的值有没有生效。
 */
export interface ConfigOverrideItem {
  key: string
  /** yml 声明值（不可变参照） */
  declaredValue: string | null
  /** 当前生效值（DB 覆盖优先） */
  effectiveValue: string | null
  /** 'db_override' | 'yml' | null（后端未返回该键时） */
  source: string | null
  /** 收紧方向：决定 UI 允许的方向 */
  tightening: Tightening
  /** 上次变更理由（未变更过为 null） */
  reason: string | null
  /** 上次操作者 */
  changedBy: string | null
  /** 上次变更时刻 */
  updatedAt: string | null
}

/** PUT /api/config/overrides/{key} 请求体。 */
export interface ApplyOverrideRequest {
  value: string
  /** CHG 变更理由，后端要求 ≥10 字 */
  reason: string
}

/* ── RAG 检索增强（rag 模块 /api/v1/retrieval）──────────────
 *
 * ⚠️ 严格对齐后端 `rag-api` 实测契约（RAG-D02 §6 / RAG-D00 §2.8）。
 * 后端当前**只暴露检索端点**，生成端点（/api/v1/retrieval/generate）
 * 尚未实现 —— 前端先把契约定下来，便于后端零改动点亮，
 * 接入前调用会得到 404/501，页面按「生成能力未接入」诚实呈现。
 */

/**
 * 检索模式（对齐后端 `RetrievalRequest.RetrievalMode` 枚举）。
 * ⚠️ 取值必须与后端一致：EXPLAIN=讲解 / SOLVE=解题 / VERIFY=核对 / OVERVIEW=概览。
 * 模式是**检索期过滤条件**而非生成期提示（防泄题第一道闸门）。
 */
export type RagRetrievalMode = 'EXPLAIN' | 'SOLVE' | 'VERIFY' | 'OVERVIEW'

/**
 * 版本检索范围模式（对齐后端 `EditionScope.Mode`）。
 * STRICT=严格锁定当前版本；PREFERRED_WITH_FALLBACK=优先本版可回退同册；CROSS_EDITION=跨版本。
 */
export type RagScopeMode = 'STRICT' | 'PREFERRED_WITH_FALLBACK' | 'CROSS_EDITION'

/**
 * 教材版本坐标系（对齐后端 `EditionDto`：五轴字符串以**展示名**传入）。
 *
 * ⚠️ 五轴全部必填且必须命中后端枚举展示名，否则后端 400
 *    （控制器负责把展示名映射到领域枚举，不匹配即 400）。
 *    subject/grade/term/publisher 用下方 RagEditionOptions 的展示名；
 *    revisionCode 为自由文本版次（如 "2024"）。
 */
export interface RagEdition {
  subject: string
  grade: string
  term: string
  publisher: string
  revisionCode: string
}

/** POST /api/v1/retrieval 请求体（对齐 `RetrievalRequestDto`）。 */
export interface RagRetrievalRequest {
  /** 用户问题（后端 @NotBlank） */
  query: string
  /** 检索模式（后端 @NotNull） */
  mode: RagRetrievalMode
  /** 版本坐标系（后端 @NotNull，五轴全必填） */
  edition: RagEdition
  /** 版本检索范围；可空，后端缺省按 STRICT 处理 */
  scopeMode?: RagScopeMode | null
  /** 已知知识点 ID → 走知识点聚合（解决碎片化召回） */
  knowledgePointId?: string | null
  /** ⚠️ 与 edition 五轴重复，本项目不另传，恒为 null，避免语义冲突 */
  subject?: string | null
  /** ⚠️ 与 edition 五轴重复，本项目不另传，恒为 null */
  grade?: string | null
  /** 召回数量（后端 @Min(1) @Max(50)） */
  topK: number
}

/** 单个召回片段（对齐 `RetrievalResultDto.HitDto`）。 */
export interface RagRetrievalHit {
  /** 片段 ID */
  chunkId: string
  /** 片段正文 */
  text: string
  /** 片段角色（EXPLANATION / EXERCISE_HINT / EXERCISE_ANSWER …） */
  role: string
  /** 综合得分 0~1（低于门禁已被编排层过滤） */
  score: number
  /** 版本展示名 */
  edition: string
  /** 面向学生的引用文本（册/章/页/题） */
  locator: string
  /** 召回通道（稠密/词法/知识点聚合…） */
  channel: string
  /** 命中原因 */
  reason: string
}

/** POST /api/v1/retrieval 响应体（对齐 `RetrievalResultDto`）。 */
export interface RagRetrievalResponse {
  hits: RagRetrievalHit[]
  /** 是否检测到版本不匹配（前端应显式提示差异） */
  versionMismatchDetected: boolean
  /** 答案类内容是否被抑制（讲解模式下应为 true） */
  answerContentSuppressed: boolean
  /** 主版本展示名 */
  primaryEdition: string
}

/**
 * 生成回答请求（对应 `POST /api/v1/retrieval/generate`，B5 已实现）。
 *
 * ⚠️ **依据由调用方指定**：`hitIds` 必须来自前一次检索的命中列表。
 *    后端不自行检索——否则「依据集合」退化成服务端黑箱，
 *    学生看不到这段话引用了哪几处语料，审计也无法复现。
 */
export interface RagGenerateRequest {
  query: string
  mode: RagRetrievalMode
  edition: RagEdition
  /** 检索阶段召回的片段 ID，作为生成依据 */
  hitIds: string[]
  /** 版本范围模式（缺省按 STRICT 严格锁定当前版本） */
  scopeMode?: RagScopeMode | null
}

/**
 * 生成回答响应（B5）。
 *
 * ⚠️ `generator` 与 `disclaimer` **不是可选的装饰字段**：
 *    前者说明「这段文字是怎么来的」，后者说明「哪些内容被策略藏起来了」。
 *    只展示 `answer` 而不展示它们，等于把「逐字摘录」伪装成「AI 讲解」。
 */
export interface RagGenerateResponse {
  /** 讲解正文。空串 = 依据不足（合法业务结果，不是故障） */
  answer: string
  /** 实际构成讲解的依据片段 */
  citations: RagRetrievalHit[]
  /**
   * 生成方式标识。当前恒为 `EXCERPT_ASSEMBLY`（逐字摘录装配）。
   *
   * ⚠️ 真实大模型接入前不会是 `LLM`；界面必须如实展示这个值，
   *    不得写「AI 生成」。
   */
  generator: string
  /** 必须随回答展示的说明（空串表示无附加说明） */
  disclaimer: string
  /** 依据中是否存在非首选版本内容 */
  versionMismatchDetected: boolean
  /** 是否因模式过滤丢弃过答案类内容 */
  answerContentSuppressed: boolean
}

/**
 * 教材版本可选项（展示名）。
 *
 * ⚠️ 从后端 `rag-core` 的 `EditionCoordinate` 枚举逐项提取，
 *    **不得**改成自由输入（否则「人教版」「人教」会被当成不同版本导致漏召回）。
 *    后端 `RagRetrievalController.toCoordinate` 用 `displayName()` 精确匹配。
 */
export const RAG_SUBJECTS = [
  '数学', '语文', '英语', '物理', '化学',
  '生物', '历史', '地理', '道法',
] as const

export const RAG_GRADES = [
  '一年级', '二年级', '三年级', '四年级', '五年级', '六年级',
  '七年级', '八年级', '九年级',
  '高一', '高二', '高三',
] as const

export const RAG_TERMS = ['上册', '下册', '全一册'] as const

export const RAG_PUBLISHERS = [
  '人教版', '苏教版', '北师大版', '华师大版', '鲁教版', '鄂教版', '其他',
] as const

/** 检索模式的可读标签（界面展示用，值仍用枚举原名）。 */
export const RAG_MODE_LABELS: Record<RagRetrievalMode, string> = {
  EXPLAIN: '讲解',
  SOLVE: '解题',
  VERIFY: '核对',
  OVERVIEW: '概览',
}

/** 版本范围模式的可读标签。 */
export const RAG_SCOPE_LABELS: Record<RagScopeMode, string> = {
  STRICT: '严格锁定当前版本',
  PREFERRED_WITH_FALLBACK: '优先本版，可回退同册',
  CROSS_EDITION: '跨版本对比',
}

/* ── RAG 语料数据管理（rag-management / RAG-D10）───────────────── */

/**
 * 语料来源的校验状态（对齐后端 `SourceVerification.VerificationStatus`）。
 *
 * ⚠️ 取值与后端枚举严格一致（RAG-D07 §2.4 状态机 T-1～T-12）：
 *    PENDING 待校验 → IN_REVIEW 审核中 → VERIFIED 已通过
 *    REJECTED 已拒绝 / REVOKED 已撤回
 *
 * ⚠️ **REVOKED 是终态**：撤回后不可复活，重新准入必须新建 source_id
 *    （RAG-D07 §2.4 T-10）。前端不得提供「撤销撤回」入口。
 */
export type RagVerificationStatus =
  | 'PENDING'
  | 'IN_REVIEW'
  | 'VERIFIED'
  | 'REJECTED'
  | 'REVOKED'

/** 权威等级（对齐后端 `TrustTier`）。 */
export type RagTrustTier =
  | 'OFFICIAL_PUBLISHER'
  | 'OFFICIAL_TUTORIAL'
  | 'PEER_REVIEWED_PAPER'
  | 'PREPRINT'
  | 'UNVERIFIED'

/** 校验手段（对齐后端 `SourceVerification.VerificationMethod`）。 */
export type RagVerificationMethod =
  | 'NONE'
  | 'ISBN'
  | 'DOI'
  | 'MANUAL_REVIEW'
  | 'OFFICIAL_HASH'

/** 版权状态（对齐后端 `rag_license_status` 枚举，V3 迁移后为 DB 枚举）。 */
export type RagLicenseStatus =
  | 'LICENSED'
  | 'FAIR_USE_QUOTATION'
  | 'PENDING'
  | 'RESTRICTED'

/**
 * 来源视图（对齐后端 `ManagementDtos.SourceResponse`）。
 *
 * ⚠️ `stale` 与 `admissible` 是**服务端算好的计算属性**：
 *    · stale = 校验结论已过期（verified_at + valid_days < now）
 *      ——「过期」**不是**状态位，枚举里没有 STALE（RAG-D07 §3.4）
 *    · admissible = 当前准入（VERIFIED ∧ 未过期 ∧ 达门槛 ∧ 版权放行）
 *    前端**不得**自行推算，租户权威门槛只有服务端知道。
 */
export interface RagSource {
  sourceId: string
  sourceType: string
  title: string
  publisher: string
  isbn: string | null
  doi: string | null
  trustTier: RagTrustTier
  verificationStatus: RagVerificationStatus
  verificationMethod: RagVerificationMethod
  verificationEvidence: string | null
  verifiedAt: string | null
  verifiedBy: string | null
  validDays: number
  licenseStatus: RagLicenseStatus
  /** 校验结论是否已过期（计算属性） */
  stale: boolean
  /** 当前是否准入可检索（计算属性） */
  admissible: boolean
  objectRef: string | null
  contentHash: string | null
  createdAt: string
  updatedAt: string
}

/** 文档载体视图（对齐后端 `ManagementDtos.DocumentResponse`）。 */
export interface RagDocument {
  documentId: string
  sourceId: string
  editionId: string
  carrier: string
  title: string
  pageCount: number | null
  objectRef: string | null
  parseStatus: string
  parserVersion: string | null
  contentHash: string | null
  chunkCount: number
  /**
   * 载体级校验状态（V3 迁移新增字段，此前一直是死字段）。
   *
   * ⚠️ 与来源级状态**并行**：同一 edition 下可以有官方电子版（VERIFIED）
   *    与第三方扫描版（REJECTED），压平就无法表达「只下架其中一个载体」。
   */
  verificationStatus?: RagVerificationStatus
  /** 载体级可检索标记 —— 撤回时置 false */
  retrievable?: boolean
  /** 撤回时刻 */
  revokedAt?: string | null
  /** 撤回依据（DB CHECK：REVOKED 必带依据） */
  revokedReason?: string | null
  /** 载体级版权状态（独立于来源级版权） */
  licenseStatus?: RagLicenseStatus
  createdAt: string
  updatedAt: string
}

/** 片段摘要（对齐后端 `ManagementDtos.ChunkResponse`）。 */
export interface RagChunk {
  chunkId: string
  role: string
  pageNumber: number
  sectionPath: string
  exerciseNo: string | null
  charLength: number
  /** 是否已写入向量 —— 缺失即「静默漏召回」风险（RAG-D03 §1） */
  hasEmbedding: boolean
  contentHash: string | null
}

/** 索引统计（对齐后端 `ManagementDtos.StatisticsResponse`）。 */
export interface RagStatistics {
  totalSources: number
  verifiedSources: number
  admissibleSources: number
  totalDocuments: number
  totalChunks: number
  /** 可检索片段数 —— 与 totalChunks 的差额即「已入库但不可检索」 */
  retrievableChunks: number
  /** 已入库但缺向量的片段数 —— 静默漏召回风险 */
  chunksMissingEmbedding: number
  missingEmbeddingRatio: number
  chunksByRole: Record<string, number>
  sourcesByVerificationStatus: Record<string, number>
  documentsByParseStatus: Record<string, number>
}

/** 来源登记请求（对齐后端 `ManagementDtos.RegisterSourceRequest`）。 */
export interface RagRegisterSourceRequest {
  sourceType: string
  title: string
  publisher: string
  isbn: string | null
  doi: string | null
  objectRef: string | null
  contentHash: string | null
  /** 登记人 UUID —— 登记必须留痕（后端 principal_id NOT NULL） */
  registeredBy: string
}

/** 校验决定请求（对齐后端 `ManagementDtos.VerificationRequest`）。 */
export interface RagVerificationRequest {
  targetStatus: RagVerificationStatus
  /** 校验依据 —— 必填（后端强制留痕） */
  evidenceRef: string
  /** 决定人 UUID —— 必填 */
  decidedBy: string
  verificationMethod: RagVerificationMethod
  /** 判定 VERIFIED 时必填（否则来源通过校验却仍被权威门槛拦下） */
  trustTier: RagTrustTier | null
  validDays: number
}

/**
 * 载体级校验决定请求（对齐后端 `ManagementDtos.DocumentVerificationRequest`）。
 *
 * ⚠️ 字段与来源级**同构**，但作用对象不同：载体是「物理载体」
 *    （一份 PDF / 一册扫描件），下架它不影响同版本的其它载体。
 */
export interface RagDocumentVerificationRequest {
  targetStatus: RagVerificationStatus
  /** 校验依据 —— 必填（撤回必带依据，DB CHECK 亦会拦） */
  evidenceRef: string
  /** 决定人 UUID —— 必填 */
  decidedBy: string
  verificationMethod: RagVerificationMethod
  trustTier: RagTrustTier | null
  validDays: number
}

/**
 * 维护问题清单（对齐后端 `ManagementDtos.IssuesResponse`）。
 *
 * ⚠️ 这些是**「哪里坏了」的定位信息**，不是统计数字——
 *    统计只说「缺 12 个向量」，清单才说「是哪几个载体」。
 */
export interface RagMaintenanceIssues {
  /** 缺向量的载体（片段已入库但没有向量 → 静默漏召回） */
  documentsMissingEmbedding: RagIssueDocument[]
  /** 解析失败的载体（不产出片段 → 这份语料在检索侧等于不存在） */
  documentsFailedParse: RagIssueDocument[]
  /** 待审核的来源 */
  sourcesPendingReview: RagIssueSource[]
  totals: {
    documentsMissingEmbedding: number
    documentsFailedParse: number
    sourcesPendingReview: number
  }
}

/** 问题清单里的载体条目。 */
export interface RagIssueDocument {
  documentId: string
  sourceId: string
  title: string
  carrier: string
  parseStatus: string
  chunkCount: number
  /** 缺向量的片段数（仅 documentsMissingEmbedding 有意义） */
  missingEmbeddingCount?: number
}

/** 问题清单里的来源条目。 */
export interface RagIssueSource {
  sourceId: string
  title: string
  verificationStatus: RagVerificationStatus
  stale: boolean
  trustTier: RagTrustTier
}

/** 重跑索引结果（对齐后端 `ManagementDtos.ReindexResponse`）。 */
export interface RagReindexResult {
  indexedChunks: number
  scannedChunks: number
  durationMs: number
}

/** 来源查询参数（对齐后端 `SourceQuery`）。 */
export interface RagSourceQuery {  status?: RagVerificationStatus | null
  trustTier?: RagTrustTier | null
  keyword?: string | null
  includeStale?: boolean
  limit?: number
  offset?: number
}

/** 校验状态的可读标签。 */
export const RAG_VERIFICATION_LABELS: Record<RagVerificationStatus, string> = {
  PENDING: '待校验',
  IN_REVIEW: '审核中',
  VERIFIED: '已通过',
  REJECTED: '已拒绝',
  REVOKED: '已撤回',
}

/** 权威等级的可读标签。 */
export const RAG_TRUST_TIER_LABELS: Record<RagTrustTier, string> = {
  OFFICIAL_PUBLISHER: '教材出版社官方',
  OFFICIAL_TUTORIAL: '教辅品牌正式出版',
  PEER_REVIEWED_PAPER: '同行评审论文',
  PREPRINT: '预印本 / 白皮书',
  UNVERIFIED: '未校验',
}

/** 校验手段的可读标签。 */
export const RAG_VERIFICATION_METHOD_LABELS: Record<RagVerificationMethod, string> = {
  NONE: '无',
  ISBN: 'ISBN 校验',
  DOI: 'DOI 校验',
  MANUAL_REVIEW: '人工逐份比对',
  OFFICIAL_HASH: '官方哈希比对',
}

/** 版权状态的可读标签。 */
export const RAG_LICENSE_LABELS: Record<RagLicenseStatus, string> = {
  LICENSED: '已获授权',
  FAIR_USE_QUOTATION: '合理引用',
  PENDING: '未取得授权',
  RESTRICTED: '禁止使用',
}

/**
 * 片段角色的可读标签（对齐后端 `rag-core` 的 `ChunkRole` 枚举）。
 *
 * ⚠️ 统计端点的 `chunksByRole` 以**枚举名**为 key（如 `EXERCISE_ANSWER`），
 *    直接把英文枚举名甩给运维是「数据已取到但没读懂」——
 *    故这里补一层中文标签。
 *
 * ⚠️ 用 `Record<string, string>` 而非 `Record<ChunkRole, string>`：
 *    分布表的 key 来自 DB 的 `role::text`，**类型上不可知**，
 *    新增枚举值时不该让前端编译失败（那会阻断构建而非暴露问题）。
 */
export const RAG_CHUNK_ROLE_LABELS: Record<string, string> = {
  EXPLANATION: '正文讲解',
  EXAMPLE: '例题',
  EXAMPLE_SOLUTION: '例题标准解答',
  EXERCISE: '习题',
  EXERCISE_ANSWER: '习题答案',
  EXERCISE_HINT: '习题提示',
  PAPER_BODY: '论文正文',
  PAPER_METHOD: '论文方法',
  APPENDIX: '附录',
}

/**
 * 载体解析状态的可读标签（对齐 `rag_document.parse_status` 的 DB CHECK 白名单）。
 *
 * ⚠️ 同上：key 来自 DB，类型不可知，故用 `Record<string, string>`。
 */
export const RAG_PARSE_STATUS_LABELS: Record<string, string> = {
  PENDING: '待解析',
  PARSED: '已解析',
  CHUNKED: '已切分',
  FAILED: '解析失败',
}

/* ── 报表中心（平台统计 / RAG 每日统计）────────────────── */

/**
 * 平台侧每日统计（对齐后端 `StatisticsPort.DailyStatistics`）。
 *
 * ⚠️ **只含租户级聚合，不含任何学生可识别信息**——
 *    后端刻意不返回 student_id / 姓名 / 题目原文（P0-D00 §4 合规红线）。
 *    前端**不得**据此推断个体行为。
 */
export interface DailyStatistics {
  /** 每日辅导量（连续日期，缺数据填 0） */
  runsByDay: RunDayStat[]
  /** 每日错题分析数（连续日期，缺数据填 0） */
  weaknessesByDay: WeaknessDayStat[]
  /** 每日学生活跃 / 新增（连续日期，缺数据填 0） */
  studentsByDay: StudentDayStat[]
  /** 薄弱知识点排行（**全历史累计**，按命中次数降序） */
  topKnowledgePoints: KnowledgePointRank[]
  /** 薄弱知识点排行（**所选区间内**，按命中次数降序） */
  topKnowledgePointsInRange: KnowledgePointRank[]
  /** 实际生成的连续天数 */
  generatedDays: number
  /** 本区间汇总（与逐日数组同源，后端口径） */
  currentPeriod: PeriodSummary
  /** 紧邻的上一等长区间汇总（环比基线） */
  previousPeriod: PeriodSummary
}

/**
 * 一个统计窗口的汇总值（环比用，对齐后端 `StatisticsPort.PeriodSummary`）。
 *
 * ⚠️ **前端不得自行对逐日数组求和来替代它**：
 *    「上一区间」的数据根本不在响应里，前端无从算起；
 *    若本区间由前端求和、上一区间由后端给出，
 *    就出现两份口径，分歧时同一张报表会有两个总数。
 *    两个窗口都由后端给出，前端只做展示与对比。
 */
export interface PeriodSummary {
  /** 发起量（含在途） */
  total: number
  /** 终态量（成功率分母） */
  terminal: number
  succeeded: number
  failed: number
  timedOut: number
  cancelled: number
  /** 错因记录条数 */
  weaknessCount: number
  /** 活跃**人次**（逐日去重后累加；不是去重人数） */
  activeStudentDays: number
  /** 新增学生数 */
  newStudents: number
}

/** 单日辅导量（含终态拆分）。 */
export interface RunDayStat {
  /** UTC 日期，格式 yyyy-MM-dd */
  date: string
  /** 当日发起的辅导总数（含在途：排队中/分析中） */
  total: number
  /** 当日终态数（成功+失败+超时+取消）—— 成功率的分母 */
  terminal: number
  succeeded: number
  failed: number
  timedOut: number
  cancelled: number
}

/** 单日错题分析数（错题产生的错因记录条数）。 */
export interface WeaknessDayStat {
  date: string
  count: number
}

/** 单日学生活跃 / 新增。 */
export interface StudentDayStat {
  date: string
  /** 当日参与错因分析的去重学生数 */
  activeStudents: number
  /** 当日首次建档的学生数 */
  newStudents: number
}

/** 薄弱知识点排行项。 */
export interface KnowledgePointRank {
  knowledgePoint: string
  /** 命中次数 */
  count: number
  /** 该知识点下的错因分布（errorType → 次数） */
  errorTypes: Record<string, number>
}

/** RAG 侧每日检索次数（对齐后端 `ManagementDtos.DailySearchResponse`）。 */
export interface RagDailySearchStatistics {
  searchesByDay: RagDailySearchCount[]
  generatedDays: number
}

/** 单日检索次数。 */
export interface RagDailySearchCount {
  date: string
  searches: number
}

/** RAG 侧每日导入语料量（对齐后端 `ManagementDtos.DailyImportResponse`）。 */
export interface RagDailyImportStatistics {
  importsByDay: RagDailyImportCount[]
  generatedDays: number
}

/** 单日导入语料量（三层级）。 */
export interface RagDailyImportCount {
  date: string
  /** 资料来源（册 / 作品）数 */
  sources: number
  /** 文档载体（物理文件）数 */
  documents: number
  /** 可检索片段数 */
  chunks: number
}
