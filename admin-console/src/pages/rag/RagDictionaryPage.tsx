/**
 * RAG 语料管理 · 字典（RAG-D13 §3.4.1 / RAG-D14 §4.11）
 *
 * <p><b>这一页做什么</b>：维护语料治理下拉枚举的数据源（来源类型 / 权威等级 /
 * 校验手段 / 版权状态），让新增取值不必改前端代码。
 *
 * <p><b>⚠️ 与视觉稿的差异（如实现，不粉饰）</b>：
 * 视觉稿原型里字典类型写的是「出版社 / 学科 / 版次」，但后端
 * `GET /dictionaries` 实际固定返回 **SOURCE_TYPE / TRUST_TIER /
 * VERIFICATION_METHOD / LICENSE_STATUS** 四类，且只有 SOURCE_TYPE 可写。
 * 本页按**后端真实类型**渲染——把不存在的类型画出来只会让人点了报错。
 * 出版社/学科/版次若要可维护，需后端先补字典类型（见改造说明）。
 */

import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'

import {
  ApiError,
  deleteRagDictionaryEntry,
  getRagDictionaries,
  upsertRagDictionaryEntry,
} from '@/api/ragClient'
import type { RagDictionaryEntry, RagDictionaryType, RagDictionaryTypeView } from '@/api/ragTypes'
import { RagScopeShell, type RagScopeBodyParts, type RagScopePageProps } from './RagScopeShell'
import { RagEmpty } from './ragVisuals'

const { Text, Paragraph } = Typography

/** 字典类型中文名（与后端四类一一对应）。 */
const TYPE_LABEL: Record<string, string> = {
  // 版本五轴（可维护）
  EDITION_PUBLISHER: '出版社',
  SUBJECT: '学科',
  GRADE: '年级',
  REVISION: '版次',
  // 治理流转
  SOURCE_TYPE: '来源类型',
  TRUST_TIER: '权威等级',
  VERIFICATION_METHOD: '校验手段',
  LICENSE_STATUS: '版权状态',
}

interface EntryForm {
  code: string
  label: string
  sortOrder: number
  enabled: boolean
}

/**
 * 平台数据优先：平台全局项（`PLATFORM`）排在每个字典类型的前面，
 * 含平台项的类型也排在页签前面；同组内保持后端返回顺序（稳定排序）。
 *
 * ⚠️ 只改**展示顺序**，不改可编辑性判定：平台项对租户只读，
 *    表格里不给它们编辑/删除入口（后端 RLS 不放行 `tenant_id IS NULL`）。
 */
function platformFirst(list: RagDictionaryTypeView[]): RagDictionaryTypeView[] {
  const withOrderedEntries = list.map((t) => ({
    ...t,
    entries: [...t.entries].sort(
      (a, b) => Number(b.scope === 'PLATFORM') - Number(a.scope === 'PLATFORM'),
    ),
  }))
  return withOrderedEntries.sort(
    (a, b) =>
      Number(b.entries.some((e) => e.scope === 'PLATFORM')) -
      Number(a.entries.some((e) => e.scope === 'PLATFORM')),
  )
}

/** 作用域列：把枚举值翻成中文并给平台档上色，避免「平台数据优先」只靠排序暗示。 */
function scopeTag(scope: string): JSX.Element {
  if (scope === 'PLATFORM') return <Tag color="blue">平台全局</Tag>
  if (scope === 'TENANT') return <Tag>本租户</Tag>
  return <Tag>{scope}</Tag>
}

/**
 * RAG · 字典（RAG-D13 §3.4.1 / RAG-D14 §4.11）
 *
 * ⚠️ 用 `body` 形态：页头「＋ 新增条目」要绑定本页 editable / form / setModalOpen，
 *    这些依赖作用域，故全部状态与页头按钮一并写在 body 函数里。
 */
export function RagDictionaryPage(): JSX.Element {
  return (
    <RagScopeShell
      domain="规则配置"
      title="字典"
      description="维护语料录入与治理下拉的数据源，新增取值无需改前端代码。版本五轴四类与「来源类型」可维护；权威等级/校验手段/版权状态为派生字典（由后端枚举生成，只读）。"
      body={(scope) => RagDictionaryBody({ scope })}
    />
  )
}

