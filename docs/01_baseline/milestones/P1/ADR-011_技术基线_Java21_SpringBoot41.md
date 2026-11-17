# ADR-011 技术基线：Java 21 + Spring Boot 4.1.1

| 字段 | 内容 |
|---|---|
| ADR 编号 | ADR-011 |
| 状态 | 已决策（DEC-4），待 M1 门禁评审签署 |
| 决策人 | AG-ARC 起草 / zhouwei 批准 |
| 决策日期 | 2026-10-17 |
| 关联决策 | DEC-4（技术基线）、DEC-9/DEC-11（配置化） |
| 关联风险 | R-019（4.x 生态未适配）、R-020（配置成后门）、R-021（配置快照） |
| 复审触发条件 | 出现 Boot 4.x 生态不兼容；模型供应商 SDK 不支持 Boot 4；组织运行时策略变更 |

---

## 1. 背景

v3.1 架构设计方案第 83 行的技术选型表原文为：

```
| 框架     | Spring Boot 3.x     | 采用组织已验证的受支持小版本 |
```

项目负责人 DEC-4 决定改用 **Java 21 + Spring Boot 4.x**。本文记录该偏离的
理由、影响面、验证结果与回滚方案。

---

## 2. 决策

**采用 Java 21 + Spring Boot 4.1.1。**

### 2.1 为何锁 4.1.1 而非 4.0.x

| 版本 | GA 日期 | OSS 支持截止 | 结论 |
|---|---|---|---|
| 4.0.x | 2025-11-20 | **2026-12-31** | ❌ 距今仅 2 个月，不可用于 18 周项目 |
| **4.1.1** | 2026-06-10（4.1.0）/ 2026-08-20（4.1.1 修复 98 个缺陷） | **2027-07-31** | ✅ **选用** |
| 4.2.x | 计划 2026-11 | — | 尚未 GA，不可用 |

若选 4.0.x，项目将在 W14（2027-02）前后失去 OSS 安全补丁，与 R-019 直接冲突。

### 2.2 为何 Java 21

| 依据 | 说明 |
|---|---|
| jOOQ 3.20+ | **要求 Java 21**（Boot 4.1 基线为 17，但 jOOQ 单独抬高了要求） |
| Spring AI 2.0 | 强制 Java 21+ / Spring Boot 4.x / Spring Framework 7.0+ |
| 长期支持 | Java 21 为 LTS |
| 组织既有 | 环境已具备 JDK 21.0.12 LTS |

三者（Boot 4.1 / jOOQ / Spring AI 2.0）恰好在 Java 21 对齐，无冲突。

### 2.3 配套版本矩阵

| 组件 | 版本 | 备注 |
|---|---|---|
| Spring Framework | 7.0.x | 随 Boot BOM 管理 |
| Spring Security | 7.1 | **全组件化配置**，破坏性变更 |
| Jakarta EE | 11 | Servlet 6.1 / Persistence 3.2 / Validation 3.1 |
| Jackson | **3.x** | Jackson 2 已移除 |
| Hibernate | 7 | 如使用 JPA |
| Flyway | 12.x | 迁移脚本需按新版校验 |
| jOOQ | 3.21.x | 需 Java 21 |
| Tomcat | 11 | Undertow 已移除 |
| JUnit | 6 | JUnit 4 已移除 |

---

## 3. 影响面与应对

### 3.1 可直接复用的能力（降低工作量）

| 能力 | 对应原计划任务 | 阶段 |
|---|---|---|
| `InetAddressFilter` HTTP 客户端 SSRF 防护（DNS 前拦截私网/云元数据） | P3-09 Egress/SSRF | P3 |
| `@ConcurrencyLimit` 内置并发上限 | P4-05 限流与过载保护 | P4 |
| `@Retryable` 内置重试 | ModelGateway 重试 | P2 |
| OpenTelemetry Starter（Kafka/RabbitMQ/JVM 自动装配） | P4-03 可观测性 | P4 |
| API 版本控制（MVC/WebFlux） | P1-D06 错误码与版本策略 | P2 |
| `RestTestClient` | P2-D07 测试计划 | P2 |
| JSpecify 全栈 null-safety | P1-D01 模块边界静态约束 | P2 |

### 3.2 破坏性变更（必须适配）

| 变更 | 影响 | 应对 | 关联任务 |
|---|---|---|---|
| **Jackson 3**（Jackson 2 移除） | 事件序列化、ToolOperation 账本、API 契约 | 序列化层统一适配；契约测试重写 | P2-S2-03/04 |
| **Security 7 全组件化**，`WebSecurityConfigurerAdapter` 移除 | 认证授权链、TenantContext 注入 | P1-04 重新设计安全配置 | P1-04 |
| 模块化 70+ starter | classpath 隐式假设失效 | 只引显式模块 starter；禁止「统一 classpath 一定含某类」的假设 | 全阶段 |
| Hibernate 7 / JSpecify | 领域层空值语义 | 领域层 `@NullMarked` 显式声明 | P1-02 |
| **内置 retry 与自研重试双重重试** | 同一失败被重试两次，可能触发重复副作用 | **明确单一重试点**：模型/工具调用重试统一走 ModelGateway 自研逻辑，内置 `@Retryable` 不用于有副作用路径 | P2-S3-03 |
| Flyway 12.x | 迁移脚本兼容性 | P1-08 迁移规范按新版重写 | P1-08 |
| Undertow 移除 | 若原计划用 Undertow 需替换 | 统一 Tomcat | P1-09 |

