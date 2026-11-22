import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { Button, Card, Space, Typography } from 'antd'
import { ToolCatalogDetail, SkillCatalogDetail, McpCatalogDetail } from './platformDetailDrawer'

/**
 * 目录详情「新页面」视图（全局配置 cap.detail.mode = page 时使用）。
 *
 * <p>⚠️ <b>与抽屉 / 弹窗共用同一套详情组件</b>（{@link ToolCatalogDetail} /
 * {@link SkillCatalogDetail} / {@link McpCatalogDetail}）—— 三种形式下
 * 详情内容、历史记录与变更对比的交互完全一致，只是外壳不同。
 * 详情组件只依赖 id 自行取数，因此 URL 可以无损重建。
 *
 * <p>⚠️ Agent 详情不在本路由内：Agent 注册表详情依赖整行聚合数据
 * （绑定清单 / 发布状态），无法从 URL 重建；Agent 已有独立的 /agents/:id 详情页。
 */
export function CatalogDetailPage(): JSX.Element {
  const { kind = '', id = '' } = useParams<{ kind: string; id: string }>()
  const navigate = useNavigate()

  const title =
    kind === 'tools' ? 'Tool 详情'
    : kind === 'skills' ? 'Skill 详情'
    : kind === 'mcps' ? 'MCP Server 详情'
    : '详情'

  return (
    <div style={{ maxWidth: 1100 }}>
      <Space style={{ marginBottom: 12 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>
          返回列表
        </Button>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {title}
          {id ? ` · ${id}` : ''}
        </Typography.Title>
      </Space>
      <Card size="small">
        {kind === 'tools' && id ? <ToolCatalogDetail id={id} /> : null}
        {kind === 'skills' && id ? <SkillCatalogDetail id={id} /> : null}
        {kind === 'mcps' && id ? <McpCatalogDetail id={id} /> : null}
        {!['tools', 'skills', 'mcps'].includes(kind) && (
          <Typography.Text type="secondary">未知的详情类型：{kind}</Typography.Text>
        )}
      </Card>
    </div>
  )
}

export default CatalogDetailPage
