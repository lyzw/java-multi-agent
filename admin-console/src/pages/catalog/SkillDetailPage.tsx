import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  Alert,
  Button,
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
  type FormInstance,
} from 'antd'
import { BulbOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { getMcp, getSkill, getSkillRelations, getTool } from '@/api/adminApi'
import type {
  McpCatalogItem,
  PlatformPublishRequest,
  SkillCatalogItem,
  SkillRelations,
  ToolCatalogItem,
  ToolSummary,
} from '@/api/types'
import { PlatformDetailShell, RelationsEmpty } from './PlatformDetailShell'
import { PlatformPublishPanel } from './PlatformPublishPanel'
import { PlatformDeprecateCard } from './PlatformDeprecateCard'
import {
  buildSkillSpec,
  credentialRefLossBlocker,
  credentialRefLossRisk,
  mcpCatalogToSpec,
  NEW_OBJECT_SEGMENT,
  planClosure,
  toAllowlistTriState,
  type McpDeclarationFormValue,
  type SkillFormValues,
} from './platformObjectForm'
import { riskTag } from './displayBits'

/** allowlist 三态的选项（**不可**简化成二态：null 与 [] 语义不同）。 */
const ALLOWLIST_STATE_OPTIONS = [
  { value: 'UNSET', label: '未配置（取该 Server 允许集全集）' },
  { value: 'EMPTY', label: '显式空（∅ · 不允许任何操作）' },
  { value: 'LIST', label: '指定清单' },
]

/**
 * Skill 详情（第三十四批 · C34-3 · 平台级写通道）。
 *
 * <p><b>⚠️ 关系维护是本页的主戏</b>：Skill 声明了哪些 Tool / MCP Server
 * 就是它的**能力边界** —— 声明多了等于抬权，声明少了等于能力缺失。
 *
 * <p><b>⚠️ {@code toolAllowlist} 的三态不可归一化</b>：
 * {@code null} = 未配置（取全集）、{@code []} = 显式不允许任何操作。
 * 把「显式空」显示成「未配置」等于**权限放宽**，所以 UI 必须真的分得开三态。
 *
 * <p><b>⚠️ 平台级发布必须构成自洽闭包</b>：Skill 声明的每个 Tool / MCP Server
 * 都必须**出现在同一次请求**的发布集合里，否则后端以
 * {@code TOOL_NOT_DECLARED} / {@code MCP_NOT_BOUND} 拒（400）。
 */
export function SkillDetailPage(): JSX.Element {
  const { id = '' } = useParams<{ id: string }>()
  const isRegistration = id === NEW_OBJECT_SEGMENT

  const [skill, setSkill] = useState<SkillCatalogItem | null>(null)
  const [relations, setRelations] = useState<SkillRelations | null>(null)
  const [loading, setLoading] = useState(!isRegistration)
  const [missing, setMissing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [form] = Form.useForm<SkillFormValues & { reason: string; expectedPublishSeq?: number }>()

  const load = useCallback(async () => {
    if (isRegistration) return
    setLoading(true)
    try {
      const [s, r] = await Promise.all([getSkill(id), getSkillRelations(id)])
      setSkill(s)
      setRelations(r)
      setMissing(false)
      setError(null)
      form.setFieldsValue({
        skillId: s.skillId,
        name: s.name ?? '',
        description: s.description ?? '',
        declaredTools: [...s.declaredTools],
        // ⚠️ 回填时按三态还原：null ≠ []，不能都塞成 []。
        declaredMcp: s.declaredMcp.map((d) => ({
          serverId: d.serverId,
          allowlistState: toAllowlistTriState(d.toolAllowlist),
          allowlist: d.toolAllowlist ?? [],
        })),
        riskCeiling: s.riskCeiling as SkillFormValues['riskCeiling'],
        paramPolicies: s.paramPolicies ?? '{}',
        requiredCaps: [...s.requiredCaps],
      })
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setMissing(true)
      } else {
        setError(e instanceof Error ? e.message : '加载 Skill 详情失败')
      }
    } finally {
      setLoading(false)
    }
  }, [id, isRegistration, form])

  useEffect(() => {
    void load()
  }, [load])

  /**
   * 构造发布申请体（含**闭包补齐**）。
   *
   * ⚠️ 声明了但**取不到定义**的对象会被如实列进错误信息 ——
   * 悄悄丢弃会让作者以为「闭包已经齐了」，而后端会用一个指向不可见对象的
   * 错误码把事情搅混。
   */
  const buildRequest = async (
    values: SkillFormValues & { reason: string; expectedPublishSeq?: number },
  ): Promise<PlatformPublishRequest | { error: string }> => {
    const built = buildSkillSpec(values)
    if (!built.ok) return { error: built.error }
    const spec = built.spec

    const body: PlatformPublishRequest = {
      skills: [spec],
      reason: values.reason.trim(),
    }
    if (values.expectedPublishSeq !== undefined && values.expectedPublishSeq !== null) {
      body.expectedPublishSeq = values.expectedPublishSeq
    }

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
        return {
          toolId: t.toolId,
          name: t.name ?? undefined,
          kind: (t.kind === 'MCP' ? 'MCP' : 'NATIVE') as 'MCP' | 'NATIVE',
          riskLevel: t.riskLevel as 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL',
          mcpServerId: t.mcpServerId ?? undefined,
          mcpToolName: t.mcpToolName ?? undefined,
          inputSchema: t.inputSchema ?? '{}',
          paramPolicies: t.paramPolicies ?? '{}',
        }
      },
      (serverId) => {
        const s = serverById.get(serverId)
        return s ? mcpCatalogToSpec(s) : null
      },
    )

    // ⚠️ 声明了但取不到定义的 id **如实列出** —— 悄悄丢弃会让作者
    //    以为「闭包已经齐了」，而后端会用一个指向不可见对象的错误码搅混。
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
    //    闭包补齐会把既有 MCP Server 一并重发，而目录接口从不回显凭据定位符，
    //    ⇒ 重发出来的新版本**没有凭据引用** ⇒ 全平台生效配置的认证失效。
    //    提交者改的是 Skill，却静默改掉了 MCP 的认证 —— 必须拦住并点名。
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

  const relationsNode = isRegistration ? (
    <RelationsEmpty text="登记模式：对象尚不存在，无既有关系" />
  ) : (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card
        size="small"
        title={`声明的 Tool（${relations?.tools.length ?? 0}）`}
        styles={{ body: { paddingBottom: 0 } }}
      >
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
          <RelationsEmpty text="未声明任何 Tool" />
        )}
      </Card>

      {relations && relations.missingTools.length > 0 && (
        <Alert
          type="error"
          showIcon
          message={`声明了但对象不存在的 Tool（${relations.missingTools.length}）`}
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {relations.missingTools.map((t) => (
                <li key={t}><Typography.Text code>{t}</Typography.Text>（无定义）</li>
              ))}
            </ul>
          }
        />
      )}

      <Card size="small" title={`声明的 MCP Server（${relations?.mcpServers.length ?? 0}）`}>
        {relations && relations.mcpServers.length > 0 ? (
          <Descriptions bordered column={2} size="small" items={relations.mcpServers.flatMap((s) => [
            { key: `${s.serverId}-id`, label: 'Server', children: <Typography.Text code>{s.serverId}</Typography.Text> },
            { key: `${s.serverId}-transport`, label: '传输', children: <Tag>{s.transport}</Tag> },
            { key: `${s.serverId}-trust`, label: '信任分级', children: <Tag>{s.trustTier}</Tag> },
            { key: `${s.serverId}-risk`, label: '连接风险', children: riskTag(s.connectionRisk) },
          ])} />
        ) : (
          <RelationsEmpty text="未声明任何 MCP Server" />
        )}
      </Card>

      {relations && relations.missingMcpServers.length > 0 && (
        <Alert
          type="error"
          showIcon
          message={`声明了但对象不存在的 MCP Server（${relations.missingMcpServers.length}）`}
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {relations.missingMcpServers.map((s) => (
                <li key={s}><Typography.Text code>{s}</Typography.Text>（无定义）</li>
              ))}
            </ul>
          }
        />
      )}
    </Space>
  )

  return (
    <PlatformDetailShell
      title={<><BulbOutlined /> {isRegistration ? '登记 Skill' : (skill?.name ?? skill?.skillId ?? 'Skill')}</>}
      subtitle={<Typography.Text type="secondary" code>{isRegistration ? '（新对象）' : id}</Typography.Text>}
      loading={loading}
      missing={missing}
      error={error}
      backTo="/skills"
      backLabel="Skill 目录"
      overview={
        isRegistration ? (
          <Alert
            type="info"
            showIcon
            message="登记新 Skill"
            description="Skill 的声明清单就是它的能力边界；平台级 Skill 全平台唯一。提交后走「申请 → 审批 → 执行」。"
          />
        ) : (
          <Descriptions bordered column={2} size="small" items={[
            { key: 'id', label: '标识', children: <Typography.Text code>{skill?.skillId ?? id}</Typography.Text> },
            { key: 'name', label: '名称', children: skill?.name ?? '-' },
            { key: 'desc', label: '描述', children: skill?.description ?? '-' },
            { key: 'risk', label: '风险上限', children: riskTag(skill?.riskCeiling ?? '') },
            { key: 'caps', label: '必需能力', children: (skill?.requiredCaps ?? []).join('、') || '-' },
            { key: 'version', label: '当前版本', children: <Tag color="blue">v{skill?.version ?? 0}</Tag> },
            {
              key: 'published',
              label: '发布状态',
              children: skill?.published ? <Tag color="green">已发布</Tag> : <Tag color="orange">未发布</Tag>,
            },
            { key: 'publishedAt', label: '发布时间', children: skill?.publishedAt ?? '-' },
          ]} />
        )
      }
      relations={relationsNode}
      actions={
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <PlatformPublishPanel<SkillFormValues & { reason: string; expectedPublishSeq?: number }>
            form={form}
            buildRequest={buildRequest}
            title={isRegistration ? '登记 Skill（三步工作流）' : '修改 Skill（发新版本 · 三步工作流）'}
            submitText={isRegistration ? '提交登记申请' : '提交新版本申请'}
            renderFields={() => (
              <>
                <Form.Item
                  name="skillId"
                  label="Skill 标识"
                  rules={[{ required: true, message: '请输入 Skill 标识' }]}
                  extra={isRegistration ? '平台级对象全平台唯一' : '标识不可改：改标识等于登记另一个对象'}
                >
                  <Input disabled={!isRegistration} placeholder="如 order-analysis" />
                </Form.Item>
                <Form.Item name="name" label="名称">
                  <Input placeholder="可读名称（可选）" />
                </Form.Item>
                <Form.Item name="description" label="描述">
                  <Input.TextArea rows={2} />
                </Form.Item>
                <Form.Item name="riskCeiling" label="风险上限（riskCeiling）" rules={[{ required: true }]}>
                  <Select
                    options={[
                      { value: 'READ', label: 'READ（只读）' },
                      { value: 'WRITE_PROFILE', label: 'WRITE_PROFILE（内部写）' },
                      { value: 'WRITE_EXTERNAL', label: 'WRITE_EXTERNAL（外部写）' },
                    ]}
                  />
                </Form.Item>
                <Form.Item
                  name="declaredTools"
                  label="声明的 Tool（declaredTools）"
                  extra="⚠️ 发布集合必须自洽闭包：声明的 Tool 会被一并纳入本次发布"
                >
                  <Select mode="tags" placeholder="Tool 标识（回车分隔）" tokenSeparators={[',']} />
                </Form.Item>
                <Form.List name="declaredMcp">
                  {(fields, { add, remove }) => (
                    <>
                      {fields.map((field) => (
                        <SkillMcpDeclarationRow key={field.key} field={field} form={form} onRemove={() => remove(field.name)} />
                      ))}
                      <Button
                        onClick={() =>
                          add({ serverId: '', allowlistState: 'UNSET', allowlist: [] })
                        }
                      >
                        添加 MCP 声明
                      </Button>
                    </>
                  )}
                </Form.List>
                <Form.Item name="requiredCaps" label="必需能力（requiredCaps）">
                  <Select mode="tags" placeholder="能力标识（回车分隔）" tokenSeparators={[',']} />
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
          {!isRegistration && skill && (
            <PlatformDeprecateCard
              type="skill"
              objectId={skill.skillId}
              version={skill.version}
              lifecycleState={skill.lifecycleState}
            />
          )}
        </Space>
      }
    />
  )
}

/** 一行 MCP 声明（Server + 三态 allowlist）。 */
function SkillMcpDeclarationRow({
  field,
  form,
  onRemove,
}: {
  field: { name: number; key: number }
  form: FormInstance<SkillFormValues & { reason: string }>
  onRemove: () => void
}): JSX.Element {
  const state = Form.useWatch(['declaredMcp', field.name, 'allowlistState'], form) as
    | McpDeclarationFormValue['allowlistState']
    | undefined
  return (
    <Space align="baseline" wrap>
      <Form.Item
        {...field}
        name={[field.name, 'serverId']}
        label="MCP Server 标识"
        rules={[{ required: true, message: '请输入 MCP Server 标识' }]}
      >
        <Input placeholder="Server 标识" style={{ width: 240 }} />
      </Form.Item>
      <Form.Item
        {...field}
        name={[field.name, 'allowlistState']}
        label="允许操作（toolAllowlist · 三态）"
        extra="null=未配置（全集） / []=显式空 / 清单"
        initialValue="UNSET"
      >
        <Select style={{ width: 280 }} options={ALLOWLIST_STATE_OPTIONS} />
      </Form.Item>
      {state === 'LIST' && (
        <Form.Item
          {...field}
          name={[field.name, 'allowlist']}
          label="允许的操作清单"
          rules={[{ required: true, message: '指定清单不能为空（请改选「显式空」）' }]}
        >
          <Select mode="tags" placeholder="远端操作名（回车分隔）" style={{ width: 320 }} tokenSeparators={[',']} />
        </Form.Item>
      )}
      <Button danger onClick={onRemove}>移除</Button>
    </Space>
  )
}

export default SkillDetailPage
