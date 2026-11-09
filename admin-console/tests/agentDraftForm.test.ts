// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import {
  buildAgentSpec,
  definitionToDraft,
  emptyAgentDraft,
  normalizeAgentDraft,
  NEW_AGENT_SEGMENT,
} from '@/pages/agents/agentDraftForm'
import type { AgentDefinition } from '@/api/types'

const DEFINITION: AgentDefinition = {
  agentId: 'agent-1',
  version: 5,
  name: '错题归因助手',
  description: '权威描述',
  roles: ['TEACHER', 'REVIEWER'],
  enabledSkills: ['skill-a', 'skill-b'],
  skillBundles: ['bundle-x'],
  capabilityTags: ['tag-1', 'tag-2'],
  overrides: '{"removedSkills":["skill-old"]}',
  orchestration: JSON.stringify({
    mode: 'SEQUENTIAL',
    childAgentRefs: [{ id: 'child-1', version: 2, required: true }],
    maxDelegationDepth: 2,
    maxChildInvocations: 4,
    timeoutMs: 30000,
    failurePolicy: 'FAIL_FAST',
  }),
  published: true,
}

describe('agentDraftForm · 回填（权威定义 → 草稿）', () => {
  it('✅ 关系字段**全部**回填（不是只回填 name/description）', () => {
    const { values, orchestrationError } = definitionToDraft(DEFINITION)
    expect(orchestrationError).toBeNull()
    expect(values.name).toBe('错题归因助手')
    expect(values.description).toBe('权威描述')
    expect(values.roles).toEqual(['TEACHER', 'REVIEWER'])
    expect(values.enabledSkills).toEqual(['skill-a', 'skill-b'])
    expect(values.skillBundles).toEqual(['bundle-x'])
    expect(values.capabilityTags).toEqual(['tag-1', 'tag-2'])
  })

  it('⛔ overrides 取自权威定义的 overrides（⛔ 不是别的什么全文）', () => {
    const { values } = definitionToDraft(DEFINITION)
    expect(values.overrides).toBe('{"removedSkills":["skill-old"]}')
  })

  it('✅ 编排声明回填为结构化取值', () => {
    const { values } = definitionToDraft(DEFINITION)
    expect(values.orchestrationMode).toBe('SEQUENTIAL')
    expect(values.orchestrationChildren).toEqual([
      { id: 'child-1', version: 2, required: true },
    ])
    expect(values.maxDelegationDepth).toBe(2)
    expect(values.maxChildInvocations).toBe(4)
    expect(values.timeoutMs).toBe(30000)
    expect(values.failurePolicy).toBe('FAIL_FAST')
  })

  it('⛔ 编排声明解析失败 → 显式报错，但**关系字段照常回填**', () => {
    const { values, orchestrationError } = definitionToDraft({
      ...DEFINITION,
      orchestration: '{ 这不是 JSON',
    })
    expect(orchestrationError).toBeTruthy()
    // ⚠️ 关系字段不能因为编排解析失败就一起丢（那等于「改一下就把关系清了」）
    expect(values.roles).toEqual(['TEACHER', 'REVIEWER'])
    expect(values.enabledSkills).toEqual(['skill-a', 'skill-b'])
    // ⛔ 编排字段**不得**被静默当 LEAF 回填
    expect(values.orchestrationMode).toBeUndefined()
  })
})

describe('agentDraftForm · 校验与归一化', () => {
  it('✅ 空白草稿的默认值：overrides = {}，编排 = LEAF', () => {
    const draft = emptyAgentDraft()
    expect(draft.overrides).toBe('{}')
    expect(draft.orchestrationMode).toBe('LEAF')
    expect(draft.orchestrationChildren).toEqual([])
  })

  it('⛔ 名称为空 → 拒绝', () => {
    const r = normalizeAgentDraft({ ...emptyAgentDraft(), name: '  ' })
    expect(r.ok).toBe(false)
  })

  it('⛔ overrides 不是合法 JSON → 拒绝', () => {
    const r = normalizeAgentDraft({ ...emptyAgentDraft(), name: 'x', overrides: '{ 坏掉的' })
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.error).toContain('overrides')
  })

  it('⛔ ROUTER 模式缺路由策略引用 → 拒绝（不得「降级成 LEAF 照常发布」）', () => {
    const r = normalizeAgentDraft({
      ...emptyAgentDraft(),
      name: 'x',
      orchestrationMode: 'ROUTER',
    })
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.error).toContain('编排声明非法')
  })

  it('⛔ 非 LEAF 缺委派限制 → 拒绝（平台不提供默认值）', () => {
    const r = normalizeAgentDraft({
      ...emptyAgentDraft(),
      name: 'x',
      orchestrationMode: 'SEQUENTIAL',
      routingPolicyRef: undefined,
    })
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.error).toContain('编排声明非法')
  })

  it('✅ 合法草稿 → spec 含全部字段，且**不含版本号与指纹**', () => {
    const r = normalizeAgentDraft({
      ...emptyAgentDraft(),
      name: '  错题归因助手  ',
      description: '  用途  ',
      roles: ['TEACHER'],
      enabledSkills: ['skill-a'],
      skillBundles: ['bundle-x'],
      capabilityTags: ['tag-1'],
      overrides: '{"removedSkills":["skill-old"]}',
    })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.draft.name).toBe('错题归因助手')
    expect(r.draft.description).toBe('用途')
    expect(r.draft.roles).toEqual(['TEACHER'])
    expect(r.draft.enabledSkills).toEqual(['skill-a'])
    expect(r.draft.skillBundles).toEqual(['bundle-x'])
    expect(r.draft.capabilityTags).toEqual(['tag-1'])
    expect(r.draft.overrides).toBe('{"removedSkills":["skill-old"]}')
    // ⚠️ 缺省编排声明 = LEAF（= 不允许委派），不是「不限制」
    expect(r.draft.orchestration).toContain('LEAF')
    // ⛔ 客户端不得指定版本号 / 内容指纹（二者由服务端决定）
    expect(r.draft).not.toHaveProperty('version')
    expect(r.draft).not.toHaveProperty('configFingerprint')
  })

  it('✅ buildAgentSpec 只负责绑定 agentId（其余由归一化保证）', () => {
    const built = buildAgentSpec('agent-9', {
      ...emptyAgentDraft(),
      name: 'x',
    })
    expect(built.ok).toBe(true)
    if (!built.ok) return
    expect(built.spec.agentId).toBe('agent-9')
    expect(built.spec.roles).toEqual([])
  })

  it('⚠️ 「新建」用保留路由段（登记与修改共用同一张表单）', () => {
    expect(NEW_AGENT_SEGMENT).toBe('new')
  })
})

