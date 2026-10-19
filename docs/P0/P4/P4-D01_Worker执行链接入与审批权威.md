# P4-D01 Worker 执行链接入 + 审批权威（P3 退出条件 C-3）

> **阶段**：P4 企业集成与生产化
> **切片**：D01（关闭 P3 退出条件 C-3）
> **验收**：FR-013 审批消费在**生产装配下**真实生效；E-2 审批权威可接入
> **状态**：✅ 完成（2026-10-19 · 17 项用例全绿 + 3 项反证验证）

---

## 1. ⚠️ 现状盘点：比「审批权威未接入」更严重的缺口

P3-D05 记录的 L-1 是「`ApprovalPolicy` 生产装配缺失」。本轮核实后发现
**问题比这严重一级**——**整条 Worker 执行链从未装配**。

### 1.1 断链点

```java
// RunDispatchConsumer.consume（现状）
RunClaimPort.LeasedTask task = claimed.get();
log.debug("已领取任务: runId={}, fencingToken={}", task.runId(), task.fencingToken());
return ConsumeResult.CLAIMED;          // ⚠️ task 拿到手就被丢弃
```

| 事实 | 实测结果 |
|---|---|
| `ToolInvocationGate` Bean | ❌ **不存在** |
| `RunExecutionLoop` Bean | ❌ **不存在** |
| `ApprovalPolicy` Bean | ❌ **不存在** |
| 调用 `RunExecutionLoop.execute` 的代码 | ❌ **零处** |

### 1.2 后果：D5–D11 的交付在生产中**一次都不会执行**

| 已交付的安全机制 | 生产中是否生效 |
|---|---|
| D5 审批消费绑定/防篡改/崩溃恢复 | ❌ 不生效（闸门未装配）|
| D6 取消竞态双检查 | ❌ 不生效 |
| D7 审计链路（R-1/R-2/R-3）| ❌ 不生效（审批/取消不发生，无审计可写）|
| D8 限流 | ⚠️ 仅 API 层生效（Worker 侧未装配）|
| D9 Prompt 注入防护 | ❌ 不生效（`renderInput` 属执行循环）|
| D11 编造出处拦截 | ❌ 不生效（拦截点在 `commitSuccess` 前）|

> ⚠️ **这是「F-6 安全假象」的第七次重演**，但方向相反：
> 前六次是「安全机制形同虚设」，这次是**「安全机制根本不在链路上」**。
> 更危险的是：所有集成测试都**直接 `new` 这些类**，
> 所以**每一项都测得通过**——测试从未验证「它们是否真的被调用」。

### 1.3 触发条件

`OutboxMessage` → MQ → `RunDispatchConsumer.consume` → **返回 CLAIMED 后无下文**。

---

## 2. 设计

### 2.1 D-1 `RunDispatchConsumer` 必须真正执行，而不是只领租约

```java
// 修复后
LeasedTask task = claimed.get();
ExecutionOutcome outcome = executor.execute(message.tenantId(), task, ...);
return mapOutcome(outcome);   // 按结局决定重试/丢弃
```

**关键：按 `ExecutionOutcome` 决定重试策略**——这正是 P2-D03 设计的三种结局：

| Outcome | MQ 处置 | 理由 |
|---|---|---|
| `SUCCEEDED` / `FAILED` / `CANCELLED` | ack，丢弃 | 已终结，重投无意义 |
| `RETRYABLE_FAILURE` | **nack 重投** | 可重试（模型瞬时故障）|
| `UNKNOWN_RESULT` | ⚠️ **ack 不重投** | 重投 = 重复副作用 = **Sev-0** |
| `SKIPPED_STALE_GENERATION` | ack 丢弃 | 已被接管 |

> ⚠️ **`UNKNOWN_RESULT` 的 ack 是全项目最危险的一行**——
> 任何「顺手改成 nack」的改动都会让 `record_weakness` 重复写入。

### 2.2 D-2 审批策略按**风险等级**决定，而非硬编码

```java
// ProductionApprovalPolicy
WRITE_EXTERNAL / WRITE_PROFILE → REQUIRED   （有副作用）
WRITE_INTERNAL                 → REQUIRED
READ                           → NOT_REQUIRED
```

依据 P0-D00 §3.2：`record_weakness` 是首期**唯一**的有副作用工具。

> ⚠️ **fail-closed 方向**：未登记的工具等级按 `WRITE_EXTERNAL` 处理
> （与 `ConfigFailClosedTest` 既有语义一致），宁可多审批不可漏审批。

### 2.3 D-3 审批权威做成**可插拔端口**，本轮提供两个实现

