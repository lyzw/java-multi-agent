# admin-console 双入口能力统一与防泄露方案（v1.1 · 第 1–3 刀已落地）

> **适用范围**：`admin-console`（平台控制台）中"同一个功能既可能由 Agent 编排对话触发、也可能由平台层面直接录入/操作"的全部场景。首批落点：**Agent 编排对话**、**RAG 语料录入**。
> **一句话结论**：不要按"要不要 Agent"把功能拆成两套实现，而要按"**同一个能力（Capability）+ 两个入口（Channel）**"重建——两条入口只负责回答"我是谁、在什么作用域下做"，统一收敛到**一个身份解析器 + 一个作用域解析器 + 一个执行器 + 一套票据语义**；数据隔离靠令牌互拒、作用域显式化、票据单点签发、数据面物理分库四道闸，任何一道缺失即 fail-closed。
> **决策前提**：项目未上线，dev/test 库可重置，本方案作为新基线；不迁就现有两套并行实现。
> **证据基线**：2026-11-18 源码核实，关键锚点见文末附录 A。
>
> **执行状态（2026-11-18）**：Q1–Q4 已按建议拍板并执行，第 1–3 刀代码已落地，platform 全量 **3294 测试 / 0 失败 / 0 错误 / 42 跳过，BUILD SUCCESS**。详见文末《附录 B · 落地清单与非目标》。

---

## 0. 先纠正问题里的一个隐含假设（重要）

用户原话的隐含前提是："有些功能需要 agent，有些是平台层面的" —— 仿佛这是**两类不同的功能**。

逐端点核实后，真实情况是：**这是同一类能力的两种调用入口，不是两类功能。**

| 能力 | 入口①：Agent 编排 | 入口②：平台层直接 | 现状实现 |
|---|---|---|---|
| Agent 对话/编排 | `/api/agent/chat/stream`（平台链，显式目标租户）| AI 实验室"发起执行"走执行票证 → `/api/runs` | 同一 `AgentChatController` 双端点，共享编排器 |
| RAG 语料录入 | Agent 编排对话里让 Agent 帮忙登记/校对语料 | `/api/rag/management/**` 表单录入（G/T/A 三档） | 两套入口、两套鉴权，**没有统一抽象** |
| Tool/Skill 调用 | 编排器 tool_call | 能力目录登记/审批 | 三步工作流 + 执行门禁分头把守 |

关键事实：`AgentChatController` 已经是"**一个能力、两个端点、共享编排器**"的正确雏形（`AgentChatController.java:32-44,106-116`），但它把**身份来源差异**硬编码在两个 `if` 分支里，且平台链留了一个"配置默认租户回落"的隐式作用域口子（`RequestAgentChatIdentityResolver.java:85-91`）。RAG 侧则独立长出了 `X-Rag-Scope` + 治理票据体系（`RagCorpusScope.java`、`PlatformRagGovernanceTicketService.java`）。

**病灶不是"能力太少"，而是"同一能力的两条入口各有一套身份/作用域/票据判定"，判定逻辑分散 = 语义迟早漂移 = 泄露。** 因此本方案的第一性目标是**收口判定，而非新增功能**。

---

## 1. 现状诊断：四个真实泄露风险点

以下不是假设，是源码中已存在或已被文档标记的风险，方案必须逐条关闭。

### R1 · 隐式作用域（平台链默认租户回落）
`RequestAgentChatIdentityResolver.java:85-91`：平台链未带 `tenantId` 时回落到配置项 `agent.chat.default-tenant-id`。这与 RAG 侧"作用域必须显式声明、禁止隐式推断"的原则直接冲突——RAG 方案原文：*"隐式推断会让忘记传租户这种本地配置错误静默变成一次跨租户查询"*（`RAG语料平台级治理与租户三档作用域方案.md` §1.1）。
**风险**：一次本以为"平台级通用"的对话，静默落到某个默认租户，读走该租户语料并进入回答。

