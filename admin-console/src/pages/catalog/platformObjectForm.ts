/**
 * 平台级能力对象（Tool / Skill / MCP Server）表单的**纯逻辑**（第三十四批 · C34-3）。
 *
 * <p><b>⚠️ 为什么抽成独立模块</b>：这些规则里有几处「归一化一下就错」的地方
 * （{@code null} 与 {@code []} 语义不同、Tool 的 {@code kind} 与 MCP 绑定字段成对互斥、
 * 发布集合必须构成自洽闭包）。写在页面组件里就只能靠「渲染整页 + 模拟输入」来测，
 * 既慢又脆；抽成纯函数后可以直接**断言语义**，而不必断言文案。
 *
 * <p><b>⚠️ 本模块只做「表单 → 请求体」的形状与一致性检查</b>，
 * 不替代服务端的门禁 —— 后端 {@code ConfigValidationGate} 才是权威。
 * 前端先拒，只是让作者**当场**看到错误，而不是填完理由、走到审批才被拒。
 */

import type {
  McpCatalogItem,
  PlatformMcpServerSpec,
  PlatformMcpTrustTier,
} from '@/api/types'

/** 校验/构造的结果（`ok=false` 时 `error` 是可直接展示给人看的原因）。 */
export type SpecResult<T> = { ok: true; spec: T } | { ok: false; error: string }

/* ── allowlist 三态（null ≠ []） ── */

/**
 * allowlist 的三态。
 *
 * <ul>
 *   <li>{@code UNSET} = 未配置（取该 Server 允许集**全集**）→ 序列化为 {@code null}；</li>
 *   <li>{@code EMPTY} = 显式空（**不允许任何操作**）→ 序列化为 {@code []}；</li>
 *   <li>{@code LIST} = 指定清单 → 序列化为 {@code string[]}。</li>
 * </ul>
 *
 * ⚠️ **不可归一化**：把「显式空」显示成「未配置」等于**权限放宽**
 * （未配置 = 取全集）。三态必须在 UI 上真的分得开。
 */
export type AllowlistTriState = 'UNSET' | 'EMPTY' | 'LIST'

/** 把后端的 {@code string[] | null} 映射到三态。 */
export function toAllowlistTriState(value: string[] | null | undefined): AllowlistTriState {
  if (value === null || value === undefined) return 'UNSET'
  return value.length === 0 ? 'EMPTY' : 'LIST'
}

/**
 * 把三态映射回后端的 {@code string[] | null}。
 *
 * ⚠️ 返回的是**新数组**而不是原引用 —— 原引用来自表单状态，
 * 直接透传会让「提交时的快照」与「表单里后来改的内容」共享同一个对象。
 */
export function fromAllowlistTriState(
  state: AllowlistTriState,
  list: string[] | null | undefined,
): string[] | null {
  if (state === 'UNSET') return null
  if (state === 'EMPTY') return []
  return [...(list ?? [])]
}

/* ── Tool ── */

/** Tool 表单取值。 */
export interface ToolFormValues {
  toolId: string
  name?: string
  kind: 'NATIVE' | 'MCP'
  riskLevel: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'
  mcpServerId?: string
  mcpToolName?: string
  inputSchema?: string
  paramPolicies?: string
}

/** JSON 文本是否合法（空串按「未填」处理，由调用方决定是否补默认值）。 */
function isJsonText(raw: string | null | undefined): boolean {
  const text = (raw ?? '').trim()
  if (text === '') return true
  try {
    JSON.parse(text)
    return true
  } catch {
    return false
  }
}

/**
 * 构造平台级 Tool spec。
 *
 * <p>⚠️ <b>kind 与 MCP 绑定字段是成对互斥的两组事实</b>：
 * {@code kind=MCP} 时 {@code mcpServerId} + {@code mcpToolName} **必须都给**；
 * {@code kind=NATIVE} 时**两者都不能给**。后端 400（DB 层有 CHECK 兜底），
 * 但那时理由已经填完、走到了审批 —— 反馈太晚。
 */
