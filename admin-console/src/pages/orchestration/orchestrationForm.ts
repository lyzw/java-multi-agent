/**
 * 编排声明表单的**纯逻辑**（多级 Agent v1.1 · 第二十九批 · C29-1 · A6）。
 *
 * <p><b>⚠️ 为什么抽成独立模块</b>：表单逻辑若写在页面组件里，
 * 就只能靠「渲染整个页面 + 模拟输入」来测 —— 既慢又脆。
 * 抽成纯函数后，序列化与校验可以**直接断言**，
 * 而页面只负责「采集输入 → 调纯函数 → 提交」。
 *
 * <p><b>⚠️ 本模块的口径必须与后端 {@code AgentOrchestrationParser} 一致</b>：
 * 后端在**配置期 fail-closed**（非法声明抛异常，绝不「降级成 LEAF 照常发布」——
 * 那会让作者以为多级已生效，实际所有委派都被静默丢弃）。
 * 前端做同样的校验，是为了让作者**当场**看到错误，而不是提交后才被门禁拒。
 * 但**前端校验只是体验优化，不是防线** —— 服务端才是权威。
 */

/** 编排模式（与后端 `OrchestrationMode` 同名集合）。 */
export const ORCHESTRATION_MODES = ['LEAF', 'ROUTER', 'SEQUENTIAL'] as const
export type OrchestrationMode = (typeof ORCHESTRATION_MODES)[number]

/** 失败策略（与后端 `DelegationFailurePolicy` 同名集合）。 */
export const FAILURE_POLICIES = ['FAIL_FAST', 'CONTINUE', 'COMPENSATE'] as const
export type FailurePolicy = (typeof FAILURE_POLICIES)[number]

/** 子 Agent 引用的一行（固定版本，`id@version` 语义拆成两列便于填）。 */
export interface ChildRefFormValue {
  /** 子 Agent 标识（UUID） */
  id: string
  /** 子 Agent 的固定 release 版本（必须为正整数） */
  version: number | null
  /** 该子调用是否必需 */
  required: boolean
}

/** 编排声明表单的取值。 */
export interface OrchestrationFormValues {
  mode: OrchestrationMode
  childAgentRefs: ChildRefFormValue[]
  /** 固定版本的路由策略引用（`id@version`） */
  routingPolicyRef?: string | null
  /** 固定版本的输出契约引用（`id@version`） */
  resultContractRef?: string | null
  /**
   * 最大委派深度（含根 Agent 计为 1）。
   *
   * ⚠️ **非 LEAF 必填**（评审前置决策 2）：平台**不提供默认值** ——
   * 缺一个上限就等于「这一跳没有上限」。LEAF 不参与委派 ⇒ 不适用，可留空。
   */
  maxDelegationDepth?: number | null
  /**
   * 单个父 Agent 允许发起的最大子调用次数。
   *
   * ⚠️ **非 LEAF 必填**（评审前置决策 2）。下限是 **0**（不是 1）——
   * 「允许发起 0 次子调用」是一个**真实且合法**的上限，与后端一致。
   */
  maxChildInvocations?: number | null
  /**
   * 本级委派链路的总超时预算（毫秒）。
   *
   * ⚠️ **非 LEAF 必填**（评审前置决策 2）。LEAF 不参与委派 ⇒ 不适用，可留空。
   */
  timeoutMs?: number | null
  failurePolicy?: FailurePolicy | null
}

/** 校验结果：`ok=false` 时 `error` 给出**可直接展示给人看**的原因。 */
export type ValidationResult = { ok: true } | { ok: false; error: string }

/**
 * 三个委派限制的**取值规则**（与后端 `AgentOrchestration` 构造器逐条对齐）。
 *
 * ⚠️ **下限为什么各不相同**：
 * - `maxDelegationDepth` 下限 1 —— 深度为 0 意味着「连自己这一层都不算」，
 *   而口径是「含根 Agent 计为 1」；
 * - `maxChildInvocations` 下限 **0** —— 「允许发起 0 次子调用」是一个**真实的
 *   上限**（这个父 Agent 声明了拓扑但一次也不许调用），后端与 schema 都接受 0；
 *   ⚠️ 此前本模块用「正整数」一刀切，把 0 判成非法 —— 与后端、与
 *   `AgentDetailPage` 里 `InputNumber min={0}` 的控件口径**互相打架**。
 * - `timeoutMs` 下限 1 —— 超时为 0 意味着「一提交就超时」。
 */
const LIMIT_FIELDS: ReadonlyArray<readonly [field: string, min: number]> = [
  ['maxDelegationDepth', 1],
  ['maxChildInvocations', 0],
  ['timeoutMs', 1],
]

/**
 * 引用分隔符（`id@version`），与后端一致。
 */
