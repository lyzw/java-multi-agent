# admin-console · Agent 相关操作「租户绑定松绑 + Agent 选择非必填」改造方案

> **适用范围**：`admin-console`（平台控制台，前端 `admin-console/`）中**所有以 Agent 为对象的操作**，以及这些操作所依赖的平台链后端端点（`platform/agent-api/.../api/platform/**`）。
> **配套文档**：《admin-console Agent 操作改造清单》（同目录，见 §9）。
> **决策前提**：项目尚未上线，dev/test 库可整体重置，本方案作为新基线，不迁就现有实现细节。
> **一句话结论**：**不是"取消租户绑定"，而是"把租户从'隐式前置条件'降级为'显式可选作用域'"** —— 平台级操作本就不该有租户前置；真正需要租户语义的少数操作（执行、发布、租户私有数据），把租户从"由 Agent 反推"改为"独立显式选择"。

---

## 0. 先纠正一个前提（重要）

用户原始判断是：**「admin-console 相关 Agent 相关的操作都必须绑定租户」**。

逐端点核实后，这个前提**与当前代码不符**，必须先纠正，否则整个方案会打在错误的位置上。

### 0.1 事实核查结果

平台链（`/api/platform/v1/**`，令牌 `aud=admin-console-api`）中与 Agent 相关的端点，`tenantId` **本来就是可选的**：

| 端点 | tenantId 语义 | 必填 | 证据 |
|---|---|---|---|
| `GET /api/platform/v1/capabilities/agents` | **可选过滤器**，null = 全部租户 | 否 | `PlatformCapabilityConsoleController.java:244-261`；`JdbcPlatformAgentDirectoryRepository.java:80`（SQL `WHERE (?::uuid IS NULL OR a.tenant_id = ?::uuid)`） |
| `GET /api/platform/v1/metrics/agent/overview` | 可选过滤器，null = 平台视角跨租户 | 否 | `PlatformAgentMetricsController.java:78`；`AgentMetricPort.java:149-156`（注释原文："`tenantId` 为空表示平台视角跨租户"） |
| `GET .../terminations` / `latency` / `tools` | 同上 | 否 | `PlatformAgentMetricsController.java:92,104,116` |
| `GET .../anomalies` / `trace/{traceId}` | 无 tenantId | 否 | `PlatformAgentMetricsController.java:122-141` |

前端侧同样印证：`getConsoleAgentRegistry(params?: { tenantId?: string })`（`admin-console/src/api/adminApi.ts:551-560`）、`fetchAgentMetricOverview(hours, tenantId?)`（`admin-console/src/api/metricsApi.ts:116`）—— 参数均可缺省。

### 0.2 真正强制"必须先选定 Agent"的地方：全站只有 1 处

| 位置 | 强制点 | 证据 |
|---|---|---|
| **AI 实验室「发起执行」按钮** | `disabled={agentId === '' \|\| input.trim() === '' \|\| loginName === ''}` | `admin-console/src/pages/agentlab/AgentLabPage.tsx:685` |
| AI 实验室「租户身份（取票）」下拉 | `disabled={ticketTenantId === ''}`（无 Agent 则连带禁用） | `AgentLabPage.tsx:599` |
| AI 实验室 execute 兜底 | `if (ticketTenantId === '' \|\| loginName === '')` → 报「未选定租户成员」 | `AgentLabPage.tsx:411-416` |
| 前端请求体类型层 | `CreateRunRequest.agentId: string`（非可选） | `admin-console/src/api/agentLabApi.ts:39` |

其余页面**均无 Agent 选择器**：指标页（`AgentMetricsPage.tsx`）只有时间窗 `Segmented`，`agentId` 只在下钻展示里出现（`:747`）；平台 Agent 注册表页（`console/AgentRegistryPage.tsx`）是只读表格 + 本地搜索框；发布中心（`publish/*`）零 `agentId` 匹配；路由策略页（`catalog/RoutingPolicyPage.tsx`）无 Agent 字段。

### 0.3 那问题出在哪？—— 出在**耦合方向**，不在"必填"本身

真正的病灶是这条**单向强制依赖链**：

