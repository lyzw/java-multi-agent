# RAG-D28 小初教程 RAG 数据预处理——落地设计、实施方案与计划

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D28 |
| 版本 | v1.4（增 D29 DEC-P9：**0.38 改为标定基线、结构变更后按 D08 §4.6 复标**；v1.3 增 rerank 首期必做，v1.2 技术论证落地版，v1.1 评审决策版，v1.0 源码核实首版） |
| 编制日期 | 2026-11-24 |
| 状态 | 待评审（本文是**实施计划**，不是「已完成」结论） |
| 上游依据 | `RAG-D27_小初教程RAG数据预处理完整方案_V1.0.md`（v1.2）及其个人笔记同源件 |
| **决策基线** | **`RAG-D29_预处理决策纪要与D27勘误_V1.0.md`——本文维度/选型/范围/rerank 以 D29 为准（DEC-P1~P8）** |
| 关联实现 | `rag-premise`、`IngestionPipeline`、`IngestJobOrchestrator`、`JdbcRetrievalRepository`、`deploy/sql/rag/`、`rag/docs/RAG-D08/D09/D18/D26` |
| 红线基线 | `08-知识检索RAG.md` §7.11、RAG-D09 G1/G2/G3、RAG-D08 G-1~G-9、`.workbuddy/memory/MEMORY.md` |
| 核实口径 | 2026-11-24 对工作树 `rag/`、`platform/`、`deploy/sql/rag/` 的只读源码/DDL/配置/测试逐项核对（证据见本文 §1 与附录 C） |

---

## 0. 结论先行与阅读指南

### 0.1 一句话结论

D27 的**主线方向正确**（版面解析 + 教学语义归一化 + 父子块 + 角色闸门 + 全链路留痕），但它的 §0「项目现有落点」表**高估了可复用资产的成熟度**：点名的类有的从未被生产调用，有的实现真实但未接入编排，有的只是文档规划。因此本计划的实施量不是「按方案补齐几个字段」，而是**「接通一条已存在但未启用的生产链 + 新建一批确实缺失的能力 + 从零搭建评测体系」**三件事并行。

> **决策更新（v1.1/v1.3/v1.4）**：2026-11-24 评审已决——Embedding 目标维度由 4096 改为 **1024**（Qwen3-8B + MRL 截断，首期即换维）、扫描/OCR 档延后、公式自然语言走受控规则；**rerank 确认引入并升级为首期必做**（D29 DEC-P8，RRF top-20 → `Qwen3-Reranker-0.6B` → top-3~5，门禁不被 rerank 覆盖）；**0.38 相关性门禁是前期实测标定基线，不是物理常量**（D29 DEC-P9）——换维/rerank 等结构变更后按 D08 §4.6 复标，新值写入快照后生效，禁止无依据下调放水。排期相应 15→**17 周**。全部决策与 D27 勘误记于 **RAG-D29**，本文按 D29 执行。

> 排期最大的变量不是解析算法，而是**评测集与人工标注**（RAG-D08 的 13/14 条需求处于「未实现」，黄金集数据文件为 0）。算法代码本计划给出精确到类/方法的改动点；数据与标注需单列资源，不可压缩为「顺手做」。

### 0.1.1 实施进展登记（2026-11-24 起，边做边更新）

> 状态口径：✅ 已完成并验证 / 🟡 进行中 / ⬜ 未开始。**「已完成」必须有测试或反证证据**，不靠代码存在推定。

| 批次 | 任务 | 状态 | 证据 / 说明 |
|---|---|---|---|
| 批 1 | **P0-7** 补 `HybridChunkerClientTest` / `ChunkPolicyTest` | ✅ | 新增 29 用例（11+18）全绿、skipped=0；`HybridChunkerClientTest` 用 JDK HttpServer 真桥驱动角色回填与 fail-closed；**撤点反证**：临时关闭答案区取严后 2 例转红，恢复+clean 重建后全绿 |
| 批 1 | **P0-3** 建 `rag-evaluation` 模块骨架 | ✅ | 新增模块 + `CaseLoader`/`EvalCase`/`CaseSet`/`CaseDiscipline` + 结构夹具；`CaseLoaderTest` 7 用例全绿；**反证**：AT 孤儿/rationale 占位/实现方标注/未知字段 4 类均转红。⚠️ 夹具是合成的，**不得用于对外宣称质量指标**（D08 §8.1） |
| 批 1 | 全量回归 | ✅ | rag reactor `mvn -o test`：97 测试类 / 621 用例 / failures=0 / errors=0 / skipped=0 |
| — | **P0-1/P0-2** 配置默认统一 + typed 配置 | ⬜ | **刻意不提前做**：yml 默认 `1024/vector` 是 IT 探针库（vector(1024)）的依赖；提前改 halfvec 会打断全部 IT。须与 P3-5 换维 + 库列迁移同步。`LocalRagPortsConfig` 已用 `@Value` + 装配期 fail-closed 实现「未知值抛错」，typed 配置类留到接线点一起做，避免空转 |
| — | **P0-4/P0-5** 真实语料基准集 / 黄金集 | ⬜ | 硬依赖数据 owner 授权 + 非实现方标注（D-A/D-G），AI 不能代签；当前只有合成结构夹具 |
| — | **P0-6** CI 门禁 + 反证 | 🟡 | D-F 孤儿检测与标注纪律断言已在批 1 落地；G-1~G-9 全量门禁待 P0-5 case 就位 |
| 批 2 | **P1-1** 页级路由 + **P1-2** 解析质量分 | ✅ | 新增 `RouteProfile`/`PageSignals`/`PageRoute`/`RoutingConfig`/`PageRouter`/`ParseQualityScorer`/`RoutingParseBackend`/`PageQuarantineException`；`ParseTrace` 补 `elapsedMs`/`routeReason`/`qualityScore`/`pageRoutes`（第 4 项是为「升级路径可观测」补的页级明细，供后续 `route_trace` jsonb 落库）。`PageRouterTest` 9 + `ParseQualityScorerTest` 5 + `RoutingParseBackendTest` 6 + `PageRoutingWiringTest` 5 全绿；**撤点反证**：把升级合并改成回退 primary → 2 例立即转红，恢复+clean 重建全绿。灰度开关 `rag.premise.page-routing.enabled=false` 默认关闭，启用时无 MinerU 端点/primary 已是 MinerU 均装配期 fail-closed |

| 批 3 | **P1-3** IR 三件套落地 | ✅ | ① `bbox` 真实赋值：两个 mapper 新增 `mapToPremiseBlocks()`，Docling `prov[0].bbox{l,t,r,b}` / MinerU `bbox`（0-1 原样）流入 `PremiseBlock`；`map()` 委托它恒等解包（既有测试零改）。**confidence 原始响应无字段 → 恒 NaN（⛔ 不伪造）**，`hasBbox()/hasConfidence()` 判未知。② `PremiseDocument` 补 `markdownRef`/`pageCacheRef`（4 参/3 参 compat 构造逐字段带过 doclingJsonRef）+ `withArtifacts`。③ 新增 `IntermediateRepresentation`/`PremiseIrRenderer`（Markdown+JSON 同源、BOILERPLATE 剔除、严格转义、bbox 缺失输出 null）+ `retrieveOriginal`（char 区间切片，越界抛错）；`PremiseArtifactSink` SPI + `ObjectStorePremiseArtifactSink`（复用 ObjectStorePort，键空间 `premise/` 隔离、路径穿越拒绝、fail-closed）+ `PremiseArtifactWriter`（渲染→落盘→回填一步）。`ParseWithRaw` 加 5 参 `premiseBlocks`（整册带几何、分段兼容构造诚实无几何）。GeometryAssignmentTest 8 + PremiseIrRendererTest 6 + PremiseArtifactWriterTest 3 + ObjectStorePremiseArtifactSinkTest 4 全绿。**两次撤点反证**：伪造空框 `[0,0,0,0]` → bbox 测试转红；空回取 → 20/20 转红；恢复+clean 重建全绿 |

