# admin-console Agent 操作改造清单（逐文件）

> **配套主方案**：《admin-console · Agent 相关操作「租户绑定松绑 + Agent 选择非必填」改造方案》（同目录 `admin-console_Agent操作_租户绑定松绑方案.md`）。
> **本清单性质**：可直接照着做的施工单。每条改动都带**文件路径 + 行号锚点 + 验收断言**。
> **代码事实基线**：2026-10-29 全量核实；本文所引行号均已逐一核对。

---

## 批次 1 · AI 实验室解耦（前端单文件为主）

### 1.0 改动目标

把「执行租户」从「被选 Agent 的反推值」改为**独立显式输入**，同时保持「发起执行仍需指名 Agent」（后端 `@NonNull` 红线，见主方案 §3.1）。

### 1.1 `admin-console/src/pages/agentlab/AgentLabPage.tsx`

#### 改动点 1-1 · 拆分两个租户语义

| 项 | 内容 |
|---|---|
| 现状锚点 | `:142-146`（`tenantId` / `agents` / `agentId` 状态）、`:207`（`getConsoleAgentRegistry(tenantId ? { tenantId } : undefined)`）、`:258`（`ticketTenantId = selectedAgent?.tenantId ?? ''`） |
| 改法 | 新增 `executionTenantId` state，与现有 `tenantId`（注册表过滤器）**语义彻底分离** |
| 命名建议 | `tenantId` → 重命名为 `registryTenantId`（注册表过滤）；新增 `executionTenantId`（票证租户） |

```ts
// 新增：执行租户（票证租户）—— 与注册表过滤器是两个不同的东西
const [executionTenantId, setExecutionTenantId] = useState('')
```

⚠️ **不要**直接把现有 `tenantId` 改造成执行租户 —— 它当前是**可选过滤器**（`:560` `allowClear`、`:562` placeholder「按租户过滤 Agent 注册表」），改成必填会**倒退**用户已有的能力。必须是两个独立 state。

#### 改动点 1-2 · Agent 下拉按执行租户过滤

| 项 | 内容 |
|---|---|
| 现状锚点 | `:202-220`（拉取 effect，依赖 `[tenantId]`）、`:222-232`（`agentOptions`）、`:574-584`（Agent Select） |
| 改法 | 拉取 effect 的依赖从 `tenantId` 改为 `executionTenantId`；`tenantId` 过滤器保留但**仅作用于只读盘点视图** |

```ts
// 现状：:207
const list = await getConsoleAgentRegistry(tenantId ? { tenantId } : undefined)
```

⚠️ **取舍**：若让「执行租户」同时成为注册表过滤条件，则"换执行租户 → Agent 列表跟着变"，符合直觉；若保留两个独立过滤器，则可能出现"选了 A 租户的 Agent，但执行租户是 B 租户"的错配 —— 这正是改动点 1-4 要拦的。**推荐合并**：执行租户即过滤条件，删掉原 `tenantId` 过滤器，避免两个"租户"控件并存造成用户困惑。

#### 改动点 1-3 · Agent 选择仍必填，但不再是"进入页面的门槛"

| 项 | 内容 |
|---|---|
| 现状锚点 | `:685` `disabled={agentId === '' \|\| input.trim() === '' \|\| loginName === ''}` |
| 改法 | 按钮禁用条件改为 `executionTenantId === '' \|\| agentId === '' \|\| input.trim() === '' \|\| loginName === ''`；**`agentId === ''` 这一项保留**（红线） |
| 文案 | 缺项时明确说清缺的是「执行租户」还是「被测 Agent」，不要只写「请选择」 |

⚠️ 建议给按钮加 `Tooltip` 说明缺失项，参考仓库既有的 `RagSourceTable.tsx:143-155` 范式（禁用 + tooltip 把「为什么不能点」说清楚）。

#### 改动点 1-4 · 跨租户错配前置拦截（**本批次最关键的正确性改动**）

| 项 | 内容 |
|---|---|
| 现状锚点 | `:246-249`（`selectedAgent` 反查）、`:258`（原 `ticketTenantId` 来源） |
| 问题 | 改造后两个租户输入独立，若用户选了 B 租户的 Agent 却用 A 租户取票，会得到一个语义模糊的 403（现状注释 `AgentLabPage.tsx:23-25` 已描述该失败路径） |
| 改法 | 在 `execute()` 入口（`:401` 附近）显式校验 `selectedAgent.tenantId === executionTenantId`，不等后端拒绝 |

```ts
// execute() 入口新增校验（示意）
if (selectedAgent && selectedAgent.tenantId !== executionTenantId) {
  setErrorKind('input')
  setErrorText(
    `被测 Agent 属于租户 ${selectedAgent.tenantId}，与执行租户 ${executionTenantId} 不一致：` +
    '票证绑定的是执行租户，跨租户执行会在「执行时授权」那一步被拒。',
  )
  return
}
```

