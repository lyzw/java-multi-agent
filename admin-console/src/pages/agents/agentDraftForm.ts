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
 *   <li>草稿字段集 = {@code cap_agent_def} 的全部列（关系字段）+ 编排声明的结构化拆解
 *       + 提示词声明 / 模型绑定 / 生成参数三个结构化字段；</li>
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
 * 名称 / 描述 / 角色 / 启用技能 / 技能包 / 能力标签 / 配置覆盖 / 编排声明
 * / 提示词声明 / 模型绑定 / 生成参数。
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
  /**
   * 提示词声明（JSON 原文）—— ⚠️ **V1 只支持 `inlineInstructions`**。
   *
   * <p>⚠️ 形态是 JSON 文本而非结构化字段：与后端 `AgentRequest.instructions`
   * 一致，且**不得**由前端重新序列化（重写空白与键序 = 每次发布都说「你改过」）。
   */
  instructions?: string
  /**
   * 绑定的模型引用（目录模型名或目录 id）。
   *
   * <p>⚠️ 留空 = 未绑定模型 ⇒ 运行期**拒绝执行**该 Agent 的 Run。
   */
  modelRef?: string
  /**
   * 生成参数（JSON 原文）：`temperature` / `topP` / `maxOutputTokens`。
   */
  generationParams?: string
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
 * 提示词声明的**示例初值**（JSON 原文）。
 *
 * <p>⚠️ 给示例而不是空串：提示词是本轮新结构化字段里**最容易填错形态**的一个
 * —— 作者很容易直接把提示词正文写进去（那是裸文本，不是 JSON 对象）。
 * 示例把形态摆出来，作者照着改即可。
 *
 * <p>⚠️ **只含 `inlineInstructions`**：V1 不支持引用式提示词库，
 * 示例里若出现 `systemPromptRef` 就等于教作者走一条必然被拒的路。
 */
export const DEFAULT_INSTRUCTIONS =
  '{"inlineInstructions":"你是初中数学辅导助手。先读取学生档案与课程安排，再给出个性化练习建议。"}'

/**
 * 提示词声明里**V1 未实现**的键（契约里的引用式提示词库）。
 *
 * <p>⚠️ 后端 V1 未实现引用式提示词库，出现该键时运行期 **fail-closed 拒绝执行**
 * （而不是静默忽略）。管理台必须在提交前就拒 ——
 * 「提交成功、跑起来才发现提示词没生效」是最坏的一类假成功。
 */
const UNSUPPORTED_PROMPT_KEY = 'systemPromptRef'

/**
 * 解析一段 JSON 文本为**对象**。
 *
 * <p>⚠️ 数组 / 字符串 / 数字 / null 全部**不是对象** —— 必须拒。
 * 宽松接受「能 parse 就算过」会让非法声明一路走到运行期才炸。
 *
 * @returns 解析结果；失败时 `error` 是可直接展示给人看的原因
 */
function parseJsonObject(
  raw: string,
): { ok: true; value: Record<string, unknown> } | { ok: false; error: string } {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { ok: false, error: '不是合法 JSON' }
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ok: false, error: '必须是 JSON 对象' }
  }
  return { ok: true, value: parsed as Record<string, unknown> }
}

/**
 * 校验并归一化**提示词声明**（`instructions`）。
 *
 * <p>⚠️ 口径与后端运行期（`AgentModelConfigAssembler.systemPrompt`）一致：
 * <ul>
 *   <li>非空时必须是**合法 JSON 对象**；</li>
 *   <li>`inlineInstructions` 若存在，必须是**非空字符串**；</li>
 *   <li>**出现 `systemPromptRef` 一律当场拒** —— V1 只支持内联提示词
 *       `inlineInstructions`，后端会 fail-closed 拒绝执行该 Agent。</li>
 * </ul>
 *
 * @param raw 表单里的原始文本
 */
function normalizeInstructions(
  raw: string | undefined,
): { ok: true; value: string } | { ok: false; error: string } {
  const text = (raw ?? '').trim()
  // ⚠️ 空串 = 未声明提示词（合法）：后端把 '{}' 当「没声明」，
  //    Agent 照常跑（它可能本来就不需要特殊指令）。
  if (text === '') return { ok: true, value: '{}' }

  const parsed = parseJsonObject(text)
  if (!parsed.ok) {
    return {
      ok: false,
      error: `提示词声明（instructions）${parsed.error}（V1 只支持 {"inlineInstructions":"..."} 形态）`,
    }
  }

  if (Object.prototype.hasOwnProperty.call(parsed.value, UNSUPPORTED_PROMPT_KEY)) {
    // ⚠️ **fail-closed**：不静默忽略。忽略会让作者以为「提示词已生效」，
    //    而运行期实际用的是另一段（甚至没有）文本。
    return {
      ok: false,
      error:
        `提示词声明（instructions）含 ${UNSUPPORTED_PROMPT_KEY}：V1 只支持内联提示词 inlineInstructions，` +
        '引用式提示词库尚未实现，后端会拒绝执行该 Agent —— 请改写为 inlineInstructions',
    }
  }

  const inline = parsed.value.inlineInstructions
  if (inline !== undefined) {
    if (typeof inline !== 'string' || inline.trim() === '') {
      return {
        ok: false,
        error: '提示词声明（instructions）的 inlineInstructions 必须是非空字符串',
      }
    }
  }

  // ⚠️ **原样回传**（不重新序列化）：重写空白与键序 = 每次发布都说「你改过」。
  return { ok: true, value: text }
}

