import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Input, Select, Table } from 'antd'
import type { TableColumnsType } from 'antd'
import { buildPolicyList, parsePolicyList, type PolicyRow } from './paramList'

/**
 * 「参数策略」列表编辑器（**纯受控展示组件**）。
 *
 * <p><b>⚠️ 为什么 op 只有两个选项</b>：后端 {@code ParamPolicyEngine} 的
 * {@code Op} 是**白名单**（{@code in_allowlist} / {@code lte}），
 * 写第三个值会在**配置期**被拒 —— 那时理由已经填完、走到了审批，反馈太晚。
 * 所以下拉里只给这两个。
 *
 * <p><b>⚠️ value 的形状随 op 变</b>：{@code in_allowlist} 的 value 是**数组**，
 * {@code lte} 的 value 是**数值**。界面上两者都是一段文本，
 * 由 {@link buildPolicyList} 按 op 决定产出数组还是数字。
 *
 * <p><b>⚠️ 不用 {@code Form.Item}</b>：文本的归属权在父级，
 * 本组件只负责「文本 ↔ 策略行」的可视化编辑。
 */
export interface PolicyListEditorProps {
  /** 参数策略 JSON 文本（父级持有） */
  value?: string
  /** 策略变化且**可成功序列化**时回传新的 JSON 文本 */
  onChange?: (text: string) => void
  /** 禁用 */
  disabled?: boolean
}

/**
 * 编辑中的一行（{@link PolicyRow} + 一个**只用于 React key** 的稳定标识）。
 *
 * ⚠️ 为什么不能直接拿参数名当 key：参数名在编辑过程中会变（作者正在打字），
 * 用它做 key 会让输入框被反复卸载重建 —— 打字打到一半焦点就没了。
 * ⚠️ {@code __id} **不参与序列化**（{@code buildPolicyList} 只读
 * {@code param} / {@code op} / {@code value}），所以不会污染产出文本。
 */
interface EditablePolicyRow extends PolicyRow {
  __id: string
}

/**
 * op 下拉选项。
 *
 * ⚠️ 标签把后端语义写清楚：{@code in_allowlist} 的值是**数组**，
 * {@code lte} 的值是**数值** —— 混了就是「配置期直接拒」。
 */
const OP_OPTIONS: { value: PolicyRow['op']; label: string }[] = [
  { value: 'in_allowlist', label: 'in_allowlist（值必须在允许清单内）' },
  { value: 'lte', label: 'lte（数值不得大于阈值）' },
]

/** 空列表时的表格空态文案。 */
const EMPTY_TEXT = '暂无策略，点击『+ 新增策略』添加'