| 批 4 | **P1-4** 归一化收敛（分隔符 / mapper 去重 / 唯一入口） | ✅ | ① 新建 `SectionPathStack`（`rag-premise/normalize`）——章节路径**唯一状态机**，持有 `SEPARATOR=" > "`、MAX_DEPTH=3、level 值栈替换/变浅弹出/跨页新分区/页眉剥除。② 两个 mapper 删除各自的 `replaceAtLevel` 副本（Docling 4 个跨块状态字段、MinerU 静态副本），统一委托共享栈；`LayoutPredicates.stripPageHeaderSuffix` 也委托它（正则单源）。③ `SectionPathResolver` 由自带「level-1 截断」算法改为共享栈门面，`HeadingHierarchyChunker`/`IngestionPipeline` 改持 `SectionPathStack`——消除「mapper 算对、切分器再算错覆盖」的隐藏漂移（铁律 #15）。④ `NormalizationRules.normalize(blocks, pageBaseAlreadyOneBased)` 成唯一入口，四规则按「页码→排序→区间→噪声」安全顺序串联。`SectionPathStackTest` 10 + `SectionPathParityTest` 4 + `NormalizationRulesTest` 新增 2 全绿。**两次撤点反证**：分隔符改回 `" / "` → 5 例转红；mapper 单独漂移 `" / "` → parity 2 例转红；恢复+clean 重建全绿 |
| 批 5a | **P1-5 前半** 表格内容提取（占位符 → 真实 Markdown） | ✅ | 排查发现 TABLE 块在两端 mapper **只读 `text` 字段**，而 Docling 内容在 `data.table_cells[]`、MinerU 在 `content[type=html]` 的 `<table>`——整册表格退化成 `[表格]` 占位符，内容 100% 丢失。① 新建 `TableGrid`（`rag-premise/normalize`，纯 Java）二维网格 + Markdown 渲染（管道/换行转义、表头分隔行、列不齐补齐、合并单元格只落左上角不重复）。② 新建 `TableContentExtractor`（`rag-infrastructure/parsing`）：Docling 按 **`docling_core` 官方 `TableCell` schema**（`start/end_*_offset_idx` 为 exclusive、`column_header` 标表头）重建；MinerU 轻量 HTML 表格解析（无 Jsoup 依赖，实体解码/嵌套标签剥离/colspan 展开），结构缺失返回 null（fail-soft 不伪造）。③ `BlockType.isTable()` 实例方法（规避 JDK 枚举 getstatic 缺陷）。④ 两个 mapper 的 TABLE 分支接上提取器，保留 bbox/page_idx 换算等既有契约。`TableGridTest` 5 + `TableContentExtractorTest` 6 + `TableMappingEndToEndTest` 3 全绿。**撤点反证**：关闭 Docling 提取 → `doclingTableBecomesMarkdown` 转红（内容回退 `[表格]`），恢复+clean 重建全绿。⚠️ 夹具按官方 schema 手写合成（非真实响应），TEDS/CDM 待 D08 G1 真样张复测；跨页合并/表头继承/切片重复表头属批 5b |
| 批 5b | **P1-5 后半** 跨页表合并 + 表头继承 + 超长切片 | ✅ | ① `PremiseBlock` 增 `TableGrid` 扩展字段（3 参 compat 构造保留）+ `withTable()`，`NormalizationRules` 三处重建块逐字段带过网格（铁律 #8）。② `TableGrid` 增 `continuesPrev`（MinerU 实测字段 `continues_prev`，2 参 compat 保留）；`TableContentExtractor` 从 block 读取。③ 新建 `TableMerger`：相邻表格块（中间仅许 BOILERPLATE）合并——强信号 `continues_prev` 或 Docling 兜底（页码严格相邻+同章节+同答案区+同列数+表头相同/缺）；续页表头剥除；超长表按行切片（每片重复表头）。④ `NormalizationRules.normalize(blocks, pageBase, mergeTables)` 新入口（默认 false 保既有契约），表格合并在四规则之后。`TableMergerTest` 10 + `CrossPageTableEndToEndTest` 2 全绿。**两次撤点反证**：不剥除续页表头 → 3 例转红；忽略 `continues_prev` 强信号 → `continuesPrevAloneIsSufficient` 转红（构造续表头不同、兜底会拒绝的用例），恢复+clean 重建全绿。⚠️ 尚未接入生产摄取链（同批 3 可达性缺口）；`maxRows=30` 是工程初值，须真实语料标定；TEDS 结构相似度与 `table_part`/`parent_table_id` 元数据未落地 |
| 批 6 | **P1-6** 公式 LaTeX + 自然语言（受控规则 + 人工抽检） | ✅ | 排查发现公式能力几乎为零（Docling 取 `orig` 纯文本、MinerU 不拼公式 content、无 formatter/双写/字段）。按 **D29 DEC-P3「先受控规则 + 人工抽检，不上小模型」** 实现：① 新建 `FormulaCatalogue`（受控词典，9 条核心公式，自然语言描述**人工审核写死**，精确键匹配——去 `$`/空白/下标花括号、上标压平，禁模糊命中套错词条）+ `FormulaEnricher`（编排，无状态单例，**不调任何 LLM**）+ `FormulaAnnotation`（latex/naturalLanguage/symbols/catalogueHit/needsReview/rawText）。② **fail-closed**：未命中 → 自然语言留 null + needsReview=true，`embeddingView()` 未命中只回 LaTeX、不追加任何描述（禁生成期自猜）；Docling 实测 `orig` 常是题干文字，自然走待抽检。③ `PremiseBlock` 增 `FormulaAnnotation` 扩展字段（3/4 参 compat 保留）+ `withFormula()`，`NormalizationRules` 三处重建逐字段带过（铁律 #8），`withTable` 切片也带过。④ 两个 mapper 的 FORMULA 块接 enricher（同一词典）；`PremiseIrRenderer` Markdown 命中时追加自然语言双写、JSON 输出 `formula` 字段（未命中 naturalLanguage=null、needsReview=true，非公式块 null 不伪造）。`FormulaEnricherTest` 8 + `FormulaMappingEndToEndTest` 5 + IR 新增 3 + 归一化新增 1 全绿。**两次撤点反证**：未命中改自猜 → 3 例转红；归一化重建丢注解 → 带过用例转红；恢复+clean 重建全绿。⚠️ 语义双写 `embeddingView()` 供后续 P3-1 `EmbeddingInputTemplate` 消费，本批未接 `ChunkIndexer`（避免跨工作项混改）；核心公式 CDM ≥90% 与自然语言人工抽检须在 D08 G1 真语料验证；不在受控词典的公式一律待抽检，不宣称覆盖率 |

> **实现决策（批 4）**：P1-4 前「标题→章节路径」有三份实现，已确认两处真实漂移：分隔符（mapper `" / "` vs resolver/规范 `" > "`）与非连续 level（mapper 值栈正确处理 1,2,3,5，resolver 的 level-1 截断会错位）。更隐蔽的是下游切分器用自己算法重新推进，把 mapper 的正确结果覆盖——「算对了但被改写」。本批把状态机收敛为一份；**页眉剥除正则也一并单源化**（`LayoutPredicates` 委托 `SectionPathStack.stripHeaderSuffix`）。`normalize()` 不含章节路径：路径是跨块累积状态机，不能做无状态批量变换；页码换算需调用方声明「是否已 1 基」，mapper 已适配阶段 +1，传错会整体错位一页。
>
> **实现决策（批 2）**：路由信号从 primary（轻量）**已解析块**推导（`PageSignals.of`），不二次解析 PDF 字节——「先用便宜路径」的零额外成本落点；升级后端只在存在 HEAVY 页时调用一次整册。混合后端时强制清空 `doclingJsonRef`，避免 `HybridChunkerClient` 拿过期 Docling JSON 切出错位块。阈值 `RoutingConfig.productionDefault()` 是**初始工程值**，须在真实教材基准集上标定（D08 SG-2），不得为让某册通过而临时放水。
>
> **实现决策（批 3）**：confidence **不做伪造赋值**——Docling/MinerU 实测响应都无块级置信度字段，`hasConfidence()` 恒 false 是诚实结论；D27 §4.2 示例里的 0.97 是示意值，非生产可得。样本响应 `sample-response.json` 的 `prov` 也被裁剪得只剩 `page_no`，故 bbox 赋值测试用带 bbox 的手工响应夹具。落盘能力（`PremiseArtifactSink`/`Writer`）已就位并装配为 Spring bean，但见下方**可达性缺口**。
>
> ⚠️ **运行期可达性缺口（批 3 发现，D09 G1 前必须解决）**：生产摄取链是 `IngestJobOrchestrator → IngestionPipeline.ingest()`，其 premise 分支走 `PremiseDocument.fromParsed(documentId, parsed)`——而 `parsed` 来自 `DocumentParserPort`（只有 `LayoutBlock`），**不经过 `PremisePreprocessor`/`RoutingParseBackend`，也不会带 mapper 的 bbox 几何**。即批 1~3 的路由、质量分、几何、IR 落盘能力目前均为「已实现+已装配为 bean，但生产摄取链未调用」。这是项目铁律 #15 警示的空转风险。解决路径：阶段 3 premise 管线接线时，把 `IngestJobOrchestrator` 的解析+切分段从 `IngestionPipeline` 切到 `PremisePreprocessor`（或让 `IngestionPipeline` 注入 `DocumentParseBackend`+`PremiseArtifactWriter`），属跨阶段门禁 G1 的系统性改造，不在单批内草率接通。**在接线+真实语料端到端通过前，不宣称 P1 阶段完成。**
>
> **下一步**：批 7 从 P2 阶段切入（题号规范化 `ExerciseNoNormalizer` / 题答配对 `QuestionAnswerPairer`，见 §3 WBS）；或按 G1 门禁优先关闭**生产摄取链可达性缺口**（见下）——这是 P1 阶段唯一系统性阻塞。P1-1~P1-6 能力均已实现并装配/委托，但生产链仍未调用；四项诚实缺口留待后续：① 生产摄取链可达性；② 超长表 `maxRows=30` / TEDS、公式核心 CDM ≥90% 与自然语言抽检，均须 D08 G1 真实语料验证；③ `table_part`/`parent_table_id`、`hasFormula` 等元数据落到 Chunk；④ `FormulaEnricher.embeddingView()` 待 P3-1 `EmbeddingInputTemplate` 消费（本批未接 `ChunkIndexer`，避免跨工作项混改）；不在受控词典的公式一律待抽检，不宣称覆盖率。P0-4/P0-5 并行等待数据授权与标注资源。⚠️ 生产摄取链可达性缺口（批 3 记录）仍未关闭——`IngestJobOrchestrator → IngestionPipeline.ingest()` 的 premise 分支仍走 `PremiseDocument.fromParsed()`，不经 `PremisePreprocessor`/`RoutingParseBackend`；G1 前须系统性接线，接线+真实语料端到端通过前不宣称 P1 完成。

### 0.2 三类读者怎么读

| 读者 | 重点章节 |
|---|---|
| 评审人 / 项目负责人 | §1（事实基线）、§2.6（红线映射）、§5（排期与里程碑）、§7（决策状态）、RAG-D29（决策纪要） |
| 后端研发 | §2.2~2.5（设计契约）、§3（分阶段 WBS，改动点精确到文件/类）、附录 A/B |
| 数据 / 标注 / 评测 | §1.3（能力真相）、§3.1（P0 评测先行）、§4（验收体系）、§5.3（角色分离） |

### 0.3 三条纪律（继承 D08 / D09，全文适用）

