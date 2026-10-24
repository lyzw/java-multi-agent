/**
 * 工作台（演进重做 · 保留真实数据接线）
 *
 * 数据仍来自 GET /api/runs（P4-D13 / G7）。演进只改观感与操作习惯：
 *   · 快捷入口改为图标动作卡（扫读更快、点击目标更大）；
 *   · 近期辅导改为带状态药丸的表格；
 * ⛔ 不编造统计数字（无真实指标源）。
 */

import { useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { Empty, Loading, ErrorState } from '@/shared/states'
import { listRuns, ApiError } from '@/api/client'
import { formatSubject } from '@/shared/format'
import type { RunListItem } from '@/api/types'
import type { NormalizedError } from '@/api/errors'
import { PageHead, Panel, StatusPill, type PillTone } from '@/components/ui'
import { Icon } from '@/components/Icon'

/** 运行状态 → 中文标签（取值与 run_state 枚举一致，无 UNKNOWN）。 */
/** 禁止折行的内联样式（不新增 class：CSS 完整性测试要求类名均有定义）。 */
const NO_WRAP: CSSProperties = { whiteSpace: 'nowrap' }

/**
 * 运行标识的省略号截断（同检索台口径）。
 *
 * <p>⚠️ 只截断显示，不截断数据：DOM 文本仍是完整 runId，
 *    悬停 {@code title} 与可访问名都保留完整值。
 */
const TRUNCATE_ID: CSSProperties = {
  display: 'inline-block',
  maxWidth: '9ch',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  verticalAlign: 'bottom',
}

const STATE_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '分析中',
  CANCELLED: '已取消',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  TIMED_OUT: '超时',
}

/** 运行状态 → 药丸色调（低饱和，语义色仅 success/danger）。 */
function stateTone(state: string): PillTone {
  if (state === 'SUCCEEDED') return 'success'
  if (state === 'FAILED' || state === 'TIMED_OUT') return 'danger'
  if (state === 'RUNNING') return 'info'
  return 'neutral'
}

/**
 * 列表里的时间显示：「10月22日 21:17」。
 *
 * ⚠️ **为什么去掉年份与秒**：列表要回答的是「哪一天、几点」，
 *    把秒摆出来对师生只是噪音（同一批记录会连着出现 21:17:03 / 21:17:41）；
 *    年份在租户内几乎恒定。要精确到秒的值在详情页与审计页仍完整保留。
 *    ⛔ 不引日期库：Intl 够用，且少一个依赖就少一份供应链面。
 */
