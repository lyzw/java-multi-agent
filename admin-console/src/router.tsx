import { createBrowserRouter, Navigate } from 'react-router-dom'
import { AdminLayout } from '@/layout/AdminLayout'
import { RequireAuth } from '@/auth/RequireAuth'
import { RequireRole } from '@/auth/RequireRole'
import { LoginPage } from '@/pages/LoginPage'
import { NoPermissionPage } from '@/pages/NoPermissionPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { TenantRegistryPage } from '@/pages/TenantRegistryPage'
import { TenantNewPage } from '@/pages/TenantNewPage'
import { TenantDetailPage } from '@/pages/TenantDetailPage'
import { AuditLogsPage } from '@/pages/audit/AuditLogsPage'
import { ChatAuditRecordListPage } from '@/pages/audit/ChatAuditRecordListPage'
import { RoleMatrixPage } from '@/pages/roles/RoleMatrixPage'
import { UsersManagePage } from '@/pages/users/UsersManagePage'
import { AgentRegistryPage } from '@/pages/console/AgentRegistryPage'
import { ToolCatalogPage } from '@/pages/console/ToolCatalogPage'
import { SkillCatalogPage } from '@/pages/console/SkillCatalogPage'
import { McpCatalogPage } from '@/pages/console/McpCatalogPage'
import { RoutingPolicyPage } from '@/pages/catalog/RoutingPolicyPage'
import { RagManagementPage } from '@/pages/rag/RagManagementPage'
import { RagWorkbenchPage } from '@/pages/rag/RagWorkbenchPage'
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
import { ModelCatalogPage } from '@/pages/models/ModelCatalogPage'
import { ChatAuditConfigPage } from '@/pages/settings/ChatAuditConfigPage'
import { ConfigCenterPage } from '@/pages/settings/ConfigCenterPage'
import { ConfigCredentialsPage } from '@/pages/settings/ConfigCredentialsPage'
import { AgentLabPage } from '@/pages/agentlab/AgentLabPage'
import { AiChatPage } from '@/pages/agentlab/AiChatPage'
import { AgentChatPage } from '@/pages/agentlab/AgentChatPage'
import { AgentMetricsPage } from '@/pages/metrics/AgentMetricsPage'
import { MetricEntryPage } from '@/pages/metrics/MetricEntryPage'
import { ReviewQueuePage } from '@/pages/quality/ReviewQueuePage'
import { NotFoundPage } from '@/pages/NotFoundPage'
// O7：已实现但未接入路由的页面（后端端点均已确认存在）。
import { DependencyGraphPage } from '@/pages/dependencies/DependencyGraphPage'
import { ImpactAnalysisPage } from '@/pages/dependencies/ImpactAnalysisPage'
import { DelegationTopologyPage } from '@/pages/dependencies/DelegationTopologyPage'
import { PublishCenterPage } from '@/pages/publish/PublishCenterPage'
import { PublishReleasesPage } from '@/pages/publish/PublishReleasesPage'
import { PolicyConfigPage } from '@/pages/publish/PolicyConfigPage'
import { DraftListPage } from '@/pages/publish/DraftListPage'
import { PublishFailuresPage } from '@/pages/publish/PublishFailuresPage'
import { VersionHistoryPage } from '@/pages/publish/VersionHistoryPage'
import { BusinessAuditLogPage } from '@/pages/audit/BusinessAuditLogPage'
import { MonitoringHealthPage } from '@/pages/monitoring/MonitoringHealthPage'
import { MonitoringDeniesPage } from '@/pages/monitoring/MonitoringDeniesPage'
import { RouteErrorBoundary } from '@/shared/AppErrorBoundary'
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

/**
 * 平台审计作用域（需 platform.audit.read；后端仍重授权）。
 *
 * ⚠️ DEF-02（2026-11-13 E2E 实测）：代码冻结矩阵 PlatformRolePermissionMatrix 与
 *    DB platform_role_permission 均授予 PLATFORM_SECURITY_ADMIN `platform.audit.read`，
 *    后端 GET /api/platform/v1/audit-events 对该角色实测 200；但前端此处与
 *    navConfig 的审计菜单只放行 SUPER_ADMIN / AUDITOR，导致安全管理员深链进审计页
 *    被 RequireRole 拦成 403。三处口径统一为「超管 + 安全管理员 + 审计员」。
 */
const AUDIT_SCOPE: Role[] = [ROLES.SUPER_ADMIN, ROLES.SECURITY_ADMIN, ROLES.AUDITOR]

