# 12｜RLS → 应用层权限迁移方案（数据库无关化改造）

> **定位**：把**行级隔离的唯一决策权威**从 PostgreSQL RLS 策略上移到应用层，解除权限逻辑与数据库的耦合，使后续可从容迁移到不同关系型数据库或向量库。
> **现状基线**：Spring Boot 4.1.1 · Java 21 · PostgreSQL 17 · `TenantContext` + `TenantSessionBinder` + `FORCE RLS`（42 张 platform 表 + 13 张 rag 表带 FORCE；DDL 共 **89** 条 `CREATE POLICY`：platform 50 + rag 37 + 增量 2）。
> **与本系列的关系**：05 是**能力层**（Agent/Skill/Tool/MCP）的授权决策规范；本文是**数据层**（行级租户隔离）的决策规范。两者共用同一套主体上下文与审计纪律，但**决策因子互不重叠**——能力层管「能不能调这个工具」，数据层管「能碰到哪些行」。
> **状态**：🟡 提案（待评审）。本文只做设计，**未进入实施**；落地计划见 §6。
> **证据基准**：本文所有「现状」断言均以真实代码/脚本落点为准，标注文件路径；无命中即写「全仓无命中」。

---

## 0. 一句话结论

**RLS 不是要被「废除」，而是要从「唯一权威」降级为「可拆卸的最内层兜底」。**
迁移的实质不是重写权限逻辑，而是把**已经在应用层手写的 `WHERE tenant_id`** 从「与 RLS 重复的第二道防线」扶正为**唯一权威**，并把 RLS 策略谓词逐条机械翻译成**数据库无关的语义对象**（`ScopeFilter`）。
**可行性依据**：仓库现有代码里，权限过滤**已经**在应用层写了一遍（每个 Repository 显式 `WHERE tenant_id`），RLS 是叠加在上面的第二道。⇒ 迁移是**删除冗余**而非**从零新建**，这是本方案能平滑推进的根本原因。

---

## 1. 现状问题分析：PG RLS 的具体耦合点与迁移障碍

### 1.1 耦合点清单（按代码实证）

| # | 耦合点 | 实证落点 | 绑死程度 | 迁移后果 |
|---|---|---|---|---|
| **C1** | **会话变量机制** | `platform/agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/TenantSessionBinder.java:42-47` — `set_config('app.tenant_id', ?, true)` | ★★★★★ | PG 私有 GUC。MySQL 用 `@var`（**无事务局部语义**）、Oracle 用 `DBMS_SESSION`、SQL Server 用 `SESSION_CONTEXT`、向量库无会话概念 ⇒ 整层消失 |
| **C2** | **策略谓词内嵌 PG 函数** | `deploy/sql/platform/01_platform_ddl.sql` 50 条策略；`deploy/sql/rag/01_rag_ddl.sql` 37 条策略 + `rag.rag_current_tenant()`（`:230`）/ `rag.rag_global_tenant_id()`（`:261`） | ★★★★★ | 自定义函数是 PG 方言，迁移即失效 |
| **C3** | **角色能力模型** | `deploy/sql/platform/03_platform_roles.sql:98`（`app_agent` NOBYPASSRLS）`:122`（`platform_ops` **BYPASSRLS**）；`deploy/sql/rag/04_rag_ops_role.sql:77`（`rag_ops` BYPASSRLS） | ★★★★ | 依赖 PG 特有的 `BYPASSRLS` 属性 + `FORCE ROW LEVEL SECURITY` 语义 |
| **C4** | **连接身份即权限边界** | `platform/agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/GuardedPlatformOpsDataSource.java` — 平台运维通道 = 一条 BYPASSRLS 连接 | ★★★★ | 权限边界由**连接池配置**表达 ⇒ 换到没有 RLS 概念的存储，这层表达直接消失 |
| **C5** | **检索谓词与存储绑定** | `rag/rag-ports/src/main/java/cn/studykid/rag/ports/RetrievalPort.java:77-80` — 契约明写依赖 `set_config('app.tenant_id', ..., true)` 与 `tenant_id IN (rag_current_tenant(), rag_global_tenant_id())` | ★★★★ | 权限过滤**写在 SQL 文本里** ⇒ 换向量库需重写整套谓词 |
| **C6** | **证伪基建绑定 pg_catalog** | `platform/agent-infrastructure/src/test/java/cn/studykid/multiagent/infrastructure/RlsPolicyAlignmentTest.java`；`.../persistence/cap/MultiAgentSchemaIT.java`（断言 RLS enabled 且 FORCE）；`scripts/counterproof-p4d13.sh:73`（删策略 → 跨租户用例必须红） | ★★★ | 退役 RLS 后这套证伪**同时失效**，必须整体重建（见 §6.5） |
| **C7** | **fail-closed 语义由 DB 提供** | `deploy/sql/rag/01_rag_ddl.sql:230-254` — `rag_current_tenant()` 无上下文返回 NULL（不报错）；`问题记录/03-数据库权限与RLS.md` DBP-08 — 空 GUC → 0 行 | ★★★★★ | **迁移期最大的开天窗风险**：应用层若不接住「出错即拒绝」，双写期出现语义空窗 |
| **C8** | **运维盲区（RLS 特有）** | `scripts/doc-c27-1.py:178` — V26 回填在 FORCE RLS 下**静默影响 0 行**（迁移报成功、状态全是 DRAFT）；`问题记录/02-数据一致性与写链.md:231` — 巡检连接身份不对导致 RLS 收窄可见行、结论失真 | ★★★ | 这些是「权限在 DB」的结构性陷阱，应用层方案从根上消除 |

### 1.2 迁移障碍的本质

```
现状：权限决策 = f(PG会话变量, PG策略谓词, PG角色, PG函数)
                    ↑ 四个输入全部来自数据库内部
      应用层的角色 = 「递参数的人」（TenantSessionBinder 注释自述：「这是 RLS 能否真正生效的关键环节」）

目标：权限决策 = f(认证上下文, 权限模型, 资源元数据)   ← 全部在应用层
      数据库     = 哑存储 + 可选兜底约束
```

**核心矛盾**：应用层**知道**租户是谁（`TenantContext` 已有 `tenantId/principalId/roles/authzVersion` 四要素），却把**裁决权**交给了 DB。迁移 = 把裁决权收回来；最大风险 = **收回过程中两层防线出现空窗**。

### 1.3 已经存在的半成品（迁移可借力的资产）

| 资产 | 落点 | 复用方式 |
|---|---|---|
| 主体上下文四要素齐备 | `platform/agent-core/src/main/java/cn/studykid/multiagent/core/tenant/TenantContext.java:26-33`（`tenantId / principalId / roles / authzVersion`） | 直接演进为 `SubjectContext`（加 `scope`），**不新建主体模型** |
| 应用层过滤已存在 | 全部 Repository 显式 `WHERE tenant_id = ?::uuid`（如 `scripts/c34-1-counterproof.sh:206`） | 这些谓词的**来源**从「手写」改为「由 `ScopeFilter` 翻译」 |
| 端口契约方向正确 | `RetrievalPort.java:20-26`：「端口暴露的是**检索能力**而非向量库 API」「过滤若由调用方事后做…极易被误用」 | **契约一字不改**，只把 SQL 内嵌谓词换成翻译结果 |
| 事务绑定纪律成熟 | `TenantSessionBinder.java:31-33`：`set_config(..., true)` 事务局部 + 获取连接无条件重置 | 这套「无条件重置」的纪律迁移到 `ScopeFilter`（构造期固化，永不复用） |
| 平台闸门形态完备 | `GuardedPlatformOpsDataSource` + `PlatformOpsSessionGuard`（未接入即 503、绝不回落） | 形态保留，语义从「绕过 RLS 的连接」改为「携带 `Unrestricted` 范围的客户端」 |
| 反证方法论成熟 | `scripts/counterproof-*.sh` 系列 + 「改动后结论不变即判无效反证」 | 方法论原样迁移，**证伪对象**从 DB 结构换成应用层装配 |

---

## 2. 目标架构：应用层权限控制的分层设计

### 2.1 五层总览