### R2 · 推理草稿跨渠道泄露
平台链 `thinkingAllowed=true`、租户链恒 `false`（`AgentChatController.java:37-41`、`RequestAgentChatIdentityResolver.java:92,106`）。`event: thinking` 是后端原始推理草稿。若把平台链的编排结果/上下文转发到租户侧展示，或把 contextState 跨渠道复用，就会把本不该出现的推理内容带到另一身份域。

### R3 · 两套票据语义分叉，可被交叉误用
- **执行票证**：`POST /api/platform/v1/tenants/{tenantId}/execution-ticket` → 签**租户域** JWT（`aud=agent-platform`），写 `tenant_id` + 真实租户角色交集，TTL 60s（`PlatformExecutionTicketService.java:186-275`）。
- **治理票据**：`POST /api/platform/v1/rag/governance-ticket` → 签**平台域** JWT（`aud=admin-console-api`），写 `rag_scope` + 专用角色 `RAG_CORPUS_GOVERNANCE`（不在授权矩阵内），TTL 300s（`PlatformRagGovernanceTicketService.java:187-241`）。

两者签发分散在两个 Service、claims 结构不同、下游各自解析。**任何一处把 `rag_scope` 当成"随便一个平台令牌也有"、或把执行票证当成平台身份用，都是提权。**

### R4 · 服务间鉴权的 Bearer 透传边界
解析器把原始 Bearer 交给编排器做 platform→rag 鉴权；租户链强制 `tenant_id claim == X-Tenant-Id`，平台链允许合法跨租户（`RequestAgentChatIdentityResolver.java:44-46`）。这条差异若随 request 对象在异步线程/通道间流转而丢失，就会用平台令牌读到租户正文。

> 另有已登记未决项：U-9（ALL 档 501）、R-4（全局语料无逐租户准入门槛）、`X-Tenant-Min-Trust-Tier` 可伪造、`rag.security.enabled=false` 逃生门——本方案在 §7 统一处置。

---

## 2. 方案总纲：一个能力、两个入口、一个收口点

### 2.1 三层解耦模型

```
┌──────────────────── 入口层（Channel）────────────────────┐
│  平台直录 PLATFORM_DIRECT      Agent 编排 AGENT_ORCH      │
│  （控制台表单/上传）           （对话里让 Agent 去录入）   │
│  只回答：我是谁 / 在什么作用域下做 / 目的是什么            │
└──────────────────────────┬───────────────────────────────┘
                           │  统一归一为 InvocationContext
┌──────────────────────────▼───────────────────────────────┐
│  收口层（单一判定点，fail-closed）                          │
│   IdentityResolver → ScopeResolver → CapabilityRegistry    │
│   → TicketBroker（唯一签发/验签） → PolicyGate             │
└──────────────────────────┬───────────────────────────────┘
                           │  已授权的能力调用（带 scope/票据）
┌──────────────────────────▼───────────────────────────────┐
│  执行层（数据面物理隔离）                                   │
│   rag_app(RLS)  │  rag_ops/rag_app 会话窗(BYPASSRLS)        │
│   platform_ops(平台 schema) │  Worker 执行门禁             │
└───────────────────────────────────────────────────────────┘
```

### 2.2 三条不变红线（沿用既有架构，不放宽）

| 红线 | 内容 | 证据 |
|---|---|---|
| N1 | 平台令牌（`aud=admin-console-api`）与租户令牌（`aud=agent-platform`）令牌层互拒；任一链收到对域令牌直接 401 | `SecurityConfiguration.java:170-263`、`JwtTokenService.java:243-248` |
| N2 | 租户业务表 `tenant_id NOT NULL` + FORCE RLS；常规应用角色 `rag_app`/`app_tenant` NOBYPASSRLS；BYPASSRLS 只经受控会话窗 | `01_rag_ddl.sql`、`RagStartupSelfCheck.java:88-97`、`GuardedRagOpsDataSource.java:35-47` |
| N3 | 审计分域：`platform_audit_event`（无 tenant_id）+ `tenant_audit_event`（RLS），仅 `correlation_id` 关联，不混库 | 《平台身份与租户身份分离改造方案》§8 |

