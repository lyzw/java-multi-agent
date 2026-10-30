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
  Typography,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, SearchOutlined, ToolOutlined } from '@ant-design/icons'
import { getConsoleToolCatalog } from '@/api/adminApi'
import type { ToolCatalogItem } from '@/api/types'
import {
  riskTag,
  fingerprintCell,
  lifecycleStateCell,
  timeCell,
} from '@/pages/catalog/displayBits'
import { PlatformRegistrationModal } from '@/pages/catalog/PlatformRegistrationModal'
import { ToolFormFields } from '@/pages/catalog/ToolFormFields'
import { type ToolFormValues } from '@/pages/catalog/platformObjectForm'
import { buildToolPublishRequest } from '@/pages/catalog/platformPublishRequests'
import { useRegistrationModal } from '@/pages/catalog/useRegistrationModal'
import { emptyTextWhileLoading } from '@/shared/loadingState'
import { scrollXOf } from '@/shared/tableLayout'
import { PlatformDeprecateModal } from './PlatformDeprecateModal'
import { CatalogDetailDrawer, ToolCatalogDetail } from './platformDetailDrawer'
import {
  LifecycleTabs,
  PlatformRequestQueueButton,
  describeCatalogLoadError,
  inLifecycleTab,
} from './platformRequestBits'

