// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'

import { RagMetadataConfirmDrawer } from '@/pages/rag/RagMetadataConfirmDrawer'
import type { RagSource } from '@/api/ragTypes'

/**
 * 元数据确认抽屉（交互文档 §4.3 / §10.2 防退化断言）。
 *
 * ⚠️ 守的是「推断值必须可辨认、且人一改就不再冒充推断」：
 *    · 推出来的字段打 ✨；
 *    · 人改过之后 ✨ 消失（否则界面在替人背书 saying「这是系统说的」）；
 *    · 确认 ≠ 可检索，文案必须说清。
 */

const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/ragClient', async (importActual) => {
  const actual = await importActual<typeof import('@/api/ragClient')>()
  return { ...actual, confirmRagSourceMetadata: confirmMock }
})

const TARGET = { scope: 'TENANT' as const, tenantId: 't-1' }

function sampleSource(overrides: Partial<RagSource> = {}): RagSource {
  return {
    sourceId: 's-1',
    sourceType: 'TEXTBOOK',
    title: '人教版八年级上册数学',
    publisher: '人民教育出版社',
    isbn: null,
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
    createdAt: '2026-11-03T00:00:00Z',
    updatedAt: '2026-11-03T00:00:00Z',
    ...overrides,
  }
}

function renderDrawer(opts: {
  source?: RagSource | null
  fileName?: string | null
  readOnly?: boolean
} = {}): void {
  render(
    <App>
      <RagMetadataConfirmDrawer
        source={opts.source === undefined ? sampleSource() : opts.source}
        target={TARGET}
        fileName={opts.fileName ?? '沪教版英语六年级上册.pdf'}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
        readOnly={opts.readOnly ?? false}
      />
    </App>,
  )
}

beforeEach(() => {
  confirmMock.mockReset()
  confirmMock.mockResolvedValue({
    sourceId: 's-1',
    metadataConfirmed: true,
    metadataConfirmedAt: '2026-11-03T00:00:00Z',
    metadataConfirmedBy: 'u-1',
  })
})

describe('元数据确认抽屉', () => {
  it('推断出的字段带 ✨ 标记', async () => {
    renderDrawer()

    // 推断来自文件名：出版社/学科/年级/册次都能推出来
    await waitFor(() => {
      expect(screen.getAllByText('✨ 系统推断').length).toBeGreaterThan(0)
    })
  })

  it('人改过字段后，该字段的 ✨ 消失', async () => {
    renderDrawer()

    const titleInput = await screen.findByPlaceholderText('如：沪教版英语六年级上册')
    const before = screen.queryAllByText('✨ 系统推断').length
    expect(before).toBeGreaterThan(0)

    fireEvent.change(titleInput, { target: { value: '我改过的书名' } })

    await waitFor(() => {
      expect(screen.queryAllByText('✨ 系统推断').length).toBeLessThan(before)
    })
  })

  it('⚠️ 文案说明「确认 ≠ 可检索」', async () => {
    renderDrawer()
    expect(await screen.findByText('确认后仍不可检索')).toBeInTheDocument()
    expect(screen.getByText(/抽检与双人复核/)).toBeInTheDocument()
  })

  it('提交时把推断出的元数据作为快照发出', async () => {
    // ⚠️ 来源里出版社留空，才轮到文件名推断出「上海教育出版社」——
    //    已有值优先于推断是另一条用例要守的行为。
    renderDrawer({ source: sampleSource({ publisher: '' }) })

    await screen.findByPlaceholderText('如：沪教版英语六年级上册')
    fireEvent.click(screen.getByRole('button', { name: '确认元数据' }))

    await waitFor(() => {
      expect(confirmMock).toHaveBeenCalledTimes(1)
    })
    const payload = confirmMock.mock.calls[0]![2]
    expect(payload.title).toBeTruthy()
    expect(payload.publisher).toBe('上海教育出版社')
    expect(payload.term).toBe('上册')
  })

  it('书名清空时不允许提交（确认一份没有书名的元数据没有意义）', async () => {
    renderDrawer()

    const titleInput = await screen.findByPlaceholderText('如：沪教版英语六年级上册')
    fireEvent.change(titleInput, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: '确认元数据' }))

    // 表单校验拦住，不发请求
    await waitFor(() => {
      expect(screen.getByText('请填写书名')).toBeInTheDocument()
    })
    expect(confirmMock).not.toHaveBeenCalled()
  })

  it('已有来源的出版社优先于推断值（已入库的事实不能被推断覆盖）', async () => {
    // 来源里已是「北京师范大学出版社」，文件名推断出「上海教育出版社」
    renderDrawer({ source: sampleSource({ publisher: '北京师范大学出版社' }) })

    await screen.findByPlaceholderText('如：上海教育出版社')
    fireEvent.click(screen.getByRole('button', { name: '确认元数据' }))

    await waitFor(() => expect(confirmMock).toHaveBeenCalledTimes(1))
    expect(confirmMock.mock.calls[0]![2].publisher).toBe('北京师范大学出版社')
  })

  it('已确认（只读）时不给提交按钮', async () => {
    renderDrawer({ readOnly: true })

    expect(await screen.findByText('这本书的元数据已确认')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认元数据' })).toBeNull()
  })
})
