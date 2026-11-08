import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  type TableColumnsType,
} from 'antd'
import { useSearchParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  assignReviewTask,
  dismissReviewTask,
  fetchReviewTasks,
  resolveReviewTask,
  ROOT_CAUSE_OPTIONS,
  REVIEW_PRIORITY_COLORS,
  REVIEW_SOURCE_LABELS,
  REVIEW_STATUS_LABELS,
  type ReviewTask,
  type RootCause,
} from '@/api/traceReviewApi'
import { renderStamp } from '@/features/quality/transcriptPresentation'
import { TraceTranscriptDrawer } from '@/features/quality/TraceTranscriptDrawer'
import { emptyTextWhileLoading } from '@/shared/loadingState'
import { scrollXOf } from '@/shared/tableLayout'
import { usePageTitle } from '@/shared/usePageTitle'

/**
 * 人工审查队列（P2 审查闭环 + P3 评估双轨的落地界面）。
 *
 * ⚠️ **读写权限分离**：列表 / 回放读 `platform.resource.read`；
 *    认领 / 归因 / 驳回写 `platform.resource.manage`。只读审计角色点写操作会 403，
 *    界面必须明确说「缺权限」，不能显示成「网络失败」或让人反复重试。
 *
 * ⚠️ **无物理删除**：队列只走状态机流转（PENDING → IN_REVIEW → RESOLVED / DISMISSED），
 *    本页刻意不提供删除按钮。
 *
 * ⚠️ **「空」不等于「没有」**：加载失败时表格空态必须说「加载失败」，
 *    否则接口挂了会显示成「暂无待审任务」——审查者据此下班，异常 Run 根本没进视野。
 *
 * 深链：`/review-queue?runId=<uuid>` 会直接打开该 Run 的对话回放抽屉，
 * 供 Grafana / 指标下钻跳转。
 */

type ActionKind = 'resolve' | 'dismiss'

interface ActionTarget {
  kind: ActionKind
  task: ReviewTask
}

const STATUS_OPTIONS = [
  { value: '', label: '待处理（PENDING + IN_REVIEW）' },
  { value: 'PENDING', label: '待认领' },
  { value: 'IN_REVIEW', label: '审查中' },
  { value: 'RESOLVED', label: '已归因关闭' },
  { value: 'DISMISSED', label: '已驳回' },
]

function describeLoadError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === 'MALFORMED_RESPONSE') return e.message
    if (e.code === 'ACCESS_DENIED') return '当前平台账号缺少 platform.resource.read，无法读取审查队列'
    return `加载失败（HTTP ${e.status}）：${e.message}`
  }
  return `加载失败：${e instanceof Error ? e.message : String(e)}`
}

function describeWriteError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === 'ACCESS_DENIED') {
      return '缺少 platform.resource.manage：只读账号不能流转审查任务，请用有平台资源管理权限的账号操作'
    }
    if (e.status === 400) return `请求被后端拒绝：${e.message}（检查 rootCause 是否在白名单内）`
    return `操作失败（HTTP ${e.status}）：${e.message}`
  }
  return `操作失败：${e instanceof Error ? e.message : String(e)}`
}