⚠️ 错误类型建议复用既有 `errorKind` 分类（`'none' | 'channel' | 'ticket' | 'ratelimit' | 'input' | 'other'`，`:165-167`），**新增一种错别新增类型** —— 属配置/选择错误，归 `input` 最贴近现有语义。

#### 改动点 1-5 · 租户切换即作废票证

| 项 | 内容 |
|---|---|
| 现状锚点 | `:260-265` `useEffect(..., [ticketTenantId, loginName])` → `clearExecutionTicket()` |
| 改法 | 依赖项 `ticketTenantId` → `executionTenantId` |
| 断言 | 换执行租户后 `peekExecutionTicket()` 必须返回 `null`（旧票属于上一个租户，复用即串租户） |

#### 改动点 1-6 · 租户成员列表按执行租户加载

| 项 | 内容 |
|---|---|
| 现状锚点 | `:267-301`（`useEffect` 依赖 `[ticketTenantId]`，`:268` 空则清空 `principals`/`loginName`） |
| 改法 | 同上，换成 `executionTenantId` |
| 保留 | `:288` 的 `status === 'ACTIVE'` 过滤（后端只对 ACTIVE 成员签发票证） |

#### 改动点 1-7 · 「租户身份（取票）」下拉的三态文案

| 项 | 内容 |
|---|---|
| 现状锚点 | `:589-611`（`placeholder` / `disabled` / `notFoundContent` 三联动） |
| 改法 | 判据 `ticketTenantId === ''` → `executionTenantId === ''`；`notFoundContent` 的「未选择 Agent」→ 改为「未选择执行租户」 |

⚠️ 该处现有文案「先选择 Agent：票证绑到该 Agent 所属租户」（`:594`）在改造后**语义已错** —— 票证不再绑 Agent，绑的是**执行租户**。必须改写。

#### 改动点 1-8 · Agent 详情 Descriptions 展示

| 项 | 内容 |
|---|---|
| 现状锚点 | `:630-656`（选中 Agent 时展示其租户/版本/生命周期） |
| 改法 | 保留；但可加一行「执行租户」，与被测 Agent 租户并排，让跨租户错配一眼可见 |

---

### 1.2 `admin-console/src/api/agentLabApi.ts`

| 项 | 内容 |
|---|---|
| 改动 | **不改**（选项 ① 下 `agentId` 仍必传） |
| 理由 | `CreateRunRequest.agentId: string`（`:39`）对应后端 `CreateRunRequest.java:35, 105-107` 的 `@NonNull` + 显式校验 |

---

### 1.3 新增测试 `admin-console/tests/agentLabScope.test.tsx`

⚠️ **当前 `admin-console/tests/` 下 grep `AgentLabPage` 零匹配** —— 本页零测试覆盖，改动无回归网。**必须先补测试再改**（或至少同批次补）。

**建议复用 `executionTicketChannel.test.ts` 的夹具范式**（假 fetch + fake timers + `sent[]` 出网记录，`:76-100`），因为它已经在测票证通道的边界。

| # | 测试项 | 断言 |
|---|---|---|
| T1-1 | 未选执行租户时「发起执行」禁用 | 按钮 `disabled` 属性为 true |
| T1-2 | 已选执行租户、未选 Agent 时仍禁用（红线保留） | 按钮 `disabled` 为 true |
| T1-3 | 未选执行租户时「租户身份（取票）」下拉禁用 | `disabled` 为 true，且 `placeholder` 含「执行租户」 |
| T1-4 | 换执行租户后旧票作废 | `peekExecutionTicket()` === `null` |
| T1-5 | 换执行租户后重新加载该租户成员 | 出网请求命中 `/tenants/{新租户}/principals` |
| T1-6 | 跨租户错配被前端拦截 | 提交后**不发出** `/api/runs` 请求，且错误文案含两个租户 id |
| T1-7 | 正常路径仍发出 `/api/runs` 且带票证头 | `Authorization` 为 `Bearer {租户令牌}` |
| T1-8 | 票证不落浏览器存储 | `sessionStorage`/`localStorage` 无票证字段 |

⚠️ T1-6 是**本批次的核心回归断言**：现状下错配要到后端执行时才失败（`AgentLabPage.tsx:23-25` 注释所述），改造后必须前端拦截。

### 1.4 回归护栏

| 文件 | 断言位置 | 要求 |
|---|---|---|
| `tests/agentLabRunBody.test.ts` | `:68-70` 请求体键集合含 `agentId` | **必须保持绿**（契约未变） |
| `tests/executionTicketChannel.test.ts` | `:99-246` 全部 11 项 | **必须保持绿** |
| `tests/agentRegistry.test.tsx` | 全量 | 保持绿（不涉及本页） |

---