```
AgentLabPage.tsx:258   const ticketTenantId = selectedAgent?.tenantId ?? ''
                            ↑
              租户的唯一来源 = 被选中的 Agent
```

代码注释把这个设计写得很明确（`AgentLabPage.tsx:251-257`）：

> 租户来自验签令牌，请求体里不允许出现 tenantId。给 A 租户取票去跑 B 租户的 Agent，只会在「执行时授权」那一步被拒；把票证绑到 Agent 自己的租户，界面才能说出「以谁的身份、在哪个租户执行」这句真话。

这个约束**在"执行一次 Run"这个动作上是正确的**（票证必须与被执行 Agent 同租户，否则执行时授权必然失败）。但它带来两个次生问题：

1. **"选 Agent"被当成整个页面的准入门槛**，而不是"发起执行"这一个动作的参数 —— 于是「只想看看有哪些 Agent 可测」也被迫先选一个。
2. **租户选择能力事实上不存在**：页面顶部那个「租户（可选过滤）」下拉（`AgentLabPage.tsx:557-569`）只过滤注册表，**不能作为票证租户**。用户想换个租户测同一批 Agent，必须先在某个租户下选中一个 Agent 才能推进。**这是"必须绑定租户"这个体感真正的来源。**

### 0.4 结论

> **"Agent 选择必填"本身不是 bug，"Agent 选择被当成租户选择的唯一入口"才是。**

因此本方案的落点是：**拆开 Agent 选择与租户选择，让租户成为独立的、显式的、可选的输入**，而不是取消任何绑定。

---

## 1. 方案总纲：作用域显式化（Scope Model）

### 1.1 引入三档作用域

把所有 admin-console 的 Agent 相关操作按"需要什么上下文"分档。这是全案的第一性分类，后续所有前端/后端改动都按此表对齐。

| 档位 | 名称 | 租户 | Agent | 典型操作 | 身份通道 |
|---|---|---|---|---|---|
| **S0** | 平台级 | 不需要 | 不需要 | 跨租户 Agent 注册表盘点、平台指标聚合、平台审计、字典、租户生命周期、能力目录 | 平台令牌（`aud=admin-console-api`） |
| **S1** | 租户级 | **需要（显式选）** | 不需要 | 租户私有 Agent 的定义/版本/草稿发布、租户级依赖图、租户 RLS 自检、租户指标录入 | 平台令牌 + `target_tenant_id` 显式参数 |
| **S2** | 执行级 | **需要** | **需要** | AI 实验室发起一次真实 Run、租户内 Tool 调用链复现 | 平台令牌 → 签发执行票证 → 租户令牌 |

**关键设计决策**：

| 决策点 | 选择 | 理由 |
|---|---|---|
| 平台级操作是否要租户 | **不要**，且要显式呈现"这是跨租户视图" | 否则用户把跨租户聚合误读成单租户健康度 |
| 租户级操作如何传租户 | **显式 `tenantId` 参数**（路径或查询），**不做"从 Agent 反推"** | 反推让"换租户"变成"必须先选中某个对象"，是当前体感问题的根源 |
| 执行级能否免 Agent | **不能**（见 §3.1 红线） | `POST /api/runs` 的 `agentId` 是 `@NonNull` 硬校验 |
| "不选租户"如何表达 | **空值 = 平台视角**，不是"查不到" | 与后端既有语义一致（`MetricQuery.tenantId` 可空 = 跨租户） |

### 1.2 与既有架构的关系

本方案**不推翻**《平台身份与租户身份分离改造方案》（`docs/重规划/平台身份与租户身份分离改造方案.md`），而是把它的 §6 数据访问矩阵落到具体操作上：

- 该方案 §10 已写明禁区：「查看/编辑租户私有 Agent 完整业务指令」「切换到租户并以平台登录态自动继承租户管理员权限」。**本方案不放宽这两条。**
- 本方案新增的是**读侧与编排侧的作用域显式化**，不新增任何跨域写通道。
- 与该方案 §5.6 一致：「平台可暂停租户，但不可直接改租户 Agent 发布版本」——S1 的租户级 Agent 定义操作仍走**申请→审批→执行**三步工作流，平台侧只做编排入口。

---

## 2. 改造范围分级

### 2.1 S0 平台级 —— 保持现状，补显式标注（低风险）