export function buildToolSpec(values: ToolFormValues): SpecResult<{
  toolId: string
  name?: string
  kind: 'NATIVE' | 'MCP'
  riskLevel: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'
  mcpServerId?: string
  mcpToolName?: string
  inputSchema?: string
  paramPolicies?: string
}> {
  const toolId = (values.toolId ?? '').trim()
  if (toolId === '') return { ok: false, error: '请输入 Tool 标识' }
  if (!values.riskLevel) return { ok: false, error: '请选择风险等级' }

  const serverId = (values.mcpServerId ?? '').trim()
  const toolName = (values.mcpToolName ?? '').trim()
  if (values.kind === 'MCP') {
    if (serverId === '' || toolName === '') {
      return {
        ok: false,
        error: 'kind=MCP 时必须同时填写「所属 MCP Server」与「远端操作名」（两者缺一即 400）',
      }
    }
  } else if (serverId !== '' || toolName !== '') {
    return {
      ok: false,
      error: 'kind=NATIVE（内置）时不得填写 MCP 绑定字段（两者必须同时为空）',
    }
  }

  const inputSchema = (values.inputSchema ?? '').trim()
  if (!isJsonText(inputSchema)) {
    return { ok: false, error: '入参结构（inputSchema）不是合法 JSON' }
  }
  const paramPolicies = (values.paramPolicies ?? '').trim()
  if (!isJsonText(paramPolicies)) {
    return { ok: false, error: '参数策略（paramPolicies）不是合法 JSON' }
  }

  const spec: {
    toolId: string
    name?: string
    kind: 'NATIVE' | 'MCP'
    riskLevel: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'
    mcpServerId?: string
    mcpToolName?: string
    inputSchema?: string
    paramPolicies?: string
  } = {
    toolId,
    kind: values.kind,
    riskLevel: values.riskLevel,
  }
  const name = (values.name ?? '').trim()
  if (name !== '') spec.name = name
  if (values.kind === 'MCP') {
    spec.mcpServerId = serverId
    spec.mcpToolName = toolName
  }
  // ⚠️ 后端把 null 归一成 '{}'（JSONB NOT NULL DEFAULT '{}'），空串原样送过去会被拒，
  //    故这里统一补 '{}'。
  spec.inputSchema = inputSchema === '' ? '{}' : inputSchema
  spec.paramPolicies = paramPolicies === '' ? '{}' : paramPolicies
  return { ok: true, spec }
}

/* ── Skill ── */

/** Skill 的 MCP 声明表单取值（allowlist 用三态承载）。 */
export interface McpDeclarationFormValue {
  serverId: string
  allowlistState: AllowlistTriState
  allowlist?: string[]
}

/** Skill 表单取值。 */
export interface SkillFormValues {
  skillId: string
  name?: string
  description?: string
  declaredTools?: string[]
  declaredMcp?: McpDeclarationFormValue[]
  riskCeiling: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'
  paramPolicies?: string
  requiredCaps?: string[]
}