## 批次 2 · S0 显式标注 + 指标下钻（纯前端）

### 2.1 `admin-console/src/pages/console/AgentRegistryPage.tsx`

| 改动点 | 锚点 | 内容 |
|---|---|---|
| 2-1-1 | `:146-153`（Tabs）、`:160` 起（Alert 区） | 补一句显式说明：「当前为**跨租户全量盘点**（N 个租户 / M 个 Agent）；按租户切换只是过滤同一份数据，不重新请求」 |
| 2-1-2 | `:155` 附近（标题 `<TeamOutlined /> Agent 注册表（跨租户）`） | 标题已有「（跨租户）」，正文补租户数/Agent 数即可 |

⚠️ 数据来源已含租户维度：`:125` `tenantIds = [...new Set(filtered.map((r) => r.tenantId))]`，统计可直接复用。

### 2.2 `admin-console/src/pages/metrics/AgentMetricsPage.tsx`

| 改动点 | 锚点 | 内容 |
|---|---|---|
| 2-2-1 | `:268-276`（`Segmented` 时间窗） | 补「**平台视角（跨租户聚合）**」标注 —— 现在读者可能误以为这是本租户数据 |
| 2-2-2 | 同上 | 新增**可选**的租户下拉 + Agent 下拉（默认留空 = 跨租户） |
| 2-2-3 | `:116-130`（`load` 函数） | 传参：`fetchAgentMetricOverview(hours, tenantId)` —— 参数已支持 |

### 2.3 `admin-console/src/api/metricsApi.ts`

| 改动点 | 锚点 | 内容 |
|---|---|---|
| 2-3-1 | `:116` | `fetchAgentMetricOverview(hours, tenantId?)` —— **已支持**，无需改签名 |
| 2-3-2 | `:101-110`（`query` helper） | **无需改**：已跳过 `undefined`/`''` |

⚠️ 后端 `PlatformAgentMetricsController.java:78-81` 的 `tenantId`/`agentId`/`agentVersion`/`scenario` **全部 `required = false`** —— 前端放开不需要任何后端改动。

### 2.4 测试

| 文件 | 内容 |
|---|---|
| `tests/agentMetrics.test.tsx` | 补一条：不选租户/Agent 时，请求 URL **不含** `tenantId`/`agentId` 参数（证明默认跨租户） |
| `tests/agentRegistry.test.tsx` | 补一条：页面文本含「跨租户」字样（防回归掉标注） |

---

## 批次 3 · S1 平台链读端点（后端为主）

⚠️ **前置**：主方案 §4.1 的 **B1**（平台侧无租户级 Agent 定义读端口）必须先解决。

### 3.1 后端 · 新增平台侧读端口

| 文件 | 改动 |
|---|---|
| `platform/agent-ports/.../persistence/cap/PlatformAgentDirectoryPort.java` | 现仅 `listAgents(@Nullable String tenantIdFilter)`（`:23-33`）。新增租户级定义读方法，如 `readDefinition(String tenantId, String agentId)` |
| `platform/agent-infrastructure/.../persistence/cap/JdbcPlatformAgentDirectoryRepository.java` | 新增实现。**必须走运维会话窗**（BYPASSRLS），样板见 `PlatformCapabilityConsoleController.java:254` 的 `platformOps.inPlatformScopeSession(() -> ...)` |

⚠️ **安全约束**：新 SQL **必须**同时带 `tenant_id = ?` 条件，不能依赖「上层已过滤」。现状盘点 SQL 的 `WHERE (?::uuid IS NULL OR a.tenant_id = ?::uuid)` 是**可空过滤**形态（`:80`），新方法**不可**照抄这个形态 —— 那是盘点（可跨租户）的语义，新方法是单租户读取。

### 3.2 后端 · 新增平台链端点

| 端点 | 方法 | 租户来源 | 授权 |
|---|---|---|---|
| `/api/platform/v1/tenants/{tenantId}/agents/{agentId}/definition` | GET | 路径参数 | `platform.resource.read` |
| `/api/platform/v1/tenants/{tenantId}/agents/{agentId}/versions` | GET | 路径参数 | `platform.resource.read` |

| 文件 | 改动 |
|---|---|
| `platform/agent-api/.../api/platform/PlatformCapabilityConsoleController.java` 或新建 `PlatformTenantAgentController.java` | 新增端点 + `security.require(principal, ...)` + UUID 校验（样板：`PlatformExecutionTicketController.java:91`） |

⚠️ **不要**放进 `/api/platform/v1/capabilities/agents` 的查询参数里 —— 那是「跨租户盘点」，语义不同；单租户读取应走独立路径，路径参数自带作用域。

### 3.3 前端 · API 封装与页面