**目标**：让用户明确知道"当前看到的是跨租户聚合，不是某个租户的数据"。

| 项 | 内容 |
|---|---|
| 涉及页面 | `console/AgentRegistryPage.tsx`、`metrics/AgentMetricsPage.tsx` |
| 现状 | 已是跨租户聚合，无租户前置 |
| 改造 | ① 注册表页补一行显式说明"当前为跨租户全量盘点（N 个租户 / M 个 Agent）"；② 指标页补"平台视角（跨租户）"标注 + **可选**的租户/Agent 下钻筛选（后端已支持，前端未暴露） |
| 后端 | **无需改动**（`tenantId`/`agentId` 已是可选参数） |
| 风险 | 低。仅前端增量 |

### 2.2 S1 租户级 —— 引入显式租户选择器（**主战场**）

**目标**：让"操作租户私有 Agent"这件事**不再依赖先选中某个 Agent**。

| 项 | 内容 |
|---|---|
| 涉及页面 | `pages/AgentDetailPage.tsx`、`pages/agents/AgentDraftPanel.tsx`、`pages/AgentDependenciesTab.tsx`、`pages/publish/*`、`pages/dependencies/*` |
| 现状 | 这些页面走**租户域**端点（`/api/agents/**`、`/api/cap/agents/{id}/definition`、`/api/cap/config/publishes`），租户来自令牌。**当前平台令牌访问这些端点必然 401**（`SecurityConfiguration` 双链隔离，`JwtTokenService.java:246-248`）—— 也就是说**这些页面在当前 admin-console 路由中已被摘除**（`router.tsx:28-31`：「租户域旧页面文件暂留仓库，路由与导航已摘除」） |
| 改造 | 见 §2.2.1 |
| 风险 | **中**。触及身份域边界，须严格遵守 §3 红线 |

#### 2.2.1 三种落地路径（必须选一个）

| 路径 | 做法 | 优点 | 代价 | 建议 |
|---|---|---|---|---|
| **A. 平台链 + 显式 target_tenant_id** | 在 `/api/platform/v1/**` 下新增租户级 Agent 读/编排端点，租户走显式路径参数（如 `/api/platform/v1/tenants/{tenantId}/agents/{agentId}/definition`）；写操作仍走申请单 | 与既有平台链一致；审计天然带 `target_tenant_id` | 需新增端点与平台侧读端口 | ✅ **推荐** |
| **B. 复用执行票证通道** | 像 AI 实验室一样，先签发租户票证再打租户域端点 | 复用现成机制 | 票证 60 秒 TTL，**不适合长会话编辑**（草稿表单要撑几分钟） | ❌ 不适合写/编辑场景 |
| **C. 保留租户控制台** | 平台控制台不提供租户私有 Agent 操作，跳转独立租户控制台 | 边界最清晰 | 平台运营无法在一个界面内查全量 | 可作为**只读盘点部分**的兜底 |

> **决策建议**：读侧走 A，写侧走"平台侧编排申请单 + 租户侧执行"（A 的变体）。**不要**为了让页面能用而给 admin-console 发长期租户令牌 —— 那等于绕开 §3 的执行票证红线。

#### 2.2.2 若采纳路径 A，端点清单（新增）

| 端点 | 方法 | 租户来源 | Agent | 作用域 |
|---|---|---|---|---|
| `/api/platform/v1/tenants/{tenantId}/agents` | GET | 路径参数 | 列表可空 | S1 |
| `/api/platform/v1/tenants/{tenantId}/agents/{agentId}/definition` | GET | 路径参数 | 路径参数 | S1 |
| `/api/platform/v1/tenants/{tenantId}/agents/{agentId}/versions` | GET | 路径参数 | 路径参数 | S1 |
| `/api/platform/v1/tenants/{tenantId}/agents/{agentId}/impact` | GET | 路径参数 | 路径参数 | S1 |

⚠️ **前置条件（必须先解决）**：平台侧目前**只有 Agent 的只读盘点端口**（`PlatformAgentDirectoryPort.java:23-33`，仅 `listAgents(tenantIdFilter)`），**没有租户级 Agent 定义的读端口**。且 `public.cap_agent_def` 是 `tenant_id NOT NULL` + FORCE RLS（`01_platform_ddl.sql:1455-1478`），平台读它需要走 BYPASSRLS 会话窗（`PlatformOpsDataSourceProvider`）。

