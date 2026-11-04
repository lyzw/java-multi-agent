/**
 * 语料生命周期面板（交互文档 §4.7「检索准入与撤回」）。
 *
 * <p><b>它解决什么</b>：当前「过期」只在列表里表现为一个标签，
 * <b>没有任何提前提醒</b>——语料到期当天才被发现，而那时学生已经搜不到了。
 * 本面板把「已过期 / 即将到期 / 正常」三类分开呈现，已过期排最前。
 *
 * <p><b>⚠️ 三条纪律</b>：
 * <ol>
 *   <li><b>过期由服务端判定</b>：前端不拿 verifiedAt + validDays 自己算，
 *       否则会在边界日/时区上与检索侧口径漂移。</li>
 *   <li><b>「已过期」与「即将到期」视觉必须可区分</b>：前者已经影响学生，
 *       后者还来得及补救；混为一谈会让人误判紧急程度。</li>
 *   <li><b>空态 ≠ 故障</b>：没有已校验语料是正常状态。</li>
 * </ol>
 */

import { useCallback, useEffect, useState } from 'react'
import { Alert, Card, Empty, Space, Spin, Statistic, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { listRagLifecycle, ApiError, isRagTargetReady } from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import type { RagLifecycleItem } from '@/api/ragTypes'

const { Text } = Typography

type LifecycleState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ok'; items: RagLifecycleItem[] }
  | { kind: 'error'; message: string }

export interface RagLifecyclePanelProps {
  target: RagTarget
}

/** 剩余天数 → 展示文案。⚠️ 负数表示已过期。 */
function daysText(item: RagLifecycleItem): string {
  if (item.daysUntilExpiry === null) return '—'
  const d = item.daysUntilExpiry
  if (item.stale) return `已过期 ${Math.abs(d)} 天`
  if (d === 0) return '今天到期'
  return `剩余 ${d} 天`
}

const columns: ColumnsType<RagLifecycleItem> = [
  {
    title: '书名',
    dataIndex: 'title',
    ellipsis: true,
    render: (title: string, row) => (
      <Space direction="vertical" size={0}>
        <Text strong>{title}</Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {row.verificationStatus}
        </Text>
      </Space>
    ),
  },
  {
    title: '有效期状态',
    key: 'status',
    width: 160,
    render: (_: unknown, row) => {
      // ⚠️ 三态必须可区分：「已过期」已经影响学生，「即将到期」还来得及补救
      if (row.stale) return <Tag color="red">已过期</Tag>
      if (row.expiringSoon) return <Tag color="orange">即将到期</Tag>
      return <Tag color="green">有效期内</Tag>
    },
  },
  {
    title: '剩余时间',
    key: 'days',
    width: 130,
    render: (_: unknown, row) => (
      <Text type={row.stale ? 'danger' : row.expiringSoon ? 'warning' : undefined}>
        {daysText(row)}
      </Text>
    ),
  },
  {
    title: '有效期',
    dataIndex: 'validDays',
    key: 'validDays',
    width: 100,
    render: (v: number) => `${v} 天`,
  },
  {
    title: '学生能否搜到',
    key: 'admissible',
    width: 130,
    render: (_: unknown, row) => (
      <Tag color={row.admissible ? 'green' : 'default'}>
        {row.admissible ? '可检索' : '不可检索'}
      </Tag>
    ),
  },
]

export function RagLifecyclePanel({ target }: RagLifecyclePanelProps): JSX.Element {
  const [state, setState] = useState<LifecycleState>({ kind: 'idle' })

  const load = useCallback(async (): Promise<void> => {
    if (!isRagTargetReady(target)) {
      setState({ kind: 'idle' })
      return
    }
    setState({ kind: 'loading' })
    try {
      const items = await listRagLifecycle(target, 200)
      setState({ kind: 'ok', items })
    } catch (cause) {
      setState({
        kind: 'error',
        message: cause instanceof ApiError ? cause.message : '读取生命周期失败',
      })
    }
  }, [target])

  useEffect(() => {
    void load()
  }, [load])

  if (state.kind === 'idle') {
    return <Text type="secondary">请先选择租户或切换到平台全局档。</Text>
  }
  if (state.kind === 'loading') return <Spin />
  if (state.kind === 'error') {
    return (
      <Alert
        type="error"
        showIcon
        message="读取生命周期失败"
        description={state.message}
        action={
          <button type="button" className="ant-btn ant-btn-sm" onClick={() => void load()}>
            重试
          </button>
        }
      />
    )
  }

  const items = state.items
  if (items.length === 0) {
    return (
      <Empty description="还没有通过校验的语料。通过校验后，这里会显示有效期与到期提醒。" />
    )
  }

  const expired = items.filter((i) => i.stale)
  const soon = items.filter((i) => i.expiringSoon)

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space size="large" wrap>
        <Statistic title="已校验语料" value={items.length} />
        <Statistic
          title="已过期"
          value={expired.length}
          valueStyle={expired.length > 0 ? { color: '#cf1322' } : undefined}
        />
        <Statistic
          title="即将到期（30 天内）"
          value={soon.length}
          valueStyle={soon.length > 0 ? { color: '#d46b08' } : undefined}
        />
      </Space>

      {expired.length > 0 ? (
        <Alert
          type="error"
          showIcon
          message={`有 ${expired.length} 册已过期，学生现在搜不到它们`}
          description="过期后语料会被检索侧排除。请对这些语料重新发起校验（含抽检）以恢复可用性。"
        />
      ) : null}

      {soon.length > 0 && expired.length === 0 ? (
        <Alert
          type="warning"
          showIcon
          message={`有 ${soon.length} 册将在 30 天内到期`}
          description="续期需要走校验流程（含抽检），建议尽早处理，避免到期当天学生突然搜不到。"
        />
      ) : null}

      <Card size="small" title="全部已校验语料（已过期排最前）">
        <Table<RagLifecycleItem>
          rowKey="sourceId"
          size="small"
          columns={columns}
          dataSource={items}
          pagination={{ pageSize: 10 }}
        />
      </Card>
    </Space>
  )
}
