#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第三十批 C30-1 文档口径修正：修掉 00 文档里七处**如实性/排版**问题。

⚠️ 本批**不改任何实现**，只改文档口径。五处修正各自独立：

① **断表**（00 原 358 行）：「历批修正的设计缺陷」表中间多了一个**空行**，
   把表格切成两块 —— 后一块（第十七批起）**没有表头与分隔行**，
   渲染出来是「一堆孤立的表格行」。这是 `scripts/doc-table-check.py`
   一直在报的那一处基线遗留。
   **修法**：删掉那个空行（表格连续，结构合法）。

② **孤儿表格行**（00 原 318 行）：`| 决策项 1/2/3 | ... |` 这行是**表格行**，
   却夹在引用块（`>` 开头）里、且下一行就是小节标题 ——
   它既不属于任何表格，也不属于那个引用块。
   且它的内容（`SEQUENTIAL` 是否进 P2、最大深度与预算默认值、状态机与取消语义
   仍未冻结）**已在「评审前置决策」编号列表里**（决策项 1/2/3），
   属于**重复登记**，且排版上完全落单。
   **修法**：删掉该行（连同它前面那个空行，避免留下连续两个空行）。

③ **重复条目**（00 原 248/249 行）：「未实现（下一批）」表里有**两条**
   `FR-027 SEQUENTIAL 串行聚合`，讲的是同一件事（多子结果聚合未实现）。
   **修法**：合并成一条，把两段各自的有效信息都保留。

④ **已消解却仍写「待补」**（00 原 140 行）：「父子关系投影」那行的证据列写着
   「同上，DB 层用例待补 IT」—— 但 DB 层用例**早已补上**
   （第十九批的 `MultiAgentPersistenceIT`，本机真实 PG17 真跑）。
   **一处已经不成立的「待补」比一处真缺口更有害**：它会让读者以为这条线还开着。
   **修法**：改为指向真实的证据。

⑤ **同一句「DB 层用例待补 IT」的两处，性质完全不同，必须分开处置**
   （00 原 139 行 / 177 行）：
   · **原 139 行（FR-022 调用记录与幂等/接管）= 陈旧声明**。
     `agent_invocation` 的 DB 层用例**早已补齐**（第十九批
     `MultiAgentPersistenceIT`：幂等、fencing CAS、接管提升在途世代、RLS 隔离）。
     **修法**：同 ④，指向真实证据。
   · **原 177 行（Run 级预算的读口径）= 真实缺口，⚠️ 不能抹掉**。
     `JdbcRunBudgetRepository.find()` 的**三态聚合口径**
     （`RESERVED` 计入预留额 / `SETTLED` 计入结算额 / `RELEASED` 不计入）
     **在真实 PG 上确实还没有用例**：`JdbcRunBudgetCapIT` 覆盖的是**预留侧封顶**，
     它对 `find()` 的断言仅是弱不变量（`available ≥ 0`），验证不了聚合口径。
     **修法**：**保留「待补」并写准** —— 指名缺哪一条、为什么重要、现有证据到哪一步。
     ⚠️ 若把真缺口也一并抹掉，本批就从「修正口径」变成「粉饰口径」，
     这正是文档纪律要防的事：未实现项必须如实标注。

⑥ **「未实现（下一批）」表形态不一致（00 第 244 行表）**：
   这张表的**表头只有两列**（`| 项 | 缺口 |`），而**17 个数据行是三列** ——
   多出来的那一格装的是「证据」（测试类名 + 例数，或文档/javadoc 标注位置），
   渲染时会挤进最后一列、且**没有列名**。
   ⚠️ **根因不是「表头少了一列」，而是「数据行多了一列」**：
   同表**另外 11 个数据行本来就是两列**，它们的证据**本来就写在缺口格里**
   （如「`RunBudgetProbeTest` 7 例 / `JdbcRunBudgetCapIT` 4 例」那种行内引用）。
   也就是说这张表**自己声明的形态就是两列**，17 行是后来补证据时漏折的。
   **修法**：把 17 个三列行的第三格**折进第二格**（`…；**证据**：<原第三格>`），
   全表统一为两列 —— **不引入空单元格**（给另外 11 行留空第三格会让人误以为
   「那几行没有证据」，而它们的证据就在缺口格里），也不新增表头列名。
   ⚠️ 折叠是**结构化**的（按表块定位 + 按表头列数判定），不是逐行文本锚点 ——
   17 行逐个写锚点既易错又难复核。