1. **诚实边界**：本文交付的是设计与计划，不产出「质量已达标」结论。任何指标值须由真实语料 + 真实模型实测后签署。
2. **反证纪律**：每条门禁必须回答「撤掉修复点会红吗？」——只有静态守卫变红而行为测试仍绿的门禁视为无效。
3. **标注独立**：标注者 ≠ 被评者 ≠ 实现方；AI 不得自行签署评测结论。

---

## 1. 事实基线：核实后的真实起点

> 本章是本文相对 D27 的**最大增量**。所有实施工作量与排期都建立在此章之上；若评审认为与源码现状不符，应先纠正本章再讨论排期。

### 1.1 核实方法

对 `rag/` 10 个 Maven 模块、`deploy/sql/rag/` 17 个 SQL、`rag-bootstrap/application.yml`、`rag.env*`，以及 `platform/agent-infra` 的草稿检索引擎，逐项核对：类是否存在 → 是否有真实实现 → 是否被生产路径调用 → 是否有对应测试/DDL/配置项。判定分四档：

| 档 | 含义 |
|---|---|
| 🟢 可用 | 生产实现且被真实调用/可被直接启用 |
| 🟡 半成品 | 代码存在但未接入生产、或口径不一致、或测试/留痕缺失 |
| 🟠 空转 | 方法存在但生产零调用 |
| 🔴 缺失/占位 | 无实现、仅抛异常、或只存在于文档规划 |

### 1.2 能力矩阵（声称 vs 实际）

| D27 环节 | D27 点名的现有落点 | 核实结论 | 实施含义 |
|---|---|---|---|
| PDF 解析（Docling） | `DoclingDocumentParserPort` | 🟢 可用：真实 HTTP / 分段 / 字符区间重编 / fail-closed；但 `PARSER_VERSION` 是占位串 | 接通即可，补真实版本号 |
| PDF 解析（MinerU） | `MineruPremiseBackend` | 🟡 半成品：Tasks API 实现真实（4.1.7 实测），但**未接入编排**，仅装配为 bean；`rag.premise.enabled=false` | 接通 + 灰度开册，非重写 |
| PDF 解析（Paddle/OCR） | （D27 列为待补） | 🔴 占位：`PaddleStructureParseBackend` 直接抛异常，`parse-backend` 甚至不接受 `paddle` | 需新建服务与适配 |
| 页级分类路由 / 质量分 | （D27 列为待补） | 🔴 缺失：只有整册级 `parse-backend` 开关，无分类器/路由字段/质量分 | 新建 |
| 归一化 | `NormalizationRules` | 🟠 空转：4 个方法生产**零调用**，真实逻辑散在两个 mapper 里 | 收敛而非新写；先统一调用点 |
| 归一化 | `SectionPathResolver` | 🟡 半成品：已调用，但分隔符 `>` 与 mapper 的 `/` 不一致 | 统一口径 |
| 归一化 | `LayoutPredicates` | 🟢 可用：题号/答案区/页眉/噪声判定双 mapper 共用，且有 parity 测试 | 直接复用 |
| 切分 | `HeadingHierarchyChunker` / `HybridChunkerClient` | 🟡 半成品：生产级代码，但默认走 legacy 循环（`rag.premise.enabled=false`）；Hybrid 缺关键测试、超长块只计数不拆、表头留痕失真 | 接通 + 补测 + 修 3 处缺陷 |
| 切分（父子块） | （D27 列为待补） | 🔴 缺失：`parent_id`/`chunk_level`/知识点语义单元代码与 DDL 零实现 | 新建 |
| 角色闸门 | `BlockRoleResolver` / `RetrievalPolicy.allowsRole` | 🟢 可用：结构化硬过滤，EXPLAIN/SOLVE/VERIFY 三档真实生效；`EXERCISE_HINT` 无生产者 | 直接复用，补提示角色来源 |
| 题号规范化 / 题答配对 | （D27 列为待补） | 🔴 缺失：题号正则只认 `1.`/`(1)`/`Exercise 3`；`exercise_ref` 仅是同块继承，非跨文档配对 | 新建 |
| 中间格式（Markdown/JSON/原页） | （D27 列为待补） | 🟡 批 3 已落地能力层：`bbox` 从 mapper 真实赋值、`PremiseIrRenderer` 同源渲染 Markdown+JSON（显式 readingOrder）、`PremiseArtifactSink`/`Writer` 落盘三件套；**confidence 原始无字段恒 NaN（不伪造）**。⚠️ 生产摄取链尚未调用（见 §0.1.1 可达性缺口） | 能力层完成，待 G1 接线 |
| 跨页表 / 公式 LaTeX+自然语言 / 核心图 caption | （D27 列为待补） | 🔴 缺失：公式取 `orig` 纯文本、图回退 `[图]`、无 LaTeX 与 VLM | 新建，公式自然语言需受控生成 + 抽检 |
| 编排与状态机 | `IngestJobOrchestrator` | 🟢 可用：PARSING→…→SUCCEEDED；但无 `QUARANTINED_REVIEW`/`REINDEX_PENDING` | 扩展状态 + 隔离区 |
| Embedding 端口 | `EmbeddingPorts` / `OpenAiCompatibleEmbeddingPort` / `LocalPortGate` | 🟢 可用：Qwen3-8B 通过通用端口配置（**当前 4096，目标 1024 见 D29 DEC-P2**）；BGE-M3 无实现；占位嵌入被闸门拦截 | 端口直接复用，维度按 D29 切换 |
| Embedding 输入模板 | （D27 列为待补） | 🔴 缺失：入库用**裸文本** `chunk.text()`，无模板、无版本 | 新建（P3 核心） |
| L2 归一化断言 | （D27 自身标「需实现」） | 🔴 缺失：生产端口无范数校验 | 新建 |
| 代际蓝绿 | `rag_index_generation` | 🟡 半成品：代际表 + ACTIVE/RETIRED + 事务切换真实可用；但**同列多代际行**，非 D27 §14.7 的「新增列双写」 | 同维度换代际可复用；跨维换列需新建路径 |
| 检索红线 | `JdbcRetrievalRepository` / `RetrievalOrchestrator` | 🟢 可用：RRF k=60、排序键/门禁键分离、门禁真实（**当前基线 0.38，可复标**，D29 DEC-P9）；「六轴」实为四轴谓词 + scope/role 分散 | 融合逻辑保持不变；阈值按复标流程更新，只加守卫 |
| 重灌纪律 | `JdbcCorpusRepository` | 🟢 可用：`ON CONFLICT DO UPDATE` + 清 `text_jieba` 已内置 | 直接复用 |
| 词法通道 | `ChineseTokenizer` / `text_jieba` | 🟡 半成品：unigram+bigram 可用，但无词典，公式符号/别名未专项进入 | 增强 |
| 配置守卫 | （D27 列为待补） | 🔴 缺失：无 premise/embedding typed 配置类，只有 @Value 散落；两个 yml 均无 `premise:` 段 | 新建配置类 + 守卫 |
| 评测体系 | `IngestInvariantEvidenceTest` / `Docling*Test` | 🟡 半成品：L1 有少量不变式测试与反证，但**整套评测基础设施未实现**（D08 FR-RAG-001~013 未实现），黄金集数据 0 | 从零搭建（P0） |
| 数据模型列 | `rag_chunk` 等 | 🟡 半成品：角色/section_path/exercise_no/kp_ids/in_answer_zone/content_hash 已有；**page_start/end、bbox、has_formula、parent_id、chunk_level 全无** | 增量 SQL |

### 1.3 十条必须纠正的认知偏差（避免按错误前提排期）

| # | D27 表述 | 源码真相 | 对计划的影响 |
|---|---|---|---|
| C1 | `04_migrate_embedding_halfvec_4096.sql` 已存在（§6.2/§14.6） | **文件不存在**，2026-11-01 已删，`deploy/sql/README.md:64` 仍列但脱节 | 跨维迁移脚本需重新编写 |
| C2 | `VectorColumn.indexable()` 恒 false（§13.6） | 按维度阈值判定：`halfvec(4096)` false，但 `halfvec(4000)`/`vector(2000)`/占位 1024 **true** | 换维评测需按阈值实测，不能引用「恒 false」 |
| C3 | 六轴准入谓词不动（§6.3/§11） | 检索 SQL 注释自称「四轴齐备」；scope 由 translator、role 由 policy 分散 | 守卫要覆盖分散的三层，不是一个谓词 |
| C4 | 配置默认 `4096/halfvec` | `application.yml` 默认 `1024/vector`，靠 `rag.env` 覆盖才一致；裸默认启动会被自检拦下 | P0 要修默认配置一致性，否则「假失败」 |
| C5 | `01_rag_ddl.sql` 是「终态快照」 | 只含 13 表，缺 08/09/10/11/13 的增量对象（reviewer_roster、index_generation、版面三列、build_cursor、chunk_policy） | 只按 01→04 建库会缺列缺表；须走 01…13 全序列 |
| C6 | `NormalizationRules` 是可用落点 | 生产零调用，逻辑散在两个 mapper；`renumberBrokenCharSpans`/`markRepeatedLineNoise`/`ensureReadingOrder` 空转 | 「收敛调用点」是一个独立任务，不能假设已接 |
| C7 | `MineruPremiseBackend` 是现有解析能力 | 实现真实但未串入 `IngestJobOrchestrator`；premise 侧同名 `MineruParseBackend` 是纯占位 | 接通路径要明确，避免误用占位类 |
| C8 | premise 切分是生产链路 | `rag.premise.enabled=false` 默认关闭，生产走 legacy 循环；`PremisePreprocessor` 注释仍写「不进生产」 | 「启用」本身是带风险的灰度动作，需门禁前置 |
| C9 | 解析基准集/黄金集/反证门禁是「需补齐项」 | D08 的 13/14 条 FR 未实现，golden/Q&A 数据文件为 0，`rag-evaluation` 模块不存在 | P0 工作量按「从零」估算，不能按「补全」 |
| C10 | `0.38` 门禁不可调 | 0.38 是 D08 §4.6.1（2026-10-28）**实测标定基线**、样本不足的指示性值，不是物理常量；生效源 `RAG_RETRIEVAL_MIN_SCORE` | 结构变更后按 D08 §4.6 复标（D29 DEC-P9）；禁止的是无依据下调，不是复标本身；加「无依据下调转红」守卫 |

