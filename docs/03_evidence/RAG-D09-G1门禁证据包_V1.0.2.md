# RAG-D09 G1 门禁证据包

| 字段 | 内容 |
|---|---|
| 门禁 | **G1「教辅语料被正确地变成可检索、可定位、可判角色的片段」** |
| 对应方案 | 《RAG语料录入线上化自动化方案 V1.0.2》S4-3 |
| 编制日期 | 2026-11-02 |
| 证据性质 | 自动化测试跑批 + 真实教材实测记录 |
| ⚠️ 前置声明 | 本文**仅汇总技术与自动化证据**，G1 正式签署仍需「项目负责人 + 独立评审（不得为 R1 实施方）」人工签署 |

---

## 一、G1 五条通过条件：逐条结论

| # | 通过条件 | 结论 | 证据 |
|---|---|---|---|
| ① | `DocumentParserPort` 有生产实现，且对 ≥1 册真实教辅 PDF 产出结构化块 | ✅ **满足** | §二 |
| ② | 切分不变式有自动化证据，**逐条有反证** | ✅ **满足** | §三（12 项测试） |
| ③ | 章节路径与字符区间**可回取原文**（随机 20 段核对） | ✅ **满足** | §四（20/20 × 2 册） |
| ④ | 答案区识别准确率 ≥95% 且**漏判 = 0** | 🟡 **逻辑已证，真实统计待补** | §五（7 项测试 + 限制说明） |
| ⑤ | RAG-D07 N-1（V2 迁移）已实施 | 🟡 **主体已落实，2 项待确认** | §六 |

---

## 二、① 生产级解析器 + 真实教材结构化块

### 2.1 实现

| 组件 | 类 | 说明 |
|---|---|---|
| 解析端口实现 | `DoclingDocumentParserPort` | 生产实现，**非 mock**；调用 docling-serve 1.38.2 / docling 2.142.1 |
| 响应映射 | `DoclingResponseMapper` | DoclingDocument → `LayoutBlock[]` |
| 分段提交 | 同上 | 20 页/段，整册区间连续化 |

### 2.2 三册真实教材实测（**零手工参数**）

| 册次 | 学科 | 页数 | 大小 | 耗时 | 块数 | 公式块 | 长路径 | 页眉噪声 | 区间不连续 |
|---|---|---|---|---|---|---|---|---|---|
| 沪教版 六年级上册 | 英语 | 162 | 16.6MB | 504s | 2123 | — | 0 | 0 | 0 |
| 沪教版 六年级下册 | 英语 | 148 | 13.4MB | 447s | 1871 | — | 0 | 0 | 0 |
| 沪教版 数学上册 | 数学 | 162 | 13.8MB | 736s | 2165 | 432 | 0 | 0 | 0 |

> **零手工参数**：不传 `--skip-pages` / `--toc-from` / `--toc-pages` / `--appendix-page`。
> 满足 G1 ① 的「至少 1 册」要求，实际做到 **3 册 / 2 学科**。

### 2.3 复现命令

```bash
cd deploy/docling && docker compose up -d
curl -fsS http://127.0.0.1:5001/health

curl -sS --max-time 1800 -X POST "http://127.0.0.1:5001/v1/convert/file" \
  -F "files=@samples/grade6-v2.pdf" -F "to_formats=json" \
  -F "do_table_structure=true" -F "do_pdf_heading_hierarchy=true" \
  -o /tmp/out.json -w "HTTP %{http_code} 耗时 %{time_total}s\n"
```

---

## 三、② 切分不变式的自动化证据（含反证）

测试类：`IngestInvariantEvidenceTest`（**12 项**，`rag-application`）

| 子项 | 正向用例 | **反证** |
|---|---|---|
| 噪声块丢弃 | BOILERPLATE 不产片段 | 把同一文本改成 PARAGRAPH → 产出 2 段，证明是**类型**驱动而非内容巧合 |
| 题号保留 | 自带题号保留 / 答案块**继承**上文题号 | 无上文时**不得凭空造题号** |
| 章节路径截断 | 多级推进后路径 ≤80 字符 | 8 层嵌套 → 深度仍 ≤4，证明截断生效 |
| 答案区角色 | 有题号→`EXERCISE_ANSWER`；无题号→`EXAMPLE_SOLUTION`；公式→`EXERCISE_ANSWER` | 同文本在**非答案区** → `EXPLANATION`，证明判定看**版面事实**而非文本 |

**关键防泄题反证**：
```java
assertFalse(chunks.get(0).role() == ChunkRole.EXPLANATION,
    "答案区无题号必须判 EXAMPLE_SOLUTION，不能判 EXPLANATION（会泄题）");
```

### 附：docling 解析层的不变式测试（**35 项**）

| 测试类 | 项数 | 锁定内容 |
|---|---|---|
| `DoclingRealTextbookMappingTest` | 5 | 真实教材的答案区占比、路径长度、页眉、九字段完整性 |
| `DoclingSectionHierarchyTest` | 10 | 同级替换、level 跳号、level 变小、页眉后缀、Contents、公式 orig、歌词误标 |
| `DoclingResponseMapperTest` | 11 | 真实响应映射、阅读顺序、字符区间连续、错误处理 |
| `DoclingAnswerZoneEvidenceTest` | 7 | 答案区正例/干扰项/边界（见 §五） |
| `DoclingSegmentedParseTest` | 2 | 分段区间连续化、默认参数 |
| `DoclingDocumentParserPortTest` | 5 | fail-closed、不回落占位解析器 |

---

## 四、③ 可回取原文（随机 20 段核对）

### 4.1 方法

