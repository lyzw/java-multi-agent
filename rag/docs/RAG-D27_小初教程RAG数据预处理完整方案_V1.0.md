# RAG-D27 小初教程 RAG 数据预处理完整方案

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D27 |
| 版本 | v1.2（细化：选型优缺点、配置矩阵、兜底与成本；新增 Embedding 维度工程化治理） |
| 编制日期 | 2026-11-23 |
| 状态 | 设计稿，待评审 |
| 适用范围 | 小学、初中教材/教辅/讲义/试卷的 RAG 入库前处理 |
| 上游依据 | RAG-D02 §3.3、RAG-D04、RAG-D08、RAG-D09 G1、RAG-D26、RAG-D18 |
| 关联实现 | `rag-premise`、`IngestionPipeline`、`IngestJobOrchestrator`、`JdbcRetrievalRepository`、`deploy/sql/rag/` |
| 红线基线 | `docs/01_baseline/capabilities/08-知识检索RAG.md` §7.11、`RAG-D09 G1`、`.workbuddy/memory/MEMORY.md` |

---

## 0. 结论先行

小初教程的 RAG 预处理，不能沿用“PDF 抽文本 → 固定字数切块 →  embedding → 向量库”的通用流水线。原因不是模型不够强，而是**输入侧的信息在入库时已经被不可逆地破坏**：公式变乱码、题干与选项分离、答案被判成讲解、词表按印刷页而非逻辑单元切分、版本坐标缺失、图片中的教学语义丢失。这些损失在检索阶段无法补救。

推荐方案的核心是一句话：

> **以“版面解析 + 教学语义归一化 + 知识点父子块 + 场景化角色闸门 + 全链路留痕/评测”为主线；PDF 处理采用分级路由，切分采用结构优先而非纯语义优先，embedding 采用可私有化、可混合检索、可代际切换的方案。**

本方案与项目现有实现的对齐关系：

| 本方案环节 | 项目现有落点 | 需要补齐 |
|---|---|---|
| PDF 分级解析 | `DocumentParserPort`、`DoclingDocumentParserPort`、`MineruPremiseBackend` | 解析前分类路由、解析质量分、页级中间产物落盘 |
| 归一化 | `NormalizationRules`、`SectionPathResolver`、`LayoutPredicates` | 附录逻辑单位、跨页表、公式自然语言描述、核心图 caption 回灌 |
| 切分 | `HeadingHierarchyChunker`、`HybridChunkerClient` | 父子块、知识点语义单元、表格/公式原子化规则固化 |
| 角色与泄题闸门 | `BlockRoleResolver`、`RetrievalPolicy.allowsRole` | 答案册与教材的题答配对、角色抽检统计 |
| Embedding/索引 | `EmbeddingPorts`、`ChunkIndexer`、`rag_chunk_embedding` | 配置与 DB 列自检、重算触发、embedding 输入模板统一 |
| 评测与门禁 | `IngestInvariantEvidenceTest`、`Docling*Test`、RAG-D08 | 解析基准集、检索黄金集、CI 门禁、在线低分回流 |

> 本章只给总体主线。各环节的可选方案、优缺点、推荐配置、兜底路径与成本模型见 **§13 方案选型细化**；Embedding 维度的跨层契约、自检、换维 SOP 与反证验收见 **§14 维度工程化治理**。

---

## 1. 小初教程的内容特点与预处理目标

### 1.1 小初教程区别于通用文档的特点

| 特点 | 小学阶段表现 | 初中阶段表现 | 对预处理的要求 |
|---|---|---|---|
| 图文比例高 | 低年级插图、拼音、生字表、图示算术 | 几何图、函数图像、电路图、实验装置图、流程图 | 不能只抽文字；核心图必须生成可检索描述并绑定原文位置 |
| 知识点递进强 | 数与形、字词、常识按课时螺旋上升 | 定理/公式依赖先修知识，错题常源于前置缺口 | 知识点层要版本无关，并建立先修关系 |
| 版本差异敏感 | 不同出版社识字顺序、例题不同 | 人教版/北师大版/沪教版章节号与结论表述不同 | 五轴版本坐标必须入库，检索期硬过滤 |
| 题答分离 | 练习册与答案册常分离 | 试卷、教辅答案单独成册 | 题目、提示、答案分角色；讲解模式结构性不可见答案 |
| 版面语义密集 | 拼音格、田字格、彩色栏目、侧栏提示 | 双栏、公式编号、三线表、无框表、跨页表 | 版面事实优先，不能按字符流硬切 |
| 表达形态混合 | 自然语言为主，图表辅助 | 自然语言 + LaTeX 公式 + 化学式 + 符号缩写 | 公式必须 LaTeX 化，并补自然语言别名 |
| 引用可核对性强 | 家长/学生要翻书核对页码 | 教师要求“见教材第几页第几题” | 页码、章节路径、题号、字符区间、bbox 必须保留 |
| 合规敏感 | 涉及未成年人学习数据 | 涉及在校学生、考试成绩、题目版权 | 不把学号/姓名等 PII 拼进 embedding 文本或供应商 Prompt |

### 1.2 项目既有的五条本质差异

RAG-D04 已经给出教辅语料的五条本质差异，本方案全部继承：

| 编号 | 差异 | 预处理必须回答的问题 |
|---|---|---|
| D1 | 版本差异 | 这个片段属于哪一版、哪一册、哪一章？旧版如何软失效？ |
| D2 | 习题与答案分离 | 答案是否独立成角色？能否回指题号？讲解模式是否绝对看不到答案？ |
| D3 | 知识点碎片化 | 定义、公式、例题、易错点是否被组织成同一个语义单元？ |
| D4 | 版面语义密集 | 双栏、侧栏、题号、答案区、跨页表是否被正确识别？ |
| D5 | 答案可用性场景相关 | EXPLAIN/SOLVE/VERIFY 三档是否由同一角色规则控制？ |

### 1.3 预处理验收目标

预处理不是“把 PDF 变成字符串”，而是让每个入库片段同时满足六个属性：

1. **可检索**：文本、别名、公式自然语言描述、题号、术语进入词法与向量通道。
2. **可定位**：能回到教材版本、章节、页码、题号、字符区间。
3. **可判角色**：讲解/例题/习题/提示/答案/噪声的边界确定，答案类不混入讲解。
4. **可解释**：切分策略、解析器版本、嵌入模型、代际均可追溯。
5. **可评测**：解析、切分、检索、生成四层指标可独立归因。
6. **可回滚**：改版、重灌、换模型均走蓝绿代际或软失效，不物理删除。

---

## 2. 总体架构

### 2.1 分层流水线

```
原始 PDF/扫描件/答案册
    │
    ▼
[0] 准入与分类     六轴准入、版权、载体角色、原生/扫描/混合、单栏/多栏、公式/表格密度
    │
    ▼
[1] PDF 分级解析   轻量文本通道 / Docling / MinerU / OCR / 商业 API 补位
    │
    ▼
[2] 归一化         页码基准、页眉页脚、阅读顺序、字符区间、章节路径、跨页表、公式 LaTeX、图片 caption
    │
    ▼
[3] 教学语义增强   知识点挂接、别名、先修关系、角色判定、题答配对、难度/认知层次
    │
    ▼
[4] 父子块切分     父块=小节/知识点；子块=定义/例题/习题/表格/公式；答案原子化
    │
    ▼
[5] Embedding/索引 输入模板统一、dense+sparse、halfvec(4096) 代际、词法分词落库
    │
    ▼
[6] 门禁与发布     解析门禁、切分不变式、泄题反证、检索黄金集、灰度、回滚
```

### 2.2 与现有代码的挂接

- `rag-premise` 负责第 1～4 步的可替换 SPI：`DocumentParseBackend`、`BlockChunker`、`NormalizationRules`、`BlockRoleResolver`。
- `IngestJobOrchestrator` 保持第 5～6 步的编排：`PARSING → CHUNKING → EMBEDDING → INDEXING → SUCCEEDED`。
- `JdbcRetrievalRepository` 继续作为唯一生产检索真源；本方案不新增平台侧检索引擎。
- `rag_chunk` / `rag_chunk_embedding` / `rag_edition` / `rag_ingest_job` / `rag_index_generation` 是主要落库点。
- 新增字段一律走增量 SQL，并同步 `03_rag_roles.sql`、SchemaDriftIT、IT 库期望值。

---

## 3. 数据准入与前置治理

### 3.1 六轴准入

入库前必须确认来源满足既有六轴准入谓词所依赖的事实：

| 轴 | 要求 | 失败处理 |
|---|---|---|
| 租户与作用域 | `tenant_id` / `scope` 明确；全局桶用哨兵租户，不作会话身份 | fail-closed，不入库 |
| 来源可信度 | `trust_tier` 已定，教辅来源需可追溯 ISBN/版本 | 待核验，不进入可检索状态 |
| 版本状态 | `ver_status` 通过，五轴版本坐标齐全 | 拒绝入库 |
| 授权状态 | `license_status` 允许 RAG 使用 | 拒绝入库 |
| 角色适用范围 | 学生用书/教师用书/答案册明确 | 按最严格角色处理 |
| 内容完整性 | 非残卷、非缺页扫描、非加密无法解析 PDF | 整册 FAILED，不部分入库 |

### 3.2 版权与隐私

