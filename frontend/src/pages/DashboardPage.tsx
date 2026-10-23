/**
 * 工作台（FE-D3）
 *
 * ⛔ 不做全局任务列表：后端无列表接口（G7），也无分页契约。
 *    用 localStorage 冒充「全部任务」是静默误导。
 *    本机记录**明确标注范围**。
 */

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { Empty, Sheet } from '@/shared/states'

interface LocalRun {
  runId: string
  createdAt: string
}

const LOCAL_RUNS_KEY = 'agent.localRuns'

function readLocalRuns(): LocalRun[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.sessionStorage.getItem(LOCAL_RUNS_KEY)
    if (raw === null) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (item): item is LocalRun =>
        typeof item === 'object' &&
        item !== null &&
        typeof (item as Record<string, unknown>)['runId'] === 'string',
    )
  } catch {
    return []
  }
}

export function DashboardPage(): JSX.Element {
  const { session } = useSession()
  const [localRuns, setLocalRuns] = useState<LocalRun[]>([])

  useEffect(() => {
    setLocalRuns(readLocalRuns())
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

      <Sheet title="本设备发起的辅导" note="只显示这台设备上的记录，不是全部记录。">
        {localRuns.length === 0 ? (
          <Empty
            title="这台设备还没有发起过辅导"
            hint="从上面选「发起辅导」，提交第一道错题。"
            action={
              <Link to="/runs/new" className="btn">
                发起辅导
              </Link>
            }
          />
        ) : (
          <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {localRuns.map((run) => (
              <li key={run.runId} className="entry">
                <span>
                  <Link to={`/runs/${run.runId}`} className="entry__title">
                    查看分析进度
                  </Link>
                  <span className="entry__desc mono">{run.runId}</span>
                </span>
                <time className="muted">{new Date(run.createdAt).toLocaleString('zh-CN')}</time>
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