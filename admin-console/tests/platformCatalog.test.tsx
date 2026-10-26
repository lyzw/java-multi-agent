// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => {
  const getTool = vi.fn()
  const getToolRelations = vi.fn()
  const getSkill = vi.fn()
  const getSkillRelations = vi.fn()
  const getMcp = vi.fn()
  const getMcpRelations = vi.fn()
  return {
    getTool,
    getToolRelations,
    // 三步工作流构造器已切到平台链 console 读函数（/api/platform/v1/capabilities/*），
    // 与旧租户链读接口共用同一份 mock（页面收到的数据一致）
    getConsoleTool: getTool,
    getConsoleToolRelations: getToolRelations,
    getSkill,
    getSkillRelations,
    getConsoleSkill: getSkill,
    getConsoleSkillRelations: getSkillRelations,
    getMcp,
    getMcpRelations,
    getConsoleMcp: getMcp,
    getConsoleMcpRelations: getMcpRelations,
    getToolCatalog: vi.fn(),
    getSkillCatalog: vi.fn(),
    getMcpCatalog: vi.fn(),
    getConsoleToolCatalog: vi.fn(),
    getConsoleSkillCatalog: vi.fn(),
    getConsoleMcpCatalog: vi.fn(),
    submitPlatformPublishRequest: vi.fn(),
    decidePlatformPublishRequest: vi.fn(),
    applyPlatformPublishRequest: vi.fn(),
    submitPlatformLifecycleRequest: vi.fn(),
    listPlatformCapabilityRequests: vi.fn(),
  }
})
import {
  applyPlatformPublishRequest,
  decidePlatformPublishRequest,
  getMcp,
  getMcpRelations,
  getSkill,
  getSkillRelations,
  getTool,
  getToolCatalog,
  getToolRelations,
  submitPlatformLifecycleRequest,
  submitPlatformPublishRequest,
} from '@/api/adminApi'
import { ApiError } from '@/api/client'

import { ToolDetailPage } from '@/pages/catalog/ToolDetailPage'
import { SkillDetailPage } from '@/pages/catalog/SkillDetailPage'
import { McpDetailPage } from '@/pages/catalog/McpDetailPage'
import { ToolCatalogPage } from '@/pages/catalog/ToolCatalogPage'

// antd v5 渲染期读 matchMedia；jsdom 默认没有，补一个最小桩
beforeAll(() => {
  if (!window.matchMedia) {
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })) as unknown as typeof window.matchMedia
  }
})

// ---- 夹具 ----

const TOOL = {
  toolId: 'tool-1',
  name: '订单查询',
  kind: 'MCP',
  riskLevel: 'READ',
  mcpServerId: 'srv-1',
  mcpToolName: 'query',
  inputSchema: '{"type":"object"}',
  paramPolicies: '{}',
  version: 3,
  schemaVersion: 1,
  published: true,
  publishedAt: '2026-10-20T00:00:00Z',
}

const TOOL_RELATIONS = {
  toolId: 'tool-1',
  mcpServer: {
    serverId: 'srv-1',
    transport: 'HTTP',
    trustTier: 'INTERNAL',
    connectionRisk: 'READ',
    version: 2,
    published: true,
  },
}

const SKILL = {
  skillId: 'sk-1',
  name: '订单分析',
  description: '分析订单',
  declaredTools: ['tool-1'],
  declaredMcp: [{ serverId: 'srv-1', toolAllowlist: null }],
  riskCeiling: 'READ',
  paramPolicies: '{}',
  requiredCaps: ['order.read'],
  version: 2,
  schemaVersion: 1,
  published: true,
  publishedAt: '2026-10-20T00:00:00Z',
}

/**
 * ⚠️ 「显式空」夹具（第三十四批 · C34-3 补测 G1/G2）。
 *
 * <p>⚠️ **为什么必须专门造一个 `[]` 夹具**：主夹具 `SKILL` / `MCP` 的
 * `toolAllowlist` / `exposedToolAllowlist` 都是 `null`（未配置）。
 * 若只用 `null` 夹具，「显式空 → 提交时仍是 `[]`」这条**页面级接线**
 * 永远不会被验证 —— 而那正是本批最核心的不变量
 * （`[]` = 不允许任何操作；`null` = 取全集；**归一化就是权限放宽**）。
 */