⑦ **数据行比表头多一格（00 第 320 行表 · C18-1 两行）**：
   「历批修正的设计缺陷」表是 `缺陷 | 现象 | 修正` **三列**，
   而 C18-1 的两行是**四格** —— 「修正」与「证据」各占一格，
   多出来的那格会挤进最后一列，把两件事混成一格。
   同表其余各行都是**把证据折进「修正」格**里写的（带括号），
   这两行是漏折。
   **修法**：把第四格折进第三格，与同表其余行一致。

⚠️ 门禁同步升级：`scripts/doc-table-check.py` 增两条判据 ——
  **列数一致性**（⑥⑦ 正是它要拦的）与**孤立表格行**
  （旧版要求「块至少两行」，**恰好漏掉了 ② 那类**：一行 `| ... |`
  不是表格，渲染器会当普通段落输出）。
  ⚠️ 同时补两个**会误伤合法文档**的坑：跳过**代码围栏**
  （`P0-D06` 的代码块里有 ASCII 矩阵），并**剔除转义竖线**后再数列
  （06 的 `` `native` \| `mcp` `` 里 `\|` 是内容、不是分隔符）。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
"""
import os
import pathlib

# 文档目录：默认取本仓的「多级 Agent优化」目录；
# ⚠️ 允许用环境变量 `DOC_C30_1_BASE` 指向**别处**（反证脚本据此在草稿副本上
#    验证「重复登记会被拒绝」这类 fail-closed 行为）——
#    不动真实文档，就没有「改乱了忘了还原」的风险。
DEFAULT_BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")
BASE = pathlib.Path(os.environ.get("DOC_C30_1_BASE", str(DEFAULT_BASE)))
F00 = BASE / "00_修订说明与评审决议项.md"

MARK = "<!-- C30-1 -->"

_PATCHED = False


def _done() -> bool:
    global _PATCHED
    if _PATCHED:
        return True
    if MARK in F00.read_text():
        _PATCHED = True
        return True
    return False


def _sub_once(text: str, old: str, new: str, where: str) -> str:
    if old not in text:
        raise SystemExit(f"{where} 待替换文本未命中：{old[:70]}")
    if text.count(old) != 1:
        raise SystemExit(f"{where} 待替换文本不唯一：{old[:70]} (命中 {text.count(old)})")
    return text.replace(old, new, 1)


def _fold_extra_cells(text: str, header_exact: str, expect_folded: int, where: str) -> str:
    """把「比表头多一列」的数据行折叠进最后一格，统一表块形态。

    ⚠️ **为什么结构化而不是逐行锚点**：要折的行有十几条，逐行写文本锚点
    既容易写错、又难以复核（漏一条就留下一个不一致的行）。按**表头列数**判定，
    则「多出来的列」无论有多少、都在同一处被折掉。

    ⚠️ **转义竖线**：Markdown 表格里 `\\|` 是**内容**、不是分隔符，
    计数前必须先换成占位符，否则会把内容里的竖线误当成列边界。

    ⚠️ **fail-closed**：折的行数与预期不符即报错退出 ——
    「以为折了 17 行、实际折了 16 行」这种偏差必须显式失败，不能默默放过。
    """
    lines = text.split("\n")
    out: list[str] = []
    folded = 0
    i = 0
    n = len(lines)
    while i < n:
        if lines[i].strip() != header_exact:
            out.append(lines[i])
            i += 1
            continue
        head = i
        j = i
        while j < n and lines[j].lstrip().startswith("|"):
            j += 1
        block = lines[head:j]
        if len(block) < 2:
            raise SystemExit(f"{where} 表块只有一行：{header_exact}")
        head_n = len(block[0].split("|")) - 2
        sep_n = len(block[1].split("|")) - 2
        if sep_n != head_n:
            raise SystemExit(f"{where} 分隔行列数({sep_n}) ≠ 表头列数({head_n})")
        new_block = [block[0], block[1]]
        for row in block[2:]:
            raw = row.replace("\\|", "\x00")
            cells = raw.split("|")[1:-1]
            if len(cells) == head_n:
                new_block.append(row)
            elif len(cells) == head_n + 1:
                # ⚠️ 占位符（转义竖线）在**每一格**里都可能出现，
                #    还原必须覆盖整行，不能只还原被折进去的那一格。
                tail = cells[-2].rstrip()
                sep = "" if tail.endswith("；") else "；"
                merged = f"{tail}{sep}**证据**：{cells[-1].strip()}"
                head_cell = cells[0].strip()
                row_out = f"| {head_cell} | {merged} |"
                new_block.append(row_out.replace(chr(0), chr(92) + "|"))
                folded += 1
            else:
                raise SystemExit(
                    f"{where} 数据行列数异常（{len(cells)} 列，表头 {head_n} 列）：{row[:70]}"
                )
        if folded != expect_folded:
            raise SystemExit(f"{where} 折叠行数不符：期望 {expect_folded}，实际 {folded}")
        out.extend(new_block)
        i = j
    return "\n".join(out)


# ── ① 断表：删掉「历批修正的设计缺陷」表中间那个多余空行 ──────────────
#    ⚠️ 用**上下文锚点**而不是「第 N 行」：行号会随其它改动漂移。

# ── ② 孤儿表格行：删掉 `| 决策项 1/2/3 | ... |` 及其前面的空行 ──────────
OLD_ORPHAN = """> 既有测试污染（E2E 上下文向真实队列发布消息 → 通道切换守卫 fail-closed），
> 在上一提交上同样红，与本批改动无关。
| 决策项 1/2/3 | `SEQUENTIAL` 是否进 P2、最大深度与预算默认值、状态机与取消语义仍未冻结 |

