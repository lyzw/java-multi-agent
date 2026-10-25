// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import {
  buildOrchestrationJson,
  evaluatePublishGate,
  parseOrchestrationJson,
  validateOrchestration,
  validatePinnedRef,
  type OrchestrationFormValues,
} from '@/pages/orchestration/orchestrationForm'

/**
 * 编排声明表单的纯逻辑测试（多级 Agent v1.1 · 第二十九批 · C29-1 · A6）。
 *
 * <p><b>⚠️ 断言的是「语义」而不是「文本长相」</b>：
 * 例如序列化结果一律用 {@code JSON.parse} 后按字段断言，
 * 而不是比对字符串 —— 键序变化不该让用例红。
 */
describe('编排声明表单 · 校验', () => {
  const base: OrchestrationFormValues = {
    mode: 'LEAF',
    childAgentRefs: [],
    routingPolicyRef: null,
    resultContractRef: null,
    maxDelegationDepth: null,
    maxChildInvocations: null,
    timeoutMs: null,
    failurePolicy: null,
  }

  /** 非 LEAF 的**合法**限制组合（三个限制必须齐全）。 */
  const limits = {
    maxDelegationDepth: 3,
    maxChildInvocations: 8,
    timeoutMs: 30000,
  }

  it('✅ LEAF 且无子引用 → 合法', () => {
    expect(validateOrchestration(base)).toEqual({ ok: true })
  })

  it('⛔ LEAF 却声明了子引用 → 必须被拒（声明了即为配置错误）', () => {
    const check = validateOrchestration({
      ...base,
      childAgentRefs: [{ id: 'a-1', version: 1, required: true }],
    })
    expect(check.ok).toBe(false)
    if (!check.ok) {
      expect(check.error).toContain('LEAF')
    }
  })

  it('⛔ ROUTER 缺路由策略引用 → 必须被拒', () => {
    const check = validateOrchestration({ ...base, mode: 'ROUTER', ...limits })
    expect(check.ok).toBe(false)
    if (!check.ok) {
      expect(check.error).toContain('ROUTER')
    }
  })

  it('⛔ 子引用缺版本 → 必须被拒（版本必须固定）', () => {
    const check = validateOrchestration({
      ...base,
      mode: 'SEQUENTIAL',
      ...limits,
      childAgentRefs: [{ id: 'a-1', version: null, required: true }],
    })
    expect(check.ok).toBe(false)
    if (!check.ok) {
      expect(check.error).toContain('版本')
    }
  })

  it('⛔ 数值字段非正整数 → 必须被拒', () => {
    expect(
      validateOrchestration({ ...base, maxDelegationDepth: 0 }).ok,
    ).toBe(false)
    expect(
      validateOrchestration({ ...base, timeoutMs: -1 }).ok,
    ).toBe(false)
  })

  it('✅ LEAF 留空的数值字段合法（不适用，而非「用默认值」）', () => {
    expect(
      validateOrchestration({
        ...base,
        maxDelegationDepth: null,
        maxChildInvocations: null,
        timeoutMs: null,
      }).ok,
    ).toBe(true)
  })

  // ── 决策 2：非 LEAF 必须显式声明三个委派限制 ──────────────────────

  it('⛔ SEQUENTIAL 缺任一委派限制 → 逐个都被拒（缺一个 = 少一道上限）', () => {
    const incomplete: OrchestrationFormValues[] = [
      { ...base, mode: 'SEQUENTIAL', maxChildInvocations: 8, timeoutMs: 30000 },
      { ...base, mode: 'SEQUENTIAL', maxDelegationDepth: 3, timeoutMs: 30000 },
      { ...base, mode: 'SEQUENTIAL', maxDelegationDepth: 3, maxChildInvocations: 8 },
    ]
    for (const values of incomplete) {
      const check = validateOrchestration(values)
      expect(check.ok).toBe(false)
      if (!check.ok) {
        expect(check.error).toContain('必填')
      }
    }
  })

  it('⛔ ROUTER 缺任一委派限制 → 同样被拒（决策不区分模式）', () => {
    const check = validateOrchestration({
      ...base,
      mode: 'ROUTER',
      routingPolicyRef: 'policy-a@1',
      maxDelegationDepth: 3,
      maxChildInvocations: 8,
    })
    expect(check.ok).toBe(false)
    if (!check.ok) {
      expect(check.error).toContain('必填')
    }
  })

  it('✅ 非 LEAF 三个限制齐全 → 合法', () => {
    expect(validateOrchestration({ ...base, mode: 'SEQUENTIAL', ...limits }).ok).toBe(true)
    expect(
      validateOrchestration({
        ...base,
        mode: 'ROUTER',
        routingPolicyRef: 'policy-a@1',
        ...limits,
      }).ok,
    ).toBe(true)
  })

  it('✅ maxChildInvocations = 0 合法（真实上限，与后端一致）', () => {
    const check = validateOrchestration({
      ...base,
      mode: 'SEQUENTIAL',
      maxDelegationDepth: 3,
      maxChildInvocations: 0,
      timeoutMs: 30000,
    })
    expect(check.ok).toBe(true)
  })
})

