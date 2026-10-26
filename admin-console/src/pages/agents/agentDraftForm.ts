/**
 * Agent 草稿表单的**纯逻辑**（第三十五批 · 管理台 Agent 全字段增改查）。
 *
 * <p><b>⚠️ 为什么抽成独立模块</b>：草稿表单要同时服务「新增」与「修改」两个场景，
 * 若把取值拼装写进页面组件，就得靠「渲染整个页面 + 模拟输入」来测 —— 既慢又脆。
 * 抽成纯函数后，<b>spec 构造</b>与<b>回填</b>可以**直接断言**，
 * 而页面只负责「采集输入 → 调纯函数 → 提交」。
 *
 * <p><b>⚠️ 本模块的口径必须与后端一致</b>：
 * <ul>
 *   <li>草稿字段集 = {@code cap_agent_def} 的全部列（关系字段）+ 编排声明的结构化拆解；</li>
 *   <li>版本号与内容指纹**由服务端决定**，客户端不得指定（否则等于让客户端
 *       指定「内容没变」这个证明）—— 故本模块产出的 spec 里没有这两个字段；</li>
 *   <li>编排声明的校验口径与后端 {@code AgentOrchestrationParser} 一致
 *       （复用 {@link validateOrchestration}），否则前端放行、后端拒，
 *       作者要提交后才看到错误。</li>
 * </ul>
 */

import type { AgentDefinition, PublishAgentSpec } from '@/api/types'
import { isValidJson } from '../agentDefinition'
import {
  buildOrchestrationJson,
  parseOrchestrationJsonStrict,
  validateOrchestration,
  type ChildRefFormValue,
  type FailurePolicy,
  type OrchestrationFormValues,
  type OrchestrationMode,
} from '../orchestration/orchestrationForm'

/**
 * 「新建 Agent」的保留路由段。
 *
 * <p>⚠️ 用保留字而不是另开一条路由：登记与修改**共用同一张草稿表单**，
 * 差别只在「对象是否已存在」——分两条路由就会有两份表单，迟早漂移。
 * （与平台级对象 {@code NEW_OBJECT_SEGMENT} 同一口径。）
 */
export const NEW_AGENT_SEGMENT = 'new'

/**
 * Agent 草稿表单的取值。
 *
 * <p>⚠️ **覆盖 `cap_agent_def` 的全部字段**（第三十五批的核心诉求）：
 * 名称 / 描述 / 角色 / 启用技能 / 技能包 / 能力标签 / 配置覆盖 / 编排声明。
 * 少一个字段，就意味着「新增时看不到、改时改不了、详情里查不到」。
 */
export interface AgentDraftFormValues {
  name: string
  description?: string
  /** 角色（声明可适用的主体角色范围，**非授权授予**） */
  roles?: string[]
  /** 启用的 Skill 标识（V1 唯一启用途径） */
  enabledSkills?: string[]
  /** 启用的 Skill 包标识 */
  skillBundles?: string[]
  /** 能力标签 */
  capabilityTags?: string[]
  /** 配置覆盖（收紧覆盖，JSON 原文）—— ⚠️ **不是**整份 definition */
  overrides?: string
  /** 编排模式（LEAF / ROUTER / SEQUENTIAL） */
  orchestrationMode: OrchestrationMode
  /** 声明的子 Agent 引用（固定版本） */
  orchestrationChildren: ChildRefFormValue[]
  /** 固定版本的路由策略引用（`id@version`） */
  routingPolicyRef?: string
  /** 固定版本的输出契约引用（`id@version`） */
  resultContractRef?: string
  /** 最大委派深度（非 LEAF 必填） */
  maxDelegationDepth?: number
  /** 最大子调用次数（非 LEAF 必填） */
  maxChildInvocations?: number
  /** 委派超时预算（毫秒，非 LEAF 必填） */
  timeoutMs?: number
  /** 失败策略 */
  failurePolicy?: FailurePolicy
}

/** 草稿构造的结果：`ok=false` 时 `error` 可直接展示给人看。 */
export type AgentDraftBuildResult =
  | { ok: true; spec: PublishAgentSpec }
  | { ok: false; error: string }

/**
 * **已归一化**的草稿内容（不含 `agentId`）。
 *
 * <p>⚠️ 之所以把「校验 + 归一化」与「绑定 agentId」拆成两步：
 * 登记模式下 `agentId` 是**创建之后**才有的，而校验必须发生在**创建之前** ——
 * 否则一次编排声明非法的提交会先建出一个 Agent 行，再发现根本发不出去，
 * 留下一个既没有多级定义、又不是用户想要的孤儿行。
 */
export type NormalizedAgentDraft = Omit<PublishAgentSpec, 'agentId'>

/**
 * 空白草稿（新建时的初值）。
 *
 * <p>⚠️ **`overrides` 默认 `'{}'`**（= 无收紧覆盖），与提交逻辑
 * {@code values.overrides?.trim() || '{}'} 保持一致 —— 两处口径不同就会
 * 「界面上显示空、提交出去是空对象」这种对不上的现象。
 */
export function emptyAgentDraft(): AgentDraftFormValues {
  return {
    name: '',
    description: '',
    roles: [],
    enabledSkills: [],
    skillBundles: [],
    capabilityTags: [],
    overrides: '{}',
    orchestrationMode: 'LEAF',
    orchestrationChildren: [],
    routingPolicyRef: undefined,
    resultContractRef: undefined,
    maxDelegationDepth: undefined,
    maxChildInvocations: undefined,
    timeoutMs: undefined,
    failurePolicy: undefined,
  }
}

