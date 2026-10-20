# RAG-D07 数据源权威性与合规治理

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D07 |
| 版本/状态 | v0.1-draft / **待评审**（未签署） |
| 维护人 | AG-ARC 起草 / zhouwei 批准 |
| 依据 | RAG-D00 §2.2（权威等级与「可被检索」是两件事）、RD-02（fail-closed 准入）、RD-13（租户隔离必须用非特权角色） |
| 数据依据 | `rag/rag-infrastructure/src/main/resources/db/migration/V1__rag_corpus_baseline.sql`（V1 迁移） |
| DDL 基线 | 2026-10-20 11:36 时点版本：**11 表 / 10 条 RLS 策略**（`rag_tenant` 为租户注册表，不启用 RLS） |
| 代码依据 | `rag/rag-core/.../source/TrustTier.java`、`rag/rag-core/.../source/SourceVerification.java` |
| 关联文档 | 主平台 `P0-D00` §4.4（保留期）、`P1-D08` §3.6（E-3 超级用户绕过 RLS）、`P0-D05`（风险登记册口径）、`P4-D04`（保留与删除机制） |

---

## 0. 文档状态与诚实边界（必须先读）

**本节的作用是防止本文被当成「已交付能力说明书」来引用。**
本文档同时包含**已实测结论**与**设计要求**，两者在正文中以不同标记区分。

| 标记 | 含义 | 可否作为验收依据 |
|---|---|---|
| ✅ **已实测** | 本项目已有代码或 DDL 支撑，且有执行过的验证记录 | 可 |
| 🟡 **已实现、未实测** | 代码/DDL 存在，但**本会话未执行验证** | 需先补验证 |
| 🔴 **未实现** | 仅有设计，无代码 | **不可** |
| ⚠️ **待实测** | 依赖外部条件（授权、出版社渠道、PG 实例）才能验证 | **不可** |

### 0.1 本文档的证据来源

| 结论 | 证据来源 | 状态 |
|---|---|---|
| `TrustTier` 五级权重与 `standaloneSupportAllowed()` 语义 | `TrustTier.java` 源码 | ✅ 已实测（`KnowledgePointAndAuthorityTest` 6 项断言通过） |
| `SourceVerification` 状态/手段枚举、`indexable()`、`isStale(validDays)` | `SourceVerification.java` 源码 | ✅ 已实测（同上，5 项断言通过） |
| `rag_source` 字段与 CHECK 约束（ISBN/DOI 自洽、`VERIFIED` 须有证据） | V1 迁移 DDL | ✅ 已实测（RAG-D00 §5：`check_violation` 反证 PASS） |
| `rag_access_audit` 追加写触发器拒绝 UPDATE/DELETE | V1 迁移 DDL | ✅ 已实测（RAG-D00 §5：触发器反证 PASS） |
| 跨租户读/写隔离（非特权角色 `app_agent`） | RAG-D00 §5 实测记录 | ✅ 已实测 |
| **超级用户与 `BYPASSRLS` 角色绕过 RLS** | RAG-D00 §5.1 实测记录 | ✅ 已实测（详见 §1.5） |
| **官方 `postgres:17` 镜像不含 pgvector** | RAG-D00 §5.2 实测记录 | ✅ 已实测（详见 §1.5） |
| 核心不变式 46 项全绿 | 本会话以 JDK 25 + JUnit 6.0.3 手工执行 `rag-core`（41 项）与 `rag-application`（5 项） | ✅ 本会话复跑通过 |
| ⚠️ **V1 基线与 D00 §5 记录不一致** | 当前 V1 为 **11 表 / 10 条 RLS 策略**（新增 `rag_document` 载体层）；RAG-D00 §5 记录的是「**10 表 + 10 条 RLS 策略**」 | ⚠️ **D00 §5 的实测不覆盖新增的 `rag_document` 表**——该表的约束与策略未经那轮验证；D00 需同步更新（§10.4 N-10） |
| 检索期/入库期的权威闸门 | — | 🔴 **未实现**（详见 §8 RAG-R01/RAG-R02） |
| 审计写入、撤回执行、过期扫描 | — | 🔴 **未实现**（`rag-infrastructure` 仅有 DDL，无 Java 源码） |
| RAG-D09（阶段门禁与主平台接入计划）| — | ✅ **已撰写**（本文 §1.2/§10.4/§10.5 对它的引用已消歧）|
| RAG-D01～D06、RAG-D08 | — | ✅ **已撰写**（本文引用处曾标注「待撰写」，为**当时的真实状态**，现已消解）|

> ⚠️ **诚实边界**：本文档 §2～§7 中，除 §0.1 明确标注为「✅ 已实测」的部分外，
> **全部是设计要求**。`rag-api`、`rag-worker`、`rag-bootstrap` 三个模块当前**只有 `pom.xml`，无源码**；
> `eval/` 目录为空；`docs/ADR/` 目录为空。
> 任何以本文档为据声称「RAG 已具备数据源治理能力」的表述，都是不成立的。

---

## 1. 适用范围与前置决策

### 1.1 适用范围

本文档定义**教辅语料（教材 / 辅导资料 / 学术论文）**从采集登记到检索、引用、撤回的治理规则：

| 覆盖对象 | 说明 |
|---|---|
| 语料来源登记与准入 | `rag_source`（作品）、`rag_edition`（版本坐标）、`rag_document`（物理载体）三层的登记、校验状态机、放行条件 |
| 权威等级判定 | `TrustTier` 五级的判定依据、冲突仲裁、独特性约束 |
| 版权与授权 | `license_status` 四取值、短引文模式、授权受限时的表现 |
| 版本与撤回 | 改版坐标系、`REVOKED` 移出索引、历史引用保全 |
| 污染与盗版 | ISBN/DOI、`OFFICIAL_HASH`、抽样比对、`content_hash` |
| 访问审计 | `rag_access_audit` 五种 action 的写入时机、保留期、脱敏要求 |
| 部署安全约束 | 非特权连接角色、启动自检项 |

### 1.2 不在本文档范围

| 非目标 | 归属 |
|---|---|
| 语料解析与切分质量 | RAG-D04（待撰写） |
| 检索算法、召回通道、分数门禁 | RAG-D05（待撰写） |
| 引用生成与接地判定的具体算法 | RAG-D06（待撰写） |
| 评测集与指标 | RAG-D08（待撰写） |
| 与主平台的接入与阶段门禁 | RAG-D09 |
| 版权协议本身的商务谈判 | RD-Q4，见 §10.3 |

### 1.3 前置决策基线

本文档**不得**与 RAG-D00 冲突。直接继承的决策：

| 编号 | 决策 | 本文如何落 |
|---|---|---|
| RD-02 | 语料准入 **fail-closed**：未通过校验的来源不得进入可检索索引 | §2.3 放行条件；§3.3 真值表 |
| RD-13 | 租户隔离：独立 schema + RLS + FORCE，**必须用非特权角色**验证 | §1.5 部署硬性要求；§9 AT-RAG-014/015 |
| D00 §2.2 | **权威等级与「可被检索」是两件事** | §3.3 字段级拆分与真值表 |
| D00 §1.2 | 教材全文对外分发 = 非目标，只做「定位 + 短引文」 | §4.1 版权基线 |
| D00 §3 | 不可妥协原则（无语料不回答 / 过滤早于生成 / 准入 fail-closed） | §3.3、§5.3、§6.1 |
| D00 §2 RD-01 | 五轴复合版本坐标，缺一轴不可入库 | §5.1 改版坐标系 |

> ⚠️ **与主平台 DEC-5 的关系**：主平台 DEC-5「首期不做 RAG」。本子项目的存在即是对其重审，
> 且 RAG-D00 §0 已明确：**主平台 A-008 评测结论尚未产出**，故本项目当前**未与主平台形成端到端业务闭环**。
> 本文档的治理规则在子项目内部有效，**不构成**对 DEC-5 的推翻。

### 1.4 术语与字段口径对照

本文档所有断言必须落到具体字段。下表是**唯一口径**，正文不再重新定义。

| 概念 | V1 DDL 字段 | rag-core 类型 | 取值域 | 约束 |
|---|---|---|---|---|
| 权威等级 | `rag_source.trust_tier` | `TrustTier` | `OFFICIAL_PUBLISHER`(4) / `OFFICIAL_TUTORIAL`(3) / `PEER_REVIEWED_PAPER`(2) / `PREPRINT`(1) / `UNVERIFIED`(0) | 枚举 `rag_trust_tier`；与 ISBN/DOI 自洽由 `rag_source_identifier_chk` 强制 |
| 校验状态 | `rag_source.ver_status` | `SourceVerification.VerificationStatus` | `PENDING` / `IN_REVIEW` / `VERIFIED` / `REJECTED` / `REVOKED` | 枚举 `rag_verification_status` |
| 校验手段 | `rag_source.ver_method` | `VerificationMethod` | `NONE` / `ISBN` / `DOI` / `MANUAL_REVIEW` / `OFFICIAL_HASH` | 枚举 `rag_verification_method` |
| 校验证据 | `rag_source.ver_evidence` | `evidenceRef` | 自由文本（建议结构化前缀） | `rag_source_verified_requires_evidence_chk`：`VERIFIED` 时非空 |
| 校验时间 | `rag_source.verified_at` | `verifiedAt` | `TIMESTAMPTZ` | 同上，`VERIFIED` 时非空 |
| 校验人 | `rag_source.verified_by` | `verifier` | `UUID` | ⚠️ **无外键、无角色表**（见 §2.6） |
| 校验有效期 | `rag_source.ver_valid_days` | `isStale(validDays)` 的入参 | `INT > 0`，默认 365 | `rag_source_ver_valid_days_chk` |
| 版权状态 | `rag_source.license_status` | — | `LICENSED` / `FAIR_USE_QUOTATION` / `PENDING` / `RESTRICTED` | `rag_source_license_chk`（DDL 内为 `TEXT` + CHECK，**非枚举类型**） |
| 物理载体 | `rag_document.carrier` | — | `PDF` / `SCAN` / `EPUB` / `HTML` / `TXT` | `rag_document_carrier_chk`；`SCAN` 是盗版扫描件的主要载体形态（§6.1） |
| 载体解析状态 | `rag_document.parse_status` | — | `PENDING` / `PARSED` / `CHUNKED` / `FAILED` | `rag_document_parse_status_chk`；⚠️ **与 `ver_status` 无任何约束关系**（§2.6） |
| 正文引用（作品级） | `rag_source.object_ref` | — | 对象存储路径/键 | ⚠️ 无格式约束、无签名策略（见 §4.5） |
| 正文引用（载体级） | `rag_document.object_ref` | — | 对象存储路径/键 | **正文引用的权威位置**（§4.1.1 注） |
| 整册校验和 | `rag_source.content_hash` / `rag_document.content_hash` | — | `CHAR(64)` | ⚠️ 两处均可为 NULL，且整册哈希的**输入口径未定义**（§6.5） |
| 租户门槛 | `rag_tenant.min_trust_tier` | `TrustTier.meets(minimum)` | 同权威等级 | 默认 `OFFICIAL_TUTORIAL` |
| 片段 | `rag_chunk.text` / `.role` / `.content_hash` / `.page_number` | `Chunk` | — | `rag_chunk` 无 `trust_tier` 列（缺口，见 §8 RAG-R03） |
| 可检索角色 | 索引 `rag_chunk_retrievable_idx` 的 `WHERE` | `ChunkRole` | 排除 `EXERCISE_ANSWER` / `EXAMPLE_SOLUTION` / `BOILERPLATE` | 部分索引，**只含 role 维度** |
| 引用 | `rag_answer_citation.verbatim_quote` / `.edition_snapshot` / `.verdict` | `Citation` | — | `rag_citation_quote_present_chk` 要求引文非空 |
| 审计 | `rag_access_audit.action` | — | `RETRIEVE` / `INGEST` / `CITE` / `EXPORT` / `REVOKE` | `rag_access_audit_action_chk` |

### 1.5 ⚠️ 生产部署硬性要求：非特权连接角色

**这是已实测事实，不是理论担忧。**

RAG-D00 §5.1 记录：用 `agent` 角色（PostgreSQL **超级用户**）执行时，**租户 1 能看到租户 2 的数据**；
改用非特权角色 `app_agent` 后隔离才生效。这与主平台 `P1-D08` §3.6 的 **E-3** 完全一致：
超级用户与 `BYPASSRLS` 角色会绕过所有策略，`FORCE ROW LEVEL SECURITY` 对它们无效。

由此产生**四项强制要求**（FR-RAG-014）：

| # | 要求 | 说明 | 状态 |
|---|---|---|---|
| 1 | 生产与集成测试连接**必须**使用 `NOSUPERUSER NOBYPASSRLS` 角色 | V1 迁移已对 10 张表执行 `ENABLE` + `FORCE ROW LEVEL SECURITY`，但对特权角色无效 | 🔴 运行账号未实现 |
| 2 | 应用**启动时自检**当前连接角色，是特权角色则**拒绝启动** | fail-closed。理由见主平台 P4-D03 教训：「启动不校验 broker 可达致静默积压」——**表面正常、实际失效**是最危险的失效模式 | 🔴 自检未实现 |
| 3 | 迁移账号与运行账号分离 | 迁移需要 DDL（owner）权限；运行账号只需 DML。`FORCE RLS` 使 owner 也受限，故运行账号**既非 owner 也非超级用户** | 🔴 未实现 |
| 4 | 集成测试一律使用非特权角色 | 主平台 `TenantSessionBinderIT` 同纪律 | ✅ D00 §5 已按此执行 |

**启动自检项清单**（FR-RAG-014 / FR-RAG-015）：

```sql
-- ① 连接角色特权检查：rolsuper / rolbypassrls 必须为 false
SELECT r.rolname, r.rolsuper, r.rolbypassrls, r.rolreplication
FROM pg_roles r
WHERE r.rolname = current_user;

-- ② 运行账号不得是表 owner（owner 虽受 FORCE 约束，但会掩盖策略缺陷）
SELECT count(*) AS owned_tables
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'rag' AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user);

-- ③ pgvector 扩展必须存在 —— ⚠️ RAG-D00 §5.2 实测：官方 postgres:17 镜像不含 pgvector
SELECT extversion FROM pg_extension WHERE extname = 'vector';

-- ④ 独立 schema 必须存在
SELECT count(*) FROM pg_namespace WHERE nspname = 'rag';
```

| 自检项 | 失败行为 | 理由 |
|---|---|---|
| ① 角色为特权 | **拒绝启动**，输出 `current_user` / `rolsuper` / `rolbypassrls` | 特权连接下 RLS 形同虚设，跨租户泄漏不可检出 |
| ② 运行账号为 owner | **拒绝启动** | owner 身份会让「策略是否生效」的测试失去意义 |
| ③ 缺 `vector` 扩展 | **拒绝启动** | 索引无法建立；「运行时才发现无向量能力」= 静默失效 |
| ④ 缺 `rag` schema | **拒绝启动** | 迁移未执行 |

> ⚠️ 部署前置：**必须**使用 `pgvector/pgvector:pg17` 镜像，或在基础镜像上安装 pgvector 扩展。
> **不可假设 `postgres:17` 自带该扩展**（D00 §5.2 实测失败记录）。
> 该项同时是 RAG-D02 §3.1 的部署要求（待撰写）。

---

## 2. 语料来源准入流程

### 2.1 流水线总览

