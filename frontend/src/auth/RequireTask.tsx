/**
 * 业务任务路由守卫（E2E 二次评估 T1，2026-11-14）
 *
 * ⚠️ 与 `RequireRole` 的区别：
 *    · RequireRole 管**治理面**（config / approvals / audit …），角色未知即 fail-closed；
 *    · RequireTask 管**业务面**（发起辅导 / 画像 / 报表 …），按能力矩阵判定。
 *
 * ⛔ 仍然是**体验优化，不是安全边界**（FE-01 §4.2 G-1）：
 *    隐藏入口 ≠ 用户无权访问，后端仍会二次拒绝。
 *    这里解决的是「导航藏了、URL 却直达」造成的不一致——
 *    直达时给一个**明确说明不可用**的终态页，而不是把 403 甩给用户。
 *
 * ⚠️ 角色未知时不拦截：此时无法判定能力，强行拦会把老会话 / 回补失败
 *    的用户挡在所有业务页之外（比后端 403 更糟）。安全由后端兜底，
 *    错误态由 `errorGuidance` 按 status 明确分流。
 */

import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useSession } from './session'
import { canPerformTask, type TaskId } from './capabilities'

/** 任务中文名，用于「明确说明不可用」的提示文案。 */
const TASK_LABELS: Record<TaskId, string> = {
  dashboard: '工作台',
  'run:create': '发起辅导',
  'run:list': '检索记录',
  'rag:search': '知识检索',
  'chat:general': '通用问答',
  'chat:agent': 'Agent 对话',
  'profile:read': '学习画像',
  'reports:read': '报表中心',
}

export function RequireTask({
  task,
  children,
}: {
  task: TaskId
  children: ReactNode
}): JSX.Element {
  const { authenticated, roles } = useSession()
  const location = useLocation()

  if (!authenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }

  // ⚠️ 角色未知（空集）不拦截，见文件头说明。
  if (roles.length > 0 && !canPerformTask(roles, task)) {
    const reason = `当前账号未开放「${TASK_LABELS[task]}」。这是账号权限范围，不是登录失效或网络问题；重新登录、刷新或重试都不会改变结果。如需使用请联系管理员。`
    return <Navigate to={`/no-permission?reason=${encodeURIComponent(reason)}`} replace />
  }

  return <>{children}</>
}
