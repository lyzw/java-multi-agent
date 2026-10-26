import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Checkbox,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import { SendOutlined } from '@ant-design/icons'
import { ApiError } from '@/api/client'
import { createAgent, submitPublishRequest } from '@/api/adminApi'
import type { AgentDefinition } from '@/api/types'
import {
  evaluatePublishGate,
  FAILURE_POLICIES,
  ORCHESTRATION_MODES,
  type FailurePolicy,
} from '../orchestration/orchestrationForm'
import {
  definitionToDraft,
  emptyAgentDraft,
  normalizeAgentDraft,
  type AgentDraftFormValues,
} from './agentDraftForm'

/** 表单取值 = 草稿字段 + 变更理由。 */
type AgentDraftForm = AgentDraftFormValues & { reason: string }

/**
 * 把租户级发布通道的错误翻译成**可区分**的提示。
 *
 * <p>⚠️ **四个状态码对应四件完全不同的事**，绝不能合并成一句「操作失败」：
 * <ul>
 *   <li><b>400</b> = 门禁预检拒绝（形状 / 引用非法）—— 作者能改；</li>
 *   <li><b>403</b> = 作用域不被允许 —— 作者改不了，得换人；</li>
 *   <li><b>409</b> = 状态冲突（同名 / 并发）—— 流程问题；</li>
 *   <li><b>404</b> = 资源不存在。</li>
 * </ul>
 */
export function describeTenantPublishError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 400) return '申请被门禁预检拒绝（400）：请检查填写内容与引用完整性'
    if (e.status === 403) return '作用域不被允许（403）'
    if (e.status === 409) return '状态冲突（409）：同名 Agent 已存在，或申请已被处理'
    if (e.status === 404) return '资源不存在（404）'
  }
  return e instanceof Error ? e.message : '提交申请失败'
}

/** 提交结果（供调用方决定跳转 / 刷新）。 */
export interface AgentDraftSubmitResult {
  /** 申请单标识；提交失败时为 null */
  requestId: string | null
  /** 目标 Agent 标识 */
  agentId: string
  /** 本次是否**新建**了 Agent 行 */
  created: boolean
  /** 申请是否提交失败（登记模式下 Agent 行已建、申请未提交） */
  failed: boolean
}

export interface AgentDraftPanelProps {
  /**
   * 登记模式（新建 Agent）。
   *
   * <p>⚠️ 登记与修改**共用同一张表单** —— 分两张表单迟早漂移。
   * 差别只在「对象是否已存在」：登记时要先建 Agent 行（`cap_agent_def` 对
   * `agent` 表有外键，顺序不可颠倒），修改时直接发新版本。
   */
  registration?: boolean
  /** 已存在的 Agent 标识（修改模式必填） */
  agentId?: string
  /** 权威定义（`cap_agent_def`）；null = 取不到 */
  definition: AgentDefinition | null
  /** 权威定义是否取不到（关系字段无从回填） */
  definitionUnavailable: boolean
  /** 旧体系实体行（提供 name / description 的兜底初值） */
  legacy?: { name?: string | null; description?: string | null } | null
  /** 提交结束（成功或失败）后的回调 */
  onSubmitted?: (result: AgentDraftSubmitResult) => void
}

/**
 * Agent 草稿面板（第三十五批 · 新增 / 修改共用）。
 *
 * <p><b>⚠️ 一张表单覆盖 `cap_agent_def` 的全部字段</b>：
 * 名称 / 描述 / 角色 / 启用技能 / 技能包 / 能力标签 / 配置覆盖 / 编排声明
 * （模式 / 子 Agent / 路由策略 / 输出契约 / 三项委派限制 / 失败策略）。
 *
 * <p><b>⚠️ 「修改」= 发一个新版本，不是原地改</b>（FR-002）：
 * 已发布版本行不可变（DB 触发器），所以改配置只能走发布通道走一个新版本。
 *
 * <p><b>⚠️ 登记模式的顺序不可颠倒</b>：
 * {@code cap_agent_def} 对 {@code agent} 表有复合外键 ⇒ 必须先有 Agent 行。
 * 但**校验必须在建行之前**完成 —— 否则一次编排声明非法的提交会先建出
 * 一个 Agent 行，再发现根本发不出去，留下一个既没有多级定义、
 * 又不是用户想要的孤儿行。
 */
