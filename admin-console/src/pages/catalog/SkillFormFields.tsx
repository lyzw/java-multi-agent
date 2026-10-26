import { Button, Form, Input, Select, Space, type FormInstance } from 'antd'
import type { McpDeclarationFormValue, SkillFormValues } from './platformObjectForm'
import { useTextBridge } from './formFieldBridge'
import { PolicyListEditor } from './PolicyListEditor'

/** allowlist 三态的选项（**不可**简化成二态：null 与 [] 语义不同）。 */
const ALLOWLIST_STATE_OPTIONS = [
  { value: 'UNSET', label: '未配置（取该 Server 允许集全集）' },
  { value: 'EMPTY', label: '显式空（∅ · 不允许任何操作）' },
  { value: 'LIST', label: '指定清单' },
]

/**
 * Skill 定义草稿的**字段区**（第三十七批 · C37-3）。
 *
 * <p><b>⚠️ 为什么抽成独立组件</b>：登记改为**弹窗**后，
 * 「登记」与「修改」分处两处 —— 字段只有<b>这一份</b>，两处都渲染它。
 *
 * <p><b>⚠️ 参数策略改成参数列表</b>：原先是一段 raw JSON 文本，
 * 作者必须自己写出 `[{param, op, value}]` 的数组形状，
 * 而 op 只有 {@code in_allowlist} / {@code lte} 两种、value 的形状还随 op 变 ——
 * 写错一个字符就是配置期被拒（400），而那时理由已经填完、走到了审批。
 */
export interface SkillFormFieldsProps {
  /** 登记模式：标识可编辑；修改模式：标识只读。 */
  isRegistration: boolean
}

/**
 * Skill 字段区（**必须渲染在 `<Form>` 内部**）。
 *
 * @param props 登记/修改模式
 * @returns 字段区
 */
export function SkillFormFields({ isRegistration }: SkillFormFieldsProps): JSX.Element {
  const form = Form.useFormInstance()
  const policies = useTextBridge(form, 'paramPolicies')

  return (
    <>
      <Form.Item
        name="skillId"
        label="Skill 标识"
        rules={[{ required: true, message: '请输入 Skill 标识' }]}
        extra={isRegistration ? '平台级对象全平台唯一' : '标识不可改：改标识等于登记另一个对象'}
      >
        <Input disabled={!isRegistration} placeholder="如 order-analysis" />
      </Form.Item>
      <Form.Item name="name" label="名称">
        <Input placeholder="可读名称（可选）" />
      </Form.Item>
      <Form.Item name="description" label="描述">
        <Input.TextArea rows={2} />
      </Form.Item>
      <Form.Item name="riskCeiling" label="风险上限（riskCeiling）" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'READ', label: 'READ（只读）' },
            { value: 'WRITE_PROFILE', label: 'WRITE_PROFILE（内部写）' },
            { value: 'WRITE_EXTERNAL', label: 'WRITE_EXTERNAL（外部写）' },
          ]}
        />
      </Form.Item>
      <Form.Item
        name="declaredTools"
        label="声明的 Tool（declaredTools）"
        extra="⚠️ 发布集合必须自洽闭包：声明的 Tool 会被一并纳入本次发布"
      >
        <Select mode="tags" placeholder="Tool 标识（回车分隔）" tokenSeparators={[',']} />
      </Form.Item>
      <Form.List name="declaredMcp">
        {(fields, { add, remove }) => (
          <>
            {fields.map((field) => (
              <SkillMcpDeclarationRow
                key={field.key}
                field={field}
                form={form}
                onRemove={() => remove(field.name)}
              />
            ))}
            <Button onClick={() => add({ serverId: '', allowlistState: 'UNSET', allowlist: [] })}>
              添加 MCP 声明
            </Button>
          </>
        )}
      </Form.List>
      <Form.Item name="requiredCaps" label="必需能力（requiredCaps）">
        <Select mode="tags" placeholder="能力标识（回车分隔）" tokenSeparators={[',']} />
      </Form.Item>

      <Form.Item name="paramPolicies" hidden>
        <Input />
      </Form.Item>
      <Form.Item
        label="参数策略（paramPolicies）"
        extra="Skill 的参数策略只能**进一步收紧** Tool 的策略：允许清单或数值上限"
      >
        <PolicyListEditor value={policies.value} onChange={policies.onChange} />
      </Form.Item>
    </>
  )
}

/** 一行 MCP 声明（Server + 三态 allowlist）。 */
export function SkillMcpDeclarationRow({
  field,
  form,
  onRemove,
}: {
  field: { name: number; key: number }
  form: FormInstance<SkillFormValues & { reason: string }>
  onRemove: () => void
}): JSX.Element {
  const state = Form.useWatch(['declaredMcp', field.name, 'allowlistState'], form) as
    | McpDeclarationFormValue['allowlistState']
    | undefined
  return (
    <Space align="baseline" wrap>
      <Form.Item
        {...field}
        name={[field.name, 'serverId']}
        label="MCP Server 标识"
        rules={[{ required: true, message: '请输入 MCP Server 标识' }]}
      >
        <Input placeholder="Server 标识" style={{ width: 240 }} />
      </Form.Item>
      <Form.Item
        {...field}
        name={[field.name, 'allowlistState']}
        label="允许操作（toolAllowlist · 三态）"
        extra="null=未配置（全集） / []=显式空 / 清单"
        initialValue="UNSET"
      >
        <Select style={{ width: 280 }} options={ALLOWLIST_STATE_OPTIONS} />
      </Form.Item>
      {state === 'LIST' && (
        <Form.Item
          {...field}
          name={[field.name, 'allowlist']}
          label="允许的操作清单"
          rules={[{ required: true, message: '指定清单不能为空（请改选「显式空」）' }]}
        >
          <Select mode="tags" placeholder="远端操作名（回车分隔）" style={{ width: 320 }} tokenSeparators={[',']} />
        </Form.Item>
      )}
      <Button danger onClick={onRemove}>移除</Button>
    </Space>
  )
}

export default SkillFormFields
