# RAG-D16 版本五轴落库改造说明

| 字段 | 内容 |
|---|---|
| 文档编号 | RAG-D16 |
| 版本/状态 | v1.0 / 实装说明 |
| 日期 | 2026-11-04 |
| 依据 | RAG-D00 RD-03（版本五轴缺一不可入库）、RAG-D15 §六 待办 #1 |
| 改造目标 | 登记来源时把**版本五轴**写入 `rag_edition`，让来源拥有真实的版本坐标 |

---

## 一、改造前的问题

### 1.1 现象：来源没有版本坐标

改造前，`RegisterSourceRequest` **没有五轴字段**。来源登记后：

- `rag_source` 行被创建（有 title/publisher/isbn 等）；
- `rag_edition` **一行都没有**。

载体上传时才由 `resolveEdition` 兜底建一个"默认版本坐标"：

```sql
INSERT INTO rag_edition (tenant_id, source_id, subject, grade, term, publisher, revision_code, scope)
VALUES (..., '未指定', '全学段', '全一册', '未指定', 'src-<sourceId>', ...)
```

### 1.2 危害：偏差不可见，只在检索时暴露

这个默认坐标意味着：用户明明登记的是「沪教版 · 数学 · 七年级 · 上册」，
落库的却是「未指定 · 全学段 · 全一册」。后果：

| 下游环节 | 表现 |
|---|---|
| 检索期版本过滤 | `EditionScope.admits()` 比较五轴，`全学段` 与学生实际的 `七年级` 不匹配 → **按七年级搜不到这本书** |
| 引用展示 | 引文标注的版本是「未指定·全学段·全一册」，学生无法据此翻书核对 |
| 排查成本 | 报错点离真因极远（登记时无任何异常），只能靠人工逐条核对 `rag_edition` |

**这正是本次改造要消灭的「静默退化」。**

---

## 二、设计决策（已确认）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 版本坐标归属 | **隶属于来源**（`source_id` 已在 `rag_edition` 表中） | 一个来源（一套书）对应一个版本坐标；不同出版社就是不同来源。与既有 `resolveEdition` 逻辑最契合 |
| 存量来源 | **不动**，新登记才写真实五轴 | 回填需额外推断规则与人工确认，风险与收益不匹配；存量来源仍走默认坐标 |
| 五轴完整性 | **部分填写报 400**，全空则兼容 | 全空 = 存量/批量路径（须保持原行为）；部分填写 = 用户以为登记了某版本，实际会退化，必须拦下 |

---

## 三、后端改造

### 3.1 领域层：`SourceRegistration`

**字段扩展**（5 个新字段，全部可空以兼容存量调用方）：

| 字段 | 说明 |
|---|---|
| `subject` | 学科 |
| `grade` | 年级 |
| `term` | 册次（非空时须在白名单 `上册/下册/全一册` 内） |
| `revisionCode` | 版次 |
| `editionPublisher` | 版本出版社（缺省回落到来源 `publisher`） |

**新增方法**：

| 方法 | 作用 |
|---|---|
| `hasFullEdition()` | 五轴是否齐全（出版社轴缺省回落来源出版社） |
| `partiallyFilledEdition()` | 部分填写时返回缺失轴名；全空或齐全返回 `null` |
| `effectiveEditionPublisher()` | 生效的版本出版社 |
| `normalizedEditionSubject/Grade/Publisher()` | **枚举归一**（见 §3.3） |

**⚠️ 保留了 7 参数旧构造器**：批量导入（CSV/ZIP）与既有测试不走五轴，必须逐字节兼容。

### 3.2 DTO 层：`RegisterSourceRequest`

新增 `subject / grade / term / revisionCode / editionPublisher / trustTier / licenseStatus`，
每个带 `@Size` 约束；并保留 **8 参数旧构造器**避免破坏既有测试与调用方。

**⚠️ `trustTier` / `licenseStatus` 刻意被服务端忽略**：

> RAG-D07 §2.2 角色表：采集员**不得自行提升权威等级或置 VERIFIED**。
> 保留这两个字段只为接口前向兼容——登记落库恒为 `UNVERIFIED / PENDING`，
> 由后续「校验决定」流程提升。若将来要支持"带初始状态的导入"，
> 必须走独立的、有权限校验的端点，不能在登记上报口径上开口子。

