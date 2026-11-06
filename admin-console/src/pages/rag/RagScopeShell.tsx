/**
 * RAG 语料管理 · 页面共享外壳（RAG-D13 四治理域导航 / RAG-D14 视觉稿）
 *
 * ⚠️ **为什么要有这个外壳而不是每个页面各写一遍作用域选择**：
 *    作用域（TENANT / PLATFORM）与治理票据是**全 RAG 模块的横切安全机制**——
 *    每个页面各自实现一份，必然出现「某个页面忘了带 X-Tenant-Id」
 *    或「平台档忘了领票」的漏网。外壳统一承载，页面只关心自己的业务区。
 *
 * ⚠️ **作用域是显式声明的，不由「有没有选租户」隐式推断**：
 *    隐式推断的失败形态是「忘记选租户 → 静默变成跨租户/全局查询」，
 *    把本地配置错误伪装成正常结果（原 RagManagementPage 的纪律，保持不变）。
 *
 * ⚠️ **切租户 / 切档位必须清空页面数据并作废票据**：
 *    否则上一档的治理结论会留在页面上，看起来像新租户的数据——
 *    "治理结论张冠李戴"比空列表危险得多。本组件用 remountKey 强制下游重挂。
 */

import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Alert, Button, Card, Segmented, Select, Space, Tag, Typography } from 'antd'

import { getPlatformTenants } from '@/api/adminApi'
import type { PlatformTenant } from '@/api/types'
import {
  ApiError,
  isRagTargetReady,
  platformTarget,
  tenantTarget,
} from '@/api/ragClient'
import type { RagCorpusScope, RagTarget } from '@/api/ragClient'
import { useRagGovernanceTicket } from '@/features/rag/useRagGovernanceTicket'

const { Text, Paragraph } = Typography

/** 下游页面消费的作用域上下文。 */
export interface RagScopeContextValue {
  /** 本次查询的目标（租户档带租户 ID；平台档带票据）。 */
  target: RagTarget
  /** 租户档已选租户（平台档为 undefined）。 */
  tenantId: string | undefined
  /** 当前档位。 */
  scope: RagCorpusScope
  /**
   * 作用域变化时递增。⚠️ 下游页面应把它放进 `key`，强制重挂：
   *    组件内部状态（已加载列表 / 抽屉选中项 / 探针结果）是租户作用域的，
   *    不重挂就会把 A 租户的结论当成 B 租户的。
   */
  scopeKey: string
  /** 是否满足发起 RAG 请求的前提（租户档要求已选租户）。 */
  ready: boolean
}

const RagScopeContext = createContext<RagScopeContextValue | null>(null)

/**
 * 读取当前 RAG 作用域。
 *
 * @throws 若在 {@link RagScopeShell} 之外使用（属接线错误，早失败优于静默默认值）
 */
export function useRagScope(): RagScopeContextValue {
  const ctx = useContext(RagScopeContext)
  if (ctx === null) {
    throw new Error('useRagScope 必须在 RagScopeShell 内使用')
  }
  return ctx
}

/**
 * ⚠️ **为什么需要下面这层「提供者 / 消费者」拆分**：
 *
 *     React context 只能由外向内流动。页面若在自己的 render **顶层**调用
 *     {@link useRagScope}，而 {@link RagScopeShell} 又写在同一组件的 return 里，
 *     消费者就位于提供者**之上**——运行时必抛
 *     「useRagScope 必须在 RagScopeShell 内使用」，整页白屏。
 *
 *     这个错误 `tsc` 查不出（类型完全合法）、单测也不易覆盖（单测常直接 mock 掉 hook），
 *     只有真正挂到路由上才暴露。改造期 13 个页面全部踩中，即为此故。
 *
 *     修法不是「每个页面各自管作用域」（会退回横切机制各写一遍的老问题），
 *     而是把外壳拆两层：外层取得作用域并渲染布局，页面主体作为子组件天然处在
 *     provider 之内，通过 {@link RagScopeBody} 取到它。
 */
