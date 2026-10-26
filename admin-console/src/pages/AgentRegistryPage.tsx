import { useEffect, useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Card,
  Table,
  Tag,
  Button,
  Modal,
  Space,
  Typography,
  App as AntApp,
} from 'antd'
import { PlusOutlined, RobotOutlined, EditOutlined } from '@ant-design/icons'
import { getAgentList } from '@/api/adminApi'
import type { AgentItem } from '@/api/types'
import { AgentDraftPanel } from './agents/AgentDraftPanel'
import { useRegistrationModal } from './catalog/useRegistrationModal'

/**
 * Agent 管理 · 列表（P1 · 租户级，复用 /api/agents）。
 *
 * <p>⚠️ 租户级实体：本页 CRUD 真实走应用层（{@code /api/agents}），
 * 与平台级 Tool/Skill/MCP（只读目录 + 带外运维）形成对照。
 * 发布版本属「不可变快照」模型，编辑入口在详情页（要改只能再发一个版本）。
 *
 * <p><b>⚠️ 「新建」改用弹窗</b>（第三十七批 · C37-3）：
 * 新建要采集**全字段**（角色 / 启用技能 / 技能包 / 能力标签 / 收紧覆盖 / 编排声明），
 * 装在一个弹窗里确实紧张 —— 但**紧张不是保留整页的理由**：
 * 字段已按主题分成页签（基本信息 / 能力声明 / 收紧覆盖 / 编排声明），
 * 每个页签只讲一件事，弹窗（可滚动、够宽）完全装得下。
 * 而「新建在整页、修改在详情页」这两个宿主共用<b>同一张草稿表单</b>
 * （{@code AgentDraftPanel}），所以不会漂移。
 */
export function AgentRegistryPage(): JSX.Element {
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [data, setData] = useState<AgentItem[]>([])
  const [loading, setLoading] = useState(true)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const registration = useRegistrationModal()

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
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={registration.openModal}
          >
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
              title: 'Agent 标识',
              dataIndex: 'agentId',
              render: (v: string) => (
                <Typography.Text code style={{ fontSize: 12 }}>
                  {v}
                </Typography.Text>
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
                <Space size="small">
                  <Button
                    size="small"
                    icon={<EditOutlined />}
                    onClick={(e) => {
                      e.stopPropagation()
                      navigate(`/agents/${r.agentId}?tab=edit`)
                    }}
                  >
                    编辑
                  </Button>
                  <Button
                    size="small"
                    onClick={(e) => {
                      e.stopPropagation()
                      navigate(`/agents/${r.agentId}`)
                    }}
                  >
                    详情
                  </Button>
                </Space>
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

      {/* ⚠️ 新建走弹窗，但表单仍是**同一张** AgentDraftPanel ——
          「新建」与「修改」共用一张草稿表单，字段不会漂移。 */}
      <Modal
        open={registration.open}
        title="新建 Agent（全字段草稿 · 申请 → 双人复核 → 执行）"
        onCancel={registration.closeModal}
        width={960}
        footer={null}
        styles={{ body: { maxHeight: '74vh', overflowY: 'auto' } }}
        destroyOnClose
      >
        <AgentDraftPanel
          registration
          definition={null}
          definitionUnavailable={false}
          onSubmitted={(r) => {
            registration.closeModal()
            void load()
            // ⚠️ 无论成功与否都跳到详情页：失败时那里能直接重试发布申请，
            //    成功时那里能看到刚建出来的 Agent（弹窗会消失，信息不该跟着消失）。
            navigate(`/agents/${r.agentId}`)
          }}
        />
      </Modal>
    </div>
  )
}

export default AgentRegistryPage
