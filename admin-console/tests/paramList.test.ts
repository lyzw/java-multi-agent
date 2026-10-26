import { describe, it, expect } from 'vitest'
import {
  buildParamListSchema,
  buildPolicyList,
  findUndeclaredPolicyParams,
  parseParamList,
  parsePolicyList,
  type ParamListState,
  type ParamRow,
  type PolicyRow,
} from '@/pages/catalog/paramList'

/**
 * 「参数列表」编辑器的**纯逻辑**断言。
 *
 * ⚠️ 这些用例全部按**语义**断言（产出文本里那个键到底在不在、那个值到底是数组还是数字），
 *    而不是只断言 {@code ok === true} —— 只断言 ok 的话，
 *    「把 minimum 悄悄丢了」「把 lte 的数值写成了字符串」这类 bug 一个都抓不住。
 */

/* ── 测试辅助 ── */

/** 解析入参结构文本（断言成功）。 */
function okState(text: string | null | undefined): ParamListState {
  const result = parseParamList(text)
  expect(result.ok).toBe(true)
  if (!result.ok) throw new Error(result.error)
  return result.spec
}

/** 构造入参结构文本（断言成功）。 */
function schemaText(state: ParamListState): string {
  const result = buildParamListSchema(state)
  expect(result.ok).toBe(true)
  if (!result.ok) throw new Error(result.error)
  return result.spec
}

/** 入参结构文本 → 行列表 → 文本 → 对象（往返）。 */
function roundTripSchema(text: string): Record<string, unknown> {
  return JSON.parse(schemaText(okState(text))) as Record<string, unknown>
}

/** 取往返结果里的 properties。 */
function propertiesOf(schema: Record<string, unknown>): Record<string, Record<string, unknown>> {
  return schema['properties'] as Record<string, Record<string, unknown>>
}

/** 构造参数策略文本（断言成功）。 */
function policyText(rows: PolicyRow[]): string {
  const result = buildPolicyList(rows)
  expect(result.ok).toBe(true)
  if (!result.ok) throw new Error(result.error)
  return result.spec
}

/** 参数策略文本 → 行列表 → 文本 → 对象（往返）。 */
function roundTripPolicies(text: string): unknown[] {
  const parsed = parsePolicyList(text)
  expect(parsed.ok).toBe(true)
  if (!parsed.ok) throw new Error(parsed.error)
  return JSON.parse(policyText(parsed.spec)) as unknown[]
}

/* ── 入参结构 · 解析 ── */

