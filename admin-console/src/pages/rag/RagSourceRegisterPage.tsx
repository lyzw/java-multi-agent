/**
 * RAG 语料管理 · 来源登记（RAG-D13 §3.2.1 / RAG-D14 §4.2）
 *
 * <p><b>视觉稿要求</b>：登记来源需录入**版本五轴**（学科/年级/册次/出版社/版次）、
 * 权威等级与版权状态，五轴缺一不可入库（RAG-D00 RD-03）。
 *
 * <p><b>⚠️ 与后端的差异（本次改造点）</b>：
 * 改造前 `RegisterSourceRequest` 只有 title/publisher/isbn/doi 等，
 * **没有版本五轴**——导致来源无法与 `rag_edition` 建立坐标，
 * 上传载体时后端拿不到 editionId。本次新增五个可选字段（可空以兼容存量调用方：
 * 批量导入 CSV 等不走五轴的路径不受影响），但**本页强制五轴齐全才允许提交**，
 * 让"缺一轴不可入库"的纪律落在 UI 上而不是靠人记。
 */

import { useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Input,
  Row,
  Select,
  Space,
  Typography,
  App,
} from 'antd'

import type { RagLicenseStatus, RagTrustTier } from '@/api/ragTypes'
import { useRagManagement } from '@/features/rag/useRagManagement'
import { currentPrincipalId } from './ragUi'
import { RagNotReady, RagScopeShell, type RagScopePageProps } from './RagScopeShell'

const { Text, Paragraph } = Typography

/** 学科可选项（与后端 `Subject` 展示名一致）。 */
const SUBJECT_OPTIONS = ['语文', '数学', '英语', '物理', '化学', '生物', '历史', '地理'].map(
  (v) => ({ value: v, label: v }),
)

/** 年级可选项（与后端 `GradeBand` 展示名一致）。 */
const GRADE_OPTIONS = [
  '一年级',
  '二年级',
  '三年级',
  '四年级',
  '五年级',
  '六年级',
  '七年级',
  '八年级',
  '九年级',
].map((v) => ({ value: v, label: v }))

/** 册次（与后端 `Term` 一致）。 */
const TERM_OPTIONS = ['上册', '下册', '全一册'].map((v) => ({ value: v, label: v }))

/**
 * 版本体系（Publisher 枚举）可选项。
 *
 * ⚠️ **必须与 `rag-core` 的 `EditionCoordinate.Publisher.displayName()` 完全一致**：
 * 检索期用 displayName 逐个比对反查，比对不上会抛异常 → 该校材任何检索 500。
 * 后端登记时会归一（非枚举值 → 「其他」），但 UI 就不该给出枚举外的值——
 * 否则用户选了枚举外的版本，存进去变成「其他」，版本坐标就失真了。
 */
const PUBLISHER_OPTIONS = [
  '人教版',
  '苏教版',
  '北师大版',
  '华师大版',
  '沪教版',
  '鲁教版',
  '鄂教版',
  '其他',
].map((v) => ({ value: v, label: v }))

const TRUST_TIER_OPTIONS: { value: RagTrustTier; label: string }[] = [
  { value: 'OFFICIAL_PUBLISHER', label: 'OFFICIAL_PUBLISHER · 官方出版社' },
  { value: 'OFFICIAL_TUTORIAL', label: 'OFFICIAL_TUTORIAL · 官方教辅' },
  { value: 'PEER_REVIEWED_PAPER', label: 'PEER_REVIEWED_PAPER · 同行评议论文' },
  { value: 'PREPRINT', label: 'PREPRINT · 预印本' },
  { value: 'UNVERIFIED', label: 'UNVERIFIED · 未校验' },
]

const LICENSE_OPTIONS: { value: RagLicenseStatus; label: string }[] = [
  { value: 'LICENSED', label: 'LICENSED · 已授权' },
  { value: 'FAIR_USE_QUOTATION', label: 'FAIR_USE_QUOTATION · 合理引用' },
  { value: 'PENDING', label: 'PENDING · 待确认' },
  { value: 'RESTRICTED', label: 'RESTRICTED · 受限（不可检索）' },
]

