# RAG-D10 语料数据管理模块

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D10 |
| 版本/状态 | v1.1 / **已实现（真实 PG 上 IT 全绿）** |
| 维护人 | AG-BE 实施 / zhouwei 待评审 |
| 依据 | RAG-D07 §2 准入流程 / §2.4 状态机 T-1～T-12 / §2.6 字段缺口 / RAG-D03 §2 数据模型 / RAG-D02 §3.2 分层 |
| 关联 | 承接 RAG-D07 §2.6「V2 迁移尚未编写」与 §7「审核动作缺独立事件流」两项缺口 |

---

## 0. 文档定位

### 0.1 本片交付什么、不交付什么

| 本片交付 | 本片**不**交付 |
|---|---|
| `rag-management` Maven 模块（8 个模块中的第 8 个）| 检索路径上对 G-1～G-4 的强制过滤（仍属 RAG-R01）|
| 语料来源登记、校验状态机、文档/片段浏览、索引统计的**管理面**能力 | 载体级撤回的完整闭环（V3 已加字段，检索路径尚未消费）|
| V3 迁移：审核留痕字段、载体级状态、版权枚举化、审计动作扩展 | 双人复核的<b>技术强制</b>（依赖主平台 IAM，D-001 未提供 issuer）|
| 59 项自动化测试（50 单元 + **9 IT 真实执行**）+ 既有检索 IT 3 项首次执行 | 管理端点的 HTTP 端到端实测（本机网关拦截，见 L-7）|

> ⚠️ **诚实边界**：
> 本片交付的是**管理面能力与其自动化证据**，
> **不是**「RAG 治理流程已闭环」的结论。
> 准入闸门 G-1～G-4 在**检索查询路径**上仍未强制（RAG-D07 §8 RAG-R01），
> 本片新增的字段与统计只让治理状态**可观测、可管理**，不等于**可强制**。

### 0.2 为什么现在做

RAG-D07 定义了完整的语料治理流程，但**全部停留在 DDL 与文档层面**：
审批动作无管理入口、字段缺口未补、审核留痕无独立事件流。
结果是：治理规则「写了」但无处执行，语料只能靠手工改库。

本片把治理规则落成**可调用、可断言、可留痕**的管理面。

---

## 1. 定位：管理面 vs 检索面

### 1.1 两个方向，两个闸门

```
   采集员                审核员 / 法务                    学生
     │                      │                              │
     ▼                      ▼                              │
 ┌──────────┐   认领/判定  ┌──────────────┐                 │
 │  登记     │ ──────────▶ │  管理面        │                 │
 │(PENDING) │             │ /api/v1/       │                 │
 └──────────┘             │  management    │                 │
                          └───────┬────────┘                 │
                                  │ 来源状态（VERIFIED）      │
                                  ▼                          ▼
                          ┌──────────────┐          ┌──────────────┐
                          │  检索面        │ ───────▶ │  回答         │
                          │ /api/v1/      │  召回    │ (可溯源)      │
                          │  retrieval    │          └──────────────┘
                          └──────────────┘
```

| 维度 | 管理面 | 检索面 |
|---|---|---|
| 回答的问题 | 「库里有什么、哪些还没审、能不能放行」| 「能召回什么」|
| 闸门性质 | **人工**闸门（带留痕与复核）| **自动**闸门（fail-closed）|
| 失败姿态 | 显式报错（400/404/409）| 返回空 + 低分门禁 |
| 端点 | `/api/v1/management/**` | `/api/v1/retrieval` |
| 租户头 | `X-Tenant-Id`（必需）| `X-Tenant-Id`（必需）|

> ⚠️ **两者不能合并**：合并会让「自动闸门」被「人工闸门」的宽松语义污染，
> 或让「人工闸门」被 fail-closed 的空结果掩盖——
> 运维就分不清「语料没审」与「语料不存在」。

### 1.2 分层落点

```
rag-management/
├── core/          领域模型（零框架依赖）
│   ├── VerificationTransition   ← 状态机（T-1～T-12 的唯一权威）
│   ├── CorpusSource             ← 来源管理视图 + 准入计算
│   ├── CorpusDocument           ← 载体管理视图
│   ├── CorpusStatistics         ← 索引统计（区分「已入库」与「可检索」）
│   ├── SourceRegistration       ← 登记命令
│   ├── SourceQuery              ← 查询条件
│   └── VerificationDecision     ← 校验决定命令
├── ports/         CorpusManagementPort（管理能力契约）
├── infrastructure/ JdbcCorpusManagementRepository + RagManagementConfig
└── api/           RagManagementController + ManagementDtos
```

