import { useEffect, useState, useCallback, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Card,
  Table,
  Tag,
  Button,
  Space,
  Typography,
  Alert,
  App as AntApp,
  Modal,
  Select,
  Tooltip,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  getPlatformTenants,
  suspendPlatformTenant,
  resumePlatformTenant,
  decommissionPlatformTenant,
  finalizeDecommissionPlatformTenant,
  cancelPlatformTenant,
} from '@/api/adminApi'
import type { PlatformTenant } from '@/api/types'
import { TenantProvisionForm } from './TenantProvisionForm'
import { useRegistrationModal } from './catalog/useRegistrationModal'
import { emptyTextWhileLoading } from '@/shared/loadingState'

/**
 * 生命周期状态机配色（与后端 TenantLifecycle 一致）。
 * ⚠️ DECOMMISSIONED / CANCELLED 为终态，无出边。
 */
const LIFECYCLE_COLOR: Record<string, string> = {
  PENDING_SETUP: 'blue',
  ACTIVE: 'green',
  SUSPENDED: 'gold',
  DECOMMISSIONING: 'volcano',
  DECOMMISSIONED: 'red',
  CANCELLED: 'default',
}

/**
 * L2（UI 评审 2026-11-17）：徽章统一为中文语义，枚举值保留在 tooltip 供管理员精确对应后端。
 * ⚠️ 仅改「显示层」，筛选 value / nextActions / 状态机迁移仍用英文枚举，逻辑零改动。
 */
const LIFECYCLE_LABEL: Record<string, string> = {
  PENDING_SETUP: '待开通',
  ACTIVE: '已启用',
  SUSPENDED: '已暂停',
  DECOMMISSIONING: '退役中',
  DECOMMISSIONED: '已退役',
  CANCELLED: '已取消',
}

function lifecycleTag(s: string): JSX.Element {
  return (
    <Tooltip title={`生命周期枚举：${s}`}>
      <Tag color={LIFECYCLE_COLOR[s] ?? 'default'}>
        {LIFECYCLE_LABEL[s] ?? s}
      </Tag>
    </Tooltip>
  )
}

/**
 * 当前状态下允许的生命周期迁移（与后端 TenantLifecycle 状态机 + DB 触发器
 * {@code enforce_tenant_lifecycle_transition} 一致）。
 *
 * ⚠️ **不要把 DECOMMISSIONING 当成终态**：状态机与 DB 触发器都允许
 *    {@code DECOMMISSIONING → DECOMMISSIONED}，此前本函数漏了这一条，
 *    导致租户退役后停在半路——控制台既无法终态化也无法回收。
 *    同样地 {@code PENDING_SETUP → CANCELLED} 也是合法迁移，此前一并被漏掉。
 *
 * ⚠️ 长期做法：让后端下发 allowed-transitions（见评测报告 P0-1），
 *    前端不再硬编码第二份状态机——前后端各写一份，漂移是必然的。
 */
function nextActions(t: PlatformTenant): Array<{ value: string; label: string }> {
  switch (t.lifecycleState) {
    case 'PENDING_SETUP':
      return [
        { value: 'ACTIVE', label: 'ACTIVE（启用上线）' },
        { value: 'CANCELLED', label: 'CANCELLED（取消开通）' },
      ]
    case 'ACTIVE':
      return [
        { value: 'SUSPENDED', label: 'SUSPENDED（暂停，拒新任务）' },
        { value: 'DECOMMISSIONING', label: 'DECOMMISSIONING（退役处置）' },
      ]
    case 'SUSPENDED':
      return [
        { value: 'ACTIVE', label: 'ACTIVE（恢复）' },
        { value: 'DECOMMISSIONING', label: 'DECOMMISSIONING（退役处置）' },
      ]
    case 'DECOMMISSIONING':
      return [{ value: 'DECOMMISSIONED', label: 'DECOMMISSIONED（退役完成，终态）' }]
    default:
      return [] // DECOMMISSIONED / CANCELLED：终态，无出边
  }
}

/**
 * 租户注册表（平台身份域 · GET/POST /api/platform/v1/tenants）。
 *
 * ⛔ 旧「我的租户」自服务分支已移除：平台主体不属于任何租户，
 *    平台控制台不再承载租户内自服务视图（租户域归租户控制台）。
 * ⛔ 生命周期不是任意置状态：每次迁移走状态机（后端 409 拒绝非法迁移），
 *    前端按当前状态给出**合法的下一步动作**，而不是自由 Select。
 */
