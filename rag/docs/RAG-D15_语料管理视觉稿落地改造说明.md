# RAG-D15 语料管理视觉稿落地改造说明

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D15 |
| 版本/状态 | v1.0 / 改造实装说明 |
| 日期 | 2026-11-04 |
| 依据 | **RAG-D14 高保真视觉稿**（唯一设计标准）、RAG-D13 UI 设计文档、RAG-D12 业务流程再设计 |
| 范围 | admin-console 前端 + `rag/` 后端同步改造，逐页对照视觉稿落地 |
| 视觉策略 | **沿用 admin-console 现有 antd 主题**，按视觉稿的信息架构与组件语义落地（不复刻像素/配色） |

---

## 一、改造总览

### 1.1 差异梳理结论（改造前）

改造前 RAG 在 admin-console 里是**单一页面挂 5 个 Tab**：

```
/rag  →  RagManagementPage（491 行）
          ├─ 作用域选择 + 治理票据（内联在页内）
          ├─ Tabs: 语料来源 / 统计概览 / 审核待办 / 有效期 / 维护
          └─ 10 个面板组件（SourceTable/Drawer/InspectionPanel/…）
```

| 维度 | 改造前 | 视觉稿要求 | 差异性质 |
|---|---|---|---|
| 页面组织 | 1 页 + 5 Tab | **13 个独立页面** | 信息架构重构 |
| 导航 | 1 个菜单项 | **四治理域分区** | 导航重构 |
| 写面入口 | 弹窗内嵌在列表页 | 独立「来源登记」页 | 新增页面 |
| 读面 | **无**（仅 frontend/ 有） | 「召回解释（质检）」页 | 新增能力 |
| 片段正文 | 接口不返回 | 解析预览/校对需展示原文 | **后端契约缺口** |
| 片段角色重判 | **端点不存在** | 校对页四个重判按钮 | **后端能力缺失** |
| 审核员全量名录 | 只有 upsert + co-signers 子集 | 独立名录页 | **后端端点缺失** |
| 任务状态 | 8 值枚举全展示 | 收敛为四态 + 单进度条 | 前端映射 |

### 1.2 改造原则（三条硬约束）

1. **不做无依据的扩展**：视觉稿有、后端没有的能力，先补后端；补不了的显式标注为「后端待办」，**不画点不动的按钮**。
2. **不编造数据**：缺失的指标显示「—」或「待后端提供」，绝不用 `0` 冒充（`0` 会被读成"没有发生"）。
3. **保留既有安全机制**：作用域（TENANT/PLATFORM）显式声明、治理票据、双人复核、抽检前置守卫、SoD 全部原样保留。

---

## 二、后端改造（先行）

### 2.1 新增端点 ①：片段角色重判

| 项 | 内容 |
|---|---|
| 方法/路径 | `PATCH /api/v1/management/chunks/{chunkId}/role` |
| 请求体 | `UpdateChunkRoleRequest { role, reason? }` |
| 响应 | `ChunkResponse`（重判后的片段摘要） |
| 授权 | **写上下文**（`requireWriteContext`）：租户档需 `X-Tenant-Id`；平台档需平台域令牌 + 治理票据 |
| 审计 | 落 `INGEST` 动作（不新增审计枚举值，避免 DDL 迁移） |

**⚠️ 为什么这个端点必须存在且必须受写闸门约束**

角色判定是基于版面事实的确定性规则（答案区标识 + 题号 + 块首前缀），不是模型推断。规则再严也挡不住版面千奇百怪——没有这个端点，纠正唯一途径是**重导整册**（连带重建向量与分词），代价远大于改一个角色。

同时它**高风险**：把「讲解」改成「习题答案」是收紧（安全）；把「习题答案」改成「讲解」是**放松**，后者会让标准答案在讲解模式下被召回，即泄题。所以它与登记/校验同级授权，并留审计。

**实现要点**：

