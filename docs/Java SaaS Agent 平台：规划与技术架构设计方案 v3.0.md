# Java SaaS Agent 平台：规划与技术架构设计方案 v3.0

> **文档类型**：可开发实施版  
> **版本**：v3.0  
> **基线**：v2.1 结构与工程落地优化版  
> **适用范围**：企业级、多租户、可审计、可恢复的 Java SaaS Agent 平台  
> **日期**：2026-10-17  
> **状态**：实施设计基线。上线前必须完成项目环境验证、威胁建模、容量评估与合规评审。

------------------------------------------------------------------------

## 0. 文档使用说明

### 0.1 目标读者

- 架构师：模块边界、事务边界、状态机、可靠性和扩展策略。
- Java 后端开发：领域接口、数据模型、API 契约、Worker 执行流程。
- DBA / 平台工程：DDL、索引、RLS、迁移、备份和恢复。
- QA / SRE / 安全：故障注入、跨租户测试、生产门禁和运行手册。
- 产品与项目负责人：MVP 边界、分阶段交付和验收条件。

### 0.2 设计约定

本文用以下标签区分实现确定性：

- **\[基线\]**：首期必须遵守的架构约束。
- **\[示例\]**：可作为项目起点，但需按现有 SaaS 平台规范调整。
- **\[待决策\]**：需要由项目负责人在编码前确认。
- **\[非 MVP\]**：明确不进入首期范围。

本文的 DDL 与 Java 片段用于明确契约和实施方向。正式落库前必须通过所选 PostgreSQL 版本、ORM、连接池、消息中间件和部署环境的集成测试。

### 0.3 不能妥协的原则

1.  模型只提出计划和工具调用建议；平台负责授权、调度、执行、持久化与最终交付。
2.  `tenant_id`、`actor_id`、权限范围、`run_id`、`trace_id` 等可信上下文由服务端构造，不接受模型覆盖。
3.  PostgreSQL 是任务状态的权威事实来源；MQ 只负责唤醒和分发。
4.  Run 与 Outbox 事件在同一数据库事务中提交。
5.  每个 Worker 执行世代有单调递增的 `fencing_token`；旧世代不能更新新世代状态。
6.  有副作用的工具必须具备下游幂等、可查询对账或受控人工处理策略；结果未知时禁止盲目重放。
7.  发布后的 Agent 版本与执行快照不可变；安全授权在每次工具执行前动态检查。
8.  预算由服务端强制执行；调用前预留、调用后结算，重复结算必须幂等。
9.  外部网页、检索内容和工具结果均为不可信输入，不得升级为系统指令。
10. 首期先交付安全、可靠的单 Agent 闭环，不以复杂编排替代可靠性基础。

------------------------------------------------------------------------

## 1. v3.0 交付范围与实施决策

### 1.1 MVP 必须交付

| 能力域     | v3.0 实施范围                                    | 不纳入首期                     |
|:-----------|:-------------------------------------------------|:-------------------------------|
| Agent 管理 | 草稿、版本、校验、发布、不可变快照               | 模板市场、灰度编排平台         |
| 执行引擎   | 单 Agent Loop、步骤持久化、检查点、结果交付      | 多 Agent、通用 DAG 引擎        |
| 任务可靠性 | 异步 Run、租约、fencing、重试、协作式取消        | 任意工作流补偿引擎             |
| 工具系统   | Tool SPI、Schema 校验、执行时授权、操作账本      | 任意脚本执行、不可信插件沙箱   |
| 副作用治理 | 稳定幂等键、UNKNOWN、对账、人工处理路径          | 无法审计的”自动重试一切”       |
| 安全       | TenantContext、RBAC、资源授权、RLS、受控 Egress  | 一开始就实现完整通用 ABAC 产品 |
| 审批       | 对具体 operation、参数摘要、目标资源和有效期审批 | 泛化的 BPM 审批中心            |
| 预算计量   | 调用前预留、调用后结算、幂等 usage 事件          | 首期完整商业账单系统           |
| 事件       | 持久化 Run Event、SSE、Last-Event-ID 续传        | 无限期保留所有 Token 流        |
| 运维       | 指标、Trace、告警、死信、恢复演练                | 未验证容量前承诺固定吞吐 SLA   |

### 1.2 技术基线

| 组件     | 基线                                     | 说明                                            |
|:---------|:-----------------------------------------|:------------------------------------------------|
| Java     | Java 21 LTS                              | 以组织运行时兼容矩阵为准                        |
| 框架     | Spring Boot 3.x                          | 采用组织已验证的受支持小版本                    |
| 数据库   | PostgreSQL                               | 任务状态、版本、事件、Outbox、操作账本权威存储  |
| ORM      | Spring JDBC / jOOQ / MyBatis 三选一      | 涉及条件更新、租约和 RLS 的路径优先使用显式 SQL |
| MQ       | Kafka 或 RabbitMQ 二选一                 | 只选一种主 MQ；首期不双栈                       |
| 缓存     | Redis                                    | 限流、热点缓存；不能作为唯一任务状态            |
| 对象存储 | 现有企业对象存储                         | 大结果、附件、长轨迹；引用需授权校验            |
| AI 接入  | 自有 `ModelGateway`，可由 Spring AI 适配 | 领域层不依赖框架专属类型                        |
| 可观测性 | OpenTelemetry + Prometheus/Grafana       | Trace、指标、日志关联                           |
| 密钥     | KMS/Vault 或企业密钥服务                 | 不进入 Prompt、消息和普通日志                   |

### 1.3 编码前必须冻结的决策

- [ ] 主 MQ：Kafka 或 RabbitMQ。
- [ ] 数据访问技术：Spring JDBC、jOOQ 或 MyBatis。
- [ ] ID 策略：UUIDv7、UUID 或数据库生成 ID；全系统统一。
- [ ] 租户 RLS 的角色模型、事务上下文设置和连接池验证方案。
- [ ] 对象存储结果引用、保留期限、删除和导出策略。
- [ ] 单租户并发、模型并发、单 Run 预算和最大运行时长。
- [ ] 每个有副作用工具的下游幂等与对账能力清单。
- [ ] 模型供应商的数据驻留、日志保留和敏感数据策略。
- [ ] RPO/RTO、容量目标和告警阈值；这些数值必须通过业务评审和压测确定。

------------------------------------------------------------------------

## 2. 总体架构与模块边界

### 2.1 逻辑架构

``` mermaid
flowchart TB
    Client[Web / Java SDK / API Client] --> API[agent-api: REST / SSE]
    API --> IAM[认证、TenantContext、资源授权]
    IAM --> Core[agent-core: Domain / Runtime / State Machine]
    Core --> DB[(PostgreSQL)]
    Core --> OB[Outbox Publisher]
    OB --> MQ[(单一 MQ)]
    MQ --> Worker[agent-worker]
    Worker --> Core
    Worker --> Tool[agent-tool: Tool Router / Policy]
    Tool --> Biz[业务服务 / 受控 HTTP Tool]
    Worker --> Model[ModelGateway]
    Model --> LLM[模型供应商]
    Core --> Store[(对象存储)]
    API --> Events[持久化 Run Events]
    Events --> API
    Core --> Obs[OpenTelemetry / Metrics / Audit]
```

### 2.2 模块职责

``` text
saas-agent-platform/
├── agent-api/        # REST、SSE、认证入口、OpenAPI、请求校验
├── agent-core/       # 领域模型、状态机、Agent Loop、预算、上下文
├── agent-tool/       # Tool SPI、Schema、授权、副作用协议
├── agent-infra/      # PostgreSQL、MQ、Outbox、Redis、对象存储、ModelGateway
├── agent-worker/     # 任务领取、租约、续租、执行、恢复
├── agent-admin/      # Agent 管理、Run 详情、审批 UI/API（可合并现有 Admin）
└── agent-testkit/    # Mock 工具、故障注入、契约测试、固定评测集
```

依赖规则：

- `agent-core` 不直接依赖 Spring AI、MQ SDK、Web 框架或数据库驱动。
- `agent-tool` 的工具执行上下文必须由平台构造；工具仍应调用业务系统自身授权。
- `agent-worker` 不把 MQ payload 当作可信任务状态；必须重新读取数据库。
- 异步边界通过持久化 ID 和明确 DTO 传递上下文，不依赖跨线程 `ThreadLocal`。
- 控制面和 Worker 可独立部署，但首期不拆成过多微服务。

### 2.3 核心执行链路

1.  API 验证身份、租户、Agent 资源权限、请求 Schema 和幂等键。
2.  在单个 PostgreSQL 事务内创建 Run、初始事件、幂等记录和 Outbox 事件。
3.  Outbox Publisher 至少一次投递任务消息。
4.  Worker 消费消息后通过数据库条件更新领取 Run，并获得新 `fencing_token`。
5.  Runtime 加载不可变执行快照、预算、上下文和当前授权策略。
6.  模型输出工具调用建议；平台校验工具、参数、权限、数据范围、预算与审批要求。
7.  对副作用工具先写入操作账本，再调用下游；未知结果进入 UNKNOWN/对账。
8.  每次步骤更新、租约续期和 Run 终态提交都校验当前执行世代。
9.  持久化事件供 SSE/轮询消费；客户端只能读取自己有权访问的 Run。

------------------------------------------------------------------------

## 3. 核心领域模型与状态机

### 3.1 核心实体

