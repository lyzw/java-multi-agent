/**
 * 路由策略「可视化配置」的数据模型与序列化层。
 *
 * <p>设计边界（与后端 `agent-routing` 的既有约定一致）：
 * - 这里只做<b>结构</b>建模与序列化，不做谓词求值（不复制一份求值器）；
 * - 输出的四段文本（inputSchema / evaluation / sampleSet / probes）必须是后端
 *   `PolicyCodec` 能解析的<b>精确文法</b>：单键谓词节点、位置数组、封闭算子集；
 * - 文法、上限与守卫 V1~V10 的唯一真相仍在后端，本文件的校验只用于「提交前给提示」。
 *
 * <p>⚠️ 可视化编辑会把结构化模型重新序列化为 JSON 文本（缩进 2 空格）。
 * 这是「用户确实通过界面改过配置」的合理代价；配置者若一段都没动，
 * 页面不会重写它（只在对应编辑器被实际修改时才更新该段文本）。
 */

/* ───────────────────────── 类型 ───────────────────────── */

export type ScalarType = 'string' | 'integer' | 'number' | 'boolean'
export type FieldType = ScalarType | 'array' | 'object'

/** 输入词汇表的一个字段（对象字段可递归包含子字段）。 */
export interface SchemaField {
  key: string
  name: string
  type: FieldType
  required: boolean
  /** 枚举值（原始文本，按字段类型解析） */
  enumValues: string[]
  /** array 的元素类型 */
  itemType: ScalarType
  children: SchemaField[]
}

/** 展开后的字段路径（in.* 引用用）。 */
export interface FlatField {
  path: string
  type: FieldType
  itemType: ScalarType
  enumValues: string[]
  required: boolean
}

/** 谓词里一个值位置的来源。 */
export type ValueSource = 'input' | 'ctx' | 'literal' | 'escaped'

export interface ValueModel {
  source: ValueSource
  /** source=input：不含 `in.` 前缀的点分路径 */
  path: string
  /** source=ctx：谓词里的写法，如 `ctx.channel` / `ctx.run.attempt` */
  ctx: string
  literalType: ScalarType
  /** 原始文本（数字/布尔在此保存输入文本，提交前解析） */
  literal: string
}

export type PredicateKind =
  | 'const' | 'and' | 'or' | 'not'
  | 'eq' | 'in' | 'null' | 'startsWith' | 'endsWith' | 'contains' | 'anyOf' | 're'

/** 谓词树节点——每种算子一个独立分支，便于 TS 穷尽检查。 */
export type PredicateNode =
  | { key: string; kind: 'const'; value: boolean }
  | { key: string; kind: 'and' | 'or'; children: PredicateNode[] }
  | { key: string; kind: 'not'; child: PredicateNode | null }
  | { key: string; kind: 'eq' | 'startsWith' | 'endsWith' | 'contains' | 're'; left: ValueModel; right: ValueModel }
  | { key: string; kind: 'in' | 'anyOf'; left: ValueModel; items: string[]; itemType: ScalarType }
  | { key: string; kind: 'null'; left: ValueModel }

export interface RuleModel {
  key: string
  ruleId: string
  target: string
  onError: 'SKIP' | 'REJECT'
  note: string
  when: PredicateNode
}

/** 样本/探针的输入：`字段路径 -> 值`；缺键 = 该字段不出现。 */
export type InputValues = Record<string, unknown>

/** ctx 覆盖项：键名用线上线名（`run.attempt` 等带点）。 */
export interface CtxModel {
  channel?: string
  locale?: string
  'run.attempt'?: number | null
  'run.depth'?: number | null
  'now.utcDate'?: string
}

export type SampleVia = 'RULE_MATCH' | 'SEMANTIC_MATCH' | 'DEFAULT_TARGET' | 'NO_MATCH_REJECTED'

export interface SampleModel {
  key: string
  label: string
  input: InputValues
  via: SampleVia
  expectTarget: string
  ctx: CtxModel
}

export interface ProbeModel {
  key: string
  label: string
  input: InputValues
  modelCandidate: string
  ctx: CtxModel
}