### 历批修正的设计缺陷（已改，记录在案）"""

NEW_ORPHAN = """> 既有测试污染（E2E 上下文向真实队列发布消息 → 通道切换守卫 fail-closed），
> 在上一提交上同样红，与本批改动无关。

### 历批修正的设计缺陷（已改，记录在案）"""

# ── ③ 重复条目：合并两条 `FR-027 SEQUENTIAL 串行聚合` ──────────────────
OLD_DUP = """| FR-027 `SEQUENTIAL` 串行聚合 | **本批未做，且是刻意不做**：`SEQUENTIAL` 是否纳入 P2 属**未闭环的评审前置决策**（决策项 1；MA-08 明确「验收后决定是否开放 `SEQUENTIAL`」）。未冻结的决策不自行推进；多子结果聚合（把多个子 Agent 的输出合成一份交父）待决策闭环后再实施。当前一次委派只对应一个目标 |
| FR-027 `SEQUENTIAL` 串行聚合 | 失败策略已生效，但**多子结果聚合**（把多个子 Agent 的输出合成一份交父）未实现：当前一次委派只对应一个目标 |"""

NEW_DUP = """| FR-027 `SEQUENTIAL` 串行聚合 | **多子结果聚合**（把多个子 Agent 的输出合成一份交父）未实现：当前一次委派只对应一个目标。⚠️ **本批未做，且是刻意不做**：`SEQUENTIAL` 是否纳入 P2 属**未闭环的评审前置决策**（决策项 1；MA-08 明确「验收后决定是否开放 `SEQUENTIAL`」），未冻结的决策不自行推进；多子结果聚合待决策闭环后再实施。（失败策略本身已生效，缺的是**多子结果的合成**） |"""

# ── ④ 已消解的「待补」：父子关系投影行 → 指向真实证据 ──────────────────
OLD_PENDING = """| 父子关系投影 | `CapAgentChildRefPort` + `JdbcCapAgentChildRefRepository` + 发布事务内写入 | 同上，DB 层用例待补 IT |"""

NEW_PENDING = """| 父子关系投影 | `CapAgentChildRefPort` + `JdbcCapAgentChildRefRepository` + 发布事务内写入 | `MultiAgentPersistenceIT`（**第十九批已补齐** · 本机真实 PG17 真跑）：投影可写入并按声明顺序读回 / 悬空子引用被拒 / 重复边被幂等挡下 / RLS 跨租户隔离 |"""

