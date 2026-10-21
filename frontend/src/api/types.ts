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
 * ⚠️ **不含 roles / 用户标识** —— 这是缺口 G1，
 * 故前端无法显示「当前身份」，不得自行假设角色。
 */
export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  /** ⚠️ 后端硬编码 900（不读配置 auth.jwt.token-ttl-seconds） */
  expiresIn: number
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
