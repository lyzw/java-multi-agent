# Java SaaS 平台 Agent 功能模块：完整开发规划与技术架构方案（改造版）

**文档目标**：面向企业级、多租户、可扩展、可审计的 Java SaaS 平台，给出可落地的 Agent 模块架构、数据模型、接口、安全与分阶段交付方案。

**核心原则（不可妥协）**  
> 模型只负责提出计划与工具调用建议；平台负责授权、调度、执行、持久化与最终结果交付。

**改造基线**  
- 首期只做「安全可审计的单 Agent 闭环」，再扩展编排与插件。  
- 模块化单体 + 独立 Worker，不做过早微服务拆分。  
- 所有写操作可幂等或可补偿；执行预算硬限制；租户 ID 只来自认证上下文。

---

## 一、项目目标与范围

### 1.1 建设目标

| 目标 | 说明 |
|------|------|
| 智能任务执行 | 目标理解、工具调用、执行反馈、结果汇总、失败恢复 |
| 企业级多租户 | 租户隔离、用户授权、Agent/工具级权限、数据范围控制、操作审计 |
| 开放式扩展 | 标准化工具协议、模型适配器、事件机制接入第三方系统 |
| 可运营可治理 | 执行追踪、成本统计、配额控制、版本管理、故障恢复 |

### 1.2 首期功能边界（强制收敛）

| 模块 | 首期（MVP / 生产可用） | 明确不做（至少首期不做） |
|------|------------------------|---------------------------|
| Agent 管理 | 创建、配置、发布、版本管理（不可变版本） | Agent 模板市场、版本灰度 |
| 执行引擎 | 单 Agent、ReAct 循环、工具调用、可恢复状态机 | 多 Agent 协作、复杂 DAG 编辑器 |
| 任务管理 | 异步任务、状态机、租约、重试、取消、幂等 | 长流程补偿引擎、定时任务平台 |
| 工具系统 | HTTP、内部 Java 服务、受控远程工具 | 插件市场、不可信沙箱插件、任意脚本 |
| 上下文 | 会话、消息历史、Token 预算裁剪、工具结果摘要 | 长期记忆、知识图谱 |
| 安全体系 | 租户隔离、RBAC、资源级授权、工具执行时授权、高风险审批 | 完整 ABAC 策略引擎 |
| 运营管理 | 调用日志、Token/成本、审计 | 自动评测优化、完整商业计费产品化 |
| 编排 | 顺序 + 简单条件 + 人工审批（可选） | Temporal、可视化复杂工作流 |

**明确非首期能力**：自主执行任意代码、无限循环式自主规划、无人工确认的高风险操作、任意租户间共享记忆。

### 1.3 架构红线（必须写入规范与 Code Review 清单）

1. 模型永远不能获得直接执行权限。  
2. 所有写操作必须可幂等或可补偿。  
3. 租户 ID 只来自已验证的认证上下文，不信任客户端与模型。  
4. 执行预算为硬限制，不可被 prompt / options 突破。  
5. 发布后的 Agent 版本配置不可变；run 绑定 `agent_version_id`。  
6. 外部内容（网页 / 工具返回 / RAG）一律视为 untrusted。  
7. 完整敏感数据默认不进入 Trace 标签与普通日志。  
8. 工具授权与白名单不能由模型自行修改。

---

## 二、系统整体架构设计

### 2.1 总体架构

采用**控制面与执行面分离**：

```
接入层
  Web 控制台 · OpenAPI · SDK · SSE

平台控制面（Control Plane）
  API Gateway · IAM · Tenant · Agent 管理
  配额计费 · 版本发布 · Tool Registry · 策略管理

执行面（Agent Runtime / Worker）
  任务状态机 · Loop Executor · Tool Router
  Context Assembler · Guardrails · Human Approval
  租约管理 · 幂等控制 · 预算熔断

异步基础设施
  消息队列（任务分发、事件、延迟重试）

外部能力
  LLM / Embedding · 业务服务 · 受控远程工具

数据与治理
  PostgreSQL · Redis · 对象存储 ·（按需）pgvector
  OpenTelemetry
```

