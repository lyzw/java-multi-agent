/**
 * 错误指引的**单一真相源**（E2E 二次评估 N2 / N3 / N6，2026-11-14）
 *
 * ⛔ 存在理由：F8 只修了进度页的 404，画像页仍把 403 说成
 *    「可能是网络问题，或这个匿名编号还没被辅导过」并给重试按钮。
 *    同类问题散落在画像 / 报表 / 知识检索 / 进度页，修一处漏一处。
 *    故把「终态错误不得伪装成临时故障」的纪律收敛到这一个函数。
 *
 * ⛔ 铁律：
 *    · 403（权限）/ 404（资源）是**终态** → 不给重试、不说网络问题、
 *      不说「稍后再试」，明确告知「重试不会改变结果」并指向管理员；
 *    · 5xx / 超时 / 网络失败才是**临时故障** → 可重试；
 *    · 失败 Run 的内部 errorCode **绝不原样透出给受众**
 *      （学生看到 BUDGET_EXHAUSTED 不知道是该充值还是系统坏了）；
 *    · 租户级配置 / 预算故障不提供「重新发起」——那是必然失败的死循环。
 */

import { isAccessDenied, type NormalizedError } from './errors'

export interface ErrorGuidance {
  /** 面向用户的失败原因 */
  reason: string
  /** 用户下一步该做什么（含「重试是否有意义」的明确说明） */
  next: string
  /** 是否给出重试按钮；终态错误一律 false */
  retryable: boolean
  retryLabel: string
}

const GENERIC: ErrorGuidance = {
  reason: '没能完成这次操作',
  next: '如果反复出现，请把页面上的运行标识或排查编号提供给管理员。',
  retryable: false,
  retryLabel: '重试',
}

/**
 * HTTP 错误 → 统一指引。
 *
 * ⚠️ 判定顺序不可调换：403/404/401 是**语义终态**，必须优先于
 *    「status >= 500 或 0」这类可重试分支；否则 `code=ACCESS_DENIED`
 *    且 status 被上层改写时会错误地给出重试按钮。
 */
export function errorGuidance(error: NormalizedError | null): ErrorGuidance {
  if (error === null) return GENERIC

  // ① 业务无权限（已登录但缺少动作）
  if (error.status === 403 || isAccessDenied(error)) {
    return {
      reason: error.message || '当前账号没有执行该操作的权限',
      next: '当前账号没有该功能权限。这不是登录失效，也不是临时故障；重新登录、刷新或反复重试都不会改变结果，请联系管理员开通。',
      retryable: false,
      retryLabel: '重试',
    }
  }

  // ② 资源不存在 / 不属于当前账号（后端刻意统一为 404 反探测）
  if (error.status === 404 || error.code === 'NOT_FOUND') {
    return {
      reason: error.message || '资源不存在',
      next: '该资源不存在，或不属于当前账号。刷新、重试都不会让它出现；请核对标识后返回列表。',
      retryable: false,
      retryLabel: '重试',
    }
  }

  // ③ 登录态失效：唯一正确动作是重新登录，不是重试当前请求
  if (error.status === 401 || error.code === 'UNAUTHENTICATED') {
    return {
      reason: error.message || '登录已过期，请重新登录',
      next: '会话已结束，请重新登录后再继续；重试当前请求不会成功。',
      retryable: false,
      retryLabel: '重试',
    }
  }

  // ④ 临时故障：网络 / 超时 / 服务端 5xx —— 重试有意义
  if (error.status === 0 || error.status >= 500 ||
      error.code === 'NETWORK_ERROR' || error.code === 'REQUEST_TIMEOUT') {
    return {
      reason: error.message || '服务暂时不可用',
      next: '这可能是网络抖动或服务临时不可用，稍后重试通常可以恢复。',
      retryable: true,
      retryLabel: '重试',
    }
  }

  // ⑤ 其余（400 / 409 / 422 …）：可修改后重试，但要说明不是权限问题
  return {
    reason: error.message || GENERIC.reason,
    next: '请检查填写内容或刷新页面后再试；若反复出现，请联系管理员。',
    retryable: true,
    retryLabel: '重试',
  }
}

/* ── 失败 Run 的 errorCode 指引（N3）───────────────────────── */

export type RunFailureKind = 'service-config' | 'quota-exhausted' | 'transient' | 'unknown'

export interface RunFailureGuidance {
  kind: RunFailureKind
  /** 面向学生的「失败原因」，绝不含内部错误码 */
  reason: string
  /** 下一步建议（含是否可重新发起） */
  advice: string
  /** 是否允许「重新发起这道题」；租户级配置故障一律 false */
  canRetry: boolean
  /** 是否附上「联系管理员」行动 */
  contactAdmin: boolean
}

/**
 * 租户级服务配置 / 预算故障。
 *
 * ⚠️ 这三类失败的根因都在 F7 的第 3、4 层：
 *    `cap_authz_version` 缺行 → 快照跳过 → CONFIG_UNAVAILABLE；
 *    `budget_account` 未开通 → BUDGET_EXHAUSTED。
 *    学生重试多少次都只会再失败一次。
 */
