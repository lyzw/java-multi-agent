import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button, Card, Form, Input, Modal, Popconfirm, Select, Space,
  Switch, Table, Tag, Typography, App as AntApp,
} from 'antd'
import { PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  createPublishPolicy, deletePublishPolicy, listPublishPolicies,
  updatePublishPolicy, type PublishPolicyCreate, type PublishPolicyItem,
  type PublishMode, type PublishPolicyLevel,
} from '@/api/publishWorkflow'

const MODE_LABELS: Record<PublishMode, { label: string; color: string }> = {
  DIRECT:            { label: '免审直发', color: 'green' },
  MANUAL:            { label: '手动发布', color: 'blue' },
  APPROVAL_MANUAL:   { label: '审批后手动发布', color: 'purple' },
  APPROVAL_AUTO:     { label: '审批后自动发布', color: 'cyan' },
}

const LEVEL_LABELS: Record<PublishPolicyLevel, { label: string; color: string }> = {
  GLOBAL:          { label: '全局', color: 'default' },
  RESOURCE_TYPE:   { label: '资源类型', color: 'geekblue' },
  INSTANCE:        { label: '单实例', color: 'orange' },
}

const RESOURCE_KINDS = [
  { value: 'AGENT', label: 'Agent' },
  { value: 'SKILL', label: 'Skill' },
  { value: 'TOOL', label: 'Tool' },
  { value: 'MCP_SERVER', label: 'MCP Server' },
  { value: 'RESULT_CONTRACT', label: 'Result Contract' },
  { value: 'ROUTING_POLICY', label: 'Routing Policy' },
]

/**
 * 发布策略配置（GLOBAL / RESOURCE_TYPE / INSTANCE 三层）。
 *
 * <p>优先级：单实例覆盖 > 资源类型 > 全局默认。
 */
export function PolicyConfigPage(): JSX.Element {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState<PublishPolicyItem[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<PublishPolicyItem | null>(null)
  const [form] = Form.useForm<PublishPolicyCreate>()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await listPublishPolicies()
      setRows(data)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const handleCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ policyLevel: 'GLOBAL', publishMode: 'MANUAL', enabled: true })
    setOpen(true)
  }

  const handleEdit = (row: PublishPolicyItem) => {
    setEditing(row)
    form.setFieldsValue({
      policyLevel: row.policyLevel,
      resourceKind: row.resourceKind,
      resourceId: row.resourceId,
      publishMode: row.publishMode,
      enabled: row.enabled,
    })
    setOpen(true)
  }

  const handleSubmit = async () => {
    const values = await form.validateFields()
    try {
      if (editing) {
        await updatePublishPolicy(editing.policyId, values)
        message.success('策略已更新')
      } else {
        await createPublishPolicy(values)
        message.success('策略已创建')
      }
      setOpen(false)
      await load()
    } catch (e) {
      const err = e as Error
      message.error(err.message ?? '操作失败')
    }
  }

  const handleDelete = async (row: PublishPolicyItem) => {
    try {
      await deletePublishPolicy(row.policyId)
      message.success('策略已删除')
      await load()
    } catch (e) {
      const err = e as Error
      message.error(err.message ?? '删除失败')
    }
  }

  // 根据当前 form 值决定哪些字段可见
  const watchLevel = Form.useWatch('policyLevel', form) as PublishPolicyLevel | undefined

  const columns = useMemo(() => ([
    {
      title: '层级', dataIndex: 'policyLevel', width: 120,
      render: (v: PublishPolicyLevel) => {
        const m = LEVEL_LABELS[v]
        return <Tag color={m.color}>{m.label}</Tag>
      },
    },
    {
      title: '资源类型', dataIndex: 'resourceKind', width: 180,
      render: (v?: string) => v ?? <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: '资源 ID', dataIndex: 'resourceId', width: 200, ellipsis: true,
      render: (v?: string) => v ?? <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: '发布模式', dataIndex: 'publishMode', width: 200,
      render: (v: PublishMode) => {
        const m = MODE_LABELS[v]
        return <Tag color={m.color}>{m.label}</Tag>
      },
    },
    {
      title: '启用', dataIndex: 'enabled', width: 80,
      render: (v: boolean) => v ? <Tag color="green">启用</Tag> : <Tag color="default">关闭</Tag>,
    },
    { title: '最近修改', dataIndex: 'updatedAt', width: 180 },
    { title: '修改人', dataIndex: 'updatedBy', width: 140 },
    {
      title: '操作', width: 180,
      render: (_: unknown, row: PublishPolicyItem) => (
        <Space size="small">
          <Button size="small" icon={<EditOutlined />} onClick={() => handleEdit(row)}>编辑</Button>
          <Popconfirm title="确认删除此策略？" onConfirm={() => handleDelete(row)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]), [])

  return (
    <Card
      title={<Space><Typography.Title level={4} style={{ margin: 0 }}>发布策略配置</Typography.Title></Space>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建策略</Button>
        </Space>
      }
    >
      <Table
        rowKey="policyId"
        columns={columns}
        dataSource={rows}
        loading={loading}
        pagination={false}
      />

      <Modal
        title={editing ? '编辑发布策略' : '新建发布策略'}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={handleSubmit}
        destroyOnClose
        width={560}
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item label="策略层级" name="policyLevel" rules={[{ required: true }]}>
            <Select options={[
              { value: 'GLOBAL', label: 'GLOBAL（全局默认）' },
              { value: 'RESOURCE_TYPE', label: 'RESOURCE_TYPE（按资源类型）' },
              { value: 'INSTANCE', label: 'INSTANCE（单实例覆盖）' },
            ]} />
          </Form.Item>

          {watchLevel && watchLevel !== 'GLOBAL' && (
            <Form.Item label="资源类型" name="resourceKind" rules={[{ required: true }]}>
              <Select options={RESOURCE_KINDS} />
            </Form.Item>
          )}

          {watchLevel === 'INSTANCE' && (
            <Form.Item label="资源 ID" name="resourceId" rules={[{ required: true }]}>
              <Input placeholder="目标资源的唯一标识" />
            </Form.Item>
          )}

          <Form.Item label="发布模式" name="publishMode" rules={[{ required: true }]}>
            <Select options={[
              { value: 'DIRECT', label: 'DIRECT — 免审直发' },
              { value: 'MANUAL', label: 'MANUAL — 手动发布' },
              { value: 'APPROVAL_MANUAL', label: 'APPROVAL_MANUAL — 审批后手动发布' },
              { value: 'APPROVAL_AUTO', label: 'APPROVAL_AUTO — 审批后自动发布' },
            ]} />
          </Form.Item>

          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
