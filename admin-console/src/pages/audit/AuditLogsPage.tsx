import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Descriptions,
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
import { getPlatformAuditEvents } from '@/api/adminApi'
import type { PlatformAuditEvent } from '@/api/types'
import { formatTime } from '@/pages/monitoring/monitoringFormat'
import { usePageTitle } from '@/shared/usePageTitle'
import { emptyTextWhileLoading } from '@/shared/loadingState'
import { scrollXOf } from '@/shared/tableLayout'

const { Text } = Typography

/** 时间窗（小时）→ ISO 起始时刻；null = 不限 */
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

const DECISION_COLOR: Record<string, string> = {
  ALLOW: 'green',
  ADMIT: 'green',
  DENY: 'red',
  REJECT: 'red',
}

/**
 * 平台审计页（重规划 §5.3 · /audit）。
 *
 * <p>读 {@code GET /api/platform/v1/audit-events}（platform.platform_audit_event，
 * 与租户业务审计**物理分域**、无 tenant_id）。<b>只读</b> —— 平台审计仅追加，
 * 本页无任何写/删入口。需 {@code platform.audit.read}（PLATFORM_AUDITOR 等）。
 */
export function AuditLogsPage(): JSX.Element {
  const [windowHours, setWindowHours] = useState<number | null>(24)
  const [action, setAction] = useState('')
  const [actorId, setActorId] = useState('')
  const [targetType, setTargetType] = useState('')
  const [rows, setRows] = useState<PlatformAuditEvent[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<PlatformAuditEvent | null>(null)

  usePageTitle('平台审计')

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await getPlatformAuditEvents({
        action: action.trim() || undefined,
        actorId: actorId.trim() || undefined,
        targetType: targetType.trim() || undefined,
        from: windowFrom(windowHours) ?? undefined,
        limit: 200,
      })
      setRows(data)
    } catch (e) {
      setError(e instanceof Error ? e.message : '平台审计不可用')
    } finally {
      setLoading(false)
    }
  }, [windowHours, action, actorId, targetType])

  useEffect(() => {
    void load()
  }, [load])

  // ⚠️ 列宽显式声明：表格按列宽求和定横向滚动宽（见 scrollXOf 的说明）。
  const columns = useMemo<TableColumnsType<PlatformAuditEvent>>(() => [
              {
                title: '时刻',
                dataIndex: 'occurredAt',
                width: 170,
                render: (v: string) => formatTime(v),
              },
              {
                title: '操作者',
                dataIndex: 'actorId',
                width: 200,
                render: (v: string) => <Text copyable={{ text: v }} code>{v.slice(0, 18)}{v.length > 18 ? '…' : ''}</Text>,
              },
              { title: '操作者类型', dataIndex: 'actorType', width: 110 },
              { title: '动作', dataIndex: 'action', width: 200 },
              { title: '目标', dataIndex: 'targetType', width: 120 },
              {
                title: '决策',
                dataIndex: 'decision',
                width: 90,
                render: (v: string) => (
                  <Tag color={DECISION_COLOR[v] ?? 'default'}>{v}</Tag>
                ),
              },
              {
                title: '票据档位',
                dataIndex: 'ticketScope',
                width: 100,
                // 空值不是「数据丢了」：非票据动作、或与档位无关的拒绝（通道开关关闭）本就没有档位。
                render: (v: string | null) => v ?? '-',
              },
              {
                title: '原因码',
                dataIndex: 'reasonCode',
                width: 260,
                render: (v: string | null) => v ?? '-',
              },
  ], [])

  return (
    <div>
      <Typography.Title level={3}>平台审计</Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="平台域审计（与租户业务审计分域）"
        description="记录平台管理动作（登录/租户生命周期/配额/能力授权等），物理隔离于租户审计 ledger。仅追加，无任何写/删入口。"
      />
      <Card
        extra={
          <Space wrap>
            <Select
              style={{ width: 140 }}
              value={windowHours}
              onChange={(v) => setWindowHours(v)}
              options={WINDOW_FILTERS}
            />
            <Input
              allowClear
              placeholder="action 精确匹配"
              style={{ width: 180 }}
              value={action}
              onChange={(e) => setAction(e.target.value)}
            />
            <Input
              allowClear
              placeholder="操作者 ID"
              style={{ width: 180 }}
              value={actorId}
              onChange={(e) => setActorId(e.target.value)}
            />
            <Input
              allowClear
              placeholder="目标类型"
              style={{ width: 140 }}
              value={targetType}
              onChange={(e) => setTargetType(e.target.value)}
            />
            <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
              刷新
            </Button>
          </Space>
        }
      >
        {error && (
          <Alert type="error" showIcon message="加载失败" description={error}
            style={{ marginBottom: 16 }} />
        )}
        <Spin spinning={loading}>
          <Table<PlatformAuditEvent>
            rowKey="id"
            size="small"
            dataSource={rows}
            pagination={{ pageSize: 50, showSizeChanger: false }}
            locale={{ emptyText: emptyTextWhileLoading(loading, '窗口内无平台审计事件') }}
            onRow={(r) => ({ onClick: () => setSelected(r), style: { cursor: 'pointer' } })}
            // ⚠️ 8 列不设 scroll.x，AntD 会把列挤进容器宽度，「原因码」表头被压成竖排 3 行。
            scroll={{ x: scrollXOf(columns) }}
            columns={columns}
          />
        </Spin>
      </Card>

      <Drawer
        open={selected !== null}
        title="审计事件详情"
        width={520}
        onClose={() => setSelected(null)}
      >
        {selected && (
          <Descriptions
            bordered
            size="small"
            column={1}
            items={[
              { key: 'id', label: '事件 ID', children: <Text copyable>{selected.id}</Text> },
              { key: 'time', label: '时刻', children: formatTime(selected.occurredAt) },
              { key: 'actor', label: '操作者', children: `${selected.actorType} / ${selected.actorId}` },
              { key: 'action', label: '动作', children: selected.action },
              {
                key: 'target',
                label: '目标',
                children: selected.targetId
                  ? `${selected.targetType} / ${selected.targetId}`
                  : selected.targetType,
              },
              {
                key: 'decision',
                label: '决策',
                children: <Tag color={DECISION_COLOR[selected.decision] ?? 'default'}>{selected.decision}</Tag>,
              },
              { key: 'reason', label: '原因码', children: selected.reasonCode ?? '-' },
              {
                key: 'ticketScope',
                label: '票据档位',
                children: selected.ticketScope ?? '-',
              },
              {
                key: 'corr',
                label: '关联 ID',
                children: selected.correlationId ? (
                  <Text copyable>{selected.correlationId}</Text>
                ) : (
                  '-'
                ),
              },
              { key: 'ip', label: '来源 IP', children: selected.ip ?? '-' },
            ]}
          />
        )}
      </Drawer>
    </div>
  )
}