### 2.3 三条统一（本方案新增）

| 统一 | 内容 |
|---|---|
| U1 **统一身份解析** | 两条入口共用同一个 `IdentityResolver`，产出同一个 `Principal{domain, principalId, roles, authzVersion}`；禁止各入口自己读令牌 |
| U2 **统一作用域模型** | 全平台一套 `Scope{PLATFORM / TENANT / ALL}`；**禁止隐式推断**（含关闭默认租户回落）；缺省一律拒绝而非回落到某个租户 |
| U3 **统一票据语义** | 一张票 = 一个 `InvocationTicket{subject, channel, scope, capability, purpose, correlationId, ttl, jti}`；单一 `TicketBroker` 签发 + 单一验签，下游只认验签后写入的 request attribute，绝不认请求头 |

---

## 3. 统一抽象层设计

### 3.1 InvocationContext（统一调用上下文）——全案的枢纽

```java
// 新增：agent-ports/.../invocation/InvocationContext.java
public record InvocationContext(
    Principal        principal,        // 已验签主体：domain + principalId + roles + authzVersion
    Channel          channel,          // PLATFORM_DIRECT | AGENT_ORCH | TENANT_SESSION
    Scope            scope,            // PLATFORM | TENANT | ALL（显式，无隐式默认）
    String           tenantId,         // 仅 TENANT 档非空；其余档必须为 null
    String           capability,       // 能力标识，如 rag.corpus.ingest / agent.chat.orchestrate
    String           purpose,          // 本次调用目的，进审计
    String           correlationId,    // 贯通双入口与分域审计
    TicketClaims     ticket,           // 已验签票据声明（如需票据）
    boolean          thinkingAllowed   // 由 channel 派生，绝不随数据/上下文流转
) {}
```

**铁律**：`InvocationContext` 只能由 `IdentityResolver` + `ScopeResolver` 在**请求线程**一次性构造，随后以不可变对象传给异步执行体（对齐 `RequestAgentChatIdentityResolver.java:48-50` 的线程边界纪律）。任何能力执行器不得接收裸 `tenantId` / 裸令牌 / 裸 `X-Rag-Scope` 头。

### 3.2 CapabilityRegistry（能力注册表）

每个能力**声明一次**，两条入口共用：

| 字段 | 含义 | 示例（RAG 录入） |
|---|---|---|
| `capabilityId` | 能力唯一标识 | `rag.corpus.ingest` |
| `allowedChannels` | 允许哪些入口 | `[PLATFORM_DIRECT, AGENT_ORCH]` |
| `allowedScopes` | 允许哪些作用域 | `[TENANT, PLATFORM]`（ALL 只读） |
| `requiredPermission` | 所需权限码 | `platform.resource.manage` / `tenant.rag.manage` |
| `dataSensitivity` | 数据敏感度 | `BUSINESS_CONTENT` |
| `requiresTicket` | 是否必须票据 | G 档写 = 治理票据；Agent 写 = 代理票据 |
| `writePolicy` | 写路径约束 | 仅 `scope='PLATFORM'` ∧ 哨兵桶 / `tenant_id=ctx.tenantId` |

价值：Agent 想在对话里"帮忙录入语料"时，先查注册表——若该能力的 `allowedChannels` 不含 `AGENT_ORCH`，或当前 scope 不在 `allowedScopes`，**在工具调用前就拒绝**，而不是靠 Agent 自觉。

### 3.3 TicketBroker（唯一票据签发/验签）

把现在分散在两个 Service 的票据逻辑收敛为一个组件，对外只暴露两种票，但用**同一套 claims schema 和验签路径**：

| 票种 | 签发票 | 域 | 关键 claims | TTL | 用途 |
|---|---|---|---|---|---|
| 执行票 | `issueExecution(tenantId, loginName)` | 租户域 | `tenant_id`、真实角色交集、`jti` | 60s | 平台运营代替指定成员跑一次 Run |
| 治理票 | `issueGovernance(scope, capability)` | 平台域 | `rag_scope`、`RAG_CORPUS_GOVERNANCE`、`jti` | 300s | 平台层写全局语料 |
| **代理票（新增）** | `issueDelegation(parentCtx, capability, scope)` | 随目标域 | `parent_principal`、`scope`、`capability`、`purpose`、`jti` | 60s | **Agent 编排代为调用能力** |

