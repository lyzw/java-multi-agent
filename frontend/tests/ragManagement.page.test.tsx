/**
 * RAG 语料数据管理页接线测试（RAG-D10）
 *
 * ⛔ 沿用 FE-RAG 范式：
 *   - 行为测试 + 连通性测试（确实调了管理接口）
 *   - 反证：改 mock 数据 → UI 必须跟着变
 *   - 五态诚实：空态 ≠ 故障；409（非法状态转移）≠ 服务故障
 *   - 租户上下文：管理按租户隔离（RLS），必须显式携带
 *
 * ⚠️ 本测试最核心的断言是「**只渲染合法的状态流转按钮**」：
 *    撤回是终态（RAG-D07 §2.4 T-10）→ 不提供任何后继按钮；
 *    已拒绝不能直接通过（T-12）→ 只提供「重新认领」。
 *    让审核员点一个注定被 409 的操作是产品缺陷。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import { RagManagementPage } from '@/pages/rag/RagManagementPage'
import { allowedVerificationTargets } from '@/features/rag/useRagManagement'
import { ApiError } from '@/api/client'
import { normalizeHttpError } from '@/api/errors'
import type { RagSource, RagStatistics } from '@/api/types'

const listRagSourcesMock = vi.hoisted(() => vi.fn())
const getRagStatisticsMock = vi.hoisted(() => vi.fn())
const applyRagVerificationMock = vi.hoisted(() => vi.fn())
const registerRagSourceMock = vi.hoisted(() => vi.fn())
const listRagDocumentsMock = vi.hoisted(() => vi.fn())
const listRagChunksMock = vi.hoisted(() => vi.fn())
const getRagDictionariesMock = vi.hoisted(() => vi.fn())
const upsertRagDictionaryEntryMock = vi.hoisted(() => vi.fn())
const deleteRagDictionaryEntryMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    listRagSources: listRagSourcesMock,
    getRagStatistics: getRagStatisticsMock,
    applyRagVerification: applyRagVerificationMock,
    registerRagSource: registerRagSourceMock,
    listRagDocuments: listRagDocumentsMock,
    listRagChunks: listRagChunksMock,
    getRagDictionaries: getRagDictionariesMock,
    upsertRagDictionaryEntry: upsertRagDictionaryEntryMock,
    deleteRagDictionaryEntry: deleteRagDictionaryEntryMock,
  }
})

const TENANT = '11111111-1111-1111-1111-111111111111'
const DECIDER = '33333333-3333-3333-3333-333333333333'

function renderPage(): void {
  render(
    <MemoryRouter>
      <SessionProvider>
        <RagManagementPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

function sampleSource(overrides?: Partial<RagSource>): RagSource {
  return {
    sourceId: 's-1',
    sourceType: '教材',
    title: '人教版八年级上册数学',
    publisher: '人民教育出版社',
    isbn: '9787100000000',
    doi: null,
    trustTier: 'UNVERIFIED',
    verificationStatus: 'PENDING',
    verificationMethod: 'NONE',
    verificationEvidence: null,
    verifiedAt: null,
    verifiedBy: null,
    validDays: 365,
    licenseStatus: 'PENDING',
    stale: false,
    admissible: false,
    objectRef: null,
    contentHash: null,
    createdAt: '2026-10-22T00:00:00Z',
    updatedAt: '2026-10-22T00:00:00Z',
    ...overrides,
  }
}

function sampleStats(overrides?: Partial<RagStatistics>): RagStatistics {
  return {
    totalSources: 5,
    verifiedSources: 3,
    admissibleSources: 2,
    totalDocuments: 4,
    totalChunks: 100,
    retrievableChunks: 60,
    chunksMissingEmbedding: 0,
    missingEmbeddingRatio: 0,
    chunksByRole: { EXPLANATION: 80 },
    sourcesByVerificationStatus: { VERIFIED: 3, PENDING: 2 },
    documentsByParseStatus: { CHUNKED: 4 },
    ...overrides,
  }
}

/**
 * 填租户并查询。
 *
 * ⚠️ 先 clear 再 type：会话里已带权威租户时输入框**已有值**，
 *    直接 type 会追加成「两个 UUID 拼在一起」——真实用户不会这么做，
 *    但测试若不处理，断言的租户值就是错的。
 */