/** 构造平台级 Skill spec。 */
export function buildSkillSpec(values: SkillFormValues): SpecResult<{
  skillId: string
  name?: string
  description?: string
  declaredTools?: string[]
  declaredMcp?: { serverId: string; toolAllowlist?: string[] | null }[]
  riskCeiling: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'
  paramPolicies?: string
  requiredCaps?: string[]
}> {
  const skillId = (values.skillId ?? '').trim()
  if (skillId === '') return { ok: false, error: '请输入 Skill 标识' }
  if (!values.riskCeiling) return { ok: false, error: '请选择风险上限' }

  const declarations = values.declaredMcp ?? []
  const seenServers = new Set<string>()
  const declaredMcp: { serverId: string; toolAllowlist?: string[] | null }[] = []
  for (const declaration of declarations) {
    const serverId = (declaration.serverId ?? '').trim()
    if (serverId === '') return { ok: false, error: 'MCP 声明缺少 Server 标识' }
    if (seenServers.has(serverId)) {
      return { ok: false, error: `MCP 声明重复：${serverId}` }
    }
    seenServers.add(serverId)
    if (declaration.allowlistState === 'LIST' && (declaration.allowlist ?? []).length === 0) {
      return {
        ok: false,
        error: `${serverId}：选择了「指定清单」但清单为空 —— 请改选「显式空」`,
      }
    }
    declaredMcp.push({
      serverId,
      // ⚠️ 三态在此落地为 null / [] / 清单，**不可归一化**。
      toolAllowlist: fromAllowlistTriState(declaration.allowlistState, declaration.allowlist),
    })
  }

  const paramPolicies = (values.paramPolicies ?? '').trim()
  if (!isJsonText(paramPolicies)) {
    return { ok: false, error: '参数策略（paramPolicies）不是合法 JSON' }
  }

  const spec: {
    skillId: string
    name?: string
    description?: string
    declaredTools?: string[]
    declaredMcp?: { serverId: string; toolAllowlist?: string[] | null }[]
    riskCeiling: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'
    paramPolicies?: string
    requiredCaps?: string[]
  } = {
    skillId,
    riskCeiling: values.riskCeiling,
    declaredTools: [...(values.declaredTools ?? [])],
    declaredMcp,
    paramPolicies: paramPolicies === '' ? '{}' : paramPolicies,
    requiredCaps: [...(values.requiredCaps ?? [])],
  }
  const name = (values.name ?? '').trim()
  if (name !== '') spec.name = name
  const description = (values.description ?? '').trim()
  if (description !== '') spec.description = description
  return { ok: true, spec }
}

/* ── MCP Server ── */

/** MCP Server 表单取值（暴露操作清单用三态承载）。 */
export interface McpServerFormValues {
  serverId: string
  transport: 'HTTP' | 'SSE'
  endpoint?: string
  command?: string
  authKind: 'TOKEN' | 'OAUTH' | 'NONE'
  trustTier: 'INTERNAL' | 'PARTNER' | 'PUBLIC'
  exposedAllowlistState: AllowlistTriState
  exposedAllowlist?: string[]
  connectionRisk: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'
  credentialRef?: string
}

/** 构造平台级 MCP Server spec。 */
export function buildMcpServerSpec(values: McpServerFormValues): SpecResult<{
  serverId: string
  transport: 'HTTP' | 'SSE'
  endpoint?: string
  command?: string
  authKind: 'TOKEN' | 'OAUTH' | 'NONE'
  trustTier: 'INTERNAL' | 'PARTNER' | 'PUBLIC'
  exposedToolAllowlist?: string[] | null
  connectionRisk: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'
  credentialRef?: string
}> {
  const serverId = (values.serverId ?? '').trim()
  if (serverId === '') return { ok: false, error: '请输入 MCP Server 标识' }
  if (!values.connectionRisk) return { ok: false, error: '请选择连接风险等级' }

  const endpoint = (values.endpoint ?? '').trim()
  // ⚠️ V1 只支持 http / sse，两者都**必须**配置 endpoint
  //    （后端 `McpServerConfigValidator`：http / sse 传输必须配置 endpoint）。
  if (endpoint === '') {
    return { ok: false, error: 'http / sse 传输必须配置 endpoint' }
  }
  let parsed: URL | null = null
  try {
    parsed = new URL(endpoint)
  } catch {
    return { ok: false, error: 'endpoint 不是合法的 URL' }
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return { ok: false, error: 'endpoint 仅允许 http / https 协议' }
  }
  if (parsed.username !== '' || parsed.password !== '') {
    return { ok: false, error: 'endpoint 不得内嵌用户名 / 口令（凭据仅引用）' }
  }

  if (values.exposedAllowlistState === 'LIST' && (values.exposedAllowlist ?? []).length === 0) {
    return {
      ok: false,
      error: '选择了「指定清单」但清单为空 —— 请改选「显式空」',
    }
  }

  const spec: {
    serverId: string
    transport: 'HTTP' | 'SSE'
    endpoint?: string
    command?: string
    authKind: 'TOKEN' | 'OAUTH' | 'NONE'
    trustTier: 'INTERNAL' | 'PARTNER' | 'PUBLIC'
    exposedToolAllowlist?: string[] | null
    connectionRisk: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'
    credentialRef?: string
  } = {
    serverId,
    transport: values.transport,
    authKind: values.authKind,
    trustTier: values.trustTier,
    connectionRisk: values.connectionRisk,
    // ⚠️ 三态在此落地为 null / [] / 清单，**不可归一化**。
    exposedToolAllowlist: fromAllowlistTriState(
      values.exposedAllowlistState,
      values.exposedAllowlist,
    ),
  }
  spec.endpoint = endpoint
  const command = (values.command ?? '').trim()
  if (command !== '') spec.command = command
  const credentialRef = (values.credentialRef ?? '').trim()
  if (credentialRef !== '') spec.credentialRef = credentialRef
  return { ok: true, spec }
}

