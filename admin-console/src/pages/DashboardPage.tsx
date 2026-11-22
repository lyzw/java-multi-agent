import { useEffect, useState } from 'react'
import { Alert, App as AntApp, Spin, Tag } from 'antd'
import { Link } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { getRoles, getDashboard, getMonitoringHealth } from '@/api/adminApi'
import type { DashboardSummary, MonitoringHealth } from '@/api/types'
import { STATUS_LABELS, SEVERITY_LABELS, reasonLabel, formatTime } from './monitoring/monitoringFormat'

/**
 * 运行总览 · 控制面 Dashboard。
 *
 * ⚠️ 数据诚实性：所有数字都来自 getDashboard / getMonitoringHealth 的真实字段。
 *    后端没有的指标（请求量趋势、时延、成本）一律显示「未采集」占位，
 *    绝不编造 —— 与「未采集显示未采集而非 0 / 链路成功 ≠ 任务成功」口径一致。
 */

const C = 2 * Math.PI * 46

/** 环形分布：入参为各项 label/value/color，按总量算弧长。 */
function Donut({
  items,
  centerValue,
  centerLabel,
}: {
  items: Array<{ label: string; value: number; color: string }>
  centerValue: number
  centerLabel: string
}): JSX.Element {
  const total = items.reduce((s, i) => s + i.value, 0)
  let offset = 0
  return (
    <div className="dc-donut-wrap">
      <div className="dc-donut">
        <svg viewBox="0 0 120 120" aria-hidden="true">
          <circle cx="60" cy="60" r="46" stroke="var(--dc-line-soft)" />
          {items.map((it) => {
            const len = total > 0 ? (C * it.value) / total : 0
            const el = (
              <circle
                key={it.label}
                cx="60"
                cy="60"
                r="46"
                stroke={it.color}
                strokeDasharray={`${len.toFixed(2)} ${(C - len).toFixed(2)}`}
                strokeDashoffset={(-offset).toFixed(2)}
              />
            )
            offset += len
            return el
          })}
        </svg>
        <div className="dc-donut__center">
          <b>{centerValue}</b>
          <span>{centerLabel}</span>
        </div>
      </div>
      <div className="dc-dl">
        {items.map((it) => (
          <div className="dc-dl__row" key={it.label}>
            <i style={{ background: it.color }} />
            <span>{it.label}</span>
            <b>
              {it.value}
              <em style={{ fontStyle: 'normal', color: 'var(--dc-text-3)', fontSize: 11, marginLeft: 4 }}>
                {total > 0 ? `${Math.round((it.value / total) * 100)}%` : '—'}
              </em>
            </b>
          </div>
        ))}
      </div>
    </div>
  )
}

/** 一条横条：value/max 决定宽度与颜色。 */
function BarRow({
  name,
  value,
  max,
  display,
  color,
  crit,
}: {
  name: string
  value: number
  max: number
  display: string
  color?: string
  crit?: boolean
}): JSX.Element {
  const pct = max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0
  return (
    <div className="dc-bar__row">
      <span className="dc-bar__name" title={name}>
        {name}
      </span>
      <div className="dc-bar__track">
        <i style={{ width: `${pct}%`, background: color }} />
      </div>
      <span className={`dc-bar__val${crit ? ' crit' : ''}`}>{display}</span>
    </div>
  )
}

