# P4-D06 真实 broker 端到端验证（关闭 D03 的 L-1）

> 阶段：P4　**状态**：✅ 完成（真实 broker 端到端 3 项 + 生产者行为 2 项，全部执行）
> 关闭的 D03 诚实声明：**L-1**（端到端未在真实 broker 上验证）
> 计划依据：`P0-D06 §3`「容量 / 恢复」；D03 §7 诚实边界 L-1 原文「归属 P4-D04」为过时指认，实际由本片关闭

---

## 0. 本片要解决的缺口

D03 修复了「断链第二层」并接好 MQ 消费链路，但 §7 留下 **L-1**：

> ⚠️ **端到端未在真实 broker 上验证** —— 本片证明的是「装配完整、配置正确、断链已修复」，
> **不是**「消息已成功送达 Worker」。把前者记为后者，就是本片要根治的那类错误。

D05 也明确指出：**L-1 仍是本片最重要的诚实声明：不得把「逻辑与装配已验证」记为「消息已送达」**。

本片要补的正是这一环：在**真实 RabbitMQ** 上证明「发布确认 → 入队 → 被消费」全链路打通，
且深度探测反映真实队列。这与 D03「装配完整」互为**连通性测试**与**行为测试**的两半——
只有两边都绿，才能说「消息真的会动」。

---

## 1. 决策

### D-6 只装配 MQ 组件，直连生产所用的实现

**为何不复用整条 Spring 上下文**：生产链路要接 PG（TenantSlugResolver）与一整套 Bean，
会把「MQ 端到端」与「PG/租户装配」耦合——一处 PG 配置问题就会让本片无法证明 MQ 本身可用。
故本片**只装配 MQ 组件**（连接的正是生产所用的 `RabbitMessageSender` 与 `RabbitQueueDepthProbe`
实现），直连本机开发 broker。这与 14 个需要 `IT_PG_URL` 的 IT 同类：没有 broker 的环境跑此片会失败，
而那正是 IT 的正确行为（它本就要在真实基建上验证）。

**拓扑声明与生产线同参数**：本片用 `RabbitAdmin` 幂等声明
`agent.run.dispatch`（topic, durable）/ `agent.run.execute`（quorum + delivery-limit=3 + DLX）/ DLX。
若真实队列类型与本声明不一致，`declareQueue` 会抛 `PRECONDITION_FAILED`——那正好暴露
「真实拓扑与代码声明不符」的隐患。⚠️ 被动声明不回传 `arguments`（AMQP 0-9-1 限制），
故 `x-queue-type` / `x-delivery-limit` 等拓扑属性由应用 `Queue` Bean（quorum + delivery-limit=3）
+ 连线测试 C-5 保证；本片聚焦「真实发送/确认/消费」闭环。

---

## 2. 验证证据（全部真实执行）

### 2.1 真实 broker 端到端 `RealBrokerEndToEndIT`（3 项）

| 片 | 证明 | 结果 |
|---|---|---|
| **E-1** 队列拓扑 | `agent.run.execute` 被动声明成功（队列存在且可访问）| ✅ |
| **E-2** 发布确认→投递→消费回环 | 发一条 `RUN_DISPATCH` → publisher confirms 确认 → 深度 +1 → 阻塞收取到消息 → 载荷 `runId/tenantId` 完整回环 → 事件类型头 `x-agent-event-type=RUN_DISPATCH` → 收走后深度回落 | ✅ |
| **E-3** 探测 fail-closed | 探测不存在的队列必须抛 `RuntimeException`（不得返回 0 伪装为空）| ✅ |

### 2.2 生产者行为 `RabbitMessageSenderTest`（2 项）

| 片 | 证明 | 结果 |
|---|---|---|
| **P-1** 出口消息属性 | `send()` 在**进入 MQ 前**把消息标为 `PERSISTENT` 且带 `x-agent-event-type` 头 | ✅ |
| **P-2** 确认失败处理 | broker 未确认（nack）必须抛 `MessagePublishException`（不得静默标记 published 致丢消息）| ✅ |

