# RAG-D26 RAG 物料前期处理模块设计

> 版本：v1.3（阶段 0/1/2/3.1 全部落地；**v1.3 更正阶段 3.2 的接口重载落地时间**）
> 状态：**解析双后端（Docling + MinerU）与切分双策略（heading + hybrid）全部可用**；编排器切换（阶段 3.2）按 `rag.premise.enabled` 灰度推进
> 文档性质：设计 + 实施状态说明
> 上游约束：RAG-D00 决策基线、RAG-D02 §3.3 文档解析选型、RAG-D04 §5 版面语义对策、
> RAG-D09 §6 工具链已知缺陷与规避（端口层类型顶层化规则）
> 调研依据：`中文教材PDF切分方案调研.md`（首选组合 = MinerU / PP-StructureV3 解析 + Docling HybridChunker / 自建标题层级分块）
>
> **实施状态（v1.2，2026-11-06）**：
> - ✅ 阶段 0：`rag-premise` 模块建成；规则类从 `IngestionPipeline` 原样搬迁并委托，**既有测试一条不改地通过**；
> - ✅ 阶段 1.1：`DoclingPremiseBackend`（包装既有端口；`parseWithRaw` 一次往返同时产出块序列与原始 DoclingDocument，供分块桥复用）；分段路径下 raw 不可信（`wholeDocument=false`）→ HybridChunker 对其 fail-closed；
> - ✅ 阶段 1.2：`MineruPremiseBackend` + `MineruResponseMapper`（**实测接入**，MinerU 4.1.7：Tasks API 三步流程、`page_idx` 0 基 +1 换算、29 种块类型映射、`metadata.producer.version` 留痕；共享版面判定抽到 `LayoutPredicates`——题号/答案区/页眉/噪声标题与 Docling 同一套规则）；PP-StructureV3 仍为占位（无服务可测）；
> - ✅ 阶段 1.4：`NormalizationRules`（页码基准 / 阅读顺序 / 字符区间 / 重复行噪声）；
> - ✅ 阶段 2.1：`HeadingHierarchyChunker`（含超预算重叠拆分；答案区块不拆）；**阶段 2.2：Python 分块桥已部署并实测**（`deploy/docling/chunk_service/`，docling-core 2.106.0；真实 162 页教材 → 444 chunks，item_refs 覆盖率 100%、char_spans 100%；注意 HybridChunker 默认 tokenizer 需从 huggingface 下载，国内环境用 `HF_ENDPOINT=https://hf-mirror.com` 启动）；`HybridChunkerClient` 含**角色回填**（item_refs → 覆盖块 → 最严角色）与 `MinimalJsonArrays`（premise 零 Jackson 依赖的受限解析）；
> - ✅ 阶段 2.4：`ChunkPolicy.fromConfigValue`（未知值装配期抛出）；2.5：`13_v1_0_4_chunk_policy_alter.sql`（`rag_ingest_job.chunk_policy` / `chunk_policy_config` + `rag_document.chunk_policy`，null = 未采集不写列）；
> - ✅ 阶段 3.1：`IngestionPipeline` 双模式（`rag.premise.enabled` 默认 false）；装配配置化（`rag.premise.parse-backend=docling|mineru`，选 mineru 未配端点拒绝启动）；
> - ✅ 阶段 3.2：**编排器留痕落库已接**——`IngestJobPort.markSucceeded(+Global)` 新增 7 参重载（default 方法委托旧签名，既有桩零改动兼容），JDBC 实现动态 SET（null 不写列）；两个编排器经 `pipeline.chunkPolicyTrace()` / `chunkPolicyConfigTrace()` 取值回写，旧内置切分传 null（不伪造策略值）；`13_v1_0_4` 增量须先于应用部署执行（alter 10 同款纪律）；
>   - ⚠️ **2026-11-06 更正（本条此前与代码不符）**：上条把「接口重载已新增」写成既成事实，实际**接口重载在 11-06 之前从未落地** —— 实现层（`JdbcIngestJobRepository`）与调用层（两个编排器）都已按新签名落地并带 `@Override`，唯独 `IngestJobPort` 只留着 5 参 / 4 参老签名，导致 `mvn compile` 在 `rag-application` 报 2 个错、其后 5 个模块全部 SKIPPED。**接口两个 `default` 重载于 2026-11-06 补齐**（委托旧签名，既有桩零改动兼容），补齐后 rag 子树 10 模块 BUILD SUCCESS / 671 项测试全绿。
>   - 教训形态与本项目此前四次「假绿」同源：**调用方与实现方都写完了，唯独契约没写**，于是「谁在调」看起来是通的（IDE 跳转能跳到实现），但编译器第一个拦住的偏偏是契约缺声明。契约层的重载/新增必须在**实现与调用同一次改动里**落地，不能拆成两批。
> - ✅ 阶段 4（部分，清理项）：**共享判定去重**——`DoclingResponseMapper` 的题号/答案区标题/页眉剥除/噪声标题/推进闸/答案区状态机/页眉阈值全部改为委托 `LayoutPredicates`（单份规则双 mapper 共用）；`LayoutPredicatesParityTest` 锁定 premise/infrastructure 两侧判定同源，**并抓出一个真实取严缺口**：`Answer Key` 等答案区标题短语不满足冒号前缀正则 → 已补入 `SOLUTION_PREFIX`（merge_peers 把答案区标题合进 chunk 块首时不再漏判）；`IngestInvariantEvidenceTest` 对照组探针相应调整（原探针文本恰为取严短语，改用不含短语的对照）；
> - ⬜ 阶段 3.3~3.4：灰度开册 / 对比评测（运维动作：`RAG_PREMISE_ENABLED=true` + 先跑 13 号 SQL）；阶段 4 余项（占位解析器定位收窄文案、删除 `LocalDeterministicDocumentParserPort` 于生产装配的可达性）。
> - 🔄 **阶段 3.3 灰度开册（2026-11-06，本地演练）**：`13_v1_0_4` 已先在本地 `ragdb` 执行（幂等复跑通过），随后以 `RAG_PREMISE_ENABLED=true` + `chunk-policy=heading` + `parse-backend=docling` 起 `rag-worker`，对**真实 162 页沪教版英语教材**（`corpus-bucket` 内既有语料）跑整册摄取。
>   - ⚠️ **首轮实测抓出一个从未暴露过的留痕回写缺陷**：解析 → 切分 → 嵌入 → 落库全部成功（**972 片段 + 972 向量入库**），却在最后一步 `markSucceeded` 报「未设定参数值 6 的内容」→ job 卡在 FAILED。根因是动态 SET 拼 SQL 时 **`job_id` 的占位符在 WHERE 段、参数却按 SET 段顺序追加**，漏绑了 jobId —— 且旧签名（两列都为 null）下同样少一个，说明**这条回写路径从来没成功过一次**。已修复并补 5 例参数绑定回归测试（详见 `f932327`）。
>   - ⚠️ 演练环境的三处口径差异（本地库与生产不同，按需对齐）：① 嵌入维度 —— 本地 Ollama 的 `qwen3-embedding:8b` 返回 **4096** 维，而 `halfvec(4096)` 列正好匹配（此前误配 1024 → 维度守卫 fail-closed 拦下）；② 蕴含端口仍是词法占位，`LocalPortGate` 按设计**拒绝启动**，本地演练需显式 `RAG_LOCAL_PORTS_ENABLED=true`（启动横幅以 ERROR 级记录，不静默）；③ `rag_edition.publisher` 必须取**版本体系名**（沪教版），不是出版机构名（上海教育出版社）—— 写错会让该书的任何一次检索 500（`SourceRegistration.normalizedEditionPublisher` 的注释已警告）。
> - **端到端实测记录（2026-11-06）**：真实 162 页英语教材 PDF，MinerU flash 档解析 189 秒 → 3099 块（318 标题 / 1605 段落 / 60 表格 / 370 图 / 746 噪声）→ heading 切分 2042 片段（9 处超长拆分）→ **泄题闸门 leak=0**；留痕 `mineru/4.1.7`。分块桥实测：同一教材 DoclingDocument → 6.4 秒 → 444 chunks。
>   - **Docling 后端本地实测**（本轮）：162 页教材按「每段 20 页 / 共 9 段」解析约 8 分钟 → heading 切分 **972 片段** → 4096 维嵌入 972 条全部入库。解析耗时约为 MinerU（189 秒）的 2.5 倍 —— 灰度选型时这是必须计入的成本差。

