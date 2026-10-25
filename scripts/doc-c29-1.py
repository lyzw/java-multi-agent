#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二十九批 C29-1 文档回写：把「管理台前端：编排声明与契约登记表单」登记进 00/08/09。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
⚠️ 口径：本批的需求编号与验收编号均为「本批补充」，**非评审冻结模型**。
⚠️ **本批不写 06 / 07**：
   · 06 是「配置数据模型与生命周期设计」—— 本批是**管理台表单**，
     不改配置数据模型，也不涉及生命周期；
   · 07 是「执行链集成设计」—— 定位是**工具调用闸门**的职责边界，
     管理台表单不在那一份文档的职责范围内。

⚠️ **落点纪律**：表格行必须落在**表格内部**。
"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C29-1 -->"

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

OLD_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十八批：MQ 拓扑配置化与生产绑定补齐）"
NEW_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十九批：管理台的编排声明与契约登记表单）"

OLD_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1387 例 / 0 failures / 0 errors / 26 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十七批基线（`08877e7`）同口径为 **1383 例** → 本批 **+4 例**
>   （`RabbitMessageSenderTest` 的「拓扑名由构造参数决定」3 例
>   + 新增测试类 `MqTopologyWiringTest` 1 例），增量与新增用例数逐条对得上。
>   ⚠️ **本批给出两个口径**：`MqTopologyWiringTest` 带 `IT_PG_URL` 守门
>   （与 `WorkerLoopWiringTest` 同款约定），**无库环境下整体跳过**
>   （3 个用例组各记 1 次跳过）；**带库时**（`IT_PG_URL` + 真实 PG17）
>   全量单测为 **1429 例 / 0 failures / 0 errors / 0 skipped** ——
>   即那 7 条装配断言**真跑**（不跑就等于「写了但没跑」，是最坏的形态）。
>   ⚠️ **本批 IT 计数**：**399 例**（上一批 397 例 → **+2 例**
>   = `RealBrokerEndToEndIT` 的两条隔离断言），本机真实 PG17 + RabbitMQ。
>   ⚠️ ⚠️ **上一批如实登记的既有失败仍在**：`GovernanceQueryIT#listFiltersBySubject`
>   在改动前同样失败（独立 worktree 复跑确认），真因是**历史 run 累积**
>   导致列表按 subject 过滤时找不到那一条 —— 与多级 Agent 改动无关。
>   ⚠️ **本批的反证覆盖七处**（见「已实现」表的「第二十八批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`19db7e8` = 本批 feat 提交）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1387 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。
>   ⚠️ **本批实测过的一处真实回归**：发送器提成独立 Bean 后，容器里出现
>   **两个** `MessageSender` 候选 → `getBean(MessageSender.class)` 抛
>   `NoUniqueBeanDefinitionException`（`MessagingCanaryWiringTest` /
>   `MessagingChannelWiringTest` 两条用例红）。已用 `@Primary` 修掉 ——
>   对外的消息发送端只能是**按租户路由**的那个，裸发送器只是它的内部实现。
>   这条登记在此，是因为「看起来无关的装配改动会打断既有调用点」在本仓
>   **确实发生过**，值得留痕。"""

NEW_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1387 例 / 0 failures / 0 errors / 26 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十七批基线（`08877e7`）同口径为 **1383 例** → 本批 **+4 例**
>   （`RabbitMessageSenderTest` 的「拓扑名由构造参数决定」3 例
>   + 新增测试类 `MqTopologyWiringTest` 1 例），增量与新增用例数逐条对得上。
>   ⚠️ **本批给出两个口径**：`MqTopologyWiringTest` 带 `IT_PG_URL` 守门
>   （与 `WorkerLoopWiringTest` 同款约定），**无库环境下整体跳过**
>   （3 个用例组各记 1 次跳过）；**带库时**（`IT_PG_URL` + 真实 PG17）
>   全量单测为 **1429 例 / 0 failures / 0 errors / 0 skipped** ——
>   即那 7 条装配断言**真跑**（不跑就等于「写了但没跑」，是最坏的形态）。
>   ⚠️ **本批 IT 计数**：**399 例**（上一批 397 例 → **+2 例**
>   = `RealBrokerEndToEndIT` 的两条隔离断言），本机真实 PG17 + RabbitMQ。
>   ⚠️ ⚠️ **上一批如实登记的既有失败仍在**：`GovernanceQueryIT#listFiltersBySubject`
>   在改动前同样失败（独立 worktree 复跑确认），真因是**历史 run 累积**
>   导致列表按 subject 过滤时找不到那一条 —— 与多级 Agent 改动无关。
>   ⚠️ **本批的反证覆盖七处**（见「已实现」表的「第二十八批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`19db7e8` = 本批 feat 提交）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1387 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。
>   ⚠️ **本批实测过的一处真实回归**：发送器提成独立 Bean 后，容器里出现
>   **两个** `MessageSender` 候选 → `getBean(MessageSender.class)` 抛
>   `NoUniqueBeanDefinitionException`（`MessagingCanaryWiringTest` /
>   `MessagingChannelWiringTest` 两条用例红）。已用 `@Primary` 修掉 ——
>   对外的消息发送端只能是**按租户路由**的那个，裸发送器只是它的内部实现。
>   这条登记在此，是因为「看起来无关的装配改动会打断既有调用点」在本仓
>   **确实发生过**，值得留痕。
>
> - **管理台前端**（`admin-console`，独立工程）：
>   `npx vitest run` → **71 例 / 0 失败**（上一批 49 例 → **+22 例**
>   = `orchestrationForm.test.tsx` 17 例 + `resultContractPage.test.tsx` 5 例）；
>   `npx tsc --noEmit` → **无类型错误**；`npx vite build` → **构建通过**。
>   ⚠️ **本批 Java 侧计数不变**（1387 / 1429 / 399）：本批只改前端，
>   后端治理通道（`AgentRequest.orchestration` + `PublishRequest.resultContracts`）
>   第十一批就已端到端打通，本批是**把前端补齐**，不是改后端。"""

