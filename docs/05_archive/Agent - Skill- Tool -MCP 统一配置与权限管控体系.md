# Agent / Skill / Tool / MCP 统一配置与权限管控体系  
## 完整分析与设计文档（V1 基线 · 第四轮修订定稿）

> **文档定位**：综合 01–09 全部结论，并闭合历次 Review 的 P0/P1，形成可评审、可独立实现、可客观验收的单一入口。  
> **规则优先级**：安全决策语义以 **§5** 为唯一契约；V1 范围与冻结门槛以 **§8** 为准；§6/§7 只提供实现机制，不得改写 §5；§9 为验收证据映射。  
> **Review 状态**：总体架构通过；设计基线有条件通过（本版已闭合审批绑定、执行意图、结果持久化、MCP 判定顺序、发布 CAS、四字段与原因码）；**V1 冻结仍取决于平台实现与自动化/故障注入证据**，不以文档勾选代替。  
> **修订轮次**：在第三轮（审批指纹、CAS、双维度状态、MCP null 规范化）基础上，第四轮闭合：参数双哈希、执行意图提交边界、确定结果可恢复持久化、MCP 新鲜度判定顺序、成功码与审批分支码唯一化。

---

## 0. 一句话结论

在现有 Tool + Permission + Config 地基上，引入 **Agent → Skill → {Tool, MCP} 单向能力禁闭**，以「配置期静态校验 → 绑定期闭包快照 → 调用期逐跳重验 + DB 强一致 authzVersion + 执行准入点线性化 + 执行意图持久化 + 确定结果可恢复落账」实现运行期纵深防御。V1 必须交付：基础 MCP（http/sse、固定清单+指纹）、DB 化 authzVersion、原子发布（CAS 当前指针）、审批双哈希与状态机、决策四字段 + ledger 维度、完整故障恢复与客观验收矩阵。Demo 仅证明设计可行，不构成任何验收。

---

## 1. 现状分析（代码事实）

### 1.1 四层成熟度

| 层 | 现状 | 关键落点 | V1 动作 |
|---|---|---|---|
| **Agent** | 仅配置/发布实体，`definition` 自由 JSONB | `agent` / `agent_version` / `execution_snapshot` | 结构化 `cap_agent_def`，声明 `enabledSkills` |
| **Skill** | 全仓缺失 | — | 新增定义与引用 |
| **Tool** | SPI + Bean + 风险分级 + 唯一闸门最成熟 | `ToolHandler`、`ToolInvocationGate`（ADR-011） | 复用；MCP-backed 同一接口 |
| **MCP** | 全仓缺失 | — | 固定清单 + 指纹 + 操作级 allowlist；传输仅 http/sse |
| **Permission** | JWT + 角色矩阵 + 执行时重授权（内存） | `AuthorizationService`、RLS 17 表 | 换 DB `cap_authz_version`，强一致读 |
| **Config** | yml ⊕ DB 覆盖 + 收紧棘轮 + 四步审批 | `EffectiveConfigResolver`、`ConfigTighteningPolicy` | 纳入 cap_* + 指纹 |

### 1.2 现有执行链（事实，非目标）

```
Schema → 执行时重授权 → 幂等键 → 账本登记 → 审批 → 预算 → 执行 → 账本更新
```

目标顺序由 **§5.3 唯一协议** 规定；§7 仅编排组件，不得另写顺序。

### 1.3 复用与改造

- **复用**：`ToolHandler`、`ToolInvocationGate`、`AuthorizationService` 接口、`EffectiveConfigResolver`、`ConfigTighteningPolicy`、RLS、审批/预算/账本 Port。  
- **改造**：authzVersion 内存→DB；快照扩展闭包+四版本；新增 `PermissionResolver` / `InvocationValidator` / MCP 适配；结果事件可恢复存储。

---

## 2. 设计评估与威胁模型

### 2.1 能力禁闭

```
Agent ──(限定可用)──▶ Skill ──(声明可用)──▶ { Tool, MCP }
有效权限 = 沿链逐层取交集闭包
```

### 2.2 威胁与 V1 缓解

| 威胁 | V1 缓解 |
|---|---|
| P1 配置期校验→越权 | 每次调用重验 + DB authzVersion + 准入点线性化 |
| P2 MCP 漂移 | 固定清单 + 操作级 allowlist + 指纹 + 判定顺序 |
| P3 嵌套抬权 | V1 禁止 `inherits` |
| P4 高危透传 | Skill `riskCeiling` |
| G1 粒度耦合 | 连接级 + 操作级分离 |
| G2 参数越权 | Tool/Skill 参数策略（仅收紧）+ 强制安全不可放宽 |
| 审批参数替换重放 | approvalScopeHash + effectiveArgsHash |
| 租户越权 | RLS + 认证上下文唯一来源 |
| 发布竞态 | 原子事务 + 当前指针 CAS |
| 副作用不确定 / 崩溃窗口 | 执行意图持久化 + 结果事件 + UNKNOWN 对账；不支持幂等的 Tool 禁止自动重试 |

