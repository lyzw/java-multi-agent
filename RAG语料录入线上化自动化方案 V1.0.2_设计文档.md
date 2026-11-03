# RAG 语料录入线上化、自动化 —— 设计文档

| 字段 | 内容 |
|---|---|
| 文档类型 | 设计文档（Technical Design） |
| 对应方案 | 《RAG语料录入线上化自动化方案 V1.0.2》 |
| 方案版本 | **v1.0.2** |
| 编制日期 | 2026-11-02 |
| 阅读前提 | 已读 V1.0.2 方案；本文给出其设计层落地方式，不重复论证取舍 |
| 待确认项 | 以【待确认】标注，不自行假设（见 §10 汇总） |

---

## 1. 设计总览

### 1.1 设计目标与立场

把"离线脚本 + DBA 手执行 SQL + 管理端签发"的断裂链路，升级为**一条自服务录入主链路**：上传 → 同步预检 → 异步流水线（解析→清洗→切分→标注→落库→嵌入）→ 人工审核 → 检索准入。设计立场与方案一致：**接线重于新建、自动化终点=人工起点、四轴闸门已实现只保不退化**。

### 1.2 架构分层（不新增 Maven 模块）

```
┌─────────────────────────────────────────────────────────┐
│ frontend/src/pages/rag/   RagIngestPage / RagInspectionPanel │
└──────────────────────────┬──────────────────────────────┘
                       HTTP │ /api/v1/management/**
┌──────────────────────────▼──────────────────────────────┐
│ rag-api            IngestJobController（新增）            │
│ rag-management     importDocument 改造 / 抽检 / 别名 / 审核强制 │
├──────────────────────────────────────────────────────────┤
│ rag-application    IngestionPipeline（生产化）             │
│                    IngestJobOrchestrator（新增：编排+落库）  │
│ rag-core           移植的清洗/切分纯函数 / 去重判定 / 抽检规则 │
├──────────────────────────────────────────────────────────┤
│ rag-ports          DocumentParserPort(不变) / OcrPort(新增) │
│ rag-infrastructure Docling适配器 / BGE-M3适配器 /           │
│                    JdbcRetrievalRepository(四轴已实现,保回归) │
├──────────────────────────────────────────────────────────┤
│ rag-worker         IngestJobSweeper + SKIP LOCKED 消费循环  │
│ rag-bootstrap      装配 + 启动自检（Docling/嵌入可达 fail-closed）│
└──────────────────────────────────────────────────────────┘
              PostgreSQL（RLS + 部分唯一索引 + rag_ingest_job）
```

### 1.3 数据流主线

1. **上传**：`importDocument` 落桶（ObjectStorePort）+ 服务端 SHA-256 + 建 `rag_document(PENDING)` + 建 `rag_ingest_job(PENDING)`，返回 `jobId`。
2. **异步消费**：`rag-worker` 以 `SELECT ... FOR UPDATE SKIP LOCKED` 领取 PENDING job，驱动 `IngestJobOrchestrator` 分段执行。
3. **流水线**：`DocumentParserPort.parse()` → `LayoutBlock[]` → 清洗/切分（rag-core 移植规则）→ 角色判定/知识点挂接（`IngestionPipeline`）→ `List<Chunk>`。
4. **落库**：`IngestJobOrchestrator` 批量 `INSERT ... ON CONFLICT DO UPDATE` 并清 `text_jieba`，回写 job 进度与状态。
5. **嵌入**：`ChunkIndexer`（判据 `text_jieba='' OR 无向量行`）补向量。
6. **审核**：抽检（`inspection_status='PASSED'`）+ 双人复核 → `VERIFIED`（人工）。
7. **准入**：`JdbcRetrievalRepository.admissiblePredicate` 四轴谓词（已实现）在检索期硬过滤。

---

## 2. 核心数据结构

### 2.1 既有类型（直接复用，不改动）

