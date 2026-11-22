// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { App as AntApp } from 'antd'
import { act } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { DetailModeProvider, useDetailMode, DETAIL_MODE_STORAGE_KEY } from '@/contexts/detailMode'
import { CatalogDetailDrawer } from '@/pages/console/platformDetailDrawer'

function ModeProbe(): JSX.Element {
  const { mode, setMode, labels } = useDetailMode()
  return (
    <div>
      <span data-testid="mode">{mode}</span>
      <span data-testid="label">{labels[mode]}</span>
      <button onClick={() => setMode('modal')}>切弹窗</button>
      <button onClick={() => setMode('page')}>切页面</button>
    </div>
  )
}

describe('详情展示形式：全局配置', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('默认抽屉，切换后持久化到 localStorage 并回显标签', async () => {
    render(
      <AntApp>
        <DetailModeProvider>
          <ModeProbe />
        </DetailModeProvider>
      </AntApp>,
    )
    expect(screen.getByTestId('mode').textContent).toBe('drawer')
    expect(screen.getByTestId('label').textContent).toBe('右侧抽屉')

    act(() => { screen.getByText('切弹窗').click() })
    await waitFor(() => expect(screen.getByTestId('mode').textContent).toBe('modal'))
    expect(window.localStorage.getItem(DETAIL_MODE_STORAGE_KEY)).toBe('modal')
    expect(screen.getByTestId('label').textContent).toBe('居中弹窗')
  })

  it('新会话从 localStorage 恢复已保存的配置', () => {
    window.localStorage.setItem(DETAIL_MODE_STORAGE_KEY, 'page')
    render(
      <AntApp>
        <DetailModeProvider>
          <ModeProbe />
        </DetailModeProvider>
      </AntApp>,
    )
    expect(screen.getByTestId('mode').textContent).toBe('page')
    expect(screen.getByTestId('label').textContent).toBe('新页面')
  })

  it('CatalogDetailDrawer 跟随全局配置：drawer → 抽屉，modal → 弹窗，内容一致', async () => {
    const body = <div>详情内容</div>
    // drawer 模式
    window.localStorage.setItem(DETAIL_MODE_STORAGE_KEY, 'drawer')
    const { unmount } = render(
      <MemoryRouter>
        <AntApp>
          <DetailModeProvider>
            <CatalogDetailDrawer open title="详情" onClose={() => {}}>
              {body}
            </CatalogDetailDrawer>
          </DetailModeProvider>
        </AntApp>
      </MemoryRouter>,
    )
    await waitFor(() => expect(document.querySelector('.ant-drawer')).toBeTruthy())
    expect(screen.getByText('详情内容')).toBeTruthy()
    expect(document.querySelector('.ant-modal')).toBeNull()
    unmount()

    // modal 模式
    window.localStorage.setItem(DETAIL_MODE_STORAGE_KEY, 'modal')
    render(
      <MemoryRouter>
        <AntApp>
          <DetailModeProvider>
            <CatalogDetailDrawer open title="详情" onClose={() => {}}>
              {body}
            </CatalogDetailDrawer>
          </DetailModeProvider>
        </AntApp>
      </MemoryRouter>,
    )
    await waitFor(() => expect(document.querySelector('.ant-modal')).toBeTruthy())
    // 内容完全一致：换壳不换内容
    expect(screen.getByText('详情内容')).toBeTruthy()
    expect(document.querySelector('.ant-drawer')).toBeNull()
  })

  it('page 模式且有 pagePath：跳转到子路由（详情由路由页渲染）', async () => {
    window.localStorage.setItem(DETAIL_MODE_STORAGE_KEY, 'page')
    let location = ''
    function LocProbe(): JSX.Element {
      // 简单探针：用 useLocation 读当前路径
      const { useLocation } = require('react-router-dom') as typeof import('react-router-dom')
      location = useLocation().pathname
      return <span data-testid="loc">{location}</span>
    }
    render(
      <MemoryRouter initialEntries={['/tools']}>
        <AntApp>
          <DetailModeProvider>
            <CatalogDetailDrawer open title="详情" onClose={() => {}} pagePath="/catalog/tools/t-1">
              <div>详情内容</div>
            </CatalogDetailDrawer>
            <LocProbe />
          </DetailModeProvider>
        </AntApp>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByTestId('loc').textContent).toBe('/catalog/tools/t-1'))
    // page 模式容器自身不渲染内容（路由页接管）
    expect(screen.queryByText('详情内容')).toBeNull()
  })

  it('三种形式的标签与值映射齐全（无遗漏兜底）', async () => {
    render(
      <AntApp>
        <DetailModeProvider>
          <ModeProbe />
        </DetailModeProvider>
      </AntApp>,
    )
    act(() => { screen.getByText('切页面').click() })
    await waitFor(() => expect(screen.getByTestId('label').textContent).toBe('新页面'))
  })
})