```
┌──────────────────────────────────────────────────────────────────┐
│ L4 审计与证伪层  AuditLedger · PolicyDecisionLog · 反证测试         │
│    「谁、何时、以何依据、碰了什么、被谁拒绝」                       │
├──────────────────────────────────────────────────────────────────┤
│ L3 数据访问层  Repository / RetrievalAdapter                       │
│    唯一允许接触存储的层；查询必须携带 ScopeFilter                  │
│    ⚠️ 不提供「无过滤」查询方法 —— 类型系统层面无法绕过               │
├──────────────────────────────────────────────────────────────────┤
│ L2 鉴权层  AccessGate（入口强制点）                                │
│    authorize(subject, action, resourceRef) → Decision             │
│    ⚠️ 编译期无法绕过：Repository 构造依赖 Gate，无 Gate 无实例      │
├──────────────────────────────────────────────────────────────────┤
│ L1 权限模型层  PolicyEngine · PolicyStore                          │
│    主体/角色/资源/动作/作用域五元组；决策可解释 · 可缓存 · 可版本化   │
├──────────────────────────────────────────────────────────────────┤
│ L0 身份上下文层  SubjectContext（取代裸 TenantContext）              │
│    认证入口唯一写入点；不可变 record；禁止从请求参数直接构造          │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 L0｜主体上下文（SubjectContext）

`TenantContext` 已有 `tenantId / principalId / roles / authzVersion`；**只增不改**地扩一个作用域维度，用来表达「平台运维身份」——这是替代 BYPASSRLS 连接的关键。

```java
// 落点建议：platform/agent-core/src/main/java/cn/studykid/multiagent/core/tenant/SubjectContext.java
public record SubjectContext(
        @NonNull String tenantId,
        @NonNull String principalId,
        @NonNull Set<String> roles,
        @NonNull Scope scope,          // TENANT | PLATFORM  ← 新增
        long authzVersion) {
    public SubjectContext {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId 不能为空");
        if (principalId == null || principalId.isBlank()) throw new IllegalArgumentException("principalId 不能为空");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        if (authzVersion < 0) throw new IllegalArgumentException("authzVersion 不能为负");
    }

    /** 平台运维身份 —— 替代「BYPASSRLS 连接」这一表达。 */
    public boolean isPlatformOps() {
        return scope == Scope.PLATFORM && roles.contains("PLATFORM_ADMIN");
    }
}
```

**纪律**（沿用 `TenantContext` 现有 Javadoc 的不可妥协规则，不新增例外）：
- `tenant_id` **只能**从可信认证上下文推导，客户端传入的不作为授权依据；
- 不可变值对象，创建后不可修改（防请求处理中途被篡改）；
- `authzVersion` 沿用现有语义：撤销授权后递增，用于拒绝持有陈旧上下文的在途请求；
- **无上下文即拒绝**（对应 C7：`TenantSessionBinder.clear()` 的 fail-closed 语义在应用层重建）。
- **`scope` 的可信推导路径（替代「BYPASSRLS 连接」的关键）**：现役「平台运维身份」由**独立数据源 + `PlatformOpsSessionGuard`** 识别（非上下文字段）。迁移后，`scope` 必须由**认证入口**依据「该请求是否走平台运维通道（独立数据源 / 运维路由）」唯一写入；**租户态请求绝不可自设 `scope = PLATFORM`**。写入规则：走运维通道且主体含 `PLATFORM_ADMIN` → `Scope.PLATFORM`；其余 → `Scope.TENANT`。`SubjectContext` 构造后不可变，请求处理中途无法被篡改。此推导点是「平台全域放行」的唯一开关，须用反证钉死（租户请求携带伪造 scope 必须被拒）。

### 2.3 L1｜权限模型层

#### 2.3.1 核心语义对象：ScopeFilter

**这是整套方案的枢纽**。它替换掉「策略谓词」，且**不含任何 SQL 方言**。

```java
// 落点建议：platform/agent-core/src/main/java/cn/studykid/multiagent/core/security/ScopeFilter.java
public sealed interface ScopeFilter {

    /** 本租户私有数据 + 平台公开数据。
     *  等价于现役 SQL：tenant_id IN (rag_current_tenant(), rag_global_tenant_id())。 */
    record TenantWithShared(@NonNull String tenantId) implements ScopeFilter {}

    /** 仅本租户。等价于现役 SQL：tenant_id = current_tenant_id()。 */
    record TenantOnly(@NonNull String tenantId) implements ScopeFilter {}

    /** 平台全域。等价于现役 BYPASSRLS 通道的「不受策略约束」视图。 */
    record Unrestricted(@NonNull String reason) implements ScopeFilter {}

    /** 空集：鉴权通过但无可见范围 → 恒假 → 0 行（fail-closed 的显式表达）。
     *  ⚠️ 必须是 permitted 子类型（不能用匿名类实现 sealed 接口，否则无法编译）。 */
    record None() implements ScopeFilter {}

    /** 便捷常量。switch 中须用类型模式 {@code case ScopeFilter.None n} 满足密封穷尽性。 */
    ScopeFilter NONE = new None();
}
```

**关键性质**：`ScopeFilter` 只有 4 种形态。**新增一种存储 = 实现一个翻译器**，不需要新增一条权限规则。

#### 2.3.2 决策请求与结果

```java
public record AccessRequest(
        @NonNull SubjectContext subject,
        @NonNull Action action,             // READ / WRITE / DELETE / PUBLISH / RETRIEVE
        @NonNull ResourceRef resource) {}

public record ResourceRef(
        @NonNull String type,               // "rag_chunk" | "cap_agent_def" | ...
        @Nullable String id,                // 单资源场景；集合场景为 null
        @NonNull Map<String, Object> attrs) // tenantId / scope / verStatus / trustTier ...

public sealed interface Decision {
    record Permit(@NonNull ScopeFilter filter) implements Decision {}
    record Deny(@NonNull String reasonCode, @NonNull String message) implements Decision {}
}
```

#### 2.3.3 策略规则（声明式，逐条对应现役 RLS 策略）

规则存 `authz_policy` 表或配置中心，带 `policyVersion`（对齐 05 系列的 `authzVersion` 机制）。

```yaml
# 翻译示例：RAG chunk 表「读=租户∪哨兵 / 写=仅租户 / 哨兵桶写入需运维通道」
- id: rag-chunk-read
  resource: rag_chunk
  action: RETRIEVE
  effect: PERMIT
  condition: "subject.scope == 'TENANT'"
  scope: TENANT_WITH_SHARED          # → ScopeFilter.TenantWithShared(subject.tenantId)

- id: rag-chunk-write
  resource: rag_chunk
  action: WRITE
  effect: PERMIT
  condition: "subject.scope == 'TENANT'"
  scope: TENANT_ONLY                 # 租户写不进哨兵桶 —— 等价于现役「写策略不含哨兵」

- id: rag-chunk-write-shared
  resource: rag_chunk
  action: WRITE
  effect: PERMIT
  condition: "subject.isPlatformOps()"
  scope: UNRESTRICTED                # 替代 BYPASSRLS 通道
```

> **不变式（构造期强制）**：`UNRESTRICTED` **只能**由 `isPlatformOps()` 条件产出。`PolicyEngine` 加载规则时校验，非法规则**拒绝加载并拒绝启动**（fail-closed）。这是 §7 R3 的核心防线 —— 不能靠「配置写错」来放大权限。

### 2.4 L2｜鉴权层（AccessGate）—— 入口强制点

```java
public interface AccessGate {
    /** 单资源鉴权：详情/更新/删除路径。Deny 必须显式抛出（fail-closed）。 */
    @NonNull ScopeFilter authorize(@NonNull Action action, @NonNull ResourceRef resource);

    /** 集合鉴权：列表/检索路径。返回的 ScopeFilter 必须被查询强制携带。 */
    @NonNull ScopeFilter authorizeCollection(@NonNull Action action, @NonNull String resourceType);
}
```

**强制性靠 Gate 注入实现，而不是靠约定**：Repository **必须持有 `AccessGate` 引用**（构造期注入），且**每个查询方法在取数前必须调用 Gate 取得 `ScopeFilter`** 并渲染进 SQL。这样即使 Repository 是单例 Spring Bean，过滤范围也来自每次请求的 `SubjectContext`（经 ThreadLocal 传入 Gate），且「无 Gate 无 Repository 实例」的编译期保证仍然成立（Gate 是构造必填参数）。

```java
public final class JdbcChunkRepository implements ChunkRepository {
    private final DataSource dataSource;
    private final AccessGate gate;          // 构造期必填（无 Gate 无实例）

    public JdbcChunkRepository(@NonNull DataSource dataSource, @NonNull AccessGate gate) {
        this.dataSource = dataSource; this.gate = gate;
    }