/**
 * 权威定义（`cap_agent_def`）→ 草稿表单的**回填**。
 *
 * <p>⚠️ **关系字段始终回填**（角色 / 启用技能 / 技能包 / 能力标签 / 配置覆盖），
 * 只有**编排声明**在解析失败时回填不了 —— 因为回填失败若静默当空，
 * 保存时就会把一份合法的编排声明**覆盖成 LEAF**（= 取消全部委派）。
 *
 * <p>⚠️ 回填的 {@code overrides} 取自权威定义的 {@code overrides} 字段，
 * **不是**旧体系 {@code latestDefinition} 全文 —— 语义完全错位。
 */
export interface AgentDraftBackfill {
  /** 回填到表单的字段（关系字段一定在内） */
  values: Partial<AgentDraftFormValues>
  /** 编排声明解析失败的原因（null = 正常） */
  orchestrationError: string | null
}

/**
 * 把权威定义回填成草稿表单的取值。
 *
 * @param def 权威定义（`GET /api/cap/agents/{id}/definition`）
 */
export function definitionToDraft(def: AgentDefinition): AgentDraftBackfill {
  const values: Partial<AgentDraftFormValues> = {
    name: def.name ?? '',
    description: def.description ?? '',
    roles: [...(def.roles ?? [])],
    enabledSkills: [...(def.enabledSkills ?? [])],
    skillBundles: [...(def.skillBundles ?? [])],
    capabilityTags: [...(def.capabilityTags ?? [])],
    // ⚠️ overrides 是「收紧覆盖」，**不是**整份 definition
    overrides: def.overrides ?? '{}',
  }

  // ⚠️ 编排声明解析失败**必须显式报错**：静默当空 = 保存时把一份合法的
  //    编排声明覆盖成 LEAF（= 取消全部委派），且用户看不出来。
  const parsed = parseOrchestrationJsonStrict(def.orchestration)
  if (!parsed.ok) {
    return { values, orchestrationError: parsed.error }
  }

  return {
    values: {
      ...values,
      orchestrationMode: parsed.values.mode,
      orchestrationChildren: parsed.values.childAgentRefs,
      routingPolicyRef: parsed.values.routingPolicyRef ?? undefined,
      resultContractRef: parsed.values.resultContractRef ?? undefined,
      maxDelegationDepth: parsed.values.maxDelegationDepth ?? undefined,
      maxChildInvocations: parsed.values.maxChildInvocations ?? undefined,
      timeoutMs: parsed.values.timeoutMs ?? undefined,
      failurePolicy: parsed.values.failurePolicy ?? undefined,
    },
    orchestrationError: null,
  }
}

/**
 * 校验并归一化草稿表单取值（**不含 `agentId`**）。
 *
 * <p>⚠️ 校验与归一化必须**在创建 Agent 行之前**完成（见 {@link NormalizedAgentDraft}）。
 *
 * @param values 表单取值
 */
export function normalizeAgentDraft(
  values: AgentDraftFormValues,
): { ok: true; draft: NormalizedAgentDraft } | { ok: false; error: string } {
  const name = (values.name ?? '').trim()
  if (!name) {
    return { ok: false, error: '名称不能为空' }
  }

  // ⚠️ overrides 是 JSON 原文：空串按「无收紧覆盖」处理，非空串必须合法。
  const overrides = values.overrides?.trim() || '{}'
  if (!isValidJson(overrides)) {
    return { ok: false, error: '配置覆盖（overrides）不是合法 JSON' }
  }

  const orchestrationValues: OrchestrationFormValues = {
    mode: values.orchestrationMode ?? 'LEAF',
    childAgentRefs: values.orchestrationChildren ?? [],
    routingPolicyRef: values.routingPolicyRef ?? null,
    resultContractRef: values.resultContractRef ?? null,
    maxDelegationDepth: values.maxDelegationDepth ?? null,
    maxChildInvocations: values.maxChildInvocations ?? null,
    timeoutMs: values.timeoutMs ?? null,
    failurePolicy: values.failurePolicy ?? null,
  }
  // ⚠️ 本地校验**不是防线**（服务端 `AgentOrchestrationParser` 才是权威），
  //    但能让作者**当场**看到错误，而不是提交后才被门禁拒。
  const orchestrationCheck = validateOrchestration(orchestrationValues)
  if (!orchestrationCheck.ok) {
    return { ok: false, error: `编排声明非法：${orchestrationCheck.error}` }
  }

  return {
    ok: true,
    draft: {
      name,
      description: values.description?.trim() || null,
      roles: values.roles ?? [],
      enabledSkills: values.enabledSkills ?? [],
      skillBundles: values.skillBundles ?? [],
      capabilityTags: values.capabilityTags ?? [],
      overrides,
      orchestration: buildOrchestrationJson(orchestrationValues),
    },
  }
}

/**
 * 草稿表单取值 → 发布草稿（`PublishAgentSpec`）。
 *
 * <p>⚠️ **不含版本号与内容指纹** —— 二者由服务端决定。
 * 客户端若能指定版本号或内容指纹，就等于能指定「内容没变」这个证明。
 *
 * @param agentId 目标 Agent 标识（登记模式下为**刚创建**的标识）
 * @param values  表单取值
 */
export function buildAgentSpec(
  agentId: string,
  values: AgentDraftFormValues,
): AgentDraftBuildResult {
  const normalized = normalizeAgentDraft(values)
  if (!normalized.ok) {
    return normalized
  }
  return { ok: true, spec: { agentId, ...normalized.draft } }
}
