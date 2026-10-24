/**
 * RAG 维护面板行为测试（统计下钻 / 召回测试 / 词表口径差异）
 *
 * ⚠️ 本文件补的是**运维侧**能力的行为断言。
 *    此前语料管理页只回答「库里有什么、哪些还没审、能不能放行」，
 *    缺三块运维视角：
 *    · 分布下钻 —— 统计只有总数，看不出问题集中在哪一类；
 *    · 召回测试 —— 「已通过校验」≠「真能被召回」（RAG-D10 L-1）；
 *    · 词表口径 —— platform 与 RAG 各一份词表，差异会静默漏召回。
 *
 * ⛔ 反证纪律：每条断言都要能在「实现被改坏」时转红。
 *    例如「未知枚举名不被吞掉」——若实现改成 fallback 成空串，这条会红。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  ChunkRoleDistribution,
  DocumentEntry,
  MaintenanceIssueBoard,
  ParseStatusDistribution,
  RetrievalProbe,
  SourceStatusDistribution,
  VocabularyDrift,
} from '@/pages/rag/RagMaintenancePanels'

const retrieveChunksMock = vi.hoisted(() => vi.fn())
const getDictionariesMock = vi.hoisted(() => vi.fn())
const getRagIssuesMock = vi.hoisted(() => vi.fn())
const reindexRagCorpusMock = vi.hoisted(() => vi.fn())
const applyRagDocumentVerificationMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    retrieveChunks: retrieveChunksMock,
    getDictionaries: getDictionariesMock,
    getRagIssues: getRagIssuesMock,
    reindexRagCorpus: reindexRagCorpusMock,
    applyRagDocumentVerification: applyRagDocumentVerificationMock,
  }
})

const TENANT = '11111111-1111-1111-1111-111111111111'

beforeEach(() => {
  retrieveChunksMock.mockReset()
  getDictionariesMock.mockReset()
  getRagIssuesMock.mockReset()
  reindexRagCorpusMock.mockReset()
  applyRagDocumentVerificationMock.mockReset()
  getDictionariesMock.mockResolvedValue({ subjects: ['数学', '物理'], grades: ['七年级'] })
})

/* ── A：统计分布下钻 ───────────────────────────────────── */

describe('统计分布下钻', () => {
  it('片段角色分布应把英文枚举名翻成中文', () => {
    render(<ChunkRoleDistribution entries={{ EXPLANATION: 80, EXERCISE_ANSWER: 20 }} />)
    expect(screen.getByText('正文讲解')).toBeInTheDocument()
    expect(screen.getByText('习题答案')).toBeInTheDocument()
    // ⛔ 不应把原始枚举名直接甩给运维
    expect(screen.queryByText('EXPLANATION')).not.toBeInTheDocument()
  })

  it('应显示计数与占比', () => {
    render(<ChunkRoleDistribution entries={{ EXPLANATION: 75, EXAMPLE: 25 }} />)
    // 75/100 → 75%
    expect(screen.getByText('75（75%）')).toBeInTheDocument()
    expect(screen.getByText('25（25%）')).toBeInTheDocument()
  })

  it('⛔ 未知枚举名不得被吞掉（应回显原值，不能静默消失）', () => {
    render(<ChunkRoleDistribution entries={{ BRAND_NEW_ROLE: 3 }} />)
    expect(screen.getByText('BRAND_NEW_ROLE')).toBeInTheDocument()
    expect(screen.getByText('3（100%）')).toBeInTheDocument()
  })

  it('来源状态分布应使用校验状态标签', () => {
    render(<SourceStatusDistribution entries={{ VERIFIED: 3, PENDING: 2 }} />)
    expect(screen.getByText('已通过')).toBeInTheDocument()
    expect(screen.getByText('待校验')).toBeInTheDocument()
  })

  it('载体解析状态分布应使用解析状态标签', () => {
    render(<ParseStatusDistribution entries={{ CHUNKED: 4, FAILED: 1 }} />)
    expect(screen.getByText('已切分')).toBeInTheDocument()
    expect(screen.getByText('解析失败')).toBeInTheDocument()
  })

  it('空分布应给出诚实空态，而不是空白面板', () => {
    render(<ParseStatusDistribution entries={{}} />)
    expect(screen.getByText(/还没有载体/)).toBeInTheDocument()
  })
})

