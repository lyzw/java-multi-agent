# RAG-D17 召回漏斗计数改造说明

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D17 |
| 版本/状态 | v1.0 / 实装说明 |
| 日期 | 2026-11-04 |
| 依据 | RAG-D05 §7（召回质量可观测性）、RAG-D12 靶点 D、RAG-D15 §六 待办 #2 |
| 改造目标 | 检索响应携带**过滤漏斗计数**，让「没结果」可被归因为召回不足还是过滤过严 |

---

## 一、改造前的问题

### 1.1 现象：只给结果，不给过程

改造前 `RetrievalResultDto` 只有四项：

```java
record RetrievalResultDto(
    List<HitDto> hits,
    boolean versionMismatchDetected,
    boolean answerContentSuppressed,
    String primaryEdition)
```

编排器 `RetrievalOrchestrator` 的每一步其实都有中间 `List`，但**计数留在局部变量里，从未对外传递**。

### 1.2 危害：诊断问题答不了

管理台「召回解释（质检）」页要回答的核心问题是：

> **这次没结果，是语料里没有，还是被过滤光了？**

这两者的处置**完全相反**：

| 真实原因 | 处置 |
|---|---|
| 语料里确实没有 | 补录语料 |
| 被版本过滤光 | 学生用的那一版没进语料 / 版本范围配置错 |
| 被角色过滤光 | 正常（防泄题），或切分把正文误判成答案 |
| 被门禁过滤光 | 语料有但不够相关 → 补语料或复核门禁阈值 |

只给 `hits` 长度，运维**无法判断该往哪个方向调**。原页面只能挂一条「漏斗计数尚未接入」的说明——诚实，但无用。

---

## 二、设计

### 2.1 数据模型：`RetrievalResult.RetrievalFunnel`

```java
record RetrievalFunnel(
    int candidates,    // ① 多路召回原始候选（含知识点补全引入）
    int afterVersion,  // ② 版本硬过滤后剩余
    int afterRole,     // ③ 角色硬过滤后剩余（泄题闸门）
    int afterGate,     // ④ 分数门禁后剩余
    int returned)      // ⑤ topK 截断后实际返回
```

**三条设计决策**：

| 决策 | 理由 |
|---|---|
| **语义是「剩余」不是「剔除」** | 界面要画逐层收窄的漏斗；缺失某层（如未走知识点补全）可直接沿用上一层值 |
| **紧凑构造器强制单调不增** | 某层比上一层还多，说明计数点取错了 `List`。与其静默传播一个自相矛盾的漏斗（会让"诊断"本身失真），不如当场抛 `IllegalArgumentException` |
| **内置 `dominantDrop()` 归因** | 见 §2.2 |

### 2.2 为什么归因放在后端

```java
public String dominantDrop() {
    int v = droppedByVersion(), r = droppedByRole(), g = droppedByGate();
    if (v == 0 && r == 0 && g == 0) return "NONE";
    if (r >= v && r >= g) return "ROLE";
    return v >= g ? "VERSION" : "GATE";
}
```

**⚠️ 若让前端比较三个差值，两处算法必然漂移**——而"诊断结论"出错比不显示更危险：
它会把人引向错误的处置（例如本该补语料，却被归因成门禁太严而去调阈值）。

归因放在领域层，前端只做**翻译**（`ROLE` → "主因：角色过滤（泄题闸门）" + 处置提示）。

### 2.3 向后兼容

`RetrievalResult` 与 `RetrievalResultDto` 都是 record，新增字段会破坏既有构造。

处置：**同时保留旧签名构造器**，漏斗缺省为 `empty(size)`。

```java
// 旧调用方（测试/主干）不传漏斗 → 不因缺字段而抛异常
public RetrievalResult(chunks, kps, edition, versionMismatch, suppressed) { ... }
```

`hits` 的构造点全仓只有 1 处生产代码（`RetrievalOrchestrator`）与 1 处 DTO 映射，影响面小；
但仍保留兼容构造器，避免任何外部调用方（含未来接入方）被迫改签名。

---

## 三、后端改动

### 3.1 领域层 `RetrievalResult`（rag-core）

- 新增 `RetrievalFunnel` record（含单调性校验、`droppedBy*`、`dominantDrop`）
- `RetrievalResult` 增 `funnel` 字段 + 旧构造器兼容

### 3.2 编排器 `RetrievalOrchestrator`（rag-application）

在 ⑦ 门禁之后、构造结果之前采集计数：

```java
List<ScoredChunk> returned = above.stream().limit(request.topK()).toList();
RetrievalResult.RetrievalFunnel funnel = new RetrievalResult.RetrievalFunnel(
        candidates.size(), versionFiltered.size(), roleFiltered.size(),
        above.size(), returned.size());
```

**⚠️ 计数点必须紧贴各环节的 `List`，不能事后重算**：重算等于把过滤规则再实现一遍，
两份实现必然漂移，表现为「漏斗显示过滤掉 0 条，结果却是空的」。

顺带修正一处：原来 `above.stream().limit(topK)` 在 `return` 语句里现算，
现在先绑定到 `returned` 变量——**保证计数与返回的是同一个列表**。

### 3.3 DTO `RetrievalResultDto`（rag-api）

新增 `FunnelDto`，除五个余量外还**预计算**三个剔除数与归因：

```java
record FunnelDto(
    int candidates, int afterVersion, int afterRole, int afterGate, int returned,
    int droppedByVersion, int droppedByRole, int droppedByGate,
    String dominantDrop)
```

