import type { ReactNode } from 'react'
import { Form, Input, InputNumber, Select, Switch, Tag } from 'antd'

/**
 * 配置参数字段的**统一呈现契约**。
 *
 * <p>配置页过去各写各的：同样的数值上限，一页写「范围 1~100」在 label 里，
 * 另一页只在校验器里藏着；布尔开关有的写「启用」，有的不写文案；
 * 读起来像不同系统。这里把「参数是什么 / 允许什么 / 改了会怎样」收敛到一份元数据，
 * 由 {@link ParamField} 统一渲染，避免同一类参数在不同页面长得不一样。
 *
 * <p>⚠️ 只统一**呈现与前端提示**，不改变后端字段、默认值或校验边界。
 */
export type ConfigParamKind = 'boolean' | 'number' | 'text' | 'enum' | 'tags'

export interface ConfigParamSpec {
  /** 与后端一致的字段名或嵌套路径（原样交给 Form.Item 的 name）。 */
  name: string | (string | number)[]
  label: string
  kind: ConfigParamKind
  /** 参数语义：这个值控制什么。 */
  description?: string
  /** 数值单位（如「毫秒」「天」），只进提示文案，不改提交值。 */
  unit?: string
  min?: number
  max?: number
  step?: number
  precision?: number
  /** enum 的选项；value 与后端枚举值逐字一致。 */
  options?: { value: string | number; label: string }[]
  placeholder?: string
  required?: boolean
  disabled?: boolean
  /** 高风险参数显示红旗标签，提醒改动前确认影响面。 */
  risk?: 'high' | 'medium'
  /** 覆盖默认控件宽度（数值默认 180，文本默认 320）。 */
  width?: number
  /** 少数页面需要定制控件时传入，会取代 kind 对应的默认控件。 */
  control?: ReactNode
}

/** 把参数的边界拼成一句固定格式的人话，避免每页自己组织文案。 */
function constraintText(spec: ConfigParamSpec): string | null {
  if (spec.kind !== 'number') return null
  const parts: string[] = []
  if (spec.min !== undefined && spec.max !== undefined) {
    parts.push(`范围 ${spec.min} ~ ${spec.max}`)
  } else if (spec.min !== undefined) {
    parts.push(`≥ ${spec.min}`)
  } else if (spec.max !== undefined) {
    parts.push(`≤ ${spec.max}`)
  }
  if (spec.step !== undefined) parts.push(`步长 ${spec.step}`)
  if (spec.unit) parts.push(`单位 ${spec.unit}`)
  return parts.length > 0 ? parts.join(' · ') : null
}

function defaultControl(spec: ConfigParamSpec): ReactNode {
  switch (spec.kind) {
    case 'boolean':
      return (
        <Switch
          disabled={spec.disabled}
          checkedChildren="启用"
          unCheckedChildren="关闭"
        />
      )
    case 'number':
      return (
        <InputNumber
          disabled={spec.disabled}
          min={spec.min}
          max={spec.max}
          step={spec.step}
          precision={spec.precision}
          placeholder={spec.placeholder}
          style={{ width: spec.width ?? 180 }}
        />
      )
    case 'enum':
      return (
        <Select
          disabled={spec.disabled}
          options={spec.options ?? []}
          placeholder={spec.placeholder}
          style={{ width: spec.width ?? 320 }}
        />
      )
    case 'tags':
      return (
        <Select
          mode="tags"
          disabled={spec.disabled}
          tokenSeparators={[',']}
          placeholder={spec.placeholder}
          style={{ width: spec.width ?? 320 }}
        />
      )
    case 'text':
    default:
      return (
        <Input
          disabled={spec.disabled}
          placeholder={spec.placeholder}
          style={{ width: spec.width ?? 320 }}
        />
      )
  }
}

/**
 * 单个配置参数字段。
 *
 * <p>⚠️ `label` 必须逐字沿用既有文案：测试与运维手册都按标签定位字段，
 * 标准化呈现不能顺手改名。
 */
export function ParamField({ spec }: { spec: ConfigParamSpec }): JSX.Element {
  const notes = [spec.description, constraintText(spec)].filter(
    (v): v is string => Boolean(v && v.trim()),
  )
  const extra = (
    <>
      {spec.risk && (
        <Tag color={spec.risk === 'high' ? 'red' : 'orange'} style={{ marginRight: 6 }}>
          {spec.risk === 'high' ? '高风险' : '需确认'}
        </Tag>
      )}
      {notes.join(' · ')}
    </>
  )

  return (
    <Form.Item
      name={spec.name}
      label={spec.label}
      extra={spec.description || spec.kind === 'number' || spec.risk ? extra : undefined}
      rules={spec.required ? [{ required: true, message: `请填写${spec.label}` }] : undefined}
      valuePropName={spec.kind === 'boolean' ? 'checked' : 'value'}
    >
      {spec.control ?? defaultControl(spec)}
    </Form.Item>
  )
}

/** 参数区栅格：让同一组参数在不同页面保持一样的列宽与间距。 */
export function ParamGrid({ children }: { children: ReactNode }): JSX.Element {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
        gap: '0 20px',
      }}
    >
      {children}
    </div>
  )
}