### 2.3 三条铁律

1. 每一跳运行期重验。  
2. MCP = 连接级 + 操作级，规范化后再交集。  
3. 配置 = 引用 + 收紧棘轮 + 租户覆盖。

---

## 3. 目标与非目标

### 3.1 Goals

- G1 统一建模：Agent/Skill/Tool/MCP 声明式 + 单一事实源。  
- G2 分层能力禁闭：有效权限 = 闭包交集。  
- G3 运行期纵深：T1 / T2 / T3 + authzVersion + 准入线性化 + 执行意图。  
- G4 复用地基：MCP-backed 与 Native 统一走 `ToolInvocationGate`。  
- G5 可审计：决策字段完整 + 稳定原因码 + 可恢复证据。

### 3.2 V1 Explicit Non-Goals

| 编号 | 能力 | 归属 |
|---|---|---|
| N1 | 嵌套 Skill / `inherits` | V2 |
| N2 | Capability Tag / Skill Bundle / Agent Profile | V2 |
| N3 | 通用参数策略引擎 | V2（V1 仅 `in_allowlist` / `lte`） |
| N4 | 动态 MCP 工具发现 | V2 |
| N5 | 策略即代码（OPA） | V3 |
| N6 | 跨 Agent 委托（A2A） | V3 |
| N7 | stdio MCP 传输 | 非 V1 必做 |

---

## 4. 核心概念与配置模型

### 4.1 依赖方向（不可反转）

```
ToolDefinition ─┐
                ├─▶ SkillDefinition ─▶ AgentDefinition
McpServerDef ───┘
```

唯一执行入口：所有 Native/MCP 必须经 `ToolInvocationGate`（ADR-011）。

### 4.2 关键字段（V1）

**Tool**  
- `kind=native|mcp`；`riskLevel` 必填（未知/非法 → 配置期拒发布、运行期 `RISK_LEVEL_UNKNOWN`）。  
- `paramPolicies`：仅 `in_allowlist` / `lte`。  
- `effectfulParams`（可选元数据）：声明进入 `effectiveArgsHash` 的参数名；未声明则默认全部入参。  
- `atMostOnceRecovery`：`auto` | `manual`（不支持幂等且不可查询时为 `manual`）。

**Skill**  
- `declaredTools` / `declaredMcp[{serverId, toolAllowlist?}]`；`riskCeiling`。  
- `paramPolicies`：可选，**仅可进一步收紧** Tool 策略与强制安全。  
- `inherits`：写入即拒。

**McpServer**  
- 传输：**仅 `http` / `sse`**。  
- `dynamicDiscovery=false`；`exposedToolAllowlist` 的 `null`≠`[]`。  
- endpoint 防 SSRF；凭据仅 `credential_ref`（永不入指纹/日志）。

**Agent**  
- `enabledSkills` 唯一启用途径；`roles` 仅调用资格，非授权授予；`overrides` 仅收紧。

### 4.3 参数策略层级与合并

| 层级 | 定义 | 可否放宽 |
|---|---|---|
| 强制安全约束 | 平台硬规则（风险未知拒绝、SSRF、fail-closed 等） | 不可覆盖 |
| Tool 参数策略 | 工具自身基础约束 | 不可被 Skill 放宽 |
| Skill 参数策略 | 仅可进一步收紧 | 不可放宽 Tool 或强制安全 |

合并：同参数多策略取更严交集（AND）。`in_allowlist` 与 `lte` 同时作用于同一参数时均须满足；类型不符、缺失且策略要求该参数 → 拒。未知 op → 配置期拒。

**null vs []（paramPolicies）**  
- `null`：未配置该层附加策略 → 继续下层 + 强制安全。  
- `[]`：显式无该层附加 → 仍执行下层与强制安全。  
对 allowlist 字段：`null`≠`[]`（见 §4.4）。

### 4.4 MCP 可调用操作（规范化后再交集）

**禁止**对未规范化的 `null` 直接做集合运算。

| 输入 | 规范化结果 |
|---|---|
| `Server.exposedToolAllowlist = null` | `ServerAllowedOps := ManifestOps` |
| `Server.exposedToolAllowlist = []` | `ServerAllowedOps := ∅` |
| `Server.exposedToolAllowlist = L` | `ServerAllowedOps := L ∩ ManifestOps` |
| `Skill.toolAllowlist = null` | `SkillAllowedOps := ServerAllowedOps` |
| `Skill.toolAllowlist = []` | `SkillAllowedOps := ∅` |
| `Skill.toolAllowlist = L` | `SkillAllowedOps := L ∩ ServerAllowedOps` |

- **ManifestOps**：当前已发布、经校验的固定 MCP 工具清单。  
- **ClosureOps**：本 Run 快照中该边允许的操作集（T2 固化）。  
- **ForcedSafety**：逐操作安全谓词（riskCeiling、trustTier、endpoint 等），非集合。