统一验签纪律：验签成功后把 `{domain, principal, scope, capability, jti}` 写入 request attribute（对齐 `RagJwtAuthFilter.java:228-255` 的 `RagCallerAttributes` 做法）；下游**只认 attribute，不认请求头**。票据只存内存、换作用域/换上下文即作废、按 jti 落审计。

---

## 4. 双入口如何兼容（直接回答用户诉求）

### 4.1 入口①：平台层直接录入

```
控制台表单 → 平台令牌(aud=admin-console-api)
          → IdentityResolver 得 PLATFORM_USER
          → ScopeResolver 由用户"显式选择"的作用域分段控件得 scope（G/T/A）
          → G 档写：TicketBroker.issueGovernance(PLATFORM, rag.corpus.ingest)
          → CapabilityRegistry 校验 allowedChannels 含 PLATFORM_DIRECT
          → 执行器按 scope 选数据面（G→rag_ops 会话窗写哨兵桶 / T→rag_app RLS）
          → 审计：platform_audit_event(actor=jti, scope, capability, correlationId)
```

现状已基本具备（G/T/A 三档 + 治理票据 + 会话窗），**只需把入口接入统一 `InvocationContext`**，并关闭 R1（禁止任何隐式租户回落）。

### 4.2 入口②：Agent 编排对话里录入（新增统一路径）

**核心设计：Agent 永远不直接持有平台令牌写数据，也不借用他人的租户令牌；它只能申请一张"代理票"。**

```
Agent 对话 → 编排器决定调用工具 rag.corpus.ingest
          → 工具适配层查 CapabilityRegistry：
              allowedChannels 含 AGENT_ORCH ? scope 合法 ? ——否则拒绝并给稳定 reasonCode
          → TicketBroker.issueDelegation(parentCtx, 'rag.corpus.ingest', scope=TENANT)
              · 绑定父主体（谁让 Agent 做的）
              · 绑定目标租户（来自 parentCtx，不由 Agent 在参数里自报）
              · 绑定能力、目的、correlationId、60s TTL
          → 统一执行器（与入口①同一个）按 scope 落数据面
          → 审计：correlationId 与父对话串起来；thinkingAllowed=false（Agent 通道恒不漏草稿）
```

### 4.3 两条入口的收敛点（一张表看懂）

| 环节 | 平台直录 | Agent 编排 | 是否统一 |
|---|---|---|---|
| 身份来源 | 平台登录令牌 | 父对话的 InvocationContext | ✅ 同一 IdentityResolver |
| 作用域来源 | 用户显式选择 | 继承父 ctx，Agent 不得自报 | ✅ 同一 ScopeResolver，均禁止隐式 |
| 票据 | 治理票 | 代理票 | ✅ 同一 TicketBroker 签发/验签 |
| 能力校验 | CapabilityRegistry | CapabilityRegistry | ✅ 完全同一 |
| 数据执行 | 同一执行器按 scope 选数据面 | 同一执行器 | ✅ 完全同一 |
| 推理草稿 | 不涉及 | 恒 `thinkingAllowed=false` | ✅ 由 channel 派生，不随 ctx 流转 |
| 审计 | platform_audit_event | platform_audit_event + 父对话 correlationId | ✅ 分域但可关联 |

> **这就是"兼容两种录入而不泄露"的答案**：两条入口在**判定层完全统一**，差异只存在于**入口层"谁触发"**；一旦归一为 `InvocationContext`，后面是同一条路。泄露最常见的根因——"Agent 走的那条路少了一道权限检查"——在结构上不再可能发生。

---

## 5. 防泄露：四道闸 + 逐条威胁对策

