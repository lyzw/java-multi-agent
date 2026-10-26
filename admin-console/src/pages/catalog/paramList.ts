/**
 * 「参数列表」编辑器的**纯逻辑**模块（把 raw JSON 文本 ↔ 可视化参数行列表互转）。
 *
 * <p><b>⚠️ 为什么抽成独立模块</b>：这个编辑器有两处「归一化一下就错」的地方 ——
 * 一是**入参结构**里那些界面表达不了的约束（{@code minimum} / {@code pattern} /
 * 不受支持的 {@code type}），二是**参数策略**里 {@code value} 的形状随 {@code op} 变
 * （{@code in_allowlist} 是数组、{@code lte} 是数值）。写在页面组件里就只能靠
 * 「渲染整页 + 模拟输入」来测，既慢又脆；抽成纯函数后可以直接**断言语义**
 * （产出文本里那个键到底在不在、那个值到底是数组还是数字），而不必断言文案。
 *
 * <p><b>⚠️ 本模块只做「文本 ↔ 行列表」的形状与一致性检查</b>，
 * 不替代服务端的门禁 —— 后端 {@code ArgsNormalizer} / {@code McpBackedToolHandler} /
 * {@code ParamPolicyEngine} 才是权威。前端先拒，只是让作者**当场**看到错误。
 */

/**
 * 校验 / 构造的结果（{@code ok=false} 时 {@code error} 是可直接展示给人看的原因）。
 *
 * <p>与 {@code platformObjectForm.ts} 同形，便于页面层用同一套写法处理两处表单。
 */
export type SpecResult<T> = { ok: true; spec: T } | { ok: false; error: string }

/* ── 入参结构（inputSchema） ── */

/** 参数行支持的类型（界面能表达的 6 种）。 */
export type ParamType = 'string' | 'number' | 'integer' | 'boolean' | 'array' | 'object'

/** 一个参数行。 */
export interface ParamRow {
  /** 参数名（JSON Schema {@code properties} 的键） */
  key: string
  /**
   * 该参数**完整原始**的 Schema 片段（解析所得的对象）。
   *
   * ⚠️ 序列化时以其为底再覆盖建模字段 —— 这样未被建模的约束
   * （{@code minimum} / {@code pattern} / {@code items} …）不会丢。
   */
  schema: Record<string, unknown>
  /** 类型 */
  type: ParamType
  /** 是否必填（落在顶层 {@code required} 数组里） */
  required: boolean
  /** 说明文字 */
  description: string
  /** 默认值的**原始文本**（按 type 解析成 JSON 值；空串表示不设默认值） */
  defaultText: string
  /** 枚举候选值的原始文本（逗号分隔；空串表示不设 enum） */
  enumText: string
}

/** 参数列表状态。 */
export interface ParamListState {
  rows: ParamRow[]
  /**
   * 顶层除 {@code properties} / {@code required} 之外的键，原样保留
   * （如 {@code additionalProperties}、{@code title}、{@code $schema}）。
   *
   * ⚠️ {@code additionalProperties: false} 决定了后端是否做**闭集**校验
   * （{@code McpBackedToolHandler.validate}），丢它等于把「只认这些参数」
   * 悄悄放宽成「随便传」—— 所以它必须原样带走。
   */
  extra: Record<string, unknown>
}

/* ── 参数策略（paramPolicies） ── */

/** 一条参数策略行。 */
export interface PolicyRow {
  /** 策略作用的参数名 */
  param: string
  /** 操作类型：仅这两种（后端白名单） */
  op: 'in_allowlist' | 'lte'
  /** {@code in_allowlist}：逗号分隔的允许值；{@code lte}：数值文本 */
  value: string
}

/* ── 内部常量 ── */

/** 界面支持的类型白名单。 */
const SUPPORTED_TYPES: readonly ParamType[] = [
  'string',
  'number',
  'integer',
  'boolean',
  'array',
  'object',
]

