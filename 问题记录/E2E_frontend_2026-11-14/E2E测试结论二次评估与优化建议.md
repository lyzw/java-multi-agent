# E2E 测试结论二次评估 · 交互与业务流程优化建议

- **评估日期**：2026-11-14 16:10–16:25
- **评估对象**：`问题记录/E2E_frontend_2026-11-14/E2E测试报告.md`（373 行）及其声称的发布结论
- **评估方式**：复跑 API 探针、真实浏览器三角色入口走查、生产代码逐处核对（不依赖原报告自述）
- **新增证据**：`probe_eval_round2.py`（API 级 8 项）、`eval2_consistency.py` / `eval2_text_probe.py`（浏览器级）、`snapshots/eval2/*.txt`、`screenshots/eval2/*.png`、`eval2.json` / `eval2_text.json`
- **修复状态**：本评估的 N1–N6 / T1 / T2 / P0 已按用户裁决落地，逐项状态、验证证据与残留风险见 **§六（文末）**；§一–§五保留评估时的原始结论，未回改。
- **二轮优化（2026-11-14 17:46–18:02）**：用户指令「继续优化」，继续闭环残留项——存量租户批量补齐、治理登记漂移、平台重启与浏览器级 E2E 复跑、T3 真实手势复核，见 **§6.4**。
- **三轮优化（2026-11-14 18:06–18:35）**：用户再次指令「继续优化」——`run_step` 口径定性并修复、进度页 403/401 终态缺陷、旧报告 10 项遗留逐条重判，外加失败路径自动化门禁与 RAG 跨页存储冲突修复，见 **§6.5**。
- **四轮优化（2026-11-14 18:42–19:10）**：第三次指令「继续优化」，把 §6.5.6 唯一能在现有环境闭环的放行缺口「旧报告 10 项中问题 3/4/5/6/7/8/10 的行为级专项实测」做实。实测**推翻了问题 4 与问题 10 的「已修复」判定**——两者都是运行时不成立的实现，已修复并补 7 项交互级门禁；6/6 撤点反证转红。见 **§6.6**。
- **五轮优化（2026-11-14 19:35–2026-11-15 01:10）**：第五次指令「继续优化」，转去已覆盖区之外找真实缺陷——顶层错误边界缺失致白屏、全局异常无人接、配置恢复默认无确认、翻页失败整列表被换、AgentsPage 视图间错误串味、审计明细对非 Record 崩溃、登录回跳丢 query；7 处全部修复 + 行为级门禁 + 7/7 撤点反证。见 **§6.7**。

---

## 〇、一句话结论

**原报告的「核心链路已具备发布条件」在「单租户 / 单学生 / 单已发布 Agent / 人工补齐前置」这一窄口径下成立，但作为对外发布判定偏宽，应下调为「具备试点演示条件，不具备规模化发布条件」。** 更重要的是：本轮用 F6 那把「能力—依赖对齐」的尺子重新量全部受众入口，发现**至少 4 处同型错配仍未闭合**，其中 2 处直接影响家长/老师角色能否用这个产品。这不是新功能需求，而是上一轮「F1 已闭环」的结论下得过早。

---

## 一、对原测试结论的逐条复核

### 1.1 成立的结论（证据充分，维持）

| 原结论 | 复核裁定 | 依据 |
|---|---|---|
| F1/F2/F3/F6/F7/F8 六个修复均已落地且复测通过 | ✅ 成立 | 源码可见（`requester_id`、`AGENT_LIST` 双路径、`notFound` 终态、`RequireRole` 跳无权限页）；`fullchain.json` 11/11 全 True |
| 学生全链路浏览器级可达（登录→发起→进度→结果） | ✅ 成立 | `fullchain.json`、`screenshots/fullchain/*.png` |
| 受众数据隔离与 404 反探测生效 | ✅ 成立（本轮独立复跑） | `probe_eval_round2.py`：学生列表 10 条 / 管理员 13 条；家长读学生 Run → 404 |
| F4 是测试脚本假象而非前端缺陷 | ✅ 成立 | `recheck/` 证据链完整，代码复核 `useRagSearch` / `generalChatApi` 均有错误态 |
| 4 层前置缺口与「`cap_authz_version` / `budget_account` 无生产写入方」 | ✅ 成立，且本轮再次印证 | DB 中仍有 6 条 FAILED Run，`errorCode ∈ {BUDGET_EXHAUSTED, CONFIG_UNAVAILABLE}` |

### 1.2 需要收紧 / 修正的结论

**① 发布判定偏宽（主要问题）**

原报告 §7.4：「核心链路已具备发布条件，但仍有 1 项产品级待办需并行推进」。但同一份报告 §7.3 自认「**新环境/新租户默认不可用**（高·产品·未闭环）」，且该缺口的特征是**静默失败**：创建 Run 返回 201，随后 `FAILED`，前端只把 `CONFIG_UNAVAILABLE` / `BUDGET_EXHAUSTED` 原样显示给学生。

- 「已具备发布条件」与「任何新租户必然跑不起来」不能同时成立。发布判定必须绑定**范围**：
  - ✅ 成立的口径：**当前已人工补齐前置的 `t-e2e-fe` 单租户，学生自助链路可用于试点演示**；
  - ❌ 不成立的口径：**产品可交付给新租户 / 新环境**。
- 建议措辞改为：**「具备单租户试点条件，不具备规模化发布条件；放行前须关闭 P0（租户开通生产写入方）」**。

**② 「F1 已闭环」覆盖了三个角色，实际只闭环了一个**

原报告 §7.2-1 / §7.3 表述为「受众角色已具备自服务能力」「STUDENT/PARENT/TEACHER 正规实现」。本轮实测（`probe_eval_round2.py` C/D2/D3）：

| 主体 | `POST /api/runs` | `GET /api/runs` | 读学生创建的 Run | 结论 |
|---|---|---|---|---|
| STUDENT | 201 | 200（本人 10 条） | — | 可用 |
| PARENT | **403 ACCESS_DENIED** | 200 但 **0 条** | **404** | **核心链路不可用** |
| TEACHER | 未授予 `RUN_CREATE`（矩阵静态确认） | 同样按 `requester_id=本人` 收窄 | 同 | **核心链路不可用** |

根因：`PARENT` / `TEACHER` 的权限集合是 `RUN_READ + EVENT_SUBSCRIBE + RESULT_READ + PROFILE_READ + AGENT_LIST`，**刻意不含 `RUN_CREATE`**；而 F1 的隔离规则把受众可见范围收窄到 `requester_id = 本人`。两者叠加 = 家长/老师**既不能发起、也看不到任何人的 Run**，登录后的「近期辅导」「检索记录」恒为空态。

而前端导航对三个受众角色**完全一致**（`snapshots/eval2/STUDENT_01_nav.txt` 与 `PARENT_01_nav.txt` 逐行相同，8 个入口全开，包括「发起辅导」）。所以家长点「发起辅导」→ 填完 4 个必填项 → 提交 403。这正是 F6「能力—依赖不匹配」在另外两个角色上的复现，上一轮只修了「学生看不到 Agent」，没有把尺子量到全部受众入口。

**③ 覆盖率口径偏乐观**

- 报告称「56/56 次访问，100% 路由可达性验证」——这里的 100% 是**「三角色 × 路由」的可达性**，不等于「角色矩阵全覆盖」：`VIEWER / AGENT_AUTHOR / WORKER / PLATFORM_ADMIN` 未做浏览器级验证。
- §7.1 自认旧报告 10 项中仍有 7 项「无法复现 / 待专项」（RAG 批量导入进度、审计导出、报表日期范围、SSE 泄漏等）。这些**没有关闭**，结论里不应与「完整 E2E」混用。
- `fullchain_browser.py` 的提交步骤使用 `form.requestSubmit()` 而非真实点击（工具口径，报告 §4.5 已诚实声明）。严格讲这一步是「规范内的表单提交」，不是 100% 真实用户手势；建议补一次真实 click 的复核以完全坐实。

**④ 报告自身的两处一致性瑕疵（文档质量）**

