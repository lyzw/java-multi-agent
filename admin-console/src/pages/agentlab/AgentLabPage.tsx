/**
 * AI 实验室 · Agent 效果测试。
 *
 * <p><b>用途</b>：挑一个 Agent，喂一条用例，发起一次<b>真实</b>执行，
 *    观察它的状态流转、耗时、重试次数与失败原因码，用于效果验收与回归比对。
 *
 * <p><b>⚠️ 诚实边界（写在这里，不藏在文档里）</b>：
 * <ol>
 *   <li><b>通道</b>：`/api/runs` 是租户域端点，租户来自验签令牌。
 *       admin-console 持平台令牌，对租户域端点可能 401/403。
 *       此时页面明示「需租户级令牌通道」，<b>不</b>伪装成执行成功。</li>
 *   <li><b>看不到输出正文</b>：`GET /api/runs/{id}` 只回状态与失败原因码，
 *       <b>不含</b>执行输出（禁区红线：Agent 定义正文与运行输出不出现在平台控制台）。
 *       效果判定只能基于状态 / 耗时 / 错误码，不能基于输出内容。</li>
 *   <li><b>轮询而非 SSE</b>：`EventSource` 无法带 Authorization 头，
 *       在后端提供一次性票证前，轮询是不牺牲凭据安全的唯一做法。</li>
 * </ol>
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { getPlatformTenants } from '@/api/adminApi'
import { getConsoleAgentRegistry } from '@/api/adminApi'
import type { PlatformAgentRegistryItem, PlatformTenant } from '@/api/types'
import {
  createRun,
  getRunStatus,
  isRateLimited,
  isRejectedInput,
  isTenantChannelBlocked,
  isTerminal,
  RUN_STATE_COLORS,
  RUN_STATE_LABELS,
  type RunState,
  type RunStatus,
} from '@/api/agentLabApi'
import { ApiError } from '@/api/client'

const { Text, Paragraph } = Typography

/** 一次执行记录（本地会话内保留，用于同 Agent 多次比对）。 */
interface RunRecord {
  key: string
  runId: string
  agentId: string
  agentName: string
  input: string
  state: RunState
  createdAt: string
  completedAt: string | null
  attempt: number
  errorCode: string | null
  elapsedMs: number | null
}

const POLL_INTERVAL_MS = 2000
const MAX_POLLS = 150

