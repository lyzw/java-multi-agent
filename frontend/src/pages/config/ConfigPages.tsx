/**
 * 配置页组（FE-D2 骨架 → FE-D7 接线）
 *
 * ⚠️ 诚实边界（P4-D07 B-3 / B-4 + FE-02 §0.3）：
 *   - `effective=false` 的键只标「仅声明未接入」，**绝不**渲染成「已生效」。
 *   - 零保留仅声明未确认（registry=PENDING）→ 闸门仍 fail-closed，
 *     本页**不得**显示「已确认放行」。
 *   - 本片只展示声明值，不编造实时指标（待 C3）/ 审批流运行态（待 C4）。
 *
 * 数据全部来自 `GET /api/config`（P4-D07 / C1 + C9）。
 * P18 Agent 配置（G2）/ P19 配置审计（C6）已接入真实接口（P4-D14）：
 *   - P18 走 `GET/POST /api/agents` 与 `POST /api/agents/{id}/versions`；
 *   - P19 复用审计通路，查询 `resourceType=config` 的 `CONFIG_VIEW` 留痕。
 */

import { useCallback, useState } from 'react'
import { Sheet, Marked, Empty, Loading, LoadingRows, ErrorState, ListFooter } from '@/shared/states'
import { useConfig } from '@/features/config/useConfig'
import { useConfigOverrides } from '@/features/config/useConfigOverrides'
import { ConfigOverrideRow } from '@/features/config/ConfigOverrideRow'
import { useGovernancePage } from '@/features/governance/useGovernancePage'
import { listAgents, createAgent, publishAgentVersion } from '@/api/client'
import { PageHead, StatusPill } from '@/components/ui'
import type {
  ConfigEntry,
  ConfigResponse,
  ConfigGroup,
  ZeroRetentionView,
  AgentItem,
} from '@/api/types'

/* ── 通用：配置表 + 生效 chip ──────────────────────────── */

/**
 * 本页展示的可写键（P4-D15 / C3）。
 *
 * ⚠️ **这些是后端白名单的子集，刻意在此显式列举而非全量渲染**：
 * P15 是「运行与容量」页，P16 是「工具与审批」页——全量渲染会把
 * 另一页的配置挤进来，也会让页面职责变得模糊。
 *
 * ⛔ **本清单不含任何 L1 锁定键**（工具风险级别 / 审批人范围）：
 * 它们是代码事实与合规边界，不存在「写入口」这个概念。
 */
const RUNTIME_WRITABLE_KEYS = [
  'agent.run.max-cost-per-run',
  'agent.run.max-llm-calls',
  'agent.run.max-steps',
  'agent.tenant.max-concurrent-runs',
  'deployment.pilot.max-tenants',
  'agent.rate-limit.limit-per-window',
  'agent.worker.concurrency',
  'agent.backpressure.high-watermark',
  'agent.retention.profile-months',
  'agent.retention.prompt-days',
] as const

const TOOLS_WRITABLE_KEYS = [
  'agent.approval.ttl',
  'agent.approval.required-by-risk',
] as const

/**
 * ⚠️ 生效 chip 文案严格区分：
 *   effective=true  → 「已接入」（低饱和青）
 *   effective=false → 「仅声明未接入」（中性），**绝不**出现「已生效」
 */
function EffectiveChip({ effective }: { effective: boolean }): JSX.Element {
  return effective ? (
    <StatusPill tone="success">已接入</StatusPill>
  ) : (
    <StatusPill tone="neutral">仅声明未接入</StatusPill>
  )
}