```
   采集登记（rag_source 落库）
   trust_tier = 'UNVERIFIED'（默认）  ver_status = 'PENDING'（默认）
   ver_method = 'NONE'               ver_evidence/verified_at/verified_by = NULL
   license_status = 'PENDING'（默认） object_ref = 对象存储引用    content_hash = 整册 SHA-256
                    │
                    │ ① 采集员提交（附采集说明与原始文件）
                    ▼
            ┌──────────────────┐
            │  PENDING          │  ← 默认态，不可索引
            └────────┬─────────┘
                     │ ② 审核员认领（记录认领人与认领时间）
                     ▼
            ┌──────────────────┐
            │  IN_REVIEW        │  ← 不可索引
            └────────┬─────────┘
                     │
     ┌───────────────┼─────────────────┬────────────────────────┐
     │ ③ 证据充分      │ ③ 证据不足       │ ③b 期间触发撤回
     ▼                ▼                 ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│  VERIFIED    │ │  REJECTED    │ │  REVOKED     │
│ 可进入索引    │ │ 不可索引      │ │ 必须移出索引  │
└──────┬───────┘ └──────┬───────┘ └──────────────┘
       │ ④ 校验到期/证据失效 │ ⑤ 申诉 + 新证据
       ▼                 └──────────► (回到 IN_REVIEW)
┌──────────────────┐
│ 过期（计算态）      │  ← 非持久化状态：verified_at + ver_valid_days < now()
│ 阻断普通用户召回    │     对应 SourceVerification.isStale(validDays)
└──────────────────┘
```

> ⚠️ **「过期」不是 `ver_status` 的取值**。`rag_verification_status` 枚举只有 5 个值，
> 过期是由 `verified_at + ver_valid_days` **在查询期计算**得出的（`SourceVerification.isStale` 的语义）。
> 这带来一个实现约束：过期判定**必须发生在检索查询里**，不能只靠状态字段——
> 详见 §3.4 与 §8 RAG-R06。

### 2.2 角色与职责

| 角色 | 职责 | 不得做的事 | 落地方式 |
|---|---|---|---|
| 语料采集员 | 采集、登记出版元数据、上传正文至对象存储、填写 `object_ref` / `content_hash` | 不得自行提升 `trust_tier` 或置 `VERIFIED` | 主平台 IAM 角色（**D-001 待提供 issuer 与测试账号**） |
| 语料审核员 | 执行 ISBN/DOI 校验、抽样人工比对、填写 `ver_evidence`、作出 `VERIFIED`/`REJECTED` 决定 | 不得审批自己采集的来源（需双人复核，见 §2.4） | 同上 |
| 语料负责人（Data Owner） | 等级判定的最终裁量、冲突仲裁升级、抽样计划 | 不得绕过校验状态机直接置 `VERIFIED` | 同上（对应 DEC-5 重新纳入条件中的「数据 owner」，D00 §0 状态 🟡） |
| 教研专家 | 教学口径冲突的裁定（§3.5 同等级冲突） | 不得修改语料与索引 | 同上 |
| 法务 / 版权 | 授权判定、撤回受理、`license_status` 裁定 | 不得直接改 `ver_status` | 同上 |
| 系统管理员（DBA） | 迁移、保留期清理、备份、启动自检配置 | **不得**用特权连接跑应用 | 见 §1.5 |
| 租户管理员 | 设置本租户 `min_trust_tier` | **不得**将其降至 `UNVERIFIED`（见 §3.3 注、§8 RAG-R07） | `rag_tenant.min_trust_tier` |

> ⚠️ **RAG 不自建认证**（D00 §4）。语料治理角色必须登记到主平台 IAM，
> 而主平台 `D-001 IAM/OIDC` 当前状态为「**已排期 2026-10-19，待提供 issuer 与测试账号**」。
> 在此之前，本文档定义的角色**无法在系统中被强制执行**——这是 §8 RAG-R18 的成因之一。

### 2.3 分阶段审核：责任角色、所需证据、留痕字段

| 阶段 | 责任角色 | 进入条件 | 所需证据 | 留痕字段（写入 `rag_source`） | 放行判定 |
|---|---|---|---|---|---|
| **① PENDING**（登记） | 语料采集员 | 采集到文件 | 出版元数据：书名、出版社、版次、ISBN/DOI、正文文件 | `rag_source`：`source_type`、`title`、`publisher`、`isbn`/`doi`、`object_ref`、`content_hash`、`trust_tier='UNVERIFIED'`、`ver_status='PENDING'`、`ver_method='NONE'`、`ver_evidence=NULL`、`verified_at=NULL`、`verified_by=NULL`、`license_status='PENDING'`<br>`rag_document`（每个物理文件一行）：`carrier`、`title`、`page_count`、`object_ref`、`content_hash`、`parse_status='PENDING'` | **不可索引**。仅入库供审核比对 |
| **② IN_REVIEW**（认领审核） | 语料审核员 | PENDING 且元数据完整 | 认领记录 | `ver_status='IN_REVIEW'`；⚠️ **认领人/认领时间无字段**（§2.6） | **不可索引** |
| **③ VERIFIED**（校验通过） | 语料审核员（复核人确认） | IN_REVIEW 且证据充分 | 依 `ver_method` 取值：<br>`ISBN` → ISBN 校验记录 + 版权页截图对象键<br>`DOI` → DOI 解析记录（解析到的标题/作者与登记一致）<br>`MANUAL_REVIEW` → 逐份比对记录（比对页码范围、抽样比例、双人签核）<br>`OFFICIAL_HASH` → 出版社电子版规范化后的 SHA-256 | `ver_status='VERIFIED'`、`ver_method=<方法>`、`ver_evidence=<证据引用>`、`verified_at=<时间>`、`verified_by=<审核人 UUID>`、`ver_valid_days=<天数>`、`trust_tier=<判定等级>` | **可进入可检索索引**（仍受 §3.3 租户门槛与 §3.4 过期约束） |
| **③′ REJECTED**（校验失败） | 语料审核员 | IN_REVIEW 且证据不足 | 拒绝依据（证据不足 / 疑似盗版 / 元数据不符 / 授权缺失） | `ver_status='REJECTED'`、`ver_evidence=<拒绝理由>`（⚠️ **权宜承载**，见 §2.6）、`verified_at=<决定时间>`、`verified_by=<决定人>`、`trust_tier` 维持或降为 `UNVERIFIED` | **不可索引**。可申诉后回到 IN_REVIEW |
| **③″ REVOKED**（撤回） | 法务 / 语料负责人 | 任意阶段触发 | 撤回依据（权利人通知 / 盗版确认 / 授权到期 / 证伪 / 监管要求） | `ver_status='REVOKED'`、`ver_evidence=<撤回依据>`、`verified_at=<时间>`、`verified_by=<执行人>` | **必须移出索引**（§5.3）。历史引用保留并标注失效（§5.4） |

#### 2.3.1 放行条件的汇总（FR-RAG-001）

一个来源**同时**满足以下全部条件，才可被普通用户检索到：

| # | 条件 | 判定字段 | 判定位置 |
|---|---|---|---|
| G-0 | **来源级**与**载体级**状态同时通过 | `rag_source.ver_status` ∧ `rag_document.<可撤回标记>` | 查询期（🔴 载体级字段不存在，RAG-R20） |
| G-1 | 校验状态为 `VERIFIED` | `ver_status` | 查询期（SQL 过滤） |
| G-2 | 校验结论未过期 | `verified_at + ver_valid_days >= now()` | 查询期（**计算属性**，非状态位） |
| G-3 | 权威等级达到租户门槛 | `trust_tier.weight() >= min_trust_tier.weight()` | 查询期（对应 `TrustTier.meets`） |
| G-4 | 版权状态允许使用 | `license_status != 'RESTRICTED'` | 查询期（`PENDING` 亦不放行，见 §4.2） |
| G-5 | 片段角色允许进入当前检索模式 | `rag_chunk.role` | 已有：`RetrievalPolicy.allowsRole`（RAG-D05，待撰写） |
| G-6 | 检索分数达门禁 | 分数 | 已有：`RetrievalOrchestrator` `minScore`（默认 0.35，RD-Q2 待标定） |
| G-7 | 版本坐标匹配 | `rag_chunk.edition_id` | 已有：`EditionScope.admits` |

> ⚠️ **G-1～G-4 当前均未在检索路径上实现**（§8 RAG-R01）。
> 现状：`RetrievalOrchestrator` 只执行了 G-5、G-6、G-7 三项。
> 换言之，**RD-02 的 fail-closed 目前只存在于 DDL 的字段与枚举层面，尚未落到查询路径**。

### 2.4 状态机允许的转移

| # | 转移 | 触发者 | 允许 | 说明 |
|---|---|---|---|---|
| T-1 | `PENDING → IN_REVIEW` | 审核员 | ✅ | 认领 |
| T-2 | `IN_REVIEW → VERIFIED` | 审核员 | ✅ | 需完整证据 |
| T-3 | `IN_REVIEW → REJECTED` | 审核员 | ✅ | 需记录理由 |
| T-4 | `REJECTED → IN_REVIEW` | 审核员 | ✅ | 申诉/补充证据后重审 |
| T-5 | `PENDING → REVOKED` | 法务/负责人 | ✅ | 发现即撤，不必先审核 |
| T-6 | `IN_REVIEW → REVOKED` | 法务/负责人 | ✅ | 同上 |
| T-7 | `VERIFIED → REVOKED` | 法务/负责人 | ✅ | 授权到期、盗版确认、证伪 |
| T-8 | `REJECTED → REVOKED` | 法务/负责人 | ✅ | 同上 |
| T-9 | `VERIFIED → IN_REVIEW` | 系统/审核员 | ✅ | **到期重校**：置为 IN_REVIEW 即刻脱离普通用户检索（§3.4） |
| T-10 | `REVOKED → 任意` | — | ❌ | **REVOKED 是终态**。重新准入必须**新建 `source_id`** 记录，保留原记录以供审计与争议追溯 |
| T-11 | `VERIFIED → PENDING` | — | ❌ | 不允许「降级回默认态」；重校走 T-9 |
| T-12 | `REJECTED → VERIFIED` | — | ❌ | **禁止跳过 IN_REVIEW 直通**，否则「审核」形同虚设 |

> ⚠️ **T-10 的理由**：把被撤回的来源「复活」会让历史撤回记录与当前状态互相矛盾。
> 重新采集同一出版物（如同一年级的 2026 修订版）应当是**新的 `source_id`**，
> 旧记录保持 `REVOKED` 以便回答「这个来源为什么不见了」。

### 2.5 状态转移的留痕要求

| 要求 | 内容 | 落地 |
|---|---|---|
| 每次转移必须有 `ver_evidence` | 「谁在什么时候基于什么证据做了什么决定」 | 写入 `rag_source.ver_evidence` / `verified_at` / `verified_by` |
| 转移必须写审计 | 至少 `INGEST`（登记）与 `REVOKE`（撤回）必须落 `rag_access_audit` | 🔴 **未实现**（§7） |
| 证据引用建议结构化 | 建议 `method:ref@timestamp` 形式，如 `ISBN:https://...@2026-10-20T10:00Z` | 建议，待评审 |
| 证据本体不落库 | 证据（截图、比对记录）应存对象存储，库内只存引用 | 与 §4.1 一致 |

### 2.6 ⚠️ 当前 DDL 的字段缺口（需 V2 迁移）

| 缺口 | 现状 | 影响 | 建议 |
|---|---|---|---|
| 审核认领人/时间 | `IN_REVIEW` 无专属字段 | 无法回答「这批语料卡在谁手上」，SLA 无法度量 | V2 增加 `review_started_at`、`reviewer_id` |
| 拒绝理由 | 无 `ver_reject_reason`；`REJECTED` 只能借 `ver_evidence` 承载 | `ver_evidence` 语义混淆（成功证据 vs 失败理由），且 `SourceVerification` 的 `evidenceRef` 语义是「校验依据」 | V2 增加 `ver_reject_reason` / `ver_decided_at` |
| 审核人无外键无角色 | `verified_by UUID` 无引用完整性 | 可写入任意 UUID；无法约束「必须是审核员角色」 | V2 关联主平台 IAM 主体表，或至少加 CHECK 非空 |
| `content_hash` 可空 | `rag_source.content_hash CHAR(64)` 无 NOT NULL | 整册无哈希 → 无法做篡改检测（§6.5） | 对 `VERIFIED` 状态要求非空 |
| `license_status` 是 TEXT | 非枚举类型 | 可写入拼写错误的值（CHECK 只挡 4 个白名单外的值，但新增取值需改 DDL） | V2 改枚举 `rag_license_status` |
| ⚠️ **`rag_document` 缺状态字段** | 载体层只有 `parse_status`（解析进度），**没有** `ver_status` / `license_status` / `retrievable` | ① 载体级撤回**无法被表达**（只能物理 DELETE，而物理 DELETE 会级联抹除引用，见 §5.5）；② 无法表达「同一版本的官方电子版保留、第三方扫描版撤回」 | V2 为 `rag_document` 增加 `ver_status` / `retrievable` / `revoked_at` |
| ⚠️ **`parse_status` 与 `ver_status` 无约束关系** | `parse_status='CHUNKED'` 与来源 `ver_status='PENDING'` 可以同时成立 | 会出现「未校验来源已切分入库」的组合，**恰好是 RAG-R02 的持久化形态** | 应用层闸门（§2.3.1 G-1）必须同时校验 `parse_status` 与 `ver_status`；DB 层建议加 CHECK |
| ⚠️ `object_ref` / `content_hash` 在 `rag_source` 与 `rag_document` **双处存在** | 两层各有一份，口径未定义 | 同一份正文有两个可能的引用位置与两个可能的哈希；**哪个是权威？** 不明确则无法做篡改检测 | V2 明确：**载体级 `rag_document` 为权威**，作品级字段降级为「参考位置」或删除（§4.1.1 注） |

> ⚠️ **V2 迁移尚未编写**。本表是 V2 的需求清单，不是完成承诺。

---

## 3. 权威等级判定规则与冲突仲裁

### 3.1 等级定义

| 等级 | 权重 | 含义 | `standaloneSupportAllowed()` |
|---|---|---|---|
| `OFFICIAL_PUBLISHER` | 4 | 教材出版社官方出版（人教/苏教/北师大等），经 ISBN 校验 | ✅ `true` |
| `OFFICIAL_TUTORIAL` | 3 | 教辅品牌正式出版（经三审三校），经 ISBN 校验 | ✅ `true` |
| `PEER_REVIEWED_PAPER` | 2 | 学术论文（同行评审），经 DOI 校验 | ❌ `false` |
| `PREPRINT` | 1 | 预印本/会议摘要/机构白皮书 | ❌ `false` |
| `UNVERIFIED` | 0 | 未校验来源（个人上传、扫描件、网页存档） | ❌ `false` |

> ⚠️ 权重数值取自 `TrustTier` 源码。**`weight()` 只是排序与比较用**，
> 不可用它推导「内容正确性」——`TrustTier` 的类注释已明确：
> 等级表示「该来源在出版/审校链条上的位置」，**不代表内容正确性**。

### 3.2 等级判定规则（FR-RAG-002）

| 目标等级 | 必要条件 | 充分性条件 | 判定人 |
|---|---|---|---|
| `OFFICIAL_PUBLISHER` | `isbn IS NOT NULL`（DDL 强制） | ISBN 校验通过 + 版权页与登记元数据一致 + 抽样人工比对通过 | 审核员 + 负责人复核 |
| `OFFICIAL_TUTORIAL` | `isbn IS NOT NULL`（DDL 强制） | ISBN 校验通过 + 出版主体为已知教辅品牌 + 抽样人工比对通过 | 审核员 + 负责人复核 |
| `PEER_REVIEWED_PAPER` | `doi IS NOT NULL`（DDL 强制） | DOI 解析成功 + 解析元数据与登记一致 + 确认是**正式发表版**而非预印本 | 审核员 |
| `PREPRINT` | `doi IS NOT NULL`（DDL 强制） | 标识可解析，但**未经同行评审**（如 arXiv 编号、预印本 DOI、机构白皮书） | 审核员 |
| `UNVERIFIED` | 无 | 默认态；未通过上述任一条件 | — |

**判定的两条硬规则**：