- 教材、教辅、试卷属于强版权内容；未获授权的 PDF 不进入向量库。
- 扫描件若含学生姓名、学号、班级、成绩等 PII，必须在解析前脱敏；任何 PII 不得进入 embedding 文本、 Prompt 或日志。
- 学号格式 `stu_[a-z0-9]{16}` 是唯一合法格式；禁止为了绕过扫描器而豁免学号检测。
- 云原生解析 API 会形成数据出域；默认采用私有化解析，商业 API 仅用于少量公式保真补位，并通过法务/数据安全审批。

---

## 4. PDF 文档处理方案

这是本方案的重点。小初教程 PDF 的失败大多发生在解析阶段，而不是检索阶段。

### 4.1 文档分类与分级路由

不要对所有 PDF 使用同一种解析器。建议先做一次轻量分类，再路由：

| 类型 | 判定信号 | 推荐后端 | 说明 |
|---|---|---|---|
| 原生单栏纯文本 | 文本层完整、无复杂表格/公式、单栏 | PyMuPDF/轻量文本通道 | 最快，仅用于封面、目录、简单讲义 |
| 原生多栏/含标题层级 | 有文本层但双栏或层级复杂 | Docling | 项目已有生产实现，适合英语教材、讲义 |
| 公式/表格密集 | 公式块或表格块占比高 | MinerU 4.x/2.5 或同类 VLM 解析器 | 项目已实测 MinerU 4.1.7；业界 OmniDocBench 显示其对教材类文档表现较好 |
| 扫描件/拍照件 | 文本层稀疏、图像占比高 | 图像矫正 + PaddleOCR/PP-StructureV3 + 版面分析 | 先去噪、矫正、二值化，再 OCR；低置信页人工复核 |
| 极高精度公式 | 手写公式、复杂矩阵、化学反应式 | Mathpix/TextIn 等商业 API 补位 | 仅对少量页使用，需数据出域审批 |
| 答案册 | 出现“参考答案/Answer Key/答案区”等结构 | 强制答案角色路径 | 角色判定取严，绝不与讲解混切 |

路由原则：

1. **先用便宜路径，复杂页升级**：页级路由而非整册一刀切；干净页走轻量路径，复杂页走 VLM。
2. **升级不可逆**：一旦某页被判为复杂页，不能用轻量路径结果覆盖。
3. **后端不可用时 fail-closed**：不得静默降级为另一个解析器；解析器版本必须写入 `parser_version` / `ParseTrace`。
4. **不复用不可达实现**：平台侧 `agent-infra` 草稿检索引擎与本链路无关，不在其上施力。

业界依据：MinerU2.5 论文在 OmniDocBench 上报告整体 90.67、教材类编辑距离 0.0499；TextIn 教育文档方案明确要求章节、公式、表格、阅读顺序、页码定位同时输出。不同榜单版本不可直接横向比较，必须用自有教材样本复测。

### 4.2 解析中间格式

PDF 不应直接作为切分输入。统一中间产物为：

1. **Markdown**：保留标题层级、段落、公式 `$...$` / `$$...$$`、表格。
2. **JSON**：每个块带 `page_number`、`bbox`、`block_type`、`reading_order`、`confidence`、`char_start/end`。
3. **原页缓存**：保留原始页图或页快照，用于人工抽检与引用回看。
4. **ParseTrace**：后端名、版本、耗时、告警、分段信息、路由原因。

示例结构：

```json
{
  "documentId": "...",
  "parser": {"name": "mineru", "version": "4.1.7", "route": "formula-dense"},
  "pages": [
    {
      "pageNumber": 45,
      "printPage": "P45",
      "blocks": [
        {
          "blockId": "b-145",
          "type": "EQUATION",
          "text": "$$v = v_0 + at$$",
          "latex": "v = v_0 + at",
          "naturalLanguage": "匀变速直线运动速度时间公式，v 为末速度，v0 为初速度，a 为加速度，t 为时间",
          "bbox": [120, 330, 460, 372],
          "charStart": 3820,
          "charEnd": 3902,
          "confidence": 0.97
        }
      ]
    }
  ]
}
```

### 4.3 解析归一化规则

归一化在切分之前完成，保证不同后端输出可被同一套切分规则消费。

| 规则 | 处理逻辑 | 失败模式 |
|---|---|---|
| 页码基准 | MinerU `page_idx` 0 基统一 +1；Docling 分段解析后做区间连续化 | 页码错位导致引用错误 |
| 页眉页脚剥离 | 按位置 + 重复率 + 文本模式识别；页码、书名、页眉不进入正文 | “第 12 页”污染几十个 chunk |
| 阅读顺序 | 版面块按人类阅读动线重排；双栏先左后右，再处理侧栏 | 左右栏串成一句话 |
| 字符区间 | `charStart/charEnd` 连续、可重建原文 | 无法回取原文，报告失真 |
| 章节路径 | 标题层级推进用**截断**而非追加：`第3章 > 3.2` 不能带 `3.1.1` | 学生按错误路径翻书 |
| 附录逻辑单位 | 词表按 `Unit N` / `Starter` 切，不按印刷页切；续页继承当前 Unit | “第五单元单词”查不到 |
| 中英双形标题 | `Unit 5（第五单元）单词表：...` | 中文问法与英文标题零重合 |
| 噪声判定 | 封面、版权页、定价页、目录页按内容判为 BOILERPLATE，而非按页号保留 | 版权页成为 top1 命中 |
| 跨页表 | 表体合并，表头继承；切片必须重复表头 | “第 3 行第 2 列”无法定位 |
| 跨文档题答 | 答案册与教材通过规范化题号 + edition 坐标配对 | 召回答案却不知对应哪题 |

### 4.4 公式处理

小初数学、物理、化学 PDF 的公式处理必须满足三条：

1. **LaTeX 化**：公式输出为 LaTeX，不能用图片或近似文字代替。多行公式、矩阵、对齐环境作为原子块保留。
2. **语义化双写**：在 embedding 文本中追加自然语言描述。例如：
   - `v = v0 + at` → “匀变速直线运动速度时间公式”
   - `E = mc^2` → “质能方程，E 为能量，m 为质量，c 为光速”
   - 化学式用 `mhchem` 或等价结构表达，保留反应条件与配平信息。
3. **符号进入词法通道**：公式编号、定理名、符号串、别名必须进入 `text_jieba`，否则纯词法通道无法命中长尾符号。

注意：公式自然语言描述必须由解析规则或受控模型生成并抽检，不能让生成模型在回答阶段自行猜测公式含义。

### 4.5 表格处理

| 表格形态 | 处理 |
|---|---|
| 三线表/无框表/合并单元格 | 用版面感知解析器输出 HTML 或 Markdown 表格，不再用 pdfplumber 规则猜行列 |
| 表格 ≤1500 token | 作为原子 chunk 整体保留，caption/表题与表体绑定 |
| 表格 >1500 token | 按行区间切分，每个切片重复表头；元数据标记 `table_part` 与 `parent_table_id` |
| 含公式的表格 | 单元格内公式仍走 LaTeX 化；表格不跨原子块拆散公式 |
| 跨页表 | 合并为同一逻辑表；续页表头继承 |
| 数值密集表 | 可选“转自然语言句子”增强检索，但需人工抽检防幻觉 |

### 4.6 图片与图示处理

小初教程的图不是装饰就是教学内容，必须先分类：

| 图片类型 | 处理 |
|---|---|
| 核心教学图：几何图、受力分析图、电路图、函数图像、实验装置、流程图 | VLM 生成详细描述；描述与图注、原文段落、bbox 绑定为同一语义单元 |
| 插图/卡通/装饰图 | 丢弃文本侧，仅保留页码存在性用于抽检 |
| 手写批注/答案笔迹 | 扫描件中需擦除或单独判为答案类；不得混入题干 |
| 图片内文字 | 必须先 OCR，再进入正常文本链；低置信项人工复核 |

### 4.7 答案区与角色判定

这是教学事故风险最高的一环，沿用项目既定规则：

1. 角色由**版面事实**判定：`inAnswerZone`、题号、答案区标题、块类型；不依赖生成模型猜测。
2. 不确定内容一律取严判为答案类；把答案判成讲解会导致讲解模式泄题，属于严重事故。
3. 讲解模式、解题模式、核对模式统一调用 `RetrievalPolicy.allowsRole`，禁止在多处重复实现。
4. 答案区遇新标题必须退出，防止下一章被整章误判为答案。
5. 题干含 `answers`、指令 `Answer the questions.` 不应触发答案区；裸 `Answers` 标题必须触发。
6. 每册做答案区人工抽检：随机 20 段，答案区识别准确率 ≥95%，漏判 0。

### 4.8 解析质量门禁

解析层是后续一切质量的上限。建议在正式入库前设置独立门禁：

| 指标 | 口径 | 建议阈值 | 失败处理 |
|---|---|---|---|
| 文本 NED | 与人工转录样张的编辑距离 | 按基准集设定，持续下降 | 回退解析器或升级 VLM 路由 |
| 表格 TEDS/TEDS-S | 表格结构相似度 | 按基准集设定 | 表格人工复核 |
| 公式 CDM | 公式识别正确率 | 核心公式 ≥90% | 公式补位解析 |
| 阅读顺序 NED | 与人工阅读顺序对比 | 按基准集设定 | 重新版面分析 |
| 页码对齐 | 物理页/印刷页映射 | 抽查 20 段全对 | 修正页码基准 |
| 可回取原文 | char 区间重建与原文逐字比对 | 20/20 | 修正 char 连续化 |
| 答案区漏判 | 答案被判为讲解 | 0 | 阻断入库 |

**整册失败，不部分入库**：半本教材入库会把数据缺失伪装成知识边界，学生无法区分“这本书没有”和“没解析出来”。