describe('编排声明表单 · 固定版本引用', () => {
  it('✅ id@version 合法', () => {
    expect(validatePinnedRef('policy-a@1', 'routingPolicyRef')).toEqual({ ok: true })
  })

  it('⛔ 裸 id（无版本）→ 必须被拒', () => {
    const check = validatePinnedRef('policy-a', 'routingPolicyRef')
    expect(check.ok).toBe(false)
  })

  it('⛔ latest 漂移别名 → 必须被拒（版本必须固定）', () => {
    expect(validatePinnedRef('latest', 'routingPolicyRef').ok).toBe(false)
    expect(validatePinnedRef('policy-a@latest', 'routingPolicyRef').ok).toBe(false)
  })

  it('⛔ 版本号非正整数 → 必须被拒', () => {
    expect(validatePinnedRef('policy-a@0', 'routingPolicyRef').ok).toBe(false)
    expect(validatePinnedRef('policy-a@x', 'routingPolicyRef').ok).toBe(false)
  })

  it('✅ 空值合法（未声明引用）', () => {
    expect(validatePinnedRef('', 'routingPolicyRef')).toEqual({ ok: true })
    expect(validatePinnedRef(null, 'routingPolicyRef')).toEqual({ ok: true })
  })
})

describe('编排声明表单 · 序列化', () => {
  it('✅ LEAF 且全空 → 只写 mode（其余字段不出现）', () => {
    const json = JSON.parse(
      buildOrchestrationJson({
        mode: 'LEAF',
        childAgentRefs: [],
        routingPolicyRef: null,
        resultContractRef: null,
        maxDelegationDepth: null,
        maxChildInvocations: null,
        timeoutMs: null,
        failurePolicy: null,
      }),
    )
    // ⚠️ **不得**出现数值字段：LEAF 不参与委派 ⇒ 三个限制**不适用**
    //    （不是「用默认值」—— 平台已取消默认值，决策 2）。
    expect(Object.keys(json)).toEqual(['mode'])
    expect(json.mode).toBe('LEAF')
  })

  it('✅ 完整声明 → 各字段都带上', () => {
    const json = JSON.parse(
      buildOrchestrationJson({
        mode: 'SEQUENTIAL',
        childAgentRefs: [{ id: 'a-1', version: 2, required: false }],
        routingPolicyRef: 'policy-a@1',
        resultContractRef: 'answer-contract@1',
        maxDelegationDepth: 3,
        maxChildInvocations: 5,
        timeoutMs: 60000,
        failurePolicy: 'FAIL_FAST',
      }),
    )
    expect(json.mode).toBe('SEQUENTIAL')
    expect(json.childAgentRefs).toEqual([
      { id: 'a-1', version: 2, required: false },
    ])
    expect(json.routingPolicyRef).toBe('policy-a@1')
    expect(json.resultContractRef).toBe('answer-contract@1')
    expect(json.maxDelegationDepth).toBe(3)
    expect(json.maxChildInvocations).toBe(5)
    expect(json.timeoutMs).toBe(60000)
    expect(json.failurePolicy).toBe('FAIL_FAST')
  })

  it('✅ 子引用的空 id 行被剔除（不该把半行提交上去）', () => {
    const json = JSON.parse(
      buildOrchestrationJson({
        mode: 'SEQUENTIAL',
        childAgentRefs: [
          { id: '  ', version: 1, required: true },
          { id: 'a-1', version: 1, required: true },
        ],
        routingPolicyRef: null,
        resultContractRef: null,
        maxDelegationDepth: 3,
        maxChildInvocations: 8,
        timeoutMs: 30000,
        failurePolicy: null,
      }),
    )
    expect(json.childAgentRefs).toHaveLength(1)
    expect(json.childAgentRefs[0].id).toBe('a-1')
  })
})

