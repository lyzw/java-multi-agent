import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Card, Spin, Table, Tabs, Tag, Tooltip, Typography } from 'antd'
import { getPlatformRoles } from '@/api/adminApi'
import type { PlatformRoleSummary } from '@/api/types'
import { PLATFORM_SCOPE_ROLES } from '@/config/constants'
import { ROLE_LABELS, ROLE_COLORS } from '@/pages/roleShared'

const { Text, Paragraph } = Typography

/** 固定 Tab 顺序；无内容的组不显示 */
const NAMESPACE_ORDER = [
  'user',
  'role',
  'tenant',
  'capability',
  'resource',
  'policy',
  'audit',
  'support',
] as const

/** 权限字符串第二段作为命名空间（platform.user.manage → user）；不在已知命名空间内的归 support */
function namespaceOf(permission: string): string {
  const seg = permission.split('.')[1]
  return seg !== undefined && (NAMESPACE_ORDER as readonly string[]).includes(seg)
    ? seg
    : 'support'
}

/** 行标签：取末段做简短标签 + Tooltip 展示完整权限名 */
function lastSegment(permission: string): string {
  const parts = permission.split('.')
  const last = parts[parts.length - 1] ?? permission
  return `${last}（${permission}）`
}

const NAMESPACE_LABELS: Record<string, string> = {
  user: '用户',
  role: '角色',
  tenant: '租户',
  capability: '能力',
  resource: '资源',
  policy: '策略',
  audit: '审计',
  support: '支持',
}

/**
 * 平台角色权限矩阵页（/roles · 只读冻结视图）。
 *
 * <p>数据来自 {@code GET /api/platform/v1/roles}（后端 PlatformRolePermissionMatrix）。
 * ⛔ 该矩阵由代码冻结，运行期不可修改；角色赋权在『平台用户』页操作。
 */
export function RoleMatrixPage(): JSX.Element {
  const [roles, setRoles] = useState<PlatformRoleSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    getPlatformRoles()
      .then(setRoles)
      .catch((e: unknown) => {
        setRoles([])
        setError(e instanceof Error ? e.message : '平台角色不可用')
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  /** 固定列序：6 个平台角色 */
  const columns = PLATFORM_SCOPE_ROLES.map((r) => ({
    title: () => <Tag color={ROLE_COLORS[r] ?? 'default'}>{ROLE_LABELS[r] ?? r}</Tag>,
    key: r as string,
    width: 140,
    render: (_: unknown, perm: string) => {
      const role = roles.find((x) => x.name === r)
      const has = role !== undefined && role.permissions.includes(perm)
      return has ? (
        <Text style={{ color: '#52c41a' }}>✓</Text>
      ) : (
        <Text type="secondary">—</Text>
      )
    },
  }))

  /** 按命名空间分组 permission → 行 */
  const groups = useMemo(() => {
    const byNs = new Map<string, Set<string>>()
    for (const role of roles) {
      for (const perm of role.permissions) {
        const ns = namespaceOf(perm)
        const set = byNs.get(ns) ?? new Set<string>()
        set.add(perm)
        byNs.set(ns, set)
      }
    }
    return NAMESPACE_ORDER.filter((ns) => byNs.has(ns)).map((ns) => ({
      key: ns,
      label: `${NAMESPACE_LABELS[ns] ?? ns}权限`,
      permissions: Array.from(byNs.get(ns) ?? []).sort(),
    }))
  }, [roles])

  return (
    <Card title="平台角色权限矩阵" extra={<Text type="secondary">只读 · 冻结</Text>}>
      <Alert
        type="info"
        showIcon
        message="该矩阵由代码冻结（PlatformRolePermissionMatrix），运行期不可修改；角色赋权在『平台用户』页操作"
        style={{ marginBottom: 16 }}
      />

      {error && (
        <Alert
          type="error"
          showIcon
          message="平台角色不可用"
          description={<Text>{error}</Text>}
          style={{ marginBottom: 16 }}
        />
      )}

      <Spin spinning={loading}>
        <Tabs
          items={groups.map((g) => ({
            key: g.key,
            label: g.label,
            children: (
              <Table<string>
                rowKey={(p) => p}
                size="small"
                dataSource={g.permissions}
                pagination={false}
                locale={{ emptyText: '无权限' }}
                columns={[
                  {
                    title: '权限',
                    key: 'perm',
                    render: (_: unknown, perm: string) => (
                      <Tooltip title={perm}>
                        <Paragraph style={{ margin: 0 }} copyable={{ text: perm }}>
                          {lastSegment(perm)}
                        </Paragraph>
                      </Tooltip>
                    ),
                  },
                  ...columns,
                ] as never}
              />
            ),
          }))}
        />
      </Spin>
    </Card>
  )
}