/**
 * 原始 {@code type} 不受支持（或缺失）时的**回退类型**。
 *
 * ⚠️ 之所以是 {@code object} 而不是「留空」：行模型必须有一个具体类型供界面下拉框选中，
 * 否则每次渲染都要额外判空；而「回退」这个事实由 {@link ParamRow.schema} 里的原始
 * {@code type} 兜底 —— 序列化时会把原值**原样写回**，不会被这个回退值污染。
 */
const FALLBACK_TYPE: ParamType = 'object'

/**
 * 参数名的合法字符集。
 *
 * ⚠️ 为什么限制得这么紧：后端策略按**参数名**寻址（{@code ParamPolicyEngine} 的
 * {@code param} 字段直接与调用实参的键比），名字里带 {@code .} 或 {@code -}
 * 会让策略**匹配不上**（静默失效，等于策略没生效）；
 * 而以数字开头的名字在各类配置语法里歧义更多。故只放开字母、数字与下划线。
 */
const PARAM_NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/

/** 后端 {@code ParamPolicyEngine.Op} 的白名单（未知 op 会被后端在**配置期**直接拒）。 */
const POLICY_OPS = ['in_allowlist', 'lte'] as const

/* ── 内部工具 ── */

/** 是否是「普通对象」（排除 null 与数组 —— 后端要求 Schema 与策略项都是 JSON 对象）。 */
function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 是否是受支持的参数类型。 */
function isSupportedType(value: unknown): value is ParamType {
  return typeof value === 'string' && (SUPPORTED_TYPES as readonly string[]).includes(value)
}

/** 是否是受支持的策略操作类型。 */
function isSupportedOp(value: unknown): value is PolicyRow['op'] {
  return typeof value === 'string' && (POLICY_OPS as readonly string[]).includes(value)
}

/**
 * 把任意 JSON 值转成**原始文本**形态（用于默认值 / 枚举候选项的展示）。
 *
 * ⚠️ 对象与数组必须用 {@code JSON.stringify} 而不是 {@code String()} ——
 * {@code String({a:1})} 得到的是 {@code "[object Object]"}，回写时已经不是原来的值了。
 */
function valueToText(value: unknown): string {
  if (value === null) return 'null'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value) ?? ''
}

/**
 * 取「可转成有限数字」的值，不能则返回 {@code null}。
 *
 * ⚠️ 空串必须单独挡掉：{@code Number('')} 是 {@code 0}（不是 NaN），
 * 于是「空阈值」会被静默当成 0 —— 那等于凭空给出一个上界。
 */
