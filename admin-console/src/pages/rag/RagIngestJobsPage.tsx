/**
 * RAG 语料管理 · 摄取任务（RAG-D13 §3.5.1 / RAG-D14 §4.12）
 *
 * <p><b>这一页做什么</b>：查看语料入库任务的**三态**，对失败任务重试。
 *
 * <p><b>靶点 A 的直接承载</b>：改造前任务状态是 8 值枚举
 * （PENDING/PARSING/CHUNKING/EMBEDDING/INDEXING/SUCCEEDED/FAILED/REJECTED），
 * 界面上把"切片中""嵌入中"都摊给用户看。本页收敛为**四态**
 * （SUBMITTED / PROCESSING / READY / FAILED）+ **单进度条**：
 * 用户要判断的是"这册书能不能用"，不是流水线走到哪一步。
 *
 * <p><b>⚠️ 进度百分比的真实来源</b>：后端没有 progress 列，百分比编码在
 * `stage` 字符串尾部（如 `"EMBEDDING 60"`）。本页用 `parseStagePercent` 解析，
 * **解析不出来就显示不确定态**——不编造数字（假百分比比不显示更糟）。
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Input,
  Segmented,
  Space,
  Table,
  Typography,
} from 'antd'

import { ApiError, listRagIngestJobs, retryRagIngestJob } from '@/api/ragClient'
import type { RagIngestJob } from '@/api/ragTypes'
import { parseStagePercent } from './ragUi'
import { RagNotReady, RagScopeShell, type RagScopeBodyParts, type RagScopePageProps } from './RagScopeShell'
import { IngestProgress, IngestStateBadge, RagEmpty, toIngestState } from './ragVisuals'
import type { IngestState } from './ragVisuals'

const { Text, Paragraph } = Typography

type StateFilter = 'ALL' | IngestState

/**
 * RAG · 摄取任务。
 *
 * ⚠️ 用 `body` 形态：页头主操作按钮要绑定本页状态（loading / 弹窗开关等），
 *    而这些状态依赖作用域，只能在 provider 之内声明，故一并写在这个函数里。
 */
export function RagIngestJobsPage(): JSX.Element {
  return (
    <RagScopeShell
      domain="运维"
      title="摄取任务"
      description="查看语料入库任务的三态（已提交 / 处理中 / 已就绪 / 失败），对失败任务重试。只有一条进度条，不展示内部阶段。"

      body={(scope) => RagIngestJobsBody({ scope })}
    />
  )
}

