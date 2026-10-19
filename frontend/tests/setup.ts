import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'

/**
 * 测试环境准备
 */

/**
 * ⛔⛔ **显式注册 cleanup**（不可省略）。
 *
 * 症状：单文件运行全绿，多文件一起跑则大面积失败，
 *       报 "Found multiple elements"。
 *
 * 根因：Testing Library 的自动 cleanup 依赖全局 `afterEach` 是否已存在。
 *       本项目 setup 显式从 vitest 导入钩子后，
 *       该自动注册路径**不保证生效**——
 *       单文件时侥幸正常，多文件共存时 DOM 跨用例累积。
 *
 * ⚠️ 这是典型的「单跑绿、联跑红」缺陷：
 *    只单跑测试文件永远发现不了，必须全量跑才能暴露。
 */
afterEach(() => {
  cleanup()
})

beforeEach(() => {
  // 避免跨用例泄漏令牌与本地记录
  window.sessionStorage.clear()
  window.localStorage.clear()
})

// jsdom 未实现 TextEncoder，fetch 流读取需要
import { TextEncoder, TextDecoder } from 'node:util'

if (typeof globalThis.TextEncoder === 'undefined') {
  Object.assign(globalThis, { TextEncoder, TextDecoder })
}