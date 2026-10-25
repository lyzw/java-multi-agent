import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  Alert,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import { ApiOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { getMcp, getMcpRelations } from '@/api/adminApi'
import type {
  McpCatalogItem,
  McpRelations,
  PlatformPublishRequest,
  ToolSummary,
} from '@/api/types'
import { PlatformDetailShell, RelationsEmpty } from './PlatformDetailShell'
import { PlatformPublishPanel } from './PlatformPublishPanel'
import { PlatformDeprecateCard } from './PlatformDeprecateCard'
import {
  buildMcpServerSpec,
  NEW_OBJECT_SEGMENT,
  toAllowlistTriState,
  type McpServerFormValues,
} from './platformObjectForm'
import { allowlistCell, riskTag } from './displayBits'

/** 暴露操作清单的三态选项（**不可**简化成二态：null 与 [] 语义不同）。 */
const EXPOSED_STATE_OPTIONS = [
  { value: 'UNSET', label: '未配置（取全集）' },
  { value: 'EMPTY', label: '显式空（∅ · 不允许任何操作）' },
  { value: 'LIST', label: '指定清单' },
]

/**
 * MCP Server 详情（第三十四批 · C34-3 · 平台级写通道）。
 *
 * <p><b>⚠️ 「允许集」与「实际登记」是两回事</b>：
 * {@code exposedToolAllowlist} 是「这个 Server <b>允许</b>哪些远端操作」，
 * 而关系视图里的 {@code tools} 是「<b>实际登记</b>在它上面的 Tool 定义」。
 * 两者可以不一致（允许集更宽、登记更窄）—— 合并成一个列表会让运维
 * 把「没登记」误读成「不允许」。
 *
 * <p><b>⚠️ 凭据仅存引用</b>：目录接口只回显「是否登记了凭据引用」这个布尔，
 * 从不回显定位符（§4.2 / P5-T3）。所以「改一个别的字段」也可能把凭据引用丢掉
 * —— 本页因此把凭据引用做成**显式输入项**，并在已有凭据引用时给出提醒。
 */
export function McpDetailPage(): JSX.Element {
  const { id = '' } = useParams<{ id: string }>()
  const isRegistration = id === NEW_OBJECT_SEGMENT

  const [server, setServer] = useState<McpCatalogItem | null>(null)
  const [relations, setRelations] = useState<McpRelations | null>(null)
  const [loading, setLoading] = useState(!isRegistration)
  const [missing, setMissing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form] = Form.useForm<McpServerFormValues & { reason: string; expectedPublishSeq?: number }>()

  const load = useCallback(async () => {
    if (isRegistration) return
    setLoading(true)
    try {
      const [s, r] = await Promise.all([getMcp(id), getMcpRelations(id)])
      setServer(s)
      setRelations(r)
      setMissing(false)
      setError(null)
      form.setFieldsValue({
        serverId: s.serverId,
        transport: s.transport === 'SSE' ? 'SSE' : 'HTTP',
        endpoint: s.endpoint ?? '',
        command: '',
        authKind: s.authKind as McpServerFormValues['authKind'],
        trustTier: s.trustTier as McpServerFormValues['trustTier'],
        // ⚠️ 回填时按三态还原：null ≠ []，不能都塞成 []。
        exposedAllowlistState: toAllowlistTriState(s.exposedToolAllowlist),
        exposedAllowlist: s.exposedToolAllowlist ?? [],
        connectionRisk: s.connectionRisk as McpServerFormValues['connectionRisk'],
        credentialRef: '',
      })
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setMissing(true)
      } else {
        setError(e instanceof Error ? e.message : '加载 MCP Server 详情失败')
      }
    } finally {
      setLoading(false)
    }
  }, [id, isRegistration, form])

  useEffect(() => {
    void load()
  }, [load])

  const exposedState = Form.useWatch('exposedAllowlistState', form) as
    | McpServerFormValues['exposedAllowlistState']
    | undefined

  const buildRequest = (
    values: McpServerFormValues & { reason: string; expectedPublishSeq?: number },
  ): PlatformPublishRequest | { error: string } => {
    const built = buildMcpServerSpec(values)
    if (!built.ok) return { error: built.error }
    const body: PlatformPublishRequest = {
      mcpServers: [built.spec],
      reason: values.reason.trim(),
    }
    if (values.expectedPublishSeq !== undefined && values.expectedPublishSeq !== null) {
      body.expectedPublishSeq = values.expectedPublishSeq
    }
    return body
  }

  const relationsNode = isRegistration ? (
    <RelationsEmpty text="登记模式：对象尚不存在，无既有关系" />
  ) : (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card size="small" title="暴露操作（允许集 · exposedToolAllowlist）">
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="允许集 ≠ 实际登记"
          description="这里是「这个 Server 允许哪些远端操作」；下方是「实际登记在它上面的 Tool 定义」。两者可以不一致。"
        />
        {relations ? allowlistCell(relations.exposedToolAllowlist) : '-'}
      </Card>

      <Card size="small" title={`实际登记的 Tool（${relations?.tools.length ?? 0}）`}>
        {relations && relations.tools.length > 0 ? (
          <Table<ToolSummary>
            rowKey="toolId"
            size="small"
            pagination={false}
            dataSource={relations.tools}
            columns={[
              { title: 'ID', dataIndex: 'toolId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
              { title: '名称', dataIndex: 'name', render: (v?: string) => v ?? '-' },
              { title: '类型', dataIndex: 'kind', render: (v: string) => <Tag>{v}</Tag> },
              { title: '风险', dataIndex: 'riskLevel', render: (v: string) => riskTag(v) },
              { title: '版本', dataIndex: 'version', render: (v: number) => <Tag color="blue">v{v}</Tag> },
            ]}
          />
        ) : (
          <RelationsEmpty text="没有 Tool 登记在该 Server 上（不等于「不允许」—— 允许集见上方）" />
        )}
      </Card>
    </Space>
  )

  return (
    <PlatformDetailShell
      title={<><ApiOutlined /> {isRegistration ? '登记 MCP Server' : (server?.serverId ?? 'MCP Server')}</>}
      subtitle={<Typography.Text type="secondary" code>{isRegistration ? '（新对象）' : id}</Typography.Text>}
      loading={loading}
      missing={missing}
      error={error}
      backTo="/mcp"
      backLabel="MCP 目录"
      overview={
        isRegistration ? (
          <Alert
            type="info"
            showIcon
            message="登记新 MCP Server"
            description="平台级 MCP Server 全平台唯一。凭据仅存引用（定位符），凭据材料永不入库。提交后走「申请 → 审批 → 执行」。"
          />
        ) : (
          <Descriptions bordered column={2} size="small" items={[
            { key: 'id', label: '标识', children: <Typography.Text code>{server?.serverId ?? id}</Typography.Text> },
            {
              key: 'transport',
              label: '传输',
              children: <Tag color={server?.transport === 'HTTP' ? 'blue' : 'cyan'}>{server?.transport}</Tag>,
            },
            {
              key: 'endpoint',
              label: 'Endpoint',
              children: server?.endpoint
                ? <Typography.Text code ellipsis style={{ maxWidth: 260 }}>{server.endpoint}</Typography.Text>
                : <Typography.Text type="secondary">（本地命令）</Typography.Text>,
            },
            { key: 'auth', label: '认证', children: <Tag>{server?.authKind}</Tag> },
            { key: 'trust', label: '信任分级', children: <Tag color={server?.trustTier === 'INTERNAL' ? 'green' : 'orange'}>{server?.trustTier}</Tag> },
            { key: 'risk', label: '连接风险', children: riskTag(server?.connectionRisk ?? '') },
            { key: 'version', label: '当前版本', children: <Tag color="blue">v{server?.version ?? 0}</Tag> },
            {
              key: 'published',
              label: '发布时间',
              children: server?.publishedAt ?? '-',
            },
            {
              key: 'credential',
              label: '凭据引用',
              children: server?.hasCredentialRef
                ? <Tag color="gold">已登记（定位符不回显）</Tag>
                : <Tag>未登记</Tag>,
            },
          ]} />
        )
      }
      relations={relationsNode}
      actions={
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <PlatformPublishPanel<McpServerFormValues & { reason: string; expectedPublishSeq?: number }>
            form={form}
            buildRequest={buildRequest}
            title={isRegistration ? '登记 MCP Server（三步工作流）' : '修改 MCP Server（发新版本 · 三步工作流）'}
            submitText={isRegistration ? '提交登记申请' : '提交新版本申请'}
            renderFields={() => (
              <>
                <Form.Item
                  name="serverId"
                  label="Server 标识"
                  rules={[{ required: true, message: '请输入 MCP Server 标识' }]}
                  extra={isRegistration ? '平台级对象全平台唯一' : '标识不可改：改标识等于登记另一个对象'}
                >
                  <Input disabled={!isRegistration} placeholder="如 order-mcp" />
                </Form.Item>
                <Form.Item name="transport" label="传输（transport）" rules={[{ required: true }]}>
                  <Select
                    options={[
                      { value: 'HTTP', label: 'HTTP' },
                      { value: 'SSE', label: 'SSE' },
                    ]}
                  />
                </Form.Item>
                <Form.Item
                  name="endpoint"
                  label="Endpoint"
                  rules={[{ required: true, message: 'http / sse 传输必须配置 endpoint' }]}
                  extra="仅允许 http / https；不得内嵌用户名 / 口令（凭据仅引用）"
                >
                  <Input placeholder="https://example.internal/mcp" />
                </Form.Item>
                <Form.Item name="command" label="启动命令（command · 可选）">
                  <Input placeholder="stdio 预留字段（V1 不启用 stdio 传输）" />
                </Form.Item>
                <Form.Item name="authKind" label="认证方式（authKind）" rules={[{ required: true }]}>
                  <Select
                    options={[
                      { value: 'TOKEN', label: 'TOKEN' },
                      { value: 'OAUTH', label: 'OAUTH' },
                      { value: 'NONE', label: 'NONE' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="trustTier" label="信任分级（trustTier）" rules={[{ required: true }]}>
                  <Select
                    options={[
                      { value: 'INTERNAL', label: 'INTERNAL' },
                      { value: 'PARTNER', label: 'PARTNER' },
                      { value: 'PUBLIC', label: 'PUBLIC' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="connectionRisk" label="连接风险（connectionRisk）" rules={[{ required: true }]}>
                  <Select
                    options={[
                      { value: 'READ', label: 'READ（只读）' },
                      { value: 'WRITE_PROFILE', label: 'WRITE_PROFILE（内部写）' },
                      { value: 'WRITE_EXTERNAL', label: 'WRITE_EXTERNAL（外部写）' },
                    ]}
                  />
                </Form.Item>
                <Form.Item
                  name="exposedAllowlistState"
                  label="暴露操作（exposedToolAllowlist · 三态）"
                  extra="null=未配置（取全集） / []=显式不允许任何操作 / 清单=指定"
                  initialValue="UNSET"
                >
                  <Select options={EXPOSED_STATE_OPTIONS} />
                </Form.Item>
                {exposedState === 'LIST' && (
                  <Form.Item
                    name="exposedAllowlist"
                    label="暴露操作清单"
                    rules={[{ required: true, message: '指定清单不能为空（请改选「显式空」）' }]}
                  >
                    <Select mode="tags" placeholder="远端操作名（回车分隔）" tokenSeparators={[',']} />
                  </Form.Item>
                )}
                <Form.Item
                  name="credentialRef"
                  label="凭据引用（credentialRef · 可选）"
                  extra="凭据库定位符（不是凭据材料）。⚠️ 目录接口从不回显它 —— 不重新填写则新版本不带凭据引用"
                >
                  <Input placeholder="如 secret://vault/order-mcp" />
                </Form.Item>
                {!isRegistration && server?.hasCredentialRef && (
                  <Alert
                    type="warning"
                    showIcon
                    style={{ marginBottom: 16 }}
                    message="该 Server 已登记凭据引用，但目录接口不回显定位符"
                    description="若本次发布不重新填写「凭据引用」，新版本将**不带**凭据引用 —— 运行时连接会因此失败。请一并填写。"
                  />
                )}
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
          {!isRegistration && server && (
            <PlatformDeprecateCard
              type="mcp_server"
              objectId={server.serverId}
              version={server.version}
              lifecycleState={server.lifecycleState}
            />
          )}
        </Space>
      }
    />
  )
}

export default McpDetailPage
