/**
 * 审核收件箱（交互文档 §4.6「审核收件箱」）。
 *
 * <p><b>它解决什么</b>：审核员当前要「搜索 → 找人 → 读上下文」才知道今天该审哪几本。
 * 本面板把待审语料做成一份**可执行清单**：每条卡片直接说「下一步做什么、
 * 为什么现在不能做」，而不是让人自己去猜流程走到哪了。
 *
 * <p><b>⚠️ 四条交互纪律</b>：
 * <ol>
 *   <li><b>下一步由服务端给</b>：{@code stage}/{@code stageText} 直接渲染，
 *       前端不推算——自行推算会与后端守卫口径漂移，产出必然 409 的按钮。</li>
 *   <li><b>不可执行要说清为什么</b>：「这是你自己采集的语料」比灰按钮有用得多。</li>
 *   <li><b>答案类片段数必须露出</b>：它是抽检最关键的对象（漏判即泄题）。</li>
 *   <li><b>空态 ≠ 故障</b>：「暂无待审」与「读取失败」是两种完全不同的结论。</li>
 * </ol>
 */

import { useCallback, useEffect, useState } from 'react'
import { Alert, Card, Empty, Space, Spin, Statistic, Tag, Typography } from 'antd'
import { listRagReviewQueue, ApiError, isRagTargetReady } from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import type { RagReviewQueueItem } from '@/api/ragTypes'
import {
  RAG_REVIEW_STAGE_LABELS,
  RAG_REVIEW_STAGE_COLORS,
} from '@/api/ragTypes'

const { Text } = Typography

type QueueState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'empty' }
  | { kind: 'ok'; items: RagReviewQueueItem[] }
  | { kind: 'error'; message: string }

export interface RagReviewInboxProps {
  target: RagTarget
  /** 点击某条待办（打开详情/抽检） */
  onOpen?: (item: RagReviewQueueItem) => void
}

export function RagReviewInbox({ target, onOpen }: RagReviewInboxProps): JSX.Element {
  const [state, setState] = useState<QueueState>({ kind: 'idle' })

  const load = useCallback(async (): Promise<void> => {
    if (!isRagTargetReady(target)) {
      setState({ kind: 'idle' })
      return
    }
    setState({ kind: 'loading' })
    try {
      const items = await listRagReviewQueue(target, 100)
      setState(items.length === 0 ? { kind: 'empty' } : { kind: 'ok', items })
    } catch (cause) {
      setState({
        kind: 'error',
        message: cause instanceof ApiError ? cause.message : '读取审核待办失败',
      })
    }
  }, [target])

  useEffect(() => {
    void load()
  }, [load])

  if (state.kind === 'idle') {
    return (
      <Text type="secondary">
        请先选择租户或切换到平台全局档，待办清单会显示在这里。
      </Text>
    )
  }
  if (state.kind === 'loading') return <Spin />
  if (state.kind === 'error') {
    return (
      <Alert
        type="error"
        showIcon
        message="读取审核待办失败"
        description={state.message}
        action={
          <button type="button" className="ant-btn ant-btn-sm" onClick={() => void load()}>
            重试
          </button>
        }
      />
    )
  }
  if (state.kind === 'empty') {
    // ⚠️ 空态与故障必须区分：这里确实是「没有待办」，不是读不到
    return <Empty description="暂时没有待审语料。新录入的语料会自动出现在这里。" />
  }

  const items = state.items
  /** 当前可执行的条数（≥1 说明有活可干）。 */
  const actionable = items.filter((i) => i.canAct).length

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space size="large" wrap>
        <Statistic title="待审总数" value={items.length} />
        <Statistic title="现在可处理" value={actionable} />
        <Statistic
          title="含答案类片段"
          value={items.filter((i) => i.answerChunkCount > 0).length}
          suffix={<Text type="secondary" style={{ fontSize: 12 }}>册（抽检重点）</Text>}
        />
      </Space>

      {items.map((item) => {
        const stageLabel = RAG_REVIEW_STAGE_LABELS[item.stage] ?? item.stage
        const stageColor = RAG_REVIEW_STAGE_COLORS[item.stage] ?? 'default'
        return (
          <Card
            key={item.sourceId}
            size="small"
            title={
              <Space wrap size={6}>
                <Text strong>{item.title}</Text>
                <Tag color={stageColor}>{stageLabel}</Tag>
                {/* ⚠️ 答案类片段数必须露出：它是抽检最关键的对象 */}
                {item.answerChunkCount > 0 ? (
                  <Tag color="red">答案类 {item.answerChunkCount} 条</Tag>
                ) : null}
              </Space>
            }
            extra={
              <Text type="secondary" style={{ fontSize: 12 }}>
                {item.publisher} · {item.chunkCount} 片段 / {item.documentCount} 载体
              </Text>
            }
          >
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Text>{item.stageText}</Text>

              {/*
                ⚠️ 不可执行时**说清原因**，而不是只给一个灰按钮：
                    「这是你自己采集的语料」比任何禁用样式都有用。
              */}
              {item.canAct ? (
                <button
                  type="button"
                  className="ant-btn ant-btn-sm ant-btn-primary"
                  onClick={() => onOpen?.(item)}
                >
                  {stageLabel}
                </button>
              ) : (
                <Alert
                  type="warning"
                  showIcon
                  message={item.cantActReason ?? '当前账号不能处理这条待办'}
                />
              )}
            </Space>
          </Card>
        )
      })}
    </Space>
  )
}
