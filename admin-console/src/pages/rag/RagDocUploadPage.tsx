/**
 * RAG 语料管理 · 载体上传与解析预览（RAG-D13 §3.2.3 / RAG-D14 §4.4）
 *
 * <p><b>这是写面的核心页面</b>：上传载体（PDF 级）后，让录入员**先看解析结果**，
 * 确认切片、角色、页码无误才提交入库。
 *
 * <p><b>⚠️ 为什么"预览"是独立的、先于入库的一步</b>：
 * 切分质量是教辅 RAG 的第一瓶颈（RAG-D04 §3）——把答案区误并进正文，
 * 后果是讲解模式下原样召回标准答案（泄题）。而这类错误**事后极难发现**：
 * 它不会让检索报错，只会让"召回不全"或"答非所问"。所以必须在上传后立刻给人看一眼。
 *
 * <p><b>靶点 A 的承载</b>：本页只显示**一条进度条 + 四态徽标**
 * （SUBMITTED / PROCESSING / READY / FAILED），不展示 PARSING/CHUNKING/EMBEDDING/INDEXING
 * 四个内部阶段——用户要判断的是"这册书能不能用"，不是流水线走到哪。
 *
 * <p><b>靶点 C/D 的承载</b>：角色着色分组（答案类红 / 提示橙 / 题目蓝 / 可引用绿 / 噪声灰）
 * 让"可能泄题"成为视觉事实，而不是需要记忆的规则。
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Input,
  List,
  Row,
  Segmented,
  Space,
  Spin,
  Tag,
  Typography,
  Upload,
} from 'antd'

import {
  ApiError,
  isRagTargetReady,
  listRagChunks,
  listRagDocuments,
  previewRagDocument,
  uploadRagDocument,
} from '@/api/ragClient'
import type { RagChunk, RagDocument, RagIngestJob, RagPreviewResult } from '@/api/ragTypes'
import { useRagIngestJobs } from '@/features/rag/useRagIngestJobs'
import { currentPrincipalId } from './ragUi'
import { RagNotReady, RagScopeShell, type RagScopePageProps } from './RagScopeShell'
import {
  ChunkRoleLegend,
  ChunkRoleTag,
  IngestProgress,
  IngestStateBadge,
  RagEmpty,
  chunkRoleGroup,
  toIngestState,
} from './ragVisuals'

const { Text, Paragraph } = Typography

/** 角色筛选标签（后端 chunks 端点支持 role 白名单，筛选在 SQL 端生效）。 */
const ROLE_FILTERS = [
  { value: 'ALL', label: '全部' },
  { value: 'ANSWER', label: '答案类' },
  { value: 'HINT', label: '提示' },
  { value: 'QUESTION', label: '题目/例题' },
  { value: 'CITE', label: '可引用正文' },
] as const

type RoleFilter = (typeof ROLE_FILTERS)[number]['value']

/** 角色筛选 → 后端 role 白名单。 */
const ROLE_WHITELIST: Record<Exclude<RoleFilter, 'ALL'>, string[]> = {
  ANSWER: ['EXAMPLE_SOLUTION', 'EXERCISE_ANSWER'],
  HINT: ['EXERCISE_HINT'],
  QUESTION: ['EXERCISE', 'EXAMPLE'],
  CITE: ['EXPLANATION', 'APPENDIX', 'PAPER_BODY', 'PAPER_METHOD'],
}

export function RagDocUploadPage(): JSX.Element {
  return (
    <RagScopeShell
      domain="录入与校对"
      title="载体上传与解析预览"
      description="上传载体（PDF 级）后先看解析结果：切片、角色着色、页码/题号。确认无误再提交入库——切分错误会直接导致泄题或召回不全。"
    >
      {(scope) => <RagDocUploadBody scope={scope} />}
    </RagScopeShell>
  )
}