用 `charStart/charEnd` 重建全文（各块首尾相接），再随机抽取 20 个块按区间切片，
与原始块文本逐字比对。**固定随机种子 `20261102` 保证可复现。**

### 4.2 结果

| 教材 | 通过 | 失败 | 全文长度 | 结论 |
|---|---|---|---|---|
| 沪教版英语六年级下册（148 页） | **20 / 20** | 0 | 56,713 字符 | ✅ |
| 沪教版数学上册（前 20 页） | **20 / 20** | 0 | 5,923 字符 | ✅ |

### 4.3 复现

```bash
# 需先跑 §2.3 得到 /tmp/out.json
java -cp "<classes>:<deps>" Recover /tmp/out.json
# 期望：结果: 通过 20 / 20, 失败 0
```

> ⚠️ 门禁要求的是「**人工**核对页码与位置全对」。本节的自动化比对证明了
> **字符区间自洽**（可精确还原），但**页码与印刷页的对应**仍需人工抽检确认。

---

## 五、④ 答案区识别（**限制说明**）

### 5.1 重要限制

**三册实测教材均无答案附录**（学生用书，答案单独成册）：

| 教材 | 疑似答案区标题数 |
|---|---|
| 英语上册 | 0 |
| 英语下册 | 0 |
| 数学上册 | 0 |

因此**无法用真实教材统计准确率**。用构造样本验证规则逻辑（`DoclingAnswerZoneEvidenceTest`，7 项）：

| 用例 | 期望 | 说明 |
|---|---|---|
| 题干含 `answers`（3 变体） | 不触发 | **实测曾误判 89%**，必须不触发 |
| `Answer Key` 标题 | 触发 | 正例 |
| `参考答案` 标题 | 触发 | 中文正例 |
| 裸 `Answers` 单词标题 | **触发** | 漏判反证——否则答案被判讲解 → 泄题 |
| 长段落提及参考答案 | 不触发 | 长度 >40 非标题 |
| `Answer the questions.` | 不触发 | 题干指令干扰项 |
| 答案区遇新标题 | 退出 | 防止下一章被整章误判 |

### 5.2 缺口

| 待补 | 说明 |
|---|---|
| 真实答案册样本 | 需至少 1 册**含答案附录**的教材，才能统计真实准确率 |
| 人工抽检记录 | 判定人 / 日期 / 片段 ID / 正确角色 / 错误类型（门禁证据形式要求） |

**结论**：规则逻辑正确，但 G1 ④ 的**正式统计证据仍缺**。

---

## 六、⑤ RAG-D07 N-1（V2 迁移）

| N-1 要求项 | DDL 现状 | 结论 |
|---|---|---|
| 来源级 + **载体级**状态冗余列 | `rag_source.ver_status` / `rag_document.ver_status` 均存在 | ✅ |
| 扩展部分索引 | `rag_source_tenant_isbn_uq` / `rag_document_tenant_content_hash_uq`（V1.0.2 新增） | ✅ |
| `quote_present` 约束 | 存在（1 处） | ✅ |
| `invalidated_at` / `invalid_reason` | `revoked_at` / `revoked_reason` 已替代 | ✅ |
| `ver_reject_reason` / `review_started_at` | 均存在 | ✅ |
| `license_status` 改枚举 | `rag_license_status` 枚举 | ✅ |
| `ON DELETE CASCADE` 改 `RESTRICT` | **仍有 10 处 CASCADE** | 🟡 **待确认** |

**🟡 待确认点**：删除目前只走状态机（`REVOKED` 终态），管理面无物理删除端点，
因此 `CASCADE` 实际不会被触发。但**若将来引入物理清理**，`CASCADE` 会连带删掉审计链。
建议保持现状并由 DBA 侧确认，或在 V2 迁移中显式改为 `RESTRICT`。

---

## 七、总体结论

| 条件 | 状态 |
|---|---|
| ① 生产解析器 + 真实块 | ✅ 满足（3 册 / 2 学科） |
| ② 切分不变式 + 反证 | ✅ 满足（12 项 + 35 项解析层） |
| ③ 可回取原文 | ✅ **自动化部分满足**（20/20 × 2）；人工抽检待补 |
| ④ 答案区准确率 | 🟡 **逻辑已证，真实统计缺样本** |
| ⑤ N-1 迁移 | 🟡 **主体落实，CASCADE 待确认** |

### 建议

**G1 可判定为「技术证据齐备，待补 3 项人工/组织证据」**：

1. **③ 的人工抽检**：随机 20 段的人工页码核对记录；
2. **④ 的答案册样本**：1 册含答案附录的教材 + 准确率统计；
3. **人工抽检签署**：由**非 R1 实施方**的评委签署。

这三项都不是代码问题，需要业务侧投入。在它们完成前，G1 不能正式关闭。

---

## 八、证据清单（可复跑）

```bash
# 1) 解析层不变式（35 项）
mvn -o -pl rag-infrastructure test -Dtest='Docling*Test'

# 2) 应用层切分不变式（12 项）
mvn -o -pl rag-application test -Dtest='IngestInvariantEvidenceTest'

# 3) 全量回归
mvn -o -pl rag-core,rag-ports,rag-application,rag-infrastructure,rag-management,rag-worker -DskipITs test
```

当前全量：**后端 408 项全绿 / 前端 562 项全绿**。

| 模块 | 测试数 |
|---|---|
| rag-core | 108 |
| rag-ports | 2 |
| rag-application | 59 |
| rag-infrastructure | 92 |
| rag-worker | 3 |
| rag-management | 144 |
| **合计** | **408** |