---

## 0. 为什么需要这个新模块（现状与痛点）

当前仓库里**并不存在**独立的"物料前期处理"层，解析与切分是**混在两条互不相同的路径里**的：

| 现状路径 | 位置 | 解析 | 切分 | 问题 |
|---|---|---|---|---|
| 生产入库路径 | `rag-worker` → `IngestJobOrchestrator` → `IngestionPipeline` | `DocumentParserPort`（Docling 或占位实现） | **固化在 `IngestionPipeline` 内部**（`resolveRole` / `updateSectionPath` / 题号继承） | ① 解析器只有 Docling 一个真实实现；② 切分策略**不可切换**，想换 HybridChunker 只能改管道本体；③ 解析与切分耦合在同一个类里，无法单独评测 |
| 离线导入路径 | `scripts/rag_import_pdf.py` | 自己按页抽文本 | 自建规则（`CleaningRules`/`TocRules`/`AppendixRules` 的 Python 原版） | 与生产路径**行为不一致**，D04 §5.5 已承认「结构优先目前只在离线通道里成立」 |

由此产生四个具体缺口：

1. **解析器单一**：`DoclingDocumentParserPort` 是唯一真实实现（占位实现 `LocalDeterministicDocumentParserPort` 仅用于链路验证）。MinerU 与 PP-StructureV3——调研结论里中文教材的第一梯队——**完全没有接入点**。
2. **切分不可切换**：`IngestionPipeline` 只有一个 `DocumentParserPort` 构造参数，切分算法写死在 `ingest()` 方法体内。HybridChunker 作为"另一种切分"无处安放。
3. **无法做解析/切分的正交评测**：RAG-D08 要求证明"切分质量"，但当前产物只有 `Chunk`，中间态的 `LayoutBlock` 序列**不落盘、不可回放**，换解析器后无法解释"质量变化来自解析还是来自切分"。
4. **版本留痕不足**：`rag_document.parser_version` 只有一列（`docling-serve-1.x`），换解析器即意味着这列语义漂移；**切分策略版本无处记录**。

