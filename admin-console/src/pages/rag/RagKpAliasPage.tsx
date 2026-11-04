/**
 * RAG 语料管理 · 知识点别名（RAG-D13 §3.4.1 / RAG-D14 §4.10）
 *
 * <p><b>这一页做什么</b>：维护知识点的别名映射，让"抛物线函数"能匹配到"二次函数"。
 *
 * <p><b>⚠️ 为什么别名是确定性匹配而非模型抽取</b>（RAG-D04 §3.4）：
 * 知识点挂错的后果是"检索到错误知识点的内容"，而模型抽取的误挂率不可控且不可复现。
 * 别名表是人可以核对、可以回滚的确定性资产。
 *
 * <p><b>⚠️ 改造前后的问题</b>：此前只有 `GET /kp-aliases?kpId=`——
 * 调用方必须先知道 kpId，而管理台无从得知有哪些知识点，
 * 页面只能做成"粘贴 UUID"，教研根本用不了。
 * 补上知识点列表端点后，本页改为「左侧选知识点 → 右侧维护别名」。
 */

import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Input,
  List,
  Row,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'

import {
  ApiError,
  deleteRagKpAlias,
  getRagKpAliases,
  listRagKnowledgePoints,
  upsertRagKpAlias,
} from '@/api/ragClient'
import type { RagKnowledgePointBrief } from '@/api/ragTypes'
import { RagNotReady, RagScopeShell, type RagScopePageProps } from './RagScopeShell'
import { RagEmpty } from './ragVisuals'

const { Text, Paragraph } = Typography

export function RagKpAliasPage(): JSX.Element {
  return (
    <RagScopeShell
      domain="规则配置"
      title="知识点别名"
      description="维护知识点别名，让口语化问法（如「抛物线函数」）能确定性匹配到规范知识点（「二次函数」）。别名是人工可核对、可回滚的确定性资产，不做模型抽取。"
    >
      {(scope) => <RagKpAliasBody scope={scope} />}
    </RagScopeShell>
  )
}