1. **不得跳级**：从 `UNVERIFIED` 直接置 `OFFICIAL_PUBLISHER` 需经完整证据链，不得以「看起来是正版」为据。
2. **等级只由证据决定，不由来源自称决定**：来源的自我声明（版权页、封面）只是证据之一，
   必须与 ISBN 库/出版社目录交叉核对。

> ⚠️ `TrustTier` 的 javadoc 已指出关键区分：
> `OFFICIAL_TUTORIAL` 与 `OFFICIAL_PUBLISHER` 的差别是**教学取向**而非正确性——
> 教辅口径可能与教材不一致，故引用教辅时**须同时给出教材出处**。这一点直接决定了 §3.5 的冲突仲裁规则。

### 3.3 「入库可、检索不可」的字段级拆分与判定真值表

**三个字段各管一件事，不可互相替代：**

| 字段 | 回答的问题 | 谁读它 |
|---|---|---|
| `trust_tier` | 这个来源**在权威链条上的位置**（静态属性） | 排序、冲突仲裁、是否可作唯一支撑 |
| `ver_status` | 这个来源**当前是否已通过校验**（动态状态） | 准入闸门 |
| `rag_tenant.min_trust_tier` | 这个租户**允许看到什么等级**（租户策略） | 检索可见性 |
| `ver_valid_days` + `verified_at` | 校验结论**是否仍然有效**（时效） | 准入闸门（计算属性） |
| `license_status` | 这个来源**是否可以合法使用**（合规） | 准入闸门 |

**三个可见性层级**：

- **L1 解析入库**：`rag_chunk` 中存在该来源的片段。可用于人工比对。
- **L2 进入可检索索引**：片段可被检索流程返回。
- **L3 普通用户可检索**：租户用户能看到的范围。

#### 判定真值表（FR-RAG-002）

| # | `trust_tier` | `ver_status` | 过期 | `license_status` | 达租户门槛 | L1 解析入库 | L2 进可检索索引 | L3 普通用户可检索 | 可作唯一教学支撑 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `OFFICIAL_PUBLISHER` | `VERIFIED` | 否 | `LICENSED` | 是 | ✅ | ✅ | ✅ | ✅ |
| 2 | `OFFICIAL_PUBLISHER` | `VERIFIED` | 否 | `FAIR_USE_QUOTATION` | 是 | ✅ | ✅ | ✅ | ✅ |
| 3 | `OFFICIAL_TUTORIAL` | `VERIFIED` | 否 | `LICENSED` | 是 | ✅ | ✅ | ✅ | ✅ |
| 4 | `OFFICIAL_PUBLISHER` | `VERIFIED` | 否 | `LICENSED` | **否**（低于门槛） | ✅ | ✅ | ❌ | ❌ |
| 5 | `PEER_REVIEWED_PAPER` | `VERIFIED` | 否 | `LICENSED` | 是 | ✅ | ✅ | ✅ | ❌（须官方源并置） |
| 6 | `PREPRINT` | `VERIFIED` | 否 | `LICENSED` | 是 | ✅ | ✅ | ✅（若门槛允许） | ❌ |
| 7 | 任意 | `PENDING` | — | 任意 | 任意 | ✅ | ❌ | ❌ | ❌ |
| 8 | 任意 | `IN_REVIEW` | — | 任意 | 任意 | ✅ | ❌ | ❌ | ❌ |
| 9 | 任意 | `REJECTED` | — | 任意 | 任意 | ✅ | ❌ | ❌ | ❌ |
| 10 | 任意 | `VERIFIED` | **是** | 任意 | 任意 | ✅ | ⚠️ 见 §3.4 | ❌ | ❌ |
| 11 | 任意 | `REVOKED` | — | 任意 | 任意 | ⚠️ 见 §5.6 | ❌ | ❌ | ❌ |
| 12 | 任意 | 任意 | — | `PENDING` | 任意 | ✅ | ❌ | ❌ | ❌ |
| 13 | 任意 | 任意 | — | `RESTRICTED` | 任意 | ❌ | ❌ | ❌ | ❌ |
| 14 | `UNVERIFIED` | 任意 | — | 任意 | 任意 | ✅ | ❌ | ❌ | ❌ |

**规则解读（逐行）**：

| 规则 | 说明 |
|---|---|
| R-1 | L1 的门槛最低：只要版权不是 `RESTRICTED`，就可解析入库——这正是 D00 §2.2「入库可、检索不可」的落点：审核人员需要比对盗版扫描件 |
| R-2 | L2 的充分必要条件是 `ver_status='VERIFIED' AND 未过期 AND license_status ∈ {LICENSED, FAIR_USE_QUOTATION}` |
| R-3 | L3 在 L2 之上再加一道 `trust_tier.meets(min_trust_tier)`。注意**只影响可见性，不影响能否入库** |
| R-4 | 「可作唯一教学支撑」= L3 ∩ `trust_tier.standaloneSupportAllowed()`。论文/预印本即使可见，也**不得单独支撑**一条教学结论 |
| R-5 | `license_status='PENDING'` 阻断 L2/L3（版权状态未知 = 风险未知，fail-closed） |
| R-6 | `license_status='RESTRICTED'` 连 L1 都不允许——但若已入库，按 §5.6 走**状态标记 + 物理隔离**，不物理删 chunk |

> ⚠️ **真值表中 L2/L3 全部为 🔴 未实现**（§8 RAG-R01）。表格是**目标语义**，不是当前行为。
> 当前行为是：只要片段进了 `rag_chunk`，检索路径就可能返回它。

> ⚠️ **真值表是「来源级」的**。V1 新增的 `rag_document` 载体层引入了**更细的粒度**：
> 同一个 `source`（作品）下可以有多个 `document`（物理文件）——官方电子版 + 第三方扫描版。
> 上述真值表应理解为**载体级的合取**：一个片段可检索 ⟺ 其所属 `source` 与其所属 `document`
> **同时**通过相应条件。
>
> 这带来一个重要能力与一个重要缺口：
>
> | 方向 | 内容 |
> |---|---|
> | **能力** | **载体级撤回**——可以只下架第三方扫描版，而**保留同版本的官方电子版**继续服务学生。DDL 注释已认可该需求：「无法在某个载体被撤回时只下架它而不影响整个版本」 |
> | **缺口** | `rag_document` **没有任何状态字段**（`ver_status` / `retrievable` / `revoked_at` 全无），只有 `parse_status`。**载体级撤回目前无法被表达**——唯一的「下架一个载体」手段是物理 `DELETE FROM rag_document`，而它会级联抹除该载体下全部历史引用（§5.5 / RAG-R20） |
>
> 因此本表的 L2/L3 判定在 V2 落地时**必须按载体级实现**，否则「只下架盗版扫描件、保留正版」这个
> 教辅场景最有价值的治理能力将无法落地。

> ⚠️ **`min_trust_tier` 的双通道问题（RAG-R07）**：
> 真值表第 4 行说明租户门槛是**收紧**可见性的。但若把租户门槛调成 `UNVERIFIED`，
> 第 14 行（`UNVERIFIED` 源）就会被 L3 放行——这等于用**生产配置**开关打开了**审核通道**。
> 正确设计是：审核通道必须是**独立的角色化通道**（审核角色可见全部 L1 语料），
> 而**不是**通过降低 `min_trust_tier` 实现。
> 因此附加硬约束：**生产租户的 `min_trust_tier` 不得低于 `OFFICIAL_TUTORIAL`**（与 DDL 默认值一致）。

### 3.4 ⚠️ 校验结论过期后的行为（明确回答）

**问题**：`ver_valid_days` 到期、`SourceVerification.isStale(validDays)` 返回 `true` 时，是否自动降权？是否阻断召回？

**结论（三条，全部明确）**：

| # | 问题 | 结论 | 理由 |
|---|---|---|---|
| **Q1** | 是否自动降权 `trust_tier`？ | **否**。`trust_tier` **不随时间变化** | `trust_tier` 描述的是「在出版/审校链条上的位置」，这是来源的**静态属性**。一本 2024 年修订的人教版教材，其出版链条位置不会因为校验结论过期而改变。若把过期映射为降权，等于把「**权威**」与「**新鲜**」两个正交维度混为一谈，且会让「过期」在展示上被误读为「不可靠」 |
| **Q2** | 是否阻断召回？ | **是。阻断普通用户召回（L2/L3 均不成立）** | ① D00 §3 原则 6「准入 fail-closed」；② **静默降权是 fail-open 的变体**——降权后内容仍会被召回并进入 prompt，而语义检索总会返回「最不相似的那一条」（RAG-D00 RD-11 记录的现象），分数惩罚**不构成有效屏障**，真正的屏障是硬过滤；③ 过期意味着「当前无法证明该来源此刻仍然合规且未被替换」——`content_hash` 可能已漂移（§6.5） |
| **Q3** | 过期是否等于撤回？ | **否**。二者语义不同，不可混用 | 「过期」是**时效性失效**（未重新证明）；「撤回」是**事实性否定**（被证伪/被要求停用）。撤回走 `REVOKED`（终态、强制移出索引、保留历史引用并标注）；过期走「重新校验」通道（T-9） |

**补充规则**：

| # | 规则 | 理由 |
|---|---|---|
| E-1 | 过期源**仍可被内部审核角色检索** | 与 D00 §2.2 一致：审核人员需要比对。审核通道独立于 `min_trust_tier`（见 §3.3 注） |
| E-2 | 过期源的**历史引用保持有效** | 过期影响的是「现在能否用」，不影响「当时依据什么」。与 §5.4 同理 |
| E-3 | 建议设置**有界宽限期**：≤ 14 天，且**仅适用于**可自动重校的来源（`ver_method ∈ {ISBN, DOI}` 且 `license_status='LICENSED'`） | 无宽限会导致每 365 天出现一次内容不可用窗口。`MANUAL_REVIEW` / `OFFICIAL_HASH` 的源**无宽限**（人工重校周期不可控） |
| E-4 | 宽限期**不是**「过期仍可用」 | 宽限期内源仍标记为过期、仍写审计、仍可被追责；宽限期结束即无条件阻断。**待评审** |
| E-5 | 应在到期前提前提醒重校（如提前 30 天） | 避免到期当天的内容断层 |

**实现约束（重要）**：过期是**计算属性**，`rag_verification_status` 枚举中**没有** `STALE` 取值。
因此：

- 过期判定必须出现在**检索 SQL** 中（`verified_at + ver_valid_days >= now()`）；
- 🔴 当前 `RetrievalOrchestrator` 无此判定；
- 🔴 无到期扫描作业，无提前提醒机制（`rag-worker` 无源码）；
- ⚠️ 建议 V2 提供「有效校验结论」视图或物化统计，避免每次检索都做时间算术。

### 3.5 权威等级冲突仲裁规则（FR-RAG-003）

#### 3.5.1 冲突的定义与前置判定

| 步骤 | 动作 | 说明 |
|---|---|---|
| C-0 | **先判是否真冲突** | 同一 `kp_id` + 同一 `edition_id` 下，两条内容对**同一命题**给出不同结论。措辞差异、详略差异、例子差异**不是**冲突 |
| C-1 | 排除版本差异 | 若差异源于 `edition_id` 不同（版次/出版社/册次），则**不是冲突**，走版本免责（§3.5.4） |
| C-2 | 排除角色差异 | 若是「教材正文」与「教辅解析」的口径差异，属**教学取向差异**，按 C-3 交叉等级处理，但必须**同时给出教材出处**（`TrustTier` javadoc 明确要求） |
| C-3 | 进入等级仲裁 | 按 C-4 / C-5 规则处理 |

> ⚠️ C-0 的自动判定能力**未实现**（`GroundingVerdict.Verdict.CONFLICTING` 枚举存在，
> 但 `GroundingService` 的判定链路尚未产出冲突结论；RAG-D06 待撰写）。
> 当前阶段冲突由人工抽检发现。

#### 3.5.2 跨等级冲突：必须展示分歧

| 规则 | 内容 |
|---|---|
| **C-4** | **高等级优先**：`OFFICIAL_PUBLISHER` > `OFFICIAL_TUTORIAL` > `PEER_REVIEWED_PAPER` > `PREPRINT` > `UNVERIFIED`。低等级来源**不参与生成结论** |
| **C-5** | **但必须展示分歧——不得静默择一**。静默择一会把「教材口径」变成唯一真相，掩盖口径差异；且违反 D00 §3 原则 2「引用先于结论」——分歧必须可核验 |

**分歧展示的强度分级**（按低等级来源的等级）：

| 低等级来源 | 分歧呈现位置 | 必须包含 |
|---|---|---|
| `UNVERIFIED` / `PREPRINT` | **仅引用区**标注 | 「另有低等级来源给出不同表述（来源X · 第 N 页）」，不展开正文 |
| `PEER_REVIEWED_PAPER` | **仅引用区**标注 | 同上；论文结论本身在正文单列为「延伸阅读」 |
| `OFFICIAL_TUTORIAL` | **正文相邻位置**必须展示 | 两种表述并列 + 各自出处（教材出处 + 教辅出处）+ 「口径不一致」提示。理由：教辅与教材口径差异是家长/学生最常遇到的实际问题，静默择一会让学生拿到的解释与手上的书对不上 |
| 同为 `OFFICIAL_PUBLISHER` | 见 C-6 | 不同出版社/版次视为版本差异 |

#### 3.5.3 同等级冲突：并列呈现，系统不裁定

| 规则 | 内容 |
|---|---|
| **C-6** | **同等级冲突必须并列呈现并标注分歧，系统不得静默择一，也不得自动裁定** |
| **C-7** | 正确输出形态：「**存在分歧**」+ 两种表述 + 两处出处 + 「该问题需以教材/教研结论为准」；**不得**输出其中一种作为唯一答案 |
| **C-8** | 同等级意味着**权威性相当**。此时任何自动裁定都是在制造**伪确定性**——比「不知道」更危险 |
| **C-9** | 同等级冲突应**升级**给教研专家/语料负责人裁决；系统负责**记录冲突与升级**，不负责**解决**冲突 |
| **C-10** | 升级与裁决结果应回写为语料备注（**不影响等级**——等级不由争议解决） |

> ⚠️ `TrustTier` 的 javadoc 已明确该立场：
> 「同等级冲突时必须**并列呈现**并标注分歧，**不允许静默择一**」。本文档把它细化为可执行规则。

#### 3.5.4 跨版本差异：不作冲突处理

| 规则 | 内容 |
|---|---|
| C-11 | 同一出版社不同版次（如 2012 版 vs 2024 修订版）的表述差异**不是冲突**，是版本演进。处理方式按 RD-04 三档模式（STRICT / PREFERRED_WITH_FALLBACK / CROSS_EDITION） |
| C-12 | 跨版本命中时**必须**输出版本免责说明（`RetrievalPolicy.detectVersionMismatch` 已提供该信号） |
| C-13 | 引用必须携带 `edition_snapshot`（`rag_answer_citation.edition_snapshot`），使「当时依据哪一版」可回溯 |

#### 3.5.5 论文等级为何不能单独支撑教学结论

`TrustTier.standaloneSupportAllowed()` 仅对 `OFFICIAL_PUBLISHER` 与 `OFFICIAL_TUTORIAL` 返回 `true`。
理由（按重要性排序）：

| # | 理由 | 说明 |
|---|---|---|
| P-1 | **命题层级不同** | 论文结论 = 「在特定样本、方法、置信区间下成立」的经验命题；教材定义 = 教学规范。两者**不是同一命题**。「三角形内角和」不能用一篇论文的定义替换——论文讨论的是别的命题 |
| P-2 | **前提条件会被丢弃** | 论文结论几乎总带前提（样本、对照、显著性）。抽取到初中课堂时前提必然被丢，而**丢前提的学术结论是最危险的简化** |
| P-3 | **教材优先（RD-03/RD-04）** | 学生的疑问是「书上这一页怎么讲」。引用必须落在教材版本坐标上，论文**没有**教材版本坐标，因此无法满足「可核验」的定义 |
| P-4 | **稳定性不匹配** | 论文结论随新证据修正，预印本更是未经同行评审。把教学结论的稳定性绑定到学术修订节奏，对初中生场景是负收益 |
| P-5 | **口径冲突风险** | 论文可能比教材**更新**（更严格的定义）。若允许论文覆盖教材，将出现「书上没这么写但系统这么说」——学生无法核对，正是 RAG-D00 §1.2 列为非目标的「教材全文对外分发/无出处内容」同类问题 |

