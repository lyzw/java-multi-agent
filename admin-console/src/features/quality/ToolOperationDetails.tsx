import { useState } from 'react'
import { Alert, Button, Descriptions, Space, Spin, Typography } from 'antd'
import { ApiError } from '@/api/client'
import { fetchToolOperation } from '@/api/traceReviewApi'

/**
 * TOOL 消息的「原始详情」展开块（详细设计 §8.3）。
 *
 * ⚠️ **为什么摘要不够用**：对话回放里的 TOOL 行只存结果摘要
 *   （输出封顶 2000 字 + 原因文本）。要判断「这次工具调用到底传了什么参数、
 *   原始返回是什么、是不是被改过」，必须回查 `tool_operation` 表。
 *
 * ⚠️ **按需加载，不预取**：参数原文敏感度高一档（常含题面、检索 query），
 *   审查者多数时候并不需要它。默认折叠、点击才拉取。
 *
 * ⚠️ **tenantId 缺失时不渲染按钮**：tool_operation 是 FORCE RLS 的租户域表，
 *   只有 (tenantId, operationId) 能命中。没有 tenantId 时宁可不给入口，
 *   也不发一个注定 404 的请求。
 */

interface Props {
  /** 工具操作标识；为 null 表示这条 TOOL 行没有关联到 tool_operation。 */
  operationId: string | null
  /** 所属租户；缺失时无法回查（端点要求显式 tenantId）。 */
  tenantId?: string
}

function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 404) {
      return '找不到这次工具调用的原始记录（可能已被保留期清理，或该行未关联 operationId）'
    }
    if (e.code === 'ACCESS_DENIED') {
      return '当前账号缺少 platform.resource.read，无权读取工具调用详情'
    }
    return e.message
  }
  return '网络错误，请稍后重试'
}

export function ToolOperationDetails({ operationId, tenantId }: Props): JSX.Element | null {
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState<string | null>(null)

  // ⚠️ 没有 operationId：说明这条 TOOL 行是「未登记工具/预算拒绝」这类
  //    根本没产生 tool_operation 记录的场景 —— 不给入口，避免用户点了必然 404。
  if (operationId === null || operationId === '') {
    return null
  }
  if (tenantId === undefined || tenantId === '') {
    return (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        未关联 tool_operation（缺租户上下文，无法回查原始参数）
      </Typography.Text>
    )
  }

  const load = async (): Promise<void> => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetchToolOperation(operationId, tenantId)
      setDetail(res.detail)
    } catch (e: unknown) {
      setError(describeError(e))
    } finally {
      setLoading(false)
    }
  }

  if (!open) {
    return (
      <Button size="small" onClick={() => { setOpen(true); void load() }}>
        查看工具原始详情
      </Button>
    )
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {loading && <Spin size="small" tip="读取工具调用详情…" />}
      {error !== null && (
        <Alert type="warning" showIcon message="读取工具调用详情失败" description={error} />
      )}
      {detail !== null && (
        <Descriptions size="small" column={1} bordered items={[
          { key: 'tool', label: '工具', children: String(detail.toolName ?? '—') },
          { key: 'state', label: '状态', children: String(detail.state ?? '—') },
          { key: 'risk', label: '风险级别', children: String(detail.riskLevel ?? '—') },
          {
            key: 'args',
            label: '调用参数（原文）',
            children: (
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                {renderJson(detail.toolArguments)}
              </Typography.Paragraph>
            ),
          },
          {
            key: 'result',
            label: '返回结果（原文）',
            children: (
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                {renderJson(detail.resultPayload)}
              </Typography.Paragraph>
            ),
          },
          {
            key: 'digest',
            label: '请求摘要',
            children: String(detail.requestDigest ?? '—'),
          },
        ]} />
      )}
    </Space>
  )
}

/** 把可能为 null / 字符串 / JSON 的列渲染成可读文本。 */
function renderJson(value: unknown): string {
  if (value === null || value === undefined) {
    return '（无）'
  }
  if (typeof value === 'string') {
    return value === '' ? '（空）' : value
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}
