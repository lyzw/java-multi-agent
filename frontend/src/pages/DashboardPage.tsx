/**
 * 工作台（FE-D3 / B6）。
 *
 * ⚠️ 改为消费真实列表端点（GET /api/runs，P4-D13 / G7 · FE-01 §P7 检索台）：
 * 此前用 sessionStorage 冒充「本设备记录」是静默误导——且**无人写入**，永远为空。
 * 现直接拉取本租户近期辅导（最多 10 条），与后端 RunListController 对齐。
 */

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { Empty, Sheet, Loading, ErrorState } from '@/shared/states'
import { listRuns, ApiError } from '@/api/client'
import type { RunListItem } from '@/api/types'
import type { NormalizedError } from '@/api/errors'

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
      <div className="main__head">
        <h1>工作台</h1>
        <p className="main__lead">从一道做错的题开始，看它错在哪一步。</p>
      </div>

      <Sheet title="开始">
        {/*
          ⛔ 不用卡片网格（AI 默认：等圆角 + 同一套阴影）。
          用线分隔的列表 —— 更像一张目录，安静且信息密度高。
        */}
        <div className="entry-list">
          <Link to="/runs/new" className="entry">
            <span>
              <span className="entry__title">发起辅导</span>
              <span className="entry__desc">提交一道做错的题，找出原因并给出正确步骤</span>
            </span>
            <span className="entry__arrow" aria-hidden="true">
              →
            </span>
          </Link>

          <Link to="/runs" className="entry">
            <span>
              <span className="entry__title">检索记录</span>
              <span className="entry__desc">按状态、学科、时间查找分析过的题</span>
            </span>
            <span className="entry__arrow" aria-hidden="true">
              →
            </span>
          </Link>

          <Link to="/profile" className="entry">
            <span>
              <span className="entry__title">学习画像</span>
              <span className="entry__desc">看看哪些知识点反复出错</span>
            </span>
            <span className="entry__arrow" aria-hidden="true">
              →
            </span>
          </Link>
        </div>
      </Sheet>

      <Sheet title="近期辅导" note="本账号在本租户下最近的分析记录（最多 10 条）。">
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
          <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {runs.map((run) => (
              <li key={run.runId} className="entry">
                <span>
                  <Link to={`/runs/${run.runId}`} className="entry__title">
                    查看分析进度
                  </Link>
                  <span className="entry__desc mono">{run.runId}</span>
                </span>
                <span className="muted">
                  {new Date(run.createdAt).toLocaleString('zh-CN')} · {run.state}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Sheet>

      {session !== null ? (
        <p className="muted">登录状态由服务端控制，有效期到后会要求重新登录。</p>
      ) : null}
    </>
  )
}