> 前端不必自己做减法，减少一处可能算错的逻辑。

---

## 四、前端改动

### 4.1 类型（`ragTypes.ts`）

- `RagRetrievalResponse.funnel?: RagRetrievalFunnel | null` —— **可选**（旧版后端无此字段）
- 新增 `RagRetrievalFunnel` 接口

### 4.2 组件（`ragVisuals.tsx`）

| 组件 | 改动 |
|---|---|
| `FunnelStep` | 增 `dropped?`（相对上层的剔除数） |
| `RecallFunnel` | 有剔除的层用橙色标签 + tooltip 显示「剩 N 条（本层剔除 M 条）」 |
| **`RecallFunnelSummary`**（新） | 五段漏斗 + 归因结论标签 + 处置提示 |

归因文案映射（前端只翻译，不计算）：

| `dominantDrop` | 标签 | 处置提示 |
|---|---|---|
| `NONE` | 各层均无剔除 | 候选全部通过，条数由 topK 决定 |
| `ROLE` | 主因：角色过滤（泄题闸门） | 多为答案类 → **防泄题的正常结果，不是故障** |
| `VERSION` | 主因：版本过滤 | 很可能学生那版**尚未录入语料** |
| `GATE` | 主因：分数门禁 | 语料有但不够相关 → 补语料或复核门槛 |

### 4.3 召回解释页（`RagRecallPage`）

把原来那条「过滤漏斗计数尚未接入」的 `Alert` 替换为真实漏斗：

```tsx
{result.funnel ? <RecallFunnelSummary funnel={result.funnel} /> : <Alert ...未返回... />}
```

> 旧版后端不返回 `funnel` 时仍显示说明——**不编造数字**的纪律保持不变。

---

## 五、验证结果

| 验证项 | 结果 |
|---|---|
| 后端全栈编译（8 模块） | ✅ BUILD SUCCESS |
| `RetrievalFunnelTest` | ✅ **11 项全绿** |
| rag-core 测试 | ✅ 119 项 |
| rag-api 测试 | ✅ 6 项 |
| rag-management 测试 | ✅ 236 项 |
| rag-bootstrap 测试 | ✅ 41 项 |
| 前端 `tsc --noEmit` | ✅ 0 error |
| 前端测试 | ✅ **44 文件 / 460 用例全绿** |

### 新增测试覆盖（11 项）

| 分组 | 测试 | 守护的语义 |
|---|---|---|
| 单调性 | `acceptsNarrowingFunnel` | 正常收窄被接受 |
| | `rejectsNonMonotonicFunnel` | **某层比上层多 → 拒绝**（计数点写错） |
| | `rejectsReturnedExceedingGate` | 返回数不得超门禁数 |
| | `allowsEqualCounts` | 相等合法（某层未剔除） |
| 归因 | `computesDropCounts` | 各层剔除数正确 |
| | `roleIsDominant` | 角色剔除最多 → `ROLE` |
| | `versionIsDominant` | 版本剔除最多 → `VERSION` |
| | `gateIsDominant` | 门禁剔除最多 → `GATE` |
| | `noneWhenNothingDropped` | 无剔除 → `NONE` |
| | `emptyFunnelIsNeutral` | 空漏斗不误导 |
| 兼容 | `legacyConstructorStillWorks` | **旧构造器（无漏斗）仍可用** |

---

## 六、改动文件清单

```
rag/rag-core/src/main/java/cn/studykid/rag/core/retrieval/RetrievalResult.java
    + RetrievalFunnel（单调校验 / droppedBy* / dominantDrop）
    + RetrievalResult.funnel 字段 + 旧构造器兼容

rag/rag-application/src/main/java/cn/studykid/rag/application/retrieval/RetrievalOrchestrator.java
    + ⑧ 漏斗计数采集（紧贴各环节 List）
    + returned 变量绑定（保证计数与返回同一个列表）

rag/rag-api/src/main/java/cn/studykid/rag/api/retrieval/RetrievalResultDto.java
    + FunnelDto（含预计算的剔除数与归因）
    + 旧构造器兼容

rag/rag-core/src/test/java/cn/studykid/rag/core/RetrievalFunnelTest.java   (新增，11 项)

admin-console/src/api/ragTypes.ts
    + RagRetrievalFunnel / RagRetrievalResponse.funnel?

admin-console/src/pages/rag/ragVisuals.tsx
    + FunnelStep.dropped
    + RecallFunnel 增强（剔除数 + tooltip）
    + RecallFunnelSummary（新）+ 归因文案映射

admin-console/src/pages/rag/RagRecallPage.tsx
    - 「漏斗计数尚未接入」占位
    + 真实漏斗摘要（缺省时仍显示说明，不编造）
```

---

## 七、边界与后续

| 项 | 说明 |
|---|---|
| **漏斗不含「知识点补全」独立层** | 补全引入的片段计入 `candidates`（它们确实进入了候选池），但不单独计数。若后续要区分「直接命中 vs 补全引入」，需再拆一层 |
| **门禁阈值 0.38 仍未随响应下发** | 前端仍硬编码 `RAG_SCORE_THRESHOLD`。这是 RAG-D15 §六 待办 #3，改动很小但需同时调整前端刻度组件 |
| **漏斗只覆盖检索编排** | 生成期（`GroundedGenerationService`）的过滤未纳入。若需要端到端漏斗，需在生成侧另加计数 |
