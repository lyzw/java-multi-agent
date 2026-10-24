import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Space,
  Table,
  Tabs,
  Typography,
  App as AntApp,
} from 'antd'
import { ReloadOutlined, RocketOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import {
  applyPublishRequest,
  getPublishPointer,
  getPublishRequests,
} from '@/api/adminApi'
import type { PublishPointer, PublishRequestItem } from '@/api/types'
import { DualReviewNotice, operationTag, requestStateTag } from './publishBits'

/** 状态分组（与后端 ConfigPublishRequest.State 一致） */
const STATE_TABS: { key: string; label: string; state?: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'PENDING', label: '待审批', state: 'PENDING' },
  { key: 'APPROVED', label: '待执行', state: 'APPROVED' },
  { key: 'APPLIED', label: '已生效', state: 'APPLIED' },
  { key: 'REJECTED', label: '已驳回', state: 'REJECTED' },
  { key: 'FAILED', label: '执行失败', state: 'FAILED' },
]

/**
 * 发布中心（P2 · M6）：申请队列 + 当前发布指针。
 *
 * <p>⚠️ <b>三步工作流刻意分开</b>：申请（不写配置表）→ 审批（仍不写）→ 执行（才写）。
 * 合并「审批」与「生效」会让审批人无法区分「我批了」与「配置真的变了」。
 *
 * <p>⚠️ <b>平台级发布不在本通道</b>：Tool / Skill / MCP Server 的发布走带外运维通道
 * （后端对 PLATFORM 作用域显式 403），本页只服务租户级 Agent 发布。
 */
export function PublishCenterPage(): JSX.Element {
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [tab, setTab] = useState('PENDING')
  const [items, setItems] = useState<PublishRequestItem[]>([])
  const [pointer, setPointer] = useState<PublishPointer | null>(null)
  const [loading, setLoading] = useState(true)
  const [applyingId, setApplyingId] = useState<string | null>(null)

  const load = useCallback(async (state?: string) => {
    setLoading(true)
    try {
      const [page, ptr] = await Promise.all([
        getPublishRequests({ state, limit: 50 }),
        getPublishPointer(),
      ])
      setItems(page.items)
      setPointer(ptr)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load(STATE_TABS.find((t) => t.key === tab)?.state)
  }, [load, tab])

  const apply = async (requestId: string): Promise<void> => {
    setApplyingId(requestId)
    try {
      await applyPublishRequest(requestId)
      message.success('已执行生效')
      await load(STATE_TABS.find((t) => t.key === tab)?.state)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        message.error('状态冲突（409）：可能已被他人执行或基线已过期，请刷新')
      } else {
        message.error(e instanceof Error ? e.message : '执行失败')
      }
    } finally {
      setApplyingId(null)
    }
  }

  return (
    <div>
      <Typography.Title level={3}>
        <Space>
          <RocketOutlined /> 发布审批中心
        </Space>
      </Typography.Title>

      <Card size="small" style={{ marginBottom: 16 }}>
        <Descriptions column={2} size="small" items={[
          {
            key: 'seq',
            label: '当前发布序号（CAS 基线）',
            children: pointer?.currentPublishSeq ?? '尚未发布',
          },
          {
            key: 'release',
            label: '当前发布记录',
            children: pointer?.currentReleaseId != null
              ? <Typography.Text code>#{pointer.currentReleaseId}</Typography.Text>
              : '-',
          },
        ]} />
        <Alert
          style={{ marginTop: 12 }}
          type="info"
          showIcon
          message="申请时应携带的 expectedPublishSeq = 当前发布序号"
          description="服务端在执行时强校验基线：你读到的序号若已过期，说明期间已有他人发布，继续执行会覆盖一份你没看过的配置。"
        />
      </Card>

      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={STATE_TABS.map((t) => ({
          key: t.key,
          label: t.label,
          children: (
            <Card
              size="small"
              extra={
                <Button
                  icon={<ReloadOutlined />}
                  size="small"
                  onClick={() => void load(t.state)}
                >
                  刷新
                </Button>
              }
            >
              <Table<PublishRequestItem>
                rowKey="requestId"
                loading={loading}
                dataSource={items}
                pagination={false}
                locale={{ emptyText: '暂无申请' }}
                columns={[
                  {
                    title: '申请',
                    dataIndex: 'requestId',
                    render: (rid: string) => (
                      <Button type="link" onClick={() => navigate(`/publish/requests/${rid}`)}>
                        {rid.slice(0, 8)}…
                      </Button>
                    ),
                    width: 110,
                  },
                  {
                    title: '类型',
                    dataIndex: 'operation',
                    render: operationTag,
                    width: 100,
                  },
                  {
                    title: '状态',
                    dataIndex: 'state',
                    render: requestStateTag,
                    width: 120,
                  },
                  { title: '申请人', dataIndex: 'requesterId', ellipsis: true },
                  {
                    title: '审批人',
                    dataIndex: 'approverId',
                    render: (v: string | null) => v ?? '-',
                  },
                  { title: '申请时间', dataIndex: 'requestedAt', width: 190 },
                  {
                    title: '操作',
                    key: 'actions',
                    width: 90,
                    render: (_: unknown, row) =>
                      row.state === 'APPROVED' ? (
                        <Button
                          size="small"
                          type="primary"
                          loading={applyingId === row.requestId}
                          onClick={() => void apply(row.requestId)}
                        >
                          执行
                        </Button>
                      ) : (
                        '-'
                      ),
                  },
                ]}
              />
            </Card>
          ),
        }))}
      />

      <Card size="small" style={{ marginTop: 16 }}>
        <DualReviewNotice extra="审批通过 ≠ 配置已变：执行是独立第三步，需再次点击「执行」。" />
      </Card>
    </div>
  )
}

export default PublishCenterPage
