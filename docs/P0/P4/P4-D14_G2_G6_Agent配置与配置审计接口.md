# P4-D14 补齐前端缺口 G2 / C6：Agent 配置与配置审计接口

> 阶段：**P4 企业集成**　切片：**D14（收尾补齐）**
> 关联缺口：`G2`（Agent 查询/管理接口）、`C6`（配置变更审计）
> 关联页面：前端 **P18 Agent 配置**（原 `AgentsPage`，`blockedBy="G2"`）、
> **P19 配置变更审计**（原 `ConfigAuditPage`，`blockedBy="C6"`）
> 决策依据：2026-10-21 与 zhouwei 确认的两条方案选项（见 §1）

---

## 0. 背景与动机

FE 规划（FE-01 §5）列出 G1–G13 后端缺口。本轮前已完成 G3–G10 的治理接口
（run 列表 / 审批 / UNKNOWN 处置 / 审计查询，对应 P4-D13），前端 P7–P10 已接线并
**288 项测试全绿**、`tsc` 零错误。

剩余卡点为 **G2** 与 **C6**：

| 缺口 | 阻塞页面 | 此前状态 | 根因 |
|---|---|---|---|
| **G2** | P18 Agent 配置 | 诚实 `Unavailable`（`blockedBy="G2"`） | 无 `GET /api/agents` 等接口 |
| **C6** | P19 配置变更审计 | 诚实 `Unavailable`（`blockedBy="C6"`） | 无配置变更审计写入点 + 前端无查询入口 |

⚠️ **关键事实（实测，非推测）**：`agent` 与 `agent_version` 两张表**早已存在于
V1 迁移**（含种子数据 `{"kind":"tutor"}`），`audit_record` 表与 `AuditQueryPort` /
`AuditPort` 也已存在。故二者缺的**不是数据模型，而是查询/写入接口**——
可以补，且**不臆造任何表或字段**。

---

## 1. 决策记录（与 zhouwei 确认）

| 维度 | 选择 | 理由 |
|---|---|---|
| **G2 范围** | **含发布/版本管理**（写操作） | agent 是 DB 实体（非 yml 声明式配置），`agent_version` 表即为不可变快照设计，发布新版本是既有数据模型的预期用法；与「yml 配置运行时不可改」**不冲突**（那是零保留供应商清单等声明值，非 agent 实体）。 |
| **C6 语义** | **复用审计 + 写查看审计** | 配置运行时不可改，「变更」实际主要是查看/快照留痕；复用 `GET /api/audit?resourceType=config` 最省且一致，并在 `GET /api/config` 写入「查看配置」审计。 |

⛔ **明确不做**：C6 不新建 `config_audit` 独立表（既不必要，也违背「复用 audit_record」的一致性）；G2 不提供「编辑已发布版本」端点（definition 是不可变快照，发布后不得原地改，FR-002）。

---

## 2. 数据模型现状（已存在，仅引用）

```sql
-- V1__baseline_schema.sql
agent (
    agent_id   UUID PK DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES tenant,         -- 租户隔离
    name       TEXT NOT NULL,
    description TEXT,
    created_at / updated_at TIMESTAMPTZ
    UNIQUE (tenant_id, name)
)

agent_version (
    version_id     UUID PK,
    tenant_id      UUID NOT NULL,
    agent_id       UUID NOT NULL REFERENCES agent(tenant_id, agent_id),
    version_number INTEGER > 0,                         -- 租户+agent 内唯一
    definition     JSONB NOT NULL,                      -- 不可变快照
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ
    UNIQUE (tenant_id, agent_id, version_number)
)
```

- `audit_record`（V6）：追加不可改（触发器 `forbid_audit_mutation`），`AuditPort` 负责写入。
- 种子：每个租户有 `agent1` 与 `agent-of-{slug}-only`，`agent1` 含 version 1。

---

## 3. 接口契约（实现必须严格对齐）

### 3.1 `GET /api/agents`（列出，G2 只读部分）
- 鉴权：`agent:manage`（动作**已存在**于 `AuthorizationService.Actions`）
- 限流：`GOVERNANCE_QUERY` 桶（与审批/审计同桶，按租户限流）
- 分页：keyset（复用 `PageCursor`，排序 `created_at DESC, agent_id DESC`）
- 响应：`GovernanceSupport.PageResponse<AgentItem>`

