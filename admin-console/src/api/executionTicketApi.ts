/**
 * 跨域执行票证 —— 平台控制台使用租户域端点的唯一合法通道。
 *
 * ⚠️ **为什么需要它**：`/api/runs` 是**租户域**端点，租户身份取自已验签令牌；
 *    控制台持有的是平台令牌（`aud=admin-console-api`），打过去必然 401 ——
 *    那是受众隔离按设计生效（`JwtTokenService.verify` 硬比 aud），不是缺陷。
 *    后端新增 `POST /api/platform/v1/tenants/{id}/execution-ticket`：
 *    由平台侧**签发**一枚 60 秒的租户令牌（角色固定 `AGENT_AUTHOR`，
 *    且必须与该主体库中登记的角色取交集），租户域侧的六道前置闸门照常生效。
 *
 * ⚠️ **票证只存内存**：绝不写入 sessionStorage / localStorage。
 *    它与租户口令在**同一台机器上等价可用**（60 秒内），
 *    落进任何存储就等于把一个租户身份留在浏览器 history / 磁盘上；
 *    刷新页面即失效是**特性**，不是不便 —— 重新取票会再写一条审计。
 *
 * ⚠️ **60 秒短于一次真实执行**：轮询状态时会在临近过期处自动续取（约每分钟 1 次）。
 *    续取走的是同一个受审计、受限流（30 次/60 秒/租户）的口，
 *    所以「长时间观察一次执行」不会把票证退化成常驻会话。
 */

import { request } from '@/api/client'

/** 一次签发的票证（后端 `TicketResponse`）。 */
export interface ExecutionTicket {
  accessToken: string
  tokenType: string
  tenantId: string
  principalId: string
  loginName: string
  roles: string[]
  /** ISO 时刻；到期即不可用（后端 TTL 60 秒） */
  expiresAt: string
  /** 票证标识：平台审计里就是 reason_code，可据此把泄露的票对回签发记录 */
  jti: string
}

/** 可被指名的租户成员（登录名/状态/角色；不含任何凭据）。 */
export interface TenantPrincipal {
  principalId: string
  loginName: string
  displayName: string | null
  status: string
  roles: string[]
}

/**
 * 提前续取的安全边界（毫秒）。
 *
 * ⚠️ 不能压到 0：票证过期与否由**服务端时钟**判定，本地只能猜。
 * 留 15 秒余量，宁可多发一次取票（有审计、有限流），
 * 也不要拿一张已经过期的票去打 `/api/runs` 然后报「通道不可达」。
 */
const REFRESH_MARGIN_MS = 15_000

/** 模块级内存态：页面刷新即清空，刻意的（见文件头 ⚠️）。 */
let current: ExecutionTicket | null = null

/** GET /api/platform/v1/tenants/{id}/principals —— 可指名的租户成员（平台令牌）。 */
export function getTenantPrincipals(tenantId: string): Promise<TenantPrincipal[]> {
  return request<TenantPrincipal[]>(
    `/platform/v1/tenants/${encodeURIComponent(tenantId)}/principals`,
    { method: 'GET' },
  )
}

/** POST /api/platform/v1/tenants/{id}/execution-ticket —— 显式指名成员签发短时票证。 */
export function requestExecutionTicket(
  tenantId: string,
  loginName: string,
): Promise<ExecutionTicket> {
  return request<ExecutionTicket>(
    `/platform/v1/tenants/${encodeURIComponent(tenantId)}/execution-ticket`,
    { method: 'POST', body: JSON.stringify({ loginName }) },
  )
}

/**
 * 取一张仍然有效的票证（必要时重新签发）。
 *
 * <p>换租户或换身份即视为换上下文：旧票**立刻丢弃**，不复用。
 */
export async function ensureExecutionTicket(
  tenantId: string,
  loginName: string,
): Promise<ExecutionTicket> {
  if (
    current !== null
    && current.tenantId === tenantId
    && current.loginName === loginName
    && Date.parse(current.expiresAt) - Date.now() > REFRESH_MARGIN_MS
  ) {
    return current
  }
  current = await requestExecutionTicket(tenantId, loginName)
  return current
}

/** 丢弃当前票证（切换租户/身份、或确定不再执行时调用）。 */
export function clearExecutionTicket(): void {
  current = null
}

/** 当前票证的只读快照（界面显示「以谁的身份、还有多少秒」）。 */
export function peekExecutionTicket(): ExecutionTicket | null {
  return current
}

/**
 * 票证 → 请求头（供租户域端点使用）。
 *
 * ⚠️ 返回的是**额外的 Authorization 头**：调用方必须以 `auth=false` 发起，
 *    否则 `client.ts` 会用平台会话令牌覆盖它 —— 那正是 401 的原始成因。
 */
export function ticketAuthHeader(ticket: ExecutionTicket): Record<string, string> {
  return { Authorization: `${ticket.tokenType} ${ticket.accessToken}` }
}
