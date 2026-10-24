import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Card,
  Col,
  Descriptions,
  Row,
  Select,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd'
import { Link } from 'react-router-dom'
import { getMonitoringHealth } from '@/api/adminApi'
import type { DenyAlert, MonitoringHealth } from '@/api/types'
import {
  SEVERITY_COLORS,
  SEVERITY_LABELS,
  STATUS_COLORS,
  STATUS_LABELS,
  WINDOW_OPTIONS,
  formatTime,
  reasonLabel,
} from './monitoringFormat'

const { Paragraph, Text } = Typography

/**
 * 运行健康总览页（P4 · /monitoring）。
 *
 * <p>回答三个问题：<b>现在健康吗</b>（状态）、<b>谁被撤过权</b>（授权水位）、
 * <b>刚刚拒绝了什么</b>（近期拒绝）。
 *
 * <p>⚠️ <b>只读</b>：本页不提供任何处置入口。监控是观测通道，
 * 处置动作在各自的既有通道（查审计 / 改配置 / 走发布）——
 * 提供「一键处理」会把观测异化为处置，且处置的正确入口不在这里。
 *
 * <p>⚠️ <b>数据源不可用 ≠ 一切正常</b>：后端在数据源故障时返回 503
 * （不返空快照冒充正常），本页把该状态显式呈现为红色告警，
 * 而不是显示成「0 个拒绝」的健康态。
 */
export function MonitoringHealthPage(): JSX.Element {
  const [windowHours, setWindowHours] = useState(24)
  const [health, setHealth] = useState<MonitoringHealth | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback((hours: number) => {
    setLoading(true)
    setError(null)
    getMonitoringHealth({ windowHours: hours })
      .then((h) => setHealth(h))
      .catch((e: unknown) => {
        setHealth(null)
        setError(e instanceof Error ? e.message : '监控数据不可用')
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load(windowHours)
  }, [load, windowHours])

  const coverage = health?.coverage
  const coverageIncomplete = coverage ? coverage.incompleteCount > 0 || coverage.nonSpecificDenyCount > 0 : false

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        title="运行健康总览"
        extra={
          <Select
            value={windowHours}
            onChange={setWindowHours}
            options={WINDOW_OPTIONS}
            style={{ width: 160 }}
            aria-label="监控窗口"
          />
        }
      >
        {error && (
          <Alert
            type="error"
            showIcon
            message="监控数据不可用"
            description={
              <Text>
                {error}
                。这是<b>数据源不可用</b>，不代表系统健康 ——
                后端刻意不返回空快照，避免把「看不到」显示成「一切正常」。
              </Text>
            }
            style={{ marginBottom: 16 }}
          />
        )}

        <Spin spinning={loading}>
          {health ? (
            <>
              <Row gutter={16}>
                <Col span={6}>
                  <Statistic
                    title="健康状态"
                    value={STATUS_LABELS[health.status]}
                    valueStyle={{
                      color:
                        health.status === 'HEALTHY'
                          ? '#389e0d'
                          : health.status === 'DEGRADED'
                            ? '#d46b08'
                            : '#cf1322',
                    }}
                  />
                </Col>
                <Col span={6}>
                  <Statistic title="严重拒绝" value={health.criticalCount} />
                </Col>
                <Col span={6}>
                  <Statistic title="警告拒绝" value={health.warningCount} />
                </Col>
                <Col span={6}>
                  <Statistic title="提示拒绝" value={health.infoCount} />
                </Col>
              </Row>

              {health.status === 'DEGRADED' && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginTop: 16 }}
                  message="健康状态为「降级」"
                  description={
                    health.criticalCount > 0
                      ? `窗口内存在 ${health.criticalCount} 条严重拒绝（越权 / 授权依据失效 / 副作用结果不确定），请优先排查。`
                      : '决策日志审计覆盖不达标，这批拒绝记录本身不可作为完整追责依据。'
                  }
                />
              )}

              <Descriptions
                title="授权版本水位"
                column={2}
                bordered
                size="small"
                style={{ marginTop: 16 }}
              >
                <Descriptions.Item label="持有授权版本的主体">
                  <Space>
                    <span>{health.authzWatermark.subjectCount}</span>
                    <Tag color={STATUS_COLORS[health.status]}>
                      {STATUS_LABELS[health.status]}
                    </Tag>
                  </Space>
                </Descriptions.Item>
                <Descriptions.Item label="被撤权过的主体">
                  {health.authzWatermark.revokedSubjects}
                </Descriptions.Item>
                <Descriptions.Item label="最大授权版本号">
                  {health.authzWatermark.maxAuthzVersion}
                </Descriptions.Item>
                <Descriptions.Item label="最近一次版本递增">
                  {formatTime(health.authzWatermark.lastBumpAt)}
                </Descriptions.Item>
                <Descriptions.Item label="观测时刻">
                  {formatTime(health.observedAt)}
                </Descriptions.Item>
                <Descriptions.Item label="审计覆盖">
                  {coverage
                    ? `总记录 ${coverage.total} · DENY ${coverage.denyCount} · 不完整 ${coverage.incompleteCount} · 笼统 ${coverage.nonSpecificDenyCount}`
                    : '—'}
                  {coverageIncomplete && (
                    <Tag color="orange" style={{ marginLeft: 8 }}>
                      不达标
                    </Tag>
                  )}
                </Descriptions.Item>
              </Descriptions>

              <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
                水位口径：版本号 &gt; 1 表示该主体<b>至少被撤权过一次</b>，
                不代表其当前无权限；当前授权结论以发布指针下的闭包为准。
                逐条排查请见{' '}
                <Link to="/monitoring/denies">拒绝告警</Link>。
              </Paragraph>
            </>
          ) : (
            !error && <Text type="secondary">加载中…</Text>
          )}
        </Spin>
      </Card>

      {health && health.recentDenies.length > 0 && (
        <Card title="最近拒绝（窗口内）" size="small">
          <Table<DenyAlert>
            rowKey="invocationId"
            size="small"
            pagination={false}
            dataSource={health.recentDenies.slice(0, 20)}
            columns={[
              {
                title: '级别',
                dataIndex: 'severity',
                render: (v: DenyAlert['severity']) => (
                  <Tag color={SEVERITY_COLORS[v]}>{SEVERITY_LABELS[v]}</Tag>
                ),
              },
              {
                title: '原因',
                dataIndex: 'reasonCode',
                render: (v: string) => <Text code>{reasonLabel(v)}</Text>,
              },
              { title: '主体', dataIndex: 'principalId', render: (v) => v ?? '—' },
              { title: '工具', dataIndex: 'toolId', render: (v) => v ?? '—' },
              {
                title: '时刻',
                dataIndex: 'occurredAt',
                render: (v: string) => formatTime(v),
              },
            ]}
          />
        </Card>
      )}
    </Space>
  )
}
