/**
 * RAG 语料数据管理（admin-console 平台侧）。
 *
 * ⚠️ **三档作用域**（RAG-D11 第四刀）：语料由谁维护决定了这里能看什么——
 *    `TENANT`（本租户私有语料，需选租户，读写俱全）、
 *    `PLATFORM`（平台维护的全局语料，**不选租户**，第二刀起**可写**：
 *    登记 / 校验流转 / 重跑索引要先领一枚短时**治理票据**，票据只存内存、换档即作废）、
 *    `ALL`（跨租户盘点，需运维数据源，后端 501，入口先占位）。
 *
 *    作用域**由分段控件显式声明**并落到 `X-Rag-Scope` 头，
 *    刻意不由「有没有选租户」隐式推断：那样「忘记选租户」会静默变成
 *    一次跨租户或全局查询，把本地配置错误伪装成正常结果。
 *
 * ⚠️ **平台档写为什么还要一张票据**：平台**登录**令牌证明的是「进了控制台」，
 *    不是「被授权改动所有租户共享的语料」。后端写闸门额外校验票据 claim 里的档位，
 *    缺票 403；签发那一步要 `platform.resource.manage` 权限、受限流、且每票写一条平台审计。
 *    界面据此在无票时不给写入口，并显示「以哪张票（jti）、还剩几秒」——
 *    管理员必须知道自己此刻是以什么授权在动全局语料。
 *
 * ⚠️ `TENANT` 档仍必须选租户：该档的管理端点缺 `X-Tenant-Id` 返回 400
 *    （fail-closed，不静默空列表）。admin-console 本身是**平台控制台**
 *    （无自己的租户上下文），所以租户档里的租户必须显式选择。
 *
 * ⚠️ **通道差异**：RAG 是独立服务（默认 8081），与平台 API（8080）不同源。
 *    开发态靠 vite 代理 `/api/v1` → 8081（**必须排在 `/api` 之前**，
 *    否则会被前缀更短的 `/api` 规则截获）。
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Modal, Segmented, Select, Space, Tabs, Tag, Typography } from 'antd'
import { getPlatformTenants } from '@/api/adminApi'
import type { PlatformTenant } from '@/api/types'
import {
  getRagDailyImports,
  getRagDailySearches,
  isRagTargetReady,
  platformTarget,
  tenantTarget,
  ApiError,
} from '@/api/ragClient'
import type { RagCorpusScope, RagTarget } from '@/api/ragClient'
import type {
  RagDailyImportCount,
  RagDailySearchCount,
  RagSource,
  RagSourceQuery,
  RagVerificationRequest,
} from '@/api/ragTypes'
import { useRagManagement } from '@/features/rag/useRagManagement'
import { useRagGovernanceTicket } from '@/features/rag/useRagGovernanceTicket'
import { RagSourceTable, RagRegisterForm } from './RagSourceTable'
import { RagSourceDrawer } from './RagSourceDrawer'
import { RagStatisticsPanel } from './RagStatisticsPanel'
import { RagMaintenancePanel } from './RagMaintenancePanel'
import { currentPrincipalId } from './ragUi'

const { Text, Paragraph } = Typography

export function RagManagementPage(): JSX.Element {
  const [tenants, setTenants] = useState<PlatformTenant[]>([])
  const [tenantError, setTenantError] = useState<string | null>(null)
  const [tenantId, setTenantId] = useState<string>('')
  /** 作用域档位（缺省租户档——与本刀之前的行为逐字节一致）。 */
  const [scope, setScope] = useState<RagCorpusScope>('TENANT')
  const [selected, setSelected] = useState<RagSource | null>(null)
  const [registerOpen, setRegisterOpen] = useState(false)
  /**
   * 维护面板的 remount key。
   *
   * ⚠️ 面板内部状态（问题清单 / 重跑结果 / 探针命中）是**租户作用域**的，
   *    组件自己不知道租户换了；靠换 key 重挂，才不会把 A 租户的结论当成 B 租户的。
   */
  const [maintenanceKey, setMaintenanceKey] = useState(0)

  const [searches, setSearches] = useState<RagDailySearchCount[]>([])
  const [imports, setImports] = useState<RagDailyImportCount[]>([])

  /**
   * 平台全局语料的治理票据（**只在内存**，见 `@/api/ragTicket`）。
   * 换档位 / 换租户即作废——清票动作在下面的「切换即清空」effect 里，
   * 不靠管理员记得点「作废」。
   */
  const {
    ticket,
    status: ticketStatus,
    message: ticketMessage,
    remainingSeconds,
    obtain: obtainTicket,
    revoke: revokeTicket,
  } = useRagGovernanceTicket()

  /**
   * 一次页面上的「查哪一批行」。
   *
   * ⚠️ 用 `useMemo` 固定引用：子组件（抽屉 / 维护面板）把它放进 effect 依赖，
   *    每次渲染换引用会让它们反复重发请求。
   * ⚠️ 平台档**刻意不带租户 ID**：后端同时收到 `X-Rag-Scope: PLATFORM` 与
   *    `X-Tenant-Id` 时返回 400——两种身份互斥，同时给出说明调用方自己不清楚要看什么。
   * ⚠️ 票据随 target 一起下去，但**只有写请求会带它**：`ragClient` 里读路径用
   *    `scopeHeaders`、写路径用 `writeHeaders`，所以「把票塞进 target」不会
   *    让读调用也换成票据凭据（那会让审计里两类调用失去区分度）。
   */
  const target = useMemo<RagTarget>(
    () => (scope === 'TENANT' ? tenantTarget(tenantId) : platformTarget(ticket)),
    [scope, tenantId, ticket],
  )

  /**
   * 读请求的重载键：档位 + 租户，**刻意不含票据**。
   *
   * ⚠️ 若 effect 直接依赖 `target`，管理员每领一次票据（TTL 上界 900 秒，
   *    到期还会自动换一张）列表就带默认筛选条件重查一遍，
   *    把他刚设好的「只看未通过 / 关键词」冲掉——票据是授权态，不是查询条件。
   */
  const readKey = `${scope}:${tenantId}`

  const {
    sources,
    stats,
    action,
    loadSources,
    loadStats,
    verify,
    register,
    retrySources,
    retryStats,
    resetAction,
    resetAll,
  } = useRagManagement()

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
   * 切租户**或切作用域档**即清空上一批数据，并**作废治理票据**。
   *
   * ⚠️ 不这么做，右侧详情抽屉、校验动作提示、维护面板结果会**留在页面上**，
   *    看起来像是新租户／全局语料的数据——治理结论张冠李戴，比空列表危险得多。
   * ⚠️ 票据也一起清掉（方案 A7）：为全局语料领的授权不该漂在页面上等着被下一次点击复用。
   *    重新领取会再走一遍授权、限流与平台审计——多一条留痕不是成本，是目的。
   *    首渲染的 tenantId 为空串，清空是 no-op，故无需跳过。
   */
  useEffect(() => {
    setSelected(null)
    setMaintenanceKey((k) => k + 1)
    resetAll()
    revokeTicket()
    setSearches([])
    setImports([])
  }, [tenantId, scope, resetAll, revokeTicket])

  /** 选定作用域（租户档还需选租户）即触发首次加载：列表 + 统计 + 每日报表。 */
  useEffect(() => {
    if (!isRagTargetReady(target)) return
    loadSources(target, { includeStale: true, limit: 100 })
    loadStats(target)
    let cancelled = false
    const run = async (): Promise<void> => {
      try {
        const [s, i] = await Promise.all([
          getRagDailySearches(target, 30),
          getRagDailyImports(target, 30),
        ])
        if (cancelled) return
        setSearches(s.searchesByDay)
        setImports(i.importsByDay)
      } catch {
        // 每日报表失败不阻断主流程：统计卡与列表才是主信息
        if (cancelled) return
        setSearches([])
        setImports([])
      }
    }
    void run()
    return () => {
      cancelled = true
    }
    // 依赖 readKey 而不是 target：见上面 readKey 的说明（票据变化不该重查列表）。
  }, [readKey, loadSources, loadStats])

  const onQuery = useCallback(
    (query: RagSourceQuery) => {
      loadSources(target, query)
    },
    [target, loadSources],
  )

  const onVerified = useCallback(
    (sourceId: string, payload: RagVerificationRequest) => {
      verify(target, sourceId, payload)
    },
    [target, verify],
  )

  const tenantOptions = useMemo(
    () =>
      tenants.map((t) => ({
        value: t.tenantId,
        label: `${t.name}（${t.slug}）`,
      })),
    [tenants],
  )

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <div>
        <Typography.Title level={4} style={{ marginBottom: 4 }}>
          RAG 语料数据管理
        </Typography.Title>
        <Paragraph type="secondary" style={{ marginBottom: 0 }}>
          治理语料的准入与时效：登记 → 校验流转 → 可检索。登记只是纳入治理，
          <b>不等于可检索</b>；撤回是终态、不可复活；这里没有删除入口。
        </Paragraph>
      </div>

      {tenantError && <Alert type="error" showIcon message="读取租户列表失败" description={tenantError} />}

      <Card size="small">
        <Space wrap>
          <Text>作用域</Text>
          <Segmented<RagCorpusScope>
            value={scope}
            onChange={(v) => setScope(v)}
            options={[
              { label: '本租户语料', value: 'TENANT' },
              {
                label: '平台全局语料',
                value: 'PLATFORM',
                title: '平台维护、全部租户共享的语料（写需先领治理票据）',
              },
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
            style={{ width: 320 }}
            disabled={scope !== 'TENANT'}
            placeholder={scope === 'TENANT' ? '选择租户（本档管理端点要求 X-Tenant-Id）' : '平台全局语料不属于任何租户'}
            value={scope === 'TENANT' ? tenantId || undefined : undefined}
            onChange={(v) => setTenantId(v ?? '')}
            options={tenantOptions}
            filterOption={(input, option) =>
              String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())
            }
          />
        </Space>
        {scope === 'TENANT' && tenantId.trim() === '' && (
          <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
            租户档未选择租户时不会发起任何 RAG 请求，下方相关入口已一并置灰——
            缺租户头后端会返回 400，这是本地配置问题，不该被伪装成后端故障。
          </Paragraph>
        )}
        {scope === 'PLATFORM' && (
          <Space direction="vertical" size={8} style={{ marginTop: 8, width: '100%' }}>
            <Alert
              type="info"
              showIcon
              message="平台全局语料：读写分开——读用登录态，写要治理票据"
              description="这里看的是平台自己维护、全部租户共享的那批语料（租户列等于全局哨兵）。登记、校验流转、重跑索引都会影响所有租户的检索结果，所以后端除「平台域身份」外还校验一张短时治理票据的档位声明；缺票是 403。票据只存在内存里，换档或过期即作废，签发每一步都进平台审计。"
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

      <Tabs
        defaultActiveKey="sources"
        items={[
          {
            key: 'sources',
            label: '语料来源',
            children: (
              <>
                <RagSourceTable
                  state={sources}
                  target={target}
                  onQuery={onQuery}
                  onRefresh={retrySources}
                  onSelect={setSelected}
                  onRegister={() => setRegisterOpen(true)}
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
                />
                <Modal
                  title={scope === 'PLATFORM' ? '登记语料来源（写入全局语料桶）' : '登记语料来源'}
                  open={registerOpen}
                  onCancel={() => setRegisterOpen(false)}
                  footer={null}
                  destroyOnHidden
                >
                  <RagRegisterForm
                    submitting={action.status === 'submitting'}
                    registeredBy={currentPrincipalId()}
                    onSubmit={(payload) => {
                      register(target, payload)
                      setRegisterOpen(false)
                      retrySources()
                    }}
                  />
                </Modal>
              </>
            ),
          },
          {
            key: 'stats',
            label: '统计概览',
            children: (
              <RagStatisticsPanel
                state={stats}
                searches={searches}
                imports={imports}
                onRefresh={retryStats}
              />
            ),
          },
          {
            key: 'maintenance',
            label: '维护',
            children: <RagMaintenancePanel key={maintenanceKey} target={target} />,
          },
        ]}
      />
    </Space>
  )
}
