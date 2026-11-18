/**
 * O1 反向验证：admin-console 必须具备「顶层错误边界 + 全局 handler + data-router
 * errorElement」三层防护。撤掉任意一层，本测试必红。
 *
 * 对齐 frontend 的 DEF-1/2 铁律：组件式边界兜路由外崩溃，errorElement 兜路由内，
 * 全局 handler 兜未捕获 Promise 拒绝（纯日志、不改 UI）。
 */
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(__dirname, '..')

describe('O1 全局错误安全网', () => {
  it('main.tsx 安装全局 handler 且用 AppErrorBoundary 包住 App', () => {
    const main = readFileSync(resolve(root, 'src/main.tsx'), 'utf8')
    expect(main).toMatch(/installGlobalErrorHandlers\(\)/)
    expect(main).toMatch(/<AppErrorBoundary>/)
    expect(main).toMatch(/<\/AppErrorBoundary>/)
  })

  it('router.tsx 根路由挂 errorElement', () => {
    const router = readFileSync(resolve(root, 'src/router.tsx'), 'utf8')
    expect(router).toMatch(/errorElement:\s*<RouteErrorBoundary\s*\/>/)
  })

  it('全局 handler 订阅 error 与 unhandledrejection，且只做日志不改 UI', () => {
    const handler = readFileSync(resolve(root, 'src/shared/globalErrorHandlers.ts'), 'utf8')
    expect(handler).toMatch(/addEventListener\('error'/)
    expect(handler).toMatch(/addEventListener\('unhandledrejection'/)
    expect(handler).toMatch(/console\.error/)
    // 纯日志：不得触碰 DOM / React 状态
    expect(handler).not.toMatch(/document\./)
  })

  it('顶层边界用整页跳转避开坏掉的 Router，不清空路由上下文', () => {
    const boundary = readFileSync(resolve(root, 'src/shared/AppErrorBoundary.tsx'), 'utf8')
    expect(boundary).toMatch(/window\.location\.assign/)
    expect(boundary).toMatch(/getDerivedStateFromError/)
    expect(boundary).toMatch(/useRouteError/)
    expect(boundary).toMatch(/clearSession\(\)/)
  })

  it('installGlobalErrorHandlers 真实的事件订阅与清理', () => {
    const added: string[] = []
    const removed: string[] = []
    const add = vi.spyOn(window, 'addEventListener').mockImplementation((type: string) => {
      added.push(type)
    })
    const remove = vi.spyOn(window, 'removeEventListener').mockImplementation((type: string) => {
      removed.push(type)
    })
    // 动态 import 避免顶层副作用
    return import('@/shared/globalErrorHandlers').then(({ installGlobalErrorHandlers }) => {
      const reporter = vi.fn()
      const dispose = installGlobalErrorHandlers(reporter)
      expect(added).toContain('error')
      expect(added).toContain('unhandledrejection')
      dispose()
      expect(removed).toContain('error')
      expect(removed).toContain('unhandledrejection')
      add.mockRestore()
      remove.mockRestore()
    })
  })
})