export interface RagScopePageProps {
  /** 已就绪的作用域。由 {@link RagScopeBody} 注入，页面不自行获取。 */
  scope: RagScopeContextValue
}

/**
 * 在作用域**之内**渲染页面主体。
 *
 * <p>页面主体只声明「我需要 scope」，位置由外壳决定：
 *
 * <pre>{@code
 * function Body({ scope }: RagScopePageProps) { ... }   // 处在 provider 内
 * export function RagDashboardPage() {
 *   return <RagScopeShell domain="总览" title="Dashboard" ...>
 *     {(scope) => <Body scope={scope} />}
 *   </RagScopeShell>
 * }
 * }</pre>
 */
export function RagScopeBody({
  children,
}: {
  children: (props: RagScopePageProps) => ReactNode
}): JSX.Element {
  const scope = useRagScope()
  return <>{children({ scope })}</>
}

/** {@link RagScopeShell} 的 `body` 形态返回的片段：页头主操作 + 页面主体。 */
export interface RagScopeBodyParts {
  /** 页头右侧主操作区。 */
  extra?: ReactNode
  /** 页面主体。 */
  content: ReactNode
}

export interface RagScopeShellProps {
  /** 页面标题。 */
  title: string
  /** 页面说明（一句话讲清这一页做什么）。 */
  description: ReactNode
  /** 所属治理域，用于面包屑与视觉分区提示。 */
  domain: '录入与校对' | '审核' | '规则配置' | '运维' | '总览'
  /**
   * 页面右上角的主操作区。
   *
   * ⚠️ 与 `children` 一样收 render prop，二者都拿到同一份作用域。
   *    若按钮还要绑定页面内部状态（loading / 刷新回调 / 弹窗开关），
   *    用 {@link RagScopeShellProps.body} 形态——它是本组件在 provider **之内**
   *    调用的一个函数，页面把状态写在那里面，extra 与主体自然共享。
   */
  extra?: (scope: RagScopeContextValue) => ReactNode
  /**
   * 页面主体（**必须是 render prop**）。
   *
   * ⚠️ 为什么不接受普通节点：主体需要作用域（target / ready / scopeKey），
   *    而作用域只在本组件内部才存在。一旦允许传节点，页面就会把
   *    `useRagScope()` 提到自己 render 的顶层——那正是白屏的成因，
   *    而且 `tsc` 当时是**查不出来**的。把签名收紧成函数，
   *    编译器就能在改造时把所有违规页面点出来。
   */
  children?: (scope: RagScopeContextValue) => ReactNode
  /**
   * **推荐形态**：在 provider 之内调用，一次返回「页头主操作 + 页面主体」。
   *
   * ⚠️ 为什么需要它：主操作按钮常要绑定页面内部状态（刷新用的 loading、
   *    新建弹窗开关、表单实例）。这些状态只有在 provider 之内才拿得到
   *    （因为要 target 才能发请求）。用 body 形态，页面把全部 state 与逻辑
   *    写在这一个函数里，`extra` 和 `content` 都是它的返回值，共享同一份状态，
   *    既不用提升、也不用把按钮挪到页面中部。
   *
   * ⚠️ 与 `children` 互斥：两者都给会用 `body`。
   */
  body?: (scope: RagScopeContextValue) => RagScopeBodyParts
  /**
   * 是否显示作用域选择器。
   * ⚠️ 规则配置域（审核员/知识点/字典）与作用域无关，关闭它避免误导。
   */
  scopeSelector?: boolean
}

/**
 * RAG 模块统一页外壳：标题 + 说明 + 作用域选择 + 治理票据 + 作用域上下文。
 */