```java
// 只改 role 一列：text / content_hash / 向量 / 分词都不动
//   —— 角色不参与嵌入，检索期 RetrievalPolicy.allowsRole 直接读本列，改完即生效
SELECT role::text FROM rag_chunk WHERE chunk_id=? AND tenant_id=?   // 读旧值（审计 from→to）
UPDATE rag_chunk SET role = ?::rag.rag_chunk_role WHERE ...          // 0 行即 404
```

- 走 `writeVia(context, ...)` 而非应用连接：平台全局语料的片段行 `tenant_id = 全局哨兵`，用应用连接改会被 RLS **静默滤成 0 行**（表面成功、实际未改）。
- 0 行受影响统一返回 **404**，不区分"不存在"与"不属于当前作用域"，避免探测其它租户数据。

### 2.2 新增端点 ②：审核员全量名录

| 项 | 内容 |
|---|---|
| 方法/路径 | `GET /api/v1/management/reviewers` |
| 响应 | `ReviewerItem[] { principalId, displayName, canReview, canCoSign, enabled }` |
| 授权 | 读上下文（`requireManagementContext`），不需票据 |

**⚠️ 与既有 `GET .../co-signers` 的区别**（两者不可互替）：

| | `co-signers` | 本端点 |
|---|---|---|
| 语义 | "能复核的人"这个**场景化子集** | **全量名录** |
| 过滤 | 排除本人与采集人、只保留 `canCoSign` | 不过滤，停用项也返回 |
| 用途 | 校验表单的下拉候选 | 管理员回答"现在谁能审核、谁被停用了" |

> 停用项必须返回：否则管理员无法"重新启用一个看不到的人"。

### 2.3 契约扩展：片段正文

| 项 | 内容 |
|---|---|
| 影响端点 | `GET /api/v1/management/documents/{documentId}/chunks` |
| 新增字段 | `ChunkResponse.text`（片段正文） |
| 截断策略 | 仓储侧 `left(c.text, 512)`（常量 `CHUNK_TEXT_PREVIEW_CHARS`） |

**⚠️ 为什么必须返回正文**：解析预览与片段校对要让人**看到原文**才能判断"这段到底是答案还是讲解"。只给 `charLength`（"128 字"）无法支撑任何判断——而误判的最坏后果是泄题。

**为什么截断到 512**：一册教材 2400+ 片段，整册正文灌进响应体会拖慢列表、把"预览"变成"全文阅读器"。512 字足以覆盖一道完整题目或一段讲解的起头。

### 2.4 后端改动清单

| 文件 | 改动 |
|---|---|
| `rag-management/.../api/ManagementDtos.java` | `ChunkResponse` 增 `text`；新增 `UpdateChunkRoleRequest`（role 用 `@Pattern` 校验为 ChunkRole 枚举名） |
| `rag-management/.../api/RagManagementController.java` | 新增 `PATCH /chunks/{chunkId}/role`、`GET /reviewers`；补 `PatchMapping` 导入 |
| `rag-management/.../ports/CorpusManagementPort.java` | `ChunkSummary` 增 `text`；新增 `updateChunkRole`、`listReviewers` |
| `rag-management/.../infrastructure/JdbcCorpusManagementRepository.java` | chunks SQL 增 `left(c.text,512)`；实现 `updateChunkRole`（writeVia + 审计 + 404）、`listReviewers` |

---

## 三、前端改造

### 3.1 公共层（新增两个文件）

| 文件 | 职责 |
|---|---|
| `pages/rag/RagScopeShell.tsx` | **统一页外壳**：标题/说明/作用域选择/治理票据/`RagScopeContext` |
| `pages/rag/ragVisuals.tsx` | **视觉语义组件**：角色着色、三态徽标、治理四态、阈值刻度、漏斗、结构化空态 |

**⚠️ 为什么抽这两层**：RAG-D12 认定的复杂度之一是「同一套规则在多处重复」。若作用域选择与角色着色在每个页面各写一份，必然出现"这页答案类红色、那页橙色"的语义漂移。集中后，13 页共享**同一口径**（对应 RAG-D13 PU-1「单一真源可视化」）。