| 位置 | 问题 | 建议 |
|---|---|---|
| 摘要（第 6、10 行） | 把「受众看不到 Agent」标为 **F5**、「核心链路不可达」标为 **F6**、「404 未终态」标为 **F7**；但第五章实际是 **F6 / F7 / F8**，且 **F5 专指「与旧报告对照」**（第 260 行）。§7.2 用的又是正确编号。**摘要整体错了一位，且 F5 被双重占用** | 摘要统一改为 F6/F7/F8；F5 保持「旧报告对照」专用 |
| §7.4「可发布的前提（建议）」 | 前两条已打删除线并标 ✅，但行尾仍写「**剩余：跑通一次真实浏览器级全链路并取证**」「**剩余：学生视角浏览器级 UI 复测**」——这两项 §4.5 / §7.1 已完成，是自相矛盾的残留文本 | 删除两处「剩余」，或改为「已完成，证据见 §4.5」 |
| §1.5 / §2「未覆盖（受环境阻断）」 | 仍写「未发布任何 Agent」「发起辅导完整链路不可达」，未随 F3/F7 解除同步更新 | 标注为「首轮初始状态，已解除」 |
| §3.2 学生视角 | 仍写「学生自己的工作台也因数据接口 403 而显示无权执行」，未标为修复前状态 | 加删除线 + 指向 F1 解除记录 |

> 这些不影响技术结论，但会削弱报告的**可引用性**：评审者按摘要的 F5 去第五章会读到一个完全不相干的章节。

---

## 二、本轮新发现（按严重度）

> 编号 N1–N6，均为**交互 / 业务流程**层面，不属于上一轮 6 个功能缺陷的回归。

### N1【高·业务流程】家长 / 老师角色登录后核心链路不可用
- **证据**：`probe_eval_round2.py` C/D2/D3；`snapshots/eval2/PARENT_01_nav.txt`（导航含「发起辅导」）；`RoleBasedAuthorizationService.java:125-132`（PARENT/TEACHER 无 `RUN_CREATE`）。
- **用户视角**：家长用学校发的账号登录 → 工作台「近期辅导」空 → 点「发起辅导」→ 认真填完匿名编号、题目、解题过程、正确答案 → 提交 → **403 无权执行该操作**；点「检索记录」→ 永远空；想看孩子的分析 → **404 资源不存在**。
- **与报告结论的冲突**：§1.3 明确列了「学生 / 家长 / 老师」受众，`AppShell.tsx:70` 的 `rolesHint` 也是「学生 / 家长 / 老师」。角色存在、导航承诺、权限却不支持，属于产品模型未收敛，不是纯前端 bug。
- **需要产品裁决**：家长/老师究竟是
  - (A) **只读查看者**：应允许查看关联学生的 Run（需要 principal ↔ studentId 绑定关系，隔离维度从 `requester_id` 扩展为「本人发起 + 被授权查看的学生」）；或
  - (B) **代孩子发起者**：应补 `RUN_CREATE`，并让 `requester_id` 记录为家长、同时按 studentId 归属给孩子；或
  - (C) **本版本不开放**：则从登录受众、导航 `rolesHint`、账号体系中移除/隐藏，避免误导。
- **当前状态**：三种方案都没选，实现落在一个「能登录、看不见、发不出」的中间态。

### N2【中·交互】学生「学习画像」入口可见但功能被后端拒绝，且错误归因误导
- **证据**：`probe_eval_round2.py` A → 学生 `GET /api/profiles/{id}` = **403 ACCESS_DENIED**；`snapshots/eval2/STUDENT_学习画像.txt` 显示导航与输入框**正常渲染**；`RoleBasedAuthorizationService.java:106-107`（刻意不给 STUDENT `PROFILE_READ`，理由是 principal 与匿名编号无绑定）。
- **关键**：403 只在**学生填完编号提交之后**才发生，前面完全正常 —— 比 F8「404 显示正在处理」更隐蔽：用户完成了一次输入才撞墙。
- **错误文案错配**：`ProfilePage.tsx:94-98` 对任何错误统一给 `next="可能是网络问题，或这个匿名编号还没被辅导过。"`，把**权限拒绝**说成**网络故障 / 数据不存在**，并附「重试」按钮（`onRetry={reload}`）。用户会反复重试一个永远不可能成功的动作。这与 F8 是同一类「终态错误被伪装成临时故障」，但上一轮只修了进度页。
- **建议**（前端可独立做，成本低）：
  1. 按角色裁剪导航：STUDENT 不展示「学习画像」（若产品确认学生不该读画像）；
  2. 所有 `ApiError` 分支按 status 分流：403 → 「当前账号没有查看画像的权限，请联系管理员」且**不给重试按钮**；404/网络/超时 → 保留重试；
  3. 若产品希望学生能看自己的画像，则需先解决 N1 的绑定关系，再补资源级收窄。**顺序不能反**——先放权限再补绑定会造成租户内画像任意读。

### N3【中·交互】失败终态把内部错误码直接甩给受众，且「重新发起」是死循环
- **证据**：本轮 DB/API 中 6 条 FAILED，`errorCode ∈ {CONFIG_UNAVAILABLE, BUDGET_EXHAUSTED}`；`runState.ts:89-93` 的 `stateHint` 直接拼 `失败原因：${errorCode}`；`RunProgressPage.tsx:291-301` 对**所有非 SUCCEEDED 终态**一律给「重新发起这道题 / 回检索记录」。
- **问题**：
  - 学生看到 `失败原因：BUDGET_EXHAUSTED`，不知道是「我没钱了」还是「系统坏了」；
  - 而这两类失败的**根因是租户级预算账户 / 授权版本没开通**（F7 第 3、4 层），学生重试多少次都只会再失败一次；进度页却把「重新发起」作为唯一主行动 → **引导用户进入必然失败的循环**。
- **建议**：errorCode → 用户可读文案 + 差异化 CTA：
  | errorCode | 面向学生文案 | 下一步 |
  |---|---|---|
  | `BUDGET_EXHAUSTED` / `CONFIG_UNAVAILABLE` | 「服务配置尚未就绪，这不是题目或网络的问题」 | 「联系管理员」+ 回工作台；**不显示重新发起** |
  | `MODEL_TIMEOUT` / 临时类 | 「这次分析超时了」 | 「重新发起这道题」 |
  | 未知 | 兜底，附运行标识便于报障 | 回检索记录 |

### N4【中·交互】「重新发起这道题」不带原题，名实不符
- **证据**：`RunProgressPage.tsx:293` 为 `<Link to="/runs/new">`；`NewRunPage.tsx:63-70` 每次挂载都是 `EMPTY_FORM`。
- 按钮文案是「重新发起**这道题**」，但用户要重新输入 4 个必填字段（含长题干、解题过程、正确答案）。对失败重试场景，这是纯粹的摩擦；且重新填写会产生**不同的内容指纹 → 新幂等键 → 新 Run**（`idempotency.ts:86-104`），语义上其实是「再分析一道」，与文案不符。
- **建议**（二选一）：把表单内容通过 `location.state` / `sessionStorage` 带过去回填；或把文案改为「再辅导一道」。前者体验更好，但需确认合规红线 S-4「受控数据不落 localStorage」——`sessionStorage` 是既有 `run.idempotency` 已在用的载体，可行但需显式登记。

### N5【中·交互】租户级报表对学生开放，与「仅本人」的数据口径冲突
- **证据**：`snapshots/eval2/STUDENT_报表中心.txt` 学生看到「发起量 13 / 成功率 54% / 失败 6 / 知识点薄弱排行 Top 10 / 每日活跃与新增学生」；`GET /api/statistics/daily` 对学生返回 200；而 `GET /api/runs` 对学生只返回 10 条（本人）。
- **定性**：**不是越权**——`AppShell.tsx:81-84` 与报表页 lead 都明确写了「租户级聚合，不含学生可识别信息」，后端复用 `RUN_READ` 是有意设计。问题在**产品语义**：受众角色看运营指标没有业务价值，且同一用户两个页面对「辅导记录」给出 10 与 13 两个数字，容易被误读为数据错误。
- **建议**：受众角色下把「统计与报表」区收进治理面（与配置/审批/审计同组）；或在报表页顶部显式标注「以下为全租户汇总，与你自己在『检索记录』里看到的条数口径不同」。

### N6【低·交互】报表中心 / 学习画像 / 知识检索的错误态未按权限分流
与 N2 同源：`ErrorState` 的 `next` 文案是通用兜底。建议统一抽象一个 `errorGuidance(status, code)`，把「403 = 找管理员、5xx/超时 = 可重试」的纪律收敛到一处，避免每个页面各写一份（F8 的教训：同类问题散落在不同页面，修一处漏一处）。

---

