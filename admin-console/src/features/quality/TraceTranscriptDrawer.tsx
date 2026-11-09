import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import { ApiError } from '@/api/client'
import { fetchPlatformTranscript, type TranscriptResponse } from '@/api/traceReviewApi'
import { ToolOperationDetails } from '@/features/quality/ToolOperationDetails'
import {
  messageBody,
  renderDuration,
  renderOptionalNumber,
  renderStamp,
  renderTokens,
  transcriptCaveats,
  ROLE_COLORS,
  ROLE_LABELS,
  NOT_COLLECTED,
} from '@/features/quality/transcriptPresentation'

/**
 * 对话回放抽屉（P1 · G1 闭环的审查侧界面）。
 *
 * ⚠️ **只走平台链端点**：后端先过 `platform.resource.read`，再按 runId 反查租户，
 *    并以 ADMIN 受众返回完整时间线（含 TOOL）。租户侧端点面向学生、隐藏 TOOL，
 *    不能拿来做审查。
 *
 * ⚠️ **「没有消息」不是「没有对话」**：P1 之前的 Run 没有埋点，或埋点写入失败
 *    被 fail-soft 吞掉，都会得到空时间线。界面必须明确说「未采集」，
 *    否则审查者会把证据缺失误读成「这次辅导没说什么」。
 */

interface Props {
  runId: string
  /** 已知租户时显式带上：后端可跳过「仅凭 runId 反查」（未采集指标的 Run 反查不到）。 */
  tenantId?: string
  open: boolean
  onClose: () => void
}

function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 404) {
      // ⚠️ 全局异常处理器对 404 统一返回「资源不存在」（防归属信息泄露），
      //    后端那段「无法仅凭 runId 定位租户」的诊断 reason 不会透到前端。
      //    因此可操作指引必须在这里补：否则审查者会以为「数据没了」而放弃，
      //    真因其实只是缺 tenantId 或该 Run 尚未采集指标。
      return `回放不可用（404 · ${e.message}）：Run 不存在，或平台侧无法仅凭 runId `
        + '定位所属租户（该 Run 可能尚未采集指标）。请从审查队列 / 指标明细进入回放，'
        + '这两个入口会自动带上 tenantId。'
    }
    if (e.code === 'ACCESS_DENIED') {
      return '当前平台账号缺少 platform.resource.read，无权读取对话回放'
    }
    return e.message
  }
  return e instanceof Error ? e.message : String(e)
}

export function TraceTranscriptDrawer({ runId, tenantId, open, onClose }: Props): JSX.Element {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [data, setData] = useState<TranscriptResponse | null>(null)

  useEffect(() => {
    if (!open || runId === '') return
    let cancelled = false
    setLoading(true)
    setError(null)
    setData(null)
    void fetchPlatformTranscript(runId, tenantId)
      .then((t) => {
        if (!cancelled) setData(t)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(describeError(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [open, runId, tenantId])

  const caveats = data === null ? [] : transcriptCaveats(data)

  return (
    <Drawer
      title="对话回放 · 内容级复盘"
      width={820}
      open={open}
      onClose={onClose}
      destroyOnHidden
      extra={<Button size="small" onClick={onClose}>关闭</Button>}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message="平台审查视图"
          description="此视图含 TOOL 消息，仅平台授权账号可见；学生端回放会隐藏 TOOL。回放只呈现事实，不给出「回答是否正确」的判分。"
        />
        {loading && <Spin tip="加载回放…" />}
        {error !== null && (
          <Alert type="error" showIcon message="读取回放失败" description={error} />
        )}
        {data !== null && (
          <>
            <Descriptions size="small" column={2} bordered items={[
              { key: 'run', label: 'Run ID', children: data.runId, span: 2 },
              { key: 'trace', label: 'Trace ID', children: renderStamp(data.traceId), span: 2 },
              { key: 'state', label: '终态', children: data.state },
              { key: 'dur', label: '耗时', children: renderDuration(data.durationMs) },
              { key: 'start', label: '开始', children: renderStamp(data.createdAt) },
              { key: 'end', label: '结束', children: renderStamp(data.completedAt) },
              { key: 'tokens', label: 'Token / 成本', children: renderTokens(data.tokens), span: 2 },
              {
                key: 'steps',
                label: '执行步骤',
                children: `${data.steps.length} 步`,
              },
              {
                key: 'msgs',
                label: '消息数',
                children: `${data.messages.length} 条`,
              },
            ]} />

            {caveats.length > 0 && (
              <Alert
                type="warning"
                showIcon
                message="回放事实完整性提示（不代表判分结论）"
                description={
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {caveats.map((c) => <li key={c}>{c}</li>)}
                  </ul>
                }
              />
            )}

            <Typography.Title level={5} style={{ margin: '8px 0 0' }}>
              对话时间线
            </Typography.Title>
            {data.messages.length === 0 ? (
              <Alert
                type="warning"
                showIcon
                message="未采集到对话消息"
                description="这可能是 P1 之前的旧 Run，或埋点写入失败（写入失败只记 WARN、不影响 Run 终态）。绝不能据此认定「这次辅导没有对话」。"
              />
            ) : (
              data.messages.map((m) => {
                const body = messageBody(m)
                return (
                  <Card
                    key={m.seq}
                    size="small"
                    title={
                      <Space size={6} wrap>
                        <Tag color={ROLE_COLORS[m.role] ?? 'default'}>
                          {ROLE_LABELS[m.role] ?? m.role}
                        </Tag>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          #{m.seq} · 步骤 {renderOptionalNumber(m.stepNumber)} · {renderStamp(m.createdAt)}
                        </Typography.Text>
                        {m.modelId !== null && (
                          <Tag>{m.modelId}</Tag>
                        )}
                      </Space>
                    }
                  >
                    <Typography.Paragraph
                      style={{ whiteSpace: 'pre-wrap', marginBottom: body.notes.length > 0 ? 8 : 0 }}
                    >
                      {body.text === '' ? `（${NOT_COLLECTED}：正文为空）` : body.text}
                    </Typography.Paragraph>
                    {body.notes.length > 0 && (
                      <Space direction="vertical" size={2}>
                        {body.notes.map((n) => (
                          <Typography.Text key={n} type="secondary" style={{ fontSize: 12 }}>
                            {n}
                          </Typography.Text>
                        ))}
                      </Space>
                    )}
                    {/* ⚠️ 只有 TOOL 行才可能下钻到 tool_operation 原始参数 */}
                    {m.role === 'TOOL' && (
                      <div style={{ marginTop: 8 }}>
                        <ToolOperationDetails operationId={m.toolOperationId} tenantId={tenantId} />
                      </div>
                    )}
                  </Card>
                )
              })
            )}

            {data.steps.length > 0 && (
              <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8 }}>
                执行步骤：
                {data.steps
                  .map((s) => `#${s.stepNumber} ${s.type}（${renderDuration(s.durationMs)}）`)
                  .join(' · ')}
              </Typography.Paragraph>
            )}
          </>
        )}
      </Space>
    </Drawer>
  )
}
