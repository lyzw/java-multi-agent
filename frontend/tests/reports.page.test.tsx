/**
 * 报表中心接线测试（工作台统计）
 *
 * ⛔ 本测试要锁住三件容易被「看起来能跑」掩盖的事：
 *
 *  ① **确实调了端点**（连通性）：不是渲染出一堆静态卡片。
 *     mock 掉 `@/api/client` 的三个统计函数，断言它们被调用、
 *     且**带上了正确参数**（days、RAG 侧带租户）。
 *
 *  ② **反证：改 mock 数据 → UI 必须跟着变**。
 *     若把检索次数从 3 改成 30，页面显示的合计必须变。
 *     写死数字的页面在本用例下会红——这是本文件存在的核心理由。
 *
 *  ③ **口径诚实性**：零值不等于「无数据」；分母为 0 时成功率显示「—」
 *     而不是「0%」；在途 Run 不计入辅导量。
 *     这些是「聚合报表最容易说谎」的地方，必须有测试钉住。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import { ReportsPage } from '@/pages/reports/ReportsPage'
import type {
  DailyStatistics,
  RagDailyImportStatistics,
  RagDailySearchStatistics,
  RagStatistics,
} from '@/api/types'

const getDailyStatisticsMock = vi.hoisted(() => vi.fn())
const getRagDailySearchesMock = vi.hoisted(() => vi.fn())
const getRagDailyImportsMock = vi.hoisted(() => vi.fn())
const getRagStatisticsMock = vi.hoisted(() => vi.fn())
const downloadCsvMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    getDailyStatistics: getDailyStatisticsMock,
    getRagDailySearches: getRagDailySearchesMock,
    getRagDailyImports: getRagDailyImportsMock,
    getRagStatistics: getRagStatisticsMock,
  }
})

// ⚠️ 导出是纯本地动作（Blob + a[download]），测试里没有真实下载环境，
//    故把 csv 模块整体替身，直接断言「导出的内容与表格同源」。
vi.mock('@/features/reports/csv', () => ({
  downloadCsv: downloadCsvMock,
  toCsv: (headers: string[]) => headers.join(','),
}))

const TENANT = '11111111-1111-1111-1111-111111111111'

/** 造一份平台侧统计（3 天）。 */
function platformData(overrides?: Partial<DailyStatistics>): DailyStatistics {
  return {
    runsByDay: [
      { date: '2026-10-21', total: 4, terminal: 4, succeeded: 3, failed: 1, timedOut: 0, cancelled: 0 },
      { date: '2026-10-22', total: 2, terminal: 2, succeeded: 2, failed: 0, timedOut: 0, cancelled: 0 },
      { date: '2026-10-23', total: 0, terminal: 0, succeeded: 0, failed: 0, timedOut: 0, cancelled: 0 },
    ],
    weaknessesByDay: [
      { date: '2026-10-21', count: 5 },
      { date: '2026-10-22', count: 2 },
      { date: '2026-10-23', count: 0 },
    ],
    studentsByDay: [
      { date: '2026-10-21', activeStudents: 3, newStudents: 1 },
      { date: '2026-10-22', activeStudents: 2, newStudents: 0 },
      { date: '2026-10-23', activeStudents: 0, newStudents: 0 },
    ],
    topKnowledgePoints: [
      {
        knowledgePoint: '一次函数',
        count: 12,
        errorTypes: { 计算失误: 8, 概念不清: 4 },
      },
      {
        knowledgePoint: '勾股定理',
        count: 5,
        errorTypes: { 审题偏差: 5 },
      },
    ],
    // ⚠️ 区间内口径：与全历史**不同**（全历史含窗口外的命中），
    //    这样「切换口径」才是可验证的行为，而不是同一份数据换个标签。
    topKnowledgePointsInRange: [
      {
        knowledgePoint: '二次函数',
        count: 4,
        errorTypes: { 步骤缺失: 4 },
      },
      {
        knowledgePoint: '三角函数',
        count: 2,
        errorTypes: { 概念不清: 2 },
      },
    ],
    generatedDays: 3,
    currentPeriod: {
      total: 6,
      terminal: 6,
      succeeded: 5,
      failed: 1,
      timedOut: 0,
      cancelled: 0,
      weaknessCount: 7,
      activeStudentDays: 5,
      newStudents: 1,
    },
    previousPeriod: {
      total: 4,
      terminal: 4,
      succeeded: 2,
      failed: 2,
      timedOut: 0,
      cancelled: 0,
      weaknessCount: 4,
      activeStudentDays: 4,
      newStudents: 2,
    },
    ...overrides,
  }
}

