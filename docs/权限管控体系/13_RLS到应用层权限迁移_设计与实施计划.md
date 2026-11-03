# 13｜RLS → 应用层权限迁移 · 设计文档与实施计划

> **配套文档**：[12_RLS到应用层权限迁移方案.md](12_RLS到应用层权限迁移方案.md)（决策规范，本文是其落地设计 + 排期）
> **状态**：🟡 实施设计（随 12 文评审修订同步更新，含 D1 基线校正、D11/D12/D13 新增、应用层回退机制；**D13 已闭环；P1 影子态可开启且已在活库实证跑通；P2 决策日志表/授权/开关/守卫已就位，活库 DDL 待 owner 凭据**）
> **目标读者**：架构 / 平台 / RAG 研发、SRE、安全评审

---

## 1. 文档定位与依据

本文承接 12 文的决策规范，给出**可执行的工程落地设计、阶段排期、交付物与验收矩阵**。12 文回答"为什么迁、迁成什么形态"，本文回答"怎么分步建、何时验收、谁来把关"。

**依据**：
- 12 文 §0–§11（含 §11 评审修订记录）；
- 实证现状：`platform.env` 主数据源已切 `app_agent`（2026-10-30）；`PlatformStartupSelfCheck` 已实装；D1 基线（`scripts/export-rls-policies.sh` + `authz/policy-mapping.yaml`）已生成但来源须校正为 `agent_dev`；现役 `CREATE POLICY` 共 89 条（platform 50 + rag 37 + 增量 2）。

---

## 2. 目标 / 非目标

| 维度 | 内容 |
|---|---|
| **目标** | ① 行级隔离唯一权威上移至应用层；② 解除权限逻辑与 PG 的耦合（数据库无关化）；③ 保留 fail-closed 与可观测性收益；④ 混合终态合法（高敏表保留 RLS） |
| **非目标** | ① 不改变 05 文能力层（Agent/Skill/Tool/MCP）授权；② 不重写业务语义；③ 不追求 SQL 语法 100% 可移植（仅权限语义与方言解耦）；④ 不要求本计划覆盖向量库选型（见 12 文 §9 Q4） |

---

## 3. 总体原则（落地红线）

1. **双写取交集**：双写期 RLS 一条策略都不许动；应用层过滤全部加在 RLS 之内，安全语义**只会变紧不会变松**。
2. **fail-closed**：无上下文即拒绝、filter 缺失即构造失败、`None` 渲染恒假、染色违例即告警。
3. **混合终态合法**（12 文 Q1 已决议）：`principal`、审计表、`cap_decision_log` 等高敏表**默认保留 RLS** 作第二道防线。
4. **三层补偿替代机制保证**：类型系统（无 Gate 无 Repository 实例）+ 结果集染色 + 反证测试，弥补"退役后只剩应用层"的纵深下降。
5. **基线一库一份、先闭合再比对**：`agent_dev`（平台域）与 `study_rag`（RAG 域）**各出一份**翻译基线，禁止跨库混用；基线闭合前不得进 P1，否则影子比对的是「两个不同数据库的策略能否对上」，必然全量假 mismatch。

---

## 4. 阶段总览

| 阶段 | 周期 | 目标 | 出口门禁（硬） |
|---|---|---|---|
| **P0 准备** | 2 周 | 基线校正 + L0/L1/L2 骨架 + 三份盘点 | D1 `meta.source==agent_dev`；Gate 单测绿；主数据源确为 `app_agent` |
| **P1 影子** | 3 周 | 离线比对 + 染色上线 | ≥2 周 `AUTHZ_MISMATCH=0`；任何 mismatch 修基线 |
| **P2 双写** | 4 周 | SQL 切换为翻译结果 + 反证改造 | 全量 IT 绿 + 双反证通过 + EXPLAIN 对比达标 |
| **P3 逐表退役** | 8 周 | 分批 `DROP POLICY`（B1–B4，含白名单） | 每批跨租户仍红 + 回滚演练 + 应用层回退就绪 |
| **P4 收口** | 持续 | 规则进配置中心 + 纪律反转 | 新增表默认无 RLS，评审检查注册 + scoped |
| **P5 清理** | P3 后 1 季度 | 删 `TenantSessionBinder` + DDL 清理 | 独立 PR + 全量回归绿 |

> 总工期约 **27 周（含 P4 持续期）**；P3 可与 D9（向量库适配器）并行。

---

## 5. 分阶段详细计划

### 5.1 P0 准备（2 周）

**任务清单**
1. **D1 校正（✅ 2026-11-02 已完成）**：直连 RDS 从**两个活库各导一份**——`agent_dev`（50 条，全 `TENANT_ONLY`）→ `authz/policy-mapping.yaml`；`study_rag`（37 条，29 `TENANT_ONLY` + 8 `TENANT_WITH_SHARED`）→ `authz/policy-mapping-rag.yaml`。导出脚本新增「远程活库」模式（`PG_URI` + `PSQL_BIN`），无需 docker 容器。旧 `rag_base`（35 条）基线已归档为 `policy-mapping.legacy-rag_base.yaml`。
2. **核验启动自检**：确认 `PlatformStartupSelfCheck` 覆盖"主数据源非特权 + 运维角色互斥"；补 Rag 侧对称性测试。
3. **搭骨架（不接 Repository）**：`SubjectContext`（扩 `scope`）、`ScopeFilter`（含 `None` permitted 子类型）、`AccessGate`、`PolicyEngine`/`PolicyStore`；单测覆盖 `isPlatformOps()→Unrestricted` 不变式、`None` 恒假。
4. **新增三份盘点**：D11 GUC 依赖清单、D12 非 Repository 面矩阵、D13 跨租户 JOIN 清单（**穷举已完成**：platform 15 处 / RAG 24 处 / SQL 5 处，见 `authz/join-inventory.md`）。
5. **修复设计缺陷**：§2.3.1 `None` 密封子类型；§2.4 Gate 执行期取 filter 模式。

**关键交付物**：D1（校正）、D2、D3、D11、D12、D13。

**验收标准**
- [x] D1 两份基线分别绑定实际运行库（`meta.source` 指向活库），`policy_count` 与活库一致（50 / 37），`UNKNOWN = 0`；
- [ ] D1 缺口清单结论经人工确认（`agent_dev` 9 张无策略表均属平台域：`platform.*` 指标/配额 + `public.tenant`）；
- [ ] Gate 决策单测绿，含 `isPlatformOps()→Unrestricted` 不变式、`None` 渲染恒假、注入异常 `PolicyEngine`→请求 500；
- [ ] D11/D12 两份清单评审通过（**D13 已完成**：穷举 + 从表谓词 + 染色覆盖 + 守卫，2026-11-02）；
- [ ] 主数据源确为 `app_agent`（启动自检通过）；
- [ ] `ScopeFilter`/`SubjectContext` 编译通过（含 `None` 密封穷尽性）。

### 5.2 P1 影子（3 周）

