/**
 * RAG 语料管理 · 重索引（RAG-D13 §3.5.2 / RAG-D14 §4.13）
 *
 * <p><b>这一页做什么</b>：换嵌入模型时的**索引代际管理与蓝绿切换**。
 *
 * <p><b>⚠️ 为什么需要蓝绿而不是"原地重建"</b>：
 * 新旧嵌入模型的向量空间**不可比**——同一条文本在两个模型下的向量毫无关系。
 * 若原地覆盖，重建期间检索会用"半新半旧"的混合索引算余弦相似度，
 * 静默产出错误分数且**没有任何报错**。
 * 代际让两套向量并存：旧代际继续服务，新代际建好再原子切换，出问题还能回滚。
 *
 * <p><b>⚠️ 本页的三条硬约束</b>：
 * <ol>
 *   <li><b>BUILDING 不可激活</b>：让只建了一半的代际上线，等于让检索去查
 *       一半是空向量的索引——表现为"有些片段永远召回不到"，且无任何报错。
 *       后端对此返回 409，界面在按钮层就置灰；</li>
 *   <li><b>任何时刻至多一个 ACTIVE</b>：切换由后端在同一事务内完成
 *       （先退役原 ACTIVE、再激活目标），界面不自己搞两段写；</li>
 *   <li><b>回滚 = 重新激活一个 RETIRED 代际</b>：退役代际保留可切回，
 *       这正是"蓝绿"相对"重建"的价值。</li>
 * </ol>
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Progress,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd'

import {
  ApiError,
  activateRagIndexGeneration,
  buildRagIndexGeneration,
  createRagIndexGeneration,
  listRagIndexGenerations,
  reindexRagCorpus,
} from '@/api/ragClient'
import type { RagIndexGeneration } from '@/api/ragTypes'
import { RAG_INDEX_GENERATION_LABEL } from '@/api/ragTypes'
import { RagNotReady, RagScopeShell, type RagScopeBodyParts, type RagScopePageProps } from './RagScopeShell'
import { RagEmpty } from './ragVisuals'

const { Text, Paragraph } = Typography

interface CreateForm {
  embedModel: string
  dimension: number
}

/** 代际状态 → antd 颜色。 */
function statusColor(g: RagIndexGeneration): string {
  switch (g.status) {
    case 'ACTIVE':
      return 'green'
    case 'BUILDING':
      return 'processing'
    default:
      return 'default'
  }
}

/**
 * RAG · 重索引。
 *
 * ⚠️ 用 `body` 形态：页头主操作按钮要绑定本页状态（loading / 弹窗开关等），
 *    而这些状态依赖作用域，只能在 provider 之内声明，故一并写在这个函数里。
 */
export function RagReindexPage(): JSX.Element {
  return (
    <RagScopeShell
      domain="运维"
      title="重索引"
      description="换嵌入模型时的索引代际管理与蓝绿切换：旧代际继续服务，新代际建好再原子切换，出问题可回滚。"

      body={(scope) => RagReindexBody({ scope })}
    />
  )
}

