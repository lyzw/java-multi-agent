#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二十八批 C28-1 文档回写：把「MQ 拓扑配置化 + 补齐生产绑定 + 隔离集成测试拓扑」登记进 00/08/09。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
⚠️ 口径：本批的需求编号与验收编号均为「本批补充」，**非评审冻结模型**。
⚠️ **本批不写 06 / 07**：
   · 06 是「配置数据模型与生命周期设计」—— 本批是**消息中间件拓扑**，
     既不是配置对象的数据模型，也不涉及生命周期；
   · 07 是「执行链集成设计」—— 其定位是**工具调用闸门**的职责边界
     （PermissionResolver / InvocationValidator / ToolInvocationGate），
     消息拓扑不在那一份文档的职责范围内。
   往这两份文档塞内容只会稀释它们的职责边界。

⚠️ **落点纪律**：表格行必须落在**表格内部**。
"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C28-1 -->"

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

OLD_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十七批：多级 Agent 三张表的迁移脚本结构门禁）"
NEW_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十八批：MQ 拓扑配置化与生产绑定补齐）"

OLD_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1383 例 / 0 failures / 0 errors / 25 skipped）**；
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
>   **确实发生过**，值得留痕。"""

BLOCK_00_IMPL = """| **MQ 拓扑配置化 + 生产绑定补齐 + 集成测试拓扑隔离（第二十八批 · C28-1 · FR-043）** | 修复前，A9 的三项缺口在**同一条因果链**上：① **生产拓扑缺绑定** —— 生产代码只声明了交换机与队列，**从未声明两者之间的绑定**。没有绑定就没有路由：`RabbitMessageSender` 按路由键 `agent.run.execute` 发布，而没有任何队列订阅它 → 消息被 broker 判为 unroutable **丢弃**，消费者 `@RabbitListener(queues="agent.run.execute")` 永远收不到，Run 永远停在 QUEUED，而 **API 照常返回 201、无报错、无告警**。**之所以一直没被发现**：集成测试 `RealBrokerEndToEndIT` **自己调了 `admin.declareBinding(...)`** —— 测试路径上拓扑是完整的，**测试替生产兜底**；本机 broker 上那个绑定就是 IT 跑出来的副产物，换环境（CI / 新部署）就没有 → 缺陷只在真部署那一刻暴露。② **配置项形同虚设** —— `agent.mq.exchange` / `agent.mq.dead-letter-exchange` / `agent.mq.queues.execute.dead-letter-*` 在 application.yml 里定义了却**没有任何读取方**，而发送器的交换机与路由键是**硬编码**的。于是「队列名可配」是**假的**：改了队列名，队列建在新名字上，发送器仍往老路由键发消息，没有任何队列订阅它 → 消息全部丢失。③ **集成测试污染生产队列** —— IT 直接用生产队列名 `agent.run.execute`，每次跑都往生产队列写消息（跨轮次残留 + 与其他 IT 互相干扰），本机 `mvn verify` 长期红的一个成因。现在：交换机 / 死信交换机 / 队列死信参数**全部读配置**；**路由键与队列名同源**（同一个配置项驱动交换机、队列、路由键三处引用，让「三处一致」成为结构上的必然而不是靠人记着改三遍）；**新增 `runExecuteBinding` Bean** 把生产拓扑补完整（已实测：删掉 broker 上的队列与绑定后，仅跑生产配置的 Spring 上下文，绑定即被**代码**重建）；IT 改用**独立拓扑**（`.it` 后缀）并加两条**确定性**隔离断言。⚠️ **另补一处「静默失败」**：不可路由消息（mandatory 退回）此前无声无息消失，现注册 `ReturnsCallback` 打成**显式 ERROR 日志** —— 拓扑改成配置驱动后失配可能性变高，必须看得见 | `MqTopologyWiringTest` 7 例 + `RabbitMessageSenderTest` +3 例 + `RealBrokerEndToEndIT` +2 例 |"""

BLOCK_00_CP = """| **第二十八批反证（七处，逐一验证用例真的会红）** | ① 发送器忽略构造参数（回到硬编码默认值）→ **恰 1 条红**；② 绑定 Bean 被删（生产拓扑缺绑定 = 消息无处可去）→ **恰 2 条红**；③ 发布交换机忽略配置（`agent.mq.exchange` 变回摆设）→ **恰 2 条红**（交换机是绑定的**上游**，上游换名下下游「绑的是不是那个交换机」当然也要红 —— 这是**正确的依赖传播**，不是误伤）；④ 绑定的路由键与发送器不一致（失配即消息无处可去）→ **恰 1 条红**；⑤ 执行队列的死信参数忽略配置 → **恰 1 条红**；⑥ 发送器的发布目标指向生产拓扑（消息直达生产队列）→ **恰 2 条红**；⑦ 集成测试把整套拓扑改回生产名（污染生产队列）→ **恰 2 条红**。**七处互相独立、可分别证伪**；脚本带「结论必须变化」的自动判定 —— 改动后结论不变即判为**无效反证**，**红了但超出预期条数同样判无效**（那说明断言之间的耦合比预期紧）。⚠️ **反证 F 曾判越界并被修正**：原先用「生产队列深度不变」做隔离判定，而该判定**依赖生产队列是否存在**（全新环境里它根本不存在），是**环境相关**的判定 —— 已改为「发送器**实际发布目标** ≠ 生产拓扑」这条**确定性静态断言**（隔离的充分条件是「发布目标 ≠ 生产拓扑」：RabbitMQ 按 `(exchange, routing key)` 路由，不同目标的消息在结构上到不了生产队列） | `scripts/c28-1-counterproof.sh`（含「恢复后复跑全绿」） |"""

NEW_00_GAP = """| ✅ **集成测试的队列污染（第二十八批已修 · C28-1）** | **已落地**：① 队列 / 交换机 / 死信参数**全部配置化**（此前 `agent.mq.exchange` 等配置项**无任何读取方**，发送器硬编码 → 「队列名可配」是假的）；② **补上生产拓扑缺失的绑定**（`runExecuteBinding` Bean）—— 此前生产代码**从未声明绑定**，没有绑定就没有路由，消息被 broker 判为 unroutable 丢弃，消费者永远收不到，而**集成测试自己补了绑定**（「测试替生产兜底」）；③ IT 改用**独立拓扑**（`.it` 后缀）+ 两条**确定性**隔离断言（本片拓扑名 ≠ 生产名；发送器实际发布目标 ≠ 生产拓扑）。⚠️ **剩余缺口**：① `mvn verify` 的**队列污染这一支**已消除，但本机 `verify` 仍红 —— 余下的是**历史 run 累积**（`GovernanceQueryIT#listFiltersBySubject` 与 `FencingTakeoverIT`），属**既有缺陷**、与多级 Agent 无关，需单独清库处理；② **其他模块**（bootstrap 的 `PlatformSecurityE2ETest` 等）仍会启动完整上下文并向**真实 broker** 发消息 —— 本批只隔离了 `agent-infrastructure` 的 IT，那部分**未动** | `MqTopologyWiringTest` 7 例 + `RabbitMessageSenderTest` +3 例 + `RealBrokerEndToEndIT` +2 例 |"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_00_TITLE, NEW_00_TITLE, "00")
    s = _sub_once(s, OLD_00_VERIFY, NEW_00_VERIFY, "00")
    lines = s.split("\n")

    # ── 缺陷表：「集成测试的队列污染」那一行整行替换 ──
    hit = [i for i, ln in enumerate(lines)
           if ln.startswith("| 集成测试的**队列污染**（第十二批发现 · 未修） |")]
    if len(hit) != 1:
        raise SystemExit(f"00 「集成测试的队列污染」行未唯一命中 (命中 {len(hit)})")
    lines[hit[0]] = NEW_00_GAP

    # ── 已实现表：插在「第二十七批反证」行之后 ──
    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("| **第二十七批反证（八处，逐一验证用例真的会红）**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"00 「第二十七批反证」行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_00_IMPL, BLOCK_00_CP]

    F00.write_text(_mark(F00, "\n".join(lines)))
    print("  已回写：00_修订说明与评审决议项.md")


