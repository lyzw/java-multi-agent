import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Card, Space, Spin, Table, Tag, Typography } from 'antd'
import { getMyPrincipals } from '@/api/adminApi'
import type { PrincipalResponse } from '@/api/types'

const { Text } = Typography

/** 角色中文名（与 Roles 登记值一致；未知角色回落原值） */
const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  PLATFORM_ADMIN: '平台管理员',
  TENANT_ADMIN: '租户管理员',
  AGENT_AUTHOR: 'Agent 作者',
  APPROVER: '审批人',
  VIEWER: '观察者',
  WORKER: 'Worker 服务账号',
  TEACHER: '教师',
  PARENT: '家长',
  STUDENT: '学生',
}

const ROLE_COLORS: Record<string, string> = {
  SUPER_ADMIN: 'red',
  PLATFORM_ADMIN: 'volcano',
  TENANT_ADMIN: 'orange',
  AGENT_AUTHOR: 'blue',
  APPROVER: 'cyan',
  VIEWER: 'default',
  WORKER: 'purple',
}

/**
 * 角色矩阵页（P5 · /roles）。
 *
 * <p>复用既有 {@code GET /api/admin/tenants/self/principals}（租户内自服务视图）：
 * 谁有什么角色、授权版本水位如何。<b>只读</b>——本页不做任何授权变更；
 * 角色授予/撤销属平台治理动作，走带外运维通道（P0.5 红线延续）。
 *
 * <p>⚠️ <b>授权版本语义</b>：authzVersion &gt; 1 表示该主体被撤权过
 * （撤销时版本递增，持有旧版本的在途上下文会被拒绝）。
 * 平台/运维级角色（PLATFORM_ADMIN）默认不授予任何主体，仅带外授予后生效。
 */
export function PrincipalMatrixPage(): JSX.Element {
  const [rows, setRows] = useState<PrincipalResponse[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    getMyPrincipals()
      .then(setRows)
      .catch((e: unknown) => {
        setRows([])
        setError(e instanceof Error ? e.message : '主体列表不可用')
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  /** 角色分布统计（角色 → 主体数） */
  const roleStats = useMemo(() => {
    const m = new Map<string, number>()
    for (const p of rows) {
      for (const r of p.roles) m.set(r, (m.get(r) ?? 0) + 1)
    }
    return [...m.entries()].sort((a, b) => b[1] - a[1])
  }, [rows])

  const revokedCount = useMemo(
    () => rows.filter((p) => p.authzVersion > 1).length,
    [rows],
  )

  return (
    <Card title="角色矩阵" extra={<Text type="secondary">只读 · 授权变更走带外运维通道</Text>}>
      {error && (
        <Alert
          type="error"
          showIcon
          message="主体列表不可用"
          description={<Text>{error}</Text>}
          style={{ marginBottom: 16 }}
        />
      )}

      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Space wrap>
          {roleStats.map(([role, count]) => (
            <Tag key={role} color={ROLE_COLORS[role] ?? 'default'}>
              {ROLE_LABELS[role] ?? role} · {count}
            </Tag>
          ))}
          {roleStats.length === 0 && !loading && (
            <Text type="secondary">（无主体）</Text>
          )}
        </Space>

        {revokedCount > 0 && (
          <Alert
            type="warning"
            showIcon
            message={`租户内 ${revokedCount} 个主体被撤权过`}
            description={
              <Text>
                authzVersion 大于 1 即发生过授权撤销；撤销时版本递增，
                持有旧版本的在途上下文会被拒绝（fail-closed）。
              </Text>
            }
          />
        )}

        <Spin spinning={loading}>
          <Table<PrincipalResponse>
            rowKey="principalId"
            size="small"
            dataSource={rows}
            pagination={false}
            locale={{ emptyText: '租户内没有主体' }}
            columns={[
              { title: '登录名', dataIndex: 'loginName', width: 180 },
              {
                title: '显示名',
                dataIndex: 'displayName',
                width: 160,
                render: (v: string | null) => v ?? '—',
              },
              {
                title: '角色',
                dataIndex: 'roles',
                render: (roles: string[]) => (
                  <Space size={4} wrap>
                    {roles.map((r) => (
                      <Tag key={r} color={ROLE_COLORS[r] ?? 'default'}>
                        {ROLE_LABELS[r] ?? r}
                      </Tag>
                    ))}
                    {roles.length === 0 && <Text type="secondary">（无角色）</Text>}
                  </Space>
                ),
              },
              {
                title: '状态',
                dataIndex: 'status',
                width: 100,
                render: (v: string) =>
                  v === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>{v}</Tag>,
              },
              {
                title: '授权版本',
                dataIndex: 'authzVersion',
                width: 100,
                render: (v: number) => (v > 1 ? <Text type="warning">{v}</Text> : v),
              },
              {
                title: '主体标识',
                dataIndex: 'principalId',
                width: 220,
                render: (v: string) => <Text copyable={{ text: v }}>{v}</Text>,
              },
            ]}
          />
        </Spin>
      </Space>
    </Card>
  )
}