**任务清单**
1. Repository 双通道：查询仍走原 SQL + RLS；**同时** Gate 算 `ScopeFilter` 并与实际谓词离线比对（只记日志不影响请求）。
2. 结果集染色校验上线（✅ 2026-11-02：采样策略 `AuthzSamplingPolicy` 落地 —— 确定性分桶 + 窗口滚动 + 高敏 100%；默认 `rate=0.01`（12 文原写 0.1% 已上调，见下））。
3. **D13 中登记的 JOIN 从表补 filter 并在染色中覆盖**（✅ 已接 Gate 的 3 组 6 处已补；其余 J4–J9 随各自 D5 接线）。
4. **影子上线装配（✅ 2026-11-02）**：`AuthzShadowConfiguration`（`agent.authz.shadow.enabled` 单开关）产出 `AuthzShadowBinding`，5 个仓库经 `@Autowired(required=false)` setter 接线；关闭时该 Bean 不存在、行为逐字节不变。
   - 装配由 `AuthzShadowWiringTest` 钉死（开启 → 5 仓私有字段真的被填；关闭 → 保持 dormant），**防「影子期从未运行却报 AUTHZ_MISMATCH=0」**。
   - 资源类型与策略规则的对应关系由 `ShadowPolicyCoverageTest` 钉死（钩子用到而未登记的 `resourceType` 直接红），避免「每次查询都 NO_RULE」的恒定假 mismatch。

> ⚠️ **采样率为何从 0.1% 调到 1%**：0.1% 在「(主体,资源) × 300s 一桶」的确定性采样下，
> 单个组合平均要等约 100 小时才被采到一次；两个月观察期里小流量租户可能**一次都没被看过**，
> 而读数仍是 `AUTHZ_MISMATCH=0`。高敏资源（`run` / `tool_operation` / `weakness_record` / `run_step`）
> 另由 100% 清单兜底。**采样只作用于「整次查询要不要看」，被采到的查询内每一行都染色，违例不会被二次采样丢掉。**

> ⚠️ **平台全域聚合（无租户主体的 metric 查询）在影子期不比对**：它只能算 `Unrestricted`，
> 而 `Unrestricted` 需要「平台运维主体」才能验证；拿租户态主体去比只会得到恒定假 mismatch。
> 该路径的可观测性归 D12（非 Repository 面）与 P3 平台审计，**不在影子期冒充已验证**。

**关键交付物**：D5（影子态）、D6（染色）、D13 补丁。

**验收标准**
- [x] **影子机制在真实库上跑通过**（`AuthzShadowLiveIT`，2026-11-02）：`shadowSamples>0`、同租户 `mismatch=0` / `taint=0`、跨租户**必须变红**；
- [ ] 影子期 ≥2 周（含全量 IT 全绿）`AUTHZ_MISMATCH = 0`；
- [ ] 高敏接口（导出/检索/统计/跨租户读）100% 染色无违例；
- [ ] **影子态在目标环境真实开启过**（`agent.authz.shadow.enabled=true` 且 `shadowSamples>0`）——「一次没跑过」不算通过；
- [ ] **任何 mismatch 归因到基线/形态并修基线，绝不修代码迁就**（见 §3 红线 5）。

> **关于第一条与第四条的区别（重要）**：`AuthzShadowLiveIT` 解决的是「机制本身灵不灵」——
> 它在 `agent_dev` 上真连、真读、真染色，证明**检出力存在**（把主体换成另一个租户，违例立刻计数）。
> 它**不**解决「生产开了两周没有 mismatch」——那需要目标环境实际运行时长。
> 两者不可互相替代：前者防「代码写了但从没跑过」，后者防「跑的是本地库、生产没开」。

### 5.3 P2 双写（4 周）

**任务清单**
1. Repository SQL 切换为 `SqlScopeTranslator.render(filter)` 产物；RLS 策略原样保留（两层取交集）。
2. 决策日志上线：✅ 表结构（`platform.authz_decision_log`）+ 授权（应用角色**只写不读**）+ 装配开关（`agent.authz.decision-log.enabled`）+ 两道启动期 fail-closed 守卫（`AuthzDecisionLogGuard`）已就位；✅ **活库 DDL 已落库**（2026-11-02，库 owner `ai_agent` 执行，`SchemaDriftIT` 2/2 复验无漂移）；✅ **活库实证**：`AuthzDecisionLogLiveIT` 5/5 —— 探测通过、判定写入后由 owner 回读得到整行、拒绝原因码落库、应用角色 SELECT 被拒、`INSERT…RETURNING` 被拒；⬜ 生产开启（本地 dev 已置 `AGENT_AUTHZ_DECISION_LOG_ENABLED=true`）。
3. 反证脚本改造：旧「删 RLS → 红」保留；新「绕 Gate → 红」建立（ArchUnit + 集成测试）。
4. P2 出口 `EXPLAIN` 对比扩展到 JOIN 重查询与分区表（12 文 §7.2 P6）。
5. ✅ **清理通道凭据隔离的应用侧接线**（2026-11-02，G3 收尾）：库侧已撤销 `app_agent` 对
   `audit_record` / `erasure_certificate` 的 DELETE，若应用仍用主数据源跑清理，则清理任务
   **运行期必然 permission denied**（低频批处理，可能几周后才发现）。现已在 `platform.env`
   接入 `AGENT_RETENTION_DATASOURCE_*`（`agent_retention`）并置
   `AGENT_RETENTION_REQUIRE_ISOLATED_CREDENTIAL=true`（缺清理凭据即拒绝启动）；
   `RetentionErasureIT` 改为双凭据（应用池验「删不了」、清理池验「删得对」），
   并在 `@BeforeAll` 断言两侧身份，防止「清理池其实连成了应用角色」。

**关键交付物**：D4、D5、D6、D7。

**活库落库记录（2026-11-02 已完成）**

`app_agent` 对 `platform` schema `has_schema_privilege(...,'CREATE') = false`（实测），故建表由库 owner `ai_agent` 执行。
落库后实测三件事，均已固化为 `AuthzDecisionLogLiveIT`（活库 IT，缺凭据即跳过但不算通过）：

| 实测项 | 结果 | 为什么必须实测 |
|---|---|---|
| `probe()`（零行写入探测） | 通过 | 它是 `AuthzDecisionLogGuard` 的判据；从没跑过＝门禁不存在 |
| 判定写入后 owner 回读 | 整行对得上 | **零行探测只证明「可写」，不证明「在写」**，13 文 §5.3 原就写明两者不可互替 |
| 应用角色 `SELECT` | `permission denied` | 「只写不读」不能只在授权脚本里写着，得在活库上真的读不动 |
| `INSERT … RETURNING id` | `permission denied` | 见下方「RETURNING 坑」 |

> **⚠️ 实测挖出的坑：`INSERT … RETURNING` 要 SELECT 权限。**
> PostgreSQL 的 `RETURNING` 会对返回列做权限检查。同一个角色、同一张表：
> 不带 `RETURNING` 的 INSERT 成功；带 `RETURNING id` 直接 `ERROR: permission denied for table authz_decision_log`。
> 而本表按设计恰恰不给应用角色 SELECT —— 于是「为了拿到插入的 id 顺手加个 RETURNING」
> 会让**每次判定都写失败**，且失败被 `JdbcAuthzDecisionLog` 的「限频告警 + 绝不外抛」吞掉，
> 表现为：判定照常、审计零行、系统全绿。已加两道守卫：源码层 `AuthzDecisionLogSchemaTest#insertMustNotUseReturning`，
> 活库层 `AuthzDecisionLogLiveIT#insertWithReturningIsDenied`（后者也防「绕过源码守卫直拼 SQL」）。
> 同批还加了 `probeWritesNoRows`：探测必须 `WHERE FALSE`，否则每次启动往审计流水塞一行 `SELF_CHECK` 假证据。