| 类型 | 位置 | 说明 |
|---|---|---|
| `LayoutBlock` | `rag-ports` | 解析最小版面单元：`type/text/pageNumber/headingLevel/sectionPath/exerciseNo/inAnswerZone/charStart/charEnd`（顶层 record，受 `NestedTypeImportGuardTest` 保护） |
| `ParsedDocument` | `rag-ports` | `record ParsedDocument(documentId, List<LayoutBlock> blocks)` |
| `BlockType` | `rag-ports` | `HEADING/PARAGRAPH/LIST_ITEM/TABLE/FORMULA/FIGURE/EXERCISE/ANSWER/BOILERPLATE`；`isHeading()`/`isNoise()` |
| `ChunkRole` | `rag-core` | 十类角色（`EXPLANATION/EXERCISE/EXERCISE_ANSWER/EXERCISE_HINT/EXAMPLE_SOLUTION/APPENDIX` 等）；`isAnswerLike()` 判定泄题风险 |
| `EditionCoordinate` | `rag-core` | 五轴版本坐标 `record`（subject/grade/term/publisher/revision_code） |
| `KnowledgePoint` | `rag-core` | `record(id, subject, grade, canonicalName, aliases, prerequisites, facets)`；**已内置 `aliases` 列表 + `matches(normalizedText)` 别名匹配 + `normalize()`** |
| `Chunk` | `rag-core` | 片段领域对象（含 `Locator`：pageNumber/sectionPath/exerciseNo/charStart/charEnd） |

> **设计要点（衔接说明）**：`KnowledgePoint` 的别名匹配机制**已存在**（内存态）。V1.0.2 新建 `rag_kp_alias` 表的职责是**为该内存机制提供持久化载体与管理入口**，而非新建匹配逻辑——别名维护端点写库后，知识点加载层将 `rag_kp_alias.alias_text` 映射进 `KnowledgePoint.aliases` 供 `matches()` 消费。

### 2.2 数据库变更（落「01 DDL 终态快照 + 活库等价 ALTER」纪律）

#### 2.2.1 `rag_ingest_job`（任务事实源，job 侧零新增表）

| 变更 | 定义 | 设计理由 |
|---|---|---|
| 加列 `stage` | `text`：阶段内进度（如 `PARSING 60%` 的百分比） | 状态枚举是阶段级，`stage` 记录阶段内进度，二者互补 |
| 加列 `attempt` | `int DEFAULT 0` | 重试次数（配合"重试=新建行"，记录该 job 是第几次尝试） |
| 加列 `created_by` | `uuid` | 触发人（审计留痕，配合去哨兵后的真实主体） |
| 加索引 | `(tenant_id, status)`、`(status, created_at)` | 该表当前零二级索引；SKIP LOCKED 领取按 `status='PENDING'` 过滤、Sweeper 按 `status IN (进行中) AND started_at < now()-interval` 扫描，无索引则全表扫描 |

> **状态机映射**：消费循环沿用枚举 `PENDING/PARSING/CHUNKING/EMBEDDING/INDEXING/SUCCEEDED/FAILED/REJECTED`，**不改 ENUM**（P5）。`stage` 列与状态枚举正交。

#### 2.2.2 去重兜底——部分唯一索引（N1 的设计落地）

| 索引 | DDL 形态 | 设计理由 |
|---|---|---|
| `rag_document` 载体级 | `CREATE UNIQUE INDEX ... ON rag_document (tenant_id, content_hash) WHERE ver_status <> 'REVOKED' AND parse_status <> 'FAILED'` | 排除已撤回/解析失败载体。**保留两条合法路径**：① 载体 REVOKED 后经新来源重传同文件；② 解析失败（`parse_status='FAILED'`）后同文件重传重跑 |
| `rag_source` 来源级 | `CREATE UNIQUE INDEX ... ON rag_source (tenant_id, isbn) WHERE isbn IS NOT NULL AND ver_status <> 'REVOKED'`（DOI 同理） | 排除 REVOKED 来源。**保留 T-10"撤回后新建 source_id 重登记"路径**（D07 L272：REVOKED 终态须新建 source_id） |

