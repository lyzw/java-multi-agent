import { useState } from 'react'
import { Form, Input, Button, Card, Typography, App as AntApp, Alert, Tag } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import { platformLogin } from '@/api/adminApi'
import { useSession } from '@/auth/session'

interface LocationState {
  from?: string
}

interface LoginForm {
  username: string
  password: string
}

/**
 * 平台控制台登录页（重规划 §7）。
 *
 * ⛔ 无「租户 ID」输入：平台身份与租户身份彻底分离——
 *    本页只认证 platform_user（out-of-band 通道创建的账号），
 *    签发 aud=admin-console-api 的平台令牌；租户账号在此登录一律 401。
 * ⛔ 登录失败不区分原因（后端统一 401，防平台账号枚举）。
 */
export function LoginPage(): JSX.Element {
  const { signIn } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const { message } = AntApp.useApp()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const onFinish = async (values: LoginForm): Promise<void> => {
    setLoading(true)
    setError(null)
    try {
      const resp = await platformLogin({
        username: values.username.trim(),
        password: values.password,
      })
      const now = Date.now()
      signIn({
        accessToken: resp.accessToken,
        expiresAt: now + resp.expiresIn * 1000,
        tenantId: null, // 平台主体不属于任何租户
        principalId: resp.userId,
        roles: resp.roles ?? [],
      })
      message.success('登录成功')
      // ⚠️ **回跳目标是 basename 相对路径**：Router 的 basename 已是 `/admin`，
      //    而 `navigate` 的目标会**再拼一次** basename ——
      //    写成 `${APP_BASE_PATH}/dashboard` 会跳到 `/admin/admin/dashboard` → 404。
      //    `state.from` 来自 `location.pathname`（已剥掉 basename），可直接回跳。
      const from = (location.state as LocationState | null)?.from ?? '/tenants'
      navigate(from, { replace: true })
    } catch (e) {
      // 统一口径（与后端 401 一致）：不区分「账号不存在 / 口令错 / 状态禁用」
      setError(e instanceof Error ? e.message : '用户名或密码错误')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f0f2f5',
      }}
    >
      <Card style={{ width: 380 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>
          平台管理控制台
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center', marginBottom: 16 }}>
          <Tag>platform identity</Tag>仅限平台账号（经运维通道创建）
        </Typography.Paragraph>
        {error && (
          <Alert type="error" message={error} style={{ marginBottom: 16 }} showIcon />
        )}
        <Form<LoginForm> layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="username" autoComplete="username" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="password" autoComplete="current-password" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={loading}>
              登录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}
