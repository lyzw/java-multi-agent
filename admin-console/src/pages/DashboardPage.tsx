import { useEffect, useState } from 'react'
import {
  Row,
  Col,
  Card,
  Statistic,
  Typography,
  Tag,
  Alert,
  Space,
  App as AntApp,
  Spin,
} from 'antd'
import { Link } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { getRoles, getDashboard, getMonitoringHealth } from '@/api/adminApi'
import { NAV_NODES } from '@/layout/navConfig'
import type { DashboardSummary, MonitoringHealth } from '@/api/types'
import {
  STATUS_LABELS,
} from './monitoring/monitoringFormat'

export function DashboardPage(): JSX.Element {
  const { roles } = useSession()
  const { message } = AntApp.useApp()
  const [backendRoles, setBackendRoles] = useState<string[] | null>(null)
  const [summary, setSummary] = useState<DashboardSummary | null>(null)
  const [health, setHealth] = useState<MonitoringHealth | null>(null)
  const [healthUnavailable, setHealthUnavailable] = useState(false)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const [r, s] = await Promise.all([
          getRoles(),
          getDashboard().catch(() => null),
        ])
        if (!active) return
        setBackendRoles(r.roles)
        setSummary(s)
      } catch (e) {
        if (active) message.warning(e instanceof Error ? e.message : '加载概览失败')
      } finally {
        if (active) setLoading(false)
      }
    })()
    return () => {
      active = false
    }
  }, [message])

  /**
   * 运行健康（P4 回填）。
   *
   * ⚠️ <b>失败时呈现为「不可用」而非 0</b>：监控数据源故障会让后端返回 503，
   * 此时若把计数留空/置 0，仪表盘看起来就是「一切正常」——
   * 那是最危险的展示形态。故单独用 unavailable 标记区分「没有数据」与「没有拒绝」。
   */
  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const h = await getMonitoringHealth({ windowHours: 24 })
        if (!active) return
        setHealth(h)
        setHealthUnavailable(false)
      } catch {
        if (!active) return
        setHealth(null)
        setHealthUnavailable(true)
      }
    })()
    return () => {
      active = false
    }
  }, [])

  if (loading) {
    return <Spin spinning style={{ width: '100%', marginTop: 80 }} />
  }

  const displayRoles = (backendRoles && backendRoles.length > 0 ? backendRoles : roles) as string[]

  return (
    <div>
      <Typography.Title level={3}>概览仪表盘</Typography.Title>
      <Card style={{ marginBottom: 16 }}>
        <Space direction="vertical" size={8}>
          <Typography.Text strong>
            当前账号角色（前端导航显隐用，安全以后端重授权为准）
          </Typography.Text>
          <Space wrap>
            {displayRoles.map((r) => (
              <Tag color="blue" key={r}>
                {r}
              </Tag>
            ))}
            {displayRoles.length === 0 && (
              <Typography.Text type="secondary">
                （未返回角色，部分菜单不可见）
              </Typography.Text>
            )}
          </Space>
        </Space>
      </Card>

      <Row gutter={16}>
        {summary && (
          <>
            <Col span={6}>
              <Card>
                <Statistic title="租户" value={summary.tenantCount} />
              </Card>
            </Col>
            <Col span={6}>
              <Card>
                <Statistic title="Agent" value={summary.agentCount} />
              </Card>
            </Col>
            <Col span={6}>
              <Card>
                <Statistic title="Tool" value={summary.toolCount} />
              </Card>
            </Col>
            <Col span={6}>
              <Card>
                <Statistic title="Skill" value={summary.skillCount} />
              </Card>
            </Col>
          </>
        )}
      </Row>

      <Card title="运行健康" style={{ marginTop: 16 }}>
        {healthUnavailable ? (
          <Alert
            type="error"
            showIcon
            message="监控数据不可用（≠ 健康）"
            description={
              <Typography.Text>
                运行监控数据源当前不可达，后端刻意返回 503 而非空快照。
                这意味着「看不到」不等于「一切正常」——
                请到 <Link to="/monitoring">运行监控</Link> 页确认是否需要人工对账。
              </Typography.Text>
            }
          />
        ) : health ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
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
                <Statistic title="被撤权主体" value={health.authzWatermark.revokedSubjects} />
              </Col>
              <Col span={6}>
                <Statistic title="提示/警告拒绝" value={health.warningCount + health.infoCount} />
              </Col>
            </Row>
            {health.status === 'DEGRADED' && (
              <Alert
                type="warning"
                showIcon
                message="健康状态为「降级」"
                description={
                  health.criticalCount > 0
                    ? `窗口内存在 ${health.criticalCount} 条严重拒绝（越权 / 授权依据失效 / 副作用结果不确定），请优先排查。`
                    : '决策日志审计覆盖不达标，这批拒绝记录本身不可作为完整追责依据。'
                }
              />
            )}
            <Typography.Text type="secondary">
              逐条排查请见 <Link to="/monitoring">运行监控</Link>。
            </Typography.Text>
          </Space>
        ) : (
          <Typography.Text type="secondary">运行健康数据加载中…</Typography.Text>
        )}
      </Card>

      <Card title="模块导航" style={{ marginTop: 16 }}>
        <Row gutter={[16, 16]}>
          {NAV_NODES.filter((n) => n.key !== 'dashboard').map((n) => (
            <Col span={8} key={n.key}>
              <Card size="small" title={<Space>{n.icon}{n.label}</Space>}>
                <Typography.Paragraph type="secondary" style={{ marginBottom: 4 }}>
                  {n.description}
                </Typography.Paragraph>
                <Tag>{n.phase} 阶段</Tag>
              </Card>
            </Col>
          ))}
        </Row>
      </Card>
    </div>
  )
}
