/**
 * 治理页接线测试（FE-D10 / G8~G10）
 *
 * ⛔ 三条硬纪律：
 *   1. **禁重试**：P9 不得出现任何「重试/重放」入口（Sev-0）
 *   2. **过期不可批准**：审批台不得给过期条目提供批准按钮
 *   3. **反证**：改 mock → UI 必须跟着变
 *
 * ⚠️ 本类同时**更正**了 `pages.skeleton.test.tsx` 里的一条错误断言：
 *   原断言要求页面出现 `RECONCILIATION_REQUIRED`，
 *   但实测 `tool_operation_state` 枚举**根本没有这个值**
 *   （PENDING/IN_FLIGHT/SUCCEEDED/FAILED/UNKNOWN/
 *     RECONCILED_SUCCEEDED/RECONCILED_ABSENT/MANUAL_DISPOSED）。
 *   要求前端展示一个**数据库里不存在的状态**，
 *   等于要求前端编造——与 FE-D8 清理的「编造内容」是同一类问题。
 *   真正的区分是：`UNKNOWN`（待核对） vs 三个已核对终态
 *   （RECONCILED_SUCCEEDED / RECONCILED_ABSENT / MANUAL_DISPOSED）。
 *   该命题改由 `governance.page.test.tsx` 以真实 mock 数据断言。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { SessionProvider } from '@/auth/session'
import {
  ApprovalsPage,
  ReconciliationPage,
  AuditPage,
} from '@/pages/governance/GovernancePages'
import type { ApprovalItem, OperationItem, AuditEntry } from '@/api/types'

const listApprovalsMock = vi.hoisted(() => vi.fn())
const decideApprovalMock = vi.hoisted(() => vi.fn())
const listOperationsMock = vi.hoisted(() => vi.fn())
const disposeOperationMock = vi.hoisted(() => vi.fn())
const queryAuditMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/client', async (importActual) => {
  const actual = await importActual<typeof import('@/api/client')>()
  return {
    ...actual,
    listApprovals: listApprovalsMock,
    decideApproval: decideApprovalMock,
    listOperations: listOperationsMock,
    disposeOperation: disposeOperationMock,
    queryAudit: queryAuditMock,
  }
})

function renderPage(element: JSX.Element): void {
  render(
    <MemoryRouter>
      <SessionProvider>{element}</SessionProvider>
    </MemoryRouter>,
  )
}

function sampleApproval(overrides?: Partial<ApprovalItem>): ApprovalItem {
  return {
    approvalId: 'ap-1',
    operationId: 'op-1',
    runId: 'run-1',
    state: 'PENDING',
    requirement: 'REQUIRED',
    argumentDigest: 'sha256:abc123',
    targetResource: 'profile/student-9',
    approverScope: 'TENANT',
    expiresAt: '2026-10-21T12:00:00Z',
    approverId: null,
    decidedAt: null,
    createdAt: '2026-10-21T10:00:00Z',
    expired: false,
    ...overrides,
  }
}

function sampleOperation(overrides?: Partial<OperationItem>): OperationItem {
  return {
    operationId: 'op-1',
    runId: 'run-1',
    toolName: 'record_weakness',
    riskLevel: 'WRITE_PROFILE',
    state: 'UNKNOWN',
    createdAt: '2026-10-21T10:00:00Z',
    updatedAt: '2026-10-21T10:00:01Z',
    reconciliationSource: null,
    manuallyDisposed: false,
    ...overrides,
  }
}

function sampleAudit(overrides?: Partial<AuditEntry>): AuditEntry {
  return {
    auditId: 'au-1',
    actorId: 'approver-7',
    action: 'APPROVAL_GRANT',
    resourceType: 'approval',
    resourceId: 'ap-1',
    createdAt: '2026-10-21T10:05:00Z',
    correlationId: null,
    detail: { operationId: 'op-1' },
    ...overrides,
  }
}

beforeEach(() => {
  for (const m of [
    listApprovalsMock,
    decideApprovalMock,
    listOperationsMock,
    disposeOperationMock,
    queryAuditMock,
  ]) {
    m.mockReset()
  }
  listApprovalsMock.mockResolvedValue({ items: [], nextCursor: null })
  listOperationsMock.mockResolvedValue({ items: [], nextCursor: null })
  queryAuditMock.mockResolvedValue({ items: [], nextCursor: null })
  decideApprovalMock.mockResolvedValue({ approvalId: 'ap-1', state: 'APPROVED' })
  disposeOperationMock.mockResolvedValue({
    operationId: 'op-1',
    state: 'MANUAL_DISPOSED',
    disposedBy: 'admin-1',
    disposedAt: '2026-10-21T11:00:00Z',
  })
})

/* ── P8 审批台 ──────────────────────────────────────────── */