/** 可视化编辑器的完整草稿（策略基础字段 + 四段结构化内容）。 */
export interface VisualDraft {
  policyId: string
  mode: 'RULES_ONLY' | 'RULE_FIRST' | 'SEMANTIC'
  noMatchAction: 'REJECT' | 'DEFAULT_TARGET'
  onConflict: 'RULE_WINS' | 'CANDIDATE_WINS' | undefined
  defaultTarget: string
  fields: SchemaField[]
  rules: RuleModel[]
  samples: SampleModel[]
  probes: ProbeModel[]
}

/* ───────────────────────── 常量 ───────────────────────── */

/** `ctx.*` 白名单（tenantId 由服务端注入，永远不出现在编辑器里）。 */
export const PREDICATE_CTX_NAMES = [
  'ctx.channel', 'ctx.locale', 'ctx.run.attempt', 'ctx.run.depth', 'ctx.now.utcDate',
] as const

export const CTX_LABELS: Record<string, string> = {
  'ctx.channel': '渠道 channel',
  'ctx.locale': '语言 locale',
  'ctx.run.attempt': '第几次尝试 run.attempt',
  'ctx.run.depth': '委派深度 run.depth',
  'ctx.now.utcDate': '回放日 now.utcDate（yyyy-MM-dd）',
}

export const FIELD_TYPE_LABELS: Record<FieldType, string> = {
  string: '文本 string',
  integer: '整数 integer',
  number: '数字 number',
  boolean: '布尔 boolean',
  array: '数组 array',
  object: '对象 object',
}

export const OPERATOR_LABELS: Record<PredicateKind, string> = {
  const: '始终成立 / 永不成立',
  and: '全部满足（且）',
  or: '任一满足（或）',
  not: '取反（非）',
  eq: '等于',
  in: '属于列表',
  null: '为空 / 字段缺失',
  startsWith: '以…开头',
  endsWith: '以…结尾',
  contains: '包含文本',
  anyOf: '与列表有交集（数组字段）',
  re: '匹配正则',
}

export const OPERATOR_GROUPS: { label: string; options: PredicateKind[] }[] = [
  { label: '逻辑组合', options: ['const', 'and', 'or', 'not'] },
  { label: '通用比较', options: ['eq', 'in', 'null'] },
  { label: '文本判断', options: ['startsWith', 'endsWith', 'contains', 're'] },
  { label: '数组判断', options: ['anyOf'] },
]

export const VIA_LABELS: Record<SampleVia, string> = {
  RULE_MATCH: '规则命中',
  SEMANTIC_MATCH: '语义命中',
  DEFAULT_TARGET: '走兜底目标',
  NO_MATCH_REJECTED: '无命中被拒绝',
}

export const MODE_HINT: Record<VisualDraft['mode'], string> = {
  RULES_ONLY: '模型零参与：只按下面的有序规则选择目标（推荐）',
  RULE_FIRST: '先按规则选；规则都没命中时，再看模型候选',
  SEMANTIC: '完全交给模型候选（规则列表可为空）',
}

/* ───────────────────────── 小工具 ───────────────────────── */

let keySeq = 0
/** 生成 React 稳定 key（不用于任何后端字段）。 */
export function newKey(prefix = 'k'): string {
  keySeq += 1
  return `${prefix}-${Date.now().toString(36)}-${keySeq.toString(36)}`
}

export function makeValue(source: ValueSource = 'literal', path = ''): ValueModel {
  return { source, path, ctx: 'ctx.channel', literalType: 'string', literal: '' }
}

export function makePredicate(kind: PredicateKind, fieldPath?: string): PredicateNode {
  const left = makeValue('input', fieldPath ?? '')
  switch (kind) {
    case 'const': return { key: newKey('p'), kind, value: true }
    case 'and':
    case 'or':
      return { key: newKey('p'), kind, children: [makePredicate('eq', fieldPath)] }
    case 'not': return { key: newKey('p'), kind, child: makePredicate('eq', fieldPath) }
    case 'eq':
    case 'startsWith':
    case 'endsWith':
    case 'contains':
    case 're':
      return { key: newKey('p'), kind, left, right: makeValue('literal') }
    case 'in':
    case 'anyOf':
      return { key: newKey('p'), kind, left, items: [], itemType: 'string' }
    case 'null': return { key: newKey('p'), kind, left }
  }
}

