/**
 * 全局未捕获错误安全网测试（E2E 四轮优化，DEF-2）。
 *
 * ⚠️ 守则是「撤掉修复点会红」：若 `main.tsx` 没调用
 * `installGlobalErrorHandlers()`，这两类事件就没人兜底——本测试用
 * 「不安装时 dispatch 不触发 reporter」做对照反证。
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { installGlobalErrorHandlers } from '@/shared/globalErrorHandlers'

describe('installGlobalErrorHandlers', () => {
  afterEach(() => {
    // ⚠️ 每个用例卸载自己的监听，避免跨用例串扰
    window.removeEventListener('error', () => {})
    window.removeEventListener('unhandledrejection', () => {})
  })

  it('捕获 error 与 unhandledrejection 并交给 reporter', () => {
    const reporter = vi.fn()
    const uninstall = installGlobalErrorHandlers(reporter)

    window.dispatchEvent(new ErrorEvent('error', { message: 'boom', error: new Error('boom') }))
    const reason = new Error('rejected')
    const rej = new Event('unhandledrejection')
    ;(rej as unknown as { reason: unknown }).reason = reason
    window.dispatchEvent(rej)

    expect(reporter).toHaveBeenCalledTimes(2)
    expect(reporter).toHaveBeenNthCalledWith(1, { type: 'error', error: expect.any(Error) })
    expect(reporter).toHaveBeenNthCalledWith(2, { type: 'rejection', error: reason })

    uninstall()
  })

  it('uninstall 后不再回调', () => {
    const reporter = vi.fn()
    const uninstall = installGlobalErrorHandlers(reporter)
    uninstall()
    window.dispatchEvent(new ErrorEvent('error', { message: 'x' }))
    expect(reporter).not.toHaveBeenCalled()
  })

  it('默认 reporter 为 console.error（不抛错）', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const uninstall = installGlobalErrorHandlers()
    window.dispatchEvent(new ErrorEvent('error', { message: 'y' }))
    expect(spy).toHaveBeenCalled()
    spy.mockRestore()
    uninstall()
  })

  it('⛔ 反证：未安装时 dispatch 不会触发 reporter（对照「已安装必调用」）', () => {
    const reporter = vi.fn()
    // ⚠️ 故意不安装：真实缺陷形态是「main.tsx 根本没注册」。
    window.dispatchEvent(new ErrorEvent('error', { message: 'z' }))
    expect(reporter).not.toHaveBeenCalled()
  })
})