---

## 5. 切分策略

### 5.1 三个原则

1. **结构优先，语义兜底**：有标题、题号、答案区、表格、公式时，按结构边界切；结构不可得时才递归按句/段切。
2. **知识点是语义单元**：一个知识点的完整讲解，即定义 + 适用条件 + 公式 + 典型例题 + 易错点，应作为一个父块；子块负责精确召回。
3. **答案原子化**：题干、提示、答案分角色；答案块永不与讲解块合并。

语义切分不是默认答案。已有反证表明，纯语义漂移切分可能产生过短碎片并降低端到端准确率。任何切分策略变化都必须通过自有评测集比较 MRR/nDCG/Recall 后再决定是否切换。

### 5.2 父子块模型

| 层级 | 粒度 | 用途 | 建议大小 |
|---|---|---|---|
| 知识点层 | 版本无关的知识点 ID、规范名、别名、先修关系 | 知识点补全、诊断先修缺口 | 结构化记录 |
| 父块 | 小节/知识点讲解，含完整上下文 | 送给 LLM 阅读与引用展示 | 500～1000 字或 512～1024 token |
| 子块 | 定义、公式、例题、习题、提示、表格、图注 | 计算 embedding、精确召回 | 200～300 字或 128～256 token |

检索命中子块后返回父块，是教材场景性价比最高的做法：小块保证精度，大块保证上下文完整。

### 5.3 具体切分规则

| 内容类型 | 切分边界 | 角色 | 是否可拆 | 备注 |
|---|---|---|---|---|
| 章/节标题 | 标题开新父块 | EXPLANATION | 否 | 章节路径按层级截断推进 |
| 定义/定理 | 定义 + 适用条件 + 公式同块 | EXPLANATION | 否 | 拆开会导致“看起来正确但前提缺失” |
| 公式 | 多行公式、矩阵、对齐环境为原子 | EXPLANATION / 答案类随上下文 | 否 | LaTeX + 自然语言双写 |
| 例题 | 题干与解题过程同父块；答案过程按角色拆子块 | EXAMPLE / EXAMPLE_SOLUTION | 过程可按句拆，答案不混入讲解 | 讲解模式不召回答案子块 |
| 习题 | 一题一子块，题号/选项完整 | EXERCISE | 否 | 题号必须保留或继承 |
| 习题答案 | 一题一答案子块，回指题号 | EXERCISE_ANSWER | 否 | 仅核对模式可见 |
| 提示 | 给方向不给结果 | EXERCISE_HINT | 否 | 无稳定来源时不要编造 |
| 表格 | ≤1500 token 整体；超长按行切并重复表头 | 随上下文 | 有限拆 | 标记 `table_part` |
| 图片说明 | 图注 + VLM 描述 + 原文段落绑定 | 随上下文 | 否 | bbox 保留 |
| 词表/附录 | 按 Unit/Starter 逻辑单位 | EXPLANATION | 否 | 续页继承，中英双形标题 |
| 页眉页脚/版权页 | 丢弃 | BOILERPLATE | 不适用 | 按内容判噪 |

### 5.4 参数起点

这些是起点，不是最终值；必须用黄金集做 128/256/512 三档对比。

| 参数 | 建议起点 | 说明 |
|---|---|---|
| 子块 `maxTokens` | 256 | 精确问答常用；数学定义可放宽到 384 |
| 父块 `maxTokens` | 1024 | 覆盖完整讲解与例题上下文 |
| `overlapRatio` | 0.10～0.15 | 固定窗口兜底时使用；结构块不建议重叠 |
| 表格上限 | 1500 token | 超过按行切并重复表头 |
| 最小块 | 64 token | 过小碎片合并到同标题邻居 |
| 角色传播 | 最严优先 | 覆盖块中任一答案块 ⇒ chunk 角色取答案类 |
| 切分确定性 | 同输入同输出 | 否则 `chunk_id` 不可复现，幂等失效 |

### 5.5 与现有双策略的关系

- `HEADING_HIERARCHY`：纯 Java、确定性、默认策略；适合小初教材的结构化切分。
- `HYBRID_CHUNKER`：经 Python 分块桥调用 Docling HybridChunker；必须返回 `item_refs` 与 `char_spans`，并做角色回填。
- Hybrid 桥不可用时必须 fail-closed，不得静默回退 heading；否则切分留痕失真，A/B 评测不可比。
- 改切分规则重灌时，必须用 `ON CONFLICT DO UPDATE` 并清空 `text_jieba`，否则 `ChunkIndexer` 不会重新嵌入，表现为“改了脚本但检索没变”。
- `chunk_policy` / `chunk_policy_config` 必须写入 `rag_ingest_job` 与 `rag_document`。

---

## 6. Embedding 与索引

### 6.1 Embedding 输入模板

不要直接对裸 chunk 文本做 embedding。推荐统一模板：

```
[教材: {publisher}-{grade}-{term}-{revisionCode}]
[章节: {sectionPath}]
[位置: P{pageStart}-P{pageEnd} / 题号 {exerciseNo}]
[类型: {blockType} / 角色: {role}]
[知识点: {canonicalName}; 别名: {aliases}]

{chunkText}
{formulaNaturalLanguage}
{captionOrTableSummary}
```

约束：

- 模板中只放教材元数据和内容语义，不放学生姓名、学号、班级、成绩。
- 总长度必须小于模型有效窗口，超出时按父块/子块层次截断，不能静默截断到前几段。
- 公式、表格、图注的自然语言描述要与原文绑定，不能单独生成后丢失锚点。
- 同一份语料的模板必须版本化，写入 `chunk_policy_config` 或独立配置字段。

### 6.2 模型选型

| 候选 | 特点 | 适用 |
|---|---|---|
| BGE-M3 | 1024 维、8192 token、dense + sparse + multi-vector，中文与多语言主流开源选择 | 私有化首选，适合教材术语、公式符号、别名混合检索 |
| Qwen3-Embedding | 32k 上下文、支持 MRL，0.6B/4B/8B 多档 | 项目本地实测 8B 返回 4096 维，与 `halfvec(4096)` 匹配 |
| GTE/多语言 E5 | 多语言检索常见，生态成熟 | 作为对照基准 |
| OpenAI/Cohere 商业模型 | 质量稳定，部分支持多模态与灵活维度 | 需数据出域评估，不作为默认 |

建议：

- 默认走私有化模型；先用 BGE-M3 或 Qwen3-Embedding 在自有黄金集上对比，再定选型。
- 同一索引代际内不混用模型；换模型必须通过 `rag_index_generation` 建新代际，蓝绿切换。
- `rag_chunk_embedding.embedding` 保持 `halfvec(4096)`；不硬降维、不新增 HNSW。
- 启动时自检模型维度与数据库列类型是否一致；不一致拒绝启动。
- 占位嵌入只允许本地链路验证，生产环境由 `LocalPortGate` 拒绝。

### 6.3 混合检索衔接

预处理阶段应为检索侧准备好三类信号：

1. **稠密语义**：父子块的 embedding，承载自然语言与公式语义描述。
2. **稀疏/词法**：`ChineseTokenizer` 的 unigram + bigram 写入 `text_jieba`，保留题号、公式符号、版本术语、别名。
3. **结构化过滤**：五轴版本、角色、知识点、难度、认知层次、租户/作用域。

检索侧继续沿用项目红线：

- `RRF k=60` 不动。
- 排序键 `fusionScore` 与门禁键“相关性分”分离不动。
- 分数门禁 `0.38` 不下调。
- 六轴准入谓词不动。
- 不在不可达的 `agent-infra` 草稿检索引擎上施力。

如果后续要做词法通道归一或 rerank，必须作为 R-2 独立灰度项，并以 R-3 评测集为前置；本方案不夹带修改。

---

## 7. 元数据设计

### 7.1 必须字段

| 字段组 | 字段 | 现有落点 |
|---|---|---|
| 来源与租户 | `tenant_id`、`source_id`、`document_id`、`scope`、`trust_tier`、`license_status`、`ver_status` | `rag_source` / `rag_document` |
| 版本五轴 | `subject`、`grade`、`term`、`publisher`、`revision_code` | `rag_edition` |
| 位置 | `page_number`、`page_start`、`page_end`、`section_path`、`exercise_no`、`exercise_ref`、`char_start/end` | `rag_chunk` |
| 内容类型 | `role`、`block_type`、`in_answer_zone`、`matched_prefix`、`has_formula` | `rag_chunk` / 增量字段 |
| 教学语义 | `kp_ids`、别名、先修关系、难度、认知层次 | `rag_knowledge_point` / `rag_chunk` |
| 切分留痕 | `chunk_policy`、`chunk_policy_config`、`parent_id`、`chunk_level` | `rag_ingest_job` / `rag_document` / 增量字段 |
| 嵌入留痕 | `embed_model`、`dimension`、`index_generation`、`content_hash` | `rag_chunk_embedding` / `rag_index_generation` |
| 解析留痕 | `parser_version`、后端路由原因、低置信告警 | `rag_document` / ParseTrace |
| 可回取 | `bbox`、原页缓存引用 | 建议新增或落对象存储指针 |

### 7.2 新增字段纪律

- 优先复用现有列；确需新增时走增量 SQL，禁止直接改 01 终态 SQL 而不迁移存量库。
- 新表或新列涉及租户数据时必须挂 RLS 或走显式租户谓词，并同步角色授权。
- `rag_chunk_embedding.content_hash` 建议落地：向量对应的文本指纹不匹配时必须重算。
- 旧版教材不物理删除；通过 `superseded_by` / `retrievable=false` / 代际 RETIRED 软失效。