### 2.2 模块边界与职责

**首期推荐物理模块（收敛后）**：

| 模块 | 核心职责 | 部署建议 |
|------|----------|----------|
| agent-api | REST/SSE、鉴权、参数校验、幂等入口 | 与控制面合并 |
| agent-core | 领域模型、状态机、Loop 执行、上下文组装 | 代码模块 |
| agent-tool | 工具 SPI、授权、校验、副作用策略、调用 | 代码模块 |
| agent-infra | 持久化、Redis、MQ、模型适配、对象存储 | 代码模块 |
| agent-worker | 队列消费、租约、执行、续租 | **独立部署** |
| agent-admin | 管理配置、运行详情、审批台 | 可与现有 SaaS Admin 合并 |

**原则**：
- 模块之间通过明确领域接口协作。
- HTTP 请求线程**禁止**执行可能持续数分钟的 Agent 任务：创建 run → 返回 `runId` → Worker 执行 → SSE/轮询推送进度。
- 仅在出现独立扩缩容、故障隔离或团队自治需求时再拆服务。

### 2.3 一次 Agent 执行的完整链路

```
User → API Gateway → IAM/Policy
     → 创建 run（绑定 version、budget、idempotency_key）→ 202 + runId
Worker 抢占任务（租约）
     → 加载上下文与状态
     → 调用模型
     → 若工具调用：执行时鉴权 → Schema/业务校验 → 副作用策略 → 执行 → 持久化步骤
     → 预算检查 / 继续或结束
     → 写最终结果 + 事件流
Client ← SSE / 轮询
```

**强制点**：
- 工具权限在**每次执行前**检查，而非仅在创建 Agent 时检查一次。
- Worker 通过数据库条件更新获取租约，避免多实例重复执行。

---

## 三、Agent 核心能力设计

### 3.1 Agent Runtime：可恢复状态机

不要把逻辑塞进不可恢复的 while 循环。Runtime 以持久化状态机为核心。

**状态定义**：

| 状态 | 含义 |
|------|------|
| CREATED | 任务已创建 |
| QUEUED | 等待 Worker |
| RUNNING | 执行中（持有租约） |
| WAITING_APPROVAL | 等待人工审批 |
| WAITING_EXTERNAL | 等待外部回调 |
| RETRYING | 计划重试 |
| PAUSED | 人工/预算暂停 |
| COMPENSATING | 写操作失败后的补偿中 |
| SUCCEEDED | 成功 |
| FAILED | 失败 |
| CANCELLED | 已取消 |
| TIMED_OUT | 超时 |

**设计要点**：
1. 状态迁移有合法校验；非法迁移直接拒绝并记审计。  
2. 每步保存：输入摘要、输出摘要/引用、耗时、错误码、尝试次数、幂等键。  
3. 支持从最近安全检查点恢复，而非整 run 重头跑。  
4. 工具副作用必须幂等控制。  
5. 最大迭代、总时长、Token、工具调用数、费用为**硬限制**。

**核心接口（首期）**：

```java
public interface AgentLoopExecutor {
    RunResult execute(AgentExecutionContext context);
}

public interface AgentStepExecutor {
    StepResult execute(AgentStepContext context);
}

public interface AgentStateRepository {
    void save(RunState state);
    RunState load(String tenantId, String runId);
    boolean tryAcquireLease(String tenantId, String runId, String workerId, Duration lease);
    void renewLease(String tenantId, String runId, String workerId, Duration lease);
}

public record ExecutionBudget(
    int maxIterations,
    Duration maxDuration,
    long maxInputTokens,
    long maxOutputTokens,
    int maxToolCalls,
    BigDecimal maxCost
) {}
```

**说明**：首期不单独引入 `AgentPlanner` 作为硬依赖。ReAct 中模型本身承担规划；固定流程走 L2 编排，避免多一次 LLM 调用与计划漂移。

### 3.2 任务编排能力（L1 / L2 边界写死）