describe('parseParamList · 文本 → 参数行列表', () => {
  it('✅ 空串 / null / undefined → 空行列表 + 空附加键', () => {
    for (const input of ['', '   ', null, undefined]) {
      const state = okState(input as string | null | undefined)
      expect(state.rows).toEqual([])
      expect(state.extra).toEqual({})
    }
  })

  it('⛔ 不是合法 JSON → 当场拒（错误点名是「入参结构」）', () => {
    const result = parseParamList('{ 这不是 JSON')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('入参结构')
  })

  it('⛔ 合法 JSON 但不是对象（数组 / 字符串 / 数字 / null）→ 当场拒', () => {
    for (const input of ['[]', '"x"', '42', 'null', 'true']) {
      const result = parseParamList(input)
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.error).toContain('入参结构')
    }
  })

  it('⛔ properties 不是对象 → 当场拒', () => {
    const result = parseParamList('{"properties":[]}')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('properties')
  })

  it('⛔ 某个 property 不是对象 → 当场拒', () => {
    const result = parseParamList('{"properties":{"a":"string"}}')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('a')
  })

  it('⛔ required 不是数组 → 当场拒', () => {
    const result = parseParamList('{"required":"a"}')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('required')
  })

  it('⛔ required 里有非字符串 → 当场拒', () => {
    const result = parseParamList('{"required":["a",1]}')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('required')
  })

  it('✅ 正常结构：类型 / 必填 / 说明 / 默认值 / 枚举各自落到对应字段', () => {
    const state = okState(
      JSON.stringify({
        properties: {
          name: { type: 'string', description: '甲', default: 'abc' },
          age: { type: 'integer', default: 3 },
          flag: { type: 'boolean', default: true },
          mode: { type: 'string', enum: ['a', 'b'] },
          items: { type: 'array', default: [1, 2] },
          blob: { type: 'object', default: { k: 1 } },
        },
        required: ['name'],
      }),
    )
    expect(state.rows.map((row) => row.key)).toEqual([
      'name',
      'age',
      'flag',
      'mode',
      'items',
      'blob',
    ])
    const name = state.rows[0]!
    expect(name.type).toBe('string')
    expect(name.required).toBe(true)
    expect(name.description).toBe('甲')
    expect(name.defaultText).toBe('abc')
    expect(state.rows[1]!.required).toBe(false)
    expect(state.rows[1]!.defaultText).toBe('3')
    expect(state.rows[2]!.defaultText).toBe('true')
    expect(state.rows[3]!.enumText).toBe('a,b')
    expect(state.rows[4]!.defaultText).toBe('[1,2]')
    expect(state.rows[5]!.defaultText).toBe('{"k":1}')
  })

  it('✅ 受支持的 type 原样落在行上', () => {
    const state = okState('{"properties":{"a":{"type":"number"}}}')
    expect(state.rows[0]!.type).toBe('number')
  })

  it('✅ 不受支持的 type → 行上按 object 处理，但原始 type 留在 schema 里', () => {
    const state = okState('{"properties":{"a":{"type":"null"}}}')
    expect(state.rows[0]!.type).toBe('object')
    expect(state.rows[0]!.schema['type']).toBe('null')
  })

  it('✅ 缺失 type → 行上按 object 处理，schema 里也没有 type', () => {
    const state = okState('{"properties":{"a":{}}}')
    expect(state.rows[0]!.type).toBe('object')
    expect('type' in state.rows[0]!.schema).toBe(false)
  })

  it('✅ 顶层附加键原样保留（additionalProperties / title / $schema）', () => {
    const state = okState(
      '{"additionalProperties":false,"title":"入参","$schema":"https://json-schema.org/draft/2020-12/schema"}',
    )
    expect(state.extra['additionalProperties']).toBe(false)
    expect(state.extra['title']).toBe('入参')
    expect(state.extra['$schema']).toBe('https://json-schema.org/draft/2020-12/schema')
    expect('properties' in state.extra).toBe(false)
    expect('required' in state.extra).toBe(false)
  })

  it('✅ default 为 null → 视为无默认值（空串）；enum 非数组 → 视为无枚举', () => {
    const state = okState('{"properties":{"a":{"default":null,"enum":"x"}}}')
    expect(state.rows[0]!.defaultText).toBe('')
    expect(state.rows[0]!.enumText).toBe('')
  })

  it('✅ description 非字符串 → 空串', () => {
    const state = okState('{"properties":{"a":{"description":5}}}')
    expect(state.rows[0]!.description).toBe('')
  })

  it('✅ 原始 property 片段整体保留（未被建模的约束不丢）', () => {
    const state = okState('{"properties":{"a":{"type":"number","minimum":1,"pattern":"^x"}}}')
    expect(state.rows[0]!.schema['minimum']).toBe(1)
    expect(state.rows[0]!.schema['pattern']).toBe('^x')
  })
})

/* ── 入参结构 · 构造 ── */