const REF_SEPARATOR = '@'

/**
 * 禁止出现的漂移别名（与后端一致）。
 */
const DRIFTING_ALIAS = 'latest'

/**
 * 校验一个**固定版本引用**（`id@version`）。
 *
 * <p>⚠️ **版本必须固定**（MA-T02）：裸 id 或 `latest` 一律拒绝 ——
 * 漂移别名意味着「本次委派用哪一版子 Agent」在发布时无法确定，
 * 闭包指纹也就无从计算（在途 Run 的失效判定会失准）。
 */
export function validatePinnedRef(
  value: string | null | undefined,
  fieldName: string,
): ValidationResult {
  const raw = (value ?? '').trim()
  if (raw === '') {
    return { ok: true }
  }
  const at = raw.indexOf(REF_SEPARATOR)
  if (at <= 0 || at === raw.length - 1) {
    return {
      ok: false,
      error: `${fieldName} 必须是「id@version」形式（当前：${raw}）`,
    }
  }
  const id = raw.slice(0, at)
  const version = raw.slice(at + 1)
  if (id.toLowerCase() === DRIFTING_ALIAS || version.toLowerCase() === DRIFTING_ALIAS) {
    return {
      ok: false,
      error: `${fieldName} 不得使用漂移别名 latest（必须固定版本）`,
    }
  }
  if (!/^\d+$/.test(version) || Number(version) <= 0) {
    return {
      ok: false,
      error: `${fieldName} 的版本号必须是正整数（当前：${version}）`,
    }
  }
  return { ok: true }
}

/**
 * 校验编排声明表单取值。
 *
 * <p>⚠️ 校验的是**结构性**问题（mode 必填、LEAF 不得有子引用、
 * ROUTER 必须有路由策略引用、版本必须固定正整数、
 * **非 LEAF 必须显式声明三个委派限制**）。
 * 这些都是后端会拒的形状 —— 前端先拒，只是让作者当场看到。
 */
export function validateOrchestration(
  values: OrchestrationFormValues,
): ValidationResult {
  if (!ORCHESTRATION_MODES.includes(values.mode)) {
    return { ok: false, error: `编排模式必须是 ${ORCHESTRATION_MODES.join(' / ')}` }
  }

  const children = values.childAgentRefs ?? []
  if (values.mode === 'LEAF' && children.length > 0) {
    return {
      ok: false,
      error: 'LEAF 模式不得声明子 Agent 引用（声明了即为配置错误）',
    }
  }
  for (const child of children) {
    if (!(child.id ?? '').trim()) {
      return { ok: false, error: '子 Agent 引用必须有 id' }
    }
    if (!child.version || child.version <= 0) {
      return {
        ok: false,
        error: `子 Agent 引用 ${child.id} 的版本必须是正整数（必须固定版本）`,
      }
    }
  }

  const routing = validatePinnedRef(values.routingPolicyRef, 'routingPolicyRef')
  if (!routing.ok) {
    return routing
  }
  if (values.mode === 'ROUTER' && !(values.routingPolicyRef ?? '').trim()) {
    return {
      ok: false,
      error: 'ROUTER 模式必须声明固定版本的路由策略引用（routingPolicyRef）',
    }
  }

  const contract = validatePinnedRef(values.resultContractRef, 'resultContractRef')
  if (!contract.ok) {
    return contract
  }

  const limitValues: Record<string, number | null | undefined> = {
    maxDelegationDepth: values.maxDelegationDepth,
    maxChildInvocations: values.maxChildInvocations,
    timeoutMs: values.timeoutMs,
  }

  // ⚠️ **非 LEAF 必须显式声明三个委派限制**（评审前置决策 2 · 2026-10-25）：
  //    平台**不提供默认值** —— 原先那三个数字（深度 2 / 次数 8 / 超时 60s）
  //    从未经容量与业务风险评估，只是示例值；一旦以「默认值」的形式存在，
  //    就会被配置方**默默继承**，然后在没人评审的情况下成为生产口径。
  //    现在缺失即**拒绝**（fail-closed），与后端 `AgentOrchestration`
  //    构造器的判据逐条对齐。
  //    ⚠️ **LEAF 不参与委派** ⇒ 这三个限制对它**不适用** ⇒ 留空合法。
  if (values.mode !== 'LEAF') {
    for (const [field] of LIMIT_FIELDS) {
      const value = limitValues[field]
      if (value === null || value === undefined) {
        return {
          ok: false,
          error: `${field} 必填：非 LEAF 模式必须显式声明委派限制（平台不提供默认值）`,
        }
      }
    }
  }

  for (const [field, min] of LIMIT_FIELDS) {
    const value = limitValues[field]
    if (value === null || value === undefined) {
      continue
    }
    if (!Number.isInteger(value) || value < min) {
      return {
        ok: false,
        error: min === 0 ? `${field} 必须是大于等于 0 的整数` : `${field} 必须是正整数`,
      }
    }
  }

  if (
    values.failurePolicy &&
    !FAILURE_POLICIES.includes(values.failurePolicy)
  ) {
    return {
      ok: false,
      error: `失败策略必须是 ${FAILURE_POLICIES.join(' / ')}`,
    }
  }

  return { ok: true }
}