**活库等价 DDL（已执行；保留以备环境重建）**

```sql
BEGIN;
CREATE TABLE IF NOT EXISTS platform.authz_decision_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    occurred_at timestamptz DEFAULT now() NOT NULL,
    tenant_id text NOT NULL,          -- text 而非 uuid：审计要能记任意形态主体
    principal_id text NOT NULL,
    scope varchar(32) NOT NULL,
    action varchar(48) NOT NULL,
    resource_type varchar(128) NOT NULL,
    permitted boolean NOT NULL,
    reason_code varchar(64)
);
ALTER TABLE ONLY platform.authz_decision_log
    ADD CONSTRAINT authz_decision_log_pkey PRIMARY KEY (id);   -- 已存在时跳过
CREATE INDEX IF NOT EXISTS idx_authz_decision_log_occurred
    ON platform.authz_decision_log (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_authz_decision_log_tenant
    ON platform.authz_decision_log (tenant_id, occurred_at DESC);
GRANT INSERT ON platform.authz_decision_log TO app_agent;
REVOKE SELECT ON platform.authz_decision_log FROM app_agent;  -- ⚠️ 抵消 DEFAULT PRIVILEGES 的自动授 SELECT
GRANT SELECT, INSERT, UPDATE, DELETE ON platform.authz_decision_log TO platform_ops;
COMMIT;
```

落库后核对：`\d platform.authz_decision_log` + `has_table_privilege('app_agent', ..., 'SELECT') = false`，再跑 `SchemaDriftIT`。

**验收标准**
- [x] 全量 IT 绿（**2026-11-02 23:05 实测：10 模块 BUILD SUCCESS，2672 例 0 失败 / 5 跳过（bootstrap 需外部依赖的用例）**，
      12:34 min。为达到它连续拆了 4 条「环境前提缺失」型红：
      `PgQueueIT` / `MessagingSwitchGuardIT` / `JdbcStatisticsRepositoryIT`（改用 `it-*` 专用租户）、
      `GovernanceQueryIT`（去掉「一页装得下」的分页假设）、`RateLimitIT`（时钟边界 + 远端延迟抖动）。
      详见 §8.1。⚠️ 前提是**租户级隔离**；整库隔离仍建议做，否则 `t-a` 会继续以每轮约 90 条的速度累积被
      运行实例跑失败的 IT 夹具）；
- [ ] 双反证同时通过（删 RLS 仍红 + 绕 Gate 红）；
- [ ] 性能基线达标：Top-20 慢查询 + JOIN 重查询 `EXPLAIN` 对比无回归；
- [ ] 染色无违例（生产高敏 100%）；
- [x] **决策日志真实落库过**：由 `AuthzDecisionLogLiveIT` 在 `agent_dev` 上实证 —— `record()` 写入后由库 owner 回读，断言行存在且 `tenant/principal/scope/action/resource/permitted/reason_code` 逐字段相符（放行为 `NULL`）。⚠️ 这一条**只**证明「写入路径真的抵达数据库」，**不**等于「生产环境在持续累积」：后者仍要看 `platform.authz_decision_log` 行数随采样查询增长（启动期零行探测只能证明「可写」，不能证明「在写」——两者不可互相替代）。
- [ ] 生产开启：`AGENT_AUTHZ_DECISION_LOG_ENABLED=true` 并观察行数增长（本地 dev 已开启）。

### 5.4 P3 逐表退役（8 周，批次 B1–B4）

**任务清单**（每批：退役 → 观察 → 回滚演练）

| 批次 | 表域 | 退役动作 | 验证 |
|---|---|---|---|
| **B1** | 只增日志类（`cap_decision_log` 等，注意白名单） | `DROP POLICY` → 观察 1 周 → `DISABLE ROW LEVEL SECURITY` | 该域 IT + 跨租户用例仍红 |
| **B2** | platform 业务表（`run` / `agent_invocation` / `cap_*` 等，36 张带 FORCE） | 同上 | 同上 + `MultiAgentSchemaIT` 断言改造（应用层防线存在性） |
| **B3** | RAG 语料表（13 表，含哨兵模型） | 同上 | 语料 scope 反证脚本全绿 + D13 JOIN 已补 |
| **B4** | 平台目录表 + 运维通道 | `GuardedPlatformOpsDataSource` 语义切换 | 通道闸门测试不变（未配置即 503、绝不回落） |

> **白名单（混合终态）**：`principal`、审计表、`cap_decision_log` 等**保留 RLS 不退役**，P3 跳过其 `DROP POLICY`。

**关键交付物**：D8（DDL 保留可回滚）、D9（可并行）。

**验收标准（每批）**
- [ ] 跨租户用例**仍红**（现在靠应用层拦）；
- [ ] 回滚演练通过：**DDL 重放 + 应用层回退开关**双路就绪（12 文 §3.4 补充）；
- [ ] D13 中该批 JOIN 已补 filter 且染色覆盖；
- [ ] 生产高敏接口 100% 染色无违例。

### 5.5 P4 收口（持续）

**任务清单**
1. 策略规则进配置中心 + 双人复核（对齐现有"申请→审批→执行"）。
2. `SchemaStructureTest` 断言对象从 `pg_catalog` 迁到"应用层防线注册表"。
3. 新增表纪律反转：默认无 RLS，安全评审检查"是否注册策略规则 + Repository 是否走 Gate"。

**验收标准**
- [ ] 新增表默认无 RLS，评审检查项齐备；
- [ ] 规则变更走治理流且可审计 `policyVersion`。

### 5.6 P5 清理（P3 后 1 季度）

**任务清单**
1. 删 `TenantSessionBinder`（独立 PR + 全量回归）。
2. 策略 DDL 定义从代码库清理（退役稳定一季后）。

**验收标准**
- [ ] 独立 PR + 全量回归绿；
- [ ] 退役稳定一季，无漏过滤告警。

---

## 6. 关键交付物清单（扩展至 D1–D13）

