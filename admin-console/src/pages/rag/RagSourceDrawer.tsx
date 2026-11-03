/**
 * 语料来源详情抽屉（校验流转 + 载体列表 + 载体级校验）。
 *
 * ⚠️ 治理纪律：
 *    · 流转按钮只渲染 `allowedVerificationTargets` 的合法目标（RAG-D07 T-1～T-12），
 *      让用户点一个注定被 409 的操作是产品缺陷。
 *    · 409 单独成态，文案指向「合法下一步」，**绝不**建议刷新重试。
 *    · 无删除入口：撤回是终态且不可复活，重新准入必须新建来源。
 *    · 载体不能超过来源（来源未通过则载体不得先通过）——后端校验，前端如实呈现后端拒绝。
 */

import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
  Upload,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { UploadOutlined } from '@ant-design/icons'
import {
  applyRagDocumentVerification,
  listRagChunks,
  listRagDocuments,
  isRagTargetReady,
  isRagWriteTarget,
  uploadRagDocument,
  ApiError,
} from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import type {
  RagCarrier,
  RagChunk,
  RagDocument,
  RagSource,
  RagVerificationRequest,
} from '@/api/ragTypes'
import {
  RAG_CARRIER_LABELS,
  RAG_CHUNK_ROLE_LABELS,
  RAG_PARSE_STATUS_LABELS,
  RAG_VERIFICATION_LABELS,
} from '@/api/ragTypes'
import { allowedVerificationTargets } from '@/features/rag/useRagManagement'
import { currentPrincipalId, TrustTierTag, VerificationTag } from './ragUi'
import { RagVerificationForm } from './RagVerificationForm'

const { Text } = Typography

export interface RagSourceDrawerProps {
  source: RagSource | null
  /** 当前作用域目标（平台全局档下载体流转只读） */
  target: RagTarget
  onClose: () => void
  onVerified: (sourceId: string, payload: RagVerificationRequest) => void
  actionStatus: string
  actionMessage: string | null
  /**
   * 最近一次流转成功后的**最新来源快照**（来自 hook 的 `action.updated`）。
   *
   * ⚠️ 抽屉此前只渲染父组件传入的 `source`，而流转成功后父组件仍是列表里的旧行，
   *    于是「已生效」的 toast 与「仍是待校验」的表单同时出现在屏幕上（实测踩到）——
   *    管理员会重复提交，而后端只会回 409。
   */
  updatedSource?: RagSource | null
}

type DocsState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'empty' }
  | { kind: 'ok'; docs: RagDocument[] }
  | { kind: 'error'; message: string }