| 实体              | 职责                   | 关键不变量                             |
|:------------------|:-----------------------|:---------------------------------------|
| AgentDefinition   | Agent 的可编辑逻辑实体 | 只代表当前管理对象，不代表历史执行配置 |
| AgentVersion      | 已发布版本             | 发布后不可原地修改                     |
| ExecutionSnapshot | Run 使用的完整配置快照 | 内容摘要固定，可追溯                   |
| Conversation      | 会话与访问边界         | tenant/user/agent 关系一致             |
| Run               | 一次异步任务           | 状态迁移受控；租约和世代可验证         |
| RunStep           | Loop 中的持久化步骤    | 每个逻辑步骤有稳定 `step_key`          |
| ToolOperation     | 一次逻辑工具操作       | 稳定幂等键；UNKNOWN 不得盲目重放       |
| Approval          | 对具体操作的授权决策   | 绑定参数摘要、目标资源、策略和有效期   |
| RunEvent          | 可续传事件流           | `(tenant_id, run_id, seq)` 唯一且单调  |
| UsageRecord       | 用量与费用账本         | 追加写、幂等、可对账                   |
| OutboxEvent       | 事务性待发布事件       | 可重复发布，消费者必须幂等             |
| AuditLog          | 安全与管理审计         | 结构化、不可由普通用户修改             |

### 3.2 Run 状态

MVP 状态：

- `CREATED`：已写入数据库但尚未完成调度标记；正常情况下只短暂存在。
- `QUEUED`：可被 Worker 领取。
- `RUNNING`：当前执行世代持有有效租约。
- `WAITING_APPROVAL`：等待对具体工具操作的人工决策，不占用持续运行线程。
- `RETRYING`：可重试失败已持久化，等待 `next_attempt_at`。
- `SUCCEEDED`：成功终态，结果引用已持久化。
- `FAILED`：不可恢复失败终态，错误分类与审计已持久化。
- `CANCELLED`：平台已完成协作式取消；不代表外部副作用必然撤销。
- `TIMED_OUT`：达到总时限；不代表下游请求已撤销或未产生副作用。

后续可选状态：`PAUSED`、`WAITING_EXTERNAL`、`COMPENSATING`。只有实现对应协议、恢复路径和测试后才可启用。

### 3.3 Run 状态迁移表

| 当前状态           | 目标状态           | 触发条件                          | 必须的校验                               |
|:-------------------|:-------------------|:----------------------------------|:-----------------------------------------|
| `CREATED`          | `QUEUED`           | 创建事务完成                      | Run、初始事件、Outbox 同事务提交         |
| `QUEUED`           | `RUNNING`          | Worker 领取成功                   | 条件更新、租约设置、fencing 递增         |
| `RETRYING`         | `RUNNING`          | 到达 `next_attempt_at` 并领取成功 | 次数、截止时间、取消标志、租约校验       |
| `RUNNING`          | `RETRYING`         | 明确可重试错误                    | 记录错误、attempt、下次执行时间          |
| `RUNNING`          | `WAITING_APPROVAL` | 策略要求人工审批                  | Approval 与操作参数快照先持久化          |
| `WAITING_APPROVAL` | `QUEUED`           | 审批通过且仍有效                  | 参数摘要、权限、预算、资源状态重新校验   |
| `WAITING_APPROVAL` | `FAILED`           | 审批拒绝或过期且策略不允许继续    | 审批决策与原因审计                       |
| `RUNNING`          | `SUCCEEDED`        | 全部步骤完成                      | 租约世代校验；结果、终态、事件同事务提交 |
| `RUNNING`          | `FAILED`           | 不可恢复错误                      | 租约世代校验；错误和终态事件同事务提交   |
| `RUNNING`          | `CANCELLED`        | 协作式取消完成                    | 停止新操作；处理在途操作的未知结果       |
| `RUNNING`          | `TIMED_OUT`        | 总截止时间到达                    | 停止新操作；在途副作用进入对账流程       |
| `RUNNING`          | `QUEUED`           | 仅在明确定义的暂停/重新排队流程   | 不允许任意状态回退；保留检查点与历史     |

所有其他未列出的迁移默认拒绝并返回 `RUN_STATE_CONFLICT`。

### 3.4 终态语义

终态包括 `SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT`。终态不是说外部系统中的每个动作都已回滚，而是说明平台对当前 Run 的执行生命周期已结束。

如果终态时存在未决的 `ToolOperation.UNKNOWN` 或 `RECONCILIATION_REQUIRED`，必须通过独立操作状态和 Run 结果摘要明确暴露，不得隐藏在普通成功响应中。

### 3.5 Run 版本与并发控制

Run 至少包含：

- `version`：乐观并发控制版本。
- `lease_owner`：当前 Worker 标识。
- `lease_until`：租约到期时间。
- `fencing_token`：执行世代号，领取或接管时单调递增。
- `cancel_requested_at`：协作式取消标记。
- `deadline_at`：绝对截止时间，恢复时不得重新计算完整时长。
- `attempt`、`max_attempts`、`next_attempt_at`：重试控制。
- `status`：状态机当前状态。

更新 Run、Step、事件或最终结果时，必须校验 `run_id + lease_owner + fencing_token`；关键状态迁移还要校验当前状态和 `version`。

------------------------------------------------------------------------

## 4. PostgreSQL 数据模型与 DDL

> 以下为 PostgreSQL 参考 DDL。生产实施需按现有 ID 类型、审计标准、保留策略和 RLS 角色规范调整。示例统一使用 `uuid`，所有时间戳使用 `timestamptz`。

### 4.1 扩展与通用约定

``` sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 建议所有业务表显式包含 tenant_id。
-- 通过复合外键确保子资源不能引用其他租户的父资源。
-- created_at/updated_at 由应用或数据库统一维护，团队必须选定一种规则。
```

### 4.2 Agent 定义与版本

``` sql
CREATE TABLE agent_definition (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    name            varchar(160) NOT NULL,
    description     text,
    status          varchar(24) NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED', 'ARCHIVED')),
    created_by      uuid        NOT NULL,
    updated_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, name)
);

CREATE TABLE agent_version (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    agent_id        uuid        NOT NULL,
    version_no      integer     NOT NULL CHECK (version_no > 0),
    config_json     jsonb       NOT NULL,
    content_hash    char(64)    NOT NULL,
    status          varchar(24) NOT NULL
                    CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    published_by    uuid,
    published_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, agent_id, version_no),
    UNIQUE (tenant_id, agent_id, id),
    FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agent_definition (tenant_id, id),
    CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL AND published_by IS NOT NULL)
        OR status <> 'PUBLISHED'
    )
);

CREATE TABLE agent_version_tool_binding (
    tenant_id       uuid        NOT NULL,
    agent_version_id uuid       NOT NULL,
    tool_id         varchar(160) NOT NULL,
    tool_version    varchar(80) NOT NULL,
    schema_hash     char(64)    NOT NULL,
    policy_version  varchar(80) NOT NULL,
    required_scopes jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, agent_version_id, tool_id),
    FOREIGN KEY (tenant_id, agent_version_id)
        REFERENCES agent_version (tenant_id, id)
);
```

**实施约束：**

- 发布动作应在事务中锁定候选版本、完成校验并转为 `PUBLISHED`。
- 应通过应用权限、触发器或独立发布服务防止已发布版本原地更新。
- `content_hash` 必须由规范化 JSON 计算；不能依赖字段顺序不稳定的普通序列化结果。
- 工具实现和 Schema 的实际制品版本也必须可定位；仅保存工具名称不足以复现和恢复。

### 4.3 执行快照与会话

``` sql
CREATE TABLE agent_execution_snapshot (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    agent_id        uuid        NOT NULL,
    agent_version_id uuid       NOT NULL,
    snapshot_json   jsonb       NOT NULL,
    content_hash    char(64)    NOT NULL,
    created_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, agent_id, id),
    FOREIGN KEY (tenant_id, agent_id, agent_version_id)
        REFERENCES agent_version (tenant_id, agent_id, id)
);

CREATE TABLE agent_conversation (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    agent_id        uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    title           varchar(240),
    status          varchar(24) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, agent_id)
        REFERENCES agent_definition (tenant_id, id)
);

CREATE TABLE agent_message (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    conversation_id uuid        NOT NULL,
    sequence_no     bigint      NOT NULL CHECK (sequence_no > 0),
    role            varchar(24) NOT NULL
                    CHECK (role IN ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),
    content_ref     text,
    content_text    text,
    token_count     integer CHECK (token_count IS NULL OR token_count >= 0),
    source_ref      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, conversation_id, sequence_no),
    FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES agent_conversation (tenant_id, id),
    CHECK (content_ref IS NOT NULL OR content_text IS NOT NULL)
);
```

对包含敏感数据或超长内容的消息，优先使用受控对象存储引用。`content_text` 是否可用、加密方式和保留期限必须与合规要求一致。

### 4.4 Run 主表

``` sql
CREATE TABLE agent_run (
    tenant_id           uuid        NOT NULL,
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    agent_id            uuid        NOT NULL,
    agent_version_id    uuid        NOT NULL,
    snapshot_id         uuid        NOT NULL,
    conversation_id     uuid,
    requested_by        uuid        NOT NULL,
    status              varchar(32) NOT NULL
                        CHECK (status IN (
                            'CREATED', 'QUEUED', 'RUNNING', 'WAITING_APPROVAL',
                            'RETRYING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'
                        )),
    version             bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
    attempt             integer     NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts        integer     NOT NULL DEFAULT 3 CHECK (max_attempts >= 0),
    next_attempt_at     timestamptz,
    lease_owner         varchar(160),
    lease_until         timestamptz,
    fencing_token       bigint      NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    cancel_requested_at timestamptz,
    deadline_at         timestamptz NOT NULL,
    budget_json         jsonb       NOT NULL,
    result_ref          text,
    result_digest       char(64),
    error_code          varchar(100),
    error_summary       text,
    idempotency_key     varchar(200) NOT NULL,
    request_digest      char(64)    NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    started_at          timestamptz,
    finished_at         timestamptz,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (tenant_id, id, agent_id),
    FOREIGN KEY (tenant_id, agent_id, agent_version_id)
        REFERENCES agent_version (tenant_id, agent_id, id),
    FOREIGN KEY (tenant_id, agent_id, snapshot_id)
        REFERENCES agent_execution_snapshot (tenant_id, agent_id, id),
    FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES agent_conversation (tenant_id, id),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT') AND finished_at IS NOT NULL)
        OR status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
    )
);

CREATE INDEX idx_agent_run_schedule
    ON agent_run (status, next_attempt_at, created_at)
    WHERE status IN ('QUEUED', 'RETRYING');

CREATE INDEX idx_agent_run_expired_lease
    ON agent_run (lease_until)
    WHERE status = 'RUNNING';

CREATE INDEX idx_agent_run_tenant_created
    ON agent_run (tenant_id, created_at DESC);

CREATE INDEX idx_agent_run_tenant_status_created
    ON agent_run (tenant_id, status, created_at DESC);
```