| # | 交付物 | 落点 | 状态 | 阶段 |
|---|---|---|---|---|
| **D1** | RLS 策略导出 + 翻译对照表（一库一份） | `scripts/export-rls-policies.sh` → `authz/policy-mapping.yaml`（agent_dev 50）+ `policy-mapping-rag.yaml`（study_rag 37） | ✅ 已完成（2026-11-02 直连 RDS） | P0 |
| **D2** | `SubjectContext` / `ScopeFilter` / `Decision` 领域模型 | `platform/agent-core`（`core/security/` + `core/tenant/SubjectContext`） | ✅ 已实施（代码 + 单测，2026-11-02） | P0 |
| **D3** | `AccessGate` + `PolicyEngine` + 规则加载器 + `SubjectContextResolver` | `platform/agent-infrastructure/.../security/` | ✅ 已实施（代码 + 单测，含 R3 不变式与 fail-closed，2026-11-02） | P0–P1 |
| **D4** | `SqlScopeTranslator` + `ScopeTranslator` SPI | `platform/agent-infrastructure/.../persistence/scope/` | ✅ 已实施（随 D3 一并落地，2026-11-02） | P1 |
| **D5** | Repository scoped 改造（逐域，Gate 执行期取 filter） | 各 Repository | 🟡 部分实施（`ScopedDataAccess` + `AuthzShadowHooks`；**5 仓**影子接线 dormant，2026-11-02；`AuthzShadowConfiguration` 单开关可整体开启，`AuthzShadowWiringTest` 钉死接线） | P1–P2 |
| **D6** | 决策日志 + 结果集染色 + `AUTHZ_MISMATCH` 指标 | 复用 `JdbcPlatformAuditRepository` 形态 | 🟡 部分实施（染色核心 + 指标 + 影子上报 + 采样策略 `AuthzSamplingPolicy` + `AuthzDecisionRecorder` 三实现；决策日志故障隔离已加固；**表结构/授权/开关/启动守卫/三方对齐守卫已就位**；**活库 DDL 已落库 + 活库实证 5/5**（探测/真写回读/原因码/只读被拒/`RETURNING` 被拒），2026-11-02。⬜ 生产开启） | P1–P2 |
| **D7** | 反证测试改造（绕 Gate → 红）+ 架构守卫 | 自研扫描 `ScopeBypassArchitectureTest`（不引 ArchUnit 依赖） | ✅ 已实施（4 条不变量 + 3 条自证，2026-11-02） | P2 |
| **D8** | 逐表退役迁移脚本（DDL 保留可回滚） | `deploy/sql/**/V*_drop_rls_policy.sql` | ⬜ 未开始 | P3 |
| **D9** | 目标向量库适配器（Milvus 等） | `rag/rag-infrastructure/.../vector/` | ⬜ 未开始 | P3–P4（并行） |
| **D10** | `TenantSessionBinder` 删除 + 平台侧自检补齐 | `platform/agent-infrastructure/.../persistence/` | ⬜ 未开始 | P3–P5 |
| **D11** | **GUC 依赖盘点**（新增） | `authz/guc-dependency.md` | ✅ 已生成（P0 基线，2026-11-02） | P0 |
| **D12** | **非 Repository 面矩阵**（新增） | `authz/non-repo-surface.md` | ✅ 已生成（P0 基线，2026-11-02） | P0 |
| **D13** | **跨租户 JOIN 清单**（新增） | `authz/join-inventory.md` | ✅ 已闭环（P0 穷举 + P1 从表谓词/染色覆盖 + 守卫 `JoinTenantPredicateArchitectureTest`，2026-11-02） | P0–P1 |

---

## 7. 验收矩阵（需求 → 交付物 → 测试 → 阶段）

| 12 文需求 | 交付物 | 测试 / 反证 | 阶段 |
|---|---|---|---|
| 唯一权威上移（§0） | D2/D3/D5 | Gate 单测 + 绕 Gate→红反证 | P0/P2 |
| 数据库无关（§4） | D4/D9 | 各 `ScopeTranslator` 单测 + 向量库适配器验证 | P1/P3 |
| fail-closed（§7.1 R5） | D2/D3/D6 | 注入异常 `PolicyEngine`→500；`None` 恒假 | P0 |
| GUC 依赖（§7.1 R6） | D11 | 删 GUC 后依赖对象按预期报错/返回哨兵 | P0/P2 |
| JOIN 泄漏（§7.1 R7） | D13/D6 | JOIN 染色覆盖 + 跨租户 JOIN 反证 | P0/P3 |
| 非 Repository 面（§7.1 R8） | D12 | 每类改造/豁免反证 | P0/P2 |
| 运维通道不退化（§7.1 R3） | D3/D10 | `Unrestricted` 仅 `isPlatformOps()`；通道未配置→503 | P0/P3 |
| 跨租户引用（§7.3 C5） | D5 | 插他租户父行必须失败 | P2 |
| 无租户身份操作（§7.3 C6） | D3 | `RunRecoverySweeper` 归类为 `Unrestricted` 反证 | P0 |
| 混合终态（§9 Q1） | D8（白名单） | 白名单表退役跳过 + 跨租户仍红 | P3 |
| 应用层回退（§3.4） | D8 | 每批回滚演练（DDL + 代码开关） | P3 |

---

## 8. 风险与回滚

**风险沿用 12 文 §7（R1–R8、P1–P6、C1–C6）**，本计划新增的处置要点：

- **基线跨库错配（最高频）**：必须一库一份（`agent_dev` / `study_rag`），**禁止**用一库的基线去比对另一库；任何 P1 mismatch 一律先归因基线，禁止修代码迁就。
- **应用层 bug 致漏过滤**：DDL 回滚不覆盖，须**代码回退开关 / 旧版 Repository 并行保留**（§3.4 补充），每批出口含回滚演练。
- **JOIN / 函数依赖盲区**：由 D11/D13 在 P0 穷举，P2/P3 验收逐条覆盖。

**回滚矩阵**

| 阶段 | 回滚方式 | 数据风险 |
|---|---|---|
| P0–P2 | 关影子/双写开关，RLS 原样兜底 | 零 |
| P3（每批） | DDL 重放 + **应用层回退开关** | 零（策略 DDL 不动数据） |
| P4+ | — | — |

### 8.1 测试环境前提：集成测试与「正在运行的实例」共用同一个库

**已发生（2026-11-02）**：`PgQueueIT` 与 `MessagingSwitchGuardIT` 长期在 `t-a` 上做
「入队 → 我领得到 / 残留还在」类断言，而本机常驻一个平台实例，按
`agent.worker.tenant-slugs`（默认 `t-a`）轮询**同一个 `agent_dev` 库**，
把消息抢先 `claim` 并 `ack` 删除。表现为：

- `claim` 返回 0 行、**无异常无日志**；而同一秒 `SELECT count(*)` 还能数到那行；
- 把同一段 claim SQL 去掉 `FOR UPDATE SKIP LOCKED` 立刻能选中（行被 worker 的事务锁着）；
- 单独跑与全量跑结果不同 → 长期被当作「flaky」忽略，实际每次都在掩盖真实前提缺失。

**处置（两层）**

1. **首选：整库隔离** —— `scripts/dev-it-db.sh` 建立 `agent_it`（本地容器 5433），
   IT 全部指向它，与运行实例彻底分开。这是根治手段。
2. **必须：租户级隔离** —— 即使仍指向共享库（当前用 RDS `agent_dev`），
   凡断言「只有我能做 X」的用例，X 的作用域必须**独占**。
   已收敛为统一实现 `ItTenantProvisioner`（`agent-test-support`，test 作用域），
   它把「slug 必须以 `it-` 开头」变成硬校验（`ItTenantProvisionerTest` 4 例钉死），
   并幂等创建 `tenant + agent + agent_version + execution_snapshot`
   （`run.snapshot_id` 必填且带复合外键，只建 tenant 无法造 Run）。

| IT | 专用租户 | 附加护栏 |
|---|---|---|
| `PgQueueIT` | `it-pgq-a` / `it-pgq-b` | 外部消费者探针：放一条消息等 2s，被领走即直接报原因 |
| `MessagingSwitchGuardIT` | `it-switch-a` | 制造残留与读残留之间不允许出现第三方消费者 |
| `JdbcStatisticsRepositoryIT` | `it-stat-a` / `it-stat-b` | 断言一律增量；每例只删自己造的行（按前缀） |

**推论（对所有 IT 生效）**：共享库上的断言**一律要带租户过滤**。
`SELECT count(*) FROM agent_message_dead_letter` 这类「数全表」的写法在干净库上刚好成立，
在共享库上必错，且失败信息指向完全无关的方向。
同理，`SELECT snapshot_id FROM execution_snapshot LIMIT 1` 必须按租户过滤 ——
库里已有多个 IT 专用租户的快照，取错租户会让复合外键 `(tenant_id, snapshot_id)` 报错，
而报错信息不会告诉你「取错租户了」（`GovernanceQueryIT` 已修）。

