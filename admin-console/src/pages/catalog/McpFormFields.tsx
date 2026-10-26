import { Alert, Form, Input, Select } from 'antd'
import type { McpServerFormValues } from './platformObjectForm'
import { useListBridge } from './formFieldBridge'
import { StringListEditor } from './StringListEditor'

/** 暴露操作清单的三态选项（**不可**简化成二态：null 与 [] 语义不同）。 */
const EXPOSED_STATE_OPTIONS = [
  { value: 'UNSET', label: '未配置（取全集）' },
  { value: 'EMPTY', label: '显式空（∅ · 不允许任何操作）' },
  { value: 'LIST', label: '指定清单' },
]

/**
 * MCP Server 定义草稿的**字段区**（第三十七批 · C37-3）。
 *
 * <p><b>⚠️ 为什么抽成独立组件</b>：登记改为**弹窗**后，
 * 「登记」与「修改」分处两处 —— 字段只有<b>这一份</b>，两处都渲染它。
 *
 * <p><b>⚠️ 暴露操作改成参数列表</b>：原先是一个 {@code Select mode="tags"}
 * —— 标签式输入看不出「哪些是真实条目、哪些是误敲的逗号」，
 * 而它恰恰是**权限边界**（允许集 ≠ 实际登记）。
 * 改成逐行列表后，每一条都是一个可增删的独立条目。
 *
 * <p>⚠️ <b>三态仍然必须分得开</b>：{@code null}（未配置 = 取全集）
 * 与 {@code []}（显式不允许任何操作）语义相反 ——
 * 把「显式空」显示成「未配置」等于**权限放宽**。
 */
export interface McpFormFieldsProps {
  /** 登记模式：标识可编辑；修改模式：标识只读。 */
  isRegistration: boolean
  /** 该 Server 已登记凭据引用（目录接口不回显定位符）—— 修改时必须提醒。 */
  hasCredentialRef?: boolean
}

/**
 * MCP Server 字段区（**必须渲染在 `<Form>` 内部**）。
 *
 * @param props 登记/修改模式与凭据提醒开关
 * @returns 字段区
 */
export function McpFormFields({
  isRegistration,
  hasCredentialRef = false,
}: McpFormFieldsProps): JSX.Element {
  const form = Form.useFormInstance()
  const exposed = useListBridge(form, 'exposedAllowlist')
  const state = Form.useWatch('exposedAllowlistState', form) as
    | McpServerFormValues['exposedAllowlistState']
    | undefined

  return (
    <>
      <Form.Item
        name="serverId"
        label="Server 标识"
        rules={[{ required: true, message: '请输入 MCP Server 标识' }]}
        extra={isRegistration ? '平台级对象全平台唯一' : '标识不可改：改标识等于登记另一个对象'}
      >
        <Input disabled={!isRegistration} placeholder="如 order-mcp" />
      </Form.Item>
      <Form.Item name="transport" label="传输（transport）" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'HTTP', label: 'HTTP' },
            { value: 'SSE', label: 'SSE' },
          ]}
        />
      </Form.Item>
      <Form.Item
        name="endpoint"
        label="Endpoint"
        rules={[{ required: true, message: 'http / sse 传输必须配置 endpoint' }]}
        extra="仅允许 http / https；不得内嵌用户名 / 口令（凭据仅引用）"
      >
        <Input placeholder="https://example.internal/mcp" />
      </Form.Item>
      <Form.Item name="command" label="启动命令（command · 可选）">
        <Input placeholder="stdio 预留字段（V1 不启用 stdio 传输）" />
      </Form.Item>
      <Form.Item name="authKind" label="认证方式（authKind）" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'TOKEN', label: 'TOKEN' },
            { value: 'OAUTH', label: 'OAUTH' },
            { value: 'NONE', label: 'NONE' },
          ]}
        />
      </Form.Item>
      <Form.Item name="trustTier" label="信任分级（trustTier）" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'INTERNAL', label: 'INTERNAL' },
            { value: 'PARTNER', label: 'PARTNER' },
            { value: 'PUBLIC', label: 'PUBLIC' },
          ]}
        />
      </Form.Item>
      <Form.Item name="connectionRisk" label="连接风险（connectionRisk）" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'READ', label: 'READ（只读）' },
            { value: 'WRITE_PROFILE', label: 'WRITE_PROFILE（内部写）' },
            { value: 'WRITE_EXTERNAL', label: 'WRITE_EXTERNAL（外部写）' },
          ]}
        />
      </Form.Item>

      <Form.Item
        name="exposedAllowlistState"
        label="暴露操作（exposedToolAllowlist · 三态）"
        extra="null=未配置（取全集） / []=显式不允许任何操作 / 清单=指定"
        initialValue="UNSET"
      >
        <Select options={EXPOSED_STATE_OPTIONS} />
      </Form.Item>
      <Form.Item name="exposedAllowlist" hidden>
        <Input />
      </Form.Item>
      {state === 'LIST' && (
        <Form.Item
          label="暴露操作清单"
          rules={[{ required: true, message: '指定清单不能为空（请改选「显式空」）' }]}
          extra="这是「这个 Server **允许**哪些远端操作」—— 与「实际登记在它上面的 Tool」是两回事"
        >
          <StringListEditor
            value={exposed.value}
            onChange={exposed.onChange}
            placeholder="远端操作名"
            emptyText="尚未指定任何远端操作"
          />
        </Form.Item>
      )}

      <Form.Item
        name="credentialRef"
        label="凭据引用（credentialRef · 可选）"
        extra="凭据库定位符（不是凭据材料）。⚠️ 目录接口从不回显它 —— 不重新填写则新版本不带凭据引用"
      >
        <Input placeholder="如 secret://vault/order-mcp" />
      </Form.Item>
      {!isRegistration && hasCredentialRef && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="该 Server 已登记凭据引用，但目录接口不回显定位符"
          description="若本次发布不重新填写「凭据引用」，新版本将**不带**凭据引用 —— 运行时连接会因此失败。请一并填写。"
        />
      )}
    </>
  )
}

export default McpFormFields
