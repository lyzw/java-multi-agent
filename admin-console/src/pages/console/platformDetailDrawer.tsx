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
 * 详情直接用**行内已有字段**渲染，不发任何请求。
 */

import { useEffect, useState, type ReactNode } from 'react'
import { Alert, Descriptions, Divider, Drawer, Space, Spin, Tag, Typography } from 'antd'
import {
  getConsoleMcp,
  getConsoleMcpRelations,
  getConsoleSkill,
  getConsoleSkillRelations,
  getConsoleTool,
  getConsoleToolRelations,
} from '@/api/adminApi'
import type {
  PlatformAgentRegistryItem,
  ToolCatalogItem,
  ToolRelations,
} from '@/api/types'
import { ApiError } from '@/api/client'
import { JsonBlock, ToolFullInfo } from '@/pages/catalog/ToolDefinitionView'
import { allowlistCell, lifecycleTag, riskTag, skillDescriptionView, timeCell } from '@/pages/catalog/displayBits'

/** 详情抽屉骨架（开合与标题由调用页持有；内容组件自己取数）。 */
export function CatalogDetailDrawer({
  title,
  open,
  onClose,
  children,
}: {
  title: ReactNode
  open: boolean
  onClose: () => void
  children: ReactNode
}): JSX.Element {
  return (
    <Drawer open={open} onClose={onClose} title={title} width={760} destroyOnHidden>
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

          <DetailSubTitle>描述（1级用途 + 2级使用指引）</DetailSubTitle>
          {/* 两级描述（1级 purpose + 2级 usage）：⚠️ 描述不是授权 */}
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

/* ── Agent（只读盘点 · 无单条端点）────────────────────────────── */

/**
 * Agent 双击详情：只渲染**行内已有**的管理元数据（不发任何请求）。
 *
 * <p>⚠️ Agent 是租户私有的，平台只有跨租户登记表盘点端点（无单条详情端点）；
 * 定义正文 / 运行输出 / 会话内容不下探 —— 这不是「查不到」，是禁区。
 */
export function AgentRegistryDetail({ item }: { item: PlatformAgentRegistryItem }): JSX.Element {
  return (
    <>
      <Descriptions
        bordered
        column={2}
        size="small"
        items={[
          { key: 'tenant', label: '租户', children: <Typography.Text code>{item.tenantId}</Typography.Text> },
          { key: 'id', label: 'Agent 标识', children: <Typography.Text code>{item.agentId}</Typography.Text> },
          { key: 'name', label: '名称', children: item.name ?? '-' },
          { key: 'desc', label: '描述', children: item.description ?? '-', span: 2 },
          { key: 'state', label: '生命周期', children: lifecycleTag(item.lifecycleState) },
          { key: 'defVersion', label: '定义版本', children: item.defVersion === null ? <Typography.Text type="secondary">无定义</Typography.Text> : <Typography.Text>v{item.defVersion}</Typography.Text> },
          {
            key: 'version',
            label: '发布版本',
            children: item.versionCount === 0
              ? <Typography.Text type="secondary">尚无版本</Typography.Text>
              : <Typography.Text>v{item.latestVersionNumber} · 共 {item.versionCount}</Typography.Text>,
          },
          { key: 'publishedAt', label: '最近发布', children: timeCell(item.latestPublishedAt) },
          { key: 'roles', label: '角色', children: codeList(item.roles) },
          { key: 'skills', label: '启用技能', children: codeList(item.enabledSkills) },
          { key: 'tags', label: '能力标签', children: codeList(item.capabilityTags) },
          { key: 'overrides', label: '收紧覆盖', children: item.hasOverrides ? '有覆盖' : <Typography.Text type="secondary">无</Typography.Text> },
        ]}
      />
      <Alert
        type="info"
        showIcon
        style={{ marginTop: 16 }}
        message="跨租户只读盘点"
        description="Agent 的定义正文与运行输出不跨租户出平台边界（禁区红线）；本详情只呈现管理元数据，缺失字段按「未提供」如实呈现。"
      />
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
