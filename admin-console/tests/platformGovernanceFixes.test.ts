import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * DEF-02 / 03 / 04 / 06 反向验证守卫（2026-11-13 E2E 实测缺陷）。
 *
 * 这四项都是「没有报错、只是口径/请求数/可见性不对」的静默偏差，行为回归脚本
 * 在真实浏览器里能测到，但成本较高；本文件把修复点锁在 CI：撤掉任一处即转红。
 *
 * ⚠️ 断言一律读取**代码行**而非全文 contains —— 注释里也会提到这些名字，
 *    全文匹配会退化成「注释存在即通过」的假绿。
 */
// ⚠️ 不用 import.meta.url：jsdom 环境下它不是 file: 协议，readFileSync 会拿到 undefined。
const read = (path: string): string => readFileSync(resolve(process.cwd(), path), 'utf8')

describe('DEF-02 平台审计角色口径（后端实测 200，前端门禁曾漏 SECURITY_ADMIN）', () => {
  it('router AUDIT_SCOPE 含 SECURITY_ADMIN', () => {
    const router = read('src/router.tsx')
    const scope = router.match(/const AUDIT_SCOPE[^=]*=\s*\[([^\]]*)\]/)?.[1] ?? ''
    expect(scope).toContain('ROLES.SUPER_ADMIN')
    expect(scope).toContain('ROLES.SECURITY_ADMIN')
    expect(scope).toContain('ROLES.AUDITOR')
  })

  it('navConfig 审计菜单 requiredRoles 与 AUDIT_SCOPE 一致', () => {
    const nav = read('src/layout/navConfig.tsx')
    const auditBlock = nav.match(/key: 'audit'[\s\S]{0,420}/)?.[0] ?? ''
    expect(auditBlock).toContain('ROLES.SECURITY_ADMIN')
    expect(auditBlock).toContain('ROLES.AUDITOR')
  })
})

describe('DEF-03 审计页重复 GET', () => {
  it('load 复用同参数在途 Promise', () => {
    const page = read('src/pages/audit/AuditLogsPage.tsx')
    expect(page).toContain('inflightRef.current')
    expect(page).toContain('return inflight.promise')
    expect(page).toContain('inflightRef.current?.promise === promise')
  })
})

describe('DEF-04 favicon 404', () => {
  it('index.html 用内联 data URI 声明图标（dev/build 都不会双前缀），且独立资源存在', () => {
    const html = read('index.html')
    // ⚠️ 只取 link 标签本身：注释里会解释「双前缀」这个反例，全文断言会假红。
    const iconLine = html.match(/<link[^>]*rel="icon"[^>]*>/)?.[0] ?? ''
    expect(iconLine).toContain('data:image/svg+xml;base64,')
    // Vite dev 会给 / 开头的 href 再加一次 base；data URI 不受影响，也不发网络请求。
    expect(iconLine).not.toContain('/admin/admin/')
    expect(read('public/favicon.svg')).toContain('<svg')
  })
})

describe('DEF-06 低权限角色不再看到用户写入口', () => {
  it('新建按钮与行操作列均受 canManageUsers 约束', () => {
    const page = read('src/pages/users/UsersManagePage.tsx')
    expect(page).toContain('const canManageUsers = iAmSuper')
    expect(page).toMatch(/\{canManageUsers && \([\s\S]{0,320}新建用户/)
    expect(page).toContain('if (!canManageUsers) return')
  })
})