### 3.3 关键防线：枚举归一（防止"一条登记把检索打挂"）

**⚠️⚠️ 这是一个在设计评审中才发现的真实风险，必须记录：**

检索期 `JdbcRetrievalRepository` 用枚举 `displayName()` 反查：

```java
private static Publisher publisherFromDb(String s) {
    for (Publisher v : Publisher.values()) {
        if (v.displayName().equals(s)) return v;
    }
    throw new IllegalStateException("未知出版社值: " + s);   // ← 检索直接 500
}
```

而 `rag_edition.publisher / subject / grade` 都是 **`text` 列，写入时不做校验**。
登记页填的是**出版机构名**（「人民教育出版社」），而枚举里是**版本体系名**（「人教版」）——
直接写入必然导致该书**任何一次检索都 500**，且报错点离真因极远。

**处置**：仓储层写入前统一走归一方法：

| 轴 | 合法值 | 非法值 → 兜底 |
|---|---|---|
| 学科 | `数学/语文/英语/物理/化学/生物/历史/地理/道法` | → `未指定` |
| 年级 | `一年级`…`九年级`、`高一/高二/高三` | → `全学段` |
| 出版社 | `人教版/苏教版/北师大版/华师大版/沪教版/鲁教版/鄂教版/其他` | → `其他` |

> 原则：**宁可版本号略粗，也不能让一条登记把检索打挂**。
> 原始值仍保留在 `rag_source.publisher`，可审计追溯。

### 3.4 仓储层：`registerSource` 同事务落库

```java
// 在既有 INSERT rag_source + 审计之后、同一事务内：
if (registration.hasFullEdition()) {
    upsertCompleteEdition(ch, sourceId, registration);
}
```

`upsertCompleteEdition` 用 `ON CONFLICT (tenant_id, subject, grade, term, publisher, revision_code) DO NOTHING`：

| 设计点 | 理由 |
|---|---|
| **upsert 而非纯 INSERT** | `rag_edition_unique` **不含 source_id**。两个来源登记同一套教材同一版次时，纯 INSERT 会撞唯一约束让登记失败；语义上它们本就该共享同一坐标 |
| **ON CONFLICT 不改 `source_id`** | 该列记录"谁最先建立坐标"，后来者复用。改它会让已有载体（FK 指向该 edition）的来源归属漂移 |
| **同一事务** | 来源行与版本坐标必须同生同灭。只写来源不写版本，就退化成 §1.1 的静默退化 |

### 3.5 控制器：部分填写拦截

```java
String missingAxes = registration.partiallyFilledEdition();
if (missingAxes != null) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "版本五轴缺轴：" + missingAxes + "（要么五轴填齐，要么全部留空；部分填写会退化成默认版本坐标）");
}
```

**⚠️ 单测抓到的真实缺陷**：初版把「出版社」也算进"是否填写"的判定，
而出版社缺省回落到必填的 `publisher`——导致**五轴全空被误判成"只差四轴"**，
所有存量登记路径会全部 400。已修正为只用四个显式轴判断（见 `emptyEditionIsNotFull` 测试）。

---

## 四、前端改造

`RagSourceRegisterPage` 已有五轴表单（RAG-D15 阶段已建模），本次做三处对齐：

| 改动 | 内容 |
|---|---|
| **出版社选项对齐枚举** | 原选项含「外研版/译林版」（**不在后端枚举内**，会被归一成「其他」）；改为 `人教版/苏教版/北师大版/华师大版/沪教版/鲁教版/鄂教版/其他` |
| **字段标签明确化** | 「出版社」→「出版社（版本体系）」，并注明"检索期按此值反查版本坐标" |
| **提交字段** | `editionPublisher` 取 `publisher` 值（版本出版社缺省回落来源出版社，避免让用户填两遍） |

**交互不变**：五轴未填齐时提交按钮禁用 + 实时显示 `(3/5)`。

---

## 五、端到端链路验证

改造后的完整链路：

