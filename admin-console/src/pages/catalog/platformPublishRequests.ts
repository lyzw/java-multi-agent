/**
 * 平台级对象的**发布申请体构造**（第三十七批 · C37-3）。
 *
 * <p><b>⚠️ 为什么抽成独立模块</b>：登记改为**弹窗**后，
 * 「登记」与「修改」就分处两处（目录页的弹窗 / 详情页的表单）——
 * 若各自写一份 {@code buildRequest}，闭包补齐规则会在两处漂移，
 * 而漂移的方向通常是「弹窗那份忘了补闭包」：
 * 作者在弹窗里填完、点提交，才被后端用一个 {@code MCP_NOT_BOUND} 挡下，
 * 而错误信息指向的对象根本不在他眼前。
 *
 * <p>故本模块是<b>唯一一份</b>构造逻辑，弹窗与详情页都调它。
 */

import { getMcp, getTool } from '@/api/adminApi'
import type {
  CapabilityRiskLevel,
  McpCatalogItem,
  PlatformPublishRequest,
  PlatformToolSpec,
  ToolCatalogItem,
} from '@/api/types'
import {
  buildMcpServerSpec,
  buildSkillSpec,
  buildToolSpec,
  credentialRefLossBlocker,
  credentialRefLossRisk,
  mcpCatalogToSpec,
  planClosure,
  type McpServerFormValues,
  type SkillFormValues,
  type ToolFormValues,
} from './platformObjectForm'

/** 发布面板的表单取值 = 对象草稿字段 + 面板自带的两个字段。 */
export type PublishFormValues<T> = T & { reason: string; expectedPublishSeq?: number }

/** 本地构造失败（形状非法 / 闭包不完整）。 */
export interface PublishBuildError {
  error: string
}

/**
 * 把 Tool 目录项转成**可重新发布**的 spec（闭包补齐用）。
 *
 * <p>⚠️ <b>HTTP 工具的三个字段必须一并带上</b>：
 * {@code kind=http} 却缺 http 三件套，后端会以
 * 「kind=http 的 Tool 必须同时声明…」拒（400）。
 * 少了它们，重发出来的就是一个「永远跑不起来」的 Tool 版本。
 */
export function toolCatalogToSpec(item: ToolCatalogItem): PlatformToolSpec {
  const kind = item.kind === 'MCP' ? 'MCP' : item.kind === 'HTTP' ? 'HTTP' : 'NATIVE'
  const spec: PlatformToolSpec = {
    toolId: item.toolId,
    kind,
    riskLevel: item.riskLevel as CapabilityRiskLevel,
    inputSchema: item.inputSchema ?? '{}',
    paramPolicies: item.paramPolicies ?? '{}',
  }
  if (item.name) {
    spec.name = item.name
  }
  if (kind === 'MCP') {
    spec.mcpServerId = item.mcpServerId ?? undefined
    spec.mcpToolName = item.mcpToolName ?? undefined
  }
  if (kind === 'HTTP') {
    spec.httpMethod = item.httpMethod ?? undefined
    spec.httpUrl = item.httpUrl ?? undefined
    spec.httpTimeoutMs = item.httpTimeoutMs ?? undefined
  }
  return spec
}

/** 取发布基线（未填则不带该字段）。 */
function applyBaseline(
  body: PlatformPublishRequest,
  values: { expectedPublishSeq?: number },
): void {
  if (values.expectedPublishSeq !== undefined && values.expectedPublishSeq !== null) {
    body.expectedPublishSeq = values.expectedPublishSeq
  }
}

/**
 * 构造 Tool 的发布申请体。
 *
 * <p>⚠️ **闭包补齐**：kind=MCP 时把绑定的 Server 一并纳入本次发布集合 ——
 * 后端要求平台级发布集合自洽，否则以 MCP_NOT_BOUND 拒（400）。
 */
export async function buildToolPublishRequest(
  values: PublishFormValues<ToolFormValues>,
): Promise<PlatformPublishRequest | PublishBuildError> {
  const built = buildToolSpec(values)
  if (!built.ok) return { error: built.error }

  const body: PlatformPublishRequest = {
    tools: [built.spec as PlatformToolSpec],
    reason: values.reason.trim(),
  }
  applyBaseline(body, values)

  if (built.spec.kind === 'MCP') {
    const serverId = built.spec.mcpServerId as string
    let server: McpCatalogItem
    try {
      server = await getMcp(serverId)
    } catch {
      return {
        error: `所属 MCP Server「${serverId}」不存在或不可读 —— 发布集合必须自洽闭包，无法只发 Tool`,
      }
    }
    // ⚠️ **凭据引用不得被静默丢弃**（fail-closed）：
    //    闭包补齐会把该既有 Server 一并重发，而目录接口从不回显凭据定位符
    //    ⇒ 重发出来的新版本**没有凭据引用** ⇒ 全平台生效配置的认证失效。
    //    提交者改的是 Tool，却静默改掉了 MCP 的认证 —— 必须拦住并点名。
    const risk = credentialRefLossRisk(server)
    const blocker = credentialRefLossBlocker(risk ? [risk] : [])
    if (blocker) {
      return { error: blocker }
    }
    body.mcpServers = [mcpCatalogToSpec(server)]
  }
  return body
}

