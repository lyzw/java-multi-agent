# P4-D03 Worker 主循环与 MQ 接线（Outbox 中继 + 消费容器 + 优雅停机）

> **阶段**：P4-D03　**验收**：FR-019（可观测性/运维）· FR-017（指标）· ADR-001（MQ 拓扑）
> **威胁**：D-1 拒绝服务（消费能力缺失 = 任务永久积压）· D-4 资源耗尽（无并发上限）
> **状态**：✅ 完成（2026-10-19 · 22 项用例全绿 + 7 项反证验证）

---

## 0. 本片要解决的断链（第二层）

D01 修复了「`RunDispatchConsumer` 领到租约后不执行」。本片核实后发现**断链还有第二层**：

```
RunController.createRun
  └→ JdbcRunCreationRepository.insertRunAtomically   ✅ 写 run + run_event + outbox_event
       └→ RunDispatchConsumer                        ⚠️ 无人调用
            └→ RunExecutionLoop                      ❌ 从未实例化（D01 已修，但上游没人触发）
```

实测证据（`grep '@RabbitListener|RabbitTemplate'` → **零命中**；
`OutboxPublisher` 只被 `OutboxDispatchTest` 调用，无 Spring 组件注解）：

| 组件 | 状态 |
|---|---|
| `OutboxPublisher.publishOnce()` | 逻辑完整、**无调用方** |
| `spring-boot-starter-amqp` | 在 `agent-infrastructure/pom.xml` ✅ |
| 队列拓扑配置 | `application.yml` 已声明 quorum + `x-delivery-limit=3` ✅ |
| `@RabbitListener` | ❌ **零** |
| `RabbitTemplate` | ❌ **零** |
| 定时中继（outbox → MQ） | ❌ **零** |

**后果**：创建 Run 后消息永远躺在 `outbox_event` 表里，Worker 永不工作，
且**系统表面完全正常**（无报错、无告警、API 返回 201）。
这与 D01 的断链同源，但更隐蔽——D01 至少还有单元测试覆盖 `consume` 本身，
本片缺失的是**「谁调用 consume」**这一层。

> ⚠️ **D01 的教训在此再次适用**：
> 「`OutboxPublisher` 的 `publishOnce()` 测试全绿」**不能证明**它会被调用。
> 必须有**连通性测试**断言「创建 Run → 消息最终被消费」。

---

## 1. 决策

### D-1 中继采用「轮询 Outbox 表」而非「应用内直调」
**决策**：`OutboxRelay` 用定时轮询 `outbox_event` 表（`SELECT ... FOR UPDATE SKIP LOCKED`）发布到 MQ。

**理由**：
- ✅ 保持 Outbox 模式的**原子性保证**——Run 与消息在同一事务落库，
  中继崩溃不会丢消息（重启后继续轮询即可）；
- ✅ 支持**多实例中继**（`SKIP LOCKED` 使各实例互不抢占）；
- ❌ 不用「创建 Run 后直接发 MQ」——那会失去原子性（DB 提交失败但消息已发）。

**代价**：发布延迟 = 轮询间隔（本片取 200ms）。
> 这是**有意的取舍**：用 ≤200ms 的延迟换取「不丢消息」，
> 对教育场景完全可接受。

### D-2 消费用 `@RabbitListener`（手动 ack），不用 auto-ack
**决策**：`@RabbitListener(ackMode = MANUAL)`，处理完才 `basicAck`。

**理由**：`RunDispatchConsumer.ConsumeResult.RETRY` 语义要求
「可重试失败 → nack 重投」。auto-ack 下 nack 无效（消息已确认），
重试次数控制（`x-delivery-limit=3` → 死信）会完全失效。

### D-3 并发度上限来自配置，缺失即拒绝启动
**决策**：`agent.worker.concurrency` 与 `agent.worker.prefetch` **必填**，缺失则拒绝启动。