/**
 * 校验并归一化**生成参数**（`generationParams`）。
 *
 * <p>⚠️ 口径与后端契约一致：`temperature` ∈ [0,2]、`topP` ∈ (0,1]、
 * `maxOutputTokens` ∈ 正整数。
 *
 * <p>⚠️ **只在「键存在」时校验**：缺省 = 走运行期默认值，
 * 平台**不提供**默认值 —— 所以作者不填是有意义的（不是「忘了填」）。
 *
 * @param raw 表单里的原始文本
 */
function normalizeGenerationParams(
  raw: string | undefined,
): { ok: true; value: string } | { ok: false; error: string } {
  const text = (raw ?? '').trim()
  // ⚠️ 空串 = 未声明生成参数（合法）：后端把 '{}' 当「走运行期默认值」。
  if (text === '') return { ok: true, value: '{}' }

  const parsed = parseJsonObject(text)
  if (!parsed.ok) {
    return { ok: false, error: `生成参数（generationParams）${parsed.error}` }
  }

  const temperature = parsed.value.temperature
  if (temperature !== undefined) {
    if (typeof temperature !== 'number' || !Number.isFinite(temperature) || temperature < 0 || temperature > 2) {
      return { ok: false, error: '生成参数（generationParams）的 temperature 必须是 0 ~ 2 之间的数值' }
    }
  }

  const topP = parsed.value.topP
  if (topP !== undefined) {
    // ⚠️ topP **不含 0**（0 会让采样退化成「只取最高分的那一个」，
    //    近乎确定性输出 —— 契约把它排除在合法区间外）。
    if (typeof topP !== 'number' || !Number.isFinite(topP) || topP <= 0 || topP > 1) {
      return { ok: false, error: '生成参数（generationParams）的 topP 必须是 0 ~ 1 之间（不含 0）的数值' }
    }
  }

  const maxOutputTokens = parsed.value.maxOutputTokens
  if (maxOutputTokens !== undefined) {
    if (typeof maxOutputTokens !== 'number' || !Number.isInteger(maxOutputTokens) || maxOutputTokens <= 0) {
      return { ok: false, error: '生成参数（generationParams）的 maxOutputTokens 必须是正整数' }
    }
  }

  // ⚠️ **原样回传**（不重新序列化）：同上，键序与空白都是内容的一部分。
  return { ok: true, value: text }
}

/**
 * 归一化**模型引用**（`modelRef`）。
 *
 * <p>⚠️ **留空是合法的**（= 未绑定模型）—— 平台**不提供**默认模型，
 * 所以「不绑定」不是「用默认」，而是「运行期会拒绝执行该 Agent 的 Run」。
 * 故这里**不做**任何拦截，只做去空白（该风险由表单说明文案承担）。
 *
 * @param raw 表单里的原始文本
 */
function normalizeModelRef(raw: string | undefined): string | undefined {
  const text = (raw ?? '').trim()
  return text === '' ? undefined : text
}

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
    // ⚠️ instructions 给**一个可改的示例**而不是空串：
    //    空串作者看不出「这个字段该长什么样」，而提示词是本轮新结构化字段里
    //    最容易填错形态的一个（写成裸文本而不是 JSON 对象）。
    instructions: DEFAULT_INSTRUCTIONS,
    // ⚠️ modelRef 缺省 = 未绑定模型（**不是**「用默认模型」——
    //    平台不提供默认模型，运行期会拒绝执行该 Agent 的 Run）。
    modelRef: '',
    // ⚠️ generationParams 缺省 '{}' = 走运行期默认值（与后端「{} = 默认」同口径）。
    generationParams: '{}',
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
    // ⚠️ 提示词 / 模型绑定 / 生成参数**回填原文**（不重新序列化）：
    //    老版本后端不回显这三列 ⇒ 缺省按「未声明」呈现，
    //    **不得**由前端臆造（臆造出来的是一段平台没存过的文本）。
    instructions: def.instructions ?? '',
    modelRef: def.modelRef ?? '',
    generationParams: def.generationParams ?? '{}',
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

  // ⚠️ 本地校验**不是防线**（服务端才是权威），但能让作者**当场**看到错误，
  //    而不是提交后才被门禁拒。
  const instructionsCheck = normalizeInstructions(values.instructions)
  if (!instructionsCheck.ok) {
    return { ok: false, error: instructionsCheck.error }
  }

  const generationParamsCheck = normalizeGenerationParams(values.generationParams)
  if (!generationParamsCheck.ok) {
    return { ok: false, error: generationParamsCheck.error }
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
      // ⚠️ **instructions / generationParams 恒为非空串**：
      //    后端 `AgentSpec` 构造器直接拒空（"instructions 不能为空"），
      //    空串送过去 = 400，而不是「不填就不带」。
      instructions: instructionsCheck.value,
      // ⚠️ modelRef 缺省 = **不带该键**（= 未绑定模型），
      //    而不是送空串（空串在后端是「绑了一个空模型」）。
      modelRef: normalizeModelRef(values.modelRef),
      generationParams: generationParamsCheck.value,
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
