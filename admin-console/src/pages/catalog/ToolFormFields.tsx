import { useEffect, useState } from 'react'
import { Form, Input, InputNumber, Select, Tabs, Tag } from 'antd'
import {
  HTTP_DEFAULT_TIMEOUT_MS,
  HTTP_MAX_TIMEOUT_MS,
  HTTP_METHODS,
  type ToolFormValues,
} from './platformObjectForm'
import { ParamListEditor } from './ParamListEditor'
import { PolicyListEditor } from './PolicyListEditor'

/**
 * Tool 定义草稿的**字段区**（第三十七批 · C37-3）。
 *
 * <p><b>⚠️ 为什么抽成独立组件</b>：登记改为**弹窗**后，
 * 「登记」与「修改」分处两处（目录页的弹窗 / 详情页的表单）。
 * 若各自写一份字段，两处必然漂移 —— 而漂移的方向通常是
 * 「弹窗那份少了一个字段」，作者填完才发现少配了东西。
 * 故字段只有<b>这一份</b>，两处都渲染它。
 *
 * <p><b>⚠️ 为什么按类型分 Tab</b>：三种 kind 的专属字段互斥
 * （MCP 字段与 HTTP 字段不能同时出现），平铺在一张表单上会让作者
 * 同时看到三组「当前用不上」的输入框 —— 填错一个就是 400。
 * 用 Tab 分组后，<b>当前类型的那一组是唯一可见的</b>，
 * 而 Tab 本身就是类型选择器（点哪个 Tab 就是哪种 kind）。
 */

/** 三种 kind 的 Tab key。 */
type KindTab = 'NATIVE' | 'MCP' | 'HTTP'

const HTTP_METHOD_OPTIONS = HTTP_METHODS.map((m) => ({ value: m, label: m }))

export interface ToolFormFieldsProps {
  /** 登记模式：标识可编辑；修改模式：标识只读。 */
  isRegistration: boolean
}

/**
 * Tool 字段区（**必须渲染在 `<Form>` 内部**）。
 *
 * @param props 登记/修改模式
 * @returns 字段区
 */
