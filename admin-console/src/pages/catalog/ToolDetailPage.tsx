import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  Alert,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd'
import { ToolOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { getMcp, getTool, getToolRelations } from '@/api/adminApi'
import type {
  McpCatalogItem,
  PlatformPublishRequest,
  PlatformToolSpec,
  ToolCatalogItem,
  ToolRelations,
} from '@/api/types'
import { PlatformDetailShell, RelationsEmpty } from './PlatformDetailShell'
import { PlatformPublishPanel } from './PlatformPublishPanel'
import { PlatformDeprecateCard } from './PlatformDeprecateCard'
import {
  buildToolSpec,
  credentialRefLossBlocker,
  credentialRefLossRisk,
  mcpCatalogToSpec,
  NEW_OBJECT_SEGMENT,
  type ToolFormValues,
} from './platformObjectForm'
import { riskTag } from './displayBits'

/**
 * Tool 详情（第三十四批 · C34-3 · 平台级写通道）。
 *
 * <p><b>⚠️ 已发布行不可变</b>：本页的「修改」= **发一个新版本**，
 * 不是原地编辑；「删除」= **停用**（DEPRECATED），物理删除不做。
 *
 * <p><b>⚠️ 平台级发布必须构成自洽闭包</b>（后端 `ConfigValidationGate`）：
 * {@code kind=MCP} 的 Tool 绑定的 Server 必须**出现在同一次请求**里，
 * 否则后端以 {@code MCP_NOT_BOUND} 拒。所以本页会自己去把那个 Server 的
 * 当前定义读出来一并纳入发布集合。
 */
export function ToolDetailPage(): JSX.Element {
  const { id = '' } = useParams<{ id: string }>()
  const isRegistration = id === NEW_OBJECT_SEGMENT

  const [tool, setTool] = useState<ToolCatalogItem | null>(null)
  const [relations, setRelations] = useState<ToolRelations | null>(null)
  const [loading, setLoading] = useState(!isRegistration)
  const [missing, setMissing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form] = Form.useForm<ToolFormValues & { reason: string; expectedPublishSeq?: number }>()

  const load = useCallback(async () => {
    if (isRegistration) return
    setLoading(true)
    try {
      const [t, r] = await Promise.all([getTool(id), getToolRelations(id)])
      setTool(t)
      setRelations(r)
      setMissing(false)
      setError(null)
      // ⚠️ 回填用**目录当前值**（这就是要发布的新一版的基线）。
      form.setFieldsValue({
        toolId: t.toolId,
        name: t.name ?? '',
        kind: t.kind === 'MCP' ? 'MCP' : 'NATIVE',
        riskLevel: t.riskLevel as ToolFormValues['riskLevel'],
        mcpServerId: t.mcpServerId ?? '',
        mcpToolName: t.mcpToolName ?? '',
        inputSchema: t.inputSchema ?? '{}',
        paramPolicies: t.paramPolicies ?? '{}',
      })
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setMissing(true)
      } else {
        setError(e instanceof Error ? e.message : '加载 Tool 详情失败')
      }
    } finally {
      setLoading(false)
    }
  }, [id, isRegistration, form])

  useEffect(() => {
    void load()
  }, [load])

  const kind = Form.useWatch('kind', form) as ToolFormValues['kind'] | undefined

  /**
   * 构造发布申请体。
   *
   * ⚠️ **闭包补齐**：kind=MCP 时把绑定的 Server 一并纳入本次发布集合 ——
   * 后端要求平台级发布集合自洽，否则以 MCP_NOT_BOUND 拒（400）。
   */
  const buildRequest = async (
    values: ToolFormValues & { reason: string; expectedPublishSeq?: number },
  ): Promise<PlatformPublishRequest | { error: string }> => {
    const built = buildToolSpec(values)
    if (!built.ok) return { error: built.error }

    const body: PlatformPublishRequest = {
      tools: [built.spec as PlatformToolSpec],
      reason: values.reason.trim(),
    }
    if (values.expectedPublishSeq !== undefined && values.expectedPublishSeq !== null) {
      body.expectedPublishSeq = values.expectedPublishSeq
    }

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

  const relationsNode = isRegistration ? (
    <RelationsEmpty text="登记模式：对象尚不存在，无既有关系" />
  ) : relations?.mcpServer ? (
    <Descriptions bordered column={2} size="small" items={[
      { key: 'server', label: '所属 MCP Server', children: <Typography.Text code>{relations.mcpServer.serverId}</Typography.Text> },
      { key: 'transport', label: '传输', children: <Tag>{relations.mcpServer.transport}</Tag> },
      { key: 'trust', label: '信任分级', children: <Tag>{relations.mcpServer.trustTier}</Tag> },
      { key: 'risk', label: '连接风险', children: riskTag(relations.mcpServer.connectionRisk) },
      { key: 'version', label: 'Server 版本', children: <Tag color="blue">v{relations.mcpServer.version}</Tag> },
      {
        key: 'published',
        label: 'Server 状态',
        children: relations.mcpServer.published ? <Tag color="green">已发布</Tag> : <Tag>未发布</Tag>,
      },
    ]} />
  ) : (
    <Space direction="vertical" size="small" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="内置 Tool（不绑定任何 MCP Server）"
        description="kind=NATIVE 的 Tool 不属于任何 MCP Server —— 这是合法状态，不是「查不到」。"
      />
    </Space>
  )

  return (
    <PlatformDetailShell
      title={<><ToolOutlined /> {isRegistration ? '登记 Tool' : (tool?.name ?? tool?.toolId ?? 'Tool')}</>}
      subtitle={<Typography.Text type="secondary" code>{isRegistration ? '（新对象）' : id}</Typography.Text>}
      loading={loading}
      missing={missing}
      error={error}
      backTo="/tools"
      backLabel="Tool 目录"
      overview={
        isRegistration ? (
          <Alert
            type="info"
            showIcon
            message="登记新 Tool"
            description="平台级 Tool 全平台唯一。提交后走「申请 → 审批 → 执行」，申请阶段不写配置表。"
          />
        ) : (
          <Descriptions bordered column={2} size="small" items={[
            { key: 'id', label: '标识', children: <Typography.Text code>{tool?.toolId ?? id}</Typography.Text> },
            { key: 'name', label: '名称', children: tool?.name ?? '-' },
            { key: 'kind', label: '类型', children: <Tag>{tool?.kind}</Tag> },
            { key: 'risk', label: '风险等级', children: riskTag(tool?.riskLevel ?? '') },
            {
              key: 'mcp',
              label: 'MCP 绑定',
              children: tool?.mcpServerId
                ? <Typography.Text code>{tool.mcpServerId}::{tool.mcpToolName ?? '?'}</Typography.Text>
                : <Typography.Text type="secondary">内置</Typography.Text>,
            },
            { key: 'version', label: '当前版本', children: <Tag color="blue">v{tool?.version ?? 0}</Tag> },
            {
              key: 'published',
              label: '发布状态',
              children: tool?.published ? <Tag color="green">已发布</Tag> : <Tag color="orange">未发布</Tag>,
            },
            { key: 'publishedAt', label: '发布时间', children: tool?.publishedAt ?? '-' },
          ]} />
        )
      }
      relations={relationsNode}
      actions={
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <PlatformPublishPanel<ToolFormValues & { reason: string; expectedPublishSeq?: number }>
            form={form}
            buildRequest={buildRequest}
            title={isRegistration ? '登记 Tool（三步工作流）' : '修改 Tool（发新版本 · 三步工作流）'}
            submitText={isRegistration ? '提交登记申请' : '提交新版本申请'}
            renderFields={() => (
              <>
                <Form.Item
                  name="toolId"
                  label="Tool 标识"
                  rules={[{ required: true, message: '请输入 Tool 标识' }]}
                  extra={isRegistration ? '平台级对象全平台唯一，标识一经提交不可改' : '标识不可改：改标识等于登记另一个对象'}
                >
                  <Input disabled={!isRegistration} placeholder="如 order-query" />
                </Form.Item>
                <Form.Item name="name" label="名称">
                  <Input placeholder="可读名称（可选）" />
                </Form.Item>
                <Form.Item name="kind" label="类型（kind）" rules={[{ required: true }]}>
                  <Select
                    options={[
                      { value: 'NATIVE', label: 'NATIVE（内置，不绑定 MCP Server）' },
                      { value: 'MCP', label: 'MCP（绑定到某个 MCP Server）' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="riskLevel" label="风险等级（riskLevel）" rules={[{ required: true }]}>
                  <Select
                    options={[
                      { value: 'READ', label: 'READ（只读）' },
                      { value: 'WRITE_PROFILE', label: 'WRITE_PROFILE（内部写）' },
                      { value: 'WRITE_EXTERNAL', label: 'WRITE_EXTERNAL（外部写）' },
                    ]}
                  />
                </Form.Item>
                {kind === 'MCP' && (
                  <>
                    <Form.Item
                      name="mcpServerId"
                      label="所属 MCP Server"
                      rules={[{ required: true, message: 'kind=MCP 时必须填写所属 MCP Server' }]}
                      extra="⚠️ 发布集合必须自洽闭包：该 Server 会被一并纳入本次发布"
                    >
                      <Input placeholder="MCP Server 标识" />
                    </Form.Item>
                    <Form.Item
                      name="mcpToolName"
                      label="远端操作名（mcpToolName）"
                      rules={[{ required: true, message: 'kind=MCP 时必须填写远端操作名' }]}
                    >
                      <Input placeholder="该 Server 暴露的远端操作名" />
                    </Form.Item>
                  </>
                )}
                <Form.Item
                  name="inputSchema"
                  label="入参结构（inputSchema · JSON）"
                  extra="缺省 {} 表示无结构约束（不代表允许任意参数）"
                >
                  <Input.TextArea rows={4} style={{ fontFamily: 'monospace', fontSize: 12 }} />
                </Form.Item>
                <Form.Item name="paramPolicies" label="参数策略（paramPolicies · JSON）">
                  <Input.TextArea rows={3} style={{ fontFamily: 'monospace', fontSize: 12 }} />
                </Form.Item>
                <Form.Item
                  name="expectedPublishSeq"
                  label="发布基线（expectedPublishSeq · 可选）"
                  extra="填入你读到的当前发布序号；不一致时后端 409（防止覆盖别人改过的配置）"
                >
                  <InputNumber min={0} precision={0} style={{ width: 200 }} />
                </Form.Item>
              </>
            )}
          />
          {!isRegistration && tool && (
            <PlatformDeprecateCard
              type="tool"
              objectId={tool.toolId}
              version={tool.version}
              lifecycleState={tool.lifecycleState}
            />
          )}
        </Space>
      }
    />
  )
}

export default ToolDetailPage
