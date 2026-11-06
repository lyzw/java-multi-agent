# 中文教材 PDF 文档切分方案调研

> 目标语境：**中文教材 PDF → RAG / 向量检索的前置切分**。教材 PDF 的切分本质是"**版面/结构解析 → 结构化 → 按语义块切分**"，很少有工具只切分、不解析。

---

## 0. 检索说明（重要，先读）

- **`web_search` 工具在当前环境不可用**（缺少 `DEEPSEEK_API_KEY`）。因此本报告改用 `web_fetch` 直接抓取各方案的**官方文档 / 官方仓库**，保证"来源可核验、内容为一手资料"。
- 官方文档 HTML 抓取不返回 `Last-Modified` 头，因此下文"更新时间"统一给出**库版本号 / 文档页脚年份**（如 "MinerU 4.1，2024–2026"），而非精确到日；这比二手博客的"更新日期"更可靠。
- 内容区分**文本版 PDF** 与**扫描版 PDF** 两类场景，聚焦中文教材切分，不含代码实现。

---

## 1. 分方案对比

### 方案 A：MinerU（OpenDataLab / 上海 AI Lab）

| 字段 | 内容 |
|---|---|
| **1. 方案名称** | MinerU（PDF-Extract-Kit 演进，服务化为 mineru-kit / DocVortex） |
| **2. 切分依据** | 版面检测 → 阅读顺序恢复 → 按**语义块（block）**输出，每个块带类型（text/table/image/equation/caption 等）与几何位置；附中间 JSON/结构化内容，下游可再按块或页切分，保留稳定引用定位（citation locator） |
| **3. 适用 PDF 类型** | 文本版 ✅、扫描版 ✅（Basic/Standard/Advanced 档支持 OCR+VLM）；Office/EPUB/OFD 也支持 |
| **4. 对教材结构的处理** | 目录：V2 Content List 按页分组的章节层级；章节层级：标题类块 + 页码/块索引定位；图表：正文、图/表标题分离、表格转 Markdown/HTML、图可导出；公式：独立 equation 块（LaTeX/图片回退）；页眉页脚：版面模型单独识别为页眉/页脚类别；跨页：`continues_prev` 标记跨页表格；中英混排：输出 PDF 导出时对含 CJK 的段落做 CJK 换行，避免中文切成空格碎片 |
| **5. 优点** | 中文/中英混排打磨充分（上海 AI Lab 出品）；4 档解析覆盖从纯文本到复杂版式；结构化输出（9 种渲染目标）对"先解析后切分"最友好 |
| **6. 局限** | 重型依赖（需模型/VLM，Flash 以外档位吃显存）；目标是"高保真解析层"，**切分粒度需自己再包一层**；文档以英文为主、中文文档常滞后半拍 |
| **7. 来源链接** | [MinerU 中文主页](https://opendatalab.github.io/MinerU/zh/) · [输出文件格式](https://opendatalab.github.io/MinerU/reference/output_files/) · [技术报告 arXiv:2409.18839](https://arxiv.org/abs/2409.18839) — **MinerU 4.1（2024–2026）** |

> 标注：**针对中文教材 PDF 适配良好**（非"仅中文"，但对中文 PDF 是社区公认的第一梯队）。

---

### 方案 B：PaddleOCR PP-StructureV3（PaddlePaddle / 百度）

| 字段 | 内容 |
|---|---|
| **1. 方案名称** | PP-StructureV3 版面解析产线（含版面区域检测/表格/公式/图表/印章子产线） |
| **2. 切分依据** | 版面区域检测（PP-DocLayout）→ 元素分析（OCR/表格/公式/图表）→ 阅读顺序恢复 → 输出 Markdown/结构化 |
| **3. 适用 PDF 类型** | 扫描版 ✅（主战场，OCR 起家）；文本版 ✅；多栏报纸/杂志/试卷/课本/古籍/竖排 |
| **4. 对教材结构的处理** | 目录：版面检测**显式含"目录"类**；章节层级：段落标题/文档标题类；习题：训练集明确含"试卷、课本"场景；图表：图表解析 + 图表标题类；公式：公式识别子产线 + "公式编号"类；页眉页脚：版面模型**显式分类"页眉/页脚/页码/脚注"**；跨页：多栏阅读顺序恢复；中英混排：训练集"中英文论文/课本"，中文 OCR（PP-OCRv6）是一流 |
| **5. 优点** | **20 类版面类别直接命中教材元素**（页眉、页脚、目录、公式、公式编号、图/表标题、参考文献…）；中文 OCR 强；模块可独立微调（可自建教材语料训练）；服务化部署成熟 |
| **6. 局限** | 是"解析产线"不自带 RAG 级"语块合并/切块策略"，需外接切分或用 PaddleX 组装；依赖 Paddle 生态，CPU 满版式解析偏慢 |
| **7. 来源链接** | [PP-StructureV3 中文教程](http://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html) · [仓库](https://github.com/PaddlePaddle/PaddleOCR) — **PP-OCR/PP-StructureV3，version3.x** |

> 标注：**针对中文（含教材/课本）PDF 强适配**，且是六个方案里唯一在文档中**逐字点名"课本/试卷/目录/页眉页脚"类别**的方案。

---

### 方案 C：RAGFlow DeepDoc（InfiniFlow，RAG 一体机）

| 字段 | 内容 |
|---|---|
| **1. 方案名称** | RAGFlow 内建文档理解引擎 DeepDoc，切分用"Chunk Method 模板" |
| **2. 切分依据** | 版面识别（OCR + layout recognition，含表格结构/图像）→ 按所选模板切块，**Book 模板按章/节层级切分并保留标题链**；另有 General/Q&A/Paper/Manual/Laws/Table 等模板 |
| **3. 适用 PDF 类型** | 扫描版 ✅、文本版 ✅；强调脏扫描/复杂版式 |
| **4. 对教材结构的处理** | 目录/章节层级：**Book 模板按 book→chapter→section 层级切分**（最贴合教材）；习题：General/块内保留；图表：版面解析识别表格/图；公式：扫 PDF 的公式识别弱于 MinerU/Paddle；页眉页脚：版面解析过滤；跨页：模板级处理较弱；中英混排：国产项目，中文支持好 |
| **5. 优点** | **开箱即用的一体机**，解析+切分+检索同链路，Book 模板即面向"书籍/教材"；界面直接选模板，无需写代码；对中文学术/教材场景知名度高 |
| **6. 局限** | DeepDoc 是闭源组件；切分粒度被模板锁死，深度定制要改内部管线；公式/复杂图表精度不如专用解析器 |
| **7. 来源链接** | [RAGFlow GitHub 仓库](https://github.com/infiniflow/ragflow)（官方 docs 站多路径当前不可达/404，故以仓库为可核验来源） — **DeepDoc / 2024–2026 持续迭代** |

> 标注：**通用 PDF（含中文），Book 模板针对教材类书籍**。

---

### 方案 D：Docling（IBM Research，LF AI & Data 项目）

| 字段 | 内容 |
|---|---|
| **1. 方案名称** | Docling（含 HybridChunker / HierarchicalChunker / LineBasedTokenChunker） |
| **2. 切分依据** | 版面+阅读顺序解析 → 原生**层级分块器**：HierarchicalChunker 每元素一切；**HybridChunker 按 token 先拆后并**，保留标题/图注元数据 |
| **3. 适用 PDF 类型** | 文本版 ✅；扫描版 ✅（可插 RapidOCR/SuryaOCR/Tesseract，支持自动语言检测） |
| **4. 对教材结构的处理** | 目录/章节层级：**Heading hierarchy 阶段**从书签、编号（支持 `第一章/1.1`/`(一)`）、字号字重还原最多 6 级；习题：作为文本/列表元素保留；图表：图片分类+导出、表格转 HTML；公式：识别并导出（LaTeX）；页眉页脚：版面元数据可区分；跨页：HybridChunker 合并"同标题的相邻小 chunk"，表格跨块可重复表头；中英混排：OCR 语言可配，公式/表格鲁棒，但字体对中文章节编号（"第X章"）需确认 |
| **5. 优点** | **把"章节层级"作为一等公民**（bookmarks/numbering/style 三信号，官方文档非常细）；分块器直接可用（含表格跨块重复表头 `repeat_table_header`）；学术/技术 PDF 精度高；完全本地可跑（IBM 开源） |
| **6. 局限** | 设计偏论文/报告，中文教材的"第X章"等中文编号与"目录→正文"跳转需实测；中文 OCR 依赖 RapidOCR，长文中文精度不如 Paddle/MinerU 主链路 |
| **7. 来源链接** | [Docling 官网](https://docling-project.github.io/docling/) · [Chunking](https://docling-project.github.io/docling/concepts/chunking/) · [Heading levels](https://docling-project.github.io/docling/usage/heading_levels/) · [arXiv:2408.09869](https://arxiv.org/abs/2408.09869) — **持续更新，2024–2026** |

> 标注：**通用 PDF（对中文/教材有可用性，非专项优化）**。

---

### 方案 E：Unstructured（Unstructured.ai）

| 字段 | 内容 |
|---|---|
| **1. 方案名称** | Unstructured（开源的 `partition_*` + `chunk_by_title`） |
| **2. 切分依据** | partitioning 按策略（`auto/fast/hi_res/ocr_only`）产出"文档元素"（Title/NarrativeText/Table/ListItem/Header/Footer…），`chunk_by_title` 以**标题为切分边界**合并元素 |
| **3. 适用 PDF 类型** | 文本版 ✅；扫描版 ✅（`hi_res`/`ocr_only`，可设 OCR 语言） |
| **4. 对教材结构的处理** | 目录/章节层级：靠 Title 元素 + by_title 切分（层级信息弱，无 multi-level 编号识别）；习题：按 NarrativeText/ListItem 块；图表：表格可提为 HTML、图可提取，但无图注语义绑定；公式：无专门公式识别；页眉页脚：有 Header/Footer 元素类；跨页：无跨页表格连续性处理；中英混排：分块基于文本特征，**中文无空格导致英文式 `\n\n` 分块失效**，中文需单独调参 |
| **5. 优点** | 生态最广（LangChain/LlamaIndex/Haystack 一等集成）；`hi_res` 对英文复杂版式稳；轻量易起步 |
| **6. 局限** | **对中文教材是六个方案中最弱**：无层级编号、无公式、无跨页、中文分块边界易错；标题检测主要针对西文排版 |
| **7. 来源链接** | [Chunking 文档](https://docs.unstructured.io/open-source/core-functionality/chunking) · [Partitioning strategies](https://docs.unstructured.io/open-source/concepts/partitioning-strategies) — **2024–2026 持续更新** |

> 标注：**通用 PDF，中文教材慎选（除非配中文 OCR + 自定义分块）**。

---

### 方案 F：LlamaParse（LlamaCloud，LlamaIndex 生态）

| 字段 | 内容 |
|---|---|
| **1. 方案名称** | LlamaParse 云解析（+ LlamaIndex node parser：MarkdownNodeParser / HierarchicalNodeParser / SemanticSplitterNodeParser / TokenTextSplitter） |
| **2. 切分依据** | 多模态模型解析（表格、图表、公式、版面、bbox）→ 输出 Markdown/JSON → LlamaIndex 侧按 Markdown 标题层级/语义/固定 token 再切 |
| **3. 适用 PDF 类型** | 文本版 ✅、扫描版 ✅（云 OCR，支持 40+ 语言含中文） |
| **4. 对教材结构的处理** | 目录/章节层级：Markdown 标题 + HierarchicalNodeParser 保留父子层级；习题：语义/结构切分保留；图表：图表理解+bbox 定位；公式：公式渲染为 Markdown/LaTeX；页眉页脚：解析层滤除；跨页：多模态整体理解较强，表格跨页可拼；中英混排：OCR 语言配置 + 多模态理解，中文可用但"中文专属精度"不如 Paddle/MinerU |
| **5. 优点** | **扫描版复杂页面效果顶级**（多模态 VLM）；解析与 LlamaIndex 检索无缝；可直接 `chunk_size`/语义切分 |
| **6. 局限** | **云服务、有费用与数据出域**（教材可能有版权/隐私顾虑，需留意 region 与企业自托管 BYOC）；对"教材级别结构"无专项模板，关键还是要配合 HierarchicalNodeParser |
| **7. 来源链接** | [LlamaParse 文档](https://developers.llamaindex.ai/llamaparse/) · [OCR & languages](https://developers.llamaindex.ai/llamaparse/parse/features/ocr-and-languages/) · [LlamaIndex Node Parser](https://developers.llamaindex.ai/python/framework/module_guides/loading/node_parsers/modules/) — **2025–2026 持续更新** |

> 标注：**通用 PDF（中文可用，云解析对扫描版教材是强项）**。

---

## 2. 教材八大结构的横向处理要点

| 教材结构 | 最佳处理方案 | 说明 |
|---|---|---|
| **目录** | Paddle（显式"目录"类）、MinerU（Content List V2）、Docling（bookmarks） | 目录应**单独解析为导航层**，用页码/书签把"目录项"和正文标题对齐，而非混入正文切片 |
| **章节层级** | Docling（三信号层级最完备）、RAGFlow Book 模板、MinerU/Paddle 标题类 | 教材切分成败在此：必须保留"第X章→X.Y→(一)"多级标题链，切块边界优先落在标题处 |
| **习题** | 通用（所有方案都当文本/列表保留） | 建议把"习题/例题/答案"单独标记为独立 chunk 或 metadata 标签，便于"找题讲解"类检索 |
| **图表** | MinerU、Paddle、LlamaParse | 图/表要"图+图注"绑定，表格转 HTML/Markdown，图片单独存并配描述摘要 |
| **公式** | MinerU、Paddle（公式识别子产线）、LlamaParse、Docling | 数学/理科教材关键；需输出 LaTeX 保留可读性，扫描版公式最依赖识别模型 |
| **页眉页脚** | Paddle（显式类）、MinerU、Unstructured（Header/Footer 类） | 必须在解析层**提前剥离**，否则"第3章 XXX"这类页眉会污染每个切片、误导检索 |
| **跨页内容** | MinerU（`continues_prev`）、Docling（表格跨块重复表头）、LlamaParse | 表格/图跨页要拼接或打标记；段落跨页应靠阅读顺序合并而非按页硬切 |
| **中英文混排** | Paddle、MinerU（CJK 换行处理）、RAGFlow | 中文没有空格，英式按 `\n\n`/空格分块会失效；需 CJK 感知的分词/换行，或直接信任版面块边界 |

---

## 3. 文本版 vs 扫描版（选型速查）

| 场景 | 推荐 |
|---|---|
| **文本版中文教材**（可复制文本、有书签/字体） | **Docling**（标题层级最好）或 **MinerU Flash/Standard**（块解析干净）；切分用 Docling HybridChunker / 标题层级分块 |
| **扫描版中文教材**（图片型 PDF） | **PaddleOCR PP-StructureV3**（中文 OCR + 20 类版面）或 **MinerU Basic/Advanced**（本地可跑）；预算允许且要极致效果再上 **LlamaParse**（云） |

---

## 4. 优先推荐

**首选组合：`MinerU`（或 `PP-StructureV3`）做解析层 + `Docling HybridChunker` / 自建"标题层级分块"做切分层。**

理由：

1. **中文教材 PDF 的瓶颈在"结构解析"不在"切分算法"**。MinerU 与 PaddleOCR-V3 是社区公认中文/中英混排解析第一梯队，且都**显式处理页眉页脚、目录、公式编号、图/表标题**这些教材致命细节——而通用工具（Unstructured）会把这些混进正文污染切片。
2. **章节层级是教材切分的胜负手**。Docling 的 Heading Hierarchy（书签 + 编号 + 字号三信号、最多 6 级）和 RAGFlow 的 Book 模板最贴近"第X章→X.Y→(一)"；若用 MinerU/Paddle，就把它输出的结构化块接给"标题层级分块器"，让切分边界落在章节标题上。
3. **覆盖两类 PDF**：文本版走 MinerU Flash / Docling（快、准、本地）；扫描版走 MinerU Advanced 或 PaddleOCR（中文 OCR 强），一套管线两类都能兜住。
4. **可私有化、可核验**：MinerU、PaddleOCR、Docling 均可本地部署（MinerU/Docling 开源、Paddle 开源），避免教材版权数据上传到云（LlamaParse 是云服务，需额外评估数据出域）。

一句话结论：**解析层用"中文友好的版面解析器（MinerU / PaddleOCR-V3）"，切分层用"标题层级分块器（Docling HybridChunker 或等价逻辑）"——先剥离页眉页脚与目录，再按章节层级切块，并单独标记习题/图表/公式**，这是目前对中文教材 PDF 最稳妥、且每一环来源都可核验的组合。