| 实现 | 用途 | 状态 |
|---|---|---|
| `RiskBasedApprovalPolicy` | 按风险等级判定「是否需要审批」 | ✅ 本轮 |
| `PolicyEngineApprovalSource` | 从配置化策略引擎取审批结论 | ✅ 本轮（本地可跑）|
| `HttpApprovalSource` | 调用外部人类审批台 | ⏳ **需外部系统**（E-2 残余）|

> ⚠️ **诚实边界**：C-3 说的是「审批权威未接入」。
> 本轮关闭的是**链路 + 可插拔契约 + 本地策略引擎**；
> 真正的「人类审批台」是**外部系统**，无法由代码凭空造出。
> 这一残余已如实记入 §4 L-1。

### 2.4 D-4 装配必须经过「上下文组装器」，避免 bootstrap 里堆构造参数

`RunExecutionLoop` 需要 6 个依赖（模型网关 / 领取端口 / 闸门 / 租约 / workerId / PromptGuard）。
在 `ApplicationConfiguration` 里堆 6 个 `@Bean` 参数会让装配层不可读。

**做法**：抽出 `WorkerExecutionConfig`（`@Configuration`）专门组装，
bootstrap 主类只留 `@Import`。

---

## 3. 接入点清单

| 位置 | 变更 |
|---|---|
| `RunDispatchConsumer` | 领取后**真正执行**；按 Outcome 决定 MQ 处置 |
| `RiskBasedApprovalPolicy`（新）| 按 `riskLevel` 决定是否需审批 |
| `PolicyEngineApprovalSource`（新）| 配置化审批结论来源（本地可跑）|
| `WorkerExecutionConfig`（新）| 组装 `ToolInvocationGate` + `RunExecutionLoop` |
| `ApplicationConfiguration` | `@Import(WorkerExecutionConfig)` |

---

## 4. 已知限制

| # | 限制 | 归属 |
|---|---|---|
| L-1 | ⚠️ **外部人类审批台未接入**——本轮提供可插拔契约 + 本地策略引擎；真实审批台需外部系统 | E-2 残余 / P4 后续 |
| L-2 | `PolicyEngineApprovalSource` 的策略来自配置，**无审批 UI 与通知** | P4 后续 |
| L-3 | Worker 主循环（批量轮询 / 优雅停机 / 并发度）未实现，本轮只接通「MQ 消息 → 执行」 | P4-D02 |
| L-4 | 模型网关用 `FakeModelGateway`，真实供应商接入需 D-002 | D-002 依赖 |

---

## 5. 测试与反证结果

### 5.1 链路连通性（`WorkerChainWiringTest`，8 项）

| 用例 | 证明什么 | 结果 |
|---|---|---|
| **W-1 消费 CLAIMED → 执行器被调用** | ⚠️⚠️ **断链修复的核心**（修复前恒为 0）| ✅ |
| W-1b 未装配执行器 → 显式告警不静默 | 不静默只领不跑 | ✅ |
| W-1c 重复投递 → 不执行 | 他人已领走 | ✅ |
| W-2 `SUCCEEDED` → ack 丢弃 | D-1 | ✅ |
| **W-3 ⚠️⚠️⚠️ `UNKNOWN` → ack 丢弃，绝不重投** | **Sev-0 防线** | ✅ |
| W-4 `RETRYABLE_FAILURE` → nack 重投 | D-1 | ✅ |
| W-4b 终态结局（CANCELLED/STALE/FAILED）均丢弃 | 不重投终结任务 | ✅ |
| W-4c 执行器抛异常 → REJECTED 进死信 | 不静默吞掉 | ✅ |

### 5.2 审批策略与权威（`RiskBasedApprovalPolicyTest`，9 项）

| 用例 | 证明什么 | 结果 |
|---|---|---|
| **A-1 `WRITE_EXTERNAL`/`WRITE_PROFILE` → 需审批** | D-2（有副作用）| ✅ |
| A-2 `READ` → 不需审批 | 不误拦只读工具 | ✅ |
| **A-3 风险等级未知（null）→ 需审批** | ⚠️ fail-closed | ✅ |
| A-4 登记 `APPROVED` → 放行 | D-3 | ✅ |
| A-4b 登记 `REJECTED` → 拒绝 | D-3 | ✅ |
| **A-5 租户未登记 → `FAIL_CLOSED`** | ⚠️ 不因「没配置」就默认批准 | ✅ |
| A-5b 工具未登记 → `FAIL_CLOSED` | 同上 | ✅ |
| **A-5c 空策略（默认构造）→ 恒 `FAIL_CLOSED`** | ⚠️ 绝不能默认批准 | ✅ |
| A-6 实现 ports 层契约 | 模块依赖方向正确 | ✅ |

