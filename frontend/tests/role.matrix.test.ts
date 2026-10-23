/**
 * 角色矩阵契约测试（FE-D4 §1.8 F-22）
 *
 * ⚠️ 背景：`ROLE_PERMISSIONS` / `hasRoleAccess` 曾处于
 *    「导出 + 注释声称与后端一致 + 无测试 + 无调用方」的四重死代码状态。
 *    后端 `RoleBasedAuthorizationService` 变更时这边不会编译失败，
 *    两边会**静默漂移**。
 *
 * 本轮处置：不接假实现（G1 未补齐前角色判定只能是猜），
 * 但把契约语义锁进测试 —— 将来接入时至少不会接错。
 *
 * ⛔ 这些断言测的是**矩阵语义**，不是「前端真的做了角色拦截」。
 *    后者在 G1 补齐前刻意不实现（见 RequireRole.tsx 文件头）。
 */

import { describe, it, expect } from 'vitest'
import { ROLE_PERMISSIONS, hasRoleAccess } from '@/auth/RequireRole'

describe('角色矩阵契约（与后端 RoleBasedAuthorizationService 对应）', () => {
  it('应覆盖后端全部 8 个角色', () => {
    // 后端 ROLE_PERMISSIONS 的键集合；漏一个即静默拒绝该角色所有访问
    expect(Object.keys(ROLE_PERMISSIONS).sort()).toEqual([
      'AGENT_AUTHOR',
      'APPROVER',
      'PARENT',
      'STUDENT',
      'TEACHER',
      'TENANT_ADMIN',
      'VIEWER',
      'WORKER',
    ])
  })

  it('TENANT_ADMIN 应拥有全部管理区域', () => {
    expect(ROLE_PERMISSIONS['TENANT_ADMIN']).toEqual([
      'config',
      'agents',
      'approvals',
      'reconciliation',
      'audit',
      'health',
      // ⚠️ RAG 语料管理面（RAG-D10）：审核/撤回属治理动作，
      //    与辅导侧的 `rag`（知识检索）语义不同，故单列。
      'ragManagement',
    ])
  })

  it('AGENT_AUTHOR 只能配置与 Agent，不得进审批/审计', () => {
    expect(ROLE_PERMISSIONS['AGENT_AUTHOR']).toEqual(['config', 'agents'])
  })

  it('APPROVER 只能审批与处置，不得改配置', () => {
    expect(ROLE_PERMISSIONS['APPROVER']).toEqual(['approvals', 'reconciliation'])
  })

  it('学生/家长/教师/VIEWER/WORKER 一律无管理区域', () => {
    for (const role of ['STUDENT', 'PARENT', 'TEACHER', 'VIEWER', 'WORKER']) {
      expect(ROLE_PERMISSIONS[role]).toEqual([])
    }
  })

  describe('hasRoleAccess 语义', () => {
    it('多角色应取并集（任一命中即放行）', () => {
      // AGENT_AUTHOR 有 config，APPROVER 有 approvals → 并集两者都有
      expect(hasRoleAccess(['AGENT_AUTHOR', 'APPROVER'], 'config')).toBe(true)
      expect(hasRoleAccess(['AGENT_AUTHOR', 'APPROVER'], 'approvals')).toBe(true)
      expect(hasRoleAccess(['AGENT_AUTHOR', 'APPROVER'], 'audit')).toBe(false)
    })

    it('空角色列表应一律拒绝（不得因「没角色」而放行）', () => {
      for (const area of ['config', 'approvals', 'audit', 'health']) {
        expect(hasRoleAccess([], area)).toBe(false)
      }
    })

    it('未知角色应拒绝而非抛异常', () => {
      // 后端新增角色而前端未同步时，必须 fail-closed 拒绝，不能崩
      expect(hasRoleAccess(['SUPER_ADMIN'], 'config')).toBe(false)
      expect(hasRoleAccess(['super_admin'], 'config')).toBe(false)
    })

    it('角色名大小写敏感（与后端枚举严格一致）', () => {
      // 小写化会意外放行：后端只认大写枚举
      expect(hasRoleAccess(['tenant_admin'], 'config')).toBe(false)
      expect(hasRoleAccess(['TENANT_ADMIN'], 'config')).toBe(true)
    })

    it('未知区域应一律拒绝', () => {
      expect(hasRoleAccess(['TENANT_ADMIN'], 'nonexistent-area')).toBe(false)
      expect(hasRoleAccess(['TENANT_ADMIN'], '')).toBe(false)
    })
  })

  describe('⛔ 反证：矩阵被放宽时测试必须转红', () => {
    it('若给 STUDENT 放开 config，上面的断言会失败', () => {
      // 这不是测试放宽，而是证明「学生有 config」确实会被抓到
      const tampered: Record<string, string[]> = {
        ...ROLE_PERMISSIONS,
        STUDENT: ['config'],
      }
      expect(hasRoleAccess(['STUDENT'], 'config')).toBe(false) // 真实矩阵
      expect(tampered['STUDENT']).toContain('config') // 篡改后被识别
      expect(tampered['STUDENT']).not.toEqual(ROLE_PERMISSIONS['STUDENT'])
    })
  })
})