> ⚠️ 依赖方向与既有模块同构：core ← ports ← infrastructure ← api。
> 本模块**不依赖 rag-api**——管理端点与检索端点职责不同、演进节奏不同。

---

## 2. 功能清单

| # | 功能 | 端点 | 对应规范 |
|---|---|---|---|
| F-1 | 语料来源登记 | `POST /api/v1/management/sources` | RAG-D07 §2.3 阶段① |
| F-2 | 来源列表（状态/等级/关键词筛选，过期源默认排除）| `GET /api/v1/management/sources` | §2.3.1 G-1/G-2 |
| F-3 | 来源详情 | `GET /api/v1/management/sources/{id}` | — |
| F-4 | 校验状态流转（认领/通过/拒绝/撤回/重认领）| `POST /api/v1/management/sources/{id}/verification` | §2.4 T-1～T-12 |
| F-5 | 文档载体列表 | `GET /api/v1/management/sources/{id}/documents` | RAG-D03 §4 |
| F-6 | 片段摘要浏览 | `GET /api/v1/management/documents/{id}/chunks` | RAG-D03 §4 |
| F-7 | 索引与准入统计 | `GET /api/v1/management/statistics` | §2.3.1 + §3.4 |
| F-8 | 审计留痕（与状态变更同事务）| 内部 | §2.5 |

---

## 3. 核心设计决策

### 3.1 D-1：状态机作为领域规则，而非服务内 if

**决策**：把 T-1～T-12 编码为 `VerificationTransition` 的白名单表，
在**写库之前**校验。

**理由**：散落在 Controller/Service 的 if 判断无法被完整测试，
且「文档声称有守卫、实际没有守卫」是本项目已发生过的问题
（RAG-D09 §6.2 T-3：规则写在 Javadoc 里，守卫测试并不存在）。

**证据**：`VerificationTransitionTest` 对 **12 条规则逐条给出断言**
（9 条允许 + 3 条禁止），并额外覆盖自转移、null 输入、不可变快照。

### 3.2 D-2：过期是计算属性，不是状态位

**决策**：`CorpusSource.stale(now)` 按 `verifiedAt + validDays < now` 计算；
`rag_verification_status` 仍只有 5 个取值。

**理由**：`rag_verification_status` 枚举里没有 `STALE`。
若把过期持久化成状态，会出现「状态位说 VERIFIED、实际已过期」的自相矛盾记录，
且无法表达「何时过期」。

**实现约束**：过期过滤必须在 SQL 里算（`verified_at + (ver_valid_days || ' days')::interval < now()`），
不能靠枚举比对——`SourceQuery.includeStale` 正是这个语义的开关。

### 3.3 D-3：统计必须区分「已入库」与「可检索」

**决策**：`CorpusStatistics` 同时给出 `totalChunks` 与 `retrievableChunks`。

**理由**：管理方最常见的误判是「片段数 = 可用知识量」。
但 RAG-D07 §2.3.1 的准入闸门意味着**大量已入库片段不可检索**
（未 VERIFIED / 校验过期 / 版权未授权 / 权威等级不达门槛）。
只报总数，运维会看到数量健康却检索不出内容，且无从判断原因。

**额外**：单独统计 `chunksMissingEmbedding`——
片段已入库但向量缺失 = **静默漏召回**（RAG-D03 §1 的原子性约束失效）。

### 3.4 D-4：撤回连带下架载体

**决策**：来源置 `REVOKED` 时，**同事务**内把其下所有 `rag_document`
置 `ver_status='REVOKED'`、`retrievable=FALSE`、`license_status='RESTRICTED'`。

**理由**：来源级与载体级是两个**独立轴**（RAG-D07 §2.3.1 G-0 要求两者同时通过）。
若只改来源状态而留下载体 `retrievable=TRUE`，
任何只看载体级标记的检索路径仍会召回已撤回语料。

**审计动作**：`REVOKED` → `REVOKE`（V1 既有取值）。

### 3.5 D-5：审计与状态变更同事务

**决策**：状态更新与审计插入放在**同一个** `TransactionTemplate` 内。

**理由**：若审计写失败而状态已改，会留下**无痕的状态变更**——
恰好是治理流程最不能接受的情形。任一失败整体回滚。

**动作映射**（V3 迁移新增 `ADMIT`/`REJECT`）：