\[
AllowedOps = \{ op \in (SkillAllowedOps \cap ClosureOps) \mid ForcedSafety(op) = true \}
\]

运行期判定顺序见 **§5.8**。旧 Run 不得将逻辑 id 静默解析到未绑定的新版本。

---

## 5. 安全决策规范（唯一契约）

### 5.1 统一决策状态模型

| 字段 | 取值 | 含义 |
|---|---|---|
| `authorizationDecision` | ALLOW / DENY | Phase A |
| `executionDecision` | ALLOW / DENY / **NOT_EVALUATED** | Phase B；未进入 Phase B 时为 NOT_EVALUATED |
| `executionStatus` | NOT_STARTED / RUNNING / SUCCEEDED / FAILED / UNKNOWN | 外部副作用结果是否已知 |
| `ledgerPersistenceStatus` | PENDING / RECORDED / RETRYING / NEEDS_MANUAL | 终态是否已可靠写入账本 |
| `reasonCode` | 见 §5.7 | **成功固定为 `SUCCESS`** |

**规则**  
- 授权失败：`authorizationDecision=DENY`，`executionDecision=NOT_EVALUATED`，`executionStatus=NOT_STARTED`。  
- 审批/预算等失败：`authorizationDecision=ALLOW`，`executionDecision=DENY`，`executionStatus=NOT_STARTED`。  
- `UNKNOWN` 仅表示外部副作用结果不确定，不得与 NOT_STARTED / FAILED 混用；账本写入失败不得单独推导为 UNKNOWN。

### 5.2 授权公式（Phase A）

\[
Allow_{auth} = PrincipalAuthorized \land AgentSkillAllowed \land SkillToolAllowed \land RiskWithinCeiling \land MCPBindingAllowed \land ParamPolicyPassed
\]

`ParamPolicyPassed` = 强制安全 ∧ Tool 策略 ∧ Skill 策略（§4.3）。  
主体授权必须最先；不适用因子（native 的 MCP）显式跳过。

### 5.3 唯一调用协议

本小节为全文档唯一顺序与语义；§7 只引用。

```
[Phase A — 授权]
1. 构建可信上下文（身份/租户/Run/目标仅来自 TenantContext）
2. PrincipalAuthorized：角色 + 权威源读取 authzVersion（强一致）
3. PermissionResolver → EffectiveClosure（仅在 2 通过后使用）
4. AgentSkillAllowed → SkillToolAllowed → RiskWithinCeiling
5. MCPBindingAllowed（仅 MCP；判定顺序见 §5.8）
6. ParamPolicyPassed（业务权限，非 Schema）
7. ConfigFingerprintValid（本 Run 依赖闭包指纹）
→ 全过：authorizationDecision=ALLOW
→ 失败：DENY + NOT_EVALUATED + NOT_STARTED + 对应 reasonCode；写决策日志；返回

[Phase B — 执行准入]
8.  幂等键校验（IdempotencyOk）
    - 已完成 → 返回首次结果（含首次 reasonCode）
    - 进行中 → 按契约阻塞或返回进行中
    - 同键不同参数 → IDEMPOTENCY_CONFLICT
    - 幂等存储不可用 → fail-closed
9.  SchemaValid：ToolHandler.validate（结构，仅一次）
    - 失败：DENY(PARAM_SCHEMA_FAIL)；不预占审批、不预留预算、不写 PRE
10. Approval：校验绑定项（§5.3.1）→ PENDING→RESERVED（预占，绑定 invocationId）
11. BudgetOk：带幂等键预留；失败 → cancel 审批、DENY(BUDGET_EXCEEDED)
12. LedgerOk(PRE)：「已登记未完成」；失败 → cancel 审批、释放预算；禁止绕过账本发请求
13. ExecutionAdmissionCheck（TOCTOU，§5.5）：权威源重验 authzVersion + 闭包指纹
    - 失效：DENY(EXEC_ADMISSION_STALE)；cancel 审批、释放预算、PRE 标取消
13a. 持久化执行意图（executionIntent / 账本 INTENT）—— 必须先于外部调用
13b. 审批 RESERVED → INTENT_SUBMITTED
14. ToolHandler.execute
14a. 将确定结果写入可恢复存储（结果事件表或可查询下游），再更新 executionStatus
15. Ledger POST；成功则 INTENT_SUBMITTED→CONSUMED，ledger=RECORDED
```

#### 5.3.1 审批绑定契约

| 绑定项 | 必选 | 含义 |
|---|---|---|
| tenantId / principalId | 是 | 主体与租户 |
| toolId / mcp 操作身份 | 是 | 目标操作 |
| **approvalScopeHash** | 是 | 审批允许的操作范围与参数约束的规范化摘要 |
| **effectiveArgsHash** | 是（消费时生成并校验） | 本次全部有效参数的规范化摘要 |
| closureFingerprint | 是 | 本 Run 依赖闭包指纹（不默认绑定全局 configVersion） |
| authzVersion | 是 | 生成时的主体授权版本 |
| approvalId / 有效期 / 状态 | 是 | 防重放与预占 |