---

## 8. 质量门禁与评测

### 8.1 四层评测

| 层级 | 评什么 | 指标 | 工具/数据 |
|---|---|---|---|
| 解析层 | PDF 是否被正确理解 | NED、TEDS、CDM、阅读顺序 NED、页码对齐 | 自建教材样张 + OmniDocBench 口径 |
| 切分层 | 语义单元是否完整、角色是否正确 | 不变式通过率、泄题漏判、char 回取成功率、确定性 | `IngestInvariantEvidenceTest` + `Docling*Test` |
| 检索层 | 相关内容是否被召回 | Recall@K、MRR、NDCG、误拒率、漏拒率、按 channel 召回 | RAG-D08 `rag-evaluation` 黄金集 |
| 生成层 | 回答是否忠实、可引用 | Faithfulness、Answer Relevancy、引用准确率、拒答率 | RAGAS + 人工复评 |

### 8.2 黄金集建议

- 正例 ≥200 条：覆盖定义、公式、例题、习题、词表、表格、图示、跨版本对比。
- 负例 ≥100 条：不应召回答案、不应跨版本、不应跨租户。
- 应拒答 ≥50 条：教材中没有、版本不匹配、答案不可见。
- 小学与初中分别统计；英语词表、数学公式、理化图示单独分组。
- RAGAS 绝对分数跨集合不可比，只用于同集合前后对比。

### 8.3 人工抽检

| 抽检项 | 抽样比例 | 合格线 |
|---|---|---|
| 页码/章节/题号 | 每册随机 20 段 | 100% |
| 答案区识别 | 每册随机 20 段 | 准确率 ≥95%，漏判 0 |
| 公式完整率 | 每册随机 20 段 | ≥90% |
| 核心图描述 | 每册随机 10 图 | ≥90% |
| 知识点挂接 | 每册随机 20 段 | ≥90% |
| 版本坐标 | 每册随机 10 段 | 100% |

### 8.4 反证纪律

每个门禁必须回答：**撤掉修复点会红吗？**

示例：

- 把答案区内容改判为 `EXPLANATION` ⇒ 讲解模式泄题用例必须转红。
- 把附录按页切而不是按 Unit 切 ⇒ “第五单元单词”查询必须转红。
- 清空 `charStart/charEnd` ⇒ 原文回取用例必须转红。
- 把 Hybrid 桥失败改为静默回退 heading ⇒ 切分留痕一致性用例必须转红。
- 把 `text_jieba` 不清空就重灌 ⇒ 检索结果不变，陈旧向量用例必须转红。

只有静态守卫变红而行为测试仍绿的门禁，视为无效。

---

## 9. 增量更新与版本治理

1. 新增 `rag_edition` 行表示新版教材，旧版保留可查。
2. 载体增加 `revision_no`、`superseded_by`；发布修订版时旧 chunk 置 `retrievable=false`，不物理删除。
3. `content_hash` 作为文档/片段/向量的一致性依据；文本变了但向量没变即判陈旧。
4. 换解析后端、换切分策略、换 embedding 模型，均需新代际或新 job，并保留旧产物用于回滚与对比。
5. 重灌必须 `ON CONFLICT DO UPDATE` 且清空 `text_jieba`；需要 UPDATE 授权与 SchemaDriftIT。
6. 旧代际 RETIRED 后仍保留一段时间，用于审计与回滚；最终清理走受控通道，不提供物理删除端点。

---

## 10. 落地路线图

### 阶段 P0：基线与评测先行

- 选 3～5 册代表性教材：小学语文、小学数学、初中数学、初中英语、含表格/图示的理化讲义。
- 抽 50～100 页做解析基准，比较 Docling、MinerU、轻量文本通道；记录 NED/TEDS/CDM/阅读顺序/耗时。
- 建 100～300 条黄金问答集，跑出 baseline。
- 验收：每次改动只能动一个变量，且指标有前后对比。

### 阶段 P1：PDF 分级解析与中间格式

- 接入页级分类路由；复杂页升级 VLM/商业补位。
- 固化 Markdown + JSON + 原页缓存中间产物。
- 落实页码、阅读顺序、char 区间、章节路径、跨页表、公式 LaTeX。
- 验收：解析门禁通过；可回取原文 20/20；答案区漏判 0。

### 阶段 P2：教学语义与父子块

- 知识点挂接、别名、先修关系、难度/认知层次入库。
- 默认 `HEADING_HIERARCHY`，按 §5.3 规则切父子块。
- Hybrid 作为对照策略，必须完成角色回填与 fail-closed。
- 验收：泄题反证通过；父子块覆盖完整；重灌后检索结果可解释。

### 阶段 P3：Embedding 与代际

- 统一 embedding 输入模板；配置与 DB 列自检。
- 用 BGE-M3 / Qwen3-Embedding 在黄金集上对比。
- 换模型走 `rag_index_generation` 蓝绿，保持 4096/halfvec。
- 验收：分数非常量；0.38 门禁生效；新代际切换不影响旧代际回滚。

### 阶段 P4：灰度与上线

- 单租户、单学科灰度；先英语词表与数学公式两类高风险样本。
- 在线采样低分 trace，每周人工复评，kappa ≥0.6 视为校准通过。
- 不达标不扩大灰度。

### 阶段 P5：持续治理

- 改版教材、勘误、答案册发布均走版本软失效。
- 评测集持续从审计摘要与低分样本回流。
- 规模化了再评估 MRL/二进制量化，不提前改 DDL 或建 HNSW。

---

## 11. 不可触碰红线清单

| 红线 | 本方案的处理 |
|---|---|
| 分数门禁 0.38 不下调 | 保持；预处理只提升输入质量，不改门禁 |
| RRF k=60 不动 | 保持；切分/embedding 变化不夹带融合参数变化 |
| 排序键与门禁键分离 | 保持；不把 RRF 分接门禁 |
| 六轴准入谓词不动 | 保持；预处理阶段只负责让事实字段齐全 |
| 不在 agent-infra 草稿施力 | 保持；生产真源仍是 rag 模块 `JdbcRetrievalRepository` |
| 不硬降维/不建 HNSW | 当前活库保持 `halfvec(4096)`；4096 是部署事实、非框架硬编码，换维须独立代际 + 列改造 + 重算重评，不夹带；规模化走 MRL/量化代际 |
| 不用占位嵌入伪装生产 | 生产必须真实 embedding；占位仅本地验证且 fail-closed |
| 不物理删除 | 改版/重灌走软失效与代际 RETIRED |
| 不把 PII 拼进 Prompt | 解析前脱敏；学号扫描不可豁免 |
| 不静默降级后端 | 解析/切分/嵌入任一失败即 job FAILED |
| 不用 API 级 PASS 替代业务链路验收 | 每阶段必须有角色×任务的真实检索用例 |

---

## 12. 业界参考依据

以下资料用于确认主流方向；其中性能数字来自不同版本与不同基准，不能跨表直接比较，必须以自有样本复测为准。

1. MinerU2.5: A Decoupled Vision-Language Model for Efficient High-Resolution Document Parsing, ACL 2026 Industry Track  
   https://aclanthology.org/2026.acl-industry.3.pdf
2. TextIn 教育文档解析与知识库建设解决方案  
   https://www.textin.com/solutions/education-document-solution
3. TextIn 教育文档智能解析引擎  
   https://www.textin.com/scenario/education
4. Best Document Parsing Tools in 2026, Mixpeek  
   https://mixpeek.com/curated-lists/best-document-parsing-tools
5. RAG Chunking Strategies That Actually Work in 2026, Viqus  
   https://viqus.ai/blog/rag-chunking-strategies-2026
6. Chunking Strategies for RAG: A Complete Guide for 2026, Atlan  
   https://atlan.com/know/chunking-strategies-rag
7. 阿里云文本向量服务文档（Qwen3/GTE/text-embedding-v4 维度与上下文）  
   https://help.aliyun.com/zh/document_detail/2788421.html
8. 火山引擎 VectorDB Embedding 文档（BGE-M3 dense+sparse、8192 token）  
   https://docs.volcengine.com/docs/vector-database-vikingdb/embedding-v2
9. A Semantic-Adaptive Hybrid Retrieval Framework for the Education Domain, ACM 2026  
   https://acm-prod.literatumonline.com/doi/10.1145/3839592.3839618
10. 教育行业大模型应用（教育 RAG 按知识点分块、版本管理、来源标注）  
    https://llmlearning-x.github.io/industry-llm-book/chapter-06/index.html
11. 企业级 RAG 数据管道架构设计与落地，袋鼠云  
    https://www.dtstack.com/zh-cn/blogs/rag-data-pipeline-architecture
12. Docling REST API 与 Chunking 概念页（项目现有分块桥的依据）  
    https://docling-project.github.io/docling/usage/api_server/rest_api/  
    https://docling-project.github.io/docling/concepts/chunking/
13. Mathpix OCR API Pricing（PDF 按页与批量按页价格）  
    https://mathpix.com/pricing/api
14. TextIn 智能文档解析市场页（套餐页单价口径）  
    http://www.textin.com/market/chager/pdf_to_markdown?cache=7733
15. TextIn 通用表格识别定价页  
    http://textin.com/market/detail/recognize_table_multipage
16. 2026 文档 OCR 选型横评（腾讯云开发者社区，公开计费口径汇总）  
    https://cloud.tencent.com/developer/article/2749304?policyId=1003
