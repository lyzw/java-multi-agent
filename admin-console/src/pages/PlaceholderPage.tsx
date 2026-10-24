import { Result, Typography, Tag } from 'antd'
import type { NavNode } from '@/layout/navConfig'

export function PlaceholderPage({ node }: { node?: NavNode }): JSX.Element {
  const title = node?.label ?? '页面未找到'
  const desc = node?.description ?? '该路径未匹配到任何已规划模块。'
  const phase = node?.phase

  return (
    <div>
      <Result
        status="warning"
        title={title}
        subTitle={desc}
        extra={phase ? <Tag color="orange">{phase} 阶段规划中</Tag> : undefined}
      />
      {node && (
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center' }}>
          本模块属于《管理后台开发计划》{node.phase} 阶段，将在后续迭代中实现。
        </Typography.Paragraph>
      )}
    </div>
  )
}
