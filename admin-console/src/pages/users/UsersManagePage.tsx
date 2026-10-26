import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { ColumnType } from 'antd/es/table'
import {
  assignPlatformUserRole,
  createPlatformUser,
  getPlatformRoles,
  getPlatformUsers,
  resetPlatformUserPassword,
  revokePlatformUserRole,
  updatePlatformUser,
} from '@/api/adminApi'
import type { PlatformRoleSummary, PlatformUserDetail } from '@/api/types'
import { useSession } from '@/auth/session'
import { ROLES } from '@/config/constants'
import { ROLE_LABELS, ROLE_COLORS, isSuperAdmin } from '@/pages/roleShared'

const { Text } = Typography

/** 密码最小长度（与后端校验一致：≥10 位） */
const PASSWORD_MIN_LEN = 10

/** 自身保护：不能对自己执行停用/重置密码/角色增删（服务层同样拒绝） */
const SELF_GUARD_TIP = '不能对自己执行该操作（自身保护，服务层同样拒绝）'
const SELF_ROLES_TIP = '不能对自己增删角色（自身保护，服务层同样拒绝）'

/** 最后一个超管预判禁用说明 */
const LAST_SUPER_TIP = '最后一名平台超级管理员可通过带外通道移交'

/** 超管 SoD：授予/撤销 PLATFORM_SUPER_ADMIN 仅超管本人可操作 */
const SUPER_SOD_TIP = '授予/撤销平台超级管理员仅超管本人可操作（SoD，否则后端 403）'

interface CreateFormValues {
  username: string
  password: string
  displayName?: string
  email?: string
  mfaRequired?: boolean
  roles?: string[]
}

interface EditFormValues {
  displayName?: string
  email?: string
  mfaRequired?: boolean
}

/** 密码校验规则（≥10 位） */
const passwordRules = [
  { required: true, message: '请输入密码' },
  {
    validator: (_: unknown, value: string | undefined) =>
      value !== undefined && value.length >= PASSWORD_MIN_LEN
        ? Promise.resolve()
        : Promise.reject(new Error(`密码长度至少 ${PASSWORD_MIN_LEN} 位`)),
  },
]

/**
 * 平台用户管理页（/users）。
 *
 * <p>后端语义如实呈现：密码 ≥10 位；自身保护（不能对自己停用/重置密码/增删角色）、
 * 超管 SoD（授予/撤销 PLATFORM_SUPER_ADMIN 仅超管本人可操作）、
 * 「最后一名平台超级管理员可通过带外通道移交」——均预检禁用并注明；
 * 角色/状态变更后 authzVersion bump，令牌无状态、最多 900s 内生效；
 * 所有失败走 message.error 呈现后端错误信息。
 */