> **"重复"的显式定义（设计不变式）**：`重复 ≡ 存在活跃对象（来源 ver_status<>'REVOKED'；载体 ver_status<>'REVOKED' AND parse_status<>'FAILED'）且同坐标/同哈希`。
> **两层同口径**：应用层预检（Z3）的 WHERE 条件必须与上述索引的 WHERE 子句**逐字一致**，否则出现"预检放行、约束 500"或"预检拒收、约束可入"的两层打架。

#### 2.2.3 新增表

| 表 | 关键列 | 设计说明 |
|---|---|---|
| `rag_inspection_record` | `record_id / tenant_id / source_id / chunk_id / inspector_id / expected_role / actual_role / error_type / verdict / created_at / scope` | 抽检明细。RLS 同其余表（`tenant_isolation` + PLATFORM 全局档规则）。`verdict ∈ {PASS, FAIL}`；`error_type` 枚举化（漏判答案区/题号丢失/坐标错误/其他） |
| `rag_kp_alias` | `alias_id / tenant_id / kp_id / alias_text / scope / created_at` | 知识点别名。外键 `(tenant_id, kp_id)` → `rag_knowledge_point`。**不复用 `rag_dictionary`**（其 `dict_type/code` 通用结构无法表达"某 kp_id 的别名集合"）。RLS 同其余表 |

#### 2.2.4 `rag_source` 加列

| 变更 | 定义 | 设计说明 |
|---|---|---|
| `inspection_status` | `text DEFAULT 'NOT_REQUIRED'`：`NOT_REQUIRED/PASSED/FAILED` | R2 抽检作为 VERIFIED 前置守卫的落点。VERIFIED 迁移时校验该列=`PASSED` |

#### 2.2.5 `rag_tenant.min_trust_tier`（前置依赖，N/待确认）

| 项 | 说明 |
|---|---|
| 现状 | **DDL 当前无 `rag_tenant` 表/该列**（全库 grep 无结果）。设计文档将其列为 G-3 门槛租户维度化的**前置**，非本方案默认交付 |
| 设计意图 | `min_trust_tier rag.rag_trust_tier DEFAULT 'OFFICIAL_TUTORIAL'`，租户管理员可设本租户门槛（D07 §2.2/§3.3），**不得降至 UNVERIFIED**（D07 §3.3 注） |
| 落地路径【待确认】 | ① 依 RAG-R23 由 platform 下发字段；② 本方案新建 `rag_tenant`。两条路径的归属方与时序未在 V1.0.2 明确，列为待确认（见 §10 T-1） |

---

## 3. 核心接口定义

### 3.1 端口契约（rag-ports，不嵌套类型）

| 接口 | 方法签名 | 实现 | 说明 |
|---|---|---|---|
| `DocumentParserPort`（不变） | `ParsedDocument parse(String documentId, byte[] content)` | **Docling 适配器**（新增，生产）替换 `LocalDeterministicDocumentParserPort`（占位） | born-digital PDF → `LayoutBlock[]`；契约不变 |
| `OcrPort`（新增，Z5b 试点） | 【待确认 T-2】签名方案未给出。建议方向：`ParsedDocument parse(String documentId, byte[] scannedPdf)` 复用 `ParsedDocument`，与 `DocumentParserPort` 同输出 | 扫描件 OCR 服务适配器 | 试点/灰度，不计入 S2 验收 |
| `EmbeddingPort`（不变） | 既有契约 | **BGE-M3 适配器**（新增）替换 `LocalDeterministicEmbeddingPort`（占位） | 维度经 `EmbeddingPort.dimension()` 与向量列对齐（启动校验） |

### 3.2 应用层新增组件