# ── ⑤a 陈旧声明：FR-022 行「DB 层用例待补 IT」→ 指向真实证据 ───────────
OLD_STALE_FR022 = """| FR-022 调用记录与幂等/接管 | `AgentInvocationPort` + `JdbcAgentInvocationRepository`（幂等插入、`fencing_token` CAS 终态写入；**写入结果三态可区分**） | 端口契约 + `DelegationExecutorTest`；DB 层用例待补 IT |"""

NEW_STALE_FR022 = """| FR-022 调用记录与幂等/接管 | `AgentInvocationPort` + `JdbcAgentInvocationRepository`（幂等插入、`fencing_token` CAS 终态写入；**写入结果三态可区分**） | 端口契约 + `DelegationExecutorTest`；DB 层：`MultiAgentPersistenceIT`（**第十九批已补齐** · 本机真实 PG17 真跑）：根/子调用可写入且父子关系可据此重建 / 重复投递幂等（MA-T10）/ 旧世代提交终态被拒、新世代可提交（MA-T12 fencing CAS）/ 接管只提升在途记录的世代令牌（不改写终态）/ RLS 跨租户隔离 |"""

# ── ⑤b 真实缺口：Run 级预算读口径行 → **保留「待补」并写准** ────────────
OLD_REAL_GAP = """| **Run 级预算的读口径（按 Run 聚合）** | `RunBudgetPort` + `JdbcRunBudgetRepository`：`run.budget_amount` 取额度，`budget_reservation` 按 `run_id` 聚合已用量（单条 SQL 取齐，避免看到不一致的两个快照）。**「全调用树共享」天然成立**：所有深度的工具调用都记在同一个 `run_id` 下，嵌套 Agent 无法通过「自己再开一份额度」绕过上限 | 端口契约 + 聚合口径（DB 层用例待补 IT） |"""

NEW_REAL_GAP = """| **Run 级预算的读口径（按 Run 聚合）** | `RunBudgetPort` + `JdbcRunBudgetRepository`：`run.budget_amount` 取额度，`budget_reservation` 按 `run_id` 聚合已用量（单条 SQL 取齐，避免看到不一致的两个快照）。**「全调用树共享」天然成立**：所有深度的工具调用都记在同一个 `run_id` 下，嵌套 Agent 无法通过「自己再开一份额度」绕过上限 | 端口契约 + `RunBudgetAggregate`（**全仓唯一占用口径**）+ `BudgetBalanceProbeTest` 8 例（其中 4 条是 Run 级判定口径）。⚠️ **DB 层读口径仍缺（真缺口，非陈旧声明）**：`JdbcRunBudgetRepository.find()` 的**三态聚合**（`RESERVED` 计入预留额 / `SETTLED` 计入结算额 / `RELEASED` **不**计入）**在真实 PG 上尚无用例** —— 现有 `JdbcRunBudgetCapIT` 覆盖的是**预留侧封顶**，其对 `find()` 的断言仅是弱不变量（`available ≥ 0`），验证不了聚合口径；口径错一个状态就会把「还剩多少」算错，**上限随之形同虚设** |"""

# ── ⑥ 「未实现」表：把多出来的那一格折进缺口格（全表统一两列） ─────────
#    ⚠️ 走**结构化**折叠（按表头定位 + 按列数判定），不逐行写文本锚点。
OLD_HEADER = "| 项 | 缺口 |"

# ── ⑦ 数据行比表头多一格：C18-1 两行的「证据」折进「修正」格 ────────────
OLD_EXTRA_CELL_1 = """并专门补了根路径的回归保护用例 | `CapExecutionFlowTest#childToolCallSucceeds`（反证：改回老口径 → 3 条红） |"""

NEW_EXTRA_CELL_1 = """并专门补了根路径的回归保护用例（`CapExecutionFlowTest#childToolCallSucceeds`，反证：改回老口径 → 3 条红） |"""

OLD_EXTRA_CELL_2 = """不静默漂移」 | `CapExecutionFlowTest#childDoesNotDriftToNewRelease`（反证：校验器漂移 / 准入漂移 / Phase A 不下传 → 各恰 1 条红） |"""

NEW_EXTRA_CELL_2 = """不静默漂移」（`CapExecutionFlowTest#childDoesNotDriftToNewRelease`，反证：校验器漂移 / 准入漂移 / Phase A 不下传 → 各恰 1 条红） |"""