**因此路径 A 的第一步是补平台侧读端口**，复用 `PlatformOpsSessionPort` 的运维会话窗模式（`PlatformCapabilityConsoleController.java:254`）。

### 2.3 S2 执行级 —— 拆分「选租户」与「选 Agent」（**用户诉求的直接落点**）

**目标**：AI 实验室页面上，**租户选择独立于 Agent 选择**；两者都可选，但组合约束在提交时校验。

| 项 | 内容 |
|---|---|
| 涉及页面 | `pages/agentlab/AgentLabPage.tsx` |
| 改造 | 见 §2.3.1 |
| 后端 | **无需改动**（票证端点已按路径参数收租户：`PlatformExecutionTicketController.java:62-75`） |
| 风险 | 低—中（前端单文件为主，需补测试） |

#### 2.3.1 具体改造设计

**现状问题**（`AgentLabPage.tsx`）：

```
:207   getConsoleAgentRegistry(tenantId ? { tenantId } : undefined)   ← 租户只是「过滤器」
:258   const ticketTenantId = selectedAgent?.tenantId ?? ''          ← 租户 = Agent 反推
:599   disabled={ticketTenantId === ''}                               ← 无 Agent ⇒ 无法取票
:685   disabled={agentId === '' || ... || loginName === ''}            ← 无 Agent ⇒ 无法执行
```

**改造后**：

```
新增状态：executionTenantId（执行租户，独立 Select）
  - 来源：getPlatformTenants()（已在 :190 拉取，:565 渲染为过滤下拉）
  - 语义：这是「票证租户」，与注册表过滤器彻底分离

新增状态：agentId（被测 Agent，可选）
  - 选项来源：getConsoleAgentRegistry(executionTenantId ? {tenantId: executionTenantId} : undefined)
  - 不选 = 「平台级通用测试」（仅在 S0 允许的范围内，见下方约束）
```

**三档交互态**（用现有 `placeholder` + `notFoundContent` + `disabled` 三联动范式，参考 `AgentLabPage.tsx:589-611`）：

| 执行租户 | 被测 Agent | 界面态 | 提交行为 |
|---|---|---|---|
| 未选 | 未选 | 「先选执行租户与被测 Agent」 | 按钮禁用 + tooltip 说明缺哪一项 |
| 已选 | 未选 | 「Agent 可选：不选则不发起单 Agent 执行」 | 按 §2.3.2 决策 |
| 已选 | 已选 | 显示两者，且校验 `agent.tenantId === executionTenantId` | 正常发起 |

**跨租户错配必须前置拦截**（不能等后端 403）：现状下若给 A 租户取票去跑 B 租户的 Agent，只会在执行时授权那一步被拒（`AgentLabPage.tsx:23-25` 注释）。改造后两者独立可选，**必须在前端做一致性校验并显式提示**，否则用户会得到一个难以理解的 403。

#### 2.3.2 关键决策：未选 Agent 时「发起执行」怎么办？

这是本方案**唯一的实质性产品决策**，需拍板。三选项：

| 选项 | 行为 | 评价 |
|---|---|---|
| **① 保持 Agent 必填，仅解耦租户** | 按钮禁用条件改为 `executionTenantId === '' \|\| agentId === '' \|\| ...` | ✅ **推荐起步**。既满足"租户不再由 Agent 反推"，又不触碰后端 `@NonNull` 契约，风险最低 |
| ② 允许无 Agent 发起 | 后端 `agentId` 改可空 → 需定义"平台默认 Agent"或"路由到入口 Agent" | ⚠️ 需后端引入"平台级默认 Agent"概念，涉及执行链语义变更，**必须单独立项** |
| ③ 无 Agent 时只做"演练"（dry-run） | 不发 `/api/runs`，只做输入校验与参数预览 | ✅ 可作为 ② 的前置，但价值有限 |