`ragVisuals` 的关键能力：

| 组件 | 承载的靶点 |
|---|---|
| `ChunkRoleTag` / `ChunkRoleLegend` | 泄题风险视觉化（答案类红 / 提示橙 / 题目蓝 / 可引用绿 / 噪声灰） |
| `toIngestState` / `IngestProgress` | **靶点 A**：8 态 → 四态 + 单进度条 |
| `RelevanceMeter` | **靶点 C**：只展示一个 relevance + 0.38 阈值刻度 |
| `RecallFunnel` | **靶点 D**：过滤前后数量序列 |
| `RagEmpty` | PU-3：带原因的空态，绝不用空白 |

### 3.2 逐页改造说明

> 每页给出：**改造内容（前端）/ 依赖接口 / 状态与交互**。

#### ① Dashboard（`/rag/dashboard`）

- **前端**：新建 `RagDashboardPage`。KPI 行（来源总数/待审核/进行中任务/可检索片段/缺向量片段）+ 四治理域入口卡 + 最近摄取任务三态 + 待审核来源列表。
- **接口**：`GET /statistics`、`GET /sources`、`GET /ingest-jobs`（均复用，无新增）。
- **诚实边界**：视觉稿的「门禁触发率」「答案类抑制数」**无后端聚合端点**，本页不放这两项——改放可算出的"缺向量片段"（有端点且更重要：缺向量=静默漏召回）。

#### ② 来源登记（`/rag/sources/new`）

- **前端**：新建 `RagSourceRegisterPage`。四节表单（基本信息 / **版本五轴** / 授权与权威 / 正文引用）+ 右侧"五轴完整性提示"。
- **🔧 接口与字段变更**：`RagRegisterSourceRequest` 新增 `subject / grade / term / editionPublisher / revisionCode / trustTier / licenseStatus`。
- **交互**：**五轴未填齐时提交按钮禁用**，并实时显示 `(3/5)`；把"缺一轴不可入库"从文档纪律变成 UI 约束。
- **⚠️ 待办**：后端 `RegisterSourceRequest` 尚未接收这五个字段（本次仅前端建模），需后端补同名字段才能落库版本坐标。

#### ③ 来源列表（`/rag/sources`）

- **前端**：新建 `RagSourceListPage`，**复用**既有 `RagSourceTable` + `RagSourceDrawer` + `RagMetadataConfirmDrawer`（这三个组件功能已完备，重写等于制造第二套规则）。
- **差异**：注册入口从"弹窗"改为**跳转独立登记页**（视觉稿 §3.2.1）；作用域选择上提到 `RagScopeShell`。
- **接口**：无变更。

#### ④ 载体上传与解析预览（`/rag/doc-upload`）

- **前端**：新建 `RagDocUploadPage`。左栏（上传 + 元数据 + **三态进度**）｜右栏（解析预览片段列表 + 角色图例 + 角色筛选）。
- **接口**：`POST /sources/{id}/documents`、`GET /sources/{id}/documents`、`GET /documents/{id}/chunks`（**新增 `text` 字段**）。
- **交互**：片段卡按角色着色，**答案类红底 + 左 4px 竖条**，让"可能泄题"成为视觉事实；噪声块灰态标"已丢弃"。
- **⚠️ 待办**：视觉稿的"上传后、入库前实时预览"需后端提供 dry-run 解析；当前必须先落库切分才有片段可看，页面已如实说明。

#### ⑤ 片段校对（`/rag/chunk-fix`）

- **前端**：新建 `RagChunkFixPage`。左栏片段列表（答案类置顶）｜右栏"原文 + 版面事实 + 角色重判"。
- **🔧 新增接口**：`PATCH /api/v1/management/chunks/{chunkId}/role`。
- **交互**：四个重判按钮（习题答案/例题解答/习题提示/噪声），危险项用红色；重判后本地同步更新，不打断校对节奏。
- **诚实边界**：视觉稿的"版面事实表"（`inAnswerZone` / `BlockType` / 块首前缀命中）**无持久化字段**，本页只展示实际可得的（页码/题号/章节路径/字符数/向量状态），并注明缺失项。