## 三、测试过程本身是否需要优化？——需要，三条

### T1【高·方法论】「API 级 PASS」不能替代「业务链路验收」
上一轮的完整复盘印证了这个教训：
- F1 宣称「API 级 13/13 PASS，已闭环」→ 紧接着浏览器级复测连续暴露 **F6（看不到 Agent）→ F7（Run 跑不起来）→ F8（404 死等）** 三个阻断；
- 本轮又把同一把尺子量到其余入口 → 再发现 **N1 / N2**。

**根因**：验收断言的是「接口返回了预期状态码」，不是「用户能完成他登录进来要做的事」。建议把 E2E 门禁固化为**三级且以第三级为准**：
1. 单元/集成（代码正确）→ 2. API 契约（状态码正确）→ 3. **角色 × 任务**矩阵（用户目标可达）。
新增一条硬门禁：**任何角色，导航中可见的每个入口，都必须有一条「从该入口完成一次真实操作」的用例**；不可用的入口必须断言「不渲染」或「明确说明不可用」。这条规则能一次性覆盖 F6、N1、N2。

### T2【中·可复现性】E2E 的通过依赖人工补数，不能作为回归基线
F7 的 4 层前置里，2 层靠 `psycopg2` 手工 INSERT（`cap_authz_version` / `budget_account`），1 层靠启动环境变量 `AGENT_TENANT_SLUGS`，1 层靠重发治理 payload 带 `modelRef`。这套动作**没有脚本化**，且报告自己承认生产无写入方 → 换一个环境、换一个租户，同一套 E2E 必然红。
**建议**：提供幂等的 `provision_tenant_for_e2e` fixture（或推动 P0 的租户开通 API），并在 E2E 开始前做**前置自检**（缺哪项就明确报哪一项，而不是让 Run 静默 FAILED 后再倒查）。这也正是报告 P0 待办的测试侧价值。

### T3【中·工具口径】补一次真实点击复核
`agent-browser click` 对 `type=submit` 是合成事件、不触发表单默认提交（报告 §4.5 已诚实记录）。`requestSubmit()` 是规范路径、与用户点击等价，但终究不是真实手势。建议在发布前用一次真实 click（或 CDP `Input.dispatchMouseEvent`）复核提交链路，把这条「工具口径」彻底关掉。

### T4【低·环境】本轮收尾时后端进程已停止
16:19 之后 `localhost:8080` 返回 `health:000`（前端 5173 仍 200）。本轮所有 API 证据均在停止前（16:15–16:18）取得，结论不受影响；但**复跑本报告附录的命令前需先重启 platform-bootstrap**，且必须带 `AGENT_TENANT_SLUGS=t-e2e-fe`。

---

## 四、优化清单与优先级

| 优先级 | 项 | 归属 | 是否阻塞规模化发布 |
|---|---|---|---|
| **P0** | 租户开通生产写入方（`cap_authz_version` / `budget_account`）+ `AGENT_TENANT_SLUGS` 纳入部署清单 | 后端 / 运维（原报告已列） | **是** |
| **P0** | **N1：产品裁决家长/老师的用户模型**（三选一），并据此调整权限矩阵与隔离维度 | 产品 + 后端 | **是**（角色承诺无法兑现） |
| **P1** | **N2：403 错误分流 + 学生导航裁剪** | 前端（可独立做） | 否，但强烈建议 |
| **P1** | **N3：errorCode → 可读文案 + 差异化 CTA**（区分「可重试」与「找管理员」） | 前端 | 否，但影响体验与工单量 |
| **P1** | **T1：把「角色 × 任务」矩阵固化为 E2E 硬门禁** | 测试 | 否（流程改进） |
| **P2** | **T2：租户 provisioning fixture + E2E 前置自检** | 测试 / 后端 | 否（回归可靠性） |
| **P2** | **N4：失败重试带回原题 / 改文案** | 前端 | 否 |
| **P2** | **N5 / N6：报表入口按角色归位 + 统一错误指引抽象** | 前端 / 产品 | 否 |
| **P2** | **T3：真实 click 复核** | 测试 | 否 |
| **P2** | 旧报告 10 项遗留逐条以实测重判；RAG 检索端联调 | 测试 / 后端 | 否 |

> **边界声明**：**评估阶段**（§一–§五）未修改任何生产代码或权限矩阵。N1 涉及授权模型与资源级隔离，属产品/架构裁决范畴（与报告 F7「未擅自改动授权版本红线」同一纪律）。**修复阶段**在用户明确裁决后才落地，完整状态见下方 §六。

---

## 五、建议的发布判定（替换原 §7.4 首句）

> **本轮结论（二次评估修订）：当前环境（`t-e2e-fe` 单租户、单已发布 Agent、前置数据已人工补齐）的学生自助链路具备试点演示条件；产品整体不具备规模化发布条件。**
>
> 放行前须关闭：① P0 租户开通生产写入方（否则新租户 100% 静默失败）；② P0 家长/老师用户模型裁决（否则两个已声明的受众角色不可用）；③ 以实测重判旧报告 10 项遗留。
>
> 已验证的是「单租户、单 Agent、一次成功 Run」；多租户灰度、治理审批并发、预算闸门边界、PARENT/TEACHER 浏览器级链路、真实模型失败路径**均未覆盖**。
>
> **2026-11-14 17:40 更新**：上面 ①「租户开通生产写入方」与 ②「家长/老师用户模型裁决」已在 §六落地（② 裁决为「本版本不开放」，故受众范围缩减为学生）。但**③ 旧报告 10 项遗留重判、浏览器级 E2E 复跑、T3 真实 click 复核仍未完成**，且平台服务未重启，故发布判定**维持不变**：单租户试点演示可用，不具备规模化发布条件。
>
> **2026-11-14 18:02 复核**：平台已重启（`8080` health UP）、浏览器全链路 **9/9 PASS** 且真实 Run DB 终态 `SUCCEEDED`、T3 真实手势复核完成（见 §6.4）。**发布判定仍维持不变**：③ 旧报告 10 项遗留未重判，多租户灰度 / 治理审批并发 / 预算闸门边界 / 真实模型失败路径仍未覆盖，且本轮新观察到 `run_step` 状态与结果落库口径疑点（§6.4 待办）。当前仍仅限单租户试点演示。
>
> **2026-11-14 18:35 复核**：③ 旧报告 10 项遗留已逐条重判（0 条「当前未修真实缺陷」，1 条产品增强项），`run_step` 疑点也已定性为**跨层契约漂移**并完成读侧去歧义（§6.5）。**发布判定仍维持不变**：重判的 10 项中多项只有渲染层快照或源码复核、缺行为级实测；多租户灰度 / 治理审批并发 / 预算闸门边界 / 真实模型失败路径仍未覆盖；本轮新增的是**自动化门禁覆盖**，不等于真实环境与真实模型失败链已验证。当前仍仅限单租户试点演示。
>
> **2026-11-14 19:10 复核（四轮）**：旧报告 7 项行为级专项实测已完成，**并因此发现 2 项「已修复」判定不成立**（问题 4 结果页 C6 扫描可绕过、问题 10 批量导入进度运行时不可观察且失败静默成功），均已修复 + 交互级门禁 + 6/6 撤点反证（§6.6）。**发布判定仍维持不变**：① 该 7 项实测全是 jsdom / mock HTTP 层的行为级验证，**真实内存泄漏、真实性能、真实下载落盘、后端逐批收到**这四类仍无法由它证明（§6.6.5 诚实边界）；② 多租户灰度 / 治理审批并发 / 预算闸门边界 / 真实模型失败路径仍完全未覆盖；③ 问题 9 组件级健康明细仍待后端 G14 + 产品定夺；④ 本轮又暴露「静态源码守卫 / 渲染快照」曾让两处真实缺陷被判为「已修复」，说明**证据层级本身需要作为放行门槛**。当前仍仅限单租户试点演示。

---

## 六、修复落地状态（2026-11-14 17:00–17:40）

用户裁决：**N1 = (C) 本版本不开放**（家长/老师保留历史账号兼容，但本版本无业务入口）；修复范围全选 **P1 交互、P2 前端体验、T1/T2 测试门禁、P0 后端租户开通**。

### 6.1 逐项状态

