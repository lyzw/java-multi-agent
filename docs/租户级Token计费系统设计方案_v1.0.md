# 租户级 Token 计费系统 — 设计方案 v1.0

> 日期：2026-11-07 ｜ 状态：待评审 ｜ 适用范围：`platform` reactor（`agent_dev` / `agent_it`），不涉及 `rag` reactor
> 依据：`deploy/sql/platform/01_platform_ddl.sql` 终态快照（当前 69 表 / 43 RLS 表 / 51 策略）、`RunExecutionLoop`、`JdbcBudgetRepository`、`JdbcPlatformTenantRepository` 等实测代码

---

## 0. 一句话结论与关键决策

**结论：不新建一套平行账本。** 现有系统已有「金额预算」三表（`budget_account` / `budget_reservation` / `usage_record`）与完整的「预留 → 调用 → 结算」骨架；本次只补上缺失的一层：**以 token 为单位的租户周期额度**，把额度配置放在平台域、实时计数放在租户域、判定放在模型调用前的原子 SQL 里，计量事实继续复用 `usage_record`。

### 0.1 现状实测（决定设计的 6 个事实）

| # | 事实 | 位置 |
|---|---|---|
| 1 | `usage_record` 已逐模型调用记录 `input_tokens / output_tokens / cost_amount`，幂等键 `(tenant_id, operation_id)` 有部分唯一索引 `idx_usage_operation_once` | `01_platform_ddl.sql:2845-2868, 4326` |
| 2 | `platform.tenant_quota` 已有 `agent_limit / run_concurrency_limit / budget_limit` 与完整 CRUD，但**执行侧从未接通**（`ConfigReadService` 明确标注 `agent.tenant.max-concurrent-runs` 为 `effective=false`） | `01_platform_ddl.sql:1193`；`ConfigReadService.java:138` |
| 3 | 金额预算已有原子条件 UPDATE + `budget_no_overspend` CHECK + 终态不可迁出 trigger，`RunExecutionLoop` 每轮调用都有 `reserve → complete → settle/release` 三明治 | `JdbcBudgetRepository.java:258-267`；`RunExecutionLoop.java:685-748` |
| 4 | 子 Agent 调用（`WorkerChildAgentRunner:271-283`）**完全不计量、不预留**；`usage_record.step_id` 恒为 NULL；控制台 SSE 走独立端口 `ChatStreamGateway` | `ObservableModelGateway.java:64-84` |
| 5 | 上游不回 usage 时适配器记 0（`asLong(0)`），既不报错也不估算 —— 硬配额若只依赖 usage 回传会被绕过 | `OpenAiCompatibleModelGateway.java:363-369` |
| 6 | `operation_id = UUID.nameUUIDFromBytes(runId + "|" + attempt + "|" + ordinal)`；`attempt` 每次领取都 +1（重投 / 崩溃接管 / HITL RESUME） | `RunExecutionLoop.java:1239-1243`；`JdbcRunClaimRepository.java:166-180` |

### 0.2 八条关键决策（先拍板）

| # | 决策 | 理由 |
|---|---|---|
| D1 | 额度单位 = **token 数**（`input + output`），与现有金额预算 **并列、取更严者** | 用户要求 token 额度；金额预算是另一维度（单价、汇率、成本），合并会破坏既有对账口径（P7 §成本指标明确「已计量 / 已结算分开呈现」） |
| D2 | 配置表放 `platform` 域（无 RLS，`platform_ops` 写），实时计数表放 `public` 域（FORCE RLS，`app_agent` 读写） | 平台对租户的管理关系属 platform 域；Worker 绝不能持 `platform_ops` 高权限连接跑业务循环 |
| D3 | 额度值**投影**到周期账户行，Worker 预扣时同一条 SQL 即可读到 `limit_tokens`，**无本地缓存** | 遵守 `EffectiveConfigResolver` 的「无缓存、每次读 DB」纪律；避免多实例额度分裂，且零额外查询 |
| D4 | 控制模型 = **调用前按上界预扣 → 调用后按真实 usage 实结 → 失败释放** | 调用后才知道用量，那时额度已花出去；现有金额预算已验证这套语义 |
| D5 | 判定与扣减必须是**单条原子条件 UPDATE**，表级 CHECK 作最后防线 | 沿用 `budget_no_overspend`（R-007）与 `JdbcRateLimiter` 的成熟范式；禁止 check-then-act |
| D6 | 幂等键沿用 `operation_id`；**累计量以账户行为准**，不依赖跨 attempt 幂等 | MQ 是 at-least-once，`attempt` 会变；账户行不可被「重放」重复增加 |
| D7 | 超限默认 **REJECT**（fail-closed 拦截 + 告警 + 审计），另设影子态与预警阈值 | `BUDGET_EXHAUSTED` 已有 `commitFailure` 收敛范式；v3.1 §1464 约定 `QUOTA_EXCEEDED → 429` |
| D8 | 新增 3 张表（1 platform + 2 public），**不改 `usage_record` 结构** | 计量事实表已够用；只补 `step_id` 写入与子 Agent 覆盖；结构计数 69→72 / 43→45 / 51→53 |

### 0.3 与既有金额预算体系的关系

```
              ┌──────────────── 同一处模型调用 ────────────────┐
  金额维度     BudgetPort.reserve/settle  → budget_account(CNY) → usage_record(事实)
  token 维度   TokenQuotaPort.reserve/settle → tenant_token_account(tokens) ─┘ 读取同一 operation_id
  判定顺序     两道闸门独立，任一触顶即停（取更严者），都在 RunExecutionLoop 同一位置
```
金额链路继续负责「写 usage_record + 成本计价」；token 链路只负责「额度账户 + 预留流水」。**不重复写 usage_record**，避免撞 `idx_usage_operation_once` 造成账目静默丢失。

---

## 1. 目标、范围与计量口径

### 1.1 需求映射

| 需求 | 本方案落点 |
|---|---|
| 租户配置各自的 token 额度 | §3.1 `platform.tenant_token_quota` + §4 配置存储/读取/变更 |
| 统计与任务相关的 token 消耗 | §5 计量粒度（调用 → 步骤 → 任务 → 租户周期） |
| 实时控制不超额 | §6 预扣/结算/超限矩阵 + §7 处理逻辑 |

### 1.2 三层计量粒度与范围

