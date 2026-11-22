import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Col, Row, Space, Tag, Typography } from 'antd'
import type { ReactNode } from 'react'
import { NAV_GROUPS, type NavNode } from '@/layout/navConfig'

const { Title, Paragraph, Text } = Typography

interface ConfigEntry {
  key: string
  title: string
  path: string
  description: string
  icon: ReactNode
  tag: string
  tagColor: string
}

interface ConfigSection {
  key: string
  title: string
  description: string
  entries: ConfigEntry[]
}

/**
 * 配置管理总览（/config）。
 *
 * <p>⚠️ 路径/标题/图标/分组一律从 NAV_GROUPS 派生（与 RAG 工作台同构），
 * 本页只保留「面向配置场景的说明与风险标签」。此前这里硬编码第二份路径清单，
 * 发布策略归位到发布域后就会出现「导航有、索引没有」的静默漂移。
 *
 * <p>⚠️ 只做导航与口径说明，不重复实现任何写入口——真正的表单仍留在各自页面。
 */
const ENTRY_META: Record<string, { tag: string; tagColor: string; description?: string }> = {
  '/dictionaries': { tag: '立即生效', tagColor: 'green', description: '统一维护前端枚举数据源；内置字典禁止物理删除，仅可停用。' },
  '/model-catalog': { tag: '写需 PLATFORM_ADMIN', tagColor: 'blue', description: '模型定义、上下文窗口、能力开关与供应商密钥（密钥只写不回显）。' },
  '/config/credentials': { tag: '只读 / 带外', tagColor: 'default', description: '只查看 MCP Server 是否已登记凭据引用；登记与轮换走带外运维通道。' },
  '/publish/policies': { tag: '高风险', tagColor: 'red', description: '三层策略（全局 / 资源类型 / 单实例）决定 DIRECT / MANUAL / APPROVAL_* 分流。' },
  '/routing-policies': { tag: '试跑可验证', tagColor: 'cyan', description: '登记并试跑有界规则；试跑走后端同一引擎，不另写一份求值器。' },
  '/chat-audit-config': { tag: '含敏感数据', tagColor: 'orange', description: '端到端录制范围、采样、脱敏与保留期；保存后下一个请求即生效。' },
  '/rag/reviewers': { tag: 'RAG 工作台', tagColor: 'geekblue', description: '维护审核员与复核权限，显式暴露职责分离冲突。' },
  '/rag/kp-alias': { tag: 'RAG 工作台', tagColor: 'geekblue', description: '把口语化问法确定性映射到规范知识点。' },
  '/rag/dictionaries': { tag: 'RAG 工作台', tagColor: 'geekblue', description: '来源类型等治理下拉枚举；多数为派生只读。' },
  '/publish': { tag: '工作流', tagColor: 'purple', description: '申请队列与当前发布指针（CAS 基线）。' },
  '/publish/drafts': { tag: '工作流', tagColor: 'purple', description: '草稿到发布失败的双轨状态跟踪。' },
}

function nodesOf(groupId: string, predicate: (node: NavNode) => boolean = () => true): NavNode[] {
  return NAV_GROUPS.find((g) => g.key === groupId)?.children.filter(predicate) ?? []
}

function entryOf(node: NavNode): ConfigEntry {
  const meta = ENTRY_META[node.path] ?? { tag: '配置入口', tagColor: 'default' }
  return {
    key: node.key,
    title: node.label,
    path: node.path,
    description: meta.description ?? node.description,
    icon: node.icon,
    tag: meta.tag,
    tagColor: meta.tagColor,
  }
}

const SECTIONS: ConfigSection[] = [
  {
    key: 'foundation',
    title: '基础配置',
    description: '平台级枚举与模型定义，影响全平台的下拉选项和模型调用。',
    entries: nodesOf('config-foundation', (n) => n.path !== '/config').map(entryOf),
  },
  {
    key: 'policies',
    title: '策略与审计配置',
    description: '控制发布流程、路由选择与审计录制；改动前先确认影响面。',
    entries: [
      ...nodesOf('config-policies'),
      ...nodesOf('ops-observability', (n) => n.path === '/publish/policies'),
    ].map(entryOf),
  },
  {
    key: 'rag-rules',
    title: 'RAG 规则配置',
    description: '语料治理域内的规则维护；入口已收口到「RAG 工作台」的「规则配置」分组，这里只做索引。',
    entries: nodesOf('rag-workbench', (n) => n.section === '规则配置').map(entryOf),
  },
  {
    key: 'workflow',
    title: '配置变更的后续动作',
    description: '配置提交后在此查看发布进度与历史；这些不是参数本身，不在此页重复实现。',
    entries: nodesOf(
      'ops-observability',
      (n) => n.path === '/publish' || n.path === '/publish/drafts',
    ).map(entryOf),
  },
]

/** 配置中心首页：只做索引、分组与参数口径说明，不承载任何写入表单。 */
export function ConfigCenterPage(): JSX.Element {
  const navigate = useNavigate()

  return (
    <div style={{ maxWidth: 1240 }}>
      <Title level={4}>配置管理总览</Title>
      <Paragraph type="secondary">
        平台配置按「基础配置 / 策略与审计 / RAG 规则 / 变更工作流」归类。
        本页只提供入口与填写口径；实际读写仍在各配置页完成，避免两处表单漂移。
      </Paragraph>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="参数填写统一约定"
        description={
          <Space direction="vertical" size={2}>
            <Text>· 参数名一律与后端字段路径一致（如 `sampling.rate`），不在前端另起别名。</Text>
            <Text>· 数值字段统一展示范围、步长与单位；边界以服务端校验为准，前端提示不替代门禁。</Text>
            <Text>· 标记为「高风险 / 含敏感数据」的参数改动前需确认影响面；凭据材料与定位符永不回显。</Text>
            <Text>· 「立即生效」指保存后下一个请求生效；发布策略等需走申请 → 手动执行才写入配置。</Text>
          </Space>
        }
      />

      {SECTIONS.map((section) => (
        <Card
          key={section.key}
          size="small"
          title={section.title}
          style={{ marginBottom: 16 }}
          extra={<Text type="secondary">{section.description}</Text>}
        >
          <Row gutter={[12, 12]}>
            {section.entries.map((entry) => (
              <Col key={entry.key} xs={24} sm={12} lg={8}>
                <Card size="small" variant="borderless" style={{ height: '100%', background: '#fafafa' }}>
                  <Space direction="vertical" size={8} style={{ width: '100%' }}>
                    <Space align="center">
                      {entry.icon}
                      <Text strong>{entry.title}</Text>
                      <Tag color={entry.tagColor}>{entry.tag}</Tag>
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
                      进入配置 →
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

export default ConfigCenterPage