### 1.4 实施含义：三件事并行，而非一件

```text
(A) 接通：已有但未启用的生产链
    Docling/Mineru 后端 → premise 切分（灰度开册）→ 代际蓝绿 → 检索真源
    主要动作：配置化启用、默认维度修正、补齐 Hybrid 三处缺陷、版本号留痕

(B) 新建：确实缺失的能力
    页级路由 + 质量分、父子块、题号规范化与题答配对、公式/图/跨页表语义增强、
    embedding 输入模板 + L2 断言、隔离区状态机、typed 配置与守卫、增量 SQL

(C) 从零：评测体系（最大变量）
    rag-evaluation 模块 / 黄金集 ≥800 条 / 5 类反例集 / CI 门禁 + 反证 /
    人工评审流程 + 标注者分离 / 100 万片段规模实测
```

> **排期建议**：(C) 与 (A) 可并行启动（评测集构建不依赖解析改造）；(B) 依赖 (A) 的接通结果做对照实验。三者都必须在 P4 灰度前收敛到门禁通过。

---

## 2. 总体设计

### 2.1 目标态架构

```text
原始 PDF/答案册
   │
   ▼
[0] 准入与分类       六轴事实齐全（来源/版本/授权/角色/完整性）+ 页级分类路由（NEW）
   │                   路由产物：PageRoute(profile, reason) 写入 ParseTrace
   ▼
[1] 分级解析         Docling(默认) / MinerU(复杂) / Paddle(扫描,NEW) / 商业补位(审批)
   │                   统一中间产物 IR：PremiseDocument(blocks+bbox+confidence+order+char)+Markdown(NEW)+原页缓存(NEW)
   ▼
[2] 归一化           复用 LayoutPredicates/SectionPathResolver；收敛 NormalizationRules 调用(NEW)
   │                   页码基准 / 页眉页脚 / 阅读顺序 / char 连续 / 章节路径(统一分隔符) / 跨页表(NEW) / 公式 LaTeX+别名(NEW) / 图 caption(NEW)
   ▼
[3] 教学语义增强     知识点挂接(kp_ids 已有) / 题号规范化 Q003.S02(NEW) / 题答配对(NEW) /
   │                   难度·认知层次(facets) / 角色判定(BlockRoleResolver 已有) / HINT 来源(NEW)
   ▼
[4] 父子块切分       Heading(默认) / Hybrid(对照,修3缺陷)；父块=知识点(NEW parent_id/chunk_level)
   │                   子块=定义/公式/例题/习题；答案原子化；角色最严传播
   ▼
[5] Embedding/索引   输入模板 v1(NEW) + L2 断言(NEW) + 代际蓝绿(已有)；1024 换维(D29)：双写列 embedding_v2 halfvec(1024) + MRL 截断后重归一化
   │                   词法增强：题号/公式符号/别名进入 text_jieba
   ▼
[6] 排序(rerank)     RRF(k=60) top-20 → Qwen3-Reranker-0.6B(NEW, DEC-P8) → top-3~5；门禁仍在融合阶段判定(当前基线 0.38，DEC-P9 可复标)，rerank 分只排序不越权
   ▼
[7] 门禁与发布       解析门禁 / L1~L4 评测(D08) / 反证 / 角色×任务 / 灰度 / 回滚 / 软失效
```

**接线原则**：`rag-premise` 保持纯 Java SPI（不依赖 Spring），`IngestionPipeline` 是唯一编排入口；`IngestJobOrchestrator` 只负责阶段推进与留痕；`JdbcRetrievalRepository` 仍是唯一生产检索真源；不在 `platform/agent-infra` 草稿引擎上施力。

### 2.2 组件与接口契约

| 组件 | 状态 | 设计动作 |
|---|---|---|
| `DocumentParseBackend` SPI | 🟢 | 保留。统一 `ParseTrace`（补 `elapsedMs` / `routeReason` / `qualityScore` 三个字段） |
| `PageRouter`（NEW） | 🔴 | 输入 `documentId, byte[]` + 轻量文本层统计，输出逐页 `PageRoute(profile, reason, confidence)`；不确定的页按最严格 profile 处理；纯 Java、可单测 |
| `ParseQualityScorer`（NEW） | 🔴 | 页级产出 `qualityScore`（文本层覆盖率、公式/表格密度、图像占比）；低于阈值触发升级或隔离 |
| `PremiseDocument` 中间模型 | 🟡 | 补齐 `markdownRef` / 原页缓存引用 / `readingOrder` 显式字段；`bbox`/`confidence` 必须从 mapper 真实赋值（当前恒 null/NaN） |
| `NormalizationRules` | 🟠 | 收敛为**唯一**归一化入口，删除 mapper 中的重复实现；统一章节路径分隔符为 `>` |
| `HeadingHierarchyChunker` | 🟡 | 增加父子块输出（`parentId`/`chunkLevel`）；修 token 口径（对齐 embedding tokenizer） |
| `HybridChunkerClient` | 🟡 | 三处必修：① `matchedPrefix` 伪采集改为真实回填；② 超长块实际拆分（当前只计数）；③ Python 桥传 `max_tokens` 使留痕与切分一致 |
| `BlockRoleResolver` | 🟢 | 保留；补 `EXERCISE_HINT` 的稳定判定规则（无来源时不编造，见 D27 §5.3） |
| `ExerciseNoNormalizer`（NEW） | 🔴 | 规范化键 `Q003` / `Q003.S02` / `GRP:Q005-Q007`；作用域锚点 `edition_id + section_path`（或 `paper_id`） |
| `QuestionAnswerPairer`（NEW） | 🔴 | 同册优先于跨册；跨册按 edition 五轴 + 章节映射 + 规范题号配对；配对失败记 `UNMATCHED`，不猜题强绑 |
| `EmbeddingInputTemplate`（NEW） | 🔴 | 统一模板（教材/章节/位置/类型角色/知识点 + 正文 + 公式自然语言 + 图注），版本写入 `chunk_policy_config`；模板无 PII |
| `VectorNormAssertion`（NEW） | 🔴 | 写入前断言 L2 范数≈1；**MRL 截断到 1024 后强制重新归一化**（D29 DEC-P2 S1）；采样监控分数分布 |
| **维度换维（NEW，D29 DEC-P2）** | 🔴 | Qwen3-Embedding-8B **MRL 截断至 1024 维**，新增 `embedding_v2 halfvec(1024)` 双写列，新代际蓝绿；禁止零填充伪造维度与原地 `ALTER COLUMN TYPE` |
| **`RerankPort`（NEW，D29 DEC-P8）** | 🔴 | 首期必做；消费 `JdbcRetrievalRepository` RRF top-20，默认 `Qwen3-Reranker-0.6B`（备选 `bge-reranker-v2-m3`），重排取 top-3~5；**只改排序不改门禁**，超时/失败显式 `degraded` + 告警，不静默回退 |
| 隔离区状态机 | 🔴 | 扩展 `IngestJobStatus`：`QUARANTINED_REVIEW`、`REINDEX_PENDING`；重入必须生成新 job、按 `chunk_id` 覆盖并清向量/词法 |

### 2.3 数据模型与增量 SQL 设计

> **铁律**：新增列/表一律走**增量 SQL**，禁止直接改 `01` 终态 SQL 而不迁移存量库；新表同步 `03_rag_roles.sql` 授权与 RLS 基线；`ON CONFLICT DO UPDATE` 需 UPDATE 授权。

建议新建 `deploy/sql/rag/18_v1_0_5_preprocessing_enhance_alter.sql`（编号接续现状最大 17），内容：

| 对象 | 类型 | 说明 |
|---|---|---|
| `rag_chunk.page_start` / `page_end` | 新增列（int） | 跨页块的页区间；与既有 `page_number` 共存 |
| `rag_chunk.parent_chunk_id` / `chunk_level` | 新增列（uuid / int） | 父子块；`chunk_level` 0=父 1=子 |
| `rag_chunk.has_formula` / `bbox` / `reading_order` | 新增列（bool / jsonb / int） | 公式原子化标记、回取几何、显式阅读序 |
| `rag_chunk_embedding.content_hash` / `real_output_dimension` | 新增列（text / int） | 向量-文本指纹一致性；换维留痕 `real_output_dimension=1024`（D29 DEC-P2） |
| **`rag_chunk_embedding.embedding_v2 halfvec(1024)`** | **新增列（D29 DEC-P2）** | **换维双写列，与旧 `embedding halfvec(4096)` 并存；按代际隔离读路径；禁止原地改列类型** |
| `rag_document.markdown_ref` / `page_cache_ref` | 新增列（text） | 中间产物指针（落对象存储或本地目录，不放绝对路径） |
| `rag_ingest_job.route_trace` / `quality_trace` | 新增列（jsonb） | 页级路由与质量分留痕 |
| `IngestJobStatus` 枚举 + DB CHECK | 枚举/DDL | 增 `QUARANTINED_REVIEW`、`REINDEX_PENDING`（同步 PG enum 与 Java 枚举） |
| `03_rag_roles.sql` GRANT | 授权 | 新列走 `app_agent`（业务读写）；删除仍只走 `agent_retention`；不新增物理删除端点 |
| RLS 基线 | 守卫 | 若新增含租户数据的表须挂 RLS；仅加列不增表则更新活库期望值至 15/43 口径并登记 |

