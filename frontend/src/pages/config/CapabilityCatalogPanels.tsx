/**
 * 能力目录面板（Point 3 · Tool / Skill 配置页的读取面）
 *
 * ⚠️ **这两个面板刻意只读，不提供任何写入入口** —— 这不是「还没做」，
 * 而是平台架构的**设计意图**：
 *
 *   Tool / Skill / MCP Server 定义是 **platform 级对象**（无 tenant_id，不启用 RLS）。
 *   平台身份表 `principal` 是**租户内**的，没有跨租户主体；
 *   若把平台级写操作挂到租户身份上，任一租户管理员自授角色即可改全平台配置。
 *   因此后端 `CapConfigPublishController` 对 PLATFORM 作用域**显式返回 403**，
 *   并把「平台级发布」留给带外运维通道（BYPASSRLS 凭据 + 运维工具）。
 *
 *   本页只把「平台上有哪些工具 / 技能、各自的风险等级与绑定关系」如实呈现，
 *   并在界面上明示变更入口不在此处 —— 避免使用者以为「页面上没按钮 = 改不了」，
 *   或反过来以为「这里就该能改」。
 *
 * ⚠️ **数据口径**：每项取该逻辑 id 的**最高版本**（与后端
 * `DISTINCT ON (id) ... ORDER BY version DESC` 同口径），
 * 不等价于「当前发布所指版本」。本页只作目录浏览，
 * 不用于授权判定（那必须走后端的 PublishedVersion 解析）。
 */

import { useCallback, useEffect, useState } from 'react'
import { Sheet, Loading, Empty, ErrorState, Marked } from '@/shared/states'
import { PageHead, StatusPill } from '@/components/ui'
import {
  listCapabilityTools,
  listCapabilitySkills,
  ApiError,
} from '@/api/client'
import { isTimeoutError } from '@/api/errors'
import type { CapToolCatalogItem, CapSkillCatalogItem } from '@/api/types'

/* ── 通用：只读目录的五态骨架 ──────────────────────────────── */

/**
 * 只读目录的读取状态（FE-D3 五态中的 loading/error/timeout/ok）。
 *
 * ⚠️ <b>每种 kind 用单一字面量，不用 {@code 'error' | 'timeout'} 合并</b>：
 * 泛型可辨识联合在 `state.kind === 'error' || state.kind === 'timeout'`
 * 这种**或条件早返回**后不会被继续收窄，`state.items` 会报「属性不存在」。
 * 拆成单一字面量后 TS 能逐步排除，收窄正常。
 */
type LoadState<T> =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'timeout'; message: string }
  | { kind: 'ok'; items: T[] }