| 目标状态 | 审计动作 | 理由 |
|---|---|---|
| `VERIFIED` | `ADMIT` | 审核通过（V3 新增，独立事件流）|
| `REJECTED` | `REJECT` | 审核拒绝（V3 新增）|
| `REVOKED` | `REVOKE` | 撤回（V1 既有）|
| `PENDING`/`IN_REVIEW` | `INGEST` | 登记与认领属入库流程 |

> ⚠️ RAG-D07 §7 记载「无『准入审核』action，审核员的判定行为只体现在
> `rag_source` 字段，缺独立事件流」——V3 迁移关闭了这项缺口。

### 3.6 D-6：不用枚举序数做权威等级比较

**决策**：SQL 中用 `trust_tier IN (?, ?, ...)` 显式枚举达标等级，
**不用** `trust_tier >= ?`。

**理由**：PostgreSQL 枚举的 `>=` 按**声明顺序**，
而 `rag_trust_tier` 的声明顺序是
`OFFICIAL_PUBLISHER → OFFICIAL_TUTORIAL → PEER_REVIEWED_PAPER → PREPRINT → UNVERIFIED`，
恰好与 `TrustTier.weight()` 的**降序**语义**相反**——
枚举序数上 `UNVERIFIED` 最大，而它恰恰是**最不权威**的等级。
直接写 `>=` 会让语义**整体反转**：门槛设为 `OFFICIAL_TUTORIAL` 时，
反而只有 `UNVERIFIED` 能通过。

> ⚠️ 这是**极易漏掉**的陷阱：单测很难发现（需真实 DB + 多等级数据），
> 但线上表现为「门槛越严、通过的来源越少」——恰好与预期相反。
> 故 `trustTierInClause` 显式枚举，不依赖枚举声明顺序这种隐式约定。

### 3.7 D-7：管理端口不提供物理删除

**决策**：`CorpusManagementPort` **不提供** `deleteSource` / `deleteDocument`。

**理由**：RAG-D07 §8 INV-4-a 明确「禁止把物理 DELETE 当作撤回手段」——
DELETE 会级联抹除历史引用，破坏可溯源性。撤回的唯一手段是 `REVOKED`。

### 3.8 D-8：显式 `@Qualifier` 消除事务模板歧义

**决策**：检索侧与管理面各定义一个 `TransactionTemplate`，
两侧注入点均用 `@Qualifier` 显式限定。

**理由**：两个同类型 bean 并存时，按类型注入会抛
`NoUniqueBeanDefinitionException`。若依赖「参数名回退匹配」，
一旦 `-parameters` 编译参数被移除就会静默变成歧义失败。

**证据**：`RagManagementConfigTest` 断言「恰好一个 `CorpusManagementPort`」。

---

## 4. V3 迁移：字段补齐

RAG-D07 §2.6 逐条列出了 DDL 缺口并标注「V2 迁移尚未编写」。
V3 补齐其中**管理面必需**的部分：

| # | 变更 | 关闭的缺口 |
|---|---|---|
| 1 | `rag_source` 增加 `review_started_at` / `reviewer_id` | §2.6「认领人/时间」|
| 2 | `rag_source` 增加 `ver_reject_reason` / `ver_decided_at` | §2.6「拒绝理由语义混淆」|
| 3 | `rag_source.license_status` 改枚举 `rag_license_status` | §2.6「TEXT 非枚举」|
| 4 | `rag_document` 增加 `ver_status` / `retrievable` / `revoked_at` / `revoked_reason` / `license_status` | §2.6「载体级撤回无法表达」（RAG-R20）|
| 5 | `rag_access_audit.action` 扩展 `ADMIT` / `REJECT` | §7「审核动作缺独立事件流」|
| 6 | 4 个管理面索引 + 1 个载体可检索部分索引 | 管理面高频路径 |

> ⚠️ **V3 只改结构，不改语义**：检索路径对 G-1～G-4 的强制过滤仍未实现
> （RAG-R01），本迁移不产生「准入闸门已在检索期生效」的结论。

### 4.1 ⛔ 实测踩坑：`ALTER COLUMN TYPE` 与 DEFAULT 的冲突

**现象**（V3 首次执行失败）：

```
ALTER TABLE rag_source
ALTER COLUMN license_status TYPE rag_license_status
USING license_status::rag_license_status
ERROR:  default for column "license_status" cannot be cast automatically to type rag_license_status
```

