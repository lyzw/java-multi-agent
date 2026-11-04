/**
 * RAG 语料管理 · 片段校对（RAG-D13 §3.2.4 / RAG-D14 §4.5）
 *
 * <p><b>这一页解决什么</b>：切分/角色判定是**基于版面事实的确定性规则**
 * （答案区标识 + 题号 + 块首前缀），不是模型推断。规则再严也挡不住
 * 版面千奇百怪——所以必须给人一个"就地纠正"的入口：
 * 把误判为 `EXPLANATION` 的答案块改成 `EXERCISE_ANSWER`。
 *
 * <p><b>⚠️ 为什么"误判为讲解"是最危险的一类错误</b>：
 * 讲解模式下答案类内容会被检索期硬过滤（不进 prompt）；
 * 但一旦被判成"讲解"，它就**畅通无阻地进入上下文** → 标准答案直接泄露给未作答的学生。
 * 所以本页把**答案类片段置顶**，并标明"疑似泄题风险"。
 *
 * <p><b>与后端的差异（本次改造点）</b>：
 * 改造前后端**没有"片段角色重判"端点**。本页的重判按钮调用
 * `PATCH /api/v1/management/chunks/{chunkId}/role`（本次新增，见后端改造说明）。
 * 在端点未就绪时，按钮会给出明确的"后端未接入"提示，而不是静默失败。
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
} from 'antd'

import { ApiError, listRagChunks, listRagDocuments } from '@/api/ragClient'
import type { RagChunk, RagDocument } from '@/api/ragTypes'
import { updateChunkRole } from '@/api/ragClient'
import { RagNotReady, RagScopeShell, type RagScopePageProps } from './RagScopeShell'
import {
  ChunkRoleTag,
  RagEmpty,
  chunkRoleGroup,
  chunkRoleLabel,
} from './ragVisuals'

const { Text, Paragraph } = Typography

/** 可重判的目标角色（与 `ChunkRole` 枚举一致）。 */
const TARGET_ROLES = [
  { value: 'EXERCISE_ANSWER', label: '习题答案 EXERCISE_ANSWER', danger: true },
  { value: 'EXAMPLE_SOLUTION', label: '例题解答 EXAMPLE_SOLUTION', danger: true },
  { value: 'EXERCISE_HINT', label: '习题提示 EXERCISE_HINT', danger: false },
  { value: 'EXERCISE', label: '习题 EXERCISE', danger: false },
  { value: 'EXPLANATION', label: '讲解 EXPLANATION', danger: false },
  { value: 'BOILERPLATE', label: '噪声 BOILERPLATE', danger: false },
] as const

type FilterKind = 'SUSPECT' | 'ANSWER' | 'ALL'

export function RagChunkFixPage(): JSX.Element {
  return (
    <RagScopeShell
      domain="录入与校对"
      title="片段校对"
      description="对片段角色纠错，重点处理「疑似答案区误判」。角色判定只基于版面事实（答案区标识 / 题号 / 块首前缀），本页只做人工纠正。"
    >
      {(scope) => <RagChunkFixBody scope={scope} />}
    </RagScopeShell>
  )
}