| 威胁（STRIDE） | 对策 | 收口位置 | 反证 |
|---|---|---|---|
| **身份伪造 / 跨身份域**（Spoofing） | 令牌层 aud + principal_type 双校验互拒；下游只认 request attribute 不认头 | IdentityResolver + 两条 FilterChain | 平台令牌打 `/api/knowledge/**` → 401；租户令牌打 `/api/platform/**` → 401 |
| **隐式作用域跨租户读**（Information disclosure） | 关闭默认租户回落；scope 必须显式；T 档 `tenant_id = ctx.tenantId` 硬绑定 | ScopeResolver + SQL 谓词 | 不带 scope 的录入请求 → 400/403，绝不返回默认租户数据 |
| **票据交叉误用 / 提权**（Elevation） | 单一 TicketBroker；claims 带 `capability + scope + channel`；验签时三者必须匹配本次调用，不匹配即 403 | TicketBroker + PolicyGate | 治理票拿去打 Agent 工具 → 403；执行票当平台票用 → 401 |
| **推理草稿 / 凭据泄露**（Information disclosure） | `thinkingAllowed` 由 channel 派生且不进 InvocationContext 的跨通道字段；Bearer 只写 attribute，绝不打印/落库/进 SSE | IdentityResolver + AgentChatController | Agent 通道 SSE 全流无 `event: thinking` |
| **数据面越权写全局桶**（Tampering/Elevation） | `rag_app` NOBYPASSRLS 闸门不放宽；G 档写只经 rag_ops 会话窗 + 哨兵桶等值护栏；窗口外取连接即异常 | 数据面 + 会话窗 | `rag_app` 仍写不出全局行（反证脚本）；未接 ops 通道 → 503 不回落 |
| **审计断链**（Repudiation） | 每次能力调用（含票据签发）写审计，带 `capability/scope/correlationId/jti`；票据 claim 档位列必须落表（U-8 已闭环，本方案固化为 schema 约束） | AuditSink | 任一 G 档写在审计表查不到 scope+actor → 失败 |
| **Agent 被诱导越权**（Tampering） | Agent 自报的 tenantId / scope 一律不接受，只能继承父 ctx；能力不在 allowedChannels 直接拒 | CapabilityRegistry | Agent 传 `tenantId=其它租户` → 400，不发生任何写 |

---

## 6. 两个例子的端到端时序

### 6.1 RAG 语料录入 · 双入口

```
[平台直录]  运营在录入页选"全局档" → 领治理票 → 上传载体 → 解析 → 校对 → 校验决定
            → 每步 G 档写都校验票的 rag_scope == 请求 scope，写哨兵桶，落平台审计
[Agent 录入] 运营在对话里说"把这份教材录入全局语料库"
            → 编排器想调 rag.corpus.ingest
            → 注册表发现：G 档写 allowedChannels 不含 AGENT_ORCH（全局语料只允许平台直录）
            → 拒绝，返回稳定 reasonCode + "请到录入页操作"提示
            → 若改说"录入到某租户"：继承父 ctx 的租户 → 代理票(scope=TENANT) → 同一执行器写租户桶(RLS)
```
> 这一条同时回答了"如何兼容"与"如何不泄露"：**Agent 能帮忙，但只能在它该在的作用域里帮；全局写这种高风险动作按注册表声明只留平台直录一条路。**

### 6.2 Agent 编排对话 · 双入口

```
[平台链 AI 实验室] 平台令牌 + 显式目标租户（或显式"平台视角"只读，不回落默认租户）
                  → thinkingAllowed=true（仅平台域内展示）
                  → 下游 rag 调用：平台令牌允许合法跨租户，但 capability 限定可见字段
[租户链 学生前台]  租户令牌 → 租户从令牌取，请求体带 tenantId 直接 400
                  → thinkingAllowed=false，恒不漏草稿
[Agent 代为录入]  见 §4.2 代理票路径
```

---

## 7. 已登记未决项的本方案处置

