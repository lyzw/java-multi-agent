# 《RAG语料录入线上化自动化方案 V1.0.1》评审意见

| 字段 | 内容 |
|---|---|
| 评审对象 | 《RAG语料录入线上化自动化方案 V1.0.1.md》（2026-11-02，v1.0.1 / 待评审） |
| 评审方式 | 复核式评审：逐条核对 V1.0.1 自称的 7 项事实修正（P1~P7）是否与代码/DDL 相符；另对修订后文本做独立缺陷扫描 |
| 评审日期 | 2026-11-02 |
| 评审结论 | **有条件通过**：P2~P7 六项修订属实、落实到位；但 **P1 建立在误读代码的错误事实上**，S2b 及其连锁条目需重写；另发现 3 个 V1.0.1 未覆盖的新问题（1 高 / 1 中 / 3 低）。建议出 V1.0.2 后定稿。 |

---

## 一、总体结论

| 维度 | 结论 |
|---|---|
| P2~P7 修正质量 | ✅ 6/6 属实且落实到位（事实核对全部通过，见 §二） |
| P1 修正质量 | ❌ **事实错误**（见 §三，附代码证据） |
| 修订引入的新问题 | ⚠️ 3 项（见 §四：N1 高 / N2 中 / N3~N5 低） |
| 建议 | S2b 重写为"门槛维度对齐 + 覆盖审计 + 回归 IT"；N1 的唯一索引加状态条件；出 V1.0.2 |

---

## 二、P2~P7 核实结果（逐条实证）

| # | V1.0.1 的修正主张 | 核实证据 | 结论 |
|---|---|---|---|
| P2 | `content_hash` 无唯一索引、ISBN 无来源级唯一约束 | `01_rag_ddl.sql`：`rag_document.content_hash` 仅有 CHECK 约束无唯一索引；`rag_source` 仅有 `rag_source_identifier_chk`（CHECK 某等级必须有 ISBN/DOI，**非唯一性**约束）。另核实 `rag_edition_unique`（五轴唯一，DDL L774-778）确实存在——Z3 保留"②约束已存在"正确 | ✅ 属实 |
| P3 | `IngestionPipeline` 仅内存计算、不落库、无独立清洗；占位解析器存在 | `IngestionPipeline.java` 全文 0 处 `JdbcTemplate`/`Repository`/`INSERT`；`rag-infrastructure/parsing/LocalDeterministicDocumentParserPort.java` 存在 | ✅ 属实 |
| P4 | 抽检前置与状态机的表述矛盾已消解（"枚举不变、迁移条件扩展"） | 表述层面自洽；与 `VerificationTransition` 白名单机制兼容 | ✅ 通过 |
| P5 | `rag_ingest_status` 真实枚举为 8 值；job 表零二级索引 | DDL L138-147：`PENDING/PARSING/CHUNKING/EMBEDDING/INDEXING/SUCCEEDED/FAILED/REJECTED` 与文档一致；全文 grep 无 ingest_job 索引；引用的《问题记录 03》存在 | ✅ 属实 |
| P6 | OCR 与 born-digital 边界拆分（Z5a/Z5b） | 拆分后 S2 验收判据可独立验证 | ✅ 合理 |
| P7 | `rag_dictionary` 为通用词表，定调新建 `rag_kp_alias` | DDL L415：`rag_dictionary(dict_type, …)` 通用结构，确无法表达 kp 别名集合 | ✅ 属实 |

---

## 三、⛔ P1 是错误事实：检索闸门已实现，S2b 大半是重复建设

### 3.1 结论

V1.0.1 断言"G-2 过期 / G-3 权威等级 / G-4 版权三道闸门缺失"（§0 P1、§1 A11、§2.1 G-E、§8 S2b、§9 S2b、§10 RD-6、§11 第 4 条）。**该断言不成立**。

### 3.2 代码证据（`rag-infrastructure/.../persistence/JdbcRetrievalRepository.java`）

该类的**"可准入"谓词（`admissiblePredicate`，Javadoc 自述"单一真源 · 6 处 SELECT 共用"）已实现全部四轴**：

```sql
-- L148-154（构造期模板展开）
AND src.ver_status = 'VERIFIED'                                        -- G-1 来源级
AND doc.ver_status = 'VERIFIED'                                        -- G-0 载体级状态
AND doc.retrievable                                                    -- G-0 载体级撤回
AND src.license_status <> 'RESTRICTED'                                 -- G-4 来源版权
AND doc.license_status <> 'RESTRICTED'                                 -- G-4 载体版权
AND src.verified_at + make_interval(days => src.ver_valid_days) >= now()  -- G-2 过期（staleness）
AND src.trust_tier IN (%s)                                             -- G-3 权威门槛
```

且 L204-210 明确：G-3 用 `TrustTier.meets()` 构造期展开达标等级 IN 列表，注释中留有"**2026-10-27 线上 RDS 实测踩坑：`>=` 写反导致全部正常语料被滤空**"的记录——即该闸门不仅存在，还经历过线上实跑验证。