/** 页面主体：处在 {@link RagScopeShell} 的 provider 之内，故可直接消费作用域。 */
function RagDocUploadBody({ scope }: RagScopePageProps): JSX.Element {
  const { message } = App.useApp()
  const { target, ready, scopeKey, tenantId } = scope

  const [documents, setDocuments] = useState<RagDocument[]>([])
  const [docsLoading, setDocsLoading] = useState(false)
  const [docsError, setDocsError] = useState<string | null>(null)

  const [activeDoc, setActiveDoc] = useState<RagDocument | null>(null)
  const [chunks, setChunks] = useState<RagChunk[]>([])
  const [chunksLoading, setChunksLoading] = useState(false)
  const [roleFilter, setRoleFilter] = useState<RoleFilter>('ALL')
  const [uploading, setUploading] = useState(false)
  const [previewing, setPreviewing] = useState(false)
  const [preview, setPreview] = useState<RagPreviewResult | null>(null)
  /** 待上传文件：先预览、确认后再入库。 */
  const [pendingFile, setPendingFile] = useState<File | null>(null)

  /**
   * ⚠️ 本页需要 sourceId 才能上传/列载体。
   * 视觉稿里"载体上传"是来源详情下的动作；在没有选中来源时，
   * 本页给出"先去来源列表选一个来源"的明确指引，而不是空白页。
   */
  const [sourceId, setSourceId] = useState<string>('')

  const ingest = useRagIngestJobs(target, { limit: 100 })

  const loadDocuments = useCallback(
    async (sid: string) => {
      if (!isRagTargetReady(target) || !sid) return
      setDocsLoading(true)
      setDocsError(null)
      try {
        const list = await listRagDocuments(target, sid)
        setDocuments(list)
      } catch (cause) {
        setDocsError(cause instanceof ApiError ? cause.message : '读取载体列表失败')
        setDocuments([])
      } finally {
        setDocsLoading(false)
      }
    },
    [target],
  )

  const loadChunks = useCallback(
    async (doc: RagDocument, filter: RoleFilter) => {
      if (!isRagTargetReady(target)) return
      setChunksLoading(true)
      try {
        const roles = filter === 'ALL' ? undefined : ROLE_WHITELIST[filter]
        const list = await listRagChunks(target, doc.documentId, 200, undefined, roles)
        setChunks(list)
      } catch (cause) {
        message.error(cause instanceof ApiError ? cause.message : '读取片段失败')
        setChunks([])
      } finally {
        setChunksLoading(false)
      }
    },
    [target, message],
  )

  // 作用域变化：清空全部本页状态（不带着上一档的载体/片段跑）。
  useEffect(() => {
    setSourceId('')
    setDocuments([])
    setActiveDoc(null)
    setChunks([])
  }, [scopeKey])

  useEffect(() => {
    if (sourceId) void loadDocuments(sourceId)
  }, [sourceId, loadDocuments])

  useEffect(() => {
    if (activeDoc) void loadChunks(activeDoc, roleFilter)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeDoc?.documentId, roleFilter])

  /** documentId → 最近一次摄取任务。 */
  const jobByDoc = useMemo(() => {
    const map = new Map<string, RagIngestJob>()
    for (const job of ingest.state.jobs) {
      if (!map.has(job.sourceId)) map.set(job.sourceId, job)
    }
    return map
  }, [ingest.state.jobs])

  /** 当前载体的任务（按 sourceId 折叠，与后端一致）。 */
  const activeJob = sourceId ? jobByDoc.get(sourceId) : undefined

  /**
   * 上传前预览（dry-run）。
   *
   * ⚠️ 这是**只读**调用：不落桶、不建行、不嵌入。让录入员先看切片质量，
   * 不满意直接换文件——而不是上传完才发现答案区被误判，再重走一遍完整链路。
   */
  const onPreview = async (file: File): Promise<void> => {
    if (!sourceId.trim()) {
      message.warning('请先填写来源 ID')
      return
    }
    setPreviewing(true)
    setPreview(null)
    setPendingFile(null)
    try {
      const r = await previewRagDocument(target, sourceId.trim(), file, 50)
      setPreview(r)
      setPendingFile(file)
      if (r.totalChunks === 0) {
        message.warning('预览结果为 0 片段：内容可能全部被判为噪声，请检查文件')
      } else {
        message.success(`预览完成：共 ${r.totalChunks} 片段（展示前 ${r.returnedChunks} 条）`)
      }
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '预览失败')
    } finally {
      setPreviewing(false)
    }
  }

  const onUpload = async (file: File): Promise<boolean> => {
    if (!sourceId.trim()) {
      message.warning('请先填写来源 ID（或从来源列表进入）')
      return false
    }
    setUploading(true)
    try {
      const doc = await uploadRagDocument(target, sourceId.trim(), file, {
        registeredBy: currentPrincipalId(),
      })
      message.success(`载体已上传：${doc.title}（待解析 / 校验后才可检索）`)
      await loadDocuments(sourceId.trim())
      setActiveDoc(doc)
      return false
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '上传失败')
      return false
    } finally {
      setUploading(false)
    }
  }

  /** 角色统计（用于顶部计数）。 */
  const roleStats = useMemo(() => {
    const map = new Map<string, number>()
    for (const c of chunks) {
      const g = chunkRoleGroup(c.role)
      map.set(g, (map.get(g) ?? 0) + 1)
    }
    return map
  }, [chunks])

  return (
    <>
      {!ready ? (
        <RagNotReady />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Card size="small" title="① 选择来源并上传载体">
            <Space wrap align="center" style={{ marginBottom: 12 }}>
              <Text>来源 ID</Text>
              <Input
                style={{ width: 380 }}
                placeholder="粘贴来源 UUID（通常从来源列表页进入）"
                value={sourceId}
                onChange={(e) => setSourceId(e.target.value.trim())}
              />
              {tenantId && (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  当前租户 {tenantId.slice(0, 8)}…
                </Text>
              )}
            </Space>

            <Upload.Dragger
              multiple={false}
              showUploadList={false}
              beforeUpload={(file: File & { uid?: string }) => {
                void onPreview(file)
                return false
              }}
              disabled={previewing || uploading || !sourceId.trim()}
            >
              <p style={{ fontSize: 24, margin: '8px 0' }}>📄</p>
              <p>{previewing ? '解析中…' : '拖拽 PDF 到此处，先预览切分结果'}</p>
              <Text type="secondary" style={{ fontSize: 12 }}>
                预览是 dry-run：不落桶、不建行、不嵌入。确认无误后再点「确认入库」
              </Text>
            </Upload.Dragger>

            {preview && (
              <Card
                size="small"
                style={{ marginTop: 12 }}
                title={
                  <Space>
                    <span>解析预览（dry-run，未入库）</span>
                    <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                      {preview.edition} · 共 {preview.totalChunks} 片段
                      {preview.truncated ? `（展示前 ${preview.returnedChunks} 条）` : ''}
                    </Text>
                  </Space>
                }
                extra={
                  <Space>
                    <Button
                      size="small"
                      onClick={() => {
                        setPreview(null)
                        setPendingFile(null)
                      }}
                    >
                      放弃
                    </Button>
                    <Button
                      size="small"
                      type="primary"
                      disabled={!pendingFile}
                      loading={uploading}
                      onClick={() => {
                        if (pendingFile) void onUpload(pendingFile)
                      }}
                    >
                      确认入库
                    </Button>
                  </Space>
                }
              >
                <Space wrap size={12} style={{ marginBottom: 10 }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>角色分布：</Text>
                  {Object.entries(preview.byRole)
                    .filter(([, n]) => n > 0)
                    .map(([role, n]) => (
                      <Space key={role} size={4}>
                        <ChunkRoleTag role={role} />
                        <Text style={{ fontSize: 12 }}>{n}</Text>
                      </Space>
                    ))}
                </Space>
                <List
                  size="small"
                  dataSource={preview.chunks}
                  pagination={{ pageSize: 6, size: 'small' }}
                  renderItem={(c) => {
                    const group = chunkRoleGroup(c.role)
                    return (
                      <List.Item
                        style={{
                          borderLeft:
                            group === 'answer'
                              ? '4px solid #cf1322'
                              : group === 'hint'
                                ? '4px solid #d46b08'
                                : group === 'cite'
                                  ? '4px solid #389e0d'
                                  : undefined,
                          background: group === 'answer' ? '#fff1f0' : undefined,
                          paddingLeft: 12,
                        }}
                      >
                        <Space direction="vertical" size={4} style={{ width: '100%' }}>
                          <Space wrap>
                            <ChunkRoleTag role={c.role} showRisk />
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              第 {c.pageNumber} 页
                              {c.exerciseNo ? ` · ${c.exerciseNo}` : ''}
                              {c.sectionPath ? ` · ${c.sectionPath}` : ''}
                            </Text>
                            {c.inAnswerZone && <Tag color="red">答案区</Tag>}
                            {c.blockType && <Tag>{c.blockType}</Tag>}
                          </Space>
                          <Text style={{ fontSize: 13 }}>{c.text.slice(0, 200)}</Text>
                        </Space>
                      </List.Item>
                    )
                  }}
                />
              </Card>
            )}

            {activeJob && (
              <div style={{ marginTop: 12 }}>
                <Space>
                  <Text strong>入库进度</Text>
                  <IngestStateBadge state={toIngestState(activeJob.status)} />
                </Space>
                <IngestProgress
                  state={toIngestState(activeJob.status)}
                  progress={activeJob.chunkCount}
                />
                {toIngestState(activeJob.status) === 'FAILED' && activeJob.errorDetail && (
                  <Alert type="error" showIcon message="入库失败" description={activeJob.errorDetail} />
                )}
              </div>
            )}
          </Card>

          <Row gutter={16}>
            <Col span={8}>
              <Card size="small" title="② 载体列表">
                {docsLoading ? (
                  <Spin />
                ) : docsError ? (
                  <Alert type="error" showIcon message={docsError} />
                ) : documents.length === 0 ? (
                  <RagEmpty text={sourceId ? '该来源暂无载体，可在上方上传' : '请先填写来源 ID'} />
                ) : (
                  <List
                    size="small"
                    dataSource={documents}
                    renderItem={(doc) => {
                      const job = jobByDoc.get(sourceId)
                      const state = job ? toIngestState(job.status) : undefined
                      return (
                        <List.Item
                          onClick={() => setActiveDoc(doc)}
                          style={{
                            cursor: 'pointer',
                            background:
                              activeDoc?.documentId === doc.documentId ? '#e6f4ff' : undefined,
                          }}
                        >
                          <Space direction="vertical" size={2} style={{ width: '100%' }}>
                            <Space>
                              <Text strong style={{ fontSize: 13 }}>
                                {doc.title || doc.documentId.slice(0, 8)}
                              </Text>
                              {state && <IngestStateBadge state={state} />}
                            </Space>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {doc.chunkCount} 片段 · {doc.pageCount ?? '—'} 页 · 解析 {doc.parseStatus}
                            </Text>
                          </Space>
                        </List.Item>
                      )
                    }}
                  />
                )}
              </Card>
            </Col>

            <Col span={16}>
              <Card
                size="small"
                title={
                  <Space>
                    <span>③ 解析预览</span>
                    {activeDoc && (
                      <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                        {activeDoc.title}
                      </Text>
                    )}
                  </Space>
                }
                extra={
                  <Segmented<RoleFilter>
                    size="small"
                    value={roleFilter}
                    onChange={(v) => setRoleFilter(v)}
                    options={ROLE_FILTERS.map((f) => ({
                      value: f.value,
                      label:
                        f.value === 'ALL'
                          ? f.label
                          : `${f.label}${roleStats.get(f.value.toLowerCase() as never) != null ? '' : ''}`,
                    }))}
                  />
                }
              >
                <ChunkRoleLegend />
                <div style={{ marginTop: 12 }}>
                  {!activeDoc ? (
                    <RagEmpty text="从左侧选择一个载体查看解析预览" />
                  ) : chunksLoading ? (
                    <Spin />
                  ) : chunks.length === 0 ? (
                    <RagEmpty text="该载体暂无片段（或筛选条件下没有片段）" />
                  ) : (
                    <List
                      size="small"
                      dataSource={chunks}
                      pagination={{ pageSize: 10, size: 'small' }}
                      renderItem={(c) => {
                        const group = chunkRoleGroup(c.role)
                        const isAnswer = group === 'answer'
                        return (
                          <List.Item
                            style={{
                              borderLeft: isAnswer
                                ? '4px solid #cf1322'
                                : group === 'hint'
                                  ? '4px solid #d46b08'
                                  : group === 'cite'
                                    ? '4px solid #389e0d'
                                    : undefined,
                              background: isAnswer ? '#fff1f0' : undefined,
                              paddingLeft: 12,
                            }}
                          >
                            <Space direction="vertical" size={4} style={{ width: '100%' }}>
                              <Space wrap>
                                <ChunkRoleTag role={c.role} showRisk />
                                <Text type="secondary" style={{ fontSize: 12 }}>
                                  第 {c.pageNumber} 页
                                  {c.exerciseNo ? ` · ${c.exerciseNo}` : ''}
                                  {c.sectionPath ? ` · ${c.sectionPath}` : ''}
                                </Text>
                                {!c.hasEmbedding && <Tag color="red">缺向量</Tag>}
                              </Space>
                              <Text type="secondary" style={{ fontSize: 12 }}>
                                {c.charLength} 字
                              </Text>
                            </Space>
                          </List.Item>
                        )
                      }}
                    />
                  )}
                </div>
                {activeDoc && (
                  <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12 }}>
                    ⚠️ 片段正文当前由 chunks 端点仅返回摘要（长度/位置/角色）。
                    若需看到原文逐字内容并就地纠错，需后端在 ChunkResponse 增补 text 字段
                    （见改造说明 §后端）。
                  </Paragraph>
                )}
              </Card>
            </Col>
          </Row>
        </Space>
      )}
    </>
  )
}
