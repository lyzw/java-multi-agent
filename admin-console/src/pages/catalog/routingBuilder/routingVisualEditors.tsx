import type { CSSProperties } from 'react'
import {
  Alert,
  AutoComplete,
  Button,
  Card,
  Col,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Switch,
  Tabs,
  Tag,
  Typography,
  App as AntApp,
} from 'antd'
import {
  CopyOutlined,
  DeleteOutlined,
  DownOutlined,
  PlusOutlined,
  UpOutlined,
} from '@ant-design/icons'
import {
  CTX_LABELS,
  FIELD_TYPE_LABELS,
  OPERATOR_GROUPS,
  OPERATOR_LABELS,
  PREDICATE_CTX_NAMES,
  VIA_LABELS,
  describePredicate,
  makeField,
  makePredicate,
  makeProbe,
  makeRule,
  makeSample,
  pretty,
  serializeInputSchema,
  serializeProbes,
  serializeRules,
  serializeSamples,
  validateDraft,
} from './routingVisualModel'
import type {
  CtxModel,
  FieldType,
  FlatField,
  InputValues,
  PredicateKind,
  PredicateNode,
  ProbeModel,
  RuleModel,
  SampleModel,
  SampleVia,
  SchemaField,
  ScalarType,
  ValueModel,
  VisualDraft,
} from './routingVisualModel'

const { Text, Paragraph } = Typography

const flexRow: CSSProperties = { display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }
const smallLabel: CSSProperties = { display: 'block', fontSize: 12, color: '#6b7280', marginBottom: 4 }
const nestedBox: CSSProperties = {
  marginTop: 8, marginLeft: 12, paddingLeft: 12, borderLeft: '2px solid #e5e7eb',
}

/* ───────────────────────── 输入词汇表 ───────────────────────── */

function FieldList({
  fields,
  onChange,
  depth = 0,
}: {
  fields: SchemaField[]
  onChange: (next: SchemaField[]) => void
  depth?: number
}): JSX.Element {
  const patch = (key: string, changes: Partial<SchemaField>): void => {
    onChange(fields.map((f) => (f.key === key ? { ...f, ...changes } : f)))
  }
  const remove = (key: string): void => {
    onChange(fields.filter((f) => f.key !== key))
  }
  const add = (): void => {
    onChange([...fields, makeField()])
  }

  return (
    <div style={depth > 0 ? nestedBox : undefined}>
      {fields.map((f) => {
        const pathSuffix = f.name.trim() ? `in.${f.name.trim()}` : '待命名'
        return (
          <div key={f.key} style={{ marginBottom: 10 }}>
            <div style={flexRow}>
              <Input
                aria-label="字段名"
                size="small"
                style={{ width: 180 }}
                value={f.name}
                placeholder="字段名，如 topic"
                onChange={(e) => patch(f.key, { name: e.target.value })}
              />
              <Select<FieldType>
                size="small"
                style={{ width: 150 }}
                value={f.type}
                onChange={(type) => patch(f.key, { type })}
                options={(Object.keys(FIELD_TYPE_LABELS) as FieldType[]).map((type) => ({
                  value: type, label: FIELD_TYPE_LABELS[type],
                }))}
              />
              <Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
                必填
              </Text>
              <Switch size="small" checked={f.required} onChange={(required) => patch(f.key, { required })} />
              <Text code style={{ fontSize: 12 }}>{pathSuffix}</Text>
              <Button
                type="text"
                size="small"
                danger
                icon={<DeleteOutlined />}
                aria-label={`删除字段 ${f.name || ''}`}
                onClick={() => remove(f.key)}
              />
            </div>

            {(f.type === 'integer' || f.type === 'number' || f.type === 'boolean' || f.type === 'string') && (
              <div style={{ marginTop: 6, marginLeft: 4 }}>
                <Text style={{ fontSize: 12 }} type="secondary">可选枚举值（回车添加，留空表示不限）</Text>
                <Select
                  mode="tags"
                  size="small"
                  tokenSeparators={[',']}
                  style={{ width: '100%', maxWidth: 520, marginTop: 4 }}
                  value={f.enumValues}
                  placeholder="如 order_status, refund"
                  onChange={(values) => patch(f.key, { enumValues: values as string[] })}
                />
              </div>
            )}

            {f.type === 'array' && (
              <div style={{ marginTop: 6, marginLeft: 4 }}>
                <Text style={{ fontSize: 12 }} type="secondary">数组元素类型：</Text>
                <Select<ScalarType>
                  size="small"
                  style={{ width: 140 }}
                  value={f.itemType}
                  onChange={(itemType) => patch(f.key, { itemType })}
                  options={[
                    { value: 'string', label: '文本' },
                    { value: 'integer', label: '整数' },
                    { value: 'number', label: '数字' },
                    { value: 'boolean', label: '布尔' },
                  ]}
                />
              </div>
            )}

            {f.type === 'object' && (
              <>
                <FieldList
                  fields={f.children}
                  depth={depth + 1}
                  onChange={(children) => patch(f.key, { children })}
                />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  子字段会展开为 <Text code>{`${f.name || '对象'}.子字段`}</Text> 供规则引用
                </Text>
              </>
            )}
          </div>
        )
      })}
      <Button size="small" icon={<PlusOutlined />} onClick={add}>
        {depth > 0 ? '新增子字段' : '新增输入字段'}
      </Button>
    </div>
  )
}

