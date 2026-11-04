# RAG-D20 待办收敛：门禁阈值下发 / 字典类型扩展 / 知识点本体查询

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D20 |
| 版本/状态 | v1.0 / 实装说明 |
| 日期 | 2026-11-04 |
| 依据 | RAG-D15 §六 待办 #3 / #7 / #8 |
| 改造目标 | 消除前端硬编码阈值；补齐规则配置域两页缺失的能力端点 |

---

## 一、待办 #3：门禁阈值随响应下发

### 1.1 问题

分数门禁阈值来自 `rag.retrieval.min-score`（默认 0.38，可经 `RAG_RETRIEVAL_MIN_SCORE` 覆盖），
但**不随检索响应返回**。前端只能硬编码 `RAG_SCORE_THRESHOLD = 0.38`。

**危害**：一旦运维改了配置而未同步改前端，界面画的刻度线就与实际门禁**不是同一个数**
——用户会看到"明明过了刻度线却被判依据不足"的矛盾界面，而这种矛盾极难归因。

### 1.2 设计：由结果携带，不从配置另读

```
RetrievalOrchestrator（构造期定格 minScore）
  → RetrievalResult.minScore        ← 本次实际生效的阈值
    → RetrievalResultDto.minScore   ← 随响应下发
      → 前端 RelevanceMeter(threshold = result.minScore)
```

**⚠️ 为什么不让前端/控制器各自读配置**：
阈值是**编排器构造期定格**的（配置改了但未重启时，编排器仍用旧值）。
若界面另读配置，就会出现"配置说 0.45、实际门禁是 0.38"的漂移。
让结果把**真实生效值**带出来，是消除这类漂移的唯一可靠做法。

### 1.3 改动

| 位置 | 改动 |
|---|---|
| `RetrievalResult` | + `minScore` 字段（保留两个兼容构造器：无漏斗 / 有漏斗无阈值） |
| `RetrievalOrchestrator` | 构造结果时传入 `minScore` |
| `RetrievalResultDto` | + `minScore`（保留两个兼容构造器） |
| `ragTypes.ts` | `RagRetrievalResponse.minScore?`（可选，旧后端缺失） |
| `RagRecallPage` | `threshold={result.minScore ?? RAG_SCORE_THRESHOLD}` |

> ⚠️ 前端仍保留常量作为**旧版后端的降级值**，而不是直接删掉——
> 硬编码是"假设"，降级值是"兜底"，两者语义不同。

---

## 二、待办 #7：字典类型扩展

### 2.1 问题

视觉稿要求字典类型为「出版社 / 学科 / 版次」，但后端只有：
`SOURCE_TYPE` / `TRUST_TIER` / `VERIFICATION_METHOD` / `LICENSE_STATUS`，
且**只有 `SOURCE_TYPE` 可写**。

字典页因此只能按真实类型渲染 4 个标签页，其中 3 个只读——规则配置域形同半成品。

### 2.2 设计：新增 4 个动态类型

| 新增类型 | 说明 | 是否参与枚举反查 |
|---|---|---|
| `EDITION_PUBLISHER` | 出版社（**版本体系**，非出版机构） | ✅ 检索期 `publisherFromDb` |
| `SUBJECT` | 学科 | ✅ `subjectFromDb` |
| `GRADE` | 年级 | ✅ `gradeFromDb` |
| `REVISION` | 版次 | ❌ 无枚举约束 |

**⚠️ 为什么是"动态字典"而不是"派生字典"**：
派生字典由 Java 枚举在查询时生成、只读。五轴四类的取值本来就在枚举里，
但**枚举数量有限且会随业务增长**（新增一个版本体系就要改代码发版）。
做成 `rag_dictionary` 表的动态类型，运维可直接新增取值。

**⚠️ 前三个类型有枚举约束的隐患**：
检索期用 `displayName()` 反查，**比对不上直接抛异常 → 该校材任何检索 500**。
故前端页脚明确提示"取值须落在版本坐标枚举范围内"。
`REVISION` 不参与反查，可自由填写。

### 2.3 改动

| 位置 | 改动 |
|---|---|
| `DictionaryPort` | + 4 个类型常量（含使用说明） |
| `JdbcDictionaryRepository.isEditable` | 从"仅 SOURCE_TYPE"扩展为 5 个动态类型 |
| `RagManagementController.listDictionaries` | 类型列表 4 → 8，顺序调整为"五轴在前、治理在后" |
| `ragTypes.ts` | `RagDictionaryType` 联合类型 + 4 个值 |
| `RagDictionaryPage` | 类型中文名映射 + 页脚提示改为枚举约束说明 |

> ⚠️ 读取路径与 `dict_type` 完全解耦（`WHERE dict_type = ?`），
> 故新增类型**无需迁移**——`rag_dictionary` 表本就按 `dict_type` 存多类。

### 2.4 测试调整（既有断言需改）

`RagManagementControllerTest.dictionaryListMarksEditable` 原本断言"固定 4 类"。
这是**预期内的行为变更**，已更新为：