### 3.3 错误根因：读串了代码上下文

V1.0.1 §0 P1 写道："核对 `JdbcRetrievalRepository.java:105` 注释'从不读 `rag_document.ver_status` 与 `retrievable'（150 行虽已 JOIN `doc.retrievable`，但 G-2/G-3/G-4 三道闸门确缺失）"。

- L104-105 的"从不读"是 Javadoc 中**"修复前的真实缺陷"的历史描述**——该段标题就是"修复前的真实缺陷"，随后 L123-126 逐条自述"四轴齐备"。
- 评审者只读了 L104-105 半段，把"修复前"当成了"现状"；而同一段 Javadoc 的后半部分（L123-126）与 SQL 本体（L148-154）都指向相反结论。**同一文件内自相矛盾的"核对"，恰恰违反了 V1.0.1 自己引用的《问题记录 11-文档与代码漂移》要防的那类错误——只不过是反向漂移：文档（D10 L-1 的旧结论）说缺、代码已有。**

### 3.4 连锁修订清单（V1.0.2 必须改）

| 位置 | 现文（错误） | 应改为 |
|---|---|---|
| §0 P1 处置 | "三道闸门确缺失……S2b 补" | 更正为：四轴闸门已实现于 `admissiblePredicate`；剩余真实缺口仅**门槛维度**（见 §3.5） |
| §1 A11 | "G-2/G-3/G-4 三道闸门缺失" | "四轴闸门已实现（单一真源，6 处 SELECT 共用，含 2026-10-27 线上实测记录）；本方案作为**回归防线对象**而非建设对象" |
| §2.1 G-E | "闸门强制"作为新增目标 | 改述为"验收闸门覆盖完整性 + 门槛维度对齐" |
| §7.2 不变式 5 | "检索 SQL 必含 G-1~G-4……" | 改为"**任何检索 SELECT 不得绕过 `admissiblePredicate`**"（架构测试拦截新增裸 SQL） |
| §9 S2b | "补 G-2/G-3/G-4 三道 WHERE 闸门" | 重写，见 §3.6 |
| §10 RD-6 | "闸门若推迟……失效" | 改述为"门槛维度缺口若不修，多租户差异化治理落空" |
| §11 第 4 条 | "准入真拦得住（P1）" | 保留立场、改述实现现状 |

### 3.5 S2b 唯一剩余的实质缺口：G-3 门槛维度（全局 vs 租户）

核实到的真实现状：

```yaml
# rag-bootstrap application.yml L109
min-trust-tier: ${RAG_RETRIEVAL_MIN_TRUST_TIER:OFFICIAL_TUTORIAL}
```

门槛是**全局配置、进程启动期定死**（`RagInfrastructureConfig` 构造 `JdbcRetrievalRepository` 时展开为常量 IN 列表）。而 RAG-D07 §2.2/§3.3 的设计是**租户维度**：租户管理员设置 `rag_tenant.min_trust_tier`（DDL 已有该列）。

即：闸门"有"，但**门槛粒度错**——多租户下无法按租户差异化，改门槛需重启进程。这是 S2b 重写后的核心工作项：

1. 检索 SQL 的 G-3 条件从"构造期常量 IN 列表"改为"查询期按请求租户读取 `rag_tenant.min_trust_tier`"（JOIN 或参数化）；
2. **必须保留 `TrustTier.meets()` 展开纪律**（防 D-6 枚举序数反转），改造时同样踩过两次的坑不得第三次踩；
3. V1.0.1 "绝不读可伪造请求头"的原则**保留**——现状读配置不读头是正确的，改造后读库也不读头；
4. 验收判据改为：A 租户门槛 OFFICIAL_TUTORIAL / B 租户门槛 OFFICIAL_PUBLISHER 的**差异化双向 IT** + "改门槛不重启生效"断言。

### 3.6 重写后的 S2b（建议稿）

> **S2b（修订）：检索准入闸门的门槛维度对齐与回归防线**
>
> | 事项 | 验收判据 |
> |---|---|
> | G-3 门槛从全局配置改为租户维度（读 `rag_tenant.min_trust_tier`） | 双租户差异化门槛双向 IT；`meets()` 展开纪律不回退（反证用例保留） |
> | 覆盖审计：确认 6 处检索 SELECT 全部引用 `admissiblePredicate`，无裸 SQL 绕过 | 架构测试（如 ArchUnit/源码扫描）：新增绕过谓词的检索 SQL 即失败（对应不变式 5 改述） |
> | 闸门双向回归 IT（对既有实现补证据，而非补实现） | 过期/低等级/版权受限/载体撤回四类夹具 0 命中；达标夹具可召回——四类各有反证 |
> | 闸门拦截计数接入 `/statistics` | 保留 V1.0.1 原有设计 |

工作量估计从"开发项"缩为"中小型改造 + 测试项"，排期可维持与 S2/S3 并行。

---

## 四、V1.0.1 未覆盖的新问题

### N1（高）：去重唯一索引与 T-10 终态纪律、失败重传路径冲突

