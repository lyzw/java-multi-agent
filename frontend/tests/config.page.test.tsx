/**
 * 配置页接线测试（FE-D7）
 *
 * ⛔ 核心纪律（沿用项目 R-016 教训）：
 *   - 既要「行为测试」（被调用时渲染正确），也要「连通性测试」（确实调了 getConfig）
 *   - 必须有「反证」：数据来自 mock，改 mock 文案 UI 必随之变化，否则断言恒真
 *   - 诚实边界：effective=false 绝不得渲染成「已生效」；零保留未确认不得显示「已确认放行」
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { ReactElement } from 'react'
import { SessionProvider } from '@/auth/session'
import {
  ConfigOverviewPage,
  ConfigRuntimePage,
  ConfigToolsPage,
  ConfigCompliancePage,
  AgentsPage,
} from '@/pages/config/ConfigPages'
import type { ConfigResponse } from '@/api/types'

/** 用 vi.hoisted 保底 mock 函数可被 vi.mock 工厂引用 */
const getConfigMock = vi.hoisted(() => vi.fn())
const listAgentsMock = vi.hoisted(() => vi.fn())
const createAgentMock = vi.hoisted(() => vi.fn())
const publishAgentVersionMock = vi.hoisted(() => vi.fn())
const queryAuditMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    getConfig: getConfigMock,
    listAgents: listAgentsMock,
    createAgent: createAgentMock,
    publishAgentVersion: publishAgentVersionMock,
    queryAudit: queryAuditMock,
  }
})

function renderPage(ui: ReactElement): void {
  render(
    <MemoryRouter>
      <SessionProvider>{ui}</SessionProvider>
    </MemoryRouter>,
  )
}

/**
 * ⚠️ 每个用例前重置 mock：清空调用计数与一次性队列。
 * 否则跨用例累积会导致「toHaveBeenCalledTimes(1)」/「not.toHaveBeenCalled」
 * 误判，以及前序遗留的默认值抢占本用例的 mock 值。
 */
beforeEach(() => {
  getConfigMock.mockReset()
  listAgentsMock.mockReset()
  createAgentMock.mockReset()
  publishAgentVersionMock.mockReset()
  queryAuditMock.mockReset()
})

/** 标准样本：覆盖 effective=true/false、零保留 PENDING/fail-closed */
function sampleConfig(overrides?: Partial<ConfigResponse>): ConfigResponse {
  const base: ConfigResponse = {
    groups: [
      {
        name: '运行与容量',
        entries: [
          {
            key: 'agent.backpressure.enabled',
            declaredValue: 'true',
            effective: true,
            source: 'yml',
            note: 'P4-D05 已实现并接入运行时',
          },
          {
            key: 'agent.run.max-cost-per-run',
            declaredValue: '2.00',
            effective: false,
            source: 'yml',
            note: 'FE-02 §2：生产无读取点，改后不影响行为',
          },
        ],
      },
      {
        name: '工具与审批',
        entries: [
          {
            key: 'agent.tool.risk-level-map',
            declaredValue: '{get_student_profile:READ}',
            effective: false,
            source: 'yml(校验镜像)',
            note: '权威来自代码 ToolHandler.riskLevel()',
          },
        ],
      },
      {
        name: '合规与零保留',
        entries: [
          {
            key: 'agent.compliance.zero-retention.confirmed-providers',
            declaredValue: 'deepseek,minimax,workbuddy',
            effective: false,
            source: 'yml',
            note: '仅声明未确认证据 → 仍 fail-closed',
          },
        ],
      },
    ],
    zeroRetention: {
      declaredProviders: ['deepseek', 'minimax', 'workbuddy'],
      registryStatus: {
        deepseek: 'PENDING',
        minimax: 'PENDING',
        workbuddy: 'PENDING',
      },
      anyConfirmed: false,
      failClosed: true,
    },
    effectiveFlags: {},
  }
  return { ...base, ...overrides }
}