**理由**：与 D8 `RateLimitPolicy` / D4 `BudgetPolicy` 同一取舍——
退回默认值等于「悄悄用了一个没人审过的并发度」，
而在容器化环境里默认并发可能直接打垮下游模型服务。

### D-4 优雅停机：先停止领取，再等待在途完成
**决策**：停机时（`@PreDestroy`）① 停止中继领取新 outbox；
② 等待在途执行完成（最长 `shutdown-timeout`）；
③ 超时后**不强制中断**——Run 的租约会自然过期被其他实例接管。

**理由**：强制中断会留下「租约未释放」的 Run，
虽然 fencing 能保证安全，但会浪费一个租约周期。
让租约自然过期是**唯一无副作用**的停机方式。

---

## 2. 交付物

| 类 | 模块 | 职责 |
|---|---|---|
| `OutboxRelay` | agent-worker | 定时轮询 outbox 表 → `MessageSender`；可注入时钟/批量大小 |
| `RabbitMessageSender` | agent-infrastructure | `MessageSender` 的 RabbitMQ 实现（**publisher confirms**）|
| `RunExecutionListener` | agent-worker | `@RabbitListener` 容器 → `RunDispatchConsumer` |
| `WorkerLifecycleConfig` | platform-bootstrap | 中继/监听器 Bean + 优雅停机编排 |
| `WorkerPolicy` | agent-application | 配置解析（fail-closed）|

---

## 3. 关键不变量

| # | 不变量 | 强制手段 |
|---|---|---|
| I-1 | **发布前必须 `assertPayloadSafe`** | `RabbitMessageSender` 出口处二次校验（纵深防御）|
| I-2 | **消息不得含 Prompt/学生数据** | `OutboxMessage.FORBIDDEN_KEYS` + 结构测试 |
| I-3 | 重投仅限 `RETRY`，`UNKNOWN` 必须 ack 丢弃 | D01 已实现，本片用**连通性测试**证明在容器中同样成立 |
| I-4 | 消费并发不超过配置上限 | `SimpleRabbitListenerContainerFactory` concurrency |
| I-5 | 中继停机后不再领取新 outbox | `@PreDestroy` 置位 + 轮询循环检查 |

---

## 4. 测试与反证结果

### 4.1 连通性测试（`WorkerLoopWiringTest`，12 项）—— 本片核心

> ⚠️ **本类存在的唯一理由**：D01 交付的断链（全仓无 `@RabbitListener`、
> 无 `RabbitTemplate`、`OutboxPublisher` 只被单测调用）之所以长期潜伏，
> 是因为**所有测试都在直接 `new` 被测类**——
> 它们验证「机制在被调用时行为正确」，却**没有任何一个验证「它是否被调用」**。

| 用例 | 证明什么 | 结果 |
|---|---|---|
| **C-1 `OutboxRelay` Bean 存在** | ⚠️⚠️ 否则无人驱动发布器（**本片修的正是这条**）| ✅ |
| **C-2 `OutboxPublisher` 持有真实 `MessageSender`** | ⚠️⚠️ 断言「持有真实实现」而非具体类 → 换 MQ 供应商仍成立 | ✅ |
| **C-3 `RabbitExecutionListener` Bean 存在** | ⚠️⚠️ 否则消息进队列但无人处理（D01 断链同一形态）| ✅ |
| **C-4 `RunDispatchConsumer` 已装配执行器** | ⚠️⚠️ 防止回到「领取即丢弃」 | ✅ |
| **C-5a 执行队列必须 `x-queue-type=quorum`** | ⚠️ classic 在 4.x 无复制能力 | ✅ |
| **C-5b `x-delivery-limit` ≤3** | ⚠️ quorum 默认 20 → `record_weakness` 最多重复写 20 次（Sev-0）| ✅ |
| **C-5c 死信队列不挂 DLX** | 避免死信循环 | ✅ |
| **C-6a–d 配置 fail-closed** | 缺失/≤0/>上限/非整数一律拒绝 | ✅ 4 项 |
| **C-7 容器 `MANUAL` ack** | ⚠️ auto-ack 下 nack 无效 → 死信控制完全失效 | ✅ |

