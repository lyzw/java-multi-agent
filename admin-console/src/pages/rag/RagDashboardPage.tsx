/**
 * RAG 语料管理 · Dashboard（RAG-D13 §3.1 / RAG-D14 §4.1）
 *
 * <p><b>这一页解决什么</b>：把「写面 / 读面 / 治理面」三条链路的健康度收在一屏，
 * 并按治理域给出「下一步该做什么」的入口——替代改造前「一个 /rag 页挂五个 Tab、
 * 用户进去不知道先看哪」的形态（RAG-D12 靶点 G：治理面过重）。
 *
 * <p><b>⚠️ KPI 的口径必须诚实</b>：
 * 「门禁触发率」「答案类抑制数」当前**没有后端聚合端点**，本页只呈现
 * 已有端点能算出的指标（来源数 / 未校验数 / 任务数 / 片段数），
 * 缺失项显示为「—」并注明"待后端提供"，**绝不用 0 冒充**。
 * 0 会被读成"没有发生"，与"没统计"是两件事。
 */

import { useEffect, useMemo } from 'react'
import { Card, Col, Row, Space, Statistic, Tag, Typography } from 'antd'
import { Link } from 'react-router-dom'

import { useRagManagement } from '@/features/rag/useRagManagement'
import { useRagIngestJobs } from '@/features/rag/useRagIngestJobs'
import { RagNotReady, RagScopeShell, useRagScope } from './RagScopeShell'
import {
  GovernanceBadge,
  IngestProgress,
  IngestStateBadge,
  RagEmpty,
  toIngestState,
} from './ragVisuals'

const { Text, Paragraph } = Typography

/** 治理域入口（对应视觉稿的四张入口卡）。 */
const DOMAIN_ENTRIES = [
  {
    key: 'ingest',
    title: '录入与校对',
    desc: '来源登记 · 载体上传解析预览 · 片段校对',
    to: '/rag/sources',
  },
  {
    key: 'review',
    title: '审核',
    desc: '校验决定 · 抽检队列 · 召回解释',
    to: '/rag/verify',
  },
  {
    key: 'rules',
    title: '规则配置',
    desc: '审核员名录 · 知识点别名 · 字典',
    to: '/rag/reviewers',
  },
  {
    key: 'ops',
    title: '运维',
    desc: '摄取任务（三态）· 重索引（蓝绿）',
    to: '/rag/jobs',
  },
] as const