| 项 | 现状 | 本方案处置 |
|---|---|---|
| R1 默认租户回落 | 平台链隐式回落 | **删除或降级**：默认租户仅允许 `scope=PLATFORM` 的只读能力；任何写/含正文请求必须显式 scope，否则 400 |
| U-9 ALL 档 501 | 全部租户盘点未实现 | 保持 `ALL` 只读 + 不出正文（红线）；若要正文单独立项，走"受控支持访问"短时授权 |
| R-4 全局语料准入门槛 | 只有部署级门槛 | 在 `CapabilityRegistry` 为 G 档写增加 `admissionPolicy` 声明，逐租户召回基线未测前维持 fail-closed |
| `X-Tenant-Min-Trust-Tier` 可伪造 | 影响门槛松紧 | 该头降级为"提示"，权威门槛来自 InvocationContext 中已验签的 authzVersion |
| `rag.security.enabled=false` 逃生门 | 标记 unverified、禁用平台档 | 生产 profile 禁止关闭；关闭即拒启，不静默降级 |
| U-10 反证脚本测不到票据 | 脚本无法签合法票 | 票据用例钉在 ServiceTest + ControllerTest（现状已如此），本方案要求新增代理票的同款测试 |

---

## 8. 迁移路径（分三刀，不推倒重来）

### 第 1 刀：收口判定（低风险，先做）
- 新增 `InvocationContext` / `Channel` / `Scope` 值与 `IdentityResolver`、`ScopeResolver`、`CapabilityRegistry` 骨架。
- **关闭 R1**：把默认租户回落改为只读限定 + 审计标注；新增"无显式 scope 写请求被拒"单测。
- Agent 双端点改为共用同一个 resolver 产出 ctx（消除两处 `if` 分支），保持对外契约不变。
- 验收：现有 71+ 用例全绿；新增 R1 反证用例红→绿。

### 第 2 刀：统一票据（中风险）
- `TicketBroker` 收敛执行票/治理票的签发与验签；新增代理票。
- 下游统一改为"只认 request attribute"，移除散落的请求头读取。
- 票据 claims 增加 `capability + channel + purpose`；验签做三元组匹配。
- 验收：交叉误用反证（治理票打工具、执行票当平台票）全部 403/401。

### 第 3 刀：能力注册 + 双入口接入（中风险）
- RAG 录入、Agent 编排两个能力首批注册；按 §4 接入两条入口。
- Agent 侧工具调用统一走"先查注册表 → 申请代理票 → 执行器"。
- 审计追加 `capability/scope/channel/correlationId` 字段（分域表各自加列）。
- 验收：§5 反证矩阵逐条通过；U-8 scope 列固化为非空约束。

---

## 9. 验收矩阵（反证纪律：撤掉修复点会红吗）

| # | 验收项 | 反证方式 |
|---|---|---|
| A1 | 两条入口能力校验完全一致 | 同一 `rag.corpus.ingest` 分别经直录/Agent 调用，权限不足时返回同一 reasonCode |
| A2 | 无隐式作用域 | 平台链不发 tenantId 且未显式 scope 的写请求 → 400，默认租户数据零返回 |
| A3 | 票据不可交叉误用 | 治理票打 Agent 工具端点 → 403；执行票打平台端点 → 401 |
| A4 | Agent 不自报租户/作用域 | 对话中传 `tenantId=其他租户` → 400，审计无任何写记录 |
| A5 | 推理草稿不出域 | Agent 通道 SSE 抓包无 `event: thinking` |
| A6 | 数据面闸门未放宽 | `rag_app` 写全局行仍失败；ops 通道未接入 G 档写 → 503 不回落 |
| A7 | 审计可回溯 | 任一 G 档写在 `platform_audit_event` 查到 scope + actor(jti) + correlationId |
| A8 | 令牌互拒不变 | 两域 JWT 互打对方端点 → 401（既有负向测试保持绿） |

---

## 10. 未决问题（✅ 已于 2026-11-18 全部按建议拍板并执行）

