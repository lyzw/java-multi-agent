# PRD：Agent / Skill / Tool / MCP 统一配置与权限管控体系

> 版本：v1.1（修订版，针对团队评审 P0/P1 修订；设计方向继承自 v1.0）
> 适用范围：复用 `platform/` 现有地基（`ToolHandler` SPI、`AuthorizationService`、`ToolInvocationGate`、`EffectiveConfigResolver`、`ConfigTighteningPolicy`、RLS、authzVersion）
> 关联文档：`01_现状分析.md`、`02_分层授权设计评估.md`、`04_V1范围与非目标基线.md`、`05_分层授权决策规范.md`、`06_配置数据模型与生命周期设计.md`、`07_执行链集成设计.md`、`08_安全测试与验收矩阵.md`、`09_需求追踪矩阵.md`
> **本 PRD 不独立重定义安全规则**——决策公式/错误码/撤权/异常以 **05** 为唯一规范来源。

---

## 1. 目标与非目标

### 1.1 目标（Goals）
- **G1 统一建模**：Agent / Skill / Tool / MCP 纳入声明式配置模型与单一事实源，消除"Agent 自由 JSONB"与"Tool Bean 注册"平行割裂。
- **G2 分层能力禁闭**：「Agent 限定可用 Skill，Skill 声明可用 Tool 与 MCP」单向委托链，有效权限 = 沿链逐层取交集闭包。
- **G3 运行期纵深防御**：每次调用沿链重验 + `authzVersion` 重授权（实时，非快照）。
- **G4 复用既有安全地基**：MCP-backed 与 Native Tool 统一实现 `ToolHandler`，落入 `ToolInvocationGate`；配置走 `EffectiveConfigResolver` + 收紧棘轮。
- **G5 可审计与可观测**：每次决策结构化日志（四字段：`authorizationDecision` / `executionDecision` / `executionStatus` / `reasonCode`，见 05 §0）。

### 1.2 非目标（Non-Goals）
- **NG1** 不重建底层认证（JWT/Spring Security）与租户隔离（RLS）——直接复用。
- **NG2** 不实现策略即代码（OPA/Rego）——V3 外置。
- **NG3** 不实现 Skill 自我进化/自动组合——嵌套 Skill 仅 V2 显式声明 + transitive enablement。
- **NG4** 不替代 `execution_snapshot`——在其内固化有效能力闭包（06 §1.6）。
- **NG5** 不处理跨 Agent 委托（A2A）——V3。
- **NG6** V1 **不实现**嵌套 Skill / Capability Tag / Skill Bundle / 动态 MCP 发现 / 通用参数策略引擎（见 **04** 冻结边界）。

---

## 2. 核心概念与配置模型

### 2.1 概念与依赖方向
```
ToolDefinition ─┐
                ├─(被声明)─▶ SkillDefinition ─(被启用)─▶ AgentDefinition
McpServerDef ───┘                                   （单向、不可反向伸手）
```
- **Tool**：最小可执行单元。`native`（现有 `ToolHandler` Bean）/ `mcp`（经 MCP-backed `ToolHandler` 包装）。二者统一接口，共享 `riskLevel`/`inputSchema`/`validate`/`execute`。
- **McpServerDef**：外部 MCP server 接入（**V1 固定清单 + 指纹校验，无动态发现**）。
- **Skill**：能力包。声明可用 Tool + 接入 MCP（连接级 + 操作级）+ `riskCeiling` + `paramPolicies`（V1 仅两点）。
- **Agent**：编排主体。声明启用 Skill 集合（V1 仅显式 ID）+ `roles` + `overrides`（收紧）。

### 2.2 字段定义（含评审 P1-2 MCP 安全补充）

