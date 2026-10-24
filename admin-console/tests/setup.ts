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
})
