#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二十四批 C24-1 文档回写：把「编排绑定承载被引用行的内容指纹」登记进 00/06/07/08/09。

⚠️ 幂等：可重复执行（按文件独立记账 + 文件内标记双重判定）。
⚠️ 口径：本批的需求编号与验收编号均为「本批补充」，**非评审冻结模型**。
⚠️ 尤其：本批带来一处**行为变化**（解析不出被引用行 → 建 Run 阶段即被拒），
   文档必须如实登记，不得写成「完全无影响」。

⚠️ **落点纪律**：表格行必须落在**表格内部**。
"""
import pathlib

BASE = pathlib.Path("/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/docs/多级 Agent优化")

F00 = BASE / "00_修订说明与评审决议项.md"
F06 = BASE / "06_配置数据模型与生命周期设计.md"
F07 = BASE / "07_执行链集成设计.md"
F08 = BASE / "08_安全测试与验收矩阵.md"
F09 = BASE / "09_需求追踪矩阵.md"

MARK = "<!-- C24-1 -->"

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


def insert_before(path: pathlib.Path, anchor: str, block: str) -> bool:
    if _done(path):
        print(f"  跳过（已回写）：{path.name}")
        return False
    s = path.read_text()
    if anchor not in s:
        raise SystemExit(f"锚点未命中：{path.name} :: {anchor[:60]}")
    s = s.replace(anchor, block + anchor, 1)
    path.write_text(_mark(path, s))
    print(f"  已回写：{path.name}")
    return True


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
        raise SystemExit(f"{where} 待替换文本未命中：{old[:60]}")
    if text.count(old) != 1:
        raise SystemExit(f"{where} 待替换文本不唯一：{old[:60]} (命中 {text.count(old)})")
    return text.replace(old, new, 1)


# ── 00 ──────────────────────────────────────────────────────────────

OLD_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十三批：委派记录补「模型原始候选」）"
NEW_00_TITLE = "## 实现状态快照（2026-10-25 · 代码落地第二十四批：编排绑定承载被引用行的内容指纹）"

OLD_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1322 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十二批基线（`9b367c6`）同口径为 **1320 例** → 本批 **+2 例**
>   （`RouterDelegationTest` 新增 2 例），增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例成对断言**（「看得见降级」与「**不该丢的事实**」互相独立）：
>   拆掉原始候选的落库只让那条红，拆掉生效目标的落库只让另一条红 ——
>   否则「两列都写生效目标」也能让用例绿。
>   ⚠️ **本批另有一层 DB 层证据**：`MultiAgentPersistenceIT` 增 3 例
>   （本机真实 PG17 真跑，含**迁移脚本结构断言**——
>   降级索引的谓词必须排除存量行）。
>   ⚠️ **本批的反证覆盖七处**（见「已实现」表的「第二十三批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`9b367c6` + 本批改动）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1322 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

NEW_00_VERIFY = """> - `mvn -o -B clean test`（无外部依赖）→ **BUILD SUCCESS（1332 例 / 0 failures / 0 errors / 25 skipped）**；
>   ⚠️ 口径说明：这是 **reactor 汇总**（各模块 surefire 汇总行之和）。
>   第二十三批基线（`c82bc36`）同口径为 **1322 例** → 本批 **+10 例**
>   （`ReferencedContentFingerprintTest` 10 例），增量与新增用例数逐条对得上。
>   ⚠️ **本批新增用例成对断言**（「第二道防线挡得住」与「**不该失效的别失效**」互相独立）：
>   拆掉内容指纹承载只让前者红，把内容指纹段改成无条件写入只让后者红 ——
>   否则「全都失效」也能让用例绿。
>   ⚠️ **DB 层证据**：`agent-infrastructure` 全部 IT **376 例 / 0 失败**
>   （本机真实 PG17 + RabbitMQ）。
>   ⚠️ **本批的反证覆盖六处**（见「已实现」表的「第二十四批反证」行）。
>   ⚠️ **worktree 独立验证**：在独立 worktree（`c82bc36` + 本批改动）上跑
>   `mvn -o -B clean test` → **BUILD SUCCESS（1332 例 / 0 failures / 0 errors）**，
>   与主工作区同口径一致。"""

BLOCK_00 = """| **编排绑定承载被引用行的内容指纹（第二十四批 · C24-1 · FR-039）** | 修复前，「**同一版本行被 DB 侧就地改写**（运维直接 UPDATE）」这一支**没有任何防线**：编排绑定只固化**固定版本引用**（`id@version`）—— 版本号没变、引用没变、编排声明没变，只有被引用行（输出契约行 / 路由策略行）自己的内容变了。「版本行不可变」只是<b>约定</b>，不是机制。现在：① `OrchestrationBinding` 增补 `routingPolicyFingerprint` / `resultContractFingerprint`（可空，紧邻对应引用之后），并提供兼容构造器；② 新增 `ReferencedContentResolver` —— 按固定版本引用取被引用行的**内容指纹**（「引用格式非法 / 版本行不存在 / 未发布 / 读取失败」一律收敛为**同一个结论：取不到**，绝不编造指纹）；③ `ClosureFingerprint` 新增 `referencedContent` 段（**只在确实声明了被引用行时**才写入）；④ `PermissionResolver` 构建闭包时绑定内容指纹，**解析不出即 fail-closed**；⑤ `CachingClosureResolver` 新增被引用行的新鲜度判定 —— **否则缓存会自己架空这道防线**。⚠️ <b>两处刻意的取舍</b>：① 「没有声明引用行」的闭包，其指纹<b>完全不变</b> —— 那本就是完整证明，让它也变只是无谓地让在途 Run 集体失效（那不是 fail-closed，只是返工）；有被引用行的闭包，指纹<b>必然变化</b>（此前那份「内容没变」的证明是<b>不完整</b>的）。② <b>行为变化</b>：声明了被引用行却解析不出该行的 Agent，现在在<b>构建闭包</b>阶段即被拒（原先只在委派时失败）—— 宁可拒绝建 Run，也不要一道防线<b>看起来在、其实不在</b>。⚠️ <b>剩余缺口</b>：① 被引用行被改写后，<b>已存在的审批</b>是否一律作废仍由指纹比对决定（指纹变 → 审批绑定失配 → 作废），但**审批记录本身不会被主动清理</b>；② 内容指纹只覆盖<b>该行自身的内容</b>，不覆盖「被引用行的引用方链路」之外的依赖 | `ReferencedContentFingerprintTest` 10 例 + agent-infrastructure 全部 IT 376 例 |
| **第二十四批反证（六处，逐一验证用例真的会红）** | ① 闭包指纹不承载被引用行的内容指纹（= 修复前的状态）→ **4 条红**；② 缓存不查被引用行的新鲜度（第二道防线被缓存架空）→ **2 条红**；③ 被引用行解析不出却不拒绝（防线看起来在、其实不在）→ **2 条红**；④ 解析能力未装配却不拒绝（静默放弃防线）→ **恰 1 条红**；⑤ 缓存没解析器却认为内容没变（缓存层 fail-open）→ **恰 1 条红**；⑥ 被引用行段无条件写入（不引用任何东西的 Run 也被无故判失效）→ **恰 1 条红**。**六处互相独立、可分别证伪** | `scripts/c24-1-counterproof.sh`（含「恢复后复跑全绿」） |"""

BLOCK_00_DEFECT = """| **「同一版本行被就地改写」没有任何防线 → 指纹说没变、行为却变了（第二十四批修 · C24-1）** | 编排绑定只固化**固定版本引用**（`id@version`）—— 版本号没变、引用没变、编排声明没变，只有被引用行（输出契约行 / 路由策略行）自己的内容被就地改写。而「版本行不可变」只是<b>约定</b>，不是机制 → 这一支<b>没有任何防线</b>。现在把被引用行的**内容指纹**承载进编排绑定：指纹变 → 在途 Run 失效 → 绑定了旧指纹的审批不被继续复用（MA-T18）。⚠️ 两处刻意取舍：① <b>没有</b>被引用行的闭包指纹<b>完全不变</b>（那本就是完整证明，无故失效只是返工）；② <b>缓存必须一起查</b> —— 否则缓存继续供出旧闭包，这道防线会被<b>缓存自己架空</b>。⚠️ <b>行为变化</b>：解析不出被引用行 → 构建闭包阶段即 fail-closed（原先只在委派时失败） | `ReferencedContentFingerprintTest` 10 例 |"""

OLD_00_GAP = """| **被引用对象的内容仍不在指纹内** | 第十批固化的是编排声明**本身**（含 `resultContractRef` / `routingPolicyRef` 的**固定版本引用**）。被引用对象（输出契约行、路由策略行）**那一行自己的内容**若被就地改写，其内容指纹会变，但**编排绑定里没有承载它** —— 故「引用指向的行内容变了」这一支仍需各自的发布校验与审批绑定把关。✅ **第十四批已把路由策略侧补齐**：策略是**不可变版本行**（只能发新版本，`ON CONFLICT DO NOTHING` 不覆盖），内容指纹由**服务端**计算，发布期校验「引用的策略必须已发布」且「策略目标 ⊆ 父声明拓扑」；契约侧第十一批已由**发布期校验 + 服务端内容指纹**双保险。⚠️ **剩余缺口**：编排绑定里**仍未直接承载**被引用行的内容指纹 —— 即「同一版本行被 DB 侧就地改写（运维直接 UPDATE）」这一支，仍只能靠「版本行不可变」这一约定挡住，没有第二道指纹级防线 | `RoutingPolicyPublishFlowTest` + `CapRoutingPolicyFingerprintTest` |"""

NEW_00_GAP = """| ✅ **被引用对象的内容仍不在指纹内（第二十四批已修）** | **已落地**：`OrchestrationBinding` 增补 `routingPolicyFingerprint` / `resultContractFingerprint`，`ClosureFingerprint` 新增 `referencedContent` 段（**只在确实声明了被引用行时**才写入），`PermissionResolver` 在构建闭包时绑定内容指纹（**解析不出即 fail-closed**），`CachingClosureResolver` 增被引用行新鲜度判定（否则缓存会架空这道防线）。✅ 第十四批的路由策略侧与第十一批的契约侧「发布期校验 + 服务端内容指纹」双保险**仍在**，本批是**再加一道指纹级防线**（应对「DB 侧就地 UPDATE」）。⚠️ **行为变化**：声明了被引用行却解析不出该行的 Agent，现在在**构建闭包**阶段即被拒（原先只在委派时失败）。⚠️ **剩余缺口**：已存在的审批**不会被主动清理**（靠指纹失配自然作废） | `ReferencedContentFingerprintTest` 10 例 |"""


def patch_00() -> None:
    if _done(F00):
        print("  跳过（已回写）：00_修订说明与评审决议项.md")
        return
    s = F00.read_text()
    s = _sub_once(s, OLD_00_TITLE, NEW_00_TITLE, "00")
    s = _sub_once(s, OLD_00_VERIFY, NEW_00_VERIFY, "00")
    s = _sub_once(s, OLD_00_GAP, NEW_00_GAP, "00")
    lines = s.split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| **第二十三批反证（七处，逐一验证用例真的会红）**")]
    if len(hit) != 1:
        raise SystemExit(f"00 「第二十三批反证」行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = BLOCK_00.split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| **路由降级在数据上看不见")]
    if len(hit) != 1:
        raise SystemExit(f"00 C23-1 缺陷行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = BLOCK_00_DEFECT.split("\n")

    F00.write_text(_mark(F00, "\n".join(lines)))
    print("  已回写：00_修订说明与评审决议项.md")


# ── 06 ──────────────────────────────────────────────────────────────

BLOCK_06 = """
### 被引用行内容指纹的承载口径（第二十四批补充）