export function ReviewQueuePage(): JSX.Element {
  usePageTitle('人工审查队列')

  const [searchParams, setSearchParams] = useSearchParams()
  const [status, setStatus] = useState('')
  const [tenantDraft, setTenantDraft] = useState('')
  const [tenantFilter, setTenantFilter] = useState('')
  const [rows, setRows] = useState<ReviewTask[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const [drawerRunId, setDrawerRunId] = useState('')
  const [drawerTenantId, setDrawerTenantId] = useState<string | undefined>(undefined)
  const [drawerOpen, setDrawerOpen] = useState(false)

  const [actionTarget, setActionTarget] = useState<ActionTarget | null>(null)
  const [rootCause, setRootCause] = useState<RootCause>('UNKNOWN')
  const [note, setNote] = useState('')
  const [busyTaskId, setBusyTaskId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [actionOk, setActionOk] = useState<string | null>(null)

  const reload = useCallback(() => setReloadToken((n) => n + 1), [])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await fetchReviewTasks({
        status: status === '' ? undefined : status,
        tenantId: tenantFilter === '' ? undefined : tenantFilter,
        limit: 50,
      })
      setRows(list)
    } catch (e: unknown) {
      setError(describeLoadError(e))
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [status, tenantFilter, reloadToken])

  useEffect(() => {
    void load()
  }, [load])

  // 深链直达回放（供指标下钻 / Grafana 链接使用）。
  const runIdFromUrl = searchParams.get('runId') ?? ''
  useEffect(() => {
    if (runIdFromUrl !== '') {
      setDrawerRunId(runIdFromUrl)
      // 深链只有 runId，没有 tenantId → 交由后端从平台域指标宽表反查。
      setDrawerTenantId(undefined)
      setDrawerOpen(true)
    }
    // 只在 URL 变化时打开；关闭抽屉时主动清掉 query，避免立刻又被打开。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runIdFromUrl])

  const closeDrawer = useCallback(() => {
    setDrawerOpen(false)
    setDrawerRunId('')
    setDrawerTenantId(undefined)
    if (searchParams.has('runId')) {
      const next = new URLSearchParams(searchParams)
      next.delete('runId')
      setSearchParams(next, { replace: true })
    }
  }, [searchParams, setSearchParams])

  const openTranscript = (runId: string, tenantId: string) => {
    setDrawerRunId(runId)
    setDrawerTenantId(tenantId)
    setDrawerOpen(true)
  }

  const openAction = (kind: ActionKind, task: ReviewTask) => {
    setActionError(null)
    setActionOk(null)
    setRootCause('UNKNOWN')
    setNote('')
    setActionTarget({ kind, task })
  }

  const runAssign = async (task: ReviewTask) => {
    setBusyTaskId(task.taskId)
    setActionError(null)
    setActionOk(null)
    try {
      await assignReviewTask(task.taskId)
      setActionOk(`已认领任务 ${task.taskId.slice(0, 8)}`)
      reload()
    } catch (e: unknown) {
      setActionError(describeWriteError(e))
    } finally {
      setBusyTaskId(null)
    }
  }

  const submitAction = async () => {
    if (actionTarget === null) return
    const { kind, task } = actionTarget
    setBusyTaskId(task.taskId)
    setActionError(null)
    setActionOk(null)
    try {
      if (kind === 'resolve') {
        await resolveReviewTask(task.taskId, rootCause, note.trim() === '' ? undefined : note.trim())
        setActionOk(`已归因关闭：${rootCause}`)
      } else {
        await dismissReviewTask(task.taskId, note.trim() === '' ? undefined : note.trim())
        setActionOk('已驳回该任务')
      }
      setActionTarget(null)
      reload()
    } catch (e: unknown) {
      setActionError(describeWriteError(e))
    } finally {
      setBusyTaskId(null)
    }
  }

  const columns: TableColumnsType<ReviewTask> = [
    {
      title: '时刻',
      dataIndex: 'createdAt',
      width: 180,
      render: (v: string) => <Typography.Text style={{ fontSize: 12 }}>{renderStamp(v)}</Typography.Text>,
    },
    {
      title: '来源',
      dataIndex: 'source',
      width: 160,
      render: (v: string) => <Tag>{REVIEW_SOURCE_LABELS[v] ?? v}</Tag>,
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      width: 90,
      render: (v: string) => <Tag color={REVIEW_PRIORITY_COLORS[v] ?? 'default'}>{v}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (v: string) => REVIEW_STATUS_LABELS[v] ?? v,
    },
    {
      title: '租户',
      dataIndex: 'tenantId',
      width: 150,
      render: (v: string) => <Typography.Text code style={{ fontSize: 11 }}>{v.slice(0, 8)}…</Typography.Text>,
    },
    {
      title: 'Run ID',
      dataIndex: 'runId',
      width: 150,
      render: (v: string) => <Typography.Text code style={{ fontSize: 11 }}>{v.slice(0, 8)}…</Typography.Text>,
    },
    {
      title: '认领人',
      dataIndex: 'assignedTo',
      width: 120,
      render: (v: string | null) => v ?? '—',
    },
    {
      title: '根因',
      dataIndex: 'rootCause',
      width: 100,
      render: (v: string | null) => v ?? '—',
    },
    {
      title: '操作',
      key: 'actions',
      width: 280,
      render: (_: unknown, task: ReviewTask) => {
        const open = task.status === 'PENDING' || task.status === 'IN_REVIEW'
        const busy = busyTaskId === task.taskId
        return (
          <Space size={4} wrap>
            <Button size="small" onClick={() => openTranscript(task.runId, task.tenantId)}>
              查看回放
            </Button>
            {/* antd 对双字中文按钮默认插入字间距（「认 领」/「驳 回」），
                显式 aria-label 才能让可访问名称与语义一致（也便于自动化验收）。 */}
            {open && task.status === 'PENDING' && (
              <Button size="small" aria-label="认领" loading={busy} onClick={() => void runAssign(task)}>
                认领
              </Button>
            )}
            {open && (
              <>
                <Button size="small" loading={busy} onClick={() => openAction('resolve', task)}>
                  归因关闭
                </Button>
                <Button size="small" aria-label="驳回" loading={busy} onClick={() => openAction('dismiss', task)}>
                  驳回
                </Button>
              </>
            )}
          </Space>
        )
      },
    },
  ]

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div>
        <Typography.Title level={4} style={{ marginBottom: 4 }}>人工审查队列</Typography.Title>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          告警 / 异常 / 随机抽样 / L1 判分缺口自动入队。认领 → 查看对话回放 → 归因关闭或驳回。
          队列无物理删除端点，只走状态机流转。
        </Typography.Paragraph>
      </div>

      <Alert
        type="info"
        showIcon
        message="审查口径"
        description="L1 规则只判「链路完整性」（输入/输出/工具是否齐全、是否被截断），不判答案正确性。答案正确性需题库标准解析，当前未接入，请勿把「已自动校验」读成「辅导正确」。"
      />

      <Card size="small">
        <Space wrap>
          <Typography.Text>状态</Typography.Text>
          <Select
            value={status}
            onChange={(v: string) => setStatus(v)}
            options={STATUS_OPTIONS}
            style={{ width: 230 }}
            aria-label="状态筛选"
          />
          <Input
            value={tenantDraft}
            onChange={(e) => setTenantDraft(e.target.value)}
            placeholder="租户 ID（可选，精确匹配）"
            style={{ width: 240 }}
            allowClear
          />
          <Button
            onClick={() => {
              setTenantFilter(tenantDraft.trim())
              reload()
            }}
          >
            查询
          </Button>
          <Button onClick={reload}>刷新</Button>
        </Space>
      </Card>

      {error !== null && (
        <Alert type="error" showIcon message="审查队列加载失败" description={error}
          action={<Button size="small" onClick={reload}>重试</Button>} />
      )}
      {actionError !== null && (
        <Alert type="error" showIcon message="操作未完成" description={actionError} />
      )}
      {actionOk !== null && (
        <Alert type="success" showIcon message={actionOk} />
      )}

      <Card size="small" title={`待审任务（${rows.length}）`}>
        <Spin spinning={loading}>
          <Table<ReviewTask>
            rowKey="taskId"
            size="small"
            dataSource={rows}
            columns={columns}
            scroll={{ x: scrollXOf(columns) }}
            pagination={{ pageSize: 20, showSizeChanger: false }}
            locale={{
              emptyText: error !== null
                ? '加载失败，无法判断是否有待审任务（不能当作空队列）'
                : emptyTextWhileLoading(loading, '当前筛选下暂无待审任务'),
            }}
          />
        </Spin>
      </Card>

      <TraceTranscriptDrawer
        runId={drawerRunId}
        tenantId={drawerTenantId}
        open={drawerOpen}
        onClose={closeDrawer}
      />

      <Modal
        title={actionTarget?.kind === 'resolve' ? '归因关闭审查任务' : '驳回审查任务'}
        open={actionTarget !== null}
        onCancel={() => setActionTarget(null)}
        onOk={() => void submitAction()}
        okText={actionTarget?.kind === 'resolve' ? '确认关闭' : '确认驳回'}
        cancelText="取消"
        confirmLoading={busyTaskId !== null}
        destroyOnHidden
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {actionTarget === null
              ? ''
              : `Run ${actionTarget.task.runId} · 来源 ${REVIEW_SOURCE_LABELS[actionTarget.task.source] ?? actionTarget.task.source}`}
          </Typography.Text>
          {actionTarget?.kind === 'resolve' && (
            <>
              <Typography.Text>归因根因</Typography.Text>
              <Select
                value={rootCause}
                onChange={(v: RootCause) => setRootCause(v)}
                options={ROOT_CAUSE_OPTIONS}
                style={{ width: '100%' }}
                aria-label="归因根因"
              />
            </>
          )}
          <Input.TextArea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="备注（可选；只写结论摘要，不要粘贴对话原文）"
            rows={3}
          />
          {actionError !== null && (
            <Alert type="error" showIcon message={actionError} />
          )}
        </Space>
      </Modal>
    </Space>
  )
}
