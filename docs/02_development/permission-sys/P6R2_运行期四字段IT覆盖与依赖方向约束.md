# W-PERM-P6R2：运行期四字段的 IT 层覆盖 + 模块依赖方向约束

> **性质**：P6 登记限制 #2 / #3 的**回流交付**，**不是**新的 W-PERM 阶段。
> W-PERM 的阶段划分仍是 P0–P6 七个（见 `00_完整开发计划.md` §4）。
>
> **日期**：2026-10-23
> **基线提交**：`d94fddd`（W-PERM-P6R1 平台运维角色接入点）

---

## 1. 被消解的两条限制

| # | P6 登记的限制 | 原状态 | 本次结论 |
|---|---|---|---|
| 2 | **NFR-03 依赖方向只有 agent-core 纯度测试** | 「agent-worker 不得直连 infrastructure 持久层」等模块级约束**无自动化** | ✅ **已建立模块级静态扫描**（7 项断言） |
| 3 | **S4a/S4b/S4c/S5b/S5c/未知风险/参数策略失败 缺 IT 层** | 这些场景**只有单测层**证据；运行期四字段**未在真实 PG 上验证** | ✅ **7 个场景已有 IT 层证据**（其中「未知风险」的结论形态特殊，见 §3.2） |

---

## 2. 为什么这层缺口值得单独补

§8.3 的冻结门槛要求「行为测试四字段 + 原因码全绿」。
P6 判定该门槛达成 —— 因为**单测层**已经全绿。

但单测层用的是**内存替身**，而替身与真实仓储的差异恰恰是最容易藏 bug 的地方：

| 真实 PG 才暴露的问题 | 替身为什么看不到 |
|---|---|
| 对象版本行存在但**没有已发布指针** → 闭包解析失败 | 替身直接返回对象 |
| 闭包指纹算不出 / 与快照不一致 → `CONFIG_FINGERPRINT_STALE` | 替身的指纹是硬编码 |
| 能力快照缺失 → `RESOURCE_UNKNOWN` | 替身永远返回快照 |
| RLS 把对象过滤掉 → 「已启用」实际读不到 | 替身不过 RLS |
| 「无副作用」只是一句断言 | 替身只能数方法调用次数，**证明不了「什么都没写下去」** |

本次新增的两个 IT 类**全部使用真实 JDBC 仓储**，
断言直接落在**真实表**上。

---

## 3. 证据

### 3.1 新增用例（16 项）

| 测试类 | 项数 | 覆盖场景 | 锁住的性质 |
|---|---|---|---|
| `RuntimeDecisionScenarioIT` | 7 | S4a / S4b / S4c / S5b / S5c（两码）/ 未知风险 | 运行期四字段在**真实发布指针 + 真实闭包 + 真实快照 + 真实指纹 + 非特权角色 RLS** 下给出同样结论 |
| `ExecutionPhaseBScenarioIT` | 1 | Schema 失败 | Phase B 的拒绝落在**任何写入之前**：决策日志恰好 1 行，执行意图/审批/预算/账本/结果事件**全部 0 行** |
| `ModuleDependencyDirectionTest` | 7 | NFR-03 | 依赖方向不可反转：领域层不认识人、端口层不认识实现、应用层不认识基础设施、外层不直连持久层 |

### 3.2 场景逐条对照（§9.1 24 行 + 3 行「—」）

| 场景 | 原证据层 | 本次 IT 落点 | 判定形态 |
|---|---|---|---|
| **S4a** Skill 从 enabled 移除 | 仅单测 | `RuntimeDecisionScenarioIT#skillRemovedFromEnabledIsDeniedOnRealDatabase` | 四字段（真实**移除转移**：启用时 ALLOW → 移除后新调用 DENY） |
| **S4b** riskCeiling 收紧 | 仅单测 | `#tightenedRiskCeilingIsDeniedOnRealDatabase` | 四字段（真实**收紧转移**） |
| **S4c** 参数策略收紧 | 仅单测 | `#tightenedParamPolicyIsDeniedOnRealDatabase` | 四字段（真实**策略收紧转移**） |
| **S5b** 操作级越界 | 仅单测 | `#mcpOperationOutsideAllowlistIsDeniedOnRealDatabase` | 四字段（真实库闭包：Skill 操作清单 ∩ 已发布服务定义） |
| **S5c** 清单漂移 · `CONFIG_FINGERPRINT_STALE` | 仅单测 | `#narrowedServerAllowlistInvalidatesFingerprintOnRealDatabase` | 四字段（**纯 DB 漂移**：已发布白名单收紧） |
| **S5c** 清单漂移 · `MCP_TOOL_DRIFTED` | 仅单测 | `#mcpManifestToctouDriftIsDeniedOnRealDatabase` | 四字段（TOCTOU：清单在指纹重算与操作复核之间变化） |
| **参数策略失败**（— 行） | 仅单测 | 同 S4c（同一码、同一条判定路径） | 四字段 |
| **未知风险**（— 行） | 仅单测 | `#unknownRiskCannotBePersisted` | ⚠️ **形态特殊，见下** |
| **Schema 失败**（— 行） | 仅单测 | `ExecutionPhaseBScenarioIT#schemaFailureDeniesWithoutSideEffectsOnRealDatabase` | 四字段 + **真实表零副作用** |

