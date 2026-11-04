/**
 * RAG 语料管理 · 来源列表（RAG-D13 §3.2.2 / RAG-D14 §4.3）
 *
 * <p><b>这一页做什么</b>：浏览/筛选全部来源，快速定位治理状态与生命周期。
 *
 * <p><b>与改造前的关系</b>：列表主体复用已有的 `RagSourceTable`（它已实现筛选、
 * 处理状态列、元数据确认入口），本页只做三件事：
 * <ol>
 *   <li>把「作用域选择」从页面内联提升到统一外壳（{@link RagScopeShell}），
 *       避免每个页面各写一份；</li>
 *   <li>把详情从「抽屉」保留（抽屉在语义上就是"行详情"，比跳转页更贴近列表心智）；</li>
 *   <li>注册入口改为跳转到独立的「来源登记」页——视觉稿把登记作为独立页面（§3.2.1）。</li>
 * </ol>
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Card, Space, Typography } from 'antd'
import { Link, useNavigate } from 'react-router-dom'

import type { RagSource, RagSourceQuery, RagVerificationRequest } from '@/api/ragTypes'
import { useRagManagement } from '@/features/rag/useRagManagement'
import { useRagIngestJobs } from '@/features/rag/useRagIngestJobs'
import { RagSourceTable } from './RagSourceTable'
import { RagSourceDrawer } from './RagSourceDrawer'
import { RagMetadataConfirmDrawer } from './RagMetadataConfirmDrawer'
import { RagNotReady, RagScopeShell, useRagScope } from './RagScopeShell'

export function RagSourceListPage(): JSX.Element {
  const { target, ready, scopeKey } = useRagScope()
  const navigate = useNavigate()

  const [selected, setSelected] = useState<RagSource | null>(null)
  const [confirmTarget, setConfirmTarget] = useState<RagSource | null>(null)

  const {
    sources,
    action,
    loadSources,
    verify,
    retrySources,
    resetAction,
    resetAll,
  } = useRagManagement()

  // 作用域变化 → 清空上一档的数据与选中项（防止治理结论张冠李戴）。
  useEffect(() => {
    setSelected(null)
    resetAll()
  }, [scopeKey, resetAll])

  // 作用域就绪即首次加载（readKey 语义：只随作用域变化，不随票据变化）。
  useEffect(() => {
    if (!ready) return
    loadSources(target, { includeStale: true, limit: 100 })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scopeKey, ready])

  const ingest = useRagIngestJobs(target, { limit: 100 })

  /** sourceId → 最近一次任务（后端已按时间倒序返回，取首条即最新）。 */
  const jobsBySource = useMemo(() => {
    const map = new Map<string, (typeof ingest.state.jobs)[number]>()
    for (const job of ingest.state.jobs) {
      if (!map.has(job.sourceId)) map.set(job.sourceId, job)
    }
    return map
  }, [ingest.state.jobs])

  const onQuery = useCallback(
    (query: RagSourceQuery) => loadSources(target, query),
    [target, loadSources],
  )

  const onVerified = useCallback(
    (sourceId: string, payload: RagVerificationRequest) => verify(target, sourceId, payload),
    [target, verify],
  )

  return (
    <RagScopeShell
      domain="录入与校对"
      title="来源列表"
      description="浏览与筛选全部来源，快速定位治理状态与生命周期。登记只是纳入治理，不等于可检索；撤回是终态、无删除入口。"
      extra={
        <Link to="/rag/sources/new">
          <Card size="small" hoverable style={{ padding: '2px 8px' }}>
            <span style={{ fontSize: 13 }}>＋ 登记新来源</span>
          </Card>
        </Link>
      }
    >
      {!ready ? (
        <RagNotReady />
      ) : (
        <Card size="small">
          <RagSourceTable
            state={sources}
            target={target}
            jobsBySource={jobsBySource}
            onQuery={onQuery}
            onRefresh={retrySources}
            onSelect={setSelected}
            onRegister={() => navigate('/rag/sources/new')}
            onConfirmMetadata={(row) => setConfirmTarget(row)}
          />

          <RagMetadataConfirmDrawer
            source={confirmTarget}
            target={target}
            onClose={() => setConfirmTarget(null)}
            onConfirmed={() => retrySources()}
          />

          <RagSourceDrawer
            source={selected}
            target={target}
            onClose={() => {
              setSelected(null)
              resetAction()
            }}
            onVerified={onVerified}
            actionStatus={action.status}
            actionMessage={action.message}
            updatedSource={action.updated}
          />

          <Space style={{ marginTop: 8 }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              共 {sources.sources.length} 条（当前筛选条件）
            </Typography.Text>
          </Space>
        </Card>
      )}
    </RagScopeShell>
  )
}