describe('P14 配置总览', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('T1：连通性 + 行为——调用 getConfig 并渲染声明值', async () => {
    getConfigMock.mockResolvedValueOnce(sampleConfig())
    renderPage(<ConfigOverviewPage />)

    // ⛔ 先确认请求确实发出，再断言 UI —— 否则可能在请求前就通过
    await waitFor(() => expect(getConfigMock).toHaveBeenCalledTimes(1))
    await waitFor(() =>
      expect(screen.getByText('运行与容量')).toBeInTheDocument(),
    )

    // effective=true → 已接入
    expect(screen.getByText('已接入')).toBeInTheDocument()
    // effective=false → 仅声明未接入（多条，取全部）
    expect(screen.getAllByText('仅声明未接入').length).toBeGreaterThan(0)
    // 声明值可见
    expect(screen.getByText('agent.backpressure.enabled')).toBeInTheDocument()
  })

  it('T2 反证：UI 内容随 mock 数据变化（非硬编码）', async () => {
    const cfg = sampleConfig()
    // ⚠️ 把声明值改成哨兵串：若页面硬编码，这条断言不会随数据变化而失败
    cfg.groups[0]!.entries[0]!.declaredValue = 'SENTINEL-XYZ-123'
    getConfigMock.mockResolvedValueOnce(cfg)
    renderPage(<ConfigOverviewPage />)

    await waitFor(() =>
      expect(screen.getByText('SENTINEL-XYZ-123')).toBeInTheDocument(),
    )
  })

  it('T3 诚实边界：不得出现「已生效」误标', async () => {
    getConfigMock.mockResolvedValueOnce(sampleConfig())
    renderPage(<ConfigOverviewPage />)

    await waitFor(() =>
      expect(screen.getByText('运行与容量')).toBeInTheDocument(),
    )
    // 全页任何位置都不允许把「仅声明」渲染成「已生效」
    expect(screen.queryByText('已生效')).toBeNull()
  })
})

describe('错误与重试（连通性）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('T4：getConfig 失败后显示错误并提供重试', async () => {
    getConfigMock.mockRejectedValueOnce(new Error('boom'))
    // 重试时给一份成功数据，避免控制台噪声
    getConfigMock.mockResolvedValueOnce(sampleConfig())

    renderPage(<ConfigOverviewPage />)

    await waitFor(() =>
      expect(
        screen.getByText('配置读取失败，请稍后重试'),
      ).toBeInTheDocument(),
    )
    expect(getConfigMock).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(getConfigMock).toHaveBeenCalledTimes(2))
  })
})

describe('P17 合规与零保留', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('T5：渲染零保留运行态，anyConfirmed=false 不显示已确认放行', async () => {
    getConfigMock.mockResolvedValueOnce(sampleConfig())
    renderPage(<ConfigCompliancePage />)

    await waitFor(() =>
      expect(screen.getByText('deepseek')).toBeInTheDocument(),
    )
    // PENDING → 待确认（多家，取全部）
    expect(screen.getAllByText('待确认').length).toBeGreaterThan(0)
    // fail-closed 生效中
    expect(screen.getByText(/零保留闸门生效中/)).toBeInTheDocument()
    // ⛔ 关键诚实边界：未确认 → 不得出现「已确认放行」
    expect(screen.queryByText(/已确认放行/)).toBeNull()
    // 始终渲染的「分别」说明（保 pages.skeleton.test.tsx P17 契约）
    expect(screen.getByText(/分别/)).toBeInTheDocument()
    // ⛔ 合规页不得提供编辑控件
    expect(
      screen.queryByRole('button', { name: /修改|登记|保存|删除/ }),
    ).toBeNull()
  })

  it('T6 反证：failClosed=false/anyConfirmed=true 时文案随数据变化', async () => {
    getConfigMock.mockResolvedValueOnce(
      sampleConfig({
        zeroRetention: {
          declaredProviders: ['acme'],
          registryStatus: { acme: 'CONFIRMED' },
          anyConfirmed: true,
          failClosed: false,
        },
      }),
    )
    renderPage(<ConfigCompliancePage />)

    await waitFor(() => expect(screen.getByText('acme')).toBeInTheDocument())
    expect(screen.getByText('已确认')).toBeInTheDocument()
    // fail-closed 文案消失，出现未生效警告
    expect(screen.queryByText(/零保留闸门生效中/)).toBeNull()
    expect(screen.getByText(/零保留闸门未生效/)).toBeInTheDocument()
  })

  it('T6b ⛔ 闸门未生效必须渲染成「失败」态（安全异常不能长得像普通说明）', async () => {
    // ⚠️ failClosed=false = 已有供应商被允许接入真实数据 = 危险状态。
    //    若沿用中性基线 .status，这条安全异常与一条普通说明视觉完全相同，
    //    管理员扫一眼根本发现不了。故必须带 status--failure。
    getConfigMock.mockResolvedValueOnce(
      sampleConfig({
        zeroRetention: {
          declaredProviders: ['acme'],
          registryStatus: { acme: 'CONFIRMED' },
          anyConfirmed: true,
          failClosed: false,
        },
      }),
    )
    renderPage(<ConfigCompliancePage />)

    await waitFor(() => expect(screen.getByText(/零保留闸门未生效/)).toBeInTheDocument())
    const box = screen.getByText(/零保留闸门未生效/).closest('.status')
    expect(box?.className).toContain('status--failure')
  })

  it('T6c ⛔ 闸门生效时是「成功」态（两种状态视觉上必须可区分）', async () => {
    getConfigMock.mockResolvedValueOnce(sampleConfig())
    renderPage(<ConfigCompliancePage />)

    await waitFor(() => expect(screen.getByText(/零保留闸门生效中/)).toBeInTheDocument())
    const box = screen.getByText(/零保留闸门生效中/).closest('.status')
    expect(box?.className).toContain('status--success')
    expect(box?.className).not.toContain('status--failure')
  })
})