function newIdempotencyKey(): string {
  return `lab-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export function AgentLabPage(): JSX.Element {
  const [tenants, setTenants] = useState<PlatformTenant[]>([])
  const [tenantId, setTenantId] = useState('')
  const [agents, setAgents] = useState<PlatformAgentRegistryItem[]>([])
  const [agentsError, setAgentsError] = useState<string | null>(null)
  const [agentId, setAgentId] = useState('')

  const [input, setInput] = useState('')
  const [timeoutMs, setTimeoutMs] = useState(30000)
  const [budgetAmount, setBudgetAmount] = useState<number | null>(null)

  const [submitting, setSubmitting] = useState(false)
  const [current, setCurrent] = useState<RunRecord | null>(null)
  const [history, setHistory] = useState<RunRecord[]>([])
  const [errorKind, setErrorKind] = useState<'none' | 'channel' | 'ratelimit' | 'input' | 'other'>(
    'none',
  )
  const [errorText, setErrorText] = useState<string | null>(null)
  const [trace, setTrace] = useState<{ at: string; state: RunState }[]>([])

  const pollTimer = useRef<number | null>(null)

  const stopPolling = useCallback(() => {
    if (pollTimer.current !== null) {
      window.clearInterval(pollTimer.current)
      pollTimer.current = null
    }
  }, [])

  useEffect(() => stopPolling, [stopPolling])

  useEffect(() => {
    let cancelled = false
    const run = async (): Promise<void> => {
      try {
        const list = await getPlatformTenants({ limit: 200 })
        if (!cancelled) setTenants(list)
      } catch {
        /* 租户列表失败不阻断：Agent 选择可不带租户过滤 */
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    const run = async (): Promise<void> => {
      setAgentsError(null)
      try {
        const list = await getConsoleAgentRegistry(tenantId ? { tenantId } : undefined)
        if (cancelled) return
        setAgents(list)
      } catch (cause) {
        if (cancelled) return
        setAgents([])
        setAgentsError(cause instanceof ApiError ? cause.message : '读取 Agent 注册表失败')
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [tenantId])

  const agentOptions = useMemo(
    () =>
      agents.map((a) => ({
        value: a.agentId,
        label:
          `${a.name ?? a.agentId}` +
          (a.latestVersionNumber ? ` · v${a.latestVersionNumber}` : '') +
          (a.lifecycleState ? ` · ${a.lifecycleState}` : ''),
      })),
    [agents],
  )

  const selectedAgent = useMemo(
    () => agents.find((a) => a.agentId === agentId) ?? null,
    [agents, agentId],
  )

  const toRecord = (status: RunStatus, agent: string, agentName: string, prompt: string): RunRecord => {
    const created = new Date(status.createdAt).getTime()
    const completed = status.completedAt ? new Date(status.completedAt).getTime() : null
    return {
      key: status.runId,
      runId: status.runId,
      agentId: agent,
      agentName,
      input: prompt,
      state: status.state,
      createdAt: status.createdAt,
      completedAt: status.completedAt,
      attempt: status.attempt,
      errorCode: status.errorCode,
      elapsedMs: completed === null ? null : completed - created,
    }
  }

  const poll = useCallback(
    (runId: string, agent: string, agentName: string, prompt: string) => {
      stopPolling()
      let n = 0
      pollTimer.current = window.setInterval(() => {
        n += 1
        if (n > MAX_POLLS) {
          stopPolling()
          setErrorKind('other')
          setErrorText('轮询已停止：超过最大等待次数，请在运行列表确认最终状态。')
          return
        }
        void (async () => {
          try {
            const status = await getRunStatus(runId)
            setTrace((prev) => [...prev, { at: new Date().toLocaleTimeString(), state: status.state }])
            const record = toRecord(status, agent, agentName, prompt)
            setCurrent(record)
            if (isTerminal(status.state)) {
              stopPolling()
              setHistory((prev) => [record, ...prev.filter((r) => r.runId !== runId)])
            }
          } catch (cause) {
            // ⚠️ 轮询中的 401/403 同样要停：继续轮询只是刷日志
            if (isTenantChannelBlocked(cause)) {
              stopPolling()
              setErrorKind('channel')
              setErrorText('查询状态时被拒绝（401/403）：平台令牌对租户域端点不可达。')
            }
          }
        })()
      }, POLL_INTERVAL_MS)
    },
    [stopPolling],
  )

  const execute = async (): Promise<void> => {
    setErrorKind('none')
    setErrorText(null)
    setTrace([])
    setCurrent(null)
    setSubmitting(true)
    try {
      const res = await createRun({
        agentId,
        input,
        timeoutMs,
        budgetAmount,
        idempotencyKey: newIdempotencyKey(),
      })
      const record: RunRecord = {
        key: res.runId,
        runId: res.runId,
        agentId,
        agentName: selectedAgent?.name ?? agentId,
        input,
        state: 'QUEUED',
        createdAt: new Date().toISOString(),
        completedAt: null,
        attempt: 0,
        errorCode: null,
        elapsedMs: null,
      }
      setCurrent(record)
      setTrace([{ at: new Date().toLocaleTimeString(), state: 'QUEUED' }])
      poll(res.runId, agentId, record.agentName, input)
    } catch (cause) {
      if (isTenantChannelBlocked(cause)) {
        setErrorKind('channel')
        setErrorText('平台令牌对租户域端点 /api/runs 不可达（401/403）。')
      } else if (isRateLimited(cause)) {
        setErrorKind('ratelimit')
        setErrorText('触发限流（429）。后端带 Retry-After: 60，请一分钟后重试——这不是参数错误。')
      } else if (isRejectedInput(cause)) {
        setErrorKind('input')
        setErrorText('输入被拒绝（400）：可能是内容合规拦截或 Prompt 防护判定超限。请缩短或改写输入。')
      } else {
        setErrorKind('other')
        setErrorText(cause instanceof ApiError ? cause.message : '发起执行失败')
      }
    } finally {
      setSubmitting(false)
    }
  }

  const historyColumns: ColumnsType<RunRecord> = [
    { title: '执行 ID', dataIndex: 'runId', width: 200, render: (v: string) => <Text code copyable>{v}</Text> },
    { title: 'Agent', dataIndex: 'agentName', width: 160, ellipsis: true },
    {
      title: '状态',
      dataIndex: 'state',
      width: 100,
      render: (s: RunState) => (
        <Tag color={RUN_STATE_COLORS[s]}>{RUN_STATE_LABELS[s] ?? s}</Tag>
      ),
    },
    {
      title: '耗时',
      dataIndex: 'elapsedMs',
      width: 100,
      render: (v: number | null) => (v === null ? '—' : `${v} ms`),
    },
    { title: '重试', dataIndex: 'attempt', width: 70 },
    {
      title: '失败码',
      dataIndex: 'errorCode',
      width: 140,
      render: (v: string | null) =>
        v ? <Tag color="red">{v}</Tag> : <Text type="secondary">—</Text>,
    },
    { title: '用例', dataIndex: 'input', ellipsis: true },
  ]

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <div>
        <Typography.Title level={4} style={{ marginBottom: 4 }}>
          Agent 效果测试
        </Typography.Title>
        <Paragraph type="secondary" style={{ marginBottom: 0 }}>
          挑一个 Agent，喂一条用例，发起一次真实执行，观察状态流转、耗时、重试次数与失败原因码。
        </Paragraph>
      </div>

      <Alert
        type="warning"
        showIcon
        message="这条链路看得到什么、看不到什么"
        description={
          <>
            1）<b>看不到输出正文</b>：状态查询只回状态与失败原因码，执行输出不出现在平台控制台（禁区红线）。
            效果判定只能基于状态 / 耗时 / 错误码。
            <br />
            2）<b>通道</b>：<code>/api/runs</code> 是租户域端点，平台令牌可能 401/403；
            此时会明确提示，不伪装成成功。
            <br />
            3）<b>合规先于执行</b>：内容合规、Prompt 防护、执行时授权、限流依次发生在创建之前，
            被拦下是<b>设计如此</b>，不是故障。
          </>
        }
      />

      {errorKind === 'channel' && (
        <Alert type="error" showIcon message="通道不可达（401/403）" description={errorText} />
      )}
      {errorKind === 'ratelimit' && (
        <Alert type="warning" showIcon message="被限流（429）" description={errorText} />
      )}
      {errorKind === 'input' && (
        <Alert type="warning" showIcon message="输入被拒绝（400）" description={errorText} />
      )}
      {errorKind === 'other' && <Alert type="error" showIcon message="执行失败" description={errorText} />}
      {agentsError && <Alert type="error" showIcon message="读取 Agent 注册表失败" description={agentsError} />}

      <Card title="执行参数" size="small">
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space wrap>
            <Text>租户（可选过滤）</Text>
            <Select
              showSearch
              allowClear
              style={{ width: 300 }}
              placeholder="按租户过滤 Agent 注册表"
              value={tenantId || undefined}
              onChange={(v) => setTenantId(v ?? '')}
              options={tenants.map((t) => ({ value: t.tenantId, label: `${t.name}（${t.slug}）` }))}
              filterOption={(inp, opt) =>
                String(opt?.label ?? '').toLowerCase().includes(inp.toLowerCase())
              }
            />
          </Space>

          <Space wrap>
            <Text>Agent</Text>
            <Select
              showSearch
              style={{ width: 420 }}
              placeholder="选择要测试的 Agent"
              value={agentId || undefined}
              onChange={setAgentId}
              options={agentOptions}
              filterOption={(inp, opt) =>
                String(opt?.label ?? '').toLowerCase().includes(inp.toLowerCase())
              }
            />
          </Space>

          {selectedAgent && (
            <Descriptions size="small" bordered column={3}>
              <Descriptions.Item label="租户">
                <Text code>{selectedAgent.tenantId}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="定义版本">
                {selectedAgent.defVersion ?? <Text type="secondary">无定义</Text>}
              </Descriptions.Item>
              <Descriptions.Item label="生命周期">
                {selectedAgent.lifecycleState ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="最新发布版本">
                {selectedAgent.latestVersionNumber ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="启用技能" span={2}>
                {selectedAgent.enabledSkills.length === 0 ? (
                  <Text type="secondary">—</Text>
                ) : (
                  selectedAgent.enabledSkills.map((s) => (
                    <Tag key={s} color="blue">
                      {s}
                    </Tag>
                  ))
                )}
              </Descriptions.Item>
            </Descriptions>
          )}

          <Input.TextArea
            rows={3}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="用例输入（会被内容合规与 Prompt 防护校验：长度 / 深度 / 元素数都有上界）"
          />

          <Space wrap>
            <Text>超时（ms）</Text>
            <InputNumber
              min={1000}
              max={600000}
              step={1000}
              value={timeoutMs}
              onChange={(v) => setTimeoutMs(v ?? 30000)}
            />
            <Text>预算上限</Text>
            <InputNumber
              min={0}
              step={1}
              value={budgetAmount}
              onChange={(v) => setBudgetAmount(v ?? null)}
              placeholder="留空=不限"
            />
            <Button
              type="primary"
              loading={submitting}
              disabled={agentId === '' || input.trim() === ''}
              onClick={() => void execute()}
            >
              发起执行
            </Button>
          </Space>
        </Space>
      </Card>

      {current && (
        <Card title="本次执行" size="small">
          <Row gutter={16}>
            <Col span={6}>
              <Statistic
                title="状态"
                valueRender={() => (
                  <Tag color={RUN_STATE_COLORS[current.state]} style={{ fontSize: 16 }}>
                    {RUN_STATE_LABELS[current.state] ?? current.state}
                  </Tag>
                )}
                value=""
              />
            </Col>
            <Col span={6}>
              <Statistic title="耗时" value={current.elapsedMs ?? '—'} suffix={current.elapsedMs === null ? '' : 'ms'} />
            </Col>
            <Col span={6}>
              <Statistic title="领取次数（重试）" value={current.attempt} />
            </Col>
            <Col span={6}>
              <Statistic
                title="失败原因码"
                valueRender={() =>
                  current.errorCode ? (
                    <Tag color="red">{current.errorCode}</Tag>
                  ) : (
                    <Text type="secondary">—</Text>
                  )
                }
                value=""
              />
            </Col>
          </Row>

          <div style={{ marginTop: 16 }}>
            <Text strong>状态轨迹</Text>
            <Timeline
              style={{ marginTop: 12 }}
              items={trace.map((t) => ({
                children: (
                  <Space>
                    <Text type="secondary">{t.at}</Text>
                    <Tag color={RUN_STATE_COLORS[t.state]}>{RUN_STATE_LABELS[t.state] ?? t.state}</Tag>
                  </Space>
                ),
              }))}
            />
          </div>
        </Card>
      )}

      <Card title="历史执行（本次会话）" size="small">
        {history.length === 0 ? (
          <Empty description="还没有执行记录" />
        ) : (
          <Table<RunRecord>
            rowKey="key"
            size="small"
            columns={historyColumns}
            dataSource={history}
            pagination={{ pageSize: 8 }}
          />
        )}
      </Card>
    </Space>
  )
}
