import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Input,
  Select,
  Space,
  Spin,
  Table,
  Typography,
} from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { getAuditLogs } from '@/api/adminApi'
import type { AuditLogEntry } from '@/api/types'
import { formatTime } from '@/pages/monitoring/monitoringFormat'

const { Text, Paragraph } = Typography

/** 时间窗（小时）→ 起始时刻；null = 不限（全量从游标头开始） */
function windowFrom(hours: number | null): string | null {
  if (hours === null) return null
  return new Date(Date.now() - hours * 3600_000).toISOString()
}

const WINDOW_FILTERS: Array<{ value: number | null; label: string }> = [
  { value: 1, label: '最近 1 小时' },
  { value: 24, label: '最近 24 小时' },
  { value: 72, label: '最近 3 天' },
  { value: 168, label: '最近 7 天' },
  { value: null, label: '不限' },
]

const PAGE_SIZE = 100

/**
 * 审计日志页（P5 · /audit）。
 *
 * <p>ledger 维度筛选 + 详情抽屉。⚠️ <b>只读</b>：
 * {@code audit_record} 有 {@code forbid_audit_mutation} 触发器，本页无任何写/删入口；
 * 且「查询审计」这件事本身会被后端 recordAccess 留痕（fail-open）——
 * 谁在什么时候查过审计，同样可追溯。
 *
 * <p>⚠️ <b>游标分页</b>：后端为追加只读游标分页（无 offset/total），
 * 翻页用 nextCursor 前进、用游标栈后退，不做「跳页」。
 */
