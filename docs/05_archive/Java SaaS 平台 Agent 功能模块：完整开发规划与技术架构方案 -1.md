# Java SaaS 平台 Agent 功能模块：完整开发规划与技术架构方案

这份规划以面向企业级、多租户、可扩展、可审计的 Java SaaS 平台为目标，覆盖从架构设计、核心引擎、数据模型、接口规范到部署运维和分阶段交付的完整开发过程。

总体建议是：以 Spring Boot 为业务基础，以独立的 Agent Runtime 为执行核心，以工作流编排引擎管理复杂任务，以统一 Tool Registry 管理工具，以租户级安全策略贯穿整个调用链路。

不要一开始就把 Agent 做成一个简单的“LLM API 调用封装”，也不建议初期就构建庞大的分布式 Agent 集群。更合理的路线是先构建具备生产级安全边界的单 Agent 执行能力，再逐步扩展到复杂工作流、多 Agent 协作、知识库、插件市场和企业级治理。

## 一、项目目标与范围

### 1.1 建设目标

智能任务执行

支持目标理解、任务拆解、计划生成、工具调用、执行反馈、结果汇总及失败恢复。

企业级多租户

实现租户隔离、用户授权、Agent 级权限、工具级权限、数据范围控制和操作审计。

开放式扩展

通过标准化插件、工具协议、模型适配器和事件机制接入第三方系统。

可运营、可治理

提供执行追踪、成本统计、模型调用监控、配额控制、版本管理、灰度发布和故障恢复。

### 1.2 建议的首期功能边界

|模块|首期建设内容|后续扩展|
|---|---|---|
|Agent 管理|创建、配置、发布、版本管理|Agent 模板市场、版本灰度|
|执行引擎|单 Agent、ReAct 循环、工具调用|多 Agent 协作、复杂 DAG|
|任务管理|异步任务、状态机、重试、取消|长流程补偿、定时任务|
|工具系统|HTTP、Java 服务、MCP 适配|插件市场、沙箱插件|
|上下文|会话、消息历史、窗口控制|长期记忆、知识图谱|
|安全体系|租户隔离、RBAC、工具授权|ABAC、细粒度策略引擎|
|运营管理|调用日志、Token、成本、审计|评测、自动优化、商业计费|

建议将以下能力列为明确的非首期目标：自主执行任意代码、无限循环式自主规划、无人工确认的高风险操作，以及任意租户之间共享记忆。这些能力不仅增加工程复杂度，也会显著放大安全风险。

## 二、系统整体架构设计

### 2.1 总体架构

采用分层架构，业务控制面与 Agent 执行面逻辑分离。

### 接入层 · Access Layer

Web 控制台 · OpenAPI · SDK · SSE / WebSocket

### 平台控制面 · Control Plane

API Gateway · IAM · Tenant · Agent 管理 · 配额计费

版本发布 · Tool Registry · 模型配置 · 策略管理

### 执行面 · Agent Runtime

任务状态机 · Planner · Executor · Tool Router

Context Manager · Guardrails · Human Approval

#### 异步基础设施

Kafka / RabbitMQ

任务队列、事件、延迟重试

#### 外部能力层

LLM / Embedding

MCP / HTTP / Java Tools

### 数据与治理层

PostgreSQL：业务元数据

Redis：缓存与短期状态

对象存储：大结果、文件

pgvector：语义检索

OpenTelemetry：链路、指标、日志

### 2.2 模块边界与职责

建议先采用模块化单体 + 独立异步执行 Worker，而不是一开始就拆成十几个微服务。

|模块|核心职责|是否建议独立部署|
|---|---|---|
|`agent-api`|API、鉴权、参数校验|可与控制面合并|
|`agent-core`|执行循环、任务状态机、领域模型|独立代码模块|
|`agent-orchestration`|工作流、节点执行、依赖管理|独立代码模块|
|`agent-tool`|工具注册、授权、调用、超时|独立代码模块|
|`agent-context`|会话、上下文组装、记忆检索|独立代码模块|
|`agent-model`|LLM 统一接口、模型路由|独立代码模块|
|`agent-worker`|异步任务执行、队列消费|建议独立部署|
|`agent-admin`|管理后台、配置、运营|可与控制面合并|
|`agent-observability`|Trace、指标、成本统计|逻辑独立即可|

核心原则是：模块之间通过明确的领域接口协作，耗时执行通过消息队列解耦，只有出现独立扩缩容、故障隔离或团队自治需求时才拆分服务。

例如，Agent API 不应该直接在 HTTP 请求线程中执行一个可能持续数分钟的 Agent 任务，而应创建任务、返回 `runId`，再由 Worker 执行并通过 SSE 提供进度。

### 2.3 一次 Agent 执行的完整链路

mermaid

```

sequenceDiagram
    participant U as User / Client
    participant A as API Gateway
    participant I as IAM / Policy
    participant R as Agent Runtime
    participant L as LLM Provider
    participant T as Tool Executor
    participant D as Data Layer

    U->>A: POST /agent-runs
    A->>I: Authenticate and authorize
    I-->>A: Tenant + permissions
    A->>D: Create run and snapshot config
    A-->>U: 202 Accepted + runId
    R->>D: Load context and run state
    R->>L: Request model response
    L-->>R: Tool call or final answer
    opt Tool call
        R->>I: Check tool permission
        I-->>R: Allow / Deny
        R->>T: Execute authorized tool
        T-->>R: Tool result
        R->>D: Persist step and result
        R->>L: Continue reasoning
    end
    R->>D: Persist final result
    R-->>U: Stream events / poll status
```

注意：图中的权限检查必须在实际工具执行前发生，不能只在 Agent 创建时检查一次。对敏感数据的读取、外部写入和审批动作都应实施相应的执行时授权。