function toFiniteNumber(value: unknown): number | null {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (trimmed === '') return null
    const parsed = Number(trimmed)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/** 取出某个 property 的默认值原始文本（不存在或为 null 时视为「无默认值」）。 */
function defaultTextOf(property: Record<string, unknown>): string {
  if (!('default' in property)) return ''
  const raw = property['default']
  if (raw === null || raw === undefined) return ''
  if (typeof raw === 'string') return raw
  if (typeof raw === 'number' || typeof raw === 'boolean') return String(raw)
  return JSON.stringify(raw) ?? ''
}

/** 取出某个 property 的枚举原始文本（非数组时视为「无枚举」）。 */
function enumTextOf(property: Record<string, unknown>): string {
  const raw = property['enum']
  if (!Array.isArray(raw)) return ''
  return raw.map(valueToText).join(',')
}

/**
 * 按类型解析一个文本值（默认值 / 枚举候选项共用）。
 *
 * <p>⚠️ 各类型的形状要求与后端一致：
 * {@code number} / {@code integer} 必须是**有限数字**（{@code integer} 还必须是整数），
 * {@code boolean} 只接受 {@code true} / {@code false}，
 * {@code array} / {@code object} 必须是**合法 JSON 且类型正确**。
 */
function parseTypedValue(text: string, type: ParamType): SpecResult<unknown> {
  switch (type) {
    case 'string':
      // ⚠️ 字符串**原样**保留：不 trim —— 前后空格可能是作者有意为之的内容
      return { ok: true, spec: text }
    case 'number': {
      const parsed = toFiniteNumber(text)
      if (parsed === null) return { ok: false, error: '不是合法数值' }
      return { ok: true, spec: parsed }
    }
    case 'integer': {
      const parsed = toFiniteNumber(text)
      if (parsed === null || !Number.isInteger(parsed)) {
        return { ok: false, error: '不是合法整数' }
      }
      return { ok: true, spec: parsed }
    }
    case 'boolean': {
      const trimmed = text.trim().toLowerCase()
      if (trimmed === 'true') return { ok: true, spec: true }
      if (trimmed === 'false') return { ok: true, spec: false }
      return { ok: false, error: '不是合法布尔值' }
    }
    case 'array': {
      let parsed: unknown
      try {
        parsed = JSON.parse(text.trim())
      } catch {
        return { ok: false, error: '不是合法 JSON 数组' }
      }
      if (!Array.isArray(parsed)) return { ok: false, error: '不是合法 JSON 数组' }
      return { ok: true, spec: parsed }
    }
    case 'object': {
      let parsed: unknown
      try {
        parsed = JSON.parse(text.trim())
      } catch {
        return { ok: false, error: '不是合法 JSON 对象' }
      }
      if (!isPlainObject(parsed)) return { ok: false, error: '不是合法 JSON 对象' }
      return { ok: true, spec: parsed }
    }
    default:
      return { ok: false, error: `不支持的参数类型：${String(type)}` }
  }
}

/**
 * 决定 property 的 {@code type} 该写什么。
 *
 * <p>⚠️ 这里是整个模块最容易写坏的一处，规则如下（按优先级）：
 * <ol>
 *   <li>原始 {@code type} **存在但不受支持**（如 {@code "null"} / {@code "string[]"}）：
 *       <b>原样写回</b>，除非行类型被显式改成了「非回退类型」——
 *       界面表达不了的类型不能被悄悄改成 {@code object}（那会改变入参校验语义）；</li>
 *   <li>原始 {@code type} 受支持：写行类型（作者可以在界面上改类型）；</li>
 *   <li>原始**没有** {@code type} 且行类型仍是回退值：<b>不写</b> {@code type} 键 ——
 *       「无类型约束」意味着任意类型都接受，补一个 {@code object} 等于把约束**收紧**了；</li>
 *   <li>行类型运行期非法：保住原始值，不写坏。</li>
 * </ol>
 */
function resolveType(row: ParamRow): { write: boolean; value?: unknown } {
  const original = row.schema?.['type']
  const rowTypeSupported = isSupportedType(row.type)
  if (original !== undefined && !isSupportedType(original)) {
    if (rowTypeSupported && row.type !== FALLBACK_TYPE) {
      return { write: true, value: row.type }
    }
    return { write: true, value: original }
  }
  if (rowTypeSupported) {
    if (original === undefined && row.type === FALLBACK_TYPE) {
      return { write: false }
    }
    return { write: true, value: row.type }
  }
  if (original !== undefined) {
    return { write: true, value: original }
  }
  return { write: false }
}

/* ── 入参结构：文本 → 行列表 ── */

/**
 * 解析入参结构文本 → 参数行列表。
 *
 * <p>⚠️ **空 Schema ≠ 允许任意参数**：{@code {}} 只表示「无结构约束」，
 * 真正的参数域由 {@code paramPolicies} 收紧（后端 {@code ParamPolicyEngine}）。
 * 本函数把空文本当作「空 Schema」（{@code rows: []}），不做任何语义推断。
 *
 * <p>⚠️ **已知取舍**：顶层 {@code required} 里出现但**未在 {@code properties} 声明**的参数名
 * 无法用行模型表达（行模型里每个参数都得有 {@code properties} 条目），保存时会被丢弃。
 * 这种配置本身是自相矛盾的（{@code additionalProperties: false} 的闭集里根本没有它），
 * 故不在此处报错。
 */
export function parseParamList(schemaText: string | null | undefined): SpecResult<ParamListState> {
  const text = (schemaText ?? '').trim()
  if (text === '') {
    return { ok: true, spec: { rows: [], extra: {} } }
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return { ok: false, error: '入参结构（inputSchema）不是合法 JSON' }
  }
  if (!isPlainObject(parsed)) {
    return { ok: false, error: '入参结构（inputSchema）必须是 JSON Schema 对象' }
  }

  // ── 顶层除 properties / required 之外的键：原样带走 ──
  const extra: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(parsed)) {
    if (key === 'properties' || key === 'required') continue
    extra[key] = value
  }

  // ── required：字符串数组；非数组或含非字符串 → 当场拒 ──
  const requiredNames = new Set<string>()
  if ('required' in parsed) {
    const rawRequired = parsed['required']
    if (!Array.isArray(rawRequired)) {
      return { ok: false, error: '入参结构（inputSchema）的 required 必须是字符串数组' }
    }
    for (const item of rawRequired) {
      if (typeof item !== 'string') {
        return { ok: false, error: '入参结构（inputSchema）的 required 必须是字符串数组' }
      }
      requiredNames.add(item)
    }
  }

  // ── properties：逐个参数 ──
  const rows: ParamRow[] = []
  if ('properties' in parsed) {
    const rawProperties = parsed['properties']
    if (!isPlainObject(rawProperties)) {
      return { ok: false, error: '入参结构（inputSchema）的 properties 必须是对象' }
    }
    for (const [key, rawProperty] of Object.entries(rawProperties)) {
      if (!isPlainObject(rawProperty)) {
        return { ok: false, error: `参数 ${key} 的定义必须是对象` }
      }
      const rawType = rawProperty['type']
      const description = rawProperty['description']
      rows.push({
        key,
        // ⚠️ 原始片段整体保留：未被建模的约束（minimum / pattern …）靠它兜底
        schema: { ...rawProperty },
        type: isSupportedType(rawType) ? rawType : FALLBACK_TYPE,
        required: requiredNames.has(key),
        description: typeof description === 'string' ? description : '',
        defaultText: defaultTextOf(rawProperty),
        enumText: enumTextOf(rawProperty),
      })
    }
  }

  return { ok: true, spec: { rows, extra } }
}