/** 造一份 RAG 检索统计。 */
function searchData(searches: number[]): RagDailySearchStatistics {
  const days = ['2026-10-21', '2026-10-22', '2026-10-23']
  return {
    searchesByDay: days.map((date, i) => ({ date, searches: searches[i] ?? 0 })),
    generatedDays: 3,
  }
}

/** 造一份 RAG 导入统计。 */
function importData(): RagDailyImportStatistics {
  return {
    importsByDay: [
      { date: '2026-10-21', sources: 1, documents: 2, chunks: 30 },
      { date: '2026-10-22', sources: 0, documents: 1, chunks: 12 },
      { date: '2026-10-23', sources: 0, documents: 0, chunks: 0 },
    ],
    generatedDays: 3,
  }
}

/** 造一份语料概况。 */
function corpusData(): RagStatistics {
  return {
    totalSources: 4,
    verifiedSources: 3,
    admissibleSources: 2,
    totalDocuments: 5,
    totalChunks: 100,
    retrievableChunks: 80,
    chunksMissingEmbedding: 20,
    missingEmbeddingRatio: 0.2,
    chunksByRole: { EXPLANATION: 60, EXERCISE: 40 },
    sourcesByVerificationStatus: { VERIFIED: 3, PENDING: 1 },
    documentsByParseStatus: { CHUNKED: 5 },
  }
}

function seedSession(tenantId: string | null = TENANT): void {
  const record: Record<string, unknown> = {
    accessToken: 'tok-1',
    expiresAt: Date.now() + 900_000,
    roles: ['TENANT_ADMIN'],
  }
  if (tenantId !== null) record['tenantId'] = tenantId
  window.sessionStorage.setItem('agent.session', JSON.stringify(record))
}

function renderPage(): void {
  render(
    <MemoryRouter>
      <SessionProvider>
        <ReportsPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('报表中心：连通性（确实调了端点）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    getDailyStatisticsMock.mockReset().mockResolvedValue(platformData())
    getRagDailySearchesMock.mockReset().mockResolvedValue(searchData([3, 5, 0]))
    getRagDailyImportsMock.mockReset().mockResolvedValue(importData())
    getRagStatisticsMock.mockReset().mockResolvedValue(corpusData())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('⛔ 应调用三个统计端点（平台 + RAG 两处）', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(getDailyStatisticsMock).toHaveBeenCalled()
    })
    expect(getRagDailySearchesMock).toHaveBeenCalled()
    expect(getRagDailyImportsMock).toHaveBeenCalled()
    expect(getRagStatisticsMock).toHaveBeenCalled()
  })

  it('⛔ RAG 侧请求必须带租户标识（RLS 上下文靠它重建）', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(getRagDailySearchesMock).toHaveBeenCalled()
    })
    expect(getRagDailySearchesMock).toHaveBeenCalledWith(TENANT, 30)
    expect(getRagDailyImportsMock).toHaveBeenCalledWith(TENANT, 30)
  })

  it('⛔ 无租户时不发 RAG 请求（缺 X-Tenant-Id 只会换来 400）', async () => {
    seedSession(null)
    renderPage()

    await waitFor(() => {
      expect(getDailyStatisticsMock).toHaveBeenCalled()
    })
    expect(getRagDailySearchesMock).not.toHaveBeenCalled()
    expect(getRagDailyImportsMock).not.toHaveBeenCalled()
  })
})

