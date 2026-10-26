// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/adminApi', () => ({
  getTool: vi.fn(),
  getToolRelations: vi.fn(),
  getMcp: vi.fn(),
  getToolCatalog: vi.fn(),
  submitPlatformPublishRequest: vi.fn(),
  decidePlatformPublishRequest: vi.fn(),
  applyPlatformPublishRequest: vi.fn(),
  submitPlatformLifecycleRequest: vi.fn(),
}))
import { getMcp, getTool, getToolCatalog, getToolRelations } from '@/api/adminApi'

import { ToolDetailPage } from '@/pages/catalog/ToolDetailPage'
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

/**
 * ⚠️ **全字段**夹具：Tool 定义行（`cap_tool_def`）的**每一个**字段都在这里。
 *
 * <p>本组用例要守的不变量是「登记 / 修改 / 详情三处都显示**所有** Tool 信息」，
 * 所以夹具必须**给全** —— 少给一个字段，用例就会因为「那个字段本来就没数据」
 * 而假装通过。
 */
const TOOL_FULL = {
  toolId: 'tool-full',
  name: '订单查询',
  kind: 'MCP',
  riskLevel: 'READ',
  mcpServerId: 'srv-1',
  mcpToolName: 'query_order',
  inputSchema: '{"type":"object","properties":{"orderId":{"type":"string"}}}',
  paramPolicies: '{"orderId":{"maxLength":32}}',
  version: 4,
  schemaVersion: 2,
  published: true,
  publishedAt: '2026-10-20T00:00:00Z',
  lifecycleState: 'PUBLISHED',
  configFingerprint: 'fp-tool-full',
  createdAt: '2026-10-01T00:00:00Z',
}

const TOOL_RELATIONS = {
  toolId: 'tool-full',
  mcpServer: {
    serverId: 'srv-1',
    transport: 'HTTP',
    trustTier: 'INTERNAL',
    connectionRisk: 'READ',
    version: 2,
    published: true,
  },
}

/** 坏 JSON 的 Tool（入参结构解析不了）—— 必须**原样展示**，不得吞掉。 */
const TOOL_BROKEN_JSON = {
  ...TOOL_FULL,
  inputSchema: '{oops',
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
 * 渲染目录页并**自动打开登记弹窗**（第三十七批 · C37-3：「登记」已改为弹窗）。
 */
function renderRegistrationModal(path = '/tools?register=1') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AntApp>
        <Routes>
          <Route path="/tools" element={<ToolCatalogPage />} />
        </Routes>
      </AntApp>
    </MemoryRouter>,
  )
}

/** Tool 定义行的**全部字段**（详情页必须逐项呈现）。 */
const ALL_TOOL_FIELDS = [
  '标识（toolId）',
  '名称（name）',
  '类型（kind）',
  '风险等级（riskLevel）',
  '所属 MCP Server（mcpServerId）',
  '远端操作名（mcpToolName）',
  '入参结构（inputSchema）',
  '参数策略（paramPolicies）',
  '当前版本（version）',
  '结构版本（schemaVersion）',
  '发布状态（published）',
  '发布时间（publishedAt）',
  '生命周期状态（lifecycleState）',
  '配置指纹（configFingerprint）',
  '创建时间（createdAt）',
]

describe('Tool 全量信息 · 详情页', () => {
  beforeAll(() => {
    vi.mocked(getTool).mockResolvedValue(TOOL_FULL as never)
    vi.mocked(getToolRelations).mockResolvedValue(TOOL_RELATIONS as never)
    vi.mocked(getMcp).mockResolvedValue({
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
      hasCredentialRef: false,
    } as never)
  })

  it('✅ 概览逐项显示 Tool 的**全部**字段（一个都不少）', async () => {
    renderAt('/tools/tool-full', <ToolDetailPage />)
    await waitFor(() => expect(screen.getAllByText('订单查询').length).toBeGreaterThan(0))

    for (const label of ALL_TOOL_FIELDS) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0)
    }
  })

  it('✅ 概览显示各字段的**真实取值**（含 JSON 正文与元信息）', async () => {
    renderAt('/tools/tool-full', <ToolDetailPage />)
    await waitFor(() => expect(screen.getAllByText('订单查询').length).toBeGreaterThan(0))

    // ⚠️ 「srv-1」在概览与关系区各出现一次 —— 用 getAllByText
    expect(screen.getAllByText('srv-1').length).toBeGreaterThan(0)
    expect(screen.getByText('query_order')).toBeTruthy()
    // ⚠️ JSON 正文必须**看得见**（不能只显示一个「已配置」）
    //    用「带空格」的格式化片段定位 `<pre>` —— 草稿 textarea 里是**未格式化**的
    //    原文（无空格），故这两条正则各自只命中概览里的那一处。
    expect(screen.getByText(/"type": "object"/)).toBeTruthy()
    expect(screen.getByText(/"maxLength": 32/)).toBeTruthy()
    // 元信息
    expect(screen.getByText('fp-tool-full')).toBeTruthy()
    expect(screen.getByText('PUBLISHED')).toBeTruthy()
    expect(screen.getByText('2026-10-01T00:00:00Z')).toBeTruthy()
    expect(screen.getByText('2026-10-20T00:00:00Z')).toBeTruthy()
  })

  it('✅ 内置 Tool（NATIVE）如实呈现「不绑定 MCP Server」，不留空让人误以为漏填', async () => {
    vi.mocked(getTool).mockResolvedValue({
      ...TOOL_FULL,
      kind: 'NATIVE',
      mcpServerId: null,
      mcpToolName: null,
    } as never)
    try {
      renderAt('/tools/tool-full', <ToolDetailPage />)
      await waitFor(() => expect(screen.getAllByText('订单查询').length).toBeGreaterThan(0))
      expect(screen.getAllByText(/内置（不绑定 MCP Server）/).length).toBeGreaterThan(0)
    } finally {
      vi.mocked(getTool).mockResolvedValue(TOOL_FULL as never)
    }
  })

  it('⛔ 入参结构不是合法 JSON → 原样展示并标红（不得吞掉内容）', async () => {
    vi.mocked(getTool).mockResolvedValue(TOOL_BROKEN_JSON as never)
    try {
      renderAt('/tools/tool-full', <ToolDetailPage />)
      await waitFor(() => expect(screen.getAllByText('订单查询').length).toBeGreaterThan(0))
      // ⚠️ 坏 JSON 恰恰是最该被看见的东西（照抄进草稿 = 把坏配置复制一份）
      //    概览与草稿 textarea 都带着这段原文，故用 getAllByText。
      expect(screen.getAllByText('{oops').length).toBeGreaterThan(0)
      expect(screen.getAllByText(/不是合法 JSON/).length).toBeGreaterThan(0)
    } finally {
      vi.mocked(getTool).mockResolvedValue(TOOL_FULL as never)
    }
  })
})

