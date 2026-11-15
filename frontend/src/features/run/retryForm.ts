/**
 * 「重新发起这道题」的原题回填契约（E2E 二次评估 N4，2026-11-14）
 *
 * ⚠️ 缺陷：按钮写「重新发起**这道题**」，但跳转 `/runs/new` 不带任何内容，
 *    用户要重填 4 个必填字段（含长题干、解题过程、正确答案）——名实不符。
 *
 * ⛔ 合规红线 S-4「受控数据不落 localStorage」：
 *    这里用 **react-router 的 location.state**（内存态、刷新即失效），
 *    刻意**不写** sessionStorage / localStorage。
 *    `run.idempotency` 虽已用 sessionStorage，但那是幂等键（不含题干），
 *    两种数据敏感级别不同，不能顺手沿用。
 *
 * ⚠️ 刷新进度页后 state 会丢失 —— 此时调用方把 CTA 文案改为
 *    「再辅导一道」而不是假装还能带回原题（诚实边界）。
 */

/** 发起辅导表单状态（单一真相源：NewRunPage 与重试回填共用）。 */
export interface FormState {
  studentId: string
  subject: string
  grade: string
  questionText: string
  studentSolution: string
  correctAnswer: string
}

/** 白名单字段：任何其它键一律丢弃，不进入表单 */
const FIELDS: readonly (keyof FormState)[] = [
  'studentId',
  'subject',
  'grade',
  'questionText',
  'studentSolution',
  'correctAnswer',
]

export interface RetryState {
  retryForm?: FormState
  agentId?: string
}

/** 从 location.state 构造重试态；调用方在提交成功后调用。 */
export function makeRetryState(form: FormState, agentId: string): RetryState {
  return { retryForm: { ...form }, agentId }
}

/**
 * 读取 location.state —— **严格白名单校验**。
 *
 * ⛔ 不能把任意 state 对象直接塞进表单：location.state 可被手工构造，
 *    任意键混入会污染提交 payload。所有字段必须是 string，
 *    缺一个就整份丢弃（返回空态，降级为「再辅导一道」）。
 */
export function readRetryState(state: unknown): RetryState {
  if (typeof state !== 'object' || state === null) return {}
  const record = state as Record<string, unknown>
  const raw = record['retryForm']
  if (typeof raw !== 'object' || raw === null) return {}
  const candidate = raw as Record<string, unknown>
  const form = {} as FormState
  for (const field of FIELDS) {
    const value = candidate[field]
    if (typeof value !== 'string') return {}
    form[field] = value
  }
  const agentId = record['agentId']
  return typeof agentId === 'string' && agentId.length > 0
    ? { retryForm: form, agentId }
    : { retryForm: form }
}
