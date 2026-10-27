/**
 * RAG 语料数据管理（admin-console 平台侧）。
 *
 * ⚠️ **为什么必须有租户选择器**：RAG 管理端点全部要求 `X-Tenant-Id`，
 *    缺失返回 400（fail-closed，不静默空列表）。admin-console 本身是
 *    **平台控制台**（无自己的租户上下文），所以租户必须显式选择——
 *    这不是 UI 冗余，而是后端契约的必然结果。
 *
 * ⚠️ **通道差异**：RAG 是独立服务（默认 8081），与平台 API（8080）不同源。
 *    开发态靠 vite 代理 `/api/v1` → 8081（**必须排在 `/api` 之前**，
 *    否则会被前缀更短的 `/api` 规则截获）。
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Card, Modal, Select, Space, Tabs, Typography } from 'antd'
import { getPlatformTenants } from '@/api/adminApi'
import type { PlatformTenant } from '@/api/types'
import {
  getRagDailyImports,
  getRagDailySearches,
  ApiError,
} from '@/api/ragClient'
import type {
  RagDailyImportCount,
  RagDailySearchCount,
  RagSource,
  RagSourceQuery,
  RagVerificationRequest,
} from '@/api/ragTypes'
import { useRagManagement } from '@/features/rag/useRagManagement'
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
   * 切租户即清空上一个租户的数据。
   *
   * ⚠️ 不这么做，右侧详情抽屉、校验动作提示、维护面板结果会**留在页面上**，
   *    看起来像是新租户的数据——治理结论张冠李戴，比空列表危险得多。
   *    首渲染的 tenantId 为空串，清空是 no-op，故无需跳过。
   */
  useEffect(() => {
    setSelected(null)
    setMaintenanceKey((k) => k + 1)
    resetAll()
    setSearches([])
    setImports([])
  }, [tenantId, resetAll])

  /** 选择租户即触发首次加载（列表 + 统计 + 每日报表）。 */
  useEffect(() => {
    if (tenantId.trim() === '') return
    loadSources(tenantId, { includeStale: true, limit: 100 })
    loadStats(tenantId)
    let cancelled = false
    const run = async (): Promise<void> => {
      try {
        const [s, i] = await Promise.all([
          getRagDailySearches(tenantId, 30),
          getRagDailyImports(tenantId, 30),
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
  }, [tenantId, loadSources, loadStats])

  const onQuery = useCallback(
    (query: RagSourceQuery) => {
      loadSources(tenantId, query)
    },
    [tenantId, loadSources],
  )

  const onVerified = useCallback(
    (sourceId: string, payload: RagVerificationRequest) => {
      verify(tenantId, sourceId, payload)
    },
    [tenantId, verify],
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
          <Text>租户</Text>
          <Select
            showSearch
            style={{ width: 320 }}
            placeholder="选择租户（RAG 管理端点要求 X-Tenant-Id）"
            value={tenantId || undefined}
            onChange={(v) => setTenantId(v ?? '')}
            options={tenantOptions}
            filterOption={(input, option) =>
              String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())
            }
          />
        </Space>
        {tenantId.trim() === '' && (
          <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
            未选择租户时不会发起任何 RAG 请求，下方相关入口已一并置灰——
            缺租户头后端会返回 400，这是本地配置问题，不该被伪装成后端故障。
          </Paragraph>
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
                  tenantId={tenantId}
                  onQuery={onQuery}
                  onRefresh={retrySources}
                  onSelect={setSelected}
                  onRegister={() => setRegisterOpen(true)}
                />
                <RagSourceDrawer
                  source={selected}
                  tenantId={tenantId}
                  onClose={() => {
                    setSelected(null)
                    resetAction()
                  }}
                  onVerified={onVerified}
                  actionStatus={action.status}
                  actionMessage={action.message}
                />
                <Modal
                  title="登记语料来源"
                  open={registerOpen}
                  onCancel={() => setRegisterOpen(false)}
                  footer={null}
                  destroyOnClose
                >
                  <RagRegisterForm
                    submitting={action.status === 'submitting'}
                    registeredBy={currentPrincipalId()}
                    onSubmit={(payload) => {
                      register(tenantId, payload)
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
            children: <RagMaintenancePanel key={maintenanceKey} tenantId={tenantId} />,
          },
        ]}
      />
    </Space>
  )
}
