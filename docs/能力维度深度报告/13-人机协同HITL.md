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

---

> **收官说明**：本维度是 13 份维度深度报告的最后一份。全篇所有「我们的实现」结论均给出 `文件路径:行号`；所有竞品结论均给出官方 URL；所有理论结论均给出真实出处或显式降级标注。对本维度最重要的一条诚实结论是：**既有报告「缺运行期暂停-恢复」的判断需要精确化——源码里已有一套完整、持久化、不重跑模型、有超时兜底的专用 PARK/RESUME 闭环，真正缺的是把它泛化为「任意点中断 + 状态编辑」的抽象，以及让它在生产默认配置下真正接线并具备审批有效性观测。**