> ⚠️ **为何分两片**：quorum 队列在 `basic.get` 回包里会**剥掉** `delivery_mode`
> （quorum 本就总落盘，该字段对它无意义），故 E-2 收回到的消息 `getDeliveryMode()` 恒为 `null`，
> **无法在那条链路里证明「生产者设了 PERSISTENT」**。
> 持久化的正确性由两处分别保证：① 本片 P-1 证明生产者设 `PERSISTENT`；
> ② 生产 quorum 拓扑（WorkerLoopConfig + 连线测试 C-5）保证队列总落盘。
> 二者缺一都会丢消息，故必须分片覆盖，不能在一条链路里同时证明。

---

## 3. ⚠️ 本片实测发现的关键事实（诚实记录）

### 3.1 E-2 初版断言是「必败的假红」—— quorum 队列剥 delivery_mode

初版 E-2 断言 `received.getMessageProperties().getDeliveryMode() == PERSISTENT`，
在真实 broker 上**稳定失败**（`expected: PERSISTENT but was: null`）。

根因不是生产代码没设 PERSISTENT（`RabbitMessageSender` 第 56 行确实设了），
而是 **quorum 队列对 `basic.get` 回包不回传 `delivery_mode`**。
这是 RabbitMQ 的已知行为：quorum 队列总是持久化，`delivery_mode` 对它无意义，broker 直接不携带。

**教训（与本项目反复踩的坑同源）**：这条断言若留在 E-2，本片会**永远红**——
但它红的不是「消息会丢」，而是「在错误的位置验证持久化」。
正确做法是把「生产者设 PERSISTENT」移到生产者行为测试（P-1），把「队列总落盘」
交给 quorum 拓扑保证。这避免了「为了过测而把断言改成恒真」或「误以为生产端没设 PERSISTENT」。

### 3.2 本机 broker 凭据需从 `config.env` 注入

D03 L-1 原文提到「本机 RabbitMQ 的凭据与配置默认值不一致（`ACCESS_REFUSED`）」。
本片实测确认：IT 的缺省值 `RABBITMQ_USER=agent / RABBITMQ_PASSWORD= / RABBITMQ_VHOST=agent`
与生产 `application.yml` 一致；但要真正连上，必须在运行环境 `source config.env`
（其导出的 `RABBITMQ_*` 覆盖了缺省值）。未 source 时才会 `ACCESS_REFUSED`。
本片已在运行命令中固化 `set -a && . ./config.env` 的步骤，避免后来者重蹈。

---

## 4. 诚实边界（本片仍未覆盖的事）

| # | 限制 | 为何不能在本片解决 |
|---|---|---|
| **L-a** | 未验证「broker 重启后消息仍在」（quorum 持久化的崩溃恢复语义）| 端到端 IT 不便在测试内重启 broker；该语义由 quorum 队列保证 + P-1 生产者设 PERSISTENT 共同覆盖 |
| **L-b** | 未验证「消费端 `RunDispatchConsumer` 真正处理该消息」| 那是 D03 消费容器链路（已用 `WorkerLoopWiringTest` 连通性测试覆盖）；本片聚焦「消息到达 broker 并被取出」 |
| **L-c** | 真实 broker 是 dev 单节点非集群 | 集群镜像/多数派复制的崩溃语义不在本机验证范围 |

> ⚠️ **L-1 已关闭，但不能扩大解释**：本片证明「消息在真实 broker 上发布确认→入队→被取出→载荷完整」，
> 仍需与 D03 的「消费容器接到消息并驱动执行」、D05 的「多租户发布器 + 背压」合起来，
> 才是完整的「消息从 Outbox 到执行」闭环。任一单片都不能单独记为「系统可生产运行」。

---

## 5. 与 D03 / D05 的关系

| 片 | 交付 | 本片补充 |
|---|---|---|
| D03 | 装配完整 + 断链第二层修复 + 配置正确 | L-1：端到端未验证 → **本片关闭** |
| D05 | 多租户发布器 + 跨租户复核 + 队列背压 | L-1：仍待真实 broker → **本片关闭** |
| **本片（D06）** | 真实 broker「发布确认→入队→消费回环」+ 生产者 PERSISTENT 行为 | 关闭 D03/D05 共同的 L-1 |

---

## 6. 结论

**D03 的 L-1（端到端未在真实 broker 上验证）于 2026-10-20 关闭。**
证据：`RealBrokerEndToEndIT` 3 项 + `RabbitMessageSenderTest` 2 项，全部在真实 RabbitMQ（dev broker）执行通过。
P4-D03 §7 L-1 的「归属 P4-D04」为过时指认，实际由本片（D06）关闭。