```java
// AgentItem（端口内 record）
record AgentItem(
    @NonNull String agentId,
    @NonNull String name,
    @Nullable String description,
    int versionCount,                 // 该 agent 的版本总数
    int latestVersionNumber,          // 最新版本号（无版本为 0）
    @Nullable String latestDefinition,// 最新版本 definition（JSON 文本）；可为 null
    @Nullable Instant latestPublishedAt
)
```

### 3.2 `POST /api/agents`（创建 agent，G2 写部分）
- 请求体：`{ "name": string, "description"?: string }`
- 鉴权：`agent:manage`（写）
- 行为：在租户内插入 `agent` 行（`agent_name_per_tenant` 唯一约束冲突 → 409）
- 写入审计：`AuditPort.Actions.AGENT_CREATE`（新增常量）
- 响应：`AgentItem`（versionCount=0）

### 3.3 `POST /api/agents/{agentId}/versions`（发布新版本，G2 写部分）
- 请求体：`{ "definition": <JSON> }`（`definition` 为 JSON 对象）
- 鉴权：`agent:manage`（写）
- 行为：`version_number = 当前该 agent 最大版本号 + 1`；插入 `agent_version`
  （definition 为**不可变快照**，发布后不复改）
- 写入审计：`AuditPort.Actions.AGENT_PUBLISH`（新增常量）
- 并发：同一 agent 并发发布 → 版本号竞态 → 用 `version_number_positive` + 唯一约束兜底，
  冲突时抛 `VersionConflictException`（→ 409），前端提示「请刷新后重试」
- 响应：`AgentVersionItem`

```java
record AgentVersionItem(
    @NonNull String versionId,
    int versionNumber,
    @NonNull String definition,       // JSON 文本
    @Nullable Instant publishedAt,
    @NonNull Instant createdAt
)
```

### 3.4 `GET /api/config` 增加「查看审计」写入（C6）
- 在 `ConfigController` 现有 `GET /api/config` 成功返回前，追加：
  `auditPort.recordAccess(tenantId, principalId, AuditPort.Actions.CONFIG_VIEW, "config", null)`
  （`CONFIG_VIEW` 新增常量；`recordAccess` 为 fail-open，不阻断主流程）

### 3.5 前端 P19 复用既有审计查询（C6 前端）
- 不新增 client 方法，直接复用 `queryAudit({ resourceType: 'config' })`
- 表头/筛选与 P10 审计页同源（`AUDIT_ACTION_LABELS` 增加 `CONFIG_VIEW: '查看配置'`）

---

## 4. 分层实现计划

| 层 | 文件 | 内容 |
|---|---|---|
| **ports** | `AgentQueryPort` | `listAgents` / `findAgent`；record `AgentItem`/`AgentVersionItem`；异常 `AgentNotFoundException` / `VersionConflictException` |
| **ports** | `AgentManagePort` | `createAgent` / `publishVersion`；审计动作常量在 `AuditPort.Actions` 新增 |
| **infrastructure** | `JdbcAgentQueryRepository` | 走 `tenantConnection` + keyset 分页；join `agent_version` 取最新版本 |
| **infrastructure** | `JdbcAgentManageRepository` | 事务内插入；版本号自增；显式 commit/rollback（`TenantConnectionLease`） |
| **api** | `AgentController` | 三个端点（列出/创建/发布），鉴权+限流+分页+审计；`CurrentPrincipal` 取租户与主体 |
| **api** | `ConfigController`（改） | `GET /api/config` 追加 config 查看审计 |
| **frontend types** | `types.ts` | `AgentItem`/`AgentVersionItem`/`CreateAgentRequest`/`PublishVersionRequest` |
| **frontend client** | `client.ts` | `listAgents` / `createAgent` / `publishAgentVersion`（复用 `queryAudit` 不走新增） |
| **frontend pages** | `AgentsPage` | 真实列表 + 创建表单 + 发布版本（definition JSON 文本框） |
| **frontend pages** | `ConfigAuditPage` | 复用 `queryAudit({resourceType:'config'})` 真实审计表，诚实空态 |

---

## 5. 必须遵守的工程纪律（沿用 P4-D13）

- ⛔ **RLS 事务铁律**：所有查询走 `tenantConnection(tenantId)`，禁止直接 `dataSource.getConnection()`；
  Repository 绝不自行 commit（借用连接用 `TenantConnectionLease.borrowed()`）。