export function SchemaEditor({
  value,
  onChange,
}: {
  value: SchemaField[]
  onChange: (next: SchemaField[]) => void
}): JSX.Element {
  return (
    <div>
      <Paragraph type="secondary" style={{ marginBottom: 12 }}>
        先告诉引擎「输入里有哪些字段」，后面的规则、样本、探针都从这里选。字段名只允许字母、数字、下划线，且必须以字母开头。
      </Paragraph>
      <FieldList fields={value} onChange={onChange} />
    </div>
  )
}

/* ───────────────────────── 条件里的「值」 ───────────────────────── */

function ValueEditor({
  value,
  onChange,
  fields,
  label,
  onlyStringLiteral = false,
  arrayFieldsOnly = false,
}: {
  value: ValueModel
  onChange: (next: ValueModel) => void
  fields: FlatField[]
  label?: string
  onlyStringLiteral?: boolean
  arrayFieldsOnly?: boolean
}): JSX.Element {
  const patch = (changes: Partial<ValueModel>): void => onChange({ ...value, ...changes })

  if (onlyStringLiteral) {
    return (
      <div>
        {label && <span style={smallLabel}>{label}</span>}
        <Input
          size="small"
          style={{ width: 260 }}
          value={value.literal}
          placeholder="正则表达式，如 SO-\\d{6}"
          onChange={(e) => patch({ literal: e.target.value, source: 'literal', literalType: 'string' })}
        />
      </div>
    )
  }

  const candidates = arrayFieldsOnly ? fields.filter((f) => f.type === 'array') : fields

  return (
    <div>
      {label && <span style={smallLabel}>{label}</span>}
      <div style={flexRow}>
        <Select
          size="small"
          style={{ width: 110 }}
          value={value.source}
          onChange={(source) => patch({ source: source as ValueModel['source'] })}
          options={[
            { value: 'input', label: '输入字段' },
            { value: 'ctx', label: '上下文' },
            { value: 'literal', label: '固定值' },
            { value: 'escaped', label: '转义字面量' },
          ]}
        />

        {value.source === 'input' && (
          <Select
            size="small"
            showSearch
            style={{ width: 240 }}
            value={value.path || undefined}
            placeholder={candidates.length ? '选择输入字段' : '请先声明输入字段'}
            onChange={(path) => patch({ path: path as string })}
            options={candidates.map((f) => ({
              value: f.path,
              label: `in.${f.path}（${FIELD_TYPE_LABELS[f.type]}）`,
            }))}
          />
        )}

        {value.source === 'ctx' && (
          <Select
            size="small"
            style={{ width: 240 }}
            value={value.ctx}
            onChange={(ctx) => patch({ ctx: ctx as string })}
            options={PREDICATE_CTX_NAMES.map((name) => ({ value: name, label: CTX_LABELS[name] ?? name }))}
          />
        )}

        {value.source === 'literal' && (
          <>
            <Select<ScalarType>
              size="small"
              style={{ width: 100 }}
              value={value.literalType}
              onChange={(literalType) => patch({ literalType, literal: '' })}
              options={[
                { value: 'string', label: '文本' },
                { value: 'integer', label: '整数' },
                { value: 'number', label: '数字' },
                { value: 'boolean', label: '布尔' },
              ]}
            />
            {value.literalType === 'boolean' ? (
              <Switch
                size="small"
                checked={value.literal === 'true'}
                onChange={(checked) => patch({ literal: checked ? 'true' : 'false' })}
              />
            ) : (
              <Input
                size="small"
                style={{ width: 160 }}
                value={value.literal}
                placeholder={value.literalType === 'string' ? '输入文本' : '输入数字'}
                onChange={(e) => patch({ literal: e.target.value })}
              />
            )}
          </>
        )}

        {value.source === 'escaped' && (
          <Input
            size="small"
            style={{ width: 220 }}
            value={value.literal}
            placeholder="长得像 in.xxx 的字面文本"
            onChange={(e) => patch({ literal: e.target.value })}
          />
        )}
      </div>
    </div>
  )
}

