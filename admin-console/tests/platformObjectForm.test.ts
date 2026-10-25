import { describe, it, expect } from 'vitest'
import {
  buildLifecycleRequest,
  buildMcpServerSpec,
  buildSkillSpec,
  buildToolSpec,
  fromAllowlistTriState,
  planClosure,
  toAllowlistTriState,
  type McpServerFormValues,
  type SkillFormValues,
  type ToolFormValues,
} from '@/pages/catalog/platformObjectForm'

/**
 * 平台级能力对象表单的**纯逻辑**断言（第三十四批 · C34-3）。
 *
 * ⚠️ 这些用例全部按**语义**断言（提交体里到底是什么值），
 *    而不是断言错误文案 —— 文案会改，语义不能改。
 */

describe('allowlist 三态 · null 与 [] 语义不同（§4.4）', () => {
  it('后端的 null / [] / 清单各自映射到不同状态', () => {
    expect(toAllowlistTriState(null)).toBe('UNSET')
    expect(toAllowlistTriState(undefined)).toBe('UNSET')
    expect(toAllowlistTriState([])).toBe('EMPTY')
    expect(toAllowlistTriState(['a'])).toBe('LIST')
  })

  it('三态回写：UNSET→null、EMPTY→[]、LIST→清单', () => {
    expect(fromAllowlistTriState('UNSET', ['a'])).toBeNull()
    expect(fromAllowlistTriState('EMPTY', ['a'])).toEqual([])
    expect(fromAllowlistTriState('LIST', ['a', 'b'])).toEqual(['a', 'b'])
  })

  it('⛔ 状态优先于清单：选了「显式空」就不得带出清单项（否则等于偷偷放宽）', () => {
    expect(fromAllowlistTriState('EMPTY', ['a', 'b'])).toEqual([])
    expect(fromAllowlistTriState('UNSET', ['a', 'b'])).toBeNull()
  })

  it('回写返回新数组，不与表单状态共享引用', () => {
    const source = ['a']
    const result = fromAllowlistTriState('LIST', source)
    expect(result).toEqual(source)
    expect(result).not.toBe(source)
  })
})

describe('Skill spec · 声明的 MCP allowlist 必须三态落地', () => {
  const base: SkillFormValues = {
    skillId: 'sk-1',
    riskCeiling: 'READ',
    declaredTools: [],
  }

  it('UNSET → toolAllowlist 为 null（取 Server 允许集全集）', () => {
    const r = buildSkillSpec({
      ...base,
      declaredMcp: [{ serverId: 'srv-1', allowlistState: 'UNSET' }],
    })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.spec.declaredMcp?.[0]?.toolAllowlist).toBeNull()
  })

  it('⛔ EMPTY → toolAllowlist 必须是 []，绝不能退化成 null（null = 全集 = 权限放宽）', () => {
    const r = buildSkillSpec({
      ...base,
      declaredMcp: [{ serverId: 'srv-1', allowlistState: 'EMPTY', allowlist: ['x'] }],
    })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    const allowlist = r.spec.declaredMcp?.[0]?.toolAllowlist
    expect(allowlist).toEqual([])
    expect(allowlist).not.toBeNull()
  })

  it('LIST → toolAllowlist 为指定清单', () => {
    const r = buildSkillSpec({
      ...base,
      declaredMcp: [{ serverId: 'srv-1', allowlistState: 'LIST', allowlist: ['read', 'write'] }],
    })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.spec.declaredMcp?.[0]?.toolAllowlist).toEqual(['read', 'write'])
  })

  it('⛔ 选了「指定清单」却是空清单 → 当场拒（不得悄悄退化成显式空或未配置）', () => {
    const r = buildSkillSpec({
      ...base,
      declaredMcp: [{ serverId: 'srv-1', allowlistState: 'LIST', allowlist: [] }],
    })
    expect(r.ok).toBe(false)
  })

  it('⛔ 同一个 Server 重复声明 → 当场拒（引用不确定 = 授权边界不确定）', () => {
    const r = buildSkillSpec({
      ...base,
      declaredMcp: [
        { serverId: 'srv-1', allowlistState: 'UNSET' },
        { serverId: 'srv-1', allowlistState: 'EMPTY' },
      ],
    })
    expect(r.ok).toBe(false)
  })
})