- **approvalScopeHash**：审批创建时由范围定义生成；消费时用范围定义**重新验证**实际参数是否落入范围。  
- **effectiveArgsHash**：覆盖所有可能影响结果或副作用的有效参数（`effectfulParams` 或默认全部）；防止替换参数重放。  
- 范围型审批：记录保存完整范围 JSON + hash；消费时范围重验，失败 → `APPROVAL_SCOPE_MISMATCH`。  
- **禁止**因「参数未出现在某条 paramPolicy」而默认允许其在复用时变化。

#### 5.3.2 参数规范化算法（`args_norm_v1`）

- 字段字典序；有效参数集见上。  
- 字符串 UTF-8 NFC；数字十进制规范形式；布尔 `true`/`false`。  
- 数组：默认保持调用序；元数据声明 `set` 则字典序排序。  
- 缺失字段与显式 `null` 必须区分编码。  
- Schema default 先展开再哈希。  
- SHA-256，小写 hex。

#### 5.3.3 审批状态机

```
PENDING → RESERVED（预占，绑定 invocationId）
       → INTENT_SUBMITTED（执行意图已持久化）
       → CONSUMED（与终态结果关联后确认）
可选：EXPIRED / CANCELLED
```

- **RESERVED**：步骤 10，原子预占。  
- **INTENT_SUBMITTED**：步骤 13b，执行意图已持久化之后。  
- **CONSUMED**：步骤 15 落账成功后与结果关联。  
- 补偿：预算/PRE/准入失败 → CANCELLED（幂等）；补偿失败 → 持久化补偿任务。

#### 5.3.4 阶段、持久化事实与恢复

| 阶段 | 必须持久化的事实 | 故障恢复 |
|---|---|---|
| 审批预占 | approvalId ↔ invocationId | 同 invocation 可恢复；他调用不可抢占 |
| 准入完成 | 版本/指纹/预算/PRE/审批 RESERVED 均满足 | 不满足则禁止外部执行 |
| 执行意图提交 | 唯一 executionIntentId，标记即将外部执行 | 恢复先查意图/下游幂等/账本；不得盲目重试 |
| 外部执行 | 是否已发出、是否得到确定结果 | 确定结果先持久化（14a）；不确定 → UNKNOWN |
| 最终落账 | executionStatus + 结果载荷 + ledger 状态 | 只重试落账，禁止再次 execute |

**崩溃窗口**：

| 崩溃点 | 恢复策略 |
|---|---|
| 准入成功，意图未写入 | 无意图 → 可重新准入；审批仍 RESERVED |
| 意图已写，execute 未发出 | 查意图 + 无下游痕迹 → 默认禁止自动重发（除非 Tool 声明可安全幂等重试） |
| 请求已发出，响应丢失 | 审批为 INTENT_SUBMITTED/CONSUMED；status=UNKNOWN；对账 |
| execute 成功，结果未持久化即崩溃 | 强制 14a；若仅内存结果则恢复后可能 UNKNOWN；故结果事件必须先落盘 |

**工程边界**：Tool 不支持幂等且不可查询结果时，标记 `atMostOnceRecovery=manual`；请求已发出后崩溃 → 禁止自动重试，对账/人工介入。

#### 5.3.5 跨系统恢复表（摘要）

| 故障点 | 审批 | 预算 | 账本 | 结果事件 | 处理 |
|---|---|---|---|---|---|
| 审批 reserve 失败 | PENDING | — | — | — | DENY(APPROVAL_*) |
| 预算失败 | CANCELLED | 释放 | — | — | DENY(BUDGET_EXCEEDED) |
| PRE 失败 | CANCELLED | 释放 | 无 PRE | — | 拒绝执行；补偿入队 |
| 准入失败 | CANCELLED | 释放 | PRE 取消 | — | DENY(EXEC_ADMISSION_STALE) |
| 响应丢失 | INTENT_SUBMITTED/CONSUMED | 按对账 | 待定 | 无/不确定 | UNKNOWN + 对账 |
| 确定成功，POST 失败 | INTENT_SUBMITTED→重试后 CONSUMED | 已结算 | RETRYING | **已持久化** | 只重试落账，不重新 execute |
| 同幂等键重试 | 按状态恢复 | 按状态 | 按状态 | 按状态 | 禁止重复副作用 |

### 5.4 四类版本

| 概念 | 存储 | 失效触发 |
|---|---|---|
| agentVersion | 对象版本行 | Agent 重发 |
| configVersion | `cap_config_release.config_publish_seq` | 新发布 |
| configFingerprint | Run 依赖闭包内容摘要 | **仅本 Run 依赖对象**内容变化 |
| authzVersion | `cap_authz_version` DB 单调 | 角色/授权关系/主体禁用等 |

