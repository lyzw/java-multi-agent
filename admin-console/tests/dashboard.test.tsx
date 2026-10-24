// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'
import type { MonitoringHealth } from '@/api/types'
import { DashboardPage } from '@/pages/DashboardPage'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  getRoles: vi.fn(),
  getDashboard: vi.fn(),
  getMonitoringHealth: vi.fn(),
}))
import { getRoles, getDashboard, getMonitoringHealth } from '@/api/adminApi'

// ---- mock 会话（避免 SessionProvider 依赖 tokenStorage） ----
vi.mock('@/auth/session', () => ({
  useSession: () => ({ roles: [] as string[] }),
  SessionProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

// antd v5 渲染期读 matchMedia；jsdom 默认没有，补一个最小桩
beforeAll(() => {
  if (!window.matchMedia) {
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })) as unknown as typeof window.matchMedia
  }
})

// ---- 夹具 ----
const summaryFixture = {
  tenantCount: 1,
  agentCount: 2,
  toolCount: 3,
  skillCount: 4,
  mcpCount: 5,
  pendingApprovals: 0,
  activeAlerts: 0,
}

const healthHealthy: MonitoringHealth = {
  status: 'HEALTHY',
  authzWatermark: {
    tenantId: 't-1',
    subjectCount: 5,
    revokedSubjects: 0,
    maxAuthzVersion: 3,
    lastBumpAt: null,
    observedAt: '2026-10-24T12:00:00Z',
  },
  recentDenies: [],
  criticalCount: 0,
  warningCount: 0,
  infoCount: 0,
  coverage: {
    total: 10,
    invocationCount: 10,
    denyCount: 0,
    incompleteCount: 0,
    nonSpecificDenyCount: 0,
  },
  observedAt: '2026-10-24T12:00:00Z',
}

const healthDegraded: MonitoringHealth = {
  ...healthHealthy,
  status: 'DEGRADED',
  criticalCount: 2,
  warningCount: 1,
  infoCount: 3,
  coverage: {
    ...healthHealthy.coverage,
    total: 12,
    invocationCount: 12,
    denyCount: 6,
    incompleteCount: 1,
  },
  authzWatermark: { ...healthHealthy.authzWatermark, revokedSubjects: 1 },
}

function renderDashboard(): ReturnType<typeof render> {
  return render(
    <AntApp>
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    </AntApp>,
  )
}

describe('DashboardPage · 运行健康卡片（P4 回填）', () => {
  it('标题与运行健康卡片骨架渲染', async () => {
    vi.mocked(getRoles).mockResolvedValue({ roles: ['TENANT_ADMIN'] })
    vi.mocked(getDashboard).mockResolvedValue(summaryFixture)
    vi.mocked(getMonitoringHealth).mockResolvedValue(healthHealthy)

    renderDashboard()
    expect(await screen.findByText('概览仪表盘')).toBeTruthy()
    expect(await screen.findByText('运行健康')).toBeTruthy()
  })

  it('健康态：展示「正常」状态与被撤权主体口径', async () => {
    vi.mocked(getRoles).mockResolvedValue({ roles: [] })
    vi.mocked(getDashboard).mockResolvedValue(summaryFixture)
    vi.mocked(getMonitoringHealth).mockResolvedValue(healthHealthy)

    renderDashboard()
    // 状态标签
    expect(await screen.findByText('正常')).toBeTruthy()
    // 授权版本水位口径标题渲染（绑定 health.authzWatermark.revokedSubjects）
    expect(screen.getByText('被撤权主体')).toBeTruthy()
    // 不可用告警不应出现（健康态）
    expect(screen.queryByText(/监控数据不可用/)).toBeNull()
  })

  it('降级态：展示「降级」警告与严重拒绝数（数据绑定到 criticalCount）', async () => {
    vi.mocked(getRoles).mockResolvedValue({ roles: [] })
    vi.mocked(getDashboard).mockResolvedValue(summaryFixture)
    vi.mocked(getMonitoringHealth).mockResolvedValue(healthDegraded)

    renderDashboard()
    expect(await screen.findByText('降级')).toBeTruthy()
    expect(screen.getByText('健康状态为「降级」')).toBeTruthy()
    // 描述文案直接绑定 criticalCount=2
    expect(screen.getByText(/窗口内存在 2 条严重拒绝/)).toBeTruthy()
  })

  it('数据源不可用（getMonitoringHealth 抛错）：红色告警「不可用≠健康」', async () => {
    vi.mocked(getRoles).mockResolvedValue({ roles: [] })
    vi.mocked(getDashboard).mockResolvedValue(summaryFixture)
    vi.mocked(getMonitoringHealth).mockRejectedValue(
      new Error('503 监控数据源不可用'),
    )

    renderDashboard()
    expect(await screen.findByText(/监控数据不可用（≠ 健康）/)).toBeTruthy()
    // 不可用时不渲染「正常 / 降级」状态结论
    expect(screen.queryByText('正常')).toBeNull()
    expect(screen.queryByText('降级')).toBeNull()
  })
})
