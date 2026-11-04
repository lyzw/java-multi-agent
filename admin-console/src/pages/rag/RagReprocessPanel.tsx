/**
 * 重处理面板（交互文档 §4.3「追加重传」）。
 *
 * <p><b>它解决什么</b>：当前想重跑的用户只有两个选择——
 * 含糊的「重试」，或手册里的 {@code POST /reindex}（而它**并不会重新切分**）。
 * 于是「我点了 reindex，但切分还是老样子」成了高频误解。
 * 本面板把三种重处理**显式列出**，各自标注代价与后果。
 *
 * <p><b>⚠️ 四条纪律</b>：
 * <ol>
 *   <li><b>动手前先看后果</b>：每种模式都写清「会发生什么」，不是只给按钮；</li>
 *   <li><b>重选择不重操作</b>：点按钮只是展开确认，不直接执行；</li>
 *   <li><b>不可逆的要二次确认</b>（换文件）；</li>
 *   <li><b>501 要说清「能力未上线」而不是「你操作失败」</b>——
 *       前者是系统边界，后者会让人以为是自己用错了。</li>
 * </ol>
 */

import { useState } from 'react'
import { Alert, App, Button, Card, Modal, Space, Tag, Typography } from 'antd'
import { ApiError } from '@/api/ragClient'
import { reprocessRagSource } from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import {
  RAG_REPROCESS_MODE_META,
} from '@/api/ragTypes'
import type { RagReprocessMode } from '@/api/ragTypes'

const { Text } = Typography

/** 需要二次确认的模式（不可逆）。 */
const NEEDS_CONFIRM: ReadonlySet<RagReprocessMode> = new Set(['REPLACE_FILE'])

export interface RagReprocessPanelProps {
  target: RagTarget
  sourceId: string
  /** 是否可写（平台档需票据；不可写时只展示不执行） */
  writable: boolean
}

const MODES: RagReprocessMode[] = ['REINDEX_MISSING', 'RESPLIT', 'REPLACE_FILE']

export function RagReprocessPanel({
  target,
  sourceId,
  writable,
}: RagReprocessPanelProps): JSX.Element {
  const { message } = App.useApp()
  const [busy, setBusy] = useState<RagReprocessMode | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const run = async (mode: RagReprocessMode): Promise<void> => {
    setBusy(mode)
    setError(null)
    setNotice(null)
    try {
      const result = await reprocessRagSource(target, sourceId, mode)
      // ⚠️ 用后端返回的 message 而非自己编：后端才知道「实际做了什么」
      setNotice(result.message)
      message.success('已受理')
    } catch (cause) {
      if (cause instanceof ApiError) {
        // ⚠️ 501 = 能力未上线，不是用户操作错误。文案必须区分，
        //    否则用户会反复尝试一个注定不支持的入口。
        setError(
          cause.status === 501
            ? `这项能力暂未上线：${cause.message}`
            : cause.message,
        )
      } else {
        setError('重处理失败，请稍后重试')
      }
    } finally {
      setBusy(null)
    }
  }

  const onClick = (mode: RagReprocessMode): void => {
    if (NEEDS_CONFIRM.has(mode)) {
      Modal.confirm({
        title: RAG_REPROCESS_MODE_META[mode].title,
        content: (
          <span>
            {RAG_REPROCESS_MODE_META[mode].consequence}
            <br />
            <b>此操作不可撤销。</b>
          </span>
        ),
        okText: '确认执行',
        cancelText: '取消',
        okButtonProps: { danger: true },
        onOk: () => run(mode),
      })
      return
    }
    void run(mode)
  }

  return (
    <Space direction="vertical" size="small" style={{ width: '100%' }}>
      <Text type="secondary" style={{ fontSize: 12 }}>
        重处理有三种方式，代价差别很大。请先看清后果再选：
      </Text>

      {error && <Alert type="error" showIcon message="重处理未完成" description={error} />}
      {notice && <Alert type="success" showIcon message="已执行" description={notice} />}

      {MODES.map((mode) => {
        const meta = RAG_REPROCESS_MODE_META[mode]
        return (
          <Card key={mode} size="small" styles={{ body: { padding: 12 } }}>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Space wrap>
                <Text strong>{meta.title}</Text>
                <Tag color={mode === 'REINDEX_MISSING' ? 'green' : mode === 'RESPLIT' ? 'orange' : 'red'}>
                  {meta.cost}
                </Tag>
                {NEEDS_CONFIRM.has(mode) ? <Tag color="red">不可撤销</Tag> : null}
              </Space>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {meta.consequence}
              </Text>
              <Button
                size="small"
                type={mode === 'REINDEX_MISSING' ? 'primary' : 'default'}
                danger={NEEDS_CONFIRM.has(mode)}
                disabled={!writable}
                loading={busy === mode}
                onClick={() => onClick(mode)}
              >
                执行
              </Button>
            </Space>
          </Card>
        )
      })}

      {!writable ? (
        <Text type="secondary" style={{ fontSize: 12 }}>
          当前作用域不可写（平台全局档需先领取治理票据）。
        </Text>
      ) : null}
    </Space>
  )
}