    /** 集合查询：执行期向 Gate 取 ScopeFilter，渲染为 WHERE 片段。 */
    public List<Chunk> findByDocument(Connection conn, String documentId) {
        ScopeFilter filter = gate.authorizeCollection(Action.RETRIEVE, "rag_chunk");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ... WHERE document_id = ? AND " + SqlScopeTranslator.render(filter))) {
            ps.setString(1, documentId);
            // ... 绑定其余参数并执行
        }
    }
}
```

> ⚠️ **与现有代码形态对齐（评审修订）**：本仓 Repository 实际使用**原始 `DataSource`/`Connection`/`PreparedStatement`**（如 `JdbcStatisticsRepository`、`JdbcRunStepRepository`），**并非** `JdbcClient`/Spring Data。迁移时**不要**引入「构造期冻结 filter」的 `scoped()` 静态工厂——单例 Bean 无法在构造期拿到 per-request 的 `SubjectContext`。正确做法：① 把现有 `TenantSessionBinder.bind(conn, new TenantContext(tenantId,...))` 调用替换为「Gate 取 `ScopeFilter` + `SqlScopeTranslator.render` 注入 SQL」；② 显式 `WHERE tenant_id` 谓词**保留**（它本就是第一道防线），但**来源**从手写字面量统一改为翻译器产物。
> **为什么不用 ThreadLocal 传递 filter**：`TenantSessionBinder` 把租户钉在**连接**上，连接池复用时会串租户（其 Javadoc `:22-29` 已论证该风险）。新方案里 `ScopeFilter` 不进连接、不进 ThreadLocal，而是**每次查询由 Gate 现算并就地渲染**；`SubjectContext` 仍走 ThreadLocal（一请求一事实），但**过滤范围**由 Gate 从 `SubjectContext` 推导，职责分离且结构上不可能串租户。

### 2.5 L3｜数据访问层职责

| 组件 | 现状职责 | 目标职责 |
|---|---|---|
| Repository（platform） | 手写 SQL + 显式 `WHERE tenant_id` + RLS 兜底 | **WHERE 唯一来源 = `ScopeFilter` 翻译结果**；手写的 `tenant_id` 谓词由翻译器统一生成，逐步消除重复 |
| RetrievalPort 实现（rag） | SQL 内嵌 `IN (rag_current_tenant(), rag_global_tenant_id())` | 谓词改为 `SqlScopeTranslator.render(filter)`；**端口契约不变**（`RetrievalPort.java:20-26` 的过滤纪律原样成立） |
| `TenantSessionBinder` | 连接级 GUC 绑定 | 过渡期保留（RLS 还在就必须钉），标 `@Deprecated(forRemoval = true)`；阶段 3 完成后删除 |
| `GuardedPlatformOpsDataSource` | BYPASSRLS 连接闸门 | 形态保留、语义切换：从「绕过 RLS 的连接」→ 「携带 `ScopeFilter.Unrestricted` 的专用客户端」。**未接入即 503、绝不回落**的纪律一字不改 |
| 原始 JDBC 路径（如 `JdbcStatisticsRepository` / `JdbcRunStepRepository`） | 手动 `Connection` + `TenantSessionBinder.bind` + 显式 `WHERE tenant_id` | `bind` 调用改为「`gate.authorizeCollection` 取 `ScopeFilter` → `SqlScopeTranslator.render` 注入 SQL」；`WHERE tenant_id` 保留但来源统一为翻译器 |
| 批量 / 夹具灌入（`rag_ingest_job`、测试夹具） | 走绑定租户的连接或运维通道 | 必须显式携带 `ScopeFilter`（批量 INSERT 的 `tenant_id` 由 Gate 产出，**禁止**从外部 CSV/参数直接灌）；夹具加载走 `Unrestricted` 专用客户端并加反证 |
| **DB 函数 / 触发器对租户 GUC 的依赖** | `rag_current_tenant()` / `rag_global_tenant_id()` / `current_setting('app.tenant_id')` 被策略与函数引用 | **新增交付物 D11 盘点全部依赖对象**：迁移 RLS 后这些函数会返回 NULL/报错，须逐一改为「读配置项哨兵 ID」或删除，并配反证（见 §7.1 R6） |

### 2.6 L4｜审计与证伪层

RLS 时代有一个隐性损失必须提前补：**DB 策略天然「每行过一遍」；应用层过滤可能漏。**

| 机制 | 作用 | 落地形态 |
|---|---|---|
| **决策日志** | 每次 `authorize` 记录 `(subject, action, resourceType, decision, scopeFilter, policyVersion)`；**拒绝必记** | 复用 `platform/agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/security/JdbcPlatformAuditRepository.java` 形态 |
| **结果集染色校验** | Repository 返回前断言「每行 `tenant_id` ∈ filter 允许集合」 | 测试环境 100%、生产采样 1%（确定性分桶 + 高敏 100%，见 §9 Q6）；违例打 `AUTHZ_MISMATCH` 指标 + 告警 |
| **反证测试重建** | 证伪对象从「DB 结构」换成「应用层装配」 | 见 §6.5 |

---

## 3. 迁移路径：从 PG RLS 平滑迁移到应用层

### 3.1 总原则

> **双写期 RLS 一条策略都不许动。**
> 应用层过滤**全部加在 RLS 之内**（取交集），保证任何时刻安全语义**只会变紧、不会变松**。

这直接回应 `问题记录/03-数据库权限与RLS.md` 的既有教训：「RLS 漏写策略等于多了一道闸门」「顺序一换，语义就变」。

### 3.2 六阶段路线

| 阶段 | 周期 | 做什么 | 出口条件 |
|---|---|---|---|
| **P0 准备** | 2 周 | ① 从**两个运行库各导一份**翻译对照表（D1：`agent_dev` 平台域 + `study_rag` RAG 域，**勿跨库混用基线**）；② **核验**平台侧启动自检 `PlatformStartupSelfCheck`（已实装，拒绝 BYPASSRLS/超级用户 + 运维角色互斥），补 Rag 侧对称性；③ 搭 L0/L1/L2 骨架（不接任何 Repository）；④ 新增 GUC 依赖盘点（D11）、非 Repository 面矩阵（D12）、JOIN 清单（D13） | 两份对照表评审通过且 `meta.source` 分别指向实际运行库、`policy_count` 与活库一致；`UNKNOWN = 0`；Gate 可独立单测；**主数据源确为 `app_agent`（NOBYPASSRLS，已由 `PlatformStartupSelfCheck` 保证）** |
| **P1 影子** | 2–3 周 | Repository 双通道：查询仍走原 SQL + RLS；**同时** Gate 算 `ScopeFilter` 并与实际 SQL 谓词**离线比对**（只记日志不影响请求）；结果集染色校验上线 | 影子期 ≥2 周（含全量 IT 全绿）`AUTHZ_MISMATCH = 0`。**任何 mismatch 都是翻译表错，修翻译表而非修代码** |
| **P2 双写** | 3–4 周 | Repository 的 SQL 切换为「显式 WHERE = 翻译结果」；**RLS 策略原样保留**（两层取交集）；决策日志上线 | 全量 IT 绿 + **两份反证同时通过**（旧的「删 RLS → 红」仍成立、新的「绕 Gate → 红」已建立）+ 性能基线达标（§7.2） |
| **P3 逐表退役** | 4–8 周 | 按域分批 `DROP POLICY` → 观察 → `DISABLE ROW LEVEL SECURITY`（见 §3.3 批次表） | 每批：跨租户用例**仍红**（现在靠应用层拦）+ 回滚演练通过 |
| **P4 收口** | 持续 | 策略规则进配置中心 + 双人复核（对齐现有「申请→审批→执行」纪律）；`SchemaStructureTest` 断言对象从 `pg_catalog` 迁到「应用层防线注册表」 | 新增表的纪律**反转**：默认无 RLS，安全评审检查「是否注册策略规则 + Repository 是否走 scoped 工厂」 |
| **P5 清理** | P3 后一个季度 | 删 `TenantSessionBinder`（独立 PR + 全量回归）；策略 DDL 定义才从代码库清理 | — |

> ⚠️ **P0 的前置依赖（已满足，启动前核验）**：历史上主数据源曾用带 BYPASSRLS 的 `ai_agent` 角色（`审查文档/护栏设计综合评估文档1.md:113`），导致 RLS 在主链路失效。该问题已于 **2026-10-30** 闭环——`platform.env` 主数据源已切到 `app_agent`（NOBYPASSRLS），且 `PlatformStartupSelfCheck` 已在启动时拒绝 BYPASSRLS/超级用户角色与运维角色复用。⇒ 本前置**已满足**，P0 只需**核验**而非建设；P1 影子验证的前提已具备。

> 🔴 **P0 的第二硬前置：翻译基线必须绑定到「目标环境的实际策略形态」，且一个库一份**。
>
> **2026-11-02 直连 RDS 两活库实测结论（已推翻本文早前的「形态不一致」论断）**：
>
> | 库 | 业务表 | 策略数 | 分类分布 | 读写不对称 | 缺口 |
> |---|---|---|---|---|---|
> | `agent_dev`（应用主数据源） | 65 | **50** | `TENANT_ONLY` 50 / 其它 0 | 4 张表（`cap_config_pointer`、`cap_config_publish_request`、`cap_config_release`、`cap_decision_log`，各 SELECT/INSERT/UPDATE 三条） | 9 张（全为 `platform.*` 指标/配额域 + `public.tenant`，按设计属平台域，非漏写） |
> | `study_rag`（RAG 数据源） | 14 | **37** | `TENANT_ONLY` 29 + `TENANT_WITH_SHARED` **8** | 8 张（读含哨兵桶、写不含） | 无 |
>
> **与 DDL 快照逐条比对 → 零真实漂移**：策略名集合完全一致（`platform` 50/50；`rag` 37/37）。
> `deploy/sql/rag` 里 39 条 `CREATE POLICY` 多出的 2 条是 `06_v1_0_2_s3_alter.sql`
> 对 `rag_inspection_tenant_isolation` / `rag_kp_alias_tenant_isolation` 的 `DROP+CREATE` 幂等重复，
> **不是新增策略**（该文件属旧「增量 patch」纪律遗留；现行纪律是「改 01 快照 + 活库等价 ALTER」）。
>
> ⚠️ **据此校正一条此前写错的判断**：早前记录「`agent_dev` 只有 10 条单策略、须先对齐到 35 条读写分策略」**不成立**。
> 35 条那组来自 `rag_base`——它是 **RAG 域的陈旧副本**（比 `study_rag` 少 2 条），
> 从来不是 `agent_dev` 的一个「形态」。把它当作 `agent_dev` 的基线属**跨库错配**：
> 比的不再是「同一批策略翻译得对不对」，而是「两个不同数据库的策略对不对得上」——必然全量 mismatch。
>
> ⇒ **D1 的正确形态是「一库一基线」**：
> `authz/policy-mapping.yaml`（`agent_dev`，50 条）+ `authz/policy-mapping-rag.yaml`（`study_rag`，37 条）。
> 旧文件已归档为 `authz/policy-mapping.legacy-rag_base.yaml` 留痕。
> 详见 [DBP-18](../../问题记录/03-数据库权限与RLS.md)。
>
> **P0 出口条件**（已满足，需评审确认）：两份对照表 `meta.source` 分别指向实际运行库、`policy_count` 与活库一致、分类中 `UNKNOWN = 0`、缺口清单结论已人工判定。

### 3.3 P3 退役批次（按风险从低到高）

| 批次 | 表域 | 退役动作 | 验证 |
|---|---|---|---|
| **B1** | 只增日志类（如 `cap_decision_log` —— 见 `问题记录/03-数据库权限与RLS.md` DBP-03：只增表需 BYPASSRLS 才能清理） | `DROP POLICY` → 观察 1 周 → `DISABLE ROW LEVEL SECURITY` | 该域 IT + 跨租户用例仍红 |
| **B2** | platform 业务表（`run` / `agent_invocation` / `cap_*` 等，42 表中 36 张带 FORCE） | 同上 | 同上 + `MultiAgentSchemaIT` 断言改造（从「RLS enabled 且 FORCE」改为「应用层防线存在性」） |
| **B3** | RAG 语料表（13 表，含哨兵模型，最复杂） | 同上 | 语料 scope 反证脚本（`scripts/rag-corpus-scope-counterproof.sh`）改造后全绿 |
| **B4** | 平台目录表 + 运维通道 | `GuardedPlatformOpsDataSource` 语义切换 | 通道闸门测试（未配置即 503、绝不回落）不变 |

### 3.4 回滚设计

| 阶段 | 回滚方式 | 数据风险 |
|---|---|---|
| P0–P2 | 关影子/双写开关，RLS 原样兜底 | 零 |
| P3（每批） | **策略 DDL 全程保留在迁移脚本**（只加 `DROP`，不删定义），回滚 = 重放该表策略 DDL + 开关切回 | 零（策略 DDL 不动数据） |
| P4+ | — | — |

> **补充（应用层 bug 致漏过滤不在 DDL 回滚覆盖内）**：D8 的 DDL 回滚只重放策略、不动代码；若漏过滤源于应用层 bug，需**代码回滚**。⇒ 每批出口须额外包含「应用层回滚开关 / 旧版 Repository 实现并行保留一个迭代」机制，确保可在不重放 DDL 的情况下回退到 RLS 兜底态。
> **纪律**：退役完成且稳定运行一个季度前，**不许删除任何一条策略 DDL 定义**。

---

## 4. 数据库无关性设计

### 4.1 枢纽：ScopeFilter → 存储方言翻译器 SPI

```java
public interface ScopeTranslator<Q> {
    @NonNull Q render(@NonNull ScopeFilter filter);
}