| 层级 | 实现 | 适用场景 | 首期 |
|------|------|----------|------|
| L1 Agent Loop | 模型决策 + 工具调用循环 | 问答、查询、轻量自动化 | **必做** |
| L2 Workflow | 固定节点、简单条件、人工审批 | 审批、报表、固定流水线 | **基础可选** |
| L3 Multi-Agent | 主从分派 | 复杂跨领域 | 明确延后 |

**约定**：
- 开放目标、步骤由模型决定 → 用 L1。  
- 步骤固定、需强审计与可重放 → 用 L2。  
- **禁止**在 L1 的 prompt 里硬编码 10 步固定流程。

L2 首期节点：`LLM_NODE`、`TOOL_NODE`、`CONDITION_NODE`、`HUMAN_APPROVAL_NODE`、`END_NODE`。  
并行、子 Agent、复杂补偿等后续再加。

声明式 DSL 需版本化 + 发布前静态校验；每个节点定义输入输出 Schema、超时、重试、错误分支、幂等键。

**Temporal**：仅当出现小时级等待、跨服务强补偿、进程重启后必须恢复的复杂流程时再评估；引入时 LLM/工具一律进 Activity，审批用 Signal，禁止 JVM 线程等待当持久化。

### 3.3 工具调用系统

模型只提出工具调用请求；实际执行由应用层完成，并控制授权、校验与结果。

**推荐调用链**：
1. 按 Agent + 租户权限筛选可用工具  
2. 向模型提供名称、描述、JSON Schema  
3. 接收模型返回的 name + arguments  
4. 校验工具是否在当前授权集合  
5. Schema + 业务参数校验  
6. 副作用策略（只读 / 需审批 / 拒绝）  
7. 配额、幂等键、超时检查  
8. 隔离执行器调用  
9. 结构化结果 + 错误码回传；大结果做 modelView 摘要  
10. 审计与成本记账  

**工具接口**：

```java
public interface AgentTool {
    ToolDefinition definition();
    ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments);
}

public record ToolDefinition(
    String name,
    String version,
    String description,
    String inputSchema,
    String outputSchema,
    ToolSideEffect sideEffect
) {}

public record ToolSideEffect(
    boolean readOnly,
    boolean hasExternalSideEffect,
    boolean requiresApproval,
    Set<String> dataScopes
) {}

public record ToolResult(
    boolean success,
    String code,
    Object data,          // 完整结果（可外置）
    Object modelView,     // 给模型的摘要视图
    String resultRef,     // 大结果对象存储引用
    String traceId
) {}
```

`ToolExecutionContext` 必须由服务端构造：可信 tenant、user、授权、runId、stepId、超时、traceId。**禁止**从模型参数读取这些字段。

**工具分类**：只读 / 写入 / 高风险 / 基础 / 委托。  
写入与高风险：显式授权、参数白名单、幂等键、必要时人工确认。不能仅靠系统提示词约束模型。

**幂等键建议**：`tenantId + runId + stepId + toolName + argsDigest`。

### 3.4 上下文管理（工程化裁剪）

上下文 ≠ 整段聊天记录。首期采用可执行 Pipeline：

```
1. System prompt（版本化，不可被用户消息覆盖）
2. Agent 配置指令（绑定版本）
3. 任务目标 + 当前执行摘要
4. 最近 N 轮消息（含 tool call / tool result）
5. （可选）RAG 结果（权限过滤 + 来源引用）
6. 长期记忆：首期不做
```

**强制策略**：
- Token 预算按角色配额分配（示例：system 20% / history 40% / tools 25% / rag 15%，可配置）。  
- 工具结果：`raw` 存库/对象存储，`modelView` 截断或摘要后给模型。  
- 大结果自动生成关键字段摘要 + `resultRef`，需要时再二次查询。  
- 摘要保留 `source_message_ids`，便于审计回放。  
- 发送模型前做敏感信息过滤与数据权限检查。  
- Spring AI ChatMemory 可用，但**权威实现是平台自己的 `ContextAssembler`**。

### 3.5 模型接入与路由

```java
public interface ModelGateway {
    ModelResponse chat(ModelRequest request);
    Flux<ModelStreamChunk> stream(ModelRequest request);
    ModelMetadata metadata();
}
```

