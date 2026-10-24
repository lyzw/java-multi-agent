/**
 * 治理页组（FE-D10 / G8~G10 接线）
 *
 * ⛔⛔ P9 禁止「一键重试」—— Sev-0 级结构约束：
 *   UNKNOWN 的含义是「副作用是否已经发生**不知道**」，
 *   此时重放就是在「可能已经改了学生数据」之上再改一次。
 *   FE-01 §P9 原文：「必须显式区分 UNKNOWN / RECONCILIATION_REQUIRED，
 *   禁止提供一键重试（盲目重放 = Sev-0）」。
 *   故本文件**连占位重试都不写**，处置选项全部是终态归档。
 *
 * ⚠️ 三个处置取值的语义差别必须在 UI 上说清，否则运维会随手选第一个：
 *   RECONCILED_SUCCEEDED = 副作用**已发生**（记为成功）
 *   RECONCILED_ABSENT    = 副作用**未发生**（记为不存在）
 *   MANUAL_DISPOSED      = **无法判定**，人工结案
 */

import { useCallback, useState } from 'react'
import { Sheet, LoadingRows, Empty, ErrorState, ListFooter } from '@/shared/states'
import {
  listApprovals,
  decideApproval,
  listOperations,
  disposeOperation,
  queryAudit,
} from '@/api/client'
import { useGovernancePage } from '@/features/governance/useGovernancePage'
import { PageHead, Panel, Toolbar } from '@/components/ui'
import type {
  ApprovalItem,
  OperationItem,
  AuditEntry,
  Disposition,
} from '@/api/types'

/* ── 通用小组件 ─────────────────────────────────────────── */

function formatTime(iso: string | null): string {
  if (iso === null) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}

/* ── P8 审批台（G8）─────────────────────────────────────── */

const APPROVAL_STATE_LABELS: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
  CONSUMED: '已使用',
}

const RISK_LABELS: Record<string, string> = {
  READ: '只读',
  WRITE_PROFILE: '写画像',
  WRITE_EXTERNAL: '写外部',
}