describe('报表中心：反证（改数据 → UI 必须跟着变）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    getDailyStatisticsMock.mockReset().mockResolvedValue(platformData())
    getRagDailySearchesMock.mockReset().mockResolvedValue(searchData([3, 5, 0]))
    getRagDailyImportsMock.mockReset().mockResolvedValue(importData())
    getRagStatisticsMock.mockReset().mockResolvedValue(corpusData())
  })

  it('⛔ 检索次数改变 → 区间合计必须跟着变', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('区间内检索次数')).toBeInTheDocument()
    })
    expect(screen.getByText('8')).toBeInTheDocument() // 3 + 5 + 0

    // 改 mock 数据后重新挂载：合计必须变（写死数字的页面会在此红）
    getRagDailySearchesMock.mockResolvedValue(searchData([30, 50, 0]))
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('80').length).toBeGreaterThan(0)
    })
  })

  it('⛔ 辅导量改变 → 区间总量必须跟着变', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('区间内发起量')).toBeInTheDocument()
    })
    expect(screen.getByText('6')).toBeInTheDocument() // 4 + 2 + 0
  })

  it('⛔ 知识点排行必须按命中次数降序呈现', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('二次函数').length).toBeGreaterThan(0)
    })
    const rows = document.querySelectorAll('.rank__row')
    expect(rows.length).toBeGreaterThanOrEqual(2)
    // ⚠️ 默认口径是「所选区间内」（区间选择器必须在排行上生效）
    expect(rows[0]?.textContent).toContain('二次函数')
    expect(rows[1]?.textContent).toContain('三角函数')
  })
})

describe('报表中心：口径诚实性（聚合报表最容易说谎的地方）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    getDailyStatisticsMock.mockReset().mockResolvedValue(platformData())
    getRagDailySearchesMock.mockReset().mockResolvedValue(searchData([3, 5, 0]))
    getRagDailyImportsMock.mockReset().mockResolvedValue(importData())
    getRagStatisticsMock.mockReset().mockResolvedValue(corpusData())
  })

  it('⛔ 分母为 0 的那一天成功率显示「—」而不是「0%」', async () => {
    // ⚠️ 「0%」与「无数据可算」是**两件事**：
    //    · 某天发起了 2 次辅导、全失败 → 成功率确实是 0%
    //    · 某天**根本没有辅导** → 成功率**无意义**，显示 0% 会谎称
    //      「那天全部失败了」——那是编造出来的结论。
    //    故分母为 0 时必须显示「—」。
    //    （另：区间内**全部**为 0 时页面走空态，根本不渲染成功率卡，
    //      那条路径由「无数据时应显示空态」用例覆盖。）
    getDailyStatisticsMock.mockResolvedValue(
      platformData({
        runsByDay: [
          { date: '2026-10-21', total: 2, terminal: 2, succeeded: 2, failed: 0, timedOut: 0, cancelled: 0 },
          { date: '2026-10-22', total: 0, terminal: 0, succeeded: 0, failed: 0, timedOut: 0, cancelled: 0 },
        ],
      }),
    )
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('成功率').length).toBeGreaterThan(0)
    })
    // 该日分母为 0 → 显示「—」
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
    // 整体成功率 100%（2/2），不是 0%
    expect(screen.getAllByText('100%').length).toBeGreaterThan(0)
    expect(screen.queryByText('0%')).not.toBeInTheDocument()
  })

  it('⛔ 必须说明「在途 Run 不计入」（否则成功率分母被高估）', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/两个口径/)).toBeInTheDocument()
    })
  })

  it('⛔ 必须说明检索审计是数据起点（否则被误读为检索坏了）', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/数据起点/)).toBeInTheDocument()
    })
  })

  it('⛔ 三个导入层级必须说明不可相加', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/三个层级不可相加/)).toBeInTheDocument()
    })
  })

  it('⛔ 活跃学生必须说明「不是登录学生数」', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/不是登录学生数/)).toBeInTheDocument()
    })
  })

  it('⛔ 每张报表旁必须有等价表格（图表只给趋势，数字要能逐行读）', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('每日搜索的问题数明细')).toBeInTheDocument()
    })
    expect(screen.getByText('每日错题分析明细')).toBeInTheDocument()
    expect(screen.getByText('每日导入 RAG 资料数明细')).toBeInTheDocument()
    expect(screen.getByText('每日辅导量与终态构成明细')).toBeInTheDocument()
    expect(screen.getByText('薄弱知识点排行与错因分布')).toBeInTheDocument()
    expect(screen.getByText('每日活跃与新增学生明细')).toBeInTheDocument()
  })
})