17. 阿里云百炼文本向量同步接口（v4/qwen3.7 维度与单价）  
    https://help.aliyun.com/en/model-studio/text-embedding-synchronous-api
18. OpenAI API Pricing（embedding 单价）  
    https://openai.com/api/pricing/
19. AutoDL 官网 GPU 零售价（4090/A100 等按小时口径）  
    https://www.autodl.com/

---

## 13. 方案选型细化：优缺点、配置矩阵、兜底与成本

> 本章把前面“做什么”细化成“选哪个、怎么配、失败怎么办、花多少钱”。  
> 口径说明：公开价格按 2026-11 前后公开页面采集，汇率按 1 USD ≈ 7.2 CNY 估算；仅限预算，结算以供应商账单为准。项目实测耗时来自 RAG-D26（MinerU 4.1.7 解析 162 页≈189 秒；Docling 分段解析 162 页≈8 分钟）。

### 13.1 选型总原则

| 原则 | 含义 | 反例 |
|---|---|---|
| 私有化优先，商业 API 补位 | 教材版权与未成年人数据敏感，默认不自发送出域 | 全量教材直接送云 OCR 换质量 |
| 页级路由，不整册一刀切 | 简单页走轻量通道，复杂页升级 | 162 页全部用最贵 VLM |
| 同一代际不混模型/策略 | 解析器、切分器、embedding 变化必须留痕并可回滚 | 主模型超时后静默换备用模型 |
| 失败是终态，不是降级 | 解析/切分/嵌入失败即 job FAILED 或隔离待处理 | 失败后返回空文档入库 |
| 先评测再切流 | 精度、成本、延迟三项同时比较 | 只看榜单数字换模型 |
| 成本算全链路 | 解析 + 重试 + 人工抽检 + 向量存储 + 重灌 | 只算 API 调用费 |

### 13.2 PDF 解析方案对比

| 方案 | 优点 | 缺点与风险 | 适用场景 | 输出形态 | 兜底/升级 | 成本口径 |
|---|---|---|---|---|---|---|
| 轻量文本通道（PyMuPDF/pdfplumber） | 极快、CPU、几乎零边际成本 | 公式乱码、表格拍平、双栏串行；不适合作为可检索语料主通道 | 封面、目录、纯文本讲义、dry-run 预览 | 纯文本/粗结构 | 检测到公式/表格/多栏即升级 | 约 0.2～2 元/千页（自建 CPU 摊销） |
| Docling 自托管 | 多格式、版面层级与表格较好；项目已有生产适配与 HybridChunker 桥 | 速度较慢；模糊扫描件依赖外部 OCR；API 无 chunk 端点 | 英语教材、讲义、通用教辅、结构清晰 PDF | DoclingDocument JSON + Markdown | 超时缩段 20→10 页；仍失败升级 MinerU | 项目实测 162 页≈8 分钟⇒1000 页≈0.82 h；4090 约 1.5 元/千页，A10 约 7.9 元/千页 |
| MinerU 4.x/2.5 自托管 | 版面分割准，公式→LaTeX，表格→HTML/LaTeX；项目已实测 4.1.7 | 需 GPU，环境较重；版本升级可能破坏兼容 | 数学/理化教材、公式密集、双栏论文式教辅 | middle_json + Markdown | 公式抽检 <90% 时仅对低置信页用 Mathpix 补位 | 项目实测 162 页≈189 秒⇒1000 页≈0.32 h；4090 约 0.6 元/千页，A10 约 3.1 元/千页 |
| PaddleOCR/PP-StructureV3 自托管 | 中文 OCR 强，扫描件友好，20 类版面标签可映射 BOILERPLATE | 项目仍是占位后端，无生产服务可测；公式弱于 MinerU | 扫描教材、拍照讲义、盖章/手写痕迹多的页面 | 版面块 + 文本 + bbox | 低置信页 300 dpi 重扫；仍低则人工复核 | 以 4090 同档估算约 1～5 元/千页，必须用自有样张压测 |
| TextIn xParse/智能文档解析 | 中文教育文档适配好，阅读顺序/表格/公式输出稳定，支持私有化 | 云 API 有数据出域；不同 SKU 价格差 30 倍以上；套餐额度与抽取额度共用 | 私有化不便时的批量建库、表格密集教辅 | Markdown/JSON + 页码 | 仅对低置信页调用；不替代本地解析留痕 | xParse 约 0.042 元/页起；通用表格 0.035～0.1 元/页；智能文档解析套餐页显示 1 千页档约 1.43 元/页、10 万页档约 1.2 元/页（非同一 SKU，需核价） |
| Mathpix | STEM/手写公式与化学结构最稳，输出 LaTeX/Mathpix Markdown | 云 API、按页/按图计费；有一次性开通费；默认留存 30 天需配置 opt-out | 极高精度公式、手写解答、化学结构补位 | LaTeX/Markdown/JSON | 仅补位，比例建议 ≤10% 页面 | v3/pdf 0.005 美元/页≈0.036 元/页；批量 files/v1 0.0015 美元/页≈0.011 元/页；一次性 19.99 美元开通费 + 29 美元测试额度 |
| 商业 VLM/OCR（Mistral OCR 4.1、LlamaParse、Reducto） | 视觉理解强，复杂版面/扫描件表现好 | 云 API、单价高、数据出域、供应商锁定 | POC 阶段验证解析上限；少量高价值页面 | Markdown/结构化 JSON + bbox | 不作为默认生产通道 | Mistral OCR 4.1 约 4 美元/千页（带标注 5 美元）≈29～36 元/千页；LlamaParse 第三方汇总约 0.003 美元/页≈0.022 元/页；Reducto 约 0.005 美元/页≈0.036 元/页 |

**结论**：默认生产组合是 **Docling（通用）+ MinerU（复杂）+ Paddle（扫描）** 的自托管三档路由；商业 API 只用于低置信页补位和 POC 对标。

### 13.3 PDF 解析配置档位

以下为建议配置模型，实施时映射到现有 `rag.premise.*`、`ChunkPolicyConfig` 与 ParseTrace；新增键须同步配置守卫与留痕。

| 档位 | 触发条件 | 主链 | 关键参数 | 质量门禁 | 失败兜底 |
|---|---|---|---|---|---|
| A 预览档 | 上传预览、dry-run | PyMuPDF | 不跑 OCR/公式；`retrievable=false` | 仅看文本层覆盖率 | 不加进正式索引 |
| B 默认教材档 | 原生 PDF、单/双栏、公式比例低 | Docling → 必要时 MinerU | 分段 20 页；`do_table_structure=true`；`do_pdf_heading_hierarchy=true`；dpi 200；overlap 0.10 | 页码/char 区间/章节路径/噪声 | 缩段到 10 页重试一次；再失败升级 MinerU |
| C 公式密集档 | 公式块占比 >15% 或含矩阵/化学式 | MinerU | 公式 LaTeX；公式自然语言双写；dpi 200；表格 HTML；低置信公式标记 | 公式 CDM ≥90%；题号 100% | 低置信页 Mathpix 补位（需审批），比例 ≤10% |
| D 扫描件档 | 文本层字符密度 <阈值 | Paddle/PP-StructureV3 | 预处理 deskew+denoise+binarize；dpi 300；OCR 置信 ≥0.80 | OCR 置信、阅读顺序、页码对齐 | 300 dpi 重扫一次；仍低则隔离人工复核 |
| E 答案/试卷档 | 答案册、试卷、含 Answer Key | Docling/MinerU + 严格角色 | 答案区取严；题号继承；题答配对；hybrid 角色回填 | 答案区漏判 0；讲解模式 leak 0 | 角色不确定一律判答案类；人工抽检 |

示意配置：

```yaml
rag:
  premise:
    route-profile: textbook-default
    parse-backend: docling
    profiles:
      textbook-default:
        primary: docling
        upgrade: mineru
        segmented-pages: 20
        dpi: 200
        table-structure: true
        heading-hierarchy: true
        overlap-ratio: 0.10
        on-fail: FAIL_JOB
      formula-dense:
        primary: mineru
        formula: latex
        formula-natural-language: true
        patch-backend: mathpix
        patch-page-ratio-max: 0.10
        data-egress: approval-required
      scan-ocr:
        primary: paddle-ppstructurev3
        preprocess: [deskew, denoise, binarize]
        dpi: 300
        ocr-confidence-min: 0.80
        on-low-confidence: QUARANTINE_FOR_REVIEW
      answer-key:
        primary: docling
        strict-answer-role: true
        question-answer-pairing: true
        hybrid-role-backfill: required
```

### 13.4 兜底与升级矩阵

| 失败场景 | 一级兜底 | 二级兜底 | 三级兜底 | 禁止事项 |
|---|---|---|---|---|
| 解析超时 | 同后端缩段 20→10 页重试一次 | 切 MinerU 重跑该页段 | job FAILED，整册不入库 | 不得返回空文档 |
| OCR 置信 <0.80 | 300 dpi 重扫 | Paddle 二次 OCR | 隔离人工复核，`retrievable=false` | 不得把低置信文本入库 |
| 公式 CDM <90% | MinerU 重解析 | Mathpix 仅补低置信页（审批） | 人工录入 LaTeX | 不得用近似文字冒充公式 |
| 表格 TEDS 不达标 | 换版面后端重解析 | 表格人工确认 | 表格降级为图片 + caption，不进结构化检索 | 不得把表格拍平成乱序文本 |
| 答案区角色不确定 | 取严判答案类 | 人工抽检 | 该册暂停入库 | 不得判为 EXPLANATION |
| Hybrid 桥不可用 | 检查版本/DoclingDocument 一致性 | 修复后重跑 | job FAILED | 不得静默回退 heading |
| Embedding 主模型不可用 | 等待/重试 | 显式切换到已建好的另一代际 | job FAILED | 不得同代际静默换模型 |
| 词法/向量通道分数异常 | 检查 `text_jieba` 与维度自检 | 重建代际 | 回滚到上一 ACTIVE 代际 | 不得调低 0.38 门禁 |