> 注：`content_hash` 已在 `rag_chunk`，本计划不在该表重复加；仅给 `rag_chunk_embedding` 加，用于向量重算判定。

### 2.4 配置模型设计

新增 typed 配置类（终结 @Value 散落与 yml 缺段）：

- `RagPremiseProperties`（`@ConfigurationProperties(prefix="rag.premise")`）：`enabled` / `parseBackend` / `routeProfile` / `profiles{...}` / `normalize{...}` / `formatter{...}` / `quarantine{...}`
- `RagEmbeddingInputProperties`（prefix=`rag.embedding.input`）：`templateVersion` / `normalize` / `realOutputDimension`
- `RagRerankProperties`（prefix=`rag.rerank`，NEW，DEC-P8）：`enabled` / `model` / `endpoint` / `topN=20` / `returnK=3~5` / `timeoutMs` / `failMode=degraded`；**无 `minScore` 字段**（门禁只在融合阶段判定，配置层杜绝 rerank 越权）
- 阈值生效与下发（D29 DEC-P9 + D20）：`RAG_RETRIEVAL_MIN_SCORE` 是唯一生效源，当前基线 0.38；**前端不得硬编码 `RAG_SCORE_THRESHOLD=0.38`**，生效值随检索响应返回，避免「配置说新值、UI 显示旧值」的漂移
- 修正 `application.yml`（rag-bootstrap 与 rag-worker 两处）：补 `premise:` 段；按 D29 DEC-P2 统一为 `dimension: 1024` + `vector-type: halfvec`（默认 `vector` 须改），并令 `rag.env` 的 `RAG_EMBEDDING_DIMENSION` 由 4096 改为 1024（消除 C4 假失败）。
- `rag.env.example` 补齐 `RAG_PREMISE_*` / `RAG_EMBEDDING_*` 新键。
- 配置守卫：未知 `routeProfile` / `parseBackend` / `templateVersion` 装配期抛错（沿用 `ChunkPolicy.fromConfigValue` 模式）。

### 2.5 状态机扩展

```text
[PARSING/CHUNKING]
  ├─ 正常 ──▶ [EMBEDDING] ──▶ [INDEXING] ──▶ [SUCCEEDED (retrievable=true)]
  ├─ 整册不可恢复/不完整 ──▶ [FAILED]（整册不入库，保留 ParseTrace）
  └─ 局部低置信/待复核 ──▶ [QUARANTINED_REVIEW]（对应页/块 retrievable=false）
                              │ 人工修正提交 → 新 ingest_job / 复核批次号
                              ▼
                        [REINDEX_PENDING] ──▶ 重走归一化/切分/嵌入/索引 ──▶ [SUCCEEDED]
```
重入纪律：按 `chunk_id`/`content_hash` 覆盖片段、`text_jieba`、`embedding`、`retrievable`，不得绕过重算直接改库（D27 补充 18）。

### 2.6 红线映射（方案 §11 → 设计约束 → 守卫）

| 红线 | 设计约束 | 守卫落点 |
|---|---|---|
| **门禁阈值按标定流程变更（DEC-P9）** | `RAG_RETRIEVAL_MIN_SCORE` 唯一生效源，当前基线 0.38；⛔ 禁止**无依据**下调放水；结构变更后按 D08 §4.6 T-1~T-6 复标，新值写 §7.2 快照后生效 | 配置守卫（无依据下调转红）+ 复标留痕断言 + 换维专项反证（C10） |
| RRF k=60 / 排序键与门禁键分离 | 不改 `JdbcRetrievalRepository` 融合逻辑 | 现有行为测试 + 回归基线 |
| 六轴准入（实为四轴+scope+role） | 只让事实字段齐全，不改谓词 | 三层守卫（SQL/scope/role） |
| 不在 agent-infra 草稿施力 | 生产真源仍是 rag 模块 | 架构守卫（无生产调用方） |
| 换维按 D29 DEC-P2 / 不建 HNSW | 目标 `halfvec(1024)`（Qwen3-8B MRL 截断）；**新代际 + 双写列**，禁止原地 ALTER；1024 虽使 `indexable()` 变 true，仍不建 HNSW（刻意取舍） | 启动自检 + typmod + 双写代际守卫 |
| MRL 截断后重新归一化 | 截断至 1024 后写入前断言 L2≈1 | `VectorNormAssertion` + 反证（D29 S1） |
| 换维必须复标门禁（DEC-P9） | 换维在**当前基线 0.38 对照 + 复标新值**下黄金集重评；新值按流程产出，不达标 ABORT | 换维专项反证（D29 DEC-P2）+ 复标快照留痕 |
| **rerank 不覆盖门禁（DEC-P8/P9）** | rerank 分**只用于排序/截取**；被融合阶段相关性分 < 当前生效阈值 拒绝的候选不得进入结果；配置层无 `minScore` | 构造「rerank 高分但融合分 < 生效阈值」候选必拒（RC-09，生效值由响应带回不硬编码） |
| **rerank fail-closed（DEC-P8）** | 超时/不可用不得静默返回未重排结果；显式 `degraded` + 告警 + P95 实测 | 服务故障注入用例 + 响应可见性断言 |
| 不用占位嵌入伪装生产 | `LocalPortGate` fail-closed | 启动自检 + 端口测试 |
| 不物理删除 | 软失效 + 代际 RETIRED | 无删除端点守卫 + RLS 授权 |
| 不把 PII 拼进 Prompt | 解析前脱敏；学号扫描不豁免 | embedding 模板 + 审计摘要扫描 |
| 不静默降级后端 | 解析/切分/嵌入失败即 job FAILED | fail-closed 反证（已有 + 补测） |
| 不用 API 级 PASS 替代业务验收 | 每阶段角色×任务真实检索用例 | 新建角色×任务套件（§4.3） |

---

## 3. 实施方案（分阶段 WBS）

> 阶段编号沿用 D27 的 P0~P5，并**对齐** D09 的 G1/G2/G3 阶段门禁与 D08 的 SG-1~SG-6 评测门禁。三套 G 编号含义不同，引用时带前缀（见 D08 §6.3 注）。

### 3.0 阶段总览

| 阶段 | 目标 | 关键交付 | 退出门禁 | 可与谁并行 |
|---|---|---|---|---|
| **P0 基线与评测先行** | 建立可测的起点 | 解析基准集、黄金集骨架、CI 门禁 + 反证、typed 配置、默认维度修正 | D08 SG-1/SG-2；D09 G1 前置 | 与 P1 解析改造并行 |
| **P1 分级解析与中间格式** | 页级路由 + IR 落地 | PageRouter/QualityScorer、IR 三件套、归一化收敛、跨页表/公式/图 | D09 **G1**（解析与切分） | 与 P0 并行启动 |
| **P2 教学语义与父子块** | 知识点语义单元 + 题答 | parent/chunk_level、题号规范化、题答配对、Hybrid 三修 | D09 G1 补充；G-5 切分完整 | 依赖 P1 IR |
| **P3 Embedding 与代际** | 模板 + 维度治理 | 输入模板 v1、L2 断言、同维换代际演练、词法增强 | D09 **G2**；代际蓝绿演练 | 依赖 P2 切分稳定 |
| **P4 灰度与上线** | 单租户单学科灰度 | 灰度开关、低分回流、回滚演练 | D09 **G3**；D08 SG-3/SG-5 | 依赖 P0~P3 |
| **P5 持续治理** | 版本软失效与规模治理 | 改版软失效、评测集回流、规模实测 | D08 SG-6（人工签署） | 长期 |

### 3.1 P0：基线与评测先行（关键路径起点）

**目标**：先把「怎么算做对了」固化成可执行的门禁，再动算法。

| 编号 | 任务 | 改动点（文件/类） | 验收 | 依赖 |
|---|---|---|---|---|
| P0-1 | 修正配置默认一致性 | `application.yml`×2、`rag.env.example`、`RagStartupSelfCheck` | 裸默认启动能被自检明确拒绝（非静默 1024/vector） | — |
| P0-2 | 建 typed 配置 + 守卫 | NEW `RagPremiseProperties`、`RagEmbeddingInputProperties`；配置守卫测试 | 未知 profile/backend/template 装配期抛错 | P0-1 |
| P0-3 | 建 `rag-evaluation` 模块骨架 | `rag/pom.xml` 增模块；case 加载器 | `rag/eval/cases/*.json` 可加载并断言；D-F 孤儿检测生效 | — |
| P0-4 | 解析基准集（3~5 册，50~100 页） | `rag/eval/cases/l1-ingest-*.json`；真实已授权 PDF | NED/TEDS/CDM/阅读顺序/页码对齐有实测记录 | 数据 owner 授权 |
| P0-5 | 黄金集骨架（正≥200/负≥100/拒≥50）+ **TRAP-NEARMISS 第 8 类** | `l2-retrieval-*.json`、`traps-*.json`、`traps-nearmiss.json`（相反数/倒数、一次函数/正比例函数、光合作用/呼吸作用等，每对 ≥10 例；英语册单独子集） | D-A~D-G 标注纪律 CI 断言全过；`rationale`/`acceptance` 非空；近义对 top-1 单独统计（D29 DEC-P7） | 标注者（非实现方） |
| P0-6 | CI 门禁 G-1~G-9 + 反证 | D08 §6.2；每门禁 ≥1 条「撤点转红」用例 | 反证执行记录；反证未执行不得列「已覆盖」 | P0-3/P0-5 |
| P0-7 | 补 `HybridChunkerClientTest` / `ChunkPolicyTest` | NEW（源码注释引用但文件不存在） | 角色回填 + 桥失败 fail-closed 有单测 | — |
| P0-8 | L1 不变式测试补全 | `IngestInvariantEvidenceTest` 扩展至 premise 切分器 | 噪声/题号/章节/答案区逐条有反证 | P0-7 |