路由考虑：任务复杂度、延迟、价格、结构化/工具能力、数据驻留、限流与故障转移。  
模型失败可降级备选模型，但**不得无条件重放已产生外部副作用的任务**。

以 Spring AI 处理常见调用与流式输出，业务只依赖自有 `ModelGateway`，避免领域模型绑定框架 API。

---

## 四、多租户隔离与权限模型

### 4.1 安全模型

**Tenant Isolation + RBAC + Resource-level Authorization + Tool-level Policy**

| 层级 | 示例 | 校验时机 |
|------|------|----------|
| 平台级 | 平台管理员 | 管理接口 |
| 租户级 | 租户管理员、成员 | 请求入口 |
| Agent 级 | 创建/编辑/发布/执行 | Agent 操作时 |
| Run 级 | 查看/取消/重试 | Run 资源访问时 |
| Tool 级 | 调用 crm.customer.read | **每次工具执行前** |
| 数据级 | 仅本部门客户 | 业务查询时 |

### 4.2 数据隔离

首期默认：**共享库共享表 + `tenant_id`**，配合 PostgreSQL RLS 作纵深防御。

关键措施：
1. 租户 ID 仅从认证上下文获取。  
2. 数据访问层强制注入租户条件。  
3. RLS 会话变量事务级设置，连接归还前清理，防连接池泄漏。  
4. 联合唯一键含租户范围，如 `(tenant_id, agent_id)`。  
5. Redis Key、对象路径、向量过滤、缓存键、消息事件均带可信租户范围。  
6. 日志脱敏；跨租户运营查询需专门授权。

### 4.3 角色建议

- `PLATFORM_ADMIN`  
- `TENANT_ADMIN`  
- `AGENT_DEVELOPER`  
- `AGENT_OPERATOR`  
- `AGENT_VIEWER`  
- `AUDITOR`  

Agent 发布时绑定工具权限清单。即使模型请求未授权工具，服务端必须拒绝。  
区分调用者身份与 Agent 服务身份：工具调用在明确委托范围内执行，不继承服务账号全部权限。

### 4.4 高风险控制

- 工具入参/出参 Schema 验证 + 业务校验  
- 外部内容一律 untrusted  
- 工具白名单与租户授权不可由模型修改  
- 写操作/批量/敏感导出可强制审批  
- 出站域名白名单 + 代理 + SSRF 防护  
- 密钥走 KMS/Vault，绝不进 Prompt、历史、普通日志  
- 执行循环硬预算与熔断  

参考：OWASP Top 10 for LLM Applications（作为设计评审输入，正式选型前核对版本与适用范围）。

---

## 五、与 SaaS 其他服务的集成

Agent 是智能执行能力，不重复建设用户、计费、业务数据系统。

| SaaS 服务 | 集成方式 | 关键约束 |
|-----------|----------|----------|
| 用户与 IAM | OAuth2/OIDC、JWT | 统一身份与授权上下文 |
| 租户与组织 | Tenant Context | 边界一致 |
| 业务服务 | REST/内部 RPC | **不绕过业务规则**；写操作优先调已有服务 |
| 知识库 | 检索 API | 检索时数据权限过滤 |
| 文件服务 | 预签名 URL / 文件 ID | 校验租户与权限 |
| 消息中心 | 领域事件、Webhook | 签名、重试、幂等 |
| 计费中心 | Token/工具/时长计量 | 按租户归集 |
| 审计服务 | 结构化事件 | 操作者、Agent、副作用 |
| 通知中心 | 站内信等 | 限制外发与重复 |

同步：低延迟只读短查询。  
异步：长任务走队列。  
Outbox 保证业务与事件一致；消费者幂等；Webhook 验签与防重放。

---

## 六、数据存储方案与核心数据模型

### 6.1 技术栈（首期最小集）

| 组件 | 用途 |
|------|------|
| PostgreSQL | Agent 定义、版本、会话、run、步骤、事件、工具元数据、权限、审计索引 |
| Redis | 缓存、限流、短期协调（**关键状态不以 Redis 为唯一真相**） |
| 消息队列 | 任务分发、事件、重试（Kafka 或 RabbitMQ 二选一） |
| 对象存储 | 大结果、附件、长轨迹归档 |
| pgvector | **有真实 RAG 需求再引入** |

