import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Card,
  Form,
  Input,
  Button,
  Typography,
  Alert,
  App as AntApp,
  Space,
} from 'antd'
import { ApiError } from '@/api/client'
import { provisionTenant } from '@/api/adminApi'

export function TenantNewPage(): JSX.Element {
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [form] = Form.useForm<{ slug: string; name: string }>()
  const [submitting, setSubmitting] = useState(false)
  const [forbidden, setForbidden] = useState(false)

  const onFinish = async (values: { slug: string; name: string }): Promise<void> => {
    setSubmitting(true)
    try {
      const t = await provisionTenant({
        slug: values.slug.trim(),
        name: values.name.trim(),
      })
      message.success(`租户 ${t.slug} 已开通`)
      navigate(`/tenants/${t.tenantId}`)
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) {
        setForbidden(true)
      } else if (e instanceof ApiError && e.status === 409) {
        message.error('开通失败：slug 可能已存在或格式非法')
      } else {
        message.error(e instanceof Error ? e.message : '开通失败')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (forbidden) {
    return (
      <Alert
        type="warning"
        showIcon
        message="无权限开通租户"
        description="开通租户为平台级操作，须经 PLATFORM_ADMIN 作用域。当前账号不持有该角色。"
      />
    )
  }

  return (
    <div>
      <Typography.Title level={3}>开通租户</Typography.Title>
      <Card style={{ maxWidth: 560 }}>
        <Form form={form} layout="vertical" onFinish={(v) => void onFinish(v)}>
          <Form.Item
            label="Slug"
            name="slug"
            extra="2-63 位小写字母/数字/连字符，不以连字符开头或结尾（与后端 CHECK 约束一致）"
            rules={[
              { required: true, message: '请输入 slug' },
              {
                pattern: /^[a-z0-9][a-z0-9-]{1,62}$/,
                message: '格式非法',
              },
            ]}
          >
            <Input placeholder="my-tenant" />
          </Form.Item>
          <Form.Item
            label="名称"
            name="name"
            rules={[{ required: true, message: '请输入名称' }]}
          >
            <Input placeholder="我的租户" />
          </Form.Item>
          <Space>
            <Button type="primary" htmlType="submit" loading={submitting}>
              开通
            </Button>
            <Button onClick={() => navigate('/tenants')}>取消</Button>
          </Space>
        </Form>
      </Card>
    </div>
  )
}
