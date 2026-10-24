/**
 * 知识检索页接线测试（FE-RAG）
 *
 * ⛔ 沿用 FE-D8/FE-D9 范式：
 *   - 行为测试 + 连通性测试（确实调了检索接口）
 *   - 反证：改 mock 数据 → UI 必须跟着变
 *   - 五态诚实：空态 ≠ 故障；未接入 ≠ 故障；超时 ≠ 网络失败
 *   - 租户上下文：检索按租户隔离（RLS），必须显式携带，缺失即不可提交
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import { RagSearchPage } from '@/pages/rag/RagSearchPage'
import { ApiError } from '@/api/client'
import type { RagRetrievalResponse, RagGenerateResponse } from '@/api/types'

const retrieveChunksMock = vi.hoisted(() => vi.fn())
const generateRagAnswerMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    retrieveChunks: retrieveChunksMock,
    generateRagAnswer: generateRagAnswerMock,
  }
})

const TENANT = '11111111-1111-1111-1111-111111111111'

function renderPage(): void {
  render(
    <MemoryRouter>
      <SessionProvider>
        <RagSearchPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** 填入租户标识：检索按租户隔离，缺失时后端直接 400，页面也不允许提交。 */
async function fillTenant(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  /*
   * ⚠️ 租户标识已折进「高级设置」（原生 <details>）：真实浏览器里默认收起、
   *    看不见也点不到。故先展开折叠区再填——
   *    这样验的是真用户路径，而不是 jsdom 不隐藏 details 内容的巧合。
   */
  const details = document.querySelector('details')
  expect(details, '应有「高级设置」折叠区').toBeTruthy()
  await user.click(screen.getByText('高级设置'))
  expect((details as HTMLDetailsElement).open).toBe(true)
  const field = await screen.findByLabelText('租户标识')
  await user.type(field, TENANT)
}

function sampleRetrieval(overrides?: Partial<RagRetrievalResponse>): RagRetrievalResponse {
  return {
    hits: [
      {
        chunkId: 'c-1',
        text: '二次函数 y=ax²+bx+c 的顶点坐标为 (-b/2a, 4ac-b²/4a)。',
        role: 'EXPLANATION',
        score: 0.82,
        edition: '人教版·八年级上册·2024',
        locator: '八年级上册 第 3 章 第 45 页',
        channel: 'DENSE',
        reason: '知识点匹配',
      },
    ],
    versionMismatchDetected: false,
    answerContentSuppressed: true,
    primaryEdition: '人教版·八年级上册·2024',
    ...overrides,
  }
}

function sampleGeneration(overrides?: Partial<RagGenerateResponse>): RagGenerateResponse {
  return {
    answer: '顶点公式是 -b/2a 和 4ac-b²/4a。',
    citations: [],
    // ⚠️ B5：generator/disclaimer 不是可选项——后端必须如实标注生成方式，
    //    前端必须展示。缺省值按「逐字摘录 + 无附加说明」构造。
    generator: 'EXCERPT_ASSEMBLY',
    disclaimer: '',
    versionMismatchDetected: false,
    answerContentSuppressed: false,
    ...overrides,
  }
}

beforeEach(() => {
  retrieveChunksMock.mockReset()
  generateRagAnswerMock.mockReset()
  retrieveChunksMock.mockResolvedValue(sampleRetrieval())
  generateRagAnswerMock.mockResolvedValue(sampleGeneration())
  // ⚠️ 清掉上次记忆的租户，避免用例之间互相污染。
  window.localStorage.clear()
})