export function makeField(): SchemaField {
  return {
    key: newKey('f'), name: '', type: 'string', required: false,
    enumValues: [], itemType: 'string', children: [],
  }
}

export function makeRule(index: number, fieldPath?: string): RuleModel {
  return {
    key: newKey('r'),
    ruleId: `r-${index + 1}`,
    target: '',
    onError: 'SKIP',
    note: '',
    when: makePredicate('eq', fieldPath),
  }
}

export function makeSample(index: number): SampleModel {
  return {
    key: newKey('s'), label: `样本 ${index + 1}`, input: {},
    via: 'RULE_MATCH', expectTarget: '', ctx: {},
  }
}

export function makeProbe(index: number): ProbeModel {
  return {
    key: newKey('t'), label: `探针 ${index + 1}`, input: {},
    modelCandidate: '', ctx: {},
  }
}

/** 收集所有可引用的输入路径（含嵌套对象的点分路径）。 */
export function flattenFields(fields: SchemaField[], prefix = ''): FlatField[] {
  const out: FlatField[] = []
  for (const f of fields) {
    const name = f.name.trim()
    if (!name) continue
    const path = prefix ? `${prefix}.${name}` : name
    out.push({
      path, type: f.type, itemType: f.itemType,
      enumValues: f.enumValues, required: f.required,
    })
    if (f.type === 'object') out.push(...flattenFields(f.children, path))
  }
  return out
}

/** 按字段类型把界面上的原始文本转成 JSON 标量。非法输入返回 null（校验层会拦）。 */
export function scalarFromText(text: string, type: ScalarType): string | number | boolean | null {
  if (type === 'boolean') {
    if (text === 'true') return true
    if (text === 'false') return false
    return null
  }
  if (type === 'integer' || type === 'number') {
    if (text === '' || text === null || text === undefined) return null
    const n = Number(text)
    if (!Number.isFinite(n) || (type === 'integer' && !Number.isInteger(n))) return null
    return n
  }
  return text
}

/* ───────────────────────── 序列化 ───────────────────────── */

function fieldDefinition(f: SchemaField): Record<string, unknown> {
  const def: Record<string, unknown> = { type: f.type }
  if (f.type === 'object') {
    const props: Record<string, unknown> = {}
    for (const child of f.children) {
      const name = child.name.trim()
      if (name) props[name] = fieldDefinition(child)
    }
    def.properties = props
    const required = f.children.filter((c) => c.required && c.name.trim()).map((c) => c.name.trim())
    if (required.length) def.required = required
  } else if (f.type === 'array') {
    def.items = { type: f.itemType }
  } else if (f.enumValues.length) {
    const values = f.enumValues
      .map((raw) => scalarFromText(raw, f.type as ScalarType))
      .filter((v): v is string | number | boolean => v !== null)
    if (values.length) def.enum = values
  }
  return def
}

/** 序列化为后端 `InputSchema.from` 接受的对象。 */
export function serializeInputSchema(draft: VisualDraft): Record<string, unknown> {
  const properties: Record<string, unknown> = {}
  for (const f of draft.fields) {
    const name = f.name.trim()
    if (name) properties[name] = fieldDefinition(f)
  }
  const required = draft.fields.filter((f) => f.required && f.name.trim()).map((f) => f.name.trim())
  return { type: 'object', properties, ...(required.length ? { required } : {}) }
}

function serializeValue(v: ValueModel): unknown {
  switch (v.source) {
    case 'input': return `in.${v.path.trim()}`
    case 'ctx': return v.ctx
    case 'escaped': return { lit: v.literal }
    case 'literal': return scalarFromText(v.literal, v.literalType)
  }
}

/** 序列化为后端 `PredicateCodec.parseNode` 接受的**单键**谓词对象。 */
export function serializePredicate(node: PredicateNode): Record<string, unknown> {
  switch (node.kind) {
    case 'const': return { const: node.value }
    case 'and':
    case 'or': return { [node.kind]: node.children.map(serializePredicate) }
    case 'not':
      return { not: node.child ? serializePredicate(node.child) : { const: false } }
    case 'eq':
    case 'startsWith':
    case 'endsWith':
    case 'contains':
      return { [node.kind]: [serializeValue(node.left), serializeValue(node.right)] }
    case 're':
      // 后端要求正则右值是字符串（且长度 ≤128）
      return { re: [serializeValue(node.left), node.right.literal] }
    case 'in':
    case 'anyOf':
      return {
        [node.kind]: [
          serializeValue(node.left),
          node.items.map((raw) => scalarFromText(raw, node.itemType)),
        ],
      }
    case 'null': return { null: [serializeValue(node.left)] }
  }
}

