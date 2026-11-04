/**
 * 元数据确认抽屉（交互文档 §4.3「产出确认」/ V1.0.2 §5 R1）。
 *
 * <p><b>它解决什么</b>：当前录入要把「类型/标题/出版社/ISBN/DOI/载体类型/文件」
 * 八个字段一个个手填，而其中多半是系统本就能从文件名推断的。
 * 本抽屉把这些值**先填好**，人只需要扫一眼、改掉错的、点确认。
 *
 * <p><b>⚠️ 五条交互纪律</b>：
 * <ol>
 *   <li><b>推断值必须可辨认</b>：由系统推断的字段打 ✨ 标记，
 *       人改过的字段标记消失——「哪些是机器说的、哪些是我确认的」一眼可分。</li>
 *   <li><b>推断不出不猜</b>：留空并给出填写提示，而不是填一个看起来合理的假值
 *       （错误的 ISBN 会误导去重与审核）。</li>
 *   <li><b>确认 ≠ 修改</b>：本抽屉只提交「确认」这一动作；
 *       字段值随确认一起作为快照留痕（后端记录「他确认的是什么」）。</li>
 *   <li><b>确认 ≠ 通过审核</b>：文案必须说清「确认后进入送审，学生仍然搜不到」，
 *       否则用户会以为点完就上线了。</li>
 *   <li><b>册次只给白名单三值</b>：上册 / 下册 / 全一册（与 DB CHECK 同口径），
 *       给自由输入会在落库时炸出用户看不懂的约束名。</li>
 * </ol>
 */

import { useEffect, useMemo, useState } from 'react'
import { Alert, App, Button, Drawer, Form, Input, Select, Space, Tag, Typography } from 'antd'
import { CheckCircleOutlined } from '@ant-design/icons'
import { confirmRagSourceMetadata, ApiError } from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import {
  RAG_TERM_OPTIONS,
  RAG_METADATA_CONFIRMED_LABELS,
} from '@/api/ragTypes'
import type { RagSource } from '@/api/ragTypes'
import {
  inferMetadataFromFileName,
  toFormDefaults,
} from '@/features/rag/metadataInference'
import type { InferredMetadata } from '@/features/rag/metadataInference'

const { Text } = Typography

export interface RagMetadataConfirmDrawerProps {
  /** 要确认的来源（null = 抽屉关闭） */
  source: RagSource | null
  target: RagTarget
  /**
   * 关联的载体文件名（用于推断）。
   *
   * ⚠️ 来源登记时往往先有文件、后有人填元数据；推断源是**文件名**，
   *    不是来源记录本身（来源里的书名可能是人随便敲的）。
   *    无文件名时回落为「不推断」，全部字段留空待填。
   */
  fileName?: string | null
  onClose: () => void
  /** 确认成功后回调（父组件据此就地更新状态） */
  onConfirmed?: (sourceId: string) => void
  /** 已确认时的只读展示（false = 尚未确认，可编辑提交） */
  readOnly?: boolean
}

interface FormShape {
  title: string
  publisher: string
  isbn: string
  subject: string
  grade: string
  term: string
  note: string
}

/**
 * 字段上方的「推断来源」标记。
 *
 * ⚠️ 用户改过的字段**不再标 ✨**：标着就代表「这是机器说的」，
 *    而人一旦改过，这个断言就不成立了。
 */
function InferredBadge({ inferred }: { inferred: boolean }): JSX.Element | null {
  if (!inferred) return null
  return (
    <Tag color="blue" style={{ marginInlineStart: 6 }}>
      ✨ 系统推断
    </Tag>
  )
}