describe('窄屏防撑破：表格必须包在 .table-wrap 内', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('T13：配置总览的每张表都在 .table-wrap 里（否则整页横向滚动）', async () => {
    getConfigMock.mockResolvedValueOnce(sampleConfig())
    renderPage(<ConfigOverviewPage />)

    await waitFor(() => expect(screen.getByText('运行与容量')).toBeInTheDocument())
    const tables = Array.from(document.querySelectorAll('table'))
    expect(tables.length).toBeGreaterThan(0)
    for (const t of tables) {
      expect(t.parentElement?.classList.contains('table-wrap')).toBe(true)
    }
  })

  it('T13b：Agent 列表的表也在 .table-wrap 里', async () => {
    listAgentsMock.mockResolvedValueOnce({
      items: [
        {
          agentId: 'a-1',
          name: '数学归因助手',
          description: null,
          versionCount: 1,
          latestVersionNumber: 1,
          latestDefinition: null,
          latestPublishedAt: null,
        },
      ],
      nextCursor: null,
    })
    renderPage(<AgentsPage />)

    await waitFor(() => expect(screen.getByText('数学归因助手')).toBeInTheDocument())
    const tables = Array.from(document.querySelectorAll('table'))
    expect(tables.length).toBeGreaterThan(0)
    for (const t of tables) {
      expect(t.parentElement?.classList.contains('table-wrap')).toBe(true)
    }
  })
})

describe('P16 子分区 tab：标准 ARIA tabs 模式', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('T12：roving tabindex + aria-controls/aria-labelledby + 方向键切换', async () => {
    const user = userEvent.setup()
    getConfigMock.mockResolvedValueOnce(sampleConfig())
    renderPage(<ConfigToolsPage />)

    const policies = await screen.findByRole('tab', { name: '工具与审批口径' })
    const tools = screen.getByRole('tab', { name: 'Tool 目录' })
    expect(policies).toHaveAttribute('aria-selected', 'true')

    // ⛔ roving tabindex：整组 tab 只占一个 Tab 停靠点
    expect(policies).toHaveAttribute('tabindex', '0')
    expect(tools).toHaveAttribute('tabindex', '-1')

    // ⛔ tab 与内容面板必须互相指认（读屏才知道选中的是哪一块）
    const panel = screen.getByRole('tabpanel')
    expect(policies.getAttribute('aria-controls')).toBe(panel.id)
    expect(panel.getAttribute('aria-labelledby')).toBe(policies.id)

    // ⛔ 方向键必须能切换（键盘用户不必按 N 次 Tab）
    policies.focus()
    await user.keyboard('{ArrowRight}')
    await waitFor(() => expect(tools).toHaveAttribute('aria-selected', 'true'))
    expect(policies).toHaveAttribute('tabindex', '-1')
    expect(tools).toHaveAttribute('tabindex', '0')
  })
})

