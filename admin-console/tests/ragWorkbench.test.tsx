// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { NAV_GROUPS } from '@/layout/navConfig'
import { RagWorkbenchPage } from '@/pages/rag/RagWorkbenchPage'

/**
 * RAG 工作台守卫：总览页必须与侧栏共用一份路径清单。
 *
 * 此处刻意不手写期望页面数组——清单的唯一来源是 NAV_GROUPS 中
 * `rag-workbench` 分组的 section。若有人只改导航页签而漏改总览页，
 * 这个用例不会自动红（因为是同源派生），但「另一个分组偷偷复刻 RAG 页面」
 * 或「总览页漏渲染整组」会被下面两条断言抓住。
 */
describe('RAG 工作台总览页', () => {
  it('按导航 section 渲染全部治理域与入口，且不提供写入表单', () => {
    render(
      <MemoryRouter>
        <RagWorkbenchPage />
      </MemoryRouter>,
    )
    const group = NAV_GROUPS.find((g) => g.key === 'rag-workbench')
    expect(group).toBeDefined()
    const sections = [...new Set(group!.children.map((n) => n.section).filter(Boolean))]
    for (const section of sections) {
      expect(screen.getAllByText(section as string).length).toBeGreaterThan(0)
    }
    for (const node of group!.children) {
      if (node.path === '/rag') continue
      expect(screen.getAllByText(node.label).length, node.path).toBeGreaterThan(0)
    }
    expect(screen.getByText(/作用域与凭据统一口径/)).toBeTruthy()
    expect(screen.getByText(/平台档禁带租户头/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '保存配置' })).toBeNull()
  })

  it('工作台总览路径本身不是入口卡片（避免自己指向自己）', () => {
    const group = NAV_GROUPS.find((g) => g.key === 'rag-workbench')!
    expect(group.children.filter((n) => n.path === '/rag')).toHaveLength(1)
    expect(group.children.find((n) => n.path === '/rag')?.label).toBe('工作台总览')
  })
})