export function RagMetadataConfirmDrawer({
  source,
  target,
  fileName,
  onClose,
  onConfirmed,
  readOnly = false,
}: RagMetadataConfirmDrawerProps): JSX.Element {
  const { message } = App.useApp()
  const [form] = Form.useForm<FormShape>()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /** 推断结果（来源切换或文件名变化时重算）。 */
  const inferred: InferredMetadata = useMemo(
    () => inferMetadataFromFileName(fileName ?? ''),
    [fileName],
  )

  /**
   * 用户已改动的字段集合。
   *
   * ⚠️ 用 state 记录而不是比较「当前值 vs 推断值」：
   *    用户可能把值改回与原值相同，那也算「他确认过这个字段」。
   */
  const [touched, setTouched] = useState<Set<keyof InferredMetadata>>(new Set())

  /** 打开时用推断值填充表单。 */
  useEffect(() => {
    if (source === null) return
    const defaults = toFormDefaults(fileName ?? '', inferred)
    form.setFieldsValue({
      // ⚠️ 已有来源的书名/出版社优先于推断值：它们是**已入库的事实**，
      //    推断只是给「还没填」的字段补建议。
      title: source.title || defaults.title,
      publisher: source.publisher || defaults.publisher,
      isbn: source.isbn ?? defaults.isbn,
      subject: defaults.subject,
      grade: defaults.grade,
      term: defaults.term,
      note: '',
    })
    setTouched(new Set())
    setError(null)
  }, [source, fileName, inferred, form])

  /** 某个字段此刻是否应显示 ✨（推断出来了 且 人没改过）。 */
  const isInferred = (field: keyof InferredMetadata): boolean =>
    inferred.inferredFields.has(field) && !touched.has(field)

  const submit = async (values: FormShape): Promise<void> => {
    if (!source) return
    setSubmitting(true)
    setError(null)
    try {
      await confirmRagSourceMetadata(target, source.sourceId, {
        title: values.title.trim(),
        publisher: values.publisher.trim(),
        isbn: values.isbn.trim() || null,
        subject: values.subject.trim() || null,
        grade: values.grade.trim() || null,
        term: values.term || null,
        note: values.note.trim() || null,
      })
      message.success('元数据已确认，可以提交审核了（学生此时仍搜不到）')
      onConfirmed?.(source.sourceId)
      onClose()
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : '确认失败，请稍后重试',
      )
    } finally {
      setSubmitting(false)
    }
  }

  const confirmed = readOnly

  return (
    <Drawer
      width={620}
      open={source !== null}
      onClose={onClose}
      destroyOnHidden
      title={confirmed ? '元数据已确认' : '确认语料元数据'}
    >
      {source && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {confirmed ? (
            <Alert
              type="success"
              showIcon
              icon={<CheckCircleOutlined />}
              message="这本书的元数据已确认"
              description={
                <span>
                  {RAG_METADATA_CONFIRMED_LABELS.CONFIRMED}：元数据已经人核对，
                  可以进入审核流程。注意：<b>确认不等于审核通过</b>，学生仍然搜不到。
                </span>
              }
            />
          ) : (
            <Alert
              type="info"
              showIcon
              message="系统已根据文件名预填，请核对后确认"
              description={`标有 ✨ 的字段是系统推断的。推断只是建议——请改掉不对的再确认。确认后本册会进入送审流程。${
                fileName ? `（推断来源：${fileName}）` : ''
              }`}
            />
          )}

          {error && <Alert type="error" showIcon message="确认失败" description={error} />}

          <Form<FormShape>
            form={form}
            layout="vertical"
            disabled={confirmed}
            onFinish={(values) => void submit(values)}
            // ⚠️ 任一字段变动即标记为「人改过」，✨ 标记随之消失
            onValuesChange={(changed: Partial<FormShape>) => {
              const fields = Object.keys(changed) as (keyof InferredMetadata)[]
              setTouched((prev) => {
                const next = new Set(prev)
                for (const f of fields) next.add(f)
                return next
              })
            }}
          >
            <Form.Item
              label={
                <span>
                  书名
                  <InferredBadge inferred={isInferred('title')} />
                </span>
              }
              name="title"
              rules={[{ required: true, message: '请填写书名' }]}
            >
              <Input placeholder="如：沪教版英语六年级上册" />
            </Form.Item>

            <Form.Item
              label={
                <span>
                  出版社
                  <InferredBadge inferred={isInferred('publisher')} />
                </span>
              }
              name="publisher"
              rules={[{ required: true, message: '请填写出版社' }]}
            >
              <Input placeholder="如：上海教育出版社" />
            </Form.Item>

            <Space size="middle" style={{ display: 'flex' }} align="start">
              <Form.Item
                label={
                  <span>
                    学科
                    <InferredBadge inferred={isInferred('subject')} />
                  </span>
                }
                name="subject"
                style={{ minWidth: 150 }}
              >
                <Input placeholder="可留空" />
              </Form.Item>
              <Form.Item
                label={
                  <span>
                    年级
                    <InferredBadge inferred={isInferred('grade')} />
                  </span>
                }
                name="grade"
                style={{ minWidth: 150 }}
              >
                <Input placeholder="可留空" />
              </Form.Item>
              <Form.Item
                label={
                  <span>
                    册次
                    <InferredBadge inferred={isInferred('term')} />
                  </span>
                }
                name="term"
                style={{ minWidth: 150 }}
              >
                <Select
                  allowClear
                  placeholder="可留空"
                  options={RAG_TERM_OPTIONS.map((v) => ({ value: v, label: v }))}
                />
              </Form.Item>
            </Space>

            <Form.Item
              label={
                <span>
                  ISBN
                  <InferredBadge inferred={isInferred('isbn')} />
                </span>
              }
              name="isbn"
              // ⚠️ 教材/教辅建议填，但不强制：论文类语料本就没有 ISBN，
              //    强制必填会逼用户填假值（比留空更糟）。
              tooltip="教材/教辅建议填写；论文类可留空。系统只采信校验位正确的 ISBN。"
            >
              <Input placeholder="可留空，如 9787544400008" />
            </Form.Item>

            <Form.Item label="确认备注" name="note" tooltip="例如「封面磨损，ISBN 由版权页核对」">
              <Input.TextArea rows={2} placeholder="可留空" />
            </Form.Item>

            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message="确认后仍不可检索"
              description="确认只代表「元数据对得上」，本册还需经过抽检与双人复核才能让学生搜到。"
            />

            {/*
              ⚠️ 只读态**不渲染**提交按钮，而不是 disabled：
                  一个灰着的「确认元数据」会让用户以为「再点点就生效了」，
                  而实际上这条路已经走完了。给不存在的动作画按钮是产品缺陷。
            */}
            {!confirmed && (
              <Space>
                <Button type="primary" htmlType="submit" loading={submitting}>
                  确认元数据
                </Button>
                <Button onClick={onClose}>稍后再说</Button>
              </Space>
            )}
          </Form>

          {confirmed && (
            <Button onClick={onClose} block>
              关闭
            </Button>
          )}

          <Text type="secondary" style={{ fontSize: 12 }}>
            确认会记录「谁在何时确认了哪些字段」，用于事后追溯。
          </Text>
        </Space>
      )}
    </Drawer>
  )
}