function RagReindexBody({ scope }: RagScopePageProps): RagScopeBodyParts {  const { message } = App.useApp()
  const { target, ready, scopeKey } = scope

  const [generations, setGenerations] = useState<RagIndexGeneration[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [activating, setActivating] = useState<number | null>(null)
  const [building, setBuilding] = useState<number | null>(null)
  const [form] = Form.useForm<CreateForm>()

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await listRagIndexGenerations(target)
      setGenerations(list)
    } catch (cause) {
      // ⚠️ 501 = 后端未接入代际能力：这不是故障，而是"该能力尚未部署"，
      //    必须与"请求失败"区分开，否则运维会去排查一个不存在的问题。
      setError(cause instanceof ApiError ? cause.message : '读取索引代际失败')
      setGenerations([])
    } finally {
      setLoading(false)
    }
  }, [target])

  useEffect(() => {
    if (ready) void load()
  }, [ready, scopeKey, load])

  const active = useMemo(
    () => generations.find((g) => g.status === 'ACTIVE') ?? null,
    [generations],
  )

  const onActivate = async (g: RagIndexGeneration): Promise<void> => {
    setActivating(g.generation)
    try {
      await activateRagIndexGeneration(target, g.generation)
      message.success(`已切换到代际 ${g.generation}（旧代际已退役，可回滚）`)
      await load()
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '切换失败')
    } finally {
      setActivating(null)
    }
  }

  const onBuild = async (g: RagIndexGeneration): Promise<void> => {
    setBuilding(g.generation)
    try {
      // ⚠️ 这是**异步**触发：端点只做"排队"（统计总数、清零游标），立即返回。
      //    真正的嵌入由 worker 分批推进，进度需轮询刷新——不能提示"构建完成"。
      const r = await buildRagIndexGeneration(target, g.generation)
      message.success(
        `代际 ${g.generation} 已排队构建（共 ${r.generation.totalChunks} 片段），` +
          'worker 将分批处理，可稍后刷新查看进度',
      )
      await load()
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '触发构建失败')
    } finally {
      setBuilding(null)
    }
  }

  const onCreate = async (v: CreateForm): Promise<void> => {
    setCreating(true)
    try {
      const created = await createRagIndexGeneration(target, {
        embedModel: v.embedModel.trim(),
        dimension: v.dimension,
      })
      message.success(
        `已创建代际 ${created.generation}（状态 BUILDING，构建完成后才能切换）`,
      )
      setCreateOpen(false)
      await load()
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '创建代际失败')
    } finally {
      setCreating(false)
    }
  }

  return {
    extra: (
ready ? (
          <Space>
            <Button onClick={() => void load()} loading={loading}>
              刷新
            </Button>
            <Button type="primary" onClick={() => setCreateOpen(true)}>
              ＋ 新建索引代际
            </Button>
          </Space>
        ) : undefined
    ),
    content: (
      <>
        {!ready ? (
        <RagNotReady />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {error && (
            <Alert
              type="warning"
              showIcon
              message="索引代际能力不可用"
              description={
                <span>
                  {error}
                  <br />
                  ⚠️ 若为 501，说明后端未装配代际能力（或数据库未执行
                  <Text code>09_v1_0_3_index_generation_alter.sql</Text>）——这是部署缺口，不是请求错误。
                </span>
              }
            />
          )}

          {/* ---- 当前服务代际 ---- */}
          {active && (
            <Card size="small" title="当前服务代际（绿）">
              <Row gutter={16} align="middle">
                <Col span={6}>
                  <Statistic title="代际号" value={active.generation} />
                </Col>
                <Col span={8}>
                  <Statistic title="嵌入模型" value={active.embedModel} />
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    维度 {active.dimension}
                  </Text>
                </Col>
                <Col span={6}>
                  <Statistic title="已索引片段" value={active.indexedChunks} />
                </Col>
                <Col span={4}>
                  <Tag color="green">参与检索</Tag>
                </Col>
              </Row>
            </Card>
          )}

          {/* ---- 代际列表 ---- */}
          <Card size="small" title="索引代际列表">
            <Table<RagIndexGeneration>
              size="small"
              rowKey="generation"
              loading={loading}
              dataSource={generations}
              pagination={false}
              locale={{
                emptyText: (
                  <RagEmpty
                    text={
                      error
                        ? '代际能力不可用（见上方提示）'
                        : '尚无索引代际记录。新建一个代际即可开始蓝绿切换流程。'
                    }
                  />
                ),
              }}
              columns={[
                {
                  title: '代际',
                  dataIndex: 'generation',
                  width: 80,
                  render: (v: number) => <Text strong>{v}</Text>,
                },
                {
                  title: '嵌入模型',
                  dataIndex: 'embedModel',
                  render: (v: string, g) => (
                    <Space direction="vertical" size={0}>
                      <Text>{v}</Text>
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        维度 {g.dimension}
                      </Text>
                    </Space>
                  ),
                },
                {
                  title: '状态',
                  key: 'status',
                  width: 140,
                  render: (_, g) => (
                    <Tag color={statusColor(g)}>{RAG_INDEX_GENERATION_LABEL[g.status]}</Tag>
                  ),
                },
                {
                  title: '构建进度',
                  key: 'progress',
                  width: 180,
                  render: (_, g) => {
                    if (g.status !== 'BUILDING') {
                      return (
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {g.indexedChunks} 片段
                        </Text>
                      )
                    }
                    return (
                      <Progress
                        percent={g.progressPercent}
                        size="small"
                        status="active"
                        format={(p) => `${p}%`}
                      />
                    )
                  },
                },
                {
                  title: '',
                  key: 'actions',
                  width: 130,
                  render: (_, g) => {
                    if (g.status === 'ACTIVE') {
                      return <Text type="secondary">当前服务中</Text>
                    }
                    // ⚠️ BUILDING 不可激活：只建了一半的代际上线，
                    //    等于让检索查一半空向量的索引。改为提供「构建」入口。
                    if (!g.activatable) {
                      return (
                        <Popconfirm
                          title={`构建代际 ${g.generation}？`}
                          description="会把本租户全部合格片段用该代际的模型重新嵌入（后台分批执行，可继续做别的事）。"
                          okText="开始构建"
                          cancelText="取消"
                          onConfirm={() => void onBuild(g)}
                        >
                          <Button
                            size="small"
                            type="primary"
                            loading={building === g.generation}
                          >
                            构建
                          </Button>
                        </Popconfirm>
                      )
                    }
                    const isRollback = g.status === 'RETIRED'
                    return (
                      <Popconfirm
                        title={isRollback ? `回滚到代际 ${g.generation}？` : `切换到代际 ${g.generation}？`}
                        description={
                          isRollback
                            ? '当前代际将退役（可再次切回）。检索会立即改用该代际的向量。'
                            : '当前代际将退役。检索会立即改用该代际的向量。'
                        }
                        okText="确认切换"
                        cancelText="取消"
                        onConfirm={() => void onActivate(g)}
                      >
                        <Button
                          size="small"
                          type={isRollback ? 'default' : 'primary'}
                          loading={activating === g.generation}
                        >
                          {isRollback ? '回滚' : '切换为当前'}
                        </Button>
                      </Popconfirm>
                    )
                  },
                },
              ]}
            />
          </Card>

          {/* ---- 数据修补（原有能力，保留） ---- */}
          <Card size="small" title="补齐缺失向量（数据修补）">
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Alert
                type="info"
                showIcon
                message="这是「修补」不是「换模型」"
                description="它把「已入库但缺向量」的片段补上，写入**当前 ACTIVE 代际**。换嵌入模型请用上方的代际流程——两者语义不同，别混用。"
              />
              <Space>
                <Button
                  onClick={async () => {
                    try {
                      const r = await reindexRagCorpus(target, 500)
                      message.success(
                        `已补齐：索引 ${r.indexedChunks} / 扫描 ${r.scannedChunks}，耗时 ${r.durationMs}ms`,
                      )
                      await load()
                    } catch (cause) {
                      message.error(cause instanceof ApiError ? cause.message : '补齐失败')
                    }
                  }}
                >
                  补齐缺失向量
                </Button>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  同步执行，上限 500 片段
                </Text>
              </Space>
            </Space>
          </Card>

          <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
            ⚠️ 代际切换由后端在**同一事务**内完成（先退役原 ACTIVE、再激活目标）——
            拆成两次提交会出现「零个 ACTIVE」的中间态，那一瞬并发检索查不到任何向量。
          </Paragraph>
        </Space>
      )}

      <Modal
        title="新建索引代际"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={creating}
        destroyOnHidden
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="新代际以 BUILDING 状态创建，不会立即生效"
          description="构建完成前不可切换（避免检索到一半空向量的索引）。"
        />
        <Form<CreateForm>
          form={form}
          layout="vertical"
          onFinish={(v) => void onCreate(v)}
          initialValues={{ embedModel: '', dimension: 1024 }}
        >
          <Form.Item
            name="embedModel"
            label="嵌入模型标识"
            rules={[{ required: true, message: '请填写模型标识' }]}
            extra="例如 BGE-M3 / qwen3-embedding:8b"
          >
            <Input placeholder="BGE-M3" />
          </Form.Item>
          <Form.Item
            name="dimension"
            label="向量维度"
            rules={[{ required: true, message: '请填写维度' }]}
            extra="必须与目标模型的真实维度一致，否则向量写不进 embedding 列"
          >
            <InputNumber min={1} max={16384} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
      </>

    ),
  }
}