**关键纪律**：任何兜底都必须写回 ParseTrace / `chunk_policy` / `embed_model` / `index_generation`。没有留痕的兜底等于把数据质量变化伪装成“模型波动”。

### 13.5 切分方案对比与配置

| 方案 | 优点 | 缺点 | 适用 | 成本 | 兜底建议 |
|---|---|---|---|---|---|
| 固定窗口 + overlap | 简单、稳定、可并行 | 切散定义与条件、题干选项、表头与数据 | 预览、纯叙述兜底 | 极低 | 仅结构不可得时启用，不默认 |
| 递归字符切分 | 按段/句边界，优于固定窗口 | 无教学语义，答案与讲解仍可能同块 | 通用讲义 | 低 | 作为结构解析失败后的第二兜底 |
| `HEADING_HIERARCHY` | 确定性、结构对齐、项目默认、易单测 | 依赖标题层级质量；附录/词表需特判 | 小初教材主路径 | 低（纯 Java） | 附录按 Unit 逻辑单位、续页继承 |
| `HYBRID_CHUNKER` | token 预算与结构融合，长块处理更均匀 | 不带角色、需 Python 桥、版本需对齐、无原生重叠 | 对照策略、长讲解段 | 中（多一服务） | 桥失败 fail-closed；角色必须回填 |
| 语义切分 / Late Chunking / Contextual Retrieval | 上下文更完整，长文档表现好 | 索引成本高、需长上下文模型、可能破坏版本/角色边界；项目尚无评测证据 | 实验分支 | 高（2～14 倍嵌入调用） | 不进默认生产，先做 A/B |
| 父子块 + 知识点语义单元 | 兼顾召回精度与上下文完整，天然适配教材 | 实现复杂度较高，需维护 parent_id | 推荐默认 | 中 | 子块检索、父块送 LLM |

配置档位：

| Profile | 子块 maxTokens | 父块 maxTokens | overlap | 特殊规则 |
|---|---:|---:|---:|---|
| `textbook-explain` | 256 | 1024 | 0.10 | 定义/条件/公式同块 |
| `exercise-set` | 192 | 768 | 0.05 | 一题一子块，答案独立答案类 |
| `wordlist-appendix` | 256 | 按 Unit | 0 | 按 Unit/Starter 逻辑单位，中英双形标题 |
| `table-heavy` | 表格 ≤1500 | 1024 | 0 | 超长按行切并重复表头 |
| `formula-dense` | 384 | 1024 | 0 | 多行公式原子化，公式自然语言双写 |

### 13.6 Embedding 方案对比与配置

| 方案 | 维度/上下文 | 优点 | 缺点 | 成本 | 与项目 DDL 的兼容性 |
|---|---|---|---|---|---|
| BGE-M3 自托管 | 1024 / 8192 token，dense+sparse | 中文与多语言主流，可混合检索，无数据出域 | 需 GPU 服务；项目当前词法通道是 `ChineseTokenizer`，sparse 接入需独立改造 | GPU 摊销；示意 4090 按 5k token/s，1000 万 token≈0.56 h≈1.1 元 | 1024 维与 `halfvec(4096)` 不匹配，需新代际/列改造，不夹带 |
| Qwen3-Embedding 8B 自托管 | 4096 / 32k token，MRL | 项目本地实测返回 4096 维，与 DDL 正好匹配；长上下文 | 显存与吞吐成本更高 | 1000 万 token 按 2k token/s≈1.39 h≈2.6 元（4090 示意） | 兼容，推荐默认 |
| 阿里云 text-embedding-v4 / qwen3.7 | v4 最高 2048 维；qwen3.7 最高 2560 维 | 单价低、零运维、国内可用 | 数据出域；最高维度不足 4096 | v4 标准 0.0005 元/千 token≈0.5 元/百万；批量 0.25 元/百万；qwen3.7-flash 0.125 元/百万 | 不匹配 `halfvec(4096)`，只能用于评估或独立代际 |
| OpenAI text-embedding-3-small/large | 1536/3072，8k token | 生态成熟，质量稳定 | 数据出域；维度不匹配 DDL | small 0.02 美元/百万≈0.144 元/百万；large 0.13 美元/百万≈0.936 元/百万；批量半价 | 不匹配，不进当前代际 |
| 本地确定性占位 | 1024 维 bigram 哈希 | 零成本，验证链路可用 | 分数近常量，生产无意义；`LocalPortGate` 拦截 | 0 | 仅本地验证，生产 fail-closed |

**推荐配置**：

```yaml
rag:
  embedding:
    primary: qwen3-embedding-8b
    dimension: 4096
    vector-type: halfvec
    index-generation: auto
    input-template-version: v1
    on-primary-unavailable: FAIL_JOB
    allow-silent-model-switch: false
```

**维度纪律（三层含义，不要混为一谈）**：

1. **当前生产活库：入库向量必须 4096 维。** `rag_chunk_embedding.embedding` 是 `public.halfvec(4096) NOT NULL`，pgvector 的 typmod 会拒绝非 4096 维写入；启动自检 `RagStartupSelfCheck` 还会比对「配置声明的 vector-type/dimension vs 库实际列」，不一致直接 fail-fast。
2. **框架/代码：不硬编码 4096。** 自检只要求「配置 = 库列」，并不写死 4096——IT 探针库就是 `vector(1024)` + 1024 维占位嵌入，两种形态都合法。所以「必须 4096」是**当前活库的部署事实**，不是代码层不可改的常量。
3. **模型选型：不要求模型原生 4096，但必须走正规换维路径。** 1024/2048/2560/3072 维模型可用于离线评估或独立代际；要进生产，必须同步改库列类型/维度、改 `rag.embedding.vector-type/dimension`、新建 `rag_index_generation` 代际并蓝绿重建全部向量，且在既有 0.38 门禁下重跑评测（不调阈值）。该路径的决策流程、自检、双写蓝绿 SOP 与反证验收见 **§14 维度工程化治理**。

**关于零填充的精确表述**：本项目稠密检索用 cosine（`<=>`），对已归一化向量补零**在数学上不改变相似度与排序**，所以问题不是「分数算错」，而是**留痕与声明失真**——把 1024/2048 维模型说成 4096 维，掩盖模型真实能力、让代际 A/B 不可比、也无法利用高维容量。因此纪律是「禁止用零填充伪装成 4096 维代际」，而非「零填充必然算错」。

### 13.7 成本模型

#### 13.7.1 单位成本公式

- 解析：`页数 × 单页耗时 ÷ 3600 × GPU 单价 + 重试次数 × 同上`
- Embedding：`token 数 ÷ 1,000,000 × 百万 token 单价`；自托管则 `token 数 ÷ 吞吐(token/s) ÷ 3600 × GPU 单价`
- 向量存储：`chunk 数 × 维度 × 2 bytes(halfvec) × 副本/索引/备份系数`
- 人工：`抽样段数 × 单段核查分钟 ÷ 60 × 人工小时成本`
- 总拥有成本：`解析 + 重试 + 切分 + embedding + 存储 + 人工抽检 + 运维摊销`

#### 13.7.2 示例：100 册 × 200 页 = 20,000 页

假设平均 6 子块/页 = 120,000 chunks，每块 300 token，合计 36,000,000 token（3600 万）。

| 项目 | 自建 Docling | 自建 MinerU | TextIn xParse | Mathpix 仅 10% 公式页补位 | 阿里云 v4 | OpenAI small |
|---|---:|---:|---:|---:|---:|---:|
| 解析（20,000 页） | 16.5 h × 1.88≈31 元（4090）；A10≈157 元 | 6.5 h × 1.88≈12 元（4090）；A10≈62 元 | 0.042 元/页≈840 元；智能解析套餐另按 SKU 核价 | 2,000 页 × 0.011≈22 元 + 开通费≈144 元 | 不适用 | 不适用 |
| Embedding（3600 万 token） | 自托管约 4～10 元（Qwen3 8B，按 2k～5k token/s、4090 示意） | 同左 | 同左 | 同左 | 标准 18 元 / 批量 9 元 | 标准约 5.2 元 / 批量约 2.6 元 |
| 向量存储（120k × 4096 halfvec） | 原始约 0.92 GiB，2～3 倍后约 1.8～2.7 GiB | 同左 | 同左 | 同左 | 维度不匹配当前 DDL | 维度不匹配当前 DDL |
| 人工抽检（100 册 × 20 段 ×30 秒） | 约 16.7 h ×100 元≈1,670 元 | 同左 | 同左 | 同左 | 同左 | 同左 |
| 合计示意 | 约 1,700～1,900 元 + 运维 | 约 1,680～1,800 元 + 运维 | 约 2,500～3,500 元 + 运维 | 约 1,850～2,000 元 + 运维 | 约 1,690～1,710 元，但需解决维度 | 约 1,680～1,700 元，但需解决维度 |

**成本结论**：