Phase A 与准入均与**权威当前** authzVersion 比较；变化 → 该次调用失败（不静默沿用旧判定）。

### 5.5 撤权与执行准入线性化

**方案 A（准入点线性化）**：

1. 线性化边界 = 步骤 13 准入检查**成功通过**的时刻。  
2. 撤权在边界前提交 → 必须读到新版本 → `EXEC_ADMISSION_STALE`，阻断新副作用。  
3. 撤权在边界后提交 → 在途操作不承诺取消；记录 executionStatus 并对账。  
4. 每次准入记录：时间、读到的版本/指纹、Run/invocation、结果 → 写入决策日志/账本。

**authzVersion 事务**：  
- 所有改变授权结果的变更与版本递增同一事务；DB 原子自增；初始值 1。  
- 读权威主库或具备线性一致/读后写可见性的源；禁止滞后缓存充当强一致。  
- 缺失/超时/非法 → fail-closed（`AUTHZ_UNAVAILABLE` 等）。  
- 审批记录保存生成时 authzVersion；消费时当前 > 记录 → 失效。

### 5.6 风险与审批

- `READ < WRITE_PROFILE < WRITE_EXTERNAL`；未知 → 拒绝。  
- 审批绑定以 §5.3.1 为准（范围哈希 + 有效参数哈希 + 闭包指纹 + authzVersion + 一次性状态）。

### 5.7 稳定原因码表（权威）

| reasonCode | 阶段 | 含义 | 可重试 | 进对账 |
|---|---|---|---|---|
| SUCCESS | — | 成功 | — | 否 |
| AUTHZ_STALE | A | 授权版本陈旧 | 否 | 否 |
| AUTHZ_ROLE_MISSING | A | 角色不足 | 否 | 否 |
| AUTHZ_UNAVAILABLE | A/准入 | 版本服务不可用 | 视运维 | 否 |
| SKILL_NOT_ENABLED | A | Skill 未启用 | 否 | 否 |
| TOOL_NOT_DECLARED | A | Tool 未声明 | 否 | 否 |
| RISK_EXCEEDS_CEILING | A | 风险超限 | 否 | 否 |
| RISK_LEVEL_UNKNOWN | A | 风险未知/非法 | 否 | 否 |
| PARAM_POLICY_FAIL | A | 参数策略失败 | 否 | 否 |
| CONFIG_FINGERPRINT_STALE | A | 闭包指纹失效 | 否 | 否 |
| MCP_NOT_BOUND | A | 未声明 MCP server | 否 | 否 |
| MCP_OP_NOT_ALLOWED | A | 操作不在允许集 | 否 | 否 |
| MCP_TOOL_DRIFTED | A | 当前清单与快照不一致/下线 | 否 | 否 |
| RESOURCE_VERSION_MISMATCH | A | 逻辑 id 解析到未绑定版本 | 否 | 否 |
| RESOURCE_UNKNOWN | A | 对象不存在 | 否 | 否 |
| PARAM_SCHEMA_FAIL | B | 结构校验失败 | 否 | 否 |
| APPROVAL_NOT_FOUND | B | 审批不存在/无效 | 否 | 否 |
| APPROVAL_EXPIRED | B | 审批过期 | 否 | 否 |
| APPROVAL_SCOPE_MISMATCH | B | 参数不在审批范围/绑定不匹配 | 否 | 否 |
| APPROVAL_RESERVED_BY_OTHER | B | 已被其他调用预占 | 否 | 否 |
| APPROVAL_REPLAY_REJECTED | B | 已消费后重放 | 否 | 否 |
| BUDGET_EXCEEDED | B | 预算不足 | 视业务 | 否 |
| IDEMPOTENCY_CONFLICT | B | 同键冲突 | 否 | 否 |
| EXEC_ADMISSION_STALE | 准入 | 准入点版本/指纹失效 | 否 | 否 |
| EXEC_UNKNOWN | 执行后 | 副作用结果不确定 | 否 | **是** |

变更须版本化并保持兼容策略。本表为对外稳定码权威源。

### 5.8 MCP 新鲜度与操作级校验顺序

1. 闭包指纹新鲜度 → 过期：`CONFIG_FINGERPRINT_STALE`  
2. 请求 op ∈ 快照 AllowedOps/ClosureOps → 否：`MCP_OP_NOT_ALLOWED`  
3. 请求 op 仍 ∈ 当前 ManifestOps → 否：`MCP_TOOL_DRIFTED`（若清单变更已纳入指纹导致步骤 1 失败，则统一为 `CONFIG_FINGERPRINT_STALE`；**同一类变化不得两种码**）  
4. 逻辑 id 映射到未绑定新版本 → `RESOURCE_VERSION_MISMATCH` 或 `CONFIG_FINGERPRINT_STALE`

---

## 6. 数据模型与生命周期

### 6.1 核心表