/** 序列化为后端 `PredicateCodec.parseRules` 接受的数组（ordinal 交给服务端回填）。 */
export function serializeRules(draft: VisualDraft): Record<string, unknown>[] {
  return draft.rules.map((r) => ({
    ruleId: r.ruleId.trim(),
    target: r.target.trim(),
    when: serializePredicate(r.when),
    onError: r.onError,
    note: r.note.trim(),
  }))
}

function serializeInputValues(input: InputValues, fields: FlatField[]): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [path, raw] of Object.entries(input)) {
    if (raw === undefined || raw === '') continue
    const field = fields.find((f) => f.path === path)
    if (field?.type === 'array' && Array.isArray(raw)) {
      out[path] = raw.map((item) =>
        field.itemType === 'integer' || field.itemType === 'number'
          ? Number(item)
          : field.itemType === 'boolean'
            ? item === true || item === 'true'
            : String(item),
      )
    } else {
      out[path] = raw
    }
  }
  return out
}

function serializeCtx(ctx: CtxModel): Record<string, unknown> | undefined {
  const out: Record<string, unknown> = {}
  if (ctx.channel?.trim()) out.channel = ctx.channel.trim()
  if (ctx.locale?.trim()) out.locale = ctx.locale.trim()
  if (ctx['run.attempt'] !== undefined && ctx['run.attempt'] !== null) out['run.attempt'] = ctx['run.attempt']
  if (ctx['run.depth'] !== undefined && ctx['run.depth'] !== null) out['run.depth'] = ctx['run.depth']
  if (ctx['now.utcDate']?.trim()) out['now.utcDate'] = ctx['now.utcDate'].trim()
  return Object.keys(out).length ? out : undefined
}

/** 序列化为 `sampleSet` 文本数组；空集显式写 `[]`（后端 DTO 拒绝空白字符串）。 */
export function serializeSamples(draft: VisualDraft): Record<string, unknown>[] {
  const fields = flattenFields(draft.fields)
  return draft.samples.map((s) => ({
    label: s.label.trim(),
    input: serializeInputValues(s.input, fields),
    expectTarget: s.expectTarget.trim(),
    via: s.via,
    ...(serializeCtx(s.ctx) ? { ctx: serializeCtx(s.ctx) } : {}),
  }))
}

/** 序列化为 dry-run 探针数组（不进策略正文与内容指纹）。 */
export function serializeProbes(draft: VisualDraft): Record<string, unknown>[] {
  const fields = flattenFields(draft.fields)
  return draft.probes.map((p) => ({
    label: p.label.trim(),
    input: serializeInputValues(p.input, fields),
    ...(p.modelCandidate.trim() ? { modelCandidate: p.modelCandidate.trim() } : {}),
    ...(serializeCtx(p.ctx) ? { ctx: serializeCtx(p.ctx) } : {}),
  }))
}

/** 生成给右侧预览面板看的缩进 JSON 文本。 */
export function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2)
}

/* ───────────────────────── 校验（只做结构，不做求值） ───────────────────────── */

export interface ValidationReport {
  errors: string[]
  warnings: string[]
}

function validateFields(fields: SchemaField[], errors: string[], parent = ''): void {
  const seen = new Set<string>()
  for (const f of fields) {
    const name = f.name.trim()
    const where = parent ? `${parent}.${name || '?'}` : (name || '未命名字段')
    if (!name) errors.push('存在未命名的输入字段')
    else if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(name)) {
      errors.push(`字段名「${name}」不合法：需字母开头，只含字母、数字、下划线`)
    }
    if (seen.has(name)) errors.push(`字段「${name}」重名`)
    seen.add(name)

    if (f.type === 'object' && f.children.length === 0) {
      errors.push(`对象字段「${where}」至少需要一个子字段`)
    }
    if (f.enumValues.length && (f.type === 'string' || f.type === 'integer' || f.type === 'number' || f.type === 'boolean')) {
      for (const raw of f.enumValues) {
        if (scalarFromText(raw, f.type) === null) {
          errors.push(`字段「${where}」的枚举值「${raw}」与类型 ${f.type} 不匹配`)
        }
      }
    }
    if (f.type === 'object') validateFields(f.children, errors, where)
  }
}