describe('Tool spec · kind 与 MCP 绑定字段成对互斥', () => {
  const base: ToolFormValues = {
    toolId: 'tool-1',
    kind: 'MCP',
    riskLevel: 'READ',
  }

  it('kind=MCP 且两项都给 → 通过，两项都进 spec', () => {
    const r = buildToolSpec({ ...base, mcpServerId: 'srv-1', mcpToolName: 'query' })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.spec.mcpServerId).toBe('srv-1')
    expect(r.spec.mcpToolName).toBe('query')
  })

  it('⛔ kind=MCP 缺 mcpToolName → 当场拒（后端会 400，但那时已走到审批）', () => {
    const r = buildToolSpec({ ...base, mcpServerId: 'srv-1' })
    expect(r.ok).toBe(false)
  })

  it('⛔ kind=MCP 缺 mcpServerId → 当场拒', () => {
    const r = buildToolSpec({ ...base, mcpToolName: 'query' })
    expect(r.ok).toBe(false)
  })

  it('⛔ kind=NATIVE 却带了 MCP 绑定字段 → 当场拒', () => {
    const r = buildToolSpec({
      toolId: 'tool-1',
      kind: 'NATIVE',
      riskLevel: 'READ',
      mcpServerId: 'srv-1',
    })
    expect(r.ok).toBe(false)
  })

  it('✅ kind=NATIVE → spec 里不得出现 MCP 绑定键（DB CHECK 也拦，但前端别送）', () => {
    const r = buildToolSpec({ toolId: 'tool-1', kind: 'NATIVE', riskLevel: 'READ' })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.spec.mcpServerId).toBeUndefined()
    expect(r.spec.mcpToolName).toBeUndefined()
  })

  it('⛔ inputSchema 不是合法 JSON → 当场拒（DB 列是 JSONB）', () => {
    const r = buildToolSpec({ ...base, inputSchema: '{ 这不是 JSON' })
    expect(r.ok).toBe(false)
  })

  it('空 JSON 字段补 {}（DB 列 NOT NULL DEFAULT）', () => {
    const r = buildToolSpec({ toolId: 'tool-1', kind: 'NATIVE', riskLevel: 'READ' })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.spec.inputSchema).toBe('{}')
    expect(r.spec.paramPolicies).toBe('{}')
  })
})

describe('MCP Server spec · endpoint 与暴露操作清单', () => {
  const base: McpServerFormValues = {
    serverId: 'srv-1',
    transport: 'HTTP',
    endpoint: 'https://example.internal/mcp',
    authKind: 'TOKEN',
    trustTier: 'INTERNAL',
    exposedAllowlistState: 'UNSET',
    connectionRisk: 'READ',
  }

  it('✅ 合规配置 → spec 带出暴露操作三态（UNSET → null）', () => {
    const r = buildMcpServerSpec(base)
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.spec.exposedToolAllowlist).toBeNull()
  })

  it('⛔ 显式空必须保留为 []（不得退化成 null = 全集）', () => {
    const r = buildMcpServerSpec({ ...base, exposedAllowlistState: 'EMPTY' })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.spec.exposedToolAllowlist).toEqual([])
  })

  it('⛔ http / sse 传输必须配置 endpoint', () => {
    const r = buildMcpServerSpec({ ...base, endpoint: '' })
    expect(r.ok).toBe(false)
  })

  it('⛔ endpoint 不得内嵌用户名 / 口令（凭据仅引用）', () => {
    const r = buildMcpServerSpec({ ...base, endpoint: 'https://user:pass@example.internal/mcp' })
    expect(r.ok).toBe(false)
  })

  it('⛔ endpoint 非 http / https → 当场拒', () => {
    const r = buildMcpServerSpec({ ...base, endpoint: 'ftp://example.internal/mcp' })
    expect(r.ok).toBe(false)
  })
})

describe('停用（生命周期迁移）· 不是物理删除', () => {
  it('✅ 构造出的申请体是 PUBLISHED → DEPRECATED 的单向迁移', () => {
    const r = buildLifecycleRequest('tool', 'tool-1', 3, '  风险过高  ')
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.spec.expectedFrom).toBe('PUBLISHED')
    expect(r.spec.to).toBe('DEPRECATED')
    expect(r.spec.version).toBe(3)
    expect(r.spec.reason).toBe('风险过高')
  })

  it('⛔ 理由为空 → 当场拒（后端会 400）', () => {
    expect(buildLifecycleRequest('tool', 'tool-1', 3, '   ').ok).toBe(false)
  })

  it('⛔ 版本号必须是正整数（版本对不上 CAS 前置条件不满足）', () => {
    expect(buildLifecycleRequest('tool', 'tool-1', 0, '理由').ok).toBe(false)
  })
})