/* ───────────────────────── 条件（递归） ───────────────────────── */

function ConditionEditor({
  value,
  onChange,
  fields,
  depth = 0,
  onRemove,
}: {
  value: PredicateNode
  onChange: (next: PredicateNode) => void
  fields: FlatField[]
  depth?: number
  onRemove?: () => void
}): JSX.Element {
  const preferredPath = 'left' in value ? value.left.path : undefined
  const changeOperator = (kind: PredicateKind): void => {
    onChange(makePredicate(kind, preferredPath))
  }

  let body: JSX.Element
  switch (value.kind) {
    case 'const':
      body = (
        <Space size={8}>
          <Switch
            size="small"
            checked={value.value}
            onChange={(v) => onChange({ ...value, value: v })}
          />
          <Text style={{ fontSize: 12 }}>
            {value.value ? '恒为成立（可作为兜底规则）' : '恒不成立'}
          </Text>
        </Space>
      )
      break
    case 'and':
    case 'or':
      body = (
        <div>
          {value.children.map((child, i) => (
            <div key={child.key} style={{ marginBottom: 8 }}>
              <div style={{ ...flexRow, marginBottom: 4 }}>
                <Tag color={value.kind === 'and' ? 'blue' : 'purple'}>
                  {value.kind === 'and' ? '且' : '或'} {i + 1}
                </Tag>
                <Button
                  type="text"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={() => onChange({
                    ...value,
                    children: value.children.filter((c) => c.key !== child.key),
                  })}
                />
              </div>
              <ConditionEditor
                value={child}
                fields={fields}
                depth={depth + 1}
                onChange={(next) => onChange({
                  ...value,
                  children: value.children.map((c) => (c.key === child.key ? next : c)),
                })}
              />
            </div>
          ))}
          <Button
            size="small"
            icon={<PlusOutlined />}
            onClick={() => onChange({
              ...value,
              children: [...value.children, makePredicate('eq', fields[0]?.path)],
            })}
          >
            增加子条件
          </Button>
        </div>
      )
      break
    case 'not':
      body = (
        <div>
          <Text type="secondary" style={{ fontSize: 12 }}>对下面的条件取反</Text>
          {value.child ? (
            <ConditionEditor
              value={value.child}
              fields={fields}
              depth={depth + 1}
              onRemove={() => onChange({ ...value, child: null })}
              onChange={(child) => onChange({ ...value, child })}
            />
          ) : (
            <Button
              size="small"
              icon={<PlusOutlined />}
              onClick={() => onChange({ ...value, child: makePredicate('eq', fields[0]?.path) })}
            >
              设置被取反的条件
            </Button>
          )}
        </div>
      )
      break
    case 'eq':
    case 'startsWith':
    case 'endsWith':
    case 'contains':
      body = (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <ValueEditor
            label="左侧：要判断的值"
            value={value.left}
            fields={fields}
            onChange={(left) => onChange({ ...value, left })}
          />
          <ValueEditor
            label="右侧：拿来比较的值"
            value={value.right}
            fields={fields}
            onChange={(right) => onChange({ ...value, right })}
          />
        </Space>
      )
      break
    case 're':
      body = (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <ValueEditor
            label="左侧：要判断的文本"
            value={value.left}
            fields={fields}
            onChange={(left) => onChange({ ...value, left })}
          />
          <ValueEditor
            label="右侧：正则表达式（完整匹配，≤128 字符）"
            value={value.right}
            fields={fields}
            onlyStringLiteral
            onChange={(right) => onChange({ ...value, right })}
          />
        </Space>
      )
      break
    case 'in':
    case 'anyOf':
      body = (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <ValueEditor
            label={value.kind === 'anyOf' ? '左侧：数组字段' : '左侧：要判断的值'}
            value={value.left}
            fields={fields}
            arrayFieldsOnly={value.kind === 'anyOf'}
            onChange={(left) => onChange({ ...value, left })}
          />
          <div>
            <span style={smallLabel}>右侧：取值列表（回车添加，最多 64 项）</span>
            <div style={flexRow}>
              <Select
                mode="tags"
                size="small"
                tokenSeparators={[',']}
                style={{ width: 300 }}
                value={value.items}
                placeholder="输入取值后回车"
                onChange={(items) => onChange({ ...value, items: items as string[] })}
              />
              <Select<ScalarType>
                size="small"
                style={{ width: 110 }}
                value={value.itemType}
                onChange={(itemType) => onChange({ ...value, itemType })}
                options={[
                  { value: 'string', label: '文本值' },
                  { value: 'integer', label: '整数值' },
                  { value: 'number', label: '数字值' },
                  { value: 'boolean', label: '布尔值' },
                ]}
              />
            </div>
          </div>
        </Space>
      )
      break
    case 'null':
      body = (
        <ValueEditor
          label="判断哪个字段为空（未出现也算空）"
          value={value.left}
          fields={fields}
          onChange={(left) => onChange({ ...value, left })}
        />
      )
      break
  }

  return (
    <div
      style={{
        border: '1px solid #e5e7eb',
        borderRadius: 8,
        padding: 12,
        background: depth % 2 === 0 ? '#ffffff' : '#fafbfc',
      }}
    >
      <div style={flexRow}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {depth === 0 ? '条件' : `子条件 · 第 ${depth + 1} 层`}
        </Text>
        <Select<PredicateKind>
          size="small"
          style={{ width: 260 }}
          value={value.kind}
          onChange={changeOperator}
          options={OPERATOR_GROUPS.map((group) => ({
            label: group.label,
            options: group.options.map((kind) => ({ value: kind, label: OPERATOR_LABELS[kind] })),
          }))}
        />
        {onRemove && (
          <Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={onRemove} />
        )}
      </div>
      <div style={{ marginTop: 10 }}>{body}</div>
    </div>
  )
}