## 三、Agent 核心能力设计

### 3.1 Agent Runtime：执行引擎

建议将 Runtime 设计为可恢复的状态机，而不是将所有逻辑塞进一个 `while` 循环。

核心执行状态如下：

接收任务 `CREATED`

校验权限、加载配置 `VALIDATING`

构建上下文、调用模型 `RUNNING`

需要工具

执行授权与调用

需要审批

进入 `WAITING_APPROVAL`

保存观察结果，判断是否继续

完成 / 失败 / 取消 / 超时

核心状态建议包括：

- `CREATED`：任务已创建。
    
- `QUEUED`：等待执行。
    
- `RUNNING`：正在执行。
    
- `WAITING_APPROVAL`：等待人工审批。
    
- `WAITING_EXTERNAL`：等待外部事件或回调。
    
- `RETRYING`：正在重试。
    
- `SUCCEEDED`：执行成功。
    
- `FAILED`：执行失败。
    
- `CANCELLED`：任务取消。
    
- `TIMED_OUT`：任务超时。
    

设计要点：

1. 状态迁移必须有明确的合法性校验。
    
2. 每个执行步骤保存输入摘要、输出摘要、耗时、错误码和尝试次数。
    
3. 支持从最近一个安全检查点恢复，而不是重头执行整个 Agent。
    
4. 对工具副作用实施幂等控制，避免任务恢复时重复发送邮件、重复扣款或重复创建业务对象。
    
5. 将最大迭代次数、总耗时、最大 Token、工具调用数和预算设置为硬限制。
    

建议定义以下核心接口：

java

```
public interface AgentExecutor {
    RunResult execute(AgentExecutionContext context);
}

public interface AgentPlanner {
    ExecutionPlan plan(AgentExecutionContext context);
}

public interface AgentStepExecutor {
    StepResult execute(AgentStepContext context);
}

public interface AgentStateRepository {
    void save(RunState state);
    RunState load(String tenantId, String runId);
}
```

对于可恢复任务，接口可以进一步演进为基于事件的执行模型。关键是将业务状态持久化，不能依赖 JVM 内存中的对象维持任务进度。

### 3.2 任务编排能力

建议将编排分为三个层级。

|层级|实现方式|适用场景|
|---|---|---|
|L1：Agent Loop|模型决策与工具调用循环|问答、查询、轻量自动化|
|L2：Workflow DAG|固定节点、条件分支、并行执行|审批、报表、数据处理|
|L3：Multi-Agent|主 Agent 分派给专业 Agent|复杂跨领域任务|

首期优先实现 L1 和基础 L2。多 Agent 应建立在统一的任务、上下文、权限和成本模型之上。

工作流节点可以包括：

- `LLM_NODE`：模型生成与结构化输出。
    
- `TOOL_NODE`：调用外部工具。
    
- `CONDITION_NODE`：根据结果进行分支。
    
- `PARALLEL_NODE`：并行执行多个子任务。
    
- `HUMAN_APPROVAL_NODE`：人工审批。
    
- `SUB_AGENT_NODE`：调用其他 Agent。
    
- `TRANSFORM_NODE`：数据转换。
    
- `END_NODE`：结束并输出结果。
    

建议工作流使用声明式 JSON DSL，版本化存储并在发布时进行静态校验。例如：

json

```
{
  "workflowId": "sales-report",
  "version": 1,
  "nodes": [
    {
      "id": "fetch",
      "type": "TOOL_NODE",
      "tool": "sales.query"
    },
    {
      "id": "summarize",
      "type": "LLM_NODE",
      "dependsOn": ["fetch"]
    },
    {
      "id": "end",
      "type": "END_NODE",
      "dependsOn": ["summarize"]
    }
  ]
}
```

生产实现还需要为每个节点定义输入输出 Schema、超时、重试策略、错误分支和幂等键。不要直接执行用户提交的任意工作流脚本。

编排技术选择：

- 轻量执行循环：自行实现状态机。
    
- 可视化 DAG：自研精简 DSL，或集成现有工作流引擎。
    
- 长时间运行、跨服务、需要进程重启后恢复的任务：重点评估 Temporal Java SDK。它提供 Workflow、Activity 和 Worker 等持久化执行抽象，适合需要可靠恢复的业务流程。
    
    Temporal Documentation
    

如果使用 Temporal，建议将 Agent 的非确定性模型调用、外部工具执行封装为 Activity，并将审批等待设计为持久化等待。避免把普通的 JVM 线程等待当成任务持久化机制。

### 3.3 工具调用系统

工具调用是 Agent 从“生成文本”升级为“执行任务”的关键。模型只应提出工具调用请求，实际执行必须由应用层完成，并由应用层控制授权、参数校验与执行结果。

Home

推荐的调用链：

1. 根据当前 Agent 和租户权限筛选可用工具。
    
2. 向模型提供工具名称、描述及 JSON Schema。
    
3. 接收模型返回的工具名称和参数。
    
4. 验证工具是否属于当前 Agent 的授权集合。
    
5. 校验参数类型、长度、业务范围和危险操作。
    
6. 执行前检查权限、配额、审批要求及幂等键。
    
7. 在隔离的执行器中调用工具。
    
8. 将结构化结果、错误码和执行摘要返回给模型。
    
9. 记录审计信息和成本。
    

工具接口建议：

java

```
public interface AgentTool {

    ToolDefinition definition();

    ToolResult execute(
        ToolExecutionContext context,
        Map<String, Object> arguments
    );
}

public record ToolDefinition(
    String name,
    String version,
    String description,
    String inputSchema,
    String outputSchema,
    String riskLevel
) {}

public record ToolResult(
    boolean success,
    String code,
    Object data,
    String traceId
) {}
```

