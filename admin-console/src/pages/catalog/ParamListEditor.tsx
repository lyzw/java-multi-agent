import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Checkbox, Input, Select, Table } from 'antd'
import type { TableColumnsType } from 'antd'
import {
  buildParamListSchema,
  parseParamList,
  type ParamRow,
  type ParamType,
} from './paramList'

/**
 * 「入参结构」参数列表编辑器（**纯受控展示组件**）。
 *
 * <p><b>⚠️ 为什么不用 {@code Form.Item}</b>：本组件刻意**不与 antd Form 耦合** ——
 * 父级（页面 / 详情抽屉）自己持有 {@code value} 文本，并决定把它放进
 * {@code Form.Item} 还是别的容器。组件只负责「文本 ↔ 行列表」的可视化编辑，
 * 文本的**归属权始终在父级**。
 *
 * <p><b>⚠️ 行内容与文本的关系</b>：文本是父级的**事实来源**，行列表是它的**视图**。
 * 因此：
 * <ul>
 *   <li>序列化**成功** → 回传新文本，父级更新后本组件不再回解析（避免把用户
 *       正在输入的原文格式抹平，例如枚举 {@code "a, b"} 被压成 {@code "a,b"}）；</li>
 *   <li>序列化**失败** → <b>不</b>回传（父级仍持有上一次合法文本），
 *       但**行内容保留用户刚改的样子**（不回滚）；</li>
 *   <li>{@code value} 解析失败 → 退化为「原文输入框」兜底，用户手改到合法后自动切回表格。</li>
 * </ul>
 */
export interface ParamListEditorProps {
  /** 入参结构 JSON 文本（父级持有） */
  value?: string
  /** 参数列表变化且**可成功序列化**时回传新的 JSON 文本 */
  onChange?: (text: string) => void
  /** 禁用 */
  disabled?: boolean
}

/**
 * 编辑中的一行（{@link ParamRow} + 一个**只用于 React key** 的稳定标识）。
 *
 * ⚠️ 为什么不能直接拿参数名当 key：参数名在编辑过程中会变（作者正在打字），
 * 用它做 key 会让输入框被反复卸载重建 —— 打字打到一半焦点就没了。
 * ⚠️ {@code __id} **不参与序列化**（{@code buildParamListSchema} 只读
 * {@code schema} / {@code type} / … 这些建模字段），所以不会污染产出文本。
 */
interface EditableParamRow extends ParamRow {
  __id: string
}

/** 类型下拉选项：英文值 + 中文说明（值必须是英文，后端只认这 6 种）。 */
const TYPE_OPTIONS: { value: ParamType; label: string }[] = [
  { value: 'string', label: 'string（文本）' },
  { value: 'number', label: 'number（数值）' },
  { value: 'integer', label: 'integer（整数）' },
  { value: 'boolean', label: 'boolean（布尔）' },
  { value: 'array', label: 'array（数组）' },
  { value: 'object', label: 'object（对象）' },
]

/** 空列表时的表格空态文案。 */
const EMPTY_TEXT = '暂无参数，点击『+ 新增参数』添加'