| # | 问题 | 备选 | 决策 | 落地证据 |
|---|---|---|---|---|
| Q1 | 全局语料写是否允许 Agent 入口 | 允许（简化）/ 禁止（安全） | ✅ **禁止**：G 档高风险写只留平台直录，符合最小特权 | `PolicyGate` 闸门 7 `AGENT_GLOBAL_WRITE_DENIED` + `PolicyGateTest#agentGlobalWriteDenied` |
| Q2 | 默认租户回落如何处置 | 删除 / 降级只读 / 保留 | ✅ **降级只读**：回落的租户视为非显式作用域，写恒拒、读仍可 | `InvocationContext.scopeExplicit` + 闸门 5 `SCOPE_IMPLICIT_WRITE_DENIED` + `resolveInvocation` |
| Q3 | 代理票是否引入新 JWT 类型 | 新 claim / 复用现有票据 | ✅ **复用执行票结构 + capability/channel claim** | `TicketBroker.DelegationSpec/delegationClaims` + 两张票的 `toClaims()` |
| Q4 | ALL 档是否开放正文下钻 | 开放 / 维持只读元数据 | ✅ **维持只读元数据**：正文走独立受控支持流程 | `Scope.ALL.allowsWrite()=false` + 闸门 4 `SCOPE_READ_ONLY` |

---

## 附录 A · 关键证据锚点

| 主题 | 文件:行号 |
|---|---|
| Agent 双端点 + 共享编排器 | `platform/agent-api/.../api/chat/AgentChatController.java:32-116` |
| 身份解析 + 默认租户回落 + thinkingAllowed + Bearer 透传 | `platform/platform-bootstrap/.../RequestAgentChatIdentityResolver.java:44-107` |
| 双链令牌互拒 | `platform/platform-bootstrap/.../SecurityConfiguration.java:170-263`；`agent-infrastructure/.../JwtTokenService.java:243-248` |
| 执行票证签发 | `platform/agent-api/.../PlatformExecutionTicketService.java:186-275`、`PlatformExecutionTicketController.java:62-86` |
| 治理票据签发 | `platform/agent-api/.../PlatformRagGovernanceTicketService.java:187-241`、`PlatformRagGovernanceTicketController.java:44` |
| RAG 作用域解析 + 鉴权 | `rag/.../RagCorpusScope.java:48-109`、`rag-bootstrap/.../RagJwtAuthFilter.java:143-255`、`rag-management/.../RagManagementController.java:2745-2895` |
| 数据面隔离 | `deploy/sql/rag/01_rag_ddl.sql`、`rag-infrastructure/.../GuardedRagOpsDataSource.java:35-47`、`rag-bootstrap/.../RagStartupSelfCheck.java:88-97` |
| 票据只存内存（前端纪律） | `admin-console/src/api/executionTicketApi.ts:64-125`、`src/api/ragTicket.ts`、`src/features/rag/useRagGovernanceTicket.ts` |
| 上游依据 | `docs/05_archive/重规划/平台身份与租户身份分离改造方案.md`、`admin-console_Agent操作_租户绑定松绑方案.md`、`RAG语料平台级治理与租户三档作用域方案.md` |

---

> 本方案自 v1.1 起已从规划文档转为**已落地基线**：Q1–Q4 已拍板，第 1–3 刀代码已合入，platform 全量测试绿。非目标见附录 B。

---

## 附录 B · 落地清单与非目标（2026-11-18）

### B.1 新增文件（统一抽象层，纯逻辑 / 零 Spring / 零 DB）

| 文件 | 职责 |
|---|---|
| `platform/agent-ports/.../ports/invocation/Channel.java` | 三入口枚举 + `thinkingAllowed()` 由入口派生（R2） |
| `platform/agent-ports/.../ports/invocation/Scope.java` | PLATFORM/TENANT/ALL 三档 + 严格 parse（禁隐式推断） |
| `platform/agent-ports/.../ports/invocation/InvocationContext.java` | 统一上下文枢纽 + 不变量自校验 |
| `platform/agent-ports/.../ports/invocation/TicketClaims.java` | 统一票据声明 + 三元组匹配（R3） |
| `platform/agent-ports/.../ports/invocation/Capability.java` | 能力注册表（3 能力 + defaults 冻结） |
| `platform/agent-ports/.../ports/invocation/PolicyGate.java` | 7 道闸门统一裁决 + 稳定 reasonCode |
| `platform/agent-ports/.../ports/invocation/TicketBroker.java` | 代理票规格与统一匹配入口（Q3） |