> ⚠️ 本模块的设计目标不是"再写一套解析代码"，而是**把解析与切分从管道里抽出来，做成两段可替换、可独立评测、可留痕的工序**。

---

## 1. 模块结构

### 1.1 命名与在几何中的位置

新增 Maven 模块 `rag-premise`（物料前期处理，"premise" 取"前置物料准备"之意，避免与 `rag-ports` 的"端口"混淆）。

放在 `rag/` 聚合工程内，与既有 8 个模块并列：

```
rag/
├── rag-core/            # 领域层（零框架依赖）
├── rag-ports/           # 端口定义（顶层类型规则）
├── rag-application/     # 用例编排
├── rag-infrastructure/  # 外部适配器（Docling 适配器在此）
├── rag-premise/         # ★ 新增：物料前期处理（解析 + 切分）
├── rag-worker/          # 异步 Worker
├── rag-api/             # REST
├── rag-management/      # 管理面
└── rag-bootstrap/       # 装配启动
```

**为什么是独立模块而不是塞进 `rag-infrastructure`？**

- `rag-infrastructure` 已经承载 pgvector 检索、JDBC 持久化、嵌入/蕴含适配器、Docling 适配器，职责已饱和；
- 本模块需要**同时**引入 MinerU 客户端、PP-StructureV3 客户端、Docling 分块桥（三种外部依赖），塞进 infrastructure 会让该模块的编译期依赖暴增，且无法被单独替换；
- 关键：本模块需要一套**自己的 SPI 接口 + 归一化的中间模型**，它与 `rag-ports` 的端口职责不同（见 §1.2），需要独立目录承载。

### 1.2 分层与依赖方向

```
rag-core  ←  rag-ports  ←  rag-premise  ←  rag-application  ←  rag-worker
             (端口)       (SPI+归一化)      (用例)             (消费)
```

- `rag-premise` **依赖** `rag-core`（复用 `ChunkRole`、`LayoutFacts`）与 `rag-ports`（复用 `LayoutBlock`、`ParsedDocument`、`BlockType` 词汇表）；
- `rag-premise` **不依赖** Spring（纯 Java，保持可独立单测）、**不依赖** `rag-infrastructure`；
- `rag-application` 通过 `rag-premise` 暴露的**用例对象**调用它（依赖方向不变，仍是从 application 指向更底层的领域/端口能力）。

> ⚠️ 与既有 `RagCorePurityTest` 的精神一致：本模块**不把框架类型泄漏进核心算法**。HTTP 客户端、JSON 解析器只在 `adapter` 子包内出现，`chunker`/`normalize` 子包保持纯函数。

### 1.3 目录划分与组件职责

```
rag-premise/
├── pom.xml
└── src/main/java/cn/studykid/rag/premise/
    ├── model/                         # 归一化中间模型（本模块的核心资产）
    │   ├── PremiseDocument.java       # 解析工序的统一输出（blocks + 文档级元信息 + 产出来源）
    │   ├── PremiseBlock.java          # 归一化版面块（对齐 LayoutBlock，额外携带页内 bbox 与置信度）
    │   ├── ParseTrace.java            # 解析留痕：后端名、后端版本、耗时、告警、分段信息
    │   ├── ChunkPolicy.java           # 切分策略枚举（HYBRID_CHUNKER / HEADING_HIERARCHY）
    │   ├── ChunkPolicyConfig.java     # 切分参数（token 上限、重叠、是否合并同标题邻居…）
    │   └── ChunkOutcome.java          # 切分工序的统一输出（chunks + 切分留痕）
    │
    ├── spi/                           # 两段工序的 SPI（可替换点）
    │   ├── DocumentParseBackend.java  # 解析后端接口（MinerU / PP-StructureV3 / Docling 三实现）
    │   └── BlockChunker.java          # 切分器接口（HybridChunker / 标题层级两实现）
    │
    ├── normalize/                     # 归一化：各后端原始输出 → PremiseDocument
    │   ├── NormalizationRules.java    # 跨后端的一致性规则（页码基准、页眉页脚、块排序、字符区间）
    │   └── SectionPathResolver.java   # 章节路径推进（截断语义，移植自已验证的 updateSectionPath）
    │
    ├── adapter/mineru/                # MinerU 适配器
    │   ├── MineruParseBackend.java    # Tasks API 客户端（提交/轮询/下载）
    │   └── MineruResponseMapper.java  # MiddleJson → PremiseBlock
    ├── adapter/paddle/                # PP-StructureV3 适配器
    │   ├── PaddleStructureParseBackend.java
    │   └── PaddleResponseMapper.java
    ├── adapter/docling/               # Docling 三用：解析后端 + HybridChunker 桥
    │   ├── DoclingParseBackend.java       # 复用现有 DoclingDocumentParserPort 的能力
    │   ├── DoclingChunkingBridge.java     # ★ HTTP 调 Python 分块服务（关键约束见 §2.4）
    │   └── DoclingChunkResponseMapper.java
    │
    ├── chunker/                       # 纯 Java 切分器
    │   ├── HeadingHierarchyChunker.java   # 自建"标题层级分块"
    │   └── HybridChunkerClient.java       # 调用 Docling 分块桥（委托 adapter/docling）
    │
    ├── role/                          # 角色判定（从 IngestionPipeline 抽出，行为不变）
    │   └── BlockRoleResolver.java
    │
    └── PremisePreprocessor.java       # 门面：一次调用 = 解析 + 切分 + 留痕
```

