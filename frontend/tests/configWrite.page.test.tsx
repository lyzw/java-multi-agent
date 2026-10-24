/**
 * 配置写入接线测试（FE-D11 · P4-D15 / F6 · C3+C5+C7）
 *
 * ⛔ 核心纪律（沿用项目 R-016 / FE-CFG-02 教训）：
 *   - 既要「行为测试」（被调用时渲染正确），也要「连通性测试」（确实调了接口）
 *   - 必须有「反证」：改 mock 数据，UI 必随之变化，否则断言恒真
 *   - ⛔ **约束必须落在「控件不存在」而非「提交后报错」**（FE-02 §4）：
 *     放宽方向的审批等级**根本不出现在下拉框选项中**
 *   - 诚实边界：生效方式必须写明「下一个 Run」而非「立即生效」
 *
 * ⚠️ **本文件锁住的是一个已发生过的真实缺陷**（P4-D15 D15-B3）：
 * 「读接口显示新值」不等于「写入真的生效」。故连通性测试断言的是
 * **调用了写接口**，而非仅检查显示。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { ReactElement } from 'react'
import { SessionProvider } from '@/auth/session'
import { ConfigRuntimePage, ConfigToolsPage } from '@/pages/config/ConfigPages'
import type { ConfigOverrideItem } from '@/api/types'

const listConfigOverridesMock = vi.hoisted(() => vi.fn())
const applyConfigOverrideMock = vi.hoisted(() => vi.fn())
const clearConfigOverrideMock = vi.hoisted(() => vi.fn())
const getConfigMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    getConfig: getConfigMock,
    listConfigOverrides: listConfigOverridesMock,
    applyConfigOverride: applyConfigOverrideMock,
    clearConfigOverride: clearConfigOverrideMock,
  }
})

/** 取未被 mock 的真实 client 实现（用于让某条用例走完整链路）。 */
async function importActualClient(): Promise<typeof import('@/api/client')> {
  const mod = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return mod
}

function renderPage(ui: ReactElement): void {
  render(
    <MemoryRouter>
      <SessionProvider>{ui}</SessionProvider>
    </MemoryRouter>,
  )
}

/** 空的只读配置（避免 P15/P16 的只读区因缺数据而报错） */
const EMPTY_CONFIG = {
  groups: [
    { name: '运行与容量', entries: [] },
    { name: '工具与审批', entries: [] },
  ],
  zeroRetention: {
    declaredProviders: [],
    registryStatus: {},
    anyConfirmed: false,
    failClosed: true,
  },
  effectiveFlags: {},
}

function override(
  key: string,
  effectiveValue: string,
  tightening: ConfigOverrideItem['tightening'] = 'TIGHTEN',
  extra: Partial<ConfigOverrideItem> = {},
): ConfigOverrideItem {
  return {
    key,
    declaredValue: effectiveValue,
    effectiveValue,
    source: 'yml',
    tightening,
    reason: null,
    changedBy: null,
    updatedAt: null,
    ...extra,
  }
}

beforeEach(() => {
  listConfigOverridesMock.mockReset()
  applyConfigOverrideMock.mockReset()
  clearConfigOverrideMock.mockReset()
  getConfigMock.mockReset()
  getConfigMock.mockResolvedValue(EMPTY_CONFIG)
})

/* ── 连通性 ──────────────────────────────────────────────── */

