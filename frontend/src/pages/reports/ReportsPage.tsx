/**
 * 报表中心（工作台统计）
 *
 * ⚠️ **为什么单独成页而不是塞进工作台**：工作台是「一个人今天要做什么」，
 *    报表是「一段时间里发生了什么」——两者的时间尺度与阅读节奏都不同。
 *    塞在一起会让工作台被 30 天 × 7 张图表淹没，反而两头看不清。
 *
 * ⚠️ **三张必含报表 + 四张常用报表**（用户明确指定）：
 *    必含：① 每日搜索的问题数 ② 每日导入 RAG 的资料数 ③ 每日错题分析
 *    常用：④ 每日辅导量与成功率 ⑤ 知识点薄弱排行 ⑥ 每日活跃/新增学生
 *          ⑦ RAG 语料增长与准入率
 *
 * ⚠️ **数据来源是两端**：
 *    平台侧 /api/statistics/daily（辅导量、错题、学生活跃、知识点排行）
 *    RAG 侧 /api/v1/management/statistics/*（检索次数、导入量、语料概况）
 *    二者失败语义不同，故分区块各自成态（同 useReports 的设计说明）。
 *
 * ⚠️ **总量口径在后端**（2026-10-23）：区间合计不再由前端对逐日数组求和，
 *    改用后端返回的 {@code currentPeriod}——「上一区间」的数据根本不在
 *    响应里，前端无从算起；若本区间前端求和、上一区间后端给出，
 *    就出现两份口径。⚠️ RAG 侧后端未提供汇总值，故 RAG 两张报表仍在前端求和
 *    （其口径只有「区间内求和」一种，不存在两份口径的问题）。
 *
 * ⚠️ **环比的诚实边界**：
 *    · 上一区间为 0 时**不给百分比**——「上期无数据」不是「环比 +∞」；
 *    · 比率与比率之比用**百分点**，不用百分比（成功率 +3% 与 +3 个百分点不是一回事）；
 *    · 涨跌**不标红绿**：辅导量涨了未必是好事（失败也涨），方向不等于好坏。
 *
 * ⚠️ **诚实边界（不可省略）**：
 *    · 「每日搜索的问题数」只统计**上线后**的检索——检索审计是从本次改造起
 *      才落库的，之前的历史检索没有记录。故报表里会出现「早于上线日期的
 *      搜索数为 0」，那是**数据起点**，不是「检索功能坏了」。
 *    · 「每日辅导量」区分「发起量（含在途）」与「终态量（成功率分母）」，
 *      两个口径分别呈现，合并成一个就必然说谎。
 *    · 语料「准入率」= 已准入来源数 / 来源总数，分母含尚未审核的来源，
 *      因此准入率低**不等于**语料有问题，也可能是还没审。
 *    · 「每日活跃学生」= 当日产生过错因记录的去重学生数，
 *      **不是**登录学生数（后者无埋点）。
 *
 * ⚠️ **不返回个体信息**：全部为租户级聚合，无 student_id / 姓名 / 题目原文
 *    （P0-D00 §4 合规红线）。页面据此不做任何个体推断。
 */

import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { Loading, Empty, ErrorState } from '@/shared/states'
import { PageHead, Panel, StatCard, StatusPill } from '@/components/ui'
import { BarChart, LineChart, RankBars } from '@/components/Chart'
import { useReports } from '@/features/reports/useReports'
import { downloadCsv } from '@/features/reports/csv'
import type {
  DailyStatistics,
  KnowledgePointRank,
  PeriodSummary,
  RunDayStat,
  RagDailyImportCount,
  RagDailySearchCount,
  StudentDayStat,
  WeaknessDayStat,
} from '@/api/types'

/** 可选统计区间（后端钳制在 1～90 天）。 */
const DAY_OPTIONS = [7, 14, 30, 90] as const

/** 日期显示：只取「月-日」，图上不需要年份。 */
function shortDate(iso: string): string {
  return iso.length >= 10 ? iso.slice(5) : iso
}

/** 百分比（分母为 0 时返回 null，**不返回 0**——0% 与「无数据」不是一回事）。 */
function ratio(part: number, total: number): number | null {
  if (total <= 0) return null
  return Math.round((part / total) * 100)
}

/** 百分比展示：null → 「—」。 */
function pct(part: number, total: number): string {
  const r = ratio(part, total)
  return r === null ? '—' : `${r}%`
}

/**
 * 环比文案（计数口径）。
 *
 * ⚠️ **上期为 0 时不给百分比**：从 0 涨到 5 写成「+∞%」或「+100%」都是编造，
 *    真实情况是「上期没有基线可比」。
 */
function trendText(current: number, previous: number): string {
  if (previous <= 0) return '上期无基线'
  if (current === previous) return '较上期持平'
  const delta = Math.round(((current - previous) / previous) * 100)
  return `较上期 ${delta > 0 ? '+' : ''}${delta}%`
}