/* ───────────────────────── 规则列表 ───────────────────────── */

export function RuleEditor({
  value,
  onChange,
  fields,
  knownTargets,
}: {
  value: RuleModel[]
  onChange: (next: RuleModel[]) => void
  fields: FlatField[]
  knownTargets: string[]
}): JSX.Element {
  const patch = (key: string, changes: Partial<RuleModel>): void => {
    onChange(value.map((r) => (r.key === key ? { ...r, ...changes } : r)))
  }
  const move = (index: number, delta: number): void => {
    const target = index + delta
    if (target < 0 || target >= value.length) return
    const next = [...value]
    const a = next[index]
    const b = next[target]
    if (!a || !b) return
    next[index] = b
    next[target] = a
    onChange(next)
  }

  return (
    <div>
      <Paragraph type="secondary" style={{ marginBottom: 12 }}>
        规则<b>从上到下依次判断</b>，第一条命中即停止。可以用「且 / 或 / 非」组合条件；「求值异常处置」决定规则算错时跳过还是中止路由。
      </Paragraph>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {value.map((r, i) => (
          <Card
            key={r.key}
            size="small"
            title={
              <Space size={8}>
                <Tag color="blue">#{i + 1}</Tag>
                <Text strong>{r.ruleId.trim() || '未命名规则'}</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {describePredicate(r.when)}
                </Text>
              </Space>
            }
            extra={
              <Space size={2}>
                <Button
                  type="text" size="small" icon={<UpOutlined />}
                  aria-label="上移规则" disabled={i === 0} onClick={() => move(i, -1)}
                />
                <Button
                  type="text" size="small" icon={<DownOutlined />}
                  aria-label="下移规则" disabled={i === value.length - 1} onClick={() => move(i, 1)}
                />
                <Button
                  type="text" size="small" danger icon={<DeleteOutlined />}
                  aria-label="删除规则"
                  onClick={() => onChange(value.filter((x) => x.key !== r.key))}
                />
              </Space>
            }
          >
            <Row gutter={[12, 8]}>
              <Col xs={24} md={8}>
                <span style={smallLabel}>规则标识（唯一）</span>
                <Input
                  size="small" value={r.ruleId} placeholder="如 r-order-status"
                  onChange={(e) => patch(r.key, { ruleId: e.target.value })}
                />
              </Col>
              <Col xs={24} md={8}>
                <span style={smallLabel}>命中目标（子 Agent 标识）</span>
                <AutoComplete
                  size="small" style={{ width: '100%' }} value={r.target}
                  placeholder="如 order-query"
                  options={knownTargets.map((t) => ({ value: t }))}
                  onChange={(target) => patch(r.key, { target })}
                />
              </Col>
              <Col xs={24} md={8}>
                <span style={smallLabel}>求值异常处置</span>
                <Select
                  size="small" style={{ width: '100%' }} value={r.onError}
                  onChange={(onError) => patch(r.key, { onError: onError as RuleModel['onError'] })}
                  options={[
                    { value: 'SKIP', label: 'SKIP · 跳过本条，继续判断' },
                    { value: 'REJECT', label: 'REJECT · 中止整个路由' },
                  ]}
                />
              </Col>
            </Row>
            <div style={{ marginTop: 10, marginBottom: 10 }}>
              <span style={smallLabel}>备注（只写给人看，不进模型提示词）</span>
              <Input
                size="small" value={r.note} placeholder="这条规则解决什么场景"
                onChange={(e) => patch(r.key, { note: e.target.value })}
              />
            </div>
            <ConditionEditor
              value={r.when}
              fields={fields}
              onChange={(when) => patch(r.key, { when })}
            />
          </Card>
        ))}
      </Space>
      <Button
        style={{ marginTop: 12 }}
        icon={<PlusOutlined />}
        disabled={value.length >= 64}
        onClick={() => onChange([...value, makeRule(value.length, fields[0]?.path)])}
      >
        新增规则{value.length >= 64 ? '（已达 64 条上限）' : ''}
      </Button>
    </div>
  )
}

