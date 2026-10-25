#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二十七批 C27-1 文档回写：把「多级 Agent 三张表的迁移脚本结构门禁」登记进 00/06/08/09。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
⚠️ 口径：本批的需求编号与验收编号均为「本批补充」，**非评审冻结模型**。
⚠️ **本批不写 07**：A8 是**数据模型的回归防护**（DDL 结构门禁），
   与 07 的职责（运行期执行链集成设计）无关 ——
   往 07 塞内容只会稀释那份文档的职责边界。

⚠️ **落点纪律**：表格行必须落在**表格内部**。
"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C27-1 -->"

_PATCHED: set[str] = set()


def _done(path: pathlib.Path) -> bool:
    if path.name in _PATCHED:
        return True
    if MARK in path.read_text():
        _PATCHED.add(path.name)
        return True
    return False


def _mark(path: pathlib.Path, s: str) -> str:
    _PATCHED.add(path.name)
    if MARK not in s:
        s = s.rstrip("\n") + "\n\n" + MARK + "\n"
    return s


def append(path: pathlib.Path, block: str) -> bool:
    if _done(path):
        print(f"  跳过（已回写）：{path.name}")
        return False
    s = path.read_text().rstrip("\n") + "\n\n" + block.rstrip("\n") + "\n"
    path.write_text(_mark(path, s))
    print(f"  已回写：{path.name}")
    return True


def _sub_once(text: str, old: str, new: str, where: str) -> str:
    if old not in text:
        raise SystemExit(f"{where} 待替换文本未命中：{old[:70]}")
    if text.count(old) != 1:
        raise SystemExit(f"{where} 待替换文本不唯一：{old[:70]} (命中 {text.count(old)})")
    return text.replace(old, new, 1)


# ── 00 ──────────────────────────────────────────────────────────────

OLD_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十六批：上下文按 token 预算裁剪）"
NEW_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十七批：多级 Agent 三张表的迁移脚本结构门禁）"

OLD_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1361 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十五批基线（`cb60ea3`）同口径为 **1345 例** → 本批 **+16 例**
>   （`ContextBudgetTrimmerTest` 12 例 + `RunExecutionLoopTest` 的「上下文按 token 预算裁剪」
>   3 例 + `WorkerChildAgentRunnerTest` 1 例），增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例成对断言**（「裁了但语义不变」与「**不该裁的不裁**」互相独立）：
>   拆掉根路径裁剪只让根路径那条红，拆掉子路径裁剪只让子路径那条红 ——
>   否则「父子两套语义」也能让用例绿。
>   ⚠️ **本批无 DB 层改动**：纯运行期逻辑（agent-worker），故 IT 计数与上一批同口径
>   （`agent-infrastructure` 全部 IT **387 例**，本机真实 PG17 + RabbitMQ）。
>   ⚠️ ⚠️ **上一批如实登记的既有失败仍在**：`GovernanceQueryIT#listFiltersBySubject`
>   在改动前同样失败（独立 worktree 复跑确认），真因是**历史 run 累积**
>   导致列表按 subject 过滤时找不到那一条 —— 与多级 Agent 改动无关。
>   ⚠️ **本批的反证覆盖七处**（见「已实现」表的「第二十六批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`f84fd20` = 本批 feat 提交）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1361 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

NEW_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1383 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十六批基线（`63c64bb`）同口径为 **1361 例** → 本批 **+22 例**
>   （`MultiAgentSchemaStructureTest` 22 例），增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例成对断言**（「脚本层」与「**库层**」互相独立）：
>   改坏脚本只让脚本层那条红，摘掉真实库里的触发器只让库层那条红 ——
>   否则「只测一层」也能让用例绿。
>   ⚠️ **本批 IT 计数**：**397 例**（上一批 387 例 → **+10 例**
>   = `MultiAgentSchemaIT` 10 例），本机真实 PG17 + RabbitMQ。
>   ⚠️ ⚠️ **上一批如实登记的既有失败仍在**：`GovernanceQueryIT#listFiltersBySubject`
>   在改动前同样失败（独立 worktree 复跑确认），真因是**历史 run 累积**
>   导致列表按 subject 过滤时找不到那一条 —— 与多级 Agent 改动无关。
>   ⚠️ **本批的反证覆盖八处**（见「已实现」表的「第二十七批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`fd20d00` = 本批 feat 提交）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1383 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

OLD_00_GAP = """⚠️ 剩余缺口：这几张表与 `cap_agent_def.orchestration` 列的**迁移脚本本身**仍无「结构断言」型用例（只验行为，不验 DDL 形态）"""

