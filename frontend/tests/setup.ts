import '@testing-library/jest-dom/vitest'

/**
 * 测试环境准备
 *
 * ⚠️ jsdom 不实现 sessionStorage 的完整行为？——实际 jsdom 已实现，
 *    但每个测试文件需清理，避免跨用例泄漏令牌。
 */

beforeEach(() => {
  window.sessionStorage.clear()
})

// jsdom 未实现 TextEncoder，fetch 流读取需要
import { TextEncoder, TextDecoder } from 'node:util'

if (typeof globalThis.TextEncoder === 'undefined') {
  Object.assign(globalThis, { TextEncoder, TextDecoder })
}