function valueType(v: ValueModel, fields: FlatField[]): FieldType | undefined {
  if (v.source === 'input') return fields.find((f) => f.path === v.path)?.type
  if (v.source === 'literal') return v.literalType
  if (v.source === 'escaped') return 'string'
  return undefined
}

function validateValue(v: ValueModel, fields: FlatField[], where: string, errors: string[], warnings: string[]): void {
  if (v.source === 'input') {
    if (!v.path.trim()) {
      errors.push(`${where}：未选择输入字段`)
    } else if (!fields.some((f) => f.path === v.path)) {
      errors.push(`${where}：in.${v.path} 未在输入词汇表中声明（规则里引用未声明字段会被静默丢弃）`)
    }
  }
  if (v.source === 'ctx' && !PREDICATE_CTX_NAMES.includes(v.ctx as (typeof PREDICATE_CTX_NAMES)[number])) {
    errors.push(`${where}：未知上下文变量 ${v.ctx}`)
  }
  if (v.source === 'literal' && scalarFromText(v.literal, v.literalType) === null) {
    errors.push(`${where}：固定值「${v.literal}」不是合法的 ${v.literalType}`)
  }
  if (v.source === 'escaped' && !v.literal.trim()) {
    warnings.push(`${where}：转义字面量为空`)
  }
}

function validatePredicate(
  node: PredicateNode | null,
  fields: FlatField[],
  where: string,
  errors: string[],
  warnings: string[],
  depth = 1,
  budget = { nodes: 0 },
): void {
  if (!node) {
    errors.push(`${where}：条件未设置完整`)
    return
  }
  budget.nodes += 1
  if (budget.nodes > 128) {
    errors.push(`${where}：单条规则最多 128 个条件节点`)
    return
  }
  if (depth > 8) {
    errors.push(`${where}：条件嵌套超过 8 层`)
    return
  }

  switch (node.kind) {
    case 'const':
      return
    case 'and':
    case 'or':
      if (node.children.length === 0) errors.push(`${where}：${node.kind} 至少需要一个子条件`)
      node.children.forEach((child, i) =>
        validatePredicate(child, fields, `${where} / ${OPERATOR_LABELS[node.kind]} #${i + 1}`, errors, warnings, depth + 1, budget))
      return
    case 'not':
      validatePredicate(node.child, fields, `${where} / 非`, errors, warnings, depth + 1, budget)
      return
    case 'null':
      validateValue(node.left, fields, where, errors, warnings)
      return
    case 'eq':
      validateValue(node.left, fields, `${where} 左值`, errors, warnings)
      validateValue(node.right, fields, `${where} 右值`, errors, warnings)
      if (node.left.source === 'input' && node.right.source === 'literal') {
        const lt = valueType(node.left, fields)
        const rt = valueType(node.right, fields)
        // 纯结构性提示：跨类型比较在运行期会得到 ERROR（不是 false）
        if (lt && rt && lt !== rt && !(lt === 'integer' && rt === 'number')) {
          warnings.push(`${where}：in.${node.left.path} 是 ${lt}，与 ${rt} 字面量比较在求值时会报错`)
        }
      }
      return
    case 'startsWith':
    case 'endsWith':
    case 'contains':
    case 're':
      validateValue(node.left, fields, `${where} 左值`, errors, warnings)
      const leftType = valueType(node.left, fields)
      if (leftType && leftType !== 'string') {
        warnings.push(`${where}：${OPERATOR_LABELS[node.kind]} 要求两侧是文本，当前左值是 ${leftType}`)
      }
      if (node.kind === 're') {
        if (!node.right.literal) errors.push(`${where}：正则表达式不能为空`)
        if (node.right.literal.length > 128) errors.push(`${where}：正则长度不能超过 128 字符`)
      } else {
        validateValue(node.right, fields, `${where} 右值`, errors, warnings)
      }
      return
    case 'in':
    case 'anyOf':
      validateValue(node.left, fields, `${where} 左值`, errors, warnings)
      if (node.items.length === 0) errors.push(`${where}：列表至少需要一个取值`)
      if (node.items.length > 64) errors.push(`${where}：列表最多 64 个取值`)
      if (node.kind === 'anyOf') {
        const lt = valueType(node.left, fields)
        if (lt && lt !== 'array') errors.push(`${where}：anyOf 的左值必须是数组字段`)
      }
      for (const raw of node.items) {
        if (scalarFromText(raw, node.itemType) === null) {
          errors.push(`${where}：列表值「${raw}」不是合法的 ${node.itemType}`)
        }
      }
      return
  }
}

