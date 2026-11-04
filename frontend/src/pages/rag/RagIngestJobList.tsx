/**
 * 摄取任务列表（V1.0.2 S1-7 / 设计 §8.4「我的任务列表」最小版）。
 *
 * <p><b>范围声明</b>：本组件只做 S1 的最小可用形态——
 * 「看得见进度、看得见失败原因、能重试」。
 * 四区完整工作台（来源登记向导 / 上传区 / 抽检台）属 S3，此处不实现。
 *
 * <p><b>⚠️ 状态渲染的三条纪律</b>（沿用既有前端约定，不得退化）：
 * <ol>
 *   <li><b>只渲染合法动作</b>：终态才有「重试」，运行中不提供任何按钮；</li>
 *   <li><b>撤回取消 ≠ 失败</b>：{@code sourceRevokedCancel} 为真时显示为「已取消」中性色，
 *       不标红——那是法务撤回的正常结果，不是文件出错（后端 N2 语义）；</li>
 *   <li><b>不提供删除</b>：管理面没有删除端点，UI 也不得暗示可以删。</li>
 * </ol>
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import {
  getRagIngestJob,
  listRagIngestJobs,
  retryRagIngestJob,
} from '@/api/client'
import type { RagIngestJob, RagIngestJobStatus } from '@/api/types'

/** 轮询周期：2 秒。与后端 worker 的消费轮询同量级，够快也不打爆管理面。 */
const POLL_INTERVAL_MS = 2000

/** 轮询上限：超过则停止（防止用户离开页面后无限轮询）。 */
const MAX_POLLS = 150

/**
 * 状态 → 展示文案与**显式**类名。
 *
 * ⚠️ 类名写成完整字面量而非 {@code `tone-${x}`} 拼接：
 *    前端 CSS 完整性守卫测试（css.integrity.test.ts）静态扫描源码，
 *    模板拼接会被解析成字面量 "tone-"，从而误报"类名未定义"。
 *    显式写法同时也让"哪个状态用哪个色阶"一眼可读。
 */
const STATUS_VIEW: Record<RagIngestJobStatus, { label: string; className: string }> = {
  PENDING: { label: '排队中', className: 'tone-muted' },
  PARSING: { label: '解析中', className: 'tone-busy' },
  CHUNKING: { label: '切分中', className: 'tone-busy' },
  EMBEDDING: { label: '嵌入中', className: 'tone-busy' },
  INDEXING: { label: '索引中', className: 'tone-busy' },
  SUCCEEDED: { label: '已完成', className: 'tone-ok' },
  FAILED: { label: '失败', className: 'tone-error' },
  REJECTED: { label: '已取消', className: 'tone-muted' },
}

/** 运行中状态集合（用于决定是否继续轮询）。 */
const IN_FLIGHT: ReadonlySet<RagIngestJobStatus> = new Set([
  'PENDING',
  'PARSING',
  'CHUNKING',
  'EMBEDDING',
  'INDEXING',
])

export interface RagIngestJobListProps {
  tenantId: string
  /** 只看某来源的任务；不传则看本租户全部 */
  sourceId?: string
}

