/**
 * RAG 统计概览（指标卡 + 分布表）。
 *
 * ⚠️ 诚实边界（RAG-R01）：`admissibleSources` 表示「治理状态认为可检索」，
 *    但检索路径目前**尚未强制**准入闸门，因此「可管理」≠「已强制」。
 *    页面顶部如实标注该边界，不用可检索数字冒充检索侧行为。
 *
 * ⚠️ 不引入 ECharts / Recharts：与既有监控页一致的零新依赖纪律，
 *    分布用「统计卡 + 表格」呈现（需要趋势时另开专题）。
 */

import type { ReactNode } from 'react'
import { Alert, Card, Col, Row, Space, Statistic, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type {
  RagDailyImportCount,
  RagDailySearchCount,
} from '@/api/ragTypes'
import { RAG_CHUNK_ROLE_LABELS, RAG_PARSE_STATUS_LABELS, RAG_VERIFICATION_LABELS } from '@/api/ragTypes'
import type { RagStatsState } from '@/features/rag/useRagManagement'

const { Text } = Typography

interface DistributionRow {
  key: string
  label: ReactNode
  count: number
}

function toRows(record: Record<string, number>, labels: Record<string, string>): DistributionRow[] {
  return Object.entries(record ?? {})
    .map(([key, count]) => ({ key, label: labels[key] ?? key, count }))
    .sort((a, b) => b.count - a.count)
}

const distColumns: ColumnsType<DistributionRow> = [
  { title: '分类', dataIndex: 'label', key: 'label' },
  {
    title: '数量',
    dataIndex: 'count',
    key: 'count',
    width: 120,
    render: (c: number) => <Text strong>{c}</Text>,
  },
]

export interface RagStatisticsPanelProps {
  state: RagStatsState
  searches: RagDailySearchCount[]
  imports: RagDailyImportCount[]
  onRefresh: () => void
}

export function RagStatisticsPanel({
  state,
  searches,
  imports,
  onRefresh,
}: RagStatisticsPanelProps): JSX.Element {
  if (state.status === 'loading') {
    return <Card loading title="统计概览" />
  }

  if (state.status === 'error' || state.status === 'timeout') {
    return (
      <Card title="统计概览">
        <Alert
          type={state.status === 'timeout' ? 'warning' : 'error'}
          showIcon
          message={state.status === 'timeout' ? '统计读取超时' : '统计读取失败'}
          description={state.error ?? '未知错误'}
          action={
            <a
              onClick={onRefresh}
              style={{ cursor: 'pointer' }}
            >
              重试
            </a>
          }
        />
      </Card>
    )
  }

  const s = state.stats
  if (!s) {
    return (
      <Card title="统计概览">
        <Alert type="info" showIcon message="请先选择租户并加载统计" />
      </Card>
    )
  }

  const missingRatio = Math.round(s.missingEmbeddingRatio * 1000) / 10

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="warning"
        showIcon
        message="诚实边界：治理状态「可检索」≠ 检索路径已强制"
        description="当前检索路径尚未强制准入闸门（已知限制 RAG-R01）。这里的「可检索」是治理侧结论，实际召回以「检索探针」实测为准。"
      />

      <Row gutter={16}>
        <Col span={6}>
          <Card>
            <Statistic title="语料来源" value={s.totalSources} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="已通过校验"
              value={s.verifiedSources}
              suffix={`/ ${s.totalSources}`}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="当前可检索来源" value={s.admissibleSources} valueStyle={{ color: '#3f8600' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="文档载体" value={s.totalDocuments} />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={8}>
          <Card>
            <Statistic title="片段总数" value={s.totalChunks} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic title="可检索片段" value={s.retrievableChunks} valueStyle={{ color: '#3f8600' }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title="缺向量片段（静默漏召回）"
              value={s.chunksMissingEmbedding}
              suffix={`${missingRatio}%`}
              valueStyle={{ color: s.chunksMissingEmbedding > 0 ? '#cf1322' : undefined }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={8}>
          <Card title="来源按校验状态" size="small">
            <Table<DistributionRow>
              size="small"
              rowKey="key"
              pagination={false}
              columns={distColumns}
              dataSource={toRows(s.sourcesByVerificationStatus, RAG_VERIFICATION_LABELS)}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card title="片段按角色" size="small">
            <Table<DistributionRow>
              size="small"
              rowKey="key"
              pagination={false}
              columns={distColumns}
              dataSource={toRows(s.chunksByRole, RAG_CHUNK_ROLE_LABELS)}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card title="载体按解析状态" size="small">
            <Table<DistributionRow>
              size="small"
              rowKey="key"
              pagination={false}
              columns={distColumns}
              dataSource={toRows(
                s.documentsByParseStatus,
                RAG_PARSE_STATUS_LABELS,
              ).map((r) => ({
                ...r,
                label:
                  r.key === 'FAILED' ? (
                    <Tag color="red">{r.label}</Tag>
                  ) : (
                    r.label
                  ),
              }))}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={12}>
          <Card title="每日检索次数" size="small">
            <Table<RagDailySearchCount>
              size="small"
              rowKey="date"
              pagination={{ pageSize: 8 }}
              columns={[
                { title: '日期', dataIndex: 'date' },
                { title: '检索次数', dataIndex: 'searches', width: 120 },
              ]}
              dataSource={searches}
            />
          </Card>
        </Col>
        <Col span={12}>
          <Card title="每日导入语料量" size="small">
            <Table<RagDailyImportCount>
              size="small"
              rowKey="date"
              pagination={{ pageSize: 8 }}
              columns={[
                { title: '日期', dataIndex: 'date' },
                { title: '来源', dataIndex: 'sources', width: 70 },
                { title: '载体', dataIndex: 'documents', width: 70 },
                { title: '片段', dataIndex: 'chunks', width: 70 },
              ]}
              dataSource={imports}
            />
          </Card>
        </Col>
      </Row>
    </Space>
  )
}
