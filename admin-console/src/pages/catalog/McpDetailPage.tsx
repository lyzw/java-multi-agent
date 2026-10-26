import { useCallback, useEffect, useState } from 'react'
import { Navigate, useParams } from 'react-router-dom'
import {
  Alert,
  Card,
  Descriptions,
  Form,
  InputNumber,
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
import { McpFormFields } from './McpFormFields'
import { NEW_OBJECT_SEGMENT, toAllowlistTriState, type McpServerFormValues } from './platformObjectForm'
import { buildMcpPublishRequest } from './platformPublishRequests'
import { allowlistCell, riskTag } from './displayBits'

/**
 * MCP Server 详情（第三十四批 · C34-3 · 平台级写通道 · 第三十七批改为「新增走弹窗」）。
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

  // ⚠️ 登记的深链入口 → 回目录页并打开登记弹窗。
  //    ⚠️ **必须放在全部 Hooks 之后**（提前 return 会让 Hook 数量突变）。
  if (isRegistration) {
    return <Navigate to="/mcp?register=1" replace />
  }

  const relationsNode = (
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
      title={<><ApiOutlined /> {server?.serverId ?? 'MCP Server'}</>}
      subtitle={<Typography.Text type="secondary" code>{id}</Typography.Text>}
      loading={loading}
      missing={missing}
      error={error}
      backTo="/mcp"
      backLabel="MCP 目录"
      overview={
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
      }
      relations={relationsNode}
      actions={
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <PlatformPublishPanel<McpServerFormValues & { reason: string; expectedPublishSeq?: number }>
            form={form}
            buildRequest={(values): PlatformPublishRequest | { error: string } =>
              buildMcpPublishRequest(values)
            }
            title="修改 MCP Server（发新版本 · 三步工作流）"
            submitText="提交新版本申请"
            renderFields={() => (
              <>
                <McpFormFields isRegistration={false} hasCredentialRef={server?.hasCredentialRef} />
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
          {server && (
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