function useCatalog<T>(fetcher: () => Promise<T[]>): LoadState<T> & { reload: () => void } {
  const [state, setState] = useState<LoadState<T>>({ kind: 'loading' })
  const [nonce, setNonce] = useState(0)

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  useEffect(() => {
    let cancelled = false
    setState({ kind: 'loading' })
    fetcher()
      .then((items) => {
        if (!cancelled) setState({ kind: 'ok', items })
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        if (cause instanceof ApiError && isTimeoutError(cause.normalized)) {
          setState({ kind: 'timeout', message: cause.normalized.message })
        } else if (cause instanceof ApiError) {
          setState({ kind: 'error', message: cause.normalized.message })
        } else {
          setState({ kind: 'error', message: '读取失败，请稍后重试' })
        }
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nonce])

  return { ...state, reload }
}

function CatalogBody<T>({
  state,
  reload,
  emptyTitle,
  emptyHint,
  children,
}: {
  state: LoadState<T>
  reload: () => void
  emptyTitle: string
  emptyHint: string
  children: (items: T[]) => React.ReactNode
}): JSX.Element {
  if (state.kind === 'loading') {
    return <Loading label="正在读取目录" />
  }
  if (state.kind === 'error' || state.kind === 'timeout') {
    return (
      <ErrorState
        reason={state.message}
        next="可以重新读取；若持续失败，请确认账号是否具备 agent:manage 权限、后端是否已启动。"
        onRetry={reload}
        retryLabel="重新读取"
      />
    )
  }
  // ⚠️ 取到局部常量再判断：泛型可辨识联合在 `||` 早返回后
  //    有时不会被继续收窄，落到 `state.items` 上会报「属性不存在」。
  const items = state.items
  if (items.length === 0) {
    return <Empty title={emptyTitle} hint={emptyHint} />
  }
  return <>{children(items)}</>
}

/** 平台级对象只读的统一说明（两个目录页共用）。 */
function PlatformReadOnlyNotice(): JSX.Element {
  return (
    <Marked label="为什么这里不能改">
      <p style={{ margin: 0 }}>
        工具与技能定义是<b>平台级对象</b>（不按租户隔离）。它们的变更走<b>带外运维通道</b>
        （BYPASSRLS 凭据 + 运维工具），不挂在租户身份上——否则任一租户管理员
        自授角色即可改动全平台配置。
      </p>
      <p className="muted" style={{ margin: 'var(--s-2) 0 0' }}>
        后端 <code>POST /api/cap/config/publishes</code> 对 PLATFORM 作用域<b>显式返回 403</b>
        （设计意图，不是被 RLS 偶然挡住）。本页因此只做目录浏览。
      </p>
    </Marked>
  )
}

/** 风险等级徽章（READ &lt; WRITE_PROFILE &lt; WRITE_EXTERNAL；未知即不可判定）。 */
function RiskBadge({ level }: { level: string }): JSX.Element {
  if (level === 'READ') return <StatusPill tone="success">READ · 只读</StatusPill>
  if (level === 'WRITE_PROFILE') return <StatusPill tone="info">WRITE_PROFILE · 写画像</StatusPill>
  if (level === 'WRITE_EXTERNAL') return <StatusPill tone="danger">WRITE_EXTERNAL · 写外部</StatusPill>
  return <StatusPill tone="warn">{level} · 不可判定</StatusPill>
}

/**
 * MCP 声明的操作口径说明。
 *
 * ⚠️ **三种形态必须分开**（§4.4）：
 * {@code null} = 未配置（取 ServerAllowedOps 全集）；
 * {@code []} = 显式不允许任何操作；
 * 非空清单 = 逐条列出。归一化成同一种会把「没配」误显示成「全允许」。
 */
function mcpAllowlistDesc(allowlist: string[] | null): string {
  if (allowlist == null) return '未配置（取 Server 全集）'
  if (allowlist.length === 0) return '显式不允许任何操作'
  return allowlist.join('、')
}

/** 可折叠的 JSON 原文展示（schema / 参数策略用）。 */
function JsonBlock({ label, json }: { label: string; json: string }): JSX.Element {
  const [open, setOpen] = useState(false)
  const pretty = usePrettyJson(json)
  return (
    <div className="cap-json">
      <button type="button" className="link-btn" onClick={() => setOpen((v) => !v)}>
        {open ? `收起${label}` : `展开${label}`}
      </button>
      {open ? <pre className="cap-json__pre">{pretty}</pre> : null}
    </div>
  )
}

/** 尽力美化 JSON；非法 JSON 原样展示（不吞错）。 */
function usePrettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/* ── Tool 目录 ─────────────────────────────────────────────── */

export function ToolCatalogPanel(): JSX.Element {
  const state = useCatalog<CapToolCatalogItem>(useCallback(() => listCapabilityTools(), []))

  return (
    <>
      <PageHead
        title="Tool 目录"
        lead="平台级工具定义清单：种类、风险等级、MCP 绑定与参数口径。只读。"
      />
      <PlatformReadOnlyNotice />
      <Sheet title="工具定义" note="每项取该逻辑 id 的最高版本；不等价于「当前发布所指版本」。">
        <CatalogBody
          state={state}
          reload={state.reload}
          emptyTitle="平台还没有登记工具定义"
          emptyHint="工具定义通过带外运维通道登记，此处只读。"
        >
          {(items) => (
            /* ⚠️ 必须包 .table-wrap：目录表的 min-content 宽度（工具 id / MCP 绑定）
               在窄屏会把整页撑出横向滚动条 —— 溢出应发生在表格内部。 */
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    {['工具', '种类', '风险等级', 'MCP 绑定', '版本', '状态', '参数口径'].map((c) => (
                      <th key={c} scope="col">
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {items.map((t) => (
                    <tr key={`${t.toolId}@${t.version}`}>
                      <td>
                        <code>{t.toolId}</code>
                        <div className="muted">{t.name ?? t.toolId}</div>
                      </td>
                      <td>{t.kind === 'MCP' ? 'MCP 远端' : '平台原生'}</td>
                      <td>
                        <RiskBadge level={t.riskLevel} />
                      </td>
                      <td className="muted">
                        {t.mcpServerId != null
                          ? `${t.mcpServerId} / ${t.mcpToolName}`
                          : '—'}
                      </td>
                      <td>v{t.version}</td>
                      <td>{t.published ? <StatusPill tone="success">已发布</StatusPill> : <StatusPill tone="warn">未发布</StatusPill>}</td>
                      <td>
                        <JsonBlock label="输入 Schema" json={t.inputSchema} />
                        <JsonBlock label="参数策略" json={t.paramPolicies} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CatalogBody>
      </Sheet>
    </>
  )
}

/* ── Skill 目录 ────────────────────────────────────────────── */

export function SkillCatalogPanel(): JSX.Element {
  const state = useCatalog<CapSkillCatalogItem>(useCallback(() => listCapabilitySkills(), []))

  return (
    <>
      <PageHead
        title="Skill 目录"
        lead="平台级技能定义清单：声明的工具、MCP 操作、风险上限与必需能力。只读。"
      />
      <PlatformReadOnlyNotice />
      <Sheet title="技能定义" note="每项取该逻辑 id 的最高版本；不等价于「当前发布所指版本」。">
        <CatalogBody
          state={state}
          reload={state.reload}
          emptyTitle="平台还没有登记技能定义"
          emptyHint="技能定义通过带外运维通道登记，此处只读。"
        >
          {(items) => (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    {['技能', '风险上限', '声明的工具', '声明的 MCP', '必需能力', '版本', '状态', '参数策略'].map(
                      (c) => (
                        <th key={c} scope="col">
                          {c}
                        </th>
                      ),
                    )}
                  </tr>
                </thead>
                <tbody>
                  {items.map((s) => (
                    <tr key={`${s.skillId}@${s.version}`}>
                      <td>
                        <code>{s.skillId}</code>
                        <div className="muted">{s.name ?? s.skillId}</div>
                        {s.description != null ? (
                          <div className="muted" style={{ fontSize: 'var(--t-note)' }}>
                            {s.description}
                          </div>
                        ) : null}
                      </td>
                      <td>
                        <RiskBadge level={s.riskCeiling} />
                      </td>
                      <td>
                        {s.declaredTools.length === 0 ? (
                          <span className="muted">—</span>
                        ) : (
                          <ul className="cap-tags">
                            {s.declaredTools.map((t) => (
                              <li key={t}>
                                <code>{t}</code>
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                      <td>
                        {s.declaredMcp.length === 0 ? (
                          <span className="muted">—</span>
                        ) : (
                          <ul className="cap-tags">
                            {s.declaredMcp.map((m) => (
                              <li key={m.serverId}>
                                <code>{m.serverId}</code>
                                <span className="muted">{mcpAllowlistDesc(m.toolAllowlist)}</span>
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                      <td>
                        {s.requiredCaps.length === 0 ? (
                          <span className="muted">—</span>
                        ) : (
                          <ul className="cap-tags">
                            {s.requiredCaps.map((c) => (
                              <li key={c}>
                                <code>{c}</code>
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                      <td>v{s.version}</td>
                      <td>
                        {s.published ? (
                          <StatusPill tone="success">已发布</StatusPill>
                        ) : (
                          <StatusPill tone="warn">未发布</StatusPill>
                        )}
                      </td>
                      <td>
                        <JsonBlock label="参数策略" json={s.paramPolicies} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CatalogBody>
      </Sheet>
    </>
  )
}