// 关系型（JDBC/SQL 方言）
public final class SqlScopeTranslator implements ScopeTranslator<SqlFragment> {
    public SqlFragment render(ScopeFilter f) {
        return switch (f) {
            case ScopeFilter.TenantOnly t ->
                new SqlFragment("tenant_id = :__scopeTenant",
                                Map.of("__scopeTenant", t.tenantId()));
            case ScopeFilter.TenantWithShared t ->
                // 等价于现役 IN (rag_current_tenant(), rag_global_tenant_id())
                // 但哨兵值是**配置项**，不再是 PG 函数
                new SqlFragment("tenant_id IN (:__scopeTenant, :__scopeShared)",
                                Map.of("__scopeTenant", t.tenantId(),
                                       "__scopeShared", sharedTenantId));
            case ScopeFilter.Unrestricted u -> SqlFragment.TRUE;
            case ScopeFilter.None n -> SqlFragment.FALSE;   // 恒假 → 0 行，fail-closed
        };
    }
}
```

### 4.2 后端适配矩阵

| 后端 | 适配方式 | 备注 |
|---|---|---|
| PostgreSQL | `SqlScopeTranslator`（PG 方言，`uuid` 强转） | 迁移首站 |
| MySQL / 其他 RDBMS | 同接口，差异仅在类型转换与分页 | **权限层零改动** |
| pgvector（现状） | 向量列与 `tenant_id` 同表，`AND` 拼接 | 现状形态 |
| Milvus / Qdrant / Weaviate | `ScopeFilter` → **collection/partition 路由 + metadata filter 表达式** | 见 §4.3 |
| Elasticsearch | `ScopeFilter` → `terms` filter 嵌入 `bool` query | |
| Redis / 对象存储 | `ScopeFilter` → **key 前缀路由**（`t-x:chunk:{id}`）；`NONE` → 拒绝生成 key | |

### 4.3 向量库适配（最现实的迁移目标）

`RetrievalPort` 的现有契约（「端口暴露检索能力而非向量库 API」「过滤必须在返回前应用」，`RetrievalPort.java:20-26`）**已经是正确方向**，缺的只是把过滤谓词从 SQL 文本里提出来。

```java
public final class MilvusRetrievalAdapter implements RetrievalPort {
    private final MilvusClient client;
    private final ScopeFilter filter;              // 构造期由 Gate 固化（同 Repository 模式）
    private final ScopeTranslator<Expr> translator = new MilvusScopeTranslator();

