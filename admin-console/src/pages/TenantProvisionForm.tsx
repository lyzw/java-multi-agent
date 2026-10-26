import { useState } from 'react'
import {
  Button,
  Form,
  Input,
  Alert,
  App as AntApp,
  Space,
} from 'antd'
import { ApiError } from '@/api/client'
import { createPlatformTenant } from '@/api/adminApi'

/**
 * 租户开通表单（第三十七批 · C37-3：从整页改为弹窗）。
 *
 * <p><b>⚠️ 为什么抽成独立组件</b>：登记改为弹窗后，
 * 「开通」有两个宿主（目录页的弹窗 / 深链 {@code /tenants/new}）。
 * 字段只有<b>这一份</b>，两处都渲染它 ——
 * 否则「弹窗那份少了一条 slug 格式校验」会直接变成 400。
 *
 * <p>平台身份域：走 {@code POST /api/platform/v1/tenants}，
 * 新租户进入 PENDING_SETUP，<b>不自动创建租户管理员</b>（管理员由租户侧建立）。
 */
export interface TenantProvisionFormProps {
  /** 开通成功后的回调（带租户标识，便于跳转详情页） */
  onProvisioned?: (tenant: { tenantId: string; slug: string }) => void
}

/**
 * 租户开通表单。
 *
 * @param props 回调
 * @returns 表单
 */
export function TenantProvisionForm({ onProvisioned }: TenantProvisionFormProps): JSX.Element {
  const { message } = AntApp.useApp()
  const [form] = Form.useForm<{ slug: string; name: string; plan?: string }>()
  const [submitting, setSubmitting] = useState(false)
  const [forbidden, setForbidden] = useState(false)

  const onFinish = async (values: { slug: string; name: string; plan?: string }): Promise<void> => {
    setSubmitting(true)
    try {
      const plan = values.plan?.trim()
      const t = await createPlatformTenant({
        slug: values.slug.trim(),
        name: values.name.trim(),
        ...(plan ? { plan } : {}),
      })
      message.success(`租户 ${t.slug} 已开通（PENDING_SETUP）`)
      onProvisioned?.({ tenantId: t.tenantId, slug: t.slug })
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
        description="开通租户需 platform.tenant.write 权限（TENANT_LIFECYCLE_ADMIN / SUPER_ADMIN，经运维通道授权）。当前账号不持有。"
      />
    )
  }

  return (
    <Form form={form} layout="vertical" onFinish={(v) => void onFinish(v)}>
      <Form.Item
        label="Slug"
        name="slug"
        extra="2-63 位小写字母/数字/连字符，不以连字符开头或结尾（后端校验一致）"
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
      <Form.Item
        label="套餐（可选）"
        name="plan"
        extra="留空使用平台默认套餐"
      >
        <Input placeholder="standard" />
      </Form.Item>
      <Space>
        <Button type="primary" htmlType="submit" loading={submitting}>
          开通
        </Button>
      </Space>
    </Form>
  )
}

export default TenantProvisionForm