# ── 08 ──────────────────────────────────────────────────────────────

BLOCK_08 = """
### 变更补充：MQ 拓扑配置化与集成测试隔离的验收补充（第二十八批）

⚠️ **本批验收项均为「本批补充」**，不是评审冻结的验收项 ——
08 原文从未规定「MQ 拓扑由谁声明」。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T22a | **路由可达** | 生产拓扑必须包含「交换机 → 队列」的绑定 | 绑定 Bean **必须存在**，且指向发布交换机与执行队列 | `MqTopologyWiringTest#bindingBeanExists`（反证 B：2 条红） |
| MA-T22b | 失配可见 | 绑定路由键与发送器路由键 | 必须**一致**（不一致时消息被 broker 判为 unroutable 丢弃） | `MqTopologyWiringTest#bindingRoutingKeyMatchesSender`（反证 B / D：各 1～2 条红） |
| MA-T22c | 配置生效 | 发布交换机 | 必须取 `agent.mq.exchange`（此前**硬编码**，配置项是摆设） | `MqTopologyWiringTest#exchangeFollowsConfig`（反证 C：2 条红） |
| MA-T22d | 配置生效 | 死信交换机与队列的死信参数 | 必须取配置值（此前**硬编码**） | `MqTopologyWiringTest#deadLetterExchangeFollowsConfig` / `#executeQueueFollowsConfig`（反证 E：1 条红） |
| MA-T22e | 配置生效 | 发送器的**实际发布目标** | 必须取配置值（此前**硬编码** →「队列名可配」是假的） | `MqTopologyWiringTest#senderUsesConfiguredTopology` / `RabbitMessageSenderTest#publishesToConfiguredTopology`（反证 A：1 条红） |
| MA-T22f | fail-closed | 拓扑名为空 | **显式拒绝**（不得静默发到默认拓扑 → 消息静默丢失） | `RabbitMessageSenderTest#blankTopologyNameIsRejected` |
| MA-T22g | **测试隔离** | 集成测试的拓扑 | 必须**独立**于生产拓扑（本片拓扑名 ≠ 生产名） | `RealBrokerEndToEndIT#testTopologyMustDifferFromProduction`（反证 G：2 条红） |
| MA-T22h | **测试隔离** | 发送器的**实际发布目标** | 必须**不是**生产拓扑（隔离的充分条件：RabbitMQ 按 `(exchange, routing key)` 路由，不同目标的消息结构上到不了生产队列） | `RealBrokerEndToEndIT#senderTargetMustNotBeProductionTopology`（反证 F / G：各 2 条红） |
"""