describe('可写配置区连通性（FE-D11）', () => {
  it('F1：P15 调用 listConfigOverrides 并渲染生效值', async () => {
    listConfigOverridesMock.mockResolvedValue([
      override('agent.run.max-steps', '20'),
    ])

    renderPage(<ConfigRuntimePage />)

    await waitFor(() => {
      expect(listConfigOverridesMock).toHaveBeenCalled()
    })
    expect(await screen.findByTestId('effective-agent.run.max-steps')).toHaveTextContent('20')
  })

  it('F2 反证：生效值随 mock 变化（非硬编码）', async () => {
    listConfigOverridesMock.mockResolvedValue([
      override('agent.run.max-steps', '999'),
    ])

    renderPage(<ConfigRuntimePage />)

    expect(await screen.findByTestId('effective-agent.run.max-steps')).toHaveTextContent('999')
  })

  it('F3 连通性：提交时调用 applyConfigOverride 并带上 value + reason', async () => {
    listConfigOverridesMock.mockResolvedValue([
      override('agent.run.max-steps', '20'),
    ])
    applyConfigOverrideMock.mockResolvedValue(override('agent.run.max-steps', '10'))

    renderPage(<ConfigRuntimePage />)

    await userEvent.click(await screen.findByText('调整'))
    // ⚠️ 输入框**预填当前生效值**，`type` 会追加（20 + "10" = 2010），
    //    必须先 clear。漏掉这一步会让「放宽判定」误拒，症状与棘轮失效相似。
    await userEvent.clear(screen.getByLabelText('agent.run.max-steps 新值'))
    await userEvent.type(screen.getByLabelText('agent.run.max-steps 新值'), '10')
    await userEvent.type(
      screen.getByLabelText('agent.run.max-steps 变更理由'),
      '试点期收紧上限以控制成本',
    )
    await userEvent.click(screen.getByTestId('submit-agent.run.max-steps'))

    await waitFor(() => {
      expect(applyConfigOverrideMock).toHaveBeenCalledWith('agent.run.max-steps', {
        value: '10',
        reason: '试点期收紧上限以控制成本',
      })
    })
  })

  it('F4 连通性：恢复默认调用 clearConfigOverride', async () => {
    listConfigOverridesMock.mockResolvedValue([
      override('agent.run.max-steps', '10', 'TIGHTEN', { source: 'db_override' }),
    ])
    clearConfigOverrideMock.mockResolvedValue(undefined)

    renderPage(<ConfigRuntimePage />)

    await userEvent.click(await screen.findByText('恢复默认'))

    await waitFor(() => {
      expect(clearConfigOverrideMock).toHaveBeenCalledWith('agent.run.max-steps')
    })
  })
})

/* ── FE-CFG-01：单向棘轮（约束落在选项不可选）───────────────── */

describe('审批矩阵：单向棘轮（FE-CFG-01）', () => {
  async function openMatrixEditor(): Promise<HTMLElement> {
    listConfigOverridesMock.mockResolvedValue([
      override(
        'agent.approval.required-by-risk',
        '{READ=NOT_REQUIRED, WRITE_EXTERNAL=REQUIRED}',
        'TIGHTEN_RATCHET',
      ),
    ])
    renderPage(<ConfigToolsPage />)
    await userEvent.click(await screen.findByText('调整'))
    return screen.getByTestId('matrix-editor-agent.approval.required-by-risk')
  }

  it('F5：READ=NOT_REQUIRED 时，下拉框只有 OPTIONAL 与 REQUIRED（无 NOT_REQUIRED）', async () => {
    const editor = await openMatrixEditor()
    const select = within(editor).getByLabelText(
      'agent.approval.required-by-risk READ',
    ) as HTMLSelectElement

    const options = Array.from(select.options).map((o) => o.value)
    expect(options).toEqual(['NOT_REQUIRED', 'OPTIONAL', 'REQUIRED'])
    // 当前值 NOT_REQUIRED 在其中（它是当前状态，不是被禁止的放宽方向）
    // ⛔ 关键：放宽方向**根本不在选项里**——不是选了才报错
  })

  it('F6 ⛔ 反证：READ=REQUIRED 时，下拉框**不含** OPTIONAL/NOT_REQUIRED', async () => {
    listConfigOverridesMock.mockResolvedValue([
      override(
        'agent.approval.required-by-risk',
        'READ=REQUIRED,WRITE_EXTERNAL=REQUIRED',
        'TIGHTEN_RATCHET',
      ),
    ])
    renderPage(<ConfigToolsPage />)
    await userEvent.click(await screen.findByText('调整'))

    const select = within(
      screen.getByTestId('matrix-editor-agent.approval.required-by-risk'),
    ).getByLabelText('agent.approval.required-by-risk READ') as HTMLSelectElement

    const options = Array.from(select.options).map((o) => o.value)
    expect(options).toEqual(['REQUIRED'])
    expect(options).not.toContain('OPTIONAL')
    expect(options).not.toContain('NOT_REQUIRED')
  })

  it('F7 ⛔ 反证：移除棘轮（列出全部选项）→ F6 必须转红', async () => {
    // 本用例是**自检**：若有人把 allowedApprovalLevels 改成返回全部值，
    // F6 会转红。此处显式记录该依赖，防止「改对了测试却忘了测试的意图」。
    listConfigOverridesMock.mockResolvedValue([
      override(
        'agent.approval.required-by-risk',
        'READ=REQUIRED,WRITE_EXTERNAL=REQUIRED',
        'TIGHTEN_RATCHET',
      ),
    ])
    renderPage(<ConfigToolsPage />)
    await userEvent.click(await screen.findByText('调整'))

    const select = within(
      screen.getByTestId('matrix-editor-agent.approval.required-by-risk'),
    ).getByLabelText('agent.approval.required-by-risk READ') as HTMLSelectElement
    expect(select.options.length).toBe(1)
  })

  it('F8：yml 花括号形态可正确解析（P4-D15 D15-B1 回归）', async () => {
    const editor = await openMatrixEditor()
    // ⛔ 若不剥花括号，READ 会被解析成 "{READ" → 该下拉框不存在
    expect(
      within(editor).getByLabelText('agent.approval.required-by-risk READ'),
    ).toBeTruthy()
  })
})