describe('编排声明表单 · 回显解析', () => {
  it('✅ 已有声明 → 解析回表单', () => {
    const { values } = parseOrchestrationJson(
      JSON.stringify({
        mode: 'ROUTER',
        childAgentRefs: [],
        routingPolicyRef: 'policy-a@1',
        resultContractRef: 'answer-contract@1',
        maxDelegationDepth: 3,
      }),
    )
    expect(values.mode).toBe('ROUTER')
    expect(values.routingPolicyRef).toBe('policy-a@1')
    expect(values.resultContractRef).toBe('answer-contract@1')
    expect(values.maxDelegationDepth).toBe(3)
  })

  it('✅ 非法 JSON → 不抛异常，保留原文（页面不能崩）', () => {
    const { values, raw } = parseOrchestrationJson('{ 这不是 JSON')
    expect(raw).toBe('{ 这不是 JSON')
    expect(values.mode).toBe('LEAF')
  })

  it('✅ 空文本 → LEAF 缺省（MA-T01：缺省等价于 LEAF，不是「不限制」）', () => {
    expect(parseOrchestrationJson('').values.mode).toBe('LEAF')
    expect(parseOrchestrationJson('{}').values.mode).toBe('LEAF')
    expect(parseOrchestrationJson(null).values.mode).toBe('LEAF')
  })
})

/**
 * 发布闸门判据（第三十四批 · C34-3）。
 *
 * <p>⚠️ **为什么要单独测纯函数而不是只测页面**：按钮的 `disabled` 与提交函数内的
 * 提前 `return` **必须共用同一判据**。一旦各写一份，漂移后会出现两种难查的故障：
 * 「按钮可点了却被静默拦下」（用户只看到「点了没反应」），
 * 或「按钮禁用了但程序化调用仍能提交空关系」。
 * 把判据抽成纯函数后，可以**直接断言判据本身**，也能保证两处调用的是同一个。
 */
describe('发布闸门 · evaluatePublishGate', () => {
  const ok = { orchestrationError: null, definitionUnavailable: false, acknowledgeClearRelations: false }

  it('✅ 一切正常 → 放行（blocked=false）', () => {
    const r = evaluatePublishGate(ok)
    expect(r.blocked).toBe(false)
    expect(r.reason).toBeNull()
  })

  it('⛔ 编排声明解析失败 → 硬拦（不得把合法编排静默覆盖成 LEAF）', () => {
    const r = evaluatePublishGate({ ...ok, orchestrationError: '编排声明不是合法 JSON' })
    expect(r.blocked).toBe(true)
    expect(r.reason).toContain('编排声明解析失败')
  })

  it('⛔ 取不到权威定义且未确认 → 硬拦（关系不得被静默清空）', () => {
    const r = evaluatePublishGate({ ...ok, definitionUnavailable: true })
    expect(r.blocked).toBe(true)
    expect(r.reason).toContain('保存会清空关系')
  })

  it('✅ 取不到权威定义但**已显式确认** → 放行（用户知情，故允许）', () => {
    const r = evaluatePublishGate({
      ...ok,
      definitionUnavailable: true,
      acknowledgeClearRelations: true,
    })
    expect(r.blocked).toBe(false)
  })

  it('⛔ 编排解析失败**优先于**回填确认（两个问题同时存在时也必须拦）', () => {
    const r = evaluatePublishGate({
      orchestrationError: '编排模式非法',
      definitionUnavailable: true,
      acknowledgeClearRelations: true,
    })
    expect(r.blocked).toBe(true)
    expect(r.reason).toContain('编排声明解析失败')
  })

  it('⛔ 已确认**不能**掩盖编排解析失败（确认只对「回填不全」有效）', () => {
    // ⚠️ 反证：确认勾选框只解决「关系无从回填」，
    //    若实现把它当成「万能放行」，那么编排解析失败就会被一起放过 ——
    //    那等于「勾一下就能把坏编排提交上去」。
    const r = evaluatePublishGate({
      orchestrationError: '编排声明不是合法 JSON',
      definitionUnavailable: true,
      acknowledgeClearRelations: true,
    })
    expect(r.blocked).toBe(true)
    expect(r.reason).not.toContain('保存会清空关系')
  })
})
