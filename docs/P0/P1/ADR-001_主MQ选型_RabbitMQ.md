# ADR-001 主 MQ 选型：RabbitMQ 4.3（Quorum Queue）

| 字段 | 内容 |
|---|---|
| ADR 编号 | ADR-001 |
| 状态 | 已决策（DEC-12），待 M1 门禁签署 |
| 决策人 | AG-ARC 起草 / zhouwei 批准 |
| 决策日期 | 2026-10-18 |
| 关联依赖 | D-003 主 MQ |
| 关联风险 | R-006（重复/延迟/死信导致任务丢失或重复执行） |
| 复审触发条件 | RabbitMQ 4.4 GA；社区支持期届满前 30 天；P4 容量压测发现积压恢复不达标 |

---

## 1. 决策

**主 MQ 采用 RabbitMQ 4.3.6（社区版），队列类型统一为 Quorum Queue。**
**不采用 Kafka、不采用 NATS，双栈禁止**（P0-D03 §4 红线 2）。

---

## 2. 为什么选 RabbitMQ（而非 Kafka）

本项目的 MQ 定位必须先厘清，否则选型会跑偏：

> **MQ 只作为唤醒信号**（P0-D03 §2）。Worker 收到消息后**按数据库状态和租户上下文领取任务**，
> 不以消息内容为权威。权威事实源永远是 PostgreSQL（P0-D03 §4 红线 1）。

因此对 MQ 的实际要求只有五项，而非「高吞吐消息中间件」：

| 要求 | 说明 | RabbitMQ 4.3 满足方式 |
|---|---|---|
| at-least-once 投递 | 允许重复，消费端幂等 | Quorum queue Raft 复制 |
| 死信与重试 | 有副作用的工具不得盲目重试 | quorum delayed retry with backoff（4.3 新增） |
| 积压可观测 | P3-D04 要求积压监控与重放 | 管理界面 quorum 指标 + per-priority 计数 |
| 低资源占用 | 单人项目，规模有限 | Erlang BEAM，资源占用远低于 Kafka |
| 运维简单 | 无专职 SRE | 单节点/三节点，Khepri 元数据存储 |

**Kafka 不适合的原因**：

- Kafka 为日志流设计，需规划 partition / consumer group / retention，运维负担显著更高；
- 本项目消息量级低（每 Run 一条唤醒消息），Kafka 的吞吐优势无从发挥；
- Kafka 无内建死信语义，需自建；
- Kafka 引入 JVM + KRaft 协调层，与「轻量」诉求直接冲突。

---

## 3. 版本与关键约束

### 3.1 版本锁定

| 项 | 值 | 说明 |
|---|---|---|
| RabbitMQ | **4.3.6**（2026-09-16） | 当前社区支持分支最新 |
| 需 Erlang/OTP | **25.0+** | 4.3 系列要求 |
| 元数据存储 | **Khepri** | 4.3 起唯一存储，Mnesia 已移除 |
| 队列类型 | **Quorum Queue** | 唯一具备复制能力的类型 |

> ⚠️ **禁止降级到 4.2 或更早**：4.2 社区支持已于 2026-07-31 结束。
> 4.3 是当前唯一仍在社区支持期的 4.x 分支。

### 3.2 支持期风险（已接受）

| 类型 | 截止 | 项目影响 |
|---|---|---|
| 社区支持 | **2026-11-30** | 覆盖 P0～P3（至 2027-01），**P4 生产化前需评估升级 4.4** |
| 商业支持 | 2028-04-30 | 未采购 |

**项目负责人 DEC-12 决定：采用社区版并接受该窗口。** 相应义务：

- P4 生产准入评审（2027-01）前，须完成 4.4 可用性评估；
- 停维后无免费 CVE 补丁，须在 P4 安全复核中登记该残余风险；
- 升级路径须提前演练（4.2→4.3 为原地滚动，跨大版本需停机窗口）。

### 3.3 4.3 的破坏性变更（部署时须避开）

| 变更 | 影响 | 应对 |
|---|---|---|
| Mnesia 移除，Khepri 唯一 | 集群元数据存储变更 | 全新部署，无历史数据迁移负担 |
| `pause_minority` 等分区策略移除 | 网络分区行为变化 | 依赖 quorum queue 的 Raft 多数派语义 |
| Classic Queue v1 存储引擎移除 | 声明 classic v1 队列会被拒绝 | 一律声明 quorum |
| 非持久非独占队列默认禁用 | 临时队列声明被拒 | 使用持久队列或独占临时队列 |

