/**
 * AI 实验室 · Agent 效果测试。
 *
 * <p><b>用途</b>：挑一个 Agent，喂一条用例，发起一次<b>真实</b>执行，
 *    观察它的状态流转、耗时、重试次数与失败原因码，用于效果验收与回归比对。
 *
 * <p><b>⚠️ 诚实边界（写在这里，不藏在文档里）</b>：
 * <ol>
 *   <li><b>通道</b>：`/api/runs` 是租户域端点，租户来自验签令牌。
 *       admin-console 持平台令牌，直接打过去必然 401（受众隔离按设计生效）。
 *       现走<b>执行票证</b>：先指名一个租户成员，
 *       由 `POST /api/platform/v1/tenants/{id}/execution-ticket` 换一张 60 秒租户票证。
 *       取票是<b>平台权限</b>（仅超级管理员持有 `platform.tenant.execution_ticket`），
 *       每次签发都写一条平台审计（谁、哪个租户、扮演哪个身份、票证 jti）并受限流保护，
 *       所以「以谁的身份执行」必须显式选择，界面上也始终显示。
 *       票证<b>只在内存</b>，绝不落 sessionStorage；取不到就明确报错，不伪装成执行成功。</li>
 *   <li><b>看不到输出正文</b>：`GET /api/runs/{id}` 只回状态与失败原因码，
 *       <b>不含</b>执行输出（禁区红线：Agent 定义正文与运行输出不出现在平台控制台）。
 *       效果判定只能基于状态 / 耗时 / 错误码，不能基于输出内容。</li>
 *   <li><b>轮询而非 SSE</b>：`EventSource` 无法带 Authorization 头，
 *       票证解决的是「能否拿到租户身份」，<b>不解决</b>「凭据不落 URL」——
 *       令牌入 query 会写进访问日志与 Referer，所以这里依旧轮询。</li>
 *   <li><b>票证的租户必须是被测 Agent 的租户</b>：租户来自票证而非请求体，
 *       给 A 租户取票去跑 B 租户的 Agent 只会在执行时授权那一步被拒。
 *       故取票对象由选中 Agent 的 `tenantId` 决定，上方的租户下拉只是注册表过滤器。</li>
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
import {
  ensureExecutionTicket,
  clearExecutionTicket,
  getTenantPrincipals,
  type ExecutionTicket,
  type TenantPrincipal,
} from '@/api/executionTicketApi'
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

/**
 * 票证的展示快照 —— **不含** accessToken。
 *
 * ⚠️ 刻意不复用 `ExecutionTicket` 对象：把它塞进 React state 会让令牌出现在
 *    React DevTools 的渲染树里，等于把「凭据只留内存」进一步扩大暴露面。
 *    令牌留在 `executionTicketApi` 的模块态里，界面只回显身份与剩余秒数。
 */
interface TicketView {
  tenantId: string
  loginName: string
  roles: string[]
  expiresAt: string
  jti: string
}

function viewOf(ticket: ExecutionTicket): TicketView {
  return {
    tenantId: ticket.tenantId,
    loginName: ticket.loginName,
    roles: ticket.roles,
    expiresAt: ticket.expiresAt,
    jti: ticket.jti,
  }
}

/** 剩余有效期（秒，向下取整；已过期为负）。 */
function remainingSeconds(expiresAt: string): number {
  return Math.floor((Date.parse(expiresAt) - Date.now()) / 1000)
}