# ── 09 ──────────────────────────────────────────────────────────────

BLOCK_09_ROW = "| FR-043 | **MQ 拓扑配置化与生产绑定补齐**（第二十八批补充） | 00 §实现状态快照（第二十八批）、08 §MA-T22a～MA-T22h | MA-09、MA-10（第二十八批 C28-1） | AC-032 / MA-T22a、MA-T22b、MA-T22c、MA-T22d、MA-T22e、MA-T22f、MA-T22g、MA-T22h |"

BLOCK_09_2 = """⚠️ **FR-043 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
08 原文从未规定「MQ 拓扑由谁声明」，本批取的是
「拓扑全部配置驱动 + 生产自己声明绑定 + 测试用独立拓扑」这一组最小口径。
⚠️ **本批补的是「声明」而不是「行为」**：
消息发布/消费的行为早已有测试覆盖，缺的是**声明本身的完整性** ——
生产代码从未声明绑定（没有绑定就没有路由，消息被 broker 静默丢弃），
而集成测试自己补了绑定，于是「测试替生产兜底」，缺陷只在真部署那一刻暴露。
⚠️ **本批刻意让「不可路由」变得可见**：此前 mandatory 退回的消息
**无声无息消失**，现打成显式 ERROR 日志 —— 拓扑改成配置驱动后，
失配的可能性变高，必须看得见。
⚠️ **本批刻意把隔离判定做成确定性的**：
原先用「生产队列深度不变」判定，而该判定**依赖生产队列是否存在**
（全新环境里它根本不存在）—— 已改为「发送器**实际发布目标** ≠ 生产拓扑」
这条**静态**性质（RabbitMQ 按 `(exchange, routing key)` 路由，
不同目标的消息在结构上到不了生产队列）。
⚠️ **剩余缺口**：① bootstrap 侧启动完整上下文的 E2E 测试
（`PlatformSecurityE2ETest` 等）仍会向**真实 broker** 发消息，本批只隔离了
`agent-infrastructure` 的 IT，那部分未动；② 本机 `mvn verify` 仍红，
余下的是**历史 run 累积**（既有缺陷，与多级 Agent 无关）。"""


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    lines = F09.read_text().split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| FR-042 |")]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-042 表格行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_09_ROW]

    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("⚠️ **FR-042 的需求描述与验收编号均为「本批补充」**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-042 说明段未唯一命中 (命中 {len(hit)})")
    lines[hit[0] : hit[0]] = BLOCK_09_2.split("\n") + [""]

    F09.write_text(_mark(F09, "\n".join(lines)))
    print("  已回写：09_需求追踪矩阵.md")


def main() -> None:
    print("C28-1 文档回写：")
    patch_00()
    append(F08, BLOCK_08)
    patch_09()
    print("完成。（本批不写 06 / 07：MQ 拓扑既非配置数据模型，也不属工具调用闸门的职责边界。）")


if __name__ == "__main__":
    main()