describe('P8 审批台（G8 接线）', () => {
  it('⛔ 连通性 + FR-014 三要素：参数摘要 / 目标资源 / 有效期', async () => {
    listApprovalsMock.mockResolvedValue({ items: [sampleApproval()], nextCursor: null })
    renderPage(<ApprovalsPage />)

    expect(await screen.findByText('sha256:abc123')).toBeTruthy()
    expect(screen.getByText('profile/student-9')).toBeTruthy()
    expect(screen.getAllByText(/有效期至/).length).toBeGreaterThan(0)
  })

  it('⛔ 过期审批**不提供**批准按钮', async () => {
    listApprovalsMock.mockResolvedValue({
      items: [sampleApproval({ expired: true })],
      nextCursor: null,
    })
    renderPage(<ApprovalsPage />)

    await screen.findByText('已过期')
    expect(screen.queryByRole('button', { name: '批准' })).toBeNull()
    // ⛔ 也必须说明原因，而不是静默无按钮
    expect(screen.getByText(/已过期，不可批准/)).toBeTruthy()
  })

  it('⛔ 拒绝必须先填理由才能提交', async () => {
    const user = userEvent.setup()
    listApprovalsMock.mockResolvedValue({ items: [sampleApproval()], nextCursor: null })
    renderPage(<ApprovalsPage />)

    await user.click(await screen.findByRole('button', { name: '拒绝' }))
    const confirm = screen.getByRole('button', { name: '确认拒绝' })
    expect((confirm as HTMLButtonElement).disabled).toBe(true)

    await user.type(screen.getByLabelText(/拒绝理由/), '参数越权')
    expect((confirm as HTMLButtonElement).disabled).toBe(false)

    await user.click(confirm)
    await waitFor(() => {
      expect(decideApprovalMock).toHaveBeenCalledWith('ap-1', 'DENY', '参数越权')
    })
  })

  it('⛔ 反证：改 mock 数据 → 页面跟着变', async () => {
    listApprovalsMock.mockResolvedValue({
      items: [sampleApproval({ argumentDigest: 'sha256:CHANGED' })],
      nextCursor: null,
    })
    renderPage(<ApprovalsPage />)

    expect(await screen.findByText('sha256:CHANGED')).toBeTruthy()
  })

  it('⛔ 诚实空态：无待批 ≠ 故障', async () => {
    renderPage(<ApprovalsPage />)

    expect(await screen.findByText('没有等待审批的操作')).toBeTruthy()
    expect(screen.queryByText(/读取失败/)).toBeNull()
  })
})

/* ── P9 UNKNOWN 处置台 ─────────────────────────────────── */