/* ───────────────────────── 样本 / 探针共用的输入与 ctx ───────────────────────── */

export function InputFieldsEditor({
  value,
  onChange,
  fields,
}: {
  value: InputValues
  onChange: (next: InputValues) => void
  fields: FlatField[]
}): JSX.Element {
  const set = (path: string, v: unknown): void => {
    const next = { ...value }
    if (v === undefined || v === '') delete next[path]
    else next[path] = v
    onChange(next)
  }

  const usable = fields.filter((f) => f.type !== 'object')
  if (usable.length === 0) {
    return <Text type="secondary">请先在「输入字段」里声明字段。</Text>
  }

  return (
    <Row gutter={[12, 8]}>
      {usable.map((f) => {
        const raw = value[f.path]
        let control: JSX.Element
        if (f.enumValues.length) {
          control = (
            <Select
              allowClear size="small" style={{ width: '100%' }}
              value={raw === undefined ? undefined : String(raw)}
              placeholder="未设置"
              options={f.enumValues.map((v) => ({ value: v, label: v }))}
              onChange={(v) => set(f.path, v)}
            />
          )
        } else if (f.type === 'string') {
          control = (
            <Input
              size="small" value={raw === undefined ? '' : String(raw)}
              placeholder="留空 = 该字段不出现"
              onChange={(e) => set(f.path, e.target.value)}
            />
          )
        } else if (f.type === 'integer' || f.type === 'number') {
          control = (
            <InputNumber
              size="small" style={{ width: '100%' }}
              value={typeof raw === 'number' ? raw : (raw === undefined ? null : Number(raw))}
              placeholder="留空 = 该字段不出现"
              onChange={(v) => set(f.path, v ?? undefined)}
            />
          )
        } else if (f.type === 'boolean') {
          control = (
            <Select
              allowClear size="small" style={{ width: '100%' }}
              value={raw === undefined ? undefined : String(raw)}
              placeholder="未设置"
              options={[{ value: 'true', label: '是 true' }, { value: 'false', label: '否 false' }]}
              onChange={(v) => set(f.path, v === undefined ? undefined : v === 'true')}
            />
          )
        } else {
          const items = Array.isArray(raw) ? raw.map(String) : []
          control = (
            <Select
              mode="tags" size="small" style={{ width: '100%' }}
              tokenSeparators={[',']} value={items}
              placeholder="回车添加元素"
              onChange={(items) => set(f.path, items as string[])}
            />
          )
        }
        return (
          <Col key={f.path} xs={24} sm={12} lg={8}>
            <span style={smallLabel}>{f.path} · {FIELD_TYPE_LABELS[f.type]}</span>
            {control}
          </Col>
        )
      })}
    </Row>
  )
}