    @Override
    public List<ScoredChunk> search(RetrievalRequest request) {
        SearchReq req = SearchReq.builder()
            .collectionName("rag_chunk")
            .annsField("embedding")
            .data(List.of(embedding(request.query())))
            // 权限谓词与业务谓词（ver_status / editionScope）同为 metadata filter
            .expr(translator.render(filter).and(businessPredicate(request)).toMilvusExpr())
            .topK(request.topK())
            .build();
        // 返回前的契约义务不变：RetrievalPolicy 的版本/角色过滤（RetrievalPort.java:20-26）
        return policyFilter.apply(request.policy(), toScored(client.search(req)));
    }
}
```

**四条要点**：

1. **过滤下推，不事后过滤**。向量库普遍支持标量+向量混合检索（metadata filtering），`tenant_id` 就是标量字段。**事后过滤（先 topK 再筛租户）是禁止项** —— 既泄露（越权数据进内存）又破坏分数语义（与 `RetrievalPort.java:34-38` 论证的「top-1 恒为满分、门禁形同虚设」是同款问题）。
2. **哨兵模型可移植**：`TenantWithShared` 渲染为 `tenant_id in ["<本租户>", "<哨兵>"]`。哨兵值从 PG 函数（`rag_global_tenant_id()`）变成**配置项**；写路径的策略规则保证「只有 `isPlatformOps()` 主体能写哨兵桶」—— 语义不变，实现位置变。
3. **分区键是白拿的收益**：租户数据量大时，`tenant_id` 做 Milvus partition key ⇒ `TenantOnly` 直接路由单分区、`TenantWithShared` 路由两分区。**权限边界顺带变成查询加速手段** —— 这是 RLS 时代做不到的。
4. **向量记录的标量完整性**：每条向量记录必须冗余 `tenant_id` / `ver_status` / `edition_id` 等过滤所需标量字段。**权限谓词只依赖这些冗余标量，不依赖跨库 JOIN** ⇒ 权限判定本身没有分布式一致性问题（见 §7.3 C2）。

### 4.4 「数据库无关」的边界（诚实声明）

- **不要求** SQL 语法 100% 可移植（分页 / JSON 操作允许方言差异）；要求的是**权限语义与方言解耦** —— `ScopeFilter` 只有 4 种形态，新增存储只需实现 1 个 `ScopeTranslator`。
- **事务语义不抽象**：跨库事务（语料写 PG + 向量写 Milvus）走现有 Outbox/Job 模式（`rag_ingest_job` 的 `PARSING → CHUNKING → EMBEDDING → INDEXING` 状态机已是此形态）；**权限层不参与分布式事务**。
- **兜底是可选项**：迁到无 RLS 概念的库后，数据库层兜底退化为「集合级 ACL」，在部署清单里声明即可 —— 架构不依赖它存在。

### 4.5 多表 JOIN 与跨租户引用（迁移新增的攻击面）

RLS 的隐性收益是**对查询树中每一张表**都注入 `tenant_id` 谓词——包括 JOIN 的从表、子查询、CTE。应用层 `ScopeFilter` 默认只在**主表**渲染 `WHERE tenant_id = ?`。这是迁移引入的**新型泄漏面**，需专门处置：

1. **JOIN 从表必须显式补谓词**：`FROM run r JOIN agent_invocation a ON a.run_id = r.id` 须写成 `… AND a.tenant_id = ?`（参数同源）。漏写时 RLS 时代被兜底，迁移后越权。
2. **盘点即清单（D13）**：P0 阶段穷举所有跨租户 JOIN，逐条登记「主表 filter / 从表是否已补 / 反证方法」，纳入 ArchUnit 或集成测试。
3. **染色校验扩展到 JOIN 路径**：结果集染色不仅断言主表行 `tenant_id ∈ filter`，也须断言 JOIN 返回行中任一租户表字段 ∈ 允许集合（§7.1 R7）。
4. **关键表保留 RLS 作 JOIN 兜底**：对高频 JOIN 的高敏表，混合终态（§9 Q1）下 RLS 不退役，天然覆盖 JOIN 从表。
5. **跨租户引用完整性（§7.3 C5）**：移除 RLS 后，外键不阻止「子行指向他租户父行」，须靠应用层保证所有 FK 推导源自租户内查询，并用反证固定。

> **D13 实测校正（2026-11-02）**：对 1 条规则做了修正，并补了一条否定性结论。
>
> - **规则 1 的正确写法是「复合键等值」而非「同源参数」**：`ON a.tenant_id = r.tenant_id` 比
>   `AND a.tenant_id = ?` 更稳 —— 前者在主表被换绑定时自动跟随，后者需要保证两处参数同步改，
>   是标准的「改一处漏一处」入口。本仓 15 处 platform JOIN 中 13 处已是复合键形态。
> - **「到处都漏写」的直觉不成立**：实测只有 **1 处**真缺口（`JdbcTraceFactRepository` 的
>   `tool_operation ⋈ tool_operation_attempt` 单键 JOIN，且主表也只有 `run_id`）。
>   代价不是「多返回几行」而是**指标口径污染**：他租户的 attempt 会计入本租户的失败率/超时率，
>   既不报错也不越权可见，比主表泄漏更难发现。
> - **平台目录表（`platform.*`）是合法豁免**：无 RLS、无 `tenant_id` 列，访问由
>   `GuardedPlatformOpsDataSource` 通道闸门约束（未配置即 503）。清单必须写明豁免理由，
>   否则下一次评审会把它当成漏项反复返工。
> - **RAG 侧 24 处 JOIN 全部是复合键**：其成立前提是「全局语料用哨兵租户而非 NULL」——
>   一旦改成可空表示法，这些 JOIN 会全部连不上（实测 0 行）。

---

## 5. 关键代码落点清单

| 符号 | 落点建议 | 说明 |
|---|---|---|
| `SubjectContext` | `platform/agent-core/.../core/tenant/SubjectContext.java` | 与 `TenantContext` 同包；`TenantContext` 保留并适配为「租户域视图」 |
| `ScopeFilter` / `AccessRequest` / `ResourceRef` / `Decision` | `platform/agent-core/.../core/security/` | 纯领域，零基础设施依赖 |
| `AccessGate` / `PolicyEngine` / `PolicyStore` | `platform/agent-infrastructure/.../security/` | 规则加载 + 决策 + 缓存 |
| `SqlScopeTranslator` | `platform/agent-infrastructure/.../persistence/scope/` | 方言 SPI 实现 |
| `ScopeTranslator` SPI | `platform/agent-core/.../core/security/`（接口） | 各存储适配器实现 |
| Repository scoped 工厂 | 各现有 Repository | 逐域改造（P1–P2） |
| `GuardedPlatformOpsDataSource` 语义切换 | 原地改造 | 通道纪律不变 |
| `MilvusScopeTranslator` / `MilvusRetrievalAdapter` | `rag/rag-infrastructure/.../vector/` | 目标向量库验证性适配器 |

---

## 6. 交付物清单

| # | 交付物 | 落点 | 状态 | 阶段 |
|---|---|---|---|---|
| **D1** | RLS 策略导出脚本 + 翻译对照表 | `scripts/export-rls-policies.sh` → `authz/policy-mapping.yaml`（`agent_dev`）+ `authz/policy-mapping-rag.yaml`（`study_rag`） | ✅ **已完成（2026-11-02，直连 RDS 重生成）**：`agent_dev` 50 条（全 `TENANT_ONLY`，`UNKNOWN=0`，9 张平台域表无策略属正常）；`study_rag` 37 条（29 `TENANT_ONLY` + 8 `TENANT_WITH_SHARED`，无缺口）。与 DDL 快照策略名逐一比对**零漂移**；脚本已加「远程活库」模式（`PG_URI`） | P0 |
| **D2** | `SubjectContext` / `ScopeFilter` / `Decision` 领域模型 | `platform/agent-core`（`core/security/` + `core/tenant/SubjectContext`） | ✅ 已实施（代码 + 单测，2026-11-02） | P0 |
| **D3** | `AccessGate` + `PolicyEngine` + 规则加载器 + `SubjectContextResolver` | `platform/agent-infrastructure/.../security/` | ✅ 已实施（代码 + 单测，含 R3 不变式与 fail-closed，2026-11-02） | P0–P1 |
| **D4** | `SqlScopeTranslator` + `ScopeTranslator` SPI | `platform/agent-infrastructure/.../persistence/scope/` | ✅ 已实施（随 D3 一并落地，2026-11-02） | P1 |
| **D5** | Repository scoped 改造（逐域，Gate 执行期取 filter） | 各 Repository | 🟡 **部分实施**：`ScopedDataAccess` 接缝（含显式主体重载）+ `AuthzShadowHooks` 统一静态入口；已接线 **5** 仓（`JdbcAgentMetricRepository` / `JdbcAgentQueryRepository` / `JdbcBudgetRepository` / `JdbcDelegationBudgetRepository` / `JdbcResultQueryRepository`），全部 **dormant 默认关闭、零行为变更**，相关单测 35 例绿（2026-11-02） | P1–P2 |
| **D6** | 决策日志 + 结果集染色校验 + `AUTHZ_MISMATCH` 指标 | 复用 `JdbcPlatformAuditRepository` 形态 | 🟡 **部分实施**：`RowAttribution`/`TaintVerifier`/`TaintReport` + `AuthzMismatchMetrics`(+内存/SLF4J 实现) + `AuthzShadowReporter`（`blockOnViolation` 开关待 P2 翻转）+ `AuthzDecisionRecorder`(+NoOp/内存/**JDBC 决策日志**) 接缝，单测 19 例绿（2026-11-02）；`platform.authz_decision_log` DDL 与生产装配待 P2 | P1–P2 |
| **D7** | 反证测试改造（绕 Gate → 红）+ ArchUnit 规则 | `scripts/authz-bypass-counterproof.sh` + ArchUnit | ✅ **已实施（改自研扫描，不引新依赖）**：`ScopeBypassArchitectureTest`（`agent-infrastructure`），4 条静态不变量 + 3 条扫描器自证；**上线首跑即抓到 `JdbcAgentMetricRepository` 自建钩子旁路并已统一到 `AuthzShadowHooks`**（2026-11-02） | P2 |
| **D8** | 逐表退役迁移脚本（DDL 保留可回滚） | `deploy/sql/**/V*_drop_rls_policy.sql` | ⬜ 未开始 | P3 |
| **D9** | 目标向量库适配器（Milvus 等） | `rag/rag-infrastructure/.../vector/` | ⬜ 未开始 | P3–P4（可与退役并行） |
| **D10** | `TenantSessionBinder` 删除 + 平台侧启动自检补齐 | `platform/agent-infrastructure/.../persistence/` | ⬜ 未开始 | P3–P5 |
| **D11** | **GUC 依赖盘点**（新增） | `docs/权限管控体系/authz/guc-dependency.md` | ✅ 已生成（P0 基线，2026-11-02） | P0 |
| **D12** | **非 Repository 面矩阵**（新增：原始 JDBC / 批量 / 夹具） | `docs/权限管控体系/authz/non-repo-surface.md` | ✅ 已生成（P0 基线，2026-11-02） | P0 |
| **D13** | **跨租户 JOIN 清单**（新增） | `docs/权限管控体系/authz/join-inventory.md` | ✅ 已生成（P0 基线，2026-11-02） | P0 |

### 6.1 D1 使用说明

```bash
# 生成翻译对照表（基线）
PG_CONTAINER=agent-pg17 PG_USER=agent PG_DB=<目标环境库> DDL_DIR=deploy/sql \
  ./scripts/export-rls-policies.sh