> **建议**：本轮落地 **①**，把 ② 拆为独立需求（涉及平台级 Agent 定义表缺失，见 §4）。
>
> ⚠️ **注意**：选项 ① 意味着「Agent 选择」在**发起执行**这一步**仍然必填**。用户诉求中的"agent 的选择改为非必填"，在本方案中体现为：**Agent 选择不再是"进入操作"的前置条件**（浏览、盘点、指标、依赖分析都不再需要它），**且不再承担"租户选择"的职责**；仅"发起一次真实 Run"这一个动作保留该约束 —— 因为那是后端契约与授权语义共同决定的，不是前端偷懒。

---

## 3. 红线（不可触碰）

以下四条在本次改造中**必须原样保留**，任何"简化"都会造成越权或数据泄露：

### 3.1 执行必须指名 Agent（后端契约）

`CreateRunRequest.agentId` 是 `@NonNull`，且紧凑构造器显式校验：`agentId == null || agentId.isBlank()` → 抛 `IllegalArgumentException` → 归一为 **400 INVALID_REQUEST**（`CreateRunRequest.java:35, 105-107`；`ApiExceptionHandler` 归一）。**这不是前端可绕过的约束。**

### 3.2 票证必须显式指名租户成员，不得服务端猜

`POST /api/platform/v1/tenants/{tenantId}/execution-ticket` 的 `loginName` 缺失 → **400**（`PlatformExecutionTicketController.java:68-71`）：
> loginName 必填：票证必须指名一个真实的租户成员，归属不能由服务端猜

且签发需 `platform.tenant.execution_ticket` 权限（`Namespaces.java:49`），**仅超级管理员持有**（`02_platform_seed.sql:62`），每次签发写平台审计并按租户限流（30 次/60 秒）。

### 3.3 票证只在内存，不落任何浏览器存储

`executionTicketApi.ts:11-15` 的纪律成立：票证与租户口令在同机等价可用（60 秒内），落盘即等于留下一个租户身份。刷新即失效是**特性**。

### 3.4 平台不写租户私有 Agent 配置

平台侧 Agent 只有只读端口，无写方法（`PlatformAgentDirectoryPort.java:23-33`）。平台可暂停租户，但**不可直接改租户 Agent 发布版本**（《分离方案》§5.6）。S1 的写操作必须走「申请→审批→执行」三步。

---

## 4. 阻塞项与未决问题

### 4.1 阻塞项（影响方案可落地性）

| # | 阻塞项 | 影响 | 证据 |
|---|---|---|---|
| **B1** | **平台侧无租户级 Agent 定义读端口** | 路径 A 的 S1 读端点无法直接实现，需先补 BYPASSRLS 会话窗下的读端口 | `PlatformAgentDirectoryPort.java:23-33` 仅 `listAgents`；`PlatformCapabilityConsoleController.java:254` 是现成的会话窗用法样板 |
| **B2** | **无平台级 Agent 定义表** | 若未来要支持"平台级公共 Agent"（不属任何租户），`public.cap_agent_def` 的 `tenant_id NOT NULL` + FORCE RLS 装不下 | `01_platform_ddl.sql:1455-1478` |
| **B3** | **`/api/cap/catalog/**` 双通道并存** | 平台级对象（Tool/Skill/MCP，无 tenant_id、无 FORCE RLS）却走租户身份域，且与 `/api/platform/v1/capabilities/**` 重复 | `CapabilityCatalogController.java:60,278-289` vs `PlatformCapabilityConsoleController.java:112-234`；`01_platform_ddl.sql:2110-2112, 2065-2067, 1846-1848` |
| **B4** | **AI 实验室页零测试覆盖** | 改造无回归网 | `admin-console/tests/` 下 grep `AgentLabPage` **零匹配** |

### 4.2 未决问题（需拍板）

| # | 问题 | 备选 | 影响面 |
|---|---|---|---|
| **Q1** | S1 租户级 Agent 操作走哪条路 | 路径 A / B / C（§2.2.1） | 决定后端新增端点规模 |
| **Q2** | 未选 Agent 时是否允许发起执行 | 选项 ①②③（§2.3.2） | 决定是否引入"平台默认 Agent"概念 |
| **Q3** | 是否顺带收敛 B3 的双通道 | 收敛 / 暂留 | 影响平台链端点数量与迁移工作量 |
| **Q4** | 平台级 Agent 定义是否立项 | 复用 `cap_agent_def` 加可空 tenant_id / 新建 `platform.agent_def` | 影响 B2，且属独立需求 |