/* ── 停用（= 生命周期迁移，不是物理删除） ── */

/** 平台级对象的类型标识（平台通道只管这三类）。 */
export type PlatformObjectType = 'tool' | 'skill' | 'mcp_server'

/** 停用的目标状态（单向终态）。 */
export const DEPRECATED_STATE = 'DEPRECATED'

/** 停用的期望原状态（CAS 前置条件）。 */
export const PUBLISHED_STATE = 'PUBLISHED'

/**
 * 构造「停用」申请体。
 *
 * <p>⚠️ **这不是物理删除**：已发布行不可变（DB 触发器），物理删除不做。
 * 正确语义是迁移到 DEPRECATED —— 单向终态，且仍走三步工作流。
 */
export function buildLifecycleRequest(
  type: PlatformObjectType,
  objectId: string,
  version: number,
  reason: string,
): SpecResult<{
  type: PlatformObjectType
  objectId: string
  version: number
  expectedFrom: string
  to: string
  reason: string
}> {
  const id = (objectId ?? '').trim()
  if (id === '') return { ok: false, error: '对象标识不能为空' }
  if (!Number.isInteger(version) || version <= 0) {
    return { ok: false, error: '版本号必须是正整数（必须是对象当前版本号）' }
  }
  const trimmedReason = (reason ?? '').trim()
  if (trimmedReason === '') {
    // ⚠️ 后端对空理由直接 400（"变更理由不能为空"）。
    return { ok: false, error: '停用理由不能为空（将留痕）' }
  }
  return {
    ok: true,
    spec: {
      type,
      objectId: id,
      version,
      expectedFrom: PUBLISHED_STATE,
      to: DEPRECATED_STATE,
      reason: trimmedReason,
    },
  }
}

/* ── 发布集合的自洽闭包 ── */

/**
 * 「登记新对象」的保留路由段。
 *
 * ⚠️ 用保留字而不是另开一条路由：登记与修改**共用同一张草稿表单**，
 * 差别只在「标识是否已存在」——分两条路由就会有两份表单，迟早漂移。
 */
export const NEW_OBJECT_SEGMENT = 'new'