**P0 退出条件**：D08 **SG-1**（case 加载器 + 完整性断言）、**SG-2**（G-1~G-5/G-9 反证执行）通过；D09 G1 的前置证据（解析基准 + 不变式）就位。

### 3.2 P1：PDF 分级解析与中间格式

| 编号 | 任务 | 改动点 | 验收 | 依赖 |
|---|---|---|---|---|
| P1-1 | 页级路由 | NEW `PageRouter`；`LocalRagPortsConfig` 接线 | 干净页走轻量、复杂页升级；升级不可逆；路由原因写 ParseTrace | P0-2 |
| P1-2 | 解析质量分 | NEW `ParseQualityScorer`；`ParseTrace` 补字段 | 低置信页隔离/升级路径可观测 | P1-1 |
| P1-3 | IR 三件套落地 | `PremiseDocument` 补字段；Markdown 落盘；原页缓存；`bbox`/`confidence` 真实赋值 | 示例 JSON 全字段有值（当前 bbox=null/conf=NaN）；可回取原文 20/20 | P1-1 |
| P1-4 | 归一化收敛 | `NormalizationRules` 成为唯一入口；mapper 去重；统一分隔符 `>` | `NormalizationRulesTest` + parity 测试；`SectionPathResolver` 与 mapper 同口径 | P1-3 |
| P1-5 | 跨页表合并 | mapper + chunker | 表体合并、表头继承、切片重复表头；TEDS 有基准 | P1-3 |
| P1-6 | 公式 LaTeX + 自然语言 | NEW formatter；**受控规则 + 人工抽检（D29 DEC-P3）**，不上小模型生成 | 核心公式 CDM ≥90%；自然语言描述人工抽检；符号进 `text_jieba` | P1-3 |
| P1-7 | 核心图 caption | VLM 描述 + 图注/bbox/段落绑定；装饰图丢弃 | 每册抽 10 图，描述准确率 ≥90% | P1-3 |
| ~~P1-8~~ | ~~Paddle/OCR 档~~ | **延后（D29 DEC-P1 已决策，不纳入首期）**；扫描件低文本层覆盖进隔离区，不静默当文本 PDF 处理 | 纳入条件：OCR 服务可测 + 置信 ≥0.80 实测 + 隔离门禁通过 | — |

**P1 退出条件**：D09 **G1** 通过——① Docling/MinerU 生产实现对 ≥1 册真实 PDF 产出结构块；② 切分不变式逐条反证；③ 原文回取 20/20；④ 答案区识别 ≥95% 且漏判 0；⑤ 未校验语料不入库（D07 N-1）。

### 3.3 P2：教学语义与父子块

| 编号 | 任务 | 改动点 | 验收 | 依赖 |
|---|---|---|---|---|
| P2-1 | 父子块模型 | NEW `parent_chunk_id`/`chunk_level`；`HeadingHierarchyChunker` 输出父子；检索命中子块返回父块 | 父子覆盖完整；父块送 LLM、子块算 embedding | P1-4 |
| P2-2 | 题号规范化 | NEW `ExerciseNoNormalizer`（Q003.S02 / GRP / UNMATCHED） | 单级/多级/题组/答案多问四类用例 | P1-4 |
| P2-3 | 跨文档题答配对 | NEW `QuestionAnswerPairer`；`exercise_ref` 真实回指 | 同册优先；跨册按 edition+章节+题号；失败记 UNMATCHED 不强绑 | P2-2 |
| P2-4 | 附录逻辑单位 | 词表按 Unit/Starter 切、续页继承、中英双形标题 | 「第五单元单词」查询可见（撤点反证转红） | P1-4 |
| P2-5 | HINT 来源 | 明确无稳定来源时不编造；有来源时判 `EXERCISE_HINT` | 不编造提示；`forbidRoles` 守卫不误伤 | P1-4 |
| P2-6 | Hybrid 三修 | `HybridChunkerClient`：`matchedPrefix` 真回填、超长块实拆、Python 桥传 `max_tokens` | 留痕与切分一致；角色回填单测通过 | P0-7 |

**P2 退出条件**：G-5 切分完整（IDX-01/02/06=100%）、IDX-05a 答案区漏判 0；父子块与题答配对反证通过。

### 3.4 P3：Embedding 与代际

| 编号 | 任务 | 改动点 | 验收 | 依赖 |
|---|---|---|---|---|
| P3-1 | 输入模板 v1 | NEW `EmbeddingInputTemplate`；`IngestJobOrchestrator`/`Global…`/`ChunkIndexer` 改调用；**query 模板拼 instruction、doc 不加（D29 DEC-P7 M5）**；chunk 200～400 字、标题链前缀拼接 | 裸文本入口全部替换为模板；模板无 PII；query/doc 模板区分有断言；版本写 `chunk_policy_config` | P2 |
| P3-2 | L2 归一化断言 | NEW `VectorNormAssertion`；写入前校验 + 采样监控 | 未归一化写入被拒；**MRL 截断至 1024 后重归一化有反证（D29 S1）** | P3-1 |
| P3-3 | 词法增强 | `ChineseTokenizer` 增公式符号/别名/版本术语；**建学科术语词典（每学科 100～300 核心词，含公式符号/LaTeX 片段）进入 `text_jieba`（D29 DEC-P7）**；BM25 硬命中通道与 dense 走既有 RRF k=60（不新增检索架构） | 长尾符号与学科术语词法通道可命中；词典按学科建、英语册单独；无词典限制在评测集上量化 | P2 |
| P3-4 | 代际蓝绿演练 | 复用 `rag_index_generation`；蓝绿切换 + 回滚演练 | 分数非常量；当前生效阈值生效（值由响应带回）；旧代际秒级回切 | P3-1 |
| **P3-5** | **1024 换维（首期必做，D29 DEC-P2）+ 门禁复标（DEC-P9）** | NEW `embedding_v2 halfvec(1024)` 双写列 + 回填脚本（替代已删的 04 脚本）；配置/yml/env 改 1024/halfvec；新代际登记 `dimension=1024`/`real_output_dimension=1024`；**三档 MRL 验证（1024/2048/4096 截断，不重复 embedding）**；按 D08 §4.6 重测阈值（每层 ≥100、口径评审、留痕、写 §7.2 快照） | 按 D27 §14.7 七步全过：新代际→双写→全量重算→**当前基线 0.38 对照 + 复标新值下黄金集重评（不达标 ABORT）**→蓝绿切流→观察 7 天→旧代际 RETIRED；三档判据：`recall@20` 掉 ≤3 点通过（掉 >3 点 **ABORT 换维，rerank 救不了召回、复标不能掩盖**）、`top-1` 掉 >8 点启用 DEC-P8 已就位的 rerank（仍不达标才上 2048 对照档）；禁原地 ALTER、禁零填充伪造、禁无依据下调 | P3-4 + D29 DEC-P2/P7/P8/P9 前置核销 |

**P3 退出条件**：D09 **G2**——rag-infrastructure 生产实现、api/worker/bootstrap 可部署、启动自检通过、非特权角色 RLS 实测、合成夹具端到端基线快照；**P3-5 换维在当前基线 0.38 对照 + 复标新值下重评通过、复标快照与 §9 留痕齐全**（D29 DEC-P2/P9，否则 ABORT 回 §14.5 决策树）。

### 3.5 P4：灰度与上线

| 编号 | 任务 | 改动点 | 验收 | 依赖 |
|---|---|---|---|---|
| P4-1 | 灰度开关与开册 | `RAG_PREMISE_ENABLED=true` + `chunk-policy=heading` + `parse-backend=docling`；先跑 18 号 SQL | 单租户单学科；英语词表 + 数学公式两类高风险样本先行 | P0~P3 |
| P4-2 | 低分回流 | NEW `scripts/rag-eval-harvest.sh` + 回流流程 | 在线低分 trace 每周复评，kappa ≥0.6 | P0-3 |
| P4-3 | 角色×任务验收套件 | NEW：STUDENT/TEACHER/运维 × 真实任务链路 | 每阶段有真实检索用例，非 API 级 PASS | P0-6 |
| P4-4 | 回滚与隔离演练 | 隔离区状态机 + 代际回切 | QUARANTINED→REINDEX→SUCCEEDED 全流程可走 | P3-4 |
| P4-5 | 规模实测准备 | 100 万片段造数脚本（合成标注） | D08 SG-5 数据就位（合成部分显式标注） | P4-2 |
| **P4-6** | **rerank 首期必做（D29 DEC-P8，由条件触发升级）** | NEW `RerankPort` + `RagRerankProperties`；`JdbcRetrievalRepository` RRF top-20 → `Qwen3-Reranker-0.6B`（备选 `bge-reranker-v2-m3`）→ top-3~5；**门禁仍在融合阶段判定（当前基线 0.38 / 复标新值），rerank 分只排序不越权**；超时/失败显式 `degraded` + 告警；**项目当前无 rerank 实现，属新增能力** | ① A/B：`1024+无rerank` / `1024+rerank` 同黄金集对比 `recall@20`/`top-1`/`nDCG@5`/P95；② TRAP-NEARMISS 近义对 top-1 转绿、**撤掉 rerank 转红**；③ 构造「rerank 高分但融合分 < 生效阈值」必拒；④ 服务故障注入不静默通过；⑤ **不为救效果无依据下调阈值**（DEC-P9） | P3-5 换维重评 + 复标 + P0-5 黄金集（服务与端点可用） |