#### 组件职责与边界

| 组件 | 职责 | **不做**什么 |
|---|---|---|
| `DocumentParseBackend`（SPI） | 输入 `(documentId, byte[] content)`，输出 `PremiseDocument`；**失败必须抛异常**，不得降级 | 不碰数据库、不碰 chunk 角色、不决定切分策略 |
| `adapter/*` | 处理 HTTP、鉴权、轮询、超时、分段；把后端原始 JSON 映射为归一化块 | 不实现任何"教学语义"判定（答案区/题号归属属归一化之后） |
| `normalize/` | 跨后端一致性：页码基准统一、页眉页脚剥离、阅读顺序、`charStart/charEnd` 连续化、章节路径截断 | 不做角色判定（角色是 `role/` 的事） |
| `BlockChunker`（SPI） | 输入 `PremiseDocument` + `ChunkPolicyConfig`，输出 `ChunkOutcome` | 不负责解析、不负责知识点挂接、不写库 |
| `chunker/HeadingHierarchyChunker` | 纯 Java 实现：标题开新节、题块自成单元、答案块自成单元、超长块按 token 预算拆分且带重叠 | 不调外部服务（可离线单测） |
| `chunker/HybridChunkerClient` | 调 Docling 分块桥，把返回的 chunk 序列映射回 `ChunkOutcome` | 不内嵌 Python 运行时（约束见 §2.4） |
| `role/BlockRoleResolver` | 从 `IngestionPipeline` **原样搬出**的 `resolveRole` / 题号继承 / 前缀兜底 | **不得修改判定语义**（见 §3.4 的回归门禁） |
| `PremisePreprocessor` | 编排：选后端 → 解析 → 归一化 → 选切分器 → 切分 → 打留痕 | 不持久化（持久化仍归 `IngestJobOrchestrator`） |

### 1.4 与既有 `rag-ports` 的关系（重要，避免两套词汇表）

`rag-ports` 已有 `LayoutBlock` / `ParsedDocument` / `BlockType`，本模块**不重新发明**这些类型：

- `PremiseBlock` 是 `LayoutBlock` 的**超集**（多带 bbox 与置信度），仅存在于模块内部；
- `PremiseDocument` 通过一个显式的 `toParsedDocument()` 方法**降维**成既有的 `ParsedDocument`；
- 这样 `IngestionPipeline` 可以**零改动**地继续消费 `ParsedDocument`（兼容性兜底，见 §3.3 阶段 3）。

> ⚠️ 严格遵守 `NestedTypeImportGuardTest` 的结论：`spi` 与 `model` 里的类型**全部顶层声明**，不得出现嵌套类型，也不得让消费方的 classfile 持有 `BlockType` 常量的 `getstatic` 引用（判定语义一律走 `isHeading()/isNoise()` 实例方法）。

---

## 2. 数据流与接口设计

### 2.1 端到端数据流

```
              ┌──────────────────────────── 解析工序（parse） ────────────────────────────┐
  PDF bytes   │  DocumentParseBackend:  MinerU / PP-StructureV3 / Docling                │
 ───────────▶ │        ↓ 后端原始 JSON (MiddleJson / PP-Structure JSON / DoclingDocument)  │
              │  normalize: 页码基准 → 页眉页脚剥离 → 阅读顺序 → char 区间连续化          │
              │        ↓ PremiseDocument(blocks[], ParseTrace)                            │
              └───────────────────────────────────────────────────────────────────────────┘
                                          ↓
              ┌──────────────────── 切分工序（chunk） ───────────────────────────────────┐
              │  BlockChunker: HEADING_HIERARCHY（纯 Java） | HYBRID_CHUNKER（Docling 桥） │
              │  ① normalize 后的块序列 → 章节路径栈（截断语义）                           │
              │  ② BlockRoleResolver 判角色（版面事实优先，前缀兜底）                       │
              │  ③ 边界：标题开新节 / 题自成单元 / 答案自成单元 / 超预算按 token 拆+重叠     │
              │        ↓ ChunkOutcome(chunks[], ChunkTrace)                                │
              └───────────────────────────────────────────────────────────────────────────┘
                                          ↓
                         IngestJobOrchestrator（既有）：嵌入 → 落库 → 回写状态
```