/**
 * 环比文案（比率口径）。
 *
 * ⚠️ **用「百分点」而不是「%」**：成功率的 80% → 83% 是**上升 3 个百分点**，
 *    不是「上升 3%」（后者是相对变化，含义完全不同）。比率之差必须用百分点。
 */
function rateTrendText(
  currentPart: number,
  currentTotal: number,
  previousPart: number,
  previousTotal: number,
): string {
  if (currentTotal <= 0) return '本区间无基线'
  if (previousTotal <= 0) return '上期无基线'
  const current = (currentPart / currentTotal) * 100
  const previous = (previousPart / previousTotal) * 100
  const delta = Math.round(current - previous)
  if (delta === 0) return '较上期持平'
  return `较上期 ${delta > 0 ? '+' : ''}${delta} 个百分点`
}

/* ── 表格区块 ─────────────────────────────────────────────── */

/**
 * 通用日期表。
 *
 * ⚠️ **每张图旁边都有等价表格**：图表只给趋势，具体数字必须能逐行读。
 *    屏幕阅读器用户读的是这张表（图表带 role="img" 摘要）。
 *
 * ⚠️ **导出与表格同源**：CSV 由同一个 rows 数组生成，
 *    保证「屏幕上看到的」与「导出的」逐格一致。
 */
