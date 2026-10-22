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

  it('⛔ 空问题不提交：按钮初始禁用', async () => {
    renderPage()
    const button = screen.getByRole('button', { name: /检索并生成/ }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
  })
})