describe('P9 UNKNOWN 处置台（G9 接线）', () => {
  it('⛔⛔ 不得出现任何重试/重放入口（Sev-0）', async () => {
    listOperationsMock.mockResolvedValue({
      items: [sampleOperation()],
      nextCursor: null,
    })
    renderPage(<ReconciliationPage />)

    await screen.findByText('record_weakness')
    const labels = Array.from(document.querySelectorAll('button')).map((b) =>
      (b.textContent ?? '').trim(),
    )
    for (const label of labels) {
      expect(label).not.toContain('重试')
      expect(label).not.toContain('重放')
      expect(label).not.toContain('retry')
    }
    // ⛔ 连解释文字里也不能把「重试」作为一个可选项提出
    expect(screen.queryByRole('button', { name: /重试|重放/ })).toBeNull()
  })

  it('⛔ 必须显式区分「待核对」与三个已核对终态', async () => {
    listOperationsMock.mockResolvedValue({
      items: [
        sampleOperation({ operationId: 'op-unknown', state: 'UNKNOWN' }),
        sampleOperation({
          operationId: 'op-done',
          state: 'RECONCILED_ABSENT',
          manuallyDisposed: true,
          reconciliationSource: 'MANUAL',
        }),
      ],
      nextCursor: null,
    })
    renderPage(<ReconciliationPage />)

    // 待核对
    expect(await screen.findByText('结果未知')).toBeTruthy()
    // 三个终态在筛选器里可单独选择
    expect(screen.getByRole('option', { name: '人工核实·已发生' })).toBeTruthy()
    expect(screen.getByRole('option', { name: '人工核实·未发生' })).toBeTruthy()
    expect(screen.getByRole('option', { name: '人工结案·无法判定' })).toBeTruthy()
    // ⛔ 原始枚举名可见
    expect(screen.getAllByText('UNKNOWN').length).toBeGreaterThan(0)
    expect(screen.getByText('RECONCILED_ABSENT')).toBeTruthy()
  })

  it('⛔ 三个处置选项只提交核对结论，绝不含重放', async () => {
    const user = userEvent.setup()
    listOperationsMock.mockResolvedValue({
      items: [sampleOperation()],
      nextCursor: null,
    })
    renderPage(<ReconciliationPage />)

    await user.click(await screen.findByRole('button', { name: '记录核对结论' }))

    // 恰好三个选项
    const options = screen.getAllByRole('radio')
    expect(options).toHaveLength(3)

    await user.click(options[1] as HTMLElement)
    await waitFor(() => {
      expect(disposeOperationMock).toHaveBeenCalledWith('op-1', 'RECONCILED_ABSENT', null)
    })
  })

  it('⛔ 已处置的操作不再显示「记录核对结论」', async () => {
    listOperationsMock.mockResolvedValue({
      items: [sampleOperation({ manuallyDisposed: true, state: 'MANUAL_DISPOSED' })],
      nextCursor: null,
    })
    renderPage(<ReconciliationPage />)

    await screen.findByText('人工结案·无法判定')
    expect(screen.queryByRole('button', { name: '记录核对结论' })).toBeNull()
  })

  it('⛔ 不得暴露工具入参/回包', async () => {
    listOperationsMock.mockResolvedValue({
      items: [sampleOperation()],
      nextCursor: null,
    })
    renderPage(<ReconciliationPage />)

    await screen.findByText('record_weakness')
    expect(screen.queryByText(/toolArguments/)).toBeNull()
    expect(screen.queryByText(/resultPayload/)).toBeNull()
  })
})

/* ── P10 审计页 ─────────────────────────────────────────── */

