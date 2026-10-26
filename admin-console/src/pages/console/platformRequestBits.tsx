/**
 * 平台能力目录四页**共享小组件**（平台链 /api/platform/v1/capabilities/*）。
 *
 * <p>包含：申请队列抽屉（三步工作流的审批/执行入口）、生命周期分组 Tabs、
 * 目录页加载错误提示。Tool / Skill / MCP 三页共用，避免三处各自漂移。
 */

import { useCallback, useEffect, useState, type ReactNode } from 'react'
import {
  App as AntApp,
  Button,
  Drawer,
  Popconfirm,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import type { TableColumnsType, TabsProps } from 'antd'
import { OrderedListOutlined } from '@ant-design/icons'
import {
  applyPlatformPublishRequest,
  decidePlatformPublishRequest,
  listPlatformCapabilityRequests,
} from '@/api/adminApi'
import type { PlatformRequestItem } from '@/api/types'
import { ApiError } from '@/api/client'
import { describePlatformError } from '@/pages/catalog/PlatformPublishPanel'

/** 目录项的通用形状（Tool / Skill / MCP 同有 lifecycleState / published / version）。 */
interface CatalogLike {
  version: number
  published: boolean
  lifecycleState: string | null
}

/**
 * 目录加载错误 → 明确文案。
 *
 * <p>⚠️ **403 与 503 是两件不同的事**：403 是「你不是平台管理员」（换人可解）；
 * 503 是「平台运营会话窗未接入」（环境问题，谁都改不了）。合并成一句
 * 「加载失败」会让管理员去检查自己根本控制不了的数据源配置。
 */
export function describeCatalogLoadError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 403) return '权限不足（403）：平台能力目录仅限平台管理员角色，请换用具备相应角色的平台账号。'
    if (e.status === 503)
      return '平台运营会话窗未接入（platform-ops 数据源未配置），跨租户盘点不可用。这是环境问题，请联系平台运维接通数据源。'
    return e.message
  }
  return e instanceof Error ? e.message : '加载目录失败'
}

/** 生命周期分组 Tabs 的 key（页内状态，非后端状态）。 */
export type LifecycleTabKey = 'ALL' | 'PUBLISHED' | 'DRAFT' | 'DEPRECATED'

/**
 * 生命周期分组过滤。
 *
 * <p>⚠️ 「草稿」= **不是** PUBLISHED 也**不是** DEPRECATED 的非终态
 * （DRAFT / REGISTERING / VALIDATED 都算）；lifecycleState 读不到时
 * 回落到 published 布尔判断 ——「已发布」看 {@code publishedAt} 是否存在，
 * 二者口径一致（见 {@code lifecycleStateCell} 的合并语义）。
 */
export function inLifecycleTab(item: CatalogLike, tab: LifecycleTabKey): boolean {
  const state = item.lifecycleState
  if (tab === 'ALL') return true
  if (tab === 'PUBLISHED') return state === 'PUBLISHED' || (state === null && item.published)
  if (tab === 'DEPRECATED') return state === 'DEPRECATED'
  // 草稿：未发布且未停用
  if (state === null) return !item.published
  return state !== 'PUBLISHED' && state !== 'DEPRECATED'
}

/** 生命周期分组 Tabs 的 items（label 与用例断言一致：全部 / 已发布 / 已停用）。 */
export const LIFECYCLE_TAB_ITEMS: Array<{ key: LifecycleTabKey; label: string }> = [
  { key: 'ALL', label: '全部' },
  { key: 'PUBLISHED', label: '已发布' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'DEPRECATED', label: '已停用' },
]

/**
 * 生命周期分组 Tabs（目录页共用骨架）。
 *
 * @param items    目录全量数据
 * @param tabs     Tabs 项（key + label）
 * @param renderPane 每个 key 的表格渲染
 */
export function LifecycleTabs<T extends CatalogLike>({
  items,
  tabs = LIFECYCLE_TAB_ITEMS,
  renderPane,
}: {
  items: T[]
  tabs?: Array<{ key: LifecycleTabKey; label: string }>
  renderPane: (rows: T[], tabKey: LifecycleTabKey) => ReactNode
}): JSX.Element {
  const entries: TabsProps['items'] = tabs.map((tab) => {
    const rows = tab.key === 'ALL' ? items : items.filter((r) => inLifecycleTab(r, tab.key))
    return {
      key: tab.key,
      label: (
        <span>
          {tab.label}
          {/* ⚠️ 分组后各格的行数直接可读：运维要知道「草稿堆了多少待定」，不必逐个 tab 点开数。 */}
          <Typography.Text type="secondary" style={{ marginLeft: 4 }}>
            ({rows.length})
          </Typography.Text>
        </span>
      ),
      children: renderPane(rows, tab.key),
    }
  })
  return <Tabs defaultActiveKey="ALL" items={entries} />
}