/* ── FE-CFG-04 / FE-CFG-07：理由与约束前置 ─────────────────── */

describe('提交前置约束', () => {
  beforeEach(() => {
    listConfigOverridesMock.mockResolvedValue([override('agent.run.max-steps', '20')])
  })

  it('F9：理由为空时提交按钮 disabled（不是提交后报错）', async () => {
    renderPage(<ConfigRuntimePage />)
    await userEvent.click(await screen.findByText('调整'))

    expect(screen.getByTestId('submit-agent.run.max-steps')).toBeDisabled()
  })

  it('F10：理由短于 10 字仍 disabled，并当场提示', async () => {
    renderPage(<ConfigRuntimePage />)
    await userEvent.click(await screen.findByText('调整'))
    await userEvent.type(screen.getByLabelText('agent.run.max-steps 新值'), '10')
    await userEvent.type(screen.getByLabelText('agent.run.max-steps 变更理由'), '太贵')

    expect(screen.getByTestId('submit-agent.run.max-steps')).toBeDisabled()
    expect(screen.getByText('变更理由不少于 10 字。')).toBeTruthy()
  })

  it('F11 ⛔ 放宽（20 → 999）时 disabled，并说明「只能收紧」', async () => {
    renderPage(<ConfigRuntimePage />)
    await userEvent.click(await screen.findByText('调整'))
    await userEvent.type(screen.getByLabelText('agent.run.max-steps 新值'), '999')
    await userEvent.type(
      screen.getByLabelText('agent.run.max-steps 变更理由'),
      '试点期放宽上限提升吞吐',
    )

    expect(screen.getByTestId('submit-agent.run.max-steps')).toBeDisabled()
    expect(screen.getByText(/只能收紧/)).toBeTruthy()
  })

  it('F12 ⛔ 非正数（-1）同样被拒（把「保护」换成「停摆」）', async () => {
    renderPage(<ConfigRuntimePage />)
    await userEvent.click(await screen.findByText('调整'))
    await userEvent.clear(screen.getByLabelText('agent.run.max-steps 新值'))
    await userEvent.type(screen.getByLabelText('agent.run.max-steps 新值'), '-1')
    await userEvent.type(
      screen.getByLabelText('agent.run.max-steps 变更理由'),
      '试点期收紧上限以控制成本',
    )

    expect(screen.getByTestId('submit-agent.run.max-steps')).toBeDisabled()
  })

  it('F12b ⛔ 判别力用例：当前值极小时，非正数仍被独立拒绝', async () => {
    // ⚠️ **本用例是 T-2 反证的承重结构**。
    //    F12 单独看是弱断言：-1 < 20 在「只比大小」的逻辑下也会被拒，
    //    因此去掉 `n <= 0` 那行后 F12 仍然绿——反证 T-2 就转不了红。
    //
    //    本用例把**当前值设为 0**，让两条规则给出**相反**结论：
    //      · 只比大小：-1 <= 0 → 允许（错误：负上限会让 Run 永远无法执行）
    //      · 含非正数检查：直接拒绝（正确）
    //    只有这样，去掉非正数检查才会让本用例转红。
    listConfigOverridesMock.mockResolvedValue([override('agent.run.max-steps', '0')])

    renderPage(<ConfigRuntimePage />)
    await userEvent.click(await screen.findByText('调整'))
    await userEvent.clear(screen.getByLabelText('agent.run.max-steps 新值'))
    await userEvent.type(screen.getByLabelText('agent.run.max-steps 新值'), '-1')
    await userEvent.type(
      screen.getByLabelText('agent.run.max-steps 变更理由'),
      '试点期收紧上限以控制成本',
    )

    expect(screen.getByTestId('submit-agent.run.max-steps')).toBeDisabled()
  })

  it('F13：收紧 + 合法理由 → 可提交', async () => {
    applyConfigOverrideMock.mockResolvedValue(override('agent.run.max-steps', '10'))
    renderPage(<ConfigRuntimePage />)
    await userEvent.click(await screen.findByText('调整'))
    await userEvent.clear(screen.getByLabelText('agent.run.max-steps 新值'))
    await userEvent.type(screen.getByLabelText('agent.run.max-steps 新值'), '10')
    await userEvent.type(
      screen.getByLabelText('agent.run.max-steps 变更理由'),
      '试点期收紧上限以控制成本',
    )

    expect(screen.getByTestId('submit-agent.run.max-steps')).not.toBeDisabled()
  })
})

