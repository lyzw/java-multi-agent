/**
 * 平台控制台 · 目录四页**共享的「双击详情」抽屉**（平台链 /api/platform/v1/capabilities/*）。
 *
 * <p><b>⚠️ 为什么是抽屉而不是整页详情</b>：平台级对象没有应用层写入口，
 * 详情是**纯只读**的；若跳整页（像旧租户域那样点「详情」离开列表），
 * 看完返回必然重新挂载列表页 → 又调一次目录 list 接口 —— 用户看到的
 * 「看个详情怎么又刷一遍列表」。抽屉在列表**上方**开合，列表状态不丢、
 * 不再请求，收支为：每看一个对象恰好只多 1～2 个详情/关系请求。
 *
 * <p><b>⚠️ 三类对象三种取数</b>：Tool / Skill / MCP Server 有平台链单条 +
 * 关系端点（{@code GET /capabilities/{kind}/{id}}、{@code /relations}）；
 * Agent 注册表**只有列表盘点**（禁区红线：定义正文不出租户边界），
 * 身份画像直接用**行内已有字段**渲染；仅「历史变更记录」按需发一次
 * 元数据请求（{@code /agents/{id}/changes}），仍不含定义正文。
 */

import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Descriptions, Divider, Drawer, Modal, Space, Spin, Table, Tag, Tooltip, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import {
  getConsoleAgentChanges,
  getConsoleMcp,
  getConsoleMcpRelations,
  getConsoleSkill,
  getConsoleSkillRelations,
  getConsoleTool,
  getConsoleToolRelations,
} from '@/api/adminApi'
import type {
  PlatformAgentChange,
  PlatformAgentRegistryItem,
  ToolCatalogItem,
  ToolRelations,
} from '@/api/types'
import { ApiError } from '@/api/client'
import { JsonBlock, ToolFullInfo } from '@/pages/catalog/ToolDefinitionView'
import { allowlistCell, lifecycleTag, riskTag, skillDescriptionView, timeCell } from '@/pages/catalog/displayBits'
import { AgentVersionHistory, type AgentVersionHistoryHandle } from '@/pages/agents/AgentVersionHistory'

import { useDetailMode } from '@/contexts/detailMode'

/**
 * 详情骨架 —— 根据全局配置 {@link useDetailMode} 选择 Drawer / Modal / Page 三选一。
 *
 * <p>⚠️ 配置是全局的：cap.detail.mode 存 localStorage，一处切换全平台生效。
 * 调用方只管 title/open/onClose/children，不关心最终用哪种形式。
 *
 * <p>⚠️ <b>page 模式</b> 暂不做跳转（详情内容在当前页组件里）：
 * 全局配置是 "允许未来切到新页面详情视图" 的开关预留，v1 统一走 Drawer 或 Modal。
 * page 模式下本组件 fallback 到 Drawer，保证详情始终可读。
 */
export function CatalogDetailDrawer({
  title,
  open,
  onClose,
  children,
  pagePath,
}: {
  title: ReactNode
  open: boolean
  onClose: () => void
  children: ReactNode
  /**
   * 「新页面」模式下跳转的目标路由。
   *
   * <p>⚠️ 只有能按 URL 重建的详情才传它（Tool / Skill / MCP 详情组件只依赖 id）。
   * Agent 注册表详情依赖整行聚合数据、无法从 URL 重建 —— 不传时 page 模式
   * 回落到抽屉，保证详情始终可读（绝不出现「切了模式就看不到详情」）。
   */
  pagePath?: string
}): JSX.Element | null {
  const { mode } = useDetailMode()
  const navigate = useNavigate()
  const jumpedRef = useRef(false)

  // page 模式：open 后跳转到子路由（详情页自行按 id 取数）。
  useEffect(() => {
    if (!open) { jumpedRef.current = false; return }
    if (mode === 'page' && pagePath && !jumpedRef.current) {
      jumpedRef.current = true
      navigate(pagePath)
    }
  }, [open, mode, pagePath, navigate])

  if (!open) return null

  if (mode === 'modal') {
    return (
      <Modal
        open
        onCancel={onClose}
        title={title}
        footer={null}
        width={760}
        destroyOnHidden
      >
        {children}
      </Modal>
    )
  }

  if (mode === 'page' && pagePath) {
    // 已触发跳转；跳转由路由接管渲染，本容器不重复渲染内容。
    return null
  }

  // drawer（默认）/ page 无 pagePath 的兜底
  return (
    <Drawer open onClose={onClose} title={title} width={760} destroyOnHidden>
      {children}
    </Drawer>
  )
}