const SKILL_EXPLICIT_EMPTY = {
  ...SKILL,
  declaredMcp: [{ serverId: 'srv-1', toolAllowlist: [] }],
}

const SKILL_RELATIONS = {
  skillId: 'sk-1',
  tools: [
    { toolId: 'tool-1', name: '订单查询', kind: 'MCP', riskLevel: 'READ', version: 3, published: true },
  ],
  // ⚠️ 声明了但对象不存在 —— 必须如实呈现
  missingTools: ['tool-missing'],
  mcpServers: [
    { serverId: 'srv-1', transport: 'HTTP', trustTier: 'INTERNAL', connectionRisk: 'READ', version: 2, published: true },
  ],
  missingMcpServers: ['srv-missing'],
}

const MCP = {
  serverId: 'srv-1',
  version: 2,
  transport: 'HTTP',
  endpoint: 'https://example.internal/mcp',
  authKind: 'TOKEN',
  trustTier: 'INTERNAL',
  exposedToolAllowlist: null,
  connectionRisk: 'READ',
  dynamicDiscovery: false,
  configFingerprint: 'fp-1',
  schemaVersion: 1,
  createdAt: '2026-10-01T00:00:00Z',
  publishedAt: '2026-10-20T00:00:00Z',
  // ⚠️ 默认夹具**不带**凭据引用：闭包补齐会把这个 Server 一并重发，
  //    而重发出来的 spec **必然没有凭据引用**（目录接口从不回显定位符）⇒
  //    带凭据的 Server 会被 fail-closed 拦住。
  //    故「正常路径」用无凭据夹具，带凭据的情形另立专用夹具（见下）。
  hasCredentialRef: false,
}

/**
 * ⚠️ **带凭据引用**的 MCP Server 夹具（第三十四批 · C34-3）。
 *
 * <p>⚠️ 闭包补齐会把这个 Server 一并重发，而目录接口**从不回显凭据定位符**
 * ⇒ 重发出来的新版本**没有凭据引用** ⇒ 全平台生效配置的认证失效。
 * 故这类 Server 必须被 **fail-closed 拦住**（不许静默重发）。
 */
const MCP_WITH_CREDENTIAL = { ...MCP, hasCredentialRef: true }

const MCP_RELATIONS = {
  serverId: 'srv-1',
  exposedToolAllowlist: null,
  tools: [
    { toolId: 'tool-1', name: '订单查询', kind: 'MCP', riskLevel: 'READ', version: 3, published: true },
  ],
}

function renderAt(path: string, element: JSX.Element) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AntApp>
        <Routes>
          <Route path={path.split('/').slice(0, 2).join('/') + '/:id'} element={element} />
        </Routes>
      </AntApp>
    </MemoryRouter>,
  )
}

/**
 * 渲染目录页（第三十七批 · C37-3：「登记」已改为弹窗，弹窗挂在目录页上）。
 *
 * @param path 初始路径（带 `?register=1` 即自动打开登记弹窗）
 * @param element 目录页组件
 */
function renderCatalog(path: string, element: JSX.Element) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AntApp>
        <Routes>
          <Route path={path.split('?')[0]!.split('/')[1]!} element={element} />
        </Routes>
      </AntApp>
    </MemoryRouter>,
  )
}

const REQUEST_OK = {
  requestId: 'req-1234567890',
  scope: 'PLATFORM',
  operation: 'PUBLISH',
  state: 'PENDING' as const,
  requesterId: 'u-1',
  approverId: undefined,
  decisionNote: undefined,
  failureReason: undefined,
  requestedAt: '2026-10-25T00:00:00Z',
  decidedAt: undefined,
  appliedAt: undefined,
}