/** 页面主体：处在 {@link RagScopeShell} 的 provider 之内，故可直接消费作用域。 */
function RagChunkFixBody({ scope }: RagScopePageProps): JSX.Element {
  const { message } = App.useApp()
  const { target, ready, scopeKey } = scope

  const [sourceId, setSourceId] = useState('')
  const [documents, setDocuments] = useState<RagDocument[]>([])
  const [activeDoc, setActiveDoc] = useState<RagDocument | null>(null)
  const [chunks, setChunks] = useState<RagChunk[]>([])
  const [loading, setLoading] = useState(false)
  const [filter, setFilter] = useState<FilterKind>('ANSWER')
  const [selected, setSelected] = useState<RagChunk | null>(null)
  const [saving, setSaving] = useState(false)

  const loadDocs = useCallback(
    async (sid: string) => {
      if (!sid) return
      try {
        const list = await listRagDocuments(target, sid)
        setDocuments(list)
        setActiveDoc(list.length > 0 ? (list[0] ?? null) : null)
      } catch (cause) {
        message.error(cause instanceof ApiError ? cause.message : '读取载体失败')
      }
    },
    [target, message],
  )

  const loadChunks = useCallback(
    async (doc: RagDocument, kind: FilterKind) => {
      setLoading(true)
      try {
        // 「疑似」= 答案类 + 提示（最需要人复核的两类）；「答案类」= 仅答案；
        // 「全部」= 不限。筛选在 SQL 端做，避免被正文淹没（后端 roles 白名单）。
        const roles =
          kind === 'ANSWER'
            ? ['EXAMPLE_SOLUTION', 'EXERCISE_ANSWER']
            : kind === 'SUSPECT'
              ? ['EXAMPLE_SOLUTION', 'EXERCISE_ANSWER', 'EXERCISE_HINT']
              : undefined
        const list = await listRagChunks(target, doc.documentId, 300, undefined, roles)
        setChunks(list)
        setSelected(list.length > 0 ? (list[0] ?? null) : null)
      } catch (cause) {
        message.error(cause instanceof ApiError ? cause.message : '读取片段失败')
        setChunks([])
      } finally {
        setLoading(false)
      }
    },
    [target, message],
  )

  useEffect(() => {
    setSourceId('')
    setDocuments([])
    setActiveDoc(null)
    setChunks([])
    setSelected(null)
  }, [scopeKey])

  useEffect(() => {
    if (sourceId) void loadDocs(sourceId)
  }, [sourceId, loadDocs])

  useEffect(() => {
    if (activeDoc) void loadChunks(activeDoc, filter)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeDoc?.documentId, filter])

  /** 按风险排序：答案类置顶。 */
  const sorted = useMemo(() => {
    const rank = (r: RagChunk): number =>
      chunkRoleGroup(r.role) === 'answer' ? 0 : chunkRoleGroup(r.role) === 'hint' ? 1 : 2
    return [...chunks].sort((a, b) => rank(a) - rank(b))
  }, [chunks])

  const onReassign = async (role: string): Promise<void> => {
    if (!selected) return
    setSaving(true)
    try {
      await updateChunkRole(target, selected.chunkId, role)
      message.success(`已改为「${chunkRoleLabel(role)}」`)
      // 本地同步更新，避免整表重拉打断校对节奏。
      setChunks((prev) =>
        prev.map((c) => (c.chunkId === selected.chunkId ? { ...c, role } : c)),
      )
      setSelected((prev) => (prev ? { ...prev, role } : prev))
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '重判失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      {!ready ? (
        <RagNotReady />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Card size="small">
            <Space wrap align="center">
              <Text>来源 ID</Text>
              <Input
                style={{ width: 300 }}
                placeholder="粘贴来源 UUID"
                value={sourceId}
                onChange={(e) => setSourceId(e.target.value.trim())}
              />
              <Text>载体</Text>
              <Segmented
                value={activeDoc?.documentId ?? ''}
                onChange={(v) => {
                  const d = documents.find((x) => x.documentId === v)
                  if (d) setActiveDoc(d)
                }}
                options={documents.map((d) => ({
                  value: d.documentId,
                  label: d.title || d.documentId.slice(0, 8),
                }))}
              />
            </Space>
          </Card>

          <Row gutter={16}>
            <Col span={10}>
              <Card
                size="small"
                title="片段列表"
                extra={
                  <Segmented<FilterKind>
                    size="small"
                    value={filter}
                    onChange={(v) => setFilter(v)}
                    options={[
                      { value: 'SUSPECT', label: '疑似' },
                      { value: 'ANSWER', label: '答案类' },
                      { value: 'ALL', label: '全部' },
                    ]}
                  />
                }
              >
                {loading ? (
                  <Spin />
                ) : sorted.length === 0 ? (
                  <RagEmpty text="该筛选条件下没有片段" />
                ) : (
                  <List
                    size="small"
                    dataSource={sorted}
                    pagination={{ pageSize: 12, size: 'small' }}
                    renderItem={(c) => {
                      const group = chunkRoleGroup(c.role)
                      return (
                        <List.Item
                          onClick={() => setSelected(c)}
                          style={{
                            cursor: 'pointer',
                            background: selected?.chunkId === c.chunkId ? '#e6f4ff' : undefined,
                          }}
                        >
                          <Space direction="vertical" size={2} style={{ width: '100%' }}>
                            <Space>
                              <ChunkRoleTag role={c.role} showRisk />
                              {group === 'answer' && <Tag color="red">疑似泄题</Tag>}
                            </Space>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              第 {c.pageNumber} 页
                              {c.exerciseNo ? ` · ${c.exerciseNo}` : ''} · {c.charLength} 字
                            </Text>
                          </Space>
                        </List.Item>
                      )
                    }}
                  />
                )}
              </Card>
            </Col>

            <Col span={14}>
              <Card size="small" title="片段详情与角色重判">
                {!selected ? (
                  <RagEmpty text="从左侧选择一个片段" />
                ) : (
                  <Space direction="vertical" size={12} style={{ width: '100%' }}>
                    <Alert
                      type={chunkRoleGroup(selected.role) === 'answer' ? 'error' : 'info'}
                      showIcon
                      message={
                        chunkRoleGroup(selected.role) === 'answer'
                          ? '该片段当前判为答案类：讲解模式下会被检索期硬过滤，不会进入 prompt。'
                          : '若该片段实为答案，请改为习题答案——判成"讲解"会让标准答案在讲解模式下被召回。'
                      }
                    />

                    <div>
                      <Text type="secondary">当前角色</Text>
                      <div style={{ marginTop: 4 }}>
                        <ChunkRoleTag role={selected.role} showRisk />
                      </div>
                    </div>

                    <div>
                      <Text type="secondary">版面事实（角色判定的依据）</Text>
                      {/*
                        ⚠️ 三态而非两态：blockType 为 null 表示「该片段早于版面事实采集」，
                        必须与「确实不在答案区」区分开——后者是"系统确认过"，
                        前者是"没有这个信息"。把两者混为一谈会误导校对员。
                      */}
                      <table style={{ width: '100%', fontSize: 12, marginTop: 4 }}>
                        <tbody>
                          <tr>
                            <td style={{ color: '#8c8c8c', width: 140 }}>页码</td>
                            <td>第 {selected.pageNumber} 页</td>
                          </tr>
                          <tr>
                            <td style={{ color: '#8c8c8c' }}>题号 exerciseNo</td>
                            <td>{selected.exerciseNo ?? '（无）'}</td>
                          </tr>
                          <tr>
                            <td style={{ color: '#8c8c8c' }}>章节路径</td>
                            <td>{selected.sectionPath || '—'}</td>
                          </tr>
                          <tr>
                            <td style={{ color: '#8c8c8c' }}>是否在答案区</td>
                            <td>
                              {selected.blockType == null && selected.matchedPrefix == null ? (
                                <Text type="secondary">未采集</Text>
                              ) : selected.inAnswerZone ? (
                                <Tag color="red">在答案区</Tag>
                              ) : (
                                <Tag>不在答案区</Tag>
                              )}
                            </td>
                          </tr>
                          <tr>
                            <td style={{ color: '#8c8c8c' }}>块类型 BlockType</td>
                            <td>
                              {selected.blockType ? (
                                <Tag>{selected.blockType}</Tag>
                              ) : (
                                <Text type="secondary">未采集</Text>
                              )}
                            </td>
                          </tr>
                          <tr>
                            <td style={{ color: '#8c8c8c' }}>块首前缀命中</td>
                            <td>
                              {selected.matchedPrefix ? (
                                <Tag color="orange">「{selected.matchedPrefix}」</Tag>
                              ) : (
                                <Text type="secondary">未命中 / 未采集</Text>
                              )}
                            </td>
                          </tr>
                          <tr>
                            <td style={{ color: '#8c8c8c' }}>字符长度</td>
                            <td>{selected.charLength}</td>
                          </tr>
                          <tr>
                            <td style={{ color: '#8c8c8c' }}>向量</td>
                            <td>{selected.hasEmbedding ? '已写入' : '缺失（静默漏召回风险）'}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>

                    <div>
                      <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
                        角色重判
                      </Text>
                      <Space wrap>
                        {TARGET_ROLES.map((r) => (
                          <Button
                            key={r.value}
                            size="small"
                            danger={r.danger}
                            disabled={saving || selected.role === r.value}
                            loading={saving}
                            onClick={() => void onReassign(r.value)}
                          >
                            {r.label}
                          </Button>
                        ))}
                      </Space>
                    </div>

                    <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
                      ⚠️ 重判调 <Text code>PATCH /api/v1/management/chunks/{'{chunkId}'}/role</Text>
                      （本次新增端点）。片段原文由 chunks 端点摘要字段提供；
                      若需逐字原文，需后端在 ChunkResponse 增补 text（见改造说明）。
                    </Paragraph>
                  </Space>
                )}
              </Card>
            </Col>
          </Row>
        </Space>
      )}
    </>
  )
}
