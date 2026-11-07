// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/modelCatalogApi', () => ({
  listModels: vi.fn(),
  getModel: vi.fn(),
  createModel: vi.fn(),
  updateModel: vi.fn(),
  deleteModel: vi.fn(),
  listProviderCredentials: vi.fn(),
  setProviderCredential: vi.fn(),
  deleteProviderCredential: vi.fn(),
}))
import {
  listModels,
  createModel,
  updateModel,
  deleteModel,
  listProviderCredentials,
  setProviderCredential,
  deleteProviderCredential,
} from '@/api/modelCatalogApi'
import { ModelCatalogPage } from '@/pages/models/ModelCatalogPage'

const MODELS = [
  {
    id: 'm1',
    provider: 'openai',
    scenario: 'agent-chat',
    modelName: 'gpt-4o',
    displayName: 'GPT-4o',
    baseUrl: 'https://api.openai.com/v1',
    isDefault: true,
    supportsTools: true,
    supportsStreaming: true,
    supportsVision: true,
    dimension: null,
    isEnabled: true,
    sortOrder: 1,
    description: '默认聊天模型',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  },
  {
    id: 'm2',
    provider: 'bge',
    scenario: 'embedding',
    modelName: 'bge-large-zh',
    displayName: null,
    baseUrl: null,
    isDefault: false,
    supportsTools: false,
    supportsStreaming: false,
    supportsVision: false,
    dimension: 1024,
    isEnabled: false,
    sortOrder: 2,
    description: null,
    createdAt: '2026-10-02T00:00:00Z',
    updatedAt: '2026-10-02T00:00:00Z',
  },
]

const CREDS = [
  { provider: 'openai', maskedKey: 'sk-…aBc9', updatedAt: '2026-10-03T00:00:00Z' },
  { provider: 'minimax', maskedKey: null, updatedAt: null },
]

function renderPage() {
  return render(
    <MemoryRouter>
      <AntApp>
        <ModelCatalogPage />
      </AntApp>
    </MemoryRouter>,
  )
}

/** antd Tabs 默认保留两个页签 DOM，作用域内查询必须用当前激活页签 pane */
function activePane(): HTMLElement {
  return document.querySelector('.ant-tabs-tabpane-active') as HTMLElement
}

beforeEach(() => {
  vi.mocked(listModels).mockReset().mockResolvedValue(MODELS as never)
  vi.mocked(listProviderCredentials).mockReset().mockResolvedValue(CREDS as never)
  vi.mocked(createModel).mockReset().mockImplementation(
    (p) => Promise.resolve({ ...(MODELS[0] as object), ...p, id: 'new', createdAt: '', updatedAt: '' } as never),
  )
  vi.mocked(updateModel).mockReset().mockImplementation((id, p) =>
    Promise.resolve({ ...(MODELS[0] as object), ...p, id } as never),
  )
  vi.mocked(deleteModel).mockReset().mockResolvedValue(undefined as never)
  vi.mocked(setProviderCredential).mockReset().mockImplementation((p) =>
    Promise.resolve({ provider: p.provider, maskedKey: 'sk-…xxxx' } as never),
  )
  vi.mocked(deleteProviderCredential).mockReset().mockResolvedValue(undefined as never)
})