注意：唯一约束 `(tenant_id, idempotency_key)` 意味着同一租户的该键在保留期内唯一。若 API 操作类型不同，应将 `operation_scope` 纳入唯一键，或把幂等记录拆为独立表。幂等键不能跨不同请求摘要复用。

### 4.5 Run Step

``` sql
CREATE TABLE agent_run_step (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    run_id          uuid        NOT NULL,
    step_key        varchar(200) NOT NULL,
    step_type       varchar(32) NOT NULL
                    CHECK (step_type IN ('MODEL', 'TOOL', 'SUMMARY', 'POLICY', 'FINALIZE')),
    status          varchar(32) NOT NULL
                    CHECK (status IN (
                        'PENDING', 'RUNNING', 'WAITING_APPROVAL',
                        'SUCCEEDED', 'FAILED', 'SKIPPED', 'UNKNOWN'
                    )),
    attempt         integer     NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    input_digest    char(64),
    input_ref       text,
    output_digest   char(64),
    output_ref      text,
    checkpoint_ref  text,
    error_code      varchar(100),
    error_summary   text,
    fencing_token   bigint      NOT NULL,
    started_at      timestamptz,
    finished_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, run_id, step_key),
    FOREIGN KEY (tenant_id, run_id)
        REFERENCES agent_run (tenant_id, id)
);

CREATE INDEX idx_agent_run_step_run
    ON agent_run_step (tenant_id, run_id, created_at);
```

`step_key` 是逻辑步骤标识，重试时不应随意变化。单次执行尝试可以另存 attempt 记录，避免覆盖历史错误与耗时。

### 4.6 工具操作账本

``` sql
CREATE TABLE agent_tool_operation (
    tenant_id           uuid        NOT NULL,
    operation_id        uuid        NOT NULL DEFAULT gen_random_uuid(),
    run_id              uuid        NOT NULL,
    step_id             uuid        NOT NULL,
    tool_id             varchar(160) NOT NULL,
    tool_version        varchar(80) NOT NULL,
    logical_operation_key varchar(200) NOT NULL,
    idempotency_key     varchar(240) NOT NULL,
    args_digest         char(64)    NOT NULL,
    target_ref          text,
    status              varchar(40) NOT NULL
                        CHECK (status IN (
                            'PREPARED', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED',
                            'UNKNOWN', 'RECONCILIATION_REQUIRED', 'COMPENSATED'
                        )),
    external_request_id varchar(240),
    result_ref          text,
    result_digest       char(64),
    error_code          varchar(100),
    error_summary       text,
    attempt_count       integer     NOT NULL DEFAULT 0,
    next_reconcile_at   timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    finished_at         timestamptz,
    PRIMARY KEY (tenant_id, operation_id),
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (tenant_id, run_id, logical_operation_key),
    FOREIGN KEY (tenant_id, run_id)
        REFERENCES agent_run (tenant_id, id),
    FOREIGN KEY (tenant_id, step_id)
        REFERENCES agent_run_step (tenant_id, id)
);

CREATE INDEX idx_tool_operation_reconcile
    ON agent_tool_operation (status, next_reconcile_at)
    WHERE status IN ('UNKNOWN', 'RECONCILIATION_REQUIRED');

CREATE INDEX idx_tool_operation_run
    ON agent_tool_operation (tenant_id, run_id, created_at);
```

幂等键应在逻辑操作生命周期内保持稳定。推荐输入包括租户、Run、逻辑步骤、工具 ID、逻辑操作标识与规范化参数摘要。不要把 attempt 编号放进逻辑操作幂等键，否则重试会生成新副作用。

### 4.7 Approval

``` sql
CREATE TABLE agent_approval (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    run_id          uuid        NOT NULL,
    step_id         uuid        NOT NULL,
    operation_id    uuid        NOT NULL,
    tool_id         varchar(160) NOT NULL,
    tool_version    varchar(80) NOT NULL,
    args_digest     char(64)    NOT NULL,
    target_ref      text,
    policy_version  varchar(80) NOT NULL,
    requested_by    uuid        NOT NULL,
    approved_by     uuid,
    status          varchar(24) NOT NULL
                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'REVOKED', 'CONSUMED')),
    reason          text,
    expires_at      timestamptz NOT NULL,
    decided_at      timestamptz,
    consumed_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, run_id)
        REFERENCES agent_run (tenant_id, id),
    FOREIGN KEY (tenant_id, step_id)
        REFERENCES agent_run_step (tenant_id, id),
    FOREIGN KEY (tenant_id, operation_id)
        REFERENCES agent_tool_operation (tenant_id, operation_id),
    CHECK (approved_by IS NULL OR approved_by <> requested_by OR reason IS NOT NULL)
);

CREATE INDEX idx_approval_pending
    ON agent_approval (tenant_id, expires_at)
    WHERE status = 'PENDING';
```

是否要求审批人和发起人分离由风险策略决定。审批通过后执行前必须再次检查权限、参数摘要、目标资源状态、预算和取消标志。

### 4.8 Run Event、Outbox、Usage、Audit

``` sql
CREATE TABLE agent_run_event (
    tenant_id       uuid        NOT NULL,
    run_id          uuid        NOT NULL,
    seq             bigint      NOT NULL CHECK (seq > 0),
    event_id        uuid        NOT NULL DEFAULT gen_random_uuid(),
    event_type      varchar(100) NOT NULL,
    schema_version  integer     NOT NULL DEFAULT 1,
    payload         jsonb,
    payload_ref     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id, seq),
    UNIQUE (tenant_id, event_id),
    FOREIGN KEY (tenant_id, run_id)
        REFERENCES agent_run (tenant_id, id),
    CHECK (payload IS NOT NULL OR payload_ref IS NOT NULL)
);

CREATE TABLE outbox_event (
    tenant_id       uuid        NOT NULL,
    event_id        uuid        NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type  varchar(80) NOT NULL,
    aggregate_id    uuid        NOT NULL,
    event_type      varchar(100) NOT NULL,
    payload         jsonb       NOT NULL,
    status          varchar(24) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'DEAD')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error_code varchar(100),
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    PRIMARY KEY (tenant_id, event_id)
);

CREATE INDEX idx_outbox_pending
    ON outbox_event (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'PUBLISHING');

CREATE TABLE agent_usage_record (
    tenant_id           uuid        NOT NULL,
    usage_event_id      uuid        NOT NULL,
    run_id              uuid        NOT NULL,
    operation_id        uuid,
    provider_request_id varchar(240),
    usage_type          varchar(80) NOT NULL,
    input_tokens        bigint      NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens       bigint      NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    tool_units          numeric(20, 6) NOT NULL DEFAULT 0 CHECK (tool_units >= 0),
    cost_amount         numeric(20, 8) NOT NULL DEFAULT 0 CHECK (cost_amount >= 0),
    currency            char(3)     NOT NULL DEFAULT 'USD',
    settlement_status   varchar(24) NOT NULL
                        CHECK (settlement_status IN ('RESERVED', 'SETTLED', 'RELEASED', 'RECONCILE')),
    source_digest       char(64),
    created_at          timestamptz NOT NULL DEFAULT now(),
    settled_at          timestamptz,
    PRIMARY KEY (tenant_id, usage_event_id),
    FOREIGN KEY (tenant_id, run_id)
        REFERENCES agent_run (tenant_id, id),
    FOREIGN KEY (tenant_id, operation_id)
        REFERENCES agent_tool_operation (tenant_id, operation_id)
);

CREATE INDEX idx_usage_run
    ON agent_usage_record (tenant_id, run_id, created_at);

CREATE TABLE agent_audit_log (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    actor_id        uuid,
    action          varchar(160) NOT NULL,
    resource_type   varchar(100) NOT NULL,
    resource_id     text NOT NULL,
    decision        varchar(24) NOT NULL,
    reason_code     varchar(100),
    trace_id        varchar(128),
    metadata        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX idx_audit_resource
    ON agent_audit_log (tenant_id, resource_type, resource_id, created_at DESC);
```

### 4.9 事件序号生成

同一 Run 的事件序号必须事务性分配。简单且可靠的首期实现可在追加事件时锁定对应 Run 行，读取或维护 `last_event_seq`，再插入事件。高吞吐优化必须先证明不会产生重复、缺号语义混乱或乱序。

可在 `agent_run` 增加 `last_event_seq bigint NOT NULL DEFAULT 0`。每次追加事件：

``` sql
UPDATE agent_run
SET last_event_seq = last_event_seq + 1
WHERE tenant_id = :tenantId
  AND id = :runId
RETURNING last_event_seq;
```

然后在同一事务中以返回的值插入 `agent_run_event`。追加事件的事务必须同时校验资源与当前执行世代（对于 Worker 产生的执行事件）。

### 4.10 RLS 示例与注意事项