describe('buildParamListSchema · 参数行列表 → 文本', () => {
  it('⛔ 参数名为空 / 纯空白 → 当场拒', () => {
    for (const key of ['', '   ']) {
      const result = buildParamListSchema({
        rows: [{ key, schema: {}, type: 'string', required: false, description: '', defaultText: '', enumText: '' }],
        extra: {},
      })
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.error).toContain('参数名不能为空')
    }
  })

  it('⛔ 重复参数名 → 当场拒（错误点名重复的那个名字）', () => {
    const row: ParamRow = {
      key: 'a',
      schema: {},
      type: 'string',
      required: false,
      description: '',
      defaultText: '',
      enumText: '',
    }
    const result = buildParamListSchema({ rows: [{ ...row }, { ...row }], extra: {} })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('a')
    expect(result.error).toContain('重复')
  })

  it('⛔ 参数名含非法字符（. / - / 数字开头）→ 当场拒（错误说明合法字符集）', () => {
    for (const key of ['a-b', 'a.b', '1abc', 'a b']) {
      const result = buildParamListSchema({
        rows: [{ key, schema: {}, type: 'string', required: false, description: '', defaultText: '', enumText: '' }],
        extra: {},
      })
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.error).toContain('参数名')
    }
  })

  it('⛔ 默认值不合法 → 当场拒（错误点名参数与类型）', () => {
    const cases: { type: 'number' | 'integer' | 'boolean' | 'array' | 'object'; text: string }[] = [
      { type: 'number', text: 'abc' },
      { type: 'integer', text: '1.5' },
      { type: 'boolean', text: 'yes' },
      { type: 'array', text: '{"a":1}' },
      { type: 'object', text: '[1]' },
    ]
    for (const item of cases) {
      const result = buildParamListSchema({
        rows: [
          {
            key: 'a',
            schema: {},
            type: item.type,
            required: false,
            description: '',
            defaultText: item.text,
            enumText: '',
          },
        ],
        extra: {},
      })
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.error).toContain('a')
      expect(result.error).toContain(item.type)
    }
  })

  it('⛔ 枚举为空（只有逗号）→ 当场拒（空枚举等于「不允许任何值」）', () => {
    const result = buildParamListSchema({
      rows: [
        { key: 'a', schema: {}, type: 'string', required: false, description: '', defaultText: '', enumText: ',, ' },
      ],
      extra: {},
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('枚举')
  })

  it('⛔ 枚举项不合法 → 当场拒', () => {
    const result = buildParamListSchema({
      rows: [
        { key: 'a', schema: {}, type: 'number', required: false, description: '', defaultText: '', enumText: '1,abc' },
      ],
      extra: {},
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('枚举')
  })

  it('✅ 默认值按类型落地（数值是数字、布尔是布尔、数组是数组）', () => {
    const text = schemaText({
      rows: [
        { key: 'n', schema: {}, type: 'number', required: false, description: '', defaultText: '1.5', enumText: '' },
        { key: 'i', schema: {}, type: 'integer', required: false, description: '', defaultText: '3', enumText: '' },
        { key: 'b', schema: {}, type: 'boolean', required: false, description: '', defaultText: 'true', enumText: '' },
        { key: 's', schema: {}, type: 'string', required: false, description: '', defaultText: 'hi', enumText: '' },
        { key: 'a', schema: {}, type: 'array', required: false, description: '', defaultText: '[1,2]', enumText: '' },
      ],
      extra: {},
    })
    const props = propertiesOf(JSON.parse(text) as Record<string, unknown>)
    expect(props['n']?.['default']).toBe(1.5)
    expect(props['i']?.['default']).toBe(3)
    expect(props['b']?.['default']).toBe(true)
    expect(props['s']?.['default']).toBe('hi')
    expect(props['a']?.['default']).toEqual([1, 2])
  })

  it('✅ 枚举按类型落地（数值型枚举的元素是数字，不是字符串）', () => {
    const text = schemaText({
      rows: [
        { key: 'n', schema: {}, type: 'number', required: false, description: '', defaultText: '', enumText: '1, 2' },
      ],
      extra: {},
    })
    const props = propertiesOf(JSON.parse(text) as Record<string, unknown>)
    expect(props['n']?.['enum']).toEqual([1, 2])
  })

  it('✅ 没有必填参数时不写 required 键', () => {
    const text = schemaText({
      rows: [
        { key: 'a', schema: {}, type: 'string', required: false, description: '', defaultText: '', enumText: '' },
      ],
      extra: {},
    })
    const out = JSON.parse(text) as Record<string, unknown>
    expect('required' in out).toBe(false)
  })

  it('✅ 必填参数的 key 进 required 数组（按行顺序）', () => {
    const text = schemaText({
      rows: [
        { key: 'a', schema: {}, type: 'string', required: true, description: '', defaultText: '', enumText: '' },
        { key: 'b', schema: {}, type: 'string', required: false, description: '', defaultText: '', enumText: '' },
        { key: 'c', schema: {}, type: 'string', required: true, description: '', defaultText: '', enumText: '' },
      ],
      extra: {},
    })
    expect((JSON.parse(text) as Record<string, unknown>)['required']).toEqual(['a', 'c'])
  })

  it('✅ properties 为空对象时也写出来（{"properties":{}} 合法）', () => {
    const text = schemaText({ rows: [], extra: {} })
    const out = JSON.parse(text) as Record<string, unknown>
    expect(out['properties']).toEqual({})
  })

  it('✅ 顶层附加键原样带回', () => {
    const text = schemaText({ rows: [], extra: { additionalProperties: false, title: '入参' } })
    const out = JSON.parse(text) as Record<string, unknown>
    expect(out['additionalProperties']).toBe(false)
    expect(out['title']).toBe('入参')
  })

  it('✅ 清空说明 / 默认值 / 枚举 → 对应键被删除（不是留空串）', () => {
    const text = schemaText({
      rows: [
        {
          key: 'a',
          schema: { type: 'string', description: '旧说明', default: 'x', enum: ['p', 'q'] },
          type: 'string',
          required: false,
          description: '',
          defaultText: '',
          enumText: '',
        },
      ],
      extra: {},
    })
    const props = propertiesOf(JSON.parse(text) as Record<string, unknown>)
    expect('description' in props['a']!).toBe(false)
    expect('default' in props['a']!).toBe(false)
    expect('enum' in props['a']!).toBe(false)
  })

  it('✅ type 受支持 → 写行上的类型（可以在界面上改类型）', () => {
    const text = schemaText({
      rows: [
        { key: 'a', schema: { type: 'string' }, type: 'number', required: false, description: '', defaultText: '', enumText: '' },
      ],
      extra: {},
    })
    const props = propertiesOf(JSON.parse(text) as Record<string, unknown>)
    expect(props['a']?.['type']).toBe('number')
  })
})

/* ── 往返（核心正确性） ── */

describe('往返 · 打开既有配置不改内容，保存后语义必须等价', () => {
  it('✅ 1) inputSchema = {} → 往返后仍是无结构约束的合法 Schema', () => {
    const out = roundTripSchema('{}')
    // 后端视角：properties 为空 → ArgsNormalizer 展开不出任何默认值；
    // 没有 required、没有 additionalProperties → 不做闭集校验。
    expect(out['properties']).toEqual({})
    expect(out['required']).toBeUndefined()
    expect(out['additionalProperties']).toBeUndefined()
  })

  it('✅ 2) properties + required + additionalProperties:false → 往返后三样都在', () => {
    const source = JSON.stringify({
      type: 'object',
      properties: {
        name: { type: 'string', description: '甲' },
        age: { type: 'number' },
      },
      required: ['name'],
      additionalProperties: false,
    })
    const out = roundTripSchema(source)
    expect(out['additionalProperties']).toBe(false)
    expect(out['type']).toBe('object')
    expect(out['required']).toEqual(['name'])
    const props = propertiesOf(out)
    expect(Object.keys(props)).toEqual(['name', 'age'])
    expect(props['name']?.['type']).toBe('string')
    expect(props['name']?.['description']).toBe('甲')
    expect(props['age']?.['type']).toBe('number')
  })

  it('✅ 3) property 带未被建模的约束 → 往返后 minimum / pattern 仍在', () => {
    const source = JSON.stringify({
      properties: {
        n: { type: 'number', minimum: 1, multipleOf: 2 },
        s: { type: 'string', pattern: '^a' },
      },
    })
    const out = roundTripSchema(source)
    const props = propertiesOf(out)
    expect(props['n']?.['minimum']).toBe(1)
    expect(props['n']?.['multipleOf']).toBe(2)
    expect(props['s']?.['pattern']).toBe('^a')
  })

  it('✅ 4) property 的 type 不受支持 → 往返后原值原样保留（不被改成 object）', () => {
    const source = JSON.stringify({ properties: { x: { type: 'null' }, y: { type: 'string[]' } } })
    // 解析阶段：行上按 object 处理，原始 type 留在 schema 里
    const state = okState(source)
    expect(state.rows[0]!.type).toBe('object')
    expect(state.rows[0]!.schema['type']).toBe('null')
    // 序列化阶段：原样写回原始 type
    const out = roundTripSchema(source)
    const props = propertiesOf(out)
    expect(props['x']?.['type']).toBe('null')
    expect(props['y']?.['type']).toBe('string[]')
  })

  it('✅ 5) paramPolicies = {} → 解析为空策略集 → 构造为 []', () => {
    // ⚠️ **文本形态会变**（{} → []），但**语义等价**：
    //    后端 ParamPolicyEngine.parse() 把 null / 空串 / [] / {} 一律解析成**空策略集**，
    //    即「该层不追加任何约束」—— 四者对求值结果的可观测后果完全相同。
    const parsed = parsePolicyList('{}')
    expect(parsed.ok).toBe(true)
    if (!parsed.ok) return
    expect(parsed.spec).toEqual([])
    const built = buildPolicyList(parsed.spec)
    expect(built.ok).toBe(true)
    if (!built.ok) return
    expect(built.spec).toBe('[]')
    expect(JSON.parse(built.spec)).toEqual([])
  })

  it('✅ 6) in_allowlist 的 value → 往返后仍是数组 ["a","b"]（不是逗号串）', () => {
    const out = roundTripPolicies('[{"param":"mode","op":"in_allowlist","value":["a","b"]}]')
    expect(out).toEqual([{ param: 'mode', op: 'in_allowlist', value: ['a', 'b'] }])
    const first = out[0] as { value: unknown }
    expect(Array.isArray(first.value)).toBe(true)
  })

  it('✅ 7) lte 的 value → 往返后仍是数字 10（不是字符串 "10"）', () => {
    const out = roundTripPolicies('[{"param":"n","op":"lte","value":10}]')
    expect(out).toEqual([{ param: 'n', op: 'lte', value: 10 }])
    const first = out[0] as { value: unknown }
    expect(typeof first.value).toBe('number')
  })

  it('✅ 策略项的键序固定为 param → op → value（产出文本稳定可预期）', () => {
    const text = policyText([{ param: 'n', op: 'lte', value: '10' }])
    expect(Object.keys(JSON.parse(text)[0] as Record<string, unknown>)).toEqual(['param', 'op', 'value'])
  })
})

/* ── 参数策略 · 解析 ── */

describe('parsePolicyList · 文本 → 策略行列表', () => {
  it('✅ 空串 / null / undefined → 空策略集', () => {
    for (const input of ['', '   ', null, undefined]) {
      const result = parsePolicyList(input as string | null | undefined)
      expect(result.ok).toBe(true)
      if (!result.ok) return
      expect(result.spec).toEqual([])
    }
  })

  it('✅ null / [] / {} 三种形态都解析成空策略集（后端语义等价）', () => {
    for (const input of ['null', '[]', '{}']) {
      const result = parsePolicyList(input)
      expect(result.ok).toBe(true)
      if (!result.ok) return
      expect(result.spec).toEqual([])
    }
  })

  it('⛔ 不是合法 JSON → 当场拒', () => {
    const result = parsePolicyList('{ 这不是 JSON')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('参数策略')
  })

  it('⛔ 顶层是标量 / 非空对象 → 当场拒（后端只接受 null / [] / {} / 数组）', () => {
    for (const input of ['"x"', '42', '{"a":1}']) {
      const result = parsePolicyList(input)
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.error).toContain('参数策略')
    }
  })

  it('⛔ 策略项不是对象 → 当场拒', () => {
    const result = parsePolicyList('["x"]')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('对象')
  })

  it('⛔ 缺 param / 缺 op / 缺 value → 当场拒', () => {
    const cases = [
      '[{"op":"lte","value":1}]',
      '[{"param":"a","value":1}]',
      '[{"param":"a","op":"lte"}]',
    ]
    for (const input of cases) {
      const result = parsePolicyList(input)
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.error).toContain('参数策略')
    }
  })

  it('⛔ 未知 op → 当场拒（错误说明后端只认 in_allowlist 与 lte）', () => {
    const result = parsePolicyList('[{"param":"a","op":"gte","value":1}]')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('in_allowlist')
    expect(result.error).toContain('lte')
  })

  it('⛔ in_allowlist 的 value 不是数组 → 当场拒', () => {
    const result = parsePolicyList('[{"param":"a","op":"in_allowlist","value":"x"}]')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('数组')
  })

  it('⛔ lte 的 value 不是数值 → 当场拒', () => {
    for (const input of ['[{"param":"a","op":"lte","value":"abc"}]', '[{"param":"a","op":"lte","value":true}]']) {
      const result = parsePolicyList(input)
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.error).toContain('数值')
    }
  })

  it('✅ in_allowlist 的数组 → 逗号分隔的文本', () => {
    const result = parsePolicyList('[{"param":"mode","op":"in_allowlist","value":["a","b"]}]')
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.spec).toEqual([{ param: 'mode', op: 'in_allowlist', value: 'a,b' }])
  })

  it('✅ lte 的数值 → 数字文本（字符串数值也归一化成数字文本）', () => {
    const result = parsePolicyList('[{"param":"n","op":"lte","value":10}]')
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.spec).toEqual([{ param: 'n', op: 'lte', value: '10' }])

    const asText = parsePolicyList('[{"param":"n","op":"lte","value":"10"}]')
    expect(asText.ok).toBe(true)
    if (!asText.ok) return
    expect(asText.spec).toEqual([{ param: 'n', op: 'lte', value: '10' }])
  })
})