> **Q2 与 Q4 建议不在本轮做**：两者都触及执行链语义与数据模型基线，应单独立项评审。本轮只做 §2.1 + §2.3.1（选项 ①）+ §2.2.1 路径 A 的**读侧**。

---

## 5. 实施路径（分三批）

### 批次 1：AI 实验室解耦（低风险，前端为主）

| 步骤 | 内容 | 文件 | 验收 |
|---|---|---|---|
| 1.1 | 引入独立「执行租户」Select，与注册表过滤器分离 | `pages/agentlab/AgentLabPage.tsx` | 换租户无需先选 Agent |
| 1.2 | 「被测 Agent」下拉按执行租户过滤，且**可清空** | 同上 | `allowClear` + 空态文案 |
| 1.3 | 提交前一致性校验 `agent.tenantId === executionTenantId` | 同上 | 错配在**前端**提示，不等后端 403 |
| 1.4 | 三档交互态（未选/半选/全选）文案与禁用态 | 同上 | 缺项时 tooltip 说清缺哪一项 |
| 1.5 | **补测试**（当前零覆盖） | 新建 `tests/agentLabScope.test.tsx` | 覆盖：未选租户禁用、未选 Agent 禁用、跨租户错配拦截、租户切换作废票证 |

**注意**：`agentLabRunBody.test.ts:68-70` 断言请求体键集合含 `agentId` —— 本批次**不改请求体契约**（选项 ① 下 `agentId` 仍必传），该断言不受影响。

### 批次 2：S0 显式标注 + 指标下钻（低风险，纯前端）

| 步骤 | 内容 | 文件 |
|---|---|---|
| 2.1 | 注册表页补"跨租户全量盘点"显式说明 | `pages/console/AgentRegistryPage.tsx` |
| 2.2 | 指标页补"平台视角（跨租户）"标注 | `pages/metrics/AgentMetricsPage.tsx` |
| 2.3 | 指标页新增**可选**租户/Agent 下钻筛选（后端已支持） | 同上 + `api/metricsApi.ts` |

### 批次 3：S1 平台链读端点（中风险，后端为主）

| 步骤 | 内容 | 文件 |
|---|---|---|
| 3.1 | 平台侧补租户级 Agent 定义读端口（BYPASSRLS 会话窗） | `agent-ports/.../PlatformAgentDirectoryPort.java` + `agent-infrastructure/.../JdbcPlatformAgentDirectoryRepository.java` |
| 3.2 | 新增 `/api/platform/v1/tenants/{tenantId}/agents/**` 读端点 | `agent-api/.../api/platform/PlatformCapabilityConsoleController.java` 或新建 Controller |
| 3.3 | 前端 `adminApi.ts` 加对应封装 | `admin-console/src/api/adminApi.ts` |
| 3.4 | 恢复/改造 `pages/AgentDetailPage.tsx` 等 S1 页面并接回路由 | `admin-console/src/router.tsx` + `layout/navConfig.tsx` |
| 3.5 | 补权限校验（`platform.resource.read` / 必要时新增 `platform.agent.read`） | `Namespaces.java` + `02_platform_seed.sql` |

---

## 6. 影响面清单（前端）

| 文件 | 现状 | 批次 | 改动性质 |
|---|---|---|---|
| `pages/agentlab/AgentLabPage.tsx` | Agent 必填 + 租户由 Agent 反推 | 1 | **重写选择逻辑**（`:246-301, 411-416, 589-611, 685`） |
| `pages/console/AgentRegistryPage.tsx` | 跨租户只读，无标注 | 2 | 加说明文案 |
| `pages/metrics/AgentMetricsPage.tsx` | 跨租户聚合，无租户/Agent 筛选 | 2 | 加标注 + 可选筛选 |
| `api/agentLabApi.ts` | `agentId: string` 必填 | 1 | **不改**（选项 ① 下契约不变） |
| `api/executionTicketApi.ts` | 票证内存态 | 1 | 不改 |
| `api/metricsApi.ts` | 参数已可选 | 2 | 加下钻封装 |
| `api/adminApi.ts` | `getConsoleAgentRegistry` 参数已可选 | 3 | 加 S1 端点封装 |
| `pages/AgentDetailPage.tsx` 等 S1 旧页 | 路由已摘除 | 3 | 接回 + 改数据源 |
| `router.tsx` / `layout/navConfig.tsx` | S1 页面未挂载 | 3 | 挂载 + 角色可见性 |

