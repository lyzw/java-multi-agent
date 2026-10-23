/**
 * 工作台（演进重做 · 保留真实数据接线）
 *
 * 数据仍来自 GET /api/runs（P4-D13 / G7）。演进只改观感与操作习惯：
 *   · 快捷入口改为图标动作卡（扫读更快、点击目标更大）；
 *   · 近期辅导改为带状态药丸的表格；
 * ⛔ 不编造统计数字（无真实指标源）。
 */

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { Empty, Loading, ErrorState } from '@/shared/states'
import { listRuns, ApiError } from '@/api/client'
import type { RunListItem } from '@/api/types'
import type { NormalizedError } from '@/api/errors'
import { PageHead, Panel, StatusPill, type PillTone } from '@/components/ui'
import { Icon } from '@/components/Icon'

/** 运行状态 → 中文标签（取值与 run_state 枚举一致，无 UNKNOWN）。 */
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
        <div className="action-grid">
          <ActionCard
            to="/runs/new"
            icon="plus"
            title="发起辅导"
            desc="提交一道做错的题，找出原因并给出正确步骤"
          />
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
            title="还没有辅导记录"
            hint="从上面选「发起辅导」，提交第一道错题。"
            action={
              <Link to="/runs/new" className="btn">
                发起辅导
              </Link>
            }
          />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">运行标识</th>
                  <th scope="col">状态</th>
                  <th scope="col">发起时间</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.runId}>
                    <td className="mono">{run.runId}</td>
                    <td>
                      <StatusPill tone={stateTone(run.state)}>
                        {STATE_LABELS[run.state] ?? run.state}
                      </StatusPill>
                    </td>
                    <td>{new Date(run.createdAt).toLocaleString('zh-CN')}</td>
                    <td>
                      <Link to={`/runs/${run.runId}`} className="btn btn--quiet">
                        查看进度
                      </Link>
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