/** Tool 目录（平台链 /api/platform/v1/capabilities/tools · 按生命周期分组） */
export function ToolCatalogPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState<ToolCatalogItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const registration = useRegistrationModal()
  const [deprecateTarget, setDeprecateTarget] = useState<{ toolId: string; version: number } | null>(
    null,
  )
  // ⚠️ 双击详情的「开合 + 看哪一条」都是本页状态：抽屉在列表上方开合，
  //    关闭后列表不卸载、不重新请求（详情另有单条 + 关系两个请求，见抽屉组件）。
  const [detailId, setDetailId] = useState<string | null>(null)
  // ⚠️ 本地搜索（不触网）：按 toolId / 名称 / 类型过滤。
  const [search, setSearch] = useState('')

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
      if (r.toolId.toLowerCase().includes(q)) return true
      if ((r.name ?? '').toLowerCase().includes(q)) return true
      if (r.kind.toLowerCase().includes(q)) return true
      return false
    })
  }, [rows, search])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setRows(await getConsoleToolCatalog())
    } catch (e) {
      setError(describeCatalogLoadError(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const columns: TableColumnsType<ToolCatalogItem> = [
    { title: 'ID', dataIndex: 'toolId', width: 180, render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: '名称', dataIndex: 'name', width: 160, render: (v: string | null) => v ?? '-' },
    {
      title: '类型', // ← 二级维度：不单独开 Tab，用列呈现
      dataIndex: 'kind',
      width: 90,
      render: (v: string) => (
        <Tag color={v === 'MCP' ? 'purple' : v === 'HTTP' ? 'blue' : 'default'}>{v}</Tag>
      ),
    },
    { title: '风险等级', dataIndex: 'riskLevel', width: 130, render: (v: string) => riskTag(v) },
    { title: '版本', dataIndex: 'version', width: 80, render: (v: number) => <Tag color="blue">v{v}</Tag> },
    {
      // ⚠️ 「状态」列 = 生命周期 + 发布状态合并 —— 只看 published 会把
      //    「已停用」（停用是单向终态，停用后 published 仍为 true）显示成「已发布」。
      title: '状态',
      dataIndex: 'lifecycleState',
      width: 120,
      render: (state: string | null, r: ToolCatalogItem) => lifecycleStateCell(state, r.published),
    },
    { title: '发布时间', dataIndex: 'publishedAt', width: 170, render: (v: string | null) => timeCell(v) },
    { title: '配置指纹', dataIndex: 'configFingerprint', width: 160, render: (v: string | null | undefined) => fingerprintCell(v) },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 90,
      // ⚠️ 「详情」与「停用」都在行内点：双击行同样可开详情（等价入口；
      //    表格列多，双击是最顺手的取出方式）。
      render: (_: unknown, r: ToolCatalogItem) => (
        <Space size="small">
          <Button size="small" onClick={() => setDetailId(r.toolId)}>
            详情
          </Button>
          {r.lifecycleState === 'PUBLISHED' ? (
            <Button size="small" onClick={() => setDeprecateTarget({ toolId: r.toolId, version: r.version })}>
              停用
            </Button>
          ) : null}
        </Space>
      ),
    },
  ]

  const renderTable = (data: ToolCatalogItem[]): JSX.Element => (
    <Table<ToolCatalogItem>
      rowKey="toolId"
      loading={loading}
      dataSource={data}
      columns={columns}
      pagination={false}
      size="small"
      // ⚠️ 必须是**各列宽度之和**而不是 'max-content'：固定操作列是独立 sticky 层，
      //    不参与 max-content 的测量，会永远压在最后一列上（评测 P1-3）。
      scroll={{ x: scrollXOf(columns) }}
      locale={{ emptyText: emptyTextWhileLoading(loading, '暂无数据') }}
      onRow={(r) => ({
        onDoubleClick: () => setDetailId(r.toolId),
      })}
    />
  )

  return (
    <div>
      <Typography.Title level={3}>
        <ToolOutlined /> Tool 目录（平台能力）
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="平台级 Tool 目录（/api/platform/v1/capabilities/*）"
        description="Tool 定义是平台级对象，全平台唯一。登记 / 改版 / 停用走平台级三步工作流（申请 → 审批 → 执行，需 PLATFORM_ADMIN）。双击行（或点「详情」）可只读查看全字段与关系——抽屉，不离开列表。"
      />
      {error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="Tool 目录不可用"
          description={error}
        />
      )}
      <Card
        extra={
          <Space>
            <PlatformRequestQueueButton />
            <Button type="primary" icon={<PlusOutlined />} onClick={registration.openModal}>
              登记 Tool
            </Button>
          </Space>
        }
      >
        <Row gutter={12} style={{ marginBottom: 16 }}>
          <Col span={6}>
            <Card size="small" variant="borderless" style={{ background: '#fafafa' }}>
              <Statistic loading={loading} title="Tool 总数" value={stats.total} />
            </Card>
          </Col>
          <Col span={6}>
            <Card size="small" variant="borderless" style={{ background: '#f6ffed' }}>
              <Statistic loading={loading} title="已发布" value={stats.published} valueStyle={{ color: '#389e0d' }} />
            </Card>
          </Col>
          <Col span={6}>
            <Card size="small" variant="borderless" style={{ background: '#fffbe6' }}>
              <Statistic loading={loading} title="草稿 / 非终态" value={stats.draft} valueStyle={{ color: '#d48806' }} />
            </Card>
          </Col>
          <Col span={6}>
            <Card size="small" variant="borderless" style={{ background: '#fff1f0' }}>
              <Statistic loading={loading} title="已停用" value={stats.deprecated} valueStyle={{ color: '#cf1322' }} />
            </Card>
          </Col>
        </Row>

        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索 toolId / 名称 / 类型"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ maxWidth: 320, marginBottom: 16 }}
        />
        <LifecycleTabs<ToolCatalogItem>
          items={filtered}
          renderPane={(data) => renderTable(data)}
        />
      </Card>

      <PlatformDeprecateModal
        objectType="tool"
        objectId={deprecateTarget?.toolId ?? null}
        version={deprecateTarget?.version ?? null}
        open={deprecateTarget !== null}
        onClose={() => setDeprecateTarget(null)}
        onSubmitted={() => message.success('已在申请队列，可在页头「申请队列」查看/跟进')}
      />

      {/* ⚠️ 双击详情走**抽屉**而不是整页详情：看完就该回到原列表状态，
          不允许「返回」把目录 list 再打一遍。 */}
      <CatalogDetailDrawer
        title={<><ToolOutlined /> Tool 详情{detailId ? ` · ${detailId}` : ''}</>}
        open={detailId !== null}
        onClose={() => setDetailId(null)}
      >
        {detailId !== null ? <ToolCatalogDetail id={detailId} /> : null}
      </CatalogDetailDrawer>

      <PlatformRegistrationModal<ToolFormValues & { reason: string; expectedPublishSeq?: number }>
        open={registration.open}
        title="登记 Tool（申请 → 审批 → 执行）"
        onClose={registration.closeModal}
        buildRequest={buildToolPublishRequest}
        renderFields={() => (
          <>
            <ToolFormFields isRegistration />
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

export default ToolCatalogPage