| 项 | 状态 | 落点 |
|---|---|---|
| **N1** 家长/老师用户模型 | ✅ 已裁决并落地 | 未加 `RUN_CREATE`、未放宽 `requester_id` 隔离；`frontend/src/auth/capabilities.ts` 将 `PARENT/TEACHER` 任务集置空，`AppShell` 渲染 `UnsupportedRoleNotice` 整页终态说明（明确「不是登录失效/网络故障」「重新登录、刷新、重试都不会改变结果」），`RequireTask` 同步封住 URL 直达 |
| **N2** 学生画像 403 误报网络问题 | ✅ 已修 | `useProfile` 保留 `errorInfo`；`ProfilePage` 改用 `ErrorStateFromError`；`STUDENT` 不渲染「学习画像」入口 |
| **N6** 错误态统一抽象 | ✅ 已修 | 新增 `frontend/src/api/errorGuidance.ts` + `shared/states.tsx#ErrorStateFromError`；报表页 7 处错误态按 status 分流；403/404/401 终态**不给重试按钮**，5xx/网络/超时才可重试 |
| **N3** 失败码可读化 + 差异化 CTA | ✅ 已修 | `runState.ts#stateHint` 不再拼内部 `errorCode`；`runFailureGuidance()` 把 `CONFIG_UNAVAILABLE`/`BUDGET_EXHAUSTED`/`AUTHZ_UNAVAILABLE` 判为租户级故障 → 隐藏「重新发起」、引导回工作台；仅 `MODEL_TIMEOUT` 等临时类允许重试 |
| **N4** 重试带回原题 | ✅ 已修 | `frontend/src/features/run/retryForm.ts` 用 React Router `location.state` 白名单回填，**不落 localStorage/sessionStorage**（S-4）；刷新丢失 state 时 CTA 改「再辅导一道」 |
| **N5** 报表口径 | ✅ 已修 | `STUDENT` 不渲染报表入口；治理角色可见且页头 lead 写明「全租户汇总，与你在检索记录里看到的本人条数口径不同」 |
| **P0** 租户开通生产写入方 | ✅ 已修 | 新增单一真相源 `TenantRuntimeDefaults.insertRuntimePrerequisites`（`budget_account` + `cap_authz_version(tenant_id,'worker')`，`ON CONFLICT DO NOTHING`）；平台侧 `JdbcPlatformTenantRepository` 与管理侧 `AdminTenantRepository` 同事务调用；后者因走 `app_agent`(NOBYPASSRLS) 在写 FORCE RLS 表前显式 `TenantSessionBinder.bind`；`dev-seed.sh` 建号时补主体级授权版本行；`02_platform_seed.sql` 的 t-a/t-b 补 worker 行；`ItTenantProvisioner` 夹具同形；`platform.env.example` 纳管 `AGENT_TENANT_SLUGS` |
| **T1** 角色×任务硬门禁 | ✅ 已修 | `capabilities.ts` 单一真相源 + `RequireTask`；`frontend/tests/roleTaskMatrix.test.tsx` 固化「导航可见入口必须有真实任务用例 + 不可达入口不渲染」，含反向验证 |
| **T2** provisioning fixture + 前置自检 | ✅ 已修 | `scripts/provision_tenant_runtime.py`（幂等补齐 + `--check-only` 前置自检，缺项退出 1）、`scripts/e2e-preflight.sh`（E2E 硬门禁）、`scripts/check-tenant-registry.sh` / `CheckTenantRegistry.java` 从 4 项扩到 6 项「五处齐」；`docs/本地启动指南.md` §6.5.1 部署清单 |
| **P0 读侧纪律** | ✅ 有守卫 | 六处生产读点继续 `require()` fail-closed；`TenantRuntimeDefaultsArchitectureTest` 禁止任何一处降级为 `currentOrInitialize()`，并带扫描器反证 |
| **T3** 真实 click 复核 | ✅ 已做（18:00 二轮） | `t3_real_click_recheck.py` 三组手势对照：A 合成 click 不跳转、B 真实键盘手势（focus + Enter）成功提交，坐实「工具口径限制、非应用缺陷」 |
| **T4** 后端重启 | ✅ 已做（17:56 二轮） | 修正 `AGENT_TENANT_SLUGS` 单租户配置后重启 `platform-bootstrap`，`8080` health UP；浏览器级 E2E 全链路复跑 9/9 PASS |

### 6.2 本轮验证证据

| 层次 | 命令 / 结果 |
|---|---|
| 前端类型 | `npm run typecheck` 通过 |
| 前端全量 | `npm test`：**50 文件 / 729 用例全绿**（新增 `errorGuidance.test.ts` 13、`roleTaskMatrix.test.tsx`、`errorStates.test.tsx`） |
| 后端编译 | `mvn -o -f platform/pom.xml -pl agent-persistence -am … compile` / `agent-test-support … test-compile` 通过 |
| 后端全量单测 | `mvn -o -f platform/pom.xml test`：**13 个模块全部 BUILD SUCCESS，0 failures / 0 errors**（含新增 `TenantRuntimeDefaultsArchitectureTest` 10 用例） |
| 活库 IT | `ItTenantProvisionerIT` 3 用例 + 新增 `TenantProvisionRuntimePrerequisitesIT` 2 用例：**5/5 PASS，skipped=0**。后者用 `app_agent`(NOBYPASSRLS) 跑真实 `AdminTenantRepository.provisionTenant`，断言绑定 GUC 后两行都在、不绑定时查不到（反证 FORCE RLS 非假绿），用完即删租户 |
| 前置门禁 | `scripts/e2e-preflight.sh --no-health` 退出 0（5 项阻断全 PASS）；默认含健康项时退出 1（平台未启动），`--require-platform-registry` 反证退出 1；`provision_tenant_runtime.py --slug t-e2e-fe` 幂等补齐 **+0/+0/+0** |
| 巡检 | `check-tenant-registry.sh` 在本地 `agent_dev` 与远程 E2E 库均能跑通，逐租户绑定 GUC 后正确报出存量漂移 |
| 二轮 · 存量归零 | `provision_tenant_runtime.py --all --dry-run`（预览 28 行）→ `--all`（实写）→ `--all --check-only`（0 行）；本地巡检 6 项全绿 |
| 二轮 · 严格门禁 | `--require-platform-registry` 6 项全 PASS；双 slug 反证报 MISS 且退出非 0 |
| 二轮 · 浏览器复跑 | `fullchain_browser.py` **9/9 PASS**，真实 Run `d6c8f6bc…` DB 终态 `SUCCEEDED` / `error_code=NULL` / outbox `PUBLISHED` |
| 二轮 · T3 | `t3_real_click_recheck.py` A 不跳转 / B `focus+Enter` 提交成功，`t3_real_click.json` 留证 |
| 二轮 · 回归 | 前端 50 文件 / 729 用例；后端 13 模块 BUILD SUCCESS（3250 testcase）；`git diff --check` 干净 |

### 6.3 本轮新发现 / 残留风险

1. **两张租户注册表漂移 —— 18:00 已提供补齐能力并本地归零**：`t-e2e-fe` 原本只在 `public.tenant`（远程库 30 个 `it-*` / E2E 租户同型），源于 `AdminTenantRepository` 只写业务侧登记 + 测试夹具裸 INSERT。新增 `--register-platform`（默认 DRY-RUN、必须 `--apply`、只补缺失半边不覆盖已有状态）后，本地 5 个租户 + 5 条 quota 补齐、巡检 6 项全绿；远程仅对 `t-e2e-fe` 精确 `--apply`，`--require-platform-registry` 严格门禁 6 项全 PASS。前置自检默认仍作**告警而非阻断**（Run 执行链不依赖它），避免「Run 明明能跑却报假失败」把门禁关掉。远程剩余 29 个 `it-*` 夹具残留租户未动，需运维确认后再决定清理或批量补齐。
2. **存量租户 ⑤⑥ —— 18:00 已闭环（本地库）**：新增 `--all` 批量幂等补齐，本地 `agent_dev` 一次补齐 28 行（budget 8 / worker 9 / 主体 11），`--all --check-only` 归零自证 0 行；`check-tenant-registry.sh` 本地 6 项全绿。远程共享 RDS 为尊重运维边界只做 `--dry-run` 预览（57 行缺口，含 30 个 `it-*` 夹具残留租户），未批量写；目标租户 `t-e2e-fe` 单独幂等复验 +0/+0/+0。
3. **平台服务 —— 17:56 已重启**：`8080` health UP。⚠️ 重启过程抓到上一轮引入的**配置冲突**：`AGENT_TENANT_SLUGS=t-a,t-e2e-fe`（2 个）触发 `deployment.pilot.max-tenants=1` 的 fail-closed 上限，服务根本起不来；已收敛为单租户 `t-e2e-fe`，并把「清单长度 ≤ 上限」加进 E2E 硬门禁（含反证）。详见 §6.4。
4. **发布判定不变**：P0 与 N1、浏览器级 E2E 复跑、T3 均已落地，但旧报告 10 项遗留未重判、多租户灰度 / 治理审批并发 / 预算闸门边界 / 真实模型失败路径未覆盖，PARENT/TEACHER 本版本不开放。仍维持 §五的「单租户试点演示可用、不具备规模化发布条件」。
5. **N1 的账号体系侧未清理**：历史 `PARENT/TEACHER` 账号仍可登录，登录后的展示已改为明确终态说明页（不再给任何业务入口）；若产品决定彻底移除角色常量，需另立改动并同步授权矩阵与账号种子。

