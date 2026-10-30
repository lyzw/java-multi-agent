import '@testing-library/jest-dom'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

/**
 * ⚠️ 显式注册 RTL cleanup（不依赖 @testing-library/react 的 auto-cleanup）。
 *
 * <p>本工程 vitest 配置为 {@code pool: 'forks', singleFork: true} —— 所有测试文件
 * 共享同一 fork，外部依赖模块（含 RTL）被缓存复用。RTL 的 auto-cleanup 在模块
 * 首次导入时把 {@code afterEach(cleanup)} 注册到「当时那个文件」的钩子上下文，
 * 后续文件复用缓存模块时不会重新注册 → 渲染残留泄漏到下一个文件
 * （entityValidation 的 null≠[] 断言曾被隔壁文件的渲染顺序误伤）。
 *
 * <p>setupFiles 每个测试文件都会执行一遍，在这里注册即保证每个文件都有清理钩子。
 */
afterEach(() => {
  cleanup()
  // ⚠️ 同理清 sessionStorage：共享 fork 下，谁存的会话都会残留给下一个文件。
  //    后果不是报错而是**假失败**——某个文件断言「请求不带 Authorization」，
  //    隔壁文件恰好存过会话就会翻车，且随执行顺序时好时坏（2026-10-30 修复 P0/P1 时实测）。
  window.sessionStorage.clear()
})

/**
 * ⚠️ jsdom 不实现 `window.matchMedia`，而 antd 的 `Row` / `Col` 响应式栅格
 *    （`useBreakpoint` → `responsiveObserver`）在挂载时就会调用它。
 *    缺了这个桩，任何用到栅格的页面测试都会以
 *    `TypeError: window.matchMedia is not a function` 崩在渲染阶段——
 *    症状像「组件写错了」，实际是测试环境缺件。
 *
 *    返回值刻意固定为「不匹配任何断点」：栅格在测试里退化为最窄布局，
 *    不影响任何文本断言。
 */
if (typeof window.matchMedia !== 'function') {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia
}
