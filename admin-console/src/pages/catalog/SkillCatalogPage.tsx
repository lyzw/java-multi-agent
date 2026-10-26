import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { Button, Form, InputNumber, Space, Typography } from 'antd'
import { BulbOutlined, PlusOutlined } from '@ant-design/icons'
import { ReadOnlyCatalogPage } from './ReadOnlyCatalogPage'
import { riskTag, tagList, lifecycleStateCell, timeCell, skillPurposeCell } from './displayBits'
import { getSkillCatalog } from '@/api/adminApi'
import type { SkillCatalogItem, McpDeclaration } from '@/api/types'
import { PlatformRegistrationModal } from './PlatformRegistrationModal'
import { SkillFormFields } from './SkillFormFields'
import { type SkillFormValues } from './platformObjectForm'
import { buildSkillPublishRequest } from './platformPublishRequests'
import { useRegistrationModal } from './useRegistrationModal'

/** Skill 目录（P1 · 平台级；C34-3 起可进入详情改版 / 停用；C37-3 起「登记」走弹窗） */
export function SkillCatalogPage(): JSX.Element {
  const fetcher = useCallback(() => getSkillCatalog(), [])
  const registration = useRegistrationModal()
  return (
    <>
    <ReadOnlyCatalogPage<SkillCatalogItem>
      title={<><BulbOutlined /> Skill 目录</>}
      notice="平台级 Skill 目录"
      noticeDesc="Skill 定义是平台级对象。其声明的工具 / MCP 关系与风险上限在下方呈现；登记 / 改版 / 停用走平台级三步工作流（需 PLATFORM_ADMIN）。点「详情」查看关系与操作区。"
      cardExtra={
        <Button type="primary" icon={<PlusOutlined />} onClick={registration.openModal}>
          登记 Skill
        </Button>
      }
      fetcher={fetcher}
      rowKey={(r) => r.skillId}
      actions={(r) => (
        <Space size="small">
          <Link to={`/skills/${encodeURIComponent(r.skillId)}`}>详情</Link>
          <Link to={`/dependencies/impact?type=skill&id=${encodeURIComponent(r.skillId)}`}>影响</Link>
        </Space>
      )}
      columns={[
        { title: 'ID', dataIndex: 'skillId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
        { title: '名称', dataIndex: 'name', render: (v: string | null) => v ?? '-' },
        {
          // ⚠️ 两级描述：1级 purpose 目录扫一眼就该知道（过长省略 + tooltip 给完整两级）。
          title: '描述用途（1级）',
          dataIndex: 'description',
          render: (v: SkillCatalogItem['description']) => skillPurposeCell(v),
        },
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
          // ⚠️ 同 Tool 目录：生命周期与发布状态合并 —— 只看 published
          //    会把「已停用」的 Skill 显示成「已发布」。
          title: '状态',
          dataIndex: 'lifecycleState',
          render: (state: string | null, r: SkillCatalogItem) => lifecycleStateCell(state, r.published),
        },
        { title: '发布时间', dataIndex: 'publishedAt', render: (v: string | null) => timeCell(v) },
      ]}
    />

      {/* ⚠️ 登记与详情页**共用同一份字段组件**（SkillFormFields）。 */}
      <PlatformRegistrationModal<SkillFormValues & { reason: string; expectedPublishSeq?: number }>
        open={registration.open}
        title="登记 Skill（申请 → 审批 → 执行）"
        onClose={registration.closeModal}
        buildRequest={buildSkillPublishRequest}
        renderFields={() => (
          <>
            <SkillFormFields isRegistration />
            <Form.Item
              name="expectedPublishSeq"
              label="发布基线（expectedPublishSeq · 可选）"
              extra="填入你读到的当前发布序号；不一致时后端 409（防止覆盖别人改过的配置）"
            >
              <InputNumber min={0} precision={0} style={{ width: 200 }} />
            </Form.Item>
          </>
        )}
      />
    </>
  )
}

export default SkillCatalogPage