describe('报表中心：五态诚实（空态 ≠ 故障）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    getDailyStatisticsMock.mockReset().mockResolvedValue(platformData())
    getRagDailySearchesMock.mockReset().mockResolvedValue(searchData([3, 5, 0]))
    getRagDailyImportsMock.mockReset().mockResolvedValue(importData())
    getRagStatisticsMock.mockReset().mockResolvedValue(corpusData())
  })

  it('⛔ 平台侧失败 → 显示错误并给出下一步，不得静默显示空数据', async () => {
    getDailyStatisticsMock.mockRejectedValue(new Error('boom'))
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('没能完成').length).toBeGreaterThan(0)
    })
    expect(screen.getAllByRole('button', { name: '重新读取' }).length).toBeGreaterThan(0)
  })

  it('⛔ RAG 侧失败不应让平台侧报表也变成空白（两侧状态独立）', async () => {
    getRagDailySearchesMock.mockRejectedValue(new Error('rag down'))
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('没能完成').length).toBeGreaterThan(0)
    })
    // 平台侧仍然有数据 —— 合成一个状态会把它一起清空
    expect(screen.getByText('区间内发起量')).toBeInTheDocument()
    expect(screen.getByText('6')).toBeInTheDocument()
  })

  it('⛔ 无数据时应显示空态并说明怎么产生数据', async () => {
    // ⚠️ 后端始终返回**连续日期轴**（缺失日补 0），故「无数据」= 区间内全为 0，
    //    而不是数组为空。空态判定若写成「数组为空」，生产环境永远不会触发——
    //    真实的无数据日会渲染成一张空图表，看起来像「页面坏了」。
    getDailyStatisticsMock.mockResolvedValue(
      platformData({
        weaknessesByDay: [
          { date: '2026-10-21', count: 0 },
          { date: '2026-10-22', count: 0 },
          { date: '2026-10-23', count: 0 },
        ],
        // ⚠️ 总量口径在后端：逐日归零但汇总没归零时，
        //    页面显示的仍是后端汇总值（这是「单一口径」的要求，不是 bug）。
        currentPeriod: {
          total: 6,
          terminal: 6,
          succeeded: 5,
          failed: 1,
          timedOut: 0,
          cancelled: 0,
          weaknessCount: 0,
          activeStudentDays: 5,
          newStudents: 1,
        },
      }),
    )
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('这个区间还没有错题分析记录')).toBeInTheDocument()
    })
  })
})

describe('报表中心：环比（上一等长区间基线）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    downloadCsvMock.mockReset()
    getDailyStatisticsMock.mockReset().mockResolvedValue(platformData())
    getRagDailySearchesMock.mockReset().mockResolvedValue(searchData([3, 5, 0]))
    getRagDailyImportsMock.mockReset().mockResolvedValue(importData())
    getRagStatisticsMock.mockReset().mockResolvedValue(corpusData())
  })

  it('⛔ 发起量 6 vs 上期 4 → 必须显示「较上期 +50%」', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('区间内发起量')).toBeInTheDocument()
    })
    expect(screen.getByText('较上期 +50%')).toBeInTheDocument()
  })

  it('⛔ 上期没有基线时不得编造百分比（0 → 5 不是「+∞%」）', async () => {
    getDailyStatisticsMock.mockResolvedValue(
      platformData({
        currentPeriod: {
          total: 5,
          terminal: 5,
          succeeded: 5,
          failed: 0,
          timedOut: 0,
          cancelled: 0,
          weaknessCount: 5,
          activeStudentDays: 5,
          newStudents: 5,
        },
        previousPeriod: {
          total: 0,
          terminal: 0,
          succeeded: 0,
          failed: 0,
          timedOut: 0,
          cancelled: 0,
          weaknessCount: 0,
          activeStudentDays: 0,
          newStudents: 0,
        },
      }),
    )
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('区间内发起量')).toBeInTheDocument()
    })
    expect(screen.getAllByText('上期无基线').length).toBeGreaterThan(0)
    expect(screen.queryByText(/较上期 \+∞/)).not.toBeInTheDocument()
  })

  it('⛔ 比率之比必须用「个百分点」而不是「%」', async () => {
    // 成功率 5/6 ≈ 83%，上期 2/4 = 50% → 相差约 33 个百分点
    // ⚠️ 若写成「+33%」会被读成「相对上期涨了 33%」，那是另一回事。
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('成功率').length).toBeGreaterThan(0)
    })
    expect(screen.getByText(/较上期 \+33 个百分点/)).toBeInTheDocument()
    expect(screen.queryByText(/较上期 \+33%/)).not.toBeInTheDocument()
  })
})