/* ── 参数策略 · 构造 ── */

describe('buildPolicyList · 策略行列表 → 文本', () => {
  it('✅ 空行列表 → []（后端认的「该层无附加策略」形态）', () => {
    expect(policyText([])).toBe('[]')
  })

  it('⛔ 缺参数名 → 当场拒', () => {
    const result = buildPolicyList([{ param: '  ', op: 'lte', value: '1' }])
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('参数名')
  })

  it('⛔ 未知 op → 当场拒', () => {
    const result = buildPolicyList([
      { param: 'a', op: 'gte' as unknown as PolicyRow['op'], value: '1' },
    ])
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('in_allowlist')
  })

  it('⛔ 允许值清单为空 → 当场拒（空清单 = 不允许任何值）', () => {
    const result = buildPolicyList([{ param: 'a', op: 'in_allowlist', value: ' , ' }])
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('清单')
  })

  it('⛔ lte 阈值不是数值 → 当场拒', () => {
    const result = buildPolicyList([{ param: 'a', op: 'lte', value: 'abc' }])
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('数值')
  })

  it('⛔ 同一参数同一操作重复声明 → 当场拒（求值语义会依赖数组顺序）', () => {
    const result = buildPolicyList([
      { param: 'a', op: 'lte', value: '10' },
      { param: 'a', op: 'lte', value: '20' },
    ])
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toContain('a')
    expect(result.error).toContain('重复')
  })

  it('✅ 同一参数的**不同**操作允许并存（AND 语义，后端会取更严交集）', () => {
    const text = policyText([
      { param: 'a', op: 'in_allowlist', value: 'x,y' },
      { param: 'a', op: 'lte', value: '10' },
    ])
    expect(JSON.parse(text)).toEqual([
      { param: 'a', op: 'in_allowlist', value: ['x', 'y'] },
      { param: 'a', op: 'lte', value: 10 },
    ])
  })

  it('✅ 产出文本能被后端 parse 接受（in_allowlist 是数组、lte 是数字）', () => {
    const text = policyText([
      { param: 'mode', op: 'in_allowlist', value: 'a,b' },
      { param: 'n', op: 'lte', value: '10' },
    ])
    const parsed = JSON.parse(text) as { param: string; op: string; value: unknown }[]
    expect(parsed[0]?.value).toEqual(['a', 'b'])
    expect(typeof parsed[1]?.value).toBe('number')
    // 回灌解析器：产出文本必须能被自己解析回来（形态没写坏）
    const back = parsePolicyList(text)
    expect(back.ok).toBe(true)
    if (!back.ok) return
    expect(back.spec).toHaveLength(2)
  })
})

