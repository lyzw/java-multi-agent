# P3-D10 SSE 实时推送 + 可观测性指标（FR-014 部分 / FR-017）

> **阶段**：P3 可靠性与安全
> **切片**：D10（依赖 D1 故障注入框架、D8 限流）
> **验收**：FR-017 ——「关键 Run 可按 ID 追踪；**指标不含用户/Run 等高基数标签**；
> 日志不含密钥」（AT-OBS-001/002）
> **延后项来源**：P2-D05 §6 L1（SSE 实时推送）、P2-01 §3.2（可观测性指标）
> **状态**：✅ 完成（2026-10-19 · 17 项用例全绿 + 2 项反证验证）

---

## 1. 现状：SSE 是「短连接 + 客户端轮询」，指标为零

| 资产 | 状态 |
|---|---|
| `GET /api/runs/{runId}/events` | ✅ 存在，但**返回完整响应即关闭**（P2-D05 §3.3）|
| `Last-Event-ID` 续传 | ✅ 游标续传正确 |
| 反向代理缓冲处理（`X-Accel-Buffering: no`）| ✅ |
| **长连接推送** | ❌ 缺失——延迟完全等于**客户端**轮询间隔 |
| **每租户 SSE 连接数上限** | ❌ 缺失（P2-D05 §6 L7）|
| **任何指标** | ❌ **完全缺失**——无法回答「今天失败了多少 Run」|

**FR-017 原文的两个方向**：

- ✅「关键 Run **可按 ID 追踪**」——需要结构化日志带 `correlationId`/`runId`；
- ⚠️「指标**不含用户/Run 等高基数标签**」——这是一条**约束**，
  而 FR-017 的验收矩阵证据类型写的是「**扫描报告**」，
  说明它预期的是「**能被扫描证明**」，而不是口头承诺。

> ⚠️ 高基数标签是监控系统最经典的自杀方式：
> `run_id` 作为标签 → 每个 Run 一个时间序列 → 时序库被打爆，
> 且**没有任何业务价值**（Run 级明细应查日志，不是查指标）。

---

## 2. 设计

### 2.1 D-1 指标标签用**代码级白名单 + 运行期拒绝**，而非文档约定

「指标不含高基数标签」若只写在文档里，第一次有人加了个 `runId` 标签就破功。

**本片在代码里强制**：

```java
// 低基数维度（唯一允许作为标签的）
tenant_tier, run_state, tool_name, risk_level, outcome,
error_kind, provider, model_id, reason

// ⚠️ 高基数（注册时直接抛异常）
run_id, user_id, student_id, operation_id, correlation_id,
idempotency_key, session_id, email
```

`AgentMetrics.increment(name, labels)` 遇到白名单外的键 **抛异常**——
让「加错标签」在**第一次调用时就崩**，而不是上线后由时序库崩溃来告知。

> ⚠️ 这是本片的核心论点：**约束要落在类型/运行时，不能落在文档与评审**。

### 2.2 D-2 指标与日志分工：明细走日志，聚合走指标

| 需要回答的问题 | 去哪查 |
|---|---|
| 「这个 `runId` 走到哪一步了？」 | **日志**（带 `runId`/`correlationId` 的结构化行）|
| 「今天 SUCCEEDED / FAILED 各多少？」 | **指标**（`run_completed_total{run_state}`）|
| 「哪个工具失败率最高？」 | **指标**（`tool_invoked_total{tool_name,outcome}`）|

> ⚠️ 二者不可混用。把 `runId` 塞进指标是**用指标系统干日志系统的活**，
> 结果是两边都难用。

### 2.3 D-3 SSE 长连接：**服务端轮询 + 事件驱动结束**

| 方案 | 优点 | 缺点 | 取舍 |
|---|---|---|---|
| **服务端轮询（采用）** | 无新依赖；连接数可控；断线自动清理 | 仍有 DB 查询（但间隔短、单次轻量）| ✅ P3 采用 |
| PG `LISTEN/NOTIFY` | 真推送，零轮询 | 每订阅者需**独占连接**；连接池被订阅吃掉；断线重订阅逻辑复杂 | P4 |
| 消息中间件广播 | 真推送 | 引入新拓扑（Redis/Kafka），违反 DEC-12 单 MQ 栈 | 拒绝 |

**关键设计**：

- 响应改为 `SseEmitter`（长连接），**服务端**以固定间隔查 `run_event`；
- **终态 Run 立即发送完成事件并 `complete()`**——连接不会悬挂；
- **超时兜底**：`emitter` 有最大存活时长，超时自动清理（防连接泄漏）；
- 客户端断线时 `onCompletion`/`onTimeout` 清理订阅登记。