#### ToolDefinition
| 字段 | 类型 | 说明 |
|---|---|---|
| `id`/`name` | string | 全局唯一 |
| `kind` | enum | `native` \| `mcp` |
| `riskLevel` | enum（**必填**） | READ/WRITE_PROFILE/WRITE_EXTERNAL（缺省/未知/非法 → **配置期拒绝发布、运行期 authorizationDecision=DENY(RISK_LEVEL_UNKNOWN)**，不再降级为 WRITE_EXTERNAL，05 §3.3） |
| `mcpServerId`/`mcpToolName` | string? | kind=mcp |
| `inputSchema` | JSON Schema | 结构校验 |
| `paramPolicies` | list | 参数级业务权限（V1 仅 `in_allowlist`/`lte`） |
| `namespace` | string | **V1 仅 `platform`**（04 §3） |
| `configFingerprint` | string | 内容指纹（06 §1.1） |

#### McpServerDef（V1 固定清单）
| 字段 | 类型 | 说明 |
|---|---|---|
| `id`/`transport` | — | 同 v1.0 |
| `endpoint`/`command` | string? | **须落允许范围（防 SSRF，06 §5）** |
| `auth` | object | token/oauth/none；**凭据引用 `credential_ref`，明文禁入库、日志脱敏** |
| `trustTier` | enum | internal/partner/public（影响默认审批） |
| `exposedToolAllowlist` | list? | 固定工具名清单；`null` vs `[]` 语义不同（见 §2.6） |
| `connectionRisk` | enum | 连接级风险（默认映射 trustTier） |
| `dynamicDiscovery` | bool | **V1 固定 false**（写入 true 即拒） |
| `timeout`/`maxResponseBytes`/`maxConcurrency` | — | 连接/读超时、响应上限、并发上限、失败隔离（06 §5） |

#### SkillDefinition
| 字段 | 类型 | 说明 |
|---|---|---|
| `id`/`name`/`description` | — | |
| `declaredTools` | list<string> | 引用 Tool id（单一事实源） |
| `declaredMcp` | list<McpBinding> | `[{serverId, toolAllowlist?}]` 连接级+操作级分离 |
| `riskCeiling` | enum | Skill 可驱动 Tool 最高风险；超过即拒 |
| `paramPolicies` | list | Skill 级参数约束（叠加 Tool，取更严；V1 仅两点） |
| `inherits` | list<string> | **V1 写入即拒**（04 §3） |
| `requiredCaps` | list<string> | **V1 忽略语义（预留）** |
| `namespace` | string | platform |

#### AgentDefinition
| 字段 | 类型 | 说明 |
|---|---|---|
| `id`/`tenantId`/`name` | — | RLS 隔离 |
| `roles` | list<string> | **仅声明 Agent 的适用主体角色范围/调用资格，非授权授予**（见 §2.2.1） |
| `enabledSkills` | list<string> | **V1 唯一启用途径（显式 ID）** |
| `skillBundles`/`capabilityTags` | list<string> | **V1 忽略语义（预留）** |
| `inherits` | string? | **V1 不支持（写入即拒）** |
| `overrides` | object | 仅收紧：`{removeSkills?, removeTools?, lowerRiskCeiling?}` |
| `version` | int | agentVersion |

> **§2.2.1 `Agent.roles` 非授权授予（评审 §4.2）**：`roles` 仅用于定义 Agent 的"适用主体角色范围或调用资格"，**不得**授予调用者超出 `AuthorizationService` 的主体权限。最终调用授权必须以可信认证上下文（`TenantContext` 的 `roles`/`authzVersion`）和当前授权状态为准，`AgentDefinition.roles` 不能作为权限来源；配置 Agent 时若将角色声明误读为权限授予，属于实现缺陷。

#### 配置对象统一生命周期状态（评审 §4.4，详设见 06 §3）
所有可引用对象（`cap_tool_def` / `cap_skill_def` / `cap_mcp_server_def` / `cap_agent_def`）采用同一状态机：`DRAFT → VALIDATED → PUBLISHED → DEPRECATED`。
- 未发布（DRAFT/VALIDATED）对象**不能**被 Agent 启用。
- 已 DEPRECATED 对象**不能**被新 Run 引用；存量 Run 是否继续由 05 §5 版本失效规则决定。
- 配置回滚**只**恢复配置版本，**不能**恢复已撤销的主体授权；删除不清除安全审计与历史发布记录。