| 组件 | 职责 | 关键设计 |
|---|---|---|
| `IngestJobOrchestrator`（rag-application） | job 领取→分段执行→进度回写→**片段落库** | ① 从 `rag-worker` 领取 job；② 调 `DocumentParserPort` → `IngestionPipeline` 得 `List<Chunk>`；③ 调 `CorpusRepository` 批量 `INSERT ... ON CONFLICT DO UPDATE` **并清 `text_jieba`**；④ 逐阶段回写 `status`+`stage`；⑤ 异常置 `FAILED`+`error_detail`。**整册失败不部分入库**——单册内所有片段写库在同一事务，任一整册级失败整体回滚 |
| 清洗/切分纯函数（rag-core） | `stripBoilerplate` / 附录切分 / 目录探测等 | 从 Python `rag_import_pdf.py` 移植为无副作用纯函数，输入 `LayoutBlock[]` 输出处理后的块序列。**完成判据：与离线脚本对同一册切分结果 diff 归零** |

### 3.3 HTTP 接口（rag-api / rag-management）

| 端点 | 方法 | 类型 | 请求→响应要点 |
|---|---|---|---|
| `/api/v1/management/sources/{id}/documents` | POST | 改造 | MULTIPART 上传；删除 `registeredBy` 哨兵回落，无令牌主体→401；校验+落桶+建 job，**201 返回 `jobId`** |
| `/api/v1/management/sources/batch` | POST | 改造 | 支持 ZIP+CSV 清单；逐条独立建 job，响应含逐条成功/失败明细 |
| `/api/v1/management/ingest-jobs` | GET | 新增 | 列表（按 status/source/时间过滤），分页 |
| `/api/v1/management/ingest-jobs/{id}` | GET | 新增 | 详情：`status`+`stage` 分段进度 + `error_detail` + 耗时 |
| `/api/v1/management/ingest-jobs/{id}/retry` | POST | 新增 | 人工重试（运维角色）；**新建 job 行**（旧行留痕不覆盖） |
| `/api/v1/management/sources/{id}/inspection` | POST/GET | 新增 | 提交/查询抽检记录；提交后回写 `rag_source.inspection_status` |
| `/api/v1/management/kp-aliases` | POST/GET | 新增 | 别名维护（读写 `rag_kp_alias`） |
| `/api/v1/management/statistics` | GET | 改造 | 追加 job 汇总（失败率**剔除 `SOURCE_REVOKED`**）+ 闸门拦截计数 |

> **错误码约定**：同步预检失败返回 400 + 错误码（`DUP_SOURCE`/`DUP_EDITION`/`DUP_CARRIER`/类型不符等），**零落库**；并发穿透（两请求同时过预检）由部分唯一索引拒绝，捕获 `DuplicateKeyException` 映射为对应 `DUP_*` 400。非法状态流转返回 409（前端呈现"这一步不被允许"）。

---

## 4. 关键流程设计

### 4.1 上传 + 同步预检（②）

```
importDocument(file, sourceId, principalAttr)
  ├─ 无令牌主体 → 401（删除哨兵回落）
  ├─ Z1 后缀/大小/魔数校验        → 失败 400，不入桶
  ├─ Z2 服务端 SHA-256（不信前端）
  ├─ Z3 三层去重预检（活跃口径，与索引同 WHERE）
  │     ├─ 来源级 ISBN/DOI 命中   → 400 DUP_SOURCE
  │     ├─ 版本级五轴命中         → 400 DUP_EDITION
  │     └─ 载体级 content_hash    → 400 DUP_CARRIER
  │     （并发穿透由部分唯一索引兜底，DuplicateKeyException→对应 400）
  ├─ 落桶 ObjectStorePort.store()
  ├─ 建 rag_document(PENDING) + rag_ingest_job(PENDING)
  └─ 201 返回 { documentId, jobId }
```

### 4.2 异步流水线（③）——IngestJobOrchestrator

