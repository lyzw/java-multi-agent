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
 * 仍无接口的页（P18 Agent 配置 G2、P19 审计 C6）维持诚实 `Unavailable`。
 */

import { Sheet, Unavailable, Marked, Empty, Loading, ErrorState } from '@/shared/states'
import { useConfig } from '@/features/config/useConfig'
import type { ConfigEntry, ConfigResponse, ConfigGroup, ZeroRetentionView } from '@/api/types'

/* ── 通用：配置表 + 生效 chip ──────────────────────────── */

/**
 * ⚠️ 生效 chip 文案严格区分：
 *   effective=true  → 「已接入」（低饱和青）
 *   effective=false → 「仅声明未接入」（中性），**绝不**出现「已生效」
 */
function EffectiveChip({ effective }: { effective: boolean }): JSX.Element {
  return effective ? (
    <span className="chip chip--settled">已接入</span>
  ) : (
    <span className="chip">仅声明未接入</span>
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
      <div className="main__head">
        <h1>配置总览</h1>
        <p className="main__lead">系统各项配置，以及它们是否真正接入了运行时。</p>
      </div>

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
      <div className="main__head">
        <h1>运行与容量</h1>
        <p className="main__lead">并发、限流、背压与 Worker 的声明配置。</p>
      </div>

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
    </>
  )
}

/* ── P16 工具与审批 ──────────────────────────────────── */

export function ConfigToolsPage(): JSX.Element {
  const { config, loading, error, reload } = useConfig()
  return (
    <>
      <div className="main__head">
        <h1>工具与审批</h1>
        <p className="main__lead">系统能调用哪些工具，各自的分级与审批口径。</p>
      </div>

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
    </>
  )
}

/* ── P17 合规与零保留 ────────────────────────────────── */

function ProviderStatusChip({ status }: { status: string }): JSX.Element {
  if (status === 'CONFIRMED') return <span className="chip chip--settled">已确认</span>
  if (status === 'PENDING') return <span className="chip chip--mark">待确认</span>
  return <span className="chip">{status}</span>
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
      <div className="main__head">
        <h1>合规与零保留</h1>
        <p className="main__lead">哪些模型供应商承诺不留存对话内容，以及当前是否真生效。</p>
      </div>

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
  return (
    <>
      <div className="main__head">
        <h1>Agent 配置</h1>
        <p className="main__lead">定义分析流程用的智能体。</p>
      </div>

      <Unavailable
        feature="Agent 配置"
        blockedBy="G2"
        reason="后端还没有提供查询和发布接口；声明类配置可在「配置总览」看到"
        eta="P3"
      >
        <Sheet title="Agent">
          <table>
            <thead>
              <tr>
                <th scope="col">名称</th>
                <th scope="col">当前版本</th>
                <th scope="col">状态</th>
              </tr>
            </thead>
            <tbody aria-hidden="true">
              <tr>
                <td />
                <td />
                <td />
              </tr>
            </tbody>
          </table>
        </Sheet>

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
      </Unavailable>
    </>
  )
}

/* ── P19 配置变更审计 ────────────────────────────────── */

export function ConfigAuditPage(): JSX.Element {
  return (
    <>
      <div className="main__head">
        <h1>配置变更审计</h1>
        <p className="main__lead">谁改过配置，改了什么。</p>
      </div>

      <Unavailable
        feature="配置变更审计"
        blockedBy="C6"
        reason="后端还没有提供审计查询接口；配置声明值可在「配置总览」看到"
        eta="P4"
      >
        <Sheet title="变更记录">
          <Empty title="没有变更记录" hint="配置被改动时会在这里留痕。" />
        </Sheet>

        {/* ⛔ 与 P10 同源：审计不含原文，此处同样不提供「查看完整请求体」。 */}
        <Marked label="记录里有什么">
          <p style={{ margin: 0 }}>
            只记录改了哪一项、从什么变成什么、谁改的、什么时候。
            不记录提交内容本身。
          </p>
        </Marked>
      </Unavailable>
    </>
  )
}