``` sql
ALTER TABLE agent_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_run FORCE ROW LEVEL SECURITY;

CREATE POLICY agent_run_tenant_policy ON agent_run
USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

对所有租户表逐一启用策略。应用角色不得拥有绕过 RLS 的权限；表所有者、迁移角色、运营角色应分离。

事务开始后使用 `SET LOCAL app.tenant_id = '...'` 或等效事务级设置。连接池复用、事务回滚、异步任务、批处理和后台 Worker 均必须测试。`current_setting` 为空或无效时必须拒绝访问，而不是退化成全表访问。

RLS 是纵深防御，不替代服务层资源授权和业务数据范围检查。普通应用角色不能通过任意 SQL 自行切换租户上下文；只有经过身份验证的可信应用层可设置该值。

------------------------------------------------------------------------

## 5. Worker 任务领取、租约与 fencing

### 5.1 领取协议

1.  Worker 从 MQ 收到 `RUN_REQUESTED` 消息，只把它当作候选任务提示。
2.  从数据库读取 Run 的可信租户、状态、取消标志、截止时间和重试时间。
3.  使用事务和条件更新领取任务；仅成功更新的一方获得执行权。
4.  领取或接管时递增 `fencing_token`，写入 `lease_owner`、`lease_until`，并递增 `version`。
5.  Worker 以领取返回的 token 执行；每次续租、步骤更新和最终提交均携带 token。
6.  租约丢失时立即停止发起新工具操作；在途请求依工具副作用协议处理。

### 5.2 推荐的候选任务领取 SQL

以下使用 `FOR UPDATE SKIP LOCKED` 选择一个候选任务。锁和更新必须位于同一事务中。

``` sql
BEGIN;

WITH candidate AS (
    SELECT tenant_id, id
    FROM agent_run
    WHERE (
        status = 'QUEUED'
        OR (status = 'RETRYING' AND next_attempt_at <= now())
        OR (status = 'RUNNING' AND lease_until < now())
    )
      AND (lease_until IS NULL OR lease_until < now())
      AND cancel_requested_at IS NULL
      AND deadline_at > now()
      AND attempt < max_attempts
    ORDER BY COALESCE(next_attempt_at, created_at), created_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
UPDATE agent_run r
SET status = 'RUNNING',
    lease_owner = :workerId,
    lease_until = now() + (:leaseSeconds * interval '1 second'),
    fencing_token = fencing_token + 1,
    version = version + 1,
    attempt = attempt + 1,
    started_at = COALESCE(started_at, now()),
    updated_at = now()
FROM candidate c
WHERE r.tenant_id = c.tenant_id
  AND r.id = c.id
RETURNING r.tenant_id, r.id, r.fencing_token, r.version, r.lease_until,
          r.deadline_at, r.snapshot_id, r.budget_json;

COMMIT;
```

**实现说明：**

- `attempt` 的语义需冻结：建议定义为 Run 执行尝试次数；单个工具操作的尝试另计。
- 上例是可执行实现的起点，不是所有重试和终态处理的完整代码。
- `RUNNING` 过期接管前，必须确认该 Run 的工具操作账本；旧世代的 `IN_FLIGHT` 操作不能被当作未执行。
- 达到 `deadline_at` 或 `max_attempts` 的任务应由调度清理器转为明确终态并写事件，不能永久滞留。
- 若使用 RLS，领取过程需要可信的租户上下文；跨租户调度可采用受限的调度角色与显式租户扫描机制，不能无边界地绕过安全策略。

### 5.3 续租 SQL

``` sql
UPDATE agent_run
SET lease_until = now() + (:leaseSeconds * interval '1 second'),
    version = version + 1,
    updated_at = now()
WHERE tenant_id = :tenantId
  AND id = :runId
  AND status = 'RUNNING'
  AND lease_owner = :workerId
  AND fencing_token = :fencingToken
  AND lease_until > now()
  AND cancel_requested_at IS NULL
RETURNING lease_until, version;
```

更新行数为 0 时，Worker 必须视为 `LEASE_LOST` 或执行权已失效。不得用 Redis 锁结果覆盖数据库的判断。

### 5.4 带 fencing 的步骤更新

``` sql
UPDATE agent_run_step s
SET status = :newStatus,
    output_ref = :outputRef,
    output_digest = :outputDigest,
    updated_at = now()
FROM agent_run r
WHERE s.tenant_id = :tenantId
  AND s.run_id = :runId
  AND s.id = :stepId
  AND r.tenant_id = s.tenant_id
  AND r.id = s.run_id
  AND r.status = 'RUNNING'
  AND r.lease_owner = :workerId
  AND r.fencing_token = :fencingToken
  AND s.fencing_token = :fencingToken;
```

更新行数不是 1 时，当前执行世代无权提交。应用必须停止该世代的后续工作，并记录可观测的 fencing 拒绝指标。

### 5.5 完成 Run 的事务边界

在同一事务中：

1.  条件更新 Run，校验 `status='RUNNING'`、`lease_owner`、`fencing_token` 和必要的 `version`。
2.  写入最终结果引用与摘要。
3.  写入 `SUCCEEDED` / `FAILED` 等终态与 `finished_at`。
4.  追加终态 Run Event。
5.  写入用量结算或后续结算 Outbox。
6.  提交事务。

任何关键条件更新失败都应回滚。旧 Worker 不得因”模型调用已经成功”而绕过 fencing 校验。

------------------------------------------------------------------------

## 6. Outbox 与消息消费

### 6.1 Run 创建事务

``` text
BEGIN
  校验幂等键及 request_digest
  绑定已发布 agent_version_id 与 execution_snapshot_id
  INSERT agent_run(status = QUEUED)
  INSERT agent_run_event(seq = 1, event_type = run.queued)
  INSERT outbox_event(event_type = RUN_REQUESTED)
COMMIT
返回 HTTP 202 + runId
```

Run、初始事件和 Outbox 必须在同一个数据库事务中。事务提交后才返回 202。

### 6.2 Outbox Publisher 协议

- 使用 `FOR UPDATE SKIP LOCKED` 领取待发布事件。
- 将事件投递至 MQ，消息包含 `event_id`、`event_type`、`aggregate_id` 和最小必要定位信息。
- 只有 MQ 确认后才标记 `PUBLISHED`。
- 如果消息已投递但数据库更新失败，允许再次发布；消费者必须幂等。
- 暂时性错误使用指数退避和抖动；超过阈值进入 `DEAD` 并告警。
- 不允许根据”MQ 中是否有消息”推断 Run 的最终状态。

### 6.3 消费者幂等

消费者收到消息后：

1.  校验消息 Schema 与事件类型。
2.  使用数据库查询可信 Run。
3.  通过条件领取 SQL 竞争执行权。
4.  未获得租约则确认消息并结束本次消费。
5.  获得租约后执行；进程崩溃由租约恢复流程接管。
6.  消息确认时机遵循 MQ 的可靠性策略；即使重投，也不得仅凭消息触发第二次逻辑副作用。

------------------------------------------------------------------------

## 7. 工具执行协议与 UNKNOWN 对账

### 7.1 工具操作状态

| 状态                      | 含义                                 | 允许动作                           |
|:--------------------------|:-------------------------------------|:-----------------------------------|
| `PREPARED`                | 操作意图、参数摘要、幂等键已持久化   | 首次发起调用                       |
| `IN_FLIGHT`               | 请求可能已发出，结果尚未确认         | 查询下游状态；必要时使用相同幂等键 |
| `SUCCEEDED`               | 下游成功已确认，结果已存储           | 读取结果，不重复执行               |
| `FAILED`                  | 已确认失败且无副作用，或失败语义明确 | 按错误分类决定是否重试             |
| `UNKNOWN`                 | 超时、断连、崩溃导致结果不确定       | 先对账；禁止创建新逻辑操作         |
| `RECONCILIATION_REQUIRED` | 自动对账仍不能确认                   | 转人工处理或受控补偿               |
| `COMPENSATED`             | 已执行明确的补偿操作                 | 保留原操作与补偿操作的审计关联     |

### 7.2 标准工具调用流程

``` mermaid
sequenceDiagram
    participant R as Runtime
    participant P as Policy/Authorization
    participant DB as PostgreSQL
    participant T as Tool Adapter
    participant D as Downstream System
    R->>P: 校验工具、权限、数据范围、预算
    P-->>R: 允许 / 需审批 / 拒绝
    R->>DB: 写入 ToolOperation(PREPARED)
    DB-->>R: operationId + stable idempotencyKey
    R->>DB: 条件更新为 IN_FLIGHT
    R->>T: execute(context, args, idempotencyKey)
    T->>D: 调用下游
    alt 明确成功
        D-->>T: 成功结果 + externalRequestId
        T-->>R: ToolResult(SUCCEEDED)
        R->>DB: 持久化结果、结算、事件
    else 明确无副作用失败
        D-->>T: 明确失败
        T-->>R: ToolResult(FAILED)
        R->>DB: 记录失败与重试策略
    else 超时或连接中断
        T-->>R: UNKNOWN
        R->>DB: 操作标记 UNKNOWN
        Note over R,DB: 不得用新幂等键盲目重放
    end
```

### 7.3 幂等键规则

- 同一个逻辑操作的幂等键在 Run 重试、Worker 接管、模型重试时保持稳定。
- 同一键 + 相同参数摘要：返回已有操作状态或结果。
- 同一键 + 不同参数摘要：拒绝并返回 `IDEMPOTENCY_CONFLICT` 或 `TOOL_SIDE_EFFECT_CONFLICT`。
- 下游支持幂等时，将平台稳定键或 `operation_id` 传递给下游。
- 下游不支持幂等时，工具必须提供可查询业务唯一键、对账 API，或明确转人工流程。
- 补偿不是回滚；补偿动作本身也必须有幂等键、权限校验、审批和审计。
- 不要把网络请求 attempt 编号加入逻辑幂等键。

### 7.4 Tool SPI

``` java
public interface AgentTool {
    ToolDefinition definition();

    ToolResult execute(
        ToolExecutionContext context,
        Map<String, Object> arguments
    );
}

public record ToolExecutionContext(
    UUID tenantId,
    UUID actorId,
    UUID runId,
    UUID stepId,
    UUID operationId,
    String traceId,
    long fencingToken,
    Instant deadline,
    Set<String> grantedScopes
) {}

public record ToolDefinition(
    String toolId,
    String version,
    String description,
    JsonNode inputSchema,
    JsonNode outputSchema,
    SideEffectLevel sideEffectLevel,
    boolean requiresApproval,
    Duration timeout
) {}

public enum SideEffectLevel {
    READ_ONLY,
    IDEMPOTENT_WRITE,
    NON_IDEMPOTENT_WRITE,
    EXTERNAL_COMMUNICATION
}

public enum ToolOperationStatus {
    PREPARED,
    IN_FLIGHT,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    RECONCILIATION_REQUIRED,
    COMPENSATED
}

public record ToolResult(
    ToolOperationStatus status,
    JsonNode modelView,
    String resultRef,
    String externalRequestId,
    String errorCode,
    boolean sideEffectConfirmed
) {}
```

说明：

- `JsonNode` 示例可替换为项目统一的 JSON 类型。
- `grantedScopes` 只是已计算的授权结果，不应替代业务系统最终的对象级授权。
- 工具定义必须声明副作用等级、超时、重试语义和对账能力。
- 工具适配器不得自行信任模型提供的 tenant/user/permission 字段。
- 所有远程调用均应有 deadline、超时、并发上限和可观测性。

### 7.5 UNKNOWN 对账策略

按工具能力登记下列一种或多种策略：

1.  **按稳定幂等键查询**：下游支持以同一键查询操作状态。
2.  **按业务唯一键查询**：例如订单号、工单号或外部请求 ID。
3.  **同一键安全重放**：只有下游明确保证同一幂等键不会重复产生副作用时才允许。
4.  **人工对账**：生成可审计工单，展示目标资源、参数摘要、请求 ID 和时间线。
5.  **受控补偿**：仅在确认原操作状态且补偿前置条件成立时执行。

绝不允许：把超时直接当作失败，再生成新操作 ID 和新幂等键重试。

------------------------------------------------------------------------

## 8. 预算、配额与费用结算

### 8.1 预算维度

- 最大 Loop 迭代数。
- 绝对运行截止时间。
- 输入/输出 Token 上限。
- 工具调用次数。
- 单次 Run 费用上限。
- 租户、Agent、用户和供应商并发上限。
- 单工具响应大小、最大重试数和总调用时长。

### 8.2 预留—执行—结算流程

``` mermaid
flowchart TD
    A[准备模型/工具调用] --> B{预算与配额可用?}
    B -- 否 --> X[拒绝调用并记录预算事件]
    B -- 是 --> C[事务性预留预算]
    C --> D[调用模型或工具]
    D --> E{获得明确结果?}
    E -- 是 --> F[按实际用量结算]
    E -- 否 --> G[标记待对账/保守计量]
    F --> H[写入幂等 usage 事件]
    G --> H
    H --> I[更新 Run 预算视图]
```

### 8.3 关键规则

- 并发调用必须在数据库或具备原子语义的预算服务中预留，不可只在 JVM 内存计数。
- 预留和结算必须使用稳定的 `usage_event_id`。
- 结算重复请求返回既有结果，不重复扣费。
- 失败时是否释放预留由失败类型决定；模型供应商可能已产生费用，不能一律全额释放。
- 供应商未返回 Token 用量时，使用保守估算或标记 `RECONCILE`，并对账最终账单。
- 用户传入的 budget/options 只能收紧服务端策略，不能扩大平台硬上限。
- Run 恢复时沿用原始绝对截止时间和已消耗预算，不能重置完整额度。
- 工具重试是否计入调用次数必须在计量契约中统一定义。

### 8.4 预算接口建议

``` java
public interface BudgetService {
    BudgetReservation reserve(BudgetRequest request);

    BudgetSettlement settle(
        UUID reservationId,
        ActualUsage actualUsage,
        String usageEventId
    );

    void release(
        UUID reservationId,
        String releaseReason,
        String usageEventId
    );
}

public record BudgetRequest(
    UUID tenantId,
    UUID runId,
    String operationKey,
    BigDecimal estimatedCost,
    long estimatedInputTokens,
    long maxOutputTokens,
    int toolUnits
) {}

public record BudgetReservation(
    UUID reservationId,
    String status,
    BigDecimal reservedCost,
    Instant expiresAt
) {}
```

接口只定义契约。生产实现还需要处理并发锁、预留过期、跨服务事务、供应商计费差异和幂等重放。

------------------------------------------------------------------------

## 9. Java 核心接口与实现骨架

### 9.1 Run 状态枚举与迁移守卫

``` java
public enum RunStatus {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    RETRYING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == SUCCEEDED
            || this == FAILED
            || this == CANCELLED
            || this == TIMED_OUT;
    }
}
```

``` java
public final class RunStateMachine {
    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = Map.of(
        RunStatus.CREATED, Set.of(RunStatus.QUEUED),
        RunStatus.QUEUED, Set.of(RunStatus.RUNNING, RunStatus.CANCELLED, RunStatus.TIMED_OUT),
        RunStatus.RETRYING, Set.of(RunStatus.RUNNING, RunStatus.CANCELLED, RunStatus.TIMED_OUT),
        RunStatus.RUNNING, Set.of(
            RunStatus.RETRYING, RunStatus.WAITING_APPROVAL,
            RunStatus.SUCCEEDED, RunStatus.FAILED,
            RunStatus.CANCELLED, RunStatus.TIMED_OUT
        ),
        RunStatus.WAITING_APPROVAL, Set.of(
            RunStatus.QUEUED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TIMED_OUT
        ),
        RunStatus.SUCCEEDED, Set.of(),
        RunStatus.FAILED, Set.of(),
        RunStatus.CANCELLED, Set.of(),
        RunStatus.TIMED_OUT, Set.of()
    );