describe('P15 运行与容量', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('T7：仅渲染「运行与容量」分组', async () => {
    getConfigMock.mockResolvedValueOnce(sampleConfig())
    renderPage(<ConfigRuntimePage />)

    await waitFor(() =>
      expect(
        screen.getByText('agent.backpressure.enabled'),
      ).toBeInTheDocument(),
    )
    // 该分组声明值可见
    expect(screen.getByText('true')).toBeInTheDocument()
    // 其它分组（工具与审批）的键不应出现
    expect(screen.queryByText('agent.tool.risk-level-map')).toBeNull()
  })
})

describe('P16 工具与审批', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('T8：渲染「工具与审批」分组且 effective 标对', async () => {
    getConfigMock.mockResolvedValueOnce(sampleConfig())
    renderPage(<ConfigToolsPage />)

    await waitFor(() =>
      expect(
        screen.getByText('agent.tool.risk-level-map'),
      ).toBeInTheDocument(),
    )
    // 工具分级条目 effective=false → 仅声明未接入
    expect(screen.getByText('仅声明未接入')).toBeInTheDocument()
    // 运行与容量分组的键不应出现
    expect(screen.queryByText('agent.backpressure.enabled')).toBeNull()
  })
})

describe('P18 Agent 配置与发布（G2 真实接线）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('T9 连通性 + 行为：调用 listAgents 并渲染 Agent 列表', async () => {
    listAgentsMock.mockResolvedValueOnce({
      items: [
        {
          agentId: 'a-sentinel-1',
          name: 'SENTINEL-AGENT-9F3',
          description: 'sentinel desc',
          versionCount: 2,
          latestVersionNumber: 2,
          latestDefinition: null,
          latestPublishedAt: null,
        },
      ],
      nextCursor: null,
    })
    renderPage(<AgentsPage />)

    // ⛔ 先确认请求确实发出，且走的是 listAgents（而非 getConfig）
    await waitFor(() => expect(listAgentsMock).toHaveBeenCalledTimes(1))
    await waitFor(() =>
      expect(screen.getByText('SENTINEL-AGENT-9F3')).toBeInTheDocument(),
    )
    // 连通性反证：P18 是管理页，绝不应调用 getConfig
    expect(getConfigMock).not.toHaveBeenCalled()
    // 新建入口存在
    expect(
      screen.getByRole('button', { name: '新建 Agent' }),
    ).toBeInTheDocument()
    // 已发布版本渲染为 v2
    expect(screen.getByText('v2')).toBeInTheDocument()
  })

  it('T10 反证：列表随 mock 数据变化（非硬编码）', async () => {
    listAgentsMock.mockResolvedValueOnce({ items: [], nextCursor: null })
    renderPage(<AgentsPage />)

    await waitFor(() => expect(listAgentsMock).toHaveBeenCalledTimes(1))
    // 空列表 → 诚实空态（不是写死的占位）
    expect(screen.getByText('还没有 Agent')).toBeInTheDocument()
  })

  it('T11 行为：展开「新建 Agent」后出现输入控件（编辑控件只在管理页出现，P17 仍无）', async () => {
    listAgentsMock.mockResolvedValueOnce({ items: [], nextCursor: null })
    renderPage(<AgentsPage />)
    await waitFor(() => expect(listAgentsMock).toHaveBeenCalledTimes(1))

    await userEvent.click(screen.getByRole('button', { name: '新建 Agent' }))
    // 表单展开：名称输入框出现且初始为空
    const nameInput = screen.getByLabelText('名称') as HTMLInputElement
    expect(nameInput).toBeInTheDocument()
    expect(nameInput.value).toBe('')
    // 输入名称后「创建」按钮可用（disabled 绑定 name.trim() 非空）
    await userEvent.type(nameInput, 'abc')
    expect(
      screen.getByRole('button', { name: '创建' }),
    ).not.toBeDisabled()
  })
})

/*
 * ⚠️ P19「配置变更审计」独立页面已于 F4（2026-10-23）合并进 P10 审计查询：
 *    /config/audit 路由与冗余导航项删除，配置查看留痕改由审计页
 *    「资源类型 → 配置查看」过滤视图承担（不再单设一页）。
 *    原行为覆盖（resourceType=config 查询 + CONFIG_VIEW 标签 + 诚实空态）
 *    已迁移至 tests/governance.page.test.tsx 的 P10 用例集。
 */