BLOCK_00_IMPL = """| **管理台前端：编排声明与输出契约登记表单（第二十九批 · C29-1 · FR-044）** | 修复前，管理台**够不着**多级 Agent 的两样核心声明：后端治理通道第十一批就已打通（`AgentRequest.orchestration` + `PublishRequest.resultContracts` 端到端透传到发布事务），但前端 `PublishAgentSpec` 里**没有** `orchestration`、`SubmitPublishRequest` 里**没有** `resultContracts`，也没有任何表单 —— 全仓 grep 不到 orchestration / childAgentRefs / resultContract / routingPolicy 任何字样。后果是「**治理通道存在，但没人走它**」：多级 Agent 的编排与输出契约只能靠手写 SQL 配，而配置期 fail-closed 的门禁（非法声明绝不「降级成 LEAF 照常发布」）也就形同虚设 —— 因为人根本没法在界面上写出一份**错**的声明来被门禁拒。现在：① **编排声明**作为**结构化表单**进入 Agent 发布抽屉（模式 / 子 Agent 引用列表 / 路由策略引用 / 输出契约引用 / 深度 / 次数 / 超时 / 失败策略），逻辑抽成**纯模块** `orchestrationForm.ts`（校验 + 序列化 + 回显解析）—— 抽成纯模块而不是写在页面里，是因为否则只能靠「渲染整个页面 + 模拟输入」来测，既慢又脆；校验口径与后端 `AgentOrchestrationParser` **对齐**（配置期 fail-closed）。② **输出契约登记**独立成页 —— 契约是独立对象（按「标识 + 固定版本」寻址，**不属于任何 Agent**），挂在某个 Agent 的表单里会诱发重复登记；复用同一条三步工作流（申请→双人复核→执行），**不开旁路** —— 旁路会绕过双人复核，而「谁能写配置」正是双人复核要管的事。⚠️ **固定版本校验**：裸 id 与 `latest` 漂移别名一律拒绝（MA-T02）。⚠️ **数值字段留空即不写入** —— 默认值口径属**评审前置决策 2**（最大深度与预算默认值，**尚未闭环**），前端一旦预填默认值就等于替评审先定了一套口径，且会成为「第二个默认值来源」（前后端各一份，迟早漂移）；故前端只提供输入、不提供默认。⚠️ **剩余缺口**：① **路由策略登记**表单**本批未做**（后端 `PublishRequest.routingPolicies` 同样已支持，但 A6 的范围是「编排声明与契约登记」两项）；② 契约登记页**只支持登记**，尚无**契约目录/查看**页；③ 编排声明的**子 Agent 候选列表**未接（需后端提供按租户的 Agent 列表接口供下拉选择，当前是手填 id） | `orchestrationForm.test.tsx` 17 例 + `resultContractPage.test.tsx` 5 例 |"""

