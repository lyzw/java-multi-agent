import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { Button, Card, Form, InputNumber, Space, Tag, Typography } from 'antd'
import { PlusOutlined, ToolOutlined } from '@ant-design/icons'
import { ReadOnlyCatalogPage } from './ReadOnlyCatalogPage'
import { riskTag, fingerprintCell, lifecycleStateCell, timeCell } from './displayBits'
import { getToolCatalog } from '@/api/adminApi'
import type { ToolCatalogItem } from '@/api/types'
import { PlatformRegistrationModal } from './PlatformRegistrationModal'
import { ToolFormFields } from './ToolFormFields'
import { ToolFieldReference } from './ToolDefinitionView'
import { type ToolFormValues } from './platformObjectForm'
import { buildToolPublishRequest } from './platformPublishRequests'
import { useRegistrationModal } from './useRegistrationModal'

/** Tool 目录（P1 · 平台级；C34-3 起可进入详情改版 / 停用；C37-3 起「登记」走弹窗） */
export function ToolCatalogPage(): JSX.Element {
  const fetcher = useCallback(() => getToolCatalog(), [])
  const registration = useRegistrationModal()

  return (
    <>
      <ReadOnlyCatalogPage<ToolCatalogItem>
        title={<><ToolOutlined /> Tool 目录</>}
        notice="平台级 Tool 目录"
        noticeDesc="Tool 定义是平台级对象，全平台唯一。登记 / 改版 / 停用走平台级三步工作流（需 PLATFORM_ADMIN）。点「详情」查看关系与操作区，点「影响」查看谁引用了该 Tool。"
        cardExtra={
          <Button type="primary" icon={<PlusOutlined />} onClick={registration.openModal}>
            登记 Tool
          </Button>
        }
        fetcher={fetcher}
        rowKey={(r) => r.toolId}
        actions={(r) => (
          <Space size="small">
            <Link to={`/tools/${encodeURIComponent(r.toolId)}`}>详情</Link>
            <Link to={`/dependencies/impact?type=tool&id=${encodeURIComponent(r.toolId)}`}>影响</Link>
          </Space>
        )}
        columns={[
          { title: 'ID', dataIndex: 'toolId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
          { title: '名称', dataIndex: 'name', render: (v: string | null) => v ?? '-' },
          {
            title: '类型',
            dataIndex: 'kind',
            render: (v: string) => (
              <Tag color={v === 'MCP' ? 'purple' : v === 'HTTP' ? 'blue' : 'default'}>{v}</Tag>
            ),
          },
          { title: '风险等级', dataIndex: 'riskLevel', render: (v: string) => riskTag(v) },
          {
            title: 'MCP 绑定',
            dataIndex: 'mcpServerId',
            render: (v: string | null, r: ToolCatalogItem) =>
              v ? (
                <Typography.Text code>{v}::{r.mcpToolName ?? '?'}</Typography.Text>
              ) : r.kind === 'HTTP' ? (
                <Typography.Text type="secondary">远程 HTTP</Typography.Text>
              ) : (
                <Typography.Text type="secondary">内置</Typography.Text>
              ),
          },
          {
            // ⚠️ HTTP 工具的**目标**必须一眼可见：它是这个 Tool 的行为定义。
            title: 'HTTP 目标',
            dataIndex: 'httpUrl',
            render: (v: string | null | undefined, r: ToolCatalogItem) =>
              r.kind === 'HTTP' ? (
                <Typography.Text code ellipsis style={{ maxWidth: 240 }}>
                  {r.httpMethod ?? '?'} {v ?? '未提供'}
                </Typography.Text>
              ) : (
                <Typography.Text type="secondary">-</Typography.Text>
              ),
          },
          { title: '版本', dataIndex: 'version', render: (v: number) => <Tag color="blue">v{v}</Tag> },
          {
            // ⚠️ 「状态」列 = **生命周期状态 + 发布状态**合并（见 lifecycleStateCell）：
            //    只显示 published 会把「已停用（DEPRECATED）」的对象显示成「已发布」——
            //    停用是单向终态，已发布过的对象停用后 published 仍为 true。
            title: '状态',
            dataIndex: 'lifecycleState',
            render: (state: string | null, r: ToolCatalogItem) => lifecycleStateCell(state, r.published),
          },
          {
            title: '发布时间',
            dataIndex: 'publishedAt',
            render: (v: string | null) => timeCell(v),
          },
          {
            // ⚠️ 配置指纹是「这行的内容到底变没变」的**唯一客观依据**
            //    （比对 JSON 原文靠不住 —— 格式化一次就比不出了）。
            title: '配置指纹',
            dataIndex: 'configFingerprint',
            render: (v: string | null | undefined) => fingerprintCell(v),
          },
        ]}
      />

      {/* ⚠️ 登记与详情页**共用同一份字段组件**（ToolFormFields）——
          两处各写一份必然漂移，而漂移的方向通常是「弹窗那份少一个字段」。 */}
      <PlatformRegistrationModal<ToolFormValues & { reason: string; expectedPublishSeq?: number }>
        open={registration.open}
        title="登记 Tool（申请 → 审批 → 执行）"
        onClose={registration.closeModal}
        buildRequest={buildToolPublishRequest}
        renderFields={() => (
          <>
            {/* ⚠️ 登记时对象尚不存在，但作者仍需知道一个 Tool 的**完整字段集**
                （含哪些由平台生成）—— 否则只能照着猜填。 */}
            <Card
              size="small"
              title="Tool 字段全览（登记对象尚不存在时的完整字段清单）"
              style={{ marginBottom: 16 }}
            >
              <ToolFieldReference />
            </Card>
            <ToolFormFields isRegistration />
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

export default ToolCatalogPage