/**
 * 构造 Skill 的发布申请体（含**闭包补齐**）。
 *
 * <p>⚠️ 声明了但**取不到定义**的对象会被如实列进错误信息 ——
 * 悄悄丢弃会让作者以为「闭包已经齐了」，而后端会用一个指向不可见对象的
 * 错误码把事情搅混。
 */
export async function buildSkillPublishRequest(
  values: PublishFormValues<SkillFormValues>,
): Promise<PlatformPublishRequest | PublishBuildError> {
  const built = buildSkillSpec(values)
  if (!built.ok) return { error: built.error }
  const spec = built.spec

  const body: PlatformPublishRequest = {
    skills: [spec],
    reason: values.reason.trim(),
  }
  applyBaseline(body, values)

  // ── 闭包：声明的 Tool / MCP Server 必须一并发布 ──
  const declaredTools = spec.declaredTools ?? []
  const declaredServers = (spec.declaredMcp ?? []).map((d) => d.serverId)

  // ⚠️ 先把被引用对象的**当前定义**读出来（闭包成员取自这里，
  //    不是取自用户这次的输入 —— 用户只声明了 id）。
  const [toolItems, serverItems] = await Promise.all([
    Promise.all(
      declaredTools.map(async (toolId) => {
        try {
          return { toolId, item: (await getTool(toolId)) as ToolCatalogItem | null }
        } catch {
          return { toolId, item: null as ToolCatalogItem | null }
        }
      }),
    ),
    Promise.all(
      declaredServers.map(async (serverId) => {
        try {
          return { serverId, item: (await getMcp(serverId)) as McpCatalogItem | null }
        } catch {
          return { serverId, item: null as McpCatalogItem | null }
        }
      }),
    ),
  ])
  const toolById = new Map(toolItems.map((x) => [x.toolId, x.item]))
  const serverById = new Map(serverItems.map((x) => [x.serverId, x.item]))

  const plan = planClosure(
    declaredTools,
    declaredServers,
    (toolId) => {
      const t = toolById.get(toolId)
      if (!t) return null
      // ⚠️ **kind 必须如实映射**（含 HTTP）：把 HTTP 工具压成 NATIVE
      //    会让重发的 Tool 版本**丢掉 HTTP 三件套**，
      //    得到一个「永远跑不起来」的工具（后端会直接拒，但错误信息会指向形状而非闭包）。
      return toolCatalogToSpec(t) as ReturnType<typeof toolCatalogToSpec> & {
        toolId: string
      }
    },
    (serverId) => {
      const s = serverById.get(serverId)
      return s ? mcpCatalogToSpec(s) : null
    },
  )

  if (plan.unresolvedTools.length > 0 || plan.unresolvedServers.length > 0) {
    const parts: string[] = []
    if (plan.unresolvedTools.length > 0) parts.push(`Tool：${plan.unresolvedTools.join('、')}`)
    if (plan.unresolvedServers.length > 0) parts.push(`MCP Server：${plan.unresolvedServers.join('、')}`)
    return {
      error:
        `声明的对象取不到定义（${parts.join('；')}）—— 发布集合必须自洽闭包。` +
        '请先登记这些对象，或从声明中移除它们。',
    }
  }

  // ⚠️ **凭据引用不得被静默丢弃**（fail-closed）：
  //    闭包补齐会把既有 MCP Server 一并重发，而目录接口从不回显凭据定位符
  //    ⇒ 重发出来的新版本**没有凭据引用** ⇒ 全平台生效配置的认证失效。
  const credentialRisks: string[] = []
  for (const serverId of plan.servers.map((s) => s.serverId)) {
    const item = serverById.get(serverId)
    const risk = item ? credentialRefLossRisk(item) : null
    if (risk) credentialRisks.push(risk)
  }
  const credentialBlocker = credentialRefLossBlocker(credentialRisks)
  if (credentialBlocker) {
    return { error: credentialBlocker }
  }

  body.tools = plan.tools
  body.mcpServers = plan.servers
  return body
}

/**
 * 构造 MCP Server 的发布申请体。
 *
 * <p>⚠️ MCP Server 不牵涉闭包补齐（它本身不被别的对象引用于同一层），
 * 但**凭据引用**必须由作者显式重新填写 —— 目录接口从不回显定位符，
 * 不重新填写就会让新版本丢掉凭据（运行时认证失败）。
 */
export function buildMcpPublishRequest(
  values: PublishFormValues<McpServerFormValues>,
): PlatformPublishRequest | PublishBuildError {
  const built = buildMcpServerSpec(values)
  if (!built.ok) return { error: built.error }
  const body: PlatformPublishRequest = {
    mcpServers: [built.spec],
    reason: values.reason.trim(),
  }
  applyBaseline(body, values)
  return body
}
