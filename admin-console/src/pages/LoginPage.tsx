import { useState } from 'react'
import { Form, Input, Button, Card, Typography, App as AntApp, Alert } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
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
 *
 * ⚠️ **会话过期要有话说**（评测 P1-5）：平台令牌有效期 900 秒且不可续期，
 *    过期后 API 层会带 `?reason=expired&next=<回跳目标>` 跳到这里。
 *    本页据此显示「为什么被踢出」，并在登录成功后回到原页面，
 *    而不是无声无息地把用户丢到默认页（现场全丢）。
 */
export function LoginPage(): JSX.Element {
  const { signIn } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const { message } = AntApp.useApp()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /** 令牌过期被动跳来（而非用户主动来登录） */
  const expired = searchParams.get('reason') === 'expired'
  /** 回跳目标（basename 相对路径）；只接受站内相对路径，防开放重定向 */
  const nextParam = searchParams.get('next')
  const next =
    nextParam && nextParam.startsWith('/') && !nextParam.startsWith('//') ? nextParam : null

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
      //    `next`（过期跳来时带的 query）同样是相对路径，优先级最高。
      const from = next ?? (location.state as LocationState | null)?.from ?? '/tenants'
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
        <Alert
          type="info"
          showIcon
          message="仅限平台账号（经运维通道创建），平台身份与租户身份彻底分离"
          style={{ marginBottom: 16 }}
        />
        {expired && (
          <Alert
            type="warning"
            showIcon
            message="会话已过期，请重新登录"
            description="平台会话有效期 15 分钟且不可续期（令牌无状态，过期即硬跳登录）。重新登录后将回到你刚才所在的页面。"
            style={{ marginBottom: 16 }}
          />
        )}
        {error && (
          <Alert type="error" message={error} style={{ marginBottom: 16 }} showIcon />
        )}
        <Form<LoginForm> layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="请输入用户名" autoComplete="username" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="请输入密码" autoComplete="current-password" />
          </Form.Item>
          <Form.Item>
            <a href="#" onClick={(e) => { e.preventDefault(); message.info('请联系运维通道管理员重置密码'); }}>
              忘记密码？
            </a>
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
