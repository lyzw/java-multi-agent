# 开发者 onboarding 手册（P2-7）

> **目标读者**：新加入本仓库的后端开发。按本文操作，**一小时内**应能：
> (1) 看懂六边形分层；(2) 用纯 `agent-core` 的 `QuickstartDemoTest` 理解三个核心概念；
> (3) 用 `./scripts/dev-env-up.sh` 一键拉起本地环境并跑通冒烟测试。

---

## 1. 项目是什么

`java-saas-agent-platform` 是一个 **Java 21 / Spring Boot 4.x** 的多租户 **Agent 编排平台**。
核心主张是「**能力（Capability）统一治理**」：Agent / Skill / Tool / MCP Server 全部以
固定版本号纳入 **有效能力闭包（EffectiveClosure）**，任何一次 Run 的授权判定都以
这个快照为唯一事实依据。同时用**应用层行级隔离（ScopeFilter）**替换 PG RLS 谓词，
把「谁能看哪行」从数据库下沉到可单测的领域代码。

## 2. Maven 多模块（六边形）

源码在 `platform/` 下的多个模块。依赖方向**唯一、单向**，由
`platform/agent-core → … → platform-bootstrap` 单向流动，并由
`ModuleDependencyDirectionTest` 强制（倒流即红）：

| 模块 | 归属 | 职责 |
|------|------|------|
| **agent-core** | 领域核心 | 纯领域对象，**零框架依赖**（不 import Spring）。`RunState`、`EffectiveClosure`、`ScopeFilter`、`TenantContext` 等全在这里。 |
| **agent-ports** | 端口 | 应用需要的外部能力**接口**（repositories、send 通道、安全端口等）。 |
| **agent-application** | 应用服务 | 用例编排 + 应用级策略（如 `AccessGate`、`CapEnforcementSwitch`）。依赖 ports，不依赖 infrastructure。 |
| **agent-infrastructure** | 外部适配 | `Jdbc*Repository`、MQ 消费者、`ScopeTranslator` 实现。 |
| **agent-worker** | 执行 | 领取 Run、执行工具调用（`ToolInvocationGate`）。 |
| **agent-api** | 入口 | Web Controller / DTO。 |
| **platform-bootstrap** | 装配根 | Spring Boot 入口，`@Configuration` 把一切 wire 起来。 |

依赖倒置：**依赖永远指向 center**。`agent-infrastructure` 实现 `agent-ports` 的接口，
但注册由 `platform-bootstrap` 决定——容器里放什么 Bean，运行就用什么。

## 3. 三个核心概念（走读）

### 3.1 Run 状态机（`agent-core/.../core/run/RunState.java`）

```
QUEUED ──claim──> RUNNING ──complete──> SUCCEEDED (终态)
   │                  ├──fail─────> FAILED       (终态)
   │                  ├──cancel───> CANCELLED    (终态)
   │                  └──timeout──> TIMED_OUT    (终态)
   └──cancel─────────> CANCELLED                 (终态)
```

关键约束：**迁移必须经过 `requireTransitionTo()`**，非法抛异常而非静默忽略；
**终态不可迁出**（防并发覆盖）。`WAITING_APPROVAL` 是 REST 态的「挂起」，
非终态，审批后 `RESUME → RUNNING`。

### 3.2 有效能力闭包（`agent-core/.../core/cap/EffectiveClosure.java`）

闭包 = 一次调用**实际依赖**的全部对象固定版本：`Agent → Skill → {Tool, MCP}`，
外加 `orchestration` 声明与多级 `childAgents` 委派边。它是闭包指纹的唯一输入——
指纹变了 → 在途 Run 判定失效。

> ⚠️ 闭包**只含本 Run 依赖的对象**，不混入全局发布序号；**生效后**状态
> （overrides 已应用）。因此对侧是**确定性**的：依赖集合必须产出逐字段相同的闭包。

### 3.3 行级隔离（`agent-core/.../core/security/ScopeFilter.java`）