export const router = createBrowserRouter(
  [
    {
      // ⚠️ 根 errorElement（O1）：data router 会吞掉路由树内渲染错误，组件式
      //    ErrorBoundary 挡不住；必须挂 errorElement 才能兜住路由内崩溃。
      //    包一层 pathless 根路由，统一覆盖登录页 + 受保护区 + 404。
      errorElement: <RouteErrorBoundary />,
      children: [
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
          // 运行总览 Dashboard（平台级聚合，只读；未采集指标如实标注）
          path: 'dashboard',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <DashboardPage />
            </RequireRole>
          ),
        },
        {
          path: 'tenants',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <TenantRegistryPage />
            </RequireRole>
          ),
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
      // 跨租户 Agent 注册表（Agent 为全局实体，租户通过 tenant_agent_binding 多对多绑定；
      // 定义走三步发布工作流，绑定走直接配置 CRUD）
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
          // 配置管理总览：只做入口索引与参数口径说明，不在两处复制写入表单。
          path: 'config',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <ConfigCenterPage />
            </RequireRole>
          ),
        },
        {
          // 凭据引用只读视图（旧 /settings 配置中心页签已独立成页）：保留可深链路径。
          path: 'config/credentials',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <ConfigCredentialsPage />
            </RequireRole>
          ),
        },
        {
          // 旧书签兼容：/settings 统一跳到配置总览，不再维持第二个配置入口。
          path: 'settings',
          element: <Navigate to="/config" replace />,
        },
        {
          // 路由策略（导航归「配置管理 · 策略与审计」）
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
          // 对话审计记录查询（跨租户审计原文 + 建连前失败，只读；需 platform.audit.read）
          path: 'chat-audit-records',
          element: (
            <RequireRole roles={AUDIT_SCOPE}>
              <ChatAuditRecordListPage />
            </RequireRole>
          ),
        },
        {
          // RAG 工作台：一个分组 + 四治理域 section（2026-11-22 收口）。
          // ⚠️ /rag 现在是**索引页**，页面清单由 navConfig 的 section 派生；
          //    旧五 Tab 单页移至 /rag/legacy，仅作兼容路由、不进侧栏。
          path: 'rag',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RagWorkbenchPage />
            </RequireRole>
          ),
        },
        {
          // 旧 /rag 单页（五 Tab）：保留能力，不再占用主入口。
          path: 'rag/legacy',
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
          // 模型配置（Model Catalog）：模型定义 CRUD + 供应商密钥（PLATFORM_ADMIN 闸门）
          path: 'model-catalog',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <ModelCatalogPage />
            </RequireRole>
          ),
        },
        {
          // 对话审计配置（平台级单例）：控制"聊天相关所有请求"的端到端审计录制，写入即生效
          path: 'chat-audit-config',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <ChatAuditConfigPage />
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
          // 观测度量 · 人工审查队列（P2/P3：告警/抽样/L1 缺口自动入队 → 回放 → 归因）
          // 深链 /review-queue?runId=<uuid> 直接打开对话回放抽屉，供指标下钻跳转。
          path: 'review-queue',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <ReviewQueuePage />
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
          // AI 实验室 · AI 对话（RAG 语料问答：输入 → 检索 → 依据 → 回答 + 异常分支）
          path: 'ai-chat',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <AiChatPage />
            </RequireRole>
          ),
        },
        {
          // AI 实验室 · 通用 Agent 编排对话（SSE 全链路可视化 + 样例回放）
          path: 'agent-chat',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <AgentChatPage />
            </RequireRole>
          ),
        },
        // O7：运维/发布页面接入；深链门禁与 navConfig.requiredRoles 同源，
        //    菜单隐藏不代表深链可进（后端口令仍会二次授权，但 UI 声明不能分家）。
        { path: 'dependencies/graph', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><DependencyGraphPage /></RequireRole>
        ) },
        { path: 'dependencies/impact', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><ImpactAnalysisPage /></RequireRole>
        ) },
        { path: 'dependencies/delegations', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><DelegationTopologyPage /></RequireRole>
        ) },
        { path: 'publish', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><PublishCenterPage /></RequireRole>
        ) },
        { path: 'publish/releases', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><PublishReleasesPage /></RequireRole>
        ) },
        { path: 'publish/policies', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><PolicyConfigPage /></RequireRole>
        ) }, // 导航归「运维观测与发布 · 发布策略」
        { path: 'publish/drafts', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><DraftListPage /></RequireRole>
        ) },
        { path: 'publish/failures', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><PublishFailuresPage /></RequireRole>
        ) },
        { path: 'publish/versions', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><VersionHistoryPage /></RequireRole>
        ) },
        { path: 'audit/business', element: (
          <RequireRole roles={AUDIT_SCOPE}><BusinessAuditLogPage /></RequireRole>
        ) },
        {
          // 运行监控索引：直达健康快照；侧栏高亮由 /monitoring 节点承担。
          path: 'monitoring',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <Navigate to="/monitoring/health" replace />
            </RequireRole>
          ),
        },
        { path: 'monitoring/health', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><MonitoringHealthPage /></RequireRole>
        ) },
        { path: 'monitoring/denies', element: (
          <RequireRole roles={PLATFORM_SCOPE_ROLES}><MonitoringDeniesPage /></RequireRole>
        ) },
        {
          // 404 兜底（评审 §1.2 P0）：未知路径不再白屏，给真实去向
          path: '*',
          element: <NotFoundPage />,
        },
      ],
    },
      ],
    },
  ],
  { basename: APP_BASE_PATH },
)