function ConfigTable({ entries }: { entries: ConfigEntry[] }): JSX.Element {
  return (
    <table>
      <thead>
        <tr>
          <th scope="col">配置键</th>
          <th scope="col">声明值</th>
          <th scope="col">是否接入运行时</th>
          <th scope="col">来源</th>
          <th scope="col">说明</th>
        </tr>
      </thead>
      <tbody>
        {entries.map((entry) => (
          <tr key={entry.key}>
            <td>
              <code>{entry.key}</code>
            </td>
            <td>{entry.declaredValue}</td>
            <td>
              <EffectiveChip effective={entry.effective} />
            </td>
            <td className="muted">{entry.source}</td>
            <td className="muted">{entry.note}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/**
 * 三态渲染：加载 / 错误(可重试) / 数据。
 * groupName 为空 → 渲染全部分组（总览）；否则只渲染指定分组。
 */
function ConfigListView({
  config,
  loading,
  error,
  reload,
  groupName,
}: {
  config: ConfigResponse | null
  loading: boolean
  error: string | null
  reload: () => void
  groupName?: string
}): JSX.Element {
  if (loading && config === null) {
    return <Loading label="正在读取配置" />
  }
  if (error !== null && config === null) {
    return <ErrorState reason={error} next="可以稍后重试" onRetry={reload} />
  }
  if (config === null) {
    return <Loading label="正在读取配置" />
  }

  const groups: ConfigGroup[] =
    groupName === undefined
      ? config.groups
      : config.groups.filter((g) => g.name === groupName)

  if (groups.length === 0) {
    return <Empty title="没有对应的配置分组" hint="后端当前未返回该配置域。" />
  }

  return (
    <>
      {groups.map((group) => (
        <Sheet key={group.name} title={group.name}>
          <ConfigTable entries={group.entries} />
        </Sheet>
      ))}
    </>
  )
}

/* ── P14 配置总览 ────────────────────────────────────── */

export function ConfigOverviewPage(): JSX.Element {
  const { config, loading, error, reload } = useConfig()
  return (
    <>
      <PageHead title="配置总览" lead="系统各项配置，以及它们是否真正接入了运行时。" />

      <Marked label="这里看到的是配置声明值">
        <p style={{ margin: 0 }}>
          标记为「仅声明未接入」的项，改了配置也<b>不会改变系统行为</b>
          —— 它们只是写在文件里的摆设，生产并无读取点。
        </p>
      </Marked>

      <ConfigListView config={config} loading={loading} error={error} reload={reload} />
    </>
  )
}

/* ── P15 运行与容量 ──────────────────────────────────── */

export function ConfigRuntimePage(): JSX.Element {
  const { config, loading, error, reload } = useConfig()
  return (
    <>
      <PageHead title="运行与容量" lead="并发、限流、背压与 Worker 的声明配置。" />

      <Marked label="这里只是声明值">
        <p style={{ margin: 0 }}>
          下方是配置文件里写明的上限与阈值。当前实际并发、队列积压等
          <b>实时运行指标</b>尚未接入（待 C3），页面不编造数字。
        </p>
      </Marked>

      <ConfigListView
        config={config}
        loading={loading}
        error={error}
        reload={reload}
        groupName="运行与容量"
      />

      <WritableConfigTable
        title="可调整的上限"
        keys={RUNTIME_WRITABLE_KEYS}
      />
    </>
  )
}

/* ── P16 工具与审批 ──────────────────────────────────── */

export function ConfigToolsPage(): JSX.Element {
  const { config, loading, error, reload } = useConfig()
  return (
    <>
      <PageHead title="工具与审批" lead="系统能调用哪些工具，各自的分级与审批口径。" />

      <Marked label="有一条规则不能调松">
        <p style={{ margin: 0 }}>
          涉及未成年人的保护项只能调严，不能调松。降低保护强度需要重新走评审，不能在页面上改。
        </p>
        <p className="muted" style={{ margin: 'var(--s-2) 0 0' }}>
          审批流本身的运行态尚未接入（待 C4），下方为声明配置。
        </p>
      </Marked>

      <ConfigListView
        config={config}
        loading={loading}
        error={error}
        reload={reload}
        groupName="工具与审批"
      />

      <WritableConfigTable
        title="可调整的审批口径"
        keys={TOOLS_WRITABLE_KEYS}
      />
    </>
  )
}

/* ── P4-D15 可写配置区（C3/C5/C7）────────────────────── */

/**
 * 可写配置表。
 *
 * ⛔ **FE-CFG-05：必须显著标明生效方式**。
 * 本体系的生效机制是「**下次 Run 创建时读取**」，
 * 而非「保存即全局生效」——正在执行的 Run 不受影响（R-021 快照隔离）。
 * 若不写明，管理员会以为保存后所有在途 Run 都变了。
 */
function WritableConfigTable({
  title,
  keys,
}: {
  title: string
  keys: readonly string[]
}): JSX.Element {
  const {
    items,
    loading,
    error,
    reload,
    submittingKey,
    actionError,
    apply,
    clear,
    clearActionError,
  } = useConfigOverrides()

  if (loading && items === null) {
    return <Loading label="正在读取可写配置" />
  }
  if (error !== null && items === null) {
    return <ErrorState reason={error} next="可以稍后重试" onRetry={reload} />
  }

  // ⚠️ 按**本页关心的键**过滤，而非全量渲染：
  //    工具风险级别 / 审批人范围等 L1 锁定项压根不该出现在写页面。
  const visible = (items ?? []).filter((i) => keys.includes(i.key))

  return (
    <Sheet title={title}>
      <Marked label="保存后何时生效">
        <p style={{ margin: 0 }}>
          保存后<b>下一个新建的 Run</b> 会使用新值；已经开始执行的 Run 不受影响
          （配置在 Run 创建时即被快照固化）。
        </p>
      </Marked>

      {actionError !== null && (
        <p role="alert" className="field__error">
          {actionError}
          <button type="button" onClick={clearActionError}>
            知道了
          </button>
        </p>
      )}

      {visible.length === 0 ? (
        <Empty title="本页没有可写配置" hint="当前租户下这些配置均未被允许修改。" />
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">配置项</th>
              <th scope="col">当前生效值</th>
              <th scope="col">约束</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((item) => (
              <ConfigOverrideRow
                key={item.key}
                item={item}
                busy={submittingKey === item.key}
                onSubmit={(k, v, r) => void apply(k, v, r)}
                onClear={(k) => void clear(k)}
              />
            ))}
          </tbody>
        </table>
      )}
    </Sheet>
  )
}

/* ── P17 合规与零保留 ────────────────────────────────── */

function ProviderStatusChip({ status }: { status: string }): JSX.Element {
  if (status === 'CONFIRMED') return <StatusPill tone="success">已确认</StatusPill>
  if (status === 'PENDING') return <StatusPill tone="warn">待确认</StatusPill>
  return <StatusPill tone="neutral">{status}</StatusPill>
}

function ComplianceBody({ config }: { config: ConfigResponse }): JSX.Element {
  const zr: ZeroRetentionView = config.zeroRetention
  const group = config.groups.find((g) => g.name === '合规与零保留')

  return (
    <>
      <Sheet title="零保留供应商">
        {zr.declaredProviders.length === 0 ? (
          <Empty
            title="还没有声明任何供应商"
            hint="在配置里声明 confirmed-providers 后会出现在这里。"
          />
        ) : (
          <table>
            <thead>
              <tr>
                <th scope="col">供应商</th>
                <th scope="col">运行态确认</th>
              </tr>
            </thead>
            <tbody>
              {zr.declaredProviders.map((p) => (
                <tr key={p}>
                  <td>
                    <code>{p}</code>
                  </td>
                  <td>
                    <ProviderStatusChip status={zr.registryStatus[p] ?? 'UNKNOWN'} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Sheet>

      {/* fail-closed = 安全默认：未确认证据的供应商调用真实数据被拒绝 */}
      <div className={zr.failClosed ? 'status status--success' : 'status'}>
        <p className="status__label">
          {zr.failClosed
            ? '零保留闸门生效中（fail-closed）'
            : '⚠️ 零保留闸门未生效'}
        </p>
        <p className="status__hint">
          {zr.failClosed
            ? '尚未确认有效证据的供应商，调用真实学生数据会被拒绝；这是默认的安全状态。'
            : '当前存在允许真实数据接入的供应商，请核查零保留登记。'}
        </p>
      </div>

      {group !== undefined ? (
        <Sheet title="合规配置项">
          <ConfigTable entries={group.entries} />
        </Sheet>
      ) : null}
    </>
  )
}

export function ConfigCompliancePage(): JSX.Element {
  const { config, error, reload } = useConfig()
  return (
    <>
      <PageHead title="合规与零保留" lead="哪些模型供应商承诺不留存对话内容，以及当前是否真生效。" />

      {config === null && error !== null ? (
        <ErrorState reason={error} next="可以稍后重试" onRetry={reload} />
      ) : config === null ? (
        <Loading label="正在读取合规状态" />
      ) : (
        <ComplianceBody config={config} />
      )}

      {/*
        ⛔ 始终渲染：保 pages.skeleton.test.tsx P17 契约（含「分别」字样）。
        且零保留登记属法律承诺（GATE-2 已取得合同承诺），前端不得提供编辑控件。
      */}
      <Marked label="为什么这一页不能改">
        <p style={{ margin: 0 }}>
          零保留是供应商在合同里承诺的条款，不是可以随手改的配置。这一页只展示，修改要走流程。
        </p>
        <p className="muted" style={{ margin: 'var(--s-2) 0 0' }}>
          判断按供应商分别做：和一家确认能留存，不代表另一家也能。
        </p>
      </Marked>
    </>
  )
}

/* ── P18 Agent 配置与发布 ────────────────────────────── */

export function AgentsPage(): JSX.Element {
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [creatingBusy, setCreatingBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const [publishFor, setPublishFor] = useState<string | null>(null)
  const [definition, setDefinition] = useState('')
  const [publishBusy, setPublishBusy] = useState(false)

  const fetcher = useCallback(
    (params: { limit: number; cursor: string | null }) =>
      listAgents({ limit: params.limit, cursor: params.cursor }),
    [],
  )
  const { items, loading, loadingMore, error, hasMore, reset, loadMore } =
    useGovernancePage<AgentItem>({ fetcher, deps: [] })

  const submitCreate = async (): Promise<void> => {
    if (name.trim() === '') return
    setCreatingBusy(true)
    setActionError(null)
    try {
      await createAgent({
        name: name.trim(),
        description: description.trim() === '' ? null : description.trim(),
      })
      setName('')
      setDescription('')
      setCreating(false)
      reset()
    } catch (cause: unknown) {
      setActionError(cause instanceof Error ? cause.message : '创建失败，请稍后重试')
    } finally {
      setCreatingBusy(false)
    }
  }

  const submitPublish = async (agentId: string): Promise<void> => {
    if (definition.trim() === '') return
    setPublishBusy(true)
    setActionError(null)
    try {
      await publishAgentVersion(agentId, { definition: definition.trim() })
      setDefinition('')
      setPublishFor(null)
      reset()
    } catch (cause: unknown) {
      setActionError(cause instanceof Error ? cause.message : '发布失败，请稍后重试')
    } finally {
      setPublishBusy(false)
    }
  }

  return (
    <>
      <PageHead
        title="Agent 配置"
        lead="定义分析流程用的智能体。"
        actions={
          <button type="button" className="btn" onClick={() => setCreating((v) => !v)}>
            {creating ? '取消新建' : '新建 Agent'}
          </button>
        }
      />

      {creating ? (
        <Sheet title="新建 Agent">
          <div className="field">
            <label htmlFor="agent-name">名称</label>
            <input
              id="agent-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="agent-desc">说明（可选）</label>
            <input
              id="agent-desc"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          {actionError !== null ? <ErrorState reason={actionError} onRetry={reset} /> : null}
          <div className="status-line">
            <button
              type="button"
              disabled={creatingBusy || name.trim() === ''}
              onClick={() => void submitCreate()}
            >
              {creatingBusy ? '正在创建…' : '创建'}
            </button>
          </div>
          <p className="muted">租户内名称不可重复；重名会被拒绝（409）。</p>
        </Sheet>
      ) : null}

      {actionError !== null && !creating ? (
        <ErrorState reason={actionError} onRetry={reset} />
      ) : null}

      <Sheet title="Agent 列表">
        {loading ? (
          <LoadingRows
            columns={['名称', '说明', '版本数', '当前版本', '最近发布', '操作']}
            rows={4}
          />
        ) : error !== null ? (
          <ErrorState reason={error} onRetry={reset} retryLabel="重新加载" />
        ) : items.length === 0 ? (
          <Empty title="还没有 Agent" hint="点上方「新建 Agent」创建第一个。" />
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  {['名称', '说明', '版本数', '当前版本', '最近发布', '操作'].map((c) => (
                    <th key={c} scope="col">
                      {c}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {items.map((agent) => (
                  <tr key={agent.agentId}>
                    <td>
                      <code>{agent.name}</code>
                    </td>
                    <td className="muted">{agent.description ?? '—'}</td>
                    <td>{agent.versionCount}</td>
                    <td>{agent.latestVersionNumber > 0 ? `v${agent.latestVersionNumber}` : '未发布'}</td>
                    <td className="muted">{formatTime(agent.latestPublishedAt)}</td>
                    <td>
                      <button
                        type="button"
                        onClick={() => {
                          setPublishFor(agent.agentId)
                          setDefinition('')
                        }}
                      >
                        发布新版本
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <ListFooter
              count={items.length}
              hasMore={hasMore}
              loadingMore={loadingMore}
              onLoadMore={loadMore}
            />
          </>
        )}
      </Sheet>

      {publishFor !== null ? (
        <Sheet title="发布新版本">
          <p className="muted">
            definition 是不可变快照（JSON 文本）。发布后生成新版本号，不影响在途分析。
          </p>
          <div className="field">
            <label htmlFor="agent-def">definition（JSON）</label>
            <textarea
              id="agent-def"
              rows={6}
              value={definition}
              onChange={(e) => setDefinition(e.target.value)}
            />
          </div>
          <div className="status-line">
            <button
              type="button"
              disabled={publishBusy || definition.trim() === ''}
              onClick={() => void submitPublish(publishFor)}
            >
              {publishBusy ? '正在发布…' : '发布'}
            </button>
            <button
              type="button"
              className="btn btn--quiet"
              onClick={() => {
                setPublishFor(null)
                setDefinition('')
              }}
            >
              取消
            </button>
          </div>
        </Sheet>
      ) : null}

      <Marked label="改配置不会影响正在进行的分析">
        <p style={{ margin: 0 }}>
          发布时会生成一份不可变的执行快照。已经发起的分析会继续用它开始时的那份，
          不会中途换配置。
        </p>
        <p className="muted" style={{ margin: 'var(--s-2) 0 0' }}>
          这条说明在具备发布能力后仍要留着，
          否则会让人以为改配置会影响到在途的分析。
        </p>
      </Marked>
    </>
  )
}

/* ── 本地渲染助手（与 GovernancePages 同源，不跨文件引入以避免耦合）── */

/**
 * ⚠️ 配置查看留痕（CONFIG_VIEW）已并入统一的「审计查询」页（F4）：
 *   审计页资源类型下拉新增「配置查看」即可过滤，无需独立的配置变更审计页。
 *   下方仅保留 AgentsPage 仍用的 formatTime。
 */

function formatTime(iso: string | null): string {
  if (iso === null) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour12: false })
}
