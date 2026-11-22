import { useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Col, Row, Space, Tag, Typography } from 'antd'
import { NAV_GROUPS, type NavNode } from '@/layout/navConfig'

const { Title, Paragraph, Text } = Typography

const SECTION_COLORS: Record<string, string> = {
  总览: 'blue',
  录入与校对: 'green',
  审核: 'orange',
  规则配置: 'geekblue',
  运维: 'purple',
}

interface WorkbenchSection {
  key: string
  entries: NavNode[]
}

/**
 * RAG 工作台总览（/rag）。
 *
 * <p>⚠️ 页面清单**不另写一份**：直接从导航分组 `rag-workbench` 的 `section` 派生。
 *    之前的分散问题不是「少一个入口页」，而是菜单、旧单页与配置中心各存一份路径，
 *    改一处就漂移一处。这里只做索引与口径说明，不复制任何页面表单或写入逻辑。
 *
 * <p>旧五 Tab 单页保留在 `/rag/legacy`（不进侧栏），避免既有书签与能力丢失。
 */
export function RagWorkbenchPage(): JSX.Element {
  const navigate = useNavigate()

  const sections = useMemo<WorkbenchSection[]>(() => {
    const group = NAV_GROUPS.find((g) => g.key === 'rag-workbench')
    const entries = (group?.children ?? []).filter((n) => n.path !== '/rag')
    const keys = [...new Set(entries.map((n) => n.section).filter((s): s is string => !!s))]
    return keys.map((key) => ({
      key,
      entries: entries.filter((n) => n.section === key),
    }))
  }, [])

  return (
    <div style={{ maxWidth: 1240 }}>
      <Title level={4}>RAG 工作台</Title>
      <Paragraph type="secondary">
        语料治理的全部页面收口在这里：先看健康度，再按「录入与校对 / 审核 / 规则配置 / 运维」进入专用页。
        本页只做索引与口径说明，不重复实现任何读写表单。
      </Paragraph>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="作用域与凭据统一口径"
        description={
          <Space direction="vertical" size={2}>
            <Text>· 默认「平台全局语料」：读走登录态，写必须先领治理票据；平台档禁带租户头。</Text>
            <Text>· 「本租户语料」必须显式选租户；未选租户不发任何请求，缺租户头后端返回 400。</Text>
            <Text>· 「跨租户盘点」需要运维数据源，当前后端 501，入口保持禁用而非显示空结果。</Text>
            <Text>· 切档位 / 切租户会清空页面数据并作废票据，避免把 A 租户的结论当成 B 租户的。</Text>
          </Space>
        }
      />

      {sections.map((section) => (
        <Card
          key={section.key}
          size="small"
          title={section.key}
          style={{ marginBottom: 16 }}
          extra={<Text type="secondary">{section.entries.length} 个页面</Text>}
        >
          <Row gutter={[12, 12]}>
            {section.entries.map((entry) => (
              <Col key={entry.path} xs={24} sm={12} lg={8}>
                <Card size="small" variant="borderless" style={{ height: '100%', background: '#fafafa' }}>
                  <Space direction="vertical" size={8} style={{ width: '100%' }}>
                    <Space align="center" wrap>
                      {entry.icon}
                      <Text strong>{entry.label}</Text>
                      <Tag color={SECTION_COLORS[section.key] ?? 'default'}>{section.key}</Tag>
                    </Space>
                    <Text type="secondary" style={{ minHeight: 42, display: 'block' }}>
                      {entry.description}
                    </Text>
                    <Button
                      type="link"
                      size="small"
                      style={{ padding: 0, textAlign: 'left' }}
                      onClick={() => navigate(entry.path)}
                    >
                      进入页面 →
                    </Button>
                  </Space>
                </Card>
              </Col>
            ))}
          </Row>
        </Card>
      ))}
    </div>
  )
}

export default RagWorkbenchPage