describe('Tool 详情页 · 概览 / 关系 / 三步工作流', () => {
  beforeAll(() => {
    vi.mocked(getTool).mockResolvedValue(TOOL as never)
    vi.mocked(getToolRelations).mockResolvedValue(TOOL_RELATIONS as never)
    vi.mocked(getMcp).mockResolvedValue(MCP as never)
  })

  it('✅ 渲染概览：当前版本、发布状态与关键字段', async () => {
    renderAt('/tools/tool-1', <ToolDetailPage />)
    await waitFor(() => expect(screen.getByText('v3')).toBeTruthy())
    expect(screen.getAllByText('已发布').length).toBeGreaterThan(0)
    expect(screen.getAllByText('订单查询').length).toBeGreaterThan(0)
  })

  it('✅ 渲染关系：所属 MCP Server', async () => {
    renderAt('/tools/tool-1', <ToolDetailPage />)
    await waitFor(() => expect(screen.getAllByText('所属 MCP Server').length).toBeGreaterThan(0))
    expect(screen.getAllByText('srv-1').length).toBeGreaterThan(0)
  })

  it('✅ 三步工作流：申请 → 审批 → 执行依次调用对应端点', async () => {
    vi.mocked(submitPlatformPublishRequest).mockResolvedValue(REQUEST_OK as never)
    vi.mocked(decidePlatformPublishRequest).mockResolvedValue({
      ...REQUEST_OK,
      state: 'APPROVED',
      approverId: 'u-2',
      decidedAt: '2026-10-25T01:00:00Z',
    } as never)
    vi.mocked(applyPlatformPublishRequest).mockResolvedValue({
      ...REQUEST_OK,
      state: 'APPLIED',
      approverId: 'u-2',
      appliedAt: '2026-10-25T02:00:00Z',
    } as never)

    renderAt('/tools/tool-1', <ToolDetailPage />)
    await waitFor(() => expect(screen.getByText('v3')).toBeTruthy())

    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '改绑 MCP Server' } })
    fireEvent.click(screen.getByRole('button', { name: /提交新版本申请/ }))

    await waitFor(() => expect(submitPlatformPublishRequest).toHaveBeenCalledTimes(1))
    const body = vi.mocked(submitPlatformPublishRequest).mock.calls[0]![0]
    expect(body.reason).toBe('改绑 MCP Server')
    expect(body.tools).toHaveLength(1)
    expect(body.tools?.[0]?.toolId).toBe('tool-1')
    expect(body.tools?.[0]?.kind).toBe('MCP')
    expect(body.tools?.[0]?.mcpServerId).toBe('srv-1')
    // ⚠️ 平台级发布必须自洽闭包：MCP 型 Tool 的 Server 必须一并纳入
    expect(body.mcpServers).toHaveLength(1)
    expect(body.mcpServers?.[0]?.serverId).toBe('srv-1')

    // 第二步：审批
    await waitFor(() => expect(screen.getByText(/第二步：审批/)).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: /提交审批决定/ }))
    await waitFor(() => expect(decidePlatformPublishRequest).toHaveBeenCalledTimes(1))
    expect(vi.mocked(decidePlatformPublishRequest).mock.calls[0]![0]).toBe('req-1234567890')

    // 第三步：执行
    await waitFor(() => expect(screen.getByText(/第三步：执行/)).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: /执行变更/ }))
    await waitFor(() => expect(applyPlatformPublishRequest).toHaveBeenCalledTimes(1))
    expect(vi.mocked(applyPlatformPublishRequest).mock.calls[0]![0]).toBe('req-1234567890')
  })

  it('⛔ 403 → 提示「权限不足（403）」，不得当成普通失败', async () => {
    vi.mocked(submitPlatformPublishRequest).mockReset()
    vi.mocked(submitPlatformPublishRequest).mockRejectedValue(new ApiError(403, 'forbidden'))

    renderAt('/tools/tool-1', <ToolDetailPage />)
    await waitFor(() => expect(screen.getByText('v3')).toBeTruthy())
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '理由' } })
    fireEvent.click(screen.getByRole('button', { name: /提交新版本申请/ }))

    await waitFor(() => expect(screen.getByText(/权限不足（403）/)).toBeTruthy())
  })

  it('⛔ 400 → 提示「门禁预检拒绝（400）」', async () => {
    vi.mocked(submitPlatformPublishRequest).mockReset()
    vi.mocked(submitPlatformPublishRequest).mockRejectedValue(new ApiError(400, 'bad'))

    renderAt('/tools/tool-1', <ToolDetailPage />)
    await waitFor(() => expect(screen.getByText('v3')).toBeTruthy())
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '理由' } })
    fireEvent.click(screen.getByRole('button', { name: /提交新版本申请/ }))

    await waitFor(() => expect(screen.getByText(/门禁预检拒绝（400）/)).toBeTruthy())
  })

  it('⛔ 503 → 提示「平台运维通道未接入（503）」，明确这是环境问题而非用户错误', async () => {
    vi.mocked(submitPlatformPublishRequest).mockReset()
    vi.mocked(submitPlatformPublishRequest).mockRejectedValue(new ApiError(503, 'ops down'))

    renderAt('/tools/tool-1', <ToolDetailPage />)
    await waitFor(() => expect(screen.getByText('v3')).toBeTruthy())
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '理由' } })
    fireEvent.click(screen.getByRole('button', { name: /提交新版本申请/ }))

    await waitFor(() => expect(screen.getByText(/平台运维通道未接入（503）/)).toBeTruthy())
  })

  it('✅ 停用走 lifecycle-requests（不是物理删除，也不复用 publish-requests）', async () => {
    vi.mocked(submitPlatformLifecycleRequest).mockReset()
    vi.mocked(submitPlatformLifecycleRequest).mockResolvedValue(REQUEST_OK as never)
    // ⚠️ 先清空发布通道的调用记录：本用例要断言「停用**不**走发布通道」
    vi.mocked(submitPlatformPublishRequest).mockReset()

    renderAt('/tools/tool-1', <ToolDetailPage />)
    await waitFor(() => expect(screen.getByText('v3')).toBeTruthy())

    fireEvent.change(screen.getByLabelText(/停用理由/), { target: { value: '风险过高' } })
    fireEvent.click(screen.getByRole('button', { name: /提交停用申请/ }))

    await waitFor(() => expect(submitPlatformLifecycleRequest).toHaveBeenCalledTimes(1))
    const body = vi.mocked(submitPlatformLifecycleRequest).mock.calls[0]![0]
    expect(body.type).toBe('tool')
    expect(body.objectId).toBe('tool-1')
    expect(body.version).toBe(3)
    expect(body.expectedFrom).toBe('PUBLISHED')
    expect(body.to).toBe('DEPRECATED')
    expect(body.reason).toBe('风险过高')
    // ⚠️ 停用不得走发布通道（那是「发新版本」）
    expect(submitPlatformPublishRequest).not.toHaveBeenCalled()
  })

  it('⛔ 页面不提供任何物理删除入口', async () => {
    renderAt('/tools/tool-1', <ToolDetailPage />)
    await waitFor(() => expect(screen.getByText('v3')).toBeTruthy())
    // ⚠️ 断言必须**归一化空白后再比对**：antd 会给**两字中文**按钮名中间插空格
    //    （「删除」渲染成「删 除」），直接用 /^删除/ 去匹配可访问名**永远落空** ——
    //    那等于「就算真摆了个删除按钮，这条用例也照样绿」（空断言）。
    //    故这里扫全部按钮、取归一化文本，再断言没有「删除」。
    const buttonTexts = screen.getAllByRole('button').map((b) => (b.textContent ?? '').replace(/\s+/g, ''))
    expect(buttonTexts).not.toContain('删除')
    // 附证：停用入口**在**（证明上一条不是因为「整块都没渲染」而绿）
    expect(buttonTexts).toContain('提交停用申请')
  })
})