describe('发布集合闭包 · 取不到定义的声明必须如实列出', () => {
  it('声明了但取不到定义的 id 进 unresolved，不得悄悄丢弃', () => {
    const plan = planClosure(
      ['tool-1', 'tool-missing'],
      ['srv-1', 'srv-missing'],
      (id) =>
        id === 'tool-1'
          ? {
              toolId: 'tool-1',
              kind: 'NATIVE',
              riskLevel: 'READ',
              inputSchema: '{}',
              paramPolicies: '{}',
            }
          : null,
      (id) =>
        id === 'srv-1'
          ? {
              serverId: 'srv-1',
              transport: 'HTTP',
              endpoint: 'https://example.internal/mcp',
              authKind: 'NONE',
              trustTier: 'INTERNAL',
              connectionRisk: 'READ',
            }
          : null,
    )
    expect(plan.tools.map((t) => t.toolId)).toEqual(['tool-1'])
    expect(plan.servers.map((s) => s.serverId)).toEqual(['srv-1'])
    expect(plan.unresolvedTools).toEqual(['tool-missing'])
    expect(plan.unresolvedServers).toEqual(['srv-missing'])
  })

  it('重复声明只补齐一次（闭包成员按 id 去重）', () => {
    const plan = planClosure(
      ['tool-1', 'tool-1'],
      [],
      () => ({ toolId: 'tool-1', kind: 'NATIVE', riskLevel: 'READ' }),
      () => null,
    )
    expect(plan.tools).toHaveLength(1)
    expect(plan.unresolvedTools).toEqual([])
  })

  it('⛔ MCP 型 Tool 绑定的 Server 必须**传递**纳入闭包（后端 MCP_NOT_BOUND）', () => {
    // ⚠️ **传递闭包不能省**：后端 `ConfigValidationGate` 要求
    //    「MCP 型 Tool 绑定的 Server」也出现在同一次发布集合里，
    //    否则后端以 `MCP_NOT_BOUND` 拒 —— 而被拒的对象不在用户眼前。
    const plan = planClosure(
      ['tool-mcp'],
      [],
      () => ({
        toolId: 'tool-mcp',
        kind: 'MCP',
        riskLevel: 'READ',
        mcpServerId: 'srv-bound',
        mcpToolName: 'query',
      }),
      (id) => ({ serverId: id, transport: 'HTTP', authKind: 'TOKEN', trustTier: 'INTERNAL', connectionRisk: 'READ' }),
    )
    // ⛔ 该 Server 必须进发布集合
    expect(plan.servers.map((s) => s.serverId)).toEqual(['srv-bound'])
    // ⚠️ 且必须**单独标出**它是传递引入的（区别于「声明里就写了」的）
    expect(plan.transitiveServers).toEqual(['srv-bound'])
  })

  it('⛔ 传递引入的 Server 取不到定义 → 进 unresolved（不得悄悄丢弃）', () => {
    const plan = planClosure(
      ['tool-mcp'],
      [],
      () => ({
        toolId: 'tool-mcp',
        kind: 'MCP',
        riskLevel: 'READ',
        mcpServerId: 'srv-missing',
        mcpToolName: 'query',
      }),
      () => null,
    )
    expect(plan.servers).toHaveLength(0)
    expect(plan.unresolvedServers).toEqual(['srv-missing'])
  })

  it('✅ 已在声明里列出的 Server 不重复引入（传递闭包去重）', () => {
    const plan = planClosure(
      ['tool-mcp'],
      ['srv-bound'],
      () => ({
        toolId: 'tool-mcp',
        kind: 'MCP',
        riskLevel: 'READ',
        mcpServerId: 'srv-bound',
        mcpToolName: 'query',
      }),
      (id) => ({ serverId: id, transport: 'HTTP', authKind: 'TOKEN', trustTier: 'INTERNAL', connectionRisk: 'READ' }),
    )
    expect(plan.servers).toHaveLength(1)
    // ⚠️ 它是**声明里就有的**，不算传递引入
    expect(plan.transitiveServers).toEqual([])
  })
})