async function query(
  user: ReturnType<typeof userEvent.setup>,
): Promise<void> {
  const tenant = await screen.findByLabelText('租户标识')
  await user.clear(tenant)
  await user.type(tenant, TENANT)
  await user.click(screen.getByRole('button', { name: '查询' }))
}

/** 选中第一条来源。 */
async function selectFirstSource(
  user: ReturnType<typeof userEvent.setup>,
): Promise<void> {
  const list = await screen.findByRole('button', { name: /人教版八年级上册数学/ })
  await user.click(list)
}

beforeEach(() => {
  // ⚠️ 模拟「登录后」：会话里带权威租户（登录响应由后端回带）。
  //    无会话租户时的行为由本文件最后一个用例单独覆盖。
  window.sessionStorage.setItem(
    'agent.session',
    JSON.stringify({
      accessToken: 'tok-agent-admin',
      expiresAt: Date.now() + 900 * 1000,
      tenantId: TENANT,
      principalId: DECIDER,
    }),
  )
  listRagSourcesMock.mockReset()
  getRagStatisticsMock.mockReset()
  applyRagVerificationMock.mockReset()
  registerRagSourceMock.mockReset()
  listRagDocumentsMock.mockReset()
  listRagChunksMock.mockReset()
  getRagDictionariesMock.mockReset()
  upsertRagDictionaryEntryMock.mockReset()
  deleteRagDictionaryEntryMock.mockReset()

  listRagSourcesMock.mockResolvedValue([sampleSource()])
  getRagStatisticsMock.mockResolvedValue(sampleStats())
  listRagDocumentsMock.mockResolvedValue([])
  listRagChunksMock.mockResolvedValue([])
  // ⚠️ 字典默认给「一条动态 + 三类派生」：既让录入下拉有选项，
  //    也让字典维护页有内容可断言。
  getRagDictionariesMock.mockResolvedValue(sampleDictionaries())
  upsertRagDictionaryEntryMock.mockResolvedValue({
    type: 'SOURCE_TYPE',
    code: 'TEXTBOOK',
    label: '教材',
    sortOrder: 0,
    enabled: true,
  })
  deleteRagDictionaryEntryMock.mockResolvedValue(undefined)
})

/** 字典夹具：SOURCE_TYPE 可维护，其余三类派生只读。 */
function sampleDictionaries() {
  return [
    {
      type: 'SOURCE_TYPE',
      editable: true,
      entries: [
        { type: 'SOURCE_TYPE', code: 'TEXTBOOK', label: '教材', sortOrder: 0, enabled: true },
        { type: 'SOURCE_TYPE', code: 'TUTORIAL', label: '教辅', sortOrder: 1, enabled: true },
      ],
    },
    {
      type: 'TRUST_TIER',
      editable: false,
      entries: [
        { type: 'TRUST_TIER', code: 'OFFICIAL_PUBLISHER', label: '教材出版社官方', sortOrder: 0, enabled: true },
        { type: 'TRUST_TIER', code: 'UNVERIFIED', label: '未校验', sortOrder: 0, enabled: true },
      ],
    },
    {
      type: 'VERIFICATION_METHOD',
      editable: false,
      entries: [
        { type: 'VERIFICATION_METHOD', code: 'ISBN', label: 'ISBN 校验', sortOrder: 0, enabled: true },
      ],
    },
    {
      type: 'LICENSE_STATUS',
      editable: false,
      entries: [
        { type: 'LICENSE_STATUS', code: 'LICENSED', label: '已获授权', sortOrder: 0, enabled: true },
      ],
    },
  ]
}

