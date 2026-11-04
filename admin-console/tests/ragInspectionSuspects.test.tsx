// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'

import { RagInspectionPanel } from '@/pages/rag/RagInspectionPanel'
import type { RagDocument, RagSource } from '@/api/ragTypes'

/**
 * 抽检「看红灯」视图（交互文档 §4.6 / §10.2 防退化断言）。
 *
 * ⚠️ 守三件事：
 *    · 默认进入「只看疑点」，而不是让人翻 159 页裁决 1463 条；
 *    · 疑点卡片**必须展示正文**——判「是不是答案」必须看到字；
 *    · 「查看全部片段」入口**必须保留**，否则会造成「没被标出=没问题」的错觉。
 */

const listSuspectsMock = vi.hoisted(() => vi.fn())
const listDocsMock = vi.hoisted(() => vi.fn())
const listChunksMock = vi.hoisted(() => vi.fn())
const listInspectionsMock = vi.hoisted(() => vi.fn())
const submitInspectionMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/ragClient', async (importActual) => {
  const actual = await importActual<typeof import('@/api/ragClient')>()
  return {
    ...actual,
    listRagInspectionSuspects: listSuspectsMock,
    listRagDocuments: listDocsMock,
    listRagChunks: listChunksMock,
    listRagInspections: listInspectionsMock,
    submitRagInspection: submitInspectionMock,
  }
})

const TARGET = { scope: 'TENANT' as const, tenantId: 't-1' }

function sampleSource(): RagSource {
  return {
    sourceId: 's-1',
    sourceType: 'TEXTBOOK',
    title: '沪教版英语六年级上册',
    publisher: '上海教育出版社',
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
  }
}

function sampleDoc(): RagDocument {
  return {
    documentId: 'd-1',
    sourceId: 's-1',
    editionId: 'e-1',
    carrier: 'PDF',
    title: '沪教版英语六年级上册.pdf',
    pageCount: 168,
    objectRef: null,
    parseStatus: 'CHUNKED',
    parserVersion: null,
    contentHash: null,
    chunkCount: 1463,
    createdAt: '2026-11-03T00:00:00Z',
    updatedAt: '2026-11-03T00:00:00Z',
  }
}

function renderPanel(): void {
  render(
    <App>
      <RagInspectionPanel target={TARGET} source={sampleSource()} />
    </App>,
  )
}

beforeEach(() => {
  listSuspectsMock.mockReset()
  listDocsMock.mockReset()
  listChunksMock.mockReset()
  listInspectionsMock.mockReset()

  listDocsMock.mockResolvedValue([sampleDoc()])
  listChunksMock.mockResolvedValue([])
  listInspectionsMock.mockResolvedValue([])
  listSuspectsMock.mockResolvedValue([])
})

describe('抽检「看红灯」视图', () => {
  it('默认就是「只看疑点」，而不是「全部片段」', async () => {
    renderPanel()

    // 视图切换器存在，且默认停在疑点档
    const suspectsTab = await screen.findByRole('radio', { name: /只看疑点/ })
    expect(suspectsTab).toBeChecked()
  })

  it('疑点卡片展示正文原文（判「是不是答案」必须看到字）', async () => {
    listSuspectsMock.mockResolvedValue([
      {
        chunkId: 'c-1',
        documentId: 'd-1',
        role: 'EXPLANATION',
        text: '参考答案：1-5 B A C D A',
        pageNumber: 160,
        sectionPath: 'Appendix',
        exerciseNo: null,
        reason: 'SUSPECT_ANSWER_MISSED',
        reasonText: '疑似答案区被判成正文（漏判即泄题）',
        severity: 'HIGH',
      },
    ])
    renderPanel()

    // 正文可见（不是只有角色/页码的摘要行）
    expect(await screen.findByText(/参考答案：1-5 B A C D A/)).toBeInTheDocument()
    // 原因可读（⚠️ 同一文案会出现两次：卡片标题 + 面板说明，用 getAllByText）
    expect(screen.getAllByText(/疑似答案区被判成正文/).length).toBeGreaterThan(0)
  })

  it('⚠️ 保留「查看全部片段」入口（防止「没被标出=没问题」的错觉）', async () => {
    renderPanel()

    expect(await screen.findByRole('radio', { name: '查看全部片段' })).toBeInTheDocument()
  })

  it('没有疑点时明确提示仍需抽查', async () => {
    listSuspectsMock.mockResolvedValue([])
    renderPanel()

    await waitFor(() => {
      expect(screen.getByText(/没有发现疑点/)).toBeInTheDocument()
    })
    // ⚠️ 文案必须提醒「仍需抽看」，否则人会以为这册肯定没问题
    expect(screen.getAllByText(/查看全部片段/).length).toBeGreaterThan(0)
  })

  it('预筛接口失败时降级，不挡死整条抽检链路', async () => {
    listSuspectsMock.mockRejectedValue(new Error('boom'))
    renderPanel()

    // 面板仍然可用：视图切换器还在，没有被一个错误页取代
    await waitFor(() => {
      expect(screen.getByText(/系统已按已知高风险模式/)).toBeInTheDocument()
    })
    expect(screen.getByRole('radio', { name: '查看全部片段' })).toBeInTheDocument()
  })

  it('高严重度疑点标注为「高」（可能泄题，优先级最高）', async () => {
    listSuspectsMock.mockResolvedValue([
      {
        chunkId: 'c-1',
        documentId: 'd-1',
        role: 'EXPLANATION',
        text: '答案：B A C',
        pageNumber: 88,
        sectionPath: 'Unit 6',
        exerciseNo: null,
        reason: 'SUSPECT_ANSWER_MISSED',
        reasonText: '疑似答案区被判成正文（漏判即泄题）',
        severity: 'HIGH',
      },
    ])
    renderPanel()

    expect(await screen.findByText('高')).toBeInTheDocument()
  })
})