export function ParamListEditor({ value, onChange, disabled }: ParamListEditorProps): JSX.Element {
  /** 行列表（rows + extra） */
  const [state, setState] = useState<{ rows: EditableParamRow[]; extra: Record<string, unknown> }>({
    rows: [],
    extra: {},
  })
  /** 序列化失败的原因（行内容保留，不回滚） */
  const [buildError, setBuildError] = useState<string | null>(null)
  /** {@code value} 解析失败的原因 */
  const [parseError, setParseError] = useState<string | null>(null)
  /** 用户正在手改的原文（{@code value} 解析失败时的兜底输入框内容） */
  const [rawOverride, setRawOverride] = useState<string | null>(null)
  /** 本组件最近一次回传出去的文本（用于识别「父级回声」） */
  const emittedRef = useRef<string | null>(null)
  /** 行标识自增器 */
  const idRef = useRef(0)

  /** 分配一个稳定的行标识。 */
  const nextRowId = (): string => {
    idRef.current += 1
    return `row-${idRef.current}`
  }

  // ── value 变化时重新解析 ──
  useEffect(() => {
    // ⚠️ 父级把我们刚回传的内容又送回来时**不再解析**：
    // 重新解析会把用户正在输入的原文格式抹平（枚举 "a, b" 被压成 "a,b"，
    // 正在输入的空格会被吃掉）。
    if (emittedRef.current !== null && value === emittedRef.current) return
    const parsed = parseParamList(value)
    if (parsed.ok) {
      setState({
        rows: parsed.spec.rows.map((row) => ({ ...row, __id: nextRowId() })),
        extra: parsed.spec.extra,
      })
      setParseError(null)
      setRawOverride(null)
      // ⚠️ 载入时也校验一次：让「参数名非法 / 默认值类型不对」这类问题**当场可见**，
      // 而不是等作者改了别的字段才暴露。
      const built = buildParamListSchema(parsed.spec)
      setBuildError(built.ok ? null : built.error)
    } else {
      setParseError(parsed.error)
      setRawOverride(value ?? '')
      setBuildError(null)
    }
  }, [value])

  /**
   * 提交一次编辑：更新本地行列表，并尝试序列化。
   *
   * ⚠️ 序列化失败时**不回传** —— 父级仍持有上一次合法文本，
   * 但行内容保留用户刚改的样子（不回滚），否则用户一改错就丢掉全部输入。
   */
  const commit = (next: { rows: EditableParamRow[]; extra: Record<string, unknown> }): void => {
    setState(next)
    const built = buildParamListSchema({ rows: next.rows, extra: next.extra })
    if (built.ok) {
      setBuildError(null)
      emittedRef.current = built.spec
      onChange?.(built.spec)
    } else {
      setBuildError(built.error)
    }
  }

  /** 改某一行的一个字段。 */
  const updateRow = (index: number, patch: Partial<EditableParamRow>): void => {
    const rows = state.rows.map((row, i) => (i === index ? { ...row, ...patch } : row))
    commit({ ...state, rows })
  }

  /** 新增一行（参数名留空由作者填写；类型默认 string）。 */
  const addRow = (): void => {
    commit({
      ...state,
      rows: [
        ...state.rows,
        {
          __id: nextRowId(),
          key: '',
          schema: {},
          type: 'string',
          required: false,
          description: '',
          defaultText: '',
          enumText: '',
        },
      ],
    })
  }

  /** 删除一行。 */
  const removeRow = (index: number): void => {
    commit({ ...state, rows: state.rows.filter((_, i) => i !== index) })
  }

  /**
   * 兜底原文输入框的编辑。
   *
   * ⚠️ 文本一旦变成合法 JSON 就**自动切回表格**（本地先解析一次），
   * 不必等父级把新文本再送回来。
   */
  const handleRawChange = (text: string): void => {
    setRawOverride(text)
    const parsed = parseParamList(text)
    if (parsed.ok) {
      setState({
        rows: parsed.spec.rows.map((row) => ({ ...row, __id: nextRowId() })),
        extra: parsed.spec.extra,
      })
      setParseError(null)
      setRawOverride(null)
      const built = buildParamListSchema(parsed.spec)
      setBuildError(built.ok ? null : built.error)
    } else {
      setParseError(parsed.error)
    }
    emittedRef.current = text
    onChange?.(text)
  }

  const columns: TableColumnsType<EditableParamRow> = [
    {
      title: '参数名',
      dataIndex: 'key',
      width: 180,
      render: (_value, record, index) => (
        <Input
          aria-label="参数名"
          size="small"
          value={record.key}
          disabled={disabled}
          onChange={(e) => updateRow(index, { key: e.target.value })}
        />
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 200,
      render: (_value, record, index) => (
        <Select
          size="small"
          value={record.type}
          options={TYPE_OPTIONS}
          disabled={disabled}
          onChange={(next) => updateRow(index, { type: next as ParamType })}
        />
      ),
    },
    {
      title: '必填',
      dataIndex: 'required',
      width: 80,
      render: (_value, record, index) => (
        <Checkbox
          aria-label="必填"
          checked={record.required}
          disabled={disabled}
          onChange={(e) => updateRow(index, { required: e.target.checked })}
        />
      ),
    },
    {
      title: '默认值',
      dataIndex: 'defaultText',
      width: 160,
      render: (_value, record, index) => (
        <Input
          aria-label="默认值"
          size="small"
          value={record.defaultText}
          disabled={disabled}
          onChange={(e) => updateRow(index, { defaultText: e.target.value })}
        />
      ),
    },
    {
      title: '说明',
      dataIndex: 'description',
      width: 200,
      render: (_value, record, index) => (
        <Input
          aria-label="说明"
          size="small"
          value={record.description}
          disabled={disabled}
          onChange={(e) => updateRow(index, { description: e.target.value })}
        />
      ),
    },
    {
      title: '枚举',
      dataIndex: 'enumText',
      width: 160,
      render: (_value, record, index) => (
        <Input
          aria-label="枚举"
          size="small"
          value={record.enumText}
          disabled={disabled}
          onChange={(e) => updateRow(index, { enumText: e.target.value })}
        />
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 80,
      render: (_value, _record, index) => (
        <Button
          type="text"
          danger
          size="small"
          disabled={disabled}
          onClick={() => removeRow(index)}
        >
          删除
        </Button>
      ),
    },
  ]

  // ── 兜底：value 不是合法 JSON 时，退化为原文输入框 ──
  if (parseError !== null) {
    return (
      <div>
        <Alert
          type="error"
          showIcon
          message="入参结构不是合法 JSON，无法解析为参数列表"
          description={parseError}
          style={{ marginBottom: 8 }}
        />
        <Input.TextArea
          aria-label="入参结构原文"
          rows={8}
          value={rawOverride ?? value ?? ''}
          disabled={disabled}
          onChange={(e) => handleRawChange(e.target.value)}
        />
      </div>
    )
  }

  return (
    <div>
      <div style={{ marginBottom: 8 }}>
        <Button onClick={addRow} disabled={disabled}>
          + 新增参数
        </Button>
      </div>
      {buildError !== null && (
        <Alert
          type="error"
          showIcon
          message="参数定义有误"
          description={buildError}
          style={{ marginBottom: 8 }}
        />
      )}
      <Table<EditableParamRow>
        size="small"
        pagination={false}
        // ⚠️ 用**行标识**做 key（不是参数名）：参数名在编辑过程中会变，
        // 用它做 key 会导致输入框被反复卸载重建（打字打到一半焦点丢失）。
        rowKey="__id"
        dataSource={state.rows}
        columns={columns}
        locale={{ emptyText: EMPTY_TEXT }}
      />
    </div>
  )
}

export default ParamListEditor