```
领取 job (FOR UPDATE SKIP LOCKED, status=PENDING)
  → status=PARSING
      DocumentParserPort.parse() → LayoutBlock[]
      （Z5a Docling；扫描件走 Z5b OcrPort）
      清洗（rag-core stripBoilerplate 移植）
      失败 → status=FAILED + error_detail（整册不入库）
  → status=CHUNKING
      结构化切分（rag-core 附录 A-1~A-3 / 目录探测移植）
      → IngestionPipeline：角色判定（宁可过判答案类）+ 知识点挂接
      → List<Chunk>
  → status=EMBEDDING（片段落库）
      单事务批量 INSERT ... ON CONFLICT DO UPDATE，清 text_jieba
      整册级失败 → 整体回滚 → status=FAILED（不部分入库）
  → status=INDEXING
      ChunkIndexer 补向量（判据 text_jieba='' OR 无向量行）
      嵌入超时指数退避重试 3 次，仍失败 → status=FAILED
  → status=SUCCEEDED，写 chunk_count/finished_at
  全程 stage 列回写阶段内百分比
```

> **来源 REVOKED 级联（N2）**：撤回时将该来源未完成 job 置 `REJECTED` + `error_detail='SOURCE_REVOKED'`；`/statistics` 失败率统计剔除该明细；Sweeper 与此共用语义。

### 4.3 人工审核（④）

抽检（R2）→ `inspection_status='PASSED'` 是 `IN_REVIEW→VERIFIED` 的**前置守卫**（迁移条件扩展，非枚举变更）。VERIFIED 还需双人复核 + `审核员≠采集员`（服务端比对 `registered_by`/`verified_by`，相同 409）。

### 4.4 检索准入（⑤）——P1 反转的设计落地

> **设计更正**：四轴闸门**已实现**于 `JdbcRetrievalRepository.admissiblePredicate`（`ADMISSIBLE_TEMPLATE` L147-155，单一真源 · 6 处 SELECT 共用），本方案**不重建**，只做两件设计动作：

| 设计动作 | 落地方式 |
|---|---|
| **回归防线（不变式 5）** | ① 行为级双向 IT（主防线）：过期/低等级/版权受限/载体撤回四类夹具 0 命中、达标夹具可召回，各有反证；② 架构测试：拦截任何新增**绕过 `admissiblePredicate`** 的检索 SELECT（不用脆弱 grep） |
| **G-3 门槛维度对齐** | 现状：门槛是全局配置（`application.yml` `RAG_RETRIEVAL_MIN_TRUST_TIER`，构造期 `TrustTier.meets()` 展开为常量 IN 列表，改门槛需重启）。目标：查询期按请求租户读 `rag_tenant.min_trust_tier`（对齐 D07 §2.2/§3.3 设计），**保留 `meets()` 展开纪律**（防 D-6 枚举序数反转）、**保留"不读可伪造请求头"原则**（读库不读头）。**前置：`rag_tenant.min_trust_tier` 字段落地（§2.2.5，待确认 T-1）** |

---

## 5. 关键修订的设计落地对照

| 修订 | 设计落点 | 章节 |
|---|---|---|
| **P1 反转**（四轴已实现） | A11 资产定位为"回归防线+门槛对齐对象"；S2b 设计为"覆盖审计+双向回归 IT+门槛租户化"，不重写 `admissiblePredicate` | §1.3/§4.4 |
| **N1**（唯一索引冲突） | 两个**部分唯一索引**（排除 REVOKED/FAILED）+ Z3 预检同 WHERE 口径 + "重复"显式定义，保留 T-10 与失败重传 | §2.2.2/§4.1 |
| **N2**（job 取消落点） | 取消=`REJECTED`+`SOURCE_REVOKED`，失败率剔除，Sweeper 共用 | §3.2/§4.2 |
| **P5**（枚举沿用+索引） | 沿用 8 值枚举不改；补 `(tenant_id,status)`/`(status,created_at)` 索引支撑 SKIP LOCKED 与 Sweeper | §2.2.1/§3.2 |
| **P6**（OCR 边界） | Z5a born-digital 必交付 / Z5b `OcrPort` 试点，验收分离 | §3.1/§4.2 |
| **P7**（别名表） | 新建 `rag_kp_alias`，为既有 `KnowledgePoint.aliases` 内存机制提供持久化载体 | §2.1/§2.2.3 |
| **P3**（规则移植） | 清洗/附录切分/目录探测从 Python 移植为 rag-core 纯函数，完成判据=同册 diff 归零 | §3.2 |