⚠️ **本节为本批补充，非评审冻结模型** —— 06/07/08 原文从未规定
「被引用对象（输出契约行 / 路由策略行）自己的内容要不要进闭包指纹」。

| 项 | 口径 |
|---|---|
| 编排绑定承载什么 | ① **固定版本引用**（`id@version`）——「依赖的是哪一版」；② **该行的内容指纹**——「那一版的内容是什么」 |
| 为什么两者都要 | 只承载引用 → 「同一版本行被就地改写」这一支**没有防线**（版本号没变、引用没变、编排声明没变）；只承载内容 → 丢掉了「依赖哪一版」这个事实 |
| 承载位置 | `OrchestrationBinding.routingPolicyFingerprint` / `resultContractFingerprint`（可空，紧邻对应引用之后） |
| 指纹来源 | 被引用行**自己的**服务端内容指纹（`cap_result_contract.config_fingerprint` / `cap_routing_policy.config_fingerprint`）—— 不重新计算，避免两处算法漂移 |
| 解析不出时 | **fail-closed**（拒绝构建闭包）——「引用了却解析不出」≠「没引用」 |
| 解析能力未装配时 | **fail-closed** —— 绝不「没有解析器就当没有指纹」 |

⚠️ **「没有声明引用」的闭包，指纹必须完全不变**：
它压根不依赖任何被引用行，其旧指纹**本就是完整证明**；
让它也变一变，只是无谓地让在途 Run 集体失效 ——
那不是 fail-closed，只是返工。
⚠️ **「声明了引用」的闭包，指纹必须变化**：
它此前的「内容没变」证明是**不完整**的（没覆盖被引用行的内容），
故在途 Run 必须失效（MA-T15 / MA-T18 的核心命题）。