| 层级 | 粒度 | 载体 | 用途 |
|---|---|---|---|
| L1 明细 | **一次模型调用**（`operation_id`） | `usage_record`（input/output/cost）+ `token_quota_reservation`（预留/实结） | 对账、追溯、审计 |
| L2 任务 | **一个 Run**（含根 Agent 与所有子 Agent 调用） | `SUM(usage_record) GROUP BY tenant_id, run_id`；`reservation.run_id` 同键 | 按任务账单、单任务平均 token |
| L3 租户汇总 | **租户 + 周期**（`period_key`） | `tenant_token_account.settled_tokens`（实时权威）+ 报表查询按 `model_id / agent_id / day` 下钻 | 实时控制、额度看板、周期账单 |

**范围（计入）**：租户 Run 中所有模型调用（根 Agent、委派/子 Agent）、工具调用行中以 `tool:` 开头且 token 为 0 的行（只计成本不计 token）。
**范围（本期不计入，须显式标注）**：控制台 SSE 对话（`ChatStreamGateway`，属平台工具而非租户 Run）、RAG 索引/检索侧模型调用（独立 `study_rag` 库、无租户主数据）、平台运维通道调用。
> ⚠️ 控制台 SSE 是**已知旁路**：只要它带着租户上下文消耗模型 token，租户就能绕开配额。故列为 P1 必接项（§10），未接前必须保留补偿控制：仅平台管理员可用 + 现有 `SSE_CONNECT` 限流桶，并在额度看板上标注「不含控制台对话」。

### 1.3 三种口径必须分开呈现

| 口径 | 字段 | 语义 |
|---|---|---|
| 已计量（metered） | `usage_record.input_tokens + output_tokens` | 事实表，调用成功后写入，可能晚于/少于配额侧 |
| 在途占用（reserved） | `tenant_token_account.reserved_tokens` | 调用前按上界占住，未结算 |
| 已结算（settled） | `tenant_token_account.settled_tokens` | 配额硬闸门与账单的唯一权威值 |

`mv_agent_metric_hourly` / `agent_trace_metric` 是**派生宽表**（只采集已收敛 Trace、需手工 REFRESH），DDL 注释明确「不能假装是实时的」——**不得**用作实时配额事实源。

---

## 2. 总体架构与模块划分

### 2.1 分层与数据流

```
【配置面 · 平台运维】 admin-console → PlatformTenantController → PlatformTenantService
      → JdbcPlatformTokenQuotaRepository（platform_ops 数据源 / 无 RLS）
      → platform.tenant_token_quota  ──单事务同步──┐
                                                    ▼
【执行面 · Worker】  RunExecutionLoop / WorkerChildAgentRunner / SSE(P1)
      → TokenQuotaPort.reserve(operationId, upperBound)                 ← 调用前硬闸门
      → ObservableModelGateway.complete(... operationId ...) → 模型供应商
      → TokenQuotaObserver.afterCall → TokenQuotaPort.settle(usage)     ← 统一结算收口
      → public.tenant_token_account / public.token_quota_reservation（FORCE RLS）
      → usage_record（由既有金额链路写入，token 侧只读）
【清扫面】 TokenQuotaSweeper（@Scheduled）：过期悬挂预留 + 周期切换 + 对账
【观测面】 Micrometer Counter → /actuator/prometheus；RunEvent；平台/租户审计
```

### 2.2 模块清单（严格遵守数据库边界：JDBC/SQL 只在 `agent-persistence`）

| 模块 | 新增 / 修改 | 职责 |
|---|---|---|
| `agent-ports` | 新增 `TokenQuotaPort`、`TokenQuotaConfigPort`；`RunEventPort.Types` 加 `TOKEN_QUOTA_WARN` / `TOKEN_QUOTA_EXCEEDED`；`ObservableModelGateway.complete` 增加 `operationId` 参数 | 端口契约；`ReserveOutcome` 复用金额版枚举风格 |
| `agent-core` | 新增 `TokenQuotaEstimate`（上界计算）、`TokenQuotaDecision`；`RunConfigSnapshot` **不改**（额度是租户级实时值，不做 Run 快照固化） | 纯领域逻辑，零外部依赖 |
| `agent-application` | 新增 `TokenQuotaService`（编排 reserve/settle/release）、`TokenQuotaPolicy`（上界/预警线/影子判定）、`TokenUsageReportService`（多维汇总） | 应用服务，依赖端口 |
| `agent-persistence` | 新增 `JdbcTokenQuotaRepository`（RLS 绑定、`TransactionalConnectionHolder`）、`JdbcPlatformTokenQuotaRepository`（platform_ops 数据源、`TenantSessionBinder.clear`）、`TokenQuotaSweeper` | 全部 SQL；条件 UPDATE；幂等插入；清扫 |
| `agent-infrastructure` | 新增 `TokenQuotaObserver`（接入 `ObserverChain.afterCall`）；`HttpAlertWebhook` 不动（用指标告警） | 观测接线 |
| `agent-worker` | 修改 `RunExecutionLoop`（第 694-748 行三明治旁增 token 预扣/释放）、`WorkerChildAgentRunner`（补 operationId + 预扣） | 执行接线 |
| `agent-api` | 修改 `PlatformTenantController/Service`（额度字段）；新增 `TenantTokenUsageController`（用量查询） | 管理面 / 租户只读 API |
| `platform-bootstrap` | `ApplicationConfiguration` 新增 3 个 `@Bean`（不用组件扫描）、灰度开关配置、`@Scheduled` 启用 | 组合根 |
| `admin-console` | `PlatformQuota` 类型 + 配额表单 + 新的用量页签 | 前端 |

### 2.3 依赖方向

`ports ← core ← application ← persistence/infrastructure ← worker/api ← bootstrap`，与 `agent-memory` 收敛时同一纪律。`agent-persistence` 不得反向依赖 `agent-worker`；配额端口只被 worker / api / sweeper 调用。**不新建模块**（记忆模块收敛后不再扩模块边界）。

### 2.4 为什么配置面与执行面分两张表

`platform` 域整体不挂 RLS，`app_agent` 仅有显式 `SELECT`；让 Worker 走 `platform_ops` 数据源等于把凭据库对业务进程敞开。因此：配置写 platform 域（运维通道）；额度值以 `quota_version` 投影进 public 域周期账户行（租户通道，FORCE RLS）；Worker 每次预扣读同一行即拿到当前额度，**配置变更即时生效、无需缓存失效、无需跨 schema 高频读**。