`ToolExecutionContext` 必须包含可信的租户、用户、授权信息、执行 ID、超时与追踪标识。这些信息由服务端构造，不能从模型生成的参数中直接读取。

工具类型建议分为：

- 只读工具：查询客户、搜索文档、读取业务数据。
    
- 写入工具：更新 CRM、创建工单、修改配置。
    
- 高风险工具：支付、删除、发送外部消息、批量操作。
    
- 基础工具：时间、计算、文本处理。
    
- 委托工具：调用其他 Agent 或工作流。
    

对写入与高风险工具，采用显式授权、参数白名单、幂等键、必要时人工确认的机制。不能仅凭系统提示词要求模型“谨慎操作”。

### 3.4 上下文管理与记忆系统

上下文管理不应该等同于保存整段聊天记录。建议分成四层：

短期对话上下文

当前会话的消息、最近几轮对话、工具调用及其返回结果。

任务上下文

目标、执行计划、已完成步骤、待处理步骤、重试次数和检查点。

长期记忆

经筛选的用户偏好、业务事实和历史任务摘要，需要有来源、有效期和删除策略。

外部知识上下文

企业知识库、业务数据库、文件系统、检索结果以及权限过滤后的内容。

建议采用以下处理流程：

- 从数据库加载最近消息和任务状态。
    
- 对历史消息进行 Token 预算控制。
    
- 将重要的任务状态与普通聊天文本分开存储。
    
- 对长对话执行摘要压缩，并保留关键事实的来源。
    
- 通过 RAG 检索相关知识，而不是将全部知识塞入 Prompt。
    
- 在发送模型前进行敏感信息过滤与数据权限检查。
    
- 将工具调用请求和工具响应按执行轮次保存，保证恢复和审计时能够重建执行轨迹。
    

Spring AI 提供 Chat Memory、Advisor 等能力，可用于实现对话记忆与上下文增强；但生产系统仍需明确自己的持久化、工具消息和上下文裁剪策略。

Home

+1

长期记忆必须遵守租户隔离、用户授权、数据保留期限和删除要求。向量检索的相似度高，并不意味着用户有权读取该内容。

### 3.5 模型接入与路由

建立统一的模型适配层，避免业务代码直接绑定单一模型厂商。

java

```
public interface ModelGateway {
    ModelResponse chat(ModelRequest request);

    Flux<ModelStreamChunk> stream(ModelRequest request);

    ModelMetadata metadata();
}
```

路由策略建议考虑：

- 任务复杂度与模型能力。
    
- 延迟与吞吐。
    
- Token 价格与租户预算。
    
- 模型的结构化输出和工具调用能力。
    
- 数据驻留、隐私和合规要求。
    
- 限流、故障转移和模型可用性。
    

模型失败时可以尝试降级到备选模型，但不能无条件重放已经产生外部副作用的整个任务。

## 四、多租户隔离与权限模型

### 4.1 安全模型

建议采用：

Tenant Isolation + RBAC + Resource-level Authorization + Tool-level Policy

其中：

- Tenant Isolation：确定数据和资源的租户边界。
    
- RBAC：定义租户内用户的角色权限。
    
- Resource-level Authorization：控制用户是否能访问某个 Agent、会话或任务。
    
- Tool-level Policy：控制 Agent 在特定执行上下文中能调用哪些工具、访问哪些数据及执行哪些操作。
    

建议采用以下授权层级：

|层级|示例|校验时机|
|---|---|---|
|平台级|平台管理员、运营人员|管理接口调用时|
|租户级|租户管理员、普通成员|请求入口|
|Agent 级|创建、编辑、发布、执行 Agent|Agent 操作时|
|Run 级|查看、取消、重试某次任务|Run 资源访问时|
|Tool 级|调用 `crm.customer.read`|每次工具执行前|
|数据级|只能查询所属部门客户|业务数据查询时|

可进一步采用 ABAC 表达租户套餐、部门、数据分级、时间窗口、审批状态等动态条件。

### 4.2 数据隔离方案

|方案|优点|缺点|建议用途|
|---|---|---|---|
|共享数据库、共享表、`tenant_id`|成本低、运营简单|需要严格防止越权查询|默认方案|
|共享数据库、独立 Schema|隔离更强|迁移和运维复杂|部分高要求租户|
|独立数据库|边界清晰、独立备份|成本较高|企业专属部署|

首期建议使用共享 PostgreSQL，并要求所有租户业务表具有 `tenant_id`。

关键措施：

1. 在服务端从已验证的身份上下文获取租户 ID。
    
2. 不信任客户端传来的 `tenantId`，更不能信任模型生成的租户 ID。
    
3. 数据访问层强制注入租户条件，结合 PostgreSQL Row-Level Security（RLS）作为纵深防御。
    
4. 对 RLS 使用的会话变量，采用事务级设置并确保连接池中的租户上下文不会泄漏。
    
5. 联合唯一键应考虑租户范围，例如 `(tenant_id, agent_id)`。
    
6. Redis Key、对象存储路径、向量检索过滤条件、缓存键、消息事件都要携带可信的租户范围。
    
7. 日志和追踪中的敏感信息需要脱敏，跨租户运营查询必须有专门授权。
    

### 4.3 Agent 权限与工具授权

建议定义角色：

- `PLATFORM_ADMIN`：平台运维及全局配置。
    
- `TENANT_ADMIN`：租户成员、资源和策略管理。
    
- `AGENT_DEVELOPER`：创建、编辑和发布 Agent。
    
- `AGENT_OPERATOR`：执行、暂停和管理任务。
    
- `AGENT_VIEWER`：只读查看。
    
- `AUDITOR`：读取授权范围内的审计记录。
    

Agent 发布时绑定一个工具权限清单。例如，一个报表 Agent 可以拥有客户只读权限，但没有客户删除权限。即使模型尝试调用删除工具，服务端也必须拒绝。