export function RagDashboardPage(): JSX.Element {
  const { target, ready, scopeKey } = useRagScope()
  const { sources, stats, loadSources, loadStats, retrySources, retryStats } = useRagManagement()
  const ingest = useRagIngestJobs(target, { limit: 100 })

  // 作用域变化即重载（scopeKey 才是"该重查"的信号，票据变化不算）。
  useEffect(() => {
    if (!ready) return
    loadSources(target, { includeStale: true, limit: 100 })
    loadStats(target)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scopeKey, ready])

  const latestJobs = useMemo(() => ingest.state.jobs.slice(0, 5), [ingest.state.jobs])

  const pendingSources = useMemo(
    () =>
      sources.sources.filter(
        (s) => s.verificationStatus === 'PENDING' || s.verificationStatus === 'IN_REVIEW',
      ),
    [sources.sources],
  )

  const processingJobs = useMemo(
    () => ingest.state.jobs.filter((j) => toIngestState(j.status) === 'PROCESSING'),
    [ingest.state.jobs],
  )

  return (
    <RagScopeShell
      domain="总览"
      title="Dashboard"
      description="三面健康度一览：写面入库 · 读面检索 · 治理面审核。数字来自已接入端点，未接入的指标显示为「—」。"
      extra={
        <Space>
          <Link to="/rag/sources">
            <Tag color="blue">登记新来源</Tag>
          </Link>
          <Link to="/rag/recall">
            <Tag color="green">打开召回质检</Tag>
          </Link>
        </Space>
      }
    >
      {!ready ? (
        <RagNotReady />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {/* ---- KPI 行 ---- */}
          <Row gutter={12}>
            <Col span={5}>
              <Card size="small">
                <Statistic
                  title="来源总数"
                  value={stats.stats?.totalSources ?? '—'}
                  loading={stats.status === 'loading'}
                />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  其中待审核 {pendingSources.length} 条
                </Text>
              </Card>
            </Col>
            <Col span={5}>
              <Card size="small">
                <Statistic
                  title="待审核来源"
                  value={pendingSources.length}
                  valueStyle={{ color: '#d46b08' }}
                  loading={sources.status === 'loading'}
                />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  PENDING / IN_REVIEW
                </Text>
              </Card>
            </Col>
            <Col span={5}>
              <Card size="small">
                <Statistic
                  title="进行中任务"
                  value={processingJobs.length}
                  valueStyle={{ color: '#1677ff' }}
                  loading={ingest.state.status === 'loading'}
                />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  PROCESSING · 单进度条
                </Text>
              </Card>
            </Col>
            <Col span={4}>
              <Card size="small">
                <Statistic title="可检索片段" value={stats.stats?.retrievableChunks ?? '—'} />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  总片段 {stats.stats?.totalChunks ?? '—'}
                </Text>
              </Card>
            </Col>
            <Col span={5}>
              <Card size="small">
                <Statistic
                  title="缺向量片段"
                  value={stats.stats?.chunksMissingEmbedding ?? '—'}
                  valueStyle={{
                    color: (stats.stats?.chunksMissingEmbedding ?? 0) > 0 ? '#cf1322' : undefined,
                  }}
                />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  缺向量 = 静默漏召回风险
                </Text>
              </Card>
            </Col>
          </Row>

          <Row gutter={12}>
            {/* ---- 治理域入口 ---- */}
            <Col span={10}>
              <Card size="small" title="治理域入口">
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  {DOMAIN_ENTRIES.map((d) => (
                    <Link key={d.key} to={d.to}>
                      <Card size="small" hoverable style={{ width: '100%' }}>
                        <Text strong>{d.title}</Text>
                        <Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 12 }}>
                          {d.desc}
                        </Paragraph>
                      </Card>
                    </Link>
                  ))}
                </Space>
              </Card>
            </Col>

            {/* ---- 最近摄取任务（三态） ---- */}
            <Col span={14}>
              <Card
                size="small"
                title="最近摄取任务（三态）"
                extra={
                  <Link to="/rag/jobs">
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      全部任务 →
                    </Text>
                  </Link>
                }
              >
                {latestJobs.length === 0 ? (
                  <RagEmpty text="暂无摄取任务" />
                ) : (
                  <Space direction="vertical" size={6} style={{ width: '100%' }}>
                    {latestJobs.map((job) => {
                      const state = toIngestState(job.status)
                      return (
                        <div key={job.jobId}>
                          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                            <Space size={8}>
                              <Text strong style={{ fontSize: 12 }}>
                                {job.sourceId.slice(0, 8)}…
                              </Text>
                              <IngestStateBadge state={state} />
                            </Space>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {job.chunkCount != null ? `${job.chunkCount} 片段` : ''}
                            </Text>
                          </Space>
                          <IngestProgress state={state} progress={job.chunkCount} />
                        </div>
                      )
                    })}
                  </Space>
                )}
              </Card>
            </Col>
          </Row>

          {/* ---- 待处理来源 ---- */}
          <Card
            size="small"
            title="待审核来源"
            extra={
              <Link to="/rag/verify">
                <Text type="secondary" style={{ fontSize: 12 }}>
                  去处理 →
                </Text>
              </Link>
            }
          >
            {sources.status === 'loading' ? (
              <RagEmpty text="加载中…" />
            ) : pendingSources.length === 0 ? (
              <RagEmpty text="没有待审核的来源" />
            ) : (
              <Space direction="vertical" size={6} style={{ width: '100%' }}>
                {pendingSources.slice(0, 6).map((s) => (
                  <Space key={s.sourceId} style={{ width: '100%', justifyContent: 'space-between' }}>
                    <Space size={8}>
                      <Text strong style={{ fontSize: 13 }}>
                        {s.title}
                      </Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {s.publisher}
                      </Text>
                    </Space>
                    <Space size={8}>
                      <GovernanceBadge status={s.verificationStatus} />
                      <Link to={`/rag/verify?sourceId=${s.sourceId}`}>
                        <Text style={{ fontSize: 12 }}>处理 →</Text>
                      </Link>
                    </Space>
                  </Space>
                ))}
              </Space>
            )}
          </Card>

          {(sources.status === 'error' || stats.status === 'error') && (
            <Card size="small">
              <Space>
                <Text type="danger">
                  {sources.error ?? stats.error ?? '加载失败'}
                </Text>
                <a onClick={retrySources}>重试列表</a>
                <a onClick={retryStats}>重试统计</a>
              </Space>
            </Card>
          )}
        </Space>
      )}
    </RagScopeShell>
  )
}