### 6.2 核心表

所有租户表统一：`tenant_id`、创建/更新时间、必要删除标记。

| 表名 | 关键字段 | 用途 |
|------|----------|------|
| agent_definition | id, tenant_id, name, status | Agent 实体 |
| agent_version | agent_id, version, config_json, published_at | 不可变版本 |
| agent_tool_binding | agent_id, tool_id, policy_id | 工具授权 |
| agent_conversation | id, tenant_id, user_id, agent_id | 会话 |
| agent_message | id, conversation_id, role, content/ref, token_count | 消息 |
| agent_run | id, tenant_id, status, agent_version_id, budget_json, lease_*, idempotency_key | 任务主记录 |
| agent_run_step | run_id, step_id, status, attempt, idempotency_key, input_digest, output_ref | 步骤 |
| agent_run_event | run_id, seq, event_type, payload | 事件流 |
| agent_tool_definition | id, name, version, schema_json, side_effect | 工具定义 |
| agent_audit_log | tenant_id, actor_id, action, resource_id | 审计 |
| agent_usage_record | tenant_id, run_id, model, tokens, cost | 用量（追加写） |

**注意**：
- run 绑定执行时的 `agent_version_id`。  
- 版本发布后配置不可变。  
- 事件 `seq` 支持去重与 SSE 断点续传。  
- usage 追加写，禁止重试覆盖账单。  
- 消息与执行记录配置保留期与归档策略。  
- 完整 Prompt 默认不落库；存 hash + 必要摘要。

### 6.3 任务表示例 DDL（增强）

```sql
CREATE TABLE agent_run (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    agent_id         UUID NOT NULL,
    agent_version_id UUID NOT NULL,
    conversation_id  UUID,
    status           VARCHAR(32) NOT NULL,
    input_json       JSONB NOT NULL DEFAULT '{}',
    result_json      JSONB,
    budget_json      JSONB NOT NULL DEFAULT '{}',
    error_code       VARCHAR(64),
    idempotency_key  VARCHAR(128),
    lease_owner      VARCHAR(128),
    lease_until      TIMESTAMPTZ,
    parent_run_id    UUID,
    created_by       UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_agent_run_tenant_status
    ON agent_run (tenant_id, status, created_at DESC);

CREATE UNIQUE INDEX uk_agent_run_idempotency
    ON agent_run (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_agent_run_lease
    ON agent_run (status, lease_until)
    WHERE status IN ('QUEUED', 'RUNNING', 'RETRYING');
```

租约抢占示例语义：

```sql
UPDATE agent_run
SET status = 'RUNNING',
    lease_owner = :workerId,
    lease_until = now() + :lease,
    version = version + 1,
    started_at = COALESCE(started_at, now())
WHERE id = :runId
  AND tenant_id = :tenantId
  AND status IN ('QUEUED', 'RETRYING')
  AND (lease_until IS NULL OR lease_until < now())
  AND version = :expectedVersion;
```

---

## 七、API 接口设计

统一前缀：`/api/v1`  
REST 管理资源；SSE 推送进度；Webhook 通知外部；高频集成提供 Java SDK。

### 7.1 核心接口

| 方法 | 接口 | 说明 |
|------|------|------|
| POST | /agents | 创建 Agent |
| GET | /agents | 分页查询 |
| GET | /agents/{agentId} | 详情 |
| PATCH | /agents/{agentId} | 修改草稿 |
| POST | /agents/{agentId}/versions | 创建版本 |
| POST | /agents/{agentId}/publish | 发布指定版本 |
| POST | /agents/{agentId}/runs | 创建执行（幂等） |
| GET | /runs/{runId} | 任务状态 |
| GET | /runs/{runId}/steps | 步骤详情（排障） |
| POST | /runs/{runId}/cancel | 取消（协作式） |
| POST | /runs/{runId}/retry | 重试失败任务 |
| GET | /runs/{runId}/events | SSE / 事件订阅 |
| POST | /conversations | 创建会话 |
| GET | /conversations/{id}/messages | 消息 |
| GET | /tools | 授权范围内工具 |
| POST | /tools/{toolId}/test | 测试连接 |
| GET | /usage | 用量统计 |

