import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import {
  AUDIT_SOURCE_OPTIONS,
  AUDIT_TERMINAL_OPTIONS,
  getChatAuditRecordDetail,
  listChatAuditRecords,
} from '@/api/chatAuditRecordApi'
import type {
  AuditRecordSource,
  ChatAuditRecordDetail,
  ChatAuditRecordSummary,
} from '@/api/chatAuditRecordApi'
import { formatTime } from '@/pages/monitoring/monitoringFormat'
import { emptyTextWhileLoading } from '@/shared/loadingState'
import { scrollXOf } from '@/shared/tableLayout'

const { Text, Paragraph } = Typography

/** 时间窗「不限」哨兵（AntD Select option value 不允许 null）。 */
const NO_LIMIT = 0
const WINDOWS = [
  { value: 1, label: '最近 1 小时' },
  { value: 24, label: '最近 24 小时' },
  { value: 168, label: '最近 7 天' },
  { value: 720, label: '最近 30 天' },
  { value: NO_LIMIT, label: '不限' },
]
function windowFrom(hours: number): string | undefined {
  if (hours === NO_LIMIT) return undefined
  return new Date(Date.now() - hours * 3600_000).toISOString()
}

const TERMINAL_COLOR: Record<string, string> = {
  SUCCEEDED: 'green',
  FAILED: 'red',
  TIMED_OUT: 'orange',
  CANCELLED: 'default',
  CLIENT_DISCONNECTED: 'orange',
  REJECTED: 'red',
}
const SOURCE_LABEL: Record<string, string> = {
  RECORD: '已归属',
  UNATTRIBUTED: '建连失败',
}
const PAGE_SIZE = 50

/**
 * 对话审计记录查询页（平台治理 · 只读）。
 *
 * <p>读 {@code GET /api/platform/v1/chat-audit-records}：跨租户审计原文，
 * 含建连前失败记录。列表只给摘要，点行按需取详情（完整信封）——避免一次加载全库原文。
 * 需 {@code platform.audit.read}；页面角色守卫（AUDIT_SCOPE）只管导航显隐，安全边界在后端。
 *
 * <p>⚠️ 只读页：无任何写/删入口。审计原文按 PIPL 处理，保留期由后端配置页管理。
 */