describe('Skill 详情页 · 关系缺失项必须标红', () => {
  beforeAll(() => {
    vi.mocked(getSkill).mockResolvedValue(SKILL as never)
    vi.mocked(getSkillRelations).mockResolvedValue(SKILL_RELATIONS as never)
    vi.mocked(getTool).mockResolvedValue(TOOL as never)
    vi.mocked(getMcp).mockResolvedValue(MCP as never)
  })

  it('✅ 渲染声明的 Tool / MCP 关系', async () => {
    renderAt('/skills/sk-1', <SkillDetailPage />)
    await waitFor(() => expect(screen.getAllByText(/声明的 Tool/).length).toBeGreaterThan(0))
    expect(screen.getAllByText(/声明的 MCP Server/).length).toBeGreaterThan(0)
  })

  it('✅ 缺失的 Tool 与 MCP Server 单独列出（不得悄悄从清单里消失）', async () => {
    renderAt('/skills/sk-1', <SkillDetailPage />)
    await waitFor(() => expect(screen.getByText(/声明了但对象不存在的 Tool/)).toBeTruthy())
    expect(screen.getByText(/声明了但对象不存在的 MCP Server/)).toBeTruthy()
    expect(screen.getByText('tool-missing')).toBeTruthy()
    expect(screen.getByText('srv-missing')).toBeTruthy()
  })

  it('✅ 缺失项告警是 error 级（标红），不是普通提示', async () => {
    const { container } = renderAt('/skills/sk-1', <SkillDetailPage />)
    await waitFor(() => expect(screen.getByText(/声明了但对象不存在的 Tool/)).toBeTruthy())
    const alerts = container.querySelectorAll('.ant-alert-error')
    expect(alerts.length).toBeGreaterThanOrEqual(2)
  })

  it('✅ 修改提交时把声明的 Tool / MCP 一并纳入发布集合（自洽闭包）', async () => {
    vi.mocked(submitPlatformPublishRequest).mockReset()
    vi.mocked(submitPlatformPublishRequest).mockResolvedValue(REQUEST_OK as never)

    renderAt('/skills/sk-1', <SkillDetailPage />)
    await waitFor(() => expect(screen.getAllByText(/声明的 Tool/).length).toBeGreaterThan(0))
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '调整声明' } })
    fireEvent.click(screen.getByRole('button', { name: /提交新版本申请/ }))

    await waitFor(() => expect(submitPlatformPublishRequest).toHaveBeenCalledTimes(1))
    const body = vi.mocked(submitPlatformPublishRequest).mock.calls[0]![0]
    expect(body.skills?.[0]?.skillId).toBe('sk-1')
    expect(body.tools?.map((t) => t.toolId)).toEqual(['tool-1'])
    expect(body.mcpServers?.map((s) => s.serverId)).toEqual(['srv-1'])
    // ⚠️ toolAllowlist 为 null（未配置）必须原样送出，不得归一成 []
    expect(body.skills?.[0]?.declaredMcp?.[0]?.toolAllowlist).toBeNull()
  })

  it('⛔ 已登记凭据引用的 MCP Server 被闭包补齐连带重发 → 必须拦住（不得静默丢凭据）', async () => {
    vi.mocked(submitPlatformPublishRequest).mockReset()
    vi.mocked(submitPlatformPublishRequest).mockResolvedValue(REQUEST_OK as never)
    // ⚠️ 切到**带凭据**的 Server：闭包补齐会把它一并重发，
    //    而目录接口**只给布尔**、凭据定位符永不入响应
    //    ⇒ 重发出来的 spec **必然没有凭据引用**
    //    ⇒ 后端建新版本行时凭据引用为 null ⇒ 全平台生效配置的认证失效。
    //    提交者改的是 Skill，却静默改掉了 MCP 的认证 —— 必须 fail-closed。
    vi.mocked(getMcp).mockResolvedValue(MCP_WITH_CREDENTIAL as never)
    try {
      renderAt('/skills/sk-1', <SkillDetailPage />)
      await waitFor(() => expect(screen.getAllByText(/声明的 Tool/).length).toBeGreaterThan(0))
      fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '调整声明' } })
      fireEvent.click(screen.getByRole('button', { name: /提交新版本申请/ }))

      // ⛔ 不得发起发布请求
      await waitFor(() => expect(submitPlatformPublishRequest).not.toHaveBeenCalled())
      // ⛔ 且必须**点名**是哪个 Server（笼统报错会让运维无从下手）
      await waitFor(() => expect(screen.getAllByText(/凭据/).length).toBeGreaterThan(0))
    } finally {
      vi.mocked(getMcp).mockResolvedValue(MCP as never)
    }
  })
})