/* ── D-1：召回测试 ─────────────────────────────────────── */

describe('召回测试', () => {
  it('⛔ 未填问题时不得发起检索', async () => {
    const user = userEvent.setup()
    render(<RetrievalProbe tenantId={TENANT} />)
    await user.click(screen.getByRole('button', { name: '开始召回测试' }))
    expect(retrieveChunksMock).not.toHaveBeenCalled()
  })

  it('⛔ 缺租户时不得发起检索（缺头后端会 400）', async () => {
    const user = userEvent.setup()
    render(<RetrievalProbe tenantId="" />)
    await user.type(screen.getByLabelText('测试问题'), '二次函数的顶点')
    await user.click(screen.getByRole('button', { name: '开始召回测试' }))
    expect(retrieveChunksMock).not.toHaveBeenCalled()
  })

  it('应调检索端点并带上租户与版本坐标', async () => {
    const user = userEvent.setup()
    retrieveChunksMock.mockResolvedValue({
      hits: [],
      versionMismatchDetected: false,
      answerContentSuppressed: true,
      primaryEdition: '数学 / 七年级 / 上册 / 人教版',
    })
    render(<RetrievalProbe tenantId={TENANT} />)
    await user.type(screen.getByLabelText('测试问题'), '二次函数的顶点')
    await user.click(screen.getByRole('button', { name: '开始召回测试' }))

    await waitFor(() => {
      expect(retrieveChunksMock).toHaveBeenCalled()
    })
    const [payload, , tenantArg] = retrieveChunksMock.mock.calls[0] as [
      { query: string; edition: { subject: string }; topK: number },
      number | undefined,
      string | undefined,
    ]
    expect(payload.query).toBe('二次函数的顶点')
    expect(payload.edition.subject).toBe('数学')
    expect(tenantArg).toBe(TENANT)
  })

  it('召回到 0 条是合法空态，⛔ 不得呈现为故障', async () => {
    const user = userEvent.setup()
    retrieveChunksMock.mockResolvedValue({
      hits: [],
      versionMismatchDetected: false,
      answerContentSuppressed: true,
      primaryEdition: '数学',
    })
    render(<RetrievalProbe tenantId={TENANT} />)
    await user.type(screen.getByLabelText('测试问题'), '二次函数')
    await user.click(screen.getByRole('button', { name: '开始召回测试' }))

    await waitFor(() => {
      expect(screen.getByText('召回到 0 条')).toBeInTheDocument()
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('命中应渲染片段角色与得分', async () => {
    const user = userEvent.setup()
    retrieveChunksMock.mockResolvedValue({
      hits: [
        {
          chunkId: 'c-1',
          text: '二次函数的顶点式为 y=a(x-h)^2+k',
          role: 'EXPLANATION',
          score: 0.8123,
        },
      ],
      versionMismatchDetected: false,
      answerContentSuppressed: true,
      primaryEdition: '数学',
    })
    render(<RetrievalProbe tenantId={TENANT} />)
    await user.type(screen.getByLabelText('测试问题'), '二次函数')
    await user.click(screen.getByRole('button', { name: '开始召回测试' }))

    await waitFor(() => {
      expect(screen.getByText('正文讲解')).toBeInTheDocument()
    })
    expect(screen.getByText(/二次函数的顶点式/)).toBeInTheDocument()
    // ⚠️ 标签写「相关性分」而不是「得分」：后端返回的是通道相关性
    //    （余弦相似度 / ts_rank），不是语义质量分，也不是 RRF 排名分。
    expect(screen.getByText('相关性分 0.812')).toBeInTheDocument()
  })

  it('版本不匹配必须显式提示（引用前须核对）', async () => {
    const user = userEvent.setup()
    retrieveChunksMock.mockResolvedValue({
      hits: [
        { chunkId: 'c-1', text: '片段正文', role: 'EXPLANATION', score: 0.5 },
      ],
      versionMismatchDetected: true,
      answerContentSuppressed: true,
      primaryEdition: '数学',
    })
    render(<RetrievalProbe tenantId={TENANT} />)
    await user.type(screen.getByLabelText('测试问题'), '二次函数')
    await user.click(screen.getByRole('button', { name: '开始召回测试' }))

    await waitFor(() => {
      expect(screen.getByText('版本不匹配')).toBeInTheDocument()
    })
  })

  it('检索失败应给出可执行的下一步，而不是「出错了」', async () => {
    const user = userEvent.setup()
    retrieveChunksMock.mockRejectedValue(new Error('boom'))
    render(<RetrievalProbe tenantId={TENANT} />)
    await user.type(screen.getByLabelText('测试问题'), '二次函数')
    await user.click(screen.getByRole('button', { name: '开始召回测试' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByText(/版本坐标与语料对不上/)).toBeInTheDocument()
  })
})

/* ── D-2：词表口径差异 ─────────────────────────────────── */

describe('词表口径差异', () => {
  it('⛔ 挂载时不得自动打平台端点（跨服务请求应由人触发）', () => {
    render(<VocabularyDrift />)
    expect(getDictionariesMock).not.toHaveBeenCalled()
  })

  it('点击比对后才调用平台词表端点', async () => {
    const user = userEvent.setup()
    render(<VocabularyDrift />)
    await user.click(screen.getByRole('button', { name: '比对词表口径' }))

    await waitFor(() => {
      expect(getDictionariesMock).toHaveBeenCalled()
    })
  })

  it('应呈现两侧差集', async () => {
    const user = userEvent.setup()
    getDictionariesMock.mockResolvedValue({
      subjects: ['数学', '体育'],
      grades: ['七年级'],
    })
    render(<VocabularyDrift />)
    await user.click(screen.getByRole('button', { name: '比对词表口径' }))

    await waitFor(() => {
      expect(screen.getAllByText('仅 RAG 侧有').length).toBeGreaterThan(0)
    })
    // 「体育」只在 platform 侧 → 应出现在「仅 platform 有」里
    expect(screen.getAllByText(/体育/).length).toBeGreaterThan(0)
  })

  it('⛔ 只呈现不修改：不得提供词表编辑入口', async () => {
    const user = userEvent.setup()
    render(<VocabularyDrift />)
    await user.click(screen.getByRole('button', { name: '比对词表口径' }))

    await waitFor(() => {
      expect(screen.getByText('为什么只呈现不修改')).toBeInTheDocument()
    })
    // 不得出现任何可编辑的词表控件
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('平台端点不可用时应给出可执行的下一步', async () => {
    const user = userEvent.setup()
    getDictionariesMock.mockRejectedValue(new Error('boom'))
    render(<VocabularyDrift />)
    await user.click(screen.getByRole('button', { name: '比对词表口径' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    // ⚠️ getAllByText：错误原因与「下一步」提示都提到了该端点（两处都要出现）
    expect(screen.getAllByText(/\/api\/reference/).length).toBeGreaterThan(0)
  })
})

/* ── B-1 / B-2：维护问题清单 + 重跑索引 ──────────────────── */

const ISSUES = {
  documentsMissingEmbedding: [
    {
      documentId: 'd-1',
      sourceId: 's-1',
      title: '八年级上册电子版',
      carrier: 'PDF',
      parseStatus: 'CHUNKED',
      chunkCount: 30,
      missingEmbeddingCount: 12,
    },
  ],
  documentsFailedParse: [
    {
      documentId: 'd-2',
      sourceId: 's-1',
      title: '第三方扫描版',
      carrier: 'SCAN',
      parseStatus: 'FAILED',
      chunkCount: 0,
    },
  ],
  sourcesPendingReview: [
    {
      sourceId: 's-2',
      title: '九年级上册数学',
      verificationStatus: 'PENDING',
      stale: false,
      trustTier: 'UNVERIFIED',
    },
  ],
  totals: {
    documentsMissingEmbedding: 1,
    documentsFailedParse: 1,
    sourcesPendingReview: 1,
  },
}

describe('维护问题清单与重跑索引', () => {
  it('⛔ 挂载时不得自动拉清单（诊断是显式动作）', () => {
    render(<MaintenanceIssueBoard tenantId={TENANT} />)
    expect(getRagIssuesMock).not.toHaveBeenCalled()
  })

  it('点击后才拉清单，并呈现三类问题', async () => {
    const user = userEvent.setup()
    getRagIssuesMock.mockResolvedValue(ISSUES)
    render(<MaintenanceIssueBoard tenantId={TENANT} />)
    await user.click(screen.getByRole('button', { name: '检查问题清单' }))

    await waitFor(() => {
      expect(getRagIssuesMock).toHaveBeenCalled()
    })
    expect(screen.getByText(/缺向量的载体（1）/)).toBeInTheDocument()
    expect(screen.getByText(/解析失败的载体（1）/)).toBeInTheDocument()
    expect(screen.getByText(/待审核的来源（1）/)).toBeInTheDocument()
    // 具体是哪几个载体 —— 统计给不了，只有清单能给
    expect(screen.getByText('八年级上册电子版')).toBeInTheDocument()
    expect(screen.getByText('第三方扫描版')).toBeInTheDocument()
  })

  it('清单全空时应给出诚实空态', async () => {
    const user = userEvent.setup()
    getRagIssuesMock.mockResolvedValue({
      documentsMissingEmbedding: [],
      documentsFailedParse: [],
      sourcesPendingReview: [],
      totals: {
        documentsMissingEmbedding: 0,
        documentsFailedParse: 0,
        sourcesPendingReview: 0,
      },
    })
    render(<MaintenanceIssueBoard tenantId={TENANT} />)
    await user.click(screen.getByRole('button', { name: '检查问题清单' }))

    await waitFor(() => {
      expect(screen.getByText('没有发现待处理的问题')).toBeInTheDocument()
    })
  })

  it('重跑索引应调端点并如实呈现处理数', async () => {
    const user = userEvent.setup()
    getRagIssuesMock.mockResolvedValue(ISSUES)
    reindexRagCorpusMock.mockResolvedValue({
      indexedChunks: 12,
      scannedChunks: 12,
      durationMs: 340,
    })
    render(<MaintenanceIssueBoard tenantId={TENANT} />)
    await user.click(screen.getByRole('button', { name: '检查问题清单' }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '重跑索引' })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: '重跑索引' }))

    await waitFor(() => {
      expect(reindexRagCorpusMock).toHaveBeenCalled()
    })
    expect(await screen.findByText(/处理 12 个/)).toBeInTheDocument()
  })

  it('⛔ 重跑索引的文案必须说清它不做什么（不解析/不切分/不审核）', async () => {
    const user = userEvent.setup()
    getRagIssuesMock.mockResolvedValue(ISSUES)
    render(<MaintenanceIssueBoard tenantId={TENANT} />)
    await user.click(screen.getByRole('button', { name: '检查问题清单' }))

    await waitFor(() => {
      expect(screen.getByText(/只为/)).toBeInTheDocument()
    })
    // ⚠️ 用 textContent 断言：文案里的「不」被 <b> 包裹，跨元素匹配会落空
    expect(screen.getByText(/只为/).textContent).toContain('不解析、不切分、不审核语料')
  })
})

/* ── C：载体级审核与下架 ───────────────────────────────── */

const DOC = {
  documentId: 'd-1',
  sourceId: 's-1',
  editionId: 'e-1',
  carrier: 'PDF',
  title: '八年级上册电子版',
  pageCount: 120,
  objectRef: null,
  parseStatus: 'CHUNKED',
  parserVersion: null,
  contentHash: null,
  chunkCount: 30,
  verificationStatus: 'PENDING' as const,
  retrievable: false,
  revokedAt: null,
  revokedReason: null,
  licenseStatus: 'PENDING' as const,
  createdAt: '2026-10-22T00:00:00Z',
  updatedAt: '2026-10-22T00:00:00Z',
}

describe('载体级审核与下架', () => {
  it('应呈现载体状态徽章（校验状态 + 可检索标记）', () => {
    render(
      <DocumentEntry
        document={DOC}
        tenantId={TENANT}
        sourceStatus="VERIFIED"
        onChanged={() => {}}
      />,
    )
    expect(screen.getByText('待校验')).toBeInTheDocument()
    // ⚠️ 措辞是「标记」而非「可检索/不可检索」——
    //    检索路径尚未过滤载体级 retrievable（RAG-R01 缺口），
    //    说成「不可检索」会让人误以为学生端一定搜不到。
    expect(screen.getByText('可检索标记：关')).toBeInTheDocument()
  })

  it('⛔ 来源未通过校验时，不得提供「已通过」（载体不得高于来源）', async () => {
    const user = userEvent.setup()
    render(
      <DocumentEntry
        document={DOC}
        tenantId={TENANT}
        sourceStatus="PENDING"
        onChanged={() => {}}
      />,
    )
    await user.click(screen.getByRole('button', { name: '载体审核' }))
    const select = screen.getByLabelText('下一步') as HTMLSelectElement
    expect(Array.from(select.options).map((o) => o.value)).not.toContain('VERIFIED')
  })

  it('来源已通过校验时，「已通过」应当可用', async () => {
    const user = userEvent.setup()
    render(
      <DocumentEntry
        document={DOC}
        tenantId={TENANT}
        sourceStatus="VERIFIED"
        onChanged={() => {}}
      />,
    )
    await user.click(screen.getByRole('button', { name: '载体审核' }))
    // 载体当前 PENDING → 合法目标是 IN_REVIEW / REVOKED（VERIFIED 需要先 IN_REVIEW）
    const select = screen.getByLabelText('下一步') as HTMLSelectElement
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['IN_REVIEW', 'REVOKED'])
  })

  it('⛔ 未填依据与决定人时不得提交（判定必须可追溯到人）', async () => {
    const user = userEvent.setup()
    render(
      <DocumentEntry
        document={DOC}
        tenantId={TENANT}
        sourceStatus="VERIFIED"
        onChanged={() => {}}
      />,
    )
    await user.click(screen.getByRole('button', { name: '载体审核' }))
    await user.type(screen.getByLabelText('校验依据（必填）'), '扫描件复核通过')
    // 只填依据、不填决定人 → 仍不得提交
    expect(screen.getByRole('button', { name: '提交载体判定' })).toBeDisabled()

    await user.type(screen.getByLabelText('决定人（UUID，必填）'), '33333333-3333-3333-3333-333333333333')
    expect(screen.getByRole('button', { name: '提交载体判定' })).not.toBeDisabled()
  })

  it('提交应携带载体 ID、依据与决定人', async () => {
    const user = userEvent.setup()
    applyRagDocumentVerificationMock.mockResolvedValue({
      ...DOC,
      verificationStatus: 'IN_REVIEW',
      retrievable: false,
    })
    render(
      <DocumentEntry
        document={DOC}
        tenantId={TENANT}
        sourceStatus="VERIFIED"
        onChanged={() => {}}
      />,
    )
    await user.click(screen.getByRole('button', { name: '载体审核' }))
    await user.type(screen.getByLabelText('校验依据（必填）'), '扫描件复核通过')
    await user.type(screen.getByLabelText('决定人（UUID，必填）'), '33333333-3333-3333-3333-333333333333')
    await user.click(screen.getByRole('button', { name: '提交载体判定' }))

    await waitFor(() => {
      expect(applyRagDocumentVerificationMock).toHaveBeenCalled()
    })
    const [tenantArg, docId, payload] = applyRagDocumentVerificationMock.mock.calls[0] as [
      string,
      string,
      { targetStatus: string; evidenceRef: string; decidedBy: string },
    ]
    expect(tenantArg).toBe(TENANT)
    expect(docId).toBe('d-1')
    expect(payload.targetStatus).toBe('IN_REVIEW')
    expect(payload.evidenceRef).toBe('扫描件复核通过')
    expect(payload.decidedBy).toBe('33333333-3333-3333-3333-333333333333')
  })

  it('⛔ 409 应呈现为「这一步不被允许」，⛔ 不得提示「刷新后重试」', async () => {
    const user = userEvent.setup()
    const { ApiError } = await import('@/api/client')
    const { normalizeHttpError } = await import('@/api/errors')
    applyRagDocumentVerificationMock.mockRejectedValue(
      new ApiError(normalizeHttpError(409, { error: 'conflict' })),
    )
    render(
      <DocumentEntry
        document={DOC}
        tenantId={TENANT}
        sourceStatus="VERIFIED"
        onChanged={() => {}}
      />,
    )
    await user.click(screen.getByRole('button', { name: '载体审核' }))
    await user.type(screen.getByLabelText('校验依据（必填）'), '扫描件复核通过')
    await user.type(screen.getByLabelText('决定人（UUID，必填）'), '33333333-3333-3333-3333-333333333333')
    await user.click(screen.getByRole('button', { name: '提交载体判定' }))

    await waitFor(() => {
      expect(screen.getByText('这一步不被允许')).toBeInTheDocument()
    })
    expect(screen.queryByText(/刷新后重试/)).not.toBeInTheDocument()
  })

  it('⛔ 已撤回的载体是终态：不提供任何流转入口', () => {
    render(
      <DocumentEntry
        document={{ ...DOC, verificationStatus: 'REVOKED', retrievable: false }}
        tenantId={TENANT}
        sourceStatus="VERIFIED"
        onChanged={() => {}}
      />,
    )
    expect(screen.queryByRole('button', { name: '载体审核' })).not.toBeInTheDocument()
    // ⚠️ getAllByText：「已撤回」同时出现在状态徽章与终态说明里
    expect(screen.getAllByText('已撤回').length).toBeGreaterThan(0)
  })
})

describe('⛔ 载体级下架的诚实边界', () => {
  it('⛔ 已撤回的载体必须说明：检索路径尚未过滤载体级标记', () => {
    render(
      <DocumentEntry
        document={{ ...DOC, verificationStatus: 'REVOKED', retrievable: false }}
        tenantId={TENANT}
        sourceStatus="VERIFIED"
        onChanged={() => {}}
      />,
    )
    expect(screen.getByText('载体级下架的诚实边界')).toBeInTheDocument()
    // 不得让管理员以为「已下架 = 学生端一定搜不到」
    expect(screen.getByText(/学生端仍可能召回它/)).toBeInTheDocument()
  })

  it('未撤回的载体不显示该边界说明（不制造噪音）', () => {
    render(
      <DocumentEntry
        document={DOC}
        tenantId={TENANT}
        sourceStatus="VERIFIED"
        onChanged={() => {}}
      />,
    )
    expect(screen.queryByText('载体级下架的诚实边界')).not.toBeInTheDocument()
  })
})

/* ── 条目排版与文案纪律（2026-10-24 UX 评审 P0-4 / P1）───── */

/**
 * ⛔ `.entry` 是 flex **行**布局：标题与描述若各自是直接子元素，
 *    描述（按「标题下方第二行」设计）会被挤到标题**右侧**；
 *    更长的教材片段正文还会把容器撑破。
 *    故两者必须同处一个纵向容器 `.entry__body`。
 */
describe('条目排版与文案纪律', () => {
  it('⛔ 清单条目的标题与描述必须在 .entry__body 里（纵向排列）', async () => {
    const user = userEvent.setup()
    getRagIssuesMock.mockResolvedValue(ISSUES)
    render(<MaintenanceIssueBoard tenantId={TENANT} />)
    await user.click(screen.getByRole('button', { name: '检查问题清单' }))

    const title = await screen.findByText('八年级上册电子版')
    const body = title.closest('.entry__body')
    expect(body).not.toBeNull()
    // 描述必须与标题同处一个容器（否则会被挤到标题右侧）
    expect(body?.textContent).toContain('缺向量')
  })

  it('⛔ 召回片段的长正文也必须在 .entry__body 里（否则撑破容器）', async () => {
    const user = userEvent.setup()
    retrieveChunksMock.mockResolvedValue({
      hits: [
        {
          chunkId: 'c-1',
          text: '二次函数的顶点式为 y=a(x-h)^2+k',
          role: 'EXPLANATION',
          score: 0.8123,
        },
      ],
      versionMismatchDetected: false,
      answerContentSuppressed: true,
      primaryEdition: '数学',
    })
    render(<RetrievalProbe tenantId={TENANT} />)
    await user.type(screen.getByLabelText('测试问题'), '二次函数')
    await user.click(screen.getByRole('button', { name: '开始召回测试' }))

    const text = await screen.findByText(/顶点式为/)
    expect(text.closest('.entry__body')).not.toBeNull()
  })

  it('⛔ 文案不得出现 markdown 标记或 emoji', async () => {
    const user = userEvent.setup()
    getRagIssuesMock.mockResolvedValue(ISSUES)
    render(<MaintenanceIssueBoard tenantId={TENANT} />)
    await user.click(screen.getByRole('button', { name: '检查问题清单' }))
    await screen.findByText(/缺向量的载体（1）/)

    expect(document.body.textContent).not.toContain('**')
    expect(document.body.textContent).not.toMatch(/⚠/)
  })
})
