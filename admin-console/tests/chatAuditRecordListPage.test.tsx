// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

const listMock = vi.fn()
const detailMock = vi.fn()

vi.mock('@/api/chatAuditRecordApi', async () => {
  const actual = await vi.importActual<typeof import('@/api/chatAuditRecordApi')>(
    '@/api/chatAuditRecordApi',
  )
  return {
    ...actual,
    listChatAuditRecords: (...args: unknown[]) => listMock(...args),
    getChatAuditRecordDetail: (...args: unknown[]) => detailMock(...args),
  }
})

import { ChatAuditRecordListPage } from '@/pages/audit/ChatAuditRecordListPage'
import type { ChatAuditRecordSummary } from '@/api/chatAuditRecordApi'

function row(overrides: Partial<ChatAuditRecordSummary> = {}): ChatAuditRecordSummary {
  return {
    auditId: '11111111-1111-1111-1111-111111111111',
    traceId: 'trace-abc',
    source: 'RECORD',
    tenantId: '22222222-2222-2222-2222-222222222222',
    domain: 'TENANT',
    principalId: 'u-1',
    endpoint: '/api/agent/chat/stream',
    startedAt: '2026-11-18T10:00:00Z',
    completedAt: '2026-11-18T10:00:01Z',
    durationMs: 1000,
    terminalState: 'SUCCEEDED',
    httpMethod: 'POST',
    httpStatus: 200,
    messagePreview: '我的孩子数学很差',
    rejectionReason: null,
    ...overrides,
  }
}

function renderPage() {
  return render(
    <MemoryRouter>
      <AntApp>
        <ChatAuditRecordListPage />
      </AntApp>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  listMock.mockReset()
  detailMock.mockReset()
  listMock.mockResolvedValue({ items: [], nextCursor: null })
  detailMock.mockResolvedValue({})
})

describe('对话审计记录查询页', () => {
  it('渲染筛选与表格；空列表给出结论性空态（非加载中）', async () => {
    renderPage()
    expect(screen.getByText('对话审计记录')).toBeTruthy()
    expect(screen.getByPlaceholderText('端点（精确）')).toBeTruthy()
    await waitFor(() => expect(listMock).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByText('加载中…')).toBeNull())
  })

  it('列表回显摘要列；点行按 source+auditId 取详情并渲染完整回答', async () => {
    listMock.mockResolvedValue({ items: [row()], nextCursor: null })
    detailMock.mockResolvedValue({
      source: 'RECORD',
      auditId: row().auditId,
      traceId: 'trace-abc',
      tenantId: row().tenantId,
      domain: 'TENANT',
      principalId: 'u-1',
      endpoint: row().endpoint,
      startedAt: row().startedAt,
      completedAt: row().completedAt,
      durationMs: 1000,
      terminalState: 'SUCCEEDED',
      httpMethod: 'POST',
      httpStatus: 200,
      configVersion: 1,
      rejectionReason: null,
      payload: {
        request: { message: '我的孩子数学很差', history: [], modelMessages: [] },
        result: { finalAnswer: '完整回答在此', sseFrameCount: 3 },
        events: [],
        chunks: [],
      },
    })

    renderPage()
    await waitFor(() => expect(screen.getByText('我的孩子数学很差')).toBeTruthy())

    fireEvent.click(screen.getByText('我的孩子数学很差'))
    await waitFor(() => expect(detailMock).toHaveBeenCalledWith('RECORD', row().auditId))
    await waitFor(() => expect(screen.getByText('完整回答在此')).toBeTruthy())
  })

  it('建连失败行按 UNATTRIBUTED 取详情，展示拒绝元数据', async () => {
    const rejected = row({
      auditId: '33333333-3333-3333-3333-333333333333',
      source: 'UNATTRIBUTED',
      domain: null,
      tenantId: null,
      terminalState: 'REJECTED',
      messagePreview: null,
      rejectionReason: 'UNAUTHENTICATED',
    })
    listMock.mockResolvedValue({ items: [rejected], nextCursor: null })
    detailMock.mockResolvedValue({
      source: 'UNATTRIBUTED',
      auditId: rejected.auditId,
      traceId: 'trace-xyz',
      tenantId: null,
      domain: null,
      principalId: null,
      endpoint: rejected.endpoint,
      startedAt: rejected.startedAt,
      completedAt: null,
      durationMs: 0,
      terminalState: 'REJECTED',
      httpMethod: 'POST',
      httpStatus: 401,
      configVersion: 0,
      rejectionReason: 'UNAUTHENTICATED',
      payload: { uri: '/api/agent/chat/stream', queryKeys: [] },
    })

    renderPage()
    await waitFor(() => expect(screen.getByText('UNAUTHENTICATED')).toBeTruthy())
    fireEvent.click(screen.getByText('UNAUTHENTICATED'))
    await waitFor(() =>
      expect(detailMock).toHaveBeenCalledWith('UNATTRIBUTED', rejected.auditId),
    )
    await waitFor(() =>
      expect(screen.getByText('建连前失败：只存结构性元数据，不含正文或凭据')).toBeTruthy(),
    )
  })

  it('加载失败：显示错误告警，不伪造空态结论', async () => {
    listMock.mockRejectedValue(new Error('平台运维通道未接入'))
    renderPage()
    await waitFor(() => expect(screen.getByText('加载失败')).toBeTruthy())
    expect(screen.getByText('平台运维通道未接入')).toBeTruthy()
  })

  it('翻页失败只显示分页附近的错误，不污染首屏全局 error', async () => {
    listMock
      .mockResolvedValueOnce({ items: [row()], nextCursor: 'cur-1' })
      .mockRejectedValueOnce(new Error('翻页游标已失效'))

    renderPage()
    await waitFor(() => expect(screen.getByText('我的孩子数学很差')).toBeTruthy())
    expect(screen.queryByText('加载失败')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    await waitFor(() => expect(screen.getByText('翻页游标已失效')).toBeTruthy())
    expect(screen.getByText('加载更多失败')).toBeTruthy()
    // ⛔ 翻页失败不得升级为首屏级「加载失败」横幅
    expect(screen.queryByText('加载失败')).toBeNull()
  })
})
