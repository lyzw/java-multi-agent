import { useEffect, useState, useCallback } from 'react'
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
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  getPlatformTenants,
  suspendPlatformTenant,
  resumePlatformTenant,
  decommissionPlatformTenant,
} from '@/api/adminApi'
import type { PlatformTenant } from '@/api/types'
import { TenantProvisionForm } from './TenantProvisionForm'
import { useRegistrationModal } from './catalog/useRegistrationModal'

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

  /** 当前状态下允许的生命周期迁移（与 TenantLifecycle 状态机一致） */
  function nextActions(t: PlatformTenant): Array<{ value: string; label: string }> {
    switch (t.lifecycleState) {
      case 'PENDING_SETUP':
        return [{ value: 'ACTIVE', label: 'ACTIVE（启用上线）' }]
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
      default:
        return [] // DECOMMISSIONING / DECOMMISSIONED / CANCELLED：无前端动作
    }
  }

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
              options={Object.keys(LIFECYCLE_COLOR).map((s) => ({ value: s, label: s }))}
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
          locale={{ emptyText: '暂无租户' }}
          columns={[
            { title: 'Slug', dataIndex: 'slug', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
            { title: '名称', dataIndex: 'name' },
            {
              title: '生命周期',
              dataIndex: 'lifecycleState',
              render: (s: string) => (
                <Tag color={LIFECYCLE_COLOR[s] ?? 'default'}>{s}</Tag>
              ),
            },
            { title: '套餐', dataIndex: 'plan', render: (v: string | null) => v ?? '-' },
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
        destroyOnClose
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