describe('RAG 语料管理页：查询与展示', () => {
  it('应调用管理接口并列出来源', async () => {
    const user = userEvent.setup()
    renderPage()
    await query(user)

    await waitFor(() => {
      expect(listRagSourcesMock).toHaveBeenCalled()
    })
    expect(listRagSourcesMock.mock.calls[0]?.[0]).toBe(TENANT)
    expect(getRagStatisticsMock).toHaveBeenCalled()

    expect(await screen.findByText('人教版八年级上册数学')).toBeInTheDocument()
  })

  it('空列表是合法空态，不是故障', async () => {
    listRagSourcesMock.mockResolvedValue([])
    const user = userEvent.setup()
    renderPage()
    await query(user)

    expect(await screen.findByText('这个租户还没有登记语料来源')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('⛔ 反证：改 mock 标题 → UI 必须跟着变（证明数据真渲染了）', async () => {
    listRagSourcesMock.mockResolvedValue([sampleSource({ title: '苏教版七年级下册英语' })])
    const user = userEvent.setup()
    renderPage()
    await query(user)

    expect(await screen.findByText('苏教版七年级下册英语')).toBeInTheDocument()
    expect(screen.queryByText('人教版八年级上册数学')).not.toBeInTheDocument()
  })

  it('缺租户时不允许查询（本地配置问题不伪装成后端故障）', async () => {
    // ⚠️ 先清会话：默认 beforeEach 已种入带租户的会话，
    //    不清的话这里测的就不是「缺租户」分支了。
    window.sessionStorage.clear()
    renderPage()

    const button = await screen.findByRole('button', { name: '查询' })
    expect(button).toBeDisabled()
    expect(screen.getByText(/请先填写租户标识/)).toBeInTheDocument()
  })

  it('接口失败应给出可执行的下一步，而不是「出错了」', async () => {
    listRagSourcesMock.mockRejectedValue(
      new ApiError(normalizeHttpError(500, { code: 'INTERNAL', message: '服务端异常' })),
    )
    const user = userEvent.setup()
    renderPage()
    await query(user)

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('没能完成')).toBeInTheDocument()
    expect(within(alert).getByRole('button', { name: '重新查询' })).toBeInTheDocument()
  })
})

describe('RAG 语料管理页：状态流转只渲染合法按钮', () => {
  it('PENDING 只提供「审核中」与「已撤回」', () => {
    expect(allowedVerificationTargets('PENDING')).toEqual(['IN_REVIEW', 'REVOKED'])
  })

  it('⛔ REVOKED 是终态：无任何后继（不得提供「撤销撤回」）', () => {
    expect(allowedVerificationTargets('REVOKED')).toEqual([])
  })

  it('⛔ REJECTED 不得直接通过（T-12），只能重新认领或撤回', () => {
    const targets = allowedVerificationTargets('REJECTED')
    expect(targets).toEqual(['IN_REVIEW', 'REVOKED'])
    expect(targets).not.toContain('VERIFIED')
  })

  it('已撤回的来源在页面上不提供任何流转按钮', async () => {
    listRagSourcesMock.mockResolvedValue([
      sampleSource({ verificationStatus: 'REVOKED', trustTier: 'OFFICIAL_PUBLISHER' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await query(user)
    await selectFirstSource(user)

    // ⚠️ 用正则匹配：提示文案里有 <b> 标签，精确文本会被拆成多个节点。
    expect(await screen.findByText(/撤回是终态，不能再流转/)).toBeInTheDocument()
    expect(screen.queryByLabelText('下一步')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '提交判定' })).not.toBeInTheDocument()
  })

  it('审核中时可选择「已通过」，且需同时给出权威等级', async () => {
    listRagSourcesMock.mockResolvedValue([
      sampleSource({ verificationStatus: 'IN_REVIEW' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await query(user)
    await selectFirstSource(user)

    const target = await screen.findByLabelText('下一步')
    await user.selectOptions(target, 'VERIFIED')

    expect(await screen.findByLabelText('判定权威等级')).toBeInTheDocument()
  })
})

describe('RAG 语料管理页：409 冲突被单独呈现', () => {
  it('409 应呈现为「这一步不被允许」，⛔ 不得提示「刷新后重试」', async () => {
    listRagSourcesMock.mockResolvedValue([
      sampleSource({ verificationStatus: 'IN_REVIEW' }),
    ])
    applyRagVerificationMock.mockRejectedValue(
      new ApiError(
        normalizeHttpError(409, {
          code: 'CONFLICT',
          message: '非法的校验状态转移：IN_REVIEW → VERIFIED',
        }),
      ),
    )

    const user = userEvent.setup()
    renderPage()
    await query(user)
    await selectFirstSource(user)

    await user.type(await screen.findByLabelText('决定人（UUID，必填）'), DECIDER)
    await user.type(screen.getByLabelText('校验依据（必填）'), 'ISBN:ref@2026-10-22')
    await user.click(screen.getByRole('button', { name: '提交判定' }))

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('这一步不被允许')).toBeInTheDocument()
    // ⛔ 关键断言：不得出现「重试」字样的引导——刷新不会让非法转移变合法。
    expect(alert.textContent).not.toContain('重试')
    expect(alert.textContent).not.toContain('刷新')
  })

  it('409 后不应把来源状态改掉（不得乐观更新）', async () => {
    listRagSourcesMock.mockResolvedValue([
      sampleSource({ verificationStatus: 'IN_REVIEW' }),
    ])
    applyRagVerificationMock.mockRejectedValue(
      new ApiError(normalizeHttpError(409, { code: 'CONFLICT', message: '非法转移' })),
    )

    const user = userEvent.setup()
    renderPage()
    await query(user)
    await selectFirstSource(user)

    await user.type(await screen.findByLabelText('决定人（UUID，必填）'), DECIDER)
    await user.type(screen.getByLabelText('校验依据（必填）'), 'evidence')
    await user.click(screen.getByRole('button', { name: '提交判定' }))

    await screen.findByRole('alert')
    // 状态标签仍应显示「审核中」——失败不改变既有事实。
    expect(screen.getAllByText('审核中').length).toBeGreaterThan(0)
  })
})

describe('RAG 语料管理页：提交校验决定', () => {
  it('应携带租户、来源、依据与决定人', async () => {
    listRagSourcesMock.mockResolvedValue([
      sampleSource({ verificationStatus: 'IN_REVIEW' }),
    ])
    applyRagVerificationMock.mockResolvedValue(
      sampleSource({ verificationStatus: 'VERIFIED', trustTier: 'OFFICIAL_PUBLISHER' }),
    )

    const user = userEvent.setup()
    renderPage()
    await query(user)
    await selectFirstSource(user)

    await user.type(await screen.findByLabelText('决定人（UUID，必填）'), DECIDER)
    await user.type(screen.getByLabelText('校验依据（必填）'), 'ISBN:ref@2026-10-22')
    await user.click(screen.getByRole('button', { name: '提交判定' }))

    await waitFor(() => {
      expect(applyRagVerificationMock).toHaveBeenCalled()
    })
    const args = applyRagVerificationMock.mock.calls[0]
    expect(args?.[0]).toBe(TENANT)
    expect(args?.[1]).toBe('s-1')
    // ⚠️ 逐字段断言（不用 toMatchObject）：toMatchObject 的失败信息
    //    会把实际值折叠成「…(5)」，排查时看不到到底哪个字段不符。
    expect(args?.[2]).toEqual({
      targetStatus: 'VERIFIED',
      evidenceRef: 'ISBN:ref@2026-10-22',
      decidedBy: DECIDER,
      verificationMethod: 'ISBN',
      // ⚠️ 表单默认等级是 OFFICIAL_TUTORIAL（教辅品牌正式出版），
      //    与「教材出版社官方」不同——测试必须写实际默认值，
      //    否则这条断言会在有人改了默认等级后仍然通过（测不到东西）。
      trustTier: 'OFFICIAL_TUTORIAL',
      validDays: 365,
    })
  })

  it('未填决定人时不得提交（登记必须可追溯到人）', async () => {
    listRagSourcesMock.mockResolvedValue([
      sampleSource({ verificationStatus: 'IN_REVIEW' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await query(user)
    await selectFirstSource(user)

    const submit = await screen.findByRole('button', { name: '提交判定' })
    expect(submit).toBeDisabled()
  })

  it('未填依据时不得提交（无依据的「已通过」不可复核）', async () => {
    listRagSourcesMock.mockResolvedValue([
      sampleSource({ verificationStatus: 'IN_REVIEW' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await query(user)
    await selectFirstSource(user)

    await user.type(await screen.findByLabelText('决定人（UUID，必填）'), DECIDER)
    expect(screen.getByRole('button', { name: '提交判定' })).toBeDisabled()
  })
})

describe('RAG 语料管理页：统计诚实呈现', () => {
  /**
   * ⚠️ **统计区已在 Point 1 拆成 tab**（`索引与统计`）：
   *    早前统计与来源列表同页平铺，故老用例「查询后即可见来源总数」。
   *    现在必须先切到该 tab。切 tab 是**导航动作**，不改变统计口径本身，
   *    故断言一字不改，只补一次点击。
   *
   * ⚠️ 用 `role="tab"` 而非 `"button"`：tab 条上的按钮显式声明了
   *    `role="tab"`（见页面里的 {@code TabNav}），按 button 角色查不到。
   */
  async function openStatsTab(user: ReturnType<typeof userEvent.setup>): Promise<void> {
    await user.click(screen.getByRole('tab', { name: '索引与统计' }))
  }

  it('区分「已入库」与「可检索」，⛔ 不得把总数当作可用量', async () => {
    getRagStatisticsMock.mockResolvedValue(
      sampleStats({ totalChunks: 100, retrievableChunks: 40, chunksMissingEmbedding: 10, missingEmbeddingRatio: 0.1 }),
    )
    const user = userEvent.setup()
    renderPage()
    await query(user)
    await openStatsTab(user)

    expect(await screen.findByText('已入库 ≠ 可检索')).toBeInTheDocument()
    expect(screen.getByText('缺向量')).toBeInTheDocument()
  })

  it('无差额时不做无谓提示', async () => {
    // ⚠️ 默认样例有差额（100 入库 / 60 可检索），故本用例显式构造「无差额」数据。
    getRagStatisticsMock.mockResolvedValue(
      sampleStats({
        totalChunks: 100,
        retrievableChunks: 100,
        chunksMissingEmbedding: 0,
        missingEmbeddingRatio: 0,
      }),
    )
    const user = userEvent.setup()
    renderPage()
    await query(user)
    await openStatsTab(user)

    await screen.findByText('来源总数')
    expect(screen.queryByText('已入库 ≠ 可检索')).not.toBeInTheDocument()
    expect(screen.queryByText('缺向量')).not.toBeInTheDocument()
  })
})

describe('RAG 语料管理页：租户上下文来自 platform 会话（2026-10-22 修复）', () => {
  /**
   * ⛔ 本组测试是「agent-admin 登录后 RAG 管理页不显示」这一问题的**回归防线**。
   *
   * 根因链：
   *   ① 登录响应**不含** tenantId → 前端拿不到权威租户；
   *   ② RAG 库曾存 `rag_tenant` 租户副本，与 platform 的 ID 空间割裂，
   *      且该表须手工维护租户清单，否则外键让写入直接失败；
   *   ③ 于是用户只能手填 UUID，填错不报错、只「查不到数据」——
   *      表现为「页面不显示」。
   *
   * 修复：登录响应回带权威 tenantId（后端认证层本就持有），
   *       前端存入会话，RAG 管理页自动采用会话中的租户。
   */
  it('会话中的租户应自动填入租户输入框（无需手填）', async () => {
    renderPage()
    const tenant = await screen.findByLabelText('租户标识')
    expect(tenant).toHaveValue(TENANT)
  })

  it('会话有租户时「查询」按钮应可用（不再被禁用）', async () => {
    renderPage()
    const button = await screen.findByRole('button', { name: '查询' })
    expect(button).toBeEnabled()
  })

  it('⛔ 反证：会话无租户时才应禁用查询（禁用逻辑仍在，不是被删掉了）', async () => {
    window.sessionStorage.clear()
    renderPage()
    const button = await screen.findByRole('button', { name: '查询' })
    expect(button).toBeDisabled()
    expect(screen.getByText(/请先填写租户标识/)).toBeInTheDocument()
  })
})

/* ── 字典维护（Point 2：枚举下拉的数据源）─────────────────── */

describe('RAG 语料管理页：字典维护', () => {
  const openDictTab = async (
    user: ReturnType<typeof userEvent.setup>,
  ): Promise<void> => {
    await user.click(screen.getByRole('tab', { name: '字典维护' }))
  }

  it('列出四类字典，且只有「来源类型」标为可维护', async () => {
    const user = userEvent.setup()
    renderPage()
    await openDictTab(user)

    expect(await screen.findByText('来源类型')).toBeInTheDocument()
    expect(screen.getByText('权威等级')).toBeInTheDocument()
    expect(screen.getByText('校验手段')).toBeInTheDocument()
    expect(screen.getByText('版权状态')).toBeInTheDocument()

    // ⚠️ 派生字典必须**只读**：它们参与准入判定，前端改了会漂移治理语义。
    expect(screen.getAllByText('只读').length).toBeGreaterThanOrEqual(3)
    expect(screen.getByText('可维护')).toBeInTheDocument()
    expect(getRagDictionariesMock).toHaveBeenCalledWith(TENANT)
  })

  it('⛔ 派生字典不渲染「新增 / 删除」入口（只读）', async () => {
    const user = userEvent.setup()
    renderPage()
    await openDictTab(user)

    expect(await screen.findByText('校验手段')).toBeInTheDocument()
    // ⚠️ 夹具里只有 SOURCE_TYPE 可维护、且恰有 2 条条目。
    //    若派生字典也渲染了入口，这三个计数就会大于 2 / 2 / 1。
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: '删除' }).length).toBe(2),
    )
    expect(screen.getAllByRole('button', { name: '编辑' }).length).toBe(2)
    // 「新增」表单只在可编辑类型下出现：1 个（SOURCE_TYPE），而非 4 个。
    expect(screen.getAllByRole('button', { name: '新增' }).length).toBe(1)
    // 派生字典区只有说明文字，没有可交互控件。
    // ⚠️ 三类派生字典各一条，故用 getAllByText。
    expect(
      screen.getAllByText(/该字典由系统枚举派生，不可在此修改/).length,
    ).toBe(3)
  })

  it('动态字典可新增：提交后调用 upsert 并刷新列表', async () => {
    const user = userEvent.setup()
    renderPage()
    await openDictTab(user)

    await user.type(screen.getByLabelText(/编码/), 'WORKBOOK')
    await user.type(screen.getByLabelText(/名称/), '练习册')
    await user.click(screen.getByRole('button', { name: '新增' }))

    await waitFor(() =>
      expect(upsertRagDictionaryEntryMock).toHaveBeenCalledWith(
        TENANT,
        'SOURCE_TYPE',
        expect.objectContaining({ code: 'WORKBOOK', label: '练习册', enabled: true }),
      ),
    )
    // ⚠️ 写成功后必须重新读取，而不是留在旧快照上假装已生效。
    await waitFor(() => expect(getRagDictionariesMock).toHaveBeenCalledTimes(2))
  })

  it('动态字典可删除：提交后调用 delete 并刷新列表', async () => {
    const user = userEvent.setup()
    renderPage()
    await openDictTab(user)

    const deleteButtons = await screen.findAllByRole('button', { name: '删除' })
    await user.click(deleteButtons[0]!)

    await waitFor(() =>
      expect(deleteRagDictionaryEntryMock).toHaveBeenCalledWith(
        TENANT,
        'SOURCE_TYPE',
        'TEXTBOOK',
      ),
    )
    await waitFor(() => expect(getRagDictionariesMock).toHaveBeenCalledTimes(2))
  })

  it('字典读取失败时给出错误态与重试，而不是空白页', async () => {
    getRagDictionariesMock.mockReset()
    getRagDictionariesMock.mockRejectedValue(
      new ApiError(normalizeHttpError(500, { message: '字典库暂时不可用' })),
    )
    const user = userEvent.setup()
    renderPage()
    await openDictTab(user)

    // ⚠️ 断言 ErrorState 的**稳定标签**而非具体文案：
    //    文案会随后端映射调整，标签是组件契约。
    expect(await screen.findByText('没能完成')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新读取' })).toBeInTheDocument()
  })
})

/* ── 录入表单：来源类型走字典下拉（Point 2）───────────────── */

describe('RAG 语料管理页：录入表单的来源类型下拉', () => {
  const openEntryTab = async (
    user: ReturnType<typeof userEvent.setup>,
  ): Promise<void> => {
    await user.click(screen.getByRole('tab', { name: '录入新来源' }))
  }

  it('字典就绪时来源类型渲染为下拉，且选项来自后台字典', async () => {
    const user = userEvent.setup()
    renderPage()
    await openEntryTab(user)

    const select = await screen.findByLabelText(/来源类型/)
    expect(select.tagName).toBe('SELECT')
    expect(screen.getByRole('option', { name: '教材' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '教辅' })).toBeInTheDocument()
  })

  it('⛔ 反证：字典读取失败时回落到自由文本，不阻断录入', async () => {
    getRagDictionariesMock.mockReset()
    getRagDictionariesMock.mockRejectedValue(
      new ApiError(normalizeHttpError(500, { message: 'dict down' })),
    )
    const user = userEvent.setup()
    renderPage()
    await openEntryTab(user)

    const input = await screen.findByLabelText(/来源类型/)
    expect(input.tagName).toBe('INPUT')
    // ⚠️ 回落不是「不校验」：仍然必填（label 带 * 标记，
    //    且空值提交会被 validate 拦下）。
    expect(input).toHaveAccessibleName(/来源类型/)
    expect(screen.getByText(/来源类型/)).toBeInTheDocument()
  })
})

/* ── 页签的无障碍契约（2026-10-24 UX 评审 P1）───────────── */

/**
 * ⛔ 这些断言守的是「键盘与读屏能不能用这条页签栏」。
 *    只有 `role="tab"` + `aria-selected`，缺 tabpanel / 方向键 / roving tabindex 时，
 *    页签栏对键盘用户等于不存在——读屏不知道内容归谁，Tab 键要按 6 次才能穿过。
 */
describe('RAG 语料管理页：页签的无障碍契约', () => {
  it('当前页签必须指向内容区（aria-controls ↔ tabpanel 不断链）', async () => {
    renderPage()
    const tab = await screen.findByRole('tab', { name: '来源列表与审核' })
    const panel = screen.getByRole('tabpanel')
    expect(tab).toHaveAttribute('aria-controls', panel.id)
    expect(panel).toHaveAttribute('aria-labelledby', tab.id)
  })

  it('只有当前页签留在 Tab 序列里（roving tabindex）', async () => {
    renderPage()
    const active = await screen.findByRole('tab', { name: '来源列表与审核' })
    const other = screen.getByRole('tab', { name: '索引与统计' })
    expect(active).toHaveAttribute('tabindex', '0')
    expect(other).toHaveAttribute('tabindex', '-1')
  })

  it('方向键可切换页签，且焦点跟着走', async () => {
    const user = userEvent.setup()
    renderPage()
    const first = await screen.findByRole('tab', { name: '来源列表与审核' })
    first.focus()
    await user.keyboard('{ArrowRight}')

    const second = screen.getByRole('tab', { name: '录入新来源' })
    expect(second).toHaveAttribute('aria-selected', 'true')
    // ⚠️ 焦点必须跟着切换：否则用户按了方向键却不知道自己在哪。
    expect(second).toHaveFocus()
    expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', second.id)
  })
})

/* ── 上传文件：键盘与读屏必须够得着（2026-10-24 UX 评审 P0）── */

describe('RAG 语料管理页：上传文件必须键盘可达', () => {
  const openImportTab = async (user: ReturnType<typeof userEvent.setup>): Promise<void> => {
    await user.click(screen.getByRole('tab', { name: '批量导入' }))
  }

  it('⛔ 文件 input 不得用 display:none 隐藏（那会退出无障碍树与焦点序列）', async () => {
    const user = userEvent.setup()
    renderPage()
    await openImportTab(user)

    const input = (await screen.findByLabelText('上传文件')) as HTMLInputElement
    // 视觉隐藏但**留在无障碍树里**：必须是 .sr-only，不能是 display:none。
    expect(input).toHaveClass('sr-only')
    expect(input.style.display).toBe('')
  })

  it('⛔ 上传控件本身可聚焦，Enter 能触发文件选择', async () => {
    const user = userEvent.setup()
    renderPage()
    await openImportTab(user)

    const input = (await screen.findByLabelText('上传文件')) as HTMLInputElement
    const trigger = screen.getByRole('button', { name: '上传文件' })
    expect(trigger).toHaveAttribute('tabindex', '0')

    const clickSpy = vi.spyOn(input, 'click')
    trigger.focus()
    await user.keyboard('{Enter}')
    expect(clickSpy).toHaveBeenCalled()
  })
})

/* ── 表单错误必须与输入框关联（P1）────────────────────────── */

describe('RAG 语料管理页：校验错误与输入框关联', () => {
  it('⛔ 错误文案要被 aria-describedby 引用（否则只在出错瞬间播报一次）', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('tab', { name: '录入新来源' }))

    await user.click(screen.getByRole('button', { name: '登记来源' }))
    const error = await screen.findByText('标题不能为空')
    const title = screen.getByLabelText(/^标题/)
    expect(title).toHaveAttribute('aria-invalid', 'true')
    expect(title.getAttribute('aria-describedby') ?? '').toContain(error.id)
  })
})

/* ── 界面文案纪律：不得混入 emoji 与 markdown（P1）────────── */

describe('RAG 语料管理页：界面文案纪律', () => {
  /** 切到「索引与统计」页签（统计区在 Point 1 之后已独立成页签）。 */
  const openStatsTab = async (user: ReturnType<typeof userEvent.setup>): Promise<void> => {
    await user.click(screen.getByRole('tab', { name: '索引与统计' }))
  }

  it('⛔ 详情与审核区不得出现 emoji 或 markdown 标记', async () => {
    const user = userEvent.setup()
    renderPage()
    await query(user)
    await selectFirstSource(user)
    await screen.findByLabelText('校验依据（必填）')

    // ⚠️ JSX 不解析 markdown：** 会以裸星号显示；emoji 则破坏「墨 + 纸」两色纪律。
    expect(document.body.textContent).not.toMatch(/⚠/)
    expect(document.body.textContent).not.toContain('**')
  })

  it('⛔ 统计区的「召不回」用 <b> 强调，不写成 markdown', async () => {
    const user = userEvent.setup()
    getRagStatisticsMock.mockResolvedValue(
      sampleStats({ totalChunks: 100, retrievableChunks: 40, chunksMissingEmbedding: 10 }),
    )
    renderPage()
    await query(user)
    await openStatsTab(user)

    await screen.findByText('缺向量')
    expect(screen.getByText(/召不回/)).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('**')
  })
})
