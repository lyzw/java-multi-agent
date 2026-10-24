/**
 * Run 检索台（演进重做 · 保留游标分页与诚实边界）
 *
 * ⚠️ **游标分页，不做页码**：契约已冻结为 keyset 分页（GET /api/runs?limit&cursor），
 *    页码式控件会要求 OFFSET，而本契约刻意不支持（并发插入下 OFFSET 会让行重复/漏读）。
 *
 * ⚠️ **不得编造后端没有的字段**：本表只展示后端真实返回的列。
 *    ⚠️ 2026-10-24：**学科/年级已由后端补齐**（`GET /api/runs` 回填
 *    `subject`/`grade`，且只回填业务词表内的取值），
 *    故本页新增「学科 · 年级」列与学科筛选；
 *    **耗时仍然没有**（`run` 表无该列），故不展示。
 */

import { useCallback, useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { LoadingRows, Empty, ErrorState, ListFooter } from '@/shared/states'
import { getDictionaries, listRuns } from '@/api/client'
import { useGovernancePage } from '@/features/governance/useGovernancePage'
import { formatSubject } from '@/shared/format'
import type { RunListItem, RunState } from '@/api/types'
import { PageHead, Panel, StatusPill, Toolbar, type PillTone } from '@/components/ui'

/**
 * 列定义。
 *
 * ⚠️ 表头一律**禁止折行**：2～4 个汉字的列名被压窄后会变成竖排
 *    （「领取次数」→ 领 / 取 / 次 / 数），比列窄本身更伤可读性。
 *    表格整体超宽时由 `.table-wrap` 承担横向滚动（与图表 `.chart-scroll` 同一处理方式）。
 */
const COLUMNS: ReadonlyArray<{ label: string; nowrap?: boolean }> = [
  { label: '运行标识' },
  { label: '学科 · 年级', nowrap: true },
  { label: '状态' },
  { label: '发起时间' },
  { label: '完成时间' },
  { label: '领取次数' },
  { label: '操作' },
]

/** 表头标签（供占位行复用同一份列定义，避免两处各写一遍）。 */
const COLUMN_LABELS = COLUMNS.map((c) => c.label)

/** 禁止折行的内联样式（不新增 class：CSS 完整性测试要求类名均有定义）。 */
const NO_WRAP: CSSProperties = { whiteSpace: 'nowrap' }

/**
 * 运行标识的省略号截断。
 *
 * <p>⚠️ <b>只截断显示，不截断数据</b>：DOM 文本仍是完整 runId，
 *    故可访问名（屏幕阅读器读的）与「复制链接」都不受影响；
 *    完整值同时出现在 {@code title} 里（悬停可见）。
 *
 * <p>⚠️ <b>为什么必须截</b>：运行标识是 36 位 UUID，占掉大半宽度，
 *    不截的话新增的「学科 · 年级」列就会被压成多行——
 *    <b>加一列反而让整张表更难读</b>。
 *
 * <p>⚠️ <b>截到 9 个字符</b>：留的是 UUID 的前 8 位十六进制（业界惯用的短标识）
 *    + 省略号。完整值在 {@code title}（悬停）、{@code href}（复制链接地址）
 *    与可访问名里都还在，只丢「直接框选可见文本复制」这一条路径。
 */
const TRUNCATE_ID: CSSProperties = {
  display: 'inline-block',
  maxWidth: '9ch',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  verticalAlign: 'bottom',
}

/**
 * ⚠️ B3：学科下拉的取值来自后端 `/api/reference`（单一真相源）；
 *    此处为**离线兜底**常量——端点不可达时筛选器仍可用，不阻断查询。
 */
const FALLBACK_SUBJECTS = ['数学', '物理', '化学', '生物', '语文', '英语'] as const

/** 状态 → 中文标签。⚠️ 取值必须与 `run_state` 枚举一致（**无 UNKNOWN**）。 */
const STATE_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '分析中',
  CANCELLED: '已取消',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  TIMED_OUT: '超时',
}

