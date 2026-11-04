/**
 * 审核「双人复核」前端约束测试（V1.0.2 S3-6）。
 *
 * <p><b>⚠️ 这组测试锁定的是「不要把规则交给用户去试错」</b>：
 * 后端已强制「VERIFIED 必须双人复核」，但那只是最后一道防线。
 * 如果前端允许用户填一个空的/雷同的复核人再提交、然后弹一个 409，
 * 用户看到的是「这一步不被允许」却不知道**该填什么**——
 * 正确的做法是**在提交前就把规则讲清楚并拦住**。
 *
 * <p>覆盖三条前端约束：
 * <ol>
 *   <li>选择「通过校验(VERIFIED)」时，出现复核人输入框；</li>
 *   <li>复核人为空 → 提交按钮禁用 + 给出"还需填写复核人"的提示；</li>
 *   <li>复核人 = 决定人 → 提交按钮禁用 + 警告"不能自己复核自己"。</li>
 * </ol>
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import { RagManagementPage } from '@/pages/rag/RagManagementPage'
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

const DECIDER = '11111111-1111-4111-8111-111111111111'
const REVIEWER = '22222222-2222-4222-8222-222222222222'

/** 一个处于 IN_REVIEW 的来源（可签发 VERIFIED）。 */
const IN_REVIEW_SOURCE: RagSource = {
  sourceId: '33333333-3333-4333-8333-333333333333',
  sourceType: 'TEXTBOOK',
  title: '沪教版英语六年级上册',
  publisher: '上海教育出版社',
  isbn: '978-7-5444-0000-0',
  doi: null,
  trustTier: 'UNVERIFIED',
  verificationStatus: 'IN_REVIEW',
  verificationMethod: 'NONE',
  verificationEvidence: null,
  verifiedAt: null,
  validDays: 365,
  licenseStatus: 'PENDING',
  createdAt: '2026-11-02T00:00:00Z',
  updatedAt: '2026-11-02T00:00:00Z',
} as unknown as RagSource

const STATS: RagStatistics = {
  totalSources: 1,
  verifiedSources: 0,
  admissibleSources: 0,
  totalDocuments: 0,
  totalChunks: 0,
  retrievableChunks: 0,
  chunksMissingEmbedding: 0,
  missingEmbeddingRatio: 0,
  chunksByRole: {},
  sourcesByVerificationStatus: { IN_REVIEW: 1 },
  documentsByParseStatus: {},
} as unknown as RagStatistics

const TENANT = '44444444-4444-4444-8444-444444444444'