### 4.2 行为测试（`OutboxRelayTest`，10 项）

| 用例 | 证明什么 | 结果 |
|---|---|---|
| **R-1a `relayOnce` 确实驱动 `publishOnce`** | ⚠️⚠️ 断链修复的核心（修复前恒为 0）| ✅ |
| R-1b 无待发布 → 不发不报错 | — | ✅ |
| **R-1c 单条坏消息不终止循环** | ⚠️ 好消息仍发出 + 异常不外泄 + 坏消息不误标已发布 | ✅ |
| R-1d 累计发布计数正确 | 供告警与人工介入判断 | ✅ |
| **R-2a 停机后 `relayOnce` 恒返回 0** | ⚠️⚠️ 优雅停机窗口内不产生新工作 | ✅ |
| R-2b start/stop 幂等 | 重复 start 不得并行发布同批消息 | ✅ |
| R-2c 停机协调器触发停止 | Bean 生命周期回调 | ✅ |
| **R-3a 轮询间隔 ≤0 → 构造即拒** | ⚠️ 0 间隔 = 忙等打满 CPU | ✅ |
| **R-4a 含学生数据的载荷在发布前被拒** | ⚠️⚠️ MQ 消息难以精确删除，携带即扩大泄露面 | ✅ |
| R-4b 正常载荷通过（不误伤） | — | ✅ |

### 4.3 反证记录（**7 项全部如期转红** ✅）

| # | 注入的缺陷 | 结果 |
|---|---|---|
| 1 | 中继不再驱动发布（`int n = 0`）| ✅ **3 项红**（R-1a/R-1c/R-1d）|
| 2 | 去掉停机后的 `stopped` 检查 | ✅ **1 项红**（R-2a）|
| 3 | 构造期不校验间隔（0 也放行）| ✅ **1 项红**（R-3a）|
| **4** | **去掉 `OutboxRelay` Bean（还原断链）** | ✅ **12 项红**（上下文起不来）|
| **5** | **去掉 `RabbitExecutionListener` Bean** | ✅ **1 项红**（C-3）|
| 6 | 队列去掉 `.quorum()` | ✅ **1 项红**（C-5a）|
| 7 | `delivery-limit` 放宽到 20 | ✅ **1 项红**（C-5b）|

> ⚠️ **反证 4 是本片最有价值的一条**：它模拟的正是「D01 修复前的状态」，
> 证明 `WorkerLoopWiringTest` **真的能捕获这一类断链**——
> 而 D01 交付时所有测试都是绿的。

---

## 5. 测试证据（实测）

| 项 | 结果 | 说明 |
|---|---|---|
| `WorkerLoopWiringTest` | ✅ **12/12** | 4 个 `@Nested` 组 |
| `OutboxRelayTest` | ✅ **10/10** | 4 个 `@Nested` 组 |
| `PlatformSecurityE2ETest` | ✅ **15/15 未破坏** | 关掉 worker 容器后仍通过 |
| `RunExecutionLoopTest` | ✅ **62 项未破坏** | — |
| infra `*IT` 全量 | ✅ **104 项未受影响** | 无 DB 变更 |
| `mvn clean verify` | ✅ **BUILD SUCCESS**（8 模块）| — |
| `@Test` 注解总数 | ✅ **469 → 494** | 新增 25（22 + D01 已计）|

---

## 6. 实现过程中的两个真实缺陷（均已修）

### 6.1 ⚠️「关掉 worker」曾只是假象（比断链更隐蔽的一档）

首次接线后 E2E 全红，报 `AuthenticationFailureException: ACCESS_REFUSED`。
根因链条（三层，每层都「看起来已经关掉了」）：

1. `WorkerLoopConfig` 加了 `@ConditionalOnProperty(agent.worker.enabled)` ——
   关掉后配置类不加载；