**机理**：V1 建表时写的是 `license_status TEXT NOT NULL DEFAULT 'PENDING'`，
PG 把它记为默认表达式 `'PENDING'::text`。
执行 `ALTER COLUMN ... TYPE` 时，PG 会**顺带尝试把该默认表达式转成新类型**；
而 `text → enum` **没有隐式转换**（枚举标签与任意字符串之间不存在自动 cast），
于是即便 `USING` 子句给出了显式转换，**默认值这一路仍会失败**。
PG 只在「旧默认可隐式转换到新类型」时才允许省略 `DROP DEFAULT`。

**修复**（三步不可颠倒）：

```sql
ALTER TABLE rag_source ALTER COLUMN license_status DROP DEFAULT;   -- 1) 先摘掉 text 默认值
ALTER TABLE rag_source ALTER COLUMN license_status                  -- 2) 再改类型
    TYPE rag_license_status USING license_status::rag_license_status;
ALTER TABLE rag_source ALTER COLUMN license_status                   -- 3) 最后用新类型重设默认值
    SET DEFAULT 'PENDING'::rag_license_status;
```

**实测结论**：
- 修复前：真实 PG 上复现同款错误（错误码 `42804`，报文与报错完全一致）；
- 修复后：整个 V3 脚本在 **PostgreSQL 18.6** 上**完整执行成功**，
  且由 **Flyway 正式应用**（`Successfully applied 1 migration ... now at version v3`），
  **启动自检全部通过**（pgvector 就绪 / 连接角色非特权 / RLS 生效 / fail-closed）。

> ⚠️ **失败时的残留状态**（供排查参考）：
> PG 的 DDL 事务性使 V3 整体回滚，但**枚举类型 `rag_license_status` 因在
> 独立的事务块中创建而残留**。此时 `rag_source.license_status` 仍是 `text`，
> 而 V1 的 CHECK 约束已被删除——即处于「类型已建、列未转换、约束已失效」的中间态。
> 修复后的脚本对 `CREATE TYPE` 使用 `IF NOT EXISTS` 保护，**可安全重跑**。


---