export function RagIngestJobList({ tenantId, sourceId }: RagIngestJobListProps) {
  const [jobs, setJobs] = useState<RagIngestJob[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const pollsRef = useRef(0)

  const refresh = useCallback(async () => {
    if (!tenantId) {
      return
    }
    try {
      const next = await listRagIngestJobs(tenantId, { sourceId, limit: 50 })
      setJobs(next)
      setError(null)
    } catch (e) {
      // ⚠️ 轮询失败不打断界面：保留上一次的任务快照，
      //    只有首次加载失败才显示错误（否则网络抖动会让列表闪烁消失）。
      if (jobs.length === 0) {
        setError(e instanceof Error ? e.message : '加载任务列表失败')
      }
    }
  }, [tenantId, sourceId, jobs.length])

  // 首次加载
  useEffect(() => {
    void refresh()
  }, [tenantId, sourceId])

  /**
   * 有进行中任务时轮询**进行中的那几条**。
   *
   * ⚠️ 依赖里放的是「进行中的 jobId 集合」而不是 `jobs` 数组本身：
   *    此前依赖 `jobs`，每次 `setJobs` 都会清掉并重建 `setInterval`——
   *    定时器被反复重置，实际周期漂移，且在高频更新下几乎不会触发。
   *    用稳定的 id 串做依赖，定时器只在「进行中的集合」真的变化时重建。
   *
   * ⚠️ 只拉详情、不拉列表：列表接口会因新任务插入而重排，
   *    而这里只需要「这几条变了没有」，逐条取更稳定（也不会引起整表跳动）。
   */
  const activeJobIds = useMemo(
    () =>
      jobs
        .filter((j) => IN_FLIGHT.has(j.status))
        .map((j) => j.jobId)
        .sort()
        .join(','),
    [jobs],
  )

  useEffect(() => {
    if (!tenantId || activeJobIds === '') {
      pollsRef.current = 0
      return
    }
    const ids = activeJobIds.split(',')
    const timer = window.setInterval(() => {
      if (pollsRef.current >= MAX_POLLS) {
        window.clearInterval(timer)
        return
      }
      // ⚠️ 页签隐藏时不打后端（与 useRagIngestJobs 同一纪律）
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
        return
      }
      pollsRef.current += 1
      void getJobsByIds(tenantId, ids)
        .then((updated) => {
          // ⚠️ 只更新进行中的那几条，避免整表重排导致视觉跳动。
          if (updated.size > 0) {
            setJobs((prev) => prev.map((j) => updated.get(j.jobId) ?? j))
          }
        })
        .catch(() => {
          /* 轮询单次失败静默，下一轮再试 */
        })
    }, POLL_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [activeJobIds, tenantId])

  const onRetry = useCallback(
    async (job: RagIngestJob) => {
      setLoading(true)
      try {
        await retryRagIngestJob(tenantId, job.jobId)
        await refresh()
      } catch (e) {
        setError(e instanceof Error ? e.message : '重试失败')
      } finally {
        setLoading(false)
      }
    },
    [tenantId, refresh],
  )

  if (!tenantId) {
    return <p className="rag-ingest-hint">请先选择租户。</p>
  }
  if (error) {
    return <p className="rag-ingest-error">{error}</p>
  }
  if (jobs.length === 0) {
    return <p className="rag-ingest-hint">暂无摄取任务。上传文件后会自动创建。</p>
  }

  return (
    <div className="rag-ingest-jobs">
      <table>
        <thead>
          <tr>
            <th>任务</th>
            <th>状态</th>
            <th>进度</th>
            <th>片段数</th>
            <th>尝试</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {jobs.map((job) => {
            const view = STATUS_VIEW[job.status]
            // ⚠️ 撤回取消显示为「已取消 · 来源已撤回」，而非失败原因，避免误读。
            const detail = job.sourceRevokedCancel
              ? '来源已撤回，任务随之下线'
              : job.errorDetail
            return (
              <tr key={job.jobId} className={view.className}>
                <td title={job.jobId}>{job.jobId.slice(0, 8)}</td>
                <td>{view.label}</td>
                <td>{job.stage ?? '—'}</td>
                <td>{job.chunkCount ?? '—'}</td>
                <td>{job.attempt + 1}</td>
                <td>
                  {/* ⚠️ 仅终态可重试：运行中重试会产生两个并行 job，导致片段重复落库。 */}
                  {!IN_FLIGHT.has(job.status) && !job.sourceRevokedCancel ? (
                    <button
                      type="button"
                      disabled={loading}
                      onClick={() => void onRetry(job)}
                    >
                      重试
                    </button>
                  ) : (
                    '—'
                  )}
                  {detail ? (
                    <div className="rag-ingest-detail">{detail}</div>
                  ) : null}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

/**
 * 批量拉取进行中的 job 详情。
 *
 * <p>⚠️ 刻意不用列表接口轮询：列表接口会重排（新任务插入），
 * 而这里只需要「这几条变了没有」，用详情接口逐条取更稳定。
 */
async function getJobsByIds(
  tenantId: string,
  jobIds: readonly string[],
): Promise<Map<string, RagIngestJob>> {
  const result = new Map<string, RagIngestJob>()
  await Promise.all(
    jobIds.map(async (jobId) => {
      try {
        result.set(jobId, await getRagIngestJob(tenantId, jobId))
      } catch {
        /* 单条失败不影响其它 */
      }
    }),
  )
  return result
}