    public void assertTransition(RunStatus from, RunStatus to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException(
                "Illegal Run state transition: " + from + " -> " + to
            );
        }
    }
}
```

注意：状态机类只提供领域层校验；数据库条件更新仍然是并发安全的最终防线。取消、超时和审批之间存在竞争时，必须由数据库状态迁移条件决定唯一结果。

### 9.2 TenantContext

``` java
public record TenantContext(
    UUID tenantId,
    UUID actorId,
    Set<String> authorities,
    String requestId,
    String traceId
) {
    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actorId, "actorId");
        authorities = Set.copyOf(authorities == null ? Set.of() : authorities);
    }
}
```

`TenantContext` 只能由已验证的认证链路创建。不能从请求 JSON 反序列化成可信对象，也不能通过模型工具参数覆盖。

### 9.3 Run 应用服务

``` java
public interface RunApplicationService {
    RunAccepted createRun(
        TenantContext tenant,
        CreateRunCommand command,
        String idempotencyKey
    );

    RunView getRun(TenantContext tenant, UUID runId);

    CancelAccepted requestCancel(TenantContext tenant, UUID runId);

    RetryAccepted retryRun(TenantContext tenant, UUID runId);
}

public record CreateRunCommand(
    UUID agentId,
    UUID conversationId,
    String goal,
    Map<String, Object> options,
    Map<String, Object> budgetOverrides
) {}

public record RunAccepted(
    UUID runId,
    RunStatus status,
    String statusUrl,
    String eventsUrl
) {}
```

创建 Run 的服务必须在事务中完成：授权、幂等检查、固定发布版本与快照、Run 插入、初始事件插入、Outbox 插入。不要在 API 请求线程执行持续数分钟的 Agent Loop。

### 9.4 Worker 执行器

``` java
public interface RunExecutor {
    void execute(ClaimedRun run);
}

public record ClaimedRun(
    UUID tenantId,
    UUID runId,
    String workerId,
    long fencingToken,
    long rowVersion,
    Instant leaseUntil,
    Instant deadlineAt,
    UUID snapshotId
) {}
```

伪代码：

``` java
public void execute(ClaimedRun claimed) {
    try {
        leaseService.assertStillOwner(claimed);

        ExecutionSnapshot snapshot =
            snapshotRepository.load(claimed.tenantId(), claimed.snapshotId());

        RunContext context = contextAssembler.assemble(claimed, snapshot);

        while (!context.isComplete()) {
            leaseService.assertStillOwner(claimed);
            budgetService.assertWithinHardLimits(context);

            ModelDecision decision = modelGateway.nextDecision(context);

            if (decision.isFinalAnswer()) {
                resultService.persistFinalResult(claimed, decision.finalAnswer());
                runRepository.completeSucceeded(claimed, decision.finalAnswer());
                return;
            }

            for (ToolCallProposal proposal : decision.toolCalls()) {
                leaseService.assertStillOwner(claimed);
                ToolExecutionPlan plan = toolPolicy.plan(context, proposal);

                if (plan.requiresApproval()) {
                    approvalService.createApprovalAndPause(claimed, plan);
                    return;
                }

                ToolResult result = toolOperationService.executeOrReconcile(
                    claimed, context, plan
                );

                context = context.withToolResult(result);
            }

            checkpointService.persist(claimed, context);
        }
    } catch (LeaseLostException e) {
        // 旧世代停止提交；已发出的外部请求由 ToolOperation 对账处理。
        metrics.increment("agent.worker.lease_lost");
    } catch (BudgetExceededException e) {
        runRepository.completeFailedIfOwner(claimed, "BUDGET_EXCEEDED");
    } catch (Exception e) {
        failureHandler.classifyAndPersist(claimed, e);
    }
}
```

这是编排骨架而非可直接上线的完整实现。所有 Repository 的写入必须使用条件更新与 fencing；异常分类不能把所有异常统一视为可重试。

### 9.5 ModelGateway

``` java
public interface ModelGateway {
    ModelResponse chat(ModelRequest request);

    Flux<ModelStreamChunk> stream(ModelRequest request);