interface FormValues {
  title: string
  sourceType: string
  subject: string
  grade: string
  term: string
  publisher: string
  revisionCode: string
  trustTier: RagTrustTier
  licenseStatus: RagLicenseStatus
  isbn?: string
  objectRef?: string
}

export function RagSourceRegisterPage(): JSX.Element {
  return (
    <RagScopeShell
      domain="录入与校对"
      title="来源登记"
      description="登记一个来源（作品级），录入版本五轴、权威等级与版权状态。登记只是纳入治理，不等于可检索；五轴缺一不可入库。"
    >
      {(scope) => <RagSourceRegisterBody scope={scope} />}
    </RagScopeShell>
  )
}

/** 页面主体：处在 {@link RagScopeShell} 的 provider 之内，故可直接消费作用域。 */
function RagSourceRegisterBody({ scope }: RagScopePageProps): JSX.Element {
  const { message } = App.useApp()
  const { target, ready } = scope
  const { action, register, resetAction } = useRagManagement()
  const [form] = Form.useForm<FormValues>()
  const [submitting, setSubmitting] = useState(false)

  /** 五轴实时完整性（视觉稿右侧提示卡）。 */
  const axisValues = Form.useWatch([], form) as Partial<FormValues> | undefined
  const axes: { label: string; key: keyof FormValues }[] = [
    { label: '学科', key: 'subject' },
    { label: '年级', key: 'grade' },
    { label: '册次', key: 'term' },
    { label: '出版社', key: 'publisher' },
    { label: '版次', key: 'revisionCode' },
  ]
  const axisFilled = axes.filter((a) => {
    const v = axisValues?.[a.key]
    return typeof v === 'string' && v.trim() !== ''
  }).length

  const onSubmit = async (values: FormValues): Promise<void> => {
    resetAction()
    setSubmitting(true)
    try {
      const created = await register(target, {
        sourceType: values.sourceType,
        title: values.title,
        publisher: values.publisher,
        isbn: values.isbn?.trim() ? values.isbn.trim() : null,
        doi: null,
        objectRef: values.objectRef?.trim() ? values.objectRef.trim() : null,
        contentHash: null,
        registeredBy: currentPrincipalId(),
        // ---- 本次新增的版本五轴（后端 RegisterSourceRequest 已补同名字段）----
        subject: values.subject,
        grade: values.grade,
        term: values.term,
        editionPublisher: values.publisher,
        revisionCode: values.revisionCode,
        trustTier: values.trustTier,
        licenseStatus: values.licenseStatus,
      })
      if (created) {
        message.success(`来源已登记：${created.title}（状态 PENDING，校验通过后才可检索）`)
        form.resetFields()
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      {!ready ? (
        <RagNotReady />
      ) : (
        <Row gutter={16}>
          <Col span={16}>
            <Card size="small">
              <Form<FormValues>
                form={form}
                layout="vertical"
                onFinish={onSubmit}
                initialValues={{
                  sourceType: 'TEXTBOOK',
                  trustTier: 'OFFICIAL_TUTORIAL',
                  licenseStatus: 'PENDING',
                }}
              >
                <Form.Item
                  name="title"
                  label="来源名称（书名）"
                  rules={[{ required: true, message: '请填写书名' }]}
                >
                  <Input placeholder="例如：义务教育教科书 · 数学 七年级上册" />
                </Form.Item>

                <Row gutter={12}>
                  <Col span={12}>
                    <Form.Item
                      name="sourceType"
                      label="来源类型"
                      rules={[{ required: true, message: '请选择来源类型' }]}
                    >
                      <Select
                        options={[
                          { value: 'TEXTBOOK', label: '教材 / 教辅' },
                          { value: 'PAPER', label: '论文' },
                          { value: 'REFERENCE', label: '参考资料' },
                        ]}
                      />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="isbn" label="ISBN（可选）">
                      <Input placeholder="978-7-107-xxxxx-x" />
                    </Form.Item>
                  </Col>
                </Row>

                <Paragraph strong style={{ marginBottom: 8 }}>
                  版本五轴
                </Paragraph>
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 12 }}
                  message="五轴是「可检索版本」的基础坐标（RAG-D00 RD-03），缺任一轴来源无法入库。"
                />
                <Row gutter={12}>
                  <Col span={8}>
                    <Form.Item
                      name="subject"
                      label="学科"
                      rules={[{ required: true, message: '请选择学科' }]}
                    >
                      <Select options={SUBJECT_OPTIONS} placeholder="学科" />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item
                      name="grade"
                      label="年级"
                      rules={[{ required: true, message: '请选择年级' }]}
                    >
                      <Select options={GRADE_OPTIONS} placeholder="年级" />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item
                      name="term"
                      label="册次"
                      rules={[{ required: true, message: '请选择册次' }]}
                    >
                      <Select options={TERM_OPTIONS} placeholder="册次" />
                    </Form.Item>
                  </Col>
                </Row>
                <Row gutter={12}>
                  <Col span={12}>
                    <Form.Item
                      name="publisher"
                      label="出版社（版本体系）"
                      rules={[{ required: true, message: '请选择出版社' }]}
                      extra="版本五轴之一；也是来源的出版方。检索期按此值反查版本坐标"
                    >
                      <Select options={PUBLISHER_OPTIONS} placeholder="版本体系" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="revisionCode"
                      label="版次"
                      rules={[{ required: true, message: '请填写版次' }]}
                      extra="例如：2024 修订"
                    >
                      <Input placeholder="2024修订" />
                    </Form.Item>
                  </Col>
                </Row>

                <Paragraph strong style={{ marginBottom: 8, marginTop: 8 }}>
                  授权与权威
                </Paragraph>
                <Row gutter={12}>
                  <Col span={12}>
                    <Form.Item
                      name="trustTier"
                      label="权威等级 TrustTier"
                      rules={[{ required: true }]}
                    >
                      <Select options={TRUST_TIER_OPTIONS} />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="licenseStatus"
                      label="版权状态 license_status"
                      rules={[{ required: true }]}
                    >
                      <Select options={LICENSE_OPTIONS} />
                    </Form.Item>
                  </Col>
                </Row>

                <Form.Item name="objectRef" label="教材正文引用（可选，后续可在载体页上传）">
                  <Input placeholder="对象存储引用，如 s3://bucket/path.pdf" />
                </Form.Item>

                {action.status === 'error' && (
                  <Alert
                    type="error"
                    showIcon
                    message="登记失败"
                    description={action.message}
                    style={{ marginBottom: 12 }}
                  />
                )}

                <Space>
                  <Button
                    type="primary"
                    htmlType="submit"
                    loading={submitting}
                    disabled={axisFilled < axes.length}
                  >
                    登记来源
                  </Button>
                  <Button onClick={() => form.resetFields()}>取消</Button>
                  {axisFilled < axes.length && (
                    <Text type="secondary">五轴未填齐（{axisFilled}/{axes.length}）</Text>
                  )}
                </Space>
              </Form>
            </Card>
          </Col>

          <Col span={8}>
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Card size="small" title="五轴完整性提示">
                <Alert
                  type="warning"
                  showIcon
                  message="版本五轴缺任一轴，来源无法入库，且不会进入检索。"
                />
                <Space direction="vertical" size={4} style={{ marginTop: 8, width: '100%' }}>
                  {axes.map((a) => {
                    const filled =
                      typeof axisValues?.[a.key] === 'string' &&
                      String(axisValues[a.key]).trim() !== ''
                    return (
                      <Space key={a.key} size={6}>
                        <Text type={filled ? 'success' : 'secondary'}>
                          {filled ? '✓' : '○'} {a.label}
                        </Text>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {filled ? '已填' : '待填'}
                        </Text>
                      </Space>
                    )
                  })}
                </Space>
              </Card>

              <Card size="small" title="权威等级对「可被检索」的影响">
                <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
                  「入库可、检索不可」分离策略（RAG-D00 RD-02）：
                  <br />· VERIFIED 且达门槛 → 可检索
                  <br />· UNVERIFIED → 仅入库供审核比对，不参与检索
                  <br />
                  权威门槛由服务端配置 <Text code>rag.retrieval.min-trust-tier</Text>
                  （默认 OFFICIAL_TUTORIAL）。
                </Paragraph>
              </Card>
            </Space>
          </Col>
        </Row>
      )}
    </>
  )
}