身份上下文决定租户；禁止仅凭 URL 资源 ID 越权访问。

### 7.2 创建执行示例

```http
POST /api/v1/agents/ag_123/runs
Authorization: Bearer <token>
Idempotency-Key: req-20261017-001
Content-Type: application/json

{
  "conversationId": "conv_456",
  "input": { "message": "查询本月销售数据并生成摘要" },
  "options": { "stream": true, "maxIterations": 8 }
}
```

服务端对 `maxIterations` 等做**上限钳制**（只能更严，不能放大租户/Agent 预算）。

响应：

```json
{
  "runId": "run_789",
  "status": "QUEUED",
  "eventsUrl": "/api/v1/runs/run_789/events",
  "createdAt": "2026-10-17T08:00:00Z"
}
```

### 7.3 事件类型

`run.started` · `step.started` · `model.token` · `tool.started` · `tool.completed` · `approval.required` · `step.failed` · `run.completed` · `run.failed` · `budget.exceeded`

SSE `id` 对应持久化 `seq`；客户端用 `Last-Event-ID` 续传。

取消返回「取消请求已受理」，并区分：协作式取消、当前步骤是否可中断、是否需要补偿。

### 7.4 错误码（补充）

| 错误码 | 含义 |
|--------|------|
| TENANT_ACCESS_DENIED | 租户访问被拒 |
| RESOURCE_FORBIDDEN | 资源权限不足 |
| TOOL_NOT_ALLOWED | 工具未授权 |
| TOOL_SCHEMA_INVALID | 参数校验失败 |
| TOOL_SIDE_EFFECT_CONFLICT | 副作用策略冲突 |
| MODEL_RATE_LIMITED | 模型限流 |
| QUOTA_EXCEEDED | 租户配额超限 |
| BUDGET_EXCEEDED | 单次执行预算耗尽 |
| RUN_STATE_CONFLICT | 状态冲突 |
| LEASE_LOST | 租约丢失 |
| APPROVAL_REQUIRED | 需要审批 |
| RUN_TIMEOUT | 超时 |
| IDEMPOTENCY_CONFLICT | 幂等键冲突 |

错误含 `requestId`；不暴露堆栈、密钥、他租户数据。

---

## 八、可扩展机制（首期克制）

### 8.1 首期扩展方式

| 方式 | 适用 | 要求 |
|------|------|------|
| 内置 Java Tool | 平台自有业务 | 可信代码、统一发布 |
| HTTP 受控远程工具 | 第三方业务 API | URL 白名单、egress、Schema、授权 |
| MCP | 标准化接入（可选） | 不能替代平台权限与治理层 |

### 8.2 首期不做

插件市场、不可信进程沙箱、任意脚本插件、租户自助上传可执行包。

后续再引入：Plugin Registry、签名、租户启用、版本锁定、调用量统计、撤销机制。

MCP 仅作为协议适配之一；「能否被模型发现 / 当前租户能否用 / 能否访问具体资源」仍由平台决定。

---

## 九、关键技术选型

| 领域 | 首选 | 说明 |
|------|------|------|
| 语言 | Java 21 LTS | 并发与生态 |
| 框架 | Spring Boot 3.x | 企业基线 |
| AI | Spring AI + 自有 ModelGateway | 不绑定框架 API 到领域层 |
| 编排 | 自研状态机 + 基础 L2 | 复杂长流程再评估 Temporal |
| DB | PostgreSQL | 事务、JSONB、RLS |
| 缓存 | Redis | 限流与热点 |
| MQ | Kafka **或** RabbitMQ（选一） | 不两套并行 |
| 向量 | 按需 pgvector | 非首日必选 |
| 安全 | Spring Security + OAuth2/OIDC | 统一认证 |
| 实时 | SSE | 流式与事件 |
| 可观测 | OpenTelemetry + Prometheus + Grafana | 追踪与告警 |
| 部署 | Docker + Kubernetes | Worker 与控制面分扩缩 |