1. 解析与 embedding 的 API/算力费用并非最大头，**人工抽检与评测标注通常才是主要可变成本**。
2. 自建解析边际成本最低（千页级几元到几十元），但需要 GPU 服务、模型版本管理和值班运维；这些固定成本没有计入上表。
3. 商业解析 API 适合 POC 和低置信补位；全量使用时要特别核对 SKU，TextIn xParse 与智能文档解析套餐价格相差一个数量级以上。
4. 重灌成本要乘以失败率。把解析门禁前置，比事后重切、重嵌、重评更省钱。
5. 在当前 `halfvec(4096)` 红线不变的前提下，云 embedding API 因维度不匹配不可直接落库；“便宜但不能用”不是有效方案。

### 13.8 决策树

```text
输入 PDF
├─ 文本层稀疏/扫描件？
│   └─ 是 → D 扫描件档（Paddle 300dpi）→ 置信不足 → 人工复核
├─ 公式或表格密集？
│   └─ 是 → C 公式密集档（MinerU）→ 公式抽检 <90% → Mathpix 补位（审批）
├─ 答案册/试卷？
│   └─ 是 → E 答案档（严格角色 + 题答配对）→ 角色不确定 → 取严
└─ 其余 → B 默认教材档（Docling）→ 超时/质量差 → 升级 MinerU → 仍失败 → FAILED

切分：
├─ 结构可得 → HEADING_HIERARCHY + 父子块
├─ 需要对照 → HYBRID_CHUNKER（角色回填 + 桥失败 FAILED）
└─ 结构不可得 → 递归切分 + overlap（仅兜底）

Embedding：
└─ qwen3-embedding-8b / 4096 / halfvec → 代际蓝绿 → 维度自检失败则拒绝启动
```

### 13.9 推荐组合与不推荐组合

| 组合 | 评价 |
|---|---|
| Docling/MinerU 自托管 + Heading 父子块 + Qwen3-Embedding 8B 4096 | 推荐默认：合规、维度匹配、成本可控、可回滚 |
| MinerU + Mathpix 仅公式补位 + Qwen3 8B | 推荐用于数学/理化高风险册，需数据出域审批 |
| TextIn 全量解析 + 云 embedding API | 不推荐作为生产默认：数据出域、SKU 报价差异大、维度与 DDL 不匹配 |
| 固定窗口切分 + 云 small embedding | 不推荐：切散教学语义，且维度不匹配 |
| 语义切分/Late Chunking 直接替换默认策略 | 暂不推荐：无自有评测证据，且可能破坏角色与版本边界 |
| HybridChunker 静默回退 heading | 禁止：留痕失真，A/B 不可比 |

---

## 14. Embedding 维度工程化：决策、自检、迁移与治理

> 本章把散落在 §6、§13.6、§13.7 的「维度」讨论收拢成一条工程契约。**维度不是一个模型参数，而是贯穿「模型输出 → 配置声明 → 库列类型 → 索引可行性 → 检索分数 → 评测阈值 → 存储成本」的跨层约束。** 任何一层单独改动，都会让其他层静默失真。

### 14.1 维度的跨层契约模型

| 层 | 谁决定 | 失配后果 | 工程防线（已有 / 待补） |
|---|---|---|---|
| 模型输出 | embedding 模型与 MRL 截断档 | 输出维度 ≠ 配置维度，端口直接抛错 | 已有：`OpenAiCompatibleEmbeddingPortTest` 校验「期望 4096 维」 |
| 配置声明 | `rag.embedding.dimension` + `vector-type` | 声明与库列不一致时启动期无异常，直到首次写入/检索才报 PG cast 错误 | 已有：`RagStartupSelfCheck.checkVectorColumn`；待补：失败信息直接给出修复指引 |
| 库列 | `rag_chunk_embedding.embedding public.halfvec(4096) NOT NULL` | typmod 拒绝非 4096 维写入 | 已有：DDL 强约束 + `04_migrate_embedding_halfvec_4096.sql` |
| 索引 | pgvector 距离算子 / ANN 可行性 | 4096 维下 HNSW 不可建，稠密检索退化为精确扫描 | 已有：`VectorColumn.indexable()` 恒 false；**刻意取舍，不得自行加索引** |
| 检索分数 | cosine `1 - (e.embedding <=> query)` | 未归一化 → 分数尺度漂移 → 0.38 门禁失效 | 已有：cosine 算子；待补：输入侧强制 normalize 的运行时断言 |
| 评测门禁 | 0.38 / RRF k=60 / 排序键与门禁键分离 | 换维后分数分布变化，若顺手调阈值则 A/B 不可比 | 已有：门禁不可调；待补：换维专项反证用例 |
| 存储成本 | 维度 × 每维字节 × chunk 数 | 维度翻倍直接翻倍存储与带宽 | 已有：halfvec 已是 fp16；待补：量化优先于加维度的成本闸门 |

一句话纪律：**配置 → 库列 → 检索 → 评测是一条链；链上任一环单独改动，都不算完成。**

### 14.2 维度对效果的影响：四条机制

1. **信息容量**：维度决定向量能独立编码的特征槽位。过低会丢细粒度语义（如把「一元二次方程求根」与「二次函数图像」压成近邻）；但主流模型在 256–768 维已能保留全维 95%+ 的检索质量。
2. **边际收益递减**：OpenAI 公开口径中，3-large 在 256 维（MTEB 62.0）已超过上一代 ada-002 的 1536 维（61.0）；3-large 从 256 拉到 3072（12× 存储）MTEB 仅涨约 2.6 分。
3. **模型能力主导**：领域对比微调（1000–5000 组 query / 正例 / 难负例）常带来 5–15 点 nDCG@10，超过换任何两个商业模型的差距。维度是容器，不是语义本身。
4. **高维相似度集中 + MRL 前提**：语料小而维度高时，归一化向量两两余弦距离趋于集中，Top-K 区分度下降；现代 MRL 模型把核心语义前置到低维前缀，截断后**必须重新 L2 归一化**，否则分数漂移。

| 对比 | 维度变化 | 存储变化 | 效果变化（公开口径，不可跨 MTEB 版本横比） |
|---|---|---|---|
| ada-002 → 3-large | 1536 → 256 | 0.17× | MTEB 61.0 → 62.0，反而更高 |
| 3-large 256 → 3072 | 12× | 12× | MTEB 约 +2.6 |
| MRL 128–256 档 | — | — | 通常保留全维 95%+ 检索质量 |
| 领域微调 1000–5000 三元组 | 维度不变 | 0 | nDCG@10 +5～15 |

**小初场景的特殊风险**：教材语料含公式、表格、跨页题干等细粒度语义单元，降到 512 维以下时这些单元最先被压缩丢失，比通用语料更敏感。因此本项目的「可裁维度下限」必须用自有教材黄金集测定，**不得直接套用通用语料的 768/1024 结论**。

### 14.3 业界常用维度基线（2026 年 8–11 月公开口径）

| 阵营 | 模型 | 默认/常用维度 | 可选维度 |
|---|---|---|---|
| 闭源 API | OpenAI text-embedding-3-small | 1536 | MRL 可裁到 256/512/1024 |
| | OpenAI text-embedding-3-large | 3072 | MRL 可裁到 256～2048 |
| | Google Gemini Embedding | 3072 | MRL 可裁到 768/1536 |
| | Cohere Embed v4 | 1536（部分资料标 1024） | 256/512/1024 |
| | Voyage 4 系列 | 1024 | 最高 2048 |
| 国内 API | 阿里 text-embedding-v4 | 1024 | 64～2048 多档 |
| | 智谱 embedding-3 | 2048 | 256/512/1024/2048 |
| 开源自托管 | BGE-M3 | 1024 | 固定，不支持裁剪 |
| | Qwen3-Embedding-0.6B / 4B | 1024 | 32～4096 |
| | Qwen3-Embedding-8B | **4096** | 32～4096 |
| | Nomic Embed v2 / EmbeddingGemma | 768 | 可裁到 128 |

结论：**1024 与 1536 是事实上的主流默认；3072 是高端档；4096 属右尾**，原生输出者主要是 Qwen3-Embedding-8B、GTE-Qwen2-7B 等大参数开源模型。项目当前用的正是 Qwen3-8B，所以 4096 是「主流上限档」——不是异类，但也不是业界普遍选择。2026 年的关键变化是维度从硬选择变成可调参数，越来越多模型支持 MRL。

> 口径说明：个别模型（Cohere Embed v4、Qwen3-8B）在不同来源中标的维度有出入，以厂商官方文档为准；不同 MTEB 版本分数不可横向比较。

### 14.4 效果提升杠杆优先级

按性价比从高到低，**加维度排最后**：

1. **模型代际 / 领域微调** — 领域三元组对比微调，+5～15 nDCG@10。
2. **重排器** — Top-50 候选精排，常优于维度翻倍，且不加存储。
3. **量化** — fp16 / int8 降精度，存储 2～4×，近乎无损。
4. **加维度** — 256 → 3072 是 12× 存储，MTEB 仅约 +2.6。

工程含义：当检索效果不达标时，**先查解析与切分质量，再上重排与微调，最后才考虑动维度**。这与 §4/§5 的判断一致——小初教材的瓶颈在输入侧，不在向量长度。

### 14.5 工程化决策流程