---

## 3. 数据结构（写入 `01_platform_ddl.sql` 终态快照，pg_dump 形状）

> 结构唯一权威源是 `deploy/sql/platform/01_platform_ddl.sql`，**禁止增量 patch 脚本**；主键/FK 用独立 `ALTER TABLE ONLY ... ADD CONSTRAINT`；public 表必须带 `tenant_id` + `FORCE/ENABLE ROW LEVEL SECURITY` + `CREATE POLICY`；platform 表**不得**挂 RLS（`SchemaStructureTest.java:57-98` 会判红）。

### 3.1 配置表（platform 域，无 RLS）

```sql
CREATE TABLE platform.tenant_token_quota (
    tenant_id uuid NOT NULL,
    period_type text DEFAULT 'CALENDAR_MONTH'::text NOT NULL,
    timezone text DEFAULT 'Asia/Shanghai'::text NOT NULL,
    limit_tokens bigint NOT NULL,
    warn_ratio numeric(4,3) DEFAULT 0.800 NOT NULL,
    over_limit_action text DEFAULT 'REJECT'::text NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    effective_from timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    reason text NOT NULL,
    changed_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT token_quota_limit_positive CHECK (limit_tokens > 0),
    CONSTRAINT token_quota_warn_ratio_valid CHECK (warn_ratio > 0 AND warn_ratio <= 1),
    CONSTRAINT token_quota_period_valid CHECK (period_type = ANY (ARRAY['CALENDAR_MONTH','CALENDAR_DAY'])),
    CONSTRAINT token_quota_action_valid CHECK (over_limit_action = ANY (ARRAY['REJECT','SHADOW'])),
    CONSTRAINT token_quota_status_valid CHECK (status = ANY (ARRAY['ACTIVE','DISABLED']))
);
-- PK: (tenant_id)；FK: tenant_id → platform.tenant(id)
-- 索引: (status, effective_from)
COMMENT ON TABLE platform.tenant_token_quota IS '租户 token 周期额度配置。平台运维通道写入，无 RLS；version 用于 CAS 与投影同步，禁止应用侧缓存';
```

`reason` 非空 + `changed_by` 强制留痕（沿用 `tenant_config_override` 的治理形状）；`version` 用于并发配置变更的 CAS 基线（借鉴 `cap_config_pointer` / `tenant_capability_grant.version`）。

### 3.2 周期账户表（public 域，实时计数，FORCE RLS）

```sql
CREATE TABLE public.tenant_token_account (
    account_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    period_key text NOT NULL,
    period_starts_at timestamp with time zone NOT NULL,
    period_ends_at timestamp with time zone NOT NULL,
    quota_version bigint NOT NULL,
    limit_tokens bigint NOT NULL,
    reserved_tokens bigint DEFAULT 0 NOT NULL,
    settled_tokens bigint DEFAULT 0 NOT NULL,
    state text DEFAULT 'OPEN'::text NOT NULL,
    last_exceeded_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT token_account_amounts_non_negative CHECK (
        limit_tokens > 0 AND reserved_tokens >= 0 AND settled_tokens >= 0),
    CONSTRAINT token_account_no_overspend CHECK (
        reserved_tokens + settled_tokens <= limit_tokens OR state = 'EXCEEDED'),
    CONSTRAINT token_account_state_valid CHECK (
        state = ANY (ARRAY['OPEN','WARNED','EXCEEDED','CLOSED']))
);
ALTER TABLE ONLY public.tenant_token_account FORCE ROW LEVEL SECURITY;
-- UNIQUE (tenant_id, period_key)；FK tenant_id → public.tenant(tenant_id)
-- ENABLE ROW LEVEL SECURITY + POLICY tenant_token_account_tenant_isolation
--   USING (tenant_id = public.current_tenant_id())
--   WITH CHECK (tenant_id = public.current_tenant_id())
COMMENT ON CONSTRAINT token_account_no_overspend ON public.tenant_token_account IS 'DB 层最后防线。EXCEEDED 例外：真实用量超过预留上界时须如实入账，但此后任何预扣 SQL 都因 state 条件被拒';
```

**状态语义**：`OPEN`（正常）、`WARNED`（达到预警线，**仍放行**）、`EXCEEDED`（补扣/硬超限，冻结新调用）、`CLOSED`（管理员停用或周期结束）。
**额度收紧保护**：`token_account_no_overspend` 是表级 CHECK，直接把 `limit_tokens` 调到低于 `reserved + settled` 会被 DB 拒绝。故配置写路径必须先校验 `new_limit >= used`，否则返回 `QUOTA_BELOW_CONSUMED`；紧急冻结走 `status='DISABLED'` → 账户置 `CLOSED`（不改 limit），不破坏 CHECK。

### 3.3 预扣流水表（public 域，幂等，FORCE RLS）

```sql
CREATE TABLE public.token_quota_reservation (
    reservation_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    account_id uuid NOT NULL,
    period_key text NOT NULL,
    run_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    model_id text NOT NULL,
    source text DEFAULT 'ROOT_MODEL'::text NOT NULL,
    reserved_tokens bigint NOT NULL,
    settled_tokens bigint,
    state text DEFAULT 'RESERVED'::text NOT NULL,
    release_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    settled_at timestamp with time zone,
    CONSTRAINT token_reservation_positive CHECK (reserved_tokens > 0),
    CONSTRAINT token_reservation_settled_valid CHECK (
        settled_tokens IS NULL OR settled_tokens >= 0),
    CONSTRAINT token_reservation_state_valid CHECK (
        state = ANY (ARRAY['RESERVED','SETTLED','RELEASED'])),
    CONSTRAINT token_reservation_source_valid CHECK (
        source = ANY (ARRAY['ROOT_MODEL','CHILD_MODEL','SSE_CHAT']))
);
ALTER TABLE ONLY public.token_quota_reservation FORCE ROW LEVEL SECURITY;
-- UNIQUE (tenant_id, operation_id)  ← 幂等键
-- 部分索引 (tenant_id, expires_at) WHERE state = 'RESERVED'  ← 清扫
-- 索引 (tenant_id, run_id)  ← 按任务汇总
-- ENABLE ROW LEVEL SECURITY + POLICY 同 3.2
-- 新增 trigger enforce_token_reservation_state_transition（复制
--   enforce_reservation_state_transition 的写法：SETTLED/RELEASED 终态不可迁出）
```