**决策摘要**：
1. Spring AI 用，但包在适配层后。  
2. Temporal 非 MVP。  
3. MQ 二选一。  
4. 模块化单体 + 独立 Worker，到第一个付费租户前不急于微服务。

---

## 十、部署与运维

### 10.1 拓扑

```
Ingress / Gateway / WAF / IAM
  → 控制面 Pod（API、配置、鉴权、配额）
  → Worker Pod（Runtime、工具、模型调用）
共享：PostgreSQL · Redis · MQ · 对象存储
外部：LLM · 业务服务 · 受控工具
运维：Tracing · Metrics · Logging · 告警
```

### 10.2 弹性

- 控制面：CPU、延迟、并发。  
- Worker：队列积压、等待时间、模型并发上限。  
- 模型网关：并发限制、排队、超时、熔断。  
- 扩容时靠租约 + 条件更新 + 幂等，**不单靠 Redis 锁**。

### 10.3 高可用

- PG 高可用与备份恢复演练  
- 队列持久化、死信、消费者恢复  
- 过期租约安全接管  
- 模型限流/重试/降级  
- 密钥轮换  
- 明确 RPO/RTO（需结合业务等级与成本确认，不能因上了 K8s 就默认具备灾备）

### 10.4 关键指标

API：QPS、P95/P99、错误率  
Agent：成功率、轮数、耗时  
模型：首 Token、生成延迟、Token、限流  
工具：成功率、超时、重试  
队列：积压、死信  
成本：租户/Agent/单任务成本、超预算次数  
安全：越权拒绝、审批等待、敏感访问告警  

`traceId` 关联 `tenantId, agentId, runId, stepId`；高基数敏感字段不进 Trace 标签。

### 10.5 测试分层

1. 单元：状态机、裁剪、校验、权限决策  
2. 集成：DB、MQ、模型适配、工具执行  
3. 契约：API、插件/业务服务 Schema  
4. Agent 评测：完成率、工具选择准确率、参数正确率、成本延迟（固定集 + Mock 工具）  
5. 安全：注入、跨租户、越权工具、SSRF、重复副作用、配额绕过  

---

## 十一、分阶段开发路线图（改造后：约 16–18 周生产 MVP）

### Phase 0｜第 1–2 周：架构与验证

- 领域模型、威胁建模、核心 ER、API 契约  
- 模型调用、工具调用、流式输出打通  
- 仓库、CI、开发环境  

**验收**：模型适配 + 工具调用 + 流式输出可演示。

### Phase 1｜第 3–7 周：MVP 闭环

- Agent CRUD、版本草稿与发布  
- Loop Runtime、模型适配、工具注册  
- 会话、消息、同步/短异步执行  
- 基础上下文裁剪、Token 统计、执行日志  
- 2–3 个真实业务工具（优先只读或低风险写）  

**验收**：可创建、发布、执行、查询历史；至少一个真实业务任务跑通。

### Phase 2｜第 8–12 周：生产可靠性与安全

- Worker、租约、超时、重试、取消、幂等、检查点  
- 工具副作用策略与人工审批  
- 租户隔离、RBAC、工具级授权、配额硬限制  
- SSE 断线恢复  
- 跨租户安全测试  

**验收**：Worker 重启可恢复；重复消费不产生不可逆副作用；越权与未授权工具调用被拒绝。

### Phase 3｜第 13–16/18 周：企业接入与生产化

- 对接 IAM、业务服务、计费计量、审计  
-（按需）知识库检索 + 权限过滤  
- K8s 部署、基础扩缩、监控告警  
- 压测、安全审计、小范围租户试点  

**验收**：身份/配额/计费/审计链路完整；约定门槛的压测与安全检查通过。

### 后续（非首期）

插件体系、长期记忆、复杂 DAG/Temporal、多 Agent、评测门禁产品化、灰度与 SLA。

**门禁**：安全验收通过前，禁止对不受控外部租户开放 Agent。

---

## 十二、团队配置（参考）