#### ⑥ 校验决定（`/rag/verify`）

- **前端**：新建 `RagVerifyPage`。来源详情 + 校验决定区（证据/复核人/权威等级/有效期）+ **审计时间线**。
- **接口**：`GET /sources/{id}`、`POST /sources/{id}/verification`、`GET /sources/{id}/co-signers`（无新增）。
- **交互**：支持 `?sourceId=` 深链（从 Dashboard「处理」直达）；VERIFIED 必填证据 + 复核人 + 权威等级，任一缺失则按钮禁用；REVOKED 显示"终态不可复活"警告。

#### ⑦ 抽检队列（`/rag/inspection`）

- **前端**：新建 `RagInspectionPage`。疑似清单（按严重度排序，HIGH 置顶）+ 判定区 + 抽检历史。
- **接口**：`GET /sources/{id}/inspection/suspects`（含正文与疑点原因）、`POST /sources/{id}/inspection`、`GET /sources/{id}/inspection`（无新增）。
- **交互**：默认全 PASS（"系统判对了"是默认假设，人只挑错）；改 FAIL 才展开"应为角色 + 错误类型"，且错误类型必选。

#### ⑧ 召回解释（质检）（`/rag/recall`）

- **前端**：新建 `RagRecallPage`。查询条件（question + intent + 五轴 + Mode + topK）→ 检索摘要 → 命中列表（每条带 relevance + 阈值条 + 通道 + 命中原因）。
- **接口**：`POST /api/v1/retrieval`（复用；仅 TENANT 档可用）。
- **🔧 靶点 C/E 落地**：把 `hits.length` + `answerContentSuppressed` 收敛为 **三态 `GROUNDED / INSUFFICIENT / SUPPRESSED`**。
- **⚠️ 待办**：视觉稿的"过滤漏斗计数"后端**未随响应返回**，本页显示"尚未接入"说明而非编造数字。

#### ⑨ 审核员名录（`/rag/reviewers`）

- **前端**：新建 `RagReviewerRosterPage`。名录表格 + 新增/编辑弹窗 + SoD 冲突标红。
- **🔧 新增接口**：`GET /api/v1/management/reviewers`。
- **交互**：`canCoSign && !canReview` 标红"⚠ 可复核但不可审核"（DDL 会拒的矛盾态）；表单提示"开复核必须开审核"。

#### ⑩ 知识点别名（`/rag/kp-alias`）

- **前端**：新建 `RagKpAliasPage`。左"按 kpId 查询" + 右"别名列表增删"。
- **🔧 新增前端 API 封装**：`getRagKpAliases` / `upsertRagKpAlias` / `deleteRagKpAlias`（后端端点本已存在，前端此前未封装）。
- **诚实边界**：后端 `GET /kp-aliases` 只返回**别名字符串数组**（无 canonical_name/先修关系），故本页只做别名增删，**不伪造**知识点本体编辑器。

#### ⑪ 字典（`/rag/dictionaries`）

- **前端**：新建 `RagDictionaryPage`。类型 Tab + 条目表格 + 新增/编辑（仅可编辑类型显示操作）。
- **接口**：`GET /dictionaries`、`POST/DELETE /dictionaries/{type}/entries`（无新增）。
- **⚠️ 与视觉稿冲突（如实呈现）**：视觉稿原型画的是「出版社/学科/版次」，后端实际只有 `SOURCE_TYPE / TRUST_TIER / VERIFICATION_METHOD / LICENSE_STATUS`，且仅 SOURCE_TYPE 可写。本页按**真实类型**渲染，并在页脚说明差异——画不存在的类型只会让人点了报错。

#### ⑫ 摄取任务（`/rag/jobs`）