```
① 登记来源（填五轴）
   └─ INSERT rag_source（来源行）
   └─ INSERT rag_edition（真实五轴坐标）   ← 本次新增
   └─ 同一事务，同生同灭

② 上传载体
   └─ resolveEdition(sourceId)
        SELECT edition_id FROM rag_edition WHERE source_id = ? ORDER BY created_at ASC LIMIT 1
        └─ ✅ 取到①写入的真实坐标（不再退化为默认坐标）

③ 检索
   └─ EditionScope.admits() 比较五轴
        └─ ✅ 学生问到「沪教版·数学·七年级·上册」能正确命中
```

**核心价值**：② 的 `resolveEdition` 会**优先取来源下已有的版本坐标**，
因此在 ① 落库真实五轴后，载体自动挂到正确坐标上——登记环节的改造自动惠及下游全链路。

---

## 六、验证结果

| 验证项 | 结果 |
|---|---|
| 后端编译（`rag-bootstrap -am`） | ✅ BUILD SUCCESS |
| `SourceRegistrationTest` | ✅ **20 项全绿**（原 10 + 新增 10） |
| 后端全量测试 | ✅ **236 项全绿**（改造前 226） |
| 前端 `tsc --noEmit` | ✅ 0 error |
| 前端测试 | ✅ **44 文件 / 460 用例全绿** |

### 新增测试覆盖（10 项）

| 测试 | 守护的语义 |
|---|---|
| `fullEditionDetected` | 五轴齐全时 `hasFullEdition` 为真 |
| `emptyEditionIsNotFull` | **全空不算部分填写**（防存量路径全部 400） |
| `partialEditionReportsMissingAxes` | 部分填写报出缺失轴 |
| `editionPublisherFallsBackToSourcePublisher` | 出版社轴回落来源出版社 |
| `illegalTermRejected` | 册次白名单校验 |
| `editionAxesAreTrimmedBeforeValidation` | 先归一后校验（带空白不被误判） |
| `legalEnumValuesKept` | 合法枚举值原样保留 |
| `institutionNameFallsBackToOther` | **出版机构名归一为「其他」，不会让检索 500** |
| `illegalSubjectAndGradeFallBack` | 非法学科/年级归一为兜底值 |
| `normalizedValuesAreAllResolvable` | 归一结果**必可被检索期安全反查** |

---

## 七、改动文件清单

```
rag/rag-management/src/main/java/cn/studykid/rag/management/core/SourceRegistration.java
    + 5 个五轴字段 + 旧构造器兼容
    + hasFullEdition / partiallyFilledEdition / effectiveEditionPublisher
    + normalizedEditionSubject / Grade / Publisher（枚举归一）
    + 册次白名单校验

rag/rag-management/src/main/java/cn/studykid/rag/management/api/ManagementDtos.java
    + RegisterSourceRequest 增 5 个五轴字段 + 2 个状态字段
    + 8 参数旧构造器兼容

rag/rag-management/src/main/java/cn/studykid/rag/management/api/RagManagementController.java
    + 五轴传入 SourceRegistration
    + 部分填写 → 400（含缺失轴名）

rag/rag-management/src/main/java/cn/studykid/rag/management/infrastructure/JdbcCorpusManagementRepository.java
    + upsertCompleteEdition（同事务写 rag_edition）
    + 写入走归一后的值

rag/rag-management/src/test/java/cn/studykid/rag/management/core/SourceRegistrationTest.java
    + 10 项五轴与归一测试

admin-console/src/pages/rag/RagSourceRegisterPage.tsx
    + 出版社选项对齐后端枚举
    + 标签明确为「出版社（版本体系）」
```

---

## 八、遗留与边界

| 项 | 说明 |
|---|---|
| **存量来源未回填** | 已有来源仍是默认坐标（`未指定/全学段/全一册`）。按约定不动；如需回填，应作为独立任务并提供人工确认，而非自动推断 |
| **来源 publisher 与版本 publisher 同源** | 当前登记页只有一个出版社输入，同时用于来源与版本坐标。若后续出现"同一出版社会有多个版本线"的真实需求，需拆成两个输入 |
| **批量导入未接五轴** | CSV/ZIP 批量登记仍走默认坐标（保持逐条独立成败语义）。若要让批量导入也带五轴，需扩展 `BulkSourceItem` 并处理逐行校验错误归类 |
| **`revision_code` 未校验格式** | 仅限制长度 ≤ 64，不强制格式。真实版次写法差异大（`2024修订` / `2024` / `第3版`），强制格式会误伤 |
