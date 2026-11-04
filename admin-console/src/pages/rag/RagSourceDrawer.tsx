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
  retryRagIngestJob,
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
import { useRagIngestJobs } from '@/features/rag/useRagIngestJobs'
import {
  currentPrincipalId,
  IngestJobTag,
  IngestProgress,
  TrustTierTag,
  VerificationTag,
} from './ragUi'
import { RagVerificationForm } from './RagVerificationForm'
import { RagInspectionPanel } from './RagInspectionPanel'
import { RagReprocessPanel } from './RagReprocessPanel'

const { Text } = Typography

/** 载体轮询周期（毫秒）。 */
const DOC_POLL_INTERVAL_MS = 3000
/** 载体轮询上限（3s × 200 ≈ 10 分钟，覆盖 148 页教材的 450 秒并留余量）。 */
const DOC_POLL_MAX = 200

/**
 * 载体是否仍在处理中（决定是否继续轮询）。
 *
 * ⚠️ 用**白名单**而不是黑名单：出现未知 `parseStatus`（后端加了新值）时
 *    按「终止」处理，宁可少轮询也不要无限轮询。
 */
function isDocInFlight(parseStatus: string): boolean {
  return parseStatus === 'PENDING' || parseStatus === 'PARSING'
}

/**
 * 把技术错误码翻译成「用户能据此行动」的说明（交互文档 §4.5）。
 *
 * ⚠️ 错误码 (`PARSING 失败` / `SWEEPER_TIMEOUT` / `CONSUMER_CRASH`) 对采集员毫无意义，
 *    而《入库操作手册》§9.4 专门做了一张对照表——说明这类翻译**本该由界面提供**。
 *    未知前缀原样透出：宁可让用户看到原始信息，也不要吞掉线索。
 */