⚠️ **内容指纹只覆盖「该行自身的内容」**，不覆盖别的依赖 ——
被引用行若再引用别的对象，那一层的覆盖要由那一层自己的绑定负责。
"""

# ── 07 ──────────────────────────────────────────────────────────────

BLOCK_07 = """
### 被引用行内容指纹的装配与缓存新鲜度（第二十四批补充）

⚠️ **本节为本批补充** —— 07 原文从未规定「被引用行的内容去哪被守住」。

```
构建闭包（PermissionResolver.build）
    │  解析本 Agent 的编排声明
    ▼
绑定被引用行内容指纹（bindReferencedContent）
    ├─ 没有声明引用 → 直接返回（零额外读，LEAF 场景不受影响）
    ├─ 解析能力未装配 → **fail-closed**（拒绝构建闭包）
    ├─ 引用了但解析不出 → **fail-closed**（拒绝构建闭包）
    └─ 解析出 → OrchestrationBinding.of(orchestration, 策略指纹, 契约指纹)
    ▼
闭包指纹（ClosureFingerprint.canonicalJson）
    └─ 只在**确实声明了被引用行**时写入 referencedContent 段
       （段内两个指纹写成显式 null，省略字段会让
         「引用了但没解析出来」与「压根没引用」算出同一个哈希）
    ▼
闭包缓存（CachingClosureResolver.isFresh）
    └─ **被引用行的新鲜度判定**（isReferencedContentFresh）
       ├─ 没有声明引用 → 直接新鲜（零额外读）
       ├─ 解析器未装配 → **一律作废**
       ├─ 读不到被引用行 → **一律作废**（读不到 ≠ 没变）
       └─ 指纹与缓存里固化的不一致 → **作废**（内容确实变了）
```