export function validateDraft(draft: VisualDraft): ValidationReport {
  const errors: string[] = []
  const warnings: string[] = []

  if (!draft.policyId.trim()) errors.push('请填写策略标识（policyId）')
  if (draft.fields.length === 0) errors.push('至少声明一个输入字段，否则规则无法引用输入')
  validateFields(draft.fields, errors)
  const fields = flattenFields(draft.fields)

  if (draft.noMatchAction === 'DEFAULT_TARGET' && !draft.defaultTarget.trim()) {
    errors.push('无命中处置为「兜底目标」时，必须填写兜底目标')
  }

  if (draft.rules.length > 64) errors.push('规则数超过上限 64 条')
  const ruleIds = new Set<string>()
  draft.rules.forEach((r, i) => {
    const where = `规则 #${i + 1}（${r.ruleId.trim() || '未命名'}）`
    const id = r.ruleId.trim()
    if (!id) errors.push(`${where}：规则标识不能为空`)
    else if (ruleIds.has(id)) errors.push(`${where}：规则标识「${id}」重复`)
    ruleIds.add(id)
    if (!r.target.trim()) errors.push(`${where}：未选择命中目标`)
    validatePredicate(r.when, fields, where, errors, warnings)
  })

  const dateOk = (s: string) => s === '' || /^\d{4}-\d{2}-\d{2}$/.test(s)
  const checkCtx = (ctx: CtxModel, where: string) => {
    if (ctx['now.utcDate'] && !dateOk(ctx['now.utcDate'])) {
      errors.push(`${where}：now.utcDate 需为 yyyy-MM-dd`)
    }
  }

  draft.samples.forEach((s, i) => {
    const where = `样本 ${i + 1}（${s.label.trim() || '未命名'}）`
    if (!s.label.trim()) errors.push(`${where}：样本名称不能为空`)
    if (!s.expectTarget.trim()) errors.push(`${where}：期望目标不能为空`)
    checkCtx(s.ctx, where)
    for (const [path, raw] of Object.entries(s.input)) {
      if (!fields.some((f) => f.path === path)) {
        warnings.push(`${where}：输入字段 ${path} 不在词汇表中（运行期会被静默丢弃）`)
      }
      const f = fields.find((x) => x.path === path)
      if (f && f.type !== 'array' && (f.type === 'integer' || f.type === 'number') &&
          (raw === '' || raw === undefined || scalarFromText(String(raw), f.type) === null)) {
        errors.push(`${where}：字段 ${path} 的值不是合法数字`)
      }
    }
  })

  if (draft.probes.length > 20) errors.push('探针数超过上限 20 条')
  draft.probes.forEach((p, i) => {
    checkCtx(p.ctx, `探针 ${i + 1}`)
    for (const [path] of Object.entries(p.input)) {
      if (!fields.some((f) => f.path === path)) {
        warnings.push(`探针 ${i + 1}：输入字段 ${path} 不在词汇表中（试跑轨迹里看不到它）`)
      }
    }
  })

  // 守卫 V9：每条规则的目标都要有 via=RULE_MATCH 的样本覆盖，否则发布期拦截
  const covered = new Set(draft.samples.filter((s) => s.via === 'RULE_MATCH').map((s) => s.expectTarget.trim()))
  for (const r of draft.rules) {
    const target = r.target.trim()
    if (target && !covered.has(target)) {
      warnings.push(`守卫 V9：规则「${r.ruleId.trim()}」的目标 ${target} 缺少「规则命中」样本覆盖，发布会被拦下`)
    }
  }

  return { errors, warnings }
}

