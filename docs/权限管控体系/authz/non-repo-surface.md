# D12｜非 Repository 数据访问面矩阵（RLS 迁移前置）

> **关联**：[12_RLS到应用层权限迁移方案.md](../../12_RLS到应用层权限迁移方案.md) §2.5 / §7.1 R8 / D12
> **目标**：方案 §2.5 只覆盖 Repository + RetrievalAdapter；本清单补齐**其余**所有触碰存储的面的
> `ScopeFilter` 处置（改造为 Gate 取 filter / 显式豁免 + 反证），消除迁移后无 `ScopeFilter` 的盲区。
> **状态**：🟡 P0 基线（已扫描 `platform` / `rag` / `scripts` / `deploy/sql`）

---

## 1. 分类

| 类别 | 说明 | 迁移处置 |
|---|---|---|
| **A. Repository 原始 JDBC 面** | 约 30 个 `Jdbc*Repository` 直接使用 `Connection`/`PreparedStatement` | **属迁移主干**：P1/P2 经 `AccessGate` 取 `ScopeFilter` 渲染；`WHERE tenant_id` 来源由手写字面量统一为翻译器产物（12 文 §2.4/§2.5） |
| **B. 非 Repository / 运营面** | 平台运维通道、双写比对、身份解析、种子/夹具、启动脚本 | **本清单重点**：逐条登记处置与反证（下表） |

---

## 2. 非 Repository 面处置矩阵（类别 B）

| # | 面 | 文件 | 现状 | 处置 | 反证 |
|---|---|---|---|---|---|
| **N1** | 平台运维通道 | `.../persistence/cap/GuardedPlatformOpsDataSource.java` | BYPASSRLS 连接闸门 | 语义切换：从「绕过 RLS 的连接」→「携带 `ScopeFilter.Unrestricted` 的专用客户端」；未接入即 503、绝不回落（12 文 §2.5） | 通道未配置凭据 → 503；运维读跨租户成功、租户请求走此通道被拒 |
| **N2** | GUC 绑定 / 租约 | `TenantSessionBinder.java` / `TenantConnectionLease.java` | 每事务 `set_config` + 连接租约 | P5 删除（`@Deprecated`）；过渡期保留 | 删除后全量回归绿 |
| **N3** | 能力双写比对 | `.../persistence/cap/CapDualWriteComparator.java` | 原始 JDBC，已按 `tenantId` 参数限定（`loadCapRows(conn, tenantId, ...)`） | **显式豁免**（运营一致性检查，平台运维域）+ 反证；确认其 `tenantId` 来源受控 | 比对结果不含他租户行 |
| **N4** | 租户身份解析 | `agent-worker/.../dispatch/TenantSlugResolver.java` | `SELECT tenant_id::text FROM tenant WHERE slug = ?` | **豁免 ScopeFilter**（slug 为公开唯一主键，是“建立身份”的入口，非越权读）；须审计：无跨租户泄漏（slug PK） | slug 唯一；无法借 slug 读出他租户数据 |
| **N5** | 启动/运营原始连接 | `platform-bootstrap/.../config/MessagingChannels.java`(`:153`)、`ApplicationConfiguration.java`(`:88`) | 原始 `Connection` 查询（拓扑/探针） | 分类：多为平台运维域；若租户限定须带 `ScopeFilter`，否则**显式豁免 + 反证** | 运营查询不泄漏租户数据 |
| **N6** | 种子/夹具批量灌入 | `scripts/tools/SeedPlatformUsers.java`、`deploy/sql/platform/02_platform_seed.sql`、`deploy/sql/rag/02_rag_seed.sql`、`scripts/dev-seed-rag-corpus.sql`、`rag_ingest_job` | 走绑定租户连接或运维通道批量写 | 必须走 `Unrestricted` 专用客户端；`tenant_id` **必须来自 Gate/已知来源**，禁止外部 CSV/参数直灌（12 文 §2.5） | 夹具加载后跨租户读校验通过 |
| **N7** | DB 初始化脚本 | `scripts/init-db.sh`、`dev-seed.sh`、`dev-it-db.sh` | 运维脚本，不在应用运行时 | 标注即可（不接入 Gate）；与 N6 共用 `Unrestricted` 客户端纪律 | 脚本执行后数据隔离校验 |

---

## 3. 关键结论

1. **A 类是迁移主干**，不属“盲区”——它本就会在 P1/P2 被 Gate 接管；本清单仅列示以证“无遗漏”。
2. **真正的盲区在 B 类**：N1（运维通道）、N3（双写比对）、N6（夹具）若不显式处置，会成为“无 `ScopeFilter` 的特权路径”。
3. **N4（slug 解析）是身份建立入口**，与“租户内查询”语义不同，豁免 ScopeFilter 但必须审计——这正是 12 文 §2.2“scope 可信推导路径”的入口侧。
4. **N6 的铁律**：批量/夹具的 `tenant_id` 必须来自 Gate 产出，禁止外部参数直灌，否则 RLS 退役后成为越权注入点。