**故意不建 FK 到 `run`**：IT 清理用 `purgeItTenantRuns` 删 Run，勿让子表 FK 阻塞；清理时按 `tenant_id` 显式删除本表行（FORCE RLS 下不带租户的 DELETE 会静默 0 行，工作记忆铁律）。

### 3.4 授权变更（`03_platform_roles.sql`）

```sql
GRANT SELECT ON platform.tenant_token_quota TO app_agent;   -- 惰性建账户时 INSERT ... SELECT 需要
```
`platform_ops` 的 DO 块动态遍历 platform 域，自动覆盖新表；`app_agent` **不获得任何写权限**。
⚠️ `03_platform_roles.sql:221` 的 `ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT` 只对**脚本执行之后**新建的表生效，而初始化顺序是 DDL 先、角色脚本后，故必须显式加上面这一行；同时业务 SQL 一律带 `WHERE tenant_id = ?::uuid`，并用**结果行的 `tenant_id` 做染色**（不得用查询绑定值自证）。

### 3.5 对既有表的两处「非结构性」修复

1. `JdbcBudgetRepository.insertUsageOnce` 的 INSERT 列清单补 `step_id`（当前恒 NULL）→ 才能按步骤追溯。
2. `ObservableModelGateway` 统一收口后，子 Agent / SSE 的调用由 `BudgetPort.recordModelUsage(...)` 补写 `usage_record`，并带上 `source` 语义；`model_id LIKE 'tool:%'` 的行不计入 token 汇总。

### 3.6 结构计数同步（漏改即「建库成功但自检红」）

| 位置 | 当前 | 改后 |
|---|---|---|
| `01_platform_ddl.sql` 表数 | 69 | **72** |
| FORCE/ENABLE RLS 表数 | 43 | **45** |
| `CREATE POLICY` 数 | 51 | **53** |

必须同步：`scripts/init-db.sh:139 EXPECT_TABLES` 与 `:148/:151` 的 43/51；`scripts/it-env.sh:184-185` 的显示值与下界（建议把 `-lt 68` 改成与 `init-db.sh` 一致的精确 `-ne`）；`scripts/dev-it-db.sh:121-144` 的清理残留数组加入 `token_quota_reservation`（排在 `'run'` 之前）；`deploy/sql/README.md:15-18`；`docs/集成测试（IT）运行指南.md:120,393,399`。另需在 `SchemaStructureTest.REQUIRED_SECURITY_MECHANISMS` 加入 `token_account_no_overspend`。

---

## 4. 租户额度配置：存储、读取、变更

### 4.1 存储与配置面读取

- **存储**：`platform.tenant_token_quota` 每租户一行（`tenant_id` PK），字段见 §3.1。种子 `02_platform_seed.sql` 在 t-a / t-b「三处齐」旁补初始额度行；新租户由 `JdbcPlatformTenantRepository.provisionTenant` 在同一事务内插入默认额度（把「三处齐」扩为「四处齐」，并纳入 `scripts/check-tenant-registry.sh` 巡检与 `ItTenantProvisioner.provision` 的 IT 造数）。
- **管理面读取**：`GET /api/platform/v1/tenants/{id}/quota` 扩展现有 `QuotaSummary`；`JdbcPlatformTokenQuotaRepository` 走 `platformIdentityDataSource()`（`platform_ops`），`openSystem()` 清空租户上下文。
- **不进 `tenant_config_override`**：那套是「yml 默认 ⊕ DB 覆盖、L3 审批、只能收紧、Run 创建时快照固化」，语义是**静态运行配置**；额度必须实时生效、且由平台侧设定硬上限，塞进去会造成「在途 Run 用旧额度」与两套生效值分裂。租户自助「更严的软上限」列为待确认项（§12）。

### 4.2 执行面读取（关键）

Worker 侧 `JdbcTokenQuotaRepository` 在 `TransactionalConnectionHolder.inTransaction(new TenantContext(tenantId,...))` 事务内：

1. **惰性建账户**（每个周期一次）：
```sql
INSERT INTO tenant_token_account
    (tenant_id, period_key, period_starts_at, period_ends_at, quota_version, limit_tokens)
SELECT q.tenant_id, ?, ?, ?, q.version, q.limit_tokens
FROM platform.tenant_token_quota q
WHERE q.tenant_id = ?::uuid AND q.status = 'ACTIVE'
ON CONFLICT (tenant_id, period_key) DO NOTHING;
```
2. **读当前账户行** `limit_tokens / state / quota_version` —— 与预扣在同一条 UPDATE 的 `RETURNING` 中拿到，无额外查询、无缓存。
3. `period_key` 由应用层按配置的 `timezone` 计算（`CALENDAR_MONTH → "2026-11"`，`CALENDAR_DAY → "2026-11-07"`），所有实例读同一份配置；周期边界由 `period_starts_at/ends_at` 固化，避免重复计算漂移。

### 4.3 写入路径、权限与审批

| 步骤 | 处理 |
|---|---|
| 身份 | 独立平台令牌 `aud=admin-console-api`，`PlatformTenantService` 执行 `security.require(principal, TENANT_QUOTA_UPDATE, "tenant", id)`；权限须登记进 `PlatformRolePermissionMatrix`（未登记默认拒绝） |
| 校验 | `limit_tokens > 0`；`warn_ratio ∈ (0,1]`；`new_limit >= used`（降额保护）；`over_limit_action` 白名单 |
| 写 | CAS：`UPDATE ... SET version = version + 1 WHERE tenant_id=? AND version=? RETURNING version`（0 行 → 并发冲突，重试或报错） |
| 同步 | 同一事务内 `UPDATE tenant_token_account SET limit_tokens=?, quota_version=? WHERE tenant_id=? AND period_key=? AND quota_version <= ?`；账户行不存在则跳过（下次惰性建行会读新配置） |
| 审计 | 写 `platform.platform_audit_event`（action 自定义常量、`ticketScope=TENANT`），`reason` 必填；审计写入 `recordRequired` 语义 fail-closed |
| 生效 | **下一次模型调用预扣即生效**，在途 Run 不快照固化（与静态配置的 R-021 语义刻意不同） |