/* ── 入参结构：行列表 → 文本 ── */

/**
 * 参数行列表 → 入参结构 JSON 文本。
 *
 * <p>⚠️ **产出必须能被后端直接吃下**：{@code properties} 恒写出（哪怕是空对象），
 * {@code required} 为空时**不写**（避免出现 {@code "required": []} 这种多余键），
 * 顶层其余键原样带回。
 */
export function buildParamListSchema(state: ParamListState): SpecResult<string> {
  const properties: Record<string, unknown> = {}
  const requiredKeys: string[] = []
  const seen = new Set<string>()

  for (const row of state.rows ?? []) {
    const key = (row.key ?? '').trim()
    if (key === '') {
      return { ok: false, error: '参数名不能为空' }
    }
    if (!PARAM_NAME_PATTERN.test(key)) {
      return {
        ok: false,
        error: `参数名 ${key} 非法：只允许字母、数字与下划线（首字符不能是数字）`,
      }
    }
    if (seen.has(key)) {
      // ⚠️ 重复参数名必须点名 —— 后端只看到一份，后写的那行会**静默覆盖**前一行
      return { ok: false, error: `参数名重复：${key}` }
    }
    seen.add(key)

    const property: Record<string, unknown> = { ...row.schema }

    // ── type ──
    const type = resolveType(row)
    if (type.write) {
      property['type'] = type.value
    } else {
      delete property['type']
    }

    // ── description：非空则写，空则删（删掉「作者清空」而不是留一个空串）──
    const description = row.description ?? ''
    if (description.trim() !== '') {
      property['description'] = description
    } else {
      delete property['description']
    }

    // ── default：按类型解析；空文本表示不设默认值 ──
    const defaultText = row.defaultText ?? ''
    if (defaultText !== '') {
      const parsed = parseTypedValue(defaultText, row.type)
      if (!parsed.ok) {
        return { ok: false, error: `参数 ${key} 的默认值不是合法的 ${row.type}` }
      }
      property['default'] = parsed.spec
    } else {
      delete property['default']
    }

    // ── enum：逗号分隔 → 按类型解析的数组 ──
    const enumText = row.enumText ?? ''
    if (enumText !== '') {
      const items = enumText
        .split(',')
        .map((item) => item.trim())
        .filter((item) => item !== '')
      if (items.length === 0) {
        // ⚠️ 空枚举等于「不允许任何值」，与「不设枚举」完全相反 —— 必须拒
        return { ok: false, error: `参数 ${key} 的枚举值不能为空` }
      }
      const values: unknown[] = []
      for (const item of items) {
        const parsed = parseTypedValue(item, row.type)
        if (!parsed.ok) {
          return { ok: false, error: `参数 ${key} 的枚举值不是合法的 ${row.type}` }
        }
        values.push(parsed.spec)
      }
      property['enum'] = values
    } else {
      delete property['enum']
    }

    properties[key] = property
    if (row.required) {
      requiredKeys.push(key)
    }
  }

  const top: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(state.extra ?? {})) {
    top[key] = value
  }
  // ⚠️ properties 恒写出：空对象也是合法 Schema（{"properties":{}}）
  top['properties'] = properties
  if (requiredKeys.length > 0) {
    top['required'] = requiredKeys
  }
  return { ok: true, spec: JSON.stringify(top) }
}

