# P2-D05 SSE 事件流与事务边界收口

> **状态**：🔄 编码中
> **前置**：D1 ✅ / D2 ✅ / D3 ✅ / D4 ✅ / D-001 ✅
> **验收**：FR-014（事件流）、红线 3（原子提交端到端可证）

---

## 0. ⚠️ 本文档首先记录一个已存在的设计冲突

D5 动笔前实测发现：**`TransactionRunner` 的事务语义目前是假的**。

`ApplicationConfiguration.transactionRunner()` 是一个**直接抛异常**的占位实现：

```java
throw new IllegalStateException("TransactionRunner 生产实现待接入（P2-D5）。");
```

这意味着 `/api/runs` POST 端点**必然 500**，端到端链路从未跑通。

但更值得注意的是：即使把它替换成一个「看起来正确」的实现，**原子性仍然是假的**。
`JdbcRunCreationRepository` 的每个方法都**自己开连接、自己 commit**：

| 行号 | 方法 | 事务行为 |
|---|---|---|
| 60–72 | `findExistingResource` | 自开连接 → `rollback()` |
| 85–121 | `insertRunAtomically` | 自开连接 → **`commit()`**（第 113 行）|
| 218–236 | `findRun` | 自开连接 → `rollback()` |
| 254–268 | `loadPendingOutbox` | 自开连接 → `rollback()` |
| 283–289 | `markOutboxPublished` | 自开连接 → `commit()` |
| 303–309 | `markOutboxFailed` | 自开连接 → `commit()` |

`RunCreationService` 在两个**独立**的 `tx.execute(...)` 中调用
「查幂等记录」与「原子写入」。若 TransactionRunner 只是简单地 `action.run()`，
外层事务对 Repository **完全无效**——两次调用各自独立提交。

### 0.1 结论：这不是「补一个实现」，而是「补一个事务模型」

本项目的 RLS 铁律要求：**任何新写 DAO 方法必须经统一入口取连接**
（`tenantConnection()`：关自动提交 + 绑定租户 + commit/rollback）。
但该入口是**方法内部私有**的，外部无法把连接传进去。

因此 D5 必须先解决：**如何让 Repository 复用外层事务的连接**。

---

## 1. 职责边界

### 1.1 做什么

| 项 | 内容 |
|---|---|
| **事务边界收口** | 提供生产版 `TransactionRunner`，使 Run 创建的「查幂等 + 原子写入」处于**真实可控**的事务语义下 |
| **Run 事件查询** | 新增 `RunEventQueryPort` + JDBC 实现，按 `sequence_no` 读取事件 |
| **SSE 端点** | `GET /api/runs/{runId}/events`，支持 `Last-Event-ID` 续传 |
| **越权拒绝** | 跨租户访问 Run 事件必须拒绝 |
| **端到端验证** | 创建 Run → Outbox → 消费 → 模型 → 工具 → 提交 的可复跑证据 |

### 1.2 ❌ 不做什么（防越权边界）

| 不做 | 理由 |
|---|---|
| **不做「Run 状态查询端点」** | 本次只补事件流。状态端点会引入新的授权面，不在 D5 范围 |
| **不做 WebSocket / 长轮询** | FR-014 明确要求 SSE。引入第二种推送机制即两套语义 |
| **不做事件推送（内存总线）** | 多实例部署时内存事件不跨实例，订阅方会漏事件。**只读数据库**是唯一正确语义 |
| **不在 SSE 中回传 Prompt 原文** | `run_event.payload` 设计上已最小化，SSE 层不得「帮忙」扩展 |
| **不改 `run_event` 表结构** | 表已具备 `sequence_no` 与唯一约束，无需 DDL 变更 |
| **不实现「订阅期间实时推送」** | 降级为**轮询式读取**（见 §3.3），理由见该节 |

---

## 2. 决策与理由

### D-1：事务连接通过 ThreadLocal 传递，而非改造全部 Repository 方法签名

**候选方案**：

| 方案 | 改动量 | 风险 |
|---|---|---|
| A. 给 `RunCreationPort` 所有方法加 `Connection` 参数 | 6 个方法 + 全部调用方 + 全部测试 | 高。测试改动面极大，且破坏端口的领域语义（持久化细节泄漏进端口）|
| B. Repository 内部 `ThreadLocal<Connection>` 复用 | 新增 1 个类，改 `tenantConnection()` 一处 | 中。需保证嵌套与线程池场景正确 |
| C. 保持现状，不收口事务 | 0 | **高。红线 3 的「四者同事务」在应用层不可证** |

**选择 B**。

理由：红线 3 要求「四者同事务」**可被验证**。当前实现虽在
`insertRunAtomically` **单方法内**用单连接单事务保证了四者原子，
但这一点**无法从应用层证明**——它依赖「恰好只有一个方法写这四张表」这一
脆弱假设。日后任何人给 `insertRunAtomically` 加一句额外 SQL 并自行 commit，
原子性即静默失效。

**B 的代价**（必须写进已知限制）：`ThreadLocal` 在虚拟线程下
若忘记清理会泄漏。实现必须用 `try/finally` 无条件清理。