- `assertEquals(8, types.size())`
- 新增顺序断言（五轴在前，对应界面标签页顺序）
- 派生字典仍只读的断言保留

---

## 三、待办 #8：知识点本体查询

### 3.1 问题

只有 `GET /kp-aliases?kpId=` —— 调用方必须**先知道 kpId** 才能查别名，
而管理台无从得知有哪些知识点。别名页只能做成"粘贴 UUID"，教研根本用不了。

### 3.2 设计：新增知识点列表端点

| 项 | 内容 |
|---|---|
| 方法/路径 | `GET /api/v1/management/knowledge-points?keyword=&limit=` |
| 返回 | `KnowledgePointBrief[] { kpId, subject, grade, canonicalName, aliasCount, prerequisiteCount }` |
| 授权 | 读上下文（不需票据） |
| 上限 | `limit ≤ 500` |

**⚠️ 只返回管理需要的最小字段**：不返回 `facets`（JSONB 大字段），
列表页不需要，传了只是浪费带宽。

**⚠️ 用 `queryTenantId()` 而非 `requireTenant()`**：
平台全局档（不选租户）也要能看知识点；`requireTenant()` 对非租户档直接抛错。

### 3.3 前端：别名页改为「选知识点 → 维护别名」

| 区域 | 内容 |
|---|---|
| 左：知识点表 | 规范名 / 学科·年级 / 别名数 / 先修数；支持按规范名搜索 |
| 右：别名维护 | 选中知识点的 kpId + 类别 + 别名增删 |
| 提示 | "别名过宽（如「函数」）会让无关片段都挂上来，反而降低检索精度" |

---

## 四、验证结果

| 验证项 | 结果 |
|---|---|
| 后端 8 模块 | ✅ 全 SUCCESS |
| rag-core / ports / application / infrastructure / api | 132 / 2 / 63 / 99 / 6 |
| rag-management | 236 全绿（含 1 处**预期内**断言更新） |
| rag-bootstrap | 41 全绿 |
| 前端 `tsc --noEmit` | ✅ 0 error |
| 前端测试 | ✅ 44 文件 / 460 用例全绿 |

> ⚠️ 本次**没有新增**测试用例：三项改动都是"把已有能力接出来"，
> 没有引入新不变式。唯一的行为变更是字典类型数量，已更新既有断言。

---

## 五、改动文件清单

```
rag/rag-core/src/main/java/cn/studykid/rag/core/retrieval/RetrievalResult.java
    + minScore 字段 + 2 个兼容构造器
rag/rag-application/src/main/java/cn/studykid/rag/application/retrieval/RetrievalOrchestrator.java
    + 传入实际阈值
rag/rag-api/src/main/java/cn/studykid/rag/api/retrieval/RetrievalResultDto.java
    + minScore + 2 个兼容构造器

rag/rag-management/src/main/java/cn/studykid/rag/management/ports/DictionaryPort.java
    + EDITION_PUBLISHER / SUBJECT / GRADE / REVISION
rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/JdbcDictionaryRepository.java
    isEditable 扩展为 5 个动态类型
rag/rag-management/src/main/java/cn/studykid/rag/management/api/RagManagementController.java
    + GET /knowledge-points
    类型列表 4 → 8

rag/rag-management/src/main/java/cn/studykid/rag/management/ports/KnowledgeAliasPort.java
    + listKnowledgePoints + KnowledgePointBrief
rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/JdbcKnowledgeAliasRepository.java
    + listKnowledgePoints 实现
rag/rag-management/src/main/java/cn/studykid/rag/management/api/ManagementDtos.java
    + KnowledgePointBriefDto

rag/rag-management/src/test/java/cn/studykid/rag/management/api/RagManagementControllerTest.java
    字典类型断言 4 → 8（预期内行为变更）

admin-console/src/api/ragTypes.ts
admin-console/src/api/ragClient.ts
admin-console/src/pages/rag/RagRecallPage.tsx
admin-console/src/pages/rag/RagDictionaryPage.tsx
admin-console/src/pages/rag/RagKpAliasPage.tsx   (重写为选择式交互)
```

---

## 六、待办进度

| # | 待办 | 状态 |
|---|---|---|
| 1 | 版本五轴落库 | ✅ RAG-D16 |
| 2 | 召回漏斗计数 | ✅ RAG-D17 |
| 3 | 门禁阈值随响应下发 | ✅ 本文档 |
| 4 | 版面事实持久化 | ✅ RAG-D19 |
| 5 | 索引代际蓝绿切换 | ✅ RAG-D18（含构建器） |
| 6 | 上传前 dry-run 预览 | ⛔ 未做 |
| 7 | 字典类型扩展 | ✅ 本文档 |
| 8 | 知识点本体查询 | ✅ 本文档 |

**剩余 #6**：上传前解析预览（不落库）。
这是唯一剩下的待办，且改动面较大——需要新增 `POST /documents/preview` 端点，
让解析器跑一遍但不写 `rag_chunk`。它的价值是"上传前就能看到切片效果"，
但当前"上传后看预览、不满意再重传"的流程已能覆盖大部分场景，优先级可再评估。
