import { useEffect, useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Row,
  Col,
  Card,
  Table,
  Tag,
  Button,
  Space,
  Typography,
  Alert,
  Spin,
  Statistic,
  App as AntApp,
  Modal,
  Select,
  Descriptions,
} from 'antd'
import {
  PlusOutlined,
  ApartmentOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons'
import { useSession } from '@/auth/session'
import { hasPlatformScope } from '@/config/constants'
import { ApiError } from '@/api/client'
import {
  getTenantList,
  getMyTenant,
  getMyPrincipals,
  getConfigOverrides,
  updateTenantStatus,
} from '@/api/adminApi'
import type {
  TenantResponse,
  PrincipalResponse,
  ConfigOverrideItem,
  TenantSummary,
} from '@/api/types'

const STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'green',
  SUSPENDED: 'gold',
  DECOMMISSIONED: 'red',
}

export function TenantRegistryPage(): JSX.Element {
  const { roles } = useSession()
  const canManage = hasPlatformScope(roles)
  return canManage ? <PlatformRegistry /> : <MyTenantConsole />
}

/* ── 平台级：租户注册表 ── */

function PlatformRegistry(): JSX.Element {
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [data, setData] = useState<TenantResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [locked, setLocked] = useState(false)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined)
  const [target, setTarget] = useState<TenantResponse | null>(null)
  const [nextStatus, setNextStatus] = useState<string>('SUSPENDED')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const page = await getTenantList({ status: statusFilter, limit: 50 })
      setData(page.items)
      setNextCursor(page.nextCursor)
      setLocked(false)
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) {
        setLocked(true)
        message.warning('平台级租户管理通道未接入（需 PLATFORM_ADMIN）')
      } else {
        message.error(e instanceof Error ? e.message : '加载租户列表失败')
      }
    } finally {
      setLoading(false)
    }
  }, [statusFilter, message])

  useEffect(() => {
    void load()
  }, [load])

  const openLifecycle = (t: TenantResponse): void => {
    setTarget(t)
    setNextStatus(t.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE')
  }

  const confirmLifecycle = async (): Promise<void> => {
    if (!target) return
    try {
      await updateTenantStatus(target.tenantId, nextStatus as 'ACTIVE' | 'SUSPENDED' | 'DECOMMISSIONED')
      message.success(`租户 ${target.slug} 已置为 ${nextStatus}`)
      setTarget(null)
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  if (locked) {
    return (
      <div>
        <Typography.Title level={3}>租户管理 · 注册表</Typography.Title>
        <Alert
          type="warning"
          showIcon
          message="平台级租户管理通道未接入"
          description="开通 / 启停 / 跨租户治理等平台级操作须经 PLATFORM_ADMIN 作用域（由平台运维通道授权）。当前账号不持有该角色，列表已锁定。请联系平台运维。"
        />
      </div>
    )
  }

  return (
    <div>
      <Typography.Title level={3}>租户管理 · 注册表</Typography.Title>
      <Card
        styles={{ body: { paddingBottom: 12 } }}
        extra={
          <Space>
            <Select
              allowClear
              placeholder="按状态筛选"
              style={{ width: 160 }}
              value={statusFilter}
              onChange={(v) => setStatusFilter(v)}
              options={[
                { value: 'ACTIVE', label: 'ACTIVE' },
                { value: 'SUSPENDED', label: 'SUSPENDED' },
                { value: 'DECOMMISSIONED', label: 'DECOMMISSIONED' },
              ]}
            />
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => navigate('/tenants/new')}
            >
              开通租户
            </Button>
          </Space>
        }
      >
        <Table<TenantResponse>
          rowKey="tenantId"
          loading={loading}
          dataSource={data}
          pagination={false}
          locale={{ emptyText: '暂无租户' }}
          columns={[
            { title: 'Slug', dataIndex: 'slug', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
            { title: '名称', dataIndex: 'name' },
            {
              title: '状态',
              dataIndex: 'status',
              render: (s: string) => <Tag color={STATUS_COLOR[s] ?? 'default'}>{s}</Tag>,
            },
            { title: '创建时间', dataIndex: 'createdAt', render: (v: string | null) => v ?? '-' },
            {
              title: '操作',
              key: 'actions',
              render: (_, t) => (
                <Space>
                  <Button size="small" onClick={() => navigate(`/tenants/${t.tenantId}`)}>
                    详情
                  </Button>
                  <Button size="small" onClick={() => openLifecycle(t)}>
                    启停
                  </Button>
                </Space>
              ),
            },
          ]}
        />
        {nextCursor && (
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
            还有更多（游标 {nextCursor}），可调整筛选或联系平台扩展分页。
          </Typography.Paragraph>
        )}
      </Card>

      <Modal
        open={target !== null}
        title={`生命周期变更 · ${target?.slug ?? ''}`}
        onCancel={() => setTarget(null)}
        onOk={() => void confirmLifecycle()}
        okText="确认"
        cancelText="取消"
      >
        <p>将租户状态置为：</p>
        <Select
          style={{ width: '100%' }}
          value={nextStatus}
          onChange={(v) => setNextStatus(v)}
          options={[
            { value: 'ACTIVE', label: 'ACTIVE（启用）' },
            { value: 'SUSPENDED', label: 'SUSPENDED（暂停）' },
            { value: 'DECOMMISSIONED', label: 'DECOMMISSIONED（停用注销）' },
          ]}
        />
      </Modal>
    </div>
  )
}

/* ── 租户内：我的租户控制台 ── */

function MyTenantConsole(): JSX.Element {
  const { message } = AntApp.useApp()
  const [summary, setSummary] = useState<TenantSummary | null>(null)
  const [principals, setPrincipals] = useState<PrincipalResponse[]>([])
  const [overrides, setOverrides] = useState<ConfigOverrideItem[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const [s, p, o] = await Promise.all([
          getMyTenant(),
          getMyPrincipals().catch(() => []),
          getConfigOverrides().catch(() => []),
        ])
        if (!active) return
        setSummary(s)
        setPrincipals(p)
        setOverrides(o)
      } catch (e) {
        if (active) message.error(e instanceof Error ? e.message : '加载我的租户失败')
      } finally {
        if (active) setLoading(false)
      }
    })()
    return () => {
      active = false
    }
  }, [message])

  if (loading) return <Spin spinning style={{ width: '100%', marginTop: 80 }} />

  return (
    <div>
      <Typography.Title level={3}>
        <Space>
          <ApartmentOutlined /> 我的租户
        </Space>
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        icon={<SafetyCertificateOutlined />}
        style={{ marginBottom: 16 }}
        message="当前视图为「我的租户」（租户内自服务）"
        description="平台级租户管理（开通 / 启停 / 跨租户治理 / RLS 校验）需 PLATFORM_ADMIN 作用域，由平台运维通道授权，尚未对当前账号开放。下方为当前租户的配置与主体快照。"
      />

      {summary && (
        <>
          <Descriptions
            bordered
            size="small"
            style={{ marginBottom: 16 }}
            column={2}
            items={[
              { key: 'slug', label: 'Slug', children: <Typography.Text code>{summary.tenant.slug}</Typography.Text> },
              { key: 'name', label: '名称', children: summary.tenant.name },
              {
                key: 'status',
                label: '状态',
                children: (
                  <Tag color={STATUS_COLOR[summary.tenant.status] ?? 'default'}>
                    {summary.tenant.status}
                  </Tag>
                ),
              },
              { key: 'created', label: '创建时间', children: summary.tenant.createdAt ?? '-' },
            ]}
          />
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={8}>
              <Card><Statistic title="主体数" value={summary.principalsCount} /></Card>
            </Col>
            <Col span={8}>
              <Card><Statistic title="Agent 定义" value={summary.agentsCount} /></Card>
            </Col>
            <Col span={8}>
              <Card><Statistic title="配置覆盖" value={summary.overridesCount} /></Card>
            </Col>
          </Row>
        </>
      )}

      <Card title="主体与角色" style={{ marginBottom: 16 }}>
        <Table<PrincipalResponse>
          rowKey="principalId"
          size="small"
          dataSource={principals}
          pagination={false}
          locale={{ emptyText: '暂无主体' }}
          columns={[
            { title: '登录名', dataIndex: 'loginName' },
            { title: '显示名', dataIndex: 'displayName', render: (v: string | null) => v ?? '-' },
            {
              title: '状态',
              dataIndex: 'status',
              render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s}</Tag>,
            },
            {
              title: '角色',
              dataIndex: 'roles',
              render: (rs: string[]) => (
                <Space wrap>
                  {rs.map((r) => (
                    <Tag key={r}>{r}</Tag>
                  ))}
                  {rs.length === 0 && <Typography.Text type="secondary">（无）</Typography.Text>}
                </Space>
              ),
            },
            { title: '授权版本', dataIndex: 'authzVersion' },
          ]}
        />
      </Card>

      <Card title="配置覆盖（tenant_config_override）">
        <Table<ConfigOverrideItem>
          rowKey="key"
          size="small"
          dataSource={overrides}
          pagination={false}
          locale={{ emptyText: '无覆盖（全部使用平台默认值）' }}
          columns={[
            { title: '键', dataIndex: 'key', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
            {
              title: '生效值',
              dataIndex: 'effectiveValue',
              render: (v: string | null) => v ?? '-',
            },
            {
              title: '来源',
              dataIndex: 'source',
              render: (v: string | null) => {
                if (v === 'db_override') return <Tag color="blue">覆盖</Tag>
                if (v === 'yml') return <Tag>默认</Tag>
                return <Typography.Text type="secondary">-</Typography.Text>
              },
            },
            { title: '方向', dataIndex: 'tightening' },
            {
              title: '理由',
              dataIndex: 'reason',
              render: (v: string | null) => v ?? '-',
            },
          ]}
        />
      </Card>
    </div>
  )
}