⚠️ **为什么缓存必须一起查**：
缓存条目是闭包的<b>旧副本</b> —— 被引用行被就地改写后，
缓存若继续供出旧闭包，指针就不会变，
「第二道防线」会被缓存<b>自己架空</b>。
⚠️ **缓存层不得 fail-open**：「读不到就认为没变」在这里是错的 ——
读不到只能得出「<b>无法确认</b>」，故一律作废（回源重解析）。
"""

# ── 08 ──────────────────────────────────────────────────────────────

BLOCK_08 = """
### 变更补充：被引用行内容指纹入闭包的验收补充（第二十四批）

⚠️ **本批验收项均为「本批补充」**，不是评审冻结的验收项 ——
08 原文从未规定「被引用对象的内容必须进闭包指纹」。

| 编号 | 维度 | 场景 | 期望 | 证据 |
|---|---|---|---|---|
| MA-T15c | 承载 | 被引用行的内容指纹被承载进闭包绑定与闭包指纹 | 绑定里有该指纹；规范 JSON 里出现 `referencedContent` | `ReferencedContentFingerprintTest#referencedContentFingerprintIsCarriedIntoClosure`（反证 A：4 条红） |
| MA-T15d | 第二道防线 | **同一版本行被就地改写**（版本号 / 引用 / 编排声明都没变） | 闭包指纹**必变** —— 否则「指纹说没变、行为却变了」 | `ReferencedContentFingerprintTest#inPlaceRewriteOfReferencedRowChangesClosureFingerprint` / `#inPlaceRewriteOfRoutingPolicyChangesClosureFingerprint`（反证 A：4 条红） |
| MA-T15e | 不误失效 | **没有**声明被引用行的闭包 | 指纹**完全不变**，且不写入 `referencedContent` 段 | `ReferencedContentFingerprintTest#closureWithoutReferencedRowsKeepsFingerprintStable`（反证 F：恰 1 条红） |
| MA-T15f | fail-closed | 声明了被引用行却解析不出（行不存在 / 未发布） | **拒绝构建闭包**（不是「没有指纹就放行」） | `ReferencedContentFingerprintTest#unresolvableReferencedRowFailsClosed` / `#unpublishedReferencedRowFailsClosed`（反证 C：2 条红） |
| MA-T15g | 装配纪律 | 被引用行内容指纹解析能力**未装配** | **拒绝构建闭包**；而**没有**声明引用时不受影响 | `ReferencedContentFingerprintTest#missingResolverFailsClosed` / `#missingResolverIsFineWithoutReferencedRows`（反证 D：恰 1 条红） |
| MA-T15h | 缓存不得架空防线 | 被引用行被改写后，闭包缓存必须作废 | 缓存回源重解析 → 指纹变化 | `ReferencedContentFingerprintTest#cacheIsInvalidatedWhenReferencedRowChanges`（反证 B：2 条红） |
| MA-T15i | 缓存不得 fail-open | 缓存层的解析器未装配 → 含被引用行的条目**一律作废** | 命中数为 0（每次都回源） | `ReferencedContentFingerprintTest#cacheWithoutResolverInvalidatesReferencedEntries`（反证 E：恰 1 条红） |
"""

# ── 09 ──────────────────────────────────────────────────────────────

BLOCK_09_ROW = "| FR-039 | **编排绑定承载被引用行的内容指纹**（第二十四批补充） | 06 §被引用行内容指纹的承载口径、07 §被引用行内容指纹的装配与缓存新鲜度 | MA-05、MA-15（第二十四批 C24-1） | AC-029 / MA-T15c、MA-T15d、MA-T15e、MA-T15f、MA-T15g、MA-T15h、MA-T15i |"

BLOCK_09_2 = """⚠️ **FR-039 的需求描述与验收编号均为「本批补充」**，不是评审冻结的需求项：
06/07/08 原文从未规定「被引用对象（输出契约行 / 路由策略行）自己的内容要不要进闭包指纹」
（本批取「引用 + 内容指纹并存、解析不出即 fail-closed、缓存一并查新鲜度」的最小方案）。
⚠️ 补充的是**既有约束**（MA-T15 的「编排声明固化进发布闭包」、
MA-T18 的「审批绑定闭包指纹」）在**被引用对象**上的落地 ——
此前那条约束只覆盖了「引用」，没覆盖「被引用的那一行自己的内容」。
⚠️ **行为变化（如实登记）**：声明了被引用行却解析不出该行的 Agent，
现在在**构建闭包**阶段即被拒（原先只在委派时失败）。
⚠️ **剩余缺口**：① 已存在的审批**不会被主动清理**（靠指纹失配自然作废）；
② 内容指纹只覆盖「该行自身的内容」，不覆盖它再引用的别的对象。"""


def patch_09() -> None:
    if _done(F09):
        print("  跳过（已回写）：09_需求追踪矩阵.md")
        return
    lines = F09.read_text().split("\n")

    hit = [i for i, ln in enumerate(lines) if ln.startswith("| FR-038 |")]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-038 表格行未唯一命中 (命中 {len(hit)})")
    lines[hit[0] + 1 : hit[0] + 1] = [BLOCK_09_ROW]

    hit = [
        i
        for i, ln in enumerate(lines)
        if ln.startswith("⚠️ **FR-038 的需求描述与验收编号均为「本批补充」**")
    ]
    if len(hit) != 1:
        raise SystemExit(f"09 FR-038 说明段未唯一命中 (命中 {len(hit)})")
    lines[hit[0] : hit[0]] = BLOCK_09_2.split("\n") + [""]

    F09.write_text(_mark(F09, "\n".join(lines)))
    print("  已回写：09_需求追踪矩阵.md")


def main() -> None:
    print("C24-1 文档回写：")
    patch_00()
    insert_before(F06, "### 发布闭包算法", BLOCK_06)
    insert_before(F07, "### 上下文与安全", BLOCK_07)
    append(F08, BLOCK_08)
    patch_09()
    print("完成。")


if __name__ == "__main__":
    main()