    ModelMetadata metadata();
}
```

约束：

- 领域层只依赖该接口，不直接依赖供应商 SDK 或 Spring AI 类型。
- 请求必须携带 deadline、模型策略版本、可用工具 Schema、Token 预算和审计关联。
- 模型输出必须先解析为结构化决策，再进行平台侧策略校验。
- 模型切换不能导致已完成的外部副作用再次执行。
- 不在普通日志中记录完整敏感 Prompt；保存必要的版本、摘要和受控引用。

### 9.6 ContextAssembler

``` java
public interface ContextAssembler {
    ModelRequest assemble(
        RunContext context,
        ExecutionSnapshot snapshot,
        ModelMetadata modelMetadata
    );
}
```

实施规则：

1.  先装配版本化系统 Prompt 和 Agent 指令。
2.  加入当前目标、执行摘要、最近必要消息。
3.  加入工具结果的最小 `modelView` 和来源引用。
4.  检索内容必须经过租户和数据权限过滤。
5.  依据模型上下文窗口与安全余量裁剪；不可依赖硬编码 token 常量。
6.  摘要保留来源消息 ID、工具操作 ID 和原始结果引用。
7.  脱敏不能代替授权；无权访问的数据不能因脱敏后进入上下文。

------------------------------------------------------------------------

## 10. REST API 契约

### 10.1 API 约定

- Base path：`/api/v1`
- 身份：由现有 IAM/OIDC/JWT 体系验证。
- 所有请求必须执行租户边界与资源级授权。
- 创建 Run 必须提供 `Idempotency-Key`。
- 错误返回统一结构，不返回堆栈、密钥或跨租户资源是否存在等信息。
- `runId` 不是授权凭据；后续查询和订阅仍须鉴权。

### 10.2 创建 Run

`POST /api/v1/agents/{agentId}/runs`

Headers:

``` http
Authorization: Bearer <access-token>
Idempotency-Key: 7d9d8f1b-unique-client-operation-key
Content-Type: application/json
```

请求：

``` json
{
  "goal": "请汇总本周已授权项目的进展，并生成一份简报",
  "conversationId": "b2a5d0c0-4d43-4a22-8d10-9fd8e20e91d0",
  "options": {
    "responseFormat": "markdown"
  },
  "budgetOverrides": {
    "maxIterations": 8,
    "maxToolCalls": 12
  }
}
```

响应：

``` http
HTTP/1.1 202 Accepted
Content-Type: application/json
```

``` json
{
  "runId": "1c7b4e43-bf1d-4b63-8b74-7a5b1f4c4c9a",
  "status": "QUEUED",
  "statusUrl": "/api/v1/runs/1c7b4e43-bf1d-4b63-8b74-7a5b1f4c4c9a",
  "eventsUrl": "/api/v1/runs/1c7b4e43-bf1d-4b63-8b74-7a5b1f4c4c9a/events"
}
```

幂等规则：

- 同一租户、同一操作范围、同一幂等键、同一请求摘要：返回原 Run。
- 同一幂等键但摘要不同：`409 IDEMPOTENCY_CONFLICT`。
- 服务端从认证上下文获取 tenant/actor；忽略或拒绝请求体中任何可信身份字段。
- 客户端只能收紧预算，服务端硬上限优先。

### 10.3 查询 Run

`GET /api/v1/runs/{runId}`

响应示例：

``` json
{
  "runId": "1c7b4e43-bf1d-4b63-8b74-7a5b1f4c4c9a",
  "status": "RUNNING",
  "createdAt": "2026-10-17T10:00:00Z",
  "startedAt": "2026-10-17T10:00:02Z",
  "deadlineAt": "2026-10-17T10:05:00Z",
  "progress": {
    "completedSteps": 2,
    "totalSteps": 4
  },
  "resultRef": null,
  "error": null
}
```

进度字段属于观测快照，不应被当作严格业务承诺。结果引用读取时需重新执行资源授权。

### 10.4 取消 Run

`POST /api/v1/runs/{runId}/cancel`

``` json
{
  "reason": "用户请求停止"
}
```

响应 `202 Accepted` 表示取消请求已受理，不代表外部请求已撤销。Worker 在下一安全检查点停止新操作，并对在途工具操作按 UNKNOWN 协议处理。

### 10.5 重试 Run

`POST /api/v1/runs/{runId}/retry`

仅允许符合策略的失败任务。重试时必须说明是：

- 重新执行 Run 的剩余安全步骤；
- 对原工具操作进行对账；
- 或创建新的业务操作。

不得因 Run 重试而自动生成新的逻辑工具操作幂等键。

### 10.6 API 列表

| 方法  | 路径                                     | 说明                     |
|:------|:-----------------------------------------|:-------------------------|
| POST  | `/api/v1/agents`                         | 创建 Agent               |
| GET   | `/api/v1/agents`                         | 分页查询授权范围内 Agent |
| PATCH | `/api/v1/agents/{agentId}`               | 修改草稿                 |
| POST  | `/api/v1/agents/{agentId}/versions`      | 创建候选版本             |
| POST  | `/api/v1/agents/{agentId}/publish`       | 校验并发布指定版本       |
| POST  | `/api/v1/agents/{agentId}/runs`          | 创建异步 Run             |
| GET   | `/api/v1/runs/{runId}`                   | 查询 Run                 |
| GET   | `/api/v1/runs/{runId}/steps`             | 查询步骤                 |
| POST  | `/api/v1/runs/{runId}/cancel`            | 提交取消请求             |
| POST  | `/api/v1/runs/{runId}/retry`             | 重试符合规则的任务       |
| GET   | `/api/v1/runs/{runId}/events`            | SSE 事件订阅             |
| GET   | `/api/v1/runs/{runId}/approvals`         | 查询审批                 |
| POST  | `/api/v1/approvals/{approvalId}/approve` | 批准具体操作             |
| POST  | `/api/v1/approvals/{approvalId}/reject`  | 拒绝具体操作             |
| POST  | `/api/v1/conversations`                  | 创建会话                 |
| GET   | `/api/v1/conversations/{id}/messages`    | 读取消息                 |
| GET   | `/api/v1/tools`                          | 查询当前可用工具         |
| GET   | `/api/v1/usage`                          | 查询授权范围内用量       |

### 10.7 错误响应

``` json
{
  "requestId": "req_01J9EXAMPLE",
  "errorCode": "BUDGET_EXCEEDED",
  "message": "当前执行已达到预算限制",
  "retryable": false
}
```

建议错误码：

| 错误码                      | HTTP | 说明                                            |
|:----------------------------|-----:|:------------------------------------------------|
| `VALIDATION_ERROR`          |  400 | 请求格式或字段非法                              |
| `AUTHENTICATION_REQUIRED`   |  401 | 未认证或凭证无效                                |
| `TENANT_ACCESS_DENIED`      |  403 | 租户访问被拒绝                                  |
| `RESOURCE_FORBIDDEN`        |  403 | 资源级权限不足                                  |
| `RESOURCE_NOT_FOUND`        |  404 | 不存在或无权访问，按安全策略统一                |
| `IDEMPOTENCY_CONFLICT`      |  409 | 幂等键对应不同请求摘要                          |
| `RUN_STATE_CONFLICT`        |  409 | 当前状态不允许该操作                            |
| `TOOL_NOT_ALLOWED`          |  403 | 工具不在允许集合                                |
| `TOOL_SCHEMA_INVALID`       |  422 | 工具参数不符合 Schema/业务规则                  |
| `APPROVAL_REQUIRED`         |  409 | 操作需要审批                                    |
| `TOOL_SIDE_EFFECT_CONFLICT` |  409 | 副作用操作与已有操作冲突                        |
| `TOOL_RESULT_UNKNOWN`       |  409 | 外部操作结果未知，需对账                        |
| `QUOTA_EXCEEDED`            |  429 | 租户配额超限                                    |
| `MODEL_RATE_LIMITED`        |  429 | 模型供应商限流                                  |
| `BUDGET_EXCEEDED`           |  422 | 单次执行预算耗尽                                |
| `LEASE_LOST`                | 内部 | Worker 执行权已失效，不直接暴露为普通客户端错误 |

错误码的 HTTP 映射需在 OpenAPI 中固定，并保持向后兼容。

------------------------------------------------------------------------

## 11. SSE 事件与续传

### 11.1 事件格式

每个事件至少包含：

- `tenant_id`
- `run_id`
- `seq`
- `event_type`
- `schema_version`
- `created_at`
- 必要 payload 或受控 payload 引用

事件示例：

``` text
id: 42
event: run.step.completed
data: {"runId":"1c7b4e43-bf1d-4b63-8b74-7a5b1f4c4c9a","seq":42,"stepId":"...","status":"SUCCEEDED"}
```

### 11.2 续传规则

- SSE `id` 使用数据库持久化的 `seq`。
- 客户端通过 `Last-Event-ID` 传递最后收到的序号。
- 服务端先验证 Run 的资源访问权限，再查询 `seq > lastEventId` 的事件。
- 客户端按事件 ID 去重，允许网络层重复投递。
- 游标已超出保留期时，返回明确的快照/重置语义，不静默从头返回。
- `model.token` 类高频事件可选；关键状态迁移必须持久化。
- 事件 payload 不放完整敏感 Prompt、密钥或不必要的业务数据。
- SSE 连接断开不取消 Run；客户端重连不触发任务重执行。

------------------------------------------------------------------------

## 12. 审批与授权

### 12.1 执行前授权链

每次工具调用至少检查：

1.  租户有效、调用者仍为有效成员。
2.  Agent 与 Run 在当前调用者授权范围内。
3.  工具存在于 Run 固定版本的允许工具清单。
4.  当前工具版本可执行，且 Schema/策略版本匹配。
5.  调用者对目标业务资源具备当前权限。
6.  参数通过 JSON Schema 和业务规则验证。
7.  当前预算、配额、并发与 deadline 允许执行。
8.  如需审批，审批记录与当前 operation、参数摘要、目标资源和策略版本一致。
9.  外部地址符合 SSRF / Egress 策略。
10. Run 未取消，当前 Worker 持有有效 fencing token。

### 12.2 审批生命周期

``` mermaid
sequenceDiagram
    participant W as Worker
    participant P as Policy Engine
    participant DB as PostgreSQL
    participant U as Approver
    W->>P: 评估工具操作
    P-->>W: NEEDS_APPROVAL
    W->>DB: 持久化 Operation + Approval + Run 状态
    DB-->>W: WAITING_APPROVAL
    U->>DB: approve/reject（资源级授权）
    DB-->>U: 决策已记录
    W->>DB: 恢复调度
    W->>P: 重新校验授权、参数摘要、预算、资源状态
    P-->>W: ALLOW / DENY
    W->>DB: 原子消费审批并继续或拒绝