function formatListTime(iso: string | null): string {
  if (iso === null || iso === '') return '—'
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

interface ActionCardProps {
  to: string
  icon: Parameters<typeof Icon>[0]['name']
  title: string
  desc: string
}

function ActionCard({ to, icon, title, desc }: ActionCardProps): JSX.Element {
  return (
    <Link to={to} className="action">
      <span className="action__icon">
        <Icon name={icon} />
      </span>
      <span className="action__body">
        <span className="action__title">{title}</span>
        <span className="action__desc">{desc}</span>
      </span>
      <Icon name="chevron" className="action__go" />
    </Link>
  )
}

export function DashboardPage(): JSX.Element {
  const { session } = useSession()
  const [runs, setRuns] = useState<RunListItem[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<NormalizedError | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listRuns({ limit: 10 })
      .then((page) => {
        if (!cancelled) setRuns(page.items)
      })
      .catch((cause) => {
        if (!cancelled && cause instanceof ApiError) setLoadError(cause.normalized)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <>
      <PageHead
        title="工作台"
        lead="从一道做错的题开始，看它错在哪一步。"
        actions={
          <Link to="/runs/new" className="btn">
            <Icon name="plus" />
            发起辅导
          </Link>
        }
      />

      <Panel title="开始" icon="dashboard">
        {/*
          ⚠️ **「开始」区不再放「发起辅导」**（2026-10-24 UX 实测）：
            页头已有主 CTA「发起辅导」，两处同名同目标的入口并排出现，
            让人怀疑是两个不同的功能。页头的那个位置更显、视觉更重，保留它；
            这里只留四个**不同目标**的入口。
        */}
        <div className="action-grid">
          <ActionCard
            to="/runs"
            icon="search"
            title="检索记录"
            desc="按状态、学科、时间查找分析过的题"
          />
          <ActionCard
            to="/rag"
            icon="book"
            title="知识检索"
            desc="从教材语料检索可溯源的讲解与出处"
          />
          <ActionCard
            to="/profile"
            icon="profile"
            title="学习画像"
            desc="看看哪些知识点反复出错"
          />
          {/*
            ⚠️ 报表中心入口：**独立成页**而非内嵌工作台。
               工作台是「今天要做什么」，报表是「一段时间里发生了什么」——
               两者时间尺度不同，塞在一起会让工作台被图表淹没。
               入口放在这里，是为了让「想看统计」的人不必猜菜单在哪。
          */}
          <ActionCard
            to="/reports"
            icon="activity"
            title="报表中心"
            desc="按天看辅导、错题、检索与语料导入的汇总"
          />
        </div>
      </Panel>

      <Panel title="近期辅导" icon="clock" note="本账号在本租户下最近的分析记录（最多 10 条）。">
        {loading ? (
          <Loading label="加载中…" />
        ) : loadError !== null ? (
          <ErrorState reason={loadError.message} />
        ) : runs.length === 0 ? (
          <Empty
            icon="clock"
            title="还没有辅导记录"
            hint="从上面选「发起辅导」，提交第一道错题。"
            action={
              /*
                ⚠️ 空态里的动作**不叫「发起辅导」**：页头主 CTA 已经叫这个名字，
                   两处同名同目标的链接会让人以为是两个功能。
                   这里说清它是"第一道"（空态语义），页头那个保持不变。
              */
              <Link to="/runs/new" className="btn">
                发起第一道辅导
              </Link>
            }
          />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  {/*
                    ⚠️ 表头一律不折行：2～4 个汉字的列名被压窄会变成竖排
                        （「领取次数」→ 领 / 取 / 次 / 数），比列窄本身更伤可读性。
                  */}
                  <th scope="col" style={NO_WRAP}>运行标识</th>
                  <th scope="col" style={NO_WRAP}>学科 · 年级</th>
                  <th scope="col" style={NO_WRAP}>状态</th>
                  <th scope="col" style={NO_WRAP}>发起时间</th>
                  <th scope="col" style={NO_WRAP}>操作</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.runId}>
                    {/*
                      ⚠️ 运行标识截断显示（同检索台）：36 位 UUID 占掉大半宽度，
                          会把「发起时间」挤到折行。DOM 文本仍是完整 runId，
                          悬停 title 与可访问名都保留完整值。
                    */}
                    <td>
                      <span className="mono" style={TRUNCATE_ID} title={run.runId}>
                        {run.runId}
                      </span>
                    </td>
                    {/*
                      ⚠️ 2026-10-24：新增「学科 · 年级」列。
                          此前本表唯一能看的内容是一串 UUID —— 人认不出它是哪道题，
                          「近期辅导」就退化成一张无法辨认的流水账。
                          后端只回填业务词表内的取值（fail-closed），没有就显示「—」。
                          ⚠️ 禁止折行：运行标识列是 36 位 UUID，已占掉大半宽度，
                          这一列若被压成多行，整张表就没人愿意看了。
                    */}
                    <td style={NO_WRAP}>{formatSubject(run)}</td>
                    <td>
                      <StatusPill tone={stateTone(run.state)}>
                        {STATE_LABELS[run.state] ?? run.state}
                      </StatusPill>
                    </td>
                    <td style={NO_WRAP}>{formatListTime(run.createdAt)}</td>
                    <td style={NO_WRAP}>
                      {/*
                        ⚠️ **已完成的运行直接给结果**（2026-10-24 UX 实测）：
                          成功终态的运行没有"进度"可言，让用户点进进度页再等它跳结果
                          是白绕一圈。故 SUCCEEDED → 「查看结果」直达结果页；
                          其余状态仍是「查看进度」。
                      */}
                      {run.state === 'SUCCEEDED' ? (
                        <Link to={`/runs/${encodeURIComponent(run.runId)}/result`} className="btn btn--quiet">
                          查看结果
                        </Link>
                      ) : (
                        <Link to={`/runs/${encodeURIComponent(run.runId)}`} className="btn btn--quiet">
                          查看进度
                        </Link>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      {session !== null ? (
        <p className="muted">登录状态由服务端控制，有效期到后会要求重新登录。</p>
      ) : null}
    </>
  )
}