§8.3 新增的两个唯一索引**没有考虑状态机语义**：

| 索引 | 冲突场景 | 后果 |
|---|---|---|
| `UNIQUE (tenant_id, isbn) WHERE isbn IS NOT NULL` | T-10：REVOKED 是终态，重新准入必须**新建 source_id**。同一本被撤回的书重新登记 → 新 source_id + 同 ISBN | **被唯一索引直接拒绝，T-10 合法路径被堵死** |
| `UNIQUE (tenant_id, content_hash)`（rag_document） | ① 载体 REVOKED 后同文件经新来源重传；② 解析失败（`parse_status=FAILED`）后同文件重传重跑 | 同上；且失败处置只剩"job 重试"一条路，索引层不留活口 |

**修法（两处都要）**：
1. 唯一索引改为部分索引并排除非活跃状态：ISBN 侧 `WHERE isbn IS NOT NULL AND ver_status <> 'REVOKED'`；content_hash 侧排除 `ver_status='REVOKED'` 或 `parse_status='FAILED'` 的载体。
2. **应用层预检（Z3）的"重复"定义必须与索引条件同口径**——预检也要跳过 REVOKED/FAILED 对象，否则出现"预检说重复拒收、约束说可以入库"的两层打架（或反过来"预检放行、约束 500"）。去重的语义定义应写进 §4 Z3："重复 = 存在**活跃**（非 REVOKED / 非解析失败）的同坐标/同哈希对象"。

### N2（中）："撤回来源活跃 job 自动取消"没有落点枚举

§3.2 与 §7.1 治理类失败写"job 自动取消 + 审计记录"，但：
- `rag_ingest_status` **没有 CANCELLED 值**，且 V1.0.1 在 P5 明确"不改 ENUM、不加值"——那么取消后 job 置什么状态？
- 唯一可用的是 `REJECTED`，但 V1.0.1 未写明，且未处理连带问题：取消混入后**失败率指标与告警**会把一波合法撤回当成故障打爆（§7.3 的失败率告警阈值会被误触发）。

**修法**：明确"取消 = 置 `REJECTED` + `error_detail='SOURCE_REVOKED'`"；`/statistics` 失败率统计排除该明细；Sweeper 与 job 取消共用该语义。

### N3（低）：不变式 5 的守卫手段

"检索 SQL 缺任一闸门条件则 IT 失败"若实现为 grep SQL 文本，脆弱且易误报。建议如 §3.6：行为级双向 IT 做主防线 + 架构测试（禁止绕过谓词的新 SELECT）做守卫。

### N4（低）：§8.3 "上线前先清理存量重复"缺处置纪律

`content_hash` 唯一索引若上线遇存量重复，清理动作涉及**物理 DELETE**——应写明：清理仅限 DBA 运维通道、逐条留档（对象 ID + 处置人 + 依据），管理面 API 仍无删除端点（不破 INV-4-a）。

### N5（低）：措辞

§1 A2 "零新增表（仅补少量列与索引）"——§8.3 实际还有两张新表（`rag_inspection_record`/`rag_kp_alias`）。A2 的"零新增"仅指 job 侧，建议限定措辞避免误读为"全方案零新表"。

---

## 五、V1.0.2 修订动作清单（汇总）

| # | 动作 | 级别 | 对应章节 |
|---|---|---|---|
| A-1 | 更正 P1 处置与 A11：四轴闸门已实现，附 `admissiblePredicate` 证据 | ⛔ 必改 | §0/§1/§2.1/§11 |
| A-2 | S2b 重写为"门槛维度对齐（全局→`rag_tenant.min_trust_tier`）+ 覆盖审计 + 双向回归 IT"，保留 `meets()` 纪律与"不读请求头"原则 | ⛔ 必改 | §9 S2b/§10 RD-6 |
| A-3 | 不变式 5 改述为"不得绕过 `admissiblePredicate`" | ⛔ 必改 | §7.2 |
| A-4 | 两个唯一索引改部分索引（排除 REVOKED / 解析失败），Z3 预检口径与索引同步，给出"重复"的显式定义 | ⛔ 必改（高） | §4 Z3/§8.3 |
| A-5 | 明确 job 取消的落点：`REJECTED` + `error_detail='SOURCE_REVOKED'`，失败率统计剔除 | ⚠️ 应改（中） | §3.2/§7.1/§7.3 |
| A-6 | 不变式 5 守卫手段改为"行为 IT + 架构测试" | 建议 | §7.2 |
| A-7 | 存量重复清理的 DBA 留档纪律 | 建议 | §8.3 |
| A-8 | A2 "零新增表"限定措辞 | 建议 | §1 |

> 保持不变的（评审确认正确，勿动）：三层去重 + DB 兜底的分层、`rag_ingest_status` 枚举不改值、抽检前置作为迁移条件扩展而非枚举变更、Z5a/Z5b 拆分、`rag_kp_alias` 定调、PG `SKIP LOCKED` 选型、diff 归零作为移植完成判据。
