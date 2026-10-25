// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import {
  buildOrchestrationJson,
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
    const check = validateOrchestration({ ...base, mode: 'ROUTER' })
    expect(check.ok).toBe(false)
    if (!check.ok) {
      expect(check.error).toContain('ROUTER')
    }
  })

  it('⛔ 子引用缺版本 → 必须被拒（版本必须固定）', () => {
    const check = validateOrchestration({
      ...base,
      mode: 'SEQUENTIAL',
      childAgentRefs: [{ id: 'a-1', version: null, required: true }],
    })
    expect(check.ok).toBe(false)
  })

  it('⛔ 数值字段非正整数 → 必须被拒', () => {
    expect(
      validateOrchestration({ ...base, maxDelegationDepth: 0 }).ok,
    ).toBe(false)
    expect(
      validateOrchestration({ ...base, timeoutMs: -1 }).ok,
    ).toBe(false)
  })

  it('✅ 留空的数值字段合法（由服务端默认值兜底）', () => {
    expect(
      validateOrchestration({
        ...base,
        maxDelegationDepth: null,
        maxChildInvocations: null,
        timeoutMs: null,
      }).ok,
    ).toBe(true)
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
    // ⚠️ **不得**出现数值字段：留空 = 用服务端默认值，
    //    前端预填默认值就等于替评审先定了一套口径（决策项 2 尚未闭环）。
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
        maxDelegationDepth: null,
        maxChildInvocations: null,
        timeoutMs: null,
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