/**
 * 把目录项转成**可重新发布**的 MCP Server spec（闭包补齐用）。
 *
 * ⚠️ `credentialRef` **无法**从目录接口取得（目录只给 `hasCredentialRef` 布尔，
 * 凭据材料与定位符永不入响应），所以转换结果里必然没有凭据引用 ——
 * 调用方**必须**就此拦住操作者，否则「顺手改个别的字段」就会把凭据引用悄悄丢掉。
 *
 * <p>⚠️ 后果有多严重：后端为发布集合里的每个 Server **建新版本行**，
 * 凭据引用取自提交体；提交体里没有 ⇒ 新版本没有凭据引用 ⇒
 * 指针移过去后，该 Server 在**全平台**的生效配置里认证失败。
 * 一个「改 Skill 的声明清单」的操作，静默改掉了全平台的 MCP 认证。
 *
 * <p>故这里**不**做「静默丢凭据」，而是交由调用方用
 * {@link credentialRefLossRisk} **显式阻断**（fail-closed）。
 */
export function mcpCatalogToSpec(item: McpCatalogItem): PlatformMcpServerSpec {
  return {
    serverId: item.serverId,
    transport: item.transport as PlatformMcpServerSpec['transport'],
    endpoint: item.endpoint ?? undefined,
    authKind: item.authKind as PlatformMcpServerSpec['authKind'],
    trustTier: item.trustTier as PlatformMcpTrustTier,
    exposedToolAllowlist: item.exposedToolAllowlist,
    connectionRisk: item.connectionRisk as PlatformMcpServerSpec['connectionRisk'],
  }
}

/**
 * 判断「把某个既有 MCP Server 纳入本次发布集合」是否会**丢掉它的凭据引用**。
 *
 * <p>⚠️ 目录接口只回显 {@code hasCredentialRef} 布尔（凭据材料与定位符**永不入响应**，
 * §4.2 / P5-T3），所以：
 * <ul>
 *   <li>{@code hasCredentialRef === true} ⇒ 该 Server **确有**凭据引用，
 *       而本次提交的 spec **必然不带**它 ⇒ **会丢** ⇒ 必须拦住；</li>
 *   <li>{@code hasCredentialRef === false} ⇒ 没有凭据可丢 ⇒ 放行。</li>
 * </ul>
 *
 * <p>⚠️ 为什么不「提示一下就放行」：那等于把「全平台 MCP 认证失效」交给
 * 一个「下次也许会看到」的告警去承担 —— 而提交者改的是**别的对象**，
 * 根本不会注意到这个 Server 的凭据没了。故按 **fail-closed** 处理。
 *
 * @param item 目录项（读 {@code hasCredentialRef}）
 * @returns 会丢凭据时返回该 Server 标识，否则 null
 */
export function credentialRefLossRisk(item: McpCatalogItem): string | null {
  return item.hasCredentialRef ? item.serverId : null
}

/**
 * 闭包补齐时**必须显式阻断**的凭据引用风险（fail-closed）。
 *
 * <p>⚠️ 返回非空 ⇒ **不得**提交本次发布集合。
 * 提示必须点名具体 Server，并给出可执行的出路
 * （去该 Server 自己的详情页改版，那里能填凭据引用）。
 */
export function credentialRefLossBlocker(serverIds: string[]): string | null {
  if (serverIds.length === 0) return null
  return (
    `本次发布集合会连带重发以下已登记凭据引用的 MCP Server：${serverIds.join('、')}。` +
    '目录接口不回显凭据定位符，连带重发会让它们的新版本**丢掉凭据引用**' +
    '（全平台生效配置的认证会失效）。请改为：在各自详情页单独改版并填写凭据引用。'
  )
}

/**
 * 平台级发布**必须构成自洽闭包**（后端 `ConfigValidationGate`）：
 * Skill 声明的每个 Tool / MCP Server、以及 MCP 型 Tool 绑定的 Server，
 * 都必须**出现在同一次请求**的发布集合里，否则后端以
 * {@code TOOL_NOT_DECLARED} / {@code MCP_NOT_BOUND} 拒。
 *
 * <p>⚠️ 这条约束是**后端既有行为**，不是本轮新增；前端必须替作者把闭包补齐，
 * 否则「只改一个 Skill 的声明清单」会被 400 挡下，而错误信息指向的对象
 * 根本不在用户眼前。
 */