```text
收到「维度/效果/成本」诉求
├─ 诉求是「检索效果不够」？
│   ├─ 先查解析与切分质量（§4/§5 门禁）→ 未达标 → 回输入侧，不动维度
│   ├─ 再上重排器 → 达标 → 收工，维度不动
│   ├─ 再做领域微调 → 达标 → 收工，维度不动
│   └─ 仍不达标 → 进换维决策（14.7 SOP）
├─ 诉求是「存储/延迟压力」？
│   ├─ 先量化（halfvec → int8）→ 达标 → 收工
│   └─ 再 MRL 裁维度 → 进换维决策（14.7 SOP）
└─ 诉求是「合规/数据出域」？
    └─ 换私有化模型 → 进换维决策（14.7 SOP）

换维决策的唯一出口：
新代际 → 双写 → 全量重算 → 0.38 门禁下重评（不调阈值）→ 蓝绿切流 → 观察期 → 旧代际 RETIRED
任何跳过「重评」或「新代际」的路径一律拒绝。
```

### 14.6 配置、自检与启动防线

**目标配置**（`index-generation` / `input-template-version` / `on-primary-unavailable` / `allow-silent-model-switch` 沿用 §13.6；其余为工程化建议项，需实现）：

```yaml
rag:
  embedding:
    primary: qwen3-embedding-8b
    model-id: qwen3-embedding-8b@2026-09      # 需实现：随向量落库
    dimension: 4096
    vector-type: halfvec
    normalize: true                            # 需实现：运行时断言
    real-output-dimension: 4096                # 需实现：MRL 截断/零填充时记录真实能力
    index-generation: auto
    input-template-version: v1
    on-primary-unavailable: FAIL_JOB
    allow-silent-model-switch: false
    dimension-self-check: FAIL_FAST            # 需实现：显式开关
    on-dimension-mismatch: REFUSE_START        # 需实现：与自检联动
```

**已有机制 vs 待补齐**：

| 工程能力 | 现有落点 | 状态 | 需补齐 |
|---|---|---|---|
| 配置声明维度 | `rag.embedding.dimension` / `vector-type` | 已有 | — |
| 启动期一致性自检 | `RagStartupSelfCheck.checkVectorColumn` | 已有 | 失败信息直接给出修复指引 |
| 端口维度校验 | `VectorColumn.requireDimension`、`OpenAiCompatibleEmbeddingPortTest` | 已有 | — |
| 列类型迁移 | `04_migrate_embedding_halfvec_4096.sql` | 已有 | 逆向脚本（4096 → 低维）与双写列方案 |
| 代际隔离 | `rag_index_generation` 蓝绿 | 已有 | 换维 SOP 固化为可执行的 job |
| 向量范数 / 分数分布监控 | 无 | 缺 | 新增指标（14.9） |
| 换维评测门禁 | 0.38 门禁已有 | 部分 | 维度变更专项反证用例（14.10） |

**启动期必须拒绝的三种错配**：

1. 配置 `vector/1024` + 库列 `halfvec(4096)` → 类型 + 维度两条都报。
2. 配置 `halfvec/1024` + 库列 `halfvec(4096)` → 维度不符。
3. 模型实际输出 1024 维 + 配置声明 4096 维 → 端口层拒绝，不得以零填充绕过。

### 14.7 换维迁移 SOP

> 前提：任何换维都是**新代际**，不是就地修改当前代际。

1. **决策记录**：在 `rag_index_generation` 建 NEW 代际，登记 `model-id` / `dimension` / `vector-type` / `input-template-version` / `content-hash 算法版本`。
2. **列改造**：优先**新增列双写**（如 `embedding_v2 halfvec(1024)`），旧列继续服务；禁止原地 `ALTER COLUMN TYPE` 导致长事务锁表与服务中断。
3. **双写**：新代际 ingest job 写新列，旧代际继续对外服务；两列并存期间按代际隔离读路径。
4. **全量重算**：确保所有 chunk 都有新向量，校验 count 一致、无 NULL、`content_hash` 与文本指纹匹配。
5. **评测门禁**：在既有 0.38 门禁下跑黄金集，对比 recall@5 / nDCG@10；不达标则 ABORT，不得通过调阈值挽救。
6. **蓝绿切流**：读路径切到新代际，观察期（建议 7 天）保留旧代际可秒级回切。
7. **收尾**：观察期通过后旧代际置 `RETIRED`（软失效，遵循不物理删除红线）；新列成为主列，旧列按数据保留策略处理。

**回滚**：任一步异常，读路径切回旧代际即可；双写期旧列持续可用，回滚不需要重算。

### 14.8 容量与成本工程

```text
单向量字节 = 维度 × 每维字节
halfvec(fp16) = 2 B/维；int8 = 1 B/维；binary = 1 bit/维
向量总容量 = chunk 数 × 单向量字节 × 副本/备份系数
```

以 12 万 chunk 为例（未计副本与索引）：

| 维度 | halfvec 原始容量 | 相对 4096 | 说明 |
|---|---:|---:|---|
| 4096 | 约 0.92 GiB | 1.0× | 当前项目形态 |
| 2048 | 约 0.46 GiB | 0.5× | 多数云 API 上限档 |
| 1024 | 约 0.23 GiB | 0.25× | 业界主流默认档 |
| 768 | 约 0.17 GiB | 0.19× | 轻量档 |

工程判断：**当前规模下即使降到 1024，绝对容量也只省不到 1 GiB，不足以单独构成换维理由**。换维的合法理由只有三类：模型质量、领域适配、合规/出域。量化（halfvec → int8 再省一半）优先于降维，且不改变向量空间语义。

### 14.9 运行时监控指标

| 指标 | 观测点 | 告警条件 | 处置 |
|---|---|---|---|
| 配置/库列一致性 | 启动自检 | 任何不一致 | 拒绝启动（已有） |
| 向量范数分布 | 写入前断言 + 定时抽样 | 归一化后范数偏离 1.0 超出阈值 | 定位模型/预处理变更，暂停该代际写入 |
| 相似度分分布 | 检索采样 | 换维后整体分布相对旧代际显著偏移 | 检查归一化与模型代际；**不得调 0.38 门禁** |
| 稠密召回占比 / 低分回流率 | 在线检索日志 | 相对基线显著下降 | 回黄金集复测，判断是否需重排/微调 |
| 代际流量比例 | 蓝绿切流 | NEW 代际流量异常或旧代际未归零 | 按 SOP 回滚或完成收尾 |

### 14.10 验收与反证清单

每个防线都必须回答「撤掉修复点会红吗」：

| 防线 | 反证构造 | 期望结果 |
|---|---|---|
| 启动自检 | 用 `vector/1024` 配置连 `halfvec(4096)` 库启动 | 启动失败，报类型 + 维度错配 |
| 库列 typmod | 向 `halfvec(4096)` 写 1024 维向量 | PG 拒绝写入 |
| 端口维度校验 | 模型返回 1024 维、配置声明 4096 维 | 端口抛「期望 4096 维」 |
| 零填充纪律 | 1024 维补零到 4096，比对 cosine 排序 | 排序不变（数学等价成立）；但代际记录必须标记 `real-output-dimension=1024` |
| MRL 归一化 | 截断到 256 维后不重新 L2 归一化 | 分数相对基线漂移，证明必须归一化 |
| 换维重评门禁 | 换维后跳过黄金集评测直接切流 | 专项用例应拦截，不允许切流 |

### 14.11 禁止事项

1. 禁止同代际静默换模型或维度。
2. 禁止用零填充伪装成原生 4096 维代际（数学上分数不变，但声明失真、代际 A/B 不可比）。
3. 禁止 MRL 截断后不重新 L2 归一化。
4. 禁止为省存储直接降维而不重评；禁止用调 0.38 阈值掩盖换维损失。
5. 禁止跨 MTEB 版本比较分数并据此决策。
6. 禁止占位嵌入进入生产；生产必须真实 embedding，占位仅本地验证且 fail-closed。
7. 禁用地原地 `ALTER COLUMN TYPE` 做在线换维；必须双写 + 蓝绿。
8. 禁止在 4096 维下自行新建 HNSW 索引——当前无 HNSW 是刻意取舍。

---

## 15. 一页检查清单

入库前逐项确认：

- [ ] 来源、版权、版本五轴、租户/作用域齐全。
- [ ] PDF 已分类路由，复杂页没有被轻量通道处理。
- [ ] 页眉页脚、版权页、噪声块已剥离。
- [ ] 页码基准、阅读顺序、字符区间、章节路径正确。
- [ ] 公式已 LaTeX 化并补自然语言描述。
- [ ] 表格结构保留，跨页表已合并，超长表重复表头。
- [ ] 核心图有描述并与原文绑定；装饰图已丢弃。
- [ ] 答案区角色取严，题号可回指；讲解模式不可见答案。
- [ ] 词表/附录按逻辑单位切，中英双形标题已补。
- [ ] 父子块边界符合语义单元；表格/公式/题目未被拆散。
- [ ] `chunk_policy`、`parser_version`、`embed_model`、`index_generation` 已留痕。
- [ ] embedding 输入模板无 PII、无页眉噪声、长度未静默截断。
- [ ] 配置 `dimension`/`vector-type` 与库列类型一致，启动自检通过（错配即拒绝启动）。
- [ ] 向量已 L2 归一化；若做 MRL 截断，截断后已重新归一化。
- [ ] 无零填充伪装维度；如确有截断/补位，`real-output-dimension` 已留痕。
- [ ] 换维走新代际 + 双写 + 重评，未调 0.38 阈值，回滚路径可用。
- [ ] `text_jieba` 与词法通道包含题号、公式符号、别名、版本术语。
- [ ] 解析/切分/检索/生成四层评测均有阈值与实测值。
- [ ] 撤掉关键修复点会使对应反证用例转红。
- [ ] 灰度、回滚、旧版软失效路径可操作。