BLOCK_00_CP = """| **第二十九批反证（六处，逐一验证用例真的会红）** | ① 编排声明的校验被摘掉（非法声明不再被拒）→ **恰 1 条红**；② 固定版本校验被摘掉（`latest` 漂移别名不再被拒）→ **恰 2 条红**；③ 序列化不再带数值字段（默认值口径由前端说了算）→ **恰 1 条红**；④ 契约登记不再把契约放进 `resultContracts` → **恰 1 条红**（⚠️ 这正是「治理通道存在但没人走它」的形态：契约被提交到 `agents` 槽位 → 后端当成**Agent 草稿**处理 → **契约根本没登记**，而页面却显示「提交成功」）；⑤ 契约定义的 JSON 校验被摘掉 → **恰 1 条红**；⑥ **类型层**不再声明 `orchestration` / `resultContracts` → **恰 2 条类型错误**（源码 1 + 测试 1）。**六处互相独立、可分别证伪**。⚠️ **两处判据修正（都实测踩过）**：① 计数正则曾把 vitest 的 `× 行` 与 `FAIL 行` **各数一次** → 计数翻倍，每条反证都误判「越界」，已改为只数 `×` 行；② 反证 ⑥ 在 vitest 里红了 **0 条** —— vitest 只做转译、**不做类型检查**，类型层的唯一权威判据是 `tsc --noEmit`，已改用它 | `scripts/c29-1-counterproof.sh`（含「恢复后复跑全绿」） |"""

NEW_00_GAP = """| ✅ **多级 Agent 的管理台前端（第二十九批已修 · C29-1）** | **已落地**：① 编排声明以**结构化表单**进入 Agent 发布抽屉（逻辑抽成纯模块 `orchestrationForm.ts`，校验口径与后端 `AgentOrchestrationParser` 对齐）；② 输出契约登记**独立成页**（契约是独立对象，不属于任何 Agent），复用三步工作流、不开旁路；③ 前端类型补齐 `orchestration` 与 `resultContracts`（**不含版本号与内容指纹** —— 二者由服务端决定）。⚠️ **剩余缺口**：① **路由策略登记**表单本批未做（后端 `PublishRequest.routingPolicies` 同样已支持）；② 契约**目录 / 查看**页尚无；③ 编排声明的**子 Agent 候选列表**未接（当前手填 id，需后端提供按租户的 Agent 列表接口） | `orchestrationForm.test.tsx` 17 例 + `resultContractPage.test.tsx` 5 例 |"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_00_TITLE, NEW_00_TITLE, "00")
    s = _sub_once(s, OLD_00_VERIFY, NEW_00_VERIFY, "00")
    lines = s.split("\n")

    # ── 缺陷表：「多级 Agent 的管理台前端」那一行整行替换 ──
    hit = [i for i, ln in enumerate(lines)
           if ln.startswith("| 多级 Agent 的**管理台前端** |")]
    if len(hit) != 1:
        raise SystemExit(f"00 「多级 Agent 的管理台前端」行未唯一命中 (命中 {len(hit)})")
    lines[hit[0]] = NEW_00_GAP

    # ── 已实现表：插在「第二十八批反证」行之后 ──
    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("| **第二十八批反证（七处，逐一验证用例真的会红）**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"00 「第二十八批反证」行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_00_IMPL, BLOCK_00_CP]

    F00.write_text(_mark(F00, "\n".join(lines)))
    print("  已回写：00_修订说明与评审决议项.md")


# ── 08 ──────────────────────────────────────────────────────────────

BLOCK_08 = """
### 变更补充：管理台编排声明与契约登记的验收补充（第二十九批）