**第三种表现（已按租户级隔离拆除）：运行实例会持续「处理」IT 遗留的 Run，改写统计基线**

`JdbcStatisticsRepositoryIT` 原用「先取基线 → 再造数 → 比对差值」的增量断言（这是正确写法），
跑在种子租户 `t-a` 上。而运行实例会把 IT 遗留的 Run **当成真活儿干完**：

1. 多个 IT 只建 `run`（`state='QUEUED'`）不清理 —— 实测 `agent_dev` 现存 **332 条 QUEUED**；
2. 运行实例的 `OutboxRelay`（200ms 一轮）把这些 Run 的 outbox 事件发布进队列；
3. `PgQueueListener` 领取 → `RunDispatchConsumer` 执行 → 因 IT 快照是占位内容而失败，
   置为 `FAILED / CONFIG_UNAVAILABLE`（现存 **410 条**，近 2 小时**新增 356 条**）；
4. 于是「本区间终态数」在基线与查询之间凭空空多若干条 —— 差值断言随机失真
   （实测 `expected 3L but was 4L`，失败时间戳 22:04:16 正落在回归执行期间）。

处置：改用 `it-stat-a` / `it-stat-b`（结构上不可能被 worker 轮询），9/9 绿。
**注意这只解决「本 IT 的基线不被改写」，不解决「别的 IT 的遗留 Run 被真干」** —— 后者仍是环境问题。

- **根治仍是整库隔离**（第 1 层）。租户级隔离是「共享库上能成立的最小前提」，不是等价替代。
- **不要**为此关掉运行实例的 `agent.worker.recovery` / `relay-enabled` ——
  那等于用「隐藏两个生产特性」换取测试变绿，而它们正是防「Run 永久卡住」的自愈机制。
- 那 332 条 QUEUED + 410 条 FAILED 属 **IT 遗留数据**，清理属**数据变更**，
  须显式确认后执行（参考 `scripts/dev-it-db.sh` §2.6 的清理清单，该脚本只对隔离库生效）。

---

## 9. 里程碑与决策门禁

| 里程碑 | 准入条件 | 准出（门禁） |
|---|---|---|
| **M0** P0 出口评审 | D1 校正 + 骨架 + 三盘点 | 全部 §5.1 验收项通过 |
| **M1** P1 影子放行 | M0 通过 + 染色上线 | ≥2 周 `AUTHZ_MISMATCH=0` |
| **M2** P2 双写放行 | M1 通过 | 全量 IT 绿 + 双反证 + EXPLAIN 达标 |
| **M3** P3 首批准入 | M2 通过 + 白名单确定 | B1/B2 回滚演练通过 |
| **M4** 全量退役 + 混合终态确认 | M3 + B3/B4 完成 | 跨租户用例全红 + 审计无漏过滤 |
| **M5** P5 清理 | M4 稳定一季 | 独立 PR + 全量回归绿 |

---

## 10. 修订记录

- 初版：依据 12 文决策规范落地排期。
- 同步 12 文评审修订：纳入 D1 基线校正（§5.1/§6）、D11/D12/D13 新增盘点（§5.1/§6）、应用层回退机制（§3.4/§8）、JOIN/函数依赖/非 Repository 面覆盖（§7 验收矩阵）、混合终态默认护栏（§3 红线 3）。
- **2026-11-02 P0 启动实施**：D2/D3/D4 代码骨架已落地（`agent-core` 领域模型 + `agent-infrastructure` 鉴权层与 `SqlScopeTranslator`，单测全绿）；D11/D12/D13 三份盘点文档已生成（落 `authz/`）。**D1 基线校正（从 `agent_dev` 重生成）为运维步骤，待具备 DB 访问时执行**。
- **2026-11-02 P1 推进（D5/D6 影子态骨架）**：新增 `persistence/scope/` 下 `RowAttribution`/`TaintVerifier`/`TaintReport`（染色校验核心）+ `security/` 下 `AuthzMismatchMetrics`(+`InMemory`/`Slf4j` 实现)/`AuthzShadowReporter`/`ScopedDataAccess`（D5 接缝）。`JdbcAgentMetricRepository` 非侵入接入：保留原 `(DataSource)` Spring 构造，新增可选 `shadowEnabled` 开关（默认 false，零行为变更），在 `overview`/`terminationDistribution`/`latency`/`findByTraceId` 调用 `shadowEnter`（比对 Gate ScopeFilter 与查询绑定租户）+ `checkTaint`（逐行染色）。15 例单测全绿（TaintVerifier 6 / AuthzShadowReporter 2 / ScopedDataAccess 1 / JdbcAgentMetricRepositoryShadow 6）。**P1 阻断开关（`blockOnViolation` 翻转）留待 P2 双写期开启；D1 校正仍是 M0 准出门禁前提**。
- **2026-11-02 P1 扩面（D5 逐域接线 + D6 决策日志接缝）**：
  - `AccessGate` 增 `authorizeCollection(SubjectContext, Action, String)` 显式主体重载（后台/worker 路径无请求上下文可钉死主体），`DefaultAccessGate` 实现并接入 `AuthzDecisionRecorder`（Permit / Deny / 异常三条路径都记），`ScopedDataAccess` 增对应重载。
  - 新增 `AuthzDecisionRecorder` 三实现：`NoOp`（默认，零副作用）、`InMemory`（本地/单测）、`JdbcAuthzDecisionLog`（P2 生产，落 `platform.authz_decision_log`）。
  - 新增 `AuthzShadowHooks` 统一静态入口（比对 + 染色各两种主体来源：显式 / 环境），**禁止各仓自建实现**。
  - D5 扩面至 4 仓：`JdbcAgentQueryRepository`（agent + agent_invocation JOIN）、`JdbcBudgetRepository`（budget_reservation + tool_operation JOIN）、`JdbcDelegationBudgetRepository`（预算 + 调用 JOIN）、`JdbcAgentMetricRepository`（指标）。全部 **dormant 默认关闭、零行为变更**。
  - `SubjectContext.forTenant(String)` 工厂。
  - 单测：`AuthzDecisionRecorderTest` 4 / `AuthzShadowHooksTest` 7 / 三仓影子接线各 5，加既有 15 例共 31 例；`mvn -o -pl agent-infrastructure -am test` **BUILD SUCCESS**。
- **2026-11-02 D7 提前落地（架构守卫）**：因 m2 无 ArchUnit（离线不可引入），改用本仓既有「自研源码扫描型架构测试」惯例（对齐 `ToolBypassArchitectureTest`）。`ScopeBypassArchitectureTest` 钉死 4 条不变量：①`ScopeFilter.Unrestricted` 唯一产出点为 `RuleBasedPolicyEngine`（R3）；②已登记仓库必须**真的**调用 `AuthzShadowHooks`（防「注入不调用」的装饰性接线）；③Gate 持有者必须登记（防漏过滤入口）；④台账无漂移。另配 3 条**扫描器自证**（注入越权构造 / 静默接线必须被检出，合规代码不误报）。
  - ⚠️ **守卫上线首跑即变红**：`JdbcAgentMetricRepository` 仍是早前自建的 `shadowEnter`/`checkTaint` 实现，未走统一入口 —— 已重构为委托 `AuthzShadowHooks`（环境主体重载），否则「影子期 0 mismatch」是假绿。
  - 验证：`agent-infrastructure -am` 全模块 **323 例 0 失败、7 模块 SUCCESS**。