/** 页面主体：处在 {@link RagScopeShell} 的 provider 之内，故可直接消费作用域。 */
function RagKpAliasBody({ scope }: RagScopePageProps): JSX.Element {
  const { message } = App.useApp()
  const { target, ready, scopeKey } = scope

  const [points, setPoints] = useState<RagKnowledgePointBrief[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [selected, setSelected] = useState<RagKnowledgePointBrief | null>(null)
  const [aliases, setAliases] = useState<string[]>([])
  const [aliasLoading, setAliasLoading] = useState(false)
  const [newAlias, setNewAlias] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadPoints = useCallback(
    async (kw?: string) => {
      setLoading(true)
      setError(null)
      try {
        const list = await listRagKnowledgePoints(target, kw)
        setPoints(list)
      } catch (cause) {
        setError(cause instanceof ApiError ? cause.message : '读取知识点失败')
        setPoints([])
      } finally {
        setLoading(false)
      }
    },
    [target],
  )

  const loadAliases = useCallback(
    async (kpId: string) => {
      setAliasLoading(true)
      try {
        const list = await getRagKpAliases(target, kpId)
        setAliases(list)
      } catch (cause) {
        message.error(cause instanceof ApiError ? cause.message : '读取别名失败')
        setAliases([])
      } finally {
        setAliasLoading(false)
      }
    },
    [target, message],
  )

  useEffect(() => {
    if (ready) void loadPoints()
  }, [ready, scopeKey, loadPoints])

  useEffect(() => {
    if (selected) void loadAliases(selected.kpId)
  }, [selected, loadAliases])

  const onAdd = async (): Promise<void> => {
    const alias = newAlias.trim()
    if (!selected || !alias) return
    setSaving(true)
    try {
      await upsertRagKpAlias(target, selected.kpId, alias)
      message.success(`已新增别名「${alias}」`)
      setNewAlias('')
      await loadAliases(selected.kpId)
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '新增失败')
    } finally {
      setSaving(false)
    }
  }

  const onDelete = async (alias: string): Promise<void> => {
    if (!selected) return
    setSaving(true)
    try {
      await deleteRagKpAlias(target, selected.kpId, alias)
      message.success(`已删除别名「${alias}」`)
      await loadAliases(selected.kpId)
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '删除失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      {!ready ? (
        <RagNotReady />
      ) : (
        <>
          {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
          <Row gutter={16}>
            {/* ---- 左：知识点列表 ---- */}
            <Col span={13}>
              <Card
                size="small"
                title="知识点"
                extra={
                  <Space.Compact style={{ width: 240 }}>
                    <Input
                      size="small"
                      placeholder="按规范名搜索"
                      value={keyword}
                      onChange={(e) => setKeyword(e.target.value)}
                      onPressEnter={() => void loadPoints(keyword)}
                    />
                    <Button size="small" onClick={() => void loadPoints(keyword)}>
                      搜索
                    </Button>
                  </Space.Compact>
                }
              >
                <Table<RagKnowledgePointBrief>
                  size="small"
                  rowKey="kpId"
                  loading={loading}
                  dataSource={points}
                  pagination={{ pageSize: 10, size: 'small' }}
                  onRow={(row) => ({
                    onClick: () => setSelected(row),
                    style: {
                      cursor: 'pointer',
                      background: selected?.kpId === row.kpId ? '#e6f4ff' : undefined,
                    },
                  })}
                  locale={{ emptyText: <RagEmpty text="没有匹配的知识点" /> }}
                  columns={[
                    {
                      title: '规范名',
                      dataIndex: 'canonicalName',
                      render: (v: string) => <Text strong>{v}</Text>,
                    },
                    {
                      title: '学科/年级',
                      key: 'sg',
                      width: 140,
                      render: (_, r) => (
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {r.subject} · {r.grade}
                        </Text>
                      ),
                    },
                    {
                      title: '别名',
                      dataIndex: 'aliasCount',
                      width: 70,
                      render: (n: number) =>
                        n > 0 ? <Tag color="blue">{n}</Tag> : <Text type="secondary">0</Text>,
                    },
                    {
                      title: '先修',
                      dataIndex: 'prerequisiteCount',
                      width: 70,
                      render: (n: number) =>
                        n > 0 ? <Tag>{n}</Tag> : <Text type="secondary">0</Text>,
                    },
                  ]}
                />
              </Card>
            </Col>

            {/* ---- 右：别名维护 ---- */}
            <Col span={11}>
              <Card size="small" title={selected ? `别名 · ${selected.canonicalName}` : '别名维护'}>
                {!selected ? (
                  <RagEmpty text="从左侧选择一个知识点，即可查看并维护它的别名" />
                ) : (
                  <Space direction="vertical" size={12} style={{ width: '100%' }}>
                    <Space direction="vertical" size={2}>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {selected.subject} · {selected.grade}
                      </Text>
                      <Text code style={{ fontSize: 11 }}>
                        {selected.kpId}
                      </Text>
                    </Space>

                    <Space.Compact style={{ width: '100%' }}>
                      <Input
                        placeholder="新增别名，如「抛物线函数」"
                        value={newAlias}
                        onChange={(e) => setNewAlias(e.target.value)}
                        onPressEnter={() => void onAdd()}
                      />
                      <Button
                        type="primary"
                        onClick={() => void onAdd()}
                        loading={saving}
                        disabled={!newAlias.trim()}
                      >
                        新增
                      </Button>
                    </Space.Compact>

                    {aliasLoading ? (
                      <Spin />
                    ) : aliases.length === 0 ? (
                      <RagEmpty text="该知识点暂无别名" />
                    ) : (
                      <List
                        size="small"
                        dataSource={aliases}
                        renderItem={(a) => (
                          <List.Item
                            actions={[
                              <a
                                key="del"
                                onClick={() => void onDelete(a)}
                                style={{ color: '#cf1322' }}
                              >
                                删除
                              </a>,
                            ]}
                          >
                            <Tag>{a}</Tag>
                          </List.Item>
                        )}
                      />
                    )}

                    <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
                      ⚠️ 别名在入库时用于<b>确定性匹配</b>：匹配到即挂接该知识点。
                      别名过宽（如「函数」）会让无关片段都挂上来，反而降低检索精度。
                    </Paragraph>
                  </Space>
                )}
              </Card>
            </Col>
          </Row>
        </>
      )}
    </>
  )
}
