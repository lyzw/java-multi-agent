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
  Row,
  Col,
  Statistic,
  Descriptions,
  App as AntApp,
} from 'antd'
import { SafetyCertificateOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  getTenantDetail,
  verifyTenantRls,
} from '@/api/adminApi'
import type {
  PrincipalResponse,
  RlsVerifyResponse,
} from '@/api/types'

const STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'green',
  SUSPENDED: 'gold',
  DECOMMISSIONED: 'red',
}

export function TenantDetailPage(): JSX.Element {
  const { id = '' } = useParams()
  const { message } = AntApp.useApp()
  const [detail, setDetail] = useState<{
    tenant: { tenantId: string; slug: string; name: string; status: string; createdAt: string | null }
    principalsCount: number
    agentsCount: number
    overridesCount: number
    principals: PrincipalResponse[]
  } | null>(null)
  const [loading, setLoading] = useState(true)
  const [forbidden, setForbidden] = useState(false)

  const [principals, setPrincipals] = useState<PrincipalResponse[]>([])
  const [verify, setVerify] = useState<RlsVerifyResponse | null>(null)
  const [verifying, setVerifying] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setForbidden(false)
    try {
      const d = await getTenantDetail(id)
      setDetail(d)
      setPrincipals(d.principals)
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) {
        setForbidden(true)
      } else {
        message.error(e instanceof Error ? e.message : '加载租户详情失败')
      }
    } finally {
      setLoading(false)
    }
  }, [id, message])

  useEffect(() => {
    if (id) void load()
  }, [id, load])

  const runVerify = async (): Promise<void> => {
    setVerifying(true)
    try {
      const r = await verifyTenantRls(id)
      setVerify(r)
      if (r.ok) message.success('RLS 隔离校验通过')
      else message.error('RLS 隔离校验未通过，可能存在越权风险')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '校验失败')
    } finally {
      setVerifying(false)
    }
  }

  if (loading) return <Spin spinning style={{ width: '100%', marginTop: 80 }} />

  if (forbidden) {
    return (
      <Alert
        type="warning"
        showIcon
        message="无权限访问租户详情"
        description="租户详情为平台级操作，须经 PLATFORM_ADMIN 作用域。当前账号不持有该角色。"
      />
    )
  }

  if (!detail) {
    return <Alert type="error" showIcon message="租户不存在" />
  }

  return (
    <div>
      <Typography.Title level={3}>租户详情 · {detail.tenant.slug}</Typography.Title>
      <Descriptions
        bordered
        size="small"
        style={{ marginBottom: 16 }}
        column={2}
        items={[
          { key: 'slug', label: 'Slug', children: <Typography.Text code>{detail.tenant.slug}</Typography.Text> },
          { key: 'name', label: '名称', children: detail.tenant.name },
          {
            key: 'status',
            label: '状态',
            children: <Tag color={STATUS_COLOR[detail.tenant.status] ?? 'default'}>{detail.tenant.status}</Tag>,
          },
          { key: 'created', label: '创建时间', children: detail.tenant.createdAt ?? '-' },
        ]}
      />

      <Tabs
        items={[
          {
            key: 'overview',
            label: '概览',
            children: (
              <Row gutter={16}>
                <Col span={8}>
                  <Card><Statistic title="主体数" value={detail.principalsCount} /></Card>
                </Col>
                <Col span={8}>
                  <Card><Statistic title="Agent 定义" value={detail.agentsCount} /></Card>
                </Col>
                <Col span={8}>
                  <Card><Statistic title="配置覆盖" value={detail.overridesCount} /></Card>
                </Col>
              </Row>
            ),
          },
          {
            key: 'principals',
            label: `主体 (${detail.principalsCount})`,
            children: (
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
            ),
          },
          {
            key: 'rls',
            label: (
              <Space>
                <SafetyCertificateOutlined /> RLS 校验
              </Space>
            ),
            children: (
              <Card>
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Button type="primary" loading={verifying} onClick={() => void runVerify()}>
                    执行 RLS 隔离校验
                  </Button>
                  {verify && (
                    <Alert
                      type={verify.ok ? 'success' : 'error'}
                      showIcon
                      message={verify.ok ? 'RLS 隔离有效' : 'RLS 隔离存在风险'}
                      description={
                        <Space direction="vertical">
                          <span>会话上下文已绑定目标租户：{verify.contextBound ? '是' : '否'}</span>
                          <span>无法越界读取其他租户数据：{verify.isolated ? '是' : '否'}</span>
                          <span>越界泄漏行数：{verify.leakedRows}</span>
                          <span>绑定租户：{verify.boundTenantId ?? '-'}</span>
                        </Space>
                      }
                    />
                  )}
                  {!verify && (
                    <Typography.Paragraph type="secondary">
                      以目标租户身份绑定连接后断言：会话上下文已绑定为目标租户，且无法读取其他租户的主体数据。
                      若隔离被破坏（泄漏行数 &gt; 0），说明当前连接带 BYPASSRLS 或策略失效。
                    </Typography.Paragraph>
                  )}
                </Space>
              </Card>
            ),
          },
        ]}
      />
    </div>
  )
}