**关键设计点：切分发生在"归一化之后"，因此两个切分器看到的是**同一份** `PremiseDocument`**。这使得「换解析器 / 换切分器」的评测可以正交进行：固定切分器换解析器 = 衡量解析质量；固定解析器换切分器 = 衡量切分质量。

### 2.2 解析层 SPI

```java
package cn.studykid.rag.premise.spi;

/** 解析后端 SPI。实现类一律 fail-closed：不可用时抛异常，绝不返回空文档或降级。 */
public interface DocumentParseBackend {

    /** 后端标识（写入留痕）。如 "mineru-4.1" / "pp-structurev3" / "docling-serve-1.x"。 */
    String backendId();

    /** 解析文档。 */
    PremiseDocument parse(String documentId, byte[] content);
}
```

三个实现的接口事实（**已核实**，不是假设）：

| 后端 | 接口形态 | 归一化要点 |
|---|---|---|
| **MinerU 4.x** | `POST /v1/tasks` 提交 → `PUT` 上传 → `GET /v1/tasks/{task_id}` 轮询 → 下载 `result_url`（ZIP，含 `middle_json.json`）。**`page_idx` 从 0 开始**（与 CLI 的 1 起页范围不同，是已知的坑） | 块类型 `type` ∈ `text/table/image/equation/...`；跨页表格有 `continues_prev`；`metadata.producer.version` 是真实版本号，必须写进 `ParseTrace` |
| **PP-StructureV3** | Python 产线（`PPStructureV3`）或 PaddleX 服务化；输出含 `parsing_res_list`（每个元素带 `block_label` / `block_content` / `bbox` / `page_index`），`block_label` 是**20 类版面标签**（含"目录""页眉""页脚""页码""公式编号""表格标题"等） | 20 类标签 → `BlockType` 的显式映射表；"目录/页眉/页脚/页码/脚注"直接映射为 `BOILERPLATE` |
| **Docling** | 已有 `DoclingDocumentParserPort`（`POST /v1/convert/file`，multipart `files`，`to_formats=json`），产出 `document.json_content`，阅读顺序由 `body.children` 的 `$ref` 序列决定 | 复用既有 `DoclingResponseMapper`；**但注意 docling-serve 无 chunk 端点**（见 §2.4） |