> ⚠️ 诚实声明：这**不是**零轮询，而是把轮询从「客户端」搬到「服务端」——
> 延迟由**客户端行为**决定变为由**服务端配置**决定（可预期），
> 且省掉了客户端反复重建连接的开销。真正的零轮询归 P4（`LISTEN/NOTIFY`）。

### 2.4 D-4 SSE 建连必须过 D8 的限流（P2-D05 L7 收口）

长连接 = 长期占用服务端资源，比短请求更危险。故
`RunEventController` 建连前调 `rateLimiter.check(tenant, SSE_CONNECT)`，
超限返回 429。

> ⚠️ **与 D8 的联动**：D8 定义了 `RateLimitPort.Buckets.SSE_CONNECT`
> 但当时无调用方——本片补上。**端口先于调用方存在不算完成**，
> 与 D5「approval 表建好零实现」是同一类问题的反面教训。

---

## 3. 端口与实现

### 3.1 `AgentMetrics`（application 层，新增，**零外部依赖**）

| 操作 | 语义 |
|---|---|
| `increment(name, labels)` | 计数 +1；标签白名单外**抛异常** |
| `snapshot()` | 读取全部指标（测试与运维端点）|
| `reset()` | 仅测试用 |

> ⚠️ **刻意不引 Micrometer**：引入后标签约束会退化为「文档约定」，
> 而 FR-017 的证据类型是「扫描报告」——本片要的是**可扫描的强制点**。
> P4 若接入 Micrometer，可把本类作为其数据源。

### 3.2 接入点

| 指标 | 标签 | 位置 |
|---|---|---|
| `run_completed_total` | `run_state` | `RunExecutionLoop` 提交后 |
| `tool_invoked_total` | `tool_name`, `outcome` | `ToolInvocationGate` |
| `budget_rejected_total` | `reason` | 预算预留拒绝分支 |
| `approval_rejected_total` | `outcome` | 审批消费拒绝分支 |
| `rate_limited_total` | `bucket` | `RunController` 429 分支 |
| `prompt_rejected_total` | `reason` | `RunController` 400 分支 |
| `sse_connections_active` | （无标签）| SSE 建连/断开 |

### 3.3 SSE 长连接

`RunEventController.streamEvents` 返回 `SseEmitter`，
并在 D8 的 `SSE_CONNECT` 限流 + 授权 + 404 判定之后进入推送循环。

---

## 4. 已知限制

| # | 限制 | 归属 |
|---|---|---|
| L-1 | SSE 长连接是**服务端轮询**，非真推送；DB 仍有查询 | P4（`LISTEN/NOTIFY`）|
| L-2 | 单实例内存态订阅登记；多实例部署时 A 实例的推送不覆盖 B 实例订阅者 | P4 |
| L-3 | 指标无持久化与聚合后端（仅内存快照）| P4（Prometheus/时序库）|
| L-4 | 结构化日志带 `runId` 属**高基数字段**；本片只保证不进入**指标标签** | 运维日志平台负责 |

---

## 5. 测试与反证结果

### 5.1 指标标签约束（`AgentMetricsTest`，9 项）

| 用例 | 证明什么 | 结果 |
|---|---|---|
| M-1 低基数标签记录与聚合 | 基本功能 | ✅ |
| M-1b 标签顺序不同 → 同一序列 | 不因书写顺序分裂序列 | ✅ |
| **M-2 `run_id` 标签 → 抛异常** | ⚠️⚠️ 高基数让时序库被打爆 | ✅ |
| **M-3 `user_id`/`student_id`/`correlation_id`/`operation_id`/… 全部被拒** | D-1 强制 | ✅ |
| M-3b 大小写变体（`runId`/`RUN_ID`）同样被拒 | 绕过不能靠改大小写 | ✅ |
| M-4 白名单内维度不被误伤 | 不误杀 | ✅ |
| M-5 snapshot 反映真实计数 | 可观测 | ✅ |
| **M-6 结构扫描：高基数字段绝不出现在任何 `increment(` 调用的标签中** | ⚠️ **AT-OBS-002「扫描报告」证据** | ✅ |
| M-6b 禁用清单覆盖全部已知高基数字段 | 扫描用例自身有效 | ✅ |

> ⚠️ M-6 是本片的核心证据形式：FR-017 的验收矩阵把证据类型写为「**扫描报告**」，
> 所以除了行为测试（M-2/M-3），还有一个**结构扫描**用例——
> 即使白名单被人为放宽，M-6 仍会红。

### 5.2 SSE 长连接（`SseStreamingTest`，8 项）

