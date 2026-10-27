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

import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  applyRagDocumentVerification,
  listRagChunks,
  listRagDocuments,
  ApiError,
} from '@/api/ragClient'
import type {
  RagChunk,
  RagDocument,
  RagSource,
  RagVerificationRequest,
} from '@/api/ragTypes'
import { RAG_CHUNK_ROLE_LABELS, RAG_PARSE_STATUS_LABELS, RAG_VERIFICATION_LABELS } from '@/api/ragTypes'
import { allowedVerificationTargets } from '@/features/rag/useRagManagement'
import { currentPrincipalId, TrustTierTag, VerificationTag } from './ragUi'
import { RagVerificationForm } from './RagVerificationForm'

const { Text } = Typography

export interface RagSourceDrawerProps {
  source: RagSource | null
  tenantId: string
  onClose: () => void
  onVerified: (sourceId: string, payload: RagVerificationRequest) => void
  actionStatus: string
  actionMessage: string | null
}

type DocsState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'empty' }
  | { kind: 'ok'; docs: RagDocument[] }
  | { kind: 'error'; message: string }

export function RagSourceDrawer({
  source,
  tenantId,
  onClose,
  onVerified,
  actionStatus,
  actionMessage,
}: RagSourceDrawerProps): JSX.Element {
  const [docs, setDocs] = useState<DocsState>({ kind: 'idle' })
  const [chunks, setChunks] = useState<RagChunk[] | null>(null)
  const [chunksLoading, setChunksLoading] = useState(false)
  const [activeDoc, setActiveDoc] = useState<RagDocument | null>(null)
  const [docError, setDocError] = useState<string | null>(null)
  /** 载体级校验的提交态与成功提示（与来源级 `action*` 分开，避免两套流转互相盖掉）。 */
  const [docSubmitting, setDocSubmitting] = useState(false)
  const [docNotice, setDocNotice] = useState<string | null>(null)

  const sourceId = source?.sourceId ?? null

  useEffect(() => {
    if (!sourceId || !tenantId) return
    let cancelled = false
    setDocs({ kind: 'loading' })
    setChunks(null)
    setActiveDoc(null)
    setDocError(null)
    const run = async (): Promise<void> => {
      try {
        const list = await listRagDocuments(tenantId, sourceId)
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
  }, [sourceId, tenantId])

  const openChunks = async (doc: RagDocument): Promise<void> => {
    setActiveDoc(doc)
    setChunksLoading(true)
    setChunks(null)
    try {
      const list = await listRagChunks(tenantId, doc.documentId, 100)
      setChunks(list)
    } catch (cause) {
      setDocError(cause instanceof ApiError ? cause.message : '读取片段失败')
    } finally {
      setChunksLoading(false)
    }
  }

  const verifyDoc = async (doc: RagDocument, payload: RagVerificationRequest): Promise<void> => {
    setDocError(null)
    setDocNotice(null)
    setDocSubmitting(true)
    try {
      await applyRagDocumentVerification(tenantId, doc.documentId, payload)
      // 就地刷新载体列表
      const list = await listRagDocuments(tenantId, doc.sourceId)
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
          {row.verificationStatus && allowedVerificationTargets(row.verificationStatus).length > 0 ? (
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
      title={source ? `语料来源 · ${source.title}` : '语料来源'}
      destroyOnClose
    >
      {source && (
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
                        {source.sourceId}
                      </Text>
                    </Descriptions.Item>
                    <Descriptions.Item label="类型">{source.sourceType}</Descriptions.Item>
                    <Descriptions.Item label="出版社">{source.publisher || '—'}</Descriptions.Item>
                    <Descriptions.Item label="权威等级">
                      <TrustTierTag tier={source.trustTier} />
                    </Descriptions.Item>
                    <Descriptions.Item label="校验状态">
                      <Space>
                        <VerificationTag status={source.verificationStatus} />
                        {source.stale && <Tag color="orange">结论已过期</Tag>}
                        <Tag color={source.admissible ? 'green' : 'default'}>
                          {source.admissible ? '可检索' : '不可检索'}
                        </Tag>
                      </Space>
                    </Descriptions.Item>
                    <Descriptions.Item label="ISBN / DOI">
                      {source.isbn || source.doi || '—'}
                    </Descriptions.Item>
                    <Descriptions.Item label="校验依据" span={2}>
                      {source.verificationEvidence || '—'}
                    </Descriptions.Item>
                    <Descriptions.Item label="审核人 / 时间">
                      {source.verifiedBy ? (
                        <>
                          <Text code>{source.verifiedBy}</Text> · {source.verifiedAt}
                        </>
                      ) : (
                        '—'
                      )}
                    </Descriptions.Item>
                    <Descriptions.Item label="有效期">{source.validDays} 天</Descriptions.Item>
                  </Descriptions>

                  {allowedVerificationTargets(source.verificationStatus).length === 0 ? (
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
                        currentStatus={source.verificationStatus}
                        submitting={actionStatus === 'submitting'}
                        onSubmitted={(payload) => onVerified(source.sourceId, payload)}
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

                  {activeDoc?.verificationStatus &&
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