**因此**：`PEER_REVIEWED_PAPER` / `PREPRINT` 在回答中只能作为**「延伸阅读 / 参考观点」单列**，
支撑定义性教学结论时**必须有官方来源并置**（`OFFICIAL_PUBLISHER` 或 `OFFICIAL_TUTORIAL`）。

### 3.6 分歧的呈现规范（建议，待评审）

| 元素 | 要求 |
|---|---|
| 分歧标记 | 显式短语，如「**口径不一致**」「**存在分歧**」，不得只用视觉样式区分 |
| 并列结构 | 两种表述各自带完整出处（书名·版本·页码·题号），与正常引用格式一致 |
| 结论归属 | 明确标出「以官方教材表述为准」（若存在官方来源） |
| 可操作 | 提示「该题号在不同辅导材料中答案不同，建议对照课本」 |
| 审计 | 分歧判定应落审计（与 `CITE` 同批写入），便于统计分歧率 |

---

## 4. 版权与授权治理

### 4.1 为什么教材全文不入库，只存 `object_ref`

**结论**：语料的**整册原文**只保存在对象存储（载体级 `rag_document.object_ref` 指向），
数据库只保存**引用 + 校验和 + 片段级数据**。

| # | 理由 | 说明 |
|---|---|---|
| 1 | **授权边界** | 采购所得通常是「查阅/使用授权」，**不包含**「复制 + 存储 + 再分发」。把全文落库 = 复制行为，超出授权范围 |
| 2 | **数据库是扩散面** | 备份、只读副本、跨租户读取、运维导出、一次 `COPY` 即可完成大规模复制。**一旦入库，取回成本极低且不可撤回**——这与主平台 P0-D00 §3.4 认定的「不可撤回」风险同级 |
| 3 | **撤回将不可行** | 全文入库后，takedown 需要「证明已从所有备份中清除」——**做不到**，等于没有撤回能力。§5 的整套撤回机制正是建立在「库内无全文」之上 |
| 4 | **与已声明的非目标一致** | RAG-D00 §1.2 明确「教材全文对外分发」是非目标，只做「定位 + 短引文」，全文不入库 |
| 5 | **容量与收益不成比例** | D00 §2.1 估算教辅语料约 **1500 万片段**量级。全文落库会把存储与备份成本推到远超检索收益的水平 |
| 6 | **与向量检索的工程约束一致** | 检索只依赖片段文本与向量；整册原文的唯一用途是「人工核对」——这正是对象存储 + 受控读取的用途 |

> ⚠️ **`object_ref` 存在两级，口径必须先定**（§2.6 缺口）：
> `rag_source.object_ref`（作品级）与 `rag_document.object_ref`（载体级）都存在。
> **本文件采用的口径是：载体级 `rag_document.object_ref` 为正文引用的权威位置**——
> 因为「一份 PDF」才是实际被读取、被计算哈希、被撤回的实体。
> 作品级 `rag_source.object_ref` 理解为「该作品下正文的参考位置」。
> ⚠️ 该口径**须在 V2 明确或删除作品级字段**，否则同一份正文存在两个可能的引用位置，
> 篡改检测（§6.5）将不知道该比对哪一个。

**对象存储的访问要求**：

| 要求 | 内容 | 状态 |
|---|---|---|
| 每次读取重新授权 | 短时效签名 URL，不发长期凭证 | 🔴 未实现（`rag-ports` 无对象存储端口） |
| 主体隔离 | 按租户 + 角色授权，对象键不跨租户 | 🔴 未实现 |
| 生命周期 | 授权到期自动删除正文 | 🔴 未实现 |
| 访问留痕 | 正文读取应可审计（当前 `rag_access_audit` 无「读取正文」action，见 §7.6） | 🔴 未实现 |

#### 4.1.1 ⚠️ 审查发现：DDL 注释与 `rag_chunk.text` 的张力

V1 迁移头部注释第 5 条写：

> 「语料正文只在对象存储留存，库内只存引用 + 校验和，避免教学资料全文落库带来的合规与容量风险。」

但 `rag_chunk` 表定义了 `text TEXT NOT NULL`——**片段正文确实入库了**。

| 事实 | 影响 |
|---|---|
| 整册原文**不**入库（只有 `object_ref`） | ✅ 与注释第 5 条的**意图**一致 |
| 片段级正文**确实**入库（`rag_chunk.text`） | ⚠️ 与注释的**字面表述**不一致 |

**建议的处置**（**待法务确认**，登记为 §10.3 的 L-3）：

1. **澄清口径**：明确本文档与 DDL 注释的表述应为「**整册原文**不入库；**片段**入库以支撑向量检索与逐字引文」；
2. **评估片段级存储的合规边界**：片段总量 ≈ 原文总量的 5～10%，仍可能构成「系统性复制」，
   需结合合理使用/授权条款评估；
3. **若法务判定片段入库不可接受**，则需改为：片段只存**位置 + 哈希 + 向量**，逐字引文在回答时**实时回对象存储取**——
   ⚠️ 该方案会显著增加检索链路复杂度与延迟，**需重新评估**；
4. 无论结论如何，**必须修订 V1 注释与本文档，保持一致**——注释与实现不一致本身就是合规风险（RAG-R11）。

> ⚠️ 本条为**文档审查发现**，不是已确认的实现缺陷；处置结论依赖法务意见（RD-Q4）。
>
> ⚠️ **载体层不改变该结论**：`rag_document` 的引入（`object_ref` + `content_hash` 落在载体级）
> **强化**了「整册原文不入库」的设计，但**没有改变**「片段正文入库」这一事实。
> `rag_chunk.text` 仍存储片段原文，§4.1.1 的口径澄清与法务评估**仍然需要**。

### 4.2 `license_status` 四个取值的判定标准

| 取值 | 判定标准 | 证据要求 | L2 可检索 | 可否展示逐字引文 | 失效触发 |
|---|---|---|---|---|---|
| `LICENSED` | 已获得权利人**明确授权**，且授权范围**同时覆盖**三项：① 存储（片段）② 检索/计算 ③ 在回答中展示短引文 | 合同/授权函/API 协议编号 + 授权主体 + 有效期 | ✅ | ✅ | 授权到期或被撤销 → 转 `RESTRICTED` |
| `FAIR_USE_QUOTATION` | **无**直接授权，但拟使用方式符合合理使用/引用豁免：短引文 + 定位 + 不构成实质替代 + 不批量输出 | 书面法律意见 + 使用方式说明（§4.3） | ✅ | ✅（限短引文） | 权利人异议或法务复核否定 → 转 `RESTRICTED` |
| `PENDING` | 授权状态**未确认**（默认态） | — | ❌ | ❌ | 确认后转 `LICENSED` / `FAIR_USE_QUOTATION`；确认无授权 → `RESTRICTED` |
| `RESTRICTED` | 明确无授权 / 权利人要求停用 / 授权被撤回 / 超出授权范围 | 撤回通知或法务裁定 | ❌ | ❌ | 不可恢复（终态语义） |

**判定优先级（必须取最严）**：

```
RESTRICTED  >  PENDING  >  FAIR_USE_QUOTATION  >  LICENSED
```

| 理由 | 说明 |
|---|---|
| ① 与 DEC-9 精神一致 | 门禁只能收紧，不能放松（RAG-D00 §2 引用 DEC-9 精神） |
| ② 未知即阻断 | `PENDING` 是默认态，绝大多数教辅在授权确认前都处于此态。若 `PENDING` 可检索，**整个授权治理形同虚设** |
| ③ 授权可撤回 | `LICENSED` 不是永久状态；授权到期必须能**单向**转为 `RESTRICTED` |
| ④ 与准入状态正交 | `license_status` 与 `ver_status` 是**两个独立轴**：一本正版教材可能 `ver_status='VERIFIED'` 但 `license_status='PENDING'`（已验真伪但未取得授权）——**两轴都通过才可检索** |

> ⚠️ **RD-Q4（教辅 PDF 的授权形态）尚未确认**。在授权形态确定前，`FAIR_USE_QUOTATION` 的适用范围
> 无法界定，本节判定标准**仅为建议**，须经法务逐条确认后方可执行。

### 4.3 「短引文 + 定位」模式如何降低侵权风险

| # | 要素 | 做法 | 落到字段 |
|---|---|---|---|
| 1 | **目的性限制** | 引用是为说明/评论/讲解服务，**非**替代原书 | `rag_answer_citation.supported_claim` |
| 2 | **最小必要** | 只引支撑该条结论所必需的句子，**不**整段搬运 | `verbatim_quote` 长度上限 ⚠️ **未定义，待评审** |
| 3 | **可溯源** | 给出定位信息，读者可回到**自己合法持有的**原件核对 | `rag_chunk.page_number` / `.section_path` / `.exercise_no` + `rag_answer_citation.edition_snapshot` |
| 4 | **非实质替代** | 一次回答不提供足以替代原书的内容；不做章节级连续输出 | 需在生成层约束 ⚠️ **未实现** |
| 5 | **非系统性复制** | 不提供批量导出接口 | `rag_access_audit.action='EXPORT'` 的授权控制（§7.1） |
| 6 | **逐条可归因** | 每段引文与它支撑的声明一一对应 | `supported_claim` ↔ `verbatim_quote` |
| 7 | **不提供全文** | 库内只存片段，库外只存受控引用 | `object_ref` + `rag_chunk` 片段级 |

> ⚠️ **诚实边界**：「短引文 + 定位」是**降低风险的技术模式**，**不等于自动合法**。
> 合理使用是**逐案判断**的结论，且教辅类作品的合理使用空间通常窄于一般作品。
> ⚠️ **必须**经法务就「引文长度上限」「章节级连续输出的判定阈值」给出明确数值，
> 本节结构可采信但数值待定（§10.3 的 L-2）。

### 4.4 被限制授权的语料在引用时如何表现（FR-RAG-005）

| 场景 | 回答正文 | 引用区展示 | 导出/打印件 | 对接地判定的影响 |
|---|---|---|---|---|
| `LICENSED` | 可使用内容与短引文 | 完整引用（书名·版本·页码·题号 + 逐字引文） | 同左 | 正常参与 `GroundingPolicy.decide` 裁决 |
| `FAIR_USE_QUOTATION` | 可使用内容与**限长**短引文 | 完整引用 + **版权声明**（如「本处引用依据合理使用，版权归原出版方」） | 同左 | 同上 |
| `PENDING` | **不得**出现该源内容 | 显示定位元数据 + 「**授权待确认，原文不展示**」 | 屏蔽 | 该引用**不计入**支撑 → 若为唯一支撑，声明判为无依据 → **fail-closed 拒绝交付** |
| `RESTRICTED` | **不得**出现该源内容 | 显示定位元数据 + 「**来源授权受限/已撤回，原文不再展示**」 | 屏蔽 | 同上 |

**实现要点**：

| # | 要点 | 说明 | 状态 |
|---|---|---|---|
| 1 | 失效引用**不计入**接地支撑 | 不得让受限来源的引用支撑一条已交付的声明 | 🔴 未实现 |
| 2 | 需要新的接地判定取值 | 现有 `GroundingVerdict.Verdict` 只有 `GROUNDED` / `PARTIALLY_GROUNDED` / `UNGROUNDED` / `CONFLICTING`，**无「失效」**。当前只能保守映射为 `UNGROUNDED` | 🔴 枚举待扩展 |
| 3 | 逐字引文必须脱敏 | `verbatim_quote` 需在失效后不可展示 | ⚠️ **DDL 冲突**：`rag_citation_quote_present_chk CHECK (length(btrim(verbatim_quote)) > 0)` **禁止**置空 → 需 V2 调整（§8 RAG-R05） |
| 4 | 导出与打印同样屏蔽 | 防止「屏幕不可见、导出可见」的绕过 | 🔴 未实现 |

### 4.5 授权期限、续期与 `object_ref` 治理

| 项 | 规则 | 状态 |
|---|---|---|
| 授权有效期 | 应与 `ver_valid_days` **分开**管理：授权到期导致 `license_status` 变更，校验到期导致 `ver_status` 失效。⚠️ DDL **无授权到期日字段** → V2 需求 | 🔴 缺口 |
| 授权续期提醒 | 到期前提醒，避免 `LICENSED` 突然变 `RESTRICTED` 造成内容断层 | 🔴 未实现 |
| `object_ref` 格式 | 建议 `{tenant}/{source_id}/{document_id}/{filename}`，**不得**含用户可识别的个人信息 | 建议 |
| `object_ref` 授权 | 短时效签名 URL，每次读取重新授权 | 🔴 未实现（`rag-ports` 无对象存储端口） |
| 生命周期 | 授权到期或撤回后，对象存储正文进入删除队列 | 🔴 未实现 |
| **载体级生命周期** | `rag_document` 的每个载体独立计算生命周期：官方电子版可长期保留，第三方扫描版可单独删除 | 🔴 未实现（载体层无状态字段，RAG-R20） |

---

## 5. 版本改版与撤回（takedown）机制

### 5.1 改版：版本坐标系与生效区间

| 项 | 规则 | 字段 |
|---|---|---|
| 坐标 | 五轴复合：学科 / 年级 / 册次 / 出版社 / 版次，**缺一轴不可入库**（RD-03） | `rag_edition.subject/grade/term/publisher/revision_code` |
| 册次取值 | 枚举约束：`上册` / `下册` / `全一册` | `rag_edition_term_chk` |
| 唯一性 | `(tenant_id, subject, grade, term, publisher, revision_code)` 唯一 | `rag_edition_unique` |
| 生效区间 | `effective_from` / `effective_to` 允许并存多版 | `rag_edition` |
| 旧版不下架 | 「教材改版后旧版仍需可查——学生可能正在用旧版」 | DDL 注释原文 |
| 课程标准 | `curriculum_ref` 挂课程标准 | `rag_edition` |
| 改版时**不**改旧版内容 | 改版 = 新增 `rag_edition` 行 + 新增 `rag_source`（或同源多册次） | — |

> ⚠️ 旧版与新版的**优先级**由 RD-04 三档模式（STRICT / PREFERRED_WITH_FALLBACK / CROSS_EDITION）决定，
> 该文档**尚未撰写**。本文档只负责：改版**不触发**来源降级或撤回。

### 5.2 撤回的触发源与 SLA

| # | 触发源 | 首次响应 | 完成清理 | 是否需人工复核 |
|---|---|---|---|---|
| K-1 | 版权方/权利人要求下架 | **立即**（≤ 4 小时）置 `REVOKED` | ≤ 24 小时删除对象存储正文 | **否**——先阻断后核实（fail-closed） |
| K-2 | 确认盗版/未授权 | 立即 | 同上 | 否 |
| K-3 | 授权到期/撤销 | 到期当日 | ≤ 24 小时 | 否（自动） |
| K-4 | 校验被证伪（内容与出版事实不符） | 立即 | ≤ 24 小时 | 是（需出具证伪依据） |
| K-5 | 监管/法律要求 | 立即 | 按要求 | 是 |
| K-6 | 语料质量事故（系统性解析错误、答案错误） | 立即 | 需评估历史回答 | 是 |

> ⚠️ **SLA 数值待评审**（本文档为建议值）。原则：**先阻断，后核实**——
> 版权/盗版类事件中「误阻断一个来源」的代价远小于「继续分发盗版教材」。

### 5.3 撤回执行：`REVOKED` 如何从索引中移除（FR-RAG-006）