describe('知识检索页（FE-RAG 接线）', () => {
  it('⛔ 连通性：提交后确实调用检索接口，并把问题与租户带上去', async () => {
    renderPage()
    const user = userEvent.setup()

    const input = await screen.findByLabelText('你的问题')
    await user.type(input, '二次函数的顶点怎么求？')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    await waitFor(() => {
      expect(retrieveChunksMock).toHaveBeenCalled()
    })
    const call = retrieveChunksMock.mock.calls[0]?.[0] as { query: string }
    expect(call.query).toBe('二次函数的顶点怎么求？')
    // ⚠️ 租户必须随请求传递：后端据此重建 RLS 租户上下文。
    const tenantArg = retrieveChunksMock.mock.calls[0]?.[2] as string | undefined
    expect(tenantArg).toBe(TENANT)
  })

  it('⛔ 租户必填：缺租户时按钮禁用，不发出无效请求', async () => {
    renderPage()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('你的问题'), '顶点怎么求')
    const button = screen.getByRole('button', { name: /检索并生成/ }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    expect(retrieveChunksMock).not.toHaveBeenCalled()
  })

  it('⛔ 反证：改 mock 命中文本 → UI 必须跟着变', async () => {
    renderPage()
    const user = userEvent.setup()

    retrieveChunksMock.mockResolvedValue(
      sampleRetrieval({
        hits: [
          {
            chunkId: 'c-x',
            text: '勾股定理 a²+b²=c²。',
            role: 'EXPLANATION',
            score: 0.7,
            edition: '人教版·八年级上册·2024',
            locator: '八上 第 1 章',
            channel: 'DENSE',
            reason: '知识点匹配',
          },
        ],
      }),
    )

    const input = await screen.findByLabelText('你的问题')
    await user.type(input, '直角三角形边长关系')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    expect(await screen.findByText('勾股定理 a²+b²=c²。')).toBeTruthy()
    expect(screen.queryByText(/顶点坐标为/)).toBeNull()
  })

  it('⛔ 诚实空态：0 命中 ≠ 故障', async () => {
    retrieveChunksMock.mockResolvedValue(sampleRetrieval({ hits: [] }))
    renderPage()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('你的问题'), '很冷门的问题')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    expect(await screen.findByText(/没有找到相关语料/)).toBeTruthy()
    // ⚠️ 空态不得说成「检索失败」
    expect(screen.queryByText(/检索失败/)).toBeNull()
  })

  it('⛔ 诚实错误态：检索失败时说原因，且不伪装空态', async () => {
    retrieveChunksMock.mockRejectedValue(
      new ApiError({
        status: 500,
        code: 'HTTP_500',
        message: '服务暂时不可用',
        fieldErrors: {},
      }),
    )
    renderPage()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('你的问题'), '顶点怎么求')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    expect(await screen.findByText('服务暂时不可用')).toBeTruthy()
    expect(screen.queryByText(/没有找到相关语料/)).toBeNull()
  })

  it('⛔ 诚实超时态：超时文案与网络失败不同', async () => {
    retrieveChunksMock.mockRejectedValue(
      new ApiError({
        status: 0,
        code: 'REQUEST_TIMEOUT',
        message: '请求超时，后端没有在规定时间内返回。可以稍后重试，或把问题说得更具体一些。',
        fieldErrors: {},
      }),
    )
    renderPage()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('你的问题'), '顶点怎么求')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    expect(await screen.findByText(/请求超时/)).toBeTruthy()
  })

  it('⛔ 来源渲染：命中片段带引用与版本，可回溯', async () => {
    renderPage()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('你的问题'), '顶点怎么求')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    expect(await screen.findByText(/二次函数 y=ax²\+bx\+c 的顶点坐标为/)).toBeTruthy()
    expect(screen.getByText('来源 1')).toBeTruthy()
    expect(screen.getByText(/八年级上册 第 3 章 第 45 页/)).toBeTruthy()
  })

  it('⛔ 生成成功：渲染模型回答正文', async () => {
    generateRagAnswerMock.mockResolvedValue(
      sampleGeneration({ answer: '用配方法把一般式写成顶点式。' }),
    )
    renderPage()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('你的问题'), '顶点怎么求')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    expect(await screen.findByText('用配方法把一般式写成顶点式。')).toBeTruthy()
  })

  it('⛔ 生成未接入：404 冷静提示，不是红色故障', async () => {
    generateRagAnswerMock.mockRejectedValue(
      new ApiError({
        status: 404,
        code: 'NOT_FOUND',
        message: 'Not Found',
        fieldErrors: {},
      }),
    )
    renderPage()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('你的问题'), '顶点怎么求')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    // ⚠️ 必须是「未接入」冷静提示，而不是「生成回答失败」红框
    expect(await screen.findByText(/生成回答能力尚未接入后端/)).toBeTruthy()
    expect(screen.queryByText(/生成回答失败/)).toBeNull()
  })

  // ── B5：生成方式必须如实展示，依据不足不是故障 ──────────────

  it('⛔ B5：生成方式必须原样展示，不得伪装成「AI 讲解」', async () => {
    generateRagAnswerMock.mockResolvedValue(
      sampleGeneration({ generator: 'EXCERPT_ASSEMBLY' }),
    )
    renderPage()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('你的问题'), '顶点怎么求')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    expect(await screen.findByText(/整理方式/)).toBeTruthy()
    expect(screen.getByText('EXCERPT_ASSEMBLY')).toBeTruthy()
    expect(screen.getByText(/逐字摘自教材原文，未作改写/)).toBeTruthy()
    // ⛔ 不得把摘录说成模型生成
    expect(screen.queryByText(/AI 讲解|AI生成|智能生成/)).toBeNull()
  })

  it('⛔ B5：依据不足（200 + 空 answer）是冷静提示，不是红色故障', async () => {
    generateRagAnswerMock.mockResolvedValue(
      sampleGeneration({
        answer: '',
        disclaimer: '指定的 2 处依据在当前「讲解」模式下不可引用（答案类内容已被策略排除）。',
        answerContentSuppressed: true,
      }),
    )
    renderPage()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('你的问题'), '顶点怎么求')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    expect(await screen.findByText(/不可引用/)).toBeTruthy()
    // ⚠️ 依据不足 ≠ 生成失败
    expect(screen.queryByText(/生成回答失败/)).toBeNull()
  })

  it('⛔ B5：0 命中时不发起生成（后端 hitIds 非空，空数组会换回 400）', async () => {
    retrieveChunksMock.mockResolvedValue(sampleRetrieval({ hits: [] }))
    renderPage()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('你的问题'), '很冷门的问题')
    await fillTenant(user)
    await user.click(screen.getByRole('button', { name: /检索并生成/ }))

    expect(await screen.findByText(/没有找到相关语料/)).toBeTruthy()
    // ⛔ 空 hitIds 会让后端 400，把一次合法空检索显示成「生成失败」
    expect(generateRagAnswerMock).not.toHaveBeenCalled()
  })

  it('⛔ 空问题不提交：按钮初始禁用', async () => {
    renderPage()
    const button = screen.getByRole('button', { name: /检索并生成/ }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
  })

  /* ── 面向师生的文案纪律（2026-10-24 UX 评审 P1）────────── */

  it('⛔ 租户提示面向师生：保留「已自动填入」「留空将无法检索」，去掉工程术语', async () => {
    renderPage()
    const hint = await screen.findByText(/留空将无法检索/)
    expect(hint.textContent).toContain('已按登录信息自动填入')
    // ⛔ RLS 之类的工程术语对师生没有意义；emoji 也破坏「墨 + 纸」两色纪律。
    expect(hint.textContent).not.toMatch(/RLS|⚠/)
  })

  it('⛔ 检索页文案不得出现 markdown 标记或 emoji', async () => {
    renderPage()
    await screen.findByLabelText('你的问题')
    expect(document.body.textContent).not.toContain('**')
    expect(document.body.textContent).not.toMatch(/⚠/)
  })

  /*
   * ── 渐进披露（2026-10-24 UX 评审 P1）──────────────────────────
   *
   * 检索设置原来一屏铺开 8 个控件（含一个裸 UUID 输入框），
   * 对只想问一句「二次函数的顶点怎么求」的学生过重。
   * 定案是**折叠**而不是删字段——所以断言分两面：
   *   ① 低频项折进「高级设置」且默认收起；
   *   ② 但字段一个都没删（仍在 DOM 里，租户标识仍在折叠区内）。
   */
  it('⛔ 渐进披露：低频项折进「高级设置」，默认收起', async () => {
    renderPage()
    await screen.findByLabelText('你的问题')

    // 高频项常显：问题、检索模式、教材版本五轴
    expect(screen.getByLabelText('你的问题')).toBeInTheDocument()
    expect(screen.getByLabelText('检索模式')).toBeInTheDocument()
    for (const label of ['学科', '年级', '册次', '出版社', '版次']) {
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }

    // 折叠区存在，且默认收起
    const details = document.querySelector('details')
    expect(details, '应有「高级设置」折叠区').toBeTruthy()
    expect((details as HTMLDetailsElement).open).toBe(false)
    expect(screen.getByText('高级设置')).toBeInTheDocument()
    // 折叠区旁边要有一句说明，告诉用户里面是什么、需不需要动
    expect(screen.getByText(/召回数量与版本范围一般无需调整/)).toBeInTheDocument()
  })

  it('⛔ 折叠不是删除：租户标识 / 召回数量 / 版本范围仍在 DOM 里', async () => {
    renderPage()
    await screen.findByLabelText('你的问题')

    expect(screen.getByLabelText('租户标识')).toBeInTheDocument()
    expect(screen.getByLabelText(/召回数量/)).toBeInTheDocument()
    expect(screen.getByLabelText('版本范围')).toBeInTheDocument()
    // 面向师生的租户说明不得因为折叠而消失
    expect(screen.getByText(/留空将无法检索/)).toBeInTheDocument()

    // ⚠️ 租户标识必须在折叠区**内**（它决定检索落在哪个租户的语料上）
    const details = document.querySelector('details')
    expect(details?.contains(screen.getByLabelText('租户标识'))).toBe(true)
  })
})
