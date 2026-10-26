import { useEffect, useState, useCallback } from 'react'
import { useParams } from 'react-router-dom'
import {
  Card,
  Tabs,
  Table,
  Tag,
  Button,
  Space,
  Typography,
  Alert,
  Spin,
  Descriptions,
  InputNumber,
  Input,
  Popconfirm,
  App as AntApp,
} from 'antd'
import {
  getPlatformTenant,
  getPlatformQuota,
  updatePlatformQuota,
  getPlatformCapabilities,
  grantPlatformCapability,
  revokePlatformCapability,
} from '@/api/adminApi'
import type {
  PlatformTenant,
  PlatformQuota,
  PlatformCapability,
} from '@/api/types'

/** 生命周期状态机配色（与 TenantLifecycle 一致） */
const LIFECYCLE_COLOR: Record<string, string> = {
  PENDING_SETUP: 'blue',
  ACTIVE: 'green',
  SUSPENDED: 'gold',
  DECOMMISSIONING: 'volcano',
  DECOMMISSIONED: 'red',
  CANCELLED: 'default',
}

/**
 * 租户详情（平台身份域 · /api/platform/v1/tenants/{id}）。
 *
 * 三个页签：基本信息 / 配额（读写）/ 能力授权（授予·撤销）。
 * ⛔ 无「主体列表」「RLS 校验」页签：平台主体不查租户内数据（跨域隔离）；
 *    RLS 校验属租户运维通道，不在平台控制台暴露。
 */