---

## 4. P0 出口动作

- [ ] N1–N7 每项的处置与反证方法评审通过。
- [ ] N1 的 `Unrestricted` 客户端语义与 `SubjectContext.isPlatformOps()` 不变式对齐（复用 D3 的 `AccessGate`）。
- [ ] N6 的夹具加载走 `Unrestricted` 客户端 + 跨租户读校验脚本就绪。

---

## 5. 台账已机器化（2026-11-02 更新，D5/D7）

本清单原本是**纯文档台账**——文档会过期，代码不会。自 D5/D7 起，台账的一部分已由
`ScopeBypassArchitectureTest`（`agent-infrastructure`）在构建期强制校验：

| 守卫断言 | 对应台账项 | 变红含义 |
|---|---|---|
| `Unrestricted` 唯一产出点为 `RuleBasedPolicyEngine` | N1（运维通道语义）+ 12 文 §2.2 R3 | 业务代码绕过策略引擎直接拿全量视图 |
| 已登记仓库必须真的调用 `AuthzShadowHooks` | A 类接线诚实性 | 接了 Gate 但从不比对 ⇒ 影子期假绿 |
| 持有 `ScopedDataAccess` 必须登记（`INTEGRATED` 或豁免名单） | 本清单第 2 节 | 新增未登记的数据访问面（漏过滤入口） |
| 台账无漂移（登记文件必须存在） | 本清单 | 守卫在保护已删除/改名的文件（静默失效） |

配套 3 条**扫描器自证**（注入越权构造 / 静默接线必须被检出、合规代码不误报），
避免「正则写错 → 恒 false → 一片绿」的假防守。

**已接线（D5，影子期 dormant 默认关闭）**：

| 仓库 | 覆盖的表 / JOIN | 状态 |
|---|---|---|
| `JdbcAgentMetricRepository` | `platform` schema 指标表（系统连接，无 RLS） | ✅ |
| `JdbcAgentQueryRepository` | `agent` ⟕ `agent_version`（LATERAL） | ✅ |
| `JdbcBudgetRepository` | `budget_reservation` ⋈ `tool_operation`；`budget_account` | ✅ |
| `JdbcDelegationBudgetRepository` | 预算 ⋈ `agent_invocation`（含 `NOT EXISTS tool_operation`） | ✅ |
| `JdbcResultQueryRepository` | `run` / `weakness_record` / `run_step` / `tool_operation`（一次读 4 表） | ✅ |

> ⚠️ **染色必须用「行租户」**：首版接线拿查询绑定租户当行租户，导致恒不违例（零检出力）。
> 现已给上述 SQL 补 `tenant_id::text` 并改读行；聚合查询不做染色（无行租户）。
> 这条属于**接线正确性**问题，而不仅是覆盖率问题 —— 详见 12 文 §11.1「染色实现的两条硬约束」。

> ⚠️ **A 类剩余约 55 个 `Jdbc*Repository` 仍待 P2 逐域接线**。守卫的「必须登记」断言
> 只在仓库**持有 `ScopedDataAccess`** 时生效；未接线的仓库当前既无 Gate 也无影子比对，
> 因此 P1 的 `AUTHZ_MISMATCH=0` **不能**外推为「全量已隔离」——它只覆盖上述 4 仓。
> 这是 P1 出口结论必须写明的限定条件。

---

## 3. WP-M1-2 仓库 Gate 决策台账（2026-11-12 triage 定案）

> 本节是改造计划 WP-M1-2 的落定产物。**修正**：上文「A 类剩余约 55 个待接线」为过时口径——
> 实测 2026-11-12：59 个 `Jdbc*Repository` 中 38 个已接影子、本日新增 3 个
> （RunStep / RunMessage / RunClaim），台账 `INTEGRATED` 已达 41。
> `AUTHZ_MISMATCH=0` 的覆盖面结论以本节台账为准。

### 3.1 本日新接（影子比对，守卫全绿）

| 类 | 决策 | 形态 | bind 去向 |
|---|---|---|---|
| `JdbcRunStepRepository` | `INTEGRATED` | 写路径 `compare("run_step")` | M4 退役 run_step 批 |
| `JdbcRunMessageRepository` | `INTEGRATED` | 写路径 `compare("run_message")` | M4 退役 run_message 批 |
| `JdbcRunClaimRepository` | `INTEGRATED` | 写主导：5 个 GUC 绑定站点 1:1 `compare("run")`，无染色点 | M4 退役 run 批 |