### 5.3 反证记录（3 项，全部如期转红 ✅）

| # | 注入的缺陷 | 结果 |
|---|---|---|
| 1 | **还原断链**（领取后直接 `return CLAIMED`，不执行）| ✅ **3 项转红** |
| 2 | `UNKNOWN_RESULT` 改为映射到 `RETRY` | ✅ **1 项转红**（`unknownMustNotBeRedelivered`）|
| 3 | 审批策略改为「一律 `NOT_REQUIRED`」| ✅ **2 项转红** |

> ⚠️ 反证 1 是本片的核心证据：**只有「执行器确实被调用」的断言，
> 才能证明断链真的修好了**。修复前所有既有测试都通过——
> 因为它们**都直接 `new` 被测类，从不验证「它是否被调用」**。

---

## 6. 测试证据（实测）

| 项 | 结果 | 说明 |
|---|---|---|
| `WorkerChainWiringTest` | ✅ **8/8** | 2 个 `@Nested` |
| `RiskBasedApprovalPolicyTest` | ✅ **9/9** | 2 个 `@Nested` |
| `RunExecutionLoopTest` | ✅ **62 项未破坏**（D5 两项已补 `APPROVED` 来源）| 闸门新增审批来源后需同步 |
| `PlatformSecurityE2ETest` | ✅ **15/15** | 上下文装载新装配后仍通过 |
| infra `*IT` 全量 | ✅ **98 项未受影响** | 无 DB 变更 |
| `mvn clean verify` | ✅ **BUILD SUCCESS**（8 模块）| — |
| `@Test` 注解总数 | ✅ **443 → 460** | 新增 17 |

### 6.1 C-3 状态判定：**部分关闭**

| 原 C-3 表述 | 本片后 |
|---|---|
| 「E-2 审批权威未接入（闸门恒 fail-closed 拒）」 | ⚠️ **已部分关闭**：链路接通 + 权威可插拔契约 + 按风险等级判定 + 本地策略引擎 |
| | ⚠️ **残余**：真实**人类审批台**（HTTP/工作流）需外部系统，本片无法凭空造出 |

> ⚠️ **诚实边界**：本片交付的是「审批权威的**接入契约**」，
> 而不是「审批台本身」。闸门从「恒拒」变为
> 「向权威查询后按结论处置」——**行为在未配置时不变（仍拒绝），
> 但已具备被真实审批驱动的能力**。

### 6.2 一并修复的模块依赖问题

`ApprovalPolicy` 原先定义在 `agent-worker` 的 `ToolInvocationGate` 内，
导致 `RiskBasedApprovalPolicy`（application 层）必须**反向依赖 worker**——
违反模块依赖方向（`application → infrastructure → worker`）。

**修复**：把 `ApprovalPolicy` 前移到 `agent-ports`，两边都依赖端口层。
`RiskBasedApprovalPolicyTest.A-6` 专门断言这一点。

### 6.3 本片暴露的更大问题：F-6「安全假象」的第七次重演

P3 期间反复出现的安全假象是「安全机制形同虚设」，
本片是**更严重的一档**：

| | P3 的 F-5/F-6/F-7 | 本片 |
|---|---|---|
| 现象 | 机制存在但**行为被绕过** | 机制**根本不在链路上** |
| 隐蔽性 | 集成测试能发现 | ⚠️ **所有集成测试都通过**（都直接 `new`）|
| 影响范围 | 单个机制 | **D5–D11 全部 7 个切片** |

> ⚠️ **为什么之前没发现**：P3 的每个切片都验证了
> 「机制在被直接调用时行为正确」，
> 但**没有任何一个测试验证「它是否被调用」**。
> 这类缺口只能靠**读装配代码**发现，不能靠测试发现——
> 因此新增了 `WorkerChainWiringTest` 这一**连通性测试**类别。

---

## 7. P4-D01 遗留

| # | 限制 | 归属 |
|---|---|---|
| L-1 | ⚠️ 外部人类审批台未接入（本片交付契约而非系统本身）| E-2 残余 |
| L-2 | 策略引擎无 UI / 无通知 / 无二次确认 | P4 后续 |
| L-3 | Worker 主循环（批量轮询 / 优雅停机 / 并发度）未实现 | P4-D02 |
| L-4 | ⚠️ `ModelGateway` 用 `FakeModelGateway` 占位（D-002 未提供真实模型服务）；`RunExecutionInputs` 返回占位配置 | D-002 依赖 / P4 后续 |

> ⚠️ L-4 意味着：**链路结构完整，但端到端尚未产生真实业务价值**。
> 不得把本片记为「系统可生产运行」。