### 2.3 层级与依赖方向（不可反转）
- 单向向下：Agent → Skill → {Tool, MCP}；反向引用禁止。
- **唯一执行入口**：无论 native/mcp 均经 `ToolInvocationGate`，禁 Spring AI 自动执行（ADR-011）；直接调用 `ToolHandler` 实现类视为绕过（07 §3）。

### 2.4 继承与覆盖规则（优先级，最高胜出）
```
Agent.overrides（显式收紧）
   > Agent.enabledSkills（含 inherits 模板，V1 无）
      > Skill.inherits（V1 无）
         > 平台默认（Tool/MCP 单一事实源）
```
- **合并**：Skill 工具集 = 自身 ∪ inherits（V2）；Agent 有效集 = enabledSkills ∪ bundles（V2） − overrides.removeSkills。
- **收紧棘轮（铁律）**：overrides 只能移除/调低，不得新增/放宽；违反显式抛异常（非静默取严）。

### 2.5 参数级策略（paramPolicies，V1 仅两点，缓解 G2）
```json
{ "param": "toDomain", "op": "in_allowlist", "value": ["school.edu", "parent.org"] }
{ "param": "maxRows",  "op": "lte", "value": 100 }
```
- **与 JSON Schema 的区别**：Schema 校验"结构合法"，paramPolicies 校验"业务权限是否允许该输入"——两层不同校验（评审 03 P1-3）。
- **运算符语义（V1 支持）**：
  - `in_allowlist`：参数值 ∈ value 集合（字符串/枚举）；类型非字符串 → 拒。
  - `lte`：数值型参数 ≤ value；非数值 → 拒。
  - 其他 op（gte/eq/regex/嵌套路径/数组）→ **V1 不支持，写入即拒**（04 §3）。
- **多策略组合**：同参数多策略取**更严交集**（AND）；参数缺失且策略要求该参数 → 拒；策略缺失 ≠ 放行一切（高危建议默认强策略）。
- **求值位置**：`paramPolicies` 在 **Phase A 授权阶段**作为业务权限策略求值（与结构校验分离）；Skill 级与 Tool 级取更严。结构校验（`ToolHandler.validate` / JSON Schema）只在 **Phase B 执行准入阶段**单次执行（见 §3.2 [5]/[7] 与 05 §1）。

### 2.6 null vs 空集合语义（评审"三条细节"之三）
> 配置 Schema 必须区分"未配置"与"明确禁止"——`null` 与 `[]` **不等价**。

| 字段 | `null`（未配置） | `[]`（空集合，明确禁止） | 缺失字段 |
|---|---|---|---|
| `McpServer.exposedToolAllowlist` | 仅连接，调用时按 Skill `toolAllowlist` 兜底 | 禁止该 server 任何工具（连接级仍开但无操作可用） | 同 `null` |
| `Skill.declaredMcp[].toolAllowlist` | 允许该 server `exposedToolAllowlist` 全部（仍受连接级） | 禁止该 server 任何工具（操作级全拒） | 同 `null` |
| `Tool.riskLevel` | 配置期**拒绝发布**（必填，不可缺省/未知） | 不适用（枚举非集合） | 同 `null` |
| `Skill.paramPolicies` | 无策略约束（**不等于放行一切**；继续继承 Tool 级约束与所有强制安全约束） | 显式无 Skill 级附加约束，仍执行 Tool 级约束及强制策略 | 同 `null`（由 Schema 规范化为统一行为，禁止不同解析器各自解释） |
| `McpServer.dynamicDiscovery` | V1 视为 false | V1 视为 false | 同 `null`，但显式 true → 拒 |