⚠️ **本批验收项均为「本批补充」**，不是评审冻结的验收项 ——
08 原文从未规定「管理台必须提供哪几个表单」。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T23a | **编排声明可达** | 管理台提交 Agent 编排声明 | 必须能提交 `orchestration`（此前前端类型里**根本没有**这个字段） | `orchestrationForm.test.tsx`（反证 A～C）+ `types.ts` 的 `PublishAgentSpec.orchestration`（反证 F：2 条类型错误） |
| MA-T23b | **fail-closed 预检** | LEAF 却声明子引用 / ROUTER 缺路由策略引用 / 子引用缺版本 | 本地**当场拒**（不让作者填完理由走到审批才被拒） | `orchestrationForm.test.tsx#validateOrchestration`（反证 A：恰 1 条红） |
| MA-T23c | **版本必须固定** | 裸 id / `latest` 漂移别名 / 版本号非正整数 | 一律拒（MA-T02：漂移别名会让「本次用哪一版」无法确定，闭包指纹无从计算） | `orchestrationForm.test.tsx#validatePinnedRef`（反证 B：恰 2 条红） |
| MA-T23d | **默认值口径不越权** | 数值字段留空 | **不得写入**任何数值（由服务端默认值兜底）—— 前端预填默认值等于替评审先定口径，且会形成「第二个默认值来源」 | `orchestrationForm.test.tsx#buildOrchestrationJson`（反证 C：恰 1 条红） |
| MA-T23e | **契约登记可达** | 管理台登记输出契约 | 必须放进 `resultContracts`（**不是** `agents` 槽位 —— 那是「治理通道存在但没人走它」的形态：契约根本没登记，页面却显示提交成功） | `resultContractPage.test.tsx`（反证 D：恰 1 条红） |
| MA-T23f | 契约定义合法性 | 契约定义不是合法 JSON | **当场拒**，不得提交 | `resultContractPage.test.tsx`（反证 E：恰 1 条红） |
| MA-T23g | 留痕与必填 | 契约标识 / 变更理由为空 | **不得提交**（理由会留痕，双人复核要审） | `resultContractPage.test.tsx`（2 条必填用例） |
| MA-T23h | **不越权** | 契约草稿的字段 | **不得**含版本号与内容指纹（二者由服务端决定；客户端若能指定就等于能指定「内容没变」这个证明） | `types.ts` 的 `PublishResultContractSpec`（只有 `contractId` + `schemaJson` 两个字段） |
"""

# ── 09 ──────────────────────────────────────────────────────────────

BLOCK_09_ROW = "| FR-044 | **管理台前端：编排声明与契约登记表单**（第二十九批补充） | 00 §实现状态快照（第二十九批）、08 §MA-T23a～MA-T23h | MA-05、MA-16（第二十九批 C29-1） | AC-033 / MA-T23a、MA-T23b、MA-T23c、MA-T23d、MA-T23e、MA-T23f、MA-T23g、MA-T23h |"

BLOCK_09_2 = """⚠️ **FR-044 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
08 原文从未规定「管理台必须提供哪几个表单」，本批取的是
「编排声明结构化表单 + 契约登记独立页 + 与后端口径对齐」这一组最小口径。
⚠️ **本批补的是「可达性」而不是「能力」**：
后端治理通道（`AgentRequest.orchestration` + `PublishRequest.resultContracts`）
第十一批就已端到端打通，本批只是把**前端**补齐 ——
此前管理台**够不着**这两样声明，全仓 grep 不到 orchestration / childAgentRefs /
resultContract / routingPolicy 任何字样，于是「治理通道存在，但没人走它」，
多级 Agent 只能靠手写 SQL 配。
⚠️ **本批刻意让前端校验与后端口径对齐**：
后端在配置期 fail-closed（非法声明绝不「降级成 LEAF 照常发布」——
那会让作者以为多级已生效，实际所有委派都被静默丢弃），
前端做同样的校验只是为了让作者**当场**看到错误；
**服务端才是权威**，前端校验不是防线。
⚠️ **本批刻意不预填默认值**：默认值口径属**评审前置决策 2**
（最大深度与预算默认值，**尚未闭环**），前端一旦预填就等于替评审先定了一套口径，
且会成为「第二个默认值来源」（前后端各一份，迟早漂移）。
⚠️ **剩余缺口**：① **路由策略登记**表单未做（后端同样已支持）；
② 契约**目录 / 查看**页尚无；③ 编排声明的**子 Agent 候选列表**未接
（需后端提供按租户的 Agent 列表接口供下拉选择，当前手填 id）。"""


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    lines = F09.read_text().split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| FR-043 |")]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-043 表格行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_09_ROW]

    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("⚠️ **FR-043 的需求描述与验收编号均为「本批补充」**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-043 说明段未唯一命中 (命中 {len(hit)})")
    lines[hit[0] : hit[0]] = BLOCK_09_2.split("\n") + [""]

    F09.write_text(_mark(F09, "\n".join(lines)))
    print("  已回写：09_需求追踪矩阵.md")


def main() -> None:
    print("C29-1 文档回写：")
    patch_00()
    append(F08, BLOCK_08)
    patch_09()
    print("完成。（本批不写 06 / 07：本批是管理台表单，既不改配置数据模型，也不属工具调用闸门的职责边界。）")


if __name__ == "__main__":
    main()
