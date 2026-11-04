# RAG-D24 上传前 dry-run 预览改造说明

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D24 |
| 版本/状态 | v1.0 / 实装说明 |
| 日期 | 2026-11-04 |
| 依据 | RAG-D15 §六 待办 #6、RAG-D21 §六 P2 |
| 改造目标 | 上传前看到"这本书会被切成什么样"，不满意直接换文件 |

---

## 一、改造前的问题

原有流程是「上传 → 落桶 → 建 job → 异步切分 → 看结果」。发现问题时已经产生了：

- 对象存储里的一份文件
- 一条 `rag_document` 行
- 一条 `rag_ingest_job` 记录

重传要再走一遍完整链路。而切分质量问题（答案区误判、公式乱码、题号丢失）
**恰恰是教辅 RAG 的第一瓶颈**（RAG-D04 §3）——这种往返成本很高。

---

## 二、设计：纯内存 dry-run

### 2.1 为什么可行：`IngestionPipeline` 本来就是纯函数

```java
public List<Chunk> ingest(documentId, title, edition, content, knowledgePoints)
```

它只依赖 `DocumentParserPort`，**零 Repository / JdbcTemplate 依赖**。
这是本功能能成立的根本原因——不需要"改造出一个不落库的版本"。

### 2.2 硬约束：dry-run 不得产生任何副作用

| 不得做 | 理由 |
|---|---|
| 写对象存储 | 预览失败就白占了存储 |
| 写 `rag_document` / `rag_chunk` / `rag_ingest_job` | 否则"先预览再入库"退化成"上传了两次" |
| 嵌入 / 建向量 | 纯浪费算力 |

`DocumentPreviewService` 的依赖**只有** `IngestionPipeline` 与 `JdbcIngestSourceLoader`——
结构上就没有"写"的能力。单测 `previewOnlyParses` 断言了这一点。

### 2.3 版本坐标从「来源已登记的五轴」取

dry-run 时**还没有 documentId**，而原 `load()` 是从 `rag_document.edition_id` 反查 edition。

故在 `JdbcIngestSourceLoader` 新增 `loadForPreview(tenantId, sourceId)`：
直接从 `rag_edition` 按 `source_id` 取，并**复用**既有的 `loadEdition` / `loadKnowledgePoints`。

**⚠️ 取不到版本坐标时明确报错，绝不造默认坐标**：

若造一个默认坐标（未指定/全学段），预览会"成功"；
而真实入库时若该来源也没坐标，载体同样挂到默认坐标——
**预览就成了"确认一个错误的结果"**。宁可让人看到"该来源未登记五轴"。

> 单测 `missingEditionFailsLoudly` 断言：报错时**不能**去解析。

### 2.4 预览结果必须与真实入库一致

预览与入库**共用**同一个 `IngestionPipeline` 与同一套 edition/KP 加载逻辑。
若预览用一套切分、入库用另一套，预览就成了误导。

---

## 三、两个实现细节

### 3.1 预览片段 ID 由内容哈希派生

```java
String documentId = "preview-" + contentDigest(content);
```

**⚠️ 不能用固定占位符**：`chunkId` 由「documentId + 页码 + charStart」派生，
固定占位会让**不同文件得到相同的 chunkId**——虽然不落库，
但界面上的 ID 会误导人（"这两个片段是同一个"）。

用内容哈希后：同一文件多次预览 ID 稳定（便于比对），不同文件必然不同。

### 3.2 角色统计预置全部角色

```java
for (ChunkRole r : ChunkRole.values()) {
    map.put(r.name(), 0);   // 先全部置 0
}
```

**⚠️ 只显示"出现过的角色"会让两次预览的图例跳来跳去**，
人无法快速对比"这次是不是答案类变多了"。

---

## 四、改动清单

### 4.1 后端

| 文件 | 改动 |
|---|---|
| `rag-infrastructure/.../ingest/DocumentPreviewService.java` | **新增**：dry-run 预览服务 |
| `rag-infrastructure/.../ingest/JdbcIngestSourceLoader.java` | + `loadForPreview` + `PreviewContext`（按 sourceId 取 edition/KP） |
| `rag-infrastructure/.../LocalRagPortsConfig.java` | + `documentPreviewService` bean |
| `rag-management/.../api/RagManagementController.java` | + `POST /sources/{sourceId}/documents/preview` |
| `rag-management/.../api/ManagementDtos.java` | + `PreviewResponse` / `PreviewChunkDto` |