### B.2 修改文件（既有链路，全部向后兼容）

| 文件 | 改动 |
|---|---|
| `RequestAgentChatIdentityResolver.java` | 新增 `resolveInvocation()`，产出统一上下文；旧 `resolve()` 逐字不动 |
| `PlatformExecutionTicketService.java` | `ExecutionTicket.toClaims()`；审计写入 capability/channel |
| `PlatformRagGovernanceTicketService.java` | `GovernanceTicket.toClaims()`；审计写入 capability/channel |
| `PlatformAuditPort.java` | `PlatformAuditEvent` 加 capability/channel 两列 + 旧 12 参兼容构造器 |
| `JdbcPlatformAuditRepository.java` | INSERT/SELECT/mapEvent 同步新列（位置顺序对齐） |
| `deploy/sql/platform/01_platform_ddl.sql` | 审计表加两列 + COMMENT |
| `deploy/sql/platform/patch_platform_audit_capability_channel.sql` | 【新增】存量库幂等补丁 |

### B.3 新增测试（反证纪律）

| 测试类 | 覆盖 |
|---|---|
| `PolicyGateTest`（13） | R1 隐式写拒 / R2 草稿入口派生 / R3 票据跨域跨档跨能力过期 / Q1 全局 Agent 写拒 / ALL 只读 / 不变量 / 注册表冻结 |
| `TicketBrokerTest`（3） | 代理票维度绑定 / 租户会话强制 TENANT 域 / match 同语义 |
| `TicketClaimsMappingTest`（4） | 两票 toClaims 正确性 / 执行票当治理票用被拒 / 治理票当代理票用被拒 |
| `RequestAgentChatIdentityResolverTest`（+3） | 显式/默认租户的 scopeExplicit / 草稿随入口派生 |
| `PlatformAuditEventCapabilityTest`（2） | 旧构造器 null 兼容 / 14 参字段位置 |

### B.4 验证结果

- `mvn -o test`（platform 全 reactor）：**BUILD SUCCESS**，Tests run 3294 / Failures 0 / Errors 0 / Skipped 42。
- 新增单测合计 25 项，全部绿。

### B.5 非目标与边界（诚实声明）

1. **未改 RAG 侧代码**：RAG 是独立 Maven 构建、不依赖 `agent-ports`，且其 G/T/A 三档 + `X-Rag-Scope` + `rag_ops` 会话窗已是 fail-closed（既有反证 102+39 项）。本方案在**平台侧**完成判定收口；RAG 数据面保持原样，不重复造轮子。
2. **未给 `PlatformAuditEvent` 加 DB CHECK**：capability/channel 为自由文本，由代码侧 `Capability` 注册表约束；如后续要求库侧强约束，可追加 CHECK（需与 `ticket_scope` 同样的规范集纪律，避免 503 变 500）。
3. **代理票已接真实签发（2026-11-22 增量），编排工具层消费待代写工具落地**：`DelegationTicketService`（agent-api）已实现真实 JWT 签发 —— 绑定父主体/目标租户（Agent 自报一律拒）、CapabilityRegistry + PolicyGate 七闸门前置裁决（预览声明自洽自检）、复用执行票结构 + `capability`/`channel`/`scope` claim（Q3，`JwtTokenService.issueDetailed` 新增 extraClaims 重载）、TTL 60s；`PolicyGate` 已注册为全进程单例 Bean（AgentChatConfiguration）。**工具层接入边界**：编排层（AgentChatOrchestrator）当前只有读检索（agent.chat.orchestrate，requiresTicket=false，无需代理票）；`rag.corpus.ingest` 代写工具落地时再消费本服务（防"边接入边漂移"）。
4. **DDL 补丁需 DBA 在存量库执行**：dev 库可整体重置（新 DDL 已含两列）；存量库按 `patch_platform_audit_capability_channel.sql` 幂等升级。