export function CtxEditor({
  value,
  onChange,
}: {
  value: CtxModel
  onChange: (next: CtxModel) => void
}): JSX.Element {
  const patch = (changes: Partial<CtxModel>): void => onChange({ ...value, ...changes })
  return (
    <Row gutter={[12, 8]}>
      <Col xs={24} sm={12} lg={8}>
        <span style={smallLabel}>渠道 channel</span>
        <Input size="small" value={value.channel ?? ''} placeholder="如 app / web"
          onChange={(e) => patch({ channel: e.target.value })} />
      </Col>
      <Col xs={24} sm={12} lg={8}>
        <span style={smallLabel}>语言 locale</span>
        <Input size="small" value={value.locale ?? ''} placeholder="如 zh-CN"
          onChange={(e) => patch({ locale: e.target.value })} />
      </Col>
      <Col xs={24} sm={12} lg={8}>
        <span style={smallLabel}>回放日 now.utcDate</span>
        <Input size="small" value={value['now.utcDate'] ?? ''} placeholder="yyyy-MM-dd"
          onChange={(e) => patch({ 'now.utcDate': e.target.value })} />
      </Col>
      <Col xs={24} sm={12} lg={8}>
        <span style={smallLabel}>第几次尝试 run.attempt</span>
        <InputNumber size="small" min={1} style={{ width: '100%' }} value={value['run.attempt'] ?? null}
          onChange={(v) => patch({ 'run.attempt': v })} />
      </Col>
      <Col xs={24} sm={12} lg={8}>
        <span style={smallLabel}>委派深度 run.depth</span>
        <InputNumber size="small" min={0} style={{ width: '100%' }} value={value['run.depth'] ?? null}
          onChange={(v) => patch({ 'run.depth': v })} />
      </Col>
    </Row>
  )
}

/* ───────────────────────── 回放样本 ───────────────────────── */