## 7. 影响面清单（后端）

| 文件 | 现状 | 批次 | 改动 |
|---|---|---|---|
| `api/platform/PlatformCapabilityConsoleController.java` | `GET /capabilities/agents` tenantId 可选 | 1–2 | **不改**（可选性已具备） |
| `api/platform/PlatformAgentMetricsController.java` | tenantId/agentId 可选 | 2 | **不改** |
| `api/platform/PlatformExecutionTicketController.java` | 租户走路径参数 | 1 | **不改**（前端传参改造即可） |
| `ports/persistence/cap/PlatformAgentDirectoryPort.java` | 仅 `listAgents` | 3 | **新增**租户级定义读方法 |
| `infrastructure/persistence/cap/JdbcPlatformAgentDirectoryRepository.java` | 单条盘点 SQL | 3 | **新增**读端口实现 |
| `api/run/CreateRunRequest.java` | `agentId` `@NonNull` | — | **不改**（红线） |
| `ports/security/Namespaces.java` | 无 `platform.agent.read` | 3 | 可能新增 |
| `deploy/sql/platform/02_platform_seed.sql` | 权限种子 | 3 | 可能新增 |

---

## 8. 验收标准

### 8.1 功能验收

| # | 验收项 | 验证方式 |
|---|---|---|
| A1 | AI 实验室：换执行租户**不需要**先选中任何 Agent | 前端 E2E |
| A2 | AI 实验室：Agent 与执行租户跨租户错配时，前端拦截并说明 | 单测 + E2E |
| A3 | 批次 2：指标页可按租户/Agent 下钻，且**不选时仍是跨租户聚合** | 前端单测 |
| A4 | 批次 3：S1 读端点返回**指定租户**的 Agent 定义 | 后端 IT（断言 tenant_id 隔离） |
| A5 | **未选执行租户时，「发起执行」不可点**且提示说清缺什么 | 前端单测 |

### 8.2 安全验收（红线回归）

| # | 验收项 | 验证方式 |
|---|---|---|
| S1 | 平台令牌访租户域端点仍 401 | 集成测试（两套 JWT 互打） |
| S2 | 平台令牌**不能**直接写租户 Agent 配置 | 端点 403 测试 |
| S3 | 票证缺失/过期/角色不符 → 401/403，界面分类呈现 | 前端单测 + IT |
| S4 | 票证**不落** sessionStorage/localStorage | 前端单测断言 |
| S5 | 跨租户读 Agent 定义被 RLS 或路径参数双重限定 | RLS 集成测试 |
| S6 | 执行时审计可回溯"谁、以谁的租户身份、跑了哪个 Agent" | 审计断言 |

### 8.3 回归护栏

| # | 项 |
|---|---|
| R1 | `agentLabRunBody.test.ts` 的请求体键集合断言**必须保持绿**（选项 ① 下契约不变） |
| R2 | 新增 `tests/agentLabScope.test.tsx` 覆盖 AI 实验室新选择逻辑（当前零覆盖） |
| R3 | `agentRegistry.test.tsx` / `metricEntry.test.tsx` / `routingPolicyPage.test.tsx` 保持绿 |

---

## 9. 配套文档

- **《admin-console Agent 操作改造清单》**（同目录 `admin-console_Agent操作_改造清单.md`）：逐文件的改动项、验收断言、测试补写清单。
- 上游依据：`docs/重规划/平台身份与租户身份分离改造方案.md`（§5.6 边界、§6 矩阵、§10 禁区）。
- 现状复盘：`docs/管理后台开发计划/00_计划总览与阶段划分.md`（W-ADMIN 阶段划分）。

---

> **本方案为规划文档，落地前需经 Q1–Q4 拍板。** 未拍板前，批次 1 与批次 2 可先行开工（不触碰后端契约与身份域边界）。