- **前端**：新建 `RagIngestJobsPage`。状态分段筛选 + 任务表格（三态/进度/失败原因/重试）。
- **接口**：`GET /ingest-jobs`、`POST /ingest-jobs/{id}/retry`（无新增）。
- **🔧 靶点 A 落地**：8 值枚举经 `toIngestState` 收敛为四态；**只渲染一条进度条**，不展示 PARSING/CHUNKING/EMBEDDING/INDEXING。
- **进度真实性**：后端无 `progress` 列，百分比编码在 `stage` 尾部（`"EMBEDDING 60"`），用 `parseStagePercent` 解析；**解析不出就显示不确定态**，不编造。

#### ⑬ 重索引（`/rag/reindex`）

- **前端**：新建 `RagReindexPage`。执行区（limit ≤ 2000）+ 结果回执（已索引/已扫描/耗时）。
- **接口**：`POST /reindex`（复用）。
- **⚠️ 与视觉稿差异（如实呈现）**：视觉稿画的是「代际列表 + 蓝绿切换 + 回滚」，后端**只有同步重索引**、无代际管理 API。本页按真实能力渲染，并明确写出"蓝绿切换尚未提供"——**不渲染不可用的切换按钮**。

### 3.3 路由与导航改造

**路由**（`src/router.tsx`）：

| 路由 | 页面 |
|---|---|
| `/rag` | 保留旧单页（兼容既有书签） |
| `/rag/dashboard` | Dashboard 总览 |
| `/rag/sources` · `/rag/sources/new` | 来源列表 · 来源登记 |
| `/rag/doc-upload` · `/rag/chunk-fix` | 载体上传与解析预览 · 片段校对 |
| `/rag/verify` · `/rag/inspection` · `/rag/recall` | 校验决定 · 抽检队列 · 召回解释 |
| `/rag/reviewers` · `/rag/kp-alias` · `/rag/dictionaries` | 审核员名录 · 知识点别名 · 字典 |
| `/rag/jobs` · `/rag/reindex` | 摄取任务 · 重索引 |

**导航**（`src/layout/navConfig.tsx`）：RAG 从 1 个菜单项拆为 **5 个分组**（含总览）：

```
RAG · 录入与校对  → Dashboard / 来源登记 / 来源列表 / 载体上传与解析预览 / 片段校对
RAG · 审核        → 校验决定 / 抽检队列 / 召回解释（质检）
RAG · 规则配置    → 审核员名录 / 知识点别名 / 字典
RAG · 运维        → 摄取任务 / 重索引
```

对应 RAG-D12 **靶点 G**（治理面过重）：从"34 端点单控制器 + 5 Tab 混装"变为"四域分区、每页一件事"。

---

## 四、接口与字段变更汇总

### 4.1 新增接口（2 个）

| 方法 | 路径 | 用途 | 授权 |
|---|---|---|---|
| `PATCH` | `/api/v1/management/chunks/{chunkId}/role` | 片段角色重判 | 写上下文（平台档需票据） |
| `GET` | `/api/v1/management/reviewers` | 审核员全量名录 | 读上下文 |

### 4.2 字段变更（2 处）

| 契约 | 变更 |
|---|---|
| `ChunkResponse` / `RagChunk` | **+ `text`**（正文预览，后端截断 512） |
| `RegisterSourceRequest` / `RagRegisterSourceRequest` | **+ 版本五轴 `subject/grade/term/editionPublisher/revisionCode`**、`trustTier`、`licenseStatus`（⚠️ 前端已建模，后端待补） |

### 4.3 前端 API 新增封装（4 个函数）

| 函数 | 对应后端 |
|---|---|
| `updateChunkRole` | `PATCH /chunks/{id}/role`（新端点） |
| `listRagReviewers` | `GET /reviewers`（新端点） |
| `getRagKpAliases` / `upsertRagKpAlias` / `deleteRagKpAlias` | 既有 `/kp-aliases`（前端此前未封装） |

---

## 五、验证结果

