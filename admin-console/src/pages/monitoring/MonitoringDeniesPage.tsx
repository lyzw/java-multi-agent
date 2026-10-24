import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Card,
  Input,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import { getMonitoringDenies } from '@/api/adminApi'
import type { DenyAlert, DenySeverity } from '@/api/types'
import {
  SEVERITY_COLORS,
  SEVERITY_LABELS,
  WINDOW_OPTIONS,
  formatTime,
  reasonLabel,
} from './monitoringFormat'

const { Text } = Typography

const SEVERITY_FILTERS: Array<{ value: string; label: string }> = [
  { value: 'ALL', label: '全部级别' },
  { value: 'CRITICAL', label: '严重' },
  { value: 'WARNING', label: '警告' },
  { value: 'INFO', label: '提示' },
]

/**
 * 拒绝告警页（P4 · /monitoring/denies）。
 *
 * <p>逐条排查「最近拒绝了什么」：严重级别、权威原因码、主体、工具与时刻。
 *
 * <p>⚠️ <b>只读</b>：本页不提供「放行 / 撤销 / 处置」按钮。
 * 拒绝是<b>结论</b>，处置权在各自的通道；在此提供处置入口等于让观测页
 * 承担执行职责，而执行需要的是审计上下文，不是列表页的一行。
 *
 * <p>⚠️ <b>级别筛选在前端做</b>：后端口径只返回 DENY 记录（ALLOW 不是告警素材），
 * 级别分档是展示层职责；窗口与条数才是服务端口径。
 */
export function MonitoringDeniesPage(): JSX.Element {
  const [windowHours, setWindowHours] = useState(24)
  const [severity, setSeverity] = useState('ALL')
  const [keyword, setKeyword] = useState('')
  const [alerts, setAlerts] = useState<DenyAlert[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback((hours: number) => {
    setLoading(true)
    setError(null)
    getMonitoringDenies({ windowHours: hours, limit: 200 })
      .then((rows) => setAlerts(rows))
      .catch((e: unknown) => {
        setAlerts([])
        setError(e instanceof Error ? e.message : '拒绝告警不可用')
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load(windowHours)
  }, [load, windowHours])

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    return alerts.filter((a) => {
      if (severity !== 'ALL' && a.severity !== severity) return false
      if (!kw) return true
      return (
        a.reasonCode.toLowerCase().includes(kw) ||
        (a.principalId ?? '').toLowerCase().includes(kw) ||
        (a.toolId ?? '').toLowerCase().includes(kw) ||
        a.invocationId.toLowerCase().includes(kw)
      )
    })
  }, [alerts, severity, keyword])

  return (
    <Card
      title="拒绝告警"
      extra={
        <Space wrap>
          <Select
            value={windowHours}
            onChange={setWindowHours}
            options={WINDOW_OPTIONS}
            style={{ width: 160 }}
            aria-label="监控窗口"
          />
          <Select
            value={severity}
            onChange={setSeverity}
            options={SEVERITY_FILTERS}
            style={{ width: 140 }}
            aria-label="严重级别"
          />
          <Input
            placeholder="按原因码 / 主体 / 工具 / 调用标识过滤"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            allowClear
            style={{ width: 280 }}
            aria-label="关键字"
          />
        </Space>
      }
    >
      {error && (
        <Alert
          type="error"
          showIcon
          message="拒绝告警不可用"
          description={
            <Text>
              {error}
              。后端在数据源故障时返回 503 而非空列表 ——
              空列表会被误读成「没有拒绝」。
            </Text>
          }
          style={{ marginBottom: 16 }}
        />
      )}

      <Spin spinning={loading}>
        <Table<DenyAlert>
          rowKey="invocationId"
          size="small"
          dataSource={filtered}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          locale={{ emptyText: '该窗口内没有拒绝记录' }}
          columns={[
            {
              title: '级别',
              dataIndex: 'severity',
              width: 90,
              render: (v: DenySeverity) => (
                <Tag color={SEVERITY_COLORS[v]}>{SEVERITY_LABELS[v]}</Tag>
              ),
            },
            {
              title: '原因码',
              dataIndex: 'reasonCode',
              width: 260,
              render: (v: string) => (
                <Space direction="vertical" size={0}>
                  <Text code>{v}</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {reasonLabel(v)}
                  </Text>
                </Space>
              ),
            },
            {
              title: '主体',
              dataIndex: 'principalId',
              width: 160,
              render: (v: string | null) => v ?? '—',
            },
            {
              title: '工具',
              dataIndex: 'toolId',
              width: 140,
              render: (v: string | null) => v ?? '—',
            },
            {
              title: 'Run',
              dataIndex: 'runId',
              width: 200,
              render: (v: string | null) =>
                v ? <Text copyable={{ text: v }}>{v}</Text> : '—',
            },
            {
              title: '调用标识',
              dataIndex: 'invocationId',
              width: 220,
              render: (v: string) => <Text copyable={{ text: v }}>{v}</Text>,
            },
            {
              title: '时刻',
              dataIndex: 'occurredAt',
              width: 180,
              render: (v: string) => formatTime(v),
            },
          ]}
        />
      </Spin>
    </Card>
  )
}