**P4 退出条件**：D09 **G3** 的 rag 侧条件（C1/C2/C3 + 端到端回溯 + SG-1~3/SG-5）；**P4-6 rerank A/B 与三类反证通过**（DEC-P8）；主平台接入仍需 A-008 结论与 SG-4/SG-6 人工签署，不在本计划内自动放行。

### 3.6 P5：持续治理

改版教材/勘误/答案册走版本软失效（`superseded_by`/`retrievable=false`/代际 RETIRED）；评测集从审计摘要与低分样本持续回流；规模化后再评估 MRL/量化，不改 DDL、不建 HNSW。

---

## 4. 验收体系

### 4.1 四层评测与门禁映射

复用 D08 既有代号，不另起炉灶：

| 层 | 指标（D08） | 本方案落点 | 门禁 |
|---|---|---|---|
| L1 解析/切分 | IDX-01~07（切分完整/题号/公式/知识点挂准/答案区漏判·过判/噪声/漏挂） | P1/P2 解析门禁 | G-5、G-2（IDX-05a） |
| L2 召回 | RC-01~09（Recall/MRR/nDCG/版本/权威/角色/成组/误拒·漏拒/门禁一致）+ **TRAP-NEARMISS 近义对子集（top-1 单独统计，D29 DEC-P7）** + **rerank A/B（recall@20/top-1/nDCG@5/P95，D29 DEC-P8）** | P0-5/P2/P3/P4 | G-1/G-2/G-4/G-9 + 近义对 top-1 判据（D29 §7.4）+ rerank 撤点/门禁/故障反证（D29 §8.2） |
| L3 接地 | GD-01~09（引用存在/蕴含/装饰/冲突/四要素/版本混用/编造位置/裁决/拒答理由） | P4（依赖真实模型） | G-3/G-4/G-7/G-8 |
| L4 端到端 | E2E-01~06（有用性/依据/拒答/引文核对/溯源/时延） | P4/P5 人工评审 | SG-4/SG-6（M 类） |

### 4.2 反证清单（「撤掉修复点会红吗」）

| 修复点 | 反证构造 | 期望转红 |
|---|---|---|
| 答案区取严 | 把答案区改判 `EXPLANATION` | TRAP-LEAK 集（G-2） |
| 附录按 Unit 切 | 改为按印刷页切 | 「第五单元单词」用例 |
| char 区间连续 | 清空 `charStart/charEnd` | 原文回取用例 |
| Hybrid fail-closed | 桥失败静默回退 heading | 切分留痕一致性用例 |
| 重灌清词法/向量 | 不清 `text_jieba` 重灌 | 陈旧向量用例 |
| 输入模板 | 退回裸文本 embedding | 模板版本一致性用例 |
| L2 归一化 | 去掉范数断言 | 写入断言 / 分数漂移用例 |
| 门禁阈值无依据下调 | 无复标记录直接下调 `RAG_RETRIEVAL_MIN_SCORE`（含前端硬编码旧值） | 分数门禁一致性用例（RC-09）+ 复标留痕断言（DEC-P9） |
| 页级路由 | 复杂页强制走轻量通道 | 公式/表格门禁（CDM/TEDS） |
| 题答配对 | 配对失败强绑错误题号 | 题号一致性用例 |
| 近义对召回 | 移除学科术语词典 / 退回单路 dense | TRAP-NEARMISS 近义对子集 top-1 转红（D29 DEC-P7） |
| query/doc 模板 | doc 侧也拼 instruction | 模板一致性用例（D29 DEC-P7 M5） |
| **rerank 排序增益** | 撤掉 rerank（或禁用端口） | TRAP-NEARMISS 近义对子集 top-1 转红（D29 DEC-P8 §8.2） |
| **门禁不被 rerank 覆盖** | 让 rerank 分参与门禁判定 / 配置 `minScore` | RC-09 门禁一致性用例（低融合分高 rerank 分候选必拒；生效值由响应带回） |
| **rerank fail-closed** | 注入超时/不可用后静默返回未重排结果 | `degraded` 标记与告警断言（D29 §8.1-4） |

### 4.3 三级验收与角色×任务

1. **单元**：SPI/算法纯函数（premise 模块，无 Spring）。
2. **API/真库**：`@SpringBootTest(RANDOM_PORT)` + RestTestClient + 真库 IT；断言 skipped=0。
3. **角色×任务**（本方案新建）：STUDENT（答疑/核对）、TEACHER（备课/查题）、运维（重灌/代际切换）三类角色 × 真实任务链路；**API 级 PASS 不得替代**。

---

## 5. 计划与排期

### 5.1 排期假设

- 团队基准：**2 名后端**（解析/切分 1 名、检索/索引/配置 1 名）+ **1 名数据工程**（SQL/语料/ETL）+ **兼职标注 2 人 & 独立评审 1 人**（非实现方）。
- 排期单位：周（W1 起）。人日按有效工时折算；标注/评审为外部资源，不并入后端人日。
- 若人力变化，**关键路径（P0→P1→P2→P3→P4）不可压缩到低于依赖允许**，可并行项见 §3.0 表。

### 5.2 里程碑与排期表

| 周次 | 阶段 | 主要工作 | 里程碑 |
|---|---|---|---|
| W1~W2 | P0 | P0-1/2/3/7：配置修正、typed 配置、评测模块骨架、缺失测试补齐 | M1 配置与评测骨架就绪 |
| W2~W4 | P0∥P1 | P0-4/5/6：解析基准集、黄金集、CI 门禁 + 反证；P1-1/2：页级路由与质量分 | **M2 SG-1/SG-2 通过**（评测基线锁定） |
| W4~W7 | P1 | P1-3~7：IR 三件套、归一化收敛、跨页表、公式（受控规则）、图；P1-8 OCR 延后不排 | **M3 D09 G1 通过**（解析切分门禁） |
| W7~W9 | P2 | P2-1~6：父子块、题号规范化、题答配对、附录、Hybrid 三修 | M4 教学语义单元落地 |
| W9~W12 | P3 | P3-1~4：输入模板、L2 断言、词法增强、代际演练；**P3-5 1024 换维：双写列+回填+门禁复标+重评+蓝绿**（D29 DEC-P2/P9，多 1 周） | **M5 D09 G2 通过 + 换维重评与复标留痕通过**（可部署 + 自检） |
| W12~W13 | P4 | P4-1~5：灰度开册（含 1024 新代际）、低分回流、角色×任务、回滚、规模造数 | 灰度基础就绪 |
| W13~W15 | P4 | **P4-6 rerank 首期必做**：端口/模型服务/超时降级 + A/B + 近义对反证 + 门禁/故障反证（D29 DEC-P8，+2 周） | **M6 D09 G3 rag 侧通过 + rerank A/B 通过**（灰度就绪） |
| W15~W17 | P4/P5 | 人工评审 SG-4、规模实测 SG-5、端到端 SG-6 签署（非实现方） | M7 质量结论可引用 |
| 持续 | P5 | 版本软失效、评测回流、量化评估（规模化后）；HNSW 仍不建（D29 S2） | — |

> 合计约 **17 周**（后端 2 人并行；较 v1.0 多 1 周用于 1024 换维的双写/重算/重评/观察期，再因 D29 DEC-P8「rerank 首期必做」多 2 周用于端口/模型服务/超时降级与 A/B 反证；换维观察期 7 天为运维等待，可与 P4 前期重叠）。P0 评测与 P1 解析并行仍为压缩关键路径的唯一有效手段。真实语料授权与人工标注若延迟，直接顺延 M2/M3；换维 `recall@20` 掉 >3 点则 ABORT 换维、回退 4096 并回到 §14.5 决策树（**rerank 救不了召回**），M5 不得签署；rerank 只在 P3-5 换维重评通过后进入灰度 A/B。

### 5.3 关键路径与依赖

```text
P0-1/2 配置 ──┐
P0-3 评测模块 ─┼─▶ P0-5 黄金集(标注) ─▶ P0-6 门禁+反证 ─▶ M2 ─┐
P0-4 解析基准 ─┘                                                │
P1-1 路由 ─▶ P1-3 IR ─▶ P1-4 归一化 ─▶ P1-5/6/7 语义 ─▶ M3(G1) ─┤
                                  └─▶ P2 父子块/题答 ─▶ M4 ─────┤
                                                                 ├─▶ P3 模板/代际 ─▶ M5(G2)
                                                                 ├─▶ P4 灰度 ─▶ P4-6 rerank(A/B+反证) ─▶ M6(G3 rag侧)
                                                                 └─▶ SG-4/5/6 人工 ─▶ M7
```
关键路径：**P0-4/5（数据与标注）→ P1-3/4（IR 与归一化）→ P2（父子块/题答）→ P3（模板/代际换维）→ P4（灰度 + rerank A/B）**。数据授权与标注是路径最前端的硬依赖；rerank 服务与端点可用性是 P4-6 的前置硬依赖（D29 DEC-P8）。

### 5.4 角色与职责分离

| 角色 | 职责 | 禁止 |
|---|---|---|
| 后端 A | 解析/切分/归一化/语义增强 | 签署 L1 标注结论 |
| 后端 B | 检索/索引/配置/代际/门禁 | 签署评测结论 |
| 数据工程 | SQL 增量、语料 ETL、造数 | 标注 gold（与标注者分离） |
| 标注者（2） | L1/L2 样本与反例标注 | 实现方人员不得标注 |
| 独立评审（1） | 反证核验、人工量表签署 | 不得是任一实现方 |

---

## 6. 风险登记册