const SERVICE_CONFIG_CODES: readonly string[] = [
  'CONFIG_UNAVAILABLE',
  'BUDGET_EXHAUSTED',
  'AUTHZ_UNAVAILABLE',
  'NO_ACCOUNT',
  'CONFIG_MISSING',
  'AGENT_CONFIG_UNAVAILABLE',
]

/**
 * 模型额度 / 限流耗尽、重试硬上限、死信收敛 —— 全部是**终态**。
 *
 * ⚠️ 这些码由后端 RunExecutionLoop / PgQueueListener / 恢复扫描器真实写入
 *    run.error_code。它们根因是「额度已耗尽 / 已重试到上限」，重试多少次
 *    都只会再次失败，绝不能落进 TRANSIENT_CODES（那会给出「再辅导一道」
 *    的按钮，让用户陷入死循环）。
 */
const QUOTA_EXHAUSTED_CODES: readonly string[] = [
  'MODEL_RATE_LIMITED',
  'QUOTA_EXHAUSTED',
  'MODEL_PROVIDER_ERROR',
  'MODEL_RETRY_EXHAUSTED',
  'MAX_ATTEMPTS_EXCEEDED',
]

/** 临时故障：换一次请求确实可能成功。 */
const TRANSIENT_CODES: readonly string[] = [
  'MODEL_TIMEOUT',
  'MODEL_ERROR',
  'PROVIDER_ERROR',
  'TIMEOUT',
  'TIMED_OUT',
  'REQUEST_TIMEOUT',
  'NETWORK_ERROR',
  'RATE_LIMITED',
  'RAG_UNAVAILABLE',
  'INTERNAL_ERROR',
  'EMPTY_ASSISTANT_OUTPUT',
  'NO_ASSISTANT_OUTPUT',
]

export function runFailureGuidance(
  errorCode: string | null | undefined,
  runId?: string,
): RunFailureGuidance {
  const code = (errorCode ?? '').trim()
  const ref = runId !== undefined && runId.length > 0 ? `（运行标识 ${runId}）` : ''

  if (code.length === 0) {
    return {
      kind: 'unknown',
      reason: '本次执行未完成',
      advice: `这次分析没有得出结论${ref}。请把运行标识提供给管理员排查；暂不建议对同一道题反复发起。`,
      canRetry: false,
      contactAdmin: true,
    }
  }

  if (SERVICE_CONFIG_CODES.includes(code)) {
    return {
      kind: 'service-config',
      reason: '服务配置尚未就绪',
      advice: `这不是题目内容或服务临时波动造成的，而是租户服务配置未完成${ref}。重新发起只会再次失败；请联系管理员开通后，再重新分析这道题。`,
      canRetry: false,
      contactAdmin: true,
    }
  }

  if (QUOTA_EXHAUSTED_CODES.includes(code)) {
    // ⚠️ 额度/限流耗尽是终态：明确说清「本次未成功」，不给自动重试暗示。
    //    MODEL_RATE_LIMITED / QUOTA_EXHAUSTED 用专属文案；
    //    其余（重试超限 / 死信 / 供应商业务错误）用同源的「已耗尽」文案。
    const isRateLimit = code === 'MODEL_RATE_LIMITED' || code === 'QUOTA_EXHAUSTED'
    return {
      kind: 'quota-exhausted',
      reason: isRateLimit
        ? '模型额度已用尽或调用过于频繁，本次分析未成功；请稍后再试或联系管理员'
        : '模型调用重试次数已达上限，本次分析未成功；请稍后再试或联系管理员',
      advice: `这不是网络抖动或题目内容问题，而是模型额度或调用次数已耗尽${ref}。重试不会改变结果；请联系管理员恢复额度后再发起。`,
      canRetry: false,
      contactAdmin: true,
    }
  }

  if (TRANSIENT_CODES.includes(code)) {
    return {
      kind: 'transient',
      reason: '这次分析临时未能完成',
      advice: `可能是模型或服务临时不可用${ref}。可以用原题重新发起一次；若连续失败请联系管理员。`,
      canRetry: true,
      contactAdmin: false,
    }
  }

  // 未知错误码：不猜含义、不原样透出，附运行标识便于报障，且不给必然失败的重试
  return {
    kind: 'unknown',
    reason: '本次执行未完成',
    advice: `这次分析没有得出结论${ref}。请把运行标识提供给管理员排查；暂不建议对同一道题反复发起。`,
    canRetry: false,
    contactAdmin: true,
  }
}

/** 供 `stateHint` 使用：把内部错误码换成面向学生的可读原因。 */
export function readableFailureReason(errorCode: string | null | undefined): string | null {
  if (errorCode === null || errorCode === undefined || errorCode.trim().length === 0) {
    return null
  }
  return `失败原因：${runFailureGuidance(errorCode).reason}`
}
