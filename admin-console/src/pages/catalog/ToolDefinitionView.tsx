import type { ReactNode } from 'react'
import { Descriptions, Space, Tag, Typography } from 'antd'
import type { ToolCatalogItem } from '@/api/types'
import { lifecycleTag, riskTag } from './displayBits'

/**
 * Tool 定义的**全量信息视图**（登记 / 修改 / 详情三处共用）。
 *
 * <p><b>⚠️ 为什么要抽成组件</b>：Tool 的字段散在三处页面上 ——
 * 概览（详情）、草稿表单（登记 / 修改）、关系区。三处各写一份，
 * 必然出现「详情里有、修改里没有」的漂移；而 Tool 的字段是**权限语义**
 * （风险等级 / 远端操作名 / 入参结构），少显示一个字段就等于
 * 让操作者在**信息不全**的状态下改动全平台配置。
 *
 * <p><b>⚠️ 只呈现，不得归一化</b>：JSON 原文一律原样展示（只做**格式化**，
 * 不做语义改写）；空值一律如实呈现为「（空）」而不是「看起来像有值」。
 */

/**
 * JSON 字段的呈现。
 *
 * <p>⚠️ **解析失败必须原样展示，不得吞掉内容** —— 一个解析不了的
 * 入参结构恰恰是最该被看见的东西（照抄进草稿 = 把坏配置复制一份）。
 */
export function JsonBlock({ value }: { value: string | null | undefined }): ReactNode {
  const raw = (value ?? '').trim()
  if (raw === '') return <Typography.Text type="secondary">（空）</Typography.Text>
  let body = raw
  let broken = false
  try {
    body = JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    broken = true
  }
  return (
    <Space direction="vertical" size={4} style={{ width: '100%' }}>
      {broken && (
        <Tag color="red" style={{ alignSelf: 'flex-start' }}>
          ⚠️ 不是合法 JSON（原样展示）
        </Tag>
      )}
      <pre
        style={{
          margin: 0,
          maxHeight: 320,
          overflow: 'auto',
          fontSize: 12,
          fontFamily: 'monospace',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
        }}
      >
        {body}
      </pre>
    </Space>
  )
}

/** 空值占位（与「有值」必须分得开）。 */
function orDash(text: string | null | undefined): ReactNode {
  const v = (text ?? '').trim()
  return v === '' ? <Typography.Text type="secondary">-</Typography.Text> : v
}

/**
 * Tool 定义字段（可改版本的那一组）。
 *
 * <p>⚠️ {@code kind=NATIVE} 时**不存在** MCP 绑定字段（DB 有 CHECK 兜底），
 * 故此处如实呈现「内置」而不是留空让人以为「忘了填」。
 */
export function toolDefinitionItems(tool: ToolCatalogItem): {
  key: string
  label: string
  children: ReactNode
  span?: number
}[] {
  const isMcp = tool.kind === 'MCP'
  const isHttp = tool.kind === 'HTTP'
  return [
    { key: 'toolId', label: '标识（toolId）', children: <Typography.Text code>{tool.toolId}</Typography.Text> },
    { key: 'name', label: '名称（name）', children: orDash(tool.name) },
    {
      key: 'kind',
      label: '类型（kind）',
      children: (
        <Tag color={isMcp ? 'purple' : isHttp ? 'blue' : 'default'}>{tool.kind}</Tag>
      ),
    },
    { key: 'riskLevel', label: '风险等级（riskLevel）', children: riskTag(tool.riskLevel) },
    {
      key: 'mcpServerId',
      label: '所属 MCP Server（mcpServerId）',
      children: isMcp && tool.mcpServerId ? (
        <Typography.Text code>{tool.mcpServerId}</Typography.Text>
      ) : (
        <Typography.Text type="secondary">
          {isHttp ? '不适用（远程 HTTP 请求直接打配置里的 URL）' : '内置（不绑定 MCP Server）'}
        </Typography.Text>
      ),
    },
    {
      key: 'mcpToolName',
      label: '远端操作名（mcpToolName）',
      children: isMcp && tool.mcpToolName ? (
        <Typography.Text code>{tool.mcpToolName}</Typography.Text>
      ) : (
        <Typography.Text type="secondary">-</Typography.Text>
      ),
    },
    // ⚠️ **HTTP 专属字段必须如实呈现**（第三十七批）：目标 URL 是这个 Tool 的
    //    行为定义 —— 不回显它，运维就无从判断「这个 Tool 到底打哪里」。
    {
      key: 'httpMethod',
      label: '请求方法（httpMethod）',
      children: isHttp ? (
        <Typography.Text code>{tool.httpMethod ?? '未提供'}</Typography.Text>
      ) : (
        <Typography.Text type="secondary">-</Typography.Text>
      ),
    },
    {
      key: 'httpUrl',
      label: '目标 URL（httpUrl）',
      children: isHttp ? (
        <Typography.Text code ellipsis style={{ maxWidth: 320 }}>
          {tool.httpUrl ?? '未提供'}
        </Typography.Text>
      ) : (
        <Typography.Text type="secondary">-</Typography.Text>
      ),
    },
    {
      key: 'httpTimeoutMs',
      label: '超时（httpTimeoutMs）',
      children: isHttp ? (
        <Tag>{tool.httpTimeoutMs ?? '未提供'} 毫秒</Tag>
      ) : (
        <Typography.Text type="secondary">-</Typography.Text>
      ),
    },
    {
      key: 'inputSchema',
      label: '入参结构（inputSchema）',
      children: <JsonBlock value={tool.inputSchema} />,
      span: 2,
    },
    {
      key: 'paramPolicies',
      label: '参数策略（paramPolicies）',
      children: <JsonBlock value={tool.paramPolicies} />,
      span: 2,
    },
  ]
}