```

审批不能替代执行时授权。参数、工具版本、目标资源或关键数据范围变化时必须重新审批。审批消费必须通过条件更新确保一次性使用。

------------------------------------------------------------------------

## 13. 安全与外部 HTTP 工具

### 13.1 SSRF 与 Egress

- 所有外部 HTTP 请求经受控 Egress Proxy 或等效出站层。
- 限制协议、主机名、端口和允许访问的域名。
- 检查 DNS 解析结果，阻断 loopback、私网、链路本地、云元数据地址和不允许的 IPv4/IPv6 范围。
- 每次重定向都重新校验最终目标；防止 DNS rebinding。
- 设置连接超时、读取超时、响应大小、压缩膨胀、内容类型和重定向次数限制。
- 凭据由 KMS/Vault 注入；不得进入 Prompt、消息历史、异常文本或普通日志。
- 工具响应视为不可信内容；必须防范 Prompt Injection 和恶意序列化载荷。

### 13.2 多租户边界

- TenantContext 只由认证层创建。
- 数据层统一增加租户条件，RLS 作为纵深防御。
- 复合外键确保子资源和父资源属于同一租户。
- MQ payload 的 tenant ID 只能作为提示，必须与数据库记录核对。
- 缓存 key、对象存储路径、向量索引、事件订阅和审计查询都纳入租户隔离。
- 跨租户运营查询使用独立受限角色、审批与审计。
- 不允许通过 `runId`、`conversationId` 或 `resultRef` 直接绕过资源授权。

### 13.3 日志与敏感数据

禁止写入普通日志/Trace 标签：

- Access token、API key、密钥材料。
- 完整敏感 Prompt 和模型上下文。
- 未脱敏的个人信息、业务机密和完整工具响应。
- 可直接访问对象存储的长期凭据。

允许记录最小必要的 request ID、trace ID、租户内部标识（按合规规范）、模型名、耗时、Token 数、工具 ID、稳定错误码和 operation ID。高基数标识不应直接作为 Prometheus 标签。

------------------------------------------------------------------------

## 14. 失败分类与恢复动作

| 场景                       | 分类           | 动作                                       |
|:---------------------------|:---------------|:-------------------------------------------|
| 模型限流 / 暂时失败        | 可重试         | 按 Retry-After、指数退避和总 deadline 调度 |
| 模型返回格式非法           | 受限重试或失败 | 记录结构化解析错误；限制纠错次数           |
| 工具 Schema 校验失败       | 不可直接重试   | 记录参数摘要；要求模型修正或结束           |
| 工具明确拒绝且确认无副作用 | 策略性失败     | 按错误类别决定是否重试                     |
| 工具超时、连接中断         | UNKNOWN        | 先查下游状态或用同一幂等键对账             |
| Worker 在工具请求后崩溃    | UNKNOWN        | 接管后先检查操作账本和下游状态             |
| Worker 续租失败            | 执行权丢失     | 停止新操作；禁止提交旧世代结果             |
| MQ 重复投递                | 正常重复       | 数据库领取条件保证幂等                     |
| Outbox 重复发布            | 正常重复       | 消费端幂等，不重复产生逻辑副作用           |
| 预算耗尽                   | 终止或等待策略 | 原子拒绝新调用；记录 usage 与原因          |
| 审批过期 / 参数摘要变化    | 拒绝执行       | 重新申请审批或结束                         |
| 用户取消                   | 协作式取消     | 设置取消标志，Worker 在安全点停止          |
| Run deadline 到达          | 超时           | 停止新操作；在途操作进入对账               |
| 工具版本已下线             | 恢复受限       | 明确兼容、迁移或人工恢复，不静默切换       |

错误分类器必须输出稳定的 `errorCode`、`retryable`、`sideEffectState`、`retryAfter` 和审计上下文。不得仅凭异常类名决定是否重试。

------------------------------------------------------------------------

## 15. 自动化测试设计

### 15.1 测试分层

1.  **单元测试**：状态迁移、预算、参数摘要、幂等键、上下文裁剪、错误分类。
2.  **数据库集成测试**：PostgreSQL DDL、事务边界、唯一约束、RLS、租约竞争、事件序号。
3.  **MQ/Outbox 集成测试**：重复发布、确认前崩溃、死信、重复消费。
4.  **工具契约测试**：Schema、超时、幂等键、UNKNOWN、对账能力。
5.  **安全测试**：跨租户 IDOR、RLS 绕过、工具越权、审批绕过、SSRF、Prompt Injection。
6.  **故障注入**：在关键事务、网络调用和 Worker 生命周期的每个断点注入崩溃。
7.  **Agent 评测**：固定任务集、Mock 工具、完成率、工具选择/参数正确率、成本与延迟。
8.  **灾备演练**：备份恢复、死信修复、未知副作用对账、密钥轮换、供应商故障切换。

### 15.2 必须自动化的关键测试用例

| ID           | 场景                                      | 预期结果                            |
|:-------------|:------------------------------------------|:------------------------------------|
| RUN-001      | 同幂等键、相同请求摘要并发创建            | 仅一个 Run；所有请求返回同一资源    |
| RUN-002      | 同幂等键、不同请求摘要                    | 返回 `IDEMPOTENCY_CONFLICT`         |
| RUN-003      | Run 插入成功前事务失败                    | 不存在 Run/初始事件/Outbox 部分数据 |
| RUN-004      | Run 事务提交后、发布 MQ 前 Publisher 崩溃 | Outbox 恢复后最终发布               |
| RUN-005      | 同一 MQ 消息重复投递                      | 仅一个 Worker 获得执行租约          |
| LEASE-001    | Worker A 租约过期，Worker B 接管          | B 的 fencing token 大于 A           |
| LEASE-002    | Worker A 在 B 接管后提交结果              | 数据库拒绝 A 的状态更新             |
| LEASE-003    | Worker 续租失败                           | Worker 停止新工具调用并记录指标     |
| TOOL-001     | 工具调用明确成功后 Run 重试               | 读取原操作结果，不重复副作用        |
| TOOL-002     | 工具超时但下游实际成功                    | 操作进入 UNKNOWN 并通过对账确认     |
| TOOL-003     | 相同幂等键、不同参数摘要                  | 拒绝执行                            |
| TOOL-004     | 下游无幂等且无查询能力的写工具            | 禁止自动重试或要求人工审批          |
| BUDGET-001   | 两个并发调用竞争剩余预算                  | 预算硬上限不被突破                  |
| BUDGET-002   | usage 结算消息重复消费                    | 不重复计费                          |
| APPROVAL-001 | 审批后参数摘要变化                        | 执行被拒绝并要求重新审批            |
| APPROVAL-002 | 审批过期或已消费后再次使用                | 条件更新失败                        |
| TENANT-001   | 租户 A 查询租户 B 的 Run ID               | 不泄露数据                          |
| TENANT-002   | 连接池复用后切换租户事务                  | 不发生上下文串租                    |
| SSE-001      | 客户端断线后携带 Last-Event-ID 重连       | 从下一个持久化序号继续              |
| SSE-002      | 订阅无权访问的 Run                        | 拒绝订阅                            |
| SECURITY-001 | HTTP 工具访问云元数据或私网地址           | Egress 层阻断                       |
| SECURITY-002 | 工具结果包含伪造系统指令                  | 不提升为系统指令或授权              |
| RECOVERY-001 | 工具版本在旧 Run 恢复前下线               | 进入显式兼容/人工恢复路径           |

### 15.3 并发与故障注入重点

至少覆盖以下崩溃点：

- 创建 Run：数据库提交前、提交后但返回响应前。
- Outbox：投递前、投递后但标记 `PUBLISHED` 前。
- Worker：领取后、执行前、工具请求前、请求发出后、结果持久化前、Run 终态提交前。
- 审批：审批决策与 Run 取消同时发生。
- 预算：预留成功后进程崩溃、调用成功后结算前崩溃。
- SSE：事件已写入但客户端未收到、客户端收到但未确认游标。
- 租约：续租与接管竞争、旧世代晚到响应、数据库短暂不可用。

------------------------------------------------------------------------

## 16. 可观测性与运行手册

### 16.1 核心指标

| 领域      | 指标                                                |
|:----------|:----------------------------------------------------|
| API       | QPS、P95/P99、错误率、幂等冲突、鉴权拒绝            |
| Run       | 成功率、失败分类、运行时长、重试次数、无进展时间    |
| Worker    | 活跃租约、续租失败、过期 RUNNING、fencing 拒绝      |
| Outbox/MQ | 最老未发布事件、积压、死信、消费延迟                |
| 模型      | 首 Token 延迟、调用失败、限流、Token、供应商请求 ID |
| 工具      | 成功率、UNKNOWN、超时、对账积压、重复操作拦截       |
| 预算      | 预留金额、实际费用、未结算 usage、超额拒绝          |
| 安全      | 跨租户拒绝、工具越权、审批异常、SSRF 拦截           |
| SSE       | 活跃连接、重连率、事件延迟、游标重置次数            |

Prometheus 标签避免包含高基数的 `run_id`、`user_id`、`operation_id`；这些信息通过日志和 Trace 关联。

### 16.2 必须有操作手册的事件

- Outbox 持续积压。
- MQ 死信增长。
- Run 长时间 QUEUED 或无进展。
- 过期租约与接管失败。
- UNKNOWN / RECONCILIATION_REQUIRED 积压。
- 预算预留无法释放或结算差异。
- 模型供应商限流/故障。
- RLS 或租户边界测试失败。
- 对象存储结果引用不可访问。
- 备份恢复失败或密钥轮换异常。

### 16.3 恢复与灾备

- PostgreSQL 备份必须定期进行恢复演练。
- 明确 RPO/RTO，不能把 Kubernetes 或多副本本身当作灾备。
- MQ 持久化、死信、消费者恢复和重放规则必须文档化。
- 定义数据保留、归档、删除、导出和审计查询流程。
- 密钥轮换与应急撤销需演练。
- 对象存储预签名 URL 不能被视为永久授权凭据。
- 工具未知结果的人工对账流程必须有责任人、工单和审计闭环。

------------------------------------------------------------------------

## 17. 分阶段开发计划

建议以 16–18 周作为初始排期估算，而不是交付承诺。实际周期应根据现有 IAM、业务工具、SRE 平台、团队规模及合规要求重新评估。

### Phase 0：架构验证（第 1–2 周）

交付：

- 领域模型和状态机。
- DDL 初稿、租户/RLS 验证。
- 租约/fencing 并发 PoC。
- Outbox + MQ PoC。
- 工具幂等与 UNKNOWN 对账协议。
- 模型接入和 Tool SPI PoC。
- 威胁模型、容量假设、待决策清单。

退出条件：关键故障场景设计评审通过；主 MQ、数据访问方式、租户策略和操作协议冻结。

### Phase 1：闭环 MVP（第 3–7 周）

交付：

- Agent CRUD、草稿、版本和发布。
- 异步 Run 创建、查询和事件。
- 单 Agent Loop、ContextAssembler、ModelGateway。
- Tool SPI、Schema 校验和只读业务工具。
- Run Step、Run Event、基础审计。
- Outbox 与基础 Worker。

退出条件：端到端任务可执行、可查询、可追踪；幂等入口和基础权限测试通过。

### Phase 2：可靠性与安全（第 8–12 周）

交付：

- 租约领取、续租、接管和 fencing。
- ToolOperation 账本与 UNKNOWN 对账。
- 重试、取消、deadline 和检查点。
- 审批、预算预留/结算、usage 幂等。
- RLS、跨租户安全测试、SSRF/Egress 策略。
- SSE 续传和游标重置语义。

退出条件：故障注入、重复副作用、跨租户、审批绕过和预算绕过测试通过。

### Phase 3：企业接入与生产化（第 13–18 周）

交付：

- IAM、业务服务、对象存储和计费集成。
- 指标、Trace、告警和操作手册。
- 压测、备份恢复、密钥轮换和灾备演练。
- 固定 Agent 评测集和试点租户。
- 生产准入报告、未关闭风险与负责人。

退出条件：生产门禁全部通过；RPO/RTO、容量目标、支持流程和高风险接受记录明确。

------------------------------------------------------------------------

## 18. 生产准入清单（Definition of Done）

### 功能

- [ ] Agent 草稿、版本、发布和快照追溯可用。
- [ ] Run 创建、查询、取消、受控重试和结果读取可用。
- [ ] 工具参数 Schema、执行时授权和审批流程可用。
- [ ] SSE 支持断点续传、授权校验和游标重置。

### 一致性与恢复

- [ ] Run + 初始事件 + Outbox 原子提交。
- [ ] 重复消息和重复发布安全。
- [ ] Worker 崩溃后任务可接管。
- [ ] 旧 fencing 世代不能提交状态或结果。
- [ ] 工具结果未知时进入 UNKNOWN，对账前不盲目重放。
- [ ] usage 重放不会重复计费。
- [ ] 终态、结果、事件和结算具有明确事务或补偿语义。

### 安全

- [ ] 跨租户 Run、Step、Event、Conversation、ToolOperation、Approval、文件和缓存访问全部拒绝。
- [ ] RLS、连接池和事务上下文测试通过。
- [ ] 工具越权、审批绕过、SSRF、Prompt Injection 和密钥泄漏测试通过。
- [ ] 外部内容不被视为可信系统指令。
- [ ] 日志和 Trace 不含密钥及不必要敏感数据。

### 预算与计量

- [ ] 并发预留不突破硬上限。
- [ ] 失败释放、重复结算、供应商 usage 缺失和账单差异有明确策略。
- [ ] 客户端参数和模型输出不能扩大预算。
- [ ] 恢复后沿用原始 deadline 和累计预算。

### 运维与容量

- [ ] Outbox、死信、长时间无进展 Run、UNKNOWN 对账积压具备告警。
- [ ] 备份恢复、密钥轮换、死信修复和人工对账已演练。
- [ ] 代表性任务压测完成，数据库连接池、模型并发、下游限流和对象存储成本有结论。
- [ ] RPO/RTO、数据保留、删除、导出与支持流程已确定。
- [ ] 所有高风险待决策项都有负责人、截止时间和接受记录。

------------------------------------------------------------------------

## 19. 主要风险与责任分工

| 风险/待决策          | 推荐处理                                                     | 责任方                 |
|:---------------------|:-------------------------------------------------------------|:-----------------------|
| 主 MQ 选型           | 按现有运维能力、吞吐、延迟、死信能力评估后只选一种           | 架构师 + SRE           |
| 下游工具幂等能力不足 | 按工具登记幂等、查询、补偿能力；不满足者限制为只读或人工审批 | 业务系统负责人         |
| RLS 与连接池上下文   | 验证角色、事务变量、回滚和连接复用                           | DBA + 后端负责人       |
| 模型数据驻留         | 核对供应商、区域、日志保留和敏感字段策略                     | 安全/法务 + AI 负责人  |
| RPO/RTO 与容量       | 通过业务分级和压测定义，不预设虚假数值                       | SRE + 产品             |
| 工具版本下线         | 定义兼容窗口和旧 Run 恢复策略                                | 架构师 + 工具负责人    |
| 预算误差             | 明确预留、估算、最终账单对账与差异处理                       | 产品/财务 + 平台负责人 |
| 事件数据增长         | 定义保留期、归档、分页和快照恢复机制                         | 平台 + DBA             |
| 过度微服务化         | 首期模块化单体 + 独立 Worker，按证据拆分                     | 架构师                 |

------------------------------------------------------------------------

## 20. 最终实施约束

1.  先完成 Phase 0 的并发、租约、RLS、Outbox 和工具 UNKNOWN PoC，再批量铺开业务功能。
2.  状态机、工具操作状态、API 错误码、事件 Schema、幂等语义和数据库约束均属于平台契约；变更必须有版本与迁移方案。
3.  不允许把”模型调用成功”当作”Run 成功”；最终状态必须由平台持久化。
4.  不允许把”HTTP 超时”当作”下游未执行”；未知结果必须对账。
5.  不允许把 Redis 锁当作唯一执行权，也不允许把 MQ 消息当作权威任务状态。
6.  不允许仅凭不可变 Prompt 哈希承诺完整复现；必须区分状态恢复、审计追溯与模型结果复现。
7.  不允许因赶工把租户隔离、幂等、恢复、审计和预算硬限制推迟到正式开放之后。
8.  所有外部工具按风险等级管理；下游能力不足时降低自动化等级，而不是弱化平台安全约束。

> **一句话原则：模型可以建议，平台必须做主。只有当任务可恢复、工具可控、权限可验证、配置可追溯、费用可结算时，Agent 才具备生产化基础。**

------------------------------------------------------------------------

## 附录 A：开发任务拆分建议

| Epic            | 子任务                        | 验收产物                      |
|:----------------|:------------------------------|:------------------------------|
| A1 数据模型     | DDL、索引、迁移、RLS          | 数据库迁移脚本 + 集成测试     |
| A2 Agent 管理   | CRUD、版本、发布、快照        | API + 发布校验 + 不可变性测试 |
| A3 Run 服务     | 幂等创建、状态查询、取消      | API + 并发幂等测试            |
| A4 调度基础     | Outbox、MQ、消费者            | 重复发布/消费测试             |
| A5 Worker       | 领取、租约、续租、fencing     | 故障注入与接管测试            |
| A6 Runtime      | Loop、上下文、模型适配        | 固定任务集评测                |
| A7 Tool Runtime | SPI、Schema、权限、副作用账本 | 工具契约测试                  |
| A8 Approval     | 审批对象、一次性消费、复核    | 参数变更/过期/撤销测试        |
| A9 Budget       | 预留、结算、释放、对账        | 并发预算与幂等计费测试        |
| A10 Events      | 事件追加、SSE、续传           | 断线续传和越权测试            |
| A11 Security    | SSRF、RLS、日志脱敏           | 安全测试报告                  |
| A12 Operations  | 指标、告警、灾备、Runbook     | 生产准入报告                  |

## 附录 B：建议的代码包结构

``` text
com.example.saasagent
├── api
│   ├── agent
│   ├── run
│   ├── approval
│   ├── conversation
│   └── error
├── application
│   ├── agent
│   ├── run
│   ├── approval
│   ├── budget
│   └── event
├── domain
│   ├── agent
│   ├── run
│   ├── tool
│   ├── approval
│   ├── budget
│   └── policy
├── ports
│   ├── persistence
│   ├── messaging
│   ├── model
│   ├── tool
│   └── observability
├── infrastructure
│   ├── postgres
│   ├── outbox
│   ├── mq
│   ├── redis
│   ├── objectstore
│   ├── model
│   └── tool
├── worker
│   ├── scheduler
│   ├── lease
│   ├── executor
│   └── recovery
└── testkit
    ├── fixtures
    ├── tools
    └── faultinjection
```

## 附录 C：实施前检查

- [ ] 已取得并评审 v2.1 架构基线。
- [ ] 已选定主 MQ 与数据库访问技术。
- [ ] 已对 DDL 在目标 PostgreSQL 版本执行验证。
- [ ] 已定义数据库迁移、回滚/前向修复策略。
- [ ] 已建立至少一个只读业务工具和一个模拟副作用工具。
- [ ] 已建立 UNKNOWN 对账的端到端演练。
- [ ] 已建立租户隔离与 RLS 自动化测试。
- [ ] 已建立 Runbook 和生产准入评审流程。
