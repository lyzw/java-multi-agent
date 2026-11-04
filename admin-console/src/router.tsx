import { createBrowserRouter, Navigate } from 'react-router-dom'
import { AdminLayout } from '@/layout/AdminLayout'
import { RequireAuth } from '@/auth/RequireAuth'
import { RequireRole } from '@/auth/RequireRole'
import { LoginPage } from '@/pages/LoginPage'
import { NoPermissionPage } from '@/pages/NoPermissionPage'
import { TenantRegistryPage } from '@/pages/TenantRegistryPage'
import { TenantNewPage } from '@/pages/TenantNewPage'
import { TenantDetailPage } from '@/pages/TenantDetailPage'
import { AuditLogsPage } from '@/pages/audit/AuditLogsPage'
import { RoleMatrixPage } from '@/pages/roles/RoleMatrixPage'
import { UsersManagePage } from '@/pages/users/UsersManagePage'
import { AgentRegistryPage } from '@/pages/console/AgentRegistryPage'
import { ToolCatalogPage } from '@/pages/console/ToolCatalogPage'
import { SkillCatalogPage } from '@/pages/console/SkillCatalogPage'
import { McpCatalogPage } from '@/pages/console/McpCatalogPage'
import { RoutingPolicyPage } from '@/pages/catalog/RoutingPolicyPage'
import { RagManagementPage } from '@/pages/rag/RagManagementPage'
import { RagDashboardPage } from '@/pages/rag/RagDashboardPage'
import { RagSourceRegisterPage } from '@/pages/rag/RagSourceRegisterPage'
import { RagSourceListPage } from '@/pages/rag/RagSourceListPage'
import { RagDocUploadPage } from '@/pages/rag/RagDocUploadPage'
import { RagChunkFixPage } from '@/pages/rag/RagChunkFixPage'
import { RagVerifyPage } from '@/pages/rag/RagVerifyPage'
import { RagInspectionPage } from '@/pages/rag/RagInspectionPage'
import { RagRecallPage } from '@/pages/rag/RagRecallPage'
import { RagReviewerRosterPage } from '@/pages/rag/RagReviewerRosterPage'
import { RagKpAliasPage } from '@/pages/rag/RagKpAliasPage'
import { RagDictionaryPage } from '@/pages/rag/RagDictionaryPage'
import { RagIngestJobsPage } from '@/pages/rag/RagIngestJobsPage'
import { RagReindexPage } from '@/pages/rag/RagReindexPage'
import { DictionaryAdminPage } from '@/pages/dictionaries/DictionaryAdminPage'
import { AgentLabPage } from '@/pages/agentlab/AgentLabPage'
import { AgentMetricsPage } from '@/pages/metrics/AgentMetricsPage'
import { MetricEntryPage } from '@/pages/metrics/MetricEntryPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { APP_BASE_PATH, ROLES, PLATFORM_SCOPE_ROLES, type Role } from '@/config/constants'

/**
 * 平台控制台路由（重规划 §7）。
 *
 * ⚠️ admin-console 是**纯平台控制台**（aud=admin-console-api）：
 *    平台身份域页面（用户/角色/租户/审计）+ 平台能力目录四页
 *    （Agent/Tool/Skill/MCP，走 /api/platform/v1/capabilities/*）。
 *    租户域旧页面文件暂留仓库，路由与导航已摘除。
 */
/** 租户生命周期管理作用域（开通/启停/退役；后端仍按 platform.tenant.* 重授权） */
const LIFECYCLE_SCOPE: Role[] = [ROLES.SUPER_ADMIN, ROLES.TENANT_LIFECYCLE_ADMIN]

/** 平台审计作用域（需 platform.audit.read；后端仍重授权） */
const AUDIT_SCOPE: Role[] = [ROLES.SUPER_ADMIN, ROLES.AUDITOR]