/* ── 参数名一致性 ── */

describe('findUndeclaredPolicyParams · 策略指向未声明参数', () => {
  it('✅ 全部参数都已声明 → 空数组', () => {
    const rows: PolicyRow[] = [
      { param: 'a', op: 'lte', value: '10' },
      { param: 'b', op: 'in_allowlist', value: 'x' },
    ]
    expect(findUndeclaredPolicyParams(rows, ['a', 'b'])).toEqual([])
  })

  it('✅ 找出未声明的参数名（去重，按首次出现顺序）', () => {
    const rows: PolicyRow[] = [
      { param: 'a', op: 'lte', value: '10' },
      { param: 'zzz', op: 'lte', value: '10' },
      { param: 'a', op: 'in_allowlist', value: 'x' },
      { param: 'yyy', op: 'lte', value: '10' },
    ]
    expect(findUndeclaredPolicyParams(rows, ['a'])).toEqual(['zzz', 'yyy'])
  })

  it('✅ 一个参数都没声明 → 返回全部策略引用的参数名', () => {
    const rows: PolicyRow[] = [
      { param: 'a', op: 'lte', value: '10' },
      { param: 'b', op: 'lte', value: '10' },
    ]
    expect(findUndeclaredPolicyParams(rows, [])).toEqual(['a', 'b'])
  })

  it('✅ 没有策略 → 空数组', () => {
    expect(findUndeclaredPolicyParams([], ['a'])).toEqual([])
  })
})