### 6.4 二轮优化（17:46–18:02）

用户指令「继续优化」，按 §6.3 残留逐项闭环：

| 项 | 结果 | 证据 |
|---|---|---|
| 存量租户运行前置 | ✅ 本地归零 | `--all --dry-run` 预览 28 行 → `--all` 实写 → `--all --check-only` 0 行；巡检 ⑤⑥ 归零 |
| 治理登记漂移 | ✅ 能力 + 本地/目标租户归零 | 新增 `--register-platform`（默认 DRY-RUN、必须 `--apply`）；本地补 5 租户 + 5 quota，巡检 6 项全绿；远程仅对 `t-e2e-fe` `--apply`，严格门禁 6 项全 PASS |
| 失效指引 | ✅ 已修 | `patch_platform_tenant_registration.sql` 早已删除但 5 处文档/巡检代码仍引用；全部改为「已移除 + 等价脚本」 |
| 平台重启 | ✅ 17:56 | `8080` health UP，PG 队列 `tenants=1`，RabbitMQ 已连，启动自检通过 |
| 浏览器 E2E 复跑 | ✅ 9/9 PASS | `fullchain_browser.py` 真实 Run `d6c8f6bc…`，DB 终态 `SUCCEEDED` / `error_code=NULL` / outbox `PUBLISHED` |
| T3 真实手势 | ✅ 已复核 | `t3_real_click_recheck.py`：A 合成 click 不跳转、B focus+Enter 提交成功 → 工具口径，非应用缺陷 |

**二轮抓到的真实缺陷（上一轮引入）**：`platform.env.example` 一边写「每开通租户就追加 slug」，一边写「受 `deployment.pilot.max-tenants` 约束」，而该上限在 `application.yml` 硬编码为 1；上一轮把本机 `AGENT_TENANT_SLUGS` 配成 `t-a,t-e2e-fe`，直接导致 `platform-bootstrap` 启动失败（`messagingChannels` fail-closed，8080 502），且这个失败**不是 Run 失败而是服务起不来**，很容易被误判成别的问题。修法：① 本机收敛为单租户 `t-e2e-fe`；② 模板与启动指南改为「只放当前试点租户，换租户是改值不是追加，扩量须同步上调上限并走灰度评审」；③ `e2e-preflight.sh` 新增「灰度清单不超试点上限」硬门禁，反证（模拟双 slug）确实报 MISS 并退出非 0。

**二轮新观察（当时未改动）**：近 2 天 9 个 `SUCCEEDED` Run 的 `run_step` 全部 `state='RUNNING'`、`completed_at` 非空但 `output_payload` 为空，且无一 `state<>'RUNNING'`。当时定性为「执行链状态机问题，需单独立项」。⚠️ **三轮复核已推翻该定性**：这是有意的占位口径 + 跨层契约漂移，不是漏写状态迁移；已在 §6.5.1 完成定性、修复与守卫，不需要另立状态机改造项目。

### 6.5 三轮优化（18:06–18:35）

用户再次指令「继续优化」。本轮不扩面到需要新环境的「多租户灰度 / 并发 / 真实模型失败」验证，而是把 §6.4 留下的**可在现有环境闭环**的三项做实，并对每个修复点做真实撤点反证。

#### 6.5.1 `run_step` 口径：从「待立项疑点」到定性 + 修复

只读排查（DDL / 两条写入链 / 执行循环 / 读侧 / 全部相关测试）结论：

- `run_step.state` 复用 `public.run_state`，**不存在独立步骤状态枚举**；`JdbcRunStepRepository.java:87`、`JdbcAgentChatAuditRepository.java:101` 把每一步**单条 INSERT 写死 `'RUNNING'::run_state`**，`started_at` 与 `completed_at` 同时落库，`commitResult()` 只更新 `run` 表。故「RUNNING + completed_at 非空」是**有意的占位口径**（表示「该步骤已发生」），不是漏写状态迁移；`AgentChatAuditIT` 还显式断言了它。
- 真正的缺陷是**跨层契约漂移**：口径只存在于两个 Java 注释和一个 IT，DDL 无 COMMENT、API DTO 写「步骤状态」、前端按字面显示「执行中」、测试夹具反倒用 `SUCCEEDED` + 有正文；`output_payload` 恒 NULL 是「无写入方」，不是「空内容」。结果页因此把已完成步骤显示成「执行中」、无绿点、正文不渲染；P7 指标 / Trace 回放 / 后台报表不读 state/output，不受影响。
- 修复走**最小改动路线**（不改枚举、不做数据迁移、不动两条执行链）：① `01_platform_ddl.sql` 加 3 条 COMMENT 把口径入库；② `RunStepPort` / `ResultQueryPort.Step` / `ResultController.StepResponse` / `JdbcResultQueryRepository` / `frontend/src/api/types.ts` 写明「RUNNING ≠ 进行中、output 恒 null＝未采集」；③ `RunResultPage` 把 RUNNING 映射为「已完成」+ 绿点，并告知「步骤只记录类型与耗时，逐步讲解看上方」；④ 前端 fixture 与两个 Trace IT 夹具统一为生产口径，`AgentChatAuditIT` 补「完成靠 completed_at」断言，`AgentChatProductionGuardTest` 补 `'RUNNING'::run_state` 锚点；⑤ 新增 `frontend/tests/runStep.contract.test.ts` 跨层守卫（含撤锚点反证）。
- ⚠️ 不做「备选路线」（新增 `run_step_state` 枚举 + 两阶段 UPDATE + 存量回填）：收益仅是结果页步骤区展示，却要动 DDL 快照、15→16 枚举自检、触发器、两条执行链、前后端与十余个测试；且 `output_payload` 内容从未产生过，**无法回填**。若将来有「步骤级内容复盘」需求再单独立项。

#### 6.5.2 真实缺陷：状态读取 403/401 仍被当作临时故障（铁律 15 漏网）

排查失败路径覆盖时发现：`useRunStatus` 只对 404 停轮询，`shouldShowSyncing` 只排除 404，`statusReadIsTerminal` 又定义在 `shouldShowSyncing` 之后无法复用 ⇒ 403/401 时页面**同时**显示「正在处理」「正在同步最新状态…」与「重试不会改变结果」，还每 10 秒继续烧 EVENT_QUERY 配额；SSE 轮询也没停。这与 F8 修的 404 是同一族，F8 当时只覆盖了 404。

修复：① `useRunStatus` 用 `errorGuidance(normalized).retryable` 作单一真相源，所有语义终态错误停状态轮询（404 仍单列 `notFound`）；② `RunProgressPage` 把 `statusGuidance` / `statusReadIsTerminal` 上移，`shouldShowSyncing`、状态标签（「无法查看状态」）、降级占位、SSE 停轮询全部纳入终态判定，刷新按钮继续 disabled。

#### 6.5.3 旧报告 10 项遗留重判（放行条件 ③）

以当前源码 + 本轮实测证据逐条重判 `frontend/E2E_TEST_REPORT.md`（2026-10-30）：