NEW_00_GAP = """✅ **这几张表与 `cap_agent_def.orchestration` 列的「迁移脚本结构断言」缺口已消解（第二十七批 · C27-1）**：新增 `MultiAgentSchemaStructureTest`（22 例，构建期解析迁移脚本）与 `MultiAgentSchemaIT`（10 例，真实 PG 查 `pg_catalog`），把 DDL 形态纳入回归防护。⚠️ 剩余缺口：① `cap_routing_policy` 与 `cap_agent_def.orchestration` 列**尚未纳入**该门禁（当前覆盖的是三张多级 Agent 关系表 + 契约表）；② 结构断言是**文本解析**，改写方式超出解析器能力时可能漏判 —— 故真实库那一层（`pg_catalog`）不可省"""

BLOCK_00_IMPL = """| **多级 Agent 三张表的迁移脚本结构门禁（第二十七批 · C27-1 · FR-042）** | 修复前，多级 Agent 三张表（`agent_invocation` / `cap_agent_child_ref` / `cap_result_contract`）的结构**只由迁移脚本定义**，而迁移脚本**只在部署新环境时执行** —— 于是「脚本被改坏 → **已部署环境察觉不到**（库里已是旧结构）→ 现有行为断言照样全绿（它们跑在早就建好的库上）→ 直到**新建环境**才爆」。改坏脚本这件事会一路潜行到新环境上线那一刻才爆。既有 `CapCoreSchemaStructureTest` 只覆盖 V15，这三张表**完全不在结构门禁范围内**。现在补两层：① **构建期**（`MultiAgentSchemaStructureTest` 22 例）解析迁移脚本推出「**跨多版本迁移后的有效结构**」—— 注意不是任何单个脚本的原文：`cap_result_contract.contract_id` 在 V20 建表时是 `UUID`、V23 才改成 `TEXT`，只看 V20 会把「已修的缺陷」当成「要防的东西」，而真正要防的是「它被改回 UUID」；② **库层**（`MultiAgentSchemaIT` 10 例，真实 PG 查 `pg_catalog`）核对「库里长出来的结构」，并验证五个相关迁移脚本**可重复执行**（事务内重放 + 回滚）。⚠️ **两层缺一不可**：只测脚本测不到「脚本写了但迁移没跑到那一步」（V26 的回填在 FORCE RLS 下静默影响 0 行 —— 表现是「迁移报成功，状态全是 DRAFT」，最难查的一类假成功）；只测库测不到「脚本被改坏但已部署环境察觉不到」。断言覆盖：列与类型、主键含 `tenant_id`、两条复合外键、状态集合**恰好七态**（封闭性）、世代令牌为正、根/非根父子判定、RLS enabled **且 FORCE**、策略读写**双向**收口、不可变与对齐触发器、关键查询路径的索引、降级索引谓词排除存量行 | `MultiAgentSchemaStructureTest` 22 例 + `MultiAgentSchemaIT` 10 例 |"""

BLOCK_00_CP = """| **第二十七批反证（八处，逐一验证用例真的会红）** | ① 状态集合拼错（封闭集合被打破）→ **恰 1 条红**；② 世代令牌约束被删（MA-T12 的地基消失）→ **恰 1 条红**；③ 契约 id 改回 UUID（V23 修过的缺陷静默回归）→ **恰 1 条红**；④ 已发布契约行的不可变触发器被删（V26 补的那条被摘掉）→ **恰 1 条红**；⑤ FORCE RLS 被摘掉（表 owner 重新能跨租户读）→ **恰 1 条红**（策略那条仍绿 —— 策略确实还在）；⑥ 主键去掉 `tenant_id`（跨租户寻址失去隔离维度）→ **恰 2 条红**；⑦ 降级索引的谓词被去掉（存量行被算成「发生过降级」）→ **恰 1 条红**；⑧ **真实库里**摘掉不可变触发器（证明库层真的咬得住）→ **恰 1 条红**。**八处互相独立、可分别证伪**；脚本带「结论必须变化」的自动判定 —— 改动后结论不变即判为**无效反证**并以非零码退出（编译失败同样判无效：「编译不过」不是「用例红」；**红了但超出预期条数同样判无效** —— 那说明断言之间的耦合比预期紧）。⚠️ **反证 G 曾无效并被修正**：断言原是在整份脚本里搜字符串，而 V25 里两个索引都提到同一列名，删掉 A 索引的谓词后 B 索引的同名字符串仍然命中 → 断言改为**按索引**定位到那一条语句 | `scripts/c27-1-counterproof.sh`（含「恢复后复跑全绿」） |"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_00_TITLE, NEW_00_TITLE, "00")
    s = _sub_once(s, OLD_00_VERIFY, NEW_00_VERIFY, "00")
    s = _sub_once(s, OLD_00_GAP, NEW_00_GAP, "00")
    lines = s.split("\n")

    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("| **第二十六批反证（七处，逐一验证用例真的会红）**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"00 「第二十六批反证」行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_00_IMPL, BLOCK_00_CP]

    F00.write_text(_mark(F00, "\n".join(lines)))
    print("  已回写：00_修订说明与评审决议项.md")


# ── 06 ──────────────────────────────────────────────────────────────

BLOCK_06 = """
### 三张表的结构不变量与构建期门禁（第二十七批补充）

