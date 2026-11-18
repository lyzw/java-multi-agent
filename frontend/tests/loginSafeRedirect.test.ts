/**
 * 登录回跳白名单守卫（P2-3 · 2026-11-17 Code Review 修复）。
 *
 * ⚠️ 本文件锁死 `safeRedirect` 的开放重定向防护：
 *    此前 `navigate(state?.from ?? '/')` 无白名单，攻击者可构造
 *    `/login` 带 `state.from='https://evil.example'`，登录后即被送外站。
 *
 * ✅ 只接受「单个斜杠开头的站内相对路径」。
 * 反向验证：删掉任一拒绝分支（尤其 `//` 与反斜杠），对应断言必红。
 */
import { describe, it, expect } from 'vitest'
import { safeRedirect } from '@/pages/LoginPage'

describe('safeRedirect：登录后回跳只接受站内相对路径', () => {
  it('缺省 / 非字符串 / 空串 → 回首页', () => {
    expect(safeRedirect(undefined)).toBe('/')
    expect(safeRedirect('')).toBe('/')
    // 运行时可能拿到非字符串（location.state 来自不可信历史记录）
    expect(safeRedirect(123 as unknown as string)).toBe('/')
  })

  it('⛔ 协议相对 / 绝对外站 URL → 回首页（开放重定向主攻击面）', () => {
    expect(safeRedirect('https://evil.example')).toBe('/')
    expect(safeRedirect('http://evil.example/phish')).toBe('/')
    expect(safeRedirect('//evil.example')).toBe('/')
    expect(safeRedirect('///evil.example')).toBe('/')
  })

  it('⛔ 不以单个斜杠开头的相对路径 → 回首页', () => {
    expect(safeRedirect('foo/bar')).toBe('/')
    expect(safeRedirect('./config')).toBe('/')
    expect(safeRedirect('../admin')).toBe('/')
  })

  it('⛔ 含反斜杠（部分浏览器当斜杠，形成 \\evil 绕过）→ 回首页', () => {
    expect(safeRedirect('/\\evil.example')).toBe('/')
    expect(safeRedirect('/ok\\bad')).toBe('/')
  })

  it('⛔ 含控制字符 → 回首页', () => {
    expect(safeRedirect('/ok\nhttps://evil.example')).toBe('/')
    expect(safeRedirect('/ok\u0000')).toBe('/')
    expect(safeRedirect('/ok\u007f')).toBe('/')
  })

  it('合法站内路径（含 query / hash / 多级）→ 原样返回', () => {
    expect(safeRedirect('/')).toBe('/')
    expect(safeRedirect('/config')).toBe('/config')
    expect(safeRedirect('/config?tab=agents')).toBe('/config?tab=agents')
    expect(safeRedirect('/runs/abc?studentId=S-1#step-2')).toBe('/runs/abc?studentId=S-1#step-2')
  })
})