export const router = createBrowserRouter(
  [
    { path: '/login', element: <LoginPage /> },
    {
      // 业务无权限整页（已登录但缺权限，区别于「未登录」跳登录）。
      // 由 `notifyAccessDenied` 在捕获到 ACCESS_DENIED 时跳转。
      path: '/no-permission',
      element: <NoPermissionPage />,
    },
    {
      path: '/',
      element: (
        <RequireAuth>
          <AdminLayout />
        </RequireAuth>
      ),
      children: [
        { index: true, element: <Navigate to="/tenants" replace /> },
        {
          path: 'tenants',
          element: <TenantRegistryPage />,
        },
        {
          // 深链 /tenants/new 不是第二个表单宿主：跳回租户页并带 ?register=1，
          // 由页面自己的 useRegistrationModal 消费后打开「开通租户」弹窗。
          path: 'tenants/new',
          element: (
            <RequireRole roles={LIFECYCLE_SCOPE}>
              <TenantNewPage />
            </RequireRole>
          ),
        },
        {
          path: 'tenants/:id',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <TenantDetailPage />
            </RequireRole>
          ),
        },
        {
          // 平台用户管理（新建/启停/重置密码/角色增删；写端点有 SoD 与自身保护）
          path: 'users',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <UsersManagePage />
            </RequireRole>
          ),
        },
        {
          // 角色权限矩阵（冻结矩阵只读呈现，按命名空间分组）
          path: 'roles',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RoleMatrixPage />
            </RequireRole>
          ),
        },
        {
          // 跨租户 Agent 注册表（只读盘点；平台链 + BYPASSRLS 会话窗）
          path: 'agents',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <AgentRegistryPage />
            </RequireRole>
          ),
        },
        {
          // 平台级 Tool 目录（三步发布工作流）
          path: 'tools',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <ToolCatalogPage />
            </RequireRole>
          ),
        },
        {
          // 平台级 Skill 目录
          path: 'skills',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <SkillCatalogPage />
            </RequireRole>
          ),
        },
        {
          // 平台级 MCP Server 目录
          path: 'mcps',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <McpCatalogPage />
            </RequireRole>
          ),
        },
        {
          // 路由策略登记 + 试跑（dry-run 走后端同一引擎；端点属租户域，见页面诚实边界）
          path: 'routing-policies',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RoutingPolicyPage />
            </RequireRole>
          ),
        },
        {
          // 平台审计（与租户审计分域；需 platform.audit.read）
          path: 'audit',
          element: (
            <RequireRole roles={AUDIT_SCOPE}>
              <AuditLogsPage />
            </RequireRole>
          ),
        },
        {
          // RAG 语料治理：单页 → 四治理域 13 页（RAG-D13 / RAG-D14）
          //
          // ⚠️ 改造前是一个 /rag 页挂 5 个 Tab（RAG-D12 靶点 G：治理面过重）。
          //    现按「录入与校对 / 审核 / 规则配置 / 运维」四个域拆成独立路由，
          //    每页只承担一件事，导航分区见 navConfig。
          // ⚠️ 旧的 /rag 单页保留为入口跳转，避免既有书签 404。
          path: 'rag',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagManagementPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/dashboard',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagDashboardPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/sources',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagSourceListPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/sources/new',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagSourceRegisterPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/doc-upload',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagDocUploadPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/chunk-fix',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagChunkFixPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/verify',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagVerifyPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/inspection',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagInspectionPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/recall',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagRecallPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/reviewers',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagReviewerRosterPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/kp-alias',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagKpAliasPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/dictionaries',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagDictionaryPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/jobs',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagIngestJobsPage />
            </RequireRole>
          ),
        },
        {
          path: 'rag/reindex',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagReindexPage />
            </RequireRole>
          ),
        },
        {
          // 字典配置（枚举下拉的数据源；内置项禁止物理删除）
          path: 'dictionaries',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <DictionaryAdminPage />
            </RequireRole>
          ),
        },
        {
          // 观测度量 · Agent 效果指标（六大类；未采集显示「未采集」而非 0）
          path: 'metrics',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <AgentMetricsPage />
            </RequireRole>
          ),
        },
        {
          // 观测度量 · 效果数据录入（L1/L2 回填 + 风险事件 + 用户反馈；平台资源写权限）
          path: 'metrics-entry',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <MetricEntryPage />
            </RequireRole>
          ),
        },
        {
          // AI 实验室 · Agent 效果测试（发起执行 + 观察事件流与消耗）
          path: 'agent-lab',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <AgentLabPage />
            </RequireRole>
          ),
        },
        {
          // 404 兜底（评审 §1.2 P0）：未知路径不再白屏，给真实去向
          path: '*',
          element: <NotFoundPage />,
        },
      ],
    },
  ],
  { basename: APP_BASE_PATH },
)
