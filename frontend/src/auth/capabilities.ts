/**
 * 角色 × 任务能力矩阵 —— 导航显隐与路由引导的**单一真相源**
 *
 * ⚠️ 存在理由（E2E 二次评估 N1 / T1，2026-11-14）：
 *    上一轮 F1 只验证了「后端接口返回预期状态码」，没有验证
 *    「用户从导航点进去能不能完成他要做的那件事」。
 *    结果是 PARENT/TEACHER 导航里堂堂写着「发起辅导」，提交却 403；
 *    STUDENT 导航里有「学习画像」，填完编号才撞上 403。
 *    根因是**能力（后端动作）与依赖（导航入口）没有同一份事实**。
 *
 * ⛔ 本表是**展示用副本**，不是安全边界：真正的授权永远在后端。
 *    但它必须与后端 `RoleBasedAuthorizationService` 的动作集合对齐，
 *    否则就会出现「点得进但全被拒」或「有权却看不见」。
 *
 * ⛔ 硬门禁（T1）：导航中可见的每个入口都必须在此登记任务，
 *    且每个任务都必须有一条「从该入口完成一次真实操作」的验收用例。
 *    见 `tests/roleTaskMatrix.test.tsx` —— 缺登记即测试转红。
 *
 * 产品裁决（2026-11-14）：**家长 / 老师本版本不开放**。
 *    保留角色常量以兼容历史账号，但登录后不渲染任何业务入口，
 *    并给出明确的终态说明（不是网络问题、重试无效）。
 *    ⚠️ 不得因此放宽 `requester_id` 隔离，也不得给它们 `RUN_CREATE`。
 */

/** 业务任务标识（与导航入口一一对应）。 */
export type TaskId =
  | 'dashboard'
  | 'run:create'
  | 'run:list'
  | 'rag:search'
  | 'chat:general'
  | 'chat:agent'
  | 'profile:read'
  | 'reports:read'

export const ALL_TASKS: readonly TaskId[] = [
  'dashboard',
  'run:create',
  'run:list',
  'rag:search',
  'chat:general',
  'chat:agent',
  'profile:read',
  'reports:read',
]

/** 本版本**不开放**的角色（产品裁决 C）。 */
export const UNSUPPORTED_ROLES: readonly string[] = ['PARENT', 'TEACHER']

/** 本版本唯一开放的受众角色。 */
export const AUDIENCE_ROLES: readonly string[] = ['STUDENT']

/**
 * 角色 → 可完成的任务。
 *
 * 依据后端 `RoleBasedAuthorizationService.ROLE_PERMISSIONS`：
 *   · RUN_CREATE + AGENT_LIST → run:create（TENANT_ADMIN / AGENT_AUTHOR / STUDENT / PLATFORM_ADMIN）
 *   · RUN_READ               → run:list
 *   · PROFILE_READ           → profile:read（TENANT_ADMIN / VIEWER / PLATFORM_ADMIN）
 *   · 报表复用 RUN_READ，但按 N5 归位到治理面，受众不开放
 *   · /rag /chat /agent-chat 是租户链只读/SSE 端点，不挂治理 area
 * WORKER 是服务账号（不登录前端），保留空集。
 */
export const ROLE_TASKS: Record<string, readonly TaskId[]> = {
  TENANT_ADMIN: ALL_TASKS,
  PLATFORM_ADMIN: ALL_TASKS,
  AGENT_AUTHOR: [
    'dashboard', 'run:create', 'run:list', 'rag:search',
    'chat:general', 'chat:agent', 'reports:read',
  ],
  APPROVER: [
    'dashboard', 'run:list', 'rag:search',
    'chat:general', 'chat:agent', 'reports:read',
  ],
  VIEWER: [
    'dashboard', 'run:list', 'rag:search',
    'chat:general', 'chat:agent', 'profile:read', 'reports:read',
  ],
  WORKER: [],
  STUDENT: [
    'dashboard', 'run:create', 'run:list', 'rag:search',
    'chat:general', 'chat:agent',
  ],
  // ⚠️ 本版本不开放：任务集为空，且 `unsupportedRoleOf` 会整页拦截。
  PARENT: [],
  TEACHER: [],
}

/**
 * 任务 → 「从该入口完成一次真实操作」的验收用例标识（T1 硬门禁）。
 *
 * ⚠️ 这里登记的是**业务链路用例**，不是「页面能渲染」。
 *    F6 / N1 / N2 三个缺陷的共同教训就是：渲染成功 ≠ 操作能完成。
 */
export const TASK_ACCEPTANCE_CASES: Record<TaskId, string> = {
  dashboard: 'TC-STUDENT-01 工作台可见「近期辅导」并有可点开的本人记录',
  'run:create': 'TC-STUDENT-03 填写四必填项并提交 → 201 + 跳转进度页',
  'run:list': 'TC-STUDENT-02 检索记录列出本人 Run 且不含他人 Run',
  'rag:search': 'TC-STUDENT-05 知识检索提交查询 → 返回依据或明确空态',
  'chat:general': 'TC-CHAT-01 通用问答发送一条消息 → 收到 SSE 响应帧',
  'chat:agent': 'TC-CHAT-02 Agent 对话发起 → 过程帧可见且 done 终帧',
  'profile:read': 'TC-ADMIN-01 输入匿名编号 → 200 画像或 notFound 空态',
  'reports:read': 'TC-ADMIN-02 选择区间 → 报表数字与后端汇总一致',
}

/**
 * 是否属于「纯未开放角色」的会话。
 *
 * ⚠️ 多角色取并集（与 `hasRoleAccess` 同口径）：
 *    若账号同时持有 PARENT 与 TENANT_ADMIN，仍按管理员放行，
 *    不能因为多挂了一个历史角色就把人锁死。
 */
export function unsupportedRoleOf(roles: readonly string[]): string | null {
  if (roles.length === 0) return null
  const unsupported = roles.filter((r) => UNSUPPORTED_ROLES.includes(r))
  if (unsupported.length !== roles.length) return null
  return unsupported[0] ?? null
}

/**
 * 该角色集合能否完成某任务。
 *
 * ⚠️ 角色未知 / 空集时只放行 `dashboard`：
 *    · 工作台是落地页，不承载敏感操作，且 nav.menu.test.tsx 锁定
 *      「角色未回传时学生侧入口仍在」；
 *    · 其余任务一律不放行（fail-closed），由 `/no-permission` 明确说明。
 * ⚠️ 未知角色不抛异常、不猜测，按无能力处理。
 */
export function canPerformTask(roles: readonly string[], task: TaskId): boolean {
  if (unsupportedRoleOf(roles) !== null) return false
  if (roles.length === 0) return task === 'dashboard'
  // ⚠️ 后端新增了角色而前端未同步时，不得崩、也不得猜测其能力：
  //    与空角色同口径，只留工作台这一落地页，其余交给后端授权兜底。
  if (roles.every((role) => ROLE_TASKS[role] === undefined)) {
    return task === 'dashboard'
  }
  return roles.some((role) => {
    const tasks = ROLE_TASKS[role]
    return tasks !== undefined && tasks.includes(task)
  })
}

/** 该角色集合可见的任务列表（供门禁测试与导航共用）。 */
export function tasksForRoles(roles: readonly string[]): TaskId[] {
  return ALL_TASKS.filter((task) => canPerformTask(roles, task))
}