### D-2：`tx.execute` 采用「外层有连接则复用，否则自开」

```java
public <T> T execute(String tenantId, TransactionalAction<T> action) {
    // 情形一：外层已有事务（嵌套调用）→ 直接复用，不开新事务
    // 情形二：顶层调用 → 开连接 + 绑定租户 + 执行 + commit/rollback
}
```

⚠️ **嵌套不自动开新事务**（无 REQUIRED/PROPAGATION 语义）。
理由：本项目事务边界极浅（最深两层），引入传播语义会让
「哪些语句在事务内」变得不可静态判定——这正是 RLS 铁律最怕的模糊地带。

### D-3：跨租户访问返回 **404 而非 403**

SSE 端点对「Run 不存在」与「Run 属于他人」**返回完全相同的 404**。

理由：若返回 403，攻击者可用一个随机 runId 逐个试探，
通过状态码差异**枚举出哪些 runId 真实存在**（租户存在性探测通道）。
D-001 已在 `PrincipalRepository` 因同类问题修过一次（非法 tenantId 返回 500
而非 401，构成探测通道），此处是同一类问题的第二次出现。

### D-4：SSE 采用「短连接 + 轮询读取」，不做长连接推送

见 §3.3。

---

## 3. 执行链

### 3.1 Run 创建（事务收口后）

```
POST /api/runs
  → RunController.createRun
    → ① CurrentPrincipal.context()        唯一可信租户来源
    → ② assertNoIdentifyingContent()     内容合规
    → ③ zeroRetentionGate.assertAllowed  GATE-1
    → ④ requireAuthorized                 红线 9 执行时授权
    → ⑤ RunCreationService.createRun
         → tx.execute #1（查幂等）        ┐
         → tx.execute #2（四者同事务）    ┘ 各自独立事务
              └ JdbcRunCreationRepository 复用外层连接 ← 本次改动
    → 201 Created / 200 OK（重放）
```

⚠️ **为何 #1 与 #2 仍是两个事务**：二者之间没有原子性要求——
幂等由 `idempotency_record` 的唯一约束兜底，重复插入会失败而非静默双写。
把它们合并为一个长事务会**延长锁持有时间**且无收益。
这是有意的取舍，不是疏漏。

### 3.2 SSE 事件读取

```
GET /api/runs/{runId}/events
     [Last-Event-ID: <seq>]
  → RunController.streamEvents
    → ① CurrentPrincipal.context()      取可信租户
    → ② requireAuthorized(RUN_READ)     授权闸门（红线 9）
    → ③ RunEventQuery.findAfter(tenant, runId, lastSeq)
         └ JDBC：WHERE tenant_id=? AND run_id=? AND sequence_no > ?
                 ORDER BY sequence_no ASC
                 LIMIT n
       └ ⚠️ 强制 tenant_id 条件，即便 RLS 已隔离（纵深防御）
    → ④ 序列化为 SSE 帧（id / event / data）
    → ⑤ Run 已达终态 → 发送后关闭流
```

⚠️ **为何 SQL 仍显式带 `tenant_id`**：RLS 是数据库侧的兜底，
应用侧条件是**第一道防线**。若某天 RLS 策略被误删，
显式条件仍能挡住跨租户读取。两层独立机制比单层更可靠。

### 3.3 ⚠️ 为何不做「订阅期间实时推送」

理想实现是：SSE 连接建立后，由 Worker 写事件时**主动推送**。
本项目**不做**，改用「客户端带 `Last-Event-ID` 重新请求」的轮询式读取。

理由：

| 因素 | 说明 |
|---|---|
| **多实例正确性** | 内存推送（`ApplicationEventPublisher`）在多实例下**不跨实例**。Worker 在实例 A 写事件，订阅方连在实例 B，事件永远收不到。这是**静默失败**——不报错，只是丢事件 |
| **无推送基础设施** | 引入 PG `LISTEN/NOTIFY` 或 Redis Pub/Sub 都会**新增基础设施依赖**，而 Redis 尚未纳入技术基线（MQ 已定为 RabbitMQ 单一栈） |
| **一致性天然正确** | 直接读库意味着**所见即所存**，不存在「推送丢了但库里还在」的补偿问题 |
| **范围约束** | P2-01 §3.2 已把「SSE 续传（完整实现）」列为**可延至 P3**，先做最简订阅 |

**代价（必须写进已知限制）**：事件延迟等于轮询间隔，不是实时。
对于「错题归因分析」这类秒级可接受、分钟级不可接受的场景，
P3 需补 `LISTEN/NOTIFY` 或延长连接轮询。

### 3.4 SSE 帧格式

```
id: 3
event: RUN_STATE_CHANGED
data: {"sequenceNo":3,"state":"RUNNING","at":"2026-10-19T14:50:00Z"}

```

`id` 即 `sequence_no`，客户端断线重连时原样回传为 `Last-Event-ID` 即可续传。
⚠️ `data` 只含状态与序号，**不含 Prompt、不含学生数据、不含工具参数**。