/**
 * 把编排声明表单取值**序列化成 JSON 文本**（提交给服务端）。
 *
 * <p>⚠️ **数值字段留空即不写入** —— 但理由已经变了：
 * 原先是因为「服务端会用自己的默认值兜底」；评审前置决策 2 落地后
 * **平台不再提供默认值**，所以留空只有一种合法含义：**LEAF 不适用**。
 * 非 LEAF 留空会被 {@link validateOrchestration} **当场拒掉**
 * （服务端 `AgentOrchestration` 构造器同样 fail-closed），
 * 根本走不到这里。
 * ⚠️ 前端**不预填**默认值 —— 预填等于替评审先定了一套口径，
 * 而且会成为「第二个默认值来源」（前后端各有一份，迟早漂移）。
 */
export function buildOrchestrationJson(values: OrchestrationFormValues): string {
  const root: Record<string, unknown> = { mode: values.mode }

  const children = (values.childAgentRefs ?? []).filter((c) => (c.id ?? '').trim())
  if (children.length > 0) {
    root.childAgentRefs = children.map((c) => ({
      id: c.id.trim(),
      version: c.version,
      required: c.required !== false,
    }))
  }

  const routing = (values.routingPolicyRef ?? '').trim()
  if (routing !== '') {
    root.routingPolicyRef = routing
  }
  const contract = (values.resultContractRef ?? '').trim()
  if (contract !== '') {
    root.resultContractRef = contract
  }

  if (values.maxDelegationDepth !== null && values.maxDelegationDepth !== undefined) {
    root.maxDelegationDepth = values.maxDelegationDepth
  }
  if (values.maxChildInvocations !== null && values.maxChildInvocations !== undefined) {
    root.maxChildInvocations = values.maxChildInvocations
  }
  if (values.timeoutMs !== null && values.timeoutMs !== undefined) {
    root.timeoutMs = values.timeoutMs
  }
  if (values.failurePolicy) {
    root.failurePolicy = values.failurePolicy
  }

  return JSON.stringify(root)
}

/**
 * 解析**已有的**编排声明 JSON（回显到表单）。
 *
 * <p>⚠️ 解析失败**不得抛异常**：后端可能存着本表单尚不支持的新形态，
 * 解析不了时应当**保留原文**让用户看到，而不是把页面搞崩。
 */
export function parseOrchestrationJson(
  raw: string | null | undefined,
): { values: OrchestrationFormValues; raw: string } {
  const text = (raw ?? '').trim()
  const empty: OrchestrationFormValues = {
    mode: 'LEAF',
    childAgentRefs: [],
    routingPolicyRef: null,
    resultContractRef: null,
    maxDelegationDepth: null,
    maxChildInvocations: null,
    timeoutMs: null,
    failurePolicy: null,
  }
  if (text === '') {
    return { values: empty, raw: text }
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return { values: empty, raw: text }
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { values: empty, raw: text }
  }
  const obj = parsed as Record<string, unknown>
  const mode = ORCHESTRATION_MODES.includes(obj.mode as OrchestrationMode)
    ? (obj.mode as OrchestrationMode)
    : 'LEAF'
  const children = Array.isArray(obj.childAgentRefs)
    ? obj.childAgentRefs
        .filter((c): c is Record<string, unknown> => c !== null && typeof c === 'object')
        .map((c) => ({
          id: String(c.id ?? ''),
          version: typeof c.version === 'number' ? c.version : null,
          required: c.required !== false,
        }))
    : []
  const numOrNull = (v: unknown): number | null =>
    typeof v === 'number' && Number.isFinite(v) ? v : null

  return {
    values: {
      mode,
      childAgentRefs: children,
      routingPolicyRef:
        typeof obj.routingPolicyRef === 'string' ? obj.routingPolicyRef : null,
      resultContractRef:
        typeof obj.resultContractRef === 'string' ? obj.resultContractRef : null,
      maxDelegationDepth: numOrNull(obj.maxDelegationDepth),
      maxChildInvocations: numOrNull(obj.maxChildInvocations),
      timeoutMs: numOrNull(obj.timeoutMs),
      failurePolicy: FAILURE_POLICIES.includes(obj.failurePolicy as FailurePolicy)
        ? (obj.failurePolicy as FailurePolicy)
        : null,
    },
    raw: text,
  }
}