export function SampleEditor({
  value,
  onChange,
  fields,
  knownTargets,
}: {
  value: SampleModel[]
  onChange: (next: SampleModel[]) => void
  fields: FlatField[]
  knownTargets: string[]
}): JSX.Element {
  const patch = (key: string, changes: Partial<SampleModel>): void => {
    onChange(value.map((s) => (s.key === key ? { ...s, ...changes } : s)))
  }

  return (
    <div>
      <Paragraph type="secondary" style={{ marginBottom: 12 }}>
        样本用于<b>发布期回放守卫</b>：每条规则的目标至少要有一条「规则命中」样本，否则发布会被拦下。留空的输入字段视为字段未出现。
      </Paragraph>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {value.map((s, i) => (
          <Card
            key={s.key}
            size="small"
            title={<Text strong>{s.label.trim() || `样本 ${i + 1}`}</Text>}
            extra={
              <Button
                type="text" size="small" danger icon={<DeleteOutlined />}
                aria-label="删除样本"
                onClick={() => onChange(value.filter((x) => x.key !== s.key))}
              />
            }
          >
            <Row gutter={[12, 8]}>
              <Col xs={24} md={8}>
                <span style={smallLabel}>样本名称</span>
                <Input size="small" value={s.label} onChange={(e) => patch(s.key, { label: e.target.value })} />
              </Col>
              <Col xs={24} md={8}>
                <span style={smallLabel}>期望目标</span>
                <AutoComplete
                  size="small" style={{ width: '100%' }} value={s.expectTarget}
                  placeholder="与命中规则的 target 一致"
                  options={knownTargets.map((t) => ({ value: t }))}
                  onChange={(expectTarget) => patch(s.key, { expectTarget })}
                />
              </Col>
              <Col xs={24} md={8}>
                <span style={smallLabel}>命中方式</span>
                <Select<SampleVia>
                  size="small" style={{ width: '100%' }} value={s.via}
                  onChange={(via) => patch(s.key, { via })}
                  options={(Object.keys(VIA_LABELS) as SampleVia[]).map((via) => ({
                    value: via, label: VIA_LABELS[via],
                  }))}
                />
              </Col>
            </Row>
            <div style={{ marginTop: 10 }}>
              <Text strong style={{ fontSize: 12 }}>输入字段</Text>
              <div style={{ marginTop: 6 }}>
                <InputFieldsEditor
                  value={s.input}
                  fields={fields}
                  onChange={(input) => patch(s.key, { input })}
                />
              </div>
            </div>
            <div style={{ marginTop: 12 }}>
              <Text strong style={{ fontSize: 12 }}>上下文覆盖（可选）</Text>
              <div style={{ marginTop: 6 }}>
                <CtxEditor value={s.ctx} onChange={(ctx) => patch(s.key, { ctx })} />
              </div>
            </div>
          </Card>
        ))}
      </Space>
      <Button
        style={{ marginTop: 12 }} icon={<PlusOutlined />}
        onClick={() => onChange([...value, makeSample(value.length)])}
      >
        新增样本
      </Button>
    </div>
  )
}

/* ───────────────────────── 试跑探针 ───────────────────────── */

export function ProbeEditor({
  value,
  onChange,
  fields,
}: {
  value: ProbeModel[]
  onChange: (next: ProbeModel[]) => void
  fields: FlatField[]
}): JSX.Element {
  const patch = (key: string, changes: Partial<ProbeModel>): void => {
    onChange(value.map((p) => (p.key === key ? { ...p, ...changes } : p)))
  }

  return (
    <div>
      <Paragraph type="secondary" style={{ marginBottom: 12 }}>
        探针只在本次试跑中存在，<b>不写入策略、不影响内容指纹</b>。用它看看某条输入实际会走到哪个目标。最多 20 条。
      </Paragraph>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {value.map((p, i) => (
          <Card
            key={p.key}
            size="small"
            title={<Text strong>{p.label.trim() || `探针 ${i + 1}`}</Text>}
            extra={
              <Button
                type="text" size="small" danger icon={<DeleteOutlined />}
                aria-label="删除探针"
                onClick={() => onChange(value.filter((x) => x.key !== p.key))}
              />
            }
          >
            <Row gutter={[12, 8]}>
              <Col xs={24} md={12}>
                <span style={smallLabel}>探针名称</span>
                <Input size="small" value={p.label} onChange={(e) => patch(p.key, { label: e.target.value })} />
              </Col>
              <Col xs={24} md={12}>
                <span style={smallLabel}>模拟模型候选（RULE_FIRST / SEMANTIC 用）</span>
                <Input size="small" value={p.modelCandidate} placeholder="可留空"
                  onChange={(e) => patch(p.key, { modelCandidate: e.target.value })} />
              </Col>
            </Row>
            <div style={{ marginTop: 10 }}>
              <Text strong style={{ fontSize: 12 }}>输入字段</Text>
              <div style={{ marginTop: 6 }}>
                <InputFieldsEditor
                  value={p.input}
                  fields={fields}
                  onChange={(input) => patch(p.key, { input })}
                />
              </div>
            </div>
            <div style={{ marginTop: 12 }}>
              <Text strong style={{ fontSize: 12 }}>上下文覆盖（可选）</Text>
              <div style={{ marginTop: 6 }}>
                <CtxEditor value={p.ctx} onChange={(ctx) => patch(p.key, { ctx })} />
              </div>
            </div>
          </Card>
        ))}
      </Space>
      <Button
        style={{ marginTop: 12 }} icon={<PlusOutlined />} disabled={value.length >= 20}
        onClick={() => onChange([...value, makeProbe(value.length)])}
      >
        新增探针{value.length >= 20 ? '（已达 20 条上限）' : ''}
      </Button>
    </div>
  )
}