还要区分调用者身份与Agent 的服务身份：Agent 不能因为拥有服务账号，就天然继承该服务账号的全部权限。工具调用应在明确的授权委托范围内执行。

### 4.4 高风险安全控制

LLM 应用尤其需要防范提示注入、敏感信息泄露、不安全插件和过度自主执行等风险。

OWASP Foundation

建议建立以下硬性控制：

- 工具输入和输出均做 Schema 验证。
    
- 外部网页、文档及工具返回内容一律视为不可信数据。
    
- 工具白名单与租户授权不能由模型自行修改。
    
- 写操作、批量操作和敏感数据导出可要求人工审批。
    
- 网络访问采用域名白名单、出站代理和 SSRF 防护。
    
- 插件运行在独立进程或容器中，实施 CPU、内存、网络和执行时间限制。
    
- 对密钥采用密钥管理服务或 Vault，绝不把密钥写入 Prompt、对话历史或普通日志。
    
- 对执行循环设置硬性预算与熔断规则。
    

## 五、与 SaaS 其他服务的集成

Agent 模块应当成为 SaaS 平台的智能执行能力，而不是重复建设一套用户、计费和业务数据系统。

|SaaS 服务|集成方式|关键约束|
|---|---|---|
|用户与 IAM|OAuth2/OIDC、JWT、内部身份接口|统一身份和授权上下文|
|租户与组织|Tenant Context、组织服务 API|租户及部门边界一致|
|业务服务|REST、内部 RPC、领域服务接口|不绕过业务规则|
|知识库|检索 API、向量检索|检索时实施数据权限过滤|
|文件服务|预签名 URL、文件 ID|验证租户归属与访问权限|
|消息中心|领域事件、Webhook|签名、重试、幂等|
|计费中心|Token、工具调用、任务时长计量|按租户和计费主体归集|
|审计服务|结构化审计事件|记录操作者、Agent 和副作用|
|通知中心|站内信、邮件等内部服务|限制外发及重复发送|
|工作流服务|任务 API 或持久化工作流|统一状态、超时与恢复策略|

### 5.1 同步与异步集成原则

- 低延迟、只读且短耗时的业务查询可以使用同步 HTTP/RPC。
    
- 长耗时任务采用消息队列或持久化工作流。
    
- 业务事件使用 Outbox Pattern，保证业务事务与事件发布的一致性。
    
- 消费者采用幂等处理；至少一次投递并不等于只执行一次。
    
- 外部系统的失败通过重试、退避、熔断和死信队列处理。
    
- Webhook 应验证签名、时间戳和重放保护，并限制回调目标地址。
    

Agent 对外执行写操作时，建议优先调用 SaaS 已有业务服务，不要让 Agent 直接操作业务数据库。这样可以复用既有权限校验、领域规则和审计能力。

## 六、数据存储方案与核心数据模型

### 6.1 推荐的数据技术栈

PostgreSQL

主数据源

保存 Agent 定义、版本、会话、任务、执行步骤、工具元数据、权限策略和审计索引。通过事务保证核心状态一致性。

Redis

保存短期缓存、限流计数、分布式协调信息和热点配置。关键任务状态不能只存于 Redis。

Kafka / RabbitMQ

负责异步任务分发、事件通知、执行结果回传及失败重试。根据吞吐量、顺序性和团队运维能力二选一。

S3 兼容对象存储

保存大型工具结果、附件、导入文件、长执行轨迹归档和知识文档原文，数据库只保存引用及元数据。

pgvector

首期支持语义检索、长期记忆和知识库向量索引。数据量和检索要求增长后，再评估独立向量数据库。

以上是推荐的逻辑组合，不意味着所有组件必须在第一天上线。MVP 可以先使用 PostgreSQL、Redis 和一个消息队列，等实际出现检索或归档需求后再引入其他组件。

### 6.2 核心业务表设计

所有租户级表建议统一包含 `tenant_id`、创建时间、更新时间及必要的删除标记。

|表名|关键字段|用途|
|---|---|---|
|`agent_definition`|`id`, `tenant_id`, `name`, `status`|Agent 逻辑实体|
|`agent_version`|`agent_id`, `version`, `config_json`, `published_at`|不可变版本配置|
|`agent_tool_binding`|`agent_id`, `tool_id`, `policy_id`|Agent 与工具的授权绑定|
|`agent_conversation`|`id`, `tenant_id`, `user_id`, `agent_id`|会话元数据|
|`agent_message`|`id`, `conversation_id`, `role`, `content`|消息历史|
|`agent_run`|`id`, `tenant_id`, `status`, `version_id`|任务主记录|
|`agent_run_step`|`run_id`, `step_id`, `status`, `attempt`|执行步骤和重试|
|`agent_run_event`|`run_id`, `seq`, `event_type`, `payload`|执行事件及流式输出|
|`agent_memory`|`tenant_id`, `scope_id`, `content`, `expires_at`|长期记忆|
|`agent_tool_definition`|`id`, `name`, `version`, `schema_json`|工具定义|
|`agent_audit_log`|`tenant_id`, `actor_id`, `action`, `resource_id`|安全审计|
|`agent_usage_record`|`tenant_id`, `run_id`, `model`, `tokens`, `cost`|用量和成本|

需要特别注意以下设计：

- `agent_run` 应保存执行时使用的 `agent_version_id`，确保任务恢复和审计可重现。
    
- 对发布后的 Agent 版本采用不可变配置；修改配置应创建新版本。
    
- 消息、步骤和事件使用独立主键，并建立合理的时间与租户索引。
    
- `agent_run_event` 可以使用递增序号 `seq` 支持事件去重、断点续传和 SSE 重连。
    
- Token 和费用记录尽可能追加写入，避免因重试覆盖历史账单数据。
    