---

## 4. 失败与边界

| 情形 | 处置 | 理由 |
|---|---|---|
| 无令牌 / 令牌无效 | 401（由 Security 过滤链统一处理） | D-001 已实现在册 |
| 角色无 `RUN_READ` 权限 | 403 | 授权失败须可区分，否则无法排查 |
| Run 不存在 | 404 | — |
| **Run 属于他人租户** | **404（不是 403）** | D-3：防租户存在性探测 |
| `Last-Event-ID` 格式非法 | 400 | 非法游标不得静默当作 0，否则客户端会收到全量历史 |
| `Last-Event-ID` 超出当前最大序号 | 视为「无新事件」，正常结束流 | 客户端可能领先于库（如读到过已回滚的数据）|
| Run 尚未产生任何事件 | 200 + 空流，立即关闭 | 无事件不是错误 |
| Run 处于终态且事件已读完 | 发送后关闭流 | 避免连接泄漏 |

⚠️ **终态定义**：`SUCCEEDED` / `FAILED` / `CANCELLED` / **`TIMED_OUT`**
（取自 `RunState` 的 `legalTargets()` 为空集者，**不以文档描述为准而以代码为准**）。
初稿曾漏 `TIMED_OUT`，已修正 —— 超时是真实存在的终态，漏判会导致连接永不关闭。

---

## 5. 测试覆盖

> 原则（沿用 R-016 教训）：断言针对「**不可绕过的性质**」，
> 且必须**主动破坏防护**验证测试确实会失败。

### 5.1 事务收口

| # | 测试 | 断言的性质 |
|---|---|---|
| T1 | `tx.execute` 内抛异常 → 外层数据**零残留** | 事务真实生效 |
| T2 | 嵌套 `tx.execute` 内外层失败 → 整体回滚 | 复用同一连接，非两个独立事务 |
| T3 | `ThreadLocal` 在异常路径被清理 | 无连接泄漏 |
| T4 | Repository 在外层事务内执行 → **实际提交者是外层** | 证明连接真被复用 |

### 5.2 SSE 事件流

| # | 测试 | 断言的性质 |
|---|---|---|
| S1 | 按 `sequence_no` 升序返回 | 续传语义基础 |
| S2 | `Last-Event-ID=3` → 只返回 `> 3` 的事件 | 续传不重复、不丢 |
| S3 | **租户 A 订阅租户 B 的 runId → 404** | 跨租户拒绝 |
| S4 | 非法 `Last-Event-ID` → 400 | 不静默当作 0 |
| S5 | 终态 Run → 流关闭 | 不泄漏连接 |
| S6 | 事件 `data` 不含 Prompt / 学生数据 | 载荷最小化 |
| S7 | **移除 `WHERE tenant_id` 条件 → 测试精确失败** | 反证：证明测试可被证伪 |

### 5.3 端到端

创建 Run → 断言 Outbox 存在 → 消费者领取 → 执行循环产出事件 →
SSE 读取到完整序列。**这是 P2 DoD 的收官证据**。

---

## 6. 已知限制

> 本节最重要。**不写清楚，后人会误以为闸门已完备。**

| # | 限制 | 影响 | 何时解决 |
|---|---|---|---|
| L1 | **SSE 非实时**，延迟等于轮询间隔 | 秒级场景体验差 | P3 补 `LISTEN/NOTIFY` |
| L2 | 无**游标过期协议** | 长时间断线的客户端可能请求到已清理的历史 | P3 |
| L3 | `RoleBasedAuthorizationService` 仍是**内存实现** | 多实例部署 `authzVersion` 不共享，实例 A 撤销的授权实例 B 不知情 | D-001 后续 / P3 |
| L4 | `CurrentPrincipal.providerId()` **硬编码 DEEPSEEK** | 多供应商路由未实现 | P3 |
| L5 | `snapshotId()` 每次**随机生成** | 同一 Run 重试时快照不一致（当前 D1 路径每次新建 Run，暂不触发） | P3 |
| L6 | 端到端测试**依赖真实 PostgreSQL** | 无 `IT_PG_URL` 时跳过（CI 双模式） | 已在 D-001 建立该模式 |
| L7 | SSE 未做**每租户连接数限流** | 单租户可耗尽连接池 | P3 可观测性 |

---

## 7. 与 P2-01 取舍清单的对应

| P2-01 §3.2 延后项 | 本次处置 |
|---|---|
| SSE 续传（完整实现）→ FR-014 | **部分交付**：游标续传已实现；长连接推送延至 P3（L1） |
| 可观测性指标 FR-017 | 仍延后（P3）|
| 业务验收 SB-01～07 FR-018 | 仍延后（P3，需评测集）|
| 输出无编造出处评测 FR-020 | 仍延后（P3）|

⚠️ 因此 **P2 退出仍记为 `Accepted with conditions`**，
条件为上述四项在 P3 补齐——与 P2-01 §7 的预判一致。

---

**下一步**：实现 §2 D-1 的事务连接传递 → §5.1 测试 → §3.2 SSE 端点 → §5.3 端到端。