/** 状态 → 药丸色调（低饱和；语义色仅 success/danger）。 */
function stateTone(state: string): PillTone {
  if (state === 'SUCCEEDED') return 'success'
  if (state === 'FAILED' || state === 'TIMED_OUT') return 'danger'
  if (state === 'RUNNING') return 'info'
  return 'neutral'
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

/**
 * 列表里的时间显示：「10月22日 21:17」。
 *
 * ⚠️ **为什么去掉年份与秒**：列表要回答的是「哪一天、几点」，
 *    把秒摆出来对师生只是噪音；年份在租户内几乎恒定。
 *    要精确到秒的值在详情页与审计页仍完整保留。
 *    ⛔ 不引日期库：Intl 够用。
 */
function formatTime(iso: string | null): string {
  if (iso === null) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const day = d.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric' })
  const clock = d.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
  return `${day} ${clock}`
}

export function RunSearchPage(): JSX.Element {
  const [state, setState] = useState<string>('')
  const [subject, setSubject] = useState<string>('')
  const [subjects, setSubjects] = useState<string[]>([...FALLBACK_SUBJECTS])

  // ⚠️ B3：运行时从后端拉取业务词表，作为下拉单一真相源；失败回退硬编码兜底。
  useEffect(() => {
    let cancelled = false
    getDictionaries()
      .then((d) => {
        if (!cancelled && d.subjects.length > 0) setSubjects(d.subjects)
      })
      .catch(() => {
        /* 端点不可达 → 保留兜底词表，不阻断查询 */
      })
    return () => {
      cancelled = true
    }
  }, [])

  // ⚠️ fetcher 必须 useCallback 且依赖 state/subject —— 否则每次渲染都造新函数，
  //    effect 依赖里带上它就会无限重取（治理桶配额会被瞬间烧光）。
  const fetcher = useCallback(
    (params: { limit: number; cursor: string | null }) =>
      listRuns({
        limit: params.limit,
        cursor: params.cursor,
        state: state === '' ? null : state,
        subject: subject === '' ? null : subject,
      }),
    [state, subject],
  )

  const { items, loading, loadingMore, error, hasMore, reset, loadMore } =
    useGovernancePage<RunListItem>({
      fetcher,
      // ⚠️ 筛选条件进 deps → 改筛选自动回到第一页
      deps: [state, subject],
    })

  return (
    <>
      <PageHead title="检索记录" lead="按状态和时间找出分析过的题目。" />

      <Panel title="筛选" icon="sliders">
        <Toolbar>
          <div className="field" style={{ minWidth: 200 }}>
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
          <div className="field" style={{ minWidth: 200 }}>
            <label htmlFor="f-subject">学科</label>
            <select
              id="f-subject"
              value={subject}
              onChange={(e) => {
                setSubject(e.target.value)
                reset()
              }}
            >
              <option value="">全部</option>
              {subjects.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
        </Toolbar>
        <p className="muted" style={{ marginTop: 'var(--s-3)', marginBottom: 0 }}>
          {/*
            ⚠️ 2026-10-24：学科筛选与「学科 · 年级」列由后端补齐——
            `GET /api/runs` 现在回填 `subject`/`grade`，且只回填**业务词表内**的取值
            （DEC-6 白名单；不在词表内一律为空，绝不回显客户端原文）。
            用户看到「—」是「这条运行没有可展示的学科」，不是加载失败。
          */}
          这里按状态和学科筛选。学科与年级由后端回填，
          只显示<b>词表内</b>的取值——不在词表内一律为空（显示「—」）。
        </p>
      </Panel>

      <Panel title="查询结果" icon="search">
        {loading ? (
          <LoadingRows columns={COLUMN_LABELS} rows={5} label="正在读取记录" />
        ) : error !== null ? (
          <ErrorState reason={error} onRetry={reset} retryLabel="重新加载" />
        ) : items.length === 0 ? (
          <>
            {/*
              ⚠️ 2026-10-24：空态提示不再提「放宽时间范围」。
                  本页**没有时间维度**（后端 run 表没有该列），
                  让人去「放宽时间范围」等于指向一个不存在的控件 ——
                  用户翻遍页面找不到，只能认为页面坏了。
                  现在只指向真实存在的出口：换状态 / 换学科 / 去「报表中心」看按天汇总。
            */}
            <Empty
              icon="search"
              title="没有符合条件的记录"
              hint="换个状态或学科再试，或到「报表中心」按天查看汇总。"
            />
          </>
        ) : (
          <>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    {COLUMNS.map((c) => (
                      <th key={c.label} scope="col" style={NO_WRAP}>
                        {c.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {items.map((run) => (
                    <tr key={run.runId}>
                      {/*
                        ⚠️ 2026-10-24：运行标识改为链接。
                            本页此前是"只能看、不能进"的死列表——
                            看到一条失败记录，没有任何路径去看它为什么失败，
                            与工作台（每行带可点的操作按钮）行为不一致。
                      */}
                      <td>
                        <Link
                          to={`/runs/${encodeURIComponent(run.runId)}`}
                          className="mono"
                          title={`查看 ${run.runId} 的分析进度`}
                          style={TRUNCATE_ID}
                        >
                          {run.runId}
                        </Link>
                      </td>
                      {/*
                        ⚠️ 2026-10-24：新增「学科 · 年级」列。
                            后端只回填业务词表内的取值（fail-closed），
                            词表外一律显示「—」——不猜、不补、不编造。
                      */}
                      <td style={NO_WRAP}>{formatSubject(run)}</td>
                      <td>
                        <StatusPill tone={stateTone(run.state)}>
                          {STATE_LABELS[run.state] ?? run.state}
                        </StatusPill>
                      </td>
                      <td className="num">{formatTime(run.createdAt)}</td>
                      <td className="num">{formatTime(run.completedAt)}</td>
                      <td className="num">{run.attempt}</td>
                      {/*
                        ⚠️ **已完成的运行直达结果**（2026-10-24 UX 实测）：
                          成功终态没有"进度"可言，让用户点进进度页再等它跳结果是白绕一圈。
                          故 SUCCEEDED → 「查看结果」；其余状态仍是「查看进度」。
                          ⚠️ 运行标识那一列的链接**不改**（它指的是"这条运行本身"，
                            不是操作按钮），改的只是这一列的操作按钮。
                      */}
                      <td style={NO_WRAP}>
                        {run.state === 'SUCCEEDED' ? (
                          <Link
                            to={`/runs/${encodeURIComponent(run.runId)}/result`}
                            className="btn btn--quiet"
                          >
                            查看结果
                          </Link>
                        ) : (
                          <Link
                            to={`/runs/${encodeURIComponent(run.runId)}`}
                            className="btn btn--quiet"
                          >
                            查看进度
                          </Link>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {/*
              ⚠️ 2026-10-24：改用 states.tsx 的 ListFooter（单一真相源）。
                本页此前**自复制**了一份实现（GovernancePages / ConfigPages 也各有一份），
                复制件用裸 <button>（浏览器默认灰）与本站的墨色按钮体系不一致，
                且行为容易走样 —— 有无 nextCursor 才显示「加载更多」的规则
                在三处各写一遍，改一处就漏两处。
            */}
            <ListFooter
              count={items.length}
              hasMore={hasMore}
              loadingMore={loadingMore}
              onLoadMore={loadMore}
            />
          </>
        )}
      </Panel>
    </>
  )
}
