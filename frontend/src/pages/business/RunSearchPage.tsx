/**
 * P7 Run 检索台（FE-D10 / G7 接线）
 *
 * ⚠️ **游标分页，不做页码**（FE-D3 原注释的等待项已由 P4-D13 兑现）：
 *   契约已冻结为 keyset 分页（`GET /api/runs?limit&cursor`），
 *   页码式控件会要求后端支持 OFFSET，而本契约**刻意不支持**
 *   （并发插入下 OFFSET 会让行重复/漏读）。
 *
 * ⚠️ **不得编造后端没有的字段**：
 *   FE-01 §P7 原规划列出「学科」「耗时」两列，但 `run` 表**没有**
 *   `subject` / `duration` 列（P4-D09 实测）。渲染它们只能靠前端编造，
 *   故本表**只展示后端真实返回的列**。
 */

import { useCallback, useState } from 'react'
import { Sheet, LoadingRows, Empty, ErrorState } from '@/shared/states'
import { listRuns } from '@/api/client'
import { useGovernancePage } from '@/features/governance/useGovernancePage'
import type { RunListItem, RunState } from '@/api/types'

const COLUMNS = ['运行标识', '状态', '发起时间', '完成时间', '领取次数']

/** 状态 → 中文标签。⚠️ 取值必须与 `run_state` 枚举一致（**无 UNKNOWN**）。 */
const STATE_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '分析中',
  CANCELLED: '已取消',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  TIMED_OUT: '超时',
}

/** 可选状态。⛔ 刻意**不含 UNKNOWN**——后端枚举里根本没有这个状态。 */
const STATE_OPTIONS: Array<{ value: RunState; label: string }> = [
  { value: 'QUEUED', label: '排队中' },
  { value: 'RUNNING', label: '分析中' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'SUCCEEDED', label: '已完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'TIMED_OUT', label: '超时' },
]

function formatTime(iso: string | null): string {
  if (iso === null) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('zh-CN', { hour12: false })
}

export function RunSearchPage(): JSX.Element {
  const [state, setState] = useState<string>('')

  // ⚠️ fetcher 必须 useCallback 且依赖 state —— 否则每次渲染都造新函数，
  //    effect 依赖里带上它就会无限重取（治理桶配额会被瞬间烧光）。
  const fetcher = useCallback(
    (params: { limit: number; cursor: string | null }) =>
      listRuns({
        limit: params.limit,
        cursor: params.cursor,
        state: state === '' ? null : state,
      }),
    [state],
  )

  const { items, loading, loadingMore, error, hasMore, reset, loadMore } =
    useGovernancePage<RunListItem>({
      fetcher,
      // ⚠️ 筛选条件进 deps → 改筛选自动回到第一页
      deps: [state],
    })

  return (
    <>
      <div className="main__head">
        <h1>检索记录</h1>
        <p className="main__lead">按状态和时间找出分析过的题目。</p>
      </div>

      <div className="sheet">
        <div style={{ display: 'flex', gap: 'var(--s-4)', flexWrap: 'wrap' }}>
          <div className="field" style={{ margin: 0, minWidth: 160 }}>
            <label htmlFor="f-status">状态</label>
            <select
              id="f-status"
              value={state}
              onChange={(e) => {
                setState(e.target.value)
                // ⚠️ 改筛选必须丢游标：拿旧游标查新条件会得到空页/错页，
                //    而空页看起来像「这个租户没有数据」——
                //    这类「看起来像结论的错误」最难排查。
                reset()
              }}
            >
              <option value="">全部</option>
              {STATE_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
        </div>
        <p className="muted" style={{ marginTop: 'var(--s-3)', marginBottom: 0 }}>
          ⚠️ 这里只有状态筛选：后端的 <code>run</code> 表没有学科与耗时列
          （P4-D09 实测），所以这两项**不做展示**——凭空生成的列比没有列更糟。
        </p>
      </div>

      <Sheet title="查询结果">
        {loading ? (
          <LoadingRows columns={COLUMNS} rows={5} label="正在读取记录" />
        ) : error !== null ? (
          <ErrorState reason={error} onRetry={reset} retryLabel="重新加载" />
        ) : items.length === 0 ? (
          <Empty
            title="没有符合条件的记录"
            hint="放宽时间范围，或换个状态再试。"
          />
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  {COLUMNS.map((c) => (
                    <th key={c} scope="col">
                      {c}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {items.map((run) => (
                  <tr key={run.runId}>
                    <td className="mono">{run.runId}</td>
                    <td>
                      <span className="chip">{STATE_LABELS[run.state] ?? run.state}</span>
                    </td>
                    <td>{formatTime(run.createdAt)}</td>
                    <td>{formatTime(run.completedAt)}</td>
                    <td>{run.attempt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {/*
              ⚠️ 只有后端给了 nextCursor 才显示「加载更多」——
              没有下一页时按钮留着，点它会发一个必然为空的请求，
              白白消耗治理桶配额（多个运维共享同一桶）。
            */}
            {hasMore ? (
              <div className="status-line" style={{ marginTop: 'var(--s-3)' }}>
                <button type="button" onClick={loadMore} disabled={loadingMore}>
                  {loadingMore ? '正在加载…' : '加载更多'}
                </button>
                <span className="muted">已显示 {items.length} 条</span>
              </div>
            ) : (
              <p className="muted" style={{ marginTop: 'var(--s-3)' }}>
                已显示全部 {items.length} 条
              </p>
            )}
          </>
        )}
      </Sheet>
    </>
  )
}