/** Tool 版本与生命周期元信息（不可改的那一组）。 */
export function toolMetadataItems(tool: ToolCatalogItem): {
  key: string
  label: string
  children: ReactNode
  span?: number
}[] {
  return [
    { key: 'version', label: '当前版本（version）', children: <Tag color="blue">v{tool.version}</Tag> },
    { key: 'schemaVersion', label: '结构版本（schemaVersion）', children: tool.schemaVersion },
    {
      key: 'published',
      label: '发布状态（published）',
      children: tool.published ? <Tag color="green">已发布</Tag> : <Tag color="orange">未发布</Tag>,
    },
    { key: 'publishedAt', label: '发布时间（publishedAt）', children: orDash(tool.publishedAt) },
    { key: 'lifecycleState', label: '生命周期状态（lifecycleState）', children: lifecycleTag(tool.lifecycleState) },
    {
      key: 'configFingerprint',
      label: '配置指纹（configFingerprint）',
      children: tool.configFingerprint ? (
        <Typography.Text code>{tool.configFingerprint}</Typography.Text>
      ) : (
        <Typography.Text type="secondary">未提供</Typography.Text>
      ),
    },
    { key: 'createdAt', label: '创建时间（createdAt）', children: orDash(tool.createdAt) },
  ]
}

/**
 * Tool 的**全量信息**（定义字段 + 版本/生命周期元信息）。
 *
 * <p>⚠️ 登记模式（对象尚不存在）**不渲染本组件** —— 没有对象就没有
 * 「当前信息」，凭空造一份等于骗人。
 */
export function ToolFullInfo({ tool }: { tool: ToolCatalogItem }): JSX.Element {
  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Descriptions
        bordered
        column={2}
        size="small"
        title="定义字段"
        items={toolDefinitionItems(tool)}
      />
      <Descriptions
        bordered
        column={2}
        size="small"
        title="版本与生命周期"
        items={toolMetadataItems(tool)}
      />
    </Space>
  )
}

/**
 * Tool 的**字段全览**（登记模式下用：对象尚不存在，但作者必须知道
 * 「一个 Tool 到底有哪些字段、哪些要填、哪些由平台生成」）。
 *
 * <p>⚠️ **必须如实区分「作者填的」与「平台生成的」**：
 * 把 {@code version} / {@code published} 之类的平台生成字段做成可编辑，
 * 会让人以为改它们有意义；而把「作者要填的」漏掉，则等于让人
 * 凭空猜字段。
 */
export function ToolFieldReference(): JSX.Element {
  return (
    <Descriptions
      bordered
      column={1}
      size="small"
      items={[
        {
          key: 'toolId',
          label: 'toolId（标识）',
          children: '平台级唯一标识；登记后不可改（改标识等于登记另一个对象）。',
        },
        { key: 'name', label: 'name（名称）', children: '可读名称，可选。' },
        {
          key: 'kind',
          label: 'kind（类型）',
          children:
            'NATIVE = 内置（不绑定任何 MCP Server）；MCP = 绑定到某个 Server。' +
            '与 mcpServerId / mcpToolName **成对互斥**（只填一半会被门禁拒）。',
        },
        {
          key: 'riskLevel',
          label: 'riskLevel（风险等级）',
          children:
            'READ / WRITE_PROFILE / WRITE_EXTERNAL。⚠️ 没有「未知」这一档 —— ' +
            '判不出风险就是「不可判定」，门禁会以 RISK_LEVEL_UNKNOWN 拒。',
        },
        {
          key: 'mcpServerId',
          label: 'mcpServerId（所属 MCP Server）',
          children: '仅 kind=MCP 时必填。该 Server 会被一并纳入本次发布集合（自洽闭包）。',
        },
        {
          key: 'mcpToolName',
          label: 'mcpToolName（远端操作名）',
          children: '仅 kind=MCP 时必填：该 Server 暴露的远端操作名。',
        },
        {
          key: 'inputSchema',
          label: 'inputSchema（入参结构）',
          children: 'JSON。缺省 {} 表示无结构约束 —— **不代表允许任意参数**。',
        },
        {
          key: 'paramPolicies',
          label: 'paramPolicies（参数策略）',
          children: 'JSON。参数级策略（取值范围、敏感字段处理等）。',
        },
        {
          key: 'version',
          label: 'version（当前版本）',
          children: '平台生成：每次改版 +1。已发布行不可变（DB 触发器）。',
        },
        { key: 'schemaVersion', label: 'schemaVersion（结构版本）', children: '平台生成：定义结构的版本号。' },
        {
          key: 'published',
          label: 'published（发布状态）',
          children: '平台生成：提交申请只生成申请单，执行第三步后才变「已发布」。',
        },
        { key: 'publishedAt', label: 'publishedAt（发布时间）', children: '平台生成。' },
        {
          key: 'lifecycleState',
          label: 'lifecycleState（生命周期状态）',
          children: '平台生成：DRAFT / VALIDATED / PUBLISHED / DEPRECATED。停用是单向终态。',
        },
        {
          key: 'configFingerprint',
          label: 'configFingerprint（配置指纹）',
          children: '平台生成：该版本定义内容的指纹 —— 用它比对「到底改过没有」。',
        },
        { key: 'createdAt', label: 'createdAt（创建时间）', children: '平台生成：版本行落库时间。' },
      ]}
    />
  )
}

export default ToolFullInfo