export function ChatAuditRecordListPage(): JSX.Element {

  const [source, setSource] = useState<AuditRecordSource | 'ALL'>('ALL')
  const [windowHours, setWindowHours] = useState<number>(24)
  const [terminalState, setTerminalState] = useState<string>()
  const [endpoint, setEndpoint] = useState('')
  const [tenantId, setTenantId] = useState('')

  const [rows, setRows] = useState<ChatAuditRecordSummary[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null)

  const [selected, setSelected] = useState<ChatAuditRecordSummary | null>(null)
  const [detail, setDetail] = useState<ChatAuditRecordDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState<string | null>(null)

  // 同参在途去重（StrictMode 双发 + 手动刷新不叠加）。
  const inflightRef = useRef<{ key: string; promise: Promise<void> } | null>(null)

  const filterKey = JSON.stringify([
    source, windowHours, terminalState ?? '', endpoint.trim(), tenantId.trim(),
  ])

  const load = useCallback(async (): Promise<void> => {
    const key = `first:${filterKey}`
    const inflight = inflightRef.current
    if (inflight !== null && inflight.key === key) return inflight.promise

    const promise = (async () => {
      setLoading(true)
      setError(null)
      setLoadMoreError(null)
      setSelected(null)
      setDetail(null)
      try {
        const page = await listChatAuditRecords({
          source,
          from: windowFrom(windowHours),
          terminalState,
          endpoint: endpoint.trim() || undefined,
          tenantId: tenantId.trim() || undefined,
          limit: PAGE_SIZE,
        })
        setRows(page.items)
        setNextCursor(page.nextCursor)
      } catch (e) {
        setRows([])
        setNextCursor(null)
        setError(e instanceof Error ? e.message : '对话审计记录不可用')
      } finally {
        setLoading(false)
      }
    })()
    inflightRef.current = { key, promise }
    void promise.finally(() => {
      if (inflightRef.current?.promise === promise) inflightRef.current = null
    })
    return promise
  }, [filterKey, source, windowHours, terminalState, endpoint, tenantId])

  useEffect(() => {
    void load()
  }, [load])

  const loadMore = useCallback(async (): Promise<void> => {
    if (nextCursor === null || loadingMore) return
    setLoadingMore(true)
    setLoadMoreError(null)
    try {
      const page = await listChatAuditRecords({
        source,
        from: windowFrom(windowHours),
        terminalState,
        endpoint: endpoint.trim() || undefined,
        tenantId: tenantId.trim() || undefined,
        cursor: nextCursor,
        limit: PAGE_SIZE,
      })
      setRows((prev) => [...prev, ...page.items])
      setNextCursor(page.nextCursor)
    } catch (e) {
      setLoadMoreError(e instanceof Error ? e.message : '加载更多失败')
    } finally {
      setLoadingMore(false)
    }
  }, [nextCursor, loadingMore, source, windowHours, terminalState, endpoint, tenantId])

  const openDetail = useCallback(async (row: ChatAuditRecordSummary): Promise<void> => {
    setSelected(row)
    setDetail(null)
    setDetailError(null)
    setDetailLoading(true)
    try {
      const d = await getChatAuditRecordDetail(row.source, row.auditId)
      setDetail(d)
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : '详情加载失败')
    } finally {
      setDetailLoading(false)
    }
  }, [])

  const columns = useMemo<TableColumnsType<ChatAuditRecordSummary>>(() => [
    {
      title: '时间',
      dataIndex: 'startedAt',
      width: 170,
      render: (v: string) => formatTime(v),
    },
    {
      title: '来源',
      dataIndex: 'source',
      width: 100,
      render: (v: AuditRecordSource) => (
        <Tag color={v === 'RECORD' ? 'blue' : 'purple'}>{SOURCE_LABEL[v] ?? v}</Tag>
      ),
    },
    {
      title: '终态',
      dataIndex: 'terminalState',
      width: 140,
      render: (v: string) => <Tag color={TERMINAL_COLOR[v] ?? 'default'}>{v}</Tag>,
    },
    {
      title: '用户问题 / 拒绝原因',
      key: 'preview',
      width: 280,
      render: (_: unknown, r: ChatAuditRecordSummary) => (
        <Text ellipsis title={r.messagePreview ?? r.rejectionReason ?? undefined}>
          {r.messagePreview ?? r.rejectionReason ?? <Text type="secondary">—</Text>}
        </Text>
      ),
    },
    {
      title: '端点',
      dataIndex: 'endpoint',
      width: 210,
      render: (v: string) => <Text code ellipsis title={v}>{v}</Text>,
    },
    {
      title: '域',
      dataIndex: 'domain',
      width: 90,
      render: (v: string | null) => v ?? <Text type="secondary">—</Text>,
    },
    {
      title: '租户 ID',
      dataIndex: 'tenantId',
      width: 200,
      render: (v: string | null) => (
        v ? <Text code copyable={{ text: v }}>{v.slice(0, 8)}…</Text>
          : <Text type="secondary">—</Text>
      ),
    },
    {
      title: 'HTTP',
      key: 'http',
      width: 110,
      render: (_: unknown, r: ChatAuditRecordSummary) =>
        `${r.httpMethod} ${r.httpStatus || '—'}`,
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      width: 90,
      render: (v: number) => (v > 0 ? `${v} ms` : '—'),
    },
  ], [])

  return (
    <div>
      <Typography.Title level={3}>对话审计记录</Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="跨租户审计原文（只读）"
        description="记录聊天相关请求从头到尾的完整信封：用户问题、历史、模型回答、thinking、逐分片、RAG 命中、工具调用与终态；含建连前失败（401/400/429，仅元数据）。仅效果评估/审计角色可读，无任何写删入口。"
      />
      <Card
        extra={
          <Space wrap>
            <Select
              style={{ width: 190 }}
              value={source}
              onChange={(v) => setSource(v)}
              options={AUDIT_SOURCE_OPTIONS}
            />
            <Select
              style={{ width: 130 }}
              value={windowHours}
              onChange={(v) => setWindowHours(v)}
              options={WINDOWS}
            />
            <Select
              allowClear
              placeholder="终态"
              style={{ width: 170 }}
              value={terminalState}
              onChange={(v) => setTerminalState(v)}
              options={AUDIT_TERMINAL_OPTIONS.map((v) => ({ value: v, label: v }))}
            />
            <Input
              allowClear
              placeholder="端点（精确）"
              style={{ width: 200 }}
              value={endpoint}
              onChange={(e) => setEndpoint(e.target.value)}
            />
            <Input
              allowClear
              placeholder="租户 ID（UUID）"
              style={{ width: 190 }}
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
            />
            <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
              刷新
            </Button>
          </Space>
        }
      >
        {error && (
          <Alert
            type="error"
            showIcon
            message="加载失败"
            description={error}
            style={{ marginBottom: 16 }}
          />
        )}
        <Spin spinning={loading}>
          <Table<ChatAuditRecordSummary>
            rowKey="auditId"
            size="small"
            dataSource={rows}
            pagination={false}
            locale={{ emptyText: emptyTextWhileLoading(loading, '该条件下无审计记录') }}
            onRow={(r) => ({ onClick: () => void openDetail(r), style: { cursor: 'pointer' } })}
            scroll={{ x: scrollXOf(columns) }}
            columns={columns}
          />
        </Spin>
        {nextCursor !== null && (
          <div style={{ textAlign: 'center', marginTop: 12 }}>
            {loadMoreError && (
              <Alert
                type="error"
                showIcon
                message="加载更多失败"
                description={loadMoreError}
                style={{ marginBottom: 8, textAlign: 'left' }}
              />
            )}
            <Button onClick={() => void loadMore()} loading={loadingMore}>
              加载更多
            </Button>
          </div>
        )}
      </Card>

      <Drawer
        open={selected !== null}
        title="审计记录详情"
        width={720}
        onClose={() => {
          setSelected(null)
          setDetail(null)
          setDetailError(null)
        }}
        destroyOnHidden
      >
        {selected && (
          <>
            <Descriptions bordered size="small" column={1} items={[
              { key: 'auditId', label: '审计 ID', children: <Text copyable code>{selected.auditId}</Text> },
              { key: 'traceId', label: 'Trace ID', children: <Text copyable code>{selected.traceId}</Text> },
              { key: 'time', label: '开始时间', children: formatTime(selected.startedAt) },
              {
                key: 'terminal',
                label: '终态',
                children: <Tag color={TERMINAL_COLOR[selected.terminalState] ?? 'default'}>{selected.terminalState}</Tag>,
              },
              { key: 'endpoint', label: '端点', children: <Text code>{selected.endpoint}</Text> },
              { key: 'http', label: 'HTTP', children: `${selected.httpMethod} ${selected.httpStatus || '—'}` },
            ]} />
            <div style={{ marginTop: 16 }}>
              {detailLoading && <Spin />}
              {detailLoading && <Text type="secondary" style={{ marginLeft: 8 }}>加载完整信封…</Text>}
              {detailError && <Alert type="error" showIcon message="详情加载失败" description={detailError} />}
              {detail && <EnvelopeView detail={detail} />}
            </div>
          </>
        )}
      </Drawer>
    </div>
  )
}