export function RagScopeShell({
  title,
  description,
  domain,
  extra: extraProp,
  children,
  body,
  scopeSelector = true,
}: RagScopeShellProps): JSX.Element {
  const [tenants, setTenants] = useState<PlatformTenant[]>([])
  const [tenantError, setTenantError] = useState<string | null>(null)
  const [tenantId, setTenantId] = useState<string>('')
  /**
   * **缺省平台档**：产品口径「凡同时涉及租户与平台数据的页面，一律优先展示平台数据」。
   *
   * ⚠️ 安全约束不因换默认值而放松：平台档首屏是**只读视图**（读走登录态），
   *    写入仍需显式领取治理票据（缺票 403），租户头与平台档互斥的规则也不变。
   *    换到「本租户语料」且未选租户时依旧一条请求都不发。
   */
  const [scope, setScope] = useState<RagCorpusScope>('PLATFORM')

  const {
    ticket,
    status: ticketStatus,
    message: ticketMessage,
    remainingSeconds,
    obtain: obtainTicket,
    revoke: revokeTicket,
  } = useRagGovernanceTicket()

  const target = useMemo<RagTarget>(
    () => (scope === 'TENANT' ? tenantTarget(tenantId) : platformTarget(ticket)),
    [scope, tenantId, ticket],
  )

  const scopeKey = `${scope}:${tenantId}`

  useEffect(() => {
    let cancelled = false
    const run = async (): Promise<void> => {
      try {
        const list = await getPlatformTenants({ limit: 200 })
        if (cancelled) return
        setTenants(list)
      } catch (cause) {
        if (cancelled) return
        setTenantError(cause instanceof ApiError ? cause.message : '读取租户列表失败')
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [])

  /**
   * 切档 / 切租户即作废治理票据。
   * ⚠️ 为全局语料领的授权不该漂在页面上等着被下一次点击复用；
   *    重新领票会再走授权、限流与审计——多一条留痕是目的，不是成本。
   */
  useEffect(() => {
    revokeTicket()
  }, [tenantId, scope, revokeTicket])

  const tenantOptions = useMemo(
    () => tenants.map((t) => ({ value: t.tenantId, label: `${t.name}（${t.slug}）` })),
    [tenants],
  )

  const ready = isRagTargetReady(target)

  const ctx = useMemo<RagScopeContextValue>(
    () => ({ target, tenantId: tenantId || undefined, scope, scopeKey, ready }),
    [target, tenantId, scope, scopeKey, ready],
  )

  /**
   * 两种形态归一：
   *   - body 形态：在 provider 之内调用一次，拿到 {extra, content}；
   *   - render prop 形态：extra / children 各自调用。
   * ⚠️ body 优先，避免调用方两个都写时出现两套状态各画一半。
   */
  const parts = body ? body(ctx) : null
  const extraNode = parts ? parts.extra : extraProp ? extraProp(ctx) : null
  const bodyNode = parts ? parts.content : children ? children(ctx) : null

  return (
    <RagScopeContext.Provider value={ctx}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 16 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <Paragraph type="secondary" style={{ marginBottom: 2, fontSize: 12 }}>
              RAG 语料管理 · {domain}
            </Paragraph>
            <Typography.Title level={4} style={{ marginBottom: 4 }}>
              {title}
            </Typography.Title>
            <Paragraph type="secondary" style={{ marginBottom: 0 }}>
              {description}
            </Paragraph>
          </div>
          {extraNode && <div style={{ flexShrink: 0 }}>{extraNode}</div>}
        </div>

        {tenantError && (
          <Alert type="error" showIcon message="读取租户列表失败" description={tenantError} />
        )}

        {scopeSelector && (
          <Card size="small">
            <Space wrap>
              <Text>作用域</Text>
              <Segmented<RagCorpusScope>
                value={scope}
                onChange={(v) => setScope(v)}
                options={[
                  // ⚠️ 平台数据优先：既作默认档，也排在选项首位。
                  {
                    label: '平台全局语料',
                    value: 'PLATFORM',
                    title: '平台维护、全部租户共享的语料（写需先领治理票据）',
                  },
                  { label: '本租户语料', value: 'TENANT' },
                  {
                    label: '跨租户盘点',
                    value: 'ALL',
                    disabled: true,
                    title: '需平台运维数据源（BYPASSRLS 只读角色），属第四刀·第三档',
                  },
                ]}
              />
              <Text>租户</Text>
              <Select
                showSearch
                style={{ width: 300 }}
                disabled={scope !== 'TENANT'}
                placeholder={
                  scope === 'TENANT'
                    ? '选择租户（本档管理端点要求 X-Tenant-Id）'
                    : '平台全局语料不属于任何租户'
                }
                value={scope === 'TENANT' ? tenantId || undefined : undefined}
                onChange={(v) => setTenantId(v ?? '')}
                options={tenantOptions}
                filterOption={(input, option) =>
                  String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                }
              />
            </Space>

            <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
              平台数据优先：首屏默认展示「平台全局语料」（只读）；要看某个租户的语料，
              请切到「本租户语料」档并选择租户。
            </Paragraph>

            {scope === 'TENANT' && tenantId.trim() === '' && (
              <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                租户档未选择租户时不会发起任何 RAG 请求——缺租户头后端返回 400，
                这是本地配置问题，不该被伪装成后端故障。
              </Paragraph>
            )}

            {scope === 'PLATFORM' && (
              <Space direction="vertical" size={8} style={{ marginTop: 8, width: '100%' }}>
                <Alert
                  type="info"
                  showIcon
                  message="平台全局语料：读写分开——读用登录态，写要治理票据"
                  description="这里看的是平台自己维护、全部租户共享的那批语料。登记、校验流转、重跑索引都会影响所有租户的检索结果，所以后端除「平台域身份」外还校验一张短时治理票据的档位；缺票是 403。"
                />
                <Space wrap align="center">
                  <Button
                    size="small"
                    type={ticket ? 'default' : 'primary'}
                    loading={ticketStatus === 'issuing'}
                    onClick={obtainTicket}
                  >
                    {ticket ? '重新领取治理票据' : '领取治理票据'}
                  </Button>
                  <Button size="small" disabled={ticket === null} onClick={revokeTicket}>
                    作废
                  </Button>
                  {ticket ? (
                    <Tag color="green">
                      票据 {ticket.jti.slice(0, 8)}… · 剩余 {remainingSeconds} 秒
                    </Tag>
                  ) : (
                    <Text type="secondary">
                      {ticketStatus === 'issuing' ? '领取中…' : '当前无票据：本档只读'}
                    </Text>
                  )}
                </Space>
                {ticketMessage && (
                  <Alert
                    type="error"
                    showIcon
                    message="领取治理票据失败"
                    description={ticketMessage}
                  />
                )}
              </Space>
            )}
          </Card>
        )}

        <div>{bodyNode}</div>
      </Space>
    </RagScopeContext.Provider>
  )
}

/**
 * 未就绪时的占位（租户档未选租户）。
 *
 * ⚠️ 刻意不是空表格：空表格会被理解成「这个租户没有语料」，
 *    而真实原因是「还没选租户」。两种含义必须区分。
 */
export function RagNotReady({ hint }: { hint?: string }): JSX.Element {
  return (
    <Card>
      <Space direction="vertical" align="center" style={{ width: '100%', padding: '32px 0' }}>
        <Text type="secondary">
          {hint ?? '本租户语料档请先选择租户；要查看平台数据请切回默认的「平台全局语料」档（只读）'}
        </Text>
      </Space>
    </Card>
  )
}

/** 便捷：判断并渲染未就绪占位。返回 true 表示已渲染占位，调用方应直接 return。 */
export function useRagReadyGuard(): { ready: boolean } {
  const { ready } = useRagScope()
  return { ready }
}