export function AgentDraftPanel({
  registration = false,
  agentId,
  definition,
  definitionUnavailable,
  legacy,
  onSubmitted,
}: AgentDraftPanelProps): JSX.Element {
  const { message } = AntApp.useApp()
  const [form] = Form.useForm<AgentDraftForm>()
  const [submitting, setSubmitting] = useState(false)
  /** 当前所在的字段分组页签（第三十七批 · C37-3） */
  const [draftTab, setDraftTab] = useState('basic')
  /** 编排声明解析失败的原因（null = 正常） */
  const [orchestrationError, setOrchestrationError] = useState<string | null>(null)
  /** 用户已显式确认「本次保存会清空关系」 */
  const [acknowledgeClearRelations, setAcknowledgeClearRelations] = useState(false)

  /**
   * ⚠️ **「取不到权威定义」只有一个来源**：父组件的 `definitionUnavailable`。
   *
   * <p>本组件**不再**自己维护一份 —— 两份状态迟早漂移，
   * 而漂移的后果是「按钮不禁用了，但关系其实取不到」（= 静默清空）。
   */
  const definitionMissing = !registration && definitionUnavailable

  /**
   * ⚠️ **回填只做一次**（按「对象 + 定义版本 + 旧体系实体行」去重）。
   *
   * <p>否则父组件每次重渲染都会重跑回填，把用户正在填的内容**抹掉** ——
   * 表现为「填一半，表单自己空了」。
   *
   * <p>⚠️ **旧体系的名称 / 描述必须进 key**：权威定义 404 时，
   * 定义版本那一段恒为 {@code none}，若 key 不含旧体系实体行，
   * 父组件「先取到实体行、后取定义失败」的第二轮渲染就会**跳过回填** ——
   * 名称 / 描述会一直空着，用户得对着一个连名字都没有的表单。
   */
  const backfillKey = registration
    ? 'new'
    : [
        agentId ?? '',
        definition?.version ?? 'none',
        legacy?.name ?? '',
        legacy?.description ?? '',
      ].join('|')
  const backfilledKey = useRef<string | null>(null)

  useEffect(() => {
    if (backfilledKey.current === backfillKey) return
    backfilledKey.current = backfillKey
    setAcknowledgeClearRelations(false)

    if (registration) {
      // ⚠️ 登记模式：对象尚不存在，关系字段为空是**预期状态**，
      //    不是「静默清空」—— 故不进入「需显式确认」状态。
      form.setFieldsValue(emptyAgentDraft())
      setOrchestrationError(null)
      return
    }

    // ⚠️ **名称 / 描述先按旧体系回填**：这两个字段不依赖多级定义，
    //    取不到权威定义时也拿得到 —— 让用户只补真正缺失的关系字段，
    //    而不是面对一个连名字都空着的表单。
    form.setFieldsValue({
      name: legacy?.name ?? '',
      description: legacy?.description ?? '',
      overrides: '{}',
    })

    if (!definition) {
      // ⚠️ 取不到权威定义 → 关系字段无从回填。此时**不得**让保存静默清空关系，
      //    必须显式提示并要求用户确认（见下方的确认勾选框）。
      setOrchestrationError(null)
      return
    }

    const backfill = definitionToDraft(definition)
    setOrchestrationError(backfill.orchestrationError)
    form.setFieldsValue(backfill.values)
  }, [backfillKey, registration, definition, legacy, form])

  /**
   * ⚠️ 发布闸门（**唯一判据来源**）：提交按钮的 `disabled` 与提交函数内的
   *    提前 `return` 都读它。二者若各写一份判据，迟早漂移 ——
   *    漂移后要么「按钮可点了却被静默拦下」（用户只看到「点了没反应」），
   *    要么「按钮禁用了但程序化调用仍能提交空关系」。
   */
  const publishGate = evaluatePublishGate({
    orchestrationError,
    definitionUnavailable: definitionMissing,
    acknowledgeClearRelations,
  })

  const submit = async (): Promise<void> => {
    let values: AgentDraftForm
    try {
      values = await form.validateFields()
    } catch {
      return // 表单校验未过
    }

    // ⚠️ 闸门判据与按钮的 `disabled` **共用同一份**（`evaluatePublishGate`）。
    const gate = evaluatePublishGate({
      orchestrationError,
      definitionUnavailable: definitionMissing,
      acknowledgeClearRelations,
    })
    if (gate.blocked) {
      message.error(gate.reason ?? '提交被闸门拦截')
      return
    }

    // ⚠️ **校验与归一化必须在创建 Agent 行之前**完成（见组件注释）。
    const normalized = normalizeAgentDraft(values)
    if (!normalized.ok) {
      message.error(normalized.error)
      return
    }

    const reason = values.reason.trim()
    setSubmitting(true)
    // ⚠️ 记录「Agent 行是否已建」—— 登记模式下申请提交失败时，
    //    必须如实告知用户「行已建、申请未提交」，并给出补救路径。
    let createdAgentId: string | null = null
    try {
      let targetAgentId = agentId ?? ''
      if (registration) {
        const agent = await createAgent({
          name: normalized.draft.name,
          description: normalized.draft.description,
        })
        createdAgentId = agent.agentId
        targetAgentId = agent.agentId
      }
      const req = await submitPublishRequest({
        operation: 'PUBLISH',
        scope: 'TENANT',
        agents: [{ agentId: targetAgentId, ...normalized.draft }],
        reason,
      })
      message.success(`申请已提交（${req.requestId.slice(0, 8)}…），待双人复核后执行`)
      onSubmitted?.({
        requestId: req.requestId,
        agentId: targetAgentId,
        created: registration,
        failed: false,
      })
    } catch (e) {
      if (createdAgentId) {
        // ⚠️ **半成功必须如实报告**：Agent 行已建（不可撤销），
        //    但多级定义申请没提交 ⇒ 该 Agent 没有权威定义，关系字段全空。
        //    悄悄说「失败」会让用户以为什么都没发生，留下一个半成品。
        message.error(
          `Agent 已创建（${createdAgentId}），但多级定义申请提交失败：${describeTenantPublishError(e)}。` +
            '该 Agent 目前没有权威定义（关系字段为空），请到它的详情页重新提交发布申请。',
          8,
        )
        onSubmitted?.({
          requestId: null,
          agentId: createdAgentId,
          created: true,
          failed: true,
        })
      } else if (e instanceof ApiError && e.status === 409 && registration) {
        message.error('同名 Agent 已存在（409）')
      } else {
        message.error(describeTenantPublishError(e))
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Card
      title={registration ? '新建 Agent（全字段草稿）' : '修改 Agent（发新版本 · 三步工作流）'}
      extra={<Tag color="blue">申请 → 双人复核 → 执行</Tag>}
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="提交只生成申请单（不写配置表）"
        description={
          registration
            ? '新建 Agent 分两步：先创建 Agent 行（拿到标识），再提交多级定义的发布申请。' +
              '申请通过双人复核并执行后，该 Agent 的关系字段（角色 / 技能 / 编排）才真正生效。'
            : '已发布版本行不可变，改配置只能发一个新版本。审批人不得等于申请人；执行才真正生效。'
        }
      />

      {definitionMissing && !registration && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="该 Agent 尚无多级定义（cap_agent_def），保存会清空关系"
          description={
            '关系字段（角色 / 启用技能 / 技能包 / 能力标签 / 编排声明 / 配置覆盖 overrides）取不到权威定义，' +
            '本次提交会把它们「全部写成空」（overrides 会被重置为 {}，即「无收紧覆盖」）。' +
            '请先确认这一点再提交。'
          }
        />
      )}

      {orchestrationError && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="编排声明解析失败，已阻止提交"
          description={
            `${orchestrationError}。回填失败时表单里的编排字段是空的，` +
            '若照此提交就等于把编排声明覆盖成 LEAF（取消全部委派）。请先修复配置。'
          }
        />
      )}

      {/* ⚠️ 刻意给 Form 命名：antd 用 `name` 给字段 id 加前缀。
          页面上还有「版本」等同名标签（概览的 Descriptions），不加前缀会产出
          **重复的 DOM id**，标签与输入框的关联就指错了地方。 */}
      <Form form={form} name="agent-draft" layout="vertical">
        {/* ⚠️ 字段按主题分 Tab（第三十七批 · C37-3）：
            一张表单里塞十来个字段，作者根本找不到「编排声明」在哪；
            分组后每个 Tab 只讲一件事。
            ⚠️ **每个 Tab 都必须 forceRender**：antd 的 Tabs 默认**懒渲染**
            （没点过的 Tab 不挂载）—— 那样未访问的 Tab 里的必填字段
            不会注册进表单，validateFields 也就**不会校验它们**，
            作者可以在「编排声明」一个字没填的情况下把申请提交上去。
            forceRender 让所有字段一开始就挂载，校验与必填约束才真的生效。 */}
        <Tabs
          activeKey={draftTab}
          onChange={setDraftTab}
          items={[
            {
              key: 'basic',
              label: '基本信息',
              forceRender: true,
              children: (
                <>
        <Form.Item
          name="name"
          label="名称"
          rules={[{ required: true, message: '请输入名称' }]}
        >
          <Input placeholder="如：错题归因助手" />
        </Form.Item>
        <Form.Item name="description" label="描述">
          <Input.TextArea rows={2} placeholder="用途说明（可留空）" />
        </Form.Item>
        <Form.Item name="roles" label="角色（roles）" extra="声明可适用的主体角色范围（非授权授予）">
          <Select mode="tags" placeholder="如 TEACHER" tokenSeparators={[',']} />
        </Form.Item>
                </>
              ),
            },
            {
              key: 'capability',
              label: '能力声明',
              forceRender: true,
              children: (
                <>
        <Form.Item name="enabledSkills" label="启用技能（enabledSkills）" extra="V1 唯一启用途径">
          <Select mode="tags" placeholder="技能 id" tokenSeparators={[',']} />
        </Form.Item>
        <Form.Item name="skillBundles" label="技能包（skillBundles）">
          <Select mode="tags" placeholder="技能包 id" tokenSeparators={[',']} />
        </Form.Item>
        <Form.Item name="capabilityTags" label="能力标签（capabilityTags）">
          <Select mode="tags" placeholder="标签" tokenSeparators={[',']} />
        </Form.Item>
                </>
              ),
            },
            {
              key: 'override',
              label: '收紧覆盖',
              forceRender: true,
              children: (
                <>
        <Form.Item
          name="overrides"
          label="配置覆盖（overrides · JSON）"
          rules={[{ required: true, message: 'overrides 不能为空' }]}
          extra="⚠️ overrides 是「收紧覆盖」，不是整份 definition。回填自权威定义的 overrides 字段；空对象写 {}"
        >
          <Input.TextArea
            rows={6}
            style={{ fontFamily: 'monospace', fontSize: 12 }}
            placeholder='{"removedSkills":["skill-old"]}'
          />
        </Form.Item>
                </>
              ),
            },
            {
              key: 'orchestration',
              label: '编排声明',
              forceRender: true,
              children: (
                <>
        {/* ── 编排声明（多级 Agent v1.1 · 第二十九批 · C29-1 · A6）── */}
        <Form.Item
          name="orchestrationMode"
          label="编排模式（orchestration.mode）"
          initialValue="LEAF"
          extra="LEAF = 不允许委派（缺省）；ROUTER = 按路由策略择一；SEQUENTIAL = 按声明顺序依次执行"
        >
          <Select
            options={ORCHESTRATION_MODES.map((m) => ({
              value: m,
              label: m,
            }))}
          />
        </Form.Item>
        <Form.List name="orchestrationChildren">
          {(fields, { add, remove }) => (
            <>
              {fields.map((field) => (
                <Space key={field.key} align="baseline" wrap>
                  <Form.Item
                    {...field}
                    name={[field.name, 'id']}
                    label="子 Agent id"
                    rules={[{ required: true, message: '请输入子 Agent id' }]}
                  >
                    <Input placeholder="子 Agent 标识（UUID）" style={{ width: 260 }} />
                  </Form.Item>
                  <Form.Item
                    {...field}
                    name={[field.name, 'version']}
                    label="版本"
                    rules={[{ required: true, message: '必须固定版本' }]}
                  >
                    <InputNumber min={1} precision={0} placeholder="固定 release 版本" />
                  </Form.Item>
                  <Form.Item
                    {...field}
                    name={[field.name, 'required']}
                    label="必需"
                    initialValue
                  >
                    <Select
                      style={{ width: 96 }}
                      options={[
                        { value: true, label: '是' },
                        { value: false, label: '否' },
                      ]}
                    />
                  </Form.Item>
                  <Button danger onClick={() => remove(field.name)}>
                    移除
                  </Button>
                </Space>
              ))}
              <Button
                onClick={() =>
                  add({ id: '', version: null, required: true })
                }
              >
                添加子 Agent 引用
              </Button>
            </>
          )}
        </Form.List>
        <Form.Item
          name="routingPolicyRef"
          label="路由策略引用（routingPolicyRef · id@version）"
          extra="ROUTER 模式必填；版本必须固定，不得用 latest"
        >
          <Input placeholder="如 policy-a@1" />
        </Form.Item>
        <Form.Item
          name="resultContractRef"
          label="输出契约引用（resultContractRef · id@version）"
          extra="版本必须固定，不得用 latest；契约需先在「输出契约」页登记"
        >
          <Input placeholder="如 answer-contract@1" />
        </Form.Item>
        <Space size="large" wrap>
          <Form.Item
            name="maxDelegationDepth"
            label="最大委派深度"
            extra="非 LEAF 必填；平台不提供默认值"
          >
            <InputNumber min={1} precision={0} style={{ width: 140 }} />
          </Form.Item>
          <Form.Item
            name="maxChildInvocations"
            label="最大子调用次数"
            extra="非 LEAF 必填；0 = 不允许发起任何子调用"
          >
            <InputNumber min={0} precision={0} style={{ width: 140 }} />
          </Form.Item>
          <Form.Item
            name="timeoutMs"
            label="委派超时（毫秒）"
            extra="非 LEAF 必填；平台不提供默认值"
          >
            <InputNumber min={1} precision={0} style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="failurePolicy" label="失败策略">
            <Select
              allowClear
              placeholder="留空 = 服务端默认值"
              options={FAILURE_POLICIES.map((p: FailurePolicy) => ({ value: p, label: p }))}
            />
          </Form.Item>
        </Space>
                </>
              ),
            },
          ]}
        />
        <Form.Item
          name="reason"
          label="变更理由"
          rules={[{ required: true, message: '变更理由不能为空（将留痕）' }]}
        >
          <Input.TextArea rows={2} placeholder="为何要做这次变更" />
        </Form.Item>

        {/* ⚠️ 关系会被清空时，必须由用户**显式确认**才能提交 —— 绝不静默清空。 */}
        {definitionMissing && !registration && (
          <Form.Item style={{ marginBottom: 12 }}>
            <Checkbox
              checked={acknowledgeClearRelations}
              onChange={(e) => setAcknowledgeClearRelations(e.target.checked)}
            >
              我已知悉：该 Agent 尚无多级定义（cap_agent_def），本次提交会把角色 / 启用技能 /
              技能包 / 能力标签 / 编排声明「全部写成空」，
              且配置覆盖（overrides）会被重置为 {'{}'}（= 无收紧覆盖）
            </Checkbox>
          </Form.Item>
        )}

        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={submitting}
          // ⚠️ 与提交函数内的提前 return **共用同一判据**（`evaluatePublishGate`）
          disabled={publishGate.blocked}
          onClick={() => void submit()}
        >
          {registration ? '创建 Agent 并提交发布申请' : '提交变更申请'}
        </Button>
        {publishGate.blocked && (
          <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
            {publishGate.reason}
          </Typography.Paragraph>
        )}
      </Form>
    </Card>
  )
}

export default AgentDraftPanel