function renderPage() {
  return render(
    <MemoryRouter>
      <SessionProvider>
        <RagManagementPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

/**
 * 填租户并查询（参照既有 ragManagement.page.test 的做法）。
 *
 * ⚠️ 先 clear 再 type：会话里已带租户时输入框**已有值**，
 *    直接 type 会拼成两个 UUID。
 */
async function querySources(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  const tenant = await screen.findByLabelText('租户标识')
  await user.clear(tenant)
  await user.type(tenant, TENANT)
  await user.click(screen.getByRole('button', { name: '查询' }))
  await waitFor(() => expect(listRagSourcesMock).toHaveBeenCalled())
}

/** 选中来源并切到「通过校验」。 */
async function openAndPickVerified(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(await screen.findByRole('button', { name: /沪教版英语六年级上册/ }))
  await user.selectOptions(await screen.findByLabelText('下一步'), 'VERIFIED')
}

describe('审核：双人复核前端约束（V1.0.2 S3-6）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // ⚠️ 模拟「登录后」：会话里带权威租户与主体（登录响应由后端回带）
    window.sessionStorage.setItem(
      'agent.session',
      JSON.stringify({
        accessToken: 'tok-test',
        expiresAt: Date.now() + 900 * 1000,
        tenantId: TENANT,
        principalId: DECIDER,
      }),
    )
    listRagSourcesMock.mockResolvedValue([IN_REVIEW_SOURCE])
    getRagStatisticsMock.mockResolvedValue(STATS)
    listRagDocumentsMock.mockResolvedValue([])
    listRagChunksMock.mockResolvedValue([])
    getRagDictionariesMock.mockResolvedValue({})
    applyRagVerificationMock.mockResolvedValue(IN_REVIEW_SOURCE)
  })

  it('选择「通过校验」后出现复核人输入框', async () => {
    const user = userEvent.setup()
    renderPage()

    await querySources(user)
    await openAndPickVerified(user)
    expect(await screen.findByLabelText(/复核人 UUID/)).toBeInTheDocument()
  })

  it('反证：复核人为空时提交按钮禁用，并提示还需填写', async () => {
    const user = userEvent.setup()
    renderPage()

    await querySources(user)
    await openAndPickVerified(user)

    // 填入决定人与依据（使其他条件都满足）
    const deciderInput = await screen.findByLabelText(/决定人/)
    await user.clear(deciderInput)
    await user.type(deciderInput, DECIDER)
    const evidenceInput = await screen.findByLabelText(/校验依据/)
    await user.type(evidenceInput, 'ISBN 校验通过')

    // ⚠️ 复核人留空 → 按钮必须禁用
    const submit = await screen.findByRole('button', { name: /提交判定/ })
    expect(submit).toBeDisabled()

    // 且必须给出「还需填写复核人」的提示，而不是让用户猜
    expect(await screen.findByText(/还需填写与决定人不同的复核人/)).toBeInTheDocument()
  })

  it('反证：复核人 = 决定人时按钮禁用，并警告不能自己复核自己', async () => {
    const user = userEvent.setup()
    renderPage()

    await querySources(user)
    await openAndPickVerified(user)

    const deciderInput = await screen.findByLabelText(/决定人/)
    await user.clear(deciderInput)
    await user.type(deciderInput, DECIDER)
    await user.type(await screen.findByLabelText(/校验依据/), 'ISBN 校验通过')

    // 复核人填成与决定人相同的值
    const reviewerInput = await screen.findByLabelText(/复核人 UUID/)
    await user.type(reviewerInput, DECIDER)

    const submit = await screen.findByRole('button', { name: /提交判定/ })
    expect(submit).toBeDisabled()
    expect(await screen.findByText(/复核人不能是决定人本人/)).toBeInTheDocument()
  })

  it('正向：填入不同复核人后可提交，且请求体带 verifiedBy', async () => {
    const user = userEvent.setup()
    renderPage()

    await querySources(user)
    await openAndPickVerified(user)

    const deciderInput = await screen.findByLabelText(/决定人/)
    await user.clear(deciderInput)
    await user.type(deciderInput, DECIDER)
    await user.type(await screen.findByLabelText(/校验依据/), 'ISBN 校验通过')
    await user.type(await screen.findByLabelText(/复核人 UUID/), REVIEWER)

    const submit = await screen.findByRole('button', { name: /提交判定/ })
    await waitFor(() => expect(submit).toBeEnabled())
    await user.click(submit)

    await waitFor(() => expect(applyRagVerificationMock).toHaveBeenCalled())
    // ⚠️ mock.calls[0] 在 noUncheckedIndexedAccess 下是可能 undefined 的元组
    const payload = applyRagVerificationMock.mock.calls[0]![2]
    expect(payload.targetStatus).toBe('VERIFIED')
    // ⚠️ 核心：复核人必须随请求发出，否则后端必然 409
    expect(payload.verifiedBy).toBe(REVIEWER)
  })

  it('反向：非 VERIFIED 状态不显示复核人输入框（不制造无谓负担）', async () => {
    const user = userEvent.setup()
    renderPage()

    await querySources(user)
    await user.click(await screen.findByRole('button', { name: /沪教版英语六年级上册/ }))

    // 选择 REJECTED（驳回不需要双人复核）
    const targets = await screen.findByLabelText('下一步')
    const options = Array.from((targets as HTMLSelectElement).options).map((o) => o.value)
    if (options.includes('REJECTED')) {
      await user.selectOptions(targets, 'REJECTED')
      expect(screen.queryByLabelText(/复核人 UUID/)).not.toBeInTheDocument()
    }
  })
})