describe('模型配置页 · Feature A 模型定义 CRUD', () => {
  it('✅ 表格渲染模型行（供应商 / 场景 / 模型名称 / 默认标签 / 维度）', async () => {
    renderPage()
    const pane = activePane()
    await waitFor(() => expect(within(pane).getByText('gpt-4o')).toBeTruthy())
    expect(within(pane).getByText('bge-large-zh')).toBeTruthy()
    expect(within(pane).getByText('agent-chat')).toBeTruthy()
    // 默认标签
    expect(within(pane).getAllByText('默认').length).toBeGreaterThan(0)
    // 维度列：1024
    expect(within(pane).getByText('1024')).toBeTruthy()
  })

  it('✅ 「新建模型」打开抽屉，填写必填后提交调用 createModel', async () => {
    renderPage()
    const pane = activePane()
    await waitFor(() => expect(within(pane).getByText('gpt-4o')).toBeTruthy())

    fireEvent.click(within(pane).getByRole('button', { name: /新\s*建\s*模\s*型/ }))
    await waitFor(() =>
      expect(document.querySelector('.ant-drawer-content')).toBeTruthy(),
    )
    const drawerEl = document.querySelector('.ant-drawer-content') as HTMLElement
    expect(drawerEl).toBeTruthy()

    // 抽屉的供应商也是 AutoComplete，取首个 input 作为供应商输入框
    const drawerInputs = drawerEl.querySelectorAll('input')
    fireEvent.change(drawerInputs[0] as HTMLInputElement, {
      target: { value: 'deepseek' },
    })
    fireEvent.change(within(drawerEl).getByPlaceholderText(/如 gpt-4o/), {
      target: { value: 'deepseek-chat' },
    })

    fireEvent.click(within(drawerEl).getByRole('button', { name: /保\s*存/ }))
    await waitFor(() => expect(createModel).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(createModel).mock.calls[0]![0] as unknown as Record<string, unknown>
    expect(payload.provider).toBe('deepseek')
    expect(payload.modelName).toBe('deepseek-chat')
    expect(payload.scenario).toBeTruthy()
  })

  it('✅ 缺少必填项时不会调用 createModel（校验拦截）', async () => {
    renderPage()
    const pane = activePane()
    await waitFor(() => expect(within(pane).getByText('gpt-4o')).toBeTruthy())
    fireEvent.click(within(pane).getByRole('button', { name: /新\s*建\s*模\s*型/ }))
    const drawerEl = document.querySelector('.ant-drawer-content') as HTMLElement
    await within(drawerEl).findByText(/新\s*建\s*模\s*型/)
    // 不填任何必填，直接保存
    fireEvent.click(within(drawerEl).getByRole('button', { name: /保\s*存/ }))
    await waitFor(() =>
      expect(within(drawerEl).getByText('请选择或输入供应商')).toBeTruthy(),
    )
    expect(createModel).not.toHaveBeenCalled()
  })

  it('✅ 删除走 Popconfirm → deleteModel 被调用', async () => {
    renderPage()
    const pane = activePane()
    await waitFor(() => expect(within(pane).getByText('gpt-4o')).toBeTruthy())
    const rows = within(pane).getAllByRole('row')
    // rows[0] = 表头，rows[1] = m1
    fireEvent.click(within(rows[1]!).getByRole('button', { name: /删\s*除/ }))
    await waitFor(() => expect(screen.getByText('删除该模型配置？')).toBeTruthy())
    const pop = document.querySelector('.ant-popconfirm') as HTMLElement
    fireEvent.click(within(pop).getByRole('button', { name: /删\s*除/ }))
    await waitFor(() => expect(deleteModel).toHaveBeenCalledWith('m1'))
  })
})

describe('模型配置页 · Feature B 供应商密钥', () => {
  it('✅ 切到「供应商密钥」页签渲染密钥列表（脱敏片段 + 未配置）', async () => {
    renderPage()
    const pane = activePane()
    await waitFor(() => expect(within(pane).getByText('gpt-4o')).toBeTruthy())
    fireEvent.click(screen.getByRole('tab', { name: /供\s*应\s*商\s*密\s*钥/ }))
    const credPane = activePane()
    await waitFor(() => expect(within(credPane).getByText('sk-…aBc9')).toBeTruthy())
    expect(within(credPane).getByText('openai')).toBeTruthy()
    expect(within(credPane).getByText('minimax')).toBeTruthy()
    expect(within(credPane).getAllByText('未配置').length).toBeGreaterThan(0)
  })

  it('✅ 「设置密钥」提交调用 setProviderCredential，明文只上传不回显', async () => {
    renderPage()
    const pane = activePane()
    await waitFor(() => expect(within(pane).getByText('gpt-4o')).toBeTruthy())
    fireEvent.click(screen.getByRole('tab', { name: /供\s*应\s*商\s*密\s*钥/ }))
    const credPane = activePane()
    await waitFor(() => expect(within(credPane).getByText('sk-…aBc9')).toBeTruthy())

    // 页签头部的「设置密钥」按钮（行内的同名按钮在后面，取首个）
    fireEvent.click(within(credPane).getAllByRole('button', { name: /设\s*置\s*密\s*钥/ })[0]!)
    // 锚定到本弹窗自身的 content（避免命中其它残留弹窗）
    const titleEl = await screen.findByText('设置供应商密钥')
    const modalEl = titleEl.closest('.ant-modal-content') as HTMLElement
    expect(modalEl).toBeTruthy()
    // 弹窗内两个输入框的 DOM 顺序：① 供应商(AutoComplete) ② API Key(Password)
    const inputs = modalEl.querySelectorAll('input')
    const providerInput = inputs[0] as HTMLInputElement
    const keyInput = inputs[1] as HTMLInputElement

    fireEvent.change(providerInput, { target: { value: 'openai' } })
    fireEvent.change(keyInput, { target: { value: 'sk-secret-12345' } })
    fireEvent.click(within(modalEl).getByRole('button', { name: /保\s*存/ }))
    await waitFor(() => expect(setProviderCredential).toHaveBeenCalledTimes(1))
    const call = vi.mocked(setProviderCredential).mock.calls[0]![0] as unknown as Record<string, unknown>
    expect(call.provider).toBe('openai')
    expect(call.apiKey).toBe('sk-secret-12345')
  })
})