| 角色 | 人数 | 职责 |
|------|------|------|
| 技术负责人/架构师 | 1 | 架构、红线、质量门禁 |
| Java 后端 | 3–4 | Runtime、API、数据、工具、集成 |
| 前端 | 1 | Agent 配置、运行详情、审批台（首期不做复杂工作流编辑器） |
| 测试 | 1–2 | 自动化、评测集、安全与性能 |
| DevOps/SRE | 1 | CI/CD、监控、部署 |
| AI 应用 | 1 | Prompt、路由、评测（可兼职） |

架构师必须盯紧四条主链路：**租约、幂等、租户注入、工具授权**。

---

## 十三、Java 工程模块结构（改造后）

```
saas-agent-platform/
├── agent-api/                 # REST、SSE、OpenAPI
├── agent-core/                # domain + runtime + state machine + context
├── agent-tool/                # SPI、内置工具、授权与副作用策略
├── agent-infra/               # PG、Redis、MQ、ModelGateway、对象存储
├── agent-worker/              # 异步执行进程
├── agent-admin/               # 管理端（可合并）
└── agent-testkit/             # Mock 工具、评测与测试基础设施
```

原则：
- 领域模型不直接依赖 Spring AI、MQ、具体 DB 驱动。  
- 基础设施通过接口注入。  
- 跨租户操作统一安全上下文与数据访问规范。  
- 配置版本化；避免散落难维护的巨型 JSON 字段失控。

---

## 十四、主要风险与应对

| 风险 | 后果 | 应对 |
|------|------|------|
| 执行不可预测 | 成本失控 | 硬预算：迭代/Token/时长/费用/工具次数 |
| 跨租户泄露 | 严重安全事件 | 强制租户上下文、RLS、权限测试 |
| 重复副作用 | 重复发信/写入 | 幂等键、操作记录、补偿 |
| 模型限流故障 | 任务堆积 | 并发控制、熔断、退避、降级（不重放有副作用步骤） |
| 上下文膨胀 | 延迟与费用上升 | Token 预算、工具摘要、分层裁剪 |
| 恢复不正确 | 状态不一致 | 检查点、状态机、故障注入测试 |
| 框架 API 演进 | 升级困难 | 自有适配层、依赖锁定、契约测试 |
| 范围蔓延 | 延期 | 首期边界与红线强制执行 |

---

## 十五、最终推荐实施方案

**首期基线**

| 维度 | 方案 |
|------|------|
| 架构 | Spring Boot 模块化单体 + 独立 Worker |
| 引擎 | Spring AI + 自有 Runtime + 持久化状态机 + 租约 |
| 编排 | L1 必做；基础 L2（顺序/条件/审批）可选；Temporal 延后 |
| 数据 | PostgreSQL + Redis + 单一 MQ + 对象存储；按需 pgvector |
| 安全 | 租户隔离 + RBAC + 资源级 + 工具执行时授权 + 高风险审批 |
| 扩展 | 自有 Tool SPI + 受控 HTTP；MCP 可选；不做插件市场 |
| 交付 | 16–18 周生产 MVP；先闭环再治理再集成 |

**建设优先级**

1. 单 Agent 执行闭环（模型、工具、上下文、状态、审计）  
2. 多租户与安全底座  
3. 异步、租约、幂等、审批  
4. 企业集成与生产化  
5. 插件、知识、多 Agent、规模化运营  

**一句话**：模型建议，平台做主。守住授权、调度、执行、持久化与交付边界，后续换模型、加工具、上编排、扩租户才可控。

---

## 附录：可参考资料（设计输入）

- Spring AI：Tool Calling、Chat Memory、Advisors（以当前官方文档为准）  
- Temporal Java SDK（仅在引入长流程时）  
- OWASP Top 10 for LLM Applications  

正式选型前核对版本兼容性、维护状态与合规要求。

---

如需下一步落地材料，可以继续输出其中任意一块的可直接开工版本，例如：

1. **核心表完整 DDL + 状态迁移表**  
2. **Worker 租约与抢占伪代码**  
3. **工具授权与副作用策略引擎接口草图**  
4. **ContextAssembler 裁剪算法说明**