> ⚠️ **「未知风险」为什么不是四字段 IT**：
> `cap_tool_def.risk_level` 是 `NOT NULL + CHECK`，取值域只含三个已知等级，
> **未知风险在数据层根本写不进去**。
> 故 IT 层能证明的是「**该状态不可达**」，
> 而运行期四字段仍由单测覆盖（若该状态因任何原因出现，拒为 `RISK_LEVEL_UNKNOWN`）。
> 两者合起来才是完整结论：**状态不可达 + 不可达时仍 fail-closed**。
> 这一形态已如实写入 `Section9ScenarioRegistry` 的备注 ——
> **不把「不可达」伪装成「四字段已验证」**。

### 3.3 哪些是真实 PG、哪些是替身（逐条说清）

| 层 | 状态 |
|---|---|
| 对象版本行、**发布指针**（§6.3 第 5 条）、Agent 定义、能力快照、闭包指纹、决策日志、执行意图、审批、预算、账本、结果事件 | ✅ **真实 PG** |
| RLS 租户边界 | ✅ **非特权角色 `app_agent`**（NOSUPERUSER NOBYPASSRLS） |
| MCP **传输边界**（`listTools`，即「远端当前暴露了哪些 op」） | ⚠️ **替身** —— 清单按定义来自外部 MCP Server，IT 不可能真连一个外部 server；清单的求交、指纹、绑定全部由真实代码与真实库完成 |

### 3.4 机器校验同步更新

`Section9ScenarioRegistry` 的证据落点与备注同步更新，
`Section9ScenarioCoverageTest`（8 项）**保持全绿** ——
它会逐条核对：

1. 场景集合与设计文档 §9 表格**逐字一致**；
2. 四字段期望与文档一致；
3. 证据方法**存在**；
4. 证据方法体里**真的断言了那个原因码**（剥离注释与字符串后查常量引用）。

> ✅ 因此「登记了 IT 落点」这件事本身是被机器验证的，
> 不会出现「文档说验过了、方法体是空的」。

### 3.5 NFR-03 模块依赖方向约束

`ModuleDependencyDirectionTest`（7 项，静态扫描）：

| 规则 | 层次 | 说明 |
|---|---|---|
| 领域层不认识任何人 | pom | `agent-core` 不得依赖任何其他平台模块 |
| 端口层不认识实现 | pom | `agent-ports` 只允许依赖 `agent-core` / `agent-test-support` |
| 应用层不认识基础设施 | pom | `agent-application` 只允许依赖 `agent-core` / `agent-ports` / `agent-test-support` |
| 应用层源码不引用 infrastructure | 源码 | 依赖方向反转最直接的证据 |
| 端口层源码不引用外层 | 源码 | 端口一旦依赖实现，「定义在内、实现在外」就名存实亡 |
| **agent-worker 不直连持久层** | 源码 | ✅ **P6 登记的原始诉求**；直连会绕过幂等、预算、账本与审计 |
| agent-api 不直连 infrastructure 非白名单包 | 源码 | 只放行 `infrastructure.security`（认证实现） |

> ⚠️ **只检查非 test 作用域的依赖**：test 作用域（如 `agent-test-support`）
> 不构成运行期的依赖方向 —— 它们只在测试期可见，不会把实现渗进产物。

---

## 4. 过程中的真实修复

### 4.1 跨测试类的夹具残留（与 R1 同源，已根治）

`CapITBase.cleanCapTables` 是**所有 CAP IT 共用的清理点**。
本次新增的 `it-r2-%` / `it-r2b-%` 前缀若未登记，
其残留会污染其他 IT 的「零写入」类断言。

**约定**：新增 IT 类若使用自有对象 id 前缀，
**必须**到 `CapITBase.cleanCapTables` 登记；
否则残留会让别的测试类的断言失真，而失败现场与真因相隔一个类。

### 4.2 执行流的「主体是 Worker 服务账号」

`CapExecutionFlow` 的 Phase A 构造可信上下文时，
主体固定为 `CapExecutionFlow.DEFAULT_PRINCIPAL_ID`（worker 服务账号），
角色集固定为 `{WORKER}`。