### 3.2 数据访问面 triage 结论（2026-11-12 更新：7 项待接线已全部完成）

| 类 | 决策 | 理由 / 替代控制 | 后续动作 |
|---|---|---|---|
| `AdminTenantRepository` | `PLATFORM_LEVEL_EXEMPT`（分面） | 全局 `tenant` 表（无 RLS 无 tenant_id）走系统身份；RLS 表读取（principal/agent/tenant_config_override 计数与 listPrincipals）= 平台管理员**按设计跨租户**，控制器 PLATFORM_ADMIN 闸门 + SQL 显式谓词双重控制 | ⚠️ `verifyIsolation`（:209-233）是 **RLS 校验端点本身**（读 `current_setting` 断言 RLS 生效）——**不是隐性依赖、不改 SubjectContext**；RLS 退役后该端点语义失效（`tenant_id <> 目标` 计数将泄漏全量）⇒ **M5 必须下线或重写**，改列 WP-M5 清单 |
| `JdbcStatisticsRepository` | `INTEGRATED` ✅ **已接线** | **纠正计划过时项**：实测无任何 `current_setting` 代码读取（WP-M1-3 的「隐性 GUC」指称不成立）；全 SQL 显式 `tenant_id = ?::uuid` + holder 中心绑定；纯聚合无行租户（不染色） | ✅ 已接 `compare("run")`（聚合扇出同连接同租户，由同一比对覆盖） |
| `JdbcAgentChatAuditRepository` | `INTEGRATED` ✅ **已接线** | 租户读路径，显式谓词 + holder 中心绑定 | ✅ 已接 compare |
| `JdbcAgentManageRepository` | `INTEGRATED` ✅ **已接线** | 写主导（INSERT…RETURNING），javadoc 已自标不参与染色；TenantConnectionLease 自管 | ✅ 已接 compare（`createAgent`/`publishVersion` 入口） |
| `JdbcAgentReleaseArchiveRepository` | `INTEGRATED` ✅ **已接线** | 混合读写，显式谓词 + holder 中心绑定 | ✅ 已接 compare |
| `JdbcExecutionSnapshotResolver` | `INTEGRATED` ✅ **已接线** | 读路径，显式 `a.tenant_id` 谓词；`execution_snapshot` 资源类型已登记 | ✅ 已接 compare-only（SELECT 未取 tenant_id 列，无行租户） |
| `JdbcRunEventAppendRepository` | `INTEGRATED` ✅ **已接线** | 读 + 追加，显式谓词 + TenantConnectionLease 自管 | ✅ 已接 compare |
| `JdbcRateLimiter` | `INTEGRATED` ✅ **已接线** | `rate_limit_bucket` 租户域基础设施，显式 `tenant_id + bucket_key` 谓词，返回计数无行租户 | ✅ 已接 compare-only（`check`/`currentCount` 入口） |
| `JdbcPlatformAuditRepository` | `PLATFORM_LEVEL_EXEMPT` | `platform.platform_audit_event`：无 `tenant_id`、无 RLS，平台运维审计 | 登记「豁免理由 + 替代控制」（平台通道认证） |
| `JdbcPlatformPrincipalRepository` | `PLATFORM_LEVEL_EXEMPT` | `platform.platform_user`：无 `tenant_id`、无 RLS，平台控制台用户域 | 同上 |
| `JdbcTenantLifecycleProbe` | `PLATFORM_LEVEL_EXEMPT` | 只读全局 `tenant` 表 status（无租户数据行） | 同上 |

### 3.3 台账外确认（此前误报为未登记）

- `JdbcToolOperationLedger`：**已在 `INTEGRATED` 台账**且有专属 `JdbcToolOperationLedgerShadowTest`（此前 diff 正则漏匹配非 `Repository` 后缀类名，误报）。
- `PrincipalRepository` / `StudentDataRepository` / `GetStudentProfileTool` / `RecordWeaknessTool`：triage 结论见改造计划 WP-M1-1（bind 保留 M4-B4/B2，接线动作随 M3 前置）。

### 3.4 纪律与闭账状态

**WP-M1-2 出口达成（2026-11-12）**：原「待接线」7 项全部按非侵入样板完成
（compare-only 写路径/聚合面 + `@Autowired(required=false) bindAuthzShadow` +
双台账登记），三层守卫（`ScopeBypassArchitectureTest` / `ShadowPolicyCoverageTest` /
`AuthzShadowWiringTest` 4 用例）全绿；`INTEGRATED` 台账达 **49**。
新增登记资源类型：`run_event`、`rate_limit_bucket`（否则 Gate 恒返 NO_RULE 假违例）。
剩余闭账项：豁免 4 项 + 既有 7 项补「豁免理由 + 替代控制」两列（本表已含豁免理由）。
