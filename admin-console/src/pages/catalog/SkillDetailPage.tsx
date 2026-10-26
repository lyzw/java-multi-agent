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
import { BulbOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { getSkill, getSkillRelations } from '@/api/adminApi'
import type {
  PlatformPublishRequest,
  SkillCatalogItem,
  SkillRelations,
  ToolSummary,
} from '@/api/types'
import { PlatformDetailShell, RelationsEmpty } from './PlatformDetailShell'
import { PlatformPublishPanel } from './PlatformPublishPanel'
import { PlatformDeprecateCard } from './PlatformDeprecateCard'
import { SkillFormFields } from './SkillFormFields'
import { NEW_OBJECT_SEGMENT, toAllowlistTriState, type SkillFormValues } from './platformObjectForm'
import { buildSkillPublishRequest } from './platformPublishRequests'
import { riskTag } from './displayBits'

/**
 * Skill 详情（第三十四批 · C34-3 · 平台级写通道 · 第三十七批改为「新增走弹窗」）。
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

  // ⚠️ 登记的深链入口 → 回目录页并打开登记弹窗。
  //    ⚠️ **必须放在全部 Hooks 之后**（提前 return 会让 Hook 数量突变）。
  if (isRegistration) {
    return <Navigate to="/skills?register=1" replace />
  }

  const relationsNode = (
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

      <Card size="small" title={`声明的 MCP Server（${relations?.mcpServers.length ?? 0}）`}>
        {relations && relations.mcpServers.length > 0 ? (
          <Descriptions bordered column={2} size="small" items={relations.mcpServers.flatMap((s) => [
            { key: `${s.serverId}-id`, label: 'Server', children: <Typography.Text code>{s.serverId}</Typography.Text> },
            { key: `${s.serverId}-transport`, label: '传输', children: <Tag>{s.transport}</Tag> },
          ])} />
        ) : (
          <RelationsEmpty text="未声明任何 MCP Server" />
        )}
      </Card>
    </Space>
  )

  return (
    <PlatformDetailShell
      title={<><BulbOutlined /> {skill?.name ?? skill?.skillId ?? 'Skill'}</>}
      subtitle={<Typography.Text type="secondary" code>{id}</Typography.Text>}
      loading={loading}
      missing={missing}
      error={error}
      backTo="/skills"
      backLabel="Skill 目录"
      overview={
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
      }
      relations={relationsNode}
      actions={
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <PlatformPublishPanel<SkillFormValues & { reason: string; expectedPublishSeq?: number }>
            form={form}
            buildRequest={(values): Promise<PlatformPublishRequest | { error: string }> =>
              buildSkillPublishRequest(values)
            }
            title="修改 Skill（发新版本 · 三步工作流）"
            submitText="提交新版本申请"
            renderFields={() => (
              <>
                <SkillFormFields isRegistration={false} />
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
          {skill && (
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

export default SkillDetailPage
