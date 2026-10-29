/**
 * RAG 全局语料<b>治理票据</b>（RAG-D11 第四刀·第二刀 §2.3 的前端一侧）。
 *
 * ⚠️ **为什么平台登录令牌不够用**：控制台会话令牌（`aud=admin-console-api`）
 *    证明的是「这个人登录了平台控制台」，而全局语料影响**所有租户**的检索结果。
 *    把登录态直接当「改全局语料的授权」，等于把一次具体的治理动作
 *    折算成一整段登录态的权限。票据多做一件事：签名 claim 里写明**这一档被授权了**
 *    （`rag_scope`），且是一枚**短 TTL、空角色**的独立凭据。
 *
 * ⚠️ **只存内存**：绝不写 sessionStorage / localStorage。
 *    它与「往全局桶写一行」这条授权在同一台机器上等价可用，
 *    落进任何浏览器存储就等于把全局语料的写权留在磁盘 / 其他标签页 / 扩展里。
 *    刷新页面即失效是**特性**——重新取票会再走一遍授权与限流，并多一条平台审计。
 *
 * ⚠️ **只随写请求出发**：读侧（列表／统计／问题清单）用会话令牌即可，
 *    后端读闸门只查「是不是平台域」，不查票据。把票据顺手挂在读请求上
 *    会让「哪些调用被授权动过全局语料」这件事在日志里失去区分度。
 *
 * ⚠️ **换作用域即作废**：`PLATFORM` 档离开后（切回租户档或换页）调
 *    {@link clearRagGovernanceTicket}，不留一张「下次还能用」的票。
 *    TTL 上界 900 秒，本模块在临近过期处主动丢弃（本地时钟只能猜，故留余量）。
 */

import { request } from '@/api/client'

/** 一张已签发的治理票据（后端 `TicketResponse`）。 */
export interface RagGovernanceTicket {
  /** ⚠️ 可用凭据：只准留在内存，绝不落任何浏览器存储 */
  accessToken: string
  tokenType: string
  /** 票据声明的档位（本轮后端只签 `PLATFORM`） */
  scope: string
  platformUserId: string
  /** ISO 时刻；到期即不可用（后端 TTL 60～900 秒） */
  expiresAt: string
  /** 票据标识：平台审计的 `reason_code` 就是它，可据此把泄露的票对回签发记录 */
  jti: string
}

/**
 * 提前丢弃的安全余量（毫秒）。
 *
 * ⚠️ 不能压到 0：过期由**服务端时钟**判定，本地只能猜。
 * 宁可让管理员多点一次「领取票据」（每次都有审计与限流），
 * 也不要拿一张已过期去打管理面然后报「无权限」。
 */
const DROP_MARGIN_MS = 15_000

/** 模块级内存态：页面刷新即清空，刻意的（见文件头 ⚠️）。 */
let current: RagGovernanceTicket | null = null

/** POST /api/platform/v1/rag/governance-ticket —— 显式申领一枚短时治理票据。 */
export function requestRagGovernanceTicket(scope: 'PLATFORM'): Promise<RagGovernanceTicket> {
  return request<RagGovernanceTicket>(
    '/platform/v1/rag/governance-ticket',
    { method: 'POST', body: JSON.stringify({ scope }) },
  )
}

/**
 * 取一张仍然有效的票据（必要时重新签发）。
 *
 * <p>档位变了就视为换上下文：旧票**立刻丢弃**，不复用——
 * 拿「盘点档票据」去写全局语料，后端会按票据与实际档位不一致直接 403。
 */
export async function ensureRagGovernanceTicket(
  scope: 'PLATFORM' = 'PLATFORM',
): Promise<RagGovernanceTicket> {
  if (
    current !== null
    && current.scope === scope
    && Date.parse(current.expiresAt) - Date.now() > DROP_MARGIN_MS
  ) {
    return current
  }
  const fresh = await requestRagGovernanceTicket(scope)
  current = fresh
  return fresh
}

/** 丢弃当前票据（离开平台档、作废、或确定不再治理时调用）。 */
export function clearRagGovernanceTicket(): void {
  current = null
}

/** 当前票据的只读快照（界面据此显示「哪张票、还剩多少秒」；null = 未领取）。 */
export function peekRagGovernanceTicket(): RagGovernanceTicket | null {
  return current
}

/**
 * 票据 → 请求头。
 *
 * ⚠️ 返回的是**覆盖会话令牌的 Authorization**：`client.ts` 里额外头的写入发生在
 *    会话令牌注入**之后**，因此这里给的值会真正发出去。只用于写请求。
 */
export function ticketAuthHeader(ticket: RagGovernanceTicket): Record<string, string> {
  return { Authorization: `${ticket.tokenType} ${ticket.accessToken}` }
}

/** 票据剩余有效秒数（向上取整；已过期返回 0，供界面提示「即将需要重新领取」）。 */
export function ragGovernanceTicketRemainingSeconds(
  ticket: RagGovernanceTicket,
  now: number = Date.now(),
): number {
  const left = Date.parse(ticket.expiresAt) - now
  return left <= 0 ? 0 : Math.ceil(left / 1000)
}

/**
 * 403 的统一文案（写全局语料时被拒的下一步只有一个：领票）。
 *
 * ⚠️ 后端 `ResponseStatusException` 的 reason 不进响应体的 `message` 字段，
 *    前端能拿到的只有「请求失败（403）」。而这里恰好知道原因该是什么：
 *    平台档写被拒 = 没有票据、或票据过期／档位不符。
 *    把这句话说死在**一处**，是为了两个入口（校验流转动作、重跑索引）不给出不同的猜测。
 */
export const TICKET_REQUIRED_HINT =
  '这次改动全局语料被拒（403）：没有治理票据、票据已过期，或票据声明的档位不是 PLATFORM。'
  + '请在页面上方「平台全局语料」处重新领取一张票据。'