⚠️ **本节为本批补充，非评审冻结模型** —— 06 原文从未规定
「三张表的 DDL 形态由谁守护」。

⚠️ **为什么这道门禁是必需的（这是本批要补的真实缺口）**：
这三张表的结构**只由迁移脚本定义**，而迁移脚本**只在部署新环境时执行**。
于是「脚本被改坏 → **已部署环境察觉不到**（库里已经是旧结构）→
现有行为断言照样全绿（它们跑在早就建好的库上）→
直到某天**新建环境**，改坏的脚本才被执行」——
那一刻线上环境已经建起来了。

⚠️ **只加行为断言是不够的**：
行为断言（写入后能否读回、非法值是否被拒）验证的是**当前库**，不是**脚本**。
脚本退化后只有**新环境**才会中招 —— 而新环境恰恰是最不该出事的那个。

#### 门禁断言的结构不变量

| 表 | 关键不变量 |
|---|---|
| `agent_invocation` | 状态集合**恰好七态**（封闭，多一个少一个都不行）；`fencing_token > 0`（MA-T12 的地基）；根调用（`depth=1`）必须无父、非根调用必须**有**父（否则悬空根可写入，调用树无法重建）；主键恰为 `(tenant_id, invocation_id)`（MA-T10 幂等的地基）；降级索引的谓词必须排除存量行（NULL ≠「发生过降级」） |
| `cap_agent_child_ref` | 父子两侧都必须有指向 `cap_agent_def` 的**复合外键**，且复合外键第一列是 `tenant_id`（顺带保证租户一致）、`ON DELETE CASCADE`（否则删父版本后留下悬空边）；`ordinal >= 0`、两侧版本号 `> 0` |
| `cap_result_contract` | `contract_id` 必须是**可读字符串**（TEXT 而非 UUID —— 对外契约把 id 定义为可读字符串，曾声明成 UUID 导致非 UUID id 的发布事务整笔回滚、运行期恒 fail-closed，V23 已修）；内容指纹非空；`lifecycle_state` 必须存在（第二十五批 V26 补的停用维度落点） |
| 三张表共同 | 主键必须**以 `tenant_id` 打头**；必须 **RLS enabled 且 FORCE**（未 FORCE 时表 owner 仍能跨租户读，而迁移角色正是 owner）；策略必须**读写双向**收口（只写 `USING` 不写 `WITH CHECK` 等于只挡读） |

#### ⚠️ 断言的是「跨多版本迁移后的**有效结构**」，不是任何单个脚本的原文

这是必需的：`cap_result_contract.contract_id` 在 V20 建表时是 `UUID`，
V23 才改成 `TEXT`。若只看 V20 的原文，会把「已被修复的缺陷」当成「要防的东西」，
而真正要防的是「**它被改回 UUID**」。

⚠️ 由此还带出一条**工程约束**：迁移脚本必须按**版本数值**排序生效 ——
字典序会把 `V10` 排到 `V9` 之前，于是「谁最后改了这张表」会判错
（契约 id 的类型就断在这里）。

#### 两层门禁（缺一不可）

| 层 | 手段 | 抓什么 |
|---|---|---|
| **构建期（脚本层）** | `MultiAgentSchemaStructureTest` —— 解析迁移脚本 | 「脚本被改坏但已部署环境察觉不到」 |
| **库层** | `MultiAgentSchemaIT` —— 真实 PG 查 `pg_catalog` | 「脚本写了但迁移没跑到那一步」 |

⚠️ 后者不是前者多余的重复：V26 的回填在 FORCE RLS 下会**静默影响 0 行**，
表现为「迁移报成功，状态全是 DRAFT」—— 最难查的一类假成功。
只有真的查一遍库里的实际结构才抓得住。

⚠️ **库层还验证迁移脚本可重复执行**（在事务内重放后回滚）：
PostgreSQL 的 DDL 是事务性的，所以重放既验证了「能执行」，
又不会把共享的 IT 库留在被改动的状态。

⚠️ **剩余缺口**：① `cap_routing_policy` 与 `cap_agent_def.orchestration` 列
**尚未纳入**该门禁；② 结构断言是**文本解析**，
改写方式超出解析器能力时可能漏判 —— 故库层那一层不可省。
"""

# ── 08 ──────────────────────────────────────────────────────────────

BLOCK_08 = """
### 变更补充：三张表的迁移脚本结构门禁的验收补充（第二十七批）

