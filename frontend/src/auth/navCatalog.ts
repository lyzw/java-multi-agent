/**
 * 业务入口目录 —— 导航与工作台卡片共用的**单一真相源**（2026-11-17 · P1-3）。
 *
 * <p><b>为什么需要它</b>：教师评审实测发现，侧边栏导航已按能力隐藏了
 * 「学习画像 / 报表中心」，但**工作台正文里还留着这两张卡片**——
 * 学生点进去直接吃一个 403 终态页。
 *
 * <p>根因不是「忘了删卡片」，而是**同一份入口信息被写了两遍**：
 * <ul>
 *   <li>{@code layouts/AppShell.tsx} 的 {@code NAV}（侧边栏）</li>
 *   <li>{@code pages/DashboardPage.tsx} 的 4 张 {@code ActionCard}（工作台）</li>
 * </ul>
 * 两处的 {@code to} / 标签 / 图标 / 能力门禁各写一份 —— 改一处漏一处，
 * 于是「导航说没有、卡片说有」的漂移必然发生。
 *
 * <p><b>处置</b>：把「入口 → 路由 / 标签 / 图标 / 能力任务 / 说明」收敛到本文件，
 * 导航与工作台都从它派生。⛔ <b>门禁口径仍以 {@link canPerformTask} 为准</b>，
 * 本文件只提供**元数据**，不自行判定权限。
 *
 * <p>⚠️ 能力门禁的**唯一权威**仍是 {@code auth/capabilities.ts} 的
 * {@code ROLE_TASKS}；本表只负责「入口长什么样、指向哪、去做什么说明」。
 */

import type { TaskId } from './capabilities'
import type { IconName } from '@/components/Icon'

/** 一个业务入口的完整描述。 */
export interface CatalogEntry {
  /** 路由路径 */
  readonly to: string
  /** 入口标题（导航与卡片共用同一份，避免文案漂移） */
  readonly label: string
  /** 图标 */
  readonly icon: IconName
  /**
   * 能力任务标识（决定该入口对当前角色是否可见）。
   *
   * ⚠️ 与 {@link TaskId} 一一对应；门禁判定走 {@code canPerformTask}。
   */
  readonly task: TaskId
  /** 工作台卡片上的说明文字；导航不显示，故可缺省。 */
  readonly desc?: string
  /**
   * 是否在工作台「开始」区出现。
   *
   * ⚠️ 工作台是「今天要做什么」，不是「能配置什么」——
   *    故只有面向受众的少数入口进工作台，治理面入口一律不进。
   */
  readonly onWorkbench?: boolean
}

/**
 * 受众侧（业务面）入口清单。
 *
 * ⚠️ 治理面入口（语料管理 / 审批台 / 审计 / 配置…）**不在此表**：
 *    它们由侧边栏的 {@code area} 门禁单独管理，且不对受众开放。
 *    两套门禁口径不同，混在一张表里会让人误以为可以统一处理。
 *
 * ⚠️ 顺序即**侧边栏「辅导台」分组的显示顺序**；
 *    工作台卡片按 {@link onWorkbench} 过滤后另行排序。
 */
export const AUDIENCE_ENTRIES: readonly CatalogEntry[] = [
  { to: '/', label: '工作台', icon: 'dashboard', task: 'dashboard' },
  { to: '/runs/new', label: '发起辅导', icon: 'plus', task: 'run:create' },
  { to: '/runs', label: '检索记录', icon: 'search', task: 'run:list', desc: '按状态、学科、时间查找分析过的题', onWorkbench: true },
  { to: '/rag', label: '知识检索', icon: 'book', task: 'rag:search', desc: '从教材语料检索可溯源的讲解与出处', onWorkbench: true },
  // 通用知识问答：不挂教材语料的 SSE 对话（与 /rag 的「可溯源依据」互补）。
  { to: '/chat', label: '通用问答', icon: 'cpu', task: 'chat:general' },
  // 通用 Agent 编排对话：同一租户链的 SSE 过程可视化，端点与 /chat 分开。
  { to: '/agent-chat', label: 'Agent 对话', icon: 'activity', task: 'chat:agent' },
  // ⚠️ N2：STUDENT 没有 PROFILE_READ，填完编号才撞 403 —— 不再渲染该入口。
  //    治理角色（TENANT_ADMIN / VIEWER）有 PROFILE_READ，仍可见。
  { to: '/profile', label: '学习画像', icon: 'profile', task: 'profile:read', desc: '看看哪些知识点反复出错', onWorkbench: true },
  // ⚠️ N5：租户级聚合报表归位到治理面（受众不开放），
  //    避免学生看到「发起量 13」与本人口径「10 条」两个数字被误读为数据错误。
  { to: '/reports', label: '报表中心', icon: 'activity', task: 'reports:read', desc: '按天看辅导、错题、检索与语料导入的汇总', onWorkbench: true },
]

/** 工作台「开始」区展示的入口（按目录顺序）。 */
export const WORKBENCH_ENTRIES: readonly CatalogEntry[] =
  AUDIENCE_ENTRIES.filter((entry) => entry.onWorkbench === true)