- `cap_*_def`：`(id, version)` 不可变版本行。  
- `cap_agent_def`：`(agent_id, tenant_id, version)` + RLS。  
- `cap_authz_version`：PK `(tenant_id, principal_id)`，`authz_version bigint` 单调，原子自增。  
- `execution_capability_snapshot`：闭包 + 四版本字段。  
- `cap_config_release`：发布头 + `object_versions`。  
- 结果事件表（或等价）：invocationId、确定结果、终态事件 id，供 POST 重试与崩溃恢复。  
- 执行意图记录：与 PRE/INTENT 合并或独立，可恢复。

**租户边界（V1）**：`cap_tool_def` / `cap_skill_def` / `cap_mcp_server_def` 为 platform；agent、快照、审批、账本、补偿任务、结果事件按 tenant RLS。

### 6.2 引用与快照职责

| 层 | 存什么 | 职责 |
|---|---|---|
| 对象定义引用 | 仅逻辑 id | 简洁 |
| 当前 PUBLISHED release | object_versions | 解析 id→精确版本 |
| Run 快照 | 固定版本集 + 闭包指纹 | 历史可追溯；执行期不得再解析为任意最新版 |

### 6.3 原子发布与并发控制

**成功条件（唯一）**：版本行插入 + release 记录 + **当前指针 CAS** 均在同一事务内成功提交。

1. 作用域：平台级全局唯一当前指针；租户级（若有）按 tenant。  
2. `config_publish_seq`：序列保证唯一；**当前版本不以 max(seq) 选择**。  
3. CAS：基于 expected 基线更新指针；失败 → 整事务回滚，无半成品当前发布。  
4. 同基线并发：仅一次 CAS 成功；失败方变基后重试。  
5. 读路径：只读已提交指针指向的 PUBLISHED release。  
6. 回滚：保留原 release 不变；新建回滚发布记录 + CAS 切指针；不恢复 authzVersion/旧审批。  
7. ROLLED_BACK 等仅作审计旁路，不参与当前选择。

### 6.4 闭包指纹

包含：本 Run 依赖的 Agent/Skill/Tool/MCP 的 `(id,version)`、riskCeiling、allowlist、paramPolicies、MCP 固定清单、ForcedSafety 策略版本摘要等；规范化后 SHA-256。缺失对象 → 发布校验失败。无关对象变更不触发本 Run 失效。凭据材料永不入指纹。

### 6.5 MCP 安全（http/sse）

DNS 解析后 IP 校验、防 rebinding、禁止重定向至内网/元数据、凭据轮换后连接级重验、超时/限流/熔断、输出不可信。stdio 非 V1 必做。

### 6.6 生命周期

```
DRAFT → VALIDATED → PUBLISHED → DEPRECATED
         ↑ 可修订      │ 新版本 / 回滚发布
```

未发布不可被 Agent 启用。校验失败保持可修订，不静默进 PUBLISHED。

---

## 7. 执行链集成

### 7.1 组件职责

| 组件 | 职责 |
|---|---|
| AuthorizationService | 主体授权 + authzVersion（最先，DB 强一致） |
| PermissionResolver | 无副作用闭包计算 |
| InvocationValidator | Phase A 能力链 + 配置约束 |
| ToolInvocationGate | 唯一入口，调度 §5.3 |
| Approval / Budget / Ledger Port | Phase B；审批状态机与补偿 |
| 结果事件存储 | 确定结果可恢复持久化 |
| ToolHandler | 原子执行（含 MCP 包装） |

### 7.2 编排

严格按 **§5.3**；防绕过：静态扫描 + 运行时注入 + 包边界，0 通过。

### 7.3 缓存

缓存键可含版本/指纹；**命中后仍须按 §5 做权威新鲜度校验**。缓存不得绕过权威校验。全局序号变化可致未命中，但不得将「仅全局序号变、闭包未变」自动视为授权失败。

### 7.4 双维度结果与对账

- executionStatus：副作用结果是否已知。  
- ledgerPersistenceStatus：终态是否已可靠落账。  
- 确定成功但 POST 失败 → status=SUCCEEDED，ledger=RETRYING；结果事件已存在则只重试落账。  
- UNKNOWN 仅副作用不确定；对账与人工确认对同一 invocation 串行化，仅一次终态。

---

## 8. V1 范围与冻结门槛

### 8.1 Must-Implement

A1 Agent 显式启用 Skill；A2 Skill 显式引用 Native Tool；A3 基础 MCP（http/sse、固定清单、操作级 allowlist、指纹）；A4 三段校验 + authzVersion；A5 riskCeiling；A6 收紧覆盖；A7 决策字段与 SUCCESS 原因码；A8 复用既有闸门；A9 fail-closed；以及本文件规定的审批双哈希、执行意图、结果事件、CAS 发布。

### 8.2 性能指标边界

| 指标 | 边界 |
|---|---|
| 本地判定 | 已获可信版本与快照后的纯内存计算；目标由压测标定 |
| 授权链 | 含权威读 + 闭包 + 准入；单独设定 |
| 端到端 | 含网络与执行；另行设定 |