| # | 风险 | 影响 | 概率 | 应对 |
|---|---|---|---|---|
| R1 | 真实教材授权不到位 | P0/P1 无法产出真实指标，全链路阻塞 | 高 | 提前启动授权；未授权前只用合成夹具且不对外宣称指标（D08 §8.1） |
| R2 | 人工标注/评审人力不足 | SG-4/SG-6 无法签署，G3 卡死 | 高 | 单列预算；标注者≠实现方；kappa 未达标不扩灰 |
| R3 | Premise 灰度开册触发旧行为回归 | 线上切分质量波动 | 中 | 灰度开关 + 代际秒级回切 + G1 前置门禁；先 162 页教材演练 |
| R4 | 公式自然语言/图描述幻觉 | 错误语义入库，污染检索 | 中 | 受控规则（D29 DEC-P3）+ 抽检 ≥90%；不确定标注，禁生成期自猜 |
| R5 | 跨页表解析质量不达标 | 表格不可检索 | 中 | 兜底矩阵（缩段→升级→隔离）；整册失败不部分入库；扫描件走隔离区（DEC-P1 延后） |
| R6 | **1024 换维+MRL 截断致分数漂移** | 现有基线失效、A/B 不可比、效果回退 | 中-高 | 新代际 + `embedding_v2` 双写 + 截断后重 L2（D29 S1）+ 按 D08 §4.6 复标并在新阈值下黄金集重评；不达标 ABORT 回退 4096，**禁无依据下调**（C10/DEC-P9） |
| R7 | 默认配置 `1024/vector` 与 1024/halfvec 选型不符 | 假失败或错误类型入库 | 中 | P0-1 与 P3-5 同步改 `vector-type: halfvec` + env 对齐 + 启动自检（C4/D29 DEC-P2） |
| R8 | 01 终态被误当完整 DDL | 新库缺列缺表 | 中 | 明确走 01…18/19 全序列（含换维列）+ DDL 守卫更新期望值（C5） |
| R11 | 端点不返回 1024 维、客户端零填充伪造 | 声明失真、代际 A/B 不可比 | 中 | 校验 `dimensions` 参数/正确取 MRL 前缀；非 1024 维拒绝；禁零填充（D29 S3） |
| **R12** | **rerank 服务不可用/超时/P95 恶化** | 显式降级、排序质量回退；叠加并发=1 队头阻塞 | 中 | `timeoutMs` 预算 + 显式 `degraded` + 告警；灰度实测 P95；禁静默返回未重排结果（D29 §8.1-4） |
| **R13** | **rerank 分越权覆盖门禁** | 低相关候选混入，推翻排序键/门禁键分离红线 | 中 | 配置层无 `minScore`；门禁仍在融合阶段判定（值按 DEC-P9 复标）；低融合分高 rerank 分候选必拒反证（D29 §8.1-2） |
| **R14** | **阈值复标被当成放水出口 / 前端硬编码旧值漂移** | 用复标掩盖召回退化；配置新值但 UI 显示旧值 | 中 | 复标须按 T-1~T-6 留痕 + 每层 ≥100 + 非实现方签署；召回判据仍一票否决（DEC-P9）；生效值随响应返回，禁前端硬编码（D20） |
| R9 | Hybrid 桥缺测导致角色回填回归 | 讲解模式泄题 | 低-中 | P0-7 补测 + G-2 反证；桥失败 fail-closed |
| R10 | 工期被评测集拖垮 | 计划顺延 | 中 | P0 与 P1 并行；标注前置；每周跟踪 gold 进度 |

---

## 7. 决策状态（2026-11-24 评审已决，详见 RAG-D29）

| # | 议题 | 决策 | D29 编号 |
|---|---|---|---|
| 1 | 扫描/OCR 档是否纳入首期 | **不纳入首期**（延后，记入决策文件）；扫描件进隔离区 | DEC-P1 |
| 2 | 跨维换列是否启动 | **启动，目标 1024 维**，按 D27 §14.7 SOP 执行 | DEC-P2 |
| 3 | Embedding 模型选型 | **Qwen3-Embedding-8B + MRL 截断至 1024 维**（模型不变，仅维度换档） | DEC-P2 |
| 4 | 公式自然语言生成方式 | **受控规则 + 人工抽检**（不上小模型生成） | DEC-P3 |
| 5 | D27 事实纠偏接受度 | **接受 C1~C10**；不改 D27 原文，以新建勘误/决策文件（D29）为准 | DEC-P4 |
| 6 | 排期资源确认 | ⚠️ **唯一仍未决策项**——§5.1 人力为假设，须按真实人力重算并锁定标注/评审排期 | DEC-P5 |
| 7 | 1024 维技术论证文案 | **采纳论证作为换维前置核销，不原文照搬**：5 处口径修正（英语册、按 collection 分、半天标注、rerank 必配、pooling/instruction）+ 4 项护栏 + 三档 MRL 验证 | DEC-P7 |
| 8 | **是否引入 rerank** | **确认引入，首期必做**（原为「重评不达标才触发」）：RRF top-20 → `Qwen3-Reranker-0.6B` → top-3~5；门禁仍在融合阶段判定，rerank 分只排序不越权；排期 +2 周 | DEC-P8 |
| 9 | **0.38 门禁是否写死** | **不写死**：0.38 是 2026-10-28 实测标定基线（指示性、样本不足），换维/rerank/模板/模型等结构变更后按 D08 §4.6 复标，新值写 §7.2 快照后生效；⛔ 禁止无依据下调，复标不替代召回判据 | DEC-P9 |

> 决策变更走 D29 追加 `DEC-P10`…，并同步本文对应章节；D27 原文保持不动。

---

## 附录 A：文件级改动清单（速查）

| 类别 | 新建 | 修改 |
|---|---|---|
| Java（NEW） | `PageRouter`、`ParseQualityScorer`、`ExerciseNoNormalizer`、`QuestionAnswerPairer`、`EmbeddingInputTemplate`、`VectorNormAssertion`、`RagPremiseProperties`、`RagEmbeddingInputProperties`、**`RerankPort` + `RagRerankProperties`（D29 DEC-P8）**、`HybridChunkerClientTest`、`ChunkPolicyTest`、角色×任务验收套件 | `IngestionPipeline`、`IngestJobOrchestrator`、`GlobalIngestJobOrchestrator`、`ChunkIndexer`、`PremiseDocument`、`NormalizationRules`、`HeadingHierarchyChunker`、`HybridChunkerClient`、`ChineseTokenizer`、`ParseTrace`、`IngestJobStatus`、`LocalRagPortsConfig` |
| SQL（NEW） | `18_v1_0_5_preprocessing_enhance_alter.sql`、`19_v1_0_5_embedding_v2_1024_alter.sql`（**D29 DEC-P2 换维双写列**） | `03_rag_roles.sql`（授权）、RLS 基线期望值、`scripts/init-db.sh` / `it-env.sh` 期望值、`deploy/sql/README.md`（修正已删的 04 脚本引用 C1） |
| 配置 | — | `application.yml`×2（`dimension:1024`/`vector-type:halfvec` + **新增 `rag.rerank.*`：model/endpoint/topN/returnK/timeoutMs**）、`rag.env.example`、`rag.env`（4096→1024 + `RAG_RERANK_*`）、`rag/pom.xml`（rag-evaluation 模块）；**阈值下发：前端去硬编码 0.38，改由检索响应带回生效值（D20/DEC-P9）** |
| 评测数据（NEW） | `rag/eval/cases/*.json`、`traps-nearmiss.json`（**D29 DEC-P7 第 8 类近义对陷阱**）、`rag/eval/baselines/*.json`、学科术语词典（每学科 100～300 词）、`scripts/rag-eval-harvest.sh`、规模造数脚本 | `IngestInvariantEvidenceTest`、D08 FR 状态表（陷阱由 7 类增至 8 类） |
| **首期必做（NEW，D29 DEC-P8）** | `RerankPort` + `Qwen3-Reranker-0.6B` 服务/端点 + A/B 与反证用例（当前项目无 rerank 实现，属新增能力） | `JdbcRetrievalRepository`（仅消费 RRF top-20，不改融合逻辑）、RC-09 门禁一致性用例 |

## 附录 B：与 D27 章节对照 / 纠偏索引

| D27 章节 | 本文落点 | 纠偏/深化 |
|---|---|---|
| §0 对齐表 | §1.2 能力矩阵 | 纠正「现有落点」成熟度被高估（C6/C7/C8/C9） |
| §4.1 分级路由 | §2.2 PageRouter + P1-1 | D27 只给原则，本文给接口与任务 |
| §4.2 中间格式 | §2.2 + P1-3 | bbox/confidence 当前无生产赋值，列为新建 |
| §4.3 归一化 | P1-4 | NormalizationRules 空转，先收敛 |
| §5.2 父子块 | §2.3 + P2-1 | parent_id/chunk_level 全新增 |
| §6.1 输入模板 | P3-1 | 当前裸文本入库，列 P3 核心 |
| §6.2/§13.6 模型 | §7 决策 3 / D29 DEC-P2 | **评审改为 Qwen3-8B + MRL 截断至 1024**（非原建议 4096），首期即换维 |
| §13.2/13.3 档位 | §2.4 配置模型 | 补 typed 配置类 + 守卫（D27 仅有 YAML 示例） |
| §14 维度治理 | §2.3 + P3-5 / D29 DEC-P2/P9 | 纠正 C1（04 脚本不存在）、C2（indexable 按阈值）；**首期换 1024：双写列 + MRL 重归一化 + 门禁复标后重评，不达标 ABORT** |
| §10 路线图 | §3 分阶段 WBS | 对齐 D08/D09 门禁编号，补排期与反证 |
| 补充 16~19 | §2.2（题号/题答/隔离区/维度） | 全部纳入 WBS |

---

> **诚实边界（收尾重申）**：本文是设计与计划。所有指标阈值、评测结论、门禁通过状态均须在真实语料 + 真实模型 + 非实现方签署后产出。当前 D08 的 13/14 条 FR、D09 的 G1/G2/G3 均为未通过状态——本计划的目标是让它们逐条通过，而非宣称它们已通过。