## 5. 端点契约

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/v1/management/sources` | POST | 登记（`registeredBy` 必填——登记必须留痕）|
| `/api/v1/management/sources` | GET | 列表（`status`/`trustTier`/`keyword`/`includeStale`/`limit`/`offset`）|
| `/api/v1/management/sources/{id}` | GET | 详情 |
| `/api/v1/management/sources/{id}/verification` | POST | 状态流转 |
| `/api/v1/management/sources/{id}/documents` | GET | 载体列表 |
| `/api/v1/management/documents/{id}/chunks` | GET | 片段摘要（limit ≤ 500）|
| `/api/v1/management/statistics` | GET | 索引统计 |

### 错误码语义（治理契约的一部分）

| 码 | 场景 | 理由 |
|---|---|---|
| 400 | 缺租户头 / 非法 UUID / limit 越界 | 配置或调用错误，必须立刻暴露 |
| 404 | 来源不存在 | — |
| **409** | **非法状态转移** | **治理规则被违反，不是服务故障**——运维须能区分「流程走错」与「服务坏了」|

---

## 6. 测试证据

| 测试类 | 项数 | 覆盖 |
|---|---|---|
| `VerificationTransitionTest` | 21 | T-1～T-12 逐条断言 + 自转移 + null + 不可变快照 |
| `CorpusSourceTest` | 8 | G-1/G-2/G-3/G-4 + 过期计算属性 + 构造校验 |
| `SourceRegistrationTest` | 10 | 登记约束 + 决定留痕 + 查询边界 |
| `RagManagementControllerTest` | 8 | 错误码语义 + DTO 映射 + 租户头 |
| `RagManagementConfigTest` | 3 | bean 图无歧义（`@Qualifier` 生效）|
| `JdbcCorpusManagementRepositoryIT` | **9（已执行）** | RLS 隔离 / 状态机落库 / 审计同事务 / 过期过滤 |
| `RetrievalRepositoryIT`（既有） | **3（首次执行）** | 检索期版本/角色/租户隔离 |

**本机实测**：`mvn -o clean verify`（含 `IT_RAG_PG_URL` 指向真实 PG）→
8 模块 BUILD SUCCESS，**148 项测试零失败零跳过**，耗时 29.0s。

> ✅ **IT 已执行**（2026-10-22，PostgreSQL 18.6 + pgvector）。
> 此前版本标注的「IT 未执行」已关闭——本机可直连线上 RDS 的 `study_rag` 库。
> 既有 `RetrievalRepositoryIT`（3 项）亦**首次获得真实执行**。

### 6.1 IT 真实执行抓到的两个缺陷（单测无法发现）

| # | 缺陷 | 后果 | 修复 |
|---|---|---|---|
| **1** | `verified_by` 是 uuid 列，UPDATE 用字符串参数绑定 | 运行期 `column "verified_by" is of type uuid but expression is of type character varying` —— **审核动作全部失败** | SQL 改 `?::uuid` 显式转换 |
| **2** | IT 用 `DriverManagerDataSource(url)` 且凭证内嵌在 URL | pgjdbc 把 `user:pass@host` 整段当主机名（`caused by: rag_app:...@host`），**IT 永远连不上** | 凭证改用 `setUsername`/`setPassword` 独立传参 |

> ⚠️ **缺陷 2 是 IT 基础设施级缺陷**：既有 `RetrievalRepositoryIT` 同样中招，
> 意味着它**从未在任何环境成功执行过**（一直靠 `IT_RAG_PG_URL` 未设置而「跳过」掩盖）。
> 两个 IT 现已统一改为分离凭证（`IT_RAG_PG_USERNAME` / `IT_RAG_PG_PASSWORD`）。
>
> **教训**：IT「因为守门条件不满足而跳过」与「IT 通过」是两回事。
> 守门只保证不在无 DB 环境报红，**不保证 IT 本身能跑**——
> 本次证明了守门条件成立时，IT 仍可能因基础设施缺陷而根本连不上库。

### 6.2 顺带修复的既有缺陷

`ChunkIndexer.PENDING_SQL` 原写 `JOIN rag_source src ON c.source_id = src.source_id`，
但 `rag_chunk` 表**无 `source_id` 列**（V1 三层模型为 source → document → chunk）。
运行期必然抛 `column c.source_id does not exist`——即**重索引功能从未真正跑通**。
已改为经 `rag_document` 中转，与 `JdbcRetrievalRepository` 一致。


---

## 7. 已知限制

| # | 限制 | 影响 |
|---|---|---|
| L-1 | **G-1～G-4 未在检索路径强制**（RAG-R01）| 状态机管得住「管理面」，管不住「检索面」——未校验来源在检索侧仍只被 `ver_status='VERIFIED'` 单一条件过滤，**过期/版权/等级三闸门缺失** |
| L-2 | ~~IT 未在本机执行~~ ✅ **已关闭**（2026-10-22）| 已在真实 PostgreSQL 18.6 上执行：管理 IT 9 项 + 既有检索 IT 3 项全绿 |
| L-3 | 双人复核未技术强制 | 依赖主平台 IAM（D-001 待提供 issuer），当前仅记录 `reviewer_id` |
| L-4 | 载体级状态字段已加，**检索路径未消费** | 单载体撤回后，检索侧仍可能召回其片段（须 JOIN `rag_document` 过滤 `retrievable`）|
| L-5 | 无分页游标，仅 offset | 深翻页性能一般；管理场景可接受 |
| L-6 | 管理面不提供删除能力 | 符合 INV-4-a，但「误登记」只能置 `REJECTED` |
| L-7 | ~~管理端点未经 HTTP 端到端实测~~ ✅ **已关闭**（2026-10-23）| 已在可直连端口（8091 独立实例）完成真实 HTTP 往返：来源列表/详情/载体列表/统计/问题清单/重跑索引/载体审核全部实测，错误码契约 409（非法转移）/400（limit 越界）/404（载体不存在）逐一验证 |

> ⚠️ **L-1 与 L-4 是同一类问题的两面**：本片让治理状态**可管理**，
> 但检索路径尚未**强制消费**这些状态。
> 关闭这两项属 RAG-R01，需在 `JdbcRetrievalRepository` 的 5 条检索 SQL
> 中补齐 G-2/G-3/G-4 与载体级 `retrievable` 过滤。

---

## 8. 后续动作

| # | 动作 | Owner | 依赖 | 目标 |
|---|---|---|---|---|
| Q-1 | 检索路径补齐 G-2/G-3/G-4 + 载体级 `retrievable` 过滤 | AG-BE | 本片 | 关闭 L-1/L-4（RAG-R01）|
| Q-2 | ~~开发机执行管理 IT~~ ✅ 已完成（2026-10-22）| — | — | 关闭 L-2 |
| Q-3 | ~~载体级撤回端点（只下架单个 document）~~ ✅ 已完成（2026-10-23）| — | V3 字段 | 补齐 RAG-R20 |
| Q-4 | 管理面接入主平台 IAM 角色（审核员/法务） | AG-BE | D-001 issuer | 强制双人复核，关闭 L-3 |
| Q-5 | ~~统计端点补充「按来源/版本维度」的下钻~~ ✅ 已完成（2026-10-23）| — | — | 管理看板可用性 |
| Q-6 | ~~管理端点 HTTP 端到端实测~~ ✅ 已完成（2026-10-23）| — | 可直连的端口 | 关闭 L-7 |

---

## 9. 前端管理页（frontend/）

> 交付于 2026-10-22 晚，接入本片的后端管理面。

### 9.1 落点

| 文件 | 职责 |
|---|---|
| `src/api/types.ts` | 管理面类型 + 4 组中文标签映射（校验状态/权威等级/校验手段/版权状态）|
| `src/api/client.ts` | 7 个管理端点函数，统一经 `requestWithTimeout` + `X-Tenant-Id` 头 |
| `src/features/rag/useRagManagement.ts` | 五态 Hook + **409 单独成态** + `allowedVerificationTargets()` |
| `src/pages/rag/RagManagementPage.tsx` | 查询条件 / 来源列表 / 详情与审核 / 统计看板 |
| 路由 | `/rag/management`（侧栏「治理」组 → 「语料管理」）|

### 9.2 前端落地的三条治理纪律

| # | 纪律 | 依据 |
|---|---|---|
| 1 | **只渲染合法的状态流转按钮** —— 撤回是终态（T-10）→ 无任何后继；已拒绝不得直接通过（T-12）→ 只提供「重新认领」 | 让审核员点一个注定被 409 的操作是产品缺陷（同 P4-D10「过期审批不提供批准按钮」）|
| 2 | **409 ≠ 服务故障** —— 呈现为「这一步不被允许」，⛔ 不得提示「刷新后重试」 | 刷新不会让非法转移变合法，只会诱导反复重试注定被拒的操作（同 P4-D15 配置收紧拒绝处置）|
| 3 | **不提供任何删除入口** | 撤回唯一手段是 `REVOKED`；物理 DELETE 会级联抹除历史引用（INV-4-a）|

> ⚠️ `admissible` / `stale` 是**服务端算好的计算属性**，
> 前端**不得**自行推算——租户权威门槛只有服务端知道，
> 前端推算会与真实门槛**静默漂移**。

### 9.3 ⛔ 前端测试抓到的 2 个真实缺陷

| # | 缺陷 | 后果 | 修复 |
|---|---|---|---|
| 1 | `VerificationForm` 缺 `key` → React 复用组件实例（`useState` 初始值只算一次）| 切换来源后表单仍显示**上一个来源**的选项与初始值 → 「给 A 来源的判定被提交到 B 来源」 | 加 `key={selected.sourceId}` 强制重挂载 |
| 2 | `busy` 语义混淆（「请求在飞」与「表单未填完」共用一个 prop）| 未填决定人时按钮文案显示「提交中…」，而实际**什么都没在提交**——用户以为系统在忙 | 拆成 `busy` 与 `canSubmit` 两个 prop |

> ⚠️ 这两个缺陷都是**行为层面**的，静态审查与类型检查都发现不了——
> 只有真正渲染组件并断言交互才能暴露。

### 9.4 前端实测

- `npx tsc --noEmit` → 通过
- `npm run build` → 成功（77 modules；CSS 14.06 kB / JS 292.29 kB，gzip 90.99 kB）
- `npx vitest run` → **24 文件 · 342 项全绿**（既有 325 + 新增 17）
- 三个守卫测试未被破坏：`css.integrity`（新类名必须在 CSS 有定义）、
  `routing.integrity`（新增导航项须有对应 Route）、`role.matrix`（角色区域同步）

---

## 10. 语料维护台扩展（2026-10-23）

> 需求：「页面缺少 RAG 的维护功能」。四块全做：**A 统计下钻 / B 问题定位与重跑索引 /
> C 载体级下架与重新入库 / D 检索侧运维**。

### 10.1 交付清单

| 块 | 能力 | 端点 / 落点 | 状态 |
|---|---|---|---|
| A | 统计下钻：片段角色 / 来源校验状态 / 载体解析状态三张分布表 | `GET /api/v1/management/statistics`（后端早已返回，前端此前丢弃）| ✅ |
| B1 | 维护问题清单：缺向量载体 / 解析失败载体 / 待审核来源 | `GET /api/v1/management/issues`（新增）| ✅ |
| B2 | 重跑索引（只补缺失向量）| `POST /api/v1/management/reindex`（新增，同步、限量 1～2000）| ✅ |
| C | 载体级审核 / 下架 / 重新入库 | `POST /api/v1/management/documents/{id}/verification`（新增）| ✅ |
| D1 | 召回测试（直打真实检索端点）| `POST /api/v1/retrieval`（既有）| ✅ |
| D2 | 词表口径差异（platform 业务词表 × RAG 版本坐标词表）| 前端 + `GET /api/reference` | ✅ |

> ⚠️ **B1 的「stale / 缺向量」是服务端在 SQL 里算的**（含 JOIN 与计数），
> 前端不推算——否则与管理面口径漂移。

### 10.2 C 块：载体级状态机

- 合法路径：`PENDING → IN_REVIEW → VERIFIED`；
  **`PENDING → VERIFIED` 直通 = 409**（非法状态转移，不是服务故障）。
- **载体不得高于来源**：来源未 `VERIFIED` 时，载体置 `VERIFIED` = 409。
- `REVOKED` 是终态：置 `retrievable=false` + 写 `revoked_at` / `revoked_reason` +
  `license_status=RESTRICTED`；**无任何复活路径**。
- 审计动作：`VERIFIED→ADMIT`、`REJECTED→REJECT`、`REVOKED→REVOKE`、`IN_REVIEW→INGEST`。

### 10.3 ⛔ 诚实边界（不得省略）

| # | 边界 | 说明 |
|---|---|---|
| 1 | **单载体撤回 ≠ 学生端搜不到** | 检索路径尚未按 `doc.retrievable` 过滤（RAG-D07 §8 的 RAG-R01 缺口）。**来源级撤回**才连带下架载体、真正阻断召回。UI 因此只写「可检索标记：开/关」，并对 `REVOKED` 载体显式展示该边界块 |
| 2 | 载体级 `evidenceRef` **仅 REVOKED 落库** | `VERIFIED` / `REJECTED` 的证据不持久化（RAG-D07 §2.6 既有缺口）|
| 3 | 载体级 `trustTier` / `validDays` **不产生效果** | 权威等级与有效期只作用于来源层 |
| 4 | 召回测试的分数是**相关性分** | 稠密通道=余弦相似度、词法通道=`ts_rank`；当前嵌入为 `LocalDeterministicEmbeddingPort` 确定性占位（**非 BGE-M3**），它只反映「共享了多少字面」，**不是语义质量结论** |
| 5 | 重跑索引**不解析、不切分、不审核** | 解析失败与待审核的来源/载体需另行处理（UI 明写此边界）|

### 10.4 ⛔ 实测抓到的真实缺陷：检索恒定返回「未找到依据」

> **这是本次最有价值的产出**：维护台做完才发现，**召回测试永远返回 0 条**——
> 不是语料没入库，而是**检索端点本身恒空**。

| # | 缺陷 | 根因 | 后果 | 修复 |
|---|---|---|---|---|
| 1 | **分数门禁作用在错误的量纲上** | 端口契约明写「门禁必须作用于检索器给出的**真实相关性分数**」，但 `JdbcRetrievalRepository` 把 **RRF 融合分**（上限约 2/61 ≈ 0.033）写进了 `score`；门禁阈值 0.2/0.35 是按**相关性量纲**（余弦相似度 / `ts_rank`，0~1）标定的 | 门禁**恒不通过** → 检索永远返回「未找到依据」→ 语料再全也召不回任何东西 | 拆成两个分数字段：`score`=**相关性分**（门禁作用于此）、`fusionScore`=**融合排序分**（RRF，排序作用于此）；版本降权改为**乘法**作用在排序键上（避免「降权」退化成「过滤」）|

- 连带修正：版本降权此前用**减法**作用在同一分数上——在 RRF 量纲下会把所有异版本分数一律压成 0（组内次序全丢），且若作用在相关性分上会把「降权」变成**静默过滤**。
- 回归防线：检索 IT 新增 1 项（强相关命中的相关性分须达默认门禁，且两分数量纲必须不同）；
  编排器单测新增 2 项（门禁读相关性分、排序读融合分）。
- ⚠️ 门禁阈值本身**仍待评测集标定**（RAG-D00 RD-Q2）：重排器（RAG-D05 §L-2）尚未实现，
  当前分母是占位嵌入的余弦相似度，阈值不能当作生产结论。

### 10.5 实测证据

| 维度 | 结果 |
|---|---|
| 后端全量 | `mvn -o verify`（带 `IT_RAG_PG_URL` 直连远程 RDS）→ **8 模块 BUILD SUCCESS · 208 个 testcase 零失败零跳过**（含检索 IT 8 项，真实执行非跳过）|
| 前端全量 | `npx vitest run` → **26 文件 · 397 项全绿**（新增维护台 30 项）|
| 真实 HTTP | 统计三张分布表 / 问题清单 / 重跑索引（扫描 2·处理 2·526ms）/ 召回测试（3 条，相关性分 0.744·0.531·0.456）/ 409·400·404 语义 逐一实测 |
| 浏览器端到端 | 临时实例（5190 前端 → 8091 RAG）走通 A/B/C/D 四块：来源与载体状态徽标、分布表、问题清单三分类、重跑索引回写、载体审核（待校验→审核中，审计落 `INGEST`）、词表口径差异 |
| fail-closed 反例 | 撤回来源的语料 / 待审来源的语料 / 无关问题 / 版本不符 四种查询均 **0 命中** |

> ⚠️ 演示语料是**编造的占位内容**（`scripts/dev-seed-rag-corpus.sql`，幂等可重跑），
> 只为让空库上能立刻看到各状态并走通全流程；**不是真实教材**。

---

## 11. 变更记录

| 日期 | 变更 | 状态 |
|---|---|---|
| 2026-10-22 | 初稿：新增 `rag-management` 模块（core/ports/infrastructure/api 四层）、状态机领域规则、来源登记与校验流转、文档片段浏览、索引统计、V3 迁移（审核留痕字段 + 载体级状态 + 版权枚举化 + 审计动作扩展）、50 项自动化测试；顺带修复 `ChunkIndexer` 引用不存在的 `c.source_id` 列的既有缺陷；本机 `mvn -o clean verify` 全绿（136 项），**IT 未执行**（本机无 PostgreSQL） | 待评审 |
| 2026-10-22 | **v1.1 修复 V3 迁移失败并完成真实 DB 验证**：① 修正 `ALTER COLUMN TYPE` 与 `text` DEFAULT 冲突（须 `DROP DEFAULT` → 改类型 → `SET DEFAULT` 三步），Flyway 正式应用成功（now at v3），启动自检全通过；② IT 真实执行抓到 2 个缺陷——`verified_by` uuid 列未显式转换（审核动作全部失败）、IT 凭证内嵌 URL 导致 pgjdbc 解析失败（既有 `RetrievalRepositoryIT` 因此从未成功执行过），均已修复；③ 管理 IT 9 项 + 既有检索 IT 3 项**首次真实执行全绿**，`mvn -o clean verify` 达 **148 项零失败零跳过**；④ IT 增自清理，验证库内无残留 | 待评审 |
| 2026-10-22 | **v1.2 前端管理页落地**：新增 `/rag/management` 页面（查询条件/来源列表/详情与审核/统计看板 4 区块）、7 个 API 客户端函数、五态 Hook（409 单独成态）、`allowedVerificationTargets()` 合法转移白名单；侧栏「治理」组新增「语料管理」入口，角色矩阵新增 `ragManagement` 区域；⛔ 行为测试抓到 2 个真实缺陷（`VerificationForm` 缺 `key` 致 React 复用实例、`busy` 语义混淆致按钮误显「提交中…」），均已修复；前端 `tsc` + `build` + **342 项测试全绿** | 待评审 |
| 2026-10-23 | **v1.3 语料维护台扩展（A+B+C+D 四块全做）**：后端新增 3 个端点（载体级审核 `/documents/{id}/verification`、问题清单 `/issues`、重跑索引 `/reindex`），`ChunkIndexer` 从 `rag-worker` **下沉**到 `rag-infrastructure`（管理面与 worker 共用同一份「待索引」口径，不复制），既有载体列表响应补 5 个载体级字段；前端新增 `RagMaintenancePanels.tsx`（分布表 / 召回测试 / 词表漂移 / 问题清单 / 载体审核控件）与 30 项行为测试。⛔ **实测抓到真实缺陷：检索恒空**（RRF 融合分被当作相关性分写入门禁，量纲不符 → 门禁恒不通过）→ 拆分为 `score`（相关性分，门禁用）与 `fusionScore`（融合排序分，排序用），版本降权改为乘法；后端 208 项 / 前端 397 项测试全绿，真实 HTTP + 浏览器端到端实测四块全通。详见 §10 | 待评审 |
