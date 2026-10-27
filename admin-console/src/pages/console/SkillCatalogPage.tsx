import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import type { TableColumnsType } from 'antd'
import {
  BulbOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { getConsoleSkillCatalog } from '@/api/adminApi'
import type { SkillCatalogItem } from '@/api/types'
import { riskTag, lifecycleStateCell, skillPurposeCell } from '@/pages/catalog/displayBits'
import { PlatformRegistrationModal } from '@/pages/catalog/PlatformRegistrationModal'
import { SkillFormFields } from '@/pages/catalog/SkillFormFields'
import { type SkillFormValues, type McpDeclarationFormValue, toAllowlistTriState } from '@/pages/catalog/platformObjectForm'
import { buildSkillPublishRequest } from '@/pages/catalog/platformPublishRequests'
import { useRegistrationModal } from '@/pages/catalog/useRegistrationModal'
import { PlatformDeprecateModal } from './PlatformDeprecateModal'
import { CatalogDetailDrawer, SkillCatalogDetail } from './platformDetailDrawer'
import {
  LifecycleTabs,
  PlatformRequestQueueButton,
  describeCatalogLoadError,
  inLifecycleTab,
} from './platformRequestBits'

/** Skill 目录（平台链 /api/platform/v1/capabilities/skills · 按生命周期分组） */
export function SkillCatalogPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState<SkillCatalogItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const registration = useRegistrationModal()
  const [deprecateTarget, setDeprecateTarget] = useState<{
    skillId: string
    version: number
  } | null>(null)
  // ⚠️ 双击详情的「开合 + 看哪一条」都是本页状态：抽屉在列表上方开合，
  //    关闭后列表不卸载、不重新请求。
  const [detailId, setDetailId] = useState<string | null>(null)
  // ⚠️ 改版：用同一套登记弹窗预填既有定义（skillId 只读）。
  const [reviseRow, setReviseRow] = useState<SkillCatalogItem | null>(null)
  // ⚠️ 本地搜索（不触网）：按 skillId / 名称 / 用途过滤。
  const [search, setSearch] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setRows(await getConsoleSkillCatalog())
    } catch (e) {
      setError(describeCatalogLoadError(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // ⚠️ 统计卡：直接基于全量数据，与下方 Tab 口径一致（inLifecycleTab）。
  const stats = useMemo(() => {
    return {
      total: rows.length,
      published: rows.filter((r) => inLifecycleTab(r, 'PUBLISHED')).length,
      draft: rows.filter((r) => inLifecycleTab(r, 'DRAFT')).length,
      deprecated: rows.filter((r) => inLifecycleTab(r, 'DEPRECATED')).length,
    }
  }, [rows])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (q === '') return rows
    return rows.filter((r) => {
      if (r.skillId.toLowerCase().includes(q)) return true
      if ((r.name ?? '').toLowerCase().includes(q)) return true
      if ((r.description?.purpose ?? '').toLowerCase().includes(q)) return true
      return false
    })
  }, [rows, search])

  const columns: TableColumnsType<SkillCatalogItem> = [
    {
      title: 'ID',
      dataIndex: 'skillId',
      width: 160,
      render: (v: string) => <Typography.Text code copyable={{ text: v }}>{v}</Typography.Text>,
    },
    { title: '名称', dataIndex: 'name', width: 160, render: (v: string | null) => v ?? '-' },
    {
      title: '用途（1级）',
      width: 220,
      // ⚠️ 用途是「这 Skill 干什么」的第一手信息，扫目录就该看到（省略 + tooltip 完整两级）。
      render: (_: unknown, r: SkillCatalogItem) => skillPurposeCell(r.description),
    },
    { title: '风险上限', dataIndex: 'riskCeiling', width: 120, render: (v: string) => riskTag(v) },
    {
      title: '状态',
      dataIndex: 'lifecycleState',
      width: 110,
      render: (state: string | null, r: SkillCatalogItem) => lifecycleStateCell(state, r.published),
    },
    {
      title: '声明（Tool / MCP）',
      width: 150,
      // ⚠️ 合并为单列 `Tool*x/MCP*y`：hover 列出具体 id（避免两列挤占横向空间）。
      render: (_: unknown, r: SkillCatalogItem) => {
        const tools = r.declaredTools
        const mcps = r.declaredMcp.map((d) => d.serverId)
        const hasNone = tools.length === 0 && mcps.length === 0
        return (
          <Tooltip
            title={
              hasNone ? (
                <Typography.Text type="secondary">未声明任何 Tool / MCP Server</Typography.Text>
              ) : (
                <div style={{ maxWidth: 320 }}>
                  <div style={{ marginBottom: 4 }}>
                    <b>Tool（{tools.length}）</b>
                  </div>
                  {tools.length === 0 ? (
                    <Typography.Text type="secondary">无</Typography.Text>
                  ) : (
                    tools.map((id) => (
                      <div key={id}>
                        <Typography.Text code>{id}</Typography.Text>
                      </div>
                    ))
                  )}
                  <div style={{ margin: '8px 0 4px' }}>
                    <b>MCP Server（{mcps.length}）</b>
                  </div>
                  {mcps.length === 0 ? (
                    <Typography.Text type="secondary">无</Typography.Text>
                  ) : (
                    mcps.map((id) => (
                      <div key={id}>
                        <Typography.Text code>{id}</Typography.Text>
                      </div>
                    ))
                  )}
                </div>
              )
            }
          >
            <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
              Tool*{tools.length}/MCP*{mcps.length}
            </span>
          </Tooltip>
        )
      },
    },
    { title: '版本', dataIndex: 'version', width: 80, render: (v: number) => <Tag color="blue">v{v}</Tag> },
    {
      title: '发布时间',
      dataIndex: 'publishedAt',
      width: 170,
      render: (v: string | null) => formatPublishTime(v),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 168,
      // ⚠️ 「详情」与「停用」都在行内点：双击行同样可开详情（等价入口）。
      render: (_: unknown, r: SkillCatalogItem) => (
        <Space size="small">
          <Button size="small" onClick={() => setDetailId(r.skillId)}>
            详情
          </Button>
          <Button size="small" icon={<EditOutlined />} onClick={() => setReviseRow(r)}>
            改版
          </Button>
          {r.lifecycleState === 'PUBLISHED' ? (
            <Button
              size="small"
              danger
              icon={<StopOutlined />}
              onClick={() => setDeprecateTarget({ skillId: r.skillId, version: r.version })}
            >
              停用
            </Button>
          ) : null}
        </Space>
      ),
    },
  ]

  const renderTable = (data: SkillCatalogItem[]): JSX.Element => (
    <Table<SkillCatalogItem>
      rowKey="skillId"
      loading={loading && data === filtered}
      dataSource={data}
      columns={columns}
      pagination={false}
      size="small"
      scroll={{ x: 'max-content' }}
      locale={{ emptyText: search.trim() !== '' ? '没有匹配的 Skill' : '暂无数据' }}
      onRow={(r) => ({
        onDoubleClick: () => setDetailId(r.skillId),
      })}
    />
  )

  return (
    <div>
      <Typography.Title level={3}>
        <BulbOutlined /> Skill 目录（平台能力）
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="平台级 Skill 目录（/api/platform/v1/capabilities/*）"
        description="Skill 定义是平台级对象，其声明的工具 / MCP 关系与风险上限在下方呈现。登记 / 改版 / 停用走平台级三步工作流（申请 → 审批 → 执行，需 PLATFORM_ADMIN）。双击行（或点「详情」）可只读查看全字段与声明关系——抽屉，不离开列表。"
      />
      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Skill 目录不可用"
          description={error}
        />
      )}

      <Row gutter={12} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small" variant="borderless" style={{ background: '#fafafa' }}>
            <Statistic title="Skill 总数" value={stats.total} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" variant="borderless" style={{ background: '#f6ffed' }}>
            <Statistic title="已发布" value={stats.published} valueStyle={{ color: '#389e0d' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" variant="borderless" style={{ background: '#fffbe6' }}>
            <Statistic title="草稿 / 非终态" value={stats.draft} valueStyle={{ color: '#d48806' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" variant="borderless" style={{ background: '#fff1f0' }}>
            <Statistic title="已停用" value={stats.deprecated} valueStyle={{ color: '#cf1322' }} />
          </Card>
        </Col>
      </Row>

      <Card
        extra={
          <Space>
            <PlatformRequestQueueButton />
            <Button icon={<ReloadOutlined />} onClick={() => void load()}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={registration.openModal}>
              登记 Skill
            </Button>
          </Space>
        }
      >
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索 skillId / 名称 / 用途"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ maxWidth: 320, marginBottom: 16 }}
        />
        <LifecycleTabs<SkillCatalogItem>
          items={filtered}
          renderPane={(data) => renderTable(data)}
        />
      </Card>

      <PlatformDeprecateModal
        objectType="skill"
        objectId={deprecateTarget?.skillId ?? null}
        version={deprecateTarget?.version ?? null}
        open={deprecateTarget !== null}
        onClose={() => setDeprecateTarget(null)}
        onSubmitted={() => message.success('已在申请队列，可在页头「申请队列」查看/跟进')}
      />

      {/* ⚠️ 双击详情走**抽屉**而不是整页详情：看完就该回到原列表状态，
          不允许「返回」把目录 list 再打一遍。 */}
      <CatalogDetailDrawer
        title={<><BulbOutlined /> Skill 详情{detailId ? ` · ${detailId}` : ''}</>}
        open={detailId !== null}
        onClose={() => setDetailId(null)}
      >
        {detailId !== null ? <SkillCatalogDetail id={detailId} /> : null}
      </CatalogDetailDrawer>

      {/* ⚠️ 改版：复用同一套登记弹窗，预填既有定义（skillId 只读）。 */}
      <PlatformRegistrationModal<SkillFormValues & { reason: string; expectedPublishSeq?: number }>
        open={reviseRow !== null}
        title={reviseRow ? `改版 Skill · ${reviseRow.skillId}（申请 → 审批 → 执行）` : '改版 Skill'}
        initialValues={reviseRow ? skillToFormValues(reviseRow) : undefined}
        onClose={() => setReviseRow(null)}
        buildRequest={buildSkillPublishRequest}
        renderFields={() => (
          <>
            <SkillFormFields isRegistration={false} />
            <SkillReviseNotice />
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

      <PlatformRegistrationModal<SkillFormValues & { reason: string; expectedPublishSeq?: number }>
        open={registration.open}
        title="登记 Skill（申请 → 审批 → 执行）"
        onClose={registration.closeModal}
        buildRequest={buildSkillPublishRequest}
        renderFields={() => (
          <>
            <SkillFormFields isRegistration />
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
    </div>
  )
}

/** 改版提示：强调「改版 = 基于既有定义生成新版本」，不是另立对象。 */
function SkillReviseNotice(): JSX.Element {
  return (
    <Alert
      type="warning"
      showIcon
      style={{ marginBottom: 16 }}
      message="改版：基于下方预填的既有定义提交新版本"
      description="skillId 不可改（改标识等于登记另一个对象）。提交后走三步工作流，审批执行后指针移向新版本；既有版本保留为历史。"
    />
  )
}

/** 发布时间：列表里显示「YYYY-MM-DD HH:mm」，tooltip + 可复制原始 ISO。 */
function formatPublishTime(v: string | null | undefined): JSX.Element {
  const raw = (v ?? '').trim()
  if (raw === '') return <Typography.Text type="secondary">-</Typography.Text>
  const d = new Date(raw)
  const formatted = Number.isNaN(d.getTime())
    ? raw
    : d
        .toLocaleString('zh-CN', { hour12: false })
        .replace(/\//g, '-')
  return (
    <Tooltip title={<Typography.Text code>{raw}</Typography.Text>}>
      <Typography.Text copyable={{ text: raw }}>{formatted}</Typography.Text>
    </Tooltip>
  )
}

/** 把目录项映射回登记表单取值（改版预填用）。 */
function skillToFormValues(item: SkillCatalogItem): Partial<SkillFormValues> {
  const declaredMcp: McpDeclarationFormValue[] = item.declaredMcp.map((d) => ({
    serverId: d.serverId,
    allowlistState: toAllowlistTriState(d.toolAllowlist),
    allowlist: d.toolAllowlist ?? [],
  }))
  return {
    skillId: item.skillId,
    name: item.name ?? undefined,
    description: item.description
      ? { purpose: item.description.purpose, usage: item.description.usage ?? [] }
      : undefined,
    riskCeiling: item.riskCeiling as SkillFormValues['riskCeiling'],
    declaredTools: [...item.declaredTools],
    declaredMcp,
    requiredCaps: [...item.requiredCaps],
    paramPolicies: item.paramPolicies,
  }
}

export default SkillCatalogPage