/** 取数详情内容区的通用骨架：loading / 404 / 其它错误 / 正常四态。 */
function DetailSection({
  loading,
  missing,
  error,
  errorFallback = '加载详情失败',
  children,
}: {
  loading: boolean
  missing: boolean
  error: string | null
  errorFallback?: string
  children: ReactNode
}): JSX.Element {
  if (loading) return <Spin style={{ display: 'block', marginTop: 48 }} />
  if (missing) return <Alert type="warning" showIcon message="对象不存在（可能已被其他管理员做改版或停用；点关闭后重试）" />
  if (error !== null) return <Alert type="error" showIcon message={errorFallback} description={error} />
  return <>{children}</>
}

/**
 * 统一取数：单条 + 关系并行读；404 视同「对象不存在」（单独一态），
 * 其余如实报错。目录行 id 变化即重取，关闭即卸载不留缓存。
 */
function useDetailPair<D, R>(
  id: string,
  detail: (id: string) => Promise<D>,
  relations: (id: string) => Promise<R>,
): { loading: boolean; missing: boolean; error: string | null; data: D | null; rel: R | null } {
  const [state, setState] = useState<{
    loading: boolean
    missing: boolean
    error: string | null
    data: D | null
    rel: R | null
  }>({ loading: true, missing: false, error: null, data: null, rel: null })

  useEffect(() => {
    let active = true
    setState({ loading: true, missing: false, error: null, data: null, rel: null })
    Promise.all([detail(id), relations(id)])
      .then(([d, r]) => {
        if (active) setState({ loading: false, missing: false, error: null, data: d, rel: r })
      })
      .catch((e: unknown) => {
        if (active) {
          setState({
            loading: false,
            missing: e instanceof ApiError && e.status === 404,
            error: e instanceof Error ? e.message : '加载详情失败',
            data: null,
            rel: null,
          })
        }
      })
    return () => {
      active = false
    }
    // 取数函数为模块级导入（稳定）；仅随 id 变化重取
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  return state
}

/** 分区标题（详情抽屉里每个小组件一段，不靠间距猜边界）。 */
function DetailSubTitle({ children }: { children: ReactNode }): JSX.Element {
  return (
    <Typography.Title level={5} style={{ marginTop: 16, marginBottom: 12 }}>
      {children}
    </Typography.Title>
  )
}

/* ── Tool ─────────────────────────────────────────────────────── */

/** Tool 关系区：所属 MCP Server / 远程 HTTP / 内置三态如实呈现。 */
function ToolRelationsBody({
  tool,
  relations,
}: {
  tool: ToolCatalogItem
  relations: ToolRelations
}): JSX.Element {
  if (relations.mcpServer !== null) {
    return (
      <Descriptions
        bordered
        column={2}
        size="small"
        items={[
          { key: 'server', label: '所属 MCP Server', children: <Typography.Text code>{relations.mcpServer.serverId}</Typography.Text> },
          { key: 'transport', label: '传输', children: relations.mcpServer.transport },
          { key: 'trust', label: '信任分级', children: relations.mcpServer.trustTier },
          { key: 'risk', label: '连接风险', children: riskTag(relations.mcpServer.connectionRisk) },
          { key: 'version', label: 'Server 版本', children: <Typography.Text>v{relations.mcpServer.version}</Typography.Text> },
          { key: 'published', label: 'Server 状态', children: relations.mcpServer.published ? '已发布' : '未发布' },
        ]}
      />
    )
  }
  if (tool.kind === 'HTTP') {
    return (
      <Alert
        type="info"
        showIcon
        message="远程 HTTP 请求工具"
        description="目标由配置里的 URL 决定，不绑定任何 MCP Server —— 这是合法状态，不是「查不到」。每次调用都会重新做 SSRF 校验。"
      />
    )
  }
  if (tool.kind === 'MCP') {
    return <Alert type="warning" showIcon message="声明绑定 MCP Server，但关系接口未返回该 Server（悬空引用）" />
  }
  return (
    <Alert
      type="info"
      showIcon
      message="内置 Tool（不绑定任何 MCP Server）"
      description="kind=NATIVE 的 Tool 不属于任何 MCP Server —— 这是合法状态，不是「查不到」。"
    />
  )
}

/** Tool 双击详情：全字段（ToolFullInfo，与登记 / 改版共用视图）+ 关系。 */
export function ToolCatalogDetail({ id }: { id: string }): JSX.Element {
  const detail = useDetailPair(id, getConsoleTool, getConsoleToolRelations)
  const tool = detail.data
  const relations = detail.rel
  return (
    <DetailSection loading={detail.loading} missing={detail.missing} error={detail.error} errorFallback="Tool 详情加载失败">
      {tool !== null && relations !== null ? (
        <>
          {/* ⚠️ 详情给的是**全量信息**：定义字段（入参结构 / 参数策略等）
              与版本 / 生命周期元信息一个都不能少 —— 少一项，运维只能靠猜。 */}
          <ToolFullInfo tool={tool} />
          <DetailSubTitle>关系</DetailSubTitle>
          <ToolRelationsBody tool={tool} relations={relations} />
        </>
      ) : null}
    </DetailSection>
  )
}

/* ── Skill ────────────────────────────────────────────────────── */

/** Skill 双击详情：全字段 + 声明关系（悬空引用单独呈现，不得与「未声明」混淆）。 */
export function SkillCatalogDetail({ id }: { id: string }): JSX.Element {
  const detail = useDetailPair(id, getConsoleSkill, getConsoleSkillRelations)
  const skill = detail.data
  const relations = detail.rel
  return (
    <DetailSection loading={detail.loading} missing={detail.missing} error={detail.error} errorFallback="Skill 详情加载失败">
      {skill !== null ? (
        <>
          {/* 摘要标签行：扫一眼即知身份 / 风险 / 生命周期 / 版本 */}
          <Space wrap size={[8, 8]} style={{ marginBottom: 4 }}>
            <Tag color="blue">
              <Typography.Text code>{skill.skillId}</Typography.Text>
            </Tag>
            {skill.name ? <Tag>{skill.name}</Tag> : null}
            {riskTag(skill.riskCeiling)}
            {lifecycleTag(skill.lifecycleState)}
            <Tag color="geekblue">v{skill.version}</Tag>
            <Tag>Schema v{skill.schemaVersion}</Tag>
            <Typography.Text type="secondary">发布：{timeCell(skill.publishedAt)}</Typography.Text>
          </Space>

          <Divider orientation="left" plain style={{ margin: '12px 0' }}>
            基本信息
          </Divider>
          <Descriptions
            bordered
            column={2}
            size="small"
            items={[
              { key: 'id', label: '标识（skillId）', children: <Typography.Text code>{skill.skillId}</Typography.Text> },
              { key: 'name', label: '名称', children: skill.name ?? '-' },
              { key: 'risk', label: '风险上限', children: riskTag(skill.riskCeiling) },
              { key: 'state', label: '生命周期', children: lifecycleTag(skill.lifecycleState) },
              { key: 'version', label: '版本', children: <Typography.Text>v{skill.version}</Typography.Text> },
              { key: 'schema', label: 'Schema 版本', children: String(skill.schemaVersion) },
              { key: 'publishedAt', label: '发布时间', children: timeCell(skill.publishedAt) },
              { key: 'caps', label: '能力要求', children: codeList(skill.requiredCaps) },
            ]}
          />

          <DetailSubTitle>描述（1级用途 + 2级使用指引 + 3级详细描述）</DetailSubTitle>
          {/* 三级描述（1级 purpose + 2级 usage + 3级 content）：⚠️ 描述不是授权 */}
          {skill.description !== null ? (
            skillDescriptionView(skill.description)
          ) : (
            <Typography.Text type="secondary">未提供</Typography.Text>
          )}

          <DetailSubTitle>参数策略（paramPolicies）</DetailSubTitle>
          <JsonBlock value={skill.paramPolicies} />

          <DetailSubTitle>声明关系（能力目录登记情况）</DetailSubTitle>
          {relations !== null ? (
            <Descriptions
              bordered
              column={1}
              size="small"
              items={[
                {
                  key: 'tools',
                  label: '声明 Tool',
                  children: <RelationList found={relations.tools.map((t) => t.toolId)} missing={relations.missingTools} />,
                },
                {
                  key: 'mcp',
                  label: '声明 MCP Server',
                  children: <RelationList found={relations.mcpServers.map((s) => s.serverId)} missing={relations.missingMcpServers} />,
                },
              ]}
            />
          ) : (
            <Typography.Text type="secondary">声明列表：{skill.declaredTools.length} 个 Tool / {skill.declaredMcp.length} 个 MCP Server（关系接口未返回明细）</Typography.Text>
          )}
        </>
      ) : null}
    </DetailSection>
  )
}

/* ── MCP Server ───────────────────────────────────────────────── */

/** MCP Server 双击详情：全字段（凭据只给「是否登记」布尔，定位符与材料永不回显）+ 关系。 */
export function McpCatalogDetail({ id }: { id: string }): JSX.Element {
  const detail = useDetailPair(id, getConsoleMcp, getConsoleMcpRelations)
  const server = detail.data
  const relations = detail.rel
  return (
    <DetailSection loading={detail.loading} missing={detail.missing} error={detail.error} errorFallback="MCP Server 详情加载失败">
      {server !== null ? (
        <>
          <Descriptions
            bordered
            column={2}
            size="small"
            items={[
              { key: 'id', label: '标识（serverId）', children: <Typography.Text code>{server.serverId}</Typography.Text> },
              { key: 'transport', label: '传输', children: server.transport },
              { key: 'endpoint', label: 'Endpoint / Base URL', children: <Typography.Text code style={{ wordBreak: 'break-all' }}>{server.endpoint ?? '（本地命令）'}</Typography.Text> },
              { key: 'auth', label: '认证方式', children: server.authKind },
              { key: 'trust', label: '信任分级', children: server.trustTier },
              { key: 'risk', label: '连接风险', children: riskTag(server.connectionRisk) },
              { key: 'discovery', label: '动态发现', children: server.dynamicDiscovery ? '开（允许运行时发现新工具）' : '关' },
              { key: 'credential', label: '凭据引用', children: server.hasCredentialRef ? '已登记' : <Typography.Text type="secondary">无</Typography.Text> },
              { key: 'state', label: '生命周期', children: lifecycleTag(server.lifecycleState) },
              { key: 'version', label: '版本', children: <Typography.Text>v{server.version}</Typography.Text> },
              { key: 'publishedAt', label: '发布时间', children: timeCell(server.publishedAt) },
              { key: 'createdAt', label: '创建时间', children: timeCell(server.createdAt) },
              { key: 'fingerprint', label: '配置指纹', children: <Typography.Text code style={{ wordBreak: 'break-all' }}>{server.configFingerprint}</Typography.Text>, span: 2 },
            ]}
          />
          <DetailSubTitle>关系（允许集与实际登记是两回事）</DetailSubTitle>
          {relations !== null ? (
            <Descriptions
              bordered
              column={1}
              size="small"
              items={[
                { key: 'allowlist', label: '暴露操作允许集', children: allowlistCell(relations.exposedToolAllowlist) },
                {
                  key: 'tools',
                  label: '已登记 Tool',
                  children: relations.tools.length === 0
                    ? <Typography.Text type="secondary">（无）</Typography.Text>
                    : <RelationList found={relations.tools.map((t) => t.toolId)} missing={[]} />,
                },
              ]}
            />
          ) : (
            <Typography.Text type="secondary">允许集：{allowlistCell(server.exposedToolAllowlist)}（关系接口未返回明细）</Typography.Text>
          )}
        </>
      ) : null}
    </DetailSection>
  )
}

/* ── Agent 详情（Agent 视角 · 2026-11-19 重构）────────────────── */

/**
 * Agent 详情抽屉：Agent 全局画像 + 租户绑定清单。
 *
 * <p>⚠️ Agent 是**全局实体**，{@link PlatformAgentRegistryItem} 后端返回的是
 * tenant_agent_binding 行（每条 = 一条租户-Agent 绑定）。主表（AgentRegistryPage）
 * 按 agentId 去重聚合为本组件传入 {@code agentUniq}，详情抽屉可以拿到完整画像。
 *
 * <p>⚠️ 定义正文 / 运行输出 / 会话内容不下探 —— 这不是「查不到」，是禁区红线。
 */
export function AgentRegistryDetail({
  item,
  agentUniq,
  tenantNameOf = (id) => id,
  tenantDetailOf,
}: {
  item: PlatformAgentRegistryItem
  agentUniq: Array<PlatformAgentRegistryItem & { boundTenants: string[] }>
  /** 租户 UUID → 中文名（列表页已加载，避免详情重复拉取租户清单） */
  tenantNameOf?: (id: string) => string
  /** 详情里「租户名 - UUID」的完整标签；缺省退化为 UUID */
  tenantDetailOf?: (id: string) => string
}): JSX.Element {
  const versionHistoryRef = useRef<AgentVersionHistoryHandle>(null)
  const full = agentUniq.find((a) => a.agentId === item.agentId) ?? { ...item, boundTenants: [item.tenantId] }
  const mode = (full.orchestrationMode ?? '').toUpperCase() || 'LEAF'
  const pending = full.pendingState === 'PENDING' || full.pendingState === 'APPROVED'
  const detailLabelOf = tenantDetailOf ?? ((id: string) => {
    const name = tenantNameOf(id)
    return name === id ? id : `${name} - ${id}`
  })
  return (
    <>
      {/* ── 第一块：Agent 身份画像（2 列）── */}
      <Descriptions bordered column={2} size="small" title="Agent 身份画像">
        <Descriptions.Item label="Agent 标识">
          <Typography.Text code>{full.agentId}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="名称">{full.name ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="描述" span={2}>{full.description ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="模型引用">
          {full.modelRef
            ? <Typography.Text code>{full.modelRef}</Typography.Text>
            : <Typography.Text type="danger">⚠️ 未绑模型（fail-closed）</Typography.Text>}
        </Descriptions.Item>
        <Descriptions.Item label="编排模式">
          {mode === 'LEAF' ? <Tag color="default">LEAF 叶子</Tag>
           : mode === 'ROUTER' ? <Tag color="purple">ROUTER 路由</Tag>
           : mode === 'SEQUENTIAL' ? <Tag color="orange">SEQUENTIAL 串行</Tag>
           : <Tag>{mode}</Tag>}
        </Descriptions.Item>
        <Descriptions.Item label="生命周期">{agentLifecycleTag(full.lifecycleState)}</Descriptions.Item>
        <Descriptions.Item label="发布状态">
          {pending ? (
            <Tag color="orange">
              {full.pendingState === 'APPROVED' ? '已修改未发布（待执行）' : '已修改未发布（待审批）'}
            </Tag>
          ) : (
            <Typography.Text type="secondary">无待发布变更</Typography.Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="定义版本">
          {full.defVersion === null
            ? <Typography.Text type="secondary">无定义</Typography.Text>
            : <Typography.Text>v{full.defVersion}</Typography.Text>}
        </Descriptions.Item>
        <Descriptions.Item label="发布版本">
          {full.versionCount === 0
            ? <Typography.Text type="secondary">尚无版本</Typography.Text>
            : `v${full.latestVersionNumber} · 共 ${full.versionCount}`}
        </Descriptions.Item>
        <Descriptions.Item label="最近发布">{timeCell(full.latestPublishedAt)}</Descriptions.Item>
        <Descriptions.Item label="租户绑定数">
          <Typography.Text strong>{full.tenantCount ?? full.boundTenants.length}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="提示词">
          {full.hasInstructions ? <Tag color="green">已声明</Tag> : <Typography.Text type="secondary">未声明</Typography.Text>}
        </Descriptions.Item>
        <Descriptions.Item label="收紧覆盖">
          {full.hasOverrides ? <Tag color="gold">有覆盖</Tag> : <Typography.Text type="secondary">无</Typography.Text>}
        </Descriptions.Item>
      </Descriptions>

      {/* ── 第二块：能力清单 ── */}
      <Descriptions bordered column={1} size="small" title="能力清单" style={{ marginTop: 16 }}>
        <Descriptions.Item label="角色">{codeList(full.roles)}</Descriptions.Item>
        <Descriptions.Item label="启用技能">{codeList(full.enabledSkills)}</Descriptions.Item>
        <Descriptions.Item label="技能包">{codeList(full.skillBundles ?? [])}</Descriptions.Item>
        <Descriptions.Item label="能力标签">{codeList(full.capabilityTags)}</Descriptions.Item>
      </Descriptions>

      {/* ── 第三块：租户绑定清单（多对多关系清晰呈现）── */}
      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16, marginBottom: 8 }}
        message={<Typography.Text strong>租户绑定清单（{full.boundTenants.length} 个）</Typography.Text>}
        description="同一 Agent 可分配给多个租户，同一租户可绑定多个 Agent；停用绑定不影响 Agent 定义本身。"
      />
      {full.boundTenants.length === 0 ? (
        <Typography.Text type="secondary">尚未绑定任何租户</Typography.Text>
      ) : (
        full.boundTenants.map((t) => (
          <Tag key={t} color="blue" style={{ margin: 4, fontSize: 12 }}>
            {detailLabelOf(t)}
          </Tag>
        ))
      )}

      {/* ── 第四块：历史变更记录（按需取管理元数据，不含定义正文）── */}
      <AgentChangeHistory
        agentId={full.agentId}
        tenantNameOf={tenantNameOf}
        onOpenVersionDiff={(v) => versionHistoryRef.current?.diffVersion(v)}
      />

      {/* ── 第五块：版本快照历史（含 Diff + 清理草稿；双击记录即对比）── */}
      <div style={{ marginTop: 16 }}>
        <AgentVersionHistory ref={versionHistoryRef} agentId={full.agentId} />
      </div>

      <Alert
        type="warning"
        showIcon
        style={{ marginTop: 16 }}
        message="禁区红线"
        description="Agent 的定义正文（instructions / orchestration / generationParams）、运行输出与会话内容不跨租户出平台边界；本详情只呈现管理元数据，缺失字段按「未提供」如实呈现。"
      />
    </>
  )
}

/** Agent 生命周期中文标签（注册表口径）。 */
function agentLifecycleTag(state: string | null | undefined): ReactNode {
  if (state === 'PUBLISHED') return <Tag color="green">已发布</Tag>
  if (state === 'DEPRECATED') return <Tag color="red">已停用</Tag>
  if (state === 'VALIDATED') return <Tag color="blue">已校验</Tag>
  if (state === 'DRAFT') return <Tag color="orange">草稿</Tag>
  return <Typography.Text type="secondary">未提供</Typography.Text>
}

/** 变更动作 / 状态 → 中文（UUID 与英文枚举不直接出现在详情列表）。 */
function changeActionText(action: string): string {
  if (action === 'PUBLISH') return '定义发布'
  if (action === 'LIFECYCLE_TRANSITION') return '生命周期'
  if (action === 'ROLLBACK') return '回滚'
  if (action.includes('agent_config.create')) return '创建 Agent'
  if (action.includes('binding.bind')) return '分配租户'
  if (action.includes('binding.unbind')) return '停用分配'
  if (action.includes('binding.update')) return '更新分配'
  if (action.includes('publish.request')) return '发布申请'
  if (action.includes('publish.decision')) return '变更审批'
  if (action.includes('publish.apply')) return '执行变更'
  if (action.includes('deprecate')) return '停用申请'
  return action
}

function changeStateText(state: string): string {
  if (state === 'PENDING') return '待审批'
  if (state === 'APPROVED') return '已批准·待执行'
  if (state === 'APPLIED') return '已生效'
  if (state === 'REJECTED') return '已驳回'
  if (state === 'FAILED') return '执行失败'
  return state
}

/** Agent 历史变更记录：发布申请单 + 建行 / 分配租户等直接配置动作。 */
function AgentChangeHistory({
  agentId,
  tenantNameOf,
  onOpenVersionDiff,
}: {
  agentId: string
  tenantNameOf: (id: string) => string
  /** 双击带目标版本的记录时，打开该版本的 Diff（由外层注入）。 */
  onOpenVersionDiff?: (versionNumber: number) => void
}): JSX.Element {
  const [changes, setChanges] = useState<PlatformAgentChange[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    setLoading(true)
    setError(null)
    getConsoleAgentChanges(agentId, 20)
      .then((list) => { if (active) setChanges(list) })
      .catch((e: unknown) => {
        if (active) setError(e instanceof Error ? e.message : '加载变更记录失败')
      })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [agentId])

  const columns = useMemo<TableColumnsType<PlatformAgentChange>>(
    () => [
      {
        title: '变更类型', dataIndex: 'action', width: 120,
        render: (v: string, r) => <Tag color={r.category === 'AUDIT' ? 'geekblue' : 'blue'}>{changeActionText(v)}</Tag>,
      },
      {
        title: '状态', dataIndex: 'state', width: 130,
        render: (v: string) => <Tag color={v === 'APPLIED' ? 'green' : v === 'PENDING' ? 'orange' : v === 'APPROVED' ? 'blue' : 'red'}>{changeStateText(v)}</Tag>,
      },
      {
        title: '目标版本', dataIndex: 'targetVersion', width: 90,
        render: (v: number | null) => (v === null ? '-' : `v${v}`),
      },
      {
        title: '租户', dataIndex: 'tenantId', width: 140, ellipsis: true,
        render: (v: string | null) => (v ? tenantNameOf(v) : '全局'),
      },
      {
        title: '变更理由', dataIndex: 'reason', ellipsis: true,
        render: (v: string) => <Tooltip title={v}>{v}</Tooltip>,
      },
      {
        title: '申请人', dataIndex: 'actorId', width: 110,
        render: (v: string) => <Tooltip title={v}><Typography.Text>运营人员</Typography.Text></Tooltip>,
      },
      { title: '时间', dataIndex: 'requestedAt', width: 160, render: (v: string) => timeCell(v) },
    ],
    [tenantNameOf],
  )

  return (
    <>
      <DetailSubTitle>历史变更记录（最近 20 条）</DetailSubTitle>
      {error !== null ? (
        <Alert type="error" showIcon message="变更记录加载失败" description={error} />
      ) : (
        <Table<PlatformAgentChange>
          rowKey="changeId"
          size="small"
          loading={loading}
          dataSource={changes}
          columns={columns}
          pagination={false}
          scroll={{ x: 860 }}
          locale={{ emptyText: loading ? '加载中…' : '暂无变更记录' }}
          onRow={(row) => ({
            // ⚠️ 双击有「目标版本」的记录 → 打开该版本与上一版本的对比。
            //   无目标版本（建行/分配租户等审计动作）不跳转，避免打开空 Diff。
            onDoubleClick: () => {
              if (row.targetVersion != null && onOpenVersionDiff) {
                onOpenVersionDiff(row.targetVersion)
              }
            },
            style: row.targetVersion != null ? { cursor: 'pointer' } : undefined,
          })}
        />
      )}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        仅展示定义发布 / 生命周期 / 租户分配等管理动作元数据；定义正文、运行输出与会话内容不出平台边界。
      </Typography.Text>
    </>
  )
}

/* ── 小工具 ───────────────────────────────────────────────────── */

/** code 文本清单（空如实呈现「（无）」，不得回落成看空的占位）。 */
function codeList(list: string[]): ReactNode {
  if (list.length === 0) return <Typography.Text type="secondary">（无）</Typography.Text>
  return list.map((v) => (
    <Typography.Text key={v} code style={{ marginRight: 8 }}>{v}</Typography.Text>
  ))
}

/** 已登记项 + 悬空引用分栏（缺失项红色，必须与「未声明」分得开）。 */
function RelationList({ found, missing }: { found: string[]; missing: string[] }): ReactNode {
  if (found.length === 0 && missing.length === 0) {
    return <Typography.Text type="secondary">（无）</Typography.Text>
  }
  return (
    <>
      {found.map((v) => (
        <Typography.Text key={v} code style={{ marginRight: 8 }}>{v}</Typography.Text>
      ))}
      {missing.map((v) => (
        <Typography.Text key={v} type="danger" code style={{ marginRight: 8 }}>
          {v}（悬空引用 · 对象不存在）
        </Typography.Text>
      ))}
    </>
  )
}