function DailyTable({
  headers,
  rows,
  caption,
  exportName,
}: {
  headers: string[]
  rows: (string | number)[][]
  caption: string
  /** 给出则显示「导出 CSV」按钮（文件名） */
  exportName?: string
}): JSX.Element {
  return (
    <>
      {exportName !== undefined ? (
        <div className="toolbar" style={{ marginBottom: 'var(--s-2)' }}>
          <button
            type="button"
            className="btn btn--quiet"
            onClick={() => downloadCsv(exportName, headers, rows)}
          >
            导出 CSV
          </button>
          <span className="muted">导出的数字与下方表格逐格一致（同源数据）。</span>
        </div>
      ) : null}
      <div className="table-wrap">
        <table>
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr>
              {headers.map((h) => (
                <th key={h} scope="col">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={String(row[0])}>
                {row.map((cell, i) => (
                  <td key={`${String(row[0])}-${i}`} className={i === 0 ? 'mono' : undefined}>
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

/* ── 报表 ①：每日搜索的问题数 ─────────────────────────────── */

/**
 * 每日搜索的问题数。
 *
 * ⚠️ **口径说明必须同屏**：检索审计从本次改造起才落库，
 *    早于上线日的数据为 0 属正常（数据起点），页面须如实说明。
 */
function DailySearchReport({
  data,
  status,
  error,
  onRetry,
}: {
  data: RagDailySearchCount[] | null
  status: 'idle' | 'loading' | 'success' | 'error' | 'timeout'
  error: string | null
  onRetry: () => void
}): JSX.Element {
  if (status === 'idle') {
    return (
      <p className="muted" style={{ margin: 0 }}>
        正在准备检索统计…
      </p>
    )
  }
  if (status === 'loading') return <Loading label="正在读取检索统计" />
  if (status === 'error' || status === 'timeout') {
    return (
      <ErrorState
        reason={error ?? '读取检索统计失败'}
        next="语料检索统计依赖 RAG 管理面端点，请确认租户标识与服务状态。"
        onRetry={onRetry}
        retryLabel="重新读取"
      />
    )
  }

  const rows = data ?? []
  const total = rows.reduce((acc, d) => acc + d.searches, 0)
  const activeDays = rows.filter((d) => d.searches > 0).length

  // ⚠️ 空态判定是「**区间内全为 0**」，不是「数组为空」。
  //    后端始终返回**连续日期轴**（缺失的日子补 0），
  //    故「数组为空」在生产里不会出现——用数组长度判空会让真实的无数据日
  //    渲染成一张空图表，看起来像「页面坏了」而不是「这段时间没人检索」。
  if (total === 0) {
    return (
      <Empty
        title="这个区间还没有检索记录"
        hint="检索审计从本次改造起才开始落库，出现一次检索后这里就会有数据。"
      />
    )
  }

  return (
    <>
      <div className="stat-grid" style={{ marginBottom: 'var(--s-4)' }}>
        <StatCard label="区间内检索次数" value={total} hint="按天聚合的 RETRIEVE 审计条数" icon="search" />
        <StatCard label="有检索的日子" value={activeDays} hint={`共 ${rows.length} 天`} icon="clock" />
        <StatCard
          label="日均"
          value={rows.length > 0 ? Math.round(total / rows.length) : 0}
          hint="含零检索日"
          icon="activity"
        />
      </div>

      <BarChart
        data={rows.map((d) => ({ label: shortDate(d.date), value: d.searches }))}
        unit=" 次"
      />

      <div className="marked marked--calm" style={{ marginTop: 'var(--s-4)' }}>
        <p className="marked__label">口径说明</p>
        <p style={{ margin: 0 }}>
          统计口径是<b>检索次数</b>（每次检索记一条审计），不是「有多少个不同的题目」。
          检索审计从本次改造起才落库——早于上线日的那几天显示 0 是<b>数据起点</b>，
          不是检索功能异常。
          <br />
          ⚠️ 本表<b>暂无环比</b>：RAG 侧管理面只返回当前区间的逐日值，
          没有上一等长区间的基线——没有基线就不编百分比。
        </p>
      </div>

      <DailyTable
        caption="每日搜索的问题数明细"
        headers={['日期', '检索次数']}
        rows={rows.map((d) => [d.date, d.searches])}
        exportName="每日搜索的问题数"
      />
    </>
  )
}

/* ── 报表 ②：每日导入 RAG 的资料数 ────────────────────────── */

/**
 * 每日导入 RAG 的资料数。
 *
 * ⚠️ **三个层级不可相加**：资料来源 ⊃ 文档载体 ⊃ 片段。
 *    把三者求和会得到一个「毫无意义」的数字（同一份资料被数了三次）。
 *    故图表用**分组折线**而非堆叠面积。
 */
function DailyImportReport({
  data,
  status,
  error,
  onRetry,
}: {
  data: RagDailyImportCount[] | null
  status: 'idle' | 'loading' | 'success' | 'error' | 'timeout'
  error: string | null
  onRetry: () => void
}): JSX.Element {
  if (status === 'loading') return <Loading label="正在读取导入统计" />
  if (status === 'error' || status === 'timeout') {
    return (
      <ErrorState
        reason={error ?? '读取导入统计失败'}
        next="请确认租户标识正确，且 RAG 管理面端点可用。"
        onRetry={onRetry}
        retryLabel="重新读取"
      />
    )
  }

  const rows = data ?? []
  const sumSources = rows.reduce((a, d) => a + d.sources, 0)
  const sumDocuments = rows.reduce((a, d) => a + d.documents, 0)
  const sumChunks = rows.reduce((a, d) => a + d.chunks, 0)

  // ⚠️ 空态 = 三层级**全为 0**（后端始终返回连续日期轴，数组不会为空）
  if (sumSources === 0 && sumDocuments === 0 && sumChunks === 0) {
    return (
      <Empty
        title="这个区间还没有导入记录"
        hint="登记语料来源、导入文档载体并切分片段后，这里会逐日显示增量。"
      />
    )
  }

  return (
    <>
      <div className="stat-grid" style={{ marginBottom: 'var(--s-4)' }}>
        <StatCard label="资料来源" value={sumSources} hint="登记的册/作品数" icon="book" />
        <StatCard label="文档载体" value={sumDocuments} hint="导入的物理文件数" icon="file" />
        <StatCard label="可检索片段" value={sumChunks} hint="切分入库的片段数" icon="layers" />
      </div>

      <LineChart
        series={[
          {
            name: '资料来源',
            tone: 'primary',
            data: rows.map((d) => ({ label: shortDate(d.date), value: d.sources })),
          },
          {
            name: '文档载体',
            tone: 'muted',
            data: rows.map((d) => ({ label: shortDate(d.date), value: d.documents })),
          },
          {
            name: '片段',
            tone: 'settled',
            data: rows.map((d) => ({ label: shortDate(d.date), value: d.chunks })),
          },
        ]}
        unit=" 个"
      />

      <div className="marked marked--calm" style={{ marginTop: 'var(--s-4)' }}>
        <p className="marked__label">三个层级不可相加</p>
        <p style={{ margin: 0 }}>
          资料来源（登记的册 / 作品）、文档载体（导入的物理文件）、片段（切出的可检索单元）
          是<b>同一份资料的不同层级</b>，把它们加起来会把同一份资料数三次。
          故图中三条线并列呈现，不做堆叠。
          <br />
          ⚠️ 本表<b>暂无环比</b>：RAG 侧管理面未提供上一等长区间的基线。
        </p>
      </div>

      <DailyTable
        caption="每日导入 RAG 资料数明细"
        headers={['日期', '资料来源', '文档载体', '片段']}
        rows={rows.map((d) => [d.date, d.sources, d.documents, d.chunks])}
        exportName="每日导入RAG的资料数"
      />
    </>
  )
}

/* ── 报表 ③：每日错题分析 ─────────────────────────────────── */

/**
 * 每日错题分析。
 *
 * ⚠️ 口径是**错因记录条数**（一条错因 = 一个知识点上的一次错误归因），
 *    不是「多少道题」——一道题可能在多个知识点上各记一条。
 */
function DailyWeaknessReport({
  data,
  status,
  error,
  onRetry,
  current,
  previous,
}: {
  data: WeaknessDayStat[] | null
  status: 'idle' | 'loading' | 'success' | 'error' | 'timeout'
  error: string | null
  onRetry: () => void
  current: PeriodSummary | null
  previous: PeriodSummary | null
}): JSX.Element {
  if (status === 'loading') return <Loading label="正在读取错题统计" />
  if (status === 'error' || status === 'timeout') {
    return (
      <ErrorState
        reason={error ?? '读取错题统计失败'}
        next="错题统计来自错因记录表，请确认平台服务状态。"
        onRetry={onRetry}
        retryLabel="重新读取"
      />
    )
  }

  const rows = data ?? []
  const total = current?.weaknessCount ?? rows.reduce((acc, d) => acc + d.count, 0)
  const activeDays = rows.filter((d) => d.count > 0).length

  // ⚠️ 空态 = 区间内**全为 0**（后端始终返回连续日期轴）
  if (total === 0) {
    return (
      <Empty
        title="这个区间还没有错题分析记录"
        hint="完成一次错题归因后，这里会逐日显示分析条数。"
      />
    )
  }

  return (
    <>
      <div className="stat-grid" style={{ marginBottom: 'var(--s-4)' }}>
        <StatCard
          label="区间内分析条数"
          value={total}
          hint={previous ? trendText(total, previous.weaknessCount) : '错因记录条数'}
          icon="file"
        />
        <StatCard label="有分析的日子" value={activeDays} hint={`共 ${rows.length} 天`} icon="clock" />
        <StatCard
          label="日均"
          value={rows.length > 0 ? Math.round(total / rows.length) : 0}
          hint="含零分析日"
          icon="activity"
        />
      </div>

      <BarChart data={rows.map((d) => ({ label: shortDate(d.date), value: d.count }))} unit=" 条" />

      <div className="marked marked--calm" style={{ marginTop: 'var(--s-4)' }}>
        <p className="marked__label">口径说明</p>
        <p style={{ margin: 0 }}>
          统计口径是<b>错因记录条数</b>，不是「提交了几道题」——
          一道题若在两个知识点上各记一条错因，就会计 2 条。
        </p>
      </div>

      <DailyTable
        caption="每日错题分析明细"
        headers={['日期', '分析条数']}
        rows={rows.map((d) => [d.date, d.count])}
        exportName="每日错题分析"
      />
    </>
  )
}

/* ── 报表 ④：每日辅导量与成功率 ───────────────────────────── */

/**
 * 每日辅导量与终态构成。
 *
 * ⚠️ **「发起量」与「成功率分母」是两个口径**：
 *    · total（发起量）含在途 Run——回答「今天有多少学生来求助」；
 *    · terminal（终态量）才是成功率的分母——在途 Run 尚无结论，
 *      计入分母会把「还没出结果」算成未成功。
 *    早期版本把在途也从 total 里排除，导致当日辅导量被少算。
 */
function DailyRunReport({
  data,
  status,
  error,
  onRetry,
  current,
  previous,
}: {
  data: RunDayStat[] | null
  status: 'idle' | 'loading' | 'success' | 'error' | 'timeout'
  error: string | null
  onRetry: () => void
  current: PeriodSummary | null
  previous: PeriodSummary | null
}): JSX.Element {
  if (status === 'loading') return <Loading label="正在读取辅导统计" />
  if (status === 'error' || status === 'timeout') {
    return (
      <ErrorState
        reason={error ?? '读取辅导统计失败'}
        next="请确认平台服务状态后重试。"
        onRetry={onRetry}
        retryLabel="重新读取"
      />
    )
  }

  const rows = data ?? []
  const fallback = {
    total: rows.reduce((acc, d) => acc + d.total, 0),
    terminal: rows.reduce((acc, d) => acc + d.terminal, 0),
    succeeded: rows.reduce((acc, d) => acc + d.succeeded, 0),
    failed: rows.reduce((acc, d) => acc + d.failed, 0),
    timedOut: rows.reduce((acc, d) => acc + d.timedOut, 0),
    cancelled: rows.reduce((acc, d) => acc + d.cancelled, 0),
  }
  const cur = current ?? fallback
  const inFlight = cur.total - cur.terminal

  // ⚠️ 空态 = 区间内**全为 0**（后端始终返回连续日期轴）
  if (cur.total === 0) {
    return (
      <Empty
        title="这个区间还没有辅导记录"
        hint="发起一次辅导后，这里会逐日显示量与终态构成。"
      />
    )
  }

  return (
    <>
      <div className="stat-grid" style={{ marginBottom: 'var(--s-4)' }}>
        <StatCard
          label="区间内发起量"
          value={cur.total}
          hint={previous ? trendText(cur.total, previous.total) : '含在途'}
          icon="activity"
        />
        <StatCard
          label="成功率"
          value={pct(cur.succeeded, cur.terminal)}
          hint={
            previous
              ? `成功 ${cur.succeeded} / 终态 ${cur.terminal} · ${rateTrendText(
                  cur.succeeded,
                  cur.terminal,
                  previous.succeeded,
                  previous.terminal,
                )}`
              : `成功 ${cur.succeeded} / 终态 ${cur.terminal}`
          }
          tone={ratio(cur.succeeded, cur.terminal) !== null && (ratio(cur.succeeded, cur.terminal) ?? 0) >= 80 ? 'success' : 'warn'}
          icon="check"
        />
        <StatCard label="失败" value={cur.failed} hint="终态为 FAILED" icon="alert" />
        <StatCard label="超时" value={cur.timedOut} hint="终态为 TIMED_OUT" icon="clock" />
        <StatCard label="取消" value={cur.cancelled} hint="终态为 CANCELLED" icon="inbox" />
        <StatCard label="在途" value={inFlight} hint="排队中/分析中" icon="clock" />
      </div>

      <LineChart
        series={[
          {
            name: '成功',
            tone: 'settled',
            data: rows.map((d) => ({ label: shortDate(d.date), value: d.succeeded })),
          },
          {
            name: '失败',
            tone: 'mark',
            data: rows.map((d) => ({ label: shortDate(d.date), value: d.failed })),
          },
          {
            name: '超时',
            tone: 'warn',
            data: rows.map((d) => ({ label: shortDate(d.date), value: d.timedOut })),
          },
        ]}
        unit=" 次"
      />

      <div className="marked marked--calm" style={{ marginTop: 'var(--s-4)' }}>
        <p className="marked__label">两个口径</p>
        <p style={{ margin: 0 }}>
          <b>发起量</b>（{cur.total}）含在途 Run——回答「今天有多少学生来求助」；
          <b>成功率</b>的分母是<b>终态数</b>（{cur.terminal}）——
          在途 Run 尚无结论，计入分母会把「还没出结果」算成未成功，让成功率失真。
        </p>
      </div>

      <DailyTable
        caption="每日辅导量与终态构成明细"
        headers={['日期', '发起量', '终态', '成功', '失败', '超时', '取消', '成功率']}
        rows={rows.map((d) => [
          d.date,
          d.total,
          d.terminal,
          d.succeeded,
          d.failed,
          d.timedOut,
          d.cancelled,
          pct(d.succeeded, d.terminal),
        ])}
        exportName="每日辅导量与终态构成"
      />
    </>
  )
}

/* ── 报表 ⑤：知识点薄弱排行 ───────────────────────────────── */

/**
 * 薄弱知识点排行（Top N）。
 *
 * ⚠️ **两个口径都给**：「所选区间内」回答「这段时间哪里最薄弱」，
 *    「全历史累计」回答「长期哪些知识点反复出问题」。
 *    只给全历史，区间选择器在这一张图上就失效了；
 *    只给区间，长期薄弱点会被筛掉。两个都摆出来，读者自己选。
 */
function KnowledgePointReport({
  inRange,
  allTime,
  status,
  error,
  onRetry,
}: {
  inRange: KnowledgePointRank[] | null
  allTime: KnowledgePointRank[] | null
  status: 'idle' | 'loading' | 'success' | 'error' | 'timeout'
  error: string | null
  onRetry: () => void
}): JSX.Element {
  const [scope, setScope] = useState<'range' | 'all'>('range')

  if (status === 'loading') return <Loading label="正在读取知识点排行" />
  if (status === 'error' || status === 'timeout') {
    return (
      <ErrorState
        reason={error ?? '读取知识点排行失败'}
        next="请确认平台服务状态后重试。"
        onRetry={onRetry}
        retryLabel="重新读取"
      />
    )
  }

  const rows = scope === 'range' ? (inRange ?? []) : (allTime ?? [])
  // ⚠️ 类名在 JSX 之外算好：`className={scope === 'range' ? ... }` 会让
  //    「range」「all」两个**枚举值**被 CSS 完整性扫描当成类名（它们不是类名）。
  const rangeButtonClass = scope === 'range' ? 'btn' : 'btn btn--quiet'
  const allButtonClass = scope === 'all' ? 'btn' : 'btn btn--quiet'
  if (rows.length === 0) {
    return (
      <Empty
        title={scope === 'range' ? '这个区间还没有薄弱知识点' : '还没有薄弱知识点'}
        hint="产生错因记录后，这里会按命中次数排出最需要补强的知识点。"
      />
    )
  }

  return (
    <>
      <div className="toolbar" style={{ marginBottom: 'var(--s-3)' }}>
        <div className="btn-row">
          <button
            type="button"
            className={rangeButtonClass}
            onClick={() => setScope('range')}
          >
            所选区间内
          </button>
          <button
            type="button"
            className={allButtonClass}
            onClick={() => setScope('all')}
          >
            全历史累计
          </button>
        </div>
      </div>

      <RankBars
        rows={rows.map((k) => ({
          label: k.knowledgePoint,
          value: k.count,
          hint: summarizeErrorTypes(k),
        }))}
        unit=" 次"
      />

      <div className="marked marked--calm" style={{ marginTop: 'var(--s-4)' }}>
        <p className="marked__label">口径说明</p>
        <p style={{ margin: 0 }}>
          {scope === 'range' ? (
            <>
              本榜是<b>所选区间内</b>的命中——回答「这段时间哪里最薄弱」。
            </>
          ) : (
            <>
              本榜是<b>全历史累计</b>（非所选区间内），回答「长期哪些知识点反复出错」。
            </>
          )}
          右侧标注的是该知识点下的<b>错因分布</b>——同样的命中次数，
          「概念不清」与「计算失误」需要的是完全不同的补救动作。
        </p>
      </div>

      <DailyTable
        caption="薄弱知识点排行与错因分布"
        headers={['知识点', '命中次数', '错因分布']}
        rows={rows.map((k) => [k.knowledgePoint, k.count, summarizeErrorTypes(k)])}
        exportName={scope === 'range' ? '薄弱知识点排行（所选区间内）' : '薄弱知识点排行（全历史）'}
      />
    </>
  )
}

/** 错因分布摘要（按次数降序，形如「计算失误 ×12 · 审题偏差 ×3」）。 */
function summarizeErrorTypes(rank: KnowledgePointRank): string {
  const entries = Object.entries(rank.errorTypes).sort((a, b) => b[1] - a[1])
  if (entries.length === 0) return '—'
  return entries.map(([type, count]) => `${type} ×${count}`).join(' · ')
}

/* ── 报表 ⑥：每日活跃 / 新增学生 ──────────────────────────── */

/**
 * 每日活跃学生与新增学生。
 *
 * ⚠️ **「活跃」= 当日产生过错因记录**，不是登录学生数——
 *    平台没有登录埋点，用登录数冒充活跃数会造出不存在的数据。
 */
function DailyStudentReport({
  data,
  status,
  error,
  onRetry,
  current,
  previous,
}: {
  data: StudentDayStat[] | null
  status: 'idle' | 'loading' | 'success' | 'error' | 'timeout'
  error: string | null
  onRetry: () => void
  current: PeriodSummary | null
  previous: PeriodSummary | null
}): JSX.Element {
  if (status === 'loading') return <Loading label="正在读取学生活跃统计" />
  if (status === 'error' || status === 'timeout') {
    return (
      <ErrorState
        reason={error ?? '读取学生活跃统计失败'}
        next="请确认平台服务状态后重试。"
        onRetry={onRetry}
        retryLabel="重新读取"
      />
    )
  }

  const rows = data ?? []
  const activeTotal = current?.activeStudentDays ?? rows.reduce((acc, d) => acc + d.activeStudents, 0)
  const newTotal = current?.newStudents ?? rows.reduce((acc, d) => acc + d.newStudents, 0)

  // ⚠️ 空态 = 活跃与新增**同时为 0**
  if (activeTotal === 0 && newTotal === 0) {
    return (
      <Empty
        title="这个区间还没有学生活跃数据"
        hint="产生错因记录后，这里会显示每日活跃与新增。"
      />
    )
  }

  return (
    <>
      <div className="stat-grid" style={{ marginBottom: 'var(--s-4)' }}>
        <StatCard
          label="区间内活跃人次"
          value={activeTotal}
          hint={previous ? trendText(activeTotal, previous.activeStudentDays) : '按日去重后累加'}
          icon="profile"
        />
        <StatCard
          label="区间内新增学生"
          value={newTotal}
          hint={previous ? trendText(newTotal, previous.newStudents) : '首次建档'}
          icon="plus"
        />
      </div>

      <LineChart
        series={[
          {
            name: '活跃学生',
            tone: 'primary',
            data: rows.map((d) => ({ label: shortDate(d.date), value: d.activeStudents })),
          },
          {
            name: '新增学生',
            tone: 'settled',
            data: rows.map((d) => ({ label: shortDate(d.date), value: d.newStudents })),
          },
        ]}
        unit=" 人"
      />

      <div className="marked marked--calm" style={{ marginTop: 'var(--s-4)' }}>
        <p className="marked__label">「活跃」的定义</p>
        <p style={{ margin: 0 }}>
          活跃 = 当日<b>产生过错因记录</b>的学生（按日去重）。
          平台没有登录埋点，因此这里<b>不是</b>登录学生数——
          用登录数冒充活跃数会造出平台上并不存在的数据。
          「活跃人次」是逐日去重后累加，同一学生多天活跃会被计多次。
        </p>
      </div>

      <DailyTable
        caption="每日活跃与新增学生明细"
        headers={['日期', '活跃学生', '新增学生']}
        rows={rows.map((d) => [d.date, d.activeStudents, d.newStudents])}
        exportName="每日活跃与新增学生"
      />
    </>
  )
}

/* ── 报表 ⑦：RAG 语料增长与准入率 ─────────────────────────── */

/**
 * 语料增长与准入概况。
 *
 * ⚠️ **准入率的分母含未审核来源**：准入率低可能是「还没审」，
 *    也可能是「审了没过」——两者含义完全不同，故同时呈现来源状态分布。
 */
function CorpusAdmissionReport({
  corpus,
  status,
  error,
  onRetry,
}: {
  corpus: import('@/api/types').RagStatistics | null
  status: 'idle' | 'loading' | 'success' | 'error' | 'timeout'
  error: string | null
  onRetry: () => void
}): JSX.Element {
  if (status === 'loading') return <Loading label="正在读取语料概况" />
  if (status === 'error' || status === 'timeout') {
    return (
      <ErrorState
        reason={error ?? '读取语料概况失败'}
        next="请确认租户标识与 RAG 管理面端点可用。"
        onRetry={onRetry}
        retryLabel="重新读取"
      />
    )
  }
  if (corpus === null) {
    return (
      <Empty title="还没有语料数据" hint="登记并导入语料后，这里会显示准入与索引概况。" />
    )
  }

  const admitted = corpus.admissibleSources
  const total = corpus.totalSources
  const storedButHidden = corpus.totalChunks - corpus.retrievableChunks

  return (
    <>
      <div className="stat-grid" style={{ marginBottom: 'var(--s-4)' }}>
        <StatCard label="来源总数" value={total} hint="含尚未审核的" icon="book" />
        <StatCard label="当前准入" value={admitted} hint="可被检索到" tone="success" icon="check" />
        <StatCard
          label="准入率"
          value={pct(admitted, total)}
          hint="分母含未审核来源"
          icon="activity"
        />
        <StatCard
          label="缺向量片段"
          value={corpus.chunksMissingEmbedding}
          hint={`占比 ${pct(corpus.chunksMissingEmbedding, corpus.totalChunks)}`}
          tone={corpus.chunksMissingEmbedding > 0 ? 'warn' : 'success'}
          icon="alert"
        />
      </div>

      {storedButHidden > 0 ? (
        <div className="marked marked--calm" style={{ marginTop: 'var(--s-3)' }}>
          <p className="marked__label">已入库 ≠ 可检索</p>
          <p style={{ margin: 0 }}>
            有 {storedButHidden} 个片段已入库但当前不可检索。
            片段数量健康<b>不代表</b>检索能得到内容——
            来源未通过校验、校验过期、版权未授权都会造成。
          </p>
        </div>
      ) : null}

      <DailyTable
        caption="语料来源校验状态分布"
        headers={['校验状态', '来源数']}
        rows={Object.entries(corpus.sourcesByVerificationStatus ?? {}).map(([k, v]) => [
          k,
          v,
        ])}
        exportName="语料来源校验状态分布"
      />

      <div className="marked marked--calm" style={{ marginTop: 'var(--s-4)' }}>
        <p className="marked__label">准入率的正确读法</p>
        <p style={{ margin: 0 }}>
          准入率的分母含<b>尚未审核</b>的来源。低准入率可能只是「还没审」，
          不等于「语料有问题」——请对照上方状态分布里的「待校验」数量再下结论。
          准入与否由服务端按租户权威门槛计算，前端不自行推算。
        </p>
      </div>
    </>
  )
}

/* ── 页面 ─────────────────────────────────────────────────── */

export function ReportsPage(): JSX.Element {
  const { session } = useSession()
  const [days, setDays] = useState<number>(30)

  const tenantId = session?.tenantId ?? ''
  const { platform, rag, reload } = useReports(tenantId, days)

  const platformData: DailyStatistics | null = platform.data
  const runs = useMemo(() => platformData?.runsByDay ?? null, [platformData])
  const weaknesses = useMemo(() => platformData?.weaknessesByDay ?? null, [platformData])
  const students = useMemo(() => platformData?.studentsByDay ?? null, [platformData])
  const knowledgePoints = useMemo(() => platformData?.topKnowledgePoints ?? null, [platformData])
  const knowledgePointsInRange = useMemo(
    () => platformData?.topKnowledgePointsInRange ?? null,
    [platformData],
  )
  const currentPeriod = platformData?.currentPeriod ?? null
  const previousPeriod = platformData?.previousPeriod ?? null

  return (
    <>
      <PageHead
        title="报表中心"
        eyebrow="统计与报表"
        lead="按天看辅导、错题与语料的变化。全部为租户级聚合，不含任何学生可识别信息。"
        actions={
          <button type="button" className="btn btn--quiet" onClick={reload}>
            刷新
          </button>
        }
      />

      {/* ── 区间选择 ── */}
      <Panel title="统计区间" icon="clock" note="后端把区间钳制在 1～90 天，超出会静默截断。">
        <div className="toolbar">
          <div className="field" style={{ margin: 0, maxWidth: 200 }}>
            <label htmlFor="reports-days">统计天数</label>
            <select
              id="reports-days"
              value={days}
              onChange={(e) => setDays(Number(e.target.value))}
            >
              {DAY_OPTIONS.map((d) => (
                <option key={d} value={d}>
                  最近 {d} 天
                </option>
              ))}
            </select>
          </div>
          <div className="toolbar__spacer" />
          <span className="muted">
            <StatusPill tone="info">UTC 日期口径</StatusPill>
            <span style={{ marginLeft: 'var(--s-2)' }}>
              日期按 UTC 归日，与后端聚合口径一致（跨午夜不会错位）。
            </span>
          </span>
        </div>
      </Panel>

      {/* ── 必含报表 ①：每日搜索的问题数 ── */}
      <Panel
        title="每日搜索的问题数"
        icon="search"
        note="按天统计的 RAG 检索次数——检索审计从本次改造起才开始落库。"
      >
        <DailySearchReport
          data={rag.searches?.searchesByDay ?? null}
          status={rag.status}
          error={rag.error}
          onRetry={reload}
        />
      </Panel>

      {/* ── 必含报表 ②：每日导入 RAG 的资料数 ── */}
      <Panel
        title="每日导入 RAG 的资料数"
        icon="book"
        note="资料来源 / 文档载体 / 片段三个层级并列，不做求和。"
      >
        <DailyImportReport
          data={rag.imports?.importsByDay ?? null}
          status={rag.status}
          error={rag.error}
          onRetry={reload}
        />
      </Panel>

      {/* ── 必含报表 ③：每日错题分析 ── */}
      <Panel
        title="每日错题分析"
        icon="file"
        note="按天的错因记录条数——一道题可能记多条错因。"
      >
        <DailyWeaknessReport
          data={weaknesses}
          status={platform.status}
          error={platform.error}
          onRetry={reload}
          current={currentPeriod}
          previous={previousPeriod}
        />
      </Panel>

      {/* ── 常用报表 ④：每日辅导量与成功率 ── */}
      <Panel
        title="每日辅导量与成功率"
        icon="activity"
        note="发起量含在途；成功率的分母只算终态。"
      >
        <DailyRunReport
          data={runs}
          status={platform.status}
          error={platform.error}
          onRetry={reload}
          current={currentPeriod}
          previous={previousPeriod}
        />
      </Panel>

      {/* ── 常用报表 ⑤：知识点薄弱排行 ── */}
      <Panel
        title="知识点薄弱排行"
        icon="alert"
        note="Top 10；可切换「所选区间内」与「全历史累计」两个口径。"
      >
        <KnowledgePointReport
          inRange={knowledgePointsInRange}
          allTime={knowledgePoints}
          status={platform.status}
          error={platform.error}
          onRetry={reload}
        />
      </Panel>

      {/* ── 常用报表 ⑥：每日活跃 / 新增学生 ── */}
      <Panel
        title="每日活跃与新增学生"
        icon="profile"
        note="活跃 = 当日产生过错因记录的去重学生数，不是登录学生数。"
      >
        <DailyStudentReport
          data={students}
          status={platform.status}
          error={platform.error}
          onRetry={reload}
          current={currentPeriod}
          previous={previousPeriod}
        />
      </Panel>

      {/* ── 常用报表 ⑦：RAG 语料增长与准入率 ── */}
      <Panel
        title="RAG 语料增长与准入率"
        icon="layers"
        note="来源状态分布 + 准入率 + 缺向量片段；增量趋势见上方「每日导入」。"
      >
        <CorpusAdmissionReport
          corpus={rag.corpus}
          status={rag.status}
          error={rag.error}
          onRetry={reload}
        />
      </Panel>

      {/* ── 相关页面 ── */}
      <Panel title="相关页面" icon="external" variant="quiet">
        <div className="btn-row">
          <Link to="/runs" className="btn btn--quiet">
            检索记录（逐条明细）
          </Link>
          <Link to="/rag/management" className="btn btn--quiet">
            语料管理（来源与载体）
          </Link>
          <Link to="/audit" className="btn btn--quiet">
            审计查询
          </Link>
        </div>
        <p className="muted" style={{ marginTop: 'var(--s-3)', marginBottom: 0 }}>
          报表是<b>聚合视图</b>；要看逐条记录请到「检索记录」与「审计查询」。
        </p>
      </Panel>
    </>
  )
}