# ── ⑧ 在「实现状态快照」标题下登记本批性质（文档批次，不改实现）──────────
OLD_SNAPSHOT = """## 实现状态快照（2026-10-25 · 代码落地第二十九批：管理台的编排声明与契约登记表单）

> ⚠️ **口径**：本节只登记**已落地且有自动化证据**的部分。"""

NEW_SNAPSHOT = """## 实现状态快照（2026-10-25 · 代码落地第二十九批：管理台的编排声明与契约登记表单）

> ⚠️ **第三十批（C30-1）是文档口径修正批次**：**不改任何实现**，只修交付文档自身的
> 结构与口径缺陷（断表 / 孤儿表格行 / 重复条目 / 已消解却仍写「待补」/ 表格列数不一致），
> 并把表格结构门禁（`scripts/doc-table-check.py`）升级为能拦住这些缺陷。
> 详见文末「历批修正的设计缺陷」表的第三十批行。

> ⚠️ **口径**：本节只登记**已落地且有自动化证据**的部分。"""

# ── ⑨ 把本批登记进「历批修正的设计缺陷（已改，记录在案）」表 ────────────
OLD_DEFECT_HEADER = "| 缺陷 | 现象 | 修正 |"

NEW_DEFECT_ROW = """| **交付文档自身的结构与口径缺陷（第三十批修 · C30-1）** | 交付文档**自己**有四类缺陷，**读的人看不出来、任何测试也不会红**：① 「历批修正的设计缺陷」表中间多一个空行 → 后半段表格塌成普通段落（无表头、无分隔行）；② 有一行 \\| 决策项 1/2/3 \\| ... \\| 夹在引用块与小节标题之间 → 既不属于任何表格、也不是表格的一部分（渲染成普通段落），且其内容与「评审前置决策」1/2/3 **重复登记**；③ 「未实现（下一批）」表里有**两条** `FR-027 SEQUENTIAL 串行聚合` 讲的是同一件事；④ 两条**早已补齐**的 DB 层用例仍写着「DB 层用例待补 IT」（`agent_invocation` 与父子关系投影的 DB 层用例第十九批已用 `MultiAgentPersistenceIT` 在真实 PG17 上跑齐）—— **一处不成立的「待补」比一处真缺口更有害**：它会让读者以为这条线还开着 | 七处修正（`scripts/doc-c30-1.py`，可重复执行）：①②③④ 如左；⑤ 「未实现」表**17 个多出一列**的数据行**折平**为两列（证据并入缺口格）—— 该表**表头本来就是两列**、另 11 行也是两列，多出来的那列是后补证据时漏折的，故按表头列数折平、**而不是**给表头加一列（加一列就得给另外 11 行留空单元格，而那 11 行的证据本就在缺口格里，留空等于误报「无证据」）；⑥ 「历批修正」表**2 个多出一格**的行折平为三列；⑦ Run 级预算读口径的「DB 层用例待补」是**真缺口**（`JdbcRunBudgetRepository.find()` 的三态聚合口径在真实 PG 上确无用例；`JdbcRunBudgetCapIT` 覆盖的是预留侧封顶、对 `find()` 仅断言弱不变量）→ **保留并写准**，不抹掉。⚠️ 门禁同步升级（`scripts/doc-table-check.py`）：新增**列数一致性**与**孤立表格行**两条判据（旧版要求「块至少两行」，恰好漏掉②那一类），并补上「跳过代码围栏」「剔除转义竖线后再计数」两个**防误伤**处理 ⚠️ **本批只动 00 文档**：06 / 07 / 08 / 09 **明确不写** —— 本批不涉及配置数据模型、执行链、安全验收矩阵与需求追踪（无新需求落地、无新验收证据），按各文档的职责边界不硬塞内容稀释分工 `scripts/doc-table-check.py`（12 个文件全绿）+ `scripts/c30-1-counterproof.sh`（11 条反证全成立） |"""
def _append_row_to_table(text: str, header_exact: str, row: str, where: str) -> str:
    """把一行追加到指定表头的表块**末尾**（表格行必须落在表格内部）。

    ⚠️ **为什么结构化而不是文本锚点**：表格末行会随其它改动漂移，
    按「第 N 行」或长句锚点都容易错位；按**表头定位表块**则不会。
    """
    lines = text.split("\n")
    out: list[str] = []
    i = 0
    n = len(lines)
    done = False
    while i < n:
        if lines[i].strip() != header_exact:
            out.append(lines[i])
            i += 1
            continue
        j = i
        while j < n and lines[j].lstrip().startswith("|"):
            j += 1
        block = lines[i:j]
        if any(r.strip() == row.strip() for r in block):
            raise SystemExit(f"{where} 该行已存在，拒绝重复追加")
        out.extend(block)
        out.append(row)
        done = True
        i = j
    if not done:
        raise SystemExit(f"{where} 未找到表头：{header_exact}")
    return "\n".join(out)