**核心结论**：撤回 = **状态驱动 + 查询期硬过滤 + 索引层纵深**，
**不是**物理 `DELETE`。

#### 5.3.1 三道防线

| 防线 | 机制 | 现状 |
|---|---|---|
| **D1 状态位（来源级）** | `rag_source.ver_status = 'REVOKED'`。`SourceVerification.VerificationStatus.REVOKED` 的 javadoc 明确「**必须从索引中移除**」 | ✅ 枚举与字段已存在 |
| **D1′ 状态位（载体级）** | `rag_document` 应有独立的可撤回标记（如 `ver_status` / `retrievable` / `revoked_at`），使**单个载体**可被下架而不影响同版本的其他载体 | 🔴 **字段不存在**（RAG-R20）——`rag_document` 只有 `parse_status` |
| **D2 查询期硬过滤** | 检索 SQL 必须 JOIN `rag_source`（经 `rag_edition`/`rag_document`）并过滤：`ver_status='VERIFIED' AND NOT stale AND license_status != 'RESTRICTED' AND trust_tier.weight >= 租户 min_trust_tier.weight` | 🔴 **未实现**（`RetrievalOrchestrator` 无此过滤） |
| **D3 索引层纵深** | 扩展部分索引，使被撤回/未校验源的片段**在存储层就不被索引命中** | 🔴 **未实现**，需 V2 |

#### 5.3.2 关于 `rag_chunk_retrievable_idx` 的准确分析

现有部分索引定义：

```sql
CREATE INDEX rag_chunk_retrievable_idx
    ON rag_chunk (tenant_id, page_number)
    WHERE role NOT IN ('EXERCISE_ANSWER', 'EXAMPLE_SOLUTION', 'BOILERPLATE');
```

| 事实 | 结论 |
|---|---|
| 该索引的 `WHERE` 条件**只含 `role` 维度** | 它解决的是**泄题闸门**（答案类片段不进索引），**完全不解决**权威等级与撤回问题 |
| `rag_chunk` 表**没有** `trust_tier` / `ver_status` / `license_status` / `retrievable` 列 | 索引**无法**跨表引用 `rag_source` / `rag_document` 的状态（PostgreSQL 部分索引不支持子查询） |
| 结论 | **「撤回内容从索引中移除」在存储层目前没有任何机制**。仅有 D1 状态位与尚未实现的 D2 |

> ⚠️ **载体层加剧了这个问题**：`rag_document` 已存在且承载 `object_ref` / `content_hash`，
> 却**没有**任何可索引的状态列。因此即便 V2 为 `rag_source` 加了状态冗余列，
> **载体级撤回仍需在 `rag_chunk` 上再加一层冗余**（例如 `source_retrievable` + `document_retrievable`），
> 否则「只下架扫描件、保留正版」无法在存储层生效（RAG-R03 / RAG-R20）。

#### 5.3.3 D3 的实现方案（V2 迁移，待实现）

| 方案 | 做法 | 评价 |
|---|---|---|
| **3-A（推荐）** | 在 `rag_chunk` 增加**源状态冗余列** `source_ver_status rag_verification_status` 与 `retrievable BOOLEAN`，由写入路径在**同事务**内随片段一并写入；并把部分索引扩展为 `WHERE retrievable AND role NOT IN (...)` | 存储层直接生效；代价是冗余列需与 `rag_source` 保持一致（由同事务保证） |
| 3-B | 用**物化视图**承载「可检索片段」 | 需处理刷新延迟，撤回生效时间不可控 |
| 3-C | 仅依赖 D2 应用层过滤 | ⚠️ **不足**：应用层过滤可能因代码缺陷遗漏；D00 §3 原则 3「过滤早于生成」要求有纵深 |

> ⚠️ 方案 3-A 还需解决一个**已存在的原子性要求**：
> 片段的「状态 / 角色 / 向量」必须**同事务**更新，否则会出现
> 「已下架内容的向量仍可召回」——这正是 V1 迁移头部注释第 1 条所禁止的。
> 冗余列方案与该要求天然一致。

#### 5.3.4 撤回执行流程

| 步 | 动作 | 责任角色 | 留痕 | 原子性要求 |
|---|---|---|---|---|
| W-1 | 受理触发（K-1～K-6） | 法务/负责人 | `rag_access_audit.action='REVOKE'` | — |
| W-2 | **先写审计**，再改状态 | 系统 | 审计记录 `source_id`、触发源、原因 | 审计写失败 → 撤回不生效（fail-closed） |
| W-3 | 置 `ver_status='REVOKED'` + `ver_evidence=<撤回依据>` + `verified_at` + `verified_by` | 系统 | `rag_source` | 与 W-2 同一事务 |
| W-3′ | **载体级撤回**（可选）：仅下架某个 `rag_document`，保留同版本的其他载体 | 系统 | `rag_document` 的可撤回标记 | 🔴 **字段不存在**（RAG-R20）；V2 前只能走「整源撤回」 |
| W-4 | 冗余列置 `retrievable=false`（V2） | 系统 | `rag_chunk` | **同事务**（防「状态已撤但向量仍可召回」） |
| W-5 | 引用链路标注失效 | 系统 | `rag_answer_citation` 标注（V2 字段） | 独立事务，允许最终一致 |
| W-6 | 对象存储正文进入删除队列 | 系统 | 生命周期策略 | 异步，≤ 24h |
| W-7 | 排查受影响的历史回答，必要时通知 | 语料负责人 | 工单 | — |
| W-8 | 抽样验证「已不可召回」 | 审核员 | 审计 + 抽检记录 | — |

### 5.4 历史引用（`rag_answer_citation`）如何处理（FR-RAG-007）

**结论：保留并标注失效，不删除。**

| # | 理由 | 说明 |
|---|---|---|
| 1 | **引用溯源链路不能被破坏** | 引用链路的价值在于「可回溯当时依据什么生成了这个回答」。删除历史引用 = 让已交付的回答变成**不可复核的断言**，与本项目「可溯源」的核心目标直接冲突 |
| 2 | **已交付内容无法撤回** | 回答已进入用户侧（家长/学生/教师），内部再删除证据将形成「外部有结论、内部无依据」的不一致，事后争议无从裁决 |
| 3 | **合规上应区分「停止使用」与「销毁历史记录」** | 版权方要求的是停止展示与停止继续使用，而非抹除审计痕迹。⚠️ 该区分**须经法务确认**（§10.3 的 L-1） |
| 4 | **与审计追加写精神一致** | `rag_access_audit` 已是追加不可改表；引用记录若可被静默删除，整个「不可篡改」叙事不成立 |

**具体处置表**：

| 字段 | 撤回后处置 | 理由 |
|---|---|---|
| `citation_id` / `tenant_id` / `conversation_id` / `run_id` / `chunk_id` | **保留** | 链路完整性 |
| `supported_claim` | **保留** | 记录「当时主张了什么」，是争议裁定的核心 |
| `verdict` / `entailment_score` | **保留** | 记录「当时如何判定」 |
| `edition_snapshot` | **保留** | 记录「当时依据哪一版」，即使该版后续失效 |
| `created_at` | **保留** | — |
| `verbatim_quote` | **脱敏或置空** | 否则「停止使用」未落实；但 ⚠️ 现有 CHECK 约束禁止置空 → V2 调整（RAG-R05） |
| ⚠️ `invalidated_at` / `invalid_reason` | **需新增** | 标注「此引用因何失效」，当前 DDL **无此字段** → V2 需求 |

**展示层表现**：

| 场景 | 引用区展示 |
|---|---|
| 引用有效 | 「《人教版·七年级上册·2024修订》· 第 3 章 > 3.2 二次函数的图像 · 第 45 页 · 第 12 题」+ 逐字引文 |
| 引用失效 | 「《…》· 第 45 页 · 第 12 题（**来源授权受限/已撤回，原文不再展示**）」——保留定位元数据，不展示原文 |

**并置效应**：若某条声明的**唯一**支撑是失效引用，则该声明失去接地 →
按 `GroundingPolicy.decide` 的 fail-closed 语义，**整条回答不予交付**或降级为「依据不足」。
⚠️ 现有 `GroundingVerdict` 无「失效」取值，当前只能保守映射为 `UNGROUNDED`（RAG-R05 / §4.4 要点 2）。

### 5.5 ⚠️ 硬约束：引用溯源链路不可被破坏（INV-4）

以下四条为**不可协商的约束**，任何实现变更不得违反：

| 编号 | 约束 | 落地机制 | 状态 |
|---|---|---|---|
| **INV-4-a** | **禁止**对 `rag_source` / `rag_edition` / `rag_document` / `rag_chunk` 执行**物理 DELETE** 作为撤回手段 | 撤回只走 `REVOKED` 状态；DB 层面加禁删触发器 | 🔴 未实现 |
| **INV-4-b** | **禁止**物理删除 `rag_chunk`（因其 `ON DELETE CASCADE` 会抹除 `rag_answer_citation`） | V2 将 `rag_citation_chunk_fk` 改为 `ON DELETE RESTRICT`，或加触发器阻断 | 🔴 **未实现，且当前行为相反**（RAG-R04） |
| **INV-4-c** | 引用**先落库、后展示**：`rag_answer_citation` 写入成功才允许把引用呈现给用户 | 与 `CITE` 审计同事务 | 🔴 未实现 |
| **INV-4-d** | 失效**只走状态标注**，永不物理删除引用记录 | V2 新增 `invalidated_at` / `invalid_reason` | 🔴 未实现 |

**当前 DDL 的具体风险（必须由 V2 修复）**：

```sql
-- V1 现状：删除 chunk 会静默删除其全部历史引用
CONSTRAINT rag_citation_chunk_fk
    FOREIGN KEY (tenant_id, chunk_id)
    REFERENCES rag_chunk (tenant_id, chunk_id) ON DELETE CASCADE
```

级联链（全部为 `ON DELETE CASCADE`，截至 2026-10-20 11:36 的 V1 版本）：

```
rag_source ──DELETE──► rag_edition ──DELETE──► rag_document ──DELETE──► rag_chunk ──DELETE──► rag_answer_citation
    │                         │                      │                      │
    └─────────────────────────┴──────────────────────┴──────────────────────┴──►
                          rag_chunk_embedding（随 rag_chunk 同步级联删除）
```

| 后果 | 严重度 |
|---|---|
| 一次「清理测试数据」的 `DELETE FROM rag_source` 会**静默抹除**全部历史引用 | **高** |
| 一次「下架一个盗版扫描件载体」的 `DELETE FROM rag_document` 会**静默抹除该载体下全部历史引用** | **高**（载体级撤回当前**唯一**可用的手段就是物理删除，见 RAG-R20） |
| 违反 INV-4-a/b | **高** |
| 若历史回答已交付给学生/家长，系统将无法回答「这个结论当时依据什么」 | **高** |

> ⚠️ 这也是 §5.4 选择「保留标注失效」而非「删除」的**结构性原因**：
> 若引用可被级联删除，保留就无从谈起。
>
> ⚠️ **载体层引入了一条新的破坏路径**：`rag_document` 没有任何「可撤回」状态字段，
> 因此「下架一个载体」目前**只能**靠物理 DELETE——而物理 DELETE 必然抹除其引用链。
> 这不是可以靠纪律规避的问题，而是**schema 层面的缺失**，必须由 V2 补齐（RAG-R20）。

### 5.6 撤回与物理删除的边界

| 动作 | 允许 | 说明 |
|---|---|---|
| 置 `ver_status='REVOKED'` | ✅ | 撤回的标准手段 |
| 冗余列置 `retrievable=false` | ✅ | V2 |
| 引用标注失效 | ✅ | V2 |
| 删除对象存储中的正文文件 | ✅ | 不在库内，不破坏链路 |
| `DELETE FROM rag_document` | ❌ | **载体级撤回的唯一现行手段**，但会级联抹除该载体下全部引用（RAG-R20） |
| `DELETE FROM rag_chunk` | ❌ | 级联抹除引用（INV-4-b） |
| `DELETE FROM rag_edition` / `rag_source` | ❌ | 级联链同上 |

> ⚠️ **实现建议**：V2 为 `rag_source` / `rag_edition` / `rag_document` / `rag_chunk` 加
> 「禁止物理删除」触发器，例外仅限**测试库**。
> 这条约束的价值在于：**它把「撤回」从一个需要纪律的约定，变成数据库强制的不可违反项**——
> 与 D00 §5.1 超级用户教训一致：**依赖纪律的边界迟早会被绕过**。

---

## 6. 内容污染与盗版识别

### 6.1 威胁清单

| # | 威胁 | 场景 | 危害 |
|---|---|---|---|
| T-1 | **盗版教材扫描件** | 教辅市场大量流传的扫描 PDF，保留原 ISBN | 把盗版内容包装成「权威讲解」输出（RAG-D00 §2.2 原文点名） |
| T-2 | **篡改扫描件** | 在盗版基础上删页、改答案、插广告 | 教辅场景下答案错误 = 教学事故 |
| T-3 | **拼接盗版** | 从多本书拼贴成「一册」 | 单册哈希无法识别（§6.5） |
| T-4 | **静默替换** | 对象存储中的文件被替换为另一版本 | `content_hash` 可检出（§6.5） |
| T-5 | **无授权教辅 PDF** | 非盗版但未授权 | 侵权风险，与真伪无关（§4.2） |
| T-6 | **低质量爬取内容** | 网页讲义、公众号文章 | 等级 `UNVERIFIED` 即可拦住 |
| T-7 | **多载体混入** | 同一 `edition` 下同时存在官方电子版与第三方扫描版 | ⚠️ `rag_document` 的存在使「只下架扫描件」成为**必要能力**（§3.3 注），但该能力当前**无字段支撑**（RAG-R20） |

> ⚠️ **关键区分**：T-1～T-4 是**真伪**问题，T-5 是**授权**问题。
> 两者**不能互相替代**——`OFFICIAL_HASH` 证明「与官方版一致」，
> 但**不证明**「这是允许的使用方式」。授权只能由合同/法律意见确认（§4.2）。

> ⚠️ **`carrier` 字段是盗版的第一手信号（低成本、高价值）**：
> `rag_document.carrier` 的取值域含 `SCAN`（扫描件）与 `PDF`（电子版）。
> 一条经验规则可供审核员优先排查：
> **「`OFFICIAL_PUBLISHER` 作品的 `carrier='SCAN'` 载体」→ 疑似盗版扫描件，应默认进入人工比对队列**。
> 该信号**不能**单独定性（合法机构也可能保存扫描存档），但它把排查成本从「全量比对」降到「按载体类型抽样」。

### 6.2 ISBN 校验

| 项 | 内容 |
|---|---|
| **作用** | 校验 ISBN-10/13 校验位算法；与 ISBN 库/出版社目录交叉核对书名、版次、出版社。证明「**这是一本真实存在且已登记的出版物**」 |
| **DDL 支撑** | `rag_source.isbn` + `rag_source_identifier_chk`（`OFFICIAL_PUBLISHER`/`OFFICIAL_TUTORIAL` 必须有 ISBN）；`rag_verification_method` 含 `ISBN` |
| **局限 1** | ISBN 只证明**出版物存在**，**不证明手中的文件是正版**。盗版扫描件通常保留原 ISBN |
| **局限 2** | 伪造 ISBN 的成本很低（校验位可计算），伪造品能通过纯算法校验 |
| **局限 3** | 覆盖面窄：不覆盖公众号讲义、网课讲义、个人整理稿、内部教辅 |
| **局限 4** | 同一 ISBN 对应多个载体（纸质/电子/PDF），**ISBN 不能区分版本形态** |
| **结论** | ISBN 是**准入必要条件，不是充分条件**。必须与其他手段组合（§6.6） |

### 6.3 `OFFICIAL_HASH` 官方电子版哈希比对