**参数策略缺失语义（评审 §4.3，统一规则）**：
- `null`：未配置 Skill 级参数约束 → 继续继承 Tool 级约束与强制安全约束。
- `[]`：显式无 Skill 级附加约束 → 仍执行 Tool 级约束及所有强制策略。
- **缺失字段**：由 Schema 规定规范化行为，不同解析器不得各自解释。
- **未知运算符 / 非法参数类型 / 无法解析的策略**：**配置期拒绝发布**；运行期再次遇到 → 拒绝调用（fail-closed）。
- 关键：参数策略缺失**不必然**意味着所有调用拒绝，但**绝不能**被解释为绕过既有安全约束（参数策略与 JSON Schema 是两层不同校验）。

---

## 3. 权限校验时机与流程

### 3.1 三个校验时机
| 时机 | 触发 | 作用 | 复用 |
|---|---|---|---|
| **T1 配置期（静态）** | 发布/修改定义 | schema + 一致性守卫 + 闭包可达性 + 收紧校验 + fail-closed | `ConfigTighteningPolicy` + T1 守卫 |
| **T2 绑定期（Bind）** | Run 创建 | 计算有效能力闭包 + 计算**有效能力闭包指纹**（Run 依赖闭包级，非全局聚合）快照入 `execution_snapshot`（06 §1.6） | `EffectiveConfigResolver` |
| **T3 调用期（Runtime）** | 每次调用 | 沿链重验 + `authzVersion` 重授权 + 风险审批 + 预算 + 账本 + 参数策略 | `ToolInvocationGate` + `InvocationValidator` |

### 3.2 调用期校验流程（核心，对应 05 §0/§2 与 07 §2）

```
> **统一决策状态模型（强制，详见 05 §0）**：一次调用产出四个字段——`authorizationDecision`(ALLOW/DENY) · `executionDecision`(ALLOW/DENY) · `executionStatus`(NOT_STARTED/RUNNING/SUCCEEDED/FAILED/UNKNOWN) · `reasonCode`。授权失败（主体/能力链/风险/参数/指纹）记 `authorizationDecision=DENY`；审批/预算/幂等失败记 `authorizationDecision=ALLOW, executionDecision=DENY`——**二者不得混为单一 DENY**（评审 G-02）。

```
请求：Agent A 经 Skill S 调用 Tool T（或 MCP server M 的工具 Mt）

[Phase A 授权]
[1] 主体授权     AuthorizationService.isAuthorized(ctx, TOOL_INVOKE, T.id) 且 authzVersion 新鲜？
        └ 否 → authorizationDecision=DENY(AUTHZ_STALE)            # 主体最先，防未授权主体读敏感配置
[2] Agent→Skill  A.effectiveSkills 含 S ?  否则 authorizationDecision=DENY(SKILL_NOT_ENABLED)
[3] Skill→Tool   S.effectiveTools 含 T ?  且 T.riskLevel ≤ S.riskCeiling ? 否则 DENY(TOOL_NOT_DECLARED/RISK_EXCEEDS_CEILING)
[4] Skill→MCP    S.declaredMcp 含 M ? 连接级授权 + 操作级 allowlist 交集 ⊇ {Mt} ?
        └ 否 → DENY(MCP_NOT_BOUND / MCP_OP_NOT_ALLOWED)；MCP 当前未暴露 → DENY(MCP_TOOL_DRIFTED)
[5] 参数策略     paramPolicies（业务权限，仅此，**不含结构校验**）? 否则 authorizationDecision=DENY(PARAM_POLICY_FAIL)
[6] 配置指纹     snapshot.configFingerprint(有效能力闭包级) == current ? 否则 authorizationDecision=DENY(CONFIG_FINGERPRINT_STALE)   # 仅 Run 依赖闭包变化才失效（05 §5），非全局聚合
→ 全过 → authorizationDecision = ALLOW