def patch_00() -> None:
    if _done():
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()

    # ① 断表：定位「历批修正的设计缺陷」表内第一个空行并删掉
    lines = s.split("\n")
    try:
        head = lines.index("### 历批修正的设计缺陷（已改，记录在案）")
    except ValueError:
        raise SystemExit("① 未找到小节标题「历批修正的设计缺陷」")
    # 表头 + 分隔行之后，第一个空行即断表点
    blank = None
    for i in range(head + 3, len(lines)):
        if lines[i].strip() == "":
            blank = i
            break
        if lines[i].startswith("### ") or lines[i].startswith("## "):
            raise SystemExit("① 未在小节内找到空行（表格结构可能已变化）")
    if blank is None:
        raise SystemExit("① 小节内没有空行 —— 断表可能已被修复")
    # 确认空行两侧都是表格行（否则删错地方）
    if not lines[blank - 1].startswith("|") or not lines[blank + 1].startswith("|"):
        raise SystemExit("① 空行两侧不全是表格行 —— 不是断表点，拒绝误删")
    del lines[blank]
    s = "\n".join(lines)

    # ② 孤儿行
    s = _sub_once(s, OLD_ORPHAN, NEW_ORPHAN, "② 孤儿行")

    # ③ 重复条目
    s = _sub_once(s, OLD_DUP, NEW_DUP, "③ 重复条目")

    # ④ 已消解的「待补」（父子关系投影）
    s = _sub_once(s, OLD_PENDING, NEW_PENDING, "④ 已消解的待补")

    # ⑤a 陈旧声明（FR-022）
    s = _sub_once(s, OLD_STALE_FR022, NEW_STALE_FR022, "⑤a 陈旧声明（FR-022）")

    # ⑤b 真实缺口（Run 级预算读口径）—— 保留「待补」，只写准
    s = _sub_once(s, OLD_REAL_GAP, NEW_REAL_GAP, "⑤b 真实缺口（Run 级预算读口径）")

    # ⑥ 「未实现」表：把多出来的那一格折进缺口格（结构化折叠）
    s = _fold_extra_cells(s, OLD_HEADER, 17, "⑥ 未实现表列数折叠")

    # ⑦ 多出来的一格折进「修正」格
    s = _sub_once(s, OLD_EXTRA_CELL_1, NEW_EXTRA_CELL_1, "⑦ 多余第四格（C18-1 第一行）")
    s = _sub_once(s, OLD_EXTRA_CELL_2, NEW_EXTRA_CELL_2, "⑦ 多余第四格（C18-1 第二行）")

    # ⑧ 登记本批性质（文档批次，不改实现）
    s = _sub_once(s, OLD_SNAPSHOT, NEW_SNAPSHOT, "⑧ 快照标题下登记本批性质")

    # ⑨ 把本批登记进「历批修正的设计缺陷」表
    s = _append_row_to_table(s, OLD_DEFECT_HEADER, NEW_DEFECT_ROW, "⑨ 登记进历批修正表")

    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    F00.write_text(s)
    print("  已回写：00_修订说明与评审决议项.md")


def main() -> None:
    print("C30-1 文档口径修正：")
    patch_00()
    print("完成。（七处修正：断表空行 / 孤儿表格行 / 重复 SEQUENTIAL 条目 / "
          "已消解的「待补」×2（父子关系投影、FR-022） / 真缺口写准（Run 级预算读口径） / "
          "「未实现」表 17 个多列行折平 / 「历批修正」表 2 个多列行折平。）")


if __name__ == "__main__":
    main()