| 验证项 | 结果 |
|---|---|
| 前端 TypeScript `tsc --noEmit` | ✅ 通过（0 error） |
| 前端单元测试 `vitest run` | ✅ **44 个文件 / 460 个用例全绿** |
| 后端编译 `mvn compile`（rag-bootstrap -am） | ✅ BUILD SUCCESS |
| 后端测试 `mvn test`（rag-management -am） | ✅ **226 个用例全绿** |

> 后端测试包含 `RagManagementControllerTest`（71 项）等既有用例，证明新增端点未破坏原有行为。

---

## 六、未完成项与后端待办

> 按"如实登记、不粉饰"的原则列出。**这些项在页面上均已显式标注**，不会让用户误以为已支持。

| # | 待办 | 影响页面 | 建议做法 |
|---|---|---|---|
| 1 | `RegisterSourceRequest` 接收版本五轴，并建立 `rag_edition` 坐标 | 来源登记 | 加 5 个可选字段 + 登记时 upsert `rag_edition` |
| 2 | 召回漏斗计数（candidate / versionFiltered / roleFiltered / above）随响应返回 | 召回解释 | `RetrievalResult` 增计数，`RetrievalResultDto` 暴露 |
| 3 | 门禁阈值 0.38 随响应下发 | 召回解释、抽检 | DTO 增 `minScore`，前端不再硬编码 |
| 4 | 版面事实持久化（`inAnswerZone` / `BlockType` / 块首前缀命中） | 片段校对 | `rag_chunk` 增列或落 `rag_chunk_layout_fact` |
| 5 | 索引代际管理 API（列表/切换/回滚） | 重索引 | 新增 generation 资源端点 |
| 6 | 上传后 dry-run 解析预览（不落库） | 载体上传 | 新增 `POST /documents/preview` |
| 7 | 字典类型扩展（出版社/学科/版次） | 字典 | `rag_dictionary` 增类型 + 放开 `editable` |
| 8 | 知识点本体查询（canonical_name / 先修） | 知识点别名 | 新增 `GET /knowledge-points` |

---

## 七、附：改动文件清单

### 后端（4 文件修改）

```
rag/rag-management/src/main/java/cn/studykid/rag/management/api/ManagementDtos.java
rag/rag-management/src/main/java/cn/studykid/rag/management/api/RagManagementController.java
rag/rag-management/src/main/java/cn/studykid/rag/management/ports/CorpusManagementPort.java
rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/JdbcCorpusManagementRepository.java
```

### 前端（4 文件修改 + 15 文件新增）

```
admin-console/src/api/ragClient.ts              (M)  +updateChunkRole / listRagReviewers / kp-alias
admin-console/src/api/ragTypes.ts               (M)  +text / +RagUpdateChunkRoleRequest / +登记五轴
admin-console/src/router.tsx                    (M)  +13 路由
admin-console/src/layout/navConfig.tsx          (M)  +四治理域分组

admin-console/src/pages/rag/RagScopeShell.tsx        (A)
admin-console/src/pages/rag/ragVisuals.tsx           (A)  视觉语义组件
admin-console/src/pages/rag/RagDashboardPage.tsx     (A)
admin-console/src/pages/rag/RagSourceRegisterPage.tsx(A)
admin-console/src/pages/rag/RagSourceListPage.tsx    (A)
admin-console/src/pages/rag/RagDocUploadPage.tsx     (A)
admin-console/src/pages/rag/RagChunkFixPage.tsx      (A)
admin-console/src/pages/rag/RagVerifyPage.tsx        (A)
admin-console/src/pages/rag/RagInspectionPage.tsx    (A)
admin-console/src/pages/rag/RagRecallPage.tsx        (A)
admin-console/src/pages/rag/RagReviewerRosterPage.tsx(A)
admin-console/src/pages/rag/RagKpAliasPage.tsx       (A)
admin-console/src/pages/rag/RagDictionaryPage.tsx    (A)
admin-console/src/pages/rag/RagIngestJobsPage.tsx    (A)
admin-console/src/pages/rag/RagReindexPage.tsx       (A)
```