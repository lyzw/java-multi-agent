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

  listRagSourcesMock.mockResolvedValue([sampleSource()])
  getRagStatisticsMock.mockResolvedValue(sampleStats())
  listRagDocumentsMock.mockResolvedValue([])
  listRagChunksMock.mockResolvedValue([])
})

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
  it('区分「已入库」与「可检索」，⛔ 不得把总数当作可用量', async () => {
    getRagStatisticsMock.mockResolvedValue(
      sampleStats({ totalChunks: 100, retrievableChunks: 40, chunksMissingEmbedding: 10, missingEmbeddingRatio: 0.1 }),
    )
    const user = userEvent.setup()
    renderPage()
    await query(user)

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
