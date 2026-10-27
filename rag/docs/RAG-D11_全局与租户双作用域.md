# RAG-D11 全局 / 租户双作用域（语料与字典）

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D11 |
| 版本/状态 | v1.0 / **已实现（真实 PG17 上 3 条反证链 + IT 全绿）** |
| 维护人 | AG-BE 实施 / zhouwei 待评审 |
| 依据 | RAG-D00 §4 租户与身份边界 / RAG-D03 §2 数据模型 / RAG-D01 §2.7 FR-RAG-060~061 / RAG-D07 §7 AT-RAG-018 |
| 关联 | 承接 platform 侧既有约定 `cap_config_release`（V15：可空 `tenant_id` + scope 判别 + 读写分策略）；落地 V7（字典试点）与 V8（语料侧 + 字典收敛）|

---

## 0. 文档定位

### 0.1 本片交付什么、不交付什么

| 本片交付 | 本片**不**交付 |
|---|---|
| 一套**全库唯一**的「什么叫全局」表示法：`scope` 判别列 + 全局哨兵租户 ID | 全局语料的**运营发布通道**（需独立运维数据源与写策略，属第四刀）|
| V7：`rag_dictionary` 双作用域试点（读写分策略、覆盖合并语义）| 管理台对全局语料的治理界面（检索侧可见，管理侧**刻意仍只看本租户**）|
| V8：语料链 7 表 + 字典表统一收敛到哨兵表示 | 全局语料的**索引**（worker 按租户列表重索引，全局桶不在其射程内）|
| `JdbcRetrievalRepository` 读谓词两层同步（RLS ∪ SQL 谓词）| 逐租户差异化权威门槛（仍依赖 platform 下发，RAG-R23）|
| 3 条反证脚本（共 125 项断言）+ 语料/字典全局作用域 IT | 按租户隔离的 HNSW 召回质量测量（见 §5 风险 R-2）|

> ⚠️ **诚实边界**：本片交付的是**数据库层与作用域读写路径**，
> **不是**「平台已能运营全局语料」。今天库里一条全局语料都没有——
> 发布它的能力刻意留在超级用户通道，第四刀才补运维面。

### 0.2 为什么现在做

各租户重复录入同一份 `SOURCE_TYPE` 下拉、同一套公共知识点与教纲片段，
是教辅 SaaS 的典型浪费：平台维护一份、全体租户受益，且**只读**。
但 RAG 库从头到尾是「纯租户隔离」，没有任何跨租户共享的表达能力。

先拿字典表试点（最低风险：一行配置错了只影响下拉框，不影响答案），
验证过读写分策略的真实语义之后，再动语料链。

---

## 1. 决策：全局行 = 哨兵租户 ID + `scope` 判别列

### 1.1 三个候选与实测结论

| 方案 | 结论 | 依据 |
|---|---|---|
| `tenant_id = -1` | **不可用** | 列是 `UUID`，-1 存不进去；且全仓无 `-1` 哨兵先例 |
| `tenant_id IS NULL`（V7 初版） | **结构上不成立**，已回退 | 见 §1.2 三条实测 |
| `tenant_id = 00000000-…-000000000000`（哨兵）+ `scope` | **采用** | 列继续 `NOT NULL`，语料链一律不用改 JOIN/FK |

### 1.2 为什么「可空 tenant_id」在语料侧根本走不通（V8 文件头有实测记录）

1. **复合外键静默失效**：语料链全靠 `FOREIGN KEY (tenant_id, <父 id>) REFERENCES
   … (tenant_id, <父 id>)`。SQL 标准 MATCH SIMPLE 下**任一 FK 列为 NULL 即跳过校验**
   ——一条 `tenant_id IS NULL` 的 chunk 可以指向一个**不存在**的 document，且**不报错**。
2. **等值 JOIN 连不上**：检索 SQL 里 `ON c.tenant_id = ed.tenant_id`；
   `NULL = NULL` 结果是 NULL 而非 true，于是全局语料**一行也召回不到**，
   除非把十几处 JOIN 全改成 `IS NOT DISTINCT FROM`。
3. **主键列不能为空**：`rag_knowledge_prerequisite` 的主键含 `tenant_id`，
   `ALTER TABLE … ALTER COLUMN tenant_id DROP NOT NULL` 被 PostgreSQL 直接拒绝
   （`column "tenant_id" is in a primary key`）——
   「全局知识点的先决关系边」在 NULL 表示法下无法存在。