⚠️ **本批验收项均为「本批补充」**，不是评审冻结的验收项 ——
08 原文从未规定「迁移脚本的结构由谁守护」。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T21a | 封闭集合 | 委派状态集合 | **恰好**七态（多一个 / 少一个都不行） | `MultiAgentSchemaStructureTest#invocationStatusIsClosed`（反证 A：恰 1 条红） |
| MA-T21b | 世代令牌 | `fencing_token` 约束 | `> 0`（MA-T12 的地基） | `MultiAgentSchemaStructureTest#fencingTokenMustBePositive`（反证 B：恰 1 条红） |
| MA-T21c | 有效结构 | 契约 id 的类型 | **TEXT**（V23 修过的缺陷不得静默回归） | `MultiAgentSchemaStructureTest#resultContractColumnsAreCorrect`（反证 C：恰 1 条红） |
| MA-T21d | **不可变保证** | 已发布契约行 | 不可变触发器**必须在**（V26 补挂的不可被摘掉） | `MultiAgentSchemaStructureTest#resultContractIsImmutableOncePublished`（反证 D：恰 1 条红） |
| MA-T21e | **租户隔离** | RLS 强度 | 三张表必须 **enabled 且 FORCE** | `MultiAgentSchemaStructureTest#allThreeTablesAreRlsForced`（反证 E：恰 1 条红） |
| MA-T21f | 寻址维度 | 主键形状 | 必须**以 `tenant_id` 打头**；`agent_invocation` 恰为 `(tenant_id, invocation_id)` | `MultiAgentSchemaStructureTest#primaryKeysAreTenantScoped` / `#invocationPrimaryKeyIsExact`（反证 F：恰 2 条红） |
| MA-T21g | 审计噪音 | 降级索引谓词 | 必须排除存量行（NULL ≠「发生过降级」） | `MultiAgentSchemaStructureTest#routedFallbackIndexExcludesNullRows`（反证 G：恰 1 条红） |
| MA-T21h | **库层** | 真实 PG 的实际结构 | `pg_catalog` 里的列类型 / 主键 / 外键 / RLS / 触发器必须与脚本一致；且迁移脚本**可重复执行** | `MultiAgentSchemaIT#contractIdIsTextInDatabase` / `#allThreeTablesAreRlsForcedInDatabase` / `#contractTriggersExistInDatabase` / `#migrationsAreReplayable` / `#replayDoesNotAlterStructure`（反证 H：恰 1 条红） |
"""

# ── 09 ──────────────────────────────────────────────────────────────

BLOCK_09_ROW = "| FR-042 | **多级 Agent 三张表的迁移脚本结构门禁**（第二十七批补充） | 06 §三张表的结构不变量与构建期门禁 | MA-05、MA-16（第二十七批 C27-1） | AC-032 / MA-T21a、MA-T21b、MA-T21c、MA-T21d、MA-T21e、MA-T21f、MA-T21g、MA-T21h |"

BLOCK_09_2 = """⚠️ **FR-042 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
06/08 原文从未规定「迁移脚本的结构由谁守护」，本批取的是
「脚本层解析 + 库层核对 + 重放幂等」这一组最小口径。
⚠️ 补充的是**既有约束的回归防护**，不是新增业务能力 ——
「委派状态封闭」「世代令牌为正」「已发布行不可变」「RLS 必须 FORCE」
这些约束**早已存在**，只是**没人守护它们的 DDL 形态**。
⚠️ **本批刻意做成两层**：只测脚本测不到「迁移没跑到那一步」
（V26 回填在 FORCE RLS 下静默影响 0 行 = 迁移报成功但状态全是 DRAFT）；
只测库测不到「脚本被改坏但已部署环境察觉不到」。
⚠️ **本批刻意把「断言必须精准到语句级」写成纪律**：
反证 G 实测踩过 —— 断言在整份脚本里搜字符串时，
同文件另一个索引的同名字符串会让退化**照样通过**，
断言因此改为**按索引**定位到那一条语句。
⚠️ **剩余缺口**：① `cap_routing_policy` 与 `cap_agent_def.orchestration` 列
尚未纳入该门禁；② 结构断言是文本解析，改写方式超出解析器能力时可能漏判 ——
故库层那一层不可省。"""


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    lines = F09.read_text().split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| FR-041 |")]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-041 表格行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_09_ROW]

    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("⚠️ **FR-041 的需求描述与验收编号均为「本批补充」**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-041 说明段未唯一命中 (命中 {len(hit)})")
    lines[hit[0] : hit[0]] = BLOCK_09_2.split("\n") + [""]

    F09.write_text(_mark(F09, "\n".join(lines)))
    print("  已回写：09_需求追踪矩阵.md")


def main() -> None:
    print("C27-1 文档回写：")
    patch_00()
    append(F06, BLOCK_06)
    append(F08, BLOCK_08)
    patch_09()
    print("完成。（本批不写 07：A8 是数据模型的回归防护，与运行期执行链无关。）")


if __name__ == "__main__":
    main()