---

## 4. 队列拓扑

### 4.1 Exchange / Queue 声明

```text
Exchange: agent.run.dispatch  (type=direct, durable=true)
    │
    ├─ routing_key=run.execute ──> Queue: agent.run.execute  (quorum, durable)
    │                                ├─ DLX: agent.run.dlx ──> Queue: agent.run.dead
    │                                ├─ x-delivery-limit: 3   （有限重试后进死信）
    │                                └─ x-queue-type: quorum
    │
    └─ routing_key=run.reconcile ─> Queue: agent.run.reconcile (quorum, durable)
                                     └─ x-queue-type: quorum
```

| 声明项 | 值 | 理由 |
|---|---|---|
| `x-queue-type` | `quorum` | 唯一有复制能力的类型 |
| `durable` | `true` | 重启不丢消息 |
| `x-delivery-limit` | **3** | 有限重试后进死信，**禁止无限重投** |
| DLX | `agent.run.dlx` | 死信独立队列便于重放与告警 |

> ⚠️ **x-delivery-limit 必须显式设置**。quorum queue 默认 delivery-limit 为 20，
> 配合「有副作用工具不得盲目重试」（P0-D03 §4 红线 7），20 次重试对
> `record_weakness` 而言风险过高。首期设为 3，超限进死信转人工处置。

### 4.2 消息体（最小化，载荷最小化原则）

```json
{
  "runId": "run-abc123",
  "tenantId": "tenant-001",
  "attempt": 1,
  "fencingToken": 12345,
  "enqueuedAt": "2026-10-20T02:15:30Z"
}
```

**消息体不含**：Prompt 内容、工具参数、学生数据、模型响应。原因：

- 消息是「唤醒信号」，Worker 会按 runId 回 DB 查权威状态；
- 消息一旦进入 MQ 就难以精确删除，携带敏感数据会扩大泄露面（P0-D05 R-015）；
- 未成年人数据合规要求数据不出境且可精确定位（P0-D00 §4）。

### 4.3 与 Outbox 的关系

```
Run 创建事务：
  INSERT run(...) + INSERT run_event(...) + INSERT idempotency_record(...)
  + INSERT outbox_event(...)        ← 同一事务原子提交（红线 3）
         │
         ▼
Outbox Publisher（独立进程）：
  轮询未发布的 outbox_event → 发布到 agent.run.dispatch
  → 标记已发布（失败可重试，R-006）
         │
         ▼
Worker 消费 → 按 runId 回 DB 领取 → 建立租约 + fencing token
```

**关键**：发布成功但标记失败会导致重复发布，消费端必须幂等（FR-005 / R-006）。

---

## 5. 与 Spring Boot 4.1 的集成

| 项 | 说明 |
|---|---|
| Starter | `spring-boot-starter-amqp` |
| AMQP 版本 | Spring AMQP 4.1.x（由 parent BOM 管理） |
| 观测 | **Spring Boot 4.1 自动装配 RabbitMQ 的 Observation 约定**（metrics/traces），直接服务 FR-017 |
| 重试 | ⚠️ **禁用内置 `@Retryable`**；统一在 Outbox Publisher 与 ModelGateway 内自研，避免与 MQ 重试双重重试（ADR-011 §3.2） |

---

## 6. 验证方式（M1 门禁前完成，P1-05）

| 验证项 | 方法 | 通过标准 |
|---|---|---|
| 事务原子性 | Run 创建后强杀进程，检查 Run/Event/Outbox 是否同生共死 | 全部存在或全部不存在 |
| 重复投递 | 同一消息投递 3 次 | 消费端幂等，不重复创建逻辑执行 |
| 发布失败恢复 | 断网后恢复 | Outbox 可重试发布，无消息丢失 |
| 死信与重放 | 触发 delivery-limit 后检查 DLX | 进死信且可重放，有告警 |
| 积压可观测 | 压测产生积压 | 指标可见积压量与消费速率 |
| fencing 联动 | 强杀 Worker 后接管 | 旧 fencing token 写入被拒绝 |

---

## 7. 决策记录

| 项 | 内容 |
|---|---|
| 决策 | RabbitMQ 4.3.6 社区版 + Quorum Queue |
| 决策人 | zhouwei |
| 起草 | AG-ARC |
| 交叉审查 | AG-SRE（运维）+ AG-QA（测试），ISO-4 |
| 日期 | 2026-10-18 |
| 状态 | 已决策，待 M1 门禁完成 P1-05 PoC 后签署 |
