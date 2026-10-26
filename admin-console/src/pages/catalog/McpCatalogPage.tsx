import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { Button, Form, InputNumber, Space, Tag, Typography } from 'antd'
import { ApiOutlined, PlusOutlined } from '@ant-design/icons'
import { ReadOnlyCatalogPage } from './ReadOnlyCatalogPage'
import { riskTag, allowlistCell, fingerprintCell, lifecycleStateCell, timeCell } from './displayBits'
import { getMcpCatalog } from '@/api/adminApi'
import type { McpCatalogItem } from '@/api/types'
import { PlatformRegistrationModal } from './PlatformRegistrationModal'
import { McpFormFields } from './McpFormFields'
import { type McpServerFormValues } from './platformObjectForm'
import { buildMcpPublishRequest } from './platformPublishRequests'
import { useRegistrationModal } from './useRegistrationModal'

/** MCP Server 目录（P1 · 平台级；C34-3 起可进入详情改版 / 停用；C37-3 起「登记」走弹窗） */
export function McpCatalogPage(): JSX.Element {
  const fetcher = useCallback(() => getMcpCatalog(), [])
  const registration = useRegistrationModal()
  return (
    <>
    <ReadOnlyCatalogPage<McpCatalogItem>
      title={<><ApiOutlined /> MCP 服务目录</>}
      notice="平台级 MCP Server 目录"
      noticeDesc="MCP Server 是平台级对象。连接测试 / 清单采集端点留待后续阶段前置；登记 / 改版 / 停用走平台级三步工作流（需 PLATFORM_ADMIN）。凭据仅存定位符，本目录不暴露任何凭据材料。"
      cardExtra={
        <Button type="primary" icon={<PlusOutlined />} onClick={registration.openModal}>
          登记 MCP Server
        </Button>
      }
      fetcher={fetcher}
      rowKey={(r) => r.serverId}
      actions={(r) => (
        <Space size="small">
          <Link to={`/mcp/${encodeURIComponent(r.serverId)}`}>详情</Link>
          <Link to={`/dependencies/impact?type=mcp_server&id=${encodeURIComponent(r.serverId)}`}>影响</Link>
        </Space>
      )}
      columns={[
        { title: 'ID', dataIndex: 'serverId', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
        {
          title: '传输',
          dataIndex: 'transport',
          render: (v: string) => <Tag color={v === 'HTTP' ? 'blue' : v === 'SSE' ? 'cyan' : 'default'}>{v}</Tag>,
        },
        {
          title: 'Endpoint',
          dataIndex: 'endpoint',
          render: (v: string | null) =>
            v ? (
              <Typography.Text code ellipsis style={{ maxWidth: 260 }}>{v}</Typography.Text>
            ) : (
              <Typography.Text type="secondary">（本地命令）</Typography.Text>
            ),
        },
        { title: '认证', dataIndex: 'authKind', render: (v: string) => <Tag>{v}</Tag> },
        {
          title: '信任分级',
          dataIndex: 'trustTier',
          render: (v: string) => <Tag color={v === 'INTERNAL' ? 'green' : 'orange'}>{v}</Tag>,
        },
        { title: '连接风险', dataIndex: 'connectionRisk', render: (v: string) => riskTag(v) },
        {
          title: '动态发现',
          dataIndex: 'dynamicDiscovery',
          render: (b: boolean) => (b ? <Tag color="red">开</Tag> : <Tag>关</Tag>),
        },
        { title: '暴露操作（null≠[]）', dataIndex: 'exposedToolAllowlist', render: (v: string[] | null) => allowlistCell(v) },
        {
          // ⚠️ 只回显**布尔**：目录接口**从不**回显凭据定位符与凭据材料
          //    （§4.2 / P5-T3）。这一列回答的是「这个 Server 登记过凭据引用没有」，
          //    而不是「凭据是什么」—— 没有凭据引用 ⇒ 远端认证无从谈起。
          title: '凭据引用',
          dataIndex: 'hasCredentialRef',
          render: (b: boolean) =>
            b ? (
              <Tag color="gold" title="仅表示「登记过凭据引用」；定位符与凭据材料永不入响应">
                已登记
              </Tag>
            ) : (
              <Tag>无</Tag>
            ),
        },
        { title: '版本', dataIndex: 'version', render: (v: number) => <Typography.Text>v{v}</Typography.Text> },
        {
          // ⚠️ 同 Tool / Skill 目录：生命周期与发布状态合并。
          title: '状态',
          dataIndex: 'lifecycleState',
          render: (state: string | null, r: McpCatalogItem) => lifecycleStateCell(state, r.published),
        },
        { title: '发布时间', dataIndex: 'publishedAt', render: (v: string | null) => timeCell(v) },
        {
          title: '配置指纹',
          dataIndex: 'configFingerprint',
          render: (v: string | null | undefined) => fingerprintCell(v),
        },
      ]}
    />

      {/* ⚠️ 登记与详情页**共用同一份字段组件**（McpFormFields）。 */}
      <PlatformRegistrationModal<McpServerFormValues & { reason: string; expectedPublishSeq?: number }>
        open={registration.open}
        title="登记 MCP Server（申请 → 审批 → 执行）"
        onClose={registration.closeModal}
        buildRequest={buildMcpPublishRequest}
        renderFields={() => (
          <>
            <McpFormFields isRegistration />
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

export default McpCatalogPage