⚠️ 第一版 IT 夹具自造了主体与 `TEACHER` 角色 →
Phase A 在**主体授权阶段**就拒绝（`AUTHZ_ROLE_MISSING`），
根本走不到 Schema 校验 —— 断言的将是「授权拒绝」而非「Schema 失败」。

**这类「前置阶段先失败」的失真最难发现**：断言写的是四字段，
但被测的其实是另一个拒绝原因。

### 4.3 「零副作用」断言必须按本次调用限定，不能用全表计数

首版 `ExecutionPhaseBScenarioIT` 用**全表计数**断言零副作用：

```sql
SELECT count(*) FROM budget_reservation;   -- 期望 0
```

单跑该 IT 时全绿，**全量构建时被打红**（实际 1 行）。

⚠️ **根因**：`budget_reservation` 与 `tool_operation` 是 **V1 基座表**，
不在 CAP 基座 `CapITBase.cleanCapTables` 的清理范围内
（那里只清 `cap_*` 与 `execution_*`）。
邻座 IT 的残留被我的全表计数数了进去。

**修法**：所有「零副作用」断言改为**按本次调用精确限定**
（`run_id` / `invocation_id` / `tool_id`）：

```sql
SELECT count(*) FROM budget_reservation WHERE run_id = '<本次 runId>'::uuid;
```

> 📌 **给后续 IT 类的约定**：「本次调用没有留下任何副作用」这句话，
> 断言的必须是<b>本次调用</b>，不是<b>这张表</b>。
> 全表计数把「别人留下的」也算进来，既会假红，也会在清理漏项时假绿。

---

## 5. 仍未消解的限制

| # | 限制 | 影响 | 责任角色 | 回流阶段 |
|---|---|---|---|---|
| 1 | **R-15 性能目标无任何压测/基准测试** | **无** —— §8.2 明文「目标由压测标定」，§8.3 未把性能列为硬门槛。但**结论不得声称「性能达标」** | 性能专项 | P6 之后 |
| 4 | **COMP-2 的文档字面表述在实现中不可达** | **无** —— P3 已登记偏差（PRE 登记前移），不改写权威文档 | 已裁决 | — |
| 5 | **S9 与 COMP-4 是同一注入点** | **无** —— 事实登记 | 平台组 | — |
| 7 | **发布 / 回滚 / 生命周期迁移未接管理端点** | **无** | 平台组 / 安全 | P6 之后 |
| 8 | **`dynamicDiscovery` 写入侧校验未接管理端点** | **无** | 平台组 | P6 之后 |
| 9 | **§5.7 权威表未回填 P2 新增的 5 个原因码** | **无** —— 平台侧 `ReasonCode` 已含全部 30 码；权威表仍是 P1 快照 | 设计文档负责人 | 需设计文档回填 |
| 10 | **`trustTier` 的 ForcedSafety 语义归属未定** | **无** | 设计文档负责人 | 需设计文档确认 |
| 11 | **S3g-c 与 S3f 共用撤权 flavor 的同一方法** | **无** —— 事实登记 | 平台组 | — |
| 12 | **P4 其余交接项**（清单 TTL / 负缓存、SSE 旧版握手、连接池、完整 JSON Schema） | **无** | 平台组 | P6 之后 |

---

## 6. 变更清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `platform/agent-infrastructure/src/test/.../cap/RuntimeDecisionScenarioIT.java` | 新增 | 7 项：S4a/S4b/S4c/S5b/S5c×2/未知风险不可达 |
| `platform/agent-infrastructure/src/test/.../cap/ExecutionPhaseBScenarioIT.java` | 新增 | 1 项：Schema 失败 + 真实表零副作用 |
| `platform/platform-bootstrap/src/test/.../config/ModuleDependencyDirectionTest.java` | 新增 | 7 项：NFR-03 模块依赖方向 |
| `platform/agent-test-support/src/main/.../acceptance/Section9ScenarioRegistry.java` | 修改 | 8 个场景的证据落点与备注（机器校验保持全绿） |
| `platform/agent-infrastructure/src/test/.../cap/CapITBase.java` | 修改 | 统一清理点登记 `it-r2-%` / `it-r2b-%` 前缀 |
| `P6_V1冻结判定报告.md` | 修改 | 追加「后续回流」指针（不改冻结结论） |
| `P6_需求追踪终态矩阵.md` | 修改 | 追加限制 2 / 3 的消解指针 |
| `Java SaaS Agent 平台 v3.1：...清单.md` | 修改 | P3 退出门禁的 W-PERM 切片证据表**追加**两行 |