/**
 * 提示词 / 模型 / 生成参数（2026-11-10 补）。
 *
 * ⚠️ 这三个字段的校验逻辑是 2026-11-09 新增的 221 行代码，当时**零用例**。
 *    这里从导出的 `normalizeAgentDraft` / `definitionToDraft` 入口进，
 *    撤掉任一校验分支，对应用例必须变红。
 */
describe('agentDraftForm · 提示词 / 模型 / 生成参数', () => {
  const valid = () => ({
    ...emptyAgentDraft(),
    name: '错题归因助手',
    instructions: '{"inlineInstructions":"你是数学辅导助手"}',
    modelRef: '  MiniMax-M2.5  ',
    generationParams: '{"temperature":0.3,"maxOutputTokens":2048}',
  })

  it('✅ 三字段原样进草稿（提示词不重新序列化，模型去空白）', () => {
    const r = normalizeAgentDraft(valid())
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.draft.instructions).toBe('{"inlineInstructions":"你是数学辅导助手"}')
    expect(r.draft.modelRef).toBe('MiniMax-M2.5')
    expect(r.draft.generationParams).toBe('{"temperature":0.3,"maxOutputTokens":2048}')
  })

  it('✅ 未声明提示词 / 未绑定模型 → {} 且不带 modelRef 键', () => {
    const r = normalizeAgentDraft({
      ...emptyAgentDraft(),
      name: 'x',
      instructions: '',
      modelRef: '',
    })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.draft.instructions).toBe('{}')
    expect(r.draft.modelRef).toBeUndefined()
  })

  it('⛔ systemPromptRef → 当场拒（V1 未实现引用式提示词库）', () => {
    const r = normalizeAgentDraft({
      ...valid(),
      instructions: '{"systemPromptRef":"prompt-x","systemPromptVersion":"3"}',
    })
    expect(r.ok).toBe(false)
    if (r.ok) return
    expect(r.error).toContain('systemPromptRef')
  })

  it('⛔ instructions 不是合法 JSON 对象 → 拒', () => {
    for (const bad of ['{ 坏掉的', '["inlineInstructions"]']) {
      const r = normalizeAgentDraft({ ...valid(), instructions: bad })
      expect(r.ok).toBe(false)
      if (!r.ok) expect(r.error).toContain('提示词声明')
    }
  })

  it('⛔ inlineInstructions 不是非空字符串 → 拒', () => {
    const r = normalizeAgentDraft({
      ...valid(),
      instructions: '{"inlineInstructions":123}',
    })
    expect(r.ok).toBe(false)
    if (r.ok) return
    expect(r.error).toContain('inlineInstructions')
  })

  it('⛔ 生成参数非法 / 越界 → 拒（不得静默走默认值）', () => {
    const badCases = [
      '{ 坏掉的',
      '{"temperature":9}',
      '{"topP":0}',
      '{"maxOutputTokens":0}',
    ]
    for (const bad of badCases) {
      const r = normalizeAgentDraft({ ...valid(), generationParams: bad })
      expect(r.ok).toBe(false)
      if (!r.ok) expect(r.error).toContain('生成参数')
    }
  })

  it('✅ 权威定义回显三字段 → 编辑既有 Agent 不会被覆盖成空', () => {
    const { values } = definitionToDraft({
      ...DEFINITION,
      instructions: '{"inlineInstructions":"既有提示词"}',
      modelRef: 'Model-Existing',
      generationParams: '{"temperature":0.7}',
    })
    expect(values.instructions).toBe('{"inlineInstructions":"既有提示词"}')
    expect(values.modelRef).toBe('Model-Existing')
    expect(values.generationParams).toBe('{"temperature":0.7}')
  })

  it('✅ buildAgentSpec 带齐三字段（治理通道提交形态）', () => {
    const built = buildAgentSpec('agent-9', valid())
    expect(built.ok).toBe(true)
    if (!built.ok) return
    expect(built.spec.instructions).toContain('你是数学辅导助手')
    expect(built.spec.modelRef).toBe('MiniMax-M2.5')
    expect(built.spec.generationParams).toContain('0.3')
  })
})