export function TenantRegistryPage(): JSX.Element {
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [data, setData] = useState<PlatformTenant[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined)
  const [target, setTarget] = useState<PlatformTenant | null>(null)
  /** 目标租户的合法下一步动作（'SUSPENDED' | 'ACTIVE' | 'DECOMMISSIONING'） */
  const [nextState, setNextState] = useState<string | null>(null)
  /** 开通租户弹窗（C37-3：「新增」用弹窗） */
  const registration = useRegistrationModal()


  /** L2/L3：各状态计数（供徽章图例与表格下方概览），随表格数据联动。 */
  const statusCounts = useMemo(() => {
    const counts: Record<string, number> = {}
    for (const t of data) counts[t.lifecycleState] = (counts[t.lifecycleState] ?? 0) + 1
    return counts
  }, [data])
  const configuredCount = useMemo(
    () => data.filter((t) => t.plan != null && t.plan.trim() !== '').length,
    [data],
  )

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const rows = await getPlatformTenants({ status: statusFilter, limit: 100 })
      setData(rows)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载租户列表失败')
    } finally {
      setLoading(false)
    }
  }, [statusFilter])

  useEffect(() => {
    void load()
  }, [load])

  const openLifecycle = (t: PlatformTenant): void => {
    const actions = nextActions(t)
    setTarget(t)
    setNextState(actions.length > 0 ? (actions[0]?.value ?? null) : null)
  }

  const confirmLifecycle = async (): Promise<void> => {
    if (!target || !nextState) return
    try {
      if (nextState === 'SUSPENDED') await suspendPlatformTenant(target.tenantId)
      else if (nextState === 'ACTIVE') await resumePlatformTenant(target.tenantId)
      else if (nextState === 'DECOMMISSIONING') await decommissionPlatformTenant(target.tenantId)
      // ⚠️ 终态迁移必须各走各的端点：没有通用的「设置状态」入口。
      else if (nextState === 'DECOMMISSIONED') await finalizeDecommissionPlatformTenant(target.tenantId)
      else if (nextState === 'CANCELLED') await cancelPlatformTenant(target.tenantId)
      message.success(`租户 ${target.slug} → ${nextState}`)
      setTarget(null)
      void load()
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        message.error('非法状态迁移（409）：状态可能已被其他人变更，请刷新后重试')
      } else {
        message.error(e instanceof Error ? e.message : '操作失败')
      }
    }
  }

  return (
    <div>
      <Typography.Title level={3}>租户注册表</Typography.Title>
      <Card
        styles={{ body: { paddingBottom: 12 } }}
        extra={
          <Space>
            <Select
              allowClear
              placeholder="按生命周期筛选"
              style={{ width: 180 }}
              value={statusFilter}
              onChange={(v) => setStatusFilter(v)}
              options={Object.keys(LIFECYCLE_COLOR).map((s) => ({
                value: s,
                label: `${LIFECYCLE_LABEL[s] ?? s}（${s}）`,
              }))}
            />
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={registration.openModal}
            >
              开通租户
            </Button>
          </Space>
        }
      >
        {error && (
          <Alert type="error" showIcon message="加载失败" description={error}
            style={{ marginBottom: 16 }} />
        )}
        <Table<PlatformTenant>
          rowKey="tenantId"
          loading={loading}
          dataSource={data}
          pagination={false}
          locale={{ emptyText: emptyTextWhileLoading(loading, '暂无租户') }}
          columns={[
            { title: 'Slug', dataIndex: 'slug', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
            { title: '名称', dataIndex: 'name' },
            {
              title: '生命周期',
              dataIndex: 'lifecycleState',
              render: (s: string) => lifecycleTag(s),
            },
            {
              title: '套餐',
              dataIndex: 'plan',
              render: (v: string | null) =>
                v != null && v.trim() !== '' ? (
                  v
                ) : (
                  <Tooltip title="该租户尚未配置套餐">
                    <Typography.Text type="secondary">未配置</Typography.Text>
                  </Tooltip>
                ),
            },
            { title: '策略版本', dataIndex: 'effectivePolicyVersion', width: 100 },
            {
              title: '操作',
              key: 'actions',
              render: (_, t) => (
                <Space>
                  <Button size="small" onClick={() => navigate(`/tenants/${t.tenantId}`)}>
                    详情
                  </Button>
                  {nextActions(t).length > 0 && (
                    <Button size="small" onClick={() => openLifecycle(t)}>
                      生命周期
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />

        {/* L2/L3：图例 + 概览。既解释徽章与「未配置」含义，又填充表格下方空白。 */}
        {!loading && data.length > 0 && (
          <div style={{
            display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '8px 16px',
            marginTop: 16, paddingTop: 12, borderTop: '1px solid #f0f0f0',
          }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              共 {data.length} 个租户 · 已配置套餐 {configuredCount} 个
            </Typography.Text>
            <Space size={[4, 8]} wrap>
              {Object.keys(LIFECYCLE_COLOR).map((s) =>
                (statusCounts[s] ?? 0) > 0 ? (
                  <span key={s} style={{ whiteSpace: 'nowrap' }}>
                    {lifecycleTag(s)} <Typography.Text type="secondary">{statusCounts[s]}</Typography.Text>
                  </span>
                ) : null,
              )}
            </Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              套餐「未配置」＝该租户尚未开通套餐
            </Typography.Text>
          </div>
        )}
      </Card>

      <Modal
        open={target !== null}
        title={`生命周期变更 · ${target?.slug ?? ''}`}
        onCancel={() => setTarget(null)}
        onOk={() => void confirmLifecycle()}
        okText="确认"
        cancelText="取消"
        okButtonProps={{ disabled: !nextState }}
      >
        {target && nextActions(target).length > 0 ? (
          <>
            <p>当前状态：<Tag color={LIFECYCLE_COLOR[target.lifecycleState] ?? 'default'}>
              {target.lifecycleState}
            </Tag></p>
            <p>迁移到：</p>
            <Select
              style={{ width: '100%' }}
              value={nextState}
              onChange={(v) => setNextState(v)}
              options={nextActions(target)}
            />
            <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
              仅显示当前状态下的<b>合法迁移</b>（后端状态机强制；非法迁移返回 409）。
              终态（DECOMMISSIONED / CANCELLED）无出边。
            </Typography.Paragraph>
          </>
        ) : (
          <Typography.Text type="secondary">当前状态无可用迁移。</Typography.Text>
        )}
      </Modal>

      {/* 开通租户走弹窗（C37-3），表单仍是同一张 TenantProvisionForm */}
      <Modal
        open={registration.open}
        title="开通租户"
        onCancel={registration.closeModal}
        footer={null}
        destroyOnHidden
      >
        <TenantProvisionForm
          onProvisioned={(t) => {
            registration.closeModal()
            navigate(`/tenants/${t.tenantId}`)
          }}
        />
      </Modal>
    </div>
  )
}