| # | 旧主张 | 当前结论 | 证据层级 | 分类 |
|---|---|---|---|---|
| 1 | 登录缺表单验证反馈 | 修复前已有 `ErrorView`；现 `isComplete` + 必填提示 + 按钮 disabled | 实测 DOM `snaps/admin/invalid_login.txt` | 不成立（实测反证） |
| 2 | 空 Agent 列表无提示 | 有/无 Agent 双分支，实测下拉预选 v2 与「没有可用的 Agent」 | 实测快照两种形态 | 已修复 |
| 3 | 进度页 SSE 轮询可能内存泄漏 | 卸载/终态双停 + `stopped`/`cancelled` 守卫 | 源码复核 + 单测，无行为级实测 | 已修复 |
| 4 | 结果页敏感词扫描阻塞渲染 | `useMemo` + `slice(0,10000)` | 源码复核，无性能实测 | 已修复 |
| 5 | 报表 90 天被静默截断 | 下拉是 `[7,14,30,90]` 封闭枚举，上限＝后端上限，旧主张前提自相矛盾 | 渲染快照 `snapshots/admin/reports.txt` | 不成立 |
| 6 | 租户标识 localStorage 无过期 | 已加 7 天 TTL；但**两页共用 key 却格式冲突**（见 6.5.4） | 源码复核，无读写/过期实测 | 已修复（附带新缺陷） |
| 7 | 配置写操作无二次确认 | `window.confirm` + 变更理由必填；单测必须 mock confirm 才能跑通 | 渲染快照 + 单测 | 已修复 |
| 8 | 审计页无 CSV 导出 | 「导出 CSV」+ `downloadCsv`（BOM / 公式注入防护） | 渲染快照，点击/下载未实测 | 已修复 |
| 9 | 健康页缺组件级明细 | 仍缺，但页面**刻意不画假数据**并登记 `blockedBy G14 / P4`；后端未暴露 `/actuator/health/components` | 实测快照 `snapshots/admin/health.txt` | 产品增强项（需后端 + 产品定夺） |
| 10 | 批量导入无进度提示 | 分批 10 行 + `importProgress` + `<progress>` | 渲染快照，真实导入未实测 | 已修复 |

**根因级发现（本次新增）**：git 提交 `689aaa5`（2026-10-30 10:38，旧报告自述 09:35 后约 1 小时）以「修复 E2E 测试发现的 10 个问题」为提交信息，把旧报告 10 条解决方案**逐条落地**（11 文件 / +180 −13，附「551 个测试全部通过」）；旧报告示例代码与当前源码逐字对应。所以 11-14 实测看到的是**修复后形态**，这才是 §六 表格 7 项判「无法复现」、F5 判「多数未获支持」的真实原因 —— **不是缺陷天然不存在，而是形态已变**，此前两轮报告都未归因到该提交。唯一未按旧方案实现的是问题 9（用 JWKS 替代健康检查）。

**诚实边界**：分类为「不成立」2 / 「已修复」7 / 「产品增强项」1 / 「当前未修真实缺陷」0。但 10 项中只有 7 项有渲染层 DOM 证据，问题 3/4 完全无行为级实测（内存泄漏/性能无法由快照证明），问题 5/6/7/8/10 的深度交互（切区间、localStorage 过期、确认框、导出下载、真实导入）仍未实测。**重判 ≠ 全覆盖实测**，故不据此放宽发布判定。

#### 6.5.4 附带修复：RAG 两页共用 localStorage key 的格式冲突

`RagSearchPage` 写 `{value,timestamp}` JSON + 7 天 TTL，`RagManagementPage` 对**同一个** `rag.tenantId` 裸写/裸读字符串 ⇒ 管理页写完检索页 `JSON.parse` 抛错被吞（租户标识变空），检索页写完管理页把 JSON 当 tenantId 发给后端。两处「各自看着都对」的实现合起来形成跨页串写。修：抽取 `frontend/src/features/rag/ragTenantStorage.ts` 为唯一读写方（统一 JSON + TTL + **兼容历史裸字符串**），两页改 import；`runStep.contract.test.ts` 加「两页不得再直接读写 localStorage」守卫。严重度低（租户 UUID 非凭据，且两页都优先用会话权威 tenantId），但属真实缺陷。

#### 6.5.5 失败路径自动化门禁 + 撤点反证

补了此前完全没锁的组合：

- `errorGuidance.test.ts`：6 个 service-config 码、12 个 transient 码**全量**；后端真实写入但前端未登记的 `MAX_LLM_CALLS` / `MAX_COST` / `TOOL_INTERNAL` / `CONTEXT_*` / `SAFETY_BLOCK` / `MAX_ROUNDS` 落 unknown fail-closed；`readableFailureReason` 三类码 + trim；403/404/401 被改写成 5xx/0/409 仍为终态。
- `progress.page.test.tsx`：403/401 终态与双链停轮询、5xx 降级反证、5xx→403 收敛；`FAILED + 6 个租户级码 / 未知真实码 / 空码 / 临时故障码` 的 CTA；`TIMED_OUT` / `CANCELLED` 独立标签与停轮询。
- `runState.test.ts`：`CANCELLED` / `TIMED_OUT` 忽略 errorCode，反证「合并进 FAILED 会红」。
- `result.page.test.tsx`：夹具改为生产口径（`RUNNING` + `output:null`），新增 T8 标签/绿点/历史兼容断言。

**真实撤点反证**（`revoke_check_round3.py` + `revoke_check_round3.json`，带信号处理器强制复原）：4 个修复点逐个撤掉并跑对应门禁，**4/4 全部转红**；复原后 `git diff --check` 干净、锚点逐字节一致。

| 撤点 | 结果 |
|---|---|
| `RUNNING: '已完成'` → `'执行中'` | ✅ 红 |
| 删 `shouldShowSyncing` 的 `!statusReadIsTerminal` | ✅ 红 |
| `useRunStatus` 退回「只有 404 停轮询」 | ✅ 红 |
| 分类表删 `CONFIG_MISSING` | ✅ 红 |

#### 6.5.6 回归与仍待办

- 前端：`tsc --noEmit` 通过；`vitest run` **51 文件 / 776 用例全绿**（上轮 729，本轮 +47）。
- 后端：`mvn -o -f platform/pom.xml test` 全 reactor 通过。DDL 增加的仅是 COMMENT，不改表/列/枚举，存量库无需数据回填，COMMENT 随下次结构发布同步。
- **发布判定维持不变**：单租户试点演示可用，不具备规模化发布条件。
- **仍待办**：多租户灰度 / 治理审批并发 / 预算闸门边界 / 真实模型失败路径；旧报告 10 项中问题 3/4/5/6/7/8/10 的行为级专项实测；账号体系彻底移除 `PARENT/TEACHER` 常量；远程共享 RDS 剩余 29 个 `it-*` 夹具残留租户（需运维确认）。

### 6.6 四轮优化（18:42–19:10）：7 项行为级专项实测

用户第三次指令「继续优化」。§6.5.6 的仍待办里，多租户灰度 / 治理审批并发 / 预算闸门边界 / 真实模型失败路径需要新环境或真实模型，`PARENT/TEACHER` 常量移除受 N1「本版本不开放」裁决约束，远程 29 个 `it-*` 残留租户需运维确认——**唯一能在现有环境闭环且直接对应放行条件**的是「旧报告 10 项中问题 3/4/5/6/7/8/10 的行为级专项实测」。本轮只攻这一项，不伪造其余覆盖。

#### 6.6.1 最重要的方法论结论：两处「已修复」其实是运行时不成立

只读探查 + 真实交互驱动后，§6.5.3 表格里判「已修复」的 **问题 4 与问题 10 被推翻**：

| 项 | §6.5.3 判定依据 | 行为级实测发现的真实缺陷 |
|---|---|---|
| **问题 4** 结果页敏感词扫描 | 「`useMemo` + `slice(0,10000)`」+ 短文本身份证拦截的**渲染快照** | ① `explanation.slice(0, 10000)` **截断后才扫描**，而 `<ModelText>` 渲染的是**完整文本** ⇒ 第 10001 字符之后的身份证 / 手机号既不扫也照常显示，C6 合规红线被绕过；② 只喂 `{ explanation }`，而 `weaknesses[].knowledgePoint` / `errorType`、`steps[].output`、`reconciliation.operations[].toolName`、`errorCode` 都会渲染却完全不扫。`slice` 的「避免长文本阻塞渲染」初衷不成立（`useMemo([result])` 已保证每次结果只扫一遍，正则 O(n)），却把红线开了口子 |
| **问题 10** 批量导入进度 | 渲染快照里有 `<progress>` 元素 | `useRagManagement.bulkRegister` 是 **fire-and-forget**（`void run()` 后返回 `undefined`），页面却 `await` 它 ⇒ ① `catch` 永不触发、失败被静默当成成功；② 所有批次在**同一微任务链**递归完成，`setImportProgress` 被 React 18 批处理合并，进度条**运行时根本不可观察**；③ 每批覆写 `bulk.response`，25 行分 3 批后结果表只剩最后 5 行，「逐行核对」承诺失效；④ 防重只靠 fire-and-forget 的 `bulk.status`，连点会重复提交整批且失败后重试从第 0 行重发（无幂等键） |