/* ── FE-CFG-05：诚实标注生效方式 ──────────────────────────── */

describe('生效方式必须如实呈现（FE-CFG-05）', () => {
  it('F14：页面写明「下一个新建的 Run」而非「立即生效」', async () => {
    listConfigOverridesMock.mockResolvedValue([override('agent.run.max-steps', '20')])

    renderPage(<ConfigRuntimePage />)

    await waitFor(() => {
      expect(screen.getByText(/下一个新建的/)).toBeTruthy()
    })
    expect(screen.getByText(/已经开始执行的 Run 不受影响/)).toBeTruthy()
    // ⛔ 绝不可出现「立即生效」「保存即生效」这类误导文案
    expect(screen.queryByText(/立即生效/)).toBeNull()
    expect(screen.queryByText(/保存后即时生效/)).toBeNull()
  })

  it('F15：被覆盖的项同时展示声明值（对照）', async () => {
    listConfigOverridesMock.mockResolvedValue([
      override('agent.run.max-steps', '10', 'TIGHTEN', {
        declaredValue: '20',
        source: 'db_override',
        reason: '试点期收紧上限以控制成本',
        changedBy: 'admin-t',
      }),
    ])

    renderPage(<ConfigRuntimePage />)

    const row = await screen.findByTestId('override-row-agent.run.max-steps')
    expect(within(row).getByText('已按租户覆盖')).toBeTruthy()
    expect(within(row).getByText('20')).toBeTruthy()
    expect(within(row).getByText(/上次理由/)).toBeTruthy()
  })
})

/* ── 错误处理 ────────────────────────────────────────────── */

