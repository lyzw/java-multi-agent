# Agent 设计总览

> **文档定位**：这是一份**以代码为准**的 Agent 子系统总览 —— 每条结论都能指到具体的表定义、
> 类或装配点。**不重述方案文档**：设计意图与验收条款看 `docs/权限管控体系/`、`docs/多级 Agent优化/`，
> 本文只回答「现在这套东西**长什么样、怎么跑、承重规则是哪几条、哪里还是空的**」。
>
> **读法**：§1 是全景；§2–§6 是要改代码前必须读的（对象 → 分层 → 两条时序 → 委派）；
> §7 是不可破坏的不变式；§8 是**现状边界**（最容易在读代码时误判的地方）；§9–§11 是索引。
>
> **最后核对**：2026-10-27，逐条读代码确认（迁移目录、`platform/*/src/main/java`、
> `platform-bootstrap` 装配点），非转述旧文档。

---

## 1. 一句话架构

**治理期**把「Agent 能用什么」算成一个**不可变的能力闭包**并发布；
**运行期**每个 Run 创建时把配置**钉死成快照**，此后所有工具调用——**包括委派子 Agent**——
都必须穿过**唯一一道闸门**，闸门在执行副作用前再向权威源确认一次授权与闭包指纹没有变。

```
 ┌── 治理期（配置，人工发布，双人复核）──────────────────────────────┐
 │  草稿 → 闭包计算(PermissionResolver) → 指纹 → release → 配置指针  │
 └───────────────────────────────┬───────────────────────────────────┘
                                 │ 已发布固定版本
 ┌─ 运行期（每个 Run 一次）──────▼───────────────────────────────────┐
 │ Run 创建：固化 execution_snapshot（配置）+ 闭包快照（见 §8-7）    │
 │   → Worker 领取(fencing) → Run 循环 → ModelGateway                │
 │   → ToolInvocationGate（15 步准入 → 账本/审批/预算/意图/执行）    │
 │        ├─ 普通工具：ToolHandler（native / mcp / http）            │
 │        └─ 委派工具：DelegationExecutor → 子 Agent 过同一个闸门     │
 └───────────────────────────────────────────────────────────────────┘
```

三层治理对象 + 一个编排层：**Agent 启用 Skill，Skill 声明 Tool/MCP，Agent 声明可委派给谁**。
**闭包 = 三层的交集，且 Agent 侧只允许收紧。**

---

## 2. 对象模型

### 2.1 能力治理四件套（`V15__cap_core.sql`）

| 对象 | 表 | 关键语义 / 承重设计点 |
|---|---|---|
| Tool | `cap_tool_def`（V15:L79） | `risk_level` 用 **NOT NULL + CHECK 强制显式声明**（缺省即拒，比应用层默认值硬）；`param_policies` V1 只支持 `in_allowlist` / `lte` 两种判定，其他 op **写入即拒**；`kind` 取值域见 §2.4 |
| Skill | `cap_skill_def`（V15:L125） | 只存 Tool **id 列表**（有效版本由发布头解析，不在这里写版本）；`risk_ceiling` 非法即发布拒绝；`inherits` / `required_caps` 是**预留字段，写入即拒**——DB 层 CHECK 兜底，防止「预留」悄悄变成「生效」 |
| MCP Server | `cap_mcp_server_def`（V15:L160） | `trust_tier`（internal/partner/public）影响默认审批；`exposed_tool_allowlist` 的 **`null ≠ []`**（null=仅连接级授权，[]=任何操作都不允许）；动态发现 V1 固定 `false`，写 `true` 即拒（否则会绕过固定清单与指纹校验） |
| Agent | `cap_agent_def`（V15:L216） | `enabled_skills` 是**唯一启用途径**（不允许标签/隐式传播启用）；`overrides` 只允许 `removeSkills` / `removeTools` / `lowerRiskCeiling`；`roles` **只声明适用主体范围，不是授权授予** |

### 2.2 治理期的两个「事实源」