⚠️ 这两处此前都是**静态源码守卫 / 渲染快照**判绿。教训与 T1（「API 级 PASS 不能替代业务链路验收」）同族并进一步收紧：**「页面上有这个元素 / 代码里有这个函数」不能证明这条业务链在运行时成立**。已把这条写进项目铁律。

#### 6.6.2 修复（最小改动，不动枚举 / 不动后端 / 不扩面）

- **问题 4**：`RunResultPage.tsx` 去掉 `slice`，改为扫**全文 + 全部自由文本字段**（讲解 / 错因归类 / 解题步骤 / 待核对），命中字段按区块**精准拦截**（不整页报错，沿用 T7「一次偶然复述学号不该让整页不可用」）；⛔ **结构性标识不进扫描文本**——`STUDENT_ID` 规则是「8–12 位连续数字」，把 `operationId`(UUID) / `confidence` 小数拼进去会让**每个正常 Run 都假阳性**；新增 T9 用例显式钉住这条。
- **问题 10**：`useRagManagement.bulkRegister` 改为 `async` 返回显式 `BulkRegisterOutcome`（`ok / response / error`），绝不吞掉错误；`RagManagementPage` 改为**逐批 await 真实结果**推进、累积逐行结果并把批内 `index` 加上批次偏移对回原始行号、失败即停在已成功行数并显式「导入失败」+「续传剩余 N 条」、`importingRef` **同步防重**；结果表改用页面累积状态作为单一真相源。

#### 6.6.3 七项行为级实测逐项结论

| 项 | 之前的证据层级 | 本轮行为级验证 | 结论 |
|---|---|---|---|
| 3 进度页轮询清理 | poller 单元用例 + 源码复核，**无组件卸载** | `progress.page.test.tsx` T9：fake timers 下 `unmount()` 后推进 60s，两条轮询链调用数不变、`vi.getTimerCount()===0`；反向锚点「不卸载时调用数确实增加」 | 实现成立；⛔ 真实 GC / 堆残留不可由 jsdom 证明 |
| 4 结果页敏感词扫描 | 渲染快照 + 纯函数单测 | `result.page.test.tsx` T9：10000 字符后尾部身份证、错因 / 步骤正文 / 工具名字段命中、正常长文本不误杀、UUID 不假阳性 | **修缺陷 + 补门禁** |
| 5 报表区间切换 | 只断言默认 30 天 | `reports.page.test.tsx`：切到 90 天四个端点都带 `days=90`；deferred promise 让**新响应先回、旧响应后回**，断言旧响应不覆盖新响应 | 实现成立；补齐切换与竞态实测 |
| 6 localStorage TTL | **仅静态源码文本守卫** | 新增 `ragTenantStorage.test.ts`：`vi.setSystemTime` 时间旅行 7 天 / 7 天+1ms、脏 JSON fail-closed、历史裸值兼容、TTL 常量值 | 实现成立；把「代码里有 TTL 字样」升级为真实过期行为 |
| 7 配置写二次确认 | 提交链，`confirm` 恒 mock `true` | `configWrite.page.test.tsx` F3b/F3c：局部改 `false` 真实验证取消后**不发请求、编辑器不丢现场**；反向锚点 `true` 时确实提交 | 实现成立；补上从未测过的取消路径 |
| 8 审计 CSV 导出 | `reports.page.test.tsx` 把整个 csv 模块 **mock 掉** | 新增 `csv.test.ts`：真实模块的 BOM / 四类公式前缀 / 转义 / 空 rows / Blob+anchor+revoke 拦截；`governance.page.test.tsx` 补页面级点击与「空数据不渲染按钮」 | 实现成立；⛔ 真实落盘 / Excel 打开不乱码不可证明 |
| 10 批量导入进度 | 渲染快照有 `<progress>` | 新增 `bulkImport.behavior.test.tsx`：deferred 逐批驱动 **25 行 → 串行 10/10/5**、中间进度 10/20、第 2 批失败停 + 续传只发剩余 15 行、连点两次只发一套请求 | **修缺陷 + 补门禁**；⛔ 后端真的逐批收到不可证明 |

#### 6.6.4 撤点反证与回归

`revoke_check_round4.py` / `.json`（带 SIGINT/SIGTERM 处理器强制复原、逐字节基线比对 + `git diff --check`）：**6/6 撤点全部转红**。

| 撤点 | 结果 |
|---|---|
| 结果页退回「只扫 explanation 前 10000 字符」 | ✅ 红 |
| 删掉「错因归类」按字段拦截 | ✅ 红 |
| hook 把 `bulkRegister` 退回 fire-and-forget | ✅ 红 |
| 页面退回「只显示最后一批结果」 | ✅ 红 |
| 删掉 `rag.tenantId` 的 TTL 过期判定 | ✅ 红 |
| 删掉 CSV 公式注入中和 | ✅ 红 |

⚠️ 撤点脚本首轮曾因「用子串判定撤点形态残留」误报（R4-5 的 `return parsed.value` 本就是原文的一部分），已改为**逐字节与启动前基线比对**——这是唯一可靠的复原证据，不靠模式匹配。

回归：`tsc --noEmit` 通过；前端 **54 文件 / 806 用例全绿**（三轮 776 → +30）；后端 `mvn -o -f platform/pom.xml test` 全 reactor **BUILD SUCCESS**（本轮未动后端，按铁律仍跑）；`git diff --check` 干净。

#### 6.6.5 诚实边界（不放宽发布判定的理由）

本轮采用的是 **jsdom + mock HTTP 层**的行为级验证，以下四类**明确不能**由它证明，不得冒充覆盖：

1. 真实内存泄漏（堆增长 / GC 后残留 / 真实浏览器里的定时器与 `EventSource` 句柄）——能证明的只有「不再发新请求 + 定时器归零」。
2. 真实性能（长文本是否阻塞主线程、扫描耗时）——jsdom 无布局绘制，任何 `performance.now()` 阈值断言都是伪覆盖。
3. 真实下载（文件落盘、Excel/WPS 打开是否乱码、公式是否真被拦截执行）——能证明的只有 Blob 文本与下载动作参数。
4. 后端是否真的逐批收到、真实网络时序——能证明的只有前端分批编排 / 进度 / 失败语义 / 结果累积。

此外：问题 9（健康页组件级明细）仍是产品增强项，后端未暴露 `/actuator/health/components`，`blockedBy G14 / P4`；`governance.page.test.tsx` 既有用例 F4 有 3 条 React `act(...)` 警告（非本轮引入，属既有测试卫生，未在本轮改动范围）；多租户灰度 / 治理审批并发 / 预算闸门边界 / 真实模型失败路径**仍完全未覆盖**。

**仍待办**：多租户灰度 / 治理审批并发 / 预算闸门边界 / 真实模型失败路径（需新环境与真实模型）；上述四类行为级验证之外的真实环境专项；问题 9 需后端 + 产品定夺；账号体系彻底移除 `PARENT/TEACHER` 常量（受 N1 裁决约束）；远程共享 RDS 剩余 29 个 `it-*` 夹具残留租户（需运维确认）。**发布判定维持：单租户试点演示可用，不具备规模化发布条件。**

### 6.7 五轮优化（2026-11-14 19:35 – 2026-11-15 01:10）：7 项前端韧性与交互层真实缺陷

用户第五次指令「继续优化」。前四轮已覆盖：F1–F8 功能缺陷、N1–N6 裁决、T1–T4 门禁、`run_step` 跨层口径、进度页 403/401 终态、旧报告 10 项重判、7 项行为级专项实测。本轮不在已覆盖区重复，而是继续在「错误韧性 / 全局基础设施 / 未覆盖页面」里找真实缺陷（XSS 与认证两块静态 + 运行时探查均干净，未在此列扩面）。

#### 6.7.1 发现的 7 个真实缺陷