describe('三态闭环（页面级接线 · 第三十四批 · C34-3 补测 G1/G2）', () => {
  beforeAll(() => {
    vi.mocked(getTool).mockResolvedValue(TOOL as never)
    vi.mocked(getMcp).mockResolvedValue(MCP as never)
  })

  it('⛔ Skill 的声明 allowlist 为「显式空」→ 提交体必须是 []（不得退化成 null）', async () => {
    vi.mocked(getSkill).mockResolvedValue(SKILL_EXPLICIT_EMPTY as never)
    vi.mocked(getSkillRelations).mockResolvedValue(SKILL_RELATIONS as never)
    vi.mocked(submitPlatformPublishRequest).mockReset()
    vi.mocked(submitPlatformPublishRequest).mockResolvedValue(REQUEST_OK as never)

    renderAt('/skills/sk-1', <SkillDetailPage />)
    await waitFor(() => expect(screen.getAllByText(/声明的 Tool/).length).toBeGreaterThan(0))
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '调整声明' } })
    fireEvent.click(screen.getByRole('button', { name: /提交新版本申请/ }))

    await waitFor(() => expect(submitPlatformPublishRequest).toHaveBeenCalledTimes(1))
    const body = vi.mocked(submitPlatformPublishRequest).mock.calls[0]![0]
    const allowlist = body.skills?.[0]?.declaredMcp?.[0]?.toolAllowlist
    // ⚠️ **本批最核心的不变量**：[] = 不允许任何操作；null = 取全集。
    //    页面这一段接线若把 [] 归一成 null，就是**权限放宽**（fail-open）。
    expect(allowlist).toEqual([])
    expect(allowlist).not.toBeNull()
  })

  it('⛔ MCP 的暴露清单为「显式空」→ 提交体必须是 []（不得退化成 null）', async () => {
    vi.mocked(getMcp).mockResolvedValue({ ...MCP, exposedToolAllowlist: [] } as never)
    vi.mocked(getMcpRelations).mockResolvedValue({
      ...MCP_RELATIONS,
      exposedToolAllowlist: [],
    } as never)
    vi.mocked(submitPlatformPublishRequest).mockReset()
    vi.mocked(submitPlatformPublishRequest).mockResolvedValue(REQUEST_OK as never)

    renderAt('/mcp/srv-1', <McpDetailPage />)
    await waitFor(() => expect(screen.getAllByText(/暴露操作（允许集/).length).toBeGreaterThan(0))
    fireEvent.change(screen.getByLabelText(/变更理由/), { target: { value: '收紧暴露面' } })
    fireEvent.click(screen.getByRole('button', { name: /提交新版本申请/ }))

    await waitFor(() => expect(submitPlatformPublishRequest).toHaveBeenCalledTimes(1))
    const body = vi.mocked(submitPlatformPublishRequest).mock.calls[0]![0]
    const allowlist = body.mcpServers?.[0]?.exposedToolAllowlist
    expect(allowlist).toEqual([])
    expect(allowlist).not.toBeNull()
  })
})