哨兵表示法让这三处**一律不变**：列继续 `NOT NULL`，等值 JOIN 天然成立，
主键照旧，语义由 `scope` 判别列自证。

### 1.3 唯一的代价，与它的封堵

把「全局」表示成一个**真实可传的 UUID**，就新增了一条攻击面：
调用方把 `app.tenant_id` 设成哨兵，即可以「普通租户」的身份读写全局桶。

| 层 | 封堵 | 证据 |
|---|---|---|
| 数据库 | `rag.rag_current_tenant()` 加固：会话身份等于哨兵 → **RAISE**（SQLSTATE 42501） | 反证脚本 P6-1/P6-2、字典脚本 P12-1 |
| API | `RagRetrievalController#requireTenant` 先挡哨兵（400），再解析 UUID | IT |
| 领域 | `RagTenantContext` 构造器拒绝哨兵租户 | `RagTenantContextTest` |
| 策略 | 写/改/删策略恒为 `tenant_id = rag_current_tenant()`，**不含哨兵分支** | 全部反证脚本 |

四层不是重复劳动：DB 层是承重墙（不可绕过），其余三层让越权请求在到达墙之前
就变成可读的错误，而不是一个「看起来正常」的会话。

---

## 2. 不变式（全库只有一条「什么叫全局」）

```sql
-- 判别列
scope TEXT NOT NULL DEFAULT 'TENANT'

-- 语义不变式：scope 必须与 tenant_id 同向（缺一条就是半全局行泄露）
CHECK ((scope = 'PLATFORM' AND tenant_id = rag_global_tenant_id())
    OR (scope = 'TENANT'   AND tenant_id <> rag_global_tenant_id()))

-- 读写分策略（8 张表同一形状）
读：tenant_id IN (rag_current_tenant(), rag_global_tenant_id())
写：WITH CHECK (tenant_id = rag_current_tenant() AND scope = 'TENANT')
改：USING (tenant_id = rag_current_tenant()) WITH CHECK (同上)
删：USING (tenant_id = rag_current_tenant())
```

覆盖 8 张表：`rag_source` / `rag_edition` / `rag_document` / `rag_chunk` /
`rag_chunk_embedding` / `rag_knowledge_point` / `rag_knowledge_prerequisite` /
`rag_dictionary`。

**不**放开的表：`rag_access_audit`、`rag_answer_citation`、`rag_ingest_job`
（继续单条严格相等策略）。引用与审计是「某租户的一次行为」，本就没有全局形态。

### 2.1 为什么迁移里绝不播种全局行

Flyway 与应用**共用同一个非特权数据源**（`rag_app`：NOSUPERUSER + NOBYPASSRLS），
而写策略按设计不放行全局桶——**应用角色写不出全局行**。
这条「写不出」正是越权闸门本身，所以它不能为了「让种子跑通」而被打开。
全局数据只能走超级用户/运维通道（`deploy/sql/rag/04_rag_seed_global_dictionary.sql`）。

### 2.2 为什么 `ON CONFLICT` 需要非 NULL 的租户值

字典表在 V7 用 `UNIQUE … NULLS NOT DISTINCT` 才让全局行的 upsert 探得到冲突
（普通唯一索引里 NULL 互不相等 → 每保存一次多一行重复全局项）。
哨兵是普通非空值，**天然参与唯一性比较**，V8 因此把它降级回普通唯一约束，
少一个只有 PG15+ 才有的特殊索引。

### 2.3 引用全局片段：一条被 V8 拆掉的复合外键

`rag_answer_citation` 原本是
`FOREIGN KEY (tenant_id, chunk_id) REFERENCES rag_chunk (tenant_id, chunk_id)`。
租户答案引用一条**全局**片段时，citation 行的 `tenant_id` 是租户的、
`chunk_id` 指向的是哨兵桶的行 → 复合外键必然不匹配 → 「引用全局语料」这条功能路
在 DDL 层就被堵死。V8 把它降为单列外键 `FOREIGN KEY (chunk_id) REFERENCES rag_chunk`，
外键仍然**强制存在性**（反证 P9-3 实测：引用不存在的 chunk 依旧被拒），
而「哪个租户的引用」继续由 citation 自己的严格相等 RLS 隔离。

---

## 3. 两层过滤必须同步改（本片最容易做错的一处）

RLS 读策略放开全局后，SQL 里若仍写 `c.tenant_id = rag_current_tenant()`，
全局语料会被这层 AND 条件**静默滤空**——表现与「语料库里确实没有」**完全无法区分**。