| 编号 | 缺陷 | 位置 | 修复 |
|---|---|---|---|
| DEF-1 | 无顶层 ErrorBoundary：子组件（含 AppShell / 导航 / 守卫）渲染抛错会冒泡到根，整棵 `<App/>` 被卸载，浏览器只剩空 `<div id="root">`（全白屏），连「退出」都点不到 | `App.tsx` 未包边界 | 新增 `AppErrorBoundary` + `FatalFallback`，`AppRoutes` 外层包裹；兜底按钮用 `window.location.assign('/login')` 整页跳转，避开可能已坏的 Router 上下文 |
| DEF-2 | 无全局未捕获错误安全网：`main.tsx` 没订阅 `window.onerror` / `unhandledrejection`，一个未被 try/catch 兜住的 Promise 拒绝会静默失败、零遥测 | `main.tsx` | 新增 `installGlobalErrorHandlers()`（纯日志、不改 UI 防二次崩溃），`main.tsx` 调用 |
| D1 | 配置「恢复默认」无二次确认：点一下即清掉租户覆盖值，用户无反悔机会（与「提交变更」同纪律应二次确认） | `ConfigOverrideRow.tsx` | `onClear(item.key)` 外包 `window.confirm`，拒绝则不调用 |
| D2 | 治理翻页失败污染首屏 error：`loadMore` 失败时写全局 `error`，已加载并展示的列表被整页 ErrorState 替换、已见条目消失 | `useGovernancePage.ts` | 翻页失败只记独立 `loadMoreError`，不写全局 error；页面用横幅展示并可重试 |
| D3 | AgentsPage 视图间错误串味：发布失败的错误在切到「新建 Agent」视图后仍残留（反之亦然），误导用户 | `ConfigPages.tsx` | 切换「新建 / 发布」视图时 `setActionError(null)` |
| D6 | 审计明细对非 Record 崩溃：`renderDetail` 对 `null / 字符串 / 数组` 调 `Object.entries` 抛错，把整页审计列表崩成错误态 | `GovernancePages.tsx` | 加类型守卫，非对象一律降级为占位符 `'—'` |
| D7 | 登录回跳丢 query：守卫 `state.from` 只写 `location.pathname`，从带查询参数页面（如 `/config?tab=agents`）被踢登录再回来丢失 query | `RequireAuth/RequireRole/RequireTask.tsx` | `state={{ from: location.pathname + location.search }}` |

#### 6.7.2 行为级门禁（每个修复点一把锁）

- `appErrorBoundary.test.tsx`：抛错 → 整页兜底不白屏；点「返回登录页」清会话 + 整页跳 `/login`；不抛错时不出现兜底（反证）
- `globalErrorHandlers.test.ts`：捕获 error 与 unhandledrejection 并交 reporter；uninstall 后不再回调；默认 reporter 为 console.error
- `configOverrideRow.test.tsx`：点「恢复默认」先弹确认；确认被拒时不调用 onClear；确认通过调用 onClear(key)
- `useGovernancePage.test.ts`：翻页失败只记 loadMoreError、保留列表、不污染首屏 error；首屏失败仍写 error 并清列表
- `agentsPageErrorSwitch.test.tsx`：发布失败后切到新建视图，错误提示消失
- `renderDetail.test.ts`：null / 字符串 / 数组 / 数字 / 空对象 → `'—'`；标量键值拼接、嵌套摘要、null 字段跳过
- `authGuardRedirect.test.tsx`：RequireAuth / RequireRole / RequireTask 未登录跳 `/login` 且 `state.from` 含完整 query

#### 6.7.3 撤点反证（7/7 全红，逐字节复原）

`revoke_check_round5.py` / `.json`（沿用逐字节基线比对 + 信号处理器强制复原 + `git diff --check`）：**7/7 撤点全部转红**，复原后 `old 锚点=1 / 残留=0 / diff --check 干净`。

| 撤点 | 结果 |
|---|---|
| AppErrorBoundary 不再捕获渲染异常 | ✅ 红 |
| installGlobalErrorHandlers 不安装监听 | ✅ 红 |
| 配置「恢复默认」去掉二次确认 | ✅ 红 |
| 治理翻页失败改回 setError | ✅ 红 |
| AgentsPage 切视图不清 actionError | ✅ 红 |
| renderDetail 删类型守卫 | ✅ 红 |
| 路由守卫 state.from 丢 search | ✅ 红 |

#### 6.7.4 回归与诚实边界

回归：`tsc --noEmit` 通过；前端 **61 文件 / 832 用例全绿**（四轮 54→806，本轮 +26 至 832）；后端本轮未改动，沿用 17:40 全 reactor BUILD SUCCESS 基线；`git diff --check` 干净。

诚实边界：本轮仍为 jsdom + mock HTTP 层行为级验证，以下不能由它证明：① 真实内存泄漏 / GC 后残留；② 真实性能（长文本主线程阻塞）；③ 真实下载落盘 / Excel 打开不乱码；④ 后端真实逐批收到。多租户灰度 / 治理审批并发 / 预算闸门边界 / 真实模型失败路径**仍完全未覆盖**。

**发布判定维持不变**：单租户试点演示可用，不具备规模化发布条件。

---

## 附录：本轮证据索引

| 文件 | 内容 |
|---|---|
| `probe_eval_round2.py` | API 级 8 项：学生画像 403、家长创建 403、家长列表 0 条、家长读学生 Run 404、失败码分布、列表收窄 10 vs 13 |
| `eval2_consistency.py` | 三角色 × 6 入口一致性走查（STUDENT 完整、PARENT 至导航；进程中断，见 T4） |
| `eval2.json` / `eval2_text.json` | 结构化结果 |
| `snapshots/eval2/STUDENT_*.txt` | 工作台 / 检索记录 / 发起辅导 / 学习画像 / 报表中心 / 知识检索 DOM 快照 |
| `snapshots/eval2/PARENT_01_nav.txt` | 家长导航与「发起辅导」入口（与学生逐行相同） |
| `screenshots/eval2/*.png` | 对应截图 |
| 源码 | `RoleBasedAuthorizationService.java`、`RunListController.java:97-103`、`ProfileController.java:85-92`、`AppShell.tsx:68-85`、`NewRunPage.tsx`、`RunProgressPage.tsx`、`RunResultPage.tsx`、`runState.ts:89-102`、`idempotency.ts`、`useProfile.ts` |
| `t3_real_click_recheck.py` / `.json` | T3 三种提交手势证据（合成 click 不提交、focus+Enter 提交成功） |
| `revoke_check_round3.py` / `revoke_check_round3.json` | 三轮 4 个修复点的真实撤点反证（4/4 转红、强制复原） |
| `frontend/tests/runStep.contract.test.ts` | `run_step` 跨层口径守卫（DDL / 写入方 / 契约 / 读侧 / RAG 存储） |
| `frontend/tests/progress.page.test.tsx` / `errorGuidance.test.ts` / `runState.test.ts` / `result.page.test.tsx` | 三轮失败路径与终态门禁（+47 用例） |
| `frontend/src/features/rag/ragTenantStorage.ts` | RAG 两页共用租户标识存储（统一 JSON + TTL + 历史裸值兼容） |
| `revoke_check_round4.py` / `revoke_check_round4.json` | 四轮 6 个修复点的真实撤点反证（6/6 转红、逐字节基线复原 + 信号处理器） |
| `frontend/tests/ragTenantStorage.test.ts` | 问题 6：TTL 真实时间旅行、脏数据 fail-closed、历史裸值兼容 |
| `frontend/tests/csv.test.ts` | 问题 8：真实 CSV 模块的 BOM / 公式注入 / 转义 / 下载动作链 |
| `frontend/tests/bulkImport.behavior.test.tsx` | 问题 10：deferred 驱动 25 行串行 10/10/5、进度、失败停与续传、防重 |
| `frontend/tests/result.page.test.tsx` T9 | 问题 4：全文 + 全字段扫描，长文本尾部与结构字段假阳性双向守卫 |
| `frontend/tests/progress.page.test.tsx` T9 / `reports.page.test.tsx` / `configWrite.page.test.tsx` F3b-F3c / `governance.page.test.tsx` | 问题 3/5/7/8 的行为级实测（卸载、切区间竞态、取消确认、导出点击） |
| 源码 | `RunResultPage.tsx`（全文全字段扫描 + 按字段拦截）、`useRagManagement.ts`（bulkRegister 显式结果）、`RagManagementPage.tsx`（串行分批 + 累积 + 续传 + 防重） |