`ScopeFilter` 是行级隔离的**唯一决策权威**，替换 PG RLS 谓词。四种形态：

| 形态 | 等价 RLS | 语义 |
|------|---------|------|
| `TenantWithShared(t)` | `tenant_id IN (current, global_shared)` | 本租户私有 + 平台公开 |
| `TenantOnly(t)` | `tenant_id = current` | 仅本租户 |
| `Unrestricted(reason)` | `BYPASSRLS` 视图 | 平台全域 |
| `None()` | fail-closed | 恒假 → 0 行 |

新增存储后端 = 实现一个 `ScopeTranslator`，**不需要新增权限规则**。

## 4. 影子权限体系（近期重点）

安全链路是「准入（fail-closed）+ 影子比对（观测不阻断）」两层：

- `AccessGate → DefaultAccessGate` 做**声明式准入**（fail-closed）。
- 泄露影子档 `AuthzShadowBinding`（含 `ScopedDataAccess` 与 `AuthzShadowReporter`）
  由 `AuthzShadowConfiguration` 在开关 `agent.authz.shadow.enabled=true` 时装配。
- **仓库接入**：实现类持 `bindAuthzShadow(binding)` setter（`@Autowired(required=false)`，
  容器里没 binding 就保持 null/false ⇒ 零行为变更），并在**读方法**调用
  `shadowCompare(...)` / `shadowTaint(...)` 两个**静态** hook（委托给 `AuthzShadowHooks`）。

> ⚠️ 接入的仓库绝不能自己实现影子逻辑，必须委托 `AuthzShadowHooks`——由
> `ScopeBypassArchitectureTest` 静态扫描强制，且新接入仓库**必须登记**
> `ScopeBypassArchitectureTest.INTEGRATED` 账本（不登记 = 扫描红）。

**总开关与租户灰度**：`CapEnforcementSwitch.isEnabled(tenantId)` =
`globalEnabled && (tenantAllowlist.contains(tenantId))`。配置键
`config.flag.cap-authorization-enforced[.tenants]`（生产默认 false / 空）。
灰度语义：白名单是「先行者」，**不是**豁免；全局开启后白名单失效。

## 5. 一小时内跑通（冒烟）

前置：JDK 21 + Maven。设置环境变量见 `docs/本地启动指南.md §1.1`。

```bash
# ① 一键拉起：PG(5433) + RabbitMQ(5672) + 迁移/自检 + agent-core 冒烟测试 + 打包
./scripts/dev-env-up.sh

# ② 只跑纯领域冒烟（不启动服务，最快验证理解）
./scripts/dev-env-up.sh --smoke-only

# ③ 单独跑 onboarding 演示测试
mvn -o -pl agent-core test -Dtest=QuickstartDemoTest
```

冒烟测试跑的是 `agent-core/src/test/.../quickstart/QuickstartDemoTest.java`，
纯领域对象、零连接，展示 RunState 迁移 / CAP 闭包 / ScopeFilter。跑通即入门。

启动后端与前端见 `docs/本地启动指南.md` §4、§5。

## 6. 常见误区

- **依赖方向**：core 里禁止 import Spring；infrastructure 不允许被 application 的
  上层反向依赖。
- **终端态不可迁出**：别为「幂等重试」从 `FAILED` 退回 `RUNNING`——这是红线。
- **影子只是观测**：默认 `shadowEnabled=false` 时不改变任何行为；接入仓库别顺手
  改 SQL 或签名。
- **灰度白名单是先行者，不是豁免**：改 `cap-authorization-enforced.tenants` 前先想清楚。

## 7. 相关文档

| 文档 | 内容 |
|------|------|
| `docs/本地启动指南.md` | 实测过的一整套本地启动细节（端口、账号、避坑） |
| `docs/阶段一详细设计_风险收敛_v1.0.md` | 影子接入与灰度开关的设计出处 |
| `docs/RLS到应用层权限迁移-问题方案与方法论归档.md` | 权限迁移沉淀 |