| 写法 | 同一份数据、同一会话的实测行数 |
|---|---|
| `= rag_current_tenant()`（旧） | 1（只有本租户） |
| `IN (rag_current_tenant(), rag_global_tenant_id())`（新） | 2（全局 + 本租户） |

因此 **JOIN 侧刻意不改**（哨兵非 NULL，等值 JOIN 成立），**只改 WHERE 侧的
租户谓词**（6 处检索 SELECT + 2 处知识点查询）。

旧注释里「SQL 中显式写 `c.tenant_id = rag_current_tenant()` 仅用于提示优化器，
真正隔离由 RLS 保证」这句是**错的且危险**：它让后来人以为不改 SQL 也没事。
该说法已在 6 个文件中更正，并由 `RetrievalRepositoryIT#sqlPredicateLayerMustKeepPaceWithRls`
把「差值必须是 1」钉成断言。

---

## 4. fail-closed 语义的重述

| | 旧底线 | 新底线（V8 后） |
|---|---|---|
| 表述 | 未建租户上下文 = 全库不可见 | 未建租户上下文 = **只见 `scope='PLATFORM'` 的行** |
| 探针 | `SELECT count(*) FROM rag_chunk` 必须为 0 | `… WHERE scope <> 'PLATFORM'` 必须为 0 |
| 改的位置 | — | `RagStartupSelfCheck#checkFailClosedEmptyCorpus`、`ReindexRunner#failClosedPreflight` |

这不是放宽：可见集合只有平台主动发布的那几行，任何租户私有行仍然一行都看不到。
**探针不同步改的后果**是全局语料一上线，正常库就被判成泄漏而拒绝启动——
假红比假绿更容易被发现，但会让运维从此习惯忽略自检。

`RagStartupSelfCheck` 另加一条：`rag_global_tenant_id()` 必须存在
（不存在 = V8 没跑，此时应用 SQL 会到处报 function does not exist，早爆更好）。

---

## 5. 遗留风险（登记，不在本片解决）

| ID | 风险 | 现状 |
|---|---|---|
| R-1 | 全局语料的发布与索引通道缺失 | 第四刀：平台运维数据源（对齐 `PlatformOpsDataSourceProvider`）+ worker 的全局桶重索引 |
| R-2 | HNSW 索引对租户**不可见**：全局语料会占用 `ef_search` 的候选槽位，大租户召回率可能被公共语料挤掉 | 未测量。上线全局语料前需按租户做召回基线对比（RAG-D08 评测体系） |
| R-3 | 管理台刻意只列本租户语料，全局语料在管理界面**看不见** | 设计而非缺陷（治理动作的作用域必须与写权限一致）；第四刀若开放全局治理面，需要独立的平台身份与审计 |
| R-4 | 逐租户差异化门槛未落地 | 全局行的准入仍用部署级门槛（`rag.retrieval.min-trust-tier`），platform 下发字段后改随请求 |

---

## 6. 证据索引

| 证据 | 位置 | 覆盖 |
|---|---|---|
| 语料侧反证 | `scripts/rag-corpus-scope-counterproof.sh` | 75 项：两层过滤、跨租户不可见、fail-closed 新口径、全局桶写/改/删拒绝、哨兵身份拒绝、CHECK 双向、复合外键、引用全局片段、全局先决边、字典收敛、系统表自证 |
| 字典侧反证 | `scripts/rag-dict-scope-counterproof.sh` | 39 项：读写分策略交互、越权写、不变式、upsert 幂等、系统表 |
| **升级路径** | `scripts/rag-v7-to-v8-retrofit-check.sh` | 11 项：V7 已发布全局字典的库上应用 V8（新库跑不出这个坑）|
| 语料 IT | `RetrievalRepositoryIT`（15 项） | 全局链两租户召回、两层过滤差值、取依据一致、越权写被拒（带反自证对照）|
| 字典 IT | `JdbcDictionaryRepositoryIT`（13 项） | 全局可见、覆盖只在本地、删不动、写不出、哨兵常量与 DB 同源 |
| 单元 | `RagScopeTest` / `DictionaryGlobalScopeMergeTest` / `RagTenantContextTest` | 表示法常量、合并语义与顺序无关、上下文拒绝哨兵 |
| 生产初始化 | `deploy/sql/rag/01_rag_ddl.sql` | 2026-10-27 由 V1→V8 实跑库 `pg_dump` 重新导出；与迁移链产物逐列/逐策略/逐索引零漂移 |
