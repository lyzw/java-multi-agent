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

import { useState } from 'react'
import { Alert, Button, Card, Collapse, Form, Input, Select, Space, Table, Tag, Tooltip, Typography, Upload } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { UploadOutlined } from '@ant-design/icons'
import type {
  RagCarrier,
  RagSource,
  RagSourceQuery,
  RagTrustTier,
  RagVerificationStatus,
} from '@/api/ragTypes'
import { RAG_CARRIER_LABELS, RAG_VERIFICATION_LABELS, RAG_LICENSE_LABELS, RAG_INSPECTION_STATUS_LABELS } from '@/api/ragTypes'
import { isRagTargetReady, isRagWriteTarget } from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import type { RagSourceListState } from '@/features/rag/useRagManagement'
import { useDictOptions } from '@/features/dictionaries/useDictionaries'
import { computeSha256Hex } from '@/features/rag/fileHash'
import { TrustTierTag, VerificationTag } from './ragUi'

/**
 * 由文件名后缀推断载体类型（前端预览用，与后端 {@code LocalObjectStoreAdapter}
 * 的推断口径一致）。无法推断时返回 null——`SCAN` 只能显式选。
 */
function inferCarrierFromName(name: string): RagCarrier | null {
  const dot = name.lastIndexOf('.')
  if (dot < 0) return null
  const ext = name.slice(dot).toLowerCase()
  if (ext === '.pdf') return 'PDF'
  if (ext === '.epub') return 'EPUB'
  if (ext === '.html' || ext === '.htm') return 'HTML'
  if (ext === '.txt' || ext === '.md') return 'TXT'
  return null
}

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
        <Space size={6} align="center" wrap={false}>
          <Text strong>{title}</Text>
          {/* ⚠️ 0 载体 = 「仅元数据」登记：从未上传物理文件。不标出来，
              管理员会点进一个没有语料的来源白忙一场（2026-11-03 抽检实测）。
              undefined = 旧版后端未返回，按「未知」处理，不打标识。 */}
          {row.documentCount === 0 ? (
            <Tag color="warning" style={{ marginInlineEnd: 0 }}>
              仅元数据
            </Tag>
          ) : null}
        </Space>
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
    title: '抽检',
    dataIndex: 'inspectionStatus',
    key: 'inspectionStatus',
    width: 100,
    render: (s: RagSource['inspectionStatus']) => {
      // ⚠️ null = 后端本次未读取（或旧版后端），不是任何一种结论——渲染「—」，
      //    不把它当成「未抽检」误导审核员。
      if (!s) return <Text type="secondary">—</Text>
      return (
        <Tag color={s === 'PASSED' ? 'green' : s === 'FAILED' ? 'red' : 'default'}>
          {RAG_INSPECTION_STATUS_LABELS[s] ?? s}
        </Tag>
      )
    },
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
    /** 选中的物理文档（真实录入主路径）；null = 仅登记来源元数据。 */
    file: File | null
    /** 载体类型（可空 → 由后端按文件名后缀推断）。 */
    carrier: RagCarrier | null
    /** 前端算的 SHA-256 预览（仅展示，落库以服务端重算为准）。 */
    fileHash: string | null
  }) => void
}): JSX.Element {
  const [form] = Form.useForm()
  const { options: sourceTypeOptions } = useDictOptions('rag.source_type')

  /** 选中的物理文档与衍生状态（文件指纹预览 / 推断的载体类型）。 */
  const [file, setFile] = useState<File | null>(null)
  const [carrier, setCarrier] = useState<RagCarrier | null>(null)
  const [fileHash, setFileHash] = useState<string | null>(null)
  const [hashLoading, setHashLoading] = useState(false)

  const onPickFile = async (picked: File | null): Promise<void> => {
    setFile(picked)
    setFileHash(null)
    if (!picked) {
      // 取消选择：清空载体预选，回到「由后端推断」。
      setCarrier(null)
      return
    }
    // ⚠️ 后缀能推断时预选 carrier（用户仍可改；SCAN 只能手动选）。
    const inferred = inferCarrierFromName(picked.name)
    if (inferred) setCarrier(inferred)
    setHashLoading(true)
    try {
      // ⚠️ 失败（如非安全上下文 crypto.subtle 不可用）只意味着「不显示预览」，
      //    绝不阻断上传——落库哈希由服务端重算。
      setFileHash(await computeSha256Hex(picked))
    } catch {
      setFileHash(null)
    } finally {
      setHashLoading(false)
    }
  }

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
          file,
          carrier,
          fileHash,
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

      {/*
        ⚠️ 真实文档录入（主路径）：选文件即视为「把文档落进语料桶」，
        替代原本手填 objectRef 的降级路径。后端会落桶 + 重算 SHA-256，
        前端无需、也不应手填定位符。
      */}
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="推荐：直接上传文档，由后端落桶并校验"
        description="选择本地文件后，系统会自动把它存进语料桶、重算内容指纹，无需你手填对象引用。"
      />
      <Form.Item label="上传文档（物理载体）">
        <Space direction="vertical" style={{ width: '100%' }} size={4}>
          <Upload
            // ⚠️ beforeUpload 返回 false：阻止 antd 自动上传，统一在本次表单提交时随来源一起落库。
            beforeUpload={(f) => {
              void onPickFile(f as File)
              return false
            }}
            maxCount={1}
            showUploadList={{ showPreviewIcon: false, showDownloadIcon: false }}
            fileList={file ? [{ uid: '-1', name: file.name, status: 'done' }] : []}
            onRemove={() => {
              void onPickFile(null)
              return true
            }}
          >
            <Button icon={<UploadOutlined />}>选择文件（PDF / EPUB / HTML / TXT 等）</Button>
          </Upload>
          {file && (
            <Space direction="vertical" size={2} style={{ width: '100%' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                载体类型：
                <Select
                  size="small"
                  style={{ width: 170, marginLeft: 4 }}
                  placeholder="未指定（按文件名推断）"
                  value={carrier ?? undefined}
                  onChange={(v) => setCarrier(v)}
                  options={Object.entries(RAG_CARRIER_LABELS).map(([v, l]) => ({ value: v, label: l }))}
                />
              </Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                内容指纹（SHA-256，仅展示）：
                {hashLoading ? ' 计算中…' : fileHash ? ` ${fileHash.slice(0, 16)}…` : ' （暂不可计算）'}
              </Text>
            </Space>
          )}
        </Space>
      </Form.Item>

      {/*
        ⚠️ 手填 objectRef / contentHash 降级为「高级」折叠区：
        仅用于「文件已在对象存储里、只差一个定位符」的少数场景。
        绝大多数情况应走上面的「上传文档」。
      */}
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: 'advanced',
            label: '高级（手动定位符）',
            children: (
              <>
                <Form.Item label="对象引用（objectRef）" name="objectRef">
                  <Input placeholder="对象存储定位符，可留空；上传文档时由系统自动生成" />
                </Form.Item>
                <Form.Item label="内容哈希" name="contentHash">
                  <Input placeholder="可留空；上传文档时由系统计算" />
                </Form.Item>
                <Alert
                  type="warning"
                  showIcon
                  message="手动定位符用于复用已有对象存储文件"
                  description="若你已自行把文件放进对象存储，可在此粘贴定位符；否则请使用上方的「上传文档」。"
                />
              </>
            ),
          },
        ]}
      />

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="登记后一律为「待校验 · 未校验」"
        description="登记只是把语料纳入治理，不等于可检索。须经审核通过（指定权威等级）且版权放行后才可检索。"
      />
      <Button type="primary" htmlType="submit" loading={submitting} block>
        {file ? '提交登记并上传文档' : '提交登记'}
      </Button>
    </Form>
  )
}