/* ───────────────────────── 人话描述（用于条件卡片摘要） ───────────────────────── */

function describeValue(v: ValueModel): string {
  switch (v.source) {
    case 'input': return `in.${v.path || '?'}`
    case 'ctx': return v.ctx.replace('ctx.', '')
    case 'escaped': return `字面量「${v.literal}」`
    case 'literal': return `「${v.literal}」`
  }
}

/** 把谓词树翻成一句人话（只影响展示，不参与任何判定）。 */
export function describePredicate(node: PredicateNode | null): string {
  if (!node) return '未设置条件'
  switch (node.kind) {
    case 'const': return node.value ? '始终成立' : '永不成立'
    case 'and': return node.children.map(describePredicate).join(' 且 ') || '（空）'
    case 'or': return node.children.map(describePredicate).join(' 或 ') || '（空）'
    case 'not': return `非（${describePredicate(node.child)}）`
    case 'eq': return `${describeValue(node.left)} 等于 ${describeValue(node.right)}`
    case 'in': return `${describeValue(node.left)} 属于 [${node.items.join('、') || '空'}]`
    case 'null': return `${describeValue(node.left)} 为空`
    case 'startsWith': return `${describeValue(node.left)} 以 ${describeValue(node.right)} 开头`
    case 'endsWith': return `${describeValue(node.left)} 以 ${describeValue(node.right)} 结尾`
    case 'contains': return `${describeValue(node.left)} 包含 ${describeValue(node.right)}`
    case 'anyOf': return `${describeValue(node.left)} 命中 [${node.items.join('、') || '空'}] 中任一`
    case 're': return `${describeValue(node.left)} 匹配正则 /${node.right.literal}/`
  }
}

/* ───────────────────────── 默认草稿 ───────────────────────── */

/** 一份文法合法、能直接试跑的示例草稿（替代原先 `when:{}` 的非法默认值）。 */
export function makeDefaultDraft(): VisualDraft {
  const topic: SchemaField = {
    key: newKey('f'), name: 'topic', type: 'string', required: true,
    enumValues: ['order_status', 'refund', 'policy'], itemType: 'string', children: [],
  }
  const orderId: SchemaField = {
    key: newKey('f'), name: 'orderId', type: 'string', required: false,
    enumValues: [], itemType: 'string', children: [],
  }
  const statusRule: PredicateNode = {
    key: newKey('p'), kind: 'and', children: [
      {
        key: newKey('p'), kind: 'eq',
        left: makeValue('input', 'topic'),
        right: { ...makeValue('literal'), literal: 'order_status' },
      },
      { key: newKey('p'), kind: 'not', child: { key: newKey('p'), kind: 'null', left: makeValue('input', 'orderId') } },
    ],
  }
  const refundRule: PredicateNode = {
    key: newKey('p'), kind: 'eq',
    left: makeValue('input', 'topic'),
    right: { ...makeValue('literal'), literal: 'refund' },
  }
  return {
    policyId: '',
    mode: 'RULES_ONLY',
    noMatchAction: 'REJECT',
    onConflict: 'RULE_WINS',
    defaultTarget: '',
    fields: [topic, orderId],
    rules: [
      {
        key: newKey('r'), ruleId: 'r-order-status', target: 'order-query',
        onError: 'SKIP', note: '带单号查状态', when: statusRule,
      },
      {
        key: newKey('r'), ruleId: 'r-refund', target: 'refund-agent',
        onError: 'SKIP', note: '退款', when: refundRule,
      },
    ],
    samples: [
      {
        key: newKey('s'), label: '带单号查状态', via: 'RULE_MATCH',
        input: { topic: 'order_status', orderId: 'SO-1' }, expectTarget: 'order-query', ctx: {},
      },
      {
        key: newKey('s'), label: '退款', via: 'RULE_MATCH',
        input: { topic: 'refund' }, expectTarget: 'refund-agent', ctx: {},
      },
    ],
    probes: [
      {
        key: newKey('t'), label: '示例探针', input: { topic: 'order_status', orderId: 'SO-1' },
        modelCandidate: '', ctx: {},
      },
    ],
  }
}