export function ToolFormFields({ isRegistration }: ToolFormFieldsProps): JSX.Element {
  const form = Form.useFormInstance()
  const kind = Form.useWatch('kind', form) as ToolFormValues['kind'] | undefined
  const inputSchema = Form.useWatch('inputSchema', form) as string | undefined
  const paramPolicies = Form.useWatch('paramPolicies', form) as string | undefined

  const [activeKind, setActiveKind] = useState<KindTab>(
    kind === 'MCP' || kind === 'HTTP' ? kind : 'NATIVE',
  )

  // ⚠️ 外部改动 kind（回填既有定义）时同步 Tab ——
  //    否则 Tab 会停在旧类型上，而表单里的 kind 已经是新类型，
  //    作者看到的是「HTTP 的字段却显示在 MCP 页签里」。
  useEffect(() => {
    if (kind === 'MCP' || kind === 'HTTP' || kind === 'NATIVE') {
      setActiveKind(kind)
    }
  }, [kind])

  const onTabChange = (key: string): void => {
    const next = key as KindTab
    setActiveKind(next)
    // ⚠️ 切换类型必须**清空其它类型的字段** ——
    //    三组专属字段互斥，残留值会让后端以「目标不唯一」拒（400），
    //    而错误信息指向的字段在当前 Tab 里根本看不见。
    form.setFieldsValue({
      kind: next,
      mcpServerId: '',
      mcpToolName: '',
      httpMethod: '',
      httpUrl: '',
      httpTimeoutMs: null,
    })
  }

  return (
    <>
      <Form.Item
        name="toolId"
        label="Tool 标识"
        rules={[{ required: true, message: '请输入 Tool 标识' }]}
        extra={
          isRegistration
            ? '平台级对象全平台唯一，标识一经提交不可改'
            : '标识不可改：改标识等于登记另一个对象'
        }
      >
        <Input disabled={!isRegistration} placeholder="如 order-query" />
      </Form.Item>
      <Form.Item name="name" label="名称">
        <Input placeholder="可读名称（可选）" />
      </Form.Item>
      <Form.Item name="riskLevel" label="风险等级（riskLevel）" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'READ', label: 'READ（只读）' },
            { value: 'WRITE_PROFILE', label: 'WRITE_PROFILE（内部写）' },
            { value: 'WRITE_EXTERNAL', label: 'WRITE_EXTERNAL（外部写）' },
          ]}
        />
      </Form.Item>

      {/* ⚠️ kind 用隐藏字段承载：真正的类型选择器是下面的 Tab。
          隐藏字段仍参与 validateFields 与 setFieldsValue。 */}
      <Form.Item name="kind" hidden>
        <Input />
      </Form.Item>

      <Tabs
        activeKey={activeKind}
        onChange={onTabChange}
        items={[
          {
            key: 'NATIVE',
            label: '内置（NATIVE）',
            children: (
              <Form.Item label="内置 Tool 说明">
                <div style={{ color: 'rgba(0,0,0,0.45)' }}>
                  内置 Tool 由平台直接实现，不绑定任何 MCP Server，也不发起远程请求。
                  本类没有额外字段 —— 它的行为完全由下方的「参数定义」决定。
                </div>
              </Form.Item>
            ),
          },
          {
            key: 'MCP',
            label: 'MCP 绑定',
            children: (
              <>
                <Form.Item
                  name="mcpServerId"
                  label="所属 MCP Server"
                  rules={[{ required: true, message: 'kind=MCP 时必须填写所属 MCP Server' }]}
                  extra="⚠️ 发布集合必须自洽闭包：该 Server 会被一并纳入本次发布"
                >
                  <Input placeholder="MCP Server 标识" />
                </Form.Item>
                <Form.Item
                  name="mcpToolName"
                  label="远端操作名（mcpToolName）"
                  rules={[{ required: true, message: 'kind=MCP 时必须填写远端操作名' }]}
                >
                  <Input placeholder="该 Server 暴露的远端操作名" />
                </Form.Item>
              </>
            ),
          },
          {
            key: 'HTTP',
            label: '远程 HTTP',
            children: (
              <>
                <Form.Item
                  name="httpMethod"
                  label="请求方法（httpMethod）"
                  rules={[{ required: true, message: 'kind=HTTP 时必须填写请求方法' }]}
                  extra="⚠️ 只允许下列方法（与后端白名单一致）"
                >
                  <Select options={HTTP_METHOD_OPTIONS} placeholder="GET" />
                </Form.Item>
                <Form.Item
                  name="httpUrl"
                  label="目标 URL（httpUrl）"
                  rules={[{ required: true, message: 'kind=HTTP 时必须填写目标 URL' }]}
                  extra={
                    <>
                      仅允许 http / https 绝对地址；不得内嵌用户名 / 口令（凭据仅引用）。
                      <br />
                      ⚠️ 目标 URL 可以用 {'{参数名}'} 占位，运行时用模型给出的参数替换
                      （路径段编码）；其余参数按方法走请求体或查询串。
                    </>
                  }
                >
                  <Input placeholder="https://api.example.com/orders/{orderId}" />
                </Form.Item>
                <Form.Item
                  name="httpTimeoutMs"
                  label="超时（httpTimeoutMs · 毫秒）"
                  rules={[{ required: true, message: 'kind=HTTP 时必须填写超时' }]}
                  extra={`1 ~ ${HTTP_MAX_TIMEOUT_MS} 毫秒。超时是**总时限**：超时的请求按「结果未知」处理，需人工对账。`}
                  initialValue={HTTP_DEFAULT_TIMEOUT_MS}
                >
                  <InputNumber min={1} max={HTTP_MAX_TIMEOUT_MS} precision={0} style={{ width: 220 }} />
                </Form.Item>
                <Form.Item label="出站安全">
                  <div style={{ color: 'rgba(0,0,0,0.45)' }}>
                    目标 URL 在**每次调用时**都会重新做 SSRF 校验：DNS 解析后按实际 IP
                    判定，回环 / 私网 / 链路本地 / 云元数据地址一律禁止；
                    每次重定向也会重新校验。响应体按「不可信外部数据」处理（加边界、限体积）。
                  </div>
                </Form.Item>
              </>
            ),
          },
        ]}
      />

      {/* ⚠️ 参数定义（入参结构 + 参数策略）**不属于任何一种类型** ——
          三种 kind 的参数含义一致，故放在 Tab 之外。 */}
      <Form.Item name="inputSchema" hidden>
        <Input />
      </Form.Item>
      <Form.Item name="paramPolicies" hidden>
        <Input />
      </Form.Item>
      <Form.Item label="入参结构（inputSchema）" extra="以参数列表逐条声明；缺省表示无结构约束（不代表允许任意参数）">
        <ParamListEditor
          value={inputSchema}
          onChange={(text) => form.setFieldValue('inputSchema', text)}
        />
      </Form.Item>
      <Form.Item label="参数策略（paramPolicies）" extra="收紧参数取值域：允许清单或数值上限">
        <PolicyListEditor
          value={paramPolicies}
          onChange={(text) => form.setFieldValue('paramPolicies', text)}
        />
      </Form.Item>

      <div style={{ marginBottom: 16 }}>
        当前类型：<Tag color="blue">{activeKind}</Tag>
      </div>
    </>
  )
}

export default ToolFormFields
