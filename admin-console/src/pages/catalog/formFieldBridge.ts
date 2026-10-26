import { useCallback } from 'react'
import { Form, type FormInstance } from 'antd'

/**
 * 表单字段与**可视化编辑器**之间的桥（第三十七批 · C37-2）。
 *
 * <p><b>⚠️ 为什么需要桥</b>：参数列表、参数策略、暴露操作清单这类字段，
 * 真实的输入界面是**自定义组件**（列表 / 表格），不是 antd 的标准控件。
 * 但它们仍然要参与 {@code validateFields} 与 {@code setFieldsValue} ——
 * 于是用「一个隐藏的 Form.Item 承载原始文本 + 一个组件渲染编辑界面」的方式接线。
 *
 * <p><b>⚠️ 为什么用隐藏 Form.Item 而不是纯本地 state</b>：
 * 详情页回填既有定义时走的是 {@code form.setFieldsValue}，
 * 若参数定义只存在本地 state，回填就**不会**生效 ——
 * 作者会看到一个空表单，却以为「打开的是已有对象」。
 */

/** 取一个文本字段的当前值，并给出写回方法。 */
export function useTextBridge<T extends FormInstance>(
  form: T,
  name: string,
): { value?: string; onChange: (text: string) => void } {
  const value = Form.useWatch(name, form) as string | undefined
  const onChange = useCallback(
    (text: string) => {
      form.setFieldValue(name, text)
    },
    [form, name],
  )
  return { value, onChange }
}

/** 取一个字符串数组字段的当前值，并给出写回方法。 */
export function useListBridge<T extends FormInstance>(
  form: T,
  name: string,
): { value?: string[]; onChange: (next: string[]) => void } {
  const value = Form.useWatch(name, form) as string[] | undefined
  const onChange = useCallback(
    (next: string[]) => {
      form.setFieldValue(name, next)
    },
    [form, name],
  )
  return { value, onChange }
}