### 4.4 配置项清单（新增键必须进 `application.yml` + `ConfigReadService.CATALOG`，由 `ApplicationConfigKeyAlignmentTest` 兜住）

| 键 | 默认 | 用途 |
|---|---|---|
| `agent.billing.shadow.enabled` | `false` | 影子态单开关（Bean 存在即开，禁止「Bean 在 + enabled 布尔」双开关） |
| `config.flag.billing-enforced` / `.tenants` | `false` / 空 | 三段式灰度全局开关 + 租户白名单 |
| `agent.billing.upper-bound.input-factor` | `2` | 输入估算安全系数（与金额版 `INPUT_TOKEN_ESTIMATE_SAFETY_FACTOR` 对齐） |
| `agent.billing.usage-missing-floor-tokens` | `1024` | usage 缺失时的保底结算 token 数 |
| `agent.billing.reservation-ttl-seconds` | `900` | 悬挂预留超时 |
| `agent.billing.sweeper.interval-seconds` | `60` | 清扫周期 |
| `agent.billing.warn.event-enabled` | `true` | 预警事件/审计开关 |

所有值在 `application.yml` 中**加引号**（项目约定）。

---

## 5. 用量统计

### 5.1 采集点与职责分工（避免双写）

| 调用来源 | 谁写 `usage_record` | 谁做 token 预扣 | 谁做 token 结算 |
|---|---|---|---|
| 根 Run 模型调用 | 既有 `JdbcBudgetRepository.settle` | `RunExecutionLoop`（第 694 行旁） | `TokenQuotaObserver.afterCall` |
| 子 Agent 模型调用 | `ObservableModelGateway` → `BudgetPort.recordModelUsage` 补写 | `WorkerChildAgentRunner` | `TokenQuotaObserver.afterCall` |
| SSE 对话（P1） | 同上 | `ChatStreamGateway` 发起前 | 末帧 usage 或保底结算 |
| 工具调用 | 既有金额链路（`model_id LIKE 'tool:%'`） | 不预扣（token=0） | 不计入 token 汇总 |

`ObservableModelGateway.complete(tenantId, runId, step, request)` 扩展为带 `operationId`，`ObserverChain.afterCall` 由此拿到幂等键与 `response.usage()`，实现「一处结算收口」，同时堵住子 Agent 旁路。

### 5.2 幂等与跨 attempt

- 同一 `operation_id` 的唯一索引保证「同一次逻辑调用只占用一次额度」；重复投递返回 `REPLAYED`，账户不变。
- `operation_id` 含 `attempt`，跨 attempt 是**不同的逻辑调用**（provider 侧确实发生了两次调用），账户累计按 `settled_tokens` 如实增加，不依赖跨 attempt 幂等 —— 这与 `RunDispatchConsumer` 的「UNKNOWN_RESULT 丢弃不重投」共同构成不重复扣费保证。
- 结算与 `usage_record` 写入处于同一事务语义下；`token_quota_reservation` 终态不可迁出 trigger 是「不重复结算」的 DB 级保证。

### 5.3 汇总查询（供报表，非实时闸门）

```sql
-- 按任务
SELECT run_id, sum(input_tokens) AS in_tokens, sum(output_tokens) AS out_tokens,
       sum(input_tokens + output_tokens) AS total_tokens, sum(cost_amount) AS cost
FROM usage_record
WHERE tenant_id = $1 AND created_at >= $2 AND created_at < $3
  AND model_id NOT LIKE 'tool:%'
GROUP BY run_id;

-- 按租户周期 / 模型下钻（账单）
SELECT date_trunc('day', created_at) AS day, model_id,
       sum(input_tokens + output_tokens) AS total_tokens
FROM usage_record
WHERE tenant_id = $1 AND created_at >= $2 AND created_at < $3
GROUP BY 1, 2;
```
建议补索引 `idx_usage_record_tenant_created (tenant_id, created_at)`（不影响表数）。**实时控制只认 `tenant_token_account.settled_tokens`**，报表查询只用于展示与对账；两者差额由 `reserved_tokens` 与「结算延迟窗口」解释。

### 5.4 usage 缺失的兜底（必须处理，否则配额可被绕过）

现状 `asLong(0)` 会把未回 usage 的调用记成 0 token。处理策略：
1. **事实表保持真实**：`usage_record` 仍按 provider 返回值写（含 0），不伪造事实。
2. **配额侧保底结算**：`settled_tokens = max(actual, floor)`，`floor = max(usage-missing-floor-tokens, 估算输入下界)`；差额记在 `token_quota_reservation` 并打 Micrometer `billing.usage_missing_total{tenant,model}` + WARN 事件。
3. **对账可解释**：报表注明「已计量（metered）」与「已结算（settled）」差异来源，沿用 P7 §成本指标两口径分开的原则。
4. 后续加固：要求供应商回传 usage（SSE 显式 `stream_options.include_usage`），连续 N 次缺失则把该模型置 fail-closed（列为 P2）。

---

## 6. 实时控制

### 6.1 三段式生命周期

```
reserve(operationId, upperBound) ──成功──> 发起模型调用 ──成功──> settle(usage)
        │                                        │                    │
        └──拒绝 → 拦截/告警                       └──失败/超时/取消 ──> release
        （影子态：仍放行但记账）                     （释放占住的上界）
```
控制点必须在 **`RunExecutionLoop` 内紧邻真实调用**处（候选 1），绝不能放在 Outbox/MQ 消费入口：MQ 是 at-least-once、`attempt` 每次领取递增，入口扣费会被重复消息重复触发。

### 6.2 原子 SQL（核心，全部在 `agent-persistence` 同一事务内）

**预扣 —— 顺序是「先幂等插流水 → 再原子扣账户」，扣不到整事务回滚**，避免现有金额链路「先扣后插、冲突再回退」的窗口：