---

## 6. 一致性与纪律守卫（设计层不变式）

1. **幂等**：活跃同哈希预检拒 + 部分唯一索引兜底；job 重试新建行不覆盖旧行。
2. **不部分入库**：单册所有片段写库在同一事务，整册级失败整体回滚。
3. **陈旧向量不可能**：落库 `ON CONFLICT DO UPDATE` 必清 `text_jieba`。
4. **自动不放行**：自动化路径不写 `ver_status='VERIFIED'`（仅 verification 端点可写，守卫测试）。
5. **检索准入不绕过**：任何检索 SELECT 不得绕过 `admissiblePredicate`（行为 IT + 架构测试）。
6. **去重两层同口径**：Z3 预检 WHERE ≡ 部分唯一索引 WHERE。

---

## 7. 待确认项汇总（不自行假设）

| # | 待确认项 | 影响 | 说明 |
|---|---|---|---|
| T-1 | `rag_tenant.min_trust_tier` 字段落地路径（RAG-R23 platform 下发 vs 本方案新建）与时序归属 | S2b 第 1 条可执行性 | V1.0.2 仅列为前置，未明确归属方/时序 |
| T-2 | `OcrPort` 的方法签名与扫描件识别率量化基线 | Z5b 试点验收 | V1.0.2 未给出 OcrPort 契约与阈值 |
| T-3 | 批量导入 ZIP 包的大小上限、单批次条目上限、ZIP 内文件与 CSV 清单的对齐/缺漏处理规则 | Z10 行为 | V1.0.2 未量化 |
| T-4 | Docling 服务的部署形态（独立容器/ sidecar）、版本与资源规格 | RD-1 / bootstrap 自检 | V1.0.2 仅述"容器化部署"，未给规格 |
| T-5 | BGE-M3 嵌入的服务端点、维度、批大小及向量列维度迁移策略 | S2 嵌入适配器 | 向量列维度须与 `EmbeddingPort.dimension()` 一致（启动校验），换维度需迁列+全量重建索引 |
| T-6 | 失败率告警阈值、Sweeper 接管频发的具体阈值与告警级别 | §7.3 可观测 | V1.0.2 未量化 |
| T-7 | 录入工作台的租户/角色可见性细节（采集员可见 job 范围是否限本人/本租户） | §6 权限矩阵落地 | 矩阵未细化 job 列表的行级可见范围 |
| T-8 | `inspection_status='NOT_REQUIRED'` 的适用场景（何种来源免检） | R2 前置守卫边界 | V1.0.2 未定义免检条件 |

---

## 8. 文档与代码的一致性声明

- 本文所有"已实现"断言均经代码核对：`admissiblePredicate`（L147-155）、`IngestionPipeline`（仅内存计算）、`ChunkIndexer` 判据、`KnowledgePoint.aliases`+`matches()`、`importDocument` 哨兵回落（L504）、`rag_ingest_status` 枚举（8 值）、`rag_edition_unique`（L774-778）、`rag_document` 仅 `(tenant_id,document_id)` 唯一、`rag_source_identifier_chk`（CHECK 非唯一）。
- `rag_tenant.min_trust_tier` 经全库 grep **不存在**，如实标注为前置依赖而非既有资产。