/* ───────────────────────── 实时预览 / 校验侧栏 ───────────────────────── */

export function PreviewPanel({ draft }: { draft: VisualDraft }): JSX.Element {
  const { message } = AntApp.useApp()
  const report = validateDraft(draft)
  const blocks = [
    { key: 'schema', label: '输入词汇表', text: pretty(serializeInputSchema(draft)) },
    { key: 'evaluation', label: '规则列表', text: pretty(serializeRules(draft)) },
    { key: 'samples', label: '回放样本', text: pretty(serializeSamples(draft)) },
    { key: 'probes', label: '试跑探针', text: pretty(serializeProbes(draft)) },
  ]
  const copy = async (text: string): Promise<void> => {
    try {
      await navigator.clipboard.writeText(text)
      message.success('已复制生成的 JSON')
    } catch {
      message.error('复制失败，请手动选中复制')
    }
  }

  return (
    <Card
      size="small"
      title="实时预览与校验"
      style={{ position: 'sticky', top: 16 }}
      extra={<Tag color={report.errors.length ? 'error' : 'success'}>
        {report.errors.length ? `${report.errors.length} 项待修正` : '结构校验通过'}
      </Tag>}
    >
      {report.errors.length > 0 ? (
        <Alert
          type="error"
          showIcon
          message="提交前请先修正以下问题"
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {report.errors.map((e, i) => <li key={`${i}-${e}`} style={{ fontSize: 12 }}>{e}</li>)}
            </ul>
          }
        />
      ) : (
        <Alert
          type="success"
          showIcon
          message="结构自检通过"
          description="文法上限、语义守卫与准入仍由后端判定；点「试跑」获取真实结论。"
        />
      )}

      {report.warnings.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 8 }}
          message={`${report.warnings.length} 条提示（不阻止试跑）`}
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {report.warnings.map((w, i) => <li key={`${i}-${w}`} style={{ fontSize: 12 }}>{w}</li>)}
            </ul>
          }
        />
      )}

      <Tabs
        size="small"
        style={{ marginTop: 12 }}
        items={blocks.map((b) => ({
          key: b.key,
          label: b.label,
          children: (
            <div>
              <Button size="small" icon={<CopyOutlined />} onClick={() => void copy(b.text)}>
                复制 JSON
              </Button>
              <pre
                style={{
                  marginTop: 8, maxHeight: 360, overflow: 'auto', background: '#f6f7f9',
                  padding: 12, borderRadius: 6, fontSize: 11, lineHeight: 1.5,
                }}
              >
                {b.text}
              </pre>
            </div>
          ),
        }))}
      />
      <Text type="secondary" style={{ fontSize: 11 }}>
        预览即最终提交内容；条件、字段都由上方控件生成，前端不做谓词求值。
      </Text>
    </Card>
  )
}