```sql
-- ① 幂等插入（0 行 = REPLAYED，直接返回，不动账户）
INSERT INTO token_quota_reservation
    (tenant_id, account_id, period_key, run_id, operation_id, model_id,
     source, reserved_tokens, expires_at)
VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, ?, ?, ?,
        now() + (? || ' seconds')::interval)
ON CONFLICT (tenant_id, operation_id) DO NOTHING
RETURNING reservation_id;

-- ② 判定与扣减同语句；RETURNING 0 行 = 拒绝
UPDATE tenant_token_account
SET reserved_tokens = reserved_tokens + ?,
    state = CASE WHEN (reserved_tokens + settled_tokens + ?) >= ceil(limit_tokens * ?::numeric)
                 THEN 'WARNED' ELSE state END,
    updated_at = now()
WHERE tenant_id = ?::uuid
  AND period_key = ?
  AND state IN ('OPEN','WARNED')          -- WARNED 只预警不拦截
  AND reserved_tokens + settled_tokens + ? <= limit_tokens
RETURNING account_id, limit_tokens, reserved_tokens, settled_tokens, state;
```
`ReserveOutcome`：`RESERVED / REPLAYED / INSUFFICIENT / ACCOUNT_CLOSED / NO_QUOTA / FAIL_CLOSED / SHADOW_ALLOWED`。

**结算（正常分支，actual ≤ 预留上界，多退少补）**：
```sql
-- ① 预留行终态判定（0 行 = 重复结算，账户不动）
UPDATE token_quota_reservation
SET state = 'SETTLED', settled_tokens = ?, settled_at = now()
WHERE reservation_id = ?::uuid AND tenant_id = ?::uuid AND state = 'RESERVED'
RETURNING reserved_tokens;
-- ② 账户：退回全额预留、只计真实用量
UPDATE tenant_token_account
SET reserved_tokens = reserved_tokens - ?,
    settled_tokens  = settled_tokens  + ?,
    state = CASE WHEN (settled_tokens + ?) >= ceil(limit_tokens * ?::numeric)
                 THEN 'WARNED' ELSE state END,
    updated_at = now()
WHERE tenant_id = ?::uuid AND period_key = ?
  AND state IN ('OPEN','WARNED')
  AND reserved_tokens - ? + settled_tokens + ? <= limit_tokens;
```
**补扣分支（actual > 预留，异常但必须如实入账）**：把账户置 `EXCEEDED`、`last_exceeded_at=now()`，允许 CHECK 的 `OR state='EXCEEDED'` 例外；同时发 `TOKEN_QUOTA_EXCEEDED` 告警与审计。此后所有预扣因 `state IN ('OPEN','WARNED')` 条件被拒。

**释放（失败/超时/取消）**：同事务内把 `reserved_tokens` 减回、预留行置 `RELEASED`；终态 trigger 防重复释放。

### 6.3 上界估算（`TokenQuotaEstimate`）

```
estimatedInput = trimmed.estimatedTokens() + estimateTokens(systemPrompt)
upperBound     = saturatingAdd(estimatedInput * inputFactor, agentConfig.maxTokens())
upperBound     = max(1, min(upperBound, perCallHardCap))
```
与金额版 `reserveModelCost` 同源（`INPUT_TOKEN_ESTIMATE_SAFETY_FACTOR=2`），但单位为 token、必须饱和运算防 long 溢出。上界偏大只会导致**额度提前占满**（结算立即退回），不会导致超发；这是可接受的保守偏差，用 `billing.reservation_overestimate_ratio` 指标监控并调参。

### 6.4 三处控制点接线

| 层 | 位置 | 作用 |
|---|---|---|
| 准入（粗粒度） | `RunCreationService` / `RunController`（现有限流闸旁） | 账户 `CLOSED/EXCEEDED` 时直接返回 **429 `QUOTA_EXCEEDED`**，不让新 Run 进队；**不在这里扣费** |
| 硬闸门（主） | `RunExecutionLoop:694-748` 与 `WorkerChildAgentRunner` | 每次模型调用前 `reserve`；拒绝即 `commitFailure(..., "TOKEN_QUOTA_EXCEEDED")`，与 `BUDGET_EXHAUSTED/MAX_LLM_CALLS` 同层收敛 |
| 结算收口 | `TokenQuotaObserver.afterCall`（`ObservableModelGateway`） | 统一 `settle`，覆盖父子两条路径；`ChatStreamGateway` 在 P1 接入 |

### 6.5 超限处理矩阵

| 场景 | 判定 | 处理 |
|---|---|---|
| 达到 `warn_ratio`（默认 80%） | 预扣/结算 RETURNING `state='WARNED'` | **不拦截**；`TOKEN_QUOTA_WARN` 事件 + Micrometer `billing.quota_warn_total{tenant}` + 可选租户审计，每周期每阈值只告警一次 |
| 预扣不足 `INSUFFICIENT` | 剩余额度 < 本次上界 | **不发起调用**；本次 Run 收敛 `FAILED / TOKEN_QUOTA_EXCEEDED`，释放本 Run 其它在途预留；写 `platform_audit_event` + `RunEvent` + `billing.quota_exceeded_total{tenant}` Counter（Prometheus/Grafana 阈值告警） |
| `ACCOUNT_CLOSED` / `EXCEEDED` | 管理员停用或补扣超额 | 同上，且新 Run 创建返回 429；需运维调额或等新周期 |
| 组件故障 / DB 异常 / 读不到额度 | `FAIL_CLOSED` | **拒绝调用**，绝不降级放行；指标 `billing.quota_fail_closed_total`；沿用 `JdbcRateLimiter.check` 的故障风格 |
| 影子态 | 判定照做、结果只记 | 仍放行调用、仍记账；`billing.quota_would_block_total{tenant}` 与权威账比对，**不得出现「影子期账断档」** |
| 额度下调至低于已用量 | `QUOTA_BELOW_CONSUMED` | 配置写路径拒绝；紧急冻结改 `status=DISABLED` → 账户 `CLOSED` |
| 告警外发 | 首选 Micrometer + `/actuator/prometheus` | `AlertWebhookPort` 目前只适配 CRITICAL DENY，不改动；如需即时外发另立端口扩展（P2） |

> **为什么能「确保不超」**：每次调用前把最坏情况（输入估算×2 + 输出上限）占住，并发下条件 UPDATE 与表级 CHECK 双保险；正常路径下 `reserved + settled ≤ limit` 恒成立。唯一例外是供应商返回超过上界的真实 usage（异常），此时如实入账并立即冻结账户，用告警 + 后续全部拦截兜底，而不是把账做假。

### 6.6 悬挂预留与周期切换