- 对消息和执行记录配置保留期限、归档与删除策略。
    

### 6.3 示例：任务表 DDL

以下是 PostgreSQL 的简化起点，实际项目还需要根据租户规模设计索引、外键和分区策略。

sql

```
CREATE TABLE agent_run (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    agent_id        UUID NOT NULL,
    agent_version   INTEGER NOT NULL,
    conversation_id UUID,
    status          VARCHAR(32) NOT NULL,
    input_json      JSONB NOT NULL DEFAULT '{}',
    result_json     JSONB,
    error_code      VARCHAR(64),
    idempotency_key VARCHAR(128),
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    version         BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_agent_run_tenant_status
    ON agent_run (tenant_id, status, created_at DESC);

CREATE UNIQUE INDEX uk_agent_run_idempotency
    ON agent_run (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

`version` 字段可用于乐观锁。状态迁移需要在事务中进行条件更新，避免两个 Worker 同时接管同一个任务。

`idempotency_key` 必须具有明确的业务语义和有效期；相同键再次提交时，应返回原任务或明确拒绝冲突请求。

## 七、API 接口设计

建议统一采用 REST API 管理资源、SSE 推送实时进度、Webhook 通知外部系统，并为高频集成提供 Java SDK。

### 7.1 核心接口清单

统一前缀：`/api/v1`

|方法|接口|说明|
|---|---|---|
|`POST`|`/agents`|创建 Agent|
|`GET`|`/agents`|分页查询 Agent|
|`GET`|`/agents/{agentId}`|查看 Agent|
|`PATCH`|`/agents/{agentId}`|修改草稿配置|
|`POST`|`/agents/{agentId}/versions`|创建版本|
|`POST`|`/agents/{agentId}/publish`|发布指定版本|
|`POST`|`/agents/{agentId}/runs`|创建执行任务|
|`GET`|`/runs/{runId}`|查询任务状态|
|`POST`|`/runs/{runId}/cancel`|取消任务|
|`POST`|`/runs/{runId}/retry`|重试失败任务|
|`GET`|`/runs/{runId}/events`|获取或订阅执行事件|
|`POST`|`/conversations`|创建会话|
|`GET`|`/conversations/{id}/messages`|查询消息|
|`GET`|`/tools`|查询当前授权范围内的工具|
|`POST`|`/tools/{toolId}/test`|测试工具连接|
|`GET`|`/usage`|查询用量统计|

建议统一响应格式、分页规则、错误码、关联 ID 和幂等语义。API 层通过认证后的身份上下文确定租户，不允许仅凭 URL 中的资源 ID 访问对象。

### 7.2 创建 Agent 执行任务

请求示例：

http

```
POST /api/v1/agents/ag_123/runs
Authorization: Bearer <access-token>
Idempotency-Key: req-20261017-001
Content-Type: application/json
```

json

```
{
  "conversationId": "conv_456",
  "input": {
    "message": "查询本月销售数据并生成摘要"
  },
  "options": {
    "stream": true,
    "maxIterations": 8
  }
}
```

响应建议：

json

```
{
  "runId": "run_789",
  "status": "QUEUED",
  "eventsUrl": "/api/v1/runs/run_789/events",
  "createdAt": "2026-10-17T08:00:00Z"
}
```

以上 ID 和时间均为接口示例。生产环境应对 `maxIterations` 等参数进行服务端上限校验，并由平台策略决定实际预算。

### 7.3 任务事件与 SSE

建议统一定义事件类型：

- `run.started`
    
- `step.started`
    
- `model.token`
    
- `tool.started`
    
- `tool.completed`
    
- `approval.required`
    
- `step.failed`
    
- `run.completed`
    
- `run.failed`
    

示例：

text

```
id: 12
event: tool.completed
data: {"runId":"run_789","tool":"sales.query","success":true}

id: 13
event: run.completed
data: {"runId":"run_789","status":"SUCCEEDED"}
```

SSE 的 `id` 对应持久化事件序号。客户端断线后通过 `Last-Event-ID` 恢复事件消费，避免仅依赖内存缓冲区导致事件丢失。

对于取消任务，接口返回“取消请求已受理”不代表外部工具已经停止。必须区分协作式取消、正在执行的不可取消操作，以及取消后的补偿流程。

### 7.4 API 错误码

建议至少包括：

|错误码|含义|
|---|---|
|`TENANT_ACCESS_DENIED`|租户访问被拒绝|
|`RESOURCE_FORBIDDEN`|资源权限不足|
|`TOOL_NOT_ALLOWED`|工具不在授权范围|
|`TOOL_SCHEMA_INVALID`|工具参数校验失败|
|`MODEL_RATE_LIMITED`|模型侧限流|
|`QUOTA_EXCEEDED`|租户配额超限|
|`RUN_STATE_CONFLICT`|任务状态冲突|
|`APPROVAL_REQUIRED`|需要人工审批|
|`RUN_TIMEOUT`|执行超时|
|`IDEMPOTENCY_CONFLICT`|幂等键冲突|

所有错误响应都应包含可追踪的 `requestId`，但不能向客户端暴露内部堆栈、密钥、数据库信息或其他租户的数据。

## 八、可扩展的插件机制

### 8.1 插件架构

建议把插件视为独立于 Agent Runtime 的可注册能力，而不是允许插件随意修改 Runtime 内部逻辑。

Plugin Registry

插件元数据 · 版本 · 发布 · 签名 · 租户授权

Capability Adapter

统一输入输出 Schema · 鉴权 · 超时 · 结果标准化

内置 Java Tools

受控的内部服务调用

Remote Plugins

HTTP / MCP / 独立进程

### 8.2 插件接口标准

java

```
public interface AgentPlugin {

    PluginManifest manifest();

    void initialize(PluginContext context);

