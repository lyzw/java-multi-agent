import { useCallback, useEffect, useState } from 'react'
import { Navigate, useParams } from 'react-router-dom'
import {
  Alert,
  Descriptions,
  Form,
  InputNumber,
  Space,
  Typography,
} from 'antd'
import { ToolOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { getTool, getToolRelations } from '@/api/adminApi'
import type {
  PlatformPublishRequest,
  ToolCatalogItem,
  ToolRelations,
} from '@/api/types'
import { PlatformDetailShell } from './PlatformDetailShell'
import { PlatformPublishPanel } from './PlatformPublishPanel'
import { PlatformDeprecateCard } from './PlatformDeprecateCard'
import { ToolFormFields } from './ToolFormFields'
import { NEW_OBJECT_SEGMENT, type ToolFormValues } from './platformObjectForm'
import { buildToolPublishRequest } from './platformPublishRequests'
import { ToolFullInfo } from './ToolDefinitionView'
import { riskTag } from './displayBits'

/**
 * Tool 详情（第三十四批 · C34-3 · 平台级写通道 · 第三十七批改为「新增走弹窗」）。
 *
 * <p><b>⚠️ 已发布行不可变</b>：本页的「修改」= **发一个新版本**，
 * 不是原地编辑；「删除」= **停用**（DEPRECATED），物理删除不做。
 *
 * <p><b>⚠️ 平台级发布必须构成自洽闭包</b>（后端 `ConfigValidationGate`）：
 * {@code kind=MCP} 的 Tool 绑定的 Server 必须**出现在同一次请求**里，
 * 否则后端以 {@code MCP_NOT_BOUND} 拒。所以本页会自己去把那个 Server 的
 * 当前定义读出来一并纳入发布集合（见 {@code buildToolPublishRequest}）。
 *
 * <p><b>⚠️ 登记已改为弹窗</b>（第三十七批）：{@code /tools/new} 现在只作**深链入口**，
 * 立刻跳回目录页并打开登记弹窗 ——
 * 同一套登记表单不该有两个宿主（一个在弹窗里、一个在整页里），否则迟早漂移。
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
    // ⚠️ 登记模式没有「既有对象」可读 —— 直接跳过，
    //    否则会去拉一个根本不存在的 id，把「未找到」当成错误弹出来。
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
        kind: t.kind === 'MCP' ? 'MCP' : t.kind === 'HTTP' ? 'HTTP' : 'NATIVE',
        riskLevel: t.riskLevel as ToolFormValues['riskLevel'],
        mcpServerId: t.mcpServerId ?? '',
        mcpToolName: t.mcpToolName ?? '',
        httpMethod: t.httpMethod ?? '',
        httpUrl: t.httpUrl ?? '',
        httpTimeoutMs: t.httpTimeoutMs ?? null,
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

  // ⚠️ 登记的深链入口 → 回目录页并打开登记弹窗。
  //    ⚠️ **必须放在全部 Hooks 之后**：提前 return 会让本组件在
  //    「/tools/new → /tools/xxx」的导航中出现 Hook 数量突变，React 直接报错。
  if (isRegistration) {
    return <Navigate to="/tools?register=1" replace />
  }

  const relationsNode = relations?.mcpServer ? (
    <Descriptions bordered column={2} size="small" items={[
      { key: 'server', label: '所属 MCP Server', children: <Typography.Text code>{relations.mcpServer.serverId}</Typography.Text> },
      { key: 'transport', label: '传输', children: <Typography.Text>{relations.mcpServer.transport}</Typography.Text> },
      { key: 'trust', label: '信任分级', children: <Typography.Text>{relations.mcpServer.trustTier}</Typography.Text> },
      { key: 'risk', label: '连接风险', children: riskTag(relations.mcpServer.connectionRisk) },
      { key: 'version', label: 'Server 版本', children: <Typography.Text>v{relations.mcpServer.version}</Typography.Text> },
      {
        key: 'published',
        label: 'Server 状态',
        children: relations.mcpServer.published ? <Typography.Text>已发布</Typography.Text> : <Typography.Text>未发布</Typography.Text>,
      },
    ]} />
  ) : tool?.kind === 'HTTP' ? (
    <Alert
      type="info"
      showIcon
      message="远程 HTTP 请求工具"
      description="目标由配置里的 URL 决定，不绑定任何 MCP Server —— 这是合法状态，不是「查不到」。每次调用都会重新做 SSRF 校验。"
    />
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
      title={<><ToolOutlined /> {tool?.name ?? tool?.toolId ?? 'Tool'}</>}
      subtitle={<Typography.Text type="secondary" code>{id}</Typography.Text>}
      loading={loading}
      missing={missing}
      error={error}
      backTo="/tools"
      backLabel="Tool 目录"
      overview={
        tool ? (
          /* ⚠️ 详情页给的是**全量信息**：定义字段（入参结构 / 参数策略等）
             与版本/生命周期元信息一个都不能少 —— 少一项，运维就只能靠猜。 */
          <ToolFullInfo tool={tool} />
        ) : (
          <Typography.Text type="secondary">（无数据）</Typography.Text>
        )
      }
      relations={relationsNode}
      actions={
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          {/* ⚠️ 只给**一行基线**（不重复「概览」里的整块元信息）：
              元信息全量已在「概览」区呈现，这里重复一遍只会让页面变得难读。
              但基线必须出现在表单旁 —— 表单很长，作者滚到下半屏时
              仍要一眼看到「我改的是 v 几、它是不是已经停用了」。 */}
          {tool && (
            <Alert
              type="info"
              showIcon
              message={
                `当前生效版本 v${tool.version} · ${tool.published ? '已发布' : '未发布'} · ` +
                `生命周期 ${tool.lifecycleState ?? '未提供'}` +
                (tool.configFingerprint ? ` · 配置指纹 ${tool.configFingerprint}` : '')
              }
              description={
                '修改 = 发一个新版本：已发布行不可变，下方草稿提交并执行后成为**新版本**，当前行原样保留。' +
                '本对象的**全部字段**见上方「概览」。'
              }
            />
          )}
          <PlatformPublishPanel<ToolFormValues & { reason: string; expectedPublishSeq?: number }>
            form={form}
            buildRequest={(values): Promise<PlatformPublishRequest | { error: string }> =>
              buildToolPublishRequest(values)
            }
            title="修改 Tool（发新版本 · 三步工作流）"
            submitText="提交新版本申请"
            renderFields={() => (
              <>
                <ToolFormFields isRegistration={false} />
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
          {tool && (
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