- `TokenQuotaSweeper`（新增 `@Scheduled`，补上现有 `BudgetReconciliationService.reconcileAll` 无生产调度触发的缺口）：
  - 扫描 `state='RESERVED' AND expires_at < now()`；
  - 关联 `tool_operation` / Run 状态为**确定失败或不存在** → 释放；
  - 状态为 `UNKNOWN` → **绝不自动释放**（R-007 / ADR-012 预算处置矩阵），只告警并交人工处置；
  - 周期切换：旧账户置 `CLOSED`、新 `period_key` 由下次预扣惰性创建；旧周期行保留供账单查询。
- 释放/结算 SQL 一律带 `tenant_id`（FORCE RLS 下不绑租户的 DELETE/UPDATE 静默 0 行）。

---

## 7. 关键处理逻辑（伪代码）

```java
// RunExecutionLoop，第 694 行旁（与金额预留并列，两道闸门取更严者）
String operationId = modelCallId(task.runId(), task.attempt(), llmCalls);
long upper = estimate.upperBound(trimmed, systemPrompt, agentConfig.maxTokens());
TokenQuotaPort.Decision d = tokenQuota.reserve(tenantId, task.runId(), operationId, upper);
if (d.blocked() && !d.shadow()) {
    appendEvent(tenantId, runId, RUN_FAILED, Map.of("errorCode", "TOKEN_QUOTA_EXCEEDED"));
    return commitFailure(tenantId, task, "租户 token 额度不足", "TOKEN_QUOTA_EXCEEDED");
}
try {
    response = modelGateway.complete(tenantId, runId, round, request, operationId);
    // TokenQuotaObserver.afterCall 统一 settle（含 usage 缺失 floor）
} catch (RuntimeException e) {
    tokenQuota.release(tenantId, operationId, "模型调用未产出");
    releaseModelCost(tenantId, modelReservation.get());   // 既有金额释放
    throw e;
}
```

```java
// 配置变更（PlatformTenantService，单事务）
quotaRepo.updateWithCas(tenantId, expectedVersion, newLimit, reason, actor);
tokenAccountRepo.syncProjection(tenantId, currentPeriodKey, newLimit, newVersion);
platformAudit.record(action = "TENANT_TOKEN_QUOTA_UPDATE", ticketScope = "TENANT", ...);
```

**失败语义**：`llmCalls++` 只在调用成功后（现有 `:742`），失败的调用既不计数也不占用 token 额度；`UNKNOWN_RESULT` 仍由 `RunDispatchConsumer` ack 丢弃、不重投。

---

## 8. API 与前端

| 端点 | 变更 |
|---|---|
| `GET/PUT /api/platform/v1/tenants/{id}/quota` | `QuotaSummary` / `QuotaRequest` 增 `periodType / timezone / limitTokens / warnRatio / overLimitAction / version`；`PlatformTenantPort.QuotaRow` 与 `JdbcPlatformTenantRepository` 三处 SQL 列清单同步 |
| `GET /api/tenants/self/token-usage?period=&groupBy=` | 新增：当前租户用量（按任务 / 按日 / 按模型），只读、受 RLS、走 `GOVERNANCE_QUERY` 限流桶 |
| `GET /api/platform/v1/tenants/{id}/token-usage` | 新增：平台运维查询，带 `ticketScope` 审计 |
| 429 响应体 | `{ "code": "QUOTA_EXCEEDED", "periodKey": "...", "limit": ..., "settled": ..., "reserved": ... }`，**不含任何模型输入原文**（风险事件只存 detailDigest 的项目纪律） |

前端：`admin-console/src/api/types.ts` 的 `PlatformQuota` 加字段、`TenantDetailPage.tsx` 配额页签加输入与「剩余额度 / 预警线」展示；新增用量页签（折线 + 按任务 Top N）。`adminApi.ts` 的 GET/PUT 路径与整体 payload 透传不变。

---

## 9. 灰度、验证与验收矩阵

### 9.1 三段式灰度（复用现有先例）

| 阶段 | 开关 | 行为 | 放行判据 |
|---|---|---|---|
| S0 影子 | `agent.billing.shadow.enabled=true`，`billing-enforced=false` | 只记账与「本会拦截」计数，不拦截 | `billing.usage_missing_total = 0` 且 `meter_coverage_ratio ≥ 99.9%` 连续 2 周；影子账与 `usage_record` 汇总差额 0 |
| S1 白名单 | `billing-enforced=true` + `.tenants=<it-租户/试点>` | 白名单租户硬拦截，其余影子 | 试点租户无超额、无误拦（`quota_would_block_total` 与人工复核一致） |
| S2 全量 | 全局 `true` | 所有租户硬拦截 | 观察 1 个完整计费周期 |

开关实现照 `AuthzShadowConfiguration`（单开关、Bean 存在即开）与 `CapEnforcementSwitch`（全局 + 租户白名单）；回退时**只关拦截、不停记账**。参考 `scripts/cap-gray-metrics-check.sh` 写 `scripts/billing-gray-metrics-check.sh` 做 E1/E2 判定。

### 9.2 测试清单

- **单测**：上界估算（溢出/负数/0）、周期 key 与时区边界、`ReserveOutcome` 各分支、预警线只告警不拦截、降额保护、`usage=0` 的 floor、影子态不影响放行。
- **IT（连库，`app_agent` 角色验 RLS）**：
  1. 并发预扣：N 线程同租户同时抢额度，`reserved+settled ≤ limit` 恒成立，成功数 × upper ≤ limit，无 CHECK 违规；
  2. 幂等重放：同 `operation_id` 二次 reserve → `REPLAYED`，账户数值不变；重复 settle 不重复加账；
  3. 跨租户染色：用**结果行 `tenant_id`** 断言，A 租户读不到 B 账户/预留行；验隔离必须用 `app_agent`（NOBYPASSRLS），不能用 `ai_agent`；
  4. 补扣超额：`actual > upper` → 账户 `EXCEEDED`、后续预扣全拒、告警事件存在；
  5. 降额：低于已用量被拒 `QUOTA_BELOW_CONSUMED`；`DISABLED → CLOSED` 立即拒新调用；
  6. 覆盖：子 Agent 调用必有 `usage_record` + 预留行；`usage_record.step_id` 非 NULL；
  7. 清扫：过期且终态确定的预留被释放，`UNKNOWN` 保持 `RESERVED` 只告警；
  8. 故障注入：DB 异常 → `FAIL_CLOSED`，调用未发起。