| 项 | 内容 |
|---|---|
| **做法** | 出版社提供正版电子版 → 对其按**固定规范化口径**（如：去页眉页脚、归一空白、统一全半角）做 SHA-256 → 与 `rag_source.content_hash` 比对 |
| **作用** | 可检出「非正版 / 被替换 / 被篡改 / 载体不一致」；是**最强的机器可判定证据** |
| **局限 1** | **需出版社提供正版文件或官方哈希**——现实中极难获得。授权关系是商业谈判结果，出版社通常不会交付哈希 |
| **局限 2** | **规范化口径必须固定且公开**，否则同一份合法内容因解析/OCR 差异产生不同哈希 → 误报率极高。这是 `content_hash` 能否用的**前提** |
| **局限 3** | 只能证明「与官方版一致」，**不证明「使用方式被授权」**（见 §6.1） |
| **局限 4** | 只能整体比对整册；**局部替换**（改一道答案）会使整册哈希失配 —— 但这正是我们想要的检出行为（代价是只能整册重校） |
| **结论** | 仅对**已授权**来源作为强证据；对未授权来源只能作为「疑似盗版」的**信号**，不作为定性依据 |

### 6.4 抽样人工比对

| 项 | 内容 |
|---|---|
| **做法** | 随机抽 N% 页（或首册全检）人工/双人核对：页码、题号、图注、版次标识、例题答案，与官方样张/合法持有的原件比对 |
| **作用** | 覆盖机器方法无法判定的差异：**版次差异、插页/删页、答案区标注差异、插图替换** |
| **局限 1** | **抽样无法证明全册**。只能给出「以样本置信度推断」 |
| **局限 2** | 成本高、周期长，无法支撑大规模语料的持续重校 |
| **局限 3** | 依赖**样张可得性**；无样张时无基准可比 |
| **局限 4** | 双人复核的判官标准可能漂移（§8 RAG-R18） |
| **建议** | 首册**全检**（建立基准）→ 后续版本**抽样**（如 5%～10%）→ 风险升高时**升级为全检** |
| **状态** | 🔴 未实现（无抽检记录表、无抽检计划表） |

### 6.5 `content_hash` 整册与片段校验和

| 层级 | 字段 | 作用 | 局限 |
|---|---|---|---|
| 载体（整册） | `rag_document.content_hash CHAR(64)` | 检测**单个物理文件**被静默替换；是 `OFFICIAL_HASH` 比对的**天然基准**（官方电子版与第三方扫描版是同一 `edition` 下的两个 `document`，可逐一比对） | ① 只能发现「与入库时记录不一致」，**不能发现入库时就是错的**；② 规范化口径未固定则误报；③ **不检测内容正确性**；④ **无法识别拼接盗版**（多本拼接的哈希与任何单本都不同，但「不同」≠「盗版」）；⑤ ⚠️ 当前**可空** |
| 作品（整册） | `rag_source.content_hash CHAR(64)` | 作品级参考哈希 | ⚠️ 与载体级哈希**双处存在且口径未定义**（§2.6）——**必须先定权威**（§4.1.1 注），否则无法判定「哪一份是基准」 |
| 片段 | `rag_chunk.content_hash CHAR(64) NOT NULL` | 检测**片段级漂移**（重建索引时校验源片段未变）；定位「哪一段被改了」 | 同上；且只覆盖已进入索引的片段，未入库部分无从校验 |

**规范化口径（必须先定义，否则不可用）**：

| 要素 | 建议 | 状态 |
|---|---|---|
| 文本归一 | Unicode NFC、去首尾空白、连续空白折叠为单空格 | 建议 |
| 页眉页脚 | 剥离 | 建议 |
| 换行/断词 | 合并软断行 | 建议 |
| 公式/表格 | 保留原样（不做 LaTeX 化） | 建议 |
| 哈希算法 | SHA-256（与 `IngestionPipeline.contentHash` 一致） | ✅ 已实现于片段侧 |
| 整册哈希的输入 | **尚未定义**（是原始 PDF 字节？还是规范化全文？） | 🔴 **待定义**——这是 §6.3/§6.5 能否落地的前置条件 |

> ⚠️ `IngestionPipeline.contentHash(String)` 实现的正是「片段文本的 SHA-256」（已落地）。
> 但**整册** `rag_document.content_hash` / `rag_source.content_hash` 的输入口径在 DDL 中
> **没有任何定义或校验**，且两列**均允许为 NULL**——这是一个必须先补齐的缺口（V2 需求，见 §2.6）。
>
> ⚠️ **载体层为 `OFFICIAL_HASH` 提供了落点**：官方电子版与第三方扫描版是同一 `edition` 下的
> 两个 `document`，各自持有独立的 `content_hash`。因此「官方版 vs 扫描版」的逐载体比对
> 在数据模型上**已经可表达**——缺的只是 §6.5 的规范化口径与 `OFFICIAL_HASH` 的实际渠道（RAG-R23）。

### 6.6 证据强度阶梯与组合判定

| 强度 | 证据 | 能证明 | 不能证明 |
|---|---|---|---|
| **E-0** | 无 | — | 任何事 |
| **E-1** | `ISBN` / `DOI` 校验通过 | 出版物/论文**真实存在且元数据一致** | 文件是正版；使用被授权；内容正确 |
| **E-2** | `MANUAL_REVIEW`（抽样人工比对） | 样本范围内与原件一致 | 全册一致；使用被授权 |
| **E-3** | `OFFICIAL_HASH` 匹配 | 与官方电子版**逐字节等价**（规范化后） | 使用被授权 |
| **E-4** | 授权文件/法律意见 | 使用方式**在授权范围内** | 内容正确 |

**组合判定表**（准入决策）：

| 授权证据 \ 真伪证据 | E-1 | E-2 | E-3 | E-4 |
|---|---|---|---|---|
| **E-4 有授权** | `LICENSED` + 等级按 §3.2 | `LICENSED` + 等级按 §3.2 | `LICENSED` + 等级按 §3.2 | — |
| **E-4 无授权（合理使用）** | `FAIR_USE_QUOTATION` + 等级按 §3.2 | `FAIR_USE_QUOTATION` | `FAIR_USE_QUOTATION`（**冗余**，仍无授权） | — |
| **E-4 明确无授权** | `RESTRICTED` | `RESTRICTED` | `RESTRICTED` | `RESTRICTED` |

**关键结论**：

> ⚠️ **没有任何单一手段能证明「正版且授权」**。
> 真伪证据（E-1～E-3）与授权证据（E-4）是**正交的两条轴**，
> 必须分别取得。二者缺一时的处置已由 §3.3 真值表第 5、12、13 行固化。

### 6.7 污染处置

| 场景 | 处置 | 状态 |
|---|---|---|
| 判定为盗版 | `ver_status='REJECTED'`（或 `REVOKED`，若已入库）+ `ver_evidence` 记录判定依据 + 审计 + 通知采集员 | 流程已定义，🔴 未实现 |
| 判定为篡改 | 同上；`content_hash` 失配记录为证据 | 同上 |
| 已入库后才发现 | `REVOKED` → §5.3 流程 → 排查受影响历史回答 | 同上 |
| 疑似但无法确认 | 保持 `UNVERIFIED`（不可检索）——**不因「无法确认」而放行** | fail-closed |

---

## 7. 语料访问审计

### 7.1 五种 action 的写入时机（FR-RAG-009）

| action | 写入时机 | 关键字段 | 写入主体 | 写入失败时的行为 | 状态 |
|---|---|---|---|---|---|
| `INGEST` | 片段写入 `rag_chunk` **成功后**（批量或逐条） | `source_id`、`chunk_id`、`principal_id`（审核员/采集员） | 采集/入库任务 | 入库任务置 `FAILED`（`rag_ingest_job.status`） | 🔴 未实现 |
| `RETRIEVE` | 检索结果**实际返回并即将进入 prompt 之前** | `chunk_id`、`source_id`、`request_digest` | 检索编排 | 建议：允许降级但**必须告警 + 补偿队列**；高敏操作（`EXPORT`/`REVOKE`）则 fail-closed | 🔴 未实现 |
| `CITE` | 引用写入 `rag_answer_citation` **同一事务内** | `chunk_id`、`source_id`、`principal_id` | 生成/校验流程 | **回答不交付**（与 `GroundingPolicy` 的 fail-closed 一致） | 🔴 未实现 |
| `EXPORT` | 导出/外发动作**执行前** | `source_id`、`chunk_id`（可空 = 整册/整源） | 导出服务 | **拒绝导出** | 🔴 未实现 |
| `REVOKE` | 撤回动作执行时（与 `ver_status='REVOKED'` 同事务） | `source_id`、`principal_id`；载体级撤回还需 `document_id`（⚠️ 该列不存在，V2） | 治理流程/法务 | **撤回不生效**（先写审计，后改状态） | 🔴 未实现 |

**两条时序原则**：

| 原则 | 说明 |
|---|---|
| **P-1「先写审计，后做动作」** | 对 `REVOKE` / `EXPORT` / `CITE` 尤为关键：先改变状态再补审计，中途崩溃就会产生「状态已变但无记录」的盲区 |
| **P-2「记返回，不记候选」** | `RETRIEVE` 必须在**结果被返回**时写，而不是召回候选时写。候选量级远大于返回量；且**只有进入 prompt 的内容才是实际风险面** |

> ⚠️ **`RETRIEVE` 的粒度权衡**（待评审）：逐条写（`chunk_id` 精确，但写入量 = 命中数 × 请求数）vs
> 逐请求写（`request_digest` + 命中数统计，可回放但定位不到具体片段）。
> 建议：**逐请求写摘要 + 对进入 prompt 的片段写 `CITE`**——
> 二者组合可覆盖「谁看了什么」与「什么被用于生成」，且写入量可控。

### 7.2 为什么审计表必须追加不可改

| # | 理由 | 说明 |
|---|---|---|
| 1 | **审计的全部价值来自「事后不可篡改」** | 可修改的审计 = 没有审计。任何有动机的主体都能改掉自己的记录 |
| 2 | **与主平台纪律一致** | 主平台 P1-D08 §3.3 R-3 记录「审计表未实现追加约束」为残余风险；V1 迁移已对 RAG 侧落实 |
| 3 | **DDL 已有强制机制** | `trg_rag_audit_append_only`（`BEFORE UPDATE OR DELETE ... FOR EACH ROW`）→ `RAISE EXCEPTION 'rag_access_audit 为追加写表，不允许 UPDATE/DELETE'`。RAG-D00 §5 已实测「审计表 UPDATE/DELETE 被拒 ✅ PASS」 |
| 4 | **与「引用溯源链路不可被破坏」同源** | 审计与引用记录是同一条「不可篡改证据链」的两端 |

**⚠️ 追加写的强度边界（必须如实认识）**：

| 限制 | 说明 | 对策 |
|---|---|---|
| **不阻止 `TRUNCATE`** | `TRUNCATE` 不触发行级触发器 | 运行账号不授予 `TRUNCATE`/owner 权限（§1.5）；建议加事件触发器阻断 |
| **不阻止 `DROP TABLE` / `DROP TRIGGER`** | DDL 权限可移除保护 | 同上；运行账号无 DDL 权限 |
| **不阻止特权角色绕过** | 超级用户可禁用/删除触发器 | §1.5 的 `NOSUPERUSER` 强制要求 |
| **无外部锚定** | 库内不可篡改 ≠ 库外不可篡改 | ⚠️ **建议**：按日聚合条数与摘要写入**与主库分离**的存储，形成外部锚定 —— 🔴 未实现（RAG-R13） |

> ⚠️ 结论：追加写触发器提供的是「**应用运行时角色不可改**」，不是「**物理不可改**」。
> 文档不得把后者写成已具备的能力。

### 7.3 审计保留期

沿用主平台 `P0-D00` §4.4 的保留期口径：

| 数据 | 主平台保留期 | 存储位置 | RAG 侧对应 | 状态 |
|---|---|---|---|---|
| 学习画像 | 12 个月 | 主库（租户隔离） | 不适用（RAG 不采集学生画像） | — |
| Prompt / 工具结果 | 90 天 | 对象存储（每次读取重新授权） | 若检索请求原文需留存，落对象存储 90 天 | 🔴 未实现（当前只存 `request_digest`） |
| **审计日志** | **180 天** | 审计库（脱敏，不含 Prompt 原文） | **`rag_access_audit` → 180 天** | 🔴 保留期作业未实现 |
| Trace / 指标 | 30 天 | 观测系统（不含高基数与敏感标签） | 检索 Trace → 30 天 | 🔴 未实现 |
| **RAG 引用记录** | ⚠️ 主平台口径**未覆盖** | — | `rag_answer_citation` **建议 ≥ 12 个月** | 🔴 待 P4-D04 复核确认（RAG-R16） |

**`rag_answer_citation` 为何建议超出 180 天**：

| 理由 | 说明 |
|---|---|
| 性质不同 | `rag_access_audit` 是**行为日志**（事后追责）；`rag_answer_citation` 是**交付证据**（争议裁决依据） |
| 争议周期 | 教学争议（作业、考试、错题纠正）的追责周期远超 6 个月 |
| 连带约束 | 引用保留期若短于其 `chunk` 的可用期，§5.4 的「保留标注失效」将失去意义 |

### 7.4 「不含敏感原文」的要求

**DDL 层面的刻意设计**：`rag_access_audit` **只有** `request_digest CHAR(64)`，**没有** `query` 原文列、
**没有** `chunk.text`、**没有** `verbatim_quote`。这是有意的。

| 要求 | 说明 | 状态 |
|---|---|---|
| 禁止在审计表记录 `verbatim_quote` | 逐字引文是教材原文，写入审计表等于把原文复制进第二张表，**破坏 §4.1 的核心前提** | ✅ DDL 天然满足（无该列） |
| 禁止在审计表记录 `chunk.text` | 同上 | ✅ DDL 天然满足（无该列） |
| 禁止在审计表记录学生身份信息 | 审计主体用 `principal_id UUID`，对齐主平台的随机 `student_id` 口径（P0-D00 §4.1） | ✅ DDL 天然满足 |
| 检索请求只存摘要 | `request_digest` 用于回放；⚠️ **「回放」与「脱敏」存在张力**——若摘要可还原原文，则等于存了原文 | ⚠️ 见下方风险 |
| 建议：`request_digest` 改用**加盐 HMAC** | 裸 SHA-256 对低熵 query（「二次函数」「第45页」等常见教学词）**可被彩虹表反查** | 🔴 未实现（RAG-R14） |

> ⚠️ **设计冲突（须在评审中裁决）**：`request_digest` 的 DDL 注释写「检索请求的摘要（**便于回放**）」，
> 而 §7.4 要求「不含敏感原文」。**回放需要原文，脱敏需要不可还原**——两者不可兼得。
> 建议裁决：**放弃「回放」能力，改为「可比对」能力**——
> 摘要仅用于「同一请求是否重复出现」的统计与幂等去重，不用于还原查询内容。
> 相应地，注释应改为「检索请求的**去重摘要**（不含原文，不可还原）」。

### 7.5 保留期清理与 `NOSUPERUSER` 约束的张力

| 事实 | 后果 |
|---|---|
| `rag_access_audit` 的追加写触发器**禁止 `DELETE`** | 180 天到期后，**应用运行账号无法执行清理** |
| 保留期清理必须**跨租户** | 应用账号受 RLS 限制，只能删本租户数据；且无 DELETE 权限 |
| 结论 | 保留期清理由 **DBA 在维护窗口执行一次性脚本**；应用运行账号**不授予 DELETE/TRUNCATE 权限**，**也不通过任何配置开关开放删除** |

| 关联项 | 要求 |
|---|---|
| 清理脚本 | 独立于应用代码，受变更管控（CHG 模板），执行后留痕 |
| 幂等 | 可重复执行，不影响未到期数据 |
| 审计表自身的例外 | 「审计不得被删」与「审计要到期清理」**天然冲突**——**必须由法务与安全书面裁决**：建议「审计表的到期清理**只允许在到期后**执行，且须由两人授权 + 全程留痕」 |
| 🔴 状态 | 保留期作业**未实现**（`rag-worker` 无源码） |