    List<AgentTool> tools();

    void close();
}

public record PluginManifest(
    String pluginId,
    String version,
    String apiVersion,
    String description,
    List<String> capabilities
) {}
```

建议定义插件生命周期：

`REGISTERED → VALIDATED → APPROVED → ENABLED → DISABLED → DEPRECATED`

其中发布前执行签名验证、Schema 校验、权限审查和兼容性检查。

### 8.3 插件接入方式

|方式|适用场景|风险与建议|
|---|---|---|
|Java SPI / Spring Bean|平台自带插件|可信代码、平台级发布|
|HTTP 插件|第三方业务服务|易于语言无关扩展|
|MCP|标准化工具与资源接入|仍需额外授权、网络隔离和输入验证|
|独立进程 / 容器|不可信或第三方插件|适合实施强隔离|
|脚本插件|简单数据转换|必须使用受限沙箱，避免任意代码执行|

MCP 可作为外部工具集成协议之一，但不应取代平台自己的权限和治理层。工具是否可被模型发现、是否可被当前租户使用、执行时能否访问具体资源，仍由 SaaS 平台决定。

### 8.4 插件市场能力

后续可增加：

- 插件搜索、分类和安装。
    
- 租户级启用与禁用。
    
- 插件版本锁定与兼容性检查。
    
- 连接器凭据的租户级托管。
    
- 插件调用量、错误率与成本统计。
    
- 插件签名、漏洞扫描和撤销机制。
    
- 插件发布审批及灰度升级。
    
- 插件权限清单和数据访问声明。
    

插件的 `name`、`version` 和输入输出 Schema 应版本化。升级不能静默改变现有 Agent 已发布版本的工具行为。

## 九、关键技术选型建议

以下是适合 Java 企业 SaaS 项目的参考技术栈。版本号应在项目启动时根据官方兼容矩阵、维护周期和团队现有基线锁定，不建议直接将所有组件升级到最新大版本。

|技术领域|首选方案|选择理由|
|---|---|---|
|语言|Java 21 LTS 或经验证的更新 LTS|成熟的并发、生态与运维能力|
|基础框架|Spring Boot 3.x 系列或兼容的新稳定基线|企业级依赖注入、安全和配置管理|
|AI 框架|Spring AI|Spring 生态集成、模型适配、工具调用和记忆扩展|
|任务编排|自研状态机；复杂长流程评估 Temporal|按业务复杂度逐步引入持久化工作流|
|数据库|PostgreSQL|事务、JSONB、RLS 和成熟的索引能力|
|缓存|Redis|热点配置、限流与短期缓存|
|消息队列|Kafka 或 RabbitMQ|异步任务、事件分发和重试|
|向量检索|pgvector|初期减少基础设施复杂度|
|对象存储|S3 兼容存储|附件、长结果和归档|
|API 安全|Spring Security + OAuth2/OIDC|统一认证和访问控制|
|服务接口|REST + OpenAPI|易于跨语言集成与 SDK 生成|
|实时输出|SSE，必要时 WebSocket|流式结果和任务事件|
|可观测性|OpenTelemetry + Prometheus + Grafana|追踪、指标和告警|
|日志|结构化日志 + Loki 或 ELK|任务关联、故障定位和审计辅助|
|容器部署|Docker + Kubernetes|弹性扩缩容、滚动升级和故障恢复|
|CI/CD|GitHub Actions、GitLab CI 或现有企业流水线|自动测试、镜像扫描和发布|

Spring AI 的工具调用和 Advisor 机制可用于构建基础执行能力。其工具调用文档明确由应用层负责分派和执行模型提出的工具请求，因此建议在它与业务工具之间增加自有的权限检查、配额、审计和幂等层。

Home

+1

### 技术选型的几个关键决策

1. Spring AI 还是自研模型适配层？

建议组合使用。以 Spring AI 处理常见模型调用、流式输出和基础工具接口，同时保留平台自己的 `ModelGateway`，避免将业务领域模型绑定到第三方框架的 API。

2. 自研编排还是 Temporal？

如果任务主要在几秒到几十秒内完成，可以先用自研状态机和数据库持久化。如果任务可能持续数小时、需要跨服务等待审批、进程重启后恢复或复杂补偿，则应认真评估 Temporal。

Temporal Documentation

3. Kafka 还是 RabbitMQ？

- RabbitMQ：适合以任务分发、确认、重试和工作队列为主的场景。
    
- Kafka：适合高吞吐事件流、多消费者订阅、事件重放和长期事件保留。
    

二者不是必须同时部署。除非存在明确的业务需求，不建议初期同时维护两套消息基础设施。

4. pgvector 还是独立向量数据库？

首期优先复用 PostgreSQL。只有在向量规模、过滤复杂度、召回性能或隔离要求达到瓶颈后，再评估独立方案。

5. 微服务还是模块化单体？

推荐模块化单体作为控制面起点，将执行 Worker 独立部署。模型路由、工具执行、记忆、插件注册等先保持清晰的代码边界，等流量和团队分工成熟后再按业务边界拆服务。

## 十、部署架构与运维策略

### 10.1 推荐部署拓扑

入口与安全

Ingress / API Gateway / WAF / IAM

控制面 Pod

管理 API、配置、鉴权、配额

执行 Worker Pod

Agent Runtime、工具调度、模型调用

共享基础设施

PostgreSQL · Redis · MQ · 对象存储

外部依赖

LLM API、企业业务服务、MCP

运维系统

Tracing、Metrics、Logging、告警

### 10.2 弹性伸缩

建议至少分开设置控制面和 Worker 的扩缩容策略。

- 控制面：按 CPU、内存、请求延迟和并发请求量扩缩容。
    
- Worker：按队列积压、任务等待时间、模型并发限制和执行资源扩缩容。
    
- 工具执行器：对高延迟、资源密集型或不可信插件采用独立执行池。
    
- 模型网关：实施并发限制、请求排队、超时和熔断，避免外部模型服务故障拖垮整个 Worker 集群。
    

Worker 扩容时必须避免同一任务被多个消费者重复执行。可采用任务租约、数据库条件更新、幂等键和可恢复工作流相结合的方式，而不能仅依靠 Redis 分布式锁。

### 10.3 高可用与灾难恢复

至少需要覆盖：

- PostgreSQL 主从或托管高可用、定期备份及恢复演练。
    
- 消息队列持久化、死信处理和消费者重启恢复。
    
- Worker 崩溃后对过期租约的任务进行安全接管。
    
- 模型服务限流、重试、降级及故障隔离。
    
- 关键配置和密钥的版本管理与轮换。
    
- 跨可用区部署、容量预留和滚动升级。
    
- 任务状态与事件的完整性校验。
    

建议在设计阶段明确 RPO 和 RTO。比如，可以把 RPO 5 分钟、RTO 30 分钟作为初步讨论目标，但实际目标需要结合 SaaS 的业务等级、基础设施能力和成本预算确认，不能仅凭部署 Kubernetes 就认定具备灾备能力。

### 10.4 可观测性指标

|类别|关键指标|
|---|---|
|API|QPS、P95/P99 延迟、4xx/5xx 比率|
|Agent|成功率、失败率、平均执行轮数、任务耗时|
|模型|首 Token 延迟、生成延迟、Token 用量、限流率|
|工具|调用成功率、超时率、平均耗时、重试次数|
|队列|积压量、等待时间、死信量、消费延迟|
|成本|租户成本、Agent 成本、单任务成本、超预算次数|
|安全|越权拒绝次数、审批等待量、敏感数据访问告警|

每次执行生成一个 `traceId`，并将 `tenantId`、`agentId`、`runId`、`stepId` 作为关联字段。避免将用户原文、完整 Prompt、密钥或工具结果默认写入 Trace 标签；高基数、敏感字段应放在受控日志中，必要时脱敏或采样。

### 10.5 测试与质量保障

建议建立五层测试体系：

1. 单元测试：状态机、上下文裁剪、参数验证、权限决策。
    
2. 集成测试：PostgreSQL、消息队列、模型适配器和工具执行器。
    
3. 契约测试：第三方插件、业务服务、API Schema。
    
4. Agent 评测：任务完成率、工具选择准确率、参数正确率、幻觉率、成本和延迟。
    
5. 安全测试：提示注入、跨租户访问、越权工具调用、SSRF、重复副作用和配额绕过。
    

模型输出存在随机性，因此 Agent 评测应采用固定测试集、可复现的 Mock 工具、版本对比和回归门禁，不能只以“人工试用感觉不错”作为验收标准。

## 十一、分阶段开发路线图

建议采用 24 周作为完整首期规划基线，先交付可投入内部使用的 MVP，再逐步达到生产级 SaaS 能力。实际周期取决于团队规模、现有 SaaS 基础设施和合规要求。

### 11.1 总体阶段安排

## 01

第 1–2 周

架构设计与技术验证

架构基线

- 确定租户、权限、任务和 Agent 领域模型。
    
- 验证模型调用、工具调用、流式输出和结构化结果。
    
- 完成核心数据库 ER 图、API 契约及威胁建模。
    
- 建立代码仓库、CI 流水线和开发环境。
    

## 02

第 3–6 周

Agent MVP

核心闭环

- Agent CRUD、版本草稿与发布。
    
- 基础 Runtime、模型适配层和工具注册。
    
- 会话、消息、任务状态和同步执行。
    
- 基础上下文管理、Token 统计和执行日志。
    
- 交付一个可实际完成业务任务的 Agent。
    

## 03

第 7–10 周

异步执行与任务编排

- 消息队列、Worker 和任务状态机。
    
- 超时、重试、取消、幂等和检查点。
    
- 基础 DAG、条件节点和人工审批。
    
- SSE 事件流与断线恢复。
    
- 集成 2–3 个真实 SaaS 业务工具。
    

## 04

第 11–14 周

多租户与安全治理

- 完成租户隔离、RBAC 和工具级授权。
    
- 引入数据权限过滤、配额与预算控制。
    
- 实现审计、密钥管理和高风险操作审批。
    
- 完成跨租户安全测试及关键故障演练。
    

## 05

第 15–18 周

企业集成与扩展机制

- 对接 SaaS IAM、业务服务、知识库和计费。
    
- 建立插件注册、版本管理和远程工具适配。
    
- 引入长期记忆、RAG 和结果归档。
    
- 实现插件授权、兼容性验证和故障隔离。
    

## 06

第 19–24 周

生产化与正式发布

- Kubernetes 部署、自动扩缩容和灰度发布。
    
- 监控、告警、成本归集和运营后台。
    
- 性能压测、安全审计、灾备演练。
    
- 建立评测集、回归门禁和发布流程。
    
- 小范围租户试点，完成生产验收。
    

### 11.2 每阶段的验收标准

不要只以“功能开发完成”作为阶段验收依据。建议为每个阶段定义可验证的交付物。

|阶段|主要验收条件|
|---|---|
|技术验证|模型适配器、工具调用和流式输出跑通|
|MVP|Agent 可创建、发布、执行和查询历史|
|异步编排|Worker 重启后任务可恢复；重复消费不会重复产生不可逆副作用|
|安全治理|越权访问、跨租户查询和未授权工具调用均被拒绝|
|企业集成|业务身份、配额、计费和审计链路完整|
|生产发布|压测、灾备、回归评测和安全检查达到约定门槛|

推荐在第 6 周、第 14 周和第 24 周设置明确的架构评审门。尤其要在多租户安全验收前，禁止将 Agent 开放给不受控的外部租户。

## 十二、团队配置与开发组织

如果目标是在 24 周左右完成首期生产版本，可以参考以下团队配置：

|角色|建议人数|主要职责|
|---|---|---|
|技术负责人 / 架构师|1|架构、技术决策、质量门禁|
|Java 后端工程师|3–4|Runtime、API、数据层、工具与集成|
|前端工程师|1–2|Agent 管理、工作流编辑器、运营后台|
|测试工程师|1–2|自动化测试、评测、安全与性能测试|
|DevOps / SRE|1|CI/CD、监控、部署和灾备|
|AI 应用工程师|1–2|Prompt、模型路由、RAG 和 Agent 评测|

团队可以精简，但不建议完全省略安全、测试和运维职责。若 SaaS 平台已有统一 IAM、计费、消息基础设施、知识库和 Kubernetes 平台，可显著减少工作量。

## 十三、建议的 Java 工程模块结构

推荐采用 Maven 多模块工程，按领域边界组织代码。

text

```
saas-agent-platform/
├── agent-api/                  # REST、SSE、OpenAPI
├── agent-application/          # 应用服务、用例编排
├── agent-domain/               # Agent、Run、Tool 等领域模型
├── agent-runtime/              # 执行循环、状态机
├── agent-orchestration/        # DAG、节点与工作流
├── agent-model-adapter/        # 模型网关与 Provider
├── agent-tool-spi/             # 工具与插件接口
├── agent-tool-impl/            # 内置业务工具
├── agent-context/              # 会话、记忆、上下文压缩
├── agent-security/             # 租户、RBAC、策略检查
├── agent-persistence/          # PostgreSQL、Redis、对象存储
├── agent-messaging/            # 消息队列、事件和 Outbox
├── agent-observability/        # Trace、Metrics、Audit
├── agent-worker/               # 异步执行进程
├── agent-admin/                # 管理端服务
└── agent-testkit/              # Mock 工具、评测与测试基础设施
```

建议遵循以下工程原则：

- `agent-domain` 不直接依赖 Spring AI、消息队列或具体数据库实现。
    
- 领域服务通过接口依赖外部基础设施。
    
- 模型供应商适配器和工具实现采用可替换设计。
    
- 所有跨租户操作必须通过统一的安全上下文和数据访问规范。
    
- 业务配置使用版本化结构和迁移机制，避免数据库中散落大量难以维护的 JSON 字段。
    
- 对 API、事件和插件接口执行兼容性测试。
    

## 十四、主要风险与应对措施

|风险|可能后果|应对策略|
|---|---|---|
|Agent 执行不可预测|成本失控、任务失败|迭代次数、Token、时长和费用硬限制|
|跨租户数据泄露|严重安全事件|租户上下文强制传递、RLS、权限测试|
|工具产生重复副作用|重复发信、重复写入|幂等键、操作记录、补偿机制|
|外部模型限流或故障|大量任务堆积|并发控制、熔断、退避和降级|
|插件存在恶意行为|数据泄露、远程攻击|独立进程、沙箱、网络白名单和审计|
|上下文无限增长|延迟升高、费用增加|Token 预算、摘要、分层记忆|
|工作流恢复不正确|重复执行、状态不一致|持久化检查点、状态机、故障注入测试|
|框架 API 快速演进|升级困难|自有适配接口、依赖锁定和契约测试|

## 十五、最终推荐实施方案

结合企业 SaaS 的可维护性、安全性和首期投入，我建议采用下面这套默认方案：

## 首期推荐基线

架构

Spring Boot 模块化单体 + 独立 Worker，预留按领域拆分服务的能力。

Agent 引擎

Spring AI + 自有 Runtime + 持久化任务状态机。

编排

首期自研基础 DAG 和人工审批；复杂长流程再引入 Temporal。

数据

PostgreSQL + Redis + 单一消息队列 + 对象存储；按实际检索需求增加 pgvector。

安全

租户隔离 + RBAC + 资源级权限 + 工具执行时授权 + 高风险审批。

扩展

自有 Tool SPI、HTTP 插件、MCP 适配器和模型网关。

交付

以 24 周为规划基线，先验证 MVP，再逐步完成安全治理、企业集成和生产化。

### 建设优先级

如果资源有限，建议按以下顺序投入：

1. 先把单 Agent 执行闭环做好：模型、工具、上下文、状态、审计。
    
2. 再把多租户和安全做好：这些属于平台底座，不适合后期补丁式建设。
    
3. 随后建设异步执行与工作流：解决超时、恢复、并行和人工审批。
    
4. 再扩展插件、知识库和多 Agent：复用前面的执行与权限能力。
    
5. 最后完善规模化运营能力：成本治理、灰度、评测、SLA 和多环境部署。
    

一个重要的架构原则是：模型负责提出计划和工具调用建议，平台负责授权、调度、执行、持久化和最终结果交付。 只要始终坚持这个边界，后续无论替换模型供应商、增加插件、引入新的工作流引擎，还是将系统扩展到数百个租户，都能保持较好的可控性。

### 可参考的官方技术资料

- Spring AI：[官方参考文档](https://docs.spring.io/spring-ai/reference/?utm_source=chatgpt.com) ，重点阅读 Tool Calling、Chat Memory、Advisors。
    
- Temporal：[Java SDK 开发指南](https://docs.temporal.io/develop/java?utm_source=chatgpt.com) 。
    
- OWASP Top 10 for LLM Applications：[LLM 应用安全指南](https://owasp.org/www-project-top-10-for-large-language-model-applications/?utm_source=chatgpt.com) 。
    

这些资料适合作为技术验证与设计评审的起点，正式选型前还应核对具体版本的兼容性、维护状态和部署要求。