function humanizeIngestError(detail: string | null | undefined): string {
  if (!detail) return '处理过程中出现异常。可点击「重试处理」重新尝试。'
  if (detail.startsWith('SWEEPER_TIMEOUT')) {
    return '处理超时（文件较大或服务繁忙），系统已自动中止。建议重试，仍失败可拆分文件后再传。'
  }
  if (detail.startsWith('CONSUMER_CRASH')) {
    return '处理过程出现意外，已自动记录。请点「重试处理」；若持续失败请联系运维。'
  }
  if (detail.startsWith('SOURCE_REVOKED')) {
    return '本来源已被撤回，处理已取消。'
  }
  if (detail.startsWith('PARSING') || detail.includes('解析')) {
    return `文件无法解析：${detail}。常见原因是文件加密、损坏或不是标准 PDF，建议先用 PDF 工具打开确认。`
  }
  return detail
}

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

  /**
   * 人工抽检面板的未提交标注数（>0 = 关抽屉会丢草稿）。
   *
   * ⚠️ 抽检标注没有任何持久化/恢复手段，Esc 或误点蒙层静默丢掉标注是
   *    纯损失（2026-11-03 端到端抽检实测踩到：Esc 关抽屉，标注全丢）。
   *    有草稿时禁掉 Esc/蒙层关闭，X 按钮走确认弹窗。
   */
  const [inspectionDirty, setInspectionDirty] = useState(0)

  /** 关闭守卫：有未提交标注时先确认。 */
  const requestClose = (): void => {
    if (inspectionDirty > 0) {
      modal.confirm({
        title: '抽检标注尚未提交',
        content: `已标注 ${inspectionDirty} 条，关闭抽屉将丢弃这些标注（无恢复手段）。`,
        okText: '丢弃并关闭',
        okButtonProps: { danger: true },
        cancelText: '继续标注',
        onOk: () => {
          setInspectionDirty(0)
          onClose()
        },
      })
      return
    }
    onClose()
  }

  /** 载体页真实文档上传（Model B）：选文件 → 上传落桶，不手填 objectRef。 */
  const { message, modal } = App.useApp()
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [uploadCarrier, setUploadCarrier] = useState<RagCarrier | null>(null)
  const [uploading, setUploading] = useState(false)

  const sourceId = source?.sourceId ?? null

  /**
   * 本来源的摄取任务（用于载体行的**真实阶段进度**）。
   *
   * ⚠️ 为什么载体行还需要任务信息：`rag_document.parse_status` 只有 4 个值
   *    （待解析/已解析/已切分/失败），**表达不了「切分中」「向量化中」**——
   *    于是用户在长达数分钟的处理期间只看到一个不变的「待解析」标签。
   *    job 的 `status` + `stage` 才能回答「现在卡在哪一步、还剩多少」。
   */
  const ingest = useRagIngestJobs(target, { sourceId: sourceId ?? undefined, limit: 20 })

  /** 本来源最近一次任务（旧任务按时间倒序在后）。 */
  const latestJob = ingest.state.jobs[0] ?? null

  /**
   * 平台全局档写要治理票据（第二刀）：没有票就是只读视图。
   *
   * ⚠️ 在这里收口成**一个**旗标，让「来源流转」「载体流转」「载体列表的校验按钮」
   *    三处入口一起消失。若只禁一处，管理员会在另外两处点到 403，
   *    而后端的 403 文案讲的是授权模型，不像「这个按钮不该出现」。
   */
  const writable = isRagWriteTarget(target)

  /**
   * 载体列表加载 + **处理中自动轮询**。
   *
   * ⚠️ 此前这里只加载一次（打开抽屉时），于是载体行的「解析状态」标签
   *    永远停在「待解析」——用户眼睁睁看着一个不会动的东西，
   *    无法判断任务是在跑还是挂了（148 页教材约 450 秒）。
   *
   * ⚠️ 轮询条件与全局纪律一致：**页面可见** 且 **有任一载体仍在处理中**
   *    （`parseStatus` 非终态），终态立即停；页签隐藏时跳过。
   * ⚠️ 轮询刷新**不重置** `activeDoc` / `chunks`：用户正在看某个载体的片段时，
   *    后台刷新把视图清掉是纯粹的体验倒退。
   */
  useEffect(() => {
    if (!sourceId || !isRagTargetReady(target)) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | null = null
    let polls = 0
    /** 是否还要继续轮询（由本次拉取结果决定）。 */
    let keepPolling = true

    const clear = (): void => {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
    }

    const schedule = (): void => {
      if (cancelled || !keepPolling) return
      if (polls >= DOC_POLL_MAX) return
      clear()
      timer = setTimeout(() => {
        void run()
      }, DOC_POLL_INTERVAL_MS)
    }

    const run = async (): Promise<void> => {
      if (cancelled) return
      // ⚠️ 页签隐藏时不打后端；切回来由 visibilitychange 立即补拉
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
        schedule()
        return
      }
      polls += 1
      try {
        const list = await listRagDocuments(target, sourceId)
        if (cancelled) return
        setDocs(list.length === 0 ? { kind: 'empty' } : { kind: 'ok', docs: list })
        keepPolling = list.some((d) => isDocInFlight(d.parseStatus))
      } catch (cause) {
        if (cancelled) return
        // ⚠️ 轮询期间的偶发失败不清空已展示的载体（只报错）
        setDocs((prev) =>
          prev.kind === 'ok'
            ? prev
            : {
                kind: 'error',
                message: cause instanceof ApiError ? cause.message : '读取载体失败',
              },
        )
        keepPolling = false
      }
      schedule()
    }

    const onVisible = (): void => {
      if (cancelled || document.visibilityState !== 'visible') return
      clear()
      polls += 1
      void run()
    }

    // 首拉：清空上一来源的视图状态（换来源时必须重置）
    setDocs({ kind: 'loading' })
    setChunks(null)
    setActiveDoc(null)
    setDocError(null)
    void run()

    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', onVisible)
    }

    return () => {
      cancelled = true
      clear()
      if (typeof document !== 'undefined') {
        document.removeEventListener('visibilitychange', onVisible)
      }
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

  /** 重试状态（与上传/校验的 busy 态分开，互不干扰）。 */
  const [retrying, setRetrying] = useState(false)

  /**
   * 重试失败的任务。
   *
   * ⚠️ 重试 = **新建一行 job**（attempt+1），旧行永久留痕。
   *    因此这里成功后必须 `reload()` 拉新任务，而不是原地改一条状态。
   */
  const retryJob = async (): Promise<void> => {
    if (!latestJob) return
    setRetrying(true)
    setDocError(null)
    try {
      await retryRagIngestJob(target, latestJob.jobId)
      ingest.reload()
      message.success('已提交重试，正在重新处理')
    } catch (cause) {
      setDocError(cause instanceof ApiError ? cause.message : '重试失败')
    } finally {
      setRetrying(false)
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
      // ⚠️ 上传后立即触发一次任务刷新，让「处理进度」列马上出现，
      //    而不是等下一个轮询周期（用户会以为没提交成功）
      ingest.reload()
      message.success('文档已提交入库，正在自动解析（可在「处理进度」查看）')
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
      title: '处理进度',
      key: 'ingest',
      width: 170,
      render: (_: unknown, row) => {
        // ⚠️ 有任务时以任务为准：它才知道「切分中 / 向量化中 / 失败原因」
        if (latestJob && latestJob.sourceId === row.sourceId) {
          return (
            <Space direction="vertical" size={2} style={{ rowGap: 2 }}>
              <IngestJobTag
                status={latestJob.status}
                stage={latestJob.stage}
                errorDetail={latestJob.errorDetail}
                sourceRevokedCancel={latestJob.sourceRevokedCancel}
              />
              <IngestProgress status={latestJob.status} stage={latestJob.stage} width={140} />
            </Space>
          )
        }
        // 无任务（如历史导入、job 未建）：回落到载体自身的解析状态
        const st = row.parseStatus
        return (
          <Tag color={st === 'FAILED' ? 'red' : st === 'PENDING' ? 'orange' : 'blue'}>
            {RAG_PARSE_STATUS_LABELS[st] ?? st}
          </Tag>
        )
      },
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
      onClose={requestClose}
      keyboard={inspectionDirty === 0}
      maskClosable={inspectionDirty === 0}
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
                        sourceId={shownSource.sourceId}
                        target={target}
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

                  {/*
                    ⚠️ 重处理面板（交互文档 §4.3）：
                        此前想重跑的用户只有两个含糊选项——「重试」或手册里的 reindex，
                        而 reindex 并**不会重新切分**，于是「点了还是老样子」成为高频误解。
                        这里把三种方式显式列出并各自标注代价。
                  */}
                  <Typography.Title level={5} style={{ marginTop: 16, marginBottom: 0 }}>
                    重处理
                  </Typography.Title>
                  <RagReprocessPanel
                    target={target}
                    sourceId={sourceId ?? ''}
                    writable={writable && sourceId !== null}
                  />

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

                  {/*
                    ⚠️ 任务失败要在**页面内**说清楚并给出下一步。
                    此前失败原因只能通过载体行的 tooltip 看到，且没有任何重试入口——
                    用户只能去翻《入库操作手册》复制 curl（交互文档问题 P7）。
                    「已取消」不在此提示：撤回是法务的正常结果，不是故障。
                  */}
                  {latestJob?.status === 'FAILED' && !latestJob.sourceRevokedCancel && (
                    <Alert
                      type="error"
                      showIcon
                      message={`处理失败${latestJob.attempt > 0 ? `（第 ${latestJob.attempt} 次尝试）` : ''}`}
                      description={humanizeIngestError(latestJob.errorDetail)}
                      action={
                        writable ? (
                          <Button size="small" loading={retrying} onClick={() => void retryJob()}>
                            重试处理
                          </Button>
                        ) : null
                      }
                    />
                  )}

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
                          sourceId={shownSource.sourceId}
                          target={target}
                        />
                      </>
                    )}
                </Space>
              ),
            },
            {
              key: 'inspection',
              label: '人工抽检',
              children: (
                <RagInspectionPanel target={target} source={shownSource} onDraftsChange={setInspectionDirty} />
              ),
            },
          ]}
        />
      )}
      <Text type="secondary">决定人（留痕）：{currentPrincipalId() || '（无 principalId）'}</Text>
    </Drawer>
  )
}