/* ── 参数策略：文本 → 行列表 ── */

/**
 * 解析参数策略文本 → 策略行列表。
 *
 * <p>⚠️ 后端 {@code ParamPolicyEngine.parse()} 只接受 4 种形态：
 * {@code null} / 空串 / {@code []} / {@code {}}（四者都解析成**空策略集**）
 * 与 {@code [{param, op, value}]}。除数组外的任何其它形态都会被后端以
 * 「结构非法」拒掉，故前端也直接拒。
 *
 * <p>⚠️ **未知 op 在这里就拒**：后端是在**配置期**拒的（{@code ParamPolicyConfigException}），
 * 那时理由已经填完、走到了审批 —— 反馈太晚。
 */
export function parsePolicyList(policiesText: string | null | undefined): SpecResult<PolicyRow[]> {
  const text = (policiesText ?? '').trim()
  if (text === '') {
    return { ok: true, spec: [] }
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return { ok: false, error: '参数策略（paramPolicies）不是合法 JSON' }
  }

  if (parsed === null) {
    // null：未配置该层附加策略 → 空策略集
    return { ok: true, spec: [] }
  }
  if (Array.isArray(parsed)) {
    const rows: PolicyRow[] = []
    for (const element of parsed) {
      if (!isPlainObject(element)) {
        return { ok: false, error: '参数策略项必须是对象' }
      }
      const rawParam = element['param']
      if (typeof rawParam !== 'string' || rawParam.trim() === '') {
        return { ok: false, error: '参数策略缺少 param（参数名）' }
      }
      const rawOp = element['op']
      if (typeof rawOp !== 'string') {
        return { ok: false, error: '参数策略缺少 op（操作类型）' }
      }
      const op = rawOp.trim()
      if (!isSupportedOp(op)) {
        return {
          ok: false,
          error: `未知参数策略 op：${rawOp}（后端只接受 in_allowlist 与 lte）`,
        }
      }
      const rawValue = element['value']
      if (rawValue === undefined) {
        return { ok: false, error: '参数策略缺少 value' }
      }
      if (op === 'in_allowlist') {
        if (!Array.isArray(rawValue)) {
          return { ok: false, error: 'in_allowlist 的 value 必须是数组' }
        }
        rows.push({
          param: rawParam.trim(),
          op,
          value: rawValue.map(valueToText).join(','),
        })
      } else {
        const number = toFiniteNumber(rawValue)
        if (number === null) {
          return { ok: false, error: 'lte 的 value 必须是数值' }
        }
        rows.push({ param: rawParam.trim(), op, value: String(number) })
      }
    }
    return { ok: true, spec: rows }
  }
  if (isPlainObject(parsed)) {
    if (Object.keys(parsed).length === 0) {
      // {}：DB 列默认值，等价于无附加策略
      return { ok: true, spec: [] }
    }
  }
  return {
    ok: false,
    error: '参数策略（paramPolicies）结构非法：只接受 null / [] / {} / [{param, op, value}]',
  }
}

/* ── 参数策略：行列表 → 文本 ── */