### 8.3 冻结门槛

- 功能：§9 行为测试四字段 + 原因码全绿（含 S3*、S4a/b/c、S5、S6 具体码、S8、S9）。  
- 安全：RLS、防绕过 0、审批一次性与范围绑定、MCP 具名安全用例、fail-closed。  
- 工程：迁移双写可回退、发布 CAS/回滚、审计完整、COMP-6 / PUB-1 等故障注入与不变量通过。  
- **Demo ≠ 验收**。

### 8.4 迁移

建表 → 迁移报告 → 幂等双写 → 只读比对 → 切读 → 稳定期 → 停旧写。失败切回旧读路径；权威源与不可回退边界文档化。

---

## 9. 安全测试与验收矩阵

### 9.1 行为测试（完整字段）

| # | 场景 | authz | exec | status | reasonCode |
|---|---|---|---|---|---|
| S1 | 已授权成功 | ALLOW | ALLOW | SUCCEEDED | SUCCESS |
| S2 | 未启用 Skill | DENY | NOT_EVALUATED | NOT_STARTED | SKILL_NOT_ENABLED |
| S3 | 主体撤权 | DENY | NOT_EVALUATED | NOT_STARTED | AUTHZ_STALE |
| S3a | 跨实例撤权后调用 | DENY | NOT_EVALUATED | NOT_STARTED | AUTHZ_STALE |
| S3b | 撤权先于准入 | ALLOW | DENY | NOT_STARTED | EXEC_ADMISSION_STALE |
| S3d | 版本服务不可用 | DENY | NOT_EVALUATED | NOT_STARTED | AUTHZ_UNAVAILABLE |
| S3f | 准入后撤权 | ALLOW | ALLOW | SUCCEEDED/FAILED/UNKNOWN | SUCCESS / 稳定失败码 / EXEC_UNKNOWN |
| S4a | Skill 从 enabled 移除 | DENY | NOT_EVALUATED | NOT_STARTED | SKILL_NOT_ENABLED |
| S4b | riskCeiling 收紧 | DENY | NOT_EVALUATED | NOT_STARTED | RISK_EXCEEDS_CEILING |
| S4c | 参数策略收紧 | DENY | NOT_EVALUATED | NOT_STARTED | PARAM_POLICY_FAIL |
| S5a | 闭包指纹变化 | DENY | NOT_EVALUATED | NOT_STARTED | CONFIG_FINGERPRINT_STALE |
| S5b | 操作级越界 | DENY | NOT_EVALUATED | NOT_STARTED | MCP_OP_NOT_ALLOWED |
| S5c | 清单下线/漂移 | DENY | NOT_EVALUATED | NOT_STARTED | MCP_TOOL_DRIFTED 或 CONFIG_FINGERPRINT_STALE（按 §5.8） |
| S6a | 审批不存在 | ALLOW | DENY | NOT_STARTED | APPROVAL_NOT_FOUND |
| S6b | 审批过期 | ALLOW | DENY | NOT_STARTED | APPROVAL_EXPIRED |
| S6c | 范围/绑定不匹配 | ALLOW | DENY | NOT_STARTED | APPROVAL_SCOPE_MISMATCH |
| S6d | 被他调用预占 | ALLOW | DENY | NOT_STARTED | APPROVAL_RESERVED_BY_OTHER |
| S6e | 已消费重放 | ALLOW | DENY | NOT_STARTED | APPROVAL_REPLAY_REJECTED |
| S7 | 预算不足 | ALLOW | DENY | NOT_STARTED | BUDGET_EXCEEDED |
| S8 | 响应丢失 | ALLOW | ALLOW | UNKNOWN | EXEC_UNKNOWN |
| S9 | 成功但 POST 失败 | ALLOW | ALLOW | SUCCEEDED | SUCCESS（ledger=RETRYING） |
| — | Schema 失败 | ALLOW | DENY | NOT_STARTED | PARAM_SCHEMA_FAIL |
| — | 参数策略失败 | DENY | NOT_EVALUATED | NOT_STARTED | PARAM_POLICY_FAIL |
| — | 未知风险 | DENY | NOT_EVALUATED | NOT_STARTED | RISK_LEVEL_UNKNOWN |

S3f 核心断言：准入后撤权不静默撤销在途；不确定结果必须进对账。

### 9.2 不变量与故障注入（纳入冻结门槛）