| 用例 | 证明什么 | 结果 |
|---|---|---|
| S-1/S-2 终态 Run → 发完成事件并 `complete()` | ⚠️ 连接不得悬挂 | ✅ |
| S-3 非终态 Run → **不**发完成事件 | 不误关 | ✅ |
| S-4 超限 → 429，不返回流 | D-4 收口 P2-D05 §6 L7 | ✅ |
| S-4b 限流 `FAIL_CLOSED` → 同样 429 | 禁止降级放行 | ✅ |
| S-5 授权失败 → 拒绝（**不建连**）| 长连接不是绕过授权的后门 | ✅ |
| S-5b Run 不存在 → 404 | 不泄露存在性 | ✅ |
| S-6 `studentName`/`idCard` 不在白名单 | P2-D05 白名单语义未回退 | ✅ |
| S-6b 白名单 fail-closed（新字段默认丢弃）| 与黑名单相反 | ✅ |

### 5.3 反证记录（2 项，均如期转红 ✅）

| # | 注入的缺陷 | 结果 |
|---|---|---|
| 1 | `requireSafeLabels` 的白名单判断改为恒真（模拟「有人把 `run_id` 加进白名单」）| ✅ **3 项转红**（M-2 / M-3 / M-3b）|
| 2 | 终态不再 `sender.complete()`（连接悬挂）| ✅ **1 项转红**（`terminalRunCompletesConnection`）|

> ⚠️ 反证 1 的价值：它同时证明了
> **「白名单被放宽时行为测试会红」**与**「结构扫描是独立防线」**——
> 若只有 M-2/M-3，攻击者改一行白名单即可绕过；M-6 独立于白名单。

---

## 6. 测试证据（实测）

| 项 | 结果 | 说明 |
|---|---|---|
| `AgentMetricsTest` | ✅ **9/9 全绿** | 含 M-6 结构扫描 |
| `SseStreamingTest` | ✅ **8/8 全绿** | 4 个 `@Nested` 组 |
| `RunEventControllerTest` | ✅ **17 项未破坏** | 3 参构造器保留 |
| `PlatformSecurityE2ETest` | ✅ **15/15 未破坏** | — |
| infra `*IT` 全量 | ✅ **96 项未受影响** | 本片无 DB 变更 |
| `mvn clean verify` | ✅ **BUILD SUCCESS**（8 模块）| — |
| `@Test` 注解总数 | ✅ **410 → 427** | 新增 17 |

### 6.1 F-6 的第三次发生

`RunEventController` 新增 `RunEventStreamer` 构造参数后有**两个**构造器，
组件扫描无法选择 → `No default constructor found` → **E2E 15 项全红**。
用 `@Autowired` 标记解决。

> ⚠️ 三次分别是 D7 `JdbcRunClaimRepository`、D8 `RunController`、
> D10 `RunEventController`——**每次都是「加一个构造参数」触发**。
> MEMORY 已记录通用规则，本次为第三次验证该规则有效。

### 6.2 为可测性做的设计调整：`FrameSender` 抽象

初版 `RunEventStreamer` 直接依赖 `SseEmitter`，导致终态关闭的用例
**必然超时失败**——`SseEmitter.send()` 在**异步未初始化**时把帧排入缓冲区，
`complete()` 也不触发回调，一切信号被吞掉（**假阴性**）。

抽出 `FrameSender` 接口（生产实现 `sseSender(emitter)`，测试用记录型实现），
用例才真正断言到「终态 → 发完成标记 → 完成连接」。

> ⚠️ **可测性缺陷与功能缺陷同样重要**：一个无法被断言的类，
> 其正确性就等于未验证。**异步 API 必须提供同步可观测的抽象**。

### 6.3 FR-017 两项要求对照

| FR-017 要求 | 落地 | 证据 |
|---|---|---|
| 「指标**不含**用户/Run 等高基数标签」| 运行期白名单强制 + `FORBIDDEN_LABEL_KEYS` 显式禁用清单 + **结构扫描** | M-2/M-3/M-6 |
| 「关键 Run 可按 ID 追踪」| 日志已带 `runId`/`correlationId`（P1 起）；**明细走日志、聚合走指标**（D-2）| 设计约束；P4 补日志平台对接 |
| 「日志不含密钥」| P1 的 `CredentialRedactor` 已达成 | `ModelGatewayContractTest` 7 项 |

### 6.4 P2 延后项收口情况

| 延后项（P2-01 §3.2）| 状态 |
|---|---|
| SSE 实时推送 | ⚠️ **部分完成**——服务端轮询的长连接已落地；真推送（`LISTEN/NOTIFY`）归 P4（§4 L-1）|
| 可观测性指标 | ⚠️ **部分完成**——指标注册与标签约束已落地；无持久化/聚合后端（§4 L-3）|

> ⚠️ 两项均为**部分完成**，不记为完全闭环。P3 退出时须在「Accepted with conditions」
> 中明确列出这两条。