describe('登记模式 · 「新增」改用弹窗（第三十七批 · C37-3）', () => {
  beforeAll(() => {
    vi.mocked(getTool).mockReset()
    vi.mocked(getToolRelations).mockReset()
    vi.mocked(getMcp).mockReset()
    vi.mocked(getSkill).mockReset()
    vi.mocked(getToolCatalog).mockResolvedValue([] as never)
  })

  it('✅ /tools/new 只作深链入口 → 不读既有对象（登记改在弹窗里做）', async () => {
    renderAt('/tools/new', <ToolDetailPage />)
    // ⚠️ 登记不得去读一个不存在的对象
    expect(getTool).not.toHaveBeenCalled()
    expect(getToolRelations).not.toHaveBeenCalled()
    // ⚠️ 也不再有「整页登记」：页面本身不再渲染登记表单
    expect(screen.queryByLabelText(/Tool 标识/)).toBeNull()
  })

  it('✅ 目录页点「登记 Tool」→ 弹窗打开，标识可编辑', async () => {
    renderCatalog('/tools', <ToolCatalogPage />)
    const button = await screen.findByRole('button', { name: /登记 Tool/ })
    fireEvent.click(button)

    await waitFor(() =>
      expect(screen.getAllByText(/登记 Tool（申请/).length).toBeGreaterThan(0),
    )
    // 标识在登记模式下**可编辑**（登记新对象才需要填）
    expect(screen.getByLabelText(/Tool 标识/)).toHaveProperty('disabled', false)
    // ⚠️ 登记弹窗不得去读一个不存在的对象
    expect(getTool).not.toHaveBeenCalled()
    expect(getToolRelations).not.toHaveBeenCalled()
  })

  it('✅ 深链 /tools?register=1 → 目录页自动打开登记弹窗', async () => {
    renderCatalog('/tools?register=1', <ToolCatalogPage />)
    await waitFor(() =>
      expect(screen.getAllByText(/登记 Tool（申请/).length).toBeGreaterThan(0),
    )
    expect(screen.getByLabelText(/Tool 标识/)).toHaveProperty('disabled', false)
  })

  it('✅ /skills/new 同样只作深链入口（不再有整页登记）', async () => {
    renderAt('/skills/new', <SkillDetailPage />)
    expect(getSkill).not.toHaveBeenCalled()
    expect(screen.queryByLabelText(/Skill 标识/)).toBeNull()
  })
})