# 输出三份结果
#   docs/权限管控体系/authz/policy-mapping.yaml   ← 对照表（翻译基线）
#   缺口清单    —— 带 tenant_id 却无策略的表（须判定：平台域表 or 策略漏写）
#   读写不对称  —— 同表读 scope ≠ 写 scope（合并成一个 ScopeFilter = 越权 ⛔）
#   漂移告警    —— 活库策略数 vs DDL 脚本策略数不一致（基线可能取错库 ⛔）
```

> ⚠️ **基线必须绑定环境**：对照表 `meta.source` 记录来源库。
> 不同库的策略形态可能不同（见 §3.2 的 DBP-18 告警），**基线取错库会让 P1 影子全量 mismatch 且根因误判**。


---

## 7. 风险与权衡

### 7.1 数据安全风险（最高优先级）

| ID | 风险 | 场景 | 应对 |
|---|---|---|---|
| **R1** | **过滤遗漏** | 新增 Repository 方法忘走 scoped 工厂 / 手写 SQL 漏拼 filter | ① **类型系统防线**：无 Gate 无实例（§2.4）；② **结果集染色**：测试环境 100% 逐行断言 `tenant_id ∈ filter`；③ **ArchUnit**：`*Repository` 构造器必须私有、工厂必须调 Gate |
| **R2** | **双写期语义分叉** | 应用层 filter 与 RLS 谓词不一致，双写期互相掩盖错误 | 影子期（P1）强制 ≥2 周 `AUTHZ_MISMATCH = 0`；翻译表评审制；**翻译错误的修复方向是修翻译表，绝不修代码去迁就** |
| **R3** | **运维通道退化** | `Unrestricted` 范围误发给非平台主体 | ① 策略规则加载期校验：`UNRESTRICTED` 只能由 `isPlatformOps()` 产出，否则**拒绝启动**（fail-closed）；② 通道闸门纪律不变（未配置凭据 → 503，绝不回落，对齐 `问题记录` C34-2 记录）；③ 决策日志对 `Unrestricted` 单独高亮告警 |
| **R4** | **退役期回滚不完整** | 某表退役后应用层有 bug，需回滚 | 策略 DDL 全量保留于迁移脚本（只加 DROP 不删定义），回滚 = 重放 + 开关切回；**回滚演练纳入每批出口条件** |
| **R5** | **fail-closed 语义丢失** | 应用层 NPE / 配置缺失时「查不到 filter」变成「不过滤」 | `ScopeFilter` 无 null 路径：Gate 拒绝必须抛异常、filter 缺失构造失败、`NONE` 渲染恒假。三条用反证测试固定（注入「抛异常的 PolicyEngine」→ 请求必须 500 而非放行） |
| **R6** | **GUC 依赖对象失效** | 迁移 RLS 后，`rag_current_tenant()` / `rag_global_tenant_id()` / `current_setting('app.tenant_id')` 等函数/触发器返回 NULL 或报错，导致静默 0 行或异常 | ① **D11 盘点全部依赖对象**；② 逐一改为「读配置项哨兵 ID」或删除；③ 反证：删 GUC 绑定后依赖对象须按预期报错/返回哨兵，而非静默 NULL |
| **R7** | **JOIN 多表租户泄漏** | RLS 会对每个连接表自动施加 `tenant_id` 谓词；应用层 `ScopeFilter` 只在主表生成 `WHERE`，JOIN 到次级租户表若漏写谓词即越权 | ① **D13 盘点所有跨租户 JOIN** 并逐条补 filter；② 染色校验扩展到 JOIN 路径返回行；③ 高敏接口 100% 染色（§9 Q6）；④ 关键表保留 RLS 作 JOIN 兜底（Q1） |
| **R8** | **非 Repository 面绕过** | 原始 JDBC / 批量灌入 / 夹具加载若未接 Gate，直接写死 `tenant_id` 或走运维连接，迁移后成为无 `ScopeFilter` 的盲区 | **D12 非 Repository 面矩阵**：每类明确「改造为 Gate 取 filter」或「显式豁免 + 反证」；批量/夹具的 `tenant_id` 必须来自 Gate 产出，禁止外部参数直灌 |

### 7.2 性能风险

| ID | 项 | 评估 | 应对 |
|---|---|---|---|
| **P1** | 谓词下推等价性 | 应用层生成的 `WHERE tenant_id = ?` 与 RLS **在优化器眼中完全等价**（RLS 本身就是把谓词注入查询树） | PG 侧无性能回归；迁移前后对 Top-20 慢查询 `EXPLAIN` 对比，纳入 P2 出口条件 |
| **P2** | 鉴权开销 | 每次请求一次 Gate 调用（内存规则匹配），微秒级 | 决策缓存键 = `(authzVersion, tenantId, roles, action, resourceType)`；随 `authzVersion` bump 失效（对齐 05 系列的撤权机制） |
| **P3** | 结果集染色成本 | 列表大结果集逐行校验有开销 | 生产采样 1%（确定性分桶 + 高敏 100%）、测试 100%；采样按**整次查询**而非逐行，染色只做 `tenant_id` 集合归属判断，O(1) |
| **P4** | 失去 RLS 的免费行级优化 | PG 能用 RLS 谓词做分区裁剪 | `ScopeFilter` 渲染的谓词在 WHERE 中位置/参数化方式与 RLS 注入等价；向量库场景**反而获益**（partition 路由，§4.3） |
| **P5** | 连接模型简化收益 | 终态不再需要每事务 `set_config`（省一次 RTT），连接池不再需防串租户强制重置 | 迁移完成后实测连接获取耗时，预期小幅改善 |
| **P6** | 「与 RLS 优化器等价」声明过宽 | §7.2 P1 仅在单表等值成立；JOIN / 子查询 / 分区裁剪处不等价 | P2 出口的 `EXPLAIN` 对比扩展到 JOIN 重查询与分区表，且对比项纳入每批出口条件，而非仅 Top-20 慢查询 |

### 7.3 一致性风险

| ID | 风险 | 场景 | 应对 |
|---|---|---|---|
| **C1** | 权限变更生效延迟 | RLS 改策略是 DDL 立即生效；应用层规则有缓存 | `authzVersion` 单调递增，规则变更 → bump → 缓存失效；紧急封禁提供主动失效接口；决策日志记 `policyVersion`，保证可审计「当时按哪版规则判的」 |
| **C2** | 跨存储权限不一致 | 语料在 PG、向量在 Milvus，chunk 删除后向量残留 | **权限层不治这个病**（属数据一致性问题）。但要求：向量记录的标量字段以 PG 为源（`rag_ingest_job` 状态机灌入），**权限谓词只依赖冗余标量、不依赖跨库 JOIN** ⇒ 权限判定本身无分布式一致性问题 |
| **C3** | 哨兵桶写入权 | 现役靠「写策略不含哨兵 + CHECK 同向约束」双保险（`deploy/sql/rag/01_rag_ddl.sql:353`） | 应用层：写路径策略规则（租户 → `TENANT_ONLY`）+ 领域服务校验（`scope` 与 `tenant_id` 同向的不变式从 DB CHECK 上移到领域对象构造器）。**DB CHECK 建议保留** —— 它属数据完整性约束，不属权限耦合，换库时本来就要重建 |
| **C4** | 测试基建重建期盲区 | `MultiAgentSchemaIT` 等断言 RLS 存在的测试随退役失效 | 改造**先行**（P2 内完成）：断言对象改为「策略规则注册表 + scoped 工厂装配」；反证脚本改造（删 RLS → 红 改为 绕 Gate → 红）必须在对应批次退役**之前**完成 —— 顺序反了就是盲区 |
| **C5** | 跨租户引用完整性 | 移除 RLS 后外键不阻止「插入指向他租户父行的子行」（现状靠 RLS 兜底） | 确认所有 FK 推导都源自租户内查询；迁移后用反证固定（企图插他租户父行必须失败） |
| **C6** | 无租户身份操作失效 | 调度/扫描类（`RunRecoverySweeper` 枚举活跃租户）需「跨全部租户」语义，误判为 `NONE → 0 行` 会令扫描失效 | 明确归类为 `Unrestricted`（仅 `isPlatformOps()`），加反证；`TenantSessionBinder.clear()` 场景改为显式 `Unrestricted` 客户端 |

### 7.4 根本权衡（需决策层拍板）

| 维度 | RLS 方案 | 应用层方案 | 裁决建议 |
|---|---|---|---|
| **绕过难度** | 极高（需 DB 层权限） | 较低（应用层 bug 即可能绕过） | 用类型系统 + 染色 + 反证三层补偿；**接受「工程纪律替代机制保证」** |
| **可移植性** | 零 | 高（新增存储 = 1 个 Translator） | 迁移诉求本身就是为了这个 |
| **可观测性** | 差（拒绝表现为「0 行」，无审计） | 好（每次决策可记录、可解释） | 净收益 |
| **表达能力** | 受限于 SQL 谓词 | 任意复杂（ABAC、时间窗、额度联动） | 净收益 —— 平台侧「治理票据」「配额」本来就在应用层 |
| **深度防御** | DB 是独立防线 | 退役后只剩应用层 | **缓解：关键表可保留 RLS 不退役**（混合终态合法，见下） |

> **混合终态是合法选项**：目标不是「RLS 必须清零」，而是「权限的唯一权威在应用层」。对 `principal`（口令散列）、`cap_decision_log`（审计）等最高敏感表，可在 P3 选择**保留 RLS 作为第二道防线**（两层取交集），代价是这些表换库时需特殊处理。建议默认全退役、按评审白名单保留。

---

## 8. 与既有纪律的衔接（迁移不破坏既有资产）

| 既有纪律 | 出处 | 在新架构中的落法 |
|---|---|---|
| **「未接入即 503，绝不回落」**（平台运维通道） | `scripts/doc-c34-2.py:124` | 平移为 `Unrestricted` 范围语义；原测试断言**一字不改**可复用 |
| **「申请 → 审批 → 执行」三步发布** | `问题记录/08-治理与发布流程.md` | 策略规则变更走同一套治理流 |
| **反证方法论**（改动后结论不变即判无效反证） | `scripts/c27-1-counterproof.sh` | 方法论原样迁移，**证伪对象**从 DB 结构换成应用层装配 |
| **fail-closed**（空 GUC → 0 行） | DBP-08 | 三重重建：Gate 无上下文即拒绝、`NONE` 渲染恒假、染色违例即告警 |
| **「返回前完成过滤」** | `RetrievalPort.java:20-26` | 从注释纪律升级为**装配纪律**（无 Gate 无 Adapter 实例） |
| **客户端 tenant_id 不作为授权依据** | `TenantContext.java:11-20` | 原样保留，扩到 `SubjectContext` |

---

## 9. 待决事项（评审时需拍板）

| # | 事项 | 选项 | 建议 |
|---|---|---|---|
| **Q1** | 是否保留混合终态（关键表留 RLS） | 全退役 / 白名单保留 / 全保留 | **已决议：默认白名单保留**（高敏表 `principal`、审计表、`cap_decision_log` 等保留 RLS 作第二道防线，属混合终态合法项；P3 退役白名单见 §3.3 备注） |
| **Q2** | 策略规则存储位置 | `authz_policy` 表 / 配置中心 / 两者（表为源、配置为缓存） | **表为源 + 配置中心分发**，走现有发布治理流 |
| **Q3** | 哨兵租户 ID 的表达 | 配置项 / 保留 PG 函数 + 应用层缓存 | **配置项**（PG 函数随退役删除） |
| **Q4** | 目标向量库选型 | Milvus / Qdrant / 暂不落地 | 先做 **SPI + 验证性适配器**，选型延后 |
| **Q5** | P0 是否必须先切 `app_agent` | 是 / 否 | **已满足**（2026-10-30 已切 `app_agent` + `PlatformStartupSelfCheck` 兜底；P0 改为核验） |
| **Q6** | 染色校验生产采样率 | 0.1% / 1% / 100% | **默认 1%**（初版写 0.1%，实施时上调，见下），**导出 / 检索 / 统计 / 跨租户读** 等高敏接口 **100%** 染色 + 异常告警（见 §7.1 R7） |

> **采样率的实施结论（2026-11-02）**：0.1% 与「随机丢样本」是两处独立的坑。
> ① 纯随机**不可复现** —— mismatch 复现不出来就无法定位；② 0.1% 在「(主体, 资源) × 300s 一桶」
> 的确定性采样下，单个组合平均约 100 小时才被采到一次，两个月观察期里小流量租户可能
> **一次都没被看过**，读数却仍是 `AUTHZ_MISMATCH=0`。
> 故实现为 `AuthzSamplingPolicy`：确定性分桶（同窗口结论恒定）+ 窗口滚动（长期全覆盖）
> + 高敏清单 100%，默认 `rate=0.01`。
> ⚠️ 采样只决定「**整次查询要不要看**」；被采到的查询**逐行染色**，违例不会被二次采样丢掉。

---

## 10. 关联文档

**同系列**
- [docs/权限管控体系/README.md](README.md) —— 本系列索引与两条线分工
- [05_分层授权决策规范.md](05_分层授权决策规范.md) —— 能力层（Agent/Skill/Tool/MCP）授权决策规范；本文是数据层规范，两者因子不重叠
- [08_安全测试与验收矩阵.md](08_安全测试与验收矩阵.md) —— 数据层反证矩阵的**扩展位**（P2 建立）
- [09_需求追踪矩阵.md](09_需求追踪矩阵.md) —— 数据层需求的追踪位（P0 建立）

**本方案产物**
- [authz/policy-mapping.yaml](authz/policy-mapping.yaml) —— 翻译对照表基线（D1，由 `scripts/export-rls-policies.sh` 生成）
- `scripts/export-rls-policies.sh` —— 策略导出与分类工具（D1）

**问题与现状**
- [问题记录/03-数据库权限与RLS.md](../../问题记录/03-数据库权限与RLS.md) —— RLS 类问题全集（DBP-01～**DBP-18**）；迁移期间的新问题登记到此（标 **[数据层迁移]** + 阶段）
  - ⭐ **DBP-18** —— 活库 RLS 策略与 DDL 漂移（`agent_dev` 仍单策略旧形态）：**P0 基线取错库的风险来源**
- [问题记录/02-数据一致性与写链.md](../../问题记录/02-数据一致性与写链.md) —— 切 `app_agent` 后的授权面变化记录
- [审查文档/护栏设计综合评估文档1.md](../../审查文档/护栏设计综合评估文档1.md) —— 现状评估基线（租户隔离「双层 RLS + 绑定 + 构建门禁」的原始描述）
- [docs/数据库用户与权限梳理_2026-11-01.md](../数据库用户与权限梳理_2026-11-01.md) —— 三角色模型与授权现状

---

## 11. 修订记录（评审意见落实）

> 本节登记对该方案做全面 review 后落实的修订，确保与代码现状、编译正确性与场景覆盖保持一致。对应落地排期见配套文档《13_RLS到应用层权限迁移_设计与实施计划.md》。

| # | 修订项 | 类型 | 落实位置 | 说明 |
|---|---|---|---|---|
| 1 | `ScopeFilter.NONE` 由匿名类改为 permitted `record None()` | **编译缺陷修复** | §2.3.1 / §4.1 | 密封接口不能由匿名类实现，原写法无法编译；`switch` 改 `case ScopeFilter.None n` |
| 2 | `AccessGate` 取 filter 改为**执行期**模式，与单例 Bean / 原始 JDBC 对齐 | **设计缺陷修复** | §2.4 | 原「构造期冻结 filter」的 `scoped()` 工厂与单例 Repository、原始 `DataSource`/`Connection` 现实不符；改为 Repository 持有 Gate 单例、每查询现算 filter 并渲染 |
| 3 | D1 基线须「一库一份」且来自实际运行库 | **矛盾/硬前置（已实测修正）** | §3.2 / §6 D1 | 原写「`agent_dev` 仅 10 条、须对齐到 35 条」，2026-11-02 直连实测证伪：`agent_dev` 实为 **50 条**（全 `TENANT_ONLY`），`study_rag` 为 **37 条**（29+8 读写分）；35 条那组是 RAG 域陈旧副本 `rag_base`（比 `study_rag` 少 2 条）。跨库混用基线必致 P1 全量假 mismatch。现已按库各出一份 |
| 4 | 「切到 `app_agent`」前置状态更新为**已满足** | **与现状矛盾** | §3.2 注释 / §9 Q5 | `platform.env` 已于 2026-10-30 切 `app_agent`（NOBYPASSRLS） |
| 5 | 「补平台侧启动自检」状态更新为**已具备** | **与现状矛盾** | §3.2 P0 行 / §9 | `PlatformStartupSelfCheck` 已实装并含测试 |
| 6 | 新增 `scope` 可信推导路径 | **澄清项** | §2.2 | 明确 `scope` 由认证入口依据「是否走运维通道」唯一写入，租户请求不可自设 `PLATFORM` |
| 7 | 新增非 Repository 面处置（原始 JDBC / 批量 / 夹具） | **覆盖缺口** | §2.5 / §7.1 R8 / D12 | 方案原仅覆盖 Repository + RetrievalAdapter |
| 8 | 新增 DB 函数/触发器 GUC 依赖盘点 | **覆盖缺口** | §2.5 / §7.1 R6 / D11 | RLS 移除后依赖 `current_tenant_id()` 等的对象会失效 |
| 9 | 新增多表 JOIN 与跨租户引用章节 | **覆盖缺口** | §4.5 / §7.1 R7 / §7.3 C5 / D13 | RLS 自动覆盖 JOIN 从表，应用层需显式补 |
| 10 | 染色采样盲区 + 高敏接口 100% | **安全/性能** | §7.1 R7 / §9 Q6 | 生产 0.1% 采样不足以发现 JOIN 泄漏 |
| 11 | 缓存键与 `authzVersion` 复用 / 性能等价声明收敛 | **安全/性能** | §7.2 P6 / §7.3 C1 | 明确等价仅在单表等值成立 |
| 12 | 混合终态升级为默认护栏 | **决策** | §7.4 / §9 Q1 | `principal`/审计/`cap_decision_log` 等默认保留 RLS |
| 13 | 应用层回退机制纳入 P3 每批出口 | **回滚缺口** | §3.4 / §7.3 C6 | DDL 回滚不覆盖应用层 bug 致漏过滤 |
| 14 | `RunRecoverySweeper` 等无租户身份操作归类为 `Unrestricted` | **覆盖缺口** | §7.3 C6 | 避免误判 `NONE → 0 行` 令扫描失效 |

### 11.1 实施进展（2026-11-02）

| 交付物 | 状态 | 落点 |
|---|---|---|
| **D2** 领域模型（`Scope`/`Action`/`ScopeFilter`/`ResourceRef`/`AccessRequest`/`Decision`/`SubjectContext`） | ✅ 已实施 | `agent-core/.../core/security/`、`core/tenant/` |
| **D3** 鉴权层（`AccessGate`/`PolicyEngine`/`PolicyStore`/`DefaultAccessGate` + `Unrestricted` 仅平台运维可产出） | ✅ 已实施 | `agent-infrastructure/.../security/` |
| **D4** 方言翻译器（`ScopeTranslator` SPI + `SqlScopeTranslator`） | ✅ 已实施 | `agent-infrastructure/.../persistence/scope/` |
| **D5** 逐域接线 | 🟡 5 仓影子态（`JdbcAgentMetricRepository`/`JdbcAgentQueryRepository`/`JdbcBudgetRepository`/`JdbcDelegationBudgetRepository`/`JdbcResultQueryRepository`），默认关闭；**2026-11-02 起可由单开关 `agent.authz.shadow.enabled` 整体开启** | 同上 `persistence/` |
| **D6** 决策日志 + 染色 + 指标 | 🟡 染色/指标/决策日志接缝已就位（`AuthzDecisionRecorder` 三实现）+ **采样策略 `AuthzSamplingPolicy`**（确定性分桶、高敏 100%）+ **决策日志故障隔离**（门禁层与实现层双层兜底，审计失败不改判定）；**P2：表结构（`platform.authz_decision_log`，9 列 + PK + 2 索引）+ 授权（应用角色**只写不读**，显式 `REVOKE SELECT` 抵消 `ALTER DEFAULT PRIVILEGES` 的自动授 SELECT）+ 开关（`agent.authz.decision-log.enabled`，默认 false）+ 两道启动守卫（`AuthzDecisionLogGuard`）+ 三方对齐守卫（`AuthzDecisionLogSchemaTest`）**；**2026-11-02 已落库活库并实证**（`AuthzDecisionLogLiveIT` 5/5：探测通过 / 真写后 owner 回读 / 原因码落库 / 应用角色 SELECT 被拒 / `RETURNING` 被拒；`SchemaDriftIT` 2/2 无漂移）；⬜ 生产开启 | `security/`、`persistence/scope/` |
| **D7** 防绕过架构守卫 | ✅ 已实施（自研扫描，不用 ArchUnit）；另有 `JoinTenantPredicateArchitectureTest`、`AuthzShadowWiringTest`、`ShadowPolicyCoverageTest` | `ScopeBypassArchitectureTest` 等 |
| **D11/D12** 两份盘点 | ✅ 已生成 | `authz/guc-dependency.md`、`authz/non-repo-surface.md` |
| **D13** 跨租户 JOIN 清单 | ✅ **已闭环**：穷举（platform 15 / RAG 24 / SQL 5）+ 从表补谓词 + 从表二次染色 + 守卫 `JoinTenantPredicateArchitectureTest` | `authz/join-inventory.md` |

> **⚠️ 2026-11-02 补上的「接缝插电」（P1 上线装配）**：D2–D7 的能力与单测都齐，
> 但此前**没有任何装配**把 `Gate` / `Reporter` 交给仓库 —— 5 仓的 `scoped` 恒 `null`、
> `shadowEnabled` 恒 `false`。也就是说影子期会给出 `AUTHZ_MISMATCH=0`
> 而**实际一次都没运行过**。现由 `AuthzShadowConfiguration` 装配
> `AuthzShadowBinding`（存在即开启、不存在即关闭），仓库经 `@Autowired(required=false)`
> setter 接线，并由 `AuthzShadowWiringTest` 断言「5 仓私有字段真的被填 / 关闭时保持 dormant」。
>
> **⚠️ 2026-11-02 补上的「机制当然成立」（P1 活库实证）**：装配只证明「接线存在」，
> 不证明「钩子真的会被调用、真的能发现越权」。`AuthzShadowLiveIT`（5 例，真连 `agent_dev`）
> 补上这一层：同租户读 ⇒ 样本>0 且 `mismatch=0`/`taint=0`；**主体换成另一租户 ⇒ `mismatch=1`
> 且逐行违例数等于行数**（逐行而非只报一次）；无主体 ⇒ Gate fail-closed 计 mismatch 而非放行；
> 另有仓储端到端一例（绑定后调 `listAgents`，断言钩子被触发）。
> **这仍不等于 P1 出口**：出口要求「目标环境开启 ≥2 周」，与「机制灵不灵」是两件事，
> 不可互相替代。
> 这是本方案 §4.5「接线诚实性」从仓库层（是否调用钩子）延伸到**装配层（钩子是否拿到依赖）**。

> **D7 的一个实证价值**：守卫上线首跑即变红 —— `JdbcAgentMetricRepository` 当时仍是自建的影子钩子实现（未走 `AuthzShadowHooks` 统一入口）。这正是方案 §4.5 担心的「接线是装饰」：影子期会给出 `AUTHZ_MISMATCH=0` 的假绿，而 P1 出口条件恰恰依赖这个 0。该仓库已重构为委托统一入口。
>
> **D1 已闭环（2026-11-02 直连 RDS 重生成）**：`agent_dev` 50 条（全 `TENANT_ONLY`）、`study_rag` 37 条（29 `TENANT_ONLY` + 8 `TENANT_WITH_SHARED`），两份基线均 `UNKNOWN = 0`，与 DDL 快照策略名逐一比对零漂移。早前「须先把 `agent_dev` 对齐到 35 条」的判断已证伪并更正（35 条属 RAG 陈旧副本 `rag_base`，不是 `agent_dev` 的形态）。

> **2026-11-02 补上的「判定过程可举证」（D6 决策日志活库落地）**：表结构、授权、开关、启动守卫此前都已就位，
> 但**没有一条 SQL 真的抵达过数据库** —— 单测用动态代理 JDBC、结构守卫只读源码文本。
> 而这张表的失效方式恰恰是静默的：表缺 / 列漂 / 没授权都会让写入抛 `SQLException`，
> 再被「限频告警 + 绝不外抛」吞掉，表现为「判定照常、审计零行、系统全绿」。
> 现已落库并以 `AuthzDecisionLogLiveIT`（5 例，真连 `agent_dev`）实证：
> `probe()` 通过、`record()` 写入后**由库 owner 回读整行**逐字段相符、拒绝原因码落库、
> 应用角色 `SELECT` 被真拒、`INSERT … RETURNING` 被真拒。
> 这里刻意区分两句话：**「可写」由零行探测证明，「在写」由写后回读证明**，两者不可互相替代。
>
> **顺便挖出一个真坑：`INSERT … RETURNING` 需要返回列的 SELECT 权限。**
> 本表按 §4.5 的设计只给应用角色 INSERT，于是「为了拿到插入 id 顺手加个 `RETURNING`」
> 会让每次判定都写失败、且被限频告警吞掉。已加源码层与活库层两道守卫。
> 这说明「只写不读」不只是一个授权配置，它还是一条**对 SQL 写法的约束** ——
> 授权收紧会连带改变哪些语句可用，这类耦合只有在真库上跑才会暴露。

> **2026-11-02 补上的「撤销权限的另一半」（G3 应用侧接线）**：库侧撤销 `app_agent` 对
> `audit_record` / `erasure_certificate` 的 DELETE 之后，应用侧若仍用主数据源跑保留期清理，
> 清理任务会在**运行期** permission denied（而清理是低频批处理，可能几周后才被发现）。
> 现已接入独立清理数据源（`agent_retention`）+ `require-isolated-credential` fail-closed。
> 收尾时踩到三个坑，都属「测试看似在验证、实际在制造假象」一类：
>
> 1. **FORCE RLS 下的 DELETE 无租户绑定 = 静默 0 行**：`erasure_certificate` 等表均 FORCE RLS，
>    未绑定 `app.tenant_id` 时策略求值为 NULL，`DELETE` **影响 0 行且不报错**，
>    故障在下一条断言里以完全无关的面貌出现。凡绕过 Repository 的裸 SQL 删除，必须先 bind 租户。
> 2. **`erasure_certificate` 是不可删的**：`forbid_certificate_mutation()` 无条件拒绝 DELETE，
>    连清理角色也没有通道 —— 所以任何「凭证表为空」的断言都写错了，
>    只能写「本次运行没有**新增**凭证」。测试需要删证据，本身就是设计信号。
> 3. **`set_config(..., true)` 是事务局部的**：autocommit 连接上声明完 actor 就失效，
>    紧接着的 DELETE 看不到 actor，报错信息指向「触发器」，真因是「事务边界」。



#### ⚠️ 染色实现的两条硬约束（2026-11-02 实证，D5 第二批）

第一版接线把**查询绑定租户**当作「行租户」传给 `TaintVerifier`：

```java
shadowTaint("agent", tenantId, ...);   // ← tenantId 就是 WHERE 里的那个参数
```

这使染色**恒不违例** —— 它比对的是「我们打算查谁」与「我们打算查谁」，
而不是「实际返回的行属于谁」。**这类实现能通过全部单测、日志一片干净，
却对越权毫无检出力**，比不做染色更危险（它提供虚假的覆盖感）。

两条必须遵守的约束：

1. **行租户必须从结果行读出**：`SELECT` 显式取 `tenant_id::text`，用 `rs.getString("tenant_id")` 染色。
   为此已给 5 个仓库的 SQL 补该列（`run`/`agent`/`agent_version`/`weakness_record`/`run_step`/
   `tool_operation`/`budget_*`）—— 多读一列内部字段，不改变任何对外契约。
2. **聚合查询不可染色**：`count(*)`/`percentile_cont` 这类结果没有「行租户」，
   强行拿绑定租户比对就是约束 1 的同义反复。已移除 `terminationDistribution` 中的此类调用，
   其可信度改由 `shadowEnter` 的谓词比对 + SQL 审计保证。

> 哨兵（共享）行的判定同理：**由「行租户 == 哨兵租户」直接推断**，不依赖调用方传参，
> 否则 `TenantWithShared` 表里的共享行会被误判为违例（该误判在 `JdbcResultQueryRepositoryShadowTest` 首跑即暴露）。

#### ⚠️ 染色的第三层同义反复：主体不能取自行租户（2026-11-02 D13 实证）

修掉「用绑定租户当行租户」之后，同一个功能里还藏着更隐蔽的一次同义反复：

```java
// 旧实现：用**行租户**当主体
AuthzShadowHooks.taint(..., SubjectContext.forTenant(rowTenantId), rowTenantId, shared);
```

策略引擎对 `TENANT_ONLY` 资源的输出是 `TenantOnly(subject.tenantId())`，
于是主体被构造成 `subject = 行租户` 时，`TenantOnly(行租户)` 与 `row.tenantId() == 行租户`
必然相等 —— **逐行比对恒通过**，除 `None`（期望 0 行）外毫无检出力。

它的假绿被测试替身完美掩盖：单测里的 `AccessGate` 返回**常量** filter，
所以「主体传行租户也能抓到跨租户行」看起来成立。真实引擎下不成立。

修正为**主体与行归属分离**：

```java
void shadowTaint(String resourceType, String subjectTenantId, String rowTenantId)
// 主体 = 发起查询的租户；行归属 = 结果行真实 tenant_id
```

并加**真实 `RuleBasedPolicyEngine`** 的回归用例（主体 A + 行 B → 必须违例），
一旦有人改回旧实现，该用例立刻变红。

> **教训（对 D6 验收的影响）**：染色「跑起来了」「日志干净」都不构成证据。
> 唯一可接受的证据是：**注入一个他租户行，必须产生违例计数**。
> 这也是 D13 要求「JOIN 从表二次染色」的原因 —— 从表越权时主表字段仍属本租户，
> 只染主表永远发现不了（详见 `authz/join-inventory.md` §0）。