export function PolicyListEditor({ value, onChange, disabled }: PolicyListEditorProps): JSX.Element {
  /** 策略行列表 */
  const [rows, setRows] = useState<EditablePolicyRow[]>([])
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
    // ⚠️ 父级把我们刚回传的内容又送回来时不再解析：避免无谓的重置。
    if (emittedRef.current !== null && value === emittedRef.current) return
    const parsed = parsePolicyList(value)
    if (parsed.ok) {
      setRows(parsed.spec.map((row) => ({ ...row, __id: nextRowId() })))
      setParseError(null)
      setRawOverride(null)
      // ⚠️ 载入时也校验一次：重复的 (param, op) 必须在**载入当场**就指出来，
      // 否则作者会以为策略已经生效（后端对重复项是静默取最后一条）。
      const built = buildPolicyList(parsed.spec)
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
   * ⚠️ 序列化失败时**不回传**（父级仍持有上一次合法文本），
   * 但行内容保留用户刚改的样子（不回滚）。
   */
  const commit = (next: EditablePolicyRow[]): void => {
    setRows(next)
    const built = buildPolicyList(next)
    if (built.ok) {
      setBuildError(null)
      emittedRef.current = built.spec
      onChange?.(built.spec)
    } else {
      setBuildError(built.error)
    }
  }

  /** 改某一行的一个字段。 */
  const updateRow = (index: number, patch: Partial<EditablePolicyRow>): void => {
    commit(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)))
  }

  /** 新增一行（参数名与取值留空由作者填写；op 默认 in_allowlist）。 */
  const addRow = (): void => {
    commit([...rows, { __id: nextRowId(), param: '', op: 'in_allowlist', value: '' }])
  }

  /** 删除一行。 */
  const removeRow = (index: number): void => {
    commit(rows.filter((_, i) => i !== index))
  }

  /**
   * 兜底原文输入框的编辑。
   *
   * ⚠️ 文本一旦变成合法 JSON 就**自动切回表格**（本地先解析一次）。
   */
  const handleRawChange = (text: string): void => {
    setRawOverride(text)
    const parsed = parsePolicyList(text)
    if (parsed.ok) {
      setRows(parsed.spec.map((row) => ({ ...row, __id: nextRowId() })))
      setParseError(null)
      setRawOverride(null)
      const built = buildPolicyList(parsed.spec)
      setBuildError(built.ok ? null : built.error)
    } else {
      setParseError(parsed.error)
    }
    emittedRef.current = text
    onChange?.(text)
  }

  const columns: TableColumnsType<EditablePolicyRow> = [
    {
      title: '参数名',
      dataIndex: 'param',
      width: 200,
      render: (_value, record, index) => (
        <Input
          aria-label="参数名"
          size="small"
          value={record.param}
          disabled={disabled}
          onChange={(e) => updateRow(index, { param: e.target.value })}
        />
      ),
    },
    {
      title: '操作',
      dataIndex: 'op',
      width: 260,
      render: (_value, record, index) => (
        <Select
          size="small"
          value={record.op}
          options={OP_OPTIONS}
          disabled={disabled}
          onChange={(next) => updateRow(index, { op: next as PolicyRow['op'] })}
        />
      ),
    },
    {
      title: '取值',
      dataIndex: 'value',
      width: 220,
      render: (_value, record, index) => (
        <Input
          aria-label="取值"
          size="small"
          value={record.value}
          disabled={disabled}
          // ⚠️ 提示文案随 op 变：允许清单是「逗号分隔的多个值」，阈值是「一个数值」。
          placeholder={record.op === 'lte' ? '阈值（数值）' : '允许值，逗号分隔'}
          onChange={(e) => updateRow(index, { value: e.target.value })}
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
          message="参数策略不是合法 JSON，无法解析为策略列表"
          description={parseError}
          style={{ marginBottom: 8 }}
        />
        <Input.TextArea
          aria-label="参数策略原文"
          rows={6}
          value={rawOverride ?? value ?? ''}
          disabled={disabled}
          onChange={(e) => handleRawChange(e.target.value)}
        />
      </div>
    )
  }

  return (
    <div>
      <Alert
        type="info"
        showIcon
        message="参数策略只有两种操作"
        description="in_allowlist 的值是数组（逗号分隔的允许值）；lte 的值是数值（阈值）。参数名必须与已声明的参数一致，否则策略不会生效。"
        style={{ marginBottom: 8 }}
      />
      <div style={{ marginBottom: 8 }}>
        <Button onClick={addRow} disabled={disabled}>
          + 新增策略
        </Button>
      </div>
      {buildError !== null && (
        <Alert
          type="error"
          showIcon
          message="参数策略有误"
          description={buildError}
          style={{ marginBottom: 8 }}
        />
      )}
      <Table<EditablePolicyRow>
        size="small"
        pagination={false}
        // ⚠️ 用**行标识**做 key（不是参数名）：参数名在编辑过程中会变，
        // 用它做 key 会导致输入框被反复卸载重建（打字打到一半焦点丢失）。
        rowKey="__id"
        dataSource={rows}
        columns={columns}
        locale={{ emptyText: EMPTY_TEXT }}
      />
    </div>
  )
}

export default PolicyListEditor
