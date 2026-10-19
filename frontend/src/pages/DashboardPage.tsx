/**
 * 工作台 P2（FE-D2 §0.1 —— 原版仅 44 行属骨架级，本次重做）
 *
 * ⛔ **不做全局任务列表**：
 *   后端无 Run 列表接口（G7），也无分页契约。
 *   用 localStorage 冒充「任务列表」会让用户以为看到全部任务，
 *   而实际只看到本设备发起的 —— 那是**静默误导**（S4）。
 *
 * ✅ 本页改为：清晰入口 + 本设备会话记录（**明示范围**）+ 缺口说明。
 */

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useSession } from '@/auth/session'

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
      <h1>工作台</h1>

      <section className="quick-actions">
        <h2>开始</h2>
        <div className="quick-actions__grid">
          <Link to="/runs/new" className="action-card action-card--primary">
            <span className="action-card__title">发起新的辅导</span>
            <span className="action-card__desc">
              提交一道错题，系统分析归因并给出解题步骤
            </span>
          </Link>

          <Link to="/runs" className="action-card">
            <span className="action-card__title">检索运行记录</span>
            <span className="action-card__desc">按状态、学科检索历史运行</span>
          </Link>

          <Link to="/profile/current" className="action-card">
            <span className="action-card__title">查看学习画像</span>
            <span className="action-card__desc">薄弱点清单与错因分布</span>
          </Link>
        </div>
      </section>

      {/*
        ⚠️ 诚实点：这一节**明确标注范围**为「本设备」。
        不标注的话，用户会以为这是全部任务。
      */}
      <section className="local-runs">
        <h2>本设备发起的运行</h2>
        {localRuns.length === 0 ? (
          <>
            <p className="muted">本设备暂无记录。</p>
            <p className="muted warn-inline">
              ⚠️ 此处仅显示本设备本次会话发起的运行，
              <b>不是</b>全部任务 —— 后端尚未提供列表接口（G7）。
            </p>
          </>
        ) : (
          <ul className="local-runs__list">
            {localRuns.map((run) => (
              <li key={run.runId}>
                <Link to={`/runs/${run.runId}`}>查看进度</Link>
                <code>{run.runId}</code>
                <time>{new Date(run.createdAt).toLocaleString('zh-CN')}</time>
              </li>
            ))}
          </ul>
        )}
      </section>

      {session !== null ? (
        <p className="muted">会话由服务端控制有效期，过期后将自动跳转登录页。</p>
      ) : null}
    </>
  )
}