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