> ⚠️ **一个必须写进代码注释的事实**：docling-serve 的 REST API 只有 `/v1/convert/*`、`/v1/status/*`、`/v1/result/*`，**没有 chunk 端点**。HybridChunker 是 Python 侧 `docling.chunking` 的类，无法通过现有 HTTP 接口调用。
> 来源：[docling-serve REST API](https://docling-project.github.io/docling/usage/api_server/rest_api/)（表 "Endpoints" 无 chunk 项）、[Chunking 概念页](https://docling-project.github.io/docling/concepts/chunking/)（仅说明 `from docling.chunking import HybridChunker`）。
> **这直接决定了 §2.4 的架构选择**。

### 2.3 切分层 SPI

```java
package cn.studykid.rag.premise.spi;

/** 切分器 SPI。实现必须是确定性的：同一输入 → 同一输出（否则 chunk_id 不可复现）。 */
public interface BlockChunker {

    /** 策略标识（写入留痕）。对应 ChunkPolicy 枚举。 */
    ChunkPolicy policy();

    /** 切分。 */
    ChunkOutcome chunk(PremiseDocument document, ChunkPolicyConfig config);
}
```

`ChunkPolicy` 与 `ChunkPolicyConfig`：

```java
public enum ChunkPolicy {
    HEADING_HIERARCHY,   // 自建：标题层级分块（纯 Java，可离线）
    HYBRID_CHUNKER       // Docling HybridChunker（经 Python 分块桥）
}

public record ChunkPolicyConfig(
        int maxTokens,              // 单块 token 预算（默认 512，须与嵌入模型对齐）
        double overlapRatio,        // 重叠比例（默认 0.15，且必须 > 0，D04 §5.1 硬要求）
        boolean mergePeers,         // 是否合并同标题的相邻小块（HybridChunker 的 merge_peers）
        boolean repeatTableHeader   // 表格跨块是否重复表头（HybridChunker 的 repeat_table_header）
) { }
```

两个实现的语义差异（**必须记录，否则切换会发生静默质量漂移**）：

| 维度 | HEADING_HIERARCHY（自建） | HYBRID_CHUNKER（Docling） |
|---|---|---|
| 边界依据 | 标题层级 + 题号 + 答案区 | 元素层级 + token 预算（先拆后并） |
| 超长块处理 | 按 token 预算 + `overlapRatio` 重叠拆分 | 仅按 token 上限拆，无重叠 |
| 小节合并 | 不合并（保持结构边界） | `merge_peers` 默认合并同标题相邻块 |
| 角色判定 | **由 `BlockRoleResolver` 判定（版面事实）** | Docling chunk 不带 `ChunkRole`，须**回填**（见下） |
| 依赖 | 纯 Java，无外部服务 | 需 Python 分块桥（§2.4） |

> ⚠️ **HYBRID_CHUNKER 的角色回填是必须的、不是可选的**：Docling chunk 的 metadata 只有标题/图注等，**没有** `EXERCISE_ANSWER` 这类角色。若直接入库，答案类片段会丢失角色 → 检索期泄题闸门（`RetrievalPolicy.applyRoleFilter`）失效 → **泄题**。
> 回填方式：chunk 的 `meta.headings` + 其覆盖的块区间，回查 `PremiseDocument` 中对应块的角色，按"最严角色优先"（见 `BlockRoleResolver` 的取严规则）合成 chunk 角色。

### 2.4 关键约束：HybridChunker 如何接进 Java

由于 docling-serve **不提供 chunk 端点**，只有三种可行路径：

| 方案 | 做法 | 优点 | 缺点 | 建议 |
|---|---|---|---|---|
| **A. Python 分块桥（推荐）** | 独立小服务：`POST /v1/chunk`，入参 `{docling_json, policy_config}`，出参 `{chunks[]}`；内部用 `HybridChunker` | 真正用官方实现；与解析服务同栈（同一镜像可加一个路由）；分块参数集中 | 多一个进程/容器；需维护 DoclingDocument 的**版本对齐** | ✅ **采纳** |
| B. JVM 内嵌 Python | GraalPy / Jython 跑 docling | 无额外进程 | docling 依赖 torch/tokenizers，JVM 内嵌基本不可行 | ❌ |
| C. 纯 Markdown 复刻 | 把 Docling 的 markdown 导出拿来自己按标题切 | 简单 | 丢掉 DoclingDocument 的结构元信息，**等于没用 HybridChunker** | ❌ 仅作降级对照 |

**方案 A 的接口契约**（`DoclingChunkingBridge` 与 Python 侧）：

```json
// 请求
POST /v1/chunk
{
  "docling_json": { ... },              // 复用解析阶段的原始 DoclingDocument（不是重新解析）
  "config": {
    "max_tokens": 512,
    "merge_peers": true,
    "repeat_table_header": true,
    "tokenizer": "embedding-aligned"
  }
}
// 响应
{
  "chunks": [
    {
      "text": "...",
      "headings": ["第3章 二次函数", "3.2 二次函数的图像"],
      "page_numbers": [45],
      "char_spans": [{"start": 120, "end": 860}],
      "item_refs": ["#/texts/12", "#/texts/13"]
    }
  ],
  "producer": { "name": "docling", "version": "2.x" }
}
```

**设计要点（三条，缺一即失真）**：

1. **必须复用解析阶段的 DoclingDocument JSON，不能二次解析**：否则分块看到的文档与角色回填看到的 `PremiseDocument` 不是同一份，`item_refs` 无法对齐，回填必然错位。
2. **`item_refs` 是回填的锚点**：Python 侧必须返回每个 chunk 覆盖的 Docling item `$ref` 列表；Java 侧用它在 `PremiseDocument` 中定位块区间 → 取角色。
3. **`char_spans` 必须能映射回原页字符流**：这是"可回取原文"门禁证据（D09 G1 ③）的要求，HybridChunker 原生不保证给字符偏移，需要在 Python 侧按 item 的 `prov` 累加算出。

> ⚠️ 若 Python 桥不可用，**必须 fail-closed**：`HybridChunkerClient` 抛异常让 job FAILED，**不得静默回退到 HEADING_HIERARCHY**。理由同 `DoclingDocumentParserPort` 的注释：切分策略切换是"这批语料按哪套规则切的"的留痕事实，静默替换会让留痕失真，且两种策略的 token 边界不同，检索表现不可比。

### 2.5 留痕与版本字段（数据库改造的最小集）

现有 `rag_document.parser_version` 一列不足以表达"解析器 + 切分器 + 参数"三元组。**最小可行改造**：

| 表 | 变更 | 理由 |
|---|---|---|
| `rag.rag_document` | 新增 `chunk_policy text`（`HEADING_HIERARCHY` / `HYBRID_CHUNKER`） | 让"这批语料怎么切的"可查、可据此决定是否重灌 |
| `rag.rag_ingest_job` | 新增 `chunk_policy text`、`chunk_policy_config jsonb` | 同一次 job 的完整留痕；失败时也能看到用的哪套参数 |
| `rag.rag_chunk` | **不改结构**（`chunk_id` 已是确定性 UUID） | 换策略重灌靠 `ON CONFLICT DO UPDATE` 语义保持 |

> ⚠️ 沿用 D04 §5.4 的实测教训：重灌时必须**同时清空 `text_jieba`**，否则 `ChunkIndexer` 的待索引判据（`text_jieba='' OR 无向量行`）不会命中，表现为"改了切分但检索没变"的静默陈旧向量。

---

## 3. 对现有项目的改造步骤

改造遵循**"先加不改、再切换、最后清理"**，每一步都可独立回滚。

### 阶段 0：准备（不改任何生产行为）

| 步骤 | 内容 | 验收 |
|---|---|---|
| 0.1 | 新建 `rag-premise` 模块，在 `rag/pom.xml` 的 `<modules>` 与 `<dependencyManagement>` 登记；`rag-application` 增加对它的依赖 | `mvn -o -Dmaven.repo.local=.m2repo -DskipTests compile` 通过（当前基线可复现此命令，exit=0） |
| 0.2 | 在模块内建 `model/`、`spi/`、`normalize/`、`role/`、`chunker/`、`adapter/` 骨架，**`PremisePreprocessor` 暂不被任何生产代码调用** | 无生产行为变化；既有测试全绿 |
| 0.3 | 把 `IngestionPipeline` 的 `resolveRole` / `matchedSolutionPrefix` / `resolveAnswerBearingByContent` / `updateSectionPath` **原样搬迁**到 `BlockRoleResolver` 与 `SectionPathResolver`，`IngestionPipeline` 改为**委托调用**这两个类 | **行为零变化**：`IngestionPipelineTest`、`IngestInvariantEvidenceTest`、`OfflineParity` 相关测试全部原样通过（这是本次改造最重要的安全垫） |

> ⚠️ 步骤 0.3 的判据不是"新测试通过"，而是"**旧测试一条不改地通过**"。角色判定是泄题源头，任何语义漂移都不可接受。

### 阶段 1：解析层多后端化

| 步骤 | 内容 | 验收 |
|---|---|---|
| 1.1 | 实现 `adapter/docling/DoclingParseBackend`，**包装**既有 `DoclingDocumentParserPort`（不重写，先建立 SPI 形状） | 用 `config/` 下现有 Docling JSON 样张跑通；产物与旧路径一致（`PremiseBlock` ↔ `LayoutBlock` 逐字段 diff 为零） |
| 1.2 | 实现 `adapter/mineru/`：Tasks API 客户端（提交/上传/轮询/下载/解 ZIP 取 `middle_json.json`）+ 映射器；**`page_idx` +1 换算**到 1 起页码；`continues_prev` 处理跨页表格 | 单测：用固化的 MiddleJson 样张断言块序列、页码、类型映射；**关键断言**：`page_idx=0` 必须映射到 `pageNumber=1` |
| 1.3 | 实现 `adapter/paddle/`：PP-StructureV3 结果（`parsing_res_list`）→ `PremiseBlock`；20 类 `block_label` 显式映射表 | 单测覆盖 20 类标签的映射，**特别是** 目录/页眉/页脚/页码 → `BOILERPLATE` |
| 1.4 | `normalize/` 落实跨后端一致性：页眉页脚剥离（复用 `CleaningRules.stripBoilerplate` 语义）、字符区间连续化、阅读顺序稳定 | **一致性门禁**：同一份教材分别用三后端解析 → 归一化后计算「块数偏差 / 标题层级一致性 / 页码覆盖率」，写入评测报告（不进 CI 硬门禁，因为三个后端必然有差异） |
| 1.5 | 配置化选择：`rag.premise.parse-backend=docling|mineru|paddle`，启动时校验唯一性，**fail-closed**（未配置或不可用即拒绝装配） | 装配测试：三选一的 bean 正确；缺失配置时启动失败而非静默降级 |

### 阶段 2：切分层双方案

| 步骤 | 内容 | 验收 |
|---|---|---|
| 2.1 | 实现 `chunker/HeadingHierarchyChunker`（纯 Java）：用 0.3 抽出的 `SectionPathResolver` + `BlockRoleResolver`，加 token 预算拆分与重叠 | **等价性门禁**：对同一批 `LayoutBlock`，新切分器与旧 `IngestionPipeline.ingest()` 的 `Chunk[]` **逐条等价**（id / role / text / locator 全等）。这是"抽出来没改坏"的证明 |
| 2.2 | 实现 Python 分块桥（独立目录，如 `deploy/docling/chunk_service/`），复用同镜像加 `/v1/chunk` 路由，入参复用解析阶段的 DoclingDocument JSON | 契约测试：固定 DoclingDocument 样张 → chunk 序列稳定；`item_refs` 与 `char_spans` 非空 |
| 2.3 | 实现 `chunker/HybridChunkerClient` + `DoclingChunkResponseMapper`，含**角色回填**（`item_refs` → 块区间 → 最严角色） | **泄题门禁（最关键）**：构造"答案区块被合并进一个 chunk"的用例，断言该 chunk 角色为 `EXERCISE_ANSWER` 而非 `EXPLANATION`；并断言 `RetrievalPolicy.applyRoleFilter` 在讲解模式下确实过滤掉它 |
| 2.4 | 配置化切换：`rag.premise.chunk-policy=heading|hybrid` + 参数（`max-tokens` / `overlap-ratio` / `merge-peers` / `repeat-table-header`） | 切换测试：同一输入下两策略产出合法 `ChunkOutcome`；**失败路径**：Hybrid 桥不可用时抛异常而非回退 |
| 2.5 | 切分留痕落库：`chunk_policy` / `chunk_policy_config` 写入 `rag_ingest_job` 与 `rag_document` | IT：断言留痕字段与实际使用的策略一致 |

### 阶段 3：接入生产管道（切换点）

| 步骤 | 内容 | 验收 |
|---|---|---|
| 3.1 | `IngestionPipeline` 增加"委托模式"：可注入 `BlockChunker`，**默认仍走旧逻辑**（开关 `rag.premise.enabled=false`） | 默认关闭时所有既有行为不变 |
| 3.2 | `IngestJobOrchestrator` / `GlobalIngestJobOrchestrator` 改为调用 `PremisePreprocessor.process()`，拿到 `ChunkOutcome` 后再走既有的嵌入/落库 | 灰度：单册跑通，`chunk_count` 与旧路径可解释（不是随机数） |
| 3.3 | 打开 `rag.premise.enabled=true` 灰度（建议先 1 册教辅 + 1 册扫描版） | **门禁证据**：① 无泄题（讲解模式检索不返回答案类）；② 可回取原文（`char_start/char_end` 能定位到 PDF 原文）；③ 页码与印刷页对齐 |
| 3.4 | 对比评测：同一教材，旧路径 vs 新路径（heading） vs 新路径（hybrid），用自建教材问题集跑 RAG-D08 的检索指标 | 结论写进评测报告；**不达标不扩大灰度** |

### 阶段 4：清理与文档收口

| 步骤 | 内容 |
|---|---|
| 4.1 | `LocalDeterministicDocumentParserPort` 的定位收窄为"仅本地占位"，`LocalPortGate` 的 ERROR 横幅文案更新（解析器已有三个真实后端） |
| 4.2 | 更新 `RAG-D02 §3.3`（解析选型从"Docling 类"改为"MinerU/PP-StructureV3 为主 + Docling 为对照"）、`RAG-D04 §5.5`（"结构优先只在离线通道成立"这条限制随阶段 3 关闭而消除） |
| 4.3 | 迁移脚本：新增列 + 回填历史行（`chunk_policy='HEADING_HIERARCHY'`，因为历史数据是旧管道切的）；**同时清空 `text_jieba`** 对需要重灌的册 |
| 4.4 | 删除 `IngestionPipeline` 内的内联切分逻辑（改为完全委托），删除重复的 `SOLUTION_PREFIX` 定义 |

---

## 4. 风险与不做什么

| 风险 | 说明 | 对策 |
|---|---|---|
| **双词汇表漂移** | `PremiseBlock` 与 `LayoutBlock` 字段不一致会导致角色判定错位 | `PremiseBlock → LayoutBlock` 的降维有**逐字段单测**；不允许两边各写一套 `BlockType` |
| **HybridChunker 无角色** | 直接入库会**泄题** | 阶段 2.3 的泄题门禁是**上线前置条件**，不可后补 |
| **两策略 token 边界不同** | 换策略后检索表现不可比，易被误归因为"模型变差" | 留痕 `chunk_policy` + 阶段 3.4 的对比评测 |
| **三后端结果差异大** | MinerU / Paddle / Docling 的块粒度本来就不同 | 阶段 1.4 只做"一致性**度量**"，不做"一致性**强制**" |
| **重灌陷阱** | 改切分后 `text_jieba` 不清 → 静默陈旧向量 | 迁移脚本内置清空；IT 断言重灌后向量确实更新 |
| **端口嵌套类型** | JDK 21 javac 符号解析缺陷 | `spi`/`model` 全顶层类型；`NestedTypeImportGuardTest` 扩展覆盖 `rag-premise` |

**明确不做**：

- ❌ 不在 JVM 内嵌 Python 运行时（方案 B）；
- ❌ 不为 HybridChunker 做"纯 Markdown 复刻"并冒充官方实现（方案 C 只作对照，不进入主干）；
- ❌ 不在本模块内做嵌入、索引、知识点挂接（这些仍属既有模块）；
- ❌ 不改造 `rag-management` 的 UI（本模块只保证字段可查，UI 展示属后续迭代）。

---

## 5. 一页速览

```text
PDF ──▶ [解析 SPI]  MinerU / PP-StructureV3 / Docling
             │
             ▼  normalize（页码基准·页眉页脚·阅读顺序·字符区间）
        PremiseDocument ──(toParsedDocument)──▶ 既有 ParsedDocument（兼容兜底）
             │
             ▼  [切分 SPI]  HEADING_HIERARCHY（纯 Java） | HYBRID_CHUNKER（Docling 桥）
             │   + BlockRoleResolver（原样搬出，行为不变）
             ▼
        ChunkOutcome ──▶ IngestJobOrchestrator（既有：嵌入→落库→回写）
             │
             └─ 留痕：parser_version + chunk_policy + chunk_policy_config
```

**改造的四个安全垫**（按优先级）：
1. 阶段 0.3 的"旧测试不改地通过"——保证角色判定零漂移；
2. 阶段 2.1 的"新切分器与旧管道逐条等价"——保证抽出没改坏；
3. 阶段 2.3 的"答案 chunk 角色回填"——保证不泄题；
4. 阶段 3 的"默认关闭 + 灰度"——保证可回滚。