- **2026-11-02 D1 闭环 + D5 第二批（染色纠错）**：
  - **D1**：导出脚本新增「远程活库」模式（`PG_URI` + `PSQL_BIN`，绕开 docker 依赖；`PG_USER` 显式传参避免 psql 回落 OS 用户名）。直连 RDS 从两活库各出一份基线：`agent_dev` **50 条**（全 `TENANT_ONLY`）、`study_rag` **37 条**（29 + 8 读写分），`UNKNOWN=0`，与 DDL 快照策略名逐一比对**零漂移**。旧 `rag_base`(35) 基线归档为 `policy-mapping.legacy-rag_base.yaml`。**证伪并更正**「`agent_dev` 仅 10 条、须对齐到 35 条」——35 条属 RAG 陈旧副本，跨库混用基线才是真风险。
  - **D5 第五仓**：`JdbcResultQueryRepository`（一次读 4 张租户表）接入，含 7 例影子测试。
  - **⚠️ 纠错（重要）**：首版把「查询绑定租户」当行租户传给 `TaintVerifier`，染色**恒不违例**（零检出力）。已改为从结果行读 `tenant_id::text` 染色（5 仓 SQL 均补该列）；聚合查询（`terminationDistribution`）移除该类调用——聚合结果无行租户，强比即同义反复；哨兵行改由「行租户 == 哨兵」推断（否则 `TenantWithShared` 共享行被误判，`JdbcResultQueryRepositoryShadowTest` 首跑即抓到）。
  - 验证：`agent-infrastructure -am` **330 例 0 失败**；`platform` 全反应堆 **10 模块 SUCCESS**。
- **2026-11-02 D13 闭环（JOIN 泄漏面）**：
  - **穷举取代估算**：原清单是「19 文件含 JOIN、J4–J6 待穷举」的起点态；本次去注释后按 `JOIN <table>` + 连接条件窗口逐一判定，收敛为 **platform 15 处具体 JOIN**（13 合规 + 2 平台目录豁免）、**RAG 24 处**（检索 19 + 语料管理 5，全复合键）、`deploy/sql` 5 处（全豁免）。清单从「文件级」细化到「行级 + 谓词形态 + 染色覆盖」。
  - **抓到唯一真实缺口 J10**：`JdbcTraceFactRepository#readToolOperations` 是 `att.operation_id = op.operation_id` 单键 JOIN + `WHERE op.run_id = ?`，主表与从表都无显式租户谓词，完全依赖 RLS。已补 `att.tenant_id = op.tenant_id` 与 `op.tenant_id = ?::uuid`。该缺口的后果不是「多返回几行」而是**指标口径污染**（他租户 attempt 计入本租户失败率/超时率），比主表泄漏更难发现。
  - **⚠️ 修掉染色同义反复（影响全部已接仓库）**：原 `shadowTaint` 用**行租户**当主体（`SubjectContext.forTenant(rowTenantId)`），策略引擎必然算出 `TenantOnly(行租户)` → 逐行比对恒通过。测试替身返回常量 filter 掩盖了它。现改为 `shadowTaint(type, subjectTenantId, rowTenantId)`：主体=查询租户、归属=行内 `tenant_id`；4 仓同步改签名，并加**真实策略引擎**回归用例（主体 A + 行 B → 必须违例）。
  - **JOIN 从表二次染色**：J1/J2/J3 三条 JOIN 查询投影补 `join_tenant_id`（J3 用 `MAX(av.tenant_id)::text`），返回路径对从表再染一次；`LEFT JOIN` 空值跳过（不误判）。
  - **守卫 `JoinTenantPredicateArchitectureTest`**：①每个 JOIN 连接条件窗口必须含 `tenant_id`；②新从表未登记即红；③台账无死条目；④3 条扫描器自证（单键 JOIN 必须检出 / 复合键不误报 / `platform.*` 豁免）。⚠️ 与 `ScopeBypassArchitectureTest` 的关键差异：**必须保留字符串字面量**（SQL 在文本块里，连字符串一起去掉等于删掉检查对象），故单独实现 `stripComments`。
  - **反证脚本** `scripts/authz-join-fk-counterproof.sh`：A①目标表复合外键是否含 `tenant_id`、A②存量跨租户引用审计、B③补谓词后 JOIN 从表租户全等、B④无上下文 0 行。只读设计（`set_config` 全在 ROLLBACK 事务内）。**未执行**：需有 `study_rag` 的演练库凭据。
  - **⚠️ J3 首版 SQL 在活库上直接报错**：`MAX(av.tenant_id)::text` → `function max(uuid) does not exist`（PG 没有 `max(uuid)` 聚合）。已改 `MAX(av.tenant_id::text)`。**教训：影子单测用 `NoConnectDataSource`，SQL 语法/列解析根本测不到** —— D13 的 4 条改动语句已全部在 `agent_dev`（`app_agent`，`BEGIN ... PREPARE ... ROLLBACK`，零写入）上做过解析校验，输出 `ALL_PREPARE_OK`。
  - 验证：`agent-infrastructure -am` **337 例 0 失败**（+7）；`platform` 全反应堆 **10 模块 SUCCESS**（platform-bootstrap 137 例，28 例无 DB 连通性 IT 跳过）。
  - **残余**：约 55 个 `Jdbc*Repository` 仍未接 Gate（`AUTHZ_MISMATCH=0` 不可外推）；守卫只扫 `platform/`，RAG 与 `deploy/sql` 靠清单人工登记；`JdbcTraceFactRepository` 其余查询仍是隐式租户（只 `run_id`），属 D5/D12 面。
