/**
 * RAG 语料管理 · 审核员名录（RAG-D13 §3.4.1 / RAG-D14 §4.9）
 *
 * <p><b>这一页做什么</b>：维护审核员及其审核/复核权限，并显式暴露**职责分离（SoD）冲突**。
 *
 * <p><b>⚠️ 为什么 SoD 冲突要标红</b>：
 * 「能复核但不能审核」是后端 DDL 的 CHECK 约束（矛盾状态）；
 * 而「同一个人既采集又审核」则是治理上真正危险的组合——
 * 它让"双人复核"退化为一个人的两次点击。本页用红色标记把它显性化，
 * 而不是等着后端在提交时才 409。
 *
 * <p><b>与改造前的关系</b>：改造前只有 `POST /reviewers`（upsert）与
 * 场景化的 `GET .../co-signers`（只返回可复核子集），**没有全量名录**。
 * 本次新增 `GET /api/v1/management/reviewers`，本页据此渲染。
 */

import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd'

import { ApiError, listRagReviewers, upsertRagReviewer } from '@/api/ragClient'
import type { RagReviewer } from '@/api/ragTypes'
import { RagScopeShell, type RagScopeBodyParts, type RagScopePageProps } from './RagScopeShell'
import { RagEmpty } from './ragVisuals'

const { Text, Paragraph } = Typography

interface ReviewerForm {
  principalId: string
  displayName: string
  canReview: boolean
  canCoSign: boolean
  enabled: boolean
}

/**
 * RAG · 审核员名录。
 *
 * ⚠️ 用 `body` 形态：页头主操作按钮要绑定本页状态（loading / 弹窗开关等），
 *    而这些状态依赖作用域，只能在 provider 之内声明，故一并写在这个函数里。
 */
export function RagReviewerRosterPage(): JSX.Element {
  return (
    <RagScopeShell
      domain="规则配置"
      title="审核员名录"
      description="维护审核员及其审核/复核权限。职责分离：能复核的人必须也能审核；同一人不得同时采集又审核。"

      body={(scope) => RagReviewerRosterBody({ scope })}
    />
  )
}

function RagReviewerRosterBody({ scope }: RagScopePageProps): RagScopeBodyParts {  const { message } = App.useApp()
  const { target, ready, scopeKey } = scope

  const [rows, setRows] = useState<RagReviewer[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<RagReviewer | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<ReviewerForm>()

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await listRagReviewers(target)
      setRows(list)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '读取审核员名录失败')
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [target])

  useEffect(() => {
    if (ready) void load()
  }, [ready, scopeKey, load])

  const openEdit = (row?: RagReviewer): void => {
    setEditing(row ?? null)
    form.setFieldsValue(
      row
        ? {
            principalId: row.principalId,
            displayName: row.displayName,
            canReview: row.canReview,
            canCoSign: row.canCoSign,
            enabled: row.enabled,
          }
        : {
            principalId: '',
            displayName: '',
            canReview: true,
            canCoSign: false,
            enabled: true,
          },
    )
    setModalOpen(true)
  }

  const onSubmit = async (v: ReviewerForm): Promise<void> => {
    setSaving(true)
    try {
      await upsertRagReviewer(target, {
        principalId: v.principalId.trim(),
        displayName: v.displayName.trim(),
        canReview: v.canReview,
        canCoSign: v.canCoSign,
        enabled: v.enabled,
      })
      message.success('审核员已保存')
      setModalOpen(false)
      await load()
    } catch (cause) {
      message.error(cause instanceof ApiError ? cause.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  /** SoD 冲突：能复核但不能审核（DDL 会拒）。 */
  const isSodConflict = (r: RagReviewer): boolean => r.canCoSign && !r.canReview

  return {
    extra: (
ready ? (
          <Button type="primary" onClick={() => openEdit()}>
            ＋ 新增审核员
          </Button>
        ) : undefined
    ),
    content: (
      <>
error && <Alert type="error" showIcon message={error} />

      <Card size="small">
        <Table<RagReviewer>
          size="small"
          rowKey="principalId"
          loading={loading}
          dataSource={rows}
          pagination={false}
          locale={{ emptyText: <RagEmpty text="名录为空：新增第一位审核员" /> }}
          columns={[
            {
              title: '显示名',
              dataIndex: 'displayName',
              render: (v: string, r) => (
                <Space>
                  <Text strong>{v}</Text>
                  {!r.enabled && <Tag>已停用</Tag>}
                </Space>
              ),
            },
            {
              title: '主体 ID',
              dataIndex: 'principalId',
              render: (v: string) => (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {v}
                </Text>
              ),
            },
            {
              title: '可审核',
              dataIndex: 'canReview',
              width: 90,
              render: (v: boolean) => (v ? <Tag color="green">是</Tag> : <Tag>否</Tag>),
            },
            {
              title: '可复核',
              dataIndex: 'canCoSign',
              width: 90,
              render: (v: boolean) => (v ? <Tag color="blue">是</Tag> : <Tag>否</Tag>),
            },
            {
              title: '职责分离 SoD',
              key: 'sod',
              width: 180,
              render: (_, r) =>
                isSodConflict(r) ? (
                  <Tag color="red">⚠ 可复核但不可审核</Tag>
                ) : r.canCoSign ? (
                  <Tag color="green">已隔离</Tag>
                ) : (
                  <Tag>—</Tag>
                ),
            },
            {
              title: '',
              key: 'actions',
              width: 80,
              render: (_, r) => (
                <a onClick={() => openEdit(r)}>编辑</a>
              ),
            },
          ]}
        />
      </Card>

      <Alert
        type="warning"
        showIcon
        style={{ marginTop: 12 }}
        message="职责分离（SegregationOfDuties）"
        description="同一人不得同时担任录入员与审核员；「可复核」必须同时「可审核」。冲突条目保存时后端会拒绝。"
      />

      <Modal
        title={editing ? `编辑审核员 · ${editing.displayName}` : '新增审核员'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        destroyOnHidden
      >
        <Form<ReviewerForm> form={form} layout="vertical" onFinish={(v) => void onSubmit(v)}>
          <Form.Item
            name="principalId"
            label="主体 ID（UUID）"
            rules={[{ required: true, message: '请填写主体 UUID' }]}
          >
            <Input disabled={Boolean(editing)} placeholder="平台用户主体 UUID" />
          </Form.Item>
          <Form.Item
            name="displayName"
            label="显示名"
            rules={[{ required: true, message: '请填写显示名' }]}
            extra="不得含真实姓名（PIPL）；建议「教研组-王老师」这类内部标识"
          >
            <Input placeholder="教研组-王老师" />
          </Form.Item>
          <Space size={24}>
            <Form.Item name="canReview" label="可审核" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="canCoSign" label="可复核" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
            ⚠️ 开启「可复核」时必须同时开启「可审核」，否则后端返回 400。
          </Paragraph>
        </Form>
      </Modal>
      </>

    ),
  }
}