function newIdempotencyKey(): string {
  return `lab-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export function AgentLabPage(): JSX.Element {
  const [tenants, setTenants] = useState<PlatformTenant[]>([])
  const [tenantId, setTenantId] = useState('')
  const [agents, setAgents] = useState<PlatformAgentRegistryItem[]>([])
  const [agentsError, setAgentsError] = useState<string | null>(null)
  const [agentId, setAgentId] = useState('')

  /** 可指名的租户成员（来自平台侧 `/principals`，不含任何凭据）。 */
  const [principals, setPrincipals] = useState<TenantPrincipal[]>([])
  const [principalsError, setPrincipalsError] = useState<string | null>(null)
  /** 要扮演哪个租户成员 —— 没有默认值：留空即不可执行。 */
  const [loginName, setLoginName] = useState('')
  /** 当前票证的只读快照（身份 / 角色 / 到期时刻），令牌本体不在这里。
   *  剩余秒数在渲染时按 `expiresAt` 现算：每次轮询 tick 都会 setTicketView，
   *  所以显示每 2 秒刷新一次，不需要额外的倒计时 state。 */
  const [ticketView, setTicketView] = useState<TicketView | null>(null)

  const [input, setInput] = useState('')
  const [timeoutMs, setTimeoutMs] = useState(30000)
  const [budgetAmount, setBudgetAmount] = useState<number | null>(null)

  const [submitting, setSubmitting] = useState(false)
  const [current, setCurrent] = useState<RunRecord | null>(null)
  const [history, setHistory] = useState<RunRecord[]>([])
  const [errorKind, setErrorKind] = useState<
    'none' | 'channel' | 'ticket' | 'ratelimit' | 'input' | 'other'
  >('none')
  const [errorText, setErrorText] = useState<string | null>(null)
  /** 状态轨迹：同值合并（`count` = 该状态被采到的次数），避免 2s 一条刷屏。 */
  const [trace, setTrace] = useState<{ at: string; state: RunState; count: number }[]>([])

  const pollTimer = useRef<number | null>(null)
  /** 轮询进行中：票证剩余秒数只在轮询时逐 tick 刷新，空闲时不谎报「还剩 N 秒」。 */
  const [polling, setPolling] = useState(false)

  const stopPolling = useCallback(() => {
    if (pollTimer.current !== null) {
      window.clearInterval(pollTimer.current)
      pollTimer.current = null
    }
    setPolling(false)
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

  const principalOptions = useMemo(
    () =>
      principals.map((p) => ({
        value: p.loginName,
        label:
          `${p.loginName}` +
          (p.displayName ? ` · ${p.displayName}` : '') +
          ` · ${p.roles.length > 0 ? p.roles.join('/') : '无角色'}`,
      })),
    [principals],
  )

  const selectedAgent = useMemo(
    () => agents.find((a) => a.agentId === agentId) ?? null,
    [agents, agentId],
  )

  /**
   * 票证的租户上下文 = <b>被测 Agent 所属租户</b>，不是上方过滤下拉的值。
   *
   * ⚠️ 租户来自验签令牌，请求体里不允许出现 tenantId。给 A 租户取票去跑 B 租户的
   *    Agent，只会在「执行时授权」那一步被拒；把票证绑到 Agent 自己的租户，
   *    界面才能说出「以谁的身份、在哪个租户执行」这句真话。
   */
  const ticketTenantId = selectedAgent?.tenantId ?? ''

  // ⚠️ 换上下文即作废：旧票属于上一个租户/身份，绝不复用（也避免审计里
  //    「一次执行挂在两个被扮演身份上」的歧义）。
  useEffect(() => {
    clearExecutionTicket()
    setTicketView(null)
  }, [ticketTenantId, loginName])

  useEffect(() => {
    if (ticketTenantId === '') {
      setPrincipals([])
      setPrincipalsError(null)
      setLoginName('')
      return
    }
    let cancelled = false
    const run = async (): Promise<void> => {
      setPrincipalsError(null)
      try {
        const list = await getTenantPrincipals(ticketTenantId)
        if (cancelled) return
        // 后端只对 ACTIVE 成员签发票证，这里同步过滤：
        // 让界面提供「必然能签出来的选项」，而不是选完再吃一个 403。
        setPrincipals(list.filter((p) => p.status === 'ACTIVE'))
        setLoginName((prev) =>
          list.some((p) => p.loginName === prev && p.status === 'ACTIVE') ? prev : '',
        )
      } catch (cause) {
        if (cancelled) return
        setPrincipals([])
        setLoginName('')
        setPrincipalsError(
          cause instanceof ApiError
            ? `${cause.message}（取票证需要平台权限 platform.tenant.execution_ticket）`
            : '读取租户成员失败',
        )
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [ticketTenantId])

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
    (
      runId: string,
      agent: string,
      agentName: string,
      prompt: string,
      ctx: { tenantId: string; loginName: string },
    ) => {
      stopPolling()
      setPolling(true)
      let n = 0
      /** 连续失败计数：只在「一直读不到」时说明，单次抖动不打扰。 */
      let fails = 0
      pollTimer.current = window.setInterval(() => {
        n += 1
        if (n > MAX_POLLS) {
          stopPolling()
          setErrorKind('other')
          setErrorText('轮询已停止：超过最大等待次数，请在运行列表确认最终状态。')
          return
        }
        void (async () => {
          let ticket: ExecutionTicket
          try {
            // ⚠️ 每次 tick 都过一遍「还要不要续票」：票证 60 秒，轮询 2 秒一次，
            //    续取只发生在临近过期处（约每 45 秒一次），不是每次都取票。
            ticket = await ensureExecutionTicket(ctx.tenantId, ctx.loginName)
            setTicketView(viewOf(ticket))
          } catch (cause) {
            stopPolling()
            setErrorKind('ticket')
            setErrorText(
              '续取票证失败，轮询已停止：' +
                (cause instanceof ApiError ? cause.message : '网络未能返回票证'),
            )
            return
          }
          try {
            const status = await getRunStatus(runId, ticket)
            fails = 0
            // ⚠️ 同值合并：轮询每 2s 一次，状态没变就不该多出一条——
            //    原先每次都 push，「等了 60s 还在 RUNNING」会被摊成 30 行噪音，
            //    真正需要的信息（在这个状态待了多久）反而看不见。
            setTrace((prev) => {
              const last = prev[prev.length - 1]
              if (last !== undefined && last.state === status.state) {
                return [...prev.slice(0, -1), { ...last, count: last.count + 1 }]
              }
              return [...prev, { at: new Date().toLocaleTimeString(), state: status.state, count: 1 }]
            })
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
              setErrorText('查询状态时被拒绝（401/403）：票证已过期或该身份不具备执行角色。')
              return
            }
            // ⚠️ 其它错误原先被完全吞掉：界面停在「执行中」，看不出是
            //    还在跑还是根本没在跑。连续 3 次读不到就如实说明并停止轮询。
            fails += 1
            if (fails >= 3) {
              stopPolling()
              setErrorKind('other')
              setErrorText(
                `连续 ${fails} 次读取状态失败：` +
                  (cause instanceof ApiError ? cause.message : '网络未能返回结果') +
                  '。轮询已停止，请在运行列表确认最终状态。',
              )
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
      if (ticketTenantId === '' || loginName === '') {
        // 兜底：按钮已禁用，走到这里说明上下文被并发清空了。
        setErrorKind('ticket')
        setErrorText('未选定「租户成员」：票证必须指名一个真实的租户身份，不能匿名执行。')
        return
      }
      let ticket: ExecutionTicket
      try {
        ticket = await ensureExecutionTicket(ticketTenantId, loginName)
      } catch (cause) {
        setErrorKind('ticket')
        setErrorText(
          '取票证失败：' +
            (cause instanceof ApiError ? cause.message : '网络未能返回票证') +
            '。票证由平台侧签发，每次取票都会写一条平台审计并计入该租户的限流（30 次/60 秒）。',
        )
        return
      }
      setTicketView(viewOf(ticket))
      const res = await createRun(
        {
          agentId,
          userMessage: input,
          timeoutMs,
          budgetAmount,
          idempotencyKey: newIdempotencyKey(),
        },
        ticket,
      )
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
      setTrace([{ at: new Date().toLocaleTimeString(), state: 'QUEUED', count: 1 }])
      poll(res.runId, agentId, record.agentName, input, {
        tenantId: ticketTenantId,
        loginName,
      })
    } catch (cause) {
      if (isTenantChannelBlocked(cause)) {
        setErrorKind('channel')
        setErrorText('票证被租户域拒绝（401/403）：可能已过期，或该身份不具备执行角色。请重新发起执行。')
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
            2）<b>通道</b>：<code>/api/runs</code> 是租户域端点，租户来自<b>票证</b>里的令牌。
            取票是平台权限（仅超级管理员），每次签发都写一条平台审计（谁 / 哪个租户 / 扮演谁 / 票证 jti）
            并按租户限流；票证 60 秒过期、只在内存，<b>不</b>写入任何浏览器存储。
            <br />
            3）<b>合规先于执行</b>：内容合规、Prompt 防护、执行时授权、限流依次发生在创建之前，
            被拦下是<b>设计如此</b>，不是故障。
          </>
        }
      />

      {errorKind === 'channel' && (
        <Alert type="error" showIcon message="票证被拒绝（401/403）" description={errorText} />
      )}
      {errorKind === 'ticket' && (
        <Alert type="error" showIcon message="取不到票证" description={errorText} />
      )}
      {errorKind === 'ratelimit' && (
        <Alert type="warning" showIcon message="被限流（429）" description={errorText} />
      )}
      {errorKind === 'input' && (
        <Alert type="warning" showIcon message="输入被拒绝（400）" description={errorText} />
      )}
      {errorKind === 'other' && <Alert type="error" showIcon message="执行失败" description={errorText} />}
      {agentsError && <Alert type="error" showIcon message="读取 Agent 注册表失败" description={agentsError} />}
      {principalsError && (
        <Alert type="error" showIcon message="读取租户成员失败（无法取票）" description={principalsError} />
      )}

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

          <Space wrap>
            <Text>租户身份（取票）</Text>
            <Select
              showSearch
              style={{ width: 420 }}
              placeholder={
                ticketTenantId === ''
                  ? '先选择 Agent：票证绑到该 Agent 所属租户'
                  : '指名一个 ACTIVE 租户成员（票证扮演谁）'
              }
              value={loginName || undefined}
              onChange={setLoginName}
              disabled={ticketTenantId === ''}
              options={principalOptions}
              filterOption={(inp, opt) =>
                String(opt?.label ?? '').toLowerCase().includes(inp.toLowerCase())
              }
              notFoundContent={
                ticketTenantId === ''
                  ? '未选择 Agent'
                  : principalsError !== null
                    ? '读取成员失败，无法取票'
                    : '该租户没有 ACTIVE 成员'
              }
            />
          </Space>

          {ticketView && (
            <Space wrap size="small">
              <Tag color="purple">以 {ticketView.loginName} 的身份执行</Tag>
              <Text type="secondary" style={{ fontSize: 12 }}>
                票证租户 <Text code>{ticketView.tenantId}</Text> · 角色{' '}
                {ticketView.roles.length > 0 ? ticketView.roles.join('/') : '—'} · 票证{' '}
                <Text code>{ticketView.jti.slice(0, 8)}…</Text>
              </Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {polling
                  ? `剩余 ${Math.max(remainingSeconds(ticketView.expiresAt), 0)}s（临近过期自动续取，每次续取写一条平台审计）`
                  : '票证已在内存中（60 秒后失效，不落任何浏览器存储）；下次执行前会自动续取'}
              </Text>
            </Space>
          )}

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
              disabled={agentId === '' || input.trim() === '' || loginName === ''}
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
                    {t.count > 1 && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        在此状态停留约 {(t.count * POLL_INTERVAL_MS) / 1000}s
                      </Text>
                    )}
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