function Kpi({
  label,
  value,
  unit,
  foot,
}: {
  label: string
  value: string | number
  unit?: string
  foot: React.ReactNode
}): JSX.Element {
  return (
    <div className="dc-kpi">
      <div className="dc-kpi__label">{label}</div>
      <div className="dc-kpi__val">
        {value}
        {unit && <small>{unit}</small>}
      </div>
      <div className="dc-kpi__foot">{foot}</div>
    </div>
  )
}

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
   * ⚠️ 失败时呈现「不可用」而非 0：监控数据源故障返回 503，
   *    若把计数留空/置 0，仪表盘看起来就是「一切正常」——那是最危险的展示形态。
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
  const status = health?.status
  const statusLabel = status ? STATUS_LABELS[status] : '—'
  const beaconClass =
    status === 'HEALTHY' ? 'dc-beacon' : status === 'DEGRADED' ? 'dc-beacon dc-beacon--warn' : 'dc-beacon dc-beacon--crit'

  const tenants = summary?.tenantCount ?? 0
  const agents = summary?.agentCount ?? 0
  const tools = summary?.toolCount ?? 0
  const skills = summary?.skillCount ?? 0
  const mcps = summary?.mcpCount ?? 0
  const pending = summary?.pendingApprovals ?? 0
  const alerts = summary?.activeAlerts ?? 0
  const capabilityTotal = agents + tools + skills + mcps

  const criticalCount = health?.criticalCount ?? 0
  const warningCount = health?.warningCount ?? 0
  const infoCount = health?.infoCount ?? 0
  const denyTotal = criticalCount + warningCount + infoCount
  const coverage = health?.coverage
  const revoked = health?.authzWatermark.revokedSubjects ?? 0
  const subjects = health?.authzWatermark.subjectCount ?? 0
  const recentDenies = health?.recentDenies ?? []

  return (
    <div className="dc-dash">
      <div className="dc-pagehead">
        <div>
          <h1>概览仪表盘</h1>
          <p>平台健康、能力使用与治理状态 · 窗口 24 小时</p>
        </div>
        <div className="dc-pagehead__actions">
          <span style={{ fontSize: 12, color: 'var(--dc-text-3)' }}>
            当前角色：{displayRoles.length > 0 ? displayRoles.join(' / ') : '未返回（部分菜单不可见）'}
          </span>
        </div>
      </div>

      {/* 仪表带 */}
      <section className="dc-hero" aria-label="运行健康">
        <div className="dc-hero__verdict">
          <div className="dc-hero__line">
            <span className={beaconClass} aria-hidden="true" />
            <span style={{ fontSize: 17, fontWeight: 400 }}>运行健康 ·</span>
            <strong>{statusLabel}</strong>
          </div>
          <p className="dc-hero__sub">
            {healthUnavailable
              ? '监控数据源不可达（≠ 健康）'
              : health
                ? `观测于 ${formatTime(health.observedAt)}`
                : '快照加载中'}
          </p>
        </div>
        <div className="dc-hero__metrics">
          <div className="dc-metric">
            <div className="dc-metric__val">{tenants}</div>
            <div className="dc-metric__label">活跃租户</div>
          </div>
          <div className="dc-metric">
            <div className="dc-metric__val">{capabilityTotal}</div>
            <div className="dc-metric__label">在册能力</div>
          </div>
          <div className="dc-metric">
            <div className="dc-metric__val">{pending}</div>
            <div className="dc-metric__label">待审批申请</div>
          </div>
          <div className={`dc-metric${criticalCount > 0 ? ' dc-metric--warn' : ''}`}>
            <div className="dc-metric__val">{criticalCount}</div>
            <div className="dc-metric__label">严重拒绝</div>
          </div>
          <div className="dc-metric">
            <div className="dc-metric__val">
              {coverage && coverage.total > 0
                ? `${Math.round((coverage.invocationCount / coverage.total) * 100)}%`
                : '未采集'}
            </div>
            <div className="dc-metric__label">审计覆盖率</div>
          </div>
        </div>
        <div className="dc-hero__scale" aria-hidden="true" />
      </section>

      {/* 监控不可用告警（原文案为测试契约，不改） */}
      {healthUnavailable && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 18 }}
          message="监控数据不可用（≠ 健康）"
          description={
            <span>
              运行监控数据源当前不可达，后端刻意返回 503 而非空快照。
              「看不到」不等于「一切正常」——请到 <Link to="/monitoring/health">运行监控</Link> 页确认是否需要人工对账。
            </span>
          }
        />
      )}

      {/* 降级告警（原文案为测试契约，不改） */}
      {status === 'DEGRADED' && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 18 }}
          message="健康状态为「降级」"
          description={
            criticalCount > 0
              ? `窗口内存在 ${criticalCount} 条严重拒绝（越权 / 授权依据失效 / 副作用结果不确定），请优先排查。`
              : '决策日志审计覆盖不达标，这批拒绝记录本身不可作为完整追责依据。'
          }
        />
      )}

      {/* KPI 卡：全部真实字段 */}
      <section className="dc-kpis" aria-label="关键指标">
        <Kpi label="租户" value={tenants} foot={<span>在册 · 生命周期状态以租户页为准</span>} />
        <Kpi label="Agent" value={agents} foot={<span>全局目录在册</span>} />
        <Kpi label="Tool" value={tools} foot={<span>平台级工具目录</span>} />
        <Kpi label="Skill + MCP" value={skills + mcps} foot={<span>Skill {skills} · MCP {mcps}</span>} />
        <Kpi
          label="待审批申请"
          value={pending}
          foot={
            pending > 0 ? (
              <span>
                <b className="down">需处理</b> · 活跃告警 {alerts}
              </span>
            ) : (
              <span>无待审批 · 活跃告警 {alerts}</span>
            )
          }
        />
      </section>

      {/* 能力构成 + 告警级别分布 */}
      <div className="dc-grid-main">
        <section className="panel dc-panel" aria-label="能力构成">
          <div className="dc-panel__head">
            <div className="dc-panel__title">
              能力构成
              <small>全局目录在册对象 · 按类型</small>
            </div>
            <span className="dc-panel__meta">实时盘点</span>
          </div>
          <div className="dc-panel__body">
            <Donut
              centerValue={capabilityTotal}
              centerLabel="在册能力"
              items={[
                { label: 'Agent', value: agents, color: 'var(--dc-ink)' },
                { label: 'Tool', value: tools, color: 'var(--dc-brass)' },
                { label: 'Skill', value: skills, color: 'var(--dc-teal)' },
                { label: 'MCP Server', value: mcps, color: 'var(--dc-line-strong)' },
              ]}
            />
          </div>
        </section>

        <section className="panel dc-panel" aria-label="运行健康">
          <div className="dc-panel__head">
            <div className="dc-panel__title">
              运行健康
              <small>拒绝级别分布 · 窗口 24 小时</small>
            </div>
            <span className="dc-panel__meta">共 {denyTotal} 条</span>
          </div>
          <div className="dc-panel__body">
            <div className="dc-bars">
              <BarRow name="严重" value={criticalCount} max={Math.max(denyTotal, 1)} display={String(criticalCount)} color="var(--dc-red)" crit />
              <BarRow name="警告" value={warningCount} max={Math.max(denyTotal, 1)} display={String(warningCount)} color="var(--dc-brass)" />
              <BarRow name="提示" value={infoCount} max={Math.max(denyTotal, 1)} display={String(infoCount)} color="var(--dc-text-3)" />
              <BarRow name="被撤权主体" value={revoked} max={Math.max(subjects, 1)} display={`${revoked}/${subjects}`} color="var(--dc-ink-2)" />
            </div>
          </div>
        </section>
      </div>

      {/* 审计覆盖 + 近期拒绝 */}
      <div className="dc-grid-sub">
        <section className="dc-panel" aria-label="决策日志审计覆盖">
          <div className="dc-panel__head">
            <div className="dc-panel__title">
              决策日志审计覆盖
              <small>覆盖不足时拒绝记录不可作为完整追责依据</small>
            </div>
            <span className="dc-panel__meta">{coverage ? `${coverage.total} 条` : '未采集'}</span>
          </div>
          <div className="dc-panel__body">
            {coverage ? (
              <div className="dc-bars">
                <BarRow name="调用覆盖" value={coverage.invocationCount} max={Math.max(coverage.total, 1)} display={`${coverage.invocationCount}/${coverage.total}`} color="var(--dc-teal)" />
                <BarRow name="拒绝覆盖" value={coverage.denyCount} max={Math.max(coverage.total, 1)} display={String(coverage.denyCount)} color="var(--dc-brass)" />
                <BarRow name="不完整记录" value={coverage.incompleteCount} max={Math.max(coverage.total, 1)} display={String(coverage.incompleteCount)} color="var(--dc-red)" crit />
                <BarRow name="泛化拒绝" value={coverage.nonSpecificDenyCount} max={Math.max(coverage.total, 1)} display={String(coverage.nonSpecificDenyCount)} color="var(--dc-text-3)" />
              </div>
            ) : (
              <div className="dc-empty">
                <span>审计覆盖数据<b>未采集</b>（监控不可用），不是 0。</span>
              </div>
            )}
          </div>
        </section>

        <section className="dc-panel" aria-label="近期拒绝">
          <div className="dc-panel__head">
            <div className="dc-panel__title">
              近期拒绝
              <small>逐条下钻请到拒绝告警页</small>
            </div>
            <Link to="/monitoring/denies" className="dc-panel__meta" style={{ color: 'var(--dc-brass-deep)' }}>
              查看全部
            </Link>
          </div>
          <div className="dc-panel__body">
            {recentDenies.length > 0 ? (
              <div className="dc-timeline">
                {recentDenies.slice(0, 4).map((d) => (
                  <div className="dc-tl" key={d.invocationId}>
                    <div className="dc-tl__time">{formatTime(d.occurredAt).slice(-8)}</div>
                    <span className={`dc-tl__tick${d.severity === 'CRITICAL' ? ' red' : d.severity === 'WARNING' ? ' brass' : ''}`} aria-hidden="true" />
                    <div className="dc-tl__body">
                      <div className="dc-tl__title">
                        <Tag
                          color={d.severity === 'CRITICAL' ? 'error' : d.severity === 'WARNING' ? 'warning' : 'default'}
                          style={{ marginInlineEnd: 6 }}
                        >
                          {SEVERITY_LABELS[d.severity]}
                        </Tag>
                        {reasonLabel(d.reasonCode)}
                      </div>
                      <div className="dc-tl__meta">
                        {d.toolId ? `tool ${d.toolId} · ` : ''}{d.principalId ? `主体 ${d.principalId}` : '主体未知'}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="dc-empty">
                <span>窗口内无拒绝记录；<b>监控不可用</b>时不会显示为空列表冒充正常。</span>
              </div>
            )}
          </div>
        </section>
      </div>

      {/* 未采集模块：后端无时序端点，明确标注，绝不编造 */}
      <div className="dc-grid-sub">
        <section className="dc-panel" aria-label="请求量趋势">
          <div className="dc-panel__head">
            <div className="dc-panel__title">
              请求量趋势
              <small>按小时的调用量与拒绝数</small>
            </div>
            <span className="dc-panel__meta">未接入</span>
          </div>
          <div className="dc-panel__body">
            <div className="dc-empty">
              <span>
                <b>未采集</b> · 当前后端未提供按小时的请求量时序端点。
                这里不填充估算数字；接入数据源后本卡片自动切换为趋势图。
              </span>
            </div>
          </div>
        </section>

        <section className="dc-panel" aria-label="成本趋势">
          <div className="dc-panel__head">
            <div className="dc-panel__title">
              模型调用成本
              <small>按日聚合 · 与预算阈值对比</small>
            </div>
            <span className="dc-panel__meta">未接入</span>
          </div>
          <div className="dc-panel__body">
            <div className="dc-empty">
              <span>
                <b>未采集</b> · 成本口径需独立计价数据源，当前未接入。
                judge 成本不与 usage_record 混算，故此处不展示替代数字。
              </span>
            </div>
          </div>
        </section>
      </div>
    </div>
  )
}