- ⛔ **JSpecify 纪律**：`@NonNull`/`@Nullable` 仅作 TYPE_USE；嵌套 record 不标 `@NonNull`；
  全限定名 import 后使用简单名（避 9 次踩坑）。
- ⚠️ **限流**：G2 三个端点均走 `GOVERNANCE_QUERY` 桶（120/60s，与治理台共享）。
- ⚠️ **审计 fail-closed 取舍**：`AGENT_CREATE`/`AGENT_PUBLISH` 用 `recordRequired`（失败即抛，阻断主流程）；
  `CONFIG_VIEW` 用 `recordAccess`（fail-open，不阻断查看配置）。
- ⚠️ **跨租户隔离**：SQL 一律 `WHERE tenant_id = ?::uuid`；列表按租户分页，不得返回他租户数据。
- ⛔ **不可变快照**：`agent_version.definition` 发布后不复改；无「编辑版本」端点。

---

## 6. 测试策略（参考 D01 教训：测试分两类）

| 类别 | 验证点 |
|---|---|
| **行为测试**（被直接调用时正确） | 列出返回租户内 agents；创建成功；发布版本号递增；definition 原样落库；限流 429；无 `agent:manage` → 403 |
| **连通性测试**（是否真被调用） | IT 中发布后列表确实出现新版本；`AGENT_PUBLISH` 审计确实写入 `audit_record`；`GET /api/config` 后 `audit_record` 出现 `resourceType=config` |

> ⚠️ D01 教训：仅行为测试通过 ≠ 链路通。G2/C6 必须有**连通性 IT**证明审计真的落库、
> 版本真的出现在列表——否则「消息永不被处理而系统全绿」的形态会重演。

---

## 7. 已知限制（诚实边界，回填时不得删除）

- **definition 为自由 JSONB**：前端「发布新版本」用 `<textarea>` 收 JSON 文本，不做 schema 校验
  （agent-core 无 agent definition 的强 schema 类；自由 JSONB 是 V1 设计现状）。
- **配置运行时不可改 ≠ agent 不可发版**：yml 声明式配置（零保留清单等）不可改；
  agent 是 DB 实体，发版是其预期用法。二者不混淆。
- **C6 是「查看/快照留痕」而非「谁改了哪个配置值」**：因配置运行时不可改，无配置值变更事件；
  C6 落地为「查看配置」审计 + 复用既有操作审计（`resourceType=config`）。
- ⚠️ **审计明细不得含自由文本 `name`（PIPL 红线）**：`AGENT_CREATE` 的审计 `detail` 用 `Map.of()`
  （不落 name）。原因：`JdbcAuditRepository.toJsonWithSensitiveGuard` 对 detail 逐键跑未成年人敏感信息
  扫描（`IdentifyingPattern`），含 UUID/数字串的自由文本 `name` 会被判定为「学号」而**整条拒写**
  （fail-closed 抛 `IllegalStateException`），导致创建整条失败。R-1 可追溯性由
  `actor + action + resourceId(agentId) + 时间戳` 满足，名称经 `audit_record → agent` 关联还原，无需落明细。

---

## 8. 验证结果（实测回填，2026-10-21）

| 项 | 实测 | 结论 |
|---|---|---|
| 后端 G2/C6 IT（`AgentEndpointIT` + `AgentRepositoryIT`） | `AgentEndpointIT` **9/9**、`AgentRepositoryIT` **6/6**，reactor **BUILD SUCCESS** | 全绿 |
| 后端连通性证据 | 日志确认 `AGENT_CREATE`/`AGENT_PUBLISH` 真实落 `audit_record`；`GET /api/config` 后 `audit_record` 出现 `action=CONFIG_VIEW, resource=config/null`（C6-1 连通性成立） | 真落库 |
| 后端负路径 | 无 `agent:manage` → **403**；未知 agent → **404**；重名 → **409**（日志实证） | 边界正确 |
| 前端 `npx tsc --noEmit` | 零错误 | 通过 |
| 前端 `npx vitest run` | **291/291**（基线 288 + 净增 3：P18/P19 由「诚实 Unavailable」改写为真实接线测试 T9–T13） | 全绿 |
| 前端设计纪律自检 `design.spec` | **18/18**（P17 合规页区间仍零编辑控件；P18 管理页的 input/textarea 合法） | 通过 |
| 反证 | `AgentEndpointIT`/`AgentRepositoryIT` 均含 D01 式连通性断言（版本真现列表、审计真落库、跨租户 RLS 隔离） | 链路通 |
