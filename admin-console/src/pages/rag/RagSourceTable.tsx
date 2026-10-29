/**
 * 语料来源列表（RAG 管理 · 五态）。
 *
 * ⚠️ 治理纪律：
 *    · 五态必须可区分（idle / loading / empty / error / timeout），
 *      其中 `empty` 与 `error` 文案不同——「还没有语料」和「读不到语料」
 *      对审核员是两个完全不同的结论。
 *    · 不提供任何删除入口：撤回（REVOKED）是唯一的下架方式（INV-4-a）。
 *    · `stale` / `admissible` 直接渲染服务端返回值，前端不推算。
 */

import { Alert, Button, Card, Form, Input, Select, Space, Table, Tag, Tooltip, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type {
  RagSource,
  RagSourceQuery,
  RagTrustTier,
  RagVerificationStatus,
} from '@/api/ragTypes'
import { RAG_VERIFICATION_LABELS, RAG_LICENSE_LABELS } from '@/api/ragTypes'
import { isRagTargetReady, isRagWriteTarget } from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import type { RagSourceListState } from '@/features/rag/useRagManagement'
import { useDictOptions } from '@/features/dictionaries/useDictionaries'
import { TrustTierTag, VerificationTag } from './ragUi'

const { Text } = Typography

export interface RagSourceTableProps {
  state: RagSourceListState
  /** 当前作用域目标；租户档未选租户时所有会发请求的入口一律禁用（后端缺 X-Tenant-Id 直接 400）。 */
  target: RagTarget
  onQuery: (query: RagSourceQuery) => void
  onRefresh: () => void
  onSelect: (source: RagSource) => void
  onRegister: () => void
}

interface FilterShape {
  status: RagVerificationStatus | null
  trustTier: RagTrustTier | null
  keyword: string
  includeStale: boolean
}

const columns: ColumnsType<RagSource> = [
  {
    title: '标题',
    dataIndex: 'title',
    key: 'title',
    width: 260,
    render: (title: string, row) => (
      <Space direction="vertical" size={0}>
        <Text strong>{title}</Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {row.publisher || '—'} · {row.sourceType}
        </Text>
      </Space>
    ),
  },
  {
    title: '校验状态',
    dataIndex: 'verificationStatus',
    key: 'verificationStatus',
    width: 110,
    render: (_: unknown, row) => <VerificationTag status={row.verificationStatus} />,
  },
  {
    title: '权威等级',
    dataIndex: 'trustTier',
    key: 'trustTier',
    width: 170,
    render: (_: unknown, row) => <TrustTierTag tier={row.trustTier} />,
  },
  {
    title: '版权',
    dataIndex: 'licenseStatus',
    key: 'licenseStatus',
    width: 110,
    render: (s: RagSource['licenseStatus']) => (
      <Tag color={s === 'RESTRICTED' ? 'red' : s === 'LICENSED' ? 'green' : 'default'}>
        {RAG_LICENSE_LABELS[s] ?? s}
      </Tag>
    ),
  },
  {
    title: '准入',
    key: 'admissible',
    width: 150,
    render: (_: unknown, row) => (
      <Space size={4}>
        {/* ⚠️ 服务端计算属性：过期与准入分开呈现，不合并成一个「是否可用」 */}
        {row.stale ? <Tag color="orange">结论已过期</Tag> : null}
        <Tag color={row.admissible ? 'green' : 'default'}>
          {row.admissible ? '可检索' : '不可检索'}
        </Tag>
      </Space>
    ),
  },
  {
    title: '校验依据',
    dataIndex: 'verificationEvidence',
    key: 'verificationEvidence',
    ellipsis: true,
    render: (v: string | null) => (v ? v : <Text type="secondary">—</Text>),
  },
]

export function RagSourceTable({
  state,
  target,
  onQuery,
  onRefresh,
  onSelect,
  onRegister,
}: RagSourceTableProps): JSX.Element {
  const [form] = Form.useForm<FilterShape>()
  // ⚠️ 校验状态是**状态机**取值（代码强绑定），刻意不走字典；权威等级走字典表
  const { options: tierOptions } = useDictOptions('rag.trust_tier')

  const submit = (values: FilterShape): void => {
    onQuery({
      status: values.status ?? null,
      trustTier: values.trustTier ?? null,
      keyword: values.keyword?.trim() || null,
      includeStale: values.includeStale === true,
      limit: 100,
    })
  }

  /**
   * 未选租户时把**会发请求的入口**全部禁用。
   *
   * ⚠️ 只写一行说明文字、按钮照旧可点，等于让管理员点一次就吃一个 400；
   *    禁用 + tooltip 才把「为什么不能点」说清楚，也不会有人把它当成故障上报。
   */
  const ready = isRagTargetReady(target)
  /**
   * 写入口（登记）在平台全局档要求**治理票据**。
   *
   * ⚠️ 不是「平台档不能写」——第二刀起能写，只是登录令牌不够格：
   *    后端写闸门除平台域身份外还校验票据的 `rag_scope`，缺票是 403。
   *    前端据此在没票时不给按钮，而不是让人点一次吃一个「需要治理票据」。
   */
  const writable = isRagWriteTarget(target)
  const blockedTip =
    target.scope === 'TENANT'
      ? '请先在上方选择租户'
      : '平台全局语料要先领取治理票据才能写：登录令牌只证明你进了控制台，不证明你被授权改动所有租户共享的那批语料'

  return (
    <Card
      title="语料来源"
      extra={
        <Space>
          <Tooltip title={ready ? '' : blockedTip}>
            <Button
              onClick={onRefresh}
              disabled={!ready || state.status === 'loading'}
            >
              刷新
            </Button>
          </Tooltip>
          <Tooltip title={writable ? '' : blockedTip}>
            <Button type="primary" onClick={onRegister} disabled={!writable}>
              登记来源
            </Button>
          </Tooltip>
        </Space>
      }
    >
      <Form<FilterShape>
        form={form}
        layout="inline"
        initialValues={{ status: null, trustTier: null, keyword: '', includeStale: true }}
        onFinish={submit}
        style={{ marginBottom: 16, rowGap: 8 }}
      >
        <Form.Item name="status" label="状态">
          <Select
            allowClear
            style={{ width: 140 }}
            placeholder="全部"
            options={Object.entries(RAG_VERIFICATION_LABELS).map(([v, l]) => ({
              value: v,
              label: l,
            }))}
          />
        </Form.Item>
        <Form.Item name="trustTier" label="权威等级">
          <Select allowClear style={{ width: 190 }} placeholder="全部" options={tierOptions} />
        </Form.Item>
        <Form.Item name="keyword" label="关键词">
          <Input placeholder="标题 / 出版社 / ISBN" style={{ width: 200 }} />
        </Form.Item>
        <Form.Item name="includeStale" label="含过期">
          <Select
            style={{ width: 90 }}
            options={[
              { value: true, label: '是' },
              { value: false, label: '否' },
            ]}
          />
        </Form.Item>
        <Form.Item>
          <Space>
            <Tooltip title={ready ? '' : blockedTip}>
              <Button
                type="primary"
                htmlType="submit"
                disabled={!ready}
                loading={state.status === 'loading'}
              >
                查询
              </Button>
            </Tooltip>
            <Button
              disabled={!ready}
              onClick={() => {
                form.resetFields()
                onQuery({ includeStale: true, limit: 100 })
              }}
            >
              重置
            </Button>
          </Space>
        </Form.Item>
      </Form>

      {state.status === 'loading' && <Table loading dataSource={[]} columns={columns} rowKey="sourceId" />}

      {state.status === 'empty' && (
        <Alert
          type="info"
          showIcon
          message={target.scope === 'PLATFORM' ? '全局语料桶还没有来源' : '本租户还没有语料来源'}
          description="登记来源后一律进入「待校验」，须经审核通过且未过期才会真正可检索。"
        />
      )}

      {state.status === 'error' && (
        <Alert
          type="error"
          showIcon
          message="读取语料来源失败"
          description={state.error ?? '未知错误'}
          action={
            <Button size="small" onClick={onRefresh}>
              重试
            </Button>
          }
        />
      )}

      {state.status === 'timeout' && (
        <Alert
          type="warning"
          showIcon
          message="读取超时"
          description={state.error ?? '后端未在规定时间内返回'}
          action={
            <Button size="small" onClick={onRefresh}>
              重试
            </Button>
          }
        />
      )}

      {state.status === 'success' && (
        <Table<RagSource>
          rowKey="sourceId"
          columns={columns}
          dataSource={state.sources}
          pagination={{ pageSize: 10, showSizeChanger: true }}
          onRow={(row) => ({ onClick: () => onSelect(row), style: { cursor: 'pointer' } })}
        />
      )}
    </Card>
  )
}

/** 来源登记表单（单条）。 */
export function RagRegisterForm({
  submitting,
  registeredBy,
  onSubmit,
}: {
  submitting: boolean
  registeredBy: string
  onSubmit: (payload: {
    sourceType: string
    title: string
    publisher: string
    isbn: string | null
    doi: string | null
    objectRef: string | null
    contentHash: string | null
    registeredBy: string
  }) => void
}): JSX.Element {
  const [form] = Form.useForm()
  const { options: sourceTypeOptions } = useDictOptions('rag.source_type')

  return (
    <Form
      form={form}
      layout="vertical"
      initialValues={{ sourceType: 'TEXTBOOK', registeredBy }}
      onFinish={(values) =>
        onSubmit({
          sourceType: values.sourceType,
          title: String(values.title ?? '').trim(),
          publisher: String(values.publisher ?? '').trim(),
          isbn: values.isbn ? String(values.isbn).trim() : null,
          doi: values.doi ? String(values.doi).trim() : null,
          objectRef: values.objectRef ? String(values.objectRef).trim() : null,
          contentHash: values.contentHash ? String(values.contentHash).trim() : null,
          registeredBy,
        })
      }
    >
      <Form.Item label="来源类型" name="sourceType" rules={[{ required: true }]}>
        <Select options={sourceTypeOptions} />
      </Form.Item>
      <Form.Item
        label="标题"
        name="title"
        rules={[{ required: true, message: '请填写标题' }]}
      >
        <Input placeholder="如：人教版数学七年级上册" />
      </Form.Item>
      <Form.Item label="出版社" name="publisher" rules={[{ required: true, message: '请填写出版社' }]}>
        <Input />
      </Form.Item>
      <Space size="large" style={{ display: 'flex' }} align="start">
        <Form.Item label="ISBN" name="isbn" style={{ flex: 1 }}>
          <Input placeholder="可留空" />
        </Form.Item>
        <Form.Item label="DOI" name="doi" style={{ flex: 1 }}>
          <Input placeholder="论文填 DOI" />
        </Form.Item>
      </Space>
      <Form.Item label="对象引用（objectRef）" name="objectRef">
        <Input placeholder="对象存储定位符，可留空" />
      </Form.Item>
      <Form.Item label="内容哈希" name="contentHash">
        <Input placeholder="可留空；填写后可用于官方哈希比对" />
      </Form.Item>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="登记后一律为「待校验 · 未校验」"
        description="登记只是把语料纳入治理，不等于可检索。须经审核通过（指定权威等级）且版权放行后才可检索。"
      />
      <Button type="primary" htmlType="submit" loading={submitting} block>
        提交登记
      </Button>
    </Form>
  )
}