function RagIngestJobsBody({ scope }: RagScopePageProps): RagScopeBodyParts {  const { message } = App.useApp()
  const { target, ready, scopeKey } = scope

  const [jobs, setJobs] = useState<RagIngestJob[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState<StateFilter>('ALL')
  const [sourceId, setSourceId] = useState('')
  const [retrying, setRetrying] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await listRagIngestJobs(target, {
        sourceId: sourceId.trim() || undefined,
        limit: 100,
      })
      setJobs(list)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '读取摄取任务失败')
      setJobs([])
    } finally {
      setLoading(false)
    }
  }, [target, sourceId])

  useEffect(() => {
    if (ready) void load()
  }, [ready, scopeKey, load])

  const filtered = useMemo(() => {
    if (filter === 'ALL') return jobs
    return jobs.filter((j) => toIngestState(j.status) === filter)
  }, [jobs, filter])

  const counts = useMemo(() => {
    const c: Record<IngestState, number> = {
      SUBMITTED: 0,
      PROCESSING: 0,
      READY: 0,
      FAILED: 0,
    }
    for (const j of jobs) c[toIngestState(j.status)] += 1
    return c
  }, [jobs])

  const onRetry = async (job: RagIngestJob): Promise<void> => {
    setRetrying(job.jobId)
    try {
      await retryRagIngestJob(target, job.jobId)
      message.success('已提交重试（新建一次尝试，旧尝试留痕）')
      await load()
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '重试失败')
    } finally {
      setRetrying(null)
    }
  }

  return {
    extra: (
<Button onClick={() => void load()} loading={loading}>
          刷新
        </Button>
    ),
    content: (
      <>
!ready ? (
        <RagNotReady />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Card size="small">
            <Space wrap align="center">
              <Text>状态</Text>
              <Segmented<StateFilter>
                value={filter}
                onChange={(v) => setFilter(v)}
                options={[
                  { value: 'ALL', label: `全部 ${jobs.length}` },
                  { value: 'SUBMITTED', label: `已提交 ${counts.SUBMITTED}` },
                  { value: 'PROCESSING', label: `处理中 ${counts.PROCESSING}` },
                  { value: 'READY', label: `已就绪 ${counts.READY}` },
                  { value: 'FAILED', label: `失败 ${counts.FAILED}` },
                ]}
              />
              <Text>来源 ID</Text>
              <Input
                style={{ width: 300 }}
                placeholder="可选：按来源筛选"
                value={sourceId}
                onChange={(e) => setSourceId(e.target.value.trim())}
                onPressEnter={() => void load()}
              />
            </Space>
          </Card>

          {error && <Alert type="error" showIcon message={error} />}

          <Card size="small">
            <Table<RagIngestJob>
              size="small"
              rowKey="jobId"
              loading={loading}
              dataSource={filtered}
              pagination={{ pageSize: 15, size: 'small' }}
              locale={{ emptyText: <RagEmpty text="没有符合条件的摄取任务" /> }}
              columns={[
                {
                  title: '来源 / 任务',
                  key: 'source',
                  render: (_, j) => (
                    <Space direction="vertical" size={0}>
                      <Text strong style={{ fontSize: 13 }}>
                        {j.sourceId.slice(0, 8)}…
                      </Text>
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        第 {j.attempt} 次尝试
                        {j.chunkCount != null ? ` · ${j.chunkCount} 片段` : ''}
                      </Text>
                    </Space>
                  ),
                },
                {
                  title: '任务三态',
                  key: 'state',
                  width: 110,
                  render: (_, j) => <IngestStateBadge state={toIngestState(j.status)} />,
                },
                {
                  title: '进度 0–100%',
                  key: 'progress',
                  width: 200,
                  render: (_, j) => {
                    const state = toIngestState(j.status)
                    const pct = parseStagePercent(j.stage)
                    return <IngestProgress state={state} progress={pct} />
                  },
                },
                {
                  title: '失败原因 error_detail',
                  dataIndex: 'errorDetail',
                  render: (v: string | null) =>
                    v ? (
                      <Text type="danger" style={{ fontSize: 12 }}>
                        {v}
                      </Text>
                    ) : (
                      <Text type="secondary">—</Text>
                    ),
                },
                {
                  title: '',
                  key: 'actions',
                  width: 90,
                  render: (_, j) =>
                    toIngestState(j.status) === 'FAILED' ? (
                      <Button
                        size="small"
                        type="primary"
                        loading={retrying === j.jobId}
                        onClick={() => void onRetry(j)}
                      >
                        重试
                      </Button>
                    ) : (
                      <Text type="secondary">—</Text>
                    ),
                },
              ]}
            />
          </Card>

          <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
            ⚠️ 「解析 / 切分 / 嵌入 / 索引」四阶段已收敛为单进度条；百分比来自
            <Text code>stage</Text> 字符串尾部（如 <Text code>EMBEDDING 60</Text>），
            解析不出时显示不确定态而非编造数字。
          </Paragraph>

          {filtered.some((j) => j.sourceRevokedCancel) && (
            <Alert
              type="info"
              showIcon
              message="部分任务因来源撤回被取消"
              description="撤回是法务的正常结果，任务显示为「已取消」而非「失败」，不标红。"
            />
          )}
        </Space>
      )
      </>

    ),
  }
}