/** 完整信封渲染（只展示有内容的分段；原文以 pre 保留格式）。 */
function EnvelopeView({ detail }: { detail: ChatAuditRecordDetail }): JSX.Element {
  const p = detail.payload
  const events = Array.isArray(p.events) ? p.events : []
  const chunks = Array.isArray(p.chunks) ? p.chunks : []
  const history = Array.isArray(p.request?.history) ? p.request!.history : []
  const modelMessages = Array.isArray(p.request?.modelMessages) ? p.request!.modelMessages : []

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <div>
        <Typography.Title level={5}>用户问题</Typography.Title>
        {p.request?.message
          ? <Paragraph><pre style={{ whiteSpace: 'pre-wrap' }}>{p.request.message}</pre></Paragraph>
          : <Text type="secondary">未采集或未留存</Text>}
      </div>
      {history.length > 0 && (
        <div>
          <Typography.Title level={5}>对话历史（{history.length}）</Typography.Title>
          <pre style={{ whiteSpace: 'pre-wrap', maxHeight: 200, overflow: 'auto' }}>
            {history.map((t) => `${t.role}: ${t.content}`).join('\n')}
          </pre>
        </div>
      )}
      <div>
        <Typography.Title level={5}>最终回答</Typography.Title>
        {p.result?.finalAnswer
          ? <pre style={{ whiteSpace: 'pre-wrap' }}>{p.result.finalAnswer}</pre>
          : <Text type="secondary">无最终回答（异常/未编排）</Text>}
      </div>
      {chunks.length > 0 && (
        <div>
          <Typography.Title level={5}>逐分片（{chunks.length}，delta / thinking）</Typography.Title>
          <pre style={{ whiteSpace: 'pre-wrap', maxHeight: 240, overflow: 'auto' }}>
            {chunks.map((c) => `[${c.seq}] ${c.kind}: ${c.text}`).join('\n')}
          </pre>
        </div>
      )}
      {events.length > 0 && (
        <div>
          <Typography.Title level={5}>事件帧（{events.length}）</Typography.Title>
          <pre style={{ whiteSpace: 'pre-wrap', maxHeight: 240, overflow: 'auto' }}>
            {events.map((e) => `[${e.seq}] ${e.name}: ${e.data ?? ''}`).join('\n')}
          </pre>
        </div>
      )}
      {modelMessages.length > 0 && (
        <div>
          <Typography.Title level={5}>送模型 Messages（{modelMessages.length}）</Typography.Title>
          <pre style={{ whiteSpace: 'pre-wrap', maxHeight: 240, overflow: 'auto' }}>
            {modelMessages.map((t) => `${t.role}: ${t.content}`).join('\n')}
          </pre>
        </div>
      )}
      {p.error && (
        <div>
          <Typography.Title level={5}>错误</Typography.Title>
          <Alert
            type="error"
            showIcon
            message={`${p.error.code} · ${p.error.phase ?? '未知阶段'}`}
            description={p.error.message}
          />
        </div>
      )}
      {detail.source === 'UNATTRIBUTED' && (
        <div>
          <Typography.Title level={5}>拒绝元数据</Typography.Title>
          <Alert
            type="warning"
            showIcon
            message="建连前失败：只存结构性元数据，不含正文或凭据"
          />
          <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(p, null, 2)}</pre>
        </div>
      )}
      {events.length === 0 && chunks.length === 0 && !p.result?.finalAnswer && !p.error
        && detail.source === 'RECORD' && <Empty description="信封无正文分段" />}
    </Space>
  )
}