> ⚠️ **双重重试是本 ADR 中风险最高的一条**：Spring Boot 4.1 内置 `@Retryable` 与
> ModelGateway 自研重试若同时生效，会对同一模型/工具调用重试两次。对有副作用的工具
> （`record_weakness`）而言，这可能造成重复写入 → Sev-0。必须在 P2-S3-03 明确归属。

### 3.3 ModelGateway 与 Spring AI 2.0 的边界

Spring AI 2.0 与本基线**天然对齐**（Java 21 + Boot 4.x + Framework 7），
统一 `ChatClient` 有利于满足 v3.1「领域层不依赖框架专属类型」。

**关键决策**：Spring AI 2.0 移除了内置工具执行循环，工具不再按 Bean 名自动解析，
须显式注册 `ToolCallback`。本项目**利用**该约束：

```
ToolCallback 注册环节
  → 插入服务端授权检查（FR-008）
  → 插入 Schema 校验（FR-007）
  → 插入 ToolOperation 账本写入（FR-009）
  → 插入审批与预算检查（FR-012/013）
```

由此从**架构上**保证「模型不能绕过服务端授权直接执行工具」（P0-D03 §2 不可妥协边界）。

> ⚠️ Spring AI 2.0 的 `ToolCallingAdvisor` 会自动执行工具循环并<b>绕过</b>上述闸门。
> **本项目禁止使用其自动执行路径**；若需 Advisor 能力，必须在其前后强制插入授权检查。
> 该约束须在 P2-S4-01 实现时由 AG-SEC 交叉审查（ISO-4）。

---

## 4. 验证结果

### 4.1 已完成（P1 工程基线，2026-10-17）

| 验证项 | 方法 | 结果 |
|---|---|---|
| 工程可编译 | `mvn compile` | ✅ 8 模块 BUILD SUCCESS |
| 依赖版本锁定 | `mvn dependency:tree` | ✅ Spring Boot 4.1.1 / Framework 7.0.9 / Security 7.x |
| **领域层零框架依赖** | `AgentCorePurityTest`（3 项） | ✅ agent-core 编译期依赖**仅 JSpecify** |
| 模块依赖方向无循环 | 逐模块 POM 检查 | ✅ core ← ports ← application ← infrastructure ← worker/api |
| Run 状态机 | `StateMachineTest`（10 项） | ✅ 终态不可迁出；非法迁移抛异常 |
| **UNKNOWN 不可盲目重试** | `StateMachineTest` | ✅ UNKNOWN 仅能转对账/人工处置 |
| fail-closed 配置 | `ConfigFailClosedTest`（19 项） | ✅ 缺失/非法配置一律拒绝 |
| 配置快照隔离 | `ConfigFailClosedTest` | ✅ 改配置只影响新 Run |
| 可执行产物 | `mvn package` | ✅ platform-bootstrap fat jar 已生成 |

### 4.2 待验证（P1 剩余）

| 验证项 | 计划 | 门禁 |
|---|---|---|
| Security 7 认证授权链 | P1-04 | M1 |
| 迁移脚本在 Flyway 12 下可重复执行 | P1-08 | M1 |
| 新成员按文档可启动环境 | P1-09 | M1 |
| 集成测试（优先于单测，R-019 应对） | P1-05～08 | M1 |

---

## 5. 回滚方案

### 5.1 触发条件

- Boot 4.1 生态组件（ORM/MQ/驱动）无兼容版本且无 workaround
- 组织运行时策略禁止 Boot 4.x
- 出现无法在合理工期内解决的 R-019 相关阻塞

### 5.2 回滚目标版本

**Spring Boot 3.5.x + Java 21**（不使用 2.x：2.7 已 EOL 且存在未修补的
Spring Framework 5.3 漏洞；3.5 于 2026-06-30 OSS EOL，**仅作为过渡回滚目标**，
需同步评估商业支持或升级到 4.1）。

### 5.3 回滚成本预估

| 影响项 | 成本 |
|---|---|
| 版本矩阵 | 低（仅 BOM 与少量依赖调整） |
| Jackson 3 → 2 | **中**（序列化层需回退，`ObjectMapper` 配置重写） |
| Security 7 全组件化 | **低**（本就需在 P1-04 设计，回退后重新设计即可） |
| 模块化 starter | 低（显式引入的 starter 在 3.x 有等价物） |
| JSpecify | **无**（3.6+ 原生支持） |
| 已写代码 | 低（领域层无框架依赖，v3.1 架构约束使其可跨版本复用） |

> **架构约束的红利**：`AgentCorePurityTest` 保证领域层不依赖任何框架，
> 因此回滚 Boot 版本时**领域层代码零改动**——这正是 v3.1 模块边界设计的价值。

---

## 6. 决策记录

| 项 | 内容 |
|---|---|
| 决策 | 采用 Java 21 + Spring Boot 4.1.1 |
| 决策人 | zhouwei（DEC-4） |
| 起草 | AG-ARC |
| 交叉审查 | AG-SEC + AG-QA（ISO-4，AG-ARC 不得自审） |
| 日期 | 2026-10-17 |
| 状态 | 已决策，待 M1 门禁（W2 D5）完成 PoC 数据核对后签署为 v1.0 |