function RagDictionaryBody({ scope }: RagScopePageProps): RagScopeBodyParts {  const { message } = App.useApp()
  const { target, ready, scopeKey, scope: corpusScope } = scope

  /**
   * ⚠️ 字典项写端点走 `requireTenantContext`：**只接受 TENANT 档**（PLATFORM 直接 409），
   *    与语料来源写（平台档可凭治理票据）不是同一套闸门。
   *    平台数据优先后首屏默认在平台档，故写入口必须按这个更严的判据收住，
   *    否则「＋ 新增条目 / 编辑 / 删除」点了必吃 409。
   */
  const canWriteTenantEntries = corpusScope === 'TENANT' && ready

  const [types, setTypes] = useState<RagDictionaryTypeView[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [activeType, setActiveType] = useState<RagDictionaryType | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<EntryForm>()

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = platformFirst(await getRagDictionaries(target))
      setTypes(list)
      // 默认落在含平台数据的字典类型（平台数据优先）
      setActiveType((prev) => prev ?? list[0]?.type ?? null)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '读取字典失败')
      setTypes([])
    } finally {
      setLoading(false)
    }
  }, [target])

  useEffect(() => {
    if (ready) void load()
  }, [ready, scopeKey, load])

  const current = types.find((t) => t.type === activeType) ?? types[0]
  const editable = current?.editable ?? false

  const onSave = async (v: EntryForm): Promise<void> => {
    if (!current) return
    setSaving(true)
    try {
      await upsertRagDictionaryEntry(target, current.type, {
        code: v.code.trim(),
        label: v.label.trim(),
        sortOrder: v.sortOrder,
        enabled: v.enabled,
      })
      message.success('字典项已保存')
      setModalOpen(false)
      await load()
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const onDelete = async (code: string): Promise<void> => {
    if (!current) return
    setSaving(true)
    try {
      await deleteRagDictionaryEntry(target, current.type, code)
      message.success(`已删除「${code}」`)
      await load()
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '删除失败')
    } finally {
      setSaving(false)
    }
  }

  return {
    extra: (
editable && canWriteTenantEntries ? (
          <Button
            type="primary"
            onClick={() => {
              form.setFieldsValue({ code: '', label: '', sortOrder: 0, enabled: true })
              setModalOpen(true)
            }}
          >
            ＋ 新增条目
          </Button>
        ) : undefined
    ),
    content: (
      <>
      {error && <Alert type="error" showIcon message={error} />}

      {editable && !canWriteTenantEntries && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="字典项写入只支持「本租户语料」档"
          description="首屏默认展示平台全局字典（只读）。要新增 / 编辑 / 删除租户私有字典项，请切到「本租户语料」档并选择租户；平台档提交会被后端以 409 拒绝。"
        />
      )}

      <Card size="small">
        {types.length === 0 && !loading ? (
          <RagEmpty text="暂无字典数据" />
        ) : (
          <Tabs
            activeKey={current?.type}
            onChange={(k) => setActiveType(k as RagDictionaryType)}
            items={types.map((t) => ({
              key: t.type,
              label: (
                <Space size={6}>
                  {TYPE_LABEL[t.type] ?? t.type}
                  {!t.editable && <Tag>只读</Tag>}
                </Space>
              ),
              children: (
                <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  {!t.editable && (
                    <Alert
                      type="info"
                      showIcon
                      message="该字典由后端枚举派生，不可编辑"
                      description="派生字典的取值来自领域枚举（如 TrustTier），改名会与检索期判定漂移，故只读。"
                    />
                  )}
                  <Table<RagDictionaryEntry>
                    size="small"
                    rowKey="code"
                    loading={loading}
                    dataSource={t.entries}
                    pagination={false}
                    locale={{ emptyText: <RagEmpty text="该类型暂无条目" /> }}
                    columns={[
                      {
                        title: '编码 code',
                        dataIndex: 'code',
                        render: (v: string) => <Text code>{v}</Text>,
                      },
                      { title: '显示名 label', dataIndex: 'label' },
                      {
                        title: '状态',
                        dataIndex: 'enabled',
                        width: 90,
                        render: (v: boolean) =>
                          v ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>,
                      },
                      {
                        title: '作用域',
                        dataIndex: 'scope',
                        width: 100,
                        render: (v: string) => scopeTag(v),
                      },
                      {
                        title: '',
                        key: 'actions',
                        width: 120,
                        render: (_, row) =>
                          t.editable && row.scope !== 'PLATFORM' && canWriteTenantEntries ? (
                            <Space size={8}>
                              <a
                                onClick={() => {
                                  form.setFieldsValue({
                                    code: row.code,
                                    label: row.label,
                                    sortOrder: row.sortOrder ?? 0,
                                    enabled: row.enabled,
                                  })
                                  setModalOpen(true)
                                }}
                              >
                                编辑
                              </a>
                              <a
                                style={{ color: '#cf1322' }}
                                onClick={() => void onDelete(row.code)}
                              >
                                删除
                              </a>
                            </Space>
                          ) : (
                            <Text type="secondary">—</Text>
                          ),
                      },
                    ]}
                  />
                </Space>
              ),
            }))}
          />
        )}
      </Card>

      <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
        平台数据优先：平台全局项排在每类字典前面并默认展示。平台档为只读视图——字典项写入只支持本租户档；平台项本身也不提供编辑 / 删除入口。
      </Paragraph>
      <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 4, marginBottom: 0 }}>
        ⚠️ 「出版社 / 学科 / 年级」的取值须落在版本坐标枚举范围内——
        检索期按枚举 displayName 反查，超出范围的值会让该校材的检索失败。
        「版次」无枚举约束，可自由填写。
      </Paragraph>

      <Modal
        title={`${current ? (TYPE_LABEL[current.type] ?? current.type) : ''} · 字典项`}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        destroyOnHidden
      >
        <Form<EntryForm> form={form} layout="vertical" onFinish={(v) => void onSave(v)}>
          <Form.Item name="code" label="编码 code" rules={[{ required: true }]}>
            <Input placeholder="如 ONLINE_BOOK" />
          </Form.Item>
          <Form.Item name="label" label="显示名 label" rules={[{ required: true }]}>
            <Input placeholder="如 在线图书" />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
      </>
    ),
  }
}