2. **但 `@RabbitListener` 端点由 `RabbitBootstrap` 注册，不受本项目
   `@ConditionalOnProperty` 影响** → 容器照常启动并连 broker；
3. 加了 `spring.rabbitmq.listener.simple.auto-startup=false` ——
   **但本类自建了 `SimpleRabbitListenerContainerFactory`**，
   Boot 的 `SimpleRabbitListenerContainerFactoryConfigurer`
   **不会**应用到用户自定义工厂 → 属性静默失效。

> ⚠️ **教训**：「关掉某个组件」在三处都要生效才算真的关掉。
> 更普适的一条：**自建同类型 Bean 会顶替自动配置**，此时
> 自动配置类做的所有配置（`@Value` 注入的默认值、校验器）**全部失效**，
> 且**不报错**。
> 修法：`factory.setAutoStartup(autoStartup)` 显式读取属性，
> 并在 `application.yml` 与测试属性中**两处同步**（已写注释说明）。

### 6.2 ⚠️ 外层类的 `@Test` 方法不执行（`Tests run: 0`）

`WorkerLoopWiringTest` 最初有 5 个外层 `@Test` + 2 个 `@Nested`，
运行结果是 **12 项全绿但外层 5 项从未执行**（surefire 报告 `Tests run: 0`）。

> ⚠️ **「写了但没跑」是最坏的形态**——看起来有覆盖，实际没有。
> 与 D01 的断链同类：**存在 ≠ 生效**。
> 修法：全部归入 `@Nested` 组；并在测试类注释中写明原因，
> 避免后来者再把方法提到外层而不知其不会执行。

---

## 7. ⚠️ 诚实边界（本片**未**完成的事）

| # | 限制 | 为何不能在本片解决 | 归属 |
|---|---|---|---|
| **L-1** | ⚠️ **端到端未在真实 broker 上验证** | 本机 RabbitMQ 的凭据与配置默认值不一致（`ACCESS_REFUSED`）；读取凭据需用户授权 | P4-D04 |
| **L-2** | ⚠️ `ModelGateway` 仍是 `FakeModelGateway` 占位 | D-002 未提供真实模型服务 | D-002 依赖 |
| **L-3** | `RunExecutionInputs` 返回占位配置（未读 `run.input_payload` 与 Agent 配置表）| 需配置表读取端口 | P4 后续 |
| **L-4** | 单租户设计：一个实例一个 `tenant-slug` | 全租户轮询需跨租户读权限，一旦配置错误即**跨租户发布消息** | P4 多租户 |
| **L-5** | 无跨实例协调（每实例各自中继，靠 `SKIP LOCKED` 不冲突）| 多实例限流/优先级调度 | P4 多实例 |
| **L-6** | Worker 无背压（并发度只限容器线程数，无队列深度水位线）| — | P4 容量 |

> ⚠️ **L-1 是最重要的诚实声明**：本片证明的是「**装配完整、配置正确、
> 断链已修复**」，**不是**「消息已成功送达 Worker」。
> 把前者记为后者，就是本片要根治的那类错误。
> 真实端到端验证需在 P4-D04 用可用的 broker 完成。

---

## 8. 与 D01 的关系（断链的两层）

| 层 | D01 修复 | 本片（D03）修复 |
|---|---|---|
| **第一层** | `RunDispatchConsumer` 领到租约后不执行 → 真正调用 `RunExecutionLoop` | — |
| **第二层** | — | ⚠️ **无人调用 `RunDispatchConsumer`** → 补 OutboxRelay + MQ 消费容器 |
| **测试类别** | 新增 `WorkerChainWiringTest`（连通性）| 新增 `WorkerLoopWiringTest`（连通性）|

> ⚠️ **两层都是同一形态**：机制存在、测试全绿、但**不在链路上**。
> 共同教训：<b>行为测试不足以证明系统工作，必须有连通性测试。</b>