export function AuditLogsPage(): JSX.Element {
  const [windowHours, setWindowHours] = useState<number | null>(24)
  const [action, setAction] = useState('')
  const [resourceType, setResourceType] = useState('')
  const [rows, setRows] = useState<AuditLogEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  const [cursorStack, setCursorStack] = useState<Array<string | null>>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [selected, setSelected] = useState<AuditLogEntry | null>(null)

  const load = useCallback(
    (cur: string | null, stack: Array<string | null>) => {
      setLoading(true)
      setError(null)
      getAuditLogs({
        action: action.trim() || undefined,
        resourceType: resourceType.trim() || undefined,
        from: windowFrom(windowHours) ?? undefined,
        limit: PAGE_SIZE,
        cursor: cur ?? undefined,
      })
        .then((page) => {
          setRows(page.items)
          setNextCursor(page.nextCursor)
          setCursor(cur)
          setCursorStack(stack)
        })
        .catch((e: unknown) => {
          setRows([])
          setNextCursor(null)
          setError(e instanceof Error ? e.message : '审计日志不可用')
        })
        .finally(() => setLoading(false))
    },
    [action, resourceType, windowHours],
  )

  useEffect(() => {
    load(null, [])
  }, [load])

  const renderDetail = (detail: Record<string, unknown>): JSX.Element => {
    const keys = Object.keys(detail)
    if (keys.length === 0) return <Text type="secondary">（无细节）</Text>
    return (
      <Paragraph>
        <pre
          style={{
            margin: 0,
            padding: 12,
            background: '#f6f6f6',
            borderRadius: 6,
            maxHeight: 360,
            overflow: 'auto',
            fontSize: 12,
          }}
        >
          {JSON.stringify(detail, null, 2)}
        </pre>
      </Paragraph>
    )
  }

  return (
    <Card
      title="审计日志"
      extra={
        <Space wrap>
          <Select
            value={windowHours}
            onChange={setWindowHours}
            options={WINDOW_FILTERS}
            style={{ width: 140 }}
            aria-label="时间窗"
          />
          <Input
            placeholder="按 action 过滤（如 CONFIG_APPROVE）"
            value={action}
            onChange={(e) => setAction(e.target.value)}
            allowClear
            style={{ width: 240 }}
            aria-label="action 过滤"
          />
          <Input
            placeholder="按资源类型过滤（如 agent）"
            value={resourceType}
            onChange={(e) => setResourceType(e.target.value)}
            allowClear
            style={{ width: 200 }}
            aria-label="资源类型过滤"
          />
          <Button icon={<ReloadOutlined />} onClick={() => load(null, [])} aria-label="刷新">
            刷新
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="审计记录不可篡改，查询行为本身亦留痕"
        description={
          <Text>
            audit_record 表有防篡改触发器（forbid_audit_mutation）：不可更新、不可删除。
            每次对本页的查询都会被记录一条 access 审计。
          </Text>
        }
      />

      {error && (
        <Alert
          type="error"
          showIcon
          message="审计日志不可用"
          description={<Text>{error}</Text>}
          style={{ marginBottom: 16 }}
        />
      )}

      <Spin spinning={loading}>
        <Table<AuditLogEntry>
          rowKey="auditId"
          size="small"
          dataSource={rows}
          pagination={false}
          locale={{ emptyText: '该筛选条件下没有审计记录' }}
          onRow={(record) => ({
            onClick: () => setSelected(record),
            style: { cursor: 'pointer' },
          })}
          columns={[
            { title: '时刻', dataIndex: 'createdAt', width: 180, render: (v: string) => formatTime(v) },
            { title: '动作', dataIndex: 'action', width: 200, render: (v: string) => <Text code>{v}</Text> },
            { title: '资源类型', dataIndex: 'resourceType', width: 140 },
            {
              title: '资源标识',
              dataIndex: 'resourceId',
              width: 220,
              render: (v: string | null) => v ?? '—',
            },
            { title: '操作者', dataIndex: 'actorId', width: 180 },
            {
              title: '关联标识',
              dataIndex: 'correlationId',
              width: 200,
              render: (v: string | null) =>
                v ? <Text copyable={{ text: v }}>{v}</Text> : '—',
            },
            {
              title: '细节',
              key: 'detail',
              render: (_: unknown, record: AuditLogEntry) => (
                <Text type="secondary">{Object.keys(record.detail).length} 项</Text>
              ),
            },
          ]}
        />
        <Space style={{ marginTop: 12 }}>
          <Button
            disabled={cursor === null || cursorStack.length === 0 || loading}
            onClick={() => {
              const stack = [...cursorStack]
              const prev = stack.pop() ?? null
              load(prev, stack)
            }}
          >
            上一页
          </Button>
          <Button
            disabled={nextCursor === null || loading}
            onClick={() => load(nextCursor, [...cursorStack, cursor])}
          >
            下一页
          </Button>
          <Text type="secondary">游标分页（追加只读，无跳页）</Text>
        </Space>
      </Spin>

      <Drawer
        title="审计条目详情"
        width={520}
        open={selected !== null}
        onClose={() => setSelected(null)}
      >
        {selected && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <div>
              <Text type="secondary">审计标识</Text>
              <div>
                <Text copyable={{ text: selected.auditId }}>{selected.auditId}</Text>
              </div>
            </div>
            <div>
              <Text type="secondary">动作</Text>
              <div>
                <Text code>{selected.action}</Text>
              </div>
            </div>
            <div>
              <Text type="secondary">资源</Text>
              <div>
                <Text>
                  {selected.resourceType}
                  {selected.resourceId ? ` / ${selected.resourceId}` : ''}
                </Text>
              </div>
            </div>
            <div>
              <Text type="secondary">操作者</Text>
              <div>
                <Text>{selected.actorId}</Text>
              </div>
            </div>
            <div>
              <Text type="secondary">时刻</Text>
              <div>
                <Text>{formatTime(selected.createdAt)}</Text>
              </div>
            </div>
            <div>
              <Text type="secondary">关联标识</Text>
              <div>
                {selected.correlationId ? (
                  <Text copyable={{ text: selected.correlationId }}>{selected.correlationId}</Text>
                ) : (
                  <Text type="secondary">—</Text>
                )}
              </div>
            </div>
            <div>
              <Text type="secondary">事件细节</Text>
              {renderDetail(selected.detail)}
            </div>
          </Space>
        )}
      </Drawer>
    </Card>
  )
}
