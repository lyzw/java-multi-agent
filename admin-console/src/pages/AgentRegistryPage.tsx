import { useEffect, useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Card,
  Table,
  Tag,
  Button,
  Space,
  Typography,
  Modal,
  Form,
  Input,
  App as AntApp,
} from 'antd'
import { PlusOutlined, RobotOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { getAgentList, createAgent } from '@/api/adminApi'
import type { AgentItem } from '@/api/types'

/**
 * Agent 管理 · 列表（P1 · 租户级，复用 /api/agents）。
 *
 * <p>⚠️ 租户级实体：本页 CRUD 真实走应用层（{@code /api/agents}），
 * 与平台级 Tool/Skill/MCP（只读目录 + 带外运维）形成对照。
 * 发布版本属「不可变快照」模型，编辑入口在详情页（要改只能再发一个版本）。
 */
export function AgentRegistryPage(): JSX.Element {
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [data, setData] = useState<AgentItem[]>([])
  const [loading, setLoading] = useState(true)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<{ name: string; description?: string }>()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const page = await getAgentList({ limit: 50 })
      setData(page.items)
      setNextCursor(page.nextCursor)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载 Agent 列表失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void load()
  }, [load])

  const submitCreate = async (): Promise<void> => {
    let values: { name: string; description?: string }
    try {
      values = await form.validateFields()
    } catch {
      return // 表单校验未过
    }
    setSubmitting(true)
    try {
      await createAgent({
        name: values.name,
        description: values.description?.trim() ? values.description : null,
      })
      message.success('Agent 已创建，可前往详情发布首个版本')
      setCreateOpen(false)
      form.resetFields()
      void load()
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        message.error('同名 Agent 已存在（409）')
      } else {
        message.error(e instanceof Error ? e.message : '创建失败')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div>
      <Typography.Title level={3}>
        <Space>
          <RobotOutlined /> Agent 管理
        </Space>
      </Typography.Title>
      <Card
        styles={{ body: { paddingBottom: 12 } }}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建 Agent
          </Button>
        }
      >
        <Table<AgentItem>
          rowKey="agentId"
          loading={loading}
          dataSource={data}
          pagination={false}
          onRow={(record) => ({ onClick: () => navigate(`/agents/${record.agentId}`) })}
          locale={{ emptyText: '暂无 Agent' }}
          columns={[
            {
              title: '名称',
              dataIndex: 'name',
              render: (v: string, r) => (
                <Space direction="vertical" size={0}>
                  <Typography.Link>{v}</Typography.Link>
                  {r.description && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {r.description}
                    </Typography.Text>
                  )}
                </Space>
              ),
            },
            {
              title: '版本',
              dataIndex: 'versionCount',
              render: (_: unknown, r) =>
                r.versionCount === 0 ? (
                  <Tag color="orange">尚无版本</Tag>
                ) : (
                  <Tag color="blue">v{r.latestVersionNumber} · 共 {r.versionCount}</Tag>
                ),
            },
            {
              title: '最近发布',
              dataIndex: 'latestPublishedAt',
              render: (v: string | null) => v ?? '-',
            },
            {
              title: '操作',
              key: 'actions',
              render: (_, r) => (
                <Button size="small" onClick={(e) => { e.stopPropagation(); navigate(`/agents/${r.agentId}`) }}>
                  详情 / 发布
                </Button>
              ),
            },
          ]}
        />
        {nextCursor && (
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
            还有更多（游标 {nextCursor}）。
          </Typography.Paragraph>
        )}
      </Card>

      <Modal
        open={createOpen}
        title="新建 Agent"
        confirmLoading={submitting}
        onOk={() => void submitCreate()}
        onCancel={() => setCreateOpen(false)}
        okText="创建"
        cancelText="取消"
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="name"
            label="名称"
            rules={[
              { required: true, message: '请输入 Agent 名称' },
              { max: 200, message: '名称过长（>200）' },
            ]}
          >
            <Input placeholder="例如：错题归因助手" />
          </Form.Item>
          <Form.Item name="description" label="描述（可选）">
            <Input.TextArea rows={2} placeholder="用途说明（可留空）" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default AgentRegistryPage