export function RagSourceDrawer({
  source,
  target,
  onClose,
  onVerified,
  actionStatus,
  actionMessage,
  updatedSource = null,
}: RagSourceDrawerProps): JSX.Element {
  /**
   * 抽屉展示的来源：优先用「最近一次流转成功后的快照」。
   *
   * ⚠️ 只在 sourceId 一致时才覆盖：列表可能已经切到别的行，
   *    拿上一行的 updated 去渲染会把两张卡片混在一起。
   */
  const shownSource = useMemo(() => {
    if (source && updatedSource && updatedSource.sourceId === source.sourceId) {
      return updatedSource
    }
    return source
  }, [source, updatedSource])

  const [docs, setDocs] = useState<DocsState>({ kind: 'idle' })
  const [chunks, setChunks] = useState<RagChunk[] | null>(null)
  const [chunksLoading, setChunksLoading] = useState(false)
  const [activeDoc, setActiveDoc] = useState<RagDocument | null>(null)
  const [docError, setDocError] = useState<string | null>(null)
  /** 载体级校验的提交态与成功提示（与来源级 `action*` 分开，避免两套流转互相盖掉）。 */
  const [docSubmitting, setDocSubmitting] = useState(false)
  const [docNotice, setDocNotice] = useState<string | null>(null)

  /** 载体页真实文档上传（Model B）：选文件 → 上传落桶，不手填 objectRef。 */
  const { message } = App.useApp()
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [uploadCarrier, setUploadCarrier] = useState<RagCarrier | null>(null)
  const [uploading, setUploading] = useState(false)

  const sourceId = source?.sourceId ?? null

  /**
   * 平台全局档写要治理票据（第二刀）：没有票就是只读视图。
   *
   * ⚠️ 在这里收口成**一个**旗标，让「来源流转」「载体流转」「载体列表的校验按钮」
   *    三处入口一起消失。若只禁一处，管理员会在另外两处点到 403，
   *    而后端的 403 文案讲的是授权模型，不像「这个按钮不该出现」。
   */
  const writable = isRagWriteTarget(target)

  useEffect(() => {
    if (!sourceId || !isRagTargetReady(target)) return
    let cancelled = false
    setDocs({ kind: 'loading' })
    setChunks(null)
    setActiveDoc(null)
    setDocError(null)
    const run = async (): Promise<void> => {
      try {
        const list = await listRagDocuments(target, sourceId)
        if (cancelled) return
        setDocs(list.length === 0 ? { kind: 'empty' } : { kind: 'ok', docs: list })
      } catch (cause) {
        if (cancelled) return
        setDocs({
          kind: 'error',
          message: cause instanceof ApiError ? cause.message : '读取载体失败',
        })
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [sourceId, target])

  const openChunks = async (doc: RagDocument): Promise<void> => {
    setActiveDoc(doc)
    setChunksLoading(true)
    setChunks(null)
    try {
      const list = await listRagChunks(target, doc.documentId, 100)
      setChunks(list)
    } catch (cause) {
      setDocError(cause instanceof ApiError ? cause.message : '读取片段失败')
    } finally {
      setChunksLoading(false)
    }
  }

  const uploadDocument = async (): Promise<void> => {
    if (!uploadFile || !sourceId) return
    setUploading(true)
    setDocError(null)
    try {
      // ⚠️ 真实文件录入（区别于手填 objectRef）：由后端落桶 + 重算 SHA-256。
      //    载体恒为「待解析 · 待校验 · 不可检索」，须经摄入与审核流程方可检索。
      await uploadRagDocument(target, sourceId, uploadFile, {
        carrier: uploadCarrier ?? undefined,
        registeredBy: currentPrincipalId() ?? undefined,
      })
      setUploadFile(null)
      setUploadCarrier(null)
      const list = await listRagDocuments(target, sourceId)
      setDocs(list.length === 0 ? { kind: 'empty' } : { kind: 'ok', docs: list })
      message.success('文档已提交入库（待解析 / 校验后才可检索）')
    } catch (cause) {
      setDocError(cause instanceof ApiError ? cause.message : '上传失败')
    } finally {
      setUploading(false)
    }
  }

  const verifyDoc = async (doc: RagDocument, payload: RagVerificationRequest): Promise<void> => {
    setDocError(null)
    setDocNotice(null)
    setDocSubmitting(true)
    try {
      await applyRagDocumentVerification(target, doc.documentId, payload)
      // 就地刷新载体列表
      const list = await listRagDocuments(target, doc.sourceId)
      setDocs(list.length === 0 ? { kind: 'empty' } : { kind: 'ok', docs: list })
      // ⚠️ 成功也要出声：原先只清空错误，界面看不出「点过了没有」，
      //    审核员会重复提交同一个流转（第二次多半吃一个 409）。
      setDocNotice(
        `载体「${doc.title}」已流转到「${RAG_VERIFICATION_LABELS[payload.targetStatus] ?? payload.targetStatus}」。`,
      )
    } catch (cause) {
      setDocError(
        cause instanceof ApiError && cause.status === 409
          ? '该载体的这一步流转不被允许（载体不得超过来源的校验状态；撤回是终态）。'
          : cause instanceof ApiError
            ? cause.message
            : '载体校验失败',
      )
    } finally {
      setDocSubmitting(false)
    }
  }

  const docColumns: ColumnsType<RagDocument> = [
    {
      title: '标题',
      dataIndex: 'title',
      render: (t: string, row) => (
        <Space direction="vertical" size={0}>
          <Text strong>{t}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {row.carrier} · 片段 {row.chunkCount}
          </Text>
        </Space>
      ),
    },
    {
      title: '解析状态',
      dataIndex: 'parseStatus',
      width: 110,
      render: (s: string) => (
        <Tag color={s === 'FAILED' ? 'red' : s === 'PENDING' ? 'orange' : 'blue'}>
          {RAG_PARSE_STATUS_LABELS[s] ?? s}
        </Tag>
      ),
    },
    {
      title: '载体校验',
      key: 'verificationStatus',
      width: 180,
      render: (_: unknown, row) => (
        <Space size={4}>
          {row.verificationStatus ? <VerificationTag status={row.verificationStatus} /> : <Text type="secondary">—</Text>}
          {row.retrievable === false && <Tag color="red">已下架</Tag>}
        </Space>
      ),
    },
    {
      title: '操作',
      key: 'ops',
      width: 160,
      render: (_: unknown, row) => (
        <Space>
          <Button size="small" onClick={() => void openChunks(row)}>
            片段
          </Button>
          {writable && row.verificationStatus && allowedVerificationTargets(row.verificationStatus).length > 0 ? (
            <Button
              size="small"
              type="link"
              onClick={() => {
                setActiveDoc(row)
                setChunks(null)
              }}
            >
              校验
            </Button>
          ) : null}
        </Space>
      ),
    },
  ]

  const chunkColumns: ColumnsType<RagChunk> = [
    { title: '角色', dataIndex: 'role', width: 130, render: (r: string) => RAG_CHUNK_ROLE_LABELS[r] ?? r },
    { title: '页码', dataIndex: 'pageNumber', width: 80 },
    { title: '章节路径', dataIndex: 'sectionPath', ellipsis: true },
    { title: '字数', dataIndex: 'charLength', width: 90 },
    {
      title: '向量',
      dataIndex: 'hasEmbedding',
      width: 100,
      render: (v: boolean) => (v ? <Tag color="green">已写入</Tag> : <Tag color="red">缺失</Tag>),
    },
  ]

  return (
    <Drawer
      width={860}
      open={source !== null}
      onClose={onClose}
      title={shownSource ? `语料来源 · ${shownSource.title}` : '语料来源'}
      destroyOnHidden
    >
      {shownSource && (
        <Tabs
          defaultActiveKey="detail"
          items={[
            {
              key: 'detail',
              label: '来源详情',
              children: (
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <Descriptions bordered column={2} size="small">
                    <Descriptions.Item label="来源 ID">
                      <Text code copyable>
                        {shownSource.sourceId}
                      </Text>
                    </Descriptions.Item>
                    <Descriptions.Item label="类型">{shownSource.sourceType}</Descriptions.Item>
                    <Descriptions.Item label="出版社">{shownSource.publisher || '—'}</Descriptions.Item>
                    <Descriptions.Item label="权威等级">
                      <TrustTierTag tier={shownSource.trustTier} />
                    </Descriptions.Item>
                    <Descriptions.Item label="校验状态">
                      <Space>
                        <VerificationTag status={shownSource.verificationStatus} />
                        {shownSource.stale && <Tag color="orange">结论已过期</Tag>}
                        <Tag color={shownSource.admissible ? 'green' : 'default'}>
                          {shownSource.admissible ? '可检索' : '不可检索'}
                        </Tag>
                      </Space>
                    </Descriptions.Item>
                    <Descriptions.Item label="ISBN / DOI">
                      {shownSource.isbn || shownSource.doi || '—'}
                    </Descriptions.Item>
                    <Descriptions.Item label="校验依据" span={2}>
                      {shownSource.verificationEvidence || '—'}
                    </Descriptions.Item>
                    <Descriptions.Item label="审核人 / 时间">
                      {shownSource.verifiedBy ? (
                        <>
                          <Text code>{shownSource.verifiedBy}</Text> · {shownSource.verifiedAt}
                        </>
                      ) : (
                        '—'
                      )}
                    </Descriptions.Item>
                    <Descriptions.Item label="有效期">{shownSource.validDays} 天</Descriptions.Item>
                  </Descriptions>

                  {!writable ? (
                    <Alert
                      type="info"
                      showIcon
                      message={
                        target.scope === 'PLATFORM'
                          ? '平台全局语料：未领取治理票据，本档只读'
                          : '未选择租户，本档只读'
                      }
                      description={
                        target.scope === 'PLATFORM'
                          ? '全局语料影响所有租户的检索结果，改动它要一张短时治理票据（在页面上方「作用域」卡片里领取）。本页此刻只查状态、载体与片段。'
                          : '租户档的管理端点要求 X-Tenant-Id；请先在上方选择租户。'
                      }
                    />
                  ) : allowedVerificationTargets(shownSource.verificationStatus).length === 0 ? (
                    <Alert
                      type="warning"
                      showIcon
                      message="已撤回（终态）"
                      description="撤回不可复活。若需重新准入，请登记一条新的语料来源并重新走审核流程。"
                    />
                  ) : (
                    <>
                      <Typography.Title level={5} style={{ marginBottom: 0 }}>
                        校验流转
                      </Typography.Title>
                      <RagVerificationForm
                        currentStatus={shownSource.verificationStatus}
                        submitting={actionStatus === 'submitting'}
                        onSubmitted={(payload) => onVerified(shownSource.sourceId, payload)}
                      />
                    </>
                  )}

                  {actionStatus === 'conflict' && (
                    <Alert type="warning" showIcon message="流转被拒绝（治理规则）" description={actionMessage} />
                  )}
                  {actionStatus === 'error' && (
                    <Alert type="error" showIcon message="流转失败" description={actionMessage} />
                  )}
                  {actionStatus === 'timeout' && (
                    <Alert type="warning" showIcon message="流转超时" description={actionMessage} />
                  )}
                  {actionStatus === 'success' && (
                    <Alert type="success" showIcon message="流转已生效" />
                  )}
                </Space>
              ),
            },
            {
              key: 'docs',
              label: '载体',
              children: (
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  {docs.kind === 'loading' && <Spin />}
                  {docs.kind === 'empty' && <Empty description="该来源还没有文档载体" />}
                  {docs.kind === 'error' && <Alert type="error" showIcon message={docs.message} />}
                  {docs.kind === 'ok' && (
                    <Table<RagDocument>
                      rowKey="documentId"
                      size="small"
                      columns={docColumns}
                      dataSource={docs.docs}
                      pagination={false}
                    />
                  )}

                  {writable && (
                    <Space direction="vertical" size={6} style={{ width: '100%', marginTop: 8 }}>
                      <Typography.Title level={5} style={{ marginBottom: 0 }}>
                        上传文档载体（真实文件录入）
                      </Typography.Title>
                      <Upload
                        // ⚠️ 阻止 antd 自动上传：统一在点击「上传到本来源」时提交。
                        beforeUpload={(f) => {
                          setUploadFile(f as File)
                          return false
                        }}
                        maxCount={1}
                        showUploadList={{ showPreviewIcon: false, showDownloadIcon: false }}
                        fileList={uploadFile ? [{ uid: '-1', name: uploadFile.name, status: 'done' }] : []}
                        onRemove={() => {
                          setUploadFile(null)
                          return true
                        }}
                      >
                        <Button icon={<UploadOutlined />}>选择文件（PDF / EPUB / HTML / TXT 等）</Button>
                      </Upload>
                      <Space>
                        <Select
                          style={{ width: 200 }}
                          placeholder="载体类型（留空按文件名推断）"
                          value={uploadCarrier ?? undefined}
                          onChange={(v) => setUploadCarrier(v)}
                          options={Object.entries(RAG_CARRIER_LABELS).map(([v, l]) => ({ value: v, label: l }))}
                        />
                        <Button
                          type="primary"
                          loading={uploading}
                          disabled={!uploadFile}
                          onClick={() => void uploadDocument()}
                        >
                          上传到本来源
                        </Button>
                      </Space>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        上传后文档进入「待解析 · 待校验 · 不可检索」；须经摄入（Docling + BGE-M3）与审核后方可检索。
                      </Text>
                    </Space>
                  )}

                  {docError && <Alert type="error" showIcon message={docError} />}
                  {docNotice && <Alert type="success" showIcon message={docNotice} />}

                  {chunksLoading && <Spin />}
                  {chunks && (
                    <Table<RagChunk>
                      rowKey="chunkId"
                      size="small"
                      title={() => `片段 · ${activeDoc?.title ?? ''}`}
                      columns={chunkColumns}
                      dataSource={chunks}
                      pagination={{ pageSize: 8 }}
                    />
                  )}

                  {writable &&
                    activeDoc?.verificationStatus &&
                    allowedVerificationTargets(activeDoc.verificationStatus).length > 0 && (
                      <>
                        <Typography.Title level={5} style={{ marginBottom: 0 }}>
                          载体级校验 · {activeDoc.title}
                        </Typography.Title>
                        <RagVerificationForm
                          currentStatus={activeDoc.verificationStatus}
                          submitting={docSubmitting}
                          onSubmitted={(payload) => void verifyDoc(activeDoc, payload)}
                        />
                      </>
                    )}
                </Space>
              ),
            },
          ]}
        />
      )}
      <Text type="secondary">决定人（留痕）：{currentPrincipalId() || '（无 principalId）'}</Text>
    </Drawer>
  )
}