- **2026-11-02 P1 上线装配（接缝插电 + 采样）**：
  - **⚠️ 先修一个「必然假绿」的结构缺陷**：D2–D7 的能力全在、单测全绿，但**没有任何装配把 Gate/reporter 交给仓库** —— 5 仓的 `scoped`/`reporter` 恒为 `null`、`shadowEnabled` 恒为 `false`。若不修，P1 出口条件「≥2 周 `AUTHZ_MISMATCH=0`」会在**一次都没跑过**的情况下达成。
  - 新增 `AuthzShadowConfiguration`（`platform-bootstrap`，`@ConditionalOnProperty(agent.authz.shadow.enabled=true)`）：装配 `AuthzSamplingPolicy` → `AuthzMismatchMetrics`（`Slf4j`）→ `AuthzShadowReporter` → `AuthzShadowPolicyDefaults.store()` → `RuleBasedPolicyEngine` → `DefaultAccessGate`（`NoOp` 决策日志）→ `ScopedDataAccess` → **`AuthzShadowBinding`**。
  - **单开关语义**：`AuthzShadowBinding` **存在即开启、不存在即关闭**，仓库经 `@Autowired(required=false)` 的 `bindAuthzShadow` setter 接收。刻意不设「Bean 在 + enabled 布尔」双开关 —— 那会造出「Bean 在、开关关」的半开状态，正是 `platform-ops` 配置错挂事故的同型静默失效。
  - 新增 `AuthzSamplingPolicy`：确定性分桶（`(主体,资源,窗口)` 哈希）—— 同一窗口内结论恒定（**mismatch 可复现**），窗口滚动后换样本（**长期覆盖每个组合**，不靠运气）；高敏资源清单 100% 采样。**默认 `rate=0.01`**（12 文 §7.1 R7 原写 0.1% 已上调，理由见 §5.2）。
  - `AuthzShadowReporter#shouldEvaluate` 作为采样闸门（**采样只决定「整次查询要不要看」；被采到的查询内逐行染色，违例不被二次采样丢弃**）。
  - 新增 `AuthzShadowPolicyDefaults`（10 个已接资源类型，逐条对齐 `policy-mapping.yaml`；`platform.agent_trace_metric` 为基线 `scope=NONE` 的缺口表，期望值仍 `TENANT_ONLY` —— 缺口表的隔离本来就只靠应用层谓词）。
  - **`JdbcAgentMetricRepository` 的平台全域聚合不再比对**（`boundTenantId == null`）：无租户主体只能算 `Unrestricted`，拿租户态主体去比只会得到**恒定假 mismatch**，把真实违例淹掉。该路径归 D12 / P3。
  - **两道新守卫**：①`AuthzShadowWiringTest`（最小容器，无 DB）—— 开启时 **5 仓私有字段真的被填**、关闭时保持 dormant；②`ShadowPolicyCoverageTest` —— 钩子用到的 `resourceType` 未登记策略即红（否则每次查询都 `NO_RULE` 恒定假 mismatch）。
  - 验证：`agent-infrastructure -am` **352 例 0 失败**（+15）；`platform` 全反应堆 **10 模块 SUCCESS**（platform-bootstrap 148 例）。
  - **残余**：①生产/预发尚未真正开启（`AGENT_AUTHZ_SHADOW_ENABLED` 仍默认 `false`），**2 周观察期未开始计时**；②决策日志仍 `NoOp`（`platform.authz_decision_log` 表属 P2 交付，不留「能开但会炸」的假旋钮）；③采样去重仅按 `(主体,资源,窗口)`，未按 `principalId` 细分；④`JdbcAgentMetricRepository#findByTraceId` 走系统连接、**端口签名不带租户主体**，该路径染色已**移除**（原实现用行内 `tenant_id` 当主体 = 同义反复，恒不违例）；要恢复必须由调用方传入主体（D5 后续），在此之前宁可没有染色，也不留一条永远不会红的染色。
- **2026-11-02 P1 活库实证 + 决策日志故障隔离**：
  - **`AuthzShadowLiveIT`（新增，5 例，真连 `agent_dev`）**：解决「机制到底灵不灵」这一在单测里无法回答的问题 —— 单测用替身，SQL 语法、RLS 行为、真实行归属全都测不到（D13 的 `max(uuid)` 事故已证明这点）。四条断言：
    ①**跑得起来**：真实 `RuleBasedPolicyEngine` + 真实连接 + 真实行 ⇒ `shadowSamples>0`；
    ②**不误报**：主体=行租户 ⇒ `mismatch=0` 且 `taint=0`、`Unrestricted=0`（与 D1 基线全 `TENANT_ONLY` 一致）；
    ③**真有检出力**：主体换成另一租户去读本租户的行 ⇒ `mismatch=1` 且逐行违例数 **等于行数**（逐行而非只报一次）；
    ④**无主体即拒绝**：环境主体路径 ⇒ Gate fail-closed，计 mismatch 而非静默放行。
    另含**仓储端到端**一例：`JdbcAgentQueryRepository` 经 `bindAuthzShadow` 绑定后调 `listAgents`，断言钩子真的被触发 —— 与 `AuthzShadowWiringTest`（无 DB、只验字段被填）互补：前者证「调用会发生」，后者证「装配会接线」。
    - 租户由**活库动态发现**（按 agent 行数降序取前二，断言至少两个有数据的租户），不硬编码 UUID；`IT_PG_URL` 未设置时跳过。
    - 运行配方：`IT_PG_URL=<jdbc url> IT_PG_USER=app_agent IT_PG_PASSWORD=*** mvn -o -pl agent-infrastructure -Dtest=AuthzShadowLiveIT test`（**角色必须 `NOBYPASSRLS`**，否则「读不到别租户的行」是假的）。
  - **⚠️ 修掉决策日志的一处自相矛盾（潜在全站故障）**：`JdbcAuthzDecisionLog` 的类注释写「任何 SQL 异常只记录、不阻断业务请求」，实现却 `throw new IllegalStateException`。后果分两路：Permit 路径上日志写在**返回 filter 之前** ⇒ 审计表抖动把「放行」变成 5xx；Deny 路径上异常覆盖原始 `reasonCode` ⇒ 调用方再也分不清 403 与 500。
    - 修法**两层都要**：`DefaultAccessGate.record` 统一 try/catch（保证换任何 recorder 实现都不破坏判定）；`JdbcAuthzDecisionLog` 自身也不外抛，并按 60s 窗口限频告警（否则审计库不可用时会以每次判定的频率刷 WARN，把有用告警挤掉）。
    - 同时把 `tenant_id` 的 `?::uuid` 转型去掉：审计流水要能记下**任意形态**的主体标识，转型失败等于「这次判定没留下痕迹」，而它恰恰发生在最需要痕迹的时候。
    - 回归：`JdbcAuthzDecisionLogTest`（连接失败 / 执行失败 / 成功绑定 8 参并 commit）3 例 + `DefaultAccessGateTest` 增 2 例（日志抛异常时 **Permit 仍放行**、**Deny 仍抛原始原因码**）。
  - 验证：`agent-infrastructure` **357 例 0 失败**（+5：门禁 2 + 决策日志 3）＋ **`AuthzShadowLiveIT` 5 例 0 失败**（需 `IT_PG_URL`，已实跑）；`platform` 全反应堆 **10 模块 SUCCESS**（platform-bootstrap 148 例）。
  - **残余**：①`platform.authz_decision_log` 建表 + 开关仍属 P2（且改 `01_platform_ddl.sql` 快照须同步活库，否则 `SchemaDriftIT` 会红 —— 需先在 `agent_dev` 落等价 DDL）；②生产开启与 2 周计时未开始；③`DefaultAccessGate` 的 `NO_SUBJECT` 拒绝路径**无法记决策日志**（没有主体可记），只能靠调用方日志，属已知边界。