export interface ClosurePlan {
  /** 需要一并发布的 Tool spec（按声明顺序去重） */
  tools: { toolId: string; name?: string; kind: 'NATIVE' | 'MCP'; riskLevel: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'; mcpServerId?: string; mcpToolName?: string; inputSchema?: string; paramPolicies?: string }[]
  /** 需要一并发布的 MCP Server spec */
  servers: { serverId: string; transport: 'HTTP' | 'SSE'; endpoint?: string; command?: string; authKind: 'TOKEN' | 'OAUTH' | 'NONE'; trustTier: 'INTERNAL' | 'PARTNER' | 'PUBLIC'; exposedToolAllowlist?: string[] | null; connectionRisk: 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'; credentialRef?: string }[]
  /** ⚠️ 声明了但**取不到定义**的 Tool id —— 必须如实呈现，不能悄悄丢弃 */
  unresolvedTools: string[]
  /** ⚠️ 声明了但取不到定义的 MCP Server id */
  unresolvedServers: string[]
  /**
   * ⚠️ 因「MCP 型 Tool 绑定了某个 Server」而**传递引入**的 Server id。
   *
   * <p>后端 `ConfigValidationGate` 要求：MCP 型 Tool 绑定的 Server 也必须
   * 出现在同一次发布集合里（否则 `MCP_NOT_BOUND`）。而 Skill 页只按
   * 「声明的 Tool / 声明的 MCP」补齐时，**跳过了这一跳** ——
   * 结果是「Skill 声明了一个 MCP 型 Tool 但没声明其 Server」必被 400 拦下。
   */
  transitiveServers: string[]
}

/**
 * 计算发布集合需要补齐的闭包成员。
 *
 * @param declaredTools  Skill 声明的 Tool id 列表
 * @param declaredServers Skill 声明的 MCP Server id 列表
 * @param resolveTool    Tool id → 当前定义（取不到返回 null）
 * @param resolveServer  Server id → 当前定义（取不到返回 null）
 */
export function planClosure(
  declaredTools: string[],
  declaredServers: string[],
  resolveTool: (id: string) => ClosurePlan['tools'][number] | null,
  resolveServer: (id: string) => ClosurePlan['servers'][number] | null,
): ClosurePlan {
  const tools: ClosurePlan['tools'] = []
  const servers: ClosurePlan['servers'] = []
  const unresolvedTools: string[] = []
  const unresolvedServers: string[] = []
  const transitiveServers: string[] = []
  const seenTools = new Set<string>()
  const seenServers = new Set<string>()

  for (const toolId of declaredTools) {
    if (seenTools.has(toolId)) continue
    seenTools.add(toolId)
    const def = resolveTool(toolId)
    if (def) tools.push(def)
    else unresolvedTools.push(toolId)
  }
  for (const serverId of declaredServers) {
    if (seenServers.has(serverId)) continue
    seenServers.add(serverId)
    const def = resolveServer(serverId)
    if (def) servers.push(def)
    else unresolvedServers.push(serverId)
  }
  // ⚠️ **传递闭包**：MCP 型 Tool 绑定的 Server 也必须一并发布
  //    （后端 `ConfigValidationGate` 的 `MCP_NOT_BOUND`）。
  //    这一跳**不能省** —— 省略它会让「声明了一个 MCP 型 Tool 但没声明其 Server」
  //    的提交必被 400 拦下，而错误信息指向的对象根本不在用户眼前。
  for (const tool of tools) {
    if (tool.kind !== 'MCP') continue
    const serverId = tool.mcpServerId
    if (!serverId || seenServers.has(serverId)) continue
    seenServers.add(serverId)
    transitiveServers.push(serverId)
    const def = resolveServer(serverId)
    if (def) servers.push(def)
    else unresolvedServers.push(serverId)
  }
  return { tools, servers, unresolvedTools, unresolvedServers, transitiveServers }
}