- **架构守卫**：`SchemaStructureTest`（新 public 表有 RLS + tenant_id、platform 表无 RLS）、`SchemaDriftIT`（活库与快照一致）、`DatabaseBoundaryTest`（SQL 只在 persistence）、`SeedTenantWriteArchitectureTest` / `ItTenantRunReclaimArchitectureTest`（IT 一律用 `it-` 租户，`@BeforeAll` 调 `purgeItTenantRuns`，不写 t-a/t-b）、`AuditActionCoverageTest` / `ApplicationConfigKeyAlignmentTest`（新增动作与配置键）。

### 9.3 验收口径

1. `scripts/init-db.sh` 重建空库自检打印 **72 / 45 / 53** 并通过；`source scripts/it-env.sh --probe` 同值；`dev-it-db.sh` 清理数组不报外键错。
2. `source scripts/it-env.sh` + `cd platform && mvn -o clean verify`（11~12 模块同 reactor，不能单独 `-pl`）全绿，基线 2828 testcase 之上新增用例全部通过、0 失败 0 错误。
3. `SchemaDriftIT` 在重建空库与「已按 ALTER 同步的既有库」各跑一次。
4. 连续两轮全量后各 `it-` 租户的 Run 数与账户/预留行数完全一致（跨轮不累积）。
5. `rag` reactor 不受影响（`source scripts/rag-it-env.sh` + `cd rag && mvn -o clean verify` 仍 745 例）。

---

## 10. 实施分期与逐文件改动清单

| 期 | 内容 | 主要文件 |
|---|---|---|
| P0 结构 + 配置 | 3 张表、授权、种子、脚本计数、平台侧 CRUD/权限/审计、前端配额字段 | `deploy/sql/platform/01,02,03,04`、`deploy/sql/README.md`、`scripts/init-db.sh`、`scripts/it-env.sh`、`scripts/dev-it-db.sh`、`docs/集成测试（IT）运行指南.md`、`agent-ports/PlatformTenantPort.java`、`agent-persistence/JdbcPlatformTenantRepository.java`、`agent-api/PlatformTenantController.java` + `PlatformTenantService.java`、`admin-console/src/api/types.ts` + `pages/TenantDetailPage.tsx` |
| P1 计量 + 控制 | 端口/领域/应用服务、JDBC 实现、主循环/子 Agent/装饰器接线、清扫器、灰度开关、指标与事件 | `agent-ports/TokenQuotaPort.java`、`ObservableModelGateway.java`、`RunEventPort.java`、`agent-core/TokenQuotaEstimate.java`、`agent-application/TokenQuotaService.java` + `TokenQuotaPolicy.java` + `TokenUsageReportService.java`、`agent-persistence/JdbcTokenQuotaRepository.java` + `TokenQuotaSweeper.java`、`agent-infrastructure/TokenQuotaObserver.java`、`agent-worker/RunExecutionLoop.java` + `WorkerChildAgentRunner.java`、`platform-bootstrap/ApplicationConfiguration.java` + `application.yml`、`agent-api/ConfigReadService.java` |
| P2 覆盖补齐 + 报表 | SSE 纳入、用量 API/前端页、强制 usage 回传、webhook 扩展、ADR-013 落盘 | `ChatStreamGateway` / `OpenAiCompatibleModelGateway` / `MiniMaxModelGateway`、`TenantTokenUsageController`、admin-console 用量页、`scripts/billing-gray-metrics-check.sh`、`docs/P0/P1/ADR-013_*.md` |

> 建议补一份 **ADR-013**（预算单位 / 价格版本 / 预留结算模型）：v3.1 §ADR-005 曾预留该决策位但从未落盘，落盘 ADR 最大编号为 ADR-012。

---

## 11. 风险与回退

| 风险 | 缓解 |
|---|---|
| 上界估算过大 → 额度提前占满、误拦 | 结算立即退回；`reservation_overestimate_ratio` 监控；`inputFactor` 可配；必要时引入按模型的实测分位数 |
| 供应商不回 usage → 配额被绕过 | §5.4 保底结算 + 指标；P2 改为强制回传，缺失即 fail-closed |
| 子 Agent / SSE 旁路 | P1 统一收口；未接前显式标注范围外 + 补偿控制 |
| 跨 attempt 重复调用 | 账户累计以 `settled_tokens` 为准；`UNKNOWN_RESULT` 不重投；预留终态 trigger |
| 表级 CHECK 与降额冲突 | 写路径 `new_limit >= used` 校验；紧急冻结用 `CLOSED` 不改 limit |
| 悬挂预留永久占额度 | 新增 `@Scheduled` 清扫；`UNKNOWN` 走人工处置，绝不自动释放 |
| 多实例时区/周期分裂 | 时区存配置、所有实例读同一份，无本地缓存 |
| `platform` 域新表误授权 | 只加 `app_agent SELECT` 一行；Worker 不持 `platform_ops` 连接 |
| 计数三处漂移 | init-db / it-env / README / IT 指南一次改齐；`SchemaDriftIT` 双向比对 |
| 回退 | 单开关关闭拦截即恢复原行为；记账不停，账本可后续补算；新表可保留不删（项目无物理删除端点纪律） |

---

## 12. 待确认项（需产品/运维拍板）

1. **计费周期默认值**：自然月（`Asia/Shanghai`）还是自然日？是否支持「开通日起 30 天」滚动窗口？
2. **超限策略**：本期 `REJECT`（Run 收敛 FAILED）；是否需要「挂起等到下个周期/管理员调额后自动恢复」？后者需复用 `WAITING_APPROVAL` 状态机并新增额度唤醒器，成本明显更高。
3. **额度归属**：仅平台运维可设，还是允许租户自助设置**更严**的软上限（若开放，需走 `ConfigOverridePort` 的 L3/TIGHTEN 审批链，且只作为第二道更严闸门）？
4. **控制台 SSE 是否必须纳入**（若租户可通过控制台无限消耗 token，则必须纳入，建议 P1 前置到 P0）。
5. **账单形态**：只出用量报表，还是需要「周期账单 + 超额计费/欠费」？本方案只做到额度控制与用量统计，不含真实支付。
6. **与金额预算的展示口径**：额度看板是否同时展示「剩余 token / 剩余金额」双余额，避免用户困惑于两道闸门。