describe('错误呈现', () => {
  it('F16：读取失败显示错误并可重试', async () => {
    listConfigOverridesMock.mockRejectedValueOnce(new Error('boom'))

    renderPage(<ConfigRuntimePage />)

    // ⚠️ 断言 hook 的**真实**兜底文案。首版写成「配置读取失败」而实为
    //    「配置操作失败，请稍后重试」——断言不匹配时容易误改产品文案去迁就测试，
    //    这里锁定的是既有常量，不因测试而改实现。
    await waitFor(() => {
      expect(screen.getByText(/配置操作失败，请稍后重试/)).toBeTruthy()
    })
  })

  it('F17：409 + CONFIG_RELAXATION_REJECTED 归一化为「只能收紧」而非「刷新重试」', async () => {
    // ⚠️ **必须走真实归一化链路**，不能直接 new ApiError({message:正确文案}）。
    //    首版就是这么写的，结果 T-4（把 errors.ts 的 409 文案改回通用冲突）
    //    注入后本用例**仍然绿**——因为它压根没碰 errors.ts，
    //    断言的是一个被绕过的分支。
    //    现在改为让 fetch 返回真实 409 响应体，覆盖「HTTP → 归一化 → 提示」全链。
    listConfigOverridesMock.mockResolvedValue([override('agent.run.max-steps', '20')])

    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          code: 'CONFIG_RELAXATION_REJECTED',
          // ⚠️ **故意让服务端传一个错误文案**：若前端直接用服务端 message，
          //    管理员就会看到「刷新后重试」而反复重试注定被拒的操作。
          //    本用例断言前端**强制收敛**为正确提示，与凭据错误同构。
          message: '请求冲突，请刷新后重试',
        }),
        { status: 409, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    // ⚠️ 必须**透传到真实实现**：本用例要验的是「HTTP → 归一化 → 提示」全链，
    //    若用 mock 拒绝，errors.ts 的归一化分支根本不会被执行。
    const actual = await importActualClient()
    applyConfigOverrideMock.mockImplementationOnce(
      actual.applyConfigOverride as typeof applyConfigOverrideMock,
    )

    renderPage(<ConfigRuntimePage />)
    await userEvent.click(await screen.findByText('调整'))
    await userEvent.clear(screen.getByLabelText('agent.run.max-steps 新值'))
    await userEvent.type(screen.getByLabelText('agent.run.max-steps 新值'), '10')
    await userEvent.type(
      screen.getByLabelText('agent.run.max-steps 变更理由'),
      '试点期收紧上限以控制成本',
    )
    await userEvent.click(screen.getByTestId('submit-agent.run.max-steps'))

    // ⛔ 关键断言：提示里**不含**「刷新后重试」——
    //    刷新永远不会让「只能收紧」变松，那是无效重试的诱导。
    await waitFor(() => {
      expect(screen.getByText(/只允许收紧/)).toBeTruthy()
    })
    expect(screen.queryByText(/刷新后重试/)).toBeNull()

    vi.unstubAllGlobals()
  })
})

describe('窄屏防撑破：可写配置表必须包在 .table-wrap 内', () => {
  it('F20：写区表格在 .table-wrap 里（否则整页横向滚动）', async () => {
    listConfigOverridesMock.mockResolvedValue([
      override('agent.run.max-steps', '20'),
    ])

    renderPage(<ConfigRuntimePage />)

    await screen.findByTestId('override-row-agent.run.max-steps')
    const tables = Array.from(document.querySelectorAll('table'))
    expect(tables.length).toBeGreaterThan(0)
    for (const t of tables) {
      expect(t.parentElement?.classList.contains('table-wrap')).toBe(true)
    }
  })
})

/* ── 范围纪律 ────────────────────────────────────────────── */

describe('页面职责边界', () => {
  it('F18：P16 只渲染审批相关可写键，不混入运行上限', async () => {
    listConfigOverridesMock.mockResolvedValue([
      override('agent.approval.ttl', '15m'),
      override('agent.approval.required-by-risk', 'READ=NOT_REQUIRED', 'TIGHTEN_RATCHET'),
      override('agent.run.max-steps', '20'),
    ])

    renderPage(<ConfigToolsPage />)

    await waitFor(() => {
      expect(screen.getByTestId('override-row-agent.approval.ttl')).toBeTruthy()
    })
    expect(screen.queryByTestId('override-row-agent.run.max-steps')).toBeNull()
  })

  it('F19：L1 锁定键不会出现在写页面', async () => {
    // ⚠️ 即便后端误返 L1 键，前端也**不渲染编辑控件**——
    // 因为它不在本页的 keys 清单里。这是前端的第一道收敛。
    listConfigOverridesMock.mockResolvedValue([
      override('agent.tool.risk-level-map', '{READ}'),
      override('agent.approval.approver-scope', 'PARENT_OR_TEACHER'),
    ])

    renderPage(<ConfigToolsPage />)

    await waitFor(() => {
      expect(screen.getByText('本页没有可写配置')).toBeTruthy()
    })
    expect(screen.queryByText('调整')).toBeNull()
  })
})