| ID | 类型 | 断言 |
|---|---|---|
| S3c | INVARIANT | 并发撤权 → authzVersion 单调无丢失 |
| S3e | INVARIANT | 重复撤权 → 版本继续递增 |
| S3g-a | 行为 | 单次调用配置有效性 |
| S3g-b | INVARIANT | 新调用只见完整已发布配置 |
| S3g-c | INVARIANT | 已准入在途按约定继续 |
| PUB-1 | INVARIANT | CAS 失败事务无半成品当前发布 |
| PUB-2 | INVARIANT | seq 分配序 ≠ 提交序时指针仍以 CAS 为准 |
| COMP-1 | 故障 | RESERVED 后预算失败 → 审批 CANCELLED、预算无残留 |
| COMP-2 | 故障 | 预算成功后 PRE 失败 → cancel + 释放；补偿失败入队 |
| COMP-3 | 故障 | 准入失败后释放失败 → 持久化补偿任务 |
| COMP-4 | 故障 | 成功 + POST 失败 → SUCCEEDED + ledger 重试 |
| COMP-5 | 故障 | UNKNOWN 上幂等并发重试 + 人工确认 → 仅一次终态 |
| COMP-6 | 故障 | 确定成功 → 结果持久化失败 → 进程重启 → 落账 SUCCEEDED 且外部只执行一次 |

---

## 10. 需求追踪原则

- 状态枚举：`DESIGNED` → `DEMO_IMPLEMENTED` → `PLATFORM_IMPLEMENTED` → `UNIT_TESTED` → `INTEGRATION_TESTED` → `SECURITY_ACCEPTED` → `ACCEPTED`。  
- Demo 与平台分列；每项附证据路径与责任人。  
- 进入各态须满足：实现落点、对应 §9 用例通过、可审计日志。  
- 当前若无平台达到 `PLATFORM_IMPLEMENTED`，冻结未达成。

---

## 11. 规则权威与证明闭环

| 权威 | 范围 |
|---|---|
| §5 | 安全决策语义、顺序、错误码、线性化、审批与执行意图 |
| §8 | V1 范围与冻结门槛 |
| §6 | 数据、发布 CAS、指纹实现 |
| §7 | 组件编排（只引用 §5.3） |
| §9 | 验收证据 |

```
安全保证（§5）→ 实现机制（§6/§7）→ 可观测证据（日志/账本/意图/结果事件）→ 自动化断言（§9）
```

每个 P0 保证至少对应一个实现落点 + 一个测试 ID。

---

## 12. 实施路线

| 阶段 | 交付 | 出口条件 |
|---|---|---|
| P0 地基 | cap_* + authzVersion DB + 迁移双写 + 结果事件表 | 表结构、原子自增、RLS、事务契约 |
| P1 解析校验 | PermissionResolver + InvocationValidator | Phase A 全因子 + 指纹 + §5.8 |
| P2 闸门 | Gate 扩展 + 准入线性化 + 执行意图 | S1–S4、S3*、防绕过 0 |
| P3 审批与恢复 | 双哈希、状态机、补偿、COMP-1～6 | 审批分支码 + COMP-6 |
| P4 MCP | http/sse 适配 + 固定清单 + SSRF | S5* + 具名安全测试 |
| P5 发布审计 | CAS 发布 + 四字段/ledger 日志 | 生命周期 + PUB-* + 审计 100% |
| P6 冻结 | 全量 IT/安全/故障注入 | §8.3 三类门槛 |

---

## 13. 设计取舍

1. 准入点线性化（方案 A），可测可审计。  
2. 调用协议唯一写在 §5.3。  
3. 审批：范围哈希 + 有效参数哈希，防止参数替换重放。  
4. 执行意图持久化先于外部调用，闭合崩溃窗口。  
5. 确定结果先入可恢复存储，再 POST；COMP-6 为硬门槛。  
6. 发布：同一事务内版本+release+指针 CAS。  
7. MCP：规范化后交集；新鲜度先于操作级。  
8. 成功 reasonCode 固定 SUCCESS；审批失败分支码唯一。  
9. 性能拆本地计算与授权链。  
10. Demo 永不等于平台验收。

---

## 附录 A：V1 冻结前检查清单（评审用）

**安全决策**  
- [ ] 五类决策字段及枚举唯一  
- [ ] 撤权与准入线性化已定义且有并发测试  
- [ ] 审批双哈希 + 状态机 + 意图边界  
- [ ] 未知风险/服务不可用 fail-closed  

**数据与发布**  
- [ ] 对象版本不可变，Run 快照固定实际版本  
- [ ] 发布成功条件 = 同事务版本+release+CAS  
- [ ] 闭包指纹内容与算法明确  
- [ ] 回滚不恢复已撤权  

**执行与恢复**  
- [ ] §5.3 顺序唯一；意图先于 execute  
- [ ] 确定结果可恢复；COMP-6 通过  
- [ ] UNKNOWN 与 ledger 维度分离  
- [ ] 防绕过 0 通过  

**验收**  
- [ ] §9 行为四字段 + 具体原因码  
- [ ] INVARIANT 与故障注入纳入门槛  
- [ ] 需求级状态可追溯  

勾选仅表示评审确认，不构成平台验收证据。

---

**文档结束。**  

**使用说明**：实现与测试以本文 §5 / §8 / §9 为准；与历史 01–09 冲突时，以本文闭合后的契约优先，并回写修订说明到变更日志。V1 冻结以平台自动化与故障注入全绿为唯一依据。