export function TenantDetailPage(): JSX.Element {
  const { id = '' } = useParams()
  const { message } = AntApp.useApp()
  const [detail, setDetail] = useState<PlatformTenant | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // ── 配额 ──
  const [quota, setQuota] = useState<PlatformQuota | null>(null)
  const [quotaSaving, setQuotaSaving] = useState(false)

  // ── 能力授权 ──
  const [caps, setCaps] = useState<PlatformCapability[]>([])
  const [newResource, setNewResource] = useState('')
  const [newScope, setNewScope] = useState('TENANT')
  const [granting, setGranting] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [t, q, c] = await Promise.all([
        getPlatformTenant(id),
        getPlatformQuota(id).catch(() => null),
        getPlatformCapabilities(id).catch(() => []),
      ])
      setDetail(t)
      setQuota(q)
      setCaps(c)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载租户详情失败')
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    if (id) void load()
  }, [id, load])

  const saveQuota = async (): Promise<void> => {
    if (!quota) return
    setQuotaSaving(true)
    try {
      const next = await updatePlatformQuota(id, quota)
      setQuota(next)
      message.success('配额已更新')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '配额更新失败')
    } finally {
      setQuotaSaving(false)
    }
  }

  const grant = async (): Promise<void> => {
    const code = newResource.trim()
    if (!code) return
    setGranting(true)
    try {
      await grantPlatformCapability(id, {
        resourceCode: code,
        scope: newScope.trim().toUpperCase(),
      })
      message.success(`能力 ${code} 已授予`)
      setNewResource('')
      setCaps(await getPlatformCapabilities(id))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '授予失败')
    } finally {
      setGranting(false)
    }
  }

  const revoke = async (code: string): Promise<void> => {
    try {
      await revokePlatformCapability(id, code)
      message.success(`能力 ${code} 已撤销`)
      setCaps(await getPlatformCapabilities(id))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '撤销失败')
    }
  }

  if (loading) return <Spin spinning style={{ width: '100%', marginTop: 80 }} />

  if (error) {
    return <Alert type="error" showIcon message="加载失败" description={error} />
  }

  if (!detail) {
    return <Alert type="error" showIcon message="租户不存在" />
  }

  return (
    <div>
      <Typography.Title level={3}>租户详情 · {detail.slug}</Typography.Title>
      <Descriptions
        bordered
        size="small"
        style={{ marginBottom: 16 }}
        column={2}
        items={[
          { key: 'slug', label: 'Slug', children: <Typography.Text code>{detail.slug}</Typography.Text> },
          { key: 'name', label: '名称', children: detail.name },
          {
            key: 'lifecycle',
            label: '生命周期',
            children: (
              <Tag color={LIFECYCLE_COLOR[detail.lifecycleState] ?? 'default'}>
                {detail.lifecycleState}
              </Tag>
            ),
          },
          { key: 'plan', label: '套餐', children: detail.plan ?? '-' },
          { key: 'policyVer', label: '生效策略版本', children: detail.effectivePolicyVersion },
          { key: 'authzVer', label: '租户授权版本', children: detail.tenantAuthzVersion },
        ]}
      />

      <Tabs
        items={[
          {
            key: 'quota',
            label: '配额',
            children: quota ? (
              <Card title="资源配额（平台级上限）">
                <Space direction="vertical" style={{ width: '100%' }} size={12}>
                  <Space wrap align="end">
                    <div>
                      <Typography.Text type="secondary">Agent 数上限</Typography.Text>
                      <div>
                        <InputNumber
                          min={0}
                          value={quota.agentLimit}
                          onChange={(v) =>
                            setQuota({ ...quota, agentLimit: typeof v === 'number' ? v : 0 })
                          }
                        />
                      </div>
                    </div>
                    <div>
                      <Typography.Text type="secondary">Run 并发上限</Typography.Text>
                      <div>
                        <InputNumber
                          min={0}
                          value={quota.runConcurrencyLimit}
                          onChange={(v) =>
                            setQuota({
                              ...quota,
                              runConcurrencyLimit: typeof v === 'number' ? v : 0,
                            })
                          }
                        />
                      </div>
                    </div>
                    <div>
                      <Typography.Text type="secondary">预算上限（可空）</Typography.Text>
                      <div>
                        <Input
                          style={{ width: 160 }}
                          placeholder="如 100.00"
                          value={quota.budgetLimit ?? ''}
                          onChange={(e) =>
                            setQuota({ ...quota, budgetLimit: e.target.value || null })
                          }
                        />
                      </div>
                    </div>
                    <Button type="primary" loading={quotaSaving} onClick={() => void saveQuota()}>
                      保存配额
                    </Button>
                  </Space>
                  <Typography.Paragraph type="secondary">
                    配额为平台侧硬上限（platform.tenant.quota）；执行侧按租户生效值拦截。
                  </Typography.Paragraph>
                </Space>
              </Card>
            ) : (
              <Alert type="info" showIcon message="配额信息不可用" />
            ),
          },
          {
            key: 'capabilities',
            label: `能力授权 (${caps.length})`,
            children: (
              <Card title="租户能力授权（平台资源可用范围）">
                <Space direction="vertical" style={{ width: '100%' }} size={12}>
                  <Space wrap align="end">
                    <div>
                      <Typography.Text type="secondary">资源编码</Typography.Text>
                      <div>
                        <Input
                          style={{ width: 240 }}
                          placeholder="tool:web-search / mcp:db-reader"
                          value={newResource}
                          onChange={(e) => setNewResource(e.target.value)}
                        />
                      </div>
                    </div>
                    <div>
                      <Typography.Text type="secondary">作用域</Typography.Text>
                      <div>
                        <Input
                          style={{ width: 120 }}
                          value={newScope}
                          onChange={(e) => setNewScope(e.target.value)}
                        />
                      </div>
                    </div>
                    <Button
                      type="primary"
                      loading={granting}
                      disabled={!newResource.trim()}
                      onClick={() => void grant()}
                    >
                      授予
                    </Button>
                  </Space>
                  <Table<PlatformCapability>
                    rowKey="id"
                    size="small"
                    dataSource={caps}
                    pagination={false}
                    locale={{ emptyText: '未授予任何平台能力' }}
                    columns={[
                      {
                        title: '资源编码',
                        dataIndex: 'resourceCode',
                        render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
                      },
                      { title: '作用域', dataIndex: 'scope', width: 100 },
                      {
                        title: '状态',
                        dataIndex: 'status',
                        width: 100,
                        render: (s: string) => (
                          <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s}</Tag>
                        ),
                      },
                      { title: '版本', dataIndex: 'version', width: 80 },
                      {
                        title: '操作',
                        key: 'actions',
                        width: 100,
                        render: (_, c) => (
                          <Popconfirm
                            title={`撤销能力 ${c.resourceCode}？`}
                            onConfirm={() => void revoke(c.resourceCode)}
                          >
                            <Button size="small" danger>
                              撤销
                            </Button>
                          </Popconfirm>
                        ),
                      },
                    ]}
                  />
                  <Typography.Paragraph type="secondary">
                    ⚠️ 授予能力 = 平台资源进入租户<b>可用范围</b>；不替代租户内的调用授权
                    （租户侧仍需自行配置 Agent 引用与权限）。
                  </Typography.Paragraph>
                </Space>
              </Card>
            ),
          },
        ]}
      />
    </div>
  )
}
