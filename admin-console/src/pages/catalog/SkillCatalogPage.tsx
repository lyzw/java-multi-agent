import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { Typography } from 'antd'
import { BulbOutlined } from '@ant-design/icons'
import { ReadOnlyCatalogPage } from './ReadOnlyCatalogPage'
import { riskTag, tagList } from './displayBits'
import { getSkillCatalog } from '@/api/adminApi'
import type { SkillCatalogItem, McpDeclaration } from '@/api/types'

/** Skill 目录（P1 · 平台级只读，复用 /api/cap/catalog/skills） */
export function SkillCatalogPage(): JSX.Element {
  const fetcher = useCallback(() => getSkillCatalog(), [])
  return (
    <ReadOnlyCatalogPage<SkillCatalogItem>
      title={<><BulbOutlined /> Skill 目录</>}
      notice="平台级 Skill 目录（只读）"
      noticeDesc="Skill 定义是平台级对象。其声明的工具 / MCP 关系与风险上限在下方呈现；变更须经带外运维通道。点「影响」查看谁引用了该 Skill。"
      fetcher={fetcher}
      rowKey={(r) => r.skillId}
      actions={(r) => (
        <Link to={`/dependencies/impact?type=skill&id=${encodeURIComponent(r.skillId)}`}>影响</Link>
      )}
      columns={[
        { title: 'ID', dataIndex: 'skillId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
        { title: '名称', dataIndex: 'name', render: (v: string | null) => v ?? '-' },
        { title: '风险上限', dataIndex: 'riskCeiling', render: (v: string) => riskTag(v) },
        { title: '声明工具', dataIndex: 'declaredTools', render: (v: string[]) => tagList(v) },
        {
          // ⚠️ null≠[]（§4.4）：toolAllowlist 为 null=未配置（全集），[]=显式空，不可归一化
          title: '声明 MCP（null≠[]）',
          dataIndex: 'declaredMcp',
          render: (list: McpDeclaration[]) =>
            list.length === 0 ? (
              <Typography.Text type="secondary">（无）</Typography.Text>
            ) : (
              list.map((m) => {
                const op = m.toolAllowlist === null ? '全集' : m.toolAllowlist.length === 0 ? '∅' : m.toolAllowlist.join('/')
                return (
                  <Typography.Text key={m.serverId} code style={{ marginRight: 8 }}>
                    {m.serverId}·{op}
                  </Typography.Text>
                )
              })
            ),
        },
        { title: '必需能力', dataIndex: 'requiredCaps', render: (v: string[]) => tagList(v, '（无）', 'geekblue') },
        { title: '版本', dataIndex: 'version', render: (v: number) => <Typography.Text>v{v}</Typography.Text> },
        {
          title: '状态',
          dataIndex: 'published',
          render: (p: boolean) => (p ? <Typography.Text type="success">已发布</Typography.Text> : <Typography.Text type="secondary">未发布</Typography.Text>),
        },
      ]}
    />
  )
}

export default SkillCatalogPage