describe('Tool 全量信息 · 修改（发新版本）', () => {
  beforeAll(() => {
    vi.mocked(getTool).mockResolvedValue(TOOL_FULL as never)
    vi.mocked(getToolRelations).mockResolvedValue(TOOL_RELATIONS as never)
  })

  it('✅ 草稿表单回填**全部可改字段**（不漏一个）', async () => {
    const { container } = renderAt('/tools/tool-full', <ToolDetailPage />)
    await waitFor(() => expect(screen.getAllByText('订单查询').length).toBeGreaterThan(0))

    const inputs = Array.from(container.querySelectorAll('input')) as HTMLInputElement[]
    const values = inputs.map((i) => i.value)
    expect(values).toContain('tool-full')
    expect(values).toContain('订单查询')
    expect(values).toContain('srv-1')
    expect(values).toContain('query_order')

    // JSON 字段落在 textarea 里
    const areas = Array.from(container.querySelectorAll('textarea')) as HTMLTextAreaElement[]
    const areaValues = areas.map((a) => a.value)
    expect(areaValues.some((v) => v.includes('orderId'))).toBe(true)
    expect(areaValues.some((v) => v.includes('maxLength'))).toBe(true)
  })

  it('✅ 表单旁给出当前生效版本的**基线**（v 几 / 是否发布 / 生命周期 / 指纹）', async () => {
    renderAt('/tools/tool-full', <ToolDetailPage />)
    await waitFor(() => expect(screen.getAllByText('订单查询').length).toBeGreaterThan(0))

    // ⚠️ 基线是**平台生成**的字段，不在表单里 —— 必须在表单旁如实点明
    expect(screen.getByText(/当前生效版本 v4/)).toBeTruthy()
    expect(screen.getByText(/生命周期 PUBLISHED/)).toBeTruthy()
    expect(screen.getByText(/配置指纹 fp-tool-full/)).toBeTruthy()
  })
})

describe('Tool 全量信息 · 登记（新对象 · 弹窗）', () => {
  beforeAll(() => {
    vi.mocked(getTool).mockReset()
    vi.mocked(getToolRelations).mockReset()
    vi.mocked(getToolCatalog).mockResolvedValue([] as never)
  })

  it('✅ 登记弹窗给出 Tool 的**完整字段清单**（含平台生成的那些）', async () => {
    renderRegistrationModal()
    await waitFor(() =>
      expect(screen.getAllByText(/登记 Tool（申请/).length).toBeGreaterThan(0),
    )

    // 定义字段
    expect(screen.getAllByText('toolId（标识）').length).toBeGreaterThan(0)
    expect(screen.getAllByText('kind（类型）').length).toBeGreaterThan(0)
    expect(screen.getAllByText('inputSchema（入参结构）').length).toBeGreaterThan(0)
    expect(screen.getAllByText('paramPolicies（参数策略）').length).toBeGreaterThan(0)
    // ⚠️ 平台生成的字段也必须列出（否则作者不知道它们会从哪来）
    expect(screen.getAllByText('version（当前版本）').length).toBeGreaterThan(0)
    expect(screen.getAllByText('published（发布状态）').length).toBeGreaterThan(0)
    expect(screen.getAllByText('lifecycleState（生命周期状态）').length).toBeGreaterThan(0)
    expect(screen.getAllByText('configFingerprint（配置指纹）').length).toBeGreaterThan(0)
    expect(screen.getAllByText('createdAt（创建时间）').length).toBeGreaterThan(0)
  })

  it('⛔ 登记弹窗不得伪造「当前版本基线」（对象尚不存在）', async () => {
    renderRegistrationModal()
    await waitFor(() =>
      expect(screen.getAllByText(/登记 Tool（申请/).length).toBeGreaterThan(0),
    )
    expect(screen.queryByText(/当前生效版本/)).toBeNull()
  })
})
