import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Empty, Space, Spin, Typography } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'

export interface PlatformDetailShellProps {
  /** 标题（如 Tool 标识） */
  title: ReactNode
  /** 副标题（对象类型 + 版本） */
  subtitle?: ReactNode
  loading: boolean
  /** 对象不存在（404） */
  missing: boolean
  /** 加载失败（非 404） */
  error?: string | null
  /** 返回目录的路径 */
  backTo: string
  backLabel: string
  /** 概览区 */
  overview: ReactNode
  /** 关系区 */
  relations: ReactNode
  /** 操作区（登记/修改 + 停用） */
  actions: ReactNode
}

/**
 * 平台级对象详情页的**共用骨架**（第三十四批 · C34-3）。
 *
 * <p>⚠️ 三个详情页（Tool / Skill / MCP）刻意共用一套骨架：它们的差异只在
 * 「关系是什么」「草稿有哪些字段」，而**生命周期语义完全一致**
 * （已发布行不可变 → 只能发新版本；删除 = 停用，不做物理删除）。
 * 骨架一旦分叉，三页的语义就会悄悄漂移。
 */
export function PlatformDetailShell({
  title,
  subtitle,
  loading,
  missing,
  error,
  backTo,
  backLabel,
  overview,
  relations,
  actions,
}: PlatformDetailShellProps): JSX.Element {
  const navigate = useNavigate()

  if (loading) return <Spin spinning style={{ width: '100%', marginTop: 80 }} />

  if (missing) {
    return (
      <div>
        <Typography.Title level={3}>对象不存在</Typography.Title>
        <Alert
          type="warning"
          showIcon
          message="对象不存在或无权限"
          description="404 不区分「不存在」与「无权限」，避免存在性探测。"
          action={<Button onClick={() => navigate(backTo)}>返回{backLabel}</Button>}
        />
      </div>
    )
  }

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(backTo)}>
          返回{backLabel}
        </Button>
      </Space>
      <Typography.Title level={3}>
        <Space>
          {title}
          {subtitle}
        </Space>
      </Typography.Title>
      {error && (
        <Alert
          style={{ marginBottom: 16 }}
          type="error"
          showIcon
          message="加载失败"
          description={error}
        />
      )}
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Card size="small" title="概览">
          {overview}
        </Card>
        <Card size="small" title="关系">
          {relations}
        </Card>
        {actions}
      </Space>
    </div>
  )
}

/** 空关系的占位（避免「空表格」看起来像「加载失败」）。 */
export function RelationsEmpty({ text }: { text: string }): JSX.Element {
  return <Empty description={text} image={Empty.PRESENTED_IMAGE_SIMPLE} />
}

export default PlatformDetailShell
