# 管理后台系统 · 开发计划（W-ADMIN）

本文件夹是《管理后台系统设计方案》（`docs/管理后台系统设计方案.html`）的**可执行开发计划**，定位为在既有「统一配置与权限管控体系」领域模型之上的**管理控制台（展示 + 编排层）**独立工作流 `W-ADMIN`。

**架构形态（v1.1）**：前端为**独立前端项目**（独立构建/部署），后端**复用既有 `platform/agent-api` 单一共享项目**（不新建独立后端工程，仅按需在其内扩展只读聚合/查询端点）。控制台是共享后端的纯 API 消费方。详见 `00_计划总览与阶段划分.md` §1 与 §1.1。

## 阅读顺序

1. **`00_计划总览与阶段划分.md`** — 必读。定位、阶段总览（P0~P5）、依赖图、里程碑与冻结门禁、通用原则、风险登记、追踪要求。
2. 按阶段顺序阅读细分计划：
   - `P0_脚手架.md` — 管理布局/路由/守卫/API Client/令牌/仪表盘骨架
   - `P0_5_租户管理.md` — M0 租户生命周期/配置覆盖/主体角色/RLS 隔离校验（超级管理员通道）
   - `P1_实体管理.md` — M2–M5 四类实体 CRUD + 版本历史（草稿态）
   - `P2_发布审批.md` — M6 三步工作流/双复核/回滚/生命周期迁移
   - `P3_关系视图.md` — M7 能力闭包图谱/影响分析/删除守卫
   - `P4_运行监控.md` — M8 authzVersion/健康/DENY 告警/运行日志（只读聚合）
   - `P5_审计配置.md` — M9+M10 审计视图/角色矩阵/凭据引用登记

## 关键约束（贯穿全部阶段）

- **复用优先**：领域规则（校验/闭包/指纹/CAS/双复核）一律复用既有 `cap_*` 服务、`ConfigPublishWorkflowService`、`PermissionResolver`、`authzVersion`、RLS、`AuditPort`；控制台只调用与呈现，禁止重写安全决策语义。
- **作用域红线**：平台级（Tool/Skill/MCP）写发布保持带外运维通道（BYPASSRLS），**后端**对 PLATFORM 作用域 HTTP 写入口显式返回 403，控制台不暴露 PLATFORM 作用域写入口；租户管理（开通/配置覆盖/主体角色/启停）属 PLATFORM 作用域，仅由超级管理员经运维通道访问。**安全判定一律在后端**，前端仅按角色/作用域条件渲染。
- **独立前端 + 共享后端**：前端是独立工程（独立 `package.json`/Vite 构建/独立部署，根路由即控制台）；后端不新建工程，仅在 `platform/agent-api` 内按需扩展端点；跨工程不 import 源码，现有前端的设计令牌与 UI 模式（AppShell/RequireRole/tightening 风格）作为参考资产**搬运**到独立工程以保证一致。
- **收紧棘轮**：`overrides` / `paramPolicies` 与租户配置覆盖只能收紧不能放宽，放宽即拒。
- **fail-closed**：依赖解析/authzVersion 不可用时拒绝并告警，前端须给明确错误态，不可静默放行。
- **凭据安全**：`credential_ref` 仅存引用，永不入前端明文/日志/指纹。
- **RLS 校验必须用非特权 `app_agent` 账号**，且 `set_config('app.tenant_id',?,true)` 必须在事务内。

## 工程基线

- 前端（独立项目）：React 18 + TS + Zustand + React Query + Vite（独立 `package.json`/构建/部署）；从现有前端**搬运**设计令牌与 UI 模式（`AppShell`/`RequireRole`/`client`/`tightening` 风格）在独立工程内实现，**不跨工程 import**；图谱用 react-flow，图表用 ECharts/Recharts；测试 Vitest + Testing Library；经 dev `server.proxy` 或生产同源反向代理连通共享后端。
- 后端（共用项目）：复用既有 `platform/agent-api` 单一共享工程，仅在其内按需扩展只读聚合/查询端点（不新建独立后端工程、不新建 `admin` Maven 模块）；复用 `config/CapConfigPublishController` 与 `application/cap/ConfigPublishWorkflowService`，以及 `cap_*` 服务、`PermissionResolver`、`authzVersion`、RLS、`AuditPort`。
- 构建/测试：前端 `npm ci && npm run build` + Vitest；后端 `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o clean verify`；IT 复用非特权 `app_agent` 账号 + RDS 夹具。

## 状态

- 设计文档：`docs/管理后台系统设计方案.html`（已完成）。
- 开发计划：本文件夹（含总览 + 7 阶段，v1.1 已明确「独立前端项目 + 共用后端」架构）。尚未进入编码阶段，待用户授权按 P0→P5 推进。