describe('P10 审计查询（G10 接线）', () => {
  it('⛔ 连通性：渲染真实审计条目与明细', async () => {
    queryAuditMock.mockResolvedValue({ items: [sampleAudit()], nextCursor: null })
    renderPage(<AuditPage />)

    expect(await screen.findByText('approver-7')).toBeTruthy()
    // ⚠️ 「审批通过」既在筛选器 <option> 里、也在表格单元格里，
    //    故用 getAllByText 而非 getByText（后者会因「找到多个」而抛错）
    expect(screen.getAllByText('审批通过').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText(/operationId: op-1/)).toBeTruthy()
  })

  it('⛔⛔ 不得提供「查看完整请求体」入口（FE-01 §P10）', async () => {
    queryAuditMock.mockResolvedValue({ items: [sampleAudit()], nextCursor: null })
    renderPage(<AuditPage />)

    await screen.findByText('approver-7')
    const labels = [
      ...Array.from(document.querySelectorAll('button')).map((b) =>
        (b.textContent ?? '').trim(),
      ),
      ...Array.from(document.querySelectorAll('a')).map((a) =>
        (a.textContent ?? '').trim(),
      ),
    ]
    for (const label of labels) {
      expect(label).not.toContain('请求体')
      expect(label).not.toContain('删除')
    }
  })

  it('⛔ 改动作筛选 → 重新查询并带正确参数', async () => {
    const user = userEvent.setup()
    queryAuditMock.mockResolvedValue({ items: [sampleAudit()], nextCursor: null })
    renderPage(<AuditPage />)

    await screen.findByText('approver-7')
    await user.selectOptions(screen.getByLabelText('动作'), 'PROFILE_READ')

    await waitFor(() => {
      const last = queryAuditMock.mock.calls.at(-1)?.[0] as { action?: string }
      expect(last?.action).toBe('PROFILE_READ')
    })
  })

  it('⛔ 诚实空态 ≠ 故障', async () => {
    renderPage(<AuditPage />)

    expect(await screen.findByText('没有符合条件的审计记录')).toBeTruthy()
    expect(screen.queryByText(/读取失败/)).toBeNull()
  })

  /*
   * ── F4（2026-10-23）：P19「配置变更审计」合并进本页 ──────────────
   *
   * 原 P19 是独立页面 + 独立路由 /config/audit，与 P10 语义重叠
   * （都是查审计表，只是 resourceType 固定为 config）。
   * 合并后：删除独立页与导航项，配置查看留痕由本页
   * 「资源类型 → 配置查看」过滤视图承担。
   * 下面两条用例即原 P19 的行为覆盖，迁移至此以防回归。
   */
  it('F4：资源类型筛选提供「配置查看」选项（承接原 P19 入口）', async () => {
    renderPage(<AuditPage />)

    const select = screen.getByLabelText('资源类型') as HTMLSelectElement
    const values = Array.from(select.options).map((o) => o.value)
    expect(values).toContain('config')
    // ⛔ 合并 ≠ 保留两个入口：不得再出现独立「配置变更审计」页式导航
    expect(screen.queryByText('配置变更审计')).toBeNull()
  })

  it('F4：选「配置查看」→ 以 resourceType=config 查询并渲染查看轨迹', async () => {
    const user = userEvent.setup()
    queryAuditMock.mockResolvedValue({
      items: [
        sampleAudit({
          auditId: 'aud-sentinel-1',
          actorId: 'tenant-sentinel',
          action: 'CONFIG_VIEW',
          resourceType: 'config',
          resourceId: null,
          detail: {},
        }),
      ],
      nextCursor: null,
    })
    renderPage(<AuditPage />)
    // 初始加载即带 CONFIG_VIEW 条目（actorId 已被上面覆盖）
    await screen.findByText('tenant-sentinel')

    await user.selectOptions(screen.getByLabelText('资源类型'), 'config')

    // ⛔ 连通性：必须按 config 资源类型查询（而非全量审计）
    await waitFor(() => {
      const last = queryAuditMock.mock.calls.at(-1)?.[0] as {
        resourceType?: string
      }
      expect(last?.resourceType).toBe('config')
    })
    // 动作标签映射：CONFIG_VIEW → 查看配置
    // ⚠️ 「查看配置」既在动作筛选 <option> 里、也在表格单元格里，
    //    故用 getAllByText（与既有 P10 用例同一处理）。
    expect((await screen.findAllByText('查看配置')).length).toBeGreaterThanOrEqual(2)
  })
})