### 7.6 审计的已知缺口

| 缺口 | 影响 | 建议 |
|---|---|---|
| 无「读取对象存储正文」action | §4.1 要求的受控读取无留痕 | 扩展 `rag_access_audit_action_chk`（V2）或在 `action` 中复用 `EXPORT` 语义 |
| 无「准入审核」action | 审核员的判定行为（谁在何时判了什么）只体现在 `rag_source` 字段，缺独立事件流 | 建议复用 `INGEST` 或新增 action（V2） |
| 无审计的**外部锚定** | 库内追加写不等于不可篡改（§7.2） | 按日外部锚定（🔴 未实现） |
| ⚠️ **审计表无 `document_id` 列** | `rag_access_audit` 只有 `source_id` 与 `chunk_id`，**载体级撤回无法被精确审计**——无法回答「下架的是哪一份物理文件」；而在 `rag_document` 引入后，「哪一份载体」恰恰是撤回的最小单位 | V2 增加 `document_id` |
| 无审计写入实现 | 全部 5 种 action 均无写入点 | 🔴 §8 RAG-R12 |

---

## 8. 风险登记册

评分口径沿用主平台 `P0-D05` §1：P（概率 1–5）× I（影响 1–5）；15–25 高，8–14 中，1–7 低。
**安全/合规类高风险不可仅靠分数降级。**

| ID | 风险 | P/I/分级 | 影响 | 缓解/验证措施 | Owner | 到期/触发条件 | 状态 |
|---|---|---|---|---|---|---|---|
| RAG-R01 | **检索路径无权威/校验闸门**：`RetrievalOrchestrator` 只做版本/角色/分数三道过滤，**未引用** `TrustTier` / `SourceVerification`（已核对源码，`rag-application` 与 `rag-ports` 中零引用） | 4/5/高 | 未校验/盗版/已撤回语料可被召回并进入 prompt，**直接违反 RD-02 fail-closed** | AT-RAG-002/003/007；查询期 JOIN `rag_source` 硬过滤；纳入启动自检 | 后端/安全 | **首个语料接入前** | Open |
| RAG-R02 | **入库路径无准入闸门**：`IngestionPipeline` 不校验 `SourceVerification`，任意文件可直接生成 `rag_chunk` | 4/5/高 | 未校验源直接进入索引，绕过全部治理流程 | AT-RAG-001；入库前置校验 + 状态机 T-12 约束 | 后端 | **首个语料接入前** | Open |
| RAG-R03 | **撤回/授权受限未在存储层生效**：`rag_chunk_retrievable_idx` 的 `WHERE` 只含 `role` 维度；`rag_chunk` 无源状态列 | 3/5/高 | 应用层过滤一旦遗漏即泄漏已撤回内容 | V2 冗余列 + 扩展部分索引（§5.3.3 方案 3-A） | 后端/DBA | V2 迁移 | Open |
| RAG-R04 | **`rag_citation_chunk_fk ON DELETE CASCADE`** 使物理删除 `rag_chunk` 静默抹除历史引用；级联链贯穿 `rag_source→rag_edition→rag_document→rag_chunk→rag_answer_citation` | 3/5/高 | **引用溯源链路被破坏**，已交付回答变为不可复核断言 | V2 改 `RESTRICT` + 禁删触发器（INV-4-a/b） | DBA | V2 迁移 | Open |
| RAG-R05 | **`rag_citation_quote_present_chk` 禁止 `verbatim_quote` 置空**，与「撤回后停止展示原文」直接冲突 | 3/4/中 | 撤回后逐字引文无法脱敏，「停止使用」未落实 | V2 调整约束 + 增设 `invalidated_at`/`invalid_reason` | DBA | V2 迁移 | Open |
| RAG-R06 | 过期是**计算属性**（`verified_at + ver_valid_days`），非 `ver_status` 取值；**无到期扫描作业、无提前提醒** | 3/4/中 | 过期源持续可检索，§3.4 的 fail-closed 结论无法执行 | 查询期计算 + 定期扫描作业（E-5 提前 30 天提醒） | 后端 | 首个语料接入前 | Open |
| RAG-R07 | **`min_trust_tier` 把「审核通道」与「生产通道」混在同一开关**；调低至 `UNVERIFIED` 即放行未校验源 | 3/4/中 | 生产配置成为绕过准入的后门（主平台 R-020 同类） | 审核通道角色化独立实现；生产租户门槛下限约束（§3.3 注） | 架构/安全 | 与 RAG-R01 同步 | Open |
| RAG-R08 | **超级用户连接绕过 RLS**（RAG-D00 §5.1 已实测：超级用户下租户 1 可见租户 2 数据） | 3/5/高 | 跨租户数据泄漏，且**无法被检出** | §1.5 四项强制要求 + 启动自检 + IT 用非特权角色 | DBA/安全 | 生产准入前 | **方案已定，自检未实现** |
| RAG-R09 | **官方 `postgres:17` 镜像不含 pgvector**（RAG-D00 §5.2 实测失败） | 2/4/中 | 部署失败或向量能力静默缺失 | 镜像固定 `pgvector/pgvector:pg17`；启动自检 ③ | SRE/DBA | 生产准入前 | Open |
| RAG-R10 | **教辅授权形态未定**（RAG-D00 RD-Q4 未确认） | 4/4/高 | `license_status` 取值无依据；`FAIR_USE_QUOTATION` 适用范围无法界定 | 法务确认 + 首批合同/法律意见 | 法务/项目负责人 | 首批语料接入前 | Open |
| RAG-R11 | **全文不入库口径与 `rag_chunk.text` 片段正文存在张力**：V1 注释称「库内只存引用 + 校验和」，但片段正文入库 | 3/4/中 | 合规声明与实现不一致；片段总量 ≈ 原文 5%～10%，仍可能构成系统性复制 | §4.1.1 澄清口径；法务评估片段级存储边界；必要时改实时回源 | 法务/架构 | V2 | Open |
| RAG-R12 | **无审计写入实现**：`rag-infrastructure` 仅有 DDL 无 Java；5 种 action 均无写入点 | 4/4/高 | 事后无法追责；RAG-D00 §1.1 承诺的「溯源可审计」不成立 | AT-RAG-012～016 | 后端 | 首个语料接入前 | Open |
| RAG-R13 | 审计表可被 `TRUNCATE` / `DROP TRIGGER` 绕过追加写约束；**无外部锚定** | 2/4/中 | 审计被事后清除 | 非特权账号 + 无 DDL 权限 + 外部锚定（§7.2） | 安全/DBA | 生产准入前 | Open |
| RAG-R14 | `request_digest` 为裸哈希时，低熵 query 可被彩虹表反查；且与「便于回放」的注释语义冲突 | 2/3/低 | 查询内容被还原 | 改加盐 HMAC；裁决「回放 vs 脱敏」（§7.4） | 后端 | V2 | Open |
| RAG-R15 | 教辅与教材口径差异被**静默择一** | 3/4/中 | 学生得到与手中教材不一致的解释 | C-4/C-5 强制展示分歧 + AT-RAG-008 | AI/教研 | 语料接入后首批评测 | Open |
| RAG-R16 | 同等级冲突时模型倾向自行裁定 | 3/4/中 | 输出无依据的唯一答案，违背 C-6/C-7 | 并列呈现强制 + 评测集反例 | AI | 语料接入后 | Open |
| RAG-R17 | 引用保留期（180 天）与「交付证据需长期可核验」冲突 | 3/4/中 | 长期教学争议无据可查 | `rag_answer_citation` 建议 ≥ 12 个月 + P4-D04 复核 | 安全/DBA | P4 | Open |
| RAG-R18 | 语料等级判定依赖人工；**IAM 未接入**（D-001 待提供 issuer 与测试账号），本文档定义的角色**无法被强制执行** | 3/3/中 | 同类来源判定不一致；审核纪律形同建议 | 判定规则表 + 双人复核 + 定期校准会；IAM 接入 | 教研/审核 | 首批语料接入后 | Open |
| RAG-R19 | `rag_source.verified_by` 无外键、无角色约束；`content_hash` 可空；`license_status` 为 TEXT 非枚举 | 3/3/中 | 治理字段可被写入任意值，审计链不可靠 | V2 补齐（§2.6） | DBA | V2 | Open |
| RAG-R20 | ⚠️ **`rag_document` 载体层缺状态字段**：只有 `parse_status`，无 `ver_status` / `retrievable` / `revoked_at` | 4/4/高 | ① **载体级撤回无法被表达**，唯一手段是物理 DELETE → 必然抹除该载体的历史引用；② 「只下架盗版扫描件、保留正版电子版」这一教辅场景最有价值的治理能力**无法落地** | V2 为 `rag_document` 增加状态字段 + `rag_chunk` 增加载体级冗余列 | 后端/DBA | **V2，优先于首批语料接入** | Open |
| RAG-R21 | ⚠️ **`rag_document.parse_status` 与 `rag_source.ver_status` 无任何约束关系** | 4/4/高 | 「未校验来源已切分入库」（`parse_status='CHUNKED'` + `ver_status='PENDING'`）是**可持久化状态**，与 RAG-R02 叠加即形成完整的治理失效 | 入库闸门同时校验两者（§2.3.1 G-1）；DB 层加 CHECK | 后端/DBA | 首批语料接入前 | Open |
| RAG-R22 | ⚠️ **`object_ref` / `content_hash` 在 `rag_source` 与 `rag_document` 双处存在且权威未定义** | 3/4/中 | 篡改检测不知道该比对哪个哈希；正文读取可能指向错误对象 | V2 明确载体级为权威（§4.1.1 注） | 架构/DBA | V2 | Open |
| RAG-R23 | ⚠️ **RAG-D00 §5 的实测基线与当前 V1 不一致**（记录为 10 表 / 10 条 RLS 策略，当前为 **11 表 / 10 条策略**，新增 `rag_document`） | 3/3/中 | 新增的 `rag_document` 表的约束与 RLS 策略**未经那轮实测**；决策基线与实现漂移 | 补跑 DDL 约束反证 + 跨租户隔离（用非特权角色）；D00 §5 同步更新 | DBA/安全 | 下次 DDL 变更前 | Open |

**风险升级规则**（沿用主平台 P0-D05 §5）：

- 安全/合规类高风险（RAG-R01/02/03/04/08/10/11/12/20/21）**不得**仅靠分数降级或推迟；
- **RAG-R01 与 RAG-R02 是「首批语料接入」的硬前置**——在它们关闭前，
  **不得**把任何真实教辅语料接入可检索索引；
- **RAG-R20 与 RAG-R21 同为硬前置**：载体层无状态字段（RAG-R20）与
  `parse_status` 脱离校验状态（RAG-R21）会直接放大 RAG-R02 的后果。
- 风险接受须记录接受人、范围、补偿控制、到期日、复审条件，不得永久豁免。

---

## 9. 验收标准与测试映射

### 9.1 功能需求清单

| 编号 | 需求 | 落点 | 状态 |
|---|---|---|---|
| FR-RAG-001 | 语料来源校验状态机：`PENDING → IN_REVIEW → VERIFIED / REJECTED`，每次转移有责任角色、证据与留痕 | §2.3/§2.4 | 🔴 未实现 |
| FR-RAG-002 | 权威等级判定：等级与 ISBN/DOI 自洽（DDL 已强制）；租户门槛 `min_trust_tier` 生效 | §3.2/§3.3 | 🟡 部分（DDL 已强制，查询未实现） |
| FR-RAG-003 | 冲突仲裁：跨等级必须展示分歧；同等级必须并列呈现、不得静默择一 | §3.5 | 🔴 未实现 |
| FR-RAG-004 | 校验结论过期（`ver_valid_days` / `isStale`）后：**不降权 `trust_tier`，但阻断普通用户召回**；过期 ≠ 撤回 | §3.4 | 🔴 未实现 |
| FR-RAG-005 | 版权治理：四取值判定标准；受限授权语料的引用表现（正文不展示、定位保留、接地失效） | §4.2/§4.4 | 🔴 未实现 |
| FR-RAG-006 | 撤回：`REVOKED` 必须从索引移除（状态 + 查询期硬过滤 + 索引层纵深） | §5.3 | 🔴 未实现 |
| FR-RAG-007 | 历史引用：保留并标注失效，**不删除**；逐字引文脱敏 | §5.4 | 🔴 未实现（DDL 约束相反） |
| FR-RAG-008 | 污染识别：ISBN/DOI、`OFFICIAL_HASH`、抽样人工比对、`content_hash` 组合判定 | §6 | 🔴 未实现 |
| FR-RAG-009 | 访问审计：5 种 action 各有写入时机；「先写审计，后做动作」 | §7.1 | 🔴 未实现 |
| FR-RAG-010 | 审计表追加不可改；保留期 180 天；不含敏感原文 | §7.2/§7.3/§7.4 | 🟡 部分（DDL 触发器已实测 ✅，保留期作业未实现） |
| FR-RAG-011 | 引用溯源链路不可被破坏（INV-4 a/b/c/d） | §5.5 | 🔴 **未实现，当前行为相反** |
| FR-RAG-012 | 「入库可、检索不可」在字段级分离（`trust_tier` / `ver_status` / `min_trust_tier` 三轴） | §3.3 | 🟡 部分（领域层已实现，查询未实现） |
| FR-RAG-013 | 论文/预印本不得单独支撑教学结论（`standaloneSupportAllowed`） | §3.5.5 | ✅ 领域层已实现并测试 |
| FR-RAG-014 | 生产连接角色必须 `NOSUPERUSER NOBYPASSRLS`；启动自检拒绝特权连接 | §1.5 | 🔴 自检未实现 |
| FR-RAG-015 | 启动自检包含 pgvector 扩展存在性 | §1.5 | 🔴 未实现 |

### 9.2 验收测试映射