export function UsersManagePage(): JSX.Element {
  const { session, roles: currentRoles } = useSession()
  const { message } = App.useApp()
  const currentUserId = session?.principalId ?? null
  const iAmSuper = isSuperAdmin(currentRoles)

  const [users, setUsers] = useState<PlatformUserDetail[]>([])
  const [roleCatalog, setRoleCatalog] = useState<PlatformRoleSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [editOpen, setEditOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<PlatformUserDetail | null>(null)
  const [pwdOpen, setPwdOpen] = useState(false)
  const [pwdTarget, setPwdTarget] = useState<PlatformUserDetail | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [roleDrawerOpen, setRoleDrawerOpen] = useState(false)
  const [roleTarget, setRoleTarget] = useState<PlatformUserDetail | null>(null)
  const [assignRole, setAssignRole] = useState<string | undefined>(undefined)

  const [editForm] = Form.useForm<EditFormValues>()
  const [pwdForm] = Form.useForm<{ newPassword: string }>()
  const [createForm] = Form.useForm<CreateFormValues>()

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    Promise.all([getPlatformUsers(), getPlatformRoles()])
      .then(([u, r]) => {
        setUsers(u)
        setRoleCatalog(r)
      })
      .catch((e: unknown) => {
        setUsers([])
        setRoleCatalog([])
        setError(e instanceof Error ? e.message : '平台用户/角色不可用')
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  /** 全平台超管数量（用于「最后一个超管」预判禁用） */
  const superAdminCount = useMemo(
    () => users.filter((u) => u.roles.includes(ROLES.SUPER_ADMIN)).length,
    [users],
  )

  /** 某一角色对目标用户的可管理性（revoke/assign 统一预检；不可管理时给出说明） */
  function roleGuard(
    target: PlatformUserDetail,
    role: string,
    mode: 'revoke' | 'assign',
  ): { disabled: boolean; tip: string | null } {
    const tips: string[] = []
    if (target.id === currentUserId) {
      tips.push(mode === 'revoke' ? SELF_ROLES_TIP : SELF_GUARD_TIP)
    }
    if (role === ROLES.SUPER_ADMIN) {
      if (!iAmSuper) tips.push(SUPER_SOD_TIP)
      else if (mode === 'revoke' && superAdminCount <= 1) tips.push(LAST_SUPER_TIP)
    }
    return { disabled: tips.length > 0, tip: tips.length > 0 ? tips.join('；') : null }
  }

  /** 成功后刷新列表；失败统一展示后端 message */
  function runAction(action: () => Promise<unknown>, successMsg: string): Promise<void> {
    return action()
      .then(() => {
        message.success(successMsg)
        load()
      })
      .catch((e: unknown) => {
        message.error(e instanceof Error ? e.message : '操作失败')
      })
  }

  function openEdit(u: PlatformUserDetail): void {
    setEditTarget(u)
    editForm.setFieldsValue({
      displayName: u.displayName ?? undefined,
      email: u.email ?? undefined,
      mfaRequired: u.mfaRequired,
    })
    setEditOpen(true)
  }

  function openPwd(u: PlatformUserDetail): void {
    setPwdTarget(u)
    pwdForm.resetFields()
    setPwdOpen(true)
  }

  function openRoles(u: PlatformUserDetail): void {
    setRoleTarget(u)
    setAssignRole(undefined)
    setRoleDrawerOpen(true)
  }

  /** 禁用按钮包一层 tooltip（antd disabled 元素不触发 hover，需外层 span） */
  function guarded(isSelf: boolean, node: JSX.Element, tip: string): JSX.Element {
    if (!isSelf) return node
    return (
      <Tooltip title={tip}>
        <span style={{ cursor: 'not-allowed' }}>{node}</span>
      </Tooltip>
    )
  }

  /** 角色目录：非超管视角禁用超级管理员选项（SoD），由 Select 原生 disabled 呈现 */
  const roleCatalogOptions = roleCatalog.map((r) => ({
    value: r.name,
    label: ROLE_LABELS[r.name] ?? r.name,
    disabled: r.name === ROLES.SUPER_ADMIN && !iAmSuper,
  }))

  const columns: ColumnType<PlatformUserDetail>[] = [
    { title: '用户名', dataIndex: 'username', width: 140 },
    {
      title: '显示名',
      dataIndex: 'displayName',
      width: 140,
      render: (v: string | null) => v ?? '—',
    },
    {
      title: '邮箱',
      dataIndex: 'email',
      width: 200,
      render: (v: string | null) => v ?? '—',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (v: string) => (v === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>),
    },
    {
      title: '角色',
      dataIndex: 'roles',
      width: 200,
      render: (rs: string[]) =>
        rs.length === 0 ? (
          '—'
        ) : (
          <Space size={4} wrap>
            {rs.map((r) => (
              <Tag key={r} color={ROLE_COLORS[r] ?? 'default'}>
                {ROLE_LABELS[r] ?? r}
              </Tag>
            ))}
          </Space>
        ),
    },
    { title: '认证源', dataIndex: 'authSource', width: 90 },
    {
      title: 'MFA',
      dataIndex: 'mfaRequired',
      width: 70,
      render: (v: boolean) => (v ? <Tag color="gold">强制</Tag> : <Tag>否</Tag>),
    },
    {
      title: '授权版本',
      dataIndex: 'authzVersion',
      width: 100,
      render: (v: number) =>
        v > 1 ? (
          <Tooltip title=">1 表示发生过角色变更；令牌无状态，最多 900s 内生效">
            <Tag color="gold">{v}</Tag>
          </Tooltip>
        ) : (
          <Text type="secondary">{v}</Text>
        ),
    },
    {
      title: '最近登录',
      dataIndex: 'lastLoginAt',
      width: 160,
      render: (v: string | null) => v ?? '—',
    },
    {
      title: '操作',
      key: 'actions',
      width: 250,
      render: (_: unknown, u: PlatformUserDetail) => {
        const isSelf = u.id === currentUserId
        const willDisable = u.status === 'ACTIVE'
        const statusBtn = (
          <Popconfirm
            title={willDisable ? '确认停用该用户？' : '确认启用该用户？'}
            onConfirm={() =>
              void runAction(
                () => updatePlatformUser(u.id, { status: willDisable ? 'DISABLED' : 'ACTIVE' }),
                willDisable ? '已停用' : '已启用',
              )
            }
          >
            <Button type="link" size="small" style={{ padding: 0 }} disabled={isSelf}>
              {willDisable ? '停用' : '启用'}
            </Button>
          </Popconfirm>
        )
        return (
          <Space size={12} wrap>
            <Button type="link" size="small" style={{ padding: 0 }} onClick={() => openEdit(u)}>
              编辑
            </Button>
            {guarded(isSelf, statusBtn, SELF_GUARD_TIP)}
            {guarded(
              isSelf,
              <Button
                type="link"
                size="small"
                style={{ padding: 0 }}
                disabled={isSelf}
                onClick={() => openPwd(u)}
              >
                重置密码
              </Button>,
              SELF_GUARD_TIP,
            )}
            {guarded(
              isSelf,
              <Button
                type="link"
                size="small"
                style={{ padding: 0 }}
                disabled={isSelf}
                onClick={() => openRoles(u)}
              >
                角色
              </Button>,
              SELF_ROLES_TIP,
            )}
          </Space>
        )
      },
    },
  ]

  return (
    <Card
      title="平台用户"
      extra={
        <Space>
          <Text type="secondary">状态/角色变更最多 900s 内生效（令牌无状态）</Text>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              createForm.resetFields()
              setCreateOpen(true)
            }}
          >
            新建用户
          </Button>
        </Space>
      }
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

      <Spin spinning={loading}>
        <Table<PlatformUserDetail>
          rowKey="id"
          size="small"
          dataSource={users}
          pagination={false}
          locale={{ emptyText: '没有平台用户' }}
          columns={columns}
        />
      </Spin>

      {/* 编辑资料 Modal（displayName/email/mfaRequired；状态变更走操作列） */}
      <Modal
        title={editTarget ? `编辑用户 · ${editTarget.username}` : '编辑用户'}
        open={editOpen}
        onCancel={() => setEditOpen(false)}
        onOk={() =>
          editForm
            .validateFields()
            .then((v: EditFormValues) => {
              if (editTarget === null) return Promise.resolve()
              return runAction(
                () =>
                  updatePlatformUser(editTarget.id, {
                    displayName: v.displayName,
                    email: v.email,
                    mfaRequired: v.mfaRequired,
                  }),
                '已更新',
              ).then(() => setEditOpen(false))
            })
            .catch(() => undefined)
        }
        destroyOnClose
      >
        <Form form={editForm} layout="vertical">
          <Form.Item name="displayName" label="显示名">
            <Input />
          </Form.Item>
          <Form.Item
            name="email"
            label="邮箱"
            rules={[{ type: 'email', message: '邮箱格式不正确' }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="mfaRequired" valuePropName="checked">
            <Checkbox>强制 MFA</Checkbox>
          </Form.Item>
        </Form>
      </Modal>

      {/* 重置密码 Modal（≥10 位前端校验） */}
      <Modal
        title={pwdTarget ? `重置密码 · ${pwdTarget.username}` : '重置密码'}
        open={pwdOpen}
        onCancel={() => setPwdOpen(false)}
        onOk={() =>
          pwdForm
            .validateFields()
            .then((v: { newPassword: string }) => {
              if (pwdTarget === null) return Promise.resolve()
              return runAction(
                () => resetPlatformUserPassword(pwdTarget.id, v.newPassword),
                '密码已重置',
              ).then(() => setPwdOpen(false))
            })
            .catch(() => undefined)
        }
        destroyOnClose
      >
        <Alert
          type="info"
          showIcon
          message={`新密码至少 ${PASSWORD_MIN_LEN} 位（与后端校验一致）`}
          style={{ marginBottom: 12 }}
        />
        <Form form={pwdForm} layout="vertical">
          <Form.Item name="newPassword" label="新密码" rules={passwordRules}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 新建用户 Modal */}
      <Modal
        title="新建用户"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() =>
          createForm
            .validateFields()
            .then((v: CreateFormValues) =>
              runAction(
                () =>
                  createPlatformUser({
                    username: v.username,
                    password: v.password,
                    displayName: v.displayName,
                    email: v.email,
                    mfaRequired: v.mfaRequired,
                    roles: v.roles,
                  }),
                '已创建',
              ).then(() => setCreateOpen(false)),
            )
            .catch(() => undefined)
        }
        destroyOnClose
      >
        <Form form={createForm} layout="vertical">
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="password" label="初始密码" rules={passwordRules}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名">
            <Input />
          </Form.Item>
          <Form.Item
            name="email"
            label="邮箱"
            rules={[{ type: 'email', message: '邮箱格式不正确' }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="mfaRequired" valuePropName="checked">
            <Checkbox>强制 MFA</Checkbox>
          </Form.Item>
          <Form.Item name="roles" label="初始角色">
            <Select mode="multiple" placeholder="可留空" options={roleCatalogOptions} allowClear />
          </Form.Item>
        </Form>
      </Modal>

      {/* 角色 Drawer：当前角色逐条撤销 + 目录赋予 */}
      <Drawer
        title={roleTarget ? `角色管理 · ${roleTarget.username}` : '角色管理'}
        open={roleDrawerOpen}
        onClose={() => {
          setRoleDrawerOpen(false)
          setRoleTarget(null)
        }}
        width={440}
        destroyOnClose
      >
        {roleTarget && (
          <>
            <Alert
              type="info"
              showIcon
              message="角色变更后 authzVersion 会 bump；令牌无状态，最多 900s 内生效"
              style={{ marginBottom: 12 }}
            />
            <Space direction="vertical" size={16} style={{ width: '100%' }}>
              <div>
                <Text type="secondary">当前角色</Text>
                <div style={{ marginTop: 8 }}>
                  {roleTarget.roles.length === 0 ? (
                    <Text type="secondary">没有角色</Text>
                  ) : (
                    <Space direction="vertical" size={6} style={{ display: 'flex' }}>
                      {roleTarget.roles.map((role) => {
                        const guard = roleGuard(roleTarget, role, 'revoke')
                        const revokeBtn = (
                          <Button
                            type="link"
                            size="small"
                            style={{ padding: 0 }}
                            disabled={guard.disabled}
                          >
                            撤销
                          </Button>
                        )
                        return (
                          <Space size={8} key={role}>
                            <Tag color={ROLE_COLORS[role] ?? 'default'}>
                              {ROLE_LABELS[role] ?? role}
                            </Tag>
                            {guard.tip !== null ? (
                              <Tooltip title={guard.tip}>
                                <span style={{ cursor: 'not-allowed' }}>{revokeBtn}</span>
                              </Tooltip>
                            ) : (
                              <Popconfirm
                                title={`确认撤销「${ROLE_LABELS[role] ?? role}」？`}
                                onConfirm={() => {
                                  const target = roleTarget
                                  void runAction(
                                    () => revokePlatformUserRole(target.id, role),
                                    '已撤销',
                                  )
                                }}
                              >
                                {revokeBtn}
                              </Popconfirm>
                            )}
                          </Space>
                        )
                      })}
                    </Space>
                  )}
                </div>
              </div>
              <div>
                <Text type="secondary">赋予角色</Text>
                <Space.Compact style={{ width: '100%', marginTop: 8 }}>
                  <Select
                    style={{ width: '100%' }}
                    placeholder="选择角色"
                    value={assignRole}
                    onChange={setAssignRole}
                    options={roleCatalogOptions}
                  />
                  <Button
                    type="primary"
                    disabled={
                      assignRole === undefined ||
                      (roleTarget !== null && roleGuard(roleTarget, assignRole, 'assign').disabled)
                    }
                    onClick={() => {
                      if (roleTarget === null || assignRole === undefined) return
                      const target = roleTarget
                      void runAction(
                        () =>
                          assignPlatformUserRole(target.id, assignRole).then((res) => {
                            setRoleTarget({ ...target, roles: res.roles })
                            setAssignRole(undefined)
                          }),
                        '已赋予',
                      )
                    }}
                  >
                    赋予
                  </Button>
                </Space.Compact>
                {!iAmSuper && (
                  <Alert
                    type="warning"
                    showIcon
                    message={SUPER_SOD_TIP}
                    style={{ marginTop: 12 }}
                  />
                )}
              </div>
            </Space>
          </>
        )}
      </Drawer>
    </Card>
  )
}