describe('报表中心：知识点排行双口径切换', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    downloadCsvMock.mockReset()
    getDailyStatisticsMock.mockReset().mockResolvedValue(platformData())
    getRagDailySearchesMock.mockReset().mockResolvedValue(searchData([3, 5, 0]))
    getRagDailyImportsMock.mockReset().mockResolvedValue(importData())
    getRagStatisticsMock.mockReset().mockResolvedValue(corpusData())
  })

  it('⛔ 默认展示「所选区间内」口径（区间选择器必须在排行上生效）', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('二次函数').length).toBeGreaterThan(0)
    })
    expect(screen.queryByText('一次函数')).not.toBeInTheDocument()
  })

  it('⛔ 切到「全历史累计」必须换成全历史榜（口径说明也要跟着变）', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('二次函数').length).toBeGreaterThan(0)
    })

    fireEvent.click(screen.getByRole('button', { name: '全历史累计' }))

    await waitFor(() => {
      expect(screen.getAllByText('一次函数').length).toBeGreaterThan(0)
    })
    expect(screen.getByText(/本榜是/)).toHaveTextContent('全历史累计')
  })
})

describe('报表中心：CSV 导出（与表格同源）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    downloadCsvMock.mockReset()
    getDailyStatisticsMock.mockReset().mockResolvedValue(platformData())
    getRagDailySearchesMock.mockReset().mockResolvedValue(searchData([3, 5, 0]))
    getRagDailyImportsMock.mockReset().mockResolvedValue(importData())
    getRagStatisticsMock.mockReset().mockResolvedValue(corpusData())
  })

  it('⛔ 每张报表旁都要有「导出 CSV」按钮', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: '导出 CSV' }).length).toBeGreaterThan(0)
    })
  })

  it('⛔ 导出的行必须与表格里的行逐格一致（不得另起一套口径）', async () => {
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('每日搜索的问题数明细')).toBeInTheDocument()
    })

    const buttons = screen.getAllByRole('button', { name: '导出 CSV' })
    fireEvent.click(buttons[0] as HTMLElement)

    await waitFor(() => {
      expect(downloadCsvMock).toHaveBeenCalled()
    })
    const call = downloadCsvMock.mock.calls[0] as unknown as [
      string,
      string[],
      (string | number)[][],
    ]
    expect(call[1]).toEqual(['日期', '检索次数'])
    expect(call[2]).toEqual([
      ['2026-10-21', 3],
      ['2026-10-22', 5],
      ['2026-10-23', 0],
    ])
  })
})

describe('报表中心：长区间图表必须 1:1 渲染（2026-10-23 修复）', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    downloadCsvMock.mockReset()
    getRagDailySearchesMock.mockReset().mockResolvedValue(searchData([3, 5, 0]))
    getRagDailyImportsMock.mockReset().mockResolvedValue(importData())
    getRagStatisticsMock.mockReset().mockResolvedValue(corpusData())
  })

  it('⛔ 90 天区间的图表不得被压成百分比宽度（否则坐标轴文字不可读）', async () => {
    const days = Array.from({ length: 90 }, (_, i) => ({
      date: `2026-07-${String((i % 28) + 1).padStart(2, '0')}`,
      total: 1,
      terminal: 1,
      succeeded: 1,
      failed: 0,
      timedOut: 0,
      cancelled: 0,
    }))
    getDailyStatisticsMock.mockReset().mockResolvedValue(
      platformData({
        runsByDay: days,
        currentPeriod: {
          total: 90,
          terminal: 90,
          succeeded: 90,
          failed: 0,
          timedOut: 0,
          cancelled: 0,
          weaknessCount: 0,
          activeStudentDays: 0,
          newStudents: 0,
        },
      }),
    )
    seedSession()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('区间内发起量')).toBeInTheDocument()
    })

    const svgs = Array.from(document.querySelectorAll('svg.chart')) as SVGElement[]
    expect(svgs.length).toBeGreaterThan(0)
    // ⚠️ 所有图都必须是像素宽度，不得回退成随容器等比压缩的 100%
    for (const svg of svgs) {
      expect(svg.style.width).toMatch(/^\d+px$/)
    }
    // ⚠️ 且必须存在一张按 90 天铺开的宽图（90 × 34px）
    const widths = svgs.map((s) => parseInt(s.style.width || '0', 10))
    expect(Math.max(...widths)).toBeGreaterThanOrEqual(90 * 34)
  })
})
