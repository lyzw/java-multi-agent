import { useEffect, useRef, useState } from 'react'
import { Button, Input, Typography } from 'antd'

/**
 * 字符串列表编辑器（**纯受控展示组件**）。
 *
 * <p>用于「一行一个字符串」的场景（工具 allowlist、标签、提示词别名 …）。
 * 与 {@code ParamListEditor} / {@code PolicyListEditor} 一样**不用
 * {@code Form.Item}**：文本归属权在父级，本组件只管「数组 ↔ 输入框列表」。
 *
 * <p><b>⚠️ 删空是允许的</b>：删除最后一行后得到**空数组**（不自动补一个空行）——
 * 「空数组」与「一行空字符串」语义不同：前者是「没有条目」，后者是「有一个空条目」，
 * 自动补行等于凭空造出一条空配置。
 */
export interface StringListEditorProps {
  /** 当前的字符串数组（父级持有） */
  value?: string[]
  /** 数组变化时回传**新的**数组（不透传原引用） */
  onChange?: (next: string[]) => void
  /** 输入框占位文案 */
  placeholder?: string
  /** 禁用 */
  disabled?: boolean
  /** 空列表文案；默认「暂无条目」 */
  emptyText?: string
}

export function StringListEditor({
  value,
  onChange,
  placeholder,
  disabled,
  emptyText = '暂无条目',
}: StringListEditorProps): JSX.Element {
  const [items, setItems] = useState<string[]>(() => value ?? [])
  /** 上一次同步过的 value 引用（用于识别「父级回声」是否真的变了） */
  const syncedRef = useRef<string[] | undefined>(undefined)

  // ── value 变化时同步内部状态 ──
  useEffect(() => {
    if (syncedRef.current === value) return
    syncedRef.current = value
    setItems(value ?? [])
  }, [value])

  /** 提交一次变更（永远传**新数组**）。 */
  const commit = (next: string[]): void => {
    setItems(next)
    onChange?.([...next])
  }

  /** 改某一行。 */
  const updateItem = (index: number, text: string): void => {
    commit(items.map((item, i) => (i === index ? text : item)))
  }

  /** 删除某一行（允许删空）。 */
  const removeItem = (index: number): void => {
    commit(items.filter((_, i) => i !== index))
  }

  /** 追加一个空串元素。 */
  const addItem = (): void => {
    commit([...items, ''])
  }

  return (
    <div>
      {items.length === 0 ? (
        <Typography.Text type="secondary">{emptyText}</Typography.Text>
      ) : (
        items.map((item, index) => (
          <div key={index} style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
            <Input
              aria-label={`条目 ${index + 1}`}
              size="small"
              value={item}
              placeholder={placeholder}
              disabled={disabled}
              onChange={(e) => updateItem(index, e.target.value)}
            />
            <Button
              type="text"
              danger
              size="small"
              disabled={disabled}
              onClick={() => removeItem(index)}
            >
              删除
            </Button>
          </div>
        ))
      )}
      <Button onClick={addItem} disabled={disabled}>
        + 新增一行
      </Button>
    </div>
  )
}

export default StringListEditor
