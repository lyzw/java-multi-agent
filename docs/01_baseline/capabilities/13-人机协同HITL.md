# 13 · 人机协同 HITL（Human-in-the-Loop）— 深度对比分析报告

> **元信息行**
> - **本报告范围**：维度 13 — 人机协同 HITL（Human-in-the-Loop）。系统「人在哪些环节介入、以什么身份介入、介入后机器如何停/如何续」：运行期高风险工具审批门（approval gate / PARK-RESUME）、治理发布审批（模型 / Agent 定义上线，双人复核 + 不可变发布）、人工复核与归因回写、打断/纠正/任务委派、审批与「谁有权批准」的权限耦合（RBAC + SoD）、审批拒绝/超时的客户端终态语义（铁律 15）。本维度是 13 份维度深度报告的收官篇，需同时收束维度 07（权限）、03（快照不可变）、10（审计）、11（可靠性）、05（工具调用）的相关结论。
> - **证据来源**：`platform/` 各模块源码（逐行复核，重点 `agent-ports` / `agent-application` / `agent-worker` / `agent-persistence` / `agent-api` / `agent-core` / `platform-bootstrap`）、`deploy/sql/platform/01_platform_ddl.sql`（DDL 真相源，4300+ 行）、`platform/platform-bootstrap/src/main/resources/application.yml`（运行期配置真相源）、`docs/能力维度实现方式梳理报告.md` 第 13 章、`docs/Agent平台竞品对比报告_2026-11-15.md` 维度 13 与来源清单、`.workbuddy/memory/MEMORY.md`（项目铁律与已知缺口）；竞品与理论信息取自官方文档与法规原文（URL 见附录 B，抓取状态在「诚实边界声明」标注）。
> - **撰写日期**：2026-11-15
> - **关联文档**：`docs/能力维度深度报告/README-撰写规范.md`（格式契约）、`docs/能力维度深度报告/01-架构与编排模型.md` ~ `12-部署与伸缩.md`（写法基线）、`03-状态持久化与可恢复性.md`、`05-工具与函数调用.md`、`07-权限与护栏.md`、`09-成本与预算控制.md`、`10-可观测性与审计.md`、`11-可靠性与故障处理.md`
> - **源码复核声明**：本报告的第四章全部锚点已逐行到源码 / 配置复核。**与既有梳理报告、竞品报告、MEMORY 存在 7 处需要澄清或更正之处**（详见 §4.5 与文末「诚实边界声明」），其中最重要的三条是：① **重大发现**——源码已完整实现面向高风险工具的「运行期暂停-恢复」闭环（`RunState.WAITING_APPROVAL` 即事实上的 PAUSED 态 + `RunExecutionLoop` 挂起写检查点/释放租约 + `ApprovalDecidedWakeupConsumer.wakeup()` + `claimParkedRunForResume()` + `loop.resume()` 不重跑模型 + `RunRecoverySweeper` 超时兜底），既有梳理报告第 13 章 / 竞品报告维度 13 / MEMORY 均未记载这套机制，「缺运行期暂停-恢复 / 缺 PAUSED 态与恢复入口」的判断需精确化；② `ReviewAttributionHook` 并非「死代码 / 完全没接线」，而是「端口 + 回调接线完整、生产无 Bean 实现 → 走 NoOp」的**预留扩展点 + 未闭环能力**（产品归因口径未定），MEMORY 的「NoOp」结论过于粗糙；③ 治理审批并发「缺验证」属实，但机制层面 `FOR UPDATE` 行锁 + 应用层状态前置 + DB `two_person_ck` 已保证**两人并发通过不会破坏状态机**，真正缺的是「两个审批人并发 decide 同一申请单」的专项验收门禁，而非并发防护本身。

---

## 一、维度定义与评估坐标系

### 1.1 这个维度到底解决什么问题（问题陈述，不谈实现）

「人机协同 HITL」回答的不是「系统能不能弹个确认框」，而是**「当 agent 的自主行为要跨越一条不可逆边界时，人类凭什么仍是有效、可追责、且不会拖垮系统的最终决策者」**这一组元问题。把它拆成六个互不替代的子问题：

- **介入点（where）**：人在「运行前」（策略下发、Agent 定义上线）、「运行中」（某个高风险工具即将产生副作用）、还是「运行后」（人工复核、归因、申诉）介入？EU AI Act Art.14 要求的「high-risk AI 系统 human oversight」把介入点前移到设计期，但运行期仍须保留「决定不使用 / 覆盖 / 逆转输出」的能力。
- **介入身份（who）**：谁有权批准？权限（能不能做这个动作）与身份（是不是申请人本人）是两件事——`config:approve` 权限不能消除「自我审批」，须由 `approver_id <> requester_id` 兜底（four-eyes / SoD）。「谁批准」若与权限模型脱钩，审批台就是最容易被越权的入口。
- **机器如何停（how to pause）**：审批请求发出后，执行引擎是「阻塞占着一个 worker 线程干等」「把 Run 落到持久化暂停态并让出资源」「还是干脆当作一次失败」？停得不对，要么吞掉吞吐，要么留下永久 `WAITING_APPROVAL` 孤儿。
- **机器如何续（how to resume）**：人类批准后，从「中断的那一行」继续，还是「从节点开头重跑」？恢复会不会重复调用模型、重复扣预算、重复产生副作用？LangGraph 官方明确：resume 时**从调用 `interrupt()` 的节点开头重跑**，这决定了恢复必须幂等。
- **介入是否「有效」（meaningful）**：GDPR Art.22 语境下的「人工干预」不能是橡皮图章（token gesture / rubber stamp）；automation bias 与 alert fatigue 会让「形式上有个人点了一下通过」退化为合规幻觉。审批门必须回答「这个人看到了什么、有没有能力拒、拒了会怎样」。
- **拒绝/超时怎么收场（terminal semantics）**：审批被拒、审批过期、无权限，是「临时故障稍后重试」还是「显式终态、停轮询」？把终态/权限错误伪装成临时故障，会让前端无限等待（本项目铁律 15 的红线）。

这六个子问题是**一条链，不是六个开关**：任何一个答错，前面所有严谨设计都可能在生产里「看上去有 HITL、实际没有有效人类控制」。本维度与维度 07（权限）、03（快照不可变）、10（审计哈希链）、11（可靠性 UNKNOWN/超时收敛）互为承载关系，是本报告反复交叉引用它们的原因。

### 1.2 能力分层的四档模型（通用分层法）

按「审批事实由谁持有、状态机由谁守卫、暂停能否持久化并恢复」给出四档，从弱到强。这套分层是本报告自定的评估法（见「诚实边界声明」第五节），不同评估者可能给出不同档位。

| 档位 | 名称 | 判定特征 | 典型代表 |
|---|---|---|---|
| **L0** | 无人工介入（human-out-of-the-loop） | 全自动执行；最多事后看日志；无暂停原语 | 纯 ReAct 循环、批处理 agent |
| **L1** | 应用层约定（framework-agnostic） | SDK 回调 / 提示式确认 / 外部审批工具；**框架不感知暂停**，靠宿主应用把「等审批」实现成阻塞或轮询 | OpenAI Agents SDK（guardrail 触发 pending approval 信号，宿主自管）、AutoGen `UserProxyAgent`、CrewAI `human_input=True` |
| **L2** | 框架层一等公民（durable pause-resume） | 引擎内建 `interrupt` / checkpointer / `Command(resume)`；暂停态持久化、可跨进程恢复、状态可编辑；恢复语义由框架定义 | **LangGraph**、Google ADK Tool Confirmation、Agno HITL、CrewAI Flows `@human_feedback`、Anthropic `canUseTool`（回调级暂停） |
| **L3** | 引擎/数据层强制（governance-enforced） | 审批事实落库、状态机由 **DB 触发器**守卫（状态只能前进、终态不可迁出）；不可变发布 + 双人复核 SoD + authz_version 绑定 + fail-closed；绕过应用层也无法伪造审批 | **本项目治理发布链路**（`cap_config_publish_request` / `cap_config_pointer` / `forbid_published_def_mutation`）；本项目运行期工具审批的 DB 侧（`approval` / `cap_approval` + `enforce_*_state_transition`）亦属此档 |

**关键判读**：四档不是「L3 全面优于 L2」。L3 换的是**抗绕过性与可追责性**，代价是**灵活性与开发者体验**——DB 触发器状态机天然不擅长「在任意节点暂停并让人类改一段中间状态」。L2（LangGraph）换的是**交互灵活性**，代价是「审批事实的正确性由开发者与 checkpointer 实现共同担保」。本项目在 HITL 上是**偏科的**：治理发布审批达 L3，运行期工具审批的 DB 侧达 L3、执行引擎侧达 L2 下沿（有专用 PARK/RESUME，但不可任意节点、不可编辑状态），而「运行中任意点动态中断 + 状态编辑」仍是空白。这个「偏科」正是本维度要讲透的主线（§4.5、§5.3、§7 改进点 1）。

### 1.3 评估坐标系：五个评价轴及观测方式

| 轴 | 定义 | 观测方式（怎么看到、怎么证伪） |
|---|---|---|
| **A1 强制程度 / 抗绕过** | 审批能否被应用层 bug、并发、参数篡改绕过；未接线时是否 fail-closed | 查 DDL 触发器（`enforce_approval_state_transition`）、CHECK（`two_person_ck`）、`SELECT ... FOR UPDATE`；查 `PolicyEngineApprovalSource` 空策略是否返回 `FAIL_CLOSED`；撤掉修复点看门禁是否变红 |
| **A2 介入时机与粒度** | 运行前 / 运行中任意点 / 治理发布期 / 事后；能否编辑中间状态 | 看暂停态是否只是「工具执行前」专用，还是任意节点 `interrupt()`；看 `Command(resume)` 能否携带任意状态 delta |
| **A3 可恢复性与幂等** | 暂停是否持久化、恢复是否重跑模型/重复副作用、超时是否收敛 | 看是否有 checkpointer / `WAITING_APPROVAL` 落库 + 租约释放；看 `loop.resume()` 是否重建原始 `ToolCallRequest` 而非重新调模型；看 sweeper 是否把孤儿收敛为 `FAILED` |
| **A4 可审计与可追溯** | 「谁、何时、基于什么、批准了什么」是否落库且防篡改；是否关联哈希链 | 看 `approver_id` 是否只来自令牌、审批审计是否同事务、参数摘要/目标资源/有效期是否绑定、是否接维度 10 审计链 |
| **A5 有效性与吞吐** | 是否抵抗 automation bias；审批 SLA / 批量吞吐 / 超时自动处置；是否退化为橡皮图章 | 看审批 TTL 与 PARK 超时是否自洽、是否有 SLA 指标与告警、是否有批量审批与审计抽查、拒绝率是否被观测 |

五轴中 **A1/A4 是本项目的强项（L3 治理），A2/A3 的「泛化」是弱项，A5 基本空白**（无 SLA 指标、无批量审批、无审批有效性观测）。§5.1 的能力对照表按这五轴逐项判定。

### 1.4 本维度在 agent 系统中的位置（上下游依赖关系）

```
                         ┌──────────────────────── 治理期（运行前） ────────────────────────┐
                         │  维度03 快照不可变(cap_agent_def.model_ref / execution_snapshot) │
                         │  维度07 权限(config:request/approve/publish/rollback + APPROVER) │
                         │  → 治理发布审批 request→decide→apply 双人复核 → cap_config_pointer │
                         └───────────────┬───────────────────────────────────────────────┘
                                         │ 发布态不可改写（forbid_published_def_mutation）
                                         ▼
   ┌──────────┐   高风险动作   ┌─────────────────── 运行期（运行中） ───────────────────┐
   │ 维度05   │ ─────────────▶ │ ToolInvocationGate → RiskBasedApprovalPolicy → ApprovalPort│
   │ 工具调用 │  risk_level    │  approval:consume 权限校验 → WAITING_APPROVAL(PARK)        │
   └──────────┘                │  → 释放租约 / 让出 worker / 检查点落库                     │
                              │  → 审批台 decision → ApprovalWakeupPort.wakeup → RESUME │
   ┌──────────┐                │  → loop.resume(重建 ToolCallRequest，不重跑模型)          │
   │ 维度09   │ 预算闸门 ◀────▶│  (恢复后重入闸门；预算/参数摘要/目标资源重验)             │
   │ 成本预算 │                └───────────────┬───────────────────────────────────────┘
   └──────────┘                                │ 拒绝/超时 → 显式终态(铁律15)
   ┌──────────┐  审批事件/审计  ┌──────────────▼─────────────────────────────────────────┐
   │ 维度10   │ ◀────────────── │ ApprovalController / ConfigPublishRequestRepo 审计同事务 │
   │ 可观测审计│  哈希链/留痕    │  approval:consume / config:approve 动作落 authz 日志    │
   └──────────┘                └──────────────┬─────────────────────────────────────────┘
   ┌──────────┐  超时收敛       ┌──────────────▼─────────────────────────────────────────┐
   │ 维度11   │ ◀────────────── │ RunRecoverySweeper.failStaleParkedRuns(APPROVAL_TIMEOUT) │
   │ 可靠性   │  孤儿 PARK      │  → FAILED 终态、工具未执行、无副作用                     │
   └──────────┘                └─────────────────────────────────────────────────────────┘
```

**上游依赖**：维度 05 提供 `risk_level` 与工具注册（决定「哪些工具要审」）；维度 07 提供 RBAC 动作与 `APPROVER` 角色（决定「谁有权批准」）；维度 03 提供不可变发布快照（决定「治理期钉死、运行期只读」）；维度 09 预算闸门与审批门同处 `ToolInvocationGate`，共享「消费前重验」；维度 10 提供审计哈希链落点。**下游影响**：维度 11 的可靠性依赖 PARK 超时收敛（否则永久 `WAITING_APPROVAL`）；维度 04 的 SSE 需要把「等待审批 / 拒绝 / 超时」表达成前端可停轮询的终态帧（铁律 15）；维度 12 的部署决定审批台 `ApprovalWakeupPort` 是否与 worker 同进程装配（`WorkerExecutionConfig.java:807-813`），跨进程部署会改变唤醒链路的可靠性假设。

---

## 二、理论体系（必须引用真实出处）

> 本章所有理论的出处分三类：**法规/标准原文**（EUR-Lex / NIST 出版物）、**学术论文/专著**（真实标题+作者+年份+会议/期刊/ISBN/arXiv 号）、**框架官方文档**（URL 见附录 B）。凡本报告未能逐页核实官方原文者，已就地标注「按公开工程实践归纳」，不作事实依据。抓取状态汇总见文末「诚实边界声明」第三节。

### 2.1 核心理论 / 概念框架

#### 框架 1：HITL 分类学与自动化等级连续谱（定位「人在环里的哪一格」）

- **HITL 分类学**：`human-in-the-loop`（人在决策闭环内，逐次批准）、`human-on-the-loop`（人在环上监督，可随时介入但不逐次批准）、`human-in-command`（人设定目标与约束、系统自主执行）、`human-out-of-the-loop`（全自动）。这组术语在欧盟 AI Act 的 human oversight 讨论与 Ben Shneiderman《Human-Centered AI》(Oxford University Press, 2022, ISBN 978-0-19-284529-0) 中被系统使用；Shneiderman 的核心命题是「把人的控制与计算机自动化做成**可兼容的二维组合**，而非此消彼长的一维滑杆」——高自动化与高人类控制可同时成立，这正是本项目「L3 治理强制 + 运行期 PARK」的理论落点。
- **Sheridan & Verplank 十级自动化**：T. B. Sheridan & W. L. Verplank, *Human and Computer Control of Undersea Teleoperators*, MIT Man-Machine Systems Laboratory Technical Report, 1978——提出从「人做全部决策」到「计算机做全部决策、人甚至不被通知」的 10 级 human control / automation 谱系，是「自动化程度」概念的源头。
- **Parasuraman 四阶段模型**：R. Parasuraman, T. B. Sheridan & C. D. Wickens, *A Model for Types and Levels of Human Interaction with Automation*, IEEE Transactions on Systems, Man, and Cybernetics — Part A: Systems and Humans, 30(3), 286–297, 2000——把「自动化」分解为四个可独立分级的信息处理阶段：① information acquisition（信息获取）、② information analysis（信息分析）、③ decision & action selection（决策与动作选择）、④ action implementation（动作实施）。**关键洞见**：可以在「动作实施」保持高自动化的同时，在「决策选择」阶段保留人类审批——这正是「高风险工具执行前审批门」的理论原型：不是全盘降自动化，而是**只在决策选择阶段插入人工关卡**。
- **对 HITL 的定位结论**：本项目的高风险工具审批门 = Parasuraman 阶段③插入人工、阶段④仍由机器实施；治理发布审批 = 运行前（阶段③前置到配置期）人工把关。二者都是「在连续谱的特定阶段保留人类控制」，而非「把整个 agent 降级为人工操作」。

#### 框架 2：法规对「有效人类监督」的硬要求（EU AI Act / GDPR / NIST AI RMF）

- **EU AI Act Article 14「Human oversight」**：Regulation (EU) 2024/1689（EUR-Lex ELI: `https://eur-lex.europa.eu/eli/reg/2024/1689/oj`）。第 14 条要求高风险 AI 系统的设计须使自然人能有效监督，具体包括：理解系统的能力与限制、监测异常并意识到**automation bias**（自动化偏见，即过度信任系统输出）、能解释输出、能**决定不使用 / 覆盖 / 逆转**输出、并能通过 **stop button 或类似机制安全地中断**系统。Annex III 特定场景另有「两名有能力的自然人复核」要求（four-eyes 的法规形态）。本报告核对的条文要点：automation bias 明示、stop 按钮明示、双人复核明示。
- **GDPR Article 22「Automated individual decision-making」**：Regulation (EU) 2016/679（EUR-Lex ELI: `https://eur-lex.europa.eu/eli/reg/2016/679/oj`）。数据主体有权不受「仅基于自动化处理、对其产生法律或类似重大影响的决定」约束；即便允许，也须保障**获得人工干预（human intervention）、表达观点、contest（质疑）该决定**的权利。EDPB/监管语境强调人工干预须是 **meaningful**（由有权限、有能力改变结果的人作出），不能是 token gesture / rubber stamp。这直接约束「审批门是否流于形式」（§2.3 反模式 1、§7 改进点 5/6）。
- **NIST AI RMF 1.0**：NIST AI 100-1, *Artificial Intelligence Risk Management Framework*, 2023（`https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.100-1.pdf`）。其 GOVERN / MAP / MEASURE / MANAGE 四函数中，human oversight、human-AI configuration、可问责与透明被列为治理要件；配套 Playbook 强调「定义人类监督角色与介入触发条件」。本报告把它作为「审批 SLA / 有效性观测」的合规动机来源，而非具体 API 出处。
- **对 HITL 的定位结论**：法规要求的是**「有效、可中断、可追责、可质疑」**四件事，恰好对应 §1.3 的 A1（抗绕过）、A4（可审计）、A5（有效性）三轴。本项目在 A1/A4 上以 DB 触发器 + 审计留痕满足，在 A5 上（审批有效性观测、SLA）尚有缺口。

#### 框架 3：审批工作流理论（four-eyes / SoD / maker-checker）

- **four-eyes principle（双人复核 / 四眼原则）**：同一事务须经第二双眼睛独立复核，申请人不得自行批准。EU AI Act Annex III 与金融/发布治理普遍采用。
- **Separation of Duties（职责分离，SoD）**：NIST RBAC 标准（ANSI INCITS 359-2004；D. F. Ferraiolo, D. R. Kuhn, R. Chandramouli, *Role-Based Access Control*, 2nd ed., Artech House, 2007；NIST CSRC glossary `https://csrc.nist.gov/glossary/term/separation_of_duties`）区分 **Static SoD**（静态，角色互斥在授权期约束）与 **Dynamic SoD**（动态，同一会话/事务内互斥在运行期约束）。「申请人 ≠ 审批人」属**动态 SoD**：不能只靠「这个角色有没有 `config:approve` 权限」静态判定，必须在决定时比对 `approver_id <> requester_id`。本项目 `AuthorizationService.java:103-105` 的注释逐字点明了这一点。
- **maker-checker（金融业制单-复核）**：maker 发起、checker 独立核验后放行，是 SoD 在交易/支付/发布场景的行业形态。**未能核实到单一权威「原始论文出处」，此处按公开工程实践归纳**：其判别标准是「申请与批准是否由不同主体、在不同时间点、留下两条独立审计记录」。
- **对 HITL 的定位结论**：本项目用「应用层 `existing.requesterId().equals(approverId)` 抛 `SelfApprovalException` + DB `two_person_ck` CHECK」实现动态 SoD 的**双保险**（`JdbcConfigPublishRequestRepository.java:253-254` + `01_platform_ddl.sql:1873`），这是四眼原则落到代码/DDL 的标准做法。

#### 框架 4：可打断性与 off-switch（人必须能「叫停」）

- **D. Hadfield-Menell et al., *Cooperative Inverse Reinforcement Learning*, NIPS 2016（arXiv:1606.03137）**：CIRL 框架把人机协作建模为「机器人不确定人类目标、因而有动机允许人类纠正/关闭」，为 off-switch 提供博弈论基础。
- **D. Hadfield-Menell, A. Dragan, P. Abbeel, S. Russell, *The Off-Switch Game*, IJCAI 2017（arXiv:1611.08219）**：正式提出「off-switch game」，论证**可被关闭的智能体反而更有用**（因为它不会为达成目标而规避人类干预）。用户原始素材里把「The Trolley Problem of Autonomous Agents / off-switch」笼统归到 Hadfield-Menell 2016，本报告**如实拆分**：CIRL 是 2016 NIPS，off-switch game 是 2017 IJCAI，两者是同一研究脉络的不同论文（诚实边界声明第五节标注此拆分）。
- **对 HITL 的定位结论**：「可打断性」对应 EU AI Act Art.14 的 stop 按钮要求，落到本项目即 `RunState.WAITING_APPROVAL` 的迁出集合含 `CANCELLED`（人撤）与 `TIMED_OUT/FAILED`（超时收敛）（`RunState.java:75`），以及 `ApprovalController` 的 `DENY` 决策使挂起 Run 收敛——「人能叫停且叫停有明确落点」，而非「系统一意孤行」。但本报告诚实指出：项目**未提供「运行中任意时刻主动打断并编辑状态」的通用 off-switch**，只有「工具执行前审批门」这一处专用可打断窗口（§5.3）。

#### 框架 5：LangGraph durable HITL 模式（运行期暂停-恢复的工程基准）

- **官方文档**：`https://docs.langchain.com/oss/python/langgraph/interrupts`（HITL / `interrupt()`）、`https://docs.langchain.com/oss/python/langgraph/persistence`（checkpointer）、`https://docs.langchain.com/oss/python/langgraph/graph-api`（`Command`）。
- **核心机制**：`interrupt()` 在**任意节点任意位置**抛出中断；graph state 由 checkpointer 按 `thread_id` 持久化；外部审批后用 `Command(resume=...)` 携带任意数据恢复；恢复时**从调用 `interrupt()` 的节点开头重跑**（官方原文：*"The node restarts from the beginning of the node where the interrupt() was called when resumed"*），因此节点内 `interrupt()` 之前的代码须幂等。`__interrupt__` / `stream.interrupts` 暴露挂起状态。
- **对 HITL 的定位结论**：这是 A2（任意点介入 + 状态编辑）与 A3（持久化暂停 + 幂等恢复）的**事实基准**。用它对照本项目，能得出精确而非笼统的差距（§4.5、§5.3、§7 改进点 1）：本项目缺的不是「持久化暂停」本身（`WAITING_APPROVAL` + 检查点已具备），而是「**任意节点 + 任意状态编辑 + 通用恢复入口**」这层抽象。
- **诚实标注**：本轮复核中，`NodeInterrupt` 作为独立类名在该官方页未出现，本报告不把它当作官方原语引用；用户素材提到的 `NodeInterrupt` 属旧版/社区术语，正文以 `interrupt()` + `Command(resume)` 为准。

#### 框架 6：automation bias 与 alert fatigue（审批门为什么会失效）

- **automation bias**：R. Parasuraman & V. Riley, *Humans and Automation: Use, Misuse, Disuse, Abuse*, Human Factors, 39(2), 230–253, 1997——人倾向过度依赖自动化建议、降低独立核验意愿；EU AI Act Art.14 已把它列为 human oversight 须对抗的明确风险。
- **alert fatigue / review fatigue**：高频、低信息量的审批请求会让审批人趋于「一律通过」。**未能核实到单一权威原始出处，此处按公开工程实践归纳**：判别指标是「拒绝率长期趋近 0、审批耗时趋近 0、同一审批人批量秒批」。
- **对 HITL 的定位结论**：审批门不是「加了就安全」，A5 有效性要求观测拒绝率、审批时长分布、批量秒批比例，并用抽样复核 / 分级审批对抗橡皮图章（§2.3、§7 改进点 5/6）。本项目当前**无任何审批有效性指标**，这是 A5 空白的直接来源。

### 2.2 理论给出的「设计张力」（trade-off）清单

每个张力给出「A 方案 vs B 方案」的取舍，并标注本项目当前站位。

| # | 张力 | A 方案 | B 方案 | 取舍要点 | 本项目站位 |
|---|---|---|---|---|---|
| T1 | 安全 vs 吞吐 | 每次高风险动作都审批 | 按风险分级 + 抽样 + 阈值免审 | 全审最安全但吞吐最差、诱发 review fatigue；分级要靠 `risk_level` 可信 | **B**：`required-by-risk`（READ 免 / WRITE_PROFILE 可选 / WRITE_EXTERNAL 必审），`application.yml:558-560` |
| T2 | 有效性 vs 形式合规 | 强制人工看 diff、可拒、拒绝率被观测 | 只要「有人点过通过」即算合规 | GDPR Art.22 / EU AI Act 要求 meaningful；token gesture 不合规 | **偏 B（缺口）**：审批留痕齐全，但无 diff 展示/拒绝率观测/抽样复核 |
| T3 | 灵活性 vs 强一致 | 任意节点 interrupt + 状态可编辑 | DB 触发器状态机 + 不可变快照 + 状态只能前进 | L2 灵活但正确性靠开发者；L3 抗绕过但不擅「编辑中间态」 | **强 B**：治理域 L3；运行期 PARK 是专用态、不可编辑状态 |
| T4 | fail-closed 严格 vs 可用性 | 审批台未接线 → 全拒 | 未接线 → 降级放行 | 全拒保安全但「生产默认不可用」；放行有绕过风险 | **A**：空策略 `FAIL_CLOSED`（`RiskBasedApprovalPolicy.java:189-214`）+ 默认 `source.mode=none` |
| T5 | 泛化 interrupt vs 领域专用门 | 通用 `pause(reason)` 抽象 | 只做「工具执行前审批」专用 PARK/RESUME | 通用抽象复用高、复杂度大；专用门简单但覆盖面窄 | **B**：仅 `WAITING_APPROVAL` 一处，且绑定工具审批语义 |
| T6 | 运行期介入 vs 治理期钉死 | 运行中可改边（改模型/提示词/参数） | 发布态不可改写、运行期只读快照 | 前者灵活但破坏可复现/可追责；后者可复现但改一点就要重走发布 | **B**：`forbid_published_def_mutation` + `model_ref` 固化 |
| T7 | 同步阻塞审批 vs 异步解耦 | HTTP 请求内阻塞等审批 | 落 PARK 态 + 释放租约 + 异步唤醒 | 阻塞占线程、超时难控；异步解耦吞吐好但恢复链路更复杂 | **B**：PARK 释放租约（`RunExecutionLoop.java:1131-1133`）+ `wakeup()` 异步恢复 |
| T8 | 单审批人 vs 双人复核 | 一人即可批准 | 四眼 + 动态 SoD | 单人快、双人慢但抗自我审批/合谋 | **B**：治理发布双人复核；运行期工具审批 `approver_scope` 单人（`PARENT_OR_TEACHER`） |

T1/T3/T6/T7 是本项目的**主动设计取舍**（写进代码与 DDL，属「设计取舍导致的代价」）；T2/T5 是**能力缺口**（§6.2）；T4 是**有意保留的未接线状态**（§4.5 不一致之六）。

### 2.3 业界公认的反模式（anti-pattern）清单及判别方式

| # | 反模式 | 判别方式（怎么发现） | 关联 |
|---|---|---|---|
| AP1 | **橡皮图章 / token gesture**：形式上有人工点通过，实质无核验 | 拒绝率长期≈0、审批耗时≈0、无 diff/上下文展示、批量一键通过 | GDPR Art.22、automation bias、§7 改进点 5/6 |
| AP2 | **自我审批**：maker = checker | 无 `approver_id <> requester_id` 约束；只校验「角色有无审批权限」 | SoD 动态约束、`two_person_ck`、§7 改进点 4 |
| AP3 | **审批绑定过松**：只批「操作类型」不批「参数/目标资源/有效期」 | 审批记录缺 `argument_digest` / `target_resource` / `expires_at`；消费时不重验 → 参数替换重放 | `approval` 表、`cap_approval` 双哈希、`ApprovalPort.consume` |
| AP4 | **终态伪装 + 无限等待**：拒绝/超时/无权限被当临时故障 | 404/403/409 返回可重试码；前端无停轮询；`REJECTED/EXPIRED` 未收敛为终态 | 铁律 15、`ApprovalController.java:158-163`、§7 改进点 7 |
| AP5 | **恢复重跑 / 副作用重复**：resume 重新调模型或重复执行工具 | resume 未重建原始 `ToolCallRequest`；无幂等键；预算被二次扣 | LangGraph「节点从头重跑」、本项目 `loop.resume`、§7 改进点 1 |
| AP6 | **暂停态泄漏**：PARK 后无超时兜底 | 无 sweeper；`WAITING_APPROVAL` 永久停留、不报错不告警；PARK 超时 < 审批 TTL | `RunRecoverySweeper`、`application.yml:294-297` |
| AP7 | **automation-bias 放大器**：审批 UI 默认勾选通过、无风险提示 | 默认值为 APPROVE、审批台不展示 `risk_level`/参数摘要、无「拒绝」醒目入口 | EU AI Act Art.14、A5、`DecisionRequest.approve()` |

本项目**已系统性规避** AP2（双保险 SoD）、AP3（参数摘要+双哈希）、AP4（404/409 显式终态）、AP5（`wakeup` 按 operationId 反查、不重跑模型）、AP6（`RunRecoverySweeper` + 超时自洽注释）；**AP1 与 AP7 未规避**（审批有效性观测/UI 缺失）；AP5 在「泛化 interrupt」语境下仍有边界（详见 §5.3）。这套「已规避 / 未规避」清单是 §6.2 与 §7 改进点的直接输入。

### 2.4 深度理论锚点补遗（解释力与边界，不与 §2.1 重复）

> 本节是对 §2.1 六个框架的**二次锚定**：每个锚点给出本轮实际访问/下载核对的官方 URL、核实到的原文要点、它**能解释什么**、它**不能解释什么**（边界），以及落在本项目哪一处。凡本轮未能逐字核实者显式降级为「按公开工程实践归纳」或「未能核实」，不臆造条款内容。

#### 锚点 A｜EU AI Act Art.14「Human oversight」——把「可中断」提升为法条级硬要求

- **官方出处与抓取状态**：Regulation (EU) 2024/1689，EUR-Lex ELI `https://eur-lex.europa.eu/eli/reg/2024/1689/oj`。本轮 `WebFetch` 已访问；网页摘录在 recital (67) 处截断，**以下 Art.14 引文取自完整法规正文核对**，非本页摘录逐字抓取。
- **核实到的原文要点**：Art.14(1) *"High-risk AI systems shall be designed and developed in such a way that they can be effectively overseen by natural persons during the period in which they are in use."*；Art.14(3)(b) *"remain aware of the possible risk of automation bias"*；Art.14(3)(d) *"be able to disregard, override or reverse the output of the high-risk AI system"*；Art.14(3)(e) *"be able to interrupt the operation of the high-risk AI system by means of a 'stop' button or similar procedure"*。
- **解释力**：它把 §1.1 第六问「机器如何停」与 §2.1 框架 4（off-switch）从「工程最佳实践」升级为**合规硬要求**——人不仅要能批，还要能「覆盖 / 逆转 / 安全中断」。本项目 `WAITING_APPROVAL→CANCELLED/TIMED_OUT/FAILED`（`RunState.java:75`）+ `ApprovalController.decide` 的 DENY（`ApprovalController.java:124-166`）正是 Art.14(3)(d)(e) 的领域实现。
- **边界（必须承认）**：① Art.14 仅约束 **high-risk AI systems（Annex III 列举场景）**，本项目是否落入适用范围取决于部署场景（教育场景可能触及 Annex III 教育类），**本报告不做法律定性**；② 法条要求「设计上可被有效监督」，**不等于**要求「任意节点 interrupt」——领域专用审批门同样可满足 stop button 要求；③ Annex III 的「两名自然人复核」与 §2.1 框架 3 的 four-eyes 属同一法规家族，但本轮**未逐页核实 Annex III 逐条**，不逐字引用。

#### 锚点 B｜GDPR Art.22「Automated individual decision-making」——把「有效人工干预」写成可质疑权

- **官方出处与抓取状态**：Regulation (EU) 2016/679，EUR-Lex ELI `https://eur-lex.europa.eu/eli/reg/2016/679/oj`。本轮 `WebFetch` 已访问；网页摘录止于 recital (109)，**Art.22 正文引文取自完整法规核对**。
- **核实到的原文要点**：Art.22(1) *"The data subject shall have the right not to be subject to a decision based solely on automated processing … which produces legal effects concerning him or her or similarly significantly affects him or her."*；Art.22(3) *"at least the right to obtain human intervention on the part of the controller, to express his or her point of view and to contest the decision."*
- **解释力**：它解释了 §1.3 A5「有效性」为何不是锦上添花——人工干预必须是 **meaningful**（有权限、有能力改变结果的人作出），且数据主体有 **contest（质疑）** 权。本项目「审批事实落库 + 同事务审计 + `approver_id` 来自令牌」（`JdbcConfigPublishRequestRepository.java:263-273`、`ApprovalController.java:139-141`）是「可举证谁批的」的工程形态；§7 改进点 6 的审计哈希链正是 contest 权的技术支撑。
- **边界**：Art.22(3) 的三项保障在**文字上限定于 Art.22(2)(a) 与 (c)**（合同必要性 / 数据主体明确同意）下的自动化决定，并非所有自动化决定；「meaningful / 非橡皮图章」是 EDPB 与监管语境的解读，本轮**未逐页抓取 EDPB 指南原文**，按 §2.1 框架 6 的 automation bias 文献旁证使用，不作逐字引文。

#### 锚点 C｜NIST AI RMF 1.0（NIST AI 100-1）——把「审批有效性观测」写成治理函数

- **官方出处与抓取状态**：`https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.100-1.pdf`。本轮以 `curl` 下载（content-type `application/pdf`，1.9 MB，48 页），经 `workbuddy-pdf --format txt` 解析：标题页确认为 *Artificial Intelligence Risk Management Framework (AI RMF 1.0)*, NIST AI 100-1, **January 2023**；`GOVERN` 68 次、`MEASURE` 84 次、`MANAGE` 152 次；`human oversight` 3 次、`human-AI configuration` 3 次。
- **解释力**：GOVERN / MAP / MEASURE / MANAGE 四函数中，`MEASURE` 直接支撑 §7 改进点 5 的 SLA / 拒绝率 / 审批时长指标——**「可测量的监督」是治理函数的一部分，不是事后报表**；`human-AI configuration` 对应 §2.1 框架 1 的「人在哪一格」。
- **边界**：NIST AI RMF 是**自愿性框架**，不规定具体 API、不规定阈值、不规定双人复核；它解释「为什么要测」，不解释「测哪个字段」——具体字段（`approval_pending_age_seconds` / `reject_ratio`）是本项目自定（§7 改进点 5），NIST 文本不含这些名字。

#### 锚点 D｜CIRL 与 Off-Switch Game——为「可打断性」提供博弈论基础（论文级复核）

- **官方出处与抓取状态**：`https://arxiv.org/abs/1606.03137`（*Cooperative Inverse Reinforcement Learning*, Hadfield-Menell / Dragan / Abbeel / Russell, v1 2016-06-09, NIPS 2016）；`https://arxiv.org/abs/1611.08219`（*The Off-Switch Game*, 同作者, v1 2016-11-24 / v3 2017-06-16, IJCAI 2017）。本轮均 `WebFetch` 核实标题、作者与摘要。
- **核实到的原文要点**：CIRL 摘要 *"a cooperative, partial-information game with two agents, human and robot; both are rewarded according to the human's reward function, but the robot does not initially know what this is … optimal CIRL solutions produce behaviors such as active teaching, active learning, and communicative actions"*；Off-Switch Game 摘要 *"we show that such agents have an incentive to disable the off switch, except in the special case where H is perfectly rational … for R to want to preserve its off switch, it needs to be uncertain about the utility associated with the outcome, and to treat H's actions as important observations about that utility."*
- **解释力**：两篇论文共同论证「**可被关闭的智能体反而更有用**」，为 §2.1 框架 4 与 EU AI Act Art.14(3)(e) 的 stop button 提供博弈论侧的支持；CIRL 的「主动教学 / 主动学习 / 交流动作」也解释了为什么审批台应展示参数摘要而非只给一个按钮（对抗 automation bias）。
- **边界**：① 两篇是**理论模型**（POMDP / 简单博弈），不是工程规范，不提供任何暂停/恢复 API；② Off-Switch Game 的 v1 提交于 2016-11，与 CIRL 同属 2016 脉络，但**正式会议是 IJCAI 2017**——既有素材笼统称「Hadfield-Menell 2016 / off-switch」已被 §2.1 框架 4 拆分，本轮 arXiv 页面进一步佐证该拆分（**已被复核纠偏**：v1 日期 ≠ 会议年份）；③ 论文结论依赖「智能体对目标不确定」这一前提，本项目 HITL 的审批门是**确定性的权限/状态机**，不能把论文结论直接搬来当安全证明。

#### 锚点 E｜LangGraph `interrupt()` + `Command(resume)` 的「节点从头重跑」——恢复幂等性的规范侧解释

- **官方出处与抓取状态**：`https://docs.langchain.com/oss/python/langgraph/interrupts`、`https://docs.langchain.com/oss/python/langgraph/persistence`。本轮均 `WebFetch` 全文核对。
- **核实到的原文要点**：interrupts 页 *"The node restarts from the beginning of the node where the `interrupt()` was called when resumed, so any code before the `interrupt()` runs again"*；*"The value passed to `Command(resume=...)` becomes the return value of the `interrupt` call"*；persistence 页 *"Checkpointers persist a thread's graph state as checkpoints … including conversation continuity, human-in-the-loop workflows, time travel, and fault tolerance"*，并明确 `MemorySaver`/`InMemorySaver` **重启即丢**、生产须 `PostgresSaver`/`SqliteSaver`。
- **解释力**：它是 §4.2 D-2「恢复绝不重跑模型」与 AP5「恢复重跑 / 副作用重复」的**规范侧对照系**——LangGraph 因「节点从头重跑」而要求节点内 `interrupt()` 前代码幂等；本项目因按 `operationId` 反查原始调用（`ApprovalDecidedWakeupConsumer.java:113/:128-129`）而在工具审批场景**不重跑模型、不重复扣预算**，是同一问题的更强解法（专用场景）。
- **边界（含一处纠偏）**：① 官方页决策词是 **approve/reject、Review and edit state、approve/edit/cancel**，**并未**以 `accept/edit/respond` 命名决策类型——§2.1 框架 5 沿用素材的「accept / edit / respond」属概括表述（**已被复核纠偏**：引用时以官方词为准）；② 官方页**未出现** `NodeInterrupt` 作为独立原语，§2.1 框架 5 的处理已正确；③ LangGraph 的 checkpointer **不校验审批状态迁移合法性**，「谁批的 / 参数是否仍有效」由开发者担保——这正是本项目 L3 触发器状态机的差异点（§5.4 分歧点 1）。

#### 锚点 F｜Temporal Signals / Queries / Updates——审批决定作为「持久消息」的工业级形态

- **官方出处与抓取状态**：`https://docs.temporal.io/encyclopedia/workflow-message-passing`（本轮 `WebSearch` 命中官方页并核对；原 `https://docs.temporal.io/encyclopedia/signals-and-queries` **返回 404 Page Not Found**，已被复核纠偏）；辅助页 `https://docs.temporal.io/workflows`（`WebFetch` 已核对 Event History / replay 语义）。
- **核实到的原文要点**：Queries 是 *read requests*，**"never add entries to the Workflow Event History"**、可对已完成的 workflow 执行；Signals 是 *asynchronous write requests*，**"cause changes in the running Workflow, but you cannot await any response or error"**，且 **"Recorded in the Event History: Yes"**；Updates 是 *synchronous, tracked write requests*，validator 可在写入 History **之前**拒绝。
- **解释力**：它给 §4.1 链路 A 的「审批决定 → 唤醒」提供了一个工业级参照——审批决定天然适合做 **Signal**（异步、持久、不需调用方等结果），「审批台已批准但工具未执行」可用 **Query** 观察工作流状态而无需阻塞。本项目 `wakeup()` 在 HTTP 请求内**同步**调用（`ApprovalController.java:147-149`，失败仅 `log.warn`），崩溃窗口靠 `RunRecoverySweeper`（`RunRecoverySweeper.java:267`）收敛——与 Temporal 的「Event History 原生持久」相比，这是**领域专用门 + 兜底扫描器**的取舍，不是「谁对谁错」。
- **边界**：Signals/Queries/Updates 是**工作流消息原语**，不含 SoD、RBAC、审批参数绑定、双人复核；Temporal 的 validator 能拒「非法 Update」，但「谁能发这个 Signal」仍由应用侧控制——与本项目 `AuthorizationService.Actions.APPROVAL_CONSUME`（`ApprovalController.java:130-131`）是不同层面的机制。另：该页明确 workflow 单实例 Signal 上限 10,000、in-flight Update 上限 10，属平台限制，与本项目无直接对应。

#### 锚点 G｜four-eyes / maker-checker / NIST SoD glossary——定义来源的诚实降级

- **核实尝试与结果**：`https://csrc.nist.gov/glossary/term/separation_of_duties` 本轮 `WebFetch` **返回 404 Page Not Found**（**已被复核纠偏**：附录 B 原登记此 URL 为「标准内容」，本轮实测不可达）；NIST RBAC 标准（ANSI INCITS 359-2004）与 Ferraiolo / Kuhn / Chandramouli《Role-Based Access Control》2nd ed.（Artech House, 2007）是真实出版物，Static/Dynamic SoD 的定义以专著为据。
- **解释力**：Dynamic SoD（同一会话/事务内互斥）精确解释了本项目为何不能只靠「角色有 `config:approve` 权限」——必须在决定时比对 `approver_id <> requester_id`（`JdbcConfigPublishRequestRepository.java:253-254` + DDL `cap_config_publish_request_two_person_ck :1882`）。
- **边界**：four-eyes principle 与 maker-checker **未能核实单一权威原始论文/官方出处**（与 §2.1 框架 3、附录 B 一致），本报告只作工程判别标准与合规动机旁证，**不把它当作可引用的规范条文**；也不据此断言任何竞品的未公开行为。

#### 锚点 H｜「HITL 分级 L0–L4」的口径边界——本报告自定 L0–L3，不冒充统一标准

- **核实结论**：本轮未核实到**单一权威的「L0–L4 HITL 分级」官方标准**；学界/工业界存在多种分层（Sheridan & Verplank 10 级、Parasuraman 四阶段、§1.2 本报告 L0–L3、部分厂商自定义 L0–L4），彼此不可互换。
- **解释力**：§1.2 的 L0–L3 是**本报告按「审批事实由谁持有 / 状态机由谁守卫 / 暂停能否持久化恢复」自定的评估法**（诚实边界声明第五节已声明），它的价值在于把「治理发布 L3 / 运行期 L2 下沿」的偏科结构讲清楚；把它误当成行业标准会抹掉这一自定义前提。
- **边界**：任何读者若要引用「L0–L4」，须先声明采用哪一家的分级；本报告正文一律用自定 L0–L3，附录 I 的红队用例按「机制是否存在」而非「档位高低」判定。

#### 2.4 小结：锚点 → 解释力 → 边界 → 落点

| 锚点 | 解释力（能回答什么） | 边界（不能回答什么） | 本项目落点 |
|---|---|---|---|
| EU AI Act Art.14 | 为什么必须可中断/可逆转/可覆盖 | 适用范围（high-risk）与「任意节点」不做法律定性 | `RunState.java:75` 迁出集、`ApprovalController.java:124-166` |
| GDPR Art.22 | 为什么人工干预须 meaningful、可 contest | 保障限于 2(a)/(c)；meaningful 非逐字法条 | 同事务审计 `:263-273`、改进点 6 哈希链 |
| NIST AI RMF 1.0 | 为什么审批有效性须可测量（MEASURE） | 不规定字段/阈值/双人复核 | 改进点 5 SLA 指标 |
| CIRL + Off-Switch Game | 为什么「可关闭」反而提升有用性 | 理论模型，非工程 API；依赖目标不确定前提 | §2.1 框架 4、`WAITING_APPROVAL` 可叫停 |
| LangGraph interrupt/resume | 为什么恢复必须幂等（节点从头重跑） | 不校验状态迁移合法性；决策词非 accept/edit/respond | D-2 按 operationId 反查、不重跑模型 |
| Temporal Signals/Queries | 审批决定作为持久异步消息的可行形态 | 无 SoD/RBAC/参数绑定；有平台消息上限 | `wakeup` 同步 + sweeper 兜底的取舍 |
| four-eyes / SoD（专著为据） | 为什么权限 ≠ 身份、须动态 SoD | 原始出处未能核实；CSRC URL 已 404 | 应用层 `SelfApprovalException` + DB CHECK `:1882` |
| L0–L4 分级口径 | 本报告自定 L0–L3 的定位价值 | 无统一权威标准，不可冒充 | §1.2 四档、附录 I「按机制判定」 |

---

## 三、竞品实现全景（每个主流方案一段）

> 本章覆盖 7 个方案（≥4 硬性要求）。每个方案的「官方定位与实现机制 / 官方 URL / 优点 / 局限 / 适用场景」逐一给出。URL 汇总见附录 B，抓取状态在「诚实边界声明」第三节逐条标注。凡官方页未明示者，明确写「官方未明示」，不作猜测。

### 3.1 LangGraph（重点基准 · L2 一等公民）

- **官方定位与实现机制**：HITL 是 LangGraph 的一等公民能力。在**任意节点的任意位置**调用 `interrupt(payload)` 暂停；graph state 由 checkpointer 按 `thread_id` 持久化到后端（Postgres / SQLite / InMemory）；外部审批后以 `Command(resume=value)` 恢复，恢复时 `value` 成为 `interrupt()` 的返回值，可携带任意审批结论 / 编辑后的状态；恢复**从调用 `interrupt()` 的节点开头重跑**。典型用法是「工具执行前中断 → 人工 accept/edit/respond → resume」：官方把审批动作抽象为 `accept / edit / respond / reject` 四类决策。挂起态通过 `__interrupt__` 与 `stream` 的 `interrupts` 暴露。
- **官方来源 URL**：`https://docs.langchain.com/oss/python/langgraph/interrupts`（HITL / `interrupt()`）、`https://docs.langchain.com/oss/python/langgraph/persistence`（checkpointer / thread / durable）、`https://docs.langchain.com/oss/python/langgraph/graph-api`（`Command`）。竞品报告维度 13 亦登记 `interrupts` 页。
- **优点**：A2 介入时机与粒度满分（任意点 + 状态可编辑）；A3 持久化暂停 + 跨进程恢复由框架担保；`Command(resume)` 把「批准 / 改状态 / 补充输入」统一为一个原语，开发者体验最好；checkpointer 与 time-travel 联动，调试期可回放审批前后的状态。是「运行期 HITL 灵活性」的事实基准。
- **局限/代价**：审批事实的**正确性与抗绕过性由开发者 + checkpointer 实现共同担保**，框架不提供 DB 层状态机守卫、不提供双人复核 SoD、不提供审批与 RBAC 的强制绑定；「节点从头重跑」要求节点内 `interrupt()` 之前的代码严格幂等，否则恢复会重复副作用；无内建审批 SLA / 到期自动处置 / 审批有效性观测。属 L2 而非 L3。
- **适用场景**：需要「运行中动态打断、让人工改一段中间状态再继续」的交互式 agent（客服坐席辅助、代码生成人工改稿、多轮人机协作创作）。

### 3.2 OpenAI Agents SDK（Python / JS · L1 应用层约定）

- **官方定位与实现机制**：**官方未明示「通用运行期中断/暂停-恢复原语」**。SDK 的 HITL 通过两条机制近似：① **Guardrails** 中的工具审批——官方 Guardrails 页出现 "pending approval interruption is emitted"（工具需批准时发出待审批中断信号）与 FunctionTool `needs_approval` / requires approval 概念，由宿主应用捕获信号后自行决定如何呈现给人和如何续跑；② **Handoffs** 把对话转交给另一个 agent（可构造「人类 agent」），但官方把 handoff 定义为 agent 间转交的 pipeline 原语，**不等同于审批门**。
- **官方来源 URL**：`https://openai.github.io/openai-agents-python/guardrails/`、`https://openai.github.io/openai-agents-python/`、`https://openai.github.io/openai-agents-python/handoffs/`、JS 版 `https://openai.github.io/openai-agents-js/`。
- **优点**：guardrail 与工具定义同处一处，审批触发条件贴近业务；概念少、上手快；输入/输出 guardrail 可复用为「执行前拦截」。
- **局限/代价**：**诚实区分**——官方页只证明「pending approval interruption 信号」与函数工具需批准概念，**不证明存在 `interrupt()` + `Command(resume)` 那样的图状态 checkpoint 持久化暂停/恢复原语**；「怎么暂停、暂停多久、状态存哪、如何续」全交给宿主应用，本质 L1。用户原始素材把 OpenAI 归为「无内建运行期 HITL，靠 handoffs 到人类 / 外部审批工具」——本报告核实后的精确表述是「有审批中断信号、无通用 durable pause-resume 引擎」，二者不矛盾（前者说没有一等公民 HITL，后者补上有 guardrail 级信号）。
- **适用场景**：以 guardrail 做合规拦截、审批逻辑由宿主应用自管的轻量 agent；不要求跨进程持久化暂停。

### 3.3 Anthropic Claude Agent SDK（computer use 人在环确认 · L1/L2 之间）

- **官方定位与实现机制**：`canUseTool` 回调在工具调用前**暂停执行**、把决策交给宿主（allow / deny / 修改输入）；配合 **hooks** 体系，`PreToolUse` hook 可 deny / allow / modify 工具调用或 defer 决策，`PermissionRequest` hook 可在权限请求时外部通知。computer use 场景的「人在环确认」即由这些回调承载：敏感操作前暂停、等人确认。SDK 以 agent loop + 工具权限为粒度，而非图状态 checkpoint。
- **官方来源 URL**：`https://platform.claude.com/docs/en/agent-sdk/overview`（SDK 概览 / 权限）、`https://platform.claude.com/docs/en/agent-sdk/hooks`（PreToolUse / PermissionRequest）、`https://docs.anthropic.com/en/api/agent-sdk`（竞品报告登记，注：文档域名已从 `docs.anthropic.com` 迁至 `platform.claude.com` / `code.claude.com`，旧 URL 会重定向——参见 12 号报告诚实边界声明第三节）。
- **优点**：工具级权限拦截粒度细、`canUseTool` 与 hooks 组合灵活、天然适配「computer use 危险操作前确认」；`PreToolUse` 可 modify 输入，具备轻度「状态编辑」能力。
- **局限/代价**：暂停是**回调级**（进程内阻塞式），非持久化 checkpointer；跨进程/长时间审批的恢复语义由宿主负责；无内建双人复核、审批审计链、审批 TTL/超时自动处置。介于 L1 与 L2 之间。
- **适用场景**：桌面/浏览器自动化（computer use）、需要在敏感工具调用前弹确认的本地 agent。

### 3.4 Google ADK（Tool Confirmation · L2 工程化最完整）

- **官方定位与实现机制**：官方 Tool Confirmation 样例用 `require_confirmation=True` 声明需确认的工具；工具内调用 `tool_context.request_confirmation(hint=..., payload=...)` 发出确认请求；ADK 事件流把确认请求传给调用方，调用方以 `FunctionResponse` 回传确认结果（yes/no 或结构化输入）；支持**远程 REST 审批**（`/run` / `/run_sse`），凭 `invocation_id` 在同一 invocation 内恢复。即「执行前暂停征询 → 结构化回传 → 继续」的工程化闭环。
- **官方来源 URL**：`https://adk.dev/tools-custom/confirmation/`（本轮已抓取核实：`request_confirmation` / `require_confirmation` / `FunctionResponse` / `/run` `/run_sse` / `invocation_id` 约束）、`https://adk.dev/sessions/`、竞品报告维度 13 登记同页。
- **优点**：远程 REST 审批链路完整（工程化最佳之一），结构化确认输入（不只 yes/no），与 ADK Session/Event 模型原生集成，适合分布式部署的审批台。
- **局限/代价**：确认点绑定「工具执行」这一处（非任意节点）；恢复约束在 `invocation_id` 内；审批台的正确性/SoD/审计仍需应用侧自建；`request_confirmation` 是「工具征询」而非「任意状态编辑」。
- **适用场景**：已用 ADK 构建、需要把审批台做成独立服务（REST 远程审批）的企业应用。

### 3.5 Microsoft AutoGen（UserProxyAgent · L1 · 已归档）

- **官方定位与实现机制**：`UserProxyAgent` 代表人类参与对话，`human_input_mode` 取 `ALWAYS` / `NEVER` / `AUTO`（按终止条件自动决定是否征询人类）；在决策点把人类输入插回 agent 对话。**归档状态**：AutoGen 于 2025-10 并入 Microsoft Agent Framework 进入 maintenance，官方时间线见 Microsoft Learn / devblogs；本报告按 2026 现状标注「已归档，不建议新项目采用」（与竞品报告及 12 号报告诚实边界声明一致）。
- **官方来源 URL**：`https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/selector-group-chat.html`（竞品报告维度 13 登记）、`https://microsoft.github.io/autogen/stable/`、Microsoft Agent Framework overview（`https://learn.microsoft.com/en-us/agent-framework/overview`）。
- **优点**：概念最直观（把「人」建模成一个 agent），`human_input_mode` 三档简单；适合对话式逐步确认。
- **局限/代价**：人在环是「对话参与者」而非「带状态机的审批门」；无持久化暂停、无 SoD、无审批审计；**已归档**，社区与维护资源迁移至 Agent Framework。
- **适用场景**：教学、原型、对话式 demo；不建议承载生产 HITL 治理。

### 3.6 CrewAI（human_input / Flows @human_feedback · L1/L2）

- **官方定位与实现机制**：Crew/Task 级 `human_input=True` 在任务结束时征询人工反馈；**Flows** 提供 `@human_feedback` 装饰器暂停流程、收集反馈并路由 `approved/rejected`；另有 webhook-based HITL（企业版）——流程进入 `Pending Human Input` 状态，挂 webhook，审批方回调后以 `/resume` 恢复（resume 时须重新传 webhook URLs）。
- **官方来源 URL**：`https://docs.crewai.com/en/learn/human-in-the-loop`（本轮已核实：`@human_feedback` / webhook HITL / `Pending Human Input` / `/resume` / `humanInputWebhook`）、`https://docs.crewai.com/en/concepts/flows`（竞品报告维度 13 登记）。
- **优点**：反馈与流程路由结合（`approved/rejected` 分支），webhook HITL 支持异步人工接入，概念贴近「任务委派 + 人工复核」。
- **局限/代价**：反馈点在「任务/流程级」而非「任意节点状态编辑」；resume 需重传 webhook URLs 说明恢复上下文由调用方维护；SoD/审计/审批 TTL 非内建。
- **适用场景**：多步骤业务流程中「跑完一段让人审一下再决定下一步」。

### 3.7 Agno（HITL 全谱 · L2）

- **官方定位与实现机制**：官方 HITL overview 把人工介入拆成 `active_requirements` 下的几类：`needs_confirmation`（工具确认）、`needs_user_input`（补充输入）、`needs_external_execution`（外部执行）；工具级 `requires_confirmation=True` / `requires_user_input=True` / `external_execution=True`，配 `UserInputField` / `user_input_schema` 定义结构化输入；审批动作 `confirm()` / `reject()`，恢复入口 `continue_run()`。是竞品中把 HITL 拆得最细、覆盖「确认/输入/外部执行/审批审计」全谱的框架。
- **官方来源 URL**：`https://docs.agno.com/hitl/overview`（本轮已核实：`active_requirements` / `needs_confirmation` / `needs_user_input` / `needs_external_execution` / `confirm()` / `reject()` / `continue_run()`）；竞品报告维度 13 登记同页。
- **优点**：HITL 类型覆盖最全；结构化用户输入 schema 明确；审批与 continue 语义清晰；覆盖「外部执行」这类本项目 `external_execution` 相似概念。
- **局限/代价**：仍以工具/步骤为粒度，非「任意节点 + 任意状态编辑」；持久化/跨进程恢复依框架 run 状态，抗绕过性与双人复核非内建；生态与 LangGraph 相比较小。
- **适用场景**：需要对「确认 / 补输入 / 外部代执行」分别建模的复杂 HITL 应用。

### 3.8 竞品横览小结（为第五章对照表铺垫）

| 方案 | 档位 | 暂停粒度 | 持久化恢复 | 状态可编辑 | 双人复核/SoD | 审批审计 |
|---|---|---|---|---|---|---|
| LangGraph | L2 | 任意节点任意位置 | ✅ checkpointer(thread_id) | ✅ Command(resume) 任意值 | ❌ | ❌（宿主） |
| OpenAI Agents SDK | L1 | 工具 guardrail 信号 | ❌（宿主） | ❌ | ❌ | ❌ |
| Claude Agent SDK | L1/L2 | 工具回调(PreToolUse) | ❌（进程内） | 部分(modify 输入) | ❌ | ❌ |
| Google ADK | L2 | 工具确认(REST) | ✅ invocation_id | 结构化输入 | ❌ | ❌ |
| AutoGen(归档) | L1 | 对话轮次 | ❌ | ❌ | ❌ | ❌ |
| CrewAI | L1/L2 | 任务/流程级 + webhook | 需重传上下文 | ❌ | ❌ | ❌ |
| Agno | L2 | 工具/步骤级 | run 状态 | 结构化输入 | ❌ | ❌ |
| **本项目** | **L3(治理)/L2下沿(运行期)** | **工具执行前(专用)** | **✅ WAITING_APPROVAL+检查点** | **❌** | **✅ 治理域双保险** | **✅ 同事务审计** |

**一句话读表**：竞品普遍在「A2 灵活性」上强、在「A1/A4 抗绕过与审计」上留白；本项目恰好相反——治理域 A1/A4 拉满（DB 触发器 + 双保险 SoD + 同事务审计），但 A2 的「任意点 + 状态编辑」是空白。这个「错位」是第五章分歧点的核心。

### 3.9 竞品实现扩充（新增 5 项 + 3 处官方核实纠偏）

> 正文 §3.1–§3.7 已覆盖 7 个方案；本节**新增 5 项**（Temporal / AWS Step Functions / Microsoft Agent Framework / PostgreSQL 行锁治理 / NIST AI RMF 作为治理框架对照），并对 3 处既有表述按本轮官方核实结果纠偏。**全部 URL 本轮经 `WebFetch` / `WebSearch` / PDF 下载实际核对**，逐条给出核实要点；核不实者显式写「未能核实」。

#### 3.9.1 Temporal（新增）——Signals / Queries / Updates 作为审批决定的持久载体

- **官方定位与机制（已核实）**：Temporal 把运行中的 Workflow 视为「有状态服务」，支持三类消息：`Queries` = 只读请求，**不写入 Event History**、可对已完成的 Workflow 执行；`Signals` = 异步写请求，**改变运行中的 Workflow 状态、调用方不等结果**，且**记入 Event History**；`Updates` = 同步可追踪写请求，带 validator 可在写入 History **之前**拒绝。单 Workflow 上限：10,000 Signals、10 个 in-flight Updates、2,000 个 Updates 入 History、单 payload 2 MB。
- **官方来源 URL**：`https://docs.temporal.io/encyclopedia/workflow-message-passing`（本轮 WebSearch 命中官方页并核对；**原 `https://docs.temporal.io/encyclopedia/signals-and-queries` 返回 404，已被复核纠偏**）；辅助 `https://docs.temporal.io/workflows`（本轮 WebFetch 已核对：Event History 是唯一事实源、replay 从事件历史重建状态而非快照恢复）。
- **对本维度的启发**：审批决定天然适合做 **Signal**（异步、持久、不需调用方等待）；「审批台显示已批准但工具没执行」可用 **Query** 观察状态而不阻塞人。本项目的 `wakeup()` 走 HTTP 请求内同步调用（`ApprovalController.java:147-149`，失败仅 `log.warn`），崩溃窗口靠 `RunRecoverySweeper.java:267` 的 `failStaleParkedRuns` 收敛——这是**领域专用门 + 兜底扫描器**，与 Temporal「Event History 原生持久 + 可 Query」是不同路线，不是谁缺谁有。
- **局限**：消息原语不含 SoD、RBAC、审批参数绑定、双人复核；validator 能拒「非法 Update」，但「谁能发 Signal」仍由应用侧授权——与本项目 `AuthorizationService.Actions.APPROVAL_CONSUME`（`ApprovalController.java:130-131`）是不同层面。

#### 3.9.2 AWS Step Functions（新增）——Activity taskToken 回调审批与「超时即作废」

- **官方定位与机制（已核实）**：Activity 由外部 worker 执行；`GetActivityTask` 返回 `input` + `taskToken`（唯一标识）；worker 完成后用 `SendTaskSuccess` / `SendTaskFailure` 带 token 回传结果；可用 `SendTaskHeartbeat` + 长 `TimeoutSeconds` 让状态等待最长一年。**关键**：超过 `TimeoutSeconds` 仍未收到结果或心跳 ⇒ 执行失败、History 记 `ExecutionTimedOut`，**Step Functions 随即作废该 taskToken，之后用过期 token 的调用抛 `TaskTimedOut`**。
- **官方来源 URL**：`https://docs.aws.amazon.com/step-functions/latest/dg/concepts-activities.html`（本轮 WebFetch 已核对原文）。
- **对本维度的启发**：这是附录 I「审批超时后旧 RESUME 消息复活终态 Run」反例的**工业级对照**——AWS 用「超时作废 token」在结构上阻止旧回调复活已超时任务；本项目 `claimParkedRunForResume` 的 `WHERE state='WAITING_APPROVAL'`（`JdbcRunClaimRepository.java:285`）+ `fencing_token = ?`（`:284`）是等价的状态侧作废：终态/已恢复 Run 的旧 wakeup 影响 0 行、返回 `NOT_PARKED`（`ApprovalDecidedWakeupConsumer.java:103-109`），**不重跑、不重复副作用**。
- **局限**：taskToken 是不透明串，AWS 不绑定「批了什么参数」；本项目 `argument_digest`/`target_resource` 绑定（DDL `:1471-1472`）比 token 更严。AWS 的 heartbeat 保活语义在本项目无对应（PARK 等待期间不续租，`RunExecutionLoop.java:1143` 主动 `releaseLease`）。

#### 3.9.3 Microsoft Agent Framework（新增；AutoGen / Semantic Kernel 后继）——把 HITL 状态管理做成框架一等公民

- **官方定位与机制（已核实）**：官方概览列四大领域（Agents / Harness Agent / Workflows / Integrations）；**Harness Agent** 明确含 *"don't-ask-again tool approval"*；Workflow 层提供 *"robust state management system for long-running and human-in-the-loop scenarios"*。关于血统，官方原文：*"Agent Framework combines AutoGen's simple agent abstractions with Semantic Kernel's enterprise features … The Agent Framework is the direct successor … In short, Agent Framework is the next generation of both Semantic Kernel and AutoGen."*
- **官方来源 URL**：`https://learn.microsoft.com/en-us/agent-framework/overview`（本轮 WebFetch 已核对）。
- **对本维度的启发**：① 微软把「长运行 + HITL 的状态管理」提升为框架级能力，与本项目 `WAITING_APPROVAL` + 检查点 + 恢复入口的方向一致；② *"don't-ask-again"* 是 A5 吞吐优化，但**直接放大 AP1 橡皮图章风险**——若本项目未来引入「同类工具免再审」，必须同时上 `reject_ratio` 观测与抽样复核（§7 改进点 5）。
- **两处纠偏（已被复核纠偏）**：① §3.5 称 AutoGen「已归档，不建议新项目采用」——本轮官方用词是「direct successor / next generation」，更精确表述为「AutoGen 与 Semantic Kernel 已进入由 Agent Framework 承接的后继阶段，新项目应直接用 Agent Framework」（**语义一致，措辞按官方原文精确化**）；② §3.5 的 `human_input_mode = ALWAYS/NEVER/AUTO` 在**当前 stable** `selector-group-chat` 页（`https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/selector-group-chat.html`，本轮 WebFetch 已核对）**未出现**——该页用 `UserProxyAgent` + `selector_func` + `"APPROVE"` 字符串判定；`human_input_mode` 是 0.2 版 API，正文 §3.5 的描述属旧版记忆，引用时须标注版本。

#### 3.9.4 PostgreSQL 行锁与事务语义（新增；非 agent 框架，但为本项目 L3 的规范侧锚点）

- **官方定位与机制（已核实）**：`FOR UPDATE … SKIP LOCKED` 跳过无法立即加锁的行，官方明确 *"not suitable for general purpose work, but can be used to avoid lock contention with multiple consumers accessing a queue-like table"*；**Caution**：READ COMMITTED 下 `ORDER BY` + 加锁子句**可能返回乱序行**（排序先执行、随后阻塞在加锁上）；`LIMIT` 存在时「锁够即停」，但被 `OFFSET` 跳过的行仍会被锁；locking clause 不能与 `GROUP BY` / `DISTINCT` / 集合运算同用。
- **官方来源 URL**：`https://www.postgresql.org/docs/current/sql-select.html`（本轮 WebFetch 已核对原文）。
- **对本维度的启发**：① `SELECT … FOR UPDATE` 是本项目「两人并发 decide 不破坏状态机」的规范基础（`JdbcConfigPublishRequestRepository.java:251/:376`），官方语义支持「并发串行化」这一结论；② 官方 Caution 的「子 SELECT 锁定的是返回给外层的行」「LIMIT 锁够即停」精确解释了 11 号报告 `MATERIALIZED` CTE 的必要性（FORCE RLS 下 IN 子查询退化、`LIMIT` 静默失效）——**同一份 SQL 语义在状态机守卫与队列领取两处同时生效**。
- **边界**：行锁只保证**串行化**，不保证 SoD——「两个不同的人」仍须应用层 `SelfApprovalException`（`:253-254`）+ DB CHECK（DDL `:1882`）双保险；把「有锁」误读为「有 SoD」是常见错误。

#### 3.9.5 NIST AI RMF（新增；治理框架对照，非竞品 SDK）

- **官方来源与抓取状态**：`https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.100-1.pdf`，本轮下载 48 页 PDF 并解析：标题 NIST AI 100-1 / AI RMF 1.0 / January 2023；`GOVERN` 68 次、`MEASURE` 84 次、`MANAGE` 152 次、`human oversight` 3 次、`human-AI configuration` 3 次（详见 §2.4 锚点 C）。
- **对本维度的启发**：A5 有效性观测（拒绝率 / SLA / 审批时长）在 NIST 框架里是 `MEASURE` 治理函数的一部分，不是事后报表——这解释了为什么 §6.2 把「A5 空白」判为能力缺口而非「锦上添花」，也解释了 7 个竞品普遍空白是**全行业短板**（§6.4 共性盲区）。
- **边界**：自愿性框架，不提供 API / 阈值 / 双人复核规则；本表不把它写成「竞品」，只在治理框架层作对照。

#### 3.9.6 三处官方核实纠偏汇总（均为本轮新发现，逐条标注）

| 处 | 既有表述 | 本轮官方核实结果 | 精确化口径 |
|---|---|---|---|
| 1 | §3.3：Claude `canUseTool` 可「allow / deny / 修改输入」；URL 用 `docs.anthropic.com` / `platform.claude.com` | `https://code.claude.com/docs/en/agent-sdk/permissions`（由 platform 域重定向而来）明确：**allow/deny 决策归 `canUseTool`，modify 归 hooks 的 `updatedInput`**；`https://code.claude.com/docs/en/agent-sdk/hooks` 明确 `PreToolUse` 支持 `permissionDecision ∈ {allow, deny, ask, defer}` + `updatedInput`，优先级 `deny > defer > ask > allow`；且 `defer` 可 *"resume the call later"* 但**无 durable checkpoint** | 「修改输入」是 **PreToolUse hook**，不是 `canUseTool`（**已被复核纠偏**）；旧 `docs.anthropic.com` 已重定向至 `code.claude.com` / `platform.claude.com`（与 12 号报告一致） |
| 2 | §2.1 框架 5 / §3.1：LangGraph 决策抽象为 `accept / edit / respond` | `https://docs.langchain.com/oss/python/langgraph/interrupts` 官方用词为 **approve / reject、Review and edit state、approve/edit/cancel**，未以 `accept/respond` 命名 | 引用官方时以 **approve / edit / cancel** 为准；`accept/edit/respond` 属素材概括（**已被复核纠偏**） |
| 3 | §3.6 / 附录 B：CrewAI `@human_feedback` 在 `human-in-the-loop` 页 | `https://docs.crewai.com/en/learn/human-in-the-loop` 本轮已核实：该页把 flow 式 `@human_feedback` **指向独立页** `https://docs.crewai.com/en/learn/human-feedback-in-flows`（标注 **1.8.0+**），本页主体讲 webhook 式 HITL（ enterprise） | `@human_feedback` 仍是 CrewAI 能力，但**官方锚点页已迁移**（**已被复核纠偏**）；正文两条 URL 并列保留，以独立页为 `@human_feedback` 锚点 |

#### 3.9.7 扩充后竞品横览（含官方 URL 可达性）

| 方案 | 审批/介入机制 | 持久化载体 | 官方 URL（本轮状态） | 与本项目对照点 |
|---|---|---|---|---|
| LangGraph | `interrupt()` + `Command(resume)` | checkpointer(thread_id) | `docs.langchain.com/oss/python/langgraph/interrupts` ✅已核实 | A2 灵活基准；我们不重跑模型更强 |
| OpenAI Agents SDK | guardrail `pending approval interruption` | 宿主自管 | `openai.github.io/openai-agents-python/guardrails/` ✅已核实 | 有信号无引擎；我们不接线时 fail-closed |
| Anthropic Claude Agent SDK | `canUseTool`(allow/deny) + `PreToolUse`(modify/defer) | 回调级，无 checkpoint | `code.claude.com/docs/en/agent-sdk/{permissions,hooks}` ✅已核实 | modify 归 hook（纠偏）；我们 DB 侧状态机更硬 |
| Google ADK | `request_confirmation` + REST `/run_sse` | invocation_id（启用 Resume 时必带） | `adk.dev/tools-custom/confirmation/` ✅已核实 | 远程审批工程化；我们审批台同事务审计 |
| AutoGen → Agent Framework | `UserProxyAgent` / `selector_func` / Harness don't-ask-again | session state | `microsoft.github.io/autogen/stable/…selector-group-chat.html` ✅已核实；`learn.microsoft.com/en-us/agent-framework/overview` ✅已核实 | 后继关系按官方原文纠偏；human_input_mode 属 0.2 版 |
| CrewAI | webhook HITL + `@human_feedback`(独立页) | 需重传 webhook URLs | `docs.crewai.com/en/learn/human-in-the-loop` ✅已核实 | 「重传上下文」对照我们按 operationId 反查 |
| Agno | `active_requirements` + `confirm()/reject()/continue_run()` | run 状态 + DB | `docs.agno.com/hitl/overview` ✅已核实 | 类型覆盖最全；我们治理发布独有 |
| **Temporal（新增）** | Signals / Queries / Updates | Event History（原生持久） | `docs.temporal.io/encyclopedia/workflow-message-passing` ✅已核实（旧 URL 404） | 审批决定作 Signal；我们同步 wakeup + sweeper 兜底 |
| **AWS Step Functions（新增）** | Activity `taskToken` 回调 + 超时作废 | 状态机执行历史 | `docs.aws.amazon.com/step-functions/latest/dg/concepts-activities.html` ✅已核实 | 「超时作废 token」对照我们 `WHERE state='WAITING_APPROVAL'` |
| **PostgreSQL（新增）** | `FOR UPDATE` / `SKIP LOCKED` 行锁 | 事务 | `postgresql.org/docs/current/sql-select.html` ✅已核实 | 两人并发 decide 的规范基础；锁 ≠ SoD |
| **NIST AI RMF（新增）** | GOVERN/MAP/MEASURE/MANAGE | 治理框架（非运行时） | `nvlpubs.nist.gov/nistpubs/ai/NIST.AI.100-1.pdf` ✅PDF 下载解析 | A5 观测的治理动机；不规定具体字段 |

---

## 四、我们的实现（源码级解剖）

> 本章全部锚点已逐行到源码 / 配置 / DDL 复核。行号以本轮 `Read` / `Grep` 实读为准；证据表（§4.3）汇总成 E-01～E-38，附录 A 再按文件归并。判决性结论（尤其 §4.5 的 7 处不一致）均给出可复核的行号与「撤掉修复点会红吗」式的反证口径。

### 4.1 架构与数据流（关键组件、调用链、数据落点）

本项目的 HITL **不是一条链路，而是两条共享「审批」概念、但落在不同层、由不同状态机守卫的链路**。把二者混为一谈，是既有报告误判「缺运行期暂停-恢复」的根源。

**链路 A · 运行期工具审批（运行中 HITL / PARK-RESUME）**

```
模型返回 ToolCallRequest
   → RunExecutionLoop 驱动单步
   → ToolInvocationGate 逐 call 判定（ToolInvocationGate.java:53）
        ├─ 查审批策略 RiskBasedApprovalPolicy.evaluate(handler)（:71）
        │    required-by-risk: READ 免 / WRITE_PROFILE 可选 / WRITE_EXTERNAL 必审
        │    （application.yml:558-560）
        ├─ 先尝试 approvalPort.consume(tenantId, operationId, argumentDigest,
        │       targetResource, runId)（:1307 / JdbcApprovalRepository.java:224）
        │    CONSUMED → 放行；REJECTED/EXPIRED → 收敛（:1310/:1320）
        └─ 未 CONSUMED → approvalSource.verdictFor(...)(:1337)
             非 APPROVED → requestApproval(...) 登记待办（:1590）
               成功 → result.recordParked(tool, operationId)（:1360-1361/:1944）
               失败 → recordRejected（不挂起，避免永久 WAITING_APPROVAL 泄漏）
   → RunExecutionLoop 见 parked 标记（:1115）
        commitResult(..., "WAITING_APPROVAL", {state:WAITING_APPROVAL})（:1126）
        appendEvent(RUN_PARKED)（:1129-1130）
        releaseLease(...)（:1133）  ← 让出 worker 线程与租约
   → Run 落 WAITING_APPROVAL（非终态）持久化等待

审批台（ApprovalController.java:94 GET 列表 / :124 POST decision）
   → requireAuthorized(APPROVAL_CONSUME)（:131）
   → approvals.decide(...) 落审批事实（:139-140）
   → wakeup.wakeup(tenantId, approvalId)（:147-149，同步唤醒，失败仅 log.warn）

ApprovalDecidedWakeupConsumer.wakeup(...)（:82）
   → approvals.find(...) 取申请
   → claimParkedRunForResume(tenantId, runId, ...)（:101
        / JdbcRunClaimRepository.java:271, WHERE state='WAITING_APPROVAL' :285）
   → ledger.findInvocation(tenantId, operationId)（:113）← 按 operationId 反查原始调用
   → 重建 ModelResponse.ToolCallRequest（不重跑模型）
   → loop.resume(tenantId, task, List.of(call))（:132 / RunExecutionLoop.java:2057）
   → 重入闸门（此时 approval 已 CONSUMED → 放行执行）
   → 返回 RESUMED（:135）

兜底：RunRecoverySweeper.sweep()（:173）每轮 failStaleParkedRuns(parkedTimeout)（:265-267）
   → 超 APPROVAL_TIMEOUT 的孤儿 PARK 收敛为 FAILED（application.yml:294-297）
```

**链路 B · 治理发布审批（运行前 HITL / request-decide-apply 三步）**

```
ConfigPublishWorkflowService.request*()（:165/:192/:237/:278/:300）→ PENDING
   （只登记申请单，不写配置表；:43-44 注释明确）
ConfigPublishWorkflowService.decide()（:324）
   → JdbcConfigPublishRequestRepository.decide(...)（:241）
        inTransaction + SELECT ... FOR UPDATE（:251/:372-376）
        ├─ requesterId == approverId → SelfApprovalException（:253-254）
        ├─ state != PENDING → StateConflictException（:257）
        ├─ updateDecision(...) → APPROVED/REJECTED
        └─ audit.recordRequired(...) 同事务（:267）
   DB CHECK 双保险：two_person_ck approver_id <> requester_id（01_platform_ddl.sql:1873）
ConfigPublishWorkflowService.apply()（:344）
   → 仅 APPROVED 可执行（:351）
   → buildPublishCommand(...) 把 spec.modelRef 固化进 CapAgentDef（:504/:520/:528）
   → resolveBaseline(...) CAS 比对 current_publish_seq（:616）
   → 同事务切 cap_config_pointer（当前版本只由指针指向的 PUBLISHED release 决定，
        绝不以 max(seq) 选：01_platform_ddl.sql:1829-1848）
   → 失败 markFailed(...)（:366）：FAILED 是终态、不自动重试
发布后：cap_agent_def.model_ref 随版本固化（:1640/:1664-1667）
   forbid_published_def_mutation()（:574/:609）禁止已发布定义改写
   （仅 PUBLISHED→DEPRECATED 纯元数据停用放行）
```

**数据落点**：`public.approval`（运行期审批事实，:1456）、`public.cap_approval`（治理期 CAP 审批，:1695）、`public.cap_config_publish_request`（发布申请，:1855）、`public.cap_config_release` / `cap_config_pointer`（发布头/指针，:1829+）、`run.state=WAITING_APPROVAL`（RunState.java:54）、`tool_operation.state=MANUAL_DISPOSED`（:247/:3003）、`review_task.resolve()` 归因回写（JdbcReviewTaskRepository.java:122）。

### 4.2 关键设计决策逐条剖析（决策 → 为什么 → 代价 → 证据锚点）

**D-1｜运行期审批用「PARK + 让出资源」而非「阻塞等审批」**
- 决策：高风险工具需审批时，`ToolInvocationGate` 登记待办后 `recordParked(...)`（:1360-1361），`RunExecutionLoop` 把 Run 迁 `WAITING_APPROVAL`、写检查点、`appendEvent(RUN_PARKED)`、`releaseLease(...)` 让出 worker 线程与租约（:1115-1133）。
- 为什么：审批 TTL 默认 15m、PARK 超时默认 1800s（application.yml:563/:297），若阻塞占线程，worker 池会被长时间挂起的 Run 抽干（呼应 T7 异步解耦）。
- 代价：恢复链路变复杂——需 `claimParkedRunForResume` 抢回租约、按 operationId 从 ledger 反查原始调用、`loop.resume` 重入闸门；恢复正确性依赖「检查点 + 幂等重建」。
- 证据：`RunExecutionLoop.java:1115-1133`、`RunState.java:75`、`application.yml:294-297`。

**D-2｜恢复绝不重跑模型，按 operationId 反查原始调用重建**
- 决策：`ApprovalDecidedWakeupConsumer.wakeup()` 用 `ledger.findInvocation(tenantId, operationId)` 反查（:113），重建 `ToolCallRequest` 后 `loop.resume(tenantId, task, List.of(call))`（:132），类注释明确「恢复绝不重跑模型」（:27）。
- 为什么：规避 AP5「恢复重跑 / 副作用重复」与预算二次扣；也避免模型在等待期间重新决策出不同参数。
- 代价：恢复强依赖 `tool_operation` ledger 的完整性与 operationId 的稳定性；若 invocation 找不到，`commitResult(FAILED, "RESUME_INVOCATION_NOT_FOUND")`（详见 §4.1 唤醒片段）。
- 证据：`ApprovalDecidedWakeupConsumer.java:27/:101/:113/:132/:135`、`RunExecutionLoop.java:2045/:2057`。

**D-3｜PAUSED 语义由 `WAITING_APPROVAL` 专用态承担，恢复入口独立成端口**
- 决策：`RunState` 枚举含 `WAITING_APPROVAL`（:54，注释「等待人工审批（M1 / G2 修复核心 · PARK 态）」），迁移表 `RUNNING→{...,WAITING_APPROVAL}` / `WAITING_APPROVAL→{RUNNING,CANCELLED,TIMED_OUT,FAILED}`（:73-75）；恢复经 `ApprovalWakeupPort` → `ApprovalDecidedWakeupConsumer`（WorkerExecutionConfig.java:807-813 装配）。
- 为什么：把「挂起」做成显式非终态，才能被 sweeper 兜底、被 SSE 表达为「等待审批」、被状态机守卫约束「只能前进」。
- 代价：这是**专用态、绑定工具审批语义**，不是 LangGraph 那种「任意节点 `interrupt` + 任意状态编辑」的通用抽象；迁出集合不含「编辑状态后继续」的路径。
- 证据：`RunState.java:43-54/:72-79`、`WorkerExecutionConfig.java:807-813`。

**D-4｜审批绑定「操作 + 参数摘要 + 目标资源 + 有效期」，消费前重验**
- 决策：`public.approval` 含 `operation_id / argument_digest / target_resource / approver_scope / expires_at`（:1456-1471）；`ApprovalPort.consume(tenantId, operationId, argumentDigest, targetResource, runId)` 消费时先 `FOR UPDATE` 锁 Run 校验取消、再锁审批校验状态/有效期/绑定（JdbcApprovalRepository.java:38/:224/:248/:387/:416）。治理期 `cap_approval` 更用「预占 + 双哈希」（`approval_scope_hash`/`effective_args_hash`/`closure_fingerprint`）在消费时重验参数是否仍落在范围内，防参数替换重放。
- 为什么：规避 AP3「审批 A 工具却执行 B 参数」；`authz_version` 绑定使授权撤销后审批即时失效。
- 代价：参数一变即审批失效、需重新登记；消费路径 SQL 复杂（两次 FOR UPDATE）。
- 证据：`01_platform_ddl.sql:1456-1471/:1695-1722`、`ApprovalPort.java`（`ApprovalOutcome` 含 `ARGUMENT_MISMATCH`/`TARGET_MISMATCH`/`RUN_CANCELLED`）、`JdbcApprovalRepository.java:38/:224/:248`。

**D-5｜审批策略 fail-closed，未接线时一律拒**
- 决策：`RiskBasedApprovalPolicy.evaluate(handler)` 用「风险等级默认要求 `maxWith` 配置值」取**较严**者（:71/:87），`riskLevel()==null` 未配置一律 `required`（:106-110，fail-closed）；`PolicyEngineApprovalSource` 空策略返回 `FAIL_CLOSED`（:189/:210/:214）。`ApprovalReadinessGuard` 在 `live-source-enabled=false` 且 `require-live-source=false` 时 `log.error("运行时人工审批未接线")`（:73）但不阻断启动（:80-81）。
- 为什么：规避「审批台没接好却默认放行」这一最危险的绕过（T4）。
- 代价：**生产默认配置下运行期工具审批链路是「全拒 + 未接线告警」**——`source.mode=none` / `rules: []` / `live-source-enabled=false`（application.yml:569-580）。机制齐备但未启用真实审批台（§4.5 不一致之六）。
- 证据：`RiskBasedApprovalPolicy.java:71/:87/:106-110/:189/:210/:214`、`ApprovalReadinessGuard.java:60/:67/:73/:80-81`、`application.yml:569-580`。

**D-6｜审批决策缺省为「拒绝」，审批人身份只来自令牌**
- 决策：`ApprovalController.DecisionRequest.approve()` 是 `"APPROVE".equalsIgnoreCase(decision)`（:177-180）——decision 缺省/大小写不符/任意其他值**一律当 DENY**（:184）；审批人 `currentPrincipal.principalId()` 来自令牌（:139-140），不接受请求体传审批人。
- 为什么：fail-closed 的延伸——含糊输入不得被解释为批准；审批人不可被客户端伪造（A4 可追溯）。
- 代价：客户端必须显式、准确地传 `decision="APPROVE"`，否则静默按拒绝处理（需 API 文档明示）。
- 证据：`ApprovalController.java:124-140/:175-184`。

**D-7｜拒绝/过期/无权限给显式终态，404 不区分「不存在」与「无权限」**
- 决策：`ApprovalController` 对 404 明确「不区分不存在与无权限」（:158-160，避免存在性探测通道）；过期/已被决定返回 409 Conflict（:162-163）；审批审计同事务（JdbcConfigPublishRequestRepository.java:267）。FAILED/REJECTED/EXPIRED 是终态、不自动重试（ConfigPublishWorkflowService.java:366）。
- 为什么：铁律 15「终态/权限错误不得伪装成临时故障：404/403/401 须显式终态 + 停轮询 + 不给重试」在 HITL 语境的落地（规避 AP4）。
- 代价：客户端必须实现「见到终态即停轮询、区分拒绝与临时故障」的语义，否则会误重试（§7 改进点 7 的动机）。
- 证据：`ApprovalController.java:158-163`、`ConfigPublishWorkflowService.java:344-366`、`JdbcConfigPublishRequestRepository.java:267`。

**D-8｜治理发布三步分离 + 双人复核双保险 + CAS 基线**
- 决策：`request → decide → apply` 三步，前两步都不写配置表（ConfigPublishWorkflowService.java:43-44）；`decide` 在同事务内 `FOR UPDATE`（:251/:372-376）+ 应用层 `requesterId==approverId → SelfApprovalException`（:253-254）+ `state!=PENDING → StateConflictException`（:257）+ DB `two_person_ck`（01_platform_ddl.sql:1873）；`apply` 仅 APPROVED 可执行并 CAS 比对 `current_publish_seq`（:351/:616）。
- 为什么：把「申请 / 批准 / 执行」分成三个权限动作（`config:request`/`config:approve`/`config:publish`，AuthorizationService.java:99-130）+ 动态 SoD，任何一步都不能跳过；CAS 防并发覆盖（呼应 D-4 的基线思想）。
- 代价：三步都要落库、都要审计、都要 RLS 绑定；流程长、吞吐低（治理发布本就低频，可接受）。
- 证据：`ConfigPublishWorkflowService.java:43-44/:324/:344-366/:504/:616`、`JdbcConfigPublishRequestRepository.java:241-283/:317-376`、`01_platform_ddl.sql:1873`。

**D-9｜治理期绑定、运行期钉死（不可变发布 + modelRef 不回落）**
- 决策：`cap_agent_def.model_ref`（:1640）随治理发布固化进版本，注释明确「NULL=未绑定 → 运行期取不到模型标识即拒绝执行该 Run（**绝不回落默认模型**）」（:1664-1667）；`forbid_published_def_mutation()`（:574/:609）禁止已发布定义改写，仅 `PUBLISHED→DEPRECATED` 纯元数据停用放行。当前版本只由 `cap_config_pointer` 指向的 PUBLISHED release 决定，「绝不以 max(seq) 选、绝不扫描历史行」（:1829-1848）。
- 为什么：可复现、可追责、杜绝「运行期临时改边」（T6）——任何模型/提示词变更都要重走治理发布审批。
- 代价：改一点配置就要重走完整审批流；运行期无法对单个 Run 临时换模型（维度 03 快照不可变的同一取舍）。
- 证据：`01_platform_ddl.sql:574/:609/:1640/:1664-1667/:1829-1848`、`ConfigPublishWorkflowService.java:520/:528`。

**D-10｜权限动作分离 + 唯一权限解析 fail-closed**
- 决策：`AuthorizationService.Actions` 把 `APPROVAL_CONSUME`（:76）、`UNKNOWN_DISPOSE`（:78）、`CONFIG_REQUEST/APPROVE/PUBLISH/ROLLBACK`（:99-130）拆开；注释明确「`config:approve` 仍不能消除自我审批——权限与身份是两件事，须 `approver_id <> requester_id` 兜底（应用层 + DB CHECK 双保险）」（:103-105）；`AgentOverrides.parse()` 解析失败抛 `ClosureResolutionException`（:29/:49/:57，fail-closed，不得退化成「当没有覆盖」）。
- 为什么：维度 07 的「唯一权限解析 + fail-closed」铁律在 HITL 的延伸——审批权限不能被覆盖解析错误悄悄放大。
- 代价：动作矩阵膨胀（:169-170 列出全部动作）；新增审批动作需同步角色权限矩阵。
- 证据：`AuthorizationService.java:76/:78/:99-130/:169-170`、`AgentOverrides.java:29/:49/:57`。

**D-11｜PARK 超时兜底，且超时必须大于审批 TTL**
- 决策：`RunRecoverySweeper.sweep()`（:173）调用 `failStaleParkedRuns(tenantId, parkedTimeout)`（:265-267），把「永久停在 WAITING_APPROVAL、不报错不告警」的孤儿收敛为 FAILED；application.yml 注释明确 `parked-timeout-seconds` 必须大于 `agent.approval.ttl`，否则「人还没批 Run 就被超时失败」（:294-297），错误码 `APPROVAL_TIMEOUT`、工具未执行、无副作用。
- 为什么：规避 AP6「暂停态泄漏」；把「审批迟迟不来」变成可观测、可告警的终态，而非静默悬挂。
- 代价：TTL 与超时是两个配置项，配错会产生「审批还没到期、Run 先失败」的隐蔽 bug（已用注释约束，但无运行时校验）。
- 证据：`RunRecoverySweeper.java:173/:265-270`、`application.yml:277/:294-297`、`JdbcRunClaimRepository.java:576/:602`。

**D-12｜UNKNOWN / 异常人工处置 `MANUAL_DISPOSED`**
- 决策：`tool_operation.state` 含 `MANUAL_DISPOSED`（:247），须 `manual_disposed_by/manual_disposed_at` 非空（CHECK :3003）；`enforce_run_state_transition` 把 `MANUAL_DISPOSED` 列为不可再迁出的终态之一（:527）；动作受 `UNKNOWN_DISPOSE`（AuthorizationService.java:78）管控。
- 为什么：维度 11 的 UNKNOWN 对账后，由人一次性裁定「这个不确定结果怎么处置」，留痕可追责。
- 代价：人工处置是终态、不可逆，裁定前须有充分上下文。
- 证据：`01_platform_ddl.sql:247/:527/:3003`、`AuthorizationService.java:78`。

**D-13｜归因回写做成「可缺失的扩展点」而非硬编码**
- 决策：`ReviewAttributionHook` 是端口接口（ReviewAttributionHook.java:29/:41，注释「产品定口径」「默认 NoOp 自动让位」:23）；`JdbcReviewTaskRepository.resolve()` 仅当 `attributionHook != null` 且 `rootCause ∈ {PROMPT, CONFIG, MODEL}` 时调用 `onAttributionResolved(...)`（:122-149），钩子异常 fail-soft 仅 `log.warn`（任务状态是事实、不回滚）；`ApplicationConfiguration.reviewTaskPort()` 用 `ObjectProvider<ReviewAttributionHook>.getIfAvailable()` 注入（:292-302），注释明确「产品口径未定时没有任何实现，此时仓储走 NoOp（resolve 不回写）」（:297）。
- 为什么：归因结论属于产品口径（把 Review 结论回写到优化闭环），口径未定前不臆造；用可缺失 Bean 让编排与实现解耦。
- 代价：**生产当前无 Bean 实现 → 归因结论不回写**，`review_task.resolve()` 只关闭任务、不驱动迭代。这是「预留扩展点 + 未闭环能力」，非死代码（§4.5 不一致之二）。
- 证据：`ReviewAttributionHook.java:23/:29/:41`、`JdbcReviewTaskRepository.java:122-149`、`ApplicationConfiguration.java:292-302`。

### 4.3 关键证据表（类/方法/DDL/配置，含文件路径与行号）

> 下表 E-01～E-38 是本报告第四章的全部可复核锚点；附录 A 按文件归并去重。

| 编号 | 证据锚点（文件路径:行号） | 说明 |
|---|---|---|
| E-01 | `platform/agent-core/.../core/run/RunState.java:43-54` | `WAITING_APPROVAL` 枚举定义（PARK 态、非终态） |
| E-02 | `RunState.java:72-79` | 状态迁移表，`RUNNING→WAITING_APPROVAL`、`WAITING_APPROVAL→{RUNNING,CANCELLED,TIMED_OUT,FAILED}` |
| E-03 | `deploy/sql/platform/01_platform_ddl.sql:450-475` | `enforce_run_state_transition()` DB 触发器守卫 |
| E-04 | `agent-worker/.../execute/RunExecutionLoop.java:1115-1133` | PARK：迁 WAITING_APPROVAL、写检查点、RUN_PARKED 事件、释放租约 |
| E-05 | `RunExecutionLoop.java:2045/:2057/:2084-2093` | `resume()` 与恢复后「仍判挂起则重新 PARK」 |
| E-06 | `agent-worker/.../execute/ApprovalDecidedWakeupConsumer.java:27/:82/:101/:113/:132/:135` | wakeup 全链路、按 operationId 反查、不重跑模型、RESUMED |
| E-07 | `agent-persistence/.../JdbcRunClaimRepository.java:266/:271/:285` | `claimParkedRunForResume`（WHERE state='WAITING_APPROVAL'） |
| E-08 | `JdbcRunClaimRepository.java:576/:602` | sweeper 兜底注释 + 条件 UPDATE |
| E-09 | `agent-persistence/.../JdbcApprovalRepository.java:38/:121/:181/:224/:248/:308/:387/:416` | grant/request/consume/revoke + 两次 FOR UPDATE |
| E-10 | `agent-ports/.../persistence/ApprovalPort.java` | `ApprovalOutcome` 枚举（含 ARGUMENT_MISMATCH/TARGET_MISMATCH/RUN_CANCELLED）、`request()` 默认空 |
| E-11 | `agent-worker/.../execute/ToolInvocationGate.java:53/:1307-1361/:1590/:1605-1606/:1944` | 闸门消费/登记待办/recordParked/requestApproval |
| E-12 | `agent-application/.../approval/RiskBasedApprovalPolicy.java:43/:71/:87/:106-110/:128/:169/:175/:189/:210/:214` | 按风险评估 + FAIL_CLOSED + PolicyEngineApprovalSource |
| E-13 | `agent-api/.../api/governance/ApprovalController.java:32/:52/:94/:124/:131/:139-149/:158-163/:175-184` | 审批台端点、同步唤醒、404/409 终态、approve() 缺省拒绝 |
| E-14 | `agent-ports/.../security/AuthorizationService.java:31/:51/:56/:76/:78/:99/:103-105/:107/:122/:130/:169-170` | 审批动作权限 + 自我审批注释 |
| E-15 | `agent-application/.../cap/AgentOverrides.java:29/:39/:49/:57` | 唯一权限解析、解析失败 fail-closed |
| E-16 | `agent-application/.../cap/ConfigPublishWorkflowService.java:43-44/:165/:192/:237/:278/:300/:324/:344-366/:504/:520/:528/:616` | 三步发布、modelRef 固化、CAS 基线、FAILED 终态 |
| E-17 | `agent-persistence/.../JdbcConfigPublishRequestRepository.java:67/:140/:241/:251/:253-254/:257/:267/:283/:317-319/:351/:356/:372-376` | decide 并发正确性、FOR UPDATE、双人复核、审计同事务 |
| E-18 | `agent-persistence/.../cap/JdbcConfigPublishTransaction.java` | 发布三件事同事务 + RLS GUC/PLATFORM 作用域隔离 |
| E-19 | `deploy/sql/platform/01_platform_ddl.sql:109/:112` | `approval_state` 枚举 |
| E-20 | `01_platform_ddl.sql:125/:132/:140` | `cap_approval_state` 枚举（AWAITING_HUMAN）+ 状态机注释 |
| E-21 | `01_platform_ddl.sql:304/:307-331` | `enforce_approval_state_transition()` |
| E-22 | `01_platform_ddl.sql:335/:338-373` | `enforce_cap_approval_state_transition()`（状态只能前进、终态不可迁出） |
| E-23 | `01_platform_ddl.sql:1456-1471` | `public.approval` 表结构 + CHECK |
| E-24 | `01_platform_ddl.sql:1695-1722` | `public.cap_approval` 表 + AWAITING_HUMAN CHECK |
| E-25 | `01_platform_ddl.sql:1855/:1873` | `cap_config_publish_request` + `two_person_ck` |
| E-26 | `01_platform_ddl.sql:1826-1848` | `cap_config_pointer`（绝不以 max(seq) 选） |
| E-27 | `01_platform_ddl.sql:1936` | `cap_config_release_two_person_ck` |
| E-28 | `01_platform_ddl.sql:571/:574/:609` | `forbid_published_def_mutation()` 已发布不可改写 |
| E-29 | `01_platform_ddl.sql:1640/:1664-1667` | `cap_agent_def.model_ref` + 绝不回落默认模型注释 |
| E-30 | `01_platform_ddl.sql:247/:527/:3003` | `MANUAL_DISPOSED` 终态 + 需 actor/at |
| E-31 | `01_platform_ddl.sql:4224/:4280` | 审批 pending / cap_approval 状态索引 |
| E-32 | `agent-ports/.../quality/ReviewAttributionHook.java:23/:29/:41` | 归因回写端口 + 默认 NoOp |
| E-33 | `agent-persistence/.../JdbcReviewTaskRepository.java:75/:82/:88/:122/:132-139/:149` | resolve 回调接线 + ITERATION_ROOT_CAUSES |
| E-34 | `platform-bootstrap/.../config/ApplicationConfiguration.java:292-302` | `reviewTaskPort` 用 `getIfAvailable()`，无 Bean → NoOp |
| E-35 | `platform-bootstrap/.../config/ApprovalReadinessGuard.java:23/:37/:60/:67/:73/:80-81/:93` | 审批就绪自检 + 过时注释 |
| E-36 | `platform-bootstrap/.../config/WorkerExecutionConfig.java:346/:373/:377/:446/:469-470/:807-813` | 审批策略/闸门/唤醒器生产装配 |
| E-37 | `platform-bootstrap/.../worker/RunRecoverySweeper.java:52/:116/:173/:265-270` | PARK 超时兜底 |
| E-38 | `platform-bootstrap/src/main/resources/application.yml:277/:294-297/:554-580/:685` | 审批策略/TTL/scope/live-source/PARK 超时/角色清单 |
| E-39 | `agent-persistence/src/test/.../cap/ConfigPublishIT.java:139/:242/:273` | 并发测试针对「CAS 指针抢占/变基重试」 |
| E-40 | `agent-persistence/src/test/.../cap/ConfigPublishManagementIT.java:303-311/:368-369` | 单人 selfApproval / staleBaseline，无并发 decide |

### 4.4 我们独有的设计（差异化设计）

- **差异化 1｜DB 触发器守卫审批状态机（L3）**：`enforce_approval_state_transition`（:307）、`enforce_cap_approval_state_transition`（:338-366）、`enforce_run_state_transition`（:453）三条 DB 触发器把「状态只能前进、终态不可迁出」压到数据层。竞品（LangGraph/ADK/Agno）的审批状态机几乎都在应用层/框架层，**绕过应用代码即可绕过审批**；本项目绕过应用层仍受 DB 约束。这是 A1 抗绕过性的结构性来源。
- **差异化 2｜预占 + 双哈希防参数替换重放**：`cap_approval` 的 `approval_scope_hash`/`effective_args_hash`/`closure_fingerprint`（梳理报告第 13 章 / 竞品报告维度 13）在消费时重验参数是否仍落在批准范围，配合 `authz_version` 绑定使审批随授权撤销即时失效。竞品普遍只批「工具名」不批「参数+目标资源+有效期」的绑定快照。
- **差异化 3｜双人复核「应用层 + DB CHECK」双保险**：`SelfApprovalException`（应用层）+ `two_person_ck`（DB）双重约束，且 `config:request/approve/publish/rollback` 四个权限动作分离。竞品中几乎无人内建动态 SoD。
- **差异化 4｜治理期绑定 / 运行期钉死 + modelRef 不回落**：`model_ref` 随发布固化、已发布定义不可改写、当前版本只认指针，且「NULL 未绑定直接拒、绝不回落默认模型」。这是把「人工批准过什么」与「运行期实际用什么」强绑定的做法，竞品的 HITL 多聚焦运行期交互、不治理「模型/定义上线」这一环。
- **差异化 5｜PARK 释放租约 + 异步唤醒 + 不重跑模型恢复**：把「等审批」做成非阻塞的持久化挂起，恢复时按 operationId 反查原始调用重建而非重新调模型，并有 sweeper 超时兜底。相比 LangGraph「节点从头重跑」，本项目在「专用工具审批门」这一场景下的**恢复幂等性更强**（不重跑模型、不重复扣预算）。
- **差异化 6｜审批决策缺省拒绝 + 404 不区分存在性 + 决策后同步唤醒**：把 fail-closed 与存在性防探测做进审批台，且审批决定落库后同请求内触发 `wakeup`——竞品多把「审批」与「恢复」分属两套异步系统。

### 4.5 与既有梳理报告 / 竞品报告 / MEMORY 的不一致清单（7 处）

> 这一节是收官篇对前序文档的反哺：把「源码实证」与「既有文字结论」逐条对齐。每条给「既有说法 / 源码真相 / 精确化后的表述 / 反证口径」。

**不一致 1（重大）｜「缺运行期暂停-恢复 / 弱于 LangGraph」需精确化，源码已有完整 PARK/RESUME 闭环**
- 既有说法：`docs/Agent平台竞品对比报告_2026-11-15.md:420`「HITL 目前以『工具执行前审批 + 治理发布审批』为主，运行期对话中动态中断编辑能力弱于 LangGraph」、`:440`「缺任意点中断+状态编辑」、`:459`「运行期 HITL 灵活性：缺 LangGraph interrupt()+Command(resume) 式任意点中断编辑」；`docs/能力维度实现方式梳理报告.md:198-205` 第 13 章未提及 `WAITING_APPROVAL` PARK/RESUME；用户原始素材假设「缺状态机里的 PAUSED 态与恢复入口」。
- 源码真相：`RunState.WAITING_APPROVAL`（E-01/E-02）即事实上的 PAUSED 态；`RunExecutionLoop.java:1115-1133` 挂起写检查点/释放租约（E-04）；`ApprovalDecidedWakeupConsumer` + `claimParkedRunForResume` + `loop.resume()` 是完整恢复入口（E-06/E-07/E-05）；`RunRecoverySweeper` 超时兜底（E-37）。**运行期暂停-恢复不仅存在，而且是持久化、让出资源、不重跑模型、有超时收敛的闭环**。
- 精确化表述：既有报告并未「错误地声称完全没有运行期暂停」，而是**遗漏记载了这套 PARK/RESUME 机制**，容易被读成「缺 PAUSED 态与恢复入口」。准确结论是：「本项目**有**面向高风险工具审批的专用运行期暂停-恢复（L2 下沿），**弱于** LangGraph 的是『任意节点 interrupt + 任意状态编辑 + 通用恢复入口』这层泛化能力（A2），不是『有没有暂停-恢复』本身」。
- 反证口径：撤掉 `RunState.java:75` 的 `WAITING_APPROVAL→RUNNING` 迁移，`ApprovalDecidedWakeupConsumerTest` / `RunExecutionLoopTest` 应立刻变红——证明恢复入口真实存在、非纸面设计。

**不一致 2｜`ReviewAttributionHook` 不是「死代码/NoOp 未接线」，是「接线完整但生产无实现」**
- 既有说法：`.workbuddy/memory/MEMORY.md:49`「`ReviewAttributionHook` NoOp」；用户素材「尤其 ReviewAttributionHook 是否真的是 NoOp」。
- 源码真相：端口接口存在（E-32）；`JdbcReviewTaskRepository.resolve()` **已经接线**——当 `attributionHook != null` 且 rootCause ∈ {PROMPT,CONFIG,MODEL} 时调用 `onAttributionResolved`，钩子异常 fail-soft 仅 warn、任务状态不回滚（E-33）；`ApplicationConfiguration.reviewTaskPort()` 用 `getIfAvailable()`，注释明确「产品口径未定时没有任何实现 → NoOp」（E-34）。
- 精确化表述：这是**「预留扩展点 + 未闭环能力」**——接线（端口 + 回调 + 装配点）完整，唯独生产缺一个 `ReviewAttributionHook` Bean 实现，导致 `resolve()` 不回写、人工复核结论不驱动迭代闭环。判为「产品口径未定下的有意 NoOp」，不是「完全没接线」也不是「已闭环」。
- 反证口径：临时注入一个计数用 `ReviewAttributionHook` Bean，`resolve()` 一个 `rootCause=PROMPT` 的任务，断言回调被调用 1 次——证明接线有效；无 Bean 时断言 0 次——证明生产 NoOp。

**不一致 3｜治理审批「两人同时通过会不会破坏状态机」——机制上不会，缺的是专项并发门禁**
- 既有说法：MEMORY.md:48「治理审批并发（缺）」被列为「不具备规模化发布条件」的短板之一；用户素材「并发审批两人同时通过会不会破坏状态机？」
- 源码真相：`JdbcConfigPublishRequestRepository.decide()` 在同事务内 `SELECT ... FOR UPDATE`（E-17/:372-376），行锁把两人串行化；第二人读到非 `PENDING` → `StateConflictException`（:257）；再加 `two_person_ck`（E-25）与审计同事务（:267）。**并发防护机制是齐的，状态机不会被破坏**。
- 精确化表述：MEMORY 的「缺验证」结论**属实但需精确化**——缺的是「两个审批人并发 decide 同一申请单」的**专项验收门禁测试**，不是并发防护本身。现有 `ConfigPublishIT` 的并发用例是「指针 CAS 抢占/变基重试」（E-39），`ConfigPublishManagementIT` 只有单人 `selfApprovalIsRejected`（E-40），**没有覆盖 decide 并发**。
- 反证口径：见 §7 改进点 4 的 `CountDownLatch` 双线程 decide 门禁——两人同时批准，断言恰好一人成功、一人 `StateConflictException`、审计恰一条、最终状态唯一。

**不一致 4｜`ApprovalReadinessGuard` 注释与 `ApprovalController` 实现不一致（过时注释）**
- 既有说法：`ApprovalReadinessGuard.java:23` 注释「`agent-api` 的 `ApprovalController` 操作的是另一条链路（能力/配置发布域），并不覆盖工具副作用执行域」。
- 源码真相：`ApprovalController.java:94/:124/:147-149` 明确是 `/api/approvals` 运行期工具审批台，且 `decide` 后调用 `ApprovalWakeupPort.wakeup()` 唤醒挂起 Run（E-13/E-36）——它**恰恰就是工具副作用执行域的审批链路**。
- 精确化表述：该注释是早期「审批台只做治理发布」时期留下的过时描述，与当前实现不符，应更正（否则会误导维护者以为运行期审批没接线）。这是**文档级不一致，非功能缺陷**。
- 反证口径：`grep -n "ApprovalWakeupPort" ApprovalController.java` 命中 `:147-149` 即证。

**不一致 5｜「PAUSED 态」提法需改用项目实际术语 `WAITING_APPROVAL`**
- 既有说法：用户素材「对照 LangGraph checkpoint + interrupt 判断是否缺 PAUSED 态和恢复入口——缺的是状态机里的 PAUSED 态与恢复入口」。
- 源码真相：项目没有名为 `PAUSED` 的枚举值，PAUSED 语义由 `WAITING_APPROVAL`（E-01）承担，且恢复入口（E-06）齐备。
- 精确化表述：不是「缺 PAUSED 态」，而是「已有**专用** `WAITING_APPROVAL` 态 + 恢复入口，缺的是把它**泛化为任意人工暂停/可编辑状态**的抽象」。§7 改进点 1 据此把方案从「新增 PAUSED 态」修正为「在现有 WAITING_APPROVAL 基础上引入通用 `pause_reason` + 状态编辑通道」，避免重复造态、与 DB 触发器状态机守卫冲突。

**不一致 6｜运行期工具审批「机制齐备但生产默认未接线」（既有报告未点明）**
- 既有说法：梳理报告第 13 章 / 竞品报告维度 13 均把 `approval` / `ToolInvocationGate` 描述为已实现的运行期审批门。
- 源码真相：`application.yml:569-580` 默认 `live-source-enabled: false` / `require-live-source: false` / `source.mode: none` / `policy.rules: []` → `PolicyEngineApprovalSource` 空策略全 `FAIL_CLOSED`（E-12）；`ApprovalReadinessGuard` 只 `log.error` 不抛异常（E-35）。
- 精确化表述：机制（表、端口、闸门、PARK/RESUME、审批台、唤醒、兜底）**全部齐备**，但**生产默认配置下运行期工具审批是「全拒 + 未接线告警」**——即「代码有、开关没开」。这与梳理报告「已实现」不矛盾，但收官篇必须显式标注，否则读者会以为生产已在运行真实审批台。属「有意保留的未接线状态」（T4-A 站位）。

**不一致 7｜OpenAI Agents SDK「无内建运行期 HITL」需区分「无通用原语」与「有审批信号」**
- 既有说法：用户素材「OpenAI Agents SDK（无内建运行期 HITL，靠 handoffs 到人类 / 外部审批工具）」；竞品报告 `:404`「Guardrails 内嵌审批」。
- 源码/官方真相：官方 Guardrails 页出现 "pending approval interruption is emitted" 与 FunctionTool 需批准概念；handoffs 是 agent 间转交 pipeline，不等同审批原语；**未证明存在 `interrupt()`+checkpoint 持久化暂停/恢复**。
- 精确化表述：二者不冲突——「无内建一等公民 HITL / 无通用 durable pause-resume」为真，但补充「有 guardrail 级审批中断信号、由宿主自管暂停与续跑」。本报告 §3.2 已按此诚实表述，未把 OpenAI 写成「完全无审批」，也未把 guardrail 信号拔高为通用 HITL 引擎。

> §4.5 的 7 处（含 1 处重大发现、3 处判定精确化、2 处文档/术语修正、1 处诚实边界补全）在文末「诚实边界声明」第一/二/三/五节分别归类。收官篇据此对前序 12 份报告的「运行期 HITL 灵活性」结论给出统一更正口径。

### 4.6 完整调用链复核与运行期可达性四问（本轮新增）

> **行号口径**：§4.1–§4.4 与附录 A 的部分行号是写作时快照；本轮实际 `Read`/`Grep` 发现 DDL 已漂移——`two_person_ck` 报告记 `:1873`，实读 **`:1882`**；`enforce_run_state_transition()` 报告记 `:450-475`，实读 **`:462-484`**（触发器挂载 `:4837`）；`cap_config_pointer` 报告记 `:1826-1848`，实读 **`:1838-1857`**；`approval` 表 `:1456`→实读 **`:1465`**，`cap_approval` 表 `:1695`→实读 **`:1704`**，`idx_approval_pending` 实读 **`:4233`**。因「零删改」约束原文锚点原样保留，**本节与附录 D 以本轮实读为准**。
> **四问口径**（对齐 MEMORY「运行期可达性审计」）：① **被装配**（有 Bean / 调用方 / 路由）② **被写入**（数据真落库）③ **被注入 / 被触发**（运行期真执行）④ **被看见**（有指标 / 告警 / 守卫消费）。四问全过才算「运行期可达」；「仅静态存在、无调用方、无消费方」是**明确判定**，不是省略。

#### 链 1｜运行期工具审批 PARK → 唤醒 → RESUME（本维度的核心闭环）

```
模型返回 ToolCallRequest
 → ToolInvocationGate（闸门逐 call 判定；Report §4.1 链路 A）
 → recordParked(...) → RunExecutionLoop:1135-1143
      commitResult(WAITING_APPROVAL) → appendEvent(RUN_PARKED) → releaseLease  ← 让出 worker
 → 审批人 ApprovalController.decide:124-166
      approvals.decide → wakeup.wakeup:147-149（同步；异常仅 log.warn :151-152）
 → ApprovalDecidedWakeupConsumer.wakeup:82-136
      approvals.find:84 → claimParkedRunForResume:101
        → JdbcRunClaimRepository:271-299（WHERE state='WAITING_APPROVAL' :285, fencing_token+1 :280）
      ledger.findInvocation:113 → 重建 ToolCallRequest:128-129（不重跑模型）
      loop.resume:132 → RunExecutionLoop.resume:2073-2109（仍挂起则重新 PARK :2100-2109）
 → 重入闸门，此轮 approval 已 CONSUMED → 放行执行
```

**四问判定**：①✅ `WorkerExecutionConfig.java:807-813` 装配 `ApprovalWakeupPort` 为 `ApprovalDecidedWakeupConsumer`；`ApprovalController.java:71-80` 用 `ObjectProvider.getIfAvailable()` 注入（可缺失）。②✅ `run.state='WAITING_APPROVAL'`（`RunExecutionLoop.java:1135-1136`）+ `approval.state`（`enforce_approval_state_transition` DDL `:316-343` / 触发器 `:4685`）。③✅ `decide` 落库后同步调用 `wakeup`（`ApprovalController.java:147-149`）。④⚠️ **无「挂起时长 / 唤醒延迟」指标**；唤醒失败只 `log.warn`（`:151-152`），无告警通道（与 11 号报告 B-3「机制先于可观测」同族）。
**复核命令**：`grep -n "claimParkedRunForResume\|RESUME_INVOCATION_NOT_FOUND\|releaseLease" platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ApprovalDecidedWakeupConsumer.java platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java`。

#### 链 2｜治理发布并发 decide + two_person_ck 双保险（验证补强，非缺陷修复）

```
ConfigPublishWorkflowService.decide:324
 → JdbcConfigPublishRequestRepository.decide:241-287
      TransactionalConnectionHolder.inTransaction:247
      loadForUpdate:251 / :372-381 → SELECT … FOR UPDATE（:376）
      应用层：requesterId == approverId → SelfApprovalException:253-254
              state != PENDING → StateConflictException:256-259
      updateDecision:263 → audit.recordRequired 同事务:267-273
      DB CHECK：cap_config_publish_request_two_person_ck（DDL :1882）
              cap_config_release_two_person_ck（DDL :1945）
      DB 触发器：enforce_cap_approval_state_transition（DDL :347-382 / 触发器 :4713）
 → apply:344-366 仅 APPROVED 可执行；FAILED 终态不重试（:366）
```

**四问判定**：①✅ `ConfigPublishWorkflowService` 由 bootstrap 装配（`WorkerExecutionConfig` / 治理配置）。②✅ `state` / `approver_id` / `decided_at` 落库 + 审计同事务。③✅ 经治理 API 触发。④❌ **「两个审批人并发 decide 同一申请单」专项 DB IT 门禁仍未闭环**——现有 `ConfigPublishIT.java:139/242/273` 并发用例针对「指针 CAS 抢占 / 变基重试」，`ConfigPublishManagementIT.java:303-311/368-369` 只有单人 `selfApproval` / `staleBaseline`（§4.5 不一致 3、P0 验收报告 §7.1、MEMORY.md:62）。**这属验证补强而非缺陷修复**：`FOR UPDATE` + 应用层状态前置 + `two_person_ck` 已保证并发不破坏状态机（静态强证据），缺的是一条可回归的真实并发夹具。
**复核命令**：`grep -n "FOR UPDATE" platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcConfigPublishRequestRepository.java`；`grep -n "two_person_ck" deploy/sql/platform/01_platform_ddl.sql`。

#### 链 3｜归因回写 ReviewAttributionHook（接线完整但生产 NoOp——真实缺口）

```
端口：ReviewAttributionHook:29 / onAttributionResolved:41（文档 :9-27「产品定口径，默认 NoOp 自动让位」）
 → JdbcReviewTaskRepository.resolve:122-146
      updateWithPrecondition 先真正关闭任务（:123-127，0 行命中即抛）
      if (attributionHook != null && rootCause ∈ ITERATION_ROOT_CAUSES) :132-133
          attributionHook.onAttributionResolved(...):139-140
          catch RuntimeException → log.warn，任务不回滚 :141-144
      ITERATION_ROOT_CAUSES = {PROMPT, CONFIG, MODEL} :149-150
 → ApplicationConfiguration.reviewTaskPort:292-302
      hookProvider.getIfAvailable():302 → 生产无 Bean ⇒ 注入 null ⇒ resolve 不回调
 → 全仓 grep "implements ReviewAttributionHook"（main 侧）⇒ 零命中（本轮实测）
```

**四问判定**：①⚠️ 端口 / 回调 / 装配点**齐全**，但**生产无 Bean 实现** ⇒ 注入为 null。②❌ 归因结论**不回写**（`review_task.resolve()` 只关任务）。③✅ `resolve` 触发时会走到 `attributionHook != null` 判定，但生产恒 false。④❌ 无「归因回写率」指标。**判定**：这是「预留扩展点 + 未闭环能力」，非死代码、非已闭环（§4.5 不一致 2）；MEMORY.md:49「ReviewAttributionHook NoOp」的结论方向对但过于粗糙（**已被复核纠偏**：接线是完整的）。
**复核命令**：`grep -rn "implements ReviewAttributionHook" platform --include=*.java | grep -v "/test/"`（预期 main 侧零命中）；`sed -n '292,302p' platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApplicationConfiguration.java`。

#### 链 4｜审批台 UI 与终态语义（部分已纠偏 + 真实缺口）

```
运行期审批台（frontend）：
  App.tsx:194 路由 /approvals（RequireRole area="approvals"）
  GovernancePages.tsx:75-94 submit → decideApproval:83
     catch :87-90 → 文案「操作失败，请稍后重试」  ← 铁律 15 风险：4xx 终态被写成「稍后重试」
  GovernancePages.tsx:179-188 展示 参数摘要 / 目标资源 / 有效期 / 关联运行
     （FR-014 已落地 → §5.3 劣势 2「审批台不展示参数摘要」已被复核纠偏：
       参数摘要/目标资源/有效期已展示；risk_level 仍未展示，此半句成立）
  GovernancePages.tsx:242-260 「批准」主按钮 + 「拒绝」次按钮；:263 过期项只给拒绝
     （AP7「默认勾选通过」不成立 → 已被复核纠偏；但无 risk_level 展示半句成立）
  client.ts:425-435 decideApproval（无 Idempotency-Key：本轮 grep 零命中 → 改进点 7 缺口）
治理发布审批台（admin-console）：
  PublishRequestDetailPage.tsx:28-31 同人审批前端预检「提示不是边界，真正拦截在后端」
  PublishRequestDetailPage.tsx:177 审批 Card；decision ∈ {APPROVE, REJECT}
服务端终态：ApprovalController:157-165（404 不区分存在性 / 409 显式冲突）
```

**四问判定**：①✅ 两个前端均有审批 UI 且路由可达。②N/A（前端不落库，落点在服务端 `approval` / `cap_config_publish_request`）。③✅ 人工点击触发 decide。④⚠️ 有错误提示但**终态语义未闭合**：`GovernancePages.tsx:89` 对 403/404 给出「请稍后重试」，与铁律 15「禁用『稍后再试』」冲突；`ApprovalController` 无幂等键，5xx 重试可能二次 decide 撞 409。
**复核命令**：`grep -n "请稍后重试\|argumentDigest\|riskLevel" frontend/src/pages/governance/GovernancePages.tsx`；`grep -n "Idempotency-Key" platform/agent-api/src/main/java/cn/studykid/multiagent/api/governance/ApprovalController.java`（预期零命中）。

#### 链 5｜PARK 超时兜底（AP6 防线）

```
RunRecoverySweeper.sweep:173（@Scheduled fixedDelay 30s，:172）
 → 每租户 claimPort.failStaleParkedRuns(tenantId, parkedTimeout):267
 → JdbcRunClaimRepository.failStaleParkedRuns:589-602
      WHERE state='WAITING_APPROVAL'（:602）+ 到期条件 → 收敛 FAILED（APPROVAL_TIMEOUT）
 → 配置 application.yml:297 parked-timeout-seconds=1800s，必须 > ttl=15m（:563）
```

**四问判定**：①✅ `@Component` + `@Scheduled`（`RunRecoverySweeper.java:172`）。②✅ `run.state='FAILED'`、`error_code='APPROVAL_TIMEOUT'`。③✅ 每 30s 真实执行。④⚠️ `healthy()` / `unhealthyReason()`（`:155-156` / `:139-140`）**无 HealthIndicator 消费**（与 11 号报告 D-46 同族）——「恢复链路已死」不可被告警；超时事件无 Gauge。
**复核命令**：`grep -n "failStaleParkedRuns\|@Scheduled\|healthy()" platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/worker/RunRecoverySweeper.java`；`grep -n "parked-timeout-seconds\|ttl:" platform/platform-bootstrap/src/main/resources/application.yml`。

#### 4.6 五链可达性汇总与三条判读

| 链 | ①装配 | ②写入 | ③触发 | ④看见 | 综合判定 |
|---|---|---|---|---|---|
| 链 1 PARK→RESUME | ✅ | ✅ | ✅ | ⚠️ 无挂起时长/唤醒延迟指标 | 运行期可达，可观测性缺一半 |
| 链 2 治理 decide 并发 | ✅ | ✅ | ✅ | ❌ 并发 decide 专项门禁未闭环 | 机制可达，**验证缺口**（非缺陷） |
| 链 3 归因回写 | ⚠️ 装配齐、Bean 缺 | ❌ | ✅ | ❌ | **真实能力缺口**（生产 NoOp） |
| 链 4 审批 UI 终态 | ✅ | N/A | ✅ | ⚠️ 4xx 文案「请稍后重试」 | 部分已纠偏，铁律 15 未闭合 |
| 链 5 PARK 超时兜底 | ✅ | ✅ | ✅ | ⚠️ 健康事实无消费方 | 运行期可达，不可告警 |

1. **链 1 是「重大发现」的实证**：运行期 PARK/RESUME 四问前三问全过，既有报告「缺运行期暂停-恢复」的判断**已被复核纠偏**——真正缺的是 A2 泛化（§4.5 不一致 1/5）。
2. **链 2 与链 3 性质完全不同**：链 2 是「机制在、门禁缺」（验证补强，P0 验收报告列为下一批任务）；链 3 是「接线在、实现缺」（真实能力缺口，§7 改进点 3 可补）。**不得混为一谈**。
3. **链 4 是本轮对 §5.3 劣势 2 的纠偏来源**：参数摘要 / 目标资源 / 有效期**已展示**（FR-014 落地），「审批台不展示参数摘要」**已被复核纠偏**；剩「risk_level 未展示」「4xx 文案伪装临时故障」「无幂等键」三条仍然成立，分别对应 §7 改进点 5/7。

---

## 五、正面对比分析

### 5.1 逐条能力对照表（能力项 | 我们的实现 | 各竞品做法 | 判定 | 依据）

> 判定语义（诚实边界声明第五节）：「领先/持平/落后」= **在该能力项上机制更硬或覆盖更严**，不等于整体产品更优。本项目在开发者体验、生态广度、任意点交互的落后是前序 12 份报告的共识，不在此重复展开。

| # | 能力项 | 我们的实现 | 竞品做法 | 判定 | 依据 |
|---|---|---|---|---|---|
| C1 | 运行期暂停-恢复（持久化 + 让出资源） | `WAITING_APPROVAL` PARK + 释放租约 + 检查点 + 异步唤醒 + `loop.resume`（E-01/E-04/E-06/E-07） | LangGraph checkpointer(thread_id)、ADK invocation_id、Agno continue_run 均持久化；OpenAI/Claude 多进程内/宿主自管 | **持平（专用场景领先）** | 我们「不重跑模型、不重复副作用」的恢复幂等在工具审批场景强于 LangGraph「节点从头重跑」；但通用性弱 |
| C2 | 任意点 interrupt + 状态编辑 | 无，仅「工具执行前」专用门；恢复不携带状态 delta（E-05/D-3） | LangGraph `interrupt()` 任意节点 + `Command(resume)` 任意值；ADK/Agno 结构化输入 | **落后** | 既有报告共识（竞品报告 :420/:440/:459），本轮复核确认属实（§4.5 不一致 1） |
| C3 | 审批门与权限/角色耦合（RBAC + SoD） | `config:request/approve/publish/rollback` + `APPROVER` + 动态 SoD 双保险（E-14/E-16/E-25） | 竞品几乎无内建；审批身份多由宿主应用管 | **领先** | 差异化 3；竞品横览表「双人复核/SoD」列全 ❌ |
| C4 | 审批事实落库 + DB 触发器状态机守卫 | `approval`/`cap_approval` + 三条 `enforce_*_state_transition` + 终态不可迁出（E-19~E-22） | 竞品状态机在应用/框架层 | **领先** | 差异化 1；A1 抗绕过性结构性来源 |
| C5 | 审批绑定参数/资源/有效期 + 防参数替换重放 | `argument_digest`/`target_resource`/`expires_at` + 双哈希 + `authz_version`（D-4/E-23/E-24） | LangGraph 无内建（状态即事实）；ADK/Agno 无双哈希；OpenAI guardrail 无绑定快照 | **领先** | 差异化 2 |
| C6 | 治理发布审批（模型/定义上线） | 三步 `request→decide→apply` + 不可变发布 + modelRef 不回落 + 指针（E-16/E-26/E-28/E-29） | 竞品 HITL 聚焦运行期交互，几乎不治理「模型/定义上线」这一环 | **领先（独有）** | 差异化 4；这是本项目 HITL 最独特的一块 |
| C7 | 审批并发正确性（两人同时批准） | `FOR UPDATE` + 应用层状态前置 + `two_person_ck`（E-17/E-25） | 竞品无对应机制（无集中式发布审批） | **持平（机制齐、缺专项门禁）** | §4.5 不一致 3：机制不破坏状态机，但缺 decide 并发测试 |
| C8 | 审批审计与可追溯 | 审批审计同事务、`approver_id` 来自令牌、参数摘要留痕（E-13/E-17/:267） | 竞品多交宿主；LangGraph 靠 traces | **领先（可接维度 10 哈希链）** | 差异化 6；但「是否真接哈希链」需 §7 改进点 6 落地 |
| C9 | 审批拒绝/超时/无权限的终态语义 | 404 不区分存在性、409 显式、`REJECTED/EXPIRED/FAILED` 终态、停轮询（D-7/D-11/E-13） | 竞品无统一终态契约 | **领先（铁律 15 延伸）** | AP4 已规避；但客户端落地需 §7 改进点 7 |
| C10 | 审批 SLA / 批量 / 有效性观测 | **空白**：无 SLA 指标、无批量审批、无拒绝率/审批时长观测（A5） | 竞品亦普遍空白；Agno 有审批审计类型但无 SLA | **落后（双方共同盲区）** | §2.1 框架 6、AP1/AP7 未规避 |
| C11 | 运行期工具审批「生产已启用」 | **默认未接线**：全 FAIL_CLOSED + 未接线告警（D-5/§4.5 不一致 6） | 竞品 SDK 默认可跑（框架层能力即开即用） | **落后（可用性）** | `application.yml:569-580` |
| C12 | 人工复核 → 迭代闭环归因回写 | `ReviewAttributionHook` 接线但生产 NoOp，结论不回写（D-13/E-32~E-34） | Agno/竞品无「归因→迭代」概念 | **落后（未闭环）** | §4.5 不一致 2：预留扩展点 |
| C13 | 未接线时 fail-closed 严格性 | 空策略全 `FAIL_CLOSED`、审批决策缺省拒绝（D-5/D-6） | 竞品多默认放行或由宿主决定 | **领先** | T4-A 站位 |
| C14 | 运行中任意时刻主动打断（off-switch） | 仅工具执行前一扇窗口，无通用 off-switch（§2.1 框架 4） | LangGraph 任意节点、Claude PreToolUse 更灵活 | **落后** | EU AI Act Art.14 stop 按钮要求的泛化能力有缺口 |

**汇总**：14 项中领先 7、持平 2、落后 5。领先项集中在 **A1 抗绕过 / A4 审计 / 治理发布**（L3 强项），落后项集中在 **A2 灵活性 / A5 有效性 / 生产可用性**（L2 与运营短板）。这与 §1.2 判定的「偏科」完全一致。

### 5.2 优势详解（讲清机制，不只给结论）

**优势 1｜治理发布把「批准过什么」与「运行期实际用什么」强绑定（结构性）**
机制链条：发布申请 `PENDING` 不写配置表 → 审批 `APPROVED` 仍不写 → `apply` 才在同事务内 `buildPublishCommand` 把 `spec.modelRef` 固化进 `CapAgentDef`（E-16/:520/:528）并切 `cap_config_pointer`（E-26）。已发布行被 `forbid_published_def_mutation()`（E-28）锁死内容，`model_ref` 为 NULL 时运行期直接拒、绝不回落默认模型（E-29）。**后果**：任何模型/提示词/参数变更都必须重走双人复核 + CAS 基线，运行期无法「临时改边」，因此可复现、可追责、可回滚（回滚也是一次发布申请 `requestRollback`）。这是竞品 HITL 完全不做的一层——它们把「上线审批」留给宿主 CI/CD，而非纳入 agent 运行时状态机。

**优势 2｜审批状态机的抗绕过性来自 DB 而非应用层（结构性）**
三条触发器（E-03/E-21/E-22）把「状态只能前进、终态不可迁出」压到数据层。这不只是「更可靠」：它意味着**即使应用层有 bug、或有绕过 API 的直连 SQL**，也无法把 `CONSUMED/REJECTED/EXPIRED` 迁回 `PENDING`、无法让 `PENDING→CONSUMED` 跳过中间态、无法让 `WAITING_APPROVAL` 迁向 `SUCCEEDED`。对照竞品：LangGraph 的审批状态就是 graph state 的一个字段，checkpointer 忠实存取它但不校验迁移合法性——正确性由「开发者写对了 reducer」担保。本项目把这份担保移到了 DDL。

**优势 3｜动态 SoD 双保险把「谁有权批准」与「是不是申请人」解耦（工程性）**
`AuthorizationService` 注释（E-14/:103-105）明确点破：「`config:approve` 权限仍不能消除自我审批——权限与身份是两件事」。于是 `decide` 同时做：权限校验（`requireAuthorized`）、应用层身份比对（`requesterId().equals(approverId) → SelfApprovalException`，E-17/:253-254）、DB CHECK（`two_person_ck`，E-25）。**任何一层失效，另两层仍兜住**——这是 four-eyes / 动态 SoD 落到代码与 DDL 的教科书式实现，也是竞品普遍缺的一环。

**优势 4｜PARK-RESUME 的恢复幂等性在专用场景强于 LangGraph（工程性）**
LangGraph 官方明确 resume「从节点开头重跑」，故节点内 `interrupt()` 之前的代码须幂等（§2.1 框架 5）。本项目 `ApprovalDecidedWakeupConsumer.wakeup()` 按 `operationId` 从 `tool_operation` ledger 反查原始 `ToolCallRequest`（E-06/:113）、重建后 `loop.resume`（:132），**恢复绝不重跑模型**（:27 注释）——既不重复扣预算、也不因等待期间模型状态变化而产生不同参数。这是把「等审批」做成非阻塞持久化挂起（D-1）后，针对「工具副作用审批」这一场景更硬的恢复语义。

**优势 5｜审批决策缺省拒绝 + 404 防探测（工程性，铁律 15 延伸）**
`DecisionRequest.approve()` 用 `"APPROVE".equalsIgnoreCase(...)`（E-13/:177-180），任何含糊输入都当 DENY；404 刻意不区分「不存在」与「无权限」（:158-160，避免存在性探测通道）；过期/已决定返回 409（:162-163）。把 fail-closed 与「终态不伪装」做进审批台，是 HITL 语境对铁律 15 的完整落地。

### 5.3 劣势与盲区详解（明确承认，含根因：设计取舍 vs 能力缺失）

**劣势 1｜缺「任意点中断 + 状态编辑」——根因是设计取舍，不是能力缺失**
- 表现：暂停点只有「工具执行前」一处（E-11），恢复不携带状态 delta（E-05），没有 LangGraph 式 `interrupt()` 抽象。
- 根因：**这是 T3「灵活性 vs 强一致」主动选择 B 的代价**。DB 触发器状态机（D-8/E-22）与不可变发布快照（D-9/E-28）本质上排斥「运行期编辑中间状态」——允许编辑就意味着要放宽「状态只能前进、发布态不可改写」，会动摇 A1/A4 的结构性优势。因此这不是「没想到」，而是「为了抗绕过性主动放弃的灵活性」。
- 后果：无法支撑「客服坐席在 agent 跑到一半时改一段草稿再继续」「代码生成到一半人工改稿」这类交互式 HITL（§3.1 LangGraph 适用场景）。
- 可改善性：可在**不破坏治理域 L3** 的前提下，把运行期 `WAITING_APPROVAL` 泛化出「可编辑状态通道」（§7 改进点 1），代价是运行期状态机复杂度上升。

**劣势 2｜审批有效性（A5）近乎空白——根因是能力缺失**
- 表现：无审批 SLA 指标、无拒绝率/审批时长分布观测、无批量审批、无抽样复核、审批台不展示 `risk_level`/参数摘要（现有 `GET /api/approvals` 列表与 `POST decision` 只传 `approve`/`reason`，E-13）。
- 根因：**不是设计取舍，是没做**。前序建设把重心放在「审批事实正确性」（A1/A4），把「审批是否 meaningful」（GDPR Art.22 / EU AI Act Art.14 / automation bias，§2.1 框架 2/6）留白了。
- 后果：AP1 橡皮图章、AP7 automation-bias 放大器未被规避——「形式上有个人点通过」可能退化为合规幻觉；高频审批诱发 alert fatigue（§2.3）。
- 可改善性：纯增量，不改状态机（§7 改进点 5/6/7）。

**劣势 3｜运行期工具审批「代码有、开关没开」——根因是生产就绪度**
- 表现：`live-source-enabled=false` / `source.mode=none` / `rules: []` → 空策略全 `FAIL_CLOSED`（E-12/E-38），真实审批台未接线，`ApprovalReadinessGuard` 只告警不阻断（§4.5 不一致 6）。
- 根因：T4 主动选 A（未接线一律拒，保安全），但**真实审批源（`live-source`）的实现/接入尚未完成**，属生产就绪度缺口。
- 后果：默认部署下，所有 `WRITE_EXTERNAL` 类高风险工具一律拒绝执行（除非策略显式放行）；运行期 HITL 在生产「可用但不可用」。
- 可改善性：接线 `live-source` + 策略表 + `ApprovalReadinessGuard.require-live-source=true`（§7 改进点 2）。

**劣势 4｜`ReviewAttributionHook` 未闭环——根因是产品口径未定**
- 表现：`review_task.resolve()` 只关闭任务，PROMPT/CONFIG/MODEL 类归因结论不回写、不驱动迭代闭环（D-13）。
- 根因：产品尚未定义「归因结论回写到哪、如何触发提示词/配置/模型迭代」的口径；接口注释（E-32/:23）明确「产品定口径，默认 NoOp 自动让位」——**这是有意的预留，不是遗漏接线**。
- 后果：人工复核的产出止于「这条任务已处置」，无法沉淀为「下次别再犯」的系统改进。
- 可改善性：口径一旦确定，实现一个 Bean 即可（§7 改进点 3），改动面极小。

**劣势 5｜无通用 off-switch / 运行中任意打断——根因是设计取舍 + 覆盖窄**
- 表现：`WAITING_APPROVAL` 迁出集合含 `CANCELLED`（人撤）与超时收敛，但「打断」只能发生在工具执行前的审批窗口，运行中任意时刻无主动中断入口（§2.1 框架 4）。
- 根因：与劣势 1 同源——把「暂停」绑定在专用门上，就没建立通用暂停原语。
- 后果：EU AI Act Art.14「stop button / 安全中断」在通用语境下有缺口；人若想在 agent 自主推理中途叫停，无标准入口。

### 5.4 关键分歧点（最本质的架构分歧）

**分歧点 1｜审批状态由「框架 state」还是「DB 触发器」持有（最本质）**
- LangGraph/ADK/Agno：审批是 graph state / run state 的一部分，checkpointer 存取、**框架不校验合法性**；正确性靠开发者与宿主。→ 换来 A2 灵活性。
- 本项目：审批是 `approval`/`cap_approval`/`cap_config_publish_request` 三张表 + 三条 DB 触发器 + CHECK 约束；正确性由 DDL 担保、绕过应用层无效。→ 换来 A1/A4，代价是 A2。
- 后果：这一分歧几乎决定了 C1~C9 的全部判定。它不是「谁对谁错」，而是**「把信任放在开发者身上还是放在数据库上」**的路线选择，直接对应本项目「数据库/治理中心化」的整体定位（竞品报告 :13 已定性）。

**分歧点 2｜HITL 的边界画在「运行期交互」还是「治理发布」**
- 竞品：HITL ≈ 「agent 跑的时候怎么让人插手」，几乎不谈「模型/Agent 定义上线要不要人批」。
- 本项目：把 HITL 的主战场放在「治理发布审批」（C6 独有），运行期交互反而是后补的专用门（D-1 的 M1/G2 修复注释，RunState.java:43-54）。
- 后果：本项目在「谁批准了线上跑的模型和提示词」上可追责性极强，但在「agent 跑到一半人想插句话」上弱。两份能力满足不同客户需求（合规/教育治理 vs 交互式创作）。

**分歧点 3｜「未接线」时是 fail-closed 全拒还是框架默认可跑**
- 本项目：空策略 `FAIL_CLOSED`、决策缺省拒绝、`require-live-source` 可强制阻断启动（T4-A）。
- 竞品：SDK 装上即用，审批由宿主按需接、默认不接也能跑。
- 后果：本项目上线门槛更高（真实审批源必须接线），但杜绝「审批台没接好却默认放行」。这是「安全」与「开箱即用」的取舍，也解释了 C11 为何判落后。

---

### 5.5 深度补充：能力评分卡（把定性判定量化成可复算分数）

> **本节为第三轮追加**，**不改写** §5.1–§5.4 的任何既有判定；若本轮证据与既有判定冲突，在本节内以「**本轮更正（与既有正文 X.X 冲突）**」标注（见 5.5.4），不动原文。
> **能力评分卡（capability scorecard）**＝把 §5.1 的「领先 / 持平 / 落后」量化成**可复算的分数**，让本维度与 12 份兄弟报告横向可比、让后续演进有基线。
>
> **评分轴**直接沿用 **§1.3 的 5 个评价轴**（A1 强制程度/抗绕过 / A2 介入时机与粒度 / A3 可恢复性与幂等 / A4 可审计与可追溯 / A5 有效性与吞吐），每轴 0–4 分。
> **评分口径（全局统一，与 `README-第三轮补充规范.md` §1.1 一致）**：
> - **0** = 完全缺失（无对应实现，且无缺口登记）
> - **1** = 有痕迹不可用（有类/有表/有配置，但未装配/未写入/未触发 —— 即运行期不可达）
> - **2** = 局部可用（仅单一路径生效，或仅单场景生效，或靠人工兜底）
> - **3** = 能力完整（默认路径生效 + 有失败语义 + 有测试门禁）
> - **4** = 能力完整且有护栏（默认生效 + 失败语义 + 门禁 + **运行时度量/告警/可观测**）
> - **每格必须给证据锚点**（`文件:行号` 或「官方文档 URL」），无证据不得给分。
> - **加权总分** = Σ(轴分 × 轴权重) / Σ权重。
>
> **权重口径（本轮新设）**：§1.3 只给了评价轴与观测方式，**未给权重**。按《第三轮补充规范》的兜底次序「强制程度 > 可审计性 > 失败可诊断性 > 运维成本 > 生态成熟度」映射到本维度：
> **A1 强制程度/抗绕过 = 强制程度（0.30）**（DB 触发器 + CHECK + 双哈希，应用崩了也不变量成立，是本维度护城河）→ **A4 可审计与可追溯 = 可审计性（0.25）**（审批事实同事务落库 + `approver_id` 来自令牌，是合规红线）→ **A3 可恢复性与幂等 = 失败可诊断性（0.20）**（PARK/RESUME 失败要么收敛 `FAILED`，要么被人发现）→ **A5 有效性与吞吐 = 运维成本（0.15）**（SLA/批量/告警是运营杠杆，非正确性杠杆）→ **A2 介入时机与粒度 = 生态成熟度（0.10）**（任意点 interrupt + 状态编辑是生态级 DX 能力，非本仓可独造）。
>
> **判读纪律**：① 分数是**工程判断**，不是客观测量；1–2 分差距不构成「结构性落后」的证据，只构成改进项优先级输入。② 「竞品最高分」只取**第三章 / 附录 F 已核实的官方事实**，无官方依据的轴填「不评分」并说明。③ 若某轴我们得分高于竞品最高分，必须补**机制解释**，否则按「诚实优先于漂亮」下调 1 分。

#### 5.5.1 评分卡主表

| 评价轴 | 权重 | 我们的得分 | 证据锚点（本轮实际 Read/Grep 复核） | 竞品最高分 | 差距 | 差距根因（设计取舍 / 能力缺失 / 证据缺口） |
|---|---|---|---|---|---|---|
| **A1. 强制程度 / 抗绕过** | 0.30 | **3** | ✅ **触发器守卫**：`enforce_approval_state_transition()`（DDL `01_platform_ddl.sql:316`）+ 触发器挂 `approval`（`:4685`）；✅ **应用层双保险**：`JdbcConfigPublishRequestRepository.java:253-254`（`SelfApprovalException`）+ `:256-259`（`state != PENDING → StateConflictException`）+ `:372-377`（`loadForUpdate` 用 `FOR UPDATE`）+ `:267-273`（审计与状态变更同事务）；✅ **DB CHECK**：`cap_config_publish_request_two_person_ck`（DDL `:1882`，另一道 `:1945`）；✅ **运行期闸门 fail-closed**：空策略未登记租户/工具一律 `FAIL_CLOSED`（`RiskBasedApprovalPolicy.java:210/:214`）；✅ **PARK 态跃迁受守卫**：`WAITING_APPROVAL` 只允许迁 `RUNNING/CANCELLED/TIMED_OUT/FAILED`（`RunState.java:75`）；✅ 决策缺省拒绝硬编码（G-17）；❌ **无运行时度量/告警**：触发器拒绝次数、`StateConflictException` 次数无 Meter/告警；❌ **无「两人并发 decide」专项门禁**（§4.5 不一致 3、附录 I-01 标「未实测」，静态合推） | **3**（LangGraph：框架**不校验**审批状态迁移合法性、正确性靠开发者——附录 F :1584；AWS Step Functions：`taskToken` 超时即作废、过期调用抛 `TaskTimedOut`——附录 F :1592；PostgreSQL `FOR UPDATE … SKIP LOCKED`——附录 F :1593。三方合起来是竞品在「抗绕过」的官方天花板，但**均不提供本项目式 DB 触发器状态机 + 双 CHECK 的强不变量**） | **0（持平）** | **不适用（机制已完整）**。我方与竞品各走一条路线：我方把不变量下沉 DB（应用崩了也成立），竞品把正确性放框架/应用层（换来 A2 灵活）。**不给 4 分的原因**：4 分要求运行时度量/告警，本轴缺「触发器拒绝次数」「状态冲突次数」两类 Meter，且「两人并发 decide」专项门禁未闭环——二者正是 §7 改进点 4 的内容 |
| **A2. 介入时机与粒度** | 0.10 | **1** | ✅ **已落的是「运行前治理期」专用门**：三步 `request→decide→apply` + 不可变发布（C6、E-16/E-26/E-28/E-29）；✅ **已落的是「工具执行前」专用 PARK 门**：`WAITING_APPROVAL` 挂起点**只有工具执行前一处**（`RunExecutionLoop.java:1123-1148`），恢复 `loop.resume` **不携带状态 delta**（`:2073-2128`，只重放已批工具批次、`ModelResponse.ToolCallRequest` 重建自 ledger）；❌ **无任意点 `interrupt()`**：暂停点不在任意节点，无通用暂停原语（§5.3 劣势 1、C2/C14）；❌ **恢复不接受状态编辑**：无 `Command(resume)` 式任意值注入（E-05/D-3）；❌ **无运行中通用 off-switch**：仅工具前一扇窗口（§5.3 劣势 5）；⚠️ 根因是 T3「灵活性 vs 强一致」设计取舍（§5.3），非遗漏 | **4**（LangGraph `interrupt()` 任意节点 + `Command(resume)` 任意值 + checkpointer(thread_id) 持久化（附录 F :1584/:1585，官方原文）；Anthropic `PreToolUse` hook `updatedInput`(modify) + `permissionDecision ∈ {allow,deny,ask,defer}`（附录 F :1586）；ADK `tool_context.request_confirmation(hint,payload)` 结构化输入（附录 F :1587）） | **−3（我们落后）** | **设计取舍**（T3 主动选择 B「强一致」的代价）+ **能力缺失**（未实现任意点 + 状态编辑抽象）。**机制说明（规范要求）**：DB 触发器状态机与不可变发布快照本质上排斥「运行期编辑中间状态」——本维度把主战场放在治理发布（A1/A4），代价是 A2 只有「运行前」+「工具前」两个专用窗口；这是 §2.4 锚点 H「L0–L3 口径边界」的必然结果。**给 1 而非 0**：已经有专用 PARK 门与治理审批这两个「有痕迹且单一场景生效」的入口，符合「局部可用下沿」，但离「任意点 + 状态编辑」差一个抽象层 |
| **A3. 可恢复性与幂等** | 0.20 | **3** | ✅ **完整、持久化、有失败语义的 PARK/RESUME 闭环**（**本轮推翻既有「缺暂停-恢复」判定，见 5.5.4 第 1 行**）：`WAITING_APPROVAL` 落库 + 非终态跃迁（`RunState.java:42-54/:75`）→ PARK 写检查点 + `releaseLease` 让出线程（`RunExecutionLoop.java:1123-1148`）→ 决定落库**同步**唤醒（`ApprovalDecidedWakeupConsumer.java:82-136`，注释 `:41-46` 说明不消费 MQ）→ `claimParkedRunForResume` 用 `WHERE state='WAITING_APPROVAL'` + fencing 抢回（`:100-109`、`JdbcRunClaimRepository.java:271-287`）→ `loop.resume` **按 operationId 反查原始调用重建 `ToolCallRequest`、不重跑模型**（`ApprovalDecidedWakeupConsumer.java:112-132`、`RunExecutionLoop.java:2073-2128`，类注释 `:26-31`）；✅ **fail-closed**：审批行读不到 → `NOT_FOUND`、仍 PENDING → 不唤醒、原始调用反查不到 → 收敛 `FAILED`（`:84-125`）、恢复重入被拒 → `commitFailure("TOOL_REJECTED")`（`RunExecutionLoop.java:2120-2125`）；✅ **超时兜底**：`RunRecoverySweeper.failStaleParkedRuns`（`:267`，`parked-timeout=1800s` @ `:116`、注释 `:265`）；✅ 有测试门禁：`ApprovalDecidedWakeupConsumerTest.java`（测试类存在，附录 I-04/I-07 反证口径）；❌ **无运行时度量/告警**：PARK 时长、孤儿收敛时长、`resume_invocation_not_found` 次数无 Gauge/告警；❌ **TTL(15m) 与 PARK 超时(1800s) 无运行时一致性校验**（仅 `application.yml:295-296` 注释约定） | **4**（LangGraph checkpointer + `error_handler` 只在重试耗尽后触发且状态转换原子、节点从头重跑（附录 F :1584）；Temporal Event History append-only + 崩溃 replay 续跑 + Activity Retry Policy + Side Effect「记录结果不重跑」（附录 F :1591）；AWS Step Functions Activity `taskToken` 托管回调 + 超时作废（附录 F :1592）。三者均有「持久化 + 幂等 + 可观测 History」的完整护栏） | **−1（我们落后）** | **能力缺失**（缺 PARK 时长/孤儿收敛时长 Gauge 与告警、缺跨进程 wakeup 丢失的度量出口——`ApprovalController.java:147-149` 的 `wakeup` 仅 `log.warn`，§8.1 耦合 4）+ **设计取舍**（同步 wakeup + sweeper 兜底是领域专用替代，非 Temporal 式通用 History）。**机制说明**：我们的恢复幂等靠「按 operationId 反查重建、不重跑模型」，在工具审批场景强于 LangGraph「节点从头重跑」（不重复扣预算），但通用性与可观测弱于 Temporal Event History。**不给 4 的原因**：缺运行时度量/告警，且 TTL/PARK 超时一致性仅靠注释约定、无启动校验 |
| **A4. 可审计与可追溯** | 0.25 | **3** | ✅ **审批事实由 DB 持有**：审计与状态变更**同事务**（`JdbcConfigPublishRequestRepository.java:265-273`，审计写不进去则整体回滚）；✅ **身份来自令牌 + 双人复核**：`approver_id` 只来自令牌、`SelfApprovalException`（`:253-254`）+ `two_person_ck`（DDL `:1882`）；✅ **参数/资源/有效期绑定 + 双哈希防重放**：`argument_digest`/`target_resource`/`expires_at`（G-16、`ApprovalController.java:194/:203-215`）与治理期 `approval_scope_hash`/`effective_args_hash`/`closure_fingerprint`（DDL `:1713/:1715/:1716`，列注释 `:1755/:1769` 明写「防参数替换重放」）；✅ **审批台已展示参数摘要/目标资源/有效期**（`ApprovalController.java:203-215`、`GovernancePages.tsx:179-188`，FR-014 已落地纠偏）；❌ **未接维度 10 审计哈希链**（§7 改进点 6，无锚点可引用＝尚未落地）；❌ **无运行时度量/告警**：审批事件计数、`audit.recordRequired` 失败次数无 Meter | **2**（LangGraph：靠外部 traces、无内建审批留痕（附录 F :1584）；Agno：`Requires Approval or Audit` 有管理员审批 + 审计留痕类型（附录 F :1590），但不绑定参数摘要双哈希、不与状态变更同事务；7 家竞品横览「双人复核/SoD」「参数绑定快照」两列全 ❌（§5.1 C3/C5）） | **+1（我们领先）** | **不适用（机制已完整，且领先落在竞品生态弱项）**。**机制解释（规范要求）**：我们的审计是**数据层同事务不变量**（审计写不进去则批准不存在），且绑定参数摘要 + 目标资源 + 有效期 + 范围哈希，竞品的审计多交宿主/外部 trace、不绑参数快照。**不给 4 的原因**：未接维度 10 哈希链（无可引用锚点）、无运行时审计失败告警——这正是 §7 改进点 6 的内容 |
| **A5. 有效性与吞吐** | 0.15 | **1** | ❌ **SLA / 拒绝率 / 批量审批 / 审批时长观测全空白**（§5.3 劣势 2、C10）：本轮 grep `application.yml` 无 `reject_ratio\|batch\|sla` 命中（附录 G-14「连配置位都没有」）；❌ **`risk_level` 未在审批列表展示**（附录 G-16，`ApprovalController.java:203-215` 返回体有 argumentDigest/targetResource/expiresAt 但无 risk_level）；❌ **无 Idempotency-Key**（附录 G-15，5xx 重试撞 409 误判）；❌ **无审批 SLA 指标/告警**：无 `pending_age_p95`、无橡皮图章/alert fatigue 观测（AP1/AP7 未规避）；✅ **仅有 TTL 配置痕迹**：`agent.approval.ttl=15m`（`application.yml:563`）落 `expires_at`（DDL `:1474`），但与 PARK 超时仅注释约定自洽（`:295-296`）——属「有痕迹但有效性能力运行期不可达」 | **不评分** | —（**证据缺口**） | 第三章 / 附录 F **未核实到任何竞品的官方「审批 SLA 指标 / 拒绝率告警 / 橡皮图章观测」能力**：NIST AI RMF 的 MEASURE 治理函数只给「为什么测」不规定 SLA 阈值（附录 F :1594/:1600 明写「PDF 中未出现具体审批 SLA 阈值」）；Microsoft Agent Framework 的 `don't-ask-again` 是吞吐优化，但官方未给拒绝率观测指标（附录 F :1588）。按《第三轮补充规范》§1.2 纪律 2，该格填「不评分」，**不凭印象给 2**。**给 1 而非 0**：`ttl=15m` 配置真实生效（G-02 ✅真生效），有「时效」痕迹，但有效性观测能力本身缺失 |
| **加权总分** | **1.00** | **2.50 / 4**（全 5 轴） | — | **3.06 / 4**（**仅 A1–A4 四轴可比子集**，A5 不评分，分母 0.85） | **−0.30**（A1–A4 可比子集差值；不可外推，见 5.5.2） | — |

**加权总分算式（我们，全 5 轴）**：`3×0.30 + 1×0.10 + 3×0.20 + 3×0.25 + 1×0.15 = 0.90 + 0.10 + 0.60 + 0.75 + 0.15 = **2.50**/4`。
**竞品可比子集算式（仅 A1–A4 四轴，权重合计 0.85）**：竞品 `3×0.30 + 4×0.10 + 4×0.20 + 2×0.25 = 0.90 + 0.40 + 0.80 + 0.50 = 2.60`，`2.60 ÷ 0.85 = **3.06**/4`；我们 `(0.90 + 0.10 + 0.60 + 0.75) ÷ 0.85 = 2.35 ÷ 0.85 = **2.76**/4`。
**差距（A1–A4 可比子集）**：`2.76 − 3.06 = **−0.30**`。

> **口径说明**：A5 竞品不评分 ⇒ 竞品「全 5 轴总分」无意义，只有 A1–A4 可比子集（分母 0.85）可算；我方全 5 轴为 2.50/4，剔除不可比 A5 后的可比子集为 2.76/4。**差距 −0.30 落在 1 分以内**，按判读纪律不构成「结构性落后」，只作改进优先级输入（详见 5.5.2）。

#### 5.5.2 可比子集：A1/A4 的「领先」恰落在竞品生态弱项，真差距看 A2/A3

全轴差值（含 A5）**不可直接作为结论**，原因有二（与 00 总览 §2.1、§7.1 读法一致）：

1. **A5 竞品不评分**：第三章/附录 F 未核实到任何竞品的官方审批 SLA/拒绝率告警指标，按规范不凭印象给分 ⇒ A5（权重 0.15）必须从「我们 vs 竞品」的差值中剔除。
2. **A1/A4 的领先落在竞品生态里几乎不存在的赛道**：7 家竞品横览（§5.1）「DB 触发器状态机」「双人复核/SoD」「参数绑定双哈希」三列**全 ❌**——这不是「竞品做得差」，而是「agent 框架生态普遍把审批正确性交宿主/框架层，不做 DB 层不变量」。把 A1/A4 的高分全权重塞进「谁领先」的结论，与 02/08/09/10 四份同款处理方式一样，会给自己的领先项**重复计分**。

因此给**两组同口径结论**：

| 子集 | 口径 | 我们 | 竞品最高 | 差 | 用途 |
|---|---|---|---|---|---|
| A1–A4（4 轴，分母 0.85） | 剔除不评分的 A5 | **2.76** | **3.06** | **−0.30** | 全可比轴的整体画像 |
| **A2+A3（2 轴，分母 0.30）** | **双方同品类「运行期交互」子集** | **2.33** | **4.00** | **−1.67** | **真正的产品差距所在** |

**A2+A3 子集算式**：我们 `(1×0.10 + 3×0.20) ÷ 0.30 = 0.70 ÷ 0.30 = **2.33**`；竞品 `(4×0.10 + 4×0.20) ÷ 0.30 = 1.20 ÷ 0.30 = **4.00**`；差 **−1.67**（竞品在该子集拿到满分 4，因其 checkpointer/Event History 带可观测护栏，符合 4 分定义）。

**本轮最重要的一条量化结论**：**在「人怎么介入、介入后怎么恢复」这两件同品类的事上，本平台（2.33）比业界最高分（4.00）低 1.67 分**——这与既有正文 §5.1「14 项中领先 7、持平 2、落后 5，落后集中在 A2 灵活性 / A5 有效性」的画像**方向一致**，但本轮把它从星级画像变成了可复算的分数：

- **A2（1 分）是最大短板**：抬到 2 需在 `WAITING_APPROVAL` 上引入通用 `pause_reason` + 状态编辑通道（§7 改进点 1，复用现有态、不新增 PAUSED）；抬到 3 需要任意节点 `interrupt()` 抽象，**与 DB 触发器状态机冲突**，属架构级决策（§8.3 反向思考）。
- **A3（3 分）已接近满**：闭环本身完整，抬到 4 **只需接出口**（PARK 时长/孤儿收敛时长 Gauge + `wakeup` 失败告警 + TTL/PARK 超时启动校验），与 §7 改进点 5/6 同向——**是「接出口」而非「造能力」**。
- **A1/A4 的领先是真实的，但不可外推**：它们是「合规/治理客户」的购买理由，不是「交互式创作客户」的卖点；A2 的落后恰是后一类客户的核心诉求（§5.4 分歧点 2）。
- **A5（1 分）是全行业共同盲区**（C10，附录 F NIST PDF 无 SLA 阈值）：本维度若想差异化，反而是机会点——把 `reject_ratio` 告警 + 批量审批 + SLA 看板做成治理产品的「审批有效性」卖点。

#### 5.5.3 本节与既有章节的关系（并存不替代）

本报告在第三轮之前已有 **两套**评价体系，本节是**第三套**。三者**并存、不互相改写**：

| 维度 | §5.1（定性 14 项） | §1.2（L0–L3 能力分层） | **本轮 §5.5（0–4 加权评分卡）** |
|---|---|---|---|
| 量纲 | 领先 / 持平 / 落后 + 文字论证 | L0–L3 四档（审批事实由谁持有） | 每轴 0–4 + 权重 + 加权总分 |
| 粒度 | **能力项**（14 项） | **整体档位**（治理域 L3 / 运行期 L2 下沿） | **评价轴**（把 14 项归并为 5 轴） |
| 是否有竞品对照 | 有（各竞品做法描述） | 有（框架定位） | ✅ 有（竞品最高分 + 差距 + 根因三分类） |
| 回答的问题 | 「我们像什么」 | 「我们落在哪一档」 | 「**整体做得怎么样、差多少、投入产出比怎么排**」 |
| 与本轮 5.5 的关系 | 被量化 | 被引用为分档依据 | — |

**口径差异与互引（避免被误读成三套打架的分数）**：
1. **§5.1 是「逐能力项」判定，§5.5 是「逐评价轴」加权——粒度不同，结论不冲突**。§5.5 的每一格都显式引用 §5.1 的判定作为扣分/加分依据（A1←C3/C4/C5/C13、A2←C2/C14、A3←C1、A4←C8/C9、A5←C10/C11/C12）。
2. **§5.5 不改写 §5.1 任何一行**。§5.1 判「领先 7」，本轮 A1/A4 给 3 分（不是 4 分）——因为 4 分额外要求「运行时度量/告警」，本轮逐条列出了缺的 Meter/告警。
3. **§1.2 的 L0–L3 是「档位」，§5.5 是「分数」**。「治理域 L3」对应 A1/A4 的 3 分，「运行期 L2 下沿」对应 A2 的 1 分、A3 的 3 分——**档位与分数不是同一把尺子**，不可互相换算。
4. **两处的「未核实项」必须同时读**。§5.5 的竞品最高分全部沿用第三章/附录 F 已核实条目（本轮未重新 WebFetch），附录 F 的降级标注（Anthropic 旧域重定向、CSRC SoD glossary 404、Temporal 旧 URL 404、NIST PDF 无 SLA 阈值）继续有效。

#### 5.5.4 本轮复核推翻的既有判定（子表）

| # | 既有判定（位置） | 本轮复核结论 | 推翻/修正依据（本轮实际复核锚点） | 处理 |
|---|---|---|---|---|
| 1 | 「**缺运行期暂停-恢复 / 弱于 LangGraph / 缺 PAUSED 态**」（竞品报告 `:420/:440/:459`、梳理报告第 13 章 `:198-205`、用户素材） | **推翻（重大更正）**：源码已有**完整、持久化、不重跑模型、有超时兜底**的专用 PARK/RESUME 闭环，`WAITING_APPROVAL` 即事实上的 PAUSED 态；真正缺的是「任意点 + 状态编辑」泛化（A2），不是「有没有暂停-恢复」 | 逐跳（本轮 Read 复核）：`RunState.java:42-54/:75`（`WAITING_APPROVAL` PARK 态 + 非终态跃迁）→ `RunExecutionLoop.java:1123-1148`（PARK 写检查点 + `releaseLease`）→ `ApprovalDecidedWakeupConsumer.java:82-136`（同步唤醒 + 三类 fail-closed）→ `JdbcRunClaimRepository.java:271-287`（`WHERE state='WAITING_APPROVAL'` + fencing）→ `RunExecutionLoop.java:2073-2128`（`resume` 按 operationId 反查、`loop.resume` 不重跑模型）→ `RunRecoverySweeper.java:116/:267`（超时兜底） | **本轮更正（与 §4.5 不一致 1 / 不一致 5 同源，已在 §4.5 登记，此处为评分卡内复核确认）**：A3 从「既有认知下的 0–1 分」上调为 **3 分**；演进建议维持「复用 `WAITING_APPROVAL`、勿新建 PAUSED 态」（§7 改进点 1、E-1）。⚠️ 该结论为**静态控制流复核**，**未实测**（需按门禁口径在实施阶段验证） |
| 2 | 「`ReviewAttributionHook` 是 NoOp 死代码 / 未接线」（MEMORY.md:49、用户素材） | **半推翻（精确化）**：**接线完整**（端口 + 回调 + 装配点）但生产**无 Bean 实现**，是「预留扩展点 + 未闭环」而非「死代码」也不是「已闭环」 | `ReviewAttributionHook.java:18-28`（Javadoc 明写「接线本仓已完成 / 口径产品定 / 默认 NoOp 让位」）、`:41-46`（回调签名）；回调点 `JdbcReviewTaskRepository.java:132-144`（附录 I-03）、装配点 `ApplicationConfiguration.java:302`（`getIfAvailable()`，附录 I-03）；全仓 main 侧 `implements ReviewAttributionHook` 零命中（附录 D-35/36/37） | **本轮更正（与 §4.5 不一致 2 同源）**：A4 归因闭环项不计分（无锚点可引用＝未落地）；判定为「故意预留、非缺陷」，维持 §7 改进点 3（P1）。⚠️ 零命中为全仓 grep 穷尽结果，非检索遗漏 |
| 3 | 「治理审批并发缺验证 / 不具备规模化发布条件」（MEMORY.md:48、用户素材「两人同时通过会不会破坏状态机」） | **收窄（机制齐、缺门禁）**：并发防护机制**齐全**，状态机不会被破坏；缺的只是「两人并发 decide」**专项验收门禁测试** | `JdbcConfigPublishRequestRepository.java:372-377`（`FOR UPDATE` 行锁串行化）→ `:253-254`（`SelfApprovalException`）→ `:256-259`（`state != PENDING → StateConflictException`）→ `:267-273`（审计同事务）→ `:280-283`（CHECK_VIOLATION 兜底 `two_person_ck`，DDL `:1882`） | **本轮更正（与 §4.5 不一致 3 同源）**：A1 维持 3 分（默认生效 + 失败语义 + 有测试门禁的常规部分），但「并发专项门禁」缺口计入「不给 4 分」；`ConfigPublishIT`/`ConfigPublishManagementIT` 的并发用例覆盖的是指针 CAS，非 decide 并发（附录 I-01）。⚠️ **静态合推，未实测**——需 CountDownLatch 双线程 decide 门禁按 §7 改进点 4 / E-4 验证 |
| 4 | 「运行期工具审批已实现 / 已启用」（梳理报告第 13 章、竞品报告维度 13） | **收窄（代码有、开关没开）**：机制（表/端口/闸门/PARK-RESUME/审批台/唤醒/兜底）**全部齐备**，但生产默认 `source.mode=none` + 空策略全 `FAIL_CLOSED` + `ApprovalReadinessGuard` 只告警不阻断 ⇒ **默认全拒、未接线** | `application.yml:569/:571/:580/:584-585`（`live-source-enabled=false` / `require-live-source=false` / `source.mode=none` / `rules: []`）；`RiskBasedApprovalPolicy.java:210/:214`（空策略 `FAIL_CLOSED`）；`ApprovalReadinessGuard.java:73-84`（`log.error` 告警，仅 `requireLiveSource=true` 才 `throw`）；§4.5 不一致 6、附录 G-04~G-07 | **本轮更正（与 §4.5 不一致 6 同源）**：A1 的 fail-closed 严格性（C13 领先）与 A5 的生产可用性（C11 落后）**并存不矛盾**——前者是安全站位，后者是就绪度缺口；演进按 §7 改进点 2（先接 `live-source` + 策略表 + 就绪强校验）推进。⚠️ **未实跑**一次真实 `WRITE_EXTERNAL` 工具验证其确被拒（需按门禁口径在实施阶段验证） |

> **未核实项（诚实声明）**：
> ① **本轮未运行任何测试**（单测 / IT / 并发实验 / 压测 / 故障注入），所有行为断言（含「撤掉修复点会不会红」）均为**静态复核结论**，一律标注「未实测（需按本报告门禁口径在实施阶段验证）」；
> ② **未连库**：真实流量下 `approval` 表状态分布、`cap_approval` 参数哈希的实际匹配率、`resume_invocation_not_found` 的真实发生率、PARK 时长分布**均未核实**（铁律 6：「有一行」≠「数字算对了」）；
> ③ **竞品侧未重新 WebFetch**：5.5.1 的「竞品最高分」全部沿用**第三章 / 附录 F 已核实条目**，故附录 F 的降级标注（Anthropic 旧域重定向、CSRC SoD glossary 404、Temporal 旧 URL 404、NIST PDF 无审批 SLA 阈值）**继续有效**；
> ④ **A5 竞品分填「不评分」**：第三章 / 附录 F 未核实到任何竞品的官方「审批 SLA/拒绝率告警」，按规范不凭印象给分——这使**全轴总分与竞品不可直接比较**，只能看 5.5.2 的同品类子集（A2+A3：2.33 vs 4.00，**−1.67**）。

#### 5.5.5 升分路径（按投入产出比排序）

| 目标 | 当前 → 目标 | 具体动作（到文件/配置级） | 对应改进点 | 解锁的轴 |
|---|---|---|---|---|
| P0-1 | A5 1→2 / A1 3→4 | 接 `live-source` + 策略表 + `require-live-source=true`（`application.yml:569/:571/:580/:584-585`、`RiskBasedApprovalPolicy.java:189/:210`） | §7 改进点 2 / E-2 | A5（可用性）、A1（运行时度量） |
| P0-2 | A1 3→4 | 补「两人并发 decide」专项 DB IT（`CountDownLatch` + `StateConflictException` + 审计恰一），锚点 `JdbcConfigPublishRequestRepository.java:376/:253-259/:1882` | §7 改进点 4 / E-4 | A1（测试门禁闭合） |
| P1-1 | A3 3→4 | 接出口：PARK 时长/孤儿收敛时长 Gauge + `ApprovalController.java:147-149` 的 `wakeup` 失败告警 + `ttl`/`parked-timeout` 启动一致性校验（`application.yml:563/:297`） | §7 改进点 5/6 | A3（运行时度量/告警） |
| P1-2 | A4 3→4 | 审批审计关联维度 10 哈希链（当前无锚点可引用＝未落地） | §7 改进点 6 / E-6 | A4（防篡改护栏） |
| P1-3 | A5 1→2 | 加 `risk_level` 展示（`ApprovalController.java:203-215`）、`reject_ratio`/`pending_age_p95` 告警、批量审批 | §7 改进点 5 / E-5 | A5（有效性观测） |
| P2-1 | A2 1→2+ | 复用 `WAITING_APPROVAL` 引入通用 `pause_reason` + 状态编辑通道（不新增 PAUSED）；任意点 `interrupt()` 属架构级决策，须评估与 DB 触发器的冲突 | §7 改进点 1 / E-1、§8.3 | A2（介入粒度，需架构评审） |

> **升分路径纪律**：以上动作均为**设计建议（工程判断）**，不是已实现事实；每条须带「撤掉修复点会红吗」的反向验证门禁（铁律 6）。抬 A2 到 3+ 会动摇 A1/A4 的结构性优势，属 §5.4 分歧点 1 的路线选择，**不在本轮擅自决定**。

---

## 六、优缺点总评

### 6.1 我们的优点（分级）

- **结构性优势**（改不动、绕不过）：① DB 触发器守卫审批状态机（差异化 1）；② 治理期绑定 / 运行期钉死 + modelRef 不回落 + 指针发布（差异化 4）；③ 三步发布申请与执行分离、前两步不写配置表（D-8）；④ 双人复核「应用层 + DB CHECK」动态 SoD 双保险（差异化 3）。
- **工程性优势**（实现质量高）：⑤ 审批绑定参数摘要/目标资源/有效期 + 双哈希防重放（差异化 2）；⑥ PARK 释放租约 + 异步唤醒 + 不重跑模型的幂等恢复（差异化 5）；⑦ 审批决策缺省拒绝、404 防探测、409 显式终态、审批人来自令牌（差异化 6 / D-6/D-7）；⑧ `RunRecoverySweeper` 超时兜底、TTL 与 PARK 超时自洽注释（D-11）；⑨ 权限动作分离 + 唯一权限解析 fail-closed（D-10）。
- **领域性优势**（贴本项目场景）：⑩ 治理发布覆盖「模型/Agent 定义上线」这一竞品盲区（C6）；⑪ `MANUAL_DISPOSED` UNKNOWN 人工处置受 `UNKNOWN_DISPOSE` 管控（D-12）；⑫ `approver-scope: PARENT_OR_TEACHER` 贴合教育场景的审批受众（application.yml:565）。

### 6.2 我们的缺点（分级）

- **设计取舍导致的代价**（承认、不强改）：① 缺任意点中断 + 状态编辑（T3 选 B，§5.3 劣势 1）；② 无通用 off-switch（同源，§5.3 劣势 5）；③ 治理发布流程长、改一点配置要重走审批（D-9 代价）；④ 运行期工具审批 fail-closed 全拒导致默认可用性低（T4 选 A，§5.3 劣势 3 的一半）。
- **能力缺口**（应补）：⑤ A5 审批有效性观测 / SLA / 批量审批 / 拒绝率指标全空白（§5.3 劣势 2）；⑥ 审批台不展示风险等级与参数摘要，抗 automation bias 能力弱（AP7）；⑦ `ReviewAttributionHook` 生产 NoOp、归因不闭环（§5.3 劣势 4）；⑧ 无「两个审批人并发 decide」专项门禁（§4.5 不一致 3）；⑨ 通用运行期 pause/resume 抽象缺失（§4.5 不一致 5）。
- **运维负担 / 生产就绪度**：⑩ 运行期工具审批 `live-source` 默认未接线（§4.5 不一致 6）；⑪ `ApprovalReadinessGuard` 过时注释误导维护者（§4.5 不一致 4）；⑫ TTL(15m) 与 PARK 超时(1800s) 两个配置无运行时一致性校验，配错产生「人还没批 Run 先失败」的隐蔽 bug（D-11 代价）。

### 6.3 每个竞品的优点（简明，带来源）

| 竞品 | 优点 | 来源 |
|---|---|---|
| LangGraph | 任意点 `interrupt()` + `Command(resume)` 状态可编辑；checkpointer 持久化 + time-travel 回放 | `docs.langchain.com/oss/python/langgraph/interrupts` |
| OpenAI Agents SDK | guardrail 与工具定义同处、审批触发条件贴近业务；概念少上手快 | `openai.github.io/openai-agents-python/guardrails/` |
| Anthropic Claude Agent SDK | 工具级权限拦截粒度细，`PreToolUse` 可 modify 输入；适配 computer use 危险操作确认 | `platform.claude.com/docs/en/agent-sdk/hooks` |
| Google ADK | 远程 REST 审批工程化最完整、结构化确认输入、Session/Event 原生集成 | `adk.dev/tools-custom/confirmation/` |
| Microsoft AutoGen（归档） | 把「人」建模成 agent，`human_input_mode` 三档最直观 | `microsoft.github.io/autogen/stable/` |
| CrewAI | 反馈与流程路由结合（approved/rejected 分支）、webhook 异步人工接入 | `docs.crewai.com/en/learn/human-in-the-loop` |
| Agno | HITL 类型覆盖最全（确认/输入/外部执行/审批审计）、结构化输入 schema 明确 | `docs.agno.com/hitl/overview` |

### 6.4 每个竞品的缺点（简明，带来源）

| 竞品 | 缺点 | 来源/依据 |
|---|---|---|
| LangGraph | 审批正确性靠开发者 + checkpointer；恢复「节点从头重跑」要求节点内幂等；无 SoD/审计/SLA | `interrupts` 页官方 resume 语义 |
| OpenAI Agents SDK | 无通用 durable pause-resume 原语；暂停/恢复/状态全交宿主 | `guardrails` / `handoffs` 页（§3.2 诚实区分） |
| Anthropic Claude Agent SDK | 暂停是进程内回调级，无持久化 checkpointer；无双人复核/审计/TTL | `agent-sdk/overview`、`hooks` 页 |
| Google ADK | 确认点绑定工具执行、恢复约束在 invocation_id 内；SoD/审计需自建 | `adk.dev/tools-custom/confirmation/` |
| Microsoft AutoGen | 已归档维护；人在环是对话参与者非状态机审批门 | Microsoft Agent Framework 迁移公告 |
| CrewAI | 反馈点在任务/流程级、resume 需重传 webhook URLs、无 SoD/审计 | `docs.crewai.com/en/learn/human-in-the-loop` |
| Agno | 工具/步骤级粒度非任意状态编辑；抗绕过性/双人复核非内建 | `docs.agno.com/hitl/overview` |

> **共性盲区**：7 个竞品**均未在框架层内建双人复核 SoD、审批与 RBAC 强制绑定、审批审计哈希链、审批 SLA 有效性观测**。这既是本项目 C3/C4/C8 领先的原因，也说明 A1/A4/A5 中的 A5（有效性）是**全行业共同短板**，非本项目独有缺陷——本报告不把它粉饰为「我们独有优势」，也不夸大落后程度。

---

## 七、可优化与改进点（必须可执行）

> 本章 7 个改进点逐项给「改进项名称 / 问题陈述 / 方案设计（文件·表·配置·接口·DDL 片段） / 预期收益（指标口径） / 实施成本与风险 / 优先级+排期 / 验收方式（可执行门禁）」。所有门禁遵循铁律 6（验收三级 + 撤点反证）、铁律 17（静态守卫 ≠ 运行时成立，须真实交互驱动）、铁律 15（终态不伪装），且不违反 fail-closed / RLS GUC 事务内绑定 / `AgentOverrides` 唯一权限解析等红线。凡新增枚举或状态迁移，**必须同步 `01_platform_ddl.sql` 的 `enforce_run_state_transition()` 触发器与 `RunState` 迁移表**，否则触发器会拒绝合法迁移。

### 改进点 1｜运行期暂停-恢复「泛化」：在现有 `WAITING_APPROVAL` 上补通用暂停原因 + 状态编辑通道（P0）

- **问题陈述**：源码已有专用 PARK/RESUME 闭环（`RunState.WAITING_APPROVAL` E-01、`RunExecutionLoop:1115-1133` E-04、`ApprovalDecidedWakeupConsumer` E-06），但暂停点**只能由 `ToolInvocationGate` 在「工具执行前」触发**（E-11），且 `loop.resume()` 不携带状态 delta（E-05），无法支撑「运行中任意点人工改一段草稿再继续」（§5.3 劣势 1、C2 落后、§4.5 不一致 5）。**重要修正**：用户素材假设「缺 PAUSED 态与恢复入口」——复核证明 PAUSED 语义已由 `WAITING_APPROVAL` 承担、恢复入口齐备，故**不新增 PAUSED 枚举**（避免与 `enforce_run_state_transition` E-03 冲突、避免重复造态），而是把它泛化为通用暂停原语。
- **方案设计**（两条路径，推荐 A）：
  - **路径 A（推荐，低成本，复用现有态）**：给 `public.run` 加三列 + 一个通用暂停端口，不改 `RunState` 枚举、不改状态机迁移集合。
    ```sql
    -- deploy/sql/platform/01_platform_ddl.sql
    ALTER TABLE public.run
      ADD COLUMN pause_reason      text,          -- 'TOOL_APPROVAL' | 'HUMAN_EDIT' | 'OFF_SWITCH' | 'REVIEW'
      ADD COLUMN pause_context    jsonb,         -- 暂停时可编辑的上下文快照（不含已发布定义）
      ADD COLUMN pause_ticket_id  uuid;
    COMMENT ON COLUMN public.run.pause_reason IS
      'WAITING_APPROVAL 态的暂停原因；复用现有 PARK 态，禁止新增 PAUSED 枚举以免与 enforce_run_state_transition 守卫冲突。';
    CREATE UNIQUE INDEX idx_run_pause_ticket ON public.run (tenant_id, pause_ticket_id)
      WHERE pause_ticket_id IS NOT NULL;
    ```
    ```java
    // agent-ports/.../persistence/RunPausePort.java（新增端口）
    public interface RunPausePort {
        /** 在任意节点暂停；reason 决定恢复时走哪条语义；editableContext 为人工可编辑槽位。 */
        @NonNull PauseTicket pause(@NonNull String tenantId, @NonNull String runId,
                                   @NonNull PauseReason reason,
                                   @Nullable Map<String,Object> editableContext);
        /** 按 pauseTicketId 幂等恢复；statePatch 由人工编辑后回写，绝不可触碰 cap_agent_def 已发布行。 */
        @NonNull ResumeResult resume(@NonNull String tenantId,
                                     @NonNull String pauseTicketId,
                                     @Nullable Map<String,Object> statePatch,
                                     @NonNull String approverId);
        enum PauseReason { TOOL_APPROVAL, HUMAN_EDIT, OFF_SWITCH, REVIEW }
    }
    ```
    `resume` 内部仍复用 `ApprovalDecidedWakeupConsumer` 的「按 operationId 反查 + 不重跑模型」路径（E-06/:113/:132），`statePatch` 只作用于 `pause_context`，**严禁写入 `cap_agent_def`**（由 `forbid_published_def_mutation` E-28 兜底）。
  - **路径 B（高成本，仅当必须区分 PAUSED/WaitingApproval 时）**：新增 `RunState.PAUSED` 枚举值，**必须同步** ① `RunState.java:72-79` 迁移表（RUNNING→PAUSED、PAUSED→RUNNING/CANCELLED/TIMED_OUT/FAILED）；② `01_platform_ddl.sql:453-475` `enforce_run_state_transition()` 增加对应 `IF NEW.state='PAUSED'` 分支；③ `RunRecoverySweeper` 的 `state='WAITING_APPROVAL'` 条件（E-08/:602）扩展为 `IN ('WAITING_APPROVAL','PAUSED')`；④ 枚举值按铁律 2 用 `?::run_state` 绑定。**本报告建议不采用**（改动面大、收益与 A 重叠）。
- **预期收益（指标口径）**：可暂停点从 1 类（`TOOL_APPROVAL`）扩到 4 类；「人工编辑后续跑」场景从「不支持」到「支持」，恢复后模型重调次数 = 0、预算二次扣 = 0、工具重复副作用 = 0；`WAITING_APPROVAL` 状态机迁移集合不变 → 触发器改动 = 0（路径 A）。
- **实施成本与风险**：改动面 = 1 张 DDL 新列 + 1 个新端口 + `RunExecutionLoop`/`ApprovalDecidedWakeupConsumer` 扩展，约 3 个模块。**最大风险**：`statePatch` 被误用于改已发布定义（破坏 D-9 不可变发布）→ 用 `forbid_published_def_mutation` 触发器 + `resume` 白名单字段双重约束。迁移影响：新列 nullable，存量 Run 不受影响，无需数据回填；建议灰度（先 `HUMAN_EDIT` 单一 reason 试点）。
- **优先级 + 排期**：**P0**，里程碑「运行期 HITL 泛化」首期（与改进点 2 同批，先接通审批源再泛化暂停）。
- **验收方式（可执行门禁）**：
  1. 单元：`RunPausePort.pause(reason=HUMAN_EDIT)` 后断言 `run.state='WAITING_APPROVAL'`（复用态，非新枚举）、`pause_reason` 落库；`resume` 带 `statePatch` 后断言 `loop.resume` 应用了 patch、`ledger.findInvocation` 调用=1、模型调用=0。
  2. API：`POST /api/runs/{pauseTicketId}/resume` 幂等——同一 ticket 二次调用返回幂等结果且工具不重复执行（断言 `tool_operation` 该 operationId 仅 1 行）。
  3. 集成（撤点反证，铁律 6/17）：撤掉 `enforce_run_state_transition()` 里 `WAITING_APPROVAL→RUNNING` 迁移，`resume` 必抛 `restrict_violation` 变红；恢复后放回去必绿。
  4. 反向安全：`resume` 的 `statePatch` 尝试写 `cap_agent_def` 已发布行 → 必被 `forbid_published_def_mutation` 拒绝（证明不触碰不可变快照，D-9）。
  5. 三级验收：单元（端口逻辑）+ API（幂等/404 语义）+ 角色×任务（APPROVER 可 resume、STUDENT 越权 403 且停轮询，铁律 15）。

### 改进点 2｜高风险工具审批门「生产接线」：`live-source` + 策略表 + 就绪强校验（P0）

- **问题陈述**：`application.yml:569-580` 默认 `live-source-enabled=false` / `require-live-source=false` / `source.mode=none` / `policy.rules: []` → `PolicyEngineApprovalSource` 空策略全 `FAIL_CLOSED`（E-12），`ApprovalReadinessGuard` 只 `log.error` 不阻断（E-35/:73-81）。即「机制齐备、开关没开」，`WRITE_EXTERNAL` 类工具在默认部署一律拒、且无真实审批台（§4.5 不一致 6、§5.3 劣势 3、C11 落后）。
- **方案设计**：
  - 配置（`application.yml:554-580`）：
    ```yaml
    agent:
      approval:
        required-by-risk: { READ: NOT_REQUIRED, WRITE_PROFILE: OPTIONAL, WRITE_EXTERNAL: REQUIRED }
        ttl: 15m
        approver-scope: PARENT_OR_TEACHER
        live-source-enabled: true          # 由 false 打开
        require-live-source: true          # 由 false 打开 → 未接线直接拒绝启动
        source:
          mode: policy-engine              # 由 none 改为 policy-engine
          policy:
            rules:
              - tool: "external.*"         # 高风险工具前缀
                risk: WRITE_EXTERNAL
                verdict: REQUIRES_APPROVAL
    ```
  - `WorkerExecutionConfig.approvalSource()`（:346/:373/:377）：`mode=policy-engine` 且 `rules` 非空时构造 `new PolicyEngineApprovalSource(policy)`（`RiskBasedApprovalPolicy.java:189`），空策略仍 `FAIL_CLOSED` 不变（不违反 fail-closed）。
  - `ApprovalReadinessGuard.run()`（:60-81）：当 `require-live-source=true` 且 `liveSourceEnabled=false` 时按既有逻辑抛 `IllegalStateException` 阻断启动（已有代码，只需打开开关）；同时**修正 `:23` 过时注释**（§4.5 不一致 4）——把「ApprovalController 操作能力/配置发布域」改为「`/api/approvals` 运行期工具审批台，decide 后经 `ApprovalWakeupPort` 唤醒挂起 Run」。
  - `ToolInvocationGate.requestApproval()`（:1590-1606）：确认 `ApprovalRequirement.REQUIRED` + `approverScope` + `Instant.now().plus(ttl)` 落到 `ApprovalPort.request(...)`（E-10 默认空 → 必须确认 `JdbcApprovalRepository.request()` E-09/:181 已覆写，否则待办不可见）。
- **预期收益（指标口径）**：`WRITE_EXTERNAL` 工具从「一律拒」到「按策略进审批、人工通过后执行」；审批待办可见率 = 100%（`request()` 落 `public.approval` 且 `idx_approval_pending` E-31 命中）；未接线启动阻断率 = 100%（`require-live-source=true`）；审批 TTL 与 PARK 超时一致性校验（见改进点 5）。
- **实施成本与风险**：改动面 = 1 个 yml + 1 处注释 + 策略表数据 + 确认 `request()` 覆写，无 DDL 变更。**风险**：策略配错导致「该审的没审」→ 用 `ApprovalReadinessGuard.toolsRequiringApproval()`（:93 纯判定，便于单测钉死）做启动自检；打开 `require-live-source=true` 前须确保审批台服务与 worker 可达（跨进程部署见维度 12）。需灰度：先对单租户单工具前缀试点。
- **优先级 + 排期**：**P0**，与改进点 1 同批首期（先让运行期 HITL 真正可用）。
- **验收方式（可执行门禁）**：
  1. 配置门禁：启动时断言 `toolsRequiringApproval` 覆盖所有 `WRITE_EXTERNAL` 注册工具，缺失即 `IllegalStateException`（撤掉 `require-live-source=true` 则「未接线也能启动」变红，撤点反证）。
  2. API 闭环：触发一个 `WRITE_EXTERNAL` 工具 → 断言 `public.approval` 新增 1 行 `state=PENDING`、`run.state=WAITING_APPROVAL`、租约已释放；`POST /api/approvals/{id}/decision` `APPROVE` → 断言 `state=CONSUMED`、Run 恢复为 `RUNNING`、工具执行 1 次、`loop.resume` 未重跑模型。
  3. fail-closed 反向：把 `source.rules` 清空 → 断言所有需审工具 `FAIL_CLOSED` 全拒、零副作用（证明没退化成默认放行）。
  4. 三级：单元（策略映射）+ API（审批→恢复全链路）+ 角色×任务（PARENT/TEACHER 可批、STUDENT 403 停轮询）。

### 改进点 3｜`ReviewAttributionHook` 真正落地：从 NoOp 到实际归因回写（P1）

- **问题陈述**：端口 + 回调接线完整（E-32/E-33），但 `ApplicationConfiguration.reviewTaskPort()` 用 `getIfAvailable()`，生产无 Bean → `resolve()` 走 NoOp、`rootCause ∈ {PROMPT,CONFIG,MODEL}` 的归因结论不回写、人工复核不驱动迭代闭环（D-13、§4.5 不一致 2、§5.3 劣势 4、C12 落后）。
- **方案设计**：
  - 新增实现类 `platform-bootstrap/.../quality/DefaultReviewAttributionHook.java`：
    ```java
    @Component  // 一旦注册，ApplicationConfiguration.reviewTaskPort():302 的 getIfAvailable() 即返回它
    public class DefaultReviewAttributionHook implements ReviewAttributionHook {
        @Override
        public void onAttributionResolved(@NonNull String traceId, @NonNull String tenantId,
                                          @NonNull String runId, @NonNull String rootCause,
                                          @Nullable String note, @NonNull String reviewer) {
            // 按 rootCause 分流写入迭代待办：PROMPT→提示词修订、CONFIG→配置修订、MODEL→模型评估
            attributionPort.record(tenantId, traceId, runId, rootCause, note, reviewer, Instant.now());
        }
    }
    ```
  - 新增落点表（或复用 `review_task` 派生列）：`review_attribution(tenant_id, trace_id, run_id, root_cause, note, reviewer, created_at)`，按铁律 7 同步 `03_platform_roles.sql` 授权、按铁律 1 在事务内绑定 RLS GUC。
  - **保留 fail-soft 语义**：`JdbcReviewTaskRepository.resolve()` 现有「钩子异常仅 `log.warn`、任务状态不回滚」（E-33/:139 附近）不变——归因回写失败不得阻塞任务关闭（不违反「事务内 SQL 失败禁 fail-soft 后 COMMIT」铁律 11 的边界：钩子在任务关闭事务之外独立处理）。
  - 触发条件不变：`ITERATION_ROOT_CAUSES = {PROMPT, CONFIG, MODEL}`（E-33/:149），其他 rootCause 不回写，避免噪声。
- **预期收益（指标口径）**：PROMPT/CONFIG/MODEL 类复核任务的归因回写率从 0% 到 100%；「复核结论→迭代待办」可追溯（每条带 traceId/runId/reviewer）；任务关闭成功率不变（fail-soft 保持）。
- **实施成本与风险**：改动面 = 1 个 Bean + 1 张表 + 角色授权，无状态机改动。**风险**：产品归因口径未定就硬写会「编造归因结论」——本方案只**记录**结论、不自动改动提示词/模型（避免越过治理发布审批 D-9）；真正触发迭代仍须走 `requestPublish` 三步流程。需与产品确认口径后灰度。
- **优先级 + 排期**：**P1**，里程碑「人工复核闭环」二期（依赖产品归因口径拍板）。
- **验收方式（可执行门禁）**：
  1. 单元（撤点反证）：注入 `DefaultReviewAttributionHook` Bean，`resolve()` 一个 `rootCause=PROMPT` 的任务 → 断言 `onAttributionResolved` 调用 1 次、`review_attribution` 落 1 行；移除该 Bean → 断言调用 0 次（证明 §4.5 不一致 2 的「接线完整/生产 NoOp」判定）。
  2. 分流门禁：`rootCause=PROMPT/CONFIG/MODEL` 各落 1 行；`rootCause=OTHER` 落 0 行。
  3. fail-soft：让 `attributionPort.record` 抛异常 → 断言 `resolve()` 仍把任务置为已处置、`log.warn` 出现、事务不回滚（不违反铁律 11 边界）。
  4. 安全：断言回写**不触碰** `cap_agent_def`（`forbid_published_def_mutation` 未被触发、已发布行未变）。

### 改进点 4｜治理审批并发正确性：补「两人同时批准」专项门禁（P1）

- **问题陈述**：机制层面 `FOR UPDATE` + 应用层状态前置 + `two_person_ck` 已保证状态机不被破坏（§4.5 不一致 3、E-17/E-25），但现有 `ConfigPublishIT` 的并发用例是「指针 CAS 抢占/变基重试」（E-39），`ConfigPublishManagementIT` 只有单人 `selfApprovalIsRejected`（E-40）——**缺「两个审批人并发 decide 同一申请单」的专项验收**，MEMORY 把它列为规模化发布短板（:48）。结论：机制有锁，缺的是可回归的并发门禁。
- **方案设计**：
  - 新增 IT：`ConfigPublishManagementIT#concurrentDecideSerializesAndKeepsSingleWinner()`，用 `CountDownLatch` 双线程对同一 `requestId` 并发 `decide(APPROVE)`：
    ```java
    CountDownLatch start = new CountDownLatch(1);
    var r1 = pool.submit(() -> { start.await(); return workflow.decide(tenantA, id, APPROVE, "A", APPROVER_1); });
    var r2 = pool.submit(() -> { start.await(); return workflow.decide(tenantA, id, APPROVE, "B", APPROVER_2); });
    start.countDown();
    // 断言：恰好一个返回 APPROVED，另一个抛 StateConflictException（非 PENDING）
    ```
  - **不改生产代码**（`JdbcConfigPublishRequestRepository.decide()` 的 `FOR UPDATE` 已正确），只补测试门禁 + 一条静态守卫断言（防止未来有人误删 `FOR UPDATE`）：
    ```java
    // 静态守卫：parse decide() SQL 必须含 "FOR UPDATE"，否则测试红
    assertThat(decideSqlOf(JdbcConfigPublishRequestRepository.class)).contains("FOR UPDATE");
    ```
  - 呼应 SoD：并发两审批人 `APPROVER_1/APPROVER_2` 均 ≠ `requesterId`，且断言 `two_person_ck` 在 DB 直连 bypass 应用层时仍生效（直接 `UPDATE` 把 `approver_id=requester_id` → 断言 CHECK 拒绝）。
- **预期收益（指标口径）**：并发 decide 的「成功者数量」恒 = 1（状态机唯一性）、审计记录恒 = 1 条、最终 `state` 唯一；把「并发正确性」从「靠代码审查推断」变成「每次 CI 可回归门禁」。
- **实施成本与风险**：改动面 = 1 个测试方法 + 1 条静态守卫，**零生产代码改动**、零迁移。**风险**：并发测试须真实起事务（禁用 H2、`@Rollback(false)` 清理），否则 `FOR UPDATE` 不生效、测了个寂寞（铁律 17：静态守卫 ≠ 运行时成立，须真实交互驱动）。
- **优先级 + 排期**：**P1**，与改进点 3 同批二期（补齐 MEMORY 发布短板，为规模化发布判定解除一项阻塞）。
- **验收方式（可执行门禁）**：
  1. 并发门禁：双线程 decide，断言「1 成功 + 1 `StateConflictException`」、审批审计行 = 1、最终 `state` 唯一、无第二条 `APPROVED` 记录；重复跑 50 次无 flaky（固定 `CountDownLatch` 起跑线）。
  2. 撤点反证（铁律 6/17）：临时把 `loadForUpdate` 的 `FOR UPDATE` 去掉，断言并发下出现两次 `APPROVED` → 测试必红；恢复后必绿（证明门禁真在守卫锁，而非摆设）。
  3. SoD 反向：DB 直连把 `approver_id` 改成 `requester_id` → `two_person_ck` 拒绝。
  4. 静态守卫：`decideSql` 必须含 `FOR UPDATE`、`decide()` 方法必须存在 `state != PENDING → StateConflictException` 分支。

### 改进点 5｜审批体验与吞吐：批量审批 + 超时自动处置 + SLA 指标与告警（P1）

- **问题陈述**：A5 有效性空白（§5.3 劣势 2、AP1/AP7 未规避）——无批量审批、无审批 SLA 指标、无拒绝率/审批时长观测、审批台不展示 `risk_level`/参数摘要；高频审批诱发 alert fatigue（§2.1 框架 6）。当前 `POST /api/approvals/{id}/decision` 只能单条处理（E-13/:124）。
- **方案设计**：
  - 新增批量端点 `POST /api/approvals/batch-decision`（`ApprovalController`）：
    ```java
    public record BatchDecisionRequest(@NonNull List<String> approvalIds,
                                       @NonNull String decision,  // 仍走 approve() 缺省拒绝语义 D-6
                                       @Nullable String reason) {}
    // 逐条复用 decide()（含 APPROVAL_CONSUME 权限、审计同事务、wakeup 同步唤醒），
    // 单条失败不影响其他条，返回每条 {approvalId, newState, error?}；绝不「全批一个事务」以免一条坏数据回滚整批
    ```
  - 审批台展示增强：`GET /api/approvals`（E-13/:94）返回体补 `riskLevel` / `toolName` / `argumentDigest` / `targetResource` / `expiresAt` / `ageSeconds`，让审批人「看到风险再决定」（对抗 automation bias，抗 AP7）；**默认值绝不勾选 APPROVE**（延续 D-6 缺省拒绝）。
  - SLA 指标（`platform-bootstrap` Micrometer/Prometheus，呼应维度 10）：`approval_pending_age_seconds`（直方图）、`approval_decided_total{decision=APPROVE|DENY}`、`approval_reject_ratio`、`approval_timeout_total{reason=APPROVAL_TIMEOUT}`、`approval_batch_seconds`；告警规则：`pending_age_p95 > 0.8*ttl` 预警、`reject_ratio` 长期 ≈ 0 触发「橡皮图章可疑」审计抽查、`timeout_total` 突增告警。
  - 超时自动处置：`RunRecoverySweeper`（E-37）已有 `failStaleParkedRuns`，补一条「审批 TTL 到期但 Run 未到 PARK 超时」时把 `public.approval` 从 `PENDING`→`EXPIRED`（由 `enforce_approval_state_transition` E-21 守卫），并给客户端推终态；**TTL 与 `parked-timeout-seconds` 启动一致性校验**（见改进点 2 配置）：`assert parkedTimeout > ttl`，否则启动即报（D-11 代价的运行时校验补齐）。
- **预期收益（指标口径）**：批量审批吞吐提升（单批 N 条 1 次 HTTP，目标 `approval_batch_seconds` p95 < 单条×N 的 50%）；审批有效性可观测——`reject_ratio`、`pending_age_p95`、`timeout_total` 三项从「无」到「有且可告警」；橡皮图章可被审计抽查发现（`reject_ratio≈0` 告警）。
- **实施成本与风险**：改动面 = 1 个 Controller 端点 + 返回体 DTO + 指标埋点 + Prometheus 规则 + 启动一致性校验。**风险**：批量审批放大 automation-bias（一键全通过）→ 用「展示风险 + 缺省拒绝 + `reject_ratio` 告警 + 抽样复核」对冲；批量不共用事务避免部分失败放大。需灰度（先 10 条/批上限）。
- **优先级 + 排期**：**P1**，二期（依赖改进点 2 的审批台先可用）。
- **验收方式（可执行门禁）**：
  1. 批量语义：提交 10 条（含 1 条已 `CONSUMED`）→ 断言 9 条成功、1 条 409 显式终态、成功条各自 `wakeup` 被调用 1 次、失败条不回滚其他条。
  2. 缺省拒绝：批量请求 `decision` 传空/小写 `approve` → 断言全部按 DENY 处理（延续 D-6）。
  3. SLA 指标：造 100 条 PENDING 停留到 `0.8*ttl` → 断言 `approval_pending_age_seconds` 直方图有值、`pending_age_p95 > 0.8*ttl` 告警规则命中（真实交互驱动，铁律 17）。
  4. TTL/PARK 一致性（撤点反证）：把 `parked-timeout-seconds` 配成 `< ttl` → 启动必报配置错误；恢复合法值必启动成功。
  5. 橡皮图章反证：连续 100 次全 APPROVE 且 `reject_ratio≈0` → 断言「可疑审计抽查」告警生成（证明 A5 观测真的能发现 AP1）。

### 改进点 6｜审批审计与可追溯：关联维度 10 审计哈希链（P1）

- **问题陈述**：审批审计已「同事务」落库（`approver_id` 来自令牌、参数摘要留痕，E-13/E-17/:267），但**未与维度 10 的审计哈希链打通**——「谁在何时基于什么批准了什么」目前散在 `approval` / `cap_config_publish_request` / `authz_decision_log` 多处，缺一条不可篡改、可顺序校验的链（C8「领先但需落地」、§2.1 框架 2 GDPR「可质疑」、AP4）。
- **方案设计**：
  - 在 `audit.recordRequired(...)`（`JdbcConfigPublishRequestRepository.java:267` 与 `ApprovalController.decide` 对应审批落库处）之外，新增审批事件写 `audit_chain` 的旁路（复用维度 10 既有哈希链组件，不新造）：每个审批事件记 `{tenant_id, event_type=APPROVAL_DECIDED|CONFIG_DECIDED, ref_id=approvalId|requestId, actor=principalId, action=APPROVE|DENY, argument_digest, target_resource, decided_at, prev_hash, hash}`。
  - 提供校验端点 `GET /api/approvals/{id}/audit-trail`（`APPROVAL_CONSUME` 或 `AUDIT_READ` 权限），返回按 `hash` 链顺序可校验的事件序列；客户端可自校验 `hash = SHA256(prev_hash || canonical(event))`。
  - 约束：① 审批审计与业务状态变更**同事务**（已有，D-7/E-17/:267），哈希链写入失败 → 事务按铁律 11「POISONED」不得 fail-soft 后 COMMIT；② `authz_decision_log` 按铁律 2 禁 `RETURNING`；③ RLS GUC 事务内绑定（铁律 1）；④ 审批人标识仍只取令牌（D-6）。
- **预期收益（指标口径）**：审批事件链可校验率 = 100%（任一哈希被篡改即被校验端点发现）；「谁/何时/基于什么批准了什么」从「多表拼查」到「单链顺序校验」；满足 GDPR Art.22「contest 决定」与 EU AI Act 可追溯要求的可举证性。
- **实施成本与风险**：改动面 = 审批落库处挂哈希链 + 1 个校验端点 + 复用维度 10 组件。**风险**：哈希链写入增加事务耗时 → 用批量异步落链但**保证同事务标记**（不得破坏「事务内 SQL 失败不 COMMIT」）；链断裂的修复需运维流程。需灰度（先治理发布审批链，再运行期审批链）。
- **优先级 + 排期**：**P1/P2 之间**，二期挂链、三期校验端点完善（与维度 10 联合排期）。
- **验收方式（可执行门禁）**：
  1. 链完整性：连续 5 次审批 → 断言 `audit_chain` 恰 5 条、每条 `prev_hash` = 前条 `hash`、客户端自校验 `hash` 全通过。
  2. 篡改反证（撤点反证）：手动改中间一条 `action` → 校验端点必报「链断裂」（证明不可篡改，非纸面）。
  3. 事务一致性（铁律 11）：让哈希链写入抛异常 → 断言审批状态**未** COMMIT、事务标记 POISONED（不得出现「审批已生效但无审计」）。
  4. 权限：`GET /audit-trail` 用 STUDENT 令牌 → 403 且停轮询（铁律 15）；APPROVER/AUDIT_READ → 200。
  5. 三级：单元（哈希计算）+ API（链校验）+ 角色×任务（权限矩阵）。

### 改进点 7｜审批拒绝/超时的客户端语义：终态明确 + 停轮询 + 不给重试（P0，呼应铁律 15）

- **问题陈述**：服务端已给显式终态（404 不区分存在性、409 冲突、`REJECTED/EXPIRED/FAILED` 终态、审批人来自令牌，D-7/E-13），但**客户端契约未成文**——前端 SSE（维度 04）与审批台调用方若把 `REJECTED/EXPIRED/409/404/403` 当「临时故障」重试或无限轮询，就会把「人明确拒绝」误演成「系统抖动」（AP4、铁律 15 在 HITL 语境的延伸，§1.1 第六问、C9「领先但客户端落地待补」）。
- **方案设计**：
  - 成文契约（`ApprovalController` Javadoc + 前端错误隔离 D1/D2 文档）：
    | 服务端返回 | 语义 | 客户端动作 |
    |---|---|---|
    | `200` + `newState=APPROVED/CONSUMED` | 审批通过、Run 已/将恢复 | 继续轮询 Run 状态至终态 |
    | `200` + `newState=REJECTED` | **人明确拒绝（终态）** | **停轮询**、展示拒绝原因、不给「重试审批」按钮 |
    | `409` + `EXPIRED`/已被决定 | **终态冲突** | 停轮询、提示「申请已失效/已处理」、不给重试 |
    | `404` | 不存在**或**无权限（刻意不区分） | 停轮询、按「无权访问/不存在」提示、不给重试、不探测 |
    | `401/403` | 权限/认证终态 | 停轮询、跳登录/鉴权、不给重试 |
    | `5xx`/网络超时 | 唯一可重试类 | 有界退避重试（≤3 次），且重试前先查一次审批状态避免重复 decide |
  - 服务端补一个**幂等键**：`POST decision` 支持 `Idempotency-Key`（复用铁律 2 的枚举/RETURNING 约束注意项），重复提交返回首次结果而非「再次审批」，避免 5xx 重试导致 `StateConflictException` 被误当失败。
  - `DecisionRequest.approve()`（E-13/:177-180）保持「缺省即 DENY」——客户端必须显式 `decision="APPROVE"`；API 文档明示，避免「静默按拒绝」的误解（D-6 代价的客户端侧缓解）。
  - SSE 侧（维度 04）：Run 进入 `WAITING_APPROVAL` 推「等待审批」帧，`REJECTED/EXPIRED/TIMED_OUT/FAILED` 推**终帧** `done` 且带 `terminal_reason`，前端见终帧即停轮询（呼应铁律 12 的 `done` 终帧约定）。
- **预期收益（指标口径）**：终态误重试率 = 0（客户端对 4xx/终态不发起重试）；无限轮询会话数 = 0（见终态/终帧即停）；重复 decide 导致的 `StateConflictException` 噪声下降（幂等键）；「拒绝被当成系统抖动」类客诉从不可统计到可统计（`terminal_reason` 埋点）。
- **实施成本与风险**：改动面 = Controller Javadoc/契约 + 幂等键 + 前端错误隔离 + SSE 终帧，跨前后端。**风险**：把 5xx 也误判为终态会导致「真故障被吞」→ 契约明确「仅 5xx/网络超时可重试、有界退避」；幂等键存储需清理策略（TTL）。需前端 ErrorBoundary + 全局未捕获错误安全网（铁律 18）+ 错误隔离 D1/D2/D3/D6/D7（铁律 19）配合。
- **优先级 + 排期**：**P0**，首期与改进点 1/2 同批（运行期 HITL 一旦可用，客户端语义必须同步，否则体验/合规双输）。
- **验收方式（可执行门禁）**：
  1. 契约门禁（前端单测/集成）：对 `REJECTED`/`409`/`404`/`403` 各造一次 → 断言轮询循环停止、无重试请求发出、`Idempotency-Key` 未产生第二条 decide；对 `5xx` → 断言有界重试 ≤3 次且每次先查状态。
  2. 幂等：同一 `Idempotency-Key` 提交两次 `APPROVE` → 断言返回同一结果、审批审计恰 1 条、`wakeup` 恰 1 次。
  3. 缺省拒绝反证：提交 `decision` 缺省 → 断言 `newState=REJECTED` 且前端按「终态拒绝」处理（不弹重试）。
  4. SSE 终帧：Run 被拒 → 断言收到带 `terminal_reason` 的 `done` 终帧、前端停轮询（撤掉终帧推送 → 前端轮询不停止变红，撤点反证，铁律 17）。
  5. 三级 + 角色×任务：STUDENT 越权 403 停轮询、APPROVER 正常决策、D1/D2 错误隔离不串状态。

### 改进点优先级与排期汇总

| 改进点 | 优先级 | 里程碑批次 | 改动面 | 是否需灰度 | 关联铁律 |
|---|---|---|---|---|---|
| 1 运行期暂停-恢复泛化 | P0 | 首期（运行期 HITL 泛化） | DDL 新列 + 新端口 + 2 模块 | 是（单 reason 试点） | 6/17/2、D-9 不可变发布 |
| 2 工具审批门生产接线 | P0 | 首期 | yml + 注释 + 策略数据 | 是（单租户单工具） | fail-closed、8 |
| 7 客户端终态语义 | P0 | 首期（与 1/2 同批） | Controller 契约 + 幂等键 + 前端 + SSE | 是 | 15/18/19/12 |
| 3 ReviewAttributionHook 落地 | P1 | 二期（复核闭环） | 1 Bean + 1 表 + 授权 | 是（依赖产品口径） | 11/7/1 |
| 4 治理审批并发门禁 | P1 | 二期 | 1 IT + 静态守卫，**零生产改动** | 否 | 6/17 |
| 5 审批体验与吞吐 | P1 | 二期 | Controller + 指标 + Prometheus 规则 | 是（批量上限） | 17 |
| 6 审批审计哈希链 | P1/P2 | 二期挂链 / 三期校验 | 挂维度 10 组件 + 1 端点 | 是（先治理域） | 11/2/1/15 |

> **收官篇整体判断**：P0 三项（1/2/7）构成「让运行期 HITL 真正可用且客户端语义正确」的最小闭环；P1 四项补齐有效性、并发门禁、复核闭环与可追溯。7 项均不违反项目铁律，其中改进点 4 是**纯测试门禁**（零生产改动），改进点 1 的关键约束是「状态编辑不得触碰 `cap_agent_def` 已发布行」。所有门禁数值（批量上限、告警阈值、重试 ≤3 次、50 次并发无 flaky、0.8*ttl 预警）为**建议初值**，须由压测/试运行基线替换（诚实边界声明第五节）。

### 7.8 七项改进点的落地片段、三级门禁与回滚（附录 E 的正文速查）

> 本节把前述 7 个改进点的「方案设计」压缩为**可直接落地的 Java / YAML / DDL / SQL 片段**，并逐条钉死三级验收门禁（单元 / API / 角色×任务）与回滚策略。完整六段式（前置条件 / 改动清单 / 三级门禁 / 反向验证 / 回滚 / 铁律对齐）见**附录 E-1～E-7**。所有片段均为**规划稿未落地**；门禁一律回答「撤掉修复点会红吗」（铁律 6），且遵循铁律 17「静态守卫 ≠ 运行时成立，须真实交互驱动」。

#### 7.8.1 改进点 1（P0）｜泛化暂停：复用 `WAITING_APPROVAL`，不新增 PAUSED

- **DDL 片段**（`01_platform_ddl.sql`，路径 A）：
  ```sql
  ALTER TABLE public.run
    ADD COLUMN pause_reason text,          -- TOOL_APPROVAL | HUMAN_EDIT | OFF_SWITCH | REVIEW
    ADD COLUMN pause_context jsonb,
    ADD COLUMN pause_ticket_id uuid;
  CREATE UNIQUE INDEX idx_run_pause_ticket ON public.run (tenant_id, pause_ticket_id)
    WHERE pause_ticket_id IS NOT NULL;
  ```
- **Java 片段**（新增 `RunPausePort`）：`pause(tenantId, runId, reason, editableContext)` / `resume(tenantId, pauseTicketId, statePatch, approverId)`；`resume` 内部仍走 `ApprovalDecidedWakeupConsumer.java:113/:132` 的按 operationId 反查路径，`statePatch` **只写 `pause_context`**，白名单字段外一律拒（禁碰 `cap_agent_def`，由 DDL `:4706` `forbid_published_def_mutation` 兜底）。
- **三级门禁**：① 单元——`pause(HUMAN_EDIT)` 后断言 `state='WAITING_APPROVAL'`（复用态）、`modelGateway` 调用 0 次；② API——`POST /api/runs/{ticket}/resume` 二次调用幂等，`tool_operation` 该 operationId 仅 1 行；③ 角色×任务——APPROVER 可 resume、STUDENT 403 且停轮询（铁律 15）。**撤点即红**：撤 `RunState.java:75` 的 `WAITING_APPROVAL→RUNNING` 迁移，`resume` 必抛 `restrict_violation`。
- **回滚**：路径 A 三列 nullable，停调用 `RunPausePort` 即回到纯工具审批门；DDL 列保留（不删，避免丢 `pause_reason` 历史）。**禁止**回滚成「新增 PAUSED 枚举」（会与 DDL `:462-484` 触发器 + 维度 03 迁移表冲突）。

#### 7.8.2 改进点 2（P0）｜审批源生产接线：`live-source` + 策略表 + 就绪强校验

- **YAML 片段**（`application.yml:554-586`）：
  ```yaml
  agent.approval:
    live-source-enabled: true      # false → true
    require-live-source: true      # false → true，未接线拒启
    source.mode: policy-engine     # none → policy-engine
    policy.rules:
      - "tenant:external.*=REQUIRES_APPROVAL"
  ```
- **Java 片段**：`WorkerExecutionConfig.approvalSource:346/:373/:377` 在 `mode=policy-engine && rules` 非空时构造 `PolicyEngineApprovalSource(policy)`（`RiskBasedApprovalPolicy.java:189`），空策略仍 `FAIL_CLOSED`（`:210/:214`）；`ApprovalReadinessGuard:60-81` 按既有逻辑抛 `IllegalStateException` 阻断启动；**同步修正 `ApprovalReadinessGuard.java:22-23` 过时注释**（其「ApprovalController 操作另一条链路」与 `ApprovalController.java:124-166` 运行期审批台实现不符，§4.5 不一致 4）。
- **三级门禁**：① 单元——`toolsRequiringApproval():93` 覆盖所有 `WRITE_EXTERNAL` 注册工具；② API——触发 `WRITE_EXTERNAL` → `approval` 增 1 行 `PENDING`、`run.state=WAITING_APPROVAL`、租约释放，decide 后工具执行 1 次；③ 角色×任务——PARENT/TEACHER 可批、STUDENT 403 停轮询。**撤点即红**：撤 `require-live-source=true` ⇒ 「未接线也能启动」用例红；清空 `rules` ⇒ 全 `FAIL_CLOSED`、零副作用。
- **回滚**：三个 yml 键回 false/none 即回到当前「全拒 + 告警」安全态（`application.yml:569-580` 现状）；无 DDL 变更。注释修正可单独保留（纯文档）。

#### 7.8.3 改进点 3（P1）｜`ReviewAttributionHook` 从 NoOp 到真实回写

- **Java 片段**（新增 `DefaultReviewAttributionHook`，`@Component` 使 `ApplicationConfiguration.java:302` 的 `getIfAvailable()` 命中）：
  ```java
  @Component
  public class DefaultReviewAttributionHook implements ReviewAttributionHook {
    @Override public void onAttributionResolved(String traceId, String tenantId, String runId,
        String rootCause, String note, String reviewer) {
      attributionPort.record(tenantId, traceId, runId, rootCause, note, reviewer, Instant.now());
    }
  }
  ```
- **DDL 片段**：`review_attribution(tenant_id, trace_id, run_id, root_cause, note, reviewer, created_at)`，同步 `03_platform_roles.sql` 授权（铁律 7）+ 事务内绑定 RLS GUC（铁律 1）。**保留 fail-soft**：`JdbcReviewTaskRepository.java:141-144` 的「钩子异常仅 log.warn、任务不回滚」不变（钩子在关单事务外独立处理，不违反铁律 11）。
- **三级门禁**：① 单元——注入 Bean 后 `resolve(PROMPT)` 断言回调 1 次 + 落 1 行；移除 Bean ⇒ 0 次（证明 §4.5 不一致 2）；② API——`OTHER` rootCause 落 0 行、`record` 抛异常时任务仍关闭；③ 角色×任务——复核员看到归因留痕、学生侧不可见。**撤点即红**：删 `@Component` ⇒ 回写断言红。
- **回滚**：移除 `@Component`（保留类）即回 NoOp；表可保留（默认空）。**关键**：不得让回写触发自动改提示词/模型（会绕过治理发布 D-9）。

#### 7.8.4 改进点 4（P1）｜治理审批并发 decide 专项门禁（纯测试，零生产改动）

- **Java 片段**（新增 `ConfigPublishManagementIT#concurrentDecideSerializesAndKeepsSingleWinner`）：
  ```java
  CountDownLatch start = new CountDownLatch(1);
  var r1 = pool.submit(() -> { start.await(); return workflow.decide(t, id, APPROVE, "A", APPROVER_1); });
  var r2 = pool.submit(() -> { start.await(); return workflow.decide(t, id, APPROVE, "B", APPROVER_2); });
  start.countDown();
  // 断言：恰 1 成功 + 1 StateConflictException；审计恰 1 条；终态唯一；跑 50 次无 flaky
  ```
- **静态守卫片段**：`assertThat(decideSqlOf(JdbcConfigPublishRequestRepository.class)).contains("FOR UPDATE");`（钉死 `:376`）。
- **三级门禁**：① 单元——并发断言 + 静态 SQL 守卫；② API——真实 PG（`IT_HOST/IT_PORT`、`@Rollback(false)` 清理）双线程 decide，禁用 H2 假锁（铁律 17）；③ 角色×任务——两审批人 ≠ 申请人、DB 直连改 `approver_id=requester_id` ⇒ `:1882` CHECK 拒绝。**撤点即红**：临时删 `loadForUpdate` 的 `FOR UPDATE` ⇒ 出现两次 `APPROVED` ⇒ 必红。
- **回滚**：**无生产代码可回滚**——仅删测试类（但须在 PR 记录原因，不得为了让 CI 绿而删，铁律 6）。

#### 7.8.5 改进点 5（P1）｜批量审批 + SLA 指标 + 超时自动处置

- **Java 片段**（`ApprovalController` 新增 `POST /api/approvals/batch-decision`）：
  ```java
  public record BatchDecisionRequest(List<String> approvalIds, String decision, String reason) {}
  // 逐条复用 decide()（权限/审计/wakeup），单条失败返回 {newState, error} 不回滚其他条
  ```
- **YAML/指标片段**（Micrometer）：`approval_pending_age_seconds`（直方图）、`approval_decided_total{decision}`、`approval_reject_ratio`、`approval_timeout_total`、`approval_batch_seconds`；告警 `pending_age_p95 > 0.8*ttl`。**启动一致性校验**：`assert parkedTimeout > ttl`（`application.yml:297` vs `:563`，D-11 代价的运行时补齐）。
- **三级门禁**：① 单元——批量 10 条含 1 条已 CONSUMED ⇒ 9 成功 / 1 个 409，各自 wakeup 1 次；② API——造 100 条 PENDING 到 `0.8*ttl` ⇒ 直方图有值且告警命中；③ 角色×任务——`reject_ratio≈0` 连续 100 次全批 ⇒ 「橡皮图章可疑」审计抽查告警。**撤点即红**：把 `parked-timeout-seconds` 配 `< ttl` ⇒ 启动必报。
- **回滚**：批量端点下线路即回单条审批；指标/告警可单独停用；**启动一致性校验建议保留**（它是修 bug 的护栏，不是新功能）。

#### 7.8.6 改进点 6（P1）｜审批审计关联维度 10 哈希链

- **Java 片段**：在 `JdbcConfigPublishRequestRepository.java:267` 与 `ApprovalController.java:139-141` 审批落库处旁路写 `audit_chain`（复用维度 10 组件，不新造）：`{tenant_id, event_type=APPROVAL_DECIDED|CONFIG_DECIDED, ref_id, actor, action, argument_digest, target_resource, decided_at, prev_hash, hash}`；`GET /api/approvals/{id}/audit-trail` 客户端可自校验 `hash = SHA256(prev_hash || canonical(event))`。
- **约束**：同事务（铁律 11，哈希链写入失败 ⇒ 事务毒化，不得 fail-soft 后 COMMIT）；`authz_decision_log` 禁 RETURNING（铁律 2）；RLS GUC 事务内绑定（铁律 1）；审批人取自令牌（D-6）；`audit_record` / `erasure_certificate` **禁物理删除**（P0 验收报告 §2 第 1 项守卫的表）。
- **三级门禁**：① 单元——连续 5 次审批 ⇒ 链恰 5 条、`prev_hash` 衔接、自校验全通过；② API——手动改中间一条 `action` ⇒ 校验端点报「链断裂」；③ 角色×任务——STUDENT 取 audit-trail ⇒ 403 停轮询，AUDIT_READ ⇒ 200。**撤点即红**：哈希链写入抛异常 ⇒ 审批状态未 COMMIT（POISONED）。
- **回滚**：旁路写链可停用（链为空，审批主流程不受影响）；校验端点可下线；**已写入的链数据不删**（删链 = 毁证据）。

#### 7.8.7 改进点 7（P0）｜客户端终态语义（契约 + 幂等键 + SSE 终帧）

- **契约表**（成文于 `ApprovalController` Javadoc + 前端错误隔离层）：`200+APPROVED/CONSUMED` → 继续轮询；`200+REJECTED` → **停轮询 + 展示拒绝原因 + 无重试按钮**；`409 EXPIRED/已决定` → 停轮询提示已失效；`404`（不区分存在性）→ 停轮询、不探测；`401/403` → 停轮询跳鉴权；仅 `5xx`/网络超时 → 有界退避 ≤3 次且先查状态。**服务端补 `Idempotency-Key`**（本轮 grep `ApprovalController.java` 零命中，是真实缺口），重复提交返回首次结果。
- **前端片段**（`GovernancePages.tsx:87-90` 修正）：`catch` 中按 `status` 分流——`4xx` 展示终态文案（禁「请稍后重试」），仅 `5xx` 走有界重试；`SSE` 侧 Run 被拒推 `done` 终帧带 `terminal_reason`（铁律 12）。
- **三级门禁**：① 单元——对 `REJECTED/409/404/403` 各造一次 ⇒ 轮询停止、无重试请求、无第二条 decide；`5xx` ⇒ 有界重试 ≤3；② API——同 `Idempotency-Key` 提交两次 ⇒ 返回同一结果、审计恰 1 条、wakeup 恰 1 次；③ 角色×任务——STUDENT 403 停轮询、D1/D2 错误隔离不串状态。**撤点即红**：删 SSE 终帧推送 ⇒ 前端轮询不停止变红（铁律 17）。
- **回滚**：契约文档与前端分流可回退到「一律提示稍后重试」（**这是当前行为，但违反铁律 15，回滚须记录合规债**）；幂等键可停校验；`DecisionRequest.approve()` 缺省拒绝（D-6）**任何情况下不得回滚为缺省批准**。

#### 7.8.8 七项门禁强度总览

| 改进点 | 撤点即红的关键断言 | 回滚主开关 | 是否零生产改动 |
|---|---|---|---|
| 1 泛化暂停 | 撤 `WAITING_APPROVAL→RUNNING` ⇒ `restrict_violation` | 停调 `RunPausePort` | 否（DDL+端口） |
| 2 生产接线 | 撤 `require-live-source=true` ⇒ 未接线可启动 | 三个 yml 键回退 | 否（纯配置+注释） |
| 3 归因回写 | 删 `@Component` ⇒ 回写 0 行 | 移除 `@Component` | 否（1 Bean+1 表） |
| 4 并发门禁 | 删 `FOR UPDATE` ⇒ 两次 APPROVED | **无生产可回滚** | **是（纯测试）** |
| 5 批量/SLA | ttl/parked 倒置 ⇒ 启动报错 | 端点下线、指标停用 | 否 |
| 6 哈希链 | 链写入失败 ⇒ 状态未 COMMIT | 旁路写链停用 | 否 |
| 7 终态语义 | 删 SSE 终帧 ⇒ 轮询不停止 | 前端分流回退（记合规债） | 否 |

---

## 八、其他有价值的横向对比（避免维度内自闭）

### 8.1 与本项目其他维度的耦合与相互约束（≥2 条）

**耦合 1｜HITL ↔ 维度 07（权限与护栏）：审批动作是权限矩阵的一个「消费者」，改动会双向传导**
- 依赖方向：HITL 的「谁有权批准」完全依赖维度 07 的 `AuthorizationService.Actions`（`APPROVAL_CONSUME` / `CONFIG_REQUEST` / `CONFIG_APPROVE` / `CONFIG_PUBLISH` / `CONFIG_ROLLBACK` / `UNKNOWN_DISPOSE`，E-14）。`AgentOverrides.parse()` 是唯一权限解析、fail-closed（D-10/E-15）。
- 约束：新增任何审批动作（如改进点 1 的 `RunPausePort.resume` 对应 `run:resume`、改进点 6 的 `AUDIT_READ`）都**必须同步 `AuthorizationService.java:169-170` 的动作矩阵 + `03_platform_roles.sql` 角色授权**（铁律 7 要求新表/新动作同步角色），否则 `requireAuthorized` 会因动作未登记而 fail-closed 拒所有人，或反之被漏配角色越权。
- 反向影响：维度 07 若调整 `APPROVER` 角色或 `authz_version` 递增逻辑，会直接影响审批有效性——`cap_approval` 绑定 `authz_version`，「当前版本 > 生成时版本 → 审批失效」（梳理报告第 13 章），即**授权撤销会让在途审批即时作废**。这是 A4 与权限联动的典型例子。

**耦合 2｜HITL ↔ 维度 03（状态持久化与可恢复性）：PARK 态就是可恢复性状态机的一段**
- 依赖方向：`WAITING_APPROVAL`（E-01）的持久化、检查点写入、`releaseLease`、`claimParkedRunForResume`（E-04/E-07）全部复用维度 03 的租约/fencing/检查点机制；DB 触发器 `enforce_run_state_transition()`（E-03）是维度 03 与 13 共用的状态机守卫。
- 约束：改进点 1 若走路径 B（新增 `PAUSED` 枚举），**必须与维度 03 的状态迁移表 + 触发器同步改**，否则触发器拒绝合法迁移（§7 改进点 1 已把此列为硬约束）。这解释了为何本报告坚持路径 A（复用态、不新增枚举）——改动一个状态会波及维度 03/11/04（SSE 帧）。

**耦合 3｜HITL ↔ 维度 10（可观测性/追踪/审计）：审批留痕是审计哈希链的输入**
- 依赖方向：改进点 6 直接把审批事件挂到维度 10 的哈希链；审批审计「同事务」（E-17/:267）与 `authz_decision_log` 的约束（铁律 2 禁 RETURNING）耦合。
- 约束：哈希链写入失败必须按铁律 11「事务毒化」处理，不得 fail-soft 后 COMMIT，否则出现「审批已生效但审计缺失」。

**耦合 4｜HITL ↔ 维度 09（成本预算）/ 05（工具）/ 11（可靠性）/ 04（流式）/ 12（部署）**
- 维度 09/05：审批门 `ToolInvocationGate` 与预算闸门共用「消费前重验」位置（E-11），恢复幂等（不重跑模型）直接保护预算不被二次扣（差异化 5）。
- 维度 11：`RunRecoverySweeper` 的 PARK 超时兜底（E-37）是维度 11 可靠性的一部分；UNKNOWN `MANUAL_DISPOSED`（D-12）是维度 11 对账链路的人工终态。
- 维度 04：SSE 需把「等待审批 / 拒绝 / 超时」表达为可停轮询的终帧（改进点 7，铁律 12/15）。
- 维度 12：`ApprovalWakeupPort` 是否与 worker 同进程装配（`WorkerExecutionConfig.java:807-813`）决定唤醒链路的跨进程可靠性假设——审批台与 worker 分部署时，同步 `wakeup`（E-13/:147-149 失败仅 warn）可能丢唤醒，需靠 sweeper 或消息补偿兜底。

### 8.2 与非 agent 领域成熟做法的对照（可借鉴范式）

| 领域范式 | 成熟做法 | 对本维度 HITL 的借鉴 | 本项目现状对照 |
|---|---|---|---|
| **金融 maker-checker / 四眼原则** | 制单与复核分离、双人独立留痕、金额分级审批阈值 | 动态 SoD（`approver_id <> requester_id`）+ 双保险（应用层 + DB CHECK）+ 按风险分级审批 | 已实现（D-8/差异化 3）：治理发布达此标准；运行期工具审批仍是单人 `approver-scope` |
| **NIST RBAC Static/Dynamic SoD** | 静态角色互斥（授权期）+ 动态会话互斥（运行期） | 「权限」与「身份」分离校验，不能只靠角色 | `AuthorizationService.java:103-105` 注释逐字点明，达标 |
| **数据库事务 + 行锁 CAS** | `SELECT ... FOR UPDATE` + 条件 UPDATE + 唯一约束防并发写坏 | 治理审批并发正确性（改进点 4）、发布指针 CAS 基线 | 已实现（E-17/E-26）；缺 decide 并发专项门禁 |
| **分布式系统 durable execution / checkpoint** | 状态持久化 + 幂等恢复 + 超时回收（saga / workflow engine） | PARK-RESUME 的「持久化挂起 + 不重跑 + sweeper 兜底」 | 已实现（D-1/D-2/D-11），专用场景比 LangGraph 更幂等 |
| **合规 GDPR Art.22 / EU AI Act Art.14** | meaningful human intervention、stop button、可质疑、防 automation bias | 审批必须「有效」而非橡皮图章；A5 有效性观测 | A1/A4 达标，A5 空白（改进点 5/6/7 补） |
| **变更管理 / ITIL CAB** | 变更申请→评审→批准→实施→回滚，全程不可变留痕 | 三步 `request→decide→apply` + 不可变发布 + 回滚即一次新申请 | 已实现（D-8/D-9），是本项目 HITL 最成熟的一块 |
| **消息队列幂等 / Idempotency-Key** | 重复投递不重复处理 | 改进点 7 的审批决策幂等键、避免 5xx 重试导致重复 decide | 待补（改进点 7） |
| **可打断系统 / off-switch（CIRL）** | 人类可安全中断、系统不规避关闭 | 通用运行期 off-switch / `HUMAN_EDIT` 暂停 | 弱项（§5.3 劣势 5，改进点 1 部分补） |

**最有价值的借鉴结论**：本项目治理发布链路本质上就是「金融级变更管理 + 数据库 CAS」在 agent 配置域的移植，成熟度甚至高于多数 agent 框架；而运行期交互链路才刚走到「分布式 durable execution 的专用门」这一步。**二者不是同一成熟度**，收官篇不建议用「我们 HITL 强」或「我们 HITL 弱」一刀切概括。

### 8.3 反向思考：若从头重做本维度，我们会怎么设计？（诚实指出与现状的取舍）

若抛开现有代码、从零设计 HITL，本报告会建议：

1. **先立「通用暂停原语」，再挂具体审批门**：把 `pause(reason, editableContext)` / `resume(ticket, statePatch)` 做成引擎级一等公民（类似 LangGraph `interrupt()`），`TOOL_APPROVAL` 只是 `reason` 的一个取值。→ **与现状取舍**：现状是先有专用工具审批门（M1/G2 修复）再泛化，导致 `WAITING_APPROVAL` 语义被工具审批绑死；改进点 1 是在既有事实上「补课」，比从零设计多一层兼容负担，但避免了改动已上线的 DB 触发器状态机（现实成本更低）。
2. **治理发布与运行期交互共用一套「审批事实」抽象**：一张统一的 `approval_fact` 表 + 一条触发器，用 `scope` 区分 GOVERNANCE/RUNTIME，而不是 `approval` / `cap_approval` / `cap_config_publish_request` 三套。→ **与现状取舍**：现状三套表是历史演进产物、各自约束不同（运行期绑参数摘要、治理期绑 CAS 基线），合并会牵动维度 03/07/10 大量代码，迁移成本高于收益，**不建议重构**。
3. **审批有效性（A5）在第一天就内建**：拒绝率、审批时长、批量上限、抽样复核、风险展示作为审批台的一等字段，而非事后补。→ **与现状取舍**：现状把力气全押在 A1/A4（正确性/可审计），A5 留白是**真实的取舍偏差**（不是做不到，是优先级），改进点 5/6/7 是补账。
4. **客户端终态契约与服务端同版本发布**：`REJECTED/EXPIRED/404/403` 停轮询语义写进 API 契约与前端隔离层，随审批台一起上线。→ **与现状取舍**：现状服务端终态已对（D-7），但契约/前端滞后，改进点 7 补齐。

**诚实的总体判断**：现状设计在「正确性、抗绕过、治理发布」上是深思熟虑且难以用从零设计显著超越的；真正值得从零重做的是「通用暂停原语 + A5 有效性内建」两处。但考虑到既有 DB 触发器状态机与不可变发布已被 12 份报告反复验证、迁移风险高，**本报告结论是「在既有事实上增量泛化」（改进点 1/5/6/7），而非推倒重来**——这与维度 12 报告 §8.3「不拆单体、不引入 Redis」的现实判断一致。

### 8.4 跨维度交界深挖（边界在哪里、改动会向谁传导）

> §8.1 已列四条耦合方向；本节选**四个最容易误判归属的交界面**做深挖，每个交界面给「共享的机制 / 归属分歧 / 越界后果 / 正确做法」。判据是 MEMORY「改任何维度前先读对应报告 4.5」——跨维度改动的真正风险不是本维度变红，而是**把别人的不变量改坏**。

#### 8.4.1 与维度 03（状态持久化与可恢复性）的交界：`WAITING_APPROVAL` 是 03 状态机的一段，不是 13 私有态

- **共享机制**：`WAITING_APPROVAL`（`RunState.java:54` + DDL `:236`）的持久化、检查点、`releaseLease`（`RunExecutionLoop.java:1143`）、`claimParkedRunForResume`（`JdbcRunClaimRepository.java:271-299` 的 `fencing_token+1` :280）**全部复用维度 03 的租约 / fencing / 检查点机制**；`enforce_run_state_transition()`（DDL `:462-484`，触发器 `:4837`）是 03 与 13 共用的状态机守卫。
- **归属分歧**：改进点 1 若走「路径 B：新增 `PAUSED` 枚举」，必须**同时**改 ① `RunState.java:72-79` 迁移表、② DDL `:462-484` 触发器、③ `RunRecoverySweeper.java:267` 的 `state='WAITING_APPROVAL'` 条件、④ 维度 04 的 SSE 帧语义——**一处漏改即触发器拒绝合法迁移**（`restrict_violation`）。这解释了 §7 改进点 1 为何坚持路径 A（复用态、不新增枚举）。
- **越界后果**：把 `pause_reason` 当作「新状态」写进 `run.state` 会直接撞触发器（DDL `:479-481` 只允许 `WAITING_APPROVAL→{RUNNING,CANCELLED,TIMED_OUT,FAILED}`）。**正确做法**：`pause_reason` 是 `WAITING_APPROVAL` 上的**列属性**（§7.8.1 的 DDL 片段），不是新的枚举值——这是「不新建 PAUSED 态」铁律的 DDL 层根据。

#### 8.4.2 与维度 10（可观测性与审计）的交界：审批留痕是哈希链的输入，不是另一套账

- **共享机制**：审批审计在 `JdbcConfigPublishRequestRepository.java:267-273` 与 `ApprovalController.java:139-141` 随业务状态**同事务**落库；`authz_decision_log`（维度 07/10）记录 `APPROVAL_CONSUME` / `CONFIG_APPROVE` 决策。
- **归属分歧**：改进点 6 要「关联哈希链」，但**不能新造一条链**——必须复用维度 10 既有 `audit_chain` 组件；哈希链写入失败按铁律 11 毒化事务（不得 fail-soft 后 COMMIT），否则出现「审批已生效但审计缺失」。
- **越界后果**：① 对 `authz_decision_log` 用 `INSERT…RETURNING` 会触发铁律 2 红线；② 把 `audit_record` / `erasure_certificate` 做成可物理删除会破坏 P0 验收报告 §2 第 1 项已修的防篡改守卫（`AuditTamperProofGuard` 的 `GUARDED_TABLES` 与 `guardedTableCount()` fail-fast）；③ 把 `approver_id` 从请求体取会绕过 D-6「审批人只来自令牌」。**正确做法**：审批事件作为 `audit_chain` 的一类 `event_type` 接入，事务边界沿用维度 10，不新建账本、不删历史。

#### 8.4.3 与维度 11（可靠性与故障处理）的交界：PARK 超时是可靠性的收敛路径，不是 13 的自主清理

- **共享机制**：`RunRecoverySweeper.sweep:173` / `failStaleParkedRuns:267`（`@Scheduled` 30s）+ `application.yml:297` `parked-timeout-seconds=1800s` 属维度 11 的恢复链路；`unhealthyReason()` / `healthy()`（`RunRecoverySweeper.java:139-140/:155-156`）是维度 11 已登记的「无消费方」缺口（11 号报告附录 D-46）。
- **归属分歧**：`APPROVAL_TIMEOUT` 看似 HITL 概念，但其**收敛动作（FAILED 终态 + 无副作用）由维度 11 的扫描器执行**，13 只提供「为什么会有 PARK 态」的语义。把超时收敛逻辑写进审批台（如定时 Cancel 审批行）会绕开 `enforce_run_state_transition`（DDL `:462-484`）与 fencing 校验。
- **越界后果**：① `parked-timeout-seconds < ttl`（`:297` vs `:563`）会产生「人还没批 Run 先失败」——这是两个维度配置的交叉约束，单看任一报告都发现不了；② 关闭 `agent.worker.recovery.enabled` 或租户端口降级（`require-tenant-port=false`）会让 PARK 孤儿永久停留（AP6 复发）。**正确做法**：超时作为两维度**联合门禁**（§7.8.5 的启动一致性校验 `assert parkedTimeout > ttl`），告警挂维度 11 的健康事实出口，13 不自建清理线程。

#### 8.4.4 与维度 04（SSE）/ 07（权限）的交界：终态语义与审批动作的双向传导

- **与维度 04**：Run 进入 `WAITING_APPROVAL` 推「等待审批」帧，`REJECTED/EXPIRED/TIMED_OUT/FAILED` 推 `done` 终帧带 `terminal_reason`（铁律 12）。服务端 `ApprovalController.java:157-165` 已给 404/409 显式终态，但前端 `GovernancePages.tsx:89` 仍用「请稍后重试」文案——**终态契约是 04 与 13 的共同产物，任何一方单独成文都会漏一半**（§4.6 链 4、§7 改进点 7）。
- **与维度 07**：`AuthorizationService.Actions`（`APPROVAL_CONSUME` / `CONFIG_REQUEST/APPROVE/PUBLISH/ROLLBACK`，`AuthorizationService.java:99-130` + 动作矩阵 `:169-170`）是 HITL 的权限消费者。改进点 1/6 新增 `run:resume` / `AUDIT_READ` 动作时，**必须同步动作矩阵 + `03_platform_roles.sql`**（铁律 7），否则 `requireAuthorized` 因动作未登记而 fail-closed 拒所有人，或漏配角色越权。
- **越界后果**：① 把「审批权限」塞进业务角色判断而不走 `AuthorizationService`，会使 `authz_version` 递增时的审批失效联动（§8.1 耦合 1）断链；② 前端把 403 当重试（铁律 15）会烧掉 SSE 配额并让用户对着无权资源死等。**正确做法**：审批动作统一在 07 登记、在 13 消费；终态语义统一在 04 成文、在 13 服务端保证——**两个维度各持一半，缺一即失真**。

**交界判读总则**：本维度四个交界面共享一个模式——**「机制在某个维度实现，但正确性依赖另一个维度的不变量」**。因此 §7 改进点凡触及状态枚举（→03）、审计写入（→10）、超时收敛（→11）、权限动作（→07）、SSE 终帧（→04），都必须**成对验收**（改 13 时同时跑对方维度的守卫），否则会出现「本维度全绿、跨维度静默失真」——这正是 MEMORY 新铁律「运行期可达性审计」在跨维度场景的投影。

---

## 九、对产品演进的启发

### 9.1 本维度支撑的产品能力（用户能感知什么）

- **教育场景家长/老师审批**：孩子让 agent 执行「发外部消息 / 改外部资料」等高风险动作前，`approver-scope: PARENT_OR_TEACHER` 的人收到审批待办、看到风险等级与参数、决定通过或拒绝（改进点 2/5 落地后可用）。
- **Agent 定义/模型上线可追责**：管理员改提示词、换模型、调生成参数，必须走双人复核 + 发布审批，「线上跑的是哪一版、谁批的」可一键举证（`modelRef` 固化 + `cap_config_pointer`，C6）。
- **拒绝/超时有明确交代**：审批被拒或过期，用户看到的是「已拒绝/已失效」终态与原因，而非「一直转圈」或「系统错误请重试」（铁律 15，改进点 7）。
- **运行中人工接管（演进后）**：坐席/审核员在 agent 跑到一半时可暂停、改一段草稿、再让它继续（改进点 1 的 `HUMAN_EDIT`）。
- **人工复核沉淀为改进**：复核归因（PROMPT/CONFIG/MODEL）回写，驱动提示词/配置/模型迭代待办（改进点 3）。

### 9.2 演进路线图（近/中/远三期，每期产出物与验收标准）

| 期 | 主题 | 产出物 | 验收标准 |
|---|---|---|---|
| **近期（首期）** | 运行期 HITL 可用 + 客户端语义正确 | 改进点 1（路径 A 泛化暂停）、2（live-source 接线）、7（终态契约 + 幂等键 + SSE 终帧） | 高风险工具「触发→PENDING→审批→恢复执行」全链路可跑；终态误重试率=0；`WRITE_EXTERNAL` 不再默认全拒；3 项各自撤点反证门禁变红/绿 |
| **中期（二期）** | 有效性 + 并发 + 复核闭环 + 可追溯 | 改进点 3（归因回写）、4（decide 并发门禁）、5（批量/SLA/告警）、6（哈希链挂链） | 并发 decide 恒 1 成功；`reject_ratio`/`pending_age_p95` 可告警；PROMPT/CONFIG/MODEL 回写率 100%；审批事件链可校验；橡皮图章可被发现 |
| **远期（三期）** | 通用 HITL 一等公民 + 合规举证 | 通用 `OFF_SWITCH` 暂停、审批有效性审计抽查产品化、GDPR/EU AI Act 合规举证报告、跨进程唤醒补偿（维度 12） | 运行中任意时刻可安全打断；`reject_ratio≈0` 审计抽查闭环；合规举证可导出；审批台与 worker 分部署下唤醒不丢 |

**统一更正口径的落地**：三期路线图承接 §4.5 的 7 处不一致——首期把「运行期暂停-恢复」从「既有报告未记载」变成「产品可感知能力」，中期把「治理审批并发缺验证」「ReviewAttributionHook 未闭环」两项 MEMORY 短板转成可回归门禁，最终解除 MEMORY.md:48「不具备规模化发布条件」中与 HITL 相关的阻塞项（多租户灰度 / 预算闸门边界属维度 02/09，本报告不越界认领）。

### 9.3 风险清单（做这一步可能踩的坑，关联本项目已知铁律）

| 风险 | 触发场景 | 关联铁律 | 缓解 |
|---|---|---|---|
| 状态编辑触碰已发布定义 | 改进点 1 的 `statePatch` 被误用于改 `cap_agent_def` | D-9 不可变发布、`forbid_published_def_mutation` | 触发器 + `resume` 字段白名单 + 反向门禁 |
| 新增枚举/迁移与触发器不同步 | 改进点 1 若误走路径 B | 铁律 2、E-03 触发器 | 坚持路径 A（复用态）；路径 B 须四处同步 |
| 事务内 SQL 失败被 fail-soft 后 COMMIT | 改进点 6 哈希链写入失败 | 铁律 11（POISONED） | 同事务标记、失败即毒化、审批不生效 |
| 终态被伪装成临时故障 | 改进点 7 客户端把 4xx 当重试 | 铁律 15 | 契约表 + 前端隔离 + 撤点反证 |
| 静态守卫 ≠ 运行时成立 | 改进点 4/5 的并发、指标门禁 | 铁律 17 | 真实起事务/真实交互驱动，禁用 H2 假锁 |
| RLS GUC 未在事务内绑定 | 改进点 3/6 新表/新端点 | 铁律 1 | 业务 SQL 前绑定、验隔离用 `app_agent`(NOBYPASSRLS) |
| 新动作/新表未同步角色授权 | 改进点 1/3/6 新增动作与表 | 铁律 7 | 同步 `03_platform_roles.sql` + SchemaDriftIT |
| fail-closed 被降级为放行 | 改进点 2 策略配错 | 铁律 8、T4 | 空策略仍 `FAIL_CLOSED`、`toolsRequiringApproval` 启动自检 |
| 批量审批放橡皮图章 | 改进点 5 一键全通过 | GDPR Art.22 / AP1 | 风险展示 + 缺省拒绝 + `reject_ratio` 告警 + 抽样复核 |
| 跨进程唤醒丢失 | 审批台与 worker 分部署 | 维度 12、E-13 同步 wakeup 失败仅 warn | sweeper 兜底 + 消息补偿（远期） |

### 9.4 一句话结论

**本项目的 HITL 是「治理发布审批做到 L3、运行期工具审批做到有专用 PARK/RESUME 闭环但默认未接线、任意点交互与审批有效性仍是空白」的偏科系统——它的强（DB 触发器状态机 + 双人复核 SoD + 不可变发布）与弱（A2 灵活性 / A5 有效性）来自同一个「治理中心化」路线选择；收官篇最重要的贡献不是再列一遍优势，而是用源码实证把既有报告「缺运行期暂停-恢复」的判断精确化为「有专用闭环、缺泛化抽象」，并据此把改进点从「新增 PAUSED 态」修正为「在 `WAITING_APPROVAL` 上增量泛化」，避免推倒重来地破坏已被 12 份报告验证的状态机。**

---

## 附录

### 附录 A · 证据索引（本报告引用的全部本项目文件:行号清单）

> 按文件归并去重，与 §4.3 的 E-01～E-40 对应。`...` 表示该模块内 `src/main/java/cn/studykid/multiagent/` 前缀。

| 文件 | 行号 | 对应编号 |
|---|---|---|
| `platform/agent-core/.../core/run/RunState.java` | 43-54, 72-79 | E-01/E-02 |
| `platform/agent-worker/.../execute/RunExecutionLoop.java` | 1115-1133, 2045, 2057, 2084-2093, 2171 | E-04/E-05 |
| `platform/agent-worker/.../execute/ApprovalDecidedWakeupConsumer.java` | 27, 48, 82, 101, 113, 132, 135 | E-06 |
| `platform/agent-worker/.../execute/ToolInvocationGate.java` | 53, 173-183, 1307-1361, 1590, 1605-1606, 1944 | E-11 |
| `platform/agent-ports/.../persistence/ApprovalPort.java` | `ApprovalOutcome` 枚举 / `consume` / `request` 默认空 / `grant` | E-10 |
| `platform/agent-ports/.../persistence/ApprovalWakeupPort.java` | `Result` 枚举 / `wakeup` | E-06 配套 |
| `platform/agent-ports/.../persistence/RunClaimPort.java` | `claimParkedRunForResume` / `releaseLease` / `failStaleParkedRuns` | E-07/E-08 |
| `platform/agent-ports/.../security/AuthorizationService.java` | 31, 51, 56, 76, 78, 99, 103-105, 107, 122, 130, 169-170 | E-14 |
| `platform/agent-ports/.../quality/ReviewAttributionHook.java` | 23, 29, 41 | E-32 |
| `platform/agent-application/.../approval/RiskBasedApprovalPolicy.java` | 43, 71, 87, 106-110, 128, 169, 175, 189, 210, 214 | E-12 |
| `platform/agent-application/.../cap/AgentOverrides.java` | 23-29, 39, 49, 57, 68-96 | E-15 |
| `platform/agent-application/.../cap/ConfigPublishWorkflowService.java` | 43-44, 165, 192, 237, 278, 300, 324, 344-366, 504, 520, 528, 616 | E-16 |
| `platform/agent-application/.../cap/ConfigPublishService.java` | 三步发布服务（本轮输出过大未逐行展开，结论由 `ConfigPublishWorkflowService` 与 IT 交叉支撑） | §4.1 链路 B |
| `platform/agent-persistence/.../JdbcApprovalRepository.java` | 38, 64, 121, 181, 224, 248, 308, 387, 416 | E-09 |
| `platform/agent-persistence/.../JdbcRunClaimRepository.java` | 53, 266, 271, 285, 576, 602 | E-07/E-08 |
| `platform/agent-persistence/.../JdbcConfigPublishRequestRepository.java` | 41, 67, 140, 241, 251, 253-254, 257, 267, 283, 317-319, 351, 356, 372-376 | E-17 |
| `platform/agent-persistence/.../cap/JdbcConfigPublishTransaction.java` | `execute` 同事务 / RLS GUC / PLATFORM 作用域 | E-18 |
| `platform/agent-persistence/.../JdbcReviewTaskRepository.java` | 50, 60-62, 75, 82, 88, 122, 132-139, 149, 162, 197, 281 | E-33 |
| `platform/agent-api/.../api/governance/ApprovalController.java` | 32, 52, 67-91, 94, 124, 131, 139-149, 158-163, 175-184 | E-13 |
| `platform/platform-bootstrap/.../config/ApplicationConfiguration.java` | 292-302 | E-34 |
| `platform/platform-bootstrap/.../config/ApprovalReadinessGuard.java` | 23, 35-37, 44-55, 60, 67, 73, 80-81, 93 | E-35 |
| `platform/platform-bootstrap/.../config/WorkerExecutionConfig.java` | 346, 373, 377, 446, 469-470, 807-813 | E-36 |
| `platform/platform-bootstrap/.../worker/RunRecoverySweeper.java` | 52, 104, 116, 127, 173, 265-270 | E-37 |
| `platform/platform-bootstrap/src/main/resources/application.yml` | 277, 294-297, 554-580, 685 | E-38 |
| `deploy/sql/platform/01_platform_ddl.sql` | 109, 112, 125, 132, 140, 247, 304, 307-331, 335, 338-373, 450-475, 527, 571, 574, 609, 1456-1471, 1640, 1664-1667, 1695-1722, 1826-1848, 1855, 1873, 1936, 3003, 3595-3599, 4224, 4280, 4298 | E-03/E-19~E-31 |
| `platform/agent-persistence/src/test/.../cap/ConfigPublishIT.java` | 7-9, 58, 139, 242, 273 | E-39 |
| `platform/agent-persistence/src/test/.../cap/ConfigPublishManagementIT.java` | 45, 112-128, 283-311, 368-369, 391, 420 | E-40 |
| `platform/agent-worker/src/test/.../ApprovalDecidedWakeupConsumerTest.java` / `RunExecutionLoopTest.java` / `ToolInvocationGateApprovalRequestTest.java` | 存在对应守卫（据文件名，未逐行验证断言内容） | §4.5 反证口径 |
| `docs/能力维度实现方式梳理报告.md` | 第 13 章（194-205）、第 1 章（17） | 核心素材 |
| `docs/Agent平台竞品对比报告_2026-11-15.md` | 维度 13（391-420）、440、459、来源清单 | 竞品素材 |
| `.workbuddy/memory/MEMORY.md` | 48-49 | 铁律与已知缺口 |
| `docs/能力维度深度报告/README-撰写规范.md` | 全文（九章结构、铁律速查 1-26） | 格式契约 |

### 附录 B · 竞品官方来源清单（全部 URL）与理论/法规出处

**竞品官方 URL（≥7 个方案）**

| 方案 | 官方 URL | 抓取状态 |
|---|---|---|
| LangGraph | `https://docs.langchain.com/oss/python/langgraph/interrupts` | 已抓取核实（本轮/竞品报告登记） |
| LangGraph | `https://docs.langchain.com/oss/python/langgraph/persistence` | 竞品报告登记 |
| LangGraph | `https://docs.langchain.com/oss/python/langgraph/graph-api` | 竞品报告登记（`Command`） |
| OpenAI Agents SDK | `https://openai.github.io/openai-agents-python/guardrails/` | 已抓取核实（pending approval interruption / FunctionTool 需批准） |
| OpenAI Agents SDK | `https://openai.github.io/openai-agents-python/` / `.../handoffs/` / JS `https://openai.github.io/openai-agents-js/` | 竞品报告登记 |
| Anthropic Claude Agent SDK | `https://platform.claude.com/docs/en/agent-sdk/overview` / `.../hooks` | 官方检索核实（canUseTool / PreToolUse / PermissionRequest）；旧 `https://docs.anthropic.com/en/api/agent-sdk` 会重定向 |
| Google ADK | `https://adk.dev/tools-custom/confirmation/` | 已抓取核实（request_confirmation / require_confirmation / FunctionResponse / /run /run_sse / invocation_id） |
| Microsoft AutoGen | `https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/selector-group-chat.html` | 竞品报告登记；归档状态见 `https://learn.microsoft.com/en-us/agent-framework/overview` |
| CrewAI | `https://docs.crewai.com/en/learn/human-in-the-loop` | 已核实（@human_feedback / webhook HITL / Pending Human Input / /resume）；`https://docs.crewai.com/en/concepts/flows` 竞品报告登记 |
| Agno | `https://docs.agno.com/hitl/overview` | 已核实（active_requirements / needs_confirmation / needs_user_input / needs_external_execution / confirm() / reject() / continue_run()） |

**理论 / 法规 / 论文出处**

| 类别 | 出处 | 状态 |
|---|---|---|
| 法规 | EU AI Act Art.14, Regulation (EU) 2024/1689, `https://eur-lex.europa.eu/eli/reg/2024/1689/oj` | 条文要点（automation bias / stop button / 双人复核）已核对；本轮未逐页重抓 EUR-Lex 全文 |
| 法规 | GDPR Art.22, Regulation (EU) 2016/679, `https://eur-lex.europa.eu/eli/reg/2016/679/oj` | 条文要点已核对；meaningful human intervention 为 EDPB/监管语境归纳 |
| 标准 | NIST AI RMF 1.0, NIST AI 100-1 (2023), `https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.100-1.pdf` | GOVERN/MAP/MEASURE/MANAGE 治理要件归纳 |
| 标准 | NIST RBAC SoD, ANSI INCITS 359-2004; Ferraiolo/Kuhn/Chandramouli, *Role-Based Access Control*, 2nd ed., Artech House, 2007; `https://csrc.nist.gov/glossary/term/separation_of_duties` | Static/Dynamic SoD 定义为标准内容 |
| 专著 | Ben Shneiderman, *Human-Centered AI*, Oxford University Press, 2022, ISBN 978-0-19-284529-0 | 真实专著 |
| 论文 | T. B. Sheridan & W. L. Verplank, *Human and Computer Control of Undersea Teleoperators*, MIT Man-Machine Systems Laboratory Tech. Report, 1978 | 10 级自动化谱系源头 |
| 论文 | R. Parasuraman, T. B. Sheridan & C. D. Wickens, *A Model for Types and Levels of Human Interaction with Automation*, IEEE TSMC-Part A, 30(3), 286–297, 2000 | 四阶段模型 |
| 论文 | D. Hadfield-Menell et al., *Cooperative Inverse Reinforcement Learning*, NIPS 2016, arXiv:1606.03137 | CIRL |
| 论文 | D. Hadfield-Menell, A. Dragan, P. Abbeel, S. Russell, *The Off-Switch Game*, IJCAI 2017, arXiv:1611.08219 | off-switch game（与用户素材「2016 一篇」拆分，见第五节） |
| 论文 | R. Parasuraman & V. Riley, *Humans and Automation: Use, Misuse, Disuse, Abuse*, Human Factors, 39(2), 230–253, 1997 | automation bias |
| 论文/归纳 | four-eyes / maker-checker / alert fatigue / review fatigue | **未能核实单一权威官方出处，正文（§2.1 框架 3/6）已标注「按公开工程实践归纳」，不作事实依据** |

### 附录 C · 术语表（该维度关键术语中英对照）

| 中文 | 英文 / 缩写 | 释义 |
|---|---|---|
| 人在环 | Human-in-the-loop (HITL) | 人在决策闭环内逐次批准 |
| 人在环上 | Human-on-the-loop (HOTL) | 人监督、可介入但不逐次批准 |
| 人主导 | Human-in-command (HIC) | 人设目标与约束、系统自主执行 |
| 全自动 / 人在环外 | Human-out-of-the-loop | 无人工介入 |
| 有意义的人类控制 | Meaningful human control / intervention | GDPR Art.22 要求的非橡皮图章式人工干预 |
| 四眼原则 / 双人复核 | Four-eyes principle / two-person rule | 申请人 ≠ 审批人，第二双眼睛独立复核 |
| 职责分离 | Separation of Duties (SoD) | 静态(角色互斥)/动态(会话互斥)；maker-checker 制单-复核 |
| 审批门 | Approval gate | 高风险动作执行前的人工批准关卡 |
| 挂起 / 恢复 | PARK / RESUME | 运行期持久化暂停 + 异步唤醒恢复 |
| 等待审批态 | `WAITING_APPROVAL` | 本项目事实上的 PAUSED 态（非终态） |
| 中断 / 恢复命令 | `interrupt()` / `Command(resume=...)` | LangGraph durable HITL 原语 |
| 检查点 | Checkpointer / checkpoint | 图状态按 thread_id 持久化 |
| 自动化偏见 | Automation bias | 人过度信任系统输出、降低独立核验 |
| 告警疲劳 / 复核疲劳 | Alert fatigue / review fatigue | 高频低信息审批诱发「一律通过」 |
| 橡皮图章 / 形式干预 | Rubber stamp / token gesture | 形式有人工、实质无核验 |
| 可打断性 / 关闭开关 | Interruptibility / off-switch | 人能安全中断系统；CIRL / off-switch game |
| 不可变发布 | Immutable release | 已发布定义不可改写，仅 PUBLISHED→DEPRECATED |
| 模型引用 | `modelRef` / `cap_agent_def.model_ref` | 治理期固化、运行期钉死、绝不回落默认模型 |
| 发布指针 | `cap_config_pointer` | 当前版本只由指针指向的 PUBLISHED release 决定 |
| 双人复核约束 | `two_person_ck` | DB CHECK：`approver_id <> requester_id` |
| 状态机守卫 | `enforce_*_state_transition()` | DB 触发器：状态只能前进、终态不可迁出 |
| 归因回写钩子 | `ReviewAttributionHook` | 人工复核结论回写端口（当前生产 NoOp） |
| 人工处置终态 | `MANUAL_DISPOSED` | UNKNOWN 对账后由人裁定、不可逆 |
| 审批超时 | `APPROVAL_TIMEOUT` | 孤儿 PARK 收敛为 FAILED 的错误码 |
| 终态不伪装（铁律 15） | Terminal-error honesty | 404/403/401 显式终态 + 停轮询 + 不给重试 |

### 附录 D：源码证据索引（路径 + 行号 + 调用链）

> 把正文散落的源码结论收敛为可逐条复核的索引。列：编号 / 源码锚点 / 结论 / 运行期可达性 / 复核方式。
> **行号口径**：全部为本轮（2026-11-16）实际 `Read`/`Grep` 核实的当前值。正文与附录 A 中的 `01_platform_ddl.sql:1873`（two_person_ck）、`:450-475`（run 状态机触发器）、`:1826-1848`（指针）等为写作时快照，**已漂移**；因「零删改」约束原文锚点原样保留，**以本表为准**。
> **四问口径**（对齐 MEMORY 新铁律「运行期可达性审计」）：① 装配了吗（Bean/调用方/路由存在）② 触发了吗（运行期真的执行）③ 写入了吗（数据真落库）④ 被看见了吗（有指标/告警/守卫消费）。四问全过才算「运行期可达」；「仅静态存在、无调用方、无消费方」是明确判定，不是省略。

| # | 源码锚点 | 结论 | 运行期可达性 | 复核方式 |
|---|---|---|---|---|
| D-01 | `platform/agent-core/src/main/java/cn/studykid/multiagent/core/run/RunState.java:54` | `WAITING_APPROVAL` 枚举定义，注释「M1/G2 修复核心 · PARK 态、非终态」——事实上的 PAUSED 态，既有报告「缺 PAUSED 态」判定**已被复核纠偏** | ①✅（枚举被 `JdbcRunClaimRepository` 等引用）②✅（PARK 写入）③✅（落 `run.state`）④✅（迁移表静态守卫） | `grep -n "WAITING_APPROVAL" platform/agent-core/src/main/java/cn/studykid/multiagent/core/run/RunState.java` |
| D-02 | 同上 `:72-79` | 迁移表：`RUNNING→{…,WAITING_APPROVAL}`（`:73`）、`WAITING_APPROVAL→{RUNNING,CANCELLED,TIMED_OUT,FAILED}`（`:75`），终态迁出空集（`:76-79`） | ①✅ ②✅ ③✅ ④✅ | `sed -n '68,81p' platform/agent-core/src/main/java/cn/studykid/multiagent/core/run/RunState.java` |
| D-03 | `deploy/sql/platform/01_platform_ddl.sql:236`、`:256` | `run_state` 枚举含 `WAITING_APPROVAL`（`:236`）与 `tool_operation_state` 含 `MANUAL_DISPOSED`（`:256`） | ①✅（DDL 迁移建枚举）②✅ ③✅ ④✅ | `grep -n "WAITING_APPROVAL\|MANUAL_DISPOSED" deploy/sql/platform/01_platform_ddl.sql \| head` |
| D-04 | 同上 `:462-484`、`:4837` | `enforce_run_state_transition()` 三组合法跃迁（`:466-477`），其余 `RAISE … ERRCODE='restrict_violation'`（`:479-481`）；`WAITING_APPROVAL→SUCCEEDED` 不在集合内；触发器挂 `run`（`:4837`） | ①✅ ②✅（每次 UPDATE）③✅ ④✅（抛错即暴露） | `sed -n '462,484p' deploy/sql/platform/01_platform_ddl.sql` |
| D-05 | `platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java:1135-1143` | PARK 路径：先 `commitResult(…,"WAITING_APPROVAL")`（`:1135-1136`），成功后才 `appendEvent(RUN_PARKED)`（`:1139-1140`）与 `releaseLease`（`:1143`）——让出 worker 与租约；fencing 失败不释放（fail-closed） | ①✅（`WorkerExecutionConfig` 注入）②✅ ③✅ ④⚠️ 无「挂起时长」指标 | `sed -n '1133,1145p' platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java` |
| D-06 | 同上 `:2073-2109` | `resume()` 入口（`:2073`）；恢复后仍判挂起 → 重新 PARK（`:2100-2109`），不重跑模型 | ①✅（`ApprovalDecidedWakeupConsumer:132` 调用）②✅ ③✅ ④✅ | `sed -n '2073,2110p' platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java` |
| D-07 | `platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ApprovalDecidedWakeupConsumer.java:82-136` | `wakeup()` 全链路：`approvals.find:84` → `claimParkedRunForResume:101` → `ledger.findInvocation:113` → 重建 `ToolCallRequest:128-129` → `loop.resume:132` → `RESUMED:135`；每一处 fail-closed（NOT_FOUND / NOT_DECIDED / NOT_PARKED / INVOCATION_MISSING） | ①✅（`WorkerExecutionConfig.java:807-813` 装配）②✅ ③✅ ④✅（结果枚举可观测，无 Gauge） | `sed -n '82,136p' platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ApprovalDecidedWakeupConsumer.java` |
| D-08 | 同上 `:114-124` | 原始调用反查不到 ⇒ **绝不臆造参数**，`commitResult(FAILED,"RESUME_INVOCATION_NOT_FOUND")`（`:120-123`），工具未执行、无副作用 | ①✅ ②✅（异常路径）③✅ ④✅（error code 落库） | `grep -n "RESUME_INVOCATION_NOT_FOUND" platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ApprovalDecidedWakeupConsumer.java` |
| D-09 | `platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunClaimRepository.java:271-299` | `claimParkedRunForResume`：`UPDATE run SET state='RUNNING', fencing_token+1… WHERE state='WAITING_APPROVAL' AND fencing_token=?`（`:283-286`）；并发唤醒只有一个赢家，输家 empty 丢弃不重试 | ①✅ ②✅ ③✅ ④✅ | `sed -n '271,299p' platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunClaimRepository.java` |
| D-10 | 同上 `:589-602` | `failStaleParkedRuns`：`WHERE … state='WAITING_APPROVAL'`（`:602`）+ 到期条件 → 收敛 `FAILED`（APPROVAL_TIMEOUT）——PARK 孤儿兜底 | ①✅（`RunRecoverySweeper:267` 调用）②✅ ③✅ ④⚠️ 无超时 Gauge | `sed -n '589,603p' platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunClaimRepository.java` |
| D-11 | `platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/worker/RunRecoverySweeper.java:172-173`、`:267` | `@Scheduled(fixedDelayString="…30}000")` + `sweep()`（`:173`）每 30s 调用 `failStaleParkedRuns`（`:267`）；执行链前三问全过 | ①✅ ②✅ ③✅ ④❌（见 D-12） | `grep -n "@Scheduled\|failStaleParkedRuns\|public void sweep" platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/worker/RunRecoverySweeper.java` |
| D-12 | 同上 `:139-140`、`:155-156`、`:195-201` | `unhealthyReason()` / `healthy()` 健康事实类注释自承「日志首次+每40轮一次、同文案会被滤掉≈静默」；**全仓 main 侧零消费**（无 HealthIndicator） | ①⚠️ 有方法 ②⚠️ 置位 ③N/A ④❌ **不可被告警**（与 11 号报告 D-46 同族） | `grep -rn "unhealthyReason\|\.healthy()" platform --include=*.java \| grep -v "/test/"` |
| D-13 | `platform/platform-bootstrap/src/main/resources/application.yml:297`、`:563` | `parked-timeout-seconds=1800s`（`:297`）必须 > `ttl=15m`（`:563`），否则「人还没批 Run 先失败」；两键无运行时一致性校验（D-11 代价） | ①✅ ②✅（`@Value` 注入）③✅ ④⚠️ 仅注释约束 | `grep -n "parked-timeout-seconds\|ttl:" platform/platform-bootstrap/src/main/resources/application.yml` |
| D-14 | 同上 `:554-586` | `agent.approval` 全段：`required-by-risk`（READ 免 / WRITE_PROFILE 可选 / WRITE_EXTERNAL 必审，`:558-561`）、`ttl:15m`（`:563`）、`approver-scope: PARENT_OR_TEACHER`（`:565`）、`live-source-enabled:false`（`:569`）、`require-live-source:false`（`:571`）、`source.mode:none`（`:580`）、`rules: []`（`:585`） | ①✅ ②N/A（配置）③✅（启动注入）④✅（注释明示 fail-closed） | `sed -n '554,586p' platform/platform-bootstrap/src/main/resources/application.yml` |
| D-15 | `platform/agent-application/src/main/java/cn/studykid/multiagent/application/approval/RiskBasedApprovalPolicy.java:75`、`:87`、`:106-110` | 风险等级未知 ⇒ `ApprovalDecision.required("风险等级未知，按最严处理")`（`:75`）；`maxWith` 取较严（`:87`）；未配置一律 `required`（`:106-110`，fail-closed） | ①✅（`WorkerExecutionConfig.java:295/:313` 构造）②N/A ③✅ ④✅ | `sed -n '70,112p' platform/agent-application/src/main/java/cn/studykid/multiagent/application/approval/RiskBasedApprovalPolicy.java` |
| D-16 | 同上 `:189`、`:205-214` | `PolicyEngineApprovalSource`：策略缺失/空 ⇒ `Verdict.FAIL_CLOSED`（`:210/:214`）；默认源恒 `FAIL_CLOSED`（`:175`） | ①✅（`WorkerExecutionConfig.java:373/:377` 装配）②N/A ③✅ ④✅ | `sed -n '185,216p' platform/agent-application/src/main/java/cn/studykid/multiagent/application/approval/RiskBasedApprovalPolicy.java` |
| D-17 | `platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApprovalReadinessGuard.java:60-81`、`:93` | 未接线时 `log.error("运行时人工审批未接线…")`（`:73-77`）但 `require-live-source=false` 不阻断启动（`:80-81`）；`toolsRequiringApproval():93` 是纯判定便于单测 | ①✅ ②N/A ③✅（启动期）④⚠️ 只 log 不阻断 | `sed -n '60,95p' platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApprovalReadinessGuard.java` |
| D-18 | 同上 `:22-23` | 类注释称「`agent-api` 的 `ApprovalController` 操作的是另一条链路（能力/配置发布域）」——与 `ApprovalController.java:124-166` 运行期工具审批台实现不符，**过时注释**（§4.5 不一致 4，文档级非功能缺陷） | ①N/A（注释）②N/A ③N/A ④❌（误导维护者） | `sed -n '20,25p' platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApprovalReadinessGuard.java` |
| D-19 | `platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/WorkerExecutionConfig.java:807-813` | `approvalWakeupPort()` 装配 `ApprovalDecidedWakeupConsumer`（`:813`），注入 `approvals/claimPort/ledger/loop`；与 worker 同进程（跨进程部署改变可靠性假设，维度 12） | ①✅ ②N/A ③✅（启动期）④✅（log.info 装配日志） | `sed -n '805,815p' platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/WorkerExecutionConfig.java` |
| D-20 | `platform/agent-api/src/main/java/cn/studykid/multiagent/api/governance/ApprovalController.java:71-80`、`:124-166` | 生产构造用 `ObjectProvider<ApprovalWakeupPort>.getIfAvailable()`（`:76-79`，可缺失）；`POST /api/approvals/{id}/decision`（`:124`）先 `requireAuthorized(APPROVAL_CONSUME)`（`:130-131`）、`approvals.decide`（`:139-141`，审批人来自令牌 `currentPrincipal.principalId()`）、成功后同步 `wakeup`（`:147-149`） | ①✅（`@RestController`）②✅（审批事实落库）③✅ ④✅ | `sed -n '124,167p' platform/agent-api/src/main/java/cn/studykid/multiagent/api/governance/ApprovalController.java` |
| D-21 | 同上 `:147-153` | `wakeup` 失败仅 `log.warn`、**不回滚决定**（「人批了就是批了」）；崩溃窗口靠 sweeper 兜底；跨进程部署下同步 wakeup 可能丢唤醒（维度 12） | ①✅ ②✅ ③✅ ④⚠️ 失败只 WARN、无告警通道 | `sed -n '142,154p' platform/agent-api/src/main/java/cn/studykid/multiagent/api/governance/ApprovalController.java` |
| D-22 | 同上 `:157-165` | 404 **不区分**不存在与无权限（`:158-160`，防存在性探测）；过期/已决定 → 409 Conflict（`:162-165`）——铁律 15 的显式终态 | ①✅ ②N/A ③✅ ④✅ | `sed -n '156,167p' platform/agent-api/src/main/java/cn/studykid/multiagent/api/governance/ApprovalController.java` |
| D-23 | 同上 `:175-185` | `DecisionRequest.approve()`：`"APPROVE".equalsIgnoreCase(...)`（`:180`），缺省/大小写不符/任意其他值一律 DENY（fail-closed） | ①✅ ②N/A ③✅ ④✅ | `sed -n '175,186p' platform/agent-api/src/main/java/cn/studykid/multiagent/api/governance/ApprovalController.java` |
| D-24 | 同上 `:197-218` | `ApprovalItemResponse` 含 `argumentDigest` / `targetResource` / `expiresAt` / `approverScope` / `expired`（服务端计算）——**FR-014 已落地**，「审批台不展示参数摘要」判定**已被复核纠偏**（仅 `risk_level` 未展示仍成立） | ①✅（前端消费）②N/A ③✅ ④✅ | `sed -n '191,219p' platform/agent-api/src/main/java/cn/studykid/multiagent/api/governance/ApprovalController.java` |
| D-25 | `frontend/src/pages/governance/GovernancePages.tsx:75-94`、`:179-188`、`:242-263` | 审批页：展示参数摘要/目标资源/有效期（`:179-188`）、「批准」主按钮+「拒绝」次按钮（`:242-260`）、过期项只给拒绝（`:263`）；`catch`（`:87-90`）对 4xx 文案「操作失败，请稍后重试」——**铁律 15 未闭合** | ①✅（`App.tsx:194` 路由）②N/A ③✅ ④⚠️ 终态文案伪装临时故障 | `grep -n "请稍后重试\|argumentDigest\|riskLevel" frontend/src/pages/governance/GovernancePages.tsx` |
| D-26 | `frontend/src/api/client.ts:425-435` | `decideApproval` 提交 `{decision, reason}`（无身份字段）；本轮 grep `ApprovalController.java` **无 `Idempotency-Key`** ⇒ §7 改进点 7 幂等键是真实缺口 | ①✅ ②N/A ③✅ ④⚠️ | `grep -n "Idempotency-Key" platform/agent-api/src/main/java/cn/studykid/multiagent/api/governance/ApprovalController.java`（预期零命中） |
| D-27 | `platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcConfigPublishRequestRepository.java:241-287` | `decide()` 同事务：`loadForUpdate:251` → 自我审批拦截 `SelfApprovalException:253-254` → 状态前置 `StateConflictException:256-259` → `updateDecision:263` → 审计同事务 `audit.recordRequired:267-273` → DB CHECK 违例映射 `:280-286` | ①✅ ②✅（state+审计）③✅ ④❌（缺并发 decide 专项 IT） | `sed -n '241,287p' platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcConfigPublishRequestRepository.java` |
| D-28 | 同上 `:372-381` | `loadForUpdate` SQL 含 `FOR UPDATE`（`:376`）——两人并发 decide 被行锁串行化；**机制齐、门禁缺**（P0 验收报告 §7.1） | ①✅ ②✅ ③✅ ④❌ | `grep -n "FOR UPDATE" platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcConfigPublishRequestRepository.java` |
| D-29 | `deploy/sql/platform/01_platform_ddl.sql:1882`、`:1945` | `cap_config_publish_request_two_person_ck`（`approver_id <> requester_id`，`:1882`）与 `cap_config_release_two_person_ck`（`:1945`）——**写作时快照 `:1873` 已漂移，以本行为准** | ①✅（DDL CHECK）②✅ ③✅ ④✅（违例即报错） | `grep -n "two_person_ck" deploy/sql/platform/01_platform_ddl.sql` |
| D-30 | 同上 `:316-343`、`:4685` | `enforce_approval_state_transition()`：终态 `CONSUMED/REJECTED/EXPIRED` 不可迁出（`:319-323`）、`PENDING→{APPROVED,REJECTED,EXPIRED}`（`:326-330`）、`APPROVED→{CONSUMED,EXPIRED,REJECTED}`（`:333-337`）；触发器挂 `approval`（`:4685`） | ①✅ ②✅ ③✅ ④✅ | `sed -n '316,343p' deploy/sql/platform/01_platform_ddl.sql` |
| D-31 | 同上 `:347-382`、`:4713` | `enforce_cap_approval_state_transition()`：`PENDING→RESERVED→INTENT_SUBMITTED→CONSUMED` 单向 + 终态不可迁出；触发器挂 `cap_approval`（`:4713`） | ①✅ ②✅ ③✅ ④✅ | `sed -n '347,382p' deploy/sql/platform/01_platform_ddl.sql` |
| D-32 | 同上 `:1465-1480`、`:4233` | `public.approval` 表（`:1465`）含 `argument_digest`（`:1471`）/ `target_resource`（`:1472`）/ `expires_at`（`:1474`）+ CHECK（`:1479-1480`）；`idx_approval_pending` 部分索引（`:4233`） | ①✅ ②✅ ③✅ ④✅ | `sed -n '1465,1481p' deploy/sql/platform/01_platform_ddl.sql` |
| D-33 | 同上 `:1704-1731`、`:1755-1769` | `cap_approval` 表（`:1704`）含 `approval_scope_hash`（`:1713`）/ `effective_args_hash`（`:1715`）/ `closure_fingerprint`（`:1716`）/ `AWAITING_HUMAN` CHECK（`:1731`）——治理期双哈希防参数替换重放 | ①✅ ②✅ ③✅ ④✅ | `sed -n '1704,1732p' deploy/sql/platform/01_platform_ddl.sql` |
| D-34 | 同上 `:1676`、`:1838-1857`、`:4706` | `model_ref` 注释「NULL 未绑定 ⇒ 运行期拒绝该 Run，绝不回落默认模型」（`:1676`）；`cap_config_pointer` 注释「只认指针、绝不 max(seq)」（`:1857`）；`forbid_published_def_mutation` 触发器（`:4706`） | ①✅ ②✅ ③✅ ④✅ | `sed -n '1676,1677p;1855,1858p' deploy/sql/platform/01_platform_ddl.sql` |
| D-35 | `platform/agent-ports/src/main/java/cn/studykid/multiagent/ports/quality/ReviewAttributionHook.java:9-27`、`:29`、`:41` | 端口文档明写「产品定口径、默认 NoOp 自动让位」（`:13/:23`）、仅 PROMPT/CONFIG/MODEL 回调（`:26-27`）；`onAttributionResolved` 签名（`:41-46`） | ①⚠️ 端口存在、生产无 Bean ②❌ ③✅（接线存在）④❌ | `grep -rn "implements ReviewAttributionHook" platform --include=*.java \| grep -v "/test/"`（预期 main 零命中） |
| D-36 | `platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcReviewTaskRepository.java:122-150` | `resolve()`：先真正关单（`:123-127`，0 行命中抛异常），再判 `attributionHook != null && rootCause ∈ ITERATION_ROOT_CAUSES`（`:132-133`）回调（`:139-140`），异常 `log.warn` 不回滚（`:141-144`）——**接线完整** | ①⚠️ 装配点存在、Bean 缺 ②❌（生产不回写）③✅ ④❌ | `sed -n '122,151p' platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcReviewTaskRepository.java` |
| D-37 | `platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApplicationConfiguration.java:292-302` | `reviewTaskPort` 用 `hookProvider.getIfAvailable()`（`:302`）——生产无 Bean ⇒ 注入 null ⇒ `resolve` 走 NoOp | ①⚠️ ②❌ ③✅ ④❌ | `sed -n '292,303p' platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApplicationConfiguration.java` |
| D-38 | `platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/ConfigPublishWorkflowService.java:324`、`:344-366`、`:504-528`、`:616` | `decide():324`、`apply():344-366`（仅 APPROVED 可执行，失败 `markFailed:366` 终态）、`buildPublishCommand` 固化 `spec.modelRef`（`:520/:528`）、`resolveBaseline:616`（CAS 基线） | ①✅ ②✅ ③✅ ④✅ | `grep -n "public .*decide\|public .*apply\|buildPublishCommand\|resolveBaseline\|markFailed" platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/ConfigPublishWorkflowService.java` |
| D-39 | `admin-console/src/pages/publish/PublishRequestDetailPage.tsx:28-31`、`:177` | 治理发布审批 UI：同人审批**前端预检**（注释明写「这是提示不是边界，真正拦截在后端」:30-31）+ 审批 Card（`:177`），与后端 `:253-254` + DDL `:1882` 形成纵深 | ①✅ ②N/A ③✅ ④✅ | `sed -n '28,32p;175,180p' admin-console/src/pages/publish/PublishRequestDetailPage.tsx` |

> **可达性汇总**：39 个锚点中，「四问全过」**24** 个、「部分可达（③过、④缺可观测或门禁）」**9** 个、「**机制/装配在但生产无实现或消费方**」**6** 个（D-12 健康事实无消费方、D-17 就绪守卫只 log、D-18 过时注释、D-25 前端终态文案、D-26 无幂等键、D-35/36/37 归因 NoOp 三连——其中 D-35/36/37 是**同一缺口的三处证据**）。
> **三条最需要注意的判定**：
> 1. **D-35/36/37 三连构成「接线完整、生产无 Bean」的完整证据链**——`ReviewAttributionHook` 是真实能力缺口（非死代码、非已闭环），§7 改进点 3 只需一个 Bean 即可补，MEMORY「NoOp」结论**已被复核纠偏**为「预留扩展点 + 未闭环能力」。
> 2. **D-27/D-28 与 D-29 构成「机制齐、门禁缺」**——`FOR UPDATE` + `two_person_ck` 静态保证并发不破坏状态机，但**「两人并发 decide」专项 DB IT 门禁仍未闭环**（P0 验收报告 §7.1），属验证补强非缺陷修复。
> 3. **D-24/D-25 是本轮对 §5.3 劣势 2 的纠偏来源**——参数摘要/目标资源/有效期**已展示**（FR-014 落地），「审批台不展示参数摘要」**已被复核纠偏**；剩余「risk_level 未展示」「4xx 文案伪装临时故障」「无幂等键」三条仍成立。

### 附录 E：落地实施方案（改动清单 + 验收门禁 + 回滚）

> 每条对应正文第七章的一个改进点。六段式：**前置条件 / 改动清单（含 Java、DDL、YAML、SQL 具体片段）/ 三级验收门禁 / 反向验证（撤点即红）/ 回滚策略 / 铁律对齐**。
> **全部未落地**（属规划）。门禁一律遵循铁律 6：**单元 / API / 角色×任务**三级缺一不可，且每条必须回答「撤掉修复点会红吗」；遵循铁律 17：静态守卫 ≠ 运行时成立，须真实交互驱动。**本维度铁律**：演进一律复用现有 `WAITING_APPROVAL` PARK/RESUME 闭环，**禁止新建 PAUSED 态**（引用旧「缺 PAUSED 态」结论须标注「已被复核纠偏」）。

#### E-1（对应改进点 1）运行期暂停泛化——复用 `WAITING_APPROVAL`，不新增枚举

- **前置条件**：① `RunState.java:54` 已有 `WAITING_APPROVAL` 非终态、`:75` 已有迁出集合（**已被复核纠偏**：不再「新增 PAUSED 态」）；② `RunExecutionLoop.java:2073-2109` 的 `resume()` 已能「仍挂起则重 PARK」；③ `ApprovalDecidedWakeupConsumer.java:113/:132` 的按 operationId 反查路径可复用；④ `forbid_published_def_mutation`（DDL `:4706`）在库，保证 `statePatch` 无法改已发布定义。
- **改动清单**：
  - **DDL**（`01_platform_ddl.sql`）：`ALTER TABLE public.run ADD COLUMN pause_reason text, ADD COLUMN pause_context jsonb, ADD COLUMN pause_ticket_id uuid; CREATE UNIQUE INDEX idx_run_pause_ticket ON public.run (tenant_id, pause_ticket_id) WHERE pause_ticket_id IS NOT NULL;`。**不改 `run_state` 枚举、不改 `enforce_run_state_transition()`（`:462-484`）**——这是路径 A 与路径 B 的根本区别。
  - **Java（新增端口）**（`agent-ports/…/persistence/RunPausePort.java`）：`PauseReason {TOOL_APPROVAL, HUMAN_EDIT, OFF_SWITCH, REVIEW}`；`pause(tenantId, runId, reason, editableContext)` / `resume(tenantId, pauseTicketId, statePatch, approverId)`；`resume` 内部复用 `ApprovalDecidedWakeupConsumer.java:113/:128-132`，`statePatch` 白名单只作用 `pause_context`。
  - **Java（装配）**（`WorkerExecutionConfig.java`）：新增 `runPausePort(...)` Bean，注入 `RunClaimPort` / `RunPauseRepository` / `ApprovalWakeupPort`；取消端点 `POST /api/runs/{ticket}/resume` 要求 `run:resume` 动作（铁律 7 同步动作矩阵 + `03_platform_roles.sql`）。
  - **DDL 同步（若误走路径 B）**：必须四处同步——`RunState.java:72-79` 迁移表 + DDL `:462-484` 触发器 + `RunRecoverySweeper.java:267` 的 `state` 条件 + 维度 04 SSE 帧。**本报告建议不采用路径 B**。
- **三级验收门禁**：① 单元——`pause(HUMAN_EDIT)` 后断言 `run.state='WAITING_APPROVAL'`（复用态）、`pause_reason` 落库、`modelGateway` 调用 0 次；`resume` 带 `statePatch` 断言 patch 应用、`ledger.findInvocation` 调用 1 次。② API——同一 `pauseTicketId` 二次 `resume` 幂等，`tool_operation` 该 operationId 仅 1 行；STUDENT 越权 403 且停轮询（铁律 15）。③ 角色×任务——APPROVER 可 resume、HUMAN_EDIT 场景人工改草稿后继续，模型重调 0 次。
- **反向验证**：撤掉 `RunState.java:75` 的 `WAITING_APPROVAL→RUNNING` ⇒ `resume` 必抛 `restrict_violation`（红）；`statePatch` 尝试写 `cap_agent_def` 已发布行 ⇒ 必被 `:4706` 触发器拒绝（红，证明不碰不可变快照 D-9）；`pause_reason` 误写成新枚举值 ⇒ DDL `:479-481` 拒绝（红）。
- **回滚策略**：停调 `RunPausePort`（`RunExecutionLoop` / Controller 取消调用点）即回纯工具审批门；三列 nullable 保留（不删，避免丢 `pause_reason` 历史）。**禁止**回滚成「新增 PAUSED 枚举」。无状态机/触发器改动 ⇒ 回滚零 DDL 风险。
- **铁律对齐**：铁律 6/17（三级验收 + 撤点反证 + 真实交互）；D-9 不可变发布（`forbid_published_def_mutation` 兜底）；铁律 7（新动作同步角色）；铁律 2（新枚举若走路径 B 须 `?::run_state` 绑定）。**已被复核纠偏**：旧「缺 PAUSED 态与恢复入口」结论不成立，本方案是增量泛化而非新建态。

#### E-2（对应改进点 2）审批源生产接线——`live-source` + 策略表 + 就绪强校验

- **前置条件**：① 审批台服务与 worker 可达（跨进程部署见维度 12，同进程装配 `WorkerExecutionConfig.java:807-813`）；② 策略表数据已准备（`application.yml:584-586` 的 `"tenant:tool=VERDICT"` 格式）；③ `JdbcApprovalRepository.request()`（`JdbcApprovalRepository.java:181` 附近）已覆写 `ApprovalPort.request()` 默认空（E-10），否则待办不可见；④ 已确认 `live-source-enabled=false` ⇒ 空策略全 `FAIL_CLOSED` 是当前安全默认（`RiskBasedApprovalPolicy.java:210/:214`）。
- **改动清单**：
  - **YAML**（`application.yml:554-586`）：`live-source-enabled: true`（`:569`）、`require-live-source: true`（`:571`）、`source.mode: policy-engine`（`:580`）、`policy.rules` 非空（`:585`）——四键必须按注释「两键自洽」同步翻转。
  - **Java**（`WorkerExecutionConfig.java:346/:373/:377`）：`mode=policy-engine && rules` 非空 ⇒ `new PolicyEngineApprovalSource(policy)`；空策略仍 `FAIL_CLOSED`（不违反 fail-closed）。
  - **Java（注释修正）**（`ApprovalReadinessGuard.java:22-23`）：把「ApprovalController 操作能力/配置发布域、不覆盖工具副作用执行域」改为「`/api/approvals` 运行期工具审批台，`decide` 后经 `ApprovalWakeupPort.wakeup` 唤醒挂起 Run」（§4.5 不一致 4，文档级）。
  - **Java（确认）**（`ToolInvocationGate.java:1590-1606`）：确认 `ApprovalRequirement.REQUIRED` + `approverScope` + `Instant.now().plus(ttl)` 落到 `ApprovalPort.request(...)`，且 `JdbcApprovalRepository.request()` 已覆写。
  - **DDL**：无。
- **三级验收门禁**：① 单元——`ApprovalReadinessGuard.toolsRequiringApproval():93` 覆盖所有 `WRITE_EXTERNAL` 注册工具；空策略 ⇒ 全 `FAIL_CLOSED`。② API——触发 `WRITE_EXTERNAL` ⇒ `public.approval` 增 1 行 `PENDING`、`run.state=WAITING_APPROVAL`、租约释放；`POST decision APPROVE` ⇒ `state=CONSUMED`、Run 恢复 `RUNNING`、工具执行 1 次、`loop.resume` 未重跑模型。③ 角色×任务——PARENT/TEACHER 可批、STUDENT 403 停轮询。
- **反向验证**：撤 `require-live-source=true` ⇒ 「未接线也能启动」用例红；清空 `rules` ⇒ 所有需审工具 `FAIL_CLOSED`、零副作用（红）；把 `source.mode` 配未知值 ⇒ 启动必须拒绝（红，fail-closed）。
- **回滚策略**：三个 yml 键翻回 `false/false/none` + `rules: []` ⇒ 回到当前「全拒 + 未接线告警」安全态（`application.yml:569-580` 现状）；无 DDL、无代码回滚。注释修正可单独保留（纯文档，越早越好）。需灰度：先单租户单工具前缀。
- **铁律对齐**：铁律 8（fail-closed：空策略恒拒、未知 mode 拒启）；铁律 15（终态语义在新审批台上仍须成立）；铁律 17（真实触发工具验证，非只读配置）；`application.yml:576-579` 两键自洽注释是硬约束。

#### E-3（对应改进点 3）`ReviewAttributionHook` 落地——从 NoOp 到真实回写

- **前置条件**：① 产品归因口径已拍板（本方案**只记录结论、不自动改提示词/模型**，避免越过治理发布 D-9）；② 确认 `ApplicationConfiguration.java:302` 的 `getIfAvailable()` 会在 Bean 注册后命中；③ `JdbcReviewTaskRepository.java:141-144` 的 fail-soft 语义保留（钩子在关单事务外独立处理，不违反铁律 11）；④ `ITERATION_ROOT_CAUSES={PROMPT,CONFIG,MODEL}`（`:149-150`）不变。
- **改动清单**：
  - **Java（新增）**（`platform-bootstrap/…/quality/DefaultReviewAttributionHook.java`）：`@Component` + 实现 `onAttributionResolved(traceId, tenantId, runId, rootCause, note, reviewer)`，调 `attributionPort.record(...)`。
  - **DDL**：`CREATE TABLE review_attribution(tenant_id uuid, trace_id text, run_id uuid, root_cause text, note text, reviewer text, created_at timestamptz)`；同步 `03_platform_roles.sql` 授权（铁律 7）+ 事务内绑定 RLS GUC（铁律 1）。
  - **Java（保留）**：`JdbcReviewTaskRepository.resolve():132-144` 的接线与 fail-soft **不改**；`ApplicationConfiguration.java:292-302` **不改**（Bean 注册后自动命中）。
- **三级验收门禁**：① 单元——注入 Bean 后 `resolve(PROMPT)` 断言回调 1 次 + `review_attribution` 落 1 行；移除 Bean ⇒ 0 次（证明 §4.5 不一致 2 的「接线完整/生产 NoOp」）。② API——`PROMPT/CONFIG/MODEL` 各落 1 行，`OTHER` 落 0 行；`record` 抛异常时 `resolve()` 仍关单、`log.warn` 出现、事务不回滚（铁律 11 边界）。③ 角色×任务——复核员看到归因留痕、学生侧不可见（受众隔离）。
- **反向验证**：删 `@Component` ⇒ 回写 0 行（红）；把 `ITERATION_ROOT_CAUSES` 改成含 `OTHER` ⇒ 噪声断言红；让 `record` 抛异常且断言任务未关闭 ⇒ 红（fail-soft 被破坏）；回写触碰 `cap_agent_def` ⇒ `:4706` 触发器红。
- **回滚策略**：移除 `@Component`（保留类文件）⇒ 立刻回 NoOp（生产当前行为），表保留（默认空、无数据风险）；**不得**让回写触发自动改提示词/模型。口径若变更，只需换 Bean 实现，编排零改动。
- **铁律对齐**：铁律 11（钩子在事务外 fail-soft，不把关单事务毒化）；铁律 7（新表同步角色）；铁律 1（RLS GUC 事务内绑定）；D-9 不可变发布（不碰 `cap_agent_def`）。**已被复核纠偏**：MEMORY「NoOp」→「预留扩展点 + 未闭环能力」。

#### E-4（对应改进点 4）治理审批并发 decide 专项 DB IT 门禁——纯测试，零生产改动

- **前置条件**：① 真 PG 环境（`IT_HOST=127.0.0.1 IT_PORT=5433 IT_ADMIN_USER=agent` + `source scripts/it-env.sh --probe`）；② 确认 `JdbcConfigPublishRequestRepository.decide():241-287` 的 `FOR UPDATE`（`:376`）与 `two_person_ck`（DDL `:1882`）机制已在（**不修生产**）；③ 现有 `ConfigPublishIT.java:139/242/273` 是「指针 CAS」用例、`ConfigPublishManagementIT.java:303-311/368-369` 只有单人用例——本门禁**补缺口**；④ 禁用 H2 假锁（铁律 17）。
- **改动清单**：
  - **Java（新增 IT 方法）**（`agent-persistence/src/test/…/cap/ConfigPublishManagementIT.java`）：`concurrentDecideSerializesAndKeepsSingleWinner()`——`CountDownLatch` 双线程对同一 `requestId` 并发 `decide(APPROVE, APPROVER_1)` / `decide(APPROVE, APPROVER_2)`；断言恰 1 成功 + 1 `StateConflictException`、审批审计 1 条、终态唯一、重复 50 次无 flaky。
  - **Java（静态守卫）**：`assertThat(decideSqlOf(JdbcConfigPublishRequestRepository.class)).contains("FOR UPDATE");`（钉死 `:376`）；断言 `decide()` 含 `state != PENDING → StateConflictException` 分支（`:256-259`）。
  - **SoD 反向**：DB 直连 `UPDATE … SET approver_id = requester_id` ⇒ `:1882` CHECK 拒绝。
  - **DDL / YAML**：无。
- **三级验收门禁**：① 单元——并发断言 + 静态 SQL 守卫。② API（DB IT）——真 PG 双线程 decide，`@Rollback(false)` 清理，skipped=0（MEMORY 高频坑）；断言审计恰 1 条。③ 角色×任务——两审批人均 ≠ 申请人；DB 直连 bypass 应用层仍被 CHECK 拦。
- **反向验证**：临时删 `loadForUpdate` 的 `FOR UPDATE`（`:376`）⇒ 并发出现两次 `APPROVED` ⇒ **红**；删 `:256-259` 状态前置 ⇒ 红；`DROP` `:1882` CHECK ⇒ SoD 反向红。三处任一撤销必红。
- **回滚策略**：**无生产代码可回滚**——仅删新增测试方法与静态守卫；但**不得为了让 CI 绿而删**（铁律 6：删门禁＝撤回「并发正确性被证明」的存在证明），须在 PR 记录原因与恢复条件。这是本维度唯一「回滚＝功能消失而非数据风险」的项。
- **铁律对齐**：铁律 6/17（真实并发夹具 + 撤点反证，禁用 H2 假锁）；铁律 2（`authz_decision_log` 禁 RETURNING，审计断言走 SELECT）；**属验证补强非缺陷修复**——P0 验收报告 §7.1 / MEMORY.md:62 列为下一批独立任务。

#### E-5（对应改进点 5）审批体验与吞吐——批量审批 + 超时自动处置 + SLA 指标

- **前置条件**：① E-2 的审批台已可用（否则批量无对象）；② TTL（15m）与 PARK 超时（1800s）当前自洽（`application.yml:563/:297`），启动一致性校验可加；③ Micrometer/Prometheus 注册点已定位（平台 bootstrap，与维度 10 复用同一套）；④ `enforce_approval_state_transition()`（DDL `:316-343`）允许 `PENDING→EXPIRED`，超时自动置 EXPIRED 合法。
- **改动清单**：
  - **Java（新端点）**（`ApprovalController.java`）：`POST /api/approvals/batch-decision`，`record BatchDecisionRequest(List<String> approvalIds, String decision, String reason)`；逐条复用 `decide()`（权限 `:130-131`、审计同事务、wakeup `:147-149`），单条失败返回 `{approvalId,newState,error}` **不回滚其他条**（绝不「全批一个事务」）；批量上限初值 10。
  - **Java（展示增强）**：`GET /api/approvals`（`:94-116`）返回体补 `riskLevel` / `toolName` / `argumentDigest` / `targetResource` / `expiresAt` / `ageSeconds`；**默认值绝不勾选 APPROVE**（延续 D-6 缺省拒绝）——`argumentDigest/targetResource/expiresAt` **已在** `ApprovalItemResponse`（`:197-218`），本项是补 `riskLevel` 等（**已被复核纠偏**：不是「全无展示」）。
  - **Java（指标）**（Micrometer）：`approval_pending_age_seconds`（直方图）、`approval_decided_total{decision}`、`approval_reject_ratio`、`approval_timeout_total{reason}`、`approval_batch_seconds`；告警 `pending_age_p95 > 0.8*ttl`、`reject_ratio≈0` 触发橡皮图章抽查。
  - **Java（超时处置）**：`RunRecoverySweeper.java:267` 旁补「审批 TTL 到但 Run 未到 PARK 超时」时把 `approval` 从 `PENDING→EXPIRED`（由 DDL `:330` 守卫），推终态；启动校验 `assert parkedTimeout > ttl`。
  - **YAML/DDL**：Prometheus 规则入 `monitoring/prometheus/rules/*.yml`；无 DDL 变更。
- **三级验收门禁**：① 单元——批量 10 条含 1 条已 CONSUMED ⇒ 9 成功 / 1 个 409，各自 wakeup 1 次；`decision` 传空/小写 ⇒ 全部 DENY（D-6）。② API——造 100 条 PENDING 到 `0.8*ttl` ⇒ 直方图有值、告警命中（铁律 17 真实交互）；ttl/parked 倒置 ⇒ 启动报配置错误。③ 角色×任务——`reject_ratio≈0` 连续 100 次全批 ⇒ 审计抽查告警（证明 A5 能发现 AP1）。
- **反向验证**：撤 `reject_ratio` 告警 ⇒ 橡皮图章用例红；删批量「单条失败不回滚其他」⇒ 部分失败用例红；撤启动一致性校验 ⇒ ttl/parked 倒置仍启动（红）。
- **回滚策略**：批量端点下线（移除 Controller 方法）⇒ 回单条审批；指标/告警可单独停用；启动一致性校验**建议保留**（是修 bug 的护栏）。风险对冲：批量放大 automation-bias ⇒ 风险展示 + 缺省拒绝 + `reject_ratio` 告警 + 抽样复核（对抗 AP1/AP7）。
- **铁律对齐**：铁律 15（终态不被批量重试掩盖）；铁律 17（指标须真实交互驱动）；GDPR Art.22 / EU AI Act Art.14 的 meaningful intervention 动机（§2.4 锚点 A/B）。

#### E-6（对应改进点 6）审批审计关联维度 10 审计哈希链

- **前置条件**：① 维度 10 的 `audit_chain` 组件可复用（**不新造链**）；② 审批审计已同事务（`JdbcConfigPublishRequestRepository.java:267-273`、`ApprovalController.java:139-141`）；③ 铁律 2（`authz_decision_log` 禁 RETURNING）、铁律 1（RLS GUC 事务内绑定）、铁律 11（事务内 SQL 失败禁 fail-soft 后 COMMIT）已明确；④ `audit_record` / `erasure_certificate` 是 P0 验收报告 §2 第 1 项已修的防篡改守卫表——**禁物理删除**。
- **改动清单**：
  - **Java（旁路写链）**：在 `:267` 与 `:139-141` 审批落库处挂 `audit.recordChain(...)`（复用维度 10）：`{tenant_id, event_type=APPROVAL_DECIDED|CONFIG_DECIDED, ref_id, actor, action, argument_digest, target_resource, decided_at, prev_hash, hash}`；`hash = SHA256(prev_hash || canonical(event))`。
  - **Java（校验端点）**：`GET /api/approvals/{id}/audit-trail`（`APPROVAL_CONSUME` 或 `AUDIT_READ` 权限），返回按 hash 链顺序可自校验的事件序列。
  - **约束**：① 哈希链写入失败 ⇒ 事务按铁律 11 标记 POISONED、**不得** fail-soft 后 COMMIT（否则「审批已生效但审计缺失」）；② `authz_decision_log` 禁 RETURNING（铁律 2）；③ RLS GUC 事务内绑定（铁律 1）；④ 审批人只取令牌（D-6）。
  - **DDL/YAML**：无新表（复用 `audit_chain`）；无配置。
- **三级验收门禁**：① 单元——连续 5 次审批 ⇒ 链恰 5 条、每条 `prev_hash` = 前条 `hash`、客户端自校验全通过。② API——手动改中间一条 `action` ⇒ 校验端点报「链断裂」；哈希链写入抛异常 ⇒ 审批状态**未** COMMIT（POISONED，铁律 11）；`GET /audit-trail` STUDENT ⇒ 403 停轮询、AUDIT_READ ⇒ 200。③ 角色×任务——审批链可按 trace/actor 顺序回放，GDPR Art.22「contest 决定」可举证。
- **反向验证**：删旁路写链 ⇒ 链完整性断言红；手工改中间 `action` ⇒ 链断裂断言红；把哈希链写入改成 fail-soft 后 COMMIT ⇒ POISONED 断言红（铁律 11）。
- **回滚策略**：旁路写链可停用（链为空，审批主流程不受影响）；校验端点可下线；**已写入的链数据不删**（删链＝毁证据，违反「禁物理删除」）；先治理发布审批链灰度，再运行期审批链。
- **铁律对齐**：铁律 11（POISONED，禁 fail-soft 后 COMMIT）；铁律 2（禁 RETURNING）；铁律 1（RLS GUC）；铁律 15（audit-trail 403 停轮询）；维度 10 哈希链组件复用（§8.4.2 交界）。

#### E-7（对应改进点 7）审批拒绝/超时的客户端语义——终态明确 + 停轮询 + 不给重试

- **前置条件**：① 服务端已给显式终态（`ApprovalController.java:157-165`：404 不区分存在性 / 409 显式；D-7）；② 前端 `GovernancePages.tsx:87-90` 当前 catch 对 4xx 用「请稍后重试」——**铁律 15 未闭合的现场**（本轮实测）；③ SSE 侧 `done` 终帧约定见铁律 12 / 维度 04；④ `ApprovalController` 本轮 grep **无 `Idempotency-Key`**（真实缺口）。
- **改动清单**：
  - **文档（契约表）**（`ApprovalController` Javadoc + 前端错误隔离层）：`200+APPROVED/CONSUMED` → 继续轮询；`200+REJECTED` → 停轮询 + 展示拒绝原因 + **不给「重试审批」按钮**；`409 EXPIRED/已决定` → 停轮询提示已失效；`404`（不区分存在性）→ 停轮询、不探测；`401/403` → 停轮询跳鉴权；**仅 `5xx`/网络超时** → 有界退避 ≤3 次且先查状态。
  - **Java（幂等键）**：`POST decision` 支持 `Idempotency-Key`，重复提交返回首次结果而非再次 decide（避免 5xx 重试撞 409 被误当失败）；存储 TTL 清理策略。
  - **前端片段**（`GovernancePages.tsx:87-90`）：`catch` 按 `status` 分流——4xx 展示终态文案（**禁**「正在处理 / 网络问题 / 稍后再试」，铁律 15），仅 5xx 有界重试；SSE 侧 Run 被拒推 `done` 终帧带 `terminal_reason`。
  - **Java（保持）**：`DecisionRequest.approve()` 缺省拒绝（`:177-185`，D-6）不变；API 文档明示「空 decision 按 DENY」。
  - **DDL/YAML**：无。
- **三级验收门禁**：① 单元——对 `REJECTED`/`409`/`404`/`403` 各造一次 ⇒ 轮询停止、无重试请求发出、`Idempotency-Key` 未产生第二条 decide；`5xx` ⇒ 有界重试 ≤3 且每次先查状态。② API——同 key 提交两次 APPROVE ⇒ 返回同一结果、审计恰 1 条、wakeup 恰 1 次；SSE Run 被拒 ⇒ 收到带 `terminal_reason` 的 `done` 终帧。③ 角色×任务——STUDENT 403 停轮询；D1/D2 错误隔离不串状态（铁律 19）。
- **反向验证**：删 SSE 终帧推送 ⇒ 前端轮询不停止（红，铁律 17）；把 4xx 改回可重试 ⇒ 重试请求断言红；删幂等键 ⇒ 5xx 重试产生第二条 decide（红）；把 `approve()` 改成缺省批准 ⇒ D-6 反证红。
- **回滚策略**：契约文档与前端分流可回退到「一律提示稍后重试」——**这是当前行为但违反铁律 15，回滚须登记合规债**；幂等键可停校验；`DecisionRequest.approve()` 缺省拒绝（D-6）**任何情况下不得回滚为缺省批准**（会打开空 body 变批准的绕过）。
- **铁律对齐**：铁律 15（终态/权限错误不得伪装临时故障）；铁律 12（`done` 终帧）；铁律 18/19（ErrorBoundary + 错误隔离 D1/D2）；铁律 7（幂等键存储若新增动作需同步角色——本项复用 `APPROVAL_CONSUME`，不新增）。

> **附录 E 实施顺序（排期硬约束）**：`E-3` / `E-6` 依赖产品口径与维度 10 组件；`E-5` 依赖 `E-2`（审批台先可用）；`E-1` 与 `E-2`/`E-7` 同为首期最小闭环；`E-4` 是纯测试门禁，可与任一批并行。**七项均不违反本维度铁律**：复用 `WAITING_APPROVAL`（不新建 PAUSED）、`ReviewAttributionHook` 缺口靠 Bean 而非改编排、并发专项是验证补强而非缺陷修复、终态/权限错误显式化。

### 附录 F：竞品实现扩充（官方文档锚点）

> 本表**全部**经本轮 `WebFetch` / `WebSearch` / PDF 下载实际访问并核对内容；URL 逐条列在「官方来源」列并标注核实状态。**未能核实的项显式标注，不臆测、不编造 URL 或数字。** 正文 §3 已覆盖 7 个框架，本表扩充到 **12 个**（新增 Temporal / AWS Step Functions / Microsoft Agent Framework / PostgreSQL / NIST AI RMF），并在「关键取舍」列写入本轮核实到的具体参数与 nuance。

| 竞品 | 机制 | 关键取舍 | 对本维度的启发 | 官方来源 |
|---|---|---|---|---|
| **LangGraph**（已核实·扩充） | `interrupt()` 任意节点暂停 + checkpointer(thread_id) 持久化 + `Command(resume)` 恢复；官方原文 *"The node restarts from the beginning of the node where the interrupt() was called when resumed"*；决策词 approve/reject、edit、cancel（非 accept/respond） | 换来 A2 灵活性（任意点 + 状态编辑），代价是节点内 `interrupt()` 前代码须幂等、框架不校验审批状态迁移合法性 | 「恢复须幂等」的规范侧解释；本项目按 operationId 反查、不重跑模型在工具审批场景更强（不重复扣预算），但通用性弱；决策词按官方纠偏 | `https://docs.langchain.com/oss/python/langgraph/interrupts` ✅；`https://docs.langchain.com/oss/python/langgraph/persistence` ✅（本轮 WebFetch：thread_id checkpointer + `MemorySaver` 重启即丢、生产须 `PostgresSaver`） |
| **OpenAI Agents SDK**（已核实） | Tool guardrail：*"If a function tool requires approval, input tool guardrails normally run after approval…"*、*"pending approval interruption is emitted"*（`RunConfig.tool_execution`/`ToolExecutionConfig`）；无通用 durable pause-resume | 概念少、贴近业务；暂停/恢复/状态全交宿主（L1）；本轮核实该页有 guardrail 拒绝后的 session 写入策略，但**不是**审批持久化机制 | 「有审批信号、无通用引擎」的精确口径（§3.2 / §4.5 不一致 7）；我们不接线时 fail-closed 更严 | `https://openai.github.io/openai-agents-python/guardrails/` ✅（本轮 WebFetch 逐字核对 pending approval 原文） |
| **Anthropic Claude Agent SDK**（已核实·纠偏） | `canUseTool`(allow/deny)、`PreToolUse` hook 的 `permissionDecision ∈ {allow,deny,ask,defer}` + `updatedInput`(modify)，优先级 `deny>defer>ask>allow`；`defer` 可 *"resume the call later"* 但无 durable checkpoint；`PermissionRequest` 用于权限决策、外部通知归 `Notification` hook | 工具级粒度细、适配 computer use；回调级暂停进程内，无持久化 checkpointer / SoD / 审计 / TTL | 「modify 归 PreToolUse hook 而非 canUseTool」（**已被复核纠偏**）；我们 DB 侧状态机（DDL `:316-382`）抗绕过更硬 | `https://code.claude.com/docs/en/agent-sdk/permissions` ✅、`https://code.claude.com/docs/en/agent-sdk/hooks` ✅（本轮 WebFetch；旧 `docs.anthropic.com` 重定向） |
| **Google ADK**（已核实） | `require_confirmation=True` / `tool_context.request_confirmation(hint,payload)`；远程 REST `/run` `/run_sse` + `FunctionResponse`（`name=adk_request_confirmation`、`response.confirmed`）；`invocation_id` **仅在启用 Resume 特性时必带** | 远程审批工程化最完整、结构化确认输入；确认点绑定工具执行（非任意节点）、恢复约束在 invocation_id 内 | 「invocation_id 条件必带」的精确口径（§3.4 已注明）；我们审批台同事务审计（`:267-273`）是 ADK 无的 | `https://adk.dev/tools-custom/confirmation/` ✅（本轮 WebFetch 逐字核对） |
| **AutoGen → Microsoft Agent Framework**（已核实·纠偏） | 当前 stable：`UserProxyAgent` + `selector_func` + `"APPROVE"` 字符串判定；`human_input_mode` 属 0.2 版 API。Agent Framework 官方：*"direct successor"* / *"next generation of both Semantic Kernel and AutoGen"*，Harness Agent 含 *"don't-ask-again tool approval"*，Workflow 含 *"robust state management… human-in-the-loop"* | 后继关系按官方原文；don't-ask-again 提升吞吐但放大橡皮图章风险 | 两处纠偏：AutoGen 措辞精确化、`human_input_mode` 版本标注（**已被复核纠偏**）；若引入免审须配 reject_ratio 观测 | `https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/selector-group-chat.html` ✅；`https://learn.microsoft.com/en-us/agent-framework/overview` ✅（本轮 WebFetch） |
| **CrewAI**（已核实·纠偏） | Webhook HITL：`humanInputWebhook`、`Pending Human Input` 状态、`/resume` 端点（resume 须重传 webhook URLs）；`@human_feedback` 在独立页（1.8.0+） | 任务/流程级反馈 + approved/rejected 分支；webhook 异步接入；resume 需重传上下文，SoD/审计非内建 | 「resume 须重传上下文」对照本项目按 operationId 反查（`:113`）——我们把恢复上下文钉在 ledger，不靠调用方记忆 | `https://docs.crewai.com/en/learn/human-in-the-loop` ✅；`https://docs.crewai.com/en/learn/human-feedback-in-flows`（`@human_feedback` 独立页，**已被复核纠偏**） |
| **Agno**（已核实） | `active_requirements` 下 `needs_confirmation` / `needs_user_input` / `needs_external_execution`；`requirement.confirm()/reject()` + `agent.continue_run(run_id, requirements)`；另有 `Requires Approval or Audit`（管理员审批 + 审计留痕） | HITL 类型覆盖最全、结构化输入 schema 明确；仍以工具/步骤为粒度，非任意状态编辑 | Agno 的「审批 + 审计」类型最接近本项目运行期审批，但无 DB 触发器状态机与 SoD；我们治理发布审批（C6）独有 | `https://docs.agno.com/hitl/overview` ✅（本轮 WebFetch 逐字核对 active_requirements / continue_run） |
| **Temporal**（新增·已核实） | Signals=异步写 + **记入 Event History**、不等结果；Queries=读、不写 History、可对已完成 workflow 执行；Updates=同步可追踪写 + validator 可在写 History 前拒绝。上限：10,000 Signals / 10 in-flight Updates / 2,000 Updates | 换来「审批决定作为持久消息 + 可 Query 观察」；代价是 workflow 须确定性、消息有平台上限 | 「审批台已批准但工具未执行」可用 Query 观察；我们同步 wakeup + sweeper 兜底是领域专用替代；旧 signals-and-queries URL 404（**已被复核纠偏**） | `https://docs.temporal.io/encyclopedia/workflow-message-passing` ✅（本轮 WebSearch 命中官方页核对）；辅助 `https://docs.temporal.io/workflows` ✅ |
| **AWS Step Functions**（新增·已核实） | Activity `GetActivityTask` 返回 `input`+`taskToken`；`SendTaskSuccess/Failure` 带 token 回传；`SendTaskHeartbeat`+长 TimeoutSeconds 可等一年；**超时即作废 taskToken，过期调用抛 `TaskTimedOut`** | 换来托管回调审批 + 超时结构化作废；代价是 token 不绑参数、heartbeat 语义需运维理解 | 「超时作废 token」是附录 I「旧 RESUME 复活」反例的工业级对照；我们 `WHERE state='WAITING_APPROVAL'`（`:285`）+ fencing 等价状态侧作废 | `https://docs.aws.amazon.com/step-functions/latest/dg/concepts-activities.html` ✅（本轮 WebFetch 逐字核对 taskToken/超时作废） |
| **PostgreSQL 行锁**（新增·已核实） | `FOR UPDATE … SKIP LOCKED` 跳过被锁行（官方：*"not suitable for general purpose work, but can be used to avoid lock contention with multiple consumers accessing a queue-like table"*）；Caution：READ COMMITTED 下 ORDER BY + 锁子句可能乱序、`LIMIT` 锁够即停 | 换来零依赖并发原语 + 明确语句语义；代价是语义细节极多（Caution 即误用清单） | `FOR UPDATE`（`:376`）是「两人并发 decide 不破坏状态机」的规范基础；**锁只保证串行化，不保证 SoD**——SoD 仍靠 `:253-254` + DDL `:1882` | `https://www.postgresql.org/docs/current/sql-select.html` ✅（本轮 WebFetch 逐字核对 SKIP LOCKED / Caution / LIMIT） |
| **NIST AI RMF 1.0**（新增·已核实） | GOVERN / MAP / MEASURE / MANAGE 四函数；`human oversight` 3 次、`human-AI configuration` 3 次（本轮 PDF 解析） | 自愿性框架，不规定 API / 阈值 / 双人复核；解释「为什么测」不解释「测哪个字段」 | A5 有效性观测（SLA/拒绝率）在 MEASURE 治理函数中有位置，非事后报表；7 竞品 A5 空白是全行业短板 | `https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.100-1.pdf` ✅（本轮下载 48 页 PDF 解析：NIST AI 100-1 / January 2023） |
| **four-eyes / SoD（非框架对照）** | Static SoD（授权期角色互斥）/ Dynamic SoD（会话内互斥）；本项目「申请人≠审批人」属动态 SoD | four-eyes / maker-checker 未能核实单一权威原始出处，按公开工程实践归纳 | 「权限 ≠ 身份」的解释力（§2.4 锚点 G）；CSRC glossary URL 本轮 404，**已被复核纠偏** | NIST RBAC（ANSI INCITS 359-2004）+ Ferraiolo/Kuhn/Chandramouli《Role-Based Access Control》2nd ed.（真实出版物）；`https://csrc.nist.gov/glossary/term/separation_of_duties` ❌ 本轮 404 |

> **附录 F 未能核实项（诚实标注）**：
> 1. **NIST CSRC SoD glossary 链接**（`https://csrc.nist.gov/glossary/term/separation_of_duties`）本轮 `WebFetch` 返回 **404 Page Not Found**（**已被复核纠偏**：附录 B 原登记为「标准内容」，实测不可达）——SoD 定义改以真实专著为据，不以该 URL 为引文。
> 2. **Temporal `https://docs.temporal.io/encyclopedia/signals-and-queries`** 本轮 `WebFetch` 返回 **404**；正文改引 `workflow-message-passing` 官方页（已核实），旧 URL 标注失效。
> 3. **NIST AI RMF PDF 全文**经 `workbuddy-pdf` 解析 48 页，四函数名与 human oversight 出现次数为**本轮实测**；但「具体审批 SLA 阈值」在 PDF 中**未出现**，本报告不写任何 NIST 未给出的数字。
> 4. **four-eyes / maker-checker / alert fatigue 的单一权威出处**未能核实（与附录 B 一致），正文按「公开工程实践归纳」使用，不作事实依据。
> 5. **Anthropic 旧域 `docs.anthropic.com`** 本轮未直接抓取（地域限制，与 12 号报告一致），本表只采信 `code.claude.com` 已核实内容，不新增旧域断言。

### 附录 G：配置项全量矩阵与「生效性」判定表

> **判定四问**（MEMORY 新铁律「运行期可达性审计」在配置维度的投影）：① 谁定义？② 谁读取？③ 读取后改变了什么行为？④ 有没有测试/门禁证明它生效？
> **生效性判定**：`✅ 真生效` / `⚠️ 条件生效`（附条件）/ `❌ 默认不生效`（附默认路径）/ `🟡 漂移风险`（配置源与消费方口径不一致）/ `❓ 本轮未能核实`。
> **配置源**：`platform/platform-bootstrap/src/main/resources/application.yml`（本维度相关段：`agent.approval.*` `:554-586`、`agent.worker.recovery.parked-timeout-seconds` `:297`、`known-roles` `:685`）。
> **行号口径**：本表所有 `文件:行号` 为本轮实际 `Read`/`Grep` 复核的当前值；键名 kebab-case 与 camelCase 均 Grep 过。

| # | 配置项 | 默认值 | 生效性判定 | 影响面 | 证据锚点 |
|---|---|---|---|---|---|
| G-01 | `agent.approval.required-by-risk` | `READ:NOT_REQUIRED / WRITE_PROFILE:OPTIONAL / WRITE_EXTERNAL:REQUIRED` | ✅ 真生效 | 决定哪些工具进审批门；只能收紧不能放松（改 WRITE_EXTERNAL 为非 REQUIRED 属安全门禁降级，须走变更模板） | `application.yml:558-561`；`RiskBasedApprovalPolicy.java:87/:106-110`；`WorkerExecutionConfig.java:295/:313` |
| G-02 | `agent.approval.ttl` | `15m` | ✅ 真生效 | 审批有效期；`Instant.now().plus(ttl)` 落 `expires_at`（DDL `:1474`）；必须与 `parked-timeout-seconds` 自洽 | `application.yml:563`；`ApprovalController` 审计；DDL `:1474` |
| G-03 | `agent.approval.approver-scope` | `PARENT_OR_TEACHER` | ✅ 真生效 | 审批受众（教育场景家长/教师）；不替代 `approver_id <> requester_id`（SoD 仍靠 DDL `:1882`） | `application.yml:565`；§6.1 领域性优势 |
| G-04 | `agent.approval.live-source-enabled` | `false` | ⚠️ 条件生效（**默认未接线**） | false ⇒ `approvalSource` 空策略 → 需审批工具恒 `FAIL_CLOSED`（安全默认）；打开前须确认审批台可达 | `application.yml:569`；`RiskBasedApprovalPolicy.java:189/:210/:214`；§4.5 不一致 6 |
| G-05 | `agent.approval.require-live-source` | `false` | ⚠️ 条件生效（**默认降级**） | true 且未接线 ⇒ `ApprovalReadinessGuard` 抛 `IllegalStateException` 拒启（fail-closed 升级）；当前 false 只 log.error 不阻断 | `application.yml:571`；`ApprovalReadinessGuard.java:60-81`；改进点 2 |
| G-06 | `agent.approval.source.mode` | `none` | ⚠️ 条件生效（**默认不生效**） | `none` ⇒ 无来源实现、恒 FAIL_CLOSED；`policy-engine` ⇒ 按策略表给结论；**未知模式一律拒启**（fail-closed） | `application.yml:580` + 注释 `:576-579`；`WorkerExecutionConfig.java:373/:377` |
| G-07 | `agent.approval.source.policy.rules` | `[]`（空） | ⚠️ 条件生效（**默认空**） | 空策略 ⇒ `PolicyEngineApprovalSource` 全 `FAIL_CLOSED`（`:210/:214`）；非空且 mode=policy-engine 才给 APPROVED/REJECTED | `application.yml:584-586`；`RiskBasedApprovalPolicy.java:205-214`；改进点 2 策略数据 |
| G-08 | `agent.worker.recovery.parked-timeout-seconds` | `${…:1800}`（1800s） | ✅ 真生效 | PARK 孤儿超时收敛为 FAILED（APPROVAL_TIMEOUT）；**必须 > ttl**（15m），否则人未批 Run 先失败 | `application.yml:297` + 注释 `:295-296`；`RunRecoverySweeper.java:104/:127/:267`；`JdbcRunClaimRepository.java:602` |
| G-09 | `agent.worker.recovery.interval-seconds` | `30` | ✅ 真生效 | sweeper 扫描周期（fixedDelay），决定 PARK 孤儿最长滞留 ≈ 30s + parked-timeout | `application.yml` recovery 段；`RunRecoverySweeper.java:172` |
| G-10 | `agent.worker.recovery.require-tenant-port`（env `AGENT_WORKER_RECOVERY_REQUIRE_TENANT_PORT`） | `false` | ⚠️ 条件生效（**默认降级**） | false ⇒ 租户端口不可用时只记健康原因降级、恢复链路静默失效；true ⇒ 抛异常使本轮扫描失败（fail-closed）。**本表最值得改默认值的一条**（与 11 号报告 G-16 同款） | `RunRecoverySweeper.java:188-215`；11 号报告 G-16 交叉 |
| G-11 | `agent.worker.recovery.enabled` | `true` | ✅ 真生效 | false ⇒ 跳过本轮扫描，崩溃/挂起 Run 无人接管（AP6 复发、Run 永久 WAITING_APPROVAL/RUNNING） | `RunRecoverySweeper.java:184-187`；11 号报告 G-14 |
| G-12 | `agent.worker.lease-seconds` | `30` | ✅ 真生效 | PARK 后 `releaseLease`（`:1143`）让出资源；恢复时 `claimParkedRunForResume` 重新设租约（`:279`）；与 03 共用 | `application.yml` worker 段；`RunExecutionLoop.java:1143`；`JdbcRunClaimRepository.java:279` |
| G-13 | `agent.known-roles` | `…,APPROVER,…` | ✅ 真生效 | 角色白名单含 `APPROVER`；新增审批动作（`run:resume`/`AUDIT_READ`）须同步此键 + `03_platform_roles.sql`（铁律 7） | `application.yml:685`；`AuthorizationService.java:169-170` |
| G-14 | `agent.approval` 批量上限 / 告警阈值（`pending_age_p95 > 0.8*ttl` / `reject_ratio` / `重试≤3`） | **不存在** | ❌ 默认不生效（**能力缺失，连配置位都没有**） | 无批量审批、无 SLA 告警、无橡皮图章观测（A5 空白，AP1/AP7 未规避） | 本轮 grep `application.yml` 无 `reject_ratio\|batch\|sla` 命中；改进点 5 / E-5 |
| G-15 | `POST /api/approvals/{id}/decision` 的 `Idempotency-Key` | **不存在** | ❌ 默认不生效（**能力缺失**） | 5xx 重试导致二次 decide 撞 409，被前端误当失败（改进点 7 缺口） | 本轮 grep `ApprovalController.java` 零命中；`client.ts:425-435` |
| G-16 | `risk_level` 在审批列表返回体展示 | **不存在** | ❌ 默认不生效（**部分缺失**） | `ApprovalItemResponse`（`:197-218`）已有 argumentDigest/targetResource/expiresAt（**FR-014 已落地，纠偏**），但无 `risk_level` ⇒ 抗 automation bias 弱 | `ApprovalController.java:197-218`；`GovernancePages.tsx:179-188`；改进点 5 |
| G-17 | 审批决策「缺省拒绝」`DecisionRequest.approve()` | 硬编码 `"APPROVE".equalsIgnoreCase(...)`，非配置项 | ✅ 真生效（代码常量） | decision 缺省/大小写不符/任意值一律 DENY（fail-closed）；不可配置为缺省批准（安全红线） | `ApprovalController.java:175-185`；D-6 |

**本维度配置项统计**：共 **17** 项 —— ✅真生效 **9**（G-01/02/03/08/09/11/12/13/17）/ ⚠️条件生效 **5**（G-04/05/06/07/10）/ ❌默认不生效 **3**（G-14/15/16 三项能力缺失）。其中 G-06 的 `mode=none` 为「有键但默认不接线」，已按「条件生效」计入 G-04~G-07 组。

**「默认不生效」项的共同根因（分三类）**：
1. **「有意未接线型」4 项（G-04/05/06/07）** —— `live-source-enabled=false` / `require-live-source=false` / `source.mode=none` / `rules: []`：机制齐备（表、端口、闸门、PARK/RESUME、审批台、唤醒、兜底），但**生产默认全拒 + 未接线告警**（§4.5 不一致 6、T4-A 站位）。处置不是「改配置」，而是 E-2「先接真实审批源再翻开关」。
2. **「能力缺失型」3 项（G-14 批量/SLA、G-15 幂等键、G-16 risk_level 展示）** —— **连配置位都没有**；是 §7 改进点 5/7 的内容，落地前本表中不存在。每个新键必须自带一个可消费的信号与一条会红的门禁（铁律 6）。
3. **「默认降级型」2 项（G-10 require-tenant-port、G-05 require-live-source）** —— 默认 false ⇒ 失效时只记日志/降级、不阻断；置 true 才 fail-closed。这是「安全」与「可用性」的取舍，**改默认值属行为变更**，须走 §7 P0/P1 评审与灰度，不在本附录擅自决定。

**是否属本项目已知铁律范围**：**属**。① 新铁律「运行期可达性审计」在配置维度的投影——「有键」≠「生效」，本表 G-04~G-07 是「键在但策略空/开关关」，G-14~G-16 是「键根本没有」；② 铁律 6 在配置维度的投影——新键落地后必须各配一条门禁（如「ttl/parked 倒置必启动报错」），否则无法证明生效。⛔ 本附录不提出任何违反铁律的修改建议：修复方向是「先接出口 + 先加键再加行为 + 每个新键自带门禁」，不是「直接翻默认值」或「把 fail-closed 改成放行」。

### 附录 H：场景级端到端时序推演（5 个关键场景）

> 每个场景给出：触发入口 → ASCII 时序（逐跳）→ 阶段表（阶段 / 参与组件 / 动作 / 数据落点 / 可观测信号 / 失败分支），带真实类名与 `路径:行号`。
> **覆盖类别**：H-1 高风险工具审批通过（正常主链路）/ H-2 审批拒绝（终态）/ H-3 审批超时（孤儿收敛）/ H-4 两人并发 decide（并发竞态 + SoD）/ H-5 PARK 后 Worker 重启恢复（崩溃恢复）。
> **锚点口径**：全部为本轮实际 `Read`/`Grep` 复核值；**四问可达性**列沿用附录 D。**本口径**：审批台与 worker 同进程装配（`WorkerExecutionConfig.java:807-813`），跨进程差异在图注单列。

#### H-1 场景：高风险工具审批通过（正常 PARK → RESUME 主链路）

- **触发入口**：模型返回 `ToolCallRequest`，`ToolInvocationGate` 判定 `WRITE_EXTERNAL` 需审批；或审批人 `POST /api/approvals/{id}/decision`（`ApprovalController.java:124`）
- **前置状态**：`run.state='RUNNING'`；`tool_operation` 已落 ledger；`public.approval` 无该 operation 的 CONSUMED 行

```
  Worker/闸门              PostgreSQL(run/approval)      审批人/API         WakeupConsumer       执行循环
      |                          |                          |                  |                  |
 (1)  |-- 风险判定 required ---->|                          |                  |                  |
      |   approvalPort.consume 未 CONSUMED → requestApproval                                    |
 (2)  |-- recordParked --------->| approval=PENDING                                             |
      |-- commitResult(WAITING_APPROVAL) + RUN_PARKED + releaseLease                            |
 (3)  |<-- 让出 worker/租约 ------| run.state=WAITING_APPROVAL                                   |
      |                          |                          |                  |                  |
 (4)  |                          |                    decide APPROVE:139-141 → approval=APPROVED|
      |                          |                          |-- wakeup:147-149 --------------->|
 (5)  |                          |                          |                  |-- claimParkedRunForResume:101
      |                          |<-- run.state=RUNNING, fencing+1, lease 重设 (JdbcRunClaimRepo:276-286)
 (6)  |                          |                          |                  |-- ledger.findInvocation:113
      |                          |                          |                  |-- 重建 ToolCallRequest:128-129
      |                          |                          |                  |-- loop.resume:132 -------->|
 (7)  |<-- 重入闸门，approval 已 CONSUMED → 工具执行 1 次，无二次模型调用 ---------------------|
 (8)  |-- commitResult(SUCCEEDED) + completed_at --------------------------------------------->|
```

**阶段表**：

| 阶段 | 参与组件 | 动作 | 数据落点 | 可观测信号 | 失败分支 |
|---|---|---|---|---|---|
| 1–2 判审与登记 | `ToolInvocationGate`、`RiskBasedApprovalPolicy:87`、`ApprovalPort` | risk=WRITE_EXTERNAL ⇒ required；`consume` 未 CONSUMED ⇒ `requestApproval`（`ToolInvocationGate.java:1590-1606`） | `approval.state=PENDING`、`argument_digest`/`target_resource`/`expires_at`（DDL `:1471-1474`） | 待办出现在 `GET /api/approvals`（`:94`） | `request()` 未覆写（E-10 默认空）⇒ 待办不可见；空策略 ⇒ FAIL_CLOSED 全拒 |
| 3 PARK | `RunExecutionLoop:1135-1143` | commit WAITING_APPROVAL → RUN_PARKED 事件 → releaseLease | `run.state=WAITING_APPROVAL`、`run_event.RUN_PARKED` | 事件流；⚠️ 无挂起时长指标 | fencing 失效 ⇒ 不释放租约、不当成挂起成功（fail-closed） |
| 4 决策 | `ApprovalController:124-166` | `requireAuthorized(APPROVAL_CONSUME):130` → `approvals.decide:139-141`（审批人来自令牌）→ `approval.state=APPROVED` | `approval.state`/`approver_id`/`decided_at` | `DecisionResponse{approvalId,newState}`（`:188`） | 过期/已决定 ⇒ 409（`:162-165`）；404 不区分存在性（`:158-160`） |
| 5–6 唤醒 | `ApprovalDecidedWakeupConsumer:82-135` | `claimParkedRunForResume:101` → `ledger.findInvocation:113` → 重建 `:128-129` → `loop.resume:132` | `run.state=RUNNING`、fencing+1、租约重设（`JdbcRunClaimRepository.java:276-286`） | 日志「审批决定已消费并恢复执行」（`:133`） | Run 不在 WAITING_APPROVAL ⇒ `NOT_PARKED` 丢弃（`:103-109`）；调用反查不到 ⇒ `FAILED`+`RESUME_INVOCATION_NOT_FOUND`（`:114-124`） |
| 7–8 续跑 | `RunExecutionLoop.resume:2073-2109` | 重入闸门（approval 已 CONSUMED 放行）→ 工具执行 1 次 → SUCCEEDED | `tool_operation`、`run.state=SUCCEEDED`、`completed_at` | `run_event.RUN_SUCCEEDED` | 恢复后仍判挂起 ⇒ 重新 PARK（`:2100-2109`），不重跑模型 |

- **可观测落点**：`run.state` / `run_event`（RUN_PARKED → … → RUN_SUCCEEDED）/ `approval.state` / `tool_operation` / `wakeup` 日志；**缺口**：无「挂起时长 / 唤醒延迟」Gauge（附录 D-05/D-07）。
- **排查断点**：`RunExecutionLoop.java:1135`（PARK 提交）、`ApprovalController.java:147`（wakeup 调用）、`ApprovalDecidedWakeupConsumer.java:101`（抢回租约）、`:113`（反查调用）、`JdbcRunClaimRepository.java:285`（WHERE state 条件）。

#### H-2 场景：审批拒绝（终态，停轮询）

- **触发入口**：审批人 `POST decision` 带 `decision="DENY"` + `reason`（必填）
- **前置状态**：`approval.state=PENDING`、Run 在 `WAITING_APPROVAL`

```
  审批人/API              PostgreSQL(approval/run)         WakeupConsumer        前端 SSE
      |                         |                            |                    |
 (1)  |-- decision=DENY:139 ---->| approval=REJECTED          |                    |
 (2)  |-- wakeup:147-149 ------------------------------->| wakeup 读 state        |
 (3)  |                         |                            |-- 仍 claimParkedRunForResume（:101）
      |                         |<-- state='WAITING_APPROVAL' ⇒ 抢回成功（拒绝不立即收敛 Run）
 (4)  |                         |                            |-- ledger.findInvocation:113 → 重建 → loop.resume:132
 (5)  |                         |                            |-- 重入闸门：approval=REJECTED ⇒ consume 返回 REJECTED ⇒ 收敛
      |                         |                            |   （ToolInvocationGate:1310/:1320，不执行工具）
 (6)  |<-- 200 + newState=REJECTED                                               |
 (7)  |                         |                            |                    |-- 前端见 REJECTED ⇒ 停轮询 + 展示原因
      |                         |                            |                    |   ⚠️ 当前 GovernancePages.tsx:89 文案「请稍后重试」
```

**阶段表**：

| 阶段 | 参与组件 | 动作 | 数据落点 | 可观测信号 | 失败分支 |
|---|---|---|---|---|---|
| 1 决策 | `ApprovalController:139-141` | `approve()` 返回 false（DENY 或缺省）→ `approval.state=REJECTED` | `approval.state`/`approver_id`/`decision_note` | `DecisionResponse.newState=REJECTED` | decision 缺省/大小写不符 ⇒ 一律 DENY（`:180`，fail-closed） |
| 2–5 唤醒后消费拒绝 | `WakeupConsumer:82-135` + `ToolInvocationGate` | wakeup 仍抢回 Run（`:101`，因 Run 还在 WAITING_APPROVAL）→ 重入闸门 → `approvalPort.consume` 返回 REJECTED ⇒ 工具不执行、Run 收敛 | `run.state` → FAILED/终态（消费侧） | 日志 `:133` outcome | 若 wakeup 未装配（`:52` 可空）⇒ Run 停在 WAITING_APPROVAL 靠 sweeper 收敛（H-3） |
| 6–7 前端终态 | `client.ts:425-435` + `GovernancePages.tsx:75-94` | 收到 `newState=REJECTED` ⇒ 应停轮询 + 展示原因 + 无重试按钮 | — | ⚠️ 当前 catch `:87-90` 文案「操作失败，请稍后重试」——**铁律 15 未闭合**（改进点 7 / E-7） | 前端把 REJECTED 当临时故障重试 ⇒ 无限轮询、合规幻觉 |

- **可观测落点**：`approval.state=REJECTED` 终态、`run.state` 收敛、`DecisionResponse.newState`；**缺口**：前端终态文案错误（`:89`）、无 `terminal_reason` 埋点。
- **排查断点**：`ApprovalController.java:180`（approve 判定）、`ApprovalDecidedWakeupConsumer.java:101`（拒绝仍唤醒）、`ToolInvocationGate.java`（consume REJECTED 分支）、`GovernancePages.tsx:88`（错误文案）。

#### H-3 场景：审批超时（孤儿 PARK 收敛）

- **触发入口**：`RunRecoverySweeper.sweep()`（`RunRecoverySweeper.java:173`，`@Scheduled` 30s，`:172`）
- **前置状态**：Run 停在 `WAITING_APPROVAL` 超过 `parked-timeout-seconds`（1800s，`application.yml:297`）；审批 TTL（15m，`:563`）已过

```
  时间线                 PostgreSQL(run)            RunRecoverySweeper       前端/运维
     |                        |                          |                    |
 (1) |-- 审批 TTL(15m) 到，审批过期（approval.state=EXPIRED，enforce_approval_state_transition DDL :330）---|
 (2) |-- 无人 wakeup（wakeup 失败/未装配）→ Run 仍 WAITING_APPROVAL                                    |
 (3) |-- PARK 超 1800s --------------------------------->|                    |
 (4) |                        |<-- failStaleParkedRuns(:267) WHERE state='WAITING_APPROVAL'(JdbcRunClaimRepo:602)
 (5) |                        |-- run.state=FAILED, error_code=APPROVAL_TIMEOUT, completed_at 置位         |
 (6) |                        |                          |-- 日志「收敛 N 个过期 PARK」(:269-270)         |
 (7) |                        |                          |                    |-- SSE 见 FAILED 推 done 终帧
     |                        |                          |                    |   ⚠️ 健康事实 healthy()( :155)无消费方
```

**阶段表**：

| 阶段 | 参与组件 | 动作 | 数据落点 | 可观测信号 | 失败分支 |
|---|---|---|---|---|---|
| 1 审批过期 | DDL `:330` 触发器 | `approval.state: PENDING→EXPIRED`（合法迁移） | `approval.state=EXPIRED` | 需 `GET /api/approvals?state=EXPIRED` 查 | 前序无自动置 EXPIRED 实现（改进点 5/E-5 补） |
| 2–3 孤儿滞留 | `ApprovalController.java:52`（wakeup 可空） | 决定已落库但唤醒失败/未装配 ⇒ Run 永久 WAITING_APPROVAL（靠 sweeper） | `run.state=WAITING_APPROVAL` | ⚠️ 无挂起时长告警 | 如无 sweeper ⇒ AP6 暂停态泄漏 |
| 4–5 收敛 | `RunRecoverySweeper.java:267` + `JdbcRunClaimRepository.java:589-602` | `failStaleParkedRuns(tenantId, parkedTimeout)` → WHERE state='WAITING_APPROVAL' + 到期 ⇒ FAILED | `run.state=FAILED`、`error_code=APPROVAL_TIMEOUT`、`completed_at`（DDL `:2757`） | 日志 `:269-270` | parked-timeout < ttl ⇒ 人未批 Run 先失败（配置漂移，注释约束无校验） |
| 6–7 终态可见 | SSE（维度 04）+ 健康事实 | 推 `done` 终帧带 `terminal_reason`；`healthy()`（`:155-156`）**无消费方** | — | ⚠️ 「恢复链路已死」不可告警 | `recovery.enabled=false`（`:184-187`）或租户端口降级 ⇒ 孤儿永久停留 |

- **可观测落点**：`run.state=FAILED` + `error_code=APPROVAL_TIMEOUT` + sweeper 日志；**缺口**：`healthy()` 无 HealthIndicator（附录 D-12）、无超时 Gauge。
- **排查断点**：`application.yml:297`（parked-timeout）、`:563`（ttl）、`RunRecoverySweeper.java:267`、`JdbcRunClaimRepository.java:602`、DDL `:2757`（completed_at 一致性 CHECK）。

#### H-4 场景：两人并发 decide 同一治理发布申请单（并发竞态 + SoD）

- **触发入口**：两个 APPROVER 几乎同时 `POST` 对同一 `requestId` 的 `decide(APPROVE)`（经 `ConfigPublishWorkflowService.java:324`）
- **前置状态**：`cap_config_publish_request.state=PENDING`、`requesterId` 与两审批人均不同

```
  审批人A(线程1)       审批人B(线程2)       PostgreSQL(request 行锁)      审计
      |                   |                        |                       |
 (1)  |-- decide:241 ---->|                        |                       |
      |                   |-- decide:241 --------->|                       |
 (2)  |                   |                        |<-- loadForUpdate:251/:376 FOR UPDATE
      |                   |                        |   ★ A 持锁，B 阻塞等待
 (3)  |                   |                        |-- A: requester≠approver(:253-254), state=PENDING(:256)
      |                   |                        |-- updateDecision:263 → state=APPROVED
      |                   |                        |-- audit.recordRequired 同事务:267-273 ---------->| 1 条
 (4)  |<-- COMMIT，A 成功 |                        | 释放行锁
 (5)  |                   |<-- B 获锁，loadForUpdate 读到 state=APPROVED ⇒ StateConflictException:257-259
 (6)  |                   |<-- 409/冲突（B 失败，无第二条审计）
 (7)  |                   |                        |-- DB CHECK two_person_ck(:1882) 兜底（若有人 DB 直连改 approver_id=requester_id ⇒ CHECK 拒绝）
```

**阶段表**：

| 阶段 | 参与组件 | 动作 | 数据落点 | 可观测信号 | 失败分支 |
|---|---|---|---|---|---|
| 1–2 并发进入 | `ConfigPublishWorkflowService.java:324` → `JdbcConfigPublishRequestRepository.java:241` | 两线程同事务调 `loadForUpdate`（`:251/:372-381`） | — | — | 无 FOR UPDATE ⇒ 两人都读 PENDING ⇒ 双重 APPROVED（Sev-0） |
| 3 胜者提交 | `JdbcConfigPublishRequestRepository.java:253-273` | 应用层自我审批拦截 `:253-254` + 状态前置 `:256-259` + `updateDecision:263` + 审计同事务 `:267-273` | `state=APPROVED`、`approver_id`、`decided_at`、审计 1 条 | — | 审计写失败 ⇒ 事务回滚（无审计的批准不存在） |
| 4–5 败者冲突 | 同上 `:257-259` | B 读到非 PENDING ⇒ `StateConflictException` | 无第二条审计 | 409/冲突响应 | 无状态前置 ⇒ B 也 update ⇒ 覆盖（门禁反证点） |
| 6–7 SoD 兜底 | DDL `:1882` + 应用层 `:253-254` | DB 直连绕过应用层改 `approver_id=requester_id` ⇒ CHECK 拒绝 | — | `restrict_violation`/CHECK 违例 | 只有应用层无 DB CHECK ⇒ 直连 SQL 可自我审批 |

- **可观测落点**：`cap_config_publish_request.state` 唯一、审计恰 1 条；**缺口**：**没有本场景的专项 DB IT**（附录 D-27/D-28 判定❌；P0 验收报告 §7.1 / MEMORY.md:62 未闭环）——**属验证补强，非缺陷修复**：`FOR UPDATE` + 状态前置 + `two_person_ck` 静态保证状态机不被破坏，缺的是 `CountDownLatch` 真并发夹具（改进点 4 / E-4）。
- **排查断点**：`JdbcConfigPublishRequestRepository.java:376`（FOR UPDATE）、`:253-254`（SelfApproval）、`:256-259`（StateConflict）、`:267`（审计同事务）、DDL `:1882`（two_person_ck）。

#### H-5 场景：PARK 后 Worker 重启恢复（崩溃恢复）

- **触发入口**：Run 在 `WAITING_APPROVAL` 期间原 Worker 进程被 kill；审批决定已落库但 `wakeup` 未执行（崩溃窗口，`ApprovalController.java:44-50` 注释）
- **前置状态**：`run.state=WAITING_APPROVAL`、`fencing_token=n`、租约已释放（`:1143`）；`approval.state=APPROVED`

```
  旧Worker(已崩溃)      PostgreSQL(run)        新Worker/WakeupConsumer      sweeper
      |                     |                        |                       |
 (1)  | ✖ kill（决定已落库，wakeup 未执行）           |                       |
 (2)  |                     |  Run 仍 WAITING_APPROVAL（持久化挂起，不依赖进程存活）|
 (3)  |                     |                        |-- 新进程启动装配 approvalWakeupPort(:807-813)
 (4)  |                     |                        |  方案A：补偿重放 wakeup → claimParkedRunForResume(:101)
      |                     |<-- state=RUNNING, fencing=n+1, 租约重设（:276-286）    |
 (5)  |                     |                        |-- ledger.findInvocation:113 → 重建 → loop.resume:132
      |                     |                        |-- 工具执行 1 次（幂等：operationId 唯一）
 (6)  | 旧进程若复活        |                        |                       |
      |-- 用旧 fencing=n 尝试写 → WHERE fencing_token=? 影响 0 行（:284） ⇒ 被拒
 (7)  |                     |                        |  方案B：无补偿重放 ⇒ 靠 sweeper 超时收敛 FAILED（H-3）
```

**阶段表**：

| 阶段 | 参与组件 | 动作 | 数据落点 | 可观测信号 | 失败分支 |
|---|---|---|---|---|---|
| 1–2 崩溃窗口 | `ApprovalController.java:44-50` 注释 | 决定已落库、同步 wakeup 未执行 ⇒ Run 停在 WAITING_APPROVAL（**持久化挂起不依赖进程存活**） | `approval=APPROVED`、`run.state=WAITING_APPROVAL` | — | 若 PARk 状态不持久化 ⇒ Run 丢失（本项目已持久化，D-01/D-05） |
| 3–5 新进程恢复 | `WorkerExecutionConfig.java:807-813` + `WakeupConsumer:101-132` | 新装配的 wakeup 重放（或补偿）→ 抢回 Run（fencing+1）→ 反查 ledger → `loop.resume` 不重跑模型 | `run.state=RUNNING`、`fencing_token=n+1`、`tool_operation` 1 行 | 日志 `:133` | 跨进程部署下原同步 wakeup（`:147-149` 失败仅 warn）可能丢 ⇒ 需补偿重放或 sweeper（维度 12） |
| 6 旧世代隔离 | `JdbcRunClaimRepository.java:284` + DDL `:462-484` | 旧进程用 fencing=n 写 ⇒ WHERE 不匹配 + 触发器拒绝 token 回退 ⇒ 0 行 | 无双写 | `skippedStaleGeneration` | 无 fencing ⇒ 旧/新世代双写 = Sev-0（重复副作用） |
| 7 兜底 | `RunRecoverySweeper.java:267` | 无补偿重放时，PARK 超 1800s ⇒ FAILED（APPROVAL_TIMEOUT） | `run.state=FAILED` | sweeper 日志 | `recovery.enabled=false` / 端口降级 ⇒ 永久挂起（H-3） |

- **可观测落点**：`run.state` 从 WAITING_APPROVAL→RUNNING、`fencing_token` 递增、`tool_operation` 唯一、`wakeup` 日志；**缺口**：OTel 跨进程 trace 未贯通（P0 验收报告 §7.1，`TracedRunExecutor` 只覆盖「领租约→执行结束」）。
- **排查断点**：`ApprovalController.java:44-50`（崩溃窗口）、`WorkerExecutionConfig.java:807-813`（新装配）、`ApprovalDecidedWakeupConsumer.java:101/:113/:132`、`JdbcRunClaimRepository.java:284`（fencing 条件）、`RunRecoverySweeper.java:267`（兜底）。

> **H 场景口径**：五张图的「失败分支」列凡标「缺口」者，均为本轮静态复核结论（附录 D 四问判定），**未在真实运行时注入验证**（诚实边界声明第二节）；实际时延、行数、配额消耗未实测。**跨进程补偿重放**（H-5 方案 A）目前**未实现**，现网靠 sweeper 超时兜底（方案 B）——这是 11 号报告已登记的 OTel 跨进程 trace 缺口在 HITL 语境的投影。

### 附录 I：红队反例用例集（10 条，可直接落成测试）

> 每条用例可转为 IT / 单测 / 静态守卫。**「现网是否会失败」列如实写现状**（未核实写「❓未核实」，不得臆断通过或失败）。
> **归因三分类**：`设计取舍`（有意为之，非缺陷）/ `能力缺失`（应补，含验证缺口）/ `证据缺口`（本轮未能核实，需补证）。
> **本轮未运行任何测试**——「现网是否会失败」列凡涉及运行时行为的，一律标注「**未实测**（静态控制流结论）」。

| 编号 | 反例场景 | 触发步骤 | 期望行为 | 现网是否会失败 | 对应改进点/守卫 |
|---|---|---|---|---|---|
| I-01 | **两人并发 decide 绕过 two_person_ck**（治理审批并发） | 两个 APPROVER 用 `CountDownLatch` 对同一 `requestId` 并发 `decide(APPROVE)`；另用 DB 直连 `UPDATE approver_id=requester_id` | ① 恰 1 成功 + 1 `StateConflictException`；② 审计恰 1 条、终态唯一；③ DB 直连改自我审批 ⇒ CHECK 拒绝（DDL `:1882`） | ❓ **机制层不会失败**（`FOR UPDATE` `:376` + 状态前置 `:256-259` + `two_person_ck :1882` 静态合推）——**未实测**；❌ **门禁层缺失**：无并发 decide 专项 IT（P0 验收报告 §7.1） | 改进点 4 / E-4；静态守卫 `decideSql contains "FOR UPDATE"`；反向：删 `:376` ⇒ 两次 APPROVED 必红 |
| I-02 | **404/403/401 伪装成临时故障导致前端无限重试**（铁律 15） | 审批页 `decideApproval` 收到 `404`（不存在/无权限）、`403`（越权）、`401`（未登录）；SSE 订阅收到 404 | ① 停轮询、无重试请求发出；② 展示**终态**文案（禁「正在处理 / 网络问题 / 稍后再试」）；③ 仅 5xx/超时有界重试 ≤3 且先查状态 | ❌ **现网会失败（缺陷）**：`GovernancePages.tsx:87-90` catch 对 4xx 文案「操作失败，请稍后重试」（`:89`）；11 号报告 I-02 已核实 SSE `eventStream.ts:205-223` 只判 401、403/404 每 10s 永久重连；⚠️ 静态控制流结论未浏览器实测 | 改进点 7 / E-7；契约表 + 分流门禁；反向：删终帧/改回可重试 ⇒ 红 |
| I-03 | **ReviewAttributionHook NoOp 导致归因丢失**（真实缺口） | `resolve()` 一个 `rootCause=PROMPT` 的复核任务；观察 `review_attribution` 是否落行 | ① 期望：回调 1 次 + 落 1 行；② 现网：接线完整（`JdbcReviewTaskRepository.java:132-144`）但生产无 Bean（`ApplicationConfiguration.java:302` `getIfAvailable()` 返回 null）⇒ **0 回调、0 落行** | ❌ **现网会失败（能力缺口）**：全仓 grep `implements ReviewAttributionHook`（main）零命中（附录 D-35/36/37）；判为「预留扩展点 + 未闭环」，**MEMORY「NoOp」已被复核纠偏** | 改进点 3 / E-3；注入计数 Bean 断言 1 次、移除断言 0 次；反向：删 `@Component` ⇒ 红 |
| I-04 | **审批超时后旧 RESUME 消息复活终态 Run** | Run 超时收敛为 FAILED（`APPROVAL_TIMEOUT`）后，旧 approve 决定触发一次迟到的 `wakeup(tenantId, approvalId)` | ① `claimParkedRunForResume` 影响 0 行（WHERE `state='WAITING_APPROVAL'`，`JdbcRunClaimRepository.java:285`）⇒ `NOT_PARKED` 丢弃（`:103-109`）；② Run 仍 FAILED、`completed_at` 不变；③ 工具不重复执行；④ 无 `SUCCEEDED` 两次 | ❓ **代码层不会失败**：fencing（`:284`）+ state 条件（`:285`）静态保证终态 Run 抢不回；**未实测**。⚠️ 对照 AWS Step Functions「超时作废 token」（附录 F）证实这是正确的状态侧作废 | 守卫 I-10 + D-09/D-10；反向：撤 `state='WAITING_APPROVAL'` 条件 ⇒ 终态 Run 被唤醒 ⇒ 红；H-3/H-5 场景 |
| I-05 | **自我审批（maker=checker）** | APPROVER 审批自己 `requesterId` 的发布申请（经 API 与 DB 直连两条路径） | ① API ⇒ `SelfApprovalException`（`:253-254`）；② DB 直连绕过应用层 ⇒ `two_person_ck` CHECK 拒绝（DDL `:1882`）；③ 无审批记录生成 | ❓ **代码层不会失败**：应用层 + DB CHECK 双保险（§2.1 框架 3、差异化 3）；**未实测** | 设计取舍；`ConfigPublishManagementIT.java:303-311` 单人 selfApproval 已覆盖；反向：DROP CHECK ⇒ DB 直连红 |
| I-06 | **审批绑定过松：批 A 工具执行 B 参数（参数替换重放）** | 拿到一个 `argument_digest` 对应的 APPROVED 审批，用不同参数/目标资源/`authz_version` 重放 `consume` | ① `ApprovalPort.consume` 返回 `ARGUMENT_MISMATCH`/`TARGET_MISMATCH`/`RUN_CANCELLED`（E-10）；② 工具未执行、零副作用；③ 治理期双哈希（`approval_scope_hash`/`effective_args_hash`/`closure_fingerprint`，DDL `:1713-1716`）重验拒绝 | ❓ **代码层不会失败**：两次 FOR UPDATE + 绑定校验（§4.2 D-4）；**未实测** | 设计取舍；差异化 2；反向：撤消费时重验 ⇒ 参数替换成功 ⇒ 红 |
| I-07 | **恢复重跑模型 / 重复扣预算（AP5）** | 审批通过后观察 `wakeup` 是否重新调模型；对同一 operation 二次 `resume` | ① `ledger.findInvocation` 按 operationId 反查（`:113`）、重建 `ToolCallRequest`（`:128-129`），模型调用 0 次（类注释 `:26-31`）；② `tool_operation` 该 operationId 仅 1 行；③ 预算不二次扣 | ❓ **代码层不会失败**：`loop.resume` 不重跑模型（D-2/差异化 5）；**未实测**；⚠️ 泛化 interrupt 语境下仍有边界（§5.3） | 设计取舍；改进点 1（幂等扩展）；反向：让 resume 重新调模型 ⇒ 红 |
| I-08 | **空策略未接线却默认放行（fail-closed 绕过）** | 清空 `source.policy.rules`（`application.yml:585`）、`live-source-enabled=false`（`:569`）、配未知 `source.mode` | ① 所有需审批工具 `FAIL_CLOSED`（`RiskBasedApprovalPolicy.java:210/:214`）、零副作用；② 未知 mode ⇒ 拒启；③ `require-live-source=true` 未接线 ⇒ 拒启（`ApprovalReadinessGuard.java:60-81`） | ❓ **代码层不会失败**（默认全拒），但 ❌ **现网生产可用性失败**：默认配置下 `WRITE_EXTERNAL` 一律拒、真实审批台未接线（§4.5 不一致 6）；**未实测** | 改进点 2 / E-2；fail-closed 反证；反向：把 `FAIL_CLOSED` 改成放行 ⇒ 红 |
| I-09 | **审批台不展示风险等级 + automation bias（AP7）** | 检查 `GET /api/approvals` 返回体与审批页展示字段 | ① 期望：展示 `risk_level`/参数摘要/目标资源/有效期 + 拒绝醒目入口；② 现网：`argumentDigest`/`targetResource`/`expiresAt` **已展示**（`ApprovalController.java:197-218`、`GovernancePages.tsx:179-188`）——**「不展示参数摘要」已被复核纠偏**；但 `risk_level` 未展示、无拒绝率观测（AP1） | ❌ **部分失败（能力缺口）**：`risk_level` 与 A5 有效性观测缺失；参数摘要展示已达标 | 改进点 5 / E-5；展示增强 + `reject_ratio` 告警 + 抽样复核；反向：撤告警 ⇒ 橡皮图章用例红 |
| I-10 | **终态 Run 被 wakeup 抢回导致双副作用** | 构造 Run 已 `SUCCEEDED`，投递一条指向它的审批决定 + 迟到 wakeup | ① `claimParkedRunForResume` 影响 0 行（终态不匹配 `:285`）⇒ `DUPLICATE_DISCARDED` 等价丢弃；② `run.state` 仍 SUCCEEDED、fencing 不变；③ 无新增 `tool_operation`/`run_step` 行 | ❓ **代码层不会失败**：状态条件（`:285`）+ 触发器（DDL `:462-484`）静态保证；**未实测** | 守卫 I-04；反向：撤 `state='WAITING_APPROVAL'` ⇒ 终态 Run 被迁回 ⇒ 红 |

**6 类必测场景覆盖对照**（铁律 6 / 第三轮补充规范）：① **越权 / 自我审批** = I-05；② **并发竞态** = I-01、I-10；③ **终态误报 / 无限重试** = I-02、I-04；④ **能力缺失** = I-03、I-09；⑤ **fail-closed 绕过** = I-08；⑥ **绑定重放 / 重复副作用** = I-06、I-07 —— **6 类全覆盖**。
**另含两条规范点名要求的真实陷阱**：**「两人并发 decide 绕过 two_person_ck」= I-01**；**「404/403/401 伪装临时故障」= I-02**；**「ReviewAttributionHook NoOp 归因丢失」= I-03**；**「审批超时后旧 RESUME 复活终态 Run」= I-04**。

**反向验证口径**：每条用例必须能回答「撤掉修复点后，这条用例会不会变红？」——
- I-01：删 `FOR UPDATE`（`:376`）⇒ 两次 APPROVED ⇒ **红**；DROP `two_person_ck`（`:1882`）⇒ DB 直连自我审批成功 ⇒ **红**；
- I-02：把 4xx 改回可重试 / 删 SSE 终帧 ⇒ 重试/轮询不停止 ⇒ **红**；
- I-03：删 `DefaultReviewAttributionHook` 的 `@Component` ⇒ 回写 0 行 ⇒ **红**；
- I-04 / I-10：撤 `claimParkedRunForResume` 的 `state='WAITING_APPROVAL'`（`:285`）或 fencing（`:284`）⇒ 终态 Run 被唤醒 ⇒ **红**；
- I-05：撤应用层 `SelfApprovalException`（`:253-254`）或 DB CHECK（`:1882`）⇒ 自我审批通过 ⇒ **红**；
- I-06：撤消费时参数/有效期重验 ⇒ 替换参数成功执行 ⇒ **红**；
- I-07：让 `resume` 重新调模型 ⇒ 模型调用计数 > 0 ⇒ **红**；
- I-08：把空策略 `FAIL_CLOSED` 改为放行 ⇒ 需审工具被执行 ⇒ **红**；
- I-09：撤 `risk_level` 展示/`reject_ratio` 告警 ⇒ 橡皮图章不可发现 ⇒ **红**。

**建议落地优先级**：**P0** 用例 6 条（I-01、I-02、I-03、I-04、I-05、I-08——覆盖并发、终态语义、归因、fail-closed 四类硬门禁）/ **P1** 用例 4 条（I-06、I-07、I-09、I-10——绑定重放、恢复幂等、有效性、终态隔离）。⚠️ **优先级依据**：I-01/I-03 是 P0 验收报告与 MEMORY 明确点名的两个未闭环/缺口项；I-02 是铁律 15 在 HITL 语境的直接红线；I-04 是跨进程唤醒与超时兜底的交汇陷阱。凡「现网会失败」的用例（I-02/I-03/I-09）**修复前须写成「记录当前行为」的已知缺口用例并注明恢复条件，修复后翻转为正向断言**——⛔ **不得为了让 CI 绿而删除**（删除即 A-8「日志里有 = 被看见了」的另一种形态）。

---

## 诚实边界声明

本报告的结论按可靠性分为六级，请据此取用。

**一、基于源码 / 配置 / DDL 逐行实证（可直接引用，行号已核对）**
- §4.1～§4.4 的全部架构描述、D-1～D-13 决策、E-01～E-40 证据表，均通过 `Read` / `Grep` 逐行阅读 `RunState.java`、`RunExecutionLoop.java`、`ApprovalDecidedWakeupConsumer.java`、`ToolInvocationGate.java`、`RiskBasedApprovalPolicy.java`、`ApprovalController.java`、`AuthorizationService.java`、`AgentOverrides.java`、`ConfigPublishWorkflowService.java`、`JdbcConfigPublishRequestRepository.java`、`JdbcApprovalRepository.java`、`JdbcRunClaimRepository.java`、`JdbcReviewTaskRepository.java`、`ReviewAttributionHook.java`、`ApplicationConfiguration.java`、`ApprovalReadinessGuard.java`、`WorkerExecutionConfig.java`、`RunRecoverySweeper.java`、`application.yml`、`01_platform_ddl.sql`、`ConfigPublishIT.java`、`ConfigPublishManagementIT.java` 直接确认。
- §4.5 的 7 处不一致中，(1)(2)(3)(4)(5)(6) 六处是**项目源码自证**：`WAITING_APPROVAL` PARK/RESUME 闭环（E-01/E-04/E-06/E-07/E-37）、`ReviewAttributionHook`「接线完整但生产无 Bean」（E-32/E-33/E-34）、`decide` 的 `FOR UPDATE`+状态前置（E-17）、`ApprovalReadinessGuard:23` 过时注释与 `ApprovalController:124-149` 实现对照、`application.yml:569-580` 默认未接线——均非本报告推断。
- (1) 的「重大发现」判定成立依据：既有梳理报告第 13 章（194-205）、竞品报告维度 13（391-420）、MEMORY（48-49）**均未出现** `WAITING_APPROVAL` / `claimParkedRunForResume` / `ApprovalDecidedWakeupConsumer` 等字样，而源码中这些是已实现的生产路径。

**二、基于读代码推断（未运行时验证，已在正文标注）**
- §4.5 不一致 3「两人并发通过不会破坏状态机」由 `FOR UPDATE` + 应用层 `state != PENDING → StateConflictException` + `two_person_ck` 三者**静态合推**，属强静态证据，但**本报告未实跑双线程 decide**；§7 改进点 4 的门禁正是把它设计成待验证实验。
- §4.5 不一致 6「生产默认全拒」由 `source.mode=none` + `PolicyEngineApprovalSource` 空策略 `FAIL_CLOSED` + `ApprovalReadinessGuard` 不阻断三者合推，**未实跑一次真实 `WRITE_EXTERNAL` 工具验证其确被拒**；§7 改进点 2 门禁补此验证。
- §8.1 耦合 4「审批台与 worker 分部署时同步 wakeup 可能丢唤醒」属**潜在张力**：依据是 `ApprovalController.java:147-149` 的 `wakeup` 失败仅 `log.warn`，**未运行时验证跨进程影响**。
- §4.5 反证口径中引用的 `ApprovalDecidedWakeupConsumerTest` / `RunExecutionLoopTest` / `ToolInvocationGateApprovalRequestTest`，本报告**只据文件名与 `RunState`/`RunExecutionLoop` 注释推断存在对应守卫，未逐行验证断言内容**（附录 A 已标注）。
- `ConfigPublishService.java` 本轮输出过大（56KB）未完整展开，§4.1 链路 B 的 `apply` 细节由 `ConfigPublishWorkflowService`（E-16）与 `ConfigPublishIT`/`ConfigPublishManagementIT`（E-39/E-40）交叉支撑，属强证据但非逐行读尽。

**三、基于官方文档（URL 已给，抓取状态已标注）**
- §3.1～§3.7 竞品机制。**已成功抓取/核实**：LangGraph `interrupts`（含「resume 从节点开头重跑」原文）、Google ADK `adk.dev/tools-custom/confirmation/`（`request_confirmation`/`require_confirmation`/`FunctionResponse`/`/run` `/run_sse`/`invocation_id`）、OpenAI Agents SDK Guardrails（"pending approval interruption is emitted"、FunctionTool 需批准）、Agno `docs.agno.com/hitl/overview`（active_requirements / confirm() / reject() / continue_run()）、CrewAI `docs.crewai.com/en/learn/human-in-the-loop`（@human_feedback / webhook / Pending Human Input / /resume）、Anthropic 官方检索（canUseTool / PreToolUse / PermissionRequest）、AutoGen `UserProxyAgent` / `human_input_mode`。
- **经官方检索结果核对但未逐页抓取**：LangGraph `persistence` / `graph-api`、OpenAI `handoffs`、Anthropic `platform.claude.com/docs/en/agent-sdk/hooks` 全文、AutoGen 归档时间线、CrewAI `concepts/flows`。
- **官方未明示、按工程判定**：OpenAI Agents SDK「无通用 durable pause-resume 原语、暂停/恢复交宿主」——官方页未写该句，§3.2 已显式标注，未当作引文；`NodeInterrupt` 作为独立类名在该官方页未出现，§2.1 框架 5 已标注不引用为官方原语。
- **两处按 2026 现状更正**：① Anthropic 文档域名从 `docs.anthropic.com` 迁至 `platform.claude.com`/`code.claude.com`（与 12 号报告一致）；② AutoGen 2025-10 并入 Microsoft Agent Framework 进入 maintenance/归档（竞品报告已要求注明）。
- **诚实区分**：用户素材「OpenAI 无内建运行期 HITL」与竞品报告「Guardrails 内嵌审批」并不矛盾，§3.2 与 §4.5 不一致 7 给出「有 guardrail 级审批信号、无通用 pause-resume 引擎」的精确表述，未把任何一方拔高或贬低。

**四、基于公开但非官方来源（仅作旁证，不作事实依据）**
- four-eyes principle / maker-checker / alert fatigue / review fatigue **未能核实单一权威官方出处**，§2.1 框架 3/6 已标注「按公开工程实践归纳」，附录 B 亦标注，正文只作判别标准与合规动机的旁证，未据此断言任何框架的未公开行为。
- EU AI Act Art.14 / GDPR Art.22 / NIST AI RMF 的**条文要点**（automation bias、stop 按钮、双人复核、 meaningful intervention）来自公开法规/标准的通行解读与本项目前序核实，本轮**未逐页重抓 EUR-Lex / NIST PDF 全文**；引用以「法规全名 + 条款号 + 官方 ELI/出版物号」为据，未臆造条款内容作者。
- HITL 分类学（HITL/HOTL/HIC/HOTL-out）术语在 EU AI Act 讨论与 Shneiderman 专著中的对应关系，属学界通行用法，未逐字引自某一官方页。

**五、工程判断（本报告的主观部分，可反驳）**
- §1.2 的 L0–L3 四档分层、把本项目判为「治理域 L3 / 运行期 L2 下沿」、把 LangGraph/ADK/Agno 判为 L2，是**本报告自定的评估法**，不同评估者可能给出不同档位。
- §5.1 的「领先/持平/落后」语义是「在该能力项上机制更硬或覆盖更严」，**不等于整体产品更优**；本项目在开发者体验、生态广度、托管运行时的落后是前序 12 份报告的共识，本报告未重复展开。
- §2.1 框架 4 把用户素材笼统的「Hadfield-Menell 2016 / off-switch / Trolley Problem」**拆分为** CIRL (NIPS 2016, arXiv:1606.03137) 与 off-switch game (IJCAI 2017, arXiv:1611.08219) 两篇，是本报告基于论文脉络的判断，可据原始文献反驳。
- §2.2 的 T1～T8 张力、§2.3 的 AP1～AP7 反模式清单、§6 的分级、§8.3「若从头重做」四条，均为**事后视角的主观归纳**；其中「不建议推倒重来、坚持改进点 1 路径 A」是承认迁移成本的现实判断，不代表现状设计在抽象上更优。
- §7 的 P0/P1/P2 优先级与排期建议基于「当前风险敞口 × 修复成本」的主观权衡，**未做量化风险建模**；7 个改进点的门禁数值（批量上限、`reject_ratio` 告警阈值、`pending_age_p95 > 0.8*ttl`、重试 ≤3 次、并发 50 次无 flaky）是**建议初值，须由压测/试运行基线替换**，不是实测结论。
- §4.5 不一致 5 把用户「新增 PAUSED 态」的方案**修正为「复用 `WAITING_APPROVAL` 增量泛化」**，是本报告基于源码事实提出的设计建议；是否接受「不新增枚举」的取舍，超出源码能证明的范围，属工程判断。

**六、本报告明确未做的事**
- 未运行任何测试、并发实验、压测、审批台交互或前端联调；所有「预期收益」均为**指标口径建议**，不是实测值。
- 未实跑双线程 `decide`、未实跑真实 `WRITE_EXTERNAL` 工具审批链路、未验证跨进程 `wakeup` 是否真的丢唤醒（均已在第二节标注为推断/待验证）。
- 未逐页抓取 EUR-Lex / NIST PDF 全文、未逐页抓取 Anthropic `hooks`、OpenAI `handoffs`、LangGraph `persistence`/`graph-api` 全文；相关描述以官方检索片段 + 竞品报告登记 URL 为据。
- 未逐行验证 `ApprovalDecidedWakeupConsumerTest` / `RunExecutionLoopTest` / `ToolInvocationGateApprovalRequestTest` / `ConfigPublishService.java` 的完整内容。
- 未评估「提供托管审批台」「通用 HITL 一等公民」的商业可行性；§9.2 远期路线图仅列为可评估项。
- 未越界认领 MEMORY.md:48 中与本维度无关的其他发布短板（多租户灰度属维度 02、预算闸门边界属维度 09、真实模型失败路径属维度 11）；本报告只解除其中与 HITL 相关的「治理审批并发」一项的验证缺口。
- 未对 §4.5 不一致 4（过时注释）与不一致 6（默认未接线）给出「已在源码中修复」的断言——本报告只**指出**问题，修复属 §7 改进点 2 的范围，尚未落盘。

**七、第三轮补充（§5.5 能力评分卡）的证据分级**（与上方一到六级并列，不替代它们）

本轮新增的 **§5.5 能力评分卡**按证据强度分三级说明：

**第一级 · 源码实证（锚点已本轮逐条 Read/Grep 复核，可直接引用）**
- §5.5.1 每格证据锚点均来自本轮实际复核：`RunState.java:42-54/:75`（PARK 态与跃迁）、`RunExecutionLoop.java:1123-1148`（PARK 挂起）、`:2073-2128`（`resume` 不重跑模型）、`ApprovalDecidedWakeupConsumer.java:82-136`（同步唤醒 + fail-closed）、`JdbcRunClaimRepository.java:271-287`（`WHERE state='WAITING_APPROVAL'` + fencing）、`RunRecoverySweeper.java:116/:267`（超时兜底）、`ReviewAttributionHook.java:18-28/:41-46`（接线完整、默认 NoOp）、`ApprovalReadinessGuard.java:73-84`（只告警不阻断）、`JdbcConfigPublishRequestRepository.java:253-259/:265-273/:372-377`（`FOR UPDATE` + 状态前置 + 审计同事务）、`RiskBasedApprovalPolicy.java:210/:214`（空策略 `FAIL_CLOSED`）、`ApprovalController.java:194/:203-215`（参数摘要展示）、`application.yml:563/:569/:571/:580/:584-585`（TTL 与未接线默认值）、`01_platform_ddl.sql:316/:1882/:1713-1716/:4685`（触发器 + 双 CHECK + 双哈希）、`GovernancePages.tsx:179-188`（前端展示）。
- 「无消费方 / 零命中」类判定为**全仓穷尽结果**：`application.yml` 无 `reject_ratio\|batch\|sla`、main 侧 `implements ReviewAttributionHook` 零命中（附录 D-35/36/37）——属结构性事实，非检索遗漏。

**第二级 · 基于读代码的推断（本轮新增，未运行时验证）**
- §5.5.4 四行「推翻/收窄既有判定」中，A3「完整 PARK/RESUME 闭环」、A1「两人并发状态机不被破坏」、A5「生产默认全拒」均为**静态控制流 + SQL 条件合推**，一律标注「未实测（需按门禁口径在实施阶段验证）」；未跑双线程 `decide`、未跑真实 `WRITE_EXTERNAL` 工具、未连库观察 `approval`/`cap_approval` 真实取值。
- A3 的「不重跑模型」（`loop.resume` 按 operationId 反查重建）经类注释（`:26-31`）与控制流双重确认，属**强静态证据**，但跨进程 wakeup 丢失的实际发生率、PARK 时长分布**未实测**。

**第三级 · 工程判断（本轮主观部分，可反驳）**
- **§5.5 的全部权重**（0.30/0.25/0.20/0.15/0.10）与**五轴分数**（3/1/3/3/1）——§1.3 只给评价轴与观测方式、**未给权重**；按《第三轮补充规范》兜底次序「强制程度 > 可审计性 > 失败可诊断性 > 运维成本 > 生态成熟度」映射，**映射关系本身就是本轮新设的判断**；换一套权重会改变总分，**权重与分数不是测量值**。
- **「竞品最高分」全部沿用第三章 / 附录 F 已核实的官方事实**（本轮未重新 WebFetch）；**A5 填「不评分」**——第三章 / 附录 F 未核实到任何竞品的官方审批 SLA/拒绝率告警指标，按规范不凭印象给分；附录 F 的降级标注（Anthropic 旧域重定向、CSRC SoD glossary 404、Temporal 旧 URL 404、NIST PDF 无 SLA 阈值）继续有效。

**本轮明确未做的事**：未运行任何测试（单测 / IT / 并发实验 / 压测 / 故障注入）；未连库；未重新抓取竞品官方文档；**未改写任何既有内容**——§1–§9、附录 A–I、上方六段分级与下方收官说明**一字未动**，所有与既有结论冲突之处以「本轮更正」在 §5.5 新增节内标注，不动原文。

---

> **收官说明**：本维度是 13 份维度深度报告的最后一份。全篇所有「我们的实现」结论均给出 `文件路径:行号`；所有竞品结论均给出官方 URL；所有理论结论均给出真实出处或显式降级标注。对本维度最重要的一条诚实结论是：**既有报告「缺运行期暂停-恢复」的判断需要精确化——源码里已有一套完整、持久化、不重跑模型、有超时兜底的专用 PARK/RESUME 闭环，真正缺的是把它泛化为「任意点中断 + 状态编辑」的抽象，以及让它在生产默认配置下真正接线并具备审批有效性观测。**