- **2026-11-02 P2 起手（决策日志：从「代码在」到「能落库」）**：
  - **表与授权**：`01_platform_ddl.sql` 新增 `platform.authz_decision_log`（9 列 + PK + 2 索引 + 表/列注释）；`03_platform_roles.sql` §2b 新增「应用角色**只写不读**」。
    - ⚠️ **为什么必须显式 `REVOKE SELECT`**：03 里既有的 `ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT ON TABLES TO app_agent` 会让**此后新建的任何 platform 表**自动获得 SELECT —— 只写 GRANT 不写 REVOKE，等于「这次修好了、下次建表又漏」。
    - ⚠️ **为什么 `tenant_id` 是 text 而非 uuid**：审计流水要能记下任意形态的主体标识；写成 uuid 后转型失败 = 这次判定没留下痕迹，而那正是最需要痕迹的时刻。
    - 该表**不挂 RLS**（与其余 platform 域表一致）：隔离靠**授权**而不是行策略 —— 应用角色只有 INSERT，读只能走 `platform_ops`，被攻破的业务进程也枚举不到别租户的判定记录。
  - **开关**：`agent.authz.decision-log.enabled`（默认 `false`）。`AuthzShadowConfiguration` **恒**产出 `AuthzDecisionRecorder`（NoOp / Jdbc 二选一），`DefaultAccessGate` 构造签名不随开关变化。
  - **两道启动期 fail-closed**（`AuthzDecisionLogGuard`）：
    ① 开关开着但容器里没有记录器（= 产生 Gate 的装配没生效）→ **拒绝启动**，因为那意味着一次判定都不会被记录；
    ② 记录器是 Jdbc 实现但**零行写入探测**（`INSERT … SELECT … WHERE FALSE`）失败 → 拒绝启动。
    - ⚠️ 用「真的走一遍 INSERT」而不是 `has_table_privilege`：后者对**列漂移**完全无感 —— 权限检查照样通过，写入却持续失败，而失败被 60s 限频成一条 WARN（静默无审计）。
  - **三方对齐守卫** `AuthzDecisionLogSchemaTest`（3 例）：快照建表 / `JdbcAuthzDecisionLog` 的 INSERT 列集 == 快照列集 / 03 脚本同时含 `GRANT INSERT` 与 `REVOKE SELECT`。
  - 验证：`platform` 全反应堆 **10 模块 SUCCESS**，**2102 例 0 失败**（新增 `AuthzDecisionLogGuardTest` 5、`AuthzShadowWiringTest` +2、`AuthzDecisionLogSchemaTest` 3）。
  - **⚠️ 未完成（阻塞）**：活库 DDL 需**库 owner（`ai_agent`）凭据** —— 实测 `app_agent` 对 `platform` schema `has_schema_privilege(...,'CREATE')=false`。等价 DDL + 授权 + 自检脚本已备。**落库前 `SchemaDriftIT` 会红**（快照有、活库无），这是 fail-closed 的预期表现，不是缺陷。
- **2026-11-02 测试隔离收敛（全量回归最后一条红的根因与修法）**：全量回归由「4 红 → 3 红 → 1 红」，最后一条
  `JdbcStatisticsRepositoryIT.periodSummaryMatchesDailyArrays`（`expected 3L but was 4L`）经实证为**环境前提缺失**而非代码缺陷 ——
  其增量基线被运行实例改写（详见 §8.1 第三种表现）。处置：
  - 新增 `agent-test-support/.../testsupport/db/ItTenantProvisioner`：IT 专用租户的**唯一**幂等供给入口
    （`it-` 前缀硬校验 + tenant/agent/agent_version/execution_snapshot 一次建齐 + `snapshotId` 按租户取）；
    `ItTenantProvisionerTest`（4 例，无 DB）钉死「前缀校验先于任何数据库访问」与「`it-` 不可悄悄改名」。
  - `PgQueueIT` / `MessagingSwitchGuardIT` 删除各自复制的 40 行建租户 SQL，改调共享实现
    （`platform-bootstrap` 增加 `agent-test-support` **test** 作用域依赖）。
  - `JdbcStatisticsRepositoryIT` 由种子租户 `t-a` / `t-b` 改为 `it-stat-a` / `it-stat-b`，9/9 绿。
  - `GovernanceQueryIT` 的 `SELECT snapshot_id FROM execution_snapshot LIMIT 1` 补租户条件 ——
    库里已有多个 IT 专用租户的快照，`LIMIT 1` 取错租户会让复合外键报错且信息不指向真因。
  - 定位手法沉淀（对同类「期望 N 实得 M」通用）：单跑 → 打印行状态 → 逐字复制 SQL 去掉
    `FOR UPDATE SKIP LOCKED` → 插入后空等观察是否被消费 → `pg_stat_activity` 确认常驻实例。
- **2026-11-02 追加：`ItTenantProvisioner` 的「伪幂等」缺陷（真库实证抓出）**：首版用
  `INSERT … SELECT … ON CONFLICT DO NOTHING` 表达「快照幂等创建」，而 `execution_snapshot` 的唯一约束是
  `(tenant_id, snapshot_id)`、`snapshot_id` 每次都是新随机值 ⇒ **冲突永远不会发生**，
  `ON CONFLICT DO NOTHING` 等于没写。连跑 5 轮后同一 IT 租户下攒了 **5 份**快照，
  而快照被 `forbid_snapshot_mutation` 保护、**删不掉** —— 「幂等」退化成「每轮永久泄漏一份」。
  - 修法：改为 `AND NOT EXISTS (SELECT 1 FROM execution_snapshot s WHERE s.tenant_id = v.tenant_id
    AND s.version_id = v.version_id)`（不依赖唯一约束存在）。
  - 守卫：新增 `ItTenantProvisionerIT`（活库，2 例）—— 断言的是**行数不增长**，不是「调用没抛异常」。
    这类缺陷在任何不连库的测试里都看不出来：语句能编译、能执行、不报错，只是每次多写一行。
  - 顺带记录：`it-cap-it-a` 现已累积 **445 份**快照（`CapITBase` 每次建 agent+version+快照，
    快照不可删）。属长期增长项，非正确性缺陷，未在本次处置范围（清理属数据变更）。
- **2026-11-02 追加：`t-a` 数据量增长让「一页装得下」的断言失效（第 3 次同源红）**：
  `GovernanceQueryIT.listFiltersBySubject` 报「列表里找不到 run …」，真因是 `findItem`
  用「取 500 条再筛」的写法，而 `t-a` 的 Run 已累积到 **588 条**（每轮回归还在加约 90 条）⇒
  新造夹具被挤出第一页。修法：
  - `findItem` 改为**沿游标翻页**直到命中（带页数上限与扫描计数，失败信息说明扫了多少条）；
  - 「筛选结果每一行都是数学」改为**拿库里的 `input_payload->>'subject'` 聚合核对**
    （一次查询、更强：直接以库为基准，不依赖列表自身一致性），同时消掉 O(n²) 翻页。
  - ⚠️ 这是同一根因的第三种面貌：**断言隐含了「数据量不会涨」这个没写下来的前提**。
- **实测数据：`t-a` 里 588 条 Run 全部是 IT 夹具且几乎全是 `FAILED`**（`gov-*` 152、`budget-*` 112、
  `pgq-*` 56、`evt-*` 54 …）——它们是**被运行实例真跑失败**的遗留夹具。
  说明「IT 把 Run 造进 worker 轮询的租户」不是个别用例的问题，而是**尚未系统治理的存量**：
  已完成迁移的只有 `PgQueueIT` / `MessagingSwitchGuardIT` / `JdbcStatisticsRepositoryIT` 三处。
- **2026-11-02 追加：`RateLimitIT.newWindowResetsCount` 的「时钟边界 + 远端延迟」抖动**：
  `expected REJECTED but was ALLOWED`。窗口起点由**数据库的** `now()` 决定（`floor(epoch/w)*w`），
  而原实现只靠「等到整秒刚过 100ms 再打两次」来保证两次 check 同窗 —— 一次 check 是
  `INSERT … ON CONFLICT → SELECT` 两次往返，在远端 RDS 上排队时可达数百毫秒，一旦跨秒，
  第二次就落进新窗口被放行。表现为**单跑恒绿、全量偶红**，失败信息却完全指向限流逻辑。
  - 修法：改为「紧贴边界开始 + 跨窗即重试（上限 5 次）」。跨窗是**实验前提不成立**，
    不是被测行为错误；但重试仍有上限 —— 5 次都跨窗说明库延迟异常，那种情况应当红，
    且提示语明确指出「非限流缺陷」。
  - 验证：连续两轮 9/9。