[Phase B 执行准入]
[7] 结构校验     ToolHandler.validate（JSON Schema，**仅此一次**）→ 失败 executionDecision=DENY(PARAM_SCHEMA_FAIL)   # 单次结构校验，不与 [5] 参数策略重复
[8] 风险审批     T.riskLevel.defaultApproval：
        ├ NOT_REQUIRED → 继续
        ├ REQUIRED 且审批已满足（绑定操作+参数域+指纹+一次性消费）→ 继续
        └ REQUIRED 且未满足/缺失/重放/不可确认 → executionDecision=DENY(APPROVAL_*)  # authorizationDecision 仍 ALLOW
[9] 预算/幂等/账本 BudgetPort 预留（幂等键）+ RunToolLedgerPort 登记（PRE）
        └ 不足/冲突 → executionDecision=DENY(BUDGET_EXCEEDED/IDEMPOTENCY_CONFLICT)  # 仍 ALLOW
[10] 执行准入点 重验 authzVersion + configFingerprint 仍新鲜（TOCTOU）→ 失效 executionDecision=DENY(EXEC_ADMISSION_STALE)

[执行]
[11] 执行        ToolHandler.execute → executionStatus=SUCCEEDED/FAILED/UNKNOWN；UNKNOWN → reconcile → 对账队列
→ 返回 ExecutionResult(authorizationDecision, executionDecision, executionStatus, reasonCode)
```

- **fail-closed**：Phase A 任一步缺失/异常/超时 → `authorizationDecision=DENY`（05 §8）。
- **审批歧义修正（评审"三条细节"其一）**：[8] 仅"需要审批且未满足"才 DENY；"无需审批"继续，不默认拒绝。
- **决策日志四字段**（替代"双维度"）：每次调用记录 `authorizationDecision` / `executionDecision` / `executionStatus` / `reasonCode` + 审计字段（§6.3）。外部副作用已发生但响应丢失 → `authorizationDecision=ALLOW, executionDecision=ALLOW, executionStatus=UNKNOWN`（**非成功**）。

### 3.3 执行职责划分（评审 P0-2，详设见 07）
> 本表仅定义职责边界；**安全语义（顺序/失败归属）以 05 为准**，07 负责组件编排，不得改变。

| 步骤 | 职责归属 | 边界（禁止重复/绕过） |
|---|---|---|
| 主体当前授权（Phase A 最先） | `AuthorizationService`（实时 authzVersion，强一致读） | 禁止以快照代替实时授权；未授权主体不得先进入能力链解析 |
| 解析 Agent/Skill/Tool 关系 | `PermissionResolver`（无副作用只读） | 计算有效能力，不执行工具 |
| 能力链+配置约束判定（Phase A） | `InvocationValidator` | 仅判定，不预留预算/不写账本/不执行 |
| 结构校验/审批/预算/幂等/账本（Phase B） | 既有 `ToolInvocationGate` + Port | 不得在 `InvocationValidator` 重复执行副作用 |
| 执行准入点重验（TOCTOU） | `ToolInvocationGate` | 发送外部请求前重验版本新鲜度 |
| 执行 Native/MCP Tool | 统一 `ToolHandler` 执行链 | 禁止绕过统一入口 |

### 3.4 版本/快照/撤权语义（评审 P0-3，详设见 05 §5 / 06 §4）
- **四类版本（语义不同，不得合并）**：`agentVersion`（对象版本）/ `configVersion`（发布序号 `cap_config_release.config_publish_seq`）/ `configFingerprint`（内容摘要）/ `authzVersion`（主体授权状态，DB 单调，V1 强制）。
- 失效规则：T3 时 `authzVersion` 不匹配 → 授权 DENY(AUTHZ_STALE)；`configFingerprint`（**Run 依赖闭包级**）不匹配 → 授权 DENY(CONFIG_FINGERPRINT_STALE)；两者满足且闭包各边通过 → 进执行闸门（Phase B）。与某 Run 无关的 Tool/Skill/MCP 变更**不**使其快照失效（05 §5）。
- 撤权/在途：新调用读实时版本（陈旧即拒）；已审批待执行随版本变化失效需重审；执行中按幂等键对账，不承诺可撤销；多实例经 **DB 强一致读取**即时生效（非普通异步缓存刷新）；版本服务不可用 → fail-closed 拒绝新调用。
- 执行准入点：`authzVersion`/`configFingerprint` 在真正发外部请求前再次重验，失效则执行准入 DENY(EXEC_ADMISSION_STALE)（07 §2[10]）。

### 3.5 风险排序与审批绑定（评审 P0-4，详表见 05 §3）
- 排序：`READ(1) < WRITE_PROFILE(2) < WRITE_EXTERNAL(3)`；**未知/未登记/非法风险等级 → 配置期拒绝发布、运行期拒绝调用**（不再误转为合法最高级执行，见 05 §3.3）。
- 审批：READ→NOT_REQUIRED；WRITE_PROFILE→OPTIONAL；WRITE_EXTERNAL→REQUIRED（trustTier=public 可升级强制）。
- **审批绑定操作+参数域+指纹+一次性消费**：通过后关键输入/指纹变化 → 审批失效需重审；并发重放同一审批 → 仅一个成功消费（防 05 §3.2 重放）。

---

## 4. 配置持久化与发布生命周期（评审 P0-5，详设见 06）

- **持久化**：`cap_tool_def` / `cap_mcp_server_def` / `cap_skill_def` / `cap_agent_def` / `cap_authz_version` / `execution_capability_snapshot`（06 §1）。
- **唯一性**：cap_* `id` 全局唯一；引用 FK 或 T1 校验（Skill→Tool、Skill→McpServer）。
- **生命周期**：DRAFT→VALIDATED→PUBLISHED（双人复核）→rollback/deactivate；被引用对象禁止物理删除→DEPRECATED（06 §3）。
- **生效**：新 Run 读当前版本/指纹生成快照；已有 Run 按快照版本，运行期失效则新调用 DENY。
- **迁移**：自由 JSONB `agent.definition` → 结构化 `cap_agent_def`（抽取字段，旧 definition 只读归档）。
- **租户级**（V2 开放）：加 `tenant_id` 受 RLS，仅引用 platform 或本租户对象。

---

## 5. 扩展性设计与 V1/V2 边界（评审 P0-1，冻结见 04）

> **V1 与 V2 边界以 04 为准**。下列能力归属：

| 能力 | 归属 | V1 处理 |
|---|---|---|
| Agent 显式启用 Skill | **V1** | 必须实现 |
| Skill 显式引用 Native Tool | **V1** | 必须实现 |
| 基础 MCP 接入 + 操作级 allowlist（固定清单+指纹） | **V1（必做，上线前置）** | 最小能力，无动态发现；连接级授权/操作级 allowlist/指纹校验/MCP SSRF 安全测试均为 V1 验收门槛（04 A3 / 08 §6） |
| DB authzVersion（多实例安全） | **V1 强制**（评审 P2 前移） | 必须实现 |
| 嵌套 Skill / inherits | V2 | V1 写入即拒 |
| Capability Tag / Skill Bundle / Agent Profile | V2 | V1 仅显式 ID |
| 通用参数策略引擎 | V2 | V1 仅两点手写判定 |
| 动态 MCP 工具发现 | V2 | V1 固定清单+指纹 |
| 策略即代码（OPA） | V3 | — |
| 跨 Agent 委托（A2A） | V3 | — |

---

## 6. 非功能需求

### 6.1 性能（评审 P1-1，拆分指标）
- **本地权限判定**：`InvocationValidator` 单跳（闭包查表 + 版本比对 + 本地策略）= **p99 < 1ms**（08 §4）。
- **完整工具调用**：含授权+审批+预算+网络+执行的端到端延迟，另行设定目标（不与本地判定混用）。
- **MCP 工具发现（V2）**：规定缓存 TTL / 刷新 / 失效窗口 / 发现失败行为（失败→拒）。
- **配置发布→生效**：`authzVersion`/`configVersion` 经 DB 强一致读取；闭包计算结果可缓存但命中须重验版本新鲜度，旧缓存不得用于跳过版本校验（07 §4）。

### 6.2 安全
- **fail-closed 全局**：缺配置/缺授权/异常/超时/列举失败 → 拒（05 §8）。
- **租户隔离**：所有配置查询带 RLS `tenant_id`；Agent 不可跨租户引用。
- **不可反转依赖**：Tool/MCP 不感知上层；禁 Spring AI 自动执行。
- **收紧棘轮**：覆盖只能收紧。
- **撤销即时生效**：DB `authzVersion` 单调递增，所有实例经**强一致读取**（或具强一致保证的授权服务）读取，撤权事务提交后新调用即失效；**不依赖普通异步缓存刷新**（04 §1.1 / 06 §1.5）。
- **双人复核**：高危配置变更 `approver_id <> requester_id`。

### 6.3 可观测性（评审 P1-4，审计字段）
- **决策日志**（每次调用，字段完整率 100%）：`runId` / `invocationId` / `tenantId` / `principalId` / `agentId` / `skillId` / `toolId`(或 mcp 工具) / `authorizationDecision` / `executionDecision` / `executionStatus` / `reasonCode` / `authzVersion` / `configVersion` / `configFingerprint` / `approvalId`(关联) / `latencyMs`。
- **敏感数据**：参数中的凭据/PII、MCP 返回内容、**不**无条件写入审计；脱敏或仅记哈希（06 §5 / NFR-06）。
- **异常分类**：按 `reasonCode` 区分"授权拒绝/审批拒绝/预算不足/执行失败/执行结果未知"，不得笼统 DENY（05 §8）。
- **指标**：deny 计数（按 stage）、闭包大小分布、MCP 列举失败率（V2）、收紧覆盖数、决策延迟。
- **能力图谱**：可导出每 Agent 有效能力闭包，用于审计与越界扫描。

---

## 7. 演进路线（与 04 一致）

| 版本 | 范围 | 关键交付 |
|---|---|---|
| **V1（本期，冻结）** | 静态定义 + 分层禁闭 + 运行期重验 + 收紧覆盖 + **DB authzVersion** + 基础 MCP（固定清单，必做） | cap_* 表；`PermissionResolver`；`InvocationValidator`；复用 `ToolInvocationGate`/`EffectiveConfigResolver`；决策日志四字段；S1–S8 + S3a–S3e 验收（08） |
| **V2** | 嵌套 Skill + capability tag + 动态 MCP + 参数策略引擎 | transitive enablement；Skill Bundle；MCP 运行期交集；`paramPolicies` 引擎 |
| **V3** | 策略即代码 + 跨 Agent 委托 + 多租户策略模板 | OPA/Rego；A2A 委托闭包；租户策略市场 |

---

## 8. 关键设计取舍（Summary of Trade-offs）

1. **分层禁闭 + 三段校验**：配置期/绑定期/调用期，牺牲少量调用开销杜绝越权（红线 9 延伸）。
2. **MCP 连接级/操作级分离 + V1 固定清单指纹**：解决动态漂移，V1 不引入运行期发现复杂度（P2）。
3. **引用 + 收紧棘轮**：抗组合爆炸/冗余，但 V1 不用标签（防过宽），仅显式 ID。
4. **复用 `ToolHandler` 统一接口**：不另建执行通道，保证 ADR-011 不被绕过；MCP-backed 需一层适配。
5. **DB authzVersion 前移 V1**：多实例安全必要条件（评审 P2），成本是可单调版本存储。
6. **决策四字段模型 + null≠空**：明确语义，避免笼统 DENY 与误放行。

→ 修订版 PRD 已闭合 P0/P1；进入阶段四：Demo 对齐 V1 范围并验证 8 端到端场景（08）。