export function ApprovalsPage(): JSX.Element {
  const [state, setState] = useState('PENDING')
  const [busyId, setBusyId] = useState<string | null>(null)
  const [denyTarget, setDenyTarget] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)

  const fetcher = useCallback(
    (params: { limit: number; cursor: string | null }) =>
      listApprovals({ limit: params.limit, cursor: params.cursor, state }),
    [state],
  )
  const { items, loading, loadingMore, error, hasMore, reset, loadMore } =
    useGovernancePage<ApprovalItem>({ fetcher, deps: [state] })

  const submit = async (
    approvalId: string,
    decision: 'APPROVE' | 'DENY',
    denyReason?: string,
  ): Promise<void> => {
    setBusyId(approvalId)
    setActionError(null)
    try {
      await decideApproval(approvalId, decision, denyReason ?? null)
      setDenyTarget(null)
      setReason('')
      reset()
    } catch (cause: unknown) {
      setActionError(
        cause instanceof Error ? cause.message : '操作失败，请稍后重试',
      )
    } finally {
      setBusyId(null)
    }
  }

  return (
    <>
      <PageHead title="审批台" lead="等着你确认才能执行的操作。" />

      <Panel title="筛选" icon="sliders">
        <Toolbar>
          <div className="field" style={{ minWidth: 200 }}>
            <label htmlFor="a-state">审批状态</label>
            <select
              id="a-state"
              value={state}
              onChange={(e) => {
                setState(e.target.value)
                reset()
              }}
            >
              {Object.entries(APPROVAL_STATE_LABELS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        </Toolbar>
      </Panel>

      <Sheet title="待审批">
        {/*
          ⚠️ 动作失败是**行内提示**，不是整页替换：
            一次「拒绝」失败就把列表换成错误框，用户刚填的理由看不见也改不了，
            而那个「重试」重试的还是**列表拉取**（不是那次决定本身）。
            故错误条挂在列表上方，列表本身继续渲染。
        */}
        {actionError !== null ? (
          <div className="marked" role="alert">
            <p className="marked__label">这次操作没有成功</p>
            <p style={{ marginBottom: 0 }}>{actionError}</p>
          </div>
        ) : null}
        {loading ? (
          <LoadingRows columns={['操作', '目标资源', '有效期', '决定']} rows={4} />
        ) : error !== null ? (
          <ErrorState reason={error} onRetry={reset} retryLabel="重新加载" />
        ) : items.length === 0 ? (
          <Empty title="没有等待审批的操作" hint="需要人工确认的操作会出现在这里。" />
        ) : (
          <>
            {items.map((item) => {
              // ⛔ 过期审批**不提供**批准按钮——后端会 409，
              //    但让用户点一个注定失败的操作是产品缺陷。
              // ⚠️ 但**过期仍然可以拒绝**：过期只否定了「批准」这一个决定；
              //    把「拒绝」也一并掐掉，这条待办就既不能批也不能驳，
              //    只能干等系统清理——那不是「保护用户」，是把活儿留给运维。
              const actionable = item.state === 'PENDING' && !item.expired
              const denyable = item.state === 'PENDING' && item.expired
              const stateText = APPROVAL_STATE_LABELS[item.state] ?? item.state
              // ⚠️ 红色**只**留给「已过期」（R1：红 = 这里有问题）。
              //    「待审批」这类中性状态若也用批改红，一屏 20 张连成红墙，
              //    真正需要被看见的「过期」反而被稀释掉。
              const expiredOnly = stateText === '已过期'
              const expiredMark = item.expired && !expiredOnly
              return (
                <div
                  key={item.approvalId}
                  // ⚠️ 非过期条目用 .marked--calm：左边的红线同样会连成一片，
                  //    把红留给真正出过事的条目。
                  className={item.expired ? 'marked' : 'marked marked--calm'}
                >
                  <p
                    className="marked__label"
                    style={expiredOnly ? undefined : { color: 'var(--ink-2)' }}
                  >
                    {stateText}
                    {expiredMark ? (
                      <b style={{ color: 'var(--mark)' }}> · 已过期</b>
                    ) : null}
                  </p>
                  {/*
                    ⚠️ FR-014 要求审批人看到三项，缺一不可：
                      参数摘要 + 目标资源 + 有效期。
                    ⚠️ argumentDigest 是**摘要**，不是参数原文——
                        参数里含学生提交的解答原文。
                  */}
                  <dl className="kv">
                    <dt>参数摘要</dt>
                    <dd className="mono">{item.argumentDigest}</dd>
                    <dt>目标资源</dt>
                    <dd className="mono">{item.targetResource}</dd>
                    <dt>有效期至</dt>
                    <dd>{formatTime(item.expiresAt)}</dd>
                    <dt>关联运行</dt>
                    <dd className="mono">{item.runId}</dd>
                  </dl>

                  {/*
                    ⚠️ 文案必须自洽：既然下面给了「拒绝」，就不能再说
                      「只能拒绝或等待系统清理」这种把用户往外推的说法。
                      「已过期，不可批准」必须留着（过期项不给批准按钮），
                      但要把**还能做什么**说清楚。
                  */}
                  {expiredMark ? (
                    <p className="muted">
                      已过期，不可批准。如需终止这条等待，请点「拒绝」并写明理由。
                    </p>
                  ) : null}

                  {denyTarget === item.approvalId ? (
                    <div className="field">
                      <label htmlFor={`reason-${item.approvalId}`}>拒绝理由（必填）</label>
                      <input
                        id={`reason-${item.approvalId}`}
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                      />
                      <div className="status-line" style={{ marginTop: 'var(--s-2)' }}>
                        <button
                          type="button"
                          className="btn"
                          disabled={busyId === item.approvalId || reason.trim() === ''}
                          onClick={() =>
                            void submit(item.approvalId, 'DENY', reason.trim())
                          }
                        >
                          确认拒绝
                        </button>
                        <button
                          type="button"
                          className="btn btn--quiet"
                          onClick={() => {
                            setDenyTarget(null)
                            setReason('')
                          }}
                        >
                          取消
                        </button>
                      </div>
                    </div>
                  ) : actionable ? (
                    <div className="status-line">
                      {/*
                        ⚠️ 主操作必须比次操作**重**：
                          「批准」用墨色主按钮，「拒绝」用静默按钮。
                          此前「批准」是裸 <button>（浏览器默认灰），
                          「拒绝」反而有样式——主次颠倒。
                          ⚠️ 可访问名（批准 / 拒绝 / 确认拒绝）不得改动。
                      */}
                      <button
                        type="button"
                        className="btn"
                        disabled={busyId === item.approvalId}
                        onClick={() => void submit(item.approvalId, 'APPROVE')}
                      >
                        批准
                      </button>
                      <button
                        type="button"
                        className="btn btn--quiet"
                        disabled={busyId === item.approvalId}
                        onClick={() => {
                          setDenyTarget(item.approvalId)
                          setReason('')
                        }}
                      >
                        拒绝
                      </button>
                    </div>
                  ) : denyable ? (
                    // ⚠️ 过期项**只**给「拒绝」——批准按钮不渲染（点了必定 409），
                    //    但拒绝必须给得出来，否则这条待办没有任何出路。
                    <div className="status-line">
                      <button
                        type="button"
                        className="btn btn--quiet"
                        disabled={busyId === item.approvalId}
                        onClick={() => {
                          setDenyTarget(item.approvalId)
                          setReason('')
                        }}
                      >
                        拒绝
                      </button>
                    </div>
                  ) : (
                    <p className="muted">
                      {item.state !== 'PENDING' && item.state !== 'EXPIRED'
                        ? `已由 ${item.approverId ?? '系统'} 处理，不再可改。`
                        : '已过期，不可批准。'}
                    </p>
                  )}
                </div>
              )
            })}
            <ListFooter
              count={items.length}
              hasMore={hasMore}
              loadingMore={loadingMore}
              onLoadMore={loadMore}
            />
          </>
        )}
      </Sheet>
    </>
  )
}

/* ── P9 UNKNOWN 处置台（G9）────────────────────────────── */

const OPERATION_STATE_LABELS: Record<string, string> = {
  UNKNOWN: '结果未知',
  RECONCILED_SUCCEEDED: '人工核实·已发生',
  RECONCILED_ABSENT: '人工核实·未发生',
  MANUAL_DISPOSED: '人工结案·无法判定',
}

interface DispositionOption {
  value: Disposition
  label: string
  hint: string
}

/**
 * ⚠️ 三选项**顺序即推荐度**，且每项都写清语义。
 * 语义写不清时运维会随手选第一个，等于把「已发生」当成默认值。
 */
const DISPOSITION_OPTIONS: DispositionOption[] = [
  {
    value: 'RECONCILED_SUCCEEDED',
    label: '已核实：副作用已发生',
    hint: '工具确实改动了数据，把它记为成功，不要再执行一次。',
  },
  {
    value: 'RECONCILED_ABSENT',
    label: '已核实：副作用未发生',
    hint: '工具在真正生效前就失败了，可以安全地重新发起一次新的辅导。',
  },
  {
    value: 'MANUAL_DISPOSED',
    label: '无法判定，人工结案',
    hint: '副作用到底发没发生说不清。归档留痕，不要猜。',
  },
]

export function ReconciliationPage(): JSX.Element {
  const [state, setState] = useState('UNKNOWN')
  const [busyId, setBusyId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [note, setNote] = useState('')

  const fetcher = useCallback(
    (params: { limit: number; cursor: string | null }) =>
      listOperations({ limit: params.limit, cursor: params.cursor, state }),
    [state],
  )
  const { items, loading, loadingMore, error, hasMore, reset, loadMore } =
    useGovernancePage<OperationItem>({ fetcher, deps: [state] })

  const submit = async (
    operationId: string,
    disposition: Disposition,
  ): Promise<void> => {
    setBusyId(operationId)
    setActionError(null)
    try {
      await disposeOperation(operationId, disposition, note.trim() === '' ? null : note.trim())
      setSelected(null)
      setNote('')
      reset()
    } catch (cause: unknown) {
      setActionError(cause instanceof Error ? cause.message : '处置失败，请稍后重试')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <>
      <PageHead title="未知操作处置" lead="结果不确定的操作，要人工核对后才能继续。" />

      {/*
        ⛔⛔ 结构约束说明：这里**没有「重试」按钮**，而且不会有。
        UNKNOWN = 不知道副作用是否已发生；重放就是在未知之上再施加一次副作用。
        这条约束写在 FE-01 §P9，并被后端枚举（G-10）从协议层锁死。
      */}
      <div className="marked" role="note">
        <p className="marked__label">为什么这里没有「重试」</p>
        <p style={{ marginBottom: 0 }}>
          「结果未知」的意思是这次操作可能已经改了数据，也可能没改。此时再执行一次，
          就是在不确定的基础上重复施加一次副作用——这是 Sev-0 级风险。
          所以这里只提供三种「核对结论」，全部是终态归档。
        </p>
      </div>

      <div className="sheet">
        <div className="field" style={{ margin: 0, minWidth: 240 }}>
          <label htmlFor="o-state">操作状态</label>
          <select
            id="o-state"
            value={state}
            onChange={(e) => {
              setState(e.target.value)
              reset()
            }}
          >
            {Object.entries(OPERATION_STATE_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
            <option value="IN_FLIGHT">执行中</option>
            <option value="SUCCEEDED">成功</option>
            <option value="FAILED">失败</option>
          </select>
        </div>
      </div>

      <Sheet title="待处置操作">
        {/*
          ⚠️ 2026-10-24：动作失败是**行内提示**，不是整页替换列表
             （与 P8 审批台同一做法）。
             此前 actionError 排在三元**第一位**：一次处置失败就把整张
             「待处置操作」列表换成错误框，用户刚写的处置说明看不见也改不了；
             而那个错误框里的按钮重新拉取的是**列表**，并不是那次处置本身。
             故错误条挂在列表上方，列表本身继续渲染。
             ⛔ 这里**不提供任何按钮**：P9 段落不得出现重放语义的入口
                （在未知副作用之上再施加一次 = Sev-0）。
        */}
        {actionError !== null ? (
          <div className="marked" role="alert">
            <p className="marked__label">这次处置没有归档成功</p>
            <p style={{ marginBottom: 0 }}>{actionError}</p>
            <p className="muted" style={{ margin: 'var(--s-2) 0 0' }}>
              下面的待处置列表和刚写的处置说明都还在。请核对结论后，再从三个选项里选一次。
            </p>
          </div>
        ) : null}
        {loading ? (
          <LoadingRows columns={['操作', '风险', '时间', '处置']} rows={4} />
        ) : error !== null ? (
          <ErrorState reason={error} onRetry={reset} retryLabel="重新加载" />
        ) : items.length === 0 ? (
          <Empty title="没有需要处置的操作" hint="所有结果不确定的操作都已核对完毕。" />
        ) : (
          <>
            {items.map((item) => {
              const actionable = item.state === 'UNKNOWN' && !item.manuallyDisposed
              const isOpen = selected === item.operationId
              return (
                <div key={item.operationId} className="marked">
                  <p className="marked__label">
                    {OPERATION_STATE_LABELS[item.state] ?? item.state}
                    {item.reconciliationSource !== null
                      ? ` · 来源 ${item.reconciliationSource}`
                      : ''}
                  </p>
                  {/*
                    ⚠️ **同时显示原始枚举名**：
                      FE-01 §P9 要求「显式区分 UNKNOWN / RECONCILIATION_REQUIRED」，
                      但实测 `tool_operation_state` 枚举里**根本没有**
                      RECONCILIATION_REQUIRED（P4-D10 已对 run_state 记录过同一问题）。
                      schema 实际的区分方式是：
                        UNKNOWN                    = 尚未核对
                        RECONCILED_SUCCEEDED/ABSENT = 已核对·副作用已/未发生
                        MANUAL_DISPOSED            = 已核对·无法判定
                      只显示中文标签会让运维看不出这三个终态的区别，
                      故原始枚举名一并展示（搜索、导出、跨系统对齐都需要它）。
                  */}
                  <p className="muted" style={{ margin: '0 0 var(--s-2)' }}>
                    状态码：<span className="mono">{item.state}</span>
                  </p>
                  <dl className="kv">
                    <dt>工具</dt>
                    <dd className="mono">{item.toolName}</dd>
                    <dt>风险等级</dt>
                    <dd>{RISK_LABELS[item.riskLevel] ?? item.riskLevel}</dd>
                    <dt>关联运行</dt>
                    <dd className="mono">{item.runId}</dd>
                    <dt>发生时间</dt>
                    <dd>{formatTime(item.createdAt)}</dd>
                  </dl>

                  {isOpen ? (
                    <div>
                      {/*
                        ⚠️ 说明框**排在 radio 之前**：
                          选中 radio 即完成归档（终态、不可撤销），
                          说明必须**先写**再选——排在后面的话，
                          用户选完才发现「原来还能写点东西」，可那时已经归档了。
                      */}
                      <div className="field">
                        <label htmlFor={`note-${item.operationId}`}>
                          处置说明（可选）
                        </label>
                        <input
                          id={`note-${item.operationId}`}
                          value={note}
                          onChange={(e) => setNote(e.target.value)}
                          disabled={busyId === item.operationId}
                        />
                        <p className="field__hint">
                          写下核对依据，会随这次归档一起记录。
                        </p>
                      </div>
                      {/*
                        ⚠️ 后果必须**写在选择之前**：
                          「选中即归档」是终态动作，不是表单里的一个待提交值。
                          不提前说清，用户会以为只是"选了个选项"，
                          点下去才发现已经不可撤销。
                      */}
                      <p className="muted" style={{ margin: '0 0 var(--s-2)' }}>
                        选中即完成归档，不可撤销。归档后该操作不再出现在待处置列表。
                      </p>
                      {DISPOSITION_OPTIONS.map((opt) => (
                        <div key={opt.value} className="field">
                          {/*
                            ⚠️ 整块可点：hint 放进 <label> 内部——
                              点标题、点说明都等价于点这个 radio
                              （label 的 htmlFor 与 input 的 id 已配对）。
                          */}
                          <label htmlFor={`${item.operationId}-${opt.value}`}>
                            {opt.label}
                            <span
                              className="field__hint"
                              style={{
                                display: 'block',
                                marginTop: 'var(--s-1)',
                                fontWeight: 400,
                              }}
                            >
                              {opt.hint}
                            </span>
                          </label>
                          <input
                            id={`${item.operationId}-${opt.value}`}
                            type="radio"
                            name={`disposition-${item.operationId}`}
                            disabled={busyId === item.operationId}
                            onChange={() => void submit(item.operationId, opt.value)}
                          />
                        </div>
                      ))}
                      <button
                        type="button"
                        className="btn btn--quiet"
                        onClick={() => {
                          setSelected(null)
                          setNote('')
                        }}
                      >
                        取消
                      </button>
                    </div>
                  ) : actionable ? (
                    <button
                      type="button"
                      className="btn"
                      onClick={() => setSelected(item.operationId)}
                    >
                      记录核对结论
                    </button>
                  ) : (
                    <p className="muted">这条已处理，不再需要人工核对。</p>
                  )}
                </div>
              )
            })}
            <ListFooter
              count={items.length}
              hasMore={hasMore}
              loadingMore={loadingMore}
              onLoadMore={loadMore}
            />
          </>
        )}
      </Sheet>
    </>
  )
}

/* ── P10 审计查询（G10）─────────────────────────────────── */

const AUDIT_ACTION_LABELS: Record<string, string> = {
  APPROVAL_GRANT: '审批通过',
  APPROVAL_REVOKE: '审批拒绝',
  APPROVAL_CONSUME: '审批消费',
  RUN_CANCEL: '取消运行',
  PROFILE_READ: '读取画像',
  AUDIT_READ: '查询审计',
  UNKNOWN_DISPOSE: '处置未知操作',
  // F4（2026-10-23）：原 P19 独立页合并进本页后，配置查看留痕
  // 在此渲染。⚠️ 该标签曾只存在于 ConfigPages 的本地副本，
  // 合并时若漏带，CONFIG_VIEW 会退化成原始枚举名显示给用户。
  CONFIG_VIEW: '查看配置',
}

export function AuditPage(): JSX.Element {
  const [action, setAction] = useState('')
  const [resourceType, setResourceType] = useState('')

  const fetcher = useCallback(
    (params: { limit: number; cursor: string | null }) =>
      queryAudit({
        limit: params.limit,
        cursor: params.cursor,
        action: action === '' ? null : action,
        resourceType: resourceType === '' ? null : resourceType,
      }),
    [action, resourceType],
  )
  const { items, loading, loadingMore, error, hasMore, reset, loadMore } =
    useGovernancePage<AuditEntry>({
      fetcher,
      deps: [action, resourceType],
      pageSize: 50,
    })

  return (
    <>
      <PageHead title="审计查询" lead="谁在什么时候做了什么。" />

      <Panel title="筛选" icon="sliders">
        <Toolbar>
          <div className="field" style={{ minWidth: 200 }}>
            <label htmlFor="au-action">动作</label>
            <select
              id="au-action"
              value={action}
              onChange={(e) => {
                setAction(e.target.value)
                reset()
              }}
            >
              <option value="">全部</option>
              {Object.entries(AUDIT_ACTION_LABELS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
          <div className="field" style={{ minWidth: 200 }}>
            <label htmlFor="au-resource">资源类型</label>
            <select
              id="au-resource"
              value={resourceType}
              onChange={(e) => {
                setResourceType(e.target.value)
                reset()
              }}
            >
              <option value="">全部</option>
              <option value="approval">审批</option>
              <option value="tool_operation">工具操作</option>
              <option value="run">运行</option>
              <option value="profile">学生画像</option>
              <option value="config">配置查看</option>
              <option value="audit_record">审计记录</option>
            </select>
          </div>
        </Toolbar>
        <p className="muted" style={{ marginTop: 'var(--s-3)', marginBottom: 0 }}>
          审计记录<b>只读</b>：没有删除或修改入口，且记录保留 180 天后自动清理。
          这里也不会展示请求原文——学生提交的题目与解答不在审计明细里。
        </p>
      </Panel>

      <Sheet title="审计记录">
        {loading ? (
          <LoadingRows
            columns={['时间', '操作者', '动作', '资源', '明细']}
            rows={6}
            label="正在读取审计记录"
          />
        ) : error !== null ? (
          <ErrorState reason={error} onRetry={reset} retryLabel="重新加载" />
        ) : items.length === 0 ? (
          <Empty title="没有符合条件的审计记录" hint="放宽筛选条件再试。" />
        ) : (
          <>
            {/*
              ⚠️ 必须包在 .table-wrap 里：明细列里的 UUID 有 min-content 宽度，
                窄屏（实测 390px）下会把整页撑破（scrollWidth 487）。
            */}
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    {['时间', '操作者', '动作', '资源', '明细'].map((c) => (
                      <th key={c} scope="col">
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {items.map((entry) => (
                    <tr key={entry.auditId}>
                      <td>{formatTime(entry.createdAt)}</td>
                      <td className="mono">{entry.actorId}</td>
                      <td>{AUDIT_ACTION_LABELS[entry.action] ?? entry.action}</td>
                      <td className="mono">
                        {entry.resourceType}
                        {entry.resourceId !== null ? `/${entry.resourceId}` : ''}
                      </td>
                      <td className="mono">{renderDetail(entry.detail)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ListFooter
              count={items.length}
              hasMore={hasMore}
              loadingMore={loadingMore}
              onLoadMore={loadMore}
            />
          </>
        )}
      </Sheet>
    </>
  )
}

/**
 * 审计明细渲染。
 *
 * ⚠️ 只渲染**标量值**：嵌套对象/数组一律显示为摘要而非展开。
 * 明细里理论上只有标量（P4-D13 写入侧已过 PII 扫描），
 * 真出现嵌套时应保持页面可读，而不是把整棵树铺开。
 */
function renderDetail(detail: Record<string, unknown>): string {
  const parts: string[] = []
  for (const [key, value] of Object.entries(detail)) {
    if (value === null) continue
    if (typeof value === 'object') {
      parts.push(`${key}: …`)
    } else {
      parts.push(`${key}: ${String(value)}`)
    }
  }
  return parts.length === 0 ? '—' : parts.join('  ')
}