/**
 * 策略行列表 → 参数策略 JSON 文本。
 *
 * <p>⚠️ **产出必须能被后端 {@code ParamPolicyEngine.parse()} 接受**：
 * {@code op} 只有 {@code in_allowlist} / {@code lte} 两种，
 * 且 {@code value} 的**形状随 op 变**（前者是数组、后者是数值）——
 * 混了就是「配置期直接拒」。
 *
 * <p>⚠️ 每项的键序固定为 {@code param} → {@code op} → {@code value}：
 * 后端按数组顺序求值，同一份配置的产出文本应当**稳定可预期**，
 * 否则「保存前后 diff」全是噪音，审核者看不出到底改了什么。
 */
export function buildPolicyList(rows: PolicyRow[]): SpecResult<string> {
  const policies: { param: string; op: string; value: unknown }[] = []
  const seen = new Set<string>()

  for (const row of rows ?? []) {
    const param = (row.param ?? '').trim()
    if (param === '') {
      return { ok: false, error: '策略缺少参数名' }
    }
    const op = row.op
    if (!isSupportedOp(op)) {
      return {
        ok: false,
        error: `未知参数策略 op：${String(op)}（后端只接受 in_allowlist 与 lte）`,
      }
    }
    // ⚠️ 使用 **转义字符** 作分隔符：
    // 参数名只合 `[A-Za-z_][A-Za-z0-9_]*`，op 只有两个字里之一，
    // 所以使用 **`\u0000` 转义字符**而不是暪转义的 **负本 NUL 字符**：
    // 暂若数组不有正字若上放的 NUL，
    // 暂若打出来是暂成 NUL，文件会被 git / grep 当成二进分权文件。
    const identity = `${param}\u0000${op}`
    if (seen.has(identity)) {
      // ⚠️ 同一参数同一操作重复声明 → 求值结果会依赖数组顺序，属配置缺陷
      return { ok: false, error: `参数 ${param} 的策略 ${op} 重复声明` }
    }
    seen.add(identity)

    let value: unknown
    if (op === 'in_allowlist') {
      const items = (row.value ?? '')
        .split(',')
        .map((item) => item.trim())
        .filter((item) => item !== '')
      if (items.length === 0) {
        return { ok: false, error: `参数 ${param} 的允许值清单不能为空` }
      }
      value = items
    } else {
      const number = toFiniteNumber(row.value)
      if (number === null) {
        return { ok: false, error: `参数 ${param} 的 lte 阈值必须是数值` }
      }
      value = number
    }
    policies.push({ param, op, value })
  }

  return { ok: true, spec: JSON.stringify(policies) }
}

/* ── 参数名一致性 ── */

/**
 * 找出策略里引用了**未声明参数**的项。
 *
 * <p>⚠️ 为什么要单独提供：{@link buildPolicyList} 不知道当前声明了哪些参数
 * （它只负责产出后端能解析的策略文本），而「策略指向一个根本不存在的参数」
 * 在后端是**静默失效**的（策略不会被拒，但求值时该参数永远不被检查）。
 * 页面层在**同时**持有入参结构与策略时，用这个函数把这类「无效策略」当场指出来。
 *
 * <p>⚠️ {@code declaredKeys} 为空数组时返回**全部**策略引用的参数名 ——
 * 「一个参数都没声明」时，任何策略都无从谈起。
 *
 * @param rows         策略行列表
 * @param declaredKeys 已声明的参数名（通常来自 {@link ParamListState.rows}）
 * @returns 引用了未声明参数的参数名数组（去重，按首次出现顺序；空数组 = 全部已声明）
 */
export function findUndeclaredPolicyParams(rows: PolicyRow[], declaredKeys: string[]): string[] {
  const declared = new Set((declaredKeys ?? []).map((key) => (key ?? '').trim()))
  const offending: string[] = []
  const seen = new Set<string>()
  for (const row of rows ?? []) {
    const param = (row.param ?? '').trim()
    if (param === '' || declared.has(param) || seen.has(param)) continue
    seen.add(param)
    offending.push(param)
  }
  return offending
}