describe('MCP 详情页 · 允许集与实际登记必须分开呈现', () => {
  beforeAll(() => {
    vi.mocked(getMcp).mockResolvedValue(MCP as never)
    vi.mocked(getMcpRelations).mockResolvedValue(MCP_RELATIONS as never)
  })

  it('✅ 允许集与实际登记是两个独立区块', async () => {
    renderAt('/mcp/srv-1', <McpDetailPage />)
    await waitFor(() => expect(screen.getAllByText(/暴露操作（允许集/).length).toBeGreaterThan(0))
    expect(screen.getAllByText(/实际登记的 Tool/).length).toBeGreaterThan(0)
  })

  it('✅ 允许集为 null 时呈现「未配置（取全集）」，不得显示成「显式空」', async () => {
    renderAt('/mcp/srv-1', <McpDetailPage />)
    await waitFor(() => expect(screen.getAllByText(/暴露操作（允许集/).length).toBeGreaterThan(0))
    expect(screen.getAllByText('未配置（取全集）').length).toBeGreaterThan(0)
    // ⚠️ 负向断言必须**匹配真实存在的文案**：表单里的选项是
    //    「显式空（∅ · 不允许任何操作）」，若这里查的是「显式空（∅）」，
    //    那么**即使真的选中了显式空**，这条断言也照样成立 —— 那是**空转的反证**，
    //    等于「测了一个永远不会发生的场景」。
    //    故断言改成「不得出现**以「显式空」开头**的文案」：
    //    命中任一显式空表述（无论后缀是什么）都会让它变红。
    //    ⚠️ 用 `queryAllByText`（找不到返回空数组），不能用 `getAllByText`
    //    （找不到会直接抛错 —— 那测的是「文案是否存在」，不是「状态对不对」）。
    const explicitEmptyLabels = screen
      .queryAllByText(/显式空/)
      .filter((el) => (el.textContent ?? '').startsWith('显式空'))
    expect(explicitEmptyLabels).toHaveLength(0)
  })

  it('✅ 已登记凭据引用时给出提醒（目录接口从不回显定位符）', async () => {
    // ⚠️ 这条必须用**带凭据**的夹具 —— 否则它会因为「本来就没凭据」而绿，
    //    变成一条**永远成立**的空断言。
    vi.mocked(getMcp).mockResolvedValue(MCP_WITH_CREDENTIAL as never)
    try {
      renderAt('/mcp/srv-1', <McpDetailPage />)
      await waitFor(() => expect(screen.getByText('已登记（定位符不回显）')).toBeTruthy())
      expect(screen.getByText(/目录接口从不回显它/)).toBeTruthy()
    } finally {
      vi.mocked(getMcp).mockResolvedValue(MCP as never)
    }
  })
})
