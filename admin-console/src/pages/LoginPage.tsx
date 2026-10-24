import { useState } from 'react'
import { Form, Input, Button, Card, Typography, App as AntApp, Alert } from 'antd'
import { LockOutlined, UserOutlined, ApartmentOutlined } from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import { login } from '@/api/adminApi'
import { useSession } from '@/auth/session'
import { APP_BASE_PATH } from '@/config/constants'
import type { LoginRequest, LoginResponse } from '@/api/types'

interface LocationState {
  from?: string
}

export function LoginPage(): JSX.Element {
  const { signIn } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const { message } = AntApp.useApp()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const onFinish = async (values: LoginRequest): Promise<void> => {
    setLoading(true)
    setError(null)
    try {
      const resp: LoginResponse = await login(values)
      const now = Date.now()
      signIn({
        accessToken: resp.accessToken,
        expiresAt: now + resp.expiresIn * 1000,
        tenantId: resp.tenantId ?? null,
        principalId: resp.principalId ?? null,
        roles: resp.roles ?? [],
      })
      message.success('登录成功')
      const from = (location.state as LocationState | null)?.from ?? `${APP_BASE_PATH}/dashboard`
      navigate(from, { replace: true })
    } catch (e) {
      setError(e instanceof Error ? e.message : '登录失败')
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
          Agent 管理控制台
        </Typography.Title>
        {error && (
          <Alert type="error" message={error} style={{ marginBottom: 16 }} showIcon />
        )}
        <Form<LoginRequest> layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item
            name="tenantId"
            label="租户 ID"
            rules={[{ required: true, message: '请输入租户 ID' }]}
          >
            <Input prefix={<ApartmentOutlined />} placeholder="tenant id" autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="loginName"
            label="登录名"
            rules={[{ required: true, message: '请输入登录名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="login name" autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="password" autoComplete="off" />
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