### 4.2 前端

| 文件 | 改动 |
|---|---|
| `ragTypes.ts` / `ragClient.ts` | + 预览类型与 `previewRagDocument` |
| `RagDocUploadPage` | 上传区改为**两步**：拖拽 → 预览 → 确认入库 |

### 4.3 端点

| 方法 | 路径 | 授权 |
|---|---|---|
| `POST` | `/api/v1/management/sources/{sourceId}/documents/preview` | **读上下文**（无副作用，不需票据） |

> ⚠️ 刻意不要治理票据：若要求票据，会让人为了"看一眼"去领一张写票。

---

## 五、前端交互

```
拖拽 PDF
   ↓
① 解析预览（dry-run）→ 显示：版本坐标 / 总片段数 / 角色分布 / 片段样本
   ↓
② 确认入库 / 放弃
```

预览面板展示：

| 信息 | 用途 |
|---|---|
| 版本坐标 + 总片段数 | 确认解析到了对的文件、切分数量合理 |
| 角色分布（全角色，含 0） | 一眼看出答案类占比是否异常 |
| 片段样本（按角色着色） | 答案类红底 + 左 4px 竖条，泄题风险可见 |
| `答案区` / `BlockType` 标签 | 展示判定依据（RAG-D19 版面事实） |

---

## 六、验证结果

| 验证项 | 结果 |
|---|---|
| 后端 9 模块 | ✅ 全 SUCCESS |
| `DocumentPreviewServiceTest` | ✅ **5 项全绿** |
| rag-infrastructure | **110**（改造前 105，+5） |
| rag-core / ports / application / worker / api / management / bootstrap | 132 / 2 / 63 / 3 / 6 / 236 / 41 |
| 前端 `tsc` / 测试 | ✅ 0 error / 460 全绿 |

### 新增测试（5 项）

| 分组 | 测试 | 守护的语义 |
|---|---|---|
| 截断 | `truncatesButReportsTotal` | 截断后仍报告总数 |
| | `clampsLimit` | 上限夹在 `[1, MAX]` |
| | `roleStatsIncludeZeroRoles` | 全角色预置 0，图例不跳 |
| 零副作用 | `previewOnlyParses` | **只解析，不写任何数据** |
| 缺失坐标 | `missingEditionFailsLoudly` | **报错时不解析**（不确认错误结果） |

---

## 七、边界与未做

| # | 项 | 说明 |
|---|---|---|
| 1 | **平台全局档不支持预览** | 需运维通道读版本坐标，端点明确返回 501（不返回"看起来正常"的空结果） |
| 2 | **预览不写对象存储** | 确认入库时会再读一遍文件重算哈希——预览与入库各算一次，可接受 |
| 3 | **无预览缓存** | 换文件就重新解析；大册 PDF 预览需等解析器跑完 |
| 4 | **未接入 Docling 真实解析器** | 当前占位解析器（RAG-07），预览的切片质量不代表生产效果 |

> 第 4 项最需注意：**预览看到的效果取决于解析器**。
> 占位解析器产出的糙结构不代表 Docling 接入后的真实切片——
> 这一点与 RAG-07 的"占位 Port fail-closed"是同一个已知边界。

---

## 八、待办全部收敛

| # | 待办 | 状态 |
|---|---|---|
| 1 版本五轴落库 | ✅ RAG-D16 |
| 2 召回漏斗计数 | ✅ RAG-D17 |
| 3 门禁阈值下发 | ✅ RAG-D20 |
| 4 版面事实持久化 | ✅ RAG-D19 |
| 5 索引代际蓝绿切换 | ✅ RAG-D18 |
| **6 上传前 dry-run 预览** | ✅ **本文档** |
| 7 字典类型扩展 | ✅ RAG-D20 |
| 8 知识点本体查询 | ✅ RAG-D20 |
| 9 代际构建异步化 | ✅ RAG-D22 |
| 10 全局桶代际构建 | ✅ RAG-D23 |

**RAG-D15 §六 的 8 项待办已全部交付**，外加 D22/D23 两项衍生改造。