| 表 | 位置 | 作用 |
|---|---|---|
| `cap_authz_version` | V15:L266 | **DB 化单调授权版本**。撤权 = 版本递增；递增必须用 `UPDATE…RETURNING` 原子自增，禁止应用先读后加一；**读不到即 fail-closed**（`AUTHZ_UNAVAILABLE`） |
| `cap_config_release` | V15:L337 | 全局单调发布序号 `config_publish_seq`（跨对象一致边界）；双人复核有 **DB CHECK**（`approver_id <> requester_id`），不只靠应用层；`scope='PLATFORM' ⇔ tenant_id IS NULL` |
| `execution_capability_snapshot` | V15:L293 | Run 绑定的**有效能力闭包快照**：`effective_closure` + `config_fingerprint` + `authz_version` + `config_version` |
| `cap_execution_intent` / `cap_result_event` | V15:L413 / V15:L377 | 执行意图（含 `args_fingerprint` 防参数替换重放）与确定结果；两者都有「**同一 invocation 最多一条**」的唯一约束 |

### 2.3 编排层（多级 Agent）

| 表 | 位置 | 作用与承重设计点 |
|---|---|---|
| `cap_agent_def.orchestration` | [V19:39](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-infrastructure/src/main/resources/db/migration/V19__multi_agent_orchestration.sql#L39) | 默认 `'{}'` 而**不是 NULL**：让「没填」与「填坏了」在存储层分得开；`{}` 在解析层等价于 LEAF。迁移**不改写任何历史行** |
| `cap_agent_child_ref` | V19:56 | 父子边的**发布投影**，权威仍是不可变 release 里的 `childAgentRefs`；本表允许重建，release 永不改写。子引用**必须固定版本**（`child_release_version NOT NULL`，禁止 latest 漂移）；复合外键 `(tenant_id, agent_id, version)` 顺带保证租户一致 |
| `agent_invocation` | V19:106 | 委派运行记录。**只存摘要**（`input_digest` / `output_digest`），不存明文；七态状态机，`UNKNOWN` 仅表示「结果确实不确定」，**不得**用来表示普通错误或账本写失败；`depth=1 ⇔ parent_invocation_id IS NULL`（否则调用树无法重建）；`fencing_token` 供终态 CAS |
| `cap_result_contract` | [V20:24](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-infrastructure/src/main/resources/db/migration/V20__result_contract.sql#L24) | 委派**输出契约**（存 JSON Schema 定义本身）。V23 把 id 从 UUID 改为可读字符串（曾因类型不符导致整笔发布事务回滚） |
| `cap_routing_policy` | [V24:39](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-infrastructure/src/main/resources/db/migration/V24__routing_policy.sql#L39) | ROUTER 模式的路由策略。**这张表存在的理由写在文件头**：此前 `routingPolicyRef` 是**悬空引用**——发布期不校验、运行期不读、指纹里只有一个字符串，「声明了路由策略」与「路由行为没变」外部观察不到差异 |
| `agent_invocation.requested_target_agent_id` | [V25](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-infrastructure/src/main/resources/db/migration/V25__agent_invocation_requested_target.sql#L13) | 「模型原始候选」与「实际生效目标」**并存**，用来回答「这次是不是发生了路由降级」。**存量行一律不回填**：当时只有日志留痕，据此回填＝用猜测冒充事实，NULL 的语义是「当时未记录」≠「没有降级」 |

### 2.4 运行事实（V1 老体系 + 后续增补）

`agent` / `agent_version` / `execution_snapshot`（[V1:67 / :80 / :107](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-infrastructure/src/main/resources/db/migration/V1__baseline_schema.sql#L67)）
+ `run` / `run_step` / `run_event` / `tool_operation` / `approval` / `budget_account` / `budget_reservation`
+ `audit_record` / `object_access_grant`（预签名 URL 必须带有效期）
+ V13 `agent_message_queue` / `agent_message_dead_letter`（ADR-001 主 MQ）
+ V21 `run.deadline_at`、V22 Run 级预算、V30/V34 状态迁移护栏、V33 平台 Agent 指标

⚠️ `execution_snapshot` 的第四段 `run_config` 是 DEC-9 的落点：**Run 创建时固化配置，此后改配置只影响新 Run**。
`agent` 表带 `UNIQUE (tenant_id, agent_id)`——**专门为下游建复合外键而存在**，单列主键不足以约束租户边界。

### 2.5 Tool 的三种 `kind`

`native | mcp | http`（[V28:82](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-infrastructure/src/main/resources/db/migration/V28__cap_tool_http_kind.sql#L82)）。
V28 同时把 MCP 绑定约束改成**三种 kind 各写各的分支**，理由在文件头里写明：
若把 `native` 那支放宽成「非 mcp 即无绑定」，将来加第四种 kind 会**默默**被放进去——那是会长 bug 的地方。

---

## 3. 分层与包结构

| 模块 | 职责 | Agent 相关的关键内容 |
|---|---|---|
| `agent-core` | **纯领域**（无 Spring、无 DB） | `core.cap`（`CapAgentDef`/`CapToolDef`/`CapSkillDef`/`CapMcpServerDef`/`CapabilitySnapshot`/`EffectiveClosure`/`ExecutionScope`/`CapToolDecision`/`AuthorizationDecision`）、`core.orchestration`（`AgentOrchestration`/`OrchestrationMode`/`RoutingStrategy`/`DelegationFailurePolicy`/`AgentInvocation`/`DelegationToolSchema`）、`core.run.RunState`、`core.config.RunConfigSnapshot`、`core.tenant.TenantContext`、`core.compliance.ZeroRetentionGate` |
| `agent-ports` | 出站端口 | `persistence.*`（`CapAgentDefPort`/`CapAgentChildRefPort`/`AgentInvocationPort`/`CapabilitySnapshotPort`/`ExecutionSnapshotPort`…）、`security.AuthorizationService`/`ApprovalPolicy`、`model.ModelGateway`、`tool.ToolHandler`、`mcp.*`、`orchestration.ChildAgentRunner`/`RoutingPolicyProvider`/`ResultContractProvider` |
| `agent-application` | 用例与治理 | `cap`（**`PermissionResolver`**、`CachingClosureResolver`、**`CapExecutionFlow`**、**`ExecutionAdmissionChecker`**、`InvocationValidator`、`ConfigPublishService`、`AgentPublishClosureValidator`、`DualWriteAgentManagePort`、`CapDefinitionProjector`）、`orchestration`（**`DelegationExecutor`**、`DelegationGuard`、`AgentOrchestrationParser`、`ChildResultContractValidator`）、`run.RunCreationService`、`prompt.PromptGuard`、`metrics.AgentMetricCollector` |
| `agent-infrastructure` | 持久化与安全实现 | `persistence.cap.*`（Jdbc 仓储）、`security.RoleBasedAuthorizationService`、`model.`（**只有 `AbstractModelGateway` + `FakeModelGateway`**）、`tool.`（`GetStudentProfileTool`/`RecordWeaknessTool`/`ToolInputSchemaValidator`/`ToolOperationReconciliationService`） |
| `agent-worker` | 执行 | `dispatch`（`RunDispatchConsumer`、Outbox Relay）、`execute`（**`RunExecutionLoop`**、**`ToolInvocationGate`**、`WorkerChildAgentRunner`、`AgentModelConfigAssembler`、`DelegationToolSchemaAssembler`、`ToolOutputLimiter`、`ContextBudgetTrimmer`） |
| `agent-api` | 入口 | `governance.AgentController`（`/api/agents`）、`config.CapAgentDefinitionController`（`/api/cap/agents`）、`config.DelegationRelationController`（`/api/cap/delegations`，**只读边查询**）、`config.CapabilityCatalogController`、`run.RunController` 等（完整清单见 §11） |
| `platform-bootstrap` | 装配 | `config.WorkerExecutionConfig`（执行链装配）、`config.ApplicationConfiguration`（治理链 + **CAP 开关**） |

---

## 4. 治理期时序：发布即计算闭包

1. **草稿**（`cap_*_def` 行 + `published_at IS NULL`）。
2. **闭包计算** = [`PermissionResolver`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/PermissionResolver.java#L54)：
   Agent → Skill → {Tool, MCP} 的**交集**，无副作用、可反复调用。
   - ⚠️ **fail-closed 的精确含义**：任何对象解析不到就抛 `ClosureResolutionException`，
     **绝不退化成「跳过该对象」**——跳过等于把「声明了但解析不出」当成「没声明」，
     那会让越权被伪装成「工具未声明」。
   - **静态深度护栏** `MAX_STATIC_CLOSURE_DEPTH = 8`（PermissionResolver.java:63）：
     这是**配置期**护栏，与运行期 `maxDelegationDepth` 无关——环检测拦「确定成环」，
     本上限拦「还没成环但深到不可运维」。
3. **发布闭包校验**（`AgentPublishClosureValidator`）：引用必须已发布、路由策略目标必须是拓扑子集、
   被引用行内容指纹必须可解析。
4. **规范化指纹** `config_fingerprint` → **release**（`cap_config_release`，双人复核）→ **CAS 切指针**
   （`ConfigPublishService`，`cap_authz_version` / `config_publish_seq` 单调递增）。

**「两层收窄」关系**（容易写错的地方）：`childAgentRefs` 声明**委派拓扑**（允许交给谁），
`cap_routing_policy.targets` 声明**拓扑内的子集与选择方式**——策略目标必须是拓扑子集，
否则同一件事有两个真相，出事时分不清谁说了算。

---

## 5. 运行期时序：闸门是唯一入口

### 5.1 Run 创建：把「能做什么」钉死

[`RunCreationService`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-application/src/main/java/cn/studykid/multiagent/application/run/RunCreationService.java#L40)：
Run、初始事件、幂等记录、Outbox **四者同事务**（事务边界由 `TransactionRunner` 显式控制，实现方不得自行提交）；
幂等三态——首次创建 / 重复且同请求**返回首次 Run** / 键相同请求不同**拒绝**。
创建时固化的是 `execution_snapshot`（四段配置，V1:L107）；
⚠️ **`execution_capability_snapshot` 不在这里写**——它由 `CapDualWritePort.onExecutionSnapshotCreated`
这个钩子负责（见 §8 第 7 条），而该钩子**目前只有端口与实现，生产路径无调用方**。

⚠️ `BudgetPort` **未装配时，声明了 Run 额度的创建请求一律被拒**：
校验缺席若静默放行，等于「Run 级上限可以大于租户总额度」——那样的上限**永远不会被触发**，形同虚设。

### 5.2 执行循环

[`RunExecutionLoop`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java#L40)：**只做编排**（模型调用 → 闸门 → 状态提交），不关心工具做什么。
- ⚠️ **重试只在 Worker 侧实现**，绝不引入 Spring `@Retryable`：两者同时生效就是双重重试，
  对 `record_weakness` 意味着**重复写入 = Sev-0**。
- `renewLease` 按 `lease_owner` 匹配，故 workerId 必须与领取时**完全一致**（V30 补了 step fencing 护栏）。
- 主循环是 `while (steps < snapshot.maxSteps())`（RunExecutionLoop.java:309，运行约束取自 **Run 快照**）；
  工具调用后把「模型意图 + 工具结果」并入下一轮上下文（:462–467，与子执行器**共用** `ToolOutcomeRenderer`）；
  出现 **UNKNOWN 即停止执行并转对账**，绝不当普通失败重试、也不释放预算预留（:447–454）。
- 落 `jsonb` 的载荷走 Jackson，不手工拼字符串（裸控制字符会被 PG 拒 → 提交失败 → Run 被误报为失败）。

### 5.3 闸门与 15 步准入

[`ToolInvocationGate`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ToolInvocationGate.java#L51) 是**不可绕过的安全边界**，
类头把顺序写成契约：`Schema 校验 → 执行时重新授权 → 幂等键 → 账本 → 审批 → 预算 → 执行 → 账本更新`。
**禁止使用 Spring AI `ToolCallingAdvisor` 的自动执行路径**（会绕过全部闸门，ADR-011 §3.3）。

闸门内部把「准入判定」委托给
[`CapExecutionFlow`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/CapExecutionFlow.java#L51)（§5.3 唯一顺序，步骤 1–15）：

```
1 可信上下文（租户/主体/Run 不取客户端输入）    2 PrincipalAuthorized（角色 + authzVersion 强一致读）
3 EffectiveClosure                             4 AgentSkillAllowed → SkillToolAllowed → RiskWithinCeiling
5 MCPBindingAllowed                            6 ParamPolicyPassed
7 ConfigFingerprintValid                       8 幂等键校验
9 SchemaValid（结构，仅一次）                  10 账本登记（PRE）
11 Approval                                    12 BudgetOk
13 ExecutionAdmissionCheck（TOCTOU）  13a 持久化执行意图（必须先于外部调用）  13b 审批 → INTENT_SUBMITTED
14 ToolHandler.execute  14a 确定结果先入可恢复存储   15 账本 POST
```

- ⚠️ **步骤 10 相对契约前移是有外键依据的**（CapExecutionFlow.java:72 起）：
  `approval.operation_id` 与 `budget_reservation.operation_id` 都外键指向 `tool_operation`，
  所以账本 PRE 必须早于审批与预算——**改顺序前先读那段依据，别当成风格问题**。
- **线性化边界 = 步骤 13 通过的瞬间**（[`ExecutionAdmissionChecker`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/ExecutionAdmissionChecker.java#L20)）：
  在**任何外部副作用发出之前**重新向权威源确认 ①`authzVersion` 未变（撤权=版本递增）②闭包指纹未变。
  两个契约：撤权在边界**前**提交 → 必须读到新版本 → `EXEC_ADMISSION_STALE` 阻断新副作用；
  撤权在边界**后**提交 → 在途操作**不承诺取消**。
  ⚠️ 本类**不做取消、不做补偿**（那是闸门的事），只回答「准入是否通过」这个二元问题——
  把判定与补偿耦合在一起会互相掩盖失败。
- **预算三条规则**（ToolInvocationGate 类头）：预留失败即拒绝（fail-closed）；
  预留失败记 **FAILED 而非 UNKNOWN**（工具根本没发出，记 UNKNOWN 会凭空造出待对账的幽灵操作）；
  **UNKNOWN 绝不释放预留**（成本可能已发生，只有确定失败才退额度）。

---

## 6. 多级委派：拓扑与授权彻底分离

[`AgentOrchestration`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-core/src/main/java/cn/studykid/multiagent/core/orchestration/AgentOrchestration.java#L35)
**只描述委派拓扑，不授予任何权限**。三个模式（[OrchestrationMode](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-core/src/main/java/cn/studykid/multiagent/core/orchestration/OrchestrationMode.java#L14)）：

| 模式 | 语义 | 要点 |
|---|---|---|
| `LEAF` | 不发起任何委派，**缺省语义** | 「多级 Agent 是**显式开启**的能力」；唯一允许 `childAgentRefs` 为空的模式，声明了子引用即为配置错误 |
| `ROUTER` | 按**固定版本**路由策略选目标 | 模型输出**只是候选**，必须落在父声明内且服务端复核 |
| `SEQUENTIAL` | 父可在声明集合内**逐次**委派 | ⚠️ 框架**不代劳串行与聚合**（评审前置决策 1 · 2026-10-25：不纳入 P2）。框架只给三件事：子集合、逐跳重新授权、逐跳资源封顶。**一次委派 = 一个目标 = 一条调用记录 = 一次子执行** |

[`RoutingStrategy`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-core/src/main/java/cn/studykid/multiagent/core/orchestration/RoutingStrategy.java#L26)：
`EXPLICIT_TARGETS`（候选不命中即**真实路由失败，不降级**——静默换一个目标会让父收到「不是我点的那个」的结果）
/ `ORDERED_FALLBACK`（按声明顺序取第一个；⚠️ **降级只作用于「选择」，不作用于「准入」**，
降级选中的目标仍须通过父声明包含→版本固定→逐跳授权→深度/次数/超时/预算的全部判定，
任一不过就是失败，**不会继续试下一个候选**——逐个试下去会让「一次委派」变成「一串委派」，绕过次数上限）。

[`DelegationFailurePolicy`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-core/src/main/java/cn/studykid/multiagent/core/orchestration/DelegationFailurePolicy.java#L10)：
`FAIL_FAST`（缺省）/ `CONTINUE_ON_ERROR`（只作用于**同一轮**的多个工具调用之间，失败结果**照常回灌**、不由框架聚合）。
两者都**不描述「是否撤销已发生的副作用」**：父 Run 失败不得被解释为所有副作用已撤销，
已发出的外部操作只能按 UNKNOWN 对账处置。

[`DelegationExecutor`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-application/src/main/java/cn/studykid/multiagent/application/orchestration/DelegationExecutor.java#L60) 的 8 步（类头即契约）：
**幂等检查 → 解析父 → 以父声明为准定目标固定版本 → `DelegationGuard` → 解析子闭包 → 落 `AgentInvocation` → 调度子 → 输出契约校验 → 写终态**。

- 委派走**保留工具名** `DelegationToolSchema.TOOL_NAME`（DelegationExecutor.java:73）——
  名字必须唯一定义：它要同时出现在「模型可见的 Schema」和「闸门的委派分支」，两处各写一遍必然漂移，
  后果是「模型看得见的委派工具」与「闸门认得的委派调用」对不上，委派被当成未登记工具拒绝。
- [`DelegationGuard`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-application/src/main/java/cn/studykid/multiagent/application/orchestration/DelegationGuard.java#L43)：
  逐跳重新授权（**绝不继承父的授权结论**，落后即 `AUTHZ_STALE`，读不到即 `AUTHZ_UNAVAILABLE`）；
  深度/次数/超时/预算**整棵调用树共享根 Run**（不能靠嵌套 Agent 重置额度）；
  `BudgetProbe` **不提供默认实现**、构造期必须显式注入（缺省「还有预算」= 嵌套即绕过上限）；
  时间预算是**封顶而非否决**：有效预算 = `min(根 Run 剩余时间, 本级 timeoutMs)`——
  若据「剩余比本级 timeout 长」就拒绝，所有合法委派都会被判超时。
- **平台默认值已删除**（AgentOrchestration.java:46–57）：原先的 depth=2 / 次数=8 / 60s 三个常量
  从未经过容量与业务评估，一旦以「默认值」存在就会被配置方**默默继承**并在没人评审的情况下成为生产口径。
  现在**非 LEAF 必须显式声明，缺失即拒绝发布**；LEAF 用 `null` 表达「不适用」（不是 0，也不是某个默认值）。
- **子 Agent 走同一个闸门，但不套 `RunExecutionLoop`**：
  [`WorkerChildAgentRunner`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentRunner.java#L59)
  自己起一个**多轮小循环**（默认 `DEFAULT_MAX_CHILD_STEPS = 4` 轮模型调用，超限即失败），
  每轮的工具调用**直接进 `ToolInvocationGate` + 子自己的闭包**；
  取消与超时**由子执行自己实施**（MA-T11 / MA-T09）——否则「取消」退化成「不再发起新委派」，
  而正在运行的子 Agent 仍在产生副作用。
  回灌口径与根循环**共用 `ToolOutcomeRenderer`**（不可信标记 / 长度封顶 / 失败与被拒也回灌三条规则逐字一致），
  这是刻意设计：两处各写一份就是「父子两套语义」。父的工具集**不会因为「能委派给 X」而扩大**。
- 编排声明**固化进发布闭包**（MA-T15），改拓扑必须走发布，不存在「运行期临时加边」。

---

## 7. 七条承重不变式（改动前先读）

| # | 不变式 | 破坏后的表现（为什么它难发现） |
|---|---|---|
| 1 | **release 不可变，引用必须 `id@version`** | latest 漂移别名会让「昨天还能跑的 Agent」今天行为变了，且审计看到的是新内容 |
| 2 | **闭包只收紧不扩张**（Agent 的 `overrides` 只能删） | 「隐式启用」一旦允许，越权路径变成配置噪声，人工复核看不出 |
| 3 | **委派不授予权限，逐跳重新授权** | 父继承子能力后，`childAgentRefs` 就成了横向提权通道 |
| 4 | **一切「读不到」= 拒绝**，不是跳过 | 跳过把「声明了但解析不出」当成「没声明」，故障伪装成配置缺失 |
| 5 | **UNKNOWN 绝不释放预算预留**；预留失败记 FAILED | 释放 UNKNOWN = 为「可能已发生的成本」退额度，账实不符且不可对账 |
| 6 | **租户边界 = RLS + `(tenant_id, 父id)` 复合外键**双保险 | 单列主键不足以约束跨租户引用；漏一条策略是**静默**跨租户读写 |
| 7 | **顺序是契约**（闸门 15 步、委派 8 步、准入线性化边界） | 插入/前移一步会让「副作用先于意图落库」，重放与幂等语义当场失效 |

---

## 8. 现状边界（读代码时最容易误判的八处）

这几处**不是设计缺陷，是进度**，但会让人把「已装配」误读成「已生效」：

1. **CAP 授权链路默认关闭**。`CapEnforcementSwitch` 的配置键
   `config.flag.cap-authorization-enforced` **默认 `false`**
   （[ApplicationConfiguration:1641–1648](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApplicationConfiguration.java#L1641)，
   注释写明「发布指针读落地前不得在生产开启」）。闸门装配处把它当参数注入并在启动日志里打印
   `CAP 授权链路={}`——**排查行为时先看这行日志**。
2. **准入链路的主体是常量**。`CapExecutionFlow.DEFAULT_PRINCIPAL_ID = "worker"`
   （CapExecutionFlow.java:109，bootstrap 也按此注入）：Worker 进程以固定主体身份过准入，
   **与「人类用户主体授权」不是同一件事**。
3. **没有真实模型网关**。`infrastructure/model/` 只有 `AbstractModelGateway` + `FakeModelGateway`，
   全仓 grep 不到任何供应商 SDK 依赖 → 端到端行为目前**只在假模型下成立**。
4. **审批是「查不到即拒」**。`RiskBasedApprovalPolicy.ApprovalSource` / `ApprovalPort`
   未接真实审批台时恒 `NO_APPROVAL` → 需审批工具一律被拒（行为安全，但没人能批）。
5. **工具极少**：`GetStudentProfileTool`、`RecordWeaknessTool` 两个内置工具 + `kind=mcp`/`kind=http` 的动态通道。
6. **新旧两套 Agent 表并存**，靠 `DualWriteAgentManagePort` 双写衔接（V1 体系仍是运行事实的载体）。
   双写**刻意做成最终一致而非同事务**（`CapDualWriteService` 类头）：旧模型写入在其自身事务内提交，
   双写在其后独立提交 → 存在「旧行已存在、新行未落」的窗口，这正是「新旧模型只做只读比对」的原因。
7. ⚠️ **`execution_capability_snapshot` 目前在生产路径上没有写入方**。
   写它的唯一入口是 `CapDualWritePort.onExecutionSnapshotCreated`
   （[CapDualWriteService:109–150](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/CapDualWriteService.java#L109)，
   注释写明「P1 起此处真正写」），但**生产代码里没有任何调用方**（只有 IT 直接调它）；
   双写装饰器 `DualWriteAgentManagePort` 只挂了 `onAgentVersionPublished`。
   而读侧有六处且**一律 fail-closed**：`CapExecutionFlow:814`（无快照 → `RESOURCE_UNKNOWN` 拒）、
   `ExecutionAdmissionChecker:187`（无快照 → `EXEC_ADMISSION_STALE` 拒）、`InvocationValidator:371`、
   `DelegationExecutor:695/727`、`RootAgentModelConfigProvider:76`。
   → **含义**：今天没出事是因为第 1 条的开关关着；**开启 CAP 授权链路之前必须先把这个写入方接上**，
   否则每一次工具调用都会被自己的准入链拒绝（表象会是「全部工具不可用」，而根因在装配缺口）。
   另一处同源约束：闭包算不出来时**不写占位闭包**（宁可跳过 + 计数），
   否则「配置缺陷」会被读成「该 Agent 没有任何能力」，把配置问题伪装成权限问题。
8. ~~**代码注释有一处已过时**：`WorkerChildAgentRunner` 类头写「已知缺口：根 Agent 主循环仍未回灌工具结果」，
   与现码不符（`RunExecutionLoop:462–467` 早已用**同一个** `ToolOutcomeRenderer` 回灌）~~
   —— **已于本次整理改掉**（2026-10-27）：该段注释改为「父子回灌口径必须共用同一份实现」，
   与 `docs/多级 Agent优化/00_修订说明` 第八批的记录一致。**功能上从来没有这个缺口，是注释残留**。

---

## 9. 迁移分幕（34 支，按主题归组）

| 幕 | 迁移 | 引入了什么 |
|---|---|---|
| 运行地基 | V1–V10 | `agent`/`agent_version`/`execution_snapshot`/`run*`/`budget_*`/`approval`/`audit_record`/`object_access_grant` + RLS 机制（`current_tenant_id()`）+ 学生域 + 限流 + 保留与擦除 |
| 配置治理 | V11–V14 | 租户配置覆盖、配置变更请求、**V13 主 MQ 队列与死信**、V14 种子管理员 |
| **CAP 核心** | V15–V18 | 能力四件套 + `cap_authz_version` + 执行闭包快照 + 审批/补偿 + 发布指针与审计 + 发布请求 |
| **多级编排** | V19–V25 | orchestration 列 / 父子投影 / `agent_invocation` / 输出契约 / Run deadline / Run 预算 / 契约 id 改 TEXT / 路由策略 / 模型原始候选 |
| 生命周期与 kind 扩容 | V26–V29 | 契约与策略的生命周期态、发布请求平台作用域、`kind=http`、Skill 两级描述 |
| 平台域 | V30–V34 | step fencing 护栏、**平台身份域**、平台字典、平台 Agent 指标、完整状态迁移护栏 |

---

## 10. 文档地图（含一处需要知道的歧义）

| 主题 | 位置 |
|---|---|
| 能力治理体系（V1 基线） | `docs/权限管控体系/01–09`（03 PRD、04 V1 范围与非目标、05 分层授权决策规范、06 配置数据模型、07 执行链集成、08 安全测试与验收矩阵、09 需求追踪） |
| 多级 Agent v1.1 | `docs/多级 Agent优化/`（`00_修订说明与评审决议项` + **06/07/08/09 同名续卷** + P0-D01…D06 + `agent-capability-contracts-v1`） |
| 分阶段交付说明 | `docs/统一配置与权限管控体系_开发计划/P0…P6*`（每阶段一份「交付说明」= 实际落地记录） |
| 治理现状评估 | `docs/governance/01-现状分析_Agent_Skill_Tool_MCP.md`、`02-分层授权评估与改进建议.md` |
| 进度与证据 | [`docs/项目进度台账.md`](file:///Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/项目进度台账.md) |

⚠️ **重名歧义（改代码前必读）**：`06_配置数据模型与生命周期设计.md` / `07_执行链集成设计.md` /
`08_安全测试与验收矩阵.md` 在 `docs/权限管控体系/` 与 `docs/多级 Agent优化/` **两个目录里各有一份**，
后者是前者的 **v1.1 变更补充**。而代码注释里引用的是**裸编号**（「06 §A5」「07 变更补充」「MA-T05」），
单看注释无法确定指哪一份。约定：**「变更补充」/`MA-*`/`FR-02x+` → 多级 Agent优化；其余裸 § → 权限管控体系**。

---

## 11. 关键类索引

| 环节 | 类 |
|---|---|
| 闭包计算 | `PermissionResolver`（+ `CachingClosureResolver`、`ClosureResolver` 接口、`ReferencedContentResolver`） |
| 发布 | `ConfigPublishService`、`AgentPublishClosureValidator`、`CapDefinitionProjector`、`DualWriteAgentManagePort`、`CapDualWriteService` |
| Run 创建 | `RunCreationService`、`ExecutionSnapshotPort`、`RunConfigSnapshotFactory`、`EffectiveConfigResolver` |
| 执行 | `RunExecutionLoop`、`WorkerExecutionConfig`、`RunDispatchConsumer`、`OutboxRelay` |
| 准入 | `ToolInvocationGate`（外壳/账本/副作用编排）、`CapExecutionFlow`（15 步判定）、`ExecutionAdmissionChecker`（TOCTOU 边界）、`InvocationValidator`、`CapEnforcementSwitch` |
| 委派 | `DelegationExecutor`、`DelegationGuard`、`AgentOrchestrationParser`、`ChildResultContractValidator`、`WorkerChildAgentRunner`、`DelegationToolSchemaAssembler`、`InFlightDelegationAdopter` |
| 读端点 | `AgentController`（`/api/agents`）、`CapAgentDefinitionController`（`/api/cap/agents`）、`CapabilityCatalogController`、`DelegationRelationController`（`/api/cap/delegations`，只读）、`CapConfigPublishController`、`RunController`/`RunQueryController`/`RunEventController`/`ResultController`、`ApprovalController`、`UnknownOperationController`、`PlatformAgentMetricsController` |