| 测试编号 | 验收内容 | 层级 | 状态 | 关联 FR |
|---|---|---|---|---|
| AT-RAG-001 | `ver_status != 'VERIFIED'` 的来源，其片段**不得**出现在可检索结果中 | 集成（需持久层） | 🔴 未实现 | FR-RAG-001/012 |
| AT-RAG-002 | `VERIFIED` 且 `ver_evidence IS NULL` → 写入被拒（CHECK 约束） | DDL 反证 | ✅ **已实测**（D00 §5，`check_violation` PASS） | FR-RAG-001 |
| AT-RAG-003 | `trust_tier='OFFICIAL_PUBLISHER'` 且 `isbn IS NULL` → 写入被拒 | DDL 反证 | ✅ **已实测**（D00 §5，PASS） | FR-RAG-002 |
| AT-RAG-004 | 校验结论过期（`verified_at + ver_valid_days < now()`）→ 不得被普通用户检索 | 集成 | 🔴 未实现 | FR-RAG-004 |
| AT-RAG-005 | 过期**不**改变 `trust_tier`；过期**不**等于 `REVOKED` | 单元 | 🔴 未实现 | FR-RAG-004 |
| AT-RAG-006 | `PEER_REVIEWED_PAPER` / `PREPRINT` 的 `standaloneSupportAllowed()` 为 `false` | 单元 | ✅ **已实测**（`KnowledgePointAndAuthorityTest.standaloneSupportRestricted`） | FR-RAG-013 |
| AT-RAG-007 | `trust_tier` 低于租户 `min_trust_tier` → 不得被该租户用户检索 | 集成 | 🔴 未实现 | FR-RAG-002/012 |
| AT-RAG-008 | 同等级冲突 → 并列呈现两种表述 + 标注分歧；**不得**输出单一结论 | 评测 | 🔴 未实现 | FR-RAG-003 |
| AT-RAG-009 | 跨等级冲突 → 高等级结论 + **必须出现分歧标注** | 评测 | 🔴 未实现 | FR-RAG-003 |
| AT-RAG-010 | `license_status='RESTRICTED'` → 不得被检索；不得出现逐字引文 | 集成 | 🔴 未实现 | FR-RAG-005 |
| AT-RAG-011 | `license_status='PENDING'` → 不得被普通用户检索 | 集成 | 🔴 未实现 | FR-RAG-005 |
| AT-RAG-012 | `ver_status='REVOKED'` → 其片段不得被召回（含向量检索路径） | 集成 | 🔴 未实现 | FR-RAG-006 |
| AT-RAG-013 | 撤回后 `rag_answer_citation` 记录**仍存在**且带失效标记 | 集成 | 🔴 未实现 | FR-RAG-007 |
| AT-RAG-014 | `DELETE FROM rag_chunk` **不得**级联删除 `rag_answer_citation` | DDL 反证 | 🔴 **未实现，当前行为相反**（V1 为 `ON DELETE CASCADE`） | FR-RAG-011 |
| AT-RAG-015 | `rag_access_audit` 的 UPDATE / DELETE 被触发器拒绝 | DDL 反证 | ✅ **已实测**（D00 §5，触发器反证 PASS） | FR-RAG-010 |
| AT-RAG-016 | 5 种 action 各在规定时机写入；`REVOKE` 先于状态变更 | 集成 | 🔴 未实现 | FR-RAG-009 |
| AT-RAG-017 | 审计表中不出现 `verbatim_quote` / `chunk.text` / query 原文 | DDL + 集成 | 🟡 DDL 天然满足；写入侧未实现 | FR-RAG-010 |
| AT-RAG-018 | 非特权角色跨租户读/写隔离 | 集成 | ✅ **已实测**（D00 §5，`app_agent`） | FR-RAG-014 |
| AT-RAG-019 | 连接角色为超级用户 → 应用**拒绝启动** | 启动自检 | 🔴 未实现 | FR-RAG-014 |
| AT-RAG-020 | 缺 `vector` 扩展 → 应用**拒绝启动** | 启动自检 | 🔴 未实现（D00 §5.2 已实测镜像缺陷） | FR-RAG-015 |
| AT-RAG-021 | `content_hash` 失配 → 检出语料被静默替换 | 集成 | 🔴 未实现（整册哈希口径未定义） | FR-RAG-008 |
| AT-RAG-022 | ISBN 校验位错误 → 校验失败，不得置 `VERIFIED` | 单元/集成 | 🔴 未实现 | FR-RAG-008 |
| AT-RAG-023 | `OFFICIAL_HASH` 不匹配 → 检出篡改 | 集成 | ⚠️ **待实测**（依赖出版社渠道，现实中极难获得） | FR-RAG-008 |
| AT-RAG-024 | 每次 `CITE` 均有对应 `rag_answer_citation` 记录，二者一一对应 | 集成 | 🔴 未实现 | FR-RAG-009/011 |
| AT-RAG-025 | **载体级撤回**：下架一个 `rag_document`（扫描件）后，其片段不可被召回，而**同一 `edition` 下的官方电子版仍可被召回** | 集成 | 🔴 **未实现**（`rag_document` 无状态字段，RAG-R20） | FR-RAG-006 |
| AT-RAG-026 | `parse_status='CHUNKED'` 的载体，其所属 `source` 必须 `ver_status='VERIFIED'` | 集成 | 🔴 未实现（RAG-R21） | FR-RAG-001 |
| AT-RAG-027 | `DELETE FROM rag_document` **不得**级联删除其下 `rag_answer_citation` | DDL 反证 | 🔴 **未实现，当前行为相反**（V1 为 `ON DELETE CASCADE`） | FR-RAG-011 |

### 9.3 门禁

| 门禁 | 内容 | 状态 |
|---|---|---|
| **G-1 语料接入门禁** | AT-RAG-001/007/010/011/012 全部通过前，**不得**接入任何真实教辅语料 | 🔴 未满足 |
| **G-2 撤回可用性门禁** | AT-RAG-012/013/014/025/027 全部通过前，**不得**上线对外服务 | 🔴 未满足 |
| **G-3 审计可用性门禁** | AT-RAG-016/017 全部通过前，**不得**上线对外服务 | 🔴 未满足 |
| **G-4 生产准入门禁** | AT-RAG-019/020 通过 + 角色符合 `NOSUPERUSER NOBYPASSRLS` + 镜像为 `pgvector/pgvector:pg17` | 🔴 未满足 |
| **G-5 授权前置** | RD-Q4（授权形态）确认 + 法务对 L-1/L-2/L-3 三项书面结论 | 🔴 未满足 |

> 阶段门禁与主平台接入的对应关系见 RAG-D09 §3 与 §2.3。
> 本文档的门禁 G-1～G-5 是**子项目内部**门禁，不等于主平台阶段门禁。

---

## 10. 已知限制与后续动作

### 10.1 已知限制（本文档的诚实边界）

| # | 限制 | 说明 |
|---|---|---|
| L-1 | **全部治理逻辑尚未在运行路径上实现** | `RetrievalOrchestrator`（检索）、`IngestionPipeline`（入库）**均无**权威/校验/版权过滤。`rag-infrastructure` 只有 DDL 无 Java；`rag-api`/`rag-worker`/`rag-bootstrap` 只有 `pom.xml` |
| L-2 | **DDL 的既有约束中有两处与本文档要求冲突** | ① `rag_citation_chunk_fk ... ON DELETE CASCADE`（与 INV-4-b 冲突，会抹除历史引用）；② `rag_citation_quote_present_chk`（阻止失效引用脱敏）。二者**必须**由 V2 迁移修复 |
| L-3 | **存储层无权威闸门** | `rag_chunk_retrievable_idx` 只处理 role 维度；`rag_chunk` 无源状态列。撤回与「未校验不可检索」目前**只有应用层一道防线**（而该防线也未实现） |
| L-4 | **`rag_source.content_hash` 允许为空且口径未定义** | 整册篡改检测无法开展；`OFFICIAL_HASH` 比对无基准 |
| L-5 | **授权形态未定** | RD-Q4 未确认；`FAIR_USE_QUOTATION` 的适用边界、引文长度上限、章节级连续输出阈值**均为空白** |
| L-6 | **整册原文不入库的口径存在实现张力** | `rag_chunk.text` 存片段正文，与 V1 注释的「库内只存引用 + 校验和」表述不一致（§4.1.1） |
| L-7 | **审核角色无法被系统强制** | 主平台 IAM（D-001）尚未提供 issuer 与测试账号；§2.2 的角色分工目前是**制度性**而非**技术性** |
| L-8 | **「回放」与「脱敏」语义冲突未裁决** | `request_digest` 注释为「便于回放」，与「不含敏感原文」不能兼得（§7.4） |
| L-9 | **过期扫描、保留期清理、撤回执行、审计写入均无实现** | `rag-worker` 无源码；`rag-source` 无审计写入点 |
| L-10 | **冲突自动识别能力缺失** | `GroundingVerdict.Verdict.CONFLICTING` 枚举存在，但冲突判定链路未实现；当前只能人工抽检发现 |
| L-11 | **论文/预印本的实际使用形态未定义** | RD-Q5 未确认；「延伸阅读」的呈现位置、数量上限、是否允许进入 `paper` 角色片段均待定 |
| L-12 | **本文档的合规结论未经法务确认** | §4 全部内容为**技术侧设计建议**，不构成法律意见 |
| L-13 | ⚠️ **载体层（`rag_document`）缺状态字段** | 载体级撤回无法被表达；其唯一现行手段（物理 DELETE）会抹除引用链（RAG-R20） |
| L-14 | ⚠️ **`parse_status` 与 `ver_status` 无约束关系** | 「未校验来源已切分入库」是**可持久化状态**（RAG-R21） |
| L-15 | ⚠️ **D00 §5 实测基线与当前 V1 不一致** | 新增 `rag_document` 表的约束与 RLS 策略**未经那轮实测**（RAG-R23） |

### 10.2 待实测清单

| # | 待实测项 | 前置条件 | 阻断对象 |
|---|---|---|---|
| T-1 | 检索期权威闸门的实际拦截效果 | 持久层 + `RetrievalPort` 实现 | G-1 |
| T-2 | 过期源在向量/词法两条通道上的实际拦截 | 同上 | G-1 |
| T-3 | 撤回后已嵌入向量的片段是否真的不可召回 | V2 冗余列 | G-2 |
| T-3′ | **载体级撤回**的实际效果：下架扫描件后官方电子版是否仍可召回 | V2 载体状态字段 + 冗余列 | G-2 |
| T-3″ | `rag_document` 新表的 DDL 约束与 RLS 隔离是否与既有表一致 | 真实 PG 17 + pgvector 实例 | RAG-R23 |
| T-4 | 审计写入的**性能影响**（`RETRIEVE` 逐条写 vs 逐请求写） | 持久层 | G-3 |
| T-5 | 100 万片段规模下的治理查询开销（JOIN `rag_source` 的过滤成本） | 数据准备 | RAG-D08 §6 |
| T-6 | `OFFICIAL_HASH` 比对的实际可得性 | 出版社授权 | G-5 |
| T-7 | 片段级正文入库的合规结论 | 法务意见 | L-6 |
| T-8 | 启动自检在真实部署形态下的有效性 | 部署环境 | G-4 |

### 10.3 待确认项

| 编号 | 待确认 | 建议默认值 | 影响 | 责任 |
|---|---|---|---|---|
| **L-A** | 撤回 SLA 的具体数值 | 版权/盗版类 ≤ 4h 置 `REVOKED`，≤ 24h 清理对象存储 | §5.2 | 项目负责人 |
| **L-B** | 失效引用的处置（保留元数据 + 脱敏原文）是否满足权利人要求 | 按当前方案 | §5.4 | **法务** |
| **L-C** | 「短引文 + 定位」中引文长度上限、章节级连续输出的判定阈值 | 无默认值，**须法务给数值** | §4.3 | **法务** |
| **L-D** | 片段级正文入库的合规边界；「整册 vs 片段」口径澄清 | 无默认值 | §4.1.1 / L-6 | **法务** |
| **L-E** | 审计表 180 天到期清理与「审计不可删」的冲突裁决 | 两人授权 + 全程留痕 | §7.5 | **法务/安全** |
| **L-F** | 冲突的升级裁决流程与 SLA | 教研专家 + 语料负责人 | §3.5.3 | 教研 |
| **L-G** | 「纸张等级」双判定人制度（采集人不得自审） | 强制双人复核 | §2.2 | 审核负责人 |
| **L-H** | 生产租户 `min_trust_tier` 的**下限**（建议 `OFFICIAL_TUTORIAL`） | `OFFICIAL_TUTORIAL` | §3.3 注 / RAG-R07 | 架构/安全 |
| **L-I** | `request_digest` 裁决：放弃「回放」改为「去重摘要」 | 加盐 HMAC | §7.4 / RAG-R14 | 后端/安全 |
| RD-Q4 | 教辅 PDF 的授权形态（承接 RAG-D00） | — | 决定 `license_status` 实际取值 | 法务/项目负责人 |
| RD-Q5 | 论文语料范围与检索用途（承接 RAG-D00） | 限「教学拓展阅读」 | 决定论文是否可进入 L3 | 项目负责人 |
| RD-Q7 | 租户默认 `min_trust_tier`（承接 RAG-D00） | `OFFICIAL_TUTORIAL` | 决定默认可检索范围 | 项目负责人 |

### 10.4 后续动作

| # | 动作 | Owner | 依赖 | 目标 |
|---|---|---|---|---|
| N-1 | 编写 **V2 迁移**：来源级 + **载体级**状态冗余列 + 扩展部分索引；`ON DELETE CASCADE` 改 `RESTRICT`；`quote_present` 约束调整；新增 `invalidated_at`/`invalid_reason`/`ver_reject_reason`/`review_started_at`；`license_status` 改枚举 | DBA/后端 | 本文 §2.6/§5.3.3/§5.5 | 关闭 L-2/L-3/RAG-R03/04/05/20 |
| N-2 | 实现**查询期权威闸门**（JOIN `rag_source` 的统一过滤 SQL）与**入库前置闸门** | 后端/安全 | N-1 | 关闭 RAG-R01/02 |
| N-3 | 实现**过期判定 + 到期扫描 + 提前提醒** | 后端 | N-2 | 关闭 RAG-R06 |
| N-4 | 实现**审计写入**（5 种 action）与**保留期清理作业**（DBA 脚本） | 后端/DBA | N-2 | 关闭 RAG-R12/17 |
| N-5 | 实现**启动自检**（§1.5 四项） | 后端/DBA | — | 关闭 RAG-R08/09 |
| N-6 | 实现**角色化审核通道**（不通过 `min_trust_tier`）+ 租户门槛下限约束 | 架构/安全 | IAM（D-001） | 关闭 RAG-R07/18 |
| N-7 | 法务确认 L-B/L-C/L-D/L-E 四项，出具书面结论 | 法务 | RD-Q4 | 关闭 RAG-R10/11、解除 L-5/L-6 |
| N-8 | 编写评测集用例：跨等级冲突、同等级冲突、论文单独支撑、盗版样例 | AI/教研 | RAG-D08（待撰写） | 关闭 RAG-R15/16 |
| N-9 | 明确**整册 `content_hash` 的规范化口径**并落为规范（载体级为权威） | 架构 | — | 关闭 L-4、RAG-R22 |
| N-10 | ⚠️ **补跑 `rag_document` 的 DDL 约束反证 + 跨租户隔离（用非特权角色）**，并同步更新 RAG-D00 §5 的表数基线 | DBA/安全 | — | 关闭 RAG-R23 |
| N-11 | ⚠️ **V2 为 `rag_document` 增加状态字段**（`ver_status` / `retrievable` / `revoked_at`），并为 `rag_chunk` 增加**载体级**冗余列，使「只下架扫描件、保留正版」可落地 | 后端/DBA | N-1 | 关闭 RAG-R20/21 |

### 10.5 复审计划

| 时机 | 触发条件 | 复审范围 |
|---|---|---|
| 首批语料接入前 | N-1～N-3 完成 | §2、§3 全部；G-1 门禁 |
| 首次真实撤回演练 | 任一触发源发生 | §5、§7 全部 |
| 授权文件签署后 | RD-Q4 落定 | §4 全部；L-5/L-6/L-7 |
| P4 生产准入 | IAM 接入完成 | §1.5、§7.2、§7.5 |
| 评测集建立后 | RAG-D08 产出 | §3.5 冲突仲裁；RAG-R15/16 |
| **ISO-5 外部抽检** | 每季度 / Sev-0/1 事件后 | **全文档**（由主平台项目负责人抽检） |

> ⚠️ 本文档由 AI 起草，存在与 AI 自身盲区相关的风险（主平台 R-016）。
> 建议在签署前由 zhouwei 或外部人员独立复核 §4（合规）与 §3.5（仲裁规则）两章。

### 10.6 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-20 | 初稿：建立准入流水线、权威等级与冲突仲裁、版权治理、撤回与溯源保全、盗版识别、访问审计六套规则；登记 23 条风险与 27 项验收测试；**全部运行路径实现仍为待办** |
| 2026-10-20 | ⚠️ 修订：适配 V1 迁移新增的 `rag_document` **载体层**（11 表基线）——补充载体级撤回能力与其 schema 缺口（RAG-R20/21/22）、`carrier` 作为盗版信号、载体级 `content_hash` 作为 `OFFICIAL_HASH` 基准、级联链更新、审计缺 `document_id`、D00 §5 基线漂移（RAG-R23） |

---

**下一动作**：N-1（V2 迁移）与 N-2（查询期/入库期闸门）已作为**首批语料接入的前置**排入 RAG-D09 阶段计划（见 RAG-D09 §4.4：N-1 → R1-4、N-2 → G1 条件⑤、N-3 → R2、N-10 → R1 阶段内）。