/** 申请状态配色 / 中文说明。 */
const REQUEST_STATE: Record<string, { color: string; text: string }> = {
  PENDING: { color: 'gold', text: '待审批' },
  APPROVED: { color: 'blue', text: '已通过' },
  REJECTED: { color: 'red', text: '已驳回' },
  APPLIED: { color: 'green', text: '已执行' },
  FAILED: { color: 'volcano', text: '执行失败' },
}

function requestStateCell(state: string): ReactNode {
  const info = REQUEST_STATE[state] ?? { color: 'default', text: state }
  return <Tag color={info.color}>{info.text}</Tag>
}

/** 时间（无值「-」） */
function reqTime(v: string | undefined): ReactNode {
  return v ? <Typography.Text>{v}</Typography.Text> : <Typography.Text type="secondary">-</Typography.Text>
}

/**
 * 申请队列按钮 + 抽屉（页头「申请队列」）。
 *
 * <p>⚠️ **只对 PENDING 行放「通过」「执行」**：审批与执行是三步工作流里
 * 两步，且必须**由不同的人**完成（后端双人复核比对 id）；APPROVED 行
 * 只放「执行」，REJECTED / APPLIED / FAILED 行不可再操作。为简化目录页
 * 职责，这里对 PENDING 给通过 + 执行两个 Popconfirm 按钮（执行会被
 * 后端 409 拦下 —— 自审批防护）。
 */
export function PlatformRequestQueueButton(): JSX.Element {
  const { message } = AntApp.useApp()
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState<PlatformRequestItem[]>([])
  const [loading, setLoading] = useState(false)
  const [acting, setActing] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await listPlatformCapabilityRequests())
    } catch (e) {
      message.error(describePlatformError(e))
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    if (open) void load()
  }, [open, load])

  const act = async (item: PlatformRequestItem, action: 'approve' | 'apply'): Promise<void> => {
    setActing(item.requestId + ':' + action)
    try {
      if (action === 'approve') {
        await decidePlatformPublishRequest(item.requestId, { decision: 'APPROVE' })
        message.success('已通过，待执行')
      } else {
        await applyPlatformPublishRequest(item.requestId)
        message.success('已执行')
      }
      await load()
    } catch (e) {
      message.error(describePlatformError(e))
    } finally {
      setActing(null)
    }
  }

  const columns: TableColumnsType<PlatformRequestItem> = [
    {
      title: '申请号',
      dataIndex: 'requestId',
      width: 160,
      render: (v: string) => (
        <Typography.Text code copyable={{ text: v }}>
          {v.length > 14 ? `${v.slice(0, 14)}…` : v}
        </Typography.Text>
      ),
    },
    { title: '操作', dataIndex: 'operation', width: 90 },
    { title: '范围', dataIndex: 'scope', width: 90 },
    { title: '状态', dataIndex: 'state', width: 100, render: (v: string) => requestStateCell(v) },
    { title: '申请人', dataIndex: 'requesterId', width: 110 },
    { title: '审批人', dataIndex: 'approverId', width: 110, render: (v?: string) => v ?? '-' },
    {
      title: '备注 / 失败原因',
      dataIndex: 'decisionNote',
      ellipsis: true,
      render: (_: unknown, r: PlatformRequestItem) => r.decisionNote ?? r.failureReason ?? '-',
    },
    { title: '提交时间', dataIndex: 'requestedAt', width: 170, render: (v: string) => reqTime(v) },
    { title: '决定时间', dataIndex: 'decidedAt', width: 170, render: (v?: string) => reqTime(v) },
    { title: '执行时间', dataIndex: 'appliedAt', width: 170, render: (v?: string) => reqTime(v) },
    {
      title: '操作',
      key: 'actions',
      width: 140,
      fixed: 'right',
      render: (_: unknown, r: PlatformRequestItem) =>
        r.state === 'PENDING' ? (
          <Space size="small">
            <Popconfirm
              title="确认通过该申请？（双人复核不在此处）"
              onConfirm={() => void act(r, 'approve')}
            >
              <Button size="small" loading={acting === `${r.requestId}:approve`}>
                通过
              </Button>
            </Popconfirm>
            <Popconfirm title="确认执行该申请？（此步才真正写入）" onConfirm={() => void act(r, 'apply')}>
              <Button size="small" type="primary" loading={acting === `${r.requestId}:apply`}>
                执行
              </Button>
            </Popconfirm>
          </Space>
        ) : (
          <Typography.Text type="secondary">-</Typography.Text>
        ),
    },
  ]

  return (
    <>
      <Button icon={<OrderedListOutlined />} onClick={() => setOpen(true)}>
        申请队列
      </Button>
      <Drawer open={open} onClose={() => setOpen(false)} width={1040} title="平台级变更申请队列">
        <Table<PlatformRequestItem>
          rowKey="requestId"
          loading={loading}
          dataSource={rows}
          columns={columns}
          pagination={false}
          size="small"
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: '暂无申请' }}
        />
      </Drawer>
    </>
  )
}
