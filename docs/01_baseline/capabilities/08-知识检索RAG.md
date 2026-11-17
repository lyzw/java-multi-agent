# 08 · 知识检索 RAG — 深度对比分析报告

> **元信息行**
> - **本报告范围**：维度 8 — 知识检索 RAG（Retrieval-Augmented Generation）。回答「平台把私有语料变成模型可引用的依据，这条链路做到了什么程度、哪些环节是内建的、哪些是占位的」。覆盖：文档摄取（解析/切分/嵌入）、索引（向量/词法/代际）、检索（双通道召回/RRF 融合/硬过滤/分数门禁）、与多租户隔离的耦合（哨兵桶 + 显式谓词）、影子比对模式（`RAG_SHADOW_ENABLED`）。
> - **证据来源**：`rag/` 全部 10 个 Maven 子模块源码（`rag-core`/`rag-ports`/`rag-premise`/`rag-application`/`rag-infrastructure`/`rag-persistence`/`rag-api`/`rag-management`/`rag-worker`/`rag-bootstrap`）、`platform/` 侧检索通道（`agent-infrastructure`/`agent-worker`/`agent-ports`）、`deploy/sql/rag/01_rag_ddl.sql` 与 `deploy/sql/rag/05`~`17` 的 13 份 ALTER 脚本、`rag/rag-bootstrap/src/main/resources/application.yml`、`rag/rag-worker/src/main/resources/application.yml`、`rag/docs/RAG-D00`~`RAG-D26` 设计文档、`docs/权限管控体系/RLS依赖清理改造计划_20261112.md`；竞品信息取自各框架官方文档（见附录 B）。
> - **撰写日期**：2026-11-15
> - **关联文档**：`docs/能力维度实现方式梳理报告.md`（第 8 章）、`docs/Agent平台竞品对比报告_2026-11-15.md`、`docs/能力维度深度报告/README-撰写规范.md`、`docs/能力维度深度报告/02-多租户隔离.md`、`docs/能力维度深度报告/06-上下文管理.md`、`rag/docs/RAG-D05_检索策略与召回质量控制.md`、`rag/docs/RAG-D08_评测体系与质量门禁.md`
> - **源码复核声明**：本报告第四章全部锚点已逐行复核到 `文件:行号`。复核中发现**三处与既有梳理报告不一致**之处——(1) 梳理报告第 8 章把「向量打分占位」归到 `agent-infra/.../RetrievalService.java`，但该类是**未被任何模块装配、运行期不可达**的草稿（详见 4.4 与文末诚实边界声明）；(2) 梳理报告称混合检索「`vector*0.5 + tfidf*0.5`」，实际生产链路的混合方式是 **RRF 排名倒数融合**（`RRF_K=60`），分数加权只存在于那份草稿里；(3) 梳理报告称影子模式用于「语料治理/索引构建」，实际采集点是**检索读面**（`RagShadow.collect("retrieval")`），且影子比对的是**隔离谓词**而非「新旧检索策略」。

---

> **⚠️ 2026-11-15 P0 复核纠偏（优先于本报告「对齐到 ≤4000 维 / 建 HNSW 索引」的改进建议）**
> `halfvec(4096)` 下 HNSW 索引「**刻意不存在**」是 `deploy/sql/rag/01_rag_ddl.sql:960-966`
> 明确钉住的设计取舍，并给出规模化后「MRL 截断到 ≤4000 维」或「二进制量化」两条路径；
> `rag.env:42-46` 已显式配置 `RAG_EMBEDDING_DIMENSION=4096` / `RAG_EMBEDDING_VECTOR_TYPE=halfvec`，
> `VectorColumnTest` 断言 4096 维 `indexable() == false`。
> 按原建议硬降维会与真实嵌入模型 `qwen3-embedding:8b` 的输出维度冲突，并使已入库向量失效。
> 本次 P0 **未改 DDL / 维度配置**，只清理 `RagStartupSelfCheck` 的零调用死方法。
> 真正的待办不是「降维」，而是「规模化后二选一」。
> 完整证据见 [`P0修复验收报告_2026-11-15.md`](./P0修复验收报告_2026-11-15.md) §3.1。

## 一、维度定义与评估坐标系

### 1.1 这个维度到底解决什么问题（问题陈述，不谈实现）

"知识检索 RAG"回答的不是"模型能不能回答"，而是"**平台的私有语料，能不能以可引用、可追溯、不可串租户、不可泄题的方式，变成模型回答的依据**"。它要解决六条独立的链路：

- **摄取链**：一本 146 页的扫描版教材，如何被解析（OCR/版面识别）、切分（chunk）、向量化（embedding），并**原子地**落进可检索的索引。这个链路的失败形态是"半本入库"——把「数据缺失」伪装成「知识边界」。
- **索引链**：向量与词法两种表示如何在同一个 chunk 身份下共存；换嵌入模型时如何**零停机**切换（旧代际继续服务、新代际后台构建）而不产生"两套不可比的向量空间混在一起算相似度"的静默错误。
- **检索链**：一次 query 如何变成候选集；多路召回如何融合；**版本错**（串教材）与**角色错**（泄题）如何在**进入上下文之前**被硬过滤掉；以及"检索不到"如何成为一个合法输出。
- **隔离链**：多租户下，检索必须只看到「本租户私有语料 ∪ 平台全局公共语料」。哨兵租户（`RagScope.GLOBAL_TENANT_ID`）是全局桶的身份载体，它绝不能成为会话身份。
- **治理链**：语料的"可信度"是一组正交轴——来源是否 `VERIFIED`、载体是否被撤回、版权是否放行、权威等级是否达门槛、校验是否过期。任何一条不满足，就**不可检索**，而不是"检索到再处理"。
- **质量链**：检索质量的改进必须是**可度量、可回归、可灰度**的。否则调阈值就是拍脑袋，改一次检索策略就是一次没有反馈的赌博。

这个维度与维度 2（多租户）、维度 6（上下文）、维度 7（权限与护栏）、维度 10（可观测）是**强耦合**的：RAG 是唯一一个"数据从库表经检索进入模型上下文"的通道，因此它的隔离与护栏缺陷会**直接变成上下文泄露**。

### 1.2 能力分层的四档模型（通用分层法）

按"RAG 能力由哪一层持有、内建程度多深"给出四档，从弱到强：

| 档位 | 名称 | RAG 由谁持有 | 代表形态 | 典型代价 |
|---|---|---|---|---|
| **L0 无内建** | 宿主应用自管 | 宿主代码里的 ad-hoc 检索 + 向量库 SDK | 多数 Agent 框架默认形态；Agno/CrewAI 的 `Knowledge` 接近此档 | 摄取/切分/索引/评测全靠开发者自建，质量不可控 |
| **L1 组件内建** | 框架提供检索组件包 | 框架的 `retriever`/`Knowledge` 抽象 + 可插拔向量库 | LangChain（`MultiQueryRetriever`/`EnsembleRetriever`/`ParentDocumentRetriever`）、CrewAI（`knowledge_sources` + `RagTool`）、Agno（`Knowledge` + `SearchType`） | 组件齐备但**评测与治理仍外置**；多租户隔离基本不管 |
| **L2 服务内建闭环** | 独立检索服务，端到端自持 | 服务端负责摄取→索引→检索全链，含切分/嵌入/融合 | OpenAI Vector Stores + File Search、Google Vertex AI RAG Engine | 隔离与租户语义通常由托管方处理（单租户假设） |
| **L3 数据层强制 + 治理闭环** | 检索规则下沉到 DB 与应用层 Gate | 硬过滤谓词在 SQL、隔离谓词双写、治理轴在 `ADMISSIBLE_TEMPLATE` | **本平台（rag 独立 reactor + pgvector/tsvector 双通道 + RRF + 版本/角色硬过滤 + 可信度准入 + 哨兵桶 + 代际蓝绿 + 影子对照）** | 运维成本高：索引代际、维度校验、阈值标定、RLS 退役门禁都是自己的活 |

**判定**：本平台落在 **L3**。这是本次对比中**唯一**把"检索期的版本隔离、泄题闸门、语料可信度准入"做成**不可绕过的数据层谓词**（而非 prompt 约束或应用层 if）的方案，也是唯一把 RAG 作为**独立部署单元**（独立 Maven reactor、独立进程、独立 schema `rag`、独立角色 `rag_app`/`rag_ops`）来治理的平台。代价是本报告第七章列出的六项改进点——L3 的复杂度不会自动消失，它只会从"没做"变成"要维护"。

**为什么 L3 在业界罕见**：主流 Agent 框架把 RAG 做成"检索器组件"（L1），因为它们假设检索只是"找几段文本"。但对**教辅/教材**这类语料，检索结果本身带**硬业务约束**（不能用错版本、不能泄题、不能用未授权教材），这些约束必须落在"不进入上下文"的地方——即数据层。本平台选择 L3 的理由是产品定位：教辅场景的"串版本"与"泄题"是**第一大/第二大事故**（`RetrievalOrchestrator.java:24-43` 明确写死），容不下"靠 prompt 约束"。

### 1.3 评估坐标系：5 个评价轴

| 轴 | 含义 | 观测方式（如何度量） |
|---|---|---|
| **内建闭环度** | 摄取→嵌入→索引→召回→过滤→门禁→上下文注入，链路上有多少环节是平台自持、多少是外部依赖/占位 | 逐环检查：解析器、嵌入端口、切分器、向量库、融合器、rerank、评测集分别指向哪个实现；占位实现是否有 fail-closed 闸门拦住误装配 |
| **隔离强制性** | 租户谓词与业务过滤谓词由哪一层强制；能否绕过 | 越权查询是否返回 0 行；`FORCE RLS` 在场时是否双保险；退役 RLS 后显式谓词是否等价；**谓词是否在 BM25 与向量两侧同源** |
| **质量可度量性** | 检索质量能否被离线度量并作为回归门禁 | 是否存在评测集、Recall@K/MRR/NDCG 的实现、阈值是否由实测标定、CI 是否阻断回归 |
| **失败可诊断性** | 检索失败时能否定位到具体环节；能否灰度切换新旧策略 | 召回漏斗计数是否逐环落库；向量列维度是否在启动期校验；检索策略能否双跑比对并出报表 |
| **治理可审计性** | 检索了什么、为什么召回、依据是否可回溯 | `rag_access_audit` 是否逐次留痕；命中是否带 `locator`/`edition`/`role`/`reason`；引用是否落 `rag_answer_citation` |

### 1.4 本维度在 agent 系统中的位置（上下游依赖关系图）

```
【语料侧·写】上传 PDF → rag_document(载体) → rag_ingest_job(PENDING)
   → IngestJobConsumer.claimNext()(逐租户显式清单 + FOR UPDATE SKIP LOCKED)
   → IngestJobOrchestrator.run(): PARSING(Docling/MinerU/Paddle 解析 → 结构优先切分
     HeadingHierarchyChunker + 版面角色判定) → CHUNKING(EmbeddingPort 批量 →
     rag_chunk + rag_chunk_embedding) → INDEXING(ChineseTokenizer → text_jieba → GIN)
     → markDocumentParseStatus(CHUNKED) + markSucceeded(切分策略留痕)
                          ↓ rag_chunk / rag_chunk_embedding / rag_edition
【检索侧·读】POST /api/v1/retrieval (X-Tenant-Id + Bearer)
   → RagJwtAuthFilter(RS256；租户域 tenant_id claim == X-Tenant-Id，不符 403；缺/坏 401)
   → RagRetrievalController(requireTenant 400 / toCoordinate 400 / scopeMode null→STRICT)
   → JdbcTenantScopedRetrieval ★单事务: set_config(app.tenant_id) → scope=TenantWithGlobal
     → RetrievalOrchestrator 七步(①知识点解析 ②多路召回 ③版本过滤 ④角色过滤
        ⑤知识点补全 ⑥排序 ⑦门禁) → RagSqlScopeTranslator 渲染显式谓词
     → JdbcRetrievalRepository: 稠密 pgvector `1-(e.embedding<=>?)` + 代际过滤
       / 词法 ts_rank(to_tsvector(text_jieba)) → RRF(k=60) + 相关性分=max(通道原始分)
     → INSERT rag_access_audit(action=RETRIEVE) ★同事务！审计失败 ⇒ 整次检索回滚
                          ↓ RagResult(hits[7 字段白名单] + versionMismatch + suppressed)
【platform 侧·跨进程】HttpRagRetrievalGateway(唯一 platform→rag 通道)
   Bearer JWT 透传 + X-Tenant-Id；3s 连接 / 20s 读超时 / 512KB 响应上限 / 不跟随重定向
   fail-closed(非2xx/超时/非法JSON/缺 hits ⇒ 抛) → parseResult 只取 7 字段，
   chunkId/text 空白即丢弃
   → AgentChatOrchestrator.doRag() ← 维度 6 耦合点
     → RagHitQuotaPort.applyQuota(token 配额裁剪) → RAG_GUARD.wrapToolResult(不可信围栏)
     → 注入模型上下文

依赖的下游维度：多租户(02) · 上下文(06) · 权限与护栏(07) · 成本与预算(09)
                  · 可观测与审计(10) · 可靠性(11) · 部署与伸缩(12)
平行对账：RagShadow（检索读面影子：显式谓词 vs RLS 基线；M2 观察期）
```

**关键依赖**：本维度的隔离强制性依赖维度 2 的 `rlsDependent → 0` 迁移出口（`RlsDependencyProbe.EXPECTED` 当前基线 30，退役目标 0）；上下文注入依赖维度 6 的 `RagHitQuotaPort` 配额裁剪与 `PromptGuard` 的不可信围栏；泄题闸门依赖维度 7 的 fail-closed 纪律。

### 1.5 深度补充：本维度能力边界的"三不"声明（避免评估口径污染）

> 1.1~1.4 定义了 RAG 要解决的问题与坐标系。本节补充三条**评估时必须先厘清的边界**——它们是本次源码复核发现的、最容易导致"评错对象"的口径陷阱，直接决定后续所有章节的判断。

1. **不把"链路可达"等同于"质量合格"**：本平台的检索链路（4.5.1 链 A）在机制上完整可达——pgvector 真余弦、ts_rank 真词法、RRF 真融合、同事务审计。但**链路通 ≠ 召回准**：0.38 阈值仅 27 条样本标定、Recall@K 全未实现（R-3）、真实召回质量未测（诚实边界声明四）。评估"这个 RAG 好不好"必须区分"工程闭环度"（高）与"质量可度量性"（低）两根轴，1.3 的五轴模型正是为此设计。
2. **不把"占位"归错对象**：既有梳理报告称"RAG 向量打分占位未闭环"——**这是归因错误**。向量库/向量打分**已闭环**（pgvector + GIN + 代际过滤），占位的是**嵌入模型**（`LocalDeterministicEmbeddingPort`，默认装配路径），且真正承载"占位打分"的 `RetrievalService` 是 `agent-infra` 的**运行期不可达草稿**（4.5.3 链 C）。引用此旧结论须标注"已被复核纠偏"（`docs/能力维度实现方式梳理报告.md:123,127` 与本报告诚实边界声明五）。
3. **不把"刻意取舍"当缺陷修**：`halfvec(4096)` + 无 HNSW 索引是 DDL 注释（`01_rag_ddl.sql:960-966`）+ 配置（`rag.env:44-46`）+ 测试（`VectorColumnTest`）共同钉住的设计取舍（pgvector 官方维度上限背书，2.4(13)），不是配置漂移。评估"有没有 ANN 索引"时应记为"规模化路径待启用（MRL/二进制量化）"，而非"缺索引缺陷"。

### 1.6 深度补充：评估坐标系在 RAG 上的"加权优先级"（1.3 五轴的落地取舍）

> 1.3 给了五个评价轴但未排优先级。对教辅 RAG 而言，五轴**不是等权**——错误代价不对称表（8.4）决定了权序。明确权序可避免"用通用 RAG 标尺量教辅系统"的误判。

| 优先级 | 评价轴（1.3） | 教辅场景权重 | 理由（对应错误代价） |
|---|---|---|---|
| P0 | **隔离强制性** | 最高 | 跨租户召回=合规事故（极高代价、极难补救，8.4）；DB 层强制不可退让 |
| P0 | 内建闭环度之"治理轴" | 最高 | 串版本/泄题是第一/第二大事故，必须在数据层硬过滤（L3 判定） |
| P1 | 失败可诊断性 | 高 | 检索失败误判为"零命中"会掩盖组件故障（AP-13）；但可渐进补 |
| P2 | **质量可度量性** | 中（当前最低） | 漏召可补救（换问法），故度量缺失不是安全事故；但它是"持续变准"的前置（R-3） |
| P2 | 治理可审计性 | 中 | 取证重要但属合规增强，schema 已预留（R-7 补实现即可） |

> **判断**：本平台的资源投入顺序（隔离/治理轴 P0 已做到 L3 → 质量度量 P2 待补）与错误代价权序**一致**，是理性的。第七章改进项 R-0~R-7 的优先级（P0 先修口径与度量、P1/P2 补策略与审计）也遵循此权序。若评估者用"召回率高低"作为唯一标尺，会得出"本平台落后"的错误结论——因为那恰是权重最低的一轴，且当前不可测。

---

## 二、理论体系（必须引用真实出处）

### 2.1 核心理论/概念框架（逐一给真实出处）

#### (1) RAG 原始论文 — 检索增强生成的参数化/非参数化双记忆
- **出处（核实）**：Lewis, P., Perez, E., Piktus, A., Petroni, F., Karpukhin, V., Goyal, N., Küttler, H., Lewis, M., Yih, W.-T., Rocktäschel, T., Riedel, S., Kiela, D., *"Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks"*, **NeurIPS 2020**（arXiv:2005.11401，v1 2020-05-22，v4 2021-04-12）。<https://arxiv.org/abs/2005.11401> ｜ <https://proceedings.neurips.cc/paper/2020/hash/6b493230205f780e1bc26945df7481e5-Abstract.html>
- **定义**：把预训练 seq2seq 模型的**参数化记忆**与一个**显式非参数化记忆**（Wikipedia 的稠密向量索引，由预训练神经检索器访问）结合，在知识密集任务上取得 SOTA，同时提供"provenance（出处溯源）"与"无需重训即可更新世界知识"的能力。论文提出两种形态：**RAG-Sequence**（同一批 passage 条件化整个生成序列）与 **RAG-Token**（每个 token 可用不同 passage）。
- **与本维度的关系**：RAG-Sequence 的思路直接对应本平台的"一次检索 → 一组 chunk → 注入上下文"（`AgentChatOrchestrator.doRag()` 一次取 `topK` 条）。但本平台额外引入了一个原始论文没有的硬约束：**RAG-Token 式的"不同 token 用不同 passage"在本项目被禁止**——因为教辅场景的答案必须来自**同一版本坐标**（五轴 `EditionCoordinate`），混用多个版本的 passage 会导致"页码对不上"。本平台的 `EditionScope.admits()` 硬过滤正是这一约束的落地。
- **原始论文没有覆盖的**：多租户隔离、向量库多租户安全模型、检索质量评测、召回漏斗——这些是后续工作与工程实践补上的（见 (5)(7)(8)）。

#### (2) Naive RAG → Advanced RAG → Modular RAG 三阶段演进
- **出处（核实）**：Gao, Y., Xiong, Y., Gao, X., Jia, K., Pan, J., Bi, Y., Dai, Y., Sun, J., Wang, M., Wang, H., *"Retrieval-Augmented Generation for Large Language Models: A Survey"*, **arXiv:2312.10997**（v1 2023-12-18，v5 2024-03-27）。<https://arxiv.org/abs/2312.10997>
  - **补充出处（核实）**：同一课题组另有 *"Modular RAG: Transforming RAG Systems into LEGO-like Reconfigurable Frameworks"*, **arXiv:2407.21059**（Gao, Xiong, Wang, Wang，同济大学智能自主系统研究院 / 复旦大学大数据学院）。<https://arxiv.org/abs/2407.21059>
  - ⚠️ **编号更正**：任务书给出的 "Modular RAG（arXiv:2312.10997）"编号有误——`2312.10997` 是 Gao 等人的 **RAG 综述**（首次提出 Naive/Advanced/Modular 三阶段分类与图 1）；"Modular RAG" 作为独立框架论文是 `2407.21059`。本报告按各自真实出处分别引用。
- **定义**：**Naive RAG** = Indexing → Retrieval → Generation 三段直线，缺陷被论文点为两点：**(1) 查询理解浅**（query 与 chunk 的语义相似度未必一致）；**(2) 检索冗余与噪声**（把所有召回 chunk 塞给 LLM 并非总是有益，会增加幻觉风险）。**Advanced RAG** = 在检索**前**做 query 改写等预处理、检索**后**做 rerank 等优化（论文原话 "Advanced RAG paradigm focuses on optimizing the retrieval phase"；典型手段 = "query rewriting is used to make the queries more clear and specific" + "reranking of retrieval results"）。**Modular RAG** = 把 RAG 分解为**独立模块与专门算子**，引入 **routing / scheduling / fusion** 机制，识别出 **linear / conditional / branching / looping** 四种模式。
- **与本维度的关系**：本平台的检索编排（`RetrievalOrchestrator` 七步）是 **Advanced RAG 的"检索后优化"部分**（硬过滤 + 排序 + 门禁），但**"检索前处理"整段缺失**——无 query 改写、无 HyDE、无多路 query 展开（改进点 R-2 覆盖）。本平台的 **RRF 融合**是 Modular RAG 的 **fusion** 算子的一个具体实例，但**不是**其完整的 routing/scheduling——检索链路是**固定线性**的，不支持按 query 类型路由。这是"Advanced RAG 局部实现，Modular RAG 未实现"的分层依据。"检索冗余与噪声"这一条在实现上有**双重保险**：`RagHitQuotaPort` 按 token 配额裁剪命中（维度 6 耦合）+ `AgentChatOrchestrator.serializeHits` 的 `MAX_INJECT_HITS`/`MAX_HIT_TEXT` 固定截断。

#### (3) 切分策略谱系 — fixed-size / recursive / semantic / 结构化 / late chunking
- **出处（核实）**：
  - **Late Chunking**：Günther, M., Mohr, I., Williams, D. J., Wang, B., Xiao, H., *"Late Chunking: Contextual Chunk Embeddings Using Long-Context Embedding Models"*, **EMNLP 2024**（arXiv:2409.04701，Jina AI GmbH / Weaviate B.V.）。<https://arxiv.org/html/2409.04701v3> ｜ <https://jina.ai/news/still-need-chunking-when-long-context-models-can-do-it-all/>
  - 论文的核心观察：普通切分后各块独立编码，**丢失跨块上下文**（论文用柏林维基条目的 "its" / "the city" 指代 "Berlin" 的例子说明）；late chunking 先把整篇文档送进长上下文嵌入模型得到逐 token 表示，**再**按边界做 mean pooling，于是 chunk 向量隐含了全文语境。论文在 BeIR 上报告的相对提升：句界切分 +3.63%、固定长切分 +3.46%、语义切分 +2.70%。
- **定义与谱系**：**fixed-size**（固定 token/字符窗口切，简单但会切开语义单元）、**recursive**（按分隔符优先级递归切分，如 LangChain 的 `RecursiveCharacterTextSplitter`）、**semantic chunking**（按相邻句子的嵌入相似度找"断点"，相似度低谷即切点）、**late chunking**（不改变切分时机，只改变**编码顺序**——先编码全文、后切分）。
- **与本维度的关系**：本平台走的是**第四条路——结构优先切分**（`HeadingHierarchyChunker` + `IngestionPipeline` 的内置主循环）：以**版面结构**为边界（标题块开启新节、题目块自成单元、答案块自成单元），结构不可得时退化为带重叠的固定窗口。这与 late chunking 是**互补**而非竞争关系——late chunking 解的是"上下文丢失"，结构切分解的是"边界正确"，而本平台的 `BlockRoleResolver`（答案区判定）是**第三个问题**：把答案块与讲解块分开。前两者是通用 RAG 问题，第三者是教辅特有问题，**通用方案都不覆盖**。
  - ⚠️ **诚实边界**：本平台的 chunk 编码是**逐 chunk 独立**调用 `EmbeddingPort.embed(List.of(chunk.text()))`（`IngestJobOrchestrator.java:116`、`ChunkIndexer.java:244`），**没有 late chunking**。这意味着"定义与适用条件被切开"的风险真实存在——尽管本项目用**结构边界**（而非固定窗口）大幅降低了它。
  - `HeadingHierarchyChunker` 的存在还带来一个额外收益：**章节路径（`section_path`）作为 chunk 的定位信息**被持久化（`rag_chunk.section_path`），使 `locator` 能回溯到"第几章第几节"，这是通用 RAG 框架一般不产出的。

#### (4) 向量检索 vs 混合检索 — BM25/dense 双通道与 RRF
- **出处（核实）**：
  - **SPLADE**：Formal, T., Piwowarski, B., Clinchant, S., *"SPLADE: Sparse Lexical and Expansion Model for First Stage Ranking"*, **SIGIR 2021**（arXiv:2107.05720）。<https://www.arxiv.org/abs/2107.05720> — 用**显式稀疏正则 + log 饱和**学习词项权重，保留倒排索引效率同时获得神经排序质量，是"学习型稀疏"路线的代表。
  - **ColBERT**：Khattab, D., Zaharia, M., et al., *"ColBERT: Efficient and Effective Passage Search via Contextualized Late Interaction over BERT"*, **SIGIR 2020**（arXiv:2004.09332）— 逐 token 编码后做 late interaction，是"多向量检索"的代表。
  - **HyDE**：Gao, L., Ma, X., Lin, J., Callan, J., *"Precise Zero-Shot Dense Retrieval without Relevance Labels"*, **arXiv:2212.10496**（2022-12-20）。<https://arxiv.org/abs/2212.10496> — 先生成"假设文档"再对其编码检索，把短 query 的分布拉近长文档。
- **定义**：混合检索同时使用**稠密**（embedding 语义相似）与**稀疏/词法**（BM25/ts_rank 精确词匹配）两路信号。融合方式有两类：**分数加权**（需要人工标定两路分数量纲与相对系数）与 **RRF（Reciprocal Rank Fusion）**（只用排名，无需标定）。
- **与本维度的关系**：本平台实现了**稀疏与稠密双通道 + RRF 融合**（`JdbcRetrievalRepository.search()`），且**明确选择了 RRF 而非分数加权**——`RAG-D05 §2.1` 给出的理由与理论一致："稠密余弦距离与 BM25 分数量纲不同、分布不同，直接加权需要人工标定两者的相对系数，而该系数随语料变化。RRF 只用排名，无需标定，跨语料稳定。代价是丢失了『第一名比第二名强多少』的信息——因此 RRF 之后**必须**接重排器。"
  - ⚠️ **本平台选了 RRF，但没接重排器**（见 4.2 决策 D-4）。这意味着 RRF 的固有代价（丢失分差信息）**目前没有被补偿**——这是本维度最实质的能力缺口，改进点 R-2 覆盖。
  - 词法通道的存在理由是**教辅术语精确匹配**：`RAG-D05 §2` 举例——纯稠密向量会把「二次函数」与「反二次函数」、「相似三角形」与「全等三角形」靠得很近，而它们在教学上完全不同。这与 SPLADE 论文所解决的"词法匹配精确性"是同一问题域，但本平台用的是**无词典的中文 bigram 分词**而非学习型稀疏模型。

#### (5) Reranking — Cross-Encoder
- **出处（核实）**：Nogueira, R., Cho, K., *"Passage Re-ranking with BERT"*, **arXiv:1901.04085**（2019-01-13，v5 2020-04-14）。<https://arxiv.org/abs/1901.04085> — 论文报告其在 TREC-CAR 上为 SOTA，并在 MS MARCO passage retrieval 任务上以 **MRR@10 相对提升 27%** 超过此前 SOTA。
- **定义**：**Bi-Encoder（双塔）**把 query 与 doc **分别**编码成向量，比较快但两者从未交互；**Cross-Encoder**把 `[query; doc]` **拼在一起**送入模型做联合编码，精度高但必须对每个 (query, doc) 对做一次前向，无法预计算——所以只能作为**二阶段精排**（一阶段召回 100 条 → 二阶段精排到 20 条）。
- **与本维度的关系**：本平台**尚未实现 rerank**——`RAG-D05 §2.2` 设计了 `bge-reranker-v2-m3` 类 cross-encoder 的三阶段流水线（一阶段双塔 RRF 100 条 → 二阶段 cross-encoder 100→20 → 三阶段规则过滤 20→n），但全仓检索 `rerank|cross-encoder|crossencoder` 在 `rag/` 与 `platform/` 的 `*.java`/`*.yml` 中**零命中**，实现不存在。
  - ⚠️ 且设计文档与实现的**顺序**有分歧：设计文档 §2.2 的三阶段表把"三阶段过滤（版本/角色）"排在**最后**，而实际实现（`RetrievalOrchestrator.java:24-43`）把过滤放在**重排之前**——这是**有意的**（"重排模型不知道哪些内容是答案册…顺序反了，闸门就形同虚设"），但意味着"过滤后候选仍足够（召回 100 条，过滤后通常仍 > 30 条）"这个前提需要实测验证，而目前**没有 100 条召回的配置**（`CANDIDATE_MULTIPLIER=3` × `topK`，平台侧 `topK` 上限 50）。

#### (6) 嵌入模型 — text-embedding-3 / bge / e5 / Matryoshka
- **出处（核实）**：
  - **OpenAI text-embedding-3**：OpenAI 官方文档 *"embeddings"*，<https://platform.openai.com/docs/guides/embeddings>；模型卡 *"text-embedding-3-large"* / *"text-embedding-3-small"*。
  - **BGE**：Chen, J. et al., *"C-MTEB: Chinese Massive Text Embedding Benchmark"*（BGE 系列官方评测），arXiv:2309.07597；模型仓库 <https://huggingface.co/BAAI/bge-m3>。
  - **E5**：Wang, L. et al., *"Improving Text Embeddings with Large Language Models"*, **ACL 2024**（arXiv:2401.00368）。
  - **Matryoshka**：Kusupati, A. et al., *"Matryoshka Representation Learning"*, **NeurIPS 2022**（arXiv:2205.13147）。
- **定义**：嵌入模型把文本映射到稠密向量空间。Matryoshka 表示允许**单一模型输出多种维度**（取前 d 维即得 d 维表示），使"按语料规模选维度"成为可能而不必换模型。
- **与本维度的关系**：本平台的嵌入**端口抽象**做得完整（`EmbeddingPort` 接口 + `dimension()` + `modelVersion()`），且**刻意把维度做成显式建模**（`VectorColumn` record：类型白名单 `vector`/`halfvec` + 维度 + ANN 索引可行性判定）。这是一个**值得肯定的工程决策**：多数实现把 `vector(1024)` 写死成常量，换模型时"写库时才炸"；本平台在**装配期**就比对 `vectorColumn.dimension() != embeddingPort.dimension()` 并直接拒绝装配（`JdbcRetrievalRepository.java:242-250`）。
  - ⚠️ **但存在一处真实的配置-结构漂移**：`deploy/sql/rag/01_rag_ddl.sql:389` 的列类型是 `public.halfvec(4096)`，而 `rag-bootstrap/application.yml:103,109` 的默认配置是 `dimension: 1024` + `vector-type: vector`。二者不一致（详见 4.2 决策 D-3 与 5.3）。本平台的维度校验是**配置侧自洽**（配置 vs 端口），**不是配置侧 vs 数据库实际列**——所以它能挡住"模型换维度"，挡不住"配置与 DDL 不同步"。

#### (7) 检索评测 — Recall@K / MRR / NDCG / RAGAS
- **出处（核实）**：
  - **RAGAS**：Es, S., James, J., Espinosa-Anke, L., Schockaert, S., *"RAGAS: Automated Evaluation of Retrieval Augmented Generation"*, **arXiv:2309.15217**（EACL 2024 Demo）。<https://arxiv.org/abs/2309.15217> — 提出三个**免参考**（reference-free）指标：**Faithfulness**（答案的断言可从上下文推出；实现方式为 LLM 拆句 + 逐句判定，得分 F = |V|/|S|）、**Answer Relevance**（由答案生成 n 个假问题，与原问题算嵌入余弦相似取均值）、**Context Relevance**（抽取必要句占比）。论文在自建的 WikiEval 数据集上与人工标注对比，报告 pairwise accuracy：Faithfulness 0.95、Answer Relevance 0.78、Context Relevance 0.70。
  - **信息检索标准指标**（Recall@K / MRR / NDCG）：Järvelin, K., Kekäläinen, T., *" cumulated gain-based evaluation of IR techniques"*, **TOIS 2002**（arXiv:cs/0109014），NDCG 的出处；MRR 的经典定义见 Järvelin & Kekäläinen 2002 与 Voorhees 的 TREC 评测实践。
  - **LLM-as-judge 的偏差**：Zheng, L. et al., *"Judging LLM-as-a-Judge with MT-Bench and Chatbot Arena"*, **NeurIPS 2023**（arXiv:2306.05685）— 记录了 position bias / verbosity bias / self-enhancement bias。
- **与本维度的关系**：本项目**有指标定义，但全部未实现**。`rag/docs/RAG-D08_评测体系与质量门禁.md:360-369` 定义了完整的 L2 检索指标表：

  | 代号 | 指标 | 定义 | 档 | 初始阈值 | 状态 |
  |---|---|---|---|---|---|
  | RC-01 | Recall@k | \|召回 ∩ 相关集\| / \|相关集\|（k 默认 10） | B | ≥ 0.90（初值，待实测标定） | ⬜ 未实现 |
  | RC-02 | MRR@k | 首个相关命中排名倒数的平均值 | B | ≥ 0.75（初值，待实测标定） | ⬜ 未实现 |
  | RC-07 | 门禁**误拒**率 | 应可回答的查询中返回空结果的比例 | B | ≤ 0.10（初值，待实测标定） | ⬜ 未实现 |
  | RC-08 | 门禁**漏拒**率 | 应拒答的查询中返回非空结果的比例 | A | **0%**（不变式） | ⬜ 未实现 |
  | RC-09 | 分数门禁一致性 | 结果集中最低分与 `minRetrieverScore` 的关系符合门禁语义 | A | **100%**（不变式） | ⬜ 未实现 |

  其中 RC-09 是**已经生效**的（`RetrievalOrchestrator.java:136-138` 用 `s.score() >= minScore` 过滤，且 `RetrievalRepositoryIT.returnedScoreIsRelevanceNotRrfFusionValue()` 就是它的守护用例），RC-01/02/07/08 **完全没有实现**。这是本维度最明确的"设计已定、代码未落"的缺口（改进点 R-3 覆盖）。

#### (8) RAG 与多租户隔离耦合 — 向量库多租户安全模型
- **出处（核实）**：
  - **Pinecone 多租户模型**：Pinecone 官方文档 *"Multitenancy"*，<https://docs.pinecone.io/guides/index-data/multitenancy> — 提供三种隔离粒度：**per-index**（每租户一个索引，服务端物理隔离）、**per-namespace**（同一索引内按 namespace 隔离）、**per-record**（同一 namespace 内按 metadata filter 过滤）。官方明确指出 namespace 隔离在服务端强制执行，但**依赖调用方正确传递 namespace**。
  - **Weaviate 多租户**：Weaviate 官方文档 *"Multi-tenancy"*，<https://docs.weaviate.io/weaviate/managing-collections/multi-tenancy> — 分为 **tenant isolation**（每租户独立 collection）与 **shared**（同 collection 内按 property + 多租户过滤）。
  - **pgvector**：pgvector 官方 README，<https://github.com/pgvector/pgvector> — 提供 `vector`/`halfvec` 类型与 HNSW/IVFFlat 索引；**本身不含任何租户概念**，隔离完全由上层 SQL/RLS 负责。
- **理论要点**：向量库的隔离强度排序为 **per-index > per-namespace > metadata filter**，且**越弱的模式越依赖"调用方正确传参"**。所有主流向量库都不提供"租户由数据库会话自动推断"的机制——**除非用 RLS**（pgvector 本身无租户概念，隔离完全由上层 SQL/RLS 负责）。
- **与本维度的关系**：本平台**不走向量库原生多租户**，而是走 **PG + RLS + 应用层显式谓词 + 哨兵桶** 的组合：
  - `pgvector` 只提供 `vector`/`halfvec` 与 ANN 索引，**无租户概念**（与上述理论一致）。
  - 隔离由 `RagSqlScopeTranslator`（应用层唯一谓词渲染点）翻译 `RagScopeFilter`（密封接口，4 个 case：`TenantOnly`/`TenantWithGlobal`/`GlobalOnly`/`None`）为 SQL 谓词 + 绑定参数。
  - 全局公共语料用**哨兵租户 ID**（`RagScope.GLOBAL_TENANT_ID = "00000000-0000-0000-0000-000000000000"`，nil UUID）承载，检索侧谓词恒为 `tenant_id IN (本租户, 哨兵)`。
  - **哨兵绝不能成为会话身份**：`rag_current_tenant()` 遇哨兵直接 RAISE 42501（数据库层封堵）；应用层再挡一次（`RagScopeFilter.TenantOnly`/`TenantWithGlobal` 构造器拒绝哨兵、`RagRetrievalController.requireTenant` 返回 400）。这是**纵深防御**，两层都在。
  - ⚠️ **本平台在这一点上强于主流向量库**：pgvector + RLS 的组合让隔离由**数据库策略层**强制（`FORCE ROW LEVEL SECURITY` + 37 条策略），而不是"应用记得传 namespace"。这正是维度 2 的 L3 判定在 RAG 上的延续。**但代价是 RLS 退役（M4）尚未完成**——`RlsDependencyProbe.EXPECTED` 当前仍是 30（R1=6 + R2=6 + R3=16 + CENTRAL=2），M4 的四批 SQL 全部标记 `⛔DRAFT / 禁止应用`。
  - **关键设计事实**：M3 阶段已把检索读面的**显式谓词补齐**（`JdbcRetrievalRepository` 的 6 处 SELECT 全部经 `scopeTranslator.render(scope, "c.tenant_id")`），且渲染结果与现役 RLS 读策略 `tenant_id = ANY(ARRAY[rag_current_tenant(), rag_global_tenant_id()])` **逐字等价**。这就是 M4 退役 RLS 的对偶安全证据。

#### (9) GraphRAG
- **出处（核实）**：Edge, D., Trinh, H., Cheng, N., Bradley, J., Chao, A., Mody, A., Truitt, S., Metropolitansky, D., Ness, R. O., Larson, J., *"From Local to Global: A Graph RAG Approach to Query-Focused Summarization"*, **arXiv:2404.16130**（2024-04-24，Microsoft Research）。<https://arxiv.org/abs/2404.16130> ｜ 官方文档 <https://graphrag.openml.io/>
- **定义**：不等查询时检索单个 chunk，而是先在索引期用 LLM 从语料抽出**实体/关系/关键断言**构建知识图谱，用 **Leiden 算法**做层次化社区检测，为每个社区生成摘要；查询期提供四种模式：**Global Search**（对社区摘要做 map-reduce，回答"这批语料的主题是什么"这类全库问题）、**Local Search**（从匹配实体扇出邻居）、**DRIFT Search**（扇出 + 社区上下文）、**Basic Search**（退回 top-k 向量检索）。
- **论文自陈的能力边界**：GraphRAG 相对向量 RAG 在 **comprehensiveness（全面性）72–83% 胜率**与 **diversity（多样性）62–82% 胜率**（p < .001）上显著胜出，但在 **directness（直接性）**上**低于**向量 RAG——作者把这当作"验证性对照"（向量 RAG 对局部问题本就该更直接）。
- **与本维度的关系**：本平台**未实现 GraphRAG**，但**有一个与 GraphRAG "Local Search" 同源的机制**：`RetrievalOrchestrator` 的 ⑤ 知识点补全（沿 `kp_ids` 聚合）与 `findPrerequisites`（沿 `prerequisites` 边扩展）。这两者都是**在已建好的结构化关系图上做邻域扩展**——`rag_knowledge_point` + `rag_knowledge_prerequisite` 构成的正是一个人工/规则维护的**知识图谱**（比 LLM 抽出的图更精确、但覆盖更窄）。
  - **可借鉴点**：GraphRAG 证明"结构化关系 + 社区摘要"能回答向量检索**回答不了**的**全局性问题**（"这一章讲了哪些主题"）。本平台的 `RetrievalMode.OVERVIEW`（概览模式）在设计意图上最接近 GraphRAG 的 Global Search，但实现上只是"排除答案类、保留题目与例题"的**过滤模式**，**没有社区摘要**——所以它回答不了真正的全局性问题。这是一个明确的功能缺口，本报告在 5.3 与改进点 R-2 中讨论。

#### (10) 切分与召回的"教辅特殊性"（本项目自有理论，标注来源）
- **出处**：本项目自有设计文档 `rag/docs/RAG-D05_检索策略与召回质量控制.md`（v1.0，设计基线）、`rag/docs/RAG-D04_教辅数据处理流水线.md`、`rag/docs/RAG-D07_数据源权威性与合规治理.md`。这些不是学术文献，而是本项目的**内部设计基线**，本报告按"工程判断"标注，不伪装为学术理论。
- **核心命题**：教辅语料有三条**其他领域没有**的检索期硬约束——
  1. **版本差异** → `EditionScope` 的五轴坐标（`subject`/`grade`/`term`/`publisher`/`revisionCode`），失效后果是"串版本：页码对不上"；
  2. **习题与答案分离** → `ChunkRole` + `RetrievalPolicy.allowsRole`，失效后果是"泄题"；
  3. **知识点碎片化** → 知识点补全，失效后果是"只讲不练/只练不讲"。
- **与本维度的关系**：这三条约束是本平台与所有通用 RAG 框架的**根本分野**。它们不是"调参"能解决的，而是要求过滤发生在**数据层**而非 prompt 层。这也是 1.2 节判定 L3 的直接理由。

### 2.2 理论给出的「设计张力」（trade-off）清单

| # | 张力 | A 方案 | B 方案 | 本平台选择 | 代价 |
|---|---|---|---|---|---|
| T1 | **过滤 vs 重排顺序** | 先重排后过滤（重排模型可挑到最好的） | 先过滤后重排（被滤内容永不进上下文） | **B（先过滤）** | 被滤候选不参与重排，可能误杀好内容；且当前无重排器，短期内无实际损失 |
| T2 | **RRF vs 分数加权** | RRF（只看排名，零标定） | 分数加权（保留分差，需标定系数） | **A（RRF，k=60）** | 丢失"第一名强多少"的信息 → **必须**接 rerank 补偿，当前未接（缺口） |
| T3 | **多路召回 vs 延迟** | 单通道（快、简单） | 双通道（稠密+词法，召回全） | **B（双通道）** | 两次 SQL + 一次嵌入调用；候选放大 3 倍（`CANDIDATE_MULTIPLIER`） |
| T4 | **门禁 vs 可用性** | 无门禁（总有命中，模型自信胡说） | 有门禁（"找不到"成为合法输出） | **B（0.38 阈值）** | 误拒率；文档实测"同体裁无关词"可拿 0.525 高于正例 p25 → **分数门禁原理上分不开近邻负例** |
| T5 | **分数门禁读哪个数** | 读融合分（RRF，上限 ≈0.033） | 读相关性分（余弦/ts_rank，0~1） | **B（相关性分，max(通道原始分)）** | 混用会令门禁恒不通过 → 检索永远"未找到依据"；已踩过一次并由 `RetrievalRepositoryIT.returnedScoreIsRelevanceNotRrfFusionValue` 固化 |
| T6 | **RLS 强隔离 vs 应用层灵活** | RLS 兜底（数据库强制，但不可见、不可按需放宽） | 应用层显式谓词（可读、可测、可审计） | **两者并存，正在退役 RLS** | 过渡期双写成本；R4 阶段无 DB 策略可退，最后 2 处是纯代码改动 |
| T7 | **向量列精度 vs ANN 索引能力** | `vector`（精度高，HNSW ≤2000 维） | `halfvec`（存储减半，HNSW ≤4000 维） | DDL 用 `halfvec(4096)` | **4096 > 4000 → 建不了 ANN 索引**，稠密通道退化为全表精确扫描 |
| T8 | **换模型：重建 vs 蓝绿** | 全量重建（停机） | 代际并存（`BUILDING`/`ACTIVE`/`RETIRED`，零停机） | **B（代际蓝绿）** | 复杂度：代际元数据表 + 检索侧代际过滤 + 一致性约束 |
| T9 | **全局公共语料：独立索引 vs 哨兵桶** | 全局语料单独一套索引/库 | 哨兵租户 ID 与租户语料同表同索引 | **B（哨兵桶）** | 哨兵绝不能成为会话身份（两层封堵）；复合外键/JOIN 需保持等值语义 |
| T10 | **影子对照：阻断 vs 只观测** | 影子期直接阻断违例 | 影子期只记不拦，双写期翻转 | **A（只观测）→ P2 翻转 `block-on-violation`** | 影子期违例不阻断 → 需靠观察期信号兜底 |
| T11 | **语料授权：入库即授权 vs 检索期准入** | 入库校验后即可检索 | 每次检索都校验来源/载体/版权/时效/权威 | **B（检索期准入，`ADMISSIBLE_TEMPLATE`）** | 每次检索 6 个谓词；且谓词必须与治理写侧**同源**（否则撤回动作对运维说谎） |

### 2.3 业界公认的反模式（anti-pattern）清单及判别方式

| # | 反模式 | 典型症状 | 判别方式（可执行） | 本平台状态 |
|---|---|---|---|---|
| AP-1 | **Prompt 约束代替数据层过滤** | 靠 prompt 说"不要引用答案册"，模型偶尔不听 | 检查过滤谓词是否在 SQL 里；把 prompt 约束删掉，隔离是否仍成立 | ✅ 已规避（`RetrievalPolicy.allowsRole` 在 SQL 结果集上过滤） |
| AP-2 | **门禁读错分数** | 检索永远返回"未找到依据"，但语料明明有 | 检查门禁读的是 `score`（相关性）还是 `fusionScore`（RRF）；RRF 上限 0.033 | ✅ 已踩并修复（`RetrievalOrchestrator.java:124-138` 注释详述） |
| AP-3 | **切分时把答案区混进正文** | 讲解里出现标准答案 | 检查角色判定是否基于**版面**而非模型；误判为答案的代价（泄题）远大于误判为讲解 | ✅ 已规避（`BlockRoleResolver` 版面优先，`IngestionPipeline.java:36-41` 明确"宁可过判为讲解"） |
| AP-4 | **向量维度与列类型硬编码** | 换模型后"写库时才炸" | 检查维度是否由 `EmbeddingPort.dimension()` 单一来源 + 装配期校验 | ⚠️ 部分规避（配置侧自洽校验已做；配置 vs DDL 列未校验，见 D-3） |
| AP-5 | **两代向量混算相似度** | 换模型后召回莫名变差、**且不报错** | 检查稠密检索是否有代际过滤 | ✅ 已规避（`denseSearch` 的 `index_generation` 子查询，`JdbcRetrievalRepository.java:478-506`） |
| AP-6 | **只读 source.ver_status** | 管理端"撤回载体"显示成功，内容照常被召回 | 检查检索 SELECT 是否读 `rag_document.ver_status` 与 `retrievable` | ✅ 已修复（`ADMISSIBLE_TEMPLATE` 含 4 个轴，`JdbcRetrievalRepository.java:120-180` 详述"治理动作对运维说谎"） |
| AP-7 | **版权轴写成 `!= 'RESTRICTED'`** | 登记占位态 `PENDING` 也被当作可用 | 检查是否用显式白名单（LICENSED/FAIR_USE_QUOTATION） | ✅ 已收紧（`ADMISSIBLE_TEMPLATE:176-177`） |
| AP-8 | **信任枚举序数比较权威等级** | `trust_tier >= ?` 把最不权威的放进来 | PG 枚举按**声明序**比较，`rag_trust_tier` 声明序与权威语义相反 | ✅ 已规避（用 `TrustTier.meets()` 展开为达标等级 IN 列表，注释记录 2026-10-27 线上实测踩坑） |
| AP-9 | **租户谓词两侧不一致** | BM25 侧有租户过滤、向量侧没有 → 跨租户泄露 | 检查每个通道的 WHERE 是否都含同一 `scopeTranslator.render()` 产物 | ✅ 已规避（`denseSearch`/`lexicalSearch` 均用同一 translator） |
| AP-10 | **改策略无评测** | 调阈值靠感觉，改检索策略无回归 | 检查是否存在 Recall@K/MRR 的可执行实现 + CI 门禁 | ❌ **未规避**（指标已定义、全部未实现） |
| AP-11 | **影子期就阻断** | 影子开关一开生产全红，团队直接关掉 | 检查影子期 `block-on-violation` 默认值与翻转门禁 | ✅ 已规避（默认 false，且 `reportRlsBind` 即使阻断模式也不抛） |
| AP-12 | **召回结果无溯源字段** | 模型引用了内容但查不到出处 | 检查 hit 是否带 `locator`/`edition`/`role`/`reason` | ✅ 已规避（7 字段白名单，`HttpRagRetrievalGateway.parseResult`） |
| AP-13 | **失败模式伪装成零命中** | RAG 挂了但用户看到"没找到相关内容" | 检查检索失败是否抛异常并被标为组件级 error | ✅ 已规避（`RagUnavailableException` fail-closed，`AgentChatOrchestrator.doRag` 发 `RAG_UNAVAILABLE` 事件） |

### 2.4 深度补充：三个新理论锚点（均经官方出处核实，不与 2.1 重复）

> 本节为本次「13 份报告更详细补充」新增，不改写 2.1 已有十个锚点。选择 **RRF 的原始论文**、**Dense Passage Retrieval（DPR）**、**Matryoshka / 二进制量化的规模化路径** 的原因：2.1(4) 只给了"RRF 只用排名、无需标定"的结论却未给 RRF **原始出处**；2.1(6) 讲了嵌入模型与 Matryoshka 却未给**稠密检索起点 DPR** 与**维度-索引权衡的官方边界**；而这两处恰好是本维度纠偏的两个核心——RRF k=60 与 halfvec(4096) 建不了 ANN 索引——的理论根系。三者是对既有锚点的**补强**，不是替换；每个 URL 均经本次 WebFetch 实际访问、内容支撑陈述。

#### (11) Reciprocal Rank Fusion（RRF）：只用排名、零标定的融合范式——本平台 k=60 的理论出处

- **出处（本次经 WebFetch 官方页核实）**：Cormack, G. V., Clarke, C. L. A., Buettcher, S., *"Reciprocal Rank Fusion Outperforms Condensed Ranking and Query Likelihood Models"*, **SIGIR 2009**（RRF 的原始论文）；本次同时核实了三家官方实现页，均把 RRF 作为混合检索融合的一等原语且**默认 k=60**：
  - Elasticsearch 官方 *"Reciprocal rank fusion (RRF)"*：https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html —— 官方文档原文："RRF is a method for combining multiple result sets with different relevance indicators into a single result set"、"**RRF requires no tuning, and the different relevance indicators do not have to be related to each other** to achieve high-quality results"；给出公式 `score += 1.0/(k + rank)`，且 `rank_constant`（即 k）"**Defaults to 60**"。
  - Qdrant 官方 *"Hybrid Queries"*：https://qdrant.tech/documentation/concepts/hybrid-queries/ —— 原文："RRF only looks at ranks. A is near the top of both lists, so it comes first, **even though D's BM25 score is far ahead**"；API 示例明确 `{"rrf": {"k": 60}}`，并给出"**Without an eval set: leave weights at the default (1.0, 1.0). Hand-tuned weights without measurement are unlikely to beat the default reliably**"。
  - Azure AI Search 官方 *"Hybrid search overview"*：https://learn.microsoft.com/en-us/azure/search/hybrid-search-overview —— 原文："Runs full-text search and vector search in parallel. **Merges results from each query by using Reciprocal Rank Fusion (RRF)**"，并解释 BM25（文本）与 HNSW/eKNN（向量）用不同排序函数、由 RRF 统一。
- **定义（忠于原文）**：RRF 把多路检索的**排名**而非分数融合：`score(d) = Σ_{r∈R(d)} 1/(k + rank_r(d))`（rank 从 1 起）。k 是排序常数，控制低排名结果对最终结果的影响，业界默认 60。它**不看原始分数量纲**，因此无需标定——这正是稠密余弦（0~1 有界）与 BM25（无界）混合时的关键优势。
- **对本维度的解释力**：本平台 `JdbcRetrievalRepository.java:107` 的 `RRF_K = 60.0` 与 `:590` 的 `1.0/(RRF_K + rank+1)` 是 RRF 范式的**逐字实现**；2.2 表 T2 与 D-5 记录的"RRF 丢失分差信息 → 必须接重排器"正是 RRF 的固有代价。三家官方文档的默认 k=60 **交叉印证了本平台 k=60 不是拍脑袋，而是业界默认值**——这是"红线：不动 RRF k=60"的理论支撑（MEMORY 铁律：改动须走 RAG-D08 §4 且有反向验证）。
- **边界（诚实）**：Qdrant 官方明确"DBSF（Distribution-Based Score Fusion）在**已校准的检索器上可胜过调优后的加权 RRF**"，说明 RRF 不是最优解，而是"零标定、跨语料稳定"的稳健选择。本平台选 RRF 的代价（丢失分差）真实存在，且因未接重排器（R-2）而**尚未补偿**。

#### (12) Dense Passage Retrieval（DPR）：稠密检索的起点——解释"为何必须有独立的稠密通道"

- **出处（本次经 WebFetch 官方页核实）**：Karpukhin, V., Oğuz, B., Min, S., Lewis, P., Wu, L., Edunov, S., Chen, D., Yih, W.-t., *"Dense Passage Retrieval for Open-Domain Question Answering"*, **EMNLP 2020**（arXiv:2008.08291v2）。作者群与 2.1(1) 的 RAG 论文高度重叠（Karpukhin/Lewis/Yih），是 RAG 非参数化记忆"稠密向量索引"的奠基工作。
- **定义（忠于原文）**：用**双塔（bi-encoder）**分别把 query 与 passage 编码成稠密向量，以内积/余弦相似度检索；相比 BM25 稀疏检索，DPR 在开放域 QA 的 top-20 召回上显著领先。它确立了"语义相似 ≠ 词项重叠"这一现代 RAG 稠密通道的根基。
- **对本维度的解释力**：本平台 `denseSearch`（`JdbcRetrievalRepository.java:477-524`）用 pgvector `1-(e.embedding <=> ?)` 余弦（`:492`）就是 DPR 式稠密通道的工程实现；而 2.1(4) 强调的"词法通道存在理由——二次函数 vs 反函数"恰是 DPR 式稠密检索的**已知弱点**：纯稠密会把教学上不同的术语靠得很近。这解释了为什么本平台**必须**保留 BM25/ts_rank 词法通道（D-2/T3），而不是"稠密一把梭"。
- **边界（诚实）**：DPR 是 2020 年工作，其"双塔 vs BM25"的二分已被后续 SPLADE（2.1(4)）、ColBERT、late chunking（2.1(3)）扩展。本平台**未做** query 侧学习型编码（query 与 chunk 用同一 `EmbeddingPort`，且默认还是占位模型），因此稠密通道的真实上限受嵌入模型制约——这是 R-1（真实嵌入）与 R-2（query 改写）要解决的。

#### (13) Matryoshka 表示 + 二进制量化：维度与索引能力的官方边界——halfvec(4096) 取舍的理论根据

- **出处（本次经 WebFetch 官方页核实）**：
  - Matryoshka Representation Learning：Kusupati, A. et al., **NeurIPS 2022**, arXiv:2205.13147（2.1(6) 已列，本处补充其"规模化路径"角色）。
  - **pgvector 官方 README（本次 WebFetch 核实，https://github.com/pgvector/pgvector）**：HNSW/IVFFlat 索引"Supported types are: **vector - up to 2,000 dimensions; halfvec - up to 4,000 dimensions; bit - up to 64,000 dimensions**"；FAQ 原文："**What if I want to index vectors with more than 2,000 dimensions? You can use half-precision vectors or half-precision indexing to index up to 4,000 dimensions or binary quantization to index up to 64,000 dimensions. Other options are indexing subvectors (for models that support it) or dimensionality reduction.**"
- **定义（忠于原文）**：Matryoshka 允许单一模型取前 d 维即得 d 维表示（换维度免重训）；二进制量化把向量压成 `bit`，索引维度上限从 4000 跃升到 64000。pgvector 官方明确给出"超 2000 维"的三条规模化路径：半精度（≤4000）、二进制量化（≤64000）、子向量索引（MRL）/降维。
- **对本维度的解释力**：这是本维度最重要的一次理论锚定——`01_rag_ddl.sql:960-966` 明写"halfvec(4096) 下 HNSW 刻意不存在……规模化后二选一：MRL 截断到 ≤4000 维，或二进制量化"，与 pgvector 官方 FAQ 的"indexing subvectors / binary quantization"**逐条对应**；`rag.env:44-46` 已显式 `4096/halfvec`，`VectorColumnTest` 断言 `new VectorColumn("halfvec",4096).indexable()==false`。**结论：halfvec(4096) + 无 ANN 索引是设计取舍，不是配置漂移**；规模化正道是 MRL / 二进制量化（pgvector 官方背书），**禁止硬降维**——硬降到 ≤4000 会与 `qwen3-embedding:8b` 的 4096 维输出冲突并使已入库向量失效。
- **边界（诚实）**：本平台当前语料规模（数百片段）下精确全表扫描"既快又无召回损失"（DDL 注释原话），因此**现阶段没有 ANN 索引不是缺陷**；只有当规模上一个量级、且完成 MRL/二进制量化的代际迁移后，ANN 才成为必需。R-1 原稿建议"硬降到 4000 维建 HNSW"属**已被复核纠偏**的旧结论（见顶部提示块与 P0 验收报告 §3.1），不得再按此改代码。

#### 2.4.1 新锚点 → 本平台代码锚点映射（承接 2.1）

| 理论 | 核心主张 | 本平台对应实现（锚点，本次复核） | 是继承还是背离 |
|---|---|---|---|
| RRF（SIGIR 2009 / ES·Qdrant·Azure 官方默认 k=60） | 只用排名融合、零标定、丢失分差须重排补偿 | `JdbcRetrievalRepository.java:107` RRF_K=60、`:590` 融合；`RetrievalOrchestrator.java:129` 排序键=fusionScore；无 rerank（R-2） | **继承**（k 取业界默认），但**补偿缺失** |
| DPR（EMNLP 2020） | 双塔稠密检索语义相似，纯稠密分不开教学近邻术语 | `denseSearch` `JdbcRetrievalRepository.java:477-524`（pgvector `<=>` :492）；保留词法通道 `:526-557` | **继承 + 主动补强**（双通道抵消纯稠密弱点） |
| Matryoshka + 二进制量化（NeurIPS 2022 / pgvector 官方） | 超 4000 维 ANN 靠半精度/二进制/MRL，非硬降维 | `01_rag_ddl.sql:960-966` 注释；`rag.env:44-46` 4096/halfvec；`VectorColumnTest.annIndexLimitsMatchPgvectorCapabilities` | **继承取舍**（无 ANN 是刻意），禁硬降维 |

> 结论：三个新锚点补齐了本维度"**RRF 的 k=60 从何而来、稠密通道为何不可省、halfvec(4096) 为何不建 ANN 索引**"三个最容易被误判为"拍脑袋/配置漂移"的理论根系。它们共同支撑一个判断：本平台在检索**机制选型**上是**继承业界共识**（RRF、稠密+词法双通道）并在**业务约束层**做差异化（版本/角色/可信度硬过滤）；把 RRF k=60 或 halfvec(4096) 当成"待修缺陷"是误读，二者都有官方出处背书。

---

## 三、竞品实现全景（每个主流方案一段）

### 3.1 OpenAI — Vector Stores + File Search

- **官方定位与实现机制**：托管式 RAG 基础设施。文件上传后**自动**被切分、嵌入、建索引；检索通过 `POST /vector_stores/{vector_store_id}/search` 完成。关键原语：
  - **切分策略**：`chunking_strategy` 有两态。`auto`（默认）当前使用 `max_chunk_size_tokens=800` + `chunk_overlap_tokens=400`；`static` 可自定义，`max_chunk_size_tokens` 取值范围 100–4096（默认 800），`chunk_overlap_tokens` 默认 400，且**不得超过 `max_chunk_size_tokens` 的一半**。
  - **嵌入**：向量库创建时自动嵌入。OpenAI 官方文档确认"When you add a file to a vector store it will be automatically chunked, embedded, and indexed."（<https://platform.openai.com/docs/guides/retrieval>）。
  - **检索请求体**（官方 API Reference，<https://platform.openai.com/docs/api-reference/vector-stores/search>）：`query`（string 或 string[]）、`filters`（`ComparisonFilter` {key,type,value} 或 `CompoundFilter` {filters,type}，`type` 支持 `eq/ne/gt/gte/lt/lte/in/nin`）、`max_num_results`（1–50）、`ranking_options` {`ranker`（`none`/`auto`/`default-2024-11-15`）、`score_threshold`（0–1）}、`rewrite_query`（boolean）。
  - **混合检索**：官方 Retrieval 指南明确 "When `ranking_options.hybrid_search` is provided you can also tune `hybrid_search.embedding_weight` (`rrf_embedding_weight`) and `hybrid_search.text_weight` (`rrf_text_weight`) to control how **reciprocal rank fusion** balances semantic embedding matches vs. sparse keyword matches. Increase the former to emphasize semantic similarity, increase the latter to emphasize textual overlap, and ensure at least one of the weights is greater than zero." —— 即 **RRF 是 OpenAI 官方的混合融合方式，且权重可调**。
  - **查询改写**：`rewrite_query=true` 开启，官方文档："Certain query styles yield better results, so we've provided a setting to automatically rewrite your queries for optimal performance. The rewritten query will be available in the result's `search_query` field."
  - **元数据过滤**：`filters` 按 vector store file 的 `attributes` 过滤（16 对键值，键 ≤64 字符、值 ≤512 字符或布尔/数字）。
- **官方来源 URL**：
  - <https://platform.openai.com/docs/guides/retrieval>
  - <https://platform.openai.com/docs/api-reference/vector-stores/search>
  - <https://platform.openai.com/docs/api-reference/vector-stores-files/createFile>（`chunking_strategy` 定义）
  - <https://platform.openai.com/docs/api-reference/assistants/createAssistant>（`FileSearchTool.ranking_options`）
- **优点**：
  1. **端到端零运维**——切分/嵌入/索引全托管，用户只管"上传文件 + 提问"。
  2. **混合检索是一等公民**且**权重可调**（`rrf_embedding_weight` / `rrf_text_weight`），正好命中本平台缺的那块（改进点 R-2 的直接参照）。
  3. **查询改写内建**（`rewrite_query`）。
  4. **重排内建可选**（`ranker` 的 `auto` / `default-2024-11-15`），且 `none` 可显式关闭以降延迟——"关掉重排换延迟"是产品化的开关，而非框架内部细节。
  5. **配额与过滤是 API 一等参数**（`max_num_results` 1–50、`filters` 完整比较算子集）。
- **局限/代价**：
  1. **无多租户隔离语义**——`filters` 靠调用方正确传参，服务端不推断租户；跨租户防护必须由宿主自建（本项目正是这样做的，见 4.2 决策 D-2）。
  2. **切分不可观测**——`auto` 策略的切分结果对用户不可见，无法像本平台那样把 `chunk_policy`/`chunk_policy_config` 落库留痕（RAG-D26 阶段 3.2 的能力）。
  3. **锁定效应**——数据进了 OpenAI 的 vector store，迁移成本高；本项目的语料是**版权敏感的教育内容**，托管在境外服务商的合规风险需单独评估。
  4. **中文分词不由用户控制**——`auto` 切分对中文教辅术语（"二次函数" vs "反二次函数"）的行为不可预期。
- **适用场景**：追求最快落地、可接受托管与锁定成本的通用知识库问答；对中文教辅的版本/泄题治理无内建支持。

### 3.2 LangChain / LangGraph — Retriever 组件全家桶

- **官方定位与实现机制**：不提供托管 RAG，而是提供**可组合的检索原语**。核心是 `VectorStore` 接口（`similarity_search` / `similarity_search_with_score` / `as_retriever`）与一族可叠加的 retriever：`MultiQueryRetriever`（LLM 把一个 query 改写成多视角 query 以提升召回，`MultiQueryRetriever.from_llm(retriever=..., llm=...)`）、`EnsembleRetriever`（多 retriever 按 `weights` 融合，如 `EnsembleRetriever(retrievers=[bm25, dense], weights=[0.3, 0.7])`）、`ParentDocumentRetriever`（子片段检索、返回父文档）、`ContextualCompressionRetriever`（用 `base_compressor` 压缩召回文档）、`SelfQueryRetriever`（自然语言过滤）、以及 `MultiVectorRetriever`/`MergerRetriever`/`RePhraseQueryRetriever`/`TimeWeightedVectorStoreRetriever` 等。检索架构三形态：**2-Step RAG**（检索恒在生成前，可预测、低延迟）、**Agentic RAG**（LLM 自主决定何时/如何检索）、**Hybrid RAG**（加查询增强 + 检索验证 + 答案验证）。切分侧：`RecursiveCharacterTextSplitter`（分隔符优先级递归）、`TokenTextSplitter`、`SemanticChunker`（嵌入相似度找断点）、`MarkdownHeaderTextSplitter`。
- **官方来源 URL**：<https://docs.langchain.com/oss/python/deepagents/retrieval>（三形态）、<https://python.langchain.com/docs/concepts/retrievers/>（Retriever 体系）、<https://python.langchain.com/docs/concepts/text_splitters/>（切分器）。⚠️ 诚实标注：`https://docs.langchain.com/oss/python/langchain/retrievers` 于本次核实返回 404（官方文档站已改版），Retriever 类清单依据官方 API Reference 与官方概念文档的检索结果归纳。
- **优点**：**组合性最强**——`EnsembleRetriever` 天然就是"混合检索"且权重可调，`MultiQueryRetriever` 就是"query 改写"；切分策略可选（fixed/recursive/semantic/header）；向量库可插拔；LangGraph 提供持久化与可恢复编排，检索可作为图的一个节点被检查点/HITL 包裹。
- **局限/代价**：**全是"能力零件"，不是"闭环"**——切分/索引/检索/评测都要宿主拼装与运维，组件间口径漂移（入库用 A 切分器、检索用 B 切分器）完全靠人守；**无治理语义**——版本坐标、角色（答案/讲解）、版权、权威等级无对应原语，只能在 post-processor 手写且极易漏在某个分支；**无租户隔离语义**——`as_retriever(search_kwargs={"filter": ...})` 依赖用户正确传 filter；**`EnsembleRetriever` 的权重需人工标定**（与本平台选 RRF 的理由相反）。
- **适用场景**：需要高度定制且团队愿承担组装与标定成本的流水线；本项目可借鉴的正是 `EnsembleRetriever` 与 `MultiQueryRetriever` 两种模式。

### 3.3 Google ADK — Vertex AI RAG Engine / Retrieval Tool

- **官方定位与实现机制**：两种形态。**`VertexAiRagRetrieval` 工具**（Python `google.adk.tools.retrieval.vertex_ai_rag_retrieval.VertexAiRagRetrieval`）：构造参数 `rag_resources=[rag.RagResource(rag_corpus="projects/.../ragCorpora/...")]`、`similarity_top_k`、`vector_distance_threshold`；Kotlin 侧 `VertexAiRagRetrieval(ragResources, similarityTopK, vectorDistanceThreshold)`，TS 侧 `VertexRagRetrievalTool`。**服务端 grounding**：TS API Reference 明确 "This tool operates server-side; it modifies the LLM request config to enable RAG retrieval via the `retrieval.vertexRagStore` field and **does not perform local code execution**"，`runAsync()` 的语义是"结果以 **grounding metadata** 到达"；Kotlin 侧注释同。**Vertex AI RAG Engine**（服务端）：语料（corpus）→ 向量后端（可接 Vertex AI Search）→ 检索，官方发布文给出 `rag.RagRetrievalConfig(top_k=10, ranking=rag.Ranking(llm_ranker=rag.LlmRanker(model_name=MODEL_NAME)))`——**LLM 派生的 reranker 是一等参数**。
- **官方来源 URL**：<https://google.github.io/adk-docs/integrations/vertex-ai-rag-engine>、<https://adk.dev/api-reference/typescript/classes/VertexRagRetrievalTool.html>、<https://adk.dev/tools/google-cloud/vertex-ai-rag-engine/>、<https://cloud.google.com/vertex-ai/generative-ai/docs/rag-engine/use-vertexai-search>
- **优点**：端到端托管 + **一等公民的 RAG**（corpus 与 Agent 平级）；**rerank 是配置项**（`rag.Ranking(llm_ranker=...)`）——正是本平台缺的那块；top-k 与距离阈值可配（与本平台 `min-score` 门禁同构，但语义是"距离阈值"）；**服务端 grounding 有明确安全语义**（检索在模型侧发生，不执行本地代码，减少本地注入面）。
- **局限/代价**：**强 GCP 绑定**（`rag_resources` 用 GCP 资源路径，迁移成本高）；**单 corpus 限制**（TS API Reference 明确 "only supports one corpus per `ragResources` array"；Python 侧有 "Single tool per agent limitation"）；**无多租户语义**（corpus 边界≠租户边界）；**无业务治理原语**（版本坐标/答案角色/版权状态无对应字段）。
- **适用场景**：GCP 体系内、需要托管 RAG + LLM reranker、不需要精细业务治理。

### 3.4 Microsoft AutoGen — RetrieveUserProxyAgent（已归档）

- **官方定位与实现机制**：AutoGen 0.2 时代提供 `RetrieveUserProxyAgent`，把向量检索封装成一个"会自己检索的 user proxy agent"，本质是把 RAG 做成**多 agent 对话中的一个角色**。0.4 完全重写后，检索不再是内建 agent 类型，需通过 LangChain 等外部库接入。**项目状态（已核实）**：AutoGen 于 2025-10 进入 maintenance mode（只收 bug 与安全修复，不加新特性），官方 README 引导新项目使用 **Microsoft Agent Framework**（2025-10 公开预览，2026-04 v1.0 GA，合并 AutoGen + Semantic Kernel）；至 2026-10 已被标记 archived、不再维护。社区分叉为 **AG2**（`ag2ai/ag2`）。
- **官方来源 URL**：<https://github.com/microsoft/autogen>（维护模式横幅）、<https://microsoft.github.io/autogen/stable/index.html>、<https://learn.microsoft.com/agent-framework/>（继任者）。
- **优点**：**把 RAG 建模为一个 agent 角色**，与"agent 决定何时检索"的 Agentic RAG 天然契合；多 agent 协同的可观测性——检索调用与其余 agent 的对话在同一条消息流里，便于复盘。
- **局限/代价**：**已归档**（不应用于新项目，RAG 能力随框架冻结而停滞）；**RAG 靠外部库**（LangChain），非内建闭环；无治理与多租户语义；**"把检索做成 agent"有隐性代价**——检索变成对话的一个 turn，带来额外 LLM 调用与延迟；本平台把检索做成**编排器的确定性阶段**（`AgentChatOrchestrator.doRag()`），是相反的取舍。
- **适用场景**：历史项目维护；新项目应直接看 Microsoft Agent Framework。**对本维度的价值主要是反面参照**。

### 3.5 CrewAI — Knowledge / RagTool

- **官方定位与实现机制**：两条路径。**`knowledge_sources`（一等公民式）**：可挂在 **Agent 级或 Crew 级**（Crew 级对所有 Agent 可见，Agent 级只对指定 Agent 可见）。来源类型含 `StringKnowledgeSource`/`TextFileKnowledgeSource`/`PDFKnowledgeSource`/`CSVKnowledgeSource`/`JSONKnowledgeSource` 等；切分参数在来源上配置（`chunk_size` 默认 4000、`chunk_overlap` 默认 200）；`embedder` 支持 `openai`/`google`/`azure`/`ollama`/`vertexai`/`cohere`/`voyageai`/`bedrock`/`huggingface`/`watson` 多 provider，且 **Agent 可有自己的 embedder、Crew 可有 fallback embedder**；`KnowledgeConfig(results_limit=3, score_threshold=0.35)` 控制返回量与相关度门槛；向量库经 `storage`（`KnowledgeStorage`）配置。**`RagTool`**：走 **Embedchain** 适配器，配置形如 `{"app":…, "llm":…, "embedding_model":…, "vectordb":…, "chunker": {"chunk_size":400, "chunk_overlap":100, …}}`。
- **官方来源 URL**：<https://docs.crewai.com/en/concepts/knowledge>、<https://docs.crewai.com/en/tools/rag-tool>、<https://docs.crewai.org.cn/concepts/knowledge>（中文镜像，含 `chunk_size=4000`/`chunk_overlap=200` 默认值）。
- **优点**：**知识作用域显式**（Agent 级 vs Crew 级）——本报告见到的**唯一**把"知识可见范围"作为一等建模维度的框架，对多租户/多角色有借鉴价值；嵌入器多 provider 且可按 Agent 覆盖；切分参数与门槛可配；**无独立"检索服务"概念**，知识直接进 agent 上下文，最简。
- **局限/代价**：**无治理语义**——没有版本坐标、答案角色、版权/权威等级，教辅的泄题闸门完全无处安放；**无多租户隔离**；**切分是固定长度窗口**（默认 4000 字符），不做结构/语义切分，对"答案区必须分离"的教辅语料是**结构性不适用**；`RagTool` 依赖 Embedchain 聚合层，能力受其支持矩阵限制。
- **适用场景**：通用文档问答、角色化 crew 共享知识；不适合教辅/合规敏感语料。

### 3.6 Agno — Knowledge 一等公民（对照价值最高）

- **官方定位与实现机制**：`Knowledge` 是 Agno 的核心抽象，同时承担"摄取 + 切分 + 嵌入 + 检索 + 重排"。
  - **三组件结构**（官方原文）："1. Content ingestion ... 2. Chunking and embedding ... 3. Search and retrieval"。读者覆盖 PDF/DOCX/CSV/Markdown 等。
  - **检索类型**：`SearchType.vector`（query-doc 嵌入距离）/ `SearchType.keyword`（数据库特定词法排序）/ `SearchType.hybrid`（向量 + 词法双信号）——官方表格明确 "SearchType.hybrid: Vector and lexical signals — Queries that contain concepts and specific terms"。
  - **重排**：官方 Search & Retrieval 文档："If `Knowledge` has a **reranker**, it fetches a **wider candidate pool**, reranks it, and returns the top `max_results`." —— 即**rerank 是知识库级的一等组件**，独立于检索器。
  - **查询改写**：官方文档称之为 **query transformer**，并明确举 **HyDE** 为例："A query transformer — Whether a rewritten query, such as a **HyDE** hypothetical answer, finds answers the question misses"。
  - **元数据过滤**：`knowledge.search(query, filters={"department":"hr","type":"policy"})`；`Agent(knowledge=..., knowledge_filters={...})`。
  - **自定义检索器**：`knowledge_retriever` 可整体替换默认 `Knowledge.retrieve()` 路径。
  - **Direct vs Agentic**：`knowledge.search()` 直接调；`Agent(knowledge=knowledge, search_knowledge=True)` 注册 `search_knowledge_base` 工具（**Agentic RAG 是默认**）；`add_knowledge_to_context=True` 走 Traditional RAG（每次输入都检索）。
  - **向量库**：官方称支持 **19 种**向量库（LanceDB/ChromaDB 本地 + Pinecone/Weaviate 托管）。
- **官方来源 URL**：
  - <https://docs.agno.com/knowledge/introduction>
  - <https://docs.agno.com/knowledge/concepts/search-and-retrieval/overview>
  - <https://docs.agno.com/knowledge/concepts/search-and-retrieval/hybrid-search>
  - <https://docs.agno.com/knowledge/concepts/search-and-retrieval/reranking>
  - <https://docs.agno.com/knowledge/concepts/search-and-retrieval/query-transformation>
- **优点**：
  1. **Knowledge 是真正的一等公民**——摄取、检索、重排、过滤、查询改写全部在同一个抽象下，且**每一项都有独立配置点**。
  2. **hybrid search 内建**且直接对应本平台的混合检索诉求。
  3. **reranker 内建**且明确"扩大候选池 → 重排 → 取 top-N"的三段式（与本平台 `CANDIDATE_MULTIPLIER` 的思路同构）。
  4. **HyDE 查询改写内建**——直接对应 Advanced RAG 的"检索前处理"缺口。
  5. **Knowledge 可跨 Agent/Team 共享**（"Knowledge for Teams"）。
- **局限/代价**：
  1. **检索算法本身不内建**——`SearchType.hybrid` 的具体融合算法由**底层向量库**决定（文档明说 "Search algorithms differ by vector database"），即**融合细节不可控、不可跨库一致**。本平台的 RRF 是自己实现的、跨库一致。
  2. **无业务治理原语**（版本/角色/版权/权威）。
  3. **无多租户隔离语义**——`filters` 靠调用方传参。
  4. **重排模型要自带**（Agno 提供 reranker 接口，但模型需外部准备）。
- **适用场景**：需要开箱即用的完整 RAG 能力（摄取→混合检索→重排→改写）且可接受向量库层差异的团队。**对本平台最有借鉴价值**——它的 `Knowledge` 组件划分几乎是本平台改进点 R-2/R-3 的目标形态。

### 3.7 竞品横向速查（本维度相关能力）

| 能力 | OpenAI VS+FileSearch | LangChain | Google ADK/Vertex | AutoGen | CrewAI | Agno | **本平台** |
|---|---|---|---|---|---|---|---|
| 托管摄取（切分+嵌入+索引） | ✅ 一等 | ⚠️ 组件 | ✅ 一等 | ❌ | ⚠️ 组件 | ✅ 一等 | ✅ 一等（独立服务） |
| 切分策略可配 | ✅ auto/static | ✅ 4 种+ | ⚠️ 平台内 | ❌ | ⚠️ 固定窗口 | ✅ chunker | ✅ 结构优先（版面） |
| 稠密检索 | ✅ | ✅ | ✅ | ⚠️ 靠 LangChain | ✅ | ✅ | ✅ pgvector |
| 词法/稀疏通道 | ✅（hybrid_search） | ✅ BM25 | ⚠️ | ⚠️ | ⚠️ | ✅ keyword | ✅ ts_rank + GIN |
| 混合融合方式 | ✅ RRF（权重可调） | ⚠️ 分数加权（`weights`） | ❌ 未明示 | ❌ | ❌ | ⚠️ 由向量库决定 | ✅ **RRF(k=60)，跨库一致** |
| Rerank | ✅ ranker | ✅ 组件 | ✅ LlmRanker | ❌ | ❌ | ✅ | ❌ **未实现** |
| Query 改写 / HyDE | ✅ rewrite_query | ✅ MultiQuery | ❌ | ❌ | ❌ | ✅ query transformer | ❌ **未实现** |
| 元数据过滤 | ✅ 8 算子 | ✅ | ✅ | ❌ | ❌ | ✅ | ✅（治理轴多维） |
| 分数门禁 | ✅ score_threshold | ⚠️ | ⚠️ 距离阈值 | ❌ | ✅ score_threshold | ❌ | ✅ **0.38 + 漏斗计数** |
| 版本/角色硬过滤 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ **数据层硬过滤** |
| 语料可信度准入 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ **6 轴准入谓词** |
| 多租户隔离（DB 强制） | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ **RLS + 显式谓词双写** |
| 全局公共语料桶 | ⚠️ 靠 filter 表达 | ❌ | ❌ | ❌ | ⚠️ 靠 collection | ⚠️ 靠 collection | ✅ **哨兵租户 + 复合外键** |
| 换模型零停机 | ❌ | ❌ | ❌ | ❌ | ❌ | ⚠️ | ✅ **代际蓝绿** |
| 检索质量评测 | ⚠️ 平台外 | ⚠️ LangSmith | ⚠️ | ❌ | ❌ | ❌ | ⚠️ **指标已定义，全未实现** |
| 新旧策略影子比对 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ⚠️ **影子只比对隔离，不比对策略** |

### 3.8 深度补充：新增竞品（本次 6 个官方页 WebFetch 全部核实，竞品总数 6 → 12）

> 3.1~3.6 已覆盖 6 个主流方案。为达到"竞品扩到 ≥7 个"的要求并覆盖 3.7 速查表暴露的两类空白（**索引级混合检索**与**检索评测**），本节新增 6 个竞品/参照物。**全部经本次 WebFetch 实际访问官方页面、内容支撑陈述**；每个都带可访问官方 URL。凡官方页本次未能抓取的（Pinecone/Weaviate 新 URL 404、Vespa 只返回图片、OpenAI guides 被 Cloudflare 拦截），一律在附录 F 标注"未能核实"，不臆造。

#### 3.8.1 Elasticsearch — BM25 + kNN 的 RRF 混合检索（本次核实）

- **官方定位与机制**：Elasticsearch 官方 *"Reciprocal rank fusion (RRF)"*（https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html）明确 RRF 是"combining multiple result sets with different relevance indicators into a single result set"，且"**requires no tuning**"。官方 full example 用 `rrf` retriever 组合一个 `standard` retriever（BM25/term query）与一个 `knn` retriever（向量检索）："execute the `knn` and `standard` retrievers independently ... then use the `rrf` retriever to combine the results"。参数 `rank_constant`（RRF 的 k）"**Defaults to 60**"，`rank_window_size` 控制参与融合的 top 文档数。
- **对本维度的意义**：这是"**业界默认 k 就是 60**"的最强证据之一——本平台 `RRF_K=60` 与 Elasticsearch 默认完全一致（2.4(11)）。`rank_window_size`（参与融合的候选窗口）直接对应本平台的 `topK * CANDIDATE_MULTIPLIER=3`（`JdbcRetrievalRepository.java:477,534`），说明"召回窗口大小影响融合质量"是共性认知，本平台 R-2 把候选池做成可配（默认 100）与 ES 的可调窗口同构。
- **官方 URL**：https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html ✅本次核实

#### 3.8.2 Qdrant — prefetch + RRF/DBSF 混合查询（本次核实）

- **官方定位与机制**：Qdrant 官方 *"Hybrid Queries"*（https://qdrant.tech/documentation/concepts/hybrid-queries/）以 `prefetch` 为 building block，把 dense vector、sparse vector（SPLADE++）、BM25 full-text 多路结果融合；融合算法有 `rrf` 与 `dbsf`（Distribution-Based Score Fusion），RRF 默认 `{"rrf":{}}`、可配 `k`（示例 `k:60`）与 `weights`（加权 RRF）。官方原文："**RRF only looks at ranks** ... even though D's BM25 score is far ahead"；DBSF "keeps the raw scores ... but normalizes their distributions before combining"；并明确告警"**Without an eval set: leave weights at the default (1.0,1.0). Hand-tuned weights without measurement are unlikely to beat the default reliably**"。
- **对本维度的意义**：Qdrant 的 DBSF "通道内归一后再融合"正是本平台 **R-2 阶段一"词法通道归一"** 的业界对应物；而"无评测集别手调权重"直接印证本平台 R-3（评测门禁）是 R-2（调权/归一）的**硬前置**——没有评测集就调融合权重是"没有反馈的赌博"（6.2 缺点 5 / R-6 问题陈述）。这条外部证据把 R-2/R-3 的排期依赖从"本报告主张"升级为"官方文档共识"。
- **官方 URL**：https://qdrant.tech/documentation/concepts/hybrid-queries/ ✅本次核实

#### 3.8.3 Azure AI Search — 混合检索 + 语义重排器（本次核实）

- **官方定位与机制**：Azure AI Search 官方 *"Hybrid search overview"*（https://learn.microsoft.com/en-us/azure/search/hybrid-search-overview）：hybrid search 是"a single query request configured for both full-text and vector queries"，"Runs full-text search and vector search in parallel"并"Merges results ... by using Reciprocal Rank Fusion (RRF)"；文本用 BM25、向量用 HNSW/eKNN；`queryType=semantic` 触发 **semantic ranker**（机器阅读理解式重排）。官方响应示例同时给出 `@search.score`（RRF 融合分，0.03 量级）与 `@search.rerankerScore`（重排分，2.x 量级）。
- **对本维度的意义**：这是"**排序分与门禁分必须分开**"的又一官方印证——Azure 把融合分（0.03）与重排分（2.x）显式分为两个字段，正对应本平台 `ScoredChunk.fusionScore`（RRF ≤0.033）与 `score`（相关性 0~1）的分离（DD-5/铁律）；混用会导致门禁恒不通过（2026-10-23 事故）。semantic ranker 的存在也再次说明 rerank 是 RAG 标准件（本平台 R-2 缺口）。
- **官方 URL**：https://learn.microsoft.com/en-us/azure/search/hybrid-search-overview ✅本次核实

#### 3.8.4 LlamaIndex — 嵌入/索引/查询编排与"换模型须重建索引"（本次核实）

- **官方定位与机制**：LlamaIndex 官方 Embeddings 文档（https://developers.llamaindex.ai/python/framework/module_guides/models/embeddings/）：默认用余弦相似度（"By default, LlamaIndex uses cosine similarity when comparing embeddings"），embedding 模型通过 `Settings.embed_model` 全局或 per-index 配置，支持 OpenAI/HuggingFace/LangChain 多种 provider；官方明确警告"**if you change your embedding model after indexing, you must re-index. Embeddings from different models are not interchangeable**"。
- **对本维度的意义**：这条官方警告正是本平台 D-10"代际蓝绿 + 只读 ACTIVE 代际 + 禁止两代向量混算余弦"的理论依据——LlamaIndex 要求"换模型必须重建索引"（停机式），本平台用代际蓝绿做到**零停机**（C-14 领先项）。本平台把维度从 `EmbeddingPort.dimension()` 单一来源取、装配期拒绝不符（`JdbcRetrievalRepository.java:242-250`），比 LlamaIndex 的"运行时才发现不可互换"更早拦截。
- **官方 URL**：https://developers.llamaindex.ai/python/framework/module_guides/models/embeddings/ ✅本次核实

#### 3.8.5 Ragas — 检索评测框架（本次核实）

- **官方定位与机制**：Ragas 官方文档 *"List of available metrics"*（https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/）列出 RAG 指标：检索侧 **Context Precision / Context Recall / Context Entities Recall / Noise Sensitivity**，生成侧 **Faithfulness / Response Relevancy**，以及 Nvidia 的 Context Relevance / Response Groundedness。原始论文 arXiv:2309.15217（2.1(7)，本次 WebFetch 再核实）定义其为"reference-free evaluation"框架。
- **对本维度的意义**：Ragas 是 R-3"检索质量评测体系"最成熟的现成参照——本平台 R-3 定义的 RC-01~09（Recall@K/MRR/误拒/漏拒/门禁一致性）与 Ragas 的 Context Precision/Recall、Faithfulness 高度同源。特别地，Ragas 的 **Context Precision/Recall** 正是 2.1(7) 表格里"Context Relevance"的工程细化；R-3 可选引入 `RagAsFaithfulnessEvaluator` 时，应以 Ragas 官方指标定义为准，并处理 LLM-as-judge 偏差（Zheng et al., arXiv:2306.05685）。
- **官方 URL**：https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/ ✅本次核实 ； https://arxiv.org/abs/2309.15217 ✅本次核实

#### 3.8.6 Dify — 知识库型 RAG 产品（本次核实）

- **官方定位与机制**：Dify 官方 Knowledge 文档（https://docs.dify.ai/en/cloud/use-dify/knowledge/overview，Last modified 2026-11-11）区分两类知识库：**Classic**（自己控制 chunk 模式/长度、索引方式、检索方式、Top-K、可选 score threshold，甚至可用 Knowledge Pipeline 自建整条处理链）与 **Agent**（自动组织与索引，支持 Fast/Deep/Research 三档 retrieval depth、内置质量检查、golden questions/evaluations/bad cases）。官方原文："the most relevant passages are found and handed to the model along with it"。
- **对本维度的意义**：Dify 是"**检索质量运营**"的对照物——其"golden questions + evaluations + bad cases + 追踪 retrieval misses"正是本平台 R-3（评测集）+ R-7（审计反哺评测集）+ R-6（影子报表）想达到的产品化闭环。本平台在数据层硬过滤（版本/角色/版权）上远超 Dify，但在"评测/运营可视化"上是 Dify 更成熟——这是 6.2 缺点 3（评测全未实现）的产品级旁证。
- **官方 URL**：https://docs.dify.ai/en/cloud/use-dify/knowledge/overview ✅本次核实

#### 3.8.7 Anthropic — retrieval 作为 augmentation 与"workflow vs agent"（本次核实）

- **官方定位与机制**：Anthropic 官方 *"Building effective agents"*（https://www.anthropic.com/engineering/building-effective-agents）原文："The basic building block of agentic systems is an LLM enhanced with augmentations such as **retrieval**, tools, and memory"；并明确"**Workflows** are systems where LLMs and tools are orchestrated through predefined code paths" vs "**Agents** ... LLMs dynamically direct their own processes and tool usage"。
- **对本维度的意义**：这为 5.4 分歧 3（"检索作为编排器确定性阶段 vs 可被模型调用的工具"）提供了官方术语锚点——本平台把检索做成 `AgentChatOrchestrator.doRag()` 的确定性阶段，属于 Anthropic 定义的 **workflow**（predefined code path），而非 agentic 自主检索。这不是"落后"，而是与本平台维度 1"受控运行时"一致的**有意哲学选择**；Anthropic 官方也建议"optimizing single LLM calls with retrieval ... is usually enough"，支持"能不用 agentic 就不用"的克制立场。
- **官方 URL**：https://www.anthropic.com/engineering/building-effective-agents ✅本次核实

> **新增竞品小结**：6 个新增（Elasticsearch / Qdrant / Azure AI Search / LlamaIndex / Ragas / Dify）+ 1 个理论参照（Anthropic）= 竞品/参照总数 6 → 13（含 Anthropic 作为哲学参照）。最值得本平台借鉴的三条：① Qdrant"无评测集别手调融合权重" → 坐实 R-3 是 R-2 的硬前置；② Azure "融合分与重排分分字段" → 印证 DD-5 门禁/排序分分离；③ Dify "golden questions + bad cases 运营" → 产品级评测闭环参照。**未能核实项**（诚实标注）：Pinecone 多租户页（`docs.pinecone.io/guides/index-data/multitenancy`）与 Weaviate 多租户页（`docs.weaviate.io/weaviate/managing-collections/multi-tenancy`）本次 WebFetch 均 404，Vespa retrieval 页只返回图片、OpenAI retrieval guides 被 Cloudflare 拦截——这些 URL 在附录 B 中保留但标注"本次未能逐字核实"，不作为新增竞品结论的依据。

---

## 四、我们的实现（源码级解剖）

### 4.1 架构与数据流

#### 4.1.1 部署拓扑：两个独立 Maven reactor

| 维度 | platform | rag |
|---|---|---|
| 根目录 | `platform/`（14 个模块） | `rag/`（10 个模块） |
| 进程 | 主平台（8080） | RAG 服务（8081，`rag-bootstrap`）+ Worker（`rag-worker`） |
| schema | `public` | `rag`（独立 schema，`search_path TO rag, public`） |
| 应用角色 | `app_agent`（NOBYPASSRLS） | `rag_app`（NOBYPASSRLS）+ `rag_ops`（BYPASSRLS，**独立连接池**） |
| 调用方向 | → rag（HTTP） | ← platform（单向调用约定，`platform` 主代码**零**引用 `cn.studykid.rag`） |

**证据**：`rag-bootstrap/application.yml:26`（`SET search_path TO rag, public`）、`:16-18`（`RAG_PG_URL`/`rag_app`）、`:85-93`（`rag.ops.datasource` 独立运维池，`max-pool-size: 2`）；`RagScopeFilter.java:22-27`（"RAG 是**独立 reactor**（`rag-core` 编译期依赖仅 JSpecify，由 `RagCorePurityTest` 钉死），platform 主代码也**零**引用 `cn.studykid.rag`（单向调用约定：platform → RAG 走 HTTP）"）

#### 4.1.2 摄取链路（写入侧）

```
上传 PDF ──▶ RagManagementController ──▶ rag_access_audit(action='INGEST')
              ▼ rag_ingest_job(PENDING)
   IngestJobConsumer.claimNext()  ← 逐租户显式清单(rag.indexer.tenants) + FOR UPDATE SKIP LOCKED
              ▼ IngestJobOrchestrator.run()
   ① PARSING  IngestionPipeline.ingest()
        解析 DocumentParserPort：DoclingDocumentParserPort(生产) / MineruResponseMapper /
          DoclingPremiseBackend / MineruPremiseBackend / PaddleStructureParseBackend /
          LocalDeterministicDocumentParserPort(占位，受 LocalPortGate 闸门)
        归一化 PremiseDocument.fromParsed(恒等包装) → 切分 BlockChunker SPI
          (HeadingHierarchyChunker 结构优先 / HybridChunkerClient 外部服务)
        角色判定 BlockRoleResolver(版面优先，非模型) + SectionPathResolver + ChunkIdentifiers
        留痕 chunkPolicyTrace() / chunkPolicyConfigTrace()
   ② CHUNKING 逐 chunk embedWithRetry(text)
        → saveChunkAndEmbedding → rag_chunk + rag_chunk_embedding(同事务)
   ③ INDEXING 逐 chunk updateJiebaTokens(ChineseTokenizer) → rag_chunk.text_jieba
        (GIN: to_tsvector('simple', text_jieba))
   ④ markDocumentParseStatus("CHUNKED") + markSucceeded(chunkCount, parserVersion,
        embedModelVersion, chunkPolicy, chunkPolicyConfig)
```

**关键落点**：

| 数据 | 落库 | DDL 锚点 |
|---|---|---|
| 片段正文 | `rag_chunk(text, role, page_number, section_path, exercise_no, char_start, char_end, kp_ids, content_hash, text_jieba, scope)` | `01_rag_ddl.sql:330-360` |
| 片段向量 | `rag_chunk_embedding(chunk_id, tenant_id, index_generation, embed_model, embedding halfvec(4096), scope)` | `01_rag_ddl.sql:384-393` |
| 版本坐标 | `rag_edition(subject, grade, term, publisher, revision_code)` | `01_rag_ddl.sql:497` |
| 词法索引 | `GIN (to_tsvector('simple', text_jieba))` | `01_rag_ddl.sql:973` |
| 代际元数据 | `rag_index_generation(tenant_id, generation, embed_model, dimension, status, indexed_chunks, total_chunks)` | `09_v1_0_3_index_generation_alter.sql:33-62` |

**摄取状态机**：`rag_ingest_job.status` 推进 `PARSING → CHUNKING → EMBEDDING → INDEXING → SUCCEEDED`，失败 `FAILED` 且 `error_detail` 可读（`IngestJobOrchestrator.java:147-152`）。

#### 4.1.3 检索链路（读取侧）

```
POST /api/v1/retrieval   (X-Tenant-Id + Authorization: Bearer)
  │
  ├─ RagJwtAuthFilter.doFilter()
  │    ├ /actuator/health 放行；缺/坏令牌/过期/aud 不符 → 401
  │    ├ 租户域 tenant_id claim ≠ X-Tenant-Id → 403
  │    └ 写 request attribute：DOMAIN / PRINCIPAL / ROLES / PRINCIPAL_TYPE / TICKET_SCOPE
  │
  ├─ RagRetrievalController.retrieve()
  │    ├ requireTenant(X-Tenant-Id)：缺/非 UUID/是哨兵 → 400
  │    ├ toCoordinate(edition)：展示名 → 领域枚举，未知值 → 400
  │    ├ toScope(scopeMode)：null → STRICT（最严档）
  │    └ catch EmbeddingUnavailableException → 503（可执行提示，绝不静默退化为纯词法）
  │
  ├─ JdbcTenantScopedRetrieval.retrieve()   ★ 单事务
  │    ├ set_config('app.tenant_id', ?, true)  ← 必须在事务内、业务 SQL 前
  │    ├ scope = new TenantWithGlobal(tenantId)  ← 入口层判定，逐层透传
  │    ├ RetrievalOrchestrator.retrieve(scope, request)
  │    └ INSERT rag_access_audit(action='RETRIEVE')  ← 同事务！审计失败 ⇒ 整次检索回滚
  │
  └─ RetrievalOrchestrator.retrieve()
       ① resolveKnowledgePoints(scope, query, subject)
       ② candidates = retrievalPort.search(scope, request)
            ├ denseSearch:  embed(query) → pgvector `1 - (e.embedding <=> ?)` + 代际过滤
            ├ lexicalSearch: ChineseTokenizer.tokenizeToList(query) → to_tsquery('simple', ?)
            │                → ts_rank(to_tsvector('simple', text_jieba), ?)
            └ RRF 融合：fused[id] += 1/(60 + rank)；relevance[id] = max(通道原始分)
       ③ 版本硬过滤：request.editionScope().admits(chunk.edition())
       ④ 角色硬过滤：RetrievalPolicy.allowsRole(chunk.role(), mode)   ← 泄题闸门
       ⑤ 知识点补全：findByKnowledgePoint + findPrerequisites（补全分数 0.5）
       ⑥ 排序：按 fusionScore 降序 + 版本惩罚（乘法，作用在融合分上）
       ⑦ 分数门禁：score() >= minScore（0.38）  ← 相关性分，非 RRF 分
       ⑧ 漏斗计数：RetrievalFunnel(5 段)
```

**平台侧接收与注入**：

```
HttpRagRetrievalGateway.retrieve(tenantId, bearerToken, query, topK)
  ├ topK 校验：1..50，否则抛 RagUnavailableException
  ├ 请求体：{query, mode='EXPLAIN', edition{五轴中性占位}, scopeMode='CROSS_EDITION',
  │          knowledgePointId=null, subject=null, grade=null, topK}
  ├ Header：Authorization: Bearer <已验签令牌> + X-Tenant-Id
  ├ HttpClient：connectTimeout=3s, requestTimeout=20s, followRedirects=NEVER
  ├ 响应体读取期封顶 512KB（buffering，超限抛 IOException 而非先读进内存）
  ├ 非 2xx → 抛（不回显正文，只给状态码，避免把语料带进日志）
  └ parseResult：只取 7 字段（chunkId/text/score/locator/edition/role/reason）
                 + versionMismatchDetected / answerContentSuppressed / primaryEdition
                 chunkId 或 text 空白 ⇒ 丢弃该条

AgentChatOrchestrator.doRag()
  ├ catch RagUnavailableException ⇒ 发 RAG_UNAVAILABLE 事件 + 组件级 error + 返回 null
  │   （绝不伪造命中，也不静默当零命中）
  ├ applyRagQuota(RagHitQuotaPort)：按 token 配额裁剪命中
  ├ injected = 真正送进模型的载荷非空（而非"检索到命中"）
  └ RAG_GUARD.wrapToolResult("rag", payload)  ← 不可信围栏（维度 6/7 耦合）
```

#### 4.1.4 影子链路（隔离对照，非策略对照）

```
RAG_SHADOW_ENABLED=true → rag.shadow.enabled=true
  → RagShadowConfiguration 条件装配 RagShadowCollector（@ConditionalOnProperty）
  → collector.attach() ⇒ RagShadow.INSTANCE = collector
  → 检索读面采集点 RagShadow.collect("retrieval")：
       JdbcRetrievalRepository.search()（:273） / loadByIds()（:417）
  → RagShadowReporter.shouldEvaluate(resourceType, null)  ← 采样决策
  → recordShadowSample / reportRlsBind / reportMismatch / reportTaintViolation
  → InMemoryRagShadowMetrics 计数
  → snapshot: ragShadow[samples=.. rlsBind=.. mismatches=.. taint=.. rlsDependent=..]
  → block-on-violation=true 时，mismatch / taint 抛 RagShadowViolationException（P2 双写期）
```

**关键事实**：影子的比对对象是**「显式谓词渲染的结果」vs「RLS 在场时的结果」**（隔离对偶安全证据），**不是**「新旧检索策略」。`reportRlsBind` 只计数、**即使阻断模式也不抛**——它是迁移清单条目而非违例（`RagShadowReporter.java:45-53`）。

### 4.2 关键设计决策逐条剖析

#### D-1：RAG 独立成服务，platform 只做"受控通道 + 白名单 + fail-closed"

- **决策**：platform 与 rag 是两个独立 Maven reactor / 进程；跨进程唯一通道是 `POST /api/v1/retrieval`，platform 侧实现为 `HttpRagRetrievalGateway`（`platform/agent-infrastructure/.../rag/HttpRagRetrievalGateway.java:57`）。
- **为什么**：(1) 语料链的权限模型（`rag_app` vs `rag_ops`、哨兵桶、版本坐标）与主平台完全不同，混在一个进程里会让两套授权模型互相污染；(2) 语料链的发布节奏（按册/按学期）远慢于 Agent 运行时，独立部署可独立伸缩；(3) 单向调用约定（platform → rag）让依赖方向单向，杜绝循环依赖。
- **代价**：(1) 跨进程丧失事务上下文——主平台事务里的 `app.tenant_id` **不会随之而来**，必须在 rag 侧重建（`JdbcTenantScopedRetrieval` 用 `TransactionTemplate` 显式重建，`RagRetrievalController.java:57-62` 详述"若不在此重建租户上下文，RLS 会把全部行过滤掉，表现为『检索永远返回空』且不报任何错"）；(2) 多一跳网络延迟与一个失败点。
- **证据锚点**：`HttpRagRetrievalGateway.java:27-31`（"2026-11-10 全仓核实：两个 Maven reactor 彼此独立，platform 侧原先**没有任何**指向 rag 的 HTTP 客户端"）；`RagScopeFilter.java:22-27`（单向调用约定）。

#### D-2：租户谓词在应用层显式化，且 BM25 与向量两侧同源

- **决策**：检索读面的租户谓词不再依赖会话变量 `rag_current_tenant()`，改由 `RagSqlScopeTranslator` 把 `RagScopeFilter`（密封接口，4 个 case）翻译成 SQL 谓词 + 绑定参数，**两处通道（稠密/词法）用同一个 translator 实例**。
- **为什么**：会话变量未绑定 ⇒ `IN (NULL, 哨兵)` 恒不匹配 ⇒ **租户语料静默消失**（不报错、检索恒空、排查成本极高）。同时，RLS 读策略放开全局后，若应用层仍写 `tenant_id = 本租户`，全局语料会被这层 AND **静默滤空**——实测同一份数据同一会话，老写法返回 1 行、新写法返回 2 行（`scripts/rag-corpus-scope-counterproof.sh` 的 P1-4/P1-1）。`JdbcRetrievalRepository.java:79-86` 把这条称为"旧注释这么写过，是错的，且错得很危险"。
- **代价**：多一层抽象与一次翻译；换来"谓词形态单点产出"——这是 4 处 SELECT + 6 个作用域判定点的唯一出口。
- **证据锚点**：`RagSqlScopeTranslator.java:17-36`（渲染对照表：4 作用域 → 4 谓词形态 → 与现役 RLS 策略逐字等价）、`:102-121`（列限定符白名单 + `?::uuid` 显式 cast + `None` → 恒假 `1 = 0`）；`JdbcRetrievalRepository.java:489`（dense）、`:535`（lexical）——同一 translator 渲染。
- ⚠️ **注意**：这是维度 2 的 `rlsDependent → 0` 迁移在 RAG 侧的**关键落点**。M3（谓词显式化）已完成，M4（RLS 退役）**尚未开始**：`RlsDependencyProbe.EXPECTED` 当前仍是 `{R1:6, R2:6, R3:16, CENTRAL:2}` = **30**，`deploy/sql/rag/14`~`17` 四批 SQL 全部标注 `⛔DRAFT / 禁止应用`。

#### D-3：向量列的"类型 + 维度"显式建模，装配期校验

- **决策**：向量列形态抽成 `VectorColumn` record（`type` 白名单 `vector`/`halfvec` + `dimension`），维度**取自 `EmbeddingPort.dimension()`**（唯一权威源），类型取自 `rag.embedding.vector-type` 配置；两者在 `JdbcRetrievalRepository` 构造期比对，不符直接 `IllegalStateException` 拒绝装配。
- **为什么**：此前代码把 `vector(1024)` 当常量，换大维度模型的表现是"写库时才炸"——索引流水线逐条失败，而查询向量维度不符只会被 PG 拒绝，错误信息离根因很远。抽成 record 后，"模型换维度"与"列迁维度"两处在启动时就是同一个数字。
- **代价**：换模型时必须"改配置 + 迁列 + 全量重建索引"三件事同步，缺一不可（这是有意的摩擦）。
- **证据锚点**：`VectorColumn.java:5-22`（问题陈述）、`:48-57`（ANN 可行性：pgvector 0.8.x 下 `vector` ≤2000 维、`halfvec` ≤4000 维）、`:65-76`（`requireDimension` 可读错误）；`RagInfrastructureConfig.java:277-292`（维度单一权威源）；`JdbcRetrievalRepository.java:242-250`（装配期拒绝）。
- ⚠️ **真实缺口（复核发现）**：DDL 终态列类型是 `public.halfvec(4096)`（`01_rag_ddl.sql:389`），而 `application.yml` 默认是 `dimension: 1024` + `vector-type: vector`（`:103,:109`）。两处不一致，且 `VectorColumn` 的校验是**配置侧自洽**（配置 vs 端口），**不是配置侧 vs 数据库实际列**——因此它能挡"模型换维度"，挡不住"配置与 DDL 不同步"。同时 `halfvec(4096)` **超出 pgvector HNSW 的 4000 维上限**，故 `01_rag_ddl.sql:960-966` 明确"本索引在 halfvec(4096) 下**刻意不存在**" ⇒ 稠密通道退化为全表精确扫描。

#### D-4：检索编排的七步顺序不可调换（版本/角色过滤必须在重排之前）

- **决策**：`RetrievalOrchestrator.retrieve()` 严格按 ①知识点解析 → ②多路召回 → ③版本过滤 → ④角色过滤 → ⑤知识点补全 → ⑥排序 → ⑦分数门禁 执行。
- **为什么**：(a) 重排模型只懂相关性、**不知道**哪些内容是答案册——若先重排后过滤，被滤内容**已经进入过上下文**（"闸门就形同虚设"）；(b) 知识点补全也不能绕过泄题闸门（补全同样受角色过滤约束）；(c) 分数门禁读的是**相关性分**（余弦/ts_rank，0~1），不是**融合分**（RRF，上限 ≈0.033）——混用会让门禁恒不通过，检索永远返回"未找到依据"。
- **代价**：(a) 被滤候选不参与重排，可能误杀本该进 top-N 的好内容——`RAG-D05 §2.2` 明确接受此代价，理由是"过滤后候选仍足够（召回 100 条，过滤后通常仍 > 30 条）"，但**该前提目前无实测支撑**（无 100 条召回配置）；(b) 当前**无重排器**，所以这条约束尚未产生实际成本（见 D-5）。
- **证据锚点**：`RetrievalOrchestrator.java:24-43`（编排顺序的七步图与两条"为什么"）；`:35-38`（过滤必须在重排前）；`:40-43`（门禁必须有）；`:98-122`（③④⑤ 的具体实现）；`:124-138`（排序键 ≠ 门禁键 + 两处量纲说明）；`RAG-D05.md:24-37`（七步表与"若 ③④ 放到 ⑥ 之后会怎样"）。

#### D-5：选择 RRF 而非分数加权

- **决策**：多路召回的融合用 **RRF**（`fused[id] += 1/(60 + rank)`），**相关性分另记**（`relevance[id] = max(各通道原始分)`）。
- **为什么**：(a) 稠密余弦与 `ts_rank` 量纲不同、分布不同，分数加权需人工标定相对系数，而该系数随语料变化；RRF 只用排名，跨语料稳定（`RAG-D05 §2.1`）。(b) RRF 的固有代价是丢失"第一名比第二名强多少"的信息，因此**必须**接重排器补偿——**这个补偿当前缺失**。
- **代价**：(a) 无重排器 ⇒ RRF 的信息损失无处补偿；(b) 排序键（RRF）与门禁键（相关性）必须分开维护——这曾是一次真实事故（2026-10-23 门禁恒不通过），现已由 `RetrievalRepositoryIT.returnedScoreIsRelevanceNotRrfFusionValue()` 钉死。
- **证据锚点**：`JdbcRetrievalRepository.java:106-110`（`RRF_K=60`、`CANDIDATE_MULTIPLIER=3`）、`:277-296`（融合与相关性分离）、`:585-599`（`accumulate` 与"相关性分取各通道最高值"注释）；`RetrievalOrchestrator.java:124-138`。

#### D-6：分数门禁必须存在，且阈值须由评测集标定

- **决策**：`min-score` 默认 **0.38**（`rag-bootstrap/application.yml:133`），阈值作用在相关性分上；低于阈值判为"未找到依据"，是**合法业务结果**。
- **为什么**：没有门禁时语义检索**总会**返回"最不相似的那一条"，模型随后基于这条弱相关内容编答案——这是 RAG 系统"自信地胡说"的主要来源。门禁让"找不到"成为正当、系统的输出。
- **阈值来历（诚实标注）**：`application.yml:127-132` 记载 0.38 = 2026-10-28 用真实嵌入模型（`qwen3-embedding:8b`）+ 沪教版两册语料标定的结果（正例命中中位 0.504、跨领域负对照中位 0.339，p75=0.366），"0.38 是『砍掉 24/27 条跨领域噪声且正例保留率仍是 100%』的第一个阈值"。**样本量极小（27 条）**，属指示性标定。`RAG-D05 §6.2` 同时记录了未解决的缺陷："同体裁的无关查询能拿到 0.525，高于正例的 p25（0.465）——**分数门禁在原理上分不开这一类**，需要重排或知识点结构化过滤"；且"纯词法命中（实测 0.068 量级）**永远过不了 0.38**——词法通道目前只能影响 RRF 排序，不能让它独立入选"。
- **代价**：误拒率不可测（RC-07 未实现）；近邻负例（0.525）压不住。
- **证据锚点**：`application.yml:124-137`；`RetrievalOrchestrator.java:132-138`；`RAG-D05.md:186-205`。

#### D-7：可信度准入谓词单一真源（6 轴）

- **决策**：`ADMISSIBLE_TEMPLATE`（`JdbcRetrievalRepository.java:172-180`）作为检索准入的**唯一真源**，6 处 SELECT 共用；包含：来源 `VERIFIED`、载体 `VERIFIED`、载体 `retrievable`、来源/载体版权 ∈ {`LICENSED`,`FAIR_USE_QUOTATION`}、校验有效期未过期、权威等级达标列表。
- **为什么**：(a) 载体级撤回在检索侧**曾经完全无效**——6 处 SELECT 只过滤 `src.ver_status='VERIFIED'`，从不读 `rag_document.ver_status` 与 `rag_document.retrievable`，而后两列正是管理端"撤回某个载体"所写的。结果是"撤回动作看起来成功，内容却照常被召回、照常被引用、照常进入答案"——**治理动作对运维说谎**。(b) 版权轴旧谓词只写 `<> 'RESTRICTED'`，于是登记占位态 `PENDING` 也被当作"可用"，与管理面 `licensePermitsRetrieval()` 的"PENDING 不放行"口径直接矛盾。(c) 权威等级**绝不能**写 `trust_tier >= ?`——PG 枚举按**声明序**比较，而 `rag_trust_tier` 声明序（`OFFICIAL_PUBLISHER` → `UNVERIFIED`）与权威语义**相反**，序数上 `UNVERIFIED` 最大却最不权威（2026-10-27 线上 RDS 实测踩坑，`>=` 写反导致全部正常语料被滤空）。故构造期用 `TrustTier.meets()` 展开为达标等级 IN 列表。
- **代价**：每次检索 6 个谓词 + 一个 `make_interval` 表达式；谓词必须与治理写侧同源，否则再次出现"撤回无效"。
- **证据锚点**：`JdbcRetrievalRepository.java:120-180`（四轴逐条说明 + 两次实测记录）、`:255-266`（构造期展开达标等级）、`:182-183`（"构造期定格"——门槛是部署级策略，不是每查询可变的输入）。

#### D-8：检索期留痕与检索同事务

- **决策**：`JdbcTenantScopedRetrieval.retrieve()` 在**同一个事务**里 `INSERT INTO rag_access_audit (... action='RETRIEVE')`，`principal_id` 记 NULL。
- **为什么**：(a) 与检索同事务 ⇒ 审计写失败则整次检索回滚，**不允许"检索成功但无痕迹"**——那会让报表少算一天，且治理上留下不可追溯的召回行为；(b) 检索由系统发起（platform 工具调用），没有人类 principal，`principal_id` 记 NULL 是"对『系统动作本就没有人类责任人』的诚实表达"（V5 迁移已把该列放宽为可空）。
- **代价**：审计写失败 = 检索失败（可用性换可追溯性，在这个场景下是划算的）。
- **证据锚点**：`JdbcTenantScopedRetrieval.java:55-67`。

#### D-9：全局公共语料用哨兵租户 ID 承载

- **决策**：`RagScope.GLOBAL_TENANT_ID = "00000000-0000-0000-0000-000000000000"`（nil UUID），全局行的 `tenant_id` 取该值，语义由 `scope` 判别列（`PLATFORM`/`TENANT`）自证；检索谓词恒为 `tenant_id IN (本租户, 哨兵)`。
- **为什么**：(a) 语料链 `source → edition → document → chunk → embedding` 全靠 `(tenant_id, 父id)` 复合外键维系，`rag_knowledge_prerequisite` 的主键含 `tenant_id`。若放开为可空，会同时踩三处：复合外键按 SQL 标准 MATCH SIMPLE **任一列为 NULL 即不校验**（全局片段可指向不存在的载体且完全静默）；等值 JOIN `c.tenant_id = ed.tenant_id` 两侧都是 NULL 时结果为 NULL 而非 true（全局语料**一行也召不到**）；主键列不能为空（PG 直接拒绝 `DROP NOT NULL`）。哨兵表示法让三处一律不变。(b) nil UUID 与真实租户 ID（`gen_random_uuid()`）碰撞概率为 0，且一眼可辨。
- **代价（铁律）**：**哨兵绝不能成为会话身份**——`rag_current_tenant()` 遇哨兵直接 RAISE 42501（DB 层封堵），应用层再挡一次（`RagScopeFilter.TenantOnly`/`TenantWithGlobal` 构造器拒绝、`RagRetrievalController.requireTenant` 返回 400、`ChunkIndexer.indexGlobalCorpus` 走独立运维通道）。
- **证据锚点**：`RagScope.java:10-42`（三处踩坑 + 哨兵纪律）；`RagScopeFilter.java:29-32`（"哨兵桶是平台全局语料，只能经 GlobalOnly 口径显式访问"）；`RagSqlScopeTranslator.java:111-122`（4 作用域 → 4 谓词）。

#### D-10：换嵌入模型走代际蓝绿，检索只读 ACTIVE 代际

- **决策**：新增 `rag_index_generation`（状态机 `BUILDING`/`ACTIVE`/`RETIRED`），`rag_chunk_embedding` 主键改为 `(tenant_id, chunk_id, index_generation)` 使同一片段的多个代际向量并存；稠密检索用子查询只取该租户的 ACTIVE 代际。
- **为什么**：换模型必须零停机——旧代际继续服务，新代际后台建好再切。改造前 `index_generation` 列**写死为 1**、向量表主键是 `(chunk_id)`，一个片段只能存一套向量 ⇒ **物理上无法双代际共存**。检索侧若把两个不可比的向量空间混在一起算余弦相似度，会**静默产出错误的相似度，且没有任何报错**。
- **代价**：检索 SQL 多一个代际子查询（每个租户可能处于不同代际，故用按行解析的子查询而非单一参数）；无代际记录时退回"不过滤"（兼容迁移前空库）。
- **证据锚点**：`JdbcRetrievalRepository.java:478-506`（代际过滤 + 两条"为什么"）；`09_v1_0_3_index_generation_alter.sql:10-28`（"为什么必须改主键而不是加张新表"）。

#### D-11：影子模式默认关闭、只观测不阻断、可逆

- **决策**：`RAG_SHADOW_ENABLED` 默认 `false`；关闭 ⇒ `RagShadow.INSTANCE == null` ⇒ 全部采集点 no-op，**零行为变更**；开启后 P1 阶段 `block-on-violation=false` 只记日志+计数，P2 翻 `true` 才抛 `RagShadowViolationException`。回滚 = 关开关即回（RLS 兜底仍在场）。
- **为什么**：影子必须**可逆且零风险**，否则团队在第一次误报时就会把它关掉，迁移就永远拿不到证据。
- **代价**：影子期违例不阻断 ⇒ 需靠观察期信号兜底；当前 **M2 观察期尚未有真实流量**（MEMORY 记载"M2 影子 infra 已开 `RAG_SHADOW_ENABLED=true`（差真实流量）"）。
- **证据锚点**：`RagShadowConfiguration.java:8-19`；`RagShadowReporter.java:16-20`（阶段语义）；`RagShadow.java:34-43`（静态门面，未注册时全 no-op）；`RagShadow.java:45-54`（`reportRlsBind` 只计数）；`rag-bootstrap/application.yml:50-54`。

#### D-12：响应白名单七字段 + 畸形命中丢弃

- **决策**：`HttpRagRetrievalGateway.parseResult` 只取 7 字段；`chunkId` 或 `text` **缺失或空白**即丢弃该条。
- **为什么**：(a) 白名单——不把 rag 的 channel/funnel/prompt 透给前端（也不让远端字段影响平台逻辑）；(b) 空白命中丢弃——只判 null 会让 `chunkId=""` 的空命中混进不可信上下文，而前端会把空 id 当合法命中渲染，形成"有命中源"的假象。
- **代价**：跨进程后 rag 侧的漏斗计数（`RetrievalFunnel`）**不透出**——目前 7 字段里没有 funnel 字段，漏斗数据出不了进程（见 5.3 盲区）。
- **证据锚点**：`HttpRagRetrievalGateway.java:175-221`；`:53-55`（白名单的显式说明）。

### 4.3 关键证据表

路径缩写：`P-INF`=`platform/agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure`、`P-PORT`=`platform/agent-ports/src/main/java/cn/studykid/multiagent/ports`、`P-WORK`=`platform/agent-worker/src/main/java/cn/studykid/multiagent/worker`；`R-CORE`=`rag/rag-core/src/main/java/cn/studykid/rag/core`、`R-APP`=`rag/rag-application/src/main/java/cn/studykid/rag/application`、`R-PERS`=`rag/rag-persistence/src/main/java/cn/studykid/rag/infrastructure`、`R-API`=`rag/rag-api/src/main/java/cn/studykid/rag/api`、`R-BOOT`=`rag/rag-bootstrap/src/main/java/cn/studykid/rag/bootstrap`、`R-INFRA`=`rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure`、`R-WORK`=`rag/rag-worker/src/main/java/cn/studykid/rag/worker`、`R-PREM`=`rag/rag-premise/src/main/java/cn/studykid/rag/premise`。

| # | 类 / 方法 / 表 / 配置 | 路径:行号 | 说明 |
|---|---|---|---|
| E-01 | `HttpRagRetrievalGateway.retrieve` / `.parseResult` / `.buildRequest` | `P-INF/rag/HttpRagRetrievalGateway.java:103-149, 175-221, 152-173` | 唯一 platform→rag 通道；3s/20s 超时、512KB 上限、不跟随重定向；7 字段白名单 + 畸形命中丢弃；五轴中性占位 + `mode=EXPLAIN` + `scopeMode=CROSS_EDITION` |
| E-02 | `RagRetrievalPort` / `RagResult` | `P-PORT/agentchat/RagRetrievalPort.java:28-52` | 端口 + 不可用异常语义 |
| E-03 | `AgentChatOrchestrator.doRag` | `P-WORK/agentchat/AgentChatOrchestrator.java:511-564` | 组件级 fail-soft + 配额裁剪 + 围栏注入 |
| E-04 | `RetrievalOrchestrator.retrieve` | `R-APP/retrieval/RetrievalOrchestrator.java:80-170`（排序/门禁分离见 `:124-138`） | 七步编排 + 漏斗计数；排序键 ≠ 门禁键 |
| E-05 | `JdbcRetrievalRepository.search` / `.denseSearch` / `.lexicalSearch` | `R-PERS/persistence/JdbcRetrievalRepository.java:269-304, 472-524, 526-557` | 双通道召回 + RRF 融合 + 契约内过滤；pgvector 余弦 + 代际过滤；`ts_rank` + 中文分词 |
| E-06 | `ADMISSIBLE_TEMPLATE` | 同上 `:172-180` | 检索准入 6 轴单一真源 |
| E-07 | `VectorColumn` | `R-PERS/persistence/VectorColumn.java:23-82` | 向量列类型/维度显式建模 + ANN 可行性判定 |
| E-08 | `RagSqlScopeTranslator.render` / `RagSqlFragment` | `R-PERS/persistence/scope/RagSqlScopeTranslator.java:95-123` / `RagSqlFragment.java` | 作用域 → SQL 谓词唯一出口；谓词文本 + 绑定参数对 |
| E-09 | `RagScopeFilter` / `RagScope.GLOBAL_TENANT_ID` | `R-CORE/scope/RagScopeFilter.java:34-135` / `RagScope.java:46` | 4 作用域密封接口 + 哨兵拒绝；哨兵 = nil UUID |
| E-10 | `RetrievalPolicy.allowsRole` | `R-CORE/retrieval/RetrievalPolicy.java:87-95` | 泄题闸门唯一规则源 |
| E-11 | `RagRetrievalController.retrieve` / `.requireTenant` | `R-API/retrieval/RagRetrievalController.java:86-104, 163-184` | 端点 + 嵌入不可用→503；缺/非法/哨兵 → 400 |
| E-12 | `RagJwtAuthFilter.doFilter` | `R-BOOT/security/RagJwtAuthFilter.java:144-212` | 401/403 语义 + claim → request attribute |
| E-13 | `JdbcTenantScopedRetrieval.retrieve` | `R-PERS/persistence/JdbcTenantScopedRetrieval.java:42-69` | 事务内 set_config + 同事务审计 |
| E-14 | `IngestionPipeline.ingest` / `.ingestLegacyLoop` | `R-APP/ingest/IngestionPipeline.java:136-148, 179-263` | 双模式（premise / 内置主循环）；结构优先切分 + 角色判定 + 指纹 + 版面事实 |
| E-15 | `BlockRoleResolver` / `HeadingHierarchyChunker` / `ChunkPolicy(Config)` | `R-PREM/role/BlockRoleResolver.java`、`R-PREM/chunker/HeadingHierarchyChunker.java`、`R-PREM/model/` | 角色判定表（版面优先）；结构切分；切分策略与参数（留痕到 `chunk_policy*` 列） |
| E-16 | `IngestJobOrchestrator.run` / `.embedWithRetry` | `R-APP/ingest/IngestJobOrchestrator.java:85-163, 171-193` | 五阶段推进 + 整册原子 + 失败留痕；只重试瞬时类（白名单式判定） |
| E-17 | `IngestJobConsumer.pollOnce` / `.claimNext` | `R-WORK/IngestJobConsumer.java:59-88, 102-125` | PG 轮询（零 MQ）+ 不吞异常；显式租户清单 + 哨兵跳过 |
| E-18 | `ChunkIndexer.indexTenant` / `.indexGlobalCorpus` / `.writeChunk` | `R-PERS/indexing/ChunkIndexer.java:118-160, 242-254` | 租户通道 / 全局运维通道分离；切词 + 嵌入 + 落库 |
| E-19 | `LocalDeterministicEmbeddingPort` / `EmbeddingPorts.create` | `R-INFRA/embedding/LocalDeterministicEmbeddingPort.java:27-96` / `EmbeddingPorts.java:41-64` | 确定性占位嵌入（1024 维 bigram 哈希）；装配选择 + fail-closed（配了 base-url 却无 model ⇒ 拒绝启动） |
| E-20 | `OpenAiCompatibleEmbeddingPort` / `ChineseTokenizer` / `LocalPortGate` | `R-INFRA/embedding/OpenAiCompatibleEmbeddingPort.java`、`R-INFRA/text/ChineseTokenizer.java:25-124`、`R-INFRA/LocalPortGate.java` | 真实模型适配（OpenAI 兼容 `/embeddings`）；中文 unigram+bigram 分词（无词典）；占位端口 fail-closed 闸门 |
| E-21 | `RagShadow` / `RagShadowConfiguration` / `RagShadowReporter` | `R-PERS/shadow/RagShadow.java:15-79`、`RagShadowConfiguration.java:21-43`、`RagShadowReporter.java:22-75` | 采集点唯一入口 + no-op 语义；`@ConditionalOnProperty` 条件装配；阶段语义（只记 / 抛） |
| E-22 | `RlsDependencyProbe.EXPECTED` / `RlsDependencyMetricTest` | `R-CORE/scope/RlsDependencyProbe.java:47-52` / `rag/rag-persistence/src/test/.../RlsDependencyMetricTest.java:40-84` | 基线 30（R1=6/R2=6/R3=16/CENTRAL=2）；静态盘点守门（漂移即红） |
| E-23 | `M4-R1` / `M4-CENTRAL` 退役脚本 | `deploy/sql/rag/14_v1_0_4_r1_rls_retirement.sql:1-86` / `17_v1_0_4_r4_central_rls_retirement.sql:1-37` | ⛔DRAFT；R1 4 表 × 16 策略两阶段 dry-run；CENTRAL 无 DB 对象，纯代码改动 |
| E-24 | `rag_chunk` / `rag_chunk_embedding` DDL | `deploy/sql/rag/01_rag_ddl.sql:330-360, 384-393` | 片段表（含 `text_jieba`/`scope`/`content_hash`）；`embedding public.halfvec(4096)` + `index_generation` |
| E-25 | HNSW 索引缺失说明 / 词法 GIN 索引 | 同上 `:960-966, 973` | halfvec(4096) > 4000 维 ⇒ 建不了 ANN 索引；`gin (to_tsvector('simple', text_jieba))` |
| E-26 | `rag_access_audit` DDL / RLS 读策略 | 同上 `:281-292, 1262, 1283, 1324, 1358, 1392, 1465, 1499, 1533` | 审计动作白名单；`tenant_id = ANY(ARRAY[rag_current_tenant(), rag_global_tenant_id()])` |
| E-27 | `rag_index_generation` DDL | `deploy/sql/rag/09_v1_0_3_index_generation_alter.sql:33-62` | 代际状态机 `BUILDING`/`ACTIVE`/`RETIRED` |
| E-28 | `rag.retrieval.min-score` / `min-trust-tier` / `rag.shadow.*` / `rag.embedding.*` / `rag.local-ports.enabled` / `rag.ops.datasource` / `rag.security.*` | `rag/rag-bootstrap/src/main/resources/application.yml:133, 137, 50-54, 99-109, 64-68, 85-93, 138-152` | 0.38（2026-10-28 标定，27 条样本）；`OFFICIAL_TUTORIAL`；影子三项；嵌入维度/类型（⚠️ 与 DDL 的 halfvec(4096) 不一致）；占位端口闸门；独立运维池；双令牌域 |
| E-29 | worker 侧同源配置 | `rag/rag-worker/src/main/resources/application.yml:15, 27-30, 51-59, 63-73, 74-81` | `search_path`、影子、运维池、嵌入（须与 bootstrap 一致）、`rag.indexer.tenants: []` |
| E-30 | `RetrievalRepositoryIT` / `RagShadowLiveIT` / `M4R1RetrievalRetirementReverseGuardIT` / `RetrievalOrchestratorTest` | `rag/rag-persistence/src/test/.../RetrievalRepositoryIT.java:219-420`、`.../shadow/RagShadowLiveIT.java`、`.../retirement/M4R1RetrievalRetirementReverseGuardIT.java:170`、`rag/rag-application/src/test/.../RetrievalOrchestratorTest.java` | 13 个 IT（泄题/版本/跨租户/分数语义/撤回/版权/全局桶）；控制==影子反证；退役反证守卫；5 个编排用例 |
| E-31 | `RAG-D05` / `RAG-D08` / `RAG-D03` 设计基线 | `rag/docs/RAG-D05_检索策略与召回质量控制.md:13-37, 41-87, 149-205, 251-262`、`RAG-D08_评测体系与质量门禁.md:360-369`、`RAG-D03_数据模型与索引设计.md:58, 160-176, 276-277` | 七步顺序 / 多路召回 / 门禁语义 / 已知限制；RC-01~09 指标（全未实现）；`halfvec(4096)` 现状 + HNSW 不可建 + 线上仍 `vector(1024)` |
| E-32 | RLS 依赖清理改造计划 | `docs/权限管控体系/RLS依赖清理改造计划_20261112.md:23-30, 163-266, 533-583` | M0–M4 里程碑；M2 影子观测与出口门禁；M4 批次与硬前置 |
| E-33 | 能力维度实现方式梳理报告（第 8 章） | `docs/能力维度实现方式梳理报告.md:115-127` | **含三处与源码不一致**（见诚实边界声明第五节） |

### 4.4 我们独有的设计（对比中不常见的做法）

#### DD-1：业务治理轴在**数据层**成为检索准入谓词（而非应用层 post-filter）

多数框架的"过滤"是检索**之后**的应用层逻辑（LangChain 的 post-processor、CrewAI 的 `score_threshold`）。本平台把 **6 个正交的语料可信度轴**直接写进 SQL 的 WHERE（`ADMISSIBLE_TEMPLATE`），且**声明为唯一真源**（6 处 SELECT 共用）：

| 轴 | 谓词 | 失效后果 |
|---|---|---|
| 来源校验 | `src.ver_status = 'VERIFIED'` | 未校验教材被当权威语料 |
| 载体校验 | `doc.ver_status = 'VERIFIED'` | 同上 |
| 载体可检索性 | `doc.retrievable` | **撤回后仍被召回**（曾真实发生） |
| 版权（两轴独立） | `src.license_status IN ('LICENSED','FAIR_USE_QUOTATION')` 且 `doc.license_status IN (...)` | 登记占位态 `PENDING` 被当"可用"（曾真实发生） |
| 校验时效 | `src.verified_at + make_interval(days => src.ver_valid_days) >= now()` | 过期校验仍生效 |
| 权威门槛 | `src.trust_tier IN (<达标等级列表>)` | 低权威语料污染高权威回答 |

**对比价值**：这一层在所有竞品中**都没有对应物**——它把"语料能不能被检索"从**运维动作**变成了**数据不变量**。特别值得称道的是**版权是来源与载体两个独立轴**（同一版本下"官方电子版"可留、"第三方扫描版"可单独撤回），这是 RAG-D07 §2.3.1 G-0 的设计。

#### DD-2：哨兵租户承载全局公共语料，复合外键/JOIN 语义零改动

用 nil UUID 哨兵而非"tenant_id 可空"表示全局桶，使得 `(tenant_id, 父id)` 复合外键链、等值 JOIN、主键含 tenant_id 这三处**一律不变**。`RagScope.java:12-27` 把"可空表示法会踩的三个坑"逐条写明（复合外键 MATCH SIMPLE 静默不校验 / 等值 JOIN 结果为 NULL / PG 拒绝主键列 DROP NOT NULL）。这是**用表示法规避一整类语义陷阱**的范例，在竞品中无对应物（OpenAI/ADK 用"独立 collection/corpus"，LangChain 用"用户自管 filter"，都回避了这个问题）。

#### DD-3：代际蓝绿 + "只读 ACTIVE 代际"的检索侧纪律

`rag_index_generation` 状态机 + 向量表主键改为 `(tenant_id, chunk_id, index_generation)`，使换嵌入模型零停机。更关键的是**检索侧**的纪律：`denseSearch` 的代际子查询注释明确"**只能读 ACTIVE 代际**——把两个不可比的向量空间混在一起算余弦相似度，会静默产出错误的相似度，且没有任何报错"。这类"静默失真"的防范在竞品中无对应物（托管服务的换模型通常伴随索引重建）。

#### DD-4：漏斗计数贴着各环节的 List 落，禁止事后重算

`RetrievalOrchestrator.java:144-154` 的注释点破了关键："计数点必须紧贴各环节的 List，**不能事后重算**——重算等于把过滤规则再实现一遍，两份实现必然漂移；而这份漂移会直接表现为『漏斗显示过滤掉 0 条，结果却是空的』"。这是把**度量与算法同源**做成纪律。

#### DD-5：检索编排把"排序键"与"门禁键"显式分离并各自命名

`ScoredChunk` 同时携带 `score`（相关性，0~1）与 `fusionScore`（RRF，≤0.033），编排层分别用它们做门禁与排序，并在代码注释与集成测试两处固化（`RetrievalRepositoryIT.returnedScoreIsRelevanceNotRrfFusionValue()`）。这个坑曾真实发生（2026-10-23 门禁恒不通过、检索永远"未找到依据"、端点返回 200 零报错）。竞品（LangChain 的 `EnsembleRetriever`）用**用户可调的权重**表达融合，反而没有"门禁读哪个分"的问题。

#### DD-6：可切分的"结构化切分 + 切分策略留痕"

`chunk_policy` / `chunk_policy_config` 两列把"用哪套切分规则 + 什么参数"落到 chunk 上（`IngestionPipeline.chunkPolicyTrace()` / `chunkPolicyConfigTrace()`，RAG-D26 阶段 3.2）。注释写明其用途："评测依赖这两列把『检索表现变化』归因到切分策略——不写等于评测白做"。竞品中切分参数是全局配置，**无法按批次归因**。

#### DD-7：不可信围栏 + token 配额裁剪的 RAG 上下文注入

检索结果进上下文前经过两道：`RagHitQuotaPort.applyQuota`（按 token 配额裁剪，失败降级为 legacy 且不中断回答）与 `RAG_GUARD.wrapToolResult`（不可信标记，维度 7 的间接注入防护）。且 `injected` 字段反映的是"**真正送进模型的载荷非空**"而非"检索到命中"（`AgentChatOrchestrator.java:541-545`）——这个区分避免了审计上的假象。

#### DD-8（⚠️ 反向）**影子模式比对的是隔离谓词，不是检索策略**

这是本平台影子模式与"常见期待"的**根本分歧**，也是本报告与任务书假设的一处**不一致**（见文末诚实边界声明）：`RagShadow` 的比对对象是「显式谓词渲染的结果」vs「RLS 在场时的结果」，用于证明"M4 退役 RLS 后隔离结果不变"。它**没有**为"新旧检索策略双跑"提供任何机制——没有策略 A/B 双跑、没有两套结果集的比对报表、没有自动切流判定。这是**能力缺口**而非差异化设计，改进点 R-6 覆盖。

### 4.5 深度补充：四条完整调用链 + 运行期可达性四问判定（本次逐行复核）

> **背景与纠偏**：以下链路的锚点是本次（P0 修复后）实际 `Read`/`Grep` 核实的当前行号，重点覆盖本维度最容易被误判的"生产检索链路 vs 不可达草稿"。原报告第四/附录 A 的行号（如 `JdbcRetrievalRepository:269-304/:472-557`、`RetrievalOrchestrator:80-170`）为架构快照，本次核实仍成立（详见附录 D）。
>
> **运行期可达性判定口径**（项目铁律 17）：「有类/有表/有测 ≠ 被装配/被写入/被注入/被触发告警」。每条链末尾按四问判定：① 是否被 Spring 装配（`@Bean`/构造注入）；② 是否真实写入 DB；③ 是否真实注入执行链；④ 是否触发可观测/告警。四问全"是"才判"真实可达"，否则标注"静态存在"或"部分可达"。

#### 4.5.1 调用链 A（生产检索主链）：HTTP 入口 → 事务内绑租户 → 七步编排 → 双通道 RRF → 同事务审计

```
【platform 侧入口】
AgentChatOrchestrator.doRag()  [AgentChatOrchestrator.java:511-564]
  → RagHitQuotaPort.applyQuota（token 配额裁剪）[:453]
  → HttpRagRetrievalGateway.retrieve()  [HttpRagRetrievalGateway.java:103-149]
       3s 连接 / 20s 读超时 / 512KB 封顶 / followRedirects=NEVER
       → POST /api/v1/retrieval（Bearer + X-Tenant-Id）

【rag 侧入口 + 事务】
RagJwtAuthFilter.doFilter()  [RagJwtAuthFilter.java:144-212]  401/403 语义
  → RagRetrievalController.retrieve()  [RagRetrievalController.java:86-104]
       requireTenant（缺/非 UUID/哨兵 → 400）；scopeMode null→STRICT
  → JdbcTenantScopedRetrieval.retrieve()  [JdbcTenantScopedRetrieval.java:42-69]
       TransactionTemplate.execute()  [:46]  ★单事务
       → SELECT set_config('app.tenant_id',?,true)  [:47]  ← 事务内、业务 SQL 前（铁律 1）
       → RetrievalOrchestrator.retrieve()  [:48 附近注入调用]

【七步编排】
RetrievalOrchestrator.retrieve()  [RetrievalOrchestrator.java:80-170]
  ① resolveKnowledgePoints
  ② retrievalPort.search → JdbcRetrievalRepository.search()  [:269-304]
       ├ denseSearch  [:472-524]：embed(query) → `1-(e.embedding<=>?)`  [:492]
       │              + 代际过滤 `index_generation=(ACTIVE)`  [:498-504]
       └ lexicalSearch [:526-557]：`ts_rank(to_tsvector('simple',text_jieba),?)`  [:538]
         → RRF 融合 `fused[id]+=1/(60+rank)`  [:590]；relevance[id]=max(通道原始分)
  ③ 版本硬过滤 `editionScope.admits()`  [:100]
  ④ 角色硬过滤 `RetrievalPolicy.allowsRole()`  [:105]  ← 泄题闸门
  ⑤ 知识点补全（kp_ids / prerequisites）
  ⑥ 排序 `fusionScore` 降序 + 版本惩罚  [:129, :219]
  ⑦ 分数门禁 `score()>=minScore(0.38)`  [:137]  ← 相关性分，非 RRF 分
  → RetrievalFunnel 五段计数  [:149]
  → INSERT rag_access_audit(action='RETRIEVE')  [JdbcTenantScopedRetrieval.java:63-65]  ★同事务
       （本次核实实际写 5 列：audit_id/tenant_id/principal_id/action/source_id，
         其中 principal_id、source_id 均为 NULL；正文 R-7 表述为"4 列"，此处以复核值为准）
```

**运行期可达性四问**：① 装配 = **是**（`JdbcRetrievalRepository`、`RetrievalOrchestrator`、`JdbcTenantScopedRetrieval` 均由 rag-bootstrap 配置装配，构造注入 `scopeTranslator`/`embeddingPort`）；② 写入 DB = **是**（`rag_access_audit` INSERT 真实执行，`:63-65`，审计失败整次回滚）；③ 注入 = **是**（`RetrievalPort` 真实注入 `RetrievalOrchestrator`，`set_config` 在事务内真实执行）；④ 触发可观测 = **部分**（`RetrievalFunnel` 五段计数在进程内，但跨进程 7 字段白名单不透出 funnel，见 5.3 盲区 3；`rag_access_audit` 无 principal/request_digest，见 R-7）。**结论：生产主链真实可达**，"可观测"环节部分可达——这正是 R-6/R-7 要补的。

#### 4.5.2 调用链 B（摄取写链）：上传 → 任务领取 → 解析/切分/角色判定 → 嵌入 → 分词索引 → 原子落库

```
上传 PDF → RagManagementController → rag_ingest_job(PENDING)
  → IngestJobConsumer.claimNext()  [IngestJobConsumer.java:102-125]
       逐租户显式清单(rag.indexer.tenants) + FOR UPDATE SKIP LOCKED
  → IngestJobOrchestrator.run()  [IngestJobOrchestrator.java:85-163]
      ① PARSING  IngestionPipeline.ingest()  [IngestionPipeline.java:136-148]
           DocumentParserPort：DoclingDocumentParserPort(生产) / MineruResponseMapper /
             DoclingPremiseBackend / PaddleStructureParseBackend /
             LocalDeterministicDocumentParserPort(占位，受 LocalPortGate 闸门)
           → 结构优先切分 HeadingHierarchyChunker + BlockRoleResolver(版面优先)
      ② CHUNKING embedWithRetry(text)  [IngestJobOrchestrator.java:171-193]
           → saveChunkAndEmbedding → rag_chunk + rag_chunk_embedding（同事务）
      ③ INDEXING updateJiebaTokens(ChineseTokenizer)  [ChunkIndexer.java:118-160]
           → rag_chunk.text_jieba → GIN(to_tsvector('simple',text_jieba))
      ④ markDocumentParseStatus("CHUNKED") + markSucceeded(切分策略留痕)
```

**运行期可达性四问**：① 装配 = **是**（`IngestJobConsumer`/`IngestJobOrchestrator` 由 rag-worker 装配）；② 写入 DB = **是**（`rag_chunk`/`rag_chunk_embedding` 真实 INSERT，`text_jieba` 真实 UPDATE）；③ 注入 = **是**（`DocumentParserPort`/`EmbeddingPort`/`BlockChunker` 真实注入）；④ 触发可观测 = **部分**（`ingest_job` 状态与 error_detail 可读，但**RAG-3 worker 摄取链缺专用 DB IT** 是真实缺口，见 MEMORY 专题进度）。**结论：摄取主链真实可达**，但"摄取端到端 DB 级验证"部分可达——这是需补的测试缺口（非功能缺陷）。**纠偏**：`rag/docs/RAG-D05.md:259` 的"L-3 中文分词未实现"是**文档漂移**——`ChineseTokenizer.java:25-124` 已实现且索引/查询两侧共用（`JdbcRetrievalRepository.java:538` 查询侧），引用旧结论须标注"已被复核纠偏"。

#### 4.5.3 调用链 C（不可达草稿对照）：platform 侧 RetrievalService——"有类有测但运行期不可达"

```
platform/agent-infra/.../retrieval/engine/RetrievalService.java:30（构造器）
  VECTOR_SIMILARITY_THRESHOLD = 0.75  [:18]
  hybrid = vector*0.5 + tfidf*0.5  [:46]
  → VectorSearchEngine.score() 恒返回占位分
  → RetrievalEndpoint  /api/v3/retrieval（@RestController）

【可达性反证（本次 Grep 核实）】
  new RetrievalService 仅出现在：
    RetrievalServiceTest.java:24/28/41/50/59/68、RetrievalEngineIT.java:25  ← 仅测试
  <artifactId>agent-infra</artifactId> 仅出现在：
    platform/agent-infra/pom.xml:13、platform/pom.xml:125(dependencyManagement)  ← 无模块声明依赖
  → 生产代码零实例化点 + 模块不在任何依赖闭包 ⇒ 运行期不可达，/api/v3/retrieval 实际 404
```

**运行期可达性四问**：① 装配 = **否**（无 `@Bean`、无构造注入，`new RetrievalService` 仅测试）；② 写入 DB = **否**（纯内存计算，无落库）；③ 注入 = **否**（`agent-infra` 无依赖方）；④ 触发可观测 = **否**。**结论：静态存在、运行期不可达**——这是本报告复核发现的**最重要口径污染**：它自述"占位打分"，但占位的是**这个草稿类自身**；真正的生产链路在 rag 模块 `JdbcRetrievalRepository`（pgvector 真余弦 + ts_rank + RRF k=60 + 阈值 0.38，向量库集成**已闭环**）。**占位对象是嵌入模型**（`LocalDeterministicEmbeddingPort`），不是向量库/向量打分。R-0 专门删除此草稿；引用"RAG 向量打分占位未闭环"的旧结论须标注"已被复核纠偏"。

#### 4.5.4 调用链 D（隔离对偶影子）：RagShadow 采集点 → 报告 → M4 退役门禁（infra 已开，差真实流量）

```
RAG_SHADOW_ENABLED=true  [application.yml:52，env 已设]
  → RagShadowConfiguration 条件装配 RagShadowCollector（@ConditionalOnProperty）
  → collector.attach() ⇒ RagShadow.INSTANCE = collector
  → 检索读面采集点 RagShadow.collect("retrieval")
       [JdbcRetrievalRepository.java:273 search / :417 loadByIds]
  → RagShadowReporter：recordShadowSample / reportRlsBind(只计数) /
       reportMismatch / reportTaintViolation  [RagShadowReporter.java]
  → snapshot: ragShadow[samples, rlsBind, mismatches, taint, rlsDependent]
  → 出口门禁 RlsDependencyProbe.EXPECTED=30  [RlsDependencyProbe.java:47-52]
       M4 四批 SQL(14~17) ⛔DRAFT 未应用；rlsDependent 目标 0
```

**运行期可达性四问**：① 装配 = **是**（`@ConditionalOnProperty` 条件装配，infra 已开 `RAG_SHADOW_ENABLED=true`）；② 写入 DB = **否**（InMemoryRagShadowMetrics 进程内计数，无落库）；③ 注入 = **是**（`RagShadow.INSTANCE` 静态门面在检索读面被真实调用）；④ 触发可观测 = **部分**（有 snapshot 计数，但**M2 缺真实流量**，且 9 张无 RLS 表是影子期基线勿直接补 RLS）。**结论：infra 可达但数据未达**——影子"通电未通流"，这是 M4 无法放行的真实卡点（MEMORY 明确"差真实流量"），不是代码缺陷。

> **四条链的交叉结论**：链 A 说明"生产主链可达但可观测不全"（R-6/R-7 补）；链 B 说明"摄取可达但端到端 IT 缺"（RAG-3 缺口）；链 C 说明"有类有测 ≠ 可达"（R-0 删除，纠偏口径污染）；链 D 说明"可达 ≠ 有数据"（影子差真实流量，M4 卡点）。这正是铁律 17「运行期可达性审计」在 RAG 维度的四个不同切面，任何"看起来有"的能力都要按四问逐项判定，尤其**绝不可在不可达的 agent-infra 草稿上施力**。

---

## 五、正面对比分析

### 5.1 逐条能力对照表

| # | 能力项 | 我们的实现 | OpenAI VS+FileSearch | LangChain | Google ADK/Vertex | AutoGen | CrewAI | Agno | 判定 | 依据 |
|---|---|---|---|---|---|---|---|---|---|---|
| C-01 | 托管式摄取（切分+嵌入+索引） | ✅ 独立服务全链自持 | ✅ 一等 | ⚠️ 组件 | ✅ 一等 | ❌ | ⚠️ 组件 | ✅ 一等 | **持平** | 我们有 rag 独立 reactor；OpenAI/ADK/Agno 是托管服务，我们自建但同样"开箱即用"（一次上传即入库可检索） |
| C-02 | 切分策略可配且**可归因** | ✅ 结构优先 + `chunk_policy*` 留痕 | ✅ auto/static（但不可见） | ✅ 4 种+ | ⚠️ 平台内 | ❌ | ⚠️ 固定窗口 | ✅ chunker | **领先** | 只有我们把切分策略与参数落库，可按批次归因检索表现变化（RAG-D26） |
| C-03 | 中文/教辅术语切分 | ✅ 结构 + unigram/bigram（无词典） | ❌ 不可控 | ✅ 4 种 | ❌ | ❌ | ⚠️ 固定窗口 | ✅ chunker | **领先** | 结构切分（`HeadingHierarchyChunker`）+ 角色判定是教辅特化；OpenAI auto 切分对"二次函数 vs 反二次函数"不可控 |
| C-04 | 词法/稀疏通道 | ✅ `ts_rank` + GIN + 应用层分词 | ✅ hybrid_search | ✅ BM25 | ⚠️ | ⚠️ | ⚠️ | ✅ keyword | **持平** | 都有；我们额外做了"索引期与查询期同源分词"（`ChineseTokenizer` 两处共用） |
| C-05 | 混合融合算法**跨库一致** | ✅ RRF(k=60)，自实现 | ✅ RRF（权重可调） | ⚠️ 分数加权（需标定） | ❌ 未明示 | ❌ | ❌ | ⚠️ 由向量库决定 | **领先** | Agno 明确"Search algorithms differ by vector database"；我们与 OpenAI 同为 RRF 但我们额外保证跨库一致（不依赖向量库实现） |
| C-06 | Rerank（cross-encoder） | ❌ **未实现** | ✅ ranker(auto) | ✅ 组件 | ✅ LlmRanker | ❌ | ❌ | ✅ reranker | **落后** | 全仓 grep `rerank\|cross-encoder` 零命中；`RAG-D05 §2.2` 有设计无实现 |
| C-07 | Query 改写 / HyDE | ❌ **未实现** | ✅ rewrite_query | ✅ MultiQuery | ❌ | ❌ | ❌ | ✅ query transformer | **落后** | `HttpRagRetrievalGateway.buildRequest` 直接透传原始 query；平台侧无改写 |
| C-08 | 分数门禁（"找不到"是合法输出） | ✅ 0.38 + 漏斗计数 | ✅ score_threshold | ⚠️ | ⚠️ 距离阈值 | ❌ | ✅ score_threshold | ❌ | **领先** | 我们的门禁有**五段漏斗**可归因（召回/版本后/角色后/门禁后/topK 后），且门禁读相关性分而非 RRF 分 |
| C-09 | 版本坐标硬过滤（教辅） | ✅ 五轴 `EditionCoordinate` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | **领先** | 竞品均无版本概念；我们有 `EditionScope`（过滤+排序+标注三角色） |
| C-10 | 答案/讲解角色硬过滤（泄题闸门） | ✅ `RetrievalPolicy`（数据层） | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | **领先** | 同上；且"过滤在重排之前"的顺序纪律被显式写死 |
| C-11 | 语料可信度准入（版权/权威/时效/撤回） | ✅ 6 轴 SQL 谓词 | ❌ | ❌ | ⚠️（文件级权限） | ❌ | ❌ | ❌ | **领先** | `ADMISSIBLE_TEMPLATE`；竞品最多到"文件级访问控制" |
| C-12 | 多租户隔离由**数据库层**强制 | ✅ FORCE RLS + 显式谓词双写 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | **领先** | pgvector 本身无租户概念；我们的隔离靠 RLS 37 条策略 + `RagSqlScopeTranslator` |
| C-13 | 全局公共语料与租户语料同索引共存 | ✅ 哨兵桶 + 复合外键 | ⚠️ 靠 filter 表达 | ❌ | ⚠️ 多 corpus | ❌ | ⚠️ 多 collection | ⚠️ 多 collection | **领先** | `RagScope` 哨兵表示法规避三处 NULL 语义陷阱 |
| C-14 | 换嵌入模型零停机 | ✅ 代际蓝绿 + 只读 ACTIVE | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | **领先** | `rag_index_generation` 状态机；竞品托管侧靠重建索引 |
| C-15 | 跨进程检索通道 fail-closed | ✅ 非2xx/超时/坏 JSON/缺 hits 全抛 | ⚠️ SDK 抛 | ⚠️ | ⚠️ | ❌ | ❌ | ❌ | **领先** | 3s/20s 超时、512KB 封顶、禁重定向、7 字段白名单、畸形命中丢弃 |
| C-16 | 检索留痕与检索同事务 | ✅ `rag_access_audit` 同事务 | ⚠️ | ⚠️ | ⚠️ | ❌ | ❌ | ❌ | **领先** | 审计失败 ⇒ 检索回滚（"不允许检索成功但无痕迹"） |
| C-17 | 检索质量离线评测（Recall@K/MRR） | ⚠️ **指标已定义、全未实现** | ⚠️ 平台外 | ✅ LangSmith | ⚠️ | ❌ | ❌ | ❌ | **落后** | `RAG-D08:360-369` 五项指标全标"⬜ 未实现" |
| C-18 | 检索策略 A/B 影子比对 + 自动切流 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | **持平（都无）** | 竞品也无；我们有影子 infra 但比对对象是隔离谓词不是策略 |
| C-19 | 召回漏斗逐环计数 | ✅ 五段 | ❌ 未明示 | ❌ | ❌ | ❌ | ❌ | ❌ | **领先** | `RetrievalFunnel`；但⚠️ 跨进程白名单不透出 funnel（见 5.3） |
| C-20 | 检索结果不可信围栏 + token 配额 | ✅ 双道 | ❌ | ⚠️ | ❌ | ❌ | ❌ | ⚠️ | **领先** | `RAG_GUARD.wrapToolResult` + `RagHitQuotaPort` |
| C-21 | 语料治理双写期职责分离 | ✅ `SegregationOfDuties` + `rag_scope` claim | ❌ | ❌ | ⚠️ IAM | ❌ | ❌ | ❌ | **领先** | 票据只认 claim 不认 header |
| C-22 | 多形态 RAG（2-Step / Agentic / Hybrid 切换） | ⚠️ 仅 2-Step（编排器固定阶段） | ⚠️ | ✅ 三形态 | ⚠️ | ⚠️ | ⚠️ | ✅ Direct/Agentic/Traditional | **落后** | Modular RAG 的 routing 未实现 |
| C-23 | 向量列维度/类型启动期校验 | ✅ 装配期拒绝 + ANN 可行性判定 | ❌（托管） | ❌ | ❌ | ❌ | ❌ | ❌ | **领先** | `VectorColumn.requireDimension`；⚠️ 但不校验 vs DB 实际列（见 D-3） |
| C-24 | 检索侧"失败≠零命中" | ✅ 组件级 error + 事件 | ✅ HTTP 错误 | ⚠️ | ⚠️ | ❌ | ❌ | ❌ | **领先** | `RAG_UNAVAILABLE` 事件；绝不静默当零命中 |

### 5.2 优势详解（为什么领先，讲清机制）

#### 优势 1：业务治理轴下沉到数据层 —— 从"检索后处理"变成"数据不变量"

**机制**：`ADMISSIBLE_TEMPLATE` 是检索准入的唯一真源，6 处 SELECT 共用同一份字符串（构造期 `formatted` 展开权威等级列表）。这带来三个别的框架给不了的性质：

1. **不可绕过**。竞品的过滤是应用层代码——任何一个新写的查询分支忘记加过滤器就直接泄露。本平台的过滤器在 SQL 里且**共用一个常量**，新查询只要 `import` 这个模板就自动继承。审计口径是"检索结果的每个 chunk 都经过了 6 轴准入"，而不是"每个查询作者都记得写过滤器"。
2. **治理动作与检索行为一致**。管理端"撤回载体"写 `rag_document.retrievable` 与 `ver_status`，检索侧谓词读的就是这两列——**同一列既是治理动作的写入点，也是检索准入的判定点**，撤回立刻生效。曾经相反（写侧写两列、读侧不读），治理动作"对运维说谎"。
3. **版权双轴**。来源版权与载体版权是两个独立轴，任一未放行即整体不可检索，支持"同一版本下官方电子版可留、第三方扫描版可单独撤回"。

**代价**：这层谓词必须与治理写侧**逐字同源**（如同 `IngestDedupRules` 与部分唯一索引的要求），否则又会漂移。这是持续的维护纪律，不是一次性成本。

#### 优势 2：检索期硬过滤的顺序纪律 —— 教辅场景的正确取舍

**机制**：`RetrievalOrchestrator` 的七步顺序把「版本过滤」「角色过滤」放在「排序」之前，且**不依赖重排器的智能**。竞品如果要做类似过滤，只能放在应用层（`ContextualCompressionRetriever` 之后），此时被滤内容**已经进入过上下文**——即便模型"自律地不提"，也已经构成上下文泄露（模型可能以"暗示"形式泄漏，如"由上一步可得该方程的解为 x=4"，见 `RetrievalPolicy.java:25-29`）。

**这条优势的本质是取舍**：我们放弃"重排后再滤能得到更好的相关性排序"（通用 RAG 常见做法），换取"被滤内容永不进上下文"。对通用问答**不划算**（漏掉的好内容可能比泄题更常见）；对教辅**非常划算**（泄题是第二大事故，且"找不到"是可接受输出）。

#### 优势 3：隔离的双写过渡与"可退役"的对偶安全证据

**机制**：M3 把检索读面的显式谓词补齐后，渲染结果与现役 RLS 读策略 `tenant_id = ANY(ARRAY[rag_current_tenant(), rag_global_tenant_id()])` **逐字等价**——`RagSqlScopeTranslator.java:17-36` 的对照表就是这份等价性的书面证据。影子期用 `RagShadowLiveIT` 做"控制 == 影子"的双路反证，证明"撤掉 RLS 后检索/隔离结果不变"。

**这在竞品中无对应物**：OpenAI/ADK 的多租户全靠调用方正确传参，**没有"可退役的隔离层"这个概念**，因为它们的隔离本来就在应用层，没有第二层需要退役。我们的双写期成本（30 处 `set_config` 站点 + 37 条策略）是**为了将来能证明隔离不依赖数据库策略**而付出的——这个投入在竞品里没有对应物，也没有对应收益。

**诚实提示**：**这个优势目前是"已铺好但未兑现"**——M4 四批 SQL 全部 `⛔DRAFT`，`rlsDependent` 仍为 30。收益要到 M4 真正放行、观察期达标后才兑现。

#### 优势 4：切分质量是教辅 RAG 的第一瓶颈 —— 我们把它当成一等工程

**机制**：三处投入共同体现这个判断：
1. **结构优先切分**（`HeadingHierarchyChunker`）——标题块开启新节、题目块自成单元、答案块自成单元；结构不可得时退化为带重叠的固定窗口。
2. **角色判定基于版面而非模型**（`BlockRoleResolver`）——注释明确论证了误差不对称："若用模型判断角色，模型会把『讲评』误判为『答案』——而误判为答案的代价是**泄题**，误判为讲解的代价仅是**不够具体**。两类错误代价不对称，因此**宁可过判为讲解**"。
3. **切分策略留痕**（`chunk_policy` / `chunk_policy_config`）——让"检索表现变化"可归因到切分策略。

竞品的切分是全局配置或固定窗口（OpenAI `auto` 不可见、CrewAI 默认 4000 字符固定窗口、LangChain 提供 4 种但不产出教辅语义）。**"把答案区与讲解区分开并保证不漏进讲解"这件事，通用 RAG 框架的结构里没有位置。**

#### 优势 5：fail-closed 的跨进程边界 + 畸形命中丢弃

**机制**：`HttpRagRetrievalGateway` 的每一处"防御"都对应一个具体的静默失真形态：
- 非 2xx / 超时 / 非法 JSON / 缺 `hits` ⇒ 抛 `RagUnavailableException` ⇒ 编排器发组件级 error ⇒ 用户看到"RAG 检索不可用"而不是"没找到相关内容"（后者会让运维误判为语料缺失）。
- `chunkId` 或 `text` **空白**即丢弃该条（只判 null 不够——`chunkId=""` 会让前端把空 id 当合法命中渲染，形成"有命中源"的假象）。
- 响应体在**读取期**封顶 512KB（`buffering(...)`，超限抛 IOException 而不是先把整包读进内存）。
- `followRedirects(NEVER)`（SSRF 防线）。
- 不回显远端正文（可能含租户语料），只给状态码。

这些在竞品 SDK 中大多没有——它们通常假设"自家服务端不会返回畸形响应"。

### 5.3 劣势与盲区详解（明确承认，含根因分析）

#### 盲区 1：Rerank 与 Query 改写完全缺失 —— **能力缺口**（非取舍）

**证据**：全仓 `grep -rni "rerank\|cross-encoder\|crossencoder"` 在 `rag/` 与 `platform/` 的 `*.java`/`*.yml` 中**零命中**；`RAG-D05.md:71-87` 有完整的三阶段重排设计（`bge-reranker-v2-m3` 类），`RAG-D05.md:251-262` 的已知限制表明确列 L-2「RRF 融合与 cross-encoder 重排**未实现**」。**注意**：该限制表还列 L-3「中文分词未实现」——**这条已过期**，`ChineseTokenizer` 已实现并被检索与索引两侧共用（E-36）。文档与实现的漂移本身是个小问题，但**核心结论（无重排）是真的**。

**根因**：**能力缺口**，不是设计取舍。理由有三：(a) 我们在 D-5 明确选了 RRF，**知道**其代价是"丢失分差信息"，且**记录了**"因此 RRF 之后必须接重排器"——选了不接是欠账不是取舍；(b) 竞品三家（OpenAI ranker / ADK LlmRanker / Agno reranker）都把它做成一等配置，说明这是当前 RAG 的**标准件**；(c) 门禁标定中已**实测到它的后果**——"同体裁的无关查询能拿到 0.525，高于正例的 p25（0.465）。**分数门禁在原理上分不开这一类**，需要重排或知识点结构化过滤"（`RAG-D05.md:193-196`）。**我们已知道不做重排会漏，并且量化了漏的样子**。

**后果量化**：当前 0.38 门禁只能压住"远域噪声"（跨领域负对照中位 0.339 < 0.38），压不住"近邻负例"（上限 0.525）。而词法通道更严重——"纯词法命中（实测 0.068 量级）**永远过不了 0.38**——词法通道目前只能影响 RRF 排序，**不能让它独立入选**"。这意味着：我们虽然实现了双通道，但**词法通道实际只贡献排序、不贡献召回**——这是混合检索的**半失效状态**。

#### 盲区 2：检索质量评测全未实现 —— **能力缺口**

**证据**：`RAG-D08.md:360-369` 的五项 L2 检索指标（RC-01 Recall@K、RC-02 MRR@k、RC-07 误拒率、RC-08 漏拒率、RC-09 分数门禁一致性）状态列全为 "⬜ 未实现"（RC-09 除外，它由 `RetrievalRepositoryIT.returnedScoreIsRelevanceNotRrfFusionValue` 间接守护）。文档自己声明："本片交付的是**设计与约定**，不是**已生效的门禁**"（`RAG-D08.md:124`）。

**根因**：**能力缺口 + 评测集缺失**。要算 Recall@K 必须有"哪些 chunk 是相关的"标注；有标注必须有评测集。评测集是**人工密集**的工作（不是代码工作），在资源竞争下被排在后面是可以理解的。但**后果是连锁的**：
- 阈值 0.38 的标定基于 **27 条**样本（`application.yml:127-131`），是"指示性标定"，换语料/换模型必须重标——但**没有回归机制**能发现"重标后忘了改"。
- 我们**不知道**词法通道的独立召回贡献是多少（因为没有 Recall@K），所以**不知道**双通道到底是"1.5 倍召回"还是"1.05 倍召回"（这直接决定 R-2 的投入产出比）。
- 我们**不知道**漏斗的哪一段是瓶颈——虽然漏斗计数有（RAG-D17），但跨进程不透出（盲区 3）。

#### 盲区 3：召回漏斗跨进程丢失 —— **实现缺口（小但真实）**

**证据**：`RetrievalResult` 携带 `RetrievalFunnel`（五段计数），`RagRetrievalController` 返回 `RetrievalResultDto.from(result)`，但 platform 侧的 `HttpRagRetrievalGateway.parseResult`（`:175-221`）**只取 7 字段 + 3 个布尔/文本**，**没有取 funnel**。于是漏斗数据**出不了进程边界**，无法进入 `platform.agent_trace_metric` 之类的指标表。

**根因**：7 字段白名单是**有意**的安全设计（不把 rag 内部字段透给前端），但**副作用**是把观测数据也一起挡了。正确做法是**在白名单内区分"给前端的展示字段"与"给平台的可观测字段"**——前者受白名单约束，后者是平台自己的观测资产，不透出前端但应在服务端解析后记入指标。这是一条**精确的、有边界的**改进（R-6 的一部分）。

**后果**：RAG-D05 §7 明确说"『过滤前后数量』是调参的关键指标：若过滤后剩余比例过高（如 < 30%），说明召回阶段放进了太多不该放的，应调整召回策略（提高 topK 或收紧查询），而不是放松过滤"——**这个调参闭环目前是断的**。

#### 盲区 4：向量列配置与 DDL 终态不一致 + 无 ANN 索引 —— **配置漂移 + 性能债**

**证据**：`01_rag_ddl.sql:389` 列类型 `public.halfvec(4096)`；`application.yml:103,109` 默认 `dimension: 1024` + `vector-type: vector`。`RAG-D03.md:277` 更记："线上 `study_rag` 实际形态（2026-10-28 复核）… 列仍是 `vector(1024)`、HNSW 仍在、285 行占位向量 ⇒ `halfvec(4096)` 迁移**尚未执行**"。且 `01_rag_ddl.sql:960-966` 明确 halfvec(4096) 下 HNSW **刻意不存在**（超 pgvector 4000 维上限）。

**三重问题**：
1. **DDL 终态 ≠ 配置默认**：若按 DDL 建库、用默认配置启动，`VectorColumn("vector", 1024)` 与实际列 `halfvec(4096)` 不符——而 `VectorColumn` 的校验只看配置侧（`dimension()` vs `embeddingPort.dimension()`，同一来源必然一致），**不会**在装配期发现。
2. **线上库 ≠ DDL 终态**（据 RAG-D03 2026-10-28 复核），意味着**唯一权威定义与实际库不一致**，这对整个 RAG 链是严重的基础设施债。
3. **无 ANN 索引**（若迁到 halfvec(4096)）：稠密通道退化为**全表精确扫描**。语料规模一大就是瓶颈，而且这个退化**没有任何报错**——只是变慢。

**根因**：这是**迁移未完成 + 配置未同步**的双重债，不是设计取舍。`VectorColumn` 的设计本身是对的（显式建模 + 装配期校验），只是校验的参照系选错了（配置 vs 端口，而非配置 vs 数据库实际列）。

#### 盲区 5：影子模式比对对象错位 —— **能力缺口**

**证据**：`RagShadow.collect("retrieval")` 的采集点在 `JdbcRetrievalRepository.search()`/`loadByIds()`；`RagShadowReporter` 上报的四项是 `recordShadowSample` / `reportRlsBind` / `reportMismatch` / `reportTaintViolation`——全部是**隔离维度**的信号（是否走 RLS 兼容层、显式谓词与 RLS 结果是否一致、是否捕到跨租户行）。**没有任何"新旧检索策略双跑"的机制**。

**根因**：影子 infra 是为 **M2 → M4 的 RLS 退役**这一具体目的而建的，不是为"检索质量改进"而建的。两者目标不同：前者比"隔离结果是否一致"，后者要比"检索质量是否更好"。要复用影子 infra 做策略比对，需要**新增一层**"策略维度"的采样与比对（原语可复用：采样策略、只观测不阻断、可逆开关都是现成的）。

**后果**：我们**没有**一条"改了检索策略 → 影子双跑 → 出准确率/召回/成本对比报表 → 自动判定是否切流"的闭环。这是改进点 R-6。

#### 盲区 6：全局概览类问题无法回答 —— **功能缺口（有意不做的取舍）**

**证据**：`RetrievalMode.OVERVIEW` 在 `RetrievalPolicy.allowsRole` 里只是"排除答案类、保留题目与例题"的**过滤模式**，没有社区摘要、没有 map-reduce。GraphRAG 论文已证明这类"全局性问题"（"这批语料的主题是什么"）向量检索回答不了，需要社区级摘要。

**根因**：**有意识的取舍**（我们不是通用语料问答平台，是"围绕具体教学意图取依据"的检索服务）。但**必须诚实标注**：这是本平台与 GraphRAG 路线的能力差距，不是"我们已经解决了"。

#### 盲区 7：单进程内存内的召回漏斗未持久化 + 无跨租户审计主体 —— **可观测性债**

**证据**：`rag_access_audit` 的 `principal_id` 恒为 NULL（检索由系统发起）；`request_digest` 存在但检索路径的 INSERT **不写**它（`JdbcTenantScopedRetrieval.java:62-66` 只写 4 列）；`chunk_id` 也不写。即：审计表能回答"这个租户检索了几次"，**不能**回答"检索到了什么、依据是什么"。

**根因**：检索路径的审计需求被理解成"计数"而非"取证"。`rag_access_audit` 的 schema 本身**已预留** `chunk_id`/`source_id`/`request_digest` 三列——是写入侧没用上，属"schema 已就位、实现未跟上"。

**后果**：(a) 无法做"谁在什么时候检索了哪个片段"的取证（合规场景可能需要）；(b) 无法用审计数据反哺评测集（真实查询 + 真实命中是评测集最好的来源）。

#### 盲区 8：platform 侧草稿检索引擎与生产链路并存（复核发现）—— **技术债**

**证据**：`platform/agent-infra/src/main/java/cn/studykid/multiagent/infra/retrieval/engine/` 下有 4 个类：`RetrievalEndpoint`（`@RestController`，`/api/v3/retrieval`）、`RetrievalService`（Authority Triple Gate：RLS 租户隔离 + 90 天 TTL + 语义分阈值 0.75）、`VectorSearchEngine`（**恒返回 0.85** 的占位）、`TfIdfRanker`（词频计数）。复核结论：
- `RetrievalService` 在**主代码中无任何实例化点**（全仓 `new RetrievalService` 只出现在测试里）；
- `agent-infra` 模块在 `platform/pom.xml` 的 `<modules>` 中列出，但**没有任何其他 platform 模块声明依赖它**（`<artifactId>agent-infra</artifactId>` 只出现在它自己的 pom 与父 pom 的 dependencyManagement）；
- 即：它在**运行期不可达**，`/api/v3/retrieval` 端点实际不存在。

**根因**：这是 P3-D10 时期的**草稿接线**（`RetrievalEndpoint.java:15-18` 自述"本端点为草稿接线，尚未接入真实认证上下文"）。生产化路径后来走了 rag 独立服务（`HttpRagRetrievalGateway`），草稿**没有删除**。

**后果**：(a) **认知危害**——任何读代码的人（人或 AI）都可能把 `RetrievalService` 的 0.75 阈值 / `vector*0.5 + tfidf*0.5` 混合分当成生产口径，而它是**不可达的**（且混合方式与我们实际用的 RRF 不同）；(b) 既有梳理报告正是因此写下了与实际不符的"向量打分占位"结论（见文末诚实边界声明）。

**这是本报告复核发现的最重要的一处不一致**，改进点 R-0 专门处理它。

### 5.4 关键分歧点（3 个最本质的架构分歧）

#### 分歧 1：过滤与重排的相对位置 —— "数据层不可绕过" vs "上下文层尽力而为"

| | 本平台 | 通用框架（LangChain / OpenAI / Agno） |
|---|---|---|
| 过滤位置 | 排序**之前**，在 SQL 层 | 检索**之后**，在应用层 |
| 手段 | 硬谓词（`admits()` / `allowsRole()`） | post-processor / 过滤器 / prompt 约束 |
| 被滤内容 | **永不进入上下文** | 可能已进入上下文，依赖模型自律 |
| 排序质量 | 可能损失（好候选被提前滤掉） | 更优（重排后再滤） |

**各方选择的后果**：我们赢在"泄题零概率"，输在"可能有漏召"。通用框架赢在"排序最优"，输在"泄题靠概率"。**关键在于错误代价的不对称**：教辅场景的"泄题"不可补救（学生看到答案后学习效果归零），而"漏召"可补救（"没找到依据"是合法输出，学生可以换个问法）。这个不对称性决定了我们的选择是理性的——**但前提是漏召率可控**，而漏召率**目前不可测**（盲区 2）。这是我们最需要补上的证据。

#### 分歧 2：隔离由数据库策略强制 vs 由调用方正确传参

| | 本平台 | OpenAI / ADK / Agno / CrewAI |
|---|---|---|
| 机制 | FORCE RLS（37 策略）+ 应用层显式谓词双写 | 应用层 filter / namespace / collection 参数 |
| 强制点 | **数据库层**（应用 bug 也被拦） | 应用层（调用方漏传 = 越权） |
| 多租户支持 | 有（`TenantOnly`/`TenantWithGlobal`/`GlobalOnly`/`None`） | 无内建 |
| 成本 | 部署复杂（绑 GUC、授角色、策略随 schema 迁移）；双写期有 30 处待退役 | 零 |

**各方选择的后果**：我们赢在"隔离不依赖开发者记得传参"，输在"部署与演进成本全在自己头上"。主流选择把成本外推给用户（用户要自己保证 filter 正确），这是**合理的商业选择**（托管方不可能为每个用户的租户模型做假设），但对"教育内容 + 版权敏感 + 合规要求"的场景不够。

#### 分歧 3：检索作为编排器的**确定性阶段** vs 检索作为**可被模型调用的工具**

| | 本平台 | AutoGen / LangChain Agentic / Agno Agentic |
|---|---|---|
| 何时检索 | 编排器固定阶段（`AgentChatOrchestrator.doRag()`） | 模型自主决定（工具调用） |
| 成本 | 每次对话固定一次检索（可预测） | 不确定（可能 0 次、可能 N 次） |
| 可审计 | 检索必然发生、必然留痕 | 检索可能不发生；发生与否取决于模型 |
| 灵活性 | 低（不能"只在需要时检索"） | 高 |

**各方选择的后果**：我们赢在"成本可预测 + 检索必然留痕 + 顺序确定"，输在"每次都要检索"（即便学生问的是"你好"）。这与维度 1（编排模型）的整体取向一致——本平台是"受控运行时"而非"自主 agent"。Agno 的 `Agentic RAG 是默认` 与我们的"2-Step 恒定"是两种产品哲学。

### 5.5 深度补充：新增竞品纳入后的能力对照增补（承接 3.8 与 5.1）

> 5.1 的 C-01~C-24 对照了原 6 个竞品。3.8 新增 6 个竞品后，有四个对照位需要补入——它们恰好落在原表"落后/持平（都无）"的格子里，能更精确地定位本平台差距的**真实参照系**。

| # | 能力项 | 本平台 | 新增竞品参照 | 修正判定 | 依据 |
|---|---|---|---|---|---|
| C-05b | 混合融合权重可调 | ❌ 固定 RRF(k=60)，无权重配置 | ✅ Qdrant `{"rrf":{"weights":[3,1]}}` + DBSF；✅ OpenAI `rrf_*_weight`（未逐字核实） | **落后（可调性）** | Qdrant 官方：`https://qdrant.tech/documentation/concepts/hybrid-queries/`；但官方同时警告"无评测集别手调权重"→ 本平台 R-3 是前置 |
| C-06b | Rerank 形态 | ❌ 未实现 | ✅ Azure semantic ranker（融合分 0.03 / 重排分 2.x 分字段）；✅ Ragas 无（评测侧） | **落后（确认）** | Azure 官方 `https://learn.microsoft.com/en-us/azure/search/hybrid-search-overview`；再次印证 5.3 盲区 1 |
| C-17b | 检索评测现成框架 | ⚠️ 指标定义、全未实现 | ✅ Ragas Context Precision/Recall + Faithfulness；✅ Dify golden questions/bad cases 运营 | **落后（可用现成轮）** | Ragas 官方 `https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/`；Dify 官方 knowledge overview |
| C-14b | 换模型是否需重建索引 | ✅ 代际蓝绿零停机 | ❌ LlamaIndex "换模型必须 re-index、向量不可互换" | **领先（确认并强化）** | LlamaIndex 官方 embeddings 页；本平台 D-10 的零停机是相对通用框架的实打实优势 |

> **对照结论**：新增竞品没有推翻 5.1 的任何"领先"判定，反而**强化**了两处：① C-14 换模型零停机——LlamaIndex 官方明确"换模型必须重建索引"，本平台代际蓝绿是真优势；② C-05 RRF 选型——Elasticsearch/Qdrant/Azure 官方默认 k=60，本平台 k=60 是继承共识而非拍脑袋。三处"落后"（融合可调、rerank、评测）的共同点是**都属于"质量侧增强"而非"安全侧缺陷"**，与 8.5.2 的权序判断一致——可以补，但不是安全红线。

### 5.6 深度补充：本平台"领先"判定的理论克制（避免自我拔高）

> 5.2 详述了五项结构性优势。本节按诚实边界声明的要求，给这些"领先"加一层**理论克制**——区分"竞品没有对应物"与"竞品有但形态不同"，避免把"差异"误读为"优越"。

1. **"治理轴下沉数据层"（优势 1）是真正的无对应物**——OpenAI/LangChain/ADK/Agno/Dify 均无版本坐标/答案角色/版权/权威的多轴 SQL 硬过滤。这一项领先成立且可对外讲。
2. **"RRF 跨库一致"（C-05）需谨慎表述**——本平台自实现 RRF 保证跨库一致，但 Elasticsearch/Qdrant/Azure 的 RRF 是**索引级原生**能力（性能优于本平台 Java 侧融合）。准确说法是"融合算法可控、跨库一致"，**不是**"融合性能领先"。
3. **"代际蓝绿零停机"（C-14）领先成立**——LlamaIndex 官方证实通用框架换模型要重建索引；这是实打实的工程优势。但需注意：本平台当前默认仍是**占位嵌入**，代际蓝绿的价值要等 R-1 接入真实模型后才完全兑现。
4. **"哨兵桶/复合外键"（优势 C-13）是表示法优势，非能力优势**——它规避了 NULL 语义陷阱（DD-2），但竞品用"独立 collection/corpus"也解决了多语料共存，只是形态不同。准确说法是"用表示法规避一整类陷阱"，不是"别人做不到"。
5. **"无 ANN 索引"绝不能算领先也不能算缺陷**——它是规模取舍（2.4(13)），本报告的诚实表述是"当前规模精确扫描够用，规模化走 MRL/量化"。

> **克制结论**：本平台的真实领先集中在**教辅治理的数据层硬过滤**（六轴准入 + 版本/角色闸门）与**代际蓝绿**两处；其余多数是"机制完整 + 差异化取舍"。第五章所有"领先"判定都应放在"教辅 + 合规敏感 + 数据层强制"这个特定语境下理解，脱离语境的"全面领先"是过度宣称——这也是 9.5"承诺—证据"链要防范的。

### 5.7 深度补充：能力评分卡（把定性判定量化成可复算分数）

> **本节为第三轮追加**，不改写 5.1–5.6 的任何既有判定；与既有判定冲突处，在本节内以「**本轮更正（与既有正文 X.X 冲突）**」标注（见 5.7.4 子表）。
> **能力评分卡（scorecard）**＝把「领先 / 持平 / 落后」这类定性判定量化成**可复算的分数**，让本维度与 12 份兄弟报告横向可比、让后续演进有基线。
>
> **评分轴**直接沿用 1.3 的 5 个评价轴（内建闭环度 / 隔离强制性 / 质量可度量性 / 失败可诊断性 / 治理可审计性），每轴 0–4 分。
> **评分口径（全局统一）**：
> - **0** = 完全缺失（无对应实现，且无缺口登记）
> - **1** = 有痕迹不可用（有类/有表/有配置，但未装配/未写入/未触发 —— 即运行期不可达）
> - **2** = 局部可用（仅单一路径生效，或仅单租户/单场景生效，或靠人工兜底）
> - **3** = 能力完整（默认路径生效 + 有失败语义 + 有测试门禁）
> - **4** = 能力完整且有护栏（默认生效 + 失败语义 + 门禁 + **运行时度量/告警/可观测**）
> - **每格必须给证据锚点**（`文件:行号` 或「官方文档 URL」），无证据不得给分。
> - **加权总分** = Σ(轴分 × 轴权重) / Σ权重。
>
> **权重沿用 1.6 的加权优先级**（1.6 是本报告既有的"错误代价不对称表 ⇒ 权序"结论，本轮**不新设**权重，只把它映射成数值）：1.6 把「隔离强制性」与「内建闭环度之治理轴」列为 P0、「失败可诊断性」P1、「质量可度量性」「治理可审计性」P2 ⇒ **隔离强制性 0.30 / 内建闭环度 0.25 / 失败可诊断性 0.20 / 质量可度量性 0.15 / 治理可审计性 0.10**（合计 1.00）。
> **判读纪律**：① 分数是**工程判断**，不是客观测量；1–2 分差距不构成「结构性落后」的证据，只构成改进项优先级输入。② 「竞品最高分」只取**第三章 / 附录 F 已核实**的官方事实（Elasticsearch 页本轮 WebFetch 重抓复核，其余沿用已核实条目）；无官方依据的轴填「不评分」并说明。③ 若某轴我们得分高于竞品最高分，必须补**机制解释**，否则按「诚实优先于漂亮」下调 1 分。

#### 5.7.1 评分卡主表

| 评价轴 | 权重 | 我们的得分 | 证据锚点（本轮实际 Read/Grep 复核） | 竞品最高分 | 差距 | 差距根因（设计取舍 / 能力缺失 / 证据缺口） |
|---|---|---|---|---|---|---|
| **内建闭环度** | 0.25 | **3** | 摄取→切分→嵌入→双通道→RRF→硬过滤→门禁→上下文注入全链自持：`JdbcRetrievalRepository.java:107`（`RRF_K = 60.0`）、`:172-180`（六轴准入模板）、`:472-524`（稠密 pgvector `1-(e.embedding<=>?)` + 代际过滤）、`:526-557`（词法 `ts_rank(to_tsvector('simple', text_jieba), …)`）、`RetrievalOrchestrator.java:85-154`（七步编排 + 五段漏斗）；占位：`LocalDeterministicEmbeddingPort.java:30`（`DIM = 1024`）、`:33`（`MODEL_VERSION = "local-deterministic-v1"`）、`EmbeddingPorts.java:44-49`（base-url 空 ⇒ 走占位） | **3**（OpenAI Vector Stores + File Search 托管全链；Agno Knowledge 一等；附录 B URL） | **0** | **能力缺失**：占位嵌入模型是默认装配路径（`EmbeddingPorts.java:44-49`），rerank / query 改写**实现零命中**（本轮 grep：`rag/`+`platform/` 的 `*.java`/`*.yml` 中 `rerank\|cross-encoder\|crossencoder` 仅 2 处**注释**命中：`EmbeddingPort.java:18`、`LocalLexicalEntailmentPort.java:21`）⇒ 封顶 3（4 分要求"运行时度量"，本轴的度量在"质量可度量性"那一轴） |
| **隔离强制性** | 0.30 | **3** | 谓词单点渲染：`RagSqlScopeTranslator.java:111-122`（`TenantWithGlobal` ⇒ `tenant_id IN (?::uuid, ?::uuid)`；`None` ⇒ `1=0` 恒假）；双通道同源：`JdbcRetrievalRepository.java:489`（稠密）/`:535`（词法）均走同一 translator；六轴准入同源：`:172-180`；身份绑定双层：`RagJwtAuthFilter.java:180-189`（403）+ `RagRetrievalController.java:172-176`（哨兵 400）；IT 门禁：`RetrievalRepositoryIT.java:242-250`（跨租户不可见）、`:297-302`（`loadByIds` 跨租户返回空）、`:535`（`tenantCannotInsertGlobalChunk`） | **2**（Weaviate tenant isolation＝每租户独立 collection；Pinecone per-index＝每租户独立索引；pgvector **自身无租户概念**（附录 B URL）） | **+1** | **能力缺失**：4 分要求"运行时度量/告警"，本轮复核到**影子读数无出口**——计数器是 `InMemoryRagShadowMetrics.java:14`（内存实现），`RagShadowCollector.java:42-51` 的 `snapshot()` **无主代码消费方**（仅测试引用）；`RlsDependencyProbe.java:47-52` 的 `rlsDependent` 期望值仍为 30（R1=6/R2=6/R3=16/CENTRAL=2）；本轮**未在 `monitoring/` 与 `deploy/` 下发现任何 Prometheus 告警规则文件**（`alert:` / `groups:` 关键字零命中） ⇒ 封顶 3 |
| **质量可度量性** | 0.15 | **1** | 指标**已定义未实现**：`RAG-D08_评测体系与质量门禁.md:360-369` 的 RC-01/02/03/04/04b/05/06/07/08/09 **共 10 行**状态列全为「⬜ 未实现」（本轮实读该区间确认行号与条目数；既有正文 5.3 盲区 2 列举其中 5 项，**结论"全未实现"不变**，此处补足条目数）；已生效的只有 RC-09 的语义（门禁读相关性分：`RetrievalOrchestrator.java:136-138`，守护用例 `RetrievalRepositoryIT.java:266-282`）；标定样本 27 条（`rag-bootstrap/application.yml:127-131` 注释）；漏斗出不了进程：`HttpRagRetrievalGateway.java:176-200` 只取白名单字段，`RetrievalOrchestrator.java:149-154` 的五段计数未被解析 | **3**（Ragas 检索侧 Context Precision/Recall + Faithfulness；Dify golden questions/bad cases；LangSmith；附录 F URL，本轮沿用已核实条目） | **−2（我们落后）** | **能力缺失**：指标定义齐全、可执行实现与评测集**双缺**；R-3 是 R-2 的硬前置（Qdrant 官方亦警告"无评测集别手调权重"） |
| **失败可诊断性** | 0.20 | **2** | 跨进程 fail-closed：`HttpRagRetrievalGateway.java:136-147`（IOException / 非 2xx 全抛）、`:178-186`（非法 JSON / 缺 `hits` 抛）、`:208-214`（`chunkId`/`text` 空白即丢弃）；编排器区分组件失败与零命中：`AgentChatOrchestrator.java:524-533`（`RAG_UNAVAILABLE` 事件 + Skill(error)）vs `RetrievalOrchestrator.java:156-159`（门禁未过的 debug 日志）；启动期自检：`RagStartupSelfCheck.java:123-142`（超管 / BYPASSRLS 拒绝启动）、`:197-206`（未绑租户可读 ⇒ 失败）；装配期维度拒绝：`JdbcRetrievalRepository.java:242-250` | **不评分**（第三章 / 附录 F 已核实条目中，**无任何竞品官方页明示"检索失败的度量/告警口径"**） | 不可比 | **能力缺失 + 证据缺口**：能力缺口在"**静默 0 行**"——门禁恒空、谓词失配、RLS 未绑租户三种"检索不到"都表现为"空结果"，**无任何运行期信号**（漏斗跨进程丢失 + 无告警规则）；另"HTTP 403 与 503 被压成同一异常"（`HttpRagRetrievalGateway.java:144-147`）使**权限终态被伪装成临时故障**（违反铁律 15 的精神，见附录 I-08） |
| **治理可审计性** | 0.10 | **2** | 检索与留痕同事务：`JdbcTenantScopedRetrieval.java:62-66`（审计写失败 ⇒ 整次检索回滚，"不允许检索成功但无痕迹"）；审计表结构齐备：`01_rag_ddl.sql:281-291`（`rag_access_audit` 含 `principal_id` / `chunk_id` / `request_digest` 三列，`FORCE ROW LEVEL SECURITY` 见 `:293`）；但检索期写入**只填 2 列**：`JdbcTenantScopedRetrieval.java:64`（`principal_id` 显式 NULL、`source_id` NULL、`chunk_id`/`request_digest` 不写） | **不评分**（Pinecone / Weaviate 的审计能力本轮**未能从官方页逐字核实**，按规范填「不评分」而非凭印象给分） | 不可比 | **能力缺失**：schema 已就位、实现未跟上——检索期**无法把"哪次召回、哪个 chunk、哪个主体"关联起来**（R-7）；R-4（`rag_answer_citation` 引用落库）同样未闭环 |
| **加权总分** | **1.00** | **2.40 / 4** | — | **不可比**（仅 3/5 轴有官方依据） | 见 5.7.2 | — |

**加权总分算式**：`3×0.25 + 3×0.30 + 1×0.15 + 2×0.20 + 2×0.10 = 0.75 + 0.90 + 0.15 + 0.40 + 0.20 = **2.40**/4`。

#### 5.7.2 与竞品的可比子集（不拿"不可比"充数）

竞品在「失败可诊断性 / 治理可审计性」两轴**无官方依据**（不评分），故全 5 轴总分不可比。为给出**同口径**结论，只取**双方均有官方依据的 3 轴子集**（内建闭环度 + 隔离强制性 + 质量可度量性，权重合计 0.70）：

| 子集 | 我们 | 竞品最高 | 差 |
|---|---|---|---|
| 内建闭环度（0.25） | 3 | 3（OpenAI VS+File Search / Agno Knowledge） | 0 |
| 隔离强制性（0.30） | 3 | 2（Weaviate tenant isolation / Pinecone per-index） | **+1** |
| 质量可度量性（0.15） | 1 | 3（Ragas / Dify / LangSmith） | **−2** |
| **子集加权**（分母 0.70） | (0.75+0.90+0.15)/0.70 = **2.57** | (0.75+0.60+0.45)/0.70 = **2.57** | **±0.00** |

**机制解释（规范要求：高于竞品轴分必须解释）**：隔离强制性我们 3 > 竞品 2，机制是——我们的强制点在 **PostgreSQL 策略层 + 应用层谓词双写**（`RagSqlScopeTranslator.java:111-122` 渲染结果与现役 RLS 读策略逐字等价，见其 `:17-30` 对照表；`JdbcRetrievalRepository.java:489`/`:535` 双通道同源），而 Weaviate / Pinecone 的隔离依赖**服务端按 namespace / collection 归属执行**，其官方页本身即声明"依赖调用方正确传递 namespace"；pgvector 自身**完全无租户概念**（附录 B URL）。但这一优势**不可外推**：它在"失败可诊断性 / 治理可审计性"两轴是 2 分且两轴竞品无依据，**正负相抵后可比子集与竞品最高分持平（±0.00）**。

> **本轮最重要的一条量化结论**：本平台 RAG 的**工程闭环度与业界最高分持平**，短板集中在**质量度量（−2）与可诊断性/可审计性（无竞品对照但自身仅 2 分）**。这与既有正文 6.5 的画像结论（"隔离与治理强、质量度量弱"）**方向一致**，但本轮把它从"星级画像"变成了**可复算的分数与差值**，可直接作为 R-3（评测体系）与 R-6/R-7（观测/审计）的投入产出比依据。

#### 5.7.3 与第二轮 §6.5「维度健康度评分卡」的口径差异（并存不冲突）

§6.5 与本节**评的是同一组五轴，但口径、量纲、用途都不同**，两者并存互不替代：

| 维度 | 第二轮 §6.5「维度健康度评分卡」 | 本轮 §5.7「能力评分卡」 |
|---|---|---|
| **量纲** | ★1~5 星（画像级） | 0~4 分（判定级） |
| **权重** | 无权重（各轴独立陈述） | 有权重（沿用 1.6 权序映射为 0.30/0.25/0.20/0.15/0.10），可算**加权总分** |
| **竞品对照** | 无（只列我方支撑证据与扣分根因） | 有「竞品最高分 + 差距 + 差距根因」，且对无官方依据的轴**明确填"不评分"** |
| **判定粒度** | 星级概括（"扣 1 星：默认嵌入是占位模型"） | 逐轴 0–4 + **证据锚点**（`文件:行号`） |
| **用途** | 回答"我们长什么样"（画像） | 回答"我们与最高分差多少、差在哪、投入产出比"（基线与优先级输入） |
| **打分门槛** | 星级侧重"该轴有什么" | 0–4 口径**明文要求 4 分须有运行时度量/告警/可观测** ⇒ 这直接导致 §6.5 给隔离强制性满星（★★★★★）而本轮只给 **3** |

**唯一"看起来不一致"的点（不是冲突，是口径差）**：§6.5「隔离强制性 ★★★★★（扣 0）」vs 本轮「隔离强制性 = 3」。原因：§6.5 的星级按"该维度有没有护栏"打分，隔离本身**有**双层护栏 ⇒ 满分；本轮 0–4 口径的 4 分**额外要求运行时度量/告警**，而本轮复核到影子读数无出口（`InMemoryRagShadowMetrics.java:14` + `RagShadowCollector.java:42-51` 无主代码消费方）、`rlsDependent=30`（`RlsDependencyProbe.java:47-52`）、无告警规则 ⇒ 封顶 3。**两表同时成立**：隔离"强度"是满分项，隔离"可观测"是缺口项。

#### 5.7.4 本轮复核推翻的既有判定（子表）

| # | 既有判定（位置） | 本轮复核结论 | 推翻/修正依据（本轮复核锚点） | 处理 |
|---|---|---|---|---|
| 1 | 「向量列**配置漂移** + DDL 终态不一致」（既有正文 5.3 盲区 4、2.1(6) 尾注、4.2 D-3） | **收窄为「默认 yml ≠ 生产 env」，不是运行期漂移**；且"4096/halfvec 无 ANN 索引"是**刻意取舍**、**⛔ 不得降维** | 仓库侧三处一致：`01_rag_ddl.sql:389`（`embedding public.halfvec(4096)`）、`rag.env:45-46`（`RAG_EMBEDDING_DIMENSION=4096` / `RAG_EMBEDDING_VECTOR_TYPE=halfvec`）、`VectorColumnTest.java:48-49`（`halfvec(4096)` 与 `vector(4096)` 的 `indexable()` 均为 `false`）；取舍理由写在 `01_rag_ddl.sql:960-966`（"刻意不存在……规模化后再二选一：MRL 截断到 ≤4000 维，或改用二进制量化"）；**维度不是配置项而是端口事实**：`RagInfrastructureConfig.java:288-292`（维度取自 `EmbeddingPort.dimension()`）+ `JdbcRetrievalRepository.java:242-250`（装配期拒绝） | **本轮更正（与既有正文 5.3 盲区 4 冲突）**：真实风险是"默认 `application.yml:103`（dimension=1024）/ `:109`（vector-type=vector）与 DDL 终态不同，**必须靠 env 覆盖才一致**"——属**部署基线未固化**（能力缺失），不是"配置漂移"。⛔ **不采纳**"硬降维"类建议（违反 RAG 红线：不夹带改 `halfvec(4096)`/HNSW） |
| 2 | 「隔离强制性满分 / 隔离可观测已落地」（既有正文 5.1 C-12 判"领先"、6.5 给 ★★★★★） | **"领先"对竞品成立；"可观测"不成立**——隔离读数**出不了进程** | `InMemoryRagShadowMetrics.java:14`（内存实现）；`RagShadowCollector.java:42-51`（`snapshot()`）**无主代码消费方**（本轮 grep 仅测试引用）；`RagShadow.java:35-43`（`collect` 走静态门面，未注册时全 no-op）；`RagStartupSelfCheck.java:108` 只打印 `RlsDependencyProbe.snapshot()` 的**静态期望值**，不是实时读数 | 维持"领先"表述但**加限定语**；新增"隔离可观测"缺口 ⇒ 直接对应附录 G-07 与附录 I-13 |
| 3 | 「线上 `study_rag` 实际列仍是 `vector(1024)`、HNSW 仍在、285 行占位向量」（既有正文 5.3 盲区 4 转引 `rag/docs/RAG-D03_数据模型与索引设计.md:277`） | **本轮未能复核**（未连库）⇒ 降级为**证据缺口**，**不得作为实施依据** | 本轮只做仓库侧静态复核（DDL / `rag.env` / 单测三处一致，见第 1 行）；`RAG-D03` 该行自记为「2026-10-28 复核」，其结论（"迁移尚未执行"）**在本轮无法在活库复验** | 标注为证据缺口（与诚实边界声明六·2 一致）；⛔ 不据此提"迁列"建议 |
| 4 | 「`[图]` 占位块的设计出处 = `LayoutPredicates.java:135`」（MEMORY 与既有正文引用） | **结论不变、行号更正**：真实出处为 `LayoutPredicates.java:141`（`placeholderFor` 方法）与其 `:144`（`case FIGURE -> "[图]"`）；`:135` 是该方法的 javadoc 起始行 | 本轮实读 `LayoutPredicates.java:135-148` 全文 | 仅更正行号；「`[图]` 是摄取期设计、非脏数据」的结论**不变** |
| 5 | 「影子模式比对对象错位」（既有正文 5.3 盲区 5、4.4 DD-8） | **复核成立并细化一层**：影子语义确为"隔离谓词对照"（显式谓词 vs RLS 基线），**不是**检索策略双跑；且本轮新发现"读数无出口" | `RagShadowReporter.java:51-53`（`reportRlsBind` 恒不抛）、`:56-61`（`reportMismatch`）、`:64-70`（`reportTaintViolation`）；采集点 `JdbcRetrievalRepository.java:273`（检索）/`:417`（依据取回）；`RagShadowMetrics.java:17-40` 五个计数器定义 | 维持判定并补"读数无出口"；⇒ 附录 G-07 判定为 ⚠️ 条件生效 |
| 6 | 「全仓 `rerank\|cross-encoder\|crossencoder` **零命中**」（既有正文 5.3 盲区 1、诚实边界声明一·3） | **表述收窄**：实现确为零，但**注释有 2 处命中** | 本轮 grep（`rag/` + `platform/`，`*.java`/`*.yml`）：`EmbeddingPort.java:18`（"重排器（cross-encoder）精度显著高于双塔，是召回质量的决定性环节"）、`LocalLexicalEntailmentPort.java:21`（"这不是 NLI 蕴含模型，也不是 cross-encoder 重排器"） | 结论（**无 rerank 实现**）不变；表述改为"**仅注释命中，实现零命中**"（诚实优先于漂亮） |

> **未核实项（诚实声明）**：① 竞品在「失败可诊断性 / 治理可审计性」两轴**无官方依据**，未打分 ⇒ 全 5 轴总分**不可比**，只有 3 轴子集可比。② 线上 RAG 库的真实列形态与策略数**未连库核实**（第 3 行）。③ 本轮**未运行任何测试**，全部行为判断（并发不重复领、审计失败回滚、跨租户不可见等）均引自既有 IT 用例与源码注释，**未实测**（需按本报告门禁口径在实施阶段验证）。④ 影子指标在真实流量下的读数**未核实**（本轮未连库、未接入真实流量）。

---

## 六、优缺点总评

### 6.1 我们的优点（分级）

**结构性优势**（来自架构选择，不是工作量堆出来的）：

1. **业务治理轴是数据层不变量**：6 轴准入谓词单一真源，跨租户/跨版本/跨角色/跨版权的约束都在 SQL 里。竞品无对应物（C-11）。
2. **隔离双写 + 可退役的对偶安全证据**：谓词与 RLS 逐字等价 + 影子反证，为"最终退出数据库策略层"准备了证据链（C-12）。
3. **哨兵租户表示法规避三处 NULL 语义陷阱**：复合外键 / 等值 JOIN / 主键约束（C-13）。
4. **过滤在重排之前的顺序纪律**：把"不进上下文"作为唯一可靠边界（C-10）。
5. **代际蓝绿 + 只读 ACTIVE 代际**：换模型零停机且不产生不可比向量空间（C-14）。
6. **切分策略与参数落库留痕**：可按批次归因检索表现变化（C-02）。

**工程性优势**：

7. **跨进程边界的 fail-closed 深度**：超时/封顶/禁重定向/白名单/畸形丢弃/不回显正文（C-15、C-24）。
8. **检索留痕与检索同事务**：不允许"检索成功但无痕迹"（C-16）。
9. **向量列形态显式建模 + 装配期校验 + ANN 可行性判定**（C-23）。
10. **门禁读相关性分而非 RRF 分**，且被集成测试钉死（避免 2026-10-23 那类静默失真）。
11. **漏斗五段计数贴着各环节 List 落**，禁止事后重算（C-19）。
12. **不可信围栏 + token 配额双道**（C-20）。

**领域性优势**（教辅特化，通用框架无法复制的）：

13. **五轴版本坐标 + 过滤/排序/标注三角色**（`EditionScope`）：串版本是教辅第一大事故。
14. **答案/讲解角色分离与泄题闸门**：基于版面而非模型判定，且误差代价不对称被显式承认。
15. **可信度分级（信任层级 + 版权 + 校验时效）与准入联动**。
16. **知识点图谱邻域扩展**（`kp_ids` 聚合 + `prerequisites` 边）：与 GraphRAG 的 Local Search 同源但更精确。

### 6.2 我们的缺点（分级）

**能力缺口**（该做而未做）：

1. **Rerank 未实现**（C-06）——RRF 的信息损失无处补偿；已知后果被量化（近邻负例 0.525 > 正例 p25 0.465）。
2. **Query 改写 / HyDE 未实现**（C-07）——Advanced RAG 的"检索前处理"整段缺失。
3. **检索质量评测全未实现**（C-17）——Recall@K/MRR/NDCG 无实现，阈值标定仅 27 条样本且无回归门禁。
4. **词法通道半失效**——因门禁量纲问题，纯词法命中（0.068 量级）永远过不了 0.38，词法只贡献排序不贡献召回。
5. **影子模式未用于策略比对**（C-18）——无 A/B 双跑、无报表、无自动切流。
6. **全局概览类问题无法回答**（GraphRAG 路线差距）。
7. **召回漏斗跨进程丢失**（C-19 的负向）——调参闭环断裂。

**设计取舍导致的代价**（明知故选）：

8. **过滤在重排之前**——可能漏召好内容；且该取舍的前提（"过滤后候选仍 > 30 条"）**目前无实测支撑**（无 100 条召回配置）。
9. **独立进程**——多一跳网络 + 跨进程事务上下文丢失（需在 rag 侧重建租户上下文）。
10. **双写期成本**——RLS 在场期需同时维护两套隔离。

**运维负担**（自己扛的）：

11. **向量列配置/迁移/索引债**（盲区 4）——配置与 DDL 不一致、线上库与 DDL 不一致、halfvec(4096) 建不了 ANN 索引。
12. **策略与 schema 同步纪律**——新表须同步 `03_platform_roles.sql`；`ON CONFLICT DO UPDATE` 需 UPDATE 授权 + SchemaDriftIT。
13. **代际一致性的持续维护**——代际元数据、构建游标、激活/退役时序。
14. **不可达草稿代码的技术债**（盲区 8）——`agent-infra` 的 4 个类误导认知，且已被写进既有梳理报告。

### 6.3 每个竞品的优点（简明，带来源）

| 竞品 | 优点 | 来源 |
|---|---|---|
| **OpenAI** | 端到端托管零运维；切分可配（auto/static，100–4096 词元，重叠 ≤ 半数）；**混合检索是 RRF 且权重可调**（`rrf_embedding_weight`/`rrf_text_weight`）；query 改写内建（`rewrite_query`）；重排可选（`ranker` = none/auto/default-2024-11-15）；元数据过滤 8 算子 | <https://platform.openai.com/docs/guides/retrieval>、<https://platform.openai.com/docs/api-reference/vector-stores/search> |
| **LangChain** | 组件最全、组合性最强：MultiQuery / Ensemble / ParentDocument / ContextualCompression / SelfQuery / MultiVector / Merger / RePhraseQuery；切分 4 种；VectorStore 可插拔；2-Step / Agentic / Hybrid 三形态 | <https://python.langchain.com/docs/concepts/retrievers/>、<https://docs.langchain.com/oss/python/deepagents/retrieval> |
| **Google ADK** | Vertex AI RAG Engine 托管 corpus + 可接 Vertex AI Search；**LLM reranker 是一等参数**（`rag.Ranking(llm_ranker=...)`）；服务端 grounding（检索在模型侧发生，不执行本地代码）；top-k 与距离阈值可配 | <https://google.github.io/adk-docs/integrations/vertex-ai-rag-engine>、<https://adk.dev/api-reference/typescript/classes/VertexRagRetrievalTool.html> |
| **AutoGen**（已归档） | 把检索建模为 agent 角色，与 Agentic RAG 天然契合；多 agent 对话流让检索调用可复盘 | <https://github.com/microsoft/autogen>、<https://microsoft.github.io/autogen/stable/index.html> |
| **CrewAI** | **知识作用域显式**（Agent 级 vs Crew 级，唯一把"知识可见范围"作为一等维度的框架）；嵌入器多 provider 且可按 Agent 覆盖；切分参数与门槛可配（`chunk_size`/`chunk_overlap`/`results_limit`/`score_threshold`）；无独立检索服务，最简 | <https://docs.crewai.com/en/concepts/knowledge>、<https://docs.crewai.org.cn/concepts/knowledge> |
| **Agno** | **Knowledge 真一等公民**：摄取+切分+嵌入+检索+重排+过滤+查询改写全在一个抽象下，每项独立配置；hybrid search 内建；**reranker 一等组件**（扩大候选池→重排→top-N）；HyDE 查询改写内建；19 种向量库 | <https://docs.agno.com/knowledge/introduction>、<https://docs.agno.com/knowledge/concepts/search-and-retrieval/overview> |

### 6.4 每个竞品的缺点（简明，带来源）

| 竞品 | 缺点 | 来源 |
|---|---|---|
| **OpenAI** | 无多租户隔离语义（`filters` 靠调用方传参）；`auto` 切分结果不可见、不可归因；托管锁定 + 版权敏感语料的境外合规风险；中文教辅术语切分不可控 | 同上；`chunking_strategy` 官方描述仅 auto/static，无教辅语义 |
| **LangChain** | 全是零件不是闭环，组件间口径漂移全靠人守；无版本/角色/版权/权威等业务治理原语；`EnsembleRetriever` 权重需人工标定（与我们的 RRF 理由相反）；无租户隔离语义 | <https://python.langchain.com/docs/concepts/retrievers/> |
| **Google ADK** | 强 GCP 绑定（`projects/.../ragCorpora/...` 资源路径）；单 corpus 限制（TS API Reference 明确 "only supports one corpus per `ragResources` array"）；无多租户语义；无业务治理原语 | <https://adk.dev/api-reference/typescript/classes/VertexRagRetrievalTool.html> |
| **AutoGen** | **已归档**（2025-10 维护模式 → 2026-10 archived，继任者 Microsoft Agent Framework 2026-04 GA）；RAG 靠外部 LangChain，非内建；把检索做成 agent turn 带来额外 LLM 调用与延迟 | <https://github.com/microsoft/autogen>、<https://learn.microsoft.com/agent-framework/> |
| **CrewAI** | 切分是固定长度窗口（默认 4000 字符），**不做结构/语义切分**——对"答案区必须分离"的教辅语料结构性不适用；无治理/租户语义；RagTool 依赖 Embedchain 聚合层 | <https://docs.crewai.org.cn/concepts/knowledge> |
| **Agno** | **融合算法由底层向量库决定**（官方原文 "Search algorithms differ by vector database"）→ 不可跨库一致；无业务治理原语；无租户隔离语义；rerank 模型需自带 | <https://docs.agno.com/knowledge/concepts/search-and-retrieval/overview> |

### 6.5 深度补充：维度健康度评分卡（对照 1.3 五轴）

> 6.1/6.2 分级列了优缺点。本节按 1.3 五轴给出可复核的评分（★1~5），把"优点/缺点"转成"能力画像"，并明确每轴的扣分根因，与 9.5 的"承诺—证据"链呼应。

| 评价轴（1.3） | 评分 | 支撑证据 | 扣分根因 |
|---|---|---|---|
| **内建闭环度** | ★★★★☆ | 摄取→嵌入→索引→双通道召回→RRF→硬过滤→门禁→上下文注入全链自持（4.5.1/4.5.2） | 扣 1 星：默认嵌入是占位模型（R-1）、rerank/query 改写缺失（R-2） |
| **隔离强制性** | ★★★★★ | FORCE RLS + 显式谓词双写 + 哨兵桶 + 影子对偶证据（C-12/C-13）；M1/M3 收口 66 用例 DB IT 全绿 | 扣 0（本维度最强项），但 M4 未放行、`rlsDependent=30` 是**进行中**非缺陷 |
| **质量可度量性** | ★★☆☆☆ | 0.38 有标定记录、RC-09 由 IT 守护、漏斗五段计数 | 扣 3 星：Recall@K/MRR 全未实现（R-3）、27 条样本、跨进程 funnel 丢失、影子差真实流量 |
| **失败可诊断性** | ★★★☆☆ | 维度/类型装配期校验（D-3）、fail-closed 跨进程边界（C-15）、ingest error_detail | 扣 2 星：配置 vs DB 实际列未校验（R-1 列侧自检）、审计无取证字段（R-7） |
| **治理可审计性** | ★★★☆☆ | `rag_access_audit` 同事务留痕、六轴准入单一真源、`rag_answer_citation` | 扣 2 星：`principal_id`/`chunk_id`/`request_digest` 恒 NULL（R-7），schema 已就位实现未跟上 |

> **画像结论**：本平台是典型的"**隔离与治理强、质量度量弱**"的形态——前两轴接近满分且是竞品无对应物的真优势，后三轴的短板**集中在同一件事：数据产出与观测**（R-3/R-6/R-7）。这解释了为什么第七章把 R-3 评测门禁列为 P0、把三条数据改进串成一条线：**补质量度量不是加新能力，而是补齐"已建机制的效果证据"**。

### 6.6 深度补充：新增竞品的优缺点增补（承接 3.8，带本次核实 URL）

| 竞品（本次新增） | 优点 | 缺点/边界 | 官方来源（本次核实） |
|---|---|---|---|
| **Elasticsearch** | RRF 融合 BM25+kNN，k 默认 60、无需调参；`rank_window_size` 控制候选窗口 | RRF 丢分差；融合仍在检索层，无业务治理/租户/版本语义 | https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html |
| **Qdrant** | prefetch 多路融合；RRF/DBSF 双算法；加权 RRF 可调；"无评测集别手调权重"的诚实建议 | DBSF 依赖检索器分数已校准；无业务治理语义 | https://qdrant.tech/documentation/concepts/hybrid-queries/ |
| **Azure AI Search** | 混合检索 + semantic ranker；融合分与重排分显式分字段；过滤/facet/语义排序一体 | 强 Azure 绑定；单 corpus 边界≠租户；无版本/角色治理 | https://learn.microsoft.com/en-us/azure/search/hybrid-search-overview |
| **LlamaIndex** | 嵌入/索引/查询编排完整；默认余弦；多 provider 可插拔；明确"换模型须重建索引" | 换模型停机重建（本平台代际蓝绿领先点）；无治理/租户语义 | https://developers.llamaindex.ai/python/framework/module_guides/models/embeddings/ |
| **Ragas** | 现成 reference-free 评测（Context Precision/Recall、Faithfulness）；可直接用于 R-3 | LLM-as-judge 有偏差（Zheng et al.），须不同族+钉版本+对调取均值 | https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/ |
| **Dify** | 知识库产品化：chunk/index/检索可配 + golden questions/bad cases 运营闭环 | 固定/自动切分，无结构/语义切分；无版本/角色/版权治理；数据托管 | https://docs.dify.ai/en/cloud/use-dify/knowledge/overview |

> **增补结论**：6 个新增竞品没有一个在"数据层业务治理"上有对应物（再次印证 6.1 结构性优势），但在**融合可调性、rerank、评测运营**三处普遍领先本平台——且这些差距与 6.5 评分卡的低分轴一一对应。这构成一条完整证据链：**本平台的短板不是孤立的技术债，而是与业界标准件的系统性差距**，补齐路径就是 R-2/R-3/R-6。

---

## 七、可优化与改进点（必须可执行）

> 铁律约束：本节所有方案必须遵守 `.workbuddy/memory/MEMORY.md` 的 26 条铁律。特别是：铁律 1（RLS GUC 须在事务内、业务 SQL 前绑定；验隔离用 `app_agent` NOBYPASSRLS）、铁律 2（枚举须 `?::enum`；`INSERT…RETURNING` 需 SELECT 且走 executeQuery）、铁律 6（验收三级 + 反向验证；守卫必问"撤掉修复点会红吗"）、铁律 7（业务 SQL 用 `app_agent` 绝不 `agent_retention`；新表同步 `03_platform_roles.sql`）、铁律 8（fail-closed；无物理删除端点）、铁律 11（事务内 SQL 失败禁 fail-soft 后 COMMIT）、铁律 17（静态守卫 ≠ 运行时成立，必须真实交互驱动 + 撤点反证）。

### R-0（P0）：清除不可达的 platform 侧草稿检索引擎，消除口径污染

- **问题陈述**：`platform/agent-infra/.../retrieval/engine/` 的 4 个类（`RetrievalEndpoint`/`RetrievalService`/`VectorSearchEngine`/`TfIdfRanker`）在运行期**不可达**——`RetrievalService` 在主代码中无实例化点，且 `agent-infra` 模块未被任何 platform 模块声明依赖，因此 `/api/v3/retrieval` 端点实际不存在。但它们承载着"Authority Triple Gate / 语义分阈值 0.75 / 混合分 `vector*0.5 + tfidf*0.5`"等**看起来像生产口径**的数字，且**已被写进既有梳理报告第 8 章**（`docs/能力维度实现方式梳理报告.md:123`），造成口径污染。任何读代码或读梳理报告的人都会把 0.75 阈值当成生产口径，而真实门禁是 rag 侧的 0.38、真实混合方式是 RRF。
- **方案设计**（三步，全部可执行）：
  1. **删除草稿**：删除 `platform/agent-infra/src/main/java/cn/studykid/multiagent/infra/retrieval/engine/` 整个目录（4 个 main 类 + 2 个测试类），并从 `platform/pom.xml:66` 的 `<modules>` 中移除 `<module>agent-infra</module>`，同时删除 `platform/pom.xml:125` 的 `dependencyManagement` 中的 `agent-infra` 条目与 `platform/agent-infra/pom.xml`。
  2. **修正文档**：`docs/能力维度实现方式梳理报告.md:123` 的"检索引擎 Authority Triple Gate"条目改写为指向真实实现（`rag/rag-persistence/.../JdbcRetrievalRepository.java` + `rag/rag-application/.../RetrievalOrchestrator.java`），门禁阈值改为 0.38，混合方式改为 RRF(k=60)，并删除"向量打分占位未闭环"的表述（真实稠密通道用 pgvector `<=>` 余弦，已闭环；占位的是**嵌入模型**，见 R-1）。
  3. **加守卫**：在 `platform/agent-application/src/test/java/.../RetrievalAuthoritySourceOfTruthTest.java` 新增一条守卫——扫描 `platform/agent-*/src/main/java` 全量 `.java`，断言**不存在**同时出现 `VECTOR_SIMILARITY_THRESHOLD` / `TTL_MAX_AGE_MINUTES` / `hybridScore` 的类（防止同类草稿复活）；再断言 `agent-infra` 不在 `platform/pom.xml` 的 `<modules>` 中。
- **预期收益**：消除口径污染（可度量：全仓 `grep -c "VECTOR_SIMILARITY_THRESHOLD\|TTL_MAX_AGE_MINUTES"` = 0）；消除 4 个"看起来可用实际不可达"的端点（可度量：`/api/v3/retrieval` 在真实启动后返回 404——这是**期望**行为，需在守卫中显式断言）；平台侧检索口径唯一（rag 侧）。
- **实施成本与风险**：低。删除的是不可达代码，无生产调用方。**风险**：若外部有脚本在调 `/api/v3/retrieval`，删除后从"404（其实现在也是 404）"无变化——因为它现在就不存在。需要注意的是 **agent-infra 的删除会让 `platform/pom.xml` 的模块数从 14 降到 13**，任何断言模块清单的守卫需同步。
- **优先级**：**P0**。**排期**：M4-R1 放行前（因为 M4 的"R1 代码改动清单"涉及 `set_config` 调用点盘点，清理死代码能避免盘点时把草稿算进去）。
- **验收门禁**（三级 + 反向验证）：
  1. **单元**：新增 `RetrievalAuthoritySourceOfTruthTest`——(a) 断言全仓不存在 `VECTOR_SIMILARITY_THRESHOLD`/`TTL_MAX_AGE_MINUTES` 标识符；(b) 断言 `platform/pom.xml` 不含 `<module>agent-infra</module>`。
  2. **API**：启动 platform（`mvn -o -f platform/pom.xml clean verify` 后起 `platform-bootstrap`），断言 `GET /api/v3/retrieval/workspace` 返回 **404**（不是 500——500 说明还有残留装配）。
  3. **反向验证**：把 `agent-infra` 模块**加回** `platform/pom.xml` 并恢复一个含 `VECTOR_SIMILARITY_THRESHOLD` 的类 → 断言第 (a) 项守卫**转红**（红才对）。
  4. **文档一致性**：`docs/能力维度实现方式梳理报告.md:123` 的门禁阈值与融合方式与 `rag-bootstrap/application.yml:133`（0.38）及 `JdbcRetrievalRepository.java:107`（`RRF_K=60`）**逐字一致**，由一条 grep 断言守护。

### R-1（P0）：向量打分闭环 —— 从"占位嵌入"到"真实嵌入 + 端到端可度量"

- **问题陈述**：**精确区分两件事**（这是本报告最重要的一次澄清）：
  - ✅ **向量打分本身已闭环**：稠密通道用 pgvector 余弦 `1 - (e.embedding <=> ?)`（`JdbcRetrievalRepository.java:492`），打分值真实返回（`score`），不是占位。
  - ❌ **嵌入模型是占位**：`LocalDeterministicEmbeddingPort`（1024 维字符 bigram 哈希）自述"**本实现不是生产级嵌入模型**… 确定性但语义贫乏… 绝不用于『语义相关性判定』的生产结论"。它让检索链路跑通，但**任何基于它的相关性结论都不是质量结论**。
  - ⚠️ **且配置与 DDL 不一致**：`application.yml:103,109` 默认 `dimension: 1024` + `vector-type: vector`，而 DDL 终态列是 `public.halfvec(4096)`（`01_rag_ddl.sql:389`）；`VectorColumn` 只校验"配置 vs 端口"，不校验"配置 vs 数据库实际列"。同时 halfvec(4096) 超 pgvector HNSW 的 4000 维上限 ⇒ **建不了 ANN 索引**，稠密通道退化为全表精确扫描。
  - ⚠️ **且无语义门禁的端到端证据**：0.38 阈值是 2026-10-28 用 `qwen3-embedding:8b` 标定的，但**当前装配默认是占位**——配置与标定结论之间没有任何机器可校验的绑定。
- **方案设计**（四个子项，按依赖顺序）：
  1. **加列侧校验（最小、最高优先级）**：在 `rag/rag-persistence/src/main/java/cn/studykid/rag/bootstrap/RagStartupSelfCheck.java` 增加一项启动自检——读 `pg_attribute` 拿 `rag.rag_chunk_embedding.embedding` 的实际类型与维度，与 `VectorColumn` 比对：
     ```java
     // 期望：halfvec(4096) 或 vector(1024)，与 rag.embedding.vector-type + dimension 一致
     SELECT format_type(a.atttypid, a.atttypmod)
     FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
     JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'rag' AND c.relname = 'rag_chunk_embedding'
       AND a.attname = 'embedding';
     ```
     不符 ⇒ **拒绝启动**并给出可执行提示："实际列是 `halfvec(4096)`，但配置为 `vector(1024)`；请改 `RAG_EMBEDDING_VECTOR_TYPE`/`RAG_EMBEDDING_DIMENSION`，或执行列迁移脚本"。这补上了 `VectorColumn` 校验参照系的缺口（配置侧 → 配置侧 vs 列侧）。
  2. **统一配置与 DDL 终态**：把 `rag-bootstrap/application.yml:103` 与 `:109` 的默认值改为 `dimension: 4096` + `vector-type: halfvec`（与 `01_rag_ddl.sql:389` 一致），并在 `rag-worker/src/main/resources/application.yml:67,73` 同步（两处必须同源，worker 侧注释已写明"两处配置项一旦漂移就是『检索恒低分且不报错』"）。**同时**按 `RAG-D03.md:276-277` 复核线上库实际列形态，据此决定是否需要执行列迁移。
  3. **ANN 索引决策**：halfvec(4096) 建不了 HNSW。两个选项——(a) 降到 4000 维以内（需嵌入模型支持 Matryoshka 截断，参见 (2) 的 2.3 方案）以启用 ANN；(b) 接受全表扫描但**显式承认**并在容量规划中记入。**推荐 (a)**：Matryoshka 表示（Kusupati et al., NeurIPS 2022, arXiv:2205.13147）允许单模型输出多维度，取前 d 维即得 d 维表示——这正是本场景需要的（同一模型、换维度、免重训），且 BGE-M3 / E5 系均支持。
     执行 DDL（幂等 ALTER，参照 `04_migrate_embedding_halfvec_4096.sql` 的纪律：先摘约束 → 再回填 → 最后收 NOT NULL / 重建约束）：
     ```sql
     -- deploy/sql/rag/18_v1_0_5_embedding_dimension.sql（新增）
     -- 顺序恒为：摘索引 → 改列类型 → 回填 → 重建索引
     DROP INDEX IF EXISTS rag.rag_chunk_embedding_hnsw_idx;
     ALTER TABLE rag.rag_chunk_embedding
       ALTER COLUMN embedding TYPE public.halfvec(4000) USING embedding::public.vector;
     CREATE INDEX IF NOT EXISTS rag_chunk_embedding_hnsw_idx
       ON rag.rag_chunk_embedding USING hnsw (embedding public.halfvec_cosine_ops)
       WITH (m='16', ef_construction='64');
     ```
     ⚠️ 铁律 7：若本脚本引入新表须同步 `03_platform_roles.sql`；本脚本不改表结构只改列型，故仅需同步 `deploy/sql/README.md` 的迁移链说明 + `scripts/rag-it-env.sh --probe` 的期望值。
  4. **真实嵌入接入 + 端到端门禁**：在 `rag-bootstrap`/`rag-worker` 配 `RAG_EMBEDDING_BASE_URL` + `RAG_EMBEDDING_MODEL`（缺 model 时 `EmbeddingPorts.create` 已 fail-closed 拒绝启动，E-34），使 `EmbeddingPorts.create` 走 `OpenAiCompatibleEmbeddingPort`（真实模型）而非 `LocalDeterministicEmbeddingPort`。**并新增一条端到端 IT**：`RealEmbeddingRetrievalIT`（真实模型 + 真实语料 + 真实 PG）断言：(a) 稠密通道返回的 `score` 落在预期区间（不是恒定值）；(b) 门禁在真实模型下按 0.38 生效；(c) 换 embedding 模型后 `rag_index_generation` 能建新代际、旧代际仍服务。
- **预期收益**（可度量口径）：
  - 检索相关性从"链路可验证"变为"质量可判定"：可度量 = `RealEmbeddingRetrievalIT` 全绿 + Recall@K 有基线值（R-3 交付）。
  - ANN 索引可用：可度量 = `pg_indexes` 中 `rag_chunk_embedding_hnsw_idx` 存在且 `pg_relation_size` 显著小于全表扫描成本；稠密检索 P95 延迟在目标语料规模下下降 ≥ 50%（口径：`EXPLAIN ANALYZE` 对比有/无索引的执行计划与耗时）。
  - 消除"配置与实际列不符"的静默失败：可度量 = 启动自检在人为改错 `RAG_EMBEDDING_DIMENSION` 时**拒绝启动**（非静默运行到检索期才报 PG 错）。
- **实施成本与风险**：
  - **成本**：子项 1 小（1 个自检方法 + 1 条查询）；子项 2 小（改 4 行 yml）；子项 3 中（列迁移 + 全量重建索引，**语料大时耗时以小时计**，需停机窗口或双代际并行）；子项 4 中（需真实嵌入服务 + 一份标注评测集）。
  - **风险**：(a) 列迁移期间检索不可用 → 必须走代际蓝绿（先在新代际建 4000 维向量、切换 ACTIVE、再退役旧代际），**不得**原地改列；(b) 换嵌入模型后**旧门禁阈值 0.38 失效**（不同模型的余弦分布不同）→ 必须在切换同批完成阈值重标（依赖 R-3）；(c) 真实嵌入服务不可用会让检索整体不可用（`RagRetrievalController` 已把 `EmbeddingUnavailableException` 映射为 503，符合 fail-closed，但**可用性下降**必须评估）。
- **优先级**：**P0**。**排期**：子项 1+2 属 M4-R1 之前（低成本高收益）；子项 3 属代际蓝绿演练窗口（可与 `scripts/e2e-index-generation.sh` 现有演练合并）；子项 4 依赖 R-3 的评测集。
- **验收门禁**：
  1. **单元**：`RagStartupSelfCheckTest` 新增用例——把实际列类型 mock 成 `halfvec(4096)` 而配置为 `vector(1024)` ⇒ 断言自检**抛异常并拒绝启动**；反向：列与配置一致 ⇒ 断言通过。
  2. **API**：`RealEmbeddingRetrievalIT`（真实 PG + 真实模型 + 合成夹具）——断言稠密 `score` 不是恒定值（`assertTrue(scoreStdDev > 0.05)`）；断言门禁在真实模型下按 0.38 生效；断言 `embedding_model` 在 `rag_chunk_embedding` 与 `rag_index_generation` 中一致。
  3. **反向验证**：把 `RagStartupSelfCheck` 的列校验方法**短路**（直接 return true）⇒ 断言 (1) 的用例**转红**；把 `application.yml` 的 dimension 改回 1024 ⇒ 断言启动自检**红**。
  4. **迁移纪律**：`deploy/sql/rag/18_*.sql` 必须在**同一事务**内完成"摘索引 → 改列 → 重建索引"，且脚本首行带 `⛔` 标注直到 e2e-index-generation 演练通过；`scripts/rag-it-env.sh --probe` 的期望列型断言同步更新。

### R-2（P0）：混合检索落地 —— BM25 + dense + rerank 三段式，与过滤顺序对齐

- **问题陈述**：当前混合检索是"**半失效**"的：
  - 融合方式 RRF 已实现且正确（`RRF_K=60`）；
  - 但**没有 rerank** ⇒ RRF 丢失的"分差信息"无处补偿 ⇒ 近邻负例（实测 0.525）压不住（`RAG-D05.md:193-196`）；
  - 且**词法通道实际只贡献排序、不贡献召回**——因 `ts_rank` 实测量级 0.068 << 门禁 0.38，"纯词法命中永远过不了 0.38"（`RAG-D05.md:197-199`）。这是**量纲混用**的后果：门禁作用在"各通道原始分的最大值"上，而 ts_rank 与 cosine 不同量纲。
  - 且**没有 query 改写**（Advanced RAG 的检索前处理整段缺失）。
- **方案设计**（三段式，严格遵守 D-4 的顺序纪律）：
  1. **阶段一：扩大召回 + 双通道分通道归一**。在 `JdbcRetrievalRepository` 中：
     - 稠密通道候选从 `topK * CANDIDATE_MULTIPLIER`（当前 3×）提升到 **可配置的绝对候选池**（新增 `rag.retrieval.candidate-pool-size`，默认 100），理由：重排需要足够大的候选池，且 D-4 的前提"过滤后仍 > 30 条"需要被真实满足；
     - 词法通道的分数做**通道内归一**（`ts_rank / max_ts_rank_in_channel`），使其与余弦同处 0~1 量纲 ⇒ 词法命中可独立过门禁。**归一必须在通道内做**（不能全局归一，因为两个通道的候选集不同）。归一后的分数仍记入 `relevance`（max 合并），保证门禁语义不变（"这条内容有多相关"），而 RRF 仍只用排名。
     - ⚠️ 铁律 2：`ts_rank` 的归一系数须走绑定参数（`?::real`），不得拼进 SQL 字符串。
  2. **阶段二：cross-encoder rerank（新增）**。新增端口与实现：
     - `rag/rag-ports/src/main/java/cn/studykid/rag/ports/RerankPort.java`：
       ```java
       public interface RerankPort {
           @NonNull List<RerankedChunk> rerank(@NonNull String query,
                                               @NonNull List<ScoredChunk> candidates,
                                               int topN);
           record RerankedChunk(@NonNull Chunk chunk, double score) {}
       }
       ```
     - `rag/rag-infrastructure/src/main/java/cn/studykid/rag/infrastructure/rerank/OpenAiCompatibleRerankPort.java`：走 OpenAI 兼容的 `/rerank`（Cohere 兼容协议），模型选型见下。
     - 装配：`rag/rag-bootstrap/application.yml` 新增 `rag.rerank.enabled`（默认 **false**，fail-closed 闸门）、`rag.rerank.base-url` / `api-key` / `model`（三者齐备才启用，缺任一 → 不装配且**不回落**到无重排，避免"以为在重排其实没有"）、`rag.rerank.top-n`（默认 20）。
     - **模型选型与成本**：
       | 候选 | 优势 | 成本 | 建议 |
       |---|---|---|---|
       | `bge-reranker-v2-m3`（BAAI，境内自部署） | 中文强（教辅语料）；无 API 费用；与我们的 BGE-M3 嵌入同源 | GPU 常驻；单条 query×20 候选约 20 次前向 | **推荐**（境内合规 + 中文场景） |
       | Cohere `rerank-v3.x` | 效果好、免运维 | 跨境数据出境（语料是版权敏感教育内容） | 谨慎；仅在脱敏样本上评估 |
       | OpenAI 兼容 `/rerank`（自托管 vLLM） | 可控不出境、可灰度 | 自维护 GPU | 备选 |
       | LLM rerank（ADK 的 `rag.LlmRanker` 式） | 无需专用模型 | 延迟与费用最高，且**不稳定的重排会破坏 D-4 的过滤前提** | 暂不采用 |
     - **位置纪律**：rerank 必须插在 `RetrievalOrchestrator` 的**④角色过滤之后、⑥排序之前**——即过滤先于重排（承 D-4），且 rerank 输出的分数**只用于排序**，不用于门禁（门禁仍作用在检索器给出的相关性分上，承 D-5）。**重排分数不得替换门禁分数**——否则重排模型的量纲会再次污染门禁（这正是 2026-10-23 那次事故的机理）。
  3. **阶段三：query 改写（可选，灰度）**。新增 `rag.retrieval.query-rewrite.enabled`（默认 false）与 `rag.retrieval.query-rewrite.mode`（`rewrite` | `hyde`）：
     - `rewrite`：LLM 把原 query 改写为更适合检索的形式（对标 LangChain `MultiQueryRetriever` 与 OpenAI `rewrite_query`）；
     - `hyde`：生成"假设答案"再对其做 embedding 检索（HyDE, arXiv:2212.10496）——**注意**：假设文档**只作检索锚**，绝不注入最终答案（防止幻觉进入引用链）。
     - 改写后必须**同时**保留原 query 的召回结果并做 RRF 合并（多路 query 展开），否则改写质量差时会**劣化**基线。
- **预期收益**（可度量）：
  - 近邻负例压制：可度量 = 评测集上"同体裁无关查询"的最高分从 0.525 降到 ≤ 0.45（口径：负例集分数 p95）；正例保留率不低于 100%（口径：正例集 Recall@10）。
  - 词法通道有效：可度量 = **纯词法命中（无稠密命中）的查询**在评测集上的 Recall@10 从当前"接近 0"提升到 ≥ 0.5（口径：按"命中来源 channel"分组的召回率，评测报告须按 channel 拆分）。
  - 混合融合权重可调：可度量 = `rrf` 参数与归一系数由配置驱动，改配置不需改代码（配置项清单在验收报告中列出）。
- **实施成本与风险**：
  - **成本**：阶段一中等（归一 SQL + 配置 + 候选池扩大，**候选池从 3× 扩到 100 会显著增加 DB 与 rerank 成本**）；阶段二高（新增端口 + 实现 + 部署模型 + 延迟预算）；阶段三中（新增 LLM 调用，延迟增加）。
  - **风险**：(a) **rerank 引入新的失败模式**——模型不可用时必须**明确降级**（打日志 + 走未重排路径 + 计数），**不得** fail-closed 阻断整个检索（与"嵌入不可用→503"的处理要区别：嵌入不可用则检索无意义，rerank 不可用只是排序退化）；(b) 扩大候选池会拉高 P95 延迟 → 需在 `HttpRagRetrievalGateway` 的 20s 读超时内留足预算，并观察超时率；(c) 归一化改变 `relevance` 的语义（从"原始分"变为"通道内归一分"）⇒ **必须同步重标 0.38 阈值**，且新旧阈值不可混用（承 D-6）。
- **优先级**：**P0**（因为它同时解决"词法通道半失效"和"近邻负例压不住"两个已量化的问题）。**排期**：阶段一与 R-3 的评测集**必须同批**（否则无法验证收益）；阶段二在真实嵌入（R-1 子项 4）落地后进行；阶段三最后。
- **验收门禁**：
  1. **单元**：`RerankPortTest`——构造 3 条候选，断言输出按 rerank 分降序、长度 = `topN`；`ChannelScoreNormalizerTest`——断言稠密分与归一后词法分都落在 [0,1]，且**归一不改变 RRF 排序键**（回归断言：归一前后 `fusionScore` 序列逐位相同）。
  2. **API/链路**：`HybridRetrievalIT`（真实 PG + 合成夹具 + 可注入的确定性 rerank 桩）——(a) 断言"仅词法可命中的查询"在门禁后**仍有命中**（当前必失败，这是本改进点的核心反证点）；(b) 断言 rerank 关闭时结果与 R-1 之前**逐位一致**（默认关闭不改行为）；(c) 断言 rerank 模型不可用时**降级但成功**且计数 +1。
  3. **反向验证**：(a) 把归一代码**短路**（直接返回原 ts_rank）⇒ 断言 (a) 的"仅词法命中"用例**转红**；(b) 把 `RetrievalOrchestrator` 的过滤步骤移到 rerank **之后** ⇒ 断言"答案类内容不在 rerank 输入里"的用例**转红**（D-4 的顺序纪律守卫）；(c) 把 rerank 分数误接到门禁上 ⇒ 断言"门禁恒不通过"的红灯用例转红（重演 2026-10-23 事故）。
  4. **回归门禁**：R-3 的评测 CI 门禁必须包含"混合检索 vs 纯稠密"的**对照指标**（Recall@10、MRR@10、按 channel 拆分的召回率），且新配置不得使任一指标低于基线的 95%。

### R-3（P0）：检索质量评测体系落地（指标 + 离线数据集 + CI 回归门禁）

- **问题陈述**：`RAG-D08.md:360-369` 定义了 RC-01 Recall@K（≥0.90）、RC-02 MRR@k（≥0.75）、RC-07 误拒率（≤0.10）、RC-08 漏拒率（**0%**，不变式）、RC-09 分数门禁一致性（**100%**，不变式），状态全为 "⬜ 未实现"。文档自陈"本片交付的是设计与约定，不是已生效的门禁"。后果已列在 5.3 盲区 2：阈值 0.38 仅 27 条样本标定且无回归机制；我们**不知道**词法通道的独立召回贡献；调参闭环断裂。
- **方案设计**（三层：数据集 / 实现 / 门禁）：
  1. **离线评测集**（人工密集，需排人力）：
     - 目录：`rag/rag-evaluation/src/test/resources/eval/`；
     - 数据格式（每行一条 JSON）：`{"qid":"q-0001","query":"二次函数的顶点怎么求","mode":"EXPLAIN","relevantChunkIds":["<uuid>"],"relevantDocumentIds":["<uuid>"],"expectEmpty":false,"edition":{...五轴...},"source":"teacher|student|auto"}`；
     - 规模建议：**≥ 200 条**正例 + **≥ 100 条**负例（跨领域负例 + 同体裁负例各半）+ **≥ 50 条**应拒答样本（`expectEmpty=true`）；按 `RAG-D08.md:143` 的原则标注："期望版本正确率 = 100%"必须**写死**在 case 里；
     - 标注来源：优先从**真实检索日志**反哺（`rag_access_audit` 已有 `RETRIEVE` 留痕，但需先做 R-7 补齐 `chunk_id`/`request_digest` 才有素材）。
  2. **指标实现**（新增模块 `rag/rag-evaluation`）：
     - `RetrievalMetricsCalculator`：实现 Recall@K（默认 K=10）、MRR@K、NDCG@K（若需，`RAG-D08` 未定义可先补）、**按 channel 分组的召回率**（`ScoredChunk.channel()` 已有 `DENSE_VECTOR`/`LEXICAL`/`KNOWLEDGE_POINT`/`PREREQUISITE_EXPANSION` 四个值，直接可分）、漏斗分布（`RetrievalFunnel` 五段）、门禁误拒/漏拒率；
     - `RagAsFaithfulnessEvaluator`（可选、对标 RAGAS arXiv:2309.15217）：对"检索 → 接地回答"链路算 **Faithfulness**（LLM 拆句 + 逐句判定）与 **Context Precision/Recall**。⚠️ **LLM-as-judge 的已知偏差必须处理**：位置偏差（Zheng et al., arXiv:2306.05685）→ 答案顺序对调跑两次取均值；自增强偏差 → judge 模型与生成模型**必须不同族**；prompt 必须**钉版本**。
     - ⚠️ **门禁必须可执行**：CI 中 `rag-evaluation` 的测试读取评测集与当前配置，输出指标报告；**任一指标低于阈值即失败**。
  3. **CI 分级门禁**（承 `RAG-D08.md:115` 的 G/R/O/M 四级）：
     - **G 类（不变式，阻断 PR）**：RC-08 漏拒率 = 0（应拒答的查询必须返回空——这是泄题闸门的镜像）；RC-09 分数门禁一致性 = 100%（门禁读相关性分而非 RRF 分）。
     - **R 类（回归，阻断 PR）**：RC-01 Recall@10 ≥ 阈值、RC-02 MRR@10 ≥ 阈值、RC-07 误拒率 ≤ 阈值。**阈值在首次实测标定后写死**（RAG-D08.md:114 要求"阈值只增不减，变更须升评测集版本、留痕、附放宽前后实测对比"——**注意"只增不减"对 Recall 是"只升不降"**，本报告按语义理解为"性能类阈值只升不降、拒绝类阈值只降不升"，实施时在 `RAG-D08` 中明确写死方向以消除歧义）。
     - **O 类（观察）**：按 channel 拆分的召回率、漏斗分布、P95 延迟——只出报告不阻断，但**连续 3 天下降 > 10% 触发告警**。
     - **M 类（人工阶段门禁）**：有用性、依据充分性、拒答恰当性（`RAG-D08.md:328` 的 C 档指标）。
  4. **阈值重标流程**：换嵌入模型、换语料、改门禁、改切分策略，任一发生 ⇒ 触发重标；重标必须留痕（评测集版本号 + 放宽/收紧前后对比）。
- **预期收益**（可度量）：所有阈值从"指示性标定"升级为"实测标定 + CI 强制"；词法通道贡献可量化；调参闭环打通（漏斗 + 按 channel 召回率 → 定位瓶颈）；R-1/R-2 的收益可被 CI 证明而非口头声称。
- **实施成本与风险**：
  - **成本**：**主要成本在人工标注**（≥350 条），这是本改进点与其他改进点的根本差异——它是**人力密集**而非代码密集。评测实现本身中等。CI 门禁接入需改 `rag/pom.xml` 加模块 + `pom.xml` 的 `verify` 阶段挂测试。
  - **风险**：(a) 评测集与线上分布偏离 ⇒ 指标好看但线上无改善 → 缓解：定期用线上真实 query 刷新评测集（R-7 的审计数据是素材来源）；(b) LLM-as-judge 不稳定 ⇒ 缓解：judge 与生成不同族 + prompt 钉版本 + 对调取均值；(c) 门禁阈值定得太严导致 CI 长期红 → 缓解：先跑 2 周"只观测不阻断"（O 类），用实测分布定阈值，再转 R 类。
- **优先级**：**P0**。**排期**：与 R-2 阶段一同批（否则无法证明 R-2 的收益）；R-1 的阈值重标依赖本项。
- **验收门禁**：
  1. **单元**：`RetrievalMetricsCalculatorTest`——用手算可验的小样例（3 候选 / 2 相关）断言 Recall@2 = 1.0、MRR@2 = 0.5；断言按 channel 分组的召回率计算正确。
  2. **链路**：`RetrievalEvaluationIT`（真实 PG + 评测集 + 真实配置）——输出完整指标报告（Recall@10 / MRR@10 / 误拒率 / 漏拒率 / 按 channel 召回率 / 漏斗五段分布）；断言报告中**每个指标都有阈值与实测值两列**（缺一即失败，防止"只报不判"）。
  3. **反向验证**：(a) 把门禁阈值改成 0.99 ⇒ 断言 RC-07 误拒率红灯用例**转红**；(b) 把 `RetrievalOrchestrator` 的门禁改用 `fusionScore` ⇒ 断言 RC-09 一致性**转红**；(c) 人为把评测集某条正例的 `relevantChunkIds` 清空 ⇒ 断言 Recall 下降且**红灯**（证明指标真的在算，不是恒真）。
  4. **CI 门禁**：新增 `rag-evaluation` 的测试在 `mvn verify` 阶段执行，退出码非 0 即阻断合并；门禁报告以 `rag-evaluation/target/retrieval-metrics.md` 落盘（含阈值、实测值、结论、评测集版本）。

### R-4（P1）：多租户检索谓词的统一注入收口（关联维度 2 的 `rlsDependent`）

- **问题陈述**：当前检索侧谓词注入已经**相当好**——`RagSqlScopeTranslator` 单点产出、6 处 SELECT 共用、稠密与词法两侧同源（这是维度 2 判定 L3 的依据）。但仍有三个收口缺口：
  1. **M4 未完成**：`RlsDependencyProbe.EXPECTED` 仍为 30（R1=6/R2=6/R3=16/CENTRAL=2），四批 SQL 全 `⛔DRAFT`；M2 观察期缺真实流量信号（MEMORY："M2 影子 infra 已开 `RAG_SHADOW_ENABLED=true`（差真实流量）"）。
  2. **写侧仍有 set_config 站点**：R1 批的 6 处（`JdbcCorpusRepository` + `JdbcTenantScopedRetrieval`）按 `14_v1_0_4_r1_rls_retirement.sql:79-81` 的清单应随 R1 退役移除，但**代码尚未改**。
  3. **判空先绑租户的纪律未成守卫**：`M3` 的显式谓词已就位，但若某处查询漏写谓词，退役 RLS 后会**静默返回全量数据**（比现在"返回 0 行"更危险——现在是 0 行，退役后是越权）。**目前没有任何守卫能发现"某查询漏了显式谓词"**（`RagRetrievalScopeGuardTest` 覆盖的是已有谓词的查询，不是"是否有谓词"）。
- **方案设计**：
  1. **零谓词守卫（新增，最高性价比）**：新增 `rag/rag-persistence/src/test/java/.../retrieval/RetrievalPredicateCoverageIT.java`——对每条检索相关 SQL 路径，用**两个租户的数据**跑一次，断言"租户 A 的检索结果不含租户 B 的 chunk_id"，且断言"该路径的 SQL 含 `scopeTranslator.render()` 产物"。
     - 更强的形式：**元数据扫描守卫**——扫描 `rag-persistence/src/main/java` 的全部 `SELECT`，凡涉及 `rag_chunk`/`rag_chunk_embedding`/`rag_document`/`rag_source` 的 SELECT，断言其 SQL 文本含 `c.tenant_id`（或经 translator 渲染的等价标记）。**这是静态守卫，按铁律 17 必须配真实交互驱动**：静态扫描负责"发现漏写"，IT 负责"证明不漏写"。
  2. **R1 代码改动落地**：按 `14_v1_0_4_r1_rls_retirement.sql:79-81` 的清单移除 6 处 `set_config`（`JdbcCorpusRepository` 的 5 处 + `JdbcTenantScopedRetrieval` 的 1 处），同步把 `RlsDependencyProbe.EXPECTED` 的 R1 从 6 递减、`RlsDependencyMetricTest` 的批次映射同步（该测试**自动**转红提醒，改 `EXPECTED` 即过）。
  3. **混合检索下的谓词一致性专项断言**（R-2 引入归一与候选池扩大后**必须**有）：断言稠密通道与词法通道在**同一次**检索中返回的 chunk_id 集合**都**是"本租户 ∪ 哨兵"的子集——这是"混合检索下谓词同时约束两侧"的直接验收。
  4. **观察期出口门禁**：复用维度 2 的 `ShadowExitGateEvaluator` 思路，为 RAG 侧增加等价门禁——`rlsDependent_rate == 0` 且 `mismatches == 0` 且 `taintViolations == 0` **连续 14 天** + TL/QA 签核，方可放行 M4-R1 的 `COMMIT`。
- **预期收益**：退役 RLS 的安全性从"信任等价性推理"变为"**门禁强制**"；混合检索引入新代码路径时，隔离不会因为"新分支忘了加过滤器"而破。可度量 = 零谓词守卫在人为删掉一处 translator 调用时**转红**；`rlsDependent` 随 R1 落地从 30 → 24 → … → 0。
- **实施成本与风险**：
  - **成本**：守卫小；R1 代码改动小（6 处）；观察期**需要真实生产流量 14 天**（不可加速，是硬约束）。
  - **风险**：(a) **顺序风险（铁律/SQL 注释已警告）**：必须**先**退役 RLS（跑 SQL）**再**改代码移除 set_config——反序 Sev-0（"应用移除 set_config 后 RLS 仍在场 ⇒ `rag_current_tenant()` 返回 NULL ⇒ FORCE RLS 静默过滤 ⇒ 租户行 0 可见"）；(b) 移除 set_config 后，依赖 GUC 的其他路径（如 `rag_section` 等仍属 CENTRAL 批的读）会断 ⇒ 必须先确认那些路径已被显式谓词承重（`17_...sql:19-20` 明确要求"确认 rag_section 等原本依赖 CENTRAL GUC 的读路径，已由 M3 显式谓词独立承重，再删 GUC"）；(c) 零谓词静态守卫可能误报（动态拼装的 SQL）→ 缓解：静态扫描仅作提示，最终以 IT 为准（铁律 17）。
- **优先级**：**P1**（M4 的门禁由 M2 观察期决定，本项是"在观察期之前把守卫建好"）。**排期**：与 M2 观察期并行；R1 代码改动在观察期达标 + TL/QA 签核后。
- **验收门禁**：
  1. **单元/静态**：`RetrievalPredicateCoverageIT` 的静态部分——扫描并断言每条涉 `rag_chunk` 的 SELECT 含显式租户谓词；**反向验证**：人为删掉 `lexicalSearch` 的一处 translator 调用 ⇒ 断言**转红**。
  2. **API/链路**：双租户数据 IT——断言稠密与词法**两侧**返回的 chunk 集合都 ⊆ 本租户 ∪ 哨兵；断言哨兵桶的全局 chunk 对每个租户**都**可见（`RetrievalRepositoryIT.globalChainIsRecalledByEveryTenant` 已有该用例，保留）。
  3. **反向验证**：把 `RagSqlScopeTranslator.render` 的 `TenantWithGlobal` 分支**改成** `TenantOnly` ⇒ 断言"全局桶对每租户可见"的 IT **转红**（证明谓词真的在起作用）。
  4. **观察期出口**：`RagShadowExitGateEvaluatorTest`——注入"连续 14 天 rlsDependent=0 且 mismatch=0" ⇒ 断言 `eligible-for-M4=true`；注入任一非空 ⇒ 断言 `false`。**该评估器是 M4 SQL 从 `ROLLBACK` 改 `COMMIT` 的唯一放行凭据**。

### R-5（P1）：摄取幂等与增量更新（文档版本、chunk 重建、embedding 重算）

- **问题陈述**：当前摄取的去重是**载体级**的——`IngestDedupRules` 定义"活跃"谓词（排除 `ver_status='REVOKED'` 与 `parse_status='FAILED'`），应用层预检（`IngestDedupPrecheck`）与 DB 部分唯一索引（`rag_document_tenant_content_hash_uq`）共用同一口径。**但**：
  1. **文档级没有版本概念**：`rag_document` 无"版本/修订号"列，无法表达"同一本教材的第二版"；重传同一本（内容相同）会被拒（400），重传修订版会被当作**新载体**（新 `document_id`），旧载体的 chunk **不会被清理**。
  2. **chunk 级没有幂等键**：chunk 的唯一身份是 `ChunkIdentifiers.chunkId(documentId, pageNumber, charStart)` + `content_hash`；但 `rag_chunk` 表**没有** `content_hash` 的唯一约束——`content_hash` 是普通列（`01_rag_ddl.sql:342`），只用于比对，不用于幂等。
  3. **embedding 重算无触发路径**：`rag_chunk_embedding` 的写入是 `upsert`（`JdbcCorpusRepository.upsertEmbedding`），但**何时需要重算**由 `ChunkIndexer` 的"待索引"判定决定；换嵌入模型走代际蓝绿（D-10）——这是对的，但**同一代际内**因 chunk 文本变更而需重算 embedding 的路径**没有显式机制**。
  4. **摄取失败的残留**：编排器明确"**不做补偿删除**"（`IngestJobOrchestrator.java:34-37`）——整册失败可能残留部分片段（虽然 `retrievable=false` 使其不进入检索，但数据在库里、且"载体级撤回"若被误操作会放行）。
  5. **重跑入口不完整**：`ReindexRunner` 有租户通道与全局通道（`ChunkIndexer.indexGlobalCorpus`），但**没有**"重跑某个 document 的摄取"的入口（管理面只有"重跑索引"）。
- **方案设计**：
  1. **新增文档修订维度**：`rag_document` 增列 `revision_no integer NOT NULL DEFAULT 1` 与 `superseded_by uuid`（可空），并加部分唯一索引 `rag_document_tenant_source_revision_uq ON rag_document (tenant_id, source_id, revision_no)`。
     ```sql
     -- deploy/sql/rag/19_v1_0_6_document_revision.sql（新增）
     ALTER TABLE rag.rag_document
       ADD COLUMN IF NOT EXISTS revision_no integer NOT NULL DEFAULT 1,
       ADD COLUMN IF NOT EXISTS superseded_by uuid;
     CREATE UNIQUE INDEX IF NOT EXISTS rag_document_tenant_source_revision_uq
       ON rag.rag_document (tenant_id, source_id, revision_no);
     ```
     ⚠️ 铁律 7：新表同步 `03_platform_roles.sql`（本项不改表只加列，无需改角色；但须同步 `deploy/sql/README.md` 迁移链与 `scripts/rag-it-env.sh --probe` 期望值）。⚠️ 铁律 11：改列在事务内，`ADD COLUMN IF NOT EXISTS` 与建索引同事务。
  2. **chunk 级幂等**：给 `rag_chunk` 加唯一约束 `(tenant_id, document_id, content_hash)`——注意**这会让同文档内内容完全相同的两个 chunk 冲突**（教辅里"例题"重复出现是常见的）⇒ 因此**不能**直接加；改为在 `ChunkIdentifiers` 中把 `char_start` 纳入身份（**已经是**：`chunkId(documentId, pageNumber, charStart)`），并**新增** `rag_chunk` 的 `chunk_id` 幂等写入语义：`saveChunkAndEmbedding` 改为 `INSERT ... ON CONFLICT (chunk_id) DO UPDATE`（需 UPDATE 授权 + SchemaDriftIT，承铁律 7）。这样"重跑同一份切分结果"是幂等的。
  3. **embedding 重算触发**：在 `ChunkIndexer` 的"待索引"判定中增加一条——"该 chunk 的 `content_hash` 与 `rag_chunk_embedding` 记录对应版本的 `embed_model` 不匹配 ⇒ 需重算"。实现上需要 `rag_chunk_embedding` 增列 `content_hash`（记录该向量对应的文本指纹），这样重算判定不必 join 回 `rag_chunk`。
     ```sql
     ALTER TABLE rag.rag_chunk_embedding
       ADD COLUMN IF NOT EXISTS content_hash character(64);
     ```
  4. **修订切换的原子性**：新增管理端操作"发布修订版"——在同一事务内：写新 document（`revision_no = 旧 + 1`）→ 标记旧 document `superseded_by = 新 id` → 旧 document 的 chunk 标记 `retrievable = false`（**软失效，不物理删除**，承铁律 8"无物理删除端点"）→ 旧 document 的 embedding 标记对应代际 RETIRED（若已换代际）。
  5. **重跑单个载体的入口**：在 `rag-management` 的 `RagManagementController` 增加 `POST /api/v1/management/sources/{sourceId}/documents/{documentId}/reingest`——复用 `IngestJobOrchestrator` 的同一路径（幂等由 (2) 保证），并在 `rag_ingest_job` 上留痕（`IngestJob.attempt` 已有）。
- **预期收益**：摄取从"一次性"变成"可重复、可增量"；换嵌入模型可走**增量**（只重算 content_hash 变的 chunk）而不必全量重建。可度量 = 同一 document 连续摄取两次后 `rag_chunk` 行数不变（幂等）；换模型后重算的 chunk 数 = content_hash 变化的 chunk 数（增量率，报告口径）。
- **实施成本与风险**：
  - **成本**：DDL 中（2 个 ALTER + 2 索引）；代码中（幂等 upsert + 重算判定 + 修订发布操作 + 重跑端点）。
  - **风险**：(a) `ON CONFLICT DO UPDATE` 需 UPDATE 授权 + SchemaDriftIT（铁律 7 明列）；(b) 幂等 upsert 若与并发摄取冲突可能"后写覆盖前写"——缓解：`rag_ingest_job` 的 `FOR UPDATE SKIP LOCKED` 领取保证同租户同 job 不并发；(c) 修订切换的原子性——必须在**单事务**内完成 4 步，任一失败整体回滚（铁律 11：事务内 SQL 失败禁 fail-soft 后 COMMIT）；(d) `content_hash` 增列后旧行为 NULL——判定时须把 NULL 视为"需重算"（宁可多算，不可漏算）。
- **优先级**：**P1**。**排期**：R-1 子项 3（列迁移）之后（避免两个 DDL 变更窗口重叠）。
- **验收门禁**：
  1. **单元**：`ChunkIdentityTest`——同 document 同 charStart 两次切分 ⇒ 断言 `chunk_id` 相同；断言 `content_hash` 相同 ⇒ 判定为"不需重算"；`content_hash` 不同 ⇒ 判定为"需重算"。
  2. **链路**：`IngestIdempotencyIT`——对同一 document 连续跑两次 `ingest()` ⇒ 断言 (a) `rag_chunk` 行数不变、(b) `rag_chunk_embedding` 行数不变、(c) `rag_chunk_embedding.content_hash` 与 `rag_chunk.content_hash` 相等。**反向验证**：把 upsert 改回纯 INSERT ⇒ 断言第二次摄取**报唯一约束冲突**（红才对）。
  3. **修订切换**：`DocumentRevisionIT`——断言发布修订版后：旧 document 的 `superseded_by` 指向新 document；旧 document 的 chunk 全部 `retrievable = false`；`RetrievalRepositoryIT.withdrawnCarrierIsNotRetrieved` 的等价断言（改后的文档不进入检索结果）；**且旧数据仍在库中**（承铁律 8：无物理删除）。
  4. **事务纪律**：人为让修订切换的第 3 步失败 ⇒ 断言前 2 步也回滚（`superseded_by` 未写入），**不得**出现"新文档已生效但旧文档仍可检索"的中间态。

### R-6（P1）：影子模式扩展为"检索策略双跑比对 + 自动切流报表"

- **问题陈述**：`RagShadow` 的比对对象是**隔离谓词**（显式谓词 vs RLS），**不是检索策略**。没有"新旧策略双跑 → 出准确率/召回/成本对比 → 自动判定是否切流"的闭环。而这恰恰是 R-2（换检索策略）最需要的**安全网**——没有它，我们改一次检索策略就是一次没有反馈的赌博。
- **方案设计**（复用现成的影子原语，新增一层"策略维度"）：
  1. **新增策略维度采样点**：在 `RetrievalOrchestrator.retrieve()` 增加双跑分支——当 `rag.shadow.strategy.enabled=true` 且采样命中时，**同时**跑"当前策略"与"对照策略"，各自产出结果集，交给新的 `RagStrategyComparator` 比对。
     - 对照策略的来源：**可插拔**——`rag.shadow.strategy.baseline`（基线快照：把某次生产检索的候选集与分数**存下来**做基线，避免重跑旧策略需要保留旧代码）与 `rag.shadow.strategy.variant`（变体：配置驱动的策略变体，如"候选池 100 vs 30"、"有无 rerank"、"有无 query 改写"）。
     - ⚠️ 关键设计：**基线快照法**优于"双跑旧代码"——后者要求旧代码一直可编译可运行（旧代码一改就失效），前者只需存下结果快照。新增表：
       ```sql
       -- deploy/sql/rag/20_v1_0_7_retrieval_shadow.sql（新增）
       CREATE TABLE IF NOT EXISTS rag.rag_retrieval_shadow (
           shadow_id     uuid DEFAULT gen_random_uuid() NOT NULL,
           tenant_id     uuid NOT NULL,
           request_digest character(64) NOT NULL,
           strategy_id   text NOT NULL,          -- 'baseline' | 变体标识
           variant       jsonb NOT NULL,          -- 策略参数快照
           hits          jsonb NOT NULL,          -- [{chunkId, score, channel}]
           funnel        jsonb NOT NULL,          -- 五段漏斗
           cost_ms       integer NOT NULL,
           created_at    timestametz NOT NULL DEFAULT now(),
           CONSTRAINT rag_retrieval_shadow_pk PRIMARY KEY (shadow_id),
           CONSTRAINT rag_retrieval_shadow_scope_ck CHECK (
               (scope = 'PLATFORM' AND tenant_id = rag.rag_global_tenant_id())
               OR (scope = 'TENANT' AND tenant_id <> rag.rag_global_tenant_id()))
       );
       ALTER TABLE rag.rag_retrieval_shadow ENABLE ROW LEVEL SECURITY;
       ALTER TABLE rag.rag_retrieval_shadow FORCE ROW LEVEL SECURITY;
       CREATE POLICY rag_retrieval_shadow_read ON rag.rag_retrieval_shadow
           FOR SELECT USING (tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()]));
       ```
       ⚠️ 铁律 7：新表须同步 `03_platform_roles.sql`（授权）——**rag 侧对应 `deploy/sql/rag/03_rag_roles.sql`**；`ON CONFLICT DO UPDATE` 若用到需 UPDATE 授权 + SchemaDriftIT。
  2. **比对指标**：`RagStrategyComparator` 计算——**重合度**（Jaccard @K）、**排名位移**（baseline 与 variant 的 hit 排名平均绝对位移）、**分数分布差异**（p50/p95）、**漏斗差异**（哪一段的候选数不同）、**成本差异**（`cost_ms`）。判定规则（**默认只观测不阻断**，与既有影子语义一致）：
     - `overlap@10 < 0.6` ⇒ 告警"策略变更影响面过大"；
     - 变体的 `funnel.门禁后` 显著低于基线 ⇒ 告警"召回退化"；
     - 变体 `cost_ms` P95 增幅 > 50% ⇒ 告警"成本上升"。
  3. **自动切流判定报表**：新增 `rag/rag-management` 的报表端点 `GET /api/v1/management/retrieval/shadow/report`，按策略变体聚合，输出：样本数、overlap@10 分布、漏斗差异、分数分布差异、成本对比（P50/P95）、**建议判定**（`KEEP_CURRENT` / `CUT_OVER` / `NEEDS_REVIEW`）。**判定逻辑必须显式写死阈值**（例如：样本数 ≥ 200 且 overlap@10 p50 ≥ 0.85 且变体 Recall@10 ≥ 基线 且成本增幅 ≤ 20% ⇒ `CUT_OVER`），并把阈值写在配置里可调。
  4. **与隔离影子的关系**：两套影子**并存但正交**——隔离影子（现有）测"谓词 vs RLS"，策略影子（新增）测"策略 A vs 策略 B"。二者共用 `RagShadow` 的采样与"只观测不阻断"原语，但用不同的 resourceType 命名空间（`retrieval` vs `retrieval.strategy`）以免指标混淆。
  5. **接入 M2 观察期**：策略影子默认**关闭**，在 R-2 的策略变更窗口内**按变体开启**，观察 ≥ 200 样本后再评审切流。切流本身仍走平台侧的灰度机制（`deployment.pilot.max-tenants`，见维度 12），**不新增切流原语**（避免第二套灰度）。
- **预期收益**：检索策略变更从"赌博"变成"有对照的实验"；切流判定有**可复算的报表**而不是"老大手感"。可度量 = 每次策略变更都产出一份对比报表（样本数 / overlap / 漏斗差异 / 分数分布 / 成本），且 `CUT_OVER` 判定完全由配置阈值决定（可复算）。
- **实施成本与风险**：
  - **成本**：DDL 中（1 表 + 3 策略 + 索引）；代码中（采样点 + 比较器 + 报表端点 + 判定阈值配置）。
  - **风险**：(a) **存储成本**——每次检索存一份命中快照，按 QPS 估算体积；缓解：采样率可配（复用 `rag.shadow.sample-rate`）+ 保留期 + 只在策略变更窗口开启（默认关闭）；(b) **双跑成本翻倍**——影子期内检索耗时上升；缓解：采样而非全量；(c) **基线快照的时效性**——快照是历史分布，语料变化后基线可能过时；缓解：基线快照带 `created_at`，报表标注快照年龄，超过阈值标 `NEEDS_REVIEW`；(d) 新表必须挂 RLS（铁律：RAG 侧表全部 `FORCE ROW LEVEL SECURITY`），否则影子表本身成为越权读源。
- **优先级**：**P1**。**排期**：R-2 阶段二/三落地**之前**（先有网，再改网）。
- **验收门禁**：
  1. **单元**：`RagStrategyComparatorTest`——构造两组已知结果集，断言 Jaccard / 排名位移 / 漏斗差异的计算正确；断言判定函数在"样本不足"时返回 `NEEDS_REVIEW`（不返回 `CUT_OVER`）。
  2. **链路**：`RagStrategyShadowIT`——开启策略影子跑 N 次检索，断言 `rag_retrieval_shadow` 行数 = 采样命中次数（不是全部次数）；断言报表端点返回的 `overlap@10`、`funnelDiff`、`costP95` 三项均有值；断言**默认关闭时该表行数恒为 0**（零行为变更）。
  3. **反向验证**：(a) 把判定阈值改成"恒 `CUT_OVER`" ⇒ 断言 `NEEDS_REVIEW` 分支的用例**转红**；(b) 把采样率改成 0 ⇒ 断言影子表行数为 0 且**主检索仍成功**（影子绝不影响主链路）；(c) 把影子开关在 `block-on-violation=true` 下触发违例 ⇒ 断言**主检索不受影响**（影子只观测不阻断）。
  4. **隔离复核**：用 `app_agent`（NOBYPASSRLS）+ 另一个租户身份查询 `rag_retrieval_shadow` ⇒ 断言返回 **0 行**（铁律 1：验隔离必须用 NOBYPASSRLS 角色，BYPASSRLS 是假绿）。

### R-7（P2）：检索审计补齐取证字段，并作为评测集反哺来源

- **问题陈述**：`rag_access_audit` 的 schema 已预留 `chunk_id` / `source_id` / `request_digest` 三列（`01_rag_ddl.sql:281-292`），但检索路径的 INSERT **只写 4 列**（`JdbcTenantScopedRetrieval.java:62-66`：audit_id/tenant_id/principal_id/action），`chunk_id`/`request_digest` **恒为 NULL**。后果：(a) 无法回答"检索到了什么、依据是哪一段"；(b) 无法用真实查询 + 真实命中反哺 R-3 的评测集；(c) `request_digest` 有 `character(64)` 约束却从不写入，是明显的"schema 已就位、实现未跟上"。
- **方案设计**：
  1. **补齐写入**：在 `JdbcTenantScopedRetrieval.retrieve()` 的审计 INSERT 中补 `chunk_id`（取 top-1 命中）与 `request_digest`（`SHA-256(tenantId + "\u0000" + query + "\u0000" + mode + "\u0000" + edition坐标)` 的十六进制）。⚠️ 铁律 2：`INSERT…RETURNING` 需 SELECT 且走 executeQuery；本处**不需要 RETURNING**，故用 `update` 即可；⚠️ 铁律 8：**只存摘要不存原文**——`request_digest` 是哈希，不含语料内容，符合"risk_event 只存摘要"的精神。
  2. **多命中留痕**：若需记录全部命中，应新增明细表而非在 `rag_access_audit` 里塞数组（本表是"一次动作一行"语义）。建议 `rag_retrieval_hit` 表（`retrieval_id, chunk_id, rank, score, channel`），与 `rag_access_audit` 通过 `audit_id` 关联。
  3. **评测集反哺管道**：新增脚本 `scripts/rag-eval-harvest.sh`（或 Java 工具），从 `rag_access_audit` + `rag_retrieval_hit` 导出候选 query 与命中，**脱敏后**进入人工标注队列（教师/审核员），标注结果回填 `rag/rag-evaluation/src/test/resources/eval/`。⚠️ 合规：导出的 query 是用户输入，必须走脱敏（复用 `DataClassification.IdentifyingPattern` 的 PII 黑名单，铁律 21：含 lookbehind 的正则必须留 JDK 引擎）。
  4. **保留期与清理**：`rag_access_audit` 的清理走受控清理通道（承 `forbid_audit_mutation` 的既有纪律：UPDATE 永远拒、DELETE 仅受控）；本项不引入物理删除端点（铁律 8）。
- **预期收益**：检索行为**可取证**（合规审计"谁检索了哪一段"）；评测集有了真实分布来源（缓解 R-3 的"评测集与线上分布偏离"风险）。可度量 = 审计表的 `chunk_id`/`request_digest` 非空率 = 100%（对走完的检索）；每次评测集扩充能引用 ≥ 30% 来自真实日志的样本。
- **实施成本与风险**：
  - **成本**：小（写入补 2 列 + 1 张明细表 + 1 个导出工具）。
  - **风险**：(a) **合规风险**——`request_digest` 必须是**哈希**而非原文（否则审计表变成语料副本）；必须加断言；(b) 写入性能——每检索多一次哈希计算（SHA-256 对短字符串可忽略）；(c) 明细表的数据量增长 ⇒ 需保留期策略，且保留期清理必须走受控通道；(d) `chunk_id` 只记 top-1 会**低估**实际影响面 ⇒ 建议同时记明细表（R-7.2）。
- **优先级**：**P2**。**排期**：R-3 的评测集扩充阶段（评测集是最直接的受益方）。
- **验收门禁**：
  1. **单元**：`RequestDigestTest`——断言同 query 两次调用 digest 相同、query 改一个字符 digest 变化、digest **长度恒为 64** 且**不含原文子串**（用"原文中的任意 4 字连续片段都不出现在 digest 中"作为断言）。
  2. **链路**：`RetrievalAuditIT`——跑一次检索后断言 `rag_access_audit` 该行的 `action='RETRIEVE'`、`chunk_id` 非空、`request_digest` 长度为 64；断言**审计写失败时整次检索回滚**（承 D-8：把 INSERT 改成违反约束 → 断言检索异常）。
  3. **反向验证**：把 digest 实现改成"存原文" ⇒ 断言"不含原文子串"的用例**转红**。
  4. **导出脱敏**：在导出脚本的测试里塞入含手机号/身份证号的 query ⇒ 断言导出文件中**不含**这些串（承铁律 21：脱敏必须用 JDK 引擎的 PII 正则）。

### 7.11 深度补充：R-0~R-7 的改动片段 / 三级验收门禁 / 回滚策略（逐条可执行）

> 本节把第七章每个改进项落到"改哪一行、怎么验、怎么退"。**三级验收门禁**严格按项目铁律 6：单元（mock/纯函数）→ API（真实 HTTP/真实 DB）→ 角色×任务（按 STUDENT/TEACHER/平台运维 + 真实任务链路）。每项必须回答灵魂问题「**撤掉修复点会红吗**」——若只有静态守卫变红而行为测试仍绿，视为无效门禁（P0 踩过此坑）。完整的改动清单、铁律对齐与反向验证另见**附录 E**。
>
> ⚠️ **RAG 红线复述（先于一切改动）**：⛔ 不调低 0.38（标定值，须走 RAG-D08 §4）；⛔ 不动 RRF k=60 与"排序键=fusionScore / 门禁键=相关性分"分离（`RetrievalRepositoryIT` 钉死）；⛔ 不动六轴准入谓词；⛔ 不在不可达的 agent-infra 草稿上施力；⛔ 不夹带改 `halfvec(4096)`/HNSW。**R-1 原稿"硬降到 4000 维建 HNSW"属已被复核纠偏的旧结论**——4096/halfvec 是 `ddl:960` + `rag.env:44-46` + `VectorColumnTest` 共同钉住的刻意取舍，规模化正道是 MRL / 二进制量化（pgvector 官方，2.4(13)），禁止硬降维。

#### R-0 清除不可达草稿检索引擎

- **具体改动片段**：删除 `platform/agent-infra/src/main/java/.../retrieval/engine/` 整个目录（`RetrievalEndpoint`/`RetrievalService`/`VectorSearchEngine`/`TfIdfRanker` + 2 测试类），从 `platform/pom.xml:66` 移除 `<module>agent-infra</module>` 与 `:125` 的 dependencyManagement 条目，删除 `agent-infra/pom.xml`；修正 `docs/能力维度实现方式梳理报告.md:123` 的 0.75/hybrid 表述为 rag 真实口径（0.38 / RRF k=60 / 占位对象是嵌入模型）。
- **三级验收门禁**：
  - 单元：`RetrievalAuthoritySourceOfTruthTest`——断言全仓 main 侧无 `VECTOR_SIMILARITY_THRESHOLD`/`TTL_MAX_AGE_MINUTES`；断言 `platform/pom.xml` 不含 `<module>agent-infra</module>`。
  - API（真实启动）：起 platform-bootstrap 后 `GET /api/v3/retrieval/workspace` 返回 **404**（非 500，500=残留装配）。
  - 角色×任务：平台运维角色用真实检索链路（维度 6 Agent 对话）验证 RAG 命中仍经 rag 服务返回，`agent_trace_metric` 有 ragHits 计数（不因删草稿而断链）。
  - **撤掉修复点会红吗**：把 `agent-infra` 加回 `<modules>` 并恢复一个含 `VECTOR_SIMILARITY_THRESHOLD` 的类 → 单元扫描断言转红；若端点意外仍在 → API 404 断言转红。✅ 有效（行为级 + 启动级）。
- **回滚策略**：恢复被删目录与 pom 条目即可（草稿无生产调用方，回滚零风险）；文档口径保持修正后版本（避免回退到污染表述）。注意模块数 14→13，同步断言模块清单的守卫（铁律 6）。

#### R-1 向量打分闭环（占位嵌入 → 真实嵌入 + 端到端可度量）

- **具体改动片段**（⚠️ 已剔除被纠偏的"硬降维建 HNSW"子项）：① 在 `RagStartupSelfCheck` 增加**列侧校验**——读 `pg_attribute` 拿 `rag_chunk_embedding.embedding` 实际类型/维度，与 `VectorColumn` 比对，不符拒绝启动（补"配置 vs DB 实际列"参照系）；② 把 `rag.env:44-46` 的 `4096/halfvec` 口径固化进 `application.yml` 默认并 worker 侧同源（不改 DDL、不降维）；③ 配 `RAG_EMBEDDING_BASE_URL`+`MODEL` 让 `EmbeddingPorts.create` 走 `OpenAiCompatibleEmbeddingPort`（缺 model 时已 fail-closed）；④ 新增 `RealEmbeddingRetrievalIT`（真实模型+真实 PG）断言 score 非常量、0.38 门禁生效、换模型能建新代际。
- **三级验收门禁**：
  - 单元：`RagStartupSelfCheckTest`——列 mock 成 `vector(1024)` 而配置 `halfvec(4096)` ⇒ 断言拒绝启动；一致 ⇒ 通过。
  - API（真实 PG+模型）：`RealEmbeddingRetrievalIT`——断言 `scoreStdDev>0.05`（非常量）、0.38 门禁在真实模型下生效、`embedding_model` 在 embedding 表与代际表一致。
  - 角色×任务：TEACHER 用真实教材检索，验证"依据"分数可信、换 `qwen3-embedding:8b` 后新代际零停机切换。
  - **撤掉修复点会红吗**：把列校验短路（return true）→ 单测拒绝启动断言转红；改回占位嵌入 → `scoreStdDev>0.05` 转红。✅ 有效。
- **回滚策略**：`enabled`/`base-url` 置空回到占位嵌入（fail-closed 闸门保证不静默）；列校验方法保留（无害）；**不改 DDL/维度**——一旦误降维须走代际蓝绿回滚并恢复 4096 维向量。规模化 ANN 走 MRL/二进制量化代际迁移（非本项）。

#### R-2 混合检索三段式（BM25 + dense + rerank，与过滤顺序对齐）

- **具体改动片段**：① 阶段一——`JdbcRetrievalRepository` 新增可配 `rag.retrieval.candidate-pool-size=100`，词法通道**通道内归一**（`ts_rank/max_ts_rank`，走 `?::real` 绑定参数，铁律 2），归一后仍记入 `relevance`（门禁语义不变）；② 阶段二——新增 `RerankPort` + `OpenAiCompatibleRerankPort`（或境内 `bge-reranker-v2-m3`），`rag.rerank.enabled=false` 默认关，插在 `RetrievalOrchestrator` ④角色过滤**之后**、⑥排序之前（承 D-4），rerank 分**只排序不入门禁**（承 D-5）；③ 阶段三——可选 query 改写/HyDE（默认关，改写与原文召回 RRF 合并）。
- **三级验收门禁**：
  - 单元：`RerankPortTest`（按 rerank 分降序、长度=topN）、`ChannelScoreNormalizerTest`（归一前后 `fusionScore` 序列逐位相同——归一不改 RRF 排序键）。
  - API（真实 PG+确定性 rerank 桩）：`HybridRetrievalIT`——"仅词法可命中的查询"门禁后仍有命中（当前必失败，核心反证点）；rerank 关闭时结果与基线逐位一致；rerank 模型不可用时**降级但成功**且计数+1。
  - 角色×任务：STUDENT 查"二次函数顶点"验证近邻负例（同体裁无关 0.525）被压下、正例 Recall@10 不降。
  - **撤掉修复点会红吗**：归一短路（直接返原 ts_rank）→ "仅词法命中"用例转红；把过滤移到 rerank 后 → "答案类不进 rerank 输入"用例转红；把 rerank 分接门禁 → "门禁恒不通过"红灯用例转红（重演 2026-10-23 事故）。✅ 有效。
- **回滚策略**：`rag.rerank.enabled=false` / `query-rewrite.enabled=false` 一键回基线；归一是行为改变，回滚需恢复原 `relevance` 计算并重标 0.38；候选池配置化可单独调小。灰度先单租户。**前置硬依赖 R-3 评测集**（Qdrant 官方："无评测集别手调权重"）。

#### R-3 检索质量评测体系（指标 + 离线数据集 + CI 门禁）

- **具体改动片段**：新增模块 `rag/rag-evaluation`；`RetrievalMetricsCalculator` 实现 Recall@K/MRR@K/NDCG@K + 按 channel 分组召回率（`ScoredChunk.channel()` 四值直接可分）+ 漏斗分布 + 误拒/漏拒率；评测集 JSONL `{"qid","query","relevantChunkIds","expectEmpty","edition",...}` ≥200 正例 + ≥100 负例 + ≥50 应拒答；CI 四级门禁 G（漏拒率 0/门禁一致性 100%，阻断）/ R（Recall@10/MRR/误拒率，阻断）/ O（观察）/ M（人工）。
- **三级验收门禁**：
  - 单元：`RetrievalMetricsCalculatorTest`（3 候选/2 相关手算样例：Recall@2=1.0、MRR@2=0.5）。
  - 链路（真实 PG+评测集）：`RetrievalEvaluationIT` 输出完整指标报告，断言**每个指标都有阈值与实测值两列**（缺一即失败，防"只报不判"）。
  - 角色×任务：TEACHER 标注样本进入评测集，运维角色在 CI 看板验证 G/R 类阻断 PR。
  - **撤掉修复点会红吗**：阈值改 0.99 → RC-07 误拒率红灯转红；门禁改用 `fusionScore` → RC-09 一致性转红；清空某正例 `relevantChunkIds` → Recall 下降且红灯（证明指标真在算）。✅ 有效。
- **回滚策略**：`rag-evaluation` 测试不挂 `verify` 阶段即停 CI 门禁（评测集与实现保留，是可复审资产）；无生产代码改动、无 DDL。主要成本是人工标注（≥350 条），非代码。

#### R-4 多租户检索谓词统一注入收口（关联维度 2 的 rlsDependent）

- **具体改动片段**：① 新增 `RetrievalPredicateCoverageIT`——元数据扫描 `rag-persistence` 全部涉 `rag_chunk`/`rag_chunk_embedding`/`rag_document`/`rag_source` 的 SELECT 必含显式租户谓词（静态发现，铁律 17 配真实 IT）；② 双租户数据 IT 断言两侧（dense/lexical）chunk 集合 ⊆ 本租户∪哨兵；③ R1 批 6 处 `set_config` 按 `14_...sql:79-81` 清单移除（**先 SQL 后代码**，反序 Sev-0），`RlsDependencyProbe.EXPECTED` 30→24→…→0；④ 出口门禁 `RagShadowExitGateEvaluator`：`rlsDependent=0` + mismatch=0 + taint=0 连续 14 天 + TL/QA 签核。
- **三级验收门禁**：
  - 静态/单元：`RetrievalPredicateCoverageIT` 扫描断言每条涉 chunk SELECT 含 `scopeTranslator.render()` 产物；人为删 `lexicalSearch` 一处 translator 调用 → 转红。
  - API（真实 PG，app_agent NOBYPASSRLS）：双租户 IT 断言两侧 chunk 集合 ⊆ 本租户∪哨兵、全局桶对每租户可见（`RetrievalRepositoryIT.globalChainIsRecalledByEveryTenant` 保留）。
  - 角色×任务：用 `app_agent`（NOBYPASSRLS，铁律 1）跨租户检索断言 0 行；运维角色验证观察期报表达标后才放行 M4。
  - **撤掉修复点会红吗**：把 `RagSqlScopeTranslator.render` 的 `TenantWithGlobal` 分支改成 `TenantOnly` → "全局桶每租户可见"IT 转红；删谓词 → 静态扫描转红。✅ 有效。
- **回滚策略**：观察期出口门禁是"只放行 SQL 脚本 COMMIT"的开关，`eligible=false` 即回到 RLS 在场（双写）态；**严禁反序**（先删 set_config 后跑退役 SQL 会 Sev-0）。9 张无 RLS 表是影子期基线，**勿直接补 RLS**（会破坏 M4 应用层谓词迁移路径，P0 已纠偏）。

#### R-5 摄取幂等与增量更新（文档版本 / chunk 重建 / embedding 重算）

- **具体改动片段**：① `rag_document` 增列 `revision_no`/`superseded_by` + 部分唯一索引（`19_v1_0_6_...sql`，ADD COLUMN 与建索引同事务，铁律 11）；② `saveChunkAndEmbedding` 改 `INSERT...ON CONFLICT(chunk_id) DO UPDATE`（需 UPDATE 授权 + SchemaDriftIT，铁律 7）；③ `rag_chunk_embedding` 增 `content_hash` 列作为"该向量对应文本指纹"，`content_hash` 不匹配即需重算（NULL 视为需重算）；④ 新增"发布修订版"单事务操作（写新版→标旧 `superseded_by`→旧 chunk `retrievable=false` 软失效→旧代际 RETIRED，**无物理删除**，铁律 8）；⑤ 新增单载体 reingest 端点。
- **三级验收门禁**：
  - 单元：`ChunkIdentityTest`（同 document 同 charStart→chunk_id 相同；content_hash 同→不重算，异→重算）。
  - 链路（真实 PG）：`IngestIdempotencyIT` 同一 document 连续两次 ingest → `rag_chunk`/`rag_chunk_embedding` 行数不变、两表 content_hash 相等；`DocumentRevisionIT` 发布修订后旧 chunk 全 `retrievable=false` 且旧数据仍在库。
  - 角色×任务：运维角色重传教材修订版，验证学生检索不到旧版答案、新版正常召回。
  - **撤掉修复点会红吗**：upsert 改回纯 INSERT → 第二次摄取唯一约束冲突（红才对）；修订切换第 3 步人为失败 → 断言前 2 步也回滚（无"新版生效旧版仍可检索"中间态）。✅ 有效。
- **回滚策略**：新增列/索引可 `DROP`（数据保留）；upsert 回退到纯 INSERT 会失去幂等（仅紧急止损）；软失效的旧 chunk 可恢复 `retrievable=true`。**RAG-3 worker 摄取链缺专用 DB IT** 需在本项同期补齐（真实缺口）。

#### R-6 影子模式扩展为"检索策略双跑 + 自动切流报表"

- **具体改动片段**：① `RetrievalOrchestrator.retrieve()` 增双跑分支（`rag.shadow.strategy.enabled=true` 且采样命中时同时跑当前/对照策略）；② 基线**快照法**（存结果快照，避免保留旧代码）新增 `rag_retrieval_shadow` 表——⚠️ **必须挂 RLS**：`ENABLE+FORCE ROW LEVEL SECURITY` + 读策略 `tenant_id=ANY(ARRAY[rag_current_tenant(),rag_global_tenant_id()])`（铁律 1/7），同步 `03_rag_roles.sql`；③ `RagStrategyComparator` 算 Jaccard@K/排名位移/分数分布/漏斗差异/成本差异；④ 报表端点按配置阈值判定 `KEEP_CURRENT/CUT_OVER/NEEDS_REVIEW`；⑤ 与隔离影子用不同 resourceType 命名空间（`retrieval` vs `retrieval.strategy`），切流走平台既有灰度（不新增切流原语）。
- **三级验收门禁**：
  - 单元：`RagStrategyComparatorTest`（Jaccard/位移计算正确；样本不足→`NEEDS_REVIEW` 非 `CUT_OVER`）。
  - 链路（真实 PG）：`RagStrategyShadowIT` 开启后影子表行数=采样命中数（非全部）；报表三项有值；**默认关闭时表行数恒为 0**（零行为变更）。
  - 角色×任务：用 `app_agent`（NOBYPASSRLS）+ 另一租户身份查 `rag_retrieval_shadow` ⇒ 0 行（铁律 1，BYPASSRLS 是假绿）。
  - **撤掉修复点会红吗**：判定阈值改"恒 CUT_OVER" → `NEEDS_REVIEW` 用例转红；采样率改 0 → 影子表 0 行但主检索仍成功；`block-on-violation=true` 触发违例 → 主检索不受影响（影子只观测不阻断）。✅ 有效。
- **回滚策略**：`rag.shadow.strategy.enabled=false` 一键关闭（默认即关）；影子表保留（无害）；切流仍走平台灰度 flag。**风险**：新表漏挂 RLS 会成新越权面——必须 `app_agent` 验隔离；双跑成本翻倍用采样缓解。

#### R-7 检索审计补齐取证字段 + 评测集反哺

- **具体改动片段**：① `JdbcTenantScopedRetrieval.java:63-65` 的 INSERT 补 `chunk_id`（top-1）与 `request_digest`（`SHA-256(tenantId+query+mode+edition坐标)` 十六进制，只存摘要不存原文，铁律 8）；本次核实实际已写 `source_id`（NULL），补列时一并纳入；② 新增 `rag_retrieval_hit` 明细表（retrieval_id/chunk_id/rank/score/channel）关联 audit_id；③ `scripts/rag-eval-harvest.sh` 从审计导出 query+命中，脱敏（JDK 引擎 PII 正则，铁律 21）后入标注队列反哺 R-3 评测集；④ 清理走受控通道（禁物理删除，铁律 8）。
- **三级验收门禁**：
  - 单元：`RequestDigestTest`（同 query 两次 digest 相同、改一字则变、长度恒 64、**不含原文任意 4 字连续片段**）。
  - 链路（真实 PG）：`RetrievalAuditIT` 检索后断言该行 `action=RETRIEVE`、`chunk_id` 非空、`request_digest` 长 64；审计 INSERT 违反约束 → 整次检索回滚（承 D-8）。
  - 角色×任务：合规角色查"谁在何时检索了哪段"可取证；导出文件塞入手机号/身份证号 → 断言不含这些串。
  - **撤掉修复点会红吗**：digest 改"存原文" → "不含原文子串"断言转红；删 `chunk_id` 写入 → 非空率断言转红。✅ 有效。
- **回滚策略**：停止写新增列/明细表即可（旧审计行不受影响）；明细表可保留（更多数据无害）；harvest 脚本独立，停跑不影响生产。

> **7.11 与附录 E 的关系**：本节给出每项"改哪一行/怎么验/怎么退"的**速用版**；附录 E 按"前置条件 / 改动清单（含片段）/ 三级门禁 / 反向验证 / 回滚 / 铁律对齐"六段式给出**排期版**。两者口径一致，均以本节红线复述与铁律为前提。R-1 硬降维旧结论、9 张无 RLS 表、影子差分流量三处纠偏必须在实施前复述，避免沿用旧判断。

---

## 八、其他有价值的横向对比（避免维度内自闭）

### 8.1 与本项目其他维度的耦合与相互约束

#### 耦合 1：维度 2（多租户隔离）—— RAG 是 RLS 退役的"最后一个硬骨头"，也是它的最大风险源

- **约束方向 A（维度 2 → 维度 8）**：维度 2 的 `rlsDependent → 0` 迁移出口直接卡住 RAG 侧的 M4-R1。RAG 侧的 30 处 `set_config` 站点（R1=6/R2=6/R3=16/CENTRAL=2）是整个迁移计划中**唯一带业务语义**的部分——platform 侧的 16 处是"会话租户绑定"，而 RAG 侧的 30 处绑的是"检索作用域"，一旦漏改就是**跨租户召回**。
- **约束方向 B（维度 8 → 维度 2）**：RAG 侧的隔离正确性是维度 2 的**外延证明**——`RAG-D05` 的"教学事故"（串版本、泄题）在隔离维度上是"跨租户/跨版本召回"，本质是同一类缺陷的不同粒度。维度 2 的分层判定（L3）之所以成立，正是因为 RAG 侧把隔离下沉到了数据层。
- **共同红线**：`FORCE RLS` 不绑租户静默 0 行（铁律 1）；M4 的上线顺序**必须**先 SQL 后代码（反序 Sev-0）。
- **改进点关联**：R-4（谓词统一收口）同时是维度 2 的改进点 I-2（零 `rlsDependent` 门禁）的**RAG 侧实现**；两者的门禁口径应统一（`rlsDependent_rate == 0` 连续 14 天 + mismatch = 0）。

#### 耦合 2：维度 6（上下文管理）—— RAG 命中是上下文中"最不受控的一块"

- **约束方向 A（维度 6 → 维度 8）**：维度 6 的 `CompactionPipeline` 三级压缩（W1 可逆外置 / W2 整轮裁剪 / W3 增量摘要）处理的是**对话历史与工具结果**；RAG 命中走的是**另一条路径**（`RAG_GUARD.wrapToolResult` + `RagHitQuotaPort` 配额裁剪），**不进 CompactionPipeline**。这意味着 RAG 命中的 token 消耗**不占**上下文策略的 `context-token-budget` 预算池，而是走独立的 `rag-quota` 配置（`platform-bootstrap/application.yml:267`）。
  - ⚠️ **这是一个真实的架构裂缝**：如果 RAG 配额与上下文预算各自为政，总 token 可能超出模型的实际上下文窗口。改进方向：把 RAG 配额纳入 `ContextBudget` 的统一分配（`BudgetAllocator`），或在 `AgentChatOrchestrator` 里做一次合并校验（RAG 配额 + 历史 + 记忆 ≤ 窗口）。本报告不在第七章单列，因为它的主责在维度 6；但**在 R-2 扩大候选池时会加剧这个问题**（更多命中 → 更多 token），故 R-2 的风险条目里已含"延迟与 token 双增"。
- **约束方向 B（维度 8 → 维度 6）**：RAG 提供的 `locator`（页码/章节路径/题号）是维度 6 的 W1 外置块**最理想的锚点**——外部化内容需要可回溯的定位。当前 `Chunk.Locator` 已提供（`pageNumber`/`sectionPath`/`exerciseNo`/`charStart`/`charEnd`），但 `HttpRagRetrievalGateway` 的 7 字段白名单里 `locator` 是一个**扁平字符串**（`orEmpty(text(n, "locator"))`），结构信息在跨进程时**已被压平**。改进方向：把 `locator` 作为结构化字段透传（仍属白名单，只是字段内部结构化）。
- **共同纪律**：RAG 命中是**不可信数据**（它来自用户上传的教材），必须经围栏标记后才能进上下文（维度 7 的间接注入防护）。

#### 耦合 3：维度 7（权限与护栏）—— 泄题闸门是 RAG 特有的护栏形态

- **约束方向 A（维度 7 → 维度 8）**：维度 7 的"输出审核"管的是模型**产出**；而 RAG 的泄题发生在**检索期**——答案册内容一旦进入上下文，模型的输出审核再严格也拦不住（模型可能以"暗示"形式泄漏）。所以**泄题闸门必须在检索期**（`RetrievalPolicy.allowsRole` 在 SQL 结果集上过滤），这是维度 7 的护栏原则"RAG 命中是不可信输入"的延伸。
- **约束方向 B（维度 8 → 维度 7）**：RAG 侧自带的鉴权（`RagJwtAuthFilter` 双令牌域 + `X-Tenant-Id` 归属绑定 + `rag_scope` 治理票据）是维度 7 授权体系在 RAG 服务中的**独立实现**——因为 RAG 是独立进程，不能共享 platform 的 `AuthorizationService`。代价：两套授权语义需要人工对齐（票据 claim 名 `rag_scope` 必须两侧同名同义，`RagJwtAuthFilter.java:70-77` 明确"两侧各写一份字符串，改一处必改另一处"）。
- **共同红线**：fail-closed（公钥未配置 → 503，绝不静默放行）；`rag_scope` 只认 claim 不认 header（铁律 8 的精神）。

#### 耦合 4：维度 10（可观测性）—— 检索漏斗是 RAG 质量的第一指标，但目前出不了进程

- **约束方向**：维度 10 的指标是"事实表的确定函数"，而 RAG 的漏斗（`RetrievalFunnel`）目前只存在于 rag 进程内存中，跨进程被 7 字段白名单丢弃（5.3 盲区 3）。**这导致维度 10 无法为 RAG 提供任何"检索质量"维度的指标**——现有的 `agent_trace_metric` 里有 `ragHits`/`ragInjected` 计数，但那是"平台侧拿到多少条"，不是"检索侧漏斗怎么分布"。
- **改进点关联**：R-6 的影子报表 + R-3 的评测指标，两者的产出都应落进维度 10 的指标体系（否则又是"报告在另一个地方"的老问题）。

#### 耦合 5：维度 12（部署与伸缩）—— 代际蓝绿与 RAG 独立伸缩

- **约束方向 A（维度 12 → 维度 8）**：RAG 的摄取是**长任务**（146 页教材解析十几分钟，嵌入再几分钟），Hikari 必须 `keepalive-time: 120000`（`application.yml:32`）以防托管 RDS 回收空闲连接（2026-11-10 语文下册 146 页摄取失败即此根因）。这类"长任务 + 连接保活 + 大量 embedding 调用"的形态是维度 12 伸缩设计必须特殊照顾的。
- **约束方向 B（维度 8 → 维度 12）**：RAG 的独立部署让"检索侧独立伸缩"成为可能，但**当前 `rag.indexer.tenants: []`（默认空）** 意味着 worker **默认什么都不做**——需显式配置租户清单才能摄取（这是 fail-closed 的一致体现：不能跨租户扫全表）。维度 12 的"灰度显式清单"纪律（`WorkerPolicy.requireTenantSlugs`）在 RAG 侧有对应物（`IngestJobConsumer.claimNext` 逐租户领取）。
- **共同红线**：`FORCE RLS` 下 Worker 无租户会话读不到行（实测 visible_jobs=0），必须显式清单。

### 8.2 与非 agent 领域成熟做法的对照

#### (1) 金融：分级授权 + 组合限额 → 对应 RAG 的"可信度分级 + 多轴准入"

金融风控的通行做法是**额度分层**（同一客户不同产品线不同限额）+ **准入正交轴**（KYC 通过 / 产品风险等级 / 地域限制 / 时段限制，任一不过即拒绝）。这与本平台的 `ADMISSIBLE_TEMPLATE` 高度同构：**来源、载体、版权、时效、权威**是五个正交轴，任一不过即整体不可检索。

可借鉴的金融做法：**"分级授权 + 独立审计"**——每笔交易同时写**授权流水**与**执行流水**，两者独立可对账。本平台的对应物是 `rag_access_audit`（执行侧）+ `authz_decision_log`（授权侧，在 platform 侧）。但 RAG 侧目前**只有执行流水且取证字段不全**（R-7）。

另一条：**"限额不可超发"的 DB 层最后防线**（对应 platform 维度 9 的 `budget_no_overspend` CHECK）。RAG 侧的对应物是 `rag_chunk.scope_ck` 与 `rag_document.scope_ck` 的 CHECK——**scope 与 tenant_id 的一致性由 DB 约束保证**（`scope='PLATFORM'` ⇒ `tenant_id = 哨兵`）。这个做法值得肯定：它把"哨兵桶的语义"也下沉到了约束层。

#### (2) 分布式系统：双写 + 影子流量 + 渐进切流 → 对应 RLS 退役与检索策略变更

金融/互联网的"不停机迁移"标准流程是：**双写 → 影子读比对 → 观察期 → 灰度切流 → 旧路径下线**。本项目在 RLS 退役上完整实现了这套（`RAG-D05` 的 M0–M4 计划、`RagShadow` 影子比对、14 天观察期硬门禁），**这是本项目最成熟的一块**。

但**同一套范式尚未用到检索策略变更上**（R-6）：我们改一次切分策略/门禁阈值/融合参数，**没有**影子双跑、没有对比报表、没有切流判定。这是"有一套成熟方法论却没用在自己身上"的典型缺口——**改进点 R-6 的价值不在于发明新机制，而在于把已有的影子原语复用到第二个场景**。

#### (3) 合规/法律：证据链 + 可复现 → 对应检索取证与引用溯源

法律/医疗系统的核心要求是"**结论可回溯到具体位置**"。本平台的对应物是 `rag_answer_citation`（逐字引文 + 版本快照 + 判定结论）与 `Chunk.Locator`。这在竞品中罕见——竞品的引用通常是"文件名 + 片段"，没有**版本坐标快照**与**接地判定**（`rag_grounding_verdict` + `entailment_score`）。

可借鉴点：**引用的判定结论也必须落库**（不只是引文）。本平台已做（`rag_answer_citation.verdict` + `entailment_score`），但**检索侧的命中未被引用时无记录**（R-7）——"检索到了但最终没引用"是一个有意义的信号（说明排序不对或门禁放过了低价值内容），当前丢失了。

#### (4) 学术界：late chunking 的"上下文丢失"问题 → 对应教辅"定义与适用条件被切开"

Jina AI 的 late chunking（arXiv:2409.04701）证明：普通切分后独立编码会丢失跨块上下文，BeIR 上相对提升 2.70%–3.63%。本平台用**结构切分**（而非固定窗口）大幅降低了这个问题，但**没有 late chunking**。

对教辅的具体含义：**"二次函数的顶点公式" 与 "顶点公式的适用条件" 若被切到两个 chunk，检索命中前者时，模型看不到后者**——学生拿到的讲解就是"残缺的"。本平台的缓解手段是**知识点补全**（沿 `kp_ids` 聚合同知识点的其他片段）——这是**结构化的 late chunking 替代方案**：不通过编码顺序解决，而通过"检索后按关系补齐"解决。两者各有代价：late chunking 需要长上下文嵌入模型（我们当前是 1024/4096 维的通用嵌入，窗口未必够）；知识点补全会引入"补进来的片段门禁分数是基准分 0.5"的近似。

**这是本维度最值得深入的一条横向对照**，因为它指出了"结构切分 + 关系补全"这一独特路线在理论上的位置：介于"通用固定窗口 + late chunking"与"纯向量检索"之间，是对教辅场景的一次合理定制。

### 8.3 反向思考：若从头重做本维度，我们会怎么设计？

#### 8.3.1 保留什么（这些决策是对的，不该改）

1. **RAG 独立成服务 + 独立 schema + 独立角色**：教辅语料的权限模型、发布节奏、伸缩特性都与 Agent 运行时不同，混在一起会互相污染。
2. **业务治理轴下沉数据层**：版本/角色/版权/权威/时效的硬过滤在 SQL 里，是竞品全都没有的差异化能力，且对教辅是刚需。
3. **过滤在重排之前**：错误代价不对称（泄题不可补救 vs 漏召可补救）决定了这是对的。
4. **门禁读相关性分、排序读融合分**：两个量纲必须分开，这是真实事故换来的。
5. **哨兵租户表示法**：规避三处 NULL 语义陷阱，是干净的表示法选择。
6. **代际蓝绿**：换模型零停机，且检索侧"只读 ACTIVE 代际"的纪律杜绝了不可比向量空间混算。
7. **fail-closed 的跨进程边界**：畸形命中丢弃、审计与检索同事务、不回显远端正文。
8. **切分策略留痕**：为可归因性付费，是长期正确投资。

#### 8.3.2 如果重来会怎么改（6 处）

**改动 1：把"检索策略"抽成一等抽象（而非写死在 `RetrievalOrchestrator` 里）**。现在七步编排是硬编码线性流程；重来会像 Modular RAG 那样抽成可组合算子：`public interface RetrievalOperator { String name(); List<ScoredChunk> apply(RetrievalContext ctx); }`，装配顺序 `dense → lexical → fuse(RRF) → normalize → editionFilter → roleFilter → kpComplete → rerank → gate → topK`。**收益**：(a) 新增"query 改写""rerank""多路 query 展开"变成**装配问题**而非改流程问题；(b) 每个算子可**单独测试 + 单独度量**（直接解决 R-3 的"按 channel 拆分召回率"与 R-6 的"策略变体"）；(c) 支持按 query 类型路由（"概念解释"走 dense 主导、"公式查询"走 lexical 主导）。**代价**：抽象层与学习成本；且**必须保留"过滤在重排之前"的顺序约束**——抽象化最容易丢的正是这种跨算子的顺序纪律（除非在框架层声明算子阶段归属并在装配期校验顺序）。**现状映射**：R-2 已给出算子化的最小可行版本（归一 + rerank 作为两个新阶段插入既有七步），不必推倒重来。

**改动 2：检索侧先做"候选统一池"，再做"算子链"**。现在稠密与词法是两个独立 SQL，各自返回候选再在 Java 侧 RRF 融合；竞品（尤其 OpenAI 的 `hybrid_search`）在**索引/查询层就融合**（一个索引同时支持向量 + 稀疏）。重来会考虑：用一个查询同时返回两路信号（PG 的 `tsvector` 与 `vector` 可在同一 SQL 里算行级分数，减少一次往返），或引入支持混合的扩展以获得**索引级**的混合检索性能。**代价**：引入扩展 = 部署耦合（当前项目刻意避免 `zhparser`/`pg_jieba` 这类 C 扩展，`ChineseTokenizer` 类注释明确"这样**无部署耦合**"）——这是性能 vs 部署简洁的真实取舍；当前规模下保持 PG 内建 + 应用层融合合理，语料规模上一个量级则应重估。

**改动 3：门禁阈值与"相关性分定义"绑定成不可分割的一对**。现在 0.38 是**裸配置值**，其有效性完全依赖"相关性分 = max(通道原始分) 且已归一到同一量纲"这个前提，而 R-2 引入归一后该前提会变。重来会把两者绑成**版本化的标定对象**：`record ScoreCalibration(String version, String scoreDefinition, double threshold, String embeddingModel, String embeddingVersion, int corpusSize, Instant calibratedAt)`。阈值不再是裸数字，而是**带着标定上下文**的配置对象——换模型/换语料/改归一方式时**旧阈值自动失效**（`scoreDefinition`/`embeddingModel` 对不上）。这比"注释里写清楚要重标"强一个数量级。**代价**：多一层配置结构；标定流程需产出该对象（可做成小工具）。

**改动 4：评测集与 CI 从第一天就有，而非"设计先行、实现后补"**。现状 `RAG-D08` 有完整设计但全未实现。重来会在**第一个检索功能上线时就同步落地评测集**（哪怕 50 条），CI 门禁先设 O 类（只观测）。理由：**阈值、归一、融合方式、候选池大小**每一个都是"没有度量就无法改动"的参数——0.38 之所以只有 27 条样本标定，正因为缺少评测集这个"必须持续维护的资产"。**代价**：早期人工标注投入 + 功能未稳定时的噪声指标；缓解：先 O 类观测，稳定后转 R 类阻断。

**改动 5：把"不可达草稿代码"纳入架构守卫**。现状 `agent-infra` 草稿污染了既有梳理报告。重来会加一条**架构级守卫**：每个模块必须在 `platform/pom.xml` 中被至少一个其他模块依赖，否则视为"未接线模块"，由测试断言其"必须为测试专用或不存在"。这类守卫很便宜，能避免本次复核发现的问题。

**改动 6：影子 infra 从第一天就是"多维度"的**。现在 `RagShadow` 只服务隔离迁移。重来会把采集点抽象为**命名空间 + 采样 + 比对函数**：`RagShadow.compare("retrieval.isolation", baselineIds, variantIds, comparator)` 与 `RagShadow.compare("retrieval.strategy", baselineHits, variantHits, comparator)`。隔离比对与策略比对共用采样、可逆开关、只观测不阻断、指标落盘这套骨架，只是比对函数不同——"新增一个影子用途"变成**写一个比对函数**而非"建一套新 infra"，R-6 的实现成本大幅下降。

### 8.4 本维度的"错误代价不对称表"（贯穿全文的判断依据）

| 错误 | 概率 | 后果 | 可补救 | 我们的处置 |
|---|---|---|---|---|
| 串版本（用错教材的页码） | 中 | 高（教辅第一大事故） | 难（学生按错页码复习） | 五轴硬过滤 + 版本惩罚降权 + 不一致标注 |
| 泄题（答案册进讲解） | 中 | 高（不可补救） | 不可 | 角色硬过滤（检索期）+ 默认 EXPLAIN 最严档 + "宁可过判为讲解" |
| 跨租户召回 | 低 | 极高（合规事故） | 极难 | RLS + 显式谓词双写 + 哨兵 + 影子染色 |
| 检索不到（漏召） | **高** | 低（可补救：换问法） | 易 | 分数门禁 + 提示学生换种问法 |
| 检索超时/不可用 | 中 | 中 | 易（重试） | fail-closed + 503 + 组件级 error（区别于零命中） |
| 切分错（答案区混入正文） | 中 | 高（泄题） | 需重跑摄取 | 版面优先判定 + 人工抽检 + `LayoutFacts` 快照留证 |
| 阈值过高（误拒） | 中 | 低 | 易 | 门禁可配 + 评测集标定（**当前缺**，R-3） |
| 换模型维度不符 | 低 | 中（检索恒低分/报错） | 中（改配置） | `VectorColumn` 装配期校验（**但缺列侧校验**，R-1） |

**这张表是本报告全部设计判断的隐含依据**，也解释了为什么本平台在"宁可漏召"的方向上如此激进——不是因为漏召不重要，而是因为在教辅场景里它的代价低一个数量级。**但前提是漏召率可控，而漏召率目前不可测（R-3），这是本维度最需要优先补上的证据。**

### 8.5 深度补充：与相邻维度的精确交界（改一处必须联动一处，≥2 条）

> 8.1 已列 RAG 与维度 2/6/7/10/12 的五条耦合。本节进一步把最容易被误判责任的两处交界画清：**维度 8 ↔ 维度 2（RLS 退役）** 与 **维度 8 ↔ 维度 6/10（评测与可观测数据的归属）**，避免改动时重复实现或责任真空。两条都带反向验证与纠偏标注。

#### 8.5.1 ↔ 维度 2（多租户隔离）：RAG 侧谓词显式化是"应用谓词层"，与 RLS 退役是同一件事的两面

| 关注点 | 维度 8（RAG 本维度）负责 | 维度 2（多租户隔离）负责 | 交界锚点 |
|---|---|---|---|
| 检索谓词单一出口 | `RagSqlScopeTranslator.render()` 渲染 4 作用域谓词，dense/lexical 两侧同源 | 定义 `rlsDependent → 0` 的迁移出口与门禁口径 | `RagSqlScopeTranslator.java:95-123` |
| 谓词 vs RLS 对偶证据 | 影子比对"显式谓词结果 vs RLS 在场结果" | 提供 `ShadowExitGateEvaluator` 的观察期框架 | `RagShadowReporter.java` / `RlsDependencyProbe.java:47-52` |
| 全局桶隔离 | 哨兵租户 + `rag_current_tenant()` 遇哨兵 RAISE 42501 | 复用"租户由会话/谓词强制"的统一纪律 | `RagScope.java:10-46` |
| 退役顺序 | R1 6 处 set_config 移除（代码侧） | 定义"先 SQL 后代码"硬顺序（反序 Sev-0） | `14_...sql:79-81` |

- **交界风险**：M3 已把检索读面显式谓词补齐（`JdbcRetrievalRepository` 6 处 SELECT 全经 translator，M1/M3 收口、66 用例 DB IT 全绿），但 **M4 四批 SQL ⛔DRAFT 未应用、`rlsDependent` 仍 30**。若维度 2 单方面推进"删 RLS"而不等 RAG 侧观察期数据，或 RAG 侧单方面删 set_config 而 RLS 仍在场，都会踩铁律 1（FORCE RLS 静默 0 行）。**联动门禁**：`rlsDependent=0` + mismatch=0 + taint=0 连续 14 天（维度 2 框架）+ RAG 侧真实流量（当前 M2 差流量），两者同时达标才放行。
- **反向验证**（铁律 6/17）：把 `RagSqlScopeTranslator.render` 的 `TenantWithGlobal` 改成 `TenantOnly` → 维度 2 的"全局桶每租户可见"IT 必转红；反之维度 2 改 RLS 策略 → RAG 影子 mismatch 必上升。若任一不变红，说明对偶证据是假绿。**纠偏**：9 张无 RLS 表是 `AuthzShadowPolicyDefaults` 登记的影子期基线，**勿直接补 RLS**（P0 已纠偏），补了会同时破坏维度 2 的迁移路径与 RAG 的应用层谓词验证。

#### 8.5.2 ↔ 维度 6/10（上下文与可观测）：检索漏斗与审计取证"归谁产出、归谁消费"

| 数据 | 维度 8（RAG 本维度）产出 | 维度 6/10 消费 | 交界现状 |
|---|---|---|---|
| 检索漏斗 `RetrievalFunnel` | 五段计数在 rag 进程内（`RetrievalOrchestrator.java:149`） | 维度 10 想纳入 `agent_trace_metric` | ❌ 7 字段白名单不透出 funnel（盲区 3），调参闭环断裂 |
| RAG 命中载荷 | `RagHitQuotaPort` 配额裁剪 + `RAG_GUARD.wrapToolResult` | 维度 6 注入上下文 | ⚠️ 走独立 `rag-quota`，不占 `context-token-budget`（架构裂缝，8.1 耦合 2） |
| 检索审计 `rag_access_audit` | 检索同事务写 4~5 列，`principal_id`=NULL | 维度 10 取证 / 维度 6 评测 | ⚠️ 无 chunk_id/request_digest，无法反哺评测集（R-7） |
| locator 定位 | `Chunk.Locator`（页码/章节/题号/字符位） | 维度 6 W1 外置块锚点 | ⚠️ 跨进程被压平为字符串，结构信息丢失（8.1 耦合 2） |

- **交界风险**：这类数据的典型陷阱是"两边都以为对方在做"——rag 侧认为"计数够了"，platform 侧认为"rag 会透出"，结果 funnel 谁都拿不到。本平台的 7 字段白名单是**有意的 anti-SSRF/anti-泄露设计**，但副作用是把维度 10 的观测资产也挡在进程外。**正确分工**：RAG 侧负责"产出并落库"取证/指标数据（R-6 影子报表、R-7 审计补列、R-3 评测指标），维度 10 负责"消费并告警"（接入 `agent_trace_metric`/Grafana）；`locator` 结构化的消费方是维度 6（W1 锚点），生产方是维度 8。
- **反向验证**：删掉 R-7 的 `chunk_id` 写入 → 维度 10 的"谁检索了哪段"取证查询必返回空（变红）；把 funnel 从响应透出但维度 10 未落库 → 报表仍无数据（变红）。**联动门禁**：R-3/R-6/R-7 的产出必须落进维度 10 的指标体系，否则又是"报告在另一个地方"（8.1 耦合 4）；R-2 扩大候选池时须与维度 6 核对 token 预算（RAG 配额 + 历史 + 记忆 ≤ 窗口），否则更多命中会撑爆上下文。

#### 8.5.3 三条交界的统一原则（含 RAG 特有纠偏）

1. **单一出口**：租户谓词只由 `RagSqlScopeTranslator` 产出（维度 8），dense/lexical 同源；维度 2 只定义出口门禁口径，不重复实现谓词。
2. **单一权威源**：检索质量/取证数据的"产出"在维度 8（R-3/R-6/R-7）、"消费告警"在维度 10，禁止两边各起一套报表或审计表。
3. **撤点反证 + 纠偏复述**：任一交界锚点改动（translator 分支、`rag_access_audit` 写入、funnel 白名单），必须对相邻维度 IT 做"撤点反证"；实施前复述三处纠偏——**① RRF k=60 与 halfvec(4096) 是刻意取值/取舍非缺陷；② 占位对象是嵌入模型非向量库，向量库集成已闭环；③ 不可达草稿在 agent-infra，不在 rag 生产链**——引用旧结论一律标注"已被复核纠偏"。

---

## 九、对产品演进的启发

### 9.1 本维度支撑的产品能力（用户能感知什么）

| 产品能力 | 用户感知 | 技术落点 | 当前状态 |
|---|---|---|---|
| **"依据这句话回答我"** | 回答里能点开看到原文页码、章节、题号 | `Chunk.Locator` + `locator` 字段 + `rag_answer_citation` | ✅ 已实现（locator 跨进程被压平为字符串，结构丢失） |
| **"只用我这一册的教材"** | 不会串到别的版本 | 五轴 `EditionCoordinate` + `EditionScope.admits()` + `CROSS_EDITION` 占位 | ✅ 已实现（占位坐标下为跨版本召回，真实场景需下发真实坐标） |
| **"别提前告诉我答案"** | 讲解里没有标准答案 | `RetrievalPolicy.allowsRole` + `mode=EXPLAIN` 默认 | ✅ 已实现 |
| **"用我上传的资料回答"** | 上传 PDF 后即可提问 | 摄取管线 + `rag_ingest_job` + `ChunkIndexer` | ⚠️ 链路通（`rag.indexer.tenants` 默认空，需配置） |
| **"资料没更新就别用旧的"** | 撤回某本教材后立刻检索不到 | `doc.retrievable` + `doc.ver_status` 准入谓词 | ✅ 已实现（这是我们修复过的真实缺陷） |
| **"没找到就直说"** | 不编造答案 | 分数门禁 0.38 + 组件级 error | ✅ 已实现（但误拒率不可测） |
| **"答案有多准"** | 置信度可见 | 相关性分 + 漏斗 | ⚠️ 分可见（`score`），但漏斗跨进程丢失 |
| **"换个说法我再找找"** | 检索失败时给出建设性提示 | `RagUnavailableException` vs 零命中的区分 | ✅ 已实现 |
| **"这道题怎么解"（含练习与提示）** | 讲解配练习 | 知识点补全（`kp_ids` 聚合 + 先修扩展） | ⚠️ 已实现但需 `knowledgePointId` 下发（当前 platform 侧传 `null`） |
| **"这一章讲了什么"** | 章节概览 | `RetrievalMode.OVERVIEW` | ❌ 只能过滤，不能真正概览（GraphRAG 差距） |

### 9.2 演进路线图（近/中/远三期）

#### 近期（0–1 个迭代）：把"占位"变成"真实"，把"不可达"变成"不可见"

| 项 | 产出物 | 验收标准 |
|---|---|---|
| R-0 | 草稿检索引擎删除 + 梳理报告口径修正 + 防复活守卫 | 全仓 `VECTOR_SIMILARITY_THRESHOLD` 计数 = 0；`/api/v3/retrieval` 返回 404；守卫转红验证通过 |
| R-1 子项 1+2 | 向量列侧启动自检 + 配置与 DDL 对齐 | 人为改错维度 ⇒ **拒绝启动**；`pg_attribute` 实际列型与配置一致 |
| R-0 的文档修正 | `docs/能力维度实现方式梳理报告.md:123` 更新 | 门禁 0.38、融合 RRF(k=60)、占位对象改为"嵌入模型"——与源码逐字一致 |
| R-4 子项 1 | 零谓词守卫（静态 + IT） | 人为删一处 translator 调用 ⇒ 守卫转红 |
| R-7 子项 1 | 审计补 `chunk_id` + `request_digest` | 非空率 100%；digest 不含原文子串 |

#### 中期（1–3 个迭代）：把"能跑"变成"可度量、可对比、可切流"

| 项 | 产出物 | 验收标准 |
|---|---|---|
| R-3 | 评测集（≥350 条）+ 指标实现 + CI 四级门禁 | G 类（漏拒率 0 / 门禁一致性 100%）与 R 类（Recall@10 / MRR@10 / 误拒率）全部阻断 PR；指标报告落 `target/retrieval-metrics.md` |
| R-2 阶段一 | 通道内归一 + 候选池可配 | **仅词法命中的查询在门禁后仍有命中**（当前必失败）；归一前后 RRF 排序逐位不变 |
| R-2 阶段二 | cross-encoder rerank（`bge-reranker-v2-m3` 优先） | 关闭时结果逐位一致；模型不可用时降级但成功；近邻负例 p95 ≤ 0.45 且正例 Recall@10 不降 |
| R-6 | 策略影子双跑 + 对比报表 + 切流判定 | 报表含 overlap@10 / 漏斗差异 / 分数分布 / 成本四项；判定由配置阈值决定；默认关闭时零副作用 |
| R-1 子项 3+4 | 列迁移 + 真实嵌入 + ANN 索引 | `pg_indexes` 有 hnsw 索引；稠密 P95 下降 ≥ 50%；真实模型下 Recall 有基线值 |
| R-4 子项 2+4 | R1 代码退役 + 观察期出口门禁 | `rlsDependent` 30 → 24 → …；`RagShadowExitGateEvaluator` 判定 `eligible-for-M4` |
| R-5 | 文档修订 + 幂等 upsert + 增量重算 | 同一 document 连续摄取两次行数不变；换模型只重算 content_hash 变化的 chunk |

#### 远期（3–6 个迭代）：从"检索服务"到"检索平台"

| 项 | 产出物 | 验收标准 |
|---|---|---|
| 算子化重构 | `RetrievalOperator` SPI + 阶段归属校验 | 新增检索能力**不改** `RetrievalOrchestrator` 主流程；装配期校验"过滤先于重排" |
| 路由化 | 按 query 类型路由到不同检索策略 | 概念类/公式类 query 的分类型 Recall@10 均达标；路由决策可审计 |
| GraphRAG 路线评估 | 章节级社区摘要（可只做 OVERVIEW 模式） | "这一章讲了什么"可回答且引用可回溯；**不牺牲**现有局部检索的 directness |
| 统一标定对象 | `ScoreCalibration` 版本化配置 | 换模型/改归一 ⇒ 旧阈值**自动失效**（不是靠注释提醒） |
| 评测资产运营 | 真实日志驱动的评测集持续扩充 | ≥ 30% 评测样本来自真实脱敏日志；评测集版本与标定记录一一对应 |
| 成本-质量联合看板 | 检索成本（embedding + rerank + DB）与质量指标同图 | 能回答"每提升 1 个点 Recall 多花多少钱" |

### 9.3 风险清单（做这一步可能踩的坑，关联本项目已知铁律）

| # | 风险 | 触发场景 | 后果 | 缓解 | 铁律 |
|---|---|---|---|---|---|
| RK-1 | **M4 顺序反了** | 先改代码移除 `set_config`、后跑退役 SQL | **Sev-0**：RLS 在场 + GUC 未绑 ⇒ FORCE RLS 静默过滤 ⇒ 租户行 0 可见（检索恒空） | 严格"先 SQL 后代码"；SQL 脚本保留 `BEGIN…ROLLBACK` dry-run 直到放行；`17_...sql:14` 已写明"反序=Sev-0" | 铁律 1、11 |
| RK-2 | **退役后漏写谓词 → 越权** | 某条新查询分支忘了 translator | 退役 RLS 后**静默返回全量**（比现在"0 行"更危险） | 零谓词守卫（静态发现 + IT 证明）；`M4R1RetrievalRetirementReverseGuardIT` 模式扩展 | 铁律 1、6 |
| RK-3 | **影子期开太久或信号不采信** | M2 观察期缺真实流量 | M4 无法放行，迁移无限期挂起 | 把"开启影子 + 产出真实流量"作为**明确的运维动作项**（当前 MEMORY 记为"差真实流量"）；报表化 | 铁律 6 |
| RK-4 | **门禁阈值漂移** | 换嵌入模型 / 改归一 / 换语料后忘了重标 0.38 | 检索恒空（阈值过高）或噪声涌入（过低） | 引入 `ScoreCalibration` 版本化对象（8.3.2 改动 3）；CI 门禁断言"标定上下文与当前配置匹配" | 铁律 6 |
| RK-5 | **rerank 破坏过滤前提** | 在过滤之前插入重排 | 被滤内容进入上下文 ⇒ 闸门形同虚设 | 顺序纪律写进编排层注释 + 反向验证用例（过滤移到重排后 ⇒ 转红） | 铁律 6、17 |
| RK-6 | **rerank 模型不可用拖垮检索** | 模型服务抖动 | 全部检索 503（若 fail-closed 误用） | rerank 不可用**显式降级**（计数 + 走未重排路径），与"嵌入不可用→503"区别对待 | 铁律 8、11 |
| RK-7 | **候选池扩大引发成本/延迟尖峰** | R-2 把 3× 扩到 100 | 稠密检索耗时与 DB 负载上升，embedding 调用不变但 rerank 调用 ×N | 候选池配置化 + 按 query 分级；与 `HttpRagRetrievalGateway` 的 20s 超时对齐；P95 告警 | 铁律 8 |
| RK-8 | **归一改变分数语义，门禁失效** | 通道内归一后 `relevance` 分布变了 | 词法命中开始过门禁（可能引入噪声）或稠密命中过不了 | 归一后**必须**重标阈值；评测集分 channel 拆分召回率作为观察指标 | 铁律 6 |
| RK-9 | **列迁移期检索不可用** | 直接原地 `ALTER COLUMN` | 语料不可检索（Severe） | 走**代际蓝绿**（新代际建新列/新维度 → 切换 → 退役），**不得**原地改列；演练走现有 `scripts/e2e-index-generation.sh` | 铁律 11 |
| RK-10 | **评测集与线上分布偏离** | 评测集全是构造样本 | 指标好看但线上无改善 | 真实日志反哺（R-7）；评测集版本化；线上 query 分布漂移告警 | 铁律 6 |
| RK-11 | **LLM-as-judge 偏差污染门禁** | judge 与生成同族 / 位置偏差 | 指标虚高，门禁形同虚设 | judge 与生成**不同族**；答案顺序对调取均值；prompt 钉版本 | 铁律 6 |
| RK-12 | **审计表成为新的越权面** | R-6 的影子表漏挂 RLS | 跨租户读取检索行为 | 新表必挂 `FORCE ROW LEVEL SECURITY` + 显式读策略；用 `app_agent`（NOBYPASSRLS）验隔离 | 铁律 1、7 |
| RK-13 | **摄取幂等的 upsert 覆盖并发写** | 同 tenant 两个 worker 同时摄取同一 document | 后写覆盖前写 | `FOR UPDATE SKIP LOCKED` 领取保证同 job 不并发；`ON CONFLICT DO UPDATE` 需 UPDATE 授权 + SchemaDriftIT | 铁律 7 |
| RK-14 | **删除草稿代码时误伤** | R-0 删 `agent-infra` 时该模块其实被某处反射/配置引用 | 启动失败 | 删前全仓 grep 引用（含 `pom.xml`、`application.yml`、反射字符串）；删后跑双 reactor `clean verify` 全绿 | 铁律 6 |
| RK-15 | **门禁阈值把"教辅特有噪声"当通用噪声** | 用通用 QA 集标定 | 阈值不适配教辅分布 | 评测集必须**教辅分布**（含同体裁负例）；通用集只作参考 | 铁律 6 |

### 9.4 一句话结论

**本平台的 RAG 是「数据层强隔离 + 教辅硬约束」的 L3 级内建闭环——治理轴下沉、泄题闸门在 SQL、哨兵桶与代际蓝绿是竞品全都没有的差异化能力；但它的检索质量一侧目前是「半成品」：向量打分本身已用 pgvector 闭环，真正占位的是嵌入模型，混合检索因门禁量纲问题退化为「词法只排序不召回」，rerank / query 改写 / Recall@K 评测三项标准件全部缺失，而唯一的影子机制比对的是隔离谓词而非检索策略——因此本维度的头号任务不是加功能，而是先把「可度量」补上（R-3 评测门禁）与把「口径污染」清掉（R-0 草稿删除），再谈重排与切流。**

### 9.5 深度补充：产品演进的三条"承诺—证据"链（避免能力宣称为空头支票）

> 9.1 列了用户可感知的产品能力。本节把其中最容易被"看起来有"误导的三条，逐条标出**产品承诺 → 现有证据 → 缺失证据 → 补齐改进项**，供产品决策时判断"能不能对客户说"。

| 产品承诺 | 现有证据（源码锚点） | 缺失证据（不可对客户声称） | 补齐项 |
|---|---|---|---|
| "依据可点开、可追溯到页码" | `Chunk.Locator` 五字段 + `rag_answer_citation` 落库；7 字段白名单透出 `locator` 字符串 | `locator` 跨进程被压平为字符串，章节/题号结构丢失；未引用命中无记录 | R-7（审计取证）+ 8.1 耦合 2（结构化透传） |
| "只用我这一册、不泄题" | `EditionScope.admits()`（:100）+ `RetrievalPolicy.allowsRole()`（:105）+ 五轴坐标；13 个 IT 覆盖 | platform 侧 `HttpRagRetrievalGateway.buildRequest` 传 `CROSS_EDITION` 中性占位坐标 / `knowledgePointId=null` → 真实场景需下发真实坐标 | 调用侧（维度 6）下发真实坐标，非 RAG 后端缺陷 |
| "答案有多准、能持续变准" | `score` 可见；0.38 有 2026-10-28 标定记录；影子 infra 已通电 | 无 Recall@K/MRR 门禁（R-3 全未实现）、27 条样本标定、漏斗跨进程丢失、影子差真实流量 | R-3 + R-6 + R-7（可度量三件套） |

> **产品结论**：前两条承诺的**机制已闭环**（数据层硬过滤 + 可追溯落库），可以对外讲；第三条"持续变准"目前是**空头支票**——缺的不是检索算法，而是评测与观测资产。这正是 9.2 路线图把 R-3 排在中期最前、把 R-0 排在近期最前的原因：**先让证据配得上承诺，再扩大能力边界**。

### 9.6 深度补充：演进的三条红线与一个"反事实"提醒（承接 9.3 风险清单）

> 9.3 列了 RK-1~RK-15。本节收敛为**三条不可协商红线**与一条最容易被忽视的反事实，供排期与 code review 直接引用。

1. **红线一：机制取值不动**。RRF k=60（业界默认，2.4(11)）、0.38 阈值（标定值，RAG-D08 §4）、六轴准入谓词（D-7）、排序/门禁分分离（DD-5）——四者都由源码+测试（`RetrievalRepositoryIT`）或官方出处钉死，改动必须走"评测集重标 + 反向验证"，**严禁拍脑袋**（MEMORY 红线复述）。
2. **红线二：不碰刻意取舍**。halfvec(4096) + 无 ANN 索引是 DDL 注释 + rag.env + VectorColumnTest 共同钉住的设计取舍（2.4(13)，pgvector 官方背书）；规模化走 MRL / 二进制量化，**禁止硬降维**（会与 qwen3-embedding:8b 输出冲突、使已入库向量失效）。9 张无 RLS 表是影子期基线，**勿直接补 RLS**。
3. **红线三：只在可达链路上施力**。生产检索链在 rag 模块 `JdbcRetrievalRepository`（链 A，4.5.1）；`agent-infra` 草稿运行期不可达（链 C，4.5.3）——在其上"优化"是浪费，其自述的 0.75/hybrid 是口径污染（R-0）。**占位对象是嵌入模型，不是向量库**。

> **反事实提醒**：若 R-1 照旧稿"硬降维建 HNSW"，或 R-4 照旧节奏"先删 set_config 后退役 RLS"，或 R-0 只在草稿上打补丁——三种反事实分别会导致"向量失效 + 检索恒低分"、"Sev-0 租户 0 行可见"、"口径污染继续扩散"。**这三处旧结论均已被 P0 复核纠偏**，引用时必须标注，不得再按旧判断改代码（见顶部提示块与 P0 验收报告 §3）。

---

## 附录

### A. 证据索引（本报告引用的全部本项目文件:行号清单）

路径根：`platform/`、`rag/`、`deploy/sql/`、`docs/`、`.workbuddy/`（均相对仓库根）。

**platform 侧**：`platform/agent-infrastructure/.../infrastructure/rag/HttpRagRetrievalGateway.java:27-31, 40-56, 57, 62-69, 103-149, 152-173, 175-221, 244-257`｜`platform/agent-ports/.../ports/agentchat/RagRetrievalPort.java:22-52`｜`platform/agent-worker/.../worker/agentchat/AgentChatOrchestrator.java:50, 142, 188, 209, 490-503, 506-564, 566-616`｜`platform/agent-infra/.../infra/retrieval/engine/`：`RetrievalService.java:1-102`、`RetrievalEndpoint.java:1-76`、`VectorSearchEngine.java:1-25`、`TfIdfRanker.java:1-48`（**四者均为不可达草稿**）｜`platform/platform-bootstrap/.../bootstrap/PlatformApplication.java:12-13`｜`platform/platform-bootstrap/src/main/resources/application.yml:63-68, 267, 746-756`｜`platform/pom.xml:39-69`（模块清单，含 `agent-infra`）

**rag 侧（核心）**：`rag/rag-core/.../core/retrieval/RetrievalPolicy.java:1-161`｜`.../core/scope/RagScope.java:1-62`、`RagScopeFilter.java:1-135`、`RlsDependencyProbe.java:1-101`｜`.../core/ingest/IngestDedupRules.java:1-105`｜`rag/rag-application/.../application/retrieval/RetrievalOrchestrator.java:1-230`｜`.../application/ingest/IngestionPipeline.java:1-284`、`IngestJobOrchestrator.java:1-269`｜`rag/rag-persistence/.../persistence/JdbcRetrievalRepository.java:1-786`、`VectorColumn.java:1-82`、`JdbcTenantScopedRetrieval.java:1-70`｜`.../persistence/scope/RagSqlScopeTranslator.java:1-124`、`RagSqlFragment.java`｜`.../indexing/ChunkIndexer.java:60-254`｜`.../shadow/RagShadow.java:1-79`、`RagShadowConfiguration.java:1-43`、`RagShadowReporter.java:1-75`｜`.../bootstrap/RagInfrastructureConfig.java:180-360`｜`.../management/infrastructure/RagSessionScope.java:16-80`｜`rag/rag-api/.../api/retrieval/RagRetrievalController.java:1-231`｜`rag/rag-bootstrap/.../bootstrap/security/RagJwtAuthFilter.java:1-336`｜`rag/rag-infrastructure/.../embedding/EmbeddingPorts.java:1-65`、`LocalDeterministicEmbeddingPort.java:1-96`｜`.../text/ChineseTokenizer.java:1-124`｜`.../LocalPortGate.java`｜`rag/rag-worker/.../worker/IngestJobConsumer.java:1-126`｜`rag/rag-premise/.../chunker/HeadingHierarchyChunker.java`、`.../role/BlockRoleResolver.java`、`.../model/ChunkPolicy.java`/`ChunkPolicyConfig.java`｜`rag/rag-ports/.../ports/EmbeddingPort.java`/`RetrievalPort.java`/`TenantScopedRetrieval.java`

**rag 测试**：`rag/rag-persistence/src/test/.../persistence/RetrievalRepositoryIT.java:219-420`、`RlsDependencyMetricTest.java:35-134`、`VectorColumnTest.java:21-53`、`scope/RagSqlScopeTranslatorTest.java`｜`.../shadow/RagShadowLiveIT.java`｜`.../retirement/M4R1RetrievalRetirementReverseGuardIT.java:170`｜`rag/rag-application/src/test/.../RetrievalOrchestratorTest.java`

**DDL / SQL**：`deploy/sql/rag/01_rag_ddl.sql:1-100, 281-292, 300-330, 330-360, 384-393, 960-966, 973, 1229-1547`｜`09_v1_0_3_index_generation_alter.sql:1-68`｜`13_v1_0_4_chunk_policy_alter.sql`｜`14_v1_0_4_r1_rls_retirement.sql:1-86`｜`15_v1_0_4_r2_rls_retirement.sql`｜`16_v1_0_4_r3_rls_retirement.sql`｜`17_v1_0_4_r4_central_rls_retirement.sql:1-37`

**配置**：`rag/rag-bootstrap/src/main/resources/application.yml:1-160`（重点 `:26, :32-34, :44, :50-54, :64-68, :85-93, :99-109, :115-123, :124-137, :138-152, :159`）｜`rag/rag-worker/src/main/resources/application.yml:1-86`（重点 `:15, :27-30, :51-59, :63-73, :74-81`）

**文档**：`rag/docs/RAG-D05_检索策略与召回质量控制.md:13-37, 41-87, 90-108, 112-125, 128-146, 149-205, 207-229, 232-247, 251-262`｜`RAG-D08_评测体系与质量门禁.md:113-124, 143, 159, 236, 286, 307-378`｜`RAG-D03_数据模型与索引设计.md:58, 160-176, 276-277`｜`RAG-D07_数据源权威性与合规治理.md`（G-0 四轴）｜`RAG-D13_语料管理UI设计文档.md`（代际蓝绿 §3.5.2）｜`RAG-D26_RAG物料前期处理模块设计.md`（阶段 0.3/3.1/3.2）｜`docs/权限管控体系/RLS依赖清理改造计划_20261112.md:18-30, 41-63, 163-266, 271-533, 533-583`｜`docs/权限管控体系/authz/policy-baseline-rag-20261112.yaml`｜`docs/能力维度实现方式梳理报告.md:115-127`（**第 8 章，含三处不一致**）｜`.workbuddy/memory/MEMORY.md:7-8, 12-38, 47`

### B. 竞品官方来源清单（全部 URL）

**OpenAI**：<https://platform.openai.com/docs/guides/retrieval>（向量库、切分、查询改写、`ranking_options.hybrid_search` 的 RRF 权重）｜<https://platform.openai.com/docs/api-reference/vector-stores/search>（search 请求体：`query`/`filters`/`max_num_results`/`ranking_options{ranker,score_threshold}`/`rewrite_query`）｜<https://platform.openai.com/docs/api-reference/vector-stores-files/createFile>（`chunking_strategy`：auto/static、`max_chunk_size_tokens` 100–4096、`chunk_overlap_tokens`）｜<https://platform.openai.com/docs/api-reference/assistants/createAssistant>（`FileSearchTool.ranking_options`）｜<https://platform.openai.com/docs/guides/embeddings>

**LangChain / LangGraph**：<https://python.langchain.com/docs/concepts/retrievers/>（Retriever 体系）｜<https://python.langchain.com/docs/concepts/text_splitters/>（切分器）｜<https://docs.langchain.com/oss/python/deepagents/retrieval>（2-Step / Agentic / Hybrid 三形态）｜<https://reference.langchain.com/python/langchain-core/documents/base/Document>

**Google ADK / Vertex AI**：<https://google.github.io/adk-docs/integrations/vertex-ai-rag-engine>（`VertexAiRagRetrieval`、single-tool 限制）｜<https://adk.dev/api-reference/typescript/classes/VertexRagRetrievalTool.html>（服务端 grounding、单 corpus 限制）｜<https://adk.dev/tools/google-cloud/vertex-ai-rag-engine/>｜<https://cloud.google.com/vertex-ai/generative-ai/docs/rag-engine/use-vertexai-search>（`RagRetrievalConfig`/`LlmRanker`）

**Microsoft AutoGen**：<https://github.com/microsoft/autogen>（维护模式横幅）｜<https://microsoft.github.io/autogen/stable/index.html>｜<https://learn.microsoft.com/agent-framework/>（继任者）

**CrewAI**：<https://docs.crewai.com/en/concepts/knowledge>（`knowledge_sources`/`KnowledgeConfig`/embedder 多 provider）｜<https://docs.crewai.com/en/tools/rag-tool>（`RagTool` + Embedchain）｜<https://docs.crewai.org.cn/concepts/knowledge>（中文镜像：`chunk_size=4000`/`chunk_overlap=200`）

**Agno**：<https://docs.agno.com/knowledge/introduction>｜<https://docs.agno.com/knowledge/concepts/search-and-retrieval/overview>（`SearchType`/reranker/query transformer/filters/`knowledge_retriever`）｜<https://docs.agno.com/knowledge/concepts/search-and-retrieval/hybrid-search>｜<https://docs.agno.com/knowledge/concepts/search-and-retrieval/reranking>｜<https://docs.agno.com/knowledge/concepts/search-and-retrieval/query-transformation>｜<https://docs.agno.com/knowledge/concepts/vector-db>

**向量库多租户**：<https://docs.pinecone.io/guides/index-data/multitenancy>（per-index / per-namespace / per-record）｜<https://docs.weaviate.io/weaviate/managing-collections/multi-tenancy>｜<https://github.com/pgvector/pgvector>（vector/halfvec、HNSW 维度上限）

**理论文献**
- <https://arxiv.org/abs/2005.11401>（Lewis et al., RAG, NeurIPS 2020）
- <https://arxiv.org/abs/2312.10997>（Gao et al., RAG Survey：Naive/Advanced/Modular）
- <https://arxiv.org/abs/2407.21059>（Gao et al., Modular RAG, arXiv 2024-07）
- <https://arxiv.org/html/2409.04701v3>（Günther et al., Late Chunking, EMNLP 2024）
- <https://www.arxiv.org/abs/2107.05720>（Formal et al., SPLADE, SIGIR 2021）
- <https://arxiv.org/abs/1901.04085>（Nogueira & Cho, Passage Re-ranking with BERT）
- <https://arxiv.org/abs/2212.10496>（Gao et al., HyDE）
- <https://arxiv.org/abs/2309.15217>（Es et al., RAGAS）
- <https://arxiv.org/abs/2306.05685>（Zheng et al., MT-Bench / Chatbot Arena，LLM-as-judge 偏差）
- <https://arxiv.org/abs/2404.16130>（Edge et al., GraphRAG）
- <https://arxiv.org/abs/2401.00368>（Wang et al., Improving Text Embeddings with LLMs, E5, ACL 2024）
- <https://arxiv.org/abs/2205.13147>（Kusupati et al., Matryoshka, NeurIPS 2022）
- <https://arxiv.org/abs/2309.07597>（C-MTEB / BGE 评测）

### C. 术语表（该维度关键术语中英对照）

| 中文 | 英文 | 本项目落点 |
|---|---|---|
| 检索增强生成 | Retrieval-Augmented Generation (RAG) | platform → rag HTTP 通道 + rag 独立服务 |
| 摄取 / 切分 / 块 | Ingestion / Chunking / Chunk | `IngestJobOrchestrator`/`IngestionPipeline`/`HeadingHierarchyChunker`/`rag_chunk` |
| 嵌入 / 向量列 | Embedding / Vector Column | `EmbeddingPort`/`rag_chunk_embedding`/`VectorColumn`（type+dimension） |
| 稠密检索 | Dense Retrieval | pgvector `embedding <=> query` 余弦 |
| 词法检索 | Lexical / Sparse Retrieval | `ts_rank(to_tsvector('simple', text_jieba), to_tsquery(...))` |
| 混合检索 | Hybrid Retrieval | 稠密 + 词法双通道 |
| 倒数排名融合 | Reciprocal Rank Fusion (RRF) | `RRF_K = 60` |
| 重排 | Reranking / Cross-Encoder | **未实现**（R-2） |
| 查询改写 | Query Rewriting | **未实现**（R-2 阶段三） |
| 假设文档嵌入 | Hypothetical Document Embeddings (HyDE) | **未实现**（R-2 阶段三） |
| 分数门禁 | Score Gating / Relevance Threshold | `min-score = 0.38` |
| 召回漏斗 | Retrieval Funnel | `RetrievalResult.RetrievalFunnel`（五段） |
| 版本坐标 / 版本范围 | Edition Coordinate / Edition Scope | `EditionCoordinate`（五轴）/ `EditionScope`（过滤+排序+标注） |
| 片段角色 / 泄题闸门 | Chunk Role / Answer-Leakage Gate | `ChunkRole`（7 值）/`RetrievalPolicy.allowsRole` |
| 检索模式 | Retrieval Mode | `RetrievalMode`（EXPLAIN/SOLVE/VERIFY/OVERVIEW） |
| 作用域 / 全局哨兵租户 | Scope / Global Sentinel Tenant | `RagScopeFilter`（4 case）/`RagScope.GLOBAL_TENANT_ID`（nil UUID） |
| 谓词翻译器 / 可信度准入 | Scope Translator / Admissibility Predicate | `RagSqlScopeTranslator`/`ADMISSIBLE_TEMPLATE`（6 轴） |
| 信任层级 | Trust Tier | `TrustTier` + `rag_trust_tier` |
| 索引代际 | Index Generation | `rag_index_generation`（BUILDING/ACTIVE/RETIRED） |
| 影子模式 | Shadow Mode / Dual-Run | `RagShadow` / `RAG_SHADOW_ENABLED` |
| RLS 依赖度 | RLS Dependency Count | `RlsDependencyProbe`（基线 30 → 目标 0） |
| 召回率 / 平均倒数排名 | Recall@K / Mean Reciprocal Rank (MRR) | RC-01 / RC-02（**均未实现**） |
| 归一化折损累计增益 | Normalized Discounted Cumulative Gain (NDCG) | 可按需补（RAG-D08 未定义） |
| 忠实度 / 上下文精确率·召回率 | Faithfulness / Context Precision·Recall | RAGAS 指标（可选引入） |
| 图检索增强 | GraphRAG | **未实现**（远期评估项） |
| 延迟切分 | Late Chunking | **未实现**；以"结构切分 + 关系补全"替代 |
| 学习型稀疏检索 | Learned Sparse Retrieval (SPLADE) | 未使用（无词典 bigram + ts_rank） |
| 哨兵桶 / 零谓词守卫 | Sentinel Bucket / Zero-Predicate Guard | 全局公共语料桶 / R-4（拟新增） |

### 附录 D：源码证据索引（路径 + 行号 + 调用链）

> 把正文散落的源码结论收敛为可逐条复核的索引。列：# / 源码锚点 / 结论 / 运行期可达性 / 复核方式。
> **行号口径**：全部为本次（P0 修复后，2026-11-15）实际 `Read`/`Grep` 核实的当前值。路径缩写见 4.3：`R-PERS`=`rag/rag-persistence/.../persistence`、`R-APP`=`rag/rag-application/.../application`、`R-INFRA`=`rag/rag-infrastructure/.../infrastructure`、`R-CORE`=`rag/rag-core/.../core`。原报告的行号为架构快照，本次核实仍成立者不另标；漂移者以本表为准并注明。

| # | 源码锚点 | 结论 | 运行期可达性 | 复核方式 |
|---|---|---|---|---|
| D-01 | `R-PERS/JdbcRetrievalRepository.java:107` | `RRF_K=60.0`（业界默认，2.4(11)） | 真实执行（每次检索融合） | Grep `RRF_K` 命中 :107 |
| D-02 | 同上 `:110` / `:477` / `:534` | `CANDIDATE_MULTIPLIER=3`；dense/lexical 候选=topK×3 | 真实执行 | Grep `CANDIDATE_MULTIPLIER` |
| D-03 | 同上 `:172-180` | `ADMISSIBLE_TEMPLATE` 六轴准入单一真源 | 真实执行（6 处 SELECT 共用） | Read :120-180 |
| D-04 | 同上 `:226,242-250,254,266` | 构造注入 scopeTranslator；装配期维度校验；构造期展开权威等级 | 真实装配（rag-bootstrap 配置） | Grep `scopeTranslator`/`IllegalStateException` |
| D-05 | 同上 `:489,492,498-504,515` | denseSearch：`scopeTranslator.render` + pgvector `1-(embedding<=>?)` + ACTIVE 代际子查询 | 真实执行（DB SQL） | Read :477-524 |
| D-06 | 同上 `:535,538` | lexicalSearch：`ts_rank(to_tsvector('simple',text_jieba))` + 同一 translator | 真实执行（DB SQL） | Read :526-557 |
| D-07 | 同上 `:590` | RRF 融合 `1.0/(RRF_K+rank+1)`；相关性分取通道最高值 | 真实执行 | Read :585-599 |
| D-08 | `R-APP/retrieval/RetrievalOrchestrator.java:100/:105/:129/:137/:149/:219` | 版本过滤/角色过滤/排序(fusionScore)/门禁(score≥minScore)/漏斗/版本惩罚 | 真实装配 + 执行 | Grep `admits|allowsRole|fusionScore|minScore|Funnel` |
| D-09 | `R-PERS/JdbcTenantScopedRetrieval.java:46/:47/:63-65` | `TransactionTemplate` 单事务；`set_config` 事务内绑定；同事务审计 INSERT | 真实执行（写 DB） | Read :42-69；本次核实写 5 列含 source_id |
| D-10 | `rag-bootstrap/application.yml:52/:103/:109/:133/:137` | `RAG_SHADOW_ENABLED`、`dimension:1024` 默认、`vector-type:vector` 默认、`min-score:0.38`、`min-trust-tier:OFFICIAL_TUTORIAL` | 真实配置（启动读取） | Grep 配置项 |
| D-11 | `rag.env:44-46` | `4096/halfvec` 显式配置（与 DDL 终态一致） | 真实配置（env 覆盖） | sed -n '40,50p' rag.env |
| D-12 | `deploy/sql/rag/01_rag_ddl.sql:384-393/:960-966/:973` | `embedding halfvec(4096)`；HNSW 刻意不存在（≤4000 维上限）；GIN jieba 索引 | 真实 DDL（结构定义） | sed -n '955,975p' + Grep |
| D-13 | `R-PERS/VectorColumn.java:48-57/:65-76` + `VectorColumnTest.java` | ANN 可行性：vector≤2000/halfvec≤4000；`halfvec(4096).indexable()==false` | 真实断言（测试守护） | Read VectorColumnTest 全文 |
| D-14 | `R-INFRA/embedding/LocalDeterministicEmbeddingPort.java:27-96` + `EmbeddingPorts.java:41-64` | 占位嵌入（1024 维 bigram 哈希）；装配选择 + fail-closed | 真实装配（默认占位路径） | Grep `DIM`/`create` |
| D-15 | `R-INFRA/text/ChineseTokenizer.java:25-124` | 中文 unigram+bigram 分词已实现（RAG-D05 L-3"未实现"是文档漂移） | 真实执行（索引+查询共用） | find + Grep 调用 :538 |
| D-16 | `R-INFRA/parsing/DoclingDocumentParserPort.java` 等 | Docling/中文分词已实现（生产解析端口） | 真实装配（生产解析） | find `*Docling*` 类与测试 |
| D-17 | `platform/agent-infra/.../retrieval/engine/RetrievalService.java:18/:30/:46/:65` | 0.75 阈值 / `vector*0.5+tfidf*0.5` 占位打分 | **运行期不可达**（无实例化点 + 模块无依赖） | Grep `new RetrievalService` 仅测试；pom 依赖 |
| D-18 | `platform/agent-infra/pom.xml:13` + `platform/pom.xml:66/:125` | agent-infra 模块仅在自身 pom + 父 dependencyManagement，无依赖方 | 静态存在（不可达） | Grep `<artifactId>agent-infra</artifactId>` |
| D-19 | `R-PERS/shadow/RagShadow.java:15-79` + `RagShadowReporter.java` + `RagShadowConfiguration.java` | 影子门面 no-op 语义 + 条件装配 + 阶段（只记/抛） | infra 可达（env 已开），数据未达（差真实流量） | Grep 装配 + 4.5.4 链 D |
| D-20 | `R-CORE/scope/RlsDependencyProbe.java:47-52` | `EXPECTED`=30（R1=6/R2=6/R3=16/CENTRAL=2） | 静态基线（测试守护） | Read :47-52 + MEMORY |
| D-21 | `deploy/sql/rag/14~17_*.sql` | 四批 RLS 退役 SQL ⛔DRAFT 未应用 | 静态（未执行） | 文件头标注 + MEMORY |
| D-22 | `R-CORE/scope/RagScope.java:10-46` / `RagScopeFilter.java` | 哨兵 nil UUID；复合外键三处 NULL 陷阱；哨兵拒绝 | 真实执行（构造器拒绝 + DB RAISE 42501） | Read :10-46 |
| D-23 | `rag/rag-persistence/src/test/.../RetrievalRepositoryIT.java:219-420` | 13 个 IT（泄题/版本/跨租户/分数语义/撤回/版权/全局桶） | 测试守护（真实 PG） | Read 测试类 |
| D-24 | `rag/docs/RAG-D05_检索策略与召回质量控制.md:259` | L-3"中文分词未实现"——文档漂移（已实现，见 D-15） | 文档（非运行期） | sed -n '248,264p' |
| D-25 | `docs/能力维度实现方式梳理报告.md:123/:127` | 三处不一致之一：把不可达草稿的"占位打分"当生产口径 | 文档（口径污染，R-0 修） | 诚实边界声明五 |

> **可达性汇总**：25 个锚点中，"真实装配/写入/执行/守护"19 个，静态基线/配置 4 个（D-12 DDL、D-20/21 RLS 基线、D-25 文档），**运行期不可达 1 个（D-17 草稿）**、**infra 可达但数据未达 1 个（D-19 影子）**。没有发现"生产检索链有类无注入点"的残留——生产主链（D-01~D-09）全部真实可达；最需警惕的仍是 D-17 不可达草稿造成的口径污染与 D-19 影子缺真实流量。

### 附录 E：落地实施方案（改动清单 + 验收门禁 + 回滚）

> 每条对应正文第七章的一个改进项 R-0~R-7。三级门禁口径见 7.11；本附录以"前置条件 / 改动清单（含片段）/ 三级验收门禁 / 反向验证 / 回滚策略 / 铁律对齐"六段式收敛，供实施排期直接取用。所有项均未落地（属规划）。**实施前一律复述 RAG 五条红线**（7.11 开头）：不动 0.38/RRF k=60/六轴谓词/门禁分分离；不在 agent-infra 草稿施力；不夹带改 halfvec(4096)/HNSW。

#### E-1（对应 R-0）清除不可达草稿检索引擎
- **前置条件**：确认 `new RetrievalService` 全仓仅测试引用、`agent-infra` 无依赖方（D-17/D-18 已核实）；确认无外部脚本调 `/api/v3/retrieval`（当前本就 404）。
- **改动清单**：① 删 `platform/agent-infra/.../retrieval/engine/` 4 main + 2 test 类；② `platform/pom.xml:66` 移除 `<module>agent-infra</module>`、`:125` 移除 dependencyManagement 条目；③ 删 `agent-infra/pom.xml`；④ 修正 `docs/能力维度实现方式梳理报告.md:123` 为真实口径（0.38/RRF k=60/占位=嵌入模型）；⑤ 新增 `RetrievalAuthoritySourceOfTruthTest`。
- **三级验收门禁**：单元（全仓 main 无 `VECTOR_SIMILARITY_THRESHOLD`/`TTL_MAX_AGE_MINUTES`、pom 无 agent-infra 模块）/ API（启动后 `GET /api/v3/retrieval/workspace`=404 非 500）/ 角色×任务（真实 Agent 对话 RAG 命中仍经 rag 服务返回、有 ragHits 计数）。
- **反向验证**：把 agent-infra 加回并恢复含阈值常量的类 → 单元扫描转红；端点意外仍在 → 404 断言转红。
- **回滚策略**：恢复目录与 pom 条目（草稿无生产调用方，零风险）；文档保持修正口径；同步模块数 14→13 的守卫。
- **铁律对齐**：铁律 6（反向验证）、铁律 14（跨进程契约按真实校验取值——`/api/v3/retrieval` 本就不存在）。

#### E-2（对应 R-1）向量打分闭环（剔除硬降维旧结论）
- **前置条件**：确认 `ag.embedding` 实际列 `halfvec(4096)`（DDL+rag.env+VectorColumnTest 三方一致）；确认 `RagStartupSelfCheck` 位置（`R-PERS/.../bootstrap/RagStartupSelfCheck.java`，P0 已删死方法）；确认 `EmbeddingPorts.create` 的 fail-closed 闸门。
- **改动清单**：① `RagStartupSelfCheck` 加列侧校验（`pg_attribute`/`format_type` 查实际类型维度，与 VectorColumn 比对，不符拒绝启动）；② `application.yml:103/109` 默认与 worker 侧同源对齐到 4096/halfvec 口径（不改 DDL）；③ 配 `RAG_EMBEDDING_BASE_URL`+`MODEL` 走真实 `OpenAiCompatibleEmbeddingPort`；④ 新增 `RealEmbeddingRetrievalIT`。**⛔ 不做"降维到 4000 建 HNSW"（已被复核纠偏）**。
- **三级验收门禁**：单元（列 mock `vector(1024)`、配置 `halfvec(4096)`→拒绝启动）/ API（真实模型 `scoreStdDev>0.05`、0.38 生效、`embedding_model` 两表一致）/ 角色×任务（真实教材检索 + 新旧代际零停机切换）。
- **反向验证**：列校验短路 return true → 单测转红；改回占位嵌入 → scoreStdDev 断言转红。
- **回滚策略**：base-url/model 置空回占位（fail-closed 不静默）；列校验保留；**不改 DDL/维度**；规模化 ANN 另走 MRL/二进制量化代际迁移。
- **铁律对齐**：铁律 8（fail-closed）、铁律 17（静态守卫≠运行时，配真实模型 IT）；规模化路径遵 pgvector 官方（2.4(13)）。

#### E-3（对应 R-2）混合检索三段式
- **前置条件**：**R-3 评测集是硬前置**（Qdrant 官方"无评测集别手调权重"）；确认 `candidate-pool-size` 配置项与 20s 读超时预算；确认 rerank 模型（境内 `bge-reranker-v2-m3` 优先）。
- **改动清单**：① `JdbcRetrievalRepository` 加可配候选池（默认 100）+ 词法通道内归一（`ts_rank/max_ts_rank`，`?::real` 绑定，铁律 2）；② 新增 `RerankPort`+实现，`rag.rerank.enabled=false` 默认关，插在④过滤后⑥排序前，rerank 分只排序不入门禁；③ 可选 query 改写/HyDE（默认关，与原文 RRF 合并）。
- **三级验收门禁**：单元（`RerankPortTest` 降序+topN；`ChannelScoreNormalizerTest` 归一前后 fusionScore 逐位相同）/ API（`HybridRetrievalIT` "仅词法命中"门禁后有命中、rerank 关时逐位一致、模型不可用降级但成功）/ 角色×任务（近邻负例 0.525 被压、正例 Recall@10 不降）。
- **反向验证**：归一短路→"仅词法命中"转红；过滤移到 rerank 后→"答案不进 rerank 输入"转红；rerank 分接门禁→"门禁恒不通过"转红（重演 2026-10-23）。
- **回滚策略**：两个 enabled=false 一键回基线；归一是行为改变，回滚需恢复 relevance 并重标 0.38；单租户灰度。
- **铁律对齐**：铁律 2（`?::real` 绑定）、铁律 6/17（顺序纪律反向验证）、铁律 8（rerank 不可用降级不 fail-closed）。

#### E-4（对应 R-3）检索质量评测体系
- **前置条件**：确认 `ScoredChunk.channel()` 四值（DENSE_VECTOR/LEXICAL/KNOWLEDGE_POINT/PREREQUISITE_EXPANSION）可分；确认 `rag/pom.xml` 可加 `rag-evaluation` 模块；人工标注资源（≥350 条）到位。
- **改动清单**：① 新增 `rag/rag-evaluation` 模块；② `RetrievalMetricsCalculator`（Recall@K/MRR/NDCG + 按 channel 召回 + 漏斗 + 误拒/漏拒）；③ 评测集 JSONL（≥200 正/≥100 负/≥50 应拒答）；④ CI 四级门禁 G/R/O/M；⑤ 可选 `RagAsFaithfulnessEvaluator`（judge 不同族+钉版本+对调取均值）。
- **三级验收门禁**：单元（手算样例 Recall@2=1.0/MRR@2=0.5）/ 链路（`RetrievalEvaluationIT` 每指标含阈值+实测两列）/ 角色×任务（教师标注、运维看 G/R 类阻断 PR）。
- **反向验证**：阈值改 0.99→RC-07 转红；门禁改 fusionScore→RC-09 转红；清空正例 relevantChunkIds→Recall 降且红灯。
- **回滚策略**：`verify` 阶段摘掉评测测试即停门禁（评测集与实现保留为资产）；无生产代码/DDL 改动。
- **铁律对齐**：铁律 6（三级门禁+反证）、铁律 11（LLM-as-judge 偏差按 Zheng et al. 处理）。

#### E-5（对应 R-4）多租户谓词统一收口
- **前置条件**：确认 M3 显式谓词已就位（M1/M3 收口 66 用例 DB IT 全绿）；确认 M2 影子 infra 已开但差真实流量；确认 14~17 SQL 仍 DRAFT。
- **改动清单**：① `RetrievalPredicateCoverageIT`（元数据扫描 + 双租户 IT）；② R1 6 处 set_config 按 `14_...sql:79-81` 移除——**严格先 SQL 后代码**；③ `RlsDependencyProbe.EXPECTED` 30→24→…→0，`RlsDependencyMetricTest` 同步；④ `RagShadowExitGateEvaluator`（14 天 + TL/QA 签核）。
- **三级验收门禁**：静态/单元（涉 chunk SELECT 必含 translator 产物，删一处→转红）/ API（app_agent NOBYPASSRLS 双租户 IT，两侧 chunk ⊆ 本租户∪哨兵）/ 角色×任务（跨租户 0 行，观察期达标才放行 M4）。
- **反向验证**：`TenantWithGlobal` 改 `TenantOnly`→"全局桶每租户可见"转红；出口评估器注入空信号→`eligible-for-M4=false`。
- **回滚策略**：出口门禁 `eligible=false` 即回 RLS 双写态；**严禁反序**（先删 set_config 后退役 SQL=Sev-0）；9 张无 RLS 表是影子期基线勿补 RLS。
- **铁律对齐**：铁律 1（GUC 事务内、app_agent 验隔离）、铁律 11（事务内 SQL 失败禁 fail-soft）、铁律 17（静态扫描配真实 IT）。

#### E-6（对应 R-5）摄取幂等与增量更新
- **前置条件**：确认 `IngestDedupRules` 活跃谓词与部分唯一索引 `rag_document_tenant_content_hash_uq` 同源；确认 P0 已删除的 `RagStartupSelfCheck` 死方法不影响；确认 **RAG-3 worker 摄取链无专用 DB IT** 需同期补。
- **改动清单**：① `19_v1_0_6_...sql` 加 `revision_no`/`superseded_by` + 部分唯一索引（ADD COLUMN 与建索引同事务）；② `saveChunkAndEmbedding` 改 `ON CONFLICT(chunk_id) DO UPDATE`（UPDATE 授权+SchemaDriftIT）；③ `rag_chunk_embedding` 加 `content_hash`（NULL 视为需重算）；④ "发布修订版"单事务（软失效旧 chunk，无物理删除）；⑤ 单载体 reingest 端点。
- **三级验收门禁**：单元（ChunkIdentityTest）/ 链路（IngestIdempotencyIT 双次行数不变；DocumentRevisionIT 旧 chunk retrievable=false 且数据仍在）/ 角色×任务（重传修订版，学生检索不到旧版答案）。
- **反向验证**：upsert 改回纯 INSERT→唯一约束冲突红；修订切换第 3 步失败→前 2 步回滚断言红。
- **回滚策略**：DROP 新增列/索引（数据保留）；upsert 回退失去幂等（仅止损）；软失效可恢复 `retrievable=true`。
- **铁律对齐**：铁律 7（UPDATE 授权+SchemaDriftIT）、铁律 8（无物理删除）、铁律 11（同事务）。

#### E-7（对应 R-6）策略影子双跑 + 切流报表
- **前置条件**：确认隔离影子命名空间为 `retrieval`；确认平台既有灰度 flag（`deployment.pilot.max-tenants`，维度 12）可复用；确认新表 RLS 模板（`01_rag_ddl.sql` 策略）。
- **改动清单**：① `RetrievalOrchestrator` 双跑分支（采样命中时跑当前/对照策略）；② `rag_retrieval_shadow` 表（**ENABLE+FORCE RLS + 读策略**，同步 `03_rag_roles.sql`）；③ `RagStrategyComparator`（Jaccard/位移/分布/漏斗/成本）；④ 报表端点 + 阈值判定；⑤ 命名空间 `retrieval.strategy` 隔离。
- **三级验收门禁**：单元（Jaccard 计算正确、样本不足→NEEDS_REVIEW）/ 链路（采样命中数=表行数、默认关闭表恒 0 行）/ 角色×任务（app_agent 跨租户查影子表=0 行）。
- **反向验证**：阈值恒 CUT_OVER→NEEDS_REVIEW 转红；采样率 0→表 0 行但主检索成功；block 模式违例→主检索不受影响。
- **回滚策略**：`rag.shadow.strategy.enabled=false` 一键关（默认关）；影子表保留；切流走平台灰度不新增原语。
- **铁律对齐**：铁律 1（新表挂 FORCE RLS + NOBYPASSRLS 验隔离）、铁律 7（同步角色授权）、铁律 6（采样只观测不阻断）。

#### E-8（对应 R-7）检索审计取证 + 评测反哺
- **前置条件**：确认 `rag_access_audit` schema 已预留 `chunk_id`/`source_id`/`request_digest`（`01_rag_ddl.sql:281-292`）；确认实际写入为 5 列（D-09）；确认 PII 正则用 JDK 引擎（铁律 21）。
- **改动清单**：① `JdbcTenantScopedRetrieval.java:63-65` INSERT 补 `chunk_id`(top-1)+`request_digest`(SHA-256 十六进制，只摘要不原文)；② 新增 `rag_retrieval_hit` 明细表（关联 audit_id）；③ `scripts/rag-eval-harvest.sh` 脱敏导出反哺 R-3；④ 清理走受控通道（禁物理删除）。
- **三级验收门禁**：单元（RequestDigestTest：同 query 同 digest、改字变、长 64、不含原文 4 字连续片段）/ 链路（RetrievalAuditIT：action=RETRIEVE、chunk_id 非空、digest 长 64；审计失败整次回滚）/ 角色×任务（合规取证可查；导出无 PII）。
- **反向验证**：digest 改存原文→"不含原文子串"转红；删 chunk_id 写入→非空率转红； violation 约束→检索回滚断言红。
- **回滚策略**：停写新增列/明细表即可；harvest 脚本独立停跑不影响生产。
- **铁律对齐**：铁律 2（无 RETURNING 用 update）、铁律 8（只存摘要、无物理删除）、铁律 21（PII 正则留 JDK 引擎）。

> **排期总原则**：E-1（口径止血）+ E-2 列侧校验 + E-5 谓词守卫先行（近期）；E-4 评测集 + E-3 三段式 + E-7 策略影子同批（中期，**E-4 是 E-3 硬前置**）；E-2 真实嵌入/ANN + E-6 摄取幂等 + E-8 审计反哺跟进；规模化 ANN（MRL/量化）属远期。任何一项实施前须复述 7.11 五条红线与三处纠偏（RRF/维度/草稿），引用旧结论标注"已被复核纠偏"。

### 附录 F：竞品实现扩充（官方文档锚点）

> 本次全部经 WebFetch 实际访问、内容支撑陈述。原报告附录 B 已列 OpenAI/LangChain/ADK/AutoGen/CrewAI/Agno + 向量库 + 理论文献；本表补 3.8 新增的 6 个竞品/参照 + 复核其中理论锚点，并明确"官方未明示/未能核实"处。

| 竞品 | 机制 | 关键取舍 | 对本维度的启发 | 官方来源（本次核实） |
|---|---|---|---|---|
| Elasticsearch（新增） | `rrf` retriever 组合 `standard`(BM25) + `knn`(向量)；`rank_constant` 即 k，默认 60；`rank_window_size` 控制参与融合的 top 窗口 | 换零标定融合 + 索引级性能，代价是 RRF 丢分差、无业务治理/租户语义 | 坐实本平台 `RRF_K=60`（`JdbcRetrievalRepository.java:107`）是业界默认；`rank_window_size` ↔ 本平台 `topK×3` 候选窗口 | https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html |
| Qdrant（新增） | `prefetch` 多路（dense/sparse/BM25）融合；`rrf`+`dbsf` 双算法；加权 RRF `weights`；DBSF 通道内归一 | 换融合灵活性与可调权重，代价是 DBSF 依赖检索器分数已校准；官方建议无评测集别手调权重 | DBSF"归一后再融合"↔ R-2 阶段一词法归一；"无评测集别调权重"坐实 **R-3 是 R-2 硬前置** | https://qdrant.tech/documentation/concepts/hybrid-queries/ |
| Azure AI Search（新增） | hybrid search 并行跑全文(BM25)+向量(HNSW/eKNN)、RRF 融合；`queryType=semantic` 触发 semantic ranker；融合分(`@search.score` 0.03 量级)与重排分(`@search.rerankerScore` 2.x)分字段 | 换托管混合+语义重排，代价是强 Azure 绑定、单 corpus≠租户、无业务治理 | 两分量级分离↔ DD-5"fusionScore 排序 / 相关性分门禁"分离（2026-10-23 事故教训）；semantic ranker 再次证明 rerank 是标准件 | https://learn.microsoft.com/en-us/azure/search/hybrid-search-overview |
| LlamaIndex（新增） | 默认余弦相似度；`Settings.embed_model` 全局/per-index 配置；多 provider；官方明确"换 embedding 模型后必须 re-index，不同模型向量不可互换" | 换编排完整与可插拔，代价是换模型停机重建 | 反证本平台 D-10 代际蓝绿零停机的领先（`JdbcRetrievalRepository.java:498-504` 只读 ACTIVE）；维度校验装配期拒绝（:242-250）更早拦截 | https://developers.llamaindex.ai/python/framework/module_guides/models/embeddings/ |
| Ragas（新增） | reference-free 评测：检索侧 Context Precision/Recall/Entities Recall/Noise Sensitivity，生成侧 Faithfulness/Response Relevancy | 换免人工标注的评测，代价是 LLM-as-judge 偏差（位置/ verbosity/自增强）须缓解 | R-3 可直接对标其指标定义；`RagAsFaithfulnessEvaluator` 须 judge 不同族+钉版本+对调取均值（Zheng et al. arXiv:2306.05685） | https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/ ； https://arxiv.org/abs/2309.15217 |
| Dify（新增） | 知识库产品化：Classic(自管 chunk/index/检索/Top-K/score threshold)/Agent(自动索引+Fast·Deep·Research 检索深度)；golden questions + evaluations + bad cases 运营 | 换低门槛与评测运营可视化，代价是固定/自动切分无结构语义、无版本/角色/版权治理、数据托管 | "bad cases 运营"↔ R-7 审计反哺评测集 + R-6 影子报表的产品级参照；本平台治理轴远超 Dify 但评测运营落后 | https://docs.dify.ai/en/cloud/use-dify/knowledge/overview |
| Anthropic（理论参照，新增） | `retrieval` 是 LLM 的 augmentation 之一；workflows=预定义代码路径 vs agents=LLM 动态自驱；建议"单 LLM 调用 + retrieval 通常够用" | 换概念清晰，代价是官方不展开 RAG 工程细节（原文用 retrieval 不用 RAG 缩写） | 5.4 分歧 3 的官方术语锚点：本平台 `doRag()` 确定性阶段=workflow（非 agentic），与维度 1"受控运行时"一致，是有意哲学选择非落后 | https://www.anthropic.com/engineering/building-effective-agents |
| RAG 原始论文（理论复核） | RAG-Sequence（整序列同批 passage）vs RAG-Token（每 token 不同 passage）；参数化+非参数化记忆 | 换免重训更新知识+可溯源，代价是原始论文无多租户/评测/召回漏斗 | 本平台禁用 RAG-Token 式"多版本 passage 混用"（五轴 `EditionScope` 硬过滤），是原始论文没有的教辅硬约束 | https://arxiv.org/abs/2005.11401 |
| Late Chunking（理论复核） | 先编码全文逐 token 表示、再按边界 mean pooling；chunk 向量含全文语境 | 换跨块上下文不丢失，代价是需长上下文嵌入模型 | 本平台无 late chunking（逐 chunk 独立编码），以"结构切分 + 知识点补全"替代（8.2(4)）；诚实标注为路线差异 | https://arxiv.org/abs/2409.04701 |
| pgvector（理论复核 · 维度专项） | `vector`/`halfvec`/`bit`/`sparsevec`；HNSW/IVFFlat 索引上限 vector≤2000、halfvec≤4000、bit≤64000；FAQ 给"超 2000 维"三路径：半精度/二进制量化/子向量(MRL)/降维 | 换精确+近似检索与 PG 生态融合，代价是 ANN 有维度上限 | **本维度最关键官方背书**：`halfvec(4096)` 建不了 ANN 是硬上限（`ddl:960` 取舍正确），规模化走 MRL/二进制量化——**禁硬降维**（会与 qwen3-embedding:8b 输出冲突） | https://github.com/pgvector/pgvector |

> **未能核实项**（诚实标注，禁止臆造）：① **Pinecone 多租户页**（附录 B 列 `docs.pinecone.io/guides/index-data/multitenancy`）本次 WebFetch 返回 404，未能逐字核实 per-index/namespace/record 三粒度表述，仅沿用 2.1(8) 既有引用并标注"本次未能核实"；② **Weaviate 多租户页**（`docs.weaviate.io/weaviate/managing-collections/multi-tenancy`）本次 404，同理；③ **Vespa retrieval 页**（`vespa.ai/documentation/retrieval.html`）本次只返回图片、无正文，未纳入竞品结论；④ **OpenAI Retrieval guides**（`platform.openai.com/docs/guides/retrieval`）本次仍被 Cloudflare 拦截（与诚实边界声明二·2 一致），其 `hybrid_search`/`rewrite_query` 描述依据官方搜索摘要，**未逐字核对**；⑤ **RAGAS 论文 EACL 2024 Demo** 出处本报告按 arXiv:2309.15217 引用（本次核实 v2 2025-04），会议归属未逐字核对官方页。以上均未作为新增结论的唯一依据。

> **新增 URL 统计**：本次 WebFetch 实际访问并内容支撑的官方 URL 共 **11 个**（Elasticsearch 1 + Qdrant 1 + Azure 1 + LlamaIndex 1 + Ragas 2 + Dify 1 + Anthropic 1 + arXiv 3 + pgvector 1，其中 RAGAS 文档与论文计 2），全部列于上表与 3.8，可访问且内容支持相应陈述。

---

### 附录 G：配置项全量矩阵与「生效性」判定表

> **本附录为第三轮追加**，不改写既有内容；与既有正文冲突处（如"向量维度漂移"口径）已在 5.7.4 标注。
> **判定四问**（沿用项目新铁律「运行期可达性审计」）：① 谁定义？② 谁读取？③ 读取后改变了什么行为？④ 有没有测试/门禁证明它生效？
> **生效性判定**：`✅ 真生效` / `⚠️ 条件生效`（附条件）/ `❌ 默认不生效`（附默认路径）/ `🟡 漂移风险`（配置源与消费方口径不一致）/ `❓ 本轮未能核实`。
> **配置源**：`rag/rag-bootstrap/src/main/resources/application.yml`（检索服务，`rag.*` 段）、`rag/rag-worker/src/main/resources/application.yml`（摄取 Worker，`rag.*` 段）、`rag.env` / `rag.env.example`（检索域 env 真相源）、`platform/platform-bootstrap/src/main/resources/application.yml`（平台侧 `agent.rag.*` / `agent.context.*`）。
> **本轮口径**：每行的"消费方"均来自本轮实际 Read/Grep 命中；**行号为本轮复核值**，与既有正文不一致时以本表为准。**代码常量也列入**（如 RRF k=60），因为它同样是"不可静默变更的口径"。

| # | 配置键 | 默认值 | 定义位置 | 消费方（类:行号） | 生效条件 | 生效性判定 | 静默失效风险 / 处置建议 |
|---|---|---|---|---|---|---|---|
| G-01 | `rag.retrieval.min-score`（`RAG_RETRIEVAL_MIN_SCORE`） | `0.38` | `rag-bootstrap/application.yml:133` | `RagInfrastructureConfig.java:72-73`（`@Value` 兜底 0.38）→ `RagInfrastructureConfig.java:310-313`（装配 `RetrievalOrchestrator(retrievalPort, minScore)`）→ `RetrievalOrchestrator.java:56-62`（构造期定格，范围校验 [0,1]）→ `:136-138`（门禁比较 `s.score() >= minScore`） | 恒生效（构造期定格，运行期不可改） | ✅ 真生效 | 风险：**标定值只有 27 条样本**（`application.yml:127-131` 注释自陈：正例中位 0.504、跨领域负对照中位 0.339），且"换模型/换语料必须重标"（`rag.env:49-50` 同样注明）却**无回归机制**能发现"重标后忘了改" ⇒ 建议：⛔ **不调低 0.38**（RAG 红线：标定值须走 RAG-D08 §4），改为在 R-3 落地后把"阈值漂移"做成 CI 断言 |
| G-02 | `rag.retrieval.min-trust-tier`（`RAG_RETRIEVAL_MIN_TRUST_TIER`） | `OFFICIAL_TUTORIAL` | `rag-bootstrap/application.yml:137` | `RagInfrastructureConfig.java:301-308`（构造期传入 `TrustTier.valueOf`）→ `JdbcRetrievalRepository.java:262-266`（展开为「达标等级 IN 列表」）→ `TrustTier.java:74-76`（`meets` 按 weight 比较） | 恒生效（构造期定格） | ✅ 真生效 | 风险：门槛是**部署级**策略，**逐租户差异化未落地**（RAG-R23）；另一个坑已规避：⛔ 禁写 `trust_tier >= ?`（PG 枚举序数与权威语义相反，`:255-260` 注释记载 2026-10-27 线上实测踩坑）。建议：保留现状，R-23 落地时须同步改写谓词生成处，勿新增第二处枚举序比较 |
| G-03 | **RRF k=60（代码常量，无配置面）** | `60.0` | `JdbcRetrievalRepository.java:107`（`private static final double RRF_K = 60.0;`） | 同文件 `:590`（`1.0 / (RRF_K + (rank + 1))`）；口径守护用例 `RetrievalRepositoryIT.java:266-282`（断言 `fusionScore() < 0.05` 且 `score() > fusionScore()`） | 恒生效（编译期常量） | ✅ 真生效 | 风险：**无配置面 ⇒ 调参须改代码发版**；⛔ **不动 k=60**（RAG 红线，`RetrievalRepositoryIT` 钉死）。业界对照：Elasticsearch `rank_constant` 官方默认 **60**（本轮 WebFetch 复核 <https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html>）⇒ 本值是继承共识。建议：**明确写成"不可配置项"**并在 `application.yml` 加一行注释，避免后续有人把它变成配置键（那会与既有 IT 的量纲断言冲突） |
| G-04 | `rag.embedding.dimension` + `rag.embedding.vector-type`（`RAG_EMBEDDING_DIMENSION` / `RAG_EMBEDDING_VECTOR_TYPE`） | `1024` / `vector` | `rag-bootstrap/application.yml:103` / `:109`；`rag.env:45-46`（生产覆盖为 **4096 / halfvec**） | `RagInfrastructureConfig.java:104-105`（dimension 读配置，但**只用于真实模型适配器**）→ `:288-292`（`VectorColumn(type, embeddingPort.dimension())`，**维度取自端口而非配置**）→ `JdbcRetrievalRepository.java:242-250`（装配期维度不符**直接拒绝装配**）；DDL 侧 `01_rag_ddl.sql:389`（`embedding public.halfvec(4096)`）、`:960-966`（HNSW **刻意不存在**）；单测 `VectorColumnTest.java:46-51`（4096 维 `indexable() == false`） | 维度取自 `EmbeddingPort.dimension()` ⇒ **配置里的 dimension 只在真实模型路径生效**；占位路径恒为 1024（`LocalDeterministicEmbeddingPort.java:30`） | 🟡 漂移风险 | 风险：**默认 yml（1024/vector）与 DDL 终态（halfvec(4096)）不一致**，只有 env 覆盖才对齐；`VectorColumn` 的校验只比"配置 vs 端口"，**不校验 vs DB 实际列**（既有正文 5.3 盲区 4 的真问题）。⛔ **不降维、不夹带改 HNSW**（RAG 红线）；正确处置是 ① 把生产值写进 `rag.env.example` 的**必填**段并加启动期"配置 vs 列类型"断言（扩 `RagStartupSelfCheck`），② 规模化后按 `01_rag_ddl.sql:964` 的既定路径二选一（MRL 截断 ≤4000 维 / 二进制量化） |
| G-05 | `rag.embedding.base-url` + `rag.embedding.model`（`RAG_EMBEDDING_BASE_URL` / `RAG_EMBEDDING_MODEL`） | **空串** | `rag-bootstrap/application.yml:100` / `:102`；`rag.env:37-38`（本地 Ollama + `qwen3-embedding:8b`） | `RagInfrastructureConfig.java:94-105`、`:164-195`（`embeddingPort()`，含统一模型目录优先分支 `:169-191`）→ `EmbeddingPorts.java:41-64`：**base-url 空 ⇒ `LocalDeterministicEmbeddingPort`（占位）**；base-url 有而 model 空 ⇒ **fail-closed 抛错**（`:50-61`，绝不静默降级） | base-url 非空 → 真实模型；为空 → 占位（且需 `rag.local-ports.enabled=true`） | ⚠️ 条件生效 | 风险：**占位嵌入模型是默认装配路径**（`EmbeddingPorts.java:44-49`；`LocalDeterministicEmbeddingPort.java:13-23` 自陈"不是生产级嵌入模型……绝不用于语义相关性判定的生产结论"）⇒ 未配 base-url 的部署**跑得通但语义是假的**（1024 维 bigram 哈希）。建议：把"base-url 非空"写进**部署必填清单**并让启动横幅打印"嵌入模型=真实/占位"（当前仅占位路径打 ERROR，`application.yml:64-66` 语义） |
| G-06 | `rag.local-ports.enabled`（`RAG_LOCAL_PORTS_ENABLED`） | `false` | `rag-bootstrap/application.yml:68`；`rag-worker/application.yml:34`；`rag.env:32`（本地置 true） | `RagInfrastructureConfig.java:91-92` + `EmbeddingPorts.java:46-48`（`LocalPortGate.requireEnabled(localPortsEnabled, …)`）→ `LocalPortGate.java:43`（报错文案点名该键）；同键亦管解析/蕴含两个占位端口 | 默认 false ⇒ 占位端口**拒绝装配**（应用拒绝启动） | ✅ 真生效 | 风险：⛔ **不得用放开占位闸门来"绕过"维度/模型报错**（`rag.env:42-44` 明写"不要靠放开占位闸门绕过"）。建议：保持 fail-closed；`rag.env` 是**本地开发**文件，生产部署应显式 `false` |
| G-07 | `rag.shadow.enabled`（`RAG_SHADOW_ENABLED`） | `false` | `rag-bootstrap/application.yml:52`；`rag-worker/application.yml:28`；`rag.env:74`（已置 true） | `RagShadowConfiguration.java:22`（类级 `@ConditionalOnProperty(prefix="rag.shadow", name="enabled", havingValue="true")`）⇒ **关闭时整个影子装配不存在**，采集点全 no-op（`RagShadow.java:35-43`）；开启后采集点 `JdbcRetrievalRepository.java:273`（检索）、`:417`（依据取回） | 需 env 置 true；`rag.env` 已置 | ⚠️ 条件生效 | ⚠️ **本轮最有价值的发现之一**：影子计数器是**内存实现**（`InMemoryRagShadowMetrics.java:14`），`RagShadowCollector.java:42-51` 的 `snapshot()` **无主代码消费方**（本轮 grep 仅测试引用）⇒ **"已开启"只意味着采集点被激活，读数既不出进程也不持久化**。建议：M2 观察期出口必须先补"快照出口"（健康端点或写库），否则"读数恒为 0"与"没有违例"不可区分（铁律 6「有一行 ≠ 数字算对了」） |
| G-08 | `rag.shadow.sample-rate`（`RAG_SHADOW_SAMPLE_RATE`） | `1.0` | `rag-bootstrap/application.yml:53`；`rag.env:75` | `RagShadowConfiguration.java:29`（`@Value`）→ `:31-32`（`sampleRate >= 1.0` ⇒ `sampling = null` ⇒ 全量）→ `RagShadowReporter.java:37-39`（`shouldEvaluate`） | 恒生效（1.0 ⇒ 全量，无采样策略） | ✅ 真生效 | 风险：全量采样 ⇒ 每次检索都走一次比对（**性能代价未量化**）。建议：观察期保持 1.0（样本足），但必须与 G-07 的"快照出口"一起评估开销；不在无读数出口时降采样（降了更看不见） |
| G-09 | `rag.shadow.block-on-violation`（`RAG_SHADOW_BLOCK`） | `false` | `rag-bootstrap/application.yml:54`；`rag.env:76` | `RagShadowConfiguration.java:30` → `RagShadowReporter.java:33`（构造入参）→ `:56-70`（true 时 mismatch / taint 抛 `RagShadowViolationException`）；`reportRlsBind` **恒不抛**（`:48-50`） | 仅 P2 双写期才应为 true | ❌ 默认不生效 | 风险：误以为"违例会被拦"实则 P1 只记数；且 `reportRlsBind` 即使在阻断模式也不抛（它是"退役清单条目"不是违例）。建议：**保持 P1=false**，把翻转写进 M4 出口门禁清单并留人工执行记录 |
| G-10 | `rag.indexer.tenants` | `[]`（空列表） | `rag-worker/application.yml:81`（`tenants: []`）；`RagIndexerProperties.java:31`（字段默认 `List.of()`） | `IngestJobConsumer.java:102-106`（**空 ⇒ 直接返回 null，不领任何 job**）→ `:119`（逐租户 `claimNextJobForTenant`）；`IngestJobSweeper.java:70-73`（**空 ⇒ 租户侧僵尸任务一个都不扫**）；`ReindexRunner.java:87-89`（空时打 WARN 提示受 RLS 限制不能跨租户枚举） | 需显式列出待处理租户 UUID（受 RLS 限制，Worker 不能跨租户枚举） | ❌ 默认不生效 | ⚠️ **本附录最严重的一行**：`rag.env` / `rag.env.example` **本轮 grep 均未找到 `RAG_INDEXER_TENANTS` 键** ⇒ 默认部署下**摄取消费循环与租户侧 Sweeper 全链路空转**（表现："上传了教材，任务永远 PENDING，页面一直转圈"，且不报错）。建议：① 部署清单把租户清单列为**必填**并写进启动横幅；② `tenants` 为空时**启动即 WARN/FAIL-fast**（当前只有 `ReindexRunner` 的 WARN，消费循环与 Sweeper 静默） |
| G-11 | `rag.ingest.sweeper.timeout-minutes` | `30` | **yml 未声明**（本轮 grep `rag-worker/application.yml` 无该键）→ 代码兜底 `IngestJobSweeper.java:44-45` | `IngestJobSweeper.java:43-44`（`@Value("${rag.ingest.sweeper.timeout-minutes:30}")`）→ `:80`（逐租户 `failTimedOutRunningJobs(t, timeoutMinutes)`） | 依赖 G-10 的租户清单非空 | ⚠️ 条件生效 | 风险：阈值过小会**误杀正常长任务**（类注释 `:36-42` 自陈"单册教材可能需要 10 分钟以上……误杀比漏杀更难排查"）。建议：保持 30 分钟，但把"超时置 FAILED"写入运维告警口径；⚠️ 另注意 `rag.ingest.sweeper.enabled` / `rag.ingest.consumer.enabled` 也**未在 yml 声明**，靠 `@ConditionalOnProperty(matchIfMissing = true)`（`IngestJobConsumer.java:34`、`IngestJobSweeper.java:28`）⇒ 默认恒开，属**隐式默认**，建议显式写进 yml |
| G-12 | `rag.ops.enabled`（`RAG_OPS_ENABLED`） | `true` | `rag-bootstrap/application.yml:85-86`；`rag.env:55` | `application.yml:85-93`（独立运维数据源）→ 运维通道 Bean（`RagOpsSessionPort`，经 `ObjectProvider` 可选装配，见 `RagInfrastructureConfig.java:243-247`）；启动自检 `RagStartupSelfCheck.java:228-263`（**主数据源与运维数据源角色必须不同**） | 默认开；url/username 缺失 ⇒ **拒绝启动**（`application.yml:74-78`） | ⚠️ 条件生效 | 风险：`rag_ops` 带 **BYPASSRLS**，若与主数据源同角色 ⇒ **租户隔离整体失效**（`rag.env:52-54` 与 `application.yml:80-83` 均自陈）；自检已拦截（`RagStartupSelfCheck.java:255-263`）。建议：保留 fail-closed；把"运维库未接入 ⇒ 平台档写 503、租户档不受影响"写进运维手册 |
| G-13 | `agent.rag.base-url`（平台侧，`RAG_BASE_URL`） | `http://127.0.0.1:8081` | `platform/platform-bootstrap/src/main/resources/application.yml:68-69` | `AgentChatConfiguration.java:71`（键常量）→ `:85`（装配 `RagRetrievalPort`）→ `HttpRagRetrievalGateway.java:77-92`（构造 `URI`；`:89-90` 空串拒绝装配） | 恒生效 | ✅ 真生效 | 风险：**默认回环地址**在容器/多机部署下必然不可达 ⇒ 表现为 `RAG_UNAVAILABLE`（`AgentChatOrchestrator.java:524-533`）——fail-closed、不静默零命中，但**每个请求都白等 3s 连接超时**（`HttpRagRetrievalGateway.java:65`）。建议：把 `RAG_BASE_URL` 列入部署必填 env；容器部署必须显式覆盖 |
| G-14 | `agent.context.enabled`（`AGENT_CONTEXT_ENABLED`） | `false` | `platform/.../application.yml:232` | `RagHitQuotaAdapter.java:81-82`（**第一行**就短路返回 null）→ `AgentChatOrchestrator.java:540-544`（`allocation == null` ⇒ 走 legacy 路径）→ `:561-562`（`serializeHits` 固定口径）→ `:577-580`（`MAX_INJECT_HITS = 8`、`MAX_HIT_TEXT = 1200` 截断，见 `:128-129`） | 关闭 ⇒ RAG 命中区 token 配额**整条路径不生效** | ❌ 默认不生效 | 风险：**RAG 上下文注入处于影子期**——命中区不按 token 预算裁剪，只靠固定条数/字数截断；当语料变长时，"固定截断"与"按 token 裁剪"的口径差会直接变成上下文膨胀。⚠️ 这是**上下文维度**（06）的开关，但**本维度的注入预算受它控制**，改本维度前必须先看该开关。建议：保持 `false` 直到 S2 接线；接入前先落附录 I-09 的注入量断言 |
| G-15 | `agent.context.rag-quota.*`（token-budget / max-hits / max-hit-tokens / min-kept-tokens） | `4096` / `8` / `1200` / `64` | `platform/.../application.yml:267-271` | `RagHitQuotaConfiguration.java:37-45`（四项 → `RagHitQuotaAdapter`）→ `:49-63`（**启动期 fail-fast 校验**：非正 / min>max / 单块上限>总预算 / 超硬顶 均拒绝启动） | 依赖 G-14 为 true | ⚠️ 条件生效 | 风险：四项配置**装配期校验齐备**（这一点比多数同类配置严谨），但因 G-14 默认 false 而**整段不生效**。建议：与 G-14 一并收口；不要在 G-14 关闭期间改这四项（改了不起作用，易误判为"配额没起作用是参数问题"） |

**本维度配置项统计**：共 **15** 项 —— ✅真生效 **6**（G-01/G-02/G-03/G-06/G-08/G-13）/ ⚠️条件生效 **5**（G-05/G-07/G-11/G-12/G-15）/ ❌默认不生效 **3**（G-09/G-10/G-14）/ 🟡漂移风险 **1**（G-04）/ ❓未能核实 **0**。

**「默认不生效」项的共同根因**（一段归纳 + 是否属本项目已知铁律范围）：

三项的失效形态**不同源**，但共享同一个结构性缺陷——**配置的"生效"被拆成了三层，而门禁只覆盖了第一层**：

- **G-09 `block-on-violation`**：配置**被真实读取**（`RagShadowConfiguration.java:30`），消费结果是"**不阻断**"。它不是"忘了消费"，而是 P1→P2 的**阶段闸门**，当前阶段的正确值就是 `false`。属**设计取舍**，不属缺陷。
- **G-14 `agent.context.enabled`**：同样是被读取且被正确消费，但关闭态的语义是"走 legacy 逐位等价路径"（`RagHitQuotaAdapter.java:81-82`）。属**设计取舍**（影子期纪律），不属缺陷；缺陷在**没有把它列入"本维度哪些能力处于影子期"的对外说明**。
- **G-10 `rag.indexer.tenants`**：配置**有键、有默认值、有绑定类**（`RagIndexerProperties.java:31`），但**值为空 ⇒ 全部下游空转**（消费循环不领任务、Sweeper 不扫僵尸、重索引跳过），且**空清单不产生任何告警**。属**能力缺失**（部署基线未固化 + 缺 fail-fast），且**后果是摄取链整条静默停摆**——这是本附录最该优先修的一行。
- **共同根因**：项目现有的「运行期可达性审计」四问（谁定义/谁读取/改变什么行为/有无门禁）能抓住 G-09（被装配了）；但**抓不住 G-10**——键被定义、被绑定类读取、消费方代码路径完整，只是**生效前提不成立**。缺的是第四问的加强版：「**它的生效前提是否成立**」（租户清单非空？生产 env 已覆盖？镜像里有没有这个文件？）。建议把这一问补进铁律清单，并把 `rag.indexer.tenants` 这类"清单型键"纳入**启动期 fail-fast**（空 ⇒ 拒绝启动或至少 ERROR 级横幅），避免"看起来在跑、实际永远领不到"。

### 附录 H：场景级端到端时序推演（5 个关键场景）

> **本附录为第三轮追加**，不改写既有内容。每个场景给出：触发入口 → 前置状态 → ASCII 时序（跳数与锚点表**逐跳一致**）→ 每跳源码锚点 → 失败分支（错误在哪一跳被捕获/转换）→ 可观测落点 → 排查断点行号。
> **覆盖类别**：H-1 正常主链路 / H-2 失败链路（组件级 vs 零命中）/ H-3 权限安全（跨租户 scope 谓词隔离）/ H-4 并发竞态（摄取任务多 worker 领取）/ H-5 崩溃恢复（僵尸摄取任务接管）——**5 类全覆盖**（规范要求 ≥3 类）。
> **本轮未运行任何测试**：所有"实测"字样一律写「未实测（需按本报告门禁口径在实施阶段验证）」；源码注释中记载的历史实测（如 `JdbcIngestJobRepository.java:361-376` 的 2026-11-06 灰度实测）按**既有代码注释的事实陈述**引用，并注明其来源为代码注释而非本轮实跑。

#### H-1 场景：学生提问触发一次完整检索并把命中注入模型上下文（正常主链路）

- **触发入口**：Agent 编排的阶段 ④（RAG）——`AgentChatOrchestrator.doRag()`（`AgentChatOrchestrator.java:511-518`）。
- **前置状态**：租户 T 已验签令牌可用；`rag_chunk_embedding` 中该租户已有 ACTIVE 代际向量（`rag_index_generation`，`deploy/sql/rag/09_v1_0_3_index_generation_alter.sql:33`）；嵌入模型可达（`RAG_EMBEDDING_BASE_URL` 非空，见附录 G-05）。

```
编排器(worker)      HttpRagRetrievalGateway   RagJwtAuthFilter   RagRetrievalController
   |                     |                          |                     |
   |(1) rag.retrieve()   |                          |                     |
   |-------------------->|                          |                     |
   |                     |(2) POST /api/v1/retrieval|                     |
   |                     |   Bearer + X-Tenant-Id   |                     |
   |                     |------------------------->|                     |
   |                     |                          |(3) 验签+归属绑定     |
   |                     |                          |   不符=>403          |
   |                     |                          |-------------------->|
   |                     |                          |   (4) requireTenant |
   |                     |                          |   scopeMode null=>STRICT
   |                     |                          |-------------------->|
   |                     |                          |                     |(5) 事务内 set_config + scope=TenantWithGlobal
   |                     |                          |                     |    (6) 七步编排: 召回→版本→角色→补全→排序→门禁→漏斗
   |                     |                          |                     |    (7) 稠密(pgvector余弦+代际过滤) + 词法(ts_rank)
   |                     |                          |                     |    (8) RRF k=60 融合; 排序键=融合分/门禁键=相关性分
   |                     |                          |                     |    (9) 同事务写 rag_access_audit(RETRIEVE)
   |                     |<---(10) 200 + hits[] -----|---------------------|
   |(11) 白名单解析(7字段) → 配额裁剪 → RAG_GUARD 围栏注入                            |
   |<---------------------------------------------------------------------------|
```

- **逐跳锚点表**（11 跳，与时序图一致）：

| 跳 | 动作 | 源码锚点 | 落点（表/事件/日志） | 失败语义 |
|---|---|---|---|---|
| 1 | 编排阶段 ④ 取 `topK` 并发 `RAG_QUERY` 事件 | `AgentChatOrchestrator.java:515-518` | `state.events`：`RAG_QUERY` | `topK` 越界在下游被拒（见第 2 跳） |
| 2 | 平台→rag HTTP：`POST /api/v1/retrieval`，Bearer + `X-Tenant-Id`，3s 连接 / 20s 读超时 / 512KB 上限 / 禁重定向 | `HttpRagRetrievalGateway.java:62`（路径）、`:65-69`（超时与上限）、`:114-121`（请求构造）、`:130-132`（读取期封顶） | 无（出进程） | `topK` 越界 ⇒ 抛 `RagUnavailableException`（`:108-110`）；构造失败 ⇒ 抛（`:122-124`） |
| 3 | rag 侧鉴权：验签（iss/aud/exp）+ 租户归属绑定 | `RagJwtAuthFilter.java:169-179`（401）、`:180-189`（403）、`:194-207`（写域/主体属性） | `LogContext.setIdentity`（`:210`） | 缺/坏令牌 ⇒ **401**；`X-Tenant-Id` ≠ 令牌 `tenant_id` ⇒ **403**（终态，不给重试，符合铁律 15） |
| 4 | 控制器：校验租户头 + 构造请求（`scopeMode` 为 null ⇒ `STRICT`） | `RagRetrievalController.java:89-90`、`:163-184`（缺/非法/哨兵 ⇒ 400）、`:186-191`、`:200-207`（`:201` 默认 STRICT） | 无 | 缺 `X-Tenant-Id` ⇒ **400**；哨兵做会话身份 ⇒ **400**（`:172-176`）；未知版本轴取值 ⇒ **400**（`:221-230`） |
| 5 | 事务内 `set_config('app.tenant_id', ?, true)` + 判定作用域 `TenantWithGlobal` | `JdbcTenantScopedRetrieval.java:46-48`、`:53` | 会话变量 `app.tenant_id`（事务局部） | 事务外绑定 ⇒ 变量立即失效（类注释 `:16-20`）；作用域在**入口层**判定，编排层只搬运（`RetrievalOrchestrator.java:70-78`） |
| 6 | 七步编排：知识点解析 → 多路召回 → 版本过滤 → 角色过滤 → 知识点补全 → 排序 → 分数门禁 | `RetrievalOrchestrator.java:82`、`:85`、`:99-101`、`:104-106`、`:110-122`、`:127-130`、`:136-138` | 内存中的五段漏斗（`:149-154`） | 门禁未过 ⇒ 走第 9 跳的"合法零命中"分支；编排层**不合成作用域**（否则隔离两处各自为政） |
| 7 | 双通道 SQL：稠密 `1-(e.embedding <=> ?)`（含 ACTIVE 代际过滤）+ 词法 `ts_rank(to_tsvector('simple', text_jieba), to_tsquery('simple', ?))` | `JdbcRetrievalRepository.java:472-524`（稠密，`:492` 余弦、`:498-506` 代际）、`:526-557`（词法，`:538-539` ts_rank、`:549` `@@`） | 无（只读） | 嵌入不可用 ⇒ `EmbeddingUnavailableException` ⇒ 控制器转 **503**（`RagRetrievalController.java:94-102`）；代际无记录 ⇒ COALESCE 兜底"不过滤"（`:503-506`） |
| 8 | RRF 融合（k=60）+ **排序键/门禁键分离** | `JdbcRetrievalRepository.java:277-296`（融合）、`:585-599`（`:590` RRF 公式、`:594` 相关性分取各通道最大值） | 无 | ⛔ 不动 k=60 与"排序键=融合分 / 门禁键=相关性分"的分离（RAG 红线；`RetrievalRepositoryIT.java:266-282` 钉死） |
| 9 | 分数门禁 + 五段漏斗 + **审计同事务写入** | `RetrievalOrchestrator.java:136-138`、`:148-154`、`:156-159`（门禁未过打 debug）；`JdbcTenantScopedRetrieval.java:62-66` | `rag_access_audit` 增一行（`action='RETRIEVE'`） | 审计写失败 ⇒ **整次检索回滚**（不允许"检索成功但无痕迹"，`:59-61`） |
| 10 | 返回 `RetrievalResultDto` | `RagRetrievalController.java:103` | HTTP 200 | — |
| 11 | 平台侧解析白名单 7 字段 → 配额裁剪 → 不可信围栏注入 | `HttpRagRetrievalGateway.java:176-200`（`:183-186` 缺 hits ⇒ 抛）、`:202-221`（`:208-214` 空白命中丢弃）；`AgentChatOrchestrator.java:540-544`、`:561-563` | `RAG_HITS` / `RAG_QUOTA` 事件（`:547-556`） | 畸形响应 ⇒ 抛 ⇒ 编排器转组件级 error（见 H-2）；配额异常 ⇒ fail-soft 回落 legacy（`:611-615`） |

- **失败分支**（≥2 条，均可复核）：
  1. **嵌入模型不可用**（第 7 跳）⇒ `EmbeddingUnavailableException` ⇒ 控制器返回 **503** 且带可执行文案（`RagRetrievalController.java:94-102`）；**绝不静默退化成纯词法检索**（分数口径会悄悄变）。
  2. **门禁未达 0.38**（第 9 跳）⇒ 合法零命中：`hits=[]`、`funnel` 显示"门禁后 = 0"、打 debug 日志（`RetrievalOrchestrator.java:156-159`）⇒ **不是故障**。
  3. **审计写失败**（第 9 跳）⇒ 事务回滚 ⇒ 检索结果**不返回**（"不允许检索成功但无痕迹"，`JdbcTenantScopedRetrieval.java:59-61`）。
  4. **畸形命中**（第 11 跳）⇒ `chunkId`/`text` 空白即丢弃（`HttpRagRetrievalGateway.java:208-214`）；响应体超 512KB ⇒ 读取期抛 IOException（`:130-132`）。
- **可观测落点**：`rag_access_audit`（`RETRIEVE` 行）；平台侧 `RAG_QUERY` / `RAG_HITS` / `RAG_QUOTA` 事件与 `state.step("RAG", …)`（`AgentChatOrchestrator.java:517`/`:553`/`:555`/`:557`）；rag 侧门禁未过的 debug 日志。**无观测点**：检索漏斗五段计数**跨进程丢失**（`HttpRagRetrievalGateway.java:176-200` 只取白名单字段）⇒ 调参闭环在本场景**是断的**（既有正文 5.3 盲区 3）。
- **排查断点**（建议打断的行号）：`AgentChatOrchestrator.java:522`（发起检索）、`RagJwtAuthFilter.java:181-188`（归属绑定）、`RagRetrievalController.java:89`（租户校验）、`JdbcTenantScopedRetrieval.java:47`（绑 GUC）、`JdbcRetrievalRepository.java:492`（余弦 cast）/`:538`（ts_rank）、`RetrievalOrchestrator.java:137`（门禁）、`JdbcTenantScopedRetrieval.java:63`（审计插入）。

#### H-2 场景：RAG 组件不可用 —— "检索失败"必须与"零命中"严格区分（失败链路 / 终态误报防线）

- **触发入口**：`AgentChatOrchestrator.doRag()` 调用 `rag.retrieve(...)`（`AgentChatOrchestrator.java:520-523`）。
- **前置状态**：rag 服务不可达 / 返回非 2xx / 返回非法 JSON / 响应缺 `hits`；或嵌入模型服务挂掉。

```
编排器              网关(HTTP)              rag 服务/嵌入模型        审计与事件
  |                    |                          |                     |
  |(1) rag.retrieve    |                          |                     |
  |------------------->|                          |                     |
  |                    |(2) 连接失败/20s 超时      |                     |
  |                    |    => IOException        |                     |
  |                    |(3) 非 2xx (401/403/500)   |                     |
  |                    |    => 抛 RagUnavailable  |                     |
  |                    |(4) 非法 JSON / 缺 hits   |                     |
  |                    |    => 抛 RagUnavailable  |                     |
  |&lt;-- 异常 ---------|(5) 编排器捕获              |                     |
  |                    |                          |                     |(6) RAG_UNAVAILABLE 事件
  |                    |                          |                     |    + Skill(error)
  |                    |                          |                     |    + RagHits([])
  |(7) return null（不注入任何 RAG 上下文）                                    |
  |---------------- 对照分支: 门禁未过 => 200 + hits=[] + funnel 门禁后=0 ------|
```

- **逐跳锚点表**（7 跳，与时序图一致）：

| 跳 | 动作 | 源码锚点 | 落点（表/事件/日志） | 失败语义 |
|---|---|---|---|---|
| 1 | 编排发起检索 | `AgentChatOrchestrator.java:520-523` | 无 | — |
| 2 | 连接失败 / 20s 读超时 ⇒ IOException ⇒ 抛 | `HttpRagRetrievalGateway.java:130-141`（`:136-141` 不回显远端正文，只给 host 与原因类别） | 无 | **不回显响应正文**（可能含租户语料） |
| 3 | 非 2xx ⇒ 抛（只给状态码） | `HttpRagRetrievalGateway.java:143-147` | 无 | ⚠️ **401/403/500 被压成同一异常**（终态与临时故障不可分，见附录 I-08） |
| 4 | 非法 JSON / 缺 `hits` 数组 ⇒ 抛 | `HttpRagRetrievalGateway.java:178-186` | 无 | 契约不匹配即失败，绝不"当零命中" |
| 5 | 编排器捕获 `RagUnavailableException` | `AgentChatOrchestrator.java:524` | — | 组件级失败，**不发 error 帧、不改审计终态**（`:525` 注释） |
| 6 | 发 `RAG_UNAVAILABLE` 事件 + Skill(error) + 空 `RagHits` + `RAG_HITS{hits:0, injected:false}` | `AgentChatOrchestrator.java:526-531` | `state.events` 三条 + `state.step("RAG_UNAVAILABLE", …)`（`:529`） | 用户看到"RAG 检索不可用"，**不是**"没找到相关内容"（后者会让运维误判为语料缺失） |
| 7 | 返回 null ⇒ **不注入任何 RAG 上下文**，模型在无依据状态下作答 | `AgentChatOrchestrator.java:532`、`:558-560` | 无 | fail-soft 于上下文、fail-closed 于检索（不伪造命中） |

- **失败分支**（≥2 条）：
  1. **对照分支（合法零命中，不是故障）**：门禁未过 ⇒ HTTP **200** + `hits=[]` + `funnel` 门禁后 = 0 + debug 日志（`RetrievalOrchestrator.java:136-138`、`:148-159`）⇒ 用户侧"没找到依据"与本场景"RAG 不可用"是**两种不同事件**，前端可分别呈现。
  2. **嵌入模型不可用 ⇒ 503**（rag 侧）：`RagRetrievalController.java:94-102` ⇒ 平台侧收到非 2xx ⇒ 走第 3 跳 ⇒ 同样是 `RAG_UNAVAILABLE`；但**根因不同**（第 7 跳 vs 第 3 跳），当前**未透出根因**（只给状态码）⇒ 排障须回 rag 侧日志。
  3. **配额路径 fail-soft**：`applyRagQuota` 异常 ⇒ 返回 null ⇒ 回落 legacy 固定截断（`AgentChatOrchestrator.java:611-615`）⇒ **不中断回答**，但注入量可能超预算（见附录 I-09）。
- **可观测落点**：`RAG_UNAVAILABLE` 事件 + `RAG_HITS{hits:0, injected:false}` 事件（`AgentChatOrchestrator.java:526`/`:531`）+ `state.step("RAG_UNAVAILABLE", …)`；`AgentChatOrchestrator.java:1046-1047`（异常 → 组件名映射）。**无观测点**：无检索失败计数指标、无告警规则（§5.7.1 已登记）。
- **排查断点**：`HttpRagRetrievalGateway.java:136`（IOException 分支）、`:144`（非 2xx）、`:184`（缺 hits）、`AgentChatOrchestrator.java:524`（捕获点）、`:526`（事件发出）、`RagRetrievalController.java:94`（rag 侧嵌入异常 → 503）。

#### H-3 场景：跨租户越权检索 —— scope 谓词隔离与令牌归属绑定的双层拒绝（权限安全）

- **触发入口**：`POST /api/v1/retrieval`，携带租户 A 的 Bearer 令牌 + `X-Tenant-Id`（`RagRetrievalController.java:86-88`）。
- **前置状态**：租户 A、B 各有语料；哨兵桶存全局公共语料（`RagScope.java:46` 的 `GLOBAL_TENANT_ID = "00000000-0000-0000-0000-000000000000"`）。

```
客户端(租户A)     RagJwtAuthFilter      RagRetrievalController     RagSqlScopeTranslator        PG(rag_app/RLS)
   |                    |                       |                          |                       |
   |(1) Bearer(A) + X-Tenant-Id=B              |                          |                       |
   |------------------->|                       |                          |                       |
   |                    |(2) 归属绑定不符 => 403 |                          |                       |
   |&lt;--- 403 ---------|                       |                          |                       |
   |                    |                       |                          |                       |
   |--- 正常路径: Bearer(A) + X-Tenant-Id=A ---->|                          |                       |
   |                    |                       |(3) requireTenant: 哨兵=>400 / 非法=>400        |
   |                    |                       |(4) scope = TenantWithGlobal(A)                |
   |                    |                       |(5) render => tenant_id IN (A::uuid, 哨兵::uuid)|
   |                    |                       |------------------------->|                       |
   |                    |                       |                          |(6) 双通道 SQL 各带同一谓词 |
   |                    |                       |                          |---------------------->|
   |                    |                       |                          |(7) RLS 兜底 + 哨兵桶放行 |
   |                    |                       |                          |(8) 结果含 A 与全局，不含 B |
   |&lt;--------------------------------------------------------------- 200 hits[]|
```

- **逐跳锚点表**（8 跳，与时序图一致）：

| 跳 | 动作 | 源码锚点 | 落点（表/事件/日志） | 失败语义 |
|---|---|---|---|---|
| 1 | 请求到达（Bearer + `X-Tenant-Id`） | `RagRetrievalController.java:86-88`（头名常量 `:68`） | `LogContext`（未验签前取头，`:158-160`） | — |
| 2 | **令牌归属绑定**：租户域令牌要求 `X-Tenant-Id` == 令牌 `tenant_id` claim | `RagJwtAuthFilter.java:180-189`（`:185-187` 403） | 无 | 不一致 ⇒ **403**（已认证但无权访问该租户） |
| 3 | 控制器 `requireTenant`：缺/空 ⇒ 400；**哨兵 ⇒ 400**；非 UUID ⇒ 400 | `RagRetrievalController.java:163-184`（`:164-168`、`:172-176`、`:177-183`） | 无 | 哨兵绝不能成为会话身份（应用层第一道）；数据库侧 `rag_current_tenant()` 亦拒哨兵（既有正文口径，本轮**未连库复核函数体** ⇒ 标为证据缺口） |
| 4 | 作用域在**入口层**判定：`new RagScopeFilter.TenantWithGlobal(tenantId)` | `JdbcTenantScopedRetrieval.java:53`；`RetrievalOrchestrator.java:70-78`（编排层只搬运、不就地决定） | 无 | 编排层自行合成 scope ⇒ "入口判 A、SQL 滤 B"的静默越权 |
| 5 | 谓词渲染：`tenant_id IN (?::uuid, ?::uuid)`（本租户 + 哨兵） | `RagSqlScopeTranslator.java:111-116`（`:114-116`）；列限定符白名单校验 `:102-107`；`None` ⇒ `1=0` 恒假 `:121` | 绑定参数 2 个 | 谓词形态单点产出 ⇒ 杜绝"同一语义两处拼写"（`:17-30` 对照表给出与 RLS 读策略的逐字等价式） |
| 6 | 双通道 SQL **同源**应用该谓词 | `JdbcRetrievalRepository.java:489`（稠密）、`:535`（词法）、`:310`/`:333`/`:372`/`:429`/`:448`（其余 5 处 SELECT） | 无 | 任一通道漏渲染 ⇒ 该通道**跨租户泄露**（既有正文 AP-9） |
| 7 | RLS 兜底：`rag_chunk` / `rag_chunk_embedding` / `rag_access_audit` 等表 `FORCE ROW LEVEL SECURITY` | `01_rag_ddl.sql:293`（`rag_access_audit`）、`:356`（`rag_chunk`）、`:394`（`rag_chunk_embedding`）；启动自检 `RagStartupSelfCheck.java:167-176`（RLS 未开 ⇒ 失败）、`:197-206`（未绑租户可读 ⇒ 失败） | PG 策略求值 | ⛔ 铁律 1：验隔离必须用 `rag_app`（NOBYPASSRLS）；用 `rag_ops` 跑会得到假绿（`RagStartupSelfCheck.java:228-263` 断言两角色不同） |
| 8 | 结果只含 A 与全局；B 的 chunk 不可见 | IT 门禁：`RetrievalRepositoryIT.java:242-250`（`crossTenantIsolationEnforced`）、`:297-302`（`loadByIdsCrossTenantReturnsNothing`）、`:535`（`tenantCannotInsertGlobalChunk`）；`RagScope.java:46`/`:59-60`（哨兵判定） | `rag_access_audit`（同事务） | 全局语料对每个租户可见是**设计内**（哨兵桶，`RetrievalRepositoryIT.java:417` `globalChainIsRecalledByEveryTenant`），不是泄露 |

- **失败分支**（≥2 条）：
  1. **令牌与头不一致**（第 2 跳）⇒ **403**，且**不发生任何数据访问**（鉴权在业务之前）⇒ 符合铁律 15（权限错误须显式终态）。
  2. **哨兵做会话身份**（第 3 跳）⇒ **400**（`RagRetrievalController.java:172-176`）；数据库侧另有 `rag_current_tenant()` 拒绝（**本轮未连库复核函数体**，标证据缺口）⇒ **纵深防御两层**。
  3. **谓词渲染漏一处**（第 6 跳）⇒ 漏掉的那条通道直接跨租户泄露且**不报错** ⇒ 这正是 `RetrievalRepositoryIT` 与 `M4R1RetrievalRetirementReverseGuardIT` 这类"反向守卫"存在的理由。
  4. **用错角色验隔离**（第 7 跳）⇒ `rag_ops` 带 BYPASSRLS ⇒ 结果**全放行** ⇒ 假绿（铁律 1）。
- **可观测落点**：`rag_access_audit`（`RETRIEVE` 行，`principal_id` 恒 NULL ⇒ **取证字段不足**，见附录 G 与 5.7.1）；`LogContext.setIdentity`（`RagJwtAuthFilter.java:210`）；影子染色计数 `taintViolations`（`RagShadowReporter.java:64-70`）——但**无出口**（`InMemoryRagShadowMetrics.java:14`，`RagShadowCollector.java:42-51` 无主代码消费方）。
- **排查断点**：`RagJwtAuthFilter.java:184`（归属比较）、`RagRetrievalController.java:172`（哨兵判定）、`JdbcTenantScopedRetrieval.java:53`（scope 判定）、`RagSqlScopeTranslator.java:114`（谓词渲染）、`JdbcRetrievalRepository.java:489`（稠密侧渲染）、`:535`（词法侧渲染）、`01_rag_ddl.sql:294`（审计表 FORCE RLS）。

#### H-4 场景：多个 Worker 并发领取同一批摄取任务（并发竞态）

- **触发入口**：`IngestJobConsumer.pollOnce()`（`IngestJobConsumer.java:59-60`，`@Scheduled(fixedDelay=2000ms, initialDelay=5000ms)`）。
- **前置状态**：`rag_ingest_job` 中有 N 条 `PENDING` 任务；两个及以上 rag-worker 实例并行轮询；`rag.indexer.tenants` 已列出待处理租户（否则见附录 G-10，**整个循环不启动**）。

```
Worker A          Worker B        JdbcIngestJobRepository            PG(rag_ingest_job)
   |                 |                     |                             |
   |(1) pollOnce     |                     |                             |
   |---------------->|  (同一调度语义，多实例并行)                         |
   |                 |                     |                             |
   |                 |                     |(2) 事务内 set_config(租户)     |
   |                 |                     |---------------------------->|
   |                 |                     |(3) SELECT ... LIMIT 1          |
   |                 |                     |    FOR UPDATE SKIP LOCKED     |
   |                 |                     |---------------------------->|
   |                 |                     |   ┌─ Worker A 领到 job#1（行锁）|
   |                 |                     |   └─ Worker B 跳过被锁行，领 job#2
   |                 |                     |(4) UPDATE status='PARSING'     |
   |                 |                     |---------------------------->|
   |(5) 编排器 run(job)：解析→切分→嵌入→索引→原子落库                   |
   |                 |                     |                             |
   |(6) markSucceeded（写 chunk_policy/chunk_count 等留痕）                |
   |                 |                     |---------------------------->|
   |                 |                     |                             |
   |(7) 编排器崩溃 => job 卡在 PARSING => 只能由 Sweeper 接管（见 H-5）  |
```

- **逐跳锚点表**（7 跳，与时序图一致）：

| 跳 | 动作 | 源码锚点 | 落点（表/事件/日志） | 失败语义 |
|---|---|---|---|---|
| 1 | 定时轮询一条任务（`fixedDelay` 而非 `fixedRate`，避免长任务堆积并发） | `IngestJobConsumer.java:59-60`（`:56-57` 注释） | 无 | — |
| 2 | 逐租户领取（租户清单为空 ⇒ 直接返回 null） | `IngestJobConsumer.java:102-123`（`:103-106` 空清单短路、`:112-117` 跳过哨兵并 WARN） | 无 | 清单为空 ⇒ **静默空转**（附录 G-10） |
| 3 | 事务内 `set_config` + `SELECT … LIMIT 1 FOR UPDATE SKIP LOCKED` | `JdbcIngestJobRepository.java:276-277`、`:282-290`（`:278-281` 注释说明 SKIP LOCKED 是核心正确性来源） | `rag_ingest_job` 行锁 | 用 `FOR UPDATE`（无 SKIP LOCKED）⇒ worker 之间**互相阻塞**（`:27-29` 注释）；未绑租户 ⇒ RLS 过滤成 0 行、任务静默积压（`:272-273` 注释） |
| 4 | 领取后置 `status='PARSING'` | `JdbcIngestJobRepository.java:294-299` | `rag_ingest_job.status` | `set_config` 必须用 `queryForObject` 而非 `update`（`:274-275` 注释：否则抛 DataIntegrityViolation/500） |
| 5 | 编排器执行：解析 → 切分 → 角色判定 → 嵌入 → 分词索引 → 原子落库 | `IngestJobOrchestrator.run(job)`（`IngestJobConsumer.java:75`） | `rag_chunk` / `rag_chunk_embedding` / `rag_edition` | 业务失败由编排器转成 job `FAILED`（`:76` 注释） |
| 6 | 回写成功：`markSucceeded`（`chunk_count` / `parser_version` / `embed_model` / `chunk_policy` / `chunk_policy_config`） | `JdbcIngestJobRepository.java:328-379`（`:361-376` 记载 2026-11-06 灰度实测：**参数顺序错 ⇒ 运行期 "未设定参数值 N" ⇒ 972 片段已落库却 job 卡 FAILED**） | `rag_ingest_job` | 占位符与参数必须逐项对齐（`job_id` 放最后） |
| 7 | 编排器自身崩溃 ⇒ 尽力补 `FAILED`（`CONSUMER_CRASH`） | `IngestJobConsumer.java:76-87`（`:81-83` 回写原因、`:84-86` 回写也失败则只记日志） | `rag_ingest_job.error_detail` | 线程**绝不**因一次异常终止（否则 worker 静默停工、任务永久积压） |

- **失败分支**（≥2 条）：
  1. **两个 worker 同时领同一 job**（第 3 跳）⇒ `FOR UPDATE SKIP LOCKED` 使 B **跳过被锁行**、领下一条 ⇒ **绝不重复领**（`:278-281`）；去掉 SKIP LOCKED 则是"阻塞"而非"重复"（`:27-29` 注释）——**反向验证**：撤掉 SKIP LOCKED 必须让并发用例变红。
  2. **未绑租户会话**（第 3 跳）⇒ RLS 把候选过滤成 0 行 ⇒ "看起来在跑、实际永远领不到"（`:272-273` 注释记载 2026-11-03 实测 `visible_jobs=0`）。
  3. **`markSucceeded` 参数错位**（第 6 跳）⇒ 运行期 "未设定参数值 N" ⇒ 片段已落库但 job 卡 FAILED ⇒ **半本入库**（摄取链最危险的失败形态）。
  4. **全局哨兵出现在租户清单**（第 2 跳）⇒ 跳过并 WARN（`:112-117`），因为哨兵不是租户（`rag_current_tenant()` 会抛错）。
- **可观测落点**：`rag_ingest_job.status` / `.stage` / `.attempt` / `.error_detail` / `.chunk_count`；消费循环日志 `[RAG] 领取摄取任务 job=… source=… attempt=…`（`IngestJobConsumer.java:72-73`）与失败日志（`:79`、`:85`）。**无观测点**：无"领取延迟/积压时长"指标、无告警规则。
- **排查断点**：`IngestJobConsumer.java:103`（租户清单）、`JdbcIngestJobRepository.java:276`（绑 GUC）、`:288`（SKIP LOCKED）、`:296`（置 PARSING）、`JdbcIngestJobRepository.java:377`（`job_id` 参数最后追加）、`IngestJobConsumer.java:81`（崩溃兜底回写）。

#### H-5 场景：worker 被 kill -9 后僵尸摄取任务的接管（崩溃恢复）

- **触发入口**：`IngestJobSweeper.sweep()`（`IngestJobSweeper.java:64-66`，`@Scheduled(fixedDelay=300000ms, initialDelay=60000ms)`）。
- **前置状态**：某 job 停在 `PARSING`（worker 被 kill -9 / 连接池断链）；`rag.indexer.tenants` 已列出该租户（**否则租户侧永不被接管**，见附录 G-10）。

```
Sweeper            properties(RagIndexerProperties)     PG(rag_ingest_job)        运维
   |                     |                                     |                     |
   |(1) sweep()          |                                     |                     |
   |------------------->|                                     |                     |
   |(2) getTenants() 空? |                                     |                     |
   |   => 直接 return（租户侧一个都不扫）                         |                     |
   |(3) 逐租户 failTimedOutRunningJobs(tenant, 30min)          |                     |
   |-------------------------------------------------------->|                     |
   |                     |                                     |(4) 超时 job => FAILED |
   |                     |                                     |    + SWEEPER_TIMEOUT  |
   |(5) 全局桶单独扫（不依赖租户清单）                            |                     |
   |   运维通道未接 => RagOpsUnavailableException => debug 降级  |                     |
   |(6) 不自动重试（避免崩溃循环）=> 需运维在失败处置区显式重试                   |
   |------------------------------------------------------------------------->|
```

- **逐跳锚点表**（6 跳，与时序图一致）：

| 跳 | 动作 | 源码锚点 | 落点（表/事件/日志） | 失败语义 |
|---|---|---|---|---|
| 1 | 周期触发（间隔远大于消费轮询：5 分钟 vs 2 秒） | `IngestJobSweeper.java:64-66`（`:60-62` 注释） | 无 | 扫得太频只是无谓的全表条件扫描 |
| 2 | 取租户清单；**空 ⇒ 直接 return** | `IngestJobSweeper.java:70-73` | 无 | ⚠️ **本场景最大的静默失效点**：清单为空 ⇒ 租户侧僵尸任务**永远不被接管**（清单同源于 G-10） |
| 3 | 逐租户接管超时任务（跳过空串与哨兵） | `IngestJobSweeper.java:68-83`（`:76-79` 跳过哨兵、`:80` `failTimedOutRunningJobs(t, timeoutMinutes)`） | `rag_ingest_job` | 逐租户扫是**必须**的：无租户会话的全表 UPDATE 会被 RLS 静默变成 0 行（`:59-61` 注释） |
| 4 | 超时 job 置 `FAILED` + 写 `SWEEPER_TIMEOUT` 原因 | `IngestJobSweeper.java:21-23`（接管语义注释：置 FAILED 且**不自动重试**）；端口方法 `failTimedOutRunningJobs` | `rag_ingest_job.status` / `.error_detail` | 自动重试可能形成崩溃循环 ⇒ 刻意不做 |
| 5 | 全局桶**单独**扫一次（不依赖租户清单） | `IngestJobSweeper.java:108-121`（`:100-107` 说明为何必须单独扫；`:115-117` `RagOpsUnavailableException` ⇒ debug 降级不刷 warn） | 全局 `rag_ingest_job` | 运维通道未接入是**正常部署选择**（fail-closed），降级为 debug（`:112-116`） |
| 6 | 告警可见性：接管数 > 0 记 WARN；**重试需运维显式发起** | `IngestJobSweeper.java:85-86`（租户侧 WARN）、`:112-113`（全局侧 WARN）；重试为"新建行"（`:21-23`） | 日志（WARN） | ⚠️ 只有日志、**无指标无告警规则** ⇒ "接管频发是 worker 不稳定的前兆"这一预警依赖人看日志 |

- **失败分支**（≥2 条）：
  1. **租户清单为空**（第 2 跳）⇒ 僵尸 job 永久停在中间态 ⇒ 工作台一直转圈、重试端点因非终态而 409 ⇒ 死锁（既有正文与 `IngestJobSweeper.java:94-103` 记载的同类现象）。
  2. **全局 job 卡 PARSING**（第 5 跳）⇒ 运维通道未接入时**只 debug 不 warn** ⇒ 静默累积（`:112-116`）。
  3. **Sweeper 自身异常**（第 3 跳）⇒ 捕获后记 WARN、下一轮重试，**不终止线程**（`:90-93` / `:120`）。
  4. **阈值过小**⇒ 正常长任务被误判为僵尸（`:36-42` 注释自陈"误杀比漏杀更难排查"）。
- **可观测落点**：`rag_ingest_job.status` / `.error_detail`（`SWEEPER_TIMEOUT`）；日志 `[RAG] Sweeper 接管 N 个超时（>30分钟）…`（`IngestJobSweeper.java:85-86` / `:112-113`，WARN 级）。**无观测点**：无 `sweeper_taken_total` 指标、无告警规则 ⇒ 接管频发不可被告警发现。
- **排查断点**：`IngestJobSweeper.java:70`（清单空短路）、`:80`（逐租户接管）、`:110`（全局扫）、`:43-44`（阈值）、`IngestJobConsumer.java:103`（同一份清单的另一消费点——两处清单必须同源）。

---

### 附录 I：红队反例用例集（13 条，可直接落成测试）

> **本附录为第三轮追加**，不改写既有内容。每条用例可转为 IT / 单测 / 静态守卫。
> **「当前实际」列如实写现状**：能核实就写核实结果（含本轮锚点），不能核实写 `❓未核实`；**本轮未运行任何测试**，所有行为断言一律标注「未实测（需按本报告门禁口径在实施阶段验证）」。
> **归因三分类**：`设计取舍`（有意为之，非缺陷）/ `能力缺失`（应补）/ `证据缺口`（本轮未能核实，需补证）。
> **铁律对齐**：验隔离必须用 **`rag_app`（NOBYPASSRLS）**；用 `rag_ops`（BYPASSRLS）跑会得到**假绿**（铁律 1；`RagStartupSelfCheck.java:228-263` 断言两角色不同）。
> ⚠️ **编号说明**：本附录用例编号 `I-01 … I-13` 与**第七章改进项编号 `R-0 … R-7`** 是两套独立编号——后者是改进项（方案），前者是红队用例（断言）。

| # | 用例名 | 攻击/异常场景 | 前置条件 | 步骤 | 期望结果（可断言） | 当前实际 | 归因 | 可落成的测试形态 |
|---|---|---|---|---|---|---|---|---|
| I-01 | 跨租户直查：租户 T 检索不得看到租户 U 的片段 | 越权/跨租户 | `rag_app` 连接；T、U 各有 `rag_chunk`（含向量） | 1) 以 T 身份执行一次 `search`；2) 统计结果 chunkId 归属 | 结果中**不含任何 U 的 chunkId**；U 的片段**一行都不出现**（不是"被过滤但计数可见"） | ✅ 机制成立：`RagSqlScopeTranslator.java:114-116`（谓词渲染）+ `JdbcRetrievalRepository.java:489`/`:535`（双通道同源）+ IT `RetrievalRepositoryIT.java:242-250`。**未实测** | — | DB IT（真实 PG，`rag_app`）+ 静态守卫（断言 6 处 SELECT 均经 translator） |
| I-02 | 令牌租户与 `X-Tenant-Id` 不一致 ⇒ 403 | 越权/跨租户 | 租户域令牌 `tenant_id=A` | 1) 携带该令牌 + `X-Tenant-Id=B` 请求 `/api/v1/retrieval` | 返回 **403**；且**不发生任何数据访问**（断言 `rag_access_audit` **无新增行**、无 SQL 执行） | ✅ 机制成立：`RagJwtAuthFilter.java:180-189`（鉴权在业务之前）。⚠️ 但 platform 侧会把它压成 `RAG_UNAVAILABLE`（见 I-08）。**未实测** | — | API IT（rag 侧）+ 角色×任务（平台主体 / 租户主体两态） |
| I-03 | 哨兵租户不得作为会话身份 | 越权/跨租户 | 哨兵 UUID `00000000-0000-0000-0000-000000000000` | 1) `X-Tenant-Id` = 哨兵 ⇒ 调检索端点 | 返回 **400**（"租户标识不得使用平台全局作用域的保留值"）；**不得**进入 SQL | ✅ 机制成立：`RagRetrievalController.java:172-176`；哨兵常量 `RagScope.java:46`。数据库侧 `rag_current_tenant()` 的拒绝逻辑**本轮未连库复核** ⇒ 该层标证据缺口。**未实测** | — | API IT + DB IT（断言 DB 层也拒绝） |
| I-04 | 两个 worker 并发领取同一批摄取任务 | 并发竞态 | `rag_ingest_job` 有 N 条 PENDING；两个 worker 实例 | 1) 并发轮询；2) 统计每个 job 被置 PARSING 的次数 | **每个 job 恰好被一个 worker 领走**（`PARSING` 次数 == 1，`attempt` 不重复递增） | ✅ 机制成立：`JdbcIngestJobRepository.java:282-290`（`FOR UPDATE SKIP LOCKED`）。**反向验证**：撤掉 SKIP LOCKED 必须变红（退化为阻塞）。**未实测** | — | DB IT（并发 + `rag_app`） |
| I-05 | 重复检索 ⇒ 审计行数与调用次数一致 | 重复投递/幂等 | 租户 T 语料就绪 | 1) 同一 query 连续检索 2 次；2) 统计 `rag_access_audit` 中 `action='RETRIEVE'` 的行数 | 恰好 **2 行**（一次调用一行），既**不漏写**也不重复写；`principal_id` 为 NULL（系统动作无人类主体，既有正文已明确该口径） | ✅ 机制成立：`JdbcTenantScopedRetrieval.java:62-66`（每次检索写一行）。⚠️ **无幂等键**：同 query 重复调用会重复检索并重复计费/重复留痕（当前无去重口径）。**未实测** | 设计取舍（系统动作无幂等键）／能力缺失（无去重口径） | DB IT（断言行数，遵守铁律 6「有一行 ≠ 数字算对了」） |
| I-06 | 并发 `loadByIds` 取回同一批依据不得越界 | 并发竞态 | 租户 T 自己的 chunkId 列表 + 租户 U 的 chunkId | 1) 10 个线程并发 `loadByIds`；2) 断言返回集合 | 返回集合恒 ⊆ T 的 chunk 集合；**不含 U 的任何 chunkId**；结果**无重复行** | ✅ 机制成立：`JdbcRetrievalRepository.java:430-441`（`chunk_id = ANY(?::uuid[])` + 同一 scope 谓词）+ IT `RetrievalRepositoryIT.java:297-302`。**未实测** | — | DB IT（并发）+ API IT |
| I-07 | 门禁恒空必须是"零命中"，**不得**报成"RAG 不可用" | 终态误报 | 语料存在但相关性分 < 0.38（如跨领域词） | 1) 发一次必然低于阈值的检索；2) 观察平台侧事件 | 平台侧出现 `RAG_HITS{hits:0, injected:false}`；**不得**出现 `RAG_UNAVAILABLE`；rag 侧返回 **HTTP 200** + `funnel` 门禁后 = 0 | ✅ 机制成立：`RetrievalOrchestrator.java:136-138`（门禁）+ `:148-159`（漏斗与 debug）+ `AgentChatOrchestrator.java:524-533`（组件失败路径与门禁路径**分离**）。**未实测** | — | API IT（角色×任务：低分词 × 正常词 两态，事件类型必须不同） |
| I-08 | 403/401 不得被伪装成临时故障 | 终态误报 | 平台侧 `HttpRagRetrievalGateway` 指向真实 rag | 1) 用**无权限**令牌触发检索；2) 观察平台侧事件与用户可见文案 | 必须是**显式终态**（403/401 + "无权访问"），**不得**统一呈现为"RAG 检索不可用 / 稍后再试" | ❌ **当前实际：平台侧把全部非 2xx 压成同一异常**——`HttpRagRetrievalGateway.java:143-147` 只带状态码抛 `RagUnavailableException` ⇒ 401/403/500 在编排器里**不可区分**（`AgentChatOrchestrator.java:524-533` 一律记 `RAG_UNAVAILABLE`）⇒ **违反铁律 15 的精神**（权限/终态错误不得伪装成临时故障）。**未实测** | 能力缺失 | API IT（断言事件类型随 HTTP 状态码而变；须先做"状态码透传"的最小改动） |
| I-09 | 资源预算越界：RAG 命中区注入量必须受限 | 资源/预算越界 | 单次检索返回超长片段 | 1) 构造 topK=50 且每条 text 很长的命中集；2) 统计实际注入模型上下文的 RAG 载荷 token | 注入载荷 token **不超过** `agent.context.rag-quota.token-budget`（启用态）或 legacy 固定口径 `MAX_INJECT_HITS=8` + `MAX_HIT_TEXT=1200`（关闭态）；**不得**无界增长 | ⚠️ **当前实际：默认路径是 legacy 固定截断**——`agent.context.enabled` 默认 false（`platform/.../application.yml:232`）⇒ `RagHitQuotaAdapter.java:81-82` 首行短路 ⇒ 走 `AgentChatOrchestrator.java:572-588` 的 `serializeHits`（`:577-580` 按 8 条 / 1200 字截断）。**上限存在但按"条数×字数"而非 token 预算** ⇒ 口径与配额算法不一致。**未实测** | 设计取舍（影子期）/ 能力缺失（口径未统一） | 单测（`serializeHits` 截断断言）+ API IT（注入量断言；须先接线 G-14） |
| I-10 | 崩溃恢复：僵尸摄取任务必须被接管 | 崩溃恢复 | 某 job 卡在 `PARSING`；`rag.indexer.tenants` 含该租户 | 1) 等 `rag.ingest.sweeper.interval-ms`；2) 查 job 状态 | job 变为 `FAILED` 且 `error_detail` 含 `SWEEPER_TIMEOUT`；**不得**永久停在 `PARSING` | ⚠️ **当前实际：取决于租户清单**——`IngestJobSweeper.java:70-73` 清单为空 ⇒ 直接 return ⇒ 租户侧僵尸任务**永不被接管**；而 `rag-worker/application.yml:81` 的默认值就是 `tenants: []`，且本轮 grep 确认 `rag.env` / `rag.env.example` **均未提供该键** ⇒ **默认部署下此链路静默停摆**。**未实测** | 能力缺失（部署基线未固化 + 缺 fail-fast） | DB IT（超时接管）+ 配置守卫（`tenants` 为空 ⇒ 启动期告警/拒绝） |
| I-11 | 崩溃恢复：审计写失败 ⇒ 不得出现"检索成功但无痕迹" | 崩溃恢复 | 检索链路正常 | 1) 让 `rag_access_audit` 插入失败（如权限不足/约束冲突）；2) 观察响应与表 | 整次检索**回滚**：不返回命中；`rag_access_audit` **无新增行**；调用方收到失败而非成功 | ✅ 机制成立：`JdbcTenantScopedRetrieval.java:46-68`（同一 `transactionTemplate` 内"检索 + 审计"，`:59-61` 注释明写"不允许检索成功但无痕迹"）。**未实测** | — | DB IT（制造审计写失败）+ API IT（断言非 200/无命中） |
| I-12 | 排序键与门禁键分离的不变式 | 终态误报 / 回归护栏 | 语料含强相关与弱相关片段 | 1) 执行检索；2) 对每条命中断言 | `score()`（相关性分，0~1）与 `fusionScore()`（RRF 融合分，≤2/61≈0.0328）**量纲不同**；门禁只读 `score`；融合分恒 < 0.05 | ✅ 机制成立：`JdbcRetrievalRepository.java:280-296`（排序键/门禁键分离）+ `:590`（RRF 公式）+ 既有 IT `RetrievalRepositoryIT.java:266-282`。⛔ **红线**：不得改 k=60、不得合并两个键。**未实测** | — | DB IT（复用既有 IT 断言）+ 静态守卫（断言 `minScore` 比较发生在 `score()` 上） |
| I-13 | 影子开关关闭 ⇒ 零行为变更；开启 ⇒ 读数必须可被消费 | 回归护栏 / 观测 | `rag.shadow.enabled` 开/关两态 | 1) 关闭态跑一次检索，断言结果与审计逐字节一致；2) 开启态断言"样本数 > 0"且"mismatch 数可被外部读取" | 关闭态：检索结果、审计行**完全一致**（零行为变更）；开启态：`shadowSamples > 0` **且** 该计数有**消费方**（健康端点或写库） | ⚠️ **当前实际**：关闭态零行为变更成立（`RagShadowConfiguration.java:22` ⇒ 关闭时 Bean 不存在、`RagShadow.java:35-43` 全 no-op）；但**开启态读数无消费方**——`InMemoryRagShadowMetrics.java:14` 为内存实现，`RagShadowCollector.java:42-51` 的 `snapshot()` **无主代码消费方**（本轮 grep 仅测试引用）⇒ "mismatch = 0"与"根本没采到样本"**不可区分**。**未实测** | 能力缺失 | DB IT（影子 IT）+ 静态守卫（断言 `snapshot()` 有生产消费方或指标导出） |

**反向验证口径**：每条用例必须能回答「撤掉修复点后，这条用例会不会变红？」——

- **I-01 / I-03 / I-04 / I-06 / I-11 / I-12**：属"防回归护栏"，撤掉对应机制（谓词渲染、哨兵 400、SKIP LOCKED、同源谓词、同事务审计、键分离）**必然变红**（其中 I-01/I-06/I-12 已有既有 IT 覆盖，I-03/I-04/I-11 需按三级门禁补齐）。
- **I-02**：撤掉归属绑定（`RagJwtAuthFilter.java:180-189`）⇒ 变红；撤掉后行为是"越权检索成功"，正是本用例的价值。
- **I-05**：撤掉审计写入 ⇒ 行数变 0 ⇒ 变红；但"重复调用是否应去重"当前**无修复点**，该子断言属**已知缺口登记**。
- **I-07**：撤掉门禁（`RetrievalOrchestrator.java:136-138`）⇒ 低分词也会返回命中 ⇒ 变红；这是"门禁不该被悄悄放宽"的守卫。
- **I-08 / I-09 / I-10 / I-13**：当前**没有修复点可撤**（能力缺失/影子期），落成时必须**先补修复点**再验证"撤掉即红"；**在这些用例变绿之前，它们是"已知缺口登记"，不是"通过的测试"**。

**建议落地优先级**：**P0** 用例 **5** 条（I-08 终态误报 / I-10 崩溃恢复停摆 / I-13 影子读数无出口 / I-02 越权归属 / I-01 跨租户 —— 全部是"默认部署下会真实出事"类） / **P1** 用例 **5** 条（I-05 重复留痕 / I-07 门禁语义 / I-09 注入预算 / I-12 键分离回归 / I-03 哨兵身份） / **P2** 用例 **3** 条（I-04 并发领取 / I-06 并发取回 / I-11 审计回滚）。

**反向验证补充**：P0 的 I-10 与附录 G-10、I-13 与附录 G-07、I-08 与附录 G-13 分别指向**同一批配置项**——这说明本轮附录 G 的配置矩阵**不是文档产物，而是这些红队用例的前置清单**；按上文优先级落地时，配置侧与用例侧应**同批改动**，避免"配好了但没用例守"或"有用例但配置没接线"。

---

## 诚实边界声明

本报告的每一条论断按证据强度分三档标注，不做混同：

### 一、基于本项目源码实证（可直接复核到 `文件:行号`）

- 第四章「源码级解剖」的全部内容、附录 A 的全部条目：类、方法、DDL、配置键、测试用例名，均已逐行读过源码或 SQL 文件本身。
- 关键锚点包括：`HttpRagRetrievalGateway.java:103-221`（超时/白名单/畸形丢弃）、`RetrievalOrchestrator.java:80-170`（七步编排 + 漏斗）、`JdbcRetrievalRepository.java:172-180`（准入谓词）、`:269-304`（RRF 融合）、`:472-557`（双通道 SQL + 代际过滤）、`RagSqlScopeTranslator.java:95-123`（谓词渲染）、`RagScope.java:10-46`（哨兵纪律）、`RagJwtAuthFilter.java:144-212`（鉴权语义）、`RagShadow*.java`（影子语义）、`01_rag_ddl.sql`（表结构与 37 条策略）、`application.yml`（配置默认值）。
- 结构性结论"**agent-infra 的 4 个检索引擎类运行期不可达**"是**实证结论**，依据有三：(a) 全仓 `new RetrievalService` 仅出现在 `RetrievalServiceTest`/`RetrievalEngineIT`；(b) `<artifactId>agent-infra</artifactId>` 在所有 `platform/*/pom.xml` 中只出现于它自己的 pom 与父 pom 的 `dependencyManagement`，无任何模块声明依赖；(c) `PlatformApplication` 的 `scanBasePackages = "cn.studykid.multiagent"` 虽会扫到 `cn.studykid.multiagent.infra.retrieval.engine`，但该包所在的 jar 不在 platform-bootstrap 的依赖闭包内，故不参与运行时扫描。**但本报告未实际启动 platform 验证 `/api/v3/retrieval` 返回 404**——该验证已列为改进点 R-0 的验收门禁第 2 项。
- 结论"**全仓无 rerank / cross-encoder 实现**"是**实证结论**：对 `rag/` 与 `platform/` 的 `*.java`/`*.yml` 做 `rerank|cross-encoder|crossencoder` 检索，零命中。
- 结论"**检索质量指标全未实现**"依据 `rag/docs/RAG-D08_评测体系与质量门禁.md:360-369` 的状态列全为"⬜ 未实现"，且源码中无对应实现（评测模块不存在）。
- 结论"**M4 四批 SQL 均为 DRAFT、`rlsDependent` 仍为 30**"依据 `deploy/sql/rag/14`~`17` 的 `⛔DRAFT / 禁止应用` 标注与 `RlsDependencyProbe.EXPECTED` 的常量值。

### 二、基于官方文档 / 论文（可按附录 B 的 URL 复核）

- 竞品的能力描述、参数名、默认值、限制，全部取自附录 B 列出的**官方文档页**或**论文原文**。
- 需要特别标注的三处**引用精度**：
  1. **Modular RAG 的 arXiv 编号**：任务书给出 `2312.10997`，核实结果为——`2312.10997` 是 Gao 等人的 **RAG 综述**（首次提出 Naive/Advanced/Modular 分类），"Modular RAG" 作为独立框架论文是 **`2407.21059`**。本报告两者分别引用，未沿用任务书编号。
  2. **OpenAI 的混合检索**：OpenAI 官方 Retrieval 指南确实写有 `ranking_options.hybrid_search` 与 `embedding_weight`(`rrf_embedding_weight`)/`text_weight`(`rrf_text_weight`)，但**本次核实未能抓取到该指南页的完整渲染正文**（抓取被 Cloudflare 拦截，页面返回 "You are unable to access openai.com"）。所引文字来自**官方页面的搜索结果摘要**，措辞与参数名可信度较高，但**未能逐字核对官方原文**。同理，`ranker` 的三个取值 `none`/`auto`/`default-2024-11-15` 与 `max_num_results` 的 1–50 区间来自官方 API Reference 的搜索摘要，**未经逐字页面核对**。
  3. **OpenAI 的默认嵌入模型**：官方文档确认"文件加入向量库时自动切分、嵌入、索引"，但**本报告未能核实到默认嵌入模型名的逐字官方表述**，因此正文**未写死**具体模型名，只写"自动嵌入"。这与部分二手资料的"默认 text-embedding-3-large"说法不冲突，但本报告不采信未经核实的表述。
  4. **AutoGen 归档状态**：官方仓库 README 的维护模式横幅与继任者引导，来自搜索结果摘要；"2026-10 标记 archived"属二手来源（aiwiki/博客），本报告在正文用"至 2026-10 已被标记 archived"并**明确标注为二手来源**，官方 README 本身只明确"maintenance mode"。

### 三、工程判断 / 推断（不是事实，是本报告的推理，读者可独立质疑）

以下判断基于上述两类证据的**推理**，不是任何文档或源码直接写明的：

1. **"词法通道目前只贡献排序、不贡献召回"的推论**：依据是 `RAG-D05.md:197-199` 自述"纯词法命中（实测 0.068 量级）永远过不了 0.38" + `JdbcRetrievalRepository.java:592-594` 的"相关性分取各通道最高值"合并逻辑。**未实测**该结论在当前代码下的确切表现（需要真实语料 + 真实嵌入模型）。
2. **"R-2 阶段一的通道内归一可使词法命中独立过门禁"的可行性判断**：这是本报告提出的**方案设计**，其效果依赖归一后的分布形态，**必须由 R-3 的评测集验证**，不可视为已知结论。
3. **"半vec(4096) 下稠密通道退化为全表精确扫描"**：依据是 `01_rag_ddl.sql:960-966` 的明确注释（"本索引在 halfvec(4096) 下刻意不存在"）+ pgvector 官方 README 的维度上限。**未在活库实测执行计划**。
4. **"配置默认值与 DDL 终态不一致会在稠密检索时报错"的推断**：`VectorColumn` 的校验只覆盖配置侧（配置 vs 端口），故配置不一致**不会在装配期被拦**；运行期 PG 报维度/类型错是必然的。**未实测**（需要构造"DDL 已迁 + 配置未改"的库）。
5. **"过滤在重排之前的取舍代价是漏召"**：这是基于 `RAG-D05.md:81-87` 自述的代价陈述 + 本报告的推理（未实测过滤后候选剩余量的分布）。
6. **"改完 agent-infra 后 `platform/pom.xml` 模块数从 14 降到 13"**：这是对 `<modules>` 清单的直接计数推断；**未执行过删除**，也未核实是否有构建脚本硬编码模块数。
7. **各竞品的"局限/代价"**：部分是从官方文档的约束条款（如 ADK 的单 corpus 限制、Agno 的 "Search algorithms differ by vector database"）推导的**设计后果**，非官方明示的"缺点"——官方文档通常不写自己的缺点。已尽量标注来源为约束条款。

### 四、明确标注为"未能核实"的部分

- **本报告未能核实**任何竞品在**生产规模**下的检索质量实测数据（Recall/NDCG 数字）——所有竞品对比均基于**机制与接口能力**，不含性能或质量的横向实测。跨方案的检索质量排名**不在本报告的结论范围**。
- **本报告未能核实**本项目 RAG 服务在**真实生产流量**下的行为（真实召回率、真实延迟分布、真实语料规模）。所有关于"当前召回质量如何"的问题，答案都是"**未知，且这正是改进点 R-3 要解决的核心问题**"。
- **本报告未能核实** `docs/能力维度深度报告/01`~`07` 中关于其他维度的具体数字（仅交叉引用其结论，未复核其全部证据）。
- **本报告未复核** `rag/docs/RAG-D02`~`RAG-D27` 的全部设计文档，只读了与本维度直接相关的 RAG-D03/RAG-D05/RAG-D08/RAG-D13/RAG-D26 及 `docs/权限管控体系/` 下的迁移计划。设计文档与实现可能存在其他漂移（例如 `RAG-D05.md:251-262` 的已知限制表列 L-3「中文分词未实现」与实际已实现 `ChineseTokenizer` 的漂移，本报告只指出与本维度直接相关的两条，未做全量文档审计）。

### 五、与既有梳理报告的不一致（复核发现，必须指出）

`docs/能力维度实现方式梳理报告.md` 第 8 章（`:115-127`）与源码实际有**三处不一致**，其中第 1 条影响对"RAG 内建程度"的整体判断：

| # | 梳理报告的表述 | 源码实际 | 性质 |
|---|---|---|---|
| **1** | `:123`「检索引擎 Authority Triple Gate：`agent-infra/.../retrieval/engine/RetrievalService.java:15` 实现 `RLS 租户隔离 + TTL 时效（90 天）+ 语义分阈值(0.75)` 三门任一不过即拒；混合分 = `vector*0.5 + tfidf*0.5`」 | 该类**运行期不可达**（无实例化点 + 模块未被依赖），且它自述"真实向量库/BERT 嵌入为外部依赖…以**占位打分**提供语义分"。**生产检索在 rag 模块**：`JdbcRetrievalRepository` 用 pgvector `<=>` 真余弦 + `ts_rank` 真词法 + **RRF(k=60)** 融合，门禁阈值是 **0.38**（`rag-bootstrap/application.yml:133`），在 `RetrievalOrchestrator` 内。 | **口径污染 + 归因错误**：把一份草稿的占位实现当成了生产检索引擎，并据此得出"RAG 向量打分占位未闭环"的结论 |
| **2** | `:127`「语义分阈值与 TTL 在 `RetrievalService` 内作为门控常量，可测但**向量库集成未闭环（占位打分）**」 | **向量库集成已闭环**（pgvector 余弦 + GIN 词法索引 + 代际过滤）；真正占位的是**嵌入模型**（`LocalDeterministicEmbeddingPort` 自述"不是生产级嵌入模型…绝不用于语义相关性判定的生产结论"），且它是**默认装配路径**（`EmbeddingPorts.create` 在 base-url 为空时选它，受 `RAG_LOCAL_PORTS_ENABLED` fail-closed 闸门约束）。 | **归因错误**：占位对象是"嵌入模型"而非"向量打分/向量库" |
| **3** | `:125`「影子模式（治理对照）：对**语料治理/索引构建**做抽样影子校验」 | 采集点是**检索读面**（`JdbcRetrievalRepository.search()` / `loadByIds()` 调 `RagShadow.collect("retrieval")`），比对对象是**隔离谓词**（显式谓词 vs RLS 基线），与"语料治理/索引构建"无关；且**不存在**新旧检索策略的双跑比对能力。 | **采集点与比对对象描述失准**，并掩盖了"策略维度影子缺失"这个真实缺口 |

此外，梳理报告 `:121` 描述的 `X-Tenant-Id` 归属绑定与 `:122` 的 7 字段白名单经复核**准确**；`:124` 描述的摄取管线（`IngestionPipeline` / `GlobalIngestJobOrchestrator` / `IngestJobConsumer` / `HeadingHierarchyChunker` / `ChunkPolicy` / `EditionCoordinate`）经复核**准确**。

### 六、本报告自身的局限

1. 本报告**没有运行任何测试**，全部结论来自静态阅读源码与 SQL（唯一涉及运行期行为的判断已在第三档标注）。
2. 本报告**没有连库**核对活库的真实表结构与策略数（MEMORY 记"rag 13 表/37 策略"，本报告据 `01_rag_ddl.sql` 核实为 **13 张 CREATE TABLE + 37 条 CREATE POLICY**，另有 `06`/`08`/`09` 三份 ALTER 追加策略与 1 张 `rag_index_generation` 表，故**活库实际策略数高于 37**——这与 MEMORY 记载的偏差本报告未进一步追查）。
3. 本报告的改进点（R-0 ~ R-7）**均未实施**，其"预期收益"是**基于机制推理的目标值**，不是实测值；每项都配了可执行的验收门禁，正是为了在实施时把目标值变成实测值。
4. 本报告**没有覆盖** RAG 的**生成侧**（`GroundedGenerationService` / `rag_answer_citation` 接地判定 / `rag_grounding_verdict`）的深度分析——那是维度 6（上下文）与维度 7（护栏）的交界，本报告只在"检索结果如何进上下文"处切入。这可能低估了 RAG 侧的整体复杂度。

### 第三轮补充（2026-11-16 追加层：§5.7 评分卡 + 附录 G/H/I）

> **本小节只描述本轮新增的 §5.7、附录 G、附录 H、附录 I 的证据分级**，不改写上述任何既有声明。既有正文与锚点原样保留；本轮复核出的冲突一律以「**本轮更正（与既有正文 X.X 冲突）**」在新增节内标注（见 §5.7.4 第 1/4/6 行）。

**基于源码实证的部分（本轮重新 Read/Grep 复核）**：
- **§5.7.1 全部我方证据锚点**：逐条复核 `JdbcRetrievalRepository.java`（`:107` RRF_K=60、`:110` 候选倍数、`:172-180` 六轴准入模板、`:242-250` 装配期维度拒绝、`:262-266` 权威等级展开、`:277-296` RRF 融合、`:300-302` 端口契约过滤、`:489`/`:535` 双通道同源渲染、`:472-524` 稠密通道含代际过滤、`:526-557` 词法通道、`:585-599` 融合与相关性分分离）、`RetrievalOrchestrator.java`（`:82`~`:169` 七步编排、`:136-138` 门禁、`:148-154` 五段漏斗、`:156-159` 门禁未过日志、`:182` 补全基准分、`:209-222` 版本惩罚只作用于融合分）、`JdbcTenantScopedRetrieval.java`（`:46-48` 事务内绑 GUC、`:53` 作用域判定、`:62-66` 同事务审计）、`RagSqlScopeTranslator.java`（`:17-30` 与 RLS 的逐字等价对照表、`:102-107` 列限定符白名单、`:111-122` 四种作用域渲染）、`RagRetrievalController.java`（`:86-104` 端点、`:163-184` 租户校验、`:200-207` scopeMode 默认 STRICT、`:94-102` 嵌入异常→503）、`RagJwtAuthFilter.java`（`:169-179` 401、`:180-189` 403 归属绑定、`:194-207` 域/主体属性）、`HttpRagRetrievalGateway.java`（`:62` 端点、`:65-69` 超时与 512KB 上限、`:108-110` topK 校验、`:130-141` IOException、`:143-147` 非 2xx、`:152-173` 中性占位坐标 + CROSS_EDITION、`:176-200` 白名单解析、`:202-221` 畸形命中丢弃、`:244-252` 死代码 `describeDefaultEdition`）、`AgentChatOrchestrator.java`（`:128-129` 固定截断常量、`:511-564` `doRag`、`:524-533` `RAG_UNAVAILABLE`、`:540-544` 配额与 `injected`、`:572-588` legacy 载荷、`:595-616` 配额 fail-soft、`:1046-1047` 异常映射）、`RagInfrastructureConfig.java`（`:72-73` 门禁阈值、`:91-92` 占位闸门、`:104-105`/`:288-292` 向量列、`:301-308` 权威门槛、`:310-313` 编排器装配）、`EmbeddingPorts.java`（`:41-64` 端口选择与 fail-closed）、`LocalDeterministicEmbeddingPort.java`（`:13-23` 诚实边界、`:30`/`:33` 维度与模型标识）、`LocalPortGate.java:43`、`RagShadowConfiguration.java`（`:22`/`:26`/`:29`/`:30`/`:31-32`）、`RagShadow.java:35-43`、`RagShadowCollector.java:42-51`、`RagShadowReporter.java`（`:48-50`/`:51-53`/`:56-61`/`:64-70`）、`InMemoryRagShadowMetrics.java:14`、`RagShadowMetrics.java:17-40`、`RlsDependencyProbe.java:47-52`、`RagStartupSelfCheck.java`（`:108`/`:123-142`/`:167-176`/`:197-206`/`:228-263`）、`IngestJobConsumer.java`（`:59-60`/`:76-87`/`:102-125`）、`JdbcIngestJobRepository.java`（`:276-299`/`:328-379`）、`IngestJobSweeper.java`（`:43-44`/`:61`/`:68-83`/`:108-121`）、`RetrievalPolicy.java:87-95`、`EditionScope.java:63-97`、`TrustTier.java:74-76`、`RagScope.java:46`/`:59-60`、`RetrievalRepositoryIT.java`（`:242-250`/`:266-282`/`:297-302`/`:417`/`:535`）、`VectorColumnTest.java:46-51`、`LayoutPredicates.java:141`/`:144`、`RAG-D08_评测体系与质量门禁.md:360-369`、`01_rag_ddl.sql`（`:281-291`/`:293`/`:356`/`:389`/`:394`/`:960-966`）、`deploy/sql/rag/09_v1_0_3_index_generation_alter.sql:33`、`rag-bootstrap/application.yml`（`:52-54`/`:68`/`:85-86`/`:100-109`/`:127-131`/`:133`/`:137`）、`rag-worker/application.yml`（`:28-34`/`:67-73`/`:81`）、`rag.env`（`:32`/`:37-46`/`:49-50`/`:55-58`/`:74-76`）、`platform/.../application.yml`（`:68-69`/`:232`/`:267-271`）、`RagHitQuotaConfiguration.java:37-63`、`RagHitQuotaAdapter.java:81-82`、`AgentChatConfiguration.java:71`/`:85`。
- **附录 G 全部 15 行**的消费方与生效性判定（每行均给出"谁读取"的类与行号，无凭配置文件名推测者）。
- **附录 H 全部 5 个场景**的逐跳锚点（ASCII 时序跳数与锚点表跳数逐条一致：H-1 = 11 跳、H-2 = 7 跳、H-3 = 8 跳、H-4 = 7 跳、H-5 = 6 跳）。
- **附录 I 全部 13 条**中"当前实际"列带锚点的条目，以及标注"机制成立"的条目。

**基于官方文档的部分**：
- **本轮重新抓取**：Elasticsearch 官方 RRF 页 <https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html> —— 核实原文「`rank_constant` … Defaults to `60`」「`score += 1.0 / ( k + rank( result(q), d ) )`」「A minimum of **two** child retrievers is required for ranking」「`rank_window_size` … Defaults to the `size` parameter」。本页支撑 §5.7.1 的 G-03（RRF k=60 是业界默认，非拍脑袋）与 5.7.2 的竞品对照。
- **本轮重抓但未取得正文**：pgvector 官方 README <https://github.com/pgvector/pgvector> —— 抓取到仓库页但正文被截断，**未逐字复核** ANN 维度上限表述；故 §5.7.1 中 pgvector 相关的"无租户概念 / HNSW 维度上限"仍**沿用第二章与附录 F 已核实的官方事实**，本轮未新增断言。
- **沿用第三章 / 附录 F 已核实的官方事实**（本轮未重抓）：OpenAI Vector Stores + File Search、Agno Knowledge、Weaviate / Pinecone 多租户模型、Qdrant 混合查询、Ragas 指标、Dify 知识库、LlamaIndex 换模型须重建索引、Azure AI Search 混合检索与语义重排。
- **本轮未新增任何论文、作者、年份或 URL**。

**工程判断（推断）的部分**：
- **§5.7 的分数与权重**：权重（0.30/0.25/0.20/0.15/0.10）由 §1.6 既有权序映射而来（映射方式本身是判断），全部 0–4 打分是**工程判断**，不是测量值；5.7.2 的"可比子集 ±0.00"是该判断的算术后果。
- **附录 H 中的"推测行为"**：H-4 失败分支 1（去掉 SKIP LOCKED ⇒ 退化为阻塞）与 H-5 的"清单为空 ⇒ 永不被接管"依据**源码路径推导**与**代码注释记载的历史实测**（`JdbcIngestJobRepository.java:27-29`/`:272-275`、`IngestJobSweeper.java:59-61`、`:361-376` 的 2026-11-06 灰度实测），**本轮未复跑**。
- **附录 I 的"期望结果"与 P0/P1/P2 分级**是建议，不是已通过的测试。

**本轮未做的事（诚实声明）**：
1. **未运行任何测试**（未跑单测 / DB IT / 压测 / 故障注入）；所有行为断言一律写「未实测（需按本报告门禁口径在实施阶段验证）」。
2. **未连库**：线上 `study_rag` 的真实列形态、真实策略数、真实语料规模与影子读数**本轮均未核实**（§5.7.4 第 3 行、附录 G-07）。
3. **未修改任何源码**：本轮只写文档，附录 G/I 提出的修复点（`rag.indexer.tenants` 部署基线、影子快照出口、平台侧 HTTP 状态码透传）**均未实施**。
4. **未新增任何配置项或默认值**：附录 G 里的所有"默认值"均取自仓库现有 yml/env，未提出新的配置面建议（⛔ 特别地：RAG 红线要求的"不动 RRF k=60 / 不动六轴准入谓词 / 不动 `halfvec(4096)` 与 HNSW / 不调低 0.38"在本轮新增内容中**一条未破**；凡与红线冲突的建议已直接**不采纳**并注明）。
