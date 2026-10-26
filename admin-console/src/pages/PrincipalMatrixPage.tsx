import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Card, Space, Spin, Table, Tag, Typography } from 'antd'
import { getPlatformUsers, getPlatformRoles } from '@/api/adminApi'
import type { PlatformUserSummary, PlatformRoleSummary } from '@/api/types'

const { Text } = Typography

/** 平台角色中文名（与后端 PlatformRoleController 描述一致） */
const ROLE_LABELS: Record<string, string> = {
  PLATFORM_SUPER_ADMIN: '平台超级管理员',
  TENANT_LIFECYCLE_ADMIN: '租户生命周期管理员',
  PLATFORM_RESOURCE_ADMIN: '平台资源管理员',
  PLATFORM_SECURITY_ADMIN: '平台安全管理员',
  PLATFORM_AUDITOR: '平台审计员',
  PLATFORM_SUPPORT: '平台支持人员',
}

const ROLE_COLORS: Record<string, string> = {
  PLATFORM_SUPER_ADMIN: 'red',
  TENANT_LIFECYCLE_ADMIN: 'orange',
  PLATFORM_RESOURCE_ADMIN: 'blue',
  PLATFORM_SECURITY_ADMIN: 'volcano',
  PLATFORM_AUDITOR: 'purple',
  PLATFORM_SUPPORT: 'cyan',
}

/**
 * 平台用户与角色页（重规划 §8 · /roles）。
 *
 * <p>两块只读视图：
 * <ol>
 *   <li>{@code GET /api/platform/v1/users} —— 平台用户（绝不返回 password_hash）；</li>
 *   <li>{@code GET /api/platform/v1/roles} —— 6 个最小特权角色及其权限（冻结矩阵）。</li>
 * </ol>
 * ⛔ 角色授予/撤销属平台治理动作，走带外运维通道（写 platform.platform_user_role），
 *    应用层不提供自授端点（防自举回路）。
 */
export function PrincipalMatrixPage(): JSX.Element {
  const [users, setUsers] = useState<PlatformUserSummary[]>([])
  const [roles, setRoles] = useState<PlatformRoleSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    Promise.all([getPlatformUsers(), getPlatformRoles()])
      .then(([u, r]) => {
        setUsers(u)
        setRoles(r)
      })
      .catch((e: unknown) => {
        setUsers([])
        setRoles([])
        setError(e instanceof Error ? e.message : '平台用户/角色不可用')
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  /** 被撤权过的用户数（authzVersion > 1） */
  const revokedCount = useMemo(
    () => users.filter((p) => p.authzVersion > 1).length,
    [users],
  )

  return (
    <Card
      title="平台用户与角色"
      extra={<Text type="secondary">只读 · 角色赋权走带外运维通道</Text>}
    >
      {error && (
        <Alert
          type="error"
          showIcon
          message="平台用户/角色不可用"
          description={<Text>{error}</Text>}
          style={{ marginBottom: 16 }}
        />
      )}

      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {revokedCount > 0 && (
          <Alert
            type="warning"
            showIcon
            message={`${revokedCount} 个平台用户被撤权过`}
            description={
              <Text>
                authzVersion 大于 1 即发生过授权撤销；持有旧版本的在途上下文会被拒绝（fail-closed）。
              </Text>
            }
          />
        )}

        <Spin spinning={loading}>
          <Table<PlatformUserSummary>
            rowKey="id"
            size="small"
            dataSource={users}
            pagination={false}
            locale={{ emptyText: '没有平台用户' }}
            columns={[
              { title: '用户名', dataIndex: 'username', width: 160 },
              {
                title: '显示名',
                dataIndex: 'displayName',
                width: 160,
                render: (v: string | null) => v ?? '—',
              },
              {
                title: '状态',
                dataIndex: 'status',
                width: 100,
                render: (v: string) =>
                  v === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>{v}</Tag>,
              },
              { title: '认证源', dataIndex: 'authSource', width: 110 },
              {
                title: 'MFA',
                dataIndex: 'mfaRequired',
                width: 80,
                render: (v: boolean) => (v ? <Tag color="gold">强制</Tag> : <Tag>否</Tag>),
              },
              {
                title: '授权版本',
                dataIndex: 'authzVersion',
                width: 100,
                render: (v: number) => (v > 1 ? <Text type="warning">{v}</Text> : v),
              },
              {
                title: '最近登录',
                dataIndex: 'lastLoginAt',
                width: 170,
                render: (v: string | null) => v ?? '—',
              },
              {
                title: '用户 ID',
                dataIndex: 'id',
                width: 220,
                render: (v: string) => <Text copyable={{ text: v }}>{v}</Text>,
              },
            ]}
          />
        </Spin>

        <Card
          title="平台角色权限矩阵（冻结）"
          size="small"
          styles={{ body: { paddingTop: 8 } }}
        >
          <Table<PlatformRoleSummary>
            rowKey="name"
            size="small"
            dataSource={roles}
            pagination={false}
            locale={{ emptyText: '角色目录不可用' }}
            columns={[
              {
                title: '角色',
                dataIndex: 'name',
                width: 240,
                render: (v: string) => (
                  <Tag color={ROLE_COLORS[v] ?? 'default'}>{ROLE_LABELS[v] ?? v}</Tag>
                ),
              },
              { title: '职责', dataIndex: 'description' },
              {
                title: '权限数',
                key: 'permCount',
                width: 90,
                render: (_, r) => r.permissions.length,
              },
              {
                title: '权限清单',
                dataIndex: 'permissions',
                render: (ps: string[]) => (
                  <Space size={4} wrap>
                    {ps.map((p) => (
                      <Tag key={p} style={{ fontSize: 11 }}>
                        {p}
                      </Tag>
                    ))}
                  </Space>
                ),
              },
            ]}
          />
        </Card>
      </Space>
    </Card>
  )
}