| 文件 | 改动 |
|---|---|
| `admin-console/src/api/adminApi.ts` | 新增 `getPlatformTenantAgentDefinition(tenantId, agentId)` 等封装 |
| `admin-console/src/api/types.ts` | 复用已有 `AgentDefinition`（`:612`）类型 |
| `admin-console/src/pages/AgentDetailPage.tsx` | 当前走 `/api/agents/**`（租户域，平台令牌必 401）。改为走新平台链端点，**租户由页面显式选择**（路径参数） |
| `admin-console/src/pages/AgentDependenciesTab.tsx` | 数据源 `/api/admin/dependencies/impact`（租户域）→ 需对应平台链版本或走票证 |
| `admin-console/src/router.tsx` | `:91-99` 附近挂载 S1 页面路由（当前 S1 页面**未挂载**，见 `router.tsx:28-31` 注释） |
| `admin-console/src/layout/navConfig.tsx` | `:109-118`「Agent 注册表」组补 S1 入口与角色可见性 |

### 3.4 权限（若需要新码）

| 文件 | 改动 |
|---|---|
| `platform/agent-ports/.../security/Namespaces.java` | 可能新增 `platform.agent.read`（现有 `RESOURCE_READ` 是否足够需确认） |
| `deploy/sql/platform/02_platform_seed.sql` | 同步新增权限码 + 角色绑定（**必须两边同步**，见该文件 `:11-13` 注释） |

### 3.5 测试

| 层次 | 内容 |
|---|---|
| 后端 IT | 租户 A 的路径参数取不到租户 B 的 Agent（断言 404/403）；平台令牌访租户域端点仍 401 |
| 前端 | S1 页面租户切换后数据正确刷新；未选租户时入口禁用 + tooltip |

---

## 附录 A · 后端「必填 tenantId」端点清单（**改造时不可误伤**）

这些端点 `tenantId` 是**真必填**，与本次改造无关，改动时不要顺手放开：

| 端点 | 校验位置 | 失败码 |
|---|---|---|
| `POST /api/platform/v1/tenants/{tenantId}/execution-ticket` | `PlatformExecutionTicketController.java:68-71`（loginName）、`PlatformExecutionTicketService.java:203-205`（UUID） | 400 |
| `POST /api/platform/v1/metrics/agent/collect` | `PlatformAgentMetricsController.java:155-157` | 400 |
| `POST /api/platform/v1/metrics/agent/eval-samples` | `:181-182` | 400 |
| `POST /api/platform/v1/metrics/agent/feedback` | `:202-203` | 400 |
| `POST /api/platform/v1/metrics/agent/risk-events` | `:228-229` | 400 |
| `POST /api/platform/v1/metrics/agent/task-outcomes` | `:250-251` | 400 |
| `GET /api/platform/v1/tenants/{id}/principals` | 路径参数 + `platform.tenant.read`（`:91`） | 401/403 |

⚠️ 指标录入类端点必填 tenantId 是**语义要求**（写的是某个租户的 trace 数据），不是历史包袱 —— **保持必填**。

## 附录 B · 禁区红线（改造中逐条自查）

| # | 红线 | 证据 |
|---|---|---|
| R1 | 平台不直接写租户私有 Agent 配置 | `PlatformAgentDirectoryPort.java:23-33`（只有只读） |
| R2 | 平台令牌访租户域端点必 401 | `SecurityConfiguration` 双链 + `JwtTokenService.java:246-248` |
| R3 | 票证必须显式指名租户成员 | `PlatformExecutionTicketController.java:68-71` |
| R4 | 票证只在内存，不落浏览器存储 | `executionTicketApi.ts:11-15` |
| R5 | 平台可暂停租户，不可改租户 Agent 发布版本 | 《分离方案》§5.6 |
| R6 | 平台控制台禁区：不看租户私有 Agent 定义正文 / 不看会话输出 / 不读凭据明文 | 《分离方案》§10 |
| R7 | 执行必须指名 Agent | `CreateRunRequest.java:35, 105-107` |
| R8 | 权限码种子必须两边同步 | `02_platform_seed.sql:11-13` |

## 附录 C · 实施顺序建议

```
批次 1（前端，无后端依赖）── 可立即开工
   ├─ 1.0 先补 agentLabScope.test.tsx（零覆盖 → 先建网）
   ├─ 1.1~1.8 改 AgentLabPage.tsx
   └─ 验收：agentLabRunBody / executionTicketChannel / agentLabScope 三套全绿

批次 2（前端，后端零改动）── 可与批次 1 并行
   └─ 验收：agentMetrics / agentRegistry 全绿

批次 3（后端 + 前端）── 需先拍板 Q1（主方案 §4.2）
   ├─ 3.1 平台侧读端口（BYPASSRLS 会话窗）
   ├─ 3.2 平台链端点
   ├─ 3.3 前端封装与页面接回
   └─ 验收：后端 IT + 安全回归 S1~S6 全绿
```
