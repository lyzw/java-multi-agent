// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'
import { DictionaryAdminPage } from '@/pages/dictionaries/DictionaryAdminPage'
import type { DictionaryView } from '@/api/dictionaryApi'

/**
 * 字典配置页的治理纪律测试。
 *
 * ⚠️ 这些断言守的是**纪律**不是渲染细节：
 *    · 内置项的取值编码必须不可编辑（改了会让已落库的历史行取值悬空）；
 *    · 「停用」不能写成「删除」（后端不物理删行，文案必须一致）；
 *    · 内置字典的两条硬约束必须在页面上可见，而不是只写在文档里。
 */

vi.mock('@/api/dictionaryApi', () => ({
  getDictionaries: vi.fn(),
  getDictionary: vi.fn(),
  createDictionary: vi.fn(),
  updateDictionary: vi.fn(),
  upsertDictionaryItem: vi.fn(),
  updateDictionaryItem: vi.fn(),
  disableDictionaryItem: vi.fn(),
}))

import { getDictionaries, disableDictionaryItem } from '@/api/dictionaryApi'

const fixture: DictionaryView[] = [
  {
    dictCode: 'http.method',
    dictName: 'HTTP 方法',
    description: 'Tool kind=HTTP 时允许的请求方法',
    builtIn: true,
    enabled: true,
    sortOrder: 10,
    items: [
      { dictCode: 'http.method', itemCode: 'GET', itemLabel: 'GET', sortOrder: 10, builtIn: true, enabled: true },
      { dictCode: 'http.method', itemCode: 'POST', itemLabel: 'POST', sortOrder: 20, builtIn: true, enabled: true },
    ],
  },
  {
    dictCode: 'rag.subject',
    dictName: '学科',
    description: null,
    builtIn: false,
    enabled: true,
    sortOrder: 90,
    items: [
      { dictCode: 'rag.subject', itemCode: 'MATH', itemLabel: '数学', sortOrder: 10, builtIn: false, enabled: true },
      { dictCode: 'rag.subject', itemCode: 'ENGLISH', itemLabel: '英语', sortOrder: 30, builtIn: false, enabled: false },
    ],
  },
]

/**
 * antd 会在两个 CJK 字符之间插入空格（「编 辑」），直接比对文本会失败。
 * 故统一去掉空白再比对——这纯粹是渲染层细节，与被测纪律无关。
 */
function norm(text: string | null | undefined): string {
  return (text ?? '').replace(/\s+/g, '')
}

function renderPage(): void {
  render(
    <MemoryRouter>
      <AntApp>
        <DictionaryAdminPage />
      </AntApp>
    </MemoryRouter>,
  )
}

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

describe('字典配置页 · 治理纪律', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getDictionaries).mockResolvedValue(fixture)
    vi.mocked(disableDictionaryItem).mockResolvedValue({
      dictCode: 'http.method',
      itemCode: 'GET',
      itemLabel: 'GET',
      sortOrder: 10,
      builtIn: true,
      enabled: false,
    })
  })

  it('✅ 内置字典的两条硬约束在页面上可见（不藏文档里）', async () => {
    renderPage()
    await waitFor(() => expect(screen.getAllByText('HTTP 方法').length).toBeGreaterThan(0))
    expect(screen.getByText('内置字典的两条硬约束')).toBeTruthy()
    expect(screen.getByText(/禁止删除、禁止改取值编码/)).toBeTruthy()
  })

  it('✅ 内置字典标记为「内置」，业务字典标记为「业务」', async () => {
    renderPage()
    await waitFor(() => expect(screen.getAllByText('HTTP 方法').length).toBeGreaterThan(0))
    const builtInTags = screen.getAllByText('内置')
    expect(builtInTags.length).toBeGreaterThan(0)
    expect(screen.getAllByText('业务').length).toBeGreaterThan(0)
  })

  it('✅ 编辑内置项时「取值编码」输入框被禁用（禁止改 code）', async () => {
    const { container } = render(
      <MemoryRouter>
        <AntApp>
          <DictionaryAdminPage />
        </AntApp>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getAllByText('HTTP 方法').length).toBeGreaterThan(0))

    // 默认选中第一个字典（http.method，内置）
    const editButtons = Array.from(container.querySelectorAll('button')).filter(
      (b) => norm(b.textContent) === '编辑',
    )
    expect(editButtons.length).toBeGreaterThan(0)
    const firstEdit = editButtons[0]
    expect(firstEdit).toBeTruthy()
    ;(firstEdit as HTMLButtonElement).click()

    await waitFor(() => expect(screen.getByText('编辑取值 · GET')).toBeTruthy())
    // ⚠️ 取值编码在表格里也出现（Text code），故只在模态框内定位输入框
    const modal = document.querySelector('.ant-modal') as HTMLElement | null
    expect(modal).toBeTruthy()
    const inputs = Array.from((modal as HTMLElement).querySelectorAll('input')) as HTMLInputElement[]
    const codeInput = inputs.find((i) => i.value === 'GET')
    expect(codeInput).toBeTruthy()
    expect((codeInput as HTMLInputElement).disabled).toBe(true)
    expect(screen.getByText('内置项的编码不可修改（后端按枚举校验的正是它）')).toBeTruthy()
  })

  it('✅ 停用动作文案是「停用」而不是「删除」（后端不物理删行）', async () => {
    const { container } = render(
      <MemoryRouter>
        <AntApp>
          <DictionaryAdminPage />
        </AntApp>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getAllByText('HTTP 方法').length).toBeGreaterThan(0))

    const buttons = Array.from(container.querySelectorAll('button')).map((b) => norm(b.textContent))
    expect(buttons).toContain('停用')
    expect(buttons).not.toContain('删除')
  })

  it('✅ 已停用的项不再提供「停用」按钮（避免重复操作）', async () => {
    const { container } = render(
      <MemoryRouter>
        <AntApp>
          <DictionaryAdminPage />
        </AntApp>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getAllByText('HTTP 方法').length).toBeGreaterThan(0))

    // 切到 rag.subject：其中 ENGLISH 已停用
    const subjectRow = Array.from(container.querySelectorAll('.ant-table-row')).find((r) =>
      (r.textContent ?? '').includes('rag.subject'),
    )
    expect(subjectRow).toBeTruthy()
    ;(subjectRow as HTMLElement).click()

    await waitFor(() => expect(screen.getByText('英语')).toBeTruthy())
    const englishRow = Array.from(container.querySelectorAll('.ant-table-row')).find((r) =>
      (r.textContent ?? '').includes('ENGLISH'),
    )
    expect(englishRow).toBeTruthy()
    // ⚠️ 状态列本身会显示「停用」二字，故只断言**按钮**：已停用的项不该再有停用按钮
    const rowButtons = Array.from((englishRow as HTMLElement).querySelectorAll('button')).map((b) =>
      norm(b.textContent),
    )
    expect(rowButtons).not.toContain